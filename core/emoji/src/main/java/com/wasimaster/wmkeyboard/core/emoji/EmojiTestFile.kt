package com.wasimaster.wmkeyboard.core.emoji

import java.io.InputStream

/**
 * Reads Unicode's `emoji-test.txt`, the file that lists every RGI emoji of a
 * release with its group and name, for the emoji the bundled catalog is
 * missing.
 *
 * This is the bulk half of adding emoji by hand: when a new Unicode release is
 * out and this build's catalog predates it, one file from unicode.org adds the
 * whole release at once instead of one face at a time.
 *
 * Line shapes it reads (everything else is skipped):
 * ```
 * # group: Smileys & Emotion
 * # subgroup: face-smiling
 * 1F600 ; fully-qualified # 😀 E1.0 grinning face
 * ```
 *
 * Left out on purpose:
 *  - anything not `fully-qualified`: the minimally- and un-qualified spellings
 *    are the same faces again;
 *  - the Component group (bare skin tones and hair styles);
 *  - skin-tone variants. The panel shows a neutral face and keeps its tones
 *    in the long-press popup, which is built from the bundled variant table;
 *    a toned face added here would sit in the grid beside its neutral one,
 *    five times over.
 */
object EmojiTestFile {

    /** Refuse anything far larger than the real file (about 700 KB). */
    const val MAX_BYTES = 4L * 1024 * 1024

    private val TONES = 0x1F3FB..0x1F3FF

    /** The same group-to-category mapping `tools/emoji/generate_catalog.py` uses. */
    private val GROUPS = mapOf(
        "Smileys & Emotion" to "smileys",
        "People & Body" to "people",
        "Food & Drink" to "food",
        "Travel & Places" to "travel",
        "Activities" to "activities",
        "Objects" to "objects",
        "Symbols" to "symbols",
        "Flags" to "flags",
    )

    private val COMMENT = Regex("""\s*\S+\s+E[\d.]+\s+(.*)""")

    /**
     * The catalog category for a Unicode group and subgroup, or null for one
     * the panel has no tab for.
     */
    fun categoryFor(group: String, subgroup: String): String? = when {
        group == "Animals & Nature" -> if (subgroup.startsWith("animal")) "animals" else "nature"
        group == "Travel & Places" && subgroup == "sky & weather" -> "nature"
        else -> GROUPS[group]
    }

    /**
     * Every emoji in [stream] that neither [catalog] nor [known] already has,
     * in file order, as catalog entries named by the file. [known] is what
     * the user added before, so a second import adds nothing twice.
     */
    fun missing(
        stream: InputStream,
        catalog: List<EmojiEntry>,
        known: Collection<String> = emptyList(),
    ): List<EmojiEntry> {
        val have = HashSet<String>(catalog.size + known.size)
        catalog.mapTo(have) { EmojiCatalog.matchKey(it.emoji) }
        known.mapTo(have) { EmojiCatalog.matchKey(it) }
        val out = ArrayList<EmojiEntry>()
        var group = ""
        var subgroup = ""
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("# group:") -> group = line.substringAfter(':').trim()
                    line.startsWith("# subgroup:") -> subgroup = line.substringAfter(':').trim()
                    line.isBlank() || line.startsWith("#") -> Unit
                    else -> parseLine(line, group, subgroup)?.let { entry ->
                        if (have.add(EmojiCatalog.matchKey(entry.emoji))) out.add(entry)
                    }
                }
            }
        }
        return out
    }

    private fun parseLine(line: String, group: String, subgroup: String): EmojiEntry? {
        val body = line.substringBefore('#')
        val fields = body.split(';')
        if (fields.size < 2 || fields[1].trim() != "fully-qualified") return null
        val category = categoryFor(group, subgroup) ?: return null
        val points = fields[0].trim().split(Regex("\\s+")).map { hex ->
            hex.toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) } ?: return null
        }
        if (points.isEmpty() || points.any { it in TONES }) return null
        val emoji = buildString { points.forEach(::appendCodePoint) }
        val name = COMMENT.matchEntire(line.substringAfter('#', ""))?.groupValues?.get(1)?.trim().orEmpty()
        return EmojiCatalog.addedEntry(emoji, category, name, emptyList())
    }
}
