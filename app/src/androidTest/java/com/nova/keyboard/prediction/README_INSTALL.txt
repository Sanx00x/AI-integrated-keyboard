NOVA ON-DEVICE PERFORMANCE BENCHMARK
====================================

Purpose
-------
Measures the local model load time and prediction latency on the Android
phone. It also logs the top three suggestions for seven representative inputs.
This is a diagnostic test only. It does not change production source files,
learning data, or keyboard preferences.

Install
-------
Copy this file into the NOVA project at:
app/src/androidTest/java/com/nova/keyboard/prediction/NovaPredictionPerformanceTest.kt

Create the folders if they do not exist. Do not replace any existing files.

Run
---
In PowerShell:

cd D:\project_keyboard\NOVA
$env:JAVA_HOME = "D:\project_keyboard\android_studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :app:compileDebugAndroidTestKotlin --rerun-tasks
.\gradlew.bat :app:connectedDebugAndroidTest

Read benchmark output
---------------------
After the test completes, run:

& "D:\project_keyboard\android\sdk\platform-tools\adb.exe" logcat -d -s NOVA-BENCH:I

Send the output lines containing MODEL_LOAD_MS, CASE=, and
BENCHMARK_COMPLETE. The test is intentionally not given a fixed timing
pass/fail threshold; first we measure real Nord 5 performance, then choose
optimizations based on the results.

Expected device-side result
---------------------------
The instrumentation report should include the existing tests plus one new
NovaPredictionPerformanceTest. The benchmark prints load time, average,
median (P50), 95th percentile (P95), maximum latency, and top-three results.
