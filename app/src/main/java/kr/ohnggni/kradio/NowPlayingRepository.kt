package kr.ohnggni.kradio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime

/** 실시간 선곡 정보의 곡 하나 (시각은 epoch 밀리초) */
data class Track(
    val trackId: Long,
    val songTitle: String,
    val artistName: String?,
    val playedAt: Long,
    val endedAt: Long,
    val durationSeconds: Int,
    val albumTitle: String?,
    val albumArtUrl: String?,
)

/** 선곡 정보 응답. recentTracks는 최신순 ([0]이 가장 최근에 재생된 곡) */
data class NowPlaying(
    val channelId: String,
    val provided: Boolean,
    val programTitle: String?,
    val recentTracks: List<Track>,
)

/** 지금 곡 판정 결과 */
sealed class SongState {
    /** 재생 구간 안: 지금 나오는 곡 */
    data class Playing(val track: Track) : SongState()
    /** 구간이 끝남: 직전 곡 (토크·광고 구간일 수 있어 지금 곡으로 단정하지 않음) */
    data class JustPlayed(val track: Track) : SongState()
    /** 곡 정보 없음 */
    object None : SongState()
}

/**
 * 실시간 선곡 정보 받기·해석. 선곡 정보는 부가 기능이라
 * 어떤 실패(네트워크·404·파싱)도 예외로 내보내지 않고 null만 돌려줌
 */
object NowPlayingRepository {

    /** 채널의 선곡 정보를 한 번 조회. 실패하면 null */
    suspend fun fetch(urlTemplate: String, id: String): NowPlaying? = withContext(Dispatchers.IO) {
        try {
            val body = ChannelRepository.httpGet(urlTemplate.replace("{id}", id), emptyMap(), timeoutMs = 8_000)
            parse(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KRadio", "선곡 정보 받기 실패[$id]: ${e.message}")
            null
        }
    }

    internal fun parse(text: String): NowPlaying? = try {
        val o = JSONObject(text)
        val arr = o.optJSONArray("recentTracks")
        val tracks = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val t = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val title = t.str("songTitle") ?: return@mapNotNull null
            val played = parseTime(t.str("playedAt")) ?: return@mapNotNull null
            val ended = parseTime(t.str("endedAt")) ?: return@mapNotNull null
            Track(
                trackId = t.optLong("trackId", 0L),
                songTitle = title,
                artistName = t.str("artistName"),
                playedAt = played,
                endedAt = ended,
                durationSeconds = t.optInt("durationSeconds", 0),
                albumTitle = t.str("albumTitle"),
                albumArtUrl = fixAlbumArtUrl(t.str("albumArtUrl")),
            )
        }
        NowPlaying(
            channelId = o.str("channelId") ?: "",
            provided = o.optBoolean("provided", false),
            programTitle = o.str("programTitle"),
            recentTracks = tracks,
        )
    } catch (e: Exception) {
        Log.w("KRadio", "선곡 정보 해석 실패: ${e.message}")
        null
    }

    /** now 시각 기준 지금 곡 판정. 빈 제목·"."(시그널 곡)은 곡 없음 취급 */
    fun songState(np: NowPlaying?, nowMs: Long): SongState {
        if (np == null || !np.provided) return SongState.None
        val track = np.recentTracks.firstOrNull() ?: return SongState.None
        if (isPlaceholder(track.songTitle)) return SongState.None
        return if (nowMs >= track.playedAt && nowMs < track.endedAt) SongState.Playing(track)
        else SongState.JustPlayed(track)
    }

    internal fun isPlaceholder(title: String): Boolean {
        val t = title.trim()
        return t.isEmpty() || t == "."
    }

    /**
     * 앨범아트 주소 보정: "https://프록시/120/https://원본/..."처럼 주소가 두 겹이면
     * 뒤쪽 원본 주소만 사용 (이미지 로더가 앞쪽 프록시 주소로 못 받는 경우 대비)
     */
    internal fun fixAlbumArtUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // 바로 앞이 "/"인 두 번째 주소만 (쿼리의 ?u=http://... 같은 건 건드리지 않음)
        val second = Regex("(?<=/)https?://").find(url, 1)?.range?.first
        return second?.let { url.substring(it) } ?: url
    }

    private fun parseTime(s: String?): Long? =
        s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** JSON null·빈 문자열은 null (org.json의 optString은 null 값을 "null"로 돌려줌) */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
