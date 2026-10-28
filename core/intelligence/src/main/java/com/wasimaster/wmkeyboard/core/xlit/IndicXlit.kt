package com.wasimaster.wmkeyboard.core.xlit

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * IndicXlit's readings of a word typed on an Indic phonetic layout: what
 * someone typing `bhalobashi` most likely meant in Bengali letters, from a
 * network that learned it from millions of romanized words (see
 * [IndicXlitModels]).
 *
 * Runs on a thread of its own, never the one keys are handled on, and one
 * word at a time: [request] starts a word and drops the one before it if it
 * has not begun, since a keystroke has made it stale. The suggestion pass
 * waits for the answer only as long as its budget ([await]); a late answer
 * is kept for the next pass over the same word. Nothing here downloads
 * anything: without the model or the runtime every call answers nothing.
 *
 * The same class runs the other direction, [IndicXlitModels.LATIN], for
 * romanized dictation: [readingsOf] spells a whole phrase's native words in
 * Latin letters at once (কাজল → kajol, kajal).
 */
class IndicXlit(context: Context, private val pack: IndicXlitModels.Pack = IndicXlitModels.NATIVE) {

    private val dir: File = context.applicationContext.noBackupFilesDir
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wm-xlit").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val cache = LruCache<String, List<String>>(CACHE_WORDS)

    // Only touched on [worker].
    private var handle = 0L
    private var vocab: IndicXlitVocab? = null
    private var failedOpen = false

    @Volatile
    private var pending: Future<List<String>>? = null

    @Volatile
    private var pendingKey: String? = null

    /**
     * Starts reading [roman] for [languageId] (which must be one of the
     * pack's [IndicXlitModels.Pack.languages]); the answer is the readings
     * best first.
     * A request already waiting for the worker is cancelled.
     */
    fun request(languageId: String, roman: String): Future<List<String>> {
        val key = "$languageId\u0000$roman"
        cache.get(key)?.let { return CompletableFuture.completedFuture(it) }
        // The same word asked again while it is still running (the space bar
        // waiting on the strip's request): that one, rather than starting over.
        pending?.takeIf { pendingKey == key && !it.isCancelled }?.let { return it }
        pending?.cancel(false)
        val future = worker.submit<List<String>> {
            // Not cached when the model could not run: it may be here for the next word.
            run(languageId, roman)?.also { cache.put(key, it) }.orEmpty()
        }
        pending = future
        pendingKey = key
        return future
    }

    /**
     * What [future] holds within [budgetMs]. Empty when there are no readings,
     * the model is not here, or the budget ran out first.
     */
    fun await(future: Future<List<String>>, budgetMs: Long): List<String> = try {
        future.get(budgetMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        emptyList()
    } catch (_: CancellationException) {
        emptyList()
    } catch (_: ExecutionException) {
        emptyList()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        emptyList()
    }

    /**
     * The readings of each of [words] in [languageId], best first, as many
     * words as the worker gets through in [budgetMs]; a word it did not
     * reach, or has no reading for, is not in the map. One task for the lot,
     * so a phrase does not queue behind itself word by word. Blocking: call
     * it off the main thread.
     */
    fun readingsOf(languageId: String, words: Collection<String>, budgetMs: Long): Map<String, List<String>> {
        val found = ConcurrentHashMap<String, List<String>>()
        val todo = ArrayList<String>()
        for (w in words.distinct()) {
            val cached = cache.get("$languageId\u0000$w")
            if (cached == null) todo += w else if (cached.isNotEmpty()) found[w] = cached
        }
        if (todo.isEmpty()) return found
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val future = worker.submit(Runnable {
            for (w in todo) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) break
                val answers = run(languageId, w) ?: break
                cache.put("$languageId\u0000$w", answers)
                if (answers.isNotEmpty()) found[w] = answers
            }
        })
        try {
            future.get(budgetMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(false)
        } catch (_: CancellationException) {
        } catch (_: ExecutionException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return HashMap(found)
    }

    /** Opens the model now, on the worker, so the first request does not wait for it. */
    fun prepare() {
        worker.execute { open() }
    }

    /** Frees the model; the next request opens it again. */
    fun release() {
        pending?.cancel(false)
        cache.evictAll()
        worker.execute { close() }
    }

    /** For good: the service is going away. */
    fun shutdown() {
        pending?.cancel(false)
        worker.execute { close() }
        worker.shutdown()
    }

    /** The readings of [word], or null when the model is not here to ask. */
    private fun run(languageId: String, word: String): List<String>? {
        if (!pack.supports(languageId) || word.isEmpty() || word.length > MAX_LETTERS) return emptyList()
        if (!open()) return null
        val v = vocab ?: return null
        val src = v.encode(languageId, word) ?: return emptyList()
        val packed = XlitNative.nativeTransliterate(handle, src, v.allowed(languageId), BEAM, 2 * word.length + 6)
            ?: return null
        return IndicXlitVocab.unpack(packed, v).map { it.first }.filter { it.isNotEmpty() }.distinct()
    }

    private fun open(): Boolean {
        if (handle != 0L) return true
        if (failedOpen || !pack.isDownloaded(dir) || !XlitNative.load()) return false
        return try {
            vocab = pack.file(dir, pack.vocab).useLines { IndicXlitVocab.parse(it, pack) }
            handle = XlitNative.nativeOpen(
                pack.file(dir, pack.encoder).path,
                pack.file(dir, pack.decoder).path,
                THREADS,
            )
            if (handle == 0L) failedOpen = true
            handle != 0L
        } catch (e: Exception) {
            Log.w(TAG, "could not open IndicXlit", e)
            failedOpen = true
            false
        }
    }

    private fun close() {
        if (handle != 0L) XlitNative.nativeClose(handle)
        handle = 0L
        vocab = null
        failedOpen = false
    }

    companion object {
        private const val TAG = "IndicXlit"

        /**
         * Four readings a word. The strip takes three at most, and measured on
         * held-out words a wider beam moves top-4 by about a point for half as
         * much time again.
         */
        const val BEAM = 4

        /** One thread: the keyboard's own work matters more than this. */
        private const val THREADS = 1

        /** Words longer than this are pasted text, not typing (or a recognizer's run-on). */
        private const val MAX_LETTERS = 32

        private const val CACHE_WORDS = 256
    }
}
