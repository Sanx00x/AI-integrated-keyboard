package com.nova.keyboard.diagnostics

import android.util.Log
import java.util.Locale
import kotlin.math.ceil

/**
 * Low-overhead, fixed-size latency sampler for temporary on-device diagnostics.
 *
 * Records on the IME main thread, keeps at most [batchSize] samples, and emits
 * one aggregate log line per batch. It never logs on every keystroke.
 */
internal class LatencyWindow(
    private val metricName: String,
    private val batchSize: Int = 128
) {
    companion object {
        private const val TAG = "NOVA-UI-PERF"
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }

    private val samples = LongArray(batchSize)
    private var sampleCount = 0

    init {
        require(batchSize >= 2) { "batchSize must be at least 2." }
    }

    @Synchronized
    fun recordNanos(durationNanos: Long) {
        if (sampleCount >= samples.size) {
            flush("BUFFER_FULL")
        }
        samples[sampleCount++] = durationNanos.coerceAtLeast(0L)
        if (sampleCount == samples.size) {
            flush("BATCH")
        }
    }

    @Synchronized
    fun flush(reason: String) {
        if (sampleCount == 0) return

        val sorted = samples.copyOf(sampleCount).apply { sort() }
        val averageMs = sorted.average() / NANOS_PER_MILLISECOND
        val p50Ms = sorted[(sampleCount - 1) / 2] / NANOS_PER_MILLISECOND
        val p95Index = (ceil(sampleCount * 0.95).toInt() - 1)
            .coerceIn(0, sampleCount - 1)
        val p95Ms = sorted[p95Index] / NANOS_PER_MILLISECOND
        val maxMs = sorted.last() / NANOS_PER_MILLISECOND

        Log.i(
            TAG,
            "METRIC=$metricName REASON=$reason N=$sampleCount " +
                "AVG_MS=${fmt(averageMs)} P50_MS=${fmt(p50Ms)} " +
                "P95_MS=${fmt(p95Ms)} MAX_MS=${fmt(maxMs)}"
        )
        sampleCount = 0
    }

    private fun fmt(value: Double): String =
        String.format(Locale.US, "%.3f", value)
}
