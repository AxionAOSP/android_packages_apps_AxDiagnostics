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
import com.axion.diagnostics.data.GpuCollector
import com.axion.diagnostics.data.GpuSnapshot
import com.axion.diagnostics.ui.components.MetricProgressCard
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SparklineChart
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun GpuScreen(modifier: Modifier = Modifier) {
    var gpu by remember { mutableStateOf<GpuSnapshot?>(null) }
    var usageHistory by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    GpuCollector.collect()
                }
                gpu = snapshot
                if (snapshot.available) {
                    usageHistory = (usageHistory + snapshot.busyPercent.toFloat()).takeLast(32)
                }
            } catch (e: Exception) {
                gpu = GpuSnapshot(0, 0, 0, 0, "unavailable", emptyList(), false)
            }
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
        gpu?.let { g ->
            if (g.available) {
                MetricProgressCard(
                    title = stringResource(R.string.gpu_usage),
                    primary = "${g.busyPercent}%",
                    secondary = "${g.frequencyMhz} MHz",
                    percent = g.busyPercent.toFloat()
                )

                StatCard(stringResource(R.string.gpu_usage_history)) {
                    SparklineChart(
                        values = usageHistory,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                StatCard(stringResource(R.string.gpu_freq_gov)) {
                    StatRow(stringResource(R.string.gpu_clock_current), "${g.frequencyMhz} MHz")
                    StatRow(stringResource(R.string.gpu_clock_min), "${g.minFrequencyMhz} MHz")
                    StatRow(stringResource(R.string.gpu_clock_max), "${g.maxFrequencyMhz} MHz")
                    StatRow(stringResource(R.string.gpu_active_governor), g.governor)
                }

                if (g.availableFrequencies.isNotEmpty()) {
                    StatCard(stringResource(R.string.gpu_avail_freqs)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            g.availableFrequencies.chunked(3).forEach { chunk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    chunk.forEach { freq ->
                                        MonoText("$freq MHz", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                StatCard(stringResource(R.string.gpu_unsupported_title)) {
                    Text(
                        text = stringResource(R.string.gpu_unsupported_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
