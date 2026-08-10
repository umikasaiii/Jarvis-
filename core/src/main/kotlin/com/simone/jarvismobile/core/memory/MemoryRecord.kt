package com.simone.jarvismobile.core.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a remembered fact is allowed to live. */
@Serializable
enum class MemoryKind {
    /** Conversation-only, app-private and removed by "Nuova conversazione". */
    TEMPORARY,

    /** Deliberately retained in the human-readable Obsidian memory file. */
    PERMANENT,

    /** Retained only after an explicit confirmation and labelled as sensitive. */
    SENSITIVE,
}

/** One editable, stable memory stored in `JARVIS/Memoria.md`. */
@Serializable
data class MemoryRecord(
    val id: String,
    val text: String,
    val kind: MemoryKind = MemoryKind.PERMANENT,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val topics: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val dates: List<String> = emptyList(),
    /** AI-assigned macro-category (one of [MemoryCategories.CANONICAL]) or "". */
    val category: String = "",
)

/**
 * The fixed set of macro-categories the on-device model sorts memories into, so
 * the archive groups like a notes app ("mi piace il gelato" and "la pizza ai
 * funghi" both land in «Cibo e gusti»). Kept small and stable, and pure so it
 * can be unit-tested; the Android layer only runs the model and normalises its
 * answer through [normalize].
 */
object MemoryCategories {
    const val OTHER = "Altro"

    // Standing "list" categories — the running to-do style lists the user asked
    // to always have available (things to watch, places to visit, things to do,
    // things to buy). They come first so a "da comprare …" note lands here rather
    // than in the generic «Soldi e acquisti».
    val LISTS: List<String> = listOf(
        "Da guardare",
        "Da visitare",
        "Da fare",
        "Da comprare",
    )

    val CANONICAL: List<String> = LISTS + listOf(
        "Cibo e gusti",
        "Casa",
        "Lavoro e studio",
        "Salute",
        "Famiglia e amici",
        "Soldi e acquisti",
        "Viaggi e luoghi",
        "Tempo libero",
        "Tecnologia",
        "Impegni e date",
        OTHER,
    )

    // Keyword → canonical, so a model that answers "cibo", "alimentazione" or
    // "Cibo e gusti." all map to the same bucket. First match wins, so the list
    // categories are checked before the topical ones.
    private val KEYWORDS: List<Pair<Regex, String>> = listOf(
        Regex("""da\s+guardar|guardar|da\s+veder|\bfilm\b|serie\s*tv|episod|puntat|documentari""") to "Da guardare",
        Regex("""da\s+visitar|visitar|posto\s+da\s+veder|luogo\s+da|da\s+andare\s+a\s+veder""") to "Da visitare",
        Regex("""da\s+fare|cose?\s+da\s+fare|faccend|commission""") to "Da fare",
        Regex("""da\s+comprar|comprar|acquistar|da\s+prender|lista\s+(della\s+)?spesa|\bspesa\b""") to "Da comprare",
        Regex("""cib|gust|maial|piace|mangi|aliment|bevand|cucin|ristor""") to "Cibo e gusti",
        Regex("""cas|domestic|arred|stanz|giardin""") to "Casa",
        Regex("""lavor|studi|scuol|universit|ufficio|carrier|progett""") to "Lavoro e studio",
        Regex("""salut|medic|farmac|allerg|dott|malatt|dieta|sport|palestr""") to "Salute",
        Regex("""famigl|amic|persona|parent|figl|moglie|marit|fidanzat""") to "Famiglia e amici",
        Regex("""sold|acquist|spes|budget|prezz|pagament|banc|conto""") to "Soldi e acquisti",
        Regex("""viagg|luog|citt|vacanz|hotel|volo|destinazion""") to "Viaggi e luoghi",
        Regex("""tempo\s*liber|hobby|svag|film|serie|music|libr|gioc""") to "Tempo libero",
        Regex("""tecnolog|comput|telefon|app|software|dispositiv|internet""") to "Tecnologia",
        Regex("""impegn|dat|appuntament|scadenz|promemori|calendari""") to "Impegni e date",
    )

