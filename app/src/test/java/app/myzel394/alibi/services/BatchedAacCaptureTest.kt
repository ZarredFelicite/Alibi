package app.myzel394.alibi.services

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchedAacCaptureTest {
    @Test
    fun batchSizingPlansCoarseBackgroundReadsAndHeadroomAt16kHz() {
        val plan = AudioBatchSizing.planBuffers(sampleRate = 16_000, minimumBufferSize = 1_280)

        assertEquals(16_384, plan.backgroundReadBytes) // 512 ms at 16 kHz mono 16-bit
        assertEquals(3_200, plan.visualizerReadBytes)  // 100 ms
        assertEquals(16_384, plan.codecMaxInputBytes)
        assertTrue(plan.audioRecordBufferBytes >= 64_000) // >= 2s FIFO
        assertEquals(0, plan.audioRecordBufferBytes % 2)
    }

    @Test
    fun batchSizingCapsLargeSampleRateBatchesAndKeepsFrameAlignment() {
        val plan = AudioBatchSizing.planBuffers(sampleRate = 48_000, minimumBufferSize = 3_840)

        assertEquals(32_768, plan.backgroundReadBytes) // capped at 32 KB (~341 ms at 48 kHz)
        assertEquals(9_600, plan.visualizerReadBytes)  // 100 ms
        assertTrue(plan.audioRecordBufferBytes >= 192_000) // ~2s at 48 kHz
    }

    @Test
    fun pollingPlanMaintainsFourXHeadroomAndFallsBackOnTinyRingBuffers() {
        val largeRing = AudioBatchSizing.planPolling(actualBufferFrames = 32_000, sampleRate = 16_000)
        assertTrue(largeRing.useNonBlockingSleep)
        assertEquals(400L, largeRing.backgroundSleepMs)
        assertEquals(80L, largeRing.visualizerSleepMs)

        val mediumRing = AudioBatchSizing.planPolling(actualBufferFrames = 6_400, sampleRate = 16_000) // 400 ms
        assertTrue(mediumRing.useNonBlockingSleep)
        assertEquals(100L, mediumRing.backgroundSleepMs)
        assertEquals(80L, mediumRing.visualizerSleepMs)

        val tinyRing = AudioBatchSizing.planPolling(actualBufferFrames = 1_600, sampleRate = 16_000) // 100 ms
        assertFalse(tinyRing.useNonBlockingSleep)
    }

    @Test
    fun adtsWriteHeaderMatchesFrameHelper() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val expected = EncodedAudioFrameRingBuffer.AdtsAac.frame(
            rawAacFrame = payload,
            sampleRate = 16_000,
            channelCount = 1,
            audioObjectType = 2,
        )
        val header = ByteArray(7)
        EncodedAudioFrameRingBuffer.AdtsAac.writeHeader(
            target = header,
            rawFrameLength = payload.size,
            frequencyIndex = EncodedAudioFrameRingBuffer.AdtsAac.frequencyIndex(16_000),
            channelCount = 1,
            audioObjectType = 2,
        )
        assertArrayEquals(expected, header + payload)
    }

    @Test
    fun rotatingAdtsFileSinkFlushesAndSeparatesBatchesCleanly() {
        val sink = RotatingAdtsFileSink(sampleRate = 16_000, bufferSizeBytes = 1024)
        val firstBatch = ByteArrayOutputStream()
        val secondBatch = ByteArrayOutputStream()
        val frameA = byteArrayOf(0x01, 0x02, 0x03)
        val frameB = byteArrayOf(0x04, 0x05, 0x06, 0x07)

        sink.switchTo(firstBatch)
        sink.onEncodedFrame(0L, frameA, frameA.size)

        sink.switchTo(secondBatch)
        sink.onEncodedFrame(64_000L, frameB, frameB.size)
        sink.flushAndClose()

        assertArrayEquals(
            EncodedAudioFrameRingBuffer.AdtsAac.frame(frameA, 16_000, 1, 2),
            firstBatch.toByteArray(),
        )
        assertArrayEquals(
            EncodedAudioFrameRingBuffer.AdtsAac.frame(frameB, 16_000, 1, 2),
            secondBatch.toByteArray(),
        )
    }
}
