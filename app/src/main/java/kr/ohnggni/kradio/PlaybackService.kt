package kr.ohnggni.kradio

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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var channels: List<Channel>? = null

    // 스트림 서버(호스트)별로 붙일 헤더 (Referer 등)
    private val hostHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // 채널 전환 연타 처리용
    private var switchJob: Job? = null
    private var pendingId: String? = null

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        // 요청마다 호스트를 보고 방송사 헤더를 자동으로 붙임
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
            val h = spec.uri.host?.let { hostHeaders[it] }
            if (h.isNullOrEmpty()) spec else spec.withAdditionalHeaders(h)
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
        exoPlayer = exo

        // 이전/다음 명령을 가로채서 채널 전환으로 바꾸는 래퍼
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

            override fun seekToNext() = switchChannel(+1)
            override fun seekToNextMediaItem() = switchChannel(+1)
            override fun seekToPrevious() = switchChannel(-1)
            override fun seekToPreviousMediaItem() = switchChannel(-1)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    private suspend fun getChannels(): List<Channel> =
        channels ?: ChannelRepository.load(this).also { channels = it }

    /** 현재 채널 기준으로 delta(+1/-1)만큼 이동 */
    private fun switchChannel(delta: Int) {
        val baseId = pendingId ?: exoPlayer?.currentMediaItem?.mediaId
        switchJob?.cancel()
        switchJob = scope.launch {
            try {
                val list = getChannels()
                if (list.isEmpty()) return@launch
                val idx = list.indexOfFirst { it.id == baseId }
                val target = list[if (idx < 0) 0 else (idx + delta + list.size) % list.size]
                pendingId = target.id
                val item = buildPlayableItem(target)
                exoPlayer?.run {
                    setMediaItem(item)
                    prepare()
                    play()
                }
                Log.i("KRadio", "채널 전환: ${target.name}")
            } catch (e: Exception) {
                Log.e("KRadio", "채널 전환 실패", e)
            } finally {
                pendingId = null
            }
        }
    }

    /** 채널 → 실제 재생 가능한 MediaItem */
    private suspend fun buildPlayableItem(ch: Channel): MediaItem {
        val url = ChannelRepository.resolve(ch)
        Uri.parse(url).host?.let { host ->
            if (ch.headers.isNotEmpty()) hostHeaders[host] = ch.headers
        }
        val builder = MediaItem.Builder()
            .setMediaId(ch.id)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ch.name)
                    .setArtist(ch.group)
                    .build()
            )
        if (url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private inner class SessionCallback : MediaSession.Callback {
        // 화면에서 채널 ID만 넘기면 여기서 주소 해석까지 처리
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                try {
                    val list = getChannels()
                    val resolved = mediaItems.map { item ->
                        val ch = list.firstOrNull { it.id == item.mediaId }
                            ?: error("알 수 없는 채널: ${item.mediaId}")
                        buildPlayableItem(ch)
                    }.toMutableList()
                    Log.i("KRadio", "해석 성공: ${resolved.map { it.localConfiguration?.uri }}")
                    future.set(resolved)
                } catch (e: Exception) {
                    Log.e("KRadio", "해석 실패: ${mediaItems.map { it.mediaId }}", e)
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