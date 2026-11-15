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
        var s = stripTitle(stripRegions(html))
        s = HEADING.replace(s) { m ->
            val level = m.groupValues[1].toInt()
            val text = decode(TAG.replace(m.groupValues[2], "")).trim()
            if (text.isEmpty()) "\n" else "\n\n" + "#".repeat(minOf(level, 6).coerceAtLeast(2)) + " " + text + "\n"
        }
        s = flattenTags(s)
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

    private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /**
     * Removes comments, `<script|style|noscript|svg|nav>` blocks and the `<title>` element in ONE pass (each becomes a space).
     * Same result as the three regex passes it replaces on every page of the test corpora; the only divergence is a
     * pathological page whose script block contains the START of a comment that ends after the script closes.
     */
    private fun stripRegions(h: String): String {
        if (h.indexOf('<') < 0) return h
        val sb = StringBuilder(h.length)
        var i = 0
        val n = h.length
        while (i < n) {
            val c = h[i]
            if (c != '<') { sb.append(c); i++; continue }
            if (h.startsWith("<!--", i)) {
                val e = h.indexOf("-->", i + 4)
                if (e >= 0) { sb.append(' '); i = e + 3; continue }
            } else {
                var k = i + 1
                while (k < n && isWord(h[k])) k++
                val len = k - (i + 1)
                if (len in 3..8) {
                    val tag = REGION_TAGS.firstOrNull { it.length == len && h.regionMatches(i + 1, it, 0, len, ignoreCase = true) }
                    if (tag != null) {
                        val close = indexOfClose(h, "</$tag>", k)
                        if (close >= 0) { sb.append(' '); i = close + tag.length + 3; continue }
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun indexOfClose(h: String, needle: String, from: Int): Int = h.indexOf(needle, from, ignoreCase = true)

    /** The `<title>` element becomes a space — AFTER the regions are stripped (a title that closes inside a script block is not closed). */
    private fun stripTitle(h: String): String {
        var i = h.indexOf('<')
        if (i < 0) return h
        var sb: StringBuilder? = null
        var copied = 0
        val n = h.length
        while (i in 0 until n) {
            if (h.regionMatches(i + 1, "title", 0, 5, ignoreCase = true)) {
                val gt = h.indexOf('>', i + 6)
                if (gt >= 0) {
                    val close = indexOfClose(h, "</title>", gt + 1)
                    if (close >= 0) {
                        val b = sb ?: StringBuilder(n).also { sb = it }
                        b.append(h, copied, i).append(' ')
                        copied = close + 8
                        i = h.indexOf('<', copied)
                        continue
                    }
                }
            }
            i = h.indexOf('<', i + 1)
        }
        val b = sb ?: return h
        b.append(h, copied, n)
        return b.toString()
    }

    private val INLINE_NAMES = setOf("a", "span", "b", "i", "em", "strong", "sup", "sub", "small", "abbr", "cite", "code", "u", "mark", "time", "bdi", "bdo", "wbr", "data")
    private val BLOCK_NAMES = setOf("p", "div", "section", "li", "ul", "ol", "tr", "table", "blockquote", "dd", "dt", "figure", "header", "footer", "article")

    /**
     * Every remaining tag becomes: nothing (inline markup, so "system ." never appears), " | " (end of a table cell), a newline
     * (end of a block, `<br>`) or a space (anything else) — one pass instead of four regex passes, same output.
     */
    private fun flattenTags(s: String): String {
        if (s.indexOf('<') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c != '<') { sb.append(c); i++; continue }
            val gt = s.indexOf('>', i + 1)
            if (gt < 0) { sb.append(s, i, n); break }
            // a '<' INSIDE a tag (malformed markup) makes the result depend on the order of the regex passes: use them
            val inner = s.indexOf('<', i + 1)
            if (inner in 0 until gt) return flattenTagsByRegex(s)
            var j = i + 1
            val closing = j < gt && s[j] == '/'
            if (closing) j++
            var k = j
            while (k < gt && isWord(s[k])) k++
            val name = s.substring(j, k).lowercase()
            val immediateEnd = k == gt
            when {
                name in INLINE_NAMES -> Unit
                closing && immediateEnd && (name == "td" || name == "th") -> sb.append(" | ")
                closing && immediateEnd && name in BLOCK_NAMES -> sb.append('\n')
                !closing && name == "br" && isBrTail(s, k, gt) -> sb.append('\n')
                else -> sb.append(' ')
            }
            i = gt + 1
        }
        return sb.toString()
    }

    private fun flattenTagsByRegex(s0: String): String {
        var s = INLINE.replace(s0, "")
        s = CELL.replace(s, " | ")
        s = BLOCK_END.replace(s, "\n")
        return TAG.replace(s, " ")
    }

    /** A `br` tag with nothing but whitespace and at most one slash between its name and the closing bracket. */
    private fun isBrTail(s: String, from: Int, gt: Int): Boolean {
        var i = from
        while (i < gt && s[i].isWhitespace()) i++
        if (i < gt && s[i] == '/') i++
        return i == gt
    }

    private val REGION_TAGS = listOf("script", "style", "noscript", "svg", "nav")

    /**
     * The first [neededChars] characters of [toText], computed from a PREFIX of the HTML only. An index keeps just the lead of an
     * article, so converting a 100 KB page to text to keep 1.8 KB is wasted work. The prefix is cut at a tag boundary that never
     * falls inside a block the converter removes (script/style/svg/nav, comments), so the result is the same text the full
     * conversion would start with; if the prefix does not yield enough text the window doubles, up to the whole page.
     * [html] is produced lazily by [window] (a decoder of the first N bytes) so a large page is never decoded in full.
     */
    fun leadText(neededChars: Int, totalLength: Int, window: (Int) -> String): ZimArticleText {
        var size = LEAD_FIRST_WINDOW
        while (true) {
            val whole = size >= totalLength
            val prefix = if (whole) window(totalLength) else safeCut(window(size))
            val text = toText(prefix)
            if (whole || text.text.length >= neededChars + LEAD_MARGIN) return ZimArticleText(text.title, text.text.take(neededChars))
            size *= 4
        }
    }

    private fun safeCut(s: String): String {
        var cut = s.lastIndexOf('>') + 1
        if (cut <= 0) return s
        var changed = true
        while (changed) {
            changed = false
            val head = s.substring(0, cut)
            val openComment = head.lastIndexOf("<!--")
            if (openComment >= 0 && head.indexOf("-->", openComment) < 0) { cut = openComment; changed = true; continue }
            for (tag in REGION_TAGS) {
                val open = lastIndexOfTag(head, "<$tag")
                if (open >= 0 && indexOfIgnoreCase(head, "</$tag", open) < 0) { cut = open; changed = true; break }
            }
        }
        return s.substring(0, cut)
    }

    private fun lastIndexOfTag(s: String, needle: String): Int {
        var i = s.length - needle.length
        while (i >= 0) { if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true) && (i + needle.length >= s.length || !s[i + needle.length].isLetterOrDigit())) return i; i-- }
        return -1
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int = s.indexOf(needle, from, ignoreCase = true)

    private const val LEAD_FIRST_WINDOW = 24 * 1024
    private const val LEAD_MARGIN = 800

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
