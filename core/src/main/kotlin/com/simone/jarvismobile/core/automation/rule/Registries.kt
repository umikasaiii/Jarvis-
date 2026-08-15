package com.simone.jarvismobile.core.automation.rule

/**
 * Metadata registries for triggers and actions (§8, §10, §27).
 *
 * The goal is that adding a trigger later means adding one [TriggerDefinition]
 * and its handler — nothing in the engine, and nothing in the Rule Builder,
 * changes. The UI builds its pickers and its parameter forms from this metadata,
 * so a new kind appears in the app automatically instead of needing a `when`
 * branch in every screen.
 *
 * Definitions are pure data: no Android, no handler references. Handlers are
 * bound in the app layer by [TriggerDefinition.type] / [ActionDefinition.type].
 */

/** How a parameter should be asked for, so the Rule Builder can render a field. */
enum class ParamKind { TEXT, INT, TIME, DATE_TIME, DAYS, PLACE, DURATION_MINUTES, BOOLEAN, ENUM }

/** One parameter of a trigger or action. */
data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val required: Boolean = true,
    /** Allowed values for [ParamKind.ENUM]. */
    val options: List<String> = emptyList(),
)

/** A kind of trigger the engine knows how to arm. */
data class TriggerDefinition(
    val type: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
    val requires: Capability = Capability.NONE,
    /** False for kinds that are declared but cannot run yet on this build. */
    val implemented: Boolean = true,
)

/** A kind of action the engine knows how to run. */
data class ActionDefinition(
    val type: String,
    val label: String,
    val params: List<ParamSpec> = emptyList(),
    val risk: ActionRisk = ActionRisk.LOCAL_SAFE,
    val requires: Capability = Capability.NONE,
    val implemented: Boolean = true,
    /**
     * A state only one rule may own at a time, e.g. the assistant's mode. Two
     * rules claiming the same resource in the same moment are a conflict, and
     * the higher-priority one wins (§12); without this the two would fight and
     * flip the state back and forth.
     */
    val exclusiveResource: String? = null,
)

object TriggerRegistry {

    const val TIME_AT = "TIME_AT"
    const val RECURRING_TIME = "RECURRING_TIME"
    const val TIME_WINDOW = "TIME_WINDOW"
    const val PLACE_ENTER = "PLACE_ENTER"
    const val PLACE_EXIT = "PLACE_EXIT"
    const val PLACE_DWELL = "PLACE_DWELL"
    const val BLUETOOTH_CONNECTED = "BLUETOOTH_CONNECTED"
    const val BLUETOOTH_DISCONNECTED = "BLUETOOTH_DISCONNECTED"
    const val ACTIVITY_ENTER = "ACTIVITY_ENTER"
    const val ACTIVITY_EXIT = "ACTIVITY_EXIT"
    const val FIRST_UNLOCK_OF_DAY = "FIRST_UNLOCK_OF_DAY"
    const val CALENDAR_EVENT_APPROACHING = "CALENDAR_EVENT_APPROACHING"
    const val REMINDER_DUE = "REMINDER_DUE"
    const val NOTIFICATION_MATCH = "NOTIFICATION_MATCH"
    const val DEVICE_CHARGING = "DEVICE_CHARGING"
    const val DEVICE_UNPLUGGED = "DEVICE_UNPLUGGED"
    const val JARVIS_MODE_ENTER = "JARVIS_MODE_ENTER"
    const val JARVIS_MODE_EXIT = "JARVIS_MODE_EXIT"

    private val ACTIVITIES = listOf("IN_VEHICLE", "WALKING", "RUNNING", "ON_BICYCLE", "STILL")

