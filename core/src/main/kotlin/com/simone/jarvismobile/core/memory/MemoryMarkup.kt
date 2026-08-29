package com.simone.jarvismobile.core.memory

/** Horizontal alignment for one line/paragraph of a note. */
enum class MarkupAlign { START, CENTER, END }

/** One inline style applied to a [StyledRun] of the line's display text. */
sealed class InlineStyle {
    data object Bold : InlineStyle()
    data object Italic : InlineStyle()
    data object Underline : InlineStyle()

    /** Background highlight. [colorHex] is `#RRGGBB`. */
    data class Highlight(val colorHex: String) : InlineStyle()

    /** Foreground text colour. [colorHex] is `#RRGGBB`. */
    data class TextColor(val colorHex: String) : InlineStyle()

    /** Relative font size step, smallest to largest. */
    data class FontSize(val step: SizeStep) : InlineStyle()
}

enum class SizeStep(val tag: String) {
    SMALL("s"), MEDIUM("m"), LARGE("l"), EXTRA_LARGE("xl");

    companion object {
        fun fromTag(tag: String): SizeStep? = entries.firstOrNull { it.tag == tag }
    }
}

/** A style applied to `text.substring(start, end)` of the owning [MarkupLine]. */
data class StyledRun(val start: Int, val end: Int, val style: InlineStyle)

/**
 * Drives a live "no visible markup characters" editor (§ richiesta esplicita
 * dell'utente: "quando seleziono per esempio in grassetto mi fa asterischi,
 * non li voglio, voglio che mi dia esattamente l'effetto richiesto"): the
 * inline markers (`**`/`*`/`==`/`[color=…]`/`[hl=…]`/`[size=…]`/`<u>`) are
 * stripped from [displayText] entirely (block markers — headings, bullets,
 * checklist, divider, alignment — are left as-is; see the honest limitation
 * on [MemoryMarkup.transform]), and [displayToRaw] lets a cursor placed in
 * the displayed text move correctly in the raw stored text and back, so the
 * underlying `TextFieldValue` selection stays in raw coordinates while the
 * screen shows only the styled result.
 */
