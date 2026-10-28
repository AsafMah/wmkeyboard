package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class TypingStatsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "stats/typing_stats.json")

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** A store pinned to UTC so a test's day math never depends on the machine. */
    private fun store(target: File? = file()): TypingStats =
        TypingStats(target) { utc }.also { it.enabled = true }

    // A moment safely inside a UTC day (12:00), and a keystroke cadence.
    private val noon = 1_754_000_000_000L - (1_754_000_000_000L % DAY) + DAY / 2

    private fun TypingStats.type(text: String, startUptime: Long = 10_000L): Long {
        var uptime = startUptime
        for (char in text) {
            uptime += CADENCE
            if (char == ' ') onSeparator(noon, uptime) else onTyped(char.toString(), noon, uptime)
        }
        return uptime
    }

    @Test
    fun `local epoch day follows the zone, not UTC`() {
        val dhaka = TimeZone.getTimeZone("Asia/Dhaka") // UTC+6, no DST
        // 20:00 UTC is already the next day in Dhaka.
        val evening = 100L * DAY + 20 * HOUR
        assertEquals(100, TypingStatsMath.localEpochDay(evening, utc))
        assertEquals(101, TypingStatsMath.localEpochDay(evening, dhaka))
        // 02:00 UTC is still the previous day in a UTC-5 zone.
        val night = 100L * DAY + 2 * HOUR
        assertEquals(99, TypingStatsMath.localEpochDay(night, TimeZone.getTimeZone("America/Bogota")))
        assertEquals(23, TypingStatsMath.hourOf(100L * DAY - HOUR, utc))
    }

    @Test
    fun `weeks align to Monday and months to the calendar`() {
        // Epoch day 0 was Thursday 1970-01-01; the Monday of that week is day -3.
        val thursday = 0
        assertEquals(TypingStatsMath.weekIndexOf(thursday), TypingStatsMath.weekIndexOf(-3))
        assertTrue(TypingStatsMath.weekIndexOf(-4) < TypingStatsMath.weekIndexOf(-3))
        // Day 3 was the following Sunday, day 4 the next Monday.
        assertEquals(TypingStatsMath.weekIndexOf(0), TypingStatsMath.weekIndexOf(3))
        assertTrue(TypingStatsMath.weekIndexOf(4) > TypingStatsMath.weekIndexOf(3))
        assertEquals(-3, TypingStatsMath.weekStartOf(TypingStatsMath.weekIndexOf(0)))

        // December 1970 and January 1971 are adjacent month keys.
        val december = TypingStatsMath.monthKeyOf(360)
        val january = TypingStatsMath.monthKeyOf(370)
        assertEquals(december + 1, january)
        assertEquals(365, TypingStatsMath.monthStartOf(january))
    }

    @Test
    fun `typed characters and separators count words by boundary`() {
        val stats = store(null)
        stats.type("hi there.")
        val totals = stats.lifetime()
        assertEquals(9, totals.chars)
        // "hi" at the space, "there" at the full stop.
        assertEquals(2, totals.words)
    }

    @Test
    fun `the trailing word is counted at save`() {
        val stats = store()
        stats.type("hello")
        assertEquals(0, stats.lifetime().words)
        stats.save()
        assertEquals(1, stats.lifetime().words)
        assertEquals(1, store().also { it.reload() }.lifetime().words)
    }

    @Test
    fun `a suggestion pick never double-counts the half-typed word`() {
        val stats = store(null)
        val uptime = stats.type("he")
        stats.onWordsCommitted(1, noon)
        stats.onSeparator(noon, uptime + CADENCE)
        assertEquals("the pick's word only", 1, stats.lifetime().words)
        stats.type("hi ", startUptime = uptime + 2 * CADENCE)
        assertEquals(2, stats.lifetime().words)
    }

    @Test
    fun `active time takes typing gaps and refuses pauses`() {
        val stats = store(null)
        var uptime = 10_000L
        stats.onTyped("a", noon, uptime)
        uptime += 1_000L
        stats.onTyped("b", noon, uptime)
        uptime += 1_000L
        stats.onTyped("c", noon, uptime)
        assertEquals(2_000L, stats.lifetime().activeMs)
        // A gap over the burst ceiling is thinking, not typing.
        uptime += TypingStats.BURST_GAP_MS + 1
        stats.onTyped("d", noon, uptime)
        assertEquals(2_000L, stats.lifetime().activeMs)
        assertEquals(60.0, TypingStatsMath.wpm(300, 60_000), 1e-9)
        assertEquals(0.0, TypingStatsMath.wpm(300, 0), 1e-9)
    }

    @Test
    fun `a disabled stretch adds nothing, not even a short one`() {
        val stats = store(null)
        stats.onTyped("a", noon, 10_000L)
        stats.onTyped("b", noon, 11_000L)
        stats.enabled = false
        stats.onTyped("x", noon, 12_000L)
        // Re-enabled two seconds later: under the burst ceiling, but the
        // pause must still not count as active typing time.
        stats.enabled = true
        stats.onTyped("c", noon, 13_000L)
        val totals = stats.lifetime()
        assertEquals("the paused keystroke is not counted", 3, totals.chars)
        assertEquals(1_000L, totals.activeMs)
        stats.onTyped("d", noon, 14_000L)
        assertEquals(2_000L, stats.lifetime().activeMs)
    }

    @Test
    fun `the snapshot survives the round trip`() {
        val first = store()
        first.type("hi there ")
        first.onBackspace(noon, 99_000L)
        first.save()
        val second = store()
        assertEquals(first.lifetime(), second.lifetime())
        assertEquals(first.dayEntries(), second.dayEntries())
        assertEquals(1, second.dayEntries().size)
        assertEquals(
            TypingStatsMath.localEpochDay(noon, utc),
            second.dayEntries().single().epochDay,
        )
    }

    @Test
    fun `a null file is memory only and clear deletes the real one`() {
        val memory = store(null)
        memory.type("abc ")
        memory.save()
        assertFalse(file().exists())

        val real = store()
        real.type("abc ")
        real.save()
        assertTrue(file().exists())
        real.clear()
        assertFalse(file().exists())
        assertEquals(0, real.lifetime().chars)
        assertEquals(0, store().lifetime().chars)
    }

    @Test
    fun `old day buckets are pruned but the lifetime totals survive`() {
        val stats = store()
        stats.onTyped("a", noon, 1_000L)
        val later = noon + (TypingStats.MAX_DAYS + 1L) * DAY
        stats.onTyped("b", later, 2_000L)
        stats.save()
        val reread = store()
        assertEquals(1, reread.dayEntries().size)
        assertEquals("pruning must not eat the totals", 2, reread.lifetime().chars)
    }

    @Test
    fun `unknown json keys are ignored`() {
        val target = file()
        target.parentFile?.mkdirs()
        target.writeText("""{"totalChars":7,"someFutureField":true}""")
        assertEquals(7, store().lifetime().chars)
    }

    @Test
    fun `day rollup zero-fills the window`() {
        val today = TypingStatsMath.localEpochDay(noon, utc)
        val entries = listOf(
            TypingStats.DayEntry(today, chars = 10, words = 2, backspaces = 1, activeMs = 1_000),
            TypingStats.DayEntry(today - 2, chars = 5, words = 1, backspaces = 0, activeMs = 500),
        )
        val buckets = TypingStatsMath.rollup(entries, StatsPeriod.DAY, today)
        assertEquals(TypingStatsMath.DAY_BUCKETS, buckets.size)
        assertEquals(today, buckets.last().startEpochDay)
        assertEquals(10, buckets.last().chars)
        assertEquals(5, buckets[buckets.size - 3].chars)
        assertEquals(0, buckets.first().chars)
    }

    @Test
    fun `week and month rollups sum their days`() {
        val today = TypingStatsMath.localEpochDay(noon, utc)
        val monday = TypingStatsMath.weekStartOf(TypingStatsMath.weekIndexOf(today))
        val entries = listOf(
            TypingStats.DayEntry(monday, chars = 3, words = 1, backspaces = 0, activeMs = 100),
            TypingStats.DayEntry(today, chars = 4, words = 1, backspaces = 0, activeMs = 100),
        )
        val weeks = TypingStatsMath.rollup(entries, StatsPeriod.WEEK, today)
        assertEquals(TypingStatsMath.WEEK_BUCKETS, weeks.size)
        assertEquals("both days land in the current week", 7, weeks.last().chars)

        val months = TypingStatsMath.rollup(entries, StatsPeriod.MONTH, today)
        assertEquals(TypingStatsMath.MONTH_BUCKETS, months.size)
        assertEquals(7, months.sumOf { it.chars })
    }

    @Test
    fun `other devices' counts add to this one's and are never saved as its own`() {
        val own = store()
        own.type("hi there ")
        own.onKeyTap("qwerty", "English", 1f, 1f, emptyList())
        own.save()

        val day = TypingStatsMath.localEpochDay(noon, utc)
        val devices = """{
            "bbbbbbbb": {"days": {"$day": {"chars": 100, "words": 20}}, "totalChars": 100, "totalWords": 20,
                         "hourHistogram": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5],
                         "heatmaps": {"qwerty": {"taps": 4, "cells": {"7": 4}}}},
            "legacy": {"totalChars": 1000, "glideWords": 3}
        }"""
        val view = store().also { it.absorbDevices(devices) }
        val totals = view.lifetime()
        assertEquals(9L + 100 + 1000, totals.chars)
        assertEquals(2L + 20, totals.words)
        assertEquals(3L, totals.glideWords)
        assertEquals("seven letters here at noon, five there", 7L + 5, totals.hourHistogram[12])
        assertEquals(9L + 100, view.dayEntries().single().chars)
        assertEquals("one board, both devices' taps", 5L, view.heatmaps().single().taps)

        assertEquals("the file still holds only this device's", 9L, store().lifetime().chars)
        view.absorbDevices("not json")
        assertEquals("a broken file adds nothing", totals, view.lifetime())
    }

    @Test
    fun `a two-thumb word counts once as a glided word and saves the taps it skipped`() {
        val stats = store(null)
        // A tapped "h" arms the word, then a swipe finishes "hello" (#751).
        stats.onTyped("h", noon, 10_000L)
        stats.onGlideWord(letters = 5, distanceMm = 12.0, nowMillis = noon, gestures = 2)
        // The space that ends it finds no armed word left to count again.
        stats.onSeparator(noon, 10_400L)
        val totals = stats.lifetime()
        assertEquals(1, totals.words)
        assertEquals(1, totals.glideWords)
        assertEquals(1, totals.twoThumbWords)
        assertEquals("five letters, two gestures", 3, totals.keystrokesSaved)
        assertEquals("the tapped h is not counted twice", 5, totals.wordLetters)
    }

    @Test
    fun `a lone swipe is a glide but not a two-thumb word`() {
        val stats = store(null)
        stats.onGlideWord(letters = 5, distanceMm = 12.0, nowMillis = noon, gestures = 1)
        val totals = stats.lifetime()
        assertEquals(1, totals.glideWords)
        assertEquals(0, totals.twoThumbWords)
        assertEquals(4, totals.keystrokesSaved)
    }

    @Test
    fun `voice phrases count apart from the typing`() {
        val stats = store(null)
        stats.type("hi ")
        stats.onVoicePhrase(words = 6, listenedMs = 4_000L, nowMillis = noon)
        // A microphone left open for an hour is not talking time.
        stats.onVoicePhrase(words = 2, listenedMs = 3_600_000L, nowMillis = noon)
        val totals = stats.lifetime()
        assertEquals("only the typed word", 1, totals.words)
        assertEquals(3, totals.chars)
        assertEquals(8, totals.voiceWords)
        assertEquals(2, totals.voicePhrases)
        assertEquals(4_000L, totals.voiceMs)
        assertEquals(8, stats.dayEntries().single().voiceWords)
    }

    @Test
    fun `the fastest burst needs a long enough stretch and survives a pause`() {
        val stats = store(null)
        // 60 characters at 200 ms each: 12 s, too short to be a record.
        var uptime = stats.type("a".repeat(60))
        assertEquals(0.0, stats.lifetime().peakBurstWpm, 0.0)
        // 40 more makes 100 characters over about 20 s: 60 wpm.
        uptime = stats.type("a".repeat(40), uptime)
        val peak = stats.lifetime().peakBurstWpm
        assertEquals(60.0, peak, 1.0)
        // After a pause, slow typing cannot lower the record.
        stats.type("a".repeat(5), uptime + 60_000L)
        assertEquals(peak, stats.lifetime().peakBurstWpm, 0.0)
    }

    @Test
    fun `the punch card files keystrokes by weekday and hour`() {
        val stats = store(null)
        stats.type("ab")
        val weekday = TypingStatsMath.weekdayOf(TypingStatsMath.localEpochDay(noon, utc))
        val card = stats.lifetime().weekHourHistogram
        assertEquals(168, card.size)
        assertEquals(2L, card[weekday * 24 + 12])
        assertEquals(2L, card.sum())
        // Epoch day 0 was a Thursday; Monday is 0.
        assertEquals(3, TypingStatsMath.weekdayOf(0))
        assertEquals(0, TypingStatsMath.weekdayOf(4))
    }

    @Test
    fun `the new counts survive a save and the best burst is a best across devices`() {
        val first = store()
        first.onVoicePhrase(3, 2_000L, noon)
        first.onGlideWord(letters = 4, distanceMm = 5.0, nowMillis = noon, gestures = 3)
        first.type("a".repeat(100))
        first.save()
        val second = store()
        assertEquals(first.lifetime(), second.lifetime())
        val devices = """{"other":{"voiceWords":10,"peakBurstWpm":1.0,"twoThumbWords":2}}"""
        val view = store().also { it.absorbDevices(devices) }
        assertEquals(13, view.lifetime().voiceWords)
        assertEquals(3, view.lifetime().twoThumbWords)
        assertEquals(second.lifetime().peakBurstWpm, view.lifetime().peakBurstWpm, 0.0)
    }

    @Test
    fun `spoken words count spaced pieces and unspaced letters`() {
        assertEquals(4, TypingStatsMath.spokenWords("Hello there, how's it"))
        assertEquals(0, TypingStatsMath.spokenWords("  ... "))
        assertEquals(4, TypingStatsMath.spokenWords("你好世界"))
        assertEquals(3, TypingStatsMath.spokenWords("OK 你好"))
    }

    @Test
    fun `typos are counted as events and end the clean run`() {
        val stats = store(null)
        var uptime = stats.type("one two three ")
        assertEquals(3, stats.lifetime().bestCleanRun)
        stats.onAutocorrect(offer = false, nowMillis = noon)
        uptime = stats.type("four ", uptime)
        stats.onAutocorrect(offer = true, nowMillis = noon)
        stats.onAutocorrectKept()
        stats.onAutocorrectKept()
        stats.onAutocorrectUndone()
        stats.onHandFix(noon)
        uptime = stats.type("five six ", uptime)
        stats.onBackspace(noon, uptime + CADENCE)
        val totals = stats.lifetime()
        assertEquals(2, totals.autocorrects)
        assertEquals(1, totals.offersTaken)
        assertEquals(2, totals.autocorrectsKept)
        assertEquals(1, totals.autocorrectsUndone)
        assertEquals(1, totals.handFixes)
        assertEquals("the first run of three stands", 3, totals.bestCleanRun)
        val day = stats.dayEntries().single()
        assertEquals(2, day.autocorrects)
        assertEquals(1, day.handFixes)
        assertEquals(3, TypingStatsMath.rollup(stats.dayEntries(), StatsPeriod.DAY, day.epochDay).last().typos)
    }

    @Test
    fun `a clean run in progress counts towards the best`() {
        val stats = store(null)
        stats.type("a b c d e ")
        assertEquals("no typo yet, so the run so far is the best", 5, stats.lifetime().bestCleanRun)
        stats.onAutocorrect(offer = false, nowMillis = noon)
        stats.type("f ", 60_000L)
        assertEquals(5, stats.lifetime().bestCleanRun)
    }

    @Test
    fun `punctuation is counted by mark and three dots make an ellipsis`() {
        val stats = store(null)
        stats.type("hi! ok? yes, no... so…")
        val marks = stats.lifetime().punctuation
        assertEquals(1L, marks['!'])
        assertEquals(1L, marks['?'])
        assertEquals(1L, marks[','])
        assertEquals("the three dots moved over", 0L, marks['.'])
        assertEquals(2L, marks[TypingStats.ELLIPSIS])
        // A dot, a letter, two dots: no run of three.
        stats.type(".a..", 90_000L)
        assertEquals(3L, stats.lifetime().punctuation['.'])
        assertEquals(2L, stats.lifetime().punctuation[TypingStats.ELLIPSIS])
    }

    @Test
    fun `a word in capitals is a shout, a capital initial is not`() {
        val stats = store(null)
        stats.type("WOW Hello OK I a1B ")
        // WOW and OK; "I" is one letter, "a1B" has a lowercase letter.
        assertEquals(2, stats.lifetime().shoutedWords)
    }

    @Test
    fun `the record book keeps the longest of everything`() {
        val stats = store(null)
        var uptime = stats.type("hi extraordinary ")
        stats.onGlideWord(letters = 7, distanceMm = 10.0, nowMillis = noon)
        stats.onWordsPicked(1, typed = 0, letters = 4, spaced = true, nowMillis = noon)
        repeat(4) {
            uptime += CADENCE
            stats.onBackspace(noon, uptime)
        }
        uptime = stats.type("x ", uptime)
        stats.onBackspace(noon, uptime + CADENCE)
        stats.onWordDeleted()
        stats.onEmoji()
        stats.onEmoji()
        val totals = stats.lifetime()
        assertEquals(13, totals.longestWordLetters)
        assertEquals(7, totals.longestGlideLetters)
        assertEquals(4, totals.longestBackspaceRun)
        assertEquals(1, totals.wordsDeletedWhole)
        assertEquals(2, totals.emojiSent)
        assertTrue("the burst so far is the longest", totals.longestBurstMs > 0L)
        // A pause ends the burst; a shorter one after it cannot lower the record.
        val record = totals.longestBurstMs
        stats.type("ab", uptime + TypingStats.BURST_GAP_MS + 10_000L)
        assertEquals(record, stats.lifetime().longestBurstMs)
    }

    @Test
    fun `words go to the language they were typed under`() {
        val stats = store(null)
        stats.language = "en"
        val uptime = stats.type("one two ")
        stats.language = "bn"
        stats.onWordsCommitted(1, noon)
        stats.language = ""
        stats.type("none ", uptime)
        val byLanguage = stats.lifetime().wordsByLanguage
        assertEquals(2L, byLanguage["en"])
        assertEquals(1L, byLanguage["bn"])
        assertEquals("no language, no entry", 2, byLanguage.size)
    }

    @Test
    fun `the language table makes room past the cap`() {
        val stats = store(null)
        for (i in 0 until TypingStats.MAX_LANGUAGES) {
            stats.language = "lang$i"
            stats.onWordsCommitted(i + 2, noon)
        }
        stats.language = "new"
        stats.onWordsCommitted(1, noon)
        val byLanguage = stats.lifetime().wordsByLanguage
        assertEquals(TypingStats.MAX_LANGUAGES, byLanguage.size)
        assertFalse("lang0" in byLanguage)
        assertTrue("new" in byLanguage)
    }

    @Test
    fun `the typo and record counts survive the round trip and merge across devices`() {
        val first = store()
        first.language = "en"
        first.type("WOW! ok... yes ")
        first.onAutocorrect(offer = true, nowMillis = noon)
        first.onAutocorrectKept()
        first.onHandFix(noon)
        first.onEmoji()
        first.onWordDeleted()
        first.save()
        val second = store()
        assertEquals(first.lifetime(), second.lifetime())
        assertEquals(first.dayEntries(), second.dayEntries())

        val devices = """{"other":{"autocorrects":5,"handFixes":2,"bestCleanRun":40,"punctuation":{"!":3,"…":1},
            "shoutedWords":1,"longestWordLetters":20,"longestBackspaceRun":9,"emojiSent":4,
            "wordsByLanguage":{"en":10,"de":3}}}"""
        val view = store().also { it.absorbDevices(devices) }
        val totals = view.lifetime()
        assertEquals(1L + 5, totals.autocorrects)
        assertEquals(1L + 2, totals.handFixes)
        assertEquals("a best is a best on any device", 40L, totals.bestCleanRun)
        assertEquals(1L + 3, totals.punctuation['!'])
        assertEquals(1L + 1, totals.punctuation[TypingStats.ELLIPSIS])
        assertEquals(2L, totals.shoutedWords)
        assertEquals(20, totals.longestWordLetters)
        assertEquals(9L, totals.longestBackspaceRun)
        assertEquals(5L, totals.emojiSent)
        assertEquals(3L + 10, totals.wordsByLanguage["en"])
        assertEquals(3L, totals.wordsByLanguage["de"])
    }

    @Test
    fun `an old snapshot without the typo fields loads as zeros`() {
        val target = file()
        target.parentFile?.mkdirs()
        target.writeText("""{"totalWords":7,"days":{"100":{"chars":10,"words":2}}}""")
        val totals = store().lifetime()
        assertEquals(7, totals.words)
        assertEquals(0, totals.autocorrects)
        assertTrue(totals.punctuation.isEmpty())
        assertTrue(totals.wordsByLanguage.isEmpty())
        assertEquals(0, store().dayEntries().single().autocorrects)
    }

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        const val CADENCE = 200L
    }
}
