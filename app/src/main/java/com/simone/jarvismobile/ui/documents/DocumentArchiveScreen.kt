package com.simone.jarvismobile.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import com.simone.jarvismobile.document.category
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentSource
import com.simone.jarvismobile.core.document.DocumentStatus

/** The document archive: every imported document, its state, and a way to remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentArchiveScreen(
    onBack: () -> Unit,
    viewModel: DocumentArchiveViewModel = hiltViewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archivio documenti") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (documents.isEmpty()) {
                Text(
                    "Nessun documento. Nella chat premi «+» per allegarne o importarne uno.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                Text(
                    "${documents.size} documenti · gli originali non vengono mai modificati.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                // Grouped by the AI macro-category (files, photos and documents
                // sorted like notes); the unclassified bucket sorts last.
                val byCategory = documents.groupBy { it.category() }
                    .toSortedMap(compareBy({ it == "Senza categoria" }, { it }))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    byCategory.forEach { (category, docs) ->
                        item(key = "hdr-$category") {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(docs, key = { it.id }) { doc ->
                            DocumentArchiveRow(
                                doc = doc,
                                onRemove = { viewModel.remove(doc.id) },
                                onCancel = { viewModel.cancel(doc.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentArchiveRow(doc: DocumentRecord, onRemove: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(glyph(doc), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(
                    doc.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle(doc), style = MaterialTheme.typography.bodySmall)
            }
            val busy = doc.status != DocumentStatus.READY && doc.status != DocumentStatus.FAILED
            IconButton(onClick = { if (busy) onCancel() else onRemove() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Rimuovi", modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun glyph(doc: DocumentRecord): String =
    when (doc.fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "📕"
        "doc", "docx" -> "📘"
        "csv", "tsv", "xlsx" -> "📊"
        "json" -> "🗂️"
        "md", "markdown" -> "📝"
        "epub" -> "📚"
        "png", "jpg", "jpeg", "webp" -> "🖼️"
        else -> "📄"
    }

private fun subtitle(doc: DocumentRecord): String {
    val where = when {
        doc.source == DocumentSource.VAULT_IMPORT && doc.vaultPath != null -> "archivio"
        else -> "chat"
    }
    val size = if (doc.fileSize > 0) " · ${doc.fileSize / 1024} KB" else ""
    val pages = doc.pageCount?.let { " · $it pag." } ?: ""
    val status = when (doc.status) {
        DocumentStatus.READY -> "pronto"
        DocumentStatus.FAILED -> "non riuscito"
        else -> "in lavorazione…"
    }
    return "$status · $where$size$pages"
}
