package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The glide check-up's rules (issue #966 §5.5), on hand-made records so
 * each one is tested on its own: when it proposes, when it refuses, and what
 * a confirmation window counts. The fitter on real decodes is in
 * `GlideCheckupFitTest`.
 */
class GlideCheckupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tuning = GlideBeam.Tuning.DEFAULT.copy(vocabularyRank = 300_000)
    private val size = 400_000
    private val fingerprint = GlideCheckup.fingerprintOf(tuning, size)
    private val candidates = GlideCheckup.candidates(tuning, size)
    private val currents = GlideCheckup.currents(tuning)
    /** The caps in ladder order: "everything" is nearest a 300k cap on a 400k list, then 150k, then 50k. */
    private val everything = candidates.first { it.id == GlideCheckup.vocabularyId(0) }
    private val medium = candidates.first { it.id == GlideCheckup.vocabularyId(150_000) }
    private val half = candidates.first { it.id == GlideCheckup.frequencyId(0.5) }

    private fun checkup() = GlideCheckup(File(temp.newFolder(), "glide_checkup.json"))

    /**
     * One swipe for [word]: the current weights read [current], and every
     * candidate reads the word itself except where [wrong] names it.
     */
    private fun GlideCheckup.swipe(word: String, current: String = word, wrong: Set<String> = emptySet()) {
        observe(
            fingerprint, candidates, currents, committed = word, current = current,
            under = candidates.associate { it.id to if (it.id in wrong) "xx$word" else word },
        )
    }

    @Test
    fun nothingIsProposedUnderThirtyStrokes() {
        val checkup = checkup()
        // Every candidate would read every stroke right and the current
        // weights none: the strongest evidence there is, still too little.
        repeat(GlideCheckup.MIN_STROKES - 1) { checkup.swipe("word$it", current = "other") }
        assertNull(checkup.proposal())
        checkup.swipe("last", current = "other")
        assertNotNull(checkup.proposal())
    }

    @Test
    fun theVocabularyRungIsTriedFirstAndAWinnerThereStopsTheLadder() {
        val checkup = checkup()
        repeat(40) { checkup.swipe("word$it", current = "other") }
        val proposal = checkup.proposal()!!
        assertEquals(GlideCheckup.Rung.VOCABULARY, proposal.candidate.rung)
        // A tie goes to the candidate nearest the current value, which on a
        // 400k list under a 300k cap is the whole list.
        assertEquals(everything.id, proposal.candidate.id)
        assertEquals(currents.first { it.rung == GlideCheckup.Rung.VOCABULARY }.id, proposal.from.id)
    }

    @Test
    fun aRungWithNothingToWinIsPassedOver() {
        val checkup = checkup()
        val vocabulary = candidates.filter { it.rung == GlideCheckup.Rung.VOCABULARY }.map { it.id }.toSet()
        // The caps read exactly what the current weights read; the frequency
        // candidates read everything right.
        repeat(40) { checkup.swipe("word$it", current = "other", wrong = vocabulary) }
        val proposal = checkup.proposal()!!
        assertEquals(GlideCheckup.Rung.FREQUENCY, proposal.candidate.rung)
    }

    @Test
    fun aGainOnTheFittingHalfAloneIsNotEnough() {
        val checkup = checkup()
        // Even-numbered strokes (the fitting half) favour every candidate;
        // odd-numbered ones (held out) favour none. A fit that does not
        // transfer is exactly what the split exists to catch.
        repeat(40) { i ->
            if (i % 2 == 0) checkup.swipe("word$i", current = "other") else checkup.swipe("word$i")
        }
        assertNull("proposed on a gain the held-out half did not confirm", checkup.proposal())
    }

    @Test
    fun theExpectedEffectCountsBothDirections() {
        val checkup = checkup()
        val others = candidates.filter { it.id != medium.id }.map { it.id }.toSet()
        repeat(40) { i ->
            when {
                // The current weights miss, the medium cap gets it: "more".
                i % 8 != 7 -> checkup.swipe("word$i", current = "other", wrong = others)
                // The current weights get it, the medium cap misses: "fewer".
                else -> checkup.swipe("word$i", wrong = setOf(medium.id))
            }
        }
        val proposal = checkup.proposal()!!
        assertEquals(medium.id, proposal.candidate.id)
        assertEquals(40, proposal.strokes)
        assertEquals(5, proposal.currentRight)
        assertEquals(35, proposal.candidateRight)
        assertEquals(35, proposal.more)
        assertEquals(5, proposal.fewer)
    }

    @Test
    fun aDeclinedCandidateIsNotOfferedAgain() {
        val checkup = checkup()
        repeat(40) { checkup.swipe("word$it", current = "other") }
        checkup.decline(everything.id)
        // Still on the vocabulary rung: the next cap along.
        assertEquals(medium.id, checkup.proposal()!!.candidate.id)
        checkup.reset()
        repeat(40) { checkup.swipe("word$it", current = "other") }
        assertEquals(everything.id, checkup.proposal()!!.candidate.id)
    }

    @Test
    fun aSettingThatMovedEmptiesTheEvidence() {
        val checkup = checkup()
        repeat(40) { checkup.swipe("word$it", current = "other") }
        assertNotNull(checkup.proposal())
        // The user moved the cap by hand: a new baseline, and the proposal
        // measured against the old one is gone with it.
        val moved = tuning.copy(vocabularyRank = 50_000)
        checkup.observe(
            GlideCheckup.fingerprintOf(moved, size), GlideCheckup.candidates(moved, size), GlideCheckup.currents(moved),
            committed = "fresh", current = "fresh", under = emptyMap(),
        )
        assertNull(checkup.proposal())
        assertEquals(1, checkup.report().strokes)
    }

    @Test
    fun aCorrectionRewritesTheNewestRecordForThatWord() {
        val checkup = checkup()
        // The stroke read "form"; one candidate read "from". The user then
        // took "from" off the strip.
        checkup.observe(
            fingerprint, candidates, currents, committed = "form", current = "form",
            under = candidates.associate { it.id to if (it.id == half.id) "from" else "form" },
        )
        checkup.correct("form", "from")
        val report = checkup.report()
        assertEquals(0, report.currentRight)
        // A word taken back with nothing in its place scores nobody.
        checkup.observe(fingerprint, candidates, currents, "the", "the", candidates.associate { it.id to "the" })
        checkup.correct("the", null)
        assertEquals(0, checkup.report().currentRight)
        // A correction of a word never measured changes nothing.
        checkup.correct("never", "ever")
        assertEquals(2, checkup.report().strokes)
    }

    @Test
    fun applyingOpensAWindowThatCountsCorrectionsBeforeAndAfter() {
        val checkup = checkup()
        repeat(40) { checkup.swipe("word$it") }
        checkup.correct("word3", "fixed3")
        checkup.correct("word7", "fixed7")
        checkup.applied(medium.id, "LARGE")
        assertNull("proposed while a change is being confirmed", checkup.proposal())
        val opened = checkup.report().applied!!
        assertEquals(40, opened.strokesBefore)
        assertEquals(2, opened.correctionsBefore)
        assertFalse(opened.done)
        // The new weights carry a new fingerprint: these are the "after".
        val after = medium.applyTo(tuning)
        val afterPrint = GlideCheckup.fingerprintOf(after, size)
        repeat(GlideCheckup.CONFIRM_WINDOW) { i ->
            checkup.observe(
                afterPrint, GlideCheckup.candidates(after, size), GlideCheckup.currents(after),
                "after$i", "after$i", emptyMap(),
            )
            if (i % 5 == 0) checkup.correct("after$i", "else$i")
        }
        val full = checkup.report().applied!!
        assertTrue(full.done)
        assertEquals(GlideCheckup.CONFIRM_WINDOW, full.strokesAfter)
        assertEquals(8, full.correctionsAfter)
        assertTrue("one in five corrected against one in twenty reads as worse", full.worse)
        checkup.settle()
        assertNull(checkup.report().applied)
        // The records under the kept weights are the next cycle's evidence.
        assertEquals(GlideCheckup.CONFIRM_WINDOW, checkup.report().strokes)
    }

    @Test
    fun theWindowOnlyCountsUntilItIsFull() {
        val checkup = checkup()
        checkup.applied(medium.id, "LARGE")
        repeat(GlideCheckup.CONFIRM_WINDOW + 10) { checkup.swipe("w$it") }
        assertEquals(GlideCheckup.CONFIRM_WINDOW, checkup.report().applied!!.strokesAfter)
    }

    @Test
    fun theEvidenceSurvivesARestart() {
        val file = File(temp.newFolder(), "glide_checkup.json")
        GlideCheckup(file).apply {
            repeat(40) { swipe("word$it", current = "other") }
            decline(everything.id)
            save()
        }
        val reopened = GlideCheckup(file)
        assertEquals(40, reopened.report().strokes)
        // Declined before the restart, and still declined after.
        assertEquals(medium.id, reopened.proposal()!!.candidate.id)
    }

    @Test
    fun aStorelessCheckupStillWorks() {
        val checkup = GlideCheckup(null)
        repeat(40) { checkup.swipe("word$it", current = "other") }
        assertNotNull(checkup.proposal())
        checkup.save()
        checkup.reload()
        assertTrue(checkup.isEmpty())
    }

    @Test
    fun candidatesSkipCapsThatCutNothing() {
        // A 17k list: every cap is wider than the list, so none is offered.
        val small = GlideCheckup.candidates(tuning, 17_000)
        assertTrue(small.none { it.rung == GlideCheckup.Rung.VOCABULARY })
        assertEquals(GlideCheckup.FREQ_WEIGHTS.size - 1, small.size)
        // A 200k list under a 300k cap: 150k and 50k cut it, "everything" does not.
        val big = GlideCheckup.candidates(tuning, 200_000)
        assertEquals(
            listOf(GlideCheckup.vocabularyId(150_000), GlideCheckup.vocabularyId(50_000)),
            big.filter { it.rung == GlideCheckup.Rung.VOCABULARY }.map { it.id },
        )
        // No list size known: nothing about caps can be said.
        assertTrue(GlideCheckup.candidates(tuning, 0).none { it.rung == GlideCheckup.Rung.VOCABULARY })
        // The current weight is never its own candidate.
        assertTrue(big.none { it.id == GlideCheckup.frequencyId(tuning.freqWeight) })
    }

    @Test
    fun theProfileReadsTheFiveAxesOffAStroke() {
        // A three-key grid in a row, keys 60 px apart, and the word "abc".
        val keys = listOf(
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('a', 0f, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('b', 60f, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('c', 120f, 0f),
        )
        // Drawn straight through the key centres, shifted 18 px right and
        // 12 px down, starting 6 px late and stopping 30 px early.
        val points = listOf(
            GesturePoint(6f + 18f, 12f),
            GesturePoint(60f + 18f, 12f),
            GesturePoint(90f + 18f, 12f),
        )
        val profile = GlideCheckup.profileOf(points, keys, 60f, "abc")!!
        assertEquals(1, profile.count)
        // Centroids: the drawn path's is 10 px right of the letters' and 12 px below.
        assertEquals(0.17, profile.offsetX, 0.02)
        assertEquals(0.2, profile.offsetY, 0.01)
        // The span drawn (84 px) against the ideal (120 px).
        assertEquals(0.7, profile.scale, 0.01)
        // 12 px short of c and 12 px low: 17 px, a little over a quarter key.
        assertEquals(0.28, profile.earlyLift, 0.02)
        // 24 px past a and 12 px low.
        assertEquals(0.45, profile.startDrift, 0.02)
        // The path's nearest sample to b sits 18 px right and 12 px low of it.
        assertEquals(0.36, profile.cornerCut, 0.02)
        // A letter the grid lacks, or a one-key word, profiles nothing.
        assertNull(GlideCheckup.profileOf(points, keys, 60f, "abd"))
        assertNull(GlideCheckup.profileOf(points, keys, 60f, "aa"))
        // Running means: a second identical stroke changes nothing.
        val twice = profile.plus(profile)
        assertEquals(2, twice.count)
        assertEquals(profile.scale, twice.scale, 1e-9)
    }
}
