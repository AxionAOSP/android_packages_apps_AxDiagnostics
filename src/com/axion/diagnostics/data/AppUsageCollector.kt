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

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

enum class ProcessType { JAVA, NATIVE, SYSTEM, UNKNOWN }

data class AppProcess(
    val pid: Int,
    val name: String,
    val cpuPercent: Float,
    val rssKb: Long,
    val threads: Int,
    val state: String,
    val oomAdj: Int,
    val type: ProcessType
)

data class AppUsage(
    val uid: Int,
    val packageName: String,
    val label: String,
    val cpuPercent: Float,
    val cpuTimeMs: Long,
    val rssKb: Long,
    val pssKb: Long,
    val processCount: Int,
    val threadCount: Int,
    val processes: List<AppProcess>,
    val cpuTrend: Float,
    val rssTrend: Float,
    val primaryType: ProcessType
)

data class UidCpuTicks(
    val uid: Int,
    val userTimeJiffies: Long,
    val systemTimeJiffies: Long
) {
    val totalJiffies get() = userTimeJiffies + systemTimeJiffies
}

object AppUsageCollector {

    private val lock = Any()
    private var previousUidTicks = mutableMapOf<Int, Long>()
    @Volatile private var previousSystemTotal = 0L
    private val uidCpuHistory = mutableMapOf<Int, MutableList<Float>>()
    private val uidRssHistory = mutableMapOf<Int, MutableList<Long>>()
    private val packageCache = ConcurrentHashMap<Int, Pair<String, String>>()
    private const val HISTORY_SIZE = 30

    fun collect(context: Context): List<AppUsage> = synchronized(lock) {
        val systemTotal = readSystemTotalTicks()
        val systemDelta = systemTotal - previousSystemTotal

        val uidTicks = readUidCpuTimes()
        val processes = ProcessCollector.collect(200)
        val uidGroups = processes.groupBy { it.uid }

        val apps = mutableListOf<AppUsage>()

        for ((uid, procs) in uidGroups) {
            if (uid < 1000 && uid != 0) continue

            val totalRss = procs.sumOf { it.rssKb }
            val totalPss = procs.sumOf { readPss(it.pid) }
            val totalThreads = procs.sumOf { it.threads }

            val uidTick = uidTicks[uid]
            val currentTotalTicks = uidTick?.totalJiffies
                ?: procs.sumOf { readProcTotalTicks(it.pid) }

            val prevTicks = previousUidTicks[uid] ?: 0L
            val cpuPercent = if (systemDelta > 0 && prevTicks > 0) {
                val tickDelta = currentTotalTicks - prevTicks
                (tickDelta.toFloat() / systemDelta * 100f).coerceIn(0f, 800f)
            } else {
                0f
            }

            previousUidTicks[uid] = currentTotalTicks

            val cpuHist = uidCpuHistory.getOrPut(uid) { mutableListOf() }
            cpuHist.add(cpuPercent)
            if (cpuHist.size > HISTORY_SIZE) cpuHist.removeAt(0)

            val rssHist = uidRssHistory.getOrPut(uid) { mutableListOf() }
            rssHist.add(totalRss)
            if (rssHist.size > HISTORY_SIZE) rssHist.removeAt(0)

            val cpuTrend = computeTrend(cpuHist.map { it.toDouble() })
            val rssTrend = computeTrend(rssHist.map { it.toDouble() })

            val (pkgName, label) = resolveUid(context, uid)

            val appProcesses = procs.map { proc ->
                val procType = detectProcessType(proc.pid, proc.name)
                AppProcess(
                    pid = proc.pid,
                    name = proc.name,
                    cpuPercent = proc.cpuPercent,
                    rssKb = proc.rssKb,
                    threads = proc.threads,
                    state = proc.state,
                    oomAdj = proc.oomAdj,
                    type = procType
                )
            }.sortedByDescending { it.cpuPercent }

            val primaryType = when {
                appProcesses.any { it.type == ProcessType.JAVA } -> ProcessType.JAVA
                appProcesses.any { it.type == ProcessType.SYSTEM } -> ProcessType.SYSTEM
                appProcesses.any { it.type == ProcessType.NATIVE } -> ProcessType.NATIVE
                else -> ProcessType.UNKNOWN
            }

            apps.add(
                AppUsage(
                    uid = uid,
                    packageName = pkgName,
                    label = label,
                    cpuPercent = cpuPercent,
                    cpuTimeMs = currentTotalTicks * 10,
                    rssKb = totalRss,
                    pssKb = totalPss,
                    processCount = procs.size,
                    threadCount = totalThreads,
                    processes = appProcesses,
                    cpuTrend = cpuTrend,
                    rssTrend = rssTrend,
                    primaryType = primaryType
                )
            )
        }

        previousSystemTotal = systemTotal

        val activeUids = uidGroups.keys
        uidCpuHistory.keys.retainAll(activeUids)
        uidRssHistory.keys.retainAll(activeUids)
        previousUidTicks.keys.retainAll(activeUids)

        apps.sortedByDescending { it.cpuPercent }
    }

