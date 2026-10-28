package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantEngine
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.strip
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * "id" drawn or typed, with one finger or two, has "I'd" right behind it on
 * the strip, on the bundled English list, and is still typed as "id" (#384).
 *
 * Only the space bar's half of a contraction, the rewrite of a spelling that is
 * no word ("dont"), used to reach a glide; a spelling that is a word of its own
 * came back alone, so "I'd" could not be swiped at all. Two thumbs read a word
 * three ways, and each is here: a lone swipe by the stroke decoder, taps and a
 * swipe together by `DuetBeam`, and taps alone as typing.
 */
@RunWith(RobolectricTestRunner::class)
class GlideApostropheOfferTest {

    /** The strip's default width; see `SuggestionStripSettings.slotCount`. */
    private val slots = 3

    /** A service on the bundled English list, in a field that asks for suggestions. */
    private fun keyboard(settings: KeyboardSettings): Pair<WMKeyboardService, RecordingEditor> {
        val (service, editor) = TwoThumbKit.keyboard(settings)
        seedState(service, glideReadyState(settings = settings, fieldNoSuggestions = false))
        val engine = SuggestionEngine(PackedTrie.of(english), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        // As the service sets up an English engine.
        engine.primaryLanguageId = "en"
        engine.englishSources = true
        engine.apostropheFixes = settings.autoText.apostrophe
        plantEngine(service, engine)
        return service to editor
    }

    /** A stroke through the centres of [letters]' keys, in order. */
    private fun strokeThrough(letters: String): List<GesturePoint> {
        val centers = glideKeyCenters()
        val stops = letters.map { centers.getValue(it.code) }
        val points = ArrayList<GesturePoint>()
        stops.zipWithNext().forEach { (from, to) ->
            for (step in 0 until STEPS) {
                val f = step / STEPS.toFloat()
                points += GesturePoint(
                    x = from.first + (to.first - from.first) * f,
                    y = from.second + (to.second - from.second) * f,
                    t = points.size * STEP_MS,
                )
            }
        }
        val (x, y) = stops.last()
        points += GesturePoint(x, y, points.size * STEP_MS)
        return points
    }

    private fun swipe(service: WMKeyboardService, letters: String) {
        service.onGesture(strokeThrough(letters), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())
        drain(service)
    }

    /** Taps [letters], and waits out the strip's debounce and the pass behind it. */
    private fun type(service: WMKeyboardService, letters: String) {
        letters.forEach { tap(service, it.toString()) }
        val job = WMKeyboardService::class.java.getDeclaredField("suggestionJob").apply { isAccessible = true }
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            if ((job.get(service) as Job?)?.isActive != true) return
            Thread.sleep(STEP_MS)
        }
    }

    private fun endWord(service: WMKeyboardService) {
        service.onKey(space)
        drain(service)
    }

    @Test
    fun `a glided id types id and offers I'd right behind it`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        swipe(service, "id")
        assertEquals("id ", editor.text.toString())
        assertEquals(listOf("id", "I'd"), strip(service).take(2))
    }

    @Test
    fun `with apostrophe fixes off a glided id offers nothing more`() {
        val off = settings(twoThumb = false).let { it.copy(autoText = it.autoText.copy(apostrophe = false)) }
        val (service, editor) = keyboard(off)
        swipe(service, "id")
        assertEquals("id ", editor.text.toString())
        assertEquals("id", strip(service).firstOrNull())
        assertFalse("${strip(service)}", "I'd" in strip(service))
    }

    @Test
    fun `a two-thumb swipe of id offers I'd behind it and types id`() {
        val (service, editor) = keyboard(settings())
        swipe(service, "id")
        assertEquals("id", editor.text.toString())
        assertEquals(listOf("id", "I'd"), strip(service).take(2))
        endWord(service)
        assertEquals("id ", editor.text.toString())
    }

    @Test
    fun `two-thumb taps and a swipe offer the contraction behind the word they read`() {
        // Not w + ed for we'd: that stroke draws "weed" as well, the commoner
        // word, which the reading rightly prefers since #886.
        for ((tapped, swiped, offered) in listOf(Triple("i", "ts", "it's"), Triple("w", "ere", "we're"))) {
            val read = tapped + swiped
            val (service, editor) = keyboard(settings())
            type(service, tapped)
            swipe(service, swiped)
            assertEquals(read, editor.text.toString())
            assertEquals(read, listOf(read, offered), strip(service).take(2))
            endWord(service)
            assertEquals("$read ", editor.text.toString())
        }
    }

    @Test
    fun `two-thumb taps of id offer I'd behind it and type id`() {
        val (service, editor) = keyboard(settings())
        type(service, "id")
        assertEquals("id", editor.text.toString())
        val shown = strip(service).take(slots)
        assertTrue("$shown", "id" in shown && "I'd" in shown)
        assertTrue("$shown", shown.indexOf("id") < shown.indexOf("I'd"))
        endWord(service)
        assertEquals("id ", editor.text.toString())
    }

    /** Puts [text] in the field as typed words, so the next glide reads it as context. */
    private fun typeWords(service: WMKeyboardService, text: String) {
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            type(service, word)
            endWord(service)
        }
    }

    @Test
    fun `hell glided where a clause starts types he'll, with hell behind it`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        typeWords(service, "I think")
        val before = editor.text.toString()
        swipe(service, "hell")
        assertEquals("${before}he'll ", editor.text.toString())
        assertEquals(listOf("he'll", "hell"), strip(service).take(2))
    }

    @Test
    fun `hell glided after other words stays hell, with he'll behind it`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        typeWords(service, "go to")
        val before = editor.text.toString()
        swipe(service, "hell")
        assertEquals("${before}hell ", editor.text.toString())
        assertEquals(listOf("hell", "he'll"), strip(service).take(2))
    }

    private companion object {
        const val STEPS = 5
        const val STEP_MS = 20L
        const val SETTLE_ROUNDS = 100

        /** The bundled English list, as the app compiles it. */
        val english by lazy {
            File("../../app/dictionaries-src/en.txt").inputStream().use { DictionaryLoader.loadEntries(it) }
        }
    }
}