data class MarkupTransform(
    val displayText: String,
    val runs: List<StyledRun>,
    private val displayToRaw: List<Int>,
) {
    init {
        require(displayToRaw.size == displayText.length + 1) {
            "displayToRaw must have one entry per display offset (0..length)"
        }
    }

    /** Raw-text cursor offset for a cursor sitting at [displayOffset] in [displayText]. */
    fun rawOffset(displayOffset: Int): Int = displayToRaw[displayOffset.coerceIn(0, displayToRaw.size - 1)]

    /**
     * Display-text cursor offset for a cursor sitting at [rawOffset] in the
     * original raw text — the first display boundary whose raw offset is
     * `>= rawOffset`, so a cursor that was inside a now-stripped marker
     * snaps to just after it rather than landing on stale coordinates.
     */
    fun displayOffset(rawOffset: Int): Int {
        var lo = 0
        var hi = displayToRaw.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (displayToRaw[mid] < rawOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * One parsed line of a note. [text] is the display text with every markup
 * marker already stripped, [runs] are style spans local to that text.
 *
 * A note is `raw.split("\n")` — one [MarkupLine] per physical line — so
 * headings/bullets/checklists/dividers/alignment are inherently per-line,
 * matching how the editor's toolbar already inserts them at line starts.
 */
data class MarkupLine(
    val text: String,
    val runs: List<StyledRun>,
    val align: MarkupAlign = MarkupAlign.START,
    val isHeading1: Boolean = false,
    val isHeading2: Boolean = false,
    val isBullet: Boolean = false,
    val isNumbered: Boolean = false,
    /** null = not a checklist line; true/false = checked/unchecked. */
    val isChecklistChecked: Boolean? = null,
    val isDivider: Boolean = false,
)

/**
 * Pure Markdown-like markup for local-only rich-text notes (§ richiesta
 * esplicita dell'utente, dopo aver tolto la sincronizzazione con Obsidian:
 * evidenziare con più colori, cambiare colore/dimensione del testo,
 * grassetto/corsivo/sottolineato, linea divisoria, elenchi, centratura).
 *
 * Deliberately not nested: a run of text carries at most one inline style.
 * Combining e.g. bold+colour on the same span would need a real span-tree
 * parser; this stays a single left-to-right scan, consistent with the
 * project's existing "insert markup at the cursor" editor pattern rather
 * than a live WYSIWYG editor. See docs update for the honest limitation.
 */
object MemoryMarkup {
    private val INLINE = Regex(
        """\*\*(.+?)\*\*""" + "|" + // 1 bold text
            """\[hl=(#[0-9A-Fa-f]{6})](.+?)\[/hl]""" + "|" + // 2 hl color, 3 hl text
            """==(.+?)==""" + "|" + // 4 default-highlight text
            """\[color=(#[0-9A-Fa-f]{6})](.+?)\[/color]""" + "|" + // 5 color, 6 color text
            """\[size=(s|m|l|xl)](.+?)\[/size]""" + "|" + // 7 size tag, 8 size text
            """<u>(.+?)</u>""" + "|" + // 9 underline text
            """(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""", // 10 italic text
    )

    private const val DEFAULT_HIGHLIGHT = "#FFEB3B"

    fun parse(raw: String): List<MarkupLine> = raw.split("\n").map(::parseLine)

    /** Strips every marker, for compact list previews (e.g. `NoteTile`). */
    fun plainText(raw: String): String = parse(raw).joinToString(" ") { it.text }.trim()

    /**
     * Inline-only transform of the whole (possibly multi-line) [raw] text,
     * for a live WYSIWYG editor (see [MarkupTransform]). Only the inline
     * markers are stripped/mapped here — block markers (`#`/`##`, `- `,
     * `1. `, `- [ ] `, `---`, `[center]`/`[right]`) are deliberately left
     * untouched in the editable text, same scope boundary as the toolbar's
     * "insert at cursor" pattern for those. Safe to run on the full
     * multi-line string in one pass: the inline regex's `.` never matches
     * `\n` (Kotlin's default), so no marker can span across a line break —
     * newlines just pass through like any other unmatched character.
     */
    fun transform(raw: String): MarkupTransform {
        val result = parseInlineWithMap(raw)
        return MarkupTransform(result.text, result.runs, result.map)
    }

    private fun parseLine(rawLine: String): MarkupLine {
        var line = rawLine
        var align = MarkupAlign.START
        val startTrimmed = line.trimStart()
        when {
            startTrimmed.startsWith(CENTER_TAG) -> { align = MarkupAlign.CENTER; line = startTrimmed.removePrefix(CENTER_TAG) }
            startTrimmed.startsWith(RIGHT_TAG) -> { align = MarkupAlign.END; line = startTrimmed.removePrefix(RIGHT_TAG) }
        }

        if (line.trim() == "---") {
            return MarkupLine(text = "", runs = emptyList(), align = align, isDivider = true)
        }

        var isH1 = false
        var isH2 = false
        var isBullet = false
        var isNumbered = false
        var checked: Boolean? = null
        val trimmedStart = line.trimStart()
        when {
            trimmedStart.startsWith("## ") -> { isH2 = true; line = trimmedStart.removePrefix("## ") }
            trimmedStart.startsWith("# ") -> { isH1 = true; line = trimmedStart.removePrefix("# ") }
            trimmedStart.startsWith("- [x] ") -> { checked = true; line = trimmedStart.removePrefix("- [x] ") }
            trimmedStart.startsWith("- [X] ") -> { checked = true; line = trimmedStart.removePrefix("- [X] ") }
            trimmedStart.startsWith("- [ ] ") -> { checked = false; line = trimmedStart.removePrefix("- [ ] ") }
            trimmedStart.startsWith("- ") -> { isBullet = true; line = trimmedStart.removePrefix("- ") }
            NUMBERED_PREFIX.containsMatchIn(trimmedStart) -> {
                isNumbered = true
                line = trimmedStart.replaceFirst(NUMBERED_PREFIX, "")
            }
        }

        val (text, runs) = parseInline(line)
        return MarkupLine(text, runs, align, isH1, isH2, isBullet, isNumbered, checked, false)
    }

    private fun parseInline(line: String): Pair<String, List<StyledRun>> {
        val result = parseInlineWithMap(line)
        return result.text to result.runs
    }

    private class InlineMapResult(val text: String, val runs: List<StyledRun>, val map: List<Int>)

    /**
     * Single-pass scan that both strips/styles inline markers (same rules as
     * the old [parseInline]) AND records, for every offset in the produced
     * [InlineMapResult.text] (0..text.length inclusive), the corresponding
     * offset in [text] — the raw-offset "sentinel" one-past-the-end entry is
     * what makes a cursor placed at the very end of the field map correctly.
     */
    private fun parseInlineWithMap(text: String): InlineMapResult {
        val out = StringBuilder()
        val runs = ArrayList<StyledRun>()
        val map = ArrayList<Int>(text.length + 1)
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first < cursor) continue // overlapping match, already consumed
            for (i in cursor until match.range.first) { out.append(text[i]); map.add(i) }
            val g = match.groupValues
            val (innerRange, style) = when {
                g[1].isNotEmpty() -> match.groups[1]!!.range to InlineStyle.Bold
                g[3].isNotEmpty() -> match.groups[3]!!.range to InlineStyle.Highlight(g[2])
                g[4].isNotEmpty() -> match.groups[4]!!.range to InlineStyle.Highlight(DEFAULT_HIGHLIGHT)
                g[6].isNotEmpty() -> match.groups[6]!!.range to InlineStyle.TextColor(g[5])
                g[8].isNotEmpty() -> match.groups[8]!!.range to (SizeStep.fromTag(g[7])?.let { InlineStyle.FontSize(it) })
                g[9].isNotEmpty() -> match.groups[9]!!.range to InlineStyle.Underline
                g[10].isNotEmpty() -> match.groups[10]!!.range to InlineStyle.Italic
                else -> null to null
            }
            if (innerRange != null && style != null) {
                val start = out.length
                for (i in innerRange) { out.append(text[i]); map.add(i) }
                runs += StyledRun(start, out.length, style)
            } else {
                for (i in match.range) { out.append(text[i]); map.add(i) }
            }
            cursor = match.range.last + 1
        }
        for (i in cursor until text.length) { out.append(text[i]); map.add(i) }
        map.add(text.length)
        return InlineMapResult(out.toString(), runs, map)
    }

    private const val CENTER_TAG = "[center]"
    private const val RIGHT_TAG = "[right]"
    private val NUMBERED_PREFIX = Regex("""^\d+\.\s+""")
}
