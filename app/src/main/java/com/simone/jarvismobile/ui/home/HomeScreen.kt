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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
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
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.ui.components.ReactorOrb

// --- Palette ---------------------------------------------------------------
private val Accent = Color(0xFF4FD1E0)
private val Green = Color(0xFF2ECC71)
private val Blue = Color(0xFF3B9EFF)
private val Amber = Color(0xFFF39C12)
private val Red = Color(0xFFE74C3C)
private val Muted = Color(0xFF6B7A83)
private val Ink = Color(0xFFE6EDF1)
private val Surface = Color(0xFF121D25)
private val SurfaceHi = Color(0xFF16232C)

/** How many recent chat lines to show (full history stays in memory). */
private const val MAX_VISIBLE_MESSAGES = 12

/**
 * JARVIS home — a chat-first dashboard: a reactor-orb push-to-talk hero with a
 * live status pill, a scrolling conversation of chat bubbles, a compact status
 * strip, and a pinned message bar for the written-chat alternative.
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
    val route by viewModel.routeState.collectAsStateWithLifecycle()
    val level by viewModel.micLevel.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val loadedModelName by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val llmLoadState by viewModel.llmLoadState.collectAsStateWithLifecycle()

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

    fun onOrbTap() {
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
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0A1014), Color(0xFF0C161D), Color(0xFF080E12))),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {

            // --- Header (pinned) ------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Accent)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = name.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Accent,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(statusLabel(state), accent)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = Color(0xFFB8C4CC))
                    }
                }
            }

            // --- Orb hero (pinned) ----------------------------------------
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .background(
                            Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)),
                            CircleShape,
                        ),
                )
                ReactorOrb(
                    accent = accent,
                    active = state.isActiveSpeaking(),
                    listening = state == ConversationState.Listening,
                    level = level,
                    modifier = Modifier.fillMaxWidth(0.5f),
                    onClick = { onOrbTap() },
                )
            }

            Text(
                text = hintFor(state, micGranted, error),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null && state.isRestingLike()) Red else Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (state == ConversationState.Listening && partial.isNotEmpty()) {
                Text(
                    text = "“$partial”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            // --- Compact status strip -------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip("Audio", audioLabel(route), if (route.usingBluetoothInput) Green else Accent, Modifier.weight(1f))
                val loading = llmLoadState == LlmLoadState.LOADING
                StatusChip(
                    "Modello",
                    when {
                        loading -> "Carico…"
                        loadedModelName != null -> "Pronto"
                        llmLoadState == LlmLoadState.ERROR -> "Errore"
                        else -> "—"
                    },
                    when {
                        loading -> Amber
                        loadedModelName != null -> Green
                        llmLoadState == LlmLoadState.ERROR -> Red
                        else -> Muted
                    },
                    Modifier.weight(1f),
                )
                val exchanges = messages.count { it.fromUser }
                StatusChip("Memoria", if (exchanges == 0) "—" else "$exchanges", if (exchanges > 0) Green else Muted, Modifier.weight(1f))
            }

            // --- Conversation (scrolls) -----------------------------------
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        text = "Tocca l'orb e parla, oppure scrivi qui sotto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                } else {
                    messages.takeLast(MAX_VISIBLE_MESSAGES).forEach { ChatBubble(it, name) }
                    TextButton(
                        onClick = viewModel::onNewConversation,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Nuova conversazione", color = Amber, fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onOpenModels) { Text("Modelli", color = Accent, fontSize = 13.sp) }
                    TextButton(onClick = onOpenMemory) { Text("Memoria", color = Accent, fontSize = 13.sp) }
                    TextButton(onClick = onOpenDiagnostics) { Text("Diagnostica", color = Accent, fontSize = 13.sp) }
                }
                Text(
                    text = "v${com.simone.jarvismobile.BuildConfig.VERSION_NAME} · build ${com.simone.jarvismobile.BuildConfig.BUILD_ID}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF44525A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }

            // --- Message bar (pinned) -------------------------------------
            MessageBar(
                value = textInput,
                onValueChange = { textInput = it },
                sending = sending,
                onSend = {
                    val msg = textInput.trim()
                    if (msg.isNotEmpty()) {
                        viewModel.onSendText(msg)
                        textInput = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, assistantName: String) {
    val isUser = message.fromUser
    val bubbleColor = if (isUser) Accent.copy(alpha = 0.14f) else SurfaceHi
    val borderColor = (if (isUser) Accent else Green).copy(alpha = 0.28f)
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
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
                text = if (isUser) "TU" else assistantName.uppercase(),
                color = if (isUser) Accent else Green,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(3.dp))
            Text(text = message.text, color = Ink, fontSize = 15.sp)
        }
    }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Surface)
            .border(1.dp, accent.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.size(6.dp))
        Text(text, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusChip(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Text(value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MessageBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && !sending
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Scrivi un messaggio…", color = Muted) },
            enabled = !sending,
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent.copy(alpha = 0.6f),
                unfocusedBorderColor = Color(0xFF27353D),
                focusedContainerColor = Surface,
                unfocusedContainerColor = Surface,
                cursorColor = Accent,
            ),
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (canSend) Accent else Color(0xFF1E2C34))
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
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

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle,
    ConversationState.Cancelled,
    is ConversationState.RecoverableError,
    is ConversationState.FatalError,
    ConversationState.PermissionRequired,
    ConversationState.BluetoothUnavailable,
    ConversationState.ModelUnavailable,
    ConversationState.VaultUnavailable,
    ConversationState.NetworkUnavailable -> true
    else -> false
}

private fun ConversationState.isActiveSpeaking(): Boolean =
    this == ConversationState.Speaking ||
        this == ConversationState.PreparingAudio ||
        this == ConversationState.Listening ||
        this == ConversationState.FollowUpWindow

private fun accentFor(state: ConversationState): Color = when {
    state == ConversationState.Listening -> Green
    state == ConversationState.Speaking -> Blue
    state is ConversationState.RecoverableError || state is ConversationState.FatalError -> Red
    state == ConversationState.Idle -> Accent
    state == ConversationState.Cancelled -> Accent
    else -> Amber
}

private fun audioLabel(route: com.simone.jarvismobile.audio.AudioRouteState): String = when {
    route.airPodsDetected -> "AirPods"
    route.usingBluetoothInput -> "Bluetooth"
    else -> "Telefono"
}

private fun hintFor(state: ConversationState, micGranted: Boolean, error: String?): String = when {
    error != null && state.isRestingLike() -> friendlyError(error)
    !micGranted && state == ConversationState.Idle -> "Tocca l'orb: chiederò il permesso microfono"
    state == ConversationState.Idle -> "Tocca l'orb per parlare"
    state == ConversationState.FollowUpWindow -> "Puoi continuare a parlare"
    else -> statusLabel(state)
}

private fun friendlyError(code: String): String = when (code) {
    "tts_unavailable" -> "Voce italiana offline non disponibile: installala in Impostazioni Android › TTS"
    "audio_begin_failed" -> "Impossibile avviare l'audio (audio_begin_failed)"
    "audio_focus_lost" -> "Audio interrotto da un'altra app (audio_focus_lost)"
    "record_failed", "permission" -> "Microfono non disponibile ($code) — verifica il permesso"
    else -> "Errore tecnico: $code"
}

private fun statusLabel(state: ConversationState): String = when (state) {
    ConversationState.Idle -> "Pronto"
    ConversationState.PreparingAudio -> "Preparazione…"
    ConversationState.Listening -> "In ascolto"
    ConversationState.FinalizingSpeech, ConversationState.Transcribing -> "Elaboro"
    ConversationState.RetrievingMemory -> "Memoria…"
    ConversationState.Routing -> "Instrado"
    ConversationState.ThinkingLocal -> "Penso"
    ConversationState.ThinkingRemote -> "Penso (PC)"
    ConversationState.AwaitingConfirmation -> "Confermi?"
    ConversationState.ExecutingTool -> "Eseguo"
    ConversationState.Speaking -> "Rispondo"
    ConversationState.FollowUpWindow -> "In ascolto"
    ConversationState.BluetoothUnavailable -> "BT assente"
    ConversationState.PermissionRequired -> "Permesso mic"
    ConversationState.ModelUnavailable -> "No modello"
    ConversationState.VaultUnavailable -> "No vault"
    ConversationState.NetworkUnavailable -> "No rete"
    ConversationState.Cancelled -> "Annullato"
    is ConversationState.RecoverableError -> "Errore"
    is ConversationState.FatalError -> "Errore"
}
