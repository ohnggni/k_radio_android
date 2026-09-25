package kr.ohnggni.kradio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

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
    val logo: String? = null,         // 로고 이미지 전체 주소
)

object ChannelRepository {

    /** 설정 파일에 적힌 EPG 주소 (load 이후 사용 가능) */
    @Volatile
    var epgUrl: String? = null
        private set

    /** 설정 파일의 logoBase (내장 로고가 없을 때만 예비로 사용) */
    @Volatile
    var logoBase: String? = null
        private set

    /** 마지막 불러오기에서 원격 접속이 실패한 사유 (null = 성공) */
    @Volatile
    var lastError: String? = null
        private set

    /** 원격 설정을 받아오고, 실패하면 이 주소로 마지막에 저장해둔 설정을 사용 */
    suspend fun load(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        val url = SourceSettings.configUrl(context)
        val cache = File(context.filesDir, "channels_cache_${url.hashCode()}.json")

        val remote = runCatching { httpGet(url, emptyMap()) }
        val remoteText = remote.getOrNull()?.takeIf { t -> runCatching { parse(t) }.isSuccess }
        lastError = when {
            remoteText != null -> null
            remote.isFailure -> remote.exceptionOrNull()?.message ?: "연결 실패"
            else -> "설정 파일 형식이 올바르지 않음"
        }

        val text = when {
            remoteText != null -> {
                cache.writeText(remoteText)
                remoteText
            }
            cache.exists() -> cache.readText()
            else -> error("채널 설정을 불러올 수 없음")
        }
        parse(text)
    }

    /** 채널 하나를 실제 재생 가능한 m3u8 주소로 변환 */
    suspend fun resolve(ch: Channel): String = withContext(Dispatchers.IO) {
        when (ch.type) {
            "direct" -> ch.url ?: error("[${ch.id}] url 없음")
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
        val root = JSONObject(text)
        epgUrl = root.optString("epgUrl").ifEmpty { null }
        logoBase = root.optString("logoBase").ifEmpty { null }
        val headerSets = root.optJSONObject("headerSets")
        val arr = root.getJSONArray("channels")
        return (0 until arr.length()).map { i ->
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
            )
        }
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

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}