    /** Maps a model's free answer to a canonical category, defaulting to [OTHER]. */
    fun normalize(raw: String): String {
        val cleaned = raw.trim().trim('"', '`', '.', ':', ' ').lowercase()
        if (cleaned.isEmpty()) return OTHER
        CANONICAL.firstOrNull { it.lowercase() == cleaned }?.let { return it }
        KEYWORDS.firstOrNull { it.first.containsMatchIn(cleaned) }?.let { return it.second }
        return OTHER
    }
}

/**
 * Human-readable Markdown codec for permanent memories.
 *
 * Obsidian displays the bullet itself. Stable IDs and structured fields live in
 * an adjacent HTML comment, so editing the file remains pleasant and the cache
 * can be rebuilt without a private database. Old `- [timestamp] fact` lines are
 * imported with deterministic legacy IDs.
 */
object MemoryRecordCodec {
    private const val HEADER = "# Memoria di JARVIS"
    private const val PREFIX = "<!-- jarvis-memory-v2:"
    private const val SUFFIX = " -->"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun render(records: List<MemoryRecord>): String = buildString {
        append(HEADER).append("\n\n")
        records.sortedWith(compareBy<MemoryRecord> { it.createdAt }.thenBy { it.id }).forEach { record ->
            append(PREFIX)
            append(json.encodeToString(MemoryRecord.serializer(), record.normalized()))
            append(SUFFIX).append('\n')
            append("- ")
            if (record.kind == MemoryKind.SENSITIVE) append("🔒 ")
            append(oneLine(record.text)).append("\n\n")
        }
    }

