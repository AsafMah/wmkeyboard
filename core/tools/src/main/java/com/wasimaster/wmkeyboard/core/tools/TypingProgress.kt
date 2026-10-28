package com.wasimaster.wmkeyboard.core.tools

import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The levels and achievements on the Statistics screen (issue #390), worked
 * out from [TypingStats.Totals] and nothing else.
 *
 * Nothing here is stored. A level is a function of the lifetime counts, so it
 * can never disagree with them: deleting the statistics resets it, a backup
 * restores it, and changing a ladder below re-reads everyone's history with
 * the new numbers rather than leaving badges earned under the old ones.
 */
object TypingProgress {

    /**
     * Something to work towards: a count, and the targets it climbs through.
     * Each target reached is a step. The last one is the finish, and the count
     * keeps going past it.
     */
    enum class Achievement(val targets: LongArray) {
        WORDS_TYPED(longArrayOf(500, 2_000, 10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000)),
        KEYSTROKES_SAVED(
            longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000, 250_000, 500_000, 1_000_000),
        ),
        WORDS_PREDICTED(longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000)),
        WORDS_COMPLETED(longArrayOf(100, 500, 2_000, 10_000, 50_000, 100_000)),
        GLIDE_WORDS(longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000)),

        /** In whole metres; the last step is a marathon. */
        GLIDE_DISTANCE(longArrayOf(20, 100, 500, 1_000, 5_000, 10_000, 42_195)),

        /** Words built from taps and swipes together (#751). */
        TWO_THUMB_WORDS(longArrayOf(100, 500, 2_000, 10_000, 50_000)),

        /** Words dictated (#761). */
        VOICE_WORDS(longArrayOf(200, 1_000, 5_000, 20_000, 50_000, 100_000)),

        /** Autocorrects that stood once the text settled. */
        AUTOCORRECTS_KEPT(longArrayOf(50, 200, 1_000, 5_000, 20_000, 50_000)),

        /** The best run of words with no backspace, autocorrect or hand fix. */
        CLEAN_RUN(longArrayOf(10, 25, 50, 100, 250, 500)),
    }

    /**
     * Where one achievement stands. [target] is the next step's number, or the
     * last one's once they are all done; [steps] counts the ones reached.
     */
    data class AchievementState(
        val achievement: Achievement,
        val value: Long,
        val steps: Int,
        val target: Long,
    ) {
        val stepCount: Int get() = achievement.targets.size
        val finished: Boolean get() = steps >= stepCount

        /** How far along the ring is drawn: towards the next step, full when finished. */
        val fraction: Float
            get() = if (finished) 1f else (value.toFloat() / target).coerceIn(0f, 1f)
    }

    /**
     * The names that go with the levels, from the first level each one starts
     * at. Bands widen as the levels get further apart, so a name lasts about as
     * long in weeks of typing at every stage.
     */
    enum class Title(val fromLevel: Int) {
        BEGINNER(1), NOVICE(3), APPRENTICE(5), REGULAR(8), SKILLED(12),
        ADEPT(17), EXPERT(23), MASTER(30), GRANDMASTER(40), LEGEND(50),
    }

    /**
     * A level: its [number] and [title], the [xp] behind it, and the XP at
     * which this level began ([floor]) and the next one begins ([next]).
     */
    data class Level(
        val number: Int,
        val title: Title,
        val xp: Long,
        val floor: Long,
        val next: Long,
    ) {
        /** Progress through this level, for the bar. */
        val fraction: Float
            get() = ((xp - floor).toFloat() / (next - floor)).coerceIn(0f, 1f)
    }

    /** Level n+1 costs this much XP more than level n did. */
    const val LEVEL_STEP_XP = 500L

    /** What each achievement step is worth, on top of the words that earned it. */
    const val STEP_XP = 100L

    fun value(achievement: Achievement, totals: TypingStats.Totals): Long = when (achievement) {
        Achievement.WORDS_TYPED -> totals.words
        Achievement.KEYSTROKES_SAVED -> totals.keystrokesSaved
        Achievement.WORDS_PREDICTED -> totals.wordsPredicted
        Achievement.WORDS_COMPLETED -> totals.wordsCompleted
        Achievement.GLIDE_WORDS -> totals.glideWords
        Achievement.GLIDE_DISTANCE -> (totals.glideDistanceMm / MM_PER_METRE).toLong()
        Achievement.TWO_THUMB_WORDS -> totals.twoThumbWords
        Achievement.VOICE_WORDS -> totals.voiceWords
        Achievement.AUTOCORRECTS_KEPT -> totals.autocorrectsKept
        Achievement.CLEAN_RUN -> totals.bestCleanRun
    }

    fun state(achievement: Achievement, totals: TypingStats.Totals): AchievementState {
        val value = value(achievement, totals).coerceAtLeast(0)
        val targets = achievement.targets
        val steps = targets.count { value >= it }
        return AchievementState(achievement, value, steps, targets[steps.coerceAtMost(targets.lastIndex)])
    }

    fun achievements(totals: TypingStats.Totals): List<AchievementState> =
        Achievement.entries.map { state(it, totals) }

    /**
     * Experience: a point for every word, typed or spoken, a second for every
     * word the keyboard predicted, finished or read off a glide, and [STEP_XP]
     * for each achievement step. Typing earns; typing with the keyboard's help
     * earns faster, which is the point of counting any of it.
     */
    fun xp(totals: TypingStats.Totals): Long {
        val assisted = totals.wordsPredicted + totals.wordsCompleted + totals.glideWords
        val steps = achievements(totals).sumOf { it.steps }
        return totals.words.coerceAtLeast(0) + totals.voiceWords.coerceAtLeast(0) +
            assisted.coerceAtLeast(0) + steps * STEP_XP
    }

    /** The XP at which [level] (1-based) begins: 0, 500, 1 500, 3 000, 5 000… */
    fun levelStart(level: Int): Long {
        val n = level.coerceAtLeast(1).toLong()
        return LEVEL_STEP_XP * (n - 1) * n / 2
    }

    /** The level [xp] reaches. Solved in closed form, then nudged for rounding. */
    fun levelOf(xp: Long): Int {
        if (xp <= 0L) return 1
        // levelStart(n) <= xp  <=>  n(n-1) <= 2xp/STEP
        val k = 2.0 * xp / LEVEL_STEP_XP
        var n = floor((1.0 + sqrt(1.0 + 4.0 * k)) / 2.0).toInt().coerceAtLeast(1)
        while (n > 1 && levelStart(n) > xp) n--
        while (levelStart(n + 1) <= xp) n++
        return n
    }

    fun titleOf(level: Int): Title = Title.entries.last { level >= it.fromLevel }

    fun level(totals: TypingStats.Totals): Level {
        val xp = xp(totals)
        val number = levelOf(xp)
        return Level(number, titleOf(number), xp, levelStart(number), levelStart(number + 1))
    }

    /** Whether there is anything at all to show a level or an achievement for. */
    fun hasProgress(totals: TypingStats.Totals): Boolean =
        totals.words > 0L || totals.voiceWords > 0L || totals.glideDistanceMm > 0.0

    private const val MM_PER_METRE = 1_000.0
}

