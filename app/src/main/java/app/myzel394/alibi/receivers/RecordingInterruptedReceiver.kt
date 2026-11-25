package app.myzel394.alibi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.myzel394.alibi.DiagnosticLog
import app.myzel394.alibi.services.RecorderNotificationHelper
import app.myzel394.alibi.services.RecorderSessionState

class RecordingInterruptedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reason = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> RecorderSessionState.InterruptionReason.DEVICE_REBOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecorderSessionState.InterruptionReason.APP_UPDATED
            else -> return
        }

        if (!RecorderSessionState.consumeActiveRecording(context)) {
            return
        }

        DiagnosticLog.log("recording_interrupted_broadcast", "reason=${reason.name}")
        RecorderNotificationHelper.notifyRecordingInterrupted(context, reason)
    }
}
