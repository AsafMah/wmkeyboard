package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Russian written back in Latin letters, the way "translit" chat spells it:
 * привет → privet, как дела → kak dela, жизнь → zhizn, хорошо → horosho.
 *
 * Not a passport or library standard: х is h, щ is sch, the soft and hard
 * signs are dropped, and ы and й are both y, which is how the people who type
 * Translit spell it. A capital stays a capital (Москва → Moskva), and a word
 * in capitals stays in capitals (ЖКХ → ZHKH).
 */
object CyrillicRomanizer {

    /** Whole text: every Cyrillic letter becomes Latin, everything else passes through. */
    fun romanize(text: String): String {
        val out = StringBuilder(text.length + text.length / 4)
        for ((i, c) in text.withIndex()) {
            val latin = LETTERS[c.lowercaseChar()]
            if (latin == null) {
                out.append(c)
                continue
            }
            if (!c.isUpperCase() || latin.isEmpty()) {
                out.append(latin)
                continue
            }
            // A word in capitals keeps all of a sound's letters capital.
            val shouting = text.getOrNull(i + 1)?.isUpperCase() == true || text.getOrNull(i - 1)?.isUpperCase() == true
            out.append(if (shouting) latin.uppercase() else latin.replaceFirstChar { it.uppercaseChar() })
        }
        return out.toString()
    }

    private val LETTERS: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )
}
