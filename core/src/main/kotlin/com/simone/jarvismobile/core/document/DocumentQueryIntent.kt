package com.simone.jarvismobile.core.document

/** A document the user referred to in words. */
sealed interface DocRef {
    /** "questo PDF", "il documento appena importato". */
    data object Latest : DocRef
    data class Named(val name: String) : DocRef
}

enum class SearchScope { DOCUMENTS, NOTES, ALL }

/** A request about the user's own documents/notes, recognised deterministically (no LLM). */
sealed interface DocumentQueryIntent {
    data class Search(val topic: String, val doc: DocRef?, val scope: SearchScope) : DocumentQueryIntent
    data class Summarize(val doc: DocRef) : DocumentQueryIntent
    data class Compare(val a: DocRef, val b: DocRef, val topic: String) : DocumentQueryIntent
    data class PageLookup(val topic: String, val doc: DocRef?) : DocumentQueryIntent
}

/**
 * Recognises document questions in the main chat ("Riassumi questo PDF", "Cosa dice il manuale
 * sulla manutenzione?", "Confronta A con B"…). Strictly anchored so ordinary conversation —
 * including "riassumi questo testo: …" with pasted text — is never captured.
 */
object DocumentQueryParser {
    private const val DOC_NOUN = """(?:documento|documenti|pdf|file|manuale|libretto|contratto)"""
    private val latestWords = Regex("""(?iU)\b(?:appena\s+importat\w+|ultim\w+|quest[oa'])""")

    private val compare = listOf(
        Regex("""(?iU)^\s*confronta\s+(?:il\s+|i\s+|lo\s+|la\s+)?(?:documento\s+|file\s+|pdf\s+)?(.+?)\s+(?:con|e)\s+(?:il\s+|lo\s+|la\s+)?(?:documento\s+|file\s+|pdf\s+)?(.+?)\s*$"""),
        Regex("""(?iU)^\s*(?:quali\s+)?differenze\s+(?:ci\s+sono\s+)?tra\s+(?:i\s+documenti\s+|i\s+file\s+)?(.+?)\s+e\s+(?:il\s+)?(.+?)\s*$"""),
    )
    private val summarize = Regex("""(?iU)^\s*(?:riassumi|riassumimi|sintetizza|fammi\s+un\s+riassunto\s+d(?:i|el|ello|ella))\s+(?:il\s+|lo\s+|la\s+|l'|quest[oa]\s+)?(${DOC_NOUN})\s*(.*)$""")
    private val pageLookup = Regex(
        """(?iU)^\s*in\s+quale\s+pagina\s+(?:si\s+parla|trovo|è\s+indicat\w+|è\s+scritt\w+)\s+(?:del(?:l'|lo|la)?|di|dei|delle|degli|sul(?:la|lo|l')?)?\s*(.+?)(?:\s+(?:nel|nello|nella)\s+${DOC_NOUN}\s+(.+?))?\s*[?.!]*\s*$""",
    )
    private val docTopic = Regex(
        """(?iU)^\s*cosa\s+dice\s+(?:il\s+|lo\s+|la\s+|l')?(${DOC_NOUN}\s+.+?|${DOC_NOUN})\s+(?:su|sul|sulla|sullo|sui|sulle|riguardo|in\s+merito\s+a|a\s+proposito\s+di)\s+(.+?)\s*[?.!]*\s*$""",
    )
    private val docOnly = Regex("""(?iU)^\s*(?:cerca|consulta|guarda)\s+(?:nel|nello|nella|sul)\s+(?:mio\s+|miei\s+)?(${DOC_NOUN}\s*.*?)\s*[?.!]*\s*$""")
    private val docsSearch = Regex("""(?iU)^\s*(?:cerca|consulta|controlla|guarda|trova)\s+(?:nei|tra\s+i|in|i)\s+(?:miei\s+)?documenti\s*(?:quando|cosa|dove|se|che|come|per)?\s*(.*?)\s*[?.!]*\s*$""")
    private val docsSearchFull = Regex("""(?iU)^\s*(?:cerca|consulta|controlla|guarda|trova)\s+(?:nei|tra\s+i)\s+(?:miei\s+)?documenti\s+(.+?)\s*[?.!]*\s*$""")
    private val notesSearch = Regex("""(?iU)^\s*(?:cerca|consulta|trova)\s+(?:nei|tra\s+i)\s+(?:miei\s+)?(?:appunti|note)\s+(?:tutte\s+le\s+informazioni\s+(?:sull'argomento|su|sul|sulla)\s+|le\s+informazioni\s+(?:su|sul|sulla)\s+|su\s+)?(.+?)\s*[?.!]*\s*$""")
    private val infoAbout = Regex("""(?iU)^\s*che\s+informazioni\s+(?:hai|ho)\s+(?:su|sul|sulla|sulle|sui|riguardo\s+a?)\s+(.+?)\s*[?.!]*\s*$""")

    fun parse(raw: String): DocumentQueryIntent? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 400) return null

        for (re in compare) re.matchEntire(text)?.let { m ->
            val a = ref(m.groupValues[1]); val b = ref(m.groupValues[2])
            if (a is DocRef.Named && b is DocRef.Named && a.name.isNotEmpty() && b.name.isNotEmpty()) {
                return DocumentQueryIntent.Compare(a, b, "")
            }
        }
        summarize.matchEntire(text)?.let { m ->
            val rest = m.groupValues[2].trim().trim('"', '«', '»', '.', '?', '!')
            return DocumentQueryIntent.Summarize(if (rest.isEmpty() || latestWords.containsMatchIn(rest)) DocRef.Latest else ref(rest))
        }
        pageLookup.matchEntire(text)?.let { m ->
            val topic = m.groupValues[1].trim()
            val doc = m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let(::ref)
            if (topic.length >= 2) return DocumentQueryIntent.PageLookup(topic, doc)
        }
        docTopic.matchEntire(text)?.let { m ->
            val topic = m.groupValues[2].trim()
            if (topic.length >= 2) return DocumentQueryIntent.Search(topic, ref(m.groupValues[1]), SearchScope.DOCUMENTS)
        }
        docOnly.matchEntire(text)?.let { m ->
            return DocumentQueryIntent.Search("", ref(m.groupValues[1]), SearchScope.DOCUMENTS)
        }
        notesSearch.matchEntire(text)?.let { m ->
            val topic = m.groupValues[1].trim()
            if (topic.length >= 2) return DocumentQueryIntent.Search(topic, null, SearchScope.NOTES)
        }
        docsSearchFull.matchEntire(text)?.let { m ->
            val topic = m.groupValues[1].trim()
            if (topic.length >= 2) return DocumentQueryIntent.Search(topic, null, SearchScope.DOCUMENTS)
        }
        docsSearch.matchEntire(text)?.let { return DocumentQueryIntent.Search("", null, SearchScope.DOCUMENTS) }
        infoAbout.matchEntire(text)?.let { m ->
            val topic = m.groupValues[1].trim()
            if (topic.length >= 2) return DocumentQueryIntent.Search(topic, null, SearchScope.ALL)
        }
        return null
    }

    private fun ref(raw: String): DocRef {
        val cleaned = raw.trim().trim('"', '«', '»', '.', '?', '!', ' ')
        return if (cleaned.isEmpty() || latestWords.containsMatchIn(cleaned)) DocRef.Latest else DocRef.Named(cleaned)
    }
}
