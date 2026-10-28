package com.wasimaster.wmkeyboard.core.prediction

import java.io.File

/**
 * How much this user's typing bears out the context model: the one dial the
 * keyboard turns on it without retraining anything.
 *
 * Whenever the model's favourite among the strip's candidates differs from
 * the engine's own first choice, the reranker notes the pair, and the word
 * the user then commits settles it: the model's pick is a win for the model,
 * the engine's pick a loss, anything else no verdict. The model's weight in
 * the context rerank then follows its record, from half the shipped weight
 * for a model this user keeps overruling to one and a half times it for one
 * they keep agreeing with, starting at the shipped weight. A few hundred
 * bytes in `learning/context_adapter.json`; nothing the user typed is kept.
 */
class ContextAdapter(private val file: File?) {

    @Volatile
    private var wins = 0

    @Volatile
    private var losses = 0

    init {
        load()
    }

    /** The model's pick and the engine's, from the last strip where they differed. */
    @Volatile
    var disagreement: Pair<String, String>? = null

    /**
     * The user committed [word] (its key). A verdict only when it was one of
     * the two picks of the last disagreement; the pair is then spent.
     */
    fun noteCommitted(word: String) {
        val (model, engine) = disagreement ?: return
        disagreement = null
        val key = WordKey.of(word)
        when (key) {
            WordKey.of(model) -> wins++
            WordKey.of(engine) -> losses++
            else -> return
        }
        // Old evidence fades: the model's standing follows recent typing.
        if (wins + losses > HORIZON) {
            wins = wins * HORIZON / (wins + losses)
            losses = losses * HORIZON / (wins + losses + 1)
        }
        save()
    }

    /** The share of disagreements the model has won, with a weak prior at one half. */
    fun share(): Double = (wins + PRIOR).toDouble() / (wins + losses + 2 * PRIOR)

    /** The factor on the model's shipped rerank weight: 0.5 to 1.5. */
    fun weightFactor(): Double = (2.0 * share()).coerceIn(MIN_FACTOR, MAX_FACTOR)

    fun counts(): Pair<Int, Int> = wins to losses

    fun clear() {
        wins = 0
        losses = 0
        disagreement = null
        file?.delete()
    }

    private fun load() {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val w = Regex("\"wins\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val l = Regex("\"losses\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        wins = w
        losses = l
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText("{\"wins\": $wins, \"losses\": $losses}\n")
        }
    }

    companion object {
        const val FILE_NAME = "learning/context_adapter.json"

        /** Verdicts kept before the counts are scaled down. */
        const val HORIZON = 200

        /** The prior's weight, in verdicts each way. */
        const val PRIOR = 4

        const val MIN_FACTOR = 0.5
        const val MAX_FACTOR = 1.5
    }
}
