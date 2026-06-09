package kr.dfinesys.hse_watch.fcmService

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.dfinesys.hse_watch.core.NotificationHelper
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

/**
 * Firebase Cloud Messaging 수신 서비스
 *
 * [type] 필드에 따라 두 가지로 분기한다:
 *   - COLLAPSE / GAS_ALERT : 위험 경보 → 화면 켜기 + 알림 + 진동
 *   - ASSIGNED / INSIDE / OUTSIDE : 상태 변경 → 조용히 브로드캐스트만 전송
 */
class HseFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "HseFcmService"

        // 위험 경보 타입에만 적용되는 중복 수신 방지 타임스탬프
        private var lastAlertTime = 0L
        private const val DEBOUNCE_MS = 3_000L

        // 상태 변경 브로드캐스트 액션 (MainActivity에서 수신)
        const val ACTION_ACCESS_STATE = "ACTION_ACCESS_STATE"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val type = remoteMessage.data["type"] ?: run {
            Log.w(TAG, "type 필드 없음 — 무시")
            return
        }
        Log.d(TAG, "메시지 수신: type=$type")

        when (type) {
            "COLLAPSE", "GAS_ALERT" -> handleAlert(remoteMessage)
            "ASSIGNED", "INSIDE", "OUTSIDE" -> handleStateChange(type, remoteMessage)
            else -> Log.w(TAG, "알 수 없는 type: $type")
        }
    }

    /**
     * 위험 경보 처리
     * - 3초 debounce 적용
     * - PARTIAL_WAKE_LOCK으로 CPU만 깨움 (화면 켜기는 MainActivity의 FLAG_TURN_SCREEN_ON으로 처리)
     * - 알림 표시 + 진동 시작
     */
    private fun handleAlert(remoteMessage: RemoteMessage) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < DEBOUNCE_MS) {
            Log.d(TAG, "중복 경보 무시됨")
            return
        }
        lastAlertTime = now

        val title = remoteMessage.data["title"] ?: "위험 경보"
        val body  = remoteMessage.data["body"]  ?: "위험이 감지되었습니다."

        // PARTIAL_WAKE_LOCK: CPU만 깨움 (deprecated된 SCREEN_BRIGHT_WAKE_LOCK 대체)
        // 화면 켜기는 MainActivity에서 window flag로 처리
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HseWatch::AlertWakeLock")
        wakeLock.acquire(10_000L)

        NotificationHelper.show(context = this, id = 2, title = title, text = body)
        NotificationHelper.startVibrate(this)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 3_000L)
    }

    /**
     * 상태 변경 처리 (ASSIGNED / INSIDE / OUTSIDE)
     * - 알림/진동 없음
     * - debounce 없음 (상태 변경은 모두 반영해야 함)
     * - LocalBroadcast로 MainActivity에 전달
     */
    private fun handleStateChange(type: String, remoteMessage: RemoteMessage) {
        val companyName = remoteMessage.data["companyName"] ?: ""
        val userName    = remoteMessage.data["userName"]    ?: ""

        val intent = Intent(ACTION_ACCESS_STATE).apply {
            putExtra("type", type)
            putExtra("companyName", companyName)
            putExtra("userName", userName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "상태 브로드캐스트 전송: $type / $companyName / $userName")
    }

    /**
     * FCM 토큰 갱신 시 서버에 새 토큰 전달
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val watchId = getOrCreateDeviceId(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.refreshFcmToken(watchId, token) }
        }
    }
}