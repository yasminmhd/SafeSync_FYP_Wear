package com.fyp.safesyncwatch.presentation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.fyp.safesyncwatch.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import android.widget.Toast
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import android.util.Log


sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Heartbeat : Screen("heartbeat")
    data object Chart     : Screen("chart")
    data object SOS       : Screen("sos")
}

@Composable
fun WearRoot(
    heartRateList: List<HeartRateEntry>,
    latestBpm: Int,
    permissionGranted: Boolean,
    emergencyRequested: Boolean,
    onEmergencyHandled: () -> Unit,
) {
    val nav = rememberNavController()

    // When emergencyRequested becomes true navigate to SOS but DO NOT clear the request here.
    LaunchedEffect(emergencyRequested) {
        if (emergencyRequested) {
            try {
                // avoid repeated navigation if already on SOS
                if (nav.currentBackStackEntry?.destination?.route != Screen.SOS.route) {
                    nav.navigate(Screen.SOS.route)
                }
            } catch (_: Exception) { /* ignore navigation errors */ }
            // do NOT call onEmergencyHandled() here — wait until SOS screen finishes (cancel or confirm)
        }
    }

    NavHost(navController = nav, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeHub(
                onHeartbeat = { nav.navigate(Screen.Heartbeat.route) },
                onChart  = { nav.navigate(Screen.Chart.route) },
                onEmergency = { nav.navigate(Screen.SOS.route) }
            )
        }
        composable(Screen.Heartbeat.route) {
            HeartbeatScreen(
                bpm = latestBpm,
                permissionGranted = permissionGranted,
            )
        }
        composable(Screen.Chart.route) {
            HeartRateChartScreen(heartRateList)
        }
        // pass the handler to SOS so it can clear the emergency flag when user cancels or when activated
        composable(Screen.SOS.route) {
            SosHelpScreen(
                onEmergencyConfirmed = { onEmergencyHandled() },
                onCancel = { onEmergencyHandled() }
            )
        }
    }
}

@Composable
fun HomeHub(
    onHeartbeat: () -> Unit,
    onEmergency: () -> Unit,
    onChart: () -> Unit,
) {
    val Side  = Color(0xFFFFE5E5)
    val Mid   = Color(0xFFAB1020)
    val Icon  = Color(0xFF801E1E)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        timeText = {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                TimeText()
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .padding(bottom = 12.dp, top = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            //logo + small title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.app_icon),
                    contentDescription = "SafeSync logo",
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "SafeSync",
                    style = MaterialTheme.typography.title3,
                    color = Color(0xFF222222),
                    textAlign = TextAlign.Center
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                //LEFT(Heart)
                Surface(
                    modifier = Modifier
                        .width(62.dp)
                        .height(75.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .clickable(onClick = onHeartbeat),
                    color = Side
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Heart rate",
                            tint = Icon,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                //CENTER(Emergency)
                Surface(
                    modifier = Modifier
                        .width(62.dp)
                        .height(75.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .clickable(onClick = onEmergency),
                    color = Mid,
                    contentColor = Color.White
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Emergency",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                //RIGHT(Graph)
                Surface(
                    modifier = Modifier
                        .width(62.dp)
                        .height(75.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .clickable(onClick = onChart),
                    color = Side
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = "Chart",
                            tint = Icon,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SosHelpScreen(
    onEmergencyConfirmed: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // pulsing animation
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    var counting by remember { mutableStateOf(true) }
    var secondsLeft by remember { mutableStateOf(10) }
    var finished by remember { mutableStateOf(false) }

    // countdown coroutine
    LaunchedEffect(key1 = counting) {
        if (!counting) return@LaunchedEffect
        while (secondsLeft > 0 && counting) {
            delay(1000L)
            secondsLeft -= 1
        }
        if (secondsLeft <= 0 && counting) {
            counting = false
            finished = true
            Toast.makeText(context, "Emergency ACTIVATED", Toast.LENGTH_LONG).show()
            try { onEmergencyConfirmed() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(secondsLeft, counting) {
        if (counting) {
            vibrator?.let { vib ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(120)
                    }
                } catch (e: Exception) {
                    Log.e("SOS_VIBRATE", "Failed to vibrate", e)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        //halo effect
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(scale)
                    .alpha(haloAlpha)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Color(0xFFFFCDD2))
            )

            //sos red button
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Color(0xFFB00020))
                    .clickable(enabled = counting) {
                        // tapping cancels the pending emergency and notify host to suppress re-trigger
                        counting = false
                        finished = false
                        try { onCancel() } catch (_: Exception) {}
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (counting) "SOS\n${secondsLeft}s" else if (finished) "Activated" else "Cancelled",
                    textAlign = TextAlign.Center,
                    style = androidx.wear.compose.material.MaterialTheme.typography.title2,
                    color = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (counting) {
                Text("Tap to cancel", style = androidx.wear.compose.material.MaterialTheme.typography.body2, color = Color.Gray)
            } else if (finished) {
                Text("Emergency mode", style = androidx.wear.compose.material.MaterialTheme.typography.body2, color = Color.Red)
            } else {
                Text("", style = androidx.wear.compose.material.MaterialTheme.typography.body2, color = Color.Gray)
            }
        }
    }
}