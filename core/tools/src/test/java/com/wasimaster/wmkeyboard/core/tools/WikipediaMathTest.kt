package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A formula in a Wikipedia extract is kept as its TeX, inline with its sentence (#937). */
class WikipediaMathTest {

    private val extract = "whose source alphabet is the set\n\n  \n    \n      {\n      a\n      ,\n      b\n" +
        "      ,\n      c\n      }\n    \n    {\\displaystyle \\{a,b,c\\}}\n  \n\nand whose target alphabet is the set\n\n" +
        "  \n    \n      {\n      0\n      ,\n      1\n      }\n    \n    {\\displaystyle \\{0,1\\}}\n  \n\n."

    @Test
    fun `a flattened formula folds back into its sentence as TeX`() {
        assertEquals(
            "whose source alphabet is the set \\(\\{a,b,c\\}\\) and whose target alphabet is the set \\(\\{0,1\\}\\).",
            WikipediaClient.foldMath(extract),
        )
    }

    @Test
    fun `the plain form writes the formulas out for a text field`() {
        val plain = WikipediaClient.plainMath(WikipediaClient.foldMath(extract))
        assertEquals("whose source alphabet is the set {a, b, c} and whose target alphabet is the set {0, 1}.", plain)
        assertTrue(WikipediaClient.plainMath("x squared is \\(x^2\\)").endsWith("x²"))
    }

    @Test
    fun `text with no formula is left alone`() {
        val plain = "It costs \$5.\n\n  Indented line.\n\nLine two."
        assertEquals(plain, WikipediaClient.foldMath(plain))
        assertEquals(plain, WikipediaClient.plainMath(plain))
    }
}
