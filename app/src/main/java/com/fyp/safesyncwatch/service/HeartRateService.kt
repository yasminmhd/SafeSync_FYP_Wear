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

    // Dedicated thread/handler for sensor callbacks so callbacks can be delivered when main looper is throttled
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Note: wakeLock and startForeground are managed in onStartCommand to be robust with startForegroundService

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

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
        Log.d(TAG, "onStartCommand")

        // Ensure foreground / wake lock are active when service is started
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed in onStartCommand", e)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (wakeLock == null || wakeLock?.isHeld == false) {
            try {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SafeSync:HR")
                // Acquire with a safety timeout (e.g., 10 minutes) to avoid stuck locks
                wakeLock?.acquire(10 * 60 * 1000L)
                Log.d(TAG, "WakeLock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock acquire failed", e)
            }
        }

        // Register sensor for fastest delivery on a dedicated handler thread
        heartRateSensor?.let {
            if (sensorThread == null) {
                sensorThread = android.os.HandlerThread("HeartRateThread").apply { start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }
            try {
                val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0, sensorHandler)
                Log.d(TAG, "registerListener (threaded)=$ok sensor=${it.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering sensor listener on threaded handler", e)
                // Fallback to main looper registration
                try {
                    val ok2 = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                    Log.d(TAG, "registerListener (main)=$ok2 sensor=${it.name}")
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback registerListener failed", ex)
                }
            }
        } ?: run {
            Log.e(TAG, "No heart rate sensor!")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering batteryReceiver", e)
        }
        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sensor", e)
        }
        try {
            sensorHandler?.looper?.quitSafely()
            sensorThread = null
            sensorHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sensor thread", e)
        }
        handler.removeCallbacks(periodicSender)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock", e)
        }
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
                try {
                    saveHeartRateEntry(now, bpm)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed saving heart rate entry", e)
                }

                // Broadcast an update so UI (if active) can update immediately
                try {
                    val update = Intent("com.fyp.safesyncwatch.HEART_RATE_UPDATED").apply {
                        putExtra("bpm", bpm)
                        putExtra("timestamp", now)
                    }
                    sendBroadcast(update)
                    Log.d(TAG, "Broadcasted HEART_RATE_UPDATED bpm=$bpm ts=$now")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed broadcasting HR update", e)
                }

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
            val timestamp = System.currentTimeMillis()
            // Primary: DataClient (existing)
            val dataClient = Wearable.getDataClient(applicationContext)
            val request = PutDataMapRequest.create("/heartRate").apply {
                dataMap.putInt("bpm", bpm)
                dataMap.putLong("timestamp", timestamp)
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request)
                .addOnSuccessListener { Log.d(TAG, "Heart rate data sent via DataApi: $bpm") }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed sending heart rate via DataApi", e)
                    // On failure, try message fallback
                    trySendHeartRateViaMessage(bpm, timestamp)
                }

            // Also attempt message fallback in parallel for lower-latency delivery
            trySendHeartRateViaMessage(bpm, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending heart rate", e)
        }
    }

    private fun trySendHeartRateViaMessage(bpm: Int, timestamp: Long) {
        try {
            val messageClient = Wearable.getMessageClient(applicationContext)
            // Discover connected nodes and send message to each
            Wearable.getNodeClient(applicationContext).connectedNodes
                .addOnSuccessListener { nodes ->
                    val payload = "$bpm|$timestamp".toByteArray()
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/heartRate", payload)
                            .addOnSuccessListener { Log.d(TAG, "MessageApi sent to ${node.displayName}: $bpm") }
                            .addOnFailureListener { e -> Log.e(TAG, "MessageApi failed to ${node.displayName}", e) }
                    }
                }.addOnFailureListener { e -> Log.e(TAG, "Failed to get connected nodes", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in trySendHeartRateViaMessage", e)
        }
    }

    /**
     * Persist a single heart rate entry to the same shared-preferences key used by MainActivity
     * Format: history_v2 = "ts,bpm;ts,bpm;..."
     */
    private fun saveHeartRateEntry(timestamp: Long, bpm: Int) {
        try {
            val prefs = getSharedPreferences("heart_rate_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getString("history_v2", "") ?: ""
            val entry = "${timestamp},${bpm}"
            val updated = if (existing.isBlank()) entry else existing + ";" + entry
            prefs.edit().putString("history_v2", updated).apply()
            Log.d(TAG, "Saved HR entry to prefs: $entry")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving HR entry to prefs", e)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "heart_rate_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Heart Rate Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Heart Rate Monitoring")
            .setContentText("Collecting heart rate in background")
            .setSmallIcon(R.drawable.app_logo)
            .setOngoing(true)
            .build()
    }
}
