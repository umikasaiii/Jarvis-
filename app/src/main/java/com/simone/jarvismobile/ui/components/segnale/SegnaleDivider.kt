package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.simone.jarvismobile.ui.theme.SegnaleColors
import com.simone.jarvismobile.ui.theme.SegnaleStroke

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §M — the border
 * primitive named alongside `SegnaleSurface`. A purely decorative hairline;
 * explicitly excluded from the accessibility tree (§H: "Decorative Canvas
 * elements must not become noisy TalkBack nodes" — the same principle
 * applies to any purely decorative element, Canvas or not) via
 * `clearAndSetSemantics {}`, which publishes an empty semantics node
 * instead of none — the reliable way to guarantee TalkBack never stops on
 * it, since an ordinary `Box` with no semantics modifier can still be
 * merged into a parent's traversal in some layouts.
 */
@Composable
fun SegnaleDivider(
    modifier: Modifier = Modifier,
    state: SegnaleBorderState = SegnaleBorderState.INACTIVE,
) {
    if (state == SegnaleBorderState.NONE) return
    val color = when (state) {
        SegnaleBorderState.NONE -> return
        SegnaleBorderState.INACTIVE -> SegnaleColors.borderInactive
        SegnaleBorderState.CONTROL -> SegnaleColors.borderControl
        SegnaleBorderState.ACTIVE -> SegnaleColors.borderActive
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SegnaleStroke.hairline)
            .clearAndSetSemantics {}
            .background(color),
    )
}
