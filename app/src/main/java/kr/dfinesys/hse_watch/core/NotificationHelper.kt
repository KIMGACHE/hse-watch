package kr.dfinesys.hse_watch.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.dfinesys.hse_watch.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.VibrationEffect
import androidx.core.content.ContextCompat
import kr.dfinesys.hse_watch.presentation.MainActivity

/**
 * 알림 및 진동을 관리하는 유틸리티 싱글톤
 *
 * - 알림 채널 생성
 * - 알림 표시 및 전체 취소
 * - 반복 진동 시작/중지
 * - 알림 해제 시 진동 중지를 위한 인텐트 처리
 */
object NotificationHelper {
    private const val CHANNEL_ID = "hse_alerts"

    // 사용자가 경고를 수동 해제한 직후 일정 시간 동안 진동 재발생을 막기 위한 타임스탬프
    private var ignoreUntil = 0L

    const val ACTION_DISMISS = "kr.dfinesys.hse_watch.ACTION_DISMISS"

    // 진동 인스턴스를 싱글톤으로 관리 (stopVibrate에서 참조해 취소)
    private var vibrator: Vibrator? = null;

    /**
     * 반복 진동을 시작한다.
     * 사용자가 최근에 경고를 해제했다면 (ignoreUntil 이내) 진동을 무시한다.
     * 500ms 진동 → 300ms 정지 패턴을 무한 반복한다.
     *
     * @param context Vibrator 서비스 접근에 필요한 Context
     */
    fun startVibrate(context: Context) {
        if (System.currentTimeMillis() < ignoreUntil) {
            Log.d("NotificationHelper", "진동 무시됨 (사용자 해제 이후)")
            return
        }

        Log.d("NotificationHelper", "진동 시작 — SDK: ${Build.VERSION.SDK_INT}")
        val pattern = longArrayOf(0, 500, 300) // 진동 500ms → 쉬기 300ms 반복

        // Android 12(S) 이상은 VibratorManager, 이하는 Vibrator 서비스를 사용
        val vm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else null

        vibrator = if (vm != null) {
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // UI 스레드에서 vibrate() 호출 (일부 기기에서 메인 스레드 필요)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 0번 인덱스부터 무한 반복
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, 0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
                Log.d("NotificationHelper", "vibrate() 호출 완료")
            } catch (e: Exception) {
                Log.e("NotificationHelper", "vibrate() 오류: ${e.message}")
            }
        }
    }

    /**
     * 진동을 중지하고, 이후 5초간 진동 재발생을 차단한다.
     * 사용자가 직접 경고를 해제했을 때 즉각적인 재진동을 방지하기 위함이다.
     */
    fun stopVibrate() {
        vibrator?.cancel()
        vibrator = null

        // 5초간 진동 재시작 금지
        ignoreUntil = System.currentTimeMillis() + 5_000
    }

    /**
     * 앱 최초 실행 시 알림 채널을 생성한다.
     * Android O 미만에서는 채널 개념이 없으므로 아무 작업도 하지 않는다.
     *
     * @param context NotificationManager 접근에 필요한 Context
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "안전 경보",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "HSE 위험 감지 알림" }
            mgr.createNotificationChannel(channel)
        }
    }

    /**
     * 지정한 ID로 알림을 표시한다.
     * - 알림을 탭하면 MainActivity가 열린다.
     * - 알림을 스와이프로 제거하면 ACTION_DISMISS 브로드캐스트가 발송되어 진동이 중지된다.
     *
     * @param context 알림 표시에 필요한 Context
     * @param id 알림 고유 ID (동일 ID로 호출하면 기존 알림을 덮어씀)
     * @param title 알림 제목
     * @param text 알림 본문
     */
    fun show(context: Context, id: Int, title: String, text: String) {
        // Android 13 이상에서 알림 권한이 없으면 조기 반환
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("NotificationHelper", "알림 권한 없음")
                return
            }
        }

        // 알림 스와이프 제거 시 진동을 중지하는 PendingIntent
        val dismissIntent = Intent(ACTION_DISMISS).apply { setPackage(context.packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, id, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 알림 탭 시 MainActivity를 여는 PendingIntent
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, id + 1000, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDeleteIntent(dismissPendingIntent)   // 스와이프 제거 시 호출
            .setContentIntent(openPendingIntent)     // 탭 시 호출

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * 현재 표시된 모든 알림을 취소한다.
     *
     * @param context NotificationManagerCompat 접근에 필요한 Context
     */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * 알림을 탭하거나 스와이프로 제거할 때 ACTION_DISMISS 브로드캐스트를 수신하는 리시버
 * 수신 시 진동을 중지한다.
 */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DismissReceiver", "수신됨 action: ${intent.action}")
        if (intent.action == NotificationHelper.ACTION_DISMISS) {
            NotificationHelper.stopVibrate()
        }
    }
}