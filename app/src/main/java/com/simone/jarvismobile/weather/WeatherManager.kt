package com.simone.jarvismobile.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.automation.rule.PlaceRepository
import com.simone.jarvismobile.context.ContextEngine
import com.simone.jarvismobile.core.weather.RainDecision
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of the weather opt-in: resolves a coordinate, fetches a rain
 * forecast, and tells the [ContextEngine] what it found. Off by default; does
 * nothing at all unless the user has explicitly turned the setting on.
 *
 * The coordinate is the user's chosen [SettingsRepository.weatherPlaceId] — one
 * of their saved [PlaceRepository] places — when they picked one (§ Impostazioni
 * › Meteo): a fixed point the forecast is actually meant for, rather than
 * whatever GPS/network happened to have last recorded, which could be a coarse
 * network fix from wherever the phone last got a location update (a trip, a
 * different room's Wi-Fi AP) and not reflect where the user actually is this
 * evening. Falls back to the last-known position only when no place is chosen.
 */
@Singleton
class WeatherManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val source: WeatherSource,
    private val contextEngine: ContextEngine,
    private val places: PlaceRepository,
) {
    /**
     * The coordinate the last [refresh] actually queried, rounded the same
     * ~1km as the request itself — never the exact fix. Exists purely for
     * diagnostics (§ segnalazione dell'utente: "da stamattina ogni prova mi
     * dà sempre CLOUDY anche se non è vero") — a wrong saved place (or a
     * fallback fix from somewhere else entirely) is now visible instead of
     * assumed correct.
     */
    private val _lastQueryPoint = MutableStateFlow<Pair<Double, Double>?>(null)
    val lastQueryPoint: StateFlow<Pair<Double, Double>?> = _lastQueryPoint.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun refresh() {
        if (!settings.weatherEnabled.first()) return
        val point = resolvePoint() ?: run {
            Log.i(TAG, "weather_skip_no_fix")
            _lastQueryPoint.value = null
            return
        }
        _lastQueryPoint.value = point
        val forecast = source.fetchRain(point.first, point.second)
        contextEngine.onWeather(
            rainToday = RainDecision.isRainDay(forecast?.todayCategory, forecast?.todayMillimeters),
            rainTomorrow = RainDecision.isRainDay(forecast?.tomorrowCategory, forecast?.tomorrowMillimeters),
            todayWeather = forecast?.todayCategory,
        )
        Log.i(TAG, "weather_refreshed ok=${forecast != null}")

        // Same periodic tick (§ tema Atena: "il meteo deve essere aggiornato
        // ogni tanto") also keeps the weekly-outlook cache current — reuses
        // the coordinate already resolved above instead of resolving twice.
        // A failed outlook fetch just leaves the previous cache in place,
        // same as any other "unknown stays unknown" fallback in this class.
        val outlook = source.fetchWeeklyOutlook(point.first, point.second)
        if (outlook != null) {
            settings.setWeatherOutlookCache(outlook.toCacheJson(System.currentTimeMillis()))
        }
    }

    /**
     * Today's live conditions plus the next three days (§ tema Ares, blocco
     * Sistema). Reuses the same coordinate resolution as [refresh] — the
     * user's chosen place, or a fresh-enough last-known fix — but is
     * otherwise independent of it: this never writes to [ContextEngine] and
     * never affects [lastQueryPoint] or the rain/no-rain signal, only
     * returns a richer forecast for the UI to render directly. Null when
     * weather is off, no fix is available, or the fetch itself fails —
     * never a guessed outlook.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchWeeklyOutlook(): WeeklyOutlook? {
        if (!settings.weatherEnabled.first()) return null
        val point = resolvePoint() ?: return null
        val outlook = source.fetchWeeklyOutlook(point.first, point.second)
        if (outlook != null) {
            settings.setWeatherOutlookCache(outlook.toCacheJson(System.currentTimeMillis()))
        }
        return outlook
    }

    /**
     * The last cached outlook (§ "salvato temporaneamente in locale"), read
     * synchronously-ish so a screen can show *something* the instant it opens
     * instead of a blank card while [fetchWeeklyOutlook] is still in flight.
     * Never a guess: null when nothing has ever been cached or the stored
     * JSON fails to decode.
     */
    suspend fun cachedOutlook(): WeeklyOutlook? = outlookFromCacheJson(settings.weatherOutlookCache.first())

    /**
     * 24-hour detail for one day (§ tema Atena: tap su un'icona meteo). Same
     * coordinate resolution, but never persisted/cached — it is fetched fresh
     * only when the user actually opens the day-detail sheet, so a value the
     * user never looks at never costs a request.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchHourlyForecast(dayIndex: Int): HourlyForecast? {
        if (!settings.weatherEnabled.first()) return null
        val point = resolvePoint() ?: return null
        return source.fetchHourlyForecast(point.first, point.second, dayIndex)
    }

    /**
     * § FASE 2A.8 RELEASE GATE H — chat-only horizon beyond the home
     * dashboard's fixed 0-3 days: "Che tempo farà tra 10 giorni?" resolves to
     * a real fetch instead of the old "solo 3 giorni" rejection, up to
     * [com.simone.jarvismobile.core.weather.WeatherDaysAhead.MAX_SUPPORTED_DAYS_AHEAD].
     * Deliberately its own method, never touching [fetchWeeklyOutlook]/its
     * cache: the home presentation horizon (today+3, § "ONE weather source
     * of truth" — same [WeatherSource]/[WeatherManager], just a second,
     * additive request shape, not a second manager) is unaffected by this.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchExtendedDay(daysAhead: Int): DayOutlook? {
        if (!settings.weatherEnabled.first()) return null
        val point = resolvePoint() ?: return null
        return source.fetchExtendedDay(point.first, point.second, daysAhead)
    }

    /** The chosen saved place's coordinate, or the last-known fix as a fallback. */
    @SuppressLint("MissingPermission")
    private suspend fun resolvePoint(): Pair<Double, Double>? {
        val placeId = settings.weatherPlaceId.first()
        if (placeId.isNotBlank()) {
            val place = places.byId(placeId)
            if (place != null) return place.latitude to place.longitude
            Log.w(TAG, "weather_place_missing")
        }
        if (!hasFineLocation()) return null
        val fix = lastKnownLocation() ?: return null
        return fix.latitude to fix.longitude
    }

    /**
     * `getLastKnownLocation` hands back whatever fix Android happens to have
     * cached, with no freshness guarantee at all — it could be hours or days
     * old, from a different city if the phone hasn't moved/reconnected since
     * (§ bug reale segnalato dall'utente: un avviso pioggia sbagliato che si
     * ripeteva, e un fix di posizione stantio era una causa reale non ancora
     * controllata). Same staleness discipline already applied to the forecast
     * itself ([com.simone.jarvismobile.context.ContextEngine.WEATHER_STALE_HOURS])
     * — a fix older than [FIX_STALE_MINUTES] is treated as unusable rather
     * than silently checking the weather for wherever the phone last was.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): android.location.Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val fix = providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return null
        val ageMinutes = (System.currentTimeMillis() - fix.time) / 60_000
        if (ageMinutes > FIX_STALE_MINUTES) {
            Log.i(TAG, "weather_fix_stale age_min=$ageMinutes")
            return null
        }
        return fix
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "JarvisWeather"
        const val FIX_STALE_MINUTES = 120L
    }
}
