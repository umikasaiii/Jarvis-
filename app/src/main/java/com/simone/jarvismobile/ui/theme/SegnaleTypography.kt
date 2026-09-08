package com.simone.jarvismobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 12 §E (Typography
 * Foundation).
 *
 * **Font-resource gate — PENDING, reported honestly per §E's explicit
 * instruction, not faked.** Inter/Space Grotesk are the target families, but
 * no `.ttf`/`.otf` files for either are bundled in this repository
 * (`res/font/` does not exist — verified, this pass's own pre-audit) and
 * fetching them would require a network binary download unavailable in this
 * environment (same class of limit already documented throughout
 * `CLAUDE.md` for other binary assets — the sherpa-onnx AAR, model files,
 * etc.). Embedding an arbitrary base64 font blob to fake the two families is
 * explicitly forbidden by §E ("Do NOT embed arbitrary base64 fonts"), so
 * [SegnaleFonts.primary]/[SegnaleFonts.display] both resolve to
 * [FontFamily.Default] (the platform system sans, resolved fully offline,
 * zero network) until real font files are added to `res/font/` — swapping
 * them in later is a one-line change in this object, nothing downstream
 * needs to change.
 */
object SegnaleFonts {
    /** Target: Inter. Current: system default sans (font-resource gate pending — see file doc). */
    val primary: FontFamily = FontFamily.Default

    /** Target: Space Grotesk, selective use only (§E — never every piece of text). Current: system default sans (font-resource gate pending). */
    val display: FontFamily = FontFamily.Default
}

/**
 * §E semantic text roles. Named for what the text IS, not what it looks
 * like, so a future screen migrating to SEGNALE reaches for `body`/`title`
 * rather than a raw `fontSize`. Deliberately a plain object of [TextStyle]
 * values — the same "typography wrapper" shape already established by
 * [com.simone.jarvismobile.ui.driving.JarvisDriveTypography] — not a second
 * `androidx.compose.material3.Typography` root (§W: no duplicate Material
 * theme root); [MaterialTheme.typography] is untouched, so every existing
 * legacy consumer keeps compiling and rendering exactly as before (§V.20).
 *
 * §E — supports Android font scaling correctly: every size below is `sp`
 * (scales with the user's font-size setting), never a fixed `dp`, and no
 * role is wrapped in a fixed-height container by this file — a future
 * SEGNALE composable using these roles must size its own container to
 * content (`wrapContentHeight`/intrinsic), never a literal `height(Dp)`, to
 * honor the 1.0x/1.3x/2.0x acceptance requirement (§E, §X).
 */
object SegnaleTypography {
    val display = TextStyle(fontFamily = SegnaleFonts.display, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
    val headline = TextStyle(fontFamily = SegnaleFonts.display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
    val title = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
    val section = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    val caption = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 14.sp)
    val status = TextStyle(fontFamily = SegnaleFonts.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
}