/**
 * The rest of the Statistics screen's arithmetic: streaks and personal bests,
 * where the words come from, and the counts turned into things a person can
 * picture. Pure, like [TypingProgress], and read off the same stored counts.
 */
object TypingInsights {

    /** A day counts towards a streak when anything at all was typed or said on it. */
    fun active(entry: TypingStats.DayEntry): Boolean =
        entry.chars > 0L || entry.words > 0L || entry.voiceWords > 0L

    /** The days with typing on them, as a set of local epoch days. */
    fun activeDays(entries: List<TypingStats.DayEntry>): Set<Int> =
        entries.filter(::active).mapTo(HashSet()) { it.epochDay }

    /**
     * Days in a row up to [today]. A streak is still alive on a day with no
     * typing yet: then it counts back from yesterday.
     */
    fun currentStreak(entries: List<TypingStats.DayEntry>, today: Int): Int {
        val days = activeDays(entries)
        var day = if (today in days) today else today - 1
        var run = 0
        while (day in days) {
            run++
            day--
        }
        return run
    }

    /** The longest run of days in a row in the history kept ([TypingStats.MAX_DAYS]). */
    fun longestStreak(entries: List<TypingStats.DayEntry>): Int {
        var best = 0
        var run = 0
        var previous = Int.MIN_VALUE
        for (day in activeDays(entries).sorted()) {
            run = if (day == previous + 1) run + 1 else 1
            if (run > best) best = run
            previous = day
        }
        return best
    }

