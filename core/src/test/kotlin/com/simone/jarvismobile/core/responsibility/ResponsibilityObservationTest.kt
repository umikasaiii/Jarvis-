package com.simone.jarvismobile.core.responsibility

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * § JARVIS — PERSISTENT AGENT KERNEL — PA-1B. Type-level proof (plain Java
 * reflection, no new test dependency) that [ResponsibilityObservation] and
 * [ResponsibilityContextSnapshot] really are the closed-world, bounded
 * contracts §7-9 require — never a `Map<String, Any>`, raw platform event,
 * free-text source name, or executable callback smuggled in as a field.
 */
class ResponsibilityObservationTest {

    private fun isClosedWorldType(type: Class<*>): Boolean =
        type == Long::class.javaPrimitiveType ||
            type == Boolean::class.javaPrimitiveType ||
            type == Int::class.javaPrimitiveType ||
            type.isEnum

    private fun assertOnlyClosedWorldFields(cls: Class<*>) {
        val offending = cls.declaredFields.filterNot { it.isSynthetic }.filterNot { isClosedWorldType(it.type) }
        assertTrue(offending.isEmpty(), "unexpected field type(s) in $cls: ${offending.map { it.name to it.type }}")
    }

    @Test
    fun `every ResponsibilityObservation variant carries only timestamps and closed-world enums -- never a map, string, or callback`() {
        assertOnlyClosedWorldFields(ResponsibilityObservation.ExternalEventObserved::class.java)
        assertOnlyClosedWorldFields(ResponsibilityObservation.RecheckDue::class.java)
        assertOnlyClosedWorldFields(ResponsibilityObservation.ProcessRestored::class.java)
        assertOnlyClosedWorldFields(ResponsibilityObservation.DeadlineReached::class.java)
        assertOnlyClosedWorldFields(ResponsibilityObservation.CapabilityChanged::class.java)
        assertOnlyClosedWorldFields(ResponsibilityObservation.VerificationResultObserved::class.java)
    }

    @Test
    fun `NoResponsibilityContext carries no fields -- the context marker is not a universal state map`() {
        val fields = NoResponsibilityContext::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) } // excludes the Kotlin `object` singleton's own INSTANCE field
        assertTrue(fields.isEmpty(), "expected no fields, found ${fields.map { it.name }}")
    }

    @Test
    fun `ResponsibilityTriggerIdentifier is a closed-world enum -- never a free-text source name`() {
        val expected = setOf("FIRST_UNLOCK", "NEXT_ALARM", "CONFIGURED_TIME", "PERIODIC_FALLBACK")
        assertTrue(ResponsibilityTriggerIdentifier.entries.map { it.name }.toSet() == expected)
    }

    @Test
    fun `ResponsibilityCoordinationResult variants carry only closed-world, bounded fields too`() {
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.TransitionProposal::class.java)
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.ActionRequested::class.java)
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.VerificationRequested::class.java)
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.RecheckRequested::class.java)
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.AskUserRequested::class.java)
        assertOnlyClosedWorldFields(ResponsibilityCoordinationResult.Rejected::class.java)
    }
}
