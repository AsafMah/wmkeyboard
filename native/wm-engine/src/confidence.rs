//! The learned autocorrect gate: how sure the engine is that the best
//! correction it found is the word that was meant.
//!
//! The old gate was one rule, the best candidate's lead over the runner-up
//! against a slider, with surcharges and shapes bolted on around it. This is
//! a logistic model over everything that gate could see at once: the lead,
//! what the edit was (how far, how many, slips, accents, a completion), how
//! long the word is, whether the lists and the user's own words agree, what
//! the context model thinks of the candidate, and how the word was typed.
//! It answers one probability; the caller applies above one threshold and
//! offers above a lower one, and the confidence slider moves the thresholds.
//!
//! The weights are fitted offline by `tools/wmlm/train_confidence.py` on
//! rows the typing harness (`examples/typing_eval.rs`, `DUMP=1`) writes:
//! words from real sentences typed by a scattered finger, and words held out
//! of the list typed right, which is what a false correction looks like.
//! [`WEIGHTS`] and the standardisation below are that script's output,
//! pasted in; nothing here is tuned by hand.

/// One feature vector's width.
pub const FEATURES: usize = 27;

/// What the gate sees about one commit.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Case {
    /// The best candidate's score less the runner-up's (or the solo floor).
    pub margin: f64,
    pub top_score: f64,
    pub top_edit_cost: f64,
    pub top_edits: u32,
    pub top_slips: u32,
    pub top_accents: u32,
    pub top_completed: u32,
    pub top_in_dictionary: bool,
    pub top_in_lexicon: bool,
    /// The lists and the lexicon independently name the same word.
    pub two_sources: bool,
    /// The best reading mixes slips with a real edit.
    pub mixes_slips: bool,
    pub typed_len: usize,
    pub has_runner: bool,
    pub runner_edit_cost: f64,
    pub candidates: usize,
    /// ln P(top | context) from the context model, `None` without one or
    /// for a word it does not know.
    pub lm_top: Option<f32>,
    /// The context model's best log-probability among the candidates.
    pub lm_best: Option<f32>,
    /// ln of the typing-rhythm multiplier (0 at rest).
    pub timing: f64,
    /// The typed spelling is a listed word the engine reads as a stand-in
    /// (an accentless or corpus-typo spelling).
    pub typed_shadowed: bool,
    /// ln P(typed spelling | context): what the context model makes of the
    /// word as it stands, `None` when it cannot read it.
    pub lm_typed: Option<f32>,
    /// How many words the context model ranks above the typed spelling here.
    pub lm_typed_rank: Option<u32>,
    /// The entropy of the model's next-word distribution, in nats.
    pub lm_entropy: Option<f32>,
    /// The model's second-best log-probability among the candidates.
    pub lm_second: Option<f32>,
    /// ln P(typed spelling | context) spelled in subword pieces, for a
    /// typed word the vocabulary does not list; `None` when it is listed
    /// (then [`Case::lm_typed`] speaks) or the model has no pieces. Kept
    /// apart from `lm_typed`: a listed typed word is usually right, a
    /// piece-spelled one is usually a typo, and one feature for both taught
    /// the gate to spare every typo once pieces arrived.
    pub lm_typed_pieces: Option<f32>,
}

impl Case {
    /// The raw feature vector, before standardisation.
    pub fn features(&self) -> [f32; FEATURES] {
        let b = |v: bool| if v { 1.0 } else { 0.0 };
        let lm_top = self.lm_top.unwrap_or(0.0);
        let lm_gap = match (self.lm_top, self.lm_best) {
            (Some(t), Some(b)) => t - b,
            _ => 0.0,
        };
        let lm_lead = match (self.lm_best, self.lm_second) {
            (Some(b), Some(s)) => b - s,
            _ => 0.0,
        };
        [
            self.margin.clamp(-5.0, 15.0) as f32,
            self.top_score.clamp(-10.0, 20.0) as f32,
            self.top_edit_cost as f32,
            self.top_edits as f32,
            self.top_slips as f32,
            self.top_accents as f32,
            self.top_completed as f32,
            b(self.top_in_dictionary),
            b(self.top_in_lexicon),
            b(self.two_sources),
            b(self.mixes_slips),
            self.typed_len.min(20) as f32,
            b(self.has_runner),
            self.runner_edit_cost as f32,
            self.candidates.min(8) as f32,
            lm_top.clamp(-20.0, 0.0),
            lm_gap.clamp(-20.0, 0.0),
            b(self.lm_top.is_some()),
            self.timing as f32,
            b(self.typed_shadowed),
            self.lm_typed.unwrap_or(0.0).clamp(-20.0, 0.0),
            b(self.lm_typed.is_some()),
            self.lm_typed_rank.map_or(0.0, |r| (r as f32 + 1.0).ln()),
            self.lm_entropy.unwrap_or(0.0).clamp(0.0, 12.0),
            lm_lead.clamp(0.0, 20.0),
            self.lm_typed_pieces.unwrap_or(0.0).clamp(-60.0, 0.0),
            b(self.lm_typed_pieces.is_some()),
        ]
    }
}

