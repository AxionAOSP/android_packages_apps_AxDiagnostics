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

import java.io.File

data class ThermalZone(
    val name: String,
    val type: String,
    val temperatureC: Float,
    val tripPoints: List<TripPoint>
)

data class TripPoint(
    val type: String,
    val temperatureC: Float
)

data class CoolingDevice(
    val name: String,
    val type: String,
    val currentState: Int,
    val maxState: Int
)

data class ThermalSnapshot(
    val zones: List<ThermalZone>,
    val coolingDevices: List<CoolingDevice>,
    val maxTemperature: Float,
    val hottest: String
)

object ThermalCollector {

    fun collect(): ThermalSnapshot {
        val zones = mutableListOf<ThermalZone>()
        val thermalDir = File("/sys/class/thermal/")
        if (thermalDir.exists()) {
            thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy {
                it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0
            }?.forEach { zoneDir ->
                val type = readFileText(File(zoneDir, "type"))
                val temp = readFileText(File(zoneDir, "temp")).toLongOrNull() ?: 0L
                val tempC = temp / 1000f

                val trips = mutableListOf<TripPoint>()
                var tripIdx = 0
                while (true) {
                    val tripTempFile = File(zoneDir, "trip_point_${tripIdx}_temp")
                    val tripTypeFile = File(zoneDir, "trip_point_${tripIdx}_type")
                    if (!tripTempFile.exists()) break
                    val tripTemp = readFileText(tripTempFile).toLongOrNull() ?: 0L
                    val tripType = readFileText(tripTypeFile)
                    trips.add(TripPoint(tripType, tripTemp / 1000f))
                    tripIdx++
                    if (tripIdx > 20) break
                }

                zones.add(ThermalZone(zoneDir.name, type, tempC, trips))
            }
        }

        val coolingDevices = mutableListOf<CoolingDevice>()
        thermalDir.listFiles()?.filter { it.name.startsWith("cooling_device") }?.sortedBy {
            it.name.removePrefix("cooling_device").toIntOrNull() ?: 0
        }?.forEach { cdDir ->
            val type = readFileText(File(cdDir, "type"))
            val curState = readFileText(File(cdDir, "cur_state")).toIntOrNull() ?: 0
            val maxState = readFileText(File(cdDir, "max_state")).toIntOrNull() ?: 0
            coolingDevices.add(CoolingDevice(cdDir.name, type, curState, maxState))
        }

        val maxTemp = zones.maxByOrNull { it.temperatureC }
        return ThermalSnapshot(
            zones = zones,
            coolingDevices = coolingDevices,
            maxTemperature = maxTemp?.temperatureC ?: 0f,
            hottest = maxTemp?.type ?: "none"
        )
    }

    private fun readFileText(file: File): String =
        runCatching { file.readText().trim() }.getOrDefault("")
}
