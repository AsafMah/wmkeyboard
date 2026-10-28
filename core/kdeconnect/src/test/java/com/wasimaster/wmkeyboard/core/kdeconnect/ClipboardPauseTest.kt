package com.wasimaster.wmkeyboard.core.kdeconnect

import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClipboardPause.ALL
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClipboardPause.FOREVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule behind pausing clipboard sharing (#918). */
class ClipboardPauseTest {

    private val now = 1_000_000L

    @Test
    fun `nothing paused shares with everyone`() {
        assertFalse(KdeClipboardPause.isPaused(emptyMap(), "pc", now))
        assertEquals(0L, KdeClipboardPause.pausedUntil(emptyMap(), "pc", now))
    }

    @Test
    fun `a device pause holds only that device`() {
        val pauses = KdeClipboardPause.pause(emptyMap(), "pc", now + 60_000, now)
        assertTrue(KdeClipboardPause.isPaused(pauses, "pc", now))
        assertFalse(KdeClipboardPause.isPaused(pauses, "laptop", now))
    }

    @Test
    fun `a pause on all holds every device`() {
        val pauses = KdeClipboardPause.pause(emptyMap(), null, now + 60_000, now)
        assertEquals(mapOf(ALL to now + 60_000), pauses)
        assertTrue(KdeClipboardPause.isPaused(pauses, "pc", now))
        assertTrue(KdeClipboardPause.isPaused(pauses, "laptop", now))
    }

    @Test
    fun `a timed pause lapses by itself`() {
        val until = KdeClipboardPause.endFor(15, now)
        assertEquals(now + 15 * 60_000L, until)
        val pauses = KdeClipboardPause.pause(emptyMap(), "pc", until, now)
        assertTrue(KdeClipboardPause.isPaused(pauses, "pc", until - 1))
        assertFalse(KdeClipboardPause.isPaused(pauses, "pc", until))
    }

    @Test
    fun `until resumed never lapses`() {
        assertEquals(FOREVER, KdeClipboardPause.endFor(0, now))
        val pauses = KdeClipboardPause.pause(emptyMap(), "pc", FOREVER, now)
        assertTrue(KdeClipboardPause.isPaused(pauses, "pc", Long.MAX_VALUE - 1))
        assertNull("nothing to wake up for", KdeClipboardPause.nextEnd(pauses, now))
    }

    @Test
    fun `the later of the device pause and the pause on all wins`() {
        var pauses = KdeClipboardPause.pause(emptyMap(), "pc", now + 10_000, now)
        pauses = KdeClipboardPause.pause(pauses, null, now + 5_000, now)
        assertEquals(now + 10_000, KdeClipboardPause.pausedUntil(pauses, "pc", now))
        assertEquals(now + 5_000, KdeClipboardPause.pausedUntil(pauses, "laptop", now))
        // Once the pause on all ends, the device's own still holds.
        assertTrue(KdeClipboardPause.isPaused(pauses, "pc", now + 6_000))
        assertFalse(KdeClipboardPause.isPaused(pauses, "laptop", now + 6_000))
        assertEquals(now + 5_000, KdeClipboardPause.nextEnd(pauses, now))
    }

    @Test
    fun `resuming one device leaves the rest and resuming all clears everything`() {
        var pauses = KdeClipboardPause.pause(emptyMap(), "pc", FOREVER, now)
        pauses = KdeClipboardPause.pause(pauses, "laptop", FOREVER, now)
        pauses = KdeClipboardPause.resume(pauses, "pc", now)
        assertFalse(KdeClipboardPause.isPaused(pauses, "pc", now))
        assertTrue(KdeClipboardPause.isPaused(pauses, "laptop", now))

        pauses = KdeClipboardPause.pause(pauses, null, FOREVER, now)
        assertTrue("held by the pause on all", KdeClipboardPause.isPaused(KdeClipboardPause.resume(pauses, "pc", now), "pc", now))
        assertEquals(emptyMap<String, Long>(), KdeClipboardPause.resume(pauses, null, now))
    }

    @Test
    fun `ended pauses are dropped as the map is rewritten`() {
        val stale = mapOf("old" to now - 1, "pc" to now + 1_000)
        assertEquals(mapOf("pc" to now + 1_000, "laptop" to FOREVER), KdeClipboardPause.pause(stale, "laptop", FOREVER, now))
    }
}
