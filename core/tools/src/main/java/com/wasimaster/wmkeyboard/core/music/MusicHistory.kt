package com.wasimaster.wmkeyboard.core.music

import android.content.Context
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One recognition the user kept: the song, and when it was heard. */
@Serializable
data class MusicHistoryEntry(
    val id: Long,
    val at: Long,
    val match: SongMatch,
)

@Serializable
data class MusicHistoryData(val entries: List<MusicHistoryEntry> = emptyList())

/**
 * The songs the tool has recognised, newest first, in one JSON file under
 * `filesDir/music/`. One per process, like `ClockStore`: the keyboard adds
 * to it and the settings app's history screen reads it, in the same process.
 *
 * A song heard again within [REPEAT_WINDOW_MS] of the last time is not a new
 * row: a panel left open through a chorus should not list the chorus thrice.
 */
class MusicHistory internal constructor(private val file: File?) {

    private val snapshot = file?.let(::SnapshotFile)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<MusicHistoryData> = _state

    val entries: List<MusicHistoryEntry> get() = _state.value.entries

    /** Adds [match] at [now], or refreshes the row it repeats. Returns the row. */
    @Synchronized
    fun add(match: SongMatch, now: Long = System.currentTimeMillis()): MusicHistoryEntry {
        val current = _state.value.entries
        val repeat = current.firstOrNull { it.match.identity == match.identity && now - it.at < REPEAT_WINDOW_MS }
        val entry = repeat?.copy(at = now, match = match) ?: MusicHistoryEntry(id = now, at = now, match = match)
        val rest = current.filterNot { it.id == entry.id }
        write(MusicHistoryData((listOf(entry) + rest).take(MAX_ENTRIES)))
        return entry
    }

    @Synchronized
    fun remove(id: Long) {
        write(MusicHistoryData(_state.value.entries.filterNot { it.id == id }))
    }

    @Synchronized
    fun clear() {
        write(MusicHistoryData())
    }

    /** Replaces everything, for a backup being restored. */
    @Synchronized
    fun replace(data: MusicHistoryData) {
        write(data.copy(entries = data.entries.sortedByDescending { it.at }.take(MAX_ENTRIES)))
    }

    private fun write(data: MusicHistoryData) {
        if (data == _state.value) return
        _state.value = data
        val ticket = snapshot?.ticket() ?: return
        if (data.entries.isEmpty()) {
            snapshot.delete()
        } else {
            snapshot.write(ticket) { json.encodeToString(MusicHistoryData.serializer(), data) }
        }
    }

    private fun load(): MusicHistoryData {
        val f = file ?: return MusicHistoryData()
        if (!f.exists()) return MusicHistoryData()
        return runCatching { json.decodeFromString(MusicHistoryData.serializer(), f.readText()) }
            .getOrElse { MusicHistoryData() }
    }

    companion object {
        const val DIR = "music"
        const val FILE_NAME = "history.json"
        const val MAX_ENTRIES = 200
        const val REPEAT_WINDOW_MS = 10 * 60 * 1000L

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        @Volatile
        private var instance: MusicHistory? = null

        fun get(context: Context): MusicHistory =
            instance ?: synchronized(this) {
                instance ?: MusicHistory(fileFor(context)).also { instance = it }
            }

        fun rootDir(files: File): File = File(files, DIR)

        internal fun fileFor(context: Context): File =
            File(rootDir(context.applicationContext.filesDir), FILE_NAME)

        /** For tests: a history with no file behind it. */
        fun inMemory(): MusicHistory = MusicHistory(null)
    }
}
