package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice surfaces' own tools on an engine that records a clip (#921).
 *
 * Splitting a phrase with a rail key and taking it back with Undo (#758) were
 * written for streamed words, and on Whisper or a transcription server they
 * did the wrong thing: a key went into the field ahead of the words still
 * being recorded, and Undo removed the previous phrase while the one being
 * said carried on. These pin the routes that replace that, next to the
 * streamed ones they mirror.
 */
class VoicePhraseToolsTest {

    private fun phrase(
        status: VoiceStatus = VoiceStatus.LISTENING,
        livePartial: Boolean = false,
        clipHeard: Boolean = false,
        clipInFlight: Boolean = false,
        liveWordsComposed: Boolean = false,
        sharesField: Boolean = false,
    ) = VoicePhraseState(status, livePartial, clipHeard, clipInFlight, liveWordsComposed, sharesField)

    @Test
    fun `a key over streamed words splits the phrase there`() {
        assertEquals(VoiceKeyRoute.SPLIT_LIVE, phrase(livePartial = true).keyRoute())
    }

    @Test
    fun `a key over a clip that has heard speech ends the clip there`() {
        assertEquals(VoiceKeyRoute.SPLIT_CLIP, phrase(clipHeard = true).keyRoute())
    }

    @Test
    fun `a key before anything is heard just types`() {
        assertEquals(VoiceKeyRoute.TYPE, phrase().keyRoute())
        assertEquals(VoiceKeyRoute.TYPE, phrase(status = VoiceStatus.IDLE).keyRoute())
    }

    @Test
    fun `a key while a clip transcribes waits for its words`() {
        assertEquals(
            VoiceKeyRoute.AFTER_CLIP,
            phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true).keyRoute(),
        )
        // whisper.cpp's words as they decode are no reason to go first.
        assertEquals(
            VoiceKeyRoute.AFTER_CLIP,
            phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true, livePartial = true).keyRoute(),
        )
    }

    @Test
    fun `asr4all's composed words are settled before the key`() {
        assertEquals(
            VoiceKeyRoute.SETTLE_LIVE,
            phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true, liveWordsComposed = true).keyRoute(),
        )
    }

    @Test
    fun `typing while you speak never splits or waits`() {
        assertEquals(VoiceKeyRoute.TYPE, phrase(clipHeard = true, sharesField = true).keyRoute())
        assertEquals(
            VoiceKeyRoute.TYPE,
            phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true, sharesField = true).keyRoute(),
        )
        assertEquals(VoiceKeyRoute.KEYBOARD, phrase(livePartial = true, sharesField = true).keyRoute())
    }

    @Test
    fun `a keyboard key finishes a clip that has heard speech instead of dropping it`() {
        assertTrue(phrase(clipHeard = true).keyboardKeyFinishesClip())
        assertFalse("nothing said yet: nothing to keep", phrase().keyboardKeyFinishesClip())
        assertFalse("asr4all's words are kept as composed", phrase(clipHeard = true, liveWordsComposed = true).keyboardKeyFinishesClip())
        assertFalse("typing while you speak keeps the mic open", phrase(clipHeard = true, sharesField = true).keyboardKeyFinishesClip())
        assertFalse(phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true).keyboardKeyFinishesClip())
    }

    @Test
    fun `undo while listening takes back what is being heard, streamed or recorded`() {
        assertTrue(phrase(livePartial = true).undoRelistens())
        assertTrue(phrase(clipHeard = true).undoRelistens())
        assertTrue(phrase(clipHeard = true, sharesField = true).undoRelistens())
        assertFalse("nothing heard: the last phrase that landed goes", phrase().undoRelistens())
        assertFalse(phrase(status = VoiceStatus.TRANSCRIBING, clipInFlight = true).undoRelistens())
    }

    @Test
    fun `undo shows for a clip that has heard speech`() {
        assertTrue(VoiceUi(status = VoiceStatus.LISTENING, clipHeard = true).undoReady())
        assertFalse(VoiceUi(status = VoiceStatus.LISTENING).undoReady())
        assertTrue(VoiceUi(status = VoiceStatus.LISTENING, partial = "meet me").undoReady())
        assertFalse(VoiceUi(status = VoiceStatus.FINISHING, canUndo = true, clipHeard = true).undoReady())
        assertTrue(VoiceUi(status = VoiceStatus.TRANSCRIBING, canUndo = true).undoReady())
    }
}
