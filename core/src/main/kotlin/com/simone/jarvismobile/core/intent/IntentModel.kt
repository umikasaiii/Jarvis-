package com.simone.jarvismobile.core.intent

/**
 * Structured intent model shared by every input surface (voice, chat, watch).
 *
 * The point of this package is that understanding is a *value*, not a side
 * effect: a parser returns what it understood, with how sure it is, and the app
 * layer decides whether that is enough to act. Nothing here touches Android, a
 * repository or the LLM, so all of it is unit-tested on the JVM.
 */

/** What area of the assistant a request is about. */
enum class Domain {
    CONVERSATION,
    DEVICE,
    APP,
    MEDIA,
    NAVIGATION,
    HOME_AUTOMATION,
    AGENDA,
    NOTE,
    MEMORY,
    AUTOMATION,
    TRANSLATION,
    STATUS,
    SYSTEM,
    UNKNOWN,
}

/** What the user wants done inside that area. */
enum class Action {
    CREATE,
    READ,
    LIST,
    SEARCH,
    UPDATE,
    DELETE,
    MOVE,
    OPEN,
    CLOSE,
    START,
    STOP,
    ENABLE,
    DISABLE,
    SET,
    INCREASE,
    DECREASE,
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    CONFIRM,
    CANCEL,
    QUERY,
    NONE,
}

/** Where an understanding came from — used for logging and for trust decisions. */
enum class IntentSource {
    /** Matched a deterministic pattern; no model involved. */
    FAST_PATH,

    /** Parsed by a structured parser (agenda/notes CRUD). */
    STRUCTURED,

    /** Completed from the conversation (a pronoun bound to the last entity). */
    CONTEXT,

    /** Produced by the local LLM and validated before use. */
    AI,
}

/**
 * Confidence bands. Thresholds live here so the router never invents its own:
 *  - [HIGH] act directly,
 *  - [MEDIUM] act only for read-only work; ask or fall back for writes,
 *  - [LOW] never act — ask the user or hand to the model.
 */
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        const val MEDIUM_AT = 0.55f
        const val HIGH_AT = 0.80f

        fun of(score: Float): Confidence = when {
            score >= HIGH_AT -> HIGH
            score >= MEDIUM_AT -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * Which item an action applies to. [needle] is the user's own words for it
 * (never something invented); [date]/[time] narrow it down when the user said
 * "il dentista *di domani*". Resolution against the real store happens in the
 * app layer, which is also where ambiguity turns into a question.
 */
data class IntentTarget(
    val needle: String = "",
    val date: String? = null,
    val time: String? = null,
    /** True when the target was a pronoun ("spostalo") bound from context. */
    val fromContext: Boolean = false,
) {
    val isEmpty: Boolean get() = needle.isBlank() && date == null && time == null
}

/**
 * The change requested by an UPDATE/MOVE. Absolute [date]/[time] and a relative
 * [shiftMinutes] are mutually exclusive in practice: "alle 17" is absolute,
 * "di due ore" is relative.
 */
data class IntentChanges(
    val date: String? = null,
    val time: String? = null,
    val shiftMinutes: Long? = null,
    val title: String? = null,
    val notes: String? = null,
    val category: String? = null,
) {
    val isEmpty: Boolean
        get() = date == null && time == null && shiftMinutes == null &&
            title == null && notes == null && category == null
}

/**
 * One understood request. [score] is the raw 0..1 certainty; [confidence] is its
 * band. [entities] carries anything extra a specific domain needs without
 * forcing a field onto this class.
 */
data class ResolvedIntent(
    val domain: Domain,
    val action: Action,
    val score: Float,
    val source: IntentSource,
    val rawText: String = "",
    val normalizedText: String = "",
    val target: IntentTarget = IntentTarget(),
    val changes: IntentChanges = IntentChanges(),
    val entities: Map<String, String> = emptyMap(),
) {
    val confidence: Confidence get() = Confidence.of(score)

    /** True when this intent may modify or delete something. */
    val isDestructive: Boolean get() = action == Action.DELETE

    val isWrite: Boolean
        get() = action in setOf(
            Action.CREATE, Action.UPDATE, Action.DELETE, Action.MOVE,
            Action.SET, Action.ENABLE, Action.DISABLE,
        )

    /**
     * Whether this understanding is solid enough to act on without asking.
     * Writes need HIGH; reads are allowed at MEDIUM. A destructive action on a
     * target that was never named is never auto-approved — the app layer still
     * has to resolve it and may still ask.
     */
    fun mayAct(): Boolean = when {
        isDestructive && target.isEmpty -> false
        isWrite -> confidence == Confidence.HIGH
        else -> confidence != Confidence.LOW
    }

    /** Privacy-safe one-liner for logs: never includes the user's text. */
    fun logLine(): String =
        "domain=$domain action=$action confidence=${"%.2f".format(score)} source=$source"
}
