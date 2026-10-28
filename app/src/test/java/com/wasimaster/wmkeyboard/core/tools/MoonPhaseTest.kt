package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonPhaseTest {

    @Test
    fun `reference new moon`() {
        // 2000-01-06 18:14 UTC is the epoch: age zero, nothing lit.
        val info = MoonPhase.at(947_182_440_000L)
        assertEquals(0.0, info.ageDays, 1e-6)
        assertEquals(0.0, info.illumination, 1e-6)
        assertEquals(0, info.phaseIndex)
    }

    @Test
    fun `the main phases around a moment, a quarter month apart`() {
        val now = 947_182_440_000L + (MoonPhase.SYNODIC_DAYS * 0.6 * 86_400_000).toLong()
        val quarters = MoonPhase.quarters(now)
        assertEquals(listOf(4, 6, 0, 2), quarters.map { it.phaseIndex })
        assertTrue(quarters[0].atMillis <= now && quarters[1].atMillis > now)
        val quarterMs = MoonPhase.SYNODIC_DAYS / 4 * 86_400_000
        quarters.zipWithNext().forEach { (a, b) -> assertEquals(quarterMs, (b.atMillis - a.atMillis).toDouble(), 2.0) }
        // The phase [at] gives at each instant is the one it is listed as.
        quarters.forEach { assertEquals(it.phaseIndex, MoonPhase.at(it.atMillis + 1_000).phaseIndex) }
    }

    @Test
    fun `full moon half cycle later`() {
        val half = (MoonPhase.SYNODIC_DAYS / 2 * 86_400_000).toLong()
        val info = MoonPhase.at(947_182_440_000L + half)
        assertEquals(4, info.phaseIndex)
        assertTrue(info.illumination > 0.999)
    }

    @Test
    fun `next events are in the future and ordered sensibly`() {
        val now = 1_784_000_000_000L // an arbitrary 2026 instant
        val info = MoonPhase.at(now)
        assertTrue(info.nextNewMoonMillis > now)
        assertTrue(info.nextFullMoonMillis > now)
        val cycleMs = (MoonPhase.SYNODIC_DAYS * 86_400_000).toLong()
        assertTrue(info.nextNewMoonMillis - now <= cycleMs)
        assertTrue(info.nextFullMoonMillis - now <= cycleMs)
    }
}
