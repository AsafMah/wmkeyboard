package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Down here rather than with the emoji settings so the emoji module, which
// reads and builds the indexes, and the settings module, which stores the
// list, share one type without either depending on the other.

/**
 * What an emoji-mix source address turned out to be. The keyboard ships no
 * source of its own: every one of these is an address the user pasted or a
 * file they imported, and the kind is worked out from its shape.
 */
@Serializable
enum class EmojiMixSourceKind {
    /**
     * A file listing which pairs exist: JSON (each pair's image address with
     * the two emoji it mixes) or plain text (one address, or two emoji and an
     * address, per line). Downloaded once and packed into an index on the
     * device; it is what lets the keyboard list every mix of an emoji.
     */
    @SerialName("index") INDEX,

    /**
     * One working image address, from which the keyboard learns the pattern
     * every other image follows (`…/{date}/{left}/{left}_{right}.png`). Needs
     * an index to know which pairs exist and their dates; points the index's
     * images at this host instead of the one the index names.
     */
    @SerialName("image_address") IMAGE_ADDRESS,

    /**
     * An address that answers any two emoji with their mix, given as a
     * pattern with `{left}` and `{right}` in it, or as one example. Works with
     * no index, one pair at a time.
     */
    @SerialName("mixer") MIXER,

    /** Images the user imported from a file or folder, copied onto the device. Works offline. */
    @SerialName("pack") PACK,
}

/**
 * One source of emoji mixes, as the user added it. [address] is what they
 * typed (or the name of what they imported); everything else is learned.
 */
@Serializable
data class EmojiMixSource(
    val id: String,
    val kind: EmojiMixSourceKind,
    val address: String,
    val enabled: Boolean = true,
    /** Pairs in this source's index once it is built; 0 for a mixer or before the first build. */
    val pairCount: Int = 0,
    /** When the index was last built, epoch millis; 0 for never. */
    val refreshedAt: Long = 0L,
    /**
     * For a mixer: the MD5 of the image it answers a pair that has no mix
     * with. Such services answer every pair with an image, so this is how a
     * missing mix is told from a real one. Empty until learned.
     */
    val placeholderMd5: String = "",
    /** The last refresh's failure, for the row to show; empty when it worked. */
    val error: String = "",
)
