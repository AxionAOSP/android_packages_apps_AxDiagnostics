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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ThreadInfo(
    val tid: Int,
    val name: String,
    val state: String,
    val cpuPercent: Float,
    val userTicks: Long,
    val systemTicks: Long,
    val service: String,
)

data class BinderStats(
    val transactionsReceived: Long,
    val transactionsSent: Long,
    val pendingTransactions: Int,
    val readyThreads: Int,
    val freeAsyncSpace: Long,
)

data class SystemServerSnapshot(
    val pid: Int,
    val totalCpuPercent: Float,
    val totalRssKb: Long,
    val totalPssKb: Long,
    val totalThreads: Int,
    val threads: List<ThreadInfo>,
    val binderStats: BinderStats?,
    val topServices: List<ServiceCpuUsage>,
    val cpuPressure: PsiStats,
    val memPressure: PsiStats,
)

data class ServiceCpuUsage(val serviceName: String, val cpuPercent: Float, val threadCount: Int, val threads: List<ThreadInfo>)

data class PsiStats(
    val someAvg10: Float,
    val someAvg60: Float,
    val someAvg300: Float,
    val fullAvg10: Float,
    val fullAvg60: Float,
    val fullAvg300: Float,
)

object SystemServerAnalyzer {

    private val lock = Any()
    private var previousThreadTicks = mutableMapOf<Int, Long>()
    @Volatile private var previousSystemTotal = 0L
    private val threadCpuHistory = mutableMapOf<Int, MutableList<Float>>()

    fun collect(): SystemServerSnapshot = synchronized(lock) {
        val ssPid = findSystemServerPid()
        if (ssPid <= 0) {
            return SystemServerSnapshot(
                0, 0f, 0, 0, 0, emptyList(), null, emptyList(),
                PsiStats(0f, 0f, 0f, 0f, 0f, 0f), PsiStats(0f, 0f, 0f, 0f, 0f, 0f),
            )
        }

        val systemTotal = readSystemTotalTicks()
        val systemDelta = systemTotal - previousSystemTotal

        val threadInfos = mutableListOf<ThreadInfo>()
        val taskDir = File("/proc/$ssPid/task")

        taskDir.listFiles()?.forEach { tidDir ->
            val tid = tidDir.name.toIntOrNull() ?: return@forEach
            val threadStat = readThreadStat(tidDir) ?: return@forEach

            val totalTicks = threadStat.utime + threadStat.stime
            val prevTicks = previousThreadTicks[tid] ?: 0L
            val cpuPercent = if (systemDelta > 0 && prevTicks > 0) {
                val delta = totalTicks - prevTicks
                (delta.toFloat() / systemDelta * 100f).coerceIn(0f, 100f)
            } else {
                0f
            }

            previousThreadTicks[tid] = totalTicks

            val cpuHist = threadCpuHistory.getOrPut(tid) { mutableListOf() }
            cpuHist.add(cpuPercent)
            if (cpuHist.size > 30) cpuHist.removeAt(0)

            val service = categorizeThread(threadStat.name)

            threadInfos.add(
                ThreadInfo(
                    tid = tid,
                    name = threadStat.name,
                    state = threadStat.state,
                    cpuPercent = cpuPercent,
                    userTicks = threadStat.utime,
                    systemTicks = threadStat.stime,
                    service = service,
                ),
            )
        }

        previousSystemTotal = systemTotal

        previousThreadTicks.keys.retainAll(threadInfos.map { it.tid }.toSet())
        threadCpuHistory.keys.retainAll(threadInfos.map { it.tid }.toSet())

        val sortedThreads = threadInfos.sortedByDescending { it.cpuPercent }

        val serviceGroups = threadInfos
            .groupBy { it.service }
            .map { (service, threads) ->
                ServiceCpuUsage(
                    serviceName = service,
                    cpuPercent = threads.sumOf { it.cpuPercent.toDouble() }.toFloat(),
                    threadCount = threads.size,
                    threads = threads.sortedByDescending { it.cpuPercent },
                )
            }
            .sortedByDescending { it.cpuPercent }

        val rssKb = readProcRss(ssPid)
        val pssKb = readProcPss(ssPid)
        val totalCpu = threadInfos.sumOf { it.cpuPercent.toDouble() }.toFloat()

        val binderStats = readBinderStats(ssPid)
        val cpuPressure = readPsi("/proc/pressure/cpu")
        val memPressure = readPsi("/proc/pressure/memory")

        SystemServerSnapshot(
            pid = ssPid,
            totalCpuPercent = totalCpu,
            totalRssKb = rssKb,
            totalPssKb = pssKb,
            totalThreads = threadInfos.size,
            threads = sortedThreads,
            binderStats = binderStats,
            topServices = serviceGroups,
            cpuPressure = cpuPressure,
            memPressure = memPressure,
        )
    }

