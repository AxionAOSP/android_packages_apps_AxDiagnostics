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

package com.axion.diagnostics.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.axion.diagnostics.DiagnosticsActivity
import com.axion.diagnostics.R
import com.axion.diagnostics.data.DrainSummary
import com.axion.diagnostics.data.DrainTracker
import com.axion.diagnostics.data.MonitorBreakdown
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MonitorService : Service() {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var notificationRefreshTask: ScheduledFuture<*>? = null
    private var receiverRegistered = false
    private var thermalRegistered = false
    @Volatile private var destroyed = false

    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (destroyed) return
            try {
                executor.execute {
                    if (!destroyed) handleMonitorAction(action, intent)
                }
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "Dropped monitor event after shutdown: $action", e)
            }
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener {
        if (!destroyed) recordBatterySnapshot()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")
        val enabled = Settings.Secure.getInt(contentResolver, DiagnosticsActivity.SETTING_KEY, 0)
        if (enabled == 0) {
            Log.w(TAG, "Diagnostics not enabled, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        destroyed = false

        startForeground(
            NotificationHelper.NOTIFICATION_SERVICE_ID,
            NotificationHelper.buildServiceNotification(
                this,
                getString(R.string.monitor_service_title),
                getString(R.string.monitor_service_starting)
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        Log.i(TAG, "Foreground started, registering event monitor")

        if (!DrainTracker.isTracking) DrainTracker.startTracking()
        registerMonitorReceiver()
        registerThermalListener()
        scheduleNotificationRefresh()
        executor.execute { recordBatterySnapshot() }

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        notificationRefreshTask?.cancel(false)
        notificationRefreshTask = null
        unregisterThermalListener()
        unregisterMonitorReceiver()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerMonitorReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(monitorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterMonitorReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(monitorReceiver) }
        receiverRegistered = false
    }

    private fun registerThermalListener() {
        if (thermalRegistered) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        powerManager.addThermalStatusListener(executor, thermalListener)
        thermalRegistered = true
    }

    private fun unregisterThermalListener() {
        if (!thermalRegistered) return
        runCatching {
            getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener)
        }
        thermalRegistered = false
    }

    private fun handleMonitorAction(action: String, intent: Intent) {
        try {
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> recordBatteryEvent(intent)
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF -> recordBatterySnapshot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Monitor event failed: $action", e)
        }
    }

    private fun recordBatteryEvent(intent: Intent) {
        DrainTracker.recordBatteryEvent(this, intent)
        updateServiceNotification()
    }

    private fun recordBatterySnapshot() {
        DrainTracker.recordSample(this)
        updateServiceNotification()
    }

    private fun scheduleNotificationRefresh() {
        notificationRefreshTask?.cancel(false)
        notificationRefreshTask = executor.scheduleWithFixedDelay(
            {
                try {
                    if (!destroyed) {
                        updateServiceNotification()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Notification refresh failed", e)
                }
            },
            NOTIFICATION_REFRESH_INTERVAL_MS,
            NOTIFICATION_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun updateServiceNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val showStats = Settings.Secure.getInt(contentResolver, "ax_diagnostics_notification_enabled", 1) == 1
        val details = if (showStats) {
            buildServiceDetails(DrainTracker.getSummary())
        } else {
            getString(R.string.monitor_service_active_minimal)
        }
        nm.notify(
            NotificationHelper.NOTIFICATION_SERVICE_ID,
            NotificationHelper.buildServiceNotification(
                this,
                getString(R.string.monitor_service_title),
                details
            )
        )
    }

    private fun buildServiceDetails(summary: DrainSummary?): String {
        summary ?: return getString(R.string.monitor_service_estimating)

        val breakdown = DrainTracker.getBreakdown(this)
        val currentMa = signedCurrentMa(summary.currentMa, summary.isCharging)
        val averageMa = averageCurrentMa(summary)
        val drainPerHour = estimateDrainPerHour(summary)
        val temperatureC = summary.samples.lastOrNull()?.temperatureC ?: 0f
        val status = if (summary.isCharging) {
            getString(R.string.battery_charging)
        } else {
            getString(R.string.battery_discharging)
        }

        val lines = mutableListOf(
            getString(
                R.string.monitor_service_now,
                formatSignedCurrent(currentMa),
                formatRemainingTime(summary.currentLevel, drainPerHour, summary.isCharging)
            ),
            getString(
                R.string.monitor_service_average,
                formatSignedCurrent(averageMa),
                formatDrainRate(drainPerHour),
                formatTotalMah(totalConsumedMah(breakdown, summary.isCharging))
            ),
            getString(
                R.string.monitor_service_status,
                summary.currentLevel,
                formatTemperature(temperatureC),
                status,
                formatAbsoluteCurrent(currentMa)
            ),
            getString(
                R.string.monitor_service_drain,
                formatDrainRate(summary.screenOnDrainPerHour),
                formatDrainRate(summary.screenOffDrainPerHour)
            )
        )
        if (breakdown != null) {
            val screenBaseMs = (
                breakdown.deviceScreenOnDurationMs + breakdown.deviceScreenOffDurationMs
            ).coerceAtLeast(1L)
            val sleepBaseMs = (
                breakdown.deepSleepDurationMs + breakdown.awakeDurationMs
            ).coerceAtLeast(1L)
            if (breakdown.hasDeviceScreenDurations) {
                lines.add(
                    getString(
                        R.string.monitor_service_screen_on,
                        formatDuration(breakdown.deviceScreenOnDurationMs),
                        formatPercent(breakdown.deviceScreenOnDurationMs, screenBaseMs)
                    )
                )
                lines.add(
                    getString(
                        R.string.monitor_service_screen_off,
                        formatDuration(breakdown.deviceScreenOffDurationMs),
                        formatPercent(breakdown.deviceScreenOffDurationMs, screenBaseMs)
                    )
                )
            }
            lines.add(
                getString(
                    R.string.monitor_service_deep_sleep,
                    formatDuration(breakdown.deepSleepDurationMs),
                    formatPercent(breakdown.deepSleepDurationMs, sleepBaseMs)
                )
            )
            lines.add(
                getString(
                    R.string.monitor_service_awake,
                    formatDuration(breakdown.awakeDurationMs),
                    formatPercent(breakdown.awakeDurationMs, sleepBaseMs)
                )
            )
        }
        return lines.joinToString("\n")
    }

    private fun signedCurrentMa(currentMa: Int, charging: Boolean): Int {
        val value = abs(currentMa)
        return if (charging) value else -value
    }

    private fun averageCurrentMa(summary: DrainSummary): Int {
        val samples = summary.samples.takeLast(5)
        if (samples.isEmpty()) return signedCurrentMa(summary.currentMa, summary.isCharging)
        val average = samples
            .map { if (it.currentAvgMa != 0) it.currentAvgMa else it.currentMa }
            .average()
            .roundToInt()
        return signedCurrentMa(average, summary.isCharging)
    }

    private fun estimateDrainPerHour(summary: DrainSummary): Float {
        return listOf(
            summary.last5minDrain.drainPerHour,
            summary.last15minDrain.drainPerHour,
            summary.last30minDrain.drainPerHour,
            summary.last1hrDrain.drainPerHour,
            summary.instantDrainPerHour
        ).firstOrNull { it > 0f } ?: 0f
    }

    private fun totalConsumedMah(
        breakdown: MonitorBreakdown?,
        charging: Boolean
    ): Float {
        val consumed = (
            (breakdown?.screenOn?.mahConsumed ?: 0f) +
                (breakdown?.screenOff?.mahConsumed ?: 0f)
        )
        if (consumed == 0f) return 0f
        return if (charging) consumed else -consumed
    }

    private fun formatRemainingTime(
        level: Int,
        drainPerHour: Float,
        charging: Boolean
    ): String {
        if (charging) return getString(R.string.battery_charging)
        if (drainPerHour <= 0f) return getString(R.string.monitor_service_estimating_short)
        val remainingMs = (level / drainPerHour * 3_600_000L).toLong().coerceAtLeast(0L)
        return getString(R.string.monitor_service_time_left, formatDuration(remainingMs))
    }

    private fun formatSignedCurrent(currentMa: Int): String {
        if (currentMa == 0) return formatAbsoluteCurrent(0)
        return getString(R.string.monitor_value_current_signed, currentMa)
    }

    private fun formatAbsoluteCurrent(currentMa: Int): String {
        return getString(R.string.monitor_value_current, abs(currentMa))
    }

    private fun formatDrainRate(rate: Float): String {
        return getString(R.string.monitor_value_drain_rate, rate.coerceAtLeast(0f))
    }

    private fun formatTemperature(temperatureC: Float): String {
        return getString(R.string.monitor_value_temperature, temperatureC)
    }

    private fun formatTotalMah(mah: Float): String {
        val value = if (abs(mah) < 0.5f) 0f else mah
        return getString(R.string.monitor_value_total_mah, value)
    }

    private fun formatPercent(durationMs: Long, totalMs: Long): String {
        val percent = durationMs.coerceAtLeast(0L) * 100f / totalMs.coerceAtLeast(1L)
        return getString(R.string.monitor_value_percent, percent)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> getString(R.string.monitor_duration_hours_minutes, hours, minutes)
            minutes > 0L -> getString(R.string.monitor_duration_minutes_seconds, minutes, seconds)
            else -> getString(R.string.monitor_duration_seconds, seconds)
        }
    }

    companion object {
        private const val TAG = "AxDiagMonitor"
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 10_000L

        @Volatile var isRunning = false
            internal set

        fun setComponentEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, MonitorService::class.java)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
