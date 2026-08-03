package com.simone.jarvismobile.core.security

/**
 * Redacts personal / secret material from anything on its way to a log
 * (docs/SECURITY.md §21, docs/PRIVACY.md §23). By default JARVIS logs technical
 * events only — never audio, transcripts, prompts, notes or tokens. This is the
 * last line of defense: even accidental interpolation of user content into a
 * log message is scrubbed here.
 */
object LogRedactor {

    private const val MASK = "[redacted]"

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Auth headers / tokens: consume the keyword, the optional "Bearer"
        // scheme, and the credential itself as a single unit so the secret is
        // never left behind.
        Regex("""(?i)\b(authorization|bearer|token|api[_-]?key|access[_-]?token)\b\s*[:=]?\s*(bearer\s+)?[A-Za-z0-9._\-]+""") to "$1 $MASK",
        // Long-lived Home Assistant style JWTs / opaque tokens (>= 20 word chars).
        Regex("""\b[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b""") to MASK,
        // Emails.
        Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""") to MASK,
        // IPv4 addresses.
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""") to MASK,
        // Phone numbers (Italian and generic international).
        Regex("""(?<!\d)(?:\+?\d[\d .\-]{7,}\d)(?!\d)""") to MASK,
    )

    /** Returns [message] with known sensitive substrings masked. */
    fun redact(message: String): String {
        var out = message
        for ((regex, replacement) in patterns) {
            out = regex.replace(out, replacement)
        }
        return out
    }

    /**
     * Wraps a raw value that must never appear verbatim in logs. Use for user
     * transcripts, note bodies, prompts: [preview] shows only a length + hash so
     * problems can still be correlated without leaking content.
     */
    fun contentPlaceholder(raw: String, label: String = "content"): String {
        val hash = raw.hashCode().toUInt().toString(16)
        return "[$label len=${raw.length} h=$hash]"
    }
}
