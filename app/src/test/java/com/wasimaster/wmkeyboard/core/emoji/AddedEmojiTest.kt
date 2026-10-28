package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AddedEmojiTest {

    private val catalog = listOf(
        EmojiEntry("😀", "smileys", listOf("grin"), name = "grinning face"),
        EmojiEntry("❤️", "smileys", listOf("love"), name = "red heart"),
        EmojiEntry("🐶", "animals", listOf("dog"), name = "dog face"),
    )

    @Test fun nothingAddedKeepsTheCatalogIdentity() {
        assertSame(catalog, EmojiCatalog.withAdded(catalog, emptyList()))
    }

    @Test fun aNewFaceIsAppendedWithItsNameAsAKeyword() {
        val added = EmojiCatalog.addedEntry("🫟", "objects", "Splatter", listOf("Paint", " "))
        val merged = EmojiCatalog.withAdded(catalog, listOf(added))
        assertEquals(4, merged.size)
        val last = merged.last()
        assertEquals("🫟", last.emoji)
        assertEquals("objects", last.category)
        assertEquals("Splatter", last.name)
        assertEquals(listOf("paint", "splatter"), last.keywords)
        assertNull(last.parent)
        assertEquals(listOf("🫟"), EmojiOrder.catalogEmoji(merged, "objects"))
    }

    @Test fun aFaceTheCatalogHasOnlyLendsItsKeywords() {
        // Typed without the presentation selector the catalog spells it with.
        val added = EmojiCatalog.addedEntry("❤", "symbols", "", listOf("crush"))
        val merged = EmojiCatalog.withAdded(catalog, listOf(added))
        assertEquals(3, merged.size)
        val heart = merged[1]
        assertEquals("smileys", heart.category)
        assertEquals(listOf("love", "crush"), heart.keywords)
    }

    @Test fun addedFacesAreSearchable() {
        val added = EmojiCatalog.addedEntry("🫟", "objects", "splatter", listOf("paint"))
        val search = EmojiSearch(EmojiCatalog.withAdded(catalog, listOf(added)))
        assertEquals("🫟", search.search("paint").first().emoji)
        assertEquals("🫟", search.search("splat").first().emoji)
    }

    @Test fun emojiTestFileYieldsOnlyWhatIsMissing() {
        val file = """
            # group: Smileys & Emotion
            # subgroup: face-smiling
            1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
            # subgroup: face-sleepy
            1FAE9                                                  ; fully-qualified     # 🫩 E16.0 face with bags under eyes
            # group: People & Body
            # subgroup: hand-fingers-open
            1F44B 1F3FB                                            ; fully-qualified     # 👋🏻 E1.0 waving hand: light skin tone
            # group: Component
            # subgroup: skin-tone
            1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
            # group: Animals & Nature
            # subgroup: plant-other
            1FABE                                                  ; fully-qualified     # 🪾 E16.0 leafless tree
            # subgroup: animal-mammal
            2764 FE0F                                              ; fully-qualified     # ❤️ E1.0 red heart
            2764                                                   ; unqualified         # ❤ E1.0 red heart
            # group: Travel & Places
            # subgroup: sky & weather
            1F6D8                                                  ; fully-qualified     # 🛘 E17.0 landslide
        """.trimIndent()
        val found = EmojiTestFile.missing(file.byteInputStream(), catalog, known = listOf("🛘"))
        assertEquals(listOf("🫩", "🪾"), found.map { it.emoji })
        assertEquals("smileys", found[0].category)
        assertEquals("face with bags under eyes", found[0].name)
        assertEquals("nature", found[1].category)
        assertTrue("leafless tree" in found[1].keywords)
    }

    @Test fun skyAndWeatherFilesUnderNature() {
        assertEquals("nature", EmojiTestFile.categoryFor("Travel & Places", "sky & weather"))
        assertEquals("animals", EmojiTestFile.categoryFor("Animals & Nature", "animal-bird"))
        assertNull(EmojiTestFile.categoryFor("Component", "skin-tone"))
    }
}
