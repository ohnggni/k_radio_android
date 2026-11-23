package kr.ohnggni.kradio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 음악 분류 이름 한글화 (모르는 이름은 영문 그대로) */
private val GENRE_KO = mapOf(
    "Top 40 & Pop Music" to "팝·Top 40", "Rock Music" to "록", "Soul & R&B" to "소울·R&B", "Country" to "컨트리",
    "World Music" to "월드", "Religious Music" to "종교", "Reggae & Dancehall" to "레게", "Latin Music" to "라틴",
    "Jazz" to "재즈", "Classical" to "클래식", "Electronic" to "일렉트로닉", "Hip Hop" to "힙합", "Hip Hop & Rap" to "힙합",
    "Oldies" to "올디스", "Easy Listening" to "이지 리스닝", "Folk" to "포크", "Blues" to "블루스", "Ambient" to "앰비언트",
    "Decades" to "연대별", "Alternative" to "얼터너티브", "Metal" to "메탈", "Dance" to "댄스",
    "Adult Hits" to "성인 히트", "Children's Music" to "어린이", "Holiday Music" to "시즌", "Soundtracks" to "OST",
)

/** 칩으로 바로 보여줄 장르 (한글 이름, 이 순서로. 나머지는 "장르 더 보기") */
private val PINNED_GENRES = listOf("팝·Top 40", "록", "재즈", "클래식", "일렉트로닉", "소울·R&B")

/** 목록 화면 하나: 제목 + 받기 함수 */
private class BrowseList(val title: String, val fetch: suspend () -> List<WebStation>)

/** 검색: 지금 방송 중인 인라이브에서 이름·제목 일치 + 방송 안내 서비스 검색 */
private suspend fun searchAll(q: String): List<WebStation> {
    val live = runCatching { RadioDirectory.inliveOnAir() }.getOrDefault(emptyList())
        .filter { it.name.contains(q, true) || (it.sub?.contains(q, true) == true) || (it.tag?.contains(q, true) == true) }
    val dir = runCatching { RadioDirectory.search(q) }.getOrDefault(emptyList())
    return (live + dir).distinctBy { it.url }
}

/**
 * 인터넷 방송 찾기: 검색 + 장르 칩 + 추천 줄(추천 방송 / 지금 방송 중인 개인 DJ / K-pop).
 * "더 보기"·검색·장르는 세로 목록. + 버튼으로 바로 채널 추가(로고 포함).
 */
