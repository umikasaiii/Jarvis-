package com.simone.jarvismobile.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.memory.VaultRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun systemOk(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.systemText(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

private fun Context.launchFixed(intent: Intent): Boolean = runCatching {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
}.getOrDefault(false)

/** Opens only a fixed allowlist of apps or system surfaces. */
class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"
    override val description = "Apre un'app supportata o una schermata di sistema."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val app = arguments.systemText("app")?.let(::normalizeAlias) ?: return "manca il campo 'app'"
        if (app !in SYSTEM_APPS && app !in PACKAGES) return "app non inclusa nell'elenco controllato"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val app = arguments.systemText("app")?.let(::normalizeAlias) ?: return ToolResult.Failure("missing_app")
        val intent = when (app) {
            "fotocamera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "calendario" -> Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI)
            "orologio" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "file" -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            "impostazioni" -> Intent(Settings.ACTION_SETTINGS)
            else -> PACKAGES[app]?.let { context.packageManager.getLaunchIntentForPackage(it) }
        } ?: return ToolResult.Failure("app_unavailable")
        return if (context.launchFixed(intent)) {
            systemOk("app" to app, "spoken" to "Apro $app.")
        } else {
            ToolResult.Failure("app_unavailable")
        }
    }

    companion object {
        val SYSTEM_APPS = setOf("fotocamera", "calendario", "orologio", "file", "impostazioni")
        val PACKAGES = mapOf(
            "whatsapp" to "com.whatsapp",
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "obsidian" to "md.obsidian",
        )

        fun normalizeAlias(value: String): String = value.lowercase()
            .replace("google maps", "maps")
            .replace("google chrome", "chrome")
            .replace("gestore file", "file")
            .replace("files", "file")
            .trim()
    }
}

/** Opens a fixed Android Settings page; it never toggles a setting itself. */
class OpenSettingsTool(private val context: Context) : Tool {
    override val name = "open_settings"
    override val description = "Apre una sezione controllata delle Impostazioni Android."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val area = arguments.systemText("area") ?: return "manca il campo 'area'"
        if (normalizeArea(area) !in ACTIONS) return "sezione impostazioni non supportata"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val area = arguments.systemText("area")?.let(::normalizeArea)
            ?: return ToolResult.Failure("missing_area")
        val action = ACTIONS[area] ?: return ToolResult.Failure("unsupported_settings")
        return if (context.launchFixed(Intent(action))) {
            systemOk("area" to area, "spoken" to "Apro le impostazioni $area.")
        } else {
            ToolResult.Failure("settings_unavailable")
        }
    }

    companion object {
        val ACTIONS = mapOf(
            "generali" to Settings.ACTION_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "schermo" to Settings.ACTION_DISPLAY_SETTINGS,
            "audio" to Settings.ACTION_SOUND_SETTINGS,
            "batteria" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "posizione" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
            "archiviazione" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "notifiche" to Settings.ACTION_NOTIFICATION_SETTINGS,
            "accesso notifiche" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            "voce" to Settings.ACTION_VOICE_INPUT_SETTINGS,
            "assistente" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
        )

        fun normalizeArea(value: String): String = value.lowercase()
            .replace("wi-fi", "wifi")
            .replace("display", "schermo")
            .replace("suono", "audio")
            .replace("tts", "voce")
            .replace("app predefinite", "assistente")
            .trim()
    }
}

