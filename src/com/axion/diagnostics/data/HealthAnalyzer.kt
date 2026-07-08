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
import com.axion.diagnostics.R
import com.axion.diagnostics.util.formatBytes

enum class InsightSeverity { OK, INFO, WARN, CRITICAL }

data class HealthInsight(
    val category: String,
    val severity: InsightSeverity,
    val title: String,
    val detail: String,
    val metric: String
)

data class HealthReport(
    val overallScore: Int,
    val overallLabel: String,
    val insights: List<HealthInsight>,
    val topCpuProcess: String,
    val topMemProcess: String,
    val drainRate: Float,
    val thermalStatus: String,
    val ioStatus: String,
    val cpuUsage: Float,
    val memUsage: Float,
    val storageStatus: String,
    val storageUsage: Float,
    val batteryLevel: Int,
    val batteryStatus: String
)

object HealthAnalyzer {

    fun analyze(context: Context): HealthReport {
        val insights = mutableListOf<HealthInsight>()

        val cpu = runCatching { CpuCollector.collect() }.getOrElse {
            CpuSnapshot(0f, 0f, 0f, 0f, 0f, emptyList(), 0f, 0f, 0f, 0L, 0L, 0L, 0L, 0, 0)
        }
        val mem = runCatching { MemCollector.collect() }.getOrElse {
            MemSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
        }
        val battery = runCatching { BatteryCollector.collect(context) }.getOrElse {
            BatterySnapshot(0, 100, "Unknown", "Unknown", "Unplugged", "unknown", 0f, 0f, 0, 0, 0, 0L, 0, 0, 0f)
        }
        val thermal = runCatching { ThermalCollector.collect() }.getOrElse {
            ThermalSnapshot(emptyList(), emptyList(), 0f, "none")
        }
        val io = runCatching { IoCollector.collect() }.getOrElse {
            IoSnapshot(emptyList(), 0f, 0f, IoPressure(0f, 0f, 0f, 0f, 0f, 0f))
        }
        val storage = runCatching { StorageCollector.collect() }.getOrElse {
            StorageSnapshot(emptyList(), null, null)
        }
        val processes = runCatching { ProcessCollector.collect(30) }.getOrDefault(emptyList())
        val gpu = runCatching { GpuCollector.collect() }.getOrElse {
            GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
        }

        analyzeCpu(cpu, insights)
        analyzeMemory(mem, insights)
        analyzeBattery(battery, insights)
        analyzeThermal(thermal, insights)
        analyzeIo(io, insights)
        analyzeStorage(context, storage, insights)
        analyzeProcesses(processes, insights)
        analyzeGpu(gpu, insights)

        val topCpu = processes.maxByOrNull { it.cpuPercent }
        val topMem = processes.maxByOrNull { it.rssKb }

        val drainSummary = DrainTracker.getSummary()
        val drainRate = drainSummary?.last5minDrain?.drainPerHour ?: 0f
        val dataVolume = storage.dataVolume

        val critCount = insights.count { it.severity == InsightSeverity.CRITICAL }
        val warnCount = insights.count { it.severity == InsightSeverity.WARN }
        val score = (100 - critCount * 25 - warnCount * 10).coerceIn(0, 100)

        val label = when {
            score >= 90 -> "Healthy"
            score >= 70 -> "Fair"
            score >= 40 -> "Degraded"
            else -> "Critical"
        }

        return HealthReport(
            overallScore = score,
            overallLabel = label,
            insights = insights.sortedBy {
                when (it.severity) {
                    InsightSeverity.CRITICAL -> 0
                    InsightSeverity.WARN -> 1
                    InsightSeverity.INFO -> 2
                    InsightSeverity.OK -> 3
                }
            },
            topCpuProcess = topCpu?.let { "${it.name} (%.1f%%)".format(it.cpuPercent) } ?: "none",
            topMemProcess = topMem?.let { "${it.name} (${it.rssKb / 1024}MB)" } ?: "none",
            drainRate = drainRate,
            thermalStatus = "%.1f°C (%s)".format(thermal.maxTemperature, thermal.hottest),
            ioStatus = "R:%.0f W:%.0f KB/s".format(io.readRateKbps, io.writeRateKbps),
            cpuUsage = cpu.totalUsage,
            memUsage = mem.usedPercent,
            storageStatus = dataVolume?.let {
                context.getString(
                    R.string.health_storage_status,
                    formatBytes(it.availableBytes),
                    it.usedPercent
                )
            } ?: context.getString(R.string.common_unavailable),
            storageUsage = dataVolume?.usedPercent ?: 0f,
            batteryLevel = battery.levelPercent,
            batteryStatus = battery.status
        )
    }

