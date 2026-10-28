package com.wasimaster.wmkeyboard.core.prediction

/**
 * Sogou's `.scel` cell dictionaries (Telegram group): the format Sogou Pinyin
 * publishes its thousands of topic word lists in, and the source of the
 * Fcitx5 Sogou dictionaries people ask to bring over. Read into the plain
 * `word<TAB>pin'yin<TAB>count` lines a Chinese word list already imports as
 * (see `ConversionWordLists`), so a cell file needs no format of its own past
 * this.
 *
 * The layout, as the open-source converters document it: a 12-byte magic, a
 * syllable table at 0x1540 (a 4-byte count, then index, byte length and the
 * syllable in UTF-16LE per entry), and from 0x2628 the words, grouped by
 * reading: how many words share it, the reading as syllable indices, then for
 * each word its UTF-16LE text and an extension whose first two bytes are a
 * count.
 */
object SogouScel {

    private const val SYLLABLES_AT = 0x1540
    private const val WORDS_AT = 0x2628
    private const val MAGIC_LENGTH = 12

    /** The two magics in the wild: they differ only in byte 4. */
    fun looksLikeScel(head: ByteArray): Boolean {
        if (head.size < MAGIC_LENGTH) return false
        val tail = byteArrayOf(0x43, 0x53, 0x01, 0x01, 0x00, 0x00, 0x00)
        return head[0] == 0x40.toByte() && head[1] == 0x15.toByte() && head[2] == 0.toByte() &&
            head[3] == 0.toByte() && (head[4] == 0x44.toByte() || head[4] == 0x45.toByte()) &&
            head.copyOfRange(5, MAGIC_LENGTH).contentEquals(tail)
    }

    data class Entry(val word: String, val syllables: List<String>, val count: Int)

    /** Every word in [bytes]; empty for a file that is not one, or is cut short. */
    fun read(bytes: ByteArray): List<Entry> {
        if (!looksLikeScel(bytes) || bytes.size < WORDS_AT) return emptyList()
        val syllables = HashMap<Int, String>()
        var at = SYLLABLES_AT + 4
        while (at + 4 <= WORDS_AT) {
            val index = u16(bytes, at)
            val length = u16(bytes, at + 2)
            at += 4
            if (length == 0 || at + length > WORDS_AT) break
            syllables[index] = utf16(bytes, at, length)
            at += length
        }
        val out = ArrayList<Entry>()
        at = WORDS_AT
        while (at + 4 <= bytes.size) {
            val same = u16(bytes, at)
            val readingBytes = u16(bytes, at + 2)
            at += 4
            if (same == 0 || at + readingBytes > bytes.size) break
            val reading = (0 until readingBytes / 2).mapNotNull { syllables[u16(bytes, at + it * 2)] }
            at += readingBytes
            repeat(same) {
                if (at + 2 > bytes.size) return out
                val wordBytes = u16(bytes, at)
                at += 2
                if (at + wordBytes + 2 > bytes.size) return out
                val word = utf16(bytes, at, wordBytes)
                at += wordBytes
                val extBytes = u16(bytes, at)
                at += 2
                val count = if (extBytes >= 2 && at + 2 <= bytes.size) u16(bytes, at) else 0
                at += extBytes
                if (word.isNotBlank() && reading.isNotEmpty()) out.add(Entry(word, reading, count))
            }
        }
        return out
    }

    /** [read]'s words as the lines a Chinese word list imports as. */
    fun toText(bytes: ByteArray): String = buildString {
        for (entry in read(bytes)) {
            append(entry.word).append('\t').append(entry.syllables.joinToString("'"))
            append('\t').append(entry.count).append('\n')
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun utf16(bytes: ByteArray, at: Int, length: Int): String =
        String(bytes, at, length, Charsets.UTF_16LE)
}
