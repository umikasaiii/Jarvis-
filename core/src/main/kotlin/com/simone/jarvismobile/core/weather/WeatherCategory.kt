package com.simone.jarvismobile.core.weather

/**
 * The day's dominant weather, classified from Open-Meteo's daily WMO weather
 * code rather than reasoned about from a raw probability aggregate — see
 * [fromWmoCode] for why, and [RainDecision] for how this feeds the rain/no-rain
 * calls the rest of the engine needs.
 *
 * The five buckets match exactly what the proactive morning greeting needs to
 * say ("Buongiorno ☀️/⛅/☁️/🌧️/⛈️" — one emoji per category, § ADR/CLAUDE.md
 * "Personalizzazione meteo"), so this enum is the single source of truth both
 * the greeting and the rain conditions read from.
 */
enum class WeatherCategory {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    RAIN,
    THUNDERSTORM,
    ;

    companion object {
        /**
         * Maps a WMO weather code (Open-Meteo's `weathercode` daily field —
         * https://open-meteo.com/en/docs, table "WMO Weather interpretation
         * codes") to one of the five buckets above. Null when [code] is missing
         * or not one of the documented values, so a bad/absent fetch reads as
         * "unknown" here too, never a guessed category — same three-valued
         * discipline the rest of the engine already follows for weather.
         *
         * Snow codes (71/73/75/77/85/86) fold into [RAIN]: nothing here asked
         * for a dedicated snow state, and "something is falling, dress for it"
         * is the closer approximation than silently calling a snow forecast
         * [CLOUDY]. Fog (45/48) folds into [CLOUDY] for the same reason — no
         * dedicated bucket was requested and it is not rain.
         *
         * Not verified against a real Open-Meteo payload — this environment's
         * network policy blocks api.open-meteo.com (confirmed via the agent
         * proxy status endpoint), same limit already documented for the
         * TomTom integrations. The code table itself is long-standing and
         * documented; first real confirmation happens on-device.
         */
        fun fromWmoCode(code: Int?): WeatherCategory? = when (code) {
            null -> null
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3, 45, 48 -> CLOUDY
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86 -> RAIN
            95, 96, 99 -> THUNDERSTORM
            else -> null
        }
    }
}

/**
 * Short, spoken-friendly Italian label (§ FASE 2A.5-bis, `get_weather` tool)
 * — shares the same five buckets [DashboardScreen.weatherCategoryLabel]
 * already renders in the Ares theme, kept as a small, separate mapping here
 * (pure `:core`, testable) instead of reaching into a Compose UI file from a
 * [com.simone.jarvismobile.core.tools.Tool] implementation.
 */
val WeatherCategory.italianLabel: String
    get() = when (this) {
        WeatherCategory.CLEAR -> "sereno"
        WeatherCategory.PARTLY_CLOUDY -> "parzialmente nuvoloso"
        WeatherCategory.CLOUDY -> "nuvoloso"
        WeatherCategory.RAIN -> "pioggia"
        WeatherCategory.THUNDERSTORM -> "temporale"
    }
