package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantEngine
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A full stop after a word marks the strip as openers straight away, so it
 * rests behind the toolbar every time, not only when an echo rebuilt it (#911).
 */
@RunWith(RobolectricTestRunner::class)
class SentenceOpenersTest {

    @Test
    fun `the strip after a full stop is the openers`() {
        val base = settings(twoThumb = false)
        val (service, editor) = TwoThumbKit.keyboard(base)
        seedState(service, glideReadyState(settings = base, fieldNoSuggestions = false))
        val seeds = File("../../app/src/main/assets/dictionaries/en_bigrams.txt").inputStream().use { SeedBigrams.load(it) }
        val engine = SuggestionEngine(
            PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null), seedBigrams = seeds,
        )
        engine.primaryLanguageId = "en"
        engine.englishSources = true
        plantEngine(service, engine)
        "hello".forEach { tap(service, it.toString()) }
        tap(service, ".")
        val state = service.uiState.value
        assertEquals("hello.", editor.text.toString().trimEnd())
        assertTrue("no words on the strip", state.suggestions.isNotEmpty())
        assertEquals(state.suggestions, state.sentenceOpeners)
    }

    private companion object {
        val english by lazy {
            File("../../app/dictionaries-src/en.txt").inputStream().use { DictionaryLoader.loadEntries(it) }
        }
    }
}
