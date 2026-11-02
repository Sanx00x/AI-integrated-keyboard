package com.nova.keyboard.prediction

import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Measures the retained memory cost of one NOVA binary model and of two
 * simultaneously retained model instances. This is diagnostic only: it does
 * not change production code or install a process-wide cache.
 */
@RunWith(AndroidJUnit4::class)
class NovaMemoryProfileInstrumentedTest {

    companion object {
        private const val TAG = "NOVA-MEM"
        private const val REPORT_NAME = "nova_memory_profile.txt"
    }

    private data class Snapshot(
        val heapUsedBytes: Long,
        val heapCommittedBytes: Long,
        val heapMaxBytes: Long,
        val nativeAllocatedBytes: Long,
        val totalPssKb: Int,
        val dalvikPssKb: Int,
        val nativePssKb: Int
    )

    @Test
    fun profileSingleAndDuplicateModelMemory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val report = File(context.cacheDir, REPORT_NAME)
        report.writeText("NOVA MEMORY PROFILE v1\n")

        fun record(line: String) {
            report.appendText(line + "\n")
            Log.i(TAG, line)
        }

        fun snapshot(): Snapshot {
            val runtime = Runtime.getRuntime()
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            return Snapshot(
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                heapCommittedBytes = runtime.totalMemory(),
                heapMaxBytes = runtime.maxMemory(),
                nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
                totalPssKb = memoryInfo.totalPss,
                dalvikPssKb = memoryInfo.dalvikPss,
                nativePssKb = memoryInfo.nativePss
            )
        }

        fun settleGc() {
            repeat(3) {
                System.gc()
                System.runFinalization()
                Thread.sleep(150)
            }
        }

        fun delta(after: Long, before: Long): Long = after - before
        fun kb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1024.0)

        settleGc()
        val baseline = snapshot()
        record(
            "BASELINE pid=${Process.myPid()} package=${context.packageName} " +
                "HEAP_USED_KB=${kb(baseline.heapUsedBytes)} " +
                "HEAP_COMMITTED_KB=${kb(baseline.heapCommittedBytes)} " +
                "HEAP_MAX_KB=${kb(baseline.heapMaxBytes)} " +
                "NATIVE_ALLOCATED_KB=${kb(baseline.nativeAllocatedBytes)} " +
                "PSS_TOTAL_KB=${baseline.totalPssKb} " +
                "PSS_DALVIK_KB=${baseline.dalvikPssKb} " +
                "PSS_NATIVE_KB=${baseline.nativePssKb}"
        )

        val firstStart = System.nanoTime()
        val firstModel = NovaBinaryModel.load(context)
        val firstLoadMs = (System.nanoTime() - firstStart) / 1_000_000.0
        settleGc()
        val afterFirst = snapshot()

        record(
            "ONE_MODEL load_ms=${fmt(firstLoadMs)} vocab=${firstModel.vocabularySize} " +
                "bigrams=${firstModel.bigramContextCount} trigrams=${firstModel.trigramContextCount} " +
                "HEAP_DELTA_KB=${kb(delta(afterFirst.heapUsedBytes, baseline.heapUsedBytes))} " +
                "NATIVE_DELTA_KB=${kb(delta(afterFirst.nativeAllocatedBytes, baseline.nativeAllocatedBytes))} " +
                "PSS_TOTAL_DELTA_KB=${afterFirst.totalPssKb - baseline.totalPssKb} " +
                "PSS_DALVIK_DELTA_KB=${afterFirst.dalvikPssKb - baseline.dalvikPssKb} " +
                "PSS_NATIVE_DELTA_KB=${afterFirst.nativePssKb - baseline.nativePssKb}"
        )

        // Deliberately hold both instances to estimate the cost of accidental
        // duplicate service loads. Both are released when this test process ends.
        val secondStart = System.nanoTime()
        val secondModel = NovaBinaryModel.load(context)
        val secondLoadMs = (System.nanoTime() - secondStart) / 1_000_000.0
        settleGc()
        val afterSecond = snapshot()

        record(
            "TWO_MODELS second_load_ms=${fmt(secondLoadMs)} " +
                "HEAP_INCREMENTAL_KB=${kb(delta(afterSecond.heapUsedBytes, afterFirst.heapUsedBytes))} " +
                "NATIVE_INCREMENTAL_KB=${kb(delta(afterSecond.nativeAllocatedBytes, afterFirst.nativeAllocatedBytes))} " +
                "PSS_TOTAL_INCREMENTAL_KB=${afterSecond.totalPssKb - afterFirst.totalPssKb} " +
                "PSS_DALVIK_INCREMENTAL_KB=${afterSecond.dalvikPssKb - afterFirst.dalvikPssKb} " +
                "PSS_NATIVE_INCREMENTAL_KB=${afterSecond.nativePssKb - afterFirst.nativePssKb}"
        )

        // Touch both objects after sampling so their references stay live through
        // the second memory snapshot and the JVM cannot treat either as dead.
        assertEquals(firstModel.vocabularySize, secondModel.vocabularySize)
        assertTrue(firstModel.topUnigrams(1).isNotEmpty())
        assertTrue(secondModel.topUnigrams(1).isNotEmpty())
        record("MEMORY_PROFILE_COMPLETE=true")
        record("REPORT_PATH=${report.absolutePath}")
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.3f", value)
}
