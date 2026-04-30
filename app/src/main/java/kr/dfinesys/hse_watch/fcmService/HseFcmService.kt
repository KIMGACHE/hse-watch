package kr.dfinesys.hse_watch.fcmService

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.dfinesys.hse_watch.core.NotificationHelper
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.presentation.MainActivity
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

/**
 * Firebase Cloud Messaging 수신 서비스
 * 서버로부터 위험 경보 푸시 메시지를 수신하여 알림과 진동을 발생시킨다.
 */
class HseFcmService : FirebaseMessagingService() {
    companion object {
        private var lastHandledTime = 0L
        private const val DEBOUNCE_MS = 3_000L  // 3초 내 중복 수신되면 무시하도록
    }

    /**
     * FCM 메시지가 수신될 때 호출된다.
     * 중복 수신 방지, 화면 켜기, 알림 표시, 진동 실행을 순서대로 처리한다.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("HseFcmService", "메시지 수신됨")

        // 3초 내 중복 메시지 무시하기
        val now = System.currentTimeMillis();
        if (now - lastHandledTime < DEBOUNCE_MS) {
            Log.d("HseFcmService", "중복 메시지 무시됨")
            return
        }
        lastHandledTime = now

        // data 페이로드에서 제목/본문 추출 (없으면 기본값 사용)
        val title = remoteMessage.data["title"] ?: "위험 경보"
        val body  = remoteMessage.data["body"]  ?: "위험이 감지되었습니다."

        // WakeLock으로 화면을 강제로 켜서 사용자에게 즉시 표시
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HseWatch::ScreenWakeLock"
        )
        wakeLock.acquire(10_000L) // 최대 10초 동안 화면 유지

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("fcm_title", title)
            putExtra("fcm_body", body)
        }
        // Android 10+에서 백그라운드에서 Service가 Activity를 직접 실행하는 건 차단된다고 한다.
        // startActivity(launchIntent)

        // 알림 표시 및 진동 시작
        NotificationHelper.show(context = this, id = 2, title = title, text = body)
        NotificationHelper.startVibrate(this)

        // 화면 켜진 후 WakeLock 해제
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 3_000L)
    }

    /**
     * FCM 토큰이 갱신될 때마다 호출된다.
     * 새 토큰을 서버에 전달하여 이후 푸시 메시지가 정상적으로 수신되도록 한다.
     *
     * @param token 새로 발급된 FCM 등록 토큰
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val watchId = getOrCreateDeviceId(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.refreshFcmToken(watchId, token) }
        }
    }
}