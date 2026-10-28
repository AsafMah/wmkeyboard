package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A glided word keeps the word one key off it on the strip (#979).
 *
 * A confident stroke filled the strip with its own readings, so gliding `is`
 * never offered `us`, though `u` sits right beside `i`. The stroke here starts
 * dead on `i` with the start tolerance cut to half a key, so the decoder
 * cannot read `us` off it at all: whatever puts `us` on the strip is the
 * leader's slip, not the decoder.
 */
class GlideLeaderSlipsTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private fun engine(): SuggestionEngine {
        val dictionary = Trie().apply {
            insert("is", 9_000)
            insert("us", 4_000)
        }
        return SuggestionEngine(dictionary, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = true
            tuneGlide(startRadius = 0.5f)
        }
    }

    /** Straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .mapNotNull { centers[it.code] }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        return points
    }

    @Test
    fun `a glided is keeps us among the alternates`() {
        val engine = engine()
        // The deep search behind an undo is the decoder's own list, no slips:
        // proof the stroke alone does not read us.
        val decoded = engine.glide(gestureFor("is"), grid, keyWidth, limit = 3, deep = true).map { it.word }
        assertFalse("the decoder reads us off this stroke: $decoded", "us" in decoded)

        val words = engine.glide(gestureFor("is"), grid, keyWidth, limit = 3).map { it.word }
        assertEquals("is", words.first())
        assertTrue("us offered in $words", "us" in words)
    }

    @Test
    fun `a slip never outscores a reading, so a lift cannot type it`() {
        val read = engine().glide(gestureFor("is"), grid, keyWidth, limit = 3)
        val slip = read.first { it.word == "us" }
        assertEquals(Double.NEGATIVE_INFINITY, slip.score, 0.0)
        assertTrue("$read", read.first().score > slip.score)
    }

    @Test
    fun `a sandboxed decode offers no slips`() {
        val words = engine().glide(
            gestureFor("is"), grid, keyWidth, limit = 3,
            tiers = setOf(FuzzyBeamSearch.Tier.DICTIONARY),
        ).map { it.word }
        assertFalse("$words", "us" in words)
    }
}
