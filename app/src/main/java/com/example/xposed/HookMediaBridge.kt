package com.example.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ImageWriter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.example.model.MediaKind
import com.example.model.VirtualCamConstants
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class HookRuntimeState(
    val enabled: Boolean = false,
    val forceBypass: Boolean = false,
    val audioEnabled: Boolean = true,
    val micEnabled: Boolean = true,
    val loopVideo: Boolean = true,
    val mirrorHorizontal: Boolean = false,
    val rotationDegrees: Int = 0,
    val allowAllScopedApps: Boolean = true,
    val mediaKind: MediaKind = MediaKind.NONE,
    val mediaPath: String = "",
    val mediaMime: String = "",
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val mediaUpdatedAtMs: Long = 0L,
    val enabledPackages: Set<String> = emptySet()
) {
    fun isScopedTarget(packageName: String, processName: String = packageName): Boolean {
        if (allowAllScopedApps) return true
        val hostPkg = resolveHostPackage(packageName, processName)
        return enabledPackages.contains(packageName) ||
            enabledPackages.contains(hostPkg) ||
            enabledPackages.contains(processName.substringBefore(':'))
    }

    fun isActiveForPackage(packageName: String, processName: String = packageName): Boolean {
        if (!enabled || forceBypass || mediaKind == MediaKind.NONE) return false
        return isScopedTarget(packageName, processName)
    }

    companion object {
        fun resolveHostPackage(packageName: String, processName: String): String {
            // WebView sandboxed/host processes (e.g. omegle.tv or com.imo.android.imoim loading com.google.android.webview)
            if (packageName == "com.google.android.webview" || packageName == "com.android.webview") {
                val baseProc = processName.substringBefore(':')
                if (baseProc.isNotBlank() && baseProc != packageName) {
                    return baseProc
                }
                val appPkg = runCatching { AndroidAppHelper.currentPackageName() }.getOrNull()
                if (!appPkg.isNullOrBlank()) return appPkg
            }
            return packageName
        }
    }
}

data class ReaderSurfaceSpec(
    val width: Int,
    val height: Int,
    val format: Int,
    val maxImages: Int
)

/**
 * Shared media/config bridge used inside hooked camera & audio target processes.
 * Combines LSPosed New XSharedPreferences (API 93+) with VirtualCamMediaProvider IPC
 * and drives Surface Canvas + ImageWriter YUV_420_888 rendering at ~30 fps.
 */
object HookMediaBridge {
    private const val TAG = "[VirtualCam]"
    private const val STATE_CACHE_TTL_MS = 200L

    @Volatile
    private var cachedPrefs: XSharedPreferences? = null

    @Volatile
    private var lastStateReadMs: Long = 0L

    @Volatile
    private var cachedState: HookRuntimeState = HookRuntimeState()

    private val activeRenderers = ConcurrentHashMap<Int, VirtualSurfaceRenderer>()
    private val imageReaderSpecs = ConcurrentHashMap<Int, ReaderSurfaceSpec>()

    fun currentAppContext(): Context? =
        runCatching { AndroidAppHelper.currentApplication() }.getOrNull()

    fun registerImageReaderSurface(surface: Surface?, spec: ReaderSurfaceSpec) {
        if (surface == null) return
        imageReaderSpecs[System.identityHashCode(surface)] = spec
    }

    fun unregisterImageReaderSurface(surface: Surface?) {
        if (surface == null) return
        imageReaderSpecs.remove(System.identityHashCode(surface))
    }

    fun getImageReaderSpec(surface: Surface?): ReaderSurfaceSpec? {
        if (surface == null) return null
        return imageReaderSpecs[System.identityHashCode(surface)]
    }

