package app.myzel394.alibi.services

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import app.myzel394.alibi.db.AudioRecorderSettings
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal interface EncodedAudioSink {
    fun onOutputFormatChanged(sampleRate: Int, channelCount: Int) {}
    fun onCodecConfig(data: ByteArray, length: Int) {}
    fun onEncodedFrame(presentationTimeUs: Long, data: ByteArray, length: Int)
    fun flushAndClose() {}
}

internal class RingBufferAudioSink(
    private val buffer: EncodedAudioFrameRingBuffer,
) : EncodedAudioSink {
    override fun onCodecConfig(data: ByteArray, length: Int) {
        buffer.setCodecConfig(data.copyOf(length))
    }

    override fun onEncodedFrame(presentationTimeUs: Long, data: ByteArray, length: Int) {
        buffer.addOwned(presentationTimeUs, data.copyOf(length))
    }
}

/**
 * Writes ADTS-framed AAC output to a buffered file stream and supports seamless
 * rotation between batch files without restarting AudioRecord or MediaCodec.
 */
internal class RotatingAdtsFileSink(
    sampleRate: Int,
    channelCount: Int = 1,
    private val audioObjectType: Int = 2,
    private val bufferSizeBytes: Int = DEFAULT_FILE_BUFFER_BYTES,
) : EncodedAudioSink {
    private var frequencyIndex = EncodedAudioFrameRingBuffer.AdtsAac.frequencyIndex(sampleRate)
    private var channelCount = channelCount
    private val lock = Any()
    private val adtsHeader = ByteArray(7)
    private var currentStream: BufferedOutputStream? = null

    override fun onOutputFormatChanged(sampleRate: Int, channelCount: Int) {
        if (!EncodedAudioFrameRingBuffer.AdtsAac.isSupportedSampleRate(sampleRate)) return
        if (channelCount !in 1..7) return
        synchronized(lock) {
            frequencyIndex = EncodedAudioFrameRingBuffer.AdtsAac.frequencyIndex(sampleRate)
            this.channelCount = channelCount
        }
    }

    fun switchTo(outputStream: OutputStream) {
        val nextStream = BufferedOutputStream(outputStream, bufferSizeBytes)
        val previous = synchronized(lock) {
            val old = currentStream
            currentStream = nextStream
            old
        }
        if (previous != null) {
            runCatching { previous.flush() }
            runCatching { previous.close() }
        }
    }

    override fun onEncodedFrame(presentationTimeUs: Long, data: ByteArray, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            val stream = currentStream ?: return
            EncodedAudioFrameRingBuffer.AdtsAac.writeHeader(
                target = adtsHeader,
                rawFrameLength = length,
                frequencyIndex = frequencyIndex,
                channelCount = channelCount,
                audioObjectType = audioObjectType,
            )
            stream.write(adtsHeader, 0, 7)
            stream.write(data, 0, length)
        }
    }

    override fun flushAndClose() {
        val stream = synchronized(lock) {
            val old = currentStream
            currentStream = null
            old
        }
        if (stream != null) {
            runCatching { stream.flush() }
            runCatching { stream.close() }
        }
    }

    companion object {
        const val DEFAULT_FILE_BUFFER_BYTES = 32 * 1024
    }
}

internal data class AudioBatchPlan(
    val audioRecordBufferBytes: Int,
    val backgroundReadBytes: Int,
    val visualizerReadBytes: Int,
    val codecMaxInputBytes: Int,
)

internal data class CapturePollingPlan(
    val useNonBlockingSleep: Boolean,
    val backgroundSleepMs: Long,
    val visualizerSleepMs: Long,
)

internal object AudioBatchSizing {
    private const val BYTES_PER_FRAME = 2 // 16-bit mono PCM
    private const val BACKGROUND_TARGET_MS = 512L
    private const val VISUALIZER_TARGET_MS = 100L
    private const val MAX_BACKGROUND_BATCH_BYTES = 32 * 1024
    private const val RECORD_RING_TARGET_MS = 2_000L
    private const val MAX_RECORD_RING_BYTES = 256 * 1024
    private const val MAX_BACKGROUND_SLEEP_MS = 400L
    private const val MIN_NONBLOCKING_BUFFER_MS = 160L
    private const val VISUALIZER_SLEEP_MS = 80L

