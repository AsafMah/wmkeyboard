package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Burmese typed in drawn order lands in Unicode order (Play review, Oct 2026). */
class MyanmarVisualOrderTest {

    /** Types [keys] one at a time through the reorder, as the keyboard does. */
    private fun type(keys: String, field: String = ""): String {
        val out = StringBuilder(field)
        for (key in keys) {
            val edit = MyanmarVisualOrder.reorder(out, key.toString())
            if (edit == null) {
                out.append(key)
            } else {
                out.setLength(out.length - edit.delete)
                out.append(edit.text)
            }
        }
        return out.toString()
    }

    @Test
    fun `the review's four words come out in Unicode order`() {
        assertEquals("နေ", type("ေန"))
        assertEquals("ရေ", type("ေရ"))
        assertEquals("လေ", type("ေလ"))
        // ေ first, then the consonant, then its medial ra.
        assertEquals("မြေ", type("ေမြ"))
    }

    @Test
    fun `both pre-base signs before the consonant, in either order`() {
        assertEquals("မြေ", type("ေြမ"))
        assertEquals("မြေ", type("ြေမ"))
        assertEquals("မြ", type("ြမ"))
    }

    @Test
    fun `stored order is left alone`() {
        for (word in listOf("နေ", "မြေ", "ကျောင်း", "မြန်မာ", "ကြွေ", "ရှေ့")) {
            assertEquals(word, type(word))
        }
    }

    @Test
    fun `a whole syllable keeps its ေ and the next one takes the orphan`() {
        assertEquals("နေရေ", type("ေနေရ"))
        assertEquals("နေရ", type("ေနရ"))
    }

    @Test
    fun `medials after a consonant's ေ take their stored place`() {
        assertEquals("ကျေ", type("ေကျ"))
        assertEquals("ကြွေ", type("ေကွြ"))
        assertEquals("ရှေ့", type("ေရှ့"))
    }

    @Test
    fun `a full word in drawn order`() {
        assertEquals("ကျောင်း", type("ေကျာင်း"))
        assertEquals("မြေပုံ", type("ေမြပုံ"))
    }

    @Test
    fun `a ေ with no consonant before it waits after a space`() {
        assertEquals("နေ နေ", type(" ေန", field = "နေ"))
    }

    @Test
    fun `other keys pass through`() {
        assertNull(MyanmarVisualOrder.reorder("ေ", "ာ"))
        assertNull(MyanmarVisualOrder.reorder("ေ", "၁"))
        assertNull(MyanmarVisualOrder.reorder("ေ", "a"))
        assertNull(MyanmarVisualOrder.reorder("", "ြ"))
    }
}
