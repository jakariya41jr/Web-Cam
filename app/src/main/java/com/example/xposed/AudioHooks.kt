package com.example.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.model.MediaKind
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Decodes PCM audio from the selected video file into a circular ring buffer
 * and hooks AudioRecord.read(...) so calls can independently:
 * 1) Inject video sound into the outgoing call stream (Video Audio toggle)
 * 2) Mute or pass through the device microphone (Main Microphone toggle)
 */
object AudioHooks {
    private const val TAG = "[VirtualCam-Audio]"

    private data class RecordConfig(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int
    )

    private val recordConfigs = WeakHashMap<AudioRecord, RecordConfig>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val processName = lpparam.processName ?: pkg
        val audioRecordClass = runCatching {
            XposedHelpers.findClass("android.media.AudioRecord", lpparam.classLoader)
        }.getOrNull() ?: return

        // Track AudioRecord parameters via constructors and startRecording
        XposedBridge.hookAllConstructors(audioRecordClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as? AudioRecord ?: return
                runCatching {
                    recordConfigs[record] = RecordConfig(
                        sampleRate = record.sampleRate.coerceAtLeast(8000),
                        channelCount = record.channelCount.coerceIn(1, 2),
                        encoding = record.audioFormat
                    )
                }
            }
        })

        // Hook AudioRecord.read(byte[], int, int) and read(byte[], int, int, int)
        hookByteArrayRead(audioRecordClass, pkg, processName)
        // Hook AudioRecord.read(short[], int, int) and read(short[], int, int, int)
        hookShortArrayRead(audioRecordClass, pkg, processName)
        // Hook AudioRecord.read(ByteBuffer, int) and read(ByteBuffer, int, int)
        hookByteBufferRead(audioRecordClass, pkg, processName)

        XposedBridge.log("$TAG Installed AudioRecord call-routing hooks for $pkg (process=$processName)")
    }

    private fun hookByteArrayRead(
        audioRecordClass: Class<*>,
        pkg: String,
        processName: String
    ) {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as? AudioRecord ?: return
                val bytesRead = (param.result as? Int) ?: return
                if (bytesRead <= 1) return

                val buffer = param.args[0] as? ByteArray ?: return
                val offset = (param.args[1] as? Int) ?: 0
                if (offset < 0 || offset + bytesRead > buffer.size) return

                val state = HookMediaBridge.readRuntimeState(pkg, processName)
                if (!state.isScopedTarget(pkg, processName) || !state.enabled) return

                val cfg = getRecordConfig(record)
                if (cfg.encoding != AudioFormat.ENCODING_PCM_16BIT) return

                val injectVideoAudio = !state.forceBypass &&
                    state.audioEnabled &&
                    state.mediaKind == MediaKind.VIDEO
                val muteMic = !state.micEnabled

                if (!muteMic && !injectVideoAudio) return

                val sampleCount = bytesRead / 2
                val tempShorts = ShortArray(sampleCount)
                ByteBuffer.wrap(buffer, offset, sampleCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(tempShorts)

                processPcm16Samples(
                    samples = tempShorts,
                    count = sampleCount,
                    dstRate = cfg.sampleRate,
                    dstChannels = cfg.channelCount,
                    muteMic = muteMic,
                    injectVideoAudio = injectVideoAudio,
                    state = state
                )

                ByteBuffer.wrap(buffer, offset, sampleCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(tempShorts)
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                callback
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                callback
            )
        }
    }

    private fun hookShortArrayRead(
        audioRecordClass: Class<*>,
        pkg: String,
        processName: String
    ) {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as? AudioRecord ?: return
                val shortsRead = (param.result as? Int) ?: return
                if (shortsRead <= 0) return

                val buffer = param.args[0] as? ShortArray ?: return
                val offset = (param.args[1] as? Int) ?: 0
                if (offset < 0 || offset + shortsRead > buffer.size) return

                val state = HookMediaBridge.readRuntimeState(pkg, processName)
                if (!state.isScopedTarget(pkg, processName) || !state.enabled) return

                val cfg = getRecordConfig(record)
                val injectVideoAudio = !state.forceBypass &&
                    state.audioEnabled &&
                    state.mediaKind == MediaKind.VIDEO
                val muteMic = !state.micEnabled

                if (!muteMic && !injectVideoAudio) return

                val slice = ShortArray(shortsRead)
                System.arraycopy(buffer, offset, slice, 0, shortsRead)
                processPcm16Samples(
                    samples = slice,
                    count = shortsRead,
                    dstRate = cfg.sampleRate,
                    dstChannels = cfg.channelCount,
                    muteMic = muteMic,
                    injectVideoAudio = injectVideoAudio,
                    state = state
                )
                System.arraycopy(slice, 0, buffer, offset, shortsRead)
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                callback
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                callback
            )
        }
    }

    private fun hookByteBufferRead(
        audioRecordClass: Class<*>,
        pkg: String,
        processName: String
    ) {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val record = param.thisObject as? AudioRecord ?: return
                val bytesRead = (param.result as? Int) ?: return
                if (bytesRead <= 1) return

                val buf = param.args[0] as? ByteBuffer ?: return
                val state = HookMediaBridge.readRuntimeState(pkg, processName)
                if (!state.isScopedTarget(pkg, processName) || !state.enabled) return

                val cfg = getRecordConfig(record)
                if (cfg.encoding != AudioFormat.ENCODING_PCM_16BIT) return

                val injectVideoAudio = !state.forceBypass &&
                    state.audioEnabled &&
                    state.mediaKind == MediaKind.VIDEO
                val muteMic = !state.micEnabled

                if (!muteMic && !injectVideoAudio) return

                val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                // AudioRecord advances position or fills from 0 depending on direct/heap buffer
                val startPos = if (dup.position() >= bytesRead) dup.position() - bytesRead else 0
                if (startPos + bytesRead > dup.capacity()) return

                val sampleCount = bytesRead / 2
                val samples = ShortArray(sampleCount)
                dup.position(startPos)
                dup.asShortBuffer().get(samples)

                processPcm16Samples(
                    samples = samples,
                    count = sampleCount,
                    dstRate = cfg.sampleRate,
                    dstChannels = cfg.channelCount,
                    muteMic = muteMic,
                    injectVideoAudio = injectVideoAudio,
                    state = state
                )

                dup.position(startPos)
                dup.asShortBuffer().put(samples)
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType,
                callback
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                audioRecordClass, "read",
                ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                callback
            )
        }
    }

    private fun getRecordConfig(record: AudioRecord): RecordConfig {
        return recordConfigs[record] ?: runCatching {
            RecordConfig(
                sampleRate = record.sampleRate.coerceAtLeast(8000),
                channelCount = record.channelCount.coerceIn(1, 2),
                encoding = record.audioFormat
            )
        }.getOrElse { RecordConfig(48000, 1, AudioFormat.ENCODING_PCM_16BIT) }
    }

    private fun processPcm16Samples(
        samples: ShortArray,
        count: Int,
        dstRate: Int,
        dstChannels: Int,
        muteMic: Boolean,
        injectVideoAudio: Boolean,
        state: HookRuntimeState
    ) {
        if (muteMic) {
            for (i in 0 until count) {
                samples[i] = 0
            }
        }
        if (injectVideoAudio) {
            VideoAudioRingBuffer.mixInto(
                context = HookMediaBridge.currentAppContext(),
                state = state,
                dstSamples = samples,
                dstCount = count,
                dstRate = dstRate,
                dstChannels = dstChannels
            )
        }
    }
}

