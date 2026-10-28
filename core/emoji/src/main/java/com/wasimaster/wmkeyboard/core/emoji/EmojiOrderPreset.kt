package com.wasimaster.wmkeyboard.core.emoji

/**
 * The emoji panel arranged the way another keyboard arranges it, for someone
 * whose thumbs learned that keyboard first: which tabs it has, in what order,
 * and where each emoji sits in them.
 *
 * A preset is not a mode. Picking one writes the three orders [EmojiOrder]
 * already reads — tab order, hidden tabs, per-tab emoji order — so the panel
 * needs nothing new, the user can drag anything afterwards, and an emoji the
 * other keyboard lacks lands beside its neighbours through [EmojiOrder.layout].
 *
 * The data is an asset per keyboard (`emoji/order/<id>.txt`), one line per
 * tab in tab order: a catalog category id, then that tab's emoji in the other
 * keyboard's order. A category with no line is hidden. A line with nothing
 * after the id keeps that category in catalog order, which is what most of
 * them are, and stores nothing. An `@grid` line gives that keyboard's grid,
 * as `key=value` pairs; see [Grid].
 */
data class EmojiOrderPreset(
    /** Tab order, as catalog category ids; every catalog category not here is hidden. */
    val tabs: List<String>,
    /** Per tab, its emoji in order; a tab with no entry keeps catalog order. */
    val emoji: Map<String, List<String>>,
    /** That keyboard's grid on a phone held upright; null leaves the grid alone. */
    val grid: Grid? = null,
) {
    /**
     * The other keyboard's grid, read from its own resources and code: how
     * many columns, how tall a row, how big an emoji, and whether the tabs
     * are one long list. Columns and row height together are what make the
     * same scroll land on the same emoji. A null field means our default.
     */
    data class Grid(
        /** `columns`: a fixed count, or 0 to fit by [cellSize]. */
        val columns: Int? = null,
        /** `row`: row height in dp. */
        val rowHeight: Int? = null,
        /** `cell`: narrowest cell in dp, for fitting columns. */
        val cellSize: Int? = null,
        /** `emoji`: glyph size. */
        val emojiSize: Int? = null,
        /** `continuous`: every tab in one list rather than a page each. */
        val continuous: Boolean? = null,
    ) {
        companion object {
            fun parse(fields: List<String>): Grid {
                val values = fields.mapNotNull { field ->
                    field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                return Grid(
                    columns = values["columns"]?.toIntOrNull(),
                    rowHeight = values["row"]?.toIntOrNull(),
                    cellSize = values["cell"]?.toIntOrNull(),
                    emojiSize = values["emoji"]?.toIntOrNull(),
                    continuous = values["continuous"]?.toBooleanStrictOrNull(),
                )
            }
        }
    }

    /** The tabs this preset hides, out of [categories]. */
    fun hidden(categories: List<String>): Set<String> = categories.filterNot { it in tabs }.toSet()

    /**
     * The stored tab order: the preset's tabs first, then the ones it hides in
     * catalog order, so un-hiding one puts it somewhere sensible.
     */
    fun categoryOrder(categories: List<String>): List<String> =
        tabs + categories.filterNot { it in tabs }

    companion object {
        /**
         * Ids of the presets, in the order the setting lists them. One whose
         * data file is not in the assets is not offered: Samsung Keyboard has
         * none yet. Its APK (5.9.40) carries no panel order, only prediction
         * data, so the order has to be read off a Samsung phone.
         */
        val ids = listOf("gboard", "swiftkey", "samsung", "heliboard")

        /** Asset path of preset [id]. */
        fun assetPath(id: String): String = "emoji/order/$id.txt"

        /**
         * Reads a preset file. Comment lines start with `#`; ids repeated
         * across lines keep their first place, so a hand-edited file cannot
         * put one emoji on two tabs.
         */
        fun parse(text: String): EmojiOrderPreset {
            val tabs = ArrayList<String>()
            val emoji = LinkedHashMap<String, List<String>>()
            val seen = HashSet<String>()
            var grid: Grid? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(' ').filter { it.isNotEmpty() }
                val tab = parts.first()
                if (tab == "@grid") {
                    grid = Grid.parse(parts.drop(1))
                    continue
                }
                if (tab in tabs) continue
                tabs += tab
                val list = parts.drop(1).filter { seen.add(it) }
                if (list.isNotEmpty()) emoji[tab] = list
            }
            return EmojiOrderPreset(tabs, emoji, grid)
        }
    }
}
