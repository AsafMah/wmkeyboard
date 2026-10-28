package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaks, bests, sources and equivalents on the Statistics screen. */
class TypingInsightsTest {

    private fun day(epochDay: Int, words: Long = 10, voice: Long = 0) =
        TypingStats.DayEntry(epochDay, chars = words * 5, words = words, backspaces = 0, activeMs = 0, voiceWords = voice)

    private fun totals(
        words: Long = 0,
        chars: Long = 0,
        backspaces: Long = 0,
        predicted: Long = 0,
        completed: Long = 0,
        glide: Long = 0,
        twoThumb: Long = 0,
        voice: Long = 0,
        voiceMs: Long = 0,
        letters: Long = 0,
        saved: Long = 0,
        activeMs: Long = 0,
        distanceMm: Double = 0.0,
    ) = TypingStats.Totals(
        chars = chars, words = words, backspaces = backspaces, activeMs = activeMs, hourHistogram = emptyList(),
        keystrokesSaved = saved, wordsPredicted = predicted, wordsCompleted = completed,
        glideWords = glide, glideDistanceMm = distanceMm, twoThumbWords = twoThumb,
        voiceWords = voice, voiceMs = voiceMs, wordLetters = letters,
    )

    @Test
    fun `streaks count days in a row and stay alive until a day is missed`() {
        val entries = listOf(day(1), day(2), day(3), day(7), day(8), day(9, words = 0, voice = 4))
        assertEquals(3, TypingInsights.longestStreak(entries))
        assertEquals("today, with voice only", 3, TypingInsights.currentStreak(entries, today = 9))
        assertEquals("nothing yet today still counts from yesterday", 3, TypingInsights.currentStreak(entries, today = 10))
        assertEquals("a full day missed ends it", 0, TypingInsights.currentStreak(entries, today = 11))
        assertEquals(0, TypingInsights.longestStreak(emptyList()))
    }

    @Test
    fun `the best day counts typed and spoken words`() {
        val entries = listOf(day(1, words = 30), day(2, words = 10, voice = 25), day(3, words = 0))
        assertEquals(2, TypingInsights.bestDay(entries)?.epochDay)
        assertNull(TypingInsights.bestDay(listOf(day(1, words = 0))))
    }

    @Test
    fun `sources split every word once`() {
        val t = totals(words = 100, predicted = 10, completed = 20, glide = 30, twoThumb = 5, voice = 7)
        val sources = TypingInsights.sources(t).toMap()
        assertEquals(40L, sources[TypingInsights.Source.TYPED])
        assertEquals(25L, sources[TypingInsights.Source.GLIDED])
        assertEquals(5L, sources[TypingInsights.Source.TWO_THUMB])
        assertEquals(7L, sources[TypingInsights.Source.SPOKEN])
        assertEquals(107L, sources.values.sum())
    }

    @Test
    fun `habits and equivalents`() {
        val t = totals(
            words = 160_000, chars = 1_000, backspaces = 50, letters = 720_000,
            voice = 120, voiceMs = 60_000, saved = 400, distanceMm = 800_000.0,
        )
        assertEquals(4.5, TypingInsights.averageWordLength(t), 1e-9)
        assertEquals(5.0, TypingInsights.correctionRate(t), 1e-9)
        assertEquals(120.0, TypingInsights.speakingWpm(t), 1e-9)
        assertEquals(160_120.0 / 80_000, TypingInsights.novels(t), 1e-9)
        assertEquals(2.0, TypingInsights.trackLaps(t), 1e-9)
        assertEquals(1_050L, TypingInsights.keyPresses(t))
        // No speed of its own yet: 400 characters at 40 wpm (200 a minute) is 2 minutes.
        assertEquals(120_000L, TypingInsights.timeSavedMs(t))
        assertEquals(0.0, TypingInsights.speakingWpm(totals(voice = 5, voiceMs = 30_000)), 0.0)
    }

    @Test
    fun `the busiest weekday sums its hours`() {
        val card = MutableList(168) { 0L }
        card[2 * 24 + 9] = 5
        card[4 * 24 + 1] = 3
        card[4 * 24 + 2] = 3
        assertEquals(4, TypingInsights.busiestWeekday(card))
        assertEquals(-1, TypingInsights.busiestWeekday(List(168) { 0L }))
        assertEquals(-1, TypingInsights.busiestWeekday(emptyList()))
    }

