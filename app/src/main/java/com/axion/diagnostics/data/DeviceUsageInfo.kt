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
import android.os.BatteryStats
import android.os.BatteryStatsManager
import android.os.BatteryUsageStatsQuery
import android.os.PowerManager
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import com.android.internal.app.IBatteryStats

data class DeviceUsageSnapshot(
    val screenOnDurationMs: Long,
    val screenOffDurationMs: Long,
    val hasScreenDurations: Boolean,
    val deepSleepDurationMs: Long,
    val awakeDurationMs: Long
)

object DeviceUsageCollector {

    private const val SCREEN_STATS_REFRESH_INTERVAL_MS = 60_000L
    @Volatile private var cachedScreenDurations: DeviceScreenDurations? = null
    @Volatile private var lastScreenStatsReadElapsedMs = 0L

    fun collect(context: Context): DeviceUsageSnapshot {
        val elapsedMs = SystemClock.elapsedRealtime()
        val awakeMs = SystemClock.uptimeMillis().coerceIn(0L, elapsedMs)
        val screenDurations = screenDurations(context, elapsedMs)
        return DeviceUsageSnapshot(
            screenOnDurationMs = screenDurations?.screenOnDurationMs ?: 0L,
            screenOffDurationMs = screenDurations?.screenOffDurationMs ?: 0L,
            hasScreenDurations = screenDurations != null,
            deepSleepDurationMs = elapsedMs - awakeMs,
            awakeDurationMs = awakeMs
        )
    }

    private fun screenDurations(context: Context, elapsedMs: Long): DeviceScreenDurations? {
        if (lastScreenStatsReadElapsedMs == 0L ||
            elapsedMs - lastScreenStatsReadElapsedMs >= SCREEN_STATS_REFRESH_INTERVAL_MS
        ) {
            lastScreenStatsReadElapsedMs = elapsedMs
            cachedScreenDurations = readScreenDurations(context, elapsedMs)
        }

        val cached = cachedScreenDurations ?: return null
        val deltaMs = (elapsedMs - cached.elapsedRealtimeMs).coerceAtLeast(0L)
        return if (context.getSystemService(PowerManager::class.java)?.isInteractive == true) {
            cached.copy(screenOnDurationMs = cached.screenOnDurationMs + deltaMs)
        } else {
            cached.copy(screenOffDurationMs = cached.screenOffDurationMs + deltaMs)
        }
    }

    private fun readScreenDurations(
        context: Context,
        elapsedMs: Long
    ): DeviceScreenDurations? {
        val totalDurationMs = readBatteryDuration(context) ?: return null
        val screenOffDurationMs = readScreenOffDuration() ?: return null
        return DeviceScreenDurations(
            screenOnDurationMs = (totalDurationMs - screenOffDurationMs).coerceAtLeast(0L),
            screenOffDurationMs = screenOffDurationMs,
            elapsedRealtimeMs = elapsedMs
        )
    }

    private fun readBatteryDuration(context: Context): Long? {
        val manager = context.getSystemService(BatteryStatsManager::class.java) ?: return null
        val query = BatteryUsageStatsQuery.Builder()
            .setMaxStatsAgeMs(0)
            .build()
        val stats = try {
            manager.getBatteryUsageStats(query)
        } catch (e: SecurityException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        stats.use {
            val dischargeDurationMs = it.dischargeDurationMs
            return if (dischargeDurationMs > 0L) {
                dischargeDurationMs
            } else {
                it.statsDuration.takeIf { durationMs -> durationMs > 0L }
            }
        }
    }

    private fun readScreenOffDuration(): Long? {
        val batteryStats = IBatteryStats.Stub.asInterface(
            ServiceManager.getService(BatteryStats.SERVICE_NAME)
        ) ?: return null
        return try {
            batteryStats.computeBatteryScreenOffRealtimeMs().coerceAtLeast(0L)
        } catch (e: RemoteException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private data class DeviceScreenDurations(
        val screenOnDurationMs: Long,
        val screenOffDurationMs: Long,
        val elapsedRealtimeMs: Long
    )
}
