package app.myzel394.alibi.services

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.myzel394.alibi.enums.RecorderState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingInterruptionTest {
    @Test
    fun manifestDisablesAutoBackup() {
        val appInfo = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun sessionStateConsumesActiveFlagOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RecorderSessionState.markInactive(context)
        assertFalse(RecorderSessionState.consumeActiveRecording(context))

        RecorderSessionState.markActive(context, "AudioRecorderService")
        assertTrue(RecorderSessionState.isActive(context))
        assertTrue(RecorderSessionState.consumeActiveRecording(context))
        assertFalse(RecorderSessionState.isActive(context))
        assertFalse(RecorderSessionState.consumeActiveRecording(context))
    }

    @Test
    fun nullIntentRestartReturnsNotStickyAndConsumesActiveSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RecorderSessionState.markActive(context, "TestRecorderService")

        var result = -1
        lateinit var service: TestRecorderService
        instrumentation.runOnMainSync {
            service = TestRecorderService(context)
            result = service.onStartCommand(null, 0, 1)
        }

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(1, service.foregroundStops)
        assertFalse(RecorderSessionState.isActive(context))
    }

    @Test
    fun initIntentCallsStartForegroundImmediatelyAndAllowsRestartFromStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var service: TestRecorderService

        instrumentation.runOnMainSync {
            service = TestRecorderService(context)
            val initResult = service.onStartCommand(Intent("init"), 0, 1)
            assertEquals(Service.START_STICKY, initResult)
            assertEquals(1, service.foregroundStarts)

            service.startRecording()
            assertEquals(RecorderState.RECORDING, service.state)
            assertTrue(RecorderSessionState.isActive(context))
        }

        runBlocking {
            instrumentation.runOnMainSync {
                runBlocking { service.stopRecording() }
            }
        }
        assertEquals(RecorderState.STOPPED, service.state)
        assertFalse(RecorderSessionState.isActive(context))

        instrumentation.runOnMainSync {
            val reInitResult = service.onStartCommand(Intent("init"), 0, 2)
            assertEquals(Service.START_STICKY, reInitResult)
            service.startRecording()
            assertEquals(RecorderState.RECORDING, service.state)
            assertTrue(RecorderSessionState.isActive(context))
        }

        RecorderSessionState.markInactive(context)
    }

    private class TestRecorderService(
        baseContext: android.content.Context,
    ) : RecorderService() {
        var foregroundStarts = 0
        var foregroundStops = 0

        init {
            attachBaseContext(baseContext)
        }

        override fun startForegroundService() {
            foregroundStarts += 1
        }

        override fun stopForegroundAndSelf(startId: Int?) {
            foregroundStops += 1
        }
    }
}
