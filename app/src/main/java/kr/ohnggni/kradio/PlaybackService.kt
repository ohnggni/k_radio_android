package kr.ohnggni.kradio

import android.app.PendingIntent
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioManager

private const val SCHEME = "kradio"
private const val RESOLVE_CACHE_MS = 10 * 60 * 1000L // 해석한 주소 10분간 재사용
private const val MAX_RETRY_DELAY_MS = 30_000L       // 재연결 최대 대기 간격

private const val BASE_REFRESH_MS = 30 * 60 * 1000L    // 채널 설정 30분마다 새로 확인

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
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
        if (key == AppVolume.KEY) applyVolume()
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

    private var pausedAt = 0L   // 멈춘 시각 (재개할 때 라이브 지점으로 갈지 판단)

    // 오디오 속성 (예약 재생 때 포커스 처리를 잠시 껐다 켜기 위해 보관)
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // 내장 로고 이미지 데이터 (워치 등 외부 기기는 앱 내부 파일을 못 읽어서 데이터로 전달)
    private val logoBytesCache = ConcurrentHashMap<String, ByteArray>()

    private fun logoBytes(ch: Channel): ByteArray? {
        val logo = ch.logo ?: return null
        if (!logo.startsWith(LogoCache.ASSET_PREFIX)) return null
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
                        applyScheduledVolume(p.channelName, p.volume)
                        fadeIn()
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
        }

        // 알림/외부 기기에서 세션을 누르면 앱 화면 열기
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

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
            val wait = at - SleepTimer.FADE_MS - System.currentTimeMillis()
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
            val currentIdx = exo.currentMediaItemIndex

            val epg = EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true)
            val byId = allChannels.associateBy { it.id }
            var changed = false

            val items = (0 until count).map { i ->
                val item = exo.getMediaItemAt(i)
                val ch = byId[item.mediaId] ?: return@map item
                val program = epg.display(ch.epg)
                val artist = program?.let {
                    if (it.subTitle.isNullOrBlank()) it.title else "${it.title} · ${it.subTitle}"
                } ?: ch.group
                val wantArt = if (i == currentIdx) logoBytes(ch) else null

                val cur = item.mediaMetadata
                val artSame = (cur.artworkData == null && wantArt == null) ||
                        (cur.artworkData != null && wantArt != null && cur.artworkData.contentEquals(wantArt))
                if (cur.title?.toString() == ch.name && cur.artist?.toString() == artist && artSame) {
                    item
                } else {
                    changed = true
                    item.buildUpon()
                        .setMediaMetadata(
                            cur.buildUpon()
                                .setTitle(ch.name)
                                .setArtist(artist)
                                .setArtworkData(wantArt, if (wantArt != null) MediaMetadata.PICTURE_TYPE_FRONT_COVER else null)
                                .build()
                        )
                        .build()
                }
            }

            if (!changed) return
            // 모든 항목의 주소가 그대로라 재생 중인 채널도 끊기지 않고 표시 정보만 바뀜
            exo.replaceMediaItems(0, count, items)
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
        return all.filter { it.id !in p.hidden }
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
                    .setArtworkUri(ch.logo?.let { Uri.parse(it) }) // ← 추가
                    .build()
            )
        if (ch.type == "api" || ch.url?.contains(".m3u8") == true) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    /** 재생 항목에 방송 정보(프로그램명)와 로고(재생 시작 채널만)를 채움 */
    private fun decoratedItem(ch: Channel, epg: EpgData?, withArt: Boolean): MediaItem {
        val item = placeholderItem(ch)
        val program = epg?.display(ch.epg)
        val artist = program?.let {
            if (it.subTitle.isNullOrBlank()) it.title else "${it.title} · ${it.subTitle}"
        } ?: ch.group
        val art = if (withArt) logoBytes(ch) else null
        return item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setArtist(artist)
                    .setArtworkData(art, if (art != null) MediaMetadata.PICTURE_TYPE_FRONT_COVER else null)
                    .build()
            )
            .build()
    }

    /** 재생 목록 전체를 방송 정보까지 채워서 생성 */
    private suspend fun playlistItems(list: List<Channel>, startIdx: Int): List<MediaItem> {
        val epg = runCatching {
            EpgRepository.load(this, SourceSettings.epgUrl(this), quiet = true, offline = true)
        }.getOrNull()
        return list.mapIndexed { i, ch -> decoratedItem(ch, epg, i == startIdx) }
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

    private inner class SessionCallback : MediaSession.Callback {

        // 워치 플러그인 등 외부 컨트롤러에도 전체 조작 권한 부여
        // (Media3 최신 버전은 신뢰되지 않은 컨트롤러를 읽기 전용으로 제한함)
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.ConnectionResult> {
            Log.i("KRadio", "컨트롤러 연결: ${controller.packageName} (trusted=${controller.isTrusted})")
            // 앱 화면이 연결됨 = 사용자가 앱을 연 상태 → 보류했던 오디오 포커스 처리를 다시 켬
            val scheduled = controller.connectionHints.getBoolean("scheduled", false)
            if (focusDeferred && controller.packageName == packageName && !scheduled) {
                focusDeferred = false
                exoPlayer?.setAudioAttributes(audioAttrs, true)
                Log.i("KRadio", "오디오 포커스 처리 재개")
            }
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
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
            controller: MediaSession.ControllerInfo
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        loudness?.release()
        loudness = null
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