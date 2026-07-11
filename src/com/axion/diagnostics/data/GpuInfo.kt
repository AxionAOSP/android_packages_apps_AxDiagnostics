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

data class GpuSnapshot(
    val frequencyMhz: Int,
    val maxFrequencyMhz: Int,
    val minFrequencyMhz: Int,
    val busyPercent: Int,
    val governor: String,
    val availableFrequencies: List<Int>,
    val available: Boolean
)

object GpuCollector {

    private val gpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/",
        "/sys/devices/platform/mali/",
        "/sys/devices/platform/1c500000.mali/",
        "/sys/kernel/gpu/",
        "/sys/devices/platform/17000000.mali/devfreq/17000000.mali/"
    )

    private val devfreqPaths by lazy {
        File("/sys/class/devfreq/").listFiles()?.filter {
            it.name.contains("mali") || it.name.contains("gpu") || it.name.contains("kgsl")
        }?.map { it.absolutePath + "/" } ?: emptyList()
    }

    fun collect(): GpuSnapshot {
        val allPaths = gpuPaths + devfreqPaths

        for (base in allPaths) {
            val dir = File(base)
            if (!dir.exists()) continue

            val curFreq = readFreq(dir, "cur_freq", "gpu_clock", "gpuclk")
            if (curFreq <= 0) continue

            val maxFreq = readFreq(dir, "max_freq", "max_gpuclk", "gpu_max_clock")
            val minFreq = readFreq(dir, "min_freq", "min_gpuclk", "gpu_min_clock")
            val busy = readBusy(dir)
            val governor = readText(dir, "governor", "gpu_governor")

            val availFreqs = readText(dir, "available_frequencies", "gpu_available_frequencies")
                .split("\\s+".toRegex())
                .mapNotNull { it.toLongOrNull()?.let { v -> (v / 1_000_000).toInt().takeIf { it2 -> it2 > 0 } ?: (v / 1000).toInt() } }
                .filter { it > 0 }
                .distinct()
                .sorted()

            return GpuSnapshot(
                frequencyMhz = curFreq,
                maxFrequencyMhz = maxFreq,
                minFrequencyMhz = minFreq,
                busyPercent = busy,
                governor = governor.ifEmpty { "unknown" },
                availableFrequencies = availFreqs,
                available = true
            )
        }

        val mtkGedDir = listOf(
            File("/sys/kernel/ged/hal/"),
            File("/sys/kernel/debug/ged/hal/")
        ).firstOrNull { it.exists() }

        if (mtkGedDir != null) {
            val curFreqRaw = runCatching {
                File(mtkGedDir, "current_freqency").readText().trim().split("\\s+".toRegex()).last().toLong()
            }.getOrDefault(0L)
            val curFreq = if (curFreqRaw > 1000) (curFreqRaw / 1000).toInt() else curFreqRaw.toInt()
            
            val mtkLoadingFile = File("/sys/module/ged/parameters/gpu_loading")
            val busy = if (mtkLoadingFile.exists()) {
                runCatching { mtkLoadingFile.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 100)
            } else {
                val busyRaw = runCatching {
                    File(mtkGedDir, "gpu_utilization").readText().trim().split("\\s+".toRegex()).first().toLong()
                }.getOrDefault(0L)
                busyRaw.toInt().coerceIn(0, 100)
            }

            val mtkOppFile = File("/proc/gpufreq/gpufreq_opp_dump")
            val mtkFreqs = if (mtkOppFile.exists()) {
                runCatching {
                    val content = mtkOppFile.readText()
                    Regex("freq\\s*=\\s*(\\d+)").findAll(content)
                        .mapNotNull { it.groupValues[1].toLongOrNull()?.let { v -> (v / 1000).toInt() } }
                        .filter { it > 0 }
                        .distinct()
                        .sorted()
                        .toList()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val maxFreq = mtkFreqs.maxOrNull() ?: 0
            val minFreq = mtkFreqs.minOrNull() ?: 0

            if (curFreq > 0) {
                return GpuSnapshot(
                    frequencyMhz = curFreq,
                    maxFrequencyMhz = maxFreq,
                    minFrequencyMhz = minFreq,
                    busyPercent = busy,
                    governor = "ged",
                    availableFrequencies = mtkFreqs,
                    available = true
                )
            }
        }

        val mtkGpuFreqDir = File("/sys/class/devfreq/gpufreq/")
        if (mtkGpuFreqDir.exists()) {
            val curFreqRaw = readLongFile(File(mtkGpuFreqDir, "cur_freq"))
            val curFreq = if (curFreqRaw > 1000) (curFreqRaw / 1000).toInt() else curFreqRaw.toInt()

            val busyFile = File(mtkGpuFreqDir, "mali_ondemand/utilisation")
            val busy = if (busyFile.exists()) {
                readLongFile(busyFile).toInt().coerceIn(0, 100)
            } else {
                val mtkMaliUtilFile = File("/proc/mtk_mali/gpu_utilization")
                if (mtkMaliUtilFile.exists()) {
                    runCatching { mtkMaliUtilFile.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 100)
                } else {
                    0
                }
            }

            if (curFreq > 0) {
                return GpuSnapshot(
                    frequencyMhz = curFreq,
                    maxFrequencyMhz = 0,
                    minFrequencyMhz = 0,
                    busyPercent = busy,
                    governor = runCatching { File(mtkGpuFreqDir, "governor").readText().trim() }.getOrDefault("unknown"),
                    availableFrequencies = emptyList(),
                    available = true
                )
            }
        }

        val mtkMaliUtil = File("/proc/mtk_mali/gpu_utilization")
        if (mtkMaliUtil.exists()) {
            val busy = runCatching { mtkMaliUtil.readText().trim().toInt() }.getOrDefault(0).coerceIn(0, 100)
            return GpuSnapshot(
                frequencyMhz = 0,
                maxFrequencyMhz = 0,
                minFrequencyMhz = 0,
                busyPercent = busy,
                governor = "mtk_mali",
                availableFrequencies = emptyList(),
                available = true
            )
        }

        return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
    }

    private fun readLongFile(file: File): Long =
        runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    private fun readFreq(dir: File, vararg names: String): Int {
        for (name in names) {
            val file = File(dir, name)
            if (!file.exists()) continue
            val raw = runCatching { file.readText().trim().toLong() }.getOrNull() ?: continue
            return when {
                raw > 1_000_000 -> (raw / 1_000_000).toInt()
                raw > 1_000 -> (raw / 1_000).toInt()
                else -> raw.toInt()
            }
        }
        return 0
    }

    private fun readBusy(dir: File): Int {
        val busyFile = File(dir, "gpu_busy_percentage")
        if (busyFile.exists()) {
            return runCatching { busyFile.readText().trim().replace("%", "").toInt() }.getOrDefault(0)
        }
        val loadFile = File(dir, "load")
        if (loadFile.exists()) {
            return runCatching { loadFile.readText().trim().toInt() }.getOrDefault(0)
        }
        return 0
    }

    private fun readText(dir: File, vararg names: String): String {
        for (name in names) {
            val file = File(dir, name)
            if (file.exists()) {
                return runCatching { file.readText().trim() }.getOrDefault("")
            }
        }
        return ""
    }
}
