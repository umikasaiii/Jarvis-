package com.simone.jarvismobile.core.segnale

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §L. A PRESENTATION
 * QUALITY choice only — never a product mode. Must never influence
 * semantic understanding, tool permissions, data accuracy, model choice,
 * memory, or action authorization; those all live entirely outside this
 * file and this enum is never read by any of them (verified: nothing in
 * `core/semantic`, `core/tools`, `core/engine` imports this package).
 *
 * [STANDARD] is the declared HONOR 200 baseline (§L). [ENHANCED]'s actual
 * heavier effects are explicitly deferred past P0 (§F/§O) — this enum and
 * [SegnaleQualityPolicy] exist now only so a future pass has one owner to
 * extend, not because ENHANCED does anything different yet.
 */
enum class SegnaleQualityProfile { ESSENTIAL, STANDARD, ENHANCED }

/**
 * Bounded capability flags a quality profile is allowed to affect — never a
 * behavior/data/permission flag. Reduced motion always overrides
 * [allowOrnamentalMotion] to `false` regardless of profile (§K/§L, tested).
 */
data class SegnaleQualityCapabilities(
    val allowOrnamentalMotion: Boolean,
    val allowRichCanvasDetail: Boolean,
    val allowOptionalTexture: Boolean,
)

object SegnaleQualityPolicy {
    /** P0's own honest baseline: ENHANCED has no extra effects to grant yet (§F/§O — no shaders/particles/blur in P0). */
    fun capabilitiesFor(profile: SegnaleQualityProfile, reducedMotion: Boolean): SegnaleQualityCapabilities {
        val base = when (profile) {
            SegnaleQualityProfile.ESSENTIAL -> SegnaleQualityCapabilities(
                allowOrnamentalMotion = false,
                allowRichCanvasDetail = false,
                allowOptionalTexture = false,
            )
            SegnaleQualityProfile.STANDARD -> SegnaleQualityCapabilities(
                allowOrnamentalMotion = true,
                allowRichCanvasDetail = false,
                allowOptionalTexture = false,
            )
            // P0: identical capability surface to STANDARD — no heavy Enhanced
            // effects exist yet to gate (§F/§O). A future pass extends this
            // branch, not this enum.
            SegnaleQualityProfile.ENHANCED -> SegnaleQualityCapabilities(
                allowOrnamentalMotion = true,
                allowRichCanvasDetail = false,
                allowOptionalTexture = false,
            )
        }
        return if (reducedMotion) base.copy(allowOrnamentalMotion = false) else base
    }
}
