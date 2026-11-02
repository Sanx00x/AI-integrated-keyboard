package com.nova.keyboard.prediction

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Geometry for one tappable letter/digit key, in the keyboard View's pixel space. */
data class SpatialKeyGeometry(
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rowIndex: Int = -1
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun isUsable(): Boolean =
        label.length == 1 && label[0].isLetterOrDigit() &&
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width > 0f && height > 0f
}

/**
 * Optional, explicitly-supervised per-key adjustment.
 * Offsets are fractions of key width/height, not raw pixels, so they survive
 * moderate keyboard resizing. Estimates with fewer than minCalibrationSamples
 * are deliberately ignored to avoid learning from a handful of accidental taps.
 */
data class SpatialKeyOffset(
    val meanOffsetXFraction: Float = 0f,
    val meanOffsetYFraction: Float = 0f,
    val sampleCount: Int = 0,
    val sigmaXMultiplier: Float = 1f,
    val sigmaYMultiplier: Float = 1f
)

data class SpatialKeyCandidate(
    val key: String,
    /** Probability normalized across every eligible letter/digit key. */
    val probability: Float,
    val logLikelihood: Double
)

data class SpatialTouchRanking(
    val candidates: List<SpatialKeyCandidate>,
    val top1Probability: Float,
    val entropyBits: Float,
    /** Sum of the probabilities returned in the truncated candidate list. */
    val returnedProbabilityMass: Float,
    val eligibleKeyCount: Int
) {
    val winner: String? get() = candidates.firstOrNull()?.key
}

/**
 * Cheap geometric gate for the expensive probabilistic decoder.
 *
 * A touch well inside a key is already unambiguous; ranking 26 candidates for
 * it adds work without changing the hard winner. We run the Gaussian model only
 * in the narrow Voronoi band around the closest letter-key boundary. The gate
 * uses the same row and centre coordinates as KeyboardView's hard decoder.
 */
internal object SpatialTouchGate {
    fun shouldRank(
        x: Float,
        y: Float,
        rawKey: String,
        rowIndex: Int,
        keys: List<SpatialKeyGeometry>,
        boundaryMarginPx: Float
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || rawKey.length != 1 ||
            !rawKey[0].isLetter() || rowIndex < 0 || boundaryMarginPx <= 0f
        ) return false

        var nearestDistanceSquared = Float.POSITIVE_INFINITY
        var secondDistanceSquared = Float.POSITIVE_INFINITY
        var nearestLabel: String? = null

        for (key in keys) {
            if (key.rowIndex != rowIndex || !key.isUsable() || !key.label[0].isLetter()) continue
            val dx = x - key.centerX
            val dy = y - key.centerY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < nearestDistanceSquared) {
                secondDistanceSquared = nearestDistanceSquared
                nearestDistanceSquared = distanceSquared
                nearestLabel = key.label
            } else if (distanceSquared < secondDistanceSquared) {
                secondDistanceSquared = distanceSquared
            }
        }

        if (nearestLabel == null || !nearestLabel.equals(rawKey, ignoreCase = true) ||
            !secondDistanceSquared.isFinite()
        ) return false

        // Difference in distance to the closest and second-closest key is zero
        // at their shared boundary and grows as the touch moves into a key.
        val boundaryDistance = kotlin.math.sqrt(secondDistanceSquared.toDouble()) -
            kotlin.math.sqrt(nearestDistanceSquared.toDouble())
        return boundaryDistance <= boundaryMarginPx
    }
}

/**
 * Gaussian soft-keyboard likelihood model used in SHADOW mode during Phase B1.
 *
 * It keeps alternatives alive instead of treating a tap as an unquestionable
 * character. It does not commit or change the selected key. A later word-level
 * decoder will combine these likelihoods with NOVA's language model.
 */
