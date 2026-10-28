package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.ime.ui.nextKeyCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where one press of the D-pad lands, on the grid a television remote is
 * actually pointing at.
 *
 * The cases here are the ones the geometry has to get right for the ring to
 * feel like a keyboard rather than a puzzle: a staggered row (QWERTY's `a` row
 * sits half a key right of its `q` row), a key several columns wide (the
 * spacebar), and the two edges — which behave differently on purpose.
 */
class KeyGridFocusTest {

    /** Three rows of a phone-shaped board: 10 keys, 9 offset by half, then a spacebar row. */
    private val keyWidth = 100f
    private val keyHeight = 60f

    private fun row(count: Int, y: Int, offset: Float = 0f): List<Rect> =
        (0 until count).map {
            Rect(
                offset + it * keyWidth,
                y * keyHeight,
                offset + (it + 1) * keyWidth,
                (y + 1) * keyHeight,
            )
        }

    private val topRow = row(10, 0)
    private val homeRow = row(9, 1, offset = keyWidth / 2)
    private val space = Rect(200f, 2 * keyHeight, 700f, 3 * keyHeight)
    private val bottomRow = listOf(
        Rect(0f, 2 * keyHeight, 200f, 3 * keyHeight),
        space,
        Rect(700f, 2 * keyHeight, 1000f, 3 * keyHeight),
    )
    private val grid = topRow + homeRow + bottomRow

    @Test
    fun `right moves to the next key in the row`() {
        assertEquals(topRow[4], nextKeyCell(grid, topRow[3], 1, 0))
    }

    @Test
    fun `left moves back`() {
        assertEquals(topRow[2], nextKeyCell(grid, topRow[3], -1, 0))
    }

    @Test
    fun `down from a staggered row lands on a neighbour, left one first`() {
        // QWERTY's home row is offset by half a key, so `e` sits exactly over
        // the seam between `s` and `d`: same overlap, same centre distance, a
        // genuine tie. It is broken by the order the grid reported its cells —
        // left to right — so the ring goes to the left of the two, every time,
        // on every board. Deterministic is the requirement; which one it is is
        // not.
        assertEquals(homeRow[1], nextKeyCell(grid, topRow[2], 0, 1))
    }

    @Test
    fun `down prefers the key nearest below when the rows line up`() {
        // Same two rows without the stagger: no tie left to break.
        val aligned = topRow + row(10, 1)
        assertEquals(aligned[12], nextKeyCell(aligned, topRow[2], 0, 1))
    }

    @Test
    fun `down onto a wide key lands on it rather than skipping past`() {
        assertEquals(space, nextKeyCell(grid, homeRow[3], 0, 1))
    }

    @Test
    fun `up off the top row goes nowhere, so the app gets the key`() {
        assertNull(nextKeyCell(grid, topRow[0], 0, -1))
    }

    @Test
    fun `down off the bottom row goes nowhere`() {
        assertNull(nextKeyCell(grid, space, 0, 1))
    }

    @Test
    fun `right off the end of a row wraps to its start`() {
        assertEquals(topRow[0], nextKeyCell(grid, topRow[9], 1, 0))
    }

    @Test
    fun `left off the start of a row wraps to its end`() {
        assertEquals(topRow[9], nextKeyCell(grid, topRow[0], -1, 0))
    }

    @Test
    fun `wrapping stays inside the row the ring is on`() {
        assertEquals(bottomRow[2], nextKeyCell(grid, bottomRow[0], -1, 0))
    }

    @Test
    fun `a straight-ahead key beats a nearer one off to the side`() {
        // A tall Enter key beside the home row: from `l`, down must reach the
        // key under it rather than the one whose centre happens to be closer.
        val enter = Rect(900f, keyHeight, 1000f, 3 * keyHeight)
        val cells = grid + enter
        assertEquals(space, nextKeyCell(cells, homeRow[4], 0, 1))
    }

    @Test
    fun `an empty grid moves nowhere`() {
        assertNull(nextKeyCell(emptyList(), topRow[0], 1, 0))
    }

    @Test
    fun `right from T9 segmentation stays on ABC instead of a nearer toolbar icon`() {
        val segmentation = Rect(0f, 80f, 180f, 160f)
        val abc = Rect(180f, 80f, 360f, 160f)
        val toolbarIcon = Rect(180f, 0f, 220f, 60f)
        assertEquals(abc, nextKeyCell(listOf(segmentation, abc, toolbarIcon), segmentation, 1, 0))
    }

    @Test
    fun `left from ABC stays on segmentation instead of a nearer toolbar icon`() {
        val segmentation = Rect(0f, 80f, 180f, 160f)
        val abc = Rect(180f, 80f, 360f, 160f)
        val toolbarIcon = Rect(140f, 0f, 180f, 60f)
        assertEquals(segmentation, nextKeyCell(listOf(segmentation, abc, toolbarIcon), abc, -1, 0))
    }

