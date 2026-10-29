package kr.ohnggni.kradio

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

private const val SHORT_MS = 5 * 60_000L // 5분 미만 = 날씨·캠페인 같은 짧은 편성

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

    /** 화면 표시용: 짧은 편성은 건너뛰고 앞뒤 본 프로그램을 보여줌 */
    fun display(epgId: String?, now: Long = System.currentTimeMillis()): Program? {
        val list = epgId?.let { byChannel[it] } ?: return null
        val idx = list.indexOfFirst { now >= it.start && now < it.stop }
        if (idx < 0) return null
        val cur = list[idx]
        if (cur.stop - cur.start >= SHORT_MS) return cur
        // 직전 본 프로그램이 10분 이내에 끝났으면 그것을, 아니면 다음 본 프로그램을
        list.subList(0, idx).lastOrNull { it.stop - it.start >= SHORT_MS }
            ?.takeIf { cur.start - it.stop <= 10 * 60_000L }
            ?.let { return it }
        return list.drop(idx + 1).firstOrNull { it.stop - it.start >= SHORT_MS } ?: cur
    }

    /** 꺼짐 예약용: 지금부터 이어지는 본 프로그램 최대 count개 (짧은 편성 제외) */
    fun upcoming(epgId: String?, now: Long = System.currentTimeMillis(), count: Int = 4): List<Program> {
        val list = epgId?.let { byChannel[it] } ?: return emptyList()
        return list.filter { it.stop > now && it.stop - it.start >= SHORT_MS }.take(count)
    }
    /** 켜짐 예약용: 지금부터 hours시간 안에 시작하는 본 프로그램 목록 */
    fun schedule(epgId: String?, now: Long = System.currentTimeMillis(), hours: Int = 36): List<Program> {
        val list = epgId?.let { byChannel[it] } ?: return emptyList()
        val end = now + hours * 3600_000L
        return list.filter { it.start > now && it.start < end && it.stop - it.start >= SHORT_MS }
    }

    val isEmpty: Boolean get() = byChannel.isEmpty()
}

object EpgRepository {

    private const val REFRESH_MS = 3 * 60 * 60 * 1000L // 3시간마다 갱신
    // 받기에 실패하면 짧게 자주 다시 시도 (차량 기기는 부팅 직후 테더링이 붙기 전에 앱이 먼저 뜸)
    private val RETRY_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    @Volatile private var memory: EpgData? = null
    @Volatile private var memoryLoadedAt = 0L   // 메모리에 올린(파일을 읽은) 시각
    @Volatile private var checkedAt = 0L        // 인터넷 갱신을 확인한 시각 (저장본만 읽은 경우는 제외)
    @Volatile private var memoryUrl: String? = null
    @Volatile private var memoryIds: Set<String> = emptySet()

    @Volatile private var failCount = 0
    /** 받기에 실패한 뒤 다음 시도 시각 (0 = 실패 상태 아님) */
    @Volatile var retryAt = 0L
        private set
    /** 마지막 받기가 실패해서 다시 받아야 하는 상태 */
    val needsRetry: Boolean get() = failCount > 0

    /** 마지막 다운로드 실패 사유 (null = 성공) */
    @Volatile var lastError: String? = null
        private set

    // 화면과 재생 서비스가 동시에 받으려 할 때 한 번에 하나씩만 (임시 파일 충돌 방지)
    private val loadLock = Mutex()

    /**
     * 편성표를 불러옴 (3시간 이내 저장본이 있으면 그대로 사용).
     * quiet = true(재생 서비스의 백그라운드 갱신)면 실패해도 화면 경고를 띄우지 않음.
     */
    suspend fun load(
        context: Context,
        url: String?,
        force: Boolean = false,
        quiet: Boolean = false,
        offline: Boolean = false,   // true면 인터넷에서 받지 않고 저장본만 사용
    ): EpgData = withContext(Dispatchers.IO) {
        loadLock.withLock {
            val now = System.currentTimeMillis()

            // 먼저 온 쪽이 방금 읽어뒀고 채널 구성도 같으면 그대로 사용
            // - 저장본만 원하는 경우(offline): 메모리에 있으면 충분
            // - 그 외: 인터넷 갱신을 3시간 안에 확인했을 때만. 저장본만 읽은 것은 확인으로 치지 않음
            //   (재생 목록·오토 목록이 저장본만 읽은 뒤 3시간 동안 새로 받지 않던 문제)
            val cached = memory
            if (cached != null && !force && url == memoryUrl && ChannelRepository.epgIds == memoryIds) {
                val fresh = if (offline) now - memoryLoadedAt < REFRESH_MS
                else now - checkedAt < REFRESH_MS && (failCount == 0 || now < retryAt)   // 실패 상태면 retryAt부터 다시
                if (fresh) return@withLock cached
            }

            val cache = File(context.filesDir, "epg_cache_${url.hashCode()}.xml")
            val cacheFresh = cache.exists() && now - cache.lastModified() < REFRESH_MS

            if (cacheFresh && !force && !offline) {   // 저장본이 아직 새것이면 다시 받을 것 없음
                failCount = 0
                retryAt = 0L
                checkedAt = now
            }
            if ((force || !cacheFresh) && url != null && !offline) {
                val result = downloadWithRetry(url, cache)
                checkedAt = now
                if (result.isSuccess) {
                    if (failCount > 0) Log.i("KRadio", "EPG 받기 성공 (실패 ${failCount}번 뒤)")
                    lastError = null
                    failCount = 0
                    retryAt = 0L
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "연결 실패"
                    val wait = RETRY_MS[minOf(failCount, RETRY_MS.size - 1)]
                    failCount++
                    retryAt = now + wait
                    Log.w("KRadio", "EPG 받기 실패${if (quiet) "(백그라운드)" else ""}: $msg → ${wait / 1000}초 뒤 다시 시도")
                    if (!quiet) lastError = msg
                }
            }

            val data = if (cache.exists()) {
                runCatching { parse(cache) }.getOrElse {
                    Log.w("KRadio", "EPG 파싱 실패: ${it.message}")
                    EpgData(emptyMap())
                }
            } else {
                EpgData(emptyMap())
            }

            memory = data
            memoryLoadedAt = now
            memoryUrl = url
            memoryIds = ChannelRepository.epgIds
            data
        }
    }

    /** 일시적인 네트워크 실패 대비: 실패하면 2초 뒤 한 번 더 */
    private suspend fun downloadWithRetry(url: String, dest: File): Result<Unit> {
        val first = runCatching { download(url, dest) }
        if (first.isSuccess) return first
        Log.w("KRadio", "EPG 받기 재시도: ${first.exceptionOrNull()?.message}")
        delay(2000)
        return runCatching { download(url, dest) }
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

        // 채널 설정에 있는 채널만, 어제~모레 범위만 메모리에 올림 (큰 편성표 파일 대비)
        val keep = ChannelRepository.epgIds.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val from = now - 24 * 3600_000L
        val to = now + 3 * 24 * 3600_000L

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
                            val c = p.getAttributeValue(null, "channel")
                            start = time(p.getAttributeValue(null, "start"))
                            stop = time(p.getAttributeValue(null, "stop"))
                            // 필요 없는 채널·기간은 건너뜀 (ch = null이면 아래에서 저장 안 함)
                            ch = if ((keep == null || c in keep) && stop > from && start < to) c else null
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