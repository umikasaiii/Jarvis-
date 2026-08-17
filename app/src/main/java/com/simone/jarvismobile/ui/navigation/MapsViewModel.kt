package com.simone.jarvismobile.ui.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.navigation.CatalogEntry
import com.simone.jarvismobile.navigation.RegionCatalogRepository
import com.simone.jarvismobile.navigation.RegionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the offline-maps screen: list, import (.pmtiles via SAF), delete. */
@HiltViewModel
class MapsViewModel @Inject constructor(
    private val regionManager: RegionManager,
    private val catalog: RegionCatalogRepository,
    private val settings: SettingsRepository,
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
