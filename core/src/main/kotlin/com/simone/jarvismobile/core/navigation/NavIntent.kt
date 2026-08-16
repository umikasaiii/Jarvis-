package com.simone.jarvismobile.core.navigation

/**
 * A structured navigation command parsed from natural language. Destinations are
 * expressed as a **query** or a **favourite reference**, never as coordinates —
 * the actual coordinate is looked up later in the offline place database, so the
 * language layer can never invent a location (spec §9, §19).
 */
sealed interface NavIntent {
    /** "Portami a Piazza Navona" — resolve [query] against the place index. */
    data class Navigate(val query: String, val options: RouteOptions = RouteOptions()) : NavIntent

    /** "Portami a casa / al lavoro". */
    data class NavigateFavorite(val kind: FavoriteKind) : NavIntent

    /** "Portami al distributore più vicino" — nearest place of a category. */
    data class NavigateNearest(val category: PlaceCategory) : NavIntent

    /** "Trova un parcheggio vicino" — list nearby, don't route yet. */
    data class SearchNearby(val category: PlaceCategory) : NavIntent

    /** "Aggiungi tappa …". */
    data class AddWaypoint(val query: String) : NavIntent

    /** "Evita l'autostrada / i pedaggi" applied to the active/next route. */
    data class SetAvoid(val options: RouteOptions) : NavIntent

    /** "Quanto manca?" / "A che ora arrivo?". */
    data class Status(val kind: StatusKind) : NavIntent

    data object Stop : NavIntent
    data object Pause : NavIntent
    data object Resume : NavIntent

    enum class StatusKind { REMAINING_DISTANCE, ETA }
}

/**
 * Deterministic first-pass parser for navigation phrases. It recognises the
 * common Italian forms directly; an ambiguous free-form destination still becomes
 * a [NavIntent.Navigate] with the raw query, which the place index resolves. It
 * returns null when the utterance isn't a navigation command so the normal
 * assistant pipeline continues.
 */
object NavIntentParser {

    private val CATEGORY_WORDS = listOf(
        setOf("distributore", "benzinaio", "carburante", "benzina", "gpl", "metano") to PlaceCategory.FUEL,
        setOf("parcheggio", "parcheggi", "posteggio") to PlaceCategory.PARKING,
        setOf("ristorante", "ristoranti", "trattoria", "pizzeria") to PlaceCategory.RESTAURANT,
        setOf("bar", "caffe") to PlaceCategory.BAR,
        setOf("farmacia", "farmacie") to PlaceCategory.PHARMACY,
        setOf("ospedale", "ospedali", "pronto", "soccorso") to PlaceCategory.HOSPITAL,
        setOf("hotel", "albergo", "alberghi") to PlaceCategory.HOTEL,
        setOf("stazione", "treni") to PlaceCategory.STATION,
        setOf("aeroporto") to PlaceCategory.AIRPORT,
        setOf("polizia", "carabinieri") to PlaceCategory.POLICE,
        setOf("negozio", "supermercato", "market") to PlaceCategory.SHOP,
    )

