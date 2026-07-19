/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axion.diagnostics.data

import android.content.Context
import com.axion.diagnostics.export.ReportFiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class ProcessSample(
    val timestamp: Long,
    val cpuPercent: Float,
    val rssKb: Long,
    val threads: Int
)

data class RegressionAlert(
    val processName: String,
    val pid: Int,
    val type: RegressionType,
    val severity: Severity,
    val message: String,
    val currentValue: Float,
    val baselineValue: Float,
    val changePercent: Float,
    val timestamp: Long
)

enum class RegressionType {
    CPU_SPIKE,
    CPU_SUSTAINED,
    MEMORY_LEAK,
    MEMORY_SPIKE,
    THREAD_GROWTH
}

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class ProcessHistory(
    val name: String,
    val pid: Int,
    val samples: MutableList<ProcessSample> = CopyOnWriteArrayList(),
    val alerts: MutableList<RegressionAlert> = CopyOnWriteArrayList()
) {
    val avgCpu: Float get() = if (samples.isEmpty()) {
        0f
    } else {
        samples.map { it.cpuPercent }.average().toFloat()
    }
    val avgRss: Long get() = if (samples.isEmpty()) {
        0L
    } else {
        samples.map { it.rssKb }.average().toLong()
    }
    val peakCpu: Float get() = samples.maxOfOrNull { it.cpuPercent } ?: 0f
    val peakRss: Long get() = samples.maxOfOrNull { it.rssKb } ?: 0L
    val currentCpu: Float get() = samples.lastOrNull()?.cpuPercent ?: 0f
    val currentRss: Long get() = samples.lastOrNull()?.rssKb ?: 0L

    val cpuTrend: Float get() {
        if (samples.size < 5) return 0f
        val recent = samples.takeLast(5).map { it.cpuPercent }.average().toFloat()
        val older = samples.take(
            5.coerceAtMost(samples.size / 2)
        ).map { it.cpuPercent }.average().toFloat()
        return if (older > 0.1f) ((recent - older) / older) * 100f else 0f
    }

    val rssTrend: Float get() {
        if (samples.size < 5) return 0f
        val recent = samples.takeLast(5).map { it.rssKb }.average().toFloat()
        val older = samples.take(
            5.coerceAtMost(samples.size / 2)
        ).map { it.rssKb }.average().toFloat()
        return if (older > 100f) ((recent - older) / older) * 100f else 0f
    }
}

object RegressionTracker {

    private const val MAX_SAMPLES_PER_PROCESS = 600
    private const val MIN_SAMPLES_FOR_ANALYSIS = 5
    private const val CPU_SPIKE_THRESHOLD = 40f
    private const val CPU_SUSTAINED_THRESHOLD = 15f
    private const val MEMORY_GROWTH_PERCENT = 20f
    private const val MEMORY_SPIKE_THRESHOLD_KB = 50_000L
    private const val THREAD_GROWTH_THRESHOLD = 10

    val processHistories = ConcurrentHashMap<String, ProcessHistory>()
    val activeAlerts = CopyOnWriteArrayList<RegressionAlert>()
    @Volatile var isTracking = false
        private set
    @Volatile var trackingStartTime = 0L
        private set
    @Volatile var sampleCount = 0
        private set

    fun startTracking() {
        isTracking = true
        trackingStartTime = System.currentTimeMillis()
        sampleCount = 0
    }

    fun stopTracking() {
        isTracking = false
    }

    fun clearHistory() {
        processHistories.clear()
        activeAlerts.clear()
        sampleCount = 0
        trackingStartTime = System.currentTimeMillis()
    }

    fun recordSample(processes: List<ProcessSnapshot>) {
        if (!isTracking) return
        val now = System.currentTimeMillis()
        sampleCount++

        for (proc in processes) {
            val key = "${proc.name}_${proc.pid}"
            val history = processHistories.computeIfAbsent(key) {
                ProcessHistory(proc.name, proc.pid)
            }

            history.samples.add(
                ProcessSample(now, proc.cpuPercent, proc.rssKb, proc.threads)
            )

            if (history.samples.size > MAX_SAMPLES_PER_PROCESS) {
                history.samples.removeAt(0)
            }

            if (history.samples.size >= MIN_SAMPLES_FOR_ANALYSIS) {
                analyzeProcess(history, now)
            }
        }

        processHistories.entries.removeAll { entry ->
            now - (entry.value.samples.lastOrNull()?.timestamp ?: 0L) > 120_000
        }
    }

