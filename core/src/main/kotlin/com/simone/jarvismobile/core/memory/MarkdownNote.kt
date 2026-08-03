package com.simone.jarvismobile.core.memory

/**
 * A parsed Obsidian Markdown note. This is the readable source of truth for
 * memory (docs/ARCHITECTURE.md §14); the Room index and any embedding cache are
 * always rebuildable from these files.
 */
data class MarkdownNote(
    val path: String,
    val title: String,
    val frontmatter: Map<String, String>,
    val tags: List<String>,
    val wikiLinks: List<String>,
    val body: String,
)

/**
 * Minimal, dependency-free parser for the subset of Obsidian Markdown JARVIS
 * needs: YAML frontmatter (flat key/value + simple lists), the first heading as
 * a title fallback, `#tags`, and `[[wiki links]]`.
 */
object MarkdownParser {

    fun parse(path: String, raw: String): MarkdownNote {
        val (frontmatter, tagsFromFm, body) = extractFrontmatter(raw)
        val title = frontmatter["title"]
            ?: firstHeading(body)
            ?: path.substringAfterLast('/').removeSuffix(".md")
        val inlineTags = INLINE_TAG.findAll(body).map { it.groupValues[1] }.toList()
        val tags = (tagsFromFm + inlineTags).map { it.lowercase() }.distinct()
        val links = WIKI_LINK.findAll(body).map { it.groupValues[1].substringBefore('|').trim() }
            .filter { it.isNotEmpty() }.distinct().toList()
        return MarkdownNote(
            path = path,
            title = title.trim(),
            frontmatter = frontmatter,
            tags = tags,
            wikiLinks = links,
            body = body.trim(),
        )
    }

    private data class Extracted(val fm: Map<String, String>, val tags: List<String>, val body: String)

    private fun extractFrontmatter(raw: String): Extracted {
        val normalized = raw.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return Extracted(emptyMap(), emptyList(), normalized)
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return Extracted(emptyMap(), emptyList(), normalized)
        val block = normalized.substring(4, end)
        val body = normalized.substring(end + 4).removePrefix("\n")

        val map = LinkedHashMap<String, String>()
        val tags = mutableListOf<String>()
        var currentListKey: String? = null
        for (line in block.split('\n')) {
            when {
                line.isBlank() -> currentListKey = null
                line.trimStart().startsWith("- ") && currentListKey != null -> {
                    val item = line.trim().removePrefix("- ").trim().trim('"', '\'')
                    if (currentListKey == "tags") tags += item
                }
                line.contains(':') -> {
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim().trim('"', '\'')
                    currentListKey = key
                    if (value.isEmpty()) continue
                    if (key == "tags") {
                        // inline list form: tags: [a, b] or "a b"
                        value.trim('[', ']').split(',', ' ')
                            .map { it.trim().trim('"', '\'') }
                            .filter { it.isNotEmpty() }
                            .forEach { tags += it }
                    } else {
                        map[key] = value
                    }
                }
            }
        }
        return Extracted(map, tags.distinct(), body)
    }

    private fun firstHeading(body: String): String? =
        body.lineSequence().firstOrNull { it.trimStart().startsWith("#") }
            ?.trimStart('#', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private val INLINE_TAG = Regex("""(?<![\w/])#([A-Za-z0-9_/\-]+)""")
    private val WIKI_LINK = Regex("""\[\[([^\]]+)]]""")
}
