package com.nova.keyboard.language

import java.util.Locale

class TanglishEngine {

    // Each group contains spelling variations of the same
    // commonly used Roman Tanglish word.
    //
    // The first spelling is the canonical representation.
    private val spellingGroups = listOf(

        listOf("iniku", "inniku", "innaiku"),
        listOf("enaku", "enakku", "yenaku", "yenakku"),
        listOf("epdi", "eppadi", "eppdi"),
        listOf("iruka", "irukka"),
        listOf("iruken", "irukken"),
        listOf("iruku", "irukku"),
        listOf("veetuku", "veettuku", "veetukku"),
        listOf("saptiya", "saaptiya", "saptiyaa"),
        listOf("romba", "rompa"),
        listOf("panren", "pannuren"),
        listOf("panitu", "pannitu"),
        listOf("varen", "varren"),
        listOf("poren", "poreen"),
        listOf("nalla", "nala"),
        listOf("sollu", "solu"),
        listOf("seri", "sari"),
        listOf("unaku", "unakku"),
        listOf("unga", "ungga")
    )

    private val canonicalByVariant: Map<String, String> =
        spellingGroups
            .flatMap { group ->
                group.map { variant ->
                    variant to group.first()
                }
            }
            .toMap()

    private val variantsByCanonical: Map<String, List<String>> =
        spellingGroups.associate {
            it.first() to it
        }

    /**
     * Convert a known spelling into its canonical form.
     *
     * Example:
     * inniku -> iniku
     * eppadi -> epdi
     */
    fun canonicalizeToken(token: String): String {

        val normalized = token.lowercase(Locale.ROOT)

        return canonicalByVariant[normalized]
            ?: normalized
    }

    /**
     * Return known spelling variations of a word.
     */
    fun getVariants(token: String): List<String> {

        val normalized = token.lowercase(Locale.ROOT)

        val canonical = canonicalizeToken(normalized)

        return variantsByCanonical[canonical]
            ?: listOf(normalized)
    }

    /**
     * Check whether two spellings belong to the same
     * known Tanglish spelling group.
     */
    fun areEquivalent(
        first: String,
        second: String
    ): Boolean {

        return canonicalizeToken(first) ==
                canonicalizeToken(second)
    }

    /**
     * Return context combinations for the previous words.
     *
     * Example:
     * ["na", "inniku"]
     *
     * Produces context keys including:
     * "na inniku"
     * "na iniku"
     * "na innaiku"
     *
     * This helps contextual prediction recognise
     * spelling variations.
     */
    fun getContextVariants(
        tokens: List<String>
    ): List<String> {

        if (tokens.isEmpty()) {
            return emptyList()
        }

        val lastTokens = tokens
            .takeLast(2)
            .map { getVariants(it) }

        var combinations = listOf(emptyList<String>())

        for (variants in lastTokens) {

            combinations = combinations.flatMap { existing ->
                variants.map { variant ->
                    existing + variant
                }
            }
        }

        return combinations
            .map { it.joinToString(" ") }
            .distinct()
    }

    /**
     * Expand a partially typed word using known
     * spelling variations.
     *
     * Example:
     * prefix "inna" can find "innaiku".
     */
    fun matchPrefix(
        prefix: String,
        vocabulary: Collection<String>
    ): List<String> {

        if (prefix.isBlank()) {
            return emptyList()
        }

        val normalizedPrefix =
            prefix.lowercase(Locale.ROOT)

        val results = linkedMapOf<String, String>()

        for (word in vocabulary) {

            val canonical = canonicalizeToken(word)

            val matchingVariant = getVariants(word)
                .firstOrNull {
                    it.startsWith(normalizedPrefix)
                }
                ?: continue

            // Avoid displaying multiple spellings of the
            // same word as separate suggestions.
            if (!results.containsKey(canonical)) {
                results[canonical] = matchingVariant
            }
        }

        return results.values.toList()
    }

    /**
     * Canonicalise words for language-context comparison.
     * Original text should always be preserved separately.
     */
    fun canonicalizeTokens(
        tokens: List<String>
    ): List<String> {

        return tokens.map {
            canonicalizeToken(it)
        }
    }
}