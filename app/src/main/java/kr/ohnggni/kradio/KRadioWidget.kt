package kr.ohnggni.kradio

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.AndroidRemoteViews
import kotlinx.coroutines.delay
import androidx.glance.appwidget.action.actionStartService

// ---------------- 위젯에 표시할 상태 ----------------

object WidgetState {
    private const val KEY = "widget_state"

    data class Data(
        val channelId: String? = null,
        val name: String = "케이라디오",
        val program: String = "재생 버튼을 눌러보세요",
        val playing: Boolean = false,
        val logo: String? = null,
        val vol: Int = -1,          // 시스템 미디어 음량 (-1 = 모름)
        val volMax: Int = 15,
        val car: Boolean = false,   // 안드로이드 오토 연결 중 → 음량 줄은 앱 음량
        val appVol: Int = 100,      // 앱 음량 (0~300%)
    )

    fun toJson(d: Data): String = JSONObject()
        .put("id", d.channelId ?: "")
        .put("name", d.name)
        .put("program", d.program)
        .put("playing", d.playing)
        .put("logo", d.logo ?: "")
        .put("vol", d.vol)
        .put("volMax", d.volMax)
        .put("car", d.car)
        .put("appVol", d.appVol)
        .toString()

    fun fromJson(s: String?): Data? = runCatching {
        val o = JSONObject(s!!)
        Data(
            channelId = o.optString("id").ifEmpty { null },
            name = o.optString("name", "케이라디오"),
            program = o.optString("program"),
            playing = o.optBoolean("playing"),
            logo = o.optString("logo").ifEmpty { null },
            vol = o.optInt("vol", -1),
            volMax = o.optInt("volMax", 15),
            car = o.optBoolean("car", false),
            appVol = o.optInt("appVol", 100),
        )
    }.getOrNull()

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, d: Data) {
        sp(c).edit().putString(KEY, toJson(d)).apply()
    }

    fun read(c: Context): Data = fromJson(sp(c).getString(KEY, null)) ?: Data()
}

/** 위젯 상태 저장소에 최신 정보를 넣고 다시 그리게 함 */
object WidgetUpdater {
    val STATE = stringPreferencesKey("state")
    val FAVS = stringPreferencesKey("favs")

    suspend fun push(c: Context) {
        val ids = GlanceAppWidgetManager(c).getGlanceIds(KRadioWidget::class.java)
        if (ids.isEmpty()) return   // 위젯을 안 깔았으면 할 일 없음
        val state = WidgetState.toJson(WidgetState.read(c))
        val favs = favsJson(c)
        val widget = KRadioWidget()
        ids.forEach { id ->
            updateAppWidgetState(c, id) { p ->
                p[STATE] = state
                p[FAVS] = favs
            }
            widget.update(c, id)
        }
    }

    fun visibleChannels(c: Context): List<Channel> = runCatching {
        val base = ChannelRepository.loadCached(c) ?: emptyList()
        ChannelPrefs.visible(base, ChannelPrefs.read(c))
    }.getOrElse { emptyList() }

    /** 즐겨찾기: 채널 관리 순서, 숨긴 채널 제외 → [{id, name}] */
    fun favsJson(c: Context): String = runCatching {
        val favorites = ChannelPrefs.read(c).favorites
        val arr = JSONArray()
        visibleChannels(c)
            .filter { it.id in favorites }
            .take(ChannelPrefs.MAX_FAVORITES)
            .forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        arr.toString()
    }.getOrElse { "[]" }

    fun parseFavs(s: String?): List<Pair<String, String>> = runCatching {
        val a = JSONArray(s!!)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
    }.getOrElse { emptyList() }
}

// ---------------- 위젯 버튼 수신기 ----------------

