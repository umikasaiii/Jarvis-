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
 * Saves a note into JARVIS's own local memory (Phase 5). Writing to the user's
 * notes is a real write, so it is classified above read-only. Local-first: the
 * save always lands in app-private storage and is mirrored to an Obsidian vault
 * only when one happens to be connected.
 */
class RememberTool(private val memory: MemoryIndex) : Tool {
    override val name = "remember"
    override val description = "Salva un appunto nella memoria."
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
            MemoryKind.PERMANENT -> "in memoria"
            MemoryKind.SENSITIVE -> "in memoria, come dato sensibile"
        }
        return "Confermi di salvare “$text” $target?"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.str("text") ?: return ToolResult.Failure("missing_text")
        val kind = arguments.str("kind")?.let { raw ->
            MemoryKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: MemoryStructure.classify(text)
        val saved = runCatching { memory.remember(text, kind) }.getOrNull()
        return if (saved != null) {
            val where = if (kind == MemoryKind.TEMPORARY) "per questa conversazione" else "in memoria"
            ok("text" to text, "kind" to kind.name, "spoken" to "Ho annotato $where: $text")
        } else {
            // Local-first, so a save should always land; getting here means the
            // local write itself failed. Never claim a save that didn't happen.
            ok(
                "spoken" to "Non sono riuscito a salvarlo in memoria. Riprova tra un momento.",
            )
        }
    }
}

/**
 * Deletes a saved memory by the words it contains ("dimentica che mi piace il
 * ketchup"). Confirming and fail-closed: it removes a memory only when exactly
 * one matches, and shows what will go first — a delete can't be undone.
 */
class ForgetMemoryTool(private val memory: MemoryIndex) : Tool {
    override val name = "forget_memory"
    override val description = "Elimina un appunto dalla memoria in base al testo."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val text = arguments.str("text") ?: return "manca il campo 'text'"
        if (text.trim().length < 2) return "testo troppo corto"
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? =
        arguments.str("text")?.take(160)?.let { "Confermi di eliminare dalla memoria l'appunto su «$it»?" }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments.str("text") ?: return ToolResult.Failure("missing_text")
        val matches = runCatching { memory.findMemories(text) }.getOrDefault(emptyList())
        return when {
            matches.isEmpty() -> ok("spoken" to "Non ho trovato nessun appunto su «$text».")
            matches.size > 1 ->
                ok("spoken" to "Ho più appunti che parlano di «$text»: dimmi più preciso quale eliminare.")
            else -> {
                val removed = runCatching { memory.deleteByText(text) }.getOrNull()
                if (removed != null) ok("spoken" to "Eliminato: ${removed.text}.")
                else ok("spoken" to "Non sono riuscito a eliminarlo.")
            }
        }
    }
}

/**
 * Edits a saved memory: finds the one matching [old] and replaces its text with
 * [new] ("cambia l'appunto sul gelato in mi piace il cioccolato"). Confirming and
 * fail-closed on ambiguity.
 */
class UpdateMemoryTool(private val memory: MemoryIndex) : Tool {
    override val name = "update_memory"
    override val description = "Modifica un appunto della memoria."
    override val policy = ToolPolicy.CONFIRMING_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? {
        val old = arguments.str("old") ?: return "manca il campo 'old'"
        val new = arguments.str("new") ?: return "manca il campo 'new'"
        if (old.trim().length < 2 || new.trim().length < 2) return "testo troppo corto"
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val old = arguments.str("old")?.take(120) ?: return null
        val new = arguments.str("new")?.take(120) ?: return null
        return "Confermi di cambiare l'appunto su «$old» in «$new»?"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val old = arguments.str("old") ?: return ToolResult.Failure("missing_old")
        val new = arguments.str("new") ?: return ToolResult.Failure("missing_new")
        val matches = runCatching { memory.findMemories(old) }.getOrDefault(emptyList())
        return when {
            matches.isEmpty() -> ok("spoken" to "Non ho trovato nessun appunto su «$old».")
            matches.size > 1 -> ok("spoken" to "Ho più appunti su «$old»: specifica quale.")
            else -> {
                val updated = runCatching { memory.updateByText(old, new) }.getOrNull()
                if (updated != null) ok("spoken" to "Aggiornato: ${updated.text}.")
                else ok("spoken" to "Non sono riuscito a modificarlo.")
            }
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
        // Local-first: the archive works without a vault, so never gate on one —
        // just read whatever is saved (locally and, if present, in the vault).
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
