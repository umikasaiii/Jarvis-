package com.simone.jarvismobile.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Memory screen (Phase 5): connect an Obsidian vault folder (read-only, via the
 * system folder picker) and index its Markdown notes so JARVIS can ground answers
 * in them. Nothing is uploaded; the vault stays the source of truth and the index
 * is a rebuildable cache (docs/PRIVACY.md).
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val vaultName by viewModel.vaultName.collectAsStateWithLifecycle()

    // OpenDocumentTree grants read access to a whole folder (the vault) with a
    // persistable permission — no MANAGE_EXTERNAL_STORAGE.
    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.onVaultPicked(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Memoria (appunti Obsidian)", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Vault: ${vaultName ?: "—"}", style = MaterialTheme.typography.titleMedium)
                Text("Stato: ${statusLabel(status)}", style = MaterialTheme.typography.bodyMedium)
                if (status.noteCount > 0 || status.chunkCount > 0) {
                    Text(
                        "${status.noteCount} note · ${status.chunkCount} frammenti indicizzati",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                status.lastError?.let {
                    Text("Errore: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(
            onClick = { pickVault.launch(null) },
            enabled = !status.building,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (status.configured) "Cambia cartella vault" else "Scegli cartella vault")
        }

        if (status.configured) {
            OutlinedButton(
                onClick = viewModel::reindex,
                enabled = !status.building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status.building) "Indicizzazione…" else "Reindicizza")
            }
            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnetti vault")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Come funziona", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Scegli la cartella del tuo vault Obsidian (o una qualsiasi cartella con file " +
                        ".md). JARVIS indicizza le note sul dispositivo e, quando fai una domanda, " +
                        "recupera i pezzi più pertinenti e li usa come contesto. Puoi anche fargli " +
                        "salvare qualcosa dicendo o scrivendo “ricorda che …”: lo aggiunge a " +
                        "JARVIS/Memoria.md nel vault. Niente viene caricato online; i file restano " +
                        "la fonte di verità e l'indice è una cache ricostruibile. Usa “Reindicizza” " +
                        "dopo aver modificato le note a mano.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Indietro")
        }

        Text(
            "Retrieval locale · nessun cloud · il vault non viene mai modificato",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun statusLabel(s: com.simone.jarvismobile.memory.MemoryIndex.Status): String = when {
    s.building -> "Indicizzazione in corso…"
    !s.configured -> "Nessun vault collegato"
    s.chunkCount > 0 -> "Pronta"
    else -> "Collegato (vuoto)"
}
