package com.wasimaster.wmkeyboard.core.stickers

import java.io.File

/**
 * A pack file read as far as its pictures and no further (#965).
 *
 * Opening a `.wmstickers` or `.wastickers` used to convert every sticker in it
 * on the spot, and an animated one is slow to convert: a pack of forty
 * animations kept the user waiting for stickers they did not want. So the
 * archive is now spilled into a staging directory first, the user is shown
 * every picture and ticks the ones to keep, and only those go through
 * [StickerPackAdoption.adopt].
 *
 * The staging directory belongs to this object until [adopt] or [discard]
 * deletes it. One left behind by a process that died in between is swept by
 * [StickerPackStore] once it is a day old.
 */
class StagedStickerPack internal constructor(
    val name: String,
    /** Who made the pack, when its file says; shown and never stored. */
    val author: String,
    val source: String,
    /** Every sticker the file lists, in its own order. */
    val stickers: List<Sticker>,
    private val staging: File,
) {
    /**
     * One sticker of the pack. [file] is its picture as the archive holds it,
     * for the preview; null when the archive has no picture for it, and then
     * it is not offered for picking (see [StickerPackAdoption.chosenOf]).
     */
    class Sticker(val incoming: StickerPackAdoption.Incoming, val file: File?)

    /** The indexes of the stickers a preview can show and a user can tick. */
    val pickable: List<Int> get() = stickers.indices.filter { stickers[it].file != null }

    /**
     * Registers the [chosen] stickers with [store] as one pack, and deletes the
     * staging directory whatever comes of it.
     */
    fun adopt(
        store: StickerPackStore,
        chosen: Set<Int>,
        now: Long = System.currentTimeMillis(),
        normalize: (ByteArray) -> ProcessedSticker? = { bytes ->
            (StickerImage.process(bytes) as? StickerImage.Result.Ok)?.sticker
        },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StickerImportResult = try {
        StickerPackAdoption.adopt(
            store = store,
            name = name,
            incoming = StickerPackAdoption.chosenOf(stickers, chosen) { it.file != null }.map { it.incoming },
            now = now,
            source = source,
            normalize = normalize,
            onProgress = onProgress,
        )
    } finally {
        discard()
    }

    /** Lets go of the staged pictures without keeping any of them. */
    fun discard() {
        staging.deleteRecursively()
    }

    /** Every sticker with a picture: what an import with nothing to ask keeps. */
    fun everything(): Set<Int> = pickable.toSet()
}

/** How far reading a pack file for its preview got. */
sealed interface StickerStageResult {
    /** Read; the user picks from [pack]. */
    class Ready(val pack: StagedStickerPack) : StickerStageResult

    /**
     * Nothing to pick from: the file is not a pack, could not be read, or
     * holds no picture at all. [result] says which.
     */
    class Done(val result: StickerImportResult) : StickerStageResult
}
