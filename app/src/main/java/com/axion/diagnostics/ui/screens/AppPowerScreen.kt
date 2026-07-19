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

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.AppUsage
import com.axion.diagnostics.data.AppUsageCollector
import com.axion.diagnostics.data.ProcessType
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SectionHeader
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.formatKb
import com.axion.diagnostics.ui.components.severityColor
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AppPowerScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<AppUsage>>(emptyList()) }
    var expandedUid by remember { mutableStateOf(-1) }

    LaunchedActiveEffect {
        while (true) {
            apps = withContext(Dispatchers.IO) { AppUsageCollector.collect(context) }
            delay(3000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val activeApps = apps.filter { it.cpuPercent > 0.1f || it.rssKb > 10_000 }

        val totalCpu = apps.sumOf { it.cpuPercent.toDouble() }.toFloat()
        val totalRss = apps.sumOf { it.rssKb }

        StatCard(stringResource(R.string.apps_system_wide)) {
            StatRow(stringResource(R.string.label_cpu), "%.1f%%".format(totalCpu))
            StatRow(stringResource(R.string.label_memory), formatKb(totalRss))
            StatRow(stringResource(R.string.label_apps_tracked), "${apps.size}")
        }

        val cpuHogs = apps.filter { it.cpuPercent > 5f }
        if (cpuHogs.isNotEmpty()) {
            StatCard(stringResource(R.string.apps_cpu_hogs)) {
                cpuHogs.forEach { app ->
                    AppSummaryRow(app, expandedUid) { expandedUid = it }
                    if (expandedUid == app.uid) AppFullDetail(app)
                    HorizontalDivider()
                }
            }
        }

        StatCard(stringResource(R.string.apps_all)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.col_app),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(stringResource(R.string.col_type), style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(R.string.col_cpu_percent),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Text(
                    stringResource(R.string.col_mem),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Text(
                    stringResource(R.string.col_thr),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            HorizontalDivider()

            activeApps.take(30).forEach { app ->
                AppSummaryRow(app, expandedUid) { expandedUid = it }
                if (expandedUid == app.uid) AppFullDetail(app)
                HorizontalDivider()
            }
        }

        val risingCpu = apps.filter { it.cpuTrend > 20f && it.cpuPercent > 1f }
            .sortedByDescending { it.cpuTrend }
        if (risingCpu.isNotEmpty()) {
            StatCard(stringResource(R.string.apps_rising_cpu)) {
                risingCpu.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText(app.label.take(20))
                        MonoText("%.1f%%".format(app.cpuPercent))
                        MonoText(
                            "trend: %+.0f%%".format(app.cpuTrend),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        val risingMem = apps.filter { it.rssTrend > 15f && it.rssKb > 20_000 }
            .sortedByDescending { it.rssTrend }
        if (risingMem.isNotEmpty()) {
            StatCard(stringResource(R.string.apps_rising_mem)) {
                risingMem.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText(app.label.take(20))
                        MonoText(formatKb(app.rssKb))
                        MonoText(
                            "trend: %+.0f%%".format(app.rssTrend),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSummaryRow(app: AppUsage, expandedUid: Int, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(if (expandedUid == app.uid) -1 else app.uid) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label.take(22),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            MonoText(app.packageName.take(28))
        }
        TypeBadge(app.primaryType)
        MonoText("%.1f%%".format(app.cpuPercent), color = severityColor(app.cpuPercent, 10f, 30f))
        MonoText(formatKb(app.rssKb), modifier = Modifier.padding(start = 6.dp))
        MonoText("${app.threadCount}T", modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TypeBadge(type: ProcessType) {
    val (text, color) = when (type) {
        ProcessType.JAVA -> "JVM" to MaterialTheme.colorScheme.primary
        ProcessType.NATIVE -> "NDK" to MaterialTheme.colorScheme.tertiary
        ProcessType.SYSTEM -> "SYS" to MaterialTheme.colorScheme.secondary
        ProcessType.UNKNOWN -> "???" to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun AppFullDetail(app: AppUsage) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val cpuTrendColor = if (app.cpuTrend > 20f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val rssTrendColor = if (app.rssTrend > 15f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            StatRow(stringResource(R.string.label_uid), "${app.uid}")
            StatRow(stringResource(R.string.label_cpu), "%.2f%%".format(app.cpuPercent))
            StatRow(stringResource(R.string.label_cpu_time), "${app.cpuTimeMs / 1000}s")
            StatRow(stringResource(R.string.col_rss), formatKb(app.rssKb))
            if (app.pssKb > 0) StatRow(stringResource(R.string.label_pss), formatKb(app.pssKb))
            StatRow(stringResource(R.string.label_processes), "${app.processCount}")
            StatRow(stringResource(R.string.label_threads), "${app.threadCount}")
            StatRow(
                stringResource(R.string.label_cpu_trend),
                "%+.1f%%".format(app.cpuTrend),
                cpuTrendColor
            )
            StatRow(
                stringResource(R.string.label_rss_trend),
                "%+.1f%%".format(app.rssTrend),
                rssTrendColor
            )

            if (app.processes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SectionHeader(stringResource(R.string.section_processes))
                app.processes.forEach { proc ->
                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                TypeBadge(proc.type)
                                MonoText("${proc.name.take(18)} (${proc.pid})")
                            }
                            MonoText("%.1f%%".format(proc.cpuPercent))
                            MonoText(
                                formatKb(proc.rssKb),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            MonoText("${proc.threads}T", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
