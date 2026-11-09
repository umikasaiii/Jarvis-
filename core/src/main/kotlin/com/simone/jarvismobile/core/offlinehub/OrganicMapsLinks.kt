package com.simone.jarvismobile.core.offlinehub

import java.util.Locale

data class GeoPoint(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    /** `41.902800,12.496400` — locale independent, fixed precision (~0.1 m). */
    fun text(): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
}

object CoordinateParser {
    private val pair = Regex("""^\s*([+-]?\d{1,3}(?:[.,]\d+)?)\s*(?:[,;]\s*|\s+)([+-]?\d{1,3}(?:[.,]\d+)?)\s*$""")

    /** "41.9028, 12.4964" / "41,9028 12,4964" / "41.9028;12.4964". Null when not a valid coordinate pair. */
    fun parse(raw: String): GeoPoint? {
        val s = raw.trim()
        // "41,9028,12,4964" is ambiguous; only accept a comma as decimal when the pair is separated otherwise.
        val m = pair.matchEntire(s) ?: return null
        val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return GeoPoint(a, b).takeIf { it.isValid }
    }
}

enum class RouteMode(val omType: String, val label: String) {
    CAR("vehicle", "automobile"),
    WALK("pedestrian", "a piedi"),
    BIKE("bicycle", "bicicletta"),
    TRANSIT("transit", "mezzi pubblici"),
}

/**
 * An Intent description that is pure data, so it can be tested without Android: action, URI string and the
 * package it must be sent to. The Android layer turns it into a real `Intent` and checks it resolves.
 */
data class IntentSpec(val action: String, val uri: String, val packageId: String? = null, val mimeType: String? = null)

/**
 * Organic Maps deep links, built exactly as the project's own parser (`libs/map/mwm_url.cpp`) reads them:
 *  - `om://search?query=…`
 *  - `om://map?ll=<lat>,<lon>&n=<name>` (`ll` must come first)
 *  - `om://route?sll=…&saddr=…&dll=…&daddr=…&type=vehicle|pedestrian|bicycle|transit` — **all five parameters,
 *    in this order, are required**, otherwise Organic Maps ignores the link. Opening it *prepares* the route
 *    (preview); the user starts the guidance.
 *  - `geo:` URIs (`geo:lat,lon` shows a point, `geo:0,0?q=text` searches) as the standard-Android fallback.
 * `om://v2/dir` and `om://v2/nav` do not exist in that parser and are NOT used.
 */
object OrganicMapsLinks {
    const val ACTION_VIEW = "android.intent.action.VIEW"

    fun search(query: String, packageId: String?): List<IntentSpec> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return listOf(
            IntentSpec(ACTION_VIEW, "om://search?query=${enc(q)}", packageId),
            IntentSpec(ACTION_VIEW, "geo:0,0?q=${enc(q)}", packageId), // standard Android search fallback
        )
    }

    fun show(point: GeoPoint, name: String?, packageId: String?): List<IntentSpec> {
        require(point.isValid) { "invalid coordinates" }
        val label = name?.trim()?.takeIf { it.isNotEmpty() }
        val om = "om://map?ll=${point.text()}" + (label?.let { "&n=${enc(it)}" } ?: "")
        val geo = "geo:${point.text()}" + (label?.let { "?q=${point.text()}(${enc(it)})" } ?: "")
        return listOf(IntentSpec(ACTION_VIEW, om, packageId), IntentSpec(ACTION_VIEW, geo, packageId))
    }

    /** Needs a verified origin AND destination; Organic Maps rejects a route link without both. */
    fun route(from: GeoPoint, fromName: String, to: GeoPoint, toName: String, mode: RouteMode, packageId: String?): IntentSpec {
        require(from.isValid && to.isValid) { "invalid coordinates" }
        val uri = "om://route?sll=${from.text()}&saddr=${enc(fromName.ifBlank { "Partenza" })}" +
            "&dll=${to.text()}&daddr=${enc(toName.ifBlank { "Destinazione" })}&type=${mode.omType}"
        return IntentSpec(ACTION_VIEW, uri, packageId)
    }

    /** RFC 3986 percent-encoding of everything outside the unreserved set; spaces become %20. */
    fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }
}

/** What the user asked of Organic Maps, before any Android is involved. */
sealed interface OrganicRequest {
    data object Open : OrganicRequest
    data class Search(val query: String) : OrganicRequest
    data class Show(val point: GeoPoint, val name: String?) : OrganicRequest
    /** [to] set = verified coordinates; otherwise only [toText] exists and no coordinate may be invented. */
    data class Route(val to: GeoPoint?, val toText: String, val mode: RouteMode, val from: GeoPoint?) : OrganicRequest
}

/** The decision for a request: what to launch and what to tell the user honestly. */
sealed interface OrganicPlan {
    data class Launch(val candidates: List<IntentSpec>, val message: String, val what: String) : OrganicPlan
    data class OpenOnly(val message: String) : OrganicPlan
    data class Refused(val reason: String) : OrganicPlan
}

object OrganicMapsPlanner {
    fun plan(req: OrganicRequest, packageId: String?): OrganicPlan = when (req) {
        OrganicRequest.Open -> OrganicPlan.OpenOnly("Apro Organic Maps. Le mappe devono essere già scaricate nell'app: JARVIS non può saperlo.")
        is OrganicRequest.Search -> {
            val specs = OrganicMapsLinks.search(req.query, packageId)
            if (specs.isEmpty()) OrganicPlan.Refused("Cosa devo cercare su Organic Maps?")
            else OrganicPlan.Launch(specs, "Cerco «${req.query.trim()}» in Organic Maps (ricerca offline dell'app, vicino alla zona mostrata).", "la ricerca")
        }
        is OrganicRequest.Show -> {
            if (!req.point.isValid) OrganicPlan.Refused("Coordinate non valide: la latitudine va da -90 a 90, la longitudine da -180 a 180.")
            else OrganicPlan.Launch(OrganicMapsLinks.show(req.point, req.name, packageId), "Mostro ${req.point.text()} su Organic Maps.", "la visualizzazione di un punto")
        }
        is OrganicRequest.Route -> routePlan(req, packageId)
    }

    private fun routePlan(r: OrganicRequest.Route, packageId: String?): OrganicPlan {
        val to = r.to
        if (to == null) {
            // No verified coordinates: never guess them. Let Organic Maps resolve the text with its own offline search.
            val text = r.toText.trim()
            if (text.isEmpty()) return OrganicPlan.Refused("Dove vuoi andare?")
            return OrganicPlan.Launch(
                OrganicMapsLinks.search(text, packageId),
                "Non ho coordinate verificate per «$text»: apro la ricerca di Organic Maps. Scegli il risultato e tocca «Percorso».",
                "la ricerca",
            )
        }
        if (!to.isValid) return OrganicPlan.Refused("Coordinate di destinazione non valide.")
        val from = r.from
        if (from == null || !from.isValid) {
            return OrganicPlan.Launch(
                OrganicMapsLinks.show(to, r.toText.takeIf { it.isNotBlank() }, packageId),
                "Organic Maps vuole un punto di partenza per creare il percorso e non conosco la tua posizione: " +
                    "mostro la destinazione, tocca «Percorso» (${r.mode.label}).",
                "la visualizzazione di un punto",
            )
        }
        return OrganicPlan.Launch(
            listOf(OrganicMapsLinks.route(from, "Posizione attuale", to, r.toText, r.mode, packageId)),
            "Preparo il percorso (${r.mode.label}) in Organic Maps. È un'anteprima: la navigazione la avvii tu dall'app.",
            "i percorsi (om://route)",
        )
    }
}
