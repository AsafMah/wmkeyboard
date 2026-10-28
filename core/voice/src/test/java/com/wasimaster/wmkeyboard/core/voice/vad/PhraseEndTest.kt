package com.wasimaster.wmkeyboard.core.voice.vad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseEndTest {

    private val rate = 16_000

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    private fun tone(seconds: Double) = FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * 220 * it / rate)).toFloat() }

    private fun loudness(hangSeconds: Float, cuts: Boolean) = FrameDetector(
        LoudnessFrames(0.015f),
        threshold = 0.5f,
        minSpeech = 25,
        hang = (hangSeconds * 100).toInt(),
        maxSpeech = 30_000,
        cuts = cuts,
    )

    /** Feeds [audio] in 100 ms chunks, the way the live loop hands the recorder's samples over. */
    private fun SpeechDetector.feedAll(audio: FloatArray): Boolean {
        var last = false
        var at = 0
        while (at < audio.size) {
            val n = minOf(1600, audio.size - at)
            last = feed(audio.copyOfRange(at, at + n), n)
            at += n
        }
        return last
    }

    @Test
    fun loudnessHearsSpeechAndItsEnd() {
        val d = loudness(0.3f, cuts = false)
        assertFalse(d.feedAll(silence(0.5)))
        assertTrue(d.feedAll(tone(1.0)))
        // Within the hang it still counts as speech...
        assertTrue(d.feedAll(silence(0.2)))
        // ...and past it, no longer.
        assertFalse(d.feedAll(silence(0.3)))
    }

    @Test
    fun aClickIsNotSpeech() {
        val d = loudness(0.3f, cuts = false)
        d.feedAll(silence(0.5))
        assertFalse(d.feedAll(tone(0.1) + silence(0.5)))
    }

    @Test
    fun cuttingKeepsOnePhrasePerStretchOfSpeech() {
        val d = loudness(0.5f, cuts = true)
        d.feedAll(silence(1.0) + tone(1.0) + silence(1.0) + tone(0.8) + silence(1.0))
        assertNotNull(d.phrase())
        assertNotNull(d.phrase())
        assertNull(d.phrase())
    }

    @Test
    fun phraseLengthsCoverTheSpeech() {
        val d = loudness(0.5f, cuts = true)
        d.feedAll(silence(1.0) + tone(1.0) + silence(1.0))
        val phrase = d.phrase()!!
        // The tone, a little lead-in, and the hang's tail of quiet.
        assertTrue("phrase is ${phrase.size}", phrase.size in (1.0 * rate).toInt()..(1.7 * rate).toInt())
        assertNull(d.phrase())
    }

    @Test
    fun flushHandsOverSpeechStillGoing() {
        val d = loudness(0.5f, cuts = true)
        d.feedAll(silence(0.5) + tone(1.0))
        assertNull(d.phrase())
        d.flush()
        assertNotNull(d.phrase())
    }

    @Test
    fun aLongPhraseIsCut() {
        val d = FrameDetector(LoudnessFrames(0.015f), 0.5f, minSpeech = 25, hang = 50, maxSpeech = 200, cuts = true)
        d.feedAll(silence(0.5) + tone(5.0))
        var count = 0
        while (d.phrase() != null) count++
        assertTrue("cut into $count", count >= 2)
    }

    @Test
    fun phraseEndWaitsOutThePause() {
        val end = PhraseEnd(loudness(0.3f, cuts = false), quietToEnd = (0.9 * rate).toInt(), smartTurnDir = null)
        end.feed(silence(1.0))
        assertFalse("quiet before speech never ends it", end.ended)
        feedChunks(end, tone(1.0))
        assertFalse(end.ended)
        feedChunks(end, silence(0.8))
        assertFalse(end.ended)
        feedChunks(end, silence(0.6))
        assertTrue(end.ended)
        assertTrue(end.heardSpeech)
    }

    @Test
    fun phraseEndBelowZeroNeverEnds() {
        val end = PhraseEnd(loudness(0.3f, cuts = false), quietToEnd = -1, smartTurnDir = null)
        feedChunks(end, tone(1.0) + silence(5.0))
        assertFalse(end.ended)
    }

    private fun feedChunks(end: PhraseEnd, audio: FloatArray) {
        var at = 0
        while (at < audio.size) {
            val n = minOf(1600, audio.size - at)
            end.feed(audio.copyOfRange(at, at + n), n)
            at += n
        }
    }

    @Test
    fun kaldiCmvnGivesMeansAndInverseDeviations() {
        // Two features over two frames: values (0, 1) and (2, 3).
        // Sums 2 and 4, count 2; squares 4 and 10.
        val stats = doubleArrayOf(2.0, 4.0, 2.0, 4.0, 10.0, 0.0)
        val bytes = ByteBuffer.allocate(15 + stats.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0); put('B'.code.toByte()); put("DM ".toByteArray(Charsets.US_ASCII))
            put(4); putInt(2); put(4); putInt(3)
            stats.forEach { putDouble(it) }
        }.array()
        val (mean, scale) = KaldiCmvn.parse(bytes)!!
        assertArrayEquals(floatArrayOf(1f, 2f), mean, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), scale, 1e-6f)
    }

    @Test
    fun kaldiCmvnTurnsAwayOtherFiles() {
        assertNull(KaldiCmvn.parse(ByteArray(0)))
        assertNull(KaldiCmvn.parse("not a matrix at all".toByteArray()))
    }

    @Test
    fun loudnessFramesAreTenMilliseconds() {
        val frames = LoudnessFrames(0.015f)
        assertEquals(0, frames.frames(FloatArray(100), 100).size)
        assertEquals(1, frames.frames(FloatArray(100), 100).size)
        assertEquals(10, frames.frames(tone(0.1), 1600).size)
    }
}
