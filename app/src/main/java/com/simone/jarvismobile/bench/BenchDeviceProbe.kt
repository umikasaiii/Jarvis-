package com.simone.jarvismobile.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.simone.jarvismobile.core.bench.PerfFacts
import com.simone.jarvismobile.llm.DeviceMemory

/**
 * M4 — reads what Android REALLY reports about this device right now. Every field is null when the platform does not give it (never 0, never a guess).
 * Temperature/battery come from the sticky battery broadcast (battery temperature, not the SoC's); the thermal status needs API 29+. It reads; it changes nothing.
 */
data class DeviceSnapshot(
    val batteryPct: Int?, val batteryTemperatureC: Double?, val thermalStatus: Int?, val pssMb: Long?, val availMb: Long?, val totalMb: Long?, val charging: Boolean?,
)

object BenchDeviceProbe {
    fun read(context: Context): DeviceSnapshot {
        val battery: Intent? = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temp = if (tenths != Int.MIN_VALUE && tenths > -500) tenths / 10.0 else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = if (status < 0) null else (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull() else null
        val mem = DeviceMemory.read(context)
        fun mb(b: Long) = if (b >= 0) b / (1024 * 1024) else null
        return DeviceSnapshot(pct, temp, thermal, mb(mem.pssBytes), mb(mem.availBytes), mb(mem.totalBytes), charging)
    }

    /** Coarse hardware key recorded with a device run (model, Android SDK, RAM): what a qualification is valid for. */
    fun deviceKey(context: Context): String = "${Build.MANUFACTURER} ${Build.MODEL}|sdk${Build.VERSION.SDK_INT}|ram${read(context).totalMb ?: -1}MB"

    /** Folds two snapshots (start/end of a run) into the report's facts; everything else stays null for the caller to fill from the model profile/trace. */
    fun perf(start: DeviceSnapshot, end: DeviceSnapshot, base: PerfFacts = PerfFacts()): PerfFacts = base.copy(
        pssBeforeMb = base.pssBeforeMb ?: start.pssMb, pssAfterMb = base.pssAfterMb ?: end.pssMb,
        batteryStartPct = start.batteryPct, batteryEndPct = end.batteryPct, temperatureStartC = start.batteryTemperatureC, temperatureEndC = end.batteryTemperatureC,
    )
}
