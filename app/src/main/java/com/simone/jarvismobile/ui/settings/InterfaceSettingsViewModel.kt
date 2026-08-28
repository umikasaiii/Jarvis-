package com.simone.jarvismobile.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.memory.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs Impostazioni → Interfaccia → Widget e notifiche. */
@HiltViewModel
class InterfaceSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
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

    // Document import (Memory & Knowledge).
    val docSaveToVaultDefault = flow(settings.docSaveToVaultDefault, false)
    val docAutoIndex = flow(settings.docAutoIndex, true)
    val docDedup = flow(settings.docDedup, true)
    val docOcrImages = flow(settings.docOcrImages, false)

    // Obsidian vault connection (§ dopo aver tolto Memoria dal vault, questo
    // resta l'unico punto d'ingresso app-wide per Documenti/Agenda/Automazioni,
    // che continuano a mirrorare qui in modo facoltativo — spostato da Memoria,
    // che ora è archivio locale puro).
    private val _vaultConfigured = MutableStateFlow(false)
    val vaultConfigured: StateFlow<Boolean> = _vaultConfigured.asStateFlow()
    private val _vaultName = MutableStateFlow<String?>(null)
    val vaultName: StateFlow<String?> = _vaultName.asStateFlow()

    init {
        viewModelScope.launch { refreshVaultStatus() }
    }

    private suspend fun refreshVaultStatus() {
        _vaultConfigured.value = vault.isConfigured()
        _vaultName.value = vault.vaultName()
    }

    fun pickVault(uri: Uri) = viewModelScope.launch {
        vault.setVault(uri)
        refreshVaultStatus()
    }

    fun disconnectVault() = viewModelScope.launch {
        vault.clearVault()
        refreshVaultStatus()
    }

    fun setWidgetShowStatus(v: Boolean) = viewModelScope.launch { settings.setWidgetShowStatus(v) }
    fun setWidgetStyle(v: String) = viewModelScope.launch { settings.setWidgetStyle(v) }
    fun setWidgetTransparency(v: Float) = viewModelScope.launch { settings.setWidgetTransparency(v) }
    fun setResponseNotifications(v: Boolean) = viewModelScope.launch { settings.setResponseNotifications(v) }
    fun setReminderNotifications(v: Boolean) = viewModelScope.launch { settings.setReminderNotifications(v) }
    fun setNotifSound(v: Boolean) = viewModelScope.launch { settings.setNotifSound(v) }
    fun setNotifVibration(v: Boolean) = viewModelScope.launch { settings.setNotifVibration(v) }

    fun setDocSaveToVaultDefault(v: Boolean) = viewModelScope.launch { settings.setDocSaveToVaultDefault(v) }
    fun setDocAutoIndex(v: Boolean) = viewModelScope.launch { settings.setDocAutoIndex(v) }
    fun setDocDedup(v: Boolean) = viewModelScope.launch { settings.setDocDedup(v) }
    fun setDocOcrImages(v: Boolean) = viewModelScope.launch { settings.setDocOcrImages(v) }
}
