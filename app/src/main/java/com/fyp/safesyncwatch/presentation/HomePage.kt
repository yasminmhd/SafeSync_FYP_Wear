package com.fyp.safesyncwatch.presentation

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
    permissionGranted: Boolean
) {
    val nav = rememberNavController()

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
        composable(Screen.SOS.route) { SosHelpScreen() }
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
        timeText = { TimeText() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .padding(bottom = 12.dp, top = 6.dp),
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
                        .height(108.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 28.dp, topEnd = 28.dp,
                                bottomEnd = 28.dp, bottomStart = 40.dp
                            )
                        )
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
                        .height(108.dp)
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
                        .height(108.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 28.dp, topEnd = 28.dp,
                                bottomEnd = 40.dp, bottomStart = 28.dp
                            )
                        )
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
fun SosHelpScreen() {
    val context = LocalContext.current
    val Brand = Color(0xFFF26060)

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = "Need help?",
                    style = MaterialTheme.typography.title2,
                    color = Color(0xFF222222)
                )

                Button(
                    onClick = {
                        android.widget.Toast
                            .makeText(context, "Help is on the way", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                    modifier = Modifier.size(90.dp),
                    colors = ButtonDefaults.primaryButtonColors(
                        backgroundColor = Brand,
                        contentColor = Color.White
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Text("SOS", style = MaterialTheme.typography.title1, color = Color.White)
                }
            }
        }
    }
}