class SpatialTouchModel(
    private val sigmaXFactor: Double = 0.34,
    private val sigmaYFactor: Double = 0.32,
    private val minSigmaPx: Double = 4.0,
    private val contactScaleStrength: Double = 0.10,
    private val minCalibrationSamples: Int = 20,
    private val calibrationPriorSamples: Double = 30.0
) {
    // This instance is owned by KeyboardView and called on the IME main thread.
    // Reuse scratch arrays between boundary taps instead of allocating several
    // arrays for every key press. rank() is intentionally thread-confined.
    private var scratchCapacity = 0
    private var logLikelihoods = DoubleArray(0)
    private var probabilities = DoubleArray(0)
    private var valid = BooleanArray(0)
    private var topIndices = IntArray(0)
    private var topProbabilities = DoubleArray(0)

    init {
        require(sigmaXFactor > 0.0 && sigmaYFactor > 0.0)
        require(minSigmaPx > 0.0)
        require(contactScaleStrength >= 0.0)
        require(minCalibrationSamples >= 1)
        require(calibrationPriorSamples > 0.0)
    }

    private fun ensureScratchCapacity(keyCount: Int, topK: Int) {
        if (keyCount > scratchCapacity) {
            scratchCapacity = Integer.highestOneBit(keyCount - 1).let { if (it == 0) 1 else it shl 1 }
            logLikelihoods = DoubleArray(scratchCapacity)
            probabilities = DoubleArray(scratchCapacity)
            valid = BooleanArray(scratchCapacity)
        }
        if (topK > topIndices.size) {
            topIndices = IntArray(topK)
            topProbabilities = DoubleArray(topK)
        }
    }

    fun rank(
        x: Float,
        y: Float,
        keys: List<SpatialKeyGeometry>,
        touchMajorPx: Float = 0f,
        touchMinorPx: Float = 0f,
        keyOffsets: Map<String, SpatialKeyOffset> = emptyMap(),
        topK: Int = 3
    ): SpatialTouchRanking {
        if (!x.isFinite() || !y.isFinite() || topK <= 0 || keys.isEmpty()) {
            return emptyRanking()
        }

        // Primitive scratch arrays avoid building several filtered/mapped/sorted
        // collections for every touch. The model is invoked on the IME thread,
        // so keeping its common path allocation-light matters more than clever
        // object-oriented composition here.
        ensureScratchCapacity(keys.size, topK)
        java.util.Arrays.fill(valid, 0, keys.size, false)
        var eligibleCount = 0
        var maxLogLikelihood = Double.NEGATIVE_INFINITY

        for (index in keys.indices) {
            val geometry = keys[index]
            if (!geometry.isUsable()) continue

            val offset = trustedOffset(keyOffsets[geometry.label])
            val offsetX = offset?.meanOffsetXFraction
                ?.takeIf(Float::isFinite)?.coerceIn(-0.18f, 0.18f) ?: 0f
            val offsetY = offset?.meanOffsetYFraction
                ?.takeIf(Float::isFinite)?.coerceIn(-0.15f, 0.15f) ?: 0f
            val meanX = geometry.centerX + offsetX * geometry.width
            val meanY = geometry.centerY + offsetY * geometry.height

            val contactScale = contactScale(
                touchMajorPx,
                touchMinorPx,
                geometry.width,
                geometry.height
            )
            val sigmaXMultiplier = offset?.sigmaXMultiplier
                ?.takeIf(Float::isFinite)?.coerceIn(0.75f, 1.50f) ?: 1f
            val sigmaYMultiplier = offset?.sigmaYMultiplier
                ?.takeIf(Float::isFinite)?.coerceIn(0.75f, 1.50f) ?: 1f
            val sigmaX = max(
                minSigmaPx,
                geometry.width * sigmaXFactor * contactScale * sigmaXMultiplier
            )
            val sigmaY = max(
                minSigmaPx,
                geometry.height * sigmaYFactor * contactScale * sigmaYMultiplier
            )
            val dx = (x - meanX) / sigmaX
            val dy = (y - meanY) / sigmaY
            val logLikelihood = -0.5 * (dx * dx + dy * dy) - ln(sigmaX) - ln(sigmaY)

            valid[index] = true
            logLikelihoods[index] = logLikelihood
            eligibleCount++
            if (logLikelihood > maxLogLikelihood) maxLogLikelihood = logLikelihood
        }

        if (eligibleCount == 0) return emptyRanking()

        var total = 0.0
        for (index in keys.indices) {
            if (!valid[index]) continue
            val value = kotlin.math.exp(logLikelihoods[index] - maxLogLikelihood)
            probabilities[index] = value
            total += value
        }
        if (!total.isFinite() || total <= 0.0) return emptyRanking()

        var entropy = 0.0
        for (index in keys.indices) {
            if (!valid[index]) continue
            val probability = probabilities[index] / total
            probabilities[index] = probability
            if (probability > 0.0) entropy -= probability * (ln(probability) / ln(2.0))
        }

        val resultCount = min(topK, eligibleCount)
        java.util.Arrays.fill(topIndices, 0, resultCount, -1)
        java.util.Arrays.fill(topProbabilities, 0, resultCount, 0.0)
        var returnedMass = 0.0

        // Insertion-select only the requested top K. Tie order remains lexical,
        // matching the former probability sort's deterministic behaviour.
        for (index in keys.indices) {
            if (!valid[index]) continue
            val probability = probabilities[index]
            val label = keys[index].label
            var insertAt = resultCount
            for (slot in 0 until resultCount) {
                val existingIndex = topIndices[slot]
                if (existingIndex < 0 ||
                    probability > topProbabilities[slot] ||
                    (probability == topProbabilities[slot] && label < keys[existingIndex].label)
                ) {
                    insertAt = slot
                    break
                }
            }
            if (insertAt >= resultCount) continue
            for (slot in resultCount - 1 downTo insertAt + 1) {
                topIndices[slot] = topIndices[slot - 1]
                topProbabilities[slot] = topProbabilities[slot - 1]
            }
            topIndices[insertAt] = index
            topProbabilities[insertAt] = probability
        }

        val top = ArrayList<SpatialKeyCandidate>(resultCount)
        for (slot in 0 until resultCount) {
            val index = topIndices[slot]
            if (index < 0) continue
            val probability = topProbabilities[slot]
            returnedMass += probability
            top.add(
                SpatialKeyCandidate(
                    key = keys[index].label,
                    probability = probability.toFloat(),
                    logLikelihood = logLikelihoods[index]
                )
            )
        }

        return SpatialTouchRanking(
            candidates = top,
            top1Probability = topProbabilities.firstOrNull()?.toFloat() ?: 0f,
            entropyBits = entropy.toFloat(),
            returnedProbabilityMass = returnedMass.toFloat(),
            eligibleKeyCount = eligibleCount
        )
    }

    private fun trustedOffset(offset: SpatialKeyOffset?): SpatialKeyOffset? {
        if (offset == null || offset.sampleCount < minCalibrationSamples) return null
        // Shrink early estimates toward the unpersonalized prior. This avoids
        // letting a small labelled sample move a key centre too far.
        val reliability = (offset.sampleCount / (offset.sampleCount + calibrationPriorSamples))
            .toFloat()
        return SpatialKeyOffset(
            meanOffsetXFraction = offset.meanOffsetXFraction * reliability,
            meanOffsetYFraction = offset.meanOffsetYFraction * reliability,
            sampleCount = offset.sampleCount,
            sigmaXMultiplier = 1f + (offset.sigmaXMultiplier - 1f) * reliability,
            sigmaYMultiplier = 1f + (offset.sigmaYMultiplier - 1f) * reliability
        )
    }

    private fun contactScale(
        touchMajorPx: Float,
        touchMinorPx: Float,
        keyWidth: Float,
        keyHeight: Float
    ): Double {
        if (!touchMajorPx.isFinite() || !touchMinorPx.isFinite() ||
            touchMajorPx <= 0f || touchMinorPx <= 0f
        ) return 1.0

        // MotionEvent reports contact axes in pixels. Use them only as a small
        // uncertainty adjustment; devices can report zeros or noisy estimates.
        val contactDiameter = sqrt(touchMajorPx.toDouble() * touchMinorPx.toDouble())
        val keyScale = sqrt(keyWidth.toDouble() * keyHeight.toDouble()).coerceAtLeast(1.0)
        val relativeContact = (contactDiameter / keyScale).coerceIn(0.0, 1.0)
        return 1.0 + contactScaleStrength * relativeContact
    }

    private fun emptyRanking() = SpatialTouchRanking(
        candidates = emptyList(),
        top1Probability = 0f,
        entropyBits = 0f,
        returnedProbabilityMass = 0f,
        eligibleKeyCount = 0
    )
}

