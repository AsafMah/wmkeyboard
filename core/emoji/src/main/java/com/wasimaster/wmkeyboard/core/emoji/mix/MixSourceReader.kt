package com.wasimaster.wmkeyboard.core.emoji.mix

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/**
 * Reads an emoji-mix index file into a [MixIndexBuilder], whatever shape it
 * comes in, without ever holding the whole file: the largest index in
 * circulation is about 99 MB of JSON.
 *
 *  - JSON: any object with an image address (`gStaticUrl`, `url`, `image` or
 *    `src`) and the two emoji (`leftEmojiCodepoint`/`rightEmojiCodepoint`,
 *    `left`/`right` or `leftEmoji`/`rightEmoji`), or an address alone whose
 *    path names the pair. `date` and `isLatest` are read when present, and an
 *    object with `emojiCodepoint` and `gBoardOrder` or `order` places that
 *    emoji in the source's order. A bare array of addresses works too.
 *  - Text: per line, one address, or two emoji and an address, or two emoji
 *    alone (a list of which pairs exist, its images to come from another
 *    source). Tabs, commas or spaces between them; `#` starts a comment.
 */
object MixSourceReader {

    /** What reading found: the pairs, or why there were none. */
    sealed interface Result {
        data class Read(val index: MixIndex) : Result
        data object NotAnIndex : Result
        data object Empty : Result
    }

    fun read(input: InputStream): Result = read(input.bufferedReader())

    fun read(reader: Reader): Result {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        val builder = MixIndexBuilder()
        val first = firstNonSpace(buffered) ?: return Result.Empty
        when (first) {
            '{', '[' -> readJson(buffered, first, builder)
            '<' -> return Result.NotAnIndex
            else -> readLines(buffered, first, builder)
        }
        val index = builder.build() ?: return Result.Empty
        return Result.Read(index)
    }

    private fun firstNonSpace(reader: BufferedReader): Char? {
        while (true) {
            val c = reader.read()
            if (c < 0) return null
            // A byte-order mark is not content.
            if (c == 0xFEFF || c.toChar().isWhitespace()) continue
            return c.toChar()
        }
    }

    private fun readJson(reader: BufferedReader, first: Char, builder: MixIndexBuilder) {
        var supportedPosition = 0
        JsonScanner(reader, first).scan(
            onObject = { fields -> objectFields(fields, builder) },
            onArrayString = { value, key ->
                if (looksLikeAddress(value)) {
                    MixTemplate.infer(value)?.let { builder.add(it.left, it.right, it.date, value) }
                } else if (key != null && key.contains("emoji", ignoreCase = true)) {
                    MixSeq.parse(value)?.let { builder.order(it, ORDER_FROM_LIST + supportedPosition++) }
                }
            },
        )
    }

    private fun objectFields(fields: Map<String, String>, builder: MixIndexBuilder) {
        val url = URL_FIELDS.firstNotNullOfOrNull { fields[it] }?.takeIf(::looksLikeAddress)
        val left = LEFT_FIELDS.firstNotNullOfOrNull { fields[it]?.let(MixSeq::parse) }
        val right = RIGHT_FIELDS.firstNotNullOfOrNull { fields[it]?.let(MixSeq::parse) }
        val latest = fields["isLatest"]?.equals("false", ignoreCase = true) != true
        if (left != null && right != null) {
            val date = fields["date"] ?: url?.let { MixTemplate.infer(it)?.date }.orEmpty()
            builder.add(left, right, date, url, latest)
        } else if (url != null) {
            MixTemplate.infer(url)?.let { builder.add(it.left, it.right, fields["date"] ?: it.date, url, latest) }
        }
        val emoji = (fields["emojiCodepoint"] ?: fields["emoji"])?.let(MixSeq::parse)
        val position = (fields["gBoardOrder"] ?: fields["order"])?.toDoubleOrNull()?.toInt()
        if (emoji != null && position != null) builder.order(emoji, position)
    }

    private fun readLines(reader: BufferedReader, first: Char, builder: MixIndexBuilder) {
        var line: String? = first + reader.readLine().orEmpty()
        while (line != null) {
            readLine(line.trim(), builder)
            line = reader.readLine()
        }
    }

    private fun readLine(line: String, builder: MixIndexBuilder) {
        if (line.isEmpty() || line.startsWith("#")) return
        val tokens = line.split(SEPARATORS).filter { it.isNotEmpty() }
        val url = tokens.firstOrNull(::looksLikeAddress)
        val emoji = tokens.filter { it != url }.mapNotNull(MixSeq::parse)
        when {
            emoji.size >= 2 -> {
                val date = url?.let { MixTemplate.infer(it)?.date }.orEmpty()
                builder.add(emoji[0], emoji[1], date, url)
            }
            url != null -> MixTemplate.infer(url)?.let { builder.add(it.left, it.right, it.date, url) }
        }
    }

