package app.myzel394.alibi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingRetentionPolicyTest {
    @Test
    fun activeSaveAtLateCounterProtectsOlderEnumeratedBatches() {
        val protectedFrom = RecordingRetentionPolicy.oldestProtectedIndex()

        // FFmpeg enumerates all numeric batches, including old leftovers, so a
        // save at cycle 20 protects from index 0 rather than only the rolling window.
        assertNull(RecordingRetentionPolicy.lastPrunableIndex(20, 3, protectedFrom))
    }

    @Test
    fun overlappingSavesKeepPruningBlockedUntilEverySaveReleases() {
        val olderSaveProtection = RecordingRetentionPolicy.oldestProtectedIndex()
        val newerSaveProtection = RecordingRetentionPolicy.oldestProtectedIndex()

        assertNull(RecordingRetentionPolicy.lastPrunableIndex(24, 3, minOf(olderSaveProtection, newerSaveProtection)))
        // Once the captured service token is released in finally, normal retention resumes.
        assertEquals(21L, RecordingRetentionPolicy.lastPrunableIndex(24, 3, null))
    }

    @Test
    fun cleanupDoesNotDeleteNewerOrAnotherSavesProtectedBatches() {
        assertEquals(0L, RecordingRetentionPolicy.cleanupEndExclusive(5, 0))
        assertEquals(5L, RecordingRetentionPolicy.cleanupEndExclusive(5, null))
        assertEquals(
            -1L,
            RecordingRetentionPolicy.immediateDeleteEndInclusive(5, 5, 0, preserveCurrentBatch = true),
        )
        assertEquals(
            5L,
            RecordingRetentionPolicy.immediateDeleteEndInclusive(5, 6, null, preserveCurrentBatch = true),
        )
        assertEquals(
            0L,
            RecordingRetentionPolicy.immediateDeleteEndInclusive(0, 0, null, preserveCurrentBatch = false),
        )
    }

    @Test
    fun immediateDeleteKeepsCurrentBatchUntilNextRotation() {
        assertEquals(
            4L,
            RecordingRetentionPolicy.immediateDeleteEndInclusive(5, 5, null, preserveCurrentBatch = true),
        )
        assertEquals(
            5L,
            RecordingRetentionPolicy.immediateDeleteEndInclusive(5, 6, null, preserveCurrentBatch = true),
        )
    }
}