/** Aggregate-only diagnostics. No characters, coordinates, or typed text are retained. */
data class SpatialShadowSnapshot(
    val taps: Long,
    val winnerDisagreements: Long,
    val lowConfidenceTaps: Long,
    val meanEntropyBits: Double
)

class SpatialShadowStats(private val lowConfidenceThreshold: Float = 0.70f) {
    private var taps = 0L
    private var winnerDisagreements = 0L
    private var lowConfidenceTaps = 0L
    private var entropySum = 0.0

    @Synchronized
    fun record(ranking: SpatialTouchRanking, legacyWinner: String?) {
        if (ranking.eligibleKeyCount == 0 || legacyWinner == null) return
        taps++
        if (ranking.winner != legacyWinner) winnerDisagreements++
        if (ranking.top1Probability < lowConfidenceThreshold) lowConfidenceTaps++
        entropySum += ranking.entropyBits.toDouble()
    }

    @Synchronized
    fun snapshotAndReset(): SpatialShadowSnapshot? {
        if (taps == 0L) return null
        val result = SpatialShadowSnapshot(
            taps = taps,
            winnerDisagreements = winnerDisagreements,
            lowConfidenceTaps = lowConfidenceTaps,
            meanEntropyBits = entropySum / taps
        )
        taps = 0L
        winnerDisagreements = 0L
        lowConfidenceTaps = 0L
        entropySum = 0.0
        return result
    }
}
