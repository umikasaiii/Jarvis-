package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.simone.jarvismobile.core.navigation.Place
import com.simone.jarvismobile.core.navigation.PlaceHit
import com.simone.jarvismobile.ui.navigation.formatDistance
import kotlinx.coroutines.delay

/**
 * The "Places" entry point (spec §11): offline destination search, exactly the
 * same [com.simone.jarvismobile.navigation.PlaceSearchRepository] the voice
 * pipeline already uses via [DrivingModeViewModel.searchDestinations] — no
 * second search implementation. A stopped vehicle gets the full text field and
 * a short result list; a moving one is pointed at the microphone instead, per
 * spec §11 ("durante guida: limitare digitazione, preferire voce").
 */
@Composable
fun DestinationSearchSheet(
    stationary: Boolean,
    onSearch: suspend (String) -> List<PlaceHit>,
    onSelect: (Place) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300) // debounce: don't re-query the index on every keystroke
        loading = true
        results = runCatching { onSearch(query) }.getOrDefault(emptyList())
        loading = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(DrivingSportColors.Bg.copy(alpha = 0.92f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(JarvisDriveDimensions.ScreenMargin)
                .clip(JarvisDriveShapes.Card)
                .background(DrivingSportColors.PanelStrong)
                .border(JarvisDriveDimensions.HairlineWidth, DrivingSportColors.LineStrong, JarvisDriveShapes.Card)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}, // swallow taps so they don't fall through to the scrim's onClose
                )
                .padding(JarvisDriveDimensions.CardPadding),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cerca destinazione", color = DrivingSportColors.TextMain, style = JarvisDriveTypography.CardTitle)
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Chiudi", tint = DrivingSportColors.Muted)
                }
            }

            if (stationary) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Piazza Venezia, farmacia, casa…", color = DrivingSportColors.Muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DrivingSportColors.TextMain,
                        unfocusedTextColor = DrivingSportColors.TextMain,
                        focusedBorderColor = DrivingSportColors.Accent,
                        unfocusedBorderColor = DrivingSportColors.LineStrong,
                        cursorColor = DrivingSportColors.Accent,
                    ),
                )
            } else {
                Text(
                    "In movimento: usa il microfono per cercare a voce " +
                        "(\"portami a…\", \"farmacia\", \"casa\").",
                    color = DrivingSportColors.Muted,
                    style = JarvisDriveTypography.Body,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = DrivingSportColors.Accent,
                    trackColor = DrivingSportColors.Line,
                )
            } else if (results.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    results.forEach { hit -> DestinationResultRow(hit) { onSelect(hit.place); onClose() } }
                }
            } else if (query.isNotBlank()) {
                Text(
                    "Nessun risultato nelle mappe offline installate.",
                    color = DrivingSportColors.Muted,
                    style = JarvisDriveTypography.Body,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DestinationResultRow(hit: PlaceHit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(hit.place.name, color = DrivingSportColors.TextMain, style = JarvisDriveTypography.Body)
        val subtitle = buildList {
            if (hit.place.address.isNotBlank()) add(hit.place.address)
            hit.distanceMeters?.let { add(formatDistance(it)) }
        }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = DrivingSportColors.Muted, style = JarvisDriveTypography.CardLabel)
        }
    }
}
