package kr.dfinesys.hse_watch.hceService

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

/**
 * HCE(Host-based Card Emulation) 서비스
 * NFC 리더기가 이 워치에 접근하면, watchId를 APDU 응답으로 전송한다.
 * AndroidManifest에 등록된 AID와 일치하는 SELECT 명령이 수신될 때 동작한다.
 */
class HseHostApduService : HostApduService() {

    companion object {
        private const val TAG = "HceService"

        // APDU 처리 성공 상태 코드 (ISO 7816-4 표준)
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        // APDU 처리 실패 상태 코드
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    /**
     * NFC 리더기로부터 APDU 명령을 수신했을 때 호출된다.
     * watchId를 UTF-8 바이트로 변환한 뒤 SUCCESS 상태 코드와 함께 반환한다.
     *
     * @param commandApdu 리더기가 보낸 APDU 명령 바이트 배열 (AID 포함)
     * @param extra 추가 정보 (현재 미사용)
     * @return watchId 바이트 배열 + 상태 코드 (성공 또는 실패)
     */
    override fun processCommandApdu(commandApdu: ByteArray?, extra: Bundle?): ByteArray? {
        // commandApdu가 AID, 어플 식별하는 16바이트
        if (commandApdu == null) return STATUS_FAILED

        Log.d(TAG, "리더기 명령 : ${byteArrayToHexString(commandApdu)}")

        val watchId = getOrCreateDeviceId(this)
        val responsePayload = watchId.toByteArray(Charsets.UTF_8)

        Log.d(TAG, "데이터 전송: $watchId")

        // 페이로드 뒤에 SUCCESS 코드를 붙여 리더기로 전송
        return responsePayload + STATUS_SUCCESS
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
    }

    /**
     * 바이트 배열을 16진수 문자열로 변환하는 유틸 함수 (로그 출력용)
     */
    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") {String.format("%02x", it)}
    }
}