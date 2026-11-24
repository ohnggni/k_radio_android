package kr.ohnggni.kradio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** 인터넷 방송 한 개 (인라이브 생방송 / 방송 안내 서비스 / 설정 파일의 추천 목록) */
data class WebStation(
    val name: String,
    val url: String,          // 재생 주소 (pls·m3u·tune.ashx도 앱이 재생 직전에 풀어 씀)
    val logo: String?,        // 로고·방송 그림 주소 (없으면 이름 첫 글자)
    val sub: String?,         // 부제: 지금 방송 제목·곡·소개
    val tag: String?,         // 짧은 꼬리표 (진행자, 장르 등)
    val listeners: Int = 0,   // 인라이브 청취자 수
    val bitrate: Int = 0,
) {
    /** 목록 부제: "진행자 · 청취 32 · 128k" + 방송 제목 */
    fun meta(): String = listOfNotNull(
        tag?.takeIf { it.isNotBlank() },
        listeners.takeIf { it > 0 }?.let { "청취 $it" },
        bitrate.takeIf { it > 0 }?.let { "${it}k" },
        sub?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * 인터넷 방송 찾기 데이터.
 * - 인라이브 공개 API(liveList): 지금 방송 중인 개인 DJ 방송, 청취자 수·방송 그림 포함
 * - 인터넷 방송 안내 서비스(OPML, 비공식): 분류·검색, 로고·안정성 점수 포함.
 *   재생 주소는 방송 ID로 된 tune.ashx 주소를 그대로 저장 → 재생할 때마다 최신 실제 주소를 받음
 */
object RadioDirectory {
    var userAgent = "KRadio"

    // ---------------- 인라이브 ----------------

    private const val INLIVE_LIVE = "https://api.inlive.co.kr/openapi/liveList"

    @Volatile private var inliveCache: Pair<Long, List<WebStation>>? = null

    /**
     * 지금 방송 중인 인라이브 방송 전부, 청취자 많은 순.
     * start는 쪽 번호(1부터, 쪽당 최대 50개). 1쪽의 total로 나머지 쪽 수를 정해 한꺼번에 받음 (최대 10쪽).
     * 첫 줄·더 보기·검색이 연달아 부르므로 1분 동안은 받아 둔 걸 씀
     */
    suspend fun inliveOnAir(): List<WebStation> = coroutineScope {
        inliveCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 60_000) return@coroutineScope list }
        fun url(page: Int) = "$INLIVE_LIVE?onair=Y&gubun=A&display=50&start=$page"
        val first = withContext(Dispatchers.IO) { JSONObject(get(url(1))) }
        val total = first.optString("total").toIntOrNull() ?: 0
        val pages = ((total + 49) / 50).coerceIn(1, 10)
        val rest = (2..pages).map { pg ->
            async(Dispatchers.IO) {
                runCatching { parseInlive(JSONObject(get(url(pg)))) }
                    .onFailure { Log.w("KRadio", "인라이브 목록 실패(${pg}쪽): ${it.message}") }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
        val list = (parseInlive(first) + rest.flatten())
            .distinctBy { it.url }
            .sortedByDescending { it.listeners }
        Log.i("KRadio", "인라이브 방송 중: ${list.size}개 (전체 ${total}개, ${pages}쪽)")
        inliveCache = System.currentTimeMillis() to list
        list
    }

    private fun parseInlive(o: JSONObject): List<WebStation> {
        val a = o.optJSONArray("channel") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            if (c.optString("onair") != "Y") return@mapNotNull null
            val pls = c.optString("pls").trim()
            val name = c.optString("channelname").trim()
            if (pls.isEmpty() || name.isEmpty()) return@mapNotNull null
            WebStation(
                name = name,
                url = pls,
                logo = c.optString("listimage").trim().ifBlank { null },
                sub = c.optString("title").trim().ifBlank { null },
                tag = c.optString("cjnick").trim().ifBlank { null },
                listeners = c.optString("listeners").toIntOrNull() ?: 0,
            )
        }
    }

    // ---------------- 인터넷 방송 안내 서비스 (OPML) ----------------

    private const val OPML = "https://opml.radiotime.com"

    /** 음악 분류 (이름 → 분류 ID). 장르 칩에 씀 */
    data class Category(val name: String, val id: String)

    suspend fun musicCategories(): List<Category> = withContext(Dispatchers.IO) {
        val body = JSONObject(get("$OPML/Browse.ashx?c=music&render=json")).optJSONArray("body") ?: JSONArray()
        (0 until body.length()).mapNotNull { i ->
            val x = body.optJSONObject(i) ?: return@mapNotNull null
            val id = x.optString("guide_id")
            val text = x.optString("text")
            if (x.optString("type") != "link" || id.isBlank() || text.isBlank()) null else Category(text, id)
        }
    }

    /** 분류 하나의 방송 목록 */
    suspend fun category(id: String): List<WebStation> = browse("$OPML/Browse.ashx?id=${enc(id)}&render=json")

    /** 이름 검색 (방송만) */
    suspend fun search(q: String): List<WebStation> =
        browse("$OPML/Search.ashx?query=${enc(q)}&types=station&render=json")

    /**
     * 목록을 받아 방송만 모음. 방송 없이 하위 분류 링크만 오면 앞쪽 몇 개(follow)를 더 받아서 합침
     */
    private suspend fun browse(url: String, follow: Int = 0, depth: Int = 1): List<WebStation> = withContext(Dispatchers.IO) {
        val body = JSONObject(get(withJson(url))).optJSONArray("body") ?: JSONArray()
        val stations = ArrayList<WebStation>()
        val links = ArrayList<String>()
        collect(body, stations, links)
        if (follow > 0 && depth > 0 && stations.size < 10) {
            coroutineScope {
                links.take(follow).map { link ->
                    async { runCatching { browse(link, follow, depth - 1) }.getOrDefault(emptyList()) }
                }.awaitAll().forEach { stations += it }
            }
        }
        stations.distinctBy { it.url }
    }

    private fun collect(a: JSONArray, out: MutableList<WebStation>, links: MutableList<String>) {
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            x.optJSONArray("children")?.let { collect(it, out, links) }
            when {
                x.optString("item") == "station" -> station(x)?.let { out += it }
                x.optString("type") == "link" && x.optString("URL").contains("Browse.ashx", true) -> links += x.optString("URL")
            }
        }
    }

    private fun station(x: JSONObject): WebStation? {
        val id = x.optString("guide_id").ifBlank { x.optString("preset_id") }
        val name = x.optString("text").trim()
        if (id.isBlank() || name.isBlank()) return null
        val reliability = x.optString("reliability").toIntOrNull() ?: 100
        if (reliability < 70) return null   // 자주 끊기는 방송은 숨김
        return WebStation(
            name = name,
            url = "$OPML/Tune.ashx?id=${enc(id)}&formats=mp3,aac,ogg",
            logo = x.optString("image").trim().ifBlank { null }?.replaceFirst("http://", "https://"),
            sub = x.optString("playing").ifBlank { x.optString("subtext") }.trim().ifBlank { null },
            tag = null,
            bitrate = x.optString("bitrate").toIntOrNull() ?: 0,
        )
    }

    private fun withJson(link: String): String {
        val https = link.replaceFirst("http://", "https://")
        return if (https.contains("render=json")) https else https + (if ('?' in https) "&" else "?") + "render=json"
    }

    // ---------------- 공통 ----------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", userAgent)
        // 분류 이름을 영문으로 받아 앱에서 한글로 바꿈 (서비스가 주는 한국어 이름은 들쭉날쭉해서 칩 정리가 안 됨)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 설정 파일 internetPicks: [{"name","url","logo"?,"tags"?,"sub"?,"group"?}]
     * group이 같은 방송끼리 한 줄 (설정 파일에 처음 나온 순서), group이 없으면 defaultTitle 줄
     */
    fun parsePicks(a: JSONArray?, defaultTitle: String): List<Pair<String, List<WebStation>>> {
        if (a == null) return emptyList()
        val groups = LinkedHashMap<String, MutableList<WebStation>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val name = o.optString("name").trim()
            if (url.isEmpty() || name.isEmpty()) continue
            groups.getOrPut(o.optString("group").trim().ifEmpty { defaultTitle }) { mutableListOf() } += WebStation(
                name = name, url = url,
                logo = o.optString("logo").trim().ifBlank { null },
                sub = o.optString("sub").trim().ifBlank { null },
                tag = o.optString("tags").split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() },
            )
        }
        return groups.map { it.key to it.value.toList() }
    }
}
