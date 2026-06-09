package kr.dfinesys.hse_watch.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.dfinesys.hse_watch.util.getOrCreateDeviceId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 서버와 통신하는 API 클라이언트 싱글톤
 * OkHttp를 사용하며, 모든 요청은 코루틴의 IO 디스패처에서 실행된다.
 */
object Api {
//        private const val BASE_URL = "http://192.168.0.24:8080"
    private const val BASE_URL = "http://211.188.55.206:3001"

    // 전체 요청에 5초 타임아웃 적용
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    data class AccessState(
        val isInside: Boolean,
        val isAssigned: Boolean = false,    // ASSIGNED 상태 (배정됐지만 미입장)
        val companyName: String = "",
        val userName: String = ""
    )

    /**
     * 쓰러짐 위험 감지 상태를 서버에 전송한다.
     * on=true: 5초간 센서 무변화 감지 → 위험 상태
     * on=false: 사용자가 화면 탭으로 경고 해제
     *
     * @param watchId 이 기기의 고유 ID
     * @param on true = 위험 감지됨, false = 위험 해제됨
     */
    suspend fun setDanger(watchId: String, on: Boolean) {
        requestPost("/api/v1/watch/collapse?watchId=$watchId&on=$on", "{}")
    }

    /**
     * NFC 태그 시 입장/퇴장을 토글하고 결과를 반환한다.
     * 서버에서 IN/OUT 방향을 자동으로 결정하며,
     * 응답에 포함된 업체명/사용자명을 함께 반환한다.
     *
     * @param watchId 이 기기의 고유 ID
     * @return 변경된 입실 여부, 업체명, 사용자명
     */
    suspend fun handleNfc(watchId: String): AccessState {
        val json = """{"watchId":"$watchId"}"""
        val response = requestPost("/api/v1/access", json)
        return try {
            val data = JSONObject(response).getJSONObject("data")
            AccessState(
                isInside = data.optString("type") == "IN",
                companyName = data.optString("companyName", ""),
                userName = data.optString("userName", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "NFC 응답 파싱 실패: ${e.message}")
            AccessState(false)
        }
    }

    /**
     * 앱 시작 시 서버에서 현재 입실 상태를 동기화한다.
     * NFC 태그가 아닌 초기 상태 복원 용도로만 사용한다.
     * watchId는 헤더(watch-id)로 전달한다.
     *
     * @param watchId 이 기기의 고유 ID
     * @return 현재 입실 여부, 업체명, 사용자명
     */
    suspend fun getAccessState(watchId: String): AccessState {
        val response = requestGet(
            path = "/api/v1/watch/access?watchId=$watchId",
            headers = mapOf("watch-id" to watchId)
        )
        return try {
            val json = JSONObject(response)
            val data = json.getJSONObject("data")
            AccessState(
                isInside = data.optBoolean("inside", false),
                isAssigned = data.optBoolean("assigned", false),
                companyName = data.optString("companyName", ""),
                userName = data.optString("userName", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "파싱 실패: ${e.message}")
            AccessState(false)
        }
    }

    // 앱 최초 실행시 - watchId와 token을 함께 등록
    /**
     * 앱 최초 실행 시 watchId와 FCM 토큰을 함께 서버에 등록한다.
     * 이후에는 is_registered 플래그로 중복 호출을 방지한다.
     *
     * @param watchId 이 기기의 고유 ID
     * @param fcmToken 현재 발급된 FCM 등록 토큰
     */
    suspend fun registerDevice(watchId: String, fcmToken: String) {
        val json = """{"watchId":"$watchId","fcmToken":"$fcmToken"}"""
        requestPost("/api/v1/watch/register", json)
    }

    /**
     * FCM 토큰이 갱신됐을 때 서버에 새 토큰을 업데이트한다.
     * watchId와 새 토큰을 함께 전송한다.
     *
     * @param watchId 이 기기의 고유 ID
     * @param fcmToken 새로 발급된 FCM 토큰
     */
    suspend fun refreshFcmToken(watchId: String, fcmToken: String) {
        val json = """{"watchId":"$watchId","fcmToken":"$fcmToken"}"""
        requestPost("/api/v1/watch/refresh-token", json)
    }

    private const val TAG = "Api"

    /**
     * 공통 GET 요청 처리기
     * 지정한 path로 GET 요청을 보내고 응답 body 문자열을 반환한다.
     * HTTP 204(No Content)는 정상으로 간주한다.
     *
     * @param path API 경로 (BASE_URL 이후의 경로)
     * @param headers 추가로 전달할 헤더 (기본값: 빈 맵)
     * @return 응답 body 문자열
     */
    private suspend fun requestGet(
        path: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {

        val url = BASE_URL + path
        Log.d(TAG, "→ GET $url")

        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Content-Type", "application/json")

        // 추가 헤더 삽입 (예: watch-id)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()

            Log.d(TAG, "← ${resp.code} $url")
            Log.d(TAG, "body: $body")

            if (!resp.isSuccessful && resp.code != 204) {
                throw IOException("HTTP ${resp.code}: $body")
            }

            return@withContext body
        }
    }

    /**
     * 공통 POST 요청 처리기
     * JSON 문자열을 body로 담아 지정한 path로 POST 요청을 보내고 응답 body를 반환한다.
     * HTTP 204(No Content)는 정상으로 간주하며 빈 문자열을 반환한다.
     *
     * @param path API 경로 (BASE_URL 이후의 경로)
     * @param json 전송할 JSON 문자열
     * @return 응답 body 문자열 (204인 경우 빈 문자열)
     */
    private suspend fun requestPost(path: String, json: String): String = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        Log.d(TAG, "→ POST $url | body: $json")

        try {
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "← ${resp.code} $url")
                if (!resp.isSuccessful && resp.code != 204) {
                    throw IOException("HTTP ${resp.code}: $body")
                }
                return@withContext body
            }
        } catch (e: Exception) {
            Log.e(TAG, "요청 실패: ${e.message}")
            throw e
        }
    }
}