    private fun analyzeProcess(history: ProcessHistory, now: Long) {
        checkCpuSpike(history, now)
        checkCpuSustained(history, now)
        checkMemoryLeak(history, now)
        checkMemorySpike(history, now)
        checkThreadGrowth(history, now)
    }

    private fun checkCpuSpike(history: ProcessHistory, now: Long) {
        val current = history.samples.last().cpuPercent
        if (current < CPU_SPIKE_THRESHOLD) return
        if (history.hasRecentAlert(RegressionType.CPU_SPIKE, now, 30_000)) return

        val baseline = history.avgCpu
        val alert = RegressionAlert(
            processName = history.name,
            pid = history.pid,
            type = RegressionType.CPU_SPIKE,
            severity = when {
                current > 80f -> Severity.CRITICAL
                current > 60f -> Severity.HIGH
                current > 40f -> Severity.MEDIUM
                else -> Severity.LOW
            },
            message = "CPU spike: %.1f%% (baseline %.1f%%)".format(current, baseline),
            currentValue = current,
            baselineValue = baseline,
            changePercent = if (baseline > 0) ((current - baseline) / baseline) * 100f else 0f,
            timestamp = now
        )
        addAlert(history, alert)
    }

    private fun checkCpuSustained(history: ProcessHistory, now: Long) {
        if (history.samples.size < 10) return
        val recent10 = history.samples.takeLast(10)
        val allAbove = recent10.all { it.cpuPercent > CPU_SUSTAINED_THRESHOLD }
        if (!allAbove) return
        if (history.hasRecentAlert(RegressionType.CPU_SUSTAINED, now, 60_000)) return

        val avgRecent = recent10.map { it.cpuPercent }.average().toFloat()
        val alert = RegressionAlert(
            processName = history.name,
            pid = history.pid,
            type = RegressionType.CPU_SUSTAINED,
            severity = when {
                avgRecent > 50f -> Severity.HIGH
                avgRecent > 30f -> Severity.MEDIUM
                else -> Severity.LOW
            },
            message = "Sustained CPU: %.1f%% avg over %d samples".format(avgRecent, recent10.size),
            currentValue = avgRecent,
            baselineValue = history.avgCpu,
            changePercent = history.cpuTrend,
            timestamp = now
        )
        addAlert(history, alert)
    }

    private fun checkMemoryLeak(history: ProcessHistory, now: Long) {
        if (history.samples.size < 10) return
        if (history.hasRecentAlert(RegressionType.MEMORY_LEAK, now, 120_000)) return

        val trend = history.rssTrend
        if (trend < MEMORY_GROWTH_PERCENT) return

        val firstHalf = history.samples.take(history.samples.size / 2)
        val secondHalf = history.samples.takeLast(history.samples.size / 2)
        val firstAvg = firstHalf.map { it.rssKb }.average().toLong()
        val secondAvg = secondHalf.map { it.rssKb }.average().toLong()

        if (secondAvg <= firstAvg) return

        val growth = secondAvg - firstAvg
        if (growth < 1024) return

        val alert = RegressionAlert(
            processName = history.name,
            pid = history.pid,
            type = RegressionType.MEMORY_LEAK,
            severity = when {
                growth > 100_000 -> Severity.CRITICAL
                growth > 50_000 -> Severity.HIGH
                growth > 10_000 -> Severity.MEDIUM
                else -> Severity.LOW
            },
            message = "Memory growing: +%d KB (%.0f%% increase)".format(growth, trend),
            currentValue = secondAvg.toFloat(),
            baselineValue = firstAvg.toFloat(),
            changePercent = trend,
            timestamp = now
        )
        addAlert(history, alert)
    }

