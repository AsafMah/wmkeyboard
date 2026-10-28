package com.wasimaster.wmkeyboard.core.gesture

/**
 * One finger path, kept whole so it can be decoded again later.
 *
 * The grid goes with the points on purpose: a stroke is only meaningful
 * against the keys it was drawn on, and by the time it is read back the
 * keyboard may be in another orientation, another layout or another size.
 * [shape] is the sample the shape store files under the word, when swipe-style
 * learning is on.
 */
class GlideStroke(
    val points: List<GesturePoint>,
    val keys: List<KeyCenter>,
    val keyWidthPx: Float,
    val shape: GlideShapeSample? = null,
) {

    /**
     * True when this stroke could have been an attempt at [word] on [map], the
     * grid it was drawn on: it starts no further than [END_SLACK] key widths
     * from the key of the word's first letter, and ends that close to the key
     * of its last.
     *
     * The gate a backspaced glide's stroke passes before the next word may
     * claim it (#805). Deleting a word and typing a different one is as often
     * an edit of the sentence as a retry of the swipe, and without it a
     * stroke drawn for "the" taught the swipe learners that it meant "a".
     * The ends are what a glide gets right even when its middle is sloppy,
     * and what the decoder itself leans on hardest, so a word whose ends the
     * finger never came near is not this stroke's answer.
     *
     * False when the stroke is empty or either end letter is not on the grid
     * at all (a romanized stroke's Bengali answer): nothing can be judged, so
     * nothing is taught.
     */
    fun endsFit(word: String, map: GlideKeyMap): Boolean {
        if (points.isEmpty() || word.isEmpty() || keyWidthPx <= 0f) return false
        val first = map.keyIndex(word.codePointAt(0))
        val last = map.keyIndex(word.codePointBefore(word.length))
        if (first < 0 || last < 0) return false
        return near(points.first(), first, map) && near(points.last(), last, map)
    }

    private fun near(point: GesturePoint, key: Int, map: GlideKeyMap): Boolean {
        val dx = point.x / keyWidthPx - map.keyX[key]
        val dy = point.y / keyWidthPx - map.keyY[key]
        return dx * dx + dy * dy <= END_SLACK * END_SLACK
    }

    companion object {
        /**
         * How many samples at one end of [points] are a straight walk to or
         * from the spacebar (#968): `wm-engine`'s `glide::open_run`, number
         * for number. From the bar end inward, every sample that lies below
         * the next one inward (within [OPEN_RUN_LEVEL] of level: the bar is
         * under the keys) and stays within [OPEN_RUN_DRIFT] key widths of
         * the bar end's column, as long as the run drops [OPEN_RUN_DESCENT]
         * in all. The letter the walk left may sit on the run ("then": `n`
         * is straight above the bar), which is why the decoder reads the
         * run as optional rather than cut. 0 for no run, never more than
         * leaves three samples closed.
         */
        fun openRun(points: List<GesturePoint>, keyWidthPx: Float, atEnd: Boolean): Int {
            val n = points.size
            if (n < 4 || keyWidthPx <= 0f) return 0
            val level = OPEN_RUN_LEVEL * keyWidthPx
            val drift = OPEN_RUN_DRIFT * keyWidthPx
            val barEnd = if (atEnd) points[n - 1] else points[0]
            var run = 0
            val maxRun = n - 3
            while (run < maxRun) {
                val inner = if (atEnd) points[n - 2 - run] else points[run + 1]
                val outer = if (atEnd) points[n - 1 - run] else points[run]
                if (outer.y < inner.y - level || kotlin.math.abs(inner.x - barEnd.x) > drift) break
                run++
            }
            if (run < 2) return 0
            val innermost = if (atEnd) points[n - 1 - run] else points[run]
            return if (barEnd.y - innermost.y < OPEN_RUN_DESCENT * keyWidthPx) 0 else run
        }

        /** [openRun]: how much a sample may sit above the next one inward, in key widths. */
        const val OPEN_RUN_LEVEL = 0.05f

        /** [openRun]: how far from the bar end's column the walk may wander, in key widths. */
        const val OPEN_RUN_DRIFT = 0.6f

        /** [openRun]: the least the walk must descend toward the bar in all, in key widths. */
        const val OPEN_RUN_DESCENT = 0.5f

        /**
         * How far, in key widths, a stroke's end may land from the letter it
         * stands for. Wider than the decoder's own start and end radius (1.6
         * by default), since a stroke the decoder misread is exactly one whose
         * ends wandered: rows are taller than keys are wide, so the far edge
         * of the key above or below is already about two key widths off.
         * 1.5 refused real corrections (#805); 2.0–2.2 is where learning on
         * QWERTY behaved in the reporter's own trials, and "the" still starts
         * over three keys from "a".
         */
        const val END_SLACK = 2.2f
    }
}
