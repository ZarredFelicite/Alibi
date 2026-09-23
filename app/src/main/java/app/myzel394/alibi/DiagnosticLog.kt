package app.myzel394.alibi

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort, local-only diagnostics. Never pass user data or exception messages here. */
internal object DiagnosticLog {
    private const val MAX_FILE_BYTES = 128 * 1024L
    private const val KEEP_DAYS = 3L
    private val initialized = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var logDirectory: File? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            logDirectory = context.getExternalFilesDir("diagnostics")
            log("process_start", "sdk=${Build.VERSION.SDK_INT}")
            logHistoricalExits(context)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    logException("uncaught", error)
                } catch (_: Throwable) {
                    // Diagnostics must not interfere with normal crash handling.
                } finally {
                    previous?.uncaughtException(thread, error)
                }
            }
        } catch (_: Throwable) {
            // Logging is optional; it must never prevent app startup.
        }
    }

    fun logException(event: String, error: Throwable) {
        try {
            log(event, "class=${error.javaClass.name}")
            error.stackTrace.forEach { frame ->
                log("${event}_frame", "class=${frame.className};method=${frame.methodName};line=${frame.lineNumber}")
            }
        } catch (_: Throwable) {
            // Best-effort only.
        }
    }

    fun log(event: String, details: String = "") {
        try {
            val directory = logDirectory ?: return
            synchronized(lock) {
                if (!directory.exists() && !directory.mkdirs()) return
                val now = Instant.now()
                val current = File(directory, "alibi.log")
                val line = "${now}\t${event}\t${details}\n".toByteArray(Charsets.UTF_8)
                if (current.length() + line.size > MAX_FILE_BYTES) {
                    val previous = File(directory, "alibi.log.1")
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendBytes(line)
                directory.listFiles()?.filter {
                    it.name.startsWith("alibi.log") && now.toEpochMilli() - it.lastModified() > KEEP_DAYS * 86_400_000L
                }?.forEach { it.delete() }
            }
        } catch (_: Throwable) {
            // Best-effort only (storage may be unavailable or full).
        }
    }

    private fun logHistoricalExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                log("historical_exit", "time=${exit.timestamp};reason=${exit.reason};importance=${exit.importance}")
            }
        } catch (_: Throwable) {
            // The API may be unavailable or restricted on some devices.
        }
    }
}
