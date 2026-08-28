package com.simone.jarvismobile.ui.localarchive

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette

private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)
private val CardBg = Color(0x660A1826)

/**
 * "Archivio locale JARVIS" (§ richiesta esplicita dell'utente): one browsable,
 * searchable surface over the files/foto già importati ([DocumentImportManager])
 * e le note ([ArchiveRepository]) — non un terzo store, solo un modo per
 * consultarli, aprirli o condividerli fuori da JARVIS invece di restare
 * raggiungibili solo dall'assistente. Both are already real content in the
 * nightly backup and already searchable by the AI (`search_documents`/
 * `search_archive`) — this screen adds the human-facing "manage/export" half
 * that was missing, it does not duplicate either store.
 */
@Composable
fun LocalArchiveScreen(
    onBack: () -> Unit,
    onOpenArchiveNotes: () -> Unit,
    viewModel: LocalArchiveViewModel = hiltViewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ink)
                }
                Text("Archivio locale", style = MaterialTheme.typography.headlineSmall, color = Cyan)
            }
            Text(
                "File e note già in JARVIS, in un unico posto: consultali, aprili o condividili fuori dall'app. " +
                    "Tutto qui è già incluso nel backup notturno.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Cerca file o note", color = Muted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan.copy(alpha = 0.7f),
                    unfocusedBorderColor = Muted.copy(alpha = 0.4f),
                    focusedContainerColor = Color(0x330A1826),
                    unfocusedContainerColor = Color(0x330A1826),
                    cursorColor = Cyan,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            if (documents.isEmpty() && notes.isEmpty()) {
                Text(
                    if (query.isBlank()) {
                        "Ancora vuoto. I documenti importati in chat e le note dell'Archivio compaiono qui."
                    } else {
                        "Nessun risultato per «$query»."
                    },
                    color = Muted,
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (documents.isNotEmpty()) {
                    item(key = "docs-header") { SectionHeader("File e foto (${documents.size})") }
                    items(documents, key = { "d:" + it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            onOpen = { openDocument(context, viewModel, doc) },
                            onShare = { shareDocument(context, viewModel, doc) },
                        )
                    }
                }
                if (notes.isNotEmpty()) {
                    item(key = "notes-header") { SectionHeader("Note (${notes.size})") }
                    items(notes, key = { "n:" + it.id }) { note ->
                        NoteRow(note = note, onClick = onOpenArchiveNotes)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        color = Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun DocumentRow(doc: DocumentRecord, onOpen: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph(doc), fontSize = 20.sp)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.displayName, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val size = if (doc.fileSize > 0) "${doc.fileSize / 1024} KB" else ""
            Text(size, color = Muted, fontSize = 11.sp)
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Condividi", tint = Cyan, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NoteRow(note: ArchiveItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📝", fontSize = 20.sp)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (note.content.isNotBlank()) {
                Text(note.content, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Opens the file in whatever app the device offers for its type — a viewer, a photo app, etc. */
private fun openDocument(context: android.content.Context, viewModel: LocalArchiveViewModel, doc: DocumentRecord) {
    val uri = viewModel.shareUri(doc) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, doc.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { if (it is ActivityNotFoundException) shareDocument(context, viewModel, doc) }
}

/** "Condividi" — the standard Android share sheet, which also covers "save a copy" via Files/Drive-style targets. */
private fun shareDocument(context: android.content.Context, viewModel: LocalArchiveViewModel, doc: DocumentRecord) {
    val uri = viewModel.shareUri(doc) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = doc.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Condividi ${doc.displayName}")) }
}
