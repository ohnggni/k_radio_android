package kr.ohnggni.kradio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/** 프로그램 표지 정보 하나 */
data class CoverProgram(
    val channelId: String,   // 채널 설정의 nowPlaying 값과 같은 체계
    val title: String,
    val image: String?,
    val persons: List<String>,
    val airTimes: List<AirTime>,
) {
    internal val key = CoverRepository.norm(title)
}

/** 정규 편성 시각 (분 단위, end가 start보다 작으면 자정을 넘김) */
data class AirTime(val days: Set<DayOfWeek>, val start: Int, val end: Int)

/**
 * 프로그램 표지 데이터 (채널 설정의 coverUrl, 서버가 하루 2번 갱신하는 암호문).
 * 부가 기능이라 받기·해석이 실패해도 조용히 표지만 안 나옴 (저장본이 있으면 그걸 씀).
 * 암호문은 보안 기능이 아니라 주소·내용이 그대로 검색되지 않게 하는 가림막
 */
object CoverRepository {
    private const val CACHE_FILE = "cover_cache.txt"
    private const val REFRESH_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 30 * 60 * 1000L   // 받기 실패 후 다시 시도까지

    private val mutex = Mutex()
    @Volatile private var byChannel: Map<String, List<CoverProgram>> = emptyMap()
    @Volatile private var loadedFrom: String? = null   // 지금 메모리 데이터의 주소
    @Volatile private var fetchedAt = 0L              // 마지막으로 새로 받은 시각
    @Volatile private var triedAt = 0L                // 마지막으로 받으러 가 본 시각

    /**
     * 필요하면 저장본을 읽고, 하루가 지났으면 새로 받음. 메모리 데이터가 바뀌었으면 true.
     * url이 null이면(설정에 없음) 기능을 끔
     */
    suspend fun ensure(context: Context, url: String?): Boolean = mutex.withLock {
        if (url == null) {
            val had = byChannel.isNotEmpty()
            byChannel = emptyMap(); loadedFrom = null
            return@withLock had
        }
        withContext(Dispatchers.IO) {
            var changed = false
            val cache = File(context.filesDir, CACHE_FILE)
            // 1) 처음이면 저장본부터 (주소가 바뀌었으면 저장본은 버림)
            if (loadedFrom != url && cache.exists()) {
                runCatching {
                    val lines = cache.readText().split('\n', limit = 2)
                    if (lines.size == 2 && lines[0] == url) {
                        byChannel = parse(ConfigCrypto.open(lines[1]))
                        loadedFrom = url
                        fetchedAt = cache.lastModified()
                        changed = true
                    }
                }.onFailure { Log.w("KRadio", "표지 저장본 읽기 실패: ${it.message}") }
            }
            // 2) 하루 지났거나 없으면 새로 받기 (실패 후엔 30분 쉼)
            val now = System.currentTimeMillis()
            val stale = loadedFrom != url || now - fetchedAt >= REFRESH_MS
            if (stale && now - triedAt >= RETRY_MS) {
                triedAt = now
                try {
                    val text = ChannelRepository.httpGet(url, emptyMap(), timeoutMs = 15_000)
                    val data = parse(ConfigCrypto.open(text))
                    if (data.isNotEmpty()) {
                        byChannel = data
                        loadedFrom = url
                        fetchedAt = now
                        changed = true
                        runCatching { cache.writeText(url + "\n" + text.trim()) }
                        Log.i("KRadio", "표지 데이터 받음: ${data.values.sumOf { it.size }}개 프로그램")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("KRadio", "표지 데이터 받기 실패: ${e.message}")
                }
            }
            changed
        }
    }

    /**
     * 지금 프로그램의 표지 찾기. titles는 우선순위대로(선곡 정보의 프로그램명, 편성표 제목).
     * 제목 정확 일치 → (충분히 긴 제목만) 한쪽이 다른 쪽을 포함하는 유일한 후보 → 정규 편성 시각 순
     */
    fun find(channelId: String, titles: List<String?>, now: LocalDateTime = LocalDateTime.now()): CoverProgram? {
        val list = byChannel[channelId] ?: return null
        val keys = titles.mapNotNull { t -> norm(t).takeIf { it.isNotEmpty() } }
        for (k in keys) list.firstOrNull { it.key == k }?.let { return it }
        for (k in keys) {
            if (k.length < 4) continue
            val c = list.filter { it.key.length >= 4 && (it.key.contains(k) || k.contains(it.key)) }
            if (c.size == 1) return c[0]
        }
        return byAirTime(list, now)
    }

    private fun byAirTime(list: List<CoverProgram>, now: LocalDateTime): CoverProgram? {
        val min = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        val yesterday = today.minus(1)
        return list.mapNotNull { p ->
            p.airTimes.firstOrNull { a ->
                if (a.end > a.start) today in a.days && min >= a.start && min < a.end
                else (today in a.days && min >= a.start) || (yesterday in a.days && min < a.end)
            }?.let { a -> p to ((a.end - a.start + 1440) % 1440) }
        }.minByOrNull { it.second }?.first   // 겹치면 편성 시간이 짧은(더 구체적인) 쪽
    }

    /** 제목 비교용: 괄호 속 내용·공백·기호 제거, 소문자 */
    internal fun norm(s: String?): String =
        (s ?: "").replace(Regex("[(\\[〈《<][^)\\]〉》>]*[)\\]〉》>]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .lowercase()

    internal fun parse(text: String): Map<String, List<CoverProgram>> {
        val root = JSONObject(text)
        if (root.optInt("version", 0) != 1) return emptyMap()
        val arr = root.optJSONArray("programs") ?: return emptyMap()
        val out = ArrayList<CoverProgram>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ch = o.optString("channelId").ifEmpty { null } ?: continue
            val title = o.optString("title").ifEmpty { null } ?: continue
            val persons = o.optJSONArray("persons")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }
            } ?: emptyList()
            val airs = o.optJSONArray("airTimes")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let(::airTime) }
            } ?: emptyList()
            out += CoverProgram(
                channelId = ch,
                title = title,
                image = if (o.isNull("image")) null else o.optString("image").trim().ifEmpty { null },
                persons = persons,
                airTimes = airs,
            )
        }
        return out.groupBy { it.channelId }
    }

    private fun airTime(o: JSONObject): AirTime? {
        val days = o.optJSONArray("days")?.let { a ->
            (0 until a.length()).mapNotNull { day(a.optString(it)) }.toSet()
        } ?: return null
        val start = hm(o.optString("start")) ?: return null
        // 끝 시각은 "21:59"처럼 끝 분을 포함하는 표기 → 1분 더해서 구간 끝으로
        val end = hm(o.optString("end"))?.let { (it + 1) % 1440 } ?: return null
        if (days.isEmpty()) return null
        return AirTime(days, start, end)
    }

    private fun hm(s: String): Int? {
        val p = s.split(":")
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return (h * 60 + m) % 1440
    }

    private fun day(code: String): DayOfWeek? = when (code.uppercase()) {
        "MON" -> DayOfWeek.MONDAY; "TUE" -> DayOfWeek.TUESDAY; "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY; "FRI" -> DayOfWeek.FRIDAY; "SAT" -> DayOfWeek.SATURDAY
        "SUN" -> DayOfWeek.SUNDAY; else -> null
    }
}