@Composable
fun BrowseScreen(
    addedUrls: Set<String>,                         // 이미 추가된 주소 (✓ 표시)
    onAdd: (WebStation) -> Unit,
    onAddManual: (name: String, url: String, logo: String) -> Unit,
    onPreview: ((WebStation) -> Unit)?,             // 로고 누르면 미리 듣기 (다시 누르면 정지)
    preview: WebStation?,                           // 지금 재생기에 올라 있는 미리 듣기 방송
    previewOn: Boolean,                             // 재생 중인지
    previewStatus: String,                          // 연결 중·오류 등 상태 글자
    onPreviewStop: () -> Unit,
    onBack: () -> Unit,
) {
    val previewUrl = preview?.url?.takeIf { previewOn }
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }   // 선택된 칩 표시 이름 (null = 추천)
    var list by remember { mutableStateOf<BrowseList?>(null) }
    var manual by remember { mutableStateOf(false) }
    var showGenres by remember { mutableStateOf(false) }   // 장르 더 보기 창

    // 목록 화면에서 뒤로 가기 → 추천 첫 화면
    androidx.activity.compose.BackHandler(enabled = list != null) { list = null; genre = null }

    val pageColor = if (cs.background.luminance() > 0.5f) cs.surfaceContainer else cs.background
    Scaffold(
        containerColor = pageColor,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (list != null) { list = null; genre = null } else onBack() }) {
                    Icon(BrowseIconBack, contentDescription = "뒤로")
                }
                Text("인터넷 방송 찾기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
        },
        bottomBar = {
            if (preview != null) PreviewBar(preview, previewOn, previewStatus, preview.url in addedUrls,
                onToggle = { if (previewOn) onPreviewStop() else onPreview?.invoke(preview) },
                onAdd = { onAdd(preview) })
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ---------- 검색 ----------
            item(key = "@search") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("방송 이름·DJ 검색") },
                    leadingIcon = { Icon(BrowseIconSearch, contentDescription = null) },
                    trailingIcon = {
                        if (text.isNotEmpty()) IconButton(onClick = { text = "" }) {
                            Icon(BrowseIconClose, contentDescription = "지우기")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = text.trim()
                        if (q.isNotEmpty()) {
                            focus.clearFocus()
                            genre = null
                            list = BrowseList("'$q' 검색 결과") { searchAll(q) }
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surface, unfocusedContainerColor = cs.surface,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            // ---------- 장르 칩 ----------
            item(key = "@chips") {
                // 칩은 짧게: 추천 · K-pop + 자주 찾는 장르 몇 개 + "장르 더 보기"(나머지는 창에서 가나다순)
                val cats by produceState(emptyList<RadioDirectory.Category>()) {
                    value = runCatching { RadioDirectory.musicCategories() }.getOrDefault(emptyList())
                }
                val labeled = cats.map { (GENRE_KO[it.name] ?: it.name) to it }
                val pinned = PINNED_GENRES.mapNotNull { want -> labeled.firstOrNull { it.first == want } }
                val rest = (labeled - pinned.toSet()).sortedBy { it.first }
                fun open(label: String, c: RadioDirectory.Category) {
                    genre = label; list = BrowseList("$label 방송") { RadioDirectory.category(c.id) }
                }
                val chips: List<Pair<String, (() -> Unit)?>> = listOf<Pair<String, (() -> Unit)?>>(
                    "추천" to null,
                    "K-pop" to { genre = "K-pop"; list = BrowseList("K-pop 방송") { RadioDirectory.search("kpop") } },
                ) + pinned.map { (label, c) -> label to { open(label, c) } } +
                    (if (rest.isNotEmpty()) listOf<Pair<String, (() -> Unit)?>>("장르 더 보기" to { showGenres = true }) else emptyList())
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chips, key = { it.first }) { (label, act) ->
                        val inRest = rest.any { it.first == genre }
                        FilterChip(
                            selected = genre == label || (label == "추천" && list == null) || (label == "장르 더 보기" && inRest),
                            onClick = { if (act == null) { list = null; genre = null } else act() },
                            label = { Text(if (label == "장르 더 보기" && inRest) genre!! else label) },
                        )
                    }
                }
                if (showGenres) {
                    AlertDialog(
                        onDismissRequest = { showGenres = false },
                        confirmButton = { TextButton(onClick = { showGenres = false }) { Text("닫기") } },
                        title = { Text("장르") },
                        text = {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                rest.forEach { (label, c) ->
                                    Text(label, style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fillMaxWidth().clickable { showGenres = false; open(label, c) }
                                            .padding(vertical = 12.dp))
                                }
                            }
                        }
                    )
                }
            }

            val current = list
            if (current == null) {
                // ---------- 추천 첫 화면 ----------
                ChannelRepository.internetPicks.forEach { (title, picks) ->
                    item(key = "@picks:$title") {
                        StationShelf(title, { Result.success(picks) }, null,
                            addedUrls, previewUrl, onAdd, onPreview)
                    }
                }
                item(key = "@inlive") {
                    StationShelf("인라이브 · 지금 방송 중인 개인 DJ", { runCatching { RadioDirectory.inliveOnAir().take(20) } },
                        { list = BrowseList("인라이브 · 지금 방송 중인 개인 DJ") { RadioDirectory.inliveOnAir() } },
                        addedUrls, previewUrl, onAdd, onPreview)
                }
                item(key = "@kpop") {
                    StationShelf("K-pop 방송", { runCatching { RadioDirectory.search("kpop").take(20) } },
                        { genre = "K-pop"; list = BrowseList("K-pop 방송") { RadioDirectory.search("kpop") } },
                        addedUrls, previewUrl, onAdd, onPreview)
                }
            } else {
                // ---------- 목록 (더 보기·검색·장르) ----------
                item(key = "@list:${current.title}") {
                    StationList(current, addedUrls, previewUrl, onAdd, onPreview)
                }
            }

            item(key = "@manual") {
                TextButton(onClick = { manual = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("찾는 방송이 없으면 주소 직접 입력")
                }
            }
        }
    }

    if (manual) {
        ChannelDialog(
            title = "채널 추가",
            initialName = "", initialUrl = "", initialLogo = "",
            builtIn = false,
            onDismiss = { manual = false },
            onReset = null,
            onConfirm = { name, url, logo -> onAddManual(name, url, logo); manual = false }
        )
    }
}

/** 받기 결과 상태 */
private sealed class Load {
    object Loading : Load()
    class Done(val list: List<WebStation>) : Load()
    object Failed : Load()
}

@Composable
private fun rememberLoad(key: Any, fetch: suspend () -> Result<List<WebStation>>): Pair<Load, () -> Unit> {
    var retry by remember(key) { mutableIntStateOf(0) }
    val state by produceState<Load>(Load.Loading, key, retry) {
        value = Load.Loading
        value = fetch().fold({ Load.Done(it) }, { Load.Failed })
    }
    return state to { retry++ }
}

