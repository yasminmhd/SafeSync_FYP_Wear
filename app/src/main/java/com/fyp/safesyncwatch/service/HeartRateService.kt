package com.fyp.safesyncwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.fyp.safesyncwatch.R
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent

class HeartRateService : Service(),
    SensorEventListener,
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null

    private val TAG = "HR_SERVICE"

    // Rate limiting / periodic send
    private val sendIntervalMs = 30_000L
    private var lastSentTime = 0L
    private var lastMeasuredBpm = -1

    private val handler = Handler(Looper.getMainLooper())
    private val periodicSender = object : Runnable {
        override fun run() {
            try {
                val bpm = lastMeasuredBpm
                if (bpm > 0) {
                    Log.d(TAG, "Periodic sender: sending last BPM=$bpm")
                    sendHeartRateToPhone(bpm)
                } else {
                    Log.d(TAG, "Periodic sender: no valid BPM to send")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic sender exception", e)
            } finally {
                handler.postDelayed(this, sendIntervalMs)
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            val dataClient = Wearable.getDataClient(applicationContext)
            val request = PutDataMapRequest.create("/batteryLevel").apply {
                dataMap.putInt("battery", batteryPct)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
                .addOnSuccessListener { Log.d(TAG, "Battery level sent: $batteryPct%") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed sending battery", e) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SafeSync:HR").apply { acquire() }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // Start foreground
        startForeground(1, createNotification())
        Log.d(TAG, "startForeground done")

        // Register sensor for fastest delivery
        heartRateSensor?.let {
            val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
            Log.d(TAG, "registerListener=$ok sensor=${it.name}")
        } ?: run {
            Log.e(TAG, "No heart rate sensor!")
        }

        // Ensure data-layer listeners are registered in the foreground service so callbacks survive screen-off
        try {
            Wearable.getDataClient(applicationContext).addListener(this)
            Wearable.getMessageClient(applicationContext).addListener(this)
            Log.d(TAG, "DataClient / MessageClient listeners registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Wearable listeners", e)
        }

        // Start periodic sending (every 30s)
        handler.postDelayed(periodicSender, sendIntervalMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        unregisterReceiver(batteryReceiver)
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(periodicSender)
        wakeLock?.release()
        wakeLock = null

        // Remove Wearable listeners
        try {
            Wearable.getDataClient(applicationContext).removeListener(this)
            Wearable.getMessageClient(applicationContext).removeListener(this)
            Log.d(TAG, "DataClient / MessageClient listeners removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Wearable listeners", e)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Sensor callbacks
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            val now = System.currentTimeMillis()
            Log.d(TAG, "HR event: $bpm")

            if (bpm > 0) {
                lastMeasuredBpm = bpm
                // Only send immediately if interval expired (otherwise rely on periodic sender)
                if (now - lastSentTime >= sendIntervalMs) {
                    sendHeartRateToPhone(bpm)
                    lastSentTime = now
                } else {
                    Log.d(TAG, "Skipping immediate send; will send in periodic task")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // DataClient listener - incoming data changes from phone
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                val path = event.dataItem.uri.path
                Log.d(TAG, "onDataChanged path=$path, eventType=${event.type}")
                // Handle data requests if needed
                if (path == "/request_heart_rate") {
                    if (lastMeasuredBpm > 0) {
                        sendHeartRateToPhone(lastMeasuredBpm)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDataChanged exception", e)
        } finally {
            dataEvents.release()
        }
    }

    // MessageClient listener - incoming messages from phone
    override fun onMessageReceived(messageEvent: MessageEvent) {
        try {
            Log.d(TAG, "onMessageReceived path=${messageEvent.path}")
            if (messageEvent.path == "/request_heart_rate") {
                if (lastMeasuredBpm > 0) {
                    sendHeartRateToPhone(lastMeasuredBpm)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onMessageReceived exception", e)
        }
    }

    private fun sendHeartRateToPhone(bpm: Int) {
        try {
            val dataClient = Wearable.getDataClient(applicationContext)
            val request = PutDataMapRequest.create("/heartRate").apply {
                dataMap.putInt("bpm", bpm)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request)
                .addOnSuccessListener { Log.d(TAG, "Heart rate data sent: $bpm") }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed sending heart rate", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending heart rate", e)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "heart_rate_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Heart Rate Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Heart Rate Monitoring")
            .setContentText("Collecting heart rate in background")
            .setSmallIcon(R.drawable.app_logo)
            .build()
    }
}
