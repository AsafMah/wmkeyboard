package com.wasimaster.wmkeyboard.core.emoji

/**
 * Resolves the order the emoji panel draws in: which category tabs appear and
 * in what order, and how the emoji inside one category are arranged.
 *
 * The catalog ships in Unicode order, which is a fine default and a poor
 * preference — the categories someone reaches for are rarely the ones Unicode
 * put first, and the emoji they use out of a category are scattered through
 * two hundred they never touch. Both orders are therefore a stored list of
 * ids that [merge] lays over the catalog's own.
 *
 * One object, because the keyboard and the settings screen that edits the
 * order must agree down to the index: the screen draws slot 4 where the panel
 * will draw slot 4, or dragging is a guess.
 */
object EmojiOrder {

    /**
     * Lays a user [stored] order over [catalogOrder].
     *
     * Stored ids that no longer exist are dropped, and duplicates collapse.
     * An id the catalog has and the stored order does not — a new Unicode
     * release, a keyword pack that added one — is inserted after the last
     * already-placed id that outranks it in [catalogOrder], so a run of new
     * emoji keeps its own relative order and lands beside its neighbours
     * rather than in a clump at the end.
     *
     * An empty or wholly stale [stored] answers [catalogOrder] untouched,
     * which is what "the user has no preference here" has to mean: the
     * alternative silently empties the panel.
     */
    fun merge(stored: List<String>, catalogOrder: List<String>): List<String> {
        if (stored.isEmpty()) return catalogOrder
        val rank = HashMap<String, Int>(catalogOrder.size)
        catalogOrder.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val placed = stored.asSequence().filter { it in rank }.distinct().toMutableList()
        if (placed.isEmpty()) return catalogOrder
        // Against the rank map, not the list: a catalog that repeats an id
        // ranks it once, and comparing with the list would call the order
        // complete while an id was still missing.
        if (placed.size == rank.size) return placed
        val seen = placed.toHashSet()
        for (id in catalogOrder) {
            if (!seen.add(id)) continue
            val idRank = rank.getValue(id)
            var at = 0
            placed.forEachIndexed { index, other ->
                if (rank.getValue(other) < idRank) at = index + 1
            }
            placed.add(at, id)
        }
        return placed
    }

    /** Every category the catalog names, in catalog order, deduped. */
    fun catalogCategories(catalog: List<EmojiEntry>): List<String> =
        catalog.map { it.category }.distinct()

    /**
     * The category tabs to draw: [catalog]'s categories in the user's [order],
     * minus the [hidden] ones.
     *
     * Hiding every category is refused rather than obeyed. The panel's grid is
     * paged by category, so an empty list is not an empty panel, it is a panel
     * with nothing to page and no way back — and a stale hidden set (saved
     * against categories a keyword pack later renamed) could reach that state
     * without anyone asking for it.
     */
    fun categories(
        catalog: List<EmojiEntry>,
        order: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
        emojiOrder: Map<String, List<String>> = emptyMap(),
    ): List<String> {
        val ordered = merge(order, catalogCategories(catalog))
        // A tab whose every emoji moved to another one has nothing to page;
        // drawn, it would be a blank page the user cannot fill from the panel.
        val filled = if (emojiOrder.isEmpty()) ordered else layout(catalog, emojiOrder).keys
        val shown = ordered.filter { it in filled && it !in hidden }
        return shown.ifEmpty { ordered }
    }

    /**
     * The emoji [category] holds, in catalog order: the base emoji only, since
     * gender and role variants live in a base's long-press popup rather than
     * in the grid, and never the [excluded] ones (the font cannot draw them).
     */
    fun catalogEmoji(
        catalog: List<EmojiEntry>,
        category: String,
        excluded: Set<String> = emptySet(),
    ): List<String> = catalog.asSequence()
        .filter { it.category == category && it.parent == null && it.emoji !in excluded }
        .map { it.emoji }
        .toList()

    /** The emoji the [category] tab draws, in order: its entry in [layout]. */
    fun emoji(
        catalog: List<EmojiEntry>,
        category: String,
        orders: Map<String, List<String>> = emptyMap(),
        excluded: Set<String> = emptySet(),
    ): List<String> =
        if (orders.isEmpty()) {
            catalogEmoji(catalog, category, excluded)
        } else {
            layout(catalog, orders, excluded)[category].orEmpty()
        }

    /**
     * Every tab's emoji, keyed by catalog category, with the stored per-tab
     * [orders] laid over the catalog. A tab nothing lands in has no key.
     *
     * A tab's stored order may list emoji the catalog files under another
     * category, and when it does they move there: that is how an arrangement
     * copied from another keyboard puts plants beside the animals and the
     * weather among the places, as Unicode's own groups do, when the catalog
     * keeps both in "nature". The emoji's own category wins a tie, and a tab
     * the catalog does not have claims nothing, since nothing would draw it.
     *
     * An emoji no stored order lists follows its nearest listed neighbour of
     * the same catalog category, the one before it or else the one after, to
     * that neighbour's tab; [merge] then seats it beside it. So with nothing
     * moved every emoji stays home, exactly as before tabs could swap emoji,
     * and an emoji a newer catalog adds to a category whose emoji all moved
     * goes with them rather than reviving a hidden tab.
     */
    fun layout(
        catalog: List<EmojiEntry>,
        orders: Map<String, List<String>>,
        excluded: Set<String> = emptySet(),
    ): Map<String, List<String>> {
        val bases = catalog.filter { it.parent == null && it.emoji !in excluded }
        val home = HashMap<String, String>(bases.size)
        bases.forEach { home.putIfAbsent(it.emoji, it.category) }
        val tabs = home.values.toHashSet()
        val claim = HashMap<String, String>()
        for ((tab, order) in orders) {
            if (tab !in tabs) continue
            for (id in order) {
                val own = home[id] ?: continue
                val held = claim[id]
                if (held == null || (tab == own && held != own)) claim[id] = tab
            }
        }
        val tabOf = HashMap<String, String>(home.size)
        for ((category, members) in bases.groupBy { home.getValue(it.emoji) }) {
            var current = members.firstNotNullOfOrNull { claim[it.emoji] } ?: category
            for (entry in members) {
                if (entry.emoji in tabOf) continue
                claim[entry.emoji]?.let { current = it }
                tabOf[entry.emoji] = current
            }
        }
        // Grouped in catalog order, so every tab's members reach merge ranked
        // the way the catalog ranks them, whichever category they came from.
        val byTab = LinkedHashMap<String, LinkedHashSet<String>>()
        for (entry in bases) {
            byTab.getOrPut(tabOf.getValue(entry.emoji)) { LinkedHashSet() } += entry.emoji
        }
        return byTab.mapValues { (tab, members) -> merge(orders[tab].orEmpty(), members.toList()) }
    }
}
