package app.myzel394.alibi.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.myzel394.alibi.MainActivity
import app.myzel394.alibi.NotificationHelper
import app.myzel394.alibi.R
import app.myzel394.alibi.db.NotificationSettings
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.ui.utils.PermissionHelper
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date

data class RecorderNotificationHelper(
    val context: Context,
    val details: NotificationDetails? = null,
) {
    @Serializable
    data class NotificationDetails(
        val title: String,
        val description: String,
        val icon: Int,
        val isOngoing: Boolean,
    ) {
        companion object {
            fun fromNotificationSettings(
                context: Context,
                settings: NotificationSettings,
            ): NotificationDetails {
                return if (settings.preset == null) {
                    NotificationDetails(
                        settings.title,
                        settings.message,
                        settings.iconID,
                        settings.showOngoing,
                    )
                } else {
                    NotificationDetails(
                        context.getString(settings.preset.titleID),
                        context.getString(settings.preset.messageID),
                        settings.preset.iconID,
                        settings.preset.showOngoing,
                    )
                }
            }
        }
    }

    private fun getNotificationChangeStateIntent(
        newState: RecorderState,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            Intent(context, context::class.java).apply {
                action = "changeState"
                putExtra("newState", newState.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getIconID(): Int = details?.icon ?: R.drawable.launcher_monochrome_noopacity

    private fun createBaseNotification(): NotificationCompat.Builder {
        return NotificationCompat.Builder(
            context,
            NotificationHelper.RECORDER_CHANNEL_ID
        )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSmallIcon(getIconID())
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setChronometerCountDown(false)
    }

    private fun getStringForRecorder(audioRes: Int, videoRes: Int): String =
        when (context::class.java) {
            AudioRecorderService::class.java -> context.getString(audioRes)

            VideoRecorderService::class.java -> context.getString(videoRes)

            else -> ""
        }

    fun buildStartingNotification(): Notification {
        return createBaseNotification()
            .setContentTitle(
                getStringForRecorder(
                    R.string.ui_audioRecorder_state_recording_title,
                    R.string.ui_videoRecorder_state_recording_title,
                )
            )
            .setContentText(context.getString(R.string.ui_recorder_state_recording_description))
            .build()
    }

    fun buildRecordingNotification(recordingTime: Long): Notification {
        return createBaseNotification()
            .setUsesChronometer(details?.isOngoing ?: true)
            .setOngoing(details?.isOngoing ?: true)
            .setShowWhen(details?.isOngoing ?: true)
            .setWhen(
                Date.from(
                    Calendar
                        .getInstance()
                        .also { it.add(Calendar.SECOND, -recordingTime.toInt()) }
                        .toInstant()
                ).time,
            )
            .addAction(
                R.drawable.ic_pause,
                context.getString(R.string.ui_recorder_action_pause_label),
                getNotificationChangeStateIntent(RecorderState.PAUSED, 2),
            )
            .setContentTitle(
                details?.title
                    ?: getStringForRecorder(
                        R.string.ui_audioRecorder_state_recording_title,
                        R.string.ui_videoRecorder_state_recording_title,
                    )
            )
            .setContentText(
                details?.description
                    ?: context.getString(R.string.ui_recorder_state_recording_description)
            )
            .build()
    }

    fun buildPausedNotification(start: LocalDateTime): Notification {
        return createBaseNotification()
            .setContentTitle(context.getString(R.string.ui_recorder_state_paused_title))
            .setContentText(context.getString(R.string.ui_recorder_state_paused_description))
            .setOngoing(false)
            .setUsesChronometer(false)
            .setWhen(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()).time)
            .addAction(
                R.drawable.ic_play,
                context.getString(R.string.ui_recorder_action_resume_label),
                getNotificationChangeStateIntent(RecorderState.RECORDING, 3),
            )
            .build()
    }

    internal fun buildInterruptedNotification(
        reason: RecorderSessionState.InterruptionReason,
    ): Notification {
        val descriptionRes = when (reason) {
            RecorderSessionState.InterruptionReason.SYSTEM_RESTART ->
                R.string.ui_recorder_state_interrupted_description

            RecorderSessionState.InterruptionReason.DEVICE_REBOOT ->
                R.string.ui_recorder_state_interrupted_reboot_description

            RecorderSessionState.InterruptionReason.APP_UPDATED ->
                R.string.ui_recorder_state_interrupted_updated_description

            RecorderSessionState.InterruptionReason.RECORDING_ERROR ->
                R.string.ui_recorder_state_interrupted_error_description
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(
            context,
            NotificationHelper.RECORDER_ALERT_CHANNEL_ID,
        )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSmallIcon(R.drawable.launcher_monochrome_noopacity)
            .setContentTitle(context.getString(R.string.ui_recorder_state_interrupted_title))
            .setContentText(context.getString(descriptionRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(descriptionRes)))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        @SuppressLint("MissingPermission")
        internal fun notifyRecordingInterrupted(
            context: Context,
            reason: RecorderSessionState.InterruptionReason,
        ) {
            if (!PermissionHelper.hasGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationHelper.createChannels(context)
            }

            val notification = RecorderNotificationHelper(context)
                .buildInterruptedNotification(reason)
            NotificationManagerCompat.from(context).notify(
                NotificationHelper.RECORDER_ALERT_NOTIFICATION_ID,
                notification,
            )
        }

        internal fun cancelInterruptedNotification(context: Context) {
            NotificationManagerCompat.from(context)
                .cancel(NotificationHelper.RECORDER_ALERT_NOTIFICATION_ID)
        }
    }
}