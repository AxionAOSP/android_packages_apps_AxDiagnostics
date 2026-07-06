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

        return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
    }

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
