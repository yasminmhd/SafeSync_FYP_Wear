package com.fyp.safesyncwatch.presentation

//Android & Core Kotlin/Java
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log

//Activity & Lifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

//Jetpack Compose - Animation
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing

// Jetpack Compose - Foundation & Layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size

//Jetpack Compose - Runtime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

//Jetpack Compose - UI
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp

//Wear Compose Material Components (for Wear UI)
import androidx.wear.compose.material.Icon // WEAR Icon
import androidx.wear.compose.material.MaterialTheme // WEAR MaterialTheme
import androidx.wear.compose.material.Text     // WEAR Text

//Material Icons (heart icon vector)
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.input.key.type
import androidx.core.content.ContextCompat

//project imports
import com.fyp.safesyncwatch.presentation.theme.SafeSyncWatchTheme

//Google Play Services for Wearable Data Layer
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var heartRateBpm by mutableStateOf(0)
    private var sensorPermissionGranted by mutableStateOf(false)
    private val TAG = "HeartRate"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "BODY_SENSORS permission GRANTED by user.")
                sensorPermissionGranted = true
                initializeAndRegisterSensor()
            } else {
                Log.e(TAG, "BODY_SENSORS permission DENIED by user.")
                sensorPermissionGranted = false
                heartRateBpm = -1
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setContent {
            SafeSyncWatchTheme {
                if (!sensorPermissionGranted) {
                    RequestHeartRatePermission()
                }
                WearApp(bpm = heartRateBpm, permissionGranted = sensorPermissionGranted)
                SensorLifecycleEffect()
            }
        }
    }

    @Composable
    private fun RequestHeartRatePermission() {
        DisposableEffect(Unit) {
            when (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BODY_SENSORS)) {
                PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "BODY_SENSORS permission already granted (checked in Composable).")
                    sensorPermissionGranted = true
                }
                else -> {
                    Log.d(TAG, "Requesting BODY_SENSORS permission from Composable.")
                    requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                }
            }
            onDispose { }
        }
    }

    private fun initializeAndRegisterSensor() {
        if (!sensorPermissionGranted) {
            Log.w(TAG, "initializeAndRegisterSensor: Permission not granted. Aborting.")
            heartRateBpm = -1
            return
        }
        if (heartRateSensor == null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        }
        if (heartRateSensor == null) {
            Log.e(TAG, "Heart rate sensor NOT AVAILABLE on this device.")
            heartRateBpm = -2
        } else {
            Log.d(TAG, "Heart rate sensor found: ${heartRateSensor?.name}. Registering listener.")
            val registered = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (registered) {
                Log.d(TAG, "Heart rate listener registered successfully.")
            } else {
                Log.e(TAG, "Failed to register heart rate listener.")
            }
        }
    }

    private fun unregisterSensorListener() {
        Log.d(TAG, "Unregistering heart rate listener.")
        sensorManager.unregisterListener(this)
    }

    @Composable
    private fun SensorLifecycleEffect() {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, sensorPermissionGranted) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        Log.d(TAG, "Lifecycle.Event.ON_RESUME")
                        if (sensorPermissionGranted) {
                            initializeAndRegisterSensor()
                        } else {
                            Log.w(TAG, "ON_RESUME: Permission not granted, not registering sensor.")
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        Log.d(TAG, "Lifecycle.Event.ON_PAUSE")
                        unregisterSensorListener()
                    }
                    else -> { /* Do nothing */ }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                Log.d(TAG, "SensorLifecycleEffect onDispose: Removing observer and unregistering listener.")
                lifecycleOwner.lifecycle.removeObserver(observer)
                unregisterSensorListener()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            if (bpm > 0) {
                heartRateBpm = bpm
                Log.d(TAG, "onSensorChanged - BPM: $bpm")
                sendHeartRateToPhone(bpm)
            } else {
                Log.d(TAG, "onSensorChanged - Received BPM: $bpm (possibly no reading or still settling)")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val accuracyStatus = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN ($accuracy)"
        }
        Log.d(TAG, "onAccuracyChanged: ${sensor?.name}, accuracy: $accuracyStatus")
    }

    private fun sendHeartRateToPhone(bpm: Int) {
        Log.e("WEAR_SEND_DEBUG", "!!!! Attempting to SEND BPM ($bpm) to phone. Timestamp: ${System.currentTimeMillis()} !!!!") // Use a unique tag and prominent message
        if (!isFinishing && !isDestroyed) {
            try {
                val dataClient = Wearable.getDataClient(this)
                val request = PutDataMapRequest.create("/heartRate").apply {
                    dataMap.putInt("bpm", bpm)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()

                dataClient.putDataItem(request)
                    .addOnSuccessListener { Log.d("WEAR_SEND_DEBUG", "SUCCESS: Heart rate data ($bpm BPM) sent.") }
                    .addOnFailureListener { e -> Log.e("WEAR_SEND_DEBUG", "FAILURE: Failed to send heart rate data.", e) }
            } catch (e: Exception) {
                Log.e("WEAR_SEND_DEBUG", "EXCEPTION while sending heart rate data", e)
            }
        } else {
            Log.w("WEAR_SEND_DEBUG", "Activity is finishing/destroyed. CANNOT SEND BPM: $bpm")
        }
    }
}
// --- End of MainActivity ---


@Composable
fun WearApp(bpm: Int, permissionGranted: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "heart_beat_transition")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "heart_beat_scale_animation"
        )

        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "Beating Heart Icon",
            modifier = Modifier
                .size(72.dp)
                .scale(scale),
            tint = if (bpm > 0 && permissionGranted) Color.Red else Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Display Text ---
        val displayText = when {
            !permissionGranted && bpm == -1 -> "Permission Denied"
            bpm == -2 -> "Sensor N/A"
            bpm == 0 && permissionGranted && bpm != -1 && bpm != -2 -> "BPM: ..."
            bpm == 0 && !permissionGranted -> "Requesting Permission..."
            bpm > 0 && permissionGranted -> "$bpm BPM"
            else -> "BPM: --"
        }

        Text(
            text = displayText,
            style = MaterialTheme.typography.title1,
            color = if (bpm > 0 && permissionGranted) { //Conditional Color
                Color.Black
            } else if (!permissionGranted && bpm == -1) {
                Color.Red // Different color for "Permission Denied"
            } else {
                Color.LightGray // Default color for other states like ... and wtv
            }
        )
    }
}
