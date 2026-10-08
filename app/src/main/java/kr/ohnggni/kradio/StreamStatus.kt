package kr.ohnggni.kradio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * 방송 점검 결과 (서버가 매일 실제 재생을 확인해서 status 브랜치에 올림).
 * 문제 채널은 목록에서 회색 + "점검 중"으로 표시하되 재생은 막지 않음 (서버 판단이 틀릴 수도 있어서).
 * 채널 설정을 직접 지정한 경우엔 채널 구성이 달라서 쓰지 않음.
 */
object StreamStatus {
    // GitHub API: 캐시가 짧아 올리자마자 반영 (로그인 없이 IP당 시간당 60번 제한)
    private const val API_URL =
        "https://api.github.com/repos/ohnggni/k_radio_android/contents/status.json?ref=status"
    // 원본 주소: 제한은 없지만 최대 5분 캐시 → API가 안 될 때만
    private const val RAW_URL =
        "https://raw.githubusercontent.com/ohnggni/k_radio_android/status/status.json"
    private const val KEY = "stream_down"

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    /** 마지막으로 받은 문제 채널 ID (즉시, 네트워크 없음) */
    fun cached(c: Context): Set<String> =
        if (SourceSettings.customConfigUrl(c) != null) emptySet()
        else sp(c).getStringSet(KEY, null)?.toSet() ?: emptySet()

    /** 새로 받아서 저장. 실패하면 저장본 그대로 */
    suspend fun refresh(c: Context): Set<String> = withContext(Dispatchers.IO) {
        if (SourceSettings.customConfigUrl(c) != null) return@withContext emptySet()
        val text = runCatching { get(API_URL, mapOf("Accept" to "application/vnd.github.raw")) }
            .recoverCatching {
                Log.w("KRadio", "방송 점검 결과(API) 실패, 원본 주소로: ${it.message}")
                get(RAW_URL, emptyMap())
            }
        text.mapCatching { t ->
            val down = JSONObject(t).optJSONObject("down")
            val ids = down?.keys()?.asSequence()?.toSet() ?: emptySet()
            sp(c).edit().putStringSet(KEY, ids).apply()
            Log.i("KRadio", "점검 중 채널: ${if (ids.isEmpty()) "없음" else ids}")
            ids
        }.getOrElse {
            Log.w("KRadio", "방송 점검 결과 받기 실패: ${it.message}")
            cached(c)
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.useCaches = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
