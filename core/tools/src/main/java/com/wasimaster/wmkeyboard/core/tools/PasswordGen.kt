package com.wasimaster.wmkeyboard.core.tools

import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Password and passphrase generation for the generator tool. All entropy
 * comes from [SecureRandom]; nothing is stored or logged. Passphrase words
 * come from the bundled English dictionary (loaded once, filtered to short
 * common words) so no extra wordlist ships with the app.
 */
object PasswordGen {

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"
    private const val AMBIGUOUS = "Il1O0o5S8B|`'\"{}[]()"

    data class PasswordSpec(
        val length: Int = 16,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = false,
        /**
         * The symbol pool to draw from; blank uses the built-in set.
         *
         * Sites that reject particular punctuation used to mean generating
         * again and again until one came out clean, because the pool was fixed.
         */
        val symbolPool: String = "",
    )

    data class PassphraseSpec(
        val words: Int = 4,
        val separator: String = "-",
        val capitalize: Boolean = false,
        val includeDigit: Boolean = false,
    )

    fun password(spec: PasswordSpec, random: SecureRandom = SecureRandom()): String {
        fun pool(chars: String) =
            if (spec.excludeAmbiguous) chars.filterNot { it in AMBIGUOUS } else chars
        val pools = buildList {
            add(pool(LOWER))
            if (spec.upper) add(pool(UPPER))
            if (spec.digits) add(pool(DIGITS))
            if (spec.symbols) add(pool(spec.symbolPool.ifBlank { SYMBOLS }))
        }.filter { it.isNotEmpty() }
        val all = pools.joinToString("")
        val length = spec.length.coerceIn(4, 128)
        // One character from every enabled class, the rest uniform, then a
        // Fisher-Yates shuffle so the guaranteed ones aren't front-loaded.
        val chars = buildList {
            pools.forEach { add(it[random.nextInt(it.length)]) }
            repeat(length - size) { add(all[random.nextInt(all.length)]) }
        }.toMutableList()
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return chars.take(length).joinToString("")
    }

    /** PIN lengths the generator offers (#899): 4 and 6 are the usual ones. */
    val PIN_LENGTHS: IntRange = 4..12

    /**
     * A PIN of [length] digits (#899). With [avoidWeak] it is drawn again
     * until it is none of the shapes a PIN rule refuses and a guesser tries
     * first ([isWeakPin]): no PIN is lost to those rules, only the few that
     * break them.
     */
    fun pin(length: Int, avoidWeak: Boolean = true, random: SecureRandom = SecureRandom()): String {
        val n = length.coerceIn(PIN_LENGTHS)
        var candidate: String
        var tries = 0
        do {
            candidate = buildString { repeat(n) { append(DIGITS[random.nextInt(DIGITS.length)]) } }
            tries++
        } while (avoidWeak && isWeakPin(candidate) && tries < MAX_PIN_TRIES)
        return candidate
    }

    /**
     * Whether [pin] is a shape a bank or phone refuses, or a guesser tries
     * first: one digit over and over (`1111`), a run up or down (`1234`,
     * `9876`), a short block repeated (`1212`, `123123`), or a date — a
     * year (`1987`), a day and month either way round (`2512`, `1225`), and
     * those with a two- or four-digit year after or before them.
     */
    fun isWeakPin(pin: String): Boolean {
        if (pin.isEmpty() || pin.any { it !in DIGITS }) return false
        val d = pin.map { it - '0' }
        if (d.all { it == d[0] }) return true
        val steps = d.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return true
        for (period in 1..pin.length / 2) {
            if (pin.length % period == 0 && pin == pin.take(period).repeat(pin.length / period)) return true
        }
        return looksLikeDate(pin)
    }

    private fun looksLikeDate(pin: String): Boolean {
        fun n(s: String) = s.toInt()
        fun dayMonth(s: String) = (n(s.take(2)) in 1..31 && n(s.drop(2)) in 1..12) ||
            (n(s.take(2)) in 1..12 && n(s.drop(2)) in 1..31)
        fun year4(s: String) = n(s) in 1900..2099
        return when (pin.length) {
            4 -> year4(pin) || dayMonth(pin)
            6 -> dayMonth(pin.take(4)) || dayMonth(pin.drop(2))
            8 -> (dayMonth(pin.take(4)) && year4(pin.drop(4))) || (year4(pin.take(4)) && dayMonth(pin.drop(4)))
            else -> false
        }
    }

    private const val MAX_PIN_TRIES = 1000

    /** Strength of a PIN in bits, for the panel's meter. */
    fun pinEntropyBits(length: Int): Int =
        (length.coerceIn(PIN_LENGTHS) * ln(10.0) / ln(2.0)).roundToInt()

    fun passphrase(
        wordlist: List<String>,
        spec: PassphraseSpec,
        random: SecureRandom = SecureRandom(),
    ): String {
        if (wordlist.isEmpty()) return ""
        val count = spec.words.coerceIn(2, 12)
        val words = List(count) { wordlist[random.nextInt(wordlist.size)] }
            .map { if (spec.capitalize) it.replaceFirstChar(Char::uppercase) else it }
            .toMutableList()
        if (spec.includeDigit) {
            val at = random.nextInt(words.size)
            words[at] = words[at] + random.nextInt(10)
        }
        return words.joinToString(spec.separator)
    }

    /** Rough strength estimate in bits, for the panel's meter. */
    fun passwordEntropyBits(spec: PasswordSpec): Int {
        var poolSize = LOWER.length
        if (spec.upper) poolSize += UPPER.length
        if (spec.digits) poolSize += DIGITS.length
        if (spec.symbols) poolSize += spec.symbolPool.ifBlank { SYMBOLS }.length
        if (spec.excludeAmbiguous) poolSize -= 8
        return (spec.length.coerceIn(4, 128) * ln(poolSize.toDouble()) / ln(2.0)).roundToInt()
    }

    fun passphraseEntropyBits(spec: PassphraseSpec, wordlistSize: Int): Int {
        if (wordlistSize <= 1) return 0
        var bits = spec.words.coerceIn(2, 12) * ln(wordlistSize.toDouble()) / ln(2.0)
        if (spec.includeDigit) bits += ln(10.0 * spec.words) / ln(2.0)
        return bits.roundToInt()
    }

    /** The default size of a built wordlist. */
    const val WORDLIST_LIMIT = 4096

    /**
     * Whether [word] is passphrase material: short, unambiguous, all lowercase
     * letters.
     *
     * Named rather than inlined into [buildWordlist] because the caller that
     * builds the list from a trie hands it to the walk as its filter, and the
     * two must agree on what a passphrase word is.
     */
    fun acceptsWord(word: String): Boolean =
        word.length in 4..8 && word.all { c -> c in 'a'..'z' }

    /**
     * Filters a dictionary's words down to passphrase material: common,
     * short, unambiguous, all-lowercase-letter words.
     */
    fun buildWordlist(words: Sequence<String>, limit: Int = WORDLIST_LIMIT): List<String> =
        words
            .filter(::acceptsWord)
            .distinct()
            .take(limit)
            .toList()
}
