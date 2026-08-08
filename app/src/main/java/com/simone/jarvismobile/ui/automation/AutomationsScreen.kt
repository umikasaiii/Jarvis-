package com.simone.jarvismobile.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.automation.Action
import com.simone.jarvismobile.core.automation.Automation
import com.simone.jarvismobile.core.automation.AutomationCodec
import com.simone.jarvismobile.core.automation.Trigger

/**
 * Customisable automations.
 *
 * Rules are written as sentences, not built from four dropdowns: the parser that
 * reads them is the same one the chat uses, so there is one grammar to learn.
 * The list underneath is the file in `JARVIS/Automazioni.md`, which the user can
 * also edit in Obsidian.
 */
@Composable
fun AutomationsScreen(
    onBack: () -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val automations by viewModel.automations.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var phrase by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Automazioni", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Regole che JARVIS esegue da solo. Scrivile come le diresti a voce.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it; viewModel.clearMessage() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nuova regola") },
                    placeholder = { Text("ogni giorno alle 8 ricordami di prendere le vitamine") },
                    maxLines = 3,
                )
                Button(
                    onClick = { viewModel.create(phrase); phrase = "" },
                    enabled = phrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Crea regola") }
                Text(
                    "Esempi: «ogni lunedì e venerdì alle 19:30 dimmi di andare in " +
                        "palestra» · «quando la batteria scende sotto il 20% avvisami» · " +
                        "«quando metto in carica dimmi buonanotte» · " +
                        "«alle 11.45 accendi la torcia»",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        if (automations.isEmpty()) {
            Text(
                "Nessuna automazione. Le regole vengono salvate in " +
                    "JARVIS/Automazioni.md e restano leggibili in Obsidian.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            automations.forEach { automation ->
                AutomationCard(
                    automation = automation,
                    onToggle = { viewModel.toggle(automation) },
                    onDelete = { viewModel.delete(automation) },
                    onTest = { viewModel.testRun(automation) },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Come funzionano", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    "Le regole a orario vengono pianificate dal sistema. Le regole su " +
                        "batteria e caricabatterie vengono valutate quando Android " +
                        "segnala il cambio di alimentazione: una soglia personalizzata " +
                        "scatta in quel momento, non all'istante esatto in cui la " +
                        "percentuale cambia — Android non offre un evento per ogni punto.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Un'automazione può solo fare ciò che potresti chiedere tu: una " +
                        "notifica, una frase detta a voce, una voce in agenda, oppure un " +
                        "comando del telefono (torcia, riproduzione) a un orario preciso. " +
                        "Il comando passa dallo stesso elenco consentito dei comandi a voce " +
                        "e viene ricontrollato quando scatta. Niente microfono in " +
                        "background, nessun permesso aggiuntivo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(triggerLabel(automation.trigger), style = MaterialTheme.typography.titleSmall)
                    Text(actionLabel(automation.action), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = automation.enabled, onCheckedChange = { onToggle() })
            }
            automation.lastFired?.let {
                Text(
                    "Ultima esecuzione: ${it.toLocalDate()} ${"%02d:%02d".format(it.hour, it.minute)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(AutomationCodec.describe(automation), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest) { Text("Prova adesso") }
                OutlinedButton(onClick = onDelete) { Text("Elimina") }
            }
        }
    }
}

private fun triggerLabel(trigger: Trigger): String = when (trigger) {
    is Trigger.TimeOfDay -> {
        val clock = "%02d:%02d".format(trigger.time.hour, trigger.time.minute)
        if (trigger.days.isEmpty()) {
            "Ogni giorno alle $clock"
        } else {
            val days = trigger.days.sortedBy { it.value }.joinToString(", ") { DAY_LABELS[it.value - 1] }
            "$days alle $clock"
        }
    }
    is Trigger.Once -> {
        val clock = "%02d:%02d".format(trigger.at.hour, trigger.at.minute)
        val date = trigger.at.toLocalDate()
        "Una volta il ${date.dayOfMonth} ${MONTH_LABELS[date.monthValue - 1]} alle $clock"
    }
    is Trigger.BatteryBelow -> "Batteria sotto il ${trigger.percent}%"
    Trigger.ChargingStarted -> "Quando metto in carica"
}

private fun actionLabel(action: Action): String = when (action) {
    is Action.Notify -> "Notifica: ${action.payload}"
    is Action.Speak -> "A voce: ${action.payload}"
    is Action.AddAgenda -> "In agenda: ${action.payload}"
    is Action.Tool -> "Comando: ${action.payload}"
}

private val MONTH_LABELS = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

private val DAY_LABELS = listOf(
    "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica",
)
