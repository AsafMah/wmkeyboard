package com.wasimaster.wmkeyboard.core.handwriting

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The keyboard's own word lists, as the ink reader asks them: the words
 * [reading] could be, best first, after [previousWord]. The handwriting
 * service answers with the suggestion engine's corrections, so the reader
 * leans on the same dictionaries typing does. Null or empty when there is
 * nothing to offer.
 */
fun interface InkLexicon {
    suspend fun candidates(reading: String, previousWord: String?, limit: Int): List<String>?
}

/**
 * Turns what a PaddleOCR recogniser saw in a strip of ink into the texts the
 * panel offers, best first.
 *
 * The recogniser answers, for every few pixels along the strip, how likely
 * each character is (CTC). The plain reading takes the likeliest at each
 * step; handwriting is too loose for that to get many words right on its own
 * (about four in ten on traced English), so each word it reads is weighed
 * against the words the keyboard's dictionaries think it could be
 * ([InkLexicon]): every candidate scores the probability the recogniser
 * gives that exact spelling over the word's steps, summed over every way of
 * lining it up with them (the CTC forward pass), and the likeliest spelling
 * wins. The plain reading competes too, [UNKNOWN_PENALTY] behind, so a name
 * or a word no list holds can still come through. Measured on 192 traced
 * English words: 42% read right plainly, 53% weighed.
 *
 * Three things about the recognisers themselves:
 * - Their character lists hold Latin letters beside every other script's,
 *   and a Cyrillic or Greek letter that looks like a Latin one is often read
 *   as the Latin one (`р` as `p`). [folds] merges each such Latin letter's
 *   probability into its look-alike before anything is read.
 * - Arabic-script recognisers read in the order the letters sit on the
 *   strip, left to right; [rightToLeft] turns each word around, keeping runs
 *   of digits and Latin in their own order, as PaddleOCR's own reader does.
 * - A space is a class of its own, last in the list; it is where one word
 *   ends and the next begins.
 *
 * Pure arithmetic over arrays: InkOcrDecoderTest runs it on the JVM.
 */
