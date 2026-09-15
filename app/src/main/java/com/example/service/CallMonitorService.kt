package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.ui.main.MainActivity

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val CHANNEL_ID = "myra_call_monitor_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        const val EXTRA_INCOMING_CALL = "INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isRinging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerPhoneStateListener()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MYRA Call Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors incoming calls for voice announcements"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Call Assistant")
            .setContentText("Monitoring incoming calls")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            isRinging = true
                            val callerName = resolveCallerName(phoneNumber)
                            Log.d(TAG, "Incoming call from: $callerName ($phoneNumber)")

                            val intent = Intent(this@CallMonitorService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(EXTRA_INCOMING_CALL, true)
                                putExtra(EXTRA_CALLER_NAME, callerName)
                            }
                            startActivity(intent)
                        }

                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (isRinging) {
                                isRinging = false
                                Log.d(TAG, "Call ended or dismissed")
                                sendBroadcast(Intent(ACTION_CALL_ENDED))
                            }
                        }

                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            isRinging = false
                            Log.d(TAG, "Call answered")
                        }
                    }
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering phone state listener: ${e.message}")
        }
    }

    private fun resolveCallerName(number: String?): String {
        if (number.isNullOrEmpty()) return "Unknown Caller"
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            var name = number
            cursor?.use {
                if (it.moveToFirst()) {
                    name = it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
            name ?: number ?: "Unknown Caller"
        } catch (e: Exception) {
            number ?: "Unknown Caller"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering listener: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
