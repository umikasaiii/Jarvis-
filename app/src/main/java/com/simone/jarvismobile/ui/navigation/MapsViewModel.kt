package com.simone.jarvismobile.ui.navigation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.navigation.RegionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the offline-maps screen: list, import (.pmtiles via SAF), delete. */
@HiltViewModel
class MapsViewModel @Inject constructor(
    private val regionManager: RegionManager,
) : ViewModel() {

    val regions: StateFlow<List<RegionMetadata>> = regionManager.regions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<RegionManager.Progress?> = regionManager.progress

    init { viewModelScope.launch { regionManager.refresh() } }

    fun importPmtiles(uri: Uri) = viewModelScope.launch { regionManager.importPmtiles(uri) }
    fun delete(id: String) = viewModelScope.launch { regionManager.deleteRegion(id) }

    /** Downloads a .pmtiles from [url] (resumable) and keeps it offline. */
    fun downloadFromUrl(url: String) = regionManager.download(url.trim())
    fun cancelDownload() = regionManager.cancelDownload()

    /** Downloads a region's routing/search companion data from [url]. */
    fun downloadCompanion(regionId: String, companion: RegionManager.CompanionKind, url: String) =
        regionManager.downloadCompanion(regionId, companion, url.trim())
}
