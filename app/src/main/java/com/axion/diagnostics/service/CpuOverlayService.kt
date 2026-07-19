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

package com.axion.diagnostics.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.axion.diagnostics.data.CpuCollector
import com.axion.diagnostics.data.GpuCollector
import com.axion.diagnostics.data.MemCollector
import com.axion.diagnostics.data.ThermalCollector
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CpuOverlayService : Service() {

    companion object {
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            context.startService(Intent(context, CpuOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CpuOverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var containerLayout: FrameLayout? = null
    private var statsTextView: TextView? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var pollingJob: Job? = null

    private var isExpanded = false
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                }

                Intent.ACTION_SCREEN_ON -> {
                    startPolling()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create Container Layout
        val frameLayout = FrameLayout(this)

        // Background shape with rounded corners (Glassmorphism-like premium design)
        val bgDrawable = GradientDrawable().apply {
            setColor(0xCC0D0D11.toInt()) // 80% transparent dark premium gray
            cornerRadius = dpToPx(12f).toFloat()
            setStroke(dpToPx(1), 0x33FFFFFF) // thin gray border
        }
        frameLayout.background = bgDrawable

        // Create Text View
        val textView = TextView(this).apply {
            setTextColor(0xFF00FFCC.toInt()) // Premium Neon Cyan
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            gravity = Gravity.CENTER
        }
        textView.text = "CPU: --% | RAM: --%"

        frameLayout.addView(textView)
        containerLayout = frameLayout
        statsTextView = textView

        // Layout Parameters for System Overlay Window
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 150
        }

        // High-fidelity touch listener distinguishing Drag vs Tap
        frameLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startTime = System.currentTimeMillis()
                        isMoving = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                            isMoving = true
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            runCatching {
                                windowManager.updateViewLayout(frameLayout, params)
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        val totalDeltaX = abs(event.rawX - initialTouchX)
                        val totalDeltaY = abs(event.rawY - initialTouchY)

                        // If touch was quick and did not move much, trigger a Tap!
                        if (duration < 250 && totalDeltaX < 15 && totalDeltaY < 15 && !isMoving) {
                            isExpanded = !isExpanded
                            // Instantly refresh text for crisp feedback
                            refreshStats()
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Add the floating view directly to System WindowManager
        runCatching {
            windowManager.addView(frameLayout, params)

            // Register Screen state receiver for absolute battery optimization
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true

            startPolling()
        }.onFailure { e ->
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = serviceScope.launch {
            while (isActive) {
                refreshStats()
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun refreshStats() {
        serviceScope.launch {
            val cpuSnapshot = withContext(Dispatchers.IO) {
                runCatching { CpuCollector.collect() }.getOrNull()
            }
            val memSnapshot = withContext(Dispatchers.IO) {
                runCatching { MemCollector.collect() }.getOrNull()
            }
            val thermalSnapshot = withContext(Dispatchers.IO) {
                runCatching { ThermalCollector.collect() }.getOrNull()
            }
            val gpuSnapshot = withContext(Dispatchers.IO) {
                runCatching { GpuCollector.collect() }.getOrNull()
            }

            if (cpuSnapshot != null && statsTextView != null) {
                val sb = StringBuilder()

                // 1. Build the Compact Row Header (Sleek, short, and focused)
                val cpuUsage = "CPU: %2.0f%%".format(cpuSnapshot.totalUsage)
                val gpuUsage = if (gpuSnapshot != null && gpuSnapshot.available) {
                    " | GPU: %2d%%".format(gpuSnapshot.busyPercent)
                } else {
                    ""
                }
                val ramUsage = if (memSnapshot != null) "RAM: %2.0f%%".format(memSnapshot.usedPercent) else "RAM: --%"
                val tempPart = if (thermalSnapshot != null && thermalSnapshot.maxTemperature > 0f) {
                    " | %2.0f°C".format(thermalSnapshot.maxTemperature)
                } else {
                    ""
                }

                sb.append("$cpuUsage$gpuUsage | $ramUsage$tempPart")

                // 2. Append per-core detail block if expanded
                if (isExpanded) {
                    sb.append("\n-------------------------------------------")
                    val coresFormatted = cpuSnapshot.cores.chunked(4).joinToString("\n") { rowCores ->
                        rowCores.joinToString(" ") { core ->
                            val coreId = core.index
                            if (core.online) {
                                "C%d:%4dM".format(coreId, core.frequencyMhz)
                            } else {
                                "C%d: OFF".format(coreId)
                            }
                        }
                    }
                    sb.append("\n").append(coresFormatted)
                }

                statsTextView?.text = sb.toString()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopPolling()
        serviceJob.cancel()

        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }

        containerLayout?.let { layout ->
            runCatching {
                windowManager.removeView(layout)
            }
        }
        containerLayout = null
        statsTextView = null
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
