package com.simone.jarvismobile.ui.components.segnale

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.simone.jarvismobile.core.segnale.DataStatus
import com.simone.jarvismobile.ui.theme.SegnaleColors
import com.simone.jarvismobile.ui.theme.SegnaleIconSize
import com.simone.jarvismobile.ui.theme.SegnaleSpacing
import com.simone.jarvismobile.ui.theme.SegnaleTypography

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §I (Runtime
 * Presentation / Data Status) + §D (semantic accent).
 *
 * `runtime domain state → presentation mapper → immutable UI presentation
 * state → Composable` (§I) — [DataStatusPresentation] IS that immutable
 * intermediate state; [toPresentation] IS the mapper (a plain function of a
 * typed [DataStatus], never a string/keyword guess); [DataStatusChip] is the
 * only thing that touches Compose. No case here reads from anything but the
 * already-real, already-typed [DataStatus] passed in.
 */
data class DataStatusPresentation(
    val icon: ImageVector,
    val title: String,
    val accent: Color,
    val accessibilityDescription: String,
)

fun DataStatus.toPresentation(): DataStatusPresentation = when (this) {
    DataStatus.SUCCESS_DATA -> DataStatusPresentation(
        icon = Icons.Filled.CheckCircle,
        title = "Dati disponibili",
        accent = SegnaleColors.success,
        accessibilityDescription = "Stato: dati disponibili",
    )
    DataStatus.SUCCESS_EMPTY -> DataStatusPresentation(
        icon = Icons.Filled.Info,
        title = "Nessun dato",
        accent = SegnaleColors.success,
        accessibilityDescription = "Stato: nessun dato, lettura riuscita",
    )
    DataStatus.LOADING -> DataStatusPresentation(
        icon = Icons.Filled.HourglassTop,
        title = "Caricamento",
        accent = SegnaleColors.textSecondary,
        accessibilityDescription = "Stato: caricamento in corso",
    )
    DataStatus.STALE -> DataStatusPresentation(
        icon = Icons.Filled.History,
        title = "Dati non aggiornati",
        accent = SegnaleColors.warning,
        accessibilityDescription = "Stato: dati non aggiornati",
    )
    DataStatus.PERMISSION_MISSING -> DataStatusPresentation(
        icon = Icons.Filled.Lock,
        title = "Permesso mancante",
        accent = SegnaleColors.error,
        accessibilityDescription = "Stato: permesso mancante",
    )
    DataStatus.DATA_UNAVAILABLE -> DataStatusPresentation(
        icon = Icons.Filled.CloudOff,
        title = "Dato non disponibile",
        accent = SegnaleColors.warning,
        accessibilityDescription = "Stato: dato non disponibile",
    )
    DataStatus.SOURCE_FAILURE -> DataStatusPresentation(
        icon = Icons.Filled.ErrorOutline,
        title = "Fonte non raggiungibile",
        accent = SegnaleColors.error,
        accessibilityDescription = "Stato: fonte dati non raggiungibile",
    )
    DataStatus.TOOL_FAILURE -> DataStatusPresentation(
        icon = Icons.Filled.ErrorOutline,
        title = "Operazione non riuscita",
        accent = SegnaleColors.error,
        accessibilityDescription = "Stato: operazione non riuscita",
    )
    DataStatus.PARTIAL -> DataStatusPresentation(
        icon = Icons.Filled.WarningAmber,
        title = "Dati parziali",
        accent = SegnaleColors.warning,
        accessibilityDescription = "Stato: dati parziali",
    )
    DataStatus.OFFLINE -> DataStatusPresentation(
        icon = Icons.Filled.WifiOff,
        title = "Offline",
        accent = SegnaleColors.remote,
        accessibilityDescription = "Stato: dispositivo offline",
    )
}

/**
 * A compact status chip — icon + short title, never a "giant decorative
 * error card" (§I). The icon is decorative (`contentDescription = null` on
 * [Icon] itself — Compose then excludes it from the accessibility tree by
 * construction) and merged into the row's own single semantics node
 * carrying [DataStatusPresentation.accessibilityDescription], so TalkBack
 * announces the status once, not icon-then-title-then-description as three
 * redundant stops (§H — merged semantics where appropriate).
 */
@Composable
fun DataStatusChip(status: DataStatus, modifier: Modifier = Modifier) {
    val presentation = status.toPresentation()
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = presentation.accessibilityDescription
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = presentation.icon,
            contentDescription = null,
            tint = presentation.accent,
            modifier = Modifier.size(SegnaleIconSize.md),
        )
        Spacer(Modifier.width(SegnaleSpacing.xs))
        Text(
            text = presentation.title,
            style = SegnaleTypography.status,
            color = presentation.accent,
        )
    }
}
