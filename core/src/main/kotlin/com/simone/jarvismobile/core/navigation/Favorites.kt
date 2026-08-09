package com.simone.jarvismobile.core.navigation

enum class FavoriteKind { HOME, WORK, CUSTOM }

/** A saved place. Stored locally only (spec §10, §21) — never uploaded. */
data class FavoritePlace(
    val kind: FavoriteKind,
    val label: String,
    val location: LatLng,
    val placeId: String? = null,
)

/**
 * Resolves "portami a casa" / "al lavoro" (and custom labels) to a stored
 * favourite. Returns null when the favourite isn't set, so the UI can offer to
 * save it rather than guessing a coordinate — the AI never invents one (§9).
 */
object FavoriteResolver {
    private val HOME_PHRASES = listOf("a casa", "verso casa", "portami a casa", "vai a casa", "torna a casa")
    private val WORK_PHRASES = listOf("al lavoro", "in ufficio", "portami al lavoro", "vai al lavoro")

    fun resolve(query: String, favorites: List<FavoritePlace>): FavoritePlace? {
        val q = ItalianTextNormalizer.normalize(query)
        if (HOME_PHRASES.any { ItalianTextNormalizer.normalize(it) in q }) {
            return favorites.firstOrNull { it.kind == FavoriteKind.HOME }
        }
        if (WORK_PHRASES.any { ItalianTextNormalizer.normalize(it) in q }) {
            return favorites.firstOrNull { it.kind == FavoriteKind.WORK }
        }
        // Custom favourites match by their label.
        return favorites.firstOrNull {
            it.kind == FavoriteKind.CUSTOM && it.label.isNotBlank() &&
                ItalianTextNormalizer.normalize(it.label) in q
        }
    }

    /** True if the phrase clearly refers to a favourite (even if unset). */
    fun referencesFavorite(query: String): FavoriteKind? {
        val q = ItalianTextNormalizer.normalize(query)
        return when {
            HOME_PHRASES.any { ItalianTextNormalizer.normalize(it) in q } -> FavoriteKind.HOME
            WORK_PHRASES.any { ItalianTextNormalizer.normalize(it) in q } -> FavoriteKind.WORK
            else -> null
        }
    }
}
