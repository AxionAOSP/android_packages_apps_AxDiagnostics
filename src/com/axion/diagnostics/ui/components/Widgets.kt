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

package com.axion.diagnostics.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

data class ProgressSegment(
    val label: String,
    val percent: Float,
    val color: Color
)

data class BarChartEntry(
    val label: String,
    val value: Float,
    val color: Color
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    val resolvedValueColor =
        if (valueColor != Color.Unspecified) valueColor else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            color = resolvedValueColor
        )
    }
}

@Composable
fun UsageBar(
    label: String,
    percent: Float,
    detail: String = "",
    modifier: Modifier = Modifier
) {
    val color = when {
        percent > 90f -> MaterialTheme.colorScheme.error
        percent > 70f -> MaterialTheme.colorScheme.tertiary
        percent > 50f -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (detail.isNotEmpty()) detail else "%.1f%%".format(percent),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.extraSmall),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
fun DeviceMetricRow(
    label: String,
    value: String,
    detail: String,
    percent: Float,
    modifier: Modifier = Modifier,
    color: Color = metricColor(percent)
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { percent.safePercent() / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(9.dp)
                .clip(MaterialTheme.shapes.extraSmall),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
fun MetricProgressCard(
    title: String,
    primary: String,
    secondary: String,
    percent: Float,
    modifier: Modifier = Modifier,
    color: Color = metricColor(percent)
) {
    StatCard(title, modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = primary,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { percent.safePercent() / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
fun ScoreRing(
    score: Int,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = metricColor(100f - score),
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val scorePercent = score.coerceIn(0, 100)
    Box(
        modifier = modifier
            .size(112.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(scorePercent.toFloat(), 0f..100f)
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(112.dp)) {
            val strokeWidth = 10.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * scorePercent / 100f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$scorePercent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
fun SegmentedProgressBar(
    segments: List<ProgressSegment>,
    modifier: Modifier = Modifier,
    totalPercent: Float = segments.sumOf { it.percent.toDouble() }.toFloat()
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(totalPercent.safePercent(), 0f..100f)
            }
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = radius
        )
        var startX = 0f
        val scale = if (totalPercent > 100f) 100f / totalPercent else 1f
        segments.forEach { segment ->
            val width = size.width * segment.percent.safePercent() * scale / 100f
            if (width > 0f) {
                drawRoundRect(
                    color = segment.color,
                    topLeft = Offset(startX, 0f),
                    size = Size(width, size.height),
                    cornerRadius = radius
                )
                startX += width
            }
        }
    }
}

@Composable
fun SegmentLegend(
    segments: List<ProgressSegment>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 10.dp)) {
        segments.forEach { segment ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = segment.color)
                    }
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    text = "%.1f%%".format(segment.percent.safePercent()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun SparklineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Canvas(modifier = modifier.fillMaxWidth().height(72.dp)) {
        if (values.isEmpty()) return@Canvas
        val safeValues = values.map { it.safeNumber() }
        val minValue = safeValues.minOrNull() ?: 0f
        val maxValue = safeValues.maxOrNull() ?: 0f
        val range = max(1f, maxValue - minValue)
        val centerY = size.height * 0.5f
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx()
        )
        if (safeValues.size == 1) {
            val y = size.height - ((safeValues.first() - minValue) / range * size.height)
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(size.width, y))
            return@Canvas
        }
        val step = size.width / (safeValues.size - 1)
        val path = Path()
        safeValues.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - ((value - minValue) / range * size.height)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun VerticalBarChart(
    entries: List<BarChartEntry>,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            if (entries.isEmpty()) return@Canvas
            val maxValue = max(1f, entries.maxOf { it.value.safeNumber() })
            val gap = min(10.dp.toPx(), size.width / (entries.size * 3f))
            val barWidth = (size.width - gap * (entries.size - 1)) / entries.size
            entries.forEachIndexed { index, entry ->
                val left = index * (barWidth + gap)
                val height = size.height * (entry.value.safeNumber() / maxValue).coerceIn(0f, 1f)
                val top = size.height - height
                val radius =
                    CornerRadius(min(barWidth, 18.dp.toPx()) / 2f, min(barWidth, 18.dp.toPx()) / 2f)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = entry.color,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, height),
                    cornerRadius = radius
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entries.forEach { entry ->
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun MonoText(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
    )
}

fun formatKb(kb: Long): String = when {
    kb >= 1_048_576 -> "%.1f GB".format(kb / 1_048_576f)
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else -> "$kb KB"
}

fun formatMs(ms: Long): String = when {
    ms >= 3_600_000 -> "%.1fh".format(ms / 3_600_000f)
    ms >= 60_000 -> "%.1fm".format(ms / 60_000f)
    ms >= 1000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms}ms"
}

@Composable
fun severityColor(value: Float, warn: Float, critical: Float): Color = when {
    value >= critical -> MaterialTheme.colorScheme.error
    value >= warn -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

object DiagnosticsColors {
    val positive: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    val warning: Color
        @Composable get() = MaterialTheme.colorScheme.tertiary

    val critical: Color
        @Composable get() = MaterialTheme.colorScheme.error

    val neutral: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun metricColor(percent: Float): Color = when {
    percent.safePercent() >= 90f -> MaterialTheme.colorScheme.error
    percent.safePercent() >= 70f -> MaterialTheme.colorScheme.tertiary
    percent.safePercent() >= 50f -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun Float.safePercent(): Float = safeNumber().coerceIn(0f, 100f)

private fun Float.safeNumber(): Float =
    if (isNaN() || isInfinite()) 0f else this
