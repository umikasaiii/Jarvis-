package com.simone.jarvismobile.ui.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.navigation.CatalogEntry
import com.simone.jarvismobile.navigation.RegionCatalogRepository
import com.simone.jarvismobile.navigation.RegionManager
import com.simone.jarvismobile.navigation.TomTomTrafficFetcher
import com.simone.jarvismobile.navigation.TrafficApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backed key-verification outcome for the "Traffico live" TomTom API key field. */
enum class TrafficKeyStatus { UNVERIFIED, CHECKING, VALID, INVALID }

/** Backs the offline-maps screen: list, import (.pmtiles via SAF), delete. */
@HiltViewModel
class MapsViewModel @Inject constructor(
    private val regionManager: RegionManager,
    private val catalog: RegionCatalogRepository,
    private val settings: SettingsRepository,
    private val trafficKeyStore: TrafficApiKeyStore,
    private val trafficFetcher: TomTomTrafficFetcher,
) : ViewModel() {

    /**
     * Opt-in, off by default (spec: PRIVACY.md sanctioned online exceptions).
     * When on, JARVIS Drive may fall back to OpenFreeMap tiles (free, no
     * account) only where no offline region is installed, and only online.
     */
    val onlineMapFallbackEnabled: StateFlow<Boolean> = settings.onlineMapFallbackEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setOnlineMapFallbackEnabled(value: Boolean) =
        viewModelScope.launch { settings.setOnlineMapFallbackEnabled(value) }

    /**
     * Opt-in, off by default. When on and a TomTom API key is saved, JARVIS
     * Drive's own internal map shows a live traffic overlay — never on the
     * Google Maps overlay mode, never without the user's own key.
     */
    val liveTrafficEnabled: StateFlow<Boolean> = settings.liveTrafficEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLiveTrafficEnabled(value: Boolean) =
        viewModelScope.launch { settings.setLiveTrafficEnabled(value) }

    private val _trafficApiKeySaved = MutableStateFlow(trafficKeyStore.apiKey != null)
    val trafficApiKeySaved: StateFlow<Boolean> = _trafficApiKeySaved.asStateFlow()

    private val _trafficKeyStatus = MutableStateFlow(TrafficKeyStatus.UNVERIFIED)
    val trafficKeyStatus: StateFlow<TrafficKeyStatus> = _trafficKeyStatus.asStateFlow()

    /** Saves the key immediately (so it survives even offline) and resets verification state. */
    fun saveTrafficApiKey(key: String) {
        trafficKeyStore.apiKey = key
        _trafficApiKeySaved.value = trafficKeyStore.apiKey != null
        _trafficKeyStatus.value = TrafficKeyStatus.UNVERIFIED
    }

    /** One live tile request against the saved key — never blocks saving, only confirms it works. */
    fun verifyTrafficApiKey() = viewModelScope.launch {
        val key = trafficKeyStore.apiKey ?: return@launch
        _trafficKeyStatus.value = TrafficKeyStatus.CHECKING
        _trafficKeyStatus.value = if (trafficFetcher.verifyApiKey(key)) TrafficKeyStatus.VALID else TrafficKeyStatus.INVALID
    }

    fun clearTrafficApiKey() {
        trafficKeyStore.apiKey = null
        _trafficApiKeySaved.value = false
        _trafficKeyStatus.value = TrafficKeyStatus.UNVERIFIED
    }

    val regions: StateFlow<List<RegionMetadata>> = regionManager.regions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<RegionManager.Progress?> = regionManager.progress

    private val _catalogEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val catalogEntries: StateFlow<List<CatalogEntry>> = _catalogEntries.asStateFlow()

    private val _manifestUrl = MutableStateFlow("")
    val manifestUrl: StateFlow<String> = _manifestUrl.asStateFlow()

    init {
        viewModelScope.launch { regionManager.refresh() }
        viewModelScope.launch { _manifestUrl.value = catalog.manifestUrl() }
    }

    fun fetchCatalog(url: String) = viewModelScope.launch {
        catalog.saveManifestUrl(url)
        _manifestUrl.value = url.trim()
        _catalogEntries.value = catalog.fetch(url.trim())
    }

    fun installFromCatalog(entry: CatalogEntry) = regionManager.installFromCatalog(entry)

    fun importPmtiles(uri: Uri) = viewModelScope.launch { regionManager.importPmtiles(uri) }
    fun delete(id: String) = viewModelScope.launch { regionManager.deleteRegion(id) }

    /** Downloads a .pmtiles from [url] (resumable) and keeps it offline. */
    fun downloadFromUrl(url: String) = regionManager.download(url.trim())
    fun cancelDownload() = regionManager.cancelDownload()

    /** Downloads a region's routing/search companion data from [url]. */
    fun downloadCompanion(regionId: String, companion: RegionManager.CompanionKind, url: String) =
        regionManager.downloadCompanion(regionId, companion, url.trim())
}
