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

package com.axion.diagnostics.export

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportFiles {

    fun write(context: Context, prefix: String, body: String): String {
        val file = create(context, prefix)
        file.writeText(body)
        return file.absolutePath
    }

    fun create(context: Context, prefix: String): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "axdiagnostics")
        dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${prefix}_$timestamp.txt")
    }
}
