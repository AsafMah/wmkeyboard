package com.wasimaster.wmkeyboard.core.emoji.mix

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Which emoji pairs one source has a mix for, packed small enough to keep in
 * the keyboard's memory: 147,000 pairs over 619 emoji come to about 0.7 MB on
 * disk and a few MB loaded.
 *
 * Each pair is stored once, in the order the source draws it (a mix of 😃 and
 * 😁 exists as one of the two, and only that one's image is there to fetch).
 * [find] looks both orders up. Emoji are numbered in the source's own order,
 * which is the order [partners] lists them in.
 *
 * Addresses are rebuilt from [template] and each pair's date; a pair whose
 * address follows no pattern keeps its own in [urls]. An index from a list of
 * pairs alone has neither, and borrows its addresses from another source.
 */
class MixIndex internal constructor(
    val template: MixTemplate?,
    private val keyArray: Array<String>,
    private val dates: Array<String>,
    /** Entries sorted by (smaller emoji number, larger): [find]'s binary search. */
    private val lefts: CharArray,
    private val rights: CharArray,
    private val dateOf: ByteArray,
    private val urls: Array<String?>?,
) {
    /** Canonical key ([MixSeq.canonical]) → emoji number. */
    private val byCanonical: HashMap<String, Int> = HashMap<String, Int>(keyArray.size * 2).apply {
        keyArray.forEachIndexed { i, k -> putIfAbsent(MixSeq.canonical(k), i) }
    }

    /** Per emoji, its entries, in partner order; built the first time [partners] is asked. */
    private val adjacency: Pair<IntArray, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) { buildAdjacency() }

    /** One pair as the source has it: keys in the source's order, its date, and its own address if any. */
    data class Entry(val left: String, val right: String, val date: String, val url: String?)

    val pairCount: Int get() = lefts.size

    /** Whether the index knows its own images' addresses, rather than listing pairs alone. */
    val hasAddresses: Boolean get() = template != null || urls != null

    /** Every emoji the source mixes, as keys, in its order. */
    fun keys(): List<String> = keyArray.asList()

    /** Whether [emoji] (any spelling, toned or not) has mixes here. */
    fun supports(emoji: String): Boolean = numberOf(emoji) != null

    /** The source's own key for [emoji], or null when it has none. */
    fun keyOf(emoji: String): String? = numberOf(emoji)?.let { keyArray[it] }

    /** The pair of [a] and [b] in whichever order the source drew it, or null. */
    fun find(a: String, b: String): Entry? {
        val x = numberOf(a) ?: return null
        val y = numberOf(b) ?: return null
        val at = search(minOf(x, y), maxOf(x, y))
        return if (at < 0) null else entry(at)
    }

    /** Every mix [emoji] is in, as entries, in the order of the other emoji. */
    fun partners(emoji: String, limit: Int = Int.MAX_VALUE): List<Entry> {
        val n = numberOf(emoji) ?: return emptyList()
        val (offsets, list) = adjacency
        val from = offsets[n]
        val to = from + minOf(offsets[n + 1] - from, limit)
        return (from until to).map { entry(list[it]) }
    }

    private fun numberOf(emoji: String): Int? {
        val key = if (emoji.any { it.code > 0x7F }) MixSeq.key(emoji) else emoji
        return byCanonical[MixSeq.canonical(key)]
    }

    private fun entry(at: Int): Entry = Entry(
        left = keyArray[lefts[at].code],
        right = keyArray[rights[at].code],
        date = dates.getOrElse(dateOf[at].toInt() and 0xFF) { "" },
        url = urls?.get(at),
    )

    private fun search(lo: Int, hi: Int): Int {
        var low = 0
        var high = lefts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val a = minOf(lefts[mid].code, rights[mid].code)
            val b = maxOf(lefts[mid].code, rights[mid].code)
            val cmp = if (a != lo) a.compareTo(lo) else b.compareTo(hi)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun buildAdjacency(): Pair<IntArray, IntArray> {
        val counts = IntArray(keyArray.size + 1)
        for (i in lefts.indices) {
            counts[lefts[i].code + 1]++
            if (rights[i] != lefts[i]) counts[rights[i].code + 1]++
        }
        for (i in 1 until counts.size) counts[i] += counts[i - 1]
        val offsets = counts.copyOf()
        val fill = counts.copyOf()
        val list = IntArray(counts.last())
        for (i in lefts.indices) {
            list[fill[lefts[i].code]++] = i
            if (rights[i] != lefts[i]) list[fill[rights[i].code]++] = i
        }
        // Each emoji's run in the other emoji's number order, which is the source's order.
        for (n in keyArray.indices) {
            val from = offsets[n]
            val to = offsets[n + 1]
            if (to - from < 2) continue
            val run = list.copyOfRange(from, to).sortedBy { e -> other(e, n) }
            run.forEachIndexed { j, e -> list[from + j] = e }
        }
        return offsets to list
    }

    private fun other(entry: Int, n: Int): Int =
        if (lefts[entry].code == n) rights[entry].code else lefts[entry].code

    /** Writes the index in the format [read] takes back. */
    fun write(out: OutputStream) {
        DataOutputStream(BufferedOutputStream(out)).run {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeUTF(template?.pattern.orEmpty())
            writeInt(dates.size)
            dates.forEach(::writeUTF)
            writeInt(keyArray.size)
            keyArray.forEach(::writeUTF)
            writeInt(lefts.size)
            for (i in lefts.indices) {
                writeChar(lefts[i].code)
                writeChar(rights[i].code)
                writeByte(dateOf[i].toInt())
            }
            writeBoolean(urls != null)
            urls?.forEach { writeUTF(it.orEmpty()) }
            flush()
        }
    }

    /** [write] into [file], through a temporary file so a reader never sees half of one. */
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { write(it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not move ${tmp.name} into place")
        }
    }

    companion object {
        private const val MAGIC = 0x574D4D58 // "WMMX"
        private const val VERSION = 1

        /** Most dates one index holds: they are numbered in a byte. */
        internal const val MAX_DATES = 255

        /** Most emoji one index holds: they are numbered in a char. */
        internal const val MAX_EMOJI = 65_535

        fun read(input: InputStream): MixIndex = DataInputStream(BufferedInputStream(input)).run {
            if (readInt() != MAGIC) throw IOException("not an emoji mix index")
            if (readInt() != VERSION) throw IOException("emoji mix index from another version")
            val template = readUTF().takeIf { it.isNotEmpty() }?.let(::MixTemplate)
            val dates = Array(readInt()) { readUTF() }
            val keys = Array(readInt()) { readUTF() }
            val n = readInt()
            val lefts = CharArray(n)
            val rights = CharArray(n)
            val dateOf = ByteArray(n)
            for (i in 0 until n) {
                lefts[i] = readChar()
                rights[i] = readChar()
                dateOf[i] = readByte()
            }
            val urls = if (readBoolean()) Array<String?>(n) { readUTF().takeIf { it.isNotEmpty() } } else null
            MixIndex(template, keys, dates, lefts, rights, dateOf, urls)
        }

        fun read(file: File): MixIndex = file.inputStream().use(::read)
    }
}

/**
 * Collects pairs from whatever a source gives, then packs them into a
 * [MixIndex]. A pair seen twice (both orders, or two dates of its art) keeps
 * the one marked latest, else the newer date.
 *
 * Addresses are checked against the pattern the first one follows, so an
 * index whose 147,000 addresses all follow one pattern stores none of them.
 */
class MixIndexBuilder {

    private class Raw(val left: String, val right: String, val date: String, val url: String?, val latest: Boolean)

    /** Canonical key → the first spelling of it seen, which is the one addresses use. */
    private val spelling = HashMap<String, String>()
    private val firstSeen = LinkedHashMap<String, Int>()
    private val order = HashMap<String, Int>()
    private val pairs = HashMap<String, Raw>()
    private var template: MixTemplate? = null
    private var templateDecided = false

    /** Pairs collected so far. */
    val size: Int get() = pairs.size

    /** Gives [key] its place in the source's own order, lower first; the lowest place given wins. */
    fun order(key: String, position: Int) {
        order.merge(spell(key), position) { a, b -> minOf(a, b) }
    }

    /**
     * Adds the mix of [left] and [right] (keys, in the order the source draws
     * them) dated [date] (may be empty), at [url] when the source names one.
     */
    fun add(left: String, right: String, date: String, url: String?, latest: Boolean = true) {
        val l = spell(left)
        val r = spell(right)
        val pairKey = if (l <= r) "$l|$r" else "$r|$l"
        val existing = pairs[pairKey]
        if (existing != null) {
            val better = when {
                latest != existing.latest -> latest
                else -> date > existing.date
            }
            if (!better) return
        }
        pairs[pairKey] = Raw(l, r, date, url?.let { compact(l, r, date, it) }, latest)
    }

    private fun spell(key: String): String {
        val lower = key.lowercase()
        val spelled = spelling.getOrPut(MixSeq.canonical(lower)) { lower }
        firstSeen.putIfAbsent(spelled, firstSeen.size)
        return spelled
    }

    /** Null when the pattern rebuilds [url] exactly, so it need not be stored. */
    private fun compact(left: String, right: String, date: String, url: String): String? {
        if (!templateDecided) {
            template = MixTemplate.infer(url)?.template
            templateDecided = true
        }
        val t = template ?: return url
        return if (t.fill(left, right, date) == url) null else url
    }

    /** The index, or null when nothing was added. */
    fun build(): MixIndex? {
        if (pairs.isEmpty()) return null
        val ordered = firstSeen.keys.sortedWith(
            compareBy<String> { order[it] ?: Int.MAX_VALUE }.thenBy { firstSeen.getValue(it) },
        )
        require(ordered.size <= MixIndex.MAX_EMOJI) { "too many emoji for one index" }
        val number = HashMap<String, Int>(ordered.size * 2)
        ordered.forEachIndexed { i, k -> number[k] = i }
        val dates = pairs.values.mapTo(sortedSetOf<String>()) { it.date }.toList()
        require(dates.size <= MixIndex.MAX_DATES) { "too many dates for one index" }
        val dateNumber = dates.withIndex().associate { it.value to it.index }
        val sorted = pairs.values.sortedWith(
            compareBy<Raw> { minOf(number.getValue(it.left), number.getValue(it.right)) }
                .thenBy { maxOf(number.getValue(it.left), number.getValue(it.right)) },
        )
        val lefts = CharArray(sorted.size) { number.getValue(sorted[it].left).toChar() }
        val rights = CharArray(sorted.size) { number.getValue(sorted[it].right).toChar() }
        val dateOf = ByteArray(sorted.size) { dateNumber.getValue(sorted[it].date).toByte() }
        // With no pattern every address is kept; with one, only those it misses.
        val keepUrls = template == null || sorted.any { it.url != null }
        val urls = if (keepUrls) Array(sorted.size) { sorted[it].url } else null
        val hasAny = urls?.any { it != null } == true
        return MixIndex(template, ordered.toTypedArray(), dates.toTypedArray(), lefts, rights, dateOf, urls.takeIf { hasAny })
    }
}
