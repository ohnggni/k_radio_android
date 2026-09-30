package kr.ohnggni.kradio

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
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
import android.content.SharedPreferences
import android.widget.Toast
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableIntStateOf
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

private enum class Screen { MAIN, SETTINGS, MANAGE, GUIDE, SCHEDULE }
private const val CONFIG_RECHECK_MS = 15 * 60_000L  // 앱이 앞으로 나올 때 이만큼 지났으면 설정 다시 확인
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private var base by mutableStateOf<List<Channel>>(emptyList())   // 기본 채널 (출처에서 받은 것)
    private var prefs by mutableStateOf(ChannelPrefsData())          // 사용자 채널 설정
    private val allChannels: List<Channel> get() = ChannelPrefs.applyOrder(base, prefs)
    private val channels: List<Channel> get() = ChannelPrefs.visible(base, prefs)

    private var epg by mutableStateOf<EpgData?>(null)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var currentId by mutableStateOf<String?>(null)
    private var isOn by mutableStateOf(false)
    private var status by mutableStateOf("채널 목록 불러오는 중...")

    private var screen by mutableStateOf(Screen.MAIN)
    private var warning by mutableStateOf<String?>(null)
    private var reloading by mutableStateOf(false)
    private var customConfig by mutableStateOf<String?>(null)
    private var customEpg by mutableStateOf<String?>(null)

    private var appVersionName = ""
    private var appVersionCode = 0L
    private var newVersion by mutableStateOf<String?>(null)    // 설치된 것보다 새 버전 이름
    private var updateNotice by mutableStateOf<String?>(null)  // 메인 화면 안내 카드

    private var startupMode by mutableStateOf(StartupSettings.MODE_NONE)
    private var startupChannel by mutableStateOf<String?>(null)
    private var pendingAutoStart = false   // 앱이 앞으로 나올 때 true
    private var recreated = false          // 폴드·회전으로 화면만 다시 만들어진 경우
    private var sleepAt by mutableStateOf<Long?>(null)
    private var showSleep by mutableStateOf(false)

    private var appVolume by mutableIntStateOf(100)

    private var volumeSync by mutableStateOf(false)

    private var configLoadedAt = 0L   // 채널 설정을 마지막으로 받은 시각

    private var schedules by mutableStateOf<List<PlaySchedule>>(emptyList())

    // 켜짐 예약 실패 알림용 권한 (안드로이드 13 이상)
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var sysVol by mutableIntStateOf(0)
    private var sysMax by mutableIntStateOf(15)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // 폰 볼륨 버튼 등으로 시스템 음량이 바뀌면 슬라이더도 따라 움직이게
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = readSystemVolume()
    }
    private val sleepPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SleepTimer.KEY) sleepAt = SleepTimer.get(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        packageManager.getPackageInfo(packageName, 0).let {
            appVersionName = it.versionName.orEmpty()
            appVersionCode = PackageInfoCompat.getLongVersionCode(it)
        }

        // 폴드 접기/펴기 등으로 화면만 다시 만들어질 때는 자동 재생하지 않음
        recreated = savedInstanceState != null
        startupMode = StartupSettings.mode(this)
        startupChannel = StartupSettings.fixedChannel(this)
        appVolume = AppVolume.get(this)
        volumeSync = AppVolume.isSync(this)
        schedules = PlaySchedules.read(this)
        ScheduleAlarms.rescheduleAll(this)

        customConfig = SourceSettings.customConfigUrl(this)
        customEpg = SourceSettings.customEpgUrl(this)

        lifecycleScope.launch {
            prefs = ChannelPrefs.read(this@MainActivity)
            try {
                base = ChannelRepository.load(this@MainActivity)
                status = ""
            } catch (e: Exception) {
                status = "채널 정보를 불러올 수 없어요"
            }
            configLoadedAt = System.currentTimeMillis()
            updateWarning()
            updateUpdateInfo()
            tryAutoStart()

            // 화면이 보이는 동안 매 분 정각마다 편성 정보 갱신
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching {
                        EpgRepository.load(this@MainActivity, SourceSettings.epgUrl(this@MainActivity))
                    }.getOrNull()?.let { epg = it }
                    now = System.currentTimeMillis()
                    sleepAt = SleepTimer.get(this@MainActivity)
                    updateWarning()
                    delay(60_000L - now % 60_000L)
                }
            }
        }

        setContent {
            KRadioTheme {
                BackHandler(enabled = screen != Screen.MAIN) {
                    screen = if (screen == Screen.SETTINGS) Screen.MAIN else Screen.SETTINGS
                }

                when (screen) {
                    Screen.MAIN -> {
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
                            warning = warning,
                            updateNotice = updateNotice,
                            onUpdate = { openUpdate() },
                            onDismissUpdate = { dismissUpdate() },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            sleepAt = sleepAt,
                            onOpenSleep = { showSleep = true },
                            appVolume = appVolume,
                            volumeSync = volumeSync,
                            sysVol = sysVol,
                            sysMax = sysMax,
                            onAppVolume = { changeAppVolume(it) },
                            onSysVolume = { setSysVolume(it) },
                            onToggleSync = { on ->
                                AppVolume.setSync(this, on)
                                volumeSync = on
                                // 시스템 모드로 바꾸면 안 보이는 앱 증폭이 남지 않게 100%로
                                if (on) {
                                    AppVolume.set(this, 100)
                                    appVolume = 100
                                }
                                readSystemVolume()
                            },
                            onToggleMute = { toggleMute() },
                        )
                        if (showSleep) {
                            SleepTimerDialog(
                                sleepAt = sleepAt,
                                programs = channels.firstOrNull { it.id == currentId }
                                    ?.let { ch -> epg?.upcoming(ch.epg, System.currentTimeMillis() + 60_000L) }
                                    ?: emptyList(),
                                onSetAt = { setSleep(it) },
                                onCancelTimer = { setSleep(null) },
                                onDismiss = { showSleep = false },
                            )
                        }
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        customConfig = customConfig,
                        customEpg = customEpg,
                        configError = ChannelRepository.lastError,
                        epgError = EpgRepository.lastError,
                        reloading = reloading,
                        onBack = { screen = Screen.MAIN },
                        onOpenManage = { screen = Screen.MANAGE },
                        onOpenGuide = { screen = Screen.GUIDE },
                        onSaveConfig = { url ->
                            SourceSettings.setCustomConfigUrl(this, url)
                            customConfig = SourceSettings.customConfigUrl(this)
                            reloadSources()
                        },
                        onSaveEpg = { url ->
                            SourceSettings.setCustomEpgUrl(this, url)
                            customEpg = SourceSettings.customEpgUrl(this)
                            reloadSources()
                        },
                        onReload = { reloadSources() },
                        appVersion = appVersionName,
                        newVersion = newVersion,
                        onOpenUpdate = { openUpdate() },
                        channels = channels,
                        startupMode = startupMode,
                        startupChannel = startupChannel,
                        onSaveStartup = { mode, id ->
                            StartupSettings.save(this, mode, id)
                            startupMode = mode
                            startupChannel = id
                        },
                        scheduleSummary = schedules.count { it.enabled }
                            .let { if (it == 0) "없음" else "${it}개 켜짐" },
                        onOpenSchedules = { screen = Screen.SCHEDULE },
                    )

                    Screen.MANAGE -> ChannelManageScreen(
                        all = allChannels,
                        defaults = base.associateBy { it.id },
                        hidden = prefs.hidden,
                        overrides = prefs.overrides,
                        onBack = { screen = Screen.SETTINGS },
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
                    Screen.GUIDE -> GuideScreen(onBack = { screen = Screen.SETTINGS })
                    Screen.SCHEDULE -> ScheduleScreen(
                        schedules = schedules,
                        channels = channels,
                        epg = epg,
                        onBack = { screen = Screen.SETTINGS },
                        onSave = { saveSchedule(it) },
                        onDelete = { deleteSchedule(it) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 폴드·회전으로 다시 만들어진 경우만 빼고, 앱이 앞으로 나올 때마다 자동 재생 여부 판단
        pendingAutoStart = !recreated
        recreated = false
        sleepAt = SleepTimer.get(this)
        schedules = PlaySchedules.read(this)
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sleepPrefListener)
        readSystemVolume()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        // 설정을 받은 지 오래됐으면 다시 확인 (새 버전 알림, 방송 주소 변경 반영)
        if (configLoadedAt > 0 && System.currentTimeMillis() - configLoadedAt > CONFIG_RECHECK_MS) {
            lifecycleScope.launch {
                runCatching { ChannelRepository.load(this@MainActivity) }.onSuccess { base = it }
                configLoadedAt = System.currentTimeMillis()
                updateWarning()
                updateUpdateInfo()
            }
        }
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
                    sleepAt = SleepTimer.get(this@MainActivity)
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
            tryAutoStart()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(sleepPrefListener)
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onStop()
    }

    // ---------------- 데이터 출처 ----------------

    private fun reloadSources() {
        lifecycleScope.launch {
            reloading = true
            runCatching { ChannelRepository.load(this@MainActivity) }
                .onSuccess {
                    base = it
                    if (status.startsWith("채널 정보")) status = ""
                }
            // 예상 못 한 오류가 나도 앱이 종료되지 않고 기존 편성표 유지
            runCatching {
                EpgRepository.load(this@MainActivity, SourceSettings.epgUrl(this@MainActivity), force = true)
            }.getOrNull()?.let { epg = it }
            now = System.currentTimeMillis()
            updateWarning()
            updateUpdateInfo()
            reloading = false
        }
    }

    /** 원격 접속 실패 시 메인 화면에 띄울 안내 문구 */
    private fun updateWarning() {
        val cfgErr = ChannelRepository.lastError != null
        val epgErr = EpgRepository.lastError != null
        if (!cfgErr && !epgErr) {
            warning = null
            return
        }
        val what = listOfNotNull(
            if (cfgErr) "채널 정보" else null,
            if (epgErr) "편성표" else null
        ).joinToString("·")
        val failedDefault =
            (cfgErr && customConfig == null) || (epgErr && customEpg == null && customConfig == null)
        warning = if (failedDefault) {
            "$what 기본 서버에 연결할 수 없어요. 저장된 정보로 표시 중이에요. 계속 안 되면 설정에서 다른 데이터 주소를 지정해 보세요."
        } else {
            "$what 서버(직접 지정한 주소)에 연결할 수 없어요. 설정에서 주소를 확인해 주세요."
        }
    }
    // ---------------- 업데이트 ----------------

    /** 설정 파일의 최신 버전과 비교해서 안내 여부 결정 */
    private fun updateUpdateInfo() {
        val latest = ChannelRepository.latestVersionCode
        newVersion = if (latest != null && latest > appVersionCode) {
            ChannelRepository.latestVersionName ?: "새 버전"
        } else null
        val dismissed = getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
            .getLong("dismissed_update", 0L)
        updateNotice = if (newVersion != null && latest != dismissed) {
            "새 버전 ${newVersion}이 나왔어요"
        } else null
    }

    private fun openUpdate() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SourceSettings.updateUrl())))
        }
    }

    /** '나중에': 이 버전에 대해서는 다시 안내하지 않음 (더 새 버전이 나오면 다시 표시) */
    private fun dismissUpdate() {
        ChannelRepository.latestVersionCode?.let {
            getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
                .edit().putLong("dismissed_update", it).apply()
        }
        updateNotice = null
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

    /** 순서·숨김·수정 초기화 + 출처에서 다시 받기 (내 채널은 유지) */
    private fun resetAll() {
        lifecycleScope.launch {
            runCatching { ChannelRepository.load(this@MainActivity) }.onSuccess { base = it }
            updatePrefs(prefs.copy(order = emptyList(), hidden = emptySet(), overrides = emptyMap()))
        }
    }

    // ---------------- 재생 ----------------
    private fun readSystemVolume() {
        sysMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    private fun changeAppVolume(v: Int) {
        AppVolume.set(this, v)
        appVolume = v
        // 처음으로 100%를 넘기면 증폭 한계를 한 번만 안내
        val sp = getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
        if (v > 100 && !sp.getBoolean("boost_tip_shown", false)) {
            sp.edit().putBoolean("boost_tip_shown", true).apply()
            Toast.makeText(
                this,
                "100%를 넘으면 증폭돼요. 원래 소리가 큰 방송은 조금만 커져요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setSysVolume(v: Int) {
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        sysVol = v
    }

    /** 스피커 아이콘: 음소거 ↔ 직전 음량으로 복원 */
    private fun toggleMute() {
        val sp = getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE)
        if (volumeSync) {
            if (sysVol > 0) {
                sp.edit().putInt("sys_before_mute", sysVol).apply()
                setSysVolume(0)
            } else {
                val restore = sp.getInt("sys_before_mute", 0).takeIf { it > 0 }
                    ?: (sysMax / 2).coerceAtLeast(1)
                setSysVolume(restore)
            }
        } else {
            if (appVolume > 0) {
                sp.edit().putInt("app_before_mute", appVolume).apply()
                changeAppVolume(0)
            } else {
                changeAppVolume(sp.getInt("app_before_mute", 100).takeIf { it > 0 } ?: 100)
            }
        }
    }
    // ---------------- 켜짐 예약 ----------------

    private fun saveSchedule(s: PlaySchedule) {
        val isNew = schedules.none { it.id == s.id }
        schedules = schedules.filterNot { it.id == s.id } + s
        PlaySchedules.write(this, schedules)
        ScheduleAlarms.rescheduleAll(this)   // 시스템에 정시 실행 등록
        // 실패 알림용 권한은 새 예약을 추가할 때만 물어봄 (스위치·수정·되돌리기 때는 안 물음)
        if (isNew && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun deleteSchedule(id: String) {
        ScheduleAlarms.cancel(this, id)
        schedules = schedules.filterNot { it.id == id }
        PlaySchedules.write(this, schedules)
    }
    /** 꺼짐 예약 설정/해제. 이미 지났거나 1분 안에 오는 시각은 받지 않음 */
    private fun setSleep(at: Long?) {
        if (at != null && at < System.currentTimeMillis() + 60_000L) {
            Toast.makeText(this, "이미 지났거나 곧 끝나는 시각이에요", Toast.LENGTH_SHORT).show()
            return   // 예약 창은 열어둔 채로 다시 고를 수 있게
        }
        SleepTimer.set(this, at)
        sleepAt = at
        showSleep = false
    }
    /** 앱이 앞으로 나왔을 때 설정에 따라 자동 재생 */
    private fun tryAutoStart() {
        if (!pendingAutoStart) return
        val c = controller ?: return
        if (channels.isEmpty()) return
        pendingAutoStart = false
        if (c.playWhenReady) return   // 재생 중이면 그대로

        // 재생 서비스가 새로 시작됐거나(목록 없음) 정지한 지 오래됐을 때만 '새로 켠 것'으로 봄
        val stoppedAt = getSharedPreferences(ChannelPrefs.PREFS, MODE_PRIVATE).getLong("stopped_at", 0L)
        val fresh = c.mediaItemCount == 0
        val longIdle = System.currentTimeMillis() - stoppedAt > StartupSettings.AUTO_START_IDLE_MS
        if (!fresh && !longIdle) return

        val id = StartupSettings.launchChannelId(this, channels) ?: return
        channels.firstOrNull { it.id == id }?.let { playChannel(it) }
    }

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
                val targetId = StartupSettings.resumeChannelId(this, channels)
                (channels.firstOrNull { it.id == targetId } ?: channels.firstOrNull())
                    ?.let { playChannel(it) }
            }
        }
    }
}