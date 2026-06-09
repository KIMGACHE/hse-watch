package kr.dfinesys.hse_watch.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.dfinesys.hse_watch.core.CollapseForegroundService
import kr.dfinesys.hse_watch.core.NotificationHelper
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.presentation.theme.Hse_watchTheme
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private var isSyncing = false;
    private var accessState by mutableStateOf(Api.AccessState(false))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Android 13 이상에서는 알림 권한과 위치 권한을 런타임에 명시적으로 요청해야 함
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

        // 알림 채널 초기화 (앱 최초 실행 시 한 번만 생성됨)
        NotificationHelper.createChannel(this)

        // 이 기기의 고유 watchId 가져오기
        val watchId = getOrCreateDeviceId(this)

        Log.d("TEST", "데이터 전송: $watchId")

        // FCM 토큰을 가져와 서버에 기기 등록 (최초 1회만 수행)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
            val isRegistered = prefs.getBoolean("is_registered", false)

            if (!isRegistered) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        Api.registerDevice(watchId, token)

                        prefs.edit()
                            .putBoolean("is_registered", true)
                            .apply()

                        withContext(Dispatchers.Main) {
                            syncStateFromServer(watchId)
                        }
                    }
                }
            } else {
                syncStateFromServer(watchId)
            }
        }

        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            Hse_watchTheme {
                val currentState by remember { mutableStateOf(accessState) }
                MonitorScreen(
                    watchId = watchId,
                    companyName = accessState.companyName,
                    userName = accessState.userName
                )
            }
        }

//        handleFcmIntent(intent)
        handleIntent(intent, watchId)
    }

    override fun onResume() {
        super.onResume()

        val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
        val isRegistered = prefs.getBoolean("is_registered", false)

        if (isRegistered) {
            val watchId = getOrCreateDeviceId(this)
            syncStateFromServer(watchId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val watchId = getOrCreateDeviceId(this)

        // 이미 실행 중인 Activity에 새 Intent가 전달될 때 처리
        handleIntent(intent, watchId)
    }

    // FCM으로부터 알림을 받았을 때
    private fun handleFcmIntent(intent: Intent) {
        val title = intent.getStringExtra("fcm_title") ?: return
        val body  = intent.getStringExtra("fcm_body") ?: return
        Log.d("MainActivity", "FCM으로 실행됨: $title / $body")
    }

    // 서버로부터 해당 워치의 IN/OUT 상태를 불러옴
    private fun handleNfcEvent(watchId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val state = Api.handleNfc(watchId)
            withContext(Dispatchers.Main) {
                applyAccessState(state)
            }
        }
    }

    // 해당 워치의 IN/OUT 상태를 보고 쓰러짐 감지를 활성화/비활성화 함
    private fun applyAccessState(state: Api.AccessState) {
        accessState = state
        getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_inside", state.isInside).apply()

        if (state.isInside) {
            Log.d("STATE", "입실 → 감지 시작 / ${state.companyName} / ${state.userName}")
            // 입실 시: 화면 켜짐 유지 플래그 해제
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            CollapseForegroundService.start(this)
        } else if (state.isAssigned) {
            Log.d("STATE", "작업 배정됨 -> 화면 유지 (NFC 태그 대기)")
            // 배정 상태: 화면을 계속 켜둬서 HCE가 동작하도록
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            CollapseForegroundService.stop(this)
        } else {
            Log.d("STATE", "퇴실 → 감지 종료")
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            CollapseForegroundService.stop(this)
        }
    }

    // 들어오는 intent의 종류에 따라 분기처리
    private fun handleIntent(intent: Intent, watchId: String) {
        when (intent.action) {
            // NFC 태그 감지 시 서버에서 입퇴실 상태 조회
            "android.nfc.action.NDEF_DISCOVERED" -> {
                handleNfcEvent(watchId)
            }
        }
        handleFcmIntent(intent)
    }

    // 앱 실행시 서버로부터 해당 워치의 IN/OUT 상태를 불러옴
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