    private fun analyzeCpu(cpu: CpuSnapshot, insights: MutableList<HealthInsight>) {
        when {
            cpu.totalUsage > 80f -> insights.add(
                HealthInsight(
                    "CPU",
                    InsightSeverity.CRITICAL,
                    "CPU overloaded",
                    "Total CPU at %.0f%%. Check top processes for throttling and drain.".format(
                        cpu.totalUsage
                    ),
                    "%.0f%%".format(cpu.totalUsage)
                )
            )
            cpu.totalUsage > 50f -> insights.add(
                HealthInsight(
                    "CPU",
                    InsightSeverity.WARN,
                    "High CPU usage",
                    "Total CPU at %.0f%% — something is working hard. Check if expected.".format(
                        cpu.totalUsage
                    ),
                    "%.0f%%".format(cpu.totalUsage)
                )
            )
        }

        if (cpu.iowaitPercent > 20f) {
            insights.add(
                HealthInsight(
                    "CPU",
                    InsightSeverity.WARN,
                    "High IO wait",
                    "CPU spending %.0f%% waiting on I/O — storage may be slow or thrashing.".format(
                        cpu.iowaitPercent
                    ),
                    "%.0f%%".format(cpu.iowaitPercent)
                )
            )
        }

        if (cpu.loadAvg1 > cpu.cores.size * 2f) {
            insights.add(
                HealthInsight(
                    "CPU",
                    InsightSeverity.CRITICAL,
                    "System overloaded",
                    "Load average %.1f is %dx core count — processes are queuing.".format(
                        cpu.loadAvg1,
                        (cpu.loadAvg1 / cpu.cores.size).toInt()
                    ),
                    "%.1f".format(cpu.loadAvg1)
                )
            )
        }

        if (cpu.procsBlocked > 5) {
            insights.add(
                HealthInsight(
                    "CPU",
                    InsightSeverity.WARN,
                    "Blocked processes",
                    "%d processes blocked in uninterruptible sleep. Check I/O and locks.".format(
                        cpu.procsBlocked
                    ),
                    "${cpu.procsBlocked}"
                )
            )
        }
    }

    private fun analyzeMemory(mem: MemSnapshot, insights: MutableList<HealthInsight>) {
        when {
            mem.usedPercent > 95f -> insights.add(
                HealthInsight(
                    "Memory",
                    InsightSeverity.CRITICAL,
                    "Memory critical",
                    "Only ${mem.availableKb / 1024}MB free. Expect app kills, jank, and restarts.",
                    "%.0f%% used".format(mem.usedPercent)
                )
            )
            mem.usedPercent > 85f -> insights.add(
                HealthInsight(
                    "Memory",
                    InsightSeverity.WARN,
                    "Memory pressure",
                    "${mem.availableKb / 1024}MB available. Background apps may be killed.",
                    "%.0f%% used".format(mem.usedPercent)
                )
            )
        }

        if (mem.swapTotalKb > 0 && mem.swapUsedPercent > 70f) {
            insights.add(
                HealthInsight(
                    "Memory",
                    InsightSeverity.WARN,
                    "High swap usage",
                    "Swap %.0f%% full — causes slowdowns from swap I/O overhead.".format(
                        mem.swapUsedPercent
                    ),
                    "%.0f%%".format(mem.swapUsedPercent)
                )
            )
        }

        if (mem.dirtyKb > 100_000) {
            insights.add(
                HealthInsight(
                    "Memory",
                    InsightSeverity.INFO,
                    "Large dirty pages",
                    "${mem.dirtyKb / 1024}MB of dirty pages pending writeback.",
                    "${mem.dirtyKb / 1024}MB"
                )
            )
        }
    }

