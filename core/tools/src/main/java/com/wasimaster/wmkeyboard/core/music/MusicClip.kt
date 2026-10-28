package com.wasimaster.wmkeyboard.core.music

import kotlin.math.sqrt

/**
 * The clip the Music ID tool records: how long it listens, when a clip is
 * long enough to ask about, and whether anything was heard at all.
 *
 * The microphone records at 16 kHz mono, as `WhisperRecorder` does for
 * dictation. The online services take any sample rate (AudD and ACRCloud
 * decode the file they are sent), so the clip goes up as recorded; the one
 * service that wants 44.1 kHz PCM is given an upsampled copy ([upsample]),
 * and the offline engine resamples to its own rate itself.
 */
object MusicClip {

    /** The microphone's rate, the rate every clip is in. */
    const val SAMPLE_RATE = 16_000

    /** How long a clip is by default: the length Shazam and AudD work best at. */
    const val DEFAULT_SECONDS = 10

    const val MIN_SECONDS = 6
    const val MAX_SECONDS = 12

    /** A clip shorter than this is not worth a request: a tap-and-release cannot name a song. */
    const val ASK_AFTER_SECONDS = 4

    /**
     * Below this RMS the microphone heard the room and nothing else; the
     * same floor `WhisperRecorder` uses to tell speech from silence, which
     * music clears easily and a muted phone does not.
     */
    private const val SILENCE_RMS = 0.004f

    /** Whether [samples] carry anything louder than a quiet room. */
    fun heardSomething(samples: FloatArray): Boolean = rms(samples) > SILENCE_RMS

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * [samples] at [targetRate], by linear interpolation. Only ever used to
     * go up (16 kHz to 44.1 kHz), where interpolation adds no aliasing worth
     * a filter: the content above 8 kHz is simply absent either way.
     */
    fun upsample(samples: FloatArray, fromRate: Int, targetRate: Int): FloatArray {
        if (fromRate == targetRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / targetRate
        val out = FloatArray((samples.size / ratio).toInt())
        for (i in out.indices) {
            val pos = i * ratio
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = samples[j.coerceAtMost(samples.lastIndex)]
            val b = samples[(j + 1).coerceAtMost(samples.lastIndex)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** [samples] as little-endian 16-bit PCM, the raw shape some services take. */
    fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
