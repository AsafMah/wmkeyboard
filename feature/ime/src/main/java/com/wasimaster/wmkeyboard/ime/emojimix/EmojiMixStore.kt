package com.wasimaster.wmkeyboard.ime.emojimix

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wasimaster.wmkeyboard.core.emoji.mix.EmojiMixer
import com.wasimaster.wmkeyboard.core.emoji.mix.MixIndex
import com.wasimaster.wmkeyboard.core.emoji.mix.MixIndexBuilder
import com.wasimaster.wmkeyboard.core.emoji.mix.MixSeq
import com.wasimaster.wmkeyboard.core.emoji.mix.MixSourceReader
import com.wasimaster.wmkeyboard.core.emoji.mix.MixTemplate
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSourceKind
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * The emoji-mix files on the device: each source's packed index, each
 * imported pack's images, and the images fetched to send. Shared by the
 * settings app, which adds and refreshes sources, and the keyboard, which
 * reads them; the keyboard learns of a change through
 * `EmojiMixSettings.indexVersion`.
 *
 * Every call here is blocking. The keyboard runs them off the main thread;
 * so does the settings screen.
 */
class EmojiMixStore(context: Context) {

    private val app = context.applicationContext
    private val root: File get() = File(app.filesDir, "emoji_mix")
    private val images: File get() = File(app.cacheDir, "emoji_mix")

    /** What adding, refreshing or importing a source came to. */
    sealed interface Outcome {
        /**
         * It worked; [pairs] is how many pairs the index holds, 0 for a mixer.
         * [pack] says an import turned out to be images rather than an index.
         */
        data class Done(val pairs: Int, val placeholderMd5: String = "", val pack: Boolean = false) : Outcome

        /** The address answered with something that lists no mixes (a web page, an empty file). */
        data object NothingFound : Outcome

        /** It failed; [message] is for the row to show. */
        data class Failed(val message: String) : Outcome
    }

    fun indexFile(id: String): File = File(root, "$id.idx")

    fun packDir(id: String): File = File(root, "pack_$id")

    /** Every switched-on source with what it brings, as one resolver. Missing files are skipped. */
    fun load(sources: List<EmojiMixSource>): EmojiMixer {
        val loaded = sources.filter { it.enabled }.map { source ->
            val index = when (source.kind) {
                EmojiMixSourceKind.INDEX, EmojiMixSourceKind.PACK ->
                    runCatching { indexFile(source.id).takeIf { it.isFile }?.let(MixIndex::read) }.getOrNull()
                EmojiMixSourceKind.IMAGE_ADDRESS, EmojiMixSourceKind.MIXER -> null
            }
            val dir = packDir(source.id).takeIf { source.kind == EmojiMixSourceKind.PACK }
            EmojiMixer.Loaded(source, index, dir)
        }
        return EmojiMixer(loaded)
    }

