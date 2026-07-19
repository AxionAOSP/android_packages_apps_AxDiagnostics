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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.axion.diagnostics.data.StorageCollector
import com.axion.diagnostics.data.StorageSnapshot
import com.axion.diagnostics.data.StorageVolumeRole
import com.axion.diagnostics.ui.components.ProgressSegment
import com.axion.diagnostics.ui.components.SectionHeader
import com.axion.diagnostics.ui.components.SegmentLegend
import com.axion.diagnostics.ui.components.SegmentedProgressBar
import com.axion.diagnostics.ui.components.StatCard
import com.axion.diagnostics.ui.components.StatRow
import com.axion.diagnostics.util.LaunchedActiveEffect
import com.axion.diagnostics.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun StorageScreen(modifier: Modifier = Modifier) {
    var storage by remember { mutableStateOf<StorageSnapshot?>(null) }

    LaunchedActiveEffect {
        while (true) {
            try {
                storage = withContext(Dispatchers.IO) { StorageCollector.collect() }
            } catch (e: Exception) {
                storage = StorageSnapshot(emptyList(), null, null)
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
        storage?.let { s ->
            if (s.volumes.isNotEmpty()) {
                StatCard(stringResource(R.string.more_storage)) {
                    s.volumes.forEach { volume ->
                        SectionHeader(stringResource(storageLabelRes(volume.role)))
                        val segments = listOf(
                            ProgressSegment(
                                label = stringResource(R.string.label_used),
                                percent = volume.usedPercent,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                            ProgressSegment(
                                label = stringResource(R.string.label_available),
                                percent = volume.availablePercent,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                        SegmentedProgressBar(
                            segments = segments,
                            totalPercent = volume.usedPercent,
                        )
                        SegmentLegend(segments)
                        StatRow(
                            stringResource(R.string.label_used),
                            stringResource(
                                R.string.storage_usage_detail,
                                volume.usedPercent,
                                formatBytes(volume.availableBytes),
                            ),
                        )
                        StatRow(
                            stringResource(R.string.label_total),
                            formatBytes(volume.totalBytes),
                        )
                        StatRow(
                            stringResource(R.string.label_available),
                            formatBytes(volume.availableBytes),
                        )
                        StatRow(stringResource(R.string.label_path), volume.path)
                    }
                }
            }

            if (s.lifetimeEstimation != null || s.preEolInfo != null) {
                StatCard(stringResource(R.string.more_storage_health)) {
                    s.lifetimeEstimation?.let { est ->
                        StatRow(stringResource(R.string.more_storage_lifetime), est)
                    }
                    s.preEolInfo?.let { eol ->
                        StatRow(stringResource(R.string.more_storage_eol), eol)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.more_storage_health_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun storageLabelRes(role: StorageVolumeRole): Int = when (role) {
    StorageVolumeRole.DATA -> R.string.storage_data
    StorageVolumeRole.CACHE -> R.string.storage_cache
    StorageVolumeRole.SYSTEM -> R.string.storage_system
    StorageVolumeRole.SYSTEM_EXT -> R.string.storage_system_ext
    StorageVolumeRole.PRODUCT -> R.string.storage_product
    StorageVolumeRole.VENDOR -> R.string.storage_vendor
    StorageVolumeRole.EXTERNAL -> R.string.storage_external
}
