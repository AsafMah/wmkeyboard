package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    private val shipped = listOf("en", "ar", "bn", "de", "iw", "in", "no", "zh-CN")

    @Test
    fun `retired resource codes match the modern ones`() {
        assertEquals("he", canonicalLocaleTag("iw"))
        assertEquals("id", canonicalLocaleTag("in"))
        assertEquals("nb", canonicalLocaleTag("no"))
        assertEquals("zh-CN", canonicalLocaleTag("zh_CN"))
        assertEquals("iw", matchAvailable("he", shipped))
        assertEquals("in", matchAvailable("id-ID", shipped))
        assertEquals("no", matchAvailable("nb", shipped))
    }

    @Test
    fun `a region the build lacks falls back to the language`() {
        assertEquals("bn", matchAvailable("bn-BD", shipped))
        assertEquals("zh-CN", matchAvailable("zh-TW", shipped))
        assertNull(matchAvailable("ja", shipped))
    }
}
