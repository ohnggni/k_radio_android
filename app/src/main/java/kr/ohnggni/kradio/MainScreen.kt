package kr.ohnggni.kradio

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ---------------- 메인 화면 ----------------

@Composable
fun MainScreen(
    channels: List<Channel>,
    epg: EpgData?,
    now: Long,
    currentId: String?,
    isOn: Boolean,
    status: String,
    enabled: Boolean,
    onChannelClick: (Channel) -> Unit,
    onPlayStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    warning: String?,
    updateNotice: String?,
    onUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    sleepAt: Long?,
    onOpenSleep: () -> Unit,
    appVolume: Int,
    volumeSync: Boolean,
    sysVol: Int,
    sysMax: Int,
    onAppVolume: (Int) -> Unit,
    onSysVolume: (Int) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    carMode: Boolean,
    hasSchedules: Boolean,
    onOpenSchedules: () -> Unit,
) {
    val current = channels.firstOrNull { it.id == currentId }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopBanner(
                    now = now,
                    onSettings = onOpenSettings,
                    isOn = isOn,
                    sleepAt = sleepAt,
                    hasSchedules = hasSchedules,
                    onOpenSleep = onOpenSleep,
                    onOpenSchedules = onOpenSchedules,
                )
                warning?.let { WarningCard(it, onOpenSettings) }
                updateNotice?.let { UpdateCard(it, onUpdate, onDismissUpdate) }
            }
        },
        bottomBar = {
            PlayerBar(
                ch = current,
                program = epg?.display(current?.epg, now),
                isOn = isOn,
                status = status,
                enabled = enabled,
                onPlayStop = onPlayStop,
                onPrev = onPrev,
                onNext = onNext,
                now = now,
                sleepAt = sleepAt,
                appVolume = appVolume,
                volumeSync = volumeSync,
                sysVol = sysVol,
                sysMax = sysMax,
                onAppVolume = onAppVolume,
                onSysVolume = onSysVolume,
                onToggleSync = onToggleSync,
                onToggleMute = onToggleMute,
                carMode = carMode,
            )
        }
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            items(channels, key = { it.id }) { ch ->
                ChannelRow(
                    ch = ch,
                    program = epg?.display(ch.epg, now),
                    selected = ch.id == currentId,
                    enabled = enabled,
                    onClick = { onChannelClick(ch) },
                )
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)

@Composable
private fun WarningCard(text: String, onSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("설정") }
        }
    }
}
@Composable
private fun UpdateCard(text: String, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("나중에") }
            TextButton(onClick = onUpdate) { Text("받기") }
        }
    }
}
@Composable
private fun TopBanner(
    now: Long,
    onSettings: () -> Unit,
    isOn: Boolean,
    sleepAt: Long?,
    hasSchedules: Boolean,
    onOpenSleep: () -> Unit,
    onOpenSchedules: () -> Unit,
) {
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(
                    Brush.horizontalGradient(listOf(cs.primaryContainer, cs.secondaryContainer))
                )
                .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                IconRadio,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "KRadio",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = cs.onPrimaryContainer
                )
                Text(
                    dateFmt.format(Date(now)),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            // 버튼인 걸 알아보기 쉽게: 바탕 있는 둥근 칸 + 아이콘 아래 이름
            // 폭 고정(48dp × 3): 글자 크기와 상관없이 배너 오른쪽 절반 안에 머물게
            BannerButton(IconSleep, "꺼짐", "꺼짐 예약", active = sleepAt != null, enabled = isOn, onClick = onOpenSleep)
            Spacer(Modifier.width(4.dp))
            BannerButton(IconAlarm, "켜짐", "켜짐 예약", active = hasSchedules, onClick = onOpenSchedules)
            Spacer(Modifier.width(4.dp))
            BannerButton(IconSettings, "설정", "설정", onClick = onSettings)
        }
    }
}

