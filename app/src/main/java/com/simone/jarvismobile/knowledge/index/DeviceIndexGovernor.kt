package com.simone.jarvismobile.knowledge.index

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.simone.jarvismobile.core.knowledge.fts.DeviceConditions
import com.simone.jarvismobile.core.knowledge.fts.IndexDecision
import com.simone.jarvismobile.core.knowledge.fts.IndexGovernor
import com.simone.jarvismobile.core.knowledge.fts.IndexPolicy

/**
 * Reads what Android tells an app about battery, power saver, thermal state and memory — nothing that needs a permission — and
 * hands it to the pure [IndexPolicy]. Android gives no CPU-load figure to apps, so CPU use is bounded structurally instead: a few
 * workers at background priority, fewer or paused as the conditions worsen. A reading the platform does not provide is passed on
 * as "unknown" (never as a reason to stop).
 */
class DeviceIndexGovernor(private val context: Context, private val maxWorkers: Int) : IndexGovernor {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun conditions(): DeviceConditions {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val mem = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val heapHeadroomMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        return DeviceConditions(
            batteryPercent = percent,
            charging = charging,
            powerSave = power.isPowerSaveMode,
            thermalStatus = power.currentThermalStatus,
            // the app's own heap is the tighter limit for the pipeline's in-flight clusters
            lowMemory = mem.lowMemory || heapHeadroomMb < HEAP_TIGHT_MB,
            availableMemoryMb = mem.availMem / (1024 * 1024),
        )
    }

    override fun decide(): IndexDecision = IndexPolicy.decide(conditions(), maxWorkers)

    private companion object { const val HEAP_TIGHT_MB = 96L }
}