    private fun findSystemServerPid(): Int {
        val pid = ProcCommand.readOutput("pidof", "system_server")?.trim()?.toIntOrNull()
        if (pid != null && pid > 0) return pid

        val procDir = File("/proc")
        val dirs = procDir.listFiles() ?: return 0
        for (pidDir in dirs) {
            if (!pidDir.name.all { c -> c.isDigit() }) continue
            val cmdline = runCatching {
                File(pidDir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .split('\u0000')
                    .firstOrNull()
                    ?.trim()
            }.getOrNull() ?: continue
            if (cmdline == "system_server") return pidDir.name.toIntOrNull() ?: 0
        }
        return 0
    }

    private data class ThreadStat(val name: String, val state: String, val utime: Long, val stime: Long)

    private fun readThreadStat(tidDir: File): ThreadStat? {
        val statLine = runCatching { File(tidDir, "stat").readText() }.getOrNull() ?: return null
        val commStart = statLine.indexOf('(')
        val commEnd = statLine.lastIndexOf(')')
        if (commStart < 0 || commEnd < 0) return null

        val name = statLine.substring(commStart + 1, commEnd)
        val rest = statLine.substring(commEnd + 2).split(" ")
        if (rest.size < 15) return null

        return ThreadStat(
            name = name,
            state = rest[0],
            utime = rest[11].toLongOrNull() ?: 0L,
            stime = rest[12].toLongOrNull() ?: 0L,
        )
    }

    private fun categorizeThread(name: String): String {
        return when {
            name.startsWith("Binder:") -> "Binder"
            name.startsWith("binder:") -> "Binder"
            name.contains("ActivityManager") || name.contains("android.anim") -> "ActivityManager"
            name.contains("PowerManager") -> "PowerManager"
            name.contains("WindowManager") || name.contains("android.display") -> "WindowManager"
            name.contains("PackageManager") || name.contains("PackageInsta") -> "PackageManager"
            name.contains("InputDispatch") || name.contains("InputReader") -> "InputManager"
            name.contains("SensorService") || name.contains("SensorEvent") -> "SensorService"
            name.contains("AlarmManager") || name.contains("alarm") -> "AlarmManager"
            name.contains("ConnectivityS") || name.contains("Network") -> "ConnectivityService"
            name.contains("LocationMan") || name.contains("GnssLoc") -> "LocationManager"
            name.contains("AudioService") || name.contains("AudioFlinger") -> "AudioService"
            name.contains("CameraServ") -> "CameraService"
            name.contains("NotificationM") -> "NotificationManager"
            name.contains("JobScheduler") || name.contains("JobService") -> "JobScheduler"
            name.contains("BatteryStats") -> "BatteryStats"
            name.contains("WifiService") || name.contains("WifiP2p") -> "WifiService"
            name.contains("BluetoothM") -> "BluetoothManager"
            name.contains("StorageManag") -> "StorageManager"
            name.contains("AccountManag") -> "AccountManager"
            name.contains("ContentServ") || name.contains("SyncManager") -> "ContentService"
            name.contains("UsbService") -> "UsbService"
            name.contains("Watchdog") -> "Watchdog"
            name.contains("FinalizerDa") || name.contains("FinalizerW") -> "GC/Finalizer"
            name.contains("HeapTaskDae") -> "GC/HeapTask"
            name.contains("ReferenceQu") -> "GC/RefQueue"
            name.contains("Signal Catch") -> "Runtime"
            name.contains("Jit thread") || name.contains("JDWP") -> "Runtime"
            name.startsWith("pool-") || name.startsWith("OkHttp") -> "ThreadPool"
            name.startsWith("AsyncTask") -> "AsyncTask"
            name.contains("Handler") || name.contains("Looper") -> "Handler"
            name == "main" -> "MainThread"
            name.contains("RenderThread") -> "RenderThread"
            else -> "Other"
        }
    }

    private fun readProcRss(pid: Int): Long {
        val line = runCatching {
            File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
        }.getOrNull() ?: return 0L
        return line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun readProcPss(pid: Int): Long {
        val file = File("/proc/$pid/smaps_rollup")
        if (!file.exists()) return 0L
        val line = runCatching {
            file.readLines().firstOrNull { it.startsWith("Pss:") }
        }.getOrNull() ?: return 0L
        return line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun readBinderStats(pid: Int): BinderStats? {
        val statsFile = File("/proc/binder/stats")
        if (!statsFile.exists()) return null

        var txnRecv = 0L
        var txnSent = 0L
        runCatching {
            statsFile.forEachLine { line ->
                when {
                    line.contains("BC_TRANSACTION:") -> {
                        txnSent = line.lastLong()
                    }

                    line.contains("BR_TRANSACTION:") -> {
                        txnRecv = line.lastLong()
                    }
                }
            }
        }

        val procBinderFile = File("/proc/binder/proc/$pid")
        var pendingTxn = 0
        var readyThreads = 0
        var freeAsync = 0L
        if (procBinderFile.exists()) {
            runCatching {
                procBinderFile.forEachLine { line ->
                    when {
                        line.contains("pending transactions:") -> {
                            pendingTxn = line.lastInt()
                        }

                        line.contains("ready threads") -> {
                            readyThreads = line.lastInt()
                        }

                        line.contains("free async space") -> {
                            freeAsync = line.lastLong()
                        }
                    }
                }
            }
        }

        return BinderStats(txnRecv, txnSent, pendingTxn, readyThreads, freeAsync)
    }

    private fun String.lastLong(): Long = trim().split("\\s+".toRegex()).lastOrNull()?.toLongOrNull() ?: 0L

    private fun String.lastInt(): Int = lastLong().toInt()

    private fun readPsi(path: String): PsiStats {
        val file = File(path)
        if (!file.exists()) return PsiStats(0f, 0f, 0f, 0f, 0f, 0f)
        var someAvg10 = 0f
        var someAvg60 = 0f
        var someAvg300 = 0f
        var fullAvg10 = 0f
        var fullAvg60 = 0f
        var fullAvg300 = 0f
        runCatching {
            file.forEachLine { line ->
                val avgs = Regex("avg10=(\\S+) avg60=(\\S+) avg300=(\\S+)").find(line)
                if (avgs != null) {
                    val a10 = avgs.groupValues[1].toFloatOrNull() ?: 0f
                    val a60 = avgs.groupValues[2].toFloatOrNull() ?: 0f
                    val a300 = avgs.groupValues[3].toFloatOrNull() ?: 0f
                    if (line.startsWith(
                            "some",
                        )
                    ) {
                        someAvg10 = a10
                        someAvg60 = a60
                        someAvg300 = a300
                    }
                    if (line.startsWith(
                            "full",
                        )
                    ) {
                        fullAvg10 = a10
                        fullAvg60 = a60
                        fullAvg300 = a300
                    }
                }
            }
        }
        return PsiStats(someAvg10, someAvg60, someAvg300, fullAvg10, fullAvg60, fullAvg300)
    }

    private fun readSystemTotalTicks(): Long {
        val line = runCatching {
            File("/proc/stat").readLines().first { it.startsWith("cpu ") }
        }.getOrNull() ?: return 0L
        return line.trim().split("\\s+".toRegex()).drop(1).sumOf { it.toLongOrNull() ?: 0L }
    }

    fun exportReport(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val snapshot = collect()

        sb.appendLine("== SYSTEM_SERVER ANALYSIS ==")
        sb.appendLine("Generated: ${sdf.format(Date())}")
        sb.appendLine("PID: ${snapshot.pid}")
        sb.appendLine("Total CPU: %.1f%%".format(snapshot.totalCpuPercent))
        sb.appendLine(
            "RSS: ${snapshot.totalRssKb / 1024} MB | PSS: ${snapshot.totalPssKb / 1024} MB",
        )
        sb.appendLine("Threads: ${snapshot.totalThreads}")
        sb.appendLine()

        sb.appendLine(
            "CPU Pressure: some=%.1f/%.1f/%.1f%% full=%.1f/%.1f/%.1f%%".format(
                snapshot.cpuPressure.someAvg10,
                snapshot.cpuPressure.someAvg60,
                snapshot.cpuPressure.someAvg300,
                snapshot.cpuPressure.fullAvg10,
                snapshot.cpuPressure.fullAvg60,
                snapshot.cpuPressure.fullAvg300,
            ),
        )
        sb.appendLine(
            "Mem Pressure: some=%.1f/%.1f/%.1f%% full=%.1f/%.1f/%.1f%%".format(
                snapshot.memPressure.someAvg10,
                snapshot.memPressure.someAvg60,
                snapshot.memPressure.someAvg300,
                snapshot.memPressure.fullAvg10,
                snapshot.memPressure.fullAvg60,
                snapshot.memPressure.fullAvg300,
            ),
        )
        sb.appendLine()

        sb.appendLine("SERVICE CPU BREAKDOWN:")
        snapshot.topServices.filter { it.cpuPercent > 0.1f }.forEach { svc ->
            sb.appendLine(
                "  %-25s %5.1f%% (%d threads)".format(
                    svc.serviceName,
                    svc.cpuPercent,
                    svc.threadCount,
                ),
            )
        }
        sb.appendLine()

        sb.appendLine("TOP THREADS:")
        sb.appendLine(
            "%-6s %-25s %5s %6s %6s %-20s".format(
                "TID",
                "Name",
                "State",
                "CPU%",
                "USR+SYS",
                "Service",
            ),
        )
        snapshot.threads.filter { it.cpuPercent > 0.1f }.take(30).forEach { t ->
            sb.appendLine(
                "%-6d %-25s %5s %5.1f%% %6d %-20s".format(
                    t.tid,
                    t.name.take(25),
                    t.state,
                    t.cpuPercent,
                    t.userTicks + t.systemTicks,
                    t.service,
                ),
            )
        }

        return sb.toString()
    }
}
