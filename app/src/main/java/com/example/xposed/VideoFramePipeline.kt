package com.example.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.example.model.MediaKind
import de.robv.android.xposed.XposedBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Thread-safe looping video/image frame decoder for Xposed target processes.
 * Uses MediaCodec (COLOR_FormatYUV420Flexible) with a MediaMetadataRetriever fallback,
 * completely avoiding MediaPlayer BufferQueue collisions on Camera2/ImageReader surfaces.
 */
object VideoFramePipeline {
    private const val TAG = "[VirtualCam-Video]"
    private const val TARGET_FRAME_INTERVAL_MS = 33L // ~30 fps

    private val running = AtomicBoolean(false)
    private val activeConsumers = AtomicInteger(0)
    private val lastFrameTimestampMs = AtomicLong(0L)

    @Volatile
    private var decoderThread: Thread? = null

    @Volatile
    private var currentMediaSignature: String = ""

    @Volatile
    private var latestBitmap: Bitmap? = null

    private val bitmapLock = Any()

    fun acquireConsumer(context: Context?, state: HookRuntimeState) {
        val count = activeConsumers.incrementAndGet()
        val sig = "${state.mediaKind.wireValue}:${state.mediaPath}:${state.mediaWidth}x${state.mediaHeight}"
        if (count == 1 || sig != currentMediaSignature || !running.get()) {
            startOrRestart(context, state, sig)
        }
    }

