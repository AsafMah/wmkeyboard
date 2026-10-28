package com.wasimaster.wmkeyboard.core.handwriting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source

/**
 * Handwriting models fetched on another device and imported here (#666).
 *
 * ML Kit gives no way to hand it a model file: its recogniser loads only what
 * Mobile Data Download (MDD) fetched and recorded itself. So rather than
 * forge that record, an import lets MDD do a real download that never leaves
 * the phone. The pack files a model needs (the ones `manifest.json` lists,
 * from dl.google.com) are checked against their published SHA-1 and kept in
 * [DIRECTORY]; then the model is downloaded as usual, and the request for
 * each pack is answered from that directory.
 *
 * Three places in ML Kit's downloader stand in the way of that on a phone
 * with no network or no internet permission, and the build rewrites each of
 * them to call in here (see `InkOfflineImportsTransform` in build-logic):
 *
 *  - [build]: every OkHttp client the library builds gets [LocalPacks] as an
 *    application interceptor, which serves a pack from disk before OkHttp
 *    looks anything up.
 *  - [checkSelfPermission]: the downloader refuses to start without the
 *    internet permission, which the no-internet build does not have.
 *  - [connectivityWaived]: with no network it queues the download until one
 *    appears, which on a phone kept offline is never.
 *
 * The last two only give way while an import is installing ([waiving]), so
 * an ordinary download behaves exactly as before.
 */
object InkPackImports {

    /** Where imported packs wait for their model to be installed, under `filesDir`. */
    private const val DIRECTORY = "handwriting_imports"

    private const val PACK_HOST = "dl.google.com"
    private const val PACK_PATH = "/handwriting/models/"

    @Volatile
    private var filesDir: File? = null

    /** Imports installing right now; above zero, the two gates give way. */
    private val waivers = AtomicInteger()

    fun attach(context: Context) {
        if (filesDir == null) filesDir = context.applicationContext.filesDir
    }

    private fun directory(context: Context): File {
        attach(context)
        return File(context.applicationContext.filesDir, DIRECTORY)
    }

    /** Whether [file] is one of the packs ML Kit fetches, by name or failing that by size. Blocking. */
    fun isPack(context: Context, file: File): Boolean = match(context, file) != null

    /**
     * Whether a file called [name], [size] bytes long, is one of the packs, from
     * the name and size alone: for a file not yet copied anywhere, such as one
     * opened from a file manager. [isPack] still has the last word on install.
     */
    fun isPackFile(context: Context, name: String, size: Long): Boolean {
        val manifest = runCatching { HandwritingModelSizes.manifest(context) }.getOrNull() ?: return false
        return manifest.values.any { it.fileName == name && it.compressedSize == size }
    }

    private fun match(context: Context, file: File): InkPack? {
        val manifest = runCatching { HandwritingModelSizes.manifest(context) }.getOrNull() ?: return null
        val length = file.length()
        // A browser that already had a file by this name saves "name (1).zip",
        // so the size is the fallback, and the checksum then has the last word.
        return manifest.values.firstOrNull { it.fileName == file.name && it.compressedSize == length }
            ?: manifest.values.firstOrNull { it.compressedSize == length && sha1(file) == it.sha1 }
    }

    /** What one imported file came to. */
    sealed interface Result {
        val fileName: String

        /** Its model is installed; [tags] are the models it completed. */
        data class Installed(override val fileName: String, val tags: List<String>) : Result

        /** Kept, and waiting on the other packs its model needs. */
        data class Waiting(override val fileName: String) : Result

        /**
         * Kept, its model's packs all here, but the recogniser's library is
         * not (#946): the offline build cannot fetch it, so the model installs
         * once the library is imported ([installWaiting]).
         */
        data class NeedsLibrary(override val fileName: String) : Result

        /** The right name and size with the wrong contents. */
        data class Mismatch(override val fileName: String) : Result

        /** Its model would not install. */
        data class Failed(override val fileName: String) : Result
    }