class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PLAY_PAUSE = "kr.ohnggni.kradio.widget.PLAY_PAUSE"
        const val ACTION_PREV = "kr.ohnggni.kradio.widget.PREV"
        const val ACTION_NEXT = "kr.ohnggni.kradio.widget.NEXT"
        const val ACTION_PLAY_CHANNEL = "kr.ohnggni.kradio.widget.PLAY_CHANNEL"
        const val ACTION_VOL_UP = "kr.ohnggni.kradio.widget.VOL_UP"
        const val ACTION_VOL_DOWN = "kr.ohnggni.kradio.widget.VOL_DOWN"
        const val APP_STEP = 20   // 오토 연결 중 위젯 버튼 한 번 = 앱 음량 20%
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        // 재생/정지: 중복 누름 무시 + 누르는 즉시 버튼 모양부터 바꿈
        if (intent.action == ACTION_PLAY_PAUSE) {
            val sp = app.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong("widget_pp_at", 0L) < 1_200L) {
                pending.finish()
                return
            }
            sp.edit().putLong("widget_pp_at", now).apply()
            val st = WidgetState.read(app)
            WidgetState.save(app, st.copy(playing = !st.playing))
            CoroutineScope(Dispatchers.Main).launch { runCatching { WidgetUpdater.push(app) } }
        }
        when (val action = intent.action) {
            ACTION_VOL_UP, ACTION_VOL_DOWN -> CoroutineScope(Dispatchers.Main).launch {
                // 오토 연결 중: 폰 음량은 차 소리와 무관 → 앱 음량을 20%씩 조절
                val inCar = withContext(Dispatchers.IO) { CarLink.query(app) }
                if (inCar) {
                    val cur = AppVolume.get(app)
                    val next = if (action == ACTION_VOL_UP) (cur / APP_STEP + 1) * APP_STEP
                               else ((cur + APP_STEP - 1) / APP_STEP - 1) * APP_STEP
                    val v = next.coerceIn(AppVolume.MIN, AppVolume.MAX)
                    AppVolume.set(app, v)   // 재생 서비스가 바로 적용
                    WidgetState.save(app, WidgetState.read(app).copy(car = true, appVol = v))
                    runCatching { WidgetUpdater.push(app) }
                    pending.finish()
                    return@launch
                }
                val am = app.getSystemService(AudioManager::class.java)
                val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                // 한 단계 조절은 시스템에 맡김 (빠르게 여러 번 눌러도 순서대로 처리돼서 안 꼬임)
                runCatching {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (action == ACTION_VOL_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        0
                    )
                }
                // 반영된 실제 값을 읽어서 한 번만 그림 (아직 반영 전이면 잠깐 기다림)
                var now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (now == before) {
                    delay(150)
                    now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                WidgetState.save(
                    app,
                    WidgetState.read(app).copy(
                        vol = now,
                        volMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        car = false
                    )
                )
                runCatching { WidgetUpdater.push(app) }
                pending.finish()
            }

            else -> withController(app) { ctl ->
                when (action) {
                    ACTION_PREV, ACTION_NEXT -> {
                        if (ctl.mediaItemCount > 0) {
                            if (action == ACTION_NEXT) ctl.seekToNext() else ctl.seekToPrevious()
                        }
                        done(pending)
                    }

                    ACTION_PLAY_PAUSE -> {
                        if (ctl.playWhenReady) {
                            ctl.pause()
                            ctl.stop()
                            done(pending)
                        } else {
                            // 멈춰 있음: 위젯에 보이는 채널을 재생 (없으면 시작 시 재생 설정)
                            val target = WidgetState.read(app).channelId
                                ?: StartupSettings.resumeChannelId(app, WidgetUpdater.visibleChannels(app))
                            startInBackground(app, target, pending)
                        }
                    }

                    ACTION_PLAY_CHANNEL -> {
                        val id = intent.getStringExtra(Shortcuts.EXTRA_CHANNEL)
                        val idx = (0 until ctl.mediaItemCount)
                            .firstOrNull { ctl.getMediaItemAt(it).mediaId == id }
                        if (ctl.playWhenReady && idx != null) {
                            // 이미 재생 중: 이전·다음처럼 목록 안에서 바로 이동 (빠름)
                            ctl.seekTo(idx, C.TIME_UNSET)
                            done(pending)
                        } else {
                            startInBackground(app, id, pending)
                        }
                    }

                    else -> done(pending)
                }
            }
        }
    }

    /** 재생 서비스에 연결해서 작업 후 연결 해제 */
    private fun withController(c: Context, block: (MediaController) -> Unit) {
        val future = MediaController.Builder(
            c, SessionToken(c, ComponentName(c, PlaybackService::class.java))
        ).buildAsync()
        future.addListener({
            runCatching { block(future.get()) }
                .onFailure { Log.w("KRadio", "위젯 동작 실패: ${it.message}") }
            // 명령이 전달될 시간을 준 뒤 연결 해제
            Handler(Looper.getMainLooper()).postDelayed({ MediaController.releaseFuture(future) }, 1_000)
        }, ContextCompat.getMainExecutor(c))
    }

    private fun done(pending: PendingResult) {
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 1_000)
    }

    /** 켜짐 예약과 같은 방식으로 백그라운드에서 시작 (음량 유지, 바로 소리) */
    private fun startInBackground(c: Context, channelId: String?, pending: PendingResult) {
        if (channelId == null) {
            pending.finish()
            return
        }
        ScheduleStarter.playChannel(
            c, channelId, null, autoOff = 0, volume = null, fade = false, source = "위젯"
        ) { pending.finish() }
    }
}

