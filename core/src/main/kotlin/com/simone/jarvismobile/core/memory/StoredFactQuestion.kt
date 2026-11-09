package com.simone.jarvismobile.core.memory

import com.simone.jarvismobile.core.regex.PortableRegex

/**
 * BETA 2/8 — recognises the questions that ask for something the user told JARVIS ("qual è la mia targa",
 * "ti avevo detto dove ho parcheggiato"). It is deliberately narrower than [MemoryRouter]'s PERSONAL hints
 * ("il mio ", "la mia " match far too much): a false positive here only adds a "don't invent" note to the
 * prompt, a false negative leaves behaviour unchanged.
 */
object StoredFactQuestion {

    private val PATTERNS = listOf(
        PortableRegex.of("""(?U)\b(ti|te)\s+(avevo|ho)\s+(detto|dato|scritto|raccontato)\b"""),
        PortableRegex.of("""(?U)\bti\s+ricordi\b"""),
        PortableRegex.of("""(?U)\bricordi\s+(che|dove|quando|come|cosa|il|la|i|le|lo)\b"""),
        PortableRegex.of("""(?U)\bcosa\s+(avevo|ho)\s+(detto|scelto|deciso|salvato|segnato|scritto)\b"""),
        PortableRegex.of("""(?U)\b(avevamo|abbiamo)\s+deciso\b|\bavevo\s+scelto\b"""),
        PortableRegex.of("""(?U)\bqual(e|\s*è|\s*e'|\s*era)\b.*\b(mio|mia|miei|mie)\b"""),
        PortableRegex.of("""(?U)\bcome\s+si\s+chiama\b.*\b(mio|mia|miei|mie)\b"""),
        PortableRegex.of("""(?U)\bdove\s+(ho|avevo)\s+(messo|lasciato|parcheggiato|salvato|appoggiato)\b"""),
        PortableRegex.of("""(?U)\bquando\s+(è|e'|era|sono)\b.*\b(mio|mia|miei|mie)\b"""),
    )

    fun matches(question: String): Boolean {
        val q = question.lowercase().replace('’', '\'').trim()
        return q.isNotEmpty() && PATTERNS.any { it.containsMatchIn(q) }
    }

    /** A bracketed instruction placed in front of the user's turn; keeps the answer grounded. */
    fun groundingNote(hasRelevantNotes: Boolean): String =
        if (hasRelevantNotes) {
            "[Domanda su informazioni personali. Rispondi USANDO SOLO gli appunti forniti o ciò che l'utente ha detto " +
                "in questa conversazione. Se non bastano, dillo chiaramente e non inventare.]"
        } else {
            "[Domanda su informazioni personali: non c'è alcun appunto salvato pertinente. Rispondi SOLO con ciò che " +
                "l'utente ha detto in questa conversazione; se non c'è, dì chiaramente che non lo sai e proponi di " +
                "segnarlo. Non inventare.]"
        }
}
