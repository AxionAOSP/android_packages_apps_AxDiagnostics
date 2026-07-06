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
    val volumes: List<StorageVolumeSnapshot>
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
        return StorageSnapshot(volumes)
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
}