    private fun analyzeBattery(battery: BatterySnapshot, insights: MutableList<HealthInsight>) {
        if (battery.status == "Discharging" || battery.plugged == "Unplugged") {
            val absCurrent = kotlin.math.abs(battery.currentNowMa)
            when {
                absCurrent > 800 -> insights.add(
                    HealthInsight(
                        "Battery",
                        InsightSeverity.CRITICAL,
                        "Extreme power draw",
                        "Drawing ${absCurrent}mA. Check for runaway processes, GPS, or camera.",
                        "${absCurrent}mA"
                    )
                )
                absCurrent > 400 -> insights.add(
                    HealthInsight(
                        "Battery",
                        InsightSeverity.WARN,
                        "High power draw",
                        "Drawing ${absCurrent}mA. Higher than typical idle/light use.",
                        "${absCurrent}mA"
                    )
                )
            }
        }

        when {
            battery.temperatureC > 42f -> insights.add(
                HealthInsight(
                    "Battery",
                    InsightSeverity.CRITICAL,
                    "Battery overheating",
                    "Battery at %.1f°C. Risk of thermal shutdown and battery wear.".format(
                        battery.temperatureC
                    ),
                    "%.1f°C".format(battery.temperatureC)
                )
            )
            battery.temperatureC > 38f -> insights.add(
                HealthInsight(
                    "Battery",
                    InsightSeverity.WARN,
                    "Battery warm",
                    "Battery at %.1f°C — above normal operating range.".format(
                        battery.temperatureC
                    ),
                    "%.1f°C".format(battery.temperatureC)
                )
            )
        }

        val drainSummary = DrainTracker.getSummary()
        if (drainSummary != null && !drainSummary.isCharging) {
            val screenOffDrain = drainSummary.screenOffDrainPerHour
            if (screenOffDrain > 3f) {
                insights.add(
                    HealthInsight(
                        "Battery",
                        InsightSeverity.CRITICAL,
                        "Abnormal idle drain",
                        "Screen-off drain %.1f%%/hr. Check background app activity.".format(
                            screenOffDrain
                        ),
                        "%.1f%%/hr".format(screenOffDrain)
                    )
                )
            }

            val screenOnDrain = drainSummary.screenOnDrainPerHour
            if (screenOnDrain > 20f) {
                insights.add(
                    HealthInsight(
                        "Battery",
                        InsightSeverity.WARN,
                        "Heavy screen-on drain",
                        "Screen-on drain %.1f%%/hr. Check workload and display brightness.".format(
                            screenOnDrain
                        ),
                        "%.1f%%/hr".format(screenOnDrain)
                    )
                )
            }
        }
    }

    private fun analyzeThermal(thermal: ThermalSnapshot, insights: MutableList<HealthInsight>) {
        val activeCooling = thermal.coolingDevices.count { it.currentState > 0 }
        when {
            thermal.maxTemperature > 60f -> insights.add(
                HealthInsight(
                    "Thermal",
                    InsightSeverity.CRITICAL,
                    "Thermal emergency",
                    "${thermal.hottest} at %.0f°C. CPU/GPU throttling is likely.".format(
                        thermal.maxTemperature
                    ),
                    "%.0f°C".format(thermal.maxTemperature)
                )
            )
            thermal.maxTemperature > 45f -> insights.add(
                HealthInsight(
                    "Thermal",
                    InsightSeverity.WARN,
                    "Running hot",
                    "${thermal.hottest} at %.0f°C — thermal throttling may be active.".format(
                        thermal.maxTemperature
                    ),
                    "%.0f°C".format(thermal.maxTemperature)
                )
            )
        }

        if (activeCooling > 3) {
            insights.add(
                HealthInsight(
                    "Thermal",
                    InsightSeverity.WARN,
                    "Active cooling",
                    "$activeCooling cooling devices active.",
                    "$activeCooling devices"
                )
            )
        }
    }

