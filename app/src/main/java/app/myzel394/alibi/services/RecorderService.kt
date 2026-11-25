package app.myzel394.alibi.services

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.myzel394.alibi.DiagnosticLog
import app.myzel394.alibi.NotificationHelper
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.ui.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean


abstract class RecorderService : LifecycleService() {
    private val binder = RecorderBinder()

    private var isPaused: Boolean = false
    lateinit var recordingStart: LocalDateTime
        private set
    private val activeRecordingTime = ActiveRecordingTime(SystemClock::elapsedRealtimeNanos)
    private var notificationDetails: RecorderNotificationHelper.NotificationDetails? = null
    private val errorReportedForSession = AtomicBoolean(false)

    var state = RecorderState.IDLE
        private set

    var onStateChange: ((RecorderState) -> Unit)? = null
    var onError: (() -> Unit)? = null

    val recordingTime: Long
        get() = activeRecordingTime.elapsedSeconds

    protected open fun start() {
        activeRecordingTime.start()
    }

    protected open fun pause() {
        isPaused = true
        activeRecordingTime.pause()
    }

    protected open fun resume() {
        activeRecordingTime.start()
    }

    protected open suspend fun stop() {
        activeRecordingTime.stop()
    }

    protected abstract fun startForegroundService()

    fun startRecording() {
        DiagnosticLog.log("recording_start", "service=${javaClass.simpleName}")
        errorReportedForSession.set(false)
        isPaused = false
        activeRecordingTime.reset()
        recordingStart = LocalDateTime.now()

        RecorderNotificationHelper.cancelInterruptedNotification(this)
        RecorderSessionState.markActive(this, javaClass.simpleName)

        startForegroundService()
        changeState(RecorderState.RECORDING)

        try {
            start()
        } catch (error: RuntimeException) {
            error.printStackTrace()

            if (error is AvoidErrorDialogError) {
                DiagnosticLog.logException("recording_start_failure", error)
                RecorderSessionState.markInactive(this)
            } else {
                reportRecordingError("recording_start_failure", error)
            }
        }
    }

    suspend fun stopRecording() {
        RecorderSessionState.markInactive(this)
        isPaused = false
        activeRecordingTime.stop()
        changeState(RecorderState.STOPPED)
        stop()
    }

    fun pauseRecording() {
        changeState(RecorderState.PAUSED)
    }

    fun resumeRecording() {
        changeState(RecorderState.RECORDING)
    }

    fun destroy() {
        RecorderSessionState.markInactive(this)
        stopForegroundAndSelf()
    }

    protected open fun stopForegroundAndSelf(startId: Int? = null) {
        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    protected fun reportRecordingError(event: String, error: Throwable? = null) {
        if (!errorReportedForSession.compareAndSet(false, true)) {
            return
        }

        if (error != null) {
            DiagnosticLog.logException(event, error)
        } else {
            DiagnosticLog.log(event, "service=${javaClass.simpleName};state=$state")
        }

        lifecycleScope.launch(Dispatchers.Main.immediate) {
            RecorderSessionState.markInactive(this@RecorderService)
            val uiErrorHandler = onError
            if (uiErrorHandler != null) {
                uiErrorHandler.invoke()
            } else {
                runCatching {
                    stopRecording()
                }
                destroy()
                RecorderNotificationHelper.notifyRecordingInterrupted(
                    this@RecorderService,
                    RecorderSessionState.InterruptionReason.RECORDING_ERROR,
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.log("service_create", "service=${javaClass.simpleName}")
    }

    override fun onDestroy() {
        DiagnosticLog.log("service_destroy", "service=${javaClass.simpleName};state=$state")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val action = when (intent?.action) {
            "init" -> "init"
            "changeState" -> "changeState"
            null -> "null"
            else -> "other"
        }
        DiagnosticLog.log("service_start_command", "service=${javaClass.simpleName};action=$action")

        if (intent == null) {
            // Android recreated this START_STICKY service after unexpected process death.
            // Android 14+ does not permit silently restarting a microphone FGS from the
            // background, so clear the zombie foreground state and alert the user instead.
            val wasActive = RecorderSessionState.consumeActiveRecording(this)
            DiagnosticLog.log(
                "service_restarted_unexpectedly",
                "service=${javaClass.simpleName};state=$state;wasActive=$wasActive",
            )
            if (wasActive) {
                RecorderNotificationHelper.notifyRecordingInterrupted(
                    this,
                    RecorderSessionState.InterruptionReason.SYSTEM_RESTART,
                )
            }
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        return when (intent.action) {
            "init" -> {
                notificationDetails = intent.getStringExtra("notificationDetails")?.let {
                    Json.decodeFromString(
                        RecorderNotificationHelper.NotificationDetails.serializer(),
                        it
                    )
                }
                // Satisfy Android's startForegroundService() contract immediately, even if
                // binding is delayed or an existing STOPPED service instance is reused.
                startForegroundService()
                START_STICKY
            }

            "changeState" -> {
                if (state != RecorderState.RECORDING && state != RecorderState.PAUSED) {
                    DiagnosticLog.log(
                        "stale_change_state_ignored",
                        "service=${javaClass.simpleName};state=$state",
                    )
                    stopForegroundAndSelf(startId)
                    START_NOT_STICKY
                } else {
                    val newState = intent.getStringExtra("newState")?.let {
                        RecorderState.valueOf(it)
                    } ?: RecorderState.STOPPED
                    changeState(newState)
                    START_STICKY
                }
            }

            else -> START_NOT_STICKY
        }
    }

    inner class RecorderBinder : Binder() {
        fun getService(): RecorderService = this@RecorderService
    }

    // Used to change the state of the service
    // will internally call start() / pause() / resume() / stop()
    // Immediately after creating the service make sure to call `changeState(RecorderState.RECORDING)`
    @SuppressLint("MissingPermission")
    fun changeState(newState: RecorderState) {
        if (state == newState) {
            return
        }

        val previousState = state
        state = newState
        DiagnosticLog.log("recording_transition", "service=${javaClass.simpleName};from=$previousState;to=$newState")
        when (newState) {
            RecorderState.RECORDING -> {
                if (isPaused) {
                    resume()
                    isPaused = false
                }
                // `start` is handled by `startRecording`
            }

            RecorderState.PAUSED -> pause()

            else -> {}
        }

        // Update notification
        if (
            arrayOf(
                RecorderState.RECORDING,
                RecorderState.PAUSED
            ).contains(newState) &&
            PermissionHelper.hasGranted(this, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            val notification = buildNotification()
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
                notification
            )
        }

        onStateChange?.invoke(newState)
    }

    protected fun getNotificationHelper(): RecorderNotificationHelper {
        return RecorderNotificationHelper(this, notificationDetails)
    }

    private fun buildNotification(): Notification {
        val notificationHelper = getNotificationHelper()

        return when (state) {
            RecorderState.RECORDING -> {
                notificationHelper.buildRecordingNotification(recordingTime)
            }

            RecorderState.PAUSED -> {
                notificationHelper.buildPausedNotification(recordingStart)
            }

            else -> {
                throw IllegalStateException("Notification can't be built in state $state")
            }
        }
    }


    // Throw this error if you show a dialog yourself.
    // This will prevent the service from showing their generic error dialog.
    class AvoidErrorDialogError : RuntimeException()
}
