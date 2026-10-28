package com.wasimaster.wmkeyboard.core.voice.vad

import java.io.File

/**
 * Hears a live dictation's end on the audio its engine is fed: the user said
 * something, the [detector] heard the speech stop, and then the quiet went on
 * for [quietToEnd] samples more (the pause set in "Stop after a pause", less
 * the detector's own hang). Below 0, it never ends by itself; the detector is
 * still fed, for an engine that decodes the phrases it cuts.
 *
 * With Smart Turn ([smartTurnDir] set), the model is asked once each time the
 * detector hears speech stop: a speaker who sounds finished ends the phrase
 * there and then, one who does not waits out the whole pause, so a long pause
 * can be set without a finished sentence hanging on it.
 *
 * Not thread-safe: the engine's thread feeds it, reads it and closes it.
 */
class PhraseEnd internal constructor(
    val detector: SpeechDetector,
    private val quietToEnd: Int,
    private val smartTurnDir: File?,
) : AutoCloseable {

    /** Speech has been heard since the dictation began. */
    var heardSpeech = false
        private set

    /** The detector hears speech now (a pause shorter than its hang counts). */
    var speaking = false
        private set

    private var quiet = 0
    private var finished = false

    // The last 8 s fed, as a ring, for Smart Turn.
    private val recent = if (smartTurnDir != null) FloatArray(SmartTurn.WINDOW_SAMPLES) else null
    private var recentAt = 0
    private var recentSize = 0

    fun feed(samples: FloatArray, count: Int = samples.size) {
        if (count <= 0) return
        recent?.let { remember(it, samples, count) }
        val now = detector.feed(samples, count)
        val stopped = speaking && !now
        speaking = now
        if (now) {
            heardSpeech = true
            quiet = 0
            finished = false
        } else {
            quiet += count
        }
        if (stopped && smartTurnDir != null && quietToEnd > 0) finished = SmartTurn.finished(smartTurnDir, recentAudio())
    }

    /** The speaker has stopped: words, then the pause, or a short pause Smart Turn calls finished. */
    val ended: Boolean
        get() = quietToEnd >= 0 && heardSpeech && !speaking && (finished || quiet >= quietToEnd)

    override fun close() {
        detector.close()
    }

    private fun remember(ring: FloatArray, samples: FloatArray, count: Int) {
        // Only the last ring's worth of a long chunk can matter.
        val from = (count - ring.size).coerceAtLeast(0)
        for (i in from until count) {
            ring[recentAt] = samples[i]
            recentAt = (recentAt + 1) % ring.size
        }
        recentSize = minOf(ring.size, recentSize + (count - from))
    }

    private fun recentAudio(): FloatArray {
        val ring = recent ?: return FloatArray(0)
        val out = FloatArray(recentSize)
        val start = (recentAt - recentSize + ring.size) % ring.size
        for (i in 0 until recentSize) out[i] = ring[(start + i) % ring.size]
        return out
    }
}
