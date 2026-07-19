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

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.DrainSummary
import com.axion.diagnostics.data.DrainTracker
import com.axion.diagnostics.data.DrainWindow
import com.axion.diagnostics.export.ReportFiles
import com.axion.diagnostics.ui.components.DiagnosticsColors
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.formatMs
import com.axion.diagnostics.ui.components.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DrainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTracking by remember { mutableStateOf(DrainTracker.isTracking) }
    var summary by remember { mutableStateOf<DrainSummary?>(null) }

    LaunchedActiveEffect(isTracking) {
        if (!isTracking) return@LaunchedActiveEffect
        while (isTracking) {
            withContext(Dispatchers.IO) { DrainTracker.recordSample(context) }
            summary = DrainTracker.getSummary()
            delay(5000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(stringResource(R.string.drain_monitor_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    val statusColor = if (isTracking) {
                        DiagnosticsColors.positive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val statusText = if (isTracking) {
                        stringResource(R.string.status_recording)
                    } else {
                        stringResource(R.string.status_stopped)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                    summary?.let { s ->
                        val screenState = if (s.isScreenOn) {
                            stringResource(R.string.screen_on)
                        } else {
                            stringResource(R.string.screen_off)
                        }
                        Text(
                            "${s.currentLevel}% | ${s.currentMa} mA | $screenState",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTracking) {
                        Button(
                            onClick = { DrainTracker.stopTracking(); isTracking = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        Button(
                            onClick = { DrainTracker.startTracking(); isTracking = true }
                        ) { Text(stringResource(R.string.action_start)) }
                    }
                }
            }
            if (summary != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            ReportFiles.write(context, "drain", DrainTracker.exportDrainLog())
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_saved, path),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text(stringResource(R.string.action_export_log)) }
            }
        }

        summary?.let { s ->
            StatCard(stringResource(R.string.drain_current_draw)) {
                StatRow(stringResource(R.string.label_battery_level), "${s.currentLevel}%")
                StatRow(stringResource(R.string.label_instant_current), "${s.currentMa} mA")
                StatRow(
                    stringResource(R.string.label_charging),
                    if (s.isCharging) {
                        stringResource(
                            R.string.common_yes
                        )
                    } else {
                        stringResource(R.string.common_no)
                    }
                )
                StatRow(
                    stringResource(R.string.label_screen),
                    if (s.isScreenOn) {
                        stringResource(
                            R.string.common_on
                        )
                    } else {
                        stringResource(R.string.common_off)
                    }
                )
                if (s.instantDrainPerHour > 0 && !s.isCharging) {
                    StatRow(
                        stringResource(R.string.label_est_drain_rate),
                        "%.1f%%/hr".format(s.instantDrainPerHour),
                        severityColor(s.instantDrainPerHour, 5f, 15f)
                    )
                }
            }

            StatCard(stringResource(R.string.drain_rate_over_time)) {
                DrainWindowRow(s.last5minDrain)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DrainWindowRow(s.last15minDrain)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DrainWindowRow(s.last30minDrain)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DrainWindowRow(s.last1hrDrain)
            }

            if (s.screenOnDrainPerHour > 0 || s.screenOffDrainPerHour > 0) {
                StatCard(stringResource(R.string.drain_screen_on_off)) {
                    StatRow(
                        stringResource(R.string.label_screen_on_drain),
                        "%.1f%%/hr".format(s.screenOnDrainPerHour),
                        severityColor(s.screenOnDrainPerHour, 8f, 20f)
                    )
                    StatRow(
                        stringResource(R.string.label_screen_off_drain),
                        "%.1f%%/hr".format(s.screenOffDrainPerHour),
                        severityColor(s.screenOffDrainPerHour, 2f, 5f)
                    )
                }
            }

            if (s.hourlyBreakdown.isNotEmpty()) {
                StatCard(stringResource(R.string.drain_hourly)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.col_hour),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            stringResource(R.string.col_drain),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            stringResource(R.string.col_avg_ma),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            stringResource(R.string.col_scr_on),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    HorizontalDivider()
                    s.hourlyBreakdown.forEach { h ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoText(h.hour)
                            MonoText(
                                "%.1f%%".format(h.drainPercent),
                                color = severityColor(h.drainPercent, 5f, 15f)
                            )
                            MonoText("%.0f".format(h.avgCurrentMa))
                            MonoText("%.0f%%".format(h.screenOnPercent))
                        }
                    }
                }
            }

            if (s.samples.size > 10) {
                StatCard(stringResource(R.string.drain_current_graph)) {
                    CurrentGraph(
                        samples = s.samples.map { it.currentMa.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText("${s.samples.size} samples")
                        val elapsed = s.samples.last().timestamp - s.samples.first().timestamp
                        MonoText(formatMs(elapsed))
                    }
                }
            }
        }

        if (!isTracking && summary == null) {
            StatCard(stringResource(R.string.how_to_use)) {
                Text(
                    stringResource(R.string.drain_how_to_use_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DrainWindowRow(window: DrainWindow) {
    if (window.sampleCount < 2) {
        StatRow(window.label, stringResource(R.string.common_collecting))
        return
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                window.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            MonoText(
                "%.1f%%/hr".format(window.drainPerHour),
                color = severityColor(window.drainPerHour, 5f, 15f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MonoText("Drain: %.1f%%".format(window.drainPercent))
            MonoText("Avg: %.0f mA".format(window.avgCurrentMa))
            MonoText("Scr: %.0f%%".format(window.screenOnPercent))
        }
    }
}

@Composable
private fun CurrentGraph(samples: List<Float>, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val maxVal = samples.maxOrNull()?.let { kotlin.math.abs(it) }?.coerceAtLeast(100f) ?: 100f
        val minVal = 0f

        drawLine(surfaceVariant, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)

        val path = Path()
        samples.forEachIndexed { i, value ->
            val x = (i.toFloat() / (samples.size - 1)) * w
            val normalized = (kotlin.math.abs(value) - minVal) / (maxVal - minVal)
            val y = h - (normalized * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primaryColor, style = Stroke(width = 2f))
    }
}