    @Test
    fun `typo rate and trust`() {
        val t = totals(words = 200).copy(autocorrects = 6, handFixes = 4, autocorrectsKept = 15, autocorrectsUndone = 5)
        assertEquals(5.0, TypingInsights.typoRate(t), 1e-9)
        assertEquals(0.75, TypingInsights.autocorrectTrust(t)!!, 1e-9)
        assertNull("too few verdicts to say", TypingInsights.autocorrectTrust(t.copy(autocorrectsKept = 14)))
        assertEquals(0.0, TypingInsights.typoRate(totals()), 0.0)
        assertEquals(2.5, TypingInsights.emojiPer100Words(totals(words = 150, voice = 50).copy(emojiSent = 5)), 1e-9)
    }

    @Test
    fun `punctuation shares and languages are sorted biggest first`() {
        val t = totals().copy(
            punctuation = mapOf('!' to 3L, '.' to 6L, '?' to 1L, ',' to 0L),
            wordsByLanguage = mapOf("en" to 30L, "bn" to 60L, "de" to 10L, "fr" to 0L),
        )
        assertEquals(listOf('.', '!', '?'), TypingInsights.punctuationShares(t).map { it.first })
        assertEquals(0.6, TypingInsights.punctuationShares(t).first().second, 1e-9)
        assertEquals(listOf("bn", "en"), TypingInsights.topLanguages(t, limit = 2).map { it.first })
        assertEquals(0.1, TypingInsights.topLanguages(t).last().second, 1e-9)
        assertTrue(TypingInsights.punctuationShares(totals()).isEmpty())
        assertTrue(TypingInsights.topLanguages(totals()).isEmpty())
    }

    @Test
    fun `hour and week badges need a share and enough keystrokes`() {
        fun hours(vararg at: Pair<Int, Long>): List<Long> = MutableList(24) { 0L }.also { h -> at.forEach { (i, n) -> h[i] = n } }
        assertNull("too few keystrokes", TypingInsights.hoursBadge(hours(23 to 100)))
        assertEquals(TypingInsights.Persona.NIGHT_OWL, TypingInsights.hoursBadge(hours(23 to 1_000, 1 to 400, 14 to 2_600)))
        assertEquals(TypingInsights.Persona.EARLY_BIRD, TypingInsights.hoursBadge(hours(6 to 900, 14 to 2_100)))
        assertEquals(TypingInsights.Persona.LUNCH_TYPIST, TypingInsights.hoursBadge(hours(12 to 600, 13 to 600, 20 to 2_800)))
        assertNull(TypingInsights.hoursBadge(hours(10 to 4_000)))

        fun week(weekend: Long, weekday: Long): List<Long> = MutableList(168) { 0L }.also { w ->
            w[5 * 24 + 10] = weekend
            w[1 * 24 + 10] = weekday
        }
        assertEquals(TypingInsights.Persona.WEEKEND_TYPIST, TypingInsights.weekBadge(week(2_000, 2_000)))
        assertEquals(TypingInsights.Persona.WEEKDAY_TYPIST, TypingInsights.weekBadge(week(100, 3_000)))
        assertNull(TypingInsights.weekBadge(week(1_000, 3_000)))
        assertNull(TypingInsights.weekBadge(emptyList()))
    }

    @Test
    fun `backspace and punctuation badges`() {
        val careful = totals(chars = 5_000, backspaces = 100)
        assertEquals(listOf(TypingInsights.Persona.CAREFUL), TypingInsights.badges(careful))
        val fearless = totals(chars = 5_000, backspaces = 1_000)
        assertEquals(listOf(TypingInsights.Persona.FEARLESS), TypingInsights.badges(fearless))
        assertTrue("too few characters to judge", TypingInsights.badges(totals(chars = 100, backspaces = 50)).isEmpty())

        assertEquals(TypingInsights.Persona.EXCLAIMER, TypingInsights.marksBadge(totals().copy(punctuation = mapOf('!' to 60L, '.' to 50L))))
        assertEquals(TypingInsights.Persona.QUESTIONER, TypingInsights.marksBadge(totals().copy(punctuation = mapOf('?' to 70L, '.' to 40L))))
        assertEquals(TypingInsights.Persona.ELLIPSIS, TypingInsights.marksBadge(totals().copy(punctuation = mapOf('…' to 70L, '.' to 40L))))
        assertEquals(TypingInsights.Persona.FULL_STOP, TypingInsights.marksBadge(totals().copy(punctuation = mapOf('.' to 95L, '!' to 5L))))
        assertNull("a full stop that merely leads is no trait", TypingInsights.marksBadge(totals().copy(punctuation = mapOf('.' to 60L, '!' to 40L))))
        assertNull("too few marks", TypingInsights.marksBadge(totals().copy(punctuation = mapOf('!' to 50L))))
        assertEquals(TypingInsights.Persona.SHOUTER, TypingInsights.marksBadge(totals(words = 1_000).copy(shoutedWords = 60)))
        assertEquals("at most four badges", 4, TypingInsights.MAX_BADGES)
    }
}
