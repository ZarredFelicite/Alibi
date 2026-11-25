package app.myzel394.alibi.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists whether a recording session is actively expected so unexpected stops
 * (process kill, device reboot, app update, or background error) can be detected
 * and reported to the user.
 */
internal object RecorderSessionState {
    private const val PREFS_NAME = "recorder_session_state"
    private const val KEY_ACTIVE = "active_recording"
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_STARTED_AT_MS = "started_at_ms"

    enum class InterruptionReason {
        SYSTEM_RESTART,
        DEVICE_REBOOT,
        APP_UPDATED,
        RECORDING_ERROR,
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markActive(context: Context, serviceName: String, startedAtMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SERVICE_NAME, serviceName)
            .putLong(KEY_STARTED_AT_MS, startedAtMs)
            .apply()
    }

    fun markInactive(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_SERVICE_NAME)
            .remove(KEY_STARTED_AT_MS)
            .apply()
    }

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    /**
     * Atomically checks whether a recording was expected to be running and clears the flag
     * so the user is alerted at most once per interruption.
     */
    fun consumeActiveRecording(context: Context): Boolean {
        val sharedPrefs = prefs(context)
        val wasActive = sharedPrefs.getBoolean(KEY_ACTIVE, false)
        if (wasActive) {
            sharedPrefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .remove(KEY_SERVICE_NAME)
                .remove(KEY_STARTED_AT_MS)
                .apply()
        }
        return wasActive
    }
}
