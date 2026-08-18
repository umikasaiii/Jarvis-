package com.simone.jarvismobile.ui.driving.golden

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Debug-only control panel (spec §3/§4: "enable/disable" + "opacity
 * configurabile 0-100%") for [JarvisDriveReferenceOverlay] and
 * [JarvisDriveVisualDebug], plus the [com.simone.jarvismobile.core.driving.golden.JarvisDriveMockState]
 * toggle from spec §7. Collapsed to a single small label by default so it
 * doesn't compete with the real HUD; tapping it expands the three switches.
 * Caller (`DrivingModeScreen`) is responsible for the `BuildConfig.DEBUG` gate.
 */
@Composable
fun JarvisDriveDebugControls(
    mockStateEnabled: Boolean,
    onMockStateToggle: (Boolean) -> Unit,
    referenceOverlayEnabled: Boolean,
    onReferenceOverlayToggle: (Boolean) -> Unit,
    referenceOverlayOpacity: Int,
    onReferenceOverlayOpacityChange: (Int) -> Unit,
    visualDebugEnabled: Boolean,
    onVisualDebugToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(230.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6101418))
            .padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DEBUG GOLDEN LAYOUT", color = Color(0xFF2ECC71), fontSize = 10.sp)
        }
        if (expanded) {
            DebugToggleRow("Mock data", mockStateEnabled, onMockStateToggle)
            DebugToggleRow("Reference overlay", referenceOverlayEnabled, onReferenceOverlayToggle)
            if (referenceOverlayEnabled) {
                Text("Opacity: $referenceOverlayOpacity%", color = Color.White, fontSize = 10.sp)
                Slider(
                    value = referenceOverlayOpacity.toFloat(),
                    onValueChange = { onReferenceOverlayOpacityChange(it.toInt()) },
                    valueRange = 0f..100f,
                )
            }
            DebugToggleRow("Visual debug", visualDebugEnabled, onVisualDebugToggle)
        }
    }
}

@Composable
private fun DebugToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
