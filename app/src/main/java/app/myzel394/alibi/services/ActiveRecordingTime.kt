package app.myzel394.alibi.services

/** Accumulates active (unpaused) recording time without a background ticker. */
internal class ActiveRecordingTime(
    private val monotonicNanos: () -> Long,
) {
    private var accumulatedNanos = 0L
    private var activeSinceNanos: Long? = null
    private var stopped = false

    val elapsedSeconds: Long
        get() = elapsedNanos() / NANOS_PER_SECOND

    fun start() {
        if (activeSinceNanos == null && !stopped) {
            activeSinceNanos = monotonicNanos()
        }
    }

    fun pause() {
        accumulateActiveDuration()
    }

    fun stop() {
        accumulateActiveDuration()
        stopped = true
    }

    private fun elapsedNanos(): Long {
        val since = activeSinceNanos ?: return accumulatedNanos
        return accumulatedNanos + (monotonicNanos() - since).coerceAtLeast(0L)
    }

    private fun accumulateActiveDuration() {
        val since = activeSinceNanos ?: return
        accumulatedNanos += (monotonicNanos() - since).coerceAtLeast(0L)
        activeSinceNanos = null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
