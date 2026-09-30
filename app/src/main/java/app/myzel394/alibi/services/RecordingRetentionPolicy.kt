package app.myzel394.alibi.services

/** Pure index calculations shared by interval retention and active-save cleanup. */
internal object RecordingRetentionPolicy {
    // BatchesFolder enumeration includes every numeric batch, not just the nominal
    // rolling window. Protect all indices while any save may still enumerate them.
    fun oldestProtectedIndex(): Long = 0L

    fun lastPrunableIndex(
        counter: Long,
        retentionIntervals: Long,
        oldestProtectedIndex: Long?,
    ): Long? {
        val retentionEnd = counter - retentionIntervals
        val protectedEnd = oldestProtectedIndex?.minus(1) ?: Long.MAX_VALUE
        return minOf(retentionEnd, protectedEnd).takeIf { it > 0L }
    }

    fun cleanupEndExclusive(
        savedThroughIndex: Long,
        otherSavesOldestProtectedIndex: Long?,
    ): Long = minOf(savedThroughIndex, otherSavesOldestProtectedIndex ?: Long.MAX_VALUE)

    fun immediateDeleteEndInclusive(
        savedThroughIndex: Long,
        currentCounter: Long,
        otherSavesOldestProtectedIndex: Long?,
        preserveCurrentBatch: Boolean,
    ): Long = minOf(
        savedThroughIndex,
        otherSavesOldestProtectedIndex?.minus(1) ?: Long.MAX_VALUE,
        if (preserveCurrentBatch) currentCounter - 1 else Long.MAX_VALUE,
    )
}
