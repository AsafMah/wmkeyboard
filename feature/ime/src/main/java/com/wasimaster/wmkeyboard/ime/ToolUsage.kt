package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How often and how lately each tool has run, for the toolbox's "Most used"
 * and "Recently used" orders ([com.wasimaster.wmkeyboard.core.settings.ToolboxSort]).
 * Persisted as JSON beside the emoji history, and kept the same way
 * ([com.wasimaster.wmkeyboard.core.emoji.EmojiUsage]): a dirty flag, and a save
 * on every dismissal that writes only when something ran.
 *
 * Tools are stored by name, so one a later build drops or renames is skipped
 * on load rather than failing the whole file.
 */
class ToolUsage(storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val recents: List<String> = emptyList(),
        val counts: Map<String, Int> = emptyMap(),
    )

    /** Every tool ever run, latest first. Bounded by the enum, so never trimmed. */
    private val recents = ArrayDeque<ToolbarTool>()
    private val counts = HashMap<ToolbarTool, Int>()
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    private fun load() {
        snapshotFile?.file?.takeIf { it.exists() }?.let { file ->
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                snapshot.recents.mapNotNullTo(recents, ::toolOf)
                for ((name, count) in snapshot.counts) {
                    toolOf(name)?.let { counts[it] = count }
                }
            }
        }
    }

    private fun toolOf(name: String): ToolbarTool? =
        runCatching { ToolbarTool.valueOf(name) }.getOrNull()

    @Synchronized
    fun record(tool: ToolbarTool) {
        recents.remove(tool)
        recents.addFirst(tool)
        counts.merge(tool, 1, Int::plus)
        dirty = true
    }

    /** The tools ever run, latest first. */
    @Synchronized
    fun recent(): List<ToolbarTool> = recents.toList()

    /** The tools ever run, most runs first; a tie goes to the one run last. */
    @Synchronized
    fun mostUsed(): List<ToolbarTool> {
        val lately = recents.withIndex().associate { it.value to it.index }
        return counts.keys.sortedWith(
            compareByDescending<ToolbarTool> { counts[it] ?: 0 }
                .thenBy { lately[it] ?: Int.MAX_VALUE },
        )
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                recents.map { it.name },
                counts.entries.associate { it.key.name to it.value },
            )
        }
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    companion object {
        const val FILE_PATH = "learning/tool_usage.json"
    }
}
