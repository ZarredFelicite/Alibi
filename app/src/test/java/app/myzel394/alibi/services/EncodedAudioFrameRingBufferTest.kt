package app.myzel394.alibi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedAudioFrameRingBufferTest {
    @Test
    fun evictsFramesOlderThanRollingDurationAndKeepsOrder() {
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 100, maxBytes = 100)
        ring.add(0, byteArrayOf(0))
        ring.add(50, byteArrayOf(1))
        ring.add(150, byteArrayOf(2))

        assertEquals(listOf(50L, 150L), ring.snapshot().map { it.presentationTimeUs })
    }

    @Test
    fun enforcesEncodedByteBound() {
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 10_000, maxBytes = 5)
        ring.add(0, byteArrayOf(0, 1, 2))
        ring.add(1, byteArrayOf(3, 4, 5))

        assertEquals(3, ring.sizeBytes())
        assertArrayEquals(byteArrayOf(3, 4, 5), ring.snapshot().single().data)
    }

    @Test
    fun wrapsRawAacFrameInValidAdtsHeader() {
        val output = EncodedAudioFrameRingBuffer.AdtsAac.frame(
            rawAacFrame = byteArrayOf(1, 2, 3),
            sampleRate = 16_000,
            channelCount = 1,
            audioObjectType = 2,
        )

        assertEquals(10, output.size)
        assertEquals(0xFF.toByte(), output[0])
        assertEquals(0xF1.toByte(), output[1])
        assertEquals(8, (output[2].toInt() shr 2) and 0x0F)
        assertEquals(1, (output[2].toInt() and 1) shl 2 or (output[3].toInt() shr 6 and 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.copyOfRange(7, 10))
    }
}
