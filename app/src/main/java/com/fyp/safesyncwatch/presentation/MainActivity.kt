package com.fyp.safesyncwatch.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.fyp.safesyncwatch.theme.PulseHeartView
import com.fyp.safesyncwatch.theme.SafeSyncWatchTheme
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import java.time.*
import androidx.compose.material3.Surface
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.annotation.SuppressLint

fun startOfTodayMillis(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}

data class HeartRateEntry(
    val timestamp: Long,
    val bpm: Int
)
class MainActivity : ComponentActivity(), SensorEventListener {
    companion object {
        const val ACTION_HR_UPDATED = "com.fyp.safesyncwatch.HEART_RATE_UPDATED"
    }

    private val hrUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bpm = intent.getIntExtra("bpm", -1)
            val ts = intent.getLongExtra("timestamp", System.currentTimeMillis())
            if (bpm > 0) {
                // Avoid duplicate entries with same timestamp
                if (heartRateHistory.isEmpty() || heartRateHistory.last().timestamp != ts) {
                    heartRateHistory.add(HeartRateEntry(ts, bpm))
                    // keep UI state in sync
                    heartRateBpm = bpm
                    pruneOldHeartRateHistory(startOfTodayMillis())
                    saveHeartRateHistory()
                }
            }
        }
    }

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var heartRateBpm by mutableStateOf(0)
    private var sensorPermissionGranted by mutableStateOf(false)
    private val TAG = "HeartRate"
    private val heartRateHistory = mutableStateListOf<HeartRateEntry>()
    private val handler = Handler(Looper.getMainLooper())
    private var showDetachedDialog by mutableStateOf(false)
    private val bpmTimeoutMs = 30_000L // 30 seconds
    private var lastValidBpmTime by mutableStateOf(0L) // Keep track of the last valid BPM time
    private var emergencyActivated by mutableStateOf(false)
    private var emergencyDismissedAt by mutableStateOf(0L) // avoid immediate re-trigger

    private val requestBackgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                android.util.Log.d(TAG, "BODY_SENSORS_BACKGROUND granted.")
                startHrService()
            } else {
                android.util.Log.e(TAG, "BODY_SENSORS_BACKGROUND denied.")
                // still attempt to start the service on older devices; for API 34+ it's required to have bg permission
                if (android.os.Build.VERSION.SDK_INT < 34) startHrService()
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "BODY_SENSORS permission GRANTED by user.")
                sensorPermissionGranted = true
                initializeAndRegisterSensor()

                // Request background sensors permission explicitly on Android 34+
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    // launch the background permission flow
                    requestBackgroundPermissionLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
                } else {
                    startHrService()
                }
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
        loadHeartRateHistory()
        setContent {
            SafeSyncWatchTheme {
                if (!sensorPermissionGranted) { RequestHeartRatePermission() }

                WearRoot(
                    heartRateList = heartRateHistory.toList(),
                    latestBpm = heartRateBpm,
                    permissionGranted = sensorPermissionGranted,
                    emergencyRequested = emergencyActivated,
                    onEmergencyHandled = {
                        // Send emergency to phone so phone app can proceed, then clear activation
                        try {
                            sendEmergencyToPhone(heartRateBpm)
                        } catch (e: Exception) {
                            Log.e(TAG, "sendEmergencyToPhone failed", e)
                        }

                        emergencyActivated = false
                        emergencyDismissedAt = System.currentTimeMillis()
                    },
                    onRequestBatteryExemption = { requestIgnoreBatteryOptimizations() }
                )

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
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        startHrService()
                    } else {
                        startHrService()
                    }
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
            heartRateBpm = -1 // Indicates permission issue
            return
        }
        if (heartRateSensor == null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        }
        if (heartRateSensor == null) {
            Log.e(TAG, "Heart rate sensor NOT AVAILABLE on this device.")
            heartRateBpm = -2 // Indicates sensor N/A
        } else {
            Log.d(TAG, "Heart rate sensor found: ${heartRateSensor?.name}. Registering listener.")
            val registered = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (registered) {
                Log.d(TAG, "Heart rate listener registered successfully.")
                // Start the timeout check immediately after registering
                handler.removeCallbacks(bpmTimeoutRunnable)
                handler.postDelayed(bpmTimeoutRunnable, bpmTimeoutMs)
                lastValidBpmTime = System.currentTimeMillis() // Initialize time
            } else {
                Log.e(TAG, "Failed to register heart rate listener.")
                heartRateBpm = -2 // Treat as sensor N/A if registration fails
            }
        }
    }

    private val bpmTimeoutRunnable = Runnable {
        // Only trigger if no new BPM has come in since the timeout was scheduled
        if (System.currentTimeMillis() - lastValidBpmTime >= bpmTimeoutMs) {
            heartRateBpm = 0
            Toast.makeText(
                this,
                "No heart rate detected. Please ensure the watch is properly worn.",
                Toast.LENGTH_LONG
            ).show()
            Log.d(TAG, "BPM Timeout! Setting BPM to 0 and showing Toast.")
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
                        Log.d(TAG, "SensorLifecycleEffect: ON_RESUME")
                        if (sensorPermissionGranted) {
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        Log.d(TAG, "SensorLifecycleEffect: ON_PAUSE")
                    }
                    else -> { /* Do nothing */ }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                Log.d(TAG, "SensorLifecycleEffect onDispose")
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            val currentTime = System.currentTimeMillis()
            if (bpm > 0) {
                heartRateBpm = bpm
                if (bpm > 140 && !emergencyActivated && currentTime - emergencyDismissedAt > TimeUnit.SECONDS.toMillis(30)) {
                    emergencyActivated = true
                }
                if (heartRateHistory.isEmpty() || heartRateHistory.last().bpm != bpm) {
                    heartRateHistory.add(HeartRateEntry(currentTime, bpm))
                    pruneOldHeartRateHistory(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48))
                    saveHeartRateHistory()
                }
                sendHeartRateToPhone(bpm)
                lastValidBpmTime = currentTime
                handler.removeCallbacks(bpmTimeoutRunnable)
                handler.postDelayed(bpmTimeoutRunnable, bpmTimeoutMs)
            } else if (bpm == 0 && heartRateBpm > 0) {
                handler.removeCallbacks(bpmTimeoutRunnable)
                handler.postDelayed(bpmTimeoutRunnable, bpmTimeoutMs)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        pruneOldHeartRateHistory(startOfTodayMillis())
        if (sensorPermissionGranted) initializeAndRegisterSensor()
        // Register receiver to update graph in real-time
        val filter = IntentFilter(ACTION_HR_UPDATED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // mark as not exported for unprotected local broadcasts
            registerReceiver(hrUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // older devices: lint suppressed above because we intentionally use the older overload
            registerReceiver(hrUpdateReceiver, filter)
        }

        // Load history saved by the background service while activity was paused / screen-off
        try {
            loadHeartRateHistory()
            // Ensure we only show today's data on resume
            pruneOldHeartRateHistory(startOfTodayMillis())
            if (heartRateHistory.isNotEmpty()) {
                heartRateBpm = heartRateHistory.last().bpm
            }
            Log.d(TAG, "onResume: Loaded ${heartRateHistory.size} history entries from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "onResume: failed to load heart rate history", e)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Lifecycle.Event.ON_PAUSE from Activity")
        unregisterSensorListener()
        handler.removeCallbacks(bpmTimeoutRunnable)
        // Unregister receiver
        try { unregisterReceiver(hrUpdateReceiver) } catch (_: Exception) { }
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
        Log.e("WEAR_SEND_DEBUG", "!!!! Attempting to SEND BPM ($bpm) to phone. Timestamp: ${System.currentTimeMillis()} !!!!")
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

    // Send an emergency notification/payload to phone via DataClient + MessageClient
    private fun sendEmergencyToPhone(bpm: Int) {
        val timestamp = System.currentTimeMillis()
        val safeBpm = if (bpm > 0) bpm else -1
        Log.d(TAG, "Preparing to send EMERGENCY to phone. bpm=$safeBpm ts=$timestamp")

        // 1) Put a DataItem so phone can observe even if message delivery fails
        try {
            val dataClient = Wearable.getDataClient(this)
            val putRequest = PutDataMapRequest.create("/emergency").apply {
                dataMap.putLong("timestamp", timestamp)
                dataMap.putInt("bpm", safeBpm)
                dataMap.putString("source", "watch")
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(putRequest)
                .addOnSuccessListener { Log.d(TAG, "Emergency DataItem sent") }
                .addOnFailureListener { e -> Log.e(TAG, "Emergency DataItem failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while putting emergency DataItem", e)
        }

        // 2) Send a low-latency Message to connected nodes so phone can act immediately
        try {
            val payload = "{\"type\":\"emergency\",\"timestamp\":$timestamp,\"bpm\":$safeBpm}".toByteArray()
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (nodes.isEmpty()) {
                        Log.w(TAG, "No connected nodes to send emergency message to")
                    }
                    for (node in nodes) {
                        Wearable.getMessageClient(this).sendMessage(node.id, "/emergency", payload)
                            .addOnSuccessListener { Log.d(TAG, "Emergency message sent to ${node.displayName}") }
                            .addOnFailureListener { e -> Log.e(TAG, "Failed sending emergency message to ${node.displayName}", e) }
                    }
                }
                .addOnFailureListener { e -> Log.e(TAG, "Failed getting connected nodes for emergency", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending emergency message", e)
        }
    }

    private fun saveHeartRateHistory() {
        val prefs = getSharedPreferences("heart_rate_prefs", Context.MODE_PRIVATE)
        val historyString = heartRateHistory.joinToString(";") { "${it.timestamp},${it.bpm}" }
        prefs.edit().putString("history_v2", historyString).apply() // Use a new key for the new format
        Log.d(TAG, "Saved history: $historyString")
    }

    private fun loadHeartRateHistory() {
        val prefs = getSharedPreferences("heart_rate_prefs", Context.MODE_PRIVATE)
        val historyString = prefs.getString("history_v2", "") ?: ""
        heartRateHistory.clear()
        if (historyString.isNotEmpty()) {
            try {
                val entries = historyString.split(";").mapNotNull { raw ->
                    val parts = raw.trim().split(",")
                    if (parts.size != 2) return@mapNotNull null
                    val ts = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                    val bpm = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                    HeartRateEntry(ts, bpm)
                }
                heartRateHistory.addAll(entries)
                // Prune old data on load as well (keep last 48 hours as elsewhere)
                pruneOldHeartRateHistory(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48))
            } catch (e: Exception) {
                Log.e(TAG, "Error loading heart rate history", e)
            }
        }
        Log.d(TAG, "Loaded history items: ${heartRateHistory.size}")
    }

    //remove data older than a certain point
    private fun pruneOldHeartRateHistory(thresholdTimestamp: Long) {
        val removed = heartRateHistory.removeAll { it.timestamp < thresholdTimestamp }
        if (removed) Log.d(TAG, "Pruned old heart rate entries. Kept: ${heartRateHistory.size}")
    }

    private fun startHrService() {
        val i = Intent(this, com.fyp.safesyncwatch.service.HeartRateService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun stopHrService() {
        val i = Intent(this, com.fyp.safesyncwatch.service.HeartRateService::class.java)
        stopService(i)
    }

}
data class BinnedPoint(
    val tStart: Long,
    val tCenter: Long,
    val min: Int,
    val median: Int,
    val max: Int,
    val count: Int
)

fun binHeartRate(
    data: List<HeartRateEntry>,
    windowStartMs: Long,
    windowEndMs: Long,
    binSizeMs: Long
): List<BinnedPoint> {
    if (windowEndMs <= windowStartMs) return emptyList()

    //Snap bin grid to the window start so x axis line up
    val bStart = windowStartMs

    val buckets = mutableMapOf<Long, MutableList<Int>>()
    data.forEach { e ->
        if (e.timestamp in windowStartMs..windowEndMs) {
            //Bin key measured from windowStartMs (no modulo shift)
            val offset = e.timestamp - bStart
            val key = bStart + (offset / binSizeMs) * binSizeMs
            buckets.getOrPut(key) { mutableListOf() }.add(e.bpm)
        }
    }

    val out = mutableListOf<BinnedPoint>()
    var cursor = bStart
    while (cursor < windowEndMs) {
        val list = buckets[cursor]
        if (!list.isNullOrEmpty()) {
            val sorted = list.sorted()
            val sz = sorted.size
            val med = if (sz % 2 == 1) sorted[sz / 2] else ((sorted[sz / 2 - 1] + sorted[sz / 2]) / 2)
            out.add(
                BinnedPoint(
                    tStart = cursor,
                    tCenter = cursor + binSizeMs / 2,
                    min = sorted.first(),
                    median = med,
                    max = sorted.last(),
                    count = sz
                )
            )
        }
        cursor += binSizeMs
    }
    return out
}

fun ema(values: List<Float>, alpha: Float = 0.3f): List<Float> {
    if (values.isEmpty()) return values
    val out = MutableList(values.size) { 0f }
    out[0] = values[0]
    for (i in 1 until values.size) {
        out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
    }
    return out
}

@Composable
fun HeartbeatScreen(bpm: Int, permissionGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(15.dp))

        PulseHeartViewCompose()

        Spacer(Modifier.height(16.dp))

        val displayText = when {
            !permissionGranted && bpm == -1 -> "Permission Denied"
            bpm == -2 -> "Sensor N/A"
            bpm == 0 && permissionGranted -> "... BpM"
            bpm == 0 && !permissionGranted -> "Requesting Permission..."
            bpm > 0 && permissionGranted -> "$bpm BpM"
            else -> "-- BpM"
        }

        Text(
            text = displayText,
            style = MaterialTheme.typography.title1,
            color = when {
                bpm > 140 && permissionGranted -> Color(0xFFB00020)
                bpm > 0 && permissionGranted -> Color.Black
                else -> Color.LightGray
            }
        )
    }
}

@Composable
fun HeartRateChartScreen(heartRateList: List<HeartRateEntry>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeartRateTimeChart(heartRateList)
    }
}

@Composable
fun PulseHeartViewCompose() {
    AndroidView(
        factory = { context -> PulseHeartView(context) },
        modifier = Modifier.size(72.dp)
    )
}

@Composable
fun HeartRateTimeChart(heartRateData: List<HeartRateEntry>) {
    val chartHeightDp = 180.dp
    val chartWidthDp = LocalConfiguration.current.screenWidthDp.dp - 32.dp

    val now = System.currentTimeMillis()
    val windowEnd = now
    val windowStart = now - TimeUnit.HOURS.toMillis(1) // last hour
    val durationMs = (windowEnd - windowStart).coerceAtLeast(1L)

    val points = remember(heartRateData, now) {
        heartRateData
            .filter { it.timestamp in windowStart..windowEnd }
            .sortedBy { it.timestamp }
    }

    if (points.isEmpty()) {
        Box(
            modifier = Modifier
                .height(chartHeightDp)
                .width(chartWidthDp)
                .padding(8.dp)
                .background(Color.DarkGray.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) { Text("No data in last hour", color = Color.LightGray) }
        return
    }

    val bpmSeries = remember(points) { points.map { it.bpm.toFloat() } }
    val smoothed = remember(bpmSeries) { ema(bpmSeries, alpha = 0.25f) }

    // Determine visual bounds with some breathing room
    val maxBpm = maxOf((points.maxOf { it.bpm }), 40)
    val minBpm = minOf((points.minOf { it.bpm }), maxBpm - 20).coerceAtLeast(30)
    val bpmRange = (maxBpm - minBpm).coerceAtLeast(1).toFloat()

    val maxGapMs = TimeUnit.MINUTES.toMillis(2) // split the line if gap > 2 minutes

    Box(
        modifier = Modifier
            .height(chartHeightDp)
            .width(chartWidthDp)
            .padding(vertical = 8.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val bottomPad = 36.dp.toPx()
            val leftPad = 35.dp.toPx()
            val topPad = 50.dp.toPx()
            val rightPad = 14.dp.toPx()

            val graphH = size.height - bottomPad - topPad
            val graphW = size.width - leftPad - rightPad

            fun xAt(t: Long): Float {
                val f = (t - windowStart).toFloat() / durationMs.toFloat()
                return leftPad + (f * graphW).coerceIn(0f, graphW)
            }
            fun yAt(bpm: Float): Float {
                val f = (bpm - minBpm) / bpmRange
                return topPad + graphH - (f * graphH).coerceIn(0f, graphH)
            }

            // Axes
            drawLine(Color.Gray, Offset(leftPad, topPad + graphH), Offset(leftPad + graphW, topPad + graphH), 2f)
            drawLine(Color.Gray, Offset(leftPad, topPad), Offset(leftPad, topPad + graphH), 2f)

            // Build contiguous segments (split on big gaps)
            val segments = mutableListOf<MutableList<Int>>()
            var current = mutableListOf<Int>()
            for (i in points.indices) {
                if (i == 0) {
                    current.add(i)
                } else {
                    val gap = points[i].timestamp - points[i - 1].timestamp
                    if (gap > maxGapMs) {
                        segments.add(current)
                        current = mutableListOf(i)
                    } else {
                        current.add(i)
                    }
                }
            }
            if (current.isNotEmpty()) segments.add(current)

            // Draw fills and smoothed lines per segment (no point markers)
            segments.forEach { seg ->
                if (seg.size < 1) return@forEach

                // Fill path (smoothed series)
                val fillPath = Path()
                val firstIdx = seg.first()
                val firstX = xAt(points[firstIdx].timestamp)
                val firstY = yAt(smoothed[firstIdx])
                fillPath.moveTo(firstX, firstY)
                for (idx in seg.drop(1)) {
                    fillPath.lineTo(xAt(points[idx].timestamp), yAt(smoothed[idx]))
                }
                // close to baseline
                val lastIdx = seg.last()
                fillPath.lineTo(xAt(points[lastIdx].timestamp), topPad + graphH) // baseline at bottom
                fillPath.lineTo(firstX, topPad + graphH)
                fillPath.close()
                drawPath(fillPath, color = Color.Red.copy(alpha = 0.12f))

                // Line path (smoothed) - rounded caps for smooth appearance
                val linePath = Path()
                linePath.moveTo(firstX, firstY)
                for (idx in seg.drop(1)) {
                    linePath.lineTo(xAt(points[idx].timestamp), yAt(smoothed[idx]))
                }
                drawPath(
                    linePath,
                    color = Color.Red,
                    style = Stroke(width = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }

            // Y ticks and labels
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 20f
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
                val yTicks = 4
                for (i in 0..yTicks) {
                    val v = minBpm + (bpmRange * (i.toFloat() / yTicks.toFloat()))
                    val y = yAt(v)
                    drawLine(Color.Gray, Offset(leftPad - 6f, y), Offset(leftPad, y), 2f)
                    c.nativeCanvas.drawText(v.toInt().toString(), leftPad - 10f, y + (paint.textSize * 0.35f), paint)
                }
                val labelPaint = android.graphics.Paint(paint).apply { textAlign = android.graphics.Paint.Align.CENTER }
                c.nativeCanvas.drawText("BPM", leftPad - 30f, topPad - 18f, labelPaint)
            }

            // X ticks for last hour at 0,15,30,45,60 minutes
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 16f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                intArrayOf(60, 45, 30, 15, 0).forEach { labelMinutes ->
                    val offsetMin = (60 - labelMinutes).toLong()
                    val t = windowStart + TimeUnit.MINUTES.toMillis(offsetMin)
                    val x = xAt(t)
                    drawLine(Color.Gray, Offset(x, topPad + graphH), Offset(x, topPad + graphH + 6f), 2f)
                    val text = "${labelMinutes}m"
                    c.nativeCanvas.drawText(text, x, topPad + graphH + 22f, paint)
                }
                val labelPaint = android.graphics.Paint(paint)
                c.nativeCanvas.drawText("Minutes ago (last hour)", leftPad + graphW / 2f, topPad + graphH + 42f, labelPaint)
            }
        }
    }
}

@Composable
fun EmergencyAlert(onCancel: () -> Unit) {
    // Fullscreen semi-transparent overlay with a centered card and a Cancel button
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .wrapContentHeight(),
            color = Color.White,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Emergency activated", style = MaterialTheme.typography.title2, color = Color(0xFFB00020))
                Text("High heart rate detected. Tap Cancel to dismiss.", style = MaterialTheme.typography.body2, color = Color.Black)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.wear.compose.material.Button(
                        onClick = onCancel,
                        modifier = Modifier.size(width = 110.dp, height = 40.dp),
                        colors = androidx.wear.compose.material.ButtonDefaults.primaryButtonColors(
                            backgroundColor = Color.LightGray,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
