package com.simone.jarvismobile.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.audio.ChatMessage
import com.simone.jarvismobile.core.state.ConversationState

private const val MAX_VISIBLE_MESSAGES = 40

private val Accent = Color(0xFF3FD8F0)
private val Green = Color(0xFF2ECC71)
private val Blue = Color(0xFF3B9EFF)
private val Amber = Color(0xFFF3B23C)
private val Red = Color(0xFFE74C3C)
private val Muted = Color(0xFF7C8B95)
private val Ink = Color(0xFFE3EFF5)
private val Surface = Color(0xFF102030)

/**
 * Chat tab — a classic messaging interface for JARVIS. Messages fill the screen;
 * the bottom bar has a microphone (voice), a comfortable text field, and a send
 * button. The big reactor orb lives on the dashboard; here talking is the mic
 * button so the conversation stays visible, keyboard open or not.
 */
@Composable
fun HomeScreen(
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

    var textInput by remember { mutableStateOf("") }

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

    val accent = accentFor(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1826), Color(0xFF0B1927), Color(0xFF060E16)))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding() // the chat lives in a panel below the status bar
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // Header.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, null, tint = Accent)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        name.uppercase(),
                        color = Accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(statusLabel(state), accent)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = Color(0xFFB8C4CC))
                    }
                }
            }

            // Messages fill the screen.
            val visible = messages.takeLast(MAX_VISIBLE_MESSAGES)
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size) {
                if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
            }
            if (visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Tocca 🎤 e parla, oppure scrivi qui sotto.",
                        color = Muted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                ) {
                    items(visible) { ChatBubble(it, name) }
                }
            }

            // Live partial + quick links.
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
                    color = Red,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (messages.isNotEmpty()) {
                    TextButton(onClick = viewModel::onNewConversation, enabled = !sending) {
                        Text("Nuova", color = if (sending) Muted else Amber, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onOpenModels, enabled = !sending) {
                    Text("Modelli", color = if (sending) Muted else Accent, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenMemory) { Text("Memoria", color = Accent, fontSize = 12.sp) }
                TextButton(onClick = onOpenDiagnostics) { Text("Diagnostica", color = Accent, fontSize = 12.sp) }
            }

            // Input bar: mic · field · send.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val talking = !state.isRestingLike()
                RoundButton(
                    color = if (talking) Red else Accent,
                    onClick = { onMicTap() },
                    enabled = !sending,
                ) {
                    Icon(
                        if (talking) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = when (state) {
                            ConversationState.Speaking -> "Interrompi e parla"
                            else -> if (talking) "Ferma" else "Parla"
                        },
                        tint = Color(0xFF07131A),
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    placeholder = { Text("Scrivi un messaggio…", color = Muted) },
                    enabled = !sending,
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent.copy(alpha = 0.85f),
                        unfocusedBorderColor = Accent.copy(alpha = 0.40f),
                        focusedContainerColor = Color(0x99061019),
                        unfocusedContainerColor = Color(0x66040C14),
                        cursorColor = Accent,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                    ),
                )

                val canSend = textInput.isNotBlank() && !sending
                RingButton(
                    accent = when {
                        sending -> Red
                        canSend -> Accent
                        else -> Muted
                    },
                    dim = !canSend && !sending,
                    onClick = {
                        if (sending) {
                            viewModel.onStopResponse()
                        } else {
                            val msg = textInput.trim()
                            if (msg.isNotEmpty()) { viewModel.onSendText(msg); textInput = "" }
                        }
                    },
                    enabled = sending || canSend,
                ) {
                    if (sending) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Ferma risposta",
                            tint = Color.White,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Invia",
                            tint = if (canSend) Accent else Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(color: Color, onClick: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChatBubble(message: ChatMessage, assistantName: String) {
    val isUser = message.fromUser
    // One accent for both sides. The old green for replies fought the cyan HUD;
    // side is already carried by alignment and by the corner marker below.
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(modifier = Modifier.widthIn(max = 310.dp)) {
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (isUser) Color(0xCC0B2130) else Color(0xCC08161F),
                                Color(0xB3040C14),
                            ),
                        ),
                    )
                    .border(1.dp, Accent.copy(alpha = if (isUser) 0.55f else 0.40f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    if (isUser) "TU" else assistantName.uppercase(),
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.size(4.dp))
                Text(message.text, color = Ink, fontSize = 16.sp, lineHeight = 22.sp)
            }

            // A short bright stroke on the corner nearest the speaker. It is what
            // makes the bubbles read as instrument panels rather than as generic
            // rounded rectangles, and it says who is talking without a colour
            // change that would break the single-accent palette.
            Canvas(Modifier.matchParentSize()) {
                val r = 14.dp.toPx()
                val len = 26.dp.toPx()
                val w = 2.dp.toPx()
                val y = w / 2
                if (isUser) {
                    drawLine(Accent, Offset(size.width - r, y), Offset(size.width - r - len, y), w)
                    drawLine(Accent, Offset(size.width - w / 2, r), Offset(size.width - w / 2, r + len * 0.6f), w)
                } else {
                    drawLine(Accent, Offset(r, y), Offset(r + len, y), w)
                    drawLine(Accent, Offset(w / 2, r), Offset(w / 2, r + len * 0.6f), w)
                }
            }
        }
    }
}

