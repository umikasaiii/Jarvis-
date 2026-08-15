package com.simone.jarvismobile.tools

import android.content.Context
import com.simone.jarvismobile.core.driving.DrivingExpandedPanel
import com.simone.jarvismobile.core.tools.SensitivityLevel
import com.simone.jarvismobile.core.tools.Tool
import com.simone.jarvismobile.core.tools.ToolPolicy
import com.simone.jarvismobile.core.tools.ToolResult
import com.simone.jarvismobile.driving.DrivingMapsLauncher
import com.simone.jarvismobile.driving.DrivingModeManager
import com.simone.jarvismobile.driving.DrivingNotificationController
import com.simone.jarvismobile.driving.DrivingStartResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private fun drivingOk(vararg pairs: Pair<String, String>): ToolResult =
    ToolResult.Success(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))

private fun JsonObject.drivingText(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

/** "attiva modalità guida" / "attiva modalità moto" (spec §2, §21). */
class StartDrivingModeTool(private val manager: DrivingModeManager) : Tool {
    override val name = "start_driving_mode"
    override val description = "Avvia la Modalità Guida: overlay JARVIS su Google Maps."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 4_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult = when (manager.start()) {
        DrivingStartResult.Started -> drivingOk("spoken" to "Modalità guida attiva.")
        DrivingStartResult.MissingOverlayPermission -> ToolResult.Failure(
            "overlay_permission_required",
            "manca il permesso di sovrapposizione ad altre app",
        )
    }
}

/** "chiudi/termina modalità guida", "esci dalla modalità moto". */
class StopDrivingModeTool(private val manager: DrivingModeManager) : Tool {
    override val name = "stop_driving_mode"
    override val description = "Termina la Modalità Guida."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        manager.stop()
        return drivingOk("spoken" to "Modalità guida terminata.")
    }
}

/**
 * "imposta navigazione per casa/lavoro/X": resolves a saved place or hands the
 * text to Maps as-is, and only *previews* the destination — turn-by-turn does
 * not start until [StartDrivingRouteTool] (spec §4).
 */
class SetDrivingNavigationTool(
    private val context: Context,
    private val manager: DrivingModeManager,
) : Tool {
    override val name = "set_driving_navigation"
    override val description = "Prepara una destinazione in Google Maps senza avviare il percorso."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.drivingText("destination").isNullOrBlank()) "manca la destinazione" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val destination = arguments.drivingText("destination") ?: return ToolResult.Failure("missing_destination")
        val nav = manager.prepareNavigation(destination)
        val query = manager.lastPreparedQuery() ?: destination
        return if (DrivingMapsLauncher.previewDestination(context, query)) {
            drivingOk("spoken" to "Percorso pronto verso ${nav.destinationLabel}. Di' \"avvia percorso\" per partire.")
        } else {
            ToolResult.Failure("maps_unavailable")
        }
    }
}

/**
 * "avvia percorso": starts real turn-by-turn navigation. Never invents an ETA
 * or ready time — Maps does not hand that back to the launching app (spec §4).
 */
class StartDrivingRouteTool(
    private val context: Context,
    private val manager: DrivingModeManager,
) : Tool {
    override val name = "start_driving_route"
    override val description = "Avvia la navigazione turn-by-turn in Google Maps verso la destinazione preparata."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = manager.pendingDestination()
            ?: return ToolResult.Failure("no_destination_prepared", "nessuna destinazione preparata")
        return if (DrivingMapsLauncher.startNavigation(context, query)) {
            manager.markRouteStarted()
            drivingOk("spoken" to "Percorso avviato.")
        } else {
            ToolResult.Failure("maps_unavailable")
        }
    }
}

/** "mostra dettagli messaggi", "mostra Spotify". */
class ShowDrivingPanelTool(private val manager: DrivingModeManager) : Tool {
    override val name = "show_driving_panel"
    override val description = "Espande il pannello messaggi o Spotify nella Modalità Guida."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 2_000L

    override fun validate(arguments: JsonObject): String? =
        if (arguments.drivingText("panel") !in setOf("messages", "media")) "pannello non valido" else null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!manager.state.value.active) {
            return ToolResult.Failure("driving_mode_inactive", "la modalità guida non è attiva")
        }
        val panel = arguments.drivingText("panel")
        val target = if (panel == "messages") DrivingExpandedPanel.MESSAGES else DrivingExpandedPanel.MEDIA
        manager.showPanel(target)
        return drivingOk("spoken" to if (panel == "messages") "Ecco i messaggi." else "Ecco Spotify.")
    }
}

/** "riduci messaggi", "nascondi dettagli messaggi", "riduci Spotify", "chiudi Spotify". */
class HideDrivingPanelTool(private val manager: DrivingModeManager) : Tool {
    override val name = "hide_driving_panel"
    override val description = "Riduce a compatto qualunque pannello espanso nella Modalità Guida."
    override val policy = ToolPolicy.LOW_RISK_WRITE
    override val sensitivity = SensitivityLevel.PUBLIC
    override val requiresNetwork = false
    override val timeoutMs = 2_000L

    override fun validate(arguments: JsonObject): String? = null

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!manager.state.value.active) {
            return ToolResult.Failure("driving_mode_inactive", "la modalità guida non è attiva")
        }
        manager.collapsePanels()
        return drivingOk("spoken" to "Fatto.")
    }
}

/**
 * "rispondi a Marco: arrivo tra dieci minuti" — an actual send via the
 * notification's own `RemoteInput`, so it stays a confirmed
 * [ToolPolicy.EXTERNAL_COMMUNICATION] action like every other outbound
 * message in this app; never reports success Android did not confirm (spec §9).
 */
class ReplyMessageTool(private val notifications: DrivingNotificationController) : Tool {
    override val name = "reply_message"
    override val description = "Risponde a un messaggio ricevuto usando l'azione di risposta rapida della notifica."
    override val policy = ToolPolicy.EXTERNAL_COMMUNICATION
    override val sensitivity = SensitivityLevel.PERSONAL
    override val requiresNetwork = false
    override val timeoutMs = 3_000L

    override fun validate(arguments: JsonObject): String? {
        if (arguments.drivingText("name").isNullOrBlank()) return "manca il destinatario"
        if (arguments.drivingText("text").isNullOrBlank()) return "manca il testo della risposta"
        return null
    }

    override fun confirmationPrompt(arguments: JsonObject): String? {
        val name = arguments.drivingText("name") ?: return null
        val text = arguments.drivingText("text") ?: return null
        return "Confermi di rispondere a $name con “$text”?"
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = arguments.drivingText("name") ?: return ToolResult.Failure("missing_name")
        val text = arguments.drivingText("text") ?: return ToolResult.Failure("missing_text")
        if (!notifications.hasAccess()) return ToolResult.Failure("notification_access_required")
        val match = notifications.snapshot(limit = 20).firstOrNull {
            it.sender.contains(name, ignoreCase = true) || it.app.contains(name, ignoreCase = true)
        } ?: return ToolResult.Failure("no_matching_notification", "nessun messaggio recente da $name")
        return if (notifications.reply(match.id, text)) {
            drivingOk("spoken" to "Ho risposto a ${match.sender}.")
        } else {
            ToolResult.Failure("reply_unsupported", "questa notifica non supporta la risposta rapida")
        }
    }
}
