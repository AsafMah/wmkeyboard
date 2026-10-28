package com.wasimaster.wmkeyboard.core.todo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads the Todo List plugin's saved list (#920), so a user moving to the
 * built-in tool keeps what they had.
 *
 * The plugin keeps one `wm.storage` key, `items`: a JSON array of
 * `{id, text, done, due, remind}`, `due` in seconds. Text, tick and due date
 * come across; the plugin's reminders stay with the plugin, which still shows
 * them for as long as it is installed. Nothing here touches the plugin's
 * file: the import copies, and the plugin keeps working until the user
 * removes it.
 */
object TodoPluginImport {

    /** The add-on repository's Todo List and the template's default. */
    const val PLUGIN_ID = "com.wasimaster.todo"

    const val STORAGE_KEY = "items"

    private val json = Json { ignoreUnknownKeys = true }

    /** The tasks in the plugin's `items` value, oldest first; empty for anything unreadable. */
    fun parse(items: String?, now: Long = System.currentTimeMillis()): List<TodoTask> {
        if (items.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(items) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val text = (item["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val done = (item["done"] as? JsonPrimitive)?.booleanOrNull == true
            val dueSeconds = (item["due"] as? JsonPrimitive)?.longOrNull
            // The plugin's list has no creation times; keep its order by
            // spacing them a millisecond apart, oldest first.
            val created = now - (array.size - index)
            TodoTask(
                id = 0,
                text = text,
                done = done,
                due = dueSeconds?.takeIf { it > 0 }?.times(1000),
                createdAt = created,
                updatedAt = created,
                completedAt = if (done) created else null,
            )
        }
    }

    /**
     * [parsed] less the tasks [existing] already has (the same text, ignoring case),
     * so a second import, or one after a sync, does not double the list.
     */
    fun missing(parsed: List<TodoTask>, existing: List<TodoTask>): List<TodoTask> {
        val have = existing.filter { it.parentId == null }.mapTo(HashSet()) { it.text.lowercase() }
        return parsed.filter { it.text.lowercase() !in have }
    }
}
