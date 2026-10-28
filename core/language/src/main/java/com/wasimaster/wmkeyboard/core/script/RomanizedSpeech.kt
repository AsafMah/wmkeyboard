package com.wasimaster.wmkeyboard.core.script

/**
 * Dictation into a romanized language (#948): Hinglish, Banglish, Tanglish and
 * the rest of the `*_rom` family are spoken as the language itself, so every
 * recognizer is asked for that language, and its answer comes back in the
 * native script for the keyboard to write in Latin letters afterwards.
 *
 * No recognizer knows "Hindi in Latin letters" as a language of its own: the
 * system recognizer has no `hi-Latn` model, Whisper has no token for it, and a
 * server told nothing listens for English and spells Hindi words as English
 * ones. Asking for Hindi is what gets the words right; the script is fixed
 * after, which is a much smaller problem than the hearing.
 */
object RomanizedSpeech {

    /**
     * Romanized languages that are their own orthography rather than a
     * transliteration. Konkani's Romi has been written in Goa for centuries
     * with spelling rules of its own, which no rule from Devanagari reaches,
     * and no recognizer here hears Konkani anyway.
     */
    private val OWN_ORTHOGRAPHY = setOf("kok_rom")

    /**
     * The language [languageId] is spoken as: `hi` for `hi_rom`. Null when it
     * is not a romanized language, or one written in a way of its own.
     */
    fun spokenAs(languageId: String): String? {
        val def = LanguageRegistry.byId(languageId)
        if (!def.isRomanized || languageId in OWN_ORTHOGRAPHY) return null
        val native = languageId.removeSuffix("_rom")
        return native.takeIf { LanguageRegistry.byId(it).id == it }
    }

    /**
     * [languageIds] as the recognizer is to hear them: every romanized one
     * replaced by the language it is spoken as, the order kept, each once.
     */
    fun heard(languageIds: List<String>): List<String> =
        languageIds.map { spokenAs(it) ?: it }.distinct()

    /**
     * The romanized languages among [languageIds] whose native script is to be
     * written in Latin letters. Not one whose own language was picked as
     * well: dictating Hindi and Hinglish together leaves no way to tell which
     * one a Devanagari word was meant for, so it stays as it was heard.
     */
    fun targets(languageIds: List<String>): List<String> =
        languageIds.filter { id -> spokenAs(id)?.let { it !in languageIds } == true }
}
