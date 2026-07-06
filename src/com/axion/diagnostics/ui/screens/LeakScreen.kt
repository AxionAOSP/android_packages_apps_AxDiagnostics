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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.data.LeakCandidate
import com.axion.diagnostics.data.LeakConfidence
import com.axion.diagnostics.data.LeakDetector
import com.axion.diagnostics.export.ReportFiles
import com.axion.diagnostics.ui.components.DiagnosticsColors
import com.axion.diagnostics.ui.components.MonoText
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.ui.components.formatKb
import com.axion.diagnostics.ui.components.formatMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LeakScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTracking by remember { mutableStateOf(LeakDetector.isTracking) }
    var candidates by remember { mutableStateOf<List<LeakCandidate>>(emptyList()) }
    var expandedPid by remember { mutableStateOf(-1) }
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(isTracking) {
        if (!isTracking) return@LaunchedEffect
        while (isTracking) {
            withContext(Dispatchers.IO) { LeakDetector.recordSamples() }
            candidates = LeakDetector.getLeakCandidates()
            elapsed = System.currentTimeMillis() - LeakDetector.trackingStartTime
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
        StatCard(stringResource(R.string.leak_title)) {
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
                        stringResource(R.string.status_monitoring)
                    } else {
                        stringResource(R.string.status_stopped)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                    if (isTracking) {
                        val leaking = candidates.count { it.isLeaking }
                        Text(
                            "${candidates.size} tracked | $leaking suspected leaks | ${formatMs(
                                elapsed
                            )}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isTracking) {
                    Button(
                        onClick = { LeakDetector.stopTracking(); isTracking = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.action_stop)) }
                } else {
                    Button(
                        onClick = { LeakDetector.startTracking(); isTracking = true }
                    ) { Text(stringResource(R.string.action_start)) }
                }
            }
            if (candidates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            ReportFiles.write(context, "leaks", LeakDetector.exportReport())
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_saved, path),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text(stringResource(R.string.action_export_report)) }
            }
        }

        val leaking = candidates.filter { it.isLeaking }
        if (leaking.isNotEmpty()) {
            StatCard("${stringResource(R.string.leak_suspects)} (${leaking.size})") {
                leaking.forEach { c ->
                    val confColor = when (c.confidence) {
                        LeakConfidence.CERTAIN -> MaterialTheme.colorScheme.error
                        LeakConfidence.HIGH -> MaterialTheme.colorScheme.error
                        LeakConfidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
                        LeakConfidence.LOW -> MaterialTheme.colorScheme.secondary
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedPid = if (expandedPid == c.pid) -1 else c.pid }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${c.name} (${c.pid})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                MonoText(
                                    "+${formatKb(c.growthKb)} (%.1f%%) @ %.0f KB/min".format(
                                        c.growthPercent,
                                        c.growthRateKbPerMin
                                    )
                                )
                            }
                            Surface(
                                color = confColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    c.confidence.name,
                                    color = confColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (expandedPid == c.pid) {
                            LeakDetailCard(c)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        val growing = candidates.filter { !it.isLeaking && it.growthKb > 1024 }
            .sortedByDescending { it.growthKb }
            .take(15)

        if (growing.isNotEmpty()) {
            StatCard(stringResource(R.string.leak_growing)) {
                growing.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedPid = if (expandedPid == c.pid) -1 else c.pid }
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText("${c.name.take(18)} (${c.pid})")
                        MonoText("+${formatKb(c.growthKb)}")
                        MonoText("%+.1f%%".format(c.growthPercent))
                        MonoText("${c.samples.size}s")
                    }
                    if (expandedPid == c.pid) {
                        LeakDetailCard(c)
                    }
                }
            }
        }

        val stable = candidates.filter { it.growthKb <= 0 }
        if (stable.isNotEmpty() && isTracking) {
            StatCard("${stringResource(R.string.leak_stable)} (${stable.size})") {
                stable.sortedBy { it.growthKb }.take(10).forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MonoText(c.name.take(20))
                        MonoText(formatKb(c.currentRssKb))
                        MonoText(
                            "${formatKb(c.growthKb)}",
                            color = DiagnosticsColors.positive
                        )
                    }
                }
            }
        }

        if (!isTracking && candidates.isEmpty()) {
            StatCard(stringResource(R.string.how_to_use)) {
                Text(
                    stringResource(R.string.leak_how_to_use_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LeakDetailCard(candidate: LeakCandidate) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            StatRow(stringResource(R.string.col_pid), "${candidate.pid}")
            StatRow(stringResource(R.string.label_uid), "${candidate.uid}")
            StatRow(stringResource(R.string.label_start_rss), formatKb(candidate.startRssKb))
            StatRow(stringResource(R.string.label_current_rss), formatKb(candidate.currentRssKb))
            StatRow(stringResource(R.string.label_peak_rss), formatKb(candidate.peakRssKb))
            StatRow(
                stringResource(R.string.label_growth),
                "+${formatKb(candidate.growthKb)} (%.1f%%)".format(candidate.growthPercent)
            )
            StatRow(
                stringResource(R.string.label_growth_rate),
                "%.1f KB/min".format(candidate.growthRateKbPerMin)
            )
            val monotonic = if (candidate.monotonic) {
                stringResource(R.string.common_yes)
            } else {
                stringResource(R.string.common_no)
            }
            StatRow(
                stringResource(R.string.label_monotonic),
                monotonic
            )
            StatRow(stringResource(R.string.label_confidence), candidate.confidence.name)
            StatRow(stringResource(R.string.label_samples), "${candidate.samples.size}")

            if (candidate.samples.size > 5) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.leak_rss_over_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                RssGraph(
                    samples = candidate.samples.map { it.rssKb.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RssGraph(samples: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val minVal = samples.min()
        val maxVal = samples.max()
        val range = (maxVal - minVal).coerceAtLeast(1f)

        drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 0.5f)
        drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 0.5f)

        val path = Path()
        samples.forEachIndexed { i, value ->
            val x = (i.toFloat() / (samples.size - 1)) * w
            val y = h - ((value - minVal) / range * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2f))
    }
}
