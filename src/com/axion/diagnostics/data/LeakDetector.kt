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

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class MemorySample(
    val timestamp: Long,
    val rssKb: Long,
    val pssKb: Long,
    val vssKb: Long,
    val swapKb: Long
)

data class LeakCandidate(
    val pid: Int,
    val name: String,
    val uid: Int,
    val currentRssKb: Long,
    val startRssKb: Long,
    val peakRssKb: Long,
    val growthKb: Long,
    val growthPercent: Float,
    val growthRateKbPerMin: Float,
    val isLeaking: Boolean,
    val confidence: LeakConfidence,
    val samples: List<MemorySample>,
    val monotonic: Boolean
)

enum class LeakConfidence {
    LOW, MEDIUM, HIGH, CERTAIN
}

object LeakDetector {

    private val processMemory = ConcurrentHashMap<Int, CopyOnWriteArrayList<MemorySample>>()
    private val processNames = ConcurrentHashMap<Int, String>()
    private val processUids = ConcurrentHashMap<Int, Int>()
    private const val MAX_SAMPLES = 600
    private const val MIN_SAMPLES = 8

    @Volatile var isTracking = false
        private set
    @Volatile var trackingStartTime = 0L
        private set

    fun startTracking() {
        isTracking = true
        trackingStartTime = System.currentTimeMillis()
        processMemory.clear()
        processNames.clear()
        processUids.clear()
    }

    fun stopTracking() {
        isTracking = false
    }

    fun recordSamples() {
        if (!isTracking) return
        val now = System.currentTimeMillis()

        val procDir = File("/proc")
        procDir.listFiles()?.filter { it.name.all { c -> c.isDigit() } }?.forEach { pidDir ->
            val pid = pidDir.name.toIntOrNull() ?: return@forEach
            val status = readStatus(pidDir) ?: return@forEach

            val samples = processMemory.computeIfAbsent(pid) { CopyOnWriteArrayList() }
            samples.add(
                MemorySample(
                    timestamp = now,
                    rssKb = status.rssKb,
                    pssKb = readPss(pid),
                    vssKb = status.vssKb,
                    swapKb = status.swapKb
                )
            )
            if (samples.size > MAX_SAMPLES) samples.removeAt(0)

            if (!processNames.containsKey(pid)) {
                processNames[pid] = status.name
                processUids[pid] = status.uid
            }
        }

        processMemory.keys.retainAll { pid ->
            File("/proc/$pid").exists()
        }
        val activePids = processMemory.keys
        processNames.keys.retainAll(activePids)
        processUids.keys.retainAll(activePids)
    }

    fun getLeakCandidates(): List<LeakCandidate> {
        val candidates = mutableListOf<LeakCandidate>()

        for ((pid, samples) in processMemory) {
            if (samples.size < MIN_SAMPLES) continue
            val name = processNames[pid] ?: continue
            val uid = processUids[pid] ?: 0

            val first = samples.first()
            val last = samples.last()
            val peak = samples.maxByOrNull { it.rssKb } ?: continue

            val growthKb = last.rssKb - first.rssKb
            val elapsed = (last.timestamp - first.timestamp).toFloat()
            if (elapsed < 10_000) continue

            val growthPercent = if (first.rssKb > 0) {
                (growthKb.toFloat() / first.rssKb * 100f)
            } else 0f

            val growthRateKbPerMin = if (elapsed > 0) {
                growthKb.toFloat() / (elapsed / 60_000f)
            } else 0f

            val monotonic = isMonotonicGrowth(samples)
            val confidence = assessConfidence(samples, growthPercent, monotonic, growthKb)
            val isLeaking = confidence >= LeakConfidence.MEDIUM && growthKb > 1024

            candidates.add(
                LeakCandidate(
                    pid = pid,
                    name = name,
                    uid = uid,
                    currentRssKb = last.rssKb,
                    startRssKb = first.rssKb,
                    peakRssKb = peak.rssKb,
                    growthKb = growthKb,
                    growthPercent = growthPercent,
                    growthRateKbPerMin = growthRateKbPerMin,
                    isLeaking = isLeaking,
                    confidence = confidence,
                    samples = samples.toList(),
                    monotonic = monotonic
                )
            )
        }

        return candidates.sortedWith(
            compareByDescending<LeakCandidate> { it.isLeaking }
                .thenByDescending { it.confidence }
                .thenByDescending { it.growthKb }
        )
    }

    private fun isMonotonicGrowth(samples: List<MemorySample>): Boolean {
        if (samples.size < 5) return false
        val windowSize = 3.coerceAtMost(samples.size / 3)
        val windows = samples.windowed(windowSize, windowSize)
        if (windows.size < 3) return false

        val avgPerWindow = windows.map { w -> w.map { it.rssKb }.average() }
        var increasing = 0
        for (i in 1 until avgPerWindow.size) {
            if (avgPerWindow[i] > avgPerWindow[i - 1]) increasing++
        }
        return increasing.toFloat() / (avgPerWindow.size - 1) >= 0.75f
    }

