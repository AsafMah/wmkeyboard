package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Sinhala written back in Latin letters, the way people spell it in a chat:
 * කොහොමද → kohomada, මම → mama, යන්නෙ → yanne.
 *
 * Sinhala's block is not laid out like Devanagari's, so it has its own table.
 * Every inherent vowel is said; ව is w, as Sinhala chat writes it (wenawa);
 * the prenasalised letters ඟ ඬ ඳ ඹ are ng, nd, nd, mb; long vowels are
 * doubled except e and o.
 */
object SinhalaRomanizer {

    /**
     * A Sinhala letter or sign, not the Lith digits or the kunddaliya, or the
     * joiner that makes the yansaya and rakaransaya inside a word.
     */
    fun isSinhala(c: Char): Boolean = (c.code in 0x0D81..0x0DF3 && c.code !in 0x0DE6..0x0DEF) || c == ZWJ

    /** Whole text: every Sinhala run becomes Latin, everything else passes through. */
    fun romanize(text: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            if (isSinhala(text[i]) && text[i] != ZWJ) {
                var j = i
                while (j < text.length && isSinhala(text[j])) j++
                out.append(romanizeWord(text.substring(i, j)))
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** One Sinhala word, without spaces. */
    fun romanizeWord(word: String): String {
        val out = StringBuilder(word.length * 2)
        // The consonant written last whose vowel is still to come.
        var pending: String? = null
        fun flush(vowel: String) {
            pending?.let { out.append(it).append(vowel) }
            pending = null
        }
        for ((index, c) in word.withIndex()) {
            val consonant = CONSONANTS[c]
            when {
                consonant != null -> {
                    flush("a")
                    pending = consonant
                }
                c in VOWELS -> {
                    flush("a")
                    out.append(VOWELS.getValue(c))
                }
                c in SIGNS -> flush(SIGNS.getValue(c))
                c == AL_LAKUNA -> {
                    val held = pending
                    pending = null
                    // A doubled consonant is the second one's first letter: yanne, akka.
                    val next = word.getOrNull(index + 1)?.takeIf { it != ZWJ } ?: word.getOrNull(index + 2)
                    if (held != null) out.append(if (CONSONANTS[next] == held) held.take(1) else held)
                }
                c == ANUSVARA -> {
                    flush("a")
                    out.append('n')
                }
                c == VISARGA -> {
                    flush("a")
                    out.append('h')
                }
                else -> Unit
            }
        }
        flush("a")
        return out.toString()
    }

    private const val ZWJ = '‍'
    private const val AL_LAKUNA = '්'
    private const val ANUSVARA = 'ං'
    private const val VISARGA = 'ඃ'

    private val CONSONANTS: Map<Char, String> = mapOf(
        'ක' to "k", 'ඛ' to "kh", 'ග' to "g", 'ඝ' to "gh", 'ඞ' to "n", 'ඟ' to "ng",
        'ච' to "ch", 'ඡ' to "chh", 'ජ' to "j", 'ඣ' to "jh", 'ඤ' to "ny", 'ඥ' to "gn",
        'ඦ' to "nj", 'ට' to "t", 'ඨ' to "th", 'ඩ' to "d", 'ඪ' to "dh", 'ණ' to "n",
        'ඬ' to "nd", 'ත' to "th", 'ථ' to "th", 'ද' to "d", 'ධ' to "dh", 'න' to "n",
        'ඳ' to "nd", 'ප' to "p", 'ඵ' to "ph", 'බ' to "b", 'භ' to "bh", 'ම' to "m",
        'ඹ' to "mb", 'ය' to "y", 'ර' to "r", 'ල' to "l", 'ව' to "w", 'ශ' to "sh",
        'ෂ' to "sh", 'ස' to "s", 'හ' to "h", 'ළ' to "l", 'ෆ' to "f",
    )

    private val VOWELS: Map<Char, String> = mapOf(
        'අ' to "a", 'ආ' to "aa", 'ඇ' to "ae", 'ඈ' to "ae", 'ඉ' to "i", 'ඊ' to "ee",
        'උ' to "u", 'ඌ' to "oo", 'ඍ' to "ru", 'ඎ' to "ru", 'ඏ' to "lu", 'ඐ' to "lu",
        'එ' to "e", 'ඒ' to "e", 'ඓ' to "ai", 'ඔ' to "o", 'ඕ' to "o", 'ඖ' to "au",
    )

    private val SIGNS: Map<Char, String> = mapOf(
        'ා' to "aa", 'ැ' to "ae", 'ෑ' to "ae", 'ි' to "i", 'ී' to "ee", 'ු' to "u",
        'ූ' to "oo", 'ෘ' to "ru", 'ෙ' to "e", 'ේ' to "e", 'ෛ' to "ai", 'ො' to "o",
        'ෝ' to "o", 'ෞ' to "au", 'ෟ' to "lu", 'ෲ' to "ru", 'ෳ' to "lu",
    )
}
