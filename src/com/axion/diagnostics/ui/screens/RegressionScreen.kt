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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.ProcessCollector
import com.axion.diagnostics.data.ProcessHistory
import com.axion.diagnostics.data.RegressionAlert
import com.axion.diagnostics.data.RegressionTracker
import com.axion.diagnostics.data.RegressionType
import com.axion.diagnostics.data.Severity
import com.axion.diagnostics.ui.components.DiagnosticsColors
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SectionHeader
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.formatKb
import com.axion.diagnostics.ui.components.formatMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RegressionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTracking by remember { mutableStateOf(RegressionTracker.isTracking) }
    var sampleCount by remember { mutableIntStateOf(RegressionTracker.sampleCount) }
    var alerts by remember { mutableStateOf<List<RegressionAlert>>(emptyList()) }
    var topProcesses by remember { mutableStateOf<List<ProcessHistory>>(emptyList()) }
    var selectedProcess by remember { mutableStateOf<ProcessHistory?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(isTracking) {
        if (!isTracking) return@LaunchedEffect
        while (isTracking) {
            val procs = withContext(Dispatchers.IO) { ProcessCollector.collect(50) }
            RegressionTracker.recordSample(procs)
            sampleCount = RegressionTracker.sampleCount
            elapsedMs = System.currentTimeMillis() - RegressionTracker.trackingStartTime

            alerts = RegressionTracker.activeAlerts.toList()

            topProcesses = RegressionTracker.processHistories.values
                .filter { it.samples.size >= 3 }
                .sortedByDescending { it.peakCpu }
                .take(20)
                .toList()

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
        StatCard(stringResource(R.string.regress_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    if (isTracking) {
                        Text(
                            stringResource(R.string.status_recording),
                            style = MaterialTheme.typography.titleMedium,
                            color = DiagnosticsColors.positive
                        )
                        Text(
                            "Samples: $sampleCount | Elapsed: ${formatMs(elapsedMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.status_stopped),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTracking) {
                        Button(
                            onClick = {
                                RegressionTracker.stopTracking()
                                isTracking = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        Button(onClick = {
                            RegressionTracker.startTracking()
                            isTracking = true
                        }) { Text(stringResource(R.string.action_start)) }
                    }
                }
            }

            if (sampleCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                RegressionTracker.saveReport(context)
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_saved, path),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }) { Text(stringResource(R.string.action_export)) }

                    OutlinedButton(onClick = {
                        RegressionTracker.clearHistory()
                        alerts = emptyList()
                        topProcesses = emptyList()
                        selectedProcess = null
                        sampleCount = 0
                    }) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }

        if (alerts.isNotEmpty()) {
            StatCard("${stringResource(R.string.regress_alerts)} (${alerts.size})") {
                alerts.take(20).forEach { alert ->
                    AlertRow(alert)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }

        if (topProcesses.isNotEmpty()) {
            StatCard(stringResource(R.string.regress_tracked)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.col_process),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.col_avg),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp)
                    )
                    Text(
                        stringResource(R.string.col_peak),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp)
                    )
                    Text(
                        stringResource(R.string.col_trend),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp)
                    )
                    Text(
                        stringResource(R.string.col_rss),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(56.dp)
                    )
                }
                HorizontalDivider()

                topProcesses.forEach { proc ->
                    val cpuTrendColor = when {
                        proc.cpuTrend > 50f -> MaterialTheme.colorScheme.error
                        proc.cpuTrend > 20f -> MaterialTheme.colorScheme.tertiary
                        proc.cpuTrend < -20f -> DiagnosticsColors.positive
                        else -> Color.Unspecified
                    }
                    val rssTrendColor = when {
                        proc.rssTrend > 30f -> MaterialTheme.colorScheme.error
                        proc.rssTrend > 10f -> MaterialTheme.colorScheme.tertiary
                        else -> Color.Unspecified
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProcess = if (selectedProcess?.pid == proc.pid) {
                                    null
                                } else {
                                    proc
                                }
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(proc.name.take(18))
                        MonoText("%.1f".format(proc.avgCpu))
                        MonoText("%.1f".format(proc.peakCpu))
                        MonoText(
                            "%+.0f%%".format(proc.cpuTrend),
                            color = cpuTrendColor
                        )
                        MonoText(formatKb(proc.currentRss), color = rssTrendColor)
                    }

                    if (selectedProcess?.pid == proc.pid) {
                        ProcessDetailCard(proc)
                    }
                }
            }
        }

        if (!isTracking && sampleCount == 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.regress_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.regress_intro_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: RegressionAlert) {
    val severityColor = when (alert.severity) {
        Severity.CRITICAL -> MaterialTheme.colorScheme.error
        Severity.HIGH -> MaterialTheme.colorScheme.error
        Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        Severity.LOW -> MaterialTheme.colorScheme.secondary
    }
    val typeIcon = when (alert.type) {
        RegressionType.CPU_SPIKE -> "CPU"
        RegressionType.CPU_SUSTAINED -> "CPU"
        RegressionType.MEMORY_LEAK -> "MEM"
        RegressionType.MEMORY_SPIKE -> "MEM"
        RegressionType.THREAD_GROWTH -> "THR"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = severityColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                typeIcon,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = severityColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                alert.processName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                alert.message,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            alert.severity.name,
            style = MaterialTheme.typography.labelSmall,
            color = severityColor
        )
    }
}

@Composable
private fun ProcessDetailCard(history: ProcessHistory) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${history.name} (PID: ${history.pid})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))

            StatRow(stringResource(R.string.label_samples), "${history.samples.size}")
            StatRow(stringResource(R.string.label_current_cpu), "%.1f%%".format(history.currentCpu))
            StatRow(stringResource(R.string.label_avg_cpu), "%.1f%%".format(history.avgCpu))
            StatRow(stringResource(R.string.label_peak_cpu), "%.1f%%".format(history.peakCpu))
            StatRow(
                stringResource(R.string.label_cpu_trend),
                "%+.1f%%".format(history.cpuTrend),
                when {
                    history.cpuTrend > 50f -> MaterialTheme.colorScheme.error
                    history.cpuTrend > 20f -> MaterialTheme.colorScheme.tertiary
                    history.cpuTrend < -20f -> DiagnosticsColors.positive
                    else -> Color.Unspecified
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            StatRow(stringResource(R.string.label_current_rss), formatKb(history.currentRss))
            StatRow(stringResource(R.string.label_avg_rss), formatKb(history.avgRss))
            StatRow(stringResource(R.string.label_peak_rss), formatKb(history.peakRss))
            StatRow(
                stringResource(R.string.label_rss_trend),
                "%+.1f%%".format(history.rssTrend),
                when {
                    history.rssTrend > 30f -> MaterialTheme.colorScheme.error
                    history.rssTrend > 10f -> MaterialTheme.colorScheme.tertiary
                    else -> Color.Unspecified
                }
            )

            if (history.samples.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                StatRow(
                    stringResource(R.string.label_threads_current),
                    "${history.samples.last().threads}"
                )
                StatRow(
                    stringResource(R.string.label_threads_first),
                    "${history.samples.first().threads}"
                )
            }

            if (history.alerts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeader("${stringResource(R.string.regress_alerts)} (${history.alerts.size})")
                history.alerts.takeLast(5).forEach { alert ->
                    MonoText("[${alert.severity}] ${alert.message}")
                }
            }
        }
    }
}
