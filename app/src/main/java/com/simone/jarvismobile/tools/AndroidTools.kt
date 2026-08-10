package com.simone.jarvismobile.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryStructure
import com.simone.jarvismobile.memory.MemoryIndex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun ok(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

private fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

/** Reads the current date and time from the device clock. Fully offline. */
class TimeTool : Tool {
    override val name = "get_time"
    override val description = "Dice l'ora e la data correnti."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 500L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN))
        return ok("time" to time, "date" to date, "spoken" to "Sono le $time di $date.")
    }
}

/** Reports the battery level and charging state. */
class BatteryTool(private val context: Context) : Tool {
    override val name = "battery_status"
    override val description = "Riporta il livello della batteria e se è in carica."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 500L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ToolResult.Failure("no_battery_service")
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        // "Quanta batteria ho?" wants a number, not a status report. The charging
        // state is extra information the user did not ask for, so it is only
        // spoken when the question actually mentioned charging.
        val wantsCharging = arguments.str("charging_asked")?.toBooleanStrictOrNull() == true
        val spoken = when {
            !wantsCharging -> "Batteria al $pct per cento."
            charging -> "Batteria al $pct per cento, in carica."
            else -> "Batteria al $pct per cento, non è in carica."
        }
        return ok("percent" to pct.toString(), "charging" to charging.toString(), "spoken" to spoken)
    }
}

/**
 * Starts a countdown timer through the system clock app (ACTION_SET_TIMER).
 * We never build arbitrary Intents from model text — only this fixed action with
 * a validated duration (docs/SECURITY.md §15).
 */
class TimerTool(private val context: Context) : Tool {
    override val name = "set_timer"
    override val description = "Avvia un timer per la durata indicata (in secondi)."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val s = arguments.int("seconds") ?: return "manca il campo 'seconds'"
        if (s !in 1..86_400) return "durata fuori intervallo (1s–24h)"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val seconds = arguments.int("seconds") ?: return ToolResult.Failure("missing_seconds")
        val label = arguments.str("label")?.take(40).orEmpty()
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ok("seconds" to seconds.toString(), "spoken" to "Timer di ${humanDuration(seconds)} avviato.")
        } catch (e: Exception) {
            ToolResult.Failure("no_clock_app")
        }
    }
}

/** Sets an alarm at a given hour/minute through the system clock app. */
class AlarmTool(private val context: Context) : Tool {
    override val name = "set_alarm"
    override val description = "Imposta una sveglia a un'ora precisa."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val h = arguments.int("hour") ?: return "manca il campo 'hour'"
        val m = arguments.int("minute") ?: 0
        if (h !in 0..23) return "ora non valida"
        if (m !in 0..59) return "minuti non validi"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val h = arguments.int("hour") ?: return ToolResult.Failure("missing_hour")
        val m = arguments.int("minute") ?: 0
        val label = arguments.str("label")?.take(40).orEmpty()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val hhmm = String.format(Locale.ITALIAN, "%02d:%02d", h, m)
            ok("time" to hhmm, "spoken" to "Sveglia impostata alle $hhmm.")
        } catch (e: Exception) {
            ToolResult.Failure("no_clock_app")
        }
    }
}

/** Turns the camera torch on or off. */
class FlashlightTool(private val context: Context) : Tool {
    override val name = "flashlight"
    override val description = "Accende o spegne la torcia."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 1_500L

    override fun validate(arguments: JsonObject): String? {
        val on = arguments.str("on") ?: return "manca il campo 'on'"
        if (on !in setOf("true", "false")) return "valore 'on' non valido"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val on = arguments.str("on")?.toBooleanStrictOrNull() ?: return ToolResult.Failure("missing_on")
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult.Failure("no_camera_service")
        return try {
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.Failure("no_torch")
            cm.setTorchMode(id, on)
            ok("on" to on.toString(), "spoken" to if (on) "Torcia accesa." else "Torcia spenta.")
        } catch (e: Exception) {
            ToolResult.Failure("torch_failed")
        }
    }
}

