package com.wasimaster.wmkeyboard.ime

/**
 * Burmese typed in the order it is drawn, the way Gboard takes it.
 *
 * ေ and medial ra (ြ) sit to the left of their consonant, so people type them
 * first. Unicode stores both after it, and a ေ typed before န renders as ေ◌န:
 * a dotted circle, then the consonant (Play review, Oct 2026). Two repairs, both
 * on text that is never valid Unicode, so nobody typing in stored order is
 * touched:
 *
 * - A consonant typed after a run of ေ / ြ that has no consonant of its own
 *   goes in front of the run: ေ န is နေ, ေ ြ မ (or ြ ေ မ) is မြေ.
 * - A medial typed after a consonant's ေ goes in front of the ေ, among the
 *   consonant's other medials in stored order: ေ မ ြ is မြေ, ေ က ျ is ကျေ.
 */
internal object MyanmarVisualOrder {

    /** Take [delete] characters from in front of the caret and type [text]. */
    data class Edit(val delete: Int, val text: String)

    /** Whether [c] can start an edit, the cheap test before the field is read. */
    fun reorders(c: Char): Boolean = isConsonant(c) || isMedial(c)

    /** The edit [typed] makes with [before] behind the caret, or null to type it as it is. */
    fun reorder(before: CharSequence, typed: String): Edit? {
        val c = typed.singleOrNull() ?: return null
        return when {
            isConsonant(c) -> consonantAfterPreBase(before, c)
            isMedial(c) -> medialAfterE(before, c)
            else -> null
        }
    }

    private fun consonantAfterPreBase(before: CharSequence, c: Char): Edit? {
        // The longest tail of pre-base signs whose first one has nothing to
        // sit on. "ကေ" + ေ is a whole syllable then an orphan: only the second
        // ေ is waiting for this consonant.
        var start = -1
        var i = before.length
        while (i > 0 && isPreBase(before[i - 1])) {
            i--
            if (!hosts(before.getOrNull(i - 1), before[i])) start = i
        }
        if (start < 0) return null
        val run = before.subSequence(start, before.length).toList()
            .sortedBy { if (it == MEDIAL_RA) 0 else 1 }
        return Edit(before.length - start, c + run.joinToString(""))
    }

    private fun medialAfterE(before: CharSequence, c: Char): Edit? {
        var i = before.length
        while (i > 0 && isE(before[i - 1])) i--
        val eRun = before.length - i
        if (eRun == 0) return null
        // The medials already on the consonant, so the new one can take its
        // stored place among them: ျ ြ ွ ှ.
        var m = i
        while (m > 0 && isMedial(before[m - 1])) m--
        if (!isConsonant(before.getOrNull(m - 1) ?: return null)) return null
        val medials = (before.subSequence(m, i).toList() + c).sortedBy { if (it in 'ျ'..'ှ') it.code else Int.MAX_VALUE }
        return Edit(before.length - m, medials.joinToString("") + before.subSequence(i, before.length))
    }

    private fun hosts(prev: Char?, sign: Char): Boolean = when {
        prev == null -> false
        sign == MEDIAL_RA -> isConsonant(prev)
        else -> isConsonant(prev) || isMedial(prev)
    }

    private fun isPreBase(c: Char) = c == MEDIAL_RA || isE(c)

    // ေ, and Shan's own e (U+1084), drawn and typed the same way.
    private fun isE(c: Char) = c == 'ေ' || c == 'ႄ'

    private fun isMedial(c: Char) = c in 'ျ'..'ှ' || c in 'ၞ'..'ၠ' || c == 'ႂ'

    // The consonants and independent vowels a sign can sit on, in the
    // Myanmar block and both extensions. Letters (Lo) only, so digits and
    // signs never qualify.
    private fun isConsonant(c: Char) = Character.getType(c) == Character.OTHER_LETTER.toInt() &&
        (c in 'က'..'႟' || c in 'ꧠ'..'꧿' || c in 'ꩠ'..'ꩿ')

    private const val MEDIAL_RA = 'ြ'
}
