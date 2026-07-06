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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.CpuCollector
import com.axion.diagnostics.data.CpuSnapshot
import com.axion.diagnostics.data.ProcessCollector
import com.axion.diagnostics.data.ProcessSnapshot
import com.axion.diagnostics.ui.components.MetricProgressCard
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SparklineChart
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.UsageBar
import com.axion.diagnostics.ui.components.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CpuScreen(modifier: Modifier = Modifier) {
    var cpu by remember { mutableStateOf<CpuSnapshot?>(null) }
    var processes by remember { mutableStateOf<List<ProcessSnapshot>>(emptyList()) }
    var usageHistory by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                CpuUiSnapshot(
                    cpu = CpuCollector.collect(),
                    processes = ProcessCollector.collect(15)
                )
            }
            cpu = snapshot.cpu
            processes = snapshot.processes
            usageHistory = (usageHistory + snapshot.cpu.totalUsage).takeLast(32)
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
        cpu?.let { c ->
            MetricProgressCard(
                title = stringResource(R.string.cpu_usage),
                primary = "%.0f%%".format(c.totalUsage),
                secondary = stringResource(R.string.cpu_load_1min_detail, c.loadAvg1),
                percent = c.totalUsage
            )

            StatCard(stringResource(R.string.cpu_usage_history)) {
                SparklineChart(
                    values = usageHistory,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            StatCard(stringResource(R.string.cpu_usage_breakdown)) {
                StatRow(stringResource(R.string.label_user), "%.1f%%".format(c.userPercent))
                StatRow(stringResource(R.string.label_system), "%.1f%%".format(c.systemPercent))
                StatRow(
                    stringResource(R.string.label_io_wait),
                    "%.1f%%".format(c.iowaitPercent),
                    severityColor(c.iowaitPercent, 10f, 30f)
                )
                StatRow(stringResource(R.string.label_irq), "%.1f%%".format(c.irqPercent))
            }

            StatCard(stringResource(R.string.cpu_load_avg)) {
                StatRow(stringResource(R.string.label_1min), "%.2f".format(c.loadAvg1))
                StatRow(stringResource(R.string.label_5min), "%.2f".format(c.loadAvg5))
                StatRow(stringResource(R.string.label_15min), "%.2f".format(c.loadAvg15))
                StatRow(
                    stringResource(R.string.label_context_switches),
                    "%,d".format(c.contextSwitches)
                )
                StatRow(stringResource(R.string.label_processes), "%,d".format(c.processes))
                StatRow(
                    stringResource(R.string.label_running_blocked),
                    "${c.procsRunning} / ${c.procsBlocked}"
                )
            }

            StatCard(stringResource(R.string.cpu_per_core)) {
                val offlineTag = stringResource(R.string.cpu_offline)
                c.cores.forEach { core ->
                    UsageBar(
                        label = "CPU${core.index}" + if (core.online) "" else " [$offlineTag]",
                        percent = if (core.online) core.usagePercent else 0f,
                        detail = if (core.online) {
                            "%.0f%% @ %d MHz".format(core.usagePercent, core.frequencyMhz)
                        } else {
                            offlineTag
                        }
                    )
                }
            }

            StatCard(stringResource(R.string.cpu_freq_gov)) {
                c.cores.forEach { core ->
                    if (core.online) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MonoText("CPU${core.index}")
                            MonoText("${core.minFrequencyMhz}-${core.maxFrequencyMhz} MHz")
                            MonoText(core.governor)
                        }
                    }
                }
            }
        }

        if (processes.isNotEmpty()) {
            StatCard(stringResource(R.string.cpu_top_processes)) {
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
                        stringResource(R.string.col_cpu_percent),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                HorizontalDivider()
                processes.forEach { proc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText(
                            "${proc.name} (${proc.pid})",
                        )
                        MonoText(
                            "%.1f%%".format(proc.cpuPercent),
                            color = severityColor(proc.cpuPercent, 20f, 50f)
                        )
                    }
                }
            }
        }
    }
}

private data class CpuUiSnapshot(
    val cpu: CpuSnapshot,
    val processes: List<ProcessSnapshot>
)
