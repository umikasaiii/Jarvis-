package com.simone.jarvismobile.automation.rule

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.automation.DeferredCommand
import com.simone.jarvismobile.background.JarvisNotifications
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.automation.rule.ActionRegistry
import com.simone.jarvismobile.core.automation.rule.ActionSpec
import com.simone.jarvismobile.tools.CommandMatcher
import com.simone.jarvismobile.tools.Match
import com.simone.jarvismobile.tools.ToolOutcome
import com.simone.jarvismobile.tools.ToolRunner
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** How one action ended. [Skipped] is not a failure: it did nothing, on purpose. */
sealed interface ActionOutcome {
    data object Done : ActionOutcome
    data class Skipped(val why: String) : ActionOutcome
    data class Failed(val why: String) : ActionOutcome

    val label: String
        get() = when (this) {
            Done -> "ok"
            is Skipped -> "skip"
            is Failed -> "failed"
        }
}

/**
 * One kind of action, implemented as an independent adapter (§10).
 *
 * Handlers never decide *whether* to run — the gate did that — only *how*. A
 * handler that cannot do its job says so; it must never report success for
 * something that did not happen.
 */
interface ActionHandler {
    val type: String

    /** [dryRun] means: report what would happen, change nothing (§22). */
    suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome
}

/** Looks up the handler for an action type; unknown types are not executed. */
@Singleton
class ActionHandlerRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards ActionHandler>,
) {
    private val byType = handlers.associateBy { it.type }

    fun handler(type: String): ActionHandler? = byType[type]

    /** Action types with no handler bound — shown in diagnostics. */
    fun unhandledTypes(): List<String> =
        ActionRegistry.available().map { it.type }.filterNot { it in byType }
}

// --- Handlers -------------------------------------------------------------

/** A private local notification through the channel the user can silence. */
@Singleton
class NotifyActionHandler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : ActionHandler {
    override val type = ActionRegistry.SHOW_NOTIFICATION

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        val message = spec.param("message") ?: return ActionOutcome.Failed("testo mancante")
        if (dryRun) return ActionOutcome.Skipped("dry-run: mostrerei «${message.take(40)}»")
        return runCatching {
            val notification = JarvisNotifications.styled(
                context = context,
                channelId = JarvisNotifications.CHANNEL_SUGGESTIONS,
                text = message,
            ).build()
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, message.hashCode(), notification)
            ActionOutcome.Done
        }.getOrElse { ActionOutcome.Failed(it.javaClass.simpleName) }
    }

    private companion object {
        const val NOTIFICATION_TAG = "jarvis_automation"
    }
}

/**
 * Says something out loud, obeying the background-speech preference. When
 * speaking is off the message still reaches the user as a notification — an
 * automation must not go silent just because the voice is muted.
 */
@Singleton
class SpeakActionHandler @Inject constructor(
    private val coordinator: SessionCoordinator,
    private val notifier: NotifyActionHandler,
) : ActionHandler {
    override val type = ActionRegistry.SPEAK

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        val message = spec.param("message") ?: return ActionOutcome.Failed("testo mancante")
        if (dryRun) return ActionOutcome.Skipped("dry-run: direi «${message.take(40)}»")
        val spoken = runCatching { coordinator.speakBackgroundResponse(message) }.getOrDefault(false)
        if (spoken) return ActionOutcome.Done
        return notifier.handle(
            ActionSpec(ActionRegistry.SHOW_NOTIFICATION, mapOf("message" to message)),
            dryRun = false,
        )
    }
}

/** Files a dated item in JARVIS's own planner. */
@Singleton
class CreateReminderActionHandler @Inject constructor(
    private val agenda: AgendaRepository,
) : ActionHandler {
    override val type = ActionRegistry.CREATE_REMINDER

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        val text = spec.param("text") ?: return ActionOutcome.Failed("testo mancante")
        val date = spec.param("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        val time = spec.param("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (dryRun) return ActionOutcome.Skipped("dry-run: creerei «$text» il $date")
        val added = runCatching { agenda.add(AgendaEntry(date = date, time = time, text = text)) }
            .getOrDefault(false)
        return if (added) ActionOutcome.Done else ActionOutcome.Failed("agenda non scrivibile")
    }
}

/**
 * Runs an allowlisted device command by feeding its words back through the very
 * same [CommandMatcher] a typed command uses.
 *
 * The rule stores only text. If those words no longer map to a permitted,
 * deferrable tool the action does nothing and says why: there is no path from a
 * stored automation to an arbitrary tool, and no way to smuggle one in by
 * editing the database (ADR 0008, §10, §20).
 */
@Singleton
class RunToolActionHandler @Inject constructor(
    private val tools: ToolRunner,
) : ActionHandler {
    override val type = ActionRegistry.RUN_TOOL

    override suspend fun handle(spec: ActionSpec, dryRun: Boolean): ActionOutcome {
        val command = spec.param("command") ?: return ActionOutcome.Failed("comando mancante")
        val match = CommandMatcher.match(command) as? Match.Run
            ?: return ActionOutcome.Failed("comando non riconosciuto")
        if (match.call.name !in DeferredCommand.ELIGIBLE_TOOLS) {
            Log.w(TAG, "automation_tool_not_allowed ${match.call.name}")
            return ActionOutcome.Failed("comando non permesso in automatico")
        }
        if (dryRun) return ActionOutcome.Skipped("dry-run: eseguirei ${match.call.name}")
        return when (val outcome = runCatching { tools.run(match.call) }.getOrNull()) {
            is ToolOutcome.Done -> ActionOutcome.Done
            is ToolOutcome.Failed -> ActionOutcome.Failed(outcome.code)
            // A confirming tool cannot be confirmed by nobody at 3am.
            is ToolOutcome.NeedsConfirmation ->
                ActionOutcome.Skipped("richiede una conferma, non eseguibile in automatico")
            null -> ActionOutcome.Failed("errore")
        }
    }

    private companion object {
        const val TAG = "JarvisAutomation"
    }
}
