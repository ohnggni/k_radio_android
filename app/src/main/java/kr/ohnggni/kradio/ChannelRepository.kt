package kr.ohnggni.kradio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import android.util.Log
import kotlinx.coroutines.delay

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val type: String,                 // "direct" 또는 "api"
    val url: String? = null,          // direct용 고정 주소
    val api: String? = null,          // api용 호출 주소
    val headers: Map<String, String> = emptyMap(),
    val extractJson: String? = null,  // 예: channel_item[media_type=radio].service_url
    val extractRegex: String? = null,
    val epg: String? = null,          // 편성표(XMLTV) 채널 아이디
    val logo: String? = null,         // 로고 (asset:///logos/... 또는 인터넷 주소)
    val nowPlaying: String? = null,   // 실시간 선곡 정보 채널 ID (없으면 선곡 정보 기능 끔)
    val cover: String? = null,        // 프로그램 표지 채널 ID (없으면 nowPlaying 값 사용. 선곡은 없고 표지만 있는 방송용)
)

object ChannelRepository {

    /** 설정 파일에 적힌 EPG 주소 */
    @Volatile var epgUrl: String? = null
        private set

    /** 실시간 선곡 정보 주소 틀. {id} 자리에 채널의 nowPlaying 값 */
    @Volatile var nowPlayingUrl: String? = null
        private set

    /** 선곡 정보 조회 주기(초, 30 미만은 30) */
    @Volatile var nowPlayingPollSeconds: Int = 30
        private set

    /** 선곡 정보 조회 때 보낼 User-Agent (없으면 기본값) */
    @Volatile var nowPlayingUserAgent: String? = null
        private set

    /** 프로그램 표지 데이터 암호문 주소 (없으면 표지 기능 끔) */
    @Volatile var coverUrl: String? = null
        private set

    /** 설정 파일의 logoBase (내장 로고가 없을 때만 예비로 사용) */
    @Volatile var logoBase: String? = null

    /** 인터넷 방송 찾기 화면의 "추천 방송" 줄 (설정 파일 internetPicks, 없으면 빈 목록 → 줄 숨김) */
    @Volatile var internetPicks: List<WebStation> = emptyList()
        private set

    /** 채널 설정에 있는 편성표 채널 ID들 (편성표에서 이 채널만 읽음) */
    @Volatile var epgIds: Set<String> = emptySet()
        private set

    /** 설정 파일에 적힌 최신 앱 버전과 다운로드 주소 */
    @Volatile var latestVersionCode: Long? = null
        private set
    @Volatile var latestVersionName: String? = null
        private set
    @Volatile var updateUrl: String? = null
        private set

    /** 마지막 불러오기에서 원격 접속이 실패한 사유 (null = 성공) */
    @Volatile var lastError: String? = null
        private set

    // 화면과 재생 서비스가 동시에 불러올 때 한 번에 하나씩만
    private val loadLock = Mutex()

    /** 원격 설정을 받아오고, 실패하면 이 주소로 마지막에 저장해둔 설정을 사용 */
    /**
     * 원격 설정을 받아오고, 실패하면 이 주소로 마지막에 저장해둔 설정을 사용.
     * quiet = true(재생 서비스의 백그라운드 갱신)면 실패해도 화면 경고를 띄우지 않음.
     */
    /** 마지막 load에서 원격 받기가 실패해 저장본을 썼는지 (네트워크 연결 시 다시 받기 판단용) */
    @Volatile var lastFetchFailed = false
        private set