    /** The day with the most words typed and spoken, or null when there is none. */
    fun bestDay(entries: List<TypingStats.DayEntry>): TypingStats.DayEntry? =
        entries.filter { it.words + it.voiceWords > 0L }
            .maxWithOrNull(compareBy<TypingStats.DayEntry> { it.words + it.voiceWords }.thenBy { it.epochDay })

    /** Letters per word, or 0 before there are words to tell. */
    fun averageWordLength(totals: TypingStats.Totals): Double =
        if (totals.words <= 0L || totals.wordLetters <= 0L) 0.0 else totals.wordLetters.toDouble() / totals.words

    /** Backspaces per hundred characters typed: how much of the typing was taken back. */
    fun correctionRate(totals: TypingStats.Totals): Double =
        if (totals.chars <= 0L) 0.0 else totals.backspaces * PERCENT / totals.chars

    /** Typos per hundred words: the ones autocorrect caught and the ones the user fixed by hand. */
    fun typoRate(totals: TypingStats.Totals): Double =
        if (totals.words <= 0L) 0.0 else (totals.autocorrects + totals.handFixes) * PERCENT / totals.words

    /** Autocorrects judged so far: kept and undone together. */
    fun autocorrectVerdicts(totals: TypingStats.Totals): Long = totals.autocorrectsKept + totals.autocorrectsUndone

    /**
     * The share of judged autocorrects that stood, 0..1, or null before
     * [MIN_VERDICTS] of them have been judged: a handful says nothing.
     */
    fun autocorrectTrust(totals: TypingStats.Totals): Double? {
        val judged = autocorrectVerdicts(totals)
        if (judged < MIN_VERDICTS) return null
        return totals.autocorrectsKept.toDouble() / judged
    }

    /** Emoji per hundred words typed and spoken, or 0 before there are words. */
    fun emojiPer100Words(totals: TypingStats.Totals): Double {
        val words = totals.words + totals.voiceWords
        return if (words <= 0L) 0.0 else totals.emojiSent * PERCENT / words
    }

    /** The marks typed, most first, each with its share of all of them; empty when none. */
    fun punctuationShares(totals: TypingStats.Totals): List<Pair<Char, Double>> {
        val counted = totals.punctuation.filterValues { it > 0L }
        val sum = counted.values.sum().toDouble()
        if (sum <= 0.0) return emptyList()
        return counted.entries
            .sortedWith(compareByDescending<Map.Entry<Char, Long>> { it.value }.thenBy { it.key })
            .map { it.key to it.value / sum }
    }

