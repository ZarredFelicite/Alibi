package app.myzel394.alibi.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRetentionIndexTest {
    @Test
    fun visitsOnlyExpiredReferences() {
        val index = BatchRetentionIndex<String>()
        index.replaceAll((0L..9L).map { it to "batch-$it" })
        val visited = mutableListOf<String>()
        index.prune(0L..3L) { visited += it; true }
        assertEquals(listOf("batch-0", "batch-1", "batch-2", "batch-3"), visited)
    }

    @Test
    fun repeatedRangeDoesNotRequireReconciliation() {
        val index = BatchRetentionIndex<String>()
        var reconciliations = 0
        if (!index.isInitialized) {
            reconciliations++
            index.replaceAll(listOf(1L to "one"))
        }
        index.prune(1L..1L) { true }
        if (!index.isInitialized) reconciliations++
        index.prune(1L..1L) { true }
        assertEquals(1, reconciliations)
    }

    @Test
    fun protectedRangeIsNoOp() {
        val index = BatchRetentionIndex<String>()
        index.replaceAll((0L..5L).map { it to "batch-$it" })
        assertEquals(0, index.prune(6L..5L) { error("must not delete") })
        assertEquals(6, index.candidates(0L..5L).size)
    }

    @Test
    fun registeredBatchBecomesVisibleImmediately() {
        val index = BatchRetentionIndex<String>()
        index.replaceAll(listOf(1L to "one"))
        index.register(2L, "two")
        assertEquals(listOf(2L to "two"), index.candidates(2L..2L))
    }

    @Test
    fun registrationBeforeReconciliationIsMergedAndDeduplicated() {
        val index = BatchRetentionIndex<String>()
        index.register(4L, "known-name")
        index.replaceAll(listOf(2L to "existing", 4L to "known-name"))

        assertEquals(listOf(4L to "known-name"), index.candidates(4L..4L))
        assertEquals(listOf(2L to "existing", 4L to "known-name"), index.candidates(0L..5L))
    }

    @Test
    fun thrownDeletionRemainsAvailableForRetry() {
        val index = BatchRetentionIndex<String>()
        index.replaceAll(listOf(5L to "five"))
        assertEquals(0, index.prune(5L..5L) { throw IllegalStateException("provider unavailable") })
        assertTrue(index.candidates(5L..5L).isNotEmpty())
        assertEquals(1, index.prune(5L..5L) { true })
    }

    @Test
    fun failedDeletionRemainsAvailableForRetry() {
        val index = BatchRetentionIndex<String>()
        index.replaceAll(listOf(3L to "three"))
        assertEquals(0, index.prune(3L..3L) { false })
        assertTrue(index.candidates(3L..3L).isNotEmpty())
        assertEquals(1, index.prune(3L..3L) { true })
        assertFalse(index.candidates(3L..3L).isNotEmpty())
    }
}
