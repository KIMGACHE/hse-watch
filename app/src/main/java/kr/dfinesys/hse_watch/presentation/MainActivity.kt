package kr.dfinesys.hse_watch.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.dfinesys.hse_watch.core.CollapseForegroundService
import kr.dfinesys.hse_watch.core.NotificationHelper
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.presentation.theme.Hse_watchTheme
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

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

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
            val isRegistered = prefs.getBoolean("is_registered", false)
            Log.d("FCM", "토큰: $token")
            if (!isRegistered) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        Api.registerDevice(watchId, token)
                        prefs.edit().putBoolean("is_registered", true).apply()
                    }
                }
            }
        }

        // 앱 실행 시 입퇴실 상태 동기화
        syncStateFromServer(watchId);

        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            Hse_watchTheme {
                MonitorScreen(watchId = watchId)
            }
        }

//        handleFcmIntent(intent)
        handleIntent(intent, watchId)
    }

    override fun onResume() {
        super.onResume()

        val watchId = getOrCreateDeviceId(this)
        syncStateFromServer(watchId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val watchId = getOrCreateDeviceId(this)

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
            val isInside = Api.getAccessState(watchId)
            applyAccessState(isInside)
        }
    }
    // 해당 워치의 IN/OUT 상태를 보고 쓰러짐 감지를 활성화/비활성화 함
    private fun applyAccessState(isInside: Boolean) {
        if (isInside) {
            Log.d("STATE", "입실 → 감지 시작")
            CollapseForegroundService.start(this)
        } else {
            Log.d("STATE", "퇴실 → 감지 종료")
            CollapseForegroundService.stop(this)
        }
    }
    // 들어오는 intent의 종류에 따라 분기처리
    private fun handleIntent(intent: Intent, watchId: String) {
        when (intent.action) {
            "android.nfc.action.NDEF_DISCOVERED" -> {
                handleNfcEvent(watchId)
            }
        }
        handleFcmIntent(intent)
    }
    // 앱 실행시 서버로부터 해당 워치의 IN/OUT 상태를 불러옴
    private fun syncStateFromServer(watchId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val isInside = Api.getAccessState(watchId)
            applyAccessState(isInside)
        }
    }
}