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
import androidx.compose.runtime.getValue
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

            progress?.let { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${p.name} · ${phaseLabel(p.phase)}", style = MaterialTheme.typography.bodyMedium)
                        if (p.phase == RegionManager.Phase.COPYING && p.bytesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
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
                        RegionRow(region = region, onDelete = { viewModel.delete(region.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(region: RegionMetadata, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(region.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle(region), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Elimina")
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
    RegionManager.Phase.COPYING -> "copia…"
    RegionManager.Phase.VERIFYING -> "verifica…"
    RegionManager.Phase.INSTALLING -> "installazione…"
    RegionManager.Phase.DONE -> "completata"
    RegionManager.Phase.FAILED -> "non riuscita"
}
