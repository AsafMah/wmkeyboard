package com.wasimaster.wmkeyboard.core.voice.vad

import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import kotlin.math.sqrt

/**
 * Hears whether someone is speaking, as a dictation's audio (16 kHz mono, in
 * [-1, 1]) is fed to it. [feed] says true while speech goes on, a pause
 * shorter than the detector's own hang included. One that cuts also keeps
 * each stretch of speech as a phrase, handed out by [phrase] oldest first,
 * for an engine that decodes a phrase at a time. One thread feeds it, and
 * closes it once.
 */
interface SpeechDetector : AutoCloseable {

    fun feed(samples: FloatArray, count: Int = samples.size): Boolean

    /** The oldest finished phrase, or null when there is none (or this one does not cut). */
    fun phrase(): FloatArray? = null

    /** The audio is over: speech still going on becomes a last phrase. */
    fun flush() {}
}

/**
 * sherpa-onnx's own detector, Silero or TEN, through a handle from
 * [SherpaNative.nativeVadOf] (or the older [SherpaNative.nativeVad] and
 * [SherpaNative.nativeSegmenter]). It cuts when it was opened to keep its
 * segments.
 */
internal class SherpaVadDetector(private var handle: Long) : SpeechDetector {

    override fun feed(samples: FloatArray, count: Int): Boolean {
        if (handle == 0L || count <= 0) return false
        return SherpaNative.nativeVadFeed(handle, samples, count)
    }

    override fun phrase(): FloatArray? = if (handle == 0L) null else SherpaNative.nativeVadSegment(handle)

    override fun flush() {
        if (handle != 0L) SherpaNative.nativeVadFlush(handle)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) SherpaNative.nativeVadFree(h)
    }
}

/** A speech probability for every 10 ms of audio fed in. */
internal interface FrameSource : AutoCloseable {

    /** [count] samples in; the probability, 0 to 1, of each 10 ms frame they complete. */
    fun frames(samples: FloatArray, count: Int): FloatArray

    override fun close() {}
}

/** FireRedVAD, through a handle from [SherpaNative.nativeFireRed]. */
internal class FireRedFrames(private var handle: Long) : FrameSource {

    override fun frames(samples: FloatArray, count: Int): FloatArray {
        if (handle == 0L || count <= 0) return EMPTY
        return SherpaNative.nativeFireRedFeed(handle, samples, count)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) SherpaNative.nativeFireRedFree(h)
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}

/**
 * No model at all: a 10 ms frame is speech when it is loud enough, [rms] or
 * more, and silence otherwise. The same measure the recorder's own silence
 * stop uses, so a loud room keeps it talking and a soft voice can fall under
 * it; it costs nothing and needs no download.
 */
internal class LoudnessFrames(private val rms: Float) : FrameSource {

    private val pending = FloatArray(FRAME)
    private var filled = 0

    override fun frames(samples: FloatArray, count: Int): FloatArray {
        val out = FloatArray((filled + count) / FRAME)
        var made = 0
        for (i in 0 until count) {
            pending[filled++] = samples[i]
            if (filled == FRAME) {
                var sumSq = 0.0
                for (v in pending) sumSq += v * v
                out[made++] = if (sqrt(sumSq / FRAME) >= rms) 1f else 0f
                filled = 0
            }
        }
        return out
    }

    companion object {
        const val FRAME = 160
    }
}

/**
 * Speech and silence from per-frame probabilities, after FireRedVAD's own
 * streaming postprocessor (`stream_vad_postprocessor.py`): the probability is
 * averaged over the last [SMOOTH] frames and held to [threshold]; speech
 * starts after [minSpeech] frames of it and stops after [hang] frames of
 * silence. When [cuts], the audio between a start ([PAD] frames early) and a
 * stop is kept as a phrase, and a phrase of [maxSpeech] frames is cut there
 * and the next one begins.
 */
