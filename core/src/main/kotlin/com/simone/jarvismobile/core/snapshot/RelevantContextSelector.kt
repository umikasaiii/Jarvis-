package com.simone.jarvismobile.core.snapshot

import com.simone.jarvismobile.core.ai.AiRequestType
import java.time.Instant

/**
 * Deterministic, rule-based selection from a full [PersonalIntelligenceSnapshot]
 * down to a small, budget-enforced [RelevantPersonalContext] (§ richiesta
 * esplicita: "per questa fase utilizzare regole deterministiche semplici...
 * NON utilizzare un secondo LLM per selezionare il contesto"). Pure, no I/O,
 * no Android dependency — testable in isolation.
 *
 * Category detection is plain keyword matching on the (already lowercased)
 * user text — deliberately not a classifier, matching the same "simple,
 * swappable heuristic" philosophy already used by
 * [com.simone.jarvismobile.core.ai.AiRoutingHeuristic].
 */
object RelevantContextSelector {

    private val DRIVING_KEYWORDS = setOf(
        "guido", "guida", "guidando", "auto", "macchina", "strada", "traffico",
        "naviga", "navigazione", "percorso", "parcheggio", "autostrada", "arrivare",
    )
    private val AGENDA_KEYWORDS = setOf(
        "appuntamento", "appuntamenti", "impegno", "impegni", "agenda", "riunione",
        "riunioni", "promemoria", "evento", "eventi", "calendario", "scadenza", "scadenze",
    )
    private val DEVICE_KEYWORDS = setOf(
        "batteria", "carica", "caricando", "telefono", "dispositivo", "connessione",
        "wifi", "rete", "cuffie", "bluetooth",
    )

    /** Drop order when [ContextBudget.maxContextItems] is exceeded — least essential first, [SelectionCategory.TEMPORAL] never dropped. */
    private val DROP_PRIORITY = listOf(
        SelectionCategory.RECENT_EVENTS,
        SelectionCategory.MEMORY,
        SelectionCategory.TASK,
        SelectionCategory.CAPABILITY,
        SelectionCategory.DEVICE,
        SelectionCategory.DRIVING,
        SelectionCategory.LOCATION,
        SelectionCategory.AGENDA,
    )

