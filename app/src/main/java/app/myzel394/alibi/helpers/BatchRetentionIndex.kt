package app.myzel394.alibi.helpers

/** Synchronized by the owning folder; stores only numeric recording batches. */
internal class BatchRetentionIndex<T> {
    private val entries = java.util.TreeMap<Long, MutableSet<T>>()
    var isInitialized: Boolean = false
        private set

    fun replaceAll(entries: Iterable<Pair<Long, T>>) {
        // Preserve registrations made before the first provider reconciliation.
        val registered = this.entries.flatMap { (counter, refs) -> refs.map { counter to it } }
        this.entries.clear()
        (entries.asSequence() + registered.asSequence()).forEach { (counter, reference) ->
            register(counter, reference)
        }
        isInitialized = true
    }

    fun register(counter: Long, reference: T) {
        entries.getOrPut(counter) { linkedSetOf() }.add(reference)
    }

    fun candidates(range: LongRange): List<Pair<Long, T>> = if (range.isEmpty()) emptyList() else
        entries.subMap(range.first, true, range.last, true)
            .flatMap { (counter, refs) -> refs.map { counter to it } }

    fun prune(range: LongRange, delete: (T) -> Boolean): Int {
        var deleted = 0
        candidates(range).forEach { (counter, reference) ->
            if (runCatching { delete(reference) }.getOrDefault(false)) {
                remove(counter, reference)
                deleted++
            }
        }
        return deleted
    }

    fun remove(counter: Long, reference: T) {
        entries[counter]?.let { refs ->
            refs.remove(reference)
            if (refs.isEmpty()) entries.remove(counter)
        }
    }

    fun invalidate() {
        entries.clear()
        isInitialized = false
    }
}
