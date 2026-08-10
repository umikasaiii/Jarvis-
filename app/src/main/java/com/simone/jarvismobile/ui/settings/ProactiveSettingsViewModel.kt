package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.proactive.ProactiveKind
import com.simone.jarvismobile.data.SettingsRepository
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

    /** The categories shown as switches, with their Italian labels. */
    val categories: List<Pair<ProactiveKind, String>> = listOf(
        ProactiveKind.MORNING_DIGEST to "Riepilogo mattutino",
        ProactiveKind.BATTERY_BEFORE_ALARM to "Batteria prima della sveglia",
        ProactiveKind.EVENING_DIGEST to "Riepilogo serale",
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
}
