package com.simone.jarvismobile.core.navigation

import java.util.Locale

/** A destination request as spoken/typed, before anything is resolved (spec §15). */
data class SearchQuery(val raw: String) {
    val tokens: List<String> get() = ItalianTextNormalizer.tokens(raw)
}

/**
 * Where a resolved destination's coordinate actually came from, so a caller can
 * phrase its reply honestly ("verso Casa" vs "ho trovato…") instead of treating
 * every hit the same way.
 */
enum class DestinationSource { SAVED_PLACE, EXPLICIT_COORDINATE, OFFLINE_SEARCH }

/**
 * The result of resolving free text (or a category/favourite) to a destination
 * (spec §9, §15). A coordinate is only ever produced by a saved favourite, an
 * explicit "lat,lon" the user typed/dictated, or the offline place index — never
 * invented by a model. [Ambiguous] is this codebase's `AmbiguousDestination`: kept
 * as a case here rather than a separate top-level type so a caller (UI, voice
 * pipeline) exhaustively handles every outcome through one `when`.
 */
sealed interface ResolvedDestination {
    data class Found(val place: Place, val source: DestinationSource) : ResolvedDestination

    /** Several candidates are plausible and none clearly dominates; ask which one. */
    data class Ambiguous(val candidates: List<PlaceHit>) : ResolvedDestination

    /** Nothing in the local index matches this query at all. */
    data class NotFound(val query: String) : ResolvedDestination

    /** The query never reached the index because no installed region covers here. */
    data class OutOfCoverage(val query: String) : ResolvedDestination
}

/**
 * Resolves a destination request through the pipeline spec §9 describes: saved
 * place -> explicit coordinate -> offline search -> ranking -> a single result or
 * an ambiguity. Recall (which [Place]s are even candidates) is the caller's job —
 * Android does the FTS query — so this stays pure `:core` and unit-testable
 * without a database.
 */
object DestinationResolver {

    /**
     * A score gap this wide between the #1 and #2 ranked hit means "clearly the
     * one meant" (typically an [PlaceSearchRanker] exact-match bonus firing);
     * anything narrower is a genuine tie worth asking about.
     */
    private const val DOMINANCE_MARGIN = 0.22

    /**
     * @param regionInstalled false when the query's location (or the user's
     * current position, for a nearby-style query) falls outside every installed
     * offline region — distinguishes "doesn't exist" from "you don't have the
     * map for that" (spec §13). Irrelevant once a favourite/coordinate/search hit
     * is actually found, since those never depend on region coverage to exist.
     */
    fun resolve(
        query: String,
        favorites: List<FavoritePlace>,
        searchCandidates: List<Place>,
        origin: LatLng? = null,
        regionInstalled: Boolean = true,
        limit: Int = 5,
    ): ResolvedDestination {
        val q = query.trim()
        if (q.isEmpty()) return ResolvedDestination.NotFound(q)

        // Spec §9 order: saved places, then an explicit coordinate, then search.
        FavoriteResolver.resolve(q, favorites)?.let { favorite ->
            return ResolvedDestination.Found(
                Place(
                    id = favorite.placeId ?: "favorite:${favorite.kind}",
                    name = favorite.label,
                    category = PlaceCategory.OTHER,
                    location = favorite.location,
                    importance = 1.0,
                ),
                DestinationSource.SAVED_PLACE,
            )
        }

        parseExplicitCoordinate(q)?.let { coordinate ->
            return ResolvedDestination.Found(
                Place(
                    id = "coord:${coordinate.lat},${coordinate.lon}",
                    name = "Posizione ${formatCoordinate(coordinate)}",
                    category = PlaceCategory.OTHER,
                    location = coordinate,
                    importance = 1.0,
                ),
                DestinationSource.EXPLICIT_COORDINATE,
            )
        }

        val hits = PlaceSearchRanker.rank(q, searchCandidates, origin, limit)
        if (hits.isEmpty()) {
            return if (regionInstalled) ResolvedDestination.NotFound(q) else ResolvedDestination.OutOfCoverage(q)
        }
        return decide(hits)
    }

    /**
     * Picks among candidates the search already ranked and recalled — no region
     * lookup here, so a resolver already holding hits from [resolve] can be
     * re-driven after the user answers an ambiguity question.
     */
    fun decide(hits: List<PlaceHit>): ResolvedDestination {
        if (hits.isEmpty()) return ResolvedDestination.NotFound("")
        if (hits.size == 1) return ResolvedDestination.Found(hits[0].place, DestinationSource.OFFLINE_SEARCH)
        val gap = hits[0].score - hits[1].score
        return if (gap >= DOMINANCE_MARGIN) {
            ResolvedDestination.Found(hits[0].place, DestinationSource.OFFLINE_SEARCH)
        } else {
            ResolvedDestination.Ambiguous(hits)
        }
    }

    /** "41.9028, 12.4964" typed or dictated as a raw coordinate pair. */
    private fun parseExplicitCoordinate(text: String): LatLng? {
        val m = COORDINATE_RE.find(text) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLng(lat, lon)
    }

    private fun formatCoordinate(p: LatLng): String =
        String.format(Locale.ROOT, "%.4f, %.4f", p.lat, p.lon)

    private val COORDINATE_RE = Regex("""^(-?\d{1,3}(?:[.,]\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:[.,]\d+)?)$""")
}
