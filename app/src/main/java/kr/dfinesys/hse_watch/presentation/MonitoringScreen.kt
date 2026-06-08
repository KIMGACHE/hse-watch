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

/**
 * 워치 메인 모니터링 화면
 * - 가속도 센서 X/Y/Z 값을 실시간으로 표시
 * - 쓰러짐 감지 시 경고 UI로 전환되며, 화면 탭으로 경고를 해제할 수 있음
 *
 * @param watchId 이 기기의 고유 디바이스 ID
 */
@Composable
fun MonitorScreen(watchId: String,
                  companyName: String,
                  userName: String) {
    val context = LocalContext.current
    // 원형 화면 여부에 따라 좌우 패딩 조정
    val sidePad = if (LocalConfiguration.current.isScreenRound) 20.dp else 12.dp

    // 경고 상태 및 센서 값 상태 변수
    var warning by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf(0) }
    var y by remember { mutableStateOf(0) }
    var z by remember { mutableStateOf(0) }

    // Composable이 활성화된 동안 BroadcastReceiver를 등록하고, 해제 시 자동으로 unregister
    DisposableEffect(Unit) {
        // CollapseForegroundService → UI: 경고 상태 변경 수신
        val warningReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                warning = intent.getBooleanExtra("warning", false)
            }
        }
        // CollapseForegroundService → UI: 실시간 센서 값 수신
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
            // Composable이 사라질 때 메모리 누수 방지를 위해 수신자 해제
            LocalBroadcastManager.getInstance(context).apply {
                unregisterReceiver(warningReceiver)
                unregisterReceiver(sensorReceiver)
            }
        }
    }

    // 사용자가 경고 카드를 탭했을 때 호출 — 경고를 해제하고 감지 서비스를 재시작
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
                // 경고 여부에 따라 카드 배경 그라디언트 색상 변경 (경고: 붉은 계열, 정상: 흰색 반투명)
                val cardBrush = if (warning)
                    Brush.verticalGradient(listOf(Color(0x55FF4D4D), Color(0x33FF0000)))
                else
                    Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x11000000)))

                // 경고 여부에 따라 타이틀 색상 변경
                val titleColor = if (warning) Color(0xFFFF5555) else MaterialTheme.colors.primary

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBrush)
                        // 경고 상태일 때만 탭 클릭 이벤트 활성화
                        .clickable(enabled = warning) { dismissWarningAndRestart() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 업체명 + 사용자명 표시 (입실 상태일 때만 표시하거나 항상 표시 가능)
                    if (companyName.isNotEmpty() || userName.isNotEmpty()) {
                        Text(
                            text = "$companyName  $userName",
                            color = titleColor,
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    Text(
                        text = if (warning) "위험" else "사용자 위험 감지",
                        color = Color(0xCCFFFFFF),
                        style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(10.dp))

                    // X/Y/Z 센서 값을 각각 pill 형태로 표시
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatPill(label = "X", value = x, warning = warning, modifier = Modifier.weight(1f))
                        StatPill(label = "Y", value = y, warning = warning, modifier = Modifier.weight(1f))
                        StatPill(label = "Z", value = z, warning = warning, modifier = Modifier.weight(1f))
                    }

                    // 경고 상태에서만 해제 안내 문구 표시
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

/**
 * 센서 축 하나의 값을 표시하는 작은 카드 컴포넌트
 *
 * @param label 축 이름 ("X", "Y", "Z")
 * @param value 해당 축의 현재 센서 정수값
 * @param warning 경고 상태 여부 (배경색 변경에 사용)
 * @param modifier 외부에서 전달받는 레이아웃 수정자
 */
@Composable
private fun StatPill(
    label: String,
    value: Int,
    warning: Boolean,
    modifier: Modifier = Modifier
) {
    // 경고 시 붉은 반투명 배경, 정상 시 흰색 반투명 배경
    val pillBg = if (warning) Color(0x33FF0000) else Color(0x22FFFFFF)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pillBg)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 축 레이블 (X / Y / Z)
        Text(
            text = label,
            color = Color(0xCCFFFFFF),
            style = MaterialTheme.typography.caption2,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Spacer(Modifier.height(2.dp))
        // 센서 수치
        Text(
            text = value.toString(),
            color = Color.White,
            style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}