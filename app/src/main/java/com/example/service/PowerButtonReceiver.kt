package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_CLICK_TIME_DELTA = 600L // milliseconds
        private var lastEventTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = System.currentTimeMillis()
            val timeDiff = now - lastEventTime
            lastEventTime = now

            if (timeDiff in 1..DOUBLE_CLICK_TIME_DELTA) {
                Log.d(TAG, "Double screen power toggle detected ($timeDiff ms) -> launching overlay")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                    val serviceIntent = Intent(context, MyraOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
