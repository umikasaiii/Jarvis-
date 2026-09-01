package com.simone.jarvismobile.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.R
import com.simone.jarvismobile.audio.ChatFocus
import com.simone.jarvismobile.audio.ChatMessage
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.document.DocumentImportManager
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId
import kotlin.math.roundToInt

private const val MAX_VISIBLE_MESSAGES = 40

/** How wide a bubble may get, as a share of the list. */
private const val USER_BUBBLE_SHARE = 0.82f
private const val JARVIS_BUBBLE_SHARE = 0.88f

// The brand accent (§ Impostazioni › Temi) — everything else here stays fixed.
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
private val CyanBright: Color
    @Composable get() = LocalJarvisPalette.current.accentBright
private val Violet = Color(0xFF9B7BFF)
private val Aqua = Color(0xFF4FE3C1)
private val Amber = Color(0xFFF3B23C)
private val Coral = Color(0xFFFF6B5B)
private val Ink = Color(0xFFE6F2F8)
private val Muted = Color(0xFF7C8B95)

/**
 * What the header pill says. These are presentation states, deliberately coarser
 * than [ConversationState]: the user needs to know whether JARVIS is free,
 * hearing, working, talking or unavailable — not which of the eight internal
 * "thinking" steps is running.
 *
 * [CONFERMA] is the one addition to the five requested labels. The assistant can
 * genuinely be waiting for a yes/no before writing to the vault, and showing
 * "ELABORAZIONE" there would be a lie: nothing progresses until the user answers.
 */
// An enum entry's constructor arguments run at class-init, outside any
// @Composable context, so PRONTO/ASCOLTO cannot read the composable-getter
// Cyan/CyanBright directly here. [themedAccent] below substitutes the live
// theme value at render time instead, wherever .accent would otherwise be read.
private enum class ChatStatus(val label: String, val accent: Color) {
    PRONTO("Pronto", Color(0xFF3FD8F0)),
    ASCOLTO("Ascolto", Color(0xFF12D9FF)),
    ELABORAZIONE("Elaborazione", Violet),
    CONFERMA("Conferma", Amber),
    PARLO("Parlo", Aqua),
    OFFLINE("Offline", Coral),
}

/**
 * [ChatStatus.accent], theme-aware. PRONTO/ASCOLTO originally equalled
 * Cyan/CyanBright exactly — before theming existed they simply *were* the
 * brand accent — so this keeps that relationship live instead of frozen at
 * whatever hue was in the enum when it was declared. Every other status keeps
 * its own fixed, non-brand colour (thinking/confirm/speaking/offline).
 */
@Composable
private fun themedAccent(status: ChatStatus): Color = when (status) {
    ChatStatus.PRONTO -> Cyan
    ChatStatus.ASCOLTO -> CyanBright
    else -> status.accent
}

private fun statusFor(state: ConversationState): ChatStatus = when (state) {
    ConversationState.Idle, ConversationState.Cancelled -> ChatStatus.PRONTO
    ConversationState.Listening, ConversationState.FollowUpWindow -> ChatStatus.ASCOLTO
    ConversationState.Speaking -> ChatStatus.PARLO
    ConversationState.AwaitingConfirmation -> ChatStatus.CONFERMA
    ConversationState.PreparingAudio, ConversationState.FinalizingSpeech,
    ConversationState.Transcribing, ConversationState.RetrievingMemory,
    ConversationState.Routing, ConversationState.ThinkingLocal,
    ConversationState.ThinkingRemote, ConversationState.ExecutingTool -> ChatStatus.ELABORAZIONE
    else -> ChatStatus.OFFLINE
}

/**
 * The chat window.
 *
 * Three layers, in this order: a fixed background that never scrolls, the chat
 * content, and the HUD frame. The frame is drawn under the header and the
 * composer rather than over everything: its bottom-left ornament lands exactly
 * where the microphone sits, and a decorative arc must never sit on top of a
 * control. Everywhere else the content is inset past the frame's rails, so the
 * frame still reads as an overlay around the conversation.
 *
 * Only the message list scrolls. The background, the frame, the header and the
 * composer are laid out around it, so opening the keyboard shrinks the list and
 * lifts the composer without moving any of the artwork.
 *
 * All behaviour comes from [HomeViewModel] — this file is presentation only.
 */
