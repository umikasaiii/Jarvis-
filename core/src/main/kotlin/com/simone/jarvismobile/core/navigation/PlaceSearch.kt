package com.simone.jarvismobile.core.navigation

/**
 * Italian text normalisation for offline place search: lowercasing, accent
 * folding, punctuation stripping and expansion of the common street-type
 * abbreviations so "P.zza" and "piazza" meet. Pure and deterministic — the same
 * function normalises both the index terms (Android side) and the query.
 */
object ItalianTextNormalizer {
    private val ABBREV = mapOf(
        "p.zza" to "piazza", "pzza" to "piazza", "p za" to "piazza",
        "v.le" to "viale", "vle" to "viale", "c.so" to "corso", "cso" to "corso",
        "str." to "strada", "loc." to "localita", "s." to "san", "ss." to "santi",
        "st." to "stazione",
    )

    fun normalize(text: String): String {
        var s = text.lowercase().trim()
        s = s.replace('à', 'a').replace('è', 'e').replace('é', 'e')
            .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        s = s.replace(Regex("[^a-z0-9 .]"), " ")
        for ((k, v) in ABBREV) s = s.replace(Regex("\\b" + Regex.escape(k) + "\\b"), v)
        s = s.replace(".", " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.length >= 2 }
}

/** A search hit with its blended score and the distance used to rank it. */
data class PlaceHit(val place: Place, val score: Double, val distanceMeters: Double?)

/**
 * Ranks candidate places for a query, blending text relevance, proximity to the
 * user and intrinsic importance (spec §8). The Android layer does the FTS recall
 * from the local SQLite index and hands the candidates here for the final,
 * deterministic ordering — so ranking stays testable off-device.
 *
 * "piazza navona roma" matches Piazza Navona: every query token that appears in
 * the place (name/address, prefix-aware) lifts relevance, and the extra "roma"
 * token still matches its address without penalising the result.
 */
object PlaceSearchRanker {

    private const val W_TEXT = 0.6
    private const val W_DISTANCE = 0.25
    private const val W_IMPORTANCE = 0.15

    /**
     * A full-name match ("piazza venezia" for "Piazza Venezia") should clearly
     * outrank a partial one even before distance/importance are weighed — this is
     * what lets [DestinationResolver] tell "obviously this one" from "ambiguous"
     * (spec §6 item 1).
     */
    private const val EXACT_MATCH_BONUS = 0.5

    /** Small per-part credit for a fuller address ("Via X, 12, Roma" over "Roma"). */
    private const val ADDRESS_PART_BONUS = 0.03
    private const val MAX_ADDRESS_PARTS = 3

    fun rank(
        query: String,
        candidates: List<Place>,
        origin: LatLng? = null,
        limit: Int = 10,
    ): List<PlaceHit> {
        val qTokens = ItalianTextNormalizer.tokens(query)
        if (qTokens.isEmpty()) return emptyList()
        val normalizedQuery = ItalianTextNormalizer.normalize(query)

        val maxDistance = 200_000.0 // beyond this, proximity contributes ~nothing
        val hits = candidates.map { place ->
            val haystack = ItalianTextNormalizer.tokens(place.name + " " + place.address).toHashSet()
            val matched = qTokens.count { q -> haystack.any { it == q || it.startsWith(q) || q.startsWith(it) } }
            val relevance = matched.toDouble() / qTokens.size

            val distance = origin?.let { Geo.distanceMeters(it, place.location) }
            val proximity = distance?.let { (1.0 - (it / maxDistance)).coerceIn(0.0, 1.0) } ?: 0.0

            val exactMatch = ItalianTextNormalizer.normalize(place.name) == normalizedQuery
            val addressParts = if (place.address.isBlank()) 0 else place.address.count { it == ',' } + 1
            val addressBonus = addressParts.coerceAtMost(MAX_ADDRESS_PARTS) * ADDRESS_PART_BONUS

            val score = W_TEXT * relevance + W_DISTANCE * proximity + W_IMPORTANCE * place.importance +
                (if (exactMatch) EXACT_MATCH_BONUS else 0.0) + addressBonus
            PlaceHit(place, score, distance)
        }

        return hits
            .filter { it.score > 0.0 && matchedAtLeastOne(qTokens, it.place) }
            .sortedWith(compareByDescending<PlaceHit> { it.score }.thenBy { it.distanceMeters ?: Double.MAX_VALUE })
            .take(limit)
    }

    /** Nearest place of a category to [origin] (for "distributore più vicino"). */
    fun nearest(category: PlaceCategory, candidates: List<Place>, origin: LatLng): PlaceHit? =
        candidates.asSequence()
            .filter { it.category == category }
            .map { PlaceHit(it, it.importance, Geo.distanceMeters(origin, it.location)) }
            .minByOrNull { it.distanceMeters ?: Double.MAX_VALUE }

    /** Reverse geocode: the closest known place to a coordinate. */
    fun reverse(point: LatLng, candidates: List<Place>): Place? =
        candidates.minByOrNull { Geo.distanceMeters(point, it.location) }

    private fun matchedAtLeastOne(qTokens: List<String>, place: Place): Boolean {
        val haystack = ItalianTextNormalizer.tokens(place.name + " " + place.address).toHashSet()
        return qTokens.any { q -> haystack.any { it == q || it.startsWith(q) || q.startsWith(it) } }
    }
}
