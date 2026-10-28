package com.wasimaster.wmkeyboard.core.voice.vad

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Kaldi's CMVN statistics, as FireRedVAD ships them in `cmvn.ark`: a binary
 * 2 x (dim + 1) matrix whose first row sums each feature (and counts the
 * frames in its last column) and whose second sums their squares. What the
 * model wants is a mean and an inverse standard deviation per feature, worked
 * out exactly as upstream's `CMVN.read_kaldi_cmvn` does.
 */
internal object KaldiCmvn {

    /** Variances are raised to this before the square root, as upstream does. */
    private const val VARIANCE_FLOOR = 1e-20

    /** Means and inverse standard deviations, or null when [bytes] is not such a matrix. */
    fun parse(bytes: ByteArray): Pair<FloatArray, FloatArray>? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // "\0B": Kaldi's binary marker, then the matrix type: DM doubles, FM floats.
        if (buf.remaining() < 15 || buf.get() != 0.toByte() || buf.get() != 'B'.code.toByte()) return null
        val type = ByteArray(3).also { buf.get(it) }.toString(Charsets.US_ASCII)
        val doubles = when (type) {
            "DM " -> true
            "FM " -> false
            else -> return null
        }
        val rows = readInt(buf) ?: return null
        val cols = readInt(buf) ?: return null
        if (rows != 2 || cols < 2) return null
        val width = if (doubles) 8 else 4
        if (buf.remaining() < rows * cols * width) return null
        val stats = DoubleArray(rows * cols) { if (doubles) buf.getDouble() else buf.getFloat().toDouble() }
        val dim = cols - 1
        val count = stats[dim]
        if (count < 1) return null
        val mean = FloatArray(dim)
        val scale = FloatArray(dim)
        for (d in 0 until dim) {
            val m = stats[d] / count
            val variance = (stats[cols + d] / count - m * m).coerceAtLeast(VARIANCE_FLOOR)
            mean[d] = m.toFloat()
            scale[d] = (1.0 / sqrt(variance)).toFloat()
        }
        return mean to scale
    }

    /** One of Kaldi's sized integers: its byte count (4), then the value. */
    private fun readInt(buf: ByteBuffer): Int? {
        if (buf.remaining() < 5 || buf.get() != 4.toByte()) return null
        return buf.getInt()
    }
}
