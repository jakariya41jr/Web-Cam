package com.example.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
    val loopVideo: Boolean = true,
    val mirrorHorizontal: Boolean = false,
    val rotationDegrees: Int = 0,
    val allowAllScopedApps: Boolean = true,
    val mediaKind: MediaKind = MediaKind.NONE,
    val mediaPath: String = "",
    val mediaMime: String = "",
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val enabledPackages: Set<String> = emptySet()
) {
    fun isActiveForPackage(packageName: String): Boolean {
        if (!enabled || forceBypass || mediaKind == MediaKind.NONE) return false
        if (allowAllScopedApps) return true
        return enabledPackages.contains(packageName)
    }
}

/**
 * Shared media/config bridge used inside hooked camera target processes.
 * Combines LSPosed New XSharedPreferences (API 93+) with VirtualCamMediaProvider IPC.
 */
object HookMediaBridge {
    private const val TAG = "[VirtualCam]"

    @Volatile
    private var cachedPrefs: XSharedPreferences? = null

    private val activeRenderers = ConcurrentHashMap<Int, VirtualFrameRenderer>()

    fun currentAppContext(): Context? =
        runCatching { AndroidAppHelper.currentApplication() }.getOrNull()

    fun readRuntimeState(packageName: String, context: Context? = currentAppContext()): HookRuntimeState {
        // 1. Primary: New XSharedPreferences (backed by LSPosed Binder proxy when xposedsharedprefs=true)
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
                val enabled = prefs.getBoolean(VirtualCamConstants.KEY_ENABLED, false)
                val forceBypass = prefs.getBoolean(VirtualCamConstants.KEY_FORCE_BYPASS, false)
                val mediaKind = MediaKind.fromWire(prefs.getString(VirtualCamConstants.KEY_MEDIA_KIND, null))
                return HookRuntimeState(
                    enabled = enabled,
                    forceBypass = forceBypass,
                    audioEnabled = prefs.getBoolean(VirtualCamConstants.KEY_AUDIO_ENABLED, true),
                    loopVideo = prefs.getBoolean(VirtualCamConstants.KEY_LOOP_VIDEO, true),
                    mirrorHorizontal = prefs.getBoolean(VirtualCamConstants.KEY_MIRROR_HORIZONTAL, false),
                    rotationDegrees = prefs.getInt(VirtualCamConstants.KEY_ROTATION_DEGREES, 0),
                    allowAllScopedApps = prefs.getBoolean(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, true),
                    mediaKind = mediaKind,
                    mediaPath = prefs.getString(VirtualCamConstants.KEY_MEDIA_PATH, "").orEmpty(),
                    mediaMime = prefs.getString(VirtualCamConstants.KEY_MEDIA_MIME, "").orEmpty(),
                    mediaWidth = prefs.getInt(VirtualCamConstants.KEY_MEDIA_WIDTH, 0),
                    mediaHeight = prefs.getInt(VirtualCamConstants.KEY_MEDIA_HEIGHT, 0),
                    enabledPackages = prefs.getStringSet(VirtualCamConstants.KEY_ENABLED_PACKAGES, emptySet())
                        ?: emptySet()
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG XSharedPreferences read failed for $packageName: ${t.message}")
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
                        fun strCol(name: String): String {
                            val idx = cursor.getColumnIndex(name)
                            return if (idx >= 0) cursor.getString(idx).orEmpty() else ""
                        }

                        return HookRuntimeState(
                            enabled = intCol(VirtualCamConstants.KEY_ENABLED) == 1,
                            forceBypass = intCol(VirtualCamConstants.KEY_FORCE_BYPASS) == 1,
                            audioEnabled = intCol(VirtualCamConstants.KEY_AUDIO_ENABLED, 1) == 1,
                            loopVideo = intCol(VirtualCamConstants.KEY_LOOP_VIDEO, 1) == 1,
                            mirrorHorizontal = intCol(VirtualCamConstants.KEY_MIRROR_HORIZONTAL) == 1,
                            rotationDegrees = intCol(VirtualCamConstants.KEY_ROTATION_DEGREES, 0),
                            allowAllScopedApps = intCol(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, 1) == 1,
                            mediaKind = MediaKind.fromWire(strCol(VirtualCamConstants.KEY_MEDIA_KIND)),
                            mediaPath = strCol(VirtualCamConstants.KEY_MEDIA_PATH),
                            mediaMime = strCol(VirtualCamConstants.KEY_MEDIA_MIME),
                            mediaWidth = intCol(VirtualCamConstants.KEY_MEDIA_WIDTH),
                            mediaHeight = intCol(VirtualCamConstants.KEY_MEDIA_HEIGHT)
                        )
                    }
                }
            }
        }

        return HookRuntimeState()
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

    fun loadBitmapFromActiveMedia(
        context: Context?,
        state: HookRuntimeState,
        maxDimension: Int = 1920
    ): Bitmap? {
        val pfd = openActiveMediaPfd(context, state.mediaPath) ?: return null
        return try {
            if (state.mediaKind == MediaKind.IMAGE) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(pfd.fileDescriptor)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to load frame bitmap: ${t.message}")
            null
        } finally {
            runCatching { pfd.close() }
        }
    }

    fun startRendererForSurface(
        surface: Surface,
        packageName: String,
        useMediaPlayerForVideo: Boolean = true
    ) {
        if (!surface.isValid) return
        val key = System.identityHashCode(surface)
        stopRendererForSurface(surface)

        val context = currentAppContext()
        val state = readRuntimeState(packageName, context)
        if (!state.isActiveForPackage(packageName)) return

        val renderer = VirtualFrameRenderer(
            surface = surface,
            packageName = packageName,
            initialState = state,
            useMediaPlayerForVideo = useMediaPlayerForVideo
        )
        activeRenderers[key] = renderer
        renderer.start(context)
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

    fun encodeJpegFromActiveMedia(packageName: String): ByteArray? {
        val context = currentAppContext()
        val state = readRuntimeState(packageName, context)
        if (!state.isActiveForPackage(packageName)) return null
        val bitmap = loadBitmapFromActiveMedia(context, state) ?: return null
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
        val scaled = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }
        val argb = IntArray(targetWidth * targetHeight)
        scaled.getPixels(argb, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled !== bitmap) scaled.recycle()

        val frameSize = targetWidth * targetHeight
        val yuv = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until targetHeight) {
            for (i in 0 until targetWidth) {
                val pixel = argb[j * targetWidth + i]
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
 * Renders either a looping MediaPlayer stream (VIDEO) or a transformed Canvas bitmap feed (IMAGE)
 * onto an intercepted Android Surface while honoring live audio/bypass toggles.
 */
class VirtualFrameRenderer(
    private val surface: Surface,
    private val packageName: String,
    private val initialState: HookRuntimeState,
    private val useMediaPlayerForVideo: Boolean
) {
    private val running = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPfd: ParcelFileDescriptor? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var staticBitmap: Bitmap? = null

    fun start(context: Context?) {
        if (!running.compareAndSet(false, true)) return

        if (initialState.mediaKind == MediaKind.VIDEO && useMediaPlayerForVideo) {
            if (startVideoPlayer(context)) return
        }
        startBitmapLoop(context)
    }

    private fun startVideoPlayer(context: Context?): Boolean {
        val pfd = HookMediaBridge.openActiveMediaPfd(context, initialState.mediaPath) ?: return false
        return try {
            val player = MediaPlayer().apply {
                setDataSource(pfd.fileDescriptor)
                setSurface(surface)
                isLooping = initialState.loopVideo
                val vol = if (initialState.audioEnabled) 1.0f else 0.0f
                setVolume(vol, vol)
                setOnPreparedListener { mp ->
                    if (running.get() && surface.isValid) {
                        mp.start()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    XposedBridge.log("[VirtualCam] MediaPlayer error ($what/$extra), falling back to frame blitter")
                    false
                }
                prepareAsync()
            }
            mediaPfd = pfd
            mediaPlayer = player
            true
        } catch (t: Throwable) {
            XposedBridge.log("[VirtualCam] Video renderer fallback: ${t.message}")
            runCatching { pfd.close() }
            false
        }
    }

    private fun startBitmapLoop(context: Context?) {
        staticBitmap = HookMediaBridge.loadBitmapFromActiveMedia(context, initialState)
        val thread = HandlerThread("VirtualCamFramePump").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tick = object : Runnable {
            override fun run() {
                if (!running.get() || !surface.isValid) return

                val liveState = HookMediaBridge.readRuntimeState(packageName, context)
                if (!liveState.isActiveForPackage(packageName)) {
                    stop()
                    return
                }

                // Update audio dynamically if MediaPlayer happens to exist
                mediaPlayer?.let { mp ->
                    val vol = if (liveState.audioEnabled) 1.0f else 0.0f
                    runCatching { mp.setVolume(vol, vol) }
                }

                val bmp = staticBitmap
                if (bmp != null && !bmp.isRecycled) {
                    drawBitmapToSurface(bmp, liveState, paint)
                }

                if (running.get()) {
                    handler.postDelayed(this, 66L) // ~15 FPS steady refresh for static frame
                }
            }
        }
        handler.post(tick)
    }

    private fun drawBitmapToSurface(bitmap: Bitmap, state: HookRuntimeState, paint: Paint) {
        var canvas: Canvas? = null
        try {
            canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                surface.lockCanvas(null)
            }
            if (canvas == null) return

            canvas.drawColor(Color.BLACK)
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            val bw = bitmap.width.toFloat().coerceAtLeast(1f)
            val bh = bitmap.height.toFloat().coerceAtLeast(1f)

            val matrix = Matrix()
            val scale = maxOf(cw / bw, ch / bh)
            matrix.postTranslate(-bw / 2f, -bh / 2f)
            if (state.mirrorHorizontal) {
                matrix.postScale(-1f, 1f)
            }
            if (state.rotationDegrees % 360 != 0) {
                matrix.postRotate(state.rotationDegrees.toFloat())
            }
            matrix.postScale(scale, scale)
            matrix.postTranslate(cw / 2f, ch / 2f)

            canvas.drawBitmap(bitmap, matrix, paint)
        } catch (_: Throwable) {
            // Surface may be transitioning or owned by another BufferQueue producer
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

        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
        runCatching { mediaPfd?.close() }
        mediaPfd = null
        staticBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        staticBitmap = null
    }
}