    private fun detectProcessType(pid: Int, name: String): ProcessType {
        val exeLink = runCatching {
            Files.readSymbolicLink(Paths.get("/proc/$pid/exe")).toString()
        }.getOrNull()
        if (exeLink != null && exeLink.contains("app_process")) return ProcessType.JAVA

        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .firstOrNull()
                ?.trim() ?: ""
        }.getOrDefault("")

        if (cmdline.contains("app_process") || cmdline.startsWith("com.") ||
            cmdline.startsWith("org.") || cmdline == "system_server"
        ) {
            return ProcessType.JAVA
        }

        val maps = runCatching {
            File("/proc/$pid/maps").readLines().take(50)
        }.getOrDefault(emptyList())
        if (maps.any { it.contains("libart.so") || it.contains("libandroid_runtime.so") }) {
            return ProcessType.JAVA
        }

        val systemNativeProcs = setOf(
            "surfaceflinger", "audioserver", "cameraserver", "mediaserver",
            "installd", "vold", "netd", "lmkd", "logd", "servicemanager",
            "hwservicemanager", "init", "zygote", "zygote64",
            "gpuservice", "storaged", "tombstoned", "traced",
            "adbd", "debuggerd", "healthd", "ueventd"
        )
        if (name in systemNativeProcs) return ProcessType.SYSTEM

        if (cmdline.startsWith("/") || cmdline.startsWith("[")) return ProcessType.NATIVE

        return ProcessType.UNKNOWN
    }

    private fun readUidCpuTimes(): Map<Int, UidCpuTicks> {
        val result = mutableMapOf<Int, UidCpuTicks>()
        val file = File("/proc/uid_cputime/show_uid_stat")
        if (!file.exists()) return result
        runCatching {
            file.forEachLine { line ->
                val parts = line.trim().split(":")
                if (parts.size == 2) {
                    val uid = parts[0].trim().toIntOrNull() ?: return@forEachLine
                    val times = parts[1].trim().split(" ")
                    if (times.size >= 2) {
                        result[uid] = UidCpuTicks(
                            uid = uid,
                            userTimeJiffies = (times[0].toLongOrNull() ?: 0L) / 10_000_000,
                            systemTimeJiffies = (times[1].toLongOrNull() ?: 0L) / 10_000_000
                        )
                    }
                }
            }
        }
        return result
    }

    private fun readPss(pid: Int): Long {
        val file = File("/proc/$pid/smaps_rollup")
        if (!file.exists()) return 0L
        val lines = runCatching { file.readLines() }.getOrNull() ?: return 0L
        val pssLine = lines.firstOrNull { it.startsWith("Pss:") } ?: return 0L
        return pssLine.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun readProcTotalTicks(pid: Int): Long {
        val file = File("/proc/$pid/stat")
        val line = runCatching { file.readText() }.getOrNull() ?: return 0L
        val commEnd = line.lastIndexOf(')')
        if (commEnd < 0) return 0L
        val rest = line.substring(commEnd + 2).split(" ")
        if (rest.size < 15) return 0L
        val utime = rest[11].toLongOrNull() ?: 0L
        val stime = rest[12].toLongOrNull() ?: 0L
        return utime + stime
    }

    private fun readSystemTotalTicks(): Long {
        val line = runCatching {
            File("/proc/stat").readLines().first { it.startsWith("cpu ") }
        }.getOrNull() ?: return 0L
        return line.trim().split("\\s+".toRegex()).drop(1).sumOf { it.toLongOrNull() ?: 0L }
    }

    fun resolveUid(context: Context, uid: Int): Pair<String, String> {
        packageCache[uid]?.let { return it }

        val result = when {
            uid == 0 -> Pair("root", "System (root)")
            uid == 1000 -> Pair("android.system", "Android System")
            uid == 1001 -> Pair("android.phone", "Phone/Radio")
            uid == 1002 -> Pair("android.bluetooth", "Bluetooth")
            uid == 1010 -> Pair("android.wifi", "Wi-Fi")
            uid == 1013 -> Pair("android.media", "Media")
            uid == 1017 -> Pair("android.nfc", "NFC")
            uid == 1021 -> Pair("android.gps", "GPS")
            uid == 1036 -> Pair("android.logd", "Log Daemon")
            uid == 1041 -> Pair("android.audioserver", "Audio Server")
            uid == 1046 -> Pair("android.cameraserver", "Camera Server")
            uid == 1047 -> Pair("android.incidentd", "Incident Daemon")
            uid == 2000 -> Pair("adb.shell", "ADB Shell")
            uid in 10000..19999 -> {
                val pm = context.packageManager
                val pkgs = pm.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    val pkg = pkgs[0]
                    val appLabel = runCatching {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    }.getOrDefault(pkg)
                    Pair(pkg, appLabel)
                } else {
                    Pair("uid:$uid", "UID $uid")
                }
            }
            else -> Pair("uid:$uid", "UID $uid")
        }

        packageCache[uid] = result
        return result
    }

    private fun computeTrend(values: List<Double>): Float {
        if (values.size < 5) return 0f
        val half = values.size / 2
        val first = values.take(half).average()
        val second = values.takeLast(half).average()
        if (first < 0.01) return 0f
        return ((second - first) / first * 100).toFloat()
    }
}
