package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learned gate's Kotlin port against its Rust original: the same fixed
 * case `confidence.rs` scores in `the_fixed_case_scores_what_the_kotlin_port_scores`
 * must come out the same here. A refit by `tools/wmlm/train_confidence.py`
 * rewrites both constant blocks and both expected numbers.
 */
class ConfidenceTest {

    private val fixed = Confidence.Case(
        margin = 2.3,
        topScore = 7.9,
        topEditCost = 0.105,
        topEdits = 1,
        topSlips = 1,
        topAccents = 0,
        topCompleted = 0,
        topInDictionary = true,
        topInLexicon = false,
        twoSources = false,
        mixesSlips = false,
        typedLength = 6,
        hasRunner = true,
        runnerEditCost = 1.6,
        candidates = 4,
        lmTop = -2.0f,
        lmBest = -2.0f,
        timing = 0.0,
        typedShadowed = false,
        lmTyped = Float.NaN,
        lmTypedRank = -1,
        lmEntropy = 4.2f,
        lmSecond = -3.5f,
        lmTypedPieces = -28f,
    )

    @Test
    fun theFixedCaseScoresWhatTheRustPortScores() {
        assertEquals(0.926967, Confidence.probability(fixed), 1e-4)
    }

    @Test
    fun aWiderLeadIsSurerAndTheThresholdsFollowTheSlider() {
        val narrow = Confidence.Case(
            0.3, 7.9, 0.105, 1, 0, 0, 0, true, false, false, false, 6, true, 1.6, 4, Float.NaN, Float.NaN, 0.0, false,
        )
        val wide = Confidence.Case(
            6.0, 7.9, 0.105, 1, 0, 0, 0, true, false, false, false, 6, true, 1.6, 4, Float.NaN, Float.NaN, 0.0, false,
        )
        assertTrue(Confidence.probability(wide) > Confidence.probability(narrow))
        assertEquals(0.8, Confidence.applyThreshold(4.0), 1e-9)
        assertTrue(Confidence.offerThreshold(4.0) < Confidence.applyThreshold(4.0))
        assertTrue(Confidence.applyThreshold(10.0) > Confidence.applyThreshold(1.5))
    }
}
