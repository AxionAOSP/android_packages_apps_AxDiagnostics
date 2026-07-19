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

data class MemSnapshot(
    val totalKb: Long,
    val freeKb: Long,
    val availableKb: Long,
    val buffersKb: Long,
    val cachedKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val swapCachedKb: Long,
    val activeKb: Long,
    val inactiveKb: Long,
    val dirtyKb: Long,
    val writebackKb: Long,
    val anonPagesKb: Long,
    val mappedKb: Long,
    val shmemKb: Long,
    val slabKb: Long,
    val kernelStackKb: Long,
    val pageTables: Long,
    val vmallocUsedKb: Long,
    val zramTotalKb: Long,
    val zramUsedKb: Long,
    val zramOrigKb: Long,
) {
    val usedKb get() = totalKb - availableKb
    val usedPercent get() = if (totalKb > 0) usedKb.toFloat() / totalKb * 100f else 0f
    val swapUsedKb get() = swapTotalKb - swapFreeKb
    val swapUsedPercent get() = if (swapTotalKb > 0) swapUsedKb.toFloat() / swapTotalKb * 100f else 0f
    val zramCompressionRatio get() = if (zramUsedKb > 0) zramOrigKb.toFloat() / zramUsedKb else 0f
}

object MemCollector {

    fun collect(): MemSnapshot {
        val memMap = mutableMapOf<String, Long>()
        File("/proc/meminfo").forEachLine { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().split("\\s+".toRegex())[0].toLongOrNull() ?: 0L
                memMap[key] = value
            }
        }

        val zramStats = readZramStats()

        return MemSnapshot(
            totalKb = memMap["MemTotal"] ?: 0,
            freeKb = memMap["MemFree"] ?: 0,
            availableKb = memMap["MemAvailable"] ?: 0,
            buffersKb = memMap["Buffers"] ?: 0,
            cachedKb = memMap["Cached"] ?: 0,
            swapTotalKb = memMap["SwapTotal"] ?: 0,
            swapFreeKb = memMap["SwapFree"] ?: 0,
            swapCachedKb = memMap["SwapCached"] ?: 0,
            activeKb = memMap["Active"] ?: 0,
            inactiveKb = memMap["Inactive"] ?: 0,
            dirtyKb = memMap["Dirty"] ?: 0,
            writebackKb = memMap["Writeback"] ?: 0,
            anonPagesKb = memMap["AnonPages"] ?: 0,
            mappedKb = memMap["Mapped"] ?: 0,
            shmemKb = memMap["Shmem"] ?: 0,
            slabKb = memMap["Slab"] ?: 0,
            kernelStackKb = memMap["KernelStack"] ?: 0,
            pageTables = memMap["PageTables"] ?: 0,
            vmallocUsedKb = memMap["VmallocUsed"] ?: 0,
            zramTotalKb = zramStats.first,
            zramUsedKb = zramStats.second,
            zramOrigKb = zramStats.third,
        )
    }

    private fun readZramStats(): Triple<Long, Long, Long> {
        val zramDir = File("/sys/block/zram0")
        if (!zramDir.exists()) return Triple(0L, 0L, 0L)
        val disksize = readLongFile(File(zramDir, "disksize")) / 1024
        val mmStat = runCatching { File(zramDir, "mm_stat").readText().trim().split("\\s+".toRegex()) }.getOrNull()
        if (mmStat != null && mmStat.size >= 2) {
            val origDataSize = (mmStat[0].toLongOrNull() ?: 0L) / 1024
            val comprDataSize = (mmStat[1].toLongOrNull() ?: 0L) / 1024
            return Triple(disksize, comprDataSize, origDataSize)
        }
        return Triple(disksize, 0L, 0L)
    }

    private fun readLongFile(file: File): Long = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    fun optimizeMemory(): Boolean {
        var success = false
        runCatching {
            val file = File("/proc/sys/vm/drop_caches")
            if (file.exists()) {
                file.writeText("3")
                success = true
            }
        }
        runCatching {
            val file = File("/proc/sys/vm/compact_memory")
            if (file.exists()) {
                file.writeText("1")
                success = true
            }
        }
        runCatching {
            System.gc()
            Runtime.getRuntime().gc()
        }
        return success
    }
}
