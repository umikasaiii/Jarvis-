package com.simone.jarvismobile.core.knowledge.zim

/** Plain text of an HTML article with `## Heading` markers, ready for [com.simone.jarvismobile.core.knowledge.KnowledgeIndexer]. */
data class ZimArticleText(val title: String?, val text: String)

/**
 * KNOWLEDGE ENGINE — turns the HTML stored in a ZIM into text. Deliberately small and dependency-free: it removes
 * script/style/comment blocks and reference markers, keeps headings (as Markdown `##` so the existing chunker can cite a
 * section) and paragraph/list/table-row boundaries, and decodes entities. It is not an HTML parser and does not need to be:
 * the output only feeds a lexical ranker and a model that is told the text is DATA.
 */
object ZimHtml {
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val SCRIPT = Regex("(?is)<(script|style|noscript|svg|nav)\\b.*?</\\1>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val HEADING = Regex("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>")
    private val BLOCK_END = Regex("(?i)</(p|div|section|li|ul|ol|tr|table|blockquote|dd|dt|figure|header|footer|article)>|<br\\s*/?>")
    private val CELL = Regex("(?i)</t[dh]>")
    private val INLINE = Regex("(?is)</?(a|span|b|i|em|strong|sup|sub|small|abbr|cite|code|u|mark|time|bdi|bdo|wbr|data)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val REF_MARK = Regex("\\[(\\d{1,3}|[a-z]|note \\d+)]")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    fun toText(html: String): ZimArticleText {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { decode(TAG.replace(it, "")).trim() }?.takeIf { it.isNotEmpty() }
        var s = COMMENT.replace(html, " ")
        s = SCRIPT.replace(s, " ")
        s = TITLE.replace(s, " ")
        s = HEADING.replace(s) { m ->
            val level = m.groupValues[1].toInt()
            val text = decode(TAG.replace(m.groupValues[2], "")).trim()
            if (text.isEmpty()) "\n" else "\n\n" + "#".repeat(minOf(level, 6).coerceAtLeast(2)) + " " + text + "\n"
        }
        s = INLINE.replace(s, "") // inline markup must not introduce spaces ("system ." -> "system.")
        s = CELL.replace(s, " | ")
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, " ")
        s = decode(s)
        s = REF_MARK.replace(s, "")
        val lines = s.lines().map { SPACES.replace(it, " ").trim().trim('|').trim() }
        val out = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isEmpty()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank > 0 || l.startsWith("#")) "\n\n" else "\n")
            out.append(l)
            blank = 0
        }
        return ZimArticleText(title, out.toString().trim())
    }

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    private fun cp(c: Int): String = if (c in 1..0x10FFFF) String(Character.toChars(c)) else ""

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "deg" to "°", "times" to "×",
        "agrave" to "à", "egrave" to "è", "eacute" to "é", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
    )
}
