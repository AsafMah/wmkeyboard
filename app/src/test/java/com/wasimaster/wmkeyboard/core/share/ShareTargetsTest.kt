package com.wasimaster.wmkeyboard.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTargetsTest {

    private val drive = ShareTarget("com.google.android.apps.docs", "UploadActivity", "Save to Drive", "Drive")
    private val gmail = ShareTarget("com.google.android.gm", "ComposeActivity", "Gmail")
    private val signal = ShareTarget("org.thoughtcrime.securesms", "ShareActivity", "Signal")
    private val telegram = ShareTarget("org.telegram.messenger", "LaunchActivity", "Telegram")
    private val all = listOf(telegram, signal, gmail, drive)

    @Test
    fun `the grid is by name and the row is pinned then recent, each once`() {
        val layout = ShareTargets.arrange(all, pinned = listOf(signal.key), recents = listOf(gmail.key, signal.key))
        assertEquals(listOf(signal, gmail), layout.shortcuts)
        assertEquals(listOf(gmail, drive, signal, telegram), layout.all)
    }

    @Test
    fun `a pinned or recent app that is gone drops out of the row`() {
        val layout = ShareTargets.arrange(all, pinned = listOf("gone/Activity"), recents = listOf(telegram.key))
        assertEquals(listOf(telegram), layout.shortcuts)
    }

    @Test
    fun `a search drops the row and puts names that start with it first`() {
        val layout = ShareTargets.arrange(all, pinned = listOf(signal.key), recents = emptyList(), query = " g ")
        assertEquals(emptyList<ShareTarget>(), layout.shortcuts)
        // Gmail starts with g; Telegram and Signal only contain it, and so
        // does the Drive target's package, which is searched too.
        assertEquals(listOf(gmail, drive, signal, telegram), layout.all)
    }

    @Test
    fun `a search reads the app's name as well as the target's`() {
        assertEquals(listOf(drive), ShareTargets.filter(all, "drive"))
        assertEquals(listOf(drive), ShareTargets.filter(all, "DRI"))
    }

    @Test
    fun `recents are newest first, once each, and capped`() {
        val many = (1..20).map { "p$it/A" }
        val next = ShareTargets.recordRecent(many, "p5/A")
        assertEquals("p5/A", next.first())
        assertEquals(ShareTargets.MAX_RECENTS, next.size)
        assertEquals(1, next.count { it == "p5/A" })
    }

    @Test
    fun `a pin toggles`() {
        val once = ShareTargets.togglePin(emptyList(), gmail.key)
        assertEquals(listOf(gmail.key), once)
        assertEquals(emptyList<String>(), ShareTargets.togglePin(once, gmail.key))
    }

    @Test
    fun `open offers itself for a lone link only`() {
        assertEquals("https://example.com/a?b=c", ShareTargets.linkIn("  https://example.com/a?b=c\n"))
        assertNull(ShareTargets.linkIn("look at https://example.com"))
        assertNull(ShareTargets.linkIn("example.com"))
        assertNull(ShareTargets.linkIn(null))
    }
}
