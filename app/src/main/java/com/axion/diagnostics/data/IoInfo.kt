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

data class DiskStats(
    val device: String,
    val readsCompleted: Long,
    val readsMerged: Long,
    val sectorsRead: Long,
    val readTimeMs: Long,
    val writesCompleted: Long,
    val writesMerged: Long,
    val sectorsWritten: Long,
    val writeTimeMs: Long,
    val ioInProgress: Long,
    val ioTimeMs: Long,
    val weightedIoTimeMs: Long,
)

data class IoSnapshot(val disks: List<DiskStats>, val readRateKbps: Float, val writeRateKbps: Float, val ioPressure: IoPressure)

data class IoPressure(
    val someAvg10: Float,
    val someAvg60: Float,
    val someAvg300: Float,
    val fullAvg10: Float,
    val fullAvg60: Float,
    val fullAvg300: Float,
)

object IoCollector {

    private val lock = Any()
    private val wholeBlockDevicePatterns = listOf(
        Regex("sd[a-z]+"),
        Regex("mmcblk\\d+"),
        Regex("nvme\\d+n\\d+"),
        Regex("vd[a-z]+"),
    )
    private var previousDisks = mutableMapOf<String, DiskStats>()
    @Volatile private var previousTimestamp = 0L

    fun collect(): IoSnapshot = synchronized(lock) {
        val disks = mutableListOf<DiskStats>()

        File("/proc/diskstats").forEachLine { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 14) {
                val name = parts[2]
                if (isWholeBlockDevice(name)) {
                    disks.add(
                        DiskStats(
                            device = name,
                            readsCompleted = parts[3].toLongOrNull() ?: 0,
                            readsMerged = parts[4].toLongOrNull() ?: 0,
                            sectorsRead = parts[5].toLongOrNull() ?: 0,
                            readTimeMs = parts[6].toLongOrNull() ?: 0,
                            writesCompleted = parts[7].toLongOrNull() ?: 0,
                            writesMerged = parts[8].toLongOrNull() ?: 0,
                            sectorsWritten = parts[9].toLongOrNull() ?: 0,
                            writeTimeMs = parts[10].toLongOrNull() ?: 0,
                            ioInProgress = parts[11].toLongOrNull() ?: 0,
                            ioTimeMs = parts[12].toLongOrNull() ?: 0,
                            weightedIoTimeMs = parts[13].toLongOrNull() ?: 0,
                        ),
                    )
                }
            }
        }

        val now = System.currentTimeMillis()
        val elapsed = (now - previousTimestamp) / 1000f
        var readRate = 0f
        var writeRate = 0f

        if (previousTimestamp > 0 && elapsed > 0) {
            for (disk in disks) {
                val prev = previousDisks[disk.device] ?: continue
                val sectorsReadDelta = disk.sectorsRead - prev.sectorsRead
                val sectorsWrittenDelta = disk.sectorsWritten - prev.sectorsWritten
                readRate += (sectorsReadDelta * 512f) / (elapsed * 1024f)
                writeRate += (sectorsWrittenDelta * 512f) / (elapsed * 1024f)
            }
        }

        previousTimestamp = now
        previousDisks = disks.associateBy { it.device }.toMutableMap()

        val pressure = readIoPressure()

        IoSnapshot(
            disks = disks,
            readRateKbps = readRate,
            writeRateKbps = writeRate,
            ioPressure = pressure,
        )
    }

    private fun readIoPressure(): IoPressure {
        val file = File("/proc/pressure/io")
        if (!file.exists()) return IoPressure(0f, 0f, 0f, 0f, 0f, 0f)
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        var someAvg10 = 0f
        var someAvg60 = 0f
        var someAvg300 = 0f
        var fullAvg10 = 0f
        var fullAvg60 = 0f
        var fullAvg300 = 0f
        for (line in lines) {
            val avgs = Regex("avg10=(\\S+) avg60=(\\S+) avg300=(\\S+)").find(line)
            if (avgs != null) {
                val a10 = avgs.groupValues[1].toFloatOrNull() ?: 0f
                val a60 = avgs.groupValues[2].toFloatOrNull() ?: 0f
                val a300 = avgs.groupValues[3].toFloatOrNull() ?: 0f
                if (line.startsWith("some")) {
                    someAvg10 = a10
                    someAvg60 = a60
                    someAvg300 = a300
                }
                if (line.startsWith("full")) {
                    fullAvg10 = a10
                    fullAvg60 = a60
                    fullAvg300 = a300
                }
            }
        }
        return IoPressure(someAvg10, someAvg60, someAvg300, fullAvg10, fullAvg60, fullAvg300)
    }

    private fun isWholeBlockDevice(name: String): Boolean = wholeBlockDevicePatterns.any { it.matches(name) }
}
