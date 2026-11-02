package com.nova.keyboard.prediction

/**
 * Conservative lexical tie-breaker for uncertain physical taps.
 *
 * The physical/Gaussian model must first show a genuinely ambiguous contact.
 * A candidate is only accepted when the raw character would leave the current
 * word prefix unsupported and exactly one sufficiently likely alternative has
 * lexical support. The callback receives a one-character key label, not the
 * entire word prefix. Unknown words therefore remain under the user's control.
 */
internal object SpatialKeyCorrectionPolicy {
    private const val MAX_CONFIDENT_TOP1 = 0.84f
    private const val MIN_ALTERNATIVE_PROBABILITY = 0.12f
    private const val MIN_ALTERNATIVE_TO_RAW_RATIO = 1.28f

    fun resolve(
        rawKey: String,
        ranking: SpatialTouchRanking,
        hasPrefixCandidate: (String) -> Boolean
    ): String {
        // Accept both lower- and uppercase alphabetic taps. The model lookup is
        // normalized to lowercase; the selected correction preserves Shift state.
        if (rawKey.length != 1 || rawKey[0].lowercaseChar() !in 'a'..'z') return rawKey
        if (ranking.eligibleKeyCount < 2 || ranking.top1Probability >= MAX_CONFIDENT_TOP1) {
            return rawKey
        }

        val raw = rawKey.lowercase()
        val rawProbability = ranking.candidates
            .firstOrNull { it.key.equals(raw, ignoreCase = true) }
            ?.probability ?: return rawKey

        // Never change a physically selected letter if the typed prefix is
        // already represented in the base/personal lexicon. This is the main
        // protection against over-correcting names, slang and OOV words.
        if (hasPrefixCandidate(raw)) return rawKey

        var supportedAlternative: String? = null
        for (candidate in ranking.candidates) {
            if (candidate.key.equals(raw, ignoreCase = true)) continue
            if (candidate.probability < MIN_ALTERNATIVE_PROBABILITY) continue
            if (candidate.probability < rawProbability * MIN_ALTERNATIVE_TO_RAW_RATIO) continue
            if (!hasPrefixCandidate(candidate.key.lowercase())) continue
            // Ambiguous language evidence: preserve the physical key rather
            // than guessing between multiple plausible neighbours.
            if (supportedAlternative != null) return rawKey
            supportedAlternative = candidate.key
        }
        val alternative = supportedAlternative ?: return rawKey
        return if (rawKey[0].isUpperCase()) alternative.uppercase() else alternative.lowercase()
    }
}
