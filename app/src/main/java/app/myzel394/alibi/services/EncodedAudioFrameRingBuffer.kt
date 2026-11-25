package app.myzel394.alibi.services

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** Thread-safe bounded store for encoded AAC access units, never raw PCM. */
class EncodedAudioFrameRingBuffer(
    private val maxDurationUs: Long,
    private val maxBytes: Int,
) {
    data class Frame(val presentationTimeUs: Long, val data: ByteArray)

    private val frames = ArrayDeque<Frame>()
    private var byteCount = 0
    private var codecConfig: ByteArray? = null

    init {
        require(maxDurationUs > 0) { "maxDurationUs must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun setCodecConfig(data: ByteArray) {
        synchronized(this) {
            codecConfig = data.copyOf()
        }
    }

    fun getCodecConfig(): ByteArray? = synchronized(this) { codecConfig?.copyOf() }

    fun add(presentationTimeUs: Long, data: ByteArray) {
        if (data.isEmpty() || data.size > maxBytes) return
        addFrame(presentationTimeUs, data.copyOf())
    }

    /** Stores [data] without copying; the caller must relinquish ownership and never mutate it. */
    internal fun addOwned(presentationTimeUs: Long, data: ByteArray) {
        if (data.isEmpty() || data.size > maxBytes) return
        addFrame(presentationTimeUs, data)
    }

    private fun addFrame(presentationTimeUs: Long, data: ByteArray) {
        synchronized(this) {
            val frame = Frame(presentationTimeUs, data)
            frames.addLast(frame)
            byteCount += frame.data.size

            val earliestAllowed = presentationTimeUs - maxDurationUs
            while (frames.isNotEmpty() &&
                (frames.first.presentationTimeUs < earliestAllowed || byteCount > maxBytes)
            ) {
                byteCount -= frames.removeFirst().data.size
            }
        }
    }

    fun snapshot(): List<Frame> = synchronized(this) {
        frames.map { Frame(it.presentationTimeUs, it.data.copyOf()) }
    }

    fun sizeBytes(): Int = synchronized(this) { byteCount }

    fun size(): Int = synchronized(this) { frames.size }

    fun durationUs(): Long = synchronized(this) {
        if (frames.size < 2) 0 else frames.last.presentationTimeUs - frames.first.presentationTimeUs
    }

    fun snapshotAsAdtsAac(
        sampleRate: Int,
        channelCount: Int,
        audioObjectType: Int = 2,
    ): ByteArray {
        val snapshot = snapshot()
        val output = ByteArrayOutputStream()
        snapshot.forEach { frame ->
            output.write(
                AdtsAac.frame(
                    rawAacFrame = frame.data,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    audioObjectType = audioObjectType,
                )
            )
        }
        return output.toByteArray()
    }

    object AdtsAac {
        private val sampleRateIndex = mapOf(
            96000 to 0,
            88200 to 1,
            64000 to 2,
            48000 to 3,
            44100 to 4,
            32000 to 5,
            24000 to 6,
            22050 to 7,
            16000 to 8,
            12000 to 9,
            11025 to 10,
            8000 to 11,
            7350 to 12,
        )

        fun isSupportedSampleRate(sampleRate: Int): Boolean =
            sampleRateIndex.containsKey(sampleRate)

        fun frequencyIndex(sampleRate: Int): Int =
            sampleRateIndex[sampleRate]
                ?: throw IllegalArgumentException("Unsupported AAC sample rate: $sampleRate")

        fun writeHeader(
            target: ByteArray,
            rawFrameLength: Int,
            frequencyIndex: Int,
            channelCount: Int,
            audioObjectType: Int = 2,
        ) {
            require(target.size >= 7) { "ADTS header buffer must be at least 7 bytes" }
            require(rawFrameLength > 0) { "AAC frame must not be empty" }
            require(channelCount in 1..7) { "Unsupported AAC channel count" }
            require(audioObjectType in 1..4) { "Unsupported AAC object type" }

            val frameLength = rawFrameLength + 7
            require(frameLength <= 0x1FFF) { "AAC frame is too large for ADTS" }

            target[0] = 0xFF.toByte()
            target[1] = 0xF1.toByte() // MPEG-4, no CRC
            target[2] = (((audioObjectType - 1) shl 6) or
                    (frequencyIndex shl 2) or
                    (channelCount shr 2)).toByte()
            target[3] = (((channelCount and 3) shl 6) or
                    (frameLength shr 11)).toByte()
            target[4] = (frameLength shr 3).toByte()
            target[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
            target[6] = 0xFC.toByte()
        }

        fun frame(
            rawAacFrame: ByteArray,
            sampleRate: Int,
            channelCount: Int,
            audioObjectType: Int,
        ): ByteArray {
            val header = ByteArray(7)
            writeHeader(
                target = header,
                rawFrameLength = rawAacFrame.size,
                frequencyIndex = frequencyIndex(sampleRate),
                channelCount = channelCount,
                audioObjectType = audioObjectType,
            )
            return header + rawAacFrame
        }
    }
}
