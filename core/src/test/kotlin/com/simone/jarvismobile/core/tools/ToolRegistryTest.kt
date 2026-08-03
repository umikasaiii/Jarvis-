package com.simone.jarvismobile.core.tools

import com.simone.jarvismobile.core.protocol.ToolCall
import com.simone.jarvismobile.core.tools.builtin.CalculateTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A configurable fake tool for policy/resolution tests. */
private class FakeTool(
    override val name: String,
    override val policy: ToolPolicy,
    override val requiresNetwork: Boolean = false,
    private val validationError: String? = null,
) : Tool {
    override val description = "fake"
    override val sensitivity = SensitivityLevel.PERSONAL
    override val timeoutMs = 1000L
    override fun validate(arguments: JsonObject): String? = validationError
    override suspend fun execute(arguments: JsonObject) =
        ToolResult.Success(JsonObject(emptyMap()))
}

class ToolRegistryTest {

    private fun call(name: String, requiresConfirmation: Boolean = false) =
        ToolCall(id = "1", name = name, arguments = JsonObject(emptyMap()), requiresConfirmation = requiresConfirmation)

    @Test
    fun `unknown tool is rejected`() {
        val reg = ToolRegistry()
        val res = reg.resolve(call("does_not_exist"), online = true)
        assertTrue(res is ToolResolution.Rejected)
        assertTrue((res as ToolResolution.Rejected).reason is ToolRejection.Unknown)
    }

    @Test
    fun `read only tool needs no confirmation`() {
        val reg = ToolRegistry(listOf(FakeTool("read", ToolPolicy.READ_ONLY)))
        val res = reg.resolve(call("read"), online = true)
        assertTrue(res is ToolResolution.Approved)
        assertEquals(false, (res as ToolResolution.Approved).needsConfirmation)
        assertEquals(false, res.needsBiometric)
    }

    @Test
    fun `model cannot downgrade a required confirmation`() {
        // Tool policy demands confirmation; model says requires_confirmation=false.
        val reg = ToolRegistry(listOf(FakeTool("send", ToolPolicy.EXTERNAL_COMMUNICATION)))
        val res = reg.resolve(call("send", requiresConfirmation = false), online = true)
        assertTrue(res is ToolResolution.Approved)
        assertTrue((res as ToolResolution.Approved).needsConfirmation)
    }

    @Test
    fun `home security requires biometric`() {
        val reg = ToolRegistry(listOf(FakeTool("unlock", ToolPolicy.HOME_SECURITY)))
        val res = reg.resolve(call("unlock"), online = true) as ToolResolution.Approved
        assertTrue(res.needsConfirmation)
        assertTrue(res.needsBiometric)
    }

    @Test
    fun `network tool offline is rejected`() {
        val reg = ToolRegistry(listOf(FakeTool("pc", ToolPolicy.EXTERNAL_COMMUNICATION, requiresNetwork = true)))
        val res = reg.resolve(call("pc"), online = false)
        assertTrue(res is ToolResolution.Rejected)
        assertTrue((res as ToolResolution.Rejected).reason is ToolRejection.NetworkRequiredButOffline)
    }

    @Test
    fun `invalid arguments are rejected before execution`() {
        val reg = ToolRegistry(listOf(FakeTool("x", ToolPolicy.READ_ONLY, validationError = "bad")))
        val res = reg.resolve(call("x"), online = true)
        assertTrue(res is ToolResolution.Rejected)
        val reason = (res as ToolResolution.Rejected).reason
        assertTrue(reason is ToolRejection.InvalidArguments)
        assertEquals("bad", (reason as ToolRejection.InvalidArguments).reason)
    }

    @Test
    fun `calculate evaluates expression safely`() = runTest {
        val tool = CalculateTool()
        val args = JsonObject(mapOf("expression" to JsonPrimitive("2 + 3 * (4 - 1)")))
        assertEquals(null, tool.validate(args))
        val result = tool.execute(args)
        assertTrue(result is ToolResult.Success)
        val value = (result as ToolResult.Success).output["result"]!!.jsonPrimitive.double
        assertEquals(11.0, value)
    }

    @Test
    fun `calculate rejects non arithmetic characters`() {
        val tool = CalculateTool()
        val args = JsonObject(mapOf("expression" to JsonPrimitive("System.exit(0)")))
        assertTrue(tool.validate(args) != null)
    }

    @Test
    fun `calculate reports division by zero as structured failure`() = runTest {
        val tool = CalculateTool()
        val args = JsonObject(mapOf("expression" to JsonPrimitive("1/0")))
        val result = tool.execute(args)
        assertTrue(result is ToolResult.Failure)
        assertEquals("arithmetic_error", (result as ToolResult.Failure).code)
    }
}