@Composable
fun JarvisChatWindow(
    autoStartRequest: Int = 0,
    onOpenDiagnostics: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val composerBusy by viewModel.composerBusy.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val duplicate by viewModel.duplicatePrompt.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    // Chat mode: normal, or focus the answer on saved memory / imported documents.
    var chatFocus by remember { mutableStateOf(ChatFocus.ALL) }
    // Whether the next picked file is saved into the vault or only attached.
    var pendingSaveToVault by remember { mutableStateOf(false) }

    // The import reads the bytes immediately and copies them to app-private
    // storage, so the picker's transient read grant is enough — no persistable
    // permission is taken and nothing outside the picked files is reachable.
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { viewModel.onDocumentPicked(it, pendingSaveToVault) } }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach { viewModel.onDocumentPicked(it, pendingSaveToVault) } }

    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        if (micGranted) viewModel.onTalkPressed()
    }
    fun neededPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    fun onMicTap() {
        when {
            state == ConversationState.Speaking -> viewModel.onInterruptAndTalk()
            state.isRestingLike() -> {
                if (micGranted) viewModel.onTalkPressed() else permissionLauncher.launch(neededPermissions())
            }
            else -> viewModel.onCancel()
        }
    }

    if (autoStartRequest > 0) {
        LaunchedEffect(autoStartRequest) {
            if (micGranted) viewModel.onTalkPressed() else permissionLauncher.launch(neededPermissions())
        }
    }

    val status = statusFor(state)

    Box(Modifier.fillMaxSize()) {
        FixedBackground()
        FixedHudFrame(Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding() // the window is a panel below the status bar
                .imePadding()
                .padding(horizontal = 20.dp), // clears the frame's side rails
        ) {
            ChatHeader(name = name, status = status, onOpenSettings = onOpenSettings)

            MessageList(
                messages = messages.takeLast(MAX_VISIBLE_MESSAGES),
                assistantName = name,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            if (state == ConversationState.Listening && partial.isNotEmpty()) {
                Text(
                    "“$partial”",
                    color = Ink,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            if (error != null && state.isRestingLike()) {
                Text(
                    friendlyError(error!!),
                    color = Coral,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }

            QuickLinks(
                canReset = messages.isNotEmpty(),
                sending = sending,
                onNewConversation = viewModel::onNewConversation,
                onOpenModels = onOpenModels,
                onOpenMemory = onOpenMemory,
                onOpenDiagnostics = onOpenDiagnostics,
            )

            if (documents.isNotEmpty()) {
                AttachmentRow(
                    documents = documents,
                    onRemove = viewModel::onRemoveDocument,
                    onCancel = viewModel::onCancelDocument,
                )
            }

            ChatModePicker(current = chatFocus, onSelect = { chatFocus = it })

            ChatComposer(
                text = textInput,
                onTextChange = { textInput = it },
                status = status,
                busy = composerBusy,
                onMic = ::onMicTap,
                onAttach = { showAttachSheet = true },
                micDescription = when {
                    state == ConversationState.Speaking -> "Interrompi e parla"
                    !state.isRestingLike() -> "Ferma"
                    else -> "Parla"
                },
                onSend = {
                    if (composerBusy) {
                        viewModel.onStopResponse()
                    } else {
                        val msg = textInput.trim()
                        if (msg.isNotEmpty()) {
                            viewModel.onSendText(msg, chatFocus)
                            textInput = ""
                        }
                    }
                },
            )
        }

        if (showAttachSheet) {
            AttachmentSheet(
                onDismiss = { showAttachSheet = false },
                onPickDocument = { save ->
                    pendingSaveToVault = save
                    showAttachSheet = false
                    documentPicker.launch(DOCUMENT_MIME_TYPES)
                },
                onPickImage = {
                    pendingSaveToVault = false
                    showAttachSheet = false
                    imagePicker.launch(arrayOf("image/*"))
                },
            )
        }

        duplicate?.let { prompt ->
            DuplicateDialog(
                prompt = prompt,
                onUseExisting = viewModel::onDuplicateUseExisting,
                onImportAnyway = { viewModel.onDuplicateImportAnyway(prompt) },
                onDismiss = viewModel::onDismissDuplicate,
            )
        }
    }
}

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
    "application/json",
    "text/csv",
    "text/*",
)

// --- document attachments --------------------------------------------------

/** Horizontally scrollable strip of attachment cards above the composer. */
@Composable
private fun AttachmentRow(
    documents: List<DocumentRecord>,
    onRemove: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        documents.take(12).forEach { doc ->
            AttachmentCard(
                doc = doc,
                onRemove = { onRemove(doc.id) },
                onCancel = { onCancel(doc.id) },
            )
        }
    }
}

@Composable
private fun AttachmentCard(doc: DocumentRecord, onRemove: () -> Unit, onCancel: () -> Unit) {
    val busy = doc.status != DocumentStatus.READY && doc.status != DocumentStatus.FAILED
    Row(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC161A20))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(fileGlyph(doc), fontSize = 18.sp)
        Column(modifier = Modifier.widthIn(max = 150.dp)) {
            Text(
                doc.displayName,
                color = Color(0xFFEAEEF3),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLabel(doc),
                color = if (doc.status == DocumentStatus.FAILED) Coral else Cyan,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "✕",
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { if (busy) onCancel() else onRemove() }
                .padding(4.dp),
        )
    }
}