    /**
     * Every trigger kind the model can express.
     *
     * `implemented = false` marks kinds whose plumbing does not exist yet in this
     * build. They are listed so the model and the migrations are stable, but the
     * app must show them as unavailable rather than letting a user arm a rule
     * that can never fire — the trap the old `ArrivedHome` trigger fell into.
     */
    val all: List<TriggerDefinition> = listOf(
        TriggerDefinition(
            TIME_AT, "A un orario preciso",
            listOf(ParamSpec("at", "Quando", ParamKind.DATE_TIME)),
            requires = Capability.EXACT_ALARM,
        ),
        TriggerDefinition(
            RECURRING_TIME, "Ogni giorno a un orario",
            listOf(
                ParamSpec("time", "Ora", ParamKind.TIME),
                ParamSpec("days", "Giorni", ParamKind.DAYS, required = false),
            ),
            requires = Capability.EXACT_ALARM,
        ),
        TriggerDefinition(
            TIME_WINDOW, "In una fascia oraria",
            listOf(
                ParamSpec("from", "Dalle", ParamKind.TIME),
                ParamSpec("to", "Alle", ParamKind.TIME),
            ),
        ),
        TriggerDefinition(
            PLACE_ENTER, "Quando arrivo in un luogo",
            listOf(ParamSpec("placeId", "Luogo", ParamKind.PLACE)),
            requires = Capability.LOCATION_BACKGROUND, implemented = false,
        ),
        TriggerDefinition(
            PLACE_EXIT, "Quando esco da un luogo",
            listOf(ParamSpec("placeId", "Luogo", ParamKind.PLACE)),
            requires = Capability.LOCATION_BACKGROUND, implemented = false,
        ),
        TriggerDefinition(
            PLACE_DWELL, "Quando resto in un luogo",
            listOf(
                ParamSpec("placeId", "Luogo", ParamKind.PLACE),
                ParamSpec("minutes", "Dopo minuti", ParamKind.DURATION_MINUTES, required = false),
            ),
            requires = Capability.LOCATION_BACKGROUND, implemented = false,
        ),
        TriggerDefinition(
            BLUETOOTH_CONNECTED, "Quando si connette un dispositivo Bluetooth",
            listOf(ParamSpec("device", "Dispositivo", ParamKind.TEXT)),
            requires = Capability.BLUETOOTH,
        ),
        TriggerDefinition(
            BLUETOOTH_DISCONNECTED, "Quando si disconnette un dispositivo Bluetooth",
            listOf(ParamSpec("device", "Dispositivo", ParamKind.TEXT)),
            requires = Capability.BLUETOOTH,
        ),
        TriggerDefinition(
            ACTIVITY_ENTER, "Quando inizio un'attività",
            listOf(ParamSpec("activity", "Attività", ParamKind.ENUM, options = ACTIVITIES)),
            requires = Capability.ACTIVITY_RECOGNITION, implemented = false,
        ),
        TriggerDefinition(
            ACTIVITY_EXIT, "Quando finisco un'attività",
            listOf(ParamSpec("activity", "Attività", ParamKind.ENUM, options = ACTIVITIES)),
            requires = Capability.ACTIVITY_RECOGNITION, implemented = false,
        ),
        TriggerDefinition(FIRST_UNLOCK_OF_DAY, "Al primo sblocco del giorno",
            listOf(ParamSpec("after", "Non prima delle", ParamKind.TIME, required = false))),
        TriggerDefinition(
            CALENDAR_EVENT_APPROACHING, "Prima di un impegno",
            listOf(ParamSpec("minutesBefore", "Minuti prima", ParamKind.DURATION_MINUTES)),
            requires = Capability.EXACT_ALARM,
        ),
        TriggerDefinition(REMINDER_DUE, "Quando scade un promemoria", requires = Capability.EXACT_ALARM),
        TriggerDefinition(
            NOTIFICATION_MATCH, "Quando arriva una notifica",
            listOf(
                ParamSpec("app", "App", ParamKind.TEXT, required = false),
                ParamSpec("contains", "Contiene", ParamKind.TEXT, required = false),
            ),
            requires = Capability.NOTIFICATION_LISTENER, implemented = false,
        ),
        TriggerDefinition(DEVICE_CHARGING, "Quando metto in carica"),
        TriggerDefinition(DEVICE_UNPLUGGED, "Quando tolgo dalla carica"),
        TriggerDefinition(JARVIS_MODE_ENTER, "Quando entro in una modalità",
            listOf(ParamSpec("mode", "Modalità", ParamKind.TEXT))),
        TriggerDefinition(JARVIS_MODE_EXIT, "Quando esco da una modalità",
            listOf(ParamSpec("mode", "Modalità", ParamKind.TEXT))),
    )

    private val byType = all.associateBy { it.type }

    fun definition(type: String): TriggerDefinition? = byType[type]

    /** Kinds the Rule Builder may offer right now. */
    fun available(): List<TriggerDefinition> = all.filter { it.implemented }

    /** Missing or malformed parameters for [spec], as human-readable reasons. */
    fun validate(spec: TriggerSpec): List<String> {
        val def = definition(spec.type) ?: return listOf("trigger sconosciuto: ${spec.type}")
        return validateParams(def.params, spec.params)
    }
}

