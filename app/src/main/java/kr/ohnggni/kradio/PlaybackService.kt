package kr.ohnggni.kradio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val SCHEME = "kradio"
private const val RESOLVE_CACHE_MS = 10 * 60 * 1000L // 해석한 주소 10분간 재사용

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var channels: List<Channel>? = null

    // 스트림 서버(호스트)별로 붙일 헤더 (Referer 등)
    private val hostHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // 채널 ID → (해석된 주소, 해석 시각)
    private val resolvedCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // 마지막으로 들은 채널 저장용
    private val prefs by lazy { getSharedPreferences("kradio", MODE_PRIVATE) }

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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_ALL // 마지막 채널 다음 → 첫 채널
        exoPlayer = exo

        // 채널이 바뀔 때마다 마지막 채널로 기록
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { prefs.edit().putString("last_channel", it).apply() }
            }
        })

        val player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                availableCommands.contains(command)

            // 라디오는 이전/다음 = 항상 채널 이동 (라이브 구간 처음으로 가는 동작 방지)
            override fun seekToNext() {
                Log.i("KRadio", "명령: seekToNext")
                exo.seekToNextMediaItem()
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            }

            override fun seekToPrevious() {
                Log.i("KRadio", "명령: seekToPrevious")
                exo.seekToPreviousMediaItem()
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            }

            override fun seekTo(positionMs: Long) { /* 라이브 방송이라 탐색 무시 */ }
            override fun isCurrentMediaItemLive(): Boolean = false // 워치 호환
            // [진단] 워치 큐에서 채널 누를 때 오는 명령
            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                Log.i("KRadio", "명령: seekToDefaultPosition($mediaItemIndex)")
                super.seekToDefaultPosition(mediaItemIndex)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                Log.i("KRadio", "명령: seekTo($mediaItemIndex, $positionMs)")
                super.seekTo(mediaItemIndex, positionMs)
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
    }

    private suspend fun getChannels(): List<Channel> =
        channels ?: ChannelRepository.load(this).also { channels = it }

    /** 재생목록에 올릴 항목 (주소는 kradio:// 가짜 주소, 제목은 바로 표시) */
    private fun placeholderItem(ch: Channel): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(ch.id)
            .setUri("$SCHEME://channel/${ch.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.name)
                    .setArtist(ch.group)
                    .build()
            )
        if (ch.type == "api" || ch.url?.contains(".m3u8") == true) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    /** 플레이어가 주소를 열기 직전에 호출됨 (백그라운드 스레드) */
    private fun resolveSpec(spec: DataSpec): DataSpec {
        if (spec.uri.scheme == SCHEME) {
            val id = spec.uri.lastPathSegment ?: throw IOException("채널 ID 없음")
            val ch = channels?.firstOrNull { it.id == id }
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

    private inner class SessionCallback : MediaSession.Callback {
        // 워치 플러그인 등 외부 컨트롤러에도 전체 조작 권한 부여
        // (Media3 최신 버전은 신뢰되지 않은 컨트롤러를 읽기 전용으로 제한함)
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.ConnectionResult> {
            Log.i("KRadio", "컨트롤러 연결: ${controller.packageName} (trusted=${controller.isTrusted})")
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
                    val list = getChannels()
                    val requested = mediaItems.getOrNull(
                        if (startIndex == C.INDEX_UNSET) 0 else startIndex
                    )?.mediaId
                    val idx = list.indexOfFirst { it.id == requested }.coerceAtLeast(0)
                    Log.i("KRadio", "재생목록 설정: ${list[idx].name}부터 (${list.size}개)")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            list.map { placeholderItem(it) }, idx, C.TIME_UNSET
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
                    val lastId = prefs.getString("last_channel", null)
                    val idx = list.indexOfFirst { it.id == lastId }.coerceAtLeast(0)
                    Log.i("KRadio", "이어 듣기: ${list[idx].name}")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            list.map { placeholderItem(it) }, idx, C.TIME_UNSET
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