    /**
     * Fetches what [source] needs: an index source's file, read as it comes
     * down and packed; a mixer's placeholder, learned from a pair no source
     * has. An image address needs nothing fetched.
     */
    fun refresh(source: EmojiMixSource): Outcome = runCatching {
        when (source.kind) {
            EmojiMixSourceKind.INDEX -> downloadIndex(source)
            EmojiMixSourceKind.MIXER -> probeMixer(source)
            EmojiMixSourceKind.IMAGE_ADDRESS ->
                if (EmojiMixer.templateOf(source) != null) Outcome.Done(0) else Outcome.NothingFound
            EmojiMixSourceKind.PACK -> Outcome.Done(runCatching { MixIndex.read(indexFile(source.id)).pairCount }.getOrDefault(0))
        }
    }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }

    private fun downloadIndex(source: EmojiMixSource): Outcome {
        val url = source.address.trim()
        // An index imported from a file has no address to fetch again.
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return Outcome.Done(runCatching { MixIndex.read(indexFile(source.id)).pairCount }.getOrDefault(0))
        }
        return ToolHttp.exchange(
            url, "GET",
            timeoutMs = INDEX_TIMEOUT_MS,
            source = NetSource.EMOJI_MIX,
            route = NetLog.pathOf(url),
        ) { exchange ->
            val body = exchange.body
            if (exchange.status !in 200..299 || body == null) return@exchange Outcome.Failed("HTTP ${exchange.status}")
            save(source.id, MixSourceReader.read(body))
        }
    }

    private fun save(id: String, result: MixSourceReader.Result): Outcome = when (result) {
        is MixSourceReader.Result.Read -> {
            result.index.writeTo(indexFile(id))
            Outcome.Done(result.index.pairCount)
        }
        MixSourceReader.Result.Empty, MixSourceReader.Result.NotAnIndex -> Outcome.NothingFound
    }

    private fun probeMixer(source: EmojiMixSource): Outcome {
        val template = EmojiMixer.templateOf(source) ?: return Outcome.NothingFound
        val (a, b) = EmojiMixer.PROBE
        val url = template.fill(MixSeq.key(a), MixSeq.key(b))
        val probe = File(images, "probe_${source.id}.img")
        return try {
            ToolHttp.download(url, probe, source = NetSource.EMOJI_MIX, route = NetLog.pathOf(url))
            Outcome.Done(0, probe.inputStream().use(EmojiMixer::md5))
        } catch (e: ToolHttpException) {
            // A mixer that answers a missing pair with an error, not a
            // picture, needs no fingerprint: a failed fetch is its "no".
            if (e.status in 400..499) Outcome.Done(0) else throw e
        } finally {
            probe.delete()
        }
    }

    /**
     * Imports a file the user picked: a zip of images named for their pairs
     * becomes a pack; anything else is read as an index file.
     */
    fun importFile(uri: Uri, id: String): Outcome = runCatching {
        val resolver = app.contentResolver
        val zip = resolver.requireInputStream(uri).use { looksLikeZip(it) }
        if (zip) {
            resolver.requireInputStream(uri).use { importZip(it, id) }
        } else {
            resolver.requireInputStream(uri).use { save(id, MixSourceReader.read(it)) }
        }
    }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }

    /** Imports a folder the user picked, and the folders inside it: the images named for their pairs. */
    fun importFolder(tree: Uri, id: String): Outcome = runCatching {
        val dir = packDir(id).apply { deleteRecursively(); mkdirs() }
        val builder = MixIndexBuilder()
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)
        walk(tree, rootDoc, "", depth = 0) { docId, path ->
            val pair = MixTemplate.pairOfFile(path) ?: return@walk
            val target = File(dir, path)
            target.parentFile?.mkdirs()
            app.contentResolver.requireInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId)).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            builder.add(pair.left, pair.right, pair.date, path)
        }
        finishPack(id, builder)
    }.getOrElse { Outcome.Failed(it.message ?: it.javaClass.simpleName) }

    private fun walk(tree: Uri, docId: String, prefix: String, depth: Int, onFile: (String, String) -> Unit) {
        if (depth > MAX_FOLDER_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val rows = ArrayList<Triple<String, String, String>>()
        app.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += Triple(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
            }
        }
        for ((child, name, mime) in rows) {
            val safe = safeName(name) ?: continue
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(tree, child, "$prefix$safe/", depth + 1, onFile)
            } else {
                onFile(child, prefix + safe)
            }
        }
    }

    private fun importZip(input: InputStream, id: String): Outcome {
        val dir = packDir(id).apply { deleteRecursively(); mkdirs() }
        val builder = MixIndexBuilder()
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++count > MAX_PACK_FILES) break
                val path = entry.name.split('/').mapNotNull(::safeName).joinToString("/")
                val pair = MixTemplate.pairOfFile(path) ?: continue
                val target = File(dir, path)
                // Zip-slip: a name that climbs out of the pack is never written.
                if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> copyCapped(zip, out) }
                builder.add(pair.left, pair.right, pair.date, path)
            }
        }
        return finishPack(id, builder)
    }

    private fun finishPack(id: String, builder: MixIndexBuilder): Outcome {
        val index = builder.build() ?: run {
            packDir(id).deleteRecursively()
            return Outcome.NothingFound
        }
        index.writeTo(indexFile(id))
        return Outcome.Done(index.pairCount, pack = true)
    }

    private fun copyCapped(input: InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) throw IOException("an image in the pack is too large")
            out.write(buffer, 0, n)
        }
    }

    private fun looksLikeZip(input: InputStream): Boolean {
        val head = ByteArray(4)
        val n = input.read(head)
        return n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2].toInt() == 3 && head[3].toInt() == 4
    }

    /** A path part safe to write under the pack: no climbing, no hidden files, no separators. */
    private fun safeName(name: String): String? =
        name.takeIf { it.isNotBlank() && it != "." && it != ".." && !it.startsWith(".") && '/' !in it && '\\' !in it }

    /** Removes everything [id] left on the device. */
    fun remove(id: String) {
        indexFile(id).delete()
        packDir(id).deleteRecursively()
    }

    /**
     * The image of [mix] on the device, fetched first if need be; null when it
     * could not be had, or when it turned out to be [source]'s placeholder,
     * which means the pair has no mix there. A pack's image is its own file.
     */
    fun fetch(mix: EmojiMixer.Mix, source: EmojiMixSource?): File? = runCatching {
        if (mix.url.startsWith("file:")) return@runCatching File(java.net.URI(mix.url)).takeIf { it.isFile }
        val dir = images.apply { mkdirs() }
        val target = File(dir, "${mix.id.removePrefix("mix:")}_${mix.url.hashCode().toUInt()}.png")
        if (!target.isFile || target.length() == 0L) {
            prune(dir)
            ToolHttp.download(
                mix.url, target,
                maxBytes = MAX_IMAGE_BYTES,
                source = NetSource.EMOJI_MIX,
                route = NetLog.pathOf(mix.url),
            )
        }
        if (!mix.verified && EmojiMixer.isPlaceholder(source, target)) {
            target.delete()
            return@runCatching null
        }
        target.setLastModified(System.currentTimeMillis())
        target
    }.getOrNull()

    /** Empties the fetched images; the indexes and packs stay. */
    fun clearImages() {
        images.deleteRecursively()
    }

    /** Bytes the fetched images take. */
    fun imagesSize(): Long = images.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED_IMAGES).forEach { it.delete() }
    }

    companion object {
        /** A new source's id: short, unique, and safe in a file name. */
        fun newId(): String = "m" + UUID.randomUUID().toString().replace("-", "").take(12)

        /** The largest index in circulation is 99 MB; a slow phone link needs its time. */
        private const val INDEX_TIMEOUT_MS = 120_000
        private const val MAX_PACK_FILES = 300_000
        private const val MAX_FOLDER_DEPTH = 4
        private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        private const val MAX_CACHED_IMAGES = 400
    }
}