private fun fileGlyph(doc: DocumentRecord): String =
    when (doc.fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "📕"
        "doc", "docx" -> "📘"
        "csv", "tsv" -> "📊"
        "json" -> "🗂️"
        "md", "markdown" -> "📝"
        "png", "jpg", "jpeg", "webp" -> "🖼️"
        else -> "📄"
    }

private fun statusLabel(doc: DocumentRecord): String = when (doc.status) {
    DocumentStatus.SELECTED -> "In coda…"
    DocumentStatus.COPYING -> "Copia…"
    DocumentStatus.PARSING -> "Analisi…"
    DocumentStatus.INDEXING -> "Indicizzazione…"
    DocumentStatus.READY -> if (doc.vaultPath != null) "Pronto · archivio" else "Pronto"
    DocumentStatus.FAILED -> "Non riuscito"
}

/** The attach menu: document / image / save-into-archive. */
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPickDocument: (saveToVault: Boolean) -> Unit,
    onPickImage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        title = { Text("Aggiungi alla chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetOption("📄  Allega documento", "PDF, DOCX, TXT, MD, JSON, CSV") { onPickDocument(false) }
                SheetOption("🖼️  Allega immagine", "Salvata come allegato") { onPickImage() }
                SheetOption("📚  Importa nell'archivio", "Copia nel vault e indicizza") { onPickDocument(true) }
            }
        },
    )
}

@Composable
private fun SheetOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Text(title, color = Color(0xFFEAEEF3), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DuplicateDialog(
    prompt: DocumentImportManager.DuplicatePrompt,
    onUseExisting: () -> Unit,
    onImportAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onUseExisting) { Text("Usa esistente") } },
        dismissButton = { TextButton(onClick = onImportAnyway) { Text("Importa comunque") } },
        title = { Text("Documento già presente") },
        text = {
            Text(
                "«${prompt.displayName}» è già nell'archivio come «${prompt.existing.displayName}». " +
                    "Vuoi usare quello esistente o importarlo di nuovo?",
            )
        },
    )
}

// --- layer 1: background ---------------------------------------------------

/**
 * The chat backdrop. It is a sibling of the scrolling list, not a decoration
 * inside it, so it stays put while messages move.
 *
 * The source art is opaque (alpha=255 throughout, a real photographic-style
 * texture), so a flat [ColorFilter.tint] would flatten the whole circuit
 * pattern into one solid rectangle instead of recolouring it — the same
 * problem `bg_dashboard` had for Rosso. `chat_background_red.webp` is a
 * pre-generated hue rotation of the same asset (source hue ~207° → target
 * ~3.5°, matching RossoPalette.accent, via a Pillow HSV shift) instead of a
 * hand-picked replacement: it preserves every dot/line/glow at its original
 * brightness, just recoloured, and needs no dedicated Rouge variant since
 * Rouge shares Rosso's exact palette.
 */
