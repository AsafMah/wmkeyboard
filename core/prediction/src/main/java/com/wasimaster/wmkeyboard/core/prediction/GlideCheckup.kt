package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The glide check-up (issue #966 §5.5): fits the decoder's two language-level
 * weights to the user's own swipes, and proposes one change at a time.
 *
 * Everything the decoder does with a stroke is a number someone chose. Two of
 * those numbers depend on what the user writes rather than on the keyboard:
 * how much of the dictionary a swipe may answer with
 * ([GlideBeam.Tuning.vocabularyRank]) and how much a word's frequency counts
 * against the stroke's shape ([GlideBeam.Tuning.freqWeight]). The reporter's
 * sweeps on real German swipes put the optimum of the second at 0.5 for
 * Wikipedia-like text and 0.4 for rare words, which is why the value should
 * be fitted per user and not only per language ([com.wasimaster.wmkeyboard.core.gesture.GlideLanguageProfiles]
 * holds the per-language value this fits on top of). The geometric knobs are
 * not on the ladder: the same sweeps found every radius and `sigma` at its
 * optimum already, with nothing for a per-user fit to win.
 *
 * **How it measures.** The keyboard does not keep strokes beyond the two
 * dozen `GlideReadings` holds for a field, so the check-up cannot replay a
 * store of them later. It replays each stroke *once*, right after the word
 * is typed and off the keyboard's thread: the stroke is decoded again under
 * every candidate value, and only the words that came out are kept — one
 * per candidate — beside the word the user ended with. A correction (a
 * reading taken off the strip, a full-search pick, a backspace and retype)
 * rewrites that last word. Nothing here is a coordinate; a record is a
 * handful of words and a flag, and [CAPACITY] of them is the most that is
 * ever held.
 *
 * **How it judges.** Success per swipe: a candidate scores a record when its
 * word is the one the user ended with. The records are split by parity into
 * a fitting half and a held-out half. The best candidate on the fitting half
 * is proposed only if it also beats the current value on the held-out half,
 * by at least [MIN_GAIN_STROKES] strokes and [MIN_GAIN_SHARE] of them, and
 * only once [MIN_STROKES] records exist. Rungs are tried in the issue's
 * order: the vocabulary cap first, then the frequency weight. A rung with
 * nothing to win is passed over, not reported.
 *
 * **What it refuses.** Evidence is tied to the weights it was measured
 * against ([fingerprint]). A setting that moves by hand changes the
 * fingerprint and empties the records: a proposal measured against a
 * baseline that no longer exists is not offered, which is the issue's "a
 * proposal whose setting moved is refused". A candidate the user turned down
 * is not offered again until they start over.
 *
 * **How it confirms.** Applying a change starts a window of
 * [CONFIRM_WINDOW] swipes. The rate at which swiped words were corrected
 * over the swipes before the change is kept, and the same rate is counted
 * after it; once the window is full the two are reported side by side and
 * the settings screen offers to keep or undo. Nothing reverts on its own.
 *
 * Storage follows [GlideSandboxLadder]: nullable file (direct boot: memory
 * only), dirty-flag save, [reload] for external edits.
 */
class GlideCheckup(private val storageFile: File?) {

    /** Which weight a candidate moves. In the order the ladder tries them. */
    enum class Rung { VOCABULARY, FREQUENCY }

    /**
     * One value the user's strokes are replayed under. [id] is stable and is
     * what a record and a decision name the candidate by.
     */
    @Serializable
    data class Candidate(val rung: Rung, val id: String, val vocabularyRank: Int, val freqWeight: Double) {

        /** [tuning] with this candidate's one weight on it. */
        fun applyTo(tuning: GlideBeam.Tuning): GlideBeam.Tuning = when (rung) {
            Rung.VOCABULARY -> tuning.copy(vocabularyRank = vocabularyRank)
            Rung.FREQUENCY -> tuning.copy(freqWeight = freqWeight)
        }

        override fun toString(): String = id
    }

    /**
     * One swipe, as kept: the word the user ended with ([word], null once a
     * backspace took the word away with nothing typed in its place), whether
     * they corrected it, what the current weights read, and what each
     * candidate read. Words are keyed by [WordKey] so case never splits a
     * hit.
     */
    @Serializable
    data class Record(
        val word: String?,
        val corrected: Boolean,
        val current: String,
        val under: Map<String, String>,
    )

    /**
     * How this user draws, as running means over the swipes measured. All in
     * key widths except [scale], a ratio. The five axes `SwipeCorpus` models,
     * read back off real strokes: how far inside a corner the finger cuts,
     * where it sits relative to the keys on average, how much of the ideal
     * path it covers, how far short of the last key it lifts, and how far
     * from the first key it starts.
     */
    @Serializable
    data class Profile(
        val count: Int = 0,
        val cornerCut: Double = 0.0,
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
        val scale: Double = 1.0,
        val earlyLift: Double = 0.0,
        val startDrift: Double = 0.0,
    ) {
        /** This profile with one more stroke's [sample] folded into its means. */
        fun plus(sample: Profile): Profile {
            val n = count + 1
            fun mean(old: Double, new: Double) = old + (new - old) / n
            return Profile(
                count = n,
                cornerCut = mean(cornerCut, sample.cornerCut),
                offsetX = mean(offsetX, sample.offsetX),
                offsetY = mean(offsetY, sample.offsetY),
                scale = mean(scale, sample.scale),
                earlyLift = mean(earlyLift, sample.earlyLift),
                startDrift = mean(startDrift, sample.startDrift),
            )
        }
    }

    /**
     * The one change the check-up proposes, with its expected effect on the
     * user's own swipes: over all [strokes] measured, the current weights
     * read [currentRight] of them right and [candidate] would read
     * [candidateRight]; [more] is the strokes it would fix and [fewer] the
     * ones it would break. [from] is the current value the proposal was
     * measured against, in the candidate's own unit, so a screen can refuse
     * to apply it over a value that moved.
     */
    class Proposal(
        val candidate: Candidate,
        val from: Candidate,
        val strokes: Int,
        val currentRight: Int,
        val candidateRight: Int,
        val more: Int,
        val fewer: Int,
    )

    /**
     * The change in force, and how the swipes since compare with the swipes
     * before it. [strokesAfter] grows until [CONFIRM_WINDOW]; [done] says the
     * window is full and [worse] that the correction rate rose.
     */
    @Serializable
    data class Applied(
        val candidateId: String,
        val fromId: String,
        val strokesBefore: Int,
        val correctionsBefore: Int,
        val strokesAfter: Int = 0,
        val correctionsAfter: Int = 0,
    ) {
        val done: Boolean get() = strokesAfter >= CONFIRM_WINDOW

        /** Corrections per swipe, before and after, as rates in 0..1. */
        val rateBefore: Double get() = if (strokesBefore == 0) 0.0 else correctionsBefore / strokesBefore.toDouble()
        val rateAfter: Double get() = if (strokesAfter == 0) 0.0 else correctionsAfter / strokesAfter.toDouble()

        /** Whether the change made the user correct more, by more than one swipe in the window. */
        val worse: Boolean get() = done && rateAfter > rateBefore + WORSE_MARGIN
    }

    /** Everything the settings screen shows, read in one go. */
    class Report(
        val strokes: Int,
        val currentRight: Int,
        val profile: Profile,
        val proposal: Proposal?,
        val applied: Applied?,
        val fingerprint: String,
    )

    @Serializable
    private data class Snapshot(
        val version: Int = VERSION,
        val fingerprint: String = "",
        val candidates: List<Candidate> = emptyList(),
        val currents: List<Candidate> = emptyList(),
        val records: List<Record> = emptyList(),
        val declined: Set<String> = emptySet(),
        val profile: Profile = Profile(),
        val applied: Applied? = null,
    )

    private var fingerprint = ""

    /** The candidates the records were measured under, and the current value on each rung. */
    private var candidates: List<Candidate> = emptyList()
    private var currents: List<Candidate> = emptyList()
    private val records = ArrayDeque<Record>()
    private val declined = HashSet<String>()
    private var profile = Profile()
    private var applied: Applied? = null
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /**
     * One swipe replayed. [current] is what the weights in force read,
     * [committed] the word that went into the field, [under] each
     * candidate's reading by id, [fingerprint] the baseline those readings
     * were measured against (see [fingerprintOf]), [candidates] the ladder
     * replayed and [currents] the value in force on each rung. A different
     * baseline from the records' empties them first.
     */
    @Synchronized
    fun observe(
        fingerprint: String,
        candidates: List<Candidate>,
        currents: List<Candidate>,
        committed: String,
        current: String,
        under: Map<String, String>,
        sample: Profile? = null,
    ) {
        if (fingerprint != this.fingerprint) {
            records.clear()
            this.fingerprint = fingerprint
            this.candidates = candidates
            this.currents = currents
        }
        records.addLast(Record(committed, corrected = false, current = current, under = under))
        while (records.size > CAPACITY) records.removeFirst()
        if (sample != null) profile = profile.plus(sample)
        applied?.let { if (!it.done) applied = it.copy(strokesAfter = it.strokesAfter + 1) }
        dirty = true
    }

    /**
     * The user replaced the swiped [rejected] with [chosen], or took it away
     * (null). Rewrites the newest record for that word; a word no record
     * holds was swiped before measuring began, or has already left the
     * window, and teaches nothing.
     */
    @Synchronized
    fun correct(rejected: String, chosen: String?) {
        val key = WordKey.of(rejected)
        if (key.isEmpty()) return
        for (i in records.indices.reversed()) {
            val record = records[i]
            if (record.word == null || WordKey.of(record.word) != key) continue
            records[i] = record.copy(word = chosen, corrected = true)
            applied?.let { if (!it.done) applied = it.copy(correctionsAfter = it.correctionsAfter + 1) }
            dirty = true
            return
        }
    }

    /** The user turned [candidate] down: not offered again until [reset]. */
    @Synchronized
    fun decline(candidateId: String) {
        if (declined.add(candidateId)) dirty = true
    }

    /**
     * The user applied the candidate [candidateId]; [fromId] is whatever the
     * screen needs to put the old value back (the setting's own value, not
     * a candidate id). Starts the confirmation window: the records so far
     * are the "before", and the new weights carry a new fingerprint, so the
     * next [observe] starts the records over as the "after".
     */
    @Synchronized
    fun applied(candidateId: String, fromId: String) {
        val before = records.toList().takeLast(CONFIRM_WINDOW)
        applied = Applied(
            candidateId = candidateId,
            fromId = fromId,
            strokesBefore = before.size,
            correctionsBefore = before.count { it.corrected },
        )
        dirty = true
    }

    /**
     * The confirmation is answered, kept or undone. The records measured
     * under the applied weights stay: if the user kept the change they are
     * the next cycle's evidence, and if they undid it the fingerprint will
     * not match and the next swipe empties them.
     */
    @Synchronized
    fun settle() {
        if (applied == null) return
        applied = null
        dirty = true
    }

    /** Back to nothing measured, nothing declined, nothing in force. */
    @Synchronized
    fun reset() {
        fingerprint = ""
        candidates = emptyList()
        currents = emptyList()
        records.clear()
        declined.clear()
        profile = Profile()
        applied = null
        dirty = true
    }

    @Synchronized
    fun isEmpty(): Boolean = records.isEmpty() && declined.isEmpty() && profile.count == 0 && applied == null

    /** What the settings screen shows. */
    @Synchronized
    fun report(): Report = Report(
        strokes = records.size,
        currentRight = records.count { hit(it.word, it.current) },
        profile = profile,
        proposal = proposal(),
        applied = applied,
        fingerprint = fingerprint,
    )

    /**
     * The one change worth proposing, or null: fewer than [MIN_STROKES]
     * records, a change still being confirmed, or no candidate that beats
     * the current weights on strokes the fit never saw.
     */
    @Synchronized
    fun proposal(): Proposal? {
        if (applied != null || records.size < MIN_STROKES) return null
        val all = records.toList()
        val fit = all.filterIndexed { i, _ -> i % 2 == 0 }
        val held = all.filterIndexed { i, _ -> i % 2 == 1 }
        for (rung in Rung.entries) {
            val current = currents.firstOrNull { it.rung == rung } ?: continue
            val onRung = candidates.filter { it.rung == rung && it.id !in declined && it.id != current.id }
            if (onRung.isEmpty()) continue
            // Best on the fitting half; the first of a tie, which is the
            // smaller change since the ladder lists them nearest first.
            val best = onRung.maxByOrNull { c -> fit.count { hit(it.word, it.under[c.id]) } } ?: continue
            val heldGain = held.count { hit(it.word, it.under[best.id]) } - held.count { hit(it.word, it.current) }
            if (heldGain < MIN_GAIN_STROKES || heldGain < MIN_GAIN_SHARE * held.size) continue
            var more = 0
            var fewer = 0
            var currentRight = 0
            var candidateRight = 0
            for (record in all) {
                val now = hit(record.word, record.current)
                val then = hit(record.word, record.under[best.id])
                if (now) currentRight++
                if (then) candidateRight++
                if (then && !now) more++
                if (now && !then) fewer++
            }
            return Proposal(best, current, all.size, currentRight, candidateRight, more, fewer)
        }
        return null
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                fingerprint = fingerprint,
                candidates = candidates,
                currents = currents,
                records = records.toList(),
                declined = declined.toSet(),
                profile = profile,
                applied = applied,
            )
        }
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app replaced or deleted it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        fingerprint = ""
        candidates = emptyList()
        currents = emptyList()
        records.clear()
        declined.clear()
        profile = Profile()
        applied = null
        load()
        dirty = false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        val snapshot = runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull() ?: return
        if (snapshot.version != VERSION) return
        fingerprint = snapshot.fingerprint
        candidates = snapshot.candidates
        currents = snapshot.currents
        snapshot.records.takeLast(CAPACITY).forEach { records.addLast(it) }
        declined.addAll(snapshot.declined)
        profile = snapshot.profile
        applied = snapshot.applied
    }

    companion object {
        private const val VERSION = 1

        /** Swipes measured before anything is proposed (issue #966 §5.5). */
        const val MIN_STROKES = 30

        /** Records kept at most; the oldest go first. */
        const val CAPACITY = 200

        /** Swipes after a change before its confirmation is judged. */
        const val CONFIRM_WINDOW = 40

        /**
         * On the held-out half, the least a candidate must read right beyond
         * the current weights: in strokes, and as a share of that half. Two
         * strokes is one swipe in fifteen at the minimum evidence; three
         * points is the smallest gain the issue's own tables treat as real.
         */
        const val MIN_GAIN_STROKES = 2
        const val MIN_GAIN_SHARE = 0.03

        /** How much the correction rate may rise after a change before it reads as worse. */
        const val WORSE_MARGIN = 0.025

        /** The vocabulary caps the ladder tries: every list, then the three the setting offers. */
        val VOCABULARY_RANKS = listOf(0, 300_000, 150_000, 50_000)

        /** The frequency weights the ladder tries, from the issue's sweep (§4.3). */
        val FREQ_WEIGHTS = listOf(1.0, 0.8, 0.6, 0.5, 0.4)

        private fun hit(word: String?, read: String?): Boolean =
            word != null && read != null && WordKey.of(word) == WordKey.of(read)

        /** A vocabulary candidate's id. */
        fun vocabularyId(rank: Int) = "vocab:$rank"

        /** A frequency candidate's id. */
        fun frequencyId(weight: Double) = "freq:$weight"

        /** The candidate [tuning] stands for on [rung]. */
        fun currentOn(rung: Rung, tuning: GlideBeam.Tuning): Candidate = when (rung) {
            Rung.VOCABULARY -> Candidate(rung, vocabularyId(tuning.vocabularyRank), tuning.vocabularyRank, tuning.freqWeight)
            Rung.FREQUENCY -> Candidate(rung, frequencyId(tuning.freqWeight), tuning.vocabularyRank, tuning.freqWeight)
        }

        /** The value in force on every rung, for [observe]. */
        fun currents(tuning: GlideBeam.Tuning): List<Candidate> = Rung.entries.map { currentOn(it, tuning) }

        /**
         * What the records were measured against: the two weights in force
         * and the size of the largest list, since a cap only cuts a list it
         * is smaller than. A change in any of them starts the evidence over.
         */
        fun fingerprintOf(tuning: GlideBeam.Tuning, vocabularySize: Int): String =
            "vocab=${tuning.vocabularyRank};freq=${tuning.freqWeight};list=$vocabularySize"

        /**
         * The candidates worth replaying [tuning]'s strokes under, in ladder
         * order, nearest the current value first. A vocabulary cap that
         * would cut the same words as the current one (both wider than the
         * list, say) is left out, so a user on a small list is never asked
         * to change a cap that changes nothing.
         */
        fun candidates(tuning: GlideBeam.Tuning, vocabularySize: Int): List<Candidate> {
            fun effective(rank: Int) = if (rank == 0 || vocabularySize == 0) vocabularySize else min(rank, vocabularySize)
            val currentCut = effective(tuning.vocabularyRank)
            val vocabulary = VOCABULARY_RANKS
                .filter { it != tuning.vocabularyRank && effective(it) != currentCut }
                .sortedBy { kotlin.math.abs(effective(it) - currentCut) }
                .map { Candidate(Rung.VOCABULARY, vocabularyId(it), it, tuning.freqWeight) }
            val frequency = FREQ_WEIGHTS
                .filter { it != tuning.freqWeight }
                .sortedBy { kotlin.math.abs(it - tuning.freqWeight) }
                .map { Candidate(Rung.FREQUENCY, frequencyId(it), tuning.vocabularyRank, it) }
            return vocabulary + frequency
        }

        /**
         * The five axes of one stroke drawn for [word] on [keys], in key
         * widths, or null when the word has a letter the grid cannot place
         * or fewer than two keys. Pure geometry against the word's ideal
         * polyline (its key centres, consecutive repeats collapsed): no
         * alignment, so a stroke the decoder misread profiles the same as
         * one it read right.
         */
        fun profileOf(points: List<GesturePoint>, keys: List<KeyCenter>, keyWidthPx: Float, word: String): Profile? {
            if (points.size < 2 || keyWidthPx <= 0f || word.isEmpty()) return null
            val byCode = HashMap<Int, KeyCenter>(keys.size)
            for (k in keys) byCode[k.codePoint] = k
            val ideal = ArrayList<KeyCenter>(word.length)
            var previous = -1
            var at = 0
            while (at < word.length) {
                val cp = Character.toLowerCase(word.codePointAt(at))
                at += Character.charCount(cp)
                if (cp == previous) continue
                ideal.add(byCode[cp] ?: return null)
                previous = cp
            }
            if (ideal.size < 2) return null
            val w = keyWidthPx.toDouble()
            fun dist(x: Double, y: Double, k: KeyCenter) = sqrt((x - k.x) * (x - k.x) + (y - k.y) * (y - k.y)) / w

            val first = points.first()
            val last = points.last()
            val startDrift = dist(first.x.toDouble(), first.y.toDouble(), ideal.first())
            val earlyLift = dist(last.x.toDouble(), last.y.toDouble(), ideal.last())

            // Corner cutting: how far the path misses each interior key.
            var cut = 0.0
            for (i in 1 until ideal.size - 1) {
                var nearest = Double.MAX_VALUE
                for (p in points) nearest = min(nearest, dist(p.x.toDouble(), p.y.toDouble(), ideal[i]))
                cut += nearest
            }
            val cornerCut = if (ideal.size > 2) cut / (ideal.size - 2) else 0.0

            // Offset and scale from the two centroids and the two spreads.
            var px = 0.0
            var py = 0.0
            for (p in points) {
                px += p.x
                py += p.y
            }
            px /= points.size
            py /= points.size
            var ix = 0.0
            var iy = 0.0
            for (k in ideal) {
                ix += k.x
                iy += k.y
            }
            ix /= ideal.size
            iy /= ideal.size
            val offsetX = (px - ix) / w
            val offsetY = (py - iy) / w
            val drawnSpread = spread(points.map { it.x.toDouble() }, points.map { it.y.toDouble() })
            val idealSpread = spread(ideal.map { it.x.toDouble() }, ideal.map { it.y.toDouble() })
            val scale = if (idealSpread <= 0.0) 1.0 else drawnSpread / idealSpread
            return Profile(1, cornerCut, offsetX, offsetY, scale, earlyLift, startDrift)
        }

        /** The diagonal of a point set's bounding box. */
        private fun spread(xs: List<Double>, ys: List<Double>): Double {
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (i in xs.indices) {
                minX = min(minX, xs[i])
                maxX = max(maxX, xs[i])
                minY = min(minY, ys[i])
                maxY = max(maxY, ys[i])
            }
            val dx = maxX - minX
            val dy = maxY - minY
            return sqrt(dx * dx + dy * dy)
        }
    }
}