    /** The languages typed in, most words first, each with its share of the words that have a language. */
    fun topLanguages(totals: TypingStats.Totals, limit: Int = Int.MAX_VALUE): List<Pair<String, Double>> {
        val counted = totals.wordsByLanguage.filterValues { it > 0L }
        val sum = counted.values.sum().toDouble()
        if (sum <= 0.0 || limit <= 0) return emptyList()
        return counted.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value / sum }
    }

    /**
     * Who this typist is, read off the counts: when they type, how much they
     * take back, how they end a sentence. Each is a badge on the screen.
     */
    enum class Persona {
        NIGHT_OWL, EARLY_BIRD, LUNCH_TYPIST, WEEKEND_TYPIST, WEEKDAY_TYPIST,
        CAREFUL, FEARLESS, SHOUTER, EXCLAIMER, QUESTIONER, FULL_STOP, ELLIPSIS,
    }

    /**
     * The badges [totals] earn, at most [MAX_BADGES], in [Persona] order:
     * one for the hours, one for the week, one for the backspaces, one for
     * the punctuation. A badge needs enough behind it to mean something
     * ([MIN_BADGE_KEYSTROKES], [MIN_BADGE_WORDS], [MIN_BADGE_MARKS]).
     */
    fun badges(totals: TypingStats.Totals): List<Persona> {
        val badges = ArrayList<Persona>(MAX_BADGES)
        hoursBadge(totals.hourHistogram)?.let(badges::add)
        weekBadge(totals.weekHourHistogram)?.let(badges::add)
        if (totals.chars >= MIN_BADGE_KEYSTROKES) {
            val rate = correctionRate(totals)
            if (rate < CAREFUL_BACKSPACES) badges.add(Persona.CAREFUL)
            else if (rate > FEARLESS_BACKSPACES) badges.add(Persona.FEARLESS)
        }
        marksBadge(totals)?.let(badges::add)
        return badges.take(MAX_BADGES)
    }

    /** The hours badge: where the keystrokes fall in the day. */
    fun hoursBadge(hours: List<Long>): Persona? {
        if (hours.size < HOURS) return null
        val sum = hours.sum()
        if (sum < MIN_BADGE_KEYSTROKES) return null
        fun share(range: IntRange) = range.sumOf { hours[Math.floorMod(it, HOURS)] }.toDouble() / sum
        return when {
            share(NIGHT_HOURS) >= NIGHT_SHARE -> Persona.NIGHT_OWL
            share(EARLY_HOURS) >= EARLY_SHARE -> Persona.EARLY_BIRD
            share(LUNCH_HOURS) >= LUNCH_SHARE -> Persona.LUNCH_TYPIST
            else -> null
        }
    }

    /** The week badge: weekend or weekday typist, from the punch card. */
    fun weekBadge(weekHours: List<Long>): Persona? {
        if (weekHours.size < WEEK_HOURS) return null
        val sum = weekHours.sum()
        if (sum < MIN_BADGE_KEYSTROKES) return null
        val weekend = (SATURDAY..SUNDAY).sumOf { day -> (0 until HOURS).sumOf { weekHours[day * HOURS + it] } }
        val share = weekend.toDouble() / sum
        return when {
            share >= WEEKEND_SHARE -> Persona.WEEKEND_TYPIST
            share <= WEEKDAY_SHARE -> Persona.WEEKDAY_TYPIST
            else -> null
        }
    }

    /** The punctuation badge: shouting, or the mark that ends most sentences. */
    fun marksBadge(totals: TypingStats.Totals): Persona? {
        if (totals.words >= MIN_BADGE_WORDS && totals.shoutedWords * PERCENT / totals.words >= SHOUT_SHARE) {
            return Persona.SHOUTER
        }
        val marks = totals.punctuation
        val enders = listOf('!', '?', '.', TypingStats.ELLIPSIS).associateWith { marks[it] ?: 0L }
        if (enders.values.sum() < MIN_BADGE_MARKS) return null
        val top = enders.maxBy { it.value }
        // The full stop is the ordinary ending; it is a badge only when it
        // has the field to itself, while any other mark that leads is a trait.
        return when (top.key) {
            '!' -> Persona.EXCLAIMER
            '?' -> Persona.QUESTIONER
            TypingStats.ELLIPSIS -> Persona.ELLIPSIS
            else -> if (top.value.toDouble() / enders.values.sum() >= FULL_STOP_SHARE) Persona.FULL_STOP else null
        }
    }

    /** Spoken words per minute the microphone listened, or 0 before there is a minute of it. */
    fun speakingWpm(totals: TypingStats.Totals): Double =
        if (totals.voiceMs < MINUTE_MS) 0.0 else totals.voiceWords / (totals.voiceMs / MINUTE_MS.toDouble())

    /** Where the words came from, in [Source] order. Sums to every word typed, glided and spoken. */
    enum class Source { TYPED, PREDICTED, COMPLETED, GLIDED, TWO_THUMB, SPOKEN }

    fun sources(totals: TypingStats.Totals): List<Pair<Source, Long>> {
        val glided = (totals.glideWords - totals.twoThumbWords).coerceAtLeast(0)
        val typed = (totals.words - totals.wordsPredicted - totals.wordsCompleted - totals.glideWords)
            .coerceAtLeast(0)
        return listOf(
            Source.TYPED to typed,
            Source.PREDICTED to totals.wordsPredicted.coerceAtLeast(0),
            Source.COMPLETED to totals.wordsCompleted.coerceAtLeast(0),
            Source.GLIDED to glided,
            Source.TWO_THUMB to totals.twoThumbWords.coerceAtLeast(0),
            Source.SPOKEN to totals.voiceWords.coerceAtLeast(0),
        )
    }

    /** Words typed and spoken, as novels of [NOVEL_WORDS] words. */
    fun novels(totals: TypingStats.Totals): Double = (totals.words + totals.voiceWords) / NOVEL_WORDS.toDouble()

    /**
     * How long the saved keystrokes would have taken to type, at the user's own
     * speed when there is one and at [FALLBACK_WPM] when not.
     */
    fun timeSavedMs(totals: TypingStats.Totals): Long {
        val wpm = TypingStatsMath.wpm(totals.chars, totals.activeMs)
            .takeIf { totals.activeMs >= TypingStats.MIN_ACTIVE_MS && it > 0.0 } ?: FALLBACK_WPM
        val charsPerMs = wpm * CHARS_PER_WORD / MINUTE_MS
        return (totals.keystrokesSaved / charsPerMs).roundToLong()
    }

    /** Glide distance as laps of a [TRACK_METRES] running track. */
    fun trackLaps(totals: TypingStats.Totals): Double = totals.glideDistanceMm / MM_PER_METRE / TRACK_METRES

    /** Every key press counted: characters and backspaces. */
    fun keyPresses(totals: TypingStats.Totals): Long = totals.chars + totals.backspaces

    /**
     * The punch card's busiest weekday (Monday 0), or -1 when it is empty.
     */
    fun busiestWeekday(weekHours: List<Long>): Int {
        if (weekHours.size < WEEK_HOURS || weekHours.all { it <= 0L }) return -1
        return (0 until DAYS).maxBy { day -> (0 until HOURS).sumOf { weekHours[day * HOURS + it] } }
    }

    /** A round figure for the length of an adult novel. */
    const val NOVEL_WORDS = 80_000L

    /** One lap of a standard outdoor track. */
    const val TRACK_METRES = 400.0

    /** The speed the time saved is reckoned at before there is one of the user's own. */
    const val FALLBACK_WPM = 40.0

    /** Judged autocorrects before the trust figure is shown. */
    const val MIN_VERDICTS = 20L

    const val MAX_BADGES = 4

    /** Keystrokes in the histogram before a time-of-day or week badge is given. */
    const val MIN_BADGE_KEYSTROKES = 2_000L

    /** Words before a shouting badge is given. */
    const val MIN_BADGE_WORDS = 500L

    /** Sentence-ending marks before a punctuation badge is given. */
    const val MIN_BADGE_MARKS = 100L

    /** 22:00 to 03:59, as hours of the day; 24 and 25 wrap to 0 and 1. */
    val NIGHT_HOURS = 22..27
    val EARLY_HOURS = 5..8
    val LUNCH_HOURS = 12..13
    const val NIGHT_SHARE = 0.35
    const val EARLY_SHARE = 0.30
    const val LUNCH_SHARE = 0.30
    const val WEEKEND_SHARE = 0.40
    const val WEEKDAY_SHARE = 0.10
    const val CAREFUL_BACKSPACES = 5.0
    const val FEARLESS_BACKSPACES = 15.0

    /** Shouted words per hundred words that make a shouter. */
    const val SHOUT_SHARE = 5.0

    /** The full stop's share of sentence enders that makes a purist. */
    const val FULL_STOP_SHARE = 0.9

    private const val SATURDAY = 5
    private const val SUNDAY = 6
    private const val PERCENT = 100.0
    private const val MINUTE_MS = 60_000L
    private const val CHARS_PER_WORD = 5.0
    private const val MM_PER_METRE = 1_000.0
    private const val DAYS = 7
    private const val HOURS = 24
    private const val WEEK_HOURS = DAYS * HOURS
}

