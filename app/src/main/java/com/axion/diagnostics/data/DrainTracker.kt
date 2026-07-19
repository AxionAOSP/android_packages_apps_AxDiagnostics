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
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class DrainSample(
    val timestamp: Long,
    val levelPercent: Int,
    val currentMa: Int,
    val currentAvgMa: Int,
    val voltageV: Float,
    val temperatureC: Float,
    val screenOn: Boolean,
    val charging: Boolean
)

data class DrainWindow(
    val label: String,
    val durationMs: Long,
    val drainPercent: Float,
    val drainPerHour: Float,
    val avgCurrentMa: Float,
    val avgTempC: Float,
    val screenOnPercent: Float,
    val sampleCount: Int
)

data class DrainSummary(
    val currentLevel: Int,
    val currentMa: Int,
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val instantDrainPerHour: Float,
    val last5minDrain: DrainWindow,
    val last15minDrain: DrainWindow,
    val last30minDrain: DrainWindow,
    val last1hrDrain: DrainWindow,
    val screenOnDrainPerHour: Float,
    val screenOffDrainPerHour: Float,
    val samples: List<DrainSample>,
    val hourlyBreakdown: List<HourlyDrain>
)

data class HourlyDrain(
    val hour: String,
    val drainPercent: Float,
    val avgCurrentMa: Float,
    val screenOnPercent: Float
)

data class ScreenStateStats(
    val screenOn: Boolean,
    val durationMs: Long,
    val drainPercent: Float,
    val mahConsumed: Float,
    val avgCurrentMa: Float,
    val drainPerHour: Float
)

data class MonitorBreakdown(
    val screenOn: ScreenStateStats,
    val screenOff: ScreenStateStats,
    val sessionDurationMs: Long,
    val totalDrainPercent: Float,
    val deviceScreenOnDurationMs: Long,
    val deviceScreenOffDurationMs: Long,
    val hasDeviceScreenDurations: Boolean,
    val deepSleepDurationMs: Long,
    val awakeDurationMs: Long
)

object DrainTracker {

    private val samples = CopyOnWriteArrayList<DrainSample>()
    private val currentReadings = CopyOnWriteArrayList<Int>()
    private const val MAX_SAMPLES = 3600
    private const val MAX_MAH_SEGMENT_MS = 120_000L

    @Volatile
    var isTracking = false
        private set

    fun startTracking() {
        isTracking = true
        samples.clear()
        currentReadings.clear()
    }

    fun stopTracking() {
        isTracking = false
    }

    fun recordSample(context: Context) {
        if (!isTracking) return
        recordSample(
            context,
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        )
    }

    fun recordBatteryEvent(context: Context, batteryIntent: Intent) {
        if (!isTracking) return
        recordSample(context, batteryIntent)
    }

    private fun recordSample(context: Context, batteryIntent: Intent?) {
        if (!isTracking) return

        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return

        val statusInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltageRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val currentNow = normalizeBatteryCurrentMa(
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        )
        val currentAvg = normalizeBatteryCurrentMa(
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
        )
        val screenOn = pm?.isInteractive ?: true
        val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL

        val levelPercent = if (scale > 0) level * 100 / scale else level

        val sample = DrainSample(
            timestamp = System.currentTimeMillis(),
            levelPercent = levelPercent,
            currentMa = currentNow,
            currentAvgMa = currentAvg,
            voltageV = if (voltageRaw > 1000) voltageRaw / 1000f else voltageRaw.toFloat(),
            temperatureC = tempRaw / 10f,
            screenOn = screenOn,
            charging = charging
        )

        samples.add(sample)
        currentReadings.add(currentNow)

        while (samples.size > MAX_SAMPLES) {
            samples.removeAt(0)
        }
        while (currentReadings.size > MAX_SAMPLES) {
            currentReadings.removeAt(0)
        }
    }

