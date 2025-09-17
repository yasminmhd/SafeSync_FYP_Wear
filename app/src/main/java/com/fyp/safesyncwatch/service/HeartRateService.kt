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

class HeartRateService : Service(), SensorEventListener {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            // Send battery level to phone
            val dataClient = Wearable.getDataClient(context)
            val request = PutDataMapRequest.create("/batteryLevel").apply {
                dataMap.putInt("battery", batteryPct)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("HR_SERVICE", "onCreate")

        // optional wakelock
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SafeSync:HR").apply { acquire() }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        startForeground(1, createNotification())
        android.util.Log.d("HR_SERVICE", "startForeground done")

        heartRateSensor?.let {
            val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            android.util.Log.d("HR_SERVICE", "registerListener=$ok sensor=${it.name}")
        } ?: run {
            android.util.Log.e("HR_SERVICE", "No heart rate sensor!")
        }
    }

    override fun onDestroy() {
        android.util.Log.d("HR_SERVICE", "onDestroy")
        unregisterReceiver(batteryReceiver)
        sensorManager.unregisterListener(this)
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            android.util.Log.d("HR_SERVICE", "HR event: $bpm")
            android.util.Log.d("HR_WATCH", "HR event in bg: $bpm @${System.currentTimeMillis()}")
            if (bpm > 0) {
                sendHeartRateToPhone(bpm)
                broadcastHeartRate(bpm)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun broadcastHeartRate(bpm: Int) {
        val intent = Intent("com.fyp.safesyncwatch.HEART_RATE_UPDATE")
        intent.putExtra("bpm", bpm)
        sendBroadcast(intent)
    }

    private fun sendHeartRateToPhone(bpm: Int) {
        val dataClient = Wearable.getDataClient(this)
        val request = PutDataMapRequest.create("/heartRate").apply {
            dataMap.putInt("bpm", bpm)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
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
            .build()
    }
}
