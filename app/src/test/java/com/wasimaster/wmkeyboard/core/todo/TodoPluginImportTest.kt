package com.wasimaster.wmkeyboard.core.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPluginImportTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `reads text, tick and due date in the plugin's order`() {
        val items = """[
            {"id":1,"text":"Milk","done":false,"due":1700000500,"remind":true},
            {"id":2,"text":"Bread","done":true},
            {"id":3,"text":"  "},
            {"text":"No id"}
        ]"""
        val tasks = TodoPluginImport.parse(items, now)
        assertEquals(listOf("Milk", "Bread", "No id"), tasks.map { it.text })
        assertEquals(1_700_000_500_000L, tasks[0].due)
        assertTrue(tasks[1].done)
        assertNull(tasks[2].due)
        assertTrue(tasks[0].createdAt < tasks[1].createdAt)
    }

    @Test
    fun `anything unreadable is an empty list`() {
        assertTrue(TodoPluginImport.parse(null).isEmpty())
        assertTrue(TodoPluginImport.parse("not json").isEmpty())
        assertTrue(TodoPluginImport.parse("""{"items":[]}""").isEmpty())
    }

    @Test
    fun `a second import adds only what is missing`() {
        val parsed = TodoPluginImport.parse("""[{"text":"Milk"},{"text":"Eggs"}]""", now)
        val existing = listOf(TodoTask(id = 1, text = "milk"))
        assertEquals(listOf("Eggs"), TodoPluginImport.missing(parsed, existing).map { it.text })
    }
}
