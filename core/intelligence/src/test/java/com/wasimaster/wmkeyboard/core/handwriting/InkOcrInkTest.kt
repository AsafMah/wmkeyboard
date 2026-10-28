package com.wasimaster.wmkeyboard.core.handwriting

import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How panel ink becomes the strip PaddleOCR's recognisers read. */
class InkOcrInkTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        HwStroke(listOf(HwPoint(x0, y0, 0), HwPoint((x0 + x1) / 2, (y0 + y1) / 2, 8), HwPoint(x1, y1, 16)))

    @Test
    fun `no ink is no strip`() {
        assertNull(InkOcrInk.render(emptyList(), 400f))
    }

    @Test
    fun `the strip is the recogniser's height and the writing's width`() {
        // A word-shaped box, four times as wide as tall.
        val ink = listOf(line(100f, 100f, 500f, 100f), line(100f, 200f, 500f, 200f))
        val strip = InkOcrInk.render(ink, 400f)!!
        assertEquals(PaddleOcrPipeline.REC_HEIGHT, strip.rgb.size / 3 / strip.width)
        // 100 px of writing fills 36 of the 48 rows, so 400 px runs 144 columns, plus the margins.
        assertTrue(strip.width in 144..170)
    }

    @Test
    fun `the ink is black on white, centred top to bottom`() {
        val ink = listOf(line(0f, 0f, 300f, 0f), line(0f, 100f, 300f, 100f))
        val strip = InkOcrInk.render(ink, 400f)!!
        val middle = strip.width / 2
        // The two lines land 6 rows from the top and the bottom, the margins.
        assertTrue(InkOcrInk.inkAt(strip, middle, 6) > 0.9f)
        assertTrue(InkOcrInk.inkAt(strip, middle, 41) > 0.9f)
        // Between them, and at the edges, nothing.
        assertEquals(0f, InkOcrInk.inkAt(strip, middle, 24), 0f)
        assertEquals(0f, InkOcrInk.inkAt(strip, middle, 0), 0f)
        assertEquals(0f, InkOcrInk.inkAt(strip, 0, 6), 0f)
    }

    @Test
    fun `a short mark is not blown up to full height`() {
        // A comma 10 px tall in a 400 px area is taken as 80 px of writing.
        val strip = InkOcrInk.render(listOf(line(50f, 50f, 48f, 60f)), 400f)!!
        val inked = (0 until PaddleOcrPipeline.REC_HEIGHT).count { y ->
            (0 until strip.width).any { x -> InkOcrInk.inkAt(strip, x, y) > 0.5f }
        }
        assertTrue("$inked rows inked", inked < 12)
    }

    @Test
    fun `a single tap draws a dot`() {
        val dot = HwStroke(listOf(HwPoint(10f, 10f, 0)))
        val strip = InkOcrInk.render(listOf(line(0f, 0f, 200f, 100f), dot), 400f)!!
        assertTrue(InkOcrInk.inkedColumns(strip) > 0)
    }

    @Test
    fun `a very long line is squeezed into the widest strip`() {
        val strip = InkOcrInk.render(listOf(line(0f, 0f, 100_000f, 50f)), 400f)!!
        assertTrue(strip.width <= PaddleOcrPipeline.REC_WIDTH * 24)
    }
}
