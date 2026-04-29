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

class CollapseForegroundService : Service() {

    companion object {
        private const val TAG = "CollapseService"
        private const val CHANNEL_ID = "collapse_service"
        private const val NOTIFICATION_ID = 10
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISMISS = "ACTION_DISMISS"

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

        fun stop(context: Context) {
            val intent = Intent(context, CollapseForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sensorManager: SensorManager? = null
    private var inactivityJob: Job? = null
    private var lastSentDanger: Boolean? = null
    private var warning = false

    private var prevX = 0
    private var prevY = 0
    private var prevZ = 0

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val nx = event.values[0].toInt()
            val ny = event.values[1].toInt()
            val nz = event.values[2].toInt()

            broadcastSensorValues(nx, ny, nz)

            val changed = (nx != prevX) || (ny != prevY) || (nz != prevZ)
            if (changed || inactivityJob?.isActive != true) {
                resetInactivityTimer()
            }

            prevX = nx; prevY = ny; prevZ = nz
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START   -> startMonitoring()
            ACTION_STOP    -> stopSelf()
            ACTION_DISMISS -> dismissWarning()
        }
        return START_STICKY
    }

    private fun broadcastWarning(isDanger: Boolean) {
        val intent = Intent("ACTION_WARNING_STATE").apply {
            putExtra("warning", isDanger)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSensorValues(x: Int, y: Int, z: Int) {
        val intent = Intent("ACTION_SENSOR_VALUES").apply {
            putExtra("x", x)
            putExtra("y", y)
            putExtra("z", z)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startMonitoring() {
        Log.d(TAG, "쓰러짐 감지 서비스 시작")
        startForeground(NOTIFICATION_ID, buildNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL)

        resetInactivityTimer()
    }

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

    private fun sendDangerIfChanged(watchId: String, isDanger: Boolean) {
        if (lastSentDanger != isDanger) {
            lastSentDanger = isDanger
            scope.launch(Dispatchers.IO) {
                runCatching { Api.setDanger(watchId, isDanger) }
            }
        }
    }

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
        sensorManager?.unregisterListener(sensorListener)
        scope.cancel()
        Log.d(TAG, "쓰러짐 감지 서비스 종료")
    }

    override fun onBind(intent: Intent?): IBinder? = null

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