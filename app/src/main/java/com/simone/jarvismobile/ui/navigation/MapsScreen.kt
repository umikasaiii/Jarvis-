package com.simone.jarvismobile.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.navigation.RegionMetadata
import com.simone.jarvismobile.navigation.RegionManager

/**
 * Impostazioni → Navigazione → Mappe offline. Lists installed regions and lets
 * the user install one by importing a `.pmtiles` archive from the device — the
 * concrete, offline, works-today way to get a first map (e.g. Lazio) onto the
 * navigator without any server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(
    onBack: () -> Unit,
    viewModel: MapsViewModel = hiltViewModel(),
) {
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importPmtiles(it) } }

    var companionTarget by remember { mutableStateOf<Pair<String, RegionManager.CompanionKind>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mappe offline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Installa una regione importando un file .pmtiles già sul dispositivo. " +
                    "Il file viene copiato, verificato (SHA-256) e i confini vengono letti " +
                    "dall'header. Tutto resta locale.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Importa mappa (.pmtiles)")
            }

            // Download-from-URL: fetch a .pmtiles once, then use it fully offline.
            var url by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL .pmtiles") },
                placeholder = { Text("https://…/lazio.pmtiles") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (url.isNotBlank()) viewModel.downloadFromUrl(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Scarica da URL") }
                val downloading = progress?.phase == RegionManager.Phase.DOWNLOADING
                if (downloading) {
                    OutlinedButton(onClick = { viewModel.cancelDownload() }) { Text("Pausa") }
                }
            }

            progress?.let { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${p.name} · ${phaseLabel(p.phase)}", style = MaterialTheme.typography.bodyMedium)
                        val active = p.phase == RegionManager.Phase.COPYING || p.phase == RegionManager.Phase.DOWNLOADING
                        if (active && p.bytesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${p.bytesDone / (1024 * 1024)} / ${p.bytesTotal / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        p.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Text("Mappe installate", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            if (regions.isEmpty()) {
                Text("Nessuna mappa installata.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(regions, key = { it.id }) { region ->
                        RegionRow(
                            region = region,
                            onDelete = { viewModel.delete(region.id) },
                            onAddCompanion = { companion -> companionTarget = region.id to companion },
                        )
                    }
                }
            }
        }

        // Download routing/search data for a region from a URL.
        companionTarget?.let { (regionId, companion) ->
            var companionUrl by rememberSaveable(regionId, companion) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { companionTarget = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (companionUrl.isNotBlank()) viewModel.downloadCompanion(regionId, companion, companionUrl)
                            companionTarget = null
                        },
                        enabled = companionUrl.isNotBlank(),
                    ) { Text("Scarica") }
                },
                dismissButton = { TextButton(onClick = { companionTarget = null }) { Text("Annulla") } },
                title = { Text("Scarica dati «${companion.label}»") },
                text = {
                    Column {
                        Text(
                            "Incolla l'URL del file ${companion.fileName} per questa regione.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = companionUrl,
                            onValueChange = { companionUrl = it },
                            singleLine = true,
                            placeholder = { Text("https://…/${companion.fileName}") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RegionRow(
    region: RegionMetadata,
    onDelete: () -> Unit,
    onAddCompanion: (RegionManager.CompanionKind) -> Unit,
) {
    val hasRouting = region.versions.routingVersion > 0
    val hasSearch = region.versions.searchIndexVersion > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(region.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(region), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Mappa ✓ · Percorsi ${if (hasRouting) "✓" else "—"} · Ricerca ${if (hasSearch) "✓" else "—"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Elimina")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasRouting) {
                    OutlinedButton(onClick = { onAddCompanion(RegionManager.CompanionKind.ROUTING) }) {
                        Text("+ Percorsi")
                    }
                }
                if (!hasSearch) {
                    OutlinedButton(onClick = { onAddCompanion(RegionManager.CompanionKind.SEARCH) }) {
                        Text("+ Ricerca")
                    }
                }
            }
        }
    }
}

private fun subtitle(r: RegionMetadata): String {
    val mb = if (r.sizeBytes > 0) "${r.sizeBytes / (1024 * 1024)} MB" else "—"
    val state = when {
        r.isUsable -> "installata"
        else -> "danneggiata — reinstalla"
    }
    return "$state · $mb"
}

private fun phaseLabel(p: RegionManager.Phase): String = when (p) {
    RegionManager.Phase.DOWNLOADING -> "download…"
    RegionManager.Phase.COPYING -> "copia…"
    RegionManager.Phase.VERIFYING -> "verifica…"
    RegionManager.Phase.INSTALLING -> "installazione…"
    RegionManager.Phase.DONE -> "completata"
    RegionManager.Phase.PAUSED -> "in pausa (riprendi con Scarica)"
    RegionManager.Phase.FAILED -> "non riuscita"
}
