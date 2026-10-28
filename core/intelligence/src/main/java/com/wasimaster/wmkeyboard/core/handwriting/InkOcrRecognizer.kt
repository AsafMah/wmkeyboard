package com.wasimaster.wmkeyboard.core.handwriting

import com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One [InkOcrModels.Pack], open: the recogniser [HandwritingRecognizerCache]
 * holds for an ink tag, in place of ML Kit's.
 *
 * The ink is drawn as a line of text ([InkOcrInk]), cut into recogniser-wide
 * pieces where it is emptiest ([PaddleOcrPipeline.cuts], as the scanner cuts
 * a long line), and each piece run through the graph on MNN
 * ([ImageGenNative], the same library the scanner and the sticker generator
 * run on). The pieces' steps, end to end, go to [InkOcrDecoder], which
 * weighs what they read against [lexicon].
 *
 * A read is a few tens of milliseconds of arithmetic per piece. It runs on
 * [Dispatchers.Default]; calls on the graph never overlap (MNN's handle is
 * not safe to share), and [close] waits for the one in flight.
 */
internal class InkOcrRecognizer private constructor(
    private val pack: InkOcrModels.Pack,
    private var handle: Long,
    charset: List<String>,
    private val steps: Int,
) : InkRuntime.Recognizer {

    private val lock = Any()
    private val classes = charset.size
    private val decoder = InkOcrDecoder(charset, pack.folds, pack.rightToLeft, pack.cased)

    /** The keyboard's word lists, set by [HandwritingRecognizerCache] before each read; null reads plainly. */
    @Volatile
    var lexicon: InkLexicon? = null

    override suspend fun recognize(
        strokes: List<HwStroke>,
        preContext: String,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
    ): List<String> = withContext(Dispatchers.Default) {
        val strip = InkOcrInk.render(strokes, writingAreaHeight) ?: return@withContext emptyList()
        val probs = run(strip) ?: return@withContext emptyList()
        decoder.read(
            probs = probs,
            steps = probs.size / classes,
            classes = classes,
            lexicon = lexicon.takeIf { pack.lexicon },
            previousWord = previousWord(preContext),
            limit = CANDIDATES,
        )
    }

    /** Every piece of [strip] through the graph, their steps end to end; null once closed. */
    private fun run(strip: PaddleOcrPipeline.Strip): FloatArray? {
        val cuts = PaddleOcrPipeline.cuts(strip)
        val input = FloatArray(PaddleOcrPipeline.REC_HEIGHT * PaddleOcrPipeline.REC_WIDTH * 3)
        val out = FloatArray((cuts.size - 1) * steps * classes)
        for (i in 0 until cuts.size - 1) {
            PaddleOcrPipeline.recognizerInput(strip, cuts[i], cuts[i + 1], input)
            val piece = synchronized(lock) {
                if (handle == 0L) return null
                ImageGenNative.nativeRun(handle, arrayOf(input), arrayOf(SHAPE), null)
            } ?: return null
            if (piece.size != steps * classes) return null
            piece.copyInto(out, i * steps * classes)
        }
        return out
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) ImageGenNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        /** Readings handed back per read; the panel shows the first few. */
        private const val CANDIDATES = 6

        private const val INPUT = "image"
        private const val OUTPUT = "probs"
        private val SHAPE = intArrayOf(1, PaddleOcrPipeline.REC_HEIGHT, PaddleOcrPipeline.REC_WIDTH, 3)
        private const val THREADS = 2

        /** The word just before the caret, for the lexicon's context: letters only, or null. */
        fun previousWord(preContext: String): String? =
            preContext.trimEnd().takeLastWhile { it.isLetter() || it == '\'' || it.isSurrogate() }
                .trim('\'')
                .takeIf { it.isNotEmpty() }

        /**
         * Opens [pack]'s graph and character list; null when the library is
         * not here, a file will not load, or the two do not belong together
         * (the graph's classes are the list plus blank and space).
         */
        fun open(pack: InkOcrModels.Pack, model: File, keys: File): InkOcrRecognizer? {
            if (!ImageGenNative.available) return null
            val charset = runCatching { PaddleOcrPipeline.charset(keys.readText()) }.getOrNull() ?: return null
            val handle = ImageGenNative.nativeLoad(model.path, arrayOf(INPUT), arrayOf(OUTPUT), THREADS)
            if (handle == 0L) return null
            val shape = IntArray(3)
            val probe = try {
                ImageGenNative.nativeRun(handle, arrayOf(FloatArray(SHAPE.reduce(Int::times))), arrayOf(SHAPE), shape)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                null
            }
            if (probe == null || shape[1] <= 0 || shape[2] != charset.size || probe.size != shape[1] * shape[2]) {
                ImageGenNative.nativeFree(handle)
                return null
            }
            return InkOcrRecognizer(pack, handle, charset, shape[1])
        }
    }
}
