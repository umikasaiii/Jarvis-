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
)

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
    private val PERSON = Regex(
        """\b(?:con|da|di|a|persona|amico|amica|collega|partner)\s+([A-ZÀ-ÖØ-Þ][\p{L}'’-]{1,30})""",
    )
    private val DATE = Regex(
        """\b(?:oggi|domani|dopodomani|stasera|stamattina|stanotte|""" +
            """luned[ìi]|marted[ìi]|mercoled[ìi]|gioved[ìi]|venerd[ìi]|sabato|domenica|""" +
            """\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?|\d{4}-\d{2}-\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CREDENTIAL = Regex(
        """\b(password|passcode|pin|otp|codice\s+di\s+accesso|token|api[ _-]?key|seed phrase|frase seed)\b""",
    )
    private val SENSITIVE = Regex(
        """\b(malattia|diagnosi|farmaco|terapia|referto|salute|medic[oa]|conto corrente|iban|""" +
            """carta di credito|documento|codice fiscale|sessual\w*|gravidanza|disabilit[aà])\b""",
    )
    private val TEMPORARY = Regex(
        """\b(solo per (questa|la) conversazione|temporane[oa]|per adesso|finche non chiudo|non salvare)\b""",
    )
    private val STOPWORDS = setOf(
        "che", "con", "del", "della", "delle", "degli", "dei", "per", "una", "uno", "nel", "nella",
        "nelle", "negli", "sono", "come", "cosa", "quando", "dove", "questo", "questa", "quello", "quella",
        "mio", "mia", "miei", "mie", "devo", "voglio", "fare", "fatto", "anche", "solo", "oggi", "domani",
        "ricorda", "ricordami", "annota", "segna", "jarvis", "alla", "alle", "allo", "non", "piu", "più",
    )
}
