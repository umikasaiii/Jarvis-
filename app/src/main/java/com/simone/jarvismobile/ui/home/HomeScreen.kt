package com.simone.jarvismobile.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.ui.components.ReactorOrb
import com.simone.jarvismobile.ui.components.StatusTile

private val JarvisAccent = Color(0xFF4FD1E0)
private val JarvisGreen = Color(0xFF2ECC71)
private val JarvisBlue = Color(0xFF3B9EFF)
private val JarvisAmber = Color(0xFFF39C12)
private val JarvisRed = Color(0xFFE74C3C)

/**
 * JARVIS dashboard: an animated reactor orb push-to-talk control, a live status
 * line, and a grid of honest status tiles. Tapping requests the microphone
 * permission when missing, so the control always does something visible.
 */
@Composable
fun HomeScreen(
    autoStart: Boolean = false,
    onOpenDiagnostics: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val route by viewModel.routeState.collectAsStateWithLifecycle()
    val level by viewModel.micLevel.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val name by viewModel.assistantName.collectAsStateWithLifecycle()
    val diagnostic by viewModel.diagnostic.collectAsStateWithLifecycle()

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
        // Auto-start from the tile / deep link, requesting permission if needed.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (micGranted) viewModel.onTalkPressed() else permissionLauncher.launch(neededPermissions())
        }
    }

    val accent = accentFor(state)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1014), Color(0xFF0E1A22), Color(0xFF0A1014)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top bar.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = JarvisAccent)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = name.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = JarvisAccent,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Impostazioni", tint = Color(0xFFB8C4CC))
                }
            }

            Spacer(Modifier.height(6.dp))

            ReactorOrb(
                accent = accent,
                active = state.isActiveSpeaking(),
                listening = state == ConversationState.Listening,
                level = level,
                modifier = Modifier.fillMaxWidth(0.72f),
                onClick = { onOrbTap() },
            )

            Text(
                text = statusLabel(state),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE4EAEE),
                textAlign = TextAlign.Center,
            )
            val showingError = error != null && state.isRestingLike()
            Text(
                text = hintFor(state, micGranted, error),
                style = MaterialTheme.typography.bodyMedium,
                color = if (showingError) JarvisRed else Color(0xFF8A99A3),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            // Status grid — honest values (unimplemented subsystems show "non attivo").
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusTile("Audio", audioLabel(route), accent = if (route.usingBluetoothInput) JarvisGreen else JarvisAccent, modifier = Modifier.weight(1f))
                StatusTile("Microfono", "${(level * 100).toInt()}%", accent = JarvisAccent, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusTile("Voce", if (route.airPodsDetected) "AirPods" else "Sistema", accent = JarvisBlue, modifier = Modifier.weight(1f))
                StatusTile("Modalità", "Offline-first", accent = JarvisAccent, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusTile("Modello", "Non caricato", accent = Color(0xFF6B7A83), modifier = Modifier.weight(1f))
                StatusTile("Memoria", "Non attiva", accent = Color(0xFF6B7A83), modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusTile("PC", "Non configurato", accent = Color(0xFF6B7A83), modifier = Modifier.weight(1f))
                StatusTile("Home Assistant", "Non configurato", accent = Color(0xFF6B7A83), modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(4.dp))
            if (diagnostic.isNotEmpty()) {
                Text(
                    text = "· $diagnostic",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6E7C85),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            TextButton(onClick = onOpenDiagnostics) { Text("Diagnostica", color = JarvisAccent) }

            Text(
                text = "v${com.simone.jarvismobile.BuildConfig.VERSION_NAME} · build ${com.simone.jarvismobile.BuildConfig.BUILD_ID}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5A666E),
            )
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
    state == ConversationState.Listening -> JarvisGreen
    state == ConversationState.Speaking -> JarvisBlue
    state is ConversationState.RecoverableError || state is ConversationState.FatalError -> JarvisRed
    state == ConversationState.Idle -> JarvisAccent
    state == ConversationState.Cancelled -> JarvisAccent
    else -> JarvisAmber
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
    else -> ""
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
    ConversationState.PreparingAudio -> "Preparazione audio…"
    ConversationState.Listening -> "In ascolto…"
    ConversationState.FinalizingSpeech, ConversationState.Transcribing -> "Elaborazione…"
    ConversationState.RetrievingMemory -> "Consulto la memoria…"
    ConversationState.Routing -> "Instradamento…"
    ConversationState.ThinkingLocal -> "Sto pensando (locale)…"
    ConversationState.ThinkingRemote -> "Sto pensando (PC)…"
    ConversationState.AwaitingConfirmation -> "Confermi?"
    ConversationState.ExecutingTool -> "Eseguo…"
    ConversationState.Speaking -> "Risposta in corso…"
    ConversationState.FollowUpWindow -> "In ascolto…"
    ConversationState.BluetoothUnavailable -> "Bluetooth non disponibile"
    ConversationState.PermissionRequired -> "Permesso microfono necessario"
    ConversationState.ModelUnavailable -> "Modello non caricato"
    ConversationState.VaultUnavailable -> "Vault non disponibile"
    ConversationState.NetworkUnavailable -> "Rete non disponibile"
    ConversationState.Cancelled -> "Annullato"
    is ConversationState.RecoverableError -> "Errore recuperabile"
    is ConversationState.FatalError -> "Errore"
}
