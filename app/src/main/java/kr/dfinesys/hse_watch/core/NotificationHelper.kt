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

object NotificationHelper {
    private const val CHANNEL_ID = "hse_alerts"
    private var ignoreUntil = 0L
    const val ACTION_DISMISS = "kr.dfinesys.hse_watch.ACTION_DISMISS"
    // Vibrator 싱글톤으로 관리
    private var vibrator: Vibrator? = null;

    fun startVibrate(context: Context) {
        if (System.currentTimeMillis() < ignoreUntil) {
            Log.d("NotificationHelper", "진동 무시됨 (사용자 해제 이후)")
            return
        }

        Log.d("NotificationHelper", "진동 시작 — SDK: ${Build.VERSION.SDK_INT}")
        val pattern = longArrayOf(0, 500, 300) // 진동 500ms → 쉬기 300ms 반복

        val vm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else null

        vibrator = if (vm != null) {
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, 0) // 0 = 반복
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

    fun stopVibrate() {
        vibrator?.cancel()
        vibrator = null

        ignoreUntil = System.currentTimeMillis() + 5_000
    }

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

    fun show(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("NotificationHelper", "알림 권한 없음")
                return
            }
        }

        // startVibrate 제거 — HseFcmService에서만 호출
        val dismissIntent = Intent(ACTION_DISMISS).apply { setPackage(context.packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, id, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // MainActivity를 여는 PendingIntent 추가
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
            .setDeleteIntent(dismissPendingIntent)
            .setContentIntent(openPendingIntent)

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

// 알림 탭/제거 시 진동 중지
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DismissReceiver", "수신됨 action: ${intent.action}")
        if (intent.action == NotificationHelper.ACTION_DISMISS) {
            NotificationHelper.stopVibrate()  // 싱글톤에서 cancel
        }
    }
}