package kr.ohnggni.kradio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.ohnggni.kradio.ui.theme.KRadioTheme

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var channels by mutableStateOf<List<Channel>>(emptyList())
    private var epg by mutableStateOf<EpgData?>(null)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var currentId by mutableStateOf<String?>(null)
    private var isOn by mutableStateOf(false)
    private var status by mutableStateOf("채널 목록 불러오는 중...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            try {
                channels = ChannelRepository.load(this@MainActivity)
                status = ""
            } catch (e: Exception) {
                status = "채널 로드 실패: ${e.message}"
                return@launch
            }
            // 화면이 보이는 동안 매 분 정각마다 편성 정보 갱신
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    epg = EpgRepository.load(this@MainActivity, ChannelRepository.epgUrl)
                    now = System.currentTimeMillis()
                    delay(60_000L - now % 60_000L)
                }
            }
        }

        setContent {
            KRadioTheme {
                MainScreen(
                    channels = channels,
                    epg = epg,
                    now = now,
                    currentId = currentId,
                    isOn = isOn,
                    status = status,
                    enabled = controller != null,
                    onChannelClick = { playChannel(it) },
                    onPlayStop = { playStop() },
                    onPrev = { controller?.seekToPrevious() },
                    onNext = { controller?.seekToNext() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            controller = c
            isOn = c.playWhenReady
            currentId = c.currentMediaItem?.mediaId
            c.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    isOn = playWhenReady
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentId = mediaItem?.mediaId
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) status = ""
                }
                override fun onPlayerError(error: PlaybackException) {
                    status = "연결 끊김 · 재연결 중..."
                }
            })
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun playChannel(ch: Channel) {
        val c = controller ?: return
        status = "${ch.name} 연결 중..."
        currentId = ch.id
        // 채널 ID만 넘기면 서비스가 전체 목록 구성 + 주소 해석 처리
        c.setMediaItem(MediaItem.Builder().setMediaId(ch.id).build())
        c.prepare()
        c.play()
    }

    /** 재생 중이면 정지, 멈춰 있으면 재생 (처음이면 마지막 채널부터) */
    private fun playStop() {
        val c = controller ?: return
        when {
            c.playWhenReady -> {
                c.pause()
                c.stop()
            }
            c.mediaItemCount > 0 -> {
                c.prepare()
                c.play()
            }
            else -> {
                val lastId = getSharedPreferences("kradio", MODE_PRIVATE).getString("last_channel", null)
                (channels.firstOrNull { it.id == lastId } ?: channels.firstOrNull())
                    ?.let { playChannel(it) }
            }
        }
    }
}