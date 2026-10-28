package com.wasimaster.wmkeyboard.core.tools.speedtest

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestMathTest {

    @Test
    fun mbpsIsBitsPerSecondInMillions() {
        // 1,250,000 bytes in one second = 10,000,000 bits = 10 Mbps.
        assertEquals(10.0, SpeedTestMath.mbps(1_250_000, 1000), 1e-9)
        // Half the time, twice the speed.
        assertEquals(20.0, SpeedTestMath.mbps(1_250_000, 500), 1e-9)
        assertEquals(0.0, SpeedTestMath.mbps(1_250_000, 0), 0.0)
        assertEquals(0.0, SpeedTestMath.mbps(0, 1000), 0.0)
    }

    @Test
    fun medianOddAndEven() {
        assertEquals(0.0, SpeedTestMath.median(emptyList()), 0.0)
        assertEquals(18.0, SpeedTestMath.median(listOf(30.0, 18.0, 12.0)), 0.0)
        assertEquals(15.0, SpeedTestMath.median(listOf(30.0, 18.0, 12.0, 9.0)), 0.0)
    }

    @Test
    fun jitterIsMeanAbsoluteStep() {
        assertEquals(0.0, SpeedTestMath.jitter(listOf(20.0)), 0.0)
        // Steps of 5, 5 and 10 → mean 20/3.
        assertEquals(20.0 / 3.0, SpeedTestMath.jitter(listOf(10.0, 15.0, 20.0, 10.0)), 1e-9)
    }

    @Test
    fun trimmedThroughputDropsTheSlowStart() {
        // Ten samples: two slow ones first, then eight at 100.
        val samples = listOf(10f, 20f) + List(8) { 100f }
        assertEquals(100.0, SpeedTestMath.trimmedThroughput(samples), 1e-6)
        // Too few to trim: everything counts.
        assertEquals(15.0, SpeedTestMath.trimmedThroughput(listOf(10f, 20f)), 1e-6)
        assertEquals(0.0, SpeedTestMath.trimmedThroughput(emptyList()), 0.0)
    }

    @Test
    fun currentIsTheLastFewSamples() {
        assertEquals(30.0, SpeedTestMath.current(listOf(1f, 1f, 20f, 30f, 40f)), 1e-6)
        assertEquals(0.0, SpeedTestMath.current(emptyList()), 0.0)
    }

    @Test
    fun runningMeanSkipsTheGraceOnceItIsOver() {
        // A 10 s phase is 50 samples; the grace is the first 10.
        val slowStart = List(10) { 5f }
        // Inside the grace: the mean of everything so far, so the number climbs.
        assertEquals(5.0, SpeedTestMath.running(slowStart, 50), 1e-6)
        // Past it: the slow start no longer counts, bursts average out.
        val bursts = listOf(40f, 0f, 30f, 0f, 20f, 0f, 40f, 0f, 30f, 0f)
        assertEquals(16.0, SpeedTestMath.running(slowStart + bursts, 50), 1e-6)
        // Once the phase is over it is what the headline reports.
        val whole = slowStart + List(40) { 16f }
        assertEquals(SpeedTestMath.trimmedThroughput(whole), SpeedTestMath.running(whole, 50), 1e-6)
        assertEquals(0.0, SpeedTestMath.running(emptyList(), 50), 0.0)
    }

    @Test
    fun smoothIsATrailingMean() {
        assertEquals(listOf(30f, 15f, 20f, 10f, 20f), SpeedTestMath.smooth(listOf(30f, 0f, 30f, 0f, 30f), 3))
        // A window of one, or nothing to smooth, is the input itself.
        assertEquals(listOf(30f, 0f), SpeedTestMath.smooth(listOf(30f, 0f), 1))
        assertEquals(listOf(30f), SpeedTestMath.smooth(listOf(30f), 3))
    }

    @Test
    fun sparklineHasTheBucketsAskedForAndPeaksAtOne() {
        val line = SpeedTestMath.sparkline(List(50) { it.toFloat() }, 24)
        assertEquals(24, line.size)
        assertEquals(1f, line.max(), 1e-6f)
        // The first bucket holds the first three samples, so it is near zero, not zero.
        assertTrue(line.first() < 0.1f)
        assertTrue(line.zipWithNext().all { (a, b) -> b >= a })
        // Fewer samples than buckets still fill every bucket.
        val stretched = SpeedTestMath.sparkline(listOf(1f, 2f), 8)
        assertEquals(8, stretched.size)
        assertEquals(1f, stretched.last(), 1e-6f)
        assertTrue(stretched.none { it.isNaN() })
        // Nothing sampled draws a flat line, not a crash.
        assertEquals(List(5) { 0f }, SpeedTestMath.sparkline(emptyList(), 5))
    }

    @Test
    fun speedsFormatWithOneDecimalUnderTen() {
        assertEquals("9.4", SpeedTestMath.formatSpeed(9.44, SpeedUnit.MBPS, Locale.US))
        assertEquals("94", SpeedTestMath.formatSpeed(94.4, SpeedUnit.MBPS, Locale.US))
        assertEquals("0", SpeedTestMath.formatSpeed(0.01, SpeedUnit.MBPS, Locale.US))
        // 80 Mbps is 10 MB/s.
        assertEquals("10", SpeedTestMath.formatSpeed(80.0, SpeedUnit.MBYTES, Locale.US))
        assertEquals("Mbps", SpeedTestMath.unitLabel(SpeedUnit.MBPS))
        assertEquals("MB/s", SpeedTestMath.unitLabel(SpeedUnit.MBYTES))
        assertEquals("1", SpeedTestMath.formatMs(0.3, Locale.US))
        assertEquals("18", SpeedTestMath.formatMs(17.6, Locale.US))
    }

    @Test
    fun dataEstimateFollowsTheLastRunAndRounds() {
        val config = SpeedTestConfig(durationS = 10, upload = true)
        // No history: 50 Mbps each way for 10 s = 62.5 MB + 62.5 MB = 125 → nearest 10.
        assertEquals(130, SpeedTestMath.estimatedDataMb(config, null))
        val last = SpeedTestResult(downMbps = 800.0, upMbps = 80.0, pingMs = 10.0, jitterMs = 1.0)
        // 1000 MB + 100 MB.
        assertEquals(1100, SpeedTestMath.estimatedDataMb(config, last))
        // Upload off: download alone.
        assertEquals(1000, SpeedTestMath.estimatedDataMb(config.copy(upload = false), last))
        // A slow line: 2 Mbps × 10 s = 2.5 MB down, 2.5 up → 5.
        assertEquals(5, SpeedTestMath.estimatedDataMb(config, last.copy(downMbps = 2.0, upMbps = 2.0)))
    }

    @Test
    fun deltaAndGauge() {
        assertNull(SpeedTestMath.deltaPercent(100.0, null))
        assertNull(SpeedTestMath.deltaPercent(100.0, 0.0))
        assertEquals(25, SpeedTestMath.deltaPercent(125.0, 100.0))
        assertEquals(-20, SpeedTestMath.deltaPercent(80.0, 100.0))
        assertEquals(0f, SpeedTestMath.gaugeFraction(0.5), 0f)
        assertEquals(1f / 3f, SpeedTestMath.gaugeFraction(10.0), 1e-6f)
        assertEquals(2f / 3f, SpeedTestMath.gaugeFraction(100.0), 1e-6f)
        assertEquals(1f, SpeedTestMath.gaugeFraction(5000.0), 0f)
    }

    @Test
    fun summaryLineNamesEverything() {
        val result = SpeedTestResult(
            downMbps = 94.2, upMbps = 31.0, pingMs = 17.6, jitterMs = 2.0,
            serverName = "Cloudflare", serverCity = "Dhaka",
        )
        assertEquals("↓ 94 Mbps · ↑ 31 Mbps · 18 ms ping (Cloudflare Dhaka)", SpeedTestMath.summaryLine(result, SpeedUnit.MBPS, Locale.US))
        // No upload phase, no server: nothing left dangling.
        assertEquals(
            "↓ 12 MB/s · 18 ms ping",
            SpeedTestMath.summaryLine(result.copy(upMbps = null, downMbps = 96.0, serverName = "", serverCity = ""), SpeedUnit.MBYTES, Locale.US),
        )
    }

    @Test
    fun resultRoundTripsThroughJson() {
        val result = SpeedTestResult(
            downMbps = 94.2, upMbps = null, pingMs = 17.6, jitterMs = 2.0,
            downSamples = listOf(1f, 2f, 3f), isp = "Vision Technologies Ltd.", ip = "203.0.113.9",
            serverName = "Cloudflare", serverCity = "Dhaka", at = 1_792_950_000_000L, bytesMoved = 123_456L,
        )
        assertEquals(result, SpeedTestResult.decode(result.encode()))
        assertNull(SpeedTestResult.decode(""))
        assertNull(SpeedTestResult.decode("not json"))
        assertNull(SpeedTestResult.decode(null))
    }
}
