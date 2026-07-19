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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.SystemServerAnalyzer
import com.axion.diagnostics.data.SystemServerSnapshot
import com.axion.diagnostics.export.ReportFiles
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SectionHeader
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.UsageBar
import com.axion.diagnostics.ui.components.formatKb
import com.axion.diagnostics.ui.components.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SystemServerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SystemServerSnapshot?>(null) }
    var expandedService by remember { mutableStateOf<String?>(null) }

    LaunchedActiveEffect {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { SystemServerAnalyzer.collect() }
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
        snapshot?.let { ss ->
            if (ss.pid <= 0) {
                StatCard("system_server") {
                    Text(
                        stringResource(R.string.ss_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                return@let
            }

            StatCard("system_server (PID: ${ss.pid})") {
                UsageBar(stringResource(R.string.label_total_cpu), ss.totalCpuPercent)
                StatRow(stringResource(R.string.col_rss), formatKb(ss.totalRssKb))
                StatRow(stringResource(R.string.label_pss_short), formatKb(ss.totalPssKb))
                StatRow(stringResource(R.string.label_threads), "${ss.totalThreads}")

                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            ReportFiles.write(
                                context,
                                "system_server",
                                SystemServerAnalyzer.exportReport()
                            )
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_saved, path),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text(stringResource(R.string.action_export_report)) }
            }

            StatCard(stringResource(R.string.ss_pressure)) {
                SectionHeader(stringResource(R.string.section_cpu_pressure))
                StatRow(
                    stringResource(R.string.label_some_10),
                    "%.1f%%".format(ss.cpuPressure.someAvg10),
                    severityColor(ss.cpuPressure.someAvg10, 20f, 50f)
                )
                StatRow(
                    stringResource(R.string.label_some_60),
                    "%.1f%%".format(ss.cpuPressure.someAvg60)
                )
                StatRow(
                    stringResource(R.string.label_some_300),
                    "%.1f%%".format(ss.cpuPressure.someAvg300)
                )

                SectionHeader(stringResource(R.string.section_mem_pressure))
                StatRow(
                    stringResource(R.string.label_some_10),
                    "%.1f%%".format(ss.memPressure.someAvg10),
                    severityColor(ss.memPressure.someAvg10, 10f, 30f)
                )
                StatRow(
                    stringResource(R.string.label_some_60),
                    "%.1f%%".format(ss.memPressure.someAvg60)
                )
                StatRow(
                    stringResource(R.string.label_full_10),
                    "%.1f%%".format(ss.memPressure.fullAvg10),
                    severityColor(ss.memPressure.fullAvg10, 5f, 20f)
                )
            }

            ss.binderStats?.let { binder ->
                StatCard(stringResource(R.string.ss_binder)) {
                    StatRow(
                        stringResource(R.string.label_transactions_sent),
                        "%,d".format(binder.transactionsSent)
                    )
                    StatRow(
                        stringResource(R.string.label_transactions_received),
                        "%,d".format(binder.transactionsReceived)
                    )
                    StatRow(stringResource(R.string.label_pending), "${binder.pendingTransactions}")
                    StatRow(stringResource(R.string.label_ready_threads), "${binder.readyThreads}")
                    if (binder.freeAsyncSpace > 0) {
                        StatRow(
                            stringResource(R.string.label_free_async),
                            "${binder.freeAsyncSpace / 1024} KB"
                        )
                    }
                }
            }

            val activeServices = ss.topServices.filter { it.cpuPercent > 0.1f }
            if (activeServices.isNotEmpty()) {
                StatCard(stringResource(R.string.ss_service_breakdown)) {
                    Text(
                        stringResource(R.string.ss_service_breakdown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    activeServices.forEach { svc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedService = if (expandedService == svc.serviceName) {
                                        null
                                    } else {
                                        svc.serviceName
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    svc.serviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                MonoText(
                                    "%.1f%%".format(svc.cpuPercent),
                                    color = severityColor(svc.cpuPercent, 5f, 15f)
                                )
                                MonoText("  ${svc.threadCount}T")
                            }

                            if (expandedService == svc.serviceName) {
                                Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                                    svc.threads.filter { it.cpuPercent > 0.05f }.forEach { thread ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(
                                                vertical = 1.dp
                                            ),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            MonoText("${thread.name.take(22)} (${thread.tid})")
                                            MonoText("[${thread.state}]")
                                            MonoText(
                                                "%.1f%%".format(thread.cpuPercent),
                                                color = severityColor(thread.cpuPercent, 3f, 10f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            val hotThreads = ss.threads.filter { it.cpuPercent > 0.5f }.take(20)
            if (hotThreads.isNotEmpty()) {
                StatCard(stringResource(R.string.ss_hot_threads)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.col_thread),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(R.string.col_tid),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            stringResource(R.string.col_cpu_percent),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Text(
                            stringResource(R.string.col_service),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider()

                    hotThreads.forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoText(t.name.take(18))
                            MonoText("${t.tid}")
                            MonoText(
                                "%.1f%%".format(t.cpuPercent),
                                color = severityColor(t.cpuPercent, 3f, 10f)
                            )
                            MonoText(t.service.take(12))
                        }
                    }
                }
            }

            val gcThreads = ss.threads.filter {
                when (it.service) {
                    "GC/Finalizer",
                    "GC/HeapTask",
                    "GC/RefQueue" -> true
                    else -> false
                }
            }
            if (gcThreads.isNotEmpty()) {
                val gcCpu = gcThreads.sumOf { it.cpuPercent.toDouble() }.toFloat()
                StatCard(stringResource(R.string.ss_gc)) {
                    StatRow(
                        stringResource(R.string.label_total_gc_cpu),
                        "%.1f%%".format(gcCpu),
                        severityColor(gcCpu, 2f, 5f)
                    )
                    gcThreads.filter { it.cpuPercent > 0.01f }.forEach { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoText(t.name)
                            MonoText("[${t.state}]")
                            MonoText("%.2f%%".format(t.cpuPercent))
                        }
                    }
                }
            }

            val binderThreads = ss.threads.filter { it.service == "Binder" }
            if (binderThreads.isNotEmpty()) {
                val binderCpu = binderThreads.sumOf { it.cpuPercent.toDouble() }.toFloat()
                val busyBinders = binderThreads.count { it.cpuPercent > 0.5f }
                StatCard("${stringResource(R.string.ss_binder_threads)} (${binderThreads.size})") {
                    StatRow(
                        stringResource(R.string.label_total_binder_cpu),
                        "%.1f%%".format(binderCpu)
                    )
                    StatRow(stringResource(R.string.label_busy_binders), "$busyBinders")
                    binderThreads.filter { it.cpuPercent > 0.3f }
                        .sortedByDescending { it.cpuPercent }
                        .take(10)
                        .forEach { t ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MonoText("${t.name} (${t.tid})")
                                MonoText(
                                    "%.1f%%".format(t.cpuPercent),
                                    color = severityColor(t.cpuPercent, 2f, 5f)
                                )
                            }
                        }
                }
            }
        }
    }
}
