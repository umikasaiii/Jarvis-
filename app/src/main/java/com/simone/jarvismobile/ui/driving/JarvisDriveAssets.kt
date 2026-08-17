package com.simone.jarvismobile.ui.driving

import com.simone.jarvismobile.R

/**
 * Real reference-artwork resource ids for the JARVIS Drive HUD (golden
 * reference kit, `docs/design/jarvis_drive_reference.png` + numbered
 * component sprites). Centralised here so every card pulls its frame from
 * one place instead of a scattered `R.drawable.*` per file — and so it is
 * obvious, from this file alone, which components are asset-backed and
 * which are still Compose-drawn.
 *
 * Two reference pieces are deliberately NOT here, both for reasons grounded
 * in what actually exists on disk this round, not convenience:
 * - the voice dock ("08_VOICE_DOCK_BOTTOM") sprite has "Listening" and the
 *   mic glow baked into the pixels — usable as a literal background only for
 *   a single fixed voice state, never for the live Idle/Listening/Thinking/
 *   Speaking/Offline states this dock actually cycles through. [VoiceDock]
 *   stays Compose-drawn (shape, gradient border, three-dot notch already
 *   matched to the reference) so the mic ring and state label can be real.
 * - the top header ("02_TOP_HEADER_HUD") has no clean, textless sprite in
 *   this batch of uploads — only the labelled overview thumbnail exists.
 *   [JarvisDriveTopBar] stays Compose-drawn until a clean asset is provided.
 */
object JarvisDriveAssets {
    val ManeuverCard = R.drawable.drive_hud_maneuver
    val SpeedCard = R.drawable.drive_hud_speed
    val EtaBar = R.drawable.drive_hud_eta
    val MessagesCard = R.drawable.drive_hud_messages
    val MusicCard = R.drawable.drive_hud_music
}

/**
 * Single entry point for the Drive overlay's design system (spec REGOLA 17):
 * colors, typography, shapes and asset ids are each already their own
 * dedicated object ([DrivingSportColors], [JarvisDriveTypography],
 * [JarvisDriveShapes], [JarvisDriveDimensions], [JarvisDriveBrushes],
 * [JarvisDriveAssets]) rather than folded into one god-object, but this
 * gathers them under one name so a reader — or a future contributor tempted
 * to reach for `MaterialTheme` inside `ui/driving/` — has one obvious place
 * confirming the Drive overlay owns its whole visual language and never
 * inherits from the general app theme.
 */
object JarvisDriveTheme {
    val Colors = DrivingSportColors
    val Typography = JarvisDriveTypography
    val Shapes = JarvisDriveShapes
    val Dimensions = JarvisDriveDimensions
    val Brushes = JarvisDriveBrushes
    val Assets = JarvisDriveAssets
}
