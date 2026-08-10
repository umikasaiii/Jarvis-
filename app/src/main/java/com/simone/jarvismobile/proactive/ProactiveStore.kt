package com.simone.jarvismobile.proactive

import android.content.Context
import com.simone.jarvismobile.core.proactive.ProactiveState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the day's proactive delivery state (count + which suggestions were
 * already shown) so the budget and "once a day" rules survive process death.
 * Small and local — a SharedPreferences file, like the automation scheduler's.
 */
@Singleton
class ProactiveStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ProactiveState {
        val epochDay = prefs.getLong(KEY_DAY, 0L)
        val day = if (epochDay > 0L) LocalDate.ofEpochDay(epochDay) else LocalDate.now()
        return ProactiveState(
            day = day,
            deliveredCount = prefs.getInt(KEY_COUNT, 0),
            deliveredKeys = prefs.getStringSet(KEY_KEYS, emptySet())?.toSet() ?: emptySet(),
        )
    }

    fun save(state: ProactiveState) {
        prefs.edit()
            .putLong(KEY_DAY, state.day.toEpochDay())
            .putInt(KEY_COUNT, state.deliveredCount)
            .putStringSet(KEY_KEYS, state.deliveredKeys)
            .apply()
    }

    private companion object {
        const val PREFS = "proactive_state"
        const val KEY_DAY = "day"
        const val KEY_COUNT = "count"
        const val KEY_KEYS = "keys"
    }
}
