package app.myzel394.alibi.ui.models

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import app.myzel394.alibi.DiagnosticLog
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.helpers.BatchesFolder
import app.myzel394.alibi.services.IntervalRecorderService
import app.myzel394.alibi.services.RecorderNotificationHelper
import app.myzel394.alibi.services.RecorderService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json

abstract class BaseRecorderModel<I, B : BatchesFolder, T : IntervalRecorderService<I, B>> :
    ViewModel() {
    protected abstract val intentClass: Class<T>

    var recorderState by mutableStateOf(RecorderState.IDLE)
        protected set
    var recordingTime by mutableLongStateOf(0)
        protected set

    open val isInRecording: Boolean
        get() = recorderService != null

    open val isCurrentlyActivelyRecording
        get() = recorderState === RecorderState.RECORDING

    val isPaused: Boolean
        get() = recorderState === RecorderState.PAUSED

    val progress: Float
        get() = recordingTime.toFloat() / (recorderService!!.settings.maxDuration / 1000)

    var recorderService by mutableStateOf<T?>(null)
        protected set

    val recordingStart
        get() = recorderService!!.recordingStart

    // If `isSavingAsOldRecording` is true, the user is saving an old recording,
    // thus the service is not running and thus doesn't need to be stopped or destroyed
    var onRecordingSave: (cleanupOldFiles: Boolean) -> CompletableDeferred<Unit> = {
        throw NotImplementedError("onRecordingSave not implemented")
    }
    var onRecordingStart: () -> Unit = {}
    var onError: () -> Unit = {}
    var onBatchesFolderNotAccessible: () -> Unit = {}
    abstract var batchesFolder: B?

    private var notificationDetails: RecorderNotificationHelper.NotificationDetails? = null
    private var startGeneration = 0L
    private var explicitConnection: ServiceConnection? = null

    protected var isExplicitStartConnection = false
        private set

    var settings: AppSettings? = null
        protected set

    protected abstract fun onServiceConnected(service: T)

    private val connection = createServiceConnection(isExplicit = false)

    private fun createServiceConnection(
        isExplicit: Boolean,
        generation: Long = startGeneration,
    ) = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // Ignore a delayed callback from a start request that has since been superseded.
            if (isExplicit && generation != startGeneration) {
                DiagnosticLog.log("stale_service_callback_skipped", "service=${intentClass.simpleName}")
                return
            }

            val recorder = (service as RecorderService.RecorderBinder).getService() as T
            DiagnosticLog.log("service_bound", "service=${intentClass.simpleName};mode=${if (isExplicit) "explicit" else "passive"};state=${recorder.state}")
            recorderService = recorder

            // Init variables from us to the service
            recorder.onStateChange = { state ->
                recorderState = state
                recordingTime = recorder.recordingTime
            }
            recordingTime = recorder.recordingTime
            recorder.onError = {
                onError()
            }
            recorder.onBatchesFolderNotAccessible = {
                onBatchesFolderNotAccessible()
            }

            if (batchesFolder != null) {
                recorder.batchesFolder = batchesFolder!!
            } else {
                batchesFolder = recorder.batchesFolder
            }

            if (settings != null) {
                // If `settings` is set, it means we started the recording, so it should be
                // properly set on the service
                recorder.settings = settings!!
            } else if (recorder.hasInitializedSettings) {
                settings = recorder.settings
            }

            isExplicitStartConnection = isExplicit
            try {
                // Rest should be initialized from the child class
                onServiceConnected(recorder)
            } finally {
                isExplicitStartConnection = false
            }

            if (!isExplicit && recorder.state != RecorderState.RECORDING &&
                recorder.state != RecorderState.PAUSED
            ) {
                // An idle or stopped service is not a live recording. Keep it out of the UI and
                // avoid exposing uninitialized recordingStart/settings to recording status UI.
                DiagnosticLog.log("stale_service_ignored", "service=${intentClass.simpleName};state=${recorder.state}")
                recorderService = null
                recorderState = RecorderState.IDLE
                recordingTime = 0
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            // `onServiceDisconnected` is called when the connection is unexpectedly lost,
            // so we need to make sure to manually call `reset` to clean up in other places
            reset()
        }
    }

    open fun reset() {
        recorderService = null
        recorderState = RecorderState.IDLE
        recordingTime = 0
    }

    protected open fun handleIntent(intent: Intent) = intent

    private fun stopOldServices(context: Context) {
        startGeneration += 1
        runCatching {
            context.unbindService(connection)
        }
        explicitConnection?.let { oldConnection ->
            runCatching {
                context.unbindService(oldConnection)
            }
        }
        explicitConnection = null

        val intent = Intent(context, intentClass)
        runCatching {
            context.stopService(intent)
        }
    }

    // If override, call `super` AFTER setting the settings
    open fun startRecording(
        context: Context,
        settings: AppSettings,
    ) {
        this.settings = settings

        // Clean up
        stopOldServices(context)

        notificationDetails = settings.notificationSettings.let {
            if (it == null)
                null
            else
                RecorderNotificationHelper.NotificationDetails.fromNotificationSettings(
                    context,
                    it
                )
        }

        val intent = Intent(context, intentClass).apply {
            action = "init"

            if (notificationDetails != null) {
                putExtra(
                    "notificationDetails",
                    Json.encodeToString(
                        RecorderNotificationHelper.NotificationDetails.serializer(),
                        notificationDetails!!,
                    ),
                )
            }
        }.let(::handleIntent)
        ContextCompat.startForegroundService(context, intent)
        val generation = startGeneration
        val newConnection = createServiceConnection(isExplicit = true, generation = generation)
        explicitConnection = newConnection
        if (!context.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)) {
            explicitConnection = null
        }
    }

    suspend fun stopRecording(context: Context) {
        recorderService!!.stopRecording()
    }

    fun pauseRecording() {
        recorderService!!.pauseRecording()
    }

    fun resumeRecording() {
        recorderService!!.resumeRecording()
    }

    fun destroyService(context: Context) {
        recorderService!!.destroy()

        stopOldServices(context)
        reset()
    }

    // Bind functions used to manually bind to the service if the app
    // is closed and reopened for example
    fun bindToService(context: Context) {
        DiagnosticLog.log("service_bind_requested", "service=${intentClass.simpleName};mode=passive")
        Intent(context, intentClass).also { intent ->
            context.bindService(intent, connection, 0)
        }
    }

    /** Refreshes elapsed time when the recorder UI is visible. */
    fun refreshRecordingTime() {
        recordingTime = recorderService?.recordingTime ?: 0L
    }

    fun unbindFromService(context: Context) {
        runCatching {
            context.unbindService(connection)
        }
        explicitConnection?.let { activeConnection ->
            runCatching {
                context.unbindService(activeConnection)
            }
        }
        explicitConnection = null
    }
}