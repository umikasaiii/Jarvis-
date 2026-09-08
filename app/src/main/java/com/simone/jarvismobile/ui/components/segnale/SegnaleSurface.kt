package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.simone.jarvismobile.ui.theme.SegnaleColors
import com.simone.jarvismobile.ui.theme.SegnaleElevationLevel
import com.simone.jarvismobile.ui.theme.SegnaleShapes
import com.simone.jarvismobile.ui.theme.SegnaleSpacing
import com.simone.jarvismobile.ui.theme.SegnaleStroke

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §F (Scene/Surface
 * Foundation, layer 2: structural surfaces) + §D (semantic border
 * treatment).
 *
 * [SegnaleBorderState] is the "semantic border treatment" §D requires — a
 * caller states MEANING (inactive/control/active), never picks a raw color,
 * so [SegnaleColors.borderActive] (localized red energy, §C) cannot leak
 * onto a border by accident the way a bare `Color` parameter would invite.
 */
enum class SegnaleBorderState { NONE, INACTIVE, CONTROL, ACTIVE }

private fun SegnaleBorderState.color(): Color? = when (this) {
    SegnaleBorderState.NONE -> null
    SegnaleBorderState.INACTIVE -> SegnaleColors.borderInactive
    SegnaleBorderState.CONTROL -> SegnaleColors.borderControl
    SegnaleBorderState.ACTIVE -> SegnaleColors.borderActive
}

private fun SegnaleBorderState.width() = when (this) {
    SegnaleBorderState.NONE -> SegnaleStroke.hairline
    SegnaleBorderState.INACTIVE -> SegnaleStroke.hairline
    SegnaleBorderState.CONTROL -> SegnaleStroke.control
    SegnaleBorderState.ACTIVE -> SegnaleStroke.active
}

/**
 * A structural surface: [SegnaleColors.surface] (or [SegnaleColors.elevated]
 * for [SegnaleElevationLevel.RAISED]/[SegnaleElevationLevel.OVERLAY]) with a
 * semantic border. This is the one reusable card/panel primitive P0
 * introduces — §M defers `PanelFrame`/`MetricPanel`/etc. to P1, this is
 * deliberately smaller and generic enough to underlie any of them later
 * without becoming one of them now.
 */
@Composable
fun SegnaleSurface(
    modifier: Modifier = Modifier,
    elevation: SegnaleElevationLevel = SegnaleElevationLevel.BASE,
    border: SegnaleBorderState = SegnaleBorderState.INACTIVE,
    shape: RoundedCornerShape = SegnaleShapes.surfaceMedium,
    contentPadding: androidx.compose.ui.unit.Dp = SegnaleSpacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = when (elevation) {
        SegnaleElevationLevel.BASE -> SegnaleColors.surface
        SegnaleElevationLevel.RAISED, SegnaleElevationLevel.OVERLAY -> SegnaleColors.elevated
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .let { m ->
                val color = border.color()
                if (color != null) m.border(border.width(), color, shape) else m
            }
            .padding(contentPadding),
        content = content,
    )
}