    suspend fun load(context: Context, quiet: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        loadLock.withLock {
            val url = SourceSettings.configUrl(context)
            val cache = cacheFile(context, url)
            val legacy = legacyCache(context, url)

            val remote = fetchWithRetry(url)
            val remoteText = remote.getOrNull()?.takeIf { t -> runCatching { parse(t) }.isSuccess }
            val err = when {
                remoteText != null -> null
                remote.isFailure -> remote.exceptionOrNull()?.message ?: "연결 실패"
                else -> "설정 파일 형식이 올바르지 않음"
            }
            if (err != null) Log.w("KRadio", "채널 설정 받기 실패${if (quiet) "(백그라운드)" else ""}: $err")
            // 성공은 언제나 경고를 지우고, 실패는 화면에서 부른 경우만 경고로 남김
            if (err == null || !quiet) lastError = err
            lastFetchFailed = err != null

            val text = when {
                remoteText != null -> {
                    cache.writeText(remoteText)
                    remoteText
                }
                cache.exists() -> cache.readText()
                legacy?.exists() == true -> legacy.readText()
                else -> error("채널 설정을 불러올 수 없음")
            }
            parse(text)
        }
    }
    /** 네트워크 없이 저장본만 읽기 (예약 실행처럼 바로 시작해야 할 때) */
    fun loadCached(context: Context): List<Channel>? {
        val url = SourceSettings.configUrl(context)
        val file = cacheFile(context, url).takeIf { it.exists() }
            ?: legacyCache(context, url)?.takeIf { it.exists() }
        return file?.let { f -> runCatching { parse(f.readText()) }.getOrNull() }
    }

    /** 받은 그대로(기본 설정은 암호문) 저장하는 파일 */
    private fun cacheFile(context: Context, url: String) =
        File(context.filesDir, "channels_cache_${url.hashCode()}.json")

    /** 기본 주소를 쓸 때만: 1.10.1 이하가 옛 주소로 저장해 둔 평문 설정 (새 저장본이 생기면 지움) */
    private fun legacyCache(context: Context, url: String): File? {
        if (url != SourceSettings.DEFAULT_CONFIG_URL) return null
        val f = cacheFile(context, SourceSettings.LEGACY_CONFIG_URL)
        if (f.exists() && cacheFile(context, url).exists()) {
            f.delete()
            return null
        }
        return f
    }

    /** 일시적인 네트워크 실패 대비: 실패하면 2초 뒤 한 번 더 */
    private suspend fun fetchWithRetry(url: String): Result<String> {
        val first = runCatching { httpGet(url, emptyMap(), timeoutMs = 10_000) }
        if (first.isSuccess) return first
        Log.w("KRadio", "채널 설정 받기 재시도: ${first.exceptionOrNull()?.message}")
        delay(2000)
        return runCatching { httpGet(url, emptyMap(), timeoutMs = 10_000) }
    }

    /** 채널 하나를 실제 재생 가능한 스트림 주소로 변환 */
    suspend fun resolve(ch: Channel): String = withContext(Dispatchers.IO) {
        when (ch.type) {
            "direct" -> {
                val url = ch.url ?: error("[${ch.id}] url 없음")
                if (isPlaylistUrl(url)) resolvePlaylist(url, ch.headers)
                    ?: error("[${ch.id}] 재생 목록에 방송 주소 없음") else url
            }
            "api" -> {
                val body = httpGet(ch.api ?: error("[${ch.id}] api 없음"), ch.headers)
                    .replace("\\/", "/")
                ch.extractJson?.let { extractByPath(body, it) }
                    ?: ch.extractRegex?.let { pattern ->
                        Regex(pattern).find(body)?.let { m ->
                            m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value
                        }
                    }
                    ?: error("[${ch.id}] 주소 추출 실패")
            }
            else -> error("[${ch.id}] 알 수 없는 type: ${ch.type}")
        }
    }

