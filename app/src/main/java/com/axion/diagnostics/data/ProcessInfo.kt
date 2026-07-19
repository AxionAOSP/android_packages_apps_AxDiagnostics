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

data class ProcessSnapshot(
    val pid: Int,
    val name: String,
    val state: String,
    val cpuPercent: Float,
    val rssKb: Long,
    val threads: Int,
    val uid: Int,
    val oomAdj: Int
)

object ProcessCollector {

    private val clkTck = 100L
    private val lock = Any()
    private var previousTicks = mutableMapOf<Int, Pair<Long, Long>>()
    @Volatile
    private var previousSystemTotal: Long = 0L

    fun collect(topN: Int = 30): List<ProcessSnapshot> {
        val systemTotal = readSystemTotalTicks()

        synchronized(lock) {
            val systemDelta = systemTotal - previousSystemTotal

            val procDir = File("/proc")
            val processes = mutableListOf<ProcessSnapshot>()

            procDir.listFiles()?.filter { it.name.all { c -> c.isDigit() } }?.forEach { pidDir ->
                val pid = pidDir.name.toIntOrNull() ?: return@forEach
                val stat = readProcStat(pidDir) ?: return@forEach
                val status = readProcStatus(pidDir)

                val totalTicks = stat.utime + stat.stime
                val prev = previousTicks[pid]
                val cpuPercent = if (prev != null && systemDelta > 0) {
                    val tickDelta = totalTicks - prev.first
                    (tickDelta.toFloat() / systemDelta * 100f).coerceIn(0f, 800f)
                } else {
                    0f
                }
                previousTicks[pid] = Pair(totalTicks, systemTotal)

                val oomAdj = runCatching {
                    File(pidDir, "oom_score_adj").readText().trim().toInt()
                }.getOrDefault(0)

                processes.add(
                    ProcessSnapshot(
                        pid = pid,
                        name = stat.comm,
                        state = stat.state,
                        cpuPercent = cpuPercent,
                        rssKb = status.rssKb,
                        threads = stat.numThreads,
                        uid = status.uid,
                        oomAdj = oomAdj
                    )
                )
            }

            previousSystemTotal = systemTotal
            previousTicks.keys.retainAll(processes.map { it.pid }.toSet())

            return processes.sortedByDescending { it.cpuPercent }.take(topN)
        }
    }

    private data class ProcStat(
        val comm: String,
        val state: String,
        val utime: Long,
        val stime: Long,
        val numThreads: Int
    )

    private data class ProcStatus(
        val uid: Int,
        val rssKb: Long
    )

    private fun readProcStat(pidDir: File): ProcStat? {
        val line = runCatching { File(pidDir, "stat").readText() }.getOrNull() ?: return null
        val commStart = line.indexOf('(')
        val commEnd = line.lastIndexOf(')')
        if (commStart < 0 || commEnd < 0) return null

        val comm = line.substring(commStart + 1, commEnd)
        val rest = line.substring(commEnd + 2).split(" ")
        if (rest.size < 18) return null

        return ProcStat(
            comm = comm,
            state = rest[0],
            utime = rest[11].toLongOrNull() ?: 0L,
            stime = rest[12].toLongOrNull() ?: 0L,
            numThreads = rest[17].toIntOrNull() ?: 1
        )
    }

    private fun readProcStatus(pidDir: File): ProcStatus {
        var uid = 0
        var rssKb = 0L
        runCatching {
            File(pidDir, "status").forEachLine { line ->
                when {
                    line.startsWith("Uid:") -> {
                        uid = line.split("\\s+".toRegex()).getOrNull(1)?.toIntOrNull() ?: 0
                    }
                    line.startsWith("VmRSS:") -> {
                        rssKb = line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }
            }
        }
        return ProcStatus(uid, rssKb)
    }

    private fun readSystemTotalTicks(): Long {
        val line = runCatching { File("/proc/stat").readLines().first { it.startsWith("cpu ") } }.getOrNull()
            ?: return 0L
        return line.trim().split("\\s+".toRegex()).drop(1).sumOf { it.toLongOrNull() ?: 0L }
    }
}
