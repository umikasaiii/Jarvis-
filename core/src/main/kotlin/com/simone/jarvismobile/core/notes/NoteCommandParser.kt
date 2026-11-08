package com.simone.jarvismobile.core.notes

/** A note phrase understood without the LLM. Arguments always come from the user's own words. */
sealed interface NoteCommand {
    /** "crea una nota chiamata X" (optionally "…: contenuto"). */
    data class CreateNamed(val title: String, val content: String) : NoteCommand

    /** "aggiungi alla nota X: testo". The note is found by title; ambiguity is resolved by asking. */
    data class Append(val title: String, val text: String) : NoteCommand

    /** "mostrami le note sulla moto". */
    data class Show(val topic: String) : NoteCommand
}

object NoteCommandParser {
    private val create = Regex(
        """(?iU)^\s*(?:crea|scrivi|fai|salva)\s+(?:una\s+)?(?:nuova\s+)?nota\s+(?:chiamata|intitolata|di\s+nome|col\s+titolo|con\s+titolo|dal\s+titolo)\s+(.+?)\s*(?::\s*(.+))?\s*$""",
    )
    private val appendColon = Regex(
        """(?iU)^\s*(?:aggiungi|scrivi|appendi|accoda)\s+(?:alla|nella|sulla|a)\s+nota\s+(?:chiamata\s+|intitolata\s+)?(.+?)\s*:\s*(.+)$""",
    )
    private val appendInline = Regex(
        """(?iU)^\s*(?:aggiungi|appendi|accoda)\s+(.+?)\s+(?:alla|nella)\s+nota\s+(?:chiamata\s+|intitolata\s+)?(.+?)\s*$""",
    )
    private val show = Regex(
        """(?iU)^\s*(?:mostrami|mostra|fammi\s+vedere|cerca|trova)\s+(?:tutte\s+)?le\s+(?:mie\s+)?note\s+(?:sul(?:la|lo|l')?|sui|sugli|sulle|su|riguardanti|relative\s+a|che\s+parlano\s+di|di|del(?:la|lo|l')?)\s*(.+)$""",
    )

    fun parse(raw: String): NoteCommand? {
        val text = raw.trim().trimEnd('.', '!', '?')
        create.matchEntire(text)?.let { m ->
            val title = clean(m.groupValues[1])
            if (title.length >= 2) return NoteCommand.CreateNamed(title, clean(m.groupValues[2]))
        }
        appendColon.matchEntire(text)?.let { m ->
            val t = clean(m.groupValues[1]); val body = clean(m.groupValues[2])
            if (t.length >= 2 && body.isNotEmpty()) return NoteCommand.Append(t, body)
        }
        appendInline.matchEntire(text)?.let { m ->
            val body = clean(m.groupValues[1]); val t = clean(m.groupValues[2])
            if (t.length >= 2 && body.isNotEmpty()) return NoteCommand.Append(t, body)
        }
        show.matchEntire(text)?.let { m ->
            val topic = clean(m.groupValues[1])
            if (topic.length >= 2) return NoteCommand.Show(topic)
        }
        return null
    }

    private fun clean(s: String) = s.trim().trim('«', '»', '"', '\'', ' ', ',', '.')
}
