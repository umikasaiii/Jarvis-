package com.simone.jarvismobile.diagnostics

import android.content.Context
import android.content.SharedPreferences
import com.simone.jarvismobile.BuildConfig

/**
 * § JARVIS Implementation Master Plan — MICRO-PATCH E.1 §7/§8. Minimal,
 * bounded, LOCAL-ONLY startup crash/checkpoint evidence — deliberately
 * built on plain [SharedPreferences], never Room/DataStore: it must keep
 * working even when the thing it is diagnosing is a Room database itself
 * failing to open (the proven root cause this same patch fixes) — a
 * recorder that shares a dependency with the subsystem it watches would go
 * silent exactly when it is needed most.
 *
 * DIAGNOSTICS ONLY. Never consulted by any runtime decision path — see
 * every call site: [checkpoint] is fire-and-forget, [lastStartupFailure] is
 * read only by the Diagnostics screen.
 *
 * Never records agenda/health/weather/message content, coordinates or
 * secrets — only: build id, a startup checkpoint name, an exception class
 * name, a message bounded to [MAX_DETAIL_CHARS], up to [MAX_STACK_FRAMES]
 * top stack frames (class/method/line — Kotlin/Java frames only, never
 * argument values), and a timestamp.
 */
object StartupDiagnostics {

    enum class Checkpoint {
        APPLICATION_CREATED,
        HILT_READY,
        RESTORE_BARRIER_STARTED,
        RESTORE_BARRIER_OK,
        RESTORE_BARRIER_FAILED,
        BACKUP_SYNC_STARTED,
        BACKUP_SYNC_OK,
        BACKUP_SYNC_FAILED,
        AUTOMATION_SYNC_STARTED,
        AUTOMATION_SYNC_OK,
        AUTOMATION_SYNC_FAILED,
        PROACTIVE_SYNC_STARTED,
        PROACTIVE_SYNC_OK,
        PROACTIVE_SYNC_FAILED,
        RULE_SYNC_STARTED,
        RULE_SYNC_OK,
        RULE_SYNC_FAILED,
        PLACE_RELOAD_STARTED,
        PLACE_RELOAD_OK,
        PLACE_RELOAD_FAILED,
        WEATHER_SYNC_STARTED,
        WEATHER_SYNC_OK,
        WEATHER_SYNC_FAILED,
        MAIN_ACTIVITY_CREATED,
        ROOT_UI_READY,
    }

    private const val PREFS = "startup_diagnostics"
    private const val KEY_LAST_FAILURE = "last_failure"
    private const val KEY_CURRENT_ATTEMPT_CHECKPOINTS = "current_attempt_checkpoints"
    private const val MAX_DETAIL_CHARS = 300
    private const val MAX_STACK_FRAMES = 6
    private const val MAX_CHECKPOINTS_KEPT = 40

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Installed as the FIRST statement of `JarvisApplication.onCreate()` —
     * as early as this recorder itself can safely run. Honesty per §7: a
     * failure inside Hilt's OWN field-injection step (which happens as part
     * of `super.onCreate()`/earlier, before user code can install anything)
     * is NOT observable here — that gap is a hard platform limit, not an
     * oversight, and is stated as such wherever this class's coverage is
     * documented.
     */
    fun install(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // If the previous attempt left checkpoints behind but never reached
        // ROOT_UI_READY and never hit the uncaught-exception handler below
        // (e.g. a process kill with no Java exception — OOM, an ANR-style
        // platform kill, a foreground-service violation that terminates the
        // process before any handler runs), record that honestly as an
        // INCOMPLETE startup — distinct from a genuine caught EXCEPTION —
        // rather than staying silent about it.
        val priorCheckpoints = p.getString(KEY_CURRENT_ATTEMPT_CHECKPOINTS, "") ?: ""
        if (priorCheckpoints.isNotBlank() && !priorCheckpoints.contains(Checkpoint.ROOT_UI_READY.name)) {
            p.edit()
                .putString(
                    KEY_LAST_FAILURE,
                    "kind=INCOMPLETE_STARTUP|checkpoints=$priorCheckpoints|note=process ended before ROOT_UI_READY, no Java exception observed",
                )
                .apply()
        }
        p.edit().putString(KEY_CURRENT_ATTEMPT_CHECKPOINTS, "").apply()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(throwable) }
            // Never permanently replace Android's own uncaught-exception
            // handling — always delegate to whatever was installed before
            // this (Android's default handler, or another tool's), so the
            // normal crash dialog / process teardown behavior is unchanged.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** Fire-and-forget; never throws, never blocks the real startup path it observes. */
    fun checkpoint(point: Checkpoint) {
        val p = prefs ?: return
        runCatching {
            val existing = (p.getString(KEY_CURRENT_ATTEMPT_CHECKPOINTS, "") ?: "")
                .split(",")
                .filter { it.isNotBlank() }
            val updated = (existing + point.name).takeLast(MAX_CHECKPOINTS_KEPT).joinToString(",")
            p.edit().putString(KEY_CURRENT_ATTEMPT_CHECKPOINTS, updated).apply()
        }
    }

    private fun recordCrash(t: Throwable) {
        val p = prefs ?: return
        val checkpoints = p.getString(KEY_CURRENT_ATTEMPT_CHECKPOINTS, "") ?: ""
        val frames = t.stackTrace.take(MAX_STACK_FRAMES).joinToString(" | ") { it.toString() }
        val message = (t.message ?: "").take(MAX_DETAIL_CHARS)
        val record = buildString {
            append("kind=EXCEPTION")
            append("|buildId=").append(runCatching { BuildConfig.BUILD_ID }.getOrDefault("unknown"))
            append("|exceptionClass=").append(t.javaClass.name)
            append("|message=").append(message)
            append("|frames=").append(frames)
            append("|checkpointsBeforeCrash=").append(checkpoints)
            append("|atMs=").append(System.currentTimeMillis())
        }
        p.edit().putString(KEY_LAST_FAILURE, record).apply()
    }

    /**
     * Read-only, Diagnostics-screen-only. `null` means no failure has been
     * recorded since the last time [clearLastStartupFailure] was called (or
     * ever, on a fresh install).
     */
    fun lastStartupFailure(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_FAILURE, null)

    fun clearLastStartupFailure(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_FAILURE).apply()
    }
}
