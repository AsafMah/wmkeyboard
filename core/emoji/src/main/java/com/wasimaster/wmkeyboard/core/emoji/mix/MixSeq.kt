package com.wasimaster.wmkeyboard.core.emoji.mix

/**
 * Emoji as the keys emoji-mix sources name them: the code points in lower-case
 * hex, joined by `-` (`1f62e-200d-1f4a8` for 😮‍💨). Sources write them several
 * ways (`u1f603`, `u263a-ufe0f`, `U+1F603`, the emoji itself), and [parse]
 * takes them all.
 *
 * Two keys for the same emoji can differ in their FE0F variation selectors,
 * and the emoji the user picked can carry a skin tone no source draws, so
 * matching goes through [canonical], which drops both. The key a source
 * gave is kept as it was for its addresses: those spell the selectors out.
 */
object MixSeq {

    private const val FE0F = 0xFE0F
    private val TONES = 0x1F3FB..0x1F3FF

    /** The key of [emoji], exactly as its code points spell it. */
    fun key(emoji: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (out.isNotEmpty()) out.append('-')
            out.append(Integer.toHexString(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** The emoji a key spells, or null for a key that is not hex code points. */
    fun emoji(key: String): String? {
        val out = StringBuilder()
        for (part in key.split('-')) {
            val cp = part.toIntOrNull(16) ?: return null
            if (cp < 0 || cp > Character.MAX_CODE_POINT) return null
            out.appendCodePoint(cp)
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * The key of [token] in any of the spellings sources use, or null when it
     * is not an emoji at all: `1f603`, `u1f603`, `U+1F603`, `u263a-ufe0f`,
     * and the emoji itself. An underscore is never part of one key: it joins
     * the two emoji of a pair.
     */
    fun parse(token: String): String? {
        val t = token.trim()
        if (t.isEmpty()) return null
        if (t.any { it.code > 0x7F }) return key(t).takeIf { looksLikeEmoji(it) }
        val parts = t.split('-')
        val cps = ArrayList<Int>(parts.size)
        for (raw in parts) {
            val p = raw.removePrefix("U+").removePrefix("u+").removePrefix("u").removePrefix("U")
            if (p.length !in 2..6 || p.any { Character.digit(it, 16) < 0 }) return null
            cps += p.toInt(16)
        }
        val key = cps.joinToString("-") { Integer.toHexString(it) }
        return key.takeIf { looksLikeEmoji(it) }
    }

    /**
     * Whether [key] starts with a code point an emoji can start with. Keeps a
     * number such as `12` or `add` from reading as an emoji: every emoji
     * starts at or past U+2000, apart from ©, ® and the keycap bases. A hex
     * word past U+2000 (`face`) still passes, which is why callers only ask
     * this of fields that are meant to hold emoji.
     */
    fun looksLikeEmoji(key: String): Boolean {
        val first = key.substringBefore('-').toIntOrNull(16) ?: return false
        if (first > Character.MAX_CODE_POINT) return false
        return first >= 0x2000 || first == 0xA9 || first == 0xAE || first == 0x23 || first == 0x2A ||
            first in 0x30..0x39
    }

    /**
     * [key] with its variation selectors and skin tones taken out: what two
     * spellings of one emoji, or a toned emoji and its base, have in common.
     */
    fun canonical(key: String): String =
        key.split('-').filter { part ->
            val cp = part.toIntOrNull(16) ?: return@filter true
            cp != FE0F && cp !in TONES
        }.joinToString("-")

    /** [canonical] of [emoji]'s key. */
    fun canonicalOf(emoji: String): String = canonical(key(emoji))

    /**
     * The emoji [text] ends in, at most [max] of them, in text order: only
     * emoji written back to back count, so "hi 😃😁" ends in two and
     * "😃 x 😁" in one. Joined sequences, keycaps, flags, tags, skin tones
     * and variation selectors stay with the emoji they belong to.
     */
    fun trailingEmoji(text: String, max: Int): List<String> {
        val out = ArrayList<String>(max)
        var end = text.length
        while (out.size < max && end > 0) {
            val start = emojiStartBefore(text, end) ?: break
            out.add(0, text.substring(start, end))
            end = start
        }
        return out
    }

    /** Where the one emoji that ends at [end] starts, or null when [text] does not end in one there. */
    private fun emojiStartBefore(text: String, end: Int): Int? {
        var i = end
        var start: Int? = null
        while (true) {
            // The trailing marks of one element: selectors, a keycap, tones, tags.
            var j = i
            while (j > 0 && isMark(text.codePointBefore(j))) j -= Character.charCount(text.codePointBefore(j))
            if (j == 0) return start
            val base = text.codePointBefore(j)
            val keycap = j < i && text.codePointBefore(i) == KEYCAP
            if (!isEmojiBase(base, keycap)) return start
            j -= Character.charCount(base)
            // A flag is two regional indicators.
            if (base in REGIONAL && j > 0 && text.codePointBefore(j) in REGIONAL) {
                j -= Character.charCount(text.codePointBefore(j))
            }
            start = j
            // A joiner glues the element before it on.
            if (j > 0 && text[j - 1].code == ZWJ) i = j - 1 else return start
        }
    }

    private fun isMark(cp: Int): Boolean = cp == FE0F || cp == KEYCAP || cp in TONES || cp in TAGS

    private fun isEmojiBase(cp: Int, keycap: Boolean): Boolean = when {
        keycap -> cp == 0x23 || cp == 0x2A || cp in 0x30..0x39
        cp == 0xA9 || cp == 0xAE || cp == 0x203C || cp == 0x2049 -> true
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x2100..0x2BFF -> Character.getType(cp).let {
            it == Character.OTHER_SYMBOL.toInt() || it == Character.MATH_SYMBOL.toInt()
        }
        cp in 0x3030..0x303D || cp == 0x3297 || cp == 0x3299 -> true
        else -> false
    }

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private val TAGS = 0xE0020..0xE007F
    private val REGIONAL = 0x1F1E6..0x1F1FF

    /** [key] the way the dated image layout writes it: every code point `u`-prefixed. */
    fun uForm(key: String): String = key.split('-').joinToString("-") { "u$it" }
}