/// A fitted gate: standardised features, one tanh hidden layer, a logistic
/// output.
#[derive(Clone, Debug, PartialEq)]
pub struct Confidence {
    pub mean: [f32; FEATURES],
    pub scale: [f32; FEATURES],
    pub w1: Vec<[f32; FEATURES]>,
    pub b1: Vec<f32>,
    pub w2: Vec<f32>,
    pub b2: f32,
}

impl Confidence {
    /// The shipped weights.
    pub fn shipped() -> Confidence {
        Confidence { mean: MEAN, scale: SCALE, w1: W1.to_vec(), b1: B1.to_vec(), w2: W2.to_vec(), b2: B2 }
    }

    /// P(the best candidate is the word meant | what the gate sees).
    pub fn probability(&self, case: &Case) -> f64 {
        let f = case.features();
        let mut z = [0.0f32; FEATURES];
        for i in 0..FEATURES {
            let s = if self.scale[i] > 0.0 { self.scale[i] } else { 1.0 };
            z[i] = (f[i] - self.mean[i]) / s;
        }
        let mut logit = self.b2;
        for (h, row) in self.w1.iter().enumerate() {
            let mut a = self.b1[h];
            for i in 0..FEATURES {
                a += row[i] * z[i];
            }
            logit += self.w2[h] * a.tanh();
        }
        1.0 / (1.0 + (-(logit as f64)).exp())
    }
}

/// The probability a correction has to reach to be applied at the user's
/// confidence `c` (the slider, 1.5 eager to 10 strict, 4 by default):
/// `c / (c + 1)`, so the default asks for four to one, as the old margin
/// gate did on scores.
pub fn apply_threshold(confidence: f64) -> f64 {
    let c = confidence.max(1.0);
    c / (c + 1.0)
}

/// The probability a correction has to reach to be offered as a chip.
pub fn offer_threshold(confidence: f64) -> f64 {
    (apply_threshold(confidence) * 0.6).max(0.35)
}

