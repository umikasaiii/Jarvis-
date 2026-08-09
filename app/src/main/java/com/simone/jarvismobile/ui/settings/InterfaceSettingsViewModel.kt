package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs Impostazioni → Interfaccia → Widget e notifiche. */
@HiltViewModel
class InterfaceSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private fun <T> flow(f: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val widgetShowStatus = flow(settings.widgetShowStatus, true)
    val widgetStyle = flow(settings.widgetStyle, "standard")
    val widgetTransparency = flow(settings.widgetTransparency, 1f)
    val responseNotifications = flow(settings.responseNotifications, true)
    val reminderNotifications = flow(settings.reminderNotifications, true)
    val notifSound = flow(settings.notifSound, true)
    val notifVibration = flow(settings.notifVibration, true)

    fun setWidgetShowStatus(v: Boolean) = viewModelScope.launch { settings.setWidgetShowStatus(v) }
    fun setWidgetStyle(v: String) = viewModelScope.launch { settings.setWidgetStyle(v) }
    fun setWidgetTransparency(v: Float) = viewModelScope.launch { settings.setWidgetTransparency(v) }
    fun setResponseNotifications(v: Boolean) = viewModelScope.launch { settings.setResponseNotifications(v) }
    fun setReminderNotifications(v: Boolean) = viewModelScope.launch { settings.setReminderNotifications(v) }
    fun setNotifSound(v: Boolean) = viewModelScope.launch { settings.setNotifSound(v) }
    fun setNotifVibration(v: Boolean) = viewModelScope.launch { settings.setNotifVibration(v) }
}
