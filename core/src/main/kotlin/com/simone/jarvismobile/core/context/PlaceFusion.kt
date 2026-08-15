package com.simone.jarvismobile.core.context

import java.time.Duration
import java.time.LocalDateTime

/**
 * Turns a stream of noisy location signals into a few honest statements about
 * where the phone is (§2, §3).
 *
 * Three problems are solved here, and each one is a real bug that a naive
 * implementation has:
 *
 * 1. **Several sources, one fact.** A geofence enter, a known Wi-Fi and a "still"
 *    activity are three deliveries of one truth. They are fused into a single
 *    confidence rather than producing three separate arrivals.
 * 2. **Flapping.** GPS near a boundary produces HOME → UNKNOWN → HOME → UNKNOWN
 *    for minutes. Entering and leaving therefore use *different* thresholds
 *    (hysteresis) and leaving must persist for a while (debounce), so a wobble
 *    cannot generate a stream of arrivals and departures.
 * 3. **Passing by.** Driving past home is not arriving home, so a place is only
 *    confirmed after staying for [Config.dwell].
 *
 * The class is pure and time is always passed in, never read from the clock, so
 * all of this is testable without waiting for real minutes to pass.
 */
class PlaceFusion(private val config: Config = Config()) {

    data class Config(
        /** Fused confidence needed to accept an arrival. */
        val enterThreshold: Float = 0.6f,
        /**
         * Confidence below which a departure is considered. Deliberately lower
         * than [enterThreshold]: the gap between the two is the hysteresis that
         * stops a signal hovering around one number from toggling the state.
         */
        val exitThreshold: Float = 0.35f,
        /** How long the evidence must hold before an arrival is announced. */
        val dwell: Duration = Duration.ofMinutes(2),
        /** How long weak evidence must persist before a departure is announced. */
        val exitDebounce: Duration = Duration.ofMinutes(3),
        /** After this long a signal no longer counts towards the fusion. */
        val signalTtl: Duration = Duration.ofMinutes(10),
        /** Minimum gap between two announced transitions for the same place. */
        val cooldown: Duration = Duration.ofMinutes(1),
    ) {
        init {
            require(exitThreshold <= enterThreshold) {
                "exit must not be stricter than enter, or the state would flap"
            }
        }
    }

    private val signals = mutableListOf<PlaceSignal>()

    private var confirmedPlace: String? = null
    private var candidateSince: LocalDateTime? = null
    private var candidatePlace: String? = null
    private var weakSince: LocalDateTime? = null
    private var lastTransitionAt: LocalDateTime? = null

    /** The place currently believed, once confirmed. */
    val place: String? get() = confirmedPlace

    /**
     * Feeds one observation in and returns a transition **only** when something
     * genuinely changed. Most calls return null — that is the point: the engine
     * downstream should be woken for real changes, not for every sensor tick.
     */
    fun observe(signal: PlaceSignal): ContextTransition? {
        signals.removeAll { Duration.between(it.at, signal.at) > config.signalTtl }
        // One reading per source: a source repeating itself is not more evidence.
        signals.removeAll { it.source == signal.source }
        signals += signal

        val best = bestCandidate(signal.at)
        return if (best == null || best.second < config.exitThreshold) {
            considerExit(signal.at, best?.second ?: 0f)
        } else {
            considerEnter(best.first, best.second, signal.at)
        }
    }

    /**
     * Lets time pass without a new signal, so a dwell that is already satisfied
     * can be confirmed. Sensors go quiet exactly when nothing is moving, which
     * is precisely when an arrival should be confirmed.
     */
    fun tick(now: LocalDateTime): ContextTransition? {
        signals.removeAll { Duration.between(it.at, now) > config.signalTtl }
        val best = bestCandidate(now)
        return if (best == null || best.second < config.exitThreshold) {
            considerExit(now, best?.second ?: 0f)
        } else {
            considerEnter(best.first, best.second, now)
        }
    }

    /** Current fused confidence for the confirmed place, 0 when none. */
    fun confidence(now: LocalDateTime): Float =
        confirmedPlace?.let { fusedConfidence(it, now) } ?: 0f

