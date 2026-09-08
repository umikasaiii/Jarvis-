package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §G (System Insets +
 * Edge-to-Edge). A single, predictable shared modifier for status bar,
 * navigation bar, display cutout and IME together — [WindowInsets.safeDrawing]
 * already folds all four in, so a SEGNALE composable that is not built
 * directly on [SegnaleScene] (which already applies this by default) still
 * has one place to reach for instead of hand-rolling
 * `WindowInsets.statusBars` + `WindowInsets.navigationBars` + ... itself.
 *
 * Deliberately does not touch predictive back / gesture navigation: Android
 * already provides the correct mechanism there
 * ([androidx.activity.compose.BackHandler], already used by three existing
 * screens per this pass's own pre-audit) and §G explicitly forbids inventing
 * a proprietary replacement.
 */
fun Modifier.segnaleSafeContent(): Modifier = composed {
    this.windowInsetsPadding(WindowInsets.safeDrawing)
}
