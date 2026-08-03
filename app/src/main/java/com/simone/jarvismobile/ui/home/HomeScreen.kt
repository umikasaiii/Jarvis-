package com.simone.jarvismobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.state.ConversationState

/**
 * Home screen: a large push-to-talk button with distinct visual states plus a
 * compact live status strip (docs/ARCHITECTURE.md §7.1/§22). All strings are
 * Italian and the layout is one-handed friendly.
 */
@Composable
fun HomeScreen(
    autoStart: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val route by viewModel.routeState.collectAsStateWithLifecycle()

    LaunchedEffect(autoStart) {
        if (autoStart) viewModel.onTalkPressed()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "JARVIS",
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.primary,
            )

            StatusStrip(
                ready = state == ConversationState.Idle,
                airPods = route.output?.productName?.contains("AirPods", true) == true ||
                    route.input?.productName?.contains("AirPods", true) == true,
                usingBluetooth = route.communicationDeviceApplied,
            )

            Spacer(Modifier.size(8.dp))

            TalkButton(
                state = state,
                onPress = viewModel::onTalkPressed,
                onCancel = viewModel::onCancel,
            )

            Text(
                text = statusLabel(state),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TalkButton(
    state: ConversationState,
    onPress: () -> Unit,
    onCancel: () -> Unit,
) {
    val idle = state == ConversationState.Idle || state.isRestingLike()
    val color = when {
        state == ConversationState.Listening -> Color(0xFF2ECC71)
        state == ConversationState.Speaking -> Color(0xFF3498DB)
        state is ConversationState.RecoverableError ||
            state is ConversationState.FatalError -> Color(0xFFE74C3C)
        idle -> colorScheme.primary
        else -> Color(0xFFF39C12)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(color)
            .clickable { if (idle) onPress() else onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Parla",
            tint = Color.White,
            modifier = Modifier.size(72.dp),
        )
    }
}

@Composable
private fun StatusStrip(ready: Boolean, airPods: Boolean, usingBluetooth: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (ready) "Pronto" else "Attivo")
            Text(if (airPods) "AirPods" else if (usingBluetooth) "Bluetooth" else "Telefono")
        }
    }
}

private fun ConversationState.isRestingLike(): Boolean = when (this) {
    ConversationState.Idle,
    ConversationState.Cancelled,
    is ConversationState.RecoverableError,
    is ConversationState.FatalError -> true
    else -> false
}

private fun statusLabel(state: ConversationState): String = when (state) {
    ConversationState.Idle -> "Premi per parlare"
    ConversationState.PreparingAudio -> "Preparazione audio…"
    ConversationState.Listening -> "In ascolto…"
    ConversationState.FinalizingSpeech, ConversationState.Transcribing -> "Trascrizione…"
    ConversationState.RetrievingMemory -> "Consulto la memoria…"
    ConversationState.Routing -> "Instradamento…"
    ConversationState.ThinkingLocal -> "Sto pensando (locale)…"
    ConversationState.ThinkingRemote -> "Sto pensando (PC)…"
    ConversationState.AwaitingConfirmation -> "Confermi?"
    ConversationState.ExecutingTool -> "Eseguo…"
    ConversationState.Speaking -> "Risposta in corso…"
    ConversationState.FollowUpWindow -> "Puoi continuare…"
    ConversationState.BluetoothUnavailable -> "Bluetooth non disponibile"
    ConversationState.PermissionRequired -> "Permesso microfono necessario"
    ConversationState.ModelUnavailable -> "Modello non caricato"
    ConversationState.VaultUnavailable -> "Vault non disponibile"
    ConversationState.NetworkUnavailable -> "Rete non disponibile"
    ConversationState.Cancelled -> "Annullato"
    is ConversationState.RecoverableError -> "Errore recuperabile"
    is ConversationState.FatalError -> "Errore"
}