    fun releaseConsumer() {
        val remaining = activeConsumers.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            stop()
        }
    }

    fun getCurrentFrameBitmap(context: Context?, state: HookRuntimeState): Bitmap? {
        val sig = "${state.mediaKind.wireValue}:${state.mediaPath}:${state.mediaWidth}x${state.mediaHeight}"
        if (sig != currentMediaSignature && state.mediaKind != MediaKind.NONE) {
            startOrRestart(context, state, sig)
        }
        synchronized(bitmapLock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled) {
                return bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        // Synchronous first-frame bootstrap if decoder hasn't produced frame 0 yet
        return loadFallbackBitmap(context, state)?.also { boot ->
            synchronized(bitmapLock) {
                latestBitmap?.let { if (!it.isRecycled) it.recycle() }
                latestBitmap = boot.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }

    private fun startOrRestart(context: Context?, state: HookRuntimeState, signature: String) {
        stop()
        currentMediaSignature = signature
        if (state.mediaKind == MediaKind.NONE) return

        // Pre-warm frame 0 immediately so consumers never see a black first frame
        loadFallbackBitmap(context, state)?.let { firstFrame ->
            synchronized(bitmapLock) {
                latestBitmap?.let { if (!it.isRecycled) it.recycle() }
                latestBitmap = firstFrame
            }
        }

        if (state.mediaKind == MediaKind.IMAGE) {
            // Static image already cached in latestBitmap
            return
        }

        running.set(true)
        val thread = Thread({
            decodeVideoLoop(context, state)
        }, "VirtualCamVideoDecoder").apply {
            isDaemon = true
            start()
        }
        decoderThread = thread
    }

    fun stop() {
        running.set(false)
        decoderThread?.interrupt()
        decoderThread = null
    }

    private fun decodeVideoLoop(context: Context?, state: HookRuntimeState) {
        while (running.get() && activeConsumers.get() > 0) {
            var usedCodec = false
            val pfd = HookMediaBridge.openActiveMediaPfd(context, state.mediaPath)
            if (pfd == null) {
                runCatching { Thread.sleep(150L) }
                continue
            }

            try {
                usedCodec = runMediaCodecPass(pfd, state)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                XposedBridge.log("$TAG MediaCodec decode warning: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { pfd.close() }
            }

            if (!usedCodec && running.get() && activeConsumers.get() > 0) {
                runRetrieverFallbackPass(context, state)
            }

            if (!state.loopVideo) {
                break
            }
        }
    }

    private fun runMediaCodecPass(pfd: ParcelFileDescriptor, state: HookRuntimeState): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(pfd.fileDescriptor)
            var videoTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    trackFormat = fmt
                    break
                }
            }
            if (videoTrack < 0 || trackFormat == null) return false

            extractor.selectTrack(videoTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return false
            trackFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPtsUs = -1L
            var startWallMs = System.currentTimeMillis()

            while (running.get() && activeConsumers.get() > 0 && !outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (bufferInfo.size > 0) {
                        val ptsUs = bufferInfo.presentationTimeUs
                        if (firstPtsUs < 0L) {
                            firstPtsUs = ptsUs
                            startWallMs = System.currentTimeMillis()
                        } else {
                            val targetElapsedMs = (ptsUs - firstPtsUs) / 1000L
                            val actualElapsedMs = System.currentTimeMillis() - startWallMs
                            val sleepMs = (targetElapsedMs - actualElapsedMs).coerceIn(0L, 60L)
                            if (sleepMs > 2L) {
                                Thread.sleep(sleepMs)
                            }
                        }

                        val image = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                        if (image != null) {
                            try {
                                val bmp = yuvImageToBitmap(image, maxDim = 1080)
                                if (bmp != null) {
                                    synchronized(bitmapLock) {
                                        latestBitmap?.let { if (!it.isRecycled) it.recycle() }
                                        latestBitmap = bmp
                                    }
                                    lastFrameTimestampMs.set(System.currentTimeMillis())
                                }
                            } finally {
                                runCatching { image.close() }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            true
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            runCatching { extractor.release() }
        }
    }

    private fun runRetrieverFallbackPass(context: Context?, state: HookRuntimeState) {
        val pfd = HookMediaBridge.openActiveMediaPfd(context, state.mediaPath) ?: return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(pfd.fileDescriptor)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1000L) ?: 3000L
            var posMs = 0L
            while (running.get() && activeConsumers.get() > 0 && posMs < durationMs) {
                val frame = retriever.getFrameAtTime(
                    posMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    synchronized(bitmapLock) {
                        latestBitmap?.let { if (!it.isRecycled) it.recycle() }
                        latestBitmap = frame
                    }
                }
                Thread.sleep(TARGET_FRAME_INTERVAL_MS * 2)
                posMs += TARGET_FRAME_INTERVAL_MS * 2
            }
        } catch (_: InterruptedException) {
            // Exit cleanly
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Retriever fallback warning: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { retriever.release() }
            runCatching { pfd.close() }
        }
    }

    private fun loadFallbackBitmap(context: Context?, state: HookRuntimeState): Bitmap? {
        val pfd = HookMediaBridge.openActiveMediaPfd(context, state.mediaPath) ?: return null
        return try {
            if (state.mediaKind == MediaKind.IMAGE) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 1280 || bounds.outHeight / sampleSize > 1280) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(pfd.fileDescriptor)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { retriever.release() }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Frame bootstrap warning: ${t.message ?: t.javaClass.simpleName}")
            null
        } finally {
            runCatching { pfd.close() }
        }
    }

    /**
     * Converts a MediaCodec YUV_420_888 Image to an ARGB_8888 Bitmap safely,
     * downsampling if needed so target memory stays lean inside WhatsApp/Imo/OmeTV.
     */
    fun yuvImageToBitmap(image: Image, maxDim: Int = 1080): Bitmap? {
        val planes = image.planes
        if (planes.size < 3) return null

        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return null

        val step = if (max(srcW, srcH) > maxDim) 2 else 1
        val dstW = (srcW / step).coerceAtLeast(2)
        val dstH = (srcH / step).coerceAtLeast(2)

        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val argb = IntArray(dstW * dstH)
        var outIdx = 0
        for (y in 0 until dstH) {
            val sy = y * step
            val yRowOffset = sy * yRowStride
            val uvRowOffset = (sy / 2) * uvRowStride
            for (x in 0 until dstW) {
                val sx = x * step
                val yPos = yRowOffset + sx * yPixelStride
                val uvPos = uvRowOffset + (sx / 2) * uvPixelStride

                val yVal = if (yPos < yBuf.limit()) (yBuf.get(yPos).toInt() and 0xFF) else 16
                val uVal = if (uvPos < uBuf.limit()) (uBuf.get(uvPos).toInt() and 0xFF) - 128 else 0
                val vVal = if (uvPos < vBuf.limit()) (vBuf.get(uvPos).toInt() and 0xFF) - 128 else 0

                val c = yVal - 16
                val r = ((298 * c + 409 * vVal + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * uVal - 208 * vVal + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * uVal + 128) shr 8).coerceIn(0, 255)

                argb[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    /**
     * Renders [source] aspect-filled into an exact [targetW] x [targetH] Bitmap
     * with mirror and sensor rotation applied.
     */
    fun renderFittedBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        mirrorHorizontal: Boolean,
        rotationDegrees: Int
    ): Bitmap {
        val safeW = targetW.coerceAtLeast(2)
        val safeH = targetH.coerceAtLeast(2)
        val out = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val rotatedW = if (rotationDegrees % 180 != 0) source.height.toFloat() else source.width.toFloat()
        val rotatedH = if (rotationDegrees % 180 != 0) source.width.toFloat() else source.height.toFloat()
        val scale = max(safeW / rotatedW.coerceAtLeast(1f), safeH / rotatedH.coerceAtLeast(1f))

        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            if (mirrorHorizontal) postScale(-1f, 1f)
            if (rotationDegrees % 360 != 0) postRotate(rotationDegrees.toFloat())
            postScale(scale, scale)
            postTranslate(safeW / 2f, safeH / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return out
    }

    /**
     * Writes an ARGB Bitmap directly into a YUV_420_888 android.media.Image (from ImageWriter).
     */
    fun writeBitmapToYuvImage(bitmap: Bitmap, destImage: Image) {
        if (destImage.format != ImageFormat.YUV_420_888) return
        val planes = destImage.planes
        if (planes.size < 3) return

        val width = min(bitmap.width, destImage.width)
        val height = min(bitmap.height, destImage.height)
        if (width <= 0 || height <= 0) return

        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf: ByteBuffer = yPlane.buffer
        val uBuf: ByteBuffer = uPlane.buffer
        val vBuf: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        for (j in 0 until height) {
            val yRowBase = j * yRowStride
            val uRowBase = (j / 2) * uRowStride
            val vRowBase = (j / 2) * vRowStride

            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val yIdx = yRowBase + i * yPixStride
                if (yIdx < yBuf.capacity()) {
                    yBuf.put(yIdx, y.toByte())
                }

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val uvCol = i / 2
                    val uIdx = uRowBase + uvCol * uPixStride
                    val vIdx = vRowBase + uvCol * vPixStride
                    if (uIdx < uBuf.capacity()) uBuf.put(uIdx, u.toByte())
                    if (vIdx < vBuf.capacity()) vBuf.put(vIdx, v.toByte())
                }
            }
        }
    }
}
