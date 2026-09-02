package com.simone.jarvismobile.core.snapshot

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure data model for JARVIS's unified contextual awareness — built by
 * `app/snapshot/PersonalIntelligenceSnapshotBuilder` from sources that
 * already exist (`ContextEngine`, `AgendaRepository`, `DrivingModeManager`,
 * `MemoryIndex`, `EventQueueStore`, ...) and never duplicated here. Every
 * section is independently optional: a missing/failed source never blocks
 * building the rest (§ richiesta esplicita, "un errore Agenda NON deve
 * impedire la costruzione dello snapshot").
 *
 * **Snapshot ≠ Prompt** (§ vincolo architetturale esplicito): this is a data
 * model only. Turning it into text happens exclusively in
 * `RelevantContextRenderer`, downstream of `RelevantContextSelector` — never
 * here.
 */
data class PersonalIntelligenceSnapshot(
    val snapshotId: String,
    val createdAt: Instant,
    val schemaVersion: Int = SCHEMA_VERSION,
    val temporal: TemporalContext? = null,
    val location: LocationContext? = null,
    val agenda: AgendaContext? = null,
    val driving: DrivingContext? = null,
    val device: DeviceContext? = null,
    val memory: MemoryContext? = null,
    val recentEvents: RecentEventsContext? = null,
    val task: TaskContext? = null,
    val capability: CapabilityContext? = null,
    val sourceSummary: SourceSummary = SourceSummary(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Which sources contributed vs. failed/were absent when this snapshot was built — never a personal-content log. */
data class SourceSummary(
    val available: Set<String> = emptySet(),
    val missing: Set<String> = emptySet(),
)

// --- sections --------------------------------------------------------------

data class TemporalContext(
    val date: LocalDate,
    val time: LocalTime,
    val dayOfWeek: DayOfWeek,
    val dayPeriod: String? = null,
    val timezoneId: String? = null,
    val capturedAt: Instant,
)

enum class PlaceLabel { HOME, WORK, KNOWN_PLACE, TRAVELLING, UNKNOWN }
enum class MovementState { STATIONARY, MOVING, UNKNOWN }

/** Deliberately semantic-only (§ privacy esplicita: "non inviare coordinate GPS quando non necessarie") — never carries lat/lon. */
data class LocationContext(
    val currentPlaceLabel: PlaceLabel,
    val currentPlaceName: String? = null,
    val lastRelevantPlace: String? = null,
    val movementState: MovementState = MovementState.UNKNOWN,
    val capturedAt: Instant,
)

data class AgendaItemSummary(
    val id: String,
    val title: String,
    val whenText: String,
    val minutesUntil: Long? = null,
)

data class AgendaContext(
    val nextEvent: AgendaItemSummary? = null,
    val imminentEvents: List<AgendaItemSummary> = emptyList(),
    val openTasksCount: Int = 0,
    val imminentReminders: List<AgendaItemSummary> = emptyList(),
    val minutesToNextEvent: Long? = null,
    val capturedAt: Instant,
)

data class DrivingContext(
    val isDriving: Boolean = false,
    val destination: String? = null,
    val etaMinutes: Int? = null,
    val remainingDistanceMeters: Int? = null,
    val navigationActive: Boolean = false,
    val relevantDrivingState: String? = null,
    val capturedAt: Instant,
)

enum class NetworkType { WIFI, CELLULAR, NONE, UNKNOWN }

data class DeviceContext(
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val isOnline: Boolean? = null,
    val headphonesConnected: Boolean? = null,
    val carMode: Boolean? = null,
    val capturedAt: Instant,
)

data class MemoryContextItem(val summary: String)

/** Deliberately NOT query-specific RAG (§ richiesta esplicita: "se non esiste ancora un retrieval contestuale affidabile, lasciare il campo predisposto") — a small, general, capped set, never the whole memory store. */
data class MemoryContext(
    val items: List<MemoryContextItem> = emptyList(),
    val capturedAt: Instant,
)

data class RecentEventSummary(
    val type: String,
    val timestampMs: Long,
    val priority: String,
)

/** Already deduplicated/capped by the time it reaches here (§ `RecentEventsSummarizer`) — never every raw Android event. */
data class RecentEventsContext(
    val events: List<RecentEventSummary> = emptyList(),
    val capturedAt: Instant,
)

data class TaskContext(
    val activeTasks: Int = 0,
    val overdueTasks: Int = 0,
    val upcomingTasks: Int = 0,
    val capturedAt: Instant,
)

data class CapabilityContext(
    val localAiAvailable: Boolean = false,
    val coreAvailable: Boolean = false,
    val navigationAvailable: Boolean = false,
    val memoryAvailable: Boolean = false,
    val agendaAvailable: Boolean = false,
    val networkAvailable: Boolean = false,
    val capturedAt: Instant,
)

// --- selection (RelevantContextSelector's output) ---------------------------

enum class SelectionCategory { TEMPORAL, LOCATION, AGENDA, DRIVING, DEVICE, MEMORY, RECENT_EVENTS, TASK, CAPABILITY }

/**
 * Configurable limits `RelevantContextSelector` enforces (§ richiesta
 * esplicita §14). Defaults are deliberately small — this is ambient
 * context, not a data dump.
 */
data class ContextBudget(
    val maxContextItems: Int = 6,
    val maxRecentEvents: Int = 3,
    val maxAgendaItems: Int = 3,
    val maxMemoryItems: Int = 2,
    val maxSerializedCharacters: Int = 1200,
)

/**
 * The trimmed, budget-enforced, privacy-minimized output of
 * `RelevantContextSelector` — what actually reaches an AI engine. Still a
 * data model (§ Snapshot ≠ Prompt), never text.
 */
data class RelevantPersonalContext(
    val temporal: TemporalContext? = null,
    val location: LocationContext? = null,
    val agenda: AgendaContext? = null,
    val driving: DrivingContext? = null,
    val device: DeviceContext? = null,
    val memory: MemoryContext? = null,
    val recentEvents: RecentEventsContext? = null,
    val task: TaskContext? = null,
    val capability: CapabilityContext? = null,
    val selected: Set<SelectionCategory> = emptySet(),
    val skipped: Set<SelectionCategory> = emptySet(),
    val approxSizeChars: Int = 0,
)