// The fitted constants. `tools/wmlm/train_confidence.py --rust=<this file>`
// rewrites everything between the markers; until a fit lands, the gate is
// the lead alone, scaled so that four to one on scores is about 0.8.
// FIT BEGIN
// Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_v1_27.csv, ../../build/corpora/rows_en2_27.csv (18868 rows, hidden 16, l2 0.003).
pub const HIDDEN: usize = 16;
pub const MEAN: [f32; FEATURES] = [2.71315, 7.21932, 0.15752, 1.49057, 1.22472, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 5.11575, 0.620734, 0.329904, 2.8764, -3.86361, -0.564656, 0.49947, 0.0, 0.0, -0.496263, 0.0473818, 0.325668, 2.32541, 1.66087, -5.68218, 0.219154];
pub const SCALE: [f32; FEATURES] = [2.42015, 1.34727, 0.369359, 0.842333, 1.0796, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.96346, 0.485205, 0.57437, 2.03868, 4.98312, 2.0758, 0.5, 1.0, 1.0, 2.38337, 0.212454, 1.55041, 2.61378, 3.38784, 11.3847, 0.413673];
pub const W1: [[f32; FEATURES]; HIDDEN] = [
    [0.0557772, -0.0287615, 0.115762, -0.11259, 0.195309, -1.35067e-70, -6.85497e-71, -9.61175e-08, -3.63922e-70, 1.13143e-70, 1.19411e-70, 0.0343892, -0.0610116, 0.0267834, -0.00358245, -0.0119331, 0.0872451, 0.0290236, 4.37343e-70, -9.61272e-71, 0.0603273, -0.0886695, -0.0628121, 0.01353, 0.0136863, -0.0173123, 0.00600747],
    [0.0650874, -0.0242094, 0.125845, -0.141327, 0.241161, -4.50328e-70, -1.2443e-70, 1.13381e-70, -8.80744e-70, -2.09603e-70, 1.13532e-70, 0.0360603, -0.0660416, 0.036029, -0.00150821, -0.0193363, 0.102935, 0.0324463, 2.3218e-70, -1.17361e-16, 0.0696841, -0.101558, -0.0721343, 0.0152205, 0.012657, -0.0166394, 0.00496535],
    [-0.164461, -0.414447, -0.215336, 0.288548, -0.47429, -3.30093e-71, 9.00717e-70, -5.49658e-70, 9.16616e-70, -1.98145e-69, 2.55081e-70, 0.451865, -0.235399, -0.007197, 0.0276586, -0.329748, -0.293061, -0.0694482, 1.03918e-69, -1.821e-71, 0.0328171, 0.0673681, -0.018563, -0.107895, -0.12819, 0.00282612, -0.0226768],
    [0.265721, 0.502134, 0.114854, -0.285261, 0.780434, 3.63871e-70, -1.31193e-69, -5.79344e-70, -1.01372e-70, -6.95948e-71, 1.47887e-70, 0.104264, -0.0470727, 0.0430412, -0.0343532, 0.218863, 0.369735, 0.205917, -5.56402e-70, 1.15084e-70, -0.00935721, -0.150718, -0.00930462, 0.0268125, 0.257612, -0.0416822, -0.0133265],
    [-0.0667159, 0.0229389, -0.12786, 0.147774, -0.251475, 3.06128e-70, -2.92047e-71, -3.50841e-70, 2.89063e-70, -6.76917e-70, 5.87614e-70, -0.0361689, 0.0668, -0.0380181, 0.000844629, 0.0210002, -0.106412, -0.0331297, -5.2969e-70, 1.35346e-19, -0.0715522, 0.104199, 0.0739847, -0.0154795, -0.0123882, 0.0163298, -0.00467149],
    [0.407522, 0.199656, 0.0278522, -0.261449, 0.245636, 1.55896e-71, -5.16992e-70, 6.06843e-70, 2.84176e-70, -9.32668e-70, -2.32842e-23, -0.20991, -0.370528, -0.212937, -0.220858, 0.0502104, 0.185114, 0.0571831, -3.30884e-70, 2.59862e-70, -0.000845605, -0.0704864, 0.00472128, -0.0796102, 0.231806, 0.0199959, -0.0439461],
    [-0.0641633, 0.0247434, -0.123923, 0.136316, -0.23351, -3.72204e-70, -1.66398e-71, -5.97514e-70, 7.55767e-13, 2.15953e-70, 2.25215e-13, -0.0358498, 0.0654924, -0.0344805, 0.00223541, 0.0183364, -0.100152, -0.0321777, 8.09176e-70, 2.05201e-70, -0.0685405, 0.0998098, 0.0710081, -0.015251, -0.0130441, 0.0170662, -0.00545501],
    [0.114343, 0.0888573, 0.807212, 0.486329, -0.531066, 1.15469e-69, -8.72591e-70, 9.75089e-70, -7.33441e-70, 0.000851872, 1.15507e-69, -0.334943, -0.0433042, -0.169373, -0.238891, 0.0421729, -0.0952973, 0.113521, -2.89455e-70, 3.62497e-70, 0.0837129, 0.0852113, -0.0653227, 0.0498527, 0.0865204, 0.00624945, 0.0449211],
    [-0.0439788, -0.287415, -0.766547, -0.278482, 0.668762, 4.65324e-70, 3.31588e-70, 2.36547e-71, 1.3612e-69, 2.64764e-70, 1.31958e-69, -0.174615, -0.0798357, 0.0831594, 0.0929767, -0.1365, 0.0223089, -0.110711, -7.05916e-71, -1.27954e-70, -0.0742059, -0.0399305, 0.0617552, 8.56042e-06, -0.159881, 0.0532042, 0.00405834],
    [0.0654257, -0.0240411, 0.126501, -0.142829, 0.243452, 1.64222e-69, -1.07941e-69, -6.98043e-70, -9.98607e-70, 3.38746e-70, 6.42633e-70, 0.0362129, -0.0662726, 0.0364647, -0.00131903, -0.0196916, 0.10378, 0.0326192, 2.14952e-70, -6.02549e-70, 0.0701394, -0.102171, -0.0725818, 0.0153027, 0.0126005, -0.0165714, 0.00490301],
    [0.0754976, 0.244074, 0.0632543, -0.0741749, 0.686145, 2.3287e-70, 5.11108e-70, -4.23329e-09, -5.865e-70, 3.84249e-08, 6.16208e-70, -0.0839726, 0.0654997, -0.051702, -0.135187, -0.0713416, 0.0831141, 0.230959, 6.88383e-70, 7.93832e-70, 0.0258513, -0.17491, -0.0407352, 0.120373, 0.180616, -0.0920142, 0.0746529],
    [0.0672186, -0.0225418, 0.128227, -0.149587, 0.254489, 9.98417e-70, 4.89383e-70, 5.79363e-70, -7.24105e-70, 1.48004e-69, 1.15265e-24, 0.0361323, -0.0669827, 0.0385823, -0.00068622, -0.0216205, 0.107412, 0.0334091, -1.72198e-70, -1.52176e-70, 0.0721, -0.104979, -0.0745183, 0.0156995, 0.0123117, -0.0163189, 0.00464032],
    [0.0303504, 0.264704, -0.500815, 0.0231137, -0.181335, -7.12342e-71, 1.08331e-69, -1.32226e-70, 6.67975e-71, -1.64222e-70, 7.92255e-70, -0.303891, 0.202422, 0.0052531, -0.0651639, -0.137898, -0.206657, -0.063813, -9.89594e-70, -6.09485e-70, -0.0776928, 0.100558, 0.0797402, -0.110038, -0.12516, 0.066356, -0.0175977],
    [0.0206361, 0.0564075, -0.655547, -0.369394, 0.459385, -1.84342e-70, -2.60759e-72, -5.71821e-70, -1.58533e-70, 1.15077e-70, -3.07922e-71, 0.164329, -0.0207425, 0.00968847, -0.00632885, -0.116956, 0.0533174, -0.0647873, 2.84387e-70, 2.3546e-70, 0.0963735, -0.133929, -0.0960958, -0.0371611, -0.155574, 0.0310194, -0.042269],
    [-0.0651391, 0.0240265, -0.125485, 0.140291, -0.239586, -1.43281e-70, 3.8309e-71, 1.1737e-69, 2.23904e-70, -6.56235e-70, 5.05758e-70, -0.0361199, 0.0660812, -0.0357008, 0.0017902, 0.0193015, -0.102341, -0.0326138, 1.72731e-70, 7.4257e-70, -0.069476, 0.101225, 0.0719393, -0.0154628, -0.0128497, 0.0169379, -0.00526443],
    [-0.0844756, -0.170437, 0.837131, 0.727071, -0.401889, 1.17765e-70, -2.6199e-71, -1.12384e-70, 2.66576e-70, -1.55248e-69, -1.0336e-70, 0.00664387, -0.192518, 0.117656, 0.122138, 0.0506351, -0.132547, -0.00898737, 3.39709e-68, -5.571e-70, 0.0202081, 0.110641, -0.0146494, 0.0638251, -0.193644, -0.0194371, 0.000810983],
];
pub const B1: [f32; HIDDEN] = [-0.103957, -0.070338, 0.473609, 0.340871, 0.0639118, 1.07143, 0.0739364, 0.192565, 0.213427, -0.069621, 0.82811, -0.0614678, 0.986781, -0.316847, 0.070922, 0.325201];
pub const W2: [f32; HIDDEN] = [0.319321, 0.385774, -1.02634, 1.13729, -0.401442, 0.781153, -0.373759, -1.15089, 1.08746, 0.389983, 0.861906, 0.40559, -0.800516, 0.902933, -0.383277, -1.12101];
pub const B2: f32 = 0.397774;





