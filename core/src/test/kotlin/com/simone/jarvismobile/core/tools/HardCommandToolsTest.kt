package com.simone.jarvismobile.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * § FASE 2A.10 §"HARD COMMAND FAST PATH" — the allowlist must stay exactly
 * the deliberately tiny set the user specified (torch + media transport,
 * the only ones this project has a real, already-safe tool for); a growing
 * allowlist here would silently widen how much natural language bypasses the
 * Semantic Interpreter.
 */
class HardCommandToolsTest {
    @Test
    fun `the hard command allowlist is exactly flashlight and media_control`() {
        assertEquals(setOf("flashlight", "media_control"), HARD_COMMAND_TOOL_NAMES)
    }
}
