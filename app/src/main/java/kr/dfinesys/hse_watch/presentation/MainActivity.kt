package kr.dfinesys.hse_watch.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.dfinesys.hse_watch.core.CollapseForegroundService
import kr.dfinesys.hse_watch.core.NotificationHelper
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.fcmService.HseFcmService
import kr.dfinesys.hse_watch.presentation.theme.Hse_watchTheme
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

class MainActivity : ComponentActivity() {
    private var isSyncing = false
    private var accessState by mutableStateOf(Api.AccessState(false))

    /**
     * FCM 상태 변경 브로드캐스트 수신기 (ASSIGNED / INSIDE / OUTSIDE)
     * HseFcmService → MainActivity로 상태를 전달받아 UI와 서비스를 즉시 갱신한다.
     */
    private val accessStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type        = intent.getStringExtra("type") ?: return
            val companyName = intent.getStringExtra("companyName") ?: ""
            val userName    = intent.getStringExtra("userName") ?: ""

            val state = Api.AccessState(
                isInside   = type == "INSIDE",
                isAssigned = type == "ASSIGNED",
                companyName = companyName,
                userName    = userName
            )
            Log.d("MainActivity", "FCM 상태 수신: $type")
            applyAccessState(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 위험 경보 FCM 수신 시 화면을 켜기 위한 window flag
        // (SCREEN_BRIGHT_WAKE_LOCK deprecated 대체)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Android 13 이상 알림/위치 권한 런타임 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                100
            )
        }

        NotificationHelper.createChannel(this)

        val watchId = getOrCreateDeviceId(this)
        Log.d("TEST", "데이터 전송: $watchId")

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
            val isRegistered = prefs.getBoolean("is_registered", false)

            if (!isRegistered) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        Api.registerDevice(watchId, token)
                        prefs.edit().putBoolean("is_registered", true).apply()
                        withContext(Dispatchers.Main) { syncStateFromServer(watchId) }
                    }
                }
            } else {
                syncStateFromServer(watchId)
            }
        }

        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            Hse_watchTheme {
                MonitorScreen(
                    watchId     = watchId,
                    companyName = accessState.companyName,
                    userName    = accessState.userName
                )
            }
        }

        handleIntent(intent, watchId)
    }

    override fun onResume() {
        super.onResume()

        // FCM 상태 변경 브로드캐스트 수신 등록
        LocalBroadcastManager.getInstance(this).registerReceiver(
            accessStateReceiver,
            IntentFilter(HseFcmService.ACTION_ACCESS_STATE)
        )

        val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_registered", false)) {
            syncStateFromServer(getOrCreateDeviceId(this))
        }
    }

    override fun onPause() {
        super.onPause()
        // 화면을 벗어날 때 수신기 해제
        LocalBroadcastManager.getInstance(this).unregisterReceiver(accessStateReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, getOrCreateDeviceId(this))
    }

    private fun handleNfcEvent(watchId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val state = Api.handleNfc(watchId)
            withContext(Dispatchers.Main) { applyAccessState(state) }
        }
    }

    /**
     * 상태에 따라 화면 유지 플래그와 쓰러짐 감지 서비스를 제어한다.
     *
     * ASSIGNED  → 화면 ON  + 감지 OFF  (NFC 태그 대기)
     * INSIDE    → 화면 OFF + 감지 ON   (입장 후 쓰러짐 감지)
     * 그 외     → 화면 OFF + 감지 OFF
     */
    private fun applyAccessState(state: Api.AccessState) {
        accessState = state
        getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_inside", state.isInside).apply()

        when {
            state.isInside -> {
                Log.d("STATE", "입실 → 감지 시작 / ${state.companyName} / ${state.userName}")
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                CollapseForegroundService.start(this)
            }
            state.isAssigned -> {
                Log.d("STATE", "배정됨 → 화면 유지 (NFC 태그 대기)")
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                CollapseForegroundService.stop(this)
            }
            else -> {
                Log.d("STATE", "퇴실/미배정 → 감지 종료")
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                CollapseForegroundService.stop(this)
            }
        }
    }

    private fun handleIntent(intent: Intent, watchId: String) {
        if (intent.action == "android.nfc.action.NDEF_DISCOVERED") {
            handleNfcEvent(watchId)
        }
    }

    private fun syncStateFromServer(watchId: String) {
        if (isSyncing) return
        isSyncing = true
        CoroutineScope(Dispatchers.IO).launch {
            val state = Api.getAccessState(watchId)
            withContext(Dispatchers.Main) {
                applyAccessState(state)
                isSyncing = false
            }
        }
    }
}