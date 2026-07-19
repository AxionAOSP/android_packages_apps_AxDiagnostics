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

package com.axion.diagnostics.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.BatteryCollector
import com.axion.diagnostics.data.BatterySnapshot
import com.axion.diagnostics.data.DrainSummary
import com.axion.diagnostics.data.DrainTracker
import com.axion.diagnostics.data.MonitorBreakdown
import com.axion.diagnostics.data.ScreenStateStats
import com.axion.diagnostics.ui.components.BarChartEntry
import com.axion.diagnostics.ui.components.DiagnosticsColors
import com.axion.diagnostics.ui.components.MetricProgressCard
import com.axion.diagnostics.ui.components.SparklineChart
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.VerticalBarChart
import com.axion.diagnostics.ui.components.formatMs
import com.axion.diagnostics.ui.components.severityColor
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatteryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
    var breakdown by remember { mutableStateOf<MonitorBreakdown?>(null) }
    var summary by remember { mutableStateOf<DrainSummary?>(null) }
    var levelHistory by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedActiveEffect {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                DrainTracker.recordSample(context)
                BatteryUiSnapshot(
                    battery = BatteryCollector.collect(context),
                    breakdown = DrainTracker.getBreakdown(context),
                    summary = DrainTracker.getSummary()
                )
            }
            battery = snapshot.battery
            breakdown = snapshot.breakdown
            summary = snapshot.summary
            levelHistory = (levelHistory + snapshot.battery.levelPercent.toFloat()).takeLast(32)
            delay(2000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        battery?.let { b ->
            val charging = b.status == "Charging"
            val heroColor =
                if (charging) DiagnosticsColors.positive else MaterialTheme.colorScheme.onSurface
            val sign = if (charging) "+" else "-"

            MetricProgressCard(
                title = stringResource(R.string.label_battery),
                primary = "${b.levelPercent}%",
                secondary = b.status,
                percent = b.levelPercent.toFloat(),
                color = batteryLevelColor(b.levelPercent)
            )

            StatCard(stringResource(R.string.battery_level_history)) {
                SparklineChart(
                    values = levelHistory,
                    color = batteryLevelColor(b.levelPercent)
                )
            }

            StatCard(stringResource(R.string.drain_current_draw)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "$sign${abs(b.currentNowMa)} mA",
                            style = MaterialTheme.typography.displaySmallEmphasized,
                            fontWeight = FontWeight.Bold,
                            color = heroColor
                        )
                        Text(
                            if (charging) {
                                stringResource(R.string.battery_charging)
                            } else {
                                stringResource(R.string.battery_discharging)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${b.levelPercent}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "%.1f°C".format(b.temperatureC),
                            style = MaterialTheme.typography.bodyMedium,
                            color = severityColor(b.temperatureC, 38f, 42f)
                        )
                    }
                }
            }

            StatCard(stringResource(R.string.battery_health_title)) {
                StatRow(stringResource(R.string.label_status), b.status)
                StatRow(stringResource(R.string.label_health), b.health)
                StatRow(stringResource(R.string.label_plugged), b.plugged)
                StatRow(stringResource(R.string.label_technology), b.technology)
                StatRow(stringResource(R.string.label_voltage), "%.3f V".format(b.voltageV))
                StatRow(stringResource(R.string.label_current_avg), "${b.currentAvgMa} mA")
                if (b.capacityUah > 0) {
                    StatRow(stringResource(R.string.label_capacity), "${b.capacityUah / 1000} mAh")
                }
                if (b.chargeCounterUah != 0) {
                    StatRow(
                        stringResource(R.string.label_charge_counter),
                        "${b.chargeCounterUah / 1000} mAh"
                    )
                }
                if (b.cycleCount > 0) {
                    StatRow(stringResource(R.string.label_cycle_count), "${b.cycleCount}")
                }
            }
        }

        summary?.let { s ->
            StatCard(stringResource(R.string.battery_drain_windows)) {
                VerticalBarChart(
                    entries = listOf(
                        BarChartEntry(
                            stringResource(R.string.label_5min),
                            s.last5minDrain.drainPerHour,
                            severityColor(s.last5minDrain.drainPerHour, 5f, 15f)
                        ),
                        BarChartEntry(
                            stringResource(R.string.label_15min),
                            s.last15minDrain.drainPerHour,
                            severityColor(s.last15minDrain.drainPerHour, 5f, 15f)
                        ),
                        BarChartEntry(
                            stringResource(R.string.label_30min),
                            s.last30minDrain.drainPerHour,
                            severityColor(s.last30minDrain.drainPerHour, 5f, 15f)
                        ),
                        BarChartEntry(
                            stringResource(R.string.label_1hr),
                            s.last1hrDrain.drainPerHour,
                            severityColor(s.last1hrDrain.drainPerHour, 5f, 15f)
                        )
                    )
                )
            }
        }

        val bd = breakdown
        if (bd != null && (bd.screenOn.durationMs > 0 || bd.screenOff.durationMs > 0)) {
            ScreenStateCard(stringResource(R.string.battery_screen_on), bd.screenOn)
            ScreenStateCard(stringResource(R.string.battery_screen_off), bd.screenOff)
        } else {
            StatCard(stringResource(R.string.drain_screen_on_off)) {
                Text(
                    stringResource(R.string.battery_monitor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun batteryLevelColor(level: Int): Color = when {
    level < 15 -> MaterialTheme.colorScheme.error
    level < 35 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private data class BatteryUiSnapshot(
    val battery: BatterySnapshot,
    val breakdown: MonitorBreakdown?,
    val summary: DrainSummary?
)

@Composable
private fun ScreenStateCard(title: String, stats: ScreenStateStats) {
    StatCard(title) {
        StatRow(stringResource(R.string.label_time), formatMs(stats.durationMs))
        StatRow(stringResource(R.string.label_drained), "%.1f%%".format(stats.drainPercent))
        StatRow(stringResource(R.string.label_consumed), "%.0f mAh".format(stats.mahConsumed))
        StatRow(stringResource(R.string.label_avg_current), "%.0f mA".format(stats.avgCurrentMa))
        StatRow(
            stringResource(R.string.label_rate),
            "%.1f%%/hr".format(stats.drainPerHour),
            severityColor(
                stats.drainPerHour,
                if (stats.screenOn) 8f else 2f,
                if (stats.screenOn) 20f else 5f
            )
        )
    }
}
