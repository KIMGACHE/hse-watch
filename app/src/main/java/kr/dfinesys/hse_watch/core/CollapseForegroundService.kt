package kr.dfinesys.hse_watch.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.dfinesys.hse_watch.R
import kr.dfinesys.hse_watch.data.Api
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

/**
 * 쓰러짐 감지 포어그라운드 서비스
 *
 * 가속도 센서를 지속적으로 모니터링하다가,
 * 일정 시간(5초) 동안 센서 값에 변화가 없으면 쓰러짐으로 판단하여
 * 경보를 발생시키고 서버에 위험 상태를 전송한다.
 *
 * 입실 상태일 때만 동작하며, 퇴실 시 자동 종료된다.
 */
class CollapseForegroundService : Service() {

    companion object {
        private const val TAG = "CollapseService"
        private const val CHANNEL_ID = "collapse_service"
        private const val NOTIFICATION_ID = 10

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISMISS = "ACTION_DISMISS"   // 사용자가 경고를 수동으로 해제할 때

        /** 포어그라운드 서비스를 시작한다. Android O 이상에서는 startForegroundService 사용 */
        fun start(context: Context) {
            val intent = Intent(context, CollapseForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 서비스를 중지한다. */
        fun stop(context: Context) {
            val intent = Intent(context, CollapseForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // 서비스 생명주기에 연결된 코루틴 스코프 (SupervisorJob: 자식 코루틴 실패가 부모에 전파되지 않음)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sensorManager: SensorManager? = null

    // 무변화 타이머 Job (센서 값이 바뀌면 취소 후 재시작)
    private var inactivityJob: Job? = null

    // 중복 API 호출 방지용: 마지막으로 서버에 보낸 위험 상태
    private var lastSentDanger: Boolean? = null
    private var warning = false

    // 이전 프레임의 센서 값 (변화 감지에 사용)
    private var prevX = 0
    private var prevY = 0
    private var prevZ = 0

    /**
     * 가속도 센서 이벤트 리스너
     * 매 프레임마다 X/Y/Z 값을 UI로 브로드캐스트하고,
     * 값이 변경됐거나 타이머가 비활성 상태이면 타이머를 리셋한다.
     */
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val nx = event.values[0].toInt()
            val ny = event.values[1].toInt()
            val nz = event.values[2].toInt()

            broadcastSensorValues(nx, ny, nz)

            // 이전 값과 다르거나 타이머가 꺼져 있으면 타이머 재시작
            val changed = (nx != prevX) || (ny != prevY) || (nz != prevZ)
            if (changed || inactivityJob?.isActive != true) {
                resetInactivityTimer()
            }

            prevX = nx; prevY = ny; prevZ = nz
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Intent action에 따라 서비스 동작을 분기한다.
     * action이 null이면 (시스템 재시작 등) SharedPreferences의 입실 상태를 확인해 처리한다.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START   -> startMonitoring()
            ACTION_STOP    -> stopSelf()
            ACTION_DISMISS -> dismissWarning()
            null -> {
                // 시스템에 의해 서비스가 재시작된 경우, 저장된 입실 상태로 복원
                val prefs = getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)
                val isInside = prefs.getBoolean("is_inside", false)
                if(isInside) {
                    startMonitoring()
                } else {
                    stopSelf()
                }
            }
        }
        // START_STICKY: 시스템이 서비스를 강제 종료해도 자동으로 재시작
        return START_STICKY
    }

    /** 경고 상태를 MonitorScreen에 브로드캐스트한다. */
    private fun broadcastWarning(isDanger: Boolean) {
        val intent = Intent("ACTION_WARNING_STATE").apply {
            putExtra("warning", isDanger)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /** 현재 센서 X/Y/Z 값을 MonitorScreen에 브로드캐스트한다. */
    private fun broadcastSensorValues(x: Int, y: Int, z: Int) {
        val intent = Intent("ACTION_SENSOR_VALUES").apply {
            putExtra("x", x)
            putExtra("y", y)
            putExtra("z", z)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /** 포어그라운드 알림을 등록하고 가속도 센서 리스너를 시작한다. */
    private fun startMonitoring() {
        Log.d(TAG, "쓰러짐 감지 서비스 시작")
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL)

        resetInactivityTimer()
    }

    /**
     * 무변화 감지 타이머를 리셋한다.
     * 기존 타이머를 취소하고 5초짜리 새 타이머를 시작한다.
     * 5초 안에 센서 값이 변하지 않으면 경고 상태로 전환된다.
     */
    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(5_000)
            if (!warning) {
                warning = true
                broadcastWarning(true)
                val watchId = getOrCreateDeviceId(this@CollapseForegroundService)
                NotificationHelper.show(
                    context = this@CollapseForegroundService,
                    id = 1,
                    title = "위험 감지",
                    text = "사용자의 위험이 감지되었습니다."
                )
                sendDangerIfChanged(watchId, true)
            }
        }
    }

    /**
     * 이전에 보낸 상태와 다를 때만 서버에 위험 상태를 전송한다.
     * 중복 API 호출을 방지한다.
     *
     * @param watchId 이 기기의 고유 ID
     * @param isDanger 전송할 위험 상태
     */
    private fun sendDangerIfChanged(watchId: String, isDanger: Boolean) {
        if (lastSentDanger != isDanger) {
            lastSentDanger = isDanger
            scope.launch(Dispatchers.IO) {
                runCatching { Api.setDanger(watchId, isDanger) }
            }
        }
    }

    /**
     * 사용자가 경고를 수동으로 해제할 때 호출된다.
     * 경고 상태를 초기화하고, 알림/진동을 취소한 뒤 타이머를 재시작한다.
     */
    private fun dismissWarning() {
        warning = false
        broadcastWarning(false)
        val watchId = getOrCreateDeviceId(this)
        NotificationHelper.cancelAll(this)
        NotificationHelper.stopVibrate()
        sendDangerIfChanged(watchId, false)
        resetInactivityTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 센서 리스너 해제 및 코루틴 스코프 취소
        sensorManager?.unregisterListener(sensorListener)
        scope.cancel()
        Log.d(TAG, "쓰러짐 감지 서비스 종료")
    }

    // 바인딩 불필요 (Started Service로만 사용)
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 포어그라운드 서비스 유지에 필요한 상시 알림을 생성한다.
     * 우선순위를 LOW로 설정해 사용자 방해를 최소화한다.
     */
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "쓰러짐 감지 실행 중",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HSE Watch")
            .setContentText("쓰러짐 감지 중...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}