// FIT END

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_wider_lead_is_surer() {
        let c = Confidence::shipped();
        let narrow = Case { margin: 0.5, ..fixed_case() };
        let wide = Case { margin: 4.0, ..fixed_case() };
        assert!(c.probability(&wide) > c.probability(&narrow));
    }

    /// The case `ConfidenceTest.kt` scores too; the two ports must agree.
    pub fn fixed_case() -> Case {
        Case {
            margin: 2.3,
            top_score: 7.9,
            top_edit_cost: 0.105,
            top_edits: 1,
            top_slips: 1,
            top_accents: 0,
            top_completed: 0,
            top_in_dictionary: true,
            top_in_lexicon: false,
            two_sources: false,
            mixes_slips: false,
            typed_len: 6,
            has_runner: true,
            runner_edit_cost: 1.6,
            candidates: 4,
            lm_top: Some(-2.0),
            lm_best: Some(-2.0),
            timing: 0.0,
            typed_shadowed: false,
            lm_typed: None,
            lm_typed_rank: None,
            lm_entropy: Some(4.2),
            lm_second: Some(-3.5),
            lm_typed_pieces: Some(-28.0),
        }
    }

    #[test]
    fn the_fixed_case_scores_what_the_kotlin_port_scores() {
        let p = Confidence::shipped().probability(&fixed_case());
        // Refit the gate and both this number and ConfidenceTest.kt's move.
        assert!((p - 0.926967).abs() < 1e-4, "{p}");
    }

    #[test]
    fn the_default_slider_asks_four_to_one() {
        assert!((apply_threshold(4.0) - 0.8).abs() < 1e-9);
        assert!(offer_threshold(4.0) < apply_threshold(4.0));
    }
}