/**
 * Decodes the selected video's audio track into PCM-16 mono/stereo and streams
 * resampled samples into AudioRecord buffers without acoustic speaker feedback.
 */
private object VideoAudioRingBuffer {
    private const val CAPACITY = 48000 * 2 * 6 // ~6 seconds at 48kHz stereo

    private val lock = Any()
    private val ring = ShortArray(CAPACITY)
    private var writePos = 0
    private var readPos = 0
    private var available = 0

    @Volatile
    private var srcSampleRate = 48000

    @Volatile
    private var srcChannels = 1

    @Volatile
    private var mediaSignature = ""

    private val running = AtomicBoolean(false)

    @Volatile
    private var decoderThread: Thread? = null

    private var resamplePhase = 0.0

    fun mixInto(
        context: Context?,
        state: HookRuntimeState,
        dstSamples: ShortArray,
        dstCount: Int,
        dstRate: Int,
        dstChannels: Int
    ) {
        val sig = "${state.mediaPath}:${state.mediaUpdatedAtMs}"
        if (sig != mediaSignature || !running.get()) {
            startDecoder(context, state, sig)
        }

        val framesNeeded = dstCount / dstChannels.coerceAtLeast(1)
        if (framesNeeded <= 0) return

        synchronized(lock) {
            if (available < srcChannels) return

            val rateRatio = srcSampleRate.toDouble() / dstRate.coerceAtLeast(8000).toDouble()
            var dstIdx = 0

            for (f in 0 until framesNeeded) {
                if (available < srcChannels) break

                // Read current source frame
                val left = ring[readPos]
                val right = if (srcChannels >= 2) ring[(readPos + 1) % CAPACITY] else left
                val mono = ((left.toInt() + right.toInt()) / 2).toShort()

                if (dstChannels == 1) {
                    val mixed = dstSamples[dstIdx].toInt() + mono.toInt()
                    dstSamples[dstIdx++] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                } else {
                    val mixL = dstSamples[dstIdx].toInt() + left.toInt()
                    dstSamples[dstIdx++] = mixL.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    if (dstIdx < dstCount) {
                        val mixR = dstSamples[dstIdx].toInt() + right.toInt()
                        dstSamples[dstIdx++] = mixR.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }

                resamplePhase += rateRatio
                val advanceFrames = resamplePhase.toInt()
                if (advanceFrames > 0) {
                    resamplePhase -= advanceFrames
                    val samplesToConsume = min(advanceFrames * srcChannels, available)
                    readPos = (readPos + samplesToConsume) % CAPACITY
                    available -= samplesToConsume
                }
            }
        }
    }

    private fun startDecoder(context: Context?, state: HookRuntimeState, signature: String) {
        running.set(false)
        decoderThread?.interrupt()
        synchronized(lock) {
            writePos = 0
            readPos = 0
            available = 0
            resamplePhase = 0.0
            mediaSignature = signature
        }

        if (state.mediaKind != MediaKind.VIDEO || state.mediaPath.isBlank()) return

        running.set(true)
        decoderThread = Thread({
            decodeLoop(context, state)
        }, "VirtualCamAudioDecoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop(context: Context?, state: HookRuntimeState) {
        while (running.get()) {
            val pfd = HookMediaBridge.openActiveMediaPfd(context, state.mediaPath)
            if (pfd == null) {
                runCatching { Thread.sleep(200L) }
                continue
            }

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                var audioTrack = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audioTrack = i
                        audioFormat = fmt
                        break
                    }
                }
                if (audioTrack < 0 || audioFormat == null) {
                    // Video has no audio track; exit quietly without error spam
                    running.set(false)
                    return
                }

                srcSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                srcChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                extractor.selectTrack(audioTrack)
                val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return

                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(audioFormat, null, null, 0)
                    start()
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (running.get() && !outputDone) {
                    // Throttle producer if ring buffer is > 70% full
                    val ringUsage = synchronized(lock) { available }
                    if (ringUsage > (CAPACITY * 7) / 10) {
                        Thread.sleep(25L)
                        continue
                    }

                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (info.size > 1) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val pcm = ShortArray(shortBuf.remaining())
                                shortBuf.get(pcm)
                                pushSamples(pcm)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val fmt = codec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                XposedBridge.log("[VirtualCam-Audio] Decoder warning: ${t.message ?: t.javaClass.simpleName}")
                runCatching { Thread.sleep(250L) }
            } finally {
                runCatching {
                    codec?.stop()
                    codec?.release()
                }
                runCatching { extractor.release() }
                runCatching { pfd.close() }
            }

            if (!state.loopVideo) break
        }
    }

    private fun pushSamples(pcm: ShortArray) {
        synchronized(lock) {
            for (s in pcm) {
                ring[writePos] = s
                writePos = (writePos + 1) % CAPACITY
                if (available < CAPACITY) {
                    available++
                } else {
                    readPos = (readPos + 1) % CAPACITY
                }
            }
        }
    }
}
