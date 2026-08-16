package com.simone.jarvismobile.ui.backup

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.backup.BackupKeyManager
import com.simone.jarvismobile.backup.BackupRepository
import com.simone.jarvismobile.backup.BackupScheduler
import com.simone.jarvismobile.backup.BackupState
import com.simone.jarvismobile.backup.CloudBackupProvider
import com.simone.jarvismobile.backup.CloudSyncManager
import com.simone.jarvismobile.backup.ExternalBackupStore
import com.simone.jarvismobile.backup.NoCloudProvider
import com.simone.jarvismobile.backup.drive.DriveConnectResult
import com.simone.jarvismobile.backup.drive.DriveCredentialStore
import com.simone.jarvismobile.backup.drive.GoogleAuthManager
import com.simone.jarvismobile.automation.rule.AutomationPlaceEntity
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.core.backup.BackupManifest
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Saved place ids the backup requires; empty means unrestricted. */
    val placeIds: Set<String> = emptySet(),
    val driveClientId: String = "",
    val driveClientSecret: String = "",
    val driveConnected: Boolean = false,
    val driveAccountEmail: String? = null,
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
    private val places: PlaceRepository,
    private val driveAuth: GoogleAuthManager,
    private val driveCredentials: DriveCredentialStore,
    private val keys: BackupKeyManager,
) : ViewModel() {

    val state: StateFlow<BackupState> = repository.state

    /** Saved places (§ Luoghi, phase 6) offered as the backup's location gate. */
    val savedPlaces: StateFlow<List<AutomationPlaceEntity>> =
        places.observePlaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        // Picks up the outcome of a "Collega Google Drive" round trip through the
        // browser, whenever OAuthRedirectActivity's handleRedirect() publishes one.
        viewModelScope.launch {
            driveAuth.connectResult.filterNotNull().collect { result ->
                _message.value = when (result) {
                    is DriveConnectResult.Connected ->
                        "Google Drive collegato" + (result.email?.let { ": $it" } ?: ".")
                    is DriveConnectResult.Failed -> "Collegamento a Google Drive non riuscito: ${result.reason}"
                }
                load()
                driveAuth.consumeConnectResult()
            }
        }
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
            placeIds = settings.backupPlaceIds.first(),
            driveClientId = driveCredentials.clientId,
            driveClientSecret = driveCredentials.clientSecret,
            driveConnected = driveAuth.isConnected(),
            driveAccountEmail = driveAuth.connectedEmail(),
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

    /** Adds/removes [placeId] from the required-places set; empty = unrestricted. */
    fun togglePlace(placeId: String) = viewModelScope.launch {
        val current = _ui.value.placeIds
        val updated = if (placeId in current) current - placeId else current + placeId
        reloadAfter { settings.setBackupPlaceIds(updated) }
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

    /** Saves the pasted-in OAuth client id/secret from the user's own Google Cloud project. */
    fun saveDriveClientConfig(clientId: String, clientSecret: String) = viewModelScope.launch {
        driveCredentials.clientId = clientId
        driveCredentials.clientSecret = clientSecret
        load()
        _message.value = "Credenziali Google Drive salvate."
    }

    /** The intent to open Google's consent page, or null if no client id is set yet. */
    fun driveConnectIntent(): Intent? = driveAuth.beginConnectIntent()

    fun disconnectDrive() = viewModelScope.launch {
        driveAuth.disconnect()
        load()
        _message.value = "Google Drive scollegato."
    }

    private val _recoveryKey = MutableStateFlow<String?>(null)
    /** Null until [revealRecoveryKey] is called — never shown by default. */
    val recoveryKey: StateFlow<String?> = _recoveryKey.asStateFlow()

    /** Reads (generating on first use) the current backup content key, for the user to save. */
    fun revealRecoveryKey() = viewModelScope.launch {
        _recoveryKey.value = withContext(Dispatchers.IO) { keys.exportRecoveryKey() }
    }

    fun hideRecoveryKey() { _recoveryKey.value = null }

    /**
     * Adopts a recovery key exported from another device (or from this one,
     * earlier) — needed before restoring a cloud backup encrypted with a
     * different content key than the one this device currently holds.
     */
    fun importRecoveryKey(text: String) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { keys.importRecoveryKey(text) }
        _message.value = if (ok) {
            "Chiave di recupero importata. Ora puoi ripristinare i backup cifrati con questa chiave."
        } else {
            "Chiave di recupero non valida: controlla di averla copiata per intero."
        }
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
