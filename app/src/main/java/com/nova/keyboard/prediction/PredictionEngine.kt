package com.nova.keyboard.prediction

import com.nova.keyboard.data.UserLanguageModel
import com.nova.keyboard.language.TanglishEngine

data class WordSuggestion(
    val word: String,
    val score: Float,
    val source: SuggestionSource
)

enum class SuggestionSource {
    PREFIX,
    CONTEXT,
    SPELLING,
    PERSONAL
}

class PredictionEngine(
    personalModel: UserLanguageModel? = null,
    private val tanglishEngine: TanglishEngine = TanglishEngine()
) {

    companion object {
        private val WORD_REGEX =
            Regex("[\\p{L}\\p{N}']+")

        private const val BINARY_PREFIX_CANDIDATE_LIMIT = 32
        private const val BINARY_CONTEXT_CANDIDATE_LIMIT = 8
    }

    /**
     * The trained read-only corpus model is attached after background loading.
     * Volatile makes the fully-built immutable model visible to the UI thread.
     */
    @Volatile
    private var binaryModel: NovaBinaryModel? = null

    @Volatile
    private var personalModel: UserLanguageModel? = personalModel

    fun setBinaryModel(model: NovaBinaryModel) {
        binaryModel = model
    }

    /** Attach the local personal model after it has been loaded off the UI thread. */
    fun setPersonalModel(model: UserLanguageModel?) {
        personalModel = model
    }

    /**
     * Cheap lexical support query for the conservative ambiguous-touch path.
     * It never edits text: a correction is considered only when the raw prefix
     * is unsupported and exactly one spatial alternative has known support.
     */
    fun hasPrefixCandidate(prefix: String, usePersonalization: Boolean = true): Boolean {
        val normalized = prefix.lowercase()
        if (normalized.isBlank()) return false
        if (hasPrefixInSortedWords(sortedBuiltinVocabulary, normalized)) return true
        if (binaryModel?.hasPrefixCandidate(normalized) == true) return true
        return usePersonalization && personalModel?.hasPrefixCandidate(normalized) == true
    }

    /** Record a word only after an explicit commit or suggestion acceptance. */
    fun learnCompletedWord(word: String, previousWords: List<String>) {
        personalModel?.learn(word, previousWords)
    }

    // ------------------------------------------------
    // 1. VOCABULARY
    // ------------------------------------------------

    private val vocabulary = mapOf(

        // English
        "about" to 0.8f,
        "after" to 0.8f,
        "again" to 0.8f,
        "always" to 0.9f,
        "amazing" to 0.8f,
        "because" to 0.9f,
        "before" to 0.8f,
        "beautiful" to 0.8f,
        "college" to 1.0f,
        "complete" to 0.8f,
        "computer" to 0.9f,
        "competition" to 0.7f,
        "could" to 0.8f,
        "day" to 0.9f,
        "doing" to 0.9f,
        "everything" to 0.8f,
        "going" to 1.0f,
        "good" to 1.0f,
        "happy" to 0.9f,
        "have" to 1.0f,
        "hello" to 0.9f,
        "hope" to 0.8f,
        "how" to 1.0f,
        "important" to 0.8f,
        "just" to 1.0f,
        "love" to 0.9f,
        "make" to 0.9f,
        "maybe" to 0.8f,
        "mobile" to 0.9f,
        "need" to 1.0f,
        "nice" to 0.8f,
        "people" to 0.9f,
        "please" to 0.9f,
        "really" to 1.0f,
        "school" to 0.8f,
        "something" to 0.9f,
        "sorry" to 0.9f,
        "study" to 0.9f,
        "thank" to 0.9f,
        "thanks" to 1.0f,
        "that" to 1.0f,
        "there" to 1.0f,
        "think" to 0.9f,
        "today" to 1.0f,
        "tomorrow" to 0.9f,
        "very" to 1.0f,
        "want" to 1.0f,
        "what" to 1.0f,
        "when" to 0.9f,
        "where" to 0.9f,
        "which" to 0.9f,
        "with" to 1.0f,
        "work" to 0.9f,
        "would" to 0.9f,

        // Roman Tanglish
        "da" to 0.9f,
        "dei" to 0.8f,
        "enaku" to 0.9f,
        "enna" to 1.0f,
        "inniku" to 0.8f,
        "iniku" to 1.0f,
        "ippo" to 0.9f,
        "iruka" to 0.8f,
        "iruken" to 0.9f,
        "irukku" to 0.9f,
        "illa" to 1.0f,
        "mass" to 0.9f,
        "naan" to 0.9f,
        "naanga" to 0.8f,
        "nalla" to 0.9f,
        "namma" to 0.9f,
        "panra" to 0.9f,
        "pannuren" to 0.8f,
        "poren" to 0.9f,
        "poyitu" to 0.8f,
        "romba" to 0.9f,
        "saptiya" to 0.8f,
        "semma" to 0.9f,
        "sollu" to 0.9f,
        "super" to 0.9f,
        "varen" to 0.9f,
        "veetuku" to 0.8f,
        "veliaya" to 0.7f,
        "venum" to 0.9f,
        "v2ku" to 0.7f,

        // Additional Tanglish spellings
        "epdi" to 0.9f,
        "iruku" to 0.8f,
        "achu" to 0.8f,
        "gym" to 0.9f,
        "class" to 0.9f
    )

    // This small built-in lexicon is sorted once at engine construction. The
    // ambiguous-touch path then performs a binary search instead of scanning
    // every key in the map on the input/IME thread.
    private val sortedBuiltinVocabulary = vocabulary.keys.sorted()

    private fun hasPrefixInSortedWords(words: List<String>, prefix: String): Boolean {
        var low = 0
        var high = words.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (words[middle] < prefix) low = middle + 1 else high = middle
        }
        return low < words.size && words[low].startsWith(prefix)
    }

    // ------------------------------------------------
    // 2. CONTEXT MODEL
    // ------------------------------------------------

    private val contextModel =
        mapOf<String, List<Pair<String, Float>>>(

            // English
            "i" to listOf(
                "am" to 1.0f,
                "will" to 0.8f,
                "have" to 0.8f,
                "want" to 0.9f
            ),

            "i am" to listOf(
                "going" to 1.0f,
                "happy" to 0.8f,
                "not" to 0.7f,
                "trying" to 0.8f
            ),

            "am going" to listOf(
                "to" to 1.0f
            ),

            "going to" to listOf(
                "college" to 0.9f,
                "school" to 0.7f,
                "work" to 0.8f,
                "study" to 0.7f,
                "sleep" to 0.6f
            ),

            "how are" to listOf(
                "you" to 1.0f
            ),

            "thank you" to listOf(
                "so" to 0.8f,
                "very" to 0.8f
            ),

            "very" to listOf(
                "good" to 0.9f,
                "nice" to 0.7f,
                "happy" to 0.8f
            ),

            // Tanglish
            "na" to listOf(
                "iniku" to 1.0f,
                "inniku" to 0.8f,
                "ippo" to 0.7f,
                "naan" to 0.6f
            ),

            "na iniku" to listOf(
                "college" to 1.0f,
                "gym" to 0.8f,
                "veetuku" to 0.7f,
                "veliaya" to 0.6f
            ),

            "iniku" to listOf(
                "college" to 0.9f,
                "gym" to 0.8f,
                "class" to 0.7f,
                "veetuku" to 0.7f
            ),

            "college" to listOf(
                "poren" to 0.8f,
                "varen" to 0.7f,
                "la" to 0.5f
            ),

            "college ku" to listOf(
                "poren" to 1.0f,
                "varen" to 0.8f
            ),

            "enna" to listOf(
                "panra" to 1.0f,
                "venum" to 0.8f,
                "achu" to 0.6f
            ),

            "semma" to listOf(
                "mass" to 1.0f,
                "super" to 0.8f,
                "nalla" to 0.7f
            ),

            "romba" to listOf(
                "nalla" to 0.9f,
                "super" to 0.8f
            )
        )

    // ------------------------------------------------
    // 3. MAIN PREDICTION
    // ------------------------------------------------

    fun predict(
        textBeforeCursor: String,
        limit: Int = 3,
        usePersonalization: Boolean = true
    ): List<WordSuggestion> {

        if (textBeforeCursor.isBlank() || limit <= 0) {
            return emptyList()
        }

        // Only the current sentence may contribute prediction context. Without
        // this reset, words before a full stop/newline leak into the next sentence.
        val sentenceStart = textBeforeCursor.indexOfLast {
            it == '.' || it == '!' || it == '?' || it == ';' || it == '\n' || it == '\r'
        }
        val activeText = textBeforeCursor.substring(sentenceStart + 1)
        if (activeText.isBlank()) return emptyList()

        val lastChar = activeText.last()
        val tokens = WORD_REGEX
            .findAll(activeText.lowercase())
            .map { it.value }
            .toList()

        if (tokens.isEmpty()) {
            return emptyList()
        }

        val isNextWord =
            lastChar.isWhitespace() ||
                    lastChar == ',' ||
                    lastChar == ';' ||
                    lastChar == ':'

        return if (isNextWord) {

            predictNextWord(
                tokens,
                limit,
                usePersonalization
            )

        } else {

            predictCurrentWord(
                tokens,
                limit,
                usePersonalization
            )
        }
    }

    // ------------------------------------------------
    // 4. CURRENT WORD PREDICTION
    // ------------------------------------------------

    private fun predictCurrentWord(
        tokens: List<String>,
        limit: Int,
        usePersonalization: Boolean
    ): List<WordSuggestion> {

        val prefix = tokens.last()
        val previousWords = tokens.dropLast(1)

        val contextScores = getContextScores(
            previousWords,
            usePersonalization
        )

        val results = mutableMapOf<String, WordSuggestion>()

        // Regular prefix suggestions.
        val matchingWords = tanglishEngine.matchPrefix(
            prefix,
            vocabulary.keys
        )

        for (word in matchingWords) {

            val canonical =
                tanglishEngine.canonicalizeToken(word)

            val frequency =
                vocabulary[canonical]
                    ?: vocabulary[word]
                    ?: 0.7f

            val contextBoost =
                contextScores[canonical]
                    ?: contextScores[word]
                    ?: 0f

            results[word] = WordSuggestion(
                word,
                frequency + contextBoost * 2f,
                SuggestionSource.PREFIX
            )
        }

        // Corpus-trained vocabulary. Prefix lookup is binary-search based
        // inside NovaBinaryModel, so this does not scan the full vocabulary.
        binaryModel?.let { model ->
            val modelCandidates = model.prefixSuggestions(
                prefix,
                limit = BINARY_PREFIX_CANDIDATE_LIMIT
            )
            val strongestCount = modelCandidates.firstOrNull()?.count?.coerceAtLeast(1) ?: 1

            for (candidate in modelCandidates) {
                val word = candidate.word
                if (word == prefix) continue

                val canonical = tanglishEngine.canonicalizeToken(word)
                val relativeFrequency = candidate.count.toFloat() / strongestCount
                val contextBoost = contextScores[word]
                    ?: contextScores[canonical]
                    ?: 0f
                val score = 0.58f + 0.42f * relativeFrequency + contextBoost * 0.25f

                putBestSuggestion(
                    results,
                    WordSuggestion(word, score, SuggestionSource.PREFIX)
                )
            }
        }

        // Context words are valid completions even when their token has little
        // or no global unigram frequency. Use them directly when the typed
        // prefix matches, rather than requiring them to first appear in the
        // small built-in dictionary.
        for ((word, contextScore) in contextScores) {
            if (word.startsWith(prefix) && word != prefix) {
                putBestSuggestion(
                    results,
                    WordSuggestion(
                        word = word,
                        score = 0.72f + contextScore * 0.42f,
                        source = SuggestionSource.CONTEXT
                    )
                )
            }
        }

        // Personal vocabulary suggestions.
        if (usePersonalization) {

            personalModel
                ?.prefixMatches(prefix)
                ?.forEach { (word, personalScore) ->

                    val contextBoost =
                        contextScores[word]
                            ?: contextScores[
                                tanglishEngine.canonicalizeToken(word)
                            ]
                            ?: 0f

                    // Personal preferences grow gradually. A single occurrence
                    // must not outrank the corpus; repeated use earns a bounded boost.
                    val score =
                        0.56f +
                                personalScore * 0.55f +
                                contextBoost * 0.12f

                    val existing = results[word]

                    if (
                        existing == null ||
                        score > existing.score
                    ) {

                        results[word] = WordSuggestion(
                            word,
                            score,
                            SuggestionSource.PERSONAL
                        )
                    }
                }
        }

        // Add typo-tolerant suggestions when appropriate. First use the
        // lightweight built-in dictionary, then consult the much larger NOVM
        // vocabulary only when exact/context matches still leave empty slots.
        if (prefix.length >= 3) {
            val fuzzySuggestions = findSpellingSuggestions(prefix, contextScores)
            for (suggestion in fuzzySuggestions) {
                val existing = results[suggestion.word]
                if (existing == null || suggestion.score > existing.score) {
                    results[suggestion.word] = suggestion
                }
            }

        }

        return results.values
            .sortedByDescending { it.score }
            .take(limit)
    }

    // ------------------------------------------------
    // 5. NEXT WORD PREDICTION
    // ------------------------------------------------

    private fun predictNextWord(
        tokens: List<String>,
        limit: Int,
        usePersonalization: Boolean
    ): List<WordSuggestion> {

        val contextScores = getContextScores(
            tokens,
            usePersonalization
        )

        val contextual = contextScores
            .map { (word, score) ->
                WordSuggestion(word, score, SuggestionSource.CONTEXT)
            }
            .sortedByDescending { it.score }
            .take(limit)

        if (contextual.isNotEmpty()) return contextual

        // Unknown contexts should not make the whole suggestion strip look
        // broken. Offer conservative corpus-frequency fallbacks until a
        // stronger trigram/bigram or personal context becomes available.
        val corpusFallback = binaryModel?.topUnigrams(limit).orEmpty().mapIndexed { index, item ->
            WordSuggestion(
                word = item.word,
                score = 0.34f - index * 0.01f,
                source = SuggestionSource.CONTEXT
            )
        }
        if (corpusFallback.isNotEmpty()) return corpusFallback

        return vocabulary.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { WordSuggestion(it.key, 0.25f + it.value * 0.1f, SuggestionSource.CONTEXT) }
    }

    // ------------------------------------------------
    // 6. CONTEXT PREDICTION
    // ------------------------------------------------

    private fun getContextScores(
        tokens: List<String>,
        usePersonalization: Boolean
    ): Map<String, Float> {

        val scores = mutableMapOf<String, Float>()

        val contextVariants =
            tanglishEngine.getContextVariants(tokens)
        val exactContext = tokens.takeLast(2).joinToString(" ")

        for (key in contextVariants) {
            // The spelling actually typed is the strongest evidence. Known
            // alternatives still help, but should not outrank an exact-context
            // match merely because their corpus happened to be larger.
            val variantWeight = if (key == exactContext) 1f else 0.75f
            val predictions = contextModel[key] ?: continue

            for ((word, score) in predictions) {
                scores[word] = maxOf(
                    scores[word] ?: 0f,
                    score * variantWeight
                )
            }
        }

        // Corpus-trained next-word predictions. Keep exact trigram candidates
        // strongest, then merge the previous-word bigram as a lower-confidence
        // backoff pool. The old exclusive fallback discarded useful alternatives
        // whenever a trigram contained only one or two continuations.
        binaryModel?.let { model ->
            for (key in contextVariants) {
                val variantWeight = if (key == exactContext) 1f else 0.75f
                val contextWords = key.split(' ').filter { it.isNotBlank() }
                if (contextWords.isEmpty()) continue

                val previous = contextWords.last()
                val trigramCandidates = if (contextWords.size >= 2) {
                    model.trigramSuggestions(
                        contextWords[contextWords.size - 2],
                        previous,
                        limit = BINARY_CONTEXT_CANDIDATE_LIMIT
                    )
                } else {
                    emptyList()
                }
                val bigramCandidates = model.bigramSuggestions(
                    previous,
                    limit = BINARY_CONTEXT_CANDIDATE_LIMIT
                )

                if (trigramCandidates.isNotEmpty()) {
                    mergeBinaryCandidates(
                        scores,
                        trigramCandidates,
                        baseScore = 0.60f,
                        frequencyWeight = 0.50f,
                        scoreWeight = variantWeight
                    )
                    // Bigram candidates supplement, rather than replace, the
                    // more specific trigram results.
                    mergeBinaryCandidates(
                        scores,
                        bigramCandidates,
                        baseScore = 0.38f,
                        frequencyWeight = 0.40f,
                        scoreWeight = variantWeight
                    )
                } else {
                    mergeBinaryCandidates(
                        scores,
                        bigramCandidates,
                        baseScore = 0.55f,
                        frequencyWeight = 0.55f,
                        scoreWeight = variantWeight
                    )
                }
            }
        }

        if (usePersonalization) {

            for (key in contextVariants) {
                val variantWeight = if (key == exactContext) 1f else 0.75f
                personalModel
                    ?.contextSuggestions(key.split(" "))
                    ?.forEach { (word, score) ->
                        scores[word] = maxOf(
                            scores[word] ?: 0f,
                            score * 0.72f * variantWeight
                        )
                    }
            }
        }

        return scores
    }

    /** Merge a model candidate pool using a per-context relative frequency. */
    private fun mergeBinaryCandidates(
        destination: MutableMap<String, Float>,
        candidates: List<NovaBinaryModel.ScoredWord>,
        baseScore: Float,
        frequencyWeight: Float,
        scoreWeight: Float = 1f
    ) {
        if (candidates.isEmpty()) return
        val strongestCount = candidates.maxOf { it.count }.coerceAtLeast(1)

        for (candidate in candidates) {
            val relativeFrequency = candidate.count.toFloat() / strongestCount
            val score = (baseScore + frequencyWeight * relativeFrequency) * scoreWeight
            destination[candidate.word] = maxOf(
                destination[candidate.word] ?: 0f,
                score
            )
        }
    }

    /** Keep one visible spelling for each known Tanglish spelling group. */
    private fun putBestSuggestion(
        results: MutableMap<String, WordSuggestion>,
        suggestion: WordSuggestion
    ) {
        val canonical = tanglishEngine.canonicalizeToken(suggestion.word)
        val existingEntry = results.entries.firstOrNull {
            tanglishEngine.canonicalizeToken(it.key) == canonical
        }

        if (existingEntry == null || suggestion.score > existingEntry.value.score) {
            if (existingEntry != null) {
                results.remove(existingEntry.key)
            }
            results[suggestion.word] = suggestion
        }
    }

    // ------------------------------------------------
    // 7. SPELLING SUGGESTIONS
    // ------------------------------------------------

    private fun findSpellingSuggestions(
        input: String,
        contextScores: Map<String, Float>
    ): List<WordSuggestion> {

        val maxDistance = when {
            input.length <= 4 -> 1
            input.length <= 8 -> 1
            else -> 2
        }

        val candidates = linkedSetOf<String>()

        // Include ordinary dictionary words.
        for (word in vocabulary.keys) {
            candidates.add(word)
            candidates.addAll(
                tanglishEngine.getVariants(word)
            )
        }

        val results =
            mutableMapOf<String, WordSuggestion>()

        for (candidate in candidates) {

            // Exact prefixes are handled separately.
            if (candidate.startsWith(input)) continue

            if (kotlin.math.abs(candidate.length - input.length) > maxDistance) {
                continue
            }

            val distance = damerauLevenshtein(
                input,
                candidate
            )

            if (distance == 0 || distance > maxDistance) {
                continue
            }

            val canonical =
                tanglishEngine.canonicalizeToken(candidate)

            val frequency =
                vocabulary[canonical]
                    ?: vocabulary[candidate]
                    ?: 0.5f

            val contextBoost =
                contextScores[canonical]
                    ?: contextScores[candidate]
                    ?: 0f

            // Fuzzy suggestions have lower priority
            // than normal prefix matches.
            val score =
                0.50f +
                        (frequency * 0.10f) +
                        (contextBoost * 0.10f) -
                        (distance * 0.12f)

            val suggestion = WordSuggestion(
                word = candidate,
                score = score,
                source = SuggestionSource.SPELLING
            )

            val existing = results[canonical]

            if (
                existing == null ||
                suggestion.score > existing.score
            ) {
                results[canonical] = suggestion
            }
        }

        return results.values.toList()
    }

    // ------------------------------------------------
    // 8. DAMERAU-LEVENSHTEIN DISTANCE
    // ------------------------------------------------

    private fun damerauLevenshtein(
        first: String,
        second: String
    ): Int {

        val a = first.lowercase()
        val b = second.lowercase()

        val rows = a.length + 1
        val columns = b.length + 1

        val distance = Array(rows) {
            IntArray(columns)
        }

        for (i in 0 until rows) {
            distance[i][0] = i
        }

        for (j in 0 until columns) {
            distance[0][j] = j
        }

        for (i in 1 until rows) {

            for (j in 1 until columns) {

                val cost = if (
                    a[i - 1] == b[j - 1]
                ) 0 else 1

                distance[i][j] = minOf(
                    distance[i - 1][j] + 1,
                    distance[i][j - 1] + 1,
                    distance[i - 1][j - 1] + cost
                )

                // Handle adjacent letter transpositions.
                if (
                    i > 1 &&
                    j > 1 &&
                    a[i - 1] == b[j - 2] &&
                    a[i - 2] == b[j - 1]
                ) {

                    distance[i][j] = minOf(
                        distance[i][j],
                        distance[i - 2][j - 2] + 1
                    )
                }
            }
        }

        return distance[a.length][b.length]
    }
}