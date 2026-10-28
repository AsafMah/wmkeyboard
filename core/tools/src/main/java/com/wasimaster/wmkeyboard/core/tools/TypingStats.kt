package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Which stretch of history a statistics view covers.
 */
enum class StatsPeriod { DAY, WEEK, MONTH }

/**
 * Running totals of the user's typing: characters, words, backspaces and
 * active time, kept per local calendar day plus lifetime sums.
 *
 * Only aggregate counts are ever stored — no text, no per-app split, no
 * timestamps finer than the day bucket and a lifetime hour-of-day histogram.
 * That is the whole privacy story of the Statistics screen, so nothing may be
 * added here that would let a reader of the file reconstruct what was typed
 * or where. The tap heatmap (issue #390) keeps to it: a lifetime count per
 * quarter-key cell of each layout, with no order and no time, so it says
 * which parts of the board get hit and never which letters followed which.
 *
 * Beside the typing itself sit the lifetime tallies the levels and
 * achievements are built from (issue #390): words the strip predicted or
 * finished, keystrokes that saved, glided words and how far the finger
 * travelled drawing them. See [TypingProgress]. Words built from taps and
 * swipes together (two-thumb typing, #751) count as glided words and in a
 * tally of their own.
 *
 * Dictation is kept apart (#761): words spoken, phrases and the time the
 * microphone listened for them, per day and in all. They are never part of
 * [Totals.words] or the characters, so speaking a paragraph cannot move the
 * typing speed.
 *
 * The punch card is a lifetime count of keystrokes per weekday and hour, 168
 * numbers, as coarse as the hour histogram it sits beside. The fastest burst
 * is one number: the best speed over any unbroken stretch of typing long
 * enough to mean something ([MIN_BURST_MS], [MIN_BURST_CHARS]).
 *
 * Typos are counted as events, never as words: an autocorrect fired, kept or
 * undone, an offer taken, a word the user repaired by hand, and the longest
 * run of words with none of those in it. Which words they were is the
 * business of the learned corrections store, not this file. Beside them sit
 * a count per punctuation mark from a fixed set, words typed in capitals,
 * the record book (the longest word by its letter count alone, the longest
 * glide, the longest unbroken stretch, the longest run of backspaces, words
 * deleted whole, emoji sent) and the words per language id. None of it says
 * what was typed, only how.
 *
 * What counts as typing is a keystroke that reaches the user's text field:
 * soft keys, hardware keys, braille and morse all funnel through the same
 * per-character path, and words picked from the suggestion strip or drawn by
 * a glide count as words. Voice, handwriting and panel inserts (emoji,
 * clipboard, translate) are insertions rather than typing and are left out on
 * purpose — they would also make the speed figures meaningless. Voice is the
 * one insertion with a count of its own, separate from the typing.
 *
 * Speed has no prompt to score against (unlike [scoreTypingTest]), so active
 * time is measured burst-wise: the gap between two keystrokes joins
 * `activeMs` only when it is at most [BURST_GAP_MS]. Words per minute is then
 * the usual chars/5 over that active time. Pauses — thinking, switching
 * apps, or recording being disabled — add neither characters nor active
 * time, so they can never dilute the average.
 *
 * Words are counted by a boundary detector on the keystroke stream itself
 * (a letter or digit arms `pendingWord`; a separator or flush counts it),
 * which works identically for composing and direct-commit scripts without a
 * hook in the word-commit path. [onWordsCommitted] clears the armed flag so
 * a half-typed word completed by a suggestion pick is counted exactly once.
 *
 * Storage follows the personal-store contract: nullable file (direct boot →
 * memory only), dirty-flag save on dismissal, [reload] for external edits by
 * the settings app. Lives in `stats/`, not `learning/` — "Delete learned
 * words" must not take the statistics with it.
 */
