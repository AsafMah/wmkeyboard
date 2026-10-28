package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [LauncherFolder] helpers (#919): file, take out, rename, reorder, group, and the stored form. */
class LauncherFoldersTest {

    private val bank = "com.bank"
    private val pay = "com.pay"
    private val mail = "com.mail"
    private val workMail = "com.mail@10"

    @Test
    fun aNewFolderHoldsTheAppItWasMadeFrom() {
        val folders = emptyList<LauncherFolder>().withNewLauncherFolder("a1", "  Money ", bank)
        assertEquals(listOf(LauncherFolder("a1", "Money", listOf(bank))), folders)
    }

    @Test
    fun aBlankNameStillLabelsTheCell() {
        val folders = emptyList<LauncherFolder>().withNewLauncherFolder("a1", " \n ", bank)
        assertEquals("…", folders.single().name)
    }

    @Test
    fun ids_skipTheOnesInUse() {
        val folders = listOf(LauncherFolder("a1", "x", listOf(bank)), LauncherFolder("a3", "y", listOf(pay)))
        assertEquals("a2", folders.nextLauncherFolderId())
        assertEquals("a1", emptyList<LauncherFolder>().nextLauncherFolderId())
    }

    @Test
    fun anAppIsInOneFolderAtMost() {
        val start = listOf(
            LauncherFolder("a1", "Money", listOf(bank, pay)),
            LauncherFolder("a2", "Mail", listOf(mail)),
        )
        val moved = start.withLauncherAppIn(pay, "a2")
        assertEquals(
            listOf(LauncherFolder("a1", "Money", listOf(bank)), LauncherFolder("a2", "Mail", listOf(mail, pay))),
            moved,
        )
        assertEquals("a2", moved.launcherFolderOf(pay)?.id)
        // Filing an app where it already is, or into a folder that is gone, changes nothing.
        assertSame(start, start.withLauncherAppIn(bank, "a1"))
        assertSame(start, start.withLauncherAppIn(bank, "a9"))
    }

    @Test
    fun aNewFolderTakesTheAppOutOfItsOldOne() {
        val start = listOf(LauncherFolder("a1", "Money", listOf(bank)))
        val folders = start.withNewLauncherFolder("a2", "Bank", bank)
        // The old folder emptied and went; the new one has the app.
        assertEquals(listOf(LauncherFolder("a2", "Bank", listOf(bank))), folders)
    }

    @Test
    fun takingTheLastAppOutRemovesTheFolder() {
        val start = listOf(LauncherFolder("a1", "Money", listOf(bank, pay)))
        val one = start.withoutLauncherApp(bank)
        assertEquals(listOf(LauncherFolder("a1", "Money", listOf(pay))), one)
        assertEquals(emptyList<LauncherFolder>(), one.withoutLauncherApp(pay))
        assertNull(one.withoutLauncherApp(pay).launcherFolderOf(pay))
    }

    @Test
    fun renameTrimsAndIgnoresBlank() {
        val start = listOf(LauncherFolder("a1", "Money", listOf(bank)))
        assertEquals("Banks", start.renamedLauncherFolder("a1", " Banks\n").single().name)
        assertSame(start, start.renamedLauncherFolder("a1", "   "))
        val long = "x".repeat(LAUNCHER_FOLDER_NAME_MAX + 10)
        assertEquals(LAUNCHER_FOLDER_NAME_MAX, start.renamedLauncherFolder("a1", long).single().name.length)
    }

    @Test
    fun moveShiftsAFolderAndStopsAtTheEnds() {
        val a = LauncherFolder("a1", "A", listOf(bank))
        val b = LauncherFolder("a2", "B", listOf(pay))
        val c = LauncherFolder("a3", "C", listOf(mail))
        val start = listOf(a, b, c)
        assertEquals(listOf(b, a, c), start.movedLauncherFolder("a1", 1))
        assertEquals(listOf(a, c, b), start.movedLauncherFolder("a3", -1))
        assertEquals(listOf(b, c, a), start.movedLauncherFolder("a1", 5))
        assertSame(start, start.movedLauncherFolder("a1", -1))
        assertSame(start, start.movedLauncherFolder("nope", 1))
    }

    @Test
    fun deletingAFolderKeepsTheOthers() {
        val a = LauncherFolder("a1", "A", listOf(bank))
        val b = LauncherFolder("a2", "B", listOf(pay))
        assertEquals(listOf(b), listOf(a, b).withoutLauncherFolder("a1"))
    }

    @Test
    fun groupingLeavesHandFiledAppsAlone() {
        val start = listOf(LauncherFolder("a1", "Mine", listOf(bank)))
        val grouped = start.withGroupedLauncherApps(
            linkedMapOf("Finance" to listOf(bank, pay, "com.coin"), "Email" to listOf(mail, workMail)),
        )
        assertEquals(
            listOf(
                LauncherFolder("a1", "Mine", listOf(bank)),
                LauncherFolder("a2", "Finance", listOf(pay, "com.coin")),
                LauncherFolder("a3", "Email", listOf(mail, workMail)),
            ),
            grouped,
        )
    }

    @Test
    fun groupingJoinsAFolderOfTheSameNameAndSkipsGroupsOfOne() {
        val start = listOf(LauncherFolder("a1", "games", listOf("com.chess")))
        val grouped = start.withGroupedLauncherApps(
            linkedMapOf("Games" to listOf("com.go"), "Maps" to listOf("com.maps")),
        )
        assertEquals(listOf(LauncherFolder("a1", "games", listOf("com.chess", "com.go"))), grouped)
    }

    @Test
    fun theStoredFormRoundTrips() {
        val folders = listOf(
            LauncherFolder("a1", "Money \"and\" more", listOf(bank, pay)),
            LauncherFolder("a2", "Work", listOf(workMail)),
        )
        assertEquals(folders, decodeLauncherFolders(encodeLauncherFolders(folders)))
        assertNull(encodeLauncherFolders(emptyList()))
    }

    @Test
    fun decodingDropsWhatCannotBeAFolder() {
        val raw = """[
            {"id":"a1","name":"A","apps":["com.bank","com.pay"]},
            {"id":"a1","name":"Again","apps":["com.mail"]},
            {"id":"a2","name":"B","apps":["com.pay"]},
            {"name":"No id","apps":["com.x"]},
            {"id":"a3","name":"C","apps":["com.mail","com.mail",""]}
        ]"""
        assertEquals(
            listOf(LauncherFolder("a1", "A", listOf(bank, pay)), LauncherFolder("a3", "C", listOf(mail))),
            decodeLauncherFolders(raw),
        )
        assertEquals(emptyList<LauncherFolder>(), decodeLauncherFolders("not json"))
        assertEquals(emptyList<LauncherFolder>(), decodeLauncherFolders(null))
    }
}
