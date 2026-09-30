package app.myzel394.alibi.services

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.helpers.AudioBatchesFolder
import app.myzel394.alibi.helpers.BatchesFolder
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRetentionIntegrationTest {
    @Test
    fun activeSaveLockPreservesRegisteredBatchesAcrossRetentionRotations() {
        val service = newService()
        val folder = service.batchesFolder
        var saveLock: ActiveSaveLock? = null

        try {
            val batches = (0L..12L).associateWith { createBatch(folder, it) }
            saveLock = service.lockFiles()

            repeat(11) { service.startNewCycle() }
            batches.forEach { (index, file) ->
                assertTrue("Batch $index disappeared while save lock was held", file.exists())
            }

            service.unlockFiles(saveLock)
            service.startNewCycle() // Counter 12: retention now prunes through index 9.

            (0L..9L).forEach { index ->
                assertFalse("Expired batch $index was not pruned", batches.getValue(index).exists())
            }
            (10L..12L).forEach { index ->
                assertTrue("Retained batch $index was pruned", batches.getValue(index).exists())
            }
        } finally {
            service.unlockFiles(saveLock)
            cleanUp(folder)
        }
    }

    @Test
    fun overlappingSaveCleanupPreservesOtherSaveAndCurrentBatch() {
        val service = newService()
        val folder = service.batchesFolder
        var firstLock: ActiveSaveLock? = null
        var secondLock: ActiveSaveLock? = null

        try {
            repeat(5) { service.startNewCycle() }
            val batches = (0L..5L).associateWith { createBatch(folder, it) }
            firstLock = service.lockFiles()
            secondLock = service.lockFiles()

            service.deleteRecordingsForSave(firstLock)
            batches.values.forEach { file ->
                assertTrue("Overlapping save deleted a shared batch", file.exists())
            }

            service.unlockFiles(firstLock)
            service.deleteRecordingsForSave(secondLock)

            (0L..4L).forEach { index ->
                assertFalse("Completed batch $index was not cleaned up", batches.getValue(index).exists())
            }
            assertTrue("Current batch was deleted during recording", batches.getValue(5L).exists())
        } finally {
            service.unlockFiles(firstLock)
            service.unlockFiles(secondLock)
            cleanUp(folder)
        }
    }

    private fun newService(): RetentionTestService {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var service: RetentionTestService
        instrumentation.runOnMainSync {
            service = RetentionTestService(
                instrumentation.targetContext,
                ".retention-test-${UUID.randomUUID()}",
            ).apply {
                settings = AppSettings(maxDuration = 30_000L, intervalDuration = 10_000L)
            }
        }
        return service
    }

    private fun createBatch(folder: AudioBatchesFolder, index: Long): File {
        val file = folder.asInternalGetFile(index, "aac")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.writeBytes(byteArrayOf(index.toByte()))
        return file
    }

    private fun cleanUp(folder: AudioBatchesFolder) {
        folder.deleteRecordings()
        folder.getInternalFolder().deleteRecursively()
    }

    private class RetentionTestService(
        context: Context,
        subfolder: String,
    ) : IntervalRecorderService<Unit, AudioBatchesFolder>() {
        override var batchesFolder = AudioBatchesFolder(
            context = context,
            type = BatchesFolder.BatchType.INTERNAL,
            subfolderName = subfolder,
        )

        override fun getRecordingInformation() = Unit

        override fun startForegroundService() = Unit
    }
}
