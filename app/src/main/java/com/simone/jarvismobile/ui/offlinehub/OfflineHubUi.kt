package com.simone.jarvismobile.ui.offlinehub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.offlinehub.ExternalAppDetector
import com.simone.jarvismobile.core.offlinehub.MapsProvider
import com.simone.jarvismobile.core.offlinehub.RouteMode
import com.simone.jarvismobile.core.offlinehub.ZimAccess
import com.simone.jarvismobile.core.offlinehub.ZimClassifier
import com.simone.jarvismobile.core.offlinehub.ZimPolicy

private val Warn = Color(0xFFF3A65B)
private val Good = Color(0xFF2ECC71)

/**
 * "Biblioteca offline" (BETA 6): open Kiwix, point JARVIS at ZIM archives (read-only references, never copied),
 * and reach the text library and personal documents that the AI CAN query. The banner says plainly that ZIM
 * archives are opened in Kiwix, not read by the AI.
 */
@Composable
fun OfflineLibraryScreen(
    onBack: () -> Unit,
    onOpenDocuments: () -> Unit,
    viewModel: OfflineHubViewModel = hiltViewModel(),
) {
    val kiwix by viewModel.kiwix.collectAsStateWithLifecycle()
    val zims by viewModel.zims.collectAsStateWithLifecycle()
    val verifiedUris by viewModel.verifiedUris.collectAsStateWithLifecycle()
    val zimReports by viewModel.zimReports.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    var probeQuery by remember { mutableStateOf("") }
    val folder by viewModel.folderName.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val kStatus by viewModel.knowledgeStatus.collectAsStateWithLifecycle()
    val kName by viewModel.knowledgeName.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refresh() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.addZimFolder(it) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.addZimFile(it) } }
    val knowledgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.setKnowledgeFolder(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            Text("Biblioteca offline", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(ZimPolicy.LIBRARY_BANNER, style = MaterialTheme.typography.bodySmall, color = Warn)

        // --- Kiwix -------------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kiwix (Wikipedia e altri archivi ZIM)", fontWeight = FontWeight.SemiBold)
                if (!kiwix.isInstalled) {
                    Text("Kiwix non risulta installato. Installalo da Play Store o F-Droid e scarica gli archivi con la rete: poi si consultano anche in modalità aereo. JARVIS non installa app e non scarica archivi.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Installato: " + kiwix.installed.joinToString(", ") { ExternalAppDetector.variantLabel(it) }, style = MaterialTheme.typography.bodySmall, color = Good)
                    if (kiwix.installed.size > 1) {
                        Text("Quale versione usare:", style = MaterialTheme.typography.bodySmall)
                        kiwix.installed.forEach { pkg ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = kiwix.preferred == pkg, onClick = { viewModel.chooseKiwix(pkg) })
                                Text(ExternalAppDetector.variantLabel(pkg))
                            }
                        }
                    }
                }
                Button(onClick = viewModel::openKiwix, enabled = kiwix.isInstalled, modifier = Modifier.fillMaxWidth()) { Text("Apri Kiwix") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Cerca in Kiwix") },
                )
                OutlinedButton(onClick = { viewModel.kiwixSearch(query) }, enabled = kiwix.isInstalled && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Cerca in Kiwix") }
                Text("La ricerca passa il testo a Kiwix (tentativo): il risultato dipende dall'archivio aperto in Kiwix e JARVIS non lo legge.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- ZIM archives -------------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Archivi ZIM selezionati", fontWeight = FontWeight.SemiBold)
                Text(ZimPolicy.NO_COPY, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (folder == null) "Collega cartella" else "Cambia cartella") }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Aggiungi file ZIM") }
                }
                folder?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cartella: $it", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::removeZimFolder) { Text("Scollega") }
                    }
                }
                if (zims.isEmpty()) {
                    Text("Nessun archivio selezionato.", style = MaterialTheme.typography.bodySmall)
                }
                zims.forEach { z ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(z.name, fontWeight = FontWeight.Medium)
                        Text(
                            ZimClassifier.formatSize(z.sizeBytes) + " · " + z.access.label +
                                " · permesso " + (if (z.persistedPermission) "persistente" else "non persistente"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (z.access == ZimAccess.READABLE) Good else Warn,
                        )
                        val verified = z.uri in verifiedUris
                        val report = zimReports[z.uri]
                        Text(
                            if (verified) "Lettura diretta VERIFICATA su questo telefono: JARVIS può cercare per titolo e citare l'articolo."
                            else "Consultabile in Kiwix · non ancora interrogabile dall'AI (premi «Verifica lettura»)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (verified) Good else Warn,
                        )
                        report?.let {
                            Text(
                                if (it.ok) "«${it.title}» · ${it.date} · ${it.language} · ${it.entries} voci" else it.detail,
                                style = MaterialTheme.typography.bodySmall, color = if (it.ok) Good else Warn,
                            )
                            if (it.diagnostics.isNotEmpty()) Text(it.diagnostics, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.verifyZim(z) },
                                enabled = z.access == ZimAccess.READABLE && verifying == null,
                            ) { Text(if (verifying == z.uri) "Verifico…" else "Verifica lettura") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.openZim(z) }, enabled = kiwix.isInstalled && z.access == ZimAccess.READABLE) { Text("Apri in Kiwix") }
                            TextButton(onClick = { viewModel.removeZim(z) }) { Text("Rimuovi") }
                        }
                    }
                }
                Text("«Accessibile» significa solo che JARVIS vede il file e l'intestazione ZIM è valida: non garantisce che Kiwix riesca ad aprirlo.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- Probe search over the verified archives ------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Prova ricerca nelle fonti verificate", fontWeight = FontWeight.SemiBold)
                Text("Cerca per titolo negli archivi ZIM verificati e nella biblioteca testuale, con lo stesso controllo di pertinenza della chat. Nessun modello coinvolto.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = probeQuery, onValueChange = { probeQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Domanda di prova") })
                OutlinedButton(onClick = { viewModel.probeSearch(probeQuery) }, enabled = probeQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Cerca") }
                if (probe.isNotEmpty()) Text(probe, style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- What the AI can query ------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interrogabile dall'AI (offline)", fontWeight = FontWeight.SemiBold)
                Text("Documenti personali importati (PDF, DOCX, TXT…): chiedi in chat, con fonti.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenDocuments, modifier = Modifier.fillMaxWidth()) { Text("Apri i documenti personali") }
                Text(
                    when {
                        !kStatus.configured -> "Biblioteca testuale (TXT/Markdown): nessuna cartella collegata."
                        kStatus.indexing -> "Biblioteca testuale: indicizzazione in corso…"
                        else -> "Biblioteca testuale «${kName ?: "cartella"}»: ${kStatus.documentCount} file, ${kStatus.passageCount} passaggi."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { knowledgePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (kStatus.configured) "Cambia cartella testuale" else "Collega cartella TXT/Markdown")
                }
            }
        }

        if (message.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f), color = Warn, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::clearMessage) { Text("OK") }
            }
        }
    }
}