    fun select(
        snapshot: PersonalIntelligenceSnapshot,
        requestType: AiRequestType,
        userText: String,
        now: Instant = Instant.now(),
        budget: ContextBudget = ContextBudget(),
    ): RelevantPersonalContext {
        val normalized = userText.lowercase()
        val drivingMatch = DRIVING_KEYWORDS.any { normalized.contains(it) }
        val agendaMatch = AGENDA_KEYWORDS.any { normalized.contains(it) }
        val deviceMatch = DEVICE_KEYWORDS.any { normalized.contains(it) }
        val broad = requestType == AiRequestType.COMPLEX || requestType == AiRequestType.MEMORY

        val wanted = mutableSetOf(SelectionCategory.TEMPORAL, SelectionCategory.CAPABILITY)
        if (drivingMatch) wanted += setOf(SelectionCategory.DRIVING, SelectionCategory.LOCATION)
        if (agendaMatch) wanted += setOf(SelectionCategory.AGENDA, SelectionCategory.LOCATION, SelectionCategory.TASK)
        if (deviceMatch) wanted += SelectionCategory.DEVICE
        if (broad) {
            wanted += setOf(
                SelectionCategory.AGENDA, SelectionCategory.LOCATION, SelectionCategory.MEMORY,
                SelectionCategory.DEVICE, SelectionCategory.RECENT_EVENTS, SelectionCategory.TASK,
            )
            if (snapshot.driving?.isDriving == true) wanted += SelectionCategory.DRIVING
        }
        // Generic conversational request with no matched category: temporal + a little relevant context (§ esempio esplicito).
        if (!drivingMatch && !agendaMatch && !deviceMatch && !broad) wanted += SelectionCategory.MEMORY

        // Full agenda detail only when the user is genuinely asking about it — otherwise minimized (§ §17 esempio).
        val agendaDetailed = agendaMatch

        var temporal = freshOrNull(snapshot.temporal, { it.capturedAt }, SnapshotFreshnessPolicy.TEMPORAL_TTL_MS, now, SelectionCategory.TEMPORAL in wanted)
        var location = freshOrNull(snapshot.location, { it.capturedAt }, SnapshotFreshnessPolicy.LOCATION_TTL_MS, now, SelectionCategory.LOCATION in wanted)
        var driving = freshOrNull(snapshot.driving, { it.capturedAt }, SnapshotFreshnessPolicy.DRIVING_TTL_MS, now, SelectionCategory.DRIVING in wanted)
        var device = freshOrNull(snapshot.device, { it.capturedAt }, SnapshotFreshnessPolicy.DEVICE_TTL_MS, now, SelectionCategory.DEVICE in wanted)
        var capability = freshOrNull(snapshot.capability, { it.capturedAt }, SnapshotFreshnessPolicy.CAPABILITY_TTL_MS, now, SelectionCategory.CAPABILITY in wanted)
        var task = freshOrNull(snapshot.task, { it.capturedAt }, SnapshotFreshnessPolicy.TASK_TTL_MS, now, SelectionCategory.TASK in wanted)

        var agenda = freshOrNull(snapshot.agenda, { it.capturedAt }, SnapshotFreshnessPolicy.AGENDA_TTL_MS, now, SelectionCategory.AGENDA in wanted)?.let { a ->
            if (agendaDetailed) {
                a.copy(imminentEvents = a.imminentEvents.take(budget.maxAgendaItems), imminentReminders = a.imminentReminders.take(budget.maxAgendaItems))
            } else {
                // Minimization (§ esempio esplicito): the fact that matters ("fra 35 minuti") without the full guest list.
                AgendaContext(nextEvent = null, imminentEvents = emptyList(), openTasksCount = a.openTasksCount, imminentReminders = emptyList(), minutesToNextEvent = a.minutesToNextEvent, capturedAt = a.capturedAt)
            }
        }
        var memory = freshOrNull(snapshot.memory, { it.capturedAt }, SnapshotFreshnessPolicy.MEMORY_TTL_MS, now, SelectionCategory.MEMORY in wanted)
            ?.let { it.copy(items = it.items.take(budget.maxMemoryItems)) }
        var recentEvents = freshOrNull(snapshot.recentEvents, { it.capturedAt }, SnapshotFreshnessPolicy.RECENT_EVENTS_TTL_MS, now, SelectionCategory.RECENT_EVENTS in wanted)
            ?.let { it.copy(events = it.events.take(budget.maxRecentEvents)) }

        fun selectedNow(): MutableSet<SelectionCategory> {
            val s = mutableSetOf<SelectionCategory>()
            if (temporal != null) s += SelectionCategory.TEMPORAL
            if (location != null) s += SelectionCategory.LOCATION
            if (agenda != null) s += SelectionCategory.AGENDA
            if (driving != null) s += SelectionCategory.DRIVING
            if (device != null) s += SelectionCategory.DEVICE
            if (memory != null) s += SelectionCategory.MEMORY
            if (recentEvents != null) s += SelectionCategory.RECENT_EVENTS
            if (task != null) s += SelectionCategory.TASK
            if (capability != null) s += SelectionCategory.CAPABILITY
            return s
        }

        // Enforce maxContextItems: drop least-essential categories first, TEMPORAL is never dropped.
        for (candidate in DROP_PRIORITY) {
            if (selectedNow().size <= budget.maxContextItems) break
            when (candidate) {
                SelectionCategory.RECENT_EVENTS -> recentEvents = null
                SelectionCategory.MEMORY -> memory = null
                SelectionCategory.TASK -> task = null
                SelectionCategory.CAPABILITY -> capability = null
                SelectionCategory.DEVICE -> device = null
                SelectionCategory.DRIVING -> driving = null
                SelectionCategory.LOCATION -> location = null
                SelectionCategory.AGENDA -> agenda = null
                SelectionCategory.TEMPORAL -> Unit
            }
        }

        fun currentSize(): Int = estimateSize(temporal, location, agenda, driving, device, memory, recentEvents, task, capability)

        // Enforce maxSerializedCharacters: drop individual list items (never truncate a string mid-way).
        while (currentSize() > budget.maxSerializedCharacters) {
            val trimmedRecent = recentEvents?.events?.isNotEmpty() == true
            if (trimmedRecent) {
                recentEvents = recentEvents!!.let { it.copy(events = it.events.dropLast(1)) }
                if (recentEvents?.events?.isEmpty() == true) recentEvents = null
                continue
            }
            val trimmedMemory = memory?.items?.isNotEmpty() == true
            if (trimmedMemory) {
                memory = memory!!.let { it.copy(items = it.items.dropLast(1)) }
                if (memory?.items?.isEmpty() == true) memory = null
                continue
            }
            val agendaHasExtra = (agenda?.imminentEvents?.size ?: 0) > 1 || (agenda?.imminentReminders?.size ?: 0) > 1
            if (agendaHasExtra) {
                agenda = agenda!!.copy(
                    imminentEvents = agenda!!.imminentEvents.take((agenda!!.imminentEvents.size - 1).coerceAtLeast(0)),
                    imminentReminders = agenda!!.imminentReminders.take((agenda!!.imminentReminders.size - 1).coerceAtLeast(0)),
                )
                continue
            }
            break // nothing left that can be trimmed without truncating a string — accept current size.
        }

        val selected = selectedNow()
        val skipped = SelectionCategory.entries.toSet() - selected

        return RelevantPersonalContext(
            temporal = temporal, location = location, agenda = agenda, driving = driving, device = device,
            memory = memory, recentEvents = recentEvents, task = task, capability = capability,
            selected = selected, skipped = skipped, approxSizeChars = currentSize(),
        )
    }

    private fun <T> freshOrNull(section: T?, capturedAtOf: (T) -> Instant, ttlMs: Long, now: Instant, wanted: Boolean): T? {
        if (!wanted || section == null) return null
        return if (SnapshotFreshnessPolicy.isStale(capturedAtOf(section), ttlMs, now)) null else section
    }

    private fun estimateSize(
        temporal: TemporalContext?, location: LocationContext?, agenda: AgendaContext?, driving: DrivingContext?,
        device: DeviceContext?, memory: MemoryContext?, recentEvents: RecentEventsContext?, task: TaskContext?,
        capability: CapabilityContext?,
    ): Int {
        var n = 0
        if (temporal != null) n += 40
        location?.let { n += 20 + (it.currentPlaceName?.length ?: 0) + (it.lastRelevantPlace?.length ?: 0) }
        agenda?.let { a ->
            n += 20
            a.nextEvent?.let { n += it.title.length + it.whenText.length + 10 }
            n += a.imminentEvents.sumOf { it.title.length + it.whenText.length + 10 }
            n += a.imminentReminders.sumOf { it.title.length + it.whenText.length + 10 }
        }
        driving?.let { n += 20 + (it.destination?.length ?: 0) + (it.relevantDrivingState?.length ?: 0) }
        if (device != null) n += 40
        memory?.let { m -> n += m.items.sumOf { it.summary.length + 5 } }
        recentEvents?.let { r -> n += r.events.sumOf { it.type.length + 15 } }
        if (task != null) n += 30
        if (capability != null) n += 60
        return n
    }
}
