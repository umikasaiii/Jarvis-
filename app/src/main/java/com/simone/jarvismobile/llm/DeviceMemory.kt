package com.simone.jarvismobile.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * What Android says about memory, labelled for what it is: [availBytes] is SYSTEM-WIDE available memory (Android can reclaim
 * caches and kill background apps, so it is not what this process can allocate), [pssBytes] is this process's own proportional
 * set size. Nothing here is a hardware limit.
 */
data class MemorySnapshot(val totalBytes: Long, val availBytes: Long, val lowMemory: Boolean, val pssBytes: Long)

object DeviceMemory {
    fun read(context: Context): MemorySnapshot {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
        val pss = runCatching { Debug.getPss() * 1024L }.getOrDefault(-1L)
        return MemorySnapshot(mi.totalMem.takeIf { it > 0 } ?: -1L, mi.availMem.takeIf { it > 0 } ?: -1L, mi.lowMemory, pss)
    }
}