    fun planBuffers(sampleRate: Int, minimumBufferSize: Int): AudioBatchPlan {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(minimumBufferSize > 0) { "minimumBufferSize must be positive" }

        val targetBackgroundBytes = alignToFrame(
            (sampleRate.toLong() * BYTES_PER_FRAME * BACKGROUND_TARGET_MS / 1000L)
                .coerceIn(minimumBufferSize.toLong(), MAX_BACKGROUND_BATCH_BYTES.toLong())
                .toInt(),
        )
        val targetVisualizerBytes = alignToFrame(
            (sampleRate.toLong() * BYTES_PER_FRAME * VISUALIZER_TARGET_MS / 1000L)
                .coerceIn(2048L, targetBackgroundBytes.toLong())
                .toInt(),
        )
        val desiredRingBytes = alignToFrame(
            (sampleRate.toLong() * BYTES_PER_FRAME * RECORD_RING_TARGET_MS / 1000L)
                .coerceAtMost(MAX_RECORD_RING_BYTES.toLong())
                .toInt(),
        )
        val audioRecordBufferBytes = alignToFrame(
            maxOf(minimumBufferSize * 4, targetBackgroundBytes * 2, desiredRingBytes),
        )
        return AudioBatchPlan(
            audioRecordBufferBytes = audioRecordBufferBytes,
            backgroundReadBytes = targetBackgroundBytes,
            visualizerReadBytes = targetVisualizerBytes,
            codecMaxInputBytes = maxOf(minimumBufferSize, targetBackgroundBytes),
        )
    }

    fun planPolling(actualBufferFrames: Int, sampleRate: Int): CapturePollingPlan {
        val actualBufferMs = if (sampleRate > 0) {
            actualBufferFrames.toLong() * 1000L / sampleRate
        } else {
            0L
        }
        if (actualBufferMs < MIN_NONBLOCKING_BUFFER_MS) {
            return CapturePollingPlan(
                useNonBlockingSleep = false,
                backgroundSleepMs = 0L,
                visualizerSleepMs = 0L,
            )
        }
        // Keep at least 4x ring-buffer headroom over the sleep cadence to prevent overruns.
        val backgroundSleepMs = (actualBufferMs / 4L).coerceIn(40L, MAX_BACKGROUND_SLEEP_MS)
        val visualizerSleepMs = minOf(VISUALIZER_SLEEP_MS, backgroundSleepMs)
        return CapturePollingPlan(
            useNonBlockingSleep = true,
            backgroundSleepMs = backgroundSleepMs,
            visualizerSleepMs = visualizerSleepMs,
        )
    }

    private fun alignToFrame(bytes: Int): Int = (bytes / BYTES_PER_FRAME).coerceAtLeast(1) * BYTES_PER_FRAME
}

/**
 * Captures mono 16-bit PCM via AudioRecord in coarse batches and encodes directly with
 * MediaCodec AAC-LC, bypassing MediaRecorder/mediaserver and minimizing codec IPC frequency.
 */
