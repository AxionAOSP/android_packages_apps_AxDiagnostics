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
import com.axion.diagnostics.data.ThermalCollector
import com.axion.diagnostics.data.ThermalSnapshot
import com.axion.diagnostics.ui.components.BarChartEntry
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.SectionHeader
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.UsageBar
import com.axion.diagnostics.ui.components.VerticalBarChart
import com.axion.diagnostics.ui.components.severityColor
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ThermalScreen(modifier: Modifier = Modifier) {
    var thermal by remember { mutableStateOf<ThermalSnapshot?>(null) }

    LaunchedActiveEffect {
        while (true) {
            try {
                thermal = withContext(Dispatchers.IO) { ThermalCollector.collect() }
            } catch (e: Exception) {
                thermal = ThermalSnapshot(emptyList(), emptyList(), 0f, "none")
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
        thermal?.let { t ->
            StatCard(stringResource(R.string.thermal_summary)) {
                StatRow(
                    stringResource(R.string.label_hottest),
                    t.hottest,
                    severityColor(t.maxTemperature, 45f, 60f),
                )
                StatRow(
                    stringResource(R.string.label_max_temp),
                    "%.1f°C".format(t.maxTemperature),
                    severityColor(t.maxTemperature, 45f, 60f),
                )
                val activeCount = t.coolingDevices.count { it.currentState > 0 }
                StatRow(
                    stringResource(R.string.label_active_cooling),
                    "$activeCount / ${t.coolingDevices.size}",
                )
            }

            val hotZones = t.zones
                .filter { it.temperatureC > 0f }
                .sortedByDescending { it.temperatureC }
                .take(6)
            if (hotZones.isNotEmpty()) {
                StatCard(stringResource(R.string.thermal_zone_graph)) {
                    VerticalBarChart(
                        entries = hotZones.map { zone ->
                            BarChartEntry(
                                label = zone.type,
                                value = zone.temperatureC,
                                color = severityColor(zone.temperatureC, 45f, 60f),
                            )
                        },
                    )
                }
            }

            StatCard(stringResource(R.string.thermal_zones)) {
                t.zones.sortedByDescending { it.temperatureC }.forEach { zone ->
                    val tempColor = severityColor(zone.temperatureC, 45f, 60f)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MonoText(zone.type.take(25))
                        MonoText("%.1f°C".format(zone.temperatureC), color = tempColor)
                    }
                }
            }

            val activeCooling = t.coolingDevices.filter { it.currentState > 0 }
            if (activeCooling.isNotEmpty()) {
                StatCard(stringResource(R.string.thermal_active_cooling)) {
                    activeCooling.forEach { cd ->
                        val coolingPercent =
                            if (cd.maxState > 0) {
                                cd.currentState.toFloat() / cd.maxState * 100f
                            } else {
                                0f
                            }
                        UsageBar(
                            label = cd.type,
                            percent = coolingPercent,
                            detail = "${cd.currentState} / ${cd.maxState}",
                        )
                    }
                }
            }

            if (t.coolingDevices.isNotEmpty()) {
                StatCard(stringResource(R.string.thermal_all_cooling)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.col_device),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.col_state),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    HorizontalDivider()
                    t.coolingDevices.forEach { cd ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MonoText(cd.type.take(30))
                            MonoText("${cd.currentState}/${cd.maxState}")
                        }
                    }
                }
            }

            val zonesWithTrips = t.zones.filter { it.tripPoints.isNotEmpty() }
            if (zonesWithTrips.isNotEmpty()) {
                StatCard(stringResource(R.string.thermal_trip_points)) {
                    zonesWithTrips.take(10).forEach { zone ->
                        SectionHeader(zone.type)
                        zone.tripPoints.forEach { trip ->
                            StatRow(trip.type, "%.0f°C".format(trip.temperatureC))
                        }
                    }
                }
            }
        }
    }
}
