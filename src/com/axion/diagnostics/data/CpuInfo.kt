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

data class CpuCoreInfo(
    val index: Int,
    val usagePercent: Float,
    val frequencyMhz: Int,
    val maxFrequencyMhz: Int,
    val minFrequencyMhz: Int,
    val governor: String,
    val online: Boolean
)

data class CpuSnapshot(
    val totalUsage: Float,
    val userPercent: Float,
    val systemPercent: Float,
    val iowaitPercent: Float,
    val irqPercent: Float,
    val cores: List<CpuCoreInfo>,
    val loadAvg1: Float,
    val loadAvg5: Float,
    val loadAvg15: Float,
    val contextSwitches: Long,
    val processes: Long,
    val procsRunning: Long,
    val procsBlocked: Long
)

data class CpuRawTicks(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long
) {
    val total get() = user + nice + system + idle + iowait + irq + softirq + steal
    val active get() = total - idle - iowait
}

object CpuCollector {

    private val lock = Any()
    @Volatile private var previousTotalTicks: CpuRawTicks? = null
    private var previousCoreTicks = mutableMapOf<Int, CpuRawTicks>()

    fun collect(): CpuSnapshot = synchronized(lock) {
        val statLines = File("/proc/stat").readLines()
        val loadAvgParts = File("/proc/loadavg").readText().trim().split("\\s+".toRegex())

        val totalLine = statLines.first { it.startsWith("cpu ") }
        val currentTotal = parseCpuLine(totalLine)
        val totalUsage = calculateUsage(previousTotalTicks, currentTotal)
        val userPct = calculateComponent(previousTotalTicks, currentTotal) { it.user + it.nice }
        val sysPct = calculateComponent(previousTotalTicks, currentTotal) { it.system }
        val ioPct = calculateComponent(previousTotalTicks, currentTotal) { it.iowait }
        val irqPct = calculateComponent(previousTotalTicks, currentTotal) { it.irq + it.softirq }
        previousTotalTicks = currentTotal

        val coreLines = statLines.filter { it.matches(Regex("cpu\\d+ .*")) }
        val cores = coreLines.mapIndexed { idx, line ->
            val coreIdx = Regex("cpu(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: idx
            val current = parseCpuLine(line)
            val usage = calculateUsage(previousCoreTicks[coreIdx], current)
            previousCoreTicks[coreIdx] = current

            val cpuDir = File("/sys/devices/system/cpu/cpu$coreIdx")
            val online = if (coreIdx == 0) true else {
                runCatching { File(cpuDir, "online").readText().trim() == "1" }.getOrDefault(true)
            }
            val freqDir = File(cpuDir, "cpufreq")
            val curFreq = readIntFile(File(freqDir, "scaling_cur_freq")) / 1000
            val maxFreq = readIntFile(File(freqDir, "scaling_max_freq")) / 1000
            val minFreq = readIntFile(File(freqDir, "scaling_min_freq")) / 1000
            val governor = runCatching { File(freqDir, "scaling_governor").readText().trim() }.getOrDefault("unknown")

            CpuCoreInfo(coreIdx, usage, curFreq, maxFreq, minFreq, governor, online)
        }

        var ctxSwitches = 0L
        var processes = 0L
        var procsRunning = 0L
        var procsBlocked = 0L
        for (line in statLines) {
            when {
                line.startsWith("ctxt ") -> ctxSwitches = line.split(" ")[1].toLongOrNull() ?: 0
                line.startsWith("processes ") -> processes = line.split(" ")[1].toLongOrNull() ?: 0
                line.startsWith("procs_running ") -> procsRunning = line.split(" ")[1].toLongOrNull() ?: 0
                line.startsWith("procs_blocked ") -> procsBlocked = line.split(" ")[1].toLongOrNull() ?: 0
            }
        }

        CpuSnapshot(
            totalUsage = totalUsage,
            userPercent = userPct,
            systemPercent = sysPct,
            iowaitPercent = ioPct,
            irqPercent = irqPct,
            cores = cores,
            loadAvg1 = loadAvgParts.getOrNull(0)?.toFloatOrNull() ?: 0f,
            loadAvg5 = loadAvgParts.getOrNull(1)?.toFloatOrNull() ?: 0f,
            loadAvg15 = loadAvgParts.getOrNull(2)?.toFloatOrNull() ?: 0f,
            contextSwitches = ctxSwitches,
            processes = processes,
            procsRunning = procsRunning,
            procsBlocked = procsBlocked
        )
    }

    private fun parseCpuLine(line: String): CpuRawTicks {
        val parts = line.trim().split("\\s+".toRegex())
        return CpuRawTicks(
            user = parts.getOrNull(1)?.toLongOrNull() ?: 0,
            nice = parts.getOrNull(2)?.toLongOrNull() ?: 0,
            system = parts.getOrNull(3)?.toLongOrNull() ?: 0,
            idle = parts.getOrNull(4)?.toLongOrNull() ?: 0,
            iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0,
            irq = parts.getOrNull(6)?.toLongOrNull() ?: 0,
            softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0,
            steal = parts.getOrNull(8)?.toLongOrNull() ?: 0
        )
    }

    private fun calculateUsage(prev: CpuRawTicks?, curr: CpuRawTicks): Float {
        if (prev == null) return 0f
        val totalDelta = curr.total - prev.total
        if (totalDelta == 0L) return 0f
        val activeDelta = curr.active - prev.active
        return (activeDelta.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    private fun calculateComponent(
        prev: CpuRawTicks?,
        curr: CpuRawTicks,
        selector: (CpuRawTicks) -> Long
    ): Float {
        if (prev == null) return 0f
        val totalDelta = curr.total - prev.total
        if (totalDelta == 0L) return 0f
        val delta = selector(curr) - selector(prev)
        return (delta.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    private fun readIntFile(file: File): Int =
        runCatching { file.readText().trim().toInt() }.getOrDefault(0)
}
