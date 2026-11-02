package com.nova.keyboard.prediction

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * Short, device-side benchmark for NOVA's local prediction path.
 * Writes a durable report to the target app cache so results can be pulled
 * even when Android logcat output is unavailable.
 */
@RunWith(AndroidJUnit4::class)
class NovaPredictionPerformanceTest {

    companion object {
        private const val TAG = "NOVA-BENCH"
        private const val WARMUP_ITERATIONS = 20
        private const val MEASURE_ITERATIONS = 50
        private const val REPORT_NAME = "nova_prediction_benchmark.txt"
    }

    @Test
    fun benchmarkLocalModelLoadAndPredictionLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.cacheDir, REPORT_NAME)
        reportFile.writeText("NOVA ON-DEVICE BENCHMARK v2\n")

        fun record(line: String) {
            reportFile.appendText(line + "\n")
            Log.i(TAG, line)
        }

        val loadStart = System.nanoTime()
        val model = NovaBinaryModel.load(context)
        val modelLoadMs = (System.nanoTime() - loadStart) / 1_000_000.0
        val engine = PredictionEngine()
        engine.setBinaryModel(model)

        record(
            "MODEL_LOAD_MS=${fmt(modelLoadMs)} " +
                "VOCAB=${model.vocabularySize} BIGRAMS=${model.bigramContextCount} " +
                "TRIGRAMS=${model.trigramContextCount}"
        )

        val scenarios = listOf(
            "prefix_in" to "in",
            "english_bigram" to "i am ",
            "english_trigram" to "i am going ",
            "tanglish_bigram" to "semma ",
            "tanglish_trigram" to "na iniku ",
            "code_mixed" to "bro office ",
            "phrase_context" to "assignment submit "
        )

        for ((name, input) in scenarios) {
            repeat(WARMUP_ITERATIONS) {
                engine.predict(input, limit = 3, usePersonalization = false)
            }

            val samples = LongArray(MEASURE_ITERATIONS)
            var topSuggestions = emptyList<String>()
            for (index in 0 until MEASURE_ITERATIONS) {
                val start = System.nanoTime()
                val result = engine.predict(input, limit = 3, usePersonalization = false)
                samples[index] = System.nanoTime() - start
                if (index == MEASURE_ITERATIONS - 1) {
                    topSuggestions = result.map { it.word }
                }
            }

            samples.sort()
            val averageMs = samples.average() / 1_000_000.0
            val p50Ms = samples[samples.size / 2] / 1_000_000.0
            val p95Index = (ceil(samples.size * 0.95).toInt() - 1).coerceIn(0, samples.lastIndex)
            val p95Ms = samples[p95Index] / 1_000_000.0
            val maxMs = samples.last() / 1_000_000.0
            record(
                "CASE=$name INPUT=[$input] AVG_MS=${fmt(averageMs)} " +
                    "P50_MS=${fmt(p50Ms)} P95_MS=${fmt(p95Ms)} " +
                    "MAX_MS=${fmt(maxMs)} TOP3=$topSuggestions"
            )
            assertTrue("Prediction call returned an invalid negative duration", p50Ms >= 0.0)
        }

        record("BENCHMARK_COMPLETE=true")
        record("REPORT_PATH=${reportFile.absolutePath}")
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)
}
