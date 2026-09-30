package kr.ohnggni.kradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 자동 꺼짐: 0 = 안 함, 양수 = 분, 이 값 = 방송 끝나면 */
const val AUTO_OFF_PROGRAM_END = -1

/** 켜짐 예약 하나 */
data class PlaySchedule(
    val id: String = "sch_${System.currentTimeMillis()}",
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = emptySet(),   // Calendar.MONDAY 등. 비어 있으면 한 번만
    val channelId: String = "",
    val volume: Int = 50,              // 시스템 미디어 음량 (%)
    val autoOff: Int = 0,
    val label: String? = null,         // 방송에서 골랐으면 방송 이름
)

object PlaySchedules {
    const val KEY = "play_schedules"

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    fun read(c: Context): List<PlaySchedule> = runCatching {
        val a = JSONArray(sp(c).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PlaySchedule(
                id = o.getString("id"),
                enabled = o.optBoolean("enabled", true),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getInt(it) }.toSet() }
                    ?: emptySet(),
                channelId = o.getString("channelId"),
                volume = o.optInt("volume", 50),
                autoOff = o.optInt("autoOff", 0),
                label = o.optString("label").ifEmpty { null },
            )
        }
    }.getOrElse { emptyList() }

    fun write(c: Context, list: List<PlaySchedule>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id)
                put("enabled", s.enabled)
                put("hour", s.hour)
                put("minute", s.minute)
                put("days", JSONArray(s.days.toList()))
                put("channelId", s.channelId)
                put("volume", s.volume)
                put("autoOff", s.autoOff)
                s.label?.let { put("label", it) }
            })
        }
        sp(c).edit().putString(KEY, a.toString()).apply()
    }
}

// ---------------- 표시·계산 도우미 ----------------

val WEEK_ORDER = listOf(
    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
    Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
)

fun dayName(d: Int): String = when (d) {
    Calendar.MONDAY -> "월"
    Calendar.TUESDAY -> "화"
    Calendar.WEDNESDAY -> "수"
    Calendar.THURSDAY -> "목"
    Calendar.FRIDAY -> "금"
    Calendar.SATURDAY -> "토"
    else -> "일"
}

fun PlaySchedule.timeLabel(): String = "%02d:%02d".format(hour, minute)

fun PlaySchedule.daysLabel(): String {
    val weekdays = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    val weekend = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
    return when {
        days.isEmpty() -> "한 번"
        days.size == 7 -> "매일"
        days == weekdays -> "평일"
        days == weekend -> "주말"
        else -> WEEK_ORDER.filter { it in days }.joinToString(",") { dayName(it) }
    }
}

fun PlaySchedule.autoOffLabel(): String = when {
    autoOff == 0 -> ""
    autoOff == AUTO_OFF_PROGRAM_END -> "방송 끝나면 꺼짐"
    else -> "${autoOff}분 뒤 꺼짐"
}

/** 다음 실행 시각 (반복이면 해당 요일 중 가장 가까운 때, 한 번이면 오늘 또는 내일) */
fun PlaySchedule.nextTrigger(now: Long = System.currentTimeMillis()): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (days.isEmpty()) {
        if (c.timeInMillis <= now) c.add(Calendar.DATE, 1)
        return c.timeInMillis
    }
    repeat(8) {
        if (c.timeInMillis > now && c.get(Calendar.DAY_OF_WEEK) in days) return c.timeInMillis
        c.add(Calendar.DATE, 1)
    }
    return c.timeInMillis
}