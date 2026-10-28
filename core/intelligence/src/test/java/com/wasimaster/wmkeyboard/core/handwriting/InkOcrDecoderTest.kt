package com.wasimaster.wmkeyboard.core.handwriting

import kotlin.math.ln
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader behind PaddleOCR handwriting, on hand-made recogniser output:
 * the plain reading, the dictionary's say, the look-alike letters and the
 * right-to-left turn. The graphs themselves were checked against the same
 * reads in Python when they were exported (data repository,
 * `scripts/export_ppocrv5_ink_mnn.py`).
 */
class InkOcrDecoderTest {

    /** Blank, the letters, space: the shape of every PaddleOCR character list. */
    private val latin = listOf("") + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".map(Char::toString) + " "

    /**
     * One step per entry: a character read with [sure] probability, the rest
     * spread over everything else; "" is a blank step, "a|b" a step split
     * evenly between two characters.
     */
    private fun frames(charset: List<String>, vararg steps: String, sure: Float = 0.9f): FloatArray {
        val classes = charset.size
        val out = FloatArray(steps.size * classes)
        steps.forEachIndexed { t, step ->
            val picks = step.split('|').map { pick -> if (pick == "_") classes - 1 else charset.indexOf(pick) }
            val rest = (1f - sure) / (classes - picks.size)
            for (k in 0 until classes) out[t * classes + k] = rest
            picks.forEach { out[t * classes + it] = sure / picks.size }
        }
        return out
    }

    private fun read(
        decoder: InkOcrDecoder,
        charset: List<String>,
        probs: FloatArray,
        lexicon: InkLexicon? = null,
    ): List<String> = runBlocking {
        decoder.read(probs, probs.size / charset.size, charset.size, lexicon, null, 6)
    }

    @Test
    fun `the plain reading merges held letters and drops blanks`() {
        val probs = frames(latin, "h", "h", "", "e", "l", "", "l", "o", "o")
        assertEquals(listOf("hello"), read(InkOcrDecoder(latin), latin, probs))
    }

    @Test
    fun `a space read between letters splits the words`() {
        val probs = frames(latin, "h", "i", "_", "", "y", "o", "u")
        assertEquals("hi you", read(InkOcrDecoder(latin), latin, probs).first())
    }

    @Test
    fun `nothing read is no reading`() {
        val probs = frames(latin, "", "", "_", "")
        assertTrue(read(InkOcrDecoder(latin), latin, probs).isEmpty())
    }

    @Test
    fun `a dictionary word the ink allows beats a plain reading one letter off`() {
        // The second letter could be t or f; the plain reading takes f.
        val probs = frames(latin, "o", "f|t", "h", "e", "r", "s")
        val probsLeaning = probs.copyOf().also {
            val classes = latin.size
            it[classes + latin.indexOf("f")] += 0.02f
        }
        val lexicon = InkLexicon { reading, _, _ ->
            assertEquals("ofhers", reading)
            listOf("others", "offers")
        }
        val out = read(InkOcrDecoder(latin), latin, probsLeaning, lexicon)
        assertEquals("others", out.first())
        // The plain reading is still on offer.
        assertTrue("ofhers" in out)
    }

    @Test
    fun `a word the ink does not allow loses to the plain reading`() {
        val probs = frames(latin, "z", "y", "x")
        val lexicon = InkLexicon { _, _, _ -> listOf("ant") }
        assertEquals("zyx", read(InkOcrDecoder(latin), latin, probs, lexicon).first())
    }

    @Test
    fun `a capital the ink shows is kept on the dictionary word`() {
        val probs = frames(latin, "P", "a|o", "r", "i", "s")
        val lexicon = InkLexicon { _, _, _ -> listOf("paris") }
        assertEquals("Paris", read(InkOcrDecoder(latin), latin, probs, lexicon).first())
    }

    @Test
    fun `Latin look-alikes read as the Cyrillic letters`() {
        val cyrillic = listOf("") + listOf("р", "е", "к", "а", "p", "e") + " "
        val decoder = InkOcrDecoder(cyrillic, mapOf("p" to "р", "e" to "е"))
        // р and е read as their Latin twins.
        val probs = frames(cyrillic, "p", "e", "к", "а")
        assertEquals("река", read(decoder, cyrillic, probs).first())
    }

    @Test
    fun `an Arabic word on the strip is turned to read right to left`() {
        val arabic = listOf("") + listOf("ب", "ي", "ت", "1", "2") + " "
        val decoder = InkOcrDecoder(arabic, rightToLeft = true, cased = false)
        // بيت sits on the strip as ت ي ب, left to right.
        assertEquals("بيت", read(decoder, arabic, frames(arabic, "ت", "ي", "ب")).first())
        // A number keeps its own order.
        assertEquals("بيت12", decoder.logical("12تيب"))
        assertEquals("12تيب", decoder.visualOf("بيت12"))
    }

    @Test
    fun `right-to-left words are read last word first`() {
        val arabic = listOf("") + listOf("ب", "ي", "ت") + " "
        val decoder = InkOcrDecoder(arabic, rightToLeft = true, cased = false)
        // Two words on the strip: the right one is written first.
        val probs = frames(arabic, "ب", "_", "ت", "ي")
        assertEquals("يت ب", read(decoder, arabic, probs).first())
    }

    @Test
    fun `the CTC score of one held letter is its own probability`() {
        val decoder = InkOcrDecoder(latin)
        val classes = latin.size
        val probs = frames(latin, "a", sure = 0.5f)
        val logp = FloatArray(probs.size) { ln(probs[it]) }
        val a = latin.indexOf("a")
        assertEquals(ln(0.5), decoder.ctc(logp, classes, 0, 1, intArrayOf(a)), 1e-6)
    }

    @Test
    fun `a doubled letter needs a blank between its two steps`() {
        val decoder = InkOcrDecoder(latin)
        val classes = latin.size
        val probs = frames(latin, "l", "l", sure = 0.99f)
        val logp = FloatArray(probs.size) { ln(probs[it]) }
        val l = latin.indexOf("l")
        val once = decoder.ctc(logp, classes, 0, 2, intArrayOf(l))
        val twice = decoder.ctc(logp, classes, 0, 2, intArrayOf(l, l))
        assertTrue(twice == Double.NEGATIVE_INFINITY || twice < once - 5)
    }

    @Test
    fun `a spelling with a character the model lacks has no labels`() {
        assertNull(InkOcrDecoder(latin).labels("café"))
        assertEquals(3, InkOcrDecoder(latin).labels("caf")!!.size)
    }
}
