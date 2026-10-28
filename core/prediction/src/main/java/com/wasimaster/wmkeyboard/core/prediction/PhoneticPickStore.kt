package com.wasimaster.wmkeyboard.core.prediction

import java.io.File

/**
 * The reading the user picked from a phonetic layout's list for each spelling
 * they typed, as desktop Avro keeps its `candidate-selections.json`: type
 * `kotha` and pick কথা once, and from then on কথা leads the list and is what a
 * space commits (Telegram group). Read by [SuggestionEngine.phoneticPicks].
 *
 * One line per pick, `language<TAB>typed<TAB>reading`, in
 * `learning/phonetic_picks.tsv`. A plain file because it is small and written
 * rarely: a pick from the list is a deliberate tap, not a keystroke. Capped at
 * [MAX] spellings, the least recently picked dropped first. A null [file]
 * (direct boot) keeps the picks in memory only.
 */
class PhoneticPickStore(private val file: File?) {

    private val picks = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX
    }

    init {
        load()
    }

    /** Every pick, keyed as [phoneticPickKey] files them. */
    @Synchronized
    fun snapshot(): Map<String, String> = HashMap(picks)

    /**
     * Remembers [reading] for [typed] on [languageId]. True when that changed
     * anything, which is when the caller should hand the engine a new
     * [snapshot] and [save].
     */
    @Synchronized
    fun put(languageId: String, typed: String, reading: String): Boolean {
        if (typed.isEmpty() || reading.isEmpty() || '\t' in typed || '\n' in typed ||
            '\t' in reading || '\n' in reading
        ) {
            return false
        }
        return picks.put(phoneticPickKey(languageId, typed), reading) != reading
    }

    /** Forgets every pick, for the settings app's delete-learned-data. */
    @Synchronized
    fun clear() {
        picks.clear()
        file?.delete()
    }

    fun save() {
        val target = file ?: return
        val text = synchronized(this) {
            buildString {
                for ((key, reading) in picks) {
                    val language = key.substringBefore('\u0000')
                    val typed = key.substringAfter('\u0000')
                    append(language).append('\t').append(typed).append('\t').append(reading).append('\n')
                }
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeText(text)
            if (!part.renameTo(target)) {
                target.writeText(text)
                part.delete()
            }
        }
    }

    @Synchronized
    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        runCatching {
            source.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 3 && parts[1].isNotEmpty() && parts[2].isNotEmpty()) {
                    picks[phoneticPickKey(parts[0], parts[1])] = parts[2]
                }
            }
        }
    }

    companion object {
        /** Spellings remembered at most. */
        const val MAX = 4_000
    }
}
