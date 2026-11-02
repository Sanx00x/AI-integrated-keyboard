package com.nova.keyboard.data

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Small, offline personal language model.
 *
 * Only committed individual words and their preceding one/two-word context are
 * retained. Complete messages are never stored. Preference serialization runs
 * on a private background executor and is debounced to avoid work on the IME UI
 * thread for every keystroke.
 */
class UserLanguageModel(
    context: Context,
    preferenceName: String = DEFAULT_PREFERENCES
) : Closeable {

    data class Stats(
        val learnedWords: Int,
        val bigramContexts: Int,
        val trigramContexts: Int
    )

    private data class Snapshot(
        val words: Map<String, Int>,
        val bigrams: Map<String, Map<String, Int>>,
        val trigrams: Map<String, Map<String, Int>>
    )

    companion object {
        const val DEFAULT_PREFERENCES = "nova_personal_language"

        private const val MAX_WORDS = 3_000
        private const val MAX_BIGRAM_CONTEXTS = 1_800
        private const val MAX_TRIGRAM_CONTEXTS = 2_400
        private const val MAX_PREDICTIONS_PER_CONTEXT = 16
        private const val MAX_COUNT = 100
        private const val SAVE_DEBOUNCE_MS = 900L

        private val WORD_REGEX = Regex("[\\p{L}\\p{N}']+")
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    private val lock = Any()
    // Sorted word keys make ambiguous-touch prefix checks logarithmic plus the
    // number of matching prefixes, rather than scanning all 3,000 learned words.
    private val wordCounts = TreeMap<String, Int>().apply { putAll(loadCounts("words")) }
    private val bigrams = loadNestedCounts("bigrams")
    private val trigrams = loadNestedCounts("trigrams")

    private val saveExecutor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable ->
            Thread(runnable, "NOVA-personal-model-save").apply {
                isDaemon = true
            }
        }
    ).apply {
        removeOnCancelPolicy = true
    }

    private var pendingSave: ScheduledFuture<*>? = null
    private var closed = false

    /** Learn one explicitly committed word and at most two preceding words. */
    fun learn(completedWord: String, previousWords: List<String>) {
        synchronized(lock) {
            if (closed) return

            val word = normalize(completedWord)
            if (!isValidToken(word)) return

            increment(wordCounts, word)

            val context = previousWords
                .takeLast(2)
                .map(::normalize)
                .filter(::isValidToken)

            if (context.isNotEmpty()) {
                incrementNested(bigrams, context.last(), word)
            }
            if (context.size >= 2) {
                incrementNested(trigrams, context.joinToString(" "), word)
            }

            trimCounts(wordCounts, MAX_WORDS)
            trimNestedCounts(bigrams, MAX_BIGRAM_CONTEXTS)
            trimNestedCounts(trigrams, MAX_TRIGRAM_CONTEXTS)
            scheduleSaveLocked()
        }
    }

    /**
     * Prefix matches become eligible after a word has been learned twice.
     * This prevents a one-off typo from immediately becoming a preferred word.
     */
    fun prefixMatches(prefix: String): List<Pair<String, Float>> = synchronized(lock) {
        val normalizedPrefix = normalize(prefix)
        if (normalizedPrefix.isBlank()) return@synchronized emptyList()

        val matches = ArrayList<Pair<String, Float>>(10)
        // Walk the sorted map from the first possible match. ceilingEntry /
        // higherEntry avoid allocating a NavigableSubMap view on each refresh.
        var entry = wordCounts.ceilingEntry(normalizedPrefix)
        while (entry != null && entry.key.startsWith(normalizedPrefix)) {
            val word = entry.key
            val count = entry.value
            if (count >= 2) matches.add(word to (count.toFloat() / (count + 8f)))
            entry = wordCounts.higherEntry(word)
        }
        matches.sortByDescending { it.second }
        if (matches.size > 10) matches.subList(10, matches.size).clear()
        matches
    }

    /**
     * Fast boolean check used only for an ambiguous soft-key decision.
     * As with normal personal suggestions, a word must have been committed
     * at least twice before it can influence a correction.
     */
    fun hasPrefixCandidate(prefix: String): Boolean = synchronized(lock) {
        val normalizedPrefix = normalize(prefix)
        if (normalizedPrefix.isBlank()) return@synchronized false
        var entry = wordCounts.ceilingEntry(normalizedPrefix)
        while (entry != null && entry.key.startsWith(normalizedPrefix)) {
            if (entry.value >= 2) return@synchronized true
            entry = wordCounts.higherEntry(entry.key)
        }
        false
    }

    /** Return bounded scores for learned next words from one/two-word contexts. */
    fun contextSuggestions(previousWords: List<String>): Map<String, Float> = synchronized(lock) {
        val context = previousWords.takeLast(2).map(::normalize).filter(::isValidToken)
        if (context.isEmpty()) return@synchronized emptyMap()

        val results = mutableMapOf<String, Float>()
        if (context.size >= 2) {
            addContextScores(results, trigrams[context.joinToString(" ")], 1.0f)
        }
        addContextScores(results, bigrams[context.last()], 0.72f)
        results
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(wordCounts.size, bigrams.size, trigrams.size)
    }

    /** Clear all personal learning data. Changes are persisted asynchronously. */
    fun clear() {
        synchronized(lock) {
            if (closed) return
            wordCounts.clear()
            bigrams.clear()
            trigrams.clear()
            scheduleSaveLocked()
        }
    }

    /** Request an immediate background save without blocking the keyboard. */
    fun flush() {
        synchronized(lock) {
            if (closed) return
            pendingSave?.cancel(false)
            pendingSave = saveExecutor.schedule(
                { persistSnapshot() },
                0,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /** Queue a final save and stop accepting new learning events. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingSave?.cancel(false)
            pendingSave = null
            try {
                saveExecutor.execute { persistSnapshot() }
            } catch (_: RuntimeException) {
                // The executor may already be shutting down; the last debounced
                // save remains best-effort and no keyboard operation is blocked.
            }
            saveExecutor.shutdown()
        }
    }

    private fun addContextScores(
        destination: MutableMap<String, Float>,
        counts: Map<String, Int>?,
        weight: Float
    ) {
        if (counts == null) return
        for ((word, count) in counts) {
            val confidence = (count.toFloat() / (count + 5f)) * weight
            destination[word] = maxOf(destination[word] ?: 0f, confidence)
        }
    }

    private fun isValidToken(word: String): Boolean =
        word.length in 2..32 && word.any { it.isLetter() }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)

    private fun increment(map: MutableMap<String, Int>, key: String) {
        map[key] = ((map[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
    }

    private fun incrementNested(
        map: MutableMap<String, MutableMap<String, Int>>,
        context: String,
        word: String
    ) {
        val predictions = map.getOrPut(context) { mutableMapOf() }
        increment(predictions, word)
        while (predictions.size > MAX_PREDICTIONS_PER_CONTEXT) {
            val weakest = predictions.minByOrNull { it.value } ?: break
            predictions.remove(weakest.key)
        }
    }

    private fun trimCounts(map: MutableMap<String, Int>, maximum: Int) {
        while (map.size > maximum) {
            val weakest = map.minByOrNull { it.value } ?: break
            map.remove(weakest.key)
        }
    }

    private fun trimNestedCounts(
        map: MutableMap<String, MutableMap<String, Int>>,
        maximum: Int
    ) {
        while (map.size > maximum) {
            val weakest = map.minByOrNull { entry -> entry.value.values.sum() } ?: break
            map.remove(weakest.key)
        }
    }

    /** Called only while holding [lock]. */
    private fun scheduleSaveLocked() {
        pendingSave?.cancel(false)
        pendingSave = saveExecutor.schedule(
            { persistSnapshot() },
            SAVE_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun persistSnapshot() {
        val snapshot = synchronized(lock) {
            Snapshot(
                words = HashMap(wordCounts),
                bigrams = bigrams.mapValues { HashMap(it.value) },
                trigrams = trigrams.mapValues { HashMap(it.value) }
            )
        }

        try {
            val editor = preferences.edit()
                .putString("words", JSONObject(snapshot.words).toString())
                .putString("bigrams", nestedToJson(snapshot.bigrams).toString())
                .putString("trigrams", nestedToJson(snapshot.trigrams).toString())
            editor.commit()
        } catch (_: Exception) {
            // A failed preference write must never interrupt text entry.
        }
    }

    private fun loadCounts(key: String): MutableMap<String, Int> {
        val json = try {
            JSONObject(preferences.getString(key, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

        val result = mutableMapOf<String, Int>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val word = keys.next()
            val normalized = normalize(word)
            val count = json.optInt(word, 0).coerceIn(0, MAX_COUNT)
            if (isValidToken(normalized) && count > 0) result[normalized] = count
        }
        trimCounts(result, MAX_WORDS)
        return result
    }

    private fun loadNestedCounts(key: String): MutableMap<String, MutableMap<String, Int>> {
        val json = try {
            JSONObject(preferences.getString(key, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

        val result = mutableMapOf<String, MutableMap<String, Int>>()
        val contexts = json.keys()
        while (contexts.hasNext()) {
            val rawContext = contexts.next()
            val context = rawContext.split(' ').map(::normalize).filter(::isValidToken)
            if (context.isEmpty()) continue
            val nested = json.optJSONObject(rawContext) ?: continue
            val predictions = mutableMapOf<String, Int>()
            val words = nested.keys()
            while (words.hasNext()) {
                val rawWord = words.next()
                val word = normalize(rawWord)
                val count = nested.optInt(rawWord, 0).coerceIn(0, MAX_COUNT)
                if (isValidToken(word) && count > 0) predictions[word] = count
            }
            if (predictions.isNotEmpty()) {
                result[context.joinToString(" ")] = predictions
            }
        }
        trimNestedCounts(result, if (key == "bigrams") MAX_BIGRAM_CONTEXTS else MAX_TRIGRAM_CONTEXTS)
        return result
    }

    private fun nestedToJson(map: Map<String, Map<String, Int>>): JSONObject {
        val result = JSONObject()
        for ((context, predictions) in map) {
            result.put(context, JSONObject(predictions))
        }
        return result
    }
}