    fun reset() {
        signals.clear()
        confirmedPlace = null
        candidatePlace = null
        candidateSince = null
        weakSince = null
        lastTransitionAt = null
    }

    // --- Internals ------------------------------------------------------

    private fun considerEnter(placeId: String, confidence: Float, now: LocalDateTime): ContextTransition? {
        if (placeId == confirmedPlace) {
            // Still here: any pending departure is cancelled.
            weakSince = null
            candidatePlace = null
            candidateSince = null
            return null
        }
        if (confidence < config.enterThreshold) return null

        if (candidatePlace != placeId) {
            candidatePlace = placeId
            candidateSince = now
            return null
        }
        val since = candidateSince ?: now
        if (Duration.between(since, now) < config.dwell) return null
        if (!cooldownElapsed(now)) return null

        val from = confirmedPlace
        confirmedPlace = placeId
        candidatePlace = null
        candidateSince = null
        weakSince = null
        lastTransitionAt = now
        return ContextTransition(
            from = from,
            to = placeId,
            confidence = confidence,
            at = now,
            reason = "permanenza confermata",
        )
    }

    private fun considerExit(now: LocalDateTime, confidence: Float): ContextTransition? {
        val current = confirmedPlace ?: return null
        if (weakSince == null) {
            weakSince = now
            return null
        }
        if (Duration.between(weakSince, now) < config.exitDebounce) return null
        if (!cooldownElapsed(now)) return null

        confirmedPlace = null
        weakSince = null
        candidatePlace = null
        candidateSince = null
        lastTransitionAt = now
        return ContextTransition(
            from = current,
            to = null,
            confidence = confidence,
            at = now,
            reason = "segnale assente a sufficienza",
        )
    }

    private fun cooldownElapsed(now: LocalDateTime): Boolean {
        val last = lastTransitionAt ?: return true
        return Duration.between(last, now) >= config.cooldown
    }

    /** The place with the strongest fused evidence right now. */
    private fun bestCandidate(now: LocalDateTime): Pair<String, Float>? =
        signals.asSequence()
            .mapNotNull { it.placeId }
            .distinct()
            .map { it to fusedConfidence(it, now) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0f }

    /**
     * Combines the evidence for one place using a noisy-OR: each corroborating
     * source reduces the remaining doubt rather than being averaged in. Two
     * moderate sources agreeing therefore beat one strong source, which is the
     * behaviour §3 asks for — and a source that says "somewhere else" actively
     * subtracts, so a geofence and a contradicting Wi-Fi do not both count.
     */
    private fun fusedConfidence(placeId: String, now: LocalDateTime): Float {
        var doubt = 1f
        var contradiction = 0f
        for (signal in signals) {
            val age = Duration.between(signal.at, now)
            if (age > config.signalTtl) continue
            val weight = signal.source.placeWeight * signal.strength * freshness(age)
            if (signal.placeId == placeId) {
                doubt *= (1f - weight.coerceIn(0f, 1f))
            } else if (signal.placeId != null) {
                contradiction = maxOf(contradiction, weight)
            }
        }
        return ((1f - doubt) - contradiction).coerceIn(0f, 1f)
    }

    /**
     * How much a signal of a given age still counts, 1..0.
     *
     * A signal keeps its full weight for the first half of its TTL and only then
     * fades. The grace period is not cosmetic: decaying from the first second
     * makes the dwell unsatisfiable, because by the time the two minutes of
     * "staying put" have passed the evidence that started them has already
     * dropped below the entry threshold — so an arrival could only ever be
     * confirmed if every source happened to re-report during the wait. With the
     * grace period, staying still (which is when sensors go quiet) confirms an
     * arrival, which is the behaviour anyone would expect.
     */
    private fun freshness(age: Duration): Float {
        val ttl = config.signalTtl.seconds.toFloat()
        val grace = ttl / 2f
        val seconds = age.seconds.toFloat()
        if (seconds <= grace) return 1f
        return (1f - (seconds - grace) / grace).coerceIn(0f, 1f)
    }
}
