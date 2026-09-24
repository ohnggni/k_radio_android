package kr.ohnggni.kradio

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
) {
    val current = channels.firstOrNull { it.id == currentId }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopBanner(now) },          // ← 추가
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
private fun TopBanner(now: Long) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()                                  // 상태표시줄은 비워두고
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(listOf(cs.primaryContainer, cs.secondaryContainer))
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
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
        }
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
) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(ch?.logo, ch?.name ?: "K", Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        ch == null -> "대기 중"
                        isOn -> "● 재생 중"
                        else -> "정지됨"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOn) MaterialTheme.colorScheme.primary else onContainer.copy(alpha = 0.7f)
                )
                Text(
                    ch?.name ?: "채널을 선택하세요",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = status.ifEmpty { program?.label() ?: "" }
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onPrev, enabled = enabled && ch != null) {
                Icon(IconPrev, contentDescription = "이전 채널")
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
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onNext, enabled = enabled && ch != null) {
                Icon(IconNext, contentDescription = "다음 채널")
            }
        }
    }
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
object LogoCache {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun get(context: Context, url: String): ImageBitmap? =
        memory[url] ?: withContext(Dispatchers.IO) {
            runCatching {
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
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()?.also { memory[url] = it }
        }
}

// ---------------- 아이콘 (라이브러리 없이 직접 정의) ----------------

private fun svgIcon(name: String, d: String): ImageVector =
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