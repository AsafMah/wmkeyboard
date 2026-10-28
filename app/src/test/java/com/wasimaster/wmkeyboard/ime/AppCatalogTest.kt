package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.LauncherFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The app-launcher catalog's pure halves: query filtering, grid ordering,
 * work-profile keys (#924) and the folder grid (#919).
 * The PackageManager-facing loaders need a device; [LauncherApp] stores plain
 * strings (never a ComponentName) precisely so these tests can run on the JVM.
 */
class AppCatalogTest {

    private fun app(pkg: String, label: String) =
        LauncherApp(packageName = pkg, label = label, activityName = "$pkg.Main")

    private val apps = listOf(
        app("com.example.mail", "Mail"),
        app("com.example.maps", "Maps"),
        app("org.chat.app", "Chatter"),
        app("com.bank.two", "Two Bank"),
    )

    // ---- filtering ----------------------------------------------------

    @Test
    fun `an empty query returns the list untouched`() {
        assertSame(apps, AppCatalog.filterApps(apps, ""))
        assertSame(apps, AppCatalog.filterApps(apps, "   "))
    }

    @Test
    fun `filtering matches labels case-insensitively`() {
        assertEquals(
            listOf("Mail", "Maps"),
            AppCatalog.filterApps(apps, "mA").map { it.label },
        )
    }

    @Test
    fun `filtering matches package names too`() {
        assertEquals(
            listOf("Chatter"),
            AppCatalog.filterApps(apps, "org.chat").map { it.label },
        )
    }

    // ---- ordering -----------------------------------------------------

    @Test
    fun `pinned apps lead in pin order, the rest keep alphabetical order`() {
        val sorted = AppCatalog.sortApps(
            apps,
            recentFirst = false,
            pinned = listOf("com.bank.two", "com.example.mail"),
            recents = emptyList(),
        )
        assertEquals(
            listOf("Two Bank", "Mail", "Maps", "Chatter"),
            sorted.map { it.label },
        )
    }

    @Test
    fun `recent-first ranks by recency and falls back to the label`() {
        val sorted = AppCatalog.sortApps(
            apps,
            recentFirst = true,
            pinned = emptyList(),
            recents = listOf("org.chat.app", "com.example.maps"),
        )
        assertEquals(
            listOf("Chatter", "Maps", "Mail", "Two Bank"),
            sorted.map { it.label },
        )
    }

    @Test
    fun `a pinned app never repeats in the recent ordering`() {
        val sorted = AppCatalog.sortApps(
            apps,
            recentFirst = true,
            pinned = listOf("org.chat.app"),
            recents = listOf("org.chat.app", "com.example.mail"),
        )
        assertEquals(
            listOf("Chatter", "Mail", "Maps", "Two Bank"),
            sorted.map { it.label },
        )
        assertEquals(sorted.size, sorted.distinctBy { it.packageName }.size)
    }

    @Test
    fun `a pin for an uninstalled package is skipped`() {
        val sorted = AppCatalog.sortApps(
            apps,
            recentFirst = false,
            pinned = listOf("gone.package", "com.example.maps"),
            recents = emptyList(),
        )
        assertEquals(
            listOf("Maps", "Mail", "Chatter", "Two Bank"),
            sorted.map { it.label },
        )
    }

    // ---- other profiles (#924) ------------------------------------------

    private val workMail = LauncherApp("com.example.mail", "Mail", "com.example.mail.Main", profile = 10)

    @Test
    fun `an app of the keyboard's own profile keys by package, a work app by package and profile`() {
        assertEquals("com.example.mail", apps[0].key)
        assertEquals("com.example.mail@10", workMail.key)
    }

    @Test
    fun `a work app pins apart from its personal twin`() {
        val sorted = AppCatalog.sortApps(
            apps + workMail,
            recentFirst = false,
            pinned = listOf("com.example.mail@10"),
            recents = emptyList(),
        )
        assertSame(workMail, sorted.first())
        assertEquals(5, sorted.size)
        assertEquals(1, sorted.count { it.key == "com.example.mail" })
    }

    // ---- folders (#919) -------------------------------------------------

    @Test
    fun `without folders every app is a cell of its own`() {
        val cells = AppCatalog.arrangeGrid(apps, emptyList())
        assertEquals(apps, cells.map { (it as LauncherCell.App).app })
    }

    @Test
    fun `folders lead the grid and keep their own order`() {
        val folders = listOf(
            LauncherFolder("a1", "Mail", listOf("com.example.mail@10", "com.example.mail")),
            LauncherFolder("a2", "Gone", listOf("uninstalled.app")),
            LauncherFolder("a3", "Money", listOf("com.bank.two")),
        )
        val cells = AppCatalog.arrangeGrid(apps + workMail, folders)
        assertEquals(listOf("folder:a1", "folder:a3", "com.example.maps", "org.chat.app"), cells.map { it.key })
        val mail = cells[0] as LauncherCell.Folder
        assertEquals(listOf("com.example.mail@10", "com.example.mail"), mail.apps.map { it.key })
    }

    @Test
    fun `an app filed twice shows in the first folder only`() {
        val folders = listOf(
            LauncherFolder("a1", "One", listOf("com.example.maps")),
            LauncherFolder("a2", "Two", listOf("com.example.maps", "org.chat.app")),
        )
        val cells = AppCatalog.arrangeGrid(apps, folders)
        assertEquals(listOf("com.example.maps"), (cells[0] as LauncherCell.Folder).apps.map { it.key })
        assertEquals(listOf("org.chat.app"), (cells[1] as LauncherCell.Folder).apps.map { it.key })
    }

    @Test
    fun `category groups name by title and skip apps without one`() {
        val catalog = listOf(
            LauncherApp("g.one", "Chess", "g.one.Main", category = 0),
            LauncherApp("s.one", "Chat", "s.one.Main", category = 4),
            LauncherApp("g.two", "Go", "g.two.Main", category = 0, profile = 10),
            LauncherApp("n.one", "Plain", "n.one.Main"),
            LauncherApp("u.one", "Odd", "u.one.Main", category = 99),
        )
        val titles = mapOf(0 to "Games", 4 to "Social")
        val groups = AppCatalog.categoryGroups(catalog) { titles[it] }
        assertEquals(listOf("Games", "Social"), groups.keys.toList())
        assertEquals(listOf("g.one", "g.two@10"), groups["Games"])
        assertEquals(listOf("s.one"), groups["Social"])
    }
}
