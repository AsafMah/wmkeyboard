package com.wasimaster.wmkeyboard.core.tools.speedtest

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The arithmetic of a run, kept apart from the sockets so it can be tested
 * on the JVM: speeds from byte counts, the ping statistics, the trimming
 * that keeps TCP's slow start out of the headline number, and the shapes
 * the card draws.
 */
object SpeedTestMath {

    /** Megabits per second for [bytes] moved in [ms]; 0 for no time at all. */
    fun mbps(bytes: Long, ms: Long): Double =
        if (ms <= 0L || bytes <= 0L) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

    /** The middle value, or the mean of the two middle ones; 0 for nothing. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Jitter as LibreSpeed and the RFC 3550 estimator both have it in
     * spirit: the mean absolute difference between consecutive pings. One
     * ping has no jitter.
     */
    fun jitter(pings: List<Double>): Double {
        if (pings.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until pings.size) sum += abs(pings[i] - pings[i - 1])
        return sum / (pings.size - 1)
    }

    /**
     * The speed a phase settles on: the mean of its samples once the first
     * [graceFraction] of them are dropped, which is where TCP is still
     * opening its window and every connection is still being set up. With
     * very few samples nothing is dropped, so a short run still has a number.
     */
    fun trimmedThroughput(samples: List<Float>, graceFraction: Double = 0.2): Double {
        if (samples.isEmpty()) return 0.0
        val drop = if (samples.size >= 5) (samples.size * graceFraction).toInt() else 0
        val kept = samples.drop(drop)
        return kept.sumOf { it.toDouble() } / kept.size
    }

    /** The mean of the last [window] samples. */
    fun current(samples: List<Float>, window: Int = 3): Double {
        if (samples.isEmpty()) return 0.0
        val tail = samples.takeLast(window)
        return tail.sumOf { it.toDouble() } / tail.size
    }

    /**
     * What the dial shows mid-run: the mean of every sample since the grace
     * period, which is the first [graceFraction] of the [expected] samples a
     * phase will take. Before the grace period is over it is the mean of
     * everything so far, so the number climbs with TCP and then settles on
     * what [trimmedThroughput] will report once the phase ends.
     *
     * A running mean rather than the last few samples because an upload's
     * samples come in bursts: a write lands in the kernel's send buffer at
     * once and counts, then the next blocks until that drains, so the raw
     * 200 ms samples swing between many times the line's speed and nothing.
     */
    fun running(samples: List<Float>, expected: Int, graceFraction: Double = 0.2): Double {
        if (samples.isEmpty()) return 0.0
        val grace = (expected * graceFraction).toInt()
        val kept = if (samples.size > grace) samples.drop(grace) else samples
        return kept.sumOf { it.toDouble() } / kept.size
    }

    /**
     * [samples] with each value replaced by the mean of itself and the
     * [window] - 1 before it, for the live line: the bursts an upload's raw
     * samples come in draw as a saw otherwise. The first values average what
     * there is.
     */
    fun smooth(samples: List<Float>, window: Int = 3): List<Float> {
        if (window <= 1 || samples.size < 2) return samples
        val out = ArrayList<Float>(samples.size)
        var sum = 0.0
        for (i in samples.indices) {
            sum += samples[i]
            if (i >= window) sum -= samples[i - window]
            out += (sum / minOf(i + 1, window)).toFloat()
        }
        return out
    }

    /**
     * [samples] resampled into [buckets] values in 0..1 for a sparkline:
     * each bucket is the mean of the samples that fall in it, scaled to the
     * largest. Fewer samples than buckets are stretched, so a short run still
     * draws a full line; no samples draw a flat one.
     */
    fun sparkline(samples: List<Float>, buckets: Int = 24): List<Float> {
        if (buckets <= 0) return emptyList()
        if (samples.isEmpty()) return List(buckets) { 0f }
        val out = FloatArray(buckets)
        val counts = IntArray(buckets)
        for ((i, v) in samples.withIndex()) {
            val b = if (samples.size == 1) 0 else (i * (buckets - 1).toLong() / (samples.size - 1)).toInt()
            out[b] += v
            counts[b]++
        }
        // Buckets no sample landed in take the value before them.
        var last = 0f
        for (b in 0 until buckets) {
            if (counts[b] > 0) {
                out[b] /= counts[b]
                last = out[b]
            } else {
                out[b] = last
            }
        }
        val peak = out.max()
        return if (peak <= 0f) out.toList() else out.map { it / peak }
    }