    fun readRuntimeState(
        packageName: String,
        processName: String = packageName,
        context: Context? = currentAppContext(),
        forceRefresh: Boolean = false
    ): HookRuntimeState {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastStateReadMs) < STATE_CACHE_TTL_MS) {
            return cachedState
        }

        // 1. Primary: New XSharedPreferences (LSPosed Binder proxy when xposedsharedprefs=true)
        try {
            val prefs = cachedPrefs ?: XSharedPreferences(
                VirtualCamConstants.MODULE_PACKAGE,
                VirtualCamConstants.PREFS_NAME
            ).also {
                runCatching { it.makeWorldReadable() }
                cachedPrefs = it
            }

            if (prefs.file.canRead() || prefs.hasFileChanged()) {
                prefs.reload()
                val loaded = HookRuntimeState(
                    enabled = prefs.getBoolean(VirtualCamConstants.KEY_ENABLED, false),
                    forceBypass = prefs.getBoolean(VirtualCamConstants.KEY_FORCE_BYPASS, false),
                    audioEnabled = prefs.getBoolean(VirtualCamConstants.KEY_AUDIO_ENABLED, true),
                    micEnabled = prefs.getBoolean(VirtualCamConstants.KEY_MIC_ENABLED, true),
                    loopVideo = prefs.getBoolean(VirtualCamConstants.KEY_LOOP_VIDEO, true),
                    mirrorHorizontal = prefs.getBoolean(VirtualCamConstants.KEY_MIRROR_HORIZONTAL, false),
                    rotationDegrees = prefs.getInt(VirtualCamConstants.KEY_ROTATION_DEGREES, 0),
                    allowAllScopedApps = prefs.getBoolean(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, true),
                    mediaKind = MediaKind.fromWire(prefs.getString(VirtualCamConstants.KEY_MEDIA_KIND, null)),
                    mediaPath = prefs.getString(VirtualCamConstants.KEY_MEDIA_PATH, "").orEmpty(),
                    mediaMime = prefs.getString(VirtualCamConstants.KEY_MEDIA_MIME, "").orEmpty(),
                    mediaWidth = prefs.getInt(VirtualCamConstants.KEY_MEDIA_WIDTH, 0),
                    mediaHeight = prefs.getInt(VirtualCamConstants.KEY_MEDIA_HEIGHT, 0),
                    mediaUpdatedAtMs = prefs.getLong(VirtualCamConstants.KEY_MEDIA_UPDATED_AT, 0L),
                    enabledPackages = prefs.getStringSet(VirtualCamConstants.KEY_ENABLED_PACKAGES, emptySet())
                        ?: emptySet()
                )
                cachedState = loaded
                lastStateReadMs = now
                return loaded
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG XSharedPreferences read failed for $packageName ($processName): ${t.message}")
        }

        // 2. Fallback: Query exported VirtualCamMediaProvider
        if (context != null) {
            runCatching {
                context.contentResolver.query(
                    Uri.parse(VirtualCamConstants.ACTIVE_CONFIG_URI),
                    null,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        fun intCol(name: String, default: Int = 0): Int {
                            val idx = cursor.getColumnIndex(name)
                            return if (idx >= 0) cursor.getInt(idx) else default
                        }
                        fun longCol(name: String, default: Long = 0L): Long {
                            val idx = cursor.getColumnIndex(name)
                            return if (idx >= 0) cursor.getLong(idx) else default
                        }
                        fun strCol(name: String): String {
                            val idx = cursor.getColumnIndex(name)
                            return if (idx >= 0) cursor.getString(idx).orEmpty() else ""
                        }

                        val loaded = HookRuntimeState(
                            enabled = intCol(VirtualCamConstants.KEY_ENABLED) == 1,
                            forceBypass = intCol(VirtualCamConstants.KEY_FORCE_BYPASS) == 1,
                            audioEnabled = intCol(VirtualCamConstants.KEY_AUDIO_ENABLED, 1) == 1,
                            micEnabled = intCol(VirtualCamConstants.KEY_MIC_ENABLED, 1) == 1,
                            loopVideo = intCol(VirtualCamConstants.KEY_LOOP_VIDEO, 1) == 1,
                            mirrorHorizontal = intCol(VirtualCamConstants.KEY_MIRROR_HORIZONTAL) == 1,
                            rotationDegrees = intCol(VirtualCamConstants.KEY_ROTATION_DEGREES, 0),
                            allowAllScopedApps = intCol(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, 1) == 1,
                            mediaKind = MediaKind.fromWire(strCol(VirtualCamConstants.KEY_MEDIA_KIND)),
                            mediaPath = strCol(VirtualCamConstants.KEY_MEDIA_PATH),
                            mediaMime = strCol(VirtualCamConstants.KEY_MEDIA_MIME),
                            mediaWidth = intCol(VirtualCamConstants.KEY_MEDIA_WIDTH),
                            mediaHeight = intCol(VirtualCamConstants.KEY_MEDIA_HEIGHT),
                            mediaUpdatedAtMs = longCol(VirtualCamConstants.KEY_MEDIA_UPDATED_AT)
                        )
                        cachedState = loaded
                        lastStateReadMs = now
                        return loaded
                    }
                }
            }
        }

        return cachedState
    }

    fun openActiveMediaPfd(context: Context?, fallbackPath: String): ParcelFileDescriptor? {
        if (context != null) {
            runCatching {
                context.contentResolver.openFileDescriptor(
                    Uri.parse(VirtualCamConstants.ACTIVE_MEDIA_URI),
                    "r"
                )
            }.getOrNull()?.let { return it }
        }
        if (fallbackPath.isNotBlank()) {
            val file = File(fallbackPath)
            if (file.exists() && file.canRead()) {
                return runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }.getOrNull()
            }
        }
        return null
    }

    fun startRendererForSurface(
        surface: Surface?,
        packageName: String,
        processName: String = packageName,
        passthroughProvider: (() -> Bitmap?)? = null
    ) {
        if (surface == null || !surface.isValid) return
        val key = System.identityHashCode(surface)
        stopRendererForSurface(surface)

        val context = currentAppContext()
        val state = readRuntimeState(packageName, processName, context, forceRefresh = true)
        if (!state.isScopedTarget(packageName, processName)) return
        if (!state.isActiveForPackage(packageName, processName) && passthroughProvider == null) return

        val readerSpec = getImageReaderSpec(surface)
        // Skip JPEG still-capture ImageReaders from 30fps pumping
        if (readerSpec != null && readerSpec.format == ImageFormat.JPEG) {
            return
        }

        val renderer = VirtualSurfaceRenderer(
            surface = surface,
            packageName = packageName,
            processName = processName,
            readerSpec = readerSpec,
            passthroughProvider = passthroughProvider
        )
        activeRenderers[key] = renderer
        renderer.start(context, state)
    }

    fun stopRendererForSurface(surface: Surface?) {
        if (surface == null) return
        val key = System.identityHashCode(surface)
        activeRenderers.remove(key)?.stop()
    }

    fun stopAllRenderers() {
        activeRenderers.values.forEach { runCatching { it.stop() } }
        activeRenderers.clear()
    }

    fun encodeJpegFromActiveMedia(packageName: String, processName: String = packageName): ByteArray? {
        val context = currentAppContext()
        val state = readRuntimeState(packageName, processName, context, forceRefresh = true)
        if (!state.isActiveForPackage(packageName, processName)) return null
        val bitmap = VideoFramePipeline.getCurrentFrameBitmap(context, state) ?: return null
        val transformed = applyTransform(bitmap, state.mirrorHorizontal, state.rotationDegrees)
        return try {
            ByteArrayOutputStream().use { out ->
                transformed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
        } finally {
            if (transformed !== bitmap) transformed.recycle()
            bitmap.recycle()
        }
    }

    fun bitmapToNv21(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): ByteArray {
        val safeW = targetWidth.coerceAtLeast(2)
        val safeH = targetHeight.coerceAtLeast(2)
        val scaled = if (bitmap.width != safeW || bitmap.height != safeH) {
            Bitmap.createScaledBitmap(bitmap, safeW, safeH, true)
        } else {
            bitmap
        }
        val argb = IntArray(safeW * safeH)
        scaled.getPixels(argb, 0, safeW, 0, 0, safeW, safeH)
        if (scaled !== bitmap) scaled.recycle()

        val frameSize = safeW * safeH
        val yuv = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until safeH) {
            for (i in 0 until safeW) {
                val pixel = argb[j * safeW + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.size) {
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    fun applyTransform(source: Bitmap, mirrorHorizontal: Boolean, rotationDegrees: Int): Bitmap {
        if (!mirrorHorizontal && rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply {
            if (mirrorHorizontal) postScale(-1f, 1f)
            if (rotationDegrees % 360 != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

/**
 * Renders 30fps frames onto a target Surface:
 * - For ImageReader (YUV_420_888) surfaces: writes genuine Y/U/V frames via ImageWriter.
 * - For SurfaceView / SurfaceTexture / MediaRecorder surfaces: blits fitted frames via lockHardwareCanvas/lockCanvas.
 * - Supports live instant switching between Virtual Feed and Bypass/Real Camera via [passthroughProvider].
 */
class VirtualSurfaceRenderer(
    private val surface: Surface,
    private val packageName: String,
    private val processName: String,
    private val readerSpec: ReaderSurfaceSpec?,
    private val passthroughProvider: (() -> Bitmap?)?
) {
    private val running = AtomicBoolean(false)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var imageWriter: ImageWriter? = null
    private var consumerAcquired = false

    fun start(context: Context?, initialState: HookRuntimeState) {
        if (!running.compareAndSet(false, true)) return
        if (!surface.isValid) {
            running.set(false)
            return
        }

        if (initialState.isActiveForPackage(packageName, processName)) {
            VideoFramePipeline.acquireConsumer(context, initialState)
            consumerAcquired = true
        }

        // Attach ImageWriter only for YUV_420_888 ImageReader surfaces
        val spec = readerSpec
        if (spec != null && spec.format == ImageFormat.YUV_420_888) {
            runCatching {
                imageWriter = ImageWriter.newInstance(surface, spec.maxImages.coerceIn(2, 4))
            }.onFailure { err ->
                XposedBridge.log("[VirtualCam] ImageWriter attach skipped: ${err.message ?: err.javaClass.simpleName}")
            }
        }

        val thread = HandlerThread("VirtualCamSurfacePump").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val tick = object : Runnable {
            override fun run() {
                if (!running.get() || !surface.isValid) {
                    stop()
                    return
                }

                val liveState = HookMediaBridge.readRuntimeState(packageName, processName, context)
                val useVirtual = liveState.isActiveForPackage(packageName, processName)

                if (useVirtual && !consumerAcquired) {
                    VideoFramePipeline.acquireConsumer(context, liveState)
                    consumerAcquired = true
                } else if (!useVirtual && consumerAcquired) {
                    VideoFramePipeline.releaseConsumer()
                    consumerAcquired = false
                }

                val frameBitmap: Bitmap? = if (useVirtual) {
                    VideoFramePipeline.getCurrentFrameBitmap(context, liveState)
                } else {
                    passthroughProvider?.invoke()
                }

                if (frameBitmap != null && !frameBitmap.isRecycled) {
                    try {
                        val writer = imageWriter
                        if (writer != null && readerSpec != null) {
                            pumpToImageWriter(writer, readerSpec, frameBitmap, liveState, useVirtual)
                        } else if (readerSpec == null) {
                            pumpToCanvasSurface(frameBitmap, liveState, useVirtual, paint)
                        }
                    } finally {
                        if (!frameBitmap.isRecycled) frameBitmap.recycle()
                    }
                }

                if (running.get()) {
                    handler.postDelayed(this, 33L) // ~30 fps
                }
            }
        }
        handler.post(tick)
    }

    private fun pumpToImageWriter(
        writer: ImageWriter,
        spec: ReaderSurfaceSpec,
        source: Bitmap,
        state: HookRuntimeState,
        applyTransforms: Boolean
    ) {
        val fitted = VideoFramePipeline.renderFittedBitmap(
            source = source,
            targetW = spec.width,
            targetH = spec.height,
            mirrorHorizontal = if (applyTransforms) state.mirrorHorizontal else false,
            rotationDegrees = if (applyTransforms) state.rotationDegrees else 0
        )
        try {
            val destImage = writer.dequeueInputImage()
            try {
                VideoFramePipeline.writeBitmapToYuvImage(fitted, destImage)
                destImage.timestamp = System.nanoTime()
                writer.queueInputImage(destImage)
            } catch (t: Throwable) {
                runCatching { destImage.close() }
            }
        } catch (_: IllegalStateException) {
            // Consumer queue full; drop frame cleanly without stalling
        } catch (_: Throwable) {
            // Surface released concurrently
        } finally {
            fitted.recycle()
        }
    }

    private fun pumpToCanvasSurface(
        source: Bitmap,
        state: HookRuntimeState,
        applyTransforms: Boolean,
        paint: Paint
    ) {
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }
            if (canvas == null) return

            canvas.drawColor(Color.BLACK)
            val cw = canvas.width.toFloat().coerceAtLeast(2f)
            val ch = canvas.height.toFloat().coerceAtLeast(2f)
            val bw = source.width.toFloat().coerceAtLeast(1f)
            val bh = source.height.toFloat().coerceAtLeast(1f)

            val rot = if (applyTransforms) state.rotationDegrees else 0
            val mirror = if (applyTransforms) state.mirrorHorizontal else false
            val rotatedW = if (rot % 180 != 0) bh else bw
            val rotatedH = if (rot % 180 != 0) bw else bh
            val scale = maxOf(cw / rotatedW, ch / rotatedH)

            val matrix = Matrix().apply {
                postTranslate(-bw / 2f, -bh / 2f)
                if (mirror) postScale(-1f, 1f)
                if (rot % 360 != 0) postRotate(rot.toFloat())
                postScale(scale, scale)
                postTranslate(cw / 2f, ch / 2f)
            }
            canvas.drawBitmap(source, matrix, paint)
        } catch (_: Throwable) {
            // Surface transitioning or owned by another consumer
        } finally {
            if (canvas != null) {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        renderHandler?.removeCallbacksAndMessages(null)
        runCatching { renderThread?.quitSafely() }
        renderThread = null
        renderHandler = null

        runCatching { imageWriter?.close() }
        imageWriter = null

        if (consumerAcquired) {
            VideoFramePipeline.releaseConsumer()
            consumerAcquired = false
        }
    }
}
