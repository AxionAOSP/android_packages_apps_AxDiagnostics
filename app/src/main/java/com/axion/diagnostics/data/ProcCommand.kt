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

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ProcCommand {

    private const val TIMEOUT_MS = 3_000L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "proc-command-reader").apply { isDaemon = true }
    }

    fun readOutput(vararg command: String): String? {
        var process: Process? = null
        return try {
            val proc = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            process = proc
            val output = executor.submit<String> {
                proc.inputStream.bufferedReader().use { it.readText() }
            }
            if (!proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                output.cancel(true)
                return null
            }
            output.get(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }
}
