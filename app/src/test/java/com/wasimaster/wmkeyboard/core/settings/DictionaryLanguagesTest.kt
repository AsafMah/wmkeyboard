package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryLanguagesTest {

    private val words = listOf("adda", "bhai", "hello", "bonjour", "Wasimaster", "colour")
    private val languageOf = mapOf(
        "adda" to "bn",
        "bhai" to "bn",
        "hello" to "en",
        "colour" to "en",
        "bonjour" to "fr",
    )

    @Test
    fun enabledLanguagesLeadInTheirOrderAndEveryLanguageComesLast() {
        assertEquals(
            listOf(
                DictionaryLanguageGroup("en", 2),
                DictionaryLanguageGroup("bn", 2),
                DictionaryLanguageGroup("fr", 1),
                DictionaryLanguageGroup(null, 1),
            ),
            dictionaryLanguageGroups(words, languageOf, preferred = listOf("en", "de", "bn")),
        )
    }

    @Test
    fun otherLanguagesGoBySizeThenId() {
        assertEquals(
            listOf("bn", "en", "fr", null),
            dictionaryLanguageGroups(words, languageOf).map { it.languageId },
        )
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        assertEquals(
            listOf(DictionaryLanguageGroup(null, 2)),
            dictionaryLanguageGroups(listOf("a", "b"), emptyMap()),
        )
        assertEquals(emptyList<DictionaryLanguageGroup>(), dictionaryLanguageGroups(emptyList(), languageOf))
    }
}
