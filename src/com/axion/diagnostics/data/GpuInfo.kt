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

    fun collect(): GpuSnapshot {
        val config = KernelConfig.gpuConfig

        if (config.currentNode != null) {
            val freqFile = File(config.currentNode)
            if (freqFile.exists()) {
                val rawFreq = runCatching { freqFile.readText().trim().toLong() }.getOrDefault(0L)
                val curFreq = KernelConfig.scaleFreqToMhz(rawFreq)

                val busy = if (config.usageNode != null) {
                    val busyFile = File(config.usageNode)
                    if (busyFile.exists()) {
                        runCatching {
                            busyFile.readText().trim().replace("%", "").toInt()
                        }.getOrDefault(0).coerceIn(0, 100)
                    } else 0
                } else 0

                val governor = if (config.node != null) {
                    val govFile = File(config.node, "governor")
                    if (govFile.exists()) runCatching { govFile.readText().trim() }.getOrDefault("unknown") else "unknown"
                } else "unknown"

                val maxFreq = if (config.node != null) {
                    val maxFile = File(config.node, "max_freq")
                    if (maxFile.exists()) {
                        val rawMax = runCatching { maxFile.readText().trim().toLong() }.getOrDefault(0L)
                        KernelConfig.scaleFreqToMhz(rawMax)
                    } else 0
                } else 0

                val minFreq = if (config.node != null) {
                    val minFile = File(config.node, "min_freq")
                    if (minFile.exists()) {
                        val rawMin = runCatching { minFile.readText().trim().toLong() }.getOrDefault(0L)
                        KernelConfig.scaleFreqToMhz(rawMin)
                    } else 0
                } else 0

                if (curFreq > 0) {
                    return GpuSnapshot(
                        frequencyMhz = curFreq,
                        maxFrequencyMhz = maxFreq,
                        minFrequencyMhz = minFreq,
                        busyPercent = busy,
                        governor = governor,
                        availableFrequencies = config.values,
                        available = true
                    )
                }
            }
        }

        return GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
    }
}
