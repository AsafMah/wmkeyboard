// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.GlideCheckup
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glide check-up's fitter (issue #966 §5.5) on strokes the harness draws,
 * fed the way the keyboard feeds it: each stroke decoded once under the
 * current weights and once under every candidate, only the words kept.
 *
 * The synthetic user writes rare words — a uniform sample of the shipped
 * list below its 4,000 commonest — which is the case the issue measured on
 * real German swipes: the words people want are rare in the list, so at the
 * English frequency weight a common short word outvotes a well-drawn long
 * one, and a lower weight reads more of them right. The check-up should
 * find that from the strokes alone, on the frequency rung, after passing
 * over the vocabulary rung, where every cap is wider than the 17k list and
 * so changes nothing.
 */
class GlideCheckupFitTest {

    private companion object {
        const val SEED = 42L
        const val CASES = 120

        /** Words below this rank are the rare-word user's vocabulary. */
        const val COMMON = 4_000

        /** A list size large enough for every cap to be a candidate, so the rung is exercised. */
        const val PRETEND_LIST = 400_000
    }

    private fun load(name: String): List<Pair<String, Int>> {
        val candidates = listOf(File("dictionaries-src/$name"), File("app/dictionaries-src/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun aRareWordUserIsOfferedALowerFrequencyWeight() {
        val english = load("en.txt")
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val keys = GlideKeyMap.of(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
        val workspace = GlideWorkspace()
        val dictionary = Trie().apply { english.forEach { (w, f) -> insert(w, f) } }
        val sources = dictionary.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }

        val base = GlideBeam.Tuning.DEFAULT
        val candidates = GlideCheckup.candidates(base, PRETEND_LIST)
        assertTrue(
            "the vocabulary rung should be on the ladder for a large list",
            candidates.any { it.rung == GlideCheckup.Rung.VOCABULARY },
        )
        val beams = candidates.associate { it.id to GlideBeam(it.applyTo(base)) }
        val current = GlideBeam(base)
        fun top1(beam: GlideBeam, path: List<com.wasimaster.wmkeyboard.core.gesture.GesturePoint>): String =
            beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, workspace, 1).firstOrNull()?.word ?: ""

        // The rare-word user: every word past the common ones, equally likely.
        val rare = english.drop(COMMON).map { it.first to 1 }
        val corpus = SwipeCorpus(SEED, grid = grid)
        val checkup = GlideCheckup(null)
        val fingerprint = GlideCheckup.fingerprintOf(base, PRETEND_LIST)
        val currents = GlideCheckup.currents(base)
        val hits = HashMap<String, Int>()
        var currentHits = 0
        for (case in corpus.generate(rare, SwipeCorpus.Noise.TYPICAL, CASES)) {
            val want = case.intended.lowercase(Locale.ROOT)
            val read = top1(current, case.path)
            if (read == want) currentHits++
            val under = HashMap<String, String>()
            for ((id, beam) in beams) {
                val word = top1(beam, case.path)
                under[id] = word
                if (word == want) hits[id] = (hits[id] ?: 0) + 1
            }
            checkup.observe(
                fingerprint, candidates, currents,
                committed = case.intended, current = read, under = under,
                sample = GlideCheckup.profileOf(case.path, grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH, want),
            )
        }

        val report = StringBuilder("rare-word user, $CASES typical strokes\n")
        report.append("  current  ${pct(currentHits)}\n")
        for (c in candidates) report.append("  ${c.id.padEnd(14)} ${pct(hits[c.id] ?: 0)}\n")
        val proposal = checkup.proposal()
        report.append("proposal: ${proposal?.candidate ?: "none"}")
        proposal?.let {
            report.append(" ${it.currentRight} -> ${it.candidateRight} of ${it.strokes} (+${it.more} -${it.fewer})")
        }
        println(report)

        // Every cap is wider than the 17k list, so the caps read exactly what
        // the current weights read, and the rung is passed over.
        for (c in candidates.filter { it.rung == GlideCheckup.Rung.VOCABULARY }) {
            assertEquals("a cap wider than the list changed a reading", currentHits, hits[c.id] ?: 0)
        }
        assertNotNull("no change proposed to a rare-word user", proposal)
        assertEquals(GlideCheckup.Rung.FREQUENCY, proposal!!.candidate.rung)
        assertTrue("proposed a higher frequency weight to a rare-word user", proposal.candidate.freqWeight < base.freqWeight)
        // The expected effect is the two counts' difference, and it is real.
        assertEquals(proposal.candidateRight - proposal.currentRight, proposal.more - proposal.fewer)
        assertTrue(proposal.candidateRight > proposal.currentRight)
        assertEquals(CASES, proposal.strokes)
        assertEquals(CASES, checkup.report().strokes)
        // The profile read the five axes off every stroke drawn.
        val profile = checkup.report().profile
        assertEquals(CASES, profile.count)
        assertTrue("typical strokes cut corners", profile.cornerCut > 0.0)
        assertTrue("a typical stroke covers most of its word", profile.scale in 0.7..1.1)
    }

    private fun pct(hits: Int) = String.format(Locale.ROOT, "%.4f", hits / CASES.toDouble())
}