@Composable
private fun FixedBackground() {
    val themeId = LocalJarvisThemeId.current
    val res = if (themeId == JarvisThemeId.BLU) R.drawable.chat_background else R.drawable.chat_background_red
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

// --- layer 3: HUD frame ----------------------------------------------------

// The frame is sliced horizontally into three bands and only the middle one is
// stretched. Stretching the whole 2:3 artwork onto a ~1:2 phone panel would pull
// the corner brackets and the lower arc out of shape; the middle band is nothing
// but the two straight side rails, so it can grow to any height without showing
// it. The numbers are fractions of the source, measured on rows where the art
// contains the rails and nothing else — cutting anywhere else would slice a
// circuit trace in half.
private const val FRAME_TOP_END = 480f / 1536f
private const val FRAME_RAIL_TOP = 484f / 1536f
private const val FRAME_RAIL_HEIGHT = 12f / 1536f
private const val FRAME_BOTTOM_START = 1086f / 1536f

/** The frame is scenery, not content: it stays behind the conversation. */
private const val FRAME_ALPHA = 0.8f

/**
 * The HUD frame: a fixed, non-interactive overlay with a genuinely transparent
 * centre. It draws nothing but the artwork, so it takes no pointer input and the
 * message list underneath stays fully touchable.
 */
@Composable
private fun FixedHudFrame(modifier: Modifier = Modifier) {
    val frame = ImageBitmap.imageResource(R.drawable.chat_hud_frame)
    // Same conditional recolour as HudOverlay/JarvisCard's frame: this is a
    // real-alpha, near-monochrome line texture (corners/rails, no baked
    // background), so a flat tint recolours it cleanly without flattening detail.
    val themeId = LocalJarvisThemeId.current
    val tint = if (themeId == JarvisThemeId.BLU) null else ColorFilter.tint(LocalJarvisPalette.current.accent)
    Canvas(modifier) {
        val sw = frame.width
        val sh = frame.height
        val w = size.width.roundToInt()
        if (w <= 0 || sw <= 0) return@Canvas

        val scale = size.width / sw
        val topSrc = (sh * FRAME_TOP_END).roundToInt()
        val bottomSrc = (sh * FRAME_BOTTOM_START).roundToInt()

        var topH = topSrc * scale
        var bottomH = (sh - bottomSrc) * scale
        // A very short panel (a small window, a huge keyboard) must still show
        // both ends rather than overdraw them.
        val ends = topH + bottomH
        if (ends > size.height && ends > 0f) {
            val k = size.height / ends
            topH *= k
            bottomH *= k
        }
        val midH = (size.height - topH - bottomH).coerceAtLeast(0f)

        drawImage(
            image = frame,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sw, topSrc),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w, topH.roundToInt()),
            alpha = FRAME_ALPHA,
            colorFilter = tint,
        )
        if (midH > 0f) {
            drawImage(
                image = frame,
                srcOffset = IntOffset(0, (sh * FRAME_RAIL_TOP).roundToInt()),
                srcSize = IntSize(sw, (sh * FRAME_RAIL_HEIGHT).roundToInt().coerceAtLeast(2)),
                dstOffset = IntOffset(0, topH.roundToInt()),
                dstSize = IntSize(w, midH.roundToInt()),
                alpha = FRAME_ALPHA,
                colorFilter = tint,
            )
        }
        drawImage(
            image = frame,
            srcOffset = IntOffset(0, bottomSrc),
            srcSize = IntSize(sw, sh - bottomSrc),
            dstOffset = IntOffset(0, (size.height - bottomH).roundToInt()),
            dstSize = IntSize(w, bottomH.roundToInt()),
            alpha = FRAME_ALPHA,
            colorFilter = tint,
        )
    }
}

// --- header ----------------------------------------------------------------

/**
 * `⚡ JARVIS · ● STATO · ⚙`. The wordmark and the state are real text, not
 * baked-in artwork: the state changes several times per turn and the assistant's
 * name is editable in Settings.
 */
