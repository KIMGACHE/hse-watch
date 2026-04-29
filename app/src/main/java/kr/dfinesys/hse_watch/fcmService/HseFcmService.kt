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

class HseFcmService : FirebaseMessagingService() {
    companion object {
        private var lastHandledTime = 0L
        private const val DEBOUNCE_MS = 3_000L  // 3초 내 중복 수신되면 무시하도록
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("HseFcmService", "메시지 수신됨")

        // 3초 내 중복 메시지 무시하기
        val now = System.currentTimeMillis();
        if (now - lastHandledTime < DEBOUNCE_MS) {
            Log.d("HseFcmService", "중복 메시지 무시됨")
            return
        }
        lastHandledTime = now

        val title = remoteMessage.data["title"] ?: "위험 경보"
        val body  = remoteMessage.data["body"]  ?: "위험이 감지되었습니다."

        // 화면 켜기
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "HseWatch::ScreenWakeLock"
        )
        wakeLock.acquire(10_000L)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("fcm_title", title)
            putExtra("fcm_body", body)
        }
        // Android 10+에서 백그라운드에서 Service가 Activity를 직접 실행하는 건 차단된다고 한다.
        // startActivity(launchIntent)

        NotificationHelper.show(context = this, id = 2, title = title, text = body)
        NotificationHelper.startVibrate(this)

        // 화면 켜진 후 WakeLock 해제
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (wakeLock.isHeld) wakeLock.release()
        }, 3_000L)
    }

    // FCM 토큰이 갱신될 때마다 호출됨
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val watchId = getOrCreateDeviceId(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Api.refreshFcmToken(watchId, token) }
        }
    }
}