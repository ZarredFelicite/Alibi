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
    private val onAmplitude: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(true)
    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec
    private lateinit var worker: Thread
    private var framesRead = 0L
    private var encoderEnded = false

    init {
        val sampleRate = audioSettings.getEffectiveSamplingRate()
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
        try {
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not start recording"
            }
            codec.start()
            worker.start()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { audioRecord.stop() }
        if (Thread.currentThread() !== worker) {
            runCatching { worker.join(2_000) }
        }
        release()
    }

    private fun run() {
        val pcm = ByteArray(audioRecord.bufferSizeInFrames.coerceAtLeast(1024) * 2)
        try {
            while (running.get()) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    val bytesRead = audioRecord.read(input, minOf(input.remaining(), pcm.size))
                    if (bytesRead > 0) {
                        val amplitude = peakAmplitude(input, bytesRead)
                        onAmplitude(amplitude)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            bytesRead,
                            framesRead * 1_000_000L / audioSettings.getEffectiveSamplingRate(),
                            0,
                        )
                        framesRead += bytesRead / BYTES_PER_PCM_FRAME
                    } else if (bytesRead < 0) {
                        throw IllegalStateException("AudioRecord read failed: $bytesRead")
                    }
                }
                drainEncoder()
            }

            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    framesRead * 1_000_000L / audioSettings.getEffectiveSamplingRate(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            val drainDeadline = System.nanoTime() + 2_000_000_000L
            while (!encoderEnded && System.nanoTime() < drainDeadline) {
                drainEncoder()
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        }
    }

    private fun drainEncoder(): Boolean {
        val info = MediaCodec.BufferInfo()
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
                            buffer.add(info.presentationTimeUs, data)
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

    private fun release() {
        runCatching { codec.stop() }
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
