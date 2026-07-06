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

data class WakelockEntry(
    val name: String,
    val activeCount: Long,
    val eventCount: Long,
    val wakeupCount: Long,
    val expireCount: Long,
    val activeSinceMs: Long,
    val totalTimeMs: Long,
    val maxTimeMs: Long,
    val lastChangeMs: Long,
    val preventSuspendTimeMs: Long
)

data class WakelockSnapshot(
    val kernelWakelocks: List<WakelockEntry>,
    val totalActiveCount: Long,
    val totalPreventSuspendMs: Long
)

object WakelockCollector {

    fun collect(): WakelockSnapshot {
        val wakelocks = mutableListOf<WakelockEntry>()

        val sources = listOf(
            "/sys/kernel/debug/wakeup_sources",
            "/d/wakeup_sources",
            "/sys/power/wakeup_sources"
        )

        val sourceFile = sources.map { File(it) }.firstOrNull { it.exists() && it.canRead() }

        if (sourceFile != null) {
            val lines = runCatching { sourceFile.readLines() }.getOrDefault(emptyList())
            for (line in lines.drop(1)) {
                val entry = parseWakeupSource(line) ?: continue
                wakelocks.add(entry)
            }
        } else {
            val procWakelocks = File("/proc/wakelocks")
            if (procWakelocks.exists()) {
                val lines = runCatching { procWakelocks.readLines() }.getOrDefault(emptyList())
                for (line in lines.drop(1)) {
                    val entry = parseProcWakelock(line) ?: continue
                    wakelocks.add(entry)
                }
            }
        }

        val sorted = wakelocks.sortedByDescending { it.totalTimeMs }
        return WakelockSnapshot(
            kernelWakelocks = sorted,
            totalActiveCount = sorted.sumOf { it.activeCount },
            totalPreventSuspendMs = sorted.sumOf { it.preventSuspendTimeMs }
        )
    }

    private fun parseWakeupSource(line: String): WakelockEntry? {
        val parts = line.trim().split("\\s+".toRegex())
        val valueCount = 9
        if (parts.size < valueCount + 1) return null
        val nameParts = parts.dropLast(valueCount)
        if (nameParts.isEmpty()) return null
        val name = nameParts.joinToString(" ")
        val nums = parts.takeLast(valueCount)
        return runCatching {
            WakelockEntry(
                name = name,
                activeCount = nums[0].toLong(),
                eventCount = nums[1].toLong(),
                wakeupCount = nums[2].toLong(),
                expireCount = nums[3].toLong(),
                activeSinceMs = nums[4].toLong(),
                totalTimeMs = nums[5].toLong(),
                maxTimeMs = nums[6].toLong(),
                lastChangeMs = nums[7].toLong(),
                preventSuspendTimeMs = nums[8].toLong()
            )
        }.getOrNull()
    }

    private fun parseProcWakelock(line: String): WakelockEntry? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null
        val name = parts[0].removeSurrounding("\"")
        return runCatching {
            WakelockEntry(
                name = name,
                activeCount = parts[1].toLong(),
                eventCount = 0,
                wakeupCount = 0,
                expireCount = 0,
                activeSinceMs = 0,
                totalTimeMs = (parts[4].toLong()) / 1_000_000,
                maxTimeMs = (parts[3].toLong()) / 1_000_000,
                lastChangeMs = 0,
                preventSuspendTimeMs = if (parts.size > 6) parts[6].toLong() / 1_000_000 else 0
            )
        }.getOrNull()
    }
}
