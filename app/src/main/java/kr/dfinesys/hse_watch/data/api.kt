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

object Api {
//    private const val BASE_URL = "http://192.168.0.24:8080"
    private const val BASE_URL = "http://211.188.55.206:3000"
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun setDanger(watchId: String, on: Boolean) {
        requestGet(
            path = "/api/watch/collapse/$on",
            headers = mapOf("watch-id" to watchId)
        )
    }
    // 해당 워치가 출입하였는지 아닌지
    suspend fun getAccessState(watchId: String): Boolean {
        val response = requestGet("/api/access/$watchId")

        return try {
            val json = JSONObject(response)
            json.optBoolean("data", false)
        } catch (e: Exception) {
            Log.e(TAG, "파싱 실패: ${e.message}")
            false
        }
    }
    // 앱 최초 실행시 - watchId와 token을 함께 등록
    suspend fun registerDevice(watchId: String, fcmToken: String) {
        val json = """{"watchId":"$watchId", "fcmToken":"$fcmToken"}"""
        requestPost("/api/watch/register", json);
    }
    // 토큰 갱신 시 - token만 전송, watchId는 서버가 이미 가지고 있음
    suspend fun refreshFcmToken(watchId: String, fcmToken: String) {
        val json = """{"watchId":"$watchId","fcmToken":"$fcmToken"}"""
        requestPost("/api/watch/fcm-token", json)
    }
    // GET
    private const val TAG = "Api"

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
    // POST
    private suspend fun requestPost(path: String, json: String) = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        Log.d(TAG, "→ POST $url | body: $json")

        try {
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                Log.d(TAG, "← ${resp.code} $url")
                if (!resp.isSuccessful && resp.code != 204) {
                    val body = resp.body?.string().orEmpty()
                    Log.e(TAG, "실패 응답: $body")
                    throw IOException("HTTP ${resp.code}: $body")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "요청 실패: ${e.message}")
            throw e
        }
    }
}