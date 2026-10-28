package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phonetic language under "Also suggest from" on an English keyboard: its readings on the Latin strip. */
class PhoneticSecondaryReadingsTest {

    private val english = Trie().apply {
        insert("the", 10000)
        insert("to", 9800)
        insert("because", 3000)
        insert("hello", 830)
        insert("kettle", 200)
    }

    private val bengali = BengaliPhoneticIndex(
        listOf(
            "আমি" to 9000,
            "তো" to 5200,
            "কেমন" to 5000,
            "হ্যালো" to 1900,
        ),
    )

    private val spellings = SpellingMap.load(
        "hello\tহ্যালো\n".byteInputStream(Charsets.UTF_8),
        "to\tতো\nkemon\tকেমন\n".byteInputStream(Charsets.UTF_8),
        loanwordStreams = 1,
    )

    private fun engine(secondary: Boolean = true) =
        SuggestionEngine(english, bengali, UserLexicon(null), spellings).apply {
            primaryLanguageId = "en"
            englishSources = true
            if (secondary) {
                secondaryDictionaries = listOf(SecondaryDictionary("bn", PackedTrie.EMPTY))
                phoneticSecondaries = listOf("bn")
            }
        }

    private fun SuggestionEngine.strip(buffer: String) = suggest(buffer, previousWord = null, phoneticSlots = 3)

    private fun isBengali(word: String) = word.any { it in 'ঀ'..'৿' }

    @Test fun withoutAPhoneticSecondaryTheStripIsLatin() {
        for (buffer in listOf("kemon", "to", "wasi")) {
            assertTrue(buffer, engine(secondary = false).strip(buffer).none(::isBengali))
        }
    }

    @Test fun aWordOnlyTheLanguageKnowsIsOfferedRightAfterTheHead() {
        val strip = engine().strip("kemon")
        assertTrue(strip.toString(), "কেমন" in strip.take(2))
        // Sentence case is the keyboard's, not a scheme's capital letter.
        assertTrue("কেমন" in engine().strip("Kemon").take(2))
    }

    @Test fun aWordOnlyEnglishKnowsGetsNoReading() {
        assertTrue(engine().strip("because").none(::isBengali))
        // Nor does a word English is still on its way to.
        assertTrue(engine().strip("becau").none(::isBengali))
    }

    @Test fun aWordBothKnowKeepsEnglishInFrontAndTheReadingOnTheLastChip() {
        val strip = engine().strip("to")
        assertEquals("to", strip.first())
        assertEquals(strip.toString(), "তো", strip.getOrNull(minOf(2, strip.size - 1)))
        // A listed loanword is the English word first, too.
        val hello = engine().strip("hello")
        assertEquals("hello", hello.first())
        assertTrue(hello.toString(), "হ্যালো" in hello)
    }

    @Test fun aWordNobodyKnowsIsOfferedInTheLanguagesScript() {
        val strip = engine().strip("wasi")
        assertTrue(strip.toString(), AvroPhonetic.transliterate("wasi") in strip)
    }

    @Test fun notLettersNoReading() {
        assertTrue(engine().strip("k3mon").none(::isBengali))
        assertTrue(engine().strip("keMon").none(::isBengali))
    }

    @Test fun aPickedReadingCountsForItsLanguage() {
        val e = engine()
        assertTrue("bn" in e.owningLanguages("কেমন"))
        assertFalse("bn" in e.owningLanguages("kettle"))
    }
}
