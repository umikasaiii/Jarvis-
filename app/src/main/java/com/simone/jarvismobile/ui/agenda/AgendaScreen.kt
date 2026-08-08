package com.simone.jarvismobile.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import java.time.LocalDate

private val Cyan = Color(0xFF3FD8F0)
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)

/** Today is aqua-green, tomorrow deep blue, anything later stays muted. */
private val Today = Color(0xFF4FE3C1)
private val Tomorrow = Color(0xFF2C5BFF)
private val Later = Color(0xFF6B7C87)

private fun dotFor(date: LocalDate?, today: LocalDate): Color = when (date) {
    today -> Today
    today.plusDays(1) -> Tomorrow
    else -> Later
}

/**
 * Everything on the calendar, grouped by day.
 *
 * The point of the screen is the dot: tapping it completes the item on the spot.
 * Completing a reminder is the most frequent thing anyone does with one, and it
 * should not require opening a menu or dictating a sentence.
 */
@Composable
fun AgendaScreen(viewModel: AgendaViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    // Open items first, chronological; done ones sink to the bottom of their day.
    // Undated tasks (no due date) group last, under "Senza data".
    val groups: List<Pair<LocalDate?, List<AgendaEntry>>> = entries
        .groupBy { it.date }
        .toList()
        .sortedWith(compareBy(nullsLast<LocalDate>()) { it.first })
        .filter { (date, dayEntries) ->
            date == null || !date.isBefore(today) || dayEntries.any { !it.done }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))),
            )
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.size(16.dp))
        Text("Attività", style = MaterialTheme.typography.headlineSmall, color = Cyan)
        Text(
            "Tocca il pallino per segnare un'attività come completata.",
            color = Muted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.size(12.dp))

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nessuna attività.\nDì «ricordami di … domani alle 15».",
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (date, dayEntries) ->
                item(key = "h-$date") {
                    Text(
                        Agenda.humanDate(date, today).replaceFirstChar { it.uppercase() },
                        color = dotFor(date, today),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
                items(
                    items = dayEntries.sortedWith(compareBy({ it.done }, { it.time })),
                    key = { it.id },
                ) { entry ->
                    EntryRow(
                        entry = entry,
                        dot = dotFor(entry.date, today),
                        onToggle = { viewModel.toggle(entry) },
                    )
                }
            }
            item("tail") { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun EntryRow(entry: AgendaEntry, dot: Color, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Color(0x660A1826))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot IS the control. 40dp of touch target around a 16dp mark, so it
        // is comfortably tappable without a big checkbox dominating the row.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.done) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(dot),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Segna come da fare",
                        tint = Color(0xFF04121A),
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(2.dp, dot, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.text,
                color = if (entry.done) Muted else Ink,
                fontSize = 15.sp,
                textDecoration = if (entry.done) TextDecoration.LineThrough else null,
            )
            val time = entry.time?.let { Agenda.humanTime(it) }
            val alerts = entry.alerts.size
            val sub = listOfNotNull(
                time,
                if (alerts > 0) "$alerts avviso${if (alerts > 1) "" else ""}" else null,
            ).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 11.sp)
        }
    }
}
