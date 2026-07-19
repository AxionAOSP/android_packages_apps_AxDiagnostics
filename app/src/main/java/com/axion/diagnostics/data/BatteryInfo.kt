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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class BatterySnapshot(
    val level: Int,
    val scale: Int,
    val status: String,
    val health: String,
    val plugged: String,
    val technology: String,
    val temperatureC: Float,
    val voltageV: Float,
    val currentNowMa: Int,
    val currentAvgMa: Int,
    val chargeCounterUah: Int,
    val energyCounterNwh: Long,
    val capacityUah: Int,
    val cycleCount: Int,
    val drainRatePerHour: Float,
) {
    val levelPercent get() = if (scale > 0) level * 100 / scale else 0
}

object BatteryCollector {

    @Volatile private var lastLevel = -1
    @Volatile private var lastTimestamp = 0L
    @Volatile private var drainRate = 0f

    fun collect(context: Context): BatterySnapshot {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val bm = context.getSystemService(BatteryManager::class.java)

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val statusInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val healthInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val pluggedInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltageRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val currentNow = bm
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val currentAvg = bm
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
        val chargeCounter = bm
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val energyCounter = bm
            ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: 0L
        val capacity = readIntFromPower("charge_full") ?: 0
        val cycleCount = readIntFromPower("cycle_count") ?: 0

        val now = System.currentTimeMillis()
        if (lastLevel >= 0 && lastTimestamp > 0 && level < lastLevel) {
            val elapsed = (now - lastTimestamp) / 3600000f
            if (elapsed > 0.001f) {
                val levelDiff = lastLevel - level
                drainRate = levelDiff.toFloat() / elapsed
            }
        }
        if (level != lastLevel) {
            lastLevel = level
            lastTimestamp = now
        }

        return BatterySnapshot(
            level = level,
            scale = scale,
            status = statusToString(statusInt),
            health = healthToString(healthInt),
            plugged = pluggedToString(pluggedInt),
            technology = technology,
            temperatureC = tempRaw / 10f,
            voltageV = if (voltageRaw > 1000) voltageRaw / 1000f else voltageRaw.toFloat(),
            currentNowMa = normalizeBatteryCurrentMa(currentNow),
            currentAvgMa = normalizeBatteryCurrentMa(currentAvg),
            chargeCounterUah = chargeCounter,
            energyCounterNwh = energyCounter,
            capacityUah = capacity,
            cycleCount = cycleCount,
            drainRatePerHour = drainRate,
        )
    }

    private fun readIntFromPower(name: String): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/$name",
            "/sys/class/power_supply/Battery/$name",
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                return runCatching { file.readText().trim().toInt() }.getOrNull()
            }
        }
        return null
    }

    private fun statusToString(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
        else -> "Unknown"
    }

    private fun healthToString(health: Int) = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    private fun pluggedToString(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        0 -> "Unplugged"
        else -> "Unknown"
    }
}

internal fun normalizeBatteryCurrentMa(rawCurrent: Int): Int {
    if (rawCurrent == 0) return 0
    val raw = rawCurrent.toLong()
    val absRaw = abs(raw)
    val currentMa = if (absRaw >= CURRENT_UA_THRESHOLD) {
        (raw / 1000f).roundToInt()
    } else {
        rawCurrent
    }
    return currentMa.coerceIn(-MAX_REASONABLE_CURRENT_MA, MAX_REASONABLE_CURRENT_MA)
}

private const val CURRENT_UA_THRESHOLD = 10_000L
private const val MAX_REASONABLE_CURRENT_MA = 20_000
