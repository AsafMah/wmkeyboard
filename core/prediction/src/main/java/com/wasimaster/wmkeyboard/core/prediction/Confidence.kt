package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * The learned autocorrect gate: how sure the engine is that the best
 * correction it found is the word that was meant.
 *
 * The margin gate was one rule, the best candidate's lead over the runner-up
 * against the slider, with surcharges and shapes bolted on around it. This
 * is a logistic model over everything that gate could see at once: the lead,
 * what the edit was (how far, how many, slips, accents, a completion), how
 * long the word is, whether the lists and the user's own words agree, what
 * the context model thinks of the candidate, and how the word was typed. It
 * answers one probability; [SuggestionEngine] applies above
 * [applyThreshold] and offers above [offerThreshold], and the confidence
 * slider moves both.
 *
 * The weights are `wm-engine`'s `confidence.rs`, number for number: fitted
 * offline by `tools/wmlm/train_confidence.py` on rows the typing harness
 * writes (real sentences typed by a scattered finger, and words held out of
 * the list typed right, which is what a false correction looks like), and
 * pasted into both files. Nothing here is tuned by hand.
 */
object Confidence {

    const val FEATURES = 27

    /** What the gate sees about one commit; see `confidence.rs`'s `Case`. */
    class Case(
        val margin: Double,
        val topScore: Double,
        val topEditCost: Double,
        val topEdits: Int,
        val topSlips: Int,
        val topAccents: Int,
        val topCompleted: Int,
        val topInDictionary: Boolean,
        val topInLexicon: Boolean,
        val twoSources: Boolean,
        val mixesSlips: Boolean,
        val typedLength: Int,
        val hasRunner: Boolean,
        val runnerEditCost: Double,
        val candidates: Int,
        /** ln P(top | context) from the context model, NaN without one. */
        val lmTop: Float,
        /** The context model's best log-probability among the candidates, NaN without one. */
        val lmBest: Float,
        /** ln of the typing-rhythm multiplier (0 at rest). */
        val timing: Double,
        val typedShadowed: Boolean,
        /** ln P(typed spelling | context), NaN when the model cannot read it. */
        val lmTyped: Float = Float.NaN,
        /** How many words the model ranks above the typed spelling here, -1 without one. */
        val lmTypedRank: Int = -1,
        /** The entropy of the model's next-word distribution in nats, NaN without one. */
        val lmEntropy: Float = Float.NaN,
        /** The model's second-best log-probability among the candidates, NaN without one. */
        val lmSecond: Float = Float.NaN,
        /** ln P(typed spelling) spelled in pieces for an unlisted typed word, NaN when listed or without pieces. */
        val lmTypedPieces: Float = Float.NaN,
    ) {
        fun features(): FloatArray {
            fun b(v: Boolean) = if (v) 1f else 0f
            val lmTopValue = if (lmTop.isNaN()) 0f else lmTop
            val lmGap = if (lmTop.isNaN() || lmBest.isNaN()) 0f else lmTop - lmBest
            val lmLead = if (lmBest.isNaN() || lmSecond.isNaN()) 0f else lmBest - lmSecond
            return floatArrayOf(
                margin.coerceIn(-5.0, 15.0).toFloat(),
                topScore.coerceIn(-10.0, 20.0).toFloat(),
                topEditCost.toFloat(),
                topEdits.toFloat(),
                topSlips.toFloat(),
                topAccents.toFloat(),
                topCompleted.toFloat(),
                b(topInDictionary),
                b(topInLexicon),
                b(twoSources),
                b(mixesSlips),
                typedLength.coerceAtMost(20).toFloat(),
                b(hasRunner),
                runnerEditCost.toFloat(),
                candidates.coerceAtMost(8).toFloat(),
                lmTopValue.coerceIn(-20f, 0f),
                lmGap.coerceIn(-20f, 0f),
                b(!lmTop.isNaN()),
                timing.toFloat(),
                b(typedShadowed),
                (if (lmTyped.isNaN()) 0f else lmTyped).coerceIn(-20f, 0f),
                b(!lmTyped.isNaN()),
                if (lmTypedRank < 0) 0f else ln(lmTypedRank + 1f),
                (if (lmEntropy.isNaN()) 0f else lmEntropy).coerceIn(0f, 12f),
                lmLead.coerceIn(0f, 20f),
                (if (lmTypedPieces.isNaN()) 0f else lmTypedPieces).coerceIn(-60f, 0f),
                b(!lmTypedPieces.isNaN()),
            )
        }
    }