class TypingStats(
    private val storageFile: File?,
    /**
     * The live speed readout on the spacebar (#752), fed from every event
     * here whether or not recording is on: it stores nothing, so it needs no
     * permission the statistics need. Null for a reader. Before [zone], so a
     * trailing zone lambda still lands on the zone.
     */
    private val speed: LiveTypingSpeed? = null,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
) {

    /** One day of typing, in reading order for the settings screen. */
    data class DayEntry(
        val epochDay: Int,
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
        val voiceWords: Long = 0,
        val autocorrects: Long = 0,
        val handFixes: Long = 0,
    )

    /**
     * Lifetime sums; unaffected by day-bucket pruning.
     *
     * The last five are the achievement tallies. [wordsPredicted],
     * [wordsCompleted] and [glideWords] are parts of [words], not additions to
     * it, and [keystrokesSaved] is the taps those words spared: a suggestion's
     * letters and its space, less what was typed of it and the tap on the chip.
     * [twoThumbWords] is a part of [glideWords] in turn: the glided words that
     * took more than one gesture.
     *
     * The voice figures are not part of [words]. [wordLetters] is the letters
     * of the typed, picked and glided words, for the average word length.
     * [peakBurstWpm] is the fastest unbroken stretch of typing, and
     * [weekHourHistogram] the keystrokes by weekday (Monday first) and hour.
     *
     * The typo figures: [autocorrects] fired (an offer chip taken is one of
     * them and an [offersTaken] besides), of which [autocorrectsKept] stood
     * and [autocorrectsUndone] were taken back; [handFixes] the user made
     * themselves; [bestCleanRun] the most words in a row with no backspace,
     * autocorrect or hand fix among them. [punctuation] counts the marks in
     * [PUNCTUATION], [shoutedWords] the typed words in capitals. The record
     * book is [longestWordLetters], [longestGlideLetters], [longestBurstMs],
     * [longestBackspaceRun], [wordsDeletedWhole] and [emojiSent].
     * [wordsByLanguage] splits [words] by the language id they were typed
     * under.
     */
    data class Totals(
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
        val hourHistogram: List<Long>,
        val keystrokesSaved: Long = 0,
        val wordsPredicted: Long = 0,
        val wordsCompleted: Long = 0,
        val glideWords: Long = 0,
        val glideDistanceMm: Double = 0.0,
        val twoThumbWords: Long = 0,
        val voiceWords: Long = 0,
        val voicePhrases: Long = 0,
        val voiceMs: Long = 0,
        val wordLetters: Long = 0,
        val peakBurstWpm: Double = 0.0,
        val weekHourHistogram: List<Long> = emptyList(),
        val autocorrects: Long = 0,
        val autocorrectsKept: Long = 0,
        val autocorrectsUndone: Long = 0,
        val offersTaken: Long = 0,
        val handFixes: Long = 0,
        val bestCleanRun: Long = 0,
        val punctuation: Map<Char, Long> = emptyMap(),
        val shoutedWords: Long = 0,
        val longestWordLetters: Int = 0,
        val longestGlideLetters: Int = 0,
        val longestBurstMs: Long = 0,
        val longestBackspaceRun: Long = 0,
        val wordsDeletedWhole: Long = 0,
        val emojiSent: Long = 0,
        val wordsByLanguage: Map<String, Long> = emptyMap(),
    )

    /**
     * Where the soft keys of one layout get tapped. [cells] counts taps per
     * [TypingHeatmapMath] cell; [keys] is the layout's letter keys at the last
     * tap, so the screen can draw the board the counts belong to without
     * knowing anything about layouts. Positions are in key widths across and
     * rows down, which is what makes portrait and landscape taps land in the
     * same cells.
     */
    data class Heatmap(
        val layoutId: String,
        val name: String,
        val taps: Long,
        val cells: Map<Int, Long>,
        val keys: List<HeatKey>,
    )

    /** A letter key on a [Heatmap]'s board, at its centre. */
    data class HeatKey(val label: String, val x: Float, val y: Float)

    @Serializable
    private data class DayStat(
        val chars: Long = 0,
        val words: Long = 0,
        val backspaces: Long = 0,
        val activeMs: Long = 0,
        val voiceWords: Long = 0,
        val autocorrects: Long = 0,
        val handFixes: Long = 0,
    )

    @Serializable
    private data class KeyStat(val label: String, val x: Float, val y: Float)

    @Serializable
    private data class HeatStat(
        val name: String = "",
        val taps: Long = 0,
        val cells: Map<Int, Long> = emptyMap(),
        val keys: List<KeyStat> = emptyList(),
    )

    @Serializable
    private data class Snapshot(
        val days: Map<Int, DayStat> = emptyMap(),
        val totalChars: Long = 0,
        val totalWords: Long = 0,
        val totalBackspaces: Long = 0,
        val totalActiveMs: Long = 0,
        val hourHistogram: List<Long> = emptyList(),
        val keystrokesSaved: Long = 0,
        val wordsPredicted: Long = 0,
        val wordsCompleted: Long = 0,
        val glideWords: Long = 0,
        val glideDistanceMm: Double = 0.0,
        val heatmaps: Map<String, HeatStat> = emptyMap(),
        val twoThumbWords: Long = 0,
        val voiceWords: Long = 0,
        val voicePhrases: Long = 0,
        val voiceMs: Long = 0,
        val wordLetters: Long = 0,
        val peakBurstWpm: Double = 0.0,
        val weekHourHistogram: List<Long> = emptyList(),
        val autocorrects: Long = 0,
        val autocorrectsKept: Long = 0,
        val autocorrectsUndone: Long = 0,
        val offersTaken: Long = 0,
        val handFixes: Long = 0,
        val bestCleanRun: Long = 0,
        /** Keyed by the mark itself, as a one-character string. */
        val punctuation: Map<String, Long> = emptyMap(),
        val shoutedWords: Long = 0,
        val longestWordLetters: Int = 0,
        val longestGlideLetters: Int = 0,
        val longestBurstMs: Long = 0,
        val longestBackspaceRun: Long = 0,
        val wordsDeletedWhole: Long = 0,
        val emojiSent: Long = 0,
        val wordsByLanguage: Map<String, Long> = emptyMap(),
    )

    private class HeatAcc(
        var name: String = "",
        var taps: Long = 0,
        val cells: HashMap<Int, Long> = HashMap(),
        var keys: List<HeatKey> = emptyList(),
    )

    private class DayAcc(
        var chars: Long = 0,
        var words: Long = 0,
        var backspaces: Long = 0,
        var activeMs: Long = 0,
        var voiceWords: Long = 0,
        var autocorrects: Long = 0,
        var handFixes: Long = 0,
    )

    private val days = HashMap<Int, DayAcc>()
    private var totalChars = 0L
    private var totalWords = 0L
    private var totalBackspaces = 0L
    private var totalActiveMs = 0L
    private val hourHistogram = LongArray(HOURS)
    private var keystrokesSaved = 0L
    private var wordsPredicted = 0L
    private var wordsCompleted = 0L
    private var glideWords = 0L
    private var glideDistanceMm = 0.0
    private val heatmaps = HashMap<String, HeatAcc>()
    private var twoThumbWords = 0L
    private var voiceWords = 0L
    private var voicePhrases = 0L
    private var voiceMs = 0L
    private var wordLetters = 0L
    private var peakBurstWpm = 0.0
    private val weekHourHistogram = LongArray(WEEK_HOURS)
    private var autocorrects = 0L
    private var autocorrectsKept = 0L
    private var autocorrectsUndone = 0L
    private var offersTaken = 0L
    private var handFixes = 0L
    private var bestCleanRun = 0L
    private val punctuation = HashMap<Char, Long>()
    private var shoutedWords = 0L
    private var longestWordLetters = 0
    private var longestGlideLetters = 0
    private var longestBurstMs = 0L
    private var longestBackspaceRun = 0L
    private var wordsDeletedWhole = 0L
    private var emojiSent = 0L
    private val wordsByLanguage = HashMap<String, Long>()
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** The day the most recent event landed on, so a flush with no clock in
     * hand (from [save]) can attribute the trailing word somewhere sane. */
    private var lastDayKey = 0

    /** Uptime of the previous counted keystroke; 0 means "no previous", so
     * the next keystroke starts a burst instead of closing a gap. */
    private var lastUptimeMs = 0L

    /** A letter or digit has been typed and its word not yet counted. */
    private var pendingWord = false

    /** The letters of that word, already in [wordLetters]: a glide that
     * finishes it adds only the rest. */
    private var pendingLetters = 0

    /** Whether every letter of the armed word so far was a capital, and how
     * many letters proper (not digits) it holds: a shout needs two. */
    private var pendingUpper = false
    private var pendingAlpha = 0

    /** Words counted since the last backspace, autocorrect or hand fix. */
    private var cleanRun = 0L

    /** Backspaces in a row, for [Totals.longestBackspaceRun]. */
    private var backspaceRun = 0L

    /** Full stops in a row: three make an ellipsis. */
    private var dotRun = 0

    /** The unbroken stretch of typing in progress, for [Totals.peakBurstWpm]. */
    private var burstChars = 0L
    private var burstMs = 0L

    /**
     * The language the keyboard is typing in, by id, for [Totals.wordsByLanguage].
     * The service sets it beside [enabled] at every event; empty attributes
     * the word to no language.
     */
    @Volatile
    var language: String = ""

    /**
     * The single recording gate: the service folds the settings toggle,
     * incognito and power saving into this one flag. Turning it back on
     * forgets the previous keystroke's uptime, so the paused stretch can
     * never leak into [Totals.activeMs] — not even a pause shorter than
     * [BURST_GAP_MS].
     */
    var enabled: Boolean = false
        set(value) {
            if (value && !field) lastUptimeMs = 0L
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /** A keystroke's text reached the field (directly or via the composing
     * buffer). Multi-character texts (ligature keys) count their full length
     * but skip the rhythm and hour bookkeeping, same as the prediction
     * engine's keystroke timing. */
    @Synchronized
    fun onTyped(text: String, nowMillis: Long, uptimeMs: Long) {
        if (text.isEmpty()) return
        speed?.onTyped(text.last().isLetterOrDigit(), text.length, uptimeMs)
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.chars += text.length
        totalChars += text.length
        backspaceRun = 0
        if (text.length == 1) {
            accrueActive(day, uptimeMs, chars = 1)
            val hour = TypingStatsMath.hourOf(nowMillis, zone())
            hourHistogram[hour]++
            val weekday = TypingStatsMath.weekdayOf(TypingStatsMath.localEpochDay(nowMillis, zone()))
            weekHourHistogram[weekday * HOURS + hour]++
            countPunctuation(text[0])
        } else {
            dotRun = 0
        }
        if (text.last().isLetterOrDigit()) {
            pendingWord = true
            val letters = text.count { it.isLetterOrDigit() }
            wordLetters += letters
            pendingLetters += letters
            for (c in text) {
                if (!c.isLetter()) continue
                // The first letter sets the case; any lowercase one after
                // it, or before it, makes the word an ordinary one.
                pendingUpper = (pendingAlpha == 0 || pendingUpper) && c.isUpperCase()
                pendingAlpha++
            }
        } else {
            countPendingWord(day)
        }
        dirty = true
    }

    /**
     * One of [PUNCTUATION] typed as a key of its own. Three full stops in a
     * row are one ellipsis, moved over from the full stops when the third
     * lands, so "..." and "…" count the same.
     */
    private fun countPunctuation(c: Char) {
        if (c == FULL_STOP) {
            dotRun++
            if (dotRun == ELLIPSIS_DOTS) {
                dotRun = 0
                punctuation.merge(FULL_STOP, -(ELLIPSIS_DOTS - 1).toLong(), Long::plus)
                punctuation.merge(ELLIPSIS, 1L, Long::plus)
                return
            }
        } else {
            dotRun = 0
        }
        if (c in PUNCTUATION) punctuation.merge(c, 1L, Long::plus)
    }

    /** A space or enter landed in the field: a character for the chars/5
     * arithmetic, and the boundary that counts the pending word. */
    @Synchronized
    fun onSeparator(nowMillis: Long, uptimeMs: Long) {
        speed?.onSeparator(uptimeMs)
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.chars += 1
        totalChars += 1
        backspaceRun = 0
        dotRun = 0
        accrueActive(day, uptimeMs, chars = 1)
        countPendingWord(day)
        dirty = true
    }

    /** Backspace (a word-delete swipe is one event). Counts and keeps the
     * rhythm going but never decrements — the statistic is work done, not
     * text surviving. Ends the clean run: the word being taken back was not
     * typed right, whatever it was. */
    @Synchronized
    fun onBackspace(nowMillis: Long, uptimeMs: Long) {
        speed?.onBackspace()
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.backspaces += 1
        totalBackspaces += 1
        backspaceRun++
        if (backspaceRun > longestBackspaceRun) longestBackspaceRun = backspaceRun
        dotRun = 0
        endCleanRun()
        accrueActive(day, uptimeMs)
        dirty = true
    }

    /** A delete swipe took a whole word (or more) in one go. */
    @Synchronized
    fun onWordDeleted() {
        if (!enabled) return
        wordsDeletedWhole++
        dirty = true
    }

    /**
     * Autocorrect replaced the word just typed. [offer] says the user tapped
     * the near-miss chip for it rather than the keyboard deciding alone;
     * both are a typo the keyboard caught. Ends the clean run.
     */
    @Synchronized
    fun onAutocorrect(offer: Boolean, nowMillis: Long) {
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.autocorrects++
        autocorrects++
        if (offer) offersTaken++
        endCleanRun()
        dirty = true
    }

    /** A correction stood once the text around it settled. */
    @Synchronized
    fun onAutocorrectKept() {
        if (!enabled) return
        autocorrectsKept++
        dirty = true
    }

    /** A correction was taken back, by backspace, chip or a later rewrite. */
    @Synchronized
    fun onAutocorrectUndone() {
        if (!enabled) return
        autocorrectsUndone++
        dirty = true
    }

    /**
     * The user went back and repaired a word by hand, a slip's worth of
     * edits. Known at settle, so it ends the clean run late: the words typed
     * between the slip and the repair stay in the run that was.
     */
    @Synchronized
    fun onHandFix(nowMillis: Long) {
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.handFixes++
        handFixes++
        endCleanRun()
        dirty = true
    }

    /** An emoji went into the field, from the panel, the strip or a shortcut. */
    @Synchronized
    fun onEmoji() {
        if (!enabled) return
        emojiSent++
        dirty = true
    }

    private fun endCleanRun() {
        if (cleanRun > bestCleanRun) bestCleanRun = cleanRun
        cleanRun = 0
    }

    /** [n] words landed clean so far: the run grows and the record with it. */
    private fun countClean(n: Int) {
        cleanRun += n
        if (cleanRun > bestCleanRun) bestCleanRun = cleanRun
        val language = language
        if (language.isNotEmpty()) {
            if (language !in wordsByLanguage && wordsByLanguage.size >= MAX_LANGUAGES) {
                wordsByLanguage.entries.minByOrNull { it.value }?.let { wordsByLanguage.remove(it.key) }
            }
            wordsByLanguage.merge(language, n.toLong(), Long::plus)
        }
    }

    /** [n] whole words landed without being typed out — a suggestion pick or
     * a glide. Clears the armed half-word so it is not counted again at the
     * next separator; adds no characters or active time, which would
     * inflate the speed figures. [letters], when known, goes to the live
     * speed readout only. */
    @Synchronized
    fun onWordsCommitted(n: Int, nowMillis: Long, letters: Int = 0, startUptimeMs: Long = 0L) {
        if (n <= 0) return
        speed?.onWord(letters, startUptimeMs)
        if (!enabled) return
        val day = dayOf(nowMillis)
        day.words += n
        totalWords += n
        if (letters > longestWordLetters) longestWordLetters = letters
        backspaceRun = 0
        countClean(n)
        clearPendingWord()
        dirty = true
    }

    private fun clearPendingWord() {
        pendingWord = false
        pendingLetters = 0
        pendingUpper = false
        pendingAlpha = 0
    }

    /**
     * [n] words taken off the suggestion strip (or flicked off a key), after
     * [typed] characters of them had been typed. [letters] is what landed,
     * [spaced] whether the keyboard added the space after it.
     *
     * Nothing typed makes it a prediction; something typed and more landing
     * makes it a completion. A pick no longer than what was typed (a
     * correction, or the typed word itself) is neither. The keystrokes saved
     * are the ones the pick stood in for, less the tap on the chip itself.
     */
    @Synchronized
    fun onWordsPicked(n: Int, typed: Int, letters: Int, spaced: Boolean, nowMillis: Long) {
        if (n <= 0) return
        onWordsCommitted(n, nowMillis, letters)
        if (!enabled) return
        wordLetters += (letters - typed).coerceAtLeast(0)
        when {
            typed == 0 -> wordsPredicted += n
            letters > typed -> wordsCompleted += n
            else -> return
        }
        keystrokesSaved += (letters + (if (spaced) 1 else 0) - typed - 1).coerceAtLeast(0)
    }

    /**
     * One glided word, [letters] long, drawn along strokes [distanceMm] long
     * on the glass, in [gestures] taps and swipes. One stroke for the lot
     * saves every letter after the first; a two-thumb word (#751) saves the
     * letters its taps and swipes did not each type. [startUptimeMs] is when
     * the first gesture began, for the live speed readout.
     */
    @Synchronized
    fun onGlideWord(
        letters: Int,
        distanceMm: Double,
        nowMillis: Long,
        gestures: Int = 1,
        startUptimeMs: Long = 0L,
    ) {
        val typed = pendingLetters
        onWordsCommitted(1, nowMillis, letters, startUptimeMs)
        if (!enabled) return
        glideWords += 1
        if (gestures > 1) twoThumbWords += 1
        if (letters > longestGlideLetters) longestGlideLetters = letters
        wordLetters += (letters - typed).coerceAtLeast(0)
        keystrokesSaved += (letters - gestures.coerceAtLeast(1)).coerceAtLeast(0)
        if (distanceMm.isFinite() && distanceMm > 0.0) glideDistanceMm += distanceMm
    }

    /**
     * One dictated phrase landed in the field (#761): [words] words, after
     * the microphone listened for [listenedMs]. Counted beside the typing,
     * never in it, and the armed half-word is left alone: a word typed before
     * the phrase is still the user's own.
     */
    @Synchronized
    fun onVoicePhrase(words: Int, listenedMs: Long, nowMillis: Long) {
        if (!enabled || words <= 0) return
        val day = dayOf(nowMillis)
        day.voiceWords += words
        voiceWords += words
        voicePhrases += 1
        if (listenedMs in 1..MAX_PHRASE_MS) voiceMs += listenedMs
        dirty = true
    }

    /**
     * A soft key went down at ([x], [y]) on layout [layoutId]: key widths
     * across, rows down. [keys] is the board it happened on, stored as given
     * — the caller hands the same list back for every tap on one board, so an
     * identity check is all it costs to keep it current.
     */
    @Synchronized
    fun onKeyTap(layoutId: String, name: String, x: Float, y: Float, keys: List<HeatKey>) {
        if (!enabled || layoutId.isEmpty() || !x.isFinite() || !y.isFinite()) return
        val map = heatmaps[layoutId] ?: HeatAcc().also {
            evictHeatmap()
            heatmaps[layoutId] = it
        }
        if (name.isNotEmpty()) map.name = name
        if (keys.isNotEmpty() && map.keys !== keys) map.keys = keys
        map.cells.merge(TypingHeatmapMath.cellOf(x, y), 1L, Long::plus)
        map.taps += 1
        dirty = true
    }

    /** Room for one more board: the least-tapped goes when the cap is reached. */
    private fun evictHeatmap() {
        if (heatmaps.size < MAX_HEATMAPS) return
        heatmaps.entries.minByOrNull { it.value.taps }?.let { heatmaps.remove(it.key) }
    }

    @Synchronized
    fun dayEntries(): List<DayEntry> =
        days.entries.sortedBy { it.key }.map { (key, acc) ->
            DayEntry(
                key, acc.chars, acc.words, acc.backspaces, acc.activeMs, acc.voiceWords,
                autocorrects = acc.autocorrects,
                handFixes = acc.handFixes,
            )
        }

    @Synchronized
    fun lifetime(): Totals =
        Totals(
            totalChars, totalWords, totalBackspaces, totalActiveMs, hourHistogram.toList(),
            keystrokesSaved = keystrokesSaved,
            wordsPredicted = wordsPredicted,
            wordsCompleted = wordsCompleted,
            glideWords = glideWords,
            glideDistanceMm = glideDistanceMm,
            twoThumbWords = twoThumbWords,
            voiceWords = voiceWords,
            voicePhrases = voicePhrases,
            voiceMs = voiceMs,
            wordLetters = wordLetters,
            peakBurstWpm = maxOf(peakBurstWpm, burstWpm()),
            weekHourHistogram = weekHourHistogram.toList(),
            autocorrects = autocorrects,
            autocorrectsKept = autocorrectsKept,
            autocorrectsUndone = autocorrectsUndone,
            offersTaken = offersTaken,
            handFixes = handFixes,
            bestCleanRun = maxOf(bestCleanRun, cleanRun),
            punctuation = HashMap(punctuation),
            shoutedWords = shoutedWords,
            longestWordLetters = longestWordLetters,
            longestGlideLetters = longestGlideLetters,
            longestBurstMs = maxOf(longestBurstMs, burstMs),
            longestBackspaceRun = longestBackspaceRun,
            wordsDeletedWhole = wordsDeletedWhole,
            emojiSent = emojiSent,
            wordsByLanguage = HashMap(wordsByLanguage),
        )

    /** Every board with a tap on it, the most tapped first. */
    @Synchronized
    fun heatmaps(): List<Heatmap> =
        heatmaps.entries
            .map { (id, acc) -> Heatmap(id, acc.name, acc.taps, HashMap(acc.cells), acc.keys) }
            .sortedByDescending { it.taps }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            // The trailing word: "hello" then keyboard dismissed is a word.
            days[lastDayKey]?.let { countPendingWord(it) }
            peakBurstWpm = maxOf(peakBurstWpm, burstWpm())
            longestBurstMs = maxOf(longestBurstMs, burstMs)
            bestCleanRun = maxOf(bestCleanRun, cleanRun)
            prune()
            dirty = false
            file.ticket() to Snapshot(
                days = days.mapValues { (_, acc) ->
                    DayStat(
                        acc.chars, acc.words, acc.backspaces, acc.activeMs, acc.voiceWords,
                        autocorrects = acc.autocorrects,
                        handFixes = acc.handFixes,
                    )
                },
                totalChars = totalChars,
                totalWords = totalWords,
                totalBackspaces = totalBackspaces,
                totalActiveMs = totalActiveMs,
                hourHistogram = hourHistogram.toList(),
                keystrokesSaved = keystrokesSaved,
                wordsPredicted = wordsPredicted,
                wordsCompleted = wordsCompleted,
                glideWords = glideWords,
                glideDistanceMm = glideDistanceMm,
                heatmaps = heatmaps.mapValues { (_, acc) ->
                    HeatStat(
                        name = acc.name,
                        taps = acc.taps,
                        cells = HashMap(acc.cells),
                        keys = acc.keys.map { KeyStat(it.label, it.x, it.y) },
                    )
                },
                twoThumbWords = twoThumbWords,
                voiceWords = voiceWords,
                voicePhrases = voicePhrases,
                voiceMs = voiceMs,
                wordLetters = wordLetters,
                peakBurstWpm = peakBurstWpm,
                weekHourHistogram = weekHourHistogram.toList(),
                autocorrects = autocorrects,
                autocorrectsKept = autocorrectsKept,
                autocorrectsUndone = autocorrectsUndone,
                offersTaken = offersTaken,
                handFixes = handFixes,
                bestCleanRun = bestCleanRun,
                punctuation = punctuation.entries.associate { (mark, count) -> mark.toString() to count },
                shoutedWords = shoutedWords,
                longestWordLetters = longestWordLetters,
                longestGlideLetters = longestGlideLetters,
                longestBurstMs = longestBurstMs,
                longestBackspaceRun = longestBackspaceRun,
                wordsDeletedWhole = wordsDeletedWhole,
                emojiSent = emojiSent,
                wordsByLanguage = HashMap(wordsByLanguage),
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** The settings app edited or deleted the file: drop the in-memory copy
     * for the disk state, otherwise the next save would clobber it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        resetInMemory()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        resetInMemory()
        dirty = snapshotFile?.delete() == false
    }

    private fun resetInMemory() {
        days.clear()
        totalChars = 0
        totalWords = 0
        totalBackspaces = 0
        totalActiveMs = 0
        hourHistogram.fill(0)
        keystrokesSaved = 0
        wordsPredicted = 0
        wordsCompleted = 0
        glideWords = 0
        glideDistanceMm = 0.0
        heatmaps.clear()
        twoThumbWords = 0
        voiceWords = 0
        voicePhrases = 0
        voiceMs = 0
        wordLetters = 0
        peakBurstWpm = 0.0
        weekHourHistogram.fill(0)
        autocorrects = 0
        autocorrectsKept = 0
        autocorrectsUndone = 0
        offersTaken = 0
        handFixes = 0
        bestCleanRun = 0
        punctuation.clear()
        shoutedWords = 0
        longestWordLetters = 0
        longestGlideLetters = 0
        longestBurstMs = 0
        longestBackspaceRun = 0
        wordsDeletedWhole = 0
        emojiSent = 0
        wordsByLanguage.clear()
        clearPendingWord()
        cleanRun = 0
        backspaceRun = 0
        dotRun = 0
        lastUptimeMs = 0
        burstChars = 0
        burstMs = 0
    }

    private fun dayOf(nowMillis: Long): DayAcc {
        val key = TypingStatsMath.localEpochDay(nowMillis, zone())
        lastDayKey = key
        return days.getOrPut(key) { DayAcc() }
    }

    /**
     * Joins the gap since the last keystroke to the active time, or ends the
     * burst when it was a pause, and then counts this keystroke's [chars]
     * towards the burst it belongs to.
     */
    private fun accrueActive(day: DayAcc, uptimeMs: Long, chars: Int = 0) {
        val last = lastUptimeMs
        lastUptimeMs = uptimeMs
        val gap = uptimeMs - last
        if (last != 0L && gap in 1..BURST_GAP_MS) {
            day.activeMs += gap
            totalActiveMs += gap
            burstMs += gap
        } else if (last == 0L || gap > BURST_GAP_MS) {
            peakBurstWpm = maxOf(peakBurstWpm, burstWpm())
            longestBurstMs = maxOf(longestBurstMs, burstMs)
            burstChars = 0
            burstMs = 0
        }
        burstChars += chars
    }

    /** The burst in progress as a speed, or 0 while it is too short to count. */
    private fun burstWpm(): Double =
        if (burstMs < MIN_BURST_MS || burstChars < MIN_BURST_CHARS) 0.0
        else TypingStatsMath.wpm(burstChars, burstMs)

    private fun countPendingWord(day: DayAcc) {
        if (!pendingWord) return
        day.words += 1
        totalWords += 1
        if (pendingLetters > longestWordLetters) longestWordLetters = pendingLetters
        if (pendingUpper && pendingAlpha >= SHOUT_LETTERS) shoutedWords++
        countClean(1)
        clearPendingWord()
    }

    /** Day buckets past [MAX_DAYS] are dropped (relative to the newest day
     * recorded, so a device with a wrong clock cannot eat real history).
     * The lifetime totals are separate fields, so pruning loses nothing the
     * top of the screen shows. */
    private fun prune() {
        val newest = days.keys.maxOrNull() ?: return
        days.keys.removeAll { it < newest - MAX_DAYS }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching { add(json.decodeFromString<Snapshot>(file.readText())) }
    }

    /**
     * Adds the other devices' counts from [DEVICES_FILE_PATH] to
     * this store's own, for a view of the whole: what the Statistics screen
     * shows once sync is on. A store that saves must never be given them, or
     * the next save would write every device's counts in as this one's.
     */
    @Synchronized
    fun absorbDevices(text: String?) {
        if (text.isNullOrBlank()) return
        runCatching { json.decodeFromString<Map<String, Snapshot>>(text) }
            .getOrNull()
            ?.values
            ?.forEach(::add)
    }

    /** Adds [snapshot] to what is held: every count summed, day by day and cell by cell. */
    private fun add(snapshot: Snapshot) {
        for ((key, stat) in snapshot.days) {
            val day = days.getOrPut(key) { DayAcc() }
            day.chars += stat.chars
            day.words += stat.words
            day.backspaces += stat.backspaces
            day.activeMs += stat.activeMs
            day.voiceWords += stat.voiceWords
            day.autocorrects += stat.autocorrects
            day.handFixes += stat.handFixes
        }
        totalChars += snapshot.totalChars
        totalWords += snapshot.totalWords
        totalBackspaces += snapshot.totalBackspaces
        totalActiveMs += snapshot.totalActiveMs
        snapshot.hourHistogram.take(HOURS).forEachIndexed { hour, count ->
            hourHistogram[hour] += count
        }
        keystrokesSaved += snapshot.keystrokesSaved
        wordsPredicted += snapshot.wordsPredicted
        wordsCompleted += snapshot.wordsCompleted
        glideWords += snapshot.glideWords
        glideDistanceMm += snapshot.glideDistanceMm
        twoThumbWords += snapshot.twoThumbWords
        voiceWords += snapshot.voiceWords
        voicePhrases += snapshot.voicePhrases
        voiceMs += snapshot.voiceMs
        wordLetters += snapshot.wordLetters
        // A best is a best on any device, not a sum.
        peakBurstWpm = maxOf(peakBurstWpm, snapshot.peakBurstWpm)
        snapshot.weekHourHistogram.take(WEEK_HOURS).forEachIndexed { slot, count ->
            weekHourHistogram[slot] += count
        }
        autocorrects += snapshot.autocorrects
        autocorrectsKept += snapshot.autocorrectsKept
        autocorrectsUndone += snapshot.autocorrectsUndone
        offersTaken += snapshot.offersTaken
        handFixes += snapshot.handFixes
        // Records are the best on any device, not a sum.
        bestCleanRun = maxOf(bestCleanRun, snapshot.bestCleanRun)
        for ((mark, count) in snapshot.punctuation) {
            mark.singleOrNull()?.let { punctuation.merge(it, count, Long::plus) }
        }
        shoutedWords += snapshot.shoutedWords
        longestWordLetters = maxOf(longestWordLetters, snapshot.longestWordLetters)
        longestGlideLetters = maxOf(longestGlideLetters, snapshot.longestGlideLetters)
        longestBurstMs = maxOf(longestBurstMs, snapshot.longestBurstMs)
        longestBackspaceRun = maxOf(longestBackspaceRun, snapshot.longestBackspaceRun)
        wordsDeletedWhole += snapshot.wordsDeletedWhole
        emojiSent += snapshot.emojiSent
        for ((id, count) in snapshot.wordsByLanguage) wordsByLanguage.merge(id, count, Long::plus)
        for ((id, stat) in snapshot.heatmaps) {
            val map = heatmaps.getOrPut(id) { HeatAcc() }
            if (map.name.isEmpty()) map.name = stat.name
            if (map.keys.isEmpty()) map.keys = stat.keys.map { HeatKey(it.label, it.x, it.y) }
            map.taps += stat.taps
            for ((cell, count) in stat.cells) map.cells.merge(cell, count, Long::plus)
        }
    }

    companion object {
        /** Where the file lives under filesDir — shared by the keyboard and
         * the Statistics screen so the two can never drift apart. */
        const val FILE_PATH = "stats/typing_stats.json"

        /**
         * The other devices' counts, as sync brought them: installation id to
         * a snapshot in this store's own format. Only sync and a restore write
         * it; the keyboard never reads it, so what it holds can never be saved
         * back as this device's typing (#447).
         */
        const val DEVICES_FILE_PATH = "stats/typing_stats_devices.json"

        /** A gap between keystrokes longer than this is a pause, not typing. */
        const val BURST_GAP_MS = 5_000L

        /** Below this much recorded active time a speed figure is noise, and
         * the screen shows a placeholder instead. */
        const val MIN_ACTIVE_MS = 30_000L

        /** Day buckets kept: a year of history plus a little slack. */
        const val MAX_DAYS = 370

        /** Boards with a heatmap of their own; past this the least-tapped one
         * makes room, so someone cycling through layouts cannot grow the file
         * without bound. */
        const val MAX_HEATMAPS = 8

        /** A burst shorter than this, or with fewer characters than
         * [MIN_BURST_CHARS], is too short for its speed to be a record: two
         * quick words would otherwise post 150 wpm. */
        const val MIN_BURST_MS = 15_000L
        const val MIN_BURST_CHARS = 50L

        /** One phrase's listening time past this is a microphone left open,
         * not talking, and is left out of the voice time. */
        const val MAX_PHRASE_MS = 10 * 60_000L

        /** The marks counted, each as a key of its own. */
        val PUNCTUATION: Set<Char> = setOf('!', '?', '.', ',', ';', ':', '…', '\'', '"')

        /** Three full stops in a row are one of these. */
        const val ELLIPSIS = '…'
        const val FULL_STOP = '.'
        const val ELLIPSIS_DOTS = 3

        /** A word in capitals is a shout only from this many letters. */
        const val SHOUT_LETTERS = 2

        /** Language ids with a word count of their own; past this the one
         * with the fewest words makes room. */
        const val MAX_LANGUAGES = 64

        private const val HOURS = 24
        private const val WEEK_HOURS = 7 * HOURS
    }
}

/**
 * The calendar and rollup arithmetic for the Statistics screen, pure and
 * separate from the store so it is testable with fixed inputs.
 *
 * All day math is done on the *local* epoch day — the millis shifted by the
 * zone offset first, so a bucket is the user's calendar day, not UTC's. No
 * `java.time` on purpose: desugaring is not enabled and minSdk is 24.
 */
object TypingStatsMath {

    /** One rollup bucket; [startEpochDay] identifies the day/week/month for labeling. */
    data class Bucket(
        val startEpochDay: Int,
        val chars: Long,
        val words: Long,
        val backspaces: Long,
        val activeMs: Long,
        val voiceWords: Long = 0,
        val autocorrects: Long = 0,
        val handFixes: Long = 0,
    ) {
        /** The typos the keyboard caught and the ones the user caught. */
        val typos: Long get() = autocorrects + handFixes
    }

    /** The user's calendar day for [nowMillis], as days since 1970-01-01 local. */
    fun localEpochDay(nowMillis: Long, tz: TimeZone): Int =
        Math.floorDiv(nowMillis + tz.getOffset(nowMillis), DAY_MS).toInt()

    /** The user's hour of day (0..23) for [nowMillis]. */
    fun hourOf(nowMillis: Long, tz: TimeZone): Int =
        Math.floorMod((nowMillis + tz.getOffset(nowMillis)) / HOUR_MS, HOURS.toLong()).toInt()

    /** Standard words-per-minute: chars/5 over the active minutes. */
    fun wpm(chars: Long, activeMs: Long): Double =
        if (activeMs <= 0L) 0.0 else (chars / CHARS_PER_WORD) / (activeMs / MINUTE_MS)

    /**
     * How many words a dictated [text] holds: its space-separated pieces with
     * a letter or digit in them, except that in a script written without
     * spaces (Chinese, Japanese, Thai and their neighbours) each letter
     * counts as one, the way word counts for those languages usually go.
     */
    fun spokenWords(text: String): Int {
        var words = 0
        for (piece in text.split(WHITESPACE)) {
            var unspaced = 0
            var other = false
            var at = 0
            while (at < piece.length) {
                val cp = piece.codePointAt(at)
                if (Character.isLetterOrDigit(cp)) {
                    if (Character.UnicodeScript.of(cp) in UNSPACED_SCRIPTS) unspaced++ else other = true
                }
                at += Character.charCount(cp)
            }
            words += unspaced + if (other) 1 else 0
        }
        return words
    }

    /** The weekday of a local [epochDay], Monday 0 through Sunday 6. */
    fun weekdayOf(epochDay: Int): Int = Math.floorMod(epochDay + THURSDAY_SHIFT, DAYS_PER_WEEK)

    /** Monday-aligned week index (epoch day 0 was a Thursday, hence the +3). */
    fun weekIndexOf(epochDay: Int): Int = Math.floorDiv(epochDay + THURSDAY_SHIFT, DAYS_PER_WEEK)

    /** The Monday an indexed week starts on, as an epoch day. */
    fun weekStartOf(weekIndex: Int): Int = weekIndex * DAYS_PER_WEEK - THURSDAY_SHIFT

    /**
     * Months since year 0 for [epochDay]. The epoch day is already local, so
     * a UTC calendar reads the right year and month fields back out.
     */
    fun monthKeyOf(epochDay: Int): Int {
        val calendar = utcCalendar()
        calendar.timeInMillis = epochDay * DAY_MS
        return calendar.get(GregorianCalendar.YEAR) * MONTHS_PER_YEAR +
            calendar.get(GregorianCalendar.MONTH)
    }

    /** The first day of a [monthKeyOf] month, as an epoch day. */
    fun monthStartOf(monthKey: Int): Int {
        val calendar = utcCalendar()
        calendar.clear()
        calendar.set(
            Math.floorDiv(monthKey, MONTHS_PER_YEAR),
            Math.floorMod(monthKey, MONTHS_PER_YEAR),
            1,
        )
        return (calendar.timeInMillis / DAY_MS).toInt()
    }

    /**
     * Rolls the day entries up into the fixed window the charts draw: the
     * last [DAY_BUCKETS] days, [WEEK_BUCKETS] weeks or [MONTH_BUCKETS]
     * months ending today, zero-filled so a quiet day is a visible gap
     * rather than a missing bar.
     */
    fun rollup(
        entries: List<TypingStats.DayEntry>,
        period: StatsPeriod,
        todayEpochDay: Int,
    ): List<Bucket> = when (period) {
        StatsPeriod.DAY -> {
            val byDay = entries.associateBy { it.epochDay }
            ((todayEpochDay - DAY_BUCKETS + 1)..todayEpochDay).map { day ->
                bucketOf(day, listOfNotNull(byDay[day]))
            }
        }
        StatsPeriod.WEEK -> {
            val byWeek = entries.groupBy { weekIndexOf(it.epochDay) }
            val thisWeek = weekIndexOf(todayEpochDay)
            ((thisWeek - WEEK_BUCKETS + 1)..thisWeek).map { week ->
                bucketOf(weekStartOf(week), byWeek[week].orEmpty())
            }
        }
        StatsPeriod.MONTH -> {
            val byMonth = entries.groupBy { monthKeyOf(it.epochDay) }
            val thisMonth = monthKeyOf(todayEpochDay)
            ((thisMonth - MONTH_BUCKETS + 1)..thisMonth).map { month ->
                bucketOf(monthStartOf(month), byMonth[month].orEmpty())
            }
        }
    }

    private fun bucketOf(startEpochDay: Int, entries: List<TypingStats.DayEntry>): Bucket =
        Bucket(
            startEpochDay = startEpochDay,
            chars = entries.sumOf { it.chars },
            words = entries.sumOf { it.words },
            backspaces = entries.sumOf { it.backspaces },
            activeMs = entries.sumOf { it.activeMs },
            voiceWords = entries.sumOf { it.voiceWords },
            autocorrects = entries.sumOf { it.autocorrects },
            handFixes = entries.sumOf { it.handFixes },
        )

    private fun utcCalendar() = GregorianCalendar(TimeZone.getTimeZone("UTC"))

    private val WHITESPACE = Regex("\\s+")

    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

    const val DAY_BUCKETS = 14
    const val WEEK_BUCKETS = 12
    const val MONTH_BUCKETS = 12

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000.0
    private const val CHARS_PER_WORD = 5.0
    private const val HOURS = 24
    private const val DAYS_PER_WEEK = 7
    private const val THURSDAY_SHIFT = 3
    private const val MONTHS_PER_YEAR = 12
}