@Composable
private fun ChatHeader(name: String, status: ChatStatus, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Fulmine dedicato (§ richiesta esplicita: "cambia il design
            // della chat scritta") al posto del glifo Material — arte
            // reale con alpha vera, nessun tint necessario (già rosso).
            Image(
                painter = painterResource(R.drawable.chat_lightning_icon),
                contentDescription = null,
                modifier = Modifier.height(24.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                name.uppercase(),
                color = Color(0xFFF2FAFF),
                fontSize = 21.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.5.sp,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(status)
            // Pulsante Impostazioni con la nuova cornice ottagonale fornita
            // dall'utente, al posto dell'IconButton Material generico.
            Image(
                painter = painterResource(R.drawable.chat_settings_btn),
                contentDescription = "Impostazioni",
                modifier = Modifier
                    .size(38.dp)
                    .clickable(onClick = onOpenSettings),
            )
        }
    }
}

/** Dark pill, thin coloured rim, lit dot — the dot is the fastest thing to read. */
@Composable
private fun StatusPill(status: ChatStatus) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xB3040C14))
            .border(1.dp, themedAccent(status).copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(themedAccent(status)))
        Spacer(Modifier.width(6.dp))
        Text(
            status.label.uppercase(),
            color = themedAccent(status),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.1.sp,
            maxLines = 1,
        )
    }
}

// --- messages --------------------------------------------------------------

/**
 * The only scrolling region. Bubble widths are a share of the measured list
 * width rather than fixed dp, so they hold their proportions on any screen and
 * at any display-size setting.
 */
@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    assistantName: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tocca il microfono e parla,\noppure scrivi qui sotto.",
                    color = Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@BoxWithConstraints
        }

        val userMax = maxWidth * USER_BUBBLE_SHARE
        val jarvisMax = maxWidth * JARVIS_BUBBLE_SHARE

        val listState = rememberLazyListState()
        // Keyed on the last message, not just the count: once the window is full
        // the count stops changing, and a reply that streams in place would
        // otherwise scroll off the bottom as it grows.
        LaunchedEffect(messages.size, messages.lastOrNull()) {
            listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    assistantName = assistantName,
                    maxWidth = if (message.fromUser) userMax else jarvisMax,
                )
            }
        }
    }
}

/**
 * A bubble is drawn, never an image: it has to grow with the text, wrap over
 * many lines and keep up with a streaming reply.
 */
@Composable
private fun MessageBubble(message: ChatMessage, assistantName: String, maxWidth: Dp) {
    val isUser = message.fromUser
    val shape = RoundedCornerShape(24.dp)

    // Arrival: fade up from slightly below. Only new items animate — an existing
    // bubble keeps its finished animation across recompositions.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(200, easing = LinearOutSlowInEasing))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 8.dp.toPx()
                }
                .clip(shape)
                .background(Color(0xE60A1420))
                // The user bubble additionally carries a faint accent wash over
                // the same dark base — previously a fixed navy fill, now tinted
                // to the theme so a red/Rouge theme doesn't leave one lone blue
                // panel behind. Two stacked backgrounds instead of one literal
                // colour so the wash composites over dark, not over transparent.
                .then(if (isUser) Modifier.background(Cyan.copy(alpha = 0.22f)) else Modifier)
                .border(
                    width = 1.2.dp,
                    color = if (isUser) Cyan.copy(alpha = 0.6f) else Cyan.copy(alpha = 0.32f),
                    shape = shape,
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text(
                if (isUser) "TU" else assistantName.uppercase(),
                color = Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(message.text, color = Ink, fontSize = 18.sp, lineHeight = 26.sp)
        }
    }
}

// --- composer --------------------------------------------------------------

private fun ChatFocus.label(): String = when (this) {
    ChatFocus.ALL -> "Tutto"
    ChatFocus.MEMORY -> "Focus memoria / doc"
    ChatFocus.KNOWLEDGE -> "Focus wiki"
}

/**
 * The openable chat-mode chip: a tap reveals the three modes (everything, only
 * memory, only imported documents/library). The choice restricts where the next
 * question is answered from.
 */
