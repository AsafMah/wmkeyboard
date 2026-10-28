package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keys that type no letter but carry one (#970): a number-row digit with a
 * letter behind its hold, a symbol whose Shift is a letter. `LayoutSet.glideKeys`
 * puts the letters nothing else claims at that key's centre, and leaves the
 * letter keys' own claims alone.
 */
class GlideCarriedKeysTest {

    private val set = LayoutSet(Layouts.QWERTY, Layouts.SYMBOLS, Layouts.SYMBOLS_SHIFTED)

    /** Key width, and the row pitch: centres below divide back to whole grid units. */
    private val keyWidth = 10f

    private val centers: Map<Int, Pair<Float, Float>> = buildMap {
        for ((rowIndex, row) in set.letters.rows.withIndex()) {
            for ((column, key) in row.withIndex()) {
                val anchor = key.glideAnchor() ?: continue
                putIfAbsent(anchor, (column * keyWidth) to (rowIndex * keyWidth))
            }
        }
    }

    /** The number row's `8`, a row above `i` (column 7 of QWERTY's top row). */
    private val overI = (7 * keyWidth) to -keyWidth

    private fun grid(carriers: List<Pair<Key, Pair<Float, Float>>> = emptyList()): GlideKeyMap =
        GlideKeyMap.of(set.glideKeys(carriers = carriers) { centers[it] }, keyWidth = keyWidth)

    @Test
    fun `a letter only a carrier holds lands at the carrier's centre`() {
        assertFalse("premise: QWERTY has no þ of its own", grid().knows('þ'))
        val keys = grid(listOf(Key("8", longPress = listOf("þ", "¾")) to overI))
        val key = keys.keyIndex('þ')
        assertTrue(key >= 0)
        assertEquals(7f, keys.keyX[key], 1e-4f)
        assertEquals(-1f, keys.keyY[key], 1e-4f)
        assertFalse("a fraction is no letter", keys.knows('¾'))
    }

    @Test
    fun `a letter key keeps its own letter and an all-claimed carrier adds no key`() {
        val plain = grid()
        val keys = grid(listOf(Key("8", longPress = listOf("i")) to overI))
        assertEquals(plain.keyCount, keys.keyCount)
        assertEquals(plain.keyIndex('i'), keys.keyIndex('i'))
        // So the top of the grid is still the top letter row, and a stroke
        // over that 8 is folded down to `i` rather than read at the digit.
        assertEquals(plain.topEdge, keys.topEdge, 1e-4f)
    }

    @Test
    fun `a carrier on the number row raises the grid's top edge to itself`() {
        // Row pitch 1, top row at 0: the edge is half a row above it.
        assertEquals(-0.5f, grid().topEdge, 1e-4f)
        val keys = grid(listOf(Key("8", shiftLabel = "þ") to overI))
        assertEquals(-1.5f, keys.topEdge, 1e-4f)
    }

    @Test
    fun `what a key carries is its shift, its corner and its hold, letters only`() {
        assertEquals(
            listOf("þ", "ð", "æ"),
            Key("8", shiftLabel = "þ", hint = "ð", longPress = listOf("æ", "¾", "þ")).carriedGlideSpellings(),
        )
        assertTrue("a letter key's own passes cover it", Key("u", longPress = listOf("ü")).carriedGlideSpellings().isEmpty())
        assertTrue(Key("⌫", action = KeyAction.Delete, longPress = listOf("x")).carriedGlideSpellings().isEmpty())
        assertTrue(Key("8", longPress = listOf("⅛", "∞")).carriedGlideSpellings().isEmpty())
    }
}
