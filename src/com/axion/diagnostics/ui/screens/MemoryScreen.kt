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
import com.axion.diagnostics.data.MemCollector
import com.axion.diagnostics.data.MemSnapshot
import com.axion.diagnostics.data.ProcessCollector
import com.axion.diagnostics.data.ProcessSnapshot
import com.axion.diagnostics.ui.components.MetricProgressCard
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.ProgressSegment
import com.axion.diagnostics.ui.components.SegmentLegend
import com.axion.diagnostics.ui.components.SegmentedProgressBar
import com.axion.diagnostics.ui.components.SparklineChart
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.UsageBar
import com.axion.diagnostics.ui.components.formatKb
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MemoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var optimizing by remember { mutableStateOf(false) }

    var mem by remember { mutableStateOf<MemSnapshot?>(null) }
    var processes by remember { mutableStateOf<List<ProcessSnapshot>>(emptyList()) }
    var usageHistory by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                MemoryUiSnapshot(
                    mem = MemCollector.collect(),
                    processes = ProcessCollector.collect(50)
                )
            }
            mem = snapshot.mem
            processes = snapshot.processes
            usageHistory = (usageHistory + snapshot.mem.usedPercent).takeLast(32)
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
        mem?.let { m ->
            MetricProgressCard(
                title = stringResource(R.string.mem_ram_usage),
                primary = "%.0f%%".format(m.usedPercent),
                secondary = "${formatKb(m.usedKb)} / ${formatKb(m.totalKb)}",
                percent = m.usedPercent
            )

            StatCard(stringResource(R.string.mem_usage_history)) {
                SparklineChart(
                    values = usageHistory,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            StatCard(stringResource(R.string.mem_optimize)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.mem_optimize_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.Button(
                        onClick = {
                            optimizing = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    MemCollector.optimizeMemory()
                                }
                                optimizing = false
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.mem_optimized),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = !optimizing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (optimizing) {
                                stringResource(R.string.mem_optimizing)
                            } else {
                                stringResource(R.string.mem_optimize)
                            }
                        )
                    }
                }
            }

            StatCard(stringResource(R.string.mem_breakdown)) {
                val usedSegment = ProgressSegment(
                    label = stringResource(R.string.label_used),
                    percent = m.usedPercent,
                    color = MaterialTheme.colorScheme.primary
                )
                val freeSegment = ProgressSegment(
                    label = stringResource(R.string.label_available),
                    percent = 100f - m.usedPercent,
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
                val segments = listOf(usedSegment, freeSegment)
                SegmentedProgressBar(segments, totalPercent = m.usedPercent)
                SegmentLegend(segments)
                StatRow(stringResource(R.string.label_total), formatKb(m.totalKb))
                StatRow(stringResource(R.string.label_available), formatKb(m.availableKb))
                StatRow(stringResource(R.string.label_free), formatKb(m.freeKb))
                StatRow(stringResource(R.string.label_buffers), formatKb(m.buffersKb))
                StatRow(stringResource(R.string.label_cached), formatKb(m.cachedKb))
            }

            StatCard(stringResource(R.string.mem_detailed_breakdown)) {
                StatRow(stringResource(R.string.label_active), formatKb(m.activeKb))
                StatRow(stringResource(R.string.label_inactive), formatKb(m.inactiveKb))
                StatRow(stringResource(R.string.label_anon), formatKb(m.anonPagesKb))
                StatRow(stringResource(R.string.label_mapped), formatKb(m.mappedKb))
                StatRow(stringResource(R.string.label_shmem), formatKb(m.shmemKb))
                StatRow(stringResource(R.string.label_slab), formatKb(m.slabKb))
                StatRow(stringResource(R.string.label_kernel_stack), formatKb(m.kernelStackKb))
                StatRow(stringResource(R.string.label_page_tables), formatKb(m.pageTables))
                StatRow(stringResource(R.string.label_vmalloc), formatKb(m.vmallocUsedKb))
                StatRow(stringResource(R.string.label_dirty), formatKb(m.dirtyKb))
                StatRow(stringResource(R.string.label_writeback), formatKb(m.writebackKb))
            }

            if (m.swapTotalKb > 0) {
                StatCard(stringResource(R.string.mem_swap)) {
                    UsageBar(
                        stringResource(R.string.label_used),
                        m.swapUsedPercent,
                        "${formatKb(m.swapUsedKb)} / ${formatKb(m.swapTotalKb)}"
                    )
                    StatRow(stringResource(R.string.label_swap_cached), formatKb(m.swapCachedKb))
                }
            }

            if (m.zramTotalKb > 0) {
                StatCard(stringResource(R.string.mem_zram)) {
                    StatRow(stringResource(R.string.label_disk_size), formatKb(m.zramTotalKb))
                    StatRow(stringResource(R.string.label_compressed), formatKb(m.zramUsedKb))
                    StatRow(stringResource(R.string.label_orig_data), formatKb(m.zramOrigKb))
                    StatRow(
                        stringResource(R.string.label_compression_ratio),
                        "%.2fx".format(m.zramCompressionRatio)
                    )
                }
            }
        }

        val memProcesses = processes.sortedByDescending { it.rssKb }.take(15)
        if (memProcesses.isNotEmpty()) {
            StatCard(stringResource(R.string.mem_top_processes)) {
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
                        stringResource(R.string.col_rss),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                HorizontalDivider()
                memProcesses.forEach { proc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText("${proc.name} (${proc.pid})")
                        MonoText(formatKb(proc.rssKb))
                    }
                }
            }
        }
    }
}

private data class MemoryUiSnapshot(
    val mem: MemSnapshot,
    val processes: List<ProcessSnapshot>
)
