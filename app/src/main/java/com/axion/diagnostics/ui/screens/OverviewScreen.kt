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

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.axion.diagnostics.data.HealthAnalyzer
import com.axion.diagnostics.data.HealthReport
import com.axion.diagnostics.data.InsightSeverity
import com.axion.diagnostics.ui.components.DeviceMetricRow
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.ScoreRing
import com.axion.diagnostics.ui.components.SparklineChart
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.severityColor
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun OverviewScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var cpuHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var memHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var storageHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var batteryHistory by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedActiveEffect {
        while (true) {
            try {
                val nextReport = withContext(Dispatchers.IO) { HealthAnalyzer.analyze(context) }
                report = nextReport
                cpuHistory = appendHistory(cpuHistory, nextReport.cpuUsage)
                memHistory = appendHistory(memHistory, nextReport.memUsage)
                storageHistory = appendHistory(storageHistory, nextReport.storageUsage)
                batteryHistory = appendHistory(batteryHistory, nextReport.batteryLevel.toFloat())
            } catch (e: Exception) {
            }
            delay(3000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        report?.let { r ->
            val criticalInsights = r.insights.filter { it.severity == InsightSeverity.CRITICAL }
            val warningInsights = r.insights.filter { it.severity == InsightSeverity.WARN }
            val infoInsights = r.insights.filter { it.severity == InsightSeverity.INFO }

            HealthScoreCard(r)

            StatCard(stringResource(R.string.overview_monitoring_overhead)) {
                Text(
                    stringResource(R.string.overview_overhead_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (criticalInsights.isNotEmpty()) {
                StatCard(stringResource(R.string.overview_issues_found)) {
                    criticalInsights.forEach { insight ->
                        InsightRow(
                            insight.category,
                            insight.title,
                            insight.detail,
                            insight.metric,
                            InsightSeverity.CRITICAL,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            if (warningInsights.isNotEmpty()) {
                StatCard(stringResource(R.string.overview_warnings)) {
                    warningInsights.forEach { insight ->
                        InsightRow(
                            insight.category,
                            insight.title,
                            insight.detail,
                            insight.metric,
                            InsightSeverity.WARN,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }

            StatCard(stringResource(R.string.overview_quick_stats)) {
                DeviceMetricRow(
                    label = stringResource(R.string.label_cpu),
                    value = "%.0f%%".format(r.cpuUsage),
                    detail = r.topCpuProcess,
                    percent = r.cpuUsage,
                )
                HorizontalDivider()
                DeviceMetricRow(
                    label = stringResource(R.string.label_memory),
                    value = "%.0f%%".format(r.memUsage),
                    detail = r.topMemProcess,
                    percent = r.memUsage,
                )
                HorizontalDivider()
                DeviceMetricRow(
                    label = stringResource(R.string.label_storage),
                    value = "%.0f%%".format(r.storageUsage),
                    detail = r.storageStatus,
                    percent = r.storageUsage,
                )
                HorizontalDivider()
                DeviceMetricRow(
                    label = stringResource(R.string.label_battery),
                    value = "${r.batteryLevel}%",
                    detail = r.batteryStatus,
                    percent = r.batteryLevel.toFloat(),
                    color = batteryColor(r.batteryLevel),
                )
                Spacer(Modifier.height(8.dp))
                StatRow(stringResource(R.string.label_top_cpu), r.topCpuProcess)
                StatRow(stringResource(R.string.label_top_memory), r.topMemProcess)
                StatRow(stringResource(R.string.label_thermal), r.thermalStatus)
                StatRow(stringResource(R.string.label_io), r.ioStatus)
                if (r.drainRate > 0) {
                    StatRow(
                        stringResource(R.string.label_drain_rate),
                        "%.1f%%/hr".format(r.drainRate),
                        severityColor(r.drainRate, 5f, 10f),
                    )
                }
            }

            StatCard(stringResource(R.string.overview_recent_trends)) {
                TrendChart(
                    stringResource(R.string.label_cpu),
                    cpuHistory,
                    MaterialTheme.colorScheme.primary,
                )
                TrendChart(
                    stringResource(R.string.label_memory),
                    memHistory,
                    MaterialTheme.colorScheme.secondary,
                )
                TrendChart(
                    stringResource(R.string.label_storage),
                    storageHistory,
                    MaterialTheme.colorScheme.tertiary,
                )
                TrendChart(
                    stringResource(R.string.label_battery),
                    batteryHistory,
                    batteryColor(r.batteryLevel),
                )
            }

            StatCard(stringResource(R.string.overview_system)) {
                StatRow(stringResource(R.string.label_device), Build.MODEL)
                StatRow(
                    stringResource(R.string.label_android),
                    "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                )
                StatRow(stringResource(R.string.label_build), Build.DISPLAY)
                StatRow(
                    stringResource(R.string.label_kernel),
                    System.getProperty("os.version") ?: "unknown",
                )
                val uptime = SystemClock.elapsedRealtime()
                StatRow(
                    stringResource(R.string.label_uptime),
                    "${uptime / 3_600_000}h ${(uptime % 3_600_000) / 60_000}m",
                )
            }

            if (infoInsights.isNotEmpty()) {
                StatCard(stringResource(R.string.overview_info)) {
                    infoInsights.forEach { insight ->
                        InsightRow(
                            insight.category,
                            insight.title,
                            insight.detail,
                            insight.metric,
                            InsightSeverity.INFO,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthScoreCard(report: HealthReport) {
    val bgColor = when {
        report.overallScore >= 90 -> MaterialTheme.colorScheme.primaryContainer
        report.overallScore >= 70 -> MaterialTheme.colorScheme.secondaryContainer
        report.overallScore >= 40 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }

    val onBgColor = when {
        report.overallScore >= 90 -> MaterialTheme.colorScheme.onPrimaryContainer
        report.overallScore >= 70 -> MaterialTheme.colorScheme.onSecondaryContainer
        report.overallScore >= 40 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = bgColor,
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    report.overallLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onBgColor,
                )
                val critCount = report.insights.count { it.severity == InsightSeverity.CRITICAL }
                val warnCount = report.insights.count { it.severity == InsightSeverity.WARN }
                Text(
                    when {
                        critCount > 0 ->
                            "$critCount critical issue${
                                if (critCount > 1) "s" else ""
                            }"

                        warnCount > 0 -> "$warnCount warning${if (warnCount > 1) "s" else ""}"

                        else -> stringResource(R.string.overview_no_issues)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBgColor.copy(alpha = 0.8f),
                )
                Text(
                    stringResource(R.string.overview_status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = onBgColor.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ScoreRing(
                score = report.overallScore,
                label = stringResource(R.string.overview_score),
                color = scoreColor(report.overallScore),
                trackColor = onBgColor.copy(alpha = 0.22f),
                contentColor = onBgColor,
            )
        }
    }
}

@Composable
private fun InsightRow(category: String, title: String, detail: String, metric: String, severity: InsightSeverity) {
    val tagColor = when (severity) {
        InsightSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        InsightSeverity.WARN -> MaterialTheme.colorScheme.tertiary
        InsightSeverity.INFO -> MaterialTheme.colorScheme.primary
        InsightSeverity.OK -> MaterialTheme.colorScheme.primary
    }

    val tagBg = when (severity) {
        InsightSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        InsightSeverity.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        InsightSeverity.INFO -> MaterialTheme.colorScheme.primaryContainer
        InsightSeverity.OK -> MaterialTheme.colorScheme.primaryContainer
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(color = tagBg, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelSmall,
                        color = tagColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MonoText(metric, color = tagColor)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun TrendChart(label: String, values: List<Float>, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    SparklineChart(
        values = values,
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun appendHistory(history: List<Float>, value: Float): List<Float> = (history + value).takeLast(32)

@Composable
private fun scoreColor(score: Int): Color = when {
    score >= 80 -> MaterialTheme.colorScheme.primary
    score >= 50 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun batteryColor(level: Int): Color = when {
    level < 15 -> MaterialTheme.colorScheme.error
    level < 35 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
