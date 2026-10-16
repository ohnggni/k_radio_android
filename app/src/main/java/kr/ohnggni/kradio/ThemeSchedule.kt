package kr.ohnggni.kradio

import android.os.Build
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 테마: 폰 설정 따름 / 밝게 / 어둡게 / 일몰~일출 어둡게 / 시간 지정 (앱 화면과 위젯에 같이 적용)
 * 일몰~일출은 서울 기준으로 날짜마다 계산 (위치 권한·인터넷 불필요, 국내 지역 차이 10~15분 정도)
 */
object ThemeMode {
    const val KEY = "theme_mode"
    const val KEY_FROM = "theme_dark_from"   // 어둡게 시작 (자정부터 분)
    const val KEY_TO = "theme_dark_to"       // 밝게 시작
    const val FOLLOW = 0
    const val LIGHT = 1
    const val DARK = 2
    const val SUN = 3
    const val CUSTOM = 4
    val OPTIONS = listOf(FOLLOW, LIGHT, DARK, SUN, CUSTOM)
    const val DEFAULT_FROM = 19 * 60
    const val DEFAULT_TO = 7 * 60

    private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    fun get(c: Context): Int = sp(c).getInt(KEY, FOLLOW).takeIf { it in OPTIONS } ?: FOLLOW
    fun darkFrom(c: Context): Int = sp(c).getInt(KEY_FROM, DEFAULT_FROM).coerceIn(0, 1439)
    fun darkTo(c: Context): Int = sp(c).getInt(KEY_TO, DEFAULT_TO).coerceIn(0, 1439)

    fun save(c: Context, mode: Int, from: Int, to: Int) {
        sp(c).edit().putInt(KEY_FROM, from).putInt(KEY_TO, to).putInt(KEY, mode).apply()
    }

    fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** 선택지 이름 (설정 창) */
    fun optionLabel(v: Int): String = when (v) {
        LIGHT -> "밝게"
        DARK -> "어둡게"
        SUN -> "일몰~일출 어둡게"
        CUSTOM -> "시간 지정"
        else -> "폰 설정 따름"
    }

    /** 설정 화면에 보이는 현재 값 */
    fun label(v: Int, from: Int, to: Int): String = when (v) {
        SUN -> sunLabel()
        CUSTOM -> "${hm(from)}~${hm(to)} 어둡게"
        else -> optionLabel(v)
    }

    fun sunLabel(date: LocalDate = LocalDate.now(ZONE)): String =
        "일몰~일출 (${hm(sunMinutes(date, false))}~${hm(sunMinutes(date.plusDays(1), true))})"

    /** 지금 시각에 어둡게 그릴지: FOLLOW면 null(폰 설정에 맡김) */
    fun darkAt(c: Context, now: Long): Boolean? {
        val mode = get(c)
        return when (mode) {
            LIGHT -> false
            DARK -> true
            SUN, CUSTOM -> {
                val t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZONE)
                val m = t.hour * 60 + t.minute
                val (from, to) = window(c, mode, t.toLocalDate())
                if (from == to) false
                else if (from < to) m in from until to
                else m >= from || m < to
            }
            else -> null
        }
    }

    /** 앱 화면: 폰 설정 따름이면 폰 다크 모드 여부 */
    fun appDark(c: Context, systemDark: Boolean, now: Long): Boolean = darkAt(c, now) ?: systemDark

    /** 위젯에 넘기는 값: FOLLOW(런처에 맡김) 또는 지금 시각 기준 LIGHT/DARK */
    fun widgetMode(c: Context, now: Long = System.currentTimeMillis()): Int = when (darkAt(c, now)) {
        null -> FOLLOW
        true -> DARK
        false -> LIGHT
    }

    private fun window(c: Context, mode: Int, date: LocalDate): Pair<Int, Int> =
        if (mode == SUN) sunMinutes(date, false) to sunMinutes(date, true)
        else darkFrom(c) to darkTo(c)

    /** 다음에 밝기가 바뀌는 시각 (시간 기준 모드가 아니면 null) */
    fun nextChange(c: Context, now: Long): Long? {
        val mode = get(c)
        if (mode != SUN && mode != CUSTOM) return null
        val today = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZONE).toLocalDate()
        return (0L..2L).flatMap { d ->
            val date = today.plusDays(d)
            val (from, to) = window(c, mode, date)
            listOf(from, to).map { date.atStartOfDay(ZONE).plusMinutes(it.toLong()).toInstant().toEpochMilli() }
        }.filter { it > now }.minOrNull()
    }

    /** 서울 기준 일출(rise=true)·일몰 시각, 자정부터 분 (NOAA 근사식, 오차 1~2분) */
    fun sunMinutes(date: LocalDate, rise: Boolean): Int {
        val lat = 37.5665
        val lng = 126.978
        val zenith = 90.833
        fun rad(d: Double) = Math.toRadians(d)
        fun deg(r: Double) = Math.toDegrees(r)
        fun norm(d: Double) = ((d % 360) + 360) % 360
        val lngHour = lng / 15
        val t = date.dayOfYear + ((if (rise) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))))
        ra = (ra + floor(l / 90) * 90 - floor(ra / 90) * 90) / 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = ((cos(rad(zenith)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))).coerceIn(-1.0, 1.0)
        val h = (if (rise) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val ut = (((h + ra - 0.06571 * t - 6.622 - lngHour) % 24) + 24) % 24
        return (((ut + 9) * 60).roundToInt() % 1440 + 1440) % 1440
    }
}

/** 시간 기준 테마일 때 다음 전환 시각에 위젯을 다시 그리도록 알람 (몇 분 늦을 수 있음, 화면 꺼져 있으면 켤 때 반영) */
object ThemeAlarm {
    const val ACTION = "kr.ohnggni.kradio.THEME_TICK"

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7301,
        Intent(c, ScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c)
        am.cancel(pi)
        val at = ThemeMode.nextChange(c, System.currentTimeMillis()) ?: return
        // 켜짐 예약과 같은 권한: 있으면 정확한 알람(하루 2번뿐이라 배터리 부담 없음), 없으면 몇 분 늦을 수 있는 알람
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC, at + 2_000L, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC, at + 2_000L, pi)
        Log.i("KRadio", "테마 전환 알람: ${Date(at)}${if (exact) "" else " (비정확)"}")
    }
}