/**
 * A hollow neon ring with a glow: the send control in the reference design is a
 * lit outline, not a filled disc, which keeps the composer reading as part of
 * the HUD rather than as a Material button dropped on top of it.
 */
@Composable
private fun RingButton(
    accent: Color,
    dim: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = if (dim) 0.05f else 0.18f), Color.Transparent),
                ),
            )
            .border(if (dim) 1.dp else 2.dp, accent.copy(alpha = if (dim) 0.35f else 0.9f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x66061019))
            .border(1.dp, accent.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.size(6.dp))
        Text(
            text.uppercase(),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        )
    }
}

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle, ConversationState.Cancelled,
    is ConversationState.RecoverableError, is ConversationState.FatalError,
    ConversationState.PermissionRequired, ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable, ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable -> true
    else -> false
}

private fun accentFor(state: ConversationState): Color = when {
    state == ConversationState.Listening || state == ConversationState.FollowUpWindow -> Green
    state == ConversationState.Speaking -> Blue
    state is ConversationState.RecoverableError || state is ConversationState.FatalError -> Red
    else -> Accent
}

private fun friendlyError(code: String): String = when (code) {
    "tts_unavailable" -> "Voce italiana offline non disponibile: installala in Impostazioni Android › TTS"
    "empty_transcript" -> "Non ho sentito nulla, riprova"
    "record_failed", "permission" -> "Microfono non disponibile ($code) — verifica il permesso"
    else -> "Errore: $code"
}

private fun statusLabel(state: ConversationState): String = when (state) {
    ConversationState.Idle -> "Pronto"
    ConversationState.PreparingAudio -> "Preparo…"
    ConversationState.Listening, ConversationState.FollowUpWindow -> "In ascolto"
    ConversationState.FinalizingSpeech, ConversationState.Transcribing -> "Elaboro"
    ConversationState.RetrievingMemory -> "Memoria…"
    ConversationState.Routing -> "Instrado"
    ConversationState.ThinkingLocal -> "Penso"
    ConversationState.ThinkingRemote -> "Penso (PC)"
    ConversationState.AwaitingConfirmation -> "Confermi?"
    ConversationState.ExecutingTool -> "Eseguo"
    ConversationState.Speaking -> "Rispondo"
    ConversationState.BluetoothUnavailable -> "BT assente"
    ConversationState.PermissionRequired -> "Permesso mic"
    ConversationState.ModelUnavailable -> "No modello"
    ConversationState.VaultUnavailable -> "No vault"
    ConversationState.NetworkUnavailable -> "No rete"
    ConversationState.Cancelled -> "Annullato"
    is ConversationState.RecoverableError -> "Errore"
    is ConversationState.FatalError -> "Errore"
}
