package com.simone.jarvismobile.ui.automation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.automation.rule.AutomationPlaceEntity
import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.AutomationRule
import com.simone.jarvismobile.core.automation.rule.Condition
import com.simone.jarvismobile.core.automation.rule.TriggerRegistry
import com.simone.jarvismobile.core.automation.rule.TriggerSpec
import com.simone.jarvismobile.core.mode.JarvisModes
import java.time.DayOfWeek

/**
 * The generic engine's screen: save a place, build a clock or place rule, watch
 * it in the list. This is what makes phases 5-6 tap-testable — everything above
 * it is plumbing behind [com.simone.jarvismobile.automation.rule.AutomationExecutor].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.savedRules.collectAsStateWithLifecycle()
    val places by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val parking by viewModel.savedParking.collectAsStateWithLifecycle()
    val executions by viewModel.recentExecutions.collectAsStateWithLifecycle()
    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val weatherEnabled by viewModel.weatherEnabled.collectAsStateWithLifecycle()
    val weatherPlaceId by viewModel.weatherPlaceId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var permTick by remember { mutableStateOf(0) }
    val foreground = remember(permTick) { viewModel.hasForegroundLocation() }
    val background = remember(permTick) { viewModel.hasBackgroundLocation() }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permTick++; viewModel.onLocationPermissionChanged() }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permTick++; viewModel.onLocationPermissionChanged() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Regole avanzate", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Il nuovo motore: regole a orario e a luogo, eseguite da JARVIS. " +
                "Le regole a luogo restano spente finché non concedi la posizione «sempre».",
            style = MaterialTheme.typography.bodySmall,
        )

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        // --- Modalità ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val currentLabel = JarvisModes.profile(currentMode)?.label ?: currentMode
                Text("Modalità", style = MaterialTheme.typography.titleMedium)
                Text("Attuale: $currentLabel", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JarvisModes.all.forEach { profile ->
                        FilterChip(
                            selected = currentMode.equals(profile.id, ignoreCase = true),
                            onClick = { viewModel.switchMode(profile.id) },
                            label = { Text(profile.label) },
                        )
                    }
                }
                if (!viewModel.hasDndAccess()) {
                    Text(
                        "La modalità Notte silenzia le notifiche tenendo sveglie e chiamate " +
                            "prioritarie: serve l'accesso «Non disturbare».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Consenti «Non disturbare»") }
                }
            }
        }

        // --- Meteo ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Meteo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "L'unica funzione che usa la rete di sua iniziativa. Se " +
                                "presente, invia solo la posizione arrotondata (circa 1 " +
                                "km) a un servizio meteo senza account; se assente, le " +
                                "regole «se piove» restano semplicemente spente, mai " +
                                "indovinate.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = weatherEnabled, onCheckedChange = { viewModel.setWeatherEnabled(it) })
                }
                if (weatherEnabled) {
                    HorizontalDivider()
                    Text(
                        "Luogo da controllare",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Senza un luogo scelto usa l'ultima posizione GPS nota, che " +
                            "può essere vecchia o imprecisa. Scegline uno tra i " +
                            "luoghi salvati per un meteo affidabile ogni sera.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = weatherPlaceId.isBlank(),
                            onClick = { viewModel.setWeatherPlaceId("") },
                            label = { Text("Posizione GPS") },
                        )
                        places.forEach { place ->
                            FilterChip(
                                selected = weatherPlaceId == place.id,
                                onClick = { viewModel.setWeatherPlaceId(place.id) },
                                label = { Text(place.displayName) },
                            )
                        }
                    }
                    if (places.isEmpty()) {
                        Text(
                            "Nessun luogo salvato ancora: salvane uno qui sotto per poterlo scegliere.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // --- Luoghi ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Luoghi", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                if (!foreground) {
                    Text(
                        "Per salvare un luogo serve il permesso di posizione.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            foregroundLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Consenti posizione") }
                } else {
                    PlaceCapture(onSave = { name, radius -> viewModel.captureCurrentPlace(name, radius) })
                    if (!background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Text(
                            "Perché una regola a luogo scatti con l'app chiusa, Android chiede «Consenti sempre».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Consenti sempre (in background)") }
                    }
                }
                if (places.isEmpty()) {
                    Text("Nessun luogo salvato.", style = MaterialTheme.typography.bodySmall)
                } else {
                    places.forEach { place ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(place.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("raggio ${place.radiusMeters.toInt()} m", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { viewModel.deletePlace(place) }) { Text("Elimina") }
                        }
                    }
                }
            }
        }

        // --- Parcheggio ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Parcheggio", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                if (!foreground) {
                    Text(
                        "Concedi la posizione qui sopra per salvare il parcheggio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(
                        onClick = { viewModel.saveParkingHere() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Salva dove sono parcheggiato") }
                    val p = parking
                    if (p == null) {
                        Text("Nessun parcheggio salvato.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Salvato: ${p.savedAt.take(16).replace('T', ' ')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.distanceToParking() }) { Text("Distanza da qui") }
                            OutlinedButton(onClick = { viewModel.clearParking() }) { Text("Cancella") }
                        }
                    }
                }
                Text(
                    "Più avanti potrai salvarlo in automatico alla chiusura della modalità guida, " +
                        "solo se non sei in un luogo salvato.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Nuova regola ---
        RuleBuilderCard(
            places = places,
            weatherEnabled = weatherEnabled,
            onCreate = { viewModel.createRule(it) },
        )

        // --- Regole esistenti ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Le tue regole", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                if (rules.isEmpty()) {
                    Text("Nessuna regola nel nuovo motore.", style = MaterialTheme.typography.bodySmall)
                } else {
                    rules.forEach { rule ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ruleSummary(rule, places), style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRule(rule) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.testRule(rule) }) { Text("Prova adesso") }
                            OutlinedButton(onClick = { viewModel.deleteRule(rule) }) { Text("Elimina") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // --- Diagnostica ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diagnostica", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ultimi eventi del motore: perché è (o non è) scattato. Le prove col " +
                        "dry-run compaiono qui come «prova».",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                if (executions.isEmpty()) {
                    Text(
                        "Ancora nessun evento. Usa «Prova adesso» o aspetta che una regola scatti.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    executions.forEach { e ->
                        val time = e.at?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--"
                        val status = when {
                            e.dryRun -> "prova"
                            e.fired -> "scattata"
                            else -> "non scattata"
                        }
                        Text(
                            "${e.ruleName.take(32).ifBlank { "regola" }} · $time · $status",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (e.reason.isNotBlank()) {
                            Text(e.reason, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
    }
}

@Composable
private fun PlaceCapture(onSave: (String, Float) -> Unit) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("150") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Nome del luogo") },
        placeholder = { Text("casa") },
        singleLine = true,
    )
    OutlinedTextField(
        value = radius,
        onValueChange = { new -> radius = new.filter { it.isDigit() }.take(4) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Raggio in metri (min 50)") },
        singleLine = true,
    )
    Button(
        onClick = { onSave(name, (radius.toIntOrNull() ?: 150).toFloat()); name = "" },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Salva la posizione attuale") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleBuilderCard(
    places: List<AutomationPlaceEntity>,
    weatherEnabled: Boolean,
    onCreate: (RuleDraft) -> Unit,
) {
    var kind by remember { mutableStateOf(TriggerKind.DAILY_TIME) }
    var time by remember { mutableStateOf("08:00") }
    var placeId by remember { mutableStateOf<String?>(null) }
    var actionType by remember { mutableStateOf(ActionRegistry.SHOW_NOTIFICATION) }
    var message by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("") }
    // Optional "SE" filters.
    var days by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var onlyCharging by remember { mutableStateOf(false) }
    var onlyIfRainTomorrow by remember { mutableStateOf(false) }
    var timeFrom by remember { mutableStateOf("") }
    var timeTo by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nuova regola", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()

            Text("Quando", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TriggerKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
            }

            when (kind) {
                TriggerKind.DAILY_TIME -> OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ora (HH:mm)") },
                    singleLine = true,
                )
                TriggerKind.PLACE_ARRIVE, TriggerKind.PLACE_LEAVE -> {
                    if (places.isEmpty()) {
                        Text(
                            "Salva prima un luogo qui sopra.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            places.forEach { place ->
                                FilterChip(
                                    selected = placeId == place.id,
                                    onClick = { placeId = place.id },
                                    label = { Text(place.displayName) },
                                )
                            }
                        }
                    }
                }
                TriggerKind.CHARGING, TriggerKind.UNPLUGGED -> Unit
                TriggerKind.FIRST_UNLOCK -> Text(
                    "Scatta al primo sblocco dello schermo della giornata. Per «non prima " +
                        "delle 7», usa «Dalle/Alle» qui sotto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Solo se… (opzionale)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DAY_LABELS.forEachIndexed { index, label ->
                    val day = DayOfWeek.of(index + 1)
                    FilterChip(
                        selected = day in days,
                        onClick = { days = if (day in days) days - day else days + day },
                        label = { Text(label) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = onlyCharging,
                    onClick = { onlyCharging = !onlyCharging },
                    label = { Text("Solo in carica") },
                )
                FilterChip(
                    selected = onlyIfRainTomorrow,
                    onClick = { onlyIfRainTomorrow = !onlyIfRainTomorrow },
                    label = { Text("Se domani piove") },
                )
            }
            if (onlyIfRainTomorrow && !weatherEnabled) {
                Text(
                    "Il meteo è spento: attivalo qui sopra perché questa condizione " +
                        "possa mai risultare vera.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = timeFrom,
                    onValueChange = { timeFrom = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Dalle (HH:mm)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = timeTo,
                    onValueChange = { timeTo = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Alle (HH:mm)") },
                    singleLine = true,
                )
            }

            Text("Fai", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = actionType == ActionRegistry.SHOW_NOTIFICATION,
                    onClick = { actionType = ActionRegistry.SHOW_NOTIFICATION },
                    label = { Text("Notifica") },
                )
                FilterChip(
                    selected = actionType == ActionRegistry.SPEAK,
                    onClick = { actionType = ActionRegistry.SPEAK },
                    label = { Text("A voce") },
                )
                FilterChip(
                    selected = actionType == ActionRegistry.SET_JARVIS_MODE,
                    onClick = { actionType = ActionRegistry.SET_JARVIS_MODE },
                    label = { Text("Modalità") },
                )
            }
            if (actionType == ActionRegistry.SET_JARVIS_MODE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JarvisModes.all.forEach { profile ->
                        FilterChip(
                            selected = mode == profile.id,
                            onClick = { mode = profile.id },
                            label = { Text(profile.label) },
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Testo") },
                    placeholder = { Text("prendi le vitamine") },
                    maxLines = 3,
                )
            }

            val canCreate = if (actionType == ActionRegistry.SET_JARVIS_MODE) mode.isNotBlank() else message.isNotBlank()
            Button(
                onClick = {
                    onCreate(
                        RuleDraft(
                            kind = kind,
                            time = time,
                            placeId = placeId,
                            actionType = actionType,
                            message = message,
                            mode = mode,
                            days = days,
                            onlyCharging = onlyCharging,
                            timeFrom = timeFrom,
                            timeTo = timeTo,
                        ),
                    )
                    message = ""
                },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Crea regola") }
        }
    }
}

private val DAY_LABELS = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")

private fun ruleSummary(rule: AutomationRule, places: List<AutomationPlaceEntity>): String {
    val trigger = rule.triggers.firstOrNull()
    val action = rule.actions.firstOrNull()
    val whenText = trigger?.let { triggerLabel(it, places) } ?: "?"
    val doText = action?.let {
        when (it.type) {
            ActionRegistry.SPEAK -> "voce: ${it.params["message"].orEmpty()}"
            ActionRegistry.SET_JARVIS_MODE ->
                "modalità ${JarvisModes.profile(it.params["mode"])?.label ?: it.params["mode"].orEmpty()}"
            else -> "notifica: ${it.params["message"].orEmpty()}"
        }
    } ?: "?"
    val ifText = rule.condition?.let { " · se ${conditionLabel(it)}" }.orEmpty()
    val off = if (rule.enabled) "" else " (disattivata)"
    return "$whenText → $doText$ifText$off"
}

private fun conditionLabel(condition: Condition): String = when (condition) {
    is Condition.All -> condition.of.joinToString(" e ") { conditionLabel(it) }
    is Condition.DayOfWeekIn -> condition.days.sortedBy { it.value }.joinToString(",") { DAY_LABELS[it.value - 1] }
    Condition.IsCharging -> "in carica"
    is Condition.TimeRange -> "${condition.from}–${condition.to}"
    is Condition.CurrentPlace -> "a ${condition.placeId}"
    Condition.RainToday -> "piove oggi"
    Condition.RainTomorrow -> "piove domani"
    else -> "condizioni"
}

private fun triggerLabel(spec: TriggerSpec, places: List<AutomationPlaceEntity>): String {
    fun placeName(id: String?): String = places.firstOrNull { it.id == id }?.displayName ?: (id ?: "?")
    return when (spec.type) {
        TriggerRegistry.RECURRING_TIME -> "Ogni giorno alle ${spec.params["time"].orEmpty()}"
        TriggerRegistry.TIME_AT -> "Il ${spec.params["at"].orEmpty()}"
        TriggerRegistry.PLACE_ENTER -> "Quando arrivo a ${placeName(spec.params["placeId"])}"
        TriggerRegistry.PLACE_EXIT -> "Quando esco da ${placeName(spec.params["placeId"])}"
        TriggerRegistry.DEVICE_CHARGING -> "Quando metto in carica"
        TriggerRegistry.DEVICE_UNPLUGGED -> "Quando tolgo dalla carica"
        TriggerRegistry.FIRST_UNLOCK_OF_DAY -> "Al primo sblocco del giorno"
        else -> TriggerRegistry.definition(spec.type)?.label ?: spec.type
    }
}