    fun parse(raw: String): List<MemoryRecord> {
        if (raw.isBlank()) return emptyList()
        val lines = raw.replace("\r\n", "\n").lines()
        val out = ArrayList<MemoryRecord>()
        var pending: MemoryRecord? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith(PREFIX) && trimmed.endsWith(SUFFIX)) {
                val payload = trimmed.removePrefix(PREFIX).removeSuffix(SUFFIX)
                pending = runCatching {
                    json.decodeFromString(MemoryRecord.serializer(), payload)
                }.getOrNull()
                continue
            }
            if (!trimmed.startsWith("- ")) continue
            val text = trimmed.removePrefix("- ").trim()
            if (text.isBlank()) {
                pending = null
                continue
            }
            val structured = pending
            if (structured != null) {
                val visibleText = if (structured.kind == MemoryKind.SENSITIVE) {
                    text.removePrefix("🔒 ").trim()
                } else {
                    text
                }
                val next = if (oneLine(structured.text) == oneLine(visibleText)) {
                    structured.copy(text = visibleText)
                } else {
                    // The bullet was edited directly in Obsidian. Preserve the
                    // stable ID/type/timestamps, but rebuild searchable fields.
                    val fields = MemoryStructure.extract(visibleText)
                    structured.copy(
                        text = visibleText,
                        topics = fields.topics,
                        people = fields.people,
                        dates = fields.dates,
                    )
                }
                out += next.normalized()
                pending = null
            } else {
                out += legacy(text)
            }
        }
        return out.distinctBy { it.id }
    }

    private fun MemoryRecord.normalized(): MemoryRecord = copy(
        text = oneLine(text),
        topics = topics.map(String::trim).filter(String::isNotEmpty).distinct().take(12),
        people = people.map(String::trim).filter(String::isNotEmpty).distinct().take(12),
        dates = dates.map(String::trim).filter(String::isNotEmpty).distinct().take(12),
    )

    private fun legacy(line: String): MemoryRecord {
        val stamp = LEGACY_STAMP.find(line)?.groupValues?.getOrNull(1)
        val body = line.replace(LEGACY_STAMP, "").trim().ifBlank { line.trim() }
        val created = stamp?.let {
            runCatching {
                LocalDateTime.parse(it, LEGACY_TIME)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        } ?: 0L
        val fields = MemoryStructure.extract(body)
        return MemoryRecord(
            id = "legacy-${stableId(line)}",
            text = body,
            kind = MemoryKind.PERMANENT,
            createdAt = created,
            updatedAt = created,
            topics = fields.topics,
            people = fields.people,
            dates = fields.dates,
        )
    }

    private fun oneLine(value: String): String = value.replace(Regex("""\s+"""), " ").trim()

    /** Dependency-free FNV-1a so legacy IDs remain stable across processes/JVMs. */
    private fun stableId(value: String): String {
        var hash = -0x340d631b7bdddcdbL
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    private val LEGACY_STAMP = Regex("""^\[([^]]+)]\s*""")
    private val LEGACY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}

data class MemoryFields(
    val topics: List<String>,
    val people: List<String>,
    val dates: List<String>,
)

/** Small deterministic extractor used by both short and permanent memory. */
object MemoryStructure {
    fun extract(text: String): MemoryFields {
        val dates = DATE.findAll(text)
            .map { it.value.trim().lowercase() }
            .distinct()
            .take(8)
            .toList()
        val people = PERSON.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()
        val topics = tokenize(text)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(8)
        return MemoryFields(topics, people, dates)
    }

    fun containsCredential(text: String): Boolean = CREDENTIAL.containsMatchIn(text.lowercase())

    fun classify(text: String): MemoryKind = when {
        TEMPORARY.containsMatchIn(text.lowercase()) -> MemoryKind.TEMPORARY
        SENSITIVE.containsMatchIn(text.lowercase()) -> MemoryKind.SENSITIVE
        else -> MemoryKind.PERMANENT
    }

    private fun tokenize(value: String): List<String> = value.lowercase()
        .split(NON_WORD)
        .filter { it.length >= 3 && it !in STOPWORDS && it.none(Char::isDigit) }

    private val NON_WORD = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Unicode-aware word boundaries. Java's `\b` is defined over `[A-Za-z0-9_]`,
     * so a trailing accent is NOT a word character and `venerd[ìi]\b` never
     * matches "venerdì" — the boundary check fails between "ì" and the space.
     * Italian needs lookaround on letters/digits instead (see
     * docs/DECISIONS/0004-structured-agenda.md, same bug in the date parser).
     */
    private const val NOT_BEFORE = """(?<![\p{L}\p{Nd}])"""
    private const val NOT_AFTER = """(?![\p{L}\p{Nd}])"""

    private val PERSON = Regex(
        NOT_BEFORE + """(?:con|da|di|a|persona|amico|amica|collega|partner)\s+([A-ZÀ-ÖØ-Þ][\p{L}'’-]{1,30})""",
    )
    private val DATE = Regex(
        NOT_BEFORE + """(?:oggi|domani|dopodomani|stasera|stamattina|stanotte|""" +
            """luned[ìi]|marted[ìi]|mercoled[ìi]|gioved[ìi]|venerd[ìi]|sabato|domenica|""" +
            """\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?|\d{4}-\d{2}-\d{2})""" + NOT_AFTER,
        RegexOption.IGNORE_CASE,
    )
    private val CREDENTIAL = Regex(
        """\b(password|passcode|pin|otp|codice\s+di\s+accesso|token|api[ _-]?key|seed phrase|frase seed)\b""",
    )
    private val SENSITIVE = Regex(
        NOT_BEFORE + """(?:malattia|diagnosi|farmaco|terapia|referto|salute|medic[oa]|conto corrente|iban|""" +
            """carta di credito|documento|codice fiscale|sessual\w*|gravidanza|disabilit[aà])""" + NOT_AFTER,
    )
    private val TEMPORARY = Regex(
        NOT_BEFORE + """(?:solo per (?:questa|la) conversazione|temporane[oa]|per adesso|""" +
            """finch[éeè] non chiudo|non salvare)""" + NOT_AFTER,
    )
    private val STOPWORDS = setOf(
        "che", "con", "del", "della", "delle", "degli", "dei", "per", "una", "uno", "nel", "nella",
        "nelle", "negli", "sono", "come", "cosa", "quando", "dove", "questo", "questa", "quello", "quella",
        "mio", "mia", "miei", "mie", "devo", "voglio", "fare", "fatto", "anche", "solo", "oggi", "domani",
        "ricorda", "ricordami", "annota", "segna", "jarvis", "alla", "alle", "allo", "non", "piu", "più",
    )
}
