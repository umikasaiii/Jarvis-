package com.simone.jarvismobile.core.offlinehub

import com.simone.jarvismobile.core.regex.PortableRegex

/** A request for Kiwix / Organic Maps / the offline library, recognised deterministically (no LLM). */
sealed interface HubCommand {
    data object OpenKiwix : HubCommand
    data object OpenLibrary : HubCommand
    data class KiwixSearch(val query: String) : HubCommand
    /** A question that can only be answered from inside a ZIM: JARVIS must say it cannot read it. */
    data class ZimQuestion(val topic: String) : HubCommand

    data object OpenOrganicMaps : HubCommand
    data class OrganicSearch(val query: String) : HubCommand
    data class OrganicShow(val point: GeoPoint?, val rawCoordinates: String) : HubCommand
    data class OrganicRoute(val destination: String, val mode: RouteMode) : HubCommand
    /** "apri il navigatore offline": goes to the provider the user chose. */
    data object OpenOfflineNavigator : HubCommand
}

object OfflineHubParser {
    private const val OM = """organic\s*maps?|omaps|organicmaps"""
    private const val KW = """kiwix|wikipedia\s+offline|wiki\s+offline"""
    private val openOm = PortableRegex.of("""(?iU)^\s*(?:apri|avvia|lancia)\s+(?:l'app\s+)?(?:$OM)\s*[.!?]*$""")
    private val openKiwix = PortableRegex.of("""(?iU)^\s*(?:apri|avvia|lancia)\s+(?:l'app\s+)?(?:kiwix|wikipedia\s+offline|wiki\s+offline)\s*[.!?]*$""")
    private val openLibrary = PortableRegex.of("""(?iU)^\s*(?:apri|mostra|vai\s+(?:alla|a))\s+(?:la\s+)?(?:biblioteca(?:\s+offline)?|libreria\s+offline)\s*[.!?]*$""")
    private val openNavigator = PortableRegex.of("""(?iU)^\s*(?:apri|avvia)\s+(?:il\s+)?navigatore\s+offline\s*[.!?]*$""")

    private val omSearch = PortableRegex.of("""(?iU)^\s*(?:cerca|trova|mostrami|cercami)\s+(.+?)\s+(?:su|in|con|usando)\s+(?:$OM)\s*[.!?]*$""")
    private val omShow = PortableRegex.of("""(?iU)^\s*(?:mostra|visualizza|apri|vai\s+a)\s+(?:le\s+coordinate\s+|la\s+posizione\s+)?([-+0-9.,;\s]+?)\s+(?:su|in|con)\s+(?:$OM)\s*[.!?]*$""")
    private val omRoute = PortableRegex.of("""(?iU)^\s*(?:portami|accompagnami|naviga|navigami|indicami\s+la\s+strada|imposta\s+(?:la\s+)?(?:navigazione|percorso))\s+(?:a|verso|fino\s+a|per)\s+(.+?)\s+(?:con|su|usando|in)\s+(?:$OM)\s*(.*?)\s*[.!?]*$""")

    private val kwSearch = PortableRegex.of("""(?iU)^\s*(?:cerca|trova|cercami)\s+(.+?)\s+(?:su|in|con|usando|nel(?:la)?)\s+(?:$KW)\s*[.!?]*$""")
    private val zimQuestion = PortableRegex.of("""(?iU)\b(?:archivio|archivi|file)\s+zim\b|\bzim\b.*\b(?:cosa|quando|chi|dove|come|perch[eé])\b|\b(?:cosa|quando|chi|dove|come|perch[eé])\b.*\b(?:nel(?:lo)?|dal(?:lo)?|dall')\s+(?:archivio\s+)?zim\b""")
    private val wikiOffline = PortableRegex.of("""(?iU)^\s*(?:cosa|che\s+cosa|chi|quando|dove|come)\s+.*\b(?:su|di|del(?:la)?|riguardo)\s+(.+?)\s+(?:su|in|nella|dalla|da)\s+(?:$KW)\s*[?.!]*$""")

    fun parse(raw: String): HubCommand? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 300) return null
        if (openOm.matches(t)) return HubCommand.OpenOrganicMaps
        if (openKiwix.matches(t)) return HubCommand.OpenKiwix
        if (openLibrary.matches(t)) return HubCommand.OpenLibrary
        if (openNavigator.matches(t)) return HubCommand.OpenOfflineNavigator

        omRoute.matchEntire(t)?.let { m ->
            val dest = clean(m.groupValues[1])
            if (dest.length >= 2) return HubCommand.OrganicRoute(dest, modeOf(m.groupValues[2]))
        }
        omShow.matchEntire(t)?.let { m ->
            val coords = m.groupValues[1].trim()
            // Only commands that really contain digits as coordinates; invalid pairs are reported, not guessed.
            if (coords.any { it.isDigit() }) return HubCommand.OrganicShow(CoordinateParser.parse(coords), coords)
        }
        omSearch.matchEntire(t)?.let { m ->
            val q = cleanQuery(m.groupValues[1])
            if (q.length >= 2) return HubCommand.OrganicSearch(q)
        }
        kwSearch.matchEntire(t)?.let { m ->
            val q = clean(m.groupValues[1])
            if (q.length >= 2) return HubCommand.KiwixSearch(q)
        }
        wikiOffline.matchEntire(t)?.let { m -> return HubCommand.ZimQuestion(clean(m.groupValues[1])) }
        if (zimQuestion.containsMatchIn(t)) return HubCommand.ZimQuestion("")
        return null
    }

    private fun modeOf(rest: String): RouteMode {
        val r = rest.lowercase()
        return when {
            Regex("""a\s+piedi|pedonale|camminando""").containsMatchIn(r) -> RouteMode.WALK
            Regex("""bici|bicicletta|in\s+bici""").containsMatchIn(r) -> RouteMode.BIKE
            Regex("""mezzi|trasporti|autobus|treno|tram|metro""").containsMatchIn(r) -> RouteMode.TRANSIT
            else -> RouteMode.CAR
        }
    }

    /** "una farmacia vicino a me" → "farmacia": Organic Maps already searches around the shown/current area. */
    private fun cleanQuery(s: String): String =
        clean(s.replace(PortableRegex.of("""(?iU)\b(?:vicino\s+a\s+me|qui\s+vicino|nei\s+dintorni|nelle\s+vicinanze)\b"""), " ")
            .replace(PortableRegex.of("""(?iU)^\s*(?:una|un|uno|la|il|lo|l'|le|i|gli|dei|delle)\s+"""), ""))

    private fun clean(s: String) = s.trim().trim('«', '»', '"', '\'', ' ', ',', '.').replace(Regex("""\s+"""), " ")
}

/** Resolves a destination to coordinates only when they are really known; never invents them. */
sealed interface DestinationFix {
    data class Verified(val point: GeoPoint, val label: String) : DestinationFix
    /** Only text: Organic Maps must resolve it with its own offline search. */
    data class TextOnly(val text: String) : DestinationFix

    companion object {
        /**
         * [saved] is a saved JARVIS place (home/work/favourite) with its stored coordinates, or null. A text that is
         * itself a valid coordinate pair is also verified. An ambiguous address stays [TextOnly].
         */
        fun of(text: String, saved: Pair<GeoPoint, String>?): DestinationFix {
            if (saved != null && saved.first.isValid) return Verified(saved.first, saved.second)
            CoordinateParser.parse(text)?.let { return Verified(it, text.trim()) }
            return TextOnly(text.trim())
        }
    }
}