/** Opens an editable event draft; Calendar owns the final Save action. */
class CalendarDraftTool(private val context: Context) : Tool {
    override val name = "create_calendar_event"
    override val description = "Prepara un evento nel calendario del telefono."
    override val policy = ToolPolicy.EXTERNAL_COMMUNICATION
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 4_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.systemText("title").isNullOrBlank()) return "manca il titolo"
        val date = arguments.systemText("date") ?: return "manca la data"
        runCatching { LocalDate.parse(date) }.getOrNull() ?: return "data non valida"
        arguments.systemText("time")?.let {
            runCatching { LocalTime.parse(it) }.getOrNull() ?: return "ora non valida"
        }
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val title = arguments.systemText("title")?.take(140) ?: return null
        val date = arguments.systemText("date") ?: return null
        val time = arguments.systemText("time")?.let { " alle $it" }.orEmpty()
        return "Confermi di preparare nel calendario l'evento “$title” per $date$time? Dovrai ancora salvarlo nell'app Calendario."
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.systemText("title") ?: return ToolResult.Failure("missing_title")
        val date = arguments.systemText("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return ToolResult.Failure("missing_date")
        val time = arguments.systemText("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val start = (time?.let { date.atTime(it) } ?: date.atStartOfDay())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = if (time == null) {
            date.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            start + DEFAULT_EVENT_MS
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            arguments.systemText("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            arguments.systemText("description")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, time == null)
        }
        return if (context.launchFixed(intent)) {
            systemOk("spoken" to "Ho aperto la bozza dell'evento. Controllala e premi Salva nel calendario.")
        } else {
            ToolResult.Failure("calendar_unavailable")
        }
    }

    private companion object { const val DEFAULT_EVENT_MS = 60 * 60 * 1_000L }
}

/** Opens the dialer only; ACTION_CALL and CALL_PHONE are deliberately absent. */
class DialDraftTool(private val context: Context) : Tool {
    override val name = "prepare_call"
    override val description = "Prepara un numero nel dialer, senza avviare la chiamata."
    override val policy = ToolPolicy.EXTERNAL_COMMUNICATION
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val number = arguments.systemText("number") ?: return "manca il numero"
        if (!PHONE.matches(number)) return "numero non valido"
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? = arguments.systemText("number")?.let {
        "Confermi di aprire il dialer con il numero $it? La chiamata partirà solo se premi Chiama."
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val number = arguments.systemText("number") ?: return ToolResult.Failure("missing_number")
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        return if (context.launchFixed(intent)) {
            systemOk("spoken" to "Ho aperto il numero nel dialer. Premi Chiama quando sei pronto.")
        } else ToolResult.Failure("dialer_unavailable")
    }

    companion object { val PHONE = Regex("""[+0-9][0-9 ()-]{2,29}""") }
}

/** Opens an SMS draft; the user remains responsible for pressing Send. */
class SmsDraftTool(private val context: Context) : Tool {
    override val name = "compose_sms"
    override val description = "Prepara un SMS, senza inviarlo."
    override val policy = ToolPolicy.EXTERNAL_COMMUNICATION
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        val number = arguments.systemText("number") ?: return "manca il numero"
        if (!DialDraftTool.PHONE.matches(number)) return "numero non valido"
        if (arguments.systemText("body").isNullOrBlank()) return "manca il testo"
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val number = arguments.systemText("number") ?: return null
        val body = arguments.systemText("body")?.take(160) ?: return null
        return "Confermi di preparare per $number l'SMS “$body”? Non verrà inviato automaticamente."
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val number = arguments.systemText("number") ?: return ToolResult.Failure("missing_number")
        val body = arguments.systemText("body") ?: return ToolResult.Failure("missing_body")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", body)
        return if (context.launchFixed(intent)) {
            systemOk("spoken" to "Ho preparato l'SMS. Controllalo e premi Invia nell'app Messaggi.")
        } else ToolResult.Failure("messaging_unavailable")
    }
}

/** Hands a destination to the user's map app. */
class NavigationTool(private val context: Context) : Tool {
    override val name = "navigate"
    override val description = "Apre la navigazione verso una destinazione esplicita."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.systemText("destination").isNullOrBlank()) "manca la destinazione" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val destination = arguments.systemText("destination") ?: return ToolResult.Failure("missing_destination")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
        return if (context.launchFixed(intent)) {
            systemOk("spoken" to "Apro la navigazione verso $destination.")
        } else ToolResult.Failure("maps_unavailable")
    }
}