/**
 * Saves a note into the Obsidian vault (Phase 5 memory). Writing to the user's
 * notes is a real write, so it is classified above read-only.
 */
class RememberTool(private val memory: MemoryIndex) : Tool {
    override val name = "remember"
    override val description = "Salva un appunto nella memoria (vault Obsidian)."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val text = arguments.str("text") ?: return "manca il campo 'text'"
        if (text.isBlank()) return "testo vuoto"
        if (MemoryStructure.containsCredential(text)) return "password, PIN, OTP e token non possono essere salvati"
        arguments.str("kind")?.let { raw ->
            if (MemoryKind.entries.none { it.name.equals(raw, ignoreCase = true) }) return "tipo di memoria non valido"
        }
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val text = arguments.str("text")?.take(180) ?: return null
        val kind = arguments.str("kind")?.let { raw ->
            MemoryKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: MemoryStructure.classify(text)
        val target = when (kind) {
            MemoryKind.TEMPORARY -> "nella memoria breve di questa conversazione"
            MemoryKind.PERMANENT -> "in JARVIS/Memoria.md nel vault Obsidian"
            MemoryKind.SENSITIVE -> "come dato sensibile in JARVIS/Memoria.md"
        }
        return "Confermi di salvare “$text” $target?"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.str("text") ?: return ToolResult.Failure("missing_text")
        val kind = arguments.str("kind")?.let { raw ->
            MemoryKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: MemoryStructure.classify(text)
        val saved = runCatching { memory.remember(text, kind) }.getOrNull()
        return when {
            saved != null -> {
                val where = if (kind == MemoryKind.TEMPORARY) "per questa conversazione" else "nel vault"
                ok("text" to text, "kind" to kind.name, "spoken" to "Ho annotato $where: $text")
            }
            // Honest failure: never claim a save that didn't happen. Tell the user
            // whether there's simply no vault, or the vault refused the write.
            !memory.isConfigured() -> ok(
                "spoken" to "Non ho un vault Obsidian collegato, quindi non posso salvarlo. " +
                    "Collegalo in Impostazioni › Memoria.",
            )
            else -> ok(
                "spoken" to "Non sono riuscito a salvarlo: il vault non ha accettato la scrittura. " +
                    "Controlla i permessi della cartella in Impostazioni › Memoria.",
            )
        }
    }
}

/**
 * Reads the saved reminders back from the vault. Deterministic on purpose: the
 * answer to "cosa devo fare?" comes from the file, never from the model, so it
 * can't be invented.
 */
class ListMemoriesTool(private val memory: MemoryIndex) : Tool {
    override val name = "list_memories"
    override val description = "Elenca gli appunti e i promemoria salvati."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 5_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!memory.isConfigured()) {
            return ok("spoken" to "Non ho un vault collegato. Aprilo in Impostazioni › Memoria.")
        }
        val items = runCatching { memory.listMemories(10) }.getOrDefault(emptyList())
        val spoken = when {
            items.isEmpty() -> "Non ho nessun appunto salvato."
            items.size == 1 -> "Hai un appunto: ${stripStamp(items.first())}."
            else -> "Hai ${items.size} appunti: " +
                items.joinToString("; ") { stripStamp(it) } + "."
        }
        return ok("count" to items.size.toString(), "spoken" to spoken)
    }

    /** "[2026-08-05 21:56] fare la revisione" -> "fare la revisione". */
    private fun stripStamp(line: String): String =
        line.replace(Regex("""^\[[^\]]*]\s*"""), "").trim()
}

/** "3600" -> "1 ora"; used for spoken confirmations. */
internal fun humanDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    val parts = buildList {
        if (h > 0) add(if (h == 1) "1 ora" else "$h ore")
        if (m > 0) add(if (m == 1) "1 minuto" else "$m minuti")
        if (s > 0 && h == 0) add(if (s == 1) "1 secondo" else "$s secondi")
    }
    return if (parts.isEmpty()) "0 secondi" else parts.joinToString(" e ")
}
