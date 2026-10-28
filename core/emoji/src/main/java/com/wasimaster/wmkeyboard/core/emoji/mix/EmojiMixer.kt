package com.wasimaster.wmkeyboard.core.emoji.mix

import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSourceKind
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Every emoji-mix source the user switched on, answering as one: which pairs
 * have a mix, and where each one's image is.
 *
 * The kinds play different parts. An index or an imported pack says which
 * pairs exist; an image address re-points an index's images at the host the
 * user named; a mixer gives an address for any pair, which the index then
 * vouches for, or which has to be fetched and checked against the mixer's
 * placeholder when no index knows the pair. Sources answer in list order.
 */
class EmojiMixer(sources: List<Loaded>) {

    /** One source and what it brings: its index, if it has one, and where a pack's files are. */
    data class Loaded(val source: EmojiMixSource, val index: MixIndex? = null, val packDir: File? = null)

    /**
     * One mix: the two emoji in the order the image draws them, its address,
     * and whether a source vouches that it exists. An unverified mix comes
     * from a mixer no index backs, and is only real if its image is not the
     * mixer's placeholder (see [isPlaceholder]).
     */
    data class Mix(
        val left: String,
        val right: String,
        val url: String,
        val verified: Boolean,
        val sourceId: String,
    ) {
        /** Stable across sources and sessions, and the same for either order of the pair. */
        val id: String get() = mixId(left, right)
    }

    private val enabled = sources.filter { it.source.enabled }
    private val indexes = enabled.filter { it.index != null }
    private val images = enabled.filter { it.source.kind == EmojiMixSourceKind.IMAGE_ADDRESS }
        .mapNotNull { loaded -> templateOf(loaded.source)?.let { loaded to it } }
    private val mixers = enabled.filter { it.source.kind == EmojiMixSourceKind.MIXER }
        .mapNotNull { loaded -> templateOf(loaded.source)?.let { loaded to it } }

    /** Whether anything can be mixed at all. */
    val usable: Boolean get() = resolvableIndex() || mixers.isNotEmpty()

    /** Whether the mixes of an emoji can be listed, which takes an index: a mixer only answers pairs. */
    val canList: Boolean get() = resolvableIndex()

    private fun resolvableIndex(): Boolean = indexes.any { loaded ->
        val index = loaded.index ?: return@any false
        index.hasAddresses || loaded.packDir != null || images.isNotEmpty() || mixers.isNotEmpty()
    }

    /** Whether [emoji] has mixes: in an index, or, with a mixer and no index, any emoji might. */
    fun supports(emoji: String): Boolean =
        indexes.any { it.index!!.supports(emoji) } || (indexes.isEmpty() && mixers.isNotEmpty())

