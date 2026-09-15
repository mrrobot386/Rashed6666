package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.R
import com.example.ui.main.MainActivity
import com.example.ui.main.OrbAnimationView
import com.example.ui.main.OrbState

class MyraOverlayService : Service() {

    companion object {
        private const val TAG = "MyraOverlayService"
        const val CHANNEL_ID = "myra_overlay_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
        }
        showOverlayOrb()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Floating Orb",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Quick access floating overlay for MYRA Assistant"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Active")
            .setContentText("Tap overlay or screen to interact")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlayOrb() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission missing")
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_orb, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val orbView = overlayView?.findViewById<OrbAnimationView>(R.id.overlayOrbView)
        orbView?.setState(OrbState.ACTIVE)

        val closeBtn = overlayView?.findViewById<ImageButton>(R.id.overlayCloseBtn)
        closeBtn?.setOnClickListener {
            stopSelf()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        val intent = Intent(this@MyraOverlayService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
