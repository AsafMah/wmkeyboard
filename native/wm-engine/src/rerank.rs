//! The context reranker: the Kotlin `NgramReranker` (core/prediction),
//! term for term. It reorders the head of the strip by the words before the
//! caret: the user's own trigrams, bigrams and skip-grams, the corpus pack's
//! trigrams and bigrams, the bundled seed pairs, and words from the current
//! conversation.
//!
//! The base of each candidate's score is its rank in the incoming order
//! (which already carries edit costs, touch likelihoods and every capped
//! boost); evidence can lift it a bounded number of places. Logarithms are
//! [`strict_math::log`], so the Kotlin original and this agree to the bit.

use crate::sort;
use crate::strict_math;
use crate::user_lexicon::UserLexicon;
use crate::wmng::Pack;

const RANK_STEP: f64 = 1.0;
const WEIGHT_USER_TRIGRAM: f64 = 1.2;
const CAP_USER_TRIGRAM: f64 = 2.5;
const WEIGHT_USER_BIGRAM: f64 = 0.8;
const CAP_USER_BIGRAM: f64 = 2.0;
const WEIGHT_USER_SKIP1: f64 = 0.5;
const CAP_USER_SKIP1: f64 = 1.2;
const WEIGHT_USER_SKIP2: f64 = 0.4;
const CAP_USER_SKIP2: f64 = 1.1;
const WEIGHT_PACK_TRIGRAM: f64 = 0.6;
const CAP_PACK_TRIGRAM: f64 = 1.5;
const WEIGHT_PACK_BIGRAM: f64 = 0.45;
const CAP_PACK_BIGRAM: f64 = 1.2;
const WEIGHT_SEED_BIGRAM: f64 = 0.4;
const CAP_SEED: f64 = 1.5;
const WEIGHT_RECENCY: f64 = 0.3;
const PACK_COUNT_SCALE: i32 = 50;
const WEIGHT_SKIP_USER: f64 = 0.5;
const CAP_SKIP_USER: f64 = 1.2;
const WEIGHT_SKIP_PACK: f64 = 0.3;
const CAP_SKIP_PACK: f64 = 0.8;
/// The context model's say ([`Candidate::lm`]): how many ranks one nat of
/// its preference over the engine's first choice is worth, and the most
/// ranks it may move any candidate either way.
/// Measured 2026-10-26 on the typing harness (step-2000 English model):
/// 0.5/3 cost 1.9 points of typo top-1 on careful typing, 0.35/2 one
/// point, 0.25/1.5 half a point, and with the score-space blend below in
/// place even 0.25 cost two points on top of it (0.864 against 0.884 on
/// unseen sentences), so the term ships off: the blend carries the model's
/// say in ranking, the gate's features and the next-word strip the rest.
/// `LMW=` on the typing harness turns it back on for a measurement.
pub const LM_WEIGHT: f64 = 0.0;
pub const LM_CAP: f64 = 1.5;
/// The model's say in score space instead, before ranking (the engine's
/// `suggest`): engine nats per nat of its preference over the leader, and
/// the most it may move a candidate. The engine's unit is the compressed
/// frequency scale, so one of its nats is worth several of the model's; 0.2
/// and 1.0 won the 2026-10-26 sweep (0.05, 0.1, 0.2; caps 0.5, 1.0) on the
/// typing harness, and the rank-unit term above stays off beside it.
pub const LM_BLEND: f64 = 0.2;
pub const LM_BLEND_CAP: f64 = 1.0;
/// The blend grows with how far the taps landed from their keys: the keys
/// say less, so the sentence says more. `weight = LM_BLEND * (1 + SLOPE *
/// (spread - LM_BLEND_SPREAD_REF))`, spread being the mean distance of the
/// word's taps to the nearest key centre in key widths, clamped to
/// [LM_BLEND_MIN_FACTOR, LM_BLEND_MAX_FACTOR] times the base. The reference
/// is a careful typist's mean (scatter 0.25/0.3). Without taps the base
/// weight stands. The slope was swept on the typing harness (`LMBSLOPE`).
pub const LM_BLEND_SLOPE: f64 = 0.0;
pub const LM_BLEND_SPREAD_REF: f64 = 0.35;
pub const LM_BLEND_MIN_FACTOR: f64 = 0.5;
pub const LM_BLEND_MAX_FACTOR: f64 = 2.0;

/// The blend weight for a word whose taps lie `spread` key widths from
/// their keys on average (`None` without taps).
pub fn lm_blend_for(base: f64, slope: f64, spread: Option<f64>) -> f64 {
    match spread {
        None => base,
        Some(s) => base * (1.0 + slope * (s - LM_BLEND_SPREAD_REF)).clamp(LM_BLEND_MIN_FACTOR, LM_BLEND_MAX_FACTOR),
    }
}

/// A word as both stores look it up: its key for the lexicon, and the
/// UTF-8 bytes of that key as the packs compare them (Kotlin's
/// `toByteArray`, a lone surrogate as `?`).
pub struct Word {
    pub key: String,
    pub bytes: Vec<u8>,
}

/// One rerank's context. `skip` is the word two back when the previous word
/// is one no store knows (the caller decides, from its dictionaries).
pub struct Context<'a> {
    pub prev: &'a Word,
    pub prev2: Option<&'a Word>,
    pub prev3: Option<&'a Word>,
    pub skip: Option<&'a Word>,
}

