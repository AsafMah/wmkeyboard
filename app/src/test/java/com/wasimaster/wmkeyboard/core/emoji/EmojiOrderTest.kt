package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * The resolution both the emoji panel and its settings screen read through
 * (issue #183). The rule that matters is the one in [EmojiOrder.merge]: a
 * stored order is a preference over the catalog, never a replacement for it,
 * so nothing the catalog has can fall out of the answer.
 */
class EmojiOrderTest {

    companion object {
        private lateinit var catalog: List<EmojiEntry>

        @BeforeClass
        @JvmStatic
        fun load() {
            // Assets stayed in :app through the module split; tests run with
            // the module directory as the working directory.
            val asset = File("src/main/assets/emoji/catalog.tsv")
            catalog = EmojiCatalog.load(FileInputStream(asset))
        }
    }

    @Test
    fun `no stored order leaves the catalog alone`() {
        assertEquals(
            EmojiOrder.catalogCategories(catalog),
            EmojiOrder.categories(catalog),
        )
    }

    @Test
    fun `a stored order leads and the rest follows`() {
        val all = EmojiOrder.catalogCategories(catalog)
        val moved = listOf(all.last(), all.first())
        val result = EmojiOrder.merge(moved, all)
        assertEquals(moved, result.take(2))
        assertEquals(all.size, result.size)
        assertEquals(all.toSet(), result.toSet())
    }

    @Test
    fun `an id the catalog dropped is dropped from the order`() {
        val all = EmojiOrder.catalogCategories(catalog)
        val result = EmojiOrder.merge(listOf("nonesuch") + all, all)
        assertEquals(all, result)
    }

    @Test
    fun `a wholly stale order falls back to the catalog`() {
        val all = EmojiOrder.catalogCategories(catalog)
        assertEquals(all, EmojiOrder.merge(listOf("nope", "also-nope"), all))
    }

    @Test
    fun `duplicates in a stored order collapse`() {
        val all = EmojiOrder.catalogCategories(catalog)
        val result = EmojiOrder.merge(listOf(all[1], all[1], all[0]), all)
        assertEquals(listOf(all[1], all[0]), result.take(2))
        assertEquals(all.size, result.size)
    }

    /**
     * The case a new Unicode release is: the user arranged what they had, and
     * the ids they never saw land beside their catalog neighbours rather than
     * in a clump at the end.
     */
    @Test
    fun `an id missing from the order rejoins at its catalog rank`() {
        val catalogOrder = listOf("a", "b", "c", "d", "e")
        // The user moved e to the front and never saw c.
        val result = EmojiOrder.merge(listOf("e", "a", "b", "d"), catalogOrder)
        assertEquals(listOf("e", "a", "b", "c", "d"), result)
    }

    @Test
    fun `several missing ids keep their relative order`() {
        val catalogOrder = listOf("a", "b", "c", "d", "e")
        val result = EmojiOrder.merge(listOf("a", "e"), catalogOrder)
        assertEquals(listOf("a", "b", "c", "d", "e"), result)
    }

    @Test
    fun `hiding a category drops its tab`() {
        val all = EmojiOrder.catalogCategories(catalog)
        val hidden = setOf(all.first())
        val result = EmojiOrder.categories(catalog, hidden = hidden)
        assertEquals(all.drop(1), result)
    }

    /**
     * The panel pages by category, so an empty tab list is a panel with
     * nothing to page — a state no setting may reach.
     */
    @Test
    fun `hiding every category is refused`() {
        val all = EmojiOrder.catalogCategories(catalog)
        assertEquals(all, EmojiOrder.categories(catalog, hidden = all.toSet()))
    }

    @Test
    fun `the grid holds base emoji only`() {
        // Not the first category: gender and role variants are concentrated in
        // a couple of categories, and most have none to leave out.
        val category = catalog.first { it.parent != null }.category
        val grid = EmojiOrder.catalogEmoji(catalog, category)
        val variants = catalog.filter { it.category == category && it.parent != null }
        assertTrue("the fixture needs a category with variants", variants.isNotEmpty())
        assertTrue(variants.none { it.emoji in grid })
    }

    @Test
    fun `an unrenderable emoji leaves the grid and the stored order with it`() {
        val category = EmojiOrder.catalogCategories(catalog).first()
        val base = EmojiOrder.catalogEmoji(catalog, category)
        val gone = base[2]
        val result = EmojiOrder.emoji(
            catalog,
            category,
            orders = mapOf(category to listOf(gone, base[0])),
            excluded = setOf(gone),
        )
        assertTrue(gone !in result)
        assertEquals(base[0], result.first())
        assertEquals(base.size - 1, result.size)
    }

    @Test
    fun `a tab can take another category's emoji`() {
        val animals = EmojiOrder.catalogEmoji(catalog, "animals")
        val nature = EmojiOrder.catalogEmoji(catalog, "nature")
        val layout = EmojiOrder.layout(catalog, mapOf("animals" to animals + nature))
        assertEquals(animals + nature, layout["animals"])
        assertTrue("an emptied tab has no entry", "nature" !in layout)
        assertTrue("nature" !in EmojiOrder.categories(catalog, emojiOrder = mapOf("animals" to animals + nature)))
    }

    @Test
    fun `an unlisted emoji follows its listed neighbour to its tab`() {
        val animals = EmojiOrder.catalogEmoji(catalog, "animals")
        val nature = EmojiOrder.catalogEmoji(catalog, "nature")
        val left = nature[3]
        val layout = EmojiOrder.layout(catalog, mapOf("animals" to animals + (nature - left)))
        val tab = layout.getValue("animals")
        assertEquals(nature[2], tab[tab.indexOf(left) - 1])
        // And a leading one, with nothing before it, follows the one after.
        val first = EmojiOrder.layout(catalog, mapOf("animals" to animals + nature.drop(1)))
        assertTrue(nature[0] in first.getValue("animals"))
    }

    @Test
    fun `nothing moved leaves every tab its own emoji`() {
        val category = "food"
        val base = EmojiOrder.catalogEmoji(catalog, category)
        val layout = EmojiOrder.layout(catalog, mapOf(category to base.reversed()))
        for (other in EmojiOrder.catalogCategories(catalog) - category) {
            assertEquals(EmojiOrder.catalogEmoji(catalog, other), layout[other])
        }
    }

    @Test
    fun `every shipped preset places every emoji once`() {
        val all = EmojiOrder.catalogCategories(catalog)
        val bases = catalog.filter { it.parent == null }.map { it.emoji }.distinct()
        val files = File("src/main/assets/emoji/order").listFiles().orEmpty()
        assertTrue("no presets shipped", files.isNotEmpty())
        for (file in files) {
            val id = file.nameWithoutExtension
            assertTrue("$id is not listed in EmojiOrderPreset.ids", id in EmojiOrderPreset.ids)
            val preset = EmojiOrderPreset.parse(file.readText())
            assertTrue("$id names a tab the catalog lacks", preset.tabs.all { it in all })
            assertTrue("$id has no @grid line", preset.grid != null)
            val layout = EmojiOrder.layout(catalog, preset.emoji)
            val tabs = EmojiOrder.categories(
                catalog, preset.categoryOrder(all), preset.hidden(all), preset.emoji,
            )
            assertEquals(id, preset.tabs.filter { it in layout }, tabs)
            val drawn = tabs.flatMap { layout.getValue(it) }
            assertEquals(id, bases.size, drawn.size)
            assertEquals(id, bases.toSet(), drawn.toSet())
            for ((tab, order) in preset.emoji) {
                assertEquals("$id/$tab", order.filter { it in bases }, layout.getValue(tab).filter { it in order })
            }
        }
    }

    @Test
    fun `a preset's grid line is read and is not a tab`() {
        val preset = EmojiOrderPreset.parse("# a comment\n@grid columns=8 row=51 emoji=34 continuous=true bogus\nsmileys")
        assertEquals(
            EmojiOrderPreset.Grid(columns = 8, rowHeight = 51, emojiSize = 34, continuous = true),
            preset.grid,
        )
        assertEquals(listOf("smileys"), preset.tabs)
        assertEquals(null, EmojiOrderPreset.parse("smileys").grid)
    }

    @Test
    fun `every category the catalog names survives a round trip`() {
        for (category in EmojiOrder.catalogCategories(catalog)) {
            val base = EmojiOrder.catalogEmoji(catalog, category)
            val reversed = base.reversed()
            assertEquals(reversed, EmojiOrder.emoji(catalog, category, mapOf(category to reversed)))
        }
    }
}