    fun getSummary(): DrainSummary? {
        if (samples.isEmpty()) return null
        val latest = samples.last()
        val now = latest.timestamp

        val instantDrain = if (currentReadings.size >= 3) {
            val recent = currentReadings.takeLast(5)
            val avgMa = recent.average().toFloat()
            estimateDrainPerHour(avgMa)
        } else {
            0f
        }

        val screenOnSamples = samples.filter { it.screenOn && !it.charging }
        val screenOffSamples = samples.filter { !it.screenOn && !it.charging }

        val screenOnDrain = computeRateFromSamples(screenOnSamples)
        val screenOffDrain = computeRateFromSamples(screenOffSamples)

        return DrainSummary(
            currentLevel = latest.levelPercent,
            currentMa = latest.currentMa,
            isCharging = latest.charging,
            isScreenOn = latest.screenOn,
            instantDrainPerHour = instantDrain,
            last5minDrain = computeWindow("5 min", now, 5 * 60_000L),
            last15minDrain = computeWindow("15 min", now, 15 * 60_000L),
            last30minDrain = computeWindow("30 min", now, 30 * 60_000L),
            last1hrDrain = computeWindow("1 hour", now, 60 * 60_000L),
            screenOnDrainPerHour = screenOnDrain,
            screenOffDrainPerHour = screenOffDrain,
            samples = samples.toList(),
            hourlyBreakdown = computeHourlyBreakdown()
        )
    }

    fun getBreakdown(context: Context): MonitorBreakdown? {
        val snapshot = samples.toList()
        if (snapshot.isEmpty()) return null

        var onMs = 0L
        var offMs = 0L
        var onMah = 0f
        var offMah = 0f
        var onDrain = 0f
        var offDrain = 0f
        val now = System.currentTimeMillis()

        for (i in 1 until snapshot.size) {
            val prev = snapshot[i - 1]
            val cur = snapshot[i]
            val dt = (cur.timestamp - prev.timestamp).coerceAtLeast(0L)
            if (dt == 0L) continue

            if (prev.screenOn) {
                onMs += dt
            } else {
                offMs += dt
            }
            if (prev.charging || cur.charging) continue

            val hours = dt / 3_600_000f
            val mah = if (dt <= MAX_MAH_SEGMENT_MS) {
                abs(prev.currentMa.toFloat()) * hours
            } else {
                0f
            }
            val drop = (prev.levelPercent - cur.levelPercent).coerceAtLeast(0).toFloat()

            if (prev.screenOn) {
                onMah += mah
                onDrain += drop
            } else {
                offMah += mah
                offDrain += drop
            }
        }

        val latest = snapshot.last()
        val activeDt = (now - latest.timestamp).coerceAtLeast(0L)
        if (activeDt > 0) {
            if (latest.screenOn) {
                onMs += activeDt
            } else {
                offMs += activeDt
            }
        }

        val deviceUsage = DeviceUsageCollector.collect(context)
        val sampleWindowMs = if (snapshot.size >= 2) {
            snapshot.last().timestamp - snapshot.first().timestamp
        } else {
            activeDt
        }
        val sessionMs = maxOf(onMs + offMs, sampleWindowMs)
        return MonitorBreakdown(
            screenOn = buildStateStats(true, onMs, onDrain, onMah),
            screenOff = buildStateStats(false, offMs, offDrain, offMah),
            sessionDurationMs = sessionMs,
            totalDrainPercent = onDrain + offDrain,
            deviceScreenOnDurationMs = deviceUsage.screenOnDurationMs,
            deviceScreenOffDurationMs = deviceUsage.screenOffDurationMs,
            hasDeviceScreenDurations = deviceUsage.hasScreenDurations,
            deepSleepDurationMs = deviceUsage.deepSleepDurationMs,
            awakeDurationMs = deviceUsage.awakeDurationMs
        )
    }

    private fun buildStateStats(
        screenOn: Boolean,
        durationMs: Long,
        drainPercent: Float,
        mahConsumed: Float
    ): ScreenStateStats {
        val hours = durationMs / 3_600_000f
        val avgCurrent = if (hours > 0f) mahConsumed / hours else 0f
        val drainPerHour = if (hours > 0f) drainPercent / hours else 0f
        return ScreenStateStats(
            screenOn,
            durationMs,
            drainPercent,
            mahConsumed,
            avgCurrent,
            drainPerHour
        )
    }