    @Test
    fun `horizontal travel in the toolbar does not fall into the T9 keys`() {
        val mic = Rect(200f, 0f, 240f, 60f)
        val emoji = Rect(400f, 0f, 440f, 60f)
        val abc = Rect(240f, 80f, 420f, 160f)
        assertEquals(emoji, nextKeyCell(listOf(mic, emoji, abc), mic, 1, 0))
    }

    @Test
    fun `T9 row wraps instead of reaching a toolbar button beyond its edge`() {
        val segmentation = Rect(0f, 80f, 180f, 160f)
        val abc = Rect(180f, 80f, 360f, 160f)
        val def = Rect(360f, 80f, 540f, 160f)
        val toolbarIcon = Rect(560f, 0f, 600f, 60f)
        assertEquals(segmentation, nextKeyCell(listOf(segmentation, abc, def, toolbarIcon), def, 1, 0))
    }

    @Test
    fun `a toolbar row touching the key row is still a different row`() {
        val segmentation = Rect(0f, 80f, 180f, 160f)
        val abc = Rect(180f, 80f, 360f, 160f)
        val toolbarIcon = Rect(180f, 0f, 220f, 80f)
        assertEquals(abc, nextKeyCell(listOf(segmentation, abc, toolbarIcon), segmentation, 1, 0))
    }

    @Test
    fun `T9 up and down keep columns and reach the toolbar above the first row`() {
        val rows = (0..2).map { y -> (0..2).map { x ->
            Rect(x * 180f, 80f + y * 80f, (x + 1) * 180f, 160f + y * 80f)
        } }
        val toolbar = (0..2).map { x -> Rect(x * 180f + 60f, 0f, x * 180f + 120f, 60f) }
        val cells = rows.flatten() + toolbar
        for (column in 0..2) {
            assertEquals(rows[1][column], nextKeyCell(cells, rows[0][column], 0, 1))
            assertEquals(rows[2][column], nextKeyCell(cells, rows[1][column], 0, 1))
            assertEquals(rows[0][column], nextKeyCell(cells, rows[1][column], 0, -1))
            assertEquals(toolbar[column], nextKeyCell(cells, rows[0][column], 0, -1))
            assertEquals(rows[0][column], nextKeyCell(cells, toolbar[column], 0, 1))
        }
    }

    @Test
    fun `horizontal travel can still reach a taller key spanning the same row`() {
        val key = Rect(0f, 80f, 100f, 160f)
        val tallEnter = Rect(100f, 80f, 200f, 320f)
        assertEquals(tallEnter, nextKeyCell(listOf(key, tallEnter), key, 1, 0))
    }

    @Test
    fun `every QWERTY row traverses and wraps without entering its toolbar`() {
        val rows = listOf(row(10, 1), row(9, 2, keyWidth / 2), row(7, 3, keyWidth))
        val toolbar = (0..4).map { Rect(it * 200f, 0f, it * 200f + 44f, 44f) }
        val cells = rows.flatten() + toolbar
        for (keys in rows) {
            for (index in keys.indices) {
                assertEquals(keys[(index + 1) % keys.size], nextKeyCell(cells, keys[index], 1, 0))
                assertEquals(keys[(index + keys.size - 1) % keys.size], nextKeyCell(cells, keys[index], -1, 0))
            }
        }
    }

    @Test
    fun `QWERTY toolbar traverses its icons instead of nearby letters below`() {
        val toolbar = (0..4).map { Rect(it * 200f, 0f, it * 200f + 44f, 44f) }
        val cells = toolbar + row(10, 1) + row(9, 2, keyWidth / 2)
        for (index in toolbar.indices) {
            assertEquals(toolbar[(index + 1) % toolbar.size], nextKeyCell(cells, toolbar[index], 1, 0))
            assertEquals(toolbar[(index + toolbar.size - 1) % toolbar.size], nextKeyCell(cells, toolbar[index], -1, 0))
        }
    }

    @Test
    fun `QWERTY wide space reaches its own row neighbours instead of letters above`() {
        assertEquals(bottomRow[0], nextKeyCell(grid, space, -1, 0))
        assertEquals(bottomRow[2], nextKeyCell(grid, space, 1, 0))
    }

    @Test
    fun `QWERTY still moves vertically between toolbar letters and wide space`() {
        val toolbar = Rect(200f, -60f, 300f, -10f)
        val cells = grid + toolbar
        assertEquals(toolbar, nextKeyCell(cells, topRow[2], 0, -1))
        assertEquals(topRow[2], nextKeyCell(cells, toolbar, 0, 1))
        assertEquals(homeRow[1], nextKeyCell(cells, topRow[2], 0, 1))
        assertEquals(space, nextKeyCell(cells, homeRow[3], 0, 1))
    }
}