@Composable
private fun ChatModePicker(current: ChatFocus, onSelect: (ChatFocus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = "Modalità chat",
                tint = if (current == ChatFocus.ALL) Muted else CyanBright,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                current.label(),
                fontSize = 12.sp,
                color = if (current == ChatFocus.ALL) Muted else CyanBright,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChatFocus.entries.forEach { focus ->
                DropdownMenuItem(
                    text = { Text(focus.label()) },
                    onClick = { onSelect(focus); open = false },
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    status: ChatStatus,
    /**
     * Whether the composer should present as "busy" — true only while a
     * response is genuinely running AND no stop has been requested yet. The
     * moment Stop is tapped this goes false again (see
     * HomeViewModel.composerBusy's doc comment): the abandoned response
     * finishes discarding itself in the background, but the composer must
     * not stay locked waiting for that.
     */
    busy: Boolean,
    micDescription: String,
    onMic: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
) {
    // Nuovo "dock" fornito dall'utente (§ richiesta esplicita: "cambia il
    // design della chat scritta") — un'unica barra con due fori reali
    // (misurati sui pixel dell'asset: centro a frazione 0.101/0.898 della
    // larghezza, diametro ~0.13) per allega/invia, più la pillola centrale
    // già disegnata per il campo di testo. Il microfono non ha un foro
    // dedicato in questo asset (solo 2 fori, non 3) — resta un pulsante a
    // parte con l'artwork esistente invece di indovinare una posizione mai
    // mostrata nel riferimento.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicrophoneButton(
            status = status,
            enabled = !busy,
            contentDescription = micDescription,
            onClick = onMic,
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            val barWidth = maxWidth
            val barHeight = barWidth / CHAT_DOCK_BAR_ASPECT_RATIO
            val holeSize = barWidth * CHAT_DOCK_HOLE_DIAMETER_FRACTION
            Box(Modifier.fillMaxWidth().height(barHeight)) {
                Image(
                    painter = painterResource(R.drawable.chat_dock_bar),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                )
                Image(
                    painter = painterResource(R.drawable.chat_attach_btn),
                    contentDescription = "Allega",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = barWidth * CHAT_DOCK_LEFT_HOLE_CENTER_X - holeSize / 2)
                        .size(holeSize)
                        .clip(CircleShape)
                        .clickable(onClick = onAttach),
                )
                DockMessageInput(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = !busy,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = holeSize * 0.95f),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = -(barWidth * (1f - CHAT_DOCK_RIGHT_HOLE_CENTER_X) - holeSize / 2))
                        .size(holeSize)
                        .clip(CircleShape)
                        .clickable(enabled = busy || text.isNotBlank(), onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.chat_send_btn2),
                        contentDescription = if (busy) "Ferma risposta" else "Invia",
                        // L'artwork è una freccia; mentre una risposta è in
                        // corso il pulsante significa "ferma", quindi la
                        // freccia arretra e un simbolo di stop prende il
                        // centro — stesso comportamento del vecchio
                        // SendButton basato su AssetButton.
                        alpha = if (busy) 0.3f else 1f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (busy) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = null,
                            tint = Coral,
                            modifier = Modifier.size(holeSize * 0.4f),
                        )
                    }
                }
            }
        }
    }
}

private const val CHAT_DOCK_BAR_ASPECT_RATIO = 1200f / 267f
private const val CHAT_DOCK_LEFT_HOLE_CENTER_X = 0.101f
private const val CHAT_DOCK_RIGHT_HOLE_CENTER_X = 0.898f
private const val CHAT_DOCK_HOLE_DIAMETER_FRACTION = 0.135f

/** No border/fill of its own so the new dock bar's own baked-in pill shows through underneath. */
@Composable
private fun DockMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text("Scrivi un messaggio…", color = Muted, fontSize = 15.sp) },
        enabled = enabled,
        maxLines = 5,
        shape = RoundedCornerShape(26.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink, fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            cursorColor = Cyan,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            disabledTextColor = Muted,
        ),
    )
}