internal class FrameDetector(
    private val source: FrameSource,
    private val threshold: Float,
    private val minSpeech: Int,
    private val hang: Int,
    private val maxSpeech: Int,
    private val cuts: Boolean,
) : SpeechDetector {

    private enum class State { SILENCE, POSSIBLE_SPEECH, SPEECH, POSSIBLE_SILENCE }

    private val smooth = FloatArray(SMOOTH)
    private var smoothCount = 0
    private var smoothSum = 0f
    private var state = State.SILENCE
    private var speechRun = 0
    private var silenceRun = 0

    /** Frames seen so far. */
    private var frame = 0L

    // Cutting: the audio from [audioStart] on, the open phrase's first sample
    // (or -1), and the finished phrases.
    private var audio = FloatArray(0)
    private var audioSize = 0
    private var audioStart = 0L
    private var phraseStart = -1L
    private var phraseFrames = 0
    private var lastEnd = 0L
    private val phrases = ArrayDeque<FloatArray>()

    override fun feed(samples: FloatArray, count: Int): Boolean {
        if (count <= 0) return speaking()
        if (cuts) append(samples, count)
        for (p in source.frames(samples, count)) step(p)
        if (cuts) trim()
        return speaking()
    }

    override fun phrase(): FloatArray? = phrases.removeFirstOrNull()

    override fun flush() {
        if (cuts && phraseStart >= 0) endPhrase(audioStart + audioSize)
    }

    override fun close() {
        source.close()
    }

    private fun speaking(): Boolean = state == State.SPEECH || state == State.POSSIBLE_SILENCE

    private fun step(probability: Float) {
        frame++
        val slot = (smoothCount % SMOOTH)
        if (smoothCount >= SMOOTH) smoothSum -= smooth[slot]
        smooth[slot] = probability
        smoothSum += probability
        smoothCount++
        val speech = smoothSum / minOf(smoothCount, SMOOTH) >= threshold
        when (state) {
            State.SILENCE -> if (speech) {
                state = State.POSSIBLE_SPEECH
                speechRun = 1
            }
            State.POSSIBLE_SPEECH -> if (speech) {
                speechRun++
                if (speechRun >= minSpeech) {
                    state = State.SPEECH
                    silenceRun = 0
                    if (cuts) startPhrase((frame - speechRun - PAD).coerceAtLeast(0) * FRAME_SAMPLES)
                }
            } else {
                state = State.SILENCE
                speechRun = 0
            }
            State.SPEECH -> if (speech) {
                silenceRun = 0
            } else {
                state = State.POSSIBLE_SILENCE
                silenceRun = 1
            }
            State.POSSIBLE_SILENCE -> if (speech) {
                state = State.SPEECH
                silenceRun = 0
            } else if (++silenceRun >= hang) {
                state = State.SILENCE
                speechRun = 0
                if (cuts) endPhrase(frame * FRAME_SAMPLES)
            }
        }
        if (cuts && phraseStart >= 0 && ++phraseFrames >= maxSpeech) {
            // Too long without a pause the detector takes: cut here, go on.
            val at = frame * FRAME_SAMPLES
            endPhrase(at)
            startPhrase(at)
        }
    }

    private fun startPhrase(at: Long) {
        phraseStart = maxOf(at, lastEnd, audioStart)
        phraseFrames = 0
    }

    private fun endPhrase(at: Long) {
        val start = phraseStart
        phraseStart = -1
        val end = minOf(at, audioStart + audioSize)
        lastEnd = end
        if (start < 0 || end <= start) return
        val from = (start - audioStart).toInt()
        phrases.addLast(audio.copyOfRange(from, from + (end - start).toInt()))
    }

    private fun append(samples: FloatArray, count: Int) {
        if (audioSize + count > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, audioSize + count, (FRAME_SAMPLES * 100).toInt()))
        samples.copyInto(audio, audioSize, 0, count)
        audioSize += count
    }

    /** Drops the audio no phrase can still reach back into. */
    private fun trim() {
        val keepFrom = if (phraseStart >= 0) {
            phraseStart
        } else {
            (audioStart + audioSize - (PAD + minSpeech + SMOOTH + LAG) * FRAME_SAMPLES).coerceAtLeast(audioStart)
        }
        val drop = (keepFrom - audioStart).toInt()
        if (drop <= 0) return
        audio.copyInto(audio, 0, drop, audioSize)
        audioSize -= drop
        audioStart = keepFrom
    }

    companion object {
        /** One frame is 10 ms. */
        const val FRAME_SAMPLES = 160L

        /** Frames averaged; upstream's default. */
        const val SMOOTH = 5

        /** Frames a phrase starts before its speech was sure; upstream's default. */
        const val PAD = 5

        /** Frames a model may trail the audio by (FireRedVAD reads 25 ms windows). */
        private const val LAG = 3
    }
}