// ---------------- 위젯 ----------------

class KRadioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KRadioWidget()
}

class KRadioWidget : GlanceAppWidget() {

    // 위젯 전용 상태 저장소: 값이 바뀌면 그리는 순간마다 최신 값을 읽음
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    private val small = DpSize(180.dp, 80.dp)
    private val large = DpSize(180.dp, 150.dp)
    private val wideLarge = DpSize(300.dp, 150.dp)   // 이 폭부터 재생 버튼과 음량을 한 줄에
    private val roomyWidth = 360.dp                   // 이 폭 미만이면 시계·음량 버튼을 조금 작게
    override val sizeMode = SizeMode.Exact   // 실제 위젯 크기를 받아서 음량 막대 폭 계산

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val fallbackState = WidgetState.read(context)
        val fallbackFavs = WidgetUpdater.favsJson(context)

        provideContent {
            val prefs = currentState<Preferences>()
            val st = WidgetState.fromJson(prefs[WidgetUpdater.STATE]) ?: fallbackState
            val favs = WidgetUpdater.parseFavs(prefs[WidgetUpdater.FAVS] ?: fallbackFavs)
            val logo = st.logo?.let { loadLogo(context, it) }
            GlanceTheme {
                WidgetContent(context, st, logo, favs)
            }
        }
    }

    @Composable
    private fun WidgetContent(
        c: Context,
        st: WidgetState.Data,
        logo: Bitmap?,
        favs: List<Pair<String, String>>,
    ) {
        val colors = GlanceTheme.colors
        val size = LocalSize.current
        val isLarge = size.height >= large.height
        val isWide = isLarge && size.width >= wideLarge.width
        val showClock = isWide
        val compact = size.width < roomyWidth            // 좁으면 시계·음량 버튼·간격을 조금 작게
        val wantVol = isLarge && (st.car || st.vol >= 0)
        val hasFavs = isLarge && favs.isNotEmpty()

        // 높이가 모자라면 잘리지 않게 줄을 뺌: 음량 줄(좁은 배치일 때만 따로 한 줄) 먼저, 그다음 즐겨찾기
        // 필요 높이(dp): 위아래 여백 24 + 정보 46 + 간격 12 + 재생 버튼 50
        val hv = size.height.value
        val baseH = 24f + 46f + 12f + 50f
        val volH = 10f + 42f
        val favH = 12f + 38f
        val volInline = isWide && wantVol
        val (volRow, showFavs) = when {
            isWide || !wantVol -> false to (hasFavs && hv >= baseH + favH)
            !hasFavs -> (hv >= baseH + volH) to false
            hv >= baseH + volH + favH -> true to true
            hv >= baseH + favH -> false to true
            else -> (hv >= baseH + volH) to false
        }
        // 높이가 넉넉하면(30dp 이상 남으면) 로고·버튼·즐겨찾기를 키워서 빈 공간을 채움 (+약 26dp)
        val needH = baseH + (if (volRow) volH else 0f) + (if (showFavs) favH else 0f)
        val big = hv >= needH + 30f

        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(colors.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---------- 1줄: 로고 + 채널 정보 (+ 넓으면 우상단 시계) ----------
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Row(
                    GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity(Intent(c, MainActivity::class.java))),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (logo != null) {
                        Image(
                            ImageProvider(logo),
                            contentDescription = st.name,
                            modifier = GlanceModifier.size(if (big) 56.dp else 46.dp).cornerRadius(10.dp)
                        )
                        Spacer(GlanceModifier.width(12.dp))
                    }
                    Column(GlanceModifier.defaultWeight()) {
                        Text(
                            st.name,
                            maxLines = 1,
                            style = TextStyle(
                                color = colors.onPrimaryContainer,
                                fontSize = if (big) 20.sp else 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            st.program,
                            maxLines = 1,
                            style = TextStyle(color = colors.onPrimaryContainer, fontSize = if (big) 15.sp else 14.sp)
                        )
                    }
                }
                if (showClock) {
                    Spacer(GlanceModifier.width(8.dp))
                    Clock(c, if (compact) 76.dp else 100.dp, small = compact)
                }
            }

            Spacer(GlanceModifier.height(12.dp))

            // ---------- 2줄: 재생 버튼 (+ 넓으면 오른쪽에 음량) ----------
            Row(
                GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isWide) Alignment.Start else Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 좁은 폭에서 음량과 한 줄이면 버튼은 키우지 않음 (게이지 자리 확보)
                Controls(c, st, big && !(volInline && compact))
                if (volInline) {
                    Spacer(GlanceModifier.width(if (compact) 10.dp else 16.dp))
                    VolumeControls(c, st, compact)
                }
            }

            // ---------- 좁은 큰 위젯: 음량은 아래 줄 가운데 ----------
            if (volRow) {
                Spacer(GlanceModifier.height(10.dp))
                Row(
                    GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VolumeControls(c, st, compact = false)
                }
            }

            // ---------- 즐겨찾기 ----------
            if (showFavs) {
                Spacer(GlanceModifier.height(12.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    favs.forEachIndexed { i, (id, name) ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        val on = id == st.channelId
                        Box(
                            GlanceModifier
                                .defaultWeight()
                                .height(if (big) 44.dp else 38.dp)
                                .cornerRadius(if (big) 22.dp else 19.dp)
                                .background(if (on) colors.primary else colors.surface)
                                .clickable(
                                    // 재생 중: 서비스에 직접 (빠름) / 정지 중: 수신기를 거쳐 재생 시작
                                    if (st.playing) {
                                        actionStartService(
                                            Intent(c, PlaybackService::class.java)
                                                .setAction(ACTION_WIDGET_PLAY_CHANNEL)
                                                .setData(Uri.parse("kradio://widget/svc/$id"))   // 채널마다 구분되게
                                                .putExtra(Shortcuts.EXTRA_CHANNEL, id)
                                        )
                                    } else {
                                        playChannelAction(c, id)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                name,
                                maxLines = 1,
                                style = TextStyle(
                                    color = if (on) colors.onPrimary else colors.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                ),
                                modifier = GlanceModifier.padding(horizontal = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /** 이전 · 재생/정지 · 다음 */
    @Composable
    private fun Controls(c: Context, st: WidgetState.Data, big: Boolean) {
        val side = if (big) 46.dp else 40.dp
        val main = if (big) 60.dp else 50.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_w_prev, "이전 채널", broadcast(c, WidgetActionReceiver.ACTION_PREV), side, filled = false)
            Spacer(GlanceModifier.width(10.dp))
            RoundButton(
                if (st.playing) R.drawable.ic_w_stop else R.drawable.ic_w_play,
                if (st.playing) "정지" else "재생",
                // 재생 중: 서비스에 직접 정지 (빠름) / 정지 중: 수신기를 거쳐 재생
                if (st.playing) {
                    actionStartService(
                        Intent(c, PlaybackService::class.java).setAction(ACTION_WIDGET_STOP)
                    )
                } else {
                    broadcast(c, WidgetActionReceiver.ACTION_PLAY_PAUSE)
                },
                main,
                filled = true
            )
            Spacer(GlanceModifier.width(10.dp))
            RoundButton(R.drawable.ic_w_next, "다음 채널", broadcast(c, WidgetActionReceiver.ACTION_NEXT), side, filled = false)
        }
    }

    /**
     * (−) 계단식 막대 (+) : 폰 음량 단계 수만큼 칸을 만들어서 버튼 한 번 = 한 칸.
     * Glance는 한 줄에 최대 10개까지만 그리므로 5칸씩 묶어서 배치.
     */
    @Composable
    private fun RowScope.VolumeControls(c: Context, st: WidgetState.Data, compact: Boolean) {
        val btn = if (compact) 36.dp else 42.dp
        val gap = if (compact) 6.dp else 10.dp
        val colors = GlanceTheme.colors
        // 오토 연결 중: 앱 음량 0~300%를 20%씩 15칸 (5칸 = 100%) / 평소: 폰 음량 단계
        val steps = if (st.car) AppVolume.MAX / WidgetActionReceiver.APP_STEP else st.volMax.coerceIn(5, 30)
        val filled = if (st.car) (st.appVol + WidgetActionReceiver.APP_STEP / 2) / WidgetActionReceiver.APP_STEP
                     else st.vol.coerceIn(0, steps)

        RoundButton(R.drawable.ic_w_minus, "음량 줄이기", broadcast(c, WidgetActionReceiver.ACTION_VOL_DOWN), btn, filled = false)
        Spacer(GlanceModifier.width(gap))

        Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Bottom) {
            (0 until steps).chunked(5).forEach { group ->
                Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Bottom) {
                    group.forEach { i ->
                        // 칸 사이 간격은 빈칸 대신 안쪽 여백으로 (칸 개수 절약)
                        Box(
                            GlanceModifier.defaultWeight().padding(horizontal = 1.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                GlanceModifier
                                    .fillMaxWidth()
                                    .height((8 + i * 16f / (steps - 1)).dp)   // 8dp → 24dp로 점점 높아지는 막대
                                    .cornerRadius(2.dp)
                                    .background(if (i < filled) colors.primary else colors.surface)
                            ) {}
                        }
                    }
                }
            }
        }

        if (st.car) {
            // 오토 연결 중임을 알리는 앱 음량 숫자
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "${st.appVol}%",
                maxLines = 1,
                style = TextStyle(color = colors.onPrimaryContainer, fontSize = 12.sp)
            )
        }
        Spacer(GlanceModifier.width(gap))
        RoundButton(R.drawable.ic_w_plus, "음량 키우기", broadcast(c, WidgetActionReceiver.ACTION_VOL_UP), btn, filled = false)
    }

    /** 날짜(작게) + 시각(크게): 채널명·방송명 두 줄 높이. 매분 자동 갱신 */
    @Composable
    private fun Clock(c: Context, width: Dp, small: Boolean) {
        val base = GlanceTheme.colors.onPrimaryContainer.getColor(c)
        val rv = RemoteViews(c.packageName, R.layout.widget_clock).apply {
            setTextColor(R.id.widget_date, base.copy(alpha = 0.7f).toArgb())
            setTextColor(R.id.widget_clock, base.toArgb())
            if (small) {
                // 좁은 위젯: 채널명 자리를 남기려고 시계를 작게
                setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextViewTextSize(R.id.widget_clock, TypedValue.COMPLEX_UNIT_SP, 22f)
            }
        }
        AndroidRemoteViews(rv, modifier = GlanceModifier.width(width))
    }

    @Composable
    private fun RoundButton(icon: Int, desc: String, action: Action, size: Dp, filled: Boolean) {
        val colors = GlanceTheme.colors
        Box(
            GlanceModifier
                .size(size)
                .cornerRadius(size / 2)
                .background(if (filled) colors.primary else colors.surface)
                .clickable(action),
            contentAlignment = Alignment.Center
        ) {
            Image(
                ImageProvider(icon),
                contentDescription = desc,
                colorFilter = ColorFilter.tint(if (filled) colors.onPrimary else colors.onSurface),
                modifier = GlanceModifier.size(size * 0.55f)
            )
        }
    }

    private fun broadcast(c: Context, action: String): Action =
        actionSendBroadcast(
            Intent(c, WidgetActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("kradio://widget/$action"))
        )

    /** 즐겨찾기: 앱을 열지 않고 바로 재생 */
    private fun playChannelAction(c: Context, channelId: String): Action =
        actionSendBroadcast(
            Intent(c, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_PLAY_CHANNEL)
                .setData(Uri.parse("kradio://widget/channel/$channelId"))
                .putExtra(Shortcuts.EXTRA_CHANNEL, channelId)
        )

    private fun loadLogo(c: Context, logo: String): Bitmap? {
        if (!logo.startsWith(LogoCache.ASSET_PREFIX)) return null
        return runCatching {
            c.assets.open(logo.removePrefix(LogoCache.ASSET_PREFIX)).use { BitmapFactory.decodeStream(it) }
                ?.let { Bitmap.createScaledBitmap(it, 128, 128, true) }
        }.getOrNull()
    }
}