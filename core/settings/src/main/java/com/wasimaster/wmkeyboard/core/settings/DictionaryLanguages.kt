package com.wasimaster.wmkeyboard.core.settings

/**
 * One chip of the personal dictionary's language filter (#930): the words
 * filed under [languageId], or under every language when it is null, and how
 * many there are.
 */
data class DictionaryLanguageGroup(val languageId: String?, val count: Int)

/**
 * The language groups [words] fall into, given each word's language
 * ([languageOf], spelling to language id; a word missing from it belongs to
 * every language). Languages in [preferred] come first in that order, which
 * is the order the user enabled them in, then any other language by size
 * and id. The every-language group comes last. Empty groups are left out.
 */
fun dictionaryLanguageGroups(
    words: Collection<String>,
    languageOf: Map<String, String>,
    preferred: List<String> = emptyList(),
): List<DictionaryLanguageGroup> {
    val counts = HashMap<String, Int>()
    var untagged = 0
    for (word in words) {
        val id = languageOf[word]
        if (id == null) untagged++ else counts[id] = (counts[id] ?: 0) + 1
    }
    val rank = preferred.withIndex().associate { (i, id) -> id to i }
    val tagged = counts.entries
        .sortedWith(
            compareBy<Map.Entry<String, Int>> { rank[it.key] ?: Int.MAX_VALUE }
                .thenByDescending { it.value }
                .thenBy { it.key },
        )
        .map { DictionaryLanguageGroup(it.key, it.value) }
    return if (untagged > 0) tagged + DictionaryLanguageGroup(null, untagged) else tagged
}