    private fun analyzeIo(io: IoSnapshot, insights: MutableList<HealthInsight>) {
        when {
            io.ioPressure.someAvg10 > 50f -> insights.add(
                HealthInsight(
                    "I/O",
                    InsightSeverity.CRITICAL,
                    "I/O bottleneck",
                    "I/O pressure at %.0f%%. Storage is congested and apps may stutter.".format(
                        io.ioPressure.someAvg10
                    ),
                    "%.0f%%".format(io.ioPressure.someAvg10)
                )
            )
            io.ioPressure.someAvg10 > 20f -> insights.add(
                HealthInsight(
                    "I/O",
                    InsightSeverity.WARN,
                    "I/O pressure",
                    "I/O pressure at %.0f%% — some processes waiting on storage.".format(
                        io.ioPressure.someAvg10
                    ),
                    "%.0f%%".format(io.ioPressure.someAvg10)
                )
            )
        }

        if (io.ioPressure.fullAvg10 > 10f) {
            insights.add(
                HealthInsight(
                    "I/O",
                    InsightSeverity.WARN,
                    "Full I/O stall",
                    "Full I/O stall at %.0f%%. All tasks blocked on storage %.0f%% of time.".format(
                        io.ioPressure.fullAvg10,
                        io.ioPressure.fullAvg10
                    ),
                    "%.0f%%".format(io.ioPressure.fullAvg10)
                )
            )
        }
    }

    private fun analyzeStorage(
        context: Context,
        storage: StorageSnapshot,
        insights: MutableList<HealthInsight>
    ) {
        val data = storage.dataVolume ?: return
        when {
            data.availableBytes < 512L * 1024L * 1024L || data.availablePercent < 3f -> {
                insights.add(
                    HealthInsight(
                        context.getString(R.string.category_storage),
                        InsightSeverity.CRITICAL,
                        context.getString(R.string.health_storage_critical_title),
                        context.getString(
                            R.string.health_storage_critical_detail,
                            formatBytes(data.availableBytes),
                            data.availablePercent
                        ),
                        formatBytes(data.availableBytes)
                    )
                )
            }
            data.availableBytes < 2L * 1024L * 1024L * 1024L || data.availablePercent < 10f -> {
                insights.add(
                    HealthInsight(
                        context.getString(R.string.category_storage),
                        InsightSeverity.WARN,
                        context.getString(R.string.health_storage_warn_title),
                        context.getString(
                            R.string.health_storage_warn_detail,
                            formatBytes(data.availableBytes),
                            data.availablePercent
                        ),
                        formatBytes(data.availableBytes)
                    )
                )
            }
        }
    }

    private fun analyzeProcesses(
        processes: List<ProcessSnapshot>,
        insights: MutableList<HealthInsight>
    ) {
        val cpuHogs = processes.filter { it.cpuPercent > 30f }
        cpuHogs.forEach { proc ->
            insights.add(
                HealthInsight(
                    "Process",
                    InsightSeverity.CRITICAL,
                    "CPU hog: ${proc.name}",
                    "PID ${proc.pid} consuming %.0f%% CPU. Check whether it is stuck.".format(
                        proc.cpuPercent
                    ),
                    "%.0f%%".format(proc.cpuPercent)
                )
            )
        }

        val memHogs = processes.filter { it.rssKb > 500_000 }
        memHogs.forEach { proc ->
            insights.add(
                HealthInsight(
                    "Process",
                    InsightSeverity.WARN,
                    "Memory hog: ${proc.name}",
                    "PID ${proc.pid} using ${proc.rssKb / 1024}MB RSS — large memory footprint.",
                    "${proc.rssKb / 1024}MB"
                )
            )
        }
    }

    private fun analyzeGpu(gpu: GpuSnapshot, insights: MutableList<HealthInsight>) {
        if (!gpu.available) return
        if (gpu.busyPercent > 80) {
            insights.add(
                HealthInsight(
                    "GPU",
                    InsightSeverity.WARN,
                    "GPU saturated",
                    "GPU at ${gpu.busyPercent}% load @ ${gpu.frequencyMhz}MHz. Expect frame drops.",
                    "${gpu.busyPercent}%"
                )
            )
        }
    }
}
