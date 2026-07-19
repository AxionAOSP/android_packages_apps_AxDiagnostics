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
    val online: Boolean,
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
    val procsBlocked: Long,
    val ddrFreqMhz: Int = 0,
    val l3FreqMhz: Int = 0,
)

data class CpuRawTicks(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
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

        val totalLine = statLines.firstOrNull { it.startsWith("cpu ") } ?: return CpuSnapshot(
            totalUsage = 0f, userPercent = 0f, systemPercent = 0f, iowaitPercent = 0f, irqPercent = 0f,
            cores = emptyList(), loadAvg1 = 0f, loadAvg5 = 0f, loadAvg15 = 0f,
            contextSwitches = 0L, processes = 0L, procsRunning = 0L, procsBlocked = 0L,
        )
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

            var curFreq = 0
            var maxFreq = 0
            var minFreq = 0
            var governor = "unknown"

            val cluster = findClusterForCore(coreIdx)
            if (cluster != null) {
                governor = cluster.governorNode?.let {
                    val file = File(it)
                    if (file.exists()) runCatching { file.readText().trim() }.getOrDefault("unknown") else "unknown"
                } ?: "unknown"

                minFreq = cluster.minNode?.let {
                    val file = File(it)
                    if (file.exists()) readIntFile(file) / 1000 else 0
                } ?: 0
                maxFreq = cluster.maxNode?.let {
                    val file = File(it)
                    if (file.exists()) readIntFile(file) / 1000 else 0
                } ?: 0

                if (online) {
                    val directCurFreqFile = File(cpuDir, "cpufreq/scaling_cur_freq")
                    val policyDir = cluster.minNode?.let { File(it).parentFile }
                    val policyCurFreqFile = policyDir?.let { File(it, "scaling_cur_freq") }
                    val freqFile = when {
                        directCurFreqFile.exists() -> directCurFreqFile
                        policyCurFreqFile != null && policyCurFreqFile.exists() -> policyCurFreqFile
                        else -> null
                    }
                    curFreq = freqFile?.let { readIntFile(it) / 1000 } ?: 0
                }
            }

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

        val ddrFreq = getBusFrequencyMhz("DDR")
        val l3Freq = getBusFrequencyMhz("L3")

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
            procsBlocked = procsBlocked,
            ddrFreqMhz = ddrFreq,
            l3FreqMhz = l3Freq,
        )
    }

    private fun findClusterForCore(coreIdx: Int): CpuClusterConfig? {
        for (cluster in KernelConfig.cpuClusters) {
            val minNode = cluster.minNode ?: continue
            val policyDir = File(minNode).parentFile ?: continue
            val relatedCpusFile = File(policyDir, "related_cpus")
            val affectedCpusFile = File(policyDir, "affected_cpus")
            val cpusText = when {
                relatedCpusFile.exists() -> runCatching { relatedCpusFile.readText().trim() }.getOrNull()
                affectedCpusFile.exists() -> runCatching { affectedCpusFile.readText().trim() }.getOrNull()
                else -> null
            }
            if (cpusText != null) {
                val cpus = cpusText.split("\\s+".toRegex()).mapNotNull { it.toIntOrNull() }
                if (coreIdx in cpus) {
                    return cluster
                }
            } else {
                val policyName = policyDir.name
                val policyNum = policyName.removePrefix("policy").toIntOrNull()
                if (policyNum != null) {
                    if (policyNum == 0 && coreIdx in 0..3) return cluster
                    if (policyNum == 4 && coreIdx in 4..5) return cluster
                    if (policyNum == 6 && coreIdx in 6..7) return cluster
                }
            }
        }
        return null
    }

    private fun getBusFrequencyMhz(busType: String): Int {
        val path = "/sys/devices/system/cpu/bus_dcvs/$busType"
        val dir = File(path)
        if (dir.exists()) {
            val files = dir.listFiles() ?: emptyArray()
            for (f in files) {
                if (f.isDirectory) {
                    val curFreqFile = File(f, "cur_freq")
                    if (curFreqFile.exists()) {
                        val freqHz = runCatching { curFreqFile.readText().trim().toLong() }.getOrNull() ?: 0L
                        if (freqHz > 0) {
                            return normalizeToMhz(freqHz)
                        }
                    }
                }
            }
        }
        if (busType == "DDR") {
            val devfreqDir = File("/sys/class/devfreq")
            if (devfreqDir.exists()) {
                val devfreqs = devfreqDir.listFiles() ?: emptyArray()
                for (df in devfreqs) {
                    if (df.name.contains("ddr") || df.name.contains("bwmon") || df.name.contains("mem") || df.name.contains("bimc")) {
                        val curFreqFile = File(df, "cur_freq")
                        if (curFreqFile.exists()) {
                            val freqHz = runCatching { curFreqFile.readText().trim().toLong() }.getOrNull() ?: 0L
                            if (freqHz > 0) {
                                return normalizeToMhz(freqHz)
                            }
                        }
                    }
                }
            }
        }
        return 0
    }

    private fun normalizeToMhz(freq: Long): Int {
        return when {
            freq > 10_000_000_000L -> (freq / 1_000_000_000L).toInt()
            freq > 10_000_000L -> (freq / 1_000_000L).toInt()
            freq > 10_000L -> (freq / 1_000L).toInt()
            else -> freq.toInt()
        }
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
            steal = parts.getOrNull(8)?.toLongOrNull() ?: 0,
        )
    }

    private fun calculateUsage(prev: CpuRawTicks?, curr: CpuRawTicks): Float {
        if (prev == null) return 0f
        val totalDelta = curr.total - prev.total
        if (totalDelta == 0L) return 0f
        val activeDelta = curr.active - prev.active
        return (activeDelta.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    private fun calculateComponent(prev: CpuRawTicks?, curr: CpuRawTicks, selector: (CpuRawTicks) -> Long): Float {
        if (prev == null) return 0f
        val totalDelta = curr.total - prev.total
        if (totalDelta == 0L) return 0f
        val delta = selector(curr) - selector(prev)
        return (delta.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    private fun readIntFile(file: File): Int = runCatching { file.readText().trim().toInt() }.getOrDefault(0)
}
