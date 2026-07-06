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

package com.axion.diagnostics.export

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.axion.diagnostics.data.BatteryCollector
import com.axion.diagnostics.data.CpuCollector
import com.axion.diagnostics.data.DrainTracker
import com.axion.diagnostics.data.GpuCollector
import com.axion.diagnostics.data.HealthAnalyzer
import com.axion.diagnostics.data.IoCollector
import com.axion.diagnostics.data.LeakDetector
import com.axion.diagnostics.data.MemCollector
import com.axion.diagnostics.data.ProcessCollector
import com.axion.diagnostics.data.RegressionTracker
import com.axion.diagnostics.data.StorageCollector
import com.axion.diagnostics.data.StorageVolumeRole
import com.axion.diagnostics.data.SystemServerAnalyzer
import com.axion.diagnostics.data.ThermalCollector
import com.axion.diagnostics.data.WakelockCollector
import com.axion.diagnostics.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    fun export(context: Context): String {
        val file = ReportFiles.create(context, "diag")

        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("  AxDiagnostics Report")
        sb.appendLine(
            "  Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}"
        )
        sb.appendLine("========================================")
        sb.appendLine()

        appendHealthSummary(sb, context)
        appendSystemInfo(sb)
        appendCpuInfo(sb)
        appendMemoryInfo(sb)
        appendStorageInfo(sb)
        appendBatteryInfo(sb, context)
        appendThermalInfo(sb)
        appendGpuInfo(sb)
        appendIoInfo(sb)
        appendProcessInfo(sb)
        appendWakelockInfo(sb)
        appendSystemServerInfo(sb)
        appendDrainInfo(sb)
        appendLeakInfo(sb)
        appendRegressionInfo(sb)

        file.writeText(sb.toString())
        return file.absolutePath
    }

    private fun appendSystemInfo(sb: StringBuilder) {
        sb.appendLine("== SYSTEM INFO ==")
        sb.appendLine("Device: ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine("Kernel: ${System.getProperty("os.version")}")
        val uptime = SystemClock.elapsedRealtime()
        sb.appendLine("Uptime: ${uptime / 3_600_000}h ${(uptime % 3_600_000) / 60_000}m")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine()
    }

    private fun appendCpuInfo(sb: StringBuilder) {
        sb.appendLine("== CPU ==")
        val cpu = CpuCollector.collect()
        sb.appendLine("Total Usage: %.1f%%".format(cpu.totalUsage))
        sb.appendLine("User: %.1f%% | System: %.1f%% | IOWait: %.1f%% | IRQ: %.1f%%".format(
            cpu.userPercent,
            cpu.systemPercent,
            cpu.iowaitPercent,
            cpu.irqPercent
        ))
        sb.appendLine("Load Avg: %.2f %.2f %.2f".format(cpu.loadAvg1, cpu.loadAvg5, cpu.loadAvg15))
        sb.appendLine(
            "Context Switches: %,d | Processes: %,d".format(cpu.contextSwitches, cpu.processes)
        )
        sb.appendLine("Running: ${cpu.procsRunning} | Blocked: ${cpu.procsBlocked}")
        sb.appendLine()
        sb.appendLine("Per-Core:")
        cpu.cores.forEach { core ->
            sb.appendLine("  CPU${core.index}: %.1f%% @ %d MHz (%d-%d MHz) [%s] %s".format(
                core.usagePercent,
                core.frequencyMhz,
                core.minFrequencyMhz,
                core.maxFrequencyMhz,
                core.governor,
                if (core.online) "" else "OFFLINE"
            ))
        }
        sb.appendLine()
    }

    private fun appendMemoryInfo(sb: StringBuilder) {
        sb.appendLine("== MEMORY ==")
        val mem = MemCollector.collect()
        sb.appendLine("Total: ${mem.totalKb / 1024} MB")
        sb.appendLine("Used: ${mem.usedKb / 1024} MB (%.1f%%)".format(mem.usedPercent))
        sb.appendLine("Available: ${mem.availableKb / 1024} MB")
        sb.appendLine("Free: ${mem.freeKb / 1024} MB")
        sb.appendLine("Buffers: ${mem.buffersKb / 1024} MB | Cached: ${mem.cachedKb / 1024} MB")
        sb.appendLine("Active: ${mem.activeKb / 1024} MB | Inactive: ${mem.inactiveKb / 1024} MB")
        sb.appendLine("AnonPages: ${mem.anonPagesKb / 1024} MB | Mapped: ${mem.mappedKb / 1024} MB")
        sb.appendLine("Slab: ${mem.slabKb / 1024} MB | KernelStack: ${mem.kernelStackKb / 1024} MB")
        sb.appendLine("Dirty: ${mem.dirtyKb} KB | Writeback: ${mem.writebackKb} KB")
        if (mem.swapTotalKb > 0) {
            sb.appendLine(
                "Swap: ${mem.swapUsedKb / 1024} / ${mem.swapTotalKb / 1024} MB (%.1f%%)".format(
                    mem.swapUsedPercent
                )
            )
        }
        if (mem.zramTotalKb > 0) {
            val usedMb = mem.zramUsedKb / 1024
            val totalMb = mem.zramTotalKb / 1024
            sb.appendLine(
                "zRAM: $usedMb / $totalMb MB (ratio: %.2fx)".format(
                    mem.zramCompressionRatio
                )
            )
        }
        sb.appendLine()
    }

    private fun appendBatteryInfo(sb: StringBuilder, context: Context) {
        sb.appendLine("== BATTERY ==")
        val bat = BatteryCollector.collect(context)
        sb.appendLine("Level: ${bat.levelPercent}% | Status: ${bat.status} | Health: ${bat.health}")
        sb.appendLine("Plugged: ${bat.plugged} | Technology: ${bat.technology}")
        sb.appendLine("Current: ${bat.currentNowMa} mA (avg: ${bat.currentAvgMa} mA)")
        sb.appendLine("Voltage: %.3fV | Temperature: %.1f°C".format(bat.voltageV, bat.temperatureC))
        if (bat.capacityUah > 0) sb.appendLine("Capacity: ${bat.capacityUah / 1000} mAh")
        if (bat.cycleCount > 0) sb.appendLine("Cycle Count: ${bat.cycleCount}")
        if (bat.drainRatePerHour > 0) {
            sb.appendLine(
                "Drain Rate: %.1f%%/hr".format(bat.drainRatePerHour)
            )
        }
        sb.appendLine()
    }

    private fun appendThermalInfo(sb: StringBuilder) {
        sb.appendLine("== THERMAL ==")
        val thermal = ThermalCollector.collect()
        sb.appendLine("Hottest: ${thermal.hottest} @ %.1f°C".format(thermal.maxTemperature))
        sb.appendLine()
        thermal.zones.sortedByDescending { it.temperatureC }.forEach { zone ->
            sb.appendLine("  %-25s %.1f°C".format(zone.type, zone.temperatureC))
        }
        sb.appendLine()
        val activeCooling = thermal.coolingDevices.filter { it.currentState > 0 }
        if (activeCooling.isNotEmpty()) {
            sb.appendLine("Active Cooling:")
            activeCooling.forEach { cd ->
                sb.appendLine("  %-25s %d/%d".format(cd.type, cd.currentState, cd.maxState))
            }
            sb.appendLine()
        }
    }

    private fun appendGpuInfo(sb: StringBuilder) {
        val gpu = GpuCollector.collect()
        if (!gpu.available) return
        sb.appendLine("== GPU ==")
        sb.appendLine(
            "Frequency: ${gpu.frequencyMhz} MHz (${gpu.minFrequencyMhz}-${gpu.maxFrequencyMhz})"
        )
        sb.appendLine("Load: ${gpu.busyPercent}% | Governor: ${gpu.governor}")
        if (gpu.availableFrequencies.isNotEmpty()) {
            sb.appendLine("Available: ${gpu.availableFrequencies.joinToString(", ")}")
        }
        sb.appendLine()
    }

    private fun appendIoInfo(sb: StringBuilder) {
        sb.appendLine("== I/O ==")
        val io = IoCollector.collect()
        sb.appendLine(
            "Read: %.1f KB/s | Write: %.1f KB/s".format(io.readRateKbps, io.writeRateKbps)
        )
        sb.appendLine("IO Pressure (some): %.1f%% / %.1f%% / %.1f%% (10/60/300s)".format(
            io.ioPressure.someAvg10,
            io.ioPressure.someAvg60,
            io.ioPressure.someAvg300
        ))
        sb.appendLine("IO Pressure (full): %.1f%% / %.1f%% / %.1f%% (10/60/300s)".format(
            io.ioPressure.fullAvg10,
            io.ioPressure.fullAvg60,
            io.ioPressure.fullAvg300
        ))
        io.disks.forEach { disk ->
            sb.appendLine(
                "  ${disk.device}: R=${disk.readsCompleted} " +
                    "W=${disk.writesCompleted} InFlight=${disk.ioInProgress}"
            )
        }
        sb.appendLine()
    }

    private fun appendProcessInfo(sb: StringBuilder) {
        sb.appendLine("== TOP PROCESSES ==")
        sb.appendLine(
            "%-8s %-25s %6s %10s %4s %4s".format("PID", "Name", "CPU%", "RSS", "Thr", "OOM")
        )
        val procs = ProcessCollector.collect(30)
        procs.forEach { p ->
            sb.appendLine("%-8d %-25s %5.1f%% %8dKB %4d %4d".format(
                p.pid,
                p.name.take(25),
                p.cpuPercent,
                p.rssKb,
                p.threads,
                p.oomAdj
            ))
        }
        sb.appendLine()
    }

    private fun appendWakelockInfo(sb: StringBuilder) {
        sb.appendLine("== WAKELOCKS ==")
        val wl = WakelockCollector.collect()
        sb.appendLine("Total Active: ${wl.totalActiveCount}")
        sb.appendLine("Total Prevent Suspend: ${wl.totalPreventSuspendMs}ms")
        sb.appendLine()
        sb.appendLine("%-35s %8s %10s %10s".format("Name", "Count", "Total", "Max"))
        wl.kernelWakelocks.take(50).forEach { w ->
            sb.appendLine("%-35s %8d %10s %10s".format(
                w.name.take(35),
                w.activeCount,
                "${w.totalTimeMs}ms",
                "${w.maxTimeMs}ms"
            ))
        }
        sb.appendLine()
    }

    private fun appendRegressionInfo(sb: StringBuilder) {
        if (RegressionTracker.sampleCount == 0) return
        sb.appendLine("== REGRESSION TRACKING ==")
        sb.appendLine(RegressionTracker.exportHistory())
        sb.appendLine()
    }

    private fun appendDrainInfo(sb: StringBuilder) {
        if (!DrainTracker.isTracking && DrainTracker.getSummary() == null) return
        sb.appendLine("== DRAIN LOG ==")
        sb.appendLine(DrainTracker.exportDrainLog())
        sb.appendLine()
    }

    private fun appendLeakInfo(sb: StringBuilder) {
        if (!LeakDetector.isTracking && LeakDetector.getLeakCandidates().isEmpty()) return
        sb.appendLine(LeakDetector.exportReport())
        sb.appendLine()
    }

    private fun appendSystemServerInfo(sb: StringBuilder) {
        sb.appendLine(SystemServerAnalyzer.exportReport())
        sb.appendLine()
    }

    private fun appendHealthSummary(sb: StringBuilder, context: Context) {
        val report = HealthAnalyzer.analyze(context)
        sb.appendLine("== HEALTH SUMMARY ==")
        sb.appendLine("Score: ${report.overallScore}/100 (${report.overallLabel})")
        sb.appendLine("Top CPU: ${report.topCpuProcess}")
        sb.appendLine("Top Memory: ${report.topMemProcess}")
        sb.appendLine("Drain Rate: %.1f%%/hr".format(report.drainRate))
        sb.appendLine("Thermal: ${report.thermalStatus}")
        sb.appendLine("I/O: ${report.ioStatus}")
        sb.appendLine("Storage: ${report.storageStatus}")
        sb.appendLine()
        if (report.insights.isNotEmpty()) {
            sb.appendLine("INSIGHTS:")
            report.insights.forEach { i ->
                sb.appendLine("  [${i.severity}] ${i.category}: ${i.title} - ${i.detail}")
            }
            sb.appendLine()
        }
        sb.appendLine(
            "NOTE: Background monitoring is event-driven. Visual polling is app-visible only."
        )
        sb.appendLine()
    }

    private fun appendStorageInfo(sb: StringBuilder) {
        sb.appendLine("== STORAGE ==")
        val storage = StorageCollector.collect()
        storage.volumes.forEach { volume ->
            sb.appendLine("%-10s %8s used / %8s total (%5.1f%% used) %8s available [%s]".format(
                storageRoleName(volume.role),
                formatBytes(volume.usedBytes),
                formatBytes(volume.totalBytes),
                volume.usedPercent,
                formatBytes(volume.availableBytes),
                volume.path
            ))
        }
        sb.appendLine()
    }

    private fun storageRoleName(role: StorageVolumeRole): String = when (role) {
        StorageVolumeRole.DATA -> "data"
        StorageVolumeRole.CACHE -> "cache"
        StorageVolumeRole.SYSTEM -> "system"
        StorageVolumeRole.SYSTEM_EXT -> "system_ext"
        StorageVolumeRole.PRODUCT -> "product"
        StorageVolumeRole.VENDOR -> "vendor"
        StorageVolumeRole.EXTERNAL -> "external"
    }
}
