package kr.dfinesys.hse_watch.hceService

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId

class HseHostApduService : HostApduService() {

    companion object {
        private const val TAG = "HceService"

        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extra: Bundle?): ByteArray? {
        // commandApdu가 AID, 어플 식별하는 16바이트
        if (commandApdu == null) return STATUS_FAILED

        Log.d(TAG, "리더기 명령 : ${byteArrayToHexString(commandApdu)}")

        val watchId = getOrCreateDeviceId(this)
        val responsePayload = watchId.toByteArray(Charsets.UTF_8)

        Log.d(TAG, "데이터 전송: $watchId")

        return responsePayload + STATUS_SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "연결 해제. 사유 코드: $reason")
    }

    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") {String.format("%02x", it)}
    }
}