    private fun parse(text: String): List<Channel> {
        val root = JSONObject(ConfigCrypto.open(text))
        epgUrl = root.optString("epgUrl").ifEmpty { null }
        logoBase = root.optString("logoBase").ifEmpty { null }
        nowPlayingUrl = root.optString("nowPlayingUrl").ifEmpty { null }
        nowPlayingPollSeconds = root.optInt("nowPlayingPollSeconds", 30).coerceAtLeast(30)
        nowPlayingUserAgent = root.optString("nowPlayingUserAgent").ifEmpty { null }
        coverUrl = root.optString("coverUrl").ifEmpty { null }
        internetPicks = RadioBrowser.parsePicks(root.optJSONArray("internetPicks"))
        latestVersionCode = root.optLong("latestVersionCode", 0L).takeIf { it > 0 }
        latestVersionName = root.optString("latestVersionName").ifEmpty { null }
        updateUrl = root.optString("updateUrl").ifEmpty { null }

        val headerSets = root.optJSONObject("headerSets")
        val arr = root.getJSONArray("channels")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val setName = o.optString("headers")
            val h = if (setName.isNotEmpty()) headerSets?.optJSONObject(setName) else null
            val headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap()
            val ex = o.optJSONObject("extract")
            // 파일 이름이면 앱 내장 로고, http로 시작하면 인터넷 주소
            val logo = o.optString("logo").ifEmpty { null }?.let {
                if (it.startsWith("http")) it else "${LogoCache.ASSET_PREFIX}logos/$it"
            }
            Channel(
                id = o.getString("id"),
                name = o.getString("name"),
                group = o.optString("group"),
                type = o.getString("type"),
                url = o.optString("url").ifEmpty { null },
                api = o.optString("api").ifEmpty { null },
                headers = headers,
                extractJson = ex?.optString("json")?.ifEmpty { null },
                extractRegex = ex?.optString("regex")?.ifEmpty { null },
                epg = o.optString("epg").ifEmpty { null },
                logo = logo,
                nowPlaying = o.optString("nowPlaying").ifEmpty { null },
                cover = o.optString("cover").ifEmpty { null },
            )
        }
        epgIds = list.mapNotNull { it.epg }.toSet()
        return list
    }

    /** "a[key=value].b" 형태의 간단한 JSON 경로 추출 */
    private fun extractByPath(body: String, path: String): String? {
        var cur: Any? = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val seg = Regex("""^(\w+)(?:\[(\w+)=([^\]]*)\])?$""")
        for (part in path.split('.')) {
            val m = seg.matchEntire(part) ?: return null
            val (key, filterKey, filterValue) = m.destructured
            cur = (cur as? JSONObject)?.opt(key) ?: return null
            if (filterKey.isNotEmpty()) {
                val arr = cur as? JSONArray ?: return null
                cur = (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString(filterKey) == filterValue } ?: return null
            }
        }
        return (cur as? String)?.takeIf { it.isNotEmpty() }
    }

    /**
     * 재생 목록 파일 주소인지 (.pls, .m3u). HLS(.m3u8)는 재생기가 직접 처리하므로 제외.
     * 인터넷 방송국의 "듣기" 링크는 대부분 이런 파일이고, 안에 실제 방송 주소가 적혀 있음
     */
    internal fun isPlaylistUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase() ?: return false
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /** 재생 목록을 받아 첫 번째 방송 주소를 꺼냄. PLS(File1=...)와 M3U(주석이 아닌 첫 줄) 모두 처리 */
    internal fun resolvePlaylist(url: String, headers: Map<String, String>): String? {
        val (body, finalUrl) = httpGetFollow(url, headers)
        val lines = body.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.toList()
        val found = lines.firstNotNullOfOrNull { line ->
            Regex("^File\\d+\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()
        } ?: lines.firstOrNull { line ->   // M3U: 주석(#)·구역([..])이 아닌 첫 주소 줄
            !line.startsWith("#") && !line.startsWith("[") &&
                (line.startsWith("http://", true) || line.startsWith("https://", true) || !line.contains("="))
        }
        val stream = found ?: return null
        // 상대 주소면 재생 목록 위치 기준으로
        val abs = runCatching { URI(finalUrl).resolve(stream).toString() }.getOrDefault(stream)
        Log.i("KRadio", "재생 목록 해석: $url → $abs")
        return abs
    }

    /**
     * 리디렉션을 직접 따라가며 받기. 기본 연결은 https→http 전환을 따라가지 않는데,
     * 인터넷 방송 사이트는 https 주소를 http로 돌려보내는 경우가 많음. 돌려주는 값: (본문, 최종 주소)
     */
    private fun httpGetFollow(url: String, headers: Map<String, String>, timeoutMs: Int = 8000): Pair<String, String> {
        var current = url
        repeat(5) {
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: error("HTTP $code 이동할 주소 없음: $current")
                    current = URI(current).resolve(loc).toString()
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code: $current")
                // 재생 목록은 작은 텍스트 파일. 실수로 방송 주소를 넣었을 때 끝없이 받지 않게 64KB까지만
                val bytes = conn.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (buf.size() < 65536) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                val text = runCatching {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }.getOrElse { String(bytes, charset("EUC-KR")) }   // 국내 방송 사이트는 옛 한글 인코딩도 씀
                return text to current
            } finally {
                conn.disconnect()
            }
        }
        error("리디렉션이 너무 많음: $url")
    }

    internal fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int = 5000): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}