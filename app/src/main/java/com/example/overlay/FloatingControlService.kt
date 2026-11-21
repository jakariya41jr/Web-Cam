package com.example.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.VirtualCamRepository
import com.example.diagnostics.LogCollector
import com.example.model.VirtualCamConfig
import com.example.model.VirtualCamConstants
import kotlin.math.abs

/**
 * Floating SYSTEM_ALERT_WINDOW quick-switch controller allowing instant real-time switching
 * between Virtual Camera and Real/Bypass Camera, plus Video Audio and Main Mic toggles,
 * directly over active video calls without restarting target apps.
 */
class FloatingControlService : Service() {

    companion object {
        private const val CHANNEL_ID = "virtualcam_overlay_channel"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_STOP = "${VirtualCamConstants.MODULE_PACKAGE}.ACTION_STOP_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, FloatingControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: VirtualCamRepository
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var expanded = false
    private lateinit var statusDot: View
    private lateinit var pillLabel: TextView
    private lateinit var expandToggle: TextView
    private lateinit var expandedPanel: LinearLayout
    private lateinit var btnCameraMode: TextView
    private lateinit var btnVideoAudio: TextView
    private lateinit var btnMainMic: TextView

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshOverlayUi()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = VirtualCamRepository(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        startOverlayForeground()

        if (!Settings.canDrawOverlays(this)) {
            markOverlayState(false)
            stopSelf()
            return
        }

        val filter = IntentFilter(VirtualCamConstants.ACTION_CONFIG_CHANGED)
        ContextCompat.registerReceiver(
            this,
            configReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        createOverlayView()
        markOverlayState(true)
        refreshOverlayUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            markOverlayState(false)
            stopSelf()
            return START_NOT_STICKY
        }
        refreshOverlayUi()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configReceiver) }
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        markOverlayState(false)
        super.onDestroy()
    }

    private fun startOverlayForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingControlService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Close Overlay", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }
        layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EA121826"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#5538BDF8"))
            }
            elevation = dp(8).toFloat()
        }

        // Top compact draggable pill row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
        }

        pillLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8), 0)
        }

        expandToggle = TextView(this).apply {
            text = "▾"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), dp(2), dp(4), dp(2))
            setOnClickListener {
                expanded = !expanded
                expandedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "▴" else "▾"
            }
        }

        headerRow.addView(statusDot)
        headerRow.addView(pillLabel)
        headerRow.addView(expandToggle)

        // Drag & quick-tap handler on pill header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleBypassLive()
                    }
                    true
                }
                else -> false
            }
        }

        // Expandable 3-switch quick-control panel
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }

        btnCameraMode = createActionChip().apply {
            setOnClickListener { toggleBypassLive() }
        }
        btnVideoAudio = createActionChip().apply {
            setOnClickListener { toggleVideoAudioLive() }
        }
        btnMainMic = createActionChip().apply {
            setOnClickListener { toggleMainMicLive() }
        }

        val btnClose = TextView(this).apply {
            text = "Close Overlay"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(2))
            setOnClickListener {
                markOverlayState(false)
                stopSelf()
            }
        }

        expandedPanel.addView(btnCameraMode)
        expandedPanel.addView(btnVideoAudio)
        expandedPanel.addView(btnMainMic)
        expandedPanel.addView(btnClose)

        container.addView(headerRow)
        container.addView(expandedPanel)

        rootView = container
        runCatching { windowManager.addView(container, params) }.onFailure {
            stopSelf()
        }
    }

    private fun createActionChip(): TextView {
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
    }

    private fun toggleBypassLive() {
        val current = repository.loadConfig()
        val updated = if (!current.enabled) {
            current.copy(enabled = true, forceBypass = false)
        } else {
            current.copy(forceBypass = !current.forceBypass)
        }
        repository.saveConfig(updated)
        LogCollector.recordEvent(
            "Overlay",
            if (updated.isEffectivelyArmed) "Switched to VIRTUAL feed" else "Switched to ORIGINAL / BYPASS"
        )
        refreshOverlayUi(updated)
    }

    private fun toggleVideoAudioLive() {
        val current = repository.loadConfig()
        val updated = current.copy(audioEnabled = !current.audioEnabled)
        repository.saveConfig(updated)
        LogCollector.recordEvent("Overlay", "Video Sound -> ${if (updated.audioEnabled) "ON" else "OFF"}")
        refreshOverlayUi(updated)
    }

    private fun toggleMainMicLive() {
        val current = repository.loadConfig()
        val updated = current.copy(micEnabled = !current.micEnabled)
        repository.saveConfig(updated)
        LogCollector.recordEvent("Overlay", "Main Mic -> ${if (updated.micEnabled) "ON" else "MUTED"}")
        refreshOverlayUi(updated)
    }

    private fun markOverlayState(running: Boolean) {
        runCatching {
            val current = repository.loadConfig()
            if (current.overlayRunning != running) {
                repository.saveConfig(current.copy(overlayRunning = running))
            }
        }
    }

    private fun refreshOverlayUi(config: VirtualCamConfig = repository.loadConfig()) {
        if (rootView == null) return
        val armed = config.isEffectivelyArmed
        val bypassed = config.forceBypass || !config.enabled

        val dotColor = when {
            armed -> Color.parseColor("#10B981")
            bypassed -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#38BDF8")
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }

        val audioFlag = if (config.audioEnabled) "VA" else "va"
        val micFlag = if (config.micEnabled) "MIC" else "mute"
        pillLabel.text = when {
            armed -> "VIRTUAL • $audioFlag/$micFlag"
            config.enabled && !config.selectedMedia.hasMedia -> "NO MEDIA"
            else -> "BYPASS • $micFlag"
        }

        styleChip(
            btnCameraMode,
            label = if (armed) "Cam: VIRTUAL (Tap to Bypass)" else "Cam: ORIGINAL / BYPASS",
            active = armed,
            activeHex = "#064E3B",
            inactiveHex = "#78350F"
        )
        styleChip(
            btnVideoAudio,
            label = if (config.audioEnabled) "Video Audio: ON" else "Video Audio: OFF",
            active = config.audioEnabled,
            activeHex = "#0C4A6E",
            inactiveHex = "#1E293B"
        )
        styleChip(
            btnMainMic,
            label = if (config.micEnabled) "Main Mic: ON" else "Main Mic: MUTED",
            active = config.micEnabled,
            activeHex = "#064E3B",
            inactiveHex = "#7F1D1D"
        )
    }

    private fun styleChip(
        view: TextView,
        label: String,
        active: Boolean,
        activeHex: String,
        inactiveHex: String
    ) {
        view.text = label
        view.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (active) activeHex else inactiveHex))
            cornerRadius = dp(10).toFloat()
            setStroke(
                dp(1),
                Color.parseColor(if (active) "#55FFFFFF" else "#3394A3B8")
            )
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
