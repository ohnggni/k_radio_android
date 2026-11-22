package kr.ohnggni.kradio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** 인터넷 방송 한 개 (공개 방송 디렉터리 또는 설정 파일의 추천 목록) */
data class WebStation(
    val uuid: String?,        // 디렉터리 방송 ID (추천 목록은 없음)
    val name: String,
    val url: String,          // 재생 주소 (pls·m3u도 앱이 재생 직전에 풀어 씀)
    val logo: String?,        // 로고 주소 (없으면 이름 첫 글자)
    val tags: List<String>,
    val country: String?,     // 국가 코드 (KR 등)
    val codec: String?,
    val bitrate: Int,
) {
    /** 목록 부제: "KR · jazz, lounge · MP3 128k" */
    fun meta(): String = listOfNotNull(
        country?.takeIf { it.isNotBlank() },
        tags.take(2).joinToString(", ").takeIf { it.isNotBlank() },
        listOfNotNull(codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" }, bitrate.takeIf { it > 0 }?.let { "${it}k" })
            .joinToString(" ").takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * 공개 인터넷 방송 디렉터리(radio-browser.info) 조회.
 * 미러 서버가 여러 개라 하나가 안 되면 다음 서버로. 고장 방송(점검 실패)은 숨겨서 받음.
 */
object RadioBrowser {
    private val SERVERS = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )
    @Volatile private var good = 0   // 마지막으로 성공한 서버부터 시도
    var userAgent = "KRadio"

    /** 찾기 조건 */
    sealed class Query(val params: Map<String, String>) {
        class Country(code: String, limit: Int) : Query(mapOf("countrycode" to code, "order" to "clickcount", "limit" to "$limit"))
        class World(limit: Int) : Query(mapOf("order" to "votes", "limit" to "$limit"))
        class Tag(val tag: String, limit: Int) : Query(mapOf("tag" to tag, "order" to "clickcount", "limit" to "$limit"))
        class Name(val name: String, limit: Int) : Query(mapOf("name" to name, "order" to "clickcount", "limit" to "$limit"))
    }

    suspend fun search(q: Query): List<WebStation> = withContext(Dispatchers.IO) {
        val p = q.params + mapOf("reverse" to "true", "hidebroken" to "true")
        val query = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        var last: Throwable? = null
        for (i in SERVERS.indices) {
            val idx = (good + i) % SERVERS.size
            try {
                val text = get("${SERVERS[idx]}/json/stations/search?$query")
                good = idx
                return@withContext parse(JSONArray(text))
            } catch (e: Throwable) {
                last = e
                Log.w("KRadio", "방송 디렉터리 서버 실패: ${SERVERS[idx]} - ${e.message}")
            }
        }
        throw last ?: IllegalStateException("서버 없음")
    }

    /** 추가했을 때 디렉터리에 '들음' 1회 알림 (디렉터리 이용 예절, 실패해도 무시) */
    suspend fun countClick(uuid: String?) {
        if (uuid.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching { get("${SERVERS[good]}/json/url/$uuid") }
        }
    }

    private fun get(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(a: JSONArray): List<WebStation> {
        val seen = HashSet<String>()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (url.isEmpty() || name.isEmpty()) return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null   // 같은 주소 중복 등록 제거
            WebStation(
                uuid = o.optString("stationuuid").ifBlank { null },
                name = name,
                url = url,
                logo = o.optString("favicon").trim().ifBlank { null },
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                country = o.optString("countrycode").ifBlank { null },
                codec = o.optString("codec").ifBlank { null },
                bitrate = o.optInt("bitrate", 0),
            )
        }
    }

    /** 설정 파일 internetPicks: [{"name","url","logo","tags":"a,b"}] */
    fun parsePicks(a: JSONArray?): List<WebStation> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            val name = o.optString("name").trim()
            if (url.isEmpty() || name.isEmpty()) return@mapNotNull null
            WebStation(
                uuid = null, name = name, url = url,
                logo = o.optString("logo").trim().ifBlank { null },
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                country = o.optString("country").ifBlank { null },
                codec = null, bitrate = 0,
            )
        }
    }
}
