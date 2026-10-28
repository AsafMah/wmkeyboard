package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPauseTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_761_400_000_000L

    @Test
    fun `nothing stored is not paused`() {
        assertFalse(BackupPause.isPaused(0L, now))
        assertFalse(AutoBackupSettings().paused(now))
        assertFalse(SyncSettings().paused(now))
    }

    @Test
    fun `a timed pause holds until its end and not a moment after`() {
        for (hours in listOf(1, 24, 24 * 7)) {
            val until = BackupPause.until(now, hours)
            assertEquals(now + hours * hour, until)
            assertTrue("$hours", BackupPause.isPaused(until, now))
            assertTrue("$hours", BackupPause.isPaused(until, until - 1))
            assertFalse("$hours", BackupPause.isPaused(until, until))
        }
    }

    @Test
    fun `until resumed holds forever`() {
        assertEquals(BackupPause.FOREVER, BackupPause.until(now, null))
        assertEquals(BackupPause.FOREVER, BackupPause.until(now, 0))
        // Longer than any choice is not a timed pause the clock check would drop.
        assertEquals(BackupPause.FOREVER, BackupPause.until(now, BackupPause.MAX_HOURS + 1))
        assertTrue(BackupPause.isPaused(BackupPause.FOREVER, now))
        assertTrue(AutoBackupSettings(pausedUntilMs = BackupPause.FOREVER).paused(Long.MAX_VALUE - 1))
    }

    @Test
    fun `a timed pause further off than a week is a clock that moved`() {
        // Set for an hour, then the clock went back a month.
        val until = BackupPause.until(now, 1)
        assertFalse(BackupPause.isPaused(until, now - 30L * 24L * hour))
        // A week from now is still the week the user picked.
        assertTrue(BackupPause.isPaused(BackupPause.until(now, BackupPause.MAX_HOURS), now))
    }

    @Test
    fun `every choice is a pause`() {
        assertEquals(listOf(1, 24, 24 * 7, null), BackupPause.CHOICES)
        for (hours in BackupPause.CHOICES) {
            assertTrue("$hours", BackupPause.isPaused(BackupPause.until(now, hours), now + 1))
        }
    }

    @Test
    fun `a pause and a sync group stay on this phone`() {
        for (key in listOf(
            SettingsBackup.AUTO_BACKUP_PAUSED_UNTIL,
            SettingsBackup.SYNC_PAUSED_UNTIL,
            SettingsBackup.SYNC_GROUP,
        )) {
            assertTrue(key, key in SettingsBackup.TRANSIENT_KEYS)
            assertFalse(key, SyncKeys.syncable(key, includeSecrets = true))
        }
    }
}
