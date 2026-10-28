package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bengali nukta letters have to be findable from a glide whichever way they
 * are spelled, because the layouts and the word lists deliberately disagree.
 *
 * ড় ঢ় য় are in Unicode's composition-exclusion table: NFC *decomposes* them, so
 * the word list — which is NFC — holds base + U+09BC, while the shipped layouts
 * write the single precomposed code point. A grid built from only one of those
 * offers the decoder characters the trie it walks does not contain, and every
 * word spelled with one drops out of reach. [composedKeyChar] covers the
 * decomposed direction and [decomposedKeyChars] the composed one; this pins both,
 * because the failure is silent — nothing crashes, the suggestions merely stop
 * appearing for a few hundred common words.
 */
class NuktaKeySpellingTest {

    private val rra = "ড়" // ড়
    private val rha = "ঢ়" // ঢ়
    private val yya = "য়" // য়
    private val nukta = '়'

    @Test
    fun `a precomposed nukta letter also offers its decomposed spelling`() {
        for ((composed, base) in listOf(rra to 'ড', rha to 'ঢ', yya to 'য')) {
            val decomposed = decomposedKeyChars(composed)
            assertEquals(
                "$composed should decompose to its base letter plus nukta",
                listOf(base, nukta),
                decomposed,
            )
        }
    }

    @Test
    fun `a decomposed nukta letter also offers its precomposed spelling`() {
        assertEquals('য়', composedKeyChar("য়"))
        assertEquals('ড়', composedKeyChar("ড়"))
        assertEquals('ঢ়', composedKeyChar("ঢ়"))
    }

    /** The two directions are inverses, so a key is reachable from either list. */
    @Test
    fun `the two spellings resolve to the same pair of characters`() {
        for (composed in listOf(rra, rha, yya)) {
            val viaDecomposed = decomposedKeyChars(composed)!!
            val roundTripped = composedKeyChar(viaDecomposed.joinToString(""))
            assertEquals(composed.single(), roundTripped)
        }
    }

    /**
     * The common case must stay free. Every ordinary key asks this question on
     * every grid rebuild, so an unqualified NFD call on each one would be work
     * done for nothing on the other four hundred keys.
     */
    @Test
    fun `an ordinary letter has no decomposed spelling`() {
        for (label in listOf("a", "ক", "п", "あ", "?123", "")) {
            assertNull("$label should not decompose", decomposedKeyChars(label))
        }
    }

    /**
     * Only composition exclusions get the second spelling. A character NFC
     * recomposes — é, and every Indic vowel sign with a canonical
     * decomposition — is already spelled in the word list the way the key
     * writes it, so emitting its parts adds nothing and costs a great deal:
     * letting ো claim the centres of the real ে and া keys took Probhat's
     * glide accuracy from 0.771 to 0.402.
     */
    @Test
    fun `a character NFC recomposes is not given a second spelling`() {
        assertNull("é is not a composition exclusion", decomposedKeyChars("é"))
        assertNull("ো recomposes under NFC", decomposedKeyChars("ো"))
        assertNull("ৌ recomposes under NFC", decomposedKeyChars("ৌ"))
    }

    /**
     * A key whose label is a whole word must not scatter its letters across the
     * grid — the same bound [keySpelling] enforces, for the same reason.
     */
    @Test
    fun `a multi-character label is not treated as a decomposable key`() {
        assertNull(decomposedKeyChars("ABC"))
        assertNull(decomposedKeyChars("য়"))
    }

    /** The shipped Bengali built-ins must use the precomposed spelling. */
    @Test
    fun `the shipped Bengali layouts write nukta letters precomposed`() {
        val probhat = com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.PROBHAT
        val jatiya = com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.JATIYA
        val bsti = com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.BSTI
        for (layout in listOf(probhat, jatiya, bsti)) {
            val spellings = layout.layers.values
                .flatMap { it.rows.flatten() }
                .flatMap { key -> listOfNotNull(key.label, key.shiftLabel) + key.longPress }
            val decomposedPairs = spellings.filter {
                it.length == 2 && it[1] == nukta
            }
            assertTrue(
                "${layout.id} still spells a nukta letter decomposed: $decomposedPairs",
                decomposedPairs.isEmpty(),
            )
            // The bare nukta on its own key is not a decomposed letter and stays.
            assertTrue(
                "${layout.id} should reach the precomposed nukta letters",
                spellings.any { it == yya },
            )
        }
    }
}
