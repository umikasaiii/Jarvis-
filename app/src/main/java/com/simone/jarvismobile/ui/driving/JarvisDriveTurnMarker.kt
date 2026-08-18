package com.simone.jarvismobile.ui.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simone.jarvismobile.core.driving.golden.JarvisDriveGoldenLayout
import com.simone.jarvismobile.core.navigation.ManeuverType
import com.simone.jarvismobile.ui.navigation.maneuverGlyph

/**
 * The small ring-and-arrow marker the golden reference draws directly on the
 * route at the point of the upcoming turn (`docs/design/jarvis_drive_reference.png`,
 * the dark circle with a white turn arrow sitting on the blue route line,
 * above and separate from the vehicle marker further down). No such marker
 * exists anywhere in the app today — [VehiclePuck] only ever represents the
 * car's own position, never a point further up the route.
 *
 * Positioning is deliberately out of scope here: placing this accurately
 * needs the maneuver's lat/lon projected to a screen offset through
 * MapLibre, the same kind of projection [com.simone.jarvismobile.ui.navigation.JarvisMapView]
 * already does for the vehicle puck's FREE-mode position — real navigation
 * code this pass was told not to touch. This composable is the reusable
 * visual piece [JarvisDriveGoldenLayout.TurnMarkerCenter] describes; wiring
 * it to a real screen coordinate is separate, later work.
 */
@Composable
fun JarvisDriveTurnMarker(maneuverType: ManeuverType?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(DrivingSportColors.PanelStrong)
            .border(1.5.dp, JarvisDriveBrushes.EdgeSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(maneuverGlyph(maneuverType), color = Color.White, fontSize = 22.sp)
    }
}