/// One candidate: its words, the bundled seed count of `prev candidate`,
/// and whether it was in the recent conversation.
pub struct Candidate {
    pub word: Word,
    pub seed: i32,
    pub recent: bool,
    /// ln P(word | context) from the neural context model, NaN when there
    /// is none or it does not know the word.
    pub lm: f32,
}

/// Each candidate's term from the context model: its log-probability
/// against the engine's first choice, `weight` ranks per nat, within
/// `cap`. Nothing moves when the model does not know the leader or a
/// candidate.
pub fn lm_terms(candidates: &[Candidate], weight: f64, cap: f64) -> Vec<f64> {
    let reference = match candidates.first() {
        Some(c) if !c.lm.is_nan() => c.lm,
        _ => return vec![0.0; candidates.len()],
    };
    candidates
        .iter()
        .map(|c| if c.lm.is_nan() { 0.0 } else { (weight * (c.lm - reference) as f64).clamp(-cap, cap) })
        .collect()
}

/// `term`: `min(weight * ln(1 + count), cap)`.
fn term(weight: f64, count: i32, cap: f64) -> f64 {
    let v = weight * strict_math::log(1.0 + count as f64);
    // Kotlin's coerceAtMost: the cap when above it, else the value (NaN stays).
    if v > cap {
        cap
    } else {
        v
    }
}

/// The largest count any pack gives, 0 with no pack: `NgramPack`'s merge.
fn pack_max<B: AsRef<[u8]>>(packs: &[&Pack<B>], count: impl Fn(&Pack<B>) -> i32) -> i32 {
    packs.iter().map(|p| count(p)).max().unwrap_or(0)
}

/// The new order, as indices into `candidates`, or `None` when no candidate
/// had any evidence (the caller keeps its order).
pub fn rerank<B: AsRef<[u8]>>(
    lexicon: &UserLexicon,
    packs: &[&Pack<B>],
    context: &Context,
    candidates: &[Candidate],
) -> Option<Vec<usize>> {
    rerank_weighted(lexicon, packs, context, candidates, LM_WEIGHT)
}

/// [`rerank`] with the context model's weight set by the caller: the
/// shipped [`LM_WEIGHT`] times what the user's own record earns it (the
/// phone's `ContextAdapter`). The cap stays.
pub fn rerank_weighted<B: AsRef<[u8]>>(
    lexicon: &UserLexicon,
    packs: &[&Pack<B>],
    context: &Context,
    candidates: &[Candidate],
    lm_weight: f64,
) -> Option<Vec<usize>> {
    if candidates.is_empty() {
        return None;
    }
    let prev = context.prev;
    let lm = lm_terms(candidates, lm_weight, LM_CAP);
    let mut any_evidence = lm.iter().any(|t| *t != 0.0);
    let mut scored: Vec<(usize, f64)> = Vec::with_capacity(candidates.len());
    for (index, c) in candidates.iter().enumerate() {
        let w = &c.word;
        let user3 = context.prev2.map_or(0, |p2| lexicon.trigram_count(&p2.key, &prev.key, &w.key));
        let user2 = lexicon.bigram_count(&prev.key, &w.key);
        let user_skip1 = context.prev2.map_or(0, |p2| lexicon.skip1gram_count(&p2.key, &w.key));
        let user_skip2 = context.prev3.map_or(0, |p3| lexicon.skip2gram_count(&p3.key, &w.key));
        let pack3 = match context.prev2 {
            None => 0,
            Some(p2) => pack_max(packs, |p| p.trigram_count_bytes(&p2.bytes, &prev.bytes, &w.bytes)),
        };
        let pack2 = pack_max(packs, |p| p.bigram_count_bytes(&prev.bytes, &w.bytes));
        let skip_user = context.skip.map_or(0, |s| lexicon.bigram_count(&s.key, &w.key));
        let skip_pack = match context.skip {
            None => 0,
            Some(s) => pack_max(packs, |p| p.bigram_count_bytes(&s.bytes, &w.bytes)),
        };
        let evidence = term(WEIGHT_USER_TRIGRAM, user3, CAP_USER_TRIGRAM)
            + term(WEIGHT_USER_BIGRAM, user2, CAP_USER_BIGRAM)
            + term(WEIGHT_USER_SKIP1, user_skip1, CAP_USER_SKIP1)
            + term(WEIGHT_USER_SKIP2, user_skip2, CAP_USER_SKIP2)
            + term(WEIGHT_PACK_TRIGRAM, pack3 / PACK_COUNT_SCALE, CAP_PACK_TRIGRAM)
            + term(WEIGHT_PACK_BIGRAM, pack2 / PACK_COUNT_SCALE, CAP_PACK_BIGRAM)
            + term(WEIGHT_SEED_BIGRAM, c.seed, CAP_SEED)
            + term(WEIGHT_SKIP_USER, skip_user, CAP_SKIP_USER)
            + term(WEIGHT_SKIP_PACK, skip_pack / PACK_COUNT_SCALE, CAP_SKIP_PACK)
            + WEIGHT_RECENCY * if c.recent { 1.0 } else { 0.0 };
        if evidence > 0.0 {
            any_evidence = true;
        }
        scored.push((index, -(index as f64) * RANK_STEP + evidence + lm[index]));
    }
    if !any_evidence {
        return None;
    }
    // Stable, highest first: equal scores keep the engine's order, as
    // Kotlin's sortedByDescending does.
    sort::by(&mut scored, |a, b| b.1.total_cmp(&a.1));
    Some(scored.into_iter().map(|(i, _)| i).collect())
}