    private fun checkMemorySpike(history: ProcessHistory, now: Long) {
        val current = history.samples.last().rssKb
        val avg = history.avgRss
        if (current - avg < MEMORY_SPIKE_THRESHOLD_KB) return
        if (history.hasRecentAlert(RegressionType.MEMORY_SPIKE, now, 60_000)) return

        val alert = RegressionAlert(
            processName = history.name,
            pid = history.pid,
            type = RegressionType.MEMORY_SPIKE,
            severity = when {
                current - avg > 200_000 -> Severity.CRITICAL
                current - avg > 100_000 -> Severity.HIGH
                else -> Severity.MEDIUM
            },
            message = "Memory spike: %d MB (avg %d MB)".format(current / 1024, avg / 1024),
            currentValue = current.toFloat(),
            baselineValue = avg.toFloat(),
            changePercent = if (avg > 0) ((current - avg).toFloat() / avg * 100f) else 0f,
            timestamp = now
        )
        addAlert(history, alert)
    }

    private fun checkThreadGrowth(history: ProcessHistory, now: Long) {
        if (history.samples.size < 10) return
        if (history.hasRecentAlert(RegressionType.THREAD_GROWTH, now, 120_000)) return

        val first = history.samples.first().threads
        val current = history.samples.last().threads
        val growth = current - first
        if (growth < THREAD_GROWTH_THRESHOLD) return

        val alert = RegressionAlert(
            processName = history.name,
            pid = history.pid,
            type = RegressionType.THREAD_GROWTH,
            severity = when {
                growth > 50 -> Severity.HIGH
                growth > 20 -> Severity.MEDIUM
                else -> Severity.LOW
            },
            message = "Thread growth: $first -> $current (+$growth)",
            currentValue = current.toFloat(),
            baselineValue = first.toFloat(),
            changePercent = if (first > 0) (growth.toFloat() / first * 100f) else 0f,
            timestamp = now
        )
        addAlert(history, alert)
    }

    private fun addAlert(history: ProcessHistory, alert: RegressionAlert) {
        history.alerts.add(alert)
        while (history.alerts.size > 50) {
            history.alerts.removeAt(0)
        }
        activeAlerts.add(0, alert)
        trimAlerts()
    }

    private fun trimAlerts() {
        while (activeAlerts.size > 200) {
            activeAlerts.removeAt(activeAlerts.size - 1)
        }
    }

    fun exportHistory(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.appendLine("========================================")
        sb.appendLine("  Regression Tracking Report")
        sb.appendLine("  Started: ${sdf.format(Date(trackingStartTime))}")
        sb.appendLine("  Generated: ${sdf.format(Date())}")
        sb.appendLine("  Samples: $sampleCount")
        sb.appendLine("========================================")
        sb.appendLine()

        val alertsByProcess = activeAlerts.toList().groupBy { it.processName }

        if (alertsByProcess.isNotEmpty()) {
            sb.appendLine("== ALERTS ==")
            alertsByProcess.forEach { (name, alerts) ->
                sb.appendLine()
                sb.appendLine("--- $name ---")
                alerts.forEach { alert ->
                    sb.appendLine("  [${alert.severity}] ${alert.type}: ${alert.message}")
                    sb.appendLine("    Time: ${sdf.format(Date(alert.timestamp))}")
                }
            }
            sb.appendLine()
        }

        sb.appendLine("== PROCESS SUMMARIES ==")
        sb.appendLine("%-25s %7s %7s %7s %7s %10s %10s %8s %8s".format(
            "Process", "AvgCPU", "PkCPU", "CurCPU", "CPUTnd",
            "AvgRSS", "PkRSS", "RSSTnd", "Samples"
        ))
        processHistories.values
            .filter { it.samples.size >= MIN_SAMPLES_FOR_ANALYSIS }
            .sortedByDescending { it.peakCpu }
            .forEach { h ->
                sb.appendLine(
                    "%-25s %6.1f%% %6.1f%% %6.1f%% %+6.0f%% %8dKB %8dKB %+6.0f%% %8d"
                        .format(
                            h.name.take(25),
                            h.avgCpu,
                            h.peakCpu,
                            h.currentCpu,
                            h.cpuTrend,
                            h.avgRss,
                            h.peakRss,
                            h.rssTrend,
                            h.samples.size
                        )
                )
            }

        return sb.toString()
    }

    private fun ProcessHistory.hasRecentAlert(
        type: RegressionType,
        now: Long,
        intervalMs: Long
    ): Boolean {
        return alerts.any { it.type == type && now - it.timestamp < intervalMs }
    }

    fun saveReport(context: Context): String =
        ReportFiles.write(context, "regression", exportHistory())
}