    /**
     * How a speed is written: one decimal under ten, whole numbers above,
     * and nothing past the thousands a home line never reaches. [locale]
     * gives the digits and the decimal mark.
     */
    fun formatSpeed(mbps: Double, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String {
        val value = when (unit) {
            SpeedUnit.MBPS -> mbps
            SpeedUnit.MBYTES -> mbps / 8.0
        }
        return formatNumber(value, locale)
    }

    /** The unit's short name beside a number: `Mbps` or `MB/s`. */
    fun unitLabel(unit: SpeedUnit): String = when (unit) {
        SpeedUnit.MBPS -> "Mbps"
        SpeedUnit.MBYTES -> "MB/s"
    }

    /** Milliseconds, whole; a sub-millisecond ping still reads as 1. */
    fun formatMs(ms: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d", max(1, ms.roundToInt()))

    fun formatNumber(value: Double, locale: Locale = Locale.getDefault()): String = when {
        value < 0.05 -> String.format(locale, "%d", 0)
        value < 10.0 -> String.format(locale, "%.1f", value)
        else -> String.format(locale, "%d", value.roundToInt())
    }

    /**
     * What a run is likely to move, in megabytes, for the data note before
     * it starts: the last run's speeds over the configured time, or a guess
     * of 50 Mbps each way when there is none. Rounded to something a person
     * reads as a size, not a measurement.
     */
    fun estimatedDataMb(config: SpeedTestConfig, last: SpeedTestResult?): Int {
        val down = last?.downMbps?.takeIf { it > 0 } ?: 50.0
        val up = last?.upMbps?.takeIf { it > 0 } ?: down
        val megabytes = down / 8.0 * config.durationS + if (config.upload) up / 8.0 * config.durationS else 0.0
        return when {
            megabytes < 10 -> max(1, megabytes.roundToInt())
            megabytes < 100 -> (megabytes / 5).roundToInt() * 5
            else -> (megabytes / 10).roundToInt() * 10
        }
    }

    /**
     * The change from [previous] to [now] as a signed percentage, or null
     * when there is nothing to compare with. Rounded to whole percent.
     */
    fun deltaPercent(now: Double, previous: Double?): Int? {
        if (previous == null || previous <= 0.0) return null
        return ((now - previous) / previous * 100.0).roundToInt()
    }

    /**
     * Where a speed sits on a dial that reads to a thousand: a log scale,
     * so 1, 10, 100 and 1000 Mbps are evenly spaced and a 20 Mbps line is
     * not pinned to the bottom. 0..1.
     */
    fun gaugeFraction(mbps: Double): Float {
        if (mbps <= 1.0) return 0f
        val f = kotlin.math.log10(mbps) / 3.0
        return f.coerceIn(0.0, 1.0).toFloat()
    }

    /** One line of text for the field: the run in words, with the unit asked for. */
    fun summaryLine(result: SpeedTestResult, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String {
        val u = unitLabel(unit)
        val parts = buildList {
            add("↓ " + formatSpeed(result.downMbps, unit, locale) + " " + u)
            result.upMbps?.let { add("↑ " + formatSpeed(it, unit, locale) + " " + u) }
            add(formatMs(result.pingMs, locale) + " ms ping")
        }
        val server = listOf(result.serverName, result.serverCity).filter { it.isNotBlank() }.joinToString(" ")
        return parts.joinToString(" · ") + if (server.isNotBlank()) " ($server)" else ""
    }
}
