package com.nova.keyboard.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialTouchModelTest {

    private val model = SpatialTouchModel(minSigmaPx = 4.0)
    private val row = listOf(
        SpatialKeyGeometry("q", 0f, 0f, 100f, 80f),
        SpatialKeyGeometry("w", 100f, 0f, 200f, 80f),
        SpatialKeyGeometry("e", 200f, 0f, 300f, 80f)
    )

    @Test
    fun centerTapRanksItsOwnKeyFirst() {
        val result = model.rank(50f, 40f, row)
        assertEquals("q", result.winner)
        assertTrue(result.top1Probability > 0.90f)
        assertEquals(3, result.candidates.size)
    }

    @Test
    fun boundaryTapKeepsBothNeighboringKeysAsAlternatives() {
        val result = model.rank(100f, 40f, row, topK = 3)
        val byKey = result.candidates.associateBy { it.key }
        assertNotNull(byKey["q"])
        assertNotNull(byKey["w"])
        assertEquals(byKey["q"]!!.probability, byKey["w"]!!.probability, 0.0001f)
        assertTrue(result.top1Probability < 0.60f)
    }

    @Test
    fun probabilitiesAreNormalizedAcrossAllEligibleKeys() {
        val result = model.rank(100f, 40f, row, topK = 3)
        assertEquals(3, result.eligibleKeyCount)
        // The complete distribution sums to one; the returned Top-K mass can
        // be smaller only when the caller requests fewer candidates.
        assertTrue(result.returnedProbabilityMass in 0.999f..1.001f)
    }

    @Test
    fun probabilitiesChangeSmoothlyAroundKeyBoundary() {
        val left = model.rank(95f, 40f, row, topK = 3)
        val right = model.rank(105f, 40f, row, topK = 3)
        val leftQ = left.candidates.first { it.key == "q" }.probability
        val rightQ = right.candidates.first { it.key == "q" }.probability
        assertTrue(kotlin.math.abs(leftQ - rightQ) < 0.25f)
        assertTrue(leftQ > rightQ)
    }

    @Test
    fun largerTouchContactSlightlyWidensDistribution() {
        val small = model.rank(100f, 40f, row, touchMajorPx = 8f, touchMinorPx = 6f)
        val large = model.rank(100f, 40f, row, touchMajorPx = 70f, touchMinorPx = 55f)
        assertTrue(large.entropyBits > small.entropyBits)
    }

    @Test
    fun untrustedCalibrationIsIgnoredUntilMinimumSamples() {
        val baseline = model.rank(62f, 40f, row)
        val untrusted = model.rank(
            62f,
            40f,
            row,
            keyOffsets = mapOf("q" to SpatialKeyOffset(meanOffsetXFraction = 0.18f, sampleCount = 3))
        )
        assertEquals(baseline.candidates, untrusted.candidates)
    }

    @Test
    fun trustedCalibrationShiftsKeyLikelihoodConservatively() {
        val baseline = model.rank(62f, 40f, row)
        val calibrated = model.rank(
            62f,
            40f,
            row,
            keyOffsets = mapOf("q" to SpatialKeyOffset(meanOffsetXFraction = 0.18f, sampleCount = 200))
        )
        val baselineQ = baseline.candidates.first { it.key == "q" }.probability
        val calibratedQ = calibrated.candidates.first { it.key == "q" }.probability
        assertTrue(calibratedQ > baselineQ)
    }

    @Test
    fun invalidGeometryAndNonLetterKeysAreIgnored() {
        val keys = listOf(
            SpatialKeyGeometry("SPACE", 0f, 0f, 100f, 80f),
            SpatialKeyGeometry("x", 50f, 50f, 20f, 20f),
            SpatialKeyGeometry("?", 100f, 0f, 200f, 80f)
        )
        val result = model.rank(50f, 40f, keys)
        assertTrue(result.candidates.isEmpty())
        assertEquals(0, result.eligibleKeyCount)
    }

    @Test
    fun invalidInputReturnsEmptyRanking() {
        val nan = model.rank(Float.NaN, 0f, row)
        val empty = model.rank(0f, 0f, emptyList())
        assertFalse(nan.candidates.isNotEmpty())
        assertTrue(empty.candidates.isEmpty())
    }

    @Test
    fun shadowStatsStoreOnlyAggregateCountsAndReset() {
        val stats = SpatialShadowStats()
        val ranking = model.rank(100f, 40f, row)
        stats.record(ranking, legacyWinner = "w")
        val snapshot = stats.snapshotAndReset()
        assertNotNull(snapshot)
        assertEquals(1L, snapshot!!.taps)
        assertEquals(1L, snapshot.winnerDisagreements)
        assertTrue(snapshot.meanEntropyBits.isFinite())
        assertTrue(snapshot.meanEntropyBits > 0.0)
        assertTrue(stats.snapshotAndReset() == null)
    }
    @Test
    fun confidentCenterTapSkipsProbabilisticRanking() {
        val keys = listOf(
            SpatialKeyGeometry("q", 0f, 0f, 100f, 80f, rowIndex = 0),
            SpatialKeyGeometry("w", 100f, 0f, 200f, 80f, rowIndex = 0),
            SpatialKeyGeometry("e", 200f, 0f, 300f, 80f, rowIndex = 0)
        )
        assertFalse(
            SpatialTouchGate.shouldRank(
                x = 50f, y = 40f, rawKey = "q", rowIndex = 0,
                keys = keys, boundaryMarginPx = 18f
            )
        )
    }

    @Test
    fun nearBoundaryTapUsesProbabilisticRanking() {
        val keys = listOf(
            SpatialKeyGeometry("q", 0f, 0f, 100f, 80f, rowIndex = 0),
            SpatialKeyGeometry("w", 100f, 0f, 200f, 80f, rowIndex = 0),
            SpatialKeyGeometry("e", 200f, 0f, 300f, 80f, rowIndex = 0)
        )
        assertTrue(
            SpatialTouchGate.shouldRank(
                x = 98f, y = 40f, rawKey = "q", rowIndex = 0,
                keys = keys, boundaryMarginPx = 18f
            )
        )
    }

    @Test
    fun aTouchWhoseHardWinnerIsNotThePhysicalKeyDoesNotEnterTheGate() {
        val keys = listOf(
            SpatialKeyGeometry("q", 0f, 0f, 100f, 80f, rowIndex = 0),
            SpatialKeyGeometry("w", 100f, 0f, 200f, 80f, rowIndex = 0)
        )
        assertFalse(
            SpatialTouchGate.shouldRank(
                x = 102f, y = 40f, rawKey = "q", rowIndex = 0,
                keys = keys, boundaryMarginPx = 18f
            )
        )
    }

}