    private fun assessConfidence(
        samples: List<MemorySample>,
        growthPercent: Float,
        monotonic: Boolean,
        growthKb: Long
    ): LeakConfidence {
        var score = 0
        if (monotonic) score += 3
        if (growthPercent > 50f) score += 3
        else if (growthPercent > 20f) score += 2
        else if (growthPercent > 10f) score += 1
        if (growthKb > 100_000) score += 2
        else if (growthKb > 50_000) score += 1
        if (samples.size > 30) score += 1

        val rssValues = samples.map { it.rssKb.toDouble() }
        val correlation = linearCorrelation(rssValues)
        if (correlation > 0.9) score += 3
        else if (correlation > 0.7) score += 2
        else if (correlation > 0.5) score += 1

        return when {
            score >= 8 -> LeakConfidence.CERTAIN
            score >= 5 -> LeakConfidence.HIGH
            score >= 3 -> LeakConfidence.MEDIUM
            else -> LeakConfidence.LOW
        }
    }

    private fun linearCorrelation(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val n = values.size
        val x = (0 until n).map { it.toDouble() }
        val meanX = x.average()
        val meanY = values.average()
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = values[i] - meanY
            sumXY += dx * dy
            sumX2 += dx * dx
            sumY2 += dy * dy
        }
        val denom = kotlin.math.sqrt(sumX2 * sumY2)
        return if (denom > 0) (sumXY / denom).coerceIn(0.0, 1.0) else 0.0
    }

    private data class ProcStatus(
        val name: String,
        val uid: Int,
        val rssKb: Long,
        val vssKb: Long,
        val swapKb: Long
    )

    private fun readStatus(pidDir: File): ProcStatus? {
        var name = ""
        var uid = 0
        var rss = 0L
        var vss = 0L
        var swap = 0L
        runCatching {
            File(pidDir, "status").forEachLine { line ->
                when {
                    line.startsWith("Name:") -> name = line.substringAfter(":").trim()
                    line.startsWith("Uid:") -> uid = line.split("\\s+".toRegex()).getOrNull(1)?.toIntOrNull() ?: 0
                    line.startsWith("VmRSS:") -> rss = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0
                    line.startsWith("VmSize:") -> vss = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0
                    line.startsWith("VmSwap:") -> swap = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0
                }
            }
        } ?: return null
        if (name.isEmpty()) return null
        return ProcStatus(name, uid, rss, vss, swap)
    }

    private fun readPss(pid: Int): Long {
        val file = File("/proc/$pid/smaps_rollup")
        if (!file.exists()) return 0L
        val line = runCatching {
            file.readLines().firstOrNull { it.startsWith("Pss:") }
        }.getOrNull() ?: return 0L
        return line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
    }

    fun exportReport(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.appendLine("== MEMORY LEAK DETECTION REPORT ==")
        sb.appendLine("Started: ${sdf.format(Date(trackingStartTime))}")
        sb.appendLine("Generated: ${sdf.format(Date())}")
        sb.appendLine()

        val candidates = getLeakCandidates()
        val leaking = candidates.filter { it.isLeaking }

        if (leaking.isNotEmpty()) {
            sb.appendLine("LEAK CANDIDATES:")
            leaking.forEach { c ->
                sb.appendLine("  [${c.confidence}] ${c.name} (PID: ${c.pid})")
                sb.appendLine("    RSS: ${c.startRssKb}KB -> ${c.currentRssKb}KB (+${c.growthKb}KB, %.1f%%)".format(c.growthPercent))
                sb.appendLine("    Growth rate: %.1f KB/min".format(c.growthRateKbPerMin))
                sb.appendLine("    Monotonic: ${c.monotonic} | Samples: ${c.samples.size}")
                sb.appendLine()
            }
        }

        sb.appendLine("ALL TRACKED PROCESSES (by growth):")
        sb.appendLine("%-20s %6s %10s %10s %10s %8s %8s %8s".format(
            "Process", "PID", "Start", "Current", "Growth", "%", "Rate/m", "Conf"
        ))
        candidates.filter { it.growthKb > 0 }.forEach { c ->
            sb.appendLine("%-20s %6d %8dKB %8dKB %+8dKB %+6.1f%% %6.0fKB %8s".format(
                c.name.take(20), c.pid, c.startRssKb, c.currentRssKb,
                c.growthKb, c.growthPercent, c.growthRateKbPerMin, c.confidence
            ))
        }

        return sb.toString()
    }
}
