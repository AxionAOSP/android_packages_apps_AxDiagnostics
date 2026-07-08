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

import android.os.Environment
import android.os.StatFs
import java.io.File

enum class StorageVolumeRole {
    DATA,
    CACHE,
    SYSTEM,
    SYSTEM_EXT,
    PRODUCT,
    VENDOR,
    EXTERNAL
}

data class StorageVolumeSnapshot(
    val role: StorageVolumeRole,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val availableBytes: Long
) {
    val usedBytes get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedPercent get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes * 100f else 0f
    val availablePercent get() = if (totalBytes > 0) {
        availableBytes.toFloat() / totalBytes * 100f
    } else {
        0f
    }
}

data class StorageSnapshot(
    val volumes: List<StorageVolumeSnapshot>,
    val lifetimeEstimation: String? = null,
    val preEolInfo: String? = null
) {
    val dataVolume get() = volumes.firstOrNull { it.role == StorageVolumeRole.DATA }
}

object StorageCollector {

    fun collect(): StorageSnapshot {
        val volumes = listOf(
            StorageVolumeRole.DATA to Environment.getDataDirectory(),
            StorageVolumeRole.CACHE to Environment.getDownloadCacheDirectory(),
            StorageVolumeRole.SYSTEM to Environment.getRootDirectory(),
            StorageVolumeRole.SYSTEM_EXT to File("/system_ext"),
            StorageVolumeRole.PRODUCT to File("/product"),
            StorageVolumeRole.VENDOR to File("/vendor"),
            StorageVolumeRole.EXTERNAL to Environment.getExternalStorageDirectory()
        ).mapNotNull { (role, file) ->
            readVolume(role, file)
        }

        val ufsLife = getUfsLifeTimeEstimation()
        val emmcLife = getEmmcLifeTimeEstimation()
        val lifetime = ufsLife ?: emmcLife
        val eol = getUfsEolInfo()

        return StorageSnapshot(
            volumes = volumes,
            lifetimeEstimation = lifetime,
            preEolInfo = eol
        )
    }

    private fun readVolume(role: StorageVolumeRole, file: File): StorageVolumeSnapshot? {
        if (!file.exists()) return null
        return runCatching {
            val stat = StatFs(file.absolutePath)
            val totalBytes = stat.totalBytes
            if (totalBytes <= 0L) return null
            StorageVolumeSnapshot(
                role = role,
                path = file.absolutePath,
                totalBytes = totalBytes,
                freeBytes = stat.freeBytes,
                availableBytes = stat.availableBytes
            )
        }.getOrNull()
    }

    private fun getUfsLifeTimeEstimation(): String? {
        val paths = listOf(
            "/sys/devices/platform/bootdevice/health_descriptor/life_time_estimation_a",
            "/sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_a",
            "/sys/devices/virtual/mi_memory/mi_memory_device/ufshcd0/dump_health_desc",
            "/sys/class/block/sdc/device/health_descriptor/life_time_estimation_a"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                val content = runCatching { file.readText().trim() }.getOrNull() ?: continue
                if (content.isNotEmpty()) {
                    if (path.contains("dump_health_desc")) {
                        val match = Regex("bDeviceLifeTimeEstA\\s*=\\s*(0x[0-9a-fA-F]+|[0-9]+)").find(content)
                        if (match != null) return decodeLifeTime(match.groupValues[1])
                    } else {
                        return decodeLifeTime(content)
                    }
                }
            }
        }
        val socDir = File("/sys/devices/platform/soc")
        if (socDir.exists()) {
            val matchedFiles = runCatching {
                socDir.walkTopDown().maxDepth(5).filter { it.name == "life_time_estimation_a" && it.isFile }.toList()
            }.getOrNull() ?: emptyList()
            for (file in matchedFiles) {
                val content = runCatching { file.readText().trim() }.getOrNull()
                if (!content.isNullOrEmpty()) {
                    return decodeLifeTime(content)
                }
            }
        }
        return null
    }

    private fun decodeLifeTime(hexOrDec: String): String {
        val clean = hexOrDec.lowercase().removePrefix("0x").trim()
        val value = clean.toIntOrNull(16) ?: clean.toIntOrNull() ?: return "Unknown"
        return when (value) {
            0 -> "Unknown"
            1 -> "0% - 10% used"
            2 -> "10% - 20% used"
            3 -> "20% - 30% used"
            4 -> "30% - 40% used"
            5 -> "40% - 50% used"
            6 -> "50% - 60% used"
            7 -> "60% - 70% used"
            8 -> "70% - 80% used"
            9 -> "80% - 90% used"
            10 -> "90% - 100% used"
            11 -> "Exceeded life expectancy"
            else -> "Unknown"
        }
    }

    private fun getEmmcLifeTimeEstimation(): String? {
        val paths = listOf(
            "/sys/block/mmcblk0/device/life_time",
            "/sys/class/block/mmcblk0/device/life_time",
            "/sys/devices/platform/bootdevice/life_time"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                val content = runCatching { file.readText().trim() }.getOrNull()
                if (!content.isNullOrEmpty()) {
                    val parts = content.split("\\s+".toRegex())
                    val valueA = parts.getOrNull(0)?.lowercase()?.removePrefix("0x")?.toIntOrNull(16) ?: continue
                    return when (valueA) {
                        0 -> "Unknown"
                        1 -> "0% - 10% used"
                        2 -> "10% - 20% used"
                        3 -> "20% - 30% used"
                        4 -> "30% - 40% used"
                        5 -> "40% - 50% used"
                        6 -> "50% - 60% used"
                        7 -> "60% - 70% used"
                        8 -> "70% - 80% used"
                        9 -> "80% - 90% used"
                        10 -> "90% - 100% used"
                        11 -> "Exceeded life expectancy"
                        else -> "Unknown"
                    }
                }
            }
        }
        return null
    }

    private fun getUfsEolInfo(): String? {
        val paths = listOf(
            "/sys/devices/platform/bootdevice/health_descriptor/eol_info",
            "/sys/devices/platform/soc/1d84000.ufshc/health_descriptor/eol_info",
            "/sys/class/block/sdc/device/health_descriptor/eol_info"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                val content = runCatching { file.readText().trim() }.getOrNull() ?: continue
                if (content.isNotEmpty()) {
                    return decodeEol(content)
                }
            }
        }
        val socDir = File("/sys/devices/platform/soc")
        if (socDir.exists()) {
            val matchedFiles = runCatching {
                socDir.walkTopDown().maxDepth(5).filter { it.name == "eol_info" && it.isFile }.toList()
            }.getOrNull() ?: emptyList()
            for (file in matchedFiles) {
                val content = runCatching { file.readText().trim() }.getOrNull()
                if (!content.isNullOrEmpty()) {
                    return decodeEol(content)
                }
            }
        }
        return null
    }

    private fun decodeEol(hexOrDec: String): String {
        val clean = hexOrDec.lowercase().removePrefix("0x").trim()
        val value = clean.toIntOrNull(16) ?: clean.toIntOrNull() ?: return "Unknown"
        return when (value) {
            1 -> "Normal (< 80% reserved blocks consumed)"
            2 -> "Warning (~ 80% reserved blocks consumed)"
            3 -> "Critical (> 90% reserved blocks consumed)"
            else -> "Unknown"
        }
    }
}
