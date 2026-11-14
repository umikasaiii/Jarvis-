package com.simone.jarvismobile.ui.offlinehub

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.offlinehub.CoordinateParser
import com.simone.jarvismobile.core.offlinehub.ExternalApp
import com.simone.jarvismobile.core.offlinehub.LaunchMessages
import com.simone.jarvismobile.core.offlinehub.LaunchOutcome
import com.simone.jarvismobile.core.offlinehub.MapsProvider
import com.simone.jarvismobile.core.offlinehub.OrganicRequest
import com.simone.jarvismobile.core.offlinehub.RouteMode
import com.simone.jarvismobile.core.offlinehub.ZimEntry
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import com.simone.jarvismobile.offlinehub.ExternalAppLauncher
import com.simone.jarvismobile.offlinehub.OfflineHubService
import com.simone.jarvismobile.offlinehub.ZimLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppStatus(val installed: List<String> = emptyList(), val preferred: String = "") {
    val isInstalled get() = installed.isNotEmpty()
}

/** State and actions of the Offline Library screen and the Organic Maps card (BETA 6). */
@HiltViewModel
class OfflineHubViewModel @Inject constructor(
    private val hub: OfflineHubService,
    private val launcher: ExternalAppLauncher,
    private val zim: ZimLibrary,
    private val settings: SettingsRepository,
    private val knowledge: KnowledgeRepository,
    private val zimKnowledge: com.simone.jarvismobile.knowledge.zim.ZimKnowledge,
    private val indexManager: com.simone.jarvismobile.knowledge.index.KnowledgeIndexManager,
) : ViewModel() {

    // KNOWLEDGE ENGINE 2.0 — the persistent full-text index: opt-in per archive, bounded, resumable, removable.
    val indexProgress = indexManager.progress
    val indexSources = indexManager.sources
    private val _budgetMb = MutableStateFlow(com.simone.jarvismobile.data.SettingsRepository.DEFAULT_KNOWLEDGE_INDEX_BUDGET_MB)
    val budgetMb: StateFlow<Int> = _budgetMb.asStateFlow()
    private val _indexFileMb = MutableStateFlow(0.0)
    val indexFileMb: StateFlow<Double> = _indexFileMb.asStateFlow()

    fun refreshIndex() = viewModelScope.launch {
        _budgetMb.value = settings.knowledgeIndexBudgetMb.first()
        indexManager.refresh()
        _indexFileMb.value = indexManager.fileBytes() / 1_048_576.0
    }
    fun indexZim(e: ZimEntry) { indexManager.startZim(e.uri, e.name) }
    fun pauseIndex() { indexManager.pause() }
    fun clearIndex() = viewModelScope.launch { indexManager.clear(); _indexFileMb.value = indexManager.fileBytes() / 1_048_576.0 }
    fun setBudgetMb(mb: Int) = viewModelScope.launch { settings.setKnowledgeIndexBudgetMb(mb); _budgetMb.value = mb }

    // KNOWLEDGE ENGINE — on-device verification of DIRECT ZIM reading, per archive, and a probe search.
    private val _zimReports = MutableStateFlow<Map<String, com.simone.jarvismobile.knowledge.zim.ZimKnowledge.Report>>(emptyMap())
    val zimReports: StateFlow<Map<String, com.simone.jarvismobile.knowledge.zim.ZimKnowledge.Report>> = _zimReports.asStateFlow()
    private val _verifiedUris = MutableStateFlow<Set<String>>(emptySet())
    val verifiedUris: StateFlow<Set<String>> = _verifiedUris.asStateFlow()
    private val _probe = MutableStateFlow("")
    val probe: StateFlow<String> = _probe.asStateFlow()
    private val _verifying = MutableStateFlow<String?>(null)
    val verifying: StateFlow<String?> = _verifying.asStateFlow()

    fun verifyZim(e: ZimEntry) = viewModelScope.launch {
        _verifying.value = e.uri
        val r = zimKnowledge.verify(e.uri)
        _zimReports.value = _zimReports.value + (e.uri to r)
        _verifying.value = null
        refreshVerified()
    }

    fun probeSearch(question: String) = viewModelScope.launch {
        _probe.value = "Cerco…"
        val t0 = System.currentTimeMillis()
        val ev = runCatching { knowledge.gather(question, 3) }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        _probe.value = when (ev) {
            is com.simone.jarvismobile.core.knowledge.Evidence.Grounded ->
                (if (ev.supportEstablished) "Passaggi di supporto" else "Solo passaggi CORRELATI (non provano la risposta)") +
                    " (${ev.strength.name.lowercase()}${if (ev.conflict) ", fonti discordanti" else ""}) in $ms ms:\n" +
                    ev.passages.joinToString("\n") {
                        (if (it.related) "~ " else "• ") + it.chunk.reference + (if (it.chunk.section.isNotBlank()) ", " + it.chunk.section else "") + ": " +
                            it.supportWindow.ifBlank { it.chunk.text }.replace('\n', ' ').take(180)
                    } +
                    (if (ev.uncovered.isNotEmpty()) "\nNessuna fonte per: " + ev.uncovered.joinToString(", ") else "")
            is com.simone.jarvismobile.core.knowledge.Evidence.Insufficient ->
                "Fonti pertinenti ma INSUFFICIENTI ($ms ms): " + ev.consulted.joinToString("; ") { it.reference } +
                    ". Nessun passaggio contiene la risposta (mancano: " + ev.missing.joinToString(", ") + "): JARVIS non le citerebbe come prova."
            else -> "Nessuna evidenza nelle fonti verificate o indicizzate ($ms ms): JARVIS dichiarerebbe che il dato non è verificabile offline."
        }
    }

    private suspend fun refreshVerified() {
        _verifiedUris.value = settings.zimVerified.first().map { it.substringBeforeLast('|') }.toSet()
    }

    private val _kiwix = MutableStateFlow(AppStatus())
    val kiwix: StateFlow<AppStatus> = _kiwix.asStateFlow()
    private val _organic = MutableStateFlow(AppStatus())
    val organic: StateFlow<AppStatus> = _organic.asStateFlow()
    private val _zims = MutableStateFlow<List<ZimEntry>>(emptyList())
    val zims: StateFlow<List<ZimEntry>> = _zims.asStateFlow()
    private val _folderName = MutableStateFlow<String?>(null)
    val folderName: StateFlow<String?> = _folderName.asStateFlow()
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()
    private val _provider = MutableStateFlow(MapsProvider.DEFAULT)
    val provider: StateFlow<MapsProvider> = _provider.asStateFlow()
    private val _knowledgeName = MutableStateFlow<String?>(null)
    val knowledgeName: StateFlow<String?> = _knowledgeName.asStateFlow()
    val knowledgeStatus = knowledge.status
    val openLibraryRequest: StateFlow<Int> = hub.openLibraryRequest
    val openNavigatorRequest: StateFlow<Int> = hub.openNavigatorRequest

    fun refresh() {
        viewModelScope.launch {
            _kiwix.value = AppStatus(launcher.installedPackages(ExternalApp.KIWIX).toList().sorted(), kiwixPreferred())
            _organic.value = AppStatus(launcher.installedPackages(ExternalApp.ORGANIC_MAPS).toList().sorted(), organicPreferred())
            _provider.value = hub.mapsProvider()
            _folderName.value = zim.folderName()
            _zims.value = runCatching { zim.entries() }.getOrDefault(emptyList())
            _knowledgeName.value = runCatching { knowledge.libraryName() }.getOrNull()
            refreshVerified()
        }
    }

    private suspend fun kiwixPreferred() = runCatching { settings.hubKiwixPreferred.first() }.getOrDefault("")
    private suspend fun organicPreferred() = runCatching { settings.hubOrganicPreferred.first() }.getOrDefault("")

    private fun say(o: LaunchOutcome) { _message.value = LaunchMessages.text(o) }

    fun openKiwix() = viewModelScope.launch { say(hub.openKiwix()) }
    fun openZim(e: ZimEntry) = viewModelScope.launch { say(hub.openZim(e)) }
    fun kiwixSearch(q: String) = viewModelScope.launch { say(hub.kiwixSearch(q)) }
    fun chooseKiwix(pkg: String) = viewModelScope.launch { settings.setHubKiwixPreferred(pkg); refresh() }
    fun chooseOrganic(pkg: String) = viewModelScope.launch { settings.setHubOrganicPreferred(pkg); refresh() }

    fun addZimFolder(uri: Uri) = viewModelScope.launch { zim.addFolder(uri); refresh(); _message.value = "Cartella collegata in sola lettura. " + com.simone.jarvismobile.core.offlinehub.ZimPolicy.NO_COPY }
    fun addZimFile(uri: Uri) = viewModelScope.launch { zim.addFile(uri); refresh(); _message.value = "Archivio aggiunto. " + com.simone.jarvismobile.core.offlinehub.ZimPolicy.NO_COPY }
    fun removeZim(e: ZimEntry) = viewModelScope.launch {
        // the archive's entries in the index go with it (the file itself is never touched)
        runCatching { zimKnowledge.sourceFor(e.uri)?.first?.zim?.uuid?.let { indexManager.removeZim("zim:$it") } }
        zimKnowledge.forget(e.uri); zim.removeFile(e.uri); refresh()
    }
    fun removeZimFolder() = viewModelScope.launch { zim.removeFolder(); refresh() }

    fun setKnowledgeFolder(uri: Uri) = viewModelScope.launch { knowledge.setLibrary(uri); refresh() }

    // --- Organic Maps -----------------------------------------------------------
    fun openOrganic() = viewModelScope.launch { say(hub.organic(OrganicRequest.Open)) }
    fun organicSearch(q: String) = viewModelScope.launch { say(hub.organic(OrganicRequest.Search(q))) }
    fun organicShow(text: String) = viewModelScope.launch {
        val p = CoordinateParser.parse(text)
        if (p == null) _message.value = "Coordinate non valide: scrivi latitudine e longitudine, ad esempio 41.9028, 12.4964 (latitudine −90…90, longitudine −180…180)."
        else say(hub.organic(OrganicRequest.Show(p, null)))
    }
    fun organicRoute(dest: String, mode: RouteMode) = viewModelScope.launch { say(hub.organicRoute(dest, mode)) }
    fun setProvider(p: MapsProvider) = viewModelScope.launch { hub.setMapsProvider(p); _provider.value = p }
    fun clearMessage() { _message.value = "" }
}
