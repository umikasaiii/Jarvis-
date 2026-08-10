package com.simone.jarvismobile.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.backup.BackupRepository
import com.simone.jarvismobile.backup.BackupScheduler
import com.simone.jarvismobile.backup.BackupState
import com.simone.jarvismobile.backup.CloudBackupProvider
import com.simone.jarvismobile.backup.CloudSyncManager
import com.simone.jarvismobile.backup.ExternalBackupStore
import com.simone.jarvismobile.backup.NoCloudProvider
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One immutable snapshot of the "Backup e sincronizzazione" screen. */
data class BackupUi(
    val enabled: Boolean = false,
    val hour: Int = 23,
    val minute: Int = 30,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val minBattery: Int = 20,
    val retentionDaily: Int = 7,
    val retentionWeekly: Int = 4,
    val retentionMonthly: Int = 6,
    val cloudEnabled: Boolean = false,
    val provider: String = NoCloudProvider.ID,
    /** Name of the chosen destination folder, or null for internal-only. */
    val destinationName: String? = null,
    val loaded: Boolean = false,
)

/** A backup as shown in the "Gestisci backup" list. */
data class BackupListItem(
    val id: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val entryCount: Int,
    val status: String,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val repository: BackupRepository,
    private val scheduler: BackupScheduler,
    private val cloud: CloudSyncManager,
    private val settings: SettingsRepository,
    private val external: ExternalBackupStore,
) : ViewModel() {

    val state: StateFlow<BackupState> = repository.state

    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupListItem>>(emptyList())
    val backups: StateFlow<List<BackupListItem>> = _backups.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val providers: List<CloudBackupProvider> get() = cloud.availableProviders

    init {
        viewModelScope.launch { load() }
        refreshBackups()
    }

    private suspend fun load() {
        _ui.value = BackupUi(
            enabled = settings.backupEnabled.first(),
            hour = settings.backupHour.first(),
            minute = settings.backupMinute.first(),
            wifiOnly = settings.backupWifiOnly.first(),
            chargingOnly = settings.backupChargingOnly.first(),
            minBattery = settings.backupMinBattery.first(),
            retentionDaily = settings.backupRetentionDaily.first(),
            retentionWeekly = settings.backupRetentionWeekly.first(),
            retentionMonthly = settings.backupRetentionMonthly.first(),
            cloudEnabled = settings.backupCloudEnabled.first(),
            provider = settings.backupCloudProvider.first().ifBlank { NoCloudProvider.ID },
            destinationName = external.folderName(),
            loaded = true,
        )
    }

    /** The user picked a destination folder in the system file picker. */
    fun setDestination(uri: Uri) = viewModelScope.launch {
        external.setFolder(uri)
        load()
        _message.value = "Destinazione impostata. I prossimi backup verranno salvati lì."
        refreshBackups()
    }

    fun clearDestination() = viewModelScope.launch {
        external.clearFolder()
        load()
        refreshBackups()
    }

    private suspend fun <T> reloadAfter(block: suspend () -> T): T {
        val r = block()
        load()
        scheduler.sync()
        return r
    }

    fun setEnabled(value: Boolean) = viewModelScope.launch {
        reloadAfter { settings.setBackupEnabled(value) }
    }

    fun setTime(hour: Int, minute: Int) = viewModelScope.launch {
        reloadAfter { settings.setBackupTime(hour, minute) }
    }

    fun setWifiOnly(value: Boolean) = viewModelScope.launch {
        reloadAfter { settings.setBackupWifiOnly(value) }
    }

    fun setChargingOnly(value: Boolean) = viewModelScope.launch {
        reloadAfter { settings.setBackupChargingOnly(value) }
    }

    fun setMinBattery(value: Int) = viewModelScope.launch {
        reloadAfter { settings.setBackupMinBattery(value) }
    }

    fun setRetention(daily: Int, weekly: Int, monthly: Int) = viewModelScope.launch {
        reloadAfter { settings.setBackupRetention(daily, weekly, monthly) }
    }

    fun setCloudEnabled(value: Boolean) = viewModelScope.launch {
        settings.setBackupCloudEnabled(value)
        load()
    }

    fun setProvider(id: String) = viewModelScope.launch {
        settings.setBackupCloudProvider(id)
        load()
    }

    fun runNow() = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        val manifest = runCatching { repository.runBackup() }.getOrNull()
        if (manifest != null) {
            runCatching { cloud.enqueue(manifest.id); cloud.processQueue() }
            _message.value = "Backup completato."
        } else {
            _message.value = "Backup non riuscito. Riprova."
        }
        refreshBackups()
        _busy.value = false
    }

    fun restore(id: String) = viewModelScope.launch {
        _busy.value = true
        _message.value = null
        val ok = runCatching { repository.restore(id) }.getOrDefault(false)
        _message.value = if (ok) {
            "Ripristino completato. Riavvia l'app per applicare i dati ripristinati."
        } else {
            "Ripristino non riuscito o incompleto."
        }
        refreshBackups()
        _busy.value = false
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { repository.delete(id) }
        refreshBackups()
    }

    fun verify(id: String) = viewModelScope.launch {
        val ok = runCatching { repository.verify(id) }.getOrDefault(false)
        _message.value = if (ok) "Integrità verificata: OK." else "Verifica fallita: file danneggiato o assente."
    }

    fun clearMessage() { _message.value = null }

    fun refreshBackups() = viewModelScope.launch {
        _backups.value = runCatching {
            repository.listBackups().map { it.toItem() }
        }.getOrDefault(emptyList())
    }

    private fun BackupManifest.toItem() = BackupListItem(
        id = id,
        createdAt = createdAt,
        sizeBytes = totalSizeBytes,
        entryCount = entries.count { it.kind.name == "FILE" },
        status = status.name,
    )
}
