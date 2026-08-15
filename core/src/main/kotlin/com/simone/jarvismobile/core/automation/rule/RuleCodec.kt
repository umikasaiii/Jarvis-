package com.simone.jarvismobile.core.automation.rule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Explicit JSON format for the parts of a rule that are trees rather than
 * scalars: its triggers, its condition and its actions (§23).
 *
 * Written by hand instead of with `@Serializable` on the domain classes, for two
 * reasons. First, the domain stays free of any storage concern — `Condition` is
 * about logic, not about how it is written down. Second, and more importantly,
 * an explicit format is one we can *migrate*: every payload carries a [VERSION],
 * and [decodeRule] is the single place where an older shape is upgraded. Relying
 * on automatic serialisation of concrete classes would make the storage format
 * an accident of the class hierarchy, so a harmless refactor could silently
 * become an unreadable database.
 *
 * Decoding never throws on bad input: a rule that cannot be understood comes
 * back as null, and the caller quarantines it rather than crashing the engine
 * (§25 "malformed automation").
 */
object RuleCodec {

    /** Payload format version. Bump when a shape changes, and migrate below. */
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- Specs ---------------------------------------------------------

    fun encodeTriggers(triggers: List<TriggerSpec>): String =
        json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                triggers.forEach { add(specObject(it.type, it.params)) }
            },
        )

    fun decodeTriggers(raw: String): List<TriggerSpec>? = runCatching {
        json.parseToJsonElement(raw).jsonArray.map { element ->
            val o = element.jsonObject
            TriggerSpec(
                type = o["type"]?.jsonPrimitive?.content ?: return@runCatching null,
                params = readParams(o),
            )
        }
    }.getOrNull()

    fun encodeActions(actions: List<ActionSpec>): String =
        json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray {
                actions.forEach { add(specObject(it.type, it.params)) }
            },
        )

    fun decodeActions(raw: String): List<ActionSpec>? = runCatching {
        json.parseToJsonElement(raw).jsonArray.map { element ->
            val o = element.jsonObject
            ActionSpec(
                type = o["type"]?.jsonPrimitive?.content ?: return@runCatching null,
                params = readParams(o),
            )
        }
    }.getOrNull()

    private fun specObject(type: String, params: Map<String, String>) = buildJsonObject {
        put("type", JsonPrimitive(type))
        put(
            "params",
            buildJsonObject { params.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )
    }

    private fun readParams(o: JsonObject): Map<String, String> =
        o["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    // --- Conditions ----------------------------------------------------

    /** Encodes a condition tree; a null condition encodes as JSON null. */
    fun encodeCondition(condition: Condition?): String =
        json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), conditionJson(condition))

    /**
     * Decodes a condition tree. Returns null both for "there was no condition"
     * and for "it could not be read" — callers that need to tell those apart use
     * [isDecodableCondition].
     */
    fun decodeCondition(raw: String?): Condition? {
        if (raw.isNullOrBlank()) return null
        return runCatching { conditionOf(json.parseToJsonElement(raw)) }.getOrNull()
    }

    /** True when [raw] is either empty or a condition we can actually read. */
    fun isDecodableCondition(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return false
        if (element is JsonNull) return true
        return runCatching { conditionOf(element) }.getOrNull() != null
    }

    private fun conditionJson(condition: Condition?): kotlinx.serialization.json.JsonElement =
        when (condition) {
            null -> JsonNull
            is Condition.All -> buildJsonObject {
                put("t", JsonPrimitive("ALL"))
                put("of", buildJsonArray { condition.of.forEach { add(conditionJson(it)) } })
            }
            is Condition.Any -> buildJsonObject {
                put("t", JsonPrimitive("ANY"))
                put("of", buildJsonArray { condition.of.forEach { add(conditionJson(it)) } })
            }
            is Condition.Not -> buildJsonObject {
                put("t", JsonPrimitive("NOT"))
                put("of", conditionJson(condition.of))
            }
            is Condition.DayOfWeekIn -> leaf("DAY_OF_WEEK") {
                put("days", buildJsonArray { condition.days.sorted().forEach { add(JsonPrimitive(it.name)) } })
            }
            is Condition.TimeRange -> leaf("TIME_RANGE") {
                put("from", JsonPrimitive(condition.from.toString()))
                put("to", JsonPrimitive(condition.to.toString()))
            }
            is Condition.CurrentPlace -> leaf("CURRENT_PLACE") {
                put("placeId", JsonPrimitive(condition.placeId))
            }
            is Condition.ActivityType -> leaf("ACTIVITY") {
                put("activity", JsonPrimitive(condition.activity))
            }
            is Condition.BatteryAbove -> leaf("BATTERY_ABOVE") {
                put("percent", JsonPrimitive(condition.percent))
            }
            is Condition.BatteryBelow -> leaf("BATTERY_BELOW") {
                put("percent", JsonPrimitive(condition.percent))
            }
            Condition.IsCharging -> leaf("IS_CHARGING") {}
            Condition.NetworkAvailable -> leaf("NETWORK_AVAILABLE") {}
            is Condition.BluetoothConnected -> leaf("BLUETOOTH") {
                put("device", JsonPrimitive(condition.device))
            }
            is Condition.JarvisMode -> leaf("JARVIS_MODE") {
                put("mode", JsonPrimitive(condition.mode))
            }
            Condition.CalendarEventExists -> leaf("CALENDAR_EVENT_EXISTS") {}
            is Condition.RuleNotExecutedRecently -> leaf("NOT_EXECUTED_RECENTLY") {
                put("minutes", JsonPrimitive(condition.minutes))
            }
        }

    private fun leaf(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("t", JsonPrimitive(type))
            body()
        }

    /** Throws on anything unreadable; callers wrap in runCatching. */
    private fun conditionOf(element: kotlinx.serialization.json.JsonElement): Condition? {
        if (element is JsonNull) return null
        val o = element.jsonObject
        return when (val type = o["t"]!!.jsonPrimitive.content) {
            "ALL" -> Condition.All(o["of"]!!.jsonArray.map { conditionOf(it)!! })
            "ANY" -> Condition.Any(o["of"]!!.jsonArray.map { conditionOf(it)!! })
            "NOT" -> Condition.Not(conditionOf(o["of"]!!)!!)
            "DAY_OF_WEEK" -> Condition.DayOfWeekIn(
                o["days"]!!.jsonArray.map { DayOfWeek.valueOf(it.jsonPrimitive.content) }.toSet(),
            )
            "TIME_RANGE" -> Condition.TimeRange(
                LocalTime.parse(o["from"]!!.jsonPrimitive.content),
                LocalTime.parse(o["to"]!!.jsonPrimitive.content),
            )
            "CURRENT_PLACE" -> Condition.CurrentPlace(o["placeId"]!!.jsonPrimitive.content)
            "ACTIVITY" -> Condition.ActivityType(o["activity"]!!.jsonPrimitive.content)
            "BATTERY_ABOVE" -> Condition.BatteryAbove(o["percent"]!!.jsonPrimitive.content.toInt())
            "BATTERY_BELOW" -> Condition.BatteryBelow(o["percent"]!!.jsonPrimitive.content.toInt())
            "IS_CHARGING" -> Condition.IsCharging
            "NETWORK_AVAILABLE" -> Condition.NetworkAvailable
            "BLUETOOTH" -> Condition.BluetoothConnected(o["device"]!!.jsonPrimitive.content)
            "JARVIS_MODE" -> Condition.JarvisMode(o["mode"]!!.jsonPrimitive.content)
            "CALENDAR_EVENT_EXISTS" -> Condition.CalendarEventExists
            "NOT_EXECUTED_RECENTLY" ->
                Condition.RuleNotExecutedRecently(o["minutes"]!!.jsonPrimitive.content.toLong())
            else -> error("condizione sconosciuta: $type")
        }
    }

    // --- Whole rule ----------------------------------------------------

    /**
     * Rebuilds a rule from its stored parts. Returns null when the payload is
     * unreadable, so the caller can quarantine that row instead of crashing —
     * one corrupt rule must not take the whole engine down.
     */
    @Suppress("LongParameterList")
    fun decodeRule(
        id: String,
        name: String,
        enabled: Boolean,
        triggersJson: String,
        conditionJson: String?,
        actionsJson: String,
        priority: Int,
        executionPolicy: String,
        cooldownSeconds: Long,
        oneShot: Boolean,
        expiresAt: String?,
        lastFiredAt: String?,
        createdAt: String?,
        updatedAt: String?,
        schemaVersion: Int,
    ): AutomationRule? {
        val triggers = decodeTriggers(triggersJson) ?: return null
        val actions = decodeActions(actionsJson) ?: return null
        if (!isDecodableCondition(conditionJson)) return null
        val policy = runCatching { ExecutionPolicy.valueOf(executionPolicy) }
            .getOrDefault(ExecutionPolicy.SKIP_IF_RUNNING)
        return runCatching {
            AutomationRule(
                id = id,
                name = name,
                enabled = enabled,
                triggers = triggers,
                condition = decodeCondition(conditionJson),
                actions = actions,
                priority = priority,
                executionPolicy = policy,
                cooldownSeconds = cooldownSeconds,
                oneShot = oneShot,
                expiresAt = parseTime(expiresAt),
                lastFiredAt = parseTime(lastFiredAt),
                createdAt = parseTime(createdAt),
                updatedAt = parseTime(updatedAt),
                schemaVersion = schemaVersion,
            )
        }.getOrNull()
    }

    private fun parseTime(raw: String?): LocalDateTime? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
}
