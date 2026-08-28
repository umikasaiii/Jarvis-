package com.simone.jarvismobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.LocalJarvisThemeId

/**
 * A Material icon that switches to real reference art on the Rouge theme
 * (§ Impostazioni › Temi), for the handful of icons that have both an obvious
 * match in the user's own reference pack and a clean single call site to swap.
 * Everywhere else keeps the plain vector icon — this never applies on its own.
 *
 * [rouge] is nullable so a call site can opt in only where a matching asset
 * actually exists; passing null always falls back to [icon], on every theme.
 */
@Composable
fun ThemedIcon(
    icon: ImageVector,
    rouge: Int?,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (rouge != null && LocalJarvisThemeId.current == JarvisThemeId.ROUGE) {
        Image(
            painter = painterResource(rouge),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Icon(icon, contentDescription, modifier, tint)
    }
}