object ActionRegistry {

    const val SHOW_NOTIFICATION = "SHOW_NOTIFICATION"
    const val SPEAK = "SPEAK"
    const val SET_JARVIS_MODE = "SET_JARVIS_MODE"
    const val OPEN_APP_SCREEN = "OPEN_APP_SCREEN"
    const val OPEN_DRIVING_HUD = "OPEN_DRIVING_HUD"
    const val CREATE_REMINDER = "CREATE_REMINDER"
    const val UPDATE_REMINDER = "UPDATE_REMINDER"
    const val DELETE_REMINDER = "DELETE_REMINDER"
    const val RUN_TOOL = "RUN_TOOL"
    const val RUN_HOME_ASSISTANT_ACTION = "RUN_HOME_ASSISTANT_ACTION"
    const val SCHEDULE_FOLLOW_UP = "SCHEDULE_FOLLOW_UP"
    const val START_WAKE_WORD_MODE = "START_WAKE_WORD_MODE"
    const val STOP_WAKE_WORD_MODE = "STOP_WAKE_WORD_MODE"
    const val SAVE_PARKING_LOCATION = "SAVE_PARKING_LOCATION"

    /** Exclusive state names. Two rules claiming one are resolved by priority. */
    const val RESOURCE_MODE = "jarvis_mode"
    const val RESOURCE_WAKE_WORD = "wake_word"

    val all: List<ActionDefinition> = listOf(
        ActionDefinition(
            SHOW_NOTIFICATION, "Mostra una notifica",
            listOf(ParamSpec("message", "Testo", ParamKind.TEXT)),
            risk = ActionRisk.LOCAL_SAFE, requires = Capability.POST_NOTIFICATIONS,
        ),
        ActionDefinition(
            SPEAK, "Dillo ad alta voce",
            listOf(ParamSpec("message", "Testo", ParamKind.TEXT)),
            risk = ActionRisk.LOCAL_SAFE,
        ),
        ActionDefinition(
            SET_JARVIS_MODE, "Cambia modalità JARVIS",
            listOf(ParamSpec("mode", "Modalità", ParamKind.TEXT)),
            risk = ActionRisk.LOCAL_SAFE, exclusiveResource = RESOURCE_MODE, implemented = false,
        ),
        ActionDefinition(
            OPEN_APP_SCREEN, "Apri una schermata di JARVIS",
            listOf(ParamSpec("screen", "Schermata", ParamKind.TEXT)),
            risk = ActionRisk.LOCAL_SAFE, implemented = false,
        ),
        ActionDefinition(OPEN_DRIVING_HUD, "Apri la modalità guida",
            risk = ActionRisk.LOCAL_SAFE, exclusiveResource = RESOURCE_MODE, implemented = false),
        ActionDefinition(
            CREATE_REMINDER, "Crea un promemoria",
            listOf(
                ParamSpec("text", "Testo", ParamKind.TEXT),
                ParamSpec("date", "Data", ParamKind.TEXT, required = false),
                ParamSpec("time", "Ora", ParamKind.TIME, required = false),
            ),
            risk = ActionRisk.LOCAL_SAFE,
        ),
        ActionDefinition(
            UPDATE_REMINDER, "Modifica un promemoria",
            listOf(
                ParamSpec("id", "Impegno", ParamKind.TEXT),
                ParamSpec("date", "Nuova data", ParamKind.TEXT, required = false),
                ParamSpec("time", "Nuova ora", ParamKind.TIME, required = false),
            ),
            risk = ActionRisk.SENSITIVE, implemented = false,
        ),
        ActionDefinition(
            DELETE_REMINDER, "Elimina un promemoria",
            listOf(ParamSpec("id", "Impegno", ParamKind.TEXT)),
            risk = ActionRisk.SENSITIVE, implemented = false,
        ),
        ActionDefinition(
            RUN_TOOL, "Esegui un comando JARVIS",
            listOf(ParamSpec("command", "Comando", ParamKind.TEXT)),
            // Goes through the existing allowlist and ToolRunner, never a shell.
            risk = ActionRisk.LOCAL_SAFE,
        ),
        ActionDefinition(
            RUN_HOME_ASSISTANT_ACTION, "Azione Home Assistant",
            listOf(
                ParamSpec("entity", "Entità", ParamKind.TEXT),
                ParamSpec("service", "Servizio", ParamKind.TEXT),
            ),
            risk = ActionRisk.EXTERNAL_SIDE_EFFECT,
            requires = Capability.HOME_ASSISTANT, implemented = false,
        ),
        ActionDefinition(
            SCHEDULE_FOLLOW_UP, "Richiamami più tardi",
            listOf(
                ParamSpec("minutes", "Fra minuti", ParamKind.DURATION_MINUTES),
                ParamSpec("message", "Testo", ParamKind.TEXT, required = false),
            ),
            risk = ActionRisk.LOCAL_SAFE, requires = Capability.EXACT_ALARM, implemented = false,
        ),
        ActionDefinition(START_WAKE_WORD_MODE, "Attiva la parola di attivazione",
            risk = ActionRisk.LOCAL_SAFE, exclusiveResource = RESOURCE_WAKE_WORD, implemented = false),
        ActionDefinition(STOP_WAKE_WORD_MODE, "Disattiva la parola di attivazione",
            risk = ActionRisk.LOCAL_SAFE, exclusiveResource = RESOURCE_WAKE_WORD, implemented = false),
        ActionDefinition(
            SAVE_PARKING_LOCATION, "Salva dove ho parcheggiato",
            risk = ActionRisk.LOCAL_SAFE,
            requires = Capability.LOCATION_BACKGROUND, implemented = false,
        ),
    )