/** 가로로 넘기는 추천 줄 */
@Composable
private fun StationShelf(
    title: String,
    fetch: suspend () -> Result<List<WebStation>>,
    onMore: (() -> Unit)?,
    addedUrls: Set<String>,
    previewUrl: String?,
    onAdd: (WebStation) -> Unit,
    onPreview: ((WebStation) -> Unit)?,
) {
    val (load, retry) = rememberLoad(title, fetch)
    if (load is Load.Done && load.list.isEmpty()) return   // 받은 방송이 없으면 줄 자체를 숨김
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onMore != null) TextButton(onClick = onMore) { Text("더 보기") }
        }
        when (load) {
            Load.Loading -> LoadingLine()
            Load.Failed -> FailedLine(retry)
            is Load.Done -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(load.list, key = { it.url }) { st ->
                    StationCard(st, st.url in addedUrls, st.url == previewUrl, { onAdd(st) }, onPreview?.let { { it(st) } })
                }
            }
        }
    }
}

/** 세로 목록 */
@Composable
private fun StationList(
    list: BrowseList,
    addedUrls: Set<String>,
    previewUrl: String?,
    onAdd: (WebStation) -> Unit,
    onPreview: ((WebStation) -> Unit)?,
) {
    val (load, retry) = rememberLoad(list.title) { runCatching { list.fetch() } }
    Column(Modifier.padding(top = 8.dp)) {
        Text(list.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        when (load) {
            Load.Loading -> LoadingLine()
            Load.Failed -> FailedLine(retry)
            is Load.Done -> if (load.list.isEmpty()) {
                Text("찾은 방송이 없어요. 다른 이름이나 영문으로 찾아보세요.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else {
                // 바깥 LazyColumn 안이라 일반 Column으로 (최대 100개)
                load.list.forEach { st ->
                    StationRow(st, st.url in addedUrls, st.url == previewUrl, { onAdd(st) }, onPreview?.let { { it(st) } })
                }
            }
        }
    }
}

@Composable
private fun StationCard(st: WebStation, added: Boolean, previewing: Boolean, onAdd: () -> Unit, onPreview: (() -> Unit)?) {
    Column(Modifier.width(84.dp)) {
        Box {
            ChannelLogo(st.logo, st.name, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                .then(if (onPreview != null) Modifier.clickable(onClick = onPreview) else Modifier))
            if (previewing) PreviewBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            AddButton(added, onAdd, Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(st.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (st.listeners > 0) "청취 ${st.listeners}" else st.sub ?: st.tag ?: " ", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StationRow(st: WebStation, added: Boolean, previewing: Boolean, onAdd: () -> Unit, onPreview: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp)).background(cs.surface)
            .then(if (onPreview != null) Modifier.clickable(onClick = onPreview) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(st.logo, st.name, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (previewing) "미리 듣는 중" else st.meta(), style = MaterialTheme.typography.bodySmall,
                color = if (previewing) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        AddButton(added, onAdd)
    }
}

/** + 추가 / ✓ 추가됨 */
@Composable
private fun AddButton(added: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .background(if (added) cs.secondaryContainer else cs.primary)
            .clickable(enabled = !added, onClick = onAdd),
        contentAlignment = Alignment.Center
    ) {
        Icon(if (added) BrowseIconCheck else BrowseIconAdd, contentDescription = if (added) "추가됨" else "채널 추가",
            tint = if (added) cs.onSecondaryContainer else cs.onPrimary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PreviewBadge(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text("듣는 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** 미리 듣기 막대: 로고·이름·상태 + 재생/정지 + 추가 */
@Composable
private fun PreviewBar(st: WebStation, on: Boolean, status: String, added: Boolean, onToggle: () -> Unit, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(st.logo, st.name, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(st.name, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.ifEmpty { if (on) "미리 듣는 중" else "멈춤" }, style = MaterialTheme.typography.bodySmall,
                    color = cs.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                Icon(if (on) BrowseIconStop else BrowseIconPlay, contentDescription = if (on) "정지" else "재생")
            }
            Spacer(Modifier.width(8.dp))
            AddButton(added, onAdd, Modifier.size(36.dp))
        }
    }
}

@Composable
private fun LoadingLine() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("방송 목록을 받는 중", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailedLine(retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("방송 목록을 못 받았어요", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = retry) { Text("다시 시도") }
    }
}

// ---------------- 아이콘 ----------------
private val BrowseIconBack = svgIcon("browse_back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
private val BrowseIconSearch = svgIcon("browse_search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
private val BrowseIconClose = svgIcon("browse_close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
private val BrowseIconAdd = svgIcon("browse_add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
private val BrowseIconPlay = svgIcon("browse_play", "M8,5v14l11,-7z")
private val BrowseIconStop = svgIcon("browse_stop", "M6,6h12v12H6z")
private val BrowseIconCheck = svgIcon("browse_check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