internal class BatchedAacCapture(
    private val audioSettings: AudioRecorderSettings,
    private val sink: EncodedAudioSink,
    private val shouldReportAmplitude: () -> Boolean,
    private val onAmplitude: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val startPresentationTimeUs: Long = 0L,
    private val preferredDevice: AudioDeviceInfo? = null,
) {
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val workerStarted = AtomicBoolean(false)
    private val codecStarted = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val sleepLock = Object()

    private val audioRecord: AudioRecord
    private val codec: MediaCodec
    private val worker: Thread
    private val sampleRate = audioSettings.getEffectiveSamplingRate()
    private val batchPlan: AudioBatchPlan
    private val pollingPlan: CapturePollingPlan
    private val outputBufferInfo = MediaCodec.BufferInfo()
    private var outputScratch = ByteArray(4096)
    private var framesRead = 0L
    private var encoderEnded = false

    val actualBufferFrames: Int
        get() = audioRecord.bufferSizeInFrames

    val backgroundSleepMs: Long
        get() = pollingPlan.backgroundSleepMs

    val backgroundReadBytes: Int
        get() = batchPlan.backgroundReadBytes

    @Volatile
    var nextPresentationTimeUs = startPresentationTimeUs
        private set

    init {
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minimumBufferSize > 0) { "AudioRecord returned an invalid buffer size" }

        batchPlan = AudioBatchSizing.planBuffers(sampleRate, minimumBufferSize)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            batchPlan.audioRecordBufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }
        if (preferredDevice != null) {
            record.setPreferredDevice(preferredDevice)
        }
        pollingPlan = AudioBatchSizing.planPolling(record.bufferSizeInFrames, sampleRate)

        val createdCodec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (error: Throwable) {
            record.release()
            throw error
        }
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                1,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfoProfile.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioSettings.getEffectiveBitRate())
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, batchPlan.codecMaxInputBytes)
            }
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = record
            codec = createdCodec
            worker = Thread(::run, "alibi-audio-batched-capture")
        } catch (error: Throwable) {
            runCatching { createdCodec.release() }
            record.release()
            throw error
        }
    }

    fun start() {
        synchronized(lifecycleLock) {
            check(started.compareAndSet(false, true)) { "Capture can only be started once" }
            try {
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord did not start recording"
                }
                codec.start()
                codecStarted.set(true)
                running.set(true)
                workerStarted.set(true)
                worker.start()
            } catch (error: Throwable) {
                running.set(false)
                workerStarted.set(false)
                releaseResources()
                throw error
            }
        }
    }

    /** Wakes the capture loop immediately (e.g., when a visible visualizer attaches). */
    fun notifyWakeup() {
        synchronized(sleepLock) {
            sleepLock.notifyAll()
        }
    }

    fun stop() {
        val shouldJoin = synchronized(lifecycleLock) {
            if (!started.get()) {
                releaseResources()
                return@synchronized false
            }

            running.set(false)
            runCatching { audioRecord.stop() }
            notifyWakeup()
            if (!workerStarted.get()) {
                releaseResources()
                false
            } else {
                Thread.currentThread() !== worker
            }
        }

        if (shouldJoin) joinWorker()
    }

    private fun joinWorker() {
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun run() {
        val pcmBuffer = ByteArray(batchPlan.backgroundReadBytes)
        var failure: Throwable? = null
        try {
            if (pollingPlan.useNonBlockingSleep) {
                runNonBlockingBatchedLoop(pcmBuffer)
            } else {
                runBlockingBatchedLoop(pcmBuffer)
            }

            // Capture any buffered PCM captured right before stop() was requested.
            drainRemainingAudioRecord(pcmBuffer)
            finishEncoder()
        } catch (error: Throwable) {
            if (running.compareAndSet(true, false)) {
                runCatching { audioRecord.stop() }
                failure = error
            }
        } finally {
            releaseResources()
            failure?.let { runCatching { onError(it) } }
        }
    }

    private fun runNonBlockingBatchedLoop(pcmBuffer: ByteArray) {
        while (running.get()) {
            val reportAmplitude = shouldReportAmplitude()
            val targetBytes = if (reportAmplitude) {
                batchPlan.visualizerReadBytes
            } else {
                batchPlan.backgroundReadBytes
            }

            var encodedAny = false
            while (running.get()) {
                val bytesRead = audioRecord.read(
                    pcmBuffer,
                    0,
                    targetBytes,
                    AudioRecord.READ_NON_BLOCKING,
                )
                if (bytesRead > 0) {
                    encodedAny = true
                    if (reportAmplitude || shouldReportAmplitude()) {
                        onAmplitude(peakAmplitude(pcmBuffer, bytesRead))
                    }
                    encodePcmChunk(pcmBuffer, bytesRead, allowWhileStopping = false)
                    if (bytesRead < targetBytes) {
                        // Drained all currently available frames from the AudioRecord ring.
                        break
                    }
                } else if (bytesRead == 0) {
                    break
                } else {
                    throw IllegalStateException("AudioRecord read failed: $bytesRead")
                }
            }

            if (encodedAny) {
                // Drain output produced by this batch while the CPU is already awake.
                drainEncoder(firstTimeoutUs = BATCH_DRAIN_TIMEOUT_US)
            }

            if (!running.get()) break

            val sleepMs = if (shouldReportAmplitude()) {
                pollingPlan.visualizerSleepMs
            } else {
                pollingPlan.backgroundSleepMs
            }
            synchronized(sleepLock) {
                if (running.get()) {
                    try {
                        sleepLock.wait(sleepMs)
                    } catch (_: InterruptedException) {
                        // Re-check running state.
                    }
                }
            }
        }
    }

    private fun runBlockingBatchedLoop(pcmBuffer: ByteArray) {
        while (running.get()) {
            val reportAmplitude = shouldReportAmplitude()
            val targetBytes = if (reportAmplitude) {
                batchPlan.visualizerReadBytes
            } else {
                batchPlan.backgroundReadBytes
            }
            val bytesRead = audioRecord.read(pcmBuffer, 0, targetBytes, AudioRecord.READ_BLOCKING)
            if (bytesRead > 0) {
                if (reportAmplitude || shouldReportAmplitude()) {
                    onAmplitude(peakAmplitude(pcmBuffer, bytesRead))
                }
                encodePcmChunk(pcmBuffer, bytesRead, allowWhileStopping = false)
                drainEncoder(firstTimeoutUs = 0L)
            } else if (bytesRead < 0) {
                throw IllegalStateException("AudioRecord read failed: $bytesRead")
            }
        }
    }

    private fun drainRemainingAudioRecord(pcmBuffer: ByteArray) {
        var iterations = 0
        while (iterations < MAX_FINAL_READ_PASSES) {
            iterations += 1
            val bytesRead = audioRecord.read(
                pcmBuffer,
                0,
                pcmBuffer.size,
                AudioRecord.READ_NON_BLOCKING,
            )
            if (bytesRead <= 0) break
            encodePcmChunk(pcmBuffer, bytesRead, allowWhileStopping = true)
        }
    }

    private fun encodePcmChunk(pcmBuffer: ByteArray, bytesRead: Int, allowWhileStopping: Boolean) {
        var offset = 0
        val deadlineNs = if (allowWhileStopping) System.nanoTime() + FINAL_DRAIN_NS else Long.MAX_VALUE
        while (offset < bytesRead && (allowWhileStopping || running.get())) {
            if (allowWhileStopping && System.nanoTime() >= deadlineNs) break
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: continue
                input.clear()
                val chunkBytes = minOf(bytesRead - offset, input.remaining())
                if (chunkBytes <= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, nextPresentationTimeUs, 0)
                    break
                }
                input.put(pcmBuffer, offset, chunkBytes)
                val presentationTimeUs = AudioCaptureTimestamps.atSample(
                    startPresentationTimeUs,
                    framesRead,
                    sampleRate,
                )
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    chunkBytes,
                    presentationTimeUs,
                    0,
                )
                offset += chunkBytes
                framesRead += chunkBytes / BYTES_PER_PCM_FRAME
                nextPresentationTimeUs = AudioCaptureTimestamps.atSample(
                    startPresentationTimeUs,
                    framesRead,
                    sampleRate,
                )
            }
            drainEncoder(firstTimeoutUs = 0L)
        }
    }

    private fun finishEncoder() {
        val drainDeadline = System.nanoTime() + FINAL_DRAIN_NS
        var eosQueued = false
        while (!eosQueued && System.nanoTime() < drainDeadline) {
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    nextPresentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                eosQueued = true
            }
            drainEncoder(firstTimeoutUs = 0L)
        }
        while (!encoderEnded) {
            val remainingUs = (drainDeadline - System.nanoTime()) / 1_000L
            if (remainingUs <= 0L) break
            drainEncoder(firstTimeoutUs = minOf(remainingUs, CODEC_TIMEOUT_US))
        }
    }

    private fun drainEncoder(firstTimeoutUs: Long): Boolean {
        val info = outputBufferInfo
        var drained = false
        var timeoutUs = firstTimeoutUs
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            timeoutUs = 0L
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return drained
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val outSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        sampleRate
                    }
                    val outChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        1
                    }
                    sink.onOutputFormatChanged(outSampleRate, outChannels)
                    continue
                }
                outputIndex >= 0 -> {
                    drained = true
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        if (outputScratch.size < info.size) {
                            outputScratch = ByteArray(info.size)
                        }
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(outputScratch, 0, info.size)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            sink.onCodecConfig(outputScratch, info.size)
                        } else {
                            sink.onEncodedFrame(info.presentationTimeUs, outputScratch, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderEnded = true
                        return false
                    }
                }
            }
        }
    }

    private fun peakAmplitude(pcmBuffer: ByteArray, bytesRead: Int): Int {
        var peak = 0
        var i = 0
        val limit = bytesRead - 1
        while (i < limit) {
            val lo = pcmBuffer[i].toInt() and 0xFF
            val hi = pcmBuffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            i += BYTES_PER_PCM_SAMPLE
        }
        return peak
    }

    private fun releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return

        if (codecStarted.compareAndSet(true, false)) {
            runCatching { codec.stop() }
        }
        runCatching { codec.release() }
        runCatching { audioRecord.release() }
    }

    private object MediaCodecInfoProfile {
        const val AACObjectLC = 2
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val BATCH_DRAIN_TIMEOUT_US = 2_000L
        private const val FINAL_DRAIN_NS = 2_000_000_000L
        private const val MAX_FINAL_READ_PASSES = 8
        private const val BYTES_PER_PCM_SAMPLE = 2
        private const val BYTES_PER_PCM_FRAME = 2
    }
}