    fun parse(raw: String): NavIntent? {
        val q = ItalianTextNormalizer.normalize(raw)
        if (q.isBlank()) return null

        // Control verbs first.
        when {
            containsAny(q, "ferma navigazione", "termina navigazione", "annulla navigazione", "stop navigazione") ->
                return NavIntent.Stop
            containsAny(q, "riprendi navigazione", "riprendi il percorso", "riprendi percorso", "continua navigazione") ->
                return NavIntent.Resume
            containsAny(q, "pausa navigazione", "metti in pausa") ->
                return NavIntent.Pause
            containsAny(q, "quanto manca", "quanta strada", "distanza rimanente") ->
                return NavIntent.Status(NavIntent.StatusKind.REMAINING_DISTANCE)
            containsAny(q, "a che ora arrivo", "quando arrivo", "orario di arrivo", "tra quanto arrivo") ->
                return NavIntent.Status(NavIntent.StatusKind.ETA)
        }

        // Avoidance can appear alone ("evita l'autostrada") or with a destination.
        // Alone (no navigate verb) it just updates the active route's preferences.
        val avoid = parseAvoid(q)
        if (avoid != null && !hasNavigateVerb(q)) return NavIntent.SetAvoid(avoid)

        // Favourites.
        FavoriteResolver.referencesFavorite(raw)?.let { return NavIntent.NavigateFavorite(it) }

        // Nearest / nearby category.
        val category = categoryIn(q)
        if (category != null) {
            val wantsRoute = hasNavigateVerb(q) ||
                containsAny(q, "piu vicino", "piu vicina", "vicino", "vicina")
            val searchOnly = containsAny(q, "trova", "cerca", "mostrami") && !hasNavigateVerb(q)
            return if (wantsRoute && !searchOnly) NavIntent.NavigateNearest(category)
            else NavIntent.SearchNearby(category)
        }

        // Waypoint.
        if (containsAny(q, "aggiungi tappa", "passa da", "fai tappa")) {
            val dest = extractDestination(q)
            if (dest.isNotBlank()) return NavIntent.AddWaypoint(dest)
        }

        // Free-form "portami a / naviga verso / vai a <dest>".
        if (hasNavigateVerb(q)) {
            val dest = extractDestination(q)
            if (dest.isNotBlank()) return NavIntent.Navigate(dest, avoid ?: RouteOptions())
        }
        return null
    }

    private fun parseAvoid(q: String): RouteOptions? {
        val tolls = containsAny(q, "evita pedaggi", "evita i pedaggi", "evita pedaggio", "senza pedaggi")
        val motorways = containsAny(q, "evita autostrada", "evita l autostrada", "evita autostrade", "evita le autostrade", "no autostrada")
        val ferries = containsAny(q, "evita traghetti", "evita traghetto", "evita i traghetti")
        return if (tolls || motorways || ferries) {
            RouteOptions(avoidTolls = tolls, avoidMotorways = motorways, avoidFerries = ferries)
        } else {
            null
        }
    }

    private fun hasNavigateVerb(q: String): Boolean = containsAny(
        q, "portami", "naviga", "vai a", "vai in", "andiamo", "conducimi", "porta mi", "raggiungi",
    )

    private fun categoryIn(q: String): PlaceCategory? {
        val words = q.split(' ').toHashSet()
        return CATEGORY_WORDS.firstOrNull { (keys, _) -> keys.any { it in words } }?.second
    }

    /** Strips the leading verb/preposition to leave the destination phrase. */
    private fun extractDestination(q: String): String {
        var d = q
        for (p in DEST_PREFIXES) {
            val idx = d.indexOf(p)
            if (idx >= 0) { d = d.substring(idx + p.length); break }
        }
        // Drop trailing avoidance clauses.
        for (clause in listOf("evita", "senza pedaggi", "no autostrada")) {
            val idx = d.indexOf(clause)
            if (idx > 0) d = d.substring(0, idx)
        }
        return d.trim().trim('.', ' ')
    }

    private val DEST_PREFIXES = listOf(
        "portami a ", "portami al ", "portami alla ", "portami in ", "portami all ",
        "naviga verso ", "naviga fino a ", "naviga a ", "vai a ", "vai al ", "vai alla ",
        "vai in ", "raggiungi ", "conducimi a ", "aggiungi tappa ", "passa da ", "fai tappa a ",
    )

    /**
     * Word/phrase-boundary containment, not raw substring: a plain `in` check
     * made "naviga" match inside "navigazione" ("imposta *naviga*zione per…"),
     * so a spoken-navigation trigger fired on a completely different phrase
     * ("imposta navigazione per…", which belongs to Modalità Guida's online
     * Maps flow, spec-primary and never the offline one unless asked for) and
     * always failed to find the raw, unstripped sentence in the offline index.
     */
    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(haystack) }
}