/**
 * The geometry of the tap heatmap: the grid taps are counted on, and the
 * questions the screen asks of it. Coordinates are key widths across and rows
 * down, the frame [TypingStats.onKeyTap] takes.
 */
object TypingHeatmapMath {

    /** A cell is a quarter of a key each way. */
    const val CELL = 0.25f

    /** Cells per packed row: 128 keys across, far wider than any board. */
    private const val STRIDE = 512

    /** How tall a row is drawn relative to a key's width. */
    const val ROW_ASPECT = 1.35f

    /** A tap further than this from every letter (in key widths) belongs to
     * no letter — the space bar, shift, the number row's own keys. */
    const val MAX_KEY_REACH = 0.75f

    fun cellOf(x: Float, y: Float): Int {
        val col = (x / CELL).toInt().coerceIn(0, STRIDE - 1)
        val row = (y / CELL).toInt().coerceIn(0, STRIDE - 1)
        return row * STRIDE + col
    }

    /** The centre of a [cellOf] cell, as (x, y). */
    fun centreOf(cell: Int): Pair<Float, Float> {
        val col = Math.floorMod(cell, STRIDE)
        val row = Math.floorDiv(cell, STRIDE)
        return (col + 0.5f) * CELL to (row + 0.5f) * CELL
    }

