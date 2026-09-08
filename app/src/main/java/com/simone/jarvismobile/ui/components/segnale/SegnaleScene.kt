package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.simone.jarvismobile.ui.theme.SegnaleColors

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §F (Scene/Surface
 * Foundation, layer 1: background) + §G (insets).
 *
 * P0 scope only: a flat [SegnaleColors.background] fill. Deliberately no
 * live blur, particles, shader noise, AGSL, animated gradients or
 * decorative circuitry (§F/§O — none of those belong in P0).
 *
 * [applySafeContentInsets] defaults to `true` so a screen built directly on
 * [SegnaleScene] never has to hand-roll `WindowInsets` itself (§G: "Do not
 * make every screen manually calculate WindowInsets. Prefer reusable
 * Compose-native primitives/modifiers") — [WindowInsets.safeDrawing]
 * already folds in status bar, navigation bar, display cutout and IME, so
 * one flag covers all four. Set it to `false` for a screen that wants to
 * paint edge-to-edge itself and apply insets only to specific children
 * (the existing `windowInsetsPadding(WindowInsets.statusBars)` pattern
 * already used by six screens is untouched by this — §U, no existing
 * screen's insets handling changes).
 */
@Composable
fun SegnaleScene(
    modifier: Modifier = Modifier,
    applySafeContentInsets: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SegnaleColors.background)
            .let { if (applySafeContentInsets) it.windowInsetsPadding(WindowInsets.safeDrawing) else it },
        content = content,
    )
}
