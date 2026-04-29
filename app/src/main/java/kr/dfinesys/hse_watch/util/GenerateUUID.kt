package kr.dfinesys.hse_watch.util
import android.content.Context
import java.util.UUID

fun getOrCreateDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)

    var id = prefs.getString("device_id", null)

    if (id == null) {
        id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
    }

    return id
}
