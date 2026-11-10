package com.simone.jarvismobile.llm

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.llm.InferenceEvent
import com.simone.jarvismobile.core.llm.InferenceEventLog
import com.simone.jarvismobile.core.llm.InferenceEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HOTFIX 3 — one bounded, content-free event trail for the whole local-inference stack, shared by the three
 * engines (fast / advanced / classifier) and by the app lifecycle. It exists to answer, from a device that
 * misbehaved hours ago, questions like "did the process die or was the generation just slow?", "was the
 * app in the background?", "did the phone actually reboot?" WITHOUT assuming any cause.
 *
 * PRIVACY: events carry types, numbers and sanitised identifier tags only ([InferenceEvent] has no free-text
 * field); prompts, replies, notes and any personal data cannot reach this file by construction.
 */
@Singleton
class InferenceDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "diag/llm_events.log")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-llm-diag").apply { isDaemon = true } }
    val log = InferenceEventLog(capacity = 400) { e -> persist(e) }
    private val started = AtomicInteger(0)

    /** Records an app-level event (lifecycle, screen, process) on the same trail as the engine events. */
    fun event(type: InferenceEventType, tag: String = "", n1: Long = -1, n2: Long = -1) {
        log.record(
            InferenceEvent(System.currentTimeMillis(), type, "app", -1, -1, n1, n2, InferenceEventLog.sanitiseTag(tag)),
        )
    }

    /** Call once from Application.onCreate: lifecycle + screen trail and the process-start snapshot. */
    fun install(app: Application) {
        trimFile()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started.getAndIncrement() == 0) event(InferenceEventType.APP_FOREGROUND)
            }

            override fun onActivityStopped(activity: Activity) {
                if (started.decrementAndGet() <= 0) {
                    started.set(0)
                    event(InferenceEventType.APP_BACKGROUND)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        when (i?.action) {
                            Intent.ACTION_SCREEN_OFF -> event(InferenceEventType.SCREEN_OFF)
                            Intent.ACTION_SCREEN_ON -> event(InferenceEventType.SCREEN_ON)
                            Intent.ACTION_USER_PRESENT -> event(InferenceEventType.USER_PRESENT)
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        recordProcessStart()
    }

    /**
     * Facts that tell a REAL reboot apart from "the lock screen simply appeared": the system boot counter and
     * uptime, plus how the previous app process ended (Android's own exit reasons: crash, native crash, ANR,
     * low memory, signal, user…). No cause is assumed — the report just lists them.
     */
    private fun recordProcessStart() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val boot = Settings.Global.getInt(context.contentResolver, "boot_count", -1)
            val prevBoot = prefs.getInt(KEY_BOOT, -2)
            val verdict = when {
                prevBoot == -2 -> "first_run"
                boot == -1 -> "boot_unknown"
                boot != prevBoot -> "device_rebooted_since_last_run"
                else -> "same_boot"
            }
            prefs.edit().putInt(KEY_BOOT, boot).apply()
            event(InferenceEventType.PROCESS_START, verdict, n1 = boot.toLong(), n2 = SystemClock.elapsedRealtime() / 1000)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val battery = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toLong() ?: -1L
            event(InferenceEventType.MEMORY, if (mi.lowMemory) "low_memory" else "ok", n1 = mi.availMem / MB, n2 = battery)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && am != null) {
                val lastSeen = prefs.getLong(KEY_EXIT_TS, 0L)
                val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                    .filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
                exits.forEach {
                    event(
                        InferenceEventType.PREVIOUS_EXIT,
                        exitName(it.reason),
                        n1 = it.importance.toLong(),
                        n2 = (System.currentTimeMillis() - it.timestamp) / 1000,
                    )
                }
                exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_EXIT_TS, it).apply() }
            }
        }.onFailure { Log.w(TAG, "process_start_snapshot_failed ${it.javaClass.simpleName}") }
    }

    /** Plain text for the Diagnostics screen / sharing. Content-free. */
    fun report(limit: Int = 120): String = buildString {
        append("JARVIS LLM diagnostics (nessun contenuto di conversazione)\n")
        append("uptime=").append(SystemClock.elapsedRealtime() / 1000).append("s sdk=").append(Build.VERSION.SDK_INT).append('\n')
        append(log.render(limit))
    }

    private fun persist(e: InferenceEvent) {
        val line = e.render() + " up=" + (SystemClock.elapsedRealtime() / 1000)
        Log.i(TAG, line)
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }
    }

    private fun trimFile() {
        writer.execute {
            runCatching {
                if (file.isFile && file.length() > MAX_FILE_BYTES) {
                    val keep = file.readLines().takeLast(KEEP_LINES)
                    file.writeText(keep.joinToString("\n") + "\n")
                }
            }
        }
    }

    private fun exitName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    private companion object {
        const val TAG = "JarvisLlmDiag"
        const val PREFS = "llm_diag"
        const val KEY_BOOT = "boot_count"
        const val KEY_EXIT_TS = "exit_ts"
        const val MB = 1024L * 1024L
        const val MAX_FILE_BYTES = 256 * 1024L
        const val KEEP_LINES = 400
    }
}
