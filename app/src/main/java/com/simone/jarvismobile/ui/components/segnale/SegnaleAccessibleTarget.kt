package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.simone.jarvismobile.ui.theme.SegnaleTouchTarget

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §H (Accessibility
 * Foundation) + §D (minimum interaction targets).
 *
 * Enforces the minimum interaction size (§D — [SegnaleTouchTarget.standard]
 * by default) AND exposes the action's MEANING to TalkBack, never its
 * visual shape (§H's own example: "Avvia conversazione", never "Pulsante
 * cerchio rosso") — [actionDescription] becomes the accessibility action
 * label via `clickable`'s `onClickLabel`, and [Role.Button] tells TalkBack
 * this is an activatable control rather than static text.
 *
 * Uses `Modifier.clickable`'s no-interaction-source overload deliberately —
 * it already manages its own `MutableInteractionSource`/ripple internally
 * (`composed` under the hood), so this stays a plain, non-`@Composable`
 * `Modifier` extension usable from any modifier chain, the same shape as
 * every other modifier in this file.
 */
fun Modifier.segnaleActionTarget(
    actionDescription: String,
    minimumSize: Dp = SegnaleTouchTarget.standard,
    onClick: () -> Unit,
): Modifier = this
    .sizeIn(minWidth = minimumSize, minHeight = minimumSize)
    .clickable(onClickLabel = actionDescription, role = Role.Button, onClick = onClick)
