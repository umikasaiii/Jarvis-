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
        val out = StringBuilder()
        val runs = ArrayList<StyledRun>()
        var cursor = 0
        for (match in INLINE.findAll(line)) {
            if (match.range.first < cursor) continue // overlapping match, already consumed
            out.append(line, cursor, match.range.first)
            val g = match.groupValues
            val (inner, style) = when {
                g[1].isNotEmpty() -> g[1] to InlineStyle.Bold
                g[3].isNotEmpty() -> g[3] to InlineStyle.Highlight(g[2])
                g[4].isNotEmpty() -> g[4] to InlineStyle.Highlight(DEFAULT_HIGHLIGHT)
                g[6].isNotEmpty() -> g[6] to InlineStyle.TextColor(g[5])
                g[8].isNotEmpty() -> g[8] to (SizeStep.fromTag(g[7])?.let { InlineStyle.FontSize(it) })
                g[9].isNotEmpty() -> g[9] to InlineStyle.Underline
                g[10].isNotEmpty() -> g[10] to InlineStyle.Italic
                else -> null to null
            }
            if (inner != null && style != null) {
                val start = out.length
                out.append(inner)
                runs += StyledRun(start, out.length, style)
            } else {
                out.append(match.value)
            }
            cursor = match.range.last + 1
        }
        out.append(line, cursor, line.length)
        return out.toString() to runs
    }

    private const val CENTER_TAG = "[center]"
    private const val RIGHT_TAG = "[right]"
    private val NUMBERED_PREFIX = Regex("""^\d+\.\s+""")
}
