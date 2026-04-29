package kr.dfinesys.hse_watch.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kr.dfinesys.hse_watch.core.CollapseForegroundService

@Composable
fun MonitorScreen(watchId: String) {
    val context = LocalContext.current
    val sidePad = if (LocalConfiguration.current.isScreenRound) 20.dp else 12.dp

    var warning by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    var z by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val warningReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                warning = intent.getBooleanExtra("warning", false)
            }
        }
        val sensorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                x = intent.getIntExtra("x", 0)
                y = intent.getIntExtra("y", 0)
                z = intent.getIntExtra("z", 0)
            }
        }

        LocalBroadcastManager.getInstance(context).apply {
            registerReceiver(warningReceiver, IntentFilter("ACTION_WARNING_STATE"))
            registerReceiver(sensorReceiver, IntentFilter("ACTION_SENSOR_VALUES"))
        }

        onDispose {
            LocalBroadcastManager.getInstance(context).apply {
                unregisterReceiver(warningReceiver)
                unregisterReceiver(sensorReceiver)
            }
        }
    }

    fun dismissWarningAndRestart() {
        warning = false
        val intent = Intent(context, CollapseForegroundService::class.java).apply {
            action = CollapseForegroundService.ACTION_DISMISS
        }
        context.startService(intent)
    }

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(horizontal = sidePad, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val cardBrush = if (warning)
                    Brush.verticalGradient(listOf(Color(0x55FF4D4D), Color(0x33FF0000)))
                else
                    Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x11000000)))

                val titleColor = if (warning) Color(0xFFFF5555) else MaterialTheme.colors.primary

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBrush)
                        .clickable(enabled = warning) { dismissWarningAndRestart() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (warning) "위험" else "사용자 위험 감지",
                        color = titleColor,
                        style = MaterialTheme.typography.title2.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatPill(label = "X", value = x, warning = warning, modifier = Modifier.weight(1f))
                        StatPill(label = "Y", value = y, warning = warning, modifier = Modifier.weight(1f))
                        StatPill(label = "Z", value = z, warning = warning, modifier = Modifier.weight(1f))
                    }

                    if (warning) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "알림 해제: 화면 탭",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: Int,
    warning: Boolean,
    modifier: Modifier = Modifier
) {
    val pillBg = if (warning) Color(0x33FF0000) else Color(0x22FFFFFF)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pillBg)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xCCFFFFFF),
            style = MaterialTheme.typography.caption2,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value.toString(),
            color = Color.White,
            style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}