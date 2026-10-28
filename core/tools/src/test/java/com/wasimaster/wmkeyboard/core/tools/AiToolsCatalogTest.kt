package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard's own AI tools (#698), as a model and the settings see them. */
class AiToolsCatalogTest {

    @Test
    fun `every name is unique, short enough for every provider, and reserved from MCP`() {
        val names = AiTools.builtIns.map { it.name } + AiTools.WEB_SEARCH + AiTools.WEB_FETCH +
            AiTools.REMEMBER + AiTools.FORGET + AiTools.RENAME_CHAT +
            AiTools.RESEARCH_PLAN + AiTools.RESEARCH_REVIEW
        assertEquals(names.size, names.toSet().size)
        // OpenAI and Gemini both cap a function name at 64 characters.
        assertTrue(names.all { it.length <= 64 && Regex("[a-z_]+").matches(it) })
        assertEquals(names.toSet(), AiTools.reservedNames)
    }

    @Test
    fun `required arguments are all described properties`() {
        for (tool in AiTools.builtIns) {
            val properties = tool.spec.parameters["properties"]!!.jsonObject.keys
            val required = tool.spec.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(tool.name, properties.containsAll(required))
        }
    }

    @Test
    fun `an optional argument stays out of required`() {
        val weather = AiTools.builtIn(AiTools.WEATHER)!!
        assertTrue(weather.spec.parameters["required"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `information tools start on run, and a user's choice wins`() {
        val calculator = AiTools.builtIn(AiTools.CALCULATE)!!
        assertEquals(McpToolPolicy.ALLOW, AiTools.policyOf(calculator, emptyMap()))
        assertEquals(
            McpToolPolicy.OFF,
            AiTools.policyOf(calculator, mapOf(AiTools.CALCULATE to McpToolPolicy.OFF)),
        )
    }

    @Test
    fun `a conversion reads as one short line`() {
        val call = AiToolCall("", AiTools.CONVERT_CURRENCY, """{"amount":12,"from":"USD","to":"EUR"}""")
        assertEquals("12 USD → EUR", AiTools.targetOf(call))
    }

    @Test
    fun `numbers are read whether or not the model quoted them`() {
        assertEquals(2450.0, AiToolCall("", "x", """{"n":"2,450"}""").number("n"))
        assertEquals(12.5, AiToolCall("", "x", """{"n":12.5}""").number("n"))
        assertNull(AiToolCall("", "x", """{"n":"lots"}""").number("n"))
    }

    @Test
    fun `policies survive a round trip, and junk reads as nothing set`() {
        val policies = mapOf(AiTools.WEATHER to McpToolPolicy.ASK, AiTools.CALCULATE to McpToolPolicy.OFF)
        assertEquals(policies, McpServerCodec.decodePolicies(McpServerCodec.encodePolicies(policies)))
        assertEquals(emptyMap<String, McpToolPolicy>(), McpServerCodec.decodePolicies("not json"))
    }
}