/**
 * Asset 3. The halo carries the state; the artwork's own rim/icon glow is a
 * near-monochrome cyan-on-black render (same family as `bg_card`/`hud_*`), so
 * on Rosso/Rouge it is tinted the same way instead of staying fixed cyan.
 */
@Composable
private fun MicrophoneButton(
    status: ChatStatus,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // While listening the halo breathes, so the control shows the mic is open
    // even when the user is not looking at the status pill.
    val listening = status == ChatStatus.ASCOLTO
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = if (listening) 0.45f else 0f,
        targetValue = if (listening) 1f else 0f,
        animationSpec = infiniteRepeatable(
            tween(1100, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "micPulse",
    )

    AssetButton(
        res = R.drawable.chat_microphone,
        contentDescription = contentDescription,
        enabled = enabled,
        pressedScale = 0.94f,
        glow = if (listening) pulse else 0.22f,
        glowColor = themedAccent(status),
        onClick = onClick,
    )
}

/**
 * A round image button. The bitmap keeps its own alpha and a 1:1 box, so it is
 * never stretched into an oval and never shows a rectangle behind it; the ripple
 * is switched off because a rectangular highlight would square off the disc.
 */
@Composable
private fun AssetButton(
    res: Int,
    contentDescription: String,
    enabled: Boolean,
    pressedScale: Float,
    glow: Float,
    glowColor: Color,
    artworkAlpha: Float = 1f,
    onClick: () -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val press = remember { Animatable(1f) }
    LaunchedEffect(interaction, pressedScale) {
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> press.animateTo(pressedScale, tween(80))
                else -> {
                    press.animateTo(1.03f, tween(110))
                    press.animateTo(1f, tween(130))
                }
            }
        }
    }

    // The disc/rim artwork is a near-monochrome cyan-on-black render (same
    // family as `bg_card`/`hud_*`), so it is tinted like them on Rosso/Rouge
    // instead of staying fixed cyan regardless of the selected theme.
    val themeId = LocalJarvisThemeId.current
    val artTint = if (themeId == JarvisThemeId.BLU) null else ColorFilter.tint(LocalJarvisPalette.current.accent)

    // The touch target is the whole box, not the artwork inside it. It used to
    // sit on the 54dp image, so the lit ring around the disc — which reads as
    // part of the button — did nothing, and stopping a running reply meant
    // hitting a smaller circle than the one on screen.
    Box(
        modifier = Modifier
            .size(62.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (glow > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(glowColor.copy(alpha = 0.55f * glow), Color.Transparent),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                )
            }
        }
        Image(
            painter = painterResource(res),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            alpha = if (enabled) artworkAlpha else artworkAlpha * 0.45f,
            colorFilter = artTint,
            modifier = Modifier
                .size(54.dp)
                .aspectRatio(1f)
                .scale(press.value),
        )
        overlay()
    }
}


/**
 * Kept from the previous layout: these are the only way into a new conversation
 * and into Models/Memory/Diagnostics from the chat, so they are restyled rather
 * than dropped — removing them would take away function, not decoration.
 */
@Composable
private fun QuickLinks(
    canReset: Boolean,
    sending: Boolean,
    onNewConversation: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (canReset) {
            QuickLink("Nuova", if (sending) Muted else Amber, !sending, onNewConversation)
        }
        QuickLink("Modelli", if (sending) Muted else Cyan, !sending, onOpenModels)
        QuickLink("Memoria", Cyan, true, onOpenMemory)
        QuickLink("Diagnostica", Cyan, true, onOpenDiagnostics)
    }
}

@Composable
private fun QuickLink(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp, letterSpacing = 0.5.sp)
    }
}

// --- shared helpers --------------------------------------------------------

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle, ConversationState.Cancelled,
    is ConversationState.RecoverableError, is ConversationState.FatalError,
    ConversationState.PermissionRequired, ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable, ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable -> true
    else -> false
}

private fun friendlyError(code: String): String = when (code) {
    "tts_unavailable" -> "Voce italiana offline non disponibile: installala in Impostazioni Android › TTS"
    "empty_transcript" -> "Non ho sentito nulla, riprova"
    "record_failed", "permission" -> "Microfono non disponibile ($code) — verifica il permesso"
    else -> "Errore: $code"
}