    /** P(the best candidate is the word meant | [case]). */
    fun probability(case: Case): Double {
        val f = case.features()
        val z = FloatArray(FEATURES) { i ->
            val s = if (SCALE[i] > 0f) SCALE[i] else 1f
            (f[i] - MEAN[i]) / s
        }
        var logit = B2
        for (h in 0 until HIDDEN) {
            var a = B1[h]
            val row = W1[h]
            for (i in 0 until FEATURES) a += row[i] * z[i]
            logit += W2[h] * tanh(a)
        }
        return 1.0 / (1.0 + exp(-logit.toDouble()))
    }

    /**
     * The probability a correction has to reach to be applied at the user's
     * confidence [confidence] (the slider, 1.5 eager to 10 strict, 4 by
     * default): `c / (c + 1)`, so the default asks for four to one, as the
     * margin gate did on scores.
     */
    fun applyThreshold(confidence: Double): Double {
        val c = confidence.coerceAtLeast(1.0)
        return c / (c + 1.0)
    }

    /** The probability a correction has to reach to be offered as a chip. */
    fun offerThreshold(confidence: Double): Double = (applyThreshold(confidence) * 0.6).coerceAtLeast(0.35)

    /** ln of the timing multiplier, as the gate takes it. */
    fun timingFeature(multiplier: Double): Double = ln(multiplier.coerceAtLeast(1e-6))

