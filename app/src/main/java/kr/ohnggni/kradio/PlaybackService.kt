package kr.ohnggni.kradio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

const val ACTION_WIDGET_STOP = "kr.ohnggni.kradio.widget.STOP"
const val ACTION_WIDGET_PLAY_CHANNEL = "kr.ohnggni.kradio.widget.PLAY_CHANNEL_DIRECT"
private const val SCHEME = "kradio"
private const val RESOLVE_CACHE_MS = 10 * 60 * 1000L // 해석한 주소 10분간 재사용
private const val MAX_RETRY_DELAY_MS = 30_000L       // 재연결 최대 대기 간격

private const val BASE_REFRESH_MS = 30 * 60 * 1000L    // 채널 설정 30분마다 새로 확인

// 안드로이드 오토 목록 ID (채널 ID와 겹치지 않게 '@'로 시작)
private const val ROOT_ID = "@root"
private const val FAV_ID = "@fav"
private const val ALL_ID = "@all"
private const val ALL_GRID_ID = "@allgrid"

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // GitHub 기본 채널 (한 번 받아서 재사용)
    @Volatile
    private var baseChannels: List<Channel>? = null

    @Volatile
    private var baseLoadedAt = 0L

    // 사용자 설정이 반영된 전체 채널 (숨김 포함, 주소 해석용)
    @Volatile
    private var allChannels: List<Channel> = emptyList()

    // 채널 관리 화면에서 설정을 바꾸면 재생 목록에 바로 반영
    // 채널 관리·출처·타이머 설정이 바뀌면 바로 반영
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ChannelPrefs.KEY || key == SourceSettings.KEY_CONFIG) scope.launch { refreshPlaylist() }
        if (key == SleepTimer.KEY) scheduleSleep()
        if (key == AppVolume.KEY) {
            applyVolume()
            updateWidget()   // 위젯 음량 게이지(오토 연결 중엔 앱 음량)
        }
        if (key == ScheduledStart.KEY) onScheduledStartRequested()
    }

    // 스트림 서버(호스트)별로 붙일 헤더 (Referer 등)
    private val hostHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // 채널 ID → (해석된 주소, 해석 시각)
    private val resolvedCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // 마지막으로 들은 채널 저장용
    private val prefs by lazy { getSharedPreferences("kradio", MODE_PRIVATE) }

    // 자동 재연결 상태
    private var retryJob: Job? = null
    private var retryCount = 0

    // 알림·잠금화면·워치·차량에 보낼 방송 정보 갱신용
    private var nowPlayingJob: Job? = null

    // 취침 타이머
    private var sleepJob: Job? = null
    private var fadingOut = false

    // 앱 음량 (100% 이하는 플레이어 음량, 초과는 증폭 효과)
    private var loudness: LoudnessEnhancer? = null
    private var baseVolume = 1f

    // 켜짐 예약: 서서히 커지기, 실패 감시
    private var fadingIn = false
    private var fadeJob: Job? = null
    private var scheduleWatchJob: Job? = null

    private var focusDeferred = false   // 예약 재생 중: 앱을 열 때까지 오디오 포커스 요청 보류

    private var lastWidget: WidgetState.Data? = null
    private var silenceJob: Job? = null

    // 안드로이드 오토 연결 여부 (위젯 음량 표시·무음 감시에 사용)
    @Volatile private var carConnected = false
    private var carReceiver: BroadcastReceiver? = null
    // 폰 볼륨 버튼으로 음량이 바뀌면 위젯 음량 막대도 갱신
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = updateWidget()
    }
    private var pausedAt = 0L   // 멈춘 시각 (재개할 때 라이브 지점으로 갈지 판단)

    // 오디오 속성 (예약 재생 때 포커스 처리를 잠시 껐다 켜기 위해 보관)
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // 로고 주소(content://): 오토·알림이 직접 불러감. 모든 채널에 붙임
    private fun logoUri(ch: Channel): Uri? = LogoProvider.uriFor(this, effectiveLogo(ch))

    // 앱에 내장된 로고 파일 목록
    private val bundledLogos: Set<String> by lazy {
        runCatching { assets.list("logos")?.toSet() }.getOrNull() ?: emptySet()
    }

    /**
     * 실제로 쓸 로고. 설정 파일에만 새로 추가돼서 앱에 내장되지 않은 로고는
     * 설정 파일의 logoBase 인터넷 주소로 바꿔서, 인터넷 로고처럼 내려받아 씀 (앱 화면과 같은 방식)
     */
    private fun effectiveLogo(ch: Channel): String? {
        val logo = ch.logo ?: return null
        if (!logo.startsWith(LogoCache.ASSET_PREFIX)) return logo
        val name = logo.substringAfterLast('/')
        if (name in bundledLogos) return logo
        val base = ChannelRepository.logoBase ?: return logo
        return base + name
    }

    // 로고 이미지 데이터: 갤럭시 워치 플러그인(Media3 컨트롤러)은 주소를 직접 열지 않아서
    // 재생 중인 채널에만 데이터로도 붙임 (큐 전체에 붙이면 전달 데이터가 너무 커짐)
    private val logoBytesCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * 세션이 바깥(오토·워치·알림·플로팅 뮤직 같은 외부 앱)으로 내보내는 '지금 재생 중' 정보.
     * 재생 중 채널 로고를 그림 데이터로 넣고 주소는 뺌. 외부 앱은 주소가 있으면 주소부터 여는데
     * 권한이 없어 실패하기 때문. 채널이 바뀌는 순간 처음 나가는 정보부터 그림이 들어 있어서 한 번만 그려짐.
     */
    private fun nowPlayingMeta(m: MediaMetadata): MediaMetadata {
        val id = exoPlayer?.currentMediaItem?.mediaId ?: return m
        val ch = allChannels.firstOrNull { it.id == id } ?: return m
        val data = logoBytes(ch) ?: return m          // 아직 못 받은 인터넷 로고: 주소 그대로
        if (m.artworkUri == null && m.artworkData?.contentEquals(data) == true) return m
        return m.buildUpon()
            .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setArtworkUri(null)
            .build()
    }

    private fun logoBytes(ch: Channel): ByteArray? {
        val logo = effectiveLogo(ch) ?: return null
        // 인터넷 로고: 미리 받아둔 저장본이 있으면 그걸로 (없으면 다음 갱신 때)
        if (!logo.startsWith(LogoCache.ASSET_PREFIX)) {
            return logoBytesCache[logo] ?: LogoProvider.remoteBytes(this, logo)?.also { logoBytesCache[logo] = it }
        }
        return logoBytesCache[logo] ?: runCatching {
            assets.open(logo.removePrefix(LogoCache.ASSET_PREFIX)).use { it.readBytes() }
        }.getOrNull()?.also { logoBytesCache[logo] = it }
    }

    // 네트워크 복구 감지
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                val exo = exoPlayer ?: return@launch
                if (exo.playWhenReady && exo.playerError != null) {
                    Log.i("KRadio", "네트워크 복구 → 즉시 재연결")
                    retryJob?.cancel()
                    retryNow()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        // kradio:// 주소는 재생 직전에 실제 주소로 해석, 그 외에는 방송사 헤더만 첨부
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
            resolveSpec(spec)
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL // 마지막 채널 다음 → 첫 채널
        exoPlayer = exo

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { prefs.edit().putString("last_channel", it).apply() }
                // 방송 정보만 바뀐 경우(PLAYLIST_CHANGED)는 재연결 시도를 취소하지 않음
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    cancelRetry()
                    scope.launch { updateNowPlaying() }
                updateWidget()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry(error.errorCodeName)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && retryCount > 0) {
                    Log.i("KRadio", "재연결 성공")
                    cancelRetry()
                }
                // 켜짐 예약으로 켠 재생이 실제로 시작됨
                if (isPlaying) {
                    ScheduledStart.get(this@PlaybackService)?.let { p ->
                        ScheduledStart.clear(this@PlaybackService)
                        scheduleWatchJob?.cancel()
                        if (p.volume >= 0) applyScheduledVolume(p.channelName, p.volume)
                        if (p.fade) {
                            fadeIn()
                        } else {
                            // 위젯 등: 바로 원래 음량으로
                            fadingIn = false
                            exoPlayer?.volume = baseVolume
                        }
                        scope.launch {
                            applyAutoOff(p.autoOff)
                            // 포그라운드 서비스가 된 뒤 포커스 처리 재개 (targetSdk 36 기준 허용)
                            delay(1_500)
                            exoPlayer?.setAudioAttributes(audioAttrs, true)
                            focusDeferred = false
                            Log.i("KRadio", "켜짐 예약: 오디오 포커스 처리 재개")
                        }
                        Log.i("KRadio", "켜짐 예약 재생 시작: ${p.channelName}")
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    // 사용자가 정지하면 재연결 중단 + 정지 시각 기록 (다음 실행 시 자동 재생 판단용)
                    cancelRetry()
                    fadeJob?.cancel()
                    pausedAt = System.currentTimeMillis()
                    prefs.edit().putLong("stopped_at", System.currentTimeMillis()).apply()
                    // 직접 정지하면 꺼짐 예약도 취소 (전화 수신 등 자동 일시정지는 유지)
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !fadingOut) {
                        SleepTimer.set(this@PlaybackService, null)
                        // 켜짐 예약 연결 중에 직접 끈 거면 실패 알림 안 띄움
                        scheduleWatchJob?.cancel()
                        ScheduledStart.clear(this@PlaybackService)
                        exoPlayer?.setAudioAttributes(audioAttrs, true)
                        focusDeferred = false
                    }
                } else {
                    resumeAtLiveEdgeIfStale()
                }
                updateWidget()
            }

            // 통화·내비 음성 등으로 잠시 소리를 양보했다가 돌아올 때
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    pausedAt = System.currentTimeMillis()
                } else {
                    resumeAtLiveEdgeIfStale()
                }
            }
            // 오디오 세션이 바뀌면 증폭 효과를 새 세션에 다시 연결
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                loudness?.release()
                loudness = null
                applyVolume()
                scheduleSleep()
                applyVolume()
                onScheduledStartRequested()   // 예약이 서비스를 새로 띄운 경우
            }
        })

        val player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                availableCommands.contains(command)

            // 라디오는 이전/다음 = 항상 채널 이동 (라이브 구간 처음으로 가는 동작 방지)
            override fun seekToNext() {
                exo.seekToNextMediaItem()
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            }

            override fun seekToPrevious() {
                exo.seekToPreviousMediaItem()
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            }

            // 세션이 읽어가는 '지금 재생 중' 정보: 재생 중 채널 로고를 그림 데이터로 (nowPlayingMeta)
            override fun getMediaMetadata(): MediaMetadata = nowPlayingMeta(super.getMediaMetadata())

            // 세션·외부 기기에 알리는 리스너는 TimelineFilter로 감싸서 등록
            // (오토 화면 깜빡임 방지 + '지금 재생 중' 정보 변환)
            private val filters = ConcurrentHashMap<Player.Listener, Player.Listener>()

            override fun addListener(listener: Player.Listener) {
                val f = TimelineFilter(listener) { nowPlayingMeta(it) }
                filters[listener] = f
                super.addListener(f)
            }

            override fun removeListener(listener: Player.Listener) {
                super.removeListener(filters.remove(listener) ?: listener)
            }
        }

        // 알림/외부 기기에서 세션을 누르면 앱 화면 열기
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback())
            // 라이브 라디오는 재생 위치가 의미 없음 → 3초마다 위치 알림 끔 (오토 큐 화면이 맨 위로 튀는 문제)
            .setPeriodicPositionUpdateEnabled(false)
            .setSessionActivity(openApp)
            .build()

        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        // 새로 시작할 땐 모름(해제)으로 두고, 오토가 접속해오거나 연결 신호가 오면 확인
        CarLink.store(this, false)
        carReceiver = CarLink.watch(this, scope) { setCarConnected(it) }

        // 매분 정각마다 현재 방송 정보 갱신
        nowPlayingJob = scope.launch {
            while (true) {
                updateNowPlaying()
                val now = System.currentTimeMillis()
                delay(60_000L - now % 60_000L + 1_000L)
            }
        }
        scheduleSleep()
        applyVolume()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        watchSilence()
    }

    // ---------------- 자동 재연결 ----------------

    private fun scheduleRetry(reason: String) {
        val exo = exoPlayer ?: return
        if (!exo.playWhenReady) return              // 사용자가 정지한 상태면 재시도 안 함
        if (retryJob?.isActive == true) return      // 이미 예약됨
        val delayMs = (1000L shl retryCount.coerceAtMost(5)).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryCount++
        Log.w("KRadio", "재연결 예약: ${delayMs}ms 후 (${retryCount}번째) - $reason")
        retryJob = scope.launch {
            delay(delayMs)
            // 방송사 주소가 바뀌어 끊겼을 수 있으니 채널 설정도 새로 받아서 반영
            refreshBase(force = true)
            runCatching { getChannels() }
            retryNow()
        }
    }

    private fun retryNow() {
        val exo = exoPlayer ?: return
        if (!exo.playWhenReady) return
        // 토큰 만료 대비: 캐시된 주소를 버리고 새로 해석하게 함
        exo.currentMediaItem?.mediaId?.let { resolvedCache.remove(it) }
        exo.seekToDefaultPosition() // 라이브 최신 지점으로
        exo.prepare()
    }
    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
    }
    /** 3초 넘게 멈췄다 재개하면, 쌓여 있던 지난 방송 대신 지금 방송(라이브 지점)부터 */
    private fun resumeAtLiveEdgeIfStale() {
        val exo = exoPlayer ?: return
        val paused = pausedAt
        pausedAt = 0L
        if (paused == 0L || System.currentTimeMillis() - paused < 3_000L) return
        if (exo.playbackState == Player.STATE_IDLE) return   // 정지 후 재생은 어차피 새로 받음
        exo.seekToDefaultPosition()
        Log.i("KRadio", "재개: 라이브 지점으로 이동 (${(System.currentTimeMillis() - paused) / 1000}초 멈춤)")
    }
    // ---------------- 무음 재생 감지 ----------------

    /** 오토 연결 상태가 바뀌면 저장(위젯·앱 화면이 읽음)하고 위젯 음량 표시를 바꿈 */
    private fun setCarConnected(on: Boolean) {
        CarLink.store(this, on)
        if (on == carConnected) return
        carConnected = on
        Log.i("KRadio", "안드로이드 오토 ${if (on) "연결" else "해제"}")
        updateWidget()
    }

    /** 오토에 직접 조회 (오토가 깨어 있을 때만 부를 것: 접속해왔을 때, 폰 음량이 0일 때) */
    private suspend fun checkCar(): Boolean {
        val on = withContext(Dispatchers.IO) { CarLink.query(this@PlaybackService) }
        setCarConnected(on)
        return on
    }

    /** 소리가 꺼진 채로 1분 재생되면 배터리 절약을 위해 정지 */
    private fun watchSilence() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            var mutedSince = 0L
            while (true) {
                delay(15_000)
                val exo = exoPlayer ?: break
                if (!exo.isPlaying) {
                    mutedSince = 0L
                    continue
                }
                val am = getSystemService(AudioManager::class.java)
                // 차량(안드로이드 오토 등)이 음량을 관리할 때는 폰 음량 값을 믿을 수 없어서 무시
                // (오토 연결 중엔 폰 스피커 음량이 0이어도 차에서는 소리가 남)
                // 폰 음량이 0일 때만 오토 연결을 확인 (평소엔 오토를 깨우지 않음)
                val phoneMuted = !am.isVolumeFixed &&
                        (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC))
                val sysMuted = phoneMuted && !(carConnected || checkCar())
                val muted = AppVolume.get(this@PlaybackService) == 0 || sysMuted
                if (!muted) {
                    mutedSince = 0L
                    continue
                }
                if (mutedSince == 0L) mutedSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - mutedSince >= 60_000L) {
                    Log.i("KRadio", "무음 재생 1분 → 자동 정지")
                    exo.pause()
                    exo.stop()
                    ScheduleNotifier.show(
                        this@PlaybackService, 2004,
                        "케이라디오를 정지했어요",
                        "소리가 꺼진 채로 1분 동안 재생되어 배터리 절약을 위해 정지했어요.",
                        channel = "general",
                        channelName = "재생 안내"
                    )
                    mutedSince = 0L
                }
            }
        }
    }
    // ---------------- 위젯 ----------------

    /** 지금 채널·방송·재생 상태를 위젯에 반영 (바뀐 게 있을 때만) */
    private fun updateWidget() {
        val exo = exoPlayer ?: return
        val item = exo.currentMediaItem
        val ch = allChannels.firstOrNull { it.id == item?.mediaId }
        val am = getSystemService(AudioManager::class.java)
        val data = WidgetState.Data(
            channelId = item?.mediaId,
            name = ch?.name ?: item?.mediaMetadata?.title?.toString() ?: "케이라디오",
            program = item?.mediaMetadata?.artist?.toString().orEmpty(),
            playing = exo.playWhenReady,
            logo = ch?.logo,
            vol = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            volMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            car = carConnected,
            appVol = AppVolume.get(this),
        )
        // 위젯에 저장된 값과 같을 때만 건너뜀 (위젯 버튼이 미리 바꾼 모양이 실제와 다르면 바로잡음)
        if (data == WidgetState.read(this)) {
            lastWidget = data
            return
        }
        lastWidget = data
        WidgetState.save(this, data)
        scope.launch { runCatching { WidgetUpdater.push(this@PlaybackService) } }
    }
    // ---------------- 취침 타이머 ----------------

    /** 설정된 시각이 되면 10초 동안 소리를 줄인 뒤 정지 */
    private fun scheduleSleep() {
        sleepJob?.cancel()
        val at = SleepTimer.get(this) ?: return
        // 서비스가 꺼져 있는 동안 지나가버린 타이머는 정리만 함
        if (at < System.currentTimeMillis() - 60_000L) {
            SleepTimer.set(this, null)
            return
        }
        sleepJob = scope.launch {
            // 인터넷 방송은 몇 초 늦게 나오므로, 정한 시각에 줄이기 시작해서 10초 뒤 정지
            val wait = at - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val exo = exoPlayer ?: return@launch
            fadingOut = true
            try {
                val steps = 20
                for (i in steps downTo 1) {
                    if (!exo.playWhenReady) break
                    exo.volume = baseVolume * i / steps.toFloat()
                    delay(SleepTimer.FADE_MS / steps)
                }
                if (exo.playWhenReady) {
                    exo.pause()
                    exo.stop()
                }
                Log.i("KRadio", "취침 타이머: 재생 중지")
                SleepTimer.set(this@PlaybackService, null)
            } finally {
                // 중간에 취소돼도 음량은 원래대로
                exo.volume = baseVolume
                fadingOut = false
            }
        }
    }
    // ---------------- 앱 음량 ----------------

    /** 귀에 느껴지는 크기 기준: 200% = +10dB, 50% = -10dB, 0% = 무음. 케이라디오 소리에만 적용 */
    private fun applyVolume() {
        val exo = exoPlayer ?: return
        val pct = AppVolume.get(this)
        val db = if (pct > 0) 10 * log2(pct / 100.0) else 0.0

        // 줄일 때는 플레이어 음량으로, 키울 때는 증폭 효과로
        baseVolume = when {
            pct == 0 -> 0f
            db < 0 -> 10.0.pow(db / 20).toFloat()
            else -> 1f
        }
        if (!fadingOut && !fadingIn) exo.volume = baseVolume

        val gainMb = if (pct > 100) (db * 100).roundToInt() else 0   // dB → 밀리벨
        runCatching {
            if (gainMb > 0) {
                val le = loudness ?: LoudnessEnhancer(exo.audioSessionId).also { loudness = it }
                le.setTargetGain(gainMb)
                le.enabled = true
            } else {
                loudness?.enabled = false
            }
        }.onFailure { Log.w("KRadio", "음량 증폭 실패: ${it.message}") }
        Log.i("KRadio", "앱 음량 ${pct}%${if (pct > 0) " (${"%.1f".format(db)}dB)" else " (무음)"}")
    }
    // ---------------- 켜짐 예약 ----------------

    /** 예약으로 켜는 중: 소리를 0에서 시작하고, 60초 안에 재생이 안 되면 실패 알림 */
    private fun onScheduledStartRequested() {
        val p = ScheduledStart.get(this) ?: return
        val exo = exoPlayer ?: return
        fadingIn = true
        exo.volume = 0f
        // 백그라운드에서는 오디오 포커스가 거부되므로, 재생이 시작될 때까지 포커스 요청 없이 진행
        exo.setAudioAttributes(audioAttrs, false)
        scheduleWatchJob?.cancel()
        scheduleWatchJob = scope.launch {
            delay(60_000)
            if (ScheduledStart.get(this@PlaybackService) != null) {
                ScheduledStart.clear(this@PlaybackService)
                fadingIn = false
                exo.setAudioAttributes(audioAttrs, true)
                applyVolume()
                notifyScheduleFailed(p.channelName)
                Log.w("KRadio", "켜짐 예약 실패: ${p.channelName}")
            }
        }
    }

    /** 재생이 시작된 뒤(포그라운드 서비스 상태) 폰 미디어 음량을 예약 값으로. 막히면 알림 */
    private fun applyScheduledVolume(name: String, pct: Int) {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * pct / 100.0).roundToInt().coerceIn(1, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
            .onFailure { Log.w("KRadio", "켜짐 예약 음량 설정 실패: ${it.message}") }
        val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.i("KRadio", "켜짐 예약 음량(재생 중): 요청 $level/$max → 실제 $actual/$max")
        if (actual == 0) notifyScheduleMuted(name)
    }

    private fun notifyScheduleMuted(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("schedule", "켜짐 예약", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "schedule")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("켜짐 예약: $name 재생 중")
            .setContentText("휴대폰 미디어 음량이 0이라 소리가 나지 않아요. 눌러서 음량을 올려주세요.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2002, n) }
    }
    /** 10초 동안 서서히 커지기 */
    private fun fadeIn() {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val exo = exoPlayer ?: return@launch
            fadingIn = true
            try {
                for (i in 1..20) {
                    exo.volume = baseVolume * i / 20f
                    delay(500)
                }
            } finally {
                exo.volume = baseVolume
                fadingIn = false
            }
        }
    }

    /** 예약의 자동 꺼짐을 꺼짐 예약으로 연결 */
    private suspend fun applyAutoOff(autoOff: Int) {
        val at = when {
            autoOff > 0 -> System.currentTimeMillis() + autoOff * 60_000L
            autoOff == AUTO_OFF_PROGRAM_END -> {
                val ch = allChannels.firstOrNull { it.id == exoPlayer?.currentMediaItem?.mediaId }
                val epg = runCatching {
                    EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true)
                }.getOrNull()
                epg?.upcoming(ch?.epg, System.currentTimeMillis() + 60_000L)?.firstOrNull()?.stop
            }
            else -> null
        }
        if (at != null) {
            SleepTimer.set(this, at)
            Log.i("KRadio", "켜짐 예약 자동 꺼짐: ${java.util.Date(at)}")
        }
    }

    private fun notifyScheduleFailed(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("schedule", "켜짐 예약", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "schedule")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("켜짐 예약: $name 연결 실패")
            .setContentText("네트워크를 확인하고 앱에서 다시 재생해 주세요.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2001, n) }
    }

    // ---------------- 방송 정보 표시 ----------------

    /** 큐의 모든 채널에 현재 프로그램 표시 + 재생 중 채널에만 로고 이미지 첨부. 재생은 그대로 */
    private suspend fun updateNowPlaying() {
        try {
            val exo = exoPlayer ?: return
            val count = exo.mediaItemCount
            if (count == 0) return

            val epg = EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true)
            val byId = allChannels.associateBy { it.id }
            var changed = false

            val items = (0 until count).map { i ->
                val item = exo.getMediaItemAt(i)
                val ch = byId[item.mediaId] ?: return@map item
                val program = epg.display(ch.epg)
                val artist = program?.let { programLabel(it) } ?: ch.group
                val subtitle = program?.let { programWithTime(it) } ?: ch.group
                // 항목에는 로고 주소만. 재생 중 채널의 그림 데이터는 nowPlayingMeta()가 내보낼 때 붙임
                val wantArt = logoUri(ch)

                val cur = item.mediaMetadata
                if (cur.title?.toString() == ch.name && cur.artist?.toString() == artist &&
                    cur.subtitle?.toString() == subtitle && cur.displayTitle?.toString() == ch.name &&
                    cur.artworkUri == wantArt && cur.artworkData == null) {
                    item
                } else {
                    changed = true
                    item.buildUpon()
                        .setMediaMetadata(
                            cur.buildUpon()
                                .setTitle(ch.name)
                                .setArtist(artist)
                                .setDisplayTitle(ch.name)
                                .setSubtitle(subtitle)
                                .setArtworkUri(wantArt)
                                .setArtworkData(null, null)
                                .build()
                        )
                        .build()
                }
            }

            if (!changed) return
            // 모든 항목의 주소가 그대로라 재생 중인 채널도 끊기지 않고 표시 정보만 바뀜
            exo.replaceMediaItems(0, count, items)
            updateWidget()
            Log.i("KRadio", "방송 정보 갱신: ${count}개 채널")
        } catch (e: Exception) {
            Log.w("KRadio", "방송 정보 갱신 실패: ${e.message}")
        }
    }

    // ---------------- 채널 → 재생 항목 ----------------

    /** 채널 설정을 새로 받음 (30분이 지났거나 force일 때만). 실패하면 기존 설정 유지 */
    private suspend fun refreshBase(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && baseChannels != null && now - baseLoadedAt < BASE_REFRESH_MS) return
        runCatching { ChannelRepository.load(this, quiet = true) }.onSuccess {
            baseChannels = it
            baseLoadedAt = now
        }
    }

    /** 재생 목록용 채널 (사용자 순서, 숨김 제외) */
    private suspend fun getChannels(): List<Channel> {
        if (baseChannels == null) {
            // 새로 뜬 서비스(예약 실행 등): 저장본으로 바로 시작하고 최신 설정은 뒤에서 받기
            ChannelRepository.loadCached(this)?.let { cached ->
                baseChannels = cached
                baseLoadedAt = System.currentTimeMillis()
                scope.launch {
                    refreshBase(force = true)
                    runCatching { getChannels() }
                }
            }
        }
        refreshBase()
        val base = baseChannels ?: error("채널 설정을 불러올 수 없음")
        val p = ChannelPrefs.read(this)
        val all = ChannelPrefs.applyOrder(base, p)
        allChannels = all
        prefetchRemoteLogos(all)
        return all.filter { it.id !in p.hidden }
    }

    // 인터넷 주소 로고(직접 추가·수정한 채널)는 미리 내려받아 content:// 로 제공 (오토·워치용)
    private val prefetchedLogos = mutableSetOf<String>()

    private fun prefetchRemoteLogos(list: List<Channel>) {
        val urls = list.mapNotNull { effectiveLogo(it) }
            .filter { it.startsWith("http") && it !in prefetchedLogos }
            .distinct()
        if (urls.isEmpty()) return
        prefetchedLogos += urls
        scope.launch(Dispatchers.IO) {
            var got = false
            urls.forEach { if (LogoProvider.prefetch(this@PlaybackService, it)) got = true }
            // 새로 받았으면 재생 중 채널 그림(워치용 이미지 데이터)도 바로 반영
            if (got) launch(Dispatchers.Main) { updateNowPlaying() }
        }
    }

    /** 재생 중인 채널은 그대로 두고 앞뒤 채널만 새 순서로 교체 (소리 끊김 없음) */
    private suspend fun refreshPlaylist() {
        val exo = exoPlayer ?: return
        if (exo.mediaItemCount == 0) return   // 재생 목록이 없으면 갱신할 것도 없음

        // 설정이 바뀌었으니 출처에서 새로 받고, 해석해둔 주소 캐시도 비움
        refreshBase(force = true)
        resolvedCache.clear()

        // 불러오기에 실패해도 앱이 죽지 않고 지금 재생은 그대로 유지
        val list = runCatching { getChannels() }.getOrElse {
            Log.w("KRadio", "재생 목록 갱신 실패: ${it.message}")
            return
        }
        val curIdx = exo.currentMediaItemIndex
        val curId = exo.currentMediaItem?.mediaId
        val newIdx = list.indexOfFirst { it.id == curId }
        // ... (이하 기존 그대로)

        if (curIdx + 1 < exo.mediaItemCount) exo.removeMediaItems(curIdx + 1, exo.mediaItemCount)
        if (curIdx > 0) exo.removeMediaItems(0, curIdx)

        if (newIdx >= 0) {
            exo.addMediaItems(0, list.subList(0, newIdx).map { placeholderItem(it) })
            exo.addMediaItems(list.subList(newIdx + 1, list.size).map { placeholderItem(it) })
        } else {
            // 듣던 채널을 숨긴 경우: 재생은 유지하고 나머지 채널을 뒤에 붙임
            exo.addMediaItems(list.map { placeholderItem(it) })
        }
        Log.i("KRadio", "재생 목록 갱신: ${list.size}개")
        updateNowPlaying()
    }

    /** 재생목록에 올릴 항목 (주소는 kradio:// 가짜 주소, 제목은 바로 표시) */
    private fun placeholderItem(ch: Channel): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(ch.id)
            .setUri("$SCHEME://channel/${ch.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.name)
                    .setArtist(ch.group)
                    // 오토·블루투스 차량 화면은 displayTitle이 있을 때 subtitle(방송 시간 포함)을 부제로 씀.
                    // 폰 알림·잠금화면·워치는 artist(프로그램명만)를 씀
                    .setDisplayTitle(ch.name)
                    .setSubtitle(ch.group)
                    // 내장 로고는 LogoProvider 주소(content://), 인터넷 로고는 그 주소 그대로
                    .setArtworkUri(logoUri(ch))
                    .build()
            )
        if (ch.type == "api" || ch.url?.contains(".m3u8") == true) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    /** 프로그램명 (부제가 있으면 "제목 · 부제") */
    private fun programLabel(p: Program): String =
        if (p.subTitle.isNullOrBlank()) p.title else "${p.title} · ${p.subTitle}"

    /** 프로그램명 + 방송 시간: "제목 · 부제 (05:00–06:00)" */
    private fun programWithTime(p: Program): String {
        val f = SimpleDateFormat("HH:mm", Locale.KOREA)
        return "${programLabel(p)} (${f.format(Date(p.start))}–${f.format(Date(p.stop))})"
    }

    /** 재생 항목에 방송 정보(프로그램명)를 채움 (로고는 주소만, 그림 데이터는 nowPlayingMeta에서) */
    private fun decoratedItem(ch: Channel, epg: EpgData?): MediaItem {
        val item = placeholderItem(ch)
        val program = epg?.display(ch.epg)
        return item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setArtist(program?.let { programLabel(it) } ?: ch.group)
                    .setSubtitle(program?.let { programWithTime(it) } ?: ch.group)
                    .build()
            )
            .build()
    }

    /** 재생 목록 전체를 방송 정보까지 채워서 생성 */
    private suspend fun playlistItems(list: List<Channel>, startIdx: Int): List<MediaItem> {
        val epg = runCatching {
            EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true, offline = true)
        }.getOrNull()
        return list.map { ch -> decoratedItem(ch, epg) }
    }

    /** 플레이어가 주소를 열기 직전에 호출됨 (백그라운드 스레드) */
    private fun resolveSpec(spec: DataSpec): DataSpec {
        if (spec.uri.scheme == SCHEME) {
            val id = spec.uri.lastPathSegment ?: throw IOException("채널 ID 없음")
            val ch = allChannels.firstOrNull { it.id == id }
                ?: throw IOException("알 수 없는 채널: $id")

            val now = System.currentTimeMillis()
            val cached = resolvedCache[id]?.takeIf { now - it.second < RESOLVE_CACHE_MS }?.first
            val url = cached ?: try {
                runBlocking { ChannelRepository.resolve(ch) }.also {
                    resolvedCache[id] = it to now
                    Log.i("KRadio", "해석: ${ch.name} → $it")
                }
            } catch (e: Exception) {
                throw IOException("[$id] 주소 해석 실패: ${e.message}", e)
            }

            val real = Uri.parse(url)
            real.host?.let { host ->
                if (ch.headers.isNotEmpty()) hostHeaders[host] = ch.headers
            }
            val resolved = spec.withUri(real)
            return if (ch.headers.isEmpty()) resolved else resolved.withAdditionalHeaders(ch.headers)
        }

        val h = spec.uri.host?.let { hostHeaders[it] }
        return if (h.isNullOrEmpty()) spec else spec.withAdditionalHeaders(h)
    }

    // ---------------- 세션 콜백 ----------------

    // ---------------- 안드로이드 오토 목록 (탭: 즐겨찾기 / 채널 목록(목록형) / 채널 목록(타일형)) ----------------

    /** 앱 리소스 아이콘 주소 (오토 탭 아이콘용) */
    private fun resUri(resId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(resId))
        .appendPath(resources.getResourceTypeName(resId))
        .appendPath(resources.getResourceEntryName(resId))
        .build()

    /**
     * 오토 탭(폴더). style = 안의 채널 모양 (MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_*),
     * icon = 탭에 표시할 단색 벡터 아이콘 (루트는 null)
     */
    private fun folderItem(id: String, title: String, style: Int, icon: Int?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .setArtworkUri(icon?.let { resUri(it) })
                    .setExtras(Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
                    })
                    .build()
            )
            .build()

    /** 오토 목록용 채널 항목. 부제는 지금 방송 중인 프로그램(없으면 방송사) */
    private fun browseItem(ch: Channel, epg: EpgData?): MediaItem =
        MediaItem.Builder()
            .setMediaId(ch.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.name)
                    .setArtist(epg?.display(ch.epg)?.let { programLabel(it) } ?: ch.group)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setArtworkUri(logoUri(ch))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    /** 오토 목록용 편성표 (저장본으로 빠르게) */
    private suspend fun browseEpg(): EpgData? = runCatching {
        EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true, offline = true)
    }.getOrNull()

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.i("KRadio", "오토 목록 요청(루트): ${browser.packageName}")
            return Futures.immediateFuture(
                LibraryResult.ofItem(folderItem(ROOT_ID, "케이라디오", MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, null), params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                try {
                    val items: List<MediaItem> = when (parentId) {
                        ROOT_ID -> listOf(
                            folderItem(FAV_ID, "즐겨찾기",
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, R.drawable.ic_tab_star),
                            folderItem(ALL_ID, "채널 목록",
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, R.drawable.ic_tab_list),
                            // 같은 채널을 작은 타일로 (스크롤 줄이기용)
                            folderItem(ALL_GRID_ID, "채널 타일",
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, R.drawable.ic_tab_grid)
                        )
                        FAV_ID -> {
                            // 위젯과 같은 기준: 채널 관리 순서, 숨긴 채널 제외, 최대 3개
                            val fav = ChannelPrefs.read(this@PlaybackService).favorites
                            val epg = browseEpg()
                            getChannels().filter { it.id in fav }
                                .take(ChannelPrefs.MAX_FAVORITES)
                                .map { browseItem(it, epg) }
                        }
                        ALL_ID, ALL_GRID_ID -> {
                            val epg = browseEpg()
                            getChannels().map { browseItem(it, epg) }
                        }
                        else -> emptyList()
                    }
                    Log.i("KRadio", "오토 목록: $parentId → ${items.size}개")
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    Log.e("KRadio", "오토 목록 실패: $parentId", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val ch = runCatching { getChannels() }.getOrNull()?.firstOrNull { it.id == mediaId }
                future.set(
                    if (ch != null) LibraryResult.ofItem(browseItem(ch, null), null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            return future
        }

        // 워치 플러그인 등 외부 컨트롤러에도 전체 조작 권한 부여
        // (Media3 최신 버전은 신뢰되지 않은 컨트롤러를 읽기 전용으로 제한함)
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.ConnectionResult> {
            Log.i("KRadio", "컨트롤러 연결: ${controller.packageName} (trusted=${controller.isTrusted}, ver=${controller.controllerVersion})")
            // 오토가 접속해옴 = 오토가 깨어 있음 → 이때 연결 상태 확인 (오토를 새로 깨우지 않음)
            if (controller.packageName == CarLink.GEARHEAD) scope.launch { checkCar() }
            // 앱 화면이 연결됨 = 사용자가 앱을 연 상태 → 보류했던 오디오 포커스 처리를 다시 켬
            val scheduled = controller.connectionHints.getBoolean("scheduled", false)
            if (focusDeferred && controller.packageName == packageName && !scheduled) {
                focusDeferred = false
                exoPlayer?.setAudioAttributes(audioAttrs, true)
                Log.i("KRadio", "오디오 포커스 처리 재개")
            }
            return Futures.immediateFuture(
                // 신뢰 여부와 상관없이 전체 명령 허용 (예전 AcceptedResultBuilder(session)과 같은 동작)
                MediaSession.ConnectionResult.AcceptedResultBuilder()
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .build()
            )
        }
        // 화면에서 채널 하나를 요청하면 → 전체 채널 목록 + 그 채널부터 재생
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    var list = getChannels()
                    val requested = mediaItems.getOrNull(
                        if (startIndex == C.INDEX_UNSET) 0 else startIndex
                    )?.mediaId
                    if (requested != null && list.none { it.id == requested }) {
                        // 화면엔 있는데 서비스가 모르는 채널 = 설정이 바뀐 것 → 새로 받기
                        refreshBase(force = true)
                        list = getChannels()
                    }
                    val idx = list.indexOfFirst { it.id == requested }.coerceAtLeast(0)
                    Log.i("KRadio", "재생목록 설정: ${list[idx].name}부터 (${list.size}개)")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            playlistItems(list, idx), idx, C.TIME_UNSET
                        )
                    )
                } catch (e: Exception) {
                    Log.e("KRadio", "재생목록 설정 실패", e)
                    future.setException(e)
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                try {
                    val list = getChannels()
                    val items = mediaItems.map { item ->
                        val ch = list.firstOrNull { it.id == item.mediaId }
                            ?: error("알 수 없는 채널: ${item.mediaId}")
                        placeholderItem(ch)
                    }.toMutableList()
                    future.set(items)
                } catch (e: Exception) {
                    Log.e("KRadio", "항목 추가 실패", e)
                    future.setException(e)
                }
            }
            return future
        }

        // 앱이 꺼진 상태에서 블루투스/이어폰 재생 버튼 → 마지막 채널부터 재생
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val list = getChannels()
                    val targetId = StartupSettings.resumeChannelId(this@PlaybackService, list)
                    val idx = list.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                    Log.i("KRadio", "이어 듣기: ${list[idx].name}")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            playlistItems(list, idx), idx, C.TIME_UNSET
                        )
                    )
                } catch (e: Exception) {
                    Log.e("KRadio", "이어 듣기 실패", e)
                    future.setException(e)
                }
            }
            return future
        }
    }
    // 위젯 정지 버튼: 서비스에 직접 와서 연결 대기 없이 바로 정지
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_STOP) {
            exoPlayer?.let {
                it.pause()
                it.stop()
            }
            // 정지 직후 위젯을 한 번 더 누른 게 '재생'으로 처리되지 않게
            prefs.edit().putLong("widget_pp_at", System.currentTimeMillis()).apply()
            Log.i("KRadio", "위젯: 정지")
            return START_NOT_STICKY
        }
        // 위젯 즐겨찾기 (재생 중일 때): 연결 대기 없이 바로 그 채널로
        if (intent?.action == ACTION_WIDGET_PLAY_CHANNEL) {
            val id = intent.getStringExtra(Shortcuts.EXTRA_CHANNEL)
            val exo = exoPlayer
            if (id != null && exo != null) {
                val idx = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == id }
                if (idx != null) {
                    exo.seekTo(idx, C.TIME_UNSET)   // 재생 목록 안에서 바로 이동
                    if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
                    exo.play()
                } else {
                    // 목록에 없으면 (설정이 바뀐 경우 등) 목록을 새로 만들어서 재생
                    scope.launch {
                        runCatching {
                            val list = getChannels()
                            val i = list.indexOfFirst { it.id == id }
                            if (i >= 0) {
                                exo.setMediaItems(playlistItems(list, i), i, C.TIME_UNSET)
                                exo.prepare()
                                exo.play()
                            }
                        }
                    }
                }
                Log.i("KRadio", "위젯: 즐겨찾기 $id")
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        CarLink.store(this, false)   // 재생 서비스가 없으면 오토 연결을 알 수 없으니 해제로 정리
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        loudness?.release()
        loudness = null
        contentResolver.unregisterContentObserver(volumeObserver)
        carReceiver?.let { runCatching { unregisterReceiver(it) } }
        carReceiver = null
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        super.onDestroy()
    }
}

