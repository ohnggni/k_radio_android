package kr.ohnggni.kradio

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

data class Program(
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String?,
)

class EpgData(private val byChannel: Map<String, List<Program>>) {
    /** 지금 방송 중인 프로그램 */
    fun current(epgId: String?, now: Long = System.currentTimeMillis()): Program? =
        epgId?.let { id -> byChannel[id]?.firstOrNull { now >= it.start && now < it.stop } }

    /** 다음 프로그램 */
    fun next(epgId: String?, now: Long = System.currentTimeMillis()): Program? =
        epgId?.let { id -> byChannel[id]?.firstOrNull { it.start >= now } }

    val isEmpty: Boolean get() = byChannel.isEmpty()
}

object EpgRepository {

    private const val CACHE_FILE = "epg_cache.xml"
    private const val REFRESH_MS = 3 * 60 * 60 * 1000L // 3시간마다 갱신

    @Volatile private var memory: EpgData? = null
    @Volatile private var memoryLoadedAt = 0L

    suspend fun load(context: Context, url: String?, force: Boolean = false): EpgData =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            memory?.let { if (!force && now - memoryLoadedAt < REFRESH_MS) return@withContext it }

            val cache = File(context.filesDir, CACHE_FILE)
            val cacheFresh = cache.exists() && now - cache.lastModified() < REFRESH_MS
            if ((force || !cacheFresh) && url != null) {
                runCatching { download(url, cache) }
                    .onFailure { Log.w("KRadio", "EPG 다운로드 실패: ${it.message}") }
            }

            val data = if (cache.exists()) {
                runCatching { parse(cache) }.getOrElse {
                    Log.w("KRadio", "EPG 파싱 실패: ${it.message}")
                    EpgData(emptyMap())
                }
            } else EpgData(emptyMap())

            memory = data
            memoryLoadedAt = now
            data
        }

    private fun download(url: String, dest: File) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            // 받다가 끊겨도 기존 파일이 깨지지 않게 임시 파일에 받은 뒤 교체
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(file: File): EpgData {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun time(s: String?): Long = s?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: 0L

        val map = HashMap<String, MutableList<Program>>()
        file.inputStream().use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")

            var ch: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var sub: String? = null

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "programme" -> {
                            ch = p.getAttributeValue(null, "channel")
                            start = time(p.getAttributeValue(null, "start"))
                            stop = time(p.getAttributeValue(null, "stop"))
                            title = null
                            sub = null
                        }
                        "title" -> if (ch != null) title = p.nextText()
                        "sub-title" -> if (ch != null) sub = p.nextText()
                    }
                    XmlPullParser.END_TAG -> if (p.name == "programme") {
                        val c = ch
                        val t = title
                        if (c != null && t != null && stop > start) {
                            map.getOrPut(c) { mutableListOf() }.add(Program(start, stop, t, sub))
                        }
                        ch = null
                    }
                }
                event = p.next()
            }
        }
        map.values.forEach { list -> list.sortBy { it.start } }
        return EpgData(map)
    }
}