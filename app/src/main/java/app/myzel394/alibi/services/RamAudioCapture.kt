package app.myzel394.alibi.services

import android.media.AudioDeviceInfo
import app.myzel394.alibi.db.AudioRecorderSettings

/** Captures transient PCM, retaining only encoded AAC access units in [buffer]. */
class RamAudioCapture(
    audioSettings: AudioRecorderSettings,
    val buffer: EncodedAudioFrameRingBuffer,
    shouldReportAmplitude: () -> Boolean,
    onAmplitude: (Int) -> Unit,
    onError: (Throwable) -> Unit,
    startPresentationTimeUs: Long,
    preferredDevice: AudioDeviceInfo? = null,
) {
    private val delegate = BatchedAacCapture(
        audioSettings = audioSettings,
        sink = RingBufferAudioSink(buffer),
        shouldReportAmplitude = shouldReportAmplitude,
        onAmplitude = onAmplitude,
        onError = onError,
        startPresentationTimeUs = startPresentationTimeUs,
        preferredDevice = preferredDevice,
    )

    val nextPresentationTimeUs: Long
        get() = delegate.nextPresentationTimeUs

    fun start() = delegate.start()

    fun notifyWakeup() = delegate.notifyWakeup()

    fun stop() = delegate.stop()
}

internal object AudioCaptureTimestamps {
    fun atSample(startPresentationTimeUs: Long, framesRead: Long, sampleRate: Int): Long {
        return startPresentationTimeUs + framesRead * 1_000_000L / sampleRate
    }
}
