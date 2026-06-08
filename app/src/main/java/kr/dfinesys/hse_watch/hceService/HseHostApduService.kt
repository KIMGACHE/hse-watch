package kr.dfinesys.hse_watch.hceService

import android.content.Context
import android.health.connect.datatypes.units.Power
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

/**
 * HCE(Host-based Card Emulation) 서비스
 * NFC 리더기가 이 워치에 접근하면, watchId를 APDU 응답으로 전송한다.
 * SELECT AID 명령에는 성공 응답만 반환하고,
 * GET DATA 명령에 watchId를 반환한다.
 */
class HseHostApduService : HostApduService() {

    companion object {
        private const val TAG = "HceService"

        // APDU 처리 성공 상태 코드 (ISO 7816-4 표준)
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        // APDU 처리 실패 상태 코드
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hse:hce-wakelock")
    }

    /**
     * NFC 리더기로부터 APDU 명령을 수신했을 때 호출된다.
     * SELECT AID(0xA4): 성공 응답만 반환
     * GET DATA(0xCA): watchId를 UTF-8 바이트로 변환한 뒤 SUCCESS 상태 코드와 함께 반환한다.
     *
     * @param commandApdu 리더기가 보낸 APDU 명령 바이트 배열
     * @param extra 추가 정보 (현재 미사용)
     * @return 상태 코드 또는 watchId 바이트 배열 + 상태 코드
     */
    override fun processCommandApdu(commandApdu: ByteArray?, extra: Bundle?): ByteArray? {
        if (!wakeLock.isHeld) wakeLock.acquire(5_000L)

        if (commandApdu == null) return STATUS_FAILED

        Log.d(TAG, "리더기 명령: ${byteArrayToHexString(commandApdu)}")

        return when {
            // SELECT AID (INS = 0xA4) → 성공 응답만 반환
            commandApdu[1] == 0xA4.toByte() -> {
                Log.d(TAG, "SELECT AID → OK")
                STATUS_SUCCESS
            }

            // GET DATA (INS = 0xCA) → watchId 반환
            commandApdu[1] == 0xCA.toByte() -> {
                val watchId = getOrCreateDeviceId(this)
                Log.d(TAG, "GET DATA → watchId 전송: $watchId")
                watchId.toByteArray(Charsets.UTF_8) + STATUS_SUCCESS
            }

            else -> {
                Log.d(TAG, "알 수 없는 명령: ${byteArrayToHexString(commandApdu)}")
                STATUS_FAILED
            }
        }
    }

    /**
     * NFC 연결이 해제되거나 다른 앱으로 전환될 때 호출된다.
     *
     * @param reason 연결 해제 사유 코드
     *               (DEACTIVATION_LINK_LOSS = 0: 통신 끊김,
     *                DEACTIVATION_DESELECTED = 1: 다른 AID 선택됨)
     */
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "연결 해제. 사유 코드: $reason")
        if (wakeLock.isHeld) wakeLock.release();
    }

    /**
     * 바이트 배열을 16진수 문자열로 변환하는 유틸 함수 (로그 출력용)
     */
    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02x", it) }
    }
}