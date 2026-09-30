package app.myzel394.alibi.services

import app.myzel394.alibi.db.AudioRecorderSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncodedAudioFrameRingBufferTest {
    @Test
    fun ramBufferIsOptInForNewAndExistingSettings() {
        assertFalse(AudioRecorderSettings.getDefaultInstance().experimentalRamBuffer)
        assertFalse(Json.decodeFromString<AudioRecorderSettings>("{}").experimentalRamBuffer)
    }

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
    fun resumedCaptureStartsAfterPreviousCaptureTimestamp() {
        val sampleRate = 16_000
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 1_000_000, maxBytes = 100)
        ring.add(AudioCaptureTimestamps.atSample(0, 0, sampleRate), byteArrayOf(0))

        val resumedAt = AudioCaptureTimestamps.atSample(0, 1_600, sampleRate)
        ring.add(AudioCaptureTimestamps.atSample(resumedAt, 0, sampleRate), byteArrayOf(1))

        assertEquals(listOf(0L, 100_000L), ring.snapshot().map { it.presentationTimeUs })
        assertEquals(100_000L, ring.durationUs())
    }

    @Test
    fun publicAddAndSnapshotsAreDefensiveCopies() {
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 1_000, maxBytes = 100)
        val input = byteArrayOf(1, 2)
        ring.add(0, input)
        input[0] = 9

        val firstSnapshot = ring.snapshot()
        assertArrayEquals(byteArrayOf(1, 2), firstSnapshot.single().data)
        firstSnapshot.single().data[1] = 8
        assertArrayEquals(byteArrayOf(1, 2), ring.snapshot().single().data)
    }

    @Test
    fun ownedInsertionRetainsFramesAndEvictsByDurationAndByteBound() {
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 100, maxBytes = 4)
        ring.addOwned(0, byteArrayOf(0, 1))
        ring.addOwned(50, byteArrayOf(2, 3))
        ring.addOwned(150, byteArrayOf(4, 5, 6))

        assertEquals(3, ring.sizeBytes())
        assertEquals(listOf(150L), ring.snapshot().map { it.presentationTimeUs })
        assertArrayEquals(byteArrayOf(4, 5, 6), ring.snapshot().single().data)
    }

    @Test
    fun snapshotIncludesTheFinalEncodedFrame() {
        val ring = EncodedAudioFrameRingBuffer(maxDurationUs = 1_000, maxBytes = 100)
        ring.add(0, byteArrayOf(1))
        val finalFrame = byteArrayOf(2, 3)
        ring.add(500, finalFrame)
        finalFrame[0] = 9

        assertArrayEquals(byteArrayOf(2, 3), ring.snapshot().last().data)
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
