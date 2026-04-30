package kr.dfinesys.hse_watch.util
import android.content.Context
import java.util.UUID

/**
 * 디바이스 고유 ID를 반환한다.
 * SharedPreferences에 저장된 ID가 있으면 재사용하고,
 * 없으면 UUID를 새로 생성해 저장한 뒤 반환한다.
 *
 * @param context SharedPreferences 접근에 필요한 Context
 * @return 디바이스 고유 ID 문자열
 */
fun getOrCreateDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)

    var id = prefs.getString("device_id", null)

    if (id == null) {
        // 최초 실행 시 UUID 생성 후 영구 저장
        id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
    }

    return id
}