/** Search and play through an installed media app using the platform voice intent. */
class PlayMediaTool(private val context: Context) : Tool {
    override val name = "play_media"
    override val description = "Cerca e riproduce musica tramite un'app multimediale installata."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.systemText("query").isNullOrBlank()) "manca cosa riprodurre" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.systemText("query") ?: return ToolResult.Failure("missing_query")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        return if (context.launchFixed(intent)) {
            systemOk("spoken" to "Cerco e riproduco $query.")
        } else ToolResult.Failure("media_app_unavailable")
    }
}

/** Controls only the current active media session. */
class MediaControlTool(private val context: Context) : Tool {
    override val name = "media_control"
    override val description = "Mette in pausa, riprende o cambia il brano attivo."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 2_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.systemText("action") !in ACTIONS) return "azione media non valida"
        return null
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            return ToolResult.Failure("notification_access_required")
        }
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return ToolResult.Failure("media_service_unavailable")
        val component = ComponentName(context, JarvisNotificationListener::class.java)
        val controller = runCatching { manager.getActiveSessions(component).firstOrNull() }.getOrNull()
            ?: return ToolResult.Failure("no_active_media")
        val action = arguments.systemText("action") ?: return ToolResult.Failure("missing_action")
        when (action) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
            "toggle" -> {
                val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                if (playing) controller.transportControls.pause() else controller.transportControls.play()
            }
        }
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(100)
        val spoken = when (action) {
            "pause" -> "Riproduzione in pausa."
            "next" -> "Passo al brano successivo."
            "previous" -> "Torno al brano precedente."
            else -> title?.let { "Riproduco $it." } ?: "Riproduzione avviata."
        }
        return systemOk("spoken" to spoken)
    }

    companion object { val ACTIONS = setOf("play", "pause", "next", "previous", "toggle") }
}

/** Reads active notifications only after system grant and an explicit confirmation. */
class ListNotificationsTool(private val context: Context) : Tool {
    override val name = "list_notifications"
    override val description = "Legge selettivamente le notifiche attive autorizzate."
    override val policy = ToolPolicy.SENSITIVE_DEVICE_ACTION
    override val sensitivity = SensitivityLevel.SENSITIVE
    override val requiresNetwork = false
    override val timeoutMs = 2_000L

    override fun validate(arguments: JsonObject): String? = null

    override fun confirmationPrompt(arguments: JsonObject): String =
        "Confermi di leggere le notifiche attive visibili a JARVIS? Il contenuto non verrà salvato."

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            return ToolResult.Failure("notification_access_required")
        }
        val listener = JarvisNotificationListener.instance
            ?: return ToolResult.Failure("notification_listener_unavailable")
        val filter = arguments.systemText("app")
        val items = listener.snapshot(filter, 8)
        val spoken = if (items.isEmpty()) {
            "Non ci sono notifiche attive${filter?.let { " per $it" }.orEmpty()}."
        } else {
            items.joinToString(prefix = "Hai ${items.size} notifiche: ", separator = "; ") {
                listOf(it.title, it.text).filter(String::isNotBlank).joinToString(" — ")
            }
        }
        return systemOk("count" to items.size.toString(), "spoken" to spoken.take(1_200))
    }
}

/** Searches only files in the user-selected vault; no broad storage permission. */
class SearchVaultTool(private val vault: VaultRepository) : Tool {
    override val name = "search_vault"
    override val description = "Cerca file e testo esclusivamente nel vault autorizzato."
    override val policy = ToolPolicy.READ_ONLY
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 8_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.systemText("query").orEmpty().length < 2) "ricerca troppo corta" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.systemText("query") ?: return ToolResult.Failure("missing_query")
        val hits = vault.searchNotes(query, 8)
        val spoken = if (hits.isEmpty()) {
            "Non ho trovato file nel vault per $query."
        } else {
            "Ho trovato ${hits.size} risultati: " + hits.joinToString("; ") { it.path }
        }
        return systemOk("count" to hits.size.toString(), "spoken" to spoken)
    }
}
