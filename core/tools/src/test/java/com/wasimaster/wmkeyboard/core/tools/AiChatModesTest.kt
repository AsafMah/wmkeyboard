package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatModesTest {

    @Test
    fun `a plain chat message gets nothing added`() {
        assertEquals("", AiChatModes.instruction(AiChatMode.CHAT, AiAnswerFormat.AUTO))
    }

    @Test
    fun `a mode brings its own format until one is picked`() {
        assertEquals(AiAnswerFormat.TABLE, AiChatModes.effectiveFormat(AiChatMode.COMPARE, AiAnswerFormat.AUTO))
        assertEquals(AiAnswerFormat.PLAIN, AiChatModes.effectiveFormat(AiChatMode.COMPARE, AiAnswerFormat.PLAIN))
        val compare = AiChatModes.instruction(AiChatMode.COMPARE, AiAnswerFormat.AUTO)
        assertTrue(compare.contains("compared"))
        assertTrue(compare.contains(AiAnswerFormat.TABLE.rule))
    }

    @Test
    fun `research adds only its format`() {
        val research = AiChatModes.instruction(AiChatMode.RESEARCH, AiAnswerFormat.BULLETS)
        assertTrue(research.contains(AiAnswerFormat.BULLETS.rule))
        assertTrue(!research.contains("Summarise"))
    }

    @Test
    fun `unknown ids fall back`() {
        assertEquals(AiChatMode.CHAT, AiChatMode.of(""))
        assertEquals(AiChatMode.RESEARCH, AiChatMode.of("research"))
        assertEquals(AiAnswerFormat.AUTO, AiAnswerFormat.of("nope"))
        assertEquals(AiResearchDepth.STANDARD, AiResearchDepth.of(""))
    }

    @Test
    fun `links are found once, without the punctuation after them`() {
        assertEquals(
            listOf("https://a.example/x?y=1", "http://b.example/"),
            AiChatModes.links("Read https://a.example/x?y=1, then (http://b.example/). Again: https://a.example/x?y=1."),
        )
        assertEquals(3, AiChatModes.links("https://a.ex/1 https://a.ex/2 https://a.ex/3 https://a.ex/4").size)
    }

    @Test
    fun `pages are wrapped with their address`() {
        assertEquals(
            "<page url=\"https://a.example/%22q\">\nhello\n</page>",
            AiChatModes.pagesBlock(listOf("https://a.example/\"q" to " hello ")),
        )
    }
}
