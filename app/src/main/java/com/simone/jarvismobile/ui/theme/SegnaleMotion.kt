package com.simone.jarvismobile.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.simone.jarvismobile.core.segnale.SegnaleMotionPolicy

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §J/§K (Motion
 * Foundation + Reduced Motion). Duration/easing TOKENS only — this file
 * does not itself run any animation and holds no animated value; every
 * value produced from here is meant to be read once by a `Composable` that
 * owns its own local animation state (§J: "Do NOT publish 60Hz animation
 * values through app-wide StateFlows. Do NOT make a ViewModel the owner of
 * frame-by-frame visual animation" — this object cannot violate that rule
 * because it has no state to publish, only functions returning specs).
 */
object SegnaleMotionDuration {
    const val ENTER_MS = 220
    const val EXIT_MS = 160
    const val EXPAND_MS = 260
    const val COLLAPSE_MS = 200
    const val STATE_CHANGE_MS = 180
    const val SUCCESS_MS = 320
    const val ERROR_MS = 220
    const val INTERRUPTION_MS = 120
}

object SegnaleMotionEasing {
    val standard: Easing = FastOutSlowInEasing
    val enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val exit: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
    val linear: Easing = LinearEasing
}

/**
 * §J — one motion token per named presentation event this pass defines a
 * language for. §J "Motion must correspond to a real presentation event" —
 * every entry here names an event a real state transition can trigger
 * ([DataStatus]/[SideEffectStatus] changing, a surface opening/closing),
 * never an ambient/ornamental default.
 */
enum class SegnaleMotionEvent { ENTER, EXIT, EXPAND, COLLAPSE, STATE_CHANGE, SUCCESS, ERROR, INTERRUPTION }

private fun SegnaleMotionEvent.durationMs(): Int = when (this) {
    SegnaleMotionEvent.ENTER -> SegnaleMotionDuration.ENTER_MS
    SegnaleMotionEvent.EXIT -> SegnaleMotionDuration.EXIT_MS
    SegnaleMotionEvent.EXPAND -> SegnaleMotionDuration.EXPAND_MS
    SegnaleMotionEvent.COLLAPSE -> SegnaleMotionDuration.COLLAPSE_MS
    SegnaleMotionEvent.STATE_CHANGE -> SegnaleMotionDuration.STATE_CHANGE_MS
    SegnaleMotionEvent.SUCCESS -> SegnaleMotionDuration.SUCCESS_MS
    SegnaleMotionEvent.ERROR -> SegnaleMotionDuration.ERROR_MS
    SegnaleMotionEvent.INTERRUPTION -> SegnaleMotionDuration.INTERRUPTION_MS
}

private fun SegnaleMotionEvent.easing(): Easing = when (this) {
    SegnaleMotionEvent.ENTER, SegnaleMotionEvent.EXPAND -> SegnaleMotionEasing.enter
    SegnaleMotionEvent.EXIT, SegnaleMotionEvent.COLLAPSE -> SegnaleMotionEasing.exit
    else -> SegnaleMotionEasing.standard
}

/**
 * §K — the real, functional-feedback-preserving reduced-motion tween: a
 * [SegnaleMotionEvent]'s ordinary spec when motion is allowed, or an
 * effectively-instant [snap] when [reducedMotion] is true — never removes
 * the transition outright (state clarity is still required, §K), only its
 * ornamental duration.
 */
fun <T> segnaleMotionSpec(event: SegnaleMotionEvent, reducedMotion: Boolean) =
    if (reducedMotion) snap<T>() else tween<T>(durationMillis = event.durationMs(), easing = event.easing())

/**
 * §K — composes the real Android system "remove animations" signal
 * ([ValueAnimator.areAnimatorsEnabled], the documented API for exactly this
 * preference since API 26 — this app's `minSdk` is 31, so no version guard
 * is needed) with an optional additional user preference via
 * [SegnaleMotionPolicy.effectiveReducedMotion]. No new Settings screen/
 * persisted preference is read here (§K) — [userPrefersReducedMotion] is
 * `null` from every current call site.
 *
 * § PASSAGGIO 12.1 precedence contract: [userPrefersReducedMotion] can only
 * ever REQUEST additional reduced motion on top of the system signal — it
 * can never turn reduced motion back off when the system requires it (see
 * [SegnaleMotionPolicy]'s own doc comment for the full truth table).
 */
@Composable
fun rememberSegnaleReducedMotion(userPrefersReducedMotion: Boolean? = null): Boolean {
    val systemReducedMotion = !ValueAnimator.areAnimatorsEnabled()
    return remember(systemReducedMotion, userPrefersReducedMotion) {
        SegnaleMotionPolicy.effectiveReducedMotion(systemReducedMotion, userPrefersReducedMotion)
    }
}
