package app.myzel394.alibi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

object NotificationHelper {
    const val RECORDER_CHANNEL_ID = "recorder"
    const val RECORDER_CHANNEL_NOTIFICATION_ID = 1

    const val RECORDER_ALERT_CHANNEL_ID = "recorder_alerts"
    const val RECORDER_ALERT_NOTIFICATION_ID = 2

    @RequiresApi(Build.VERSION_CODES.O)
    fun createChannels(context: Context) {
        val recorderChannel = NotificationChannel(
            RECORDER_CHANNEL_ID,
            context.resources.getString(R.string.notificationChannels_recorder_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.resources.getString(R.string.notificationChannels_recorder_description)
        }

        val alertChannel = NotificationChannel(
            RECORDER_ALERT_CHANNEL_ID,
            context.resources.getString(R.string.notificationChannels_recorderAlert_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.resources.getString(R.string.notificationChannels_recorderAlert_description)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(recorderChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

}