/**
 * 라이브 HLS는 몇 초마다 재생목록을 새로 받으면서 타임라인이 바뀌는데(라이브 구간 위치만 바뀜),
 * 이걸 그대로 알리면 세션이 안드로이드 오토에 큐를 매번 다시 보내 재생 화면 배경이 깜빡인다.
 * 채널 구성(채널 ID·라이브 여부 등)이 그대로인 SOURCE_UPDATE는 알리지 않고 걸러낸다.
 * 채널 추가·순서 변경·방송 정보 갱신은 PLAYLIST_CHANGED라 그대로 전달된다.
 *
 * 주의: 코틀린의 `by` 위임은 자바 인터페이스의 default 메서드를 위임하지 않는다.
 * Player.Listener는 전부 default 메서드라서, 모든 콜백을 하나씩 직접 전달해야 한다.
 * (Media3 버전을 올려 Listener에 메서드가 추가되면 여기에도 추가할 것)
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class TimelineFilter(
    private val inner: Player.Listener,
    private val meta: (MediaMetadata) -> MediaMetadata,   // '지금 재생 중' 정보 변환 (재생 중 채널 로고 그림)
) : Player.Listener {

    private var lastKey: List<String>? = null
    private var suppressed = false
    private val window = Timeline.Window()

    private fun keyOf(t: Timeline): List<String> = (0 until t.windowCount).map { i ->
        t.getWindow(i, window)
        "${window.mediaItem.mediaId}|${window.isLive()}|${window.isDynamic}|${window.isPlaceholder}|${window.isSeekable}"
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val key = keyOf(timeline)
        suppressed = reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && key == lastKey
        if (suppressed) return
        lastKey = key
        inner.onTimelineChanged(timeline, reason)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (suppressed && events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            suppressed = false
            val flags = FlagSet.Builder()
            for (i in 0 until events.size()) {
                val e = events.get(i)
                if (e != Player.EVENT_TIMELINE_CHANGED) flags.add(e)
            }
            val rest = flags.build()
            if (rest.size() > 0) inner.onEvents(player, Player.Events(rest))
            return
        }
        inner.onEvents(player, events)
    }

    // ---- 나머지 콜백은 그대로 전달 ----
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = inner.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = inner.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = inner.onMediaMetadataChanged(meta(mediaMetadata))
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = inner.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = inner.onAvailableCommandsChanged(availableCommands)
    override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = inner.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = inner.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = inner.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = inner.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = inner.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = inner.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = inner.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = inner.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = inner.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = inner.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = inner.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = inner.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = inner.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = inner.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = inner.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
    override fun onCues(cues: MutableList<androidx.media3.common.text.Cue>) = inner.onCues(cues)
    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = inner.onCues(cueGroup)
    override fun onMetadata(metadata: androidx.media3.common.Metadata) = inner.onMetadata(metadata)

    // 리스너 제거 시 같은 원본을 감싼 필터끼리 같은 것으로 취급
    override fun equals(other: Any?): Boolean = other is TimelineFilter && other.inner == inner
    override fun hashCode(): Int = inner.hashCode()
}
