package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several people at one location: sync groups in the file names. */
class SyncGroupNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun entry(name: String, modifiedAtMs: Long = 0L) =
        SinkEntry(id = name, name = name, sizeBytes = 1, modifiedAtMs = modifiedAtMs)

    /** What a build from before groups matched a sync file's owner with. */
    private val oldSyncOwner = Regex("^" + Regex.escape("wmkeyboard-sync_") + "([0-9a-f]{8})")

    /** What a build from before groups parsed a backup's name with. */
    private val oldBackupShape = Regex(
        "^wmkeyboard-auto-(\\d{8}-\\d{6})(?:_([0-9a-f]{8})(?:_([A-Za-z0-9-]{1,32}))?)?\\.(wmconfig\\.json|wmconfig\\.enc)$",
    )

    @Test
    fun `no group is the name every file had before groups`() {
        assertEquals(
            "wmkeyboard-sync_78c415cf_OPPO-Reno8-T.wmsync.enc",
            SyncNaming.name("78c415cf", "OPPO Reno8 T", encrypted = true, group = ""),
        )
        assertEquals("", SyncNaming.group("wmkeyboard-sync_78c415cf_OPPO-Reno8-T.wmsync.enc"))
        assertEquals("", SyncNaming.group("wmkeyboard-sync_630c4870_OPPO-Reno8-T.wmsync (1).json"))
    }

    @Test
    fun `a grouped name says its group, owner and device`() {
        val name = SyncNaming.name("78c415cf", "Pixel 8", encrypted = false, group = "Family")
        assertEquals("wmkeyboard-sync-family_78c415cf_Pixel-8.wmsync.json", name)
        assertTrue(SyncNaming.isOurs(name))
        assertEquals("family", SyncNaming.group(name))
        assertEquals("78c415cf", SyncNaming.installId(name))
        assertEquals("Pixel-8", SyncNaming.device(name))
        // The provider's clash rename keeps it ours, so the pass still cleans it up.
        val clash = "wmkeyboard-sync-kids-tab_78c415cf_Pixel-8 (1).wmsync.json"
        assertEquals("kids-tab", SyncNaming.group(clash))
        assertEquals("78c415cf", SyncNaming.installId(clash))
    }

    @Test
    fun `an old build never reads a grouped file`() {
        val grouped = SyncNaming.name("78c415cf", "Pixel 8", encrypted = false, group = "alice")
        assertFalse(oldSyncOwner.containsMatchIn(grouped))
        assertTrue(oldSyncOwner.containsMatchIn(SyncNaming.name("78c415cf", "Pixel 8", encrypted = false)))
    }

    @Test
    fun `grouped files are listed and swept like the rest, and never taken for backups`() {
        val name = SyncNaming.name("78c415cf", "Pixel 8", encrypted = true, group = "alice")
        assertTrue(AutoBackupNaming.isListed(name))
        assertFalse(AutoBackupNaming.isOurs(name))
        assertTrue(AutoBackupNaming.isPart("$name${AutoBackupNaming.PART_SUFFIX}"))
        assertTrue(AutoBackupNaming.isPart("$name${AutoBackupNaming.UPLOAD_SUFFIX}"))
    }

    @Test
    fun `a typed group becomes a name every location accepts`() {
        assertEquals("mum-s-phone", SyncNaming.groupSlug("  Mum's phone! "))
        assertEquals("family", SyncNaming.groupSlug("FAMILY"))
        assertEquals("", SyncNaming.groupSlug("পরিবার"))
        assertEquals(SyncNaming.GROUP_MAX, SyncNaming.groupSlug("a".repeat(40)).length)
        // Cut where a hyphen would end it: no trailing hyphen.
        assertEquals("abcdefghijk", SyncNaming.groupSlug("abcdefghijk lmn"))
        // Nothing usable is no group, and the name is the ungrouped one.
        assertEquals(
            SyncNaming.name("78c415cf", "", encrypted = false),
            SyncNaming.name("78c415cf", "", encrypted = false, group = "!!!"),
        )
    }

    @Test
    fun `a pass reads only its own group`() {
        val entries = listOf(
            entry(SyncNaming.name("aaaaaaaa", "Mine", false, "alice"), 9),
            entry(SyncNaming.name("bbbbbbbb", "Tablet", false, "alice"), 1),
            entry(SyncNaming.name("bbbbbbbb", "Tablet", false, "alice").replace(".wmsync", " (1).wmsync"), 2),
            entry(SyncNaming.name("cccccccc", "Dad", false, "bob"), 50),
            entry(SyncNaming.name("dddddddd", "Old", false), 60),
        )
        val alice = SyncNaming.newestOfOthers(entries, me = "aaaaaaaa", inGroup = "alice")
        assertEquals(listOf(entries[2]), alice)
        // No group reads the ungrouped files only, as before groups existed.
        assertEquals(listOf(entries[4]), SyncNaming.newestOfOthers(entries, me = "aaaaaaaa", inGroup = ""))
        assertEquals(listOf(entries[3]), SyncNaming.newestOfOthers(entries, me = "aaaaaaaa", inGroup = "bob"))
    }

    @Test
    fun `the groups at a location leave this device out`() {
        val names = listOf(
            SyncNaming.name("aaaaaaaa", "Mine", false, "zed"),
            SyncNaming.name("bbbbbbbb", "Tablet", false, "alice"),
            SyncNaming.name("cccccccc", "Dad", false),
            "wmkeyboard-auto-20250807-140233_cccccccc_Dad.wmconfig.json",
            "notes.txt",
        )
        assertEquals(listOf("", "alice"), SyncNaming.groups(names, except = "aaaaaaaa"))
        assertEquals(listOf("", "alice", "zed"), SyncNaming.groups(names))
    }

    @Test
    fun `a paired computer is never in a group`() {
        val nearby = BackupLocation(id = "11111111", type = BackupDestination.NEARBY, nearbyDeviceId = "pc")
        val dropbox = BackupLocation(id = "22222222", type = BackupDestination.DROPBOX)
        assertEquals("", SyncRunner.groupAt(nearby, "alice"))
        assertEquals("alice", SyncRunner.groupAt(dropbox, "alice"))
    }

    @Test
    fun `a backup name carries the group where an old build still finds the owner`() {
        val name = AutoBackupNaming.name(
            1_754_575_353_000L,
            encrypted = false,
            zone = utc,
            installId = "a1b2c3d4",
            device = "Pixel 8",
            group = "Family",
        )
        assertEquals("wmkeyboard-auto-20250807-140233_a1b2c3d4_family--Pixel-8.wmconfig.json", name)
        val parsed = AutoBackupNaming.parse(name, utc)!!
        assertEquals("a1b2c3d4", parsed.installId)
        assertEquals("family", parsed.group)
        assertEquals("Pixel-8", parsed.device)
        // The safety property: an old build's rotation reads the owner, so it
        // never takes a grouped backup for an unowned one and deletes it.
        assertEquals("a1b2c3d4", oldBackupShape.matchEntire(name)!!.groupValues[2])
    }

    @Test
    fun `a long group and device still fit an old build's device part`() {
        val name = AutoBackupNaming.name(
            0L,
            encrypted = true,
            zone = utc,
            installId = "12345678",
            device = "A very long device name indeed, longer than any",
            group = "a".repeat(40),
        )
        val match = oldBackupShape.matchEntire(name)!!
        assertEquals("12345678", match.groupValues[2])
        assertTrue(match.groupValues[3].length <= 32)
        assertEquals("a".repeat(SyncNaming.GROUP_MAX), AutoBackupNaming.parse(name, utc)!!.group)
    }

    @Test
    fun `a group with no usable device name keeps the group`() {
        val name = AutoBackupNaming.name(0L, false, utc, installId = "0badf00d", device = "ওয়াসির ফোন", group = "kids")
        val parsed = AutoBackupNaming.parse(name, utc)!!
        assertEquals("kids", parsed.group)
        assertNull(parsed.device)
        assertEquals("0badf00d", oldBackupShape.matchEntire(name)!!.groupValues[2])
    }

    @Test
    fun `a backup with no group parses as no group`() {
        val name = AutoBackupNaming.name(0L, false, utc, installId = "0badf00d", device = "Pixel 8")
        assertEquals("wmkeyboard-auto-19700101-000000_0badf00d_Pixel-8.wmconfig.json", name)
        assertEquals("", AutoBackupNaming.parse(name, utc)!!.group)
        assertEquals("", AutoBackupNaming.parse("wmkeyboard-auto-20250807-140233.wmconfig.json", utc)!!.group)
    }

    @Test
    fun `rotation still counts by install, whatever the group`() {
        fun owned(id: String, group: String, at: Long) =
            entry(AutoBackupNaming.name(at * 1000, false, utc, installId = id, device = "Phone", group = group), at)
        val mine = listOf(owned("aaaaaaaa", "", 1), owned("aaaaaaaa", "alice", 2), owned("aaaaaaaa", "alice", 3))
        val theirs = listOf(owned("bbbbbbbb", "alice", 4), owned("bbbbbbbb", "bob", 5))
        val doomed = AutoBackupNaming.rotation(mine + theirs, keep = 1, installId = "aaaaaaaa")
        assertEquals(mine.take(2).map { it.name }, doomed.map { it.name })
    }
}
