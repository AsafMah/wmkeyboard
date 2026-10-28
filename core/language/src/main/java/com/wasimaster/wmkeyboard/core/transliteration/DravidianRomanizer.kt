package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Tamil, Telugu, Kannada and Malayalam written back in Latin letters, the way
 * people spell them in a chat: காதல் → kaadhal, ఎక్కడ → ekkada,
 * ಬೆಂಗಳೂರು → bengaluru, ഞാൻ → njaan.
 *
 * Not the Hindi romanizer over the same letters, which is what these four
 * had before: Hindi leaves the inherent vowel unsaid at the end of a word and
 * between voiced syllables, and Dravidian speech says it, so ఎక్కడ came
 * out "ekkad". Here every inherent vowel is written.
 *
 * The four blocks are laid out like Devanagari, 0x80 apart, so each letter is
 * read by its place in the block ([IndicScript.block]). What differs by script
 * is small and kept in [letter]: Tamil writes no voiced stops and is read by
 * where the stop stands (காதல் has a dh, கதை a th), Malayalam closes a word on
 * a spoken u and has its chillus and its റ്റ, and Tamil's ற்ற is a tr.
 *
 * Conventions are the ones romanized chat uses, not a scholarly scheme: long
 * vowels doubled (aa, ee, oo) but e and o single, the plain dentals th and dh
 * as IndicProfiles' index expects them, ழ as zh.
 */
object DravidianRomanizer {

    /** Whether [script] is one of the four this reads. */
    fun reads(script: IndicScript): Boolean = script in SCRIPTS

    /** One word of [script], without spaces, in Latin letters. */
    fun romanizeWord(script: IndicScript, word: String): String {
        val syllables = parse(script, word)
        val out = StringBuilder(word.length * 2)
        for (i in syllables.indices) {
            val s = syllables[i]
            if (s.consonant >= 0) out.append(consonant(script, syllables, i))
            if (!s.killed) out.append(s.vowel ?: "a")
            if (s.nasal) out.append(nasal(syllables, i))
            if (s.visarga) out.append('h')
        }
        // Malayalam's closing virama is a spoken u: ആണ് is "aanu".
        if (script == IndicScript.MALAYALAM && syllables.lastOrNull()?.let { it.killed && it.explicitVirama } == true) {
            out.append('u')
        }
        return out.toString()
    }

