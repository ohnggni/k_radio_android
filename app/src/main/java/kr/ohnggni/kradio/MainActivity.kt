package kr.ohnggni.kradio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import kr.ohnggni.kradio.ui.theme.KRadioTheme

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var channels by mutableStateOf<List<Channel>>(emptyList())
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
            }
        }

        setContent {
            KRadioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.fillMaxSize().padding(innerPadding)) {

                        // 상단: 현재 채널 + 정지 버튼
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    channels.firstOrNull { it.id == currentId }?.name
                                        ?: "재생 중인 채널 없음",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                if (status.isNotEmpty()) {
                                    Text(
                                        status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Button(
                                enabled = controller != null && isOn,
                                onClick = { stopPlayback() }
                            ) { Text("정지") }
                        }
                        HorizontalDivider()

                        // 채널 목록
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(channels, key = { it.id }) { ch ->
                                val selected = ch.id == currentId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = controller != null) { playChannel(ch) }
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        ch.name,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        ch.group,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
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
                    status = "재생 오류: ${error.errorCodeName}"
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
        // 채널 ID만 넘기면 서비스가 주소 해석 + 헤더 처리
        c.setMediaItem(MediaItem.Builder().setMediaId(ch.id).build())
        c.prepare()
        c.play()
    }

    private fun stopPlayback() {
        val c = controller ?: return
        c.pause()
        c.stop()
    }
}