    // The fitted constants, identical to confidence.rs: `tools/wmlm/train_confidence.py
    // --kotlin=<this file>` rewrites everything between the markers.
    // FIT BEGIN
    // Fitted by tools/wmlm/train_confidence.py on ../../build/corpora/rows_v1_27.csv, ../../build/corpora/rows_en2_27.csv (18868 rows, hidden 16, l2 0.003).
    const val HIDDEN = 16
    val MEAN = floatArrayOf(2.71315f, 7.21932f, 0.15752f, 1.49057f, 1.22472f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 5.11575f, 0.620734f, 0.329904f, 2.8764f, -3.86361f, -0.564656f, 0.49947f, 0.0f, 0.0f, -0.496263f, 0.0473818f, 0.325668f, 2.32541f, 1.66087f, -5.68218f, 0.219154f)
    val SCALE = floatArrayOf(2.42015f, 1.34727f, 0.369359f, 0.842333f, 1.0796f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.96346f, 0.485205f, 0.57437f, 2.03868f, 4.98312f, 2.0758f, 0.5f, 1.0f, 1.0f, 2.38337f, 0.212454f, 1.55041f, 2.61378f, 3.38784f, 11.3847f, 0.413673f)
    val W1 = arrayOf(
        floatArrayOf(0.0557772f, -0.0287615f, 0.115762f, -0.11259f, 0.195309f, -1.35067e-70f, -6.85497e-71f, -9.61175e-08f, -3.63922e-70f, 1.13143e-70f, 1.19411e-70f, 0.0343892f, -0.0610116f, 0.0267834f, -0.00358245f, -0.0119331f, 0.0872451f, 0.0290236f, 4.37343e-70f, -9.61272e-71f, 0.0603273f, -0.0886695f, -0.0628121f, 0.01353f, 0.0136863f, -0.0173123f, 0.00600747f),
        floatArrayOf(0.0650874f, -0.0242094f, 0.125845f, -0.141327f, 0.241161f, -4.50328e-70f, -1.2443e-70f, 1.13381e-70f, -8.80744e-70f, -2.09603e-70f, 1.13532e-70f, 0.0360603f, -0.0660416f, 0.036029f, -0.00150821f, -0.0193363f, 0.102935f, 0.0324463f, 2.3218e-70f, -1.17361e-16f, 0.0696841f, -0.101558f, -0.0721343f, 0.0152205f, 0.012657f, -0.0166394f, 0.00496535f),
        floatArrayOf(-0.164461f, -0.414447f, -0.215336f, 0.288548f, -0.47429f, -3.30093e-71f, 9.00717e-70f, -5.49658e-70f, 9.16616e-70f, -1.98145e-69f, 2.55081e-70f, 0.451865f, -0.235399f, -0.007197f, 0.0276586f, -0.329748f, -0.293061f, -0.0694482f, 1.03918e-69f, -1.821e-71f, 0.0328171f, 0.0673681f, -0.018563f, -0.107895f, -0.12819f, 0.00282612f, -0.0226768f),
        floatArrayOf(0.265721f, 0.502134f, 0.114854f, -0.285261f, 0.780434f, 3.63871e-70f, -1.31193e-69f, -5.79344e-70f, -1.01372e-70f, -6.95948e-71f, 1.47887e-70f, 0.104264f, -0.0470727f, 0.0430412f, -0.0343532f, 0.218863f, 0.369735f, 0.205917f, -5.56402e-70f, 1.15084e-70f, -0.00935721f, -0.150718f, -0.00930462f, 0.0268125f, 0.257612f, -0.0416822f, -0.0133265f),
        floatArrayOf(-0.0667159f, 0.0229389f, -0.12786f, 0.147774f, -0.251475f, 3.06128e-70f, -2.92047e-71f, -3.50841e-70f, 2.89063e-70f, -6.76917e-70f, 5.87614e-70f, -0.0361689f, 0.0668f, -0.0380181f, 0.000844629f, 0.0210002f, -0.106412f, -0.0331297f, -5.2969e-70f, 1.35346e-19f, -0.0715522f, 0.104199f, 0.0739847f, -0.0154795f, -0.0123882f, 0.0163298f, -0.00467149f),
        floatArrayOf(0.407522f, 0.199656f, 0.0278522f, -0.261449f, 0.245636f, 1.55896e-71f, -5.16992e-70f, 6.06843e-70f, 2.84176e-70f, -9.32668e-70f, -2.32842e-23f, -0.20991f, -0.370528f, -0.212937f, -0.220858f, 0.0502104f, 0.185114f, 0.0571831f, -3.30884e-70f, 2.59862e-70f, -0.000845605f, -0.0704864f, 0.00472128f, -0.0796102f, 0.231806f, 0.0199959f, -0.0439461f),
        floatArrayOf(-0.0641633f, 0.0247434f, -0.123923f, 0.136316f, -0.23351f, -3.72204e-70f, -1.66398e-71f, -5.97514e-70f, 7.55767e-13f, 2.15953e-70f, 2.25215e-13f, -0.0358498f, 0.0654924f, -0.0344805f, 0.00223541f, 0.0183364f, -0.100152f, -0.0321777f, 8.09176e-70f, 2.05201e-70f, -0.0685405f, 0.0998098f, 0.0710081f, -0.015251f, -0.0130441f, 0.0170662f, -0.00545501f),
        floatArrayOf(0.114343f, 0.0888573f, 0.807212f, 0.486329f, -0.531066f, 1.15469e-69f, -8.72591e-70f, 9.75089e-70f, -7.33441e-70f, 0.000851872f, 1.15507e-69f, -0.334943f, -0.0433042f, -0.169373f, -0.238891f, 0.0421729f, -0.0952973f, 0.113521f, -2.89455e-70f, 3.62497e-70f, 0.0837129f, 0.0852113f, -0.0653227f, 0.0498527f, 0.0865204f, 0.00624945f, 0.0449211f),
        floatArrayOf(-0.0439788f, -0.287415f, -0.766547f, -0.278482f, 0.668762f, 4.65324e-70f, 3.31588e-70f, 2.36547e-71f, 1.3612e-69f, 2.64764e-70f, 1.31958e-69f, -0.174615f, -0.0798357f, 0.0831594f, 0.0929767f, -0.1365f, 0.0223089f, -0.110711f, -7.05916e-71f, -1.27954e-70f, -0.0742059f, -0.0399305f, 0.0617552f, 8.56042e-06f, -0.159881f, 0.0532042f, 0.00405834f),
        floatArrayOf(0.0654257f, -0.0240411f, 0.126501f, -0.142829f, 0.243452f, 1.64222e-69f, -1.07941e-69f, -6.98043e-70f, -9.98607e-70f, 3.38746e-70f, 6.42633e-70f, 0.0362129f, -0.0662726f, 0.0364647f, -0.00131903f, -0.0196916f, 0.10378f, 0.0326192f, 2.14952e-70f, -6.02549e-70f, 0.0701394f, -0.102171f, -0.0725818f, 0.0153027f, 0.0126005f, -0.0165714f, 0.00490301f),
        floatArrayOf(0.0754976f, 0.244074f, 0.0632543f, -0.0741749f, 0.686145f, 2.3287e-70f, 5.11108e-70f, -4.23329e-09f, -5.865e-70f, 3.84249e-08f, 6.16208e-70f, -0.0839726f, 0.0654997f, -0.051702f, -0.135187f, -0.0713416f, 0.0831141f, 0.230959f, 6.88383e-70f, 7.93832e-70f, 0.0258513f, -0.17491f, -0.0407352f, 0.120373f, 0.180616f, -0.0920142f, 0.0746529f),
        floatArrayOf(0.0672186f, -0.0225418f, 0.128227f, -0.149587f, 0.254489f, 9.98417e-70f, 4.89383e-70f, 5.79363e-70f, -7.24105e-70f, 1.48004e-69f, 1.15265e-24f, 0.0361323f, -0.0669827f, 0.0385823f, -0.00068622f, -0.0216205f, 0.107412f, 0.0334091f, -1.72198e-70f, -1.52176e-70f, 0.0721f, -0.104979f, -0.0745183f, 0.0156995f, 0.0123117f, -0.0163189f, 0.00464032f),
        floatArrayOf(0.0303504f, 0.264704f, -0.500815f, 0.0231137f, -0.181335f, -7.12342e-71f, 1.08331e-69f, -1.32226e-70f, 6.67975e-71f, -1.64222e-70f, 7.92255e-70f, -0.303891f, 0.202422f, 0.0052531f, -0.0651639f, -0.137898f, -0.206657f, -0.063813f, -9.89594e-70f, -6.09485e-70f, -0.0776928f, 0.100558f, 0.0797402f, -0.110038f, -0.12516f, 0.066356f, -0.0175977f),
        floatArrayOf(0.0206361f, 0.0564075f, -0.655547f, -0.369394f, 0.459385f, -1.84342e-70f, -2.60759e-72f, -5.71821e-70f, -1.58533e-70f, 1.15077e-70f, -3.07922e-71f, 0.164329f, -0.0207425f, 0.00968847f, -0.00632885f, -0.116956f, 0.0533174f, -0.0647873f, 2.84387e-70f, 2.3546e-70f, 0.0963735f, -0.133929f, -0.0960958f, -0.0371611f, -0.155574f, 0.0310194f, -0.042269f),
        floatArrayOf(-0.0651391f, 0.0240265f, -0.125485f, 0.140291f, -0.239586f, -1.43281e-70f, 3.8309e-71f, 1.1737e-69f, 2.23904e-70f, -6.56235e-70f, 5.05758e-70f, -0.0361199f, 0.0660812f, -0.0357008f, 0.0017902f, 0.0193015f, -0.102341f, -0.0326138f, 1.72731e-70f, 7.4257e-70f, -0.069476f, 0.101225f, 0.0719393f, -0.0154628f, -0.0128497f, 0.0169379f, -0.00526443f),
        floatArrayOf(-0.0844756f, -0.170437f, 0.837131f, 0.727071f, -0.401889f, 1.17765e-70f, -2.6199e-71f, -1.12384e-70f, 2.66576e-70f, -1.55248e-69f, -1.0336e-70f, 0.00664387f, -0.192518f, 0.117656f, 0.122138f, 0.0506351f, -0.132547f, -0.00898737f, 3.39709e-68f, -5.571e-70f, 0.0202081f, 0.110641f, -0.0146494f, 0.0638251f, -0.193644f, -0.0194371f, 0.000810983f),
    )
    val B1 = floatArrayOf(-0.103957f, -0.070338f, 0.473609f, 0.340871f, 0.0639118f, 1.07143f, 0.0739364f, 0.192565f, 0.213427f, -0.069621f, 0.82811f, -0.0614678f, 0.986781f, -0.316847f, 0.070922f, 0.325201f)
    val W2 = floatArrayOf(0.319321f, 0.385774f, -1.02634f, 1.13729f, -0.401442f, 0.781153f, -0.373759f, -1.15089f, 1.08746f, 0.389983f, 0.861906f, 0.40559f, -0.800516f, 0.902933f, -0.383277f, -1.12101f)
    const val B2 = 0.397774f





    // FIT END
}
