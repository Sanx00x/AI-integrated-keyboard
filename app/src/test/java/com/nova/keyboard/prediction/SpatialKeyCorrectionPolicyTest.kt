package com.nova.keyboard.prediction

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialKeyCorrectionPolicyTest {
    private fun ranking(raw: String, alternative: String, rawProbability: Float = 0.35f,
                        alternativeProbability: Float = 0.55f,
                        top1: Float = alternativeProbability) = SpatialTouchRanking(
        candidates = listOf(
            SpatialKeyCandidate(alternative, alternativeProbability, -1.0),
            SpatialKeyCandidate(raw, rawProbability, -1.4),
            SpatialKeyCandidate("x", 1f - alternativeProbability - rawProbability, -2.0)
        ),
        top1Probability = top1,
        entropyBits = 1.2f,
        returnedProbabilityMass = 1f,
        eligibleKeyCount = 26
    )

    @Test
    fun switchesOnlyWhenRawPrefixIsUnsupportedAndOneAlternativeIsSupported() {
        val result = SpatialKeyCorrectionPolicy.resolve("g", ranking("g", "l")) { it == "l" }
        assertEquals("l", result)
    }

    @Test
    fun keepsRawKeyWhenItsPrefixIsKnown() {
        val result = SpatialKeyCorrectionPolicy.resolve("g", ranking("g", "l")) {
            it == "g" || it == "l"
        }
        assertEquals("g", result)
    }

    @Test
    fun keepsRawKeyWhenNoAlternativeIsSupported() {
        val result = SpatialKeyCorrectionPolicy.resolve("g", ranking("g", "l")) { false }
        assertEquals("g", result)
    }

    @Test
    fun keepsRawKeyWhenMultipleAlternativesArePlausible() {
        // Both l and h must independently pass the policy's 1.28x-over-raw
        // probability threshold. The old fixture's h=0.17f could not pass
        // that threshold against raw g=0.35f, so it did not actually test
        // the multiple-plausible-alternatives branch.
        val ambiguous = ranking("g", "l").copy(
            candidates = listOf(
                SpatialKeyCandidate("l", 0.30f, -1.0),
                SpatialKeyCandidate("h", 0.27f, -1.1),
                SpatialKeyCandidate("g", 0.20f, -1.4)
            ),
            top1Probability = 0.30f,
            returnedProbabilityMass = 0.77f
        )
        val result = SpatialKeyCorrectionPolicy.resolve("g", ambiguous) {
            it == "l" || it == "h"
        }
        assertEquals("g", result)
    }

    @Test
    fun keepsRawKeyWhenPhysicalModelIsConfident() {
        val confident = ranking("g", "l", rawProbability = 0.25f,
            alternativeProbability = 0.88f, top1 = 0.88f)
        val result = SpatialKeyCorrectionPolicy.resolve("g", confident) { it == "l" }
        assertEquals("g", result)
    }

    @Test
    fun correctionPreservesUppercaseState() {
        val result = SpatialKeyCorrectionPolicy.resolve("G", ranking("g", "l")) { it == "l" }
        assertEquals("L", result)
    }

    @Test
    fun nonLatinAndNonLetterInputsAreNeverCorrected() {
        val result = SpatialKeyCorrectionPolicy.resolve("₹", ranking("g", "l")) { true }
        assertEquals("₹", result)
    }
}
