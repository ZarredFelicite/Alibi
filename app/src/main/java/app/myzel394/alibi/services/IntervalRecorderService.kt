package app.myzel394.alibi.services

import app.myzel394.alibi.DiagnosticLog
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.helpers.BatchesFolder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal data class ActiveSaveLock(
    val id: Long,
    val savedThroughIndex: Long,
    val oldestProtectedIndex: Long,
)

abstract class IntervalRecorderService<I, B : BatchesFolder> :
    RecorderService() {
    protected var counter = 0L
        private set

    private val retentionLock = Any()
    private var nextSaveLockId = 0L
    private val activeSaveLocks = mutableMapOf<Long, ActiveSaveLock>()

    lateinit var settings: AppSettings

    val hasInitializedSettings: Boolean
        get() = this::settings.isInitialized

    private lateinit var cycleTimer: ScheduledExecutorService

    protected open val usesIntervalBatches: Boolean
        get() = true

    abstract var batchesFolder: B

    var onBatchesFolderNotAccessible: () -> Unit = {}

    abstract fun getRecordingInformation(): I

    // Protect the complete rolling window needed by this save. Each save gets its own
    // handle so overlapping saves cannot unlock one another.
    internal fun lockFiles(): ActiveSaveLock = synchronized(retentionLock) {
        val oldestProtectedIndex = if (usesIntervalBatches) {
            RecordingRetentionPolicy.oldestProtectedIndex()
        } else {
            // RAM capture materializes its own snapshot and has no rotating batch window.
            Long.MAX_VALUE
        }
        val lock = ActiveSaveLock(
            id = nextSaveLockId++,
            savedThroughIndex = counter,
            oldestProtectedIndex = oldestProtectedIndex,
        )
        activeSaveLocks[lock.id] = lock
        lock
    }

    // Releases exactly this save's protection, regardless of later recorder state.
    internal fun unlockFiles(lock: ActiveSaveLock?, cleanupFiles: Boolean = false) {
        if (lock == null) return

        synchronized(retentionLock) {
            if (activeSaveLocks.remove(lock.id) == null) return

            if (cleanupFiles) {
                val otherOldestProtectedIndex = activeSaveLocks.values.minOfOrNull {
                    it.oldestProtectedIndex
                }
                val cleanupEnd = RecordingRetentionPolicy.cleanupEndExclusive(
                    lock.savedThroughIndex,
                    otherOldestProtectedIndex,
                )
                if (cleanupEnd > 0L) {
                    batchesFolder.deleteRecordings(0..<cleanupEnd)
                }
            }
        }
    }

    // After concatenation, immediate cleanup may remove only batches no longer
    // needed by another in-flight save and never batches newer than this save.
    internal fun deleteRecordingsForSave(lock: ActiveSaveLock?) {
        if (lock == null) return

        synchronized(retentionLock) {
            if (lock.id !in activeSaveLocks) return
            val otherOldestProtectedIndex = activeSaveLocks.values
                .filter { it.id != lock.id }
                .minOfOrNull { it.oldestProtectedIndex }
            val lastDeletableIndex = RecordingRetentionPolicy.immediateDeleteEndInclusive(
                savedThroughIndex = lock.savedThroughIndex,
                currentCounter = counter,
                otherSavesOldestProtectedIndex = otherOldestProtectedIndex,
                preserveCurrentBatch = usesIntervalBatches,
            )
            if (lastDeletableIndex >= 0L) {
                batchesFolder.deleteRecordings(0..lastDeletableIndex)
            }
        }
    }

    // Make overrideable
    open fun startNewCycle() = synchronized(retentionLock) {
        counter += 1
        if (counter % 12L == 0L) {
            DiagnosticLog.log("recording_cycle_checkpoint", "service=${javaClass.simpleName};cycle=$counter")
        }
        deleteOldRecordings()
    }

    private fun createTimer() {
        cycleTimer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate(
                {
                    try {
                        startNewCycle()
                    } catch (error: Throwable) {
                        DiagnosticLog.logException("recording_cycle_failure", error)
                        throw error
                    }
                },
                0,
                settings.intervalDuration,
                TimeUnit.MILLISECONDS
            )
        }
    }

    override fun start() {
        super.start()

        batchesFolder.initFolders()

        if (!batchesFolder.checkIfFolderIsAccessible()) {
            onBatchesFolderNotAccessible()

            throw AvoidErrorDialogError()
        }

        if (usesIntervalBatches) {
            createTimer()
        }
    }

    override fun pause() {
        super.pause()
        if (::cycleTimer.isInitialized) {
            cycleTimer.shutdown()
        }
    }

    override fun resume() {
        super.resume()
        if (usesIntervalBatches) {
            createTimer()
        }
    }

    override suspend fun stop() {
        if (::cycleTimer.isInitialized) {
            cycleTimer.shutdown()
        }
        batchesFolder.cleanup()
        super.stop()
    }

    fun clearAllRecordings() {
        batchesFolder.deleteRecordings()
    }

    private fun deleteOldRecordings() {
        val timeMultiplier = settings.maxDuration / settings.intervalDuration
        val oldestProtectedIndex = activeSaveLocks.values.minOfOrNull {
            it.oldestProtectedIndex
        }
        val lastDeletableIndex = RecordingRetentionPolicy.lastPrunableIndex(
            counter,
            timeMultiplier,
            oldestProtectedIndex,
        ) ?: return

        batchesFolder.deleteRecordings(0..lastDeletableIndex)
    }
}