package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shortcut given to a personal-dictionary word (#931). */
class DictionaryShortcutsTest {

    @Test
    fun `a shortcut becomes a suggesting snippet in its own folder`() {
        val store = SnippetStore(null)
        val added = DictionaryShortcuts.add(store, " asaik ", "as far as I know")!!
        assertEquals("asaik", added.trigger)
        assertEquals("as far as I know", added.text)
        assertTrue(added.confirm)
        val folder = store.folders().single { it.name == DictionaryShortcuts.FOLDER_NAME }
        assertEquals(folder.id, added.folderId)

        DictionaryShortcuts.add(store, "dnt", "don't")
        DictionaryShortcuts.add(store, "asaik", "as far as I know")
        assertEquals(1, store.folders().count { it.name == DictionaryShortcuts.FOLDER_NAME })
        assertEquals(2, store.items().size)
    }

    @Test
    fun `a shortcut that cannot be matched is refused`() {
        assertNull(DictionaryShortcuts.add(SnippetStore(null), "   ", "word"))
    }
}