/** 상단 배너 버튼: 반투명 바탕의 둥근 칸에 아이콘과 이름. active면 강조색, 비활성은 흐리게 */
@Composable
private fun BannerButton(
    icon: ImageVector,
    label: String,
    desc: String,   // 음성 안내용 전체 이름
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> cs.onPrimaryContainer.copy(alpha = 0.38f)
        active -> cs.primary
        else -> cs.onPrimaryContainer
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surface.copy(alpha = if (enabled) 0.7f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .width(48.dp)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
@Composable
private fun ChannelRow(
    ch: Channel,
    program: Program?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 선택된 채널: 왼쪽 세로 강조 막대
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
        Row(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(ch.logo, ch.name, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    program?.label() ?: "편성 정보 없음",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (program != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    program.timeRange(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlayerBar(
    ch: Channel?,
    program: Program?,
    isOn: Boolean,
    status: String,
    enabled: Boolean,
    onPlayStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    now: Long,
    sleepAt: Long?,
    appVolume: Int,
    volumeSync: Boolean,
    sysVol: Int,
    sysMax: Int,
    onAppVolume: (Int) -> Unit,
    onSysVolume: (Int) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    carMode: Boolean,   // 안드로이드 오토 연결 중: 폰 음량은 차 소리와 무관 → 앱 음량만
) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = container,
        contentColor = onContainer,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {

            // ---------- 1줄: 음량 ----------
            val sysMode = volumeSync && !carMode
            val muted = if (sysMode) sysVol == 0 else appVolume == 0
            val label = if (sysMode) {
                "${if (sysMax > 0) sysVol * 100 / sysMax else 0}%"
            } else "${appVolume}%"

            Row(
                Modifier
                    .fillMaxWidth()
                    .systemGestureExclusion()   // 이 줄에서는 좌우 뒤로 가기 제스처보다 앱 터치 우선
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (muted) IconVolumeOff else IconVolume,
                        contentDescription = if (muted) "음소거 해제" else "음소거",
                        modifier = Modifier.size(22.dp),
                        tint = onContainer.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (sysMode) {
                    VolumeSlider(
                        value = sysVol.toFloat(),
                        onValueChange = { onSysVolume(it.roundToInt()) },
                        valueRange = 0f..sysMax.coerceAtLeast(1).toFloat(),
                        steps = (sysMax - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    VolumeSlider(
                        value = appVolume.toFloat(),
                        onValueChange = { onAppVolume((it / 5).roundToInt() * 5) },  // 5% 단위
                        valueRange = AppVolume.MIN.toFloat()..AppVolume.MAX.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp)
                )
                if (carMode) {
                    // 오토 연결 중: 음량은 차량에서, 여기선 앱 음량(증폭)만
                    Text(
                        "차량",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                } else {
                    Checkbox(checked = volumeSync, onCheckedChange = onToggleSync)
                    Text("시스템", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ---------- 2줄: 로고 + 채널명·방송·상태 3줄 + 재생 버튼 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelLogo(ch?.logo, ch?.name ?: "K", Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))

                // 3줄: 채널명 / 방송 정보 / 재생 상태 (채널명이 폭을 다 쓰도록 상태는 따로 한 줄)
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        ch?.name ?: "채널을 선택하세요",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = status.ifEmpty { program?.label() ?: "" }
                    Text(
                        sub.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val stateLabel = when {
                        ch == null -> "대기 중"
                        isOn -> "● 재생 중"
                        else -> "정지됨"
                    }
                    Text(
                        listOfNotNull(stateLabel, sleepAt?.let { sleepRemainLabel(it, now) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOn) MaterialTheme.colorScheme.primary else onContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onPrev,
                    enabled = enabled && ch != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(IconPrev, contentDescription = "이전 채널", modifier = Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = onPlayStop,
                    enabled = enabled,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        if (isOn) IconStop else IconPlay,
                        contentDescription = if (isOn) "정지" else "재생",
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = enabled && ch != null,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(IconNext, contentDescription = "다음 채널", modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}
/** 재생기 음량 슬라이더: 손잡이를 동그랗게 키우고 터치 영역을 넓힘 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier.height(44.dp),
        interactionSource = interaction,
        thumb = {
            // 누를 때 모양이 변하지 않는 단순한 동그라미 손잡이
            Box(
                Modifier
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    )
}
// ---------------- 프로그램 표시 도우미 ----------------

private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

private fun Program.label(): String =
    if (subTitle.isNullOrBlank()) title else "$title · $subTitle"

private fun Program.timeRange(): String =
    "${hm.format(Date(start))}–${hm.format(Date(stop))}"

// ---------------- 로고 ----------------

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { LogoCache.get(context, it) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(name.take(1), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 로고를 한 번 받아서 폰에 저장해두고 재사용 */
/** 로고 불러오기: 앱 내장 로고 우선, 없으면 인터넷에서 받아 폰에 저장해두고 재사용 */
object LogoCache {
    const val ASSET_PREFIX = "asset:///"

    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun get(context: Context, url: String): ImageBitmap? =
        memory[url] ?: withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith(ASSET_PREFIX)) {
                    val path = url.removePrefix(ASSET_PREFIX)
                    val bundled = runCatching {
                        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    if (bundled != null) return@runCatching bundled.asImageBitmap()
                    // 내장 로고가 없는 새 채널: 설정 파일의 logoBase 주소로 시도
                    val base = ChannelRepository.logoBase ?: return@runCatching null
                    return@runCatching download(context, base + path.substringAfterLast('/'))
                }
                download(context, url)
            }.getOrNull()?.also { memory[url] = it }
        }

    private fun download(context: Context, url: String): ImageBitmap? {
        val dir = File(context.cacheDir, "logos").apply { mkdirs() }
        val file = File(dir, url.hashCode().toString())
        if (!file.exists()) {
            val tmp = File(dir, file.name + ".tmp")
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            try {
                conn.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            tmp.renameTo(file)
        }
        return BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }
}

// ---------------- 아이콘 (라이브러리 없이 직접 정의) ----------------

internal fun svgIcon(name: String, d: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)).build()

private val IconPlay = svgIcon("play", "M8,5v14l11,-7z")
private val IconStop = svgIcon("stop", "M6,6h12v12H6z")
private val IconPrev = svgIcon("prev", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
private val IconNext = svgIcon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
private val IconRadio = svgIcon(
    "radio",
    "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2V8c0,-1.11 -0.89,-2 -2,-2H8.3l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2H4V8h16v4z"
)
private val IconSettings = svgIcon(
    "settings",
    "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
)
private fun sleepRemainLabel(at: Long, now: Long): String {
    val min = ((at - now) / 60_000L).coerceAtLeast(0)
    return when {
        min < 1 -> "곧 꺼짐"
        min < 60 -> "${min}분 후 꺼짐"
        else -> "%d:%02d 후 꺼짐".format(min / 60, min % 60)
    }
}

private val IconSleep = svgIcon(
    "sleep",
    "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z"
)
private val IconVolume = svgIcon(
    "volume",
    "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
)
private val IconVolumeOff = svgIcon(
    "volume_off",
    "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"
)
private val IconAlarm = svgIcon(
    "alarm",
    "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
)