    /**
     * Keeps every pack among [files], then installs each model that the kept
     * packs now complete. A model whose packs arrive over several imports
     * installs on the one that brings the last of them.
     */
    suspend fun install(context: Context, files: List<File>): List<Result> {
        val dir = directory(context)
        val kept = LinkedHashMap<String, InkPack>()
        val results = ArrayList<Result>()
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            for (file in files) {
                val pack = match(context, file)
                if (pack == null || sha1(file) != pack.sha1) {
                    results += Result.Mismatch(file.name)
                    continue
                }
                file.copyTo(File(dir, pack.fileName), overwrite = true)
                kept[file.name] = pack
            }
        }
        if (kept.isEmpty()) return results
        // Installing waits on the library, which an offline phone would wait
        // for forever and then report as a failed file.
        if (!FeatureModules.handwriting.installed) {
            val mapping = withContext(Dispatchers.IO) { HandwritingModelSizes.packMapping(context) }
            for ((name, pack) in kept) {
                val complete = mapping.any { (tag, names) -> pack.name in names && covers(context, tag) }
                results += if (complete) Result.NeedsLibrary(name) else Result.Waiting(name)
            }
            return results
        }
        val installed = installCompleted(context)
        for ((name, pack) in kept) {
            val tags = installed.filterValues { pack.name in it.first }
            results += when {
                tags.isEmpty() -> Result.Waiting(name)
                tags.values.all { it.second } -> Result.Installed(name, tags.keys.toList())
                else -> Result.Failed(name)
            }
        }
        return results
    }

    /**
     * Installs the models whose packs were kept while the recogniser's
     * library was missing ([Result.NeedsLibrary]), now that it is here; the
     * tags installed, and whether each made it. Nothing when the library is
     * still missing or no packs wait.
     */
    suspend fun installWaiting(context: Context): Map<String, Boolean> {
        if (!FeatureModules.handwriting.installed) return emptyMap()
        val waiting = withContext(Dispatchers.IO) { directory(context).list()?.isNotEmpty() == true }
        if (!waiting) return emptyMap()
        return installCompleted(context).mapValues { it.value.second }
    }

    /**
     * Installs every model whose packs are all in the directory, and clears
     * out the packs of the ones that made it: MDD keeps its own copy. Tag to
     * the pack names it used and whether it installed.
     */
    private suspend fun installCompleted(context: Context): Map<String, Pair<Set<String>, Boolean>> {
        val dir = directory(context)
        val here = withContext(Dispatchers.IO) { dir.list()?.toSet().orEmpty() }
        val mapping = withContext(Dispatchers.IO) { HandwritingModelSizes.packMapping(context) }
        val manifest = withContext(Dispatchers.IO) { HandwritingModelSizes.manifest(context) }
        val outcome = LinkedHashMap<String, Pair<Set<String>, Boolean>>()
        for (tag in InkModelTags.ALL) {
            val names = mapping[tag] ?: continue
            val packs = names.mapNotNull { manifest[it] }
            if (packs.isEmpty() || packs.size != names.size || packs.any { it.fileName !in here }) continue
            val ok = runCatching {
                if (!HandwritingModels.isDownloaded(tag)) HandwritingModels.download(context, tag)
            }.isSuccess
            outcome[tag] = names to ok
        }
        val used = outcome.filterValues { it.second }.values.flatMap { it.first }.toSet()
        withContext(Dispatchers.IO) {
            used.mapNotNull { manifest[it] }.forEach { File(dir, it.fileName).delete() }
        }
        return outcome
    }

    /** Whether every pack [tag]'s model needs is waiting in the directory. Blocking. */
    internal fun covers(context: Context, tag: String): Boolean {
        val dir = directory(context)
        val packs = runCatching { HandwritingModelSizes.packs(context, tag) }.getOrDefault(emptyList())
        return packs.isNotEmpty() && packs.all { File(dir, it.fileName).isFile }
    }

    /**
     * The links to fetch [tag]'s packs from, as ML Kit itself would. Blocking.
     * A zinnia model is one file, which the offline import installs as the CJK
     * pack it is; a PaddleOCR one is two, its graph and its character list.
     */
    fun packUrls(context: Context, tag: String): List<String> =
        if (ZinniaModels.isTag(tag)) {
            listOfNotNull(ZinniaModels.packUrl(tag))
        } else if (InkOcrModels.isTag(tag)) {
            InkOcrModels.packUrls(tag)
        } else {
            runCatching { HandwritingModelSizes.packs(context, tag) }.getOrDefault(emptyList()).map { it.url }
        }

    /** Runs [block] with the downloader's internet and network gates open. */
    internal suspend fun <T> waiving(block: suspend () -> T): T {
        waivers.incrementAndGet()
        try {
            return block()
        } finally {
            waivers.decrementAndGet()
        }
    }

    // Called from ML Kit's own bytecode, rewritten at build time. The names
    // and descriptors are part of that contract: InkOfflineImportsTransform
    // spells them out, and InkPackHookTest checks the calls it replaces are
    // still there to replace.

    @JvmStatic
    fun build(builder: OkHttpClient.Builder): OkHttpClient = builder.addInterceptor(LocalPacks).build()

    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int {
        val waived = permission == Manifest.permission.INTERNET ||
            permission == Manifest.permission.ACCESS_NETWORK_STATE
        if (waived && waivers.get() > 0) return PackageManager.PERMISSION_GRANTED
        return ContextCompat.checkSelfPermission(context, permission)
    }

    @JvmStatic
    fun connectivityWaived(): Boolean = waivers.get() > 0

    /**
     * Answers a request for a pack that is waiting on disk, and lets every
     * other request through. While an import has the permission gate open, a
     * request it cannot answer is refused here in a build without the
     * permission, where OkHttp's own lookup would throw a SecurityException
     * the library does not expect.
     */
    private object LocalPacks : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            if (url.host == PACK_HOST && url.encodedPath.startsWith(PACK_PATH)) {
                val file = filesDir?.let { File(File(it, DIRECTORY), url.pathSegments.last()) }
                if (file != null && file.isFile) {
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(HTTP_OK)
                        .message("OK")
                        .header("Content-Length", file.length().toString())
                        .body(file.source().buffer().asResponseBody(ZIP, file.length()))
                        .build()
                }
            }
            if (waivers.get() > 0) InternetPermission.check()
            return chain.proceed(request)
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val HTTP_OK = 200
    private const val BUFFER_BYTES = 64 * 1024
    private val ZIP = "application/zip".toMediaType()
}
