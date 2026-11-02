package com.nova.keyboard.prediction

import android.content.Context
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.util.HashMap
import java.util.zip.GZIPInputStream

/**
 * Read-only lookup model for NOVA's NOVM v1 binary asset.
 *
 * Important:
 * - Call load() from a background thread. Decompression and index construction
 *   are intentionally not performed on the keyboard/UI thread.
 * - This currently loads the exported V1 count model, not the V3
 *   Modified Kneser-Ney evaluation model.
 * - The class owns its in-memory indexes after loading; the input stream is
 *   closed by the load(InputStream) overload.
 */
class NovaBinaryModel private constructor(
    val metadata: Metadata,
    private val vocabulary: Array<String>,
    private val tokenToId: HashMap<String, Int>,
    private val unigramCounts: IntArray,
    private val unigramOrder: IntArray,
    /** Exact unigram counts where available; bounded context-frequency estimates otherwise. */
    private val prefixCounts: IntArray,
    private val bigrams: HashMap<Int, CandidateList>,
    private val trigrams: HashMap<Long, CandidateList>
) {
    data class Metadata(
        val modelName: String,
        val languages: String,
        val dataset: String,
        val datasetAuthor: String,
        val license: String,
        val attribution: String,
        val formatVersion: Int
    )

    private data class CandidateList(
        val tokenIds: IntArray,
        val counts: IntArray
    )

    data class ScoredWord(
        val word: String,
        val count: Int
    )

    val vocabularySize: Int
        get() = vocabulary.size

    val bigramContextCount: Int
        get() = bigrams.size

    val trigramContextCount: Int
        get() = trigrams.size

    /**
     * Returns up to [limit] next-word candidates.
     *
     * Trigram context is tried first, then bigram, then global unigram
     * frequency. The stored candidate order is already descending by count.
     *
     * [previousWord] and [wordBeforePrevious] should be normalized to
     * lowercase using the same token normalization used by the Python trainer.
     */
    fun suggestNext(
        previousWord: String?,
        wordBeforePrevious: String? = null,
        limit: Int = 5
    ): List<ScoredWord> {
        if (limit <= 0) return emptyList()

        if (!previousWord.isNullOrBlank() && !wordBeforePrevious.isNullOrBlank()) {
            val previousId = tokenToId[previousWord]
            val earlierId = tokenToId[wordBeforePrevious]

            if (previousId != null && earlierId != null) {
                val trigramKey = makeTrigramKey(earlierId, previousId)
                val trigramCandidates = trigrams[trigramKey]
                if (trigramCandidates != null && trigramCandidates.tokenIds.isNotEmpty()) {
                    return toScoredWords(trigramCandidates, limit)
                }
            }
        }

        if (!previousWord.isNullOrBlank()) {
            val previousId = tokenToId[previousWord]
            if (previousId != null) {
                val bigramCandidates = bigrams[previousId]
                if (bigramCandidates != null && bigramCandidates.tokenIds.isNotEmpty()) {
                    return toScoredWords(bigramCandidates, limit)
                }
            }
        }

        return topUnigrams(limit)
    }

    /** Returns direct bigram candidates without applying fallback. */
    fun bigramSuggestions(previousWord: String, limit: Int = 5): List<ScoredWord> {
        if (limit <= 0) return emptyList()
        val id = tokenToId[previousWord.lowercase()] ?: return emptyList()
        val candidates = bigrams[id] ?: return emptyList()
        return toScoredWords(candidates, limit)
    }

    /** Returns direct trigram candidates without applying fallback. */
    fun trigramSuggestions(
        wordBeforePrevious: String,
        previousWord: String,
        limit: Int = 5
    ): List<ScoredWord> {
        if (limit <= 0) return emptyList()
        val firstId = tokenToId[wordBeforePrevious.lowercase()] ?: return emptyList()
        val secondId = tokenToId[previousWord.lowercase()] ?: return emptyList()
        val candidates = trigrams[makeTrigramKey(firstId, secondId)] ?: return emptyList()
        return toScoredWords(candidates, limit)
    }

    /** Returns the most frequent words in the model. */
    fun topUnigrams(limit: Int = 5): List<ScoredWord> {
        if (limit <= 0) return emptyList()
        val resultSize = minOf(limit, unigramOrder.size)
        return ArrayList<ScoredWord>(resultSize).apply {
            for (index in 0 until resultSize) {
                val tokenId = unigramOrder[index]
                add(ScoredWord(vocabulary[tokenId], unigramCounts[tokenId]))
            }
        }
    }

    /**
     * Finds vocabulary entries beginning with [prefix], ranked by unigram
     * frequency. Vocabulary is stored lexicographically, so lookup starts
     * with binary search rather than scanning the entire vocabulary.
     *
     * Prefix matching is intentionally conservative: this method only finds
     * known vocabulary words and never changes the user's typed text.
     */
    fun prefixSuggestions(prefix: String, limit: Int = 5): List<ScoredWord> {
        val normalized = prefix.lowercase()
        if (normalized.isEmpty() || limit <= 0) return emptyList()

        var low = 0
        var high = vocabulary.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (vocabulary[middle] < normalized) {
                low = middle + 1
            } else {
                high = middle
            }
        }

        val matches = ArrayList<ScoredWord>()
        var index = low
        while (index < vocabulary.size) {
            val word = vocabulary[index]
            if (!word.startsWith(normalized)) break

            val rankingCount = prefixCounts[index]
            if (rankingCount > 0) {
                matches.add(ScoredWord(word, rankingCount))
            }
            index++
        }

        matches.sortWith(
            compareByDescending<ScoredWord> { it.count }
                .thenBy { it.word }
        )
        return matches.take(limit)
    }

    /**
     * Allocation-free prefix existence check for the rare ambiguous-touch path.
     * Vocabulary is sorted lexicographically, so only O(log N) comparisons are
     * needed. Context-only vocabulary entries are intentionally included.
     */
    fun hasPrefixCandidate(prefix: String): Boolean {
        val normalized = prefix.lowercase()
        if (normalized.isEmpty()) return false
        var low = 0
        var high = vocabulary.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (vocabulary[middle] < normalized) low = middle + 1 else high = middle
        }
        return low < vocabulary.size && vocabulary[low].startsWith(normalized)
    }

    fun containsToken(token: String): Boolean = tokenToId.containsKey(token.lowercase())

    private fun toScoredWords(
        candidates: CandidateList,
        limit: Int
    ): List<ScoredWord> {
        val resultSize = minOf(limit, candidates.tokenIds.size)
        return ArrayList<ScoredWord>(resultSize).apply {
            for (index in 0 until resultSize) {
                val tokenId = candidates.tokenIds[index]
                add(ScoredWord(vocabulary[tokenId], candidates.counts[index]))
            }
        }
    }

    private fun makeTrigramKey(firstId: Int, secondId: Int): Long {
        return (firstId.toLong() shl 32) or (secondId.toLong() and 0xFFFF_FFFFL)
    }

    companion object {
        const val DEFAULT_ASSET_NAME = "nova_tanglish_android_v1.novagz"
        private const val EXPECTED_MAGIC = "NOVM"
        private const val SUPPORTED_FORMAT_VERSION = 1
        private const val COMPRESSED_BUFFER_BYTES = 64 * 1024
        private const val DECOMPRESSED_BUFFER_BYTES = 64 * 1024
        private const val MAX_STRING_BYTES = 1_000_000
        private const val MAX_VOCABULARY_SIZE = 65_535
        private const val MAX_CONTEXT_COUNT = 5_000_000
        private const val MAX_CANDIDATES_PER_CONTEXT = 255

        /**
         * Opens the model from app/src/main/assets.
         * MUST be called off the main/UI thread.
         */
        fun load(
            context: Context,
            assetName: String = DEFAULT_ASSET_NAME
        ): NovaBinaryModel {
            return context.assets.open(assetName).use { input ->
                load(input)
            }
        }

        /**
         * Parses a NOVM gzip stream. The supplied stream is closed.
         * Public to allow JVM tests to load a fixture without Android Context.
         */
        fun load(input: InputStream): NovaBinaryModel {
            input.use { raw ->
                GZIPInputStream(
                    BufferedInputStream(raw, COMPRESSED_BUFFER_BYTES)
                ).use { gzip ->
                    // VarintReader consumes the decompressed stream one byte at a time.
                    // Buffer that stream too, otherwise every varint byte can trigger
                    // another GZIPInputStream read/decompression call.
                    val reader = VarintReader(
                        BufferedInputStream(gzip, DECOMPRESSED_BUFFER_BYTES)
                    )

                    val magic = reader.readFixedBytes(4, "magic")
                        .toString(Charsets.US_ASCII)
                    require(magic == EXPECTED_MAGIC) {
                        "Invalid NOVA model magic. Expected $EXPECTED_MAGIC, got $magic."
                    }

                    val formatVersion = reader.readVarInt("format version")
                    require(formatVersion == SUPPORTED_FORMAT_VERSION) {
                        "Unsupported NOVA model format $formatVersion. " +
                            "Supported: $SUPPORTED_FORMAT_VERSION."
                    }

                    val metadata = Metadata(
                        modelName = reader.readString("model name"),
                        languages = reader.readString("languages"),
                        dataset = reader.readString("dataset"),
                        datasetAuthor = reader.readString("dataset author"),
                        license = reader.readString("license"),
                        attribution = reader.readString("attribution"),
                        formatVersion = formatVersion
                    )

                    val vocabularySize = reader.readBoundedCount(
                        "vocabulary size",
                        MAX_VOCABULARY_SIZE
                    )
                    require(vocabularySize > 0) { "NOVA vocabulary is empty." }

                    val vocabulary = Array(vocabularySize) { index ->
                        reader.readString("vocabulary entry")
                    }

                    val tokenToId = HashMap<String, Int>(hashCapacity(vocabularySize))
                    for (id in vocabulary.indices) {
                        val previous = tokenToId.put(vocabulary[id], id)
                        require(previous == null) {
                            "Duplicate vocabulary token '${vocabulary[id]}'."
                        }
                    }

                    val unigramEntryCount = reader.readBoundedCount(
                        "unigram entry count",
                        vocabularySize
                    )
                    val unigramCounts = IntArray(vocabularySize)
                    val seenUnigrams = BooleanArray(vocabularySize)

                    repeat(unigramEntryCount) { row ->
                        val tokenId = reader.readBoundedCount(
                            "unigram token ID",
                            vocabularySize - 1
                        )
                        val count = reader.readVarInt("unigram count")
                        require(count > 0) { "Unigram[$row] has a zero count." }
                        require(!seenUnigrams[tokenId]) {
                            "Duplicate unigram token ID $tokenId."
                        }
                        seenUnigrams[tokenId] = true
                        unigramCounts[tokenId] = count
                    }

                    val unigramOrder = IntArray(unigramEntryCount)
                    var orderIndex = 0
                    // Exporter writes unigrams in descending count order.
                    // Reconstruct that order directly from the file rows would
                    // require another array, so derive it below.
                    // The file rows are read in order but token IDs are arbitrary.
                    // Store the frequency-ranked IDs while parsing in a later pass.
                    val idsByFrequency = ArrayList<Int>(unigramEntryCount)
                    for (id in unigramCounts.indices) {
                        if (seenUnigrams[id]) idsByFrequency.add(id)
                    }
                    idsByFrequency.sortWith(
                        compareByDescending<Int> { unigramCounts[it] }
                            .thenBy { vocabulary[it] }
                    )
                    for (id in idsByFrequency) {
                        unigramOrder[orderIndex++] = id
                    }

                    val bigramContextCount = reader.readBoundedCount(
                        "bigram context count",
                        MAX_CONTEXT_COUNT
                    )
                    val bigrams = HashMap<Int, CandidateList>(
                        hashCapacity(bigramContextCount)
                    )
                    // The export intentionally stores only 12,000 exact unigrams.
                    // Build lightweight fallback activity for the remaining
                    // vocabulary while the model is already being parsed off-thread.
                    val bigramContextActivity = IntArray(vocabularySize)
                    val bigramFollowerActivity = IntArray(vocabularySize)

                    repeat(bigramContextCount) {
                        val contextId = reader.readBoundedCount(
                            "bigram context ID",
                            vocabularySize - 1
                        )
                        val candidateCount = reader.readBoundedCount(
                            "bigram candidate count",
                            MAX_CANDIDATES_PER_CONTEXT
                        )
                        val candidateList = readCandidates(
                            reader,
                            vocabularySize,
                            candidateCount
                        )
                        var rowActivity = 0
                        for (candidateIndex in candidateList.tokenIds.indices) {
                            val candidateId = candidateList.tokenIds[candidateIndex]
                            val count = candidateList.counts[candidateIndex]
                            rowActivity = saturatedAdd(rowActivity, count)
                            bigramFollowerActivity[candidateId] = saturatedAdd(
                                bigramFollowerActivity[candidateId],
                                count
                            )
                        }
                        bigramContextActivity[contextId] = maxOf(
                            bigramContextActivity[contextId],
                            rowActivity
                        )
                        require(bigrams.put(contextId, candidateList) == null) {
                            "Duplicate bigram context ID $contextId."
                        }
                    }

                    val trigramContextCount = reader.readBoundedCount(
                        "trigram context count",
                        MAX_CONTEXT_COUNT
                    )
                    val trigrams = HashMap<Long, CandidateList>(
                        hashCapacity(trigramContextCount)
                    )
                    val trigramContextActivity = IntArray(vocabularySize)
                    val trigramFollowerActivity = IntArray(vocabularySize)

                    repeat(trigramContextCount) { row ->
                        val firstId = reader.readBoundedCount(
                            "trigram first context ID",
                            vocabularySize - 1
                        )
                        val secondId = reader.readBoundedCount(
                            "trigram second context ID",
                            vocabularySize - 1
                        )
                        val candidateCount = reader.readBoundedCount(
                            "trigram candidate count",
                            MAX_CANDIDATES_PER_CONTEXT
                        )
                        val key = (firstId.toLong() shl 32) or
                            (secondId.toLong() and 0xFFFF_FFFFL)
                        val candidateList = readCandidates(
                            reader,
                            vocabularySize,
                            candidateCount
                        )
                        var rowActivity = 0
                        for (candidateIndex in candidateList.tokenIds.indices) {
                            val candidateId = candidateList.tokenIds[candidateIndex]
                            val count = candidateList.counts[candidateIndex]
                            rowActivity = saturatedAdd(rowActivity, count)
                            trigramFollowerActivity[candidateId] = saturatedAdd(
                                trigramFollowerActivity[candidateId],
                                count
                            )
                        }
                        // A word can occur only in the first position of an
                        // exported trigram context. Preserve some evidence for it
                        // even when it has no unigram or bigram row of its own.
                        trigramContextActivity[firstId] = maxOf(
                            trigramContextActivity[firstId],
                            rowActivity
                        )
                        trigramContextActivity[secondId] = maxOf(
                            trigramContextActivity[secondId],
                            rowActivity
                        )
                        require(trigrams.put(key, candidateList) == null) {
                            "Duplicate trigram context at row $row."
                        }
                    }

                    require(reader.readOptionalTrailingByte() == -1) {
                        "Unexpected trailing data after NOVA trigram table."
                    }

                    val prefixCounts = unigramCounts.copyOf()
                    for (tokenId in prefixCounts.indices) {
                        if (prefixCounts[tokenId] == 0) {
                            // These are ranking proxies, not reconstructed corpus
                            // unigram counts. max() avoids counting the same word
                            // occurrence twice across overlapping n-gram tables.
                            val evidence = maxOf(
                                bigramContextActivity[tokenId],
                                bigramFollowerActivity[tokenId],
                                trigramContextActivity[tokenId],
                                trigramFollowerActivity[tokenId]
                            )
                            // Keep every vocabulary token discoverable. Entries
                            // with no surviving frequency evidence rank last.
                            prefixCounts[tokenId] = evidence.coerceAtLeast(1)
                        }
                    }

                    return NovaBinaryModel(
                        metadata = metadata,
                        vocabulary = vocabulary,
                        tokenToId = tokenToId,
                        unigramCounts = unigramCounts,
                        unigramOrder = unigramOrder,
                        prefixCounts = prefixCounts,
                        bigrams = bigrams,
                        trigrams = trigrams
                    )
                }
            }
        }

        private fun readCandidates(
            reader: VarintReader,
            vocabularySize: Int,
            candidateCount: Int
        ): CandidateList {
            val ids = IntArray(candidateCount)
            val counts = IntArray(candidateCount)

            repeat(candidateCount) { index ->
                val tokenId = reader.readBoundedCount(
                    "candidate token ID",
                    vocabularySize - 1
                )
                val count = reader.readVarInt("candidate count")
                require(count > 0) {
                    "Candidate $index has a zero count."
                }
                ids[index] = tokenId
                counts[index] = count
            }

            return CandidateList(ids, counts)
        }

        private fun saturatedAdd(first: Int, second: Int): Int {
            return if (first > Int.MAX_VALUE - second) Int.MAX_VALUE else first + second
        }

        private fun hashCapacity(expectedSize: Int): Int {
            if (expectedSize < 3) return expectedSize + 1
            return ((expectedSize / 0.75f).toInt() + 1)
        }
    }

    private class VarintReader(private val input: InputStream) {
        fun readFixedBytes(size: Int, label: String): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(result, offset, size - offset)
                if (count < 0) {
                    throw EOFException(
                        "Unexpected end of NOVA model while reading $label."
                    )
                }
                offset += count
            }
            return result
        }

        fun readVarInt(label: String): Int {
            var result = 0L
            var shift = 0

            while (shift <= 28) {
                val next = input.read()
                if (next < 0) {
                    throw EOFException(
                        "Unexpected end of NOVA model while reading $label."
                    )
                }

                val byteValue = next and 0xFF
                result = result or
                    ((byteValue and 0x7F).toLong() shl shift)

                if ((byteValue and 0x80) == 0) {
                    require(result <= Int.MAX_VALUE) {
                        "Value for $label exceeds the supported integer range."
                    }
                    return result.toInt()
                }

                shift += 7
            }

            throw IllegalArgumentException("Invalid or oversized varint for $label.")
        }

        fun readBoundedCount(label: String, maximum: Int): Int {
            val value = readVarInt(label)
            require(value in 0..maximum) {
                "$label is out of range: $value (maximum $maximum)."
            }
            return value
        }

        fun readString(label: String): String {
            val byteCount = readBoundedCount(label + " byte length", MAX_STRING_BYTES)
            val bytes = readFixedBytes(byteCount, label)
            return bytes.toString(Charsets.UTF_8)
        }

        fun readOptionalTrailingByte(): Int = input.read()

        companion object {
            private const val MAX_STRING_BYTES = 1_000_000
        }
    }
}