    /**
     * The distance from one row of letters to the next, in the units [ys] are
     * given in: the smallest clear gap between distinct rows. A board with one
     * row, or none, answers 1 so a caller can always divide by it.
     */
    fun rowPitch(ys: List<Float>): Float {
        val rows = ys.filter { it.isFinite() }.sorted()
        var pitch = Float.MAX_VALUE
        for (i in 1 until rows.size) {
            val gap = rows[i] - rows[i - 1]
            if (gap > MIN_ROW_GAP && gap < pitch) pitch = gap
        }
        return if (pitch == Float.MAX_VALUE) 1f else pitch
    }

    /**
     * The letters tapped most on [map], most first, each with its tap count:
     * every cell goes to the nearest letter, if one is close enough. Rows are
     * measured at their drawn height, so a tap between two rows goes to the
     * one it looks closer to.
     */
    fun topKeys(map: TypingStats.Heatmap, limit: Int): List<Pair<String, Long>> {
        if (map.keys.isEmpty() || limit <= 0) return emptyList()
        val byKey = HashMap<String, Long>()
        for ((cell, count) in map.cells) {
            val (x, y) = centreOf(cell)
            var best: TypingStats.HeatKey? = null
            var bestDistance = Float.MAX_VALUE
            for (key in map.keys) {
                val dx = x - key.x
                val dy = (y - key.y) * ROW_ASPECT
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = key
                }
            }
            if (best != null && bestDistance <= MAX_KEY_REACH * MAX_KEY_REACH) {
                byKey.merge(best.label, count, Long::plus)
            }
        }
        return byKey.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    /** Rows closer than this are the same row, staggered. */
    private const val MIN_ROW_GAP = 0.3f
}