    private fun computeWindow(label: String, now: Long, windowMs: Long): DrainWindow {
        val windowSamples = samples.filter { now - it.timestamp <= windowMs }
        if (windowSamples.size < 2) return DrainWindow(label, windowMs, 0f, 0f, 0f, 0f, 0f, 0)

        val first = windowSamples.first()
        val last = windowSamples.last()
        val elapsed = (last.timestamp - first.timestamp).toFloat()
        if (elapsed < 1000) {
            return DrainWindow(
                label,
                windowMs,
                0f,
                0f,
                0f,
                0f,
                0f,
                windowSamples.size
            )
        }

        val drainPercent = (first.levelPercent - last.levelPercent).toFloat()
        val drainPerHour = drainPercent / (elapsed / 3_600_000f)
        val avgCurrent = windowSamples.map { it.currentMa }.average().toFloat()
        val avgTemp = windowSamples.map { it.temperatureC }.average().toFloat()
        val screenOnCount = windowSamples.count { it.screenOn }
        val screenOnPct = screenOnCount.toFloat() / windowSamples.size * 100f

        return DrainWindow(
            label = label,
            durationMs = elapsed.toLong(),
            drainPercent = drainPercent.coerceAtLeast(0f),
            drainPerHour = drainPerHour.coerceAtLeast(0f),
            avgCurrentMa = avgCurrent,
            avgTempC = avgTemp,
            screenOnPercent = screenOnPct,
            sampleCount = windowSamples.size
        )
    }

    private fun computeRateFromSamples(filteredSamples: List<DrainSample>): Float {
        if (filteredSamples.size < 2) return 0f
        val first = filteredSamples.first()
        val last = filteredSamples.last()
        val elapsed = (last.timestamp - first.timestamp).toFloat()
        if (elapsed < 10_000) return 0f
        val drain = (first.levelPercent - last.levelPercent).toFloat()
        return (drain / (elapsed / 3_600_000f)).coerceAtLeast(0f)
    }

    private fun computeHourlyBreakdown(): List<HourlyDrain> {
        if (samples.size < 2) return emptyList()
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        val bucketMs = 3_600_000L
        val result = mutableListOf<HourlyDrain>()

        val first = samples.first().timestamp
        val last = samples.last().timestamp
        var bucketStart = first

        while (bucketStart < last) {
            val bucketEnd = bucketStart + bucketMs
            val bucketSamples = samples.filter { it.timestamp in bucketStart until bucketEnd }
            if (bucketSamples.size >= 2) {
                val drain = (
                    bucketSamples.first().levelPercent - bucketSamples.last().levelPercent
                ).toFloat()
                val avgCurrent = bucketSamples.map { it.currentMa }.average().toFloat()
                val screenOnPct = bucketSamples.count { it.screenOn }
                    .toFloat() / bucketSamples.size * 100f
                result.add(
                    HourlyDrain(
                        hour = sdf.format(Date(bucketStart)),
                        drainPercent = drain.coerceAtLeast(0f),
                        avgCurrentMa = avgCurrent,
                        screenOnPercent = screenOnPct
                    )
                )
            }
            bucketStart = bucketEnd
        }
        return result
    }

    private fun estimateDrainPerHour(avgMa: Float): Float {
        val capacityUah = readBatteryCapacity()
        if (capacityUah <= 0) return 0f
        val absMa = abs(avgMa)
        return (absMa / (capacityUah / 1000f)) * 100f
    }

    private fun readBatteryCapacity(): Int {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/Battery/charge_full"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                return runCatching { file.readText().trim().toInt() }.getOrDefault(0)
            }
        }
        return 0
    }

    fun exportDrainLog(): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        sb.appendLine("== DRAIN LOG ==")
        sb.appendLine(
            "%-10s %5s %6s %6s %5s %4s %5s".format(
                "Time",
                "Level",
                "mA",
                "AvgmA",
                "Temp",
                "Scr",
                "Chg"
            )
        )
        samples.forEach { s ->
            sb.appendLine("%-10s %4d%% %5dmA %5dmA %4.1f°C %4s %4s".format(
                sdf.format(Date(s.timestamp)),
                s.levelPercent,
                s.currentMa,
                s.currentAvgMa,
                s.temperatureC,
                if (s.screenOn) "ON" else "OFF",
                if (s.charging) "YES" else "NO"
            ))
        }
        return sb.toString()
    }
}
