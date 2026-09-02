package com.simone.jarvismobile.snapshot.providers

import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.core.agenda.Agenda
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.core.agenda.ReminderSchedule
import com.simone.jarvismobile.core.snapshot.AgendaContext
import com.simone.jarvismobile.core.snapshot.AgendaItemSummary
import com.simone.jarvismobile.core.snapshot.TaskContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads [AgendaRepository]'s already-live `entries` `StateFlow` — never
 * `reload()` (that re-reads disk; the cached in-memory list is exactly what
 * every other screen already treats as current, e.g. `DashboardViewModel`'s
 * `today`/`upcoming` flows) — and mirrors the exact filter/sort recipe those
 * screens already use (`Agenda.filter`+`Agenda.sorted`), rather than
 * inventing a second one. No new database, no new query logic (§ richiesta
 * esplicita: "NON duplicare il database Agenda").
 */
fun interface AgendaContextProvider {
    suspend fun provide(): AgendaContext?
}

/** Same underlying data as [AgendaContextProvider] — a distinct section (§ richiesta esplicita §9), so its own small interface rather than folding into the other. */
fun interface TaskContextProvider {
    suspend fun provide(): TaskContext?
}

private const val PROVIDER_MAX_ITEMS = 10
private val REMINDER_LOOKAHEAD = Duration.ofHours(24)

@Singleton
class DefaultAgendaContextProvider @Inject constructor(
    private val agenda: AgendaRepository,
) : AgendaContextProvider {

    override suspend fun provide(): AgendaContext {
        val today = LocalDate.now()
        val entries = agenda.entries.value
        val upcoming = Agenda.sorted(Agenda.filter(entries, today)).filter { !it.done }
        val next = upcoming.firstOrNull()

        val now = LocalDateTime.now()
        val imminentReminders = entries
            .flatMap { entry -> entry.alerts.mapNotNull { alert -> ReminderSchedule.triggerAt(entry, alert)?.let { entry to it } } }
            .filter { (_, at) -> !at.isBefore(now) && Duration.between(now, at) <= REMINDER_LOOKAHEAD }
            .sortedBy { (_, at) -> at }
            .take(PROVIDER_MAX_ITEMS)
            .map { (entry, at) -> entry.toSummary(today, minutesUntil = Duration.between(now, at).toMinutes()) }

        return AgendaContext(
            nextEvent = next?.toSummary(today, minutesUntil = next.minutesUntilOrNull(now)),
            imminentEvents = upcoming.take(PROVIDER_MAX_ITEMS).map { it.toSummary(today, it.minutesUntilOrNull(now)) },
            openTasksCount = entries.count { !it.done },
            imminentReminders = imminentReminders,
            minutesToNextEvent = next?.minutesUntilOrNull(now),
            capturedAt = Instant.now(),
        )
    }
}

@Singleton
class DefaultTaskContextProvider @Inject constructor(
    private val agenda: AgendaRepository,
) : TaskContextProvider {

    override suspend fun provide(): TaskContext {
        val today = LocalDate.now()
        val entries = agenda.entries.value
        val undone = entries.filter { !it.done }
        return TaskContext(
            activeTasks = undone.size,
            overdueTasks = undone.count { val d = it.date; d != null && d.isBefore(today) },
            upcomingTasks = undone.count { val d = it.date; d != null && !d.isBefore(today) },
            capturedAt = Instant.now(),
        )
    }
}

private fun AgendaEntry.toSummary(today: LocalDate, minutesUntil: Long?): AgendaItemSummary {
    val whenText = Agenda.humanDate(date, today) + (time?.let { " alle ${Agenda.humanTime(it)}" } ?: "")
    return AgendaItemSummary(id = id, title = text, whenText = whenText, minutesUntil = minutesUntil)
}

private fun AgendaEntry.minutesUntilOrNull(now: LocalDateTime): Long? {
    val d = date ?: return null
    val t = time ?: return null
    return Duration.between(now, LocalDateTime.of(d, t)).toMinutes()
}
