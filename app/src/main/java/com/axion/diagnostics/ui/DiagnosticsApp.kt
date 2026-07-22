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

package com.axion.diagnostics.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.axion.compose.scaffold.AxionPinnedTopAppBar
import com.android.axion.compose.theme.AxionTheme
import com.axion.diagnostics.DiagnosticsActivity
import com.axion.diagnostics.R
import com.axion.diagnostics.service.CpuOverlayService
import com.axion.diagnostics.service.MonitorService
import com.axion.diagnostics.ui.screens.AppPowerScreen
import com.axion.diagnostics.ui.screens.BatteryScreen
import com.axion.diagnostics.ui.screens.CpuScreen
import com.axion.diagnostics.ui.screens.GpuScreen
import com.axion.diagnostics.ui.screens.MemoryScreen
import com.axion.diagnostics.ui.screens.MoreScreen
import com.axion.diagnostics.ui.screens.OverviewScreen
import com.axion.diagnostics.ui.screens.StorageScreen
import com.axion.diagnostics.ui.screens.ThermalScreen
import kotlinx.coroutines.launch

private val tabTitles = listOf(
    R.string.tab_overview,
    R.string.tab_apps,
    R.string.tab_cpu,
    R.string.tab_gpu,
    R.string.tab_memory,
    R.string.tab_battery,
    R.string.tab_thermal,
    R.string.more_storage,
    R.string.tab_more,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsApp() {
    AxionTheme {
        val context = LocalContext.current
        val cr = context.contentResolver
        var enabled by remember {
            mutableStateOf(
                Settings.Secure.getInt(cr, DiagnosticsActivity.SETTING_KEY, 0) == 1,
            )
        }

        if (!enabled) {
            DisabledScreen(
                onEnable = {
                    Settings.Secure.putInt(cr, DiagnosticsActivity.SETTING_KEY, 1)
                    MonitorService.setComponentEnabled(context, true)
                    MonitorService.start(context)
                    enabled = true
                },
            )
            return@AxionTheme
        }

        val pagerState = rememberPagerState(pageCount = { tabTitles.size })
        val coroutineScope = rememberCoroutineScope()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                Column {
                    AxionPinnedTopAppBar(
                        title = stringResource(R.string.app_name),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        actions = {
                            FilledTonalButton(
                                onClick = {
                                    Settings.Secure.putInt(cr, DiagnosticsActivity.SETTING_KEY, 0)
                                    MonitorService.stop(context)
                                    CpuOverlayService.stop(context)
                                    MonitorService.setComponentEnabled(context, false)
                                    enabled = false
                                },
                                modifier = Modifier.padding(end = 12.dp),
                            ) {
                                Text(stringResource(R.string.action_disable))
                            }
                        },
                    )
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        edgePadding = 16.dp,
                    ) {
                        tabTitles.forEachIndexed { index, titleRes ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                text = { Text(stringResource(titleRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { page ->
                when (page) {
                    0 -> OverviewScreen()
                    1 -> AppPowerScreen()
                    2 -> CpuScreen()
                    3 -> GpuScreen()
                    4 -> MemoryScreen()
                    5 -> BatteryScreen()
                    6 -> ThermalScreen()
                    7 -> StorageScreen()
                    8 -> MoreScreen()
                }
            }
        }
    }
}

@Composable
private fun DisabledScreen(onEnable: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.disabled_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onEnable) {
                Text(stringResource(R.string.action_enable))
            }
        }
    }
}
