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

package com.axion.diagnostics.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

data class CpuClusterConfig(
    val id: String,
    val group: String,
    val minNode: String?,
    val maxNode: String?,
    val availablePath: String?,
    val governorNode: String?,
    val governorAvailablePath: String?
)

data class GpuConfig(
    val node: String?,
    val currentNode: String?,
    val usageNode: String?,
    val frequencyMultiplier: Long,
    val values: List<Int>
)

object KernelConfig {
    private const val CONFIG_PATH = "/system_ext/etc/ax_kernel_manager.xml"

    val cpuClusters: List<CpuClusterConfig> by lazy {
        parseCpuClusters()
    }

    val gpuConfig: GpuConfig by lazy {
        parseGpuConfig()
    }

    fun scaleFreqToMhz(raw: Long): Int {
        return when {
            raw > 1_000_000_000L -> (raw / 1_000_000_000L).toInt()
            raw > 1_000_000L -> (raw / 1_000_000L).toInt()
            raw > 1_000L -> (raw / 1_000L).toInt()
            else -> raw.toInt()
        }
    }

    private fun parseCpuClusters(): List<CpuClusterConfig> {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return emptyList()

        val clusters = mutableListOf<CpuClusterConfig>()
        runCatching {
            FileInputStream(file).use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "cpu") {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val group = parser.getAttributeValue(null, "group") ?: ""
                        val minNode = parser.getAttributeValue(null, "minNode")
                        val maxNode = parser.getAttributeValue(null, "maxNode")
                        val availablePath = parser.getAttributeValue(null, "availablePath")
                        val governorNode = parser.getAttributeValue(null, "governorNode")
                        val governorAvailablePath = parser.getAttributeValue(null, "governorAvailablePath")
                        
                        if (id.isNotEmpty()) {
                            clusters.add(
                                CpuClusterConfig(
                                    id = id,
                                    group = group,
                                    minNode = minNode,
                                    maxNode = maxNode,
                                    availablePath = availablePath,
                                    governorNode = governorNode,
                                    governorAvailablePath = governorAvailablePath
                                )
                            )
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
        return clusters
    }

    private fun parseGpuConfig(): GpuConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return GpuConfig(null, null, null, 1L, emptyList())

        var node: String? = null
        var currentNode: String? = null
        var usageNode: String? = null
        var multiplier = 1L
        var values = emptyList<Int>()

        runCatching {
            FileInputStream(file).use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "gpu") {
                        node = parser.getAttributeValue(null, "node")
                        currentNode = parser.getAttributeValue(null, "currentNode")
                        usageNode = parser.getAttributeValue(null, "usageNode")
                        val multStr = parser.getAttributeValue(null, "frequencyMultiplier")
                        multiplier = multStr?.toLongOrNull() ?: 1L
                        
                        val valStr = parser.getAttributeValue(null, "values")
                        values = valStr?.split(",")
                            ?.mapNotNull { it.trim().toLongOrNull() }
                            ?.map { scaleFreqToMhz(it) } ?: emptyList()
                        break
                    }
                    eventType = parser.next()
                }
            }
        }
        return GpuConfig(node, currentNode, usageNode, multiplier, values)
    }
}
