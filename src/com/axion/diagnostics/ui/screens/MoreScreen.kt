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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.axion.diagnostics.R
import com.axion.diagnostics.export.ReportExporter
import com.axion.diagnostics.ui.components.StatCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import com.axion.diagnostics.service.CpuOverlayService
import com.axion.diagnostics.service.MonitorService
import com.axion.diagnostics.util.LaunchedActiveEffect
import kotlinx.coroutines.delay

@Composable
fun MoreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayActive by remember { mutableStateOf(CpuOverlayService.isRunning) }
    var notificationStatsActive by remember {
        mutableStateOf(
            Settings.Secure.getInt(context.contentResolver, "ax_diagnostics_notification_enabled", 1) == 1
        )
    }

    LaunchedActiveEffect {
        while (true) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
            overlayActive = CpuOverlayService.isRunning
            notificationStatsActive = Settings.Secure.getInt(context.contentResolver, "ax_diagnostics_notification_enabled", 1) == 1
            delay(1000)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(stringResource(R.string.more_export)) {
            Text(
                stringResource(R.string.more_export_hint),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            ReportExporter.export(context)
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_report_saved, path),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.action_export_report))
            }
        }

        StatCard(stringResource(R.string.cpu_overlay_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.cpu_overlay_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (!hasOverlayPermission) {
                    Text(
                        text = stringResource(R.string.cpu_overlay_permission_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cpu_overlay_grant))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overlayActive) {
                                stringResource(R.string.cpu_overlay_enabled)
                            } else {
                                stringResource(R.string.cpu_overlay_disabled)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = if (overlayActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Switch(
                            checked = overlayActive,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    CpuOverlayService.start(context)
                                    overlayActive = true
                                } else {
                                    CpuOverlayService.stop(context)
                                    overlayActive = false
                                }
                            }
                        )
                    }
                }
            }
        }

        StatCard(stringResource(R.string.more_notification_stats)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.more_notification_stats_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = if (notificationStatsActive) {
                            stringResource(R.string.common_enabled)
                        } else {
                            stringResource(R.string.common_disabled)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = if (notificationStatsActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Switch(
                        checked = notificationStatsActive,
                        onCheckedChange = { checked ->
                            Settings.Secure.putInt(
                                context.contentResolver,
                                "ax_diagnostics_notification_enabled",
                                if (checked) 1 else 0
                            )
                            notificationStatsActive = checked
                            if (MonitorService.isRunning) {
                                val intent = Intent(context, MonitorService::class.java)
                                runCatching { context.startService(intent) }
                            }
                        }
                    )
                }
            }
        }
    }
}
