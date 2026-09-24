package kr.ohnggni.kradio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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

    private var base by mutableStateOf<List<Channel>>(emptyList())   // GitHub 기본 채널
    private var prefs by mutableStateOf(ChannelPrefsData())          // 사용자 설정
    private val allChannels: List<Channel> get() = ChannelPrefs.applyOrder(base, prefs)
    private val channels: List<Channel> get() = ChannelPrefs.visible(base, prefs)

    private var epg by mutableStateOf<EpgData?>(null)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var currentId by mutableStateOf<String?>(null)
    private var isOn by mutableStateOf(false)
    private var status by mutableStateOf("채널 목록 불러오는 중...")
    private var showManage by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            try {
                base = ChannelRepository.load(this@MainActivity)
                prefs = ChannelPrefs.read(this@MainActivity)
                status = ""
            } catch (e: Exception) {
                status = "채널 로드 실패: ${e.message}"
                return@launch
            }
            // 화면이 보이는 동안 매 분 정각마다 편성 정보 갱신
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    epg = EpgRepository.load(this@MainActivity, SourceSettings.epgUrl(this@MainActivity))
                    now = System.currentTimeMillis()
                    delay(60_000L - now % 60_000L)
                }
            }
        }

        setContent {
            KRadioTheme {
                BackHandler(enabled = showManage) { showManage = false }

                if (showManage) {
                    ChannelManageScreen(
                        all = allChannels,
                        defaults = base.associateBy { it.id },
                        hidden = prefs.hidden,
                        overrides = prefs.overrides,
                        onBack = { showManage = false },
                        onReorder = { ids -> updatePrefs(prefs.copy(order = ids)) },
                        onToggleVisible = { id, visible ->
                            updatePrefs(
                                prefs.copy(hidden = if (visible) prefs.hidden - id else prefs.hidden + id)
                            )
                        },
                        onAdd = { name, url, logo -> addCustom(name, url, logo.ifBlank { null }) },
                        onEdit = { id, name, url, logo -> editChannel(id, name, url, logo) },
                        onResetChannel = { id -> updatePrefs(prefs.copy(overrides = prefs.overrides - id)) },
                        onDelete = { id -> deleteCustom(id) },
                        onResetAll = { resetAll() },
                    )
                } else {
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
                        onOpenManage = { showManage = true },
                    )
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

    // ---------------- 채널 설정 ----------------

    /** 저장하면 서비스가 감지해서 재생 목록에 바로 반영 */
    private fun updatePrefs(newPrefs: ChannelPrefsData) {
        prefs = newPrefs
        ChannelPrefs.write(this, newPrefs)
    }

    private fun addCustom(name: String, url: String, logo: String?) {
        val ch = Channel(
            id = ChannelPrefs.newCustomId(),
            name = name,
            group = ChannelPrefs.CUSTOM_GROUP,
            type = "direct",
            url = url,
            logo = logo,
        )
        updatePrefs(
            prefs.copy(
                custom = prefs.custom + ch,
                order = allChannels.map { it.id } + ch.id
            )
        )
    }

    private fun deleteCustom(id: String) {
        updatePrefs(
            prefs.copy(
                custom = prefs.custom.filterNot { it.id == id },
                order = prefs.order - id,
                hidden = prefs.hidden - id
            )
        )
    }

    /** 내 채널은 직접 수정, 기본 채널은 바뀐 항목만 따로 저장 */
    private fun editChannel(id: String, name: String, url: String, logo: String) {
        val custom = prefs.custom.firstOrNull { it.id == id }
        if (custom != null) {
            val updated = custom.copy(name = name, url = url, logo = logo.ifBlank { null })
            updatePrefs(prefs.copy(custom = prefs.custom.map { if (it.id == id) updated else it }))
            return
        }
        val def = base.firstOrNull { it.id == id } ?: return
        val o = ChannelOverride(
            name = name.takeIf { it.isNotBlank() && it != def.name },
            url = url.takeIf { it.isNotBlank() && it != def.url },
            logo = logo.takeIf { it.isNotBlank() && it != def.logo },
        )
        updatePrefs(
            prefs.copy(overrides = if (o.isEmpty()) prefs.overrides - id else prefs.overrides + (id to o))
        )
    }

    /** 순서·숨김·수정 초기화 + GitHub 설정 다시 받기 (내 채널은 유지) */
    private fun resetAll() {
        lifecycleScope.launch {
            runCatching { ChannelRepository.load(this@MainActivity) }.onSuccess { base = it }
            updatePrefs(prefs.copy(order = emptyList(), hidden = emptySet(), overrides = emptyMap()))
        }
    }

    // ---------------- 재생 ----------------

    private fun playChannel(ch: Channel) {
        val c = controller ?: return
        status = "${ch.name} 연결 중..."
        currentId = ch.id
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
                val lastId = getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
                    .getString("last_channel", null)
                (channels.firstOrNull { it.id == lastId } ?: channels.firstOrNull())
                    ?.let { playChannel(it) }
            }
        }
    }
}