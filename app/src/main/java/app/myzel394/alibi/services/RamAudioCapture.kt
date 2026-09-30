package app.myzel394.alibi.services

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import app.myzel394.alibi.db.AudioRecorderSettings
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Captures transient PCM, retaining only encoded AAC access units in [buffer]. */
class RamAudioCapture(
    private val audioSettings: AudioRecorderSettings,
    val buffer: EncodedAudioFrameRingBuffer,
    private val shouldReportAmplitude: () -> Boolean,
    private val onAmplitude: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val startPresentationTimeUs: Long,
) {
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val workerStarted = AtomicBoolean(false)
    private val codecStarted = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec
    private lateinit var worker: Thread
    private val sampleRate = audioSettings.getEffectiveSamplingRate()
    private val outputBufferInfo = MediaCodec.BufferInfo()
    private var framesRead = 0L
    private var encoderEnded = false

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

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimumBufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

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
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minimumBufferSize)
            }
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = record
            codec = createdCodec
            worker = Thread(::run, "alibi-audio-ram-capture")
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

    fun stop() {
        val shouldJoin = synchronized(lifecycleLock) {
            if (!started.get()) {
                releaseResources()
                return@synchronized false
            }

            running.set(false)
            runCatching { audioRecord.stop() }
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
        val maxReadBytes = audioRecord.bufferSizeInFrames.coerceAtLeast(1024) * 2
        var failure: Throwable? = null
        try {
            while (running.get()) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    val bytesRead = audioRecord.read(input, minOf(input.remaining(), maxReadBytes))
                    if (bytesRead > 0) {
                        if (shouldReportAmplitude()) {
                            onAmplitude(peakAmplitude(input, bytesRead))
                        }
                        val presentationTimeUs = AudioCaptureTimestamps.atSample(
                            startPresentationTimeUs,
                            framesRead,
                            sampleRate,
                        )
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            bytesRead,
                            presentationTimeUs,
                            0,
                        )
                        framesRead += bytesRead / BYTES_PER_PCM_FRAME
                        nextPresentationTimeUs = AudioCaptureTimestamps.atSample(
                            startPresentationTimeUs,
                            framesRead,
                            sampleRate,
                        )
                    } else if (bytesRead < 0) {
                        throw IllegalStateException("AudioRecord read failed: $bytesRead")
                    }
                }
                drainEncoder()
            }

            val drainDeadline = System.nanoTime() + 2_000_000_000L
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
                drainEncoder()
            }
            while (!encoderEnded && System.nanoTime() < drainDeadline) {
                drainEncoder()
            }
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

    private fun drainEncoder(): Boolean {
        val info = outputBufferInfo
        var drained = false
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return drained
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outputIndex >= 0 -> {
                    drained = true
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        output.get(data)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            buffer.setCodecConfig(data)
                        } else {
                            buffer.addOwned(info.presentationTimeUs, data)
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

    private fun peakAmplitude(input: ByteBuffer, bytesRead: Int): Int {
        val originalPosition = input.position()
        input.position(0)
        var peak = 0
        repeat(bytesRead / BYTES_PER_PCM_SAMPLE) {
            peak = maxOf(peak, abs(input.short.toInt()))
        }
        input.position(originalPosition)
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
        private const val BYTES_PER_PCM_SAMPLE = 2
        private const val BYTES_PER_PCM_FRAME = 2
    }
}

internal object AudioCaptureTimestamps {
    fun atSample(startPresentationTimeUs: Long, framesRead: Long, sampleRate: Int): Long {
        return startPresentationTimeUs + framesRead * 1_000_000L / sampleRate
    }
}