/**
 * Organic Maps card (BETA 6), placed in "Mappe offline": open it, search a place, show coordinates, prepare a route, and
 * choose which maps app the driving commands use. It says it cannot know whether the regional maps are downloaded
 * inside Organic Maps, and that a route is a preview the user starts in the app.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OrganicMapsCard(viewModel: OfflineHubViewModel = hiltViewModel()) {
    val om by viewModel.organic.collectAsStateWithLifecycle()
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RouteMode.CAR) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Organic Maps (navigazione offline)", fontWeight = FontWeight.SemiBold)
            if (!om.isInstalled) {
                Text("Organic Maps non risulta installato. Installalo da Play Store o F-Droid e scarica le mappe della tua zona con la rete: poi funziona senza Internet.", style = MaterialTheme.typography.bodySmall, color = Warn)
            } else {
                Text("Installato: " + om.installed.joinToString(", ") { ExternalAppDetector.variantLabel(it) }, style = MaterialTheme.typography.bodySmall, color = Good)
                if (om.installed.size > 1) om.installed.forEach { pkg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = om.preferred == pkg, onClick = { viewModel.chooseOrganic(pkg) }); Text(ExternalAppDetector.variantLabel(pkg))
                    }
                }
                Text("JARVIS non può sapere se le mappe della tua zona sono già scaricate in Organic Maps: se la ricerca non trova nulla, scaricale lì.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::openOrganic, enabled = om.isInstalled, modifier = Modifier.fillMaxWidth()) { Text("Apri Organic Maps") }

            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cerca un luogo (es. farmacia)") })
            OutlinedButton(onClick = { viewModel.organicSearch(search) }, enabled = om.isInstalled && search.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Cerca in Organic Maps") }

            OutlinedTextField(coords, { coords = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Coordinate (41.9028, 12.4964)") })
            OutlinedButton(onClick = { viewModel.organicShow(coords) }, enabled = om.isInstalled && coords.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Mostra coordinate") }

            OutlinedTextField(dest, { dest = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Destinazione (luogo salvato o coordinate)") })
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
            }
            OutlinedButton(onClick = { viewModel.organicRoute(dest, mode) }, enabled = om.isInstalled && dest.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Prepara percorso") }
            Text("Il percorso si apre come anteprima in Organic Maps: la navigazione la avvii tu nell'app. Senza coordinate verificate o senza la tua posizione, JARVIS apre la ricerca/la destinazione e non inventa nulla.", style = MaterialTheme.typography.bodySmall)

            Text("Mappa per i comandi di guida", fontWeight = FontWeight.SemiBold)
            MapsProvider.entries.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = provider == p, onClick = { viewModel.setProvider(p) })
                    Text(p.label)
                }
            }
            Text("La scelta vale per «imposta navigazione», «avvia percorso» e l'apertura della Modalità Guida. Il navigatore interno resta sempre disponibile con «portami a …».", style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, modifier = Modifier.weight(1f), color = Warn, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                }
            }
        }
    }
}