    /** Whole text: every run of [script]'s letters becomes Latin, everything else passes through. */
    fun romanize(script: IndicScript, text: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            if (script.isNative(text[i])) {
                var j = i
                while (j < text.length && script.isNative(text[j])) j++
                out.append(romanizeWord(script, text.substring(i, j)))
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * One written syllable: a consonant (its Devanagari place, or -1 for an
     * independent vowel), the vowel said after it (null for the inherent
     * one), or none at all when [killed].
     */
    private class Syllable(val consonant: Int) {
        var vowel: String? = null
        var killed = false
        /** Killed by a written virama, not by a chillu. */
        var explicitVirama = false
        var nasal = false
        var visarga = false
    }

    private fun parse(script: IndicScript, word: String): List<Syllable> {
        val base = script.block.first
        val out = ArrayList<Syllable>(word.length)
        for (ch in word) {
            CHILLUS[ch]?.let { place ->
                out += Syllable(place).apply { killed = true }
                continue
            }
            if (ch.code !in script.block) continue
            val code = ch.code - base + DEVANAGARI
            val last = out.lastOrNull()
            when {
                code in CONSONANTS -> out += Syllable(code)
                code in VOWELS -> out += Syllable(-1).apply { vowel = VOWELS.getValue(code) }
                code in SIGNS -> last?.vowel = SIGNS.getValue(code)
                code == VIRAMA -> last?.apply { killed = true; explicitVirama = true }
                code == ANUSVARA || code == CANDRABINDU -> last?.nasal = true
                code == VISARGA -> last?.visarga = true
            }
        }
        // A sign with nothing in front of it, a stray mark, is dropped above.
        return out
    }

    private fun consonant(script: IndicScript, syllables: List<Syllable>, i: Int): String {
        val s = syllables[i]
        val prev = syllables.getOrNull(i - 1)
        val next = syllables.getOrNull(i + 1)
        val place = s.consonant
        val geminateFirst = s.killed && next != null && next.consonant == place
        val afterSame = prev != null && prev.killed && prev.consonant == place
        return when {
            // The first of a doubled pair is the second's first letter (kk,
            // cch, tth), or nothing where the pair is written as one (ങ്ങ is
            // ng, ഞ്ഞ nj). Tamil's ற்ற and Malayalam's റ്റ open on a t.
            geminateFirst && place == RRA -> "t"
            geminateFirst && (place == NGA || place == NYA) -> ""
            geminateFirst -> PLAIN.getValue(place).take(1)
            // ங்க is ng and ஞ்ச nj: the nasal before another consonant is an n,
            // and the stop after it says the rest.
            (place == NGA || place == NYA) && s.killed && next != null && next.consonant >= 0 -> "n"
            else -> letter(script, place, prev, afterSame)
        }
    }

    /**
     * The letters for the consonant at Devanagari place [place], with [prev]
     * the syllable in front of it, for Tamil's stops and the r clusters.
     */
    private fun letter(script: IndicScript, place: Int, prev: Syllable?, afterSame: Boolean): String {
        if (place == RRA) {
            return when {
                afterSame -> if (script == IndicScript.MALAYALAM) "t" else "r"
                // ன்ற in Tamil is ndr; ന്റ in Malayalam is nt.
                prev != null && prev.killed && prev.consonant in DENTAL_N ->
                    if (script == IndicScript.MALAYALAM) "t" else "dr"
                else -> "r"
            }
        }
        if (script == IndicScript.TAMIL) TAMIL_STOPS[place]?.let { forms ->
            return when {
                prev == null -> forms[0]
                afterSame -> forms[3]
                prev.killed && prev.consonant in NASALS -> forms[1]
                prev.killed -> forms[3]
                else -> forms[2]
            }
        }
        // A doubled plain dental is tt or dd, not tth: ಹೇಗಿದ್ದೀರಾ is "hegiddeeraa".
        if (afterSame && (place == TA || place == DA)) return if (place == TA) "t" else "d"
        return PLAIN.getValue(place)
    }

    /**
     * A nasal sign, said as the nasal of the consonant after it, and as m
     * closing the word (മലയാളം is "malayaalam").
     */
    private fun nasal(syllables: List<Syllable>, i: Int): String {
        val next = syllables.getOrNull(i + 1)?.consonant ?: return "m"
        return if (next in LABIALS) "m" else "n"
    }

    private val SCRIPTS = setOf(IndicScript.TAMIL, IndicScript.TELUGU, IndicScript.KANNADA, IndicScript.MALAYALAM)

    private const val DEVANAGARI = 0x0900
    private const val VIRAMA = 0x094D
    private const val ANUSVARA = 0x0902
    private const val CANDRABINDU = 0x0901
    private const val VISARGA = 0x0903
    private const val NGA = 0x0919
    private const val NYA = 0x091E
    private const val RRA = 0x0931
    private const val TA = 0x0924
    private const val DA = 0x0926

    private val PLAIN: Map<Int, String> = mapOf(
        0x0915 to "k", 0x0916 to "kh", 0x0917 to "g", 0x0918 to "gh", NGA to "ng",
        0x091A to "ch", 0x091B to "chh", 0x091C to "j", 0x091D to "jh", NYA to "nj",
        0x091F to "t", 0x0920 to "th", 0x0921 to "d", 0x0922 to "dh", 0x0923 to "n",
        TA to "th", 0x0925 to "th", DA to "dh", 0x0927 to "dh", 0x0928 to "n", 0x0929 to "n",
        0x092A to "p", 0x092B to "ph", 0x092C to "b", 0x092D to "bh", 0x092E to "m",
        0x092F to "y", 0x0930 to "r", RRA to "r", 0x0932 to "l", 0x0933 to "l", 0x0934 to "zh",
        0x0935 to "v", 0x0936 to "sh", 0x0937 to "sh", 0x0938 to "s", 0x0939 to "h",
    )
    private val CONSONANTS: Set<Int> = PLAIN.keys

    /**
     * Tamil's stops, which its script writes one way whatever is said: at the
     * start of a word; after a nasal; between vowels; doubled or after
     * another consonant.
     */
    private val TAMIL_STOPS: Map<Int, Array<String>> = mapOf(
        0x0915 to arrayOf("k", "g", "g", "k"),
        0x091A to arrayOf("s", "j", "s", "ch"),
        0x091F to arrayOf("t", "d", "d", "t"),
        TA to arrayOf("th", "dh", "dh", "th"),
        0x092A to arrayOf("p", "b", "b", "p"),
    )
    private val NASALS = setOf(NGA, NYA, 0x0923, 0x0928, 0x0929, 0x092E)
    private val DENTAL_N = setOf(0x0928, 0x0929)
    private val LABIALS = setOf(0x092A, 0x092B, 0x092C, 0x092D, 0x092E)

    private val VOWELS: Map<Int, String> = mapOf(
        0x0905 to "a", 0x0906 to "aa", 0x0907 to "i", 0x0908 to "ee", 0x0909 to "u", 0x090A to "oo",
        0x090B to "ru", 0x0960 to "roo", 0x090C to "lu", 0x090E to "e", 0x090F to "e", 0x0910 to "ai",
        0x0912 to "o", 0x0913 to "o", 0x0914 to "au",
    )
    private val SIGNS: Map<Int, String> = mapOf(
        0x093E to "aa", 0x093F to "i", 0x0940 to "ee", 0x0941 to "u", 0x0942 to "oo",
        0x0943 to "ru", 0x0944 to "roo", 0x0946 to "e", 0x0947 to "e", 0x0948 to "ai",
        0x094A to "o", 0x094B to "o", 0x094C to "au",
        // Malayalam's au length mark, written alone after the consonant.
        0x0957 to "au",
    )

    /** Malayalam's chillus: a consonant that closes a syllable, written as one letter. */
    private val CHILLUS: Map<Char, Int> = mapOf(
        'ൺ' to 0x0923, 'ൻ' to 0x0928, 'ർ' to 0x0930,
        'ൽ' to 0x0932, 'ൾ' to 0x0933, 'ൿ' to 0x0915,
    )
}