internal class InkOcrDecoder(
    /** Class index to text: the CTC blank first, then the character list, then the space. */
    private val charset: List<String>,
    /** Latin letters to read as the script's look-alikes, by their text. */
    folds: Map<String, String> = emptyMap(),
    private val rightToLeft: Boolean = false,
    /** Whether the script has capitals, so a candidate is also tried capitalised and in capitals. */
    private val cased: Boolean = true,
) {

    private val space = charset.lastIndex
    private val index: Map<String, Int> = HashMap<String, Int>().also { map ->
        // First wins: a character listed twice keeps its first class.
        charset.forEachIndexed { i, text -> if (i != 0 && text.isNotEmpty()) map.putIfAbsent(text, i) }
    }
    private val longest = charset.maxOf { it.length }.coerceAtLeast(1)
    private val foldPairs: List<Pair<Int, Int>> = folds.mapNotNull { (from, to) ->
        val f = index[from] ?: return@mapNotNull null
        val t = index[to] ?: return@mapNotNull null
        f to t
    }

    /**
     * The readings of [probs] ([steps] rows of [classes] probabilities), best
     * first, at most [limit]. [previousWord] is the word before the ink, for
     * the lexicon's context. Empty when the recogniser saw nothing.
     */
    suspend fun read(
        probs: FloatArray,
        steps: Int,
        classes: Int,
        lexicon: InkLexicon?,
        previousWord: String?,
        limit: Int,
    ): List<String> {
        require(classes == charset.size) { "$classes classes for a list of ${charset.size}" }
        val logp = logProbabilities(probs, steps, classes)
        val best = IntArray(steps) { t -> argmax(logp, t, classes) }
        val spans = wordSpans(best)
        if (spans.isEmpty()) return emptyList()

        // Words in the order they sit on the strip, left to right.
        val visual = spans.map { (from, to) -> collapse(best, from, to) }
        val plain = ordered(visual.map(::logical))
        var previous = previousWord
        // Weighed in reading order, so each word's context is the one before it.
        val readingOrder = if (rightToLeft) spans.indices.reversed() else spans.indices
        val byPosition = arrayOfNulls<List<String>>(spans.size)
        for (i in readingOrder) {
            val (from, to) = spans[i]
            val options = weigh(logp, classes, from, to, logical(visual[i]), lexicon, previous)
            byPosition[i] = options
            previous = options.first()
        }
        val choices = byPosition.map { it!! }
        val words = ordered(choices.map { it.first() })
        val line = words.joinToString(" ")
        // The last word written is the one the strip offers others for,
        // as zinnia's reader does with its last character.
        val lastAt = if (rightToLeft) 0 else choices.lastIndex
        val head = words.dropLast(1).joinToString(" ")
        val others = choices[lastAt].drop(1).map { if (head.isEmpty()) it else "$head $it" }
        return (listOf(line) + others + plain.joinToString(" "))
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }

    /** Words listed left to right on the strip, in the order they are read. */
    private fun ordered(words: List<String>): List<String> = if (rightToLeft) words.reversed() else words

    /**
     * [reading]'s spellings, likeliest first: the lexicon's words and the
     * plain reading itself, each scored over steps [from] to [to].
     */
    private suspend fun weigh(
        logp: FloatArray, classes: Int, from: Int, to: Int,
        reading: String, lexicon: InkLexicon?, previous: String?,
    ): List<String> {
        val candidates = lexicon?.let { runCatching { it.candidates(reading, previous, CANDIDATES) }.getOrNull() }
        if (candidates.isNullOrEmpty()) return listOf(reading)
        val scores = HashMap<String, Double>()
        candidates.forEachIndexed { rank, word ->
            for (spelling in spellingsOf(word)) {
                val labels = labels(visualOf(spelling)) ?: continue
                val score = ctc(logp, classes, from, to, labels) - RANK_WEIGHT * ln(1.0 + rank)
                scores.merge(spelling, score, ::maxOf)
            }
        }
        labels(visualOf(reading))?.let { labels ->
            val score = ctc(logp, classes, from, to, labels) - UNKNOWN_PENALTY
            scores.merge(reading, score, ::maxOf)
        }
        if (scores.isEmpty()) return listOf(reading)
        return scores.entries.sortedByDescending { it.value }.map { it.key }.take(WORD_OPTIONS)
    }

    private fun spellingsOf(word: String): Set<String> =
        if (!cased || word.isEmpty()) {
            setOf(word)
        } else {
            linkedSetOf(word, word.replaceFirstChar { it.uppercaseChar() }, word.uppercase())
        }

    /** The class indices that spell [text], longest listed piece first; null when some part is not listed. */
    fun labels(text: String): IntArray? {
        if (text.isEmpty()) return null
        val out = ArrayList<Int>(text.length)
        var at = 0
        while (at < text.length) {
            var found = -1
            var length = minOf(longest, text.length - at)
            while (length > 0) {
                val i = index[text.substring(at, at + length)]
                if (i != null && i != space) {
                    found = i
                    break
                }
                length--
            }
            if (found < 0) return null
            out += found
            at += length
        }
        return out.toIntArray()
    }

    /** The plain reading of steps [from] to [to]: runs read once, blanks dropped. */
    private fun collapse(best: IntArray, from: Int, to: Int): String {
        val text = StringBuilder()
        var previous = 0
        for (t in from until to) {
            val c = best[t]
            if (c != 0 && c != previous && c != space) text.append(charset[c])
            previous = c
        }
        return text.toString()
    }

    /**
     * The words on the strip as step ranges: runs of steps between the
     * spaces the recogniser read, each holding at least one character.
     */
    private fun wordSpans(best: IntArray): List<Pair<Int, Int>> {
        val spans = ArrayList<Pair<Int, Int>>()
        var start = 0
        var inked = false
        for (t in 0..best.size) {
            if (t == best.size || best[t] == space) {
                if (inked) spans += start to t
                start = t + 1
                inked = false
            } else if (best[t] != 0) {
                inked = true
            }
        }
        return spans
    }

    /** Natural logs of [probs], with each Latin look-alike's probability moved onto its letter. */
    private fun logProbabilities(probs: FloatArray, steps: Int, classes: Int): FloatArray {
        val out = probs.copyOf(steps * classes)
        if (foldPairs.isNotEmpty()) {
            for (t in 0 until steps) {
                val row = t * classes
                for ((from, to) in foldPairs) {
                    out[row + to] += out[row + from]
                    out[row + from] = 0f
                }
            }
        }
        for (i in out.indices) out[i] = ln(max(out[i], FLOOR))
        return out
    }

    private fun argmax(logp: FloatArray, t: Int, classes: Int): Int {
        val row = t * classes
        var best = 0
        for (k in 1 until classes) if (logp[row + k] > logp[row + best]) best = k
        return best
    }

    /**
     * log P([labels] | steps [from] to [to]), CTC's forward pass: every way
     * of spreading the labels over the steps, a label held for several steps
     * or separated by blanks, with a blank required between a label and its
     * repeat.
     */
    fun ctc(logp: FloatArray, classes: Int, from: Int, to: Int, labels: IntArray): Double {
        val steps = to - from
        if (steps <= 0) return Double.NEGATIVE_INFINITY
        val s = labels.size * 2 + 1
        fun symbol(i: Int) = if (i % 2 == 0) 0 else labels[i / 2]
        var alpha = DoubleArray(s) { Double.NEGATIVE_INFINITY }
        var next = DoubleArray(s)
        alpha[0] = logp[from * classes].toDouble()
        if (s > 1) alpha[1] = logp[from * classes + symbol(1)].toDouble()
        for (t in 1 until steps) {
            val row = (from + t) * classes
            for (i in 0 until s) {
                var v = alpha[i]
                if (i >= 1) v = logAdd(v, alpha[i - 1])
                if (i >= 2 && symbol(i) != 0 && symbol(i) != symbol(i - 2)) v = logAdd(v, alpha[i - 2])
                next[i] = v + logp[row + symbol(i)]
            }
            val swap = alpha
            alpha = next
            next = swap
        }
        return if (s > 1) logAdd(alpha[s - 1], alpha[s - 2]) else alpha[0]
    }

    /** A word as it reads, from the order it sits on the strip in. */
    fun logical(visual: String): String = if (rightToLeft) turn(visual) else visual

    /** A word as it sits on the strip, from the order it reads in. */
    fun visualOf(logical: String): String = if (rightToLeft) turn(logical) else logical

    /**
     * PaddleOCR's own turn for right-to-left recognisers: the word reversed
     * character by character, except that a run of Latin letters, digits and
     * the marks that go with them stays in its own order. Its own inverse.
     */
    private fun turn(text: String): String {
        val pieces = ArrayList<String>()
        val run = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (keepsOrder(cp)) {
                run.appendCodePoint(cp)
            } else {
                if (run.isNotEmpty()) {
                    pieces += run.toString()
                    run.setLength(0)
                }
                pieces += text.substring(i, i + n)
            }
            i += n
        }
        if (run.isNotEmpty()) pieces += run.toString()
        return pieces.asReversed().joinToString("")
    }

    private fun keepsOrder(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code || cp.toChar() in LTR_MARKS

    companion object {
        /** Words asked of the lexicon for each written word. */
        const val CANDIDATES = 12

        /** Spellings kept per word, for the strip's alternatives. */
        private const val WORD_OPTIONS = 4

        /**
         * How far behind the dictionary's words the plain reading starts,
         * in log probability. Tuned on traced English (2026-10-25): 6 reads
         * 52.6% of words right, never letting the plain reading win 53.1%,
         * 2 only 50.0%.
         */
        private const val UNKNOWN_PENALTY = 6.0

        /** A tie-break toward the lexicon's own order; on its own it moved nothing in the tuning. */
        private const val RANK_WEIGHT = 0.5

        private const val FLOOR = 1e-9f

        // The marks PaddleOCR keeps with a run of Latin or digits when it turns an Arabic line.
        private const val LTR_MARKS = " :*./%+-"

        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val hi = max(a, b)
            return hi + ln(1.0 + exp(-kotlin.math.abs(a - b)))
        }
    }
}