    private val byType = all.associateBy { it.type }

    fun definition(type: String): ActionDefinition? = byType[type]

    fun available(): List<ActionDefinition> = all.filter { it.implemented }

    fun validate(spec: ActionSpec): List<String> {
        val def = definition(spec.type) ?: return listOf("azione sconosciuta: ${spec.type}")
        return validateParams(def.params, spec.params)
    }

    /** Exclusive states the given actions would claim. */
    fun exclusiveResources(actions: List<ActionSpec>): Set<String> =
        actions.mapNotNull { definition(it.type)?.exclusiveResource }.toSet()

    /** The highest risk among [actions] — what the rule as a whole amounts to. */
    fun riskOf(actions: List<ActionSpec>): ActionRisk = actions
        .mapNotNull { definition(it.type)?.risk }
        .maxByOrNull { it.ordinal } ?: ActionRisk.READ_ONLY
}

/** Shared parameter validation for both registries. */
internal fun validateParams(specs: List<ParamSpec>, given: Map<String, String>): List<String> =
    buildList {
        for (p in specs) {
            val value = given[p.key]?.takeIf { it.isNotBlank() }
            if (value == null) {
                if (p.required) add("manca il parametro «${p.label}»")
                continue
            }
            when (p.kind) {
                ParamKind.INT, ParamKind.DURATION_MINUTES ->
                    if (value.trim().toIntOrNull() == null) add("«${p.label}» deve essere un numero")
                ParamKind.BOOLEAN ->
                    if (value.lowercase() !in setOf("true", "false")) add("«${p.label}» deve essere true/false")
                ParamKind.ENUM ->
                    if (p.options.isNotEmpty() && value !in p.options) {
                        add("«${p.label}» non è fra: ${p.options.joinToString(", ")}")
                    }
                else -> Unit
            }
        }
        // An unknown parameter is a bug or a stale rule, not something to ignore
        // silently — it usually means a schema changed under our feet.
        val knownKeys = specs.map { it.key }.toSet()
        given.keys.filterNot { it in knownKeys }.forEach { add("parametro sconosciuto: $it") }
    }

/** Everything wrong with a rule, empty when it is safe to store and arm. */
fun AutomationRule.validationErrors(): List<String> = buildList {
    if (name.isBlank()) add("manca il nome")
    if (triggers.isEmpty()) add("serve almeno un trigger")
    if (actions.isEmpty()) add("serve almeno un'azione")
    triggers.forEach { addAll(TriggerRegistry.validate(it)) }
    actions.forEach { addAll(ActionRegistry.validate(it)) }
    // Refuse to store a rule armed on something this build cannot deliver, so
    // the user never sees a rule that looks active but can never fire.
    triggers.mapNotNull { TriggerRegistry.definition(it.type) }
        .filterNot { it.implemented }
        .forEach { add("il trigger «${it.label}» non è ancora disponibile in questa versione") }
    actions.mapNotNull { ActionRegistry.definition(it.type) }
        .filterNot { it.implemented }
        .forEach { add("l'azione «${it.label}» non è ancora disponibile in questa versione") }
}
