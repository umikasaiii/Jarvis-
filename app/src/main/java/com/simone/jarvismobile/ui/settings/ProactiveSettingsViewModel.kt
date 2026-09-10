package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.proactive.MorningTriggerScheduler
import com.simone.jarvismobile.proactive.ProactiveScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the «Proattività» settings section. Self-contained so [SettingsViewModel] stays lean. */
@HiltViewModel
class ProactiveSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: ProactiveScheduler,
    // § MICRO-PATCH 14.2.1 — the canonical owner of the CONFIGURED_TIME exact
    // alarm (§ MorningTriggerScheduler's own doc comment); reused here to
    // re-arm it after the user changes the briefing time, never a second
    // scheduler.
    private val morningTriggerScheduler: MorningTriggerScheduler,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.proactiveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val maxPerDay: StateFlow<Int> = settings.proactiveMaxPerDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_PROACTIVE_MAX)
    val quietStart: StateFlow<Int> = settings.proactiveQuietStart
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 22)
    val quietEnd: StateFlow<Int> = settings.proactiveQuietEnd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 8)
    val disabledKinds: StateFlow<Set<String>> = settings.proactiveDisabledKinds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val mutedKinds: StateFlow<Set<String>> = settings.proactiveMutedKinds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // § MICRO-PATCH 14.2.1 — the real, already-persisted morning briefing time
    // (SettingsRepository.MORNING_BRIEFING_HOUR/MINUTE, default 08:00, § FASE
    // 2A.8's mandatory CONFIGURED_TIME fallback). Never a UI-local default.
    val morningBriefingHour: StateFlow<Int> = settings.morningBriefingHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 8)
    val morningBriefingMinute: StateFlow<Int> = settings.morningBriefingMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The categories shown as switches, with their Italian labels. */
    val categories: List<Pair<ProactiveKind, String>> = listOf(
        ProactiveKind.MORNING_DIGEST to "Riepilogo mattutino",
        ProactiveKind.BATTERY_BEFORE_ALARM to "Batteria prima della sveglia",
        ProactiveKind.EVENING_DIGEST to "Riepilogo serale",
        // § JARVIS Implementation Master Plan PASSAGGIO 14.2.
        ProactiveKind.WEATHER_ALERT to "Avviso pioggia/temporali (la sera prima)",
    )

    fun setEnabled(value: Boolean) = viewModelScope.launch {
        settings.setProactiveEnabled(value)
        scheduler.apply(value)
    }

    fun setMaxPerDay(value: Int) = viewModelScope.launch { settings.setProactiveMaxPerDay(value) }

    fun setQuietHours(startHour: Int, endHour: Int) = viewModelScope.launch {
        settings.setProactiveQuietHours(startHour, endHour)
    }

    fun setCategoryEnabled(kind: ProactiveKind, enabled: Boolean) = viewModelScope.launch {
        // A category switch OFF disables it; ON also lifts any per-message mute.
        settings.setProactiveKindDisabled(kind.name, !enabled)
        if (enabled) settings.setProactiveKindMuted(kind.name, false)
    }

    fun unmute(kind: ProactiveKind) = viewModelScope.launch {
        settings.setProactiveKindMuted(kind.name, false)
    }

    /**
     * § MICRO-PATCH 14.2.1 — delegates to the existing [SettingsRepository]
     * persistence, then re-arms [MorningTriggerScheduler]'s CONFIGURED_TIME
     * exact alarm (§4: the schedule DOES materialize into a real
     * [android.app.AlarmManager] booking, so a save must reschedule it —
     * otherwise the alarm already armed at the OLD time would still fire
     * today). [MorningTriggerScheduler.scheduleConfiguredTimeTrigger] always
     * re-books under the SAME key (`FLAG_UPDATE_CURRENT`), so this never
     * creates a second alarm/worker — it replaces the one that already
     * exists. Changing the time never touches
     * [com.simone.jarvismobile.core.proactive.ProactiveOccurrenceKey] (§6): the
     * occurrence identity stays `MORNING_DIGEST:<date>`, date-only, so an
     * already-DELIVERED occurrence for today is never redelivered just
     * because the configured hour changed mid-day.
     */
    fun setMorningBriefingTime(hour: Int, minute: Int) = viewModelScope.launch {
        settings.setMorningBriefingTime(hour, minute)
        morningTriggerScheduler.scheduleConfiguredTimeTrigger()
    }
}
