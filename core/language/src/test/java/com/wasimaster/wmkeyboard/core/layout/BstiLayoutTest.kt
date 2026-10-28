package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BSTI (BDS 1738:2018), against Ekushey's Bangla BSTI Mobile tables (#901):
 * the keys by row in both layers, the holds, and the conventions it shares
 * with the other fixed Bangla layouts.
 */
class BstiLayoutTest {

    private val rows = BuiltInLayouts.BSTI.layers.getValue(LayoutLayer.LETTERS.key).rows
    private val letters = rows.dropLast(1).map { row -> row.filter { it.action == KeyAction.Text } }
    private val keys = letters.flatten()

    private fun key(label: String) = keys.single { it.label == label }

    @Test
    fun `the base layer follows the standard`() {
        assertEquals(
            listOf(
                listOf("ঙ", "য", "ড", "প", "ট", "চ", "জ", "হ", "গ", "ড়"),
                listOf("ৃ", "ু", "ি", "া", "্", "ব", "ক", "ত", "দ"),
                listOf("্র", "ো", "ে", "র", "ন", "স", "ম"),
            ),
            letters.map { row -> row.map { it.label } },
        )
    }

    @Test
    fun `the shift layer follows the standard`() {
        assertEquals(
            listOf(
                listOf("ং", "য়", "ঢ", "ফ", "ঠ", "ছ", "ঝ", "ঞ", "ঘ", "ঢ়"),
                listOf("র্", "ূ", "ী", "অ", "ঁ", "ভ", "খ", "থ", "ধ"),
                listOf("্য", "ৌ", "ৈ", "ল", "ণ", "ষ", "শ"),
            ),
            letters.map { row -> row.map { it.shiftLabel } },
        )
    }

    @Test
    fun `the kars hold their independent vowels`() {
        for ((kar, vowels) in listOf(
            "ৃ" to listOf("ঋ"),
            "ু" to listOf("উ", "ঊ"),
            "ি" to listOf("ই", "ঈ"),
            "া" to listOf("আ"),
            "ো" to listOf("ও", "ঔ"),
            "ে" to listOf("এ", "ঐ"),
        )) {
            assertTrue(kar, key(kar).longPress.containsAll(vowels))
        }
    }

    @Test
    fun `the conjuncts ride the holds of their shift keys`() {
        for ((label, conjunct) in listOf(
            "ঙ" to "ঙ্ক", "য" to "ঞ্ঝ", "চ" to "ঞ্ছ", "জ" to "জ্ঞ", "হ" to "ঞ্জ", "গ" to "ঙ্গ",
            "ক" to "ক্ষ", "র" to "ব়্য", "ন" to "ঞ্চ", "স" to "ষ্ণ", "ম" to "হ্ম",
        )) {
            assertTrue(label, conjunct in key(label).longPress)
        }
        assertTrue("ৎ" in key("ত").longPress)
        assertTrue("ঃ" in key("ম").longPress)
    }

    @Test
    fun `the joiners are on the hasant and full stop holds`() {
        val joiners = listOf("‌", "‍")
        assertTrue(key("্").longPress.containsAll(joiners))
        val period = rows.last().single { it.role == KeyRole.Period }
        assertTrue(period.longPress.containsAll(joiners))
    }

    @Test
    fun `it is a Bangla layout`() {
        assertEquals(BuiltInLayouts.BSTI, BuiltInLayouts.byId(BuiltInLayouts.BSTI_ID))
        assertTrue(BuiltInLayouts.BSTI_ID in LanguageRegistry.byId("bn").layoutIds)
    }
}
