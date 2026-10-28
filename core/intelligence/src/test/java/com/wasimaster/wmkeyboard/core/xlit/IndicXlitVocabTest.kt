package com.wasimaster.wmkeyboard.core.xlit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicXlitVocabTest {

    // The published file's shape, cut down: four specials, letters, language
    // tokens; the target side mixes scripts the way the real one does.
    private val vocab = IndicXlitVocab.parse(
        sequenceOf(
            "s\t<s>", "s\t<pad>", "s\t</s>", "s\t<unk>", "s\ta", "s\tm", "s\ti", "s\t__bn__", "s\t__hi__",
            "t\t<s>", "t\t<pad>", "t\t</s>", "t\t<unk>", "t\tআ", "t\tম", "t\tি", "t\tम", "t\t‌", "t\t__bn__",
        ),
    )

    @Test
    fun aWordIsItsLanguageTokenItsLettersThenTheEnd() {
        assertArrayEquals(intArrayOf(7, 4, 5, 6, 2), vocab.encode("bn", "ami"))
        assertArrayEquals(intArrayOf(8, 4, 5, 6, 2), vocab.encode("hi", "AMI"))
    }

    @Test
    fun aCharacterTheModelNeverSawMeansNoReadings() {
        assertNull(vocab.encode("bn", "am1"))
        assertNull(vocab.encode("bn", "a'm"))
        assertNull(vocab.encode("ta", "ami"))
    }

    @Test
    fun aReadingKeepsToItsLanguagesScript() {
        val bn = vocab.allowed("bn")
        assertTrue(bn[2])
        assertTrue(bn[4] && bn[5] && bn[6])
        assertFalse(bn[7])
        assertTrue(bn[8])
        assertFalse(bn[9])
        assertFalse(bn[0] || bn[1] || bn[3])
        val hi = vocab.allowed("hi")
        assertTrue(hi[7])
        assertFalse(hi[4])
    }

    // The other direction: native characters in, the letters out.
    private val latin = IndicXlitVocab.parse(
        sequenceOf(
            "s\t<s>", "s\t<pad>", "s\t</s>", "s\t<unk>", "s\tআ", "s\tম", "s\tি", "s\t়", "s\tড", "s\t__bn__",
            "t\t<s>", "t\t<pad>", "t\t</s>", "t\t<unk>", "t\ta", "t\tm", "t\ti", "t\tmadeupword0000", "t\t__bn__",
        ),
        IndicXlitModels.LATIN,
    )

    @Test
    fun theLatinModelReadsANativeWordAsItLearnedIt() {
        assertArrayEquals(intArrayOf(9, 4, 5, 6, 2), latin.encode("bn", "আমি"))
        // No joiners, and ড় taken apart into ড and the nukta, which NFC does.
        assertArrayEquals(intArrayOf(9, 4, 5, 6, 2), latin.encode("bn", "আ\u200Cমি"))
        assertArrayEquals(intArrayOf(9, 8, 7, 2), latin.encode("bn", "\u09DC"))
        assertNull(latin.encode("bn", "ami"))
        assertNull(latin.encode("hi", "আমি"))
    }

    @Test
    fun theLatinModelWritesTheLettersAndNothingElse() {
        val bn = latin.allowed("bn")
        assertTrue(bn[2] && bn[4] && bn[5] && bn[6])
        assertFalse(bn[7])
        assertFalse(bn[8])
        assertFalse(bn[0] || bn[1] || bn[3])
    }

    @Test
    fun packedReadingsUnpackBestFirst() {
        val packed = intArrayOf(3, (-0.1f).toBits(), 4, 5, 6, 2, (-0.7f).toBits(), 4, 5)
        val readings = IndicXlitVocab.unpack(packed, vocab)
        assertEquals(listOf("আমি", "আম"), readings.map { it.first })
        assertEquals(-0.1f, readings[0].second)
    }

    @Test
    fun aTruncatedPackStopsCleanly() {
        val packed = intArrayOf(3, (-0.1f).toBits(), 4, 5, 6, 9, 0)
        assertEquals(listOf("আমি"), IndicXlitVocab.unpack(packed, vocab).map { it.first })
    }
}
