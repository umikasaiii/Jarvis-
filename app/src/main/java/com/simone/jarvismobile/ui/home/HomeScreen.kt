package com.simone.jarvismobile.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
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
    autoStart: Boolean = false,
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
        if (state.isRestingLike()) {
            if (micGranted) viewModel.onTalkPressed() else permissionLauncher.launch(neededPermissions())
        } else {
            viewModel.onCancel()
        }
    }

    if (autoStart) {
        LaunchedEffect(Unit) {
            if (micGranted) viewModel.onTalkPressed() else permissionLauncher.launch(neededPermissions())
        }
    }

    val accent = accentFor(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF05101A), Color(0xFF0A1622), Color(0xFF040B12)))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
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
                    Text(name.uppercase(), color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    TextButton(onClick = viewModel::onNewConversation) { Text("Nuova", color = Amber, fontSize = 12.sp) }
                }
                TextButton(onClick = onOpenModels) { Text("Modelli", color = Accent, fontSize = 12.sp) }
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
                    enabled = true,
                ) {
                    Icon(
                        if (talking) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (talking) "Ferma" else "Parla",
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
                        focusedBorderColor = Accent.copy(alpha = 0.7f),
                        unfocusedBorderColor = Color(0xFF27353D),
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        cursorColor = Accent,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                    ),
                )

                val canSend = textInput.isNotBlank() && !sending
                RoundButton(
                    color = if (canSend) Accent else Color(0xFF1E2C34),
                    onClick = {
                        val msg = textInput.trim()
                        if (msg.isNotEmpty()) { viewModel.onSendText(msg); textInput = "" }
                    },
                    enabled = canSend,
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Invia",
                            tint = if (canSend) Color(0xFF07131A) else Muted,
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
    val bubbleColor = if (isUser) Accent.copy(alpha = 0.15f) else Color(0xFF14232E)
    val borderColor = (if (isUser) Accent else Green).copy(alpha = 0.3f)
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                if (isUser) "TU" else assistantName.uppercase(),
                color = if (isUser) Accent else Green,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(3.dp))
            Text(message.text, color = Ink, fontSize = 15.sp)
        }
    }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Surface)
            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.size(6.dp))
        Text(text, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
