package com.simone.jarvismobile.ui.automation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF9DB0BC)
// The brand accent (§ Impostazioni › Temi).
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
private val Warn = Color(0xFFF3A65B)

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
    onOpenAdvanced: () -> Unit = {},
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
        Text("Automazioni", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(
            "Regole che JARVIS esegue da solo. Scrivile come le diresti a voce.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
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

        EventBuilderCard(viewModel)

        if (message.isNotBlank()) {
            val ok = message.startsWith("Creata") || message.startsWith("Eliminata") ||
                message.startsWith("Eseguita")
            Text(message, style = MaterialTheme.typography.bodyMedium, color = if (ok) Cyan else Warn)
        }

        if (automations.isEmpty()) {
            Text(
                "Nessuna automazione. Le regole vengono salvate in " +
                    "JARVIS/Automazioni.md e restano leggibili in Obsidian.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
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

        OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
            Text("Regole avanzate (orario e luogo) →")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
    }
}

/** The event a device automation reacts to. Some carry a parameter (time/device/percent). */
private enum class EventKind(val label: String) {
    SCREEN_UNLOCK("Allo sblocco del telefono"),
    MORNING("Primo sblocco al mattino"),
    HEADPHONES("Quando collego le cuffie"),
    BLUETOOTH("Quando si connette un Bluetooth"),
    AIRPLANE_ON("Modalità aereo attivata"),
    AIRPLANE_OFF("Modalità aereo disattivata"),
    WIFI_ON("Wi-Fi acceso"),
    WIFI_OFF("Wi-Fi spento"),
    DATA_ON("Dati mobili attivati"),
    DATA_OFF("Dati mobili disattivati"),
    BATTERY("Batteria sotto una soglia"),
    CHARGING("Quando metto in carica"),
}

private enum class ActionKind(val label: String) {
    NOTIFY("Notifica"), SPEAK("A voce"), AGENDA("In agenda"), TOOL("Comando"),
}

/**
 * Structured builder for device-event automations (choose event → action). Those
 * events have no spoken grammar, so they are picked here rather than typed. The
 * rule still lands in `Automazioni.md` and fires only while the "Automazioni
 * attive" service is on (Impostazioni).
 */
@Composable
private fun EventBuilderCard(viewModel: AutomationsViewModel) {
    var event by remember { mutableStateOf(EventKind.SCREEN_UNLOCK) }
    var eventMenu by remember { mutableStateOf(false) }
    var actionKind by remember { mutableStateOf(ActionKind.NOTIFY) }
    var actionText by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var morningHour by remember { mutableStateOf(7f) }
    var percent by remember { mutableStateOf(20f) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Crea per evento del telefono", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                "Per eventi come sblocco, cuffie, aereo o Wi-Fi. Richiede «Automazioni attive» " +
                    "in Impostazioni per scattare ad app chiusa.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )

            Text("Quando…", style = MaterialTheme.typography.titleSmall)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { eventMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(event.label)
                }
                DropdownMenu(expanded = eventMenu, onDismissRequest = { eventMenu = false }) {
                    EventKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { event = option; eventMenu = false },
                        )
                    }
                }
            }

            when (event) {
                EventKind.MORNING -> {
                    Text("Non prima delle %02d:00".format(morningHour.toInt()))
                    Slider(
                        value = morningHour,
                        onValueChange = { morningHour = it },
                        valueRange = 4f..12f,
                        steps = 7,
                    )
                }
                EventKind.BLUETOOTH -> OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text("Nome del dispositivo (vuoto = qualsiasi)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EventKind.BATTERY -> {
                    Text("Sotto il ${percent.toInt()}%")
                    Slider(
                        value = percent,
                        onValueChange = { percent = it },
                        valueRange = 5f..95f,
                    )
                }
                else -> Unit
            }

            Text("…fai", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionKind.entries.forEach { kind ->
                    OutlinedButton(onClick = { actionKind = kind }) {
                        Text(if (actionKind == kind) "● ${kind.label}" else kind.label)
                    }
                }
            }
            OutlinedTextField(
                value = actionText,
                onValueChange = { actionText = it },
                label = { Text(if (actionKind == ActionKind.TOOL) "Comando (es. accendi la torcia)" else "Testo") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )

            val ready = actionText.isNotBlank()
            Button(
                onClick = {
                    viewModel.createBuilt(
                        buildTrigger(event, morningHour.toInt(), device, percent.toInt()),
                        buildAction(actionKind, actionText.trim()),
                    )
                    actionText = ""
                    device = ""
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crea automazione") }
        }
    }
}

private fun buildTrigger(event: EventKind, morningHour: Int, device: String, percent: Int): Trigger =
    when (event) {
        EventKind.SCREEN_UNLOCK -> Trigger.ScreenUnlocked
        EventKind.MORNING -> Trigger.MorningUnlock(java.time.LocalTime.of(morningHour.coerceIn(0, 23), 0))
        EventKind.HEADPHONES -> Trigger.HeadphonesConnected
        EventKind.BLUETOOTH -> Trigger.BluetoothConnected(device.trim())
        EventKind.AIRPLANE_ON -> Trigger.AirplaneMode(on = true)
        EventKind.AIRPLANE_OFF -> Trigger.AirplaneMode(on = false)
        EventKind.WIFI_ON -> Trigger.WifiPower(on = true)
        EventKind.WIFI_OFF -> Trigger.WifiPower(on = false)
        EventKind.DATA_ON -> Trigger.MobileData(on = true)
        EventKind.DATA_OFF -> Trigger.MobileData(on = false)
        EventKind.BATTERY -> Trigger.BatteryBelow(percent.coerceIn(1, 99))
        EventKind.CHARGING -> Trigger.ChargingStarted
    }

private fun buildAction(kind: ActionKind, text: String): Action = when (kind) {
    ActionKind.NOTIFY -> Action.Notify(text)
    ActionKind.SPEAK -> Action.Speak(text)
    ActionKind.AGENDA -> Action.AddAgenda(text)
    ActionKind.TOOL -> Action.Tool(text)
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
    is Trigger.MorningUnlock ->
        "Primo sblocco dopo le %02d:%02d".format(trigger.after.hour, trigger.after.minute)
    Trigger.ScreenUnlocked -> "Allo sblocco del telefono"
    Trigger.HeadphonesConnected -> "Quando collego le cuffie"
    is Trigger.BluetoothConnected -> "Quando si connette ${trigger.device}"
    is Trigger.AirplaneMode -> "Modalità aereo ${if (trigger.on) "attivata" else "disattivata"}"
    is Trigger.WifiPower -> "Wi-Fi ${if (trigger.on) "acceso" else "spento"}"
    is Trigger.MobileData -> "Dati mobili ${if (trigger.on) "attivati" else "disattivati"}"
    is Trigger.WifiNetwork -> "Connesso alla rete «${trigger.ssid}»"
    Trigger.ArrivedHome -> "Quando arrivo a casa"
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