    /** Every emoji the indexes mix, as emoji, in the first index's order, the others' after. */
    fun supported(): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (loaded in indexes) {
            for (key in loaded.index!!.keys()) {
                val emoji = MixSeq.emoji(key) ?: continue
                seen.putIfAbsent(MixSeq.canonical(key), emoji)
            }
        }
        return seen.values.toList()
    }

    /** The mix of [a] and [b], or null when no source has one. */
    fun mix(a: String, b: String): Mix? {
        for (loaded in indexes) {
            val entry = loaded.index!!.find(a, b) ?: continue
            resolve(loaded, entry)?.let { return it }
        }
        // No index knows the pair: a mixer may still, one fetch away.
        // Both emoji are in an index that has no such pair: there is none to find.
        if (indexes.any { loaded -> loaded.index!!.let { it.supports(a) && it.supports(b) } }) return null
        val (loaded, template) = mixers.firstOrNull() ?: return null
        val left = MixSeq.key(a)
        val right = MixSeq.key(b)
        return Mix(a, b, template.fill(left, right), verified = false, sourceId = loaded.source.id)
    }

    /**
     * Every mix [emoji] is in, up to [limit], in the source's order, each pair
     * once; empty without an index.
     */
    fun partners(emoji: String, limit: Int = Int.MAX_VALUE): List<Mix> {
        val out = LinkedHashMap<String, Mix>()
        for (loaded in indexes) {
            for (entry in loaded.index!!.partners(emoji)) {
                if (out.size >= limit) return out.values.toList()
                val mix = resolve(loaded, entry) ?: continue
                out.putIfAbsent(mix.id, mix)
            }
        }
        return out.values.toList()
    }

    private fun resolve(loaded: Loaded, entry: MixIndex.Entry): Mix? {
        val index = loaded.index ?: return null
        val left = MixSeq.emoji(entry.left) ?: return null
        val right = MixSeq.emoji(entry.right) ?: return null
        val id = loaded.source.id
        // An image address the user named wins: it is a mirror they chose.
        images.firstOrNull { (_, t) -> !t.needsDate || entry.date.isNotEmpty() }?.let { (_, t) ->
            return Mix(left, right, t.fill(entry.left, entry.right, entry.date), verified = true, sourceId = id)
        }
        val own = entry.url
            ?: index.template?.takeIf { !it.needsDate || entry.date.isNotEmpty() }
                ?.fill(entry.left, entry.right, entry.date)
        if (own != null) {
            val url = loaded.packDir?.let { dir -> File(dir, own.removePrefix("/")).toURI().toString() } ?: own
            return Mix(left, right, url, verified = true, sourceId = id)
        }
        val (_, mixer) = mixers.firstOrNull() ?: return null
        return Mix(left, right, mixer.fill(entry.left, entry.right), verified = true, sourceId = id)
    }

    companion object {
        val EMPTY = EmojiMixer(emptyList())

        /** [Mix.id] for a pair, either order. */
        fun mixId(a: String, b: String): String {
            val x = MixSeq.canonicalOf(a)
            val y = MixSeq.canonicalOf(b)
            return if (x <= y) "mix:${x}_$y" else "mix:${y}_$x"
        }

        /**
         * A pair no source has a mix of, ©️ and ®️: what a mixer is asked once
         * when it is added, so the image it answers a missing pair with is
         * known (it answers every pair with an image).
         */
        val PROBE: Pair<String, String> = "©️" to "®️"

        /** The pattern a mixer or image-address source follows: as typed, or worked out from its example. */
        fun templateOf(source: EmojiMixSource): MixTemplate? {
            val address = source.address.trim()
            if (MixTemplate.isPattern(address)) return MixTemplate(address)
            return MixTemplate.infer(address)?.template
        }

        /**
         * What kind of source [address] is, from its shape alone: an imported
         * file is a pack, a pattern or example naming a dated folder an image
         * address, one naming a pair without a date a mixer, anything else an
         * index to be downloaded (whose content then says whether it is one).
         */
        fun detect(address: String): EmojiMixSourceKind {
            val a = address.trim()
            if (a.startsWith("content:", ignoreCase = true) || a.startsWith("file:", ignoreCase = true)) {
                return EmojiMixSourceKind.PACK
            }
            if (MixTemplate.isPattern(a)) {
                return if (MixTemplate.DATE in a) EmojiMixSourceKind.IMAGE_ADDRESS else EmojiMixSourceKind.MIXER
            }
            val inferred = MixTemplate.infer(a) ?: return EmojiMixSourceKind.INDEX
            return if (inferred.template.needsDate) EmojiMixSourceKind.IMAGE_ADDRESS else EmojiMixSourceKind.MIXER
        }

        /** MD5 of a fetched image, lower-case hex: what a placeholder is recognised by. */
        fun md5(input: InputStream): String {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Whether [file] is [source]'s placeholder: the image a mixer sends for a pair it has no mix of. */
        fun isPlaceholder(source: EmojiMixSource?, file: File): Boolean {
            val known = source?.placeholderMd5?.takeIf { it.isNotEmpty() } ?: return false
            return runCatching { file.inputStream().use(::md5) }.getOrNull() == known
        }
    }
}