    /** Whether [text] is an address rather than an emoji or a word. */
    fun looksLikeAddress(text: String): Boolean =
        text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("file:", ignoreCase = true)

    private val SEPARATORS = Regex("[\\t,; ]+")
    private val URL_FIELDS = listOf("gStaticUrl", "url", "image", "src")
    private val LEFT_FIELDS = listOf("leftEmojiCodepoint", "left", "leftEmoji")
    private val RIGHT_FIELDS = listOf("rightEmojiCodepoint", "right", "rightEmoji")

    /** A list of supported emoji places them after any that carry their own order. */
    private const val ORDER_FROM_LIST = 1_000_000
}

/**
 * A forgiving, streaming JSON reader that hands back each object's scalar
 * fields as it closes, and each string in an array with the key the array
 * sits under. Nothing else is kept, so a file of any size reads in the memory
 * of its largest object. Malformed input ends the scan rather than throwing:
 * whatever was read before it stays read.
 */
internal class JsonScanner(private val reader: Reader, private val first: Char) {

    private sealed class Frame(val key: String?) {
        class Obj(key: String?) : Frame(key) {
            val fields = HashMap<String, String>()
            var name: String? = null
            var expectingName = true
        }
        class Arr(key: String?) : Frame(key)
    }

    private var pushedBack = -1

    fun scan(onObject: (Map<String, String>) -> Unit, onArrayString: (String, String?) -> Unit) {
        val stack = ArrayList<Frame>()
        var c: Int = first.code
        try {
            while (c >= 0) {
                val ch = c.toChar()
                when {
                    ch == '{' -> stack += Frame.Obj(keyForValue(stack))
                    ch == '[' -> stack += Frame.Arr(keyForValue(stack))
                    ch == '}' -> {
                        val obj = stack.removeLastOrNull() as? Frame.Obj ?: return
                        onObject(obj.fields)
                    }
                    ch == ']' -> if (stack.removeLastOrNull() !is Frame.Arr) return
                    ch == ':' -> (stack.lastOrNull() as? Frame.Obj)?.expectingName = false
                    ch == ',' -> (stack.lastOrNull() as? Frame.Obj)?.let { it.expectingName = true; it.name = null }
                    ch == '"' -> {
                        val s = readString()
                        when (val top = stack.lastOrNull()) {
                            is Frame.Obj -> if (top.expectingName) top.name = s else top.name?.let { top.fields[it] = s }
                            is Frame.Arr -> onArrayString(s, top.key)
                            null -> Unit
                        }
                    }
                    ch.isWhitespace() -> Unit
                    else -> {
                        val literal = readLiteral(ch)
                        val top = stack.lastOrNull()
                        if (top is Frame.Obj && !top.expectingName) top.name?.let { top.fields[it] = literal }
                    }
                }
                c = next()
            }
        } catch (_: IOException) {
            // Whatever came before the break is kept.
        }
    }

    private fun keyForValue(stack: List<Frame>): String? = when (val top = stack.lastOrNull()) {
        is Frame.Obj -> top.name
        is Frame.Arr -> top.key
        null -> null
    }

    private fun next(): Int {
        if (pushedBack >= 0) {
            val c = pushedBack
            pushedBack = -1
            return c
        }
        return reader.read()
    }

    private fun readString(): String {
        val out = StringBuilder()
        while (true) {
            val c = next()
            if (c < 0) throw IOException("unterminated string")
            when (val ch = c.toChar()) {
                '"' -> return if (out.length > MAX_VALUE) "" else out.toString()
                '\\' -> {
                    when (val e = next().toChar()) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            val hex = CharArray(4) { next().toChar() }
                            out.append(String(hex).toIntOrNull(16)?.toChar() ?: '?')
                        }
                        else -> out.append(e)
                    }
                }
                else -> if (out.length <= MAX_VALUE) out.append(ch)
            }
        }
    }

    private fun readLiteral(start: Char): String {
        val out = StringBuilder().append(start)
        while (true) {
            val c = next()
            if (c < 0) return out.toString()
            val ch = c.toChar()
            if (ch == ',' || ch == '}' || ch == ']' || ch == ':' || ch.isWhitespace()) {
                pushedBack = c
                return out.toString()
            }
            out.append(ch)
        }
    }

    private companion object {
        /** Longer strings are read through and dropped: no field this reader wants runs past it. */
        const val MAX_VALUE = 4096
    }
}
