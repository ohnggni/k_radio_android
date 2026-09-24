package kr.ohnggni.kradio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun ChannelManageScreen(
    all: List<Channel>,                  // 사용자 순서, 숨김 포함, 수정 반영
    defaults: Map<String, Channel>,      // GitHub 원본 (기본 채널 판단·기본값 표시용)
    hidden: Set<String>,
    overrides: Map<String, ChannelOverride>,  // 기본 채널 수정 내용
    onBack: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onToggleVisible: (String, Boolean) -> Unit,
    onAdd: (name: String, url: String, logo: String) -> Unit,
    onEdit: (id: String, name: String, url: String, logo: String) -> Unit,
    onResetChannel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onResetAll: () -> Unit,
) {
    val edited = overrides.keys
    var list by remember(all) { mutableStateOf(all) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var confirmDelete by remember { mutableStateOf<Channel?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = list.indexOfFirst { it.id == from.key }
        val toIdx = list.indexOfFirst { it.id == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            list = list.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        }
    }
    val visibleCount = list.count { it.id !in hidden }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(IconBack, contentDescription = "뒤로") }
                Text(
                    "채널 관리",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmResetAll = true }) { Text("전체 초기화") }
            }
        }
    ) { inner ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    "≡ 끌어서 순서 변경 · 채널을 눌러 수정 · 스위치로 표시/숨김",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            items(list, key = { it.id }) { ch ->
                ReorderableItem(reorderState, key = ch.id) { isDragging ->
                    val visible = ch.id !in hidden
                    val isCustom = !defaults.containsKey(ch.id)
                    val subLabel = buildList {
                        add(ch.group)
                        if (ch.id in edited) add("수정됨")
                        if (!visible) add("숨김")
                    }.joinToString(" · ")

                    Surface(
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (isDragging) 6.dp else 0.dp,
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editing = ch }
                                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                modifier = Modifier.draggableHandle(
                                    onDragStopped = { onReorder(list.map { it.id }) }
                                ),
                                onClick = {}
                            ) { Icon(IconDrag, contentDescription = "순서 변경") }

                            ChannelLogo(
                                ch.logo, ch.name,
                                Modifier.size(40.dp).alpha(if (visible) 1f else 0.4f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.alpha(if (visible) 1f else 0.5f)
                                )
                                Text(
                                    subLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ch.id in edited) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isCustom) {
                                IconButton(onClick = { confirmDelete = ch }) {
                                    Icon(IconDelete, contentDescription = "삭제")
                                }
                            }
                            Switch(
                                checked = visible,
                                enabled = !(visible && visibleCount <= 1),
                                onCheckedChange = { onToggleVisible(ch.id, it) }
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("+ 채널 추가") }
            }
        }
    }

    // ---------- 추가 ----------
    if (showAdd) {
        ChannelDialog(
            title = "채널 추가",
            initialName = "", initialUrl = "", initialLogo = "",
            builtIn = false,
            onDismiss = { showAdd = false },
            onReset = null,
            onConfirm = { name, url, logo ->
                onAdd(name, url, logo)
                showAdd = false
            }
        )
    }

    // ---------- 수정 ----------
    editing?.let { ch ->
        val def = defaults[ch.id]
        val ov = overrides[ch.id]
        ChannelDialog(
            title = "채널 수정",
            initialName = ch.name,
            // 기본 채널은 원래 주소·로고를 보여주지 않고, 직접 바꾼 값만 표시
            initialUrl = if (def != null) ov?.url.orEmpty() else ch.url.orEmpty(),
            initialLogo = if (def != null) ov?.logo.orEmpty() else ch.logo.orEmpty(),
            builtIn = def != null,
            onDismiss = { editing = null },
            onReset = if (ch.id in edited) {
                { onResetChannel(ch.id); editing = null }
            } else null,
            onConfirm = { name, url, logo ->
                onEdit(ch.id, name, url, logo)
                editing = null
            }
        )
    }

    // ---------- 삭제 확인 ----------
    confirmDelete?.let { ch ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("채널 삭제") },
            text = { Text("'${ch.name}' 채널을 삭제할까?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(ch.id)
                    confirmDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } }
        )
    }

    // ---------- 전체 초기화 확인 ----------
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("전체 초기화") },
            text = {
                Text("순서·숨김·기본 채널 수정 내용을 모두 되돌리고 GitHub 기본 설정을 다시 받아와.\n직접 추가한 채널은 유지돼.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onResetAll()
                    confirmResetAll = false
                }) { Text("초기화") }
            },
            dismissButton = { TextButton(onClick = { confirmResetAll = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun ChannelDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    initialLogo: String,
    builtIn: Boolean,                 // 기본 채널이면 주소·로고를 비워두면 기본 제공값 사용
    onDismiss: () -> Unit,
    onReset: (() -> Unit)?,
    onConfirm: (name: String, url: String, logo: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var logo by remember { mutableStateOf(initialLogo) }

    // 공백 정리는 저장할 때만 (입력 중에 고치면 커서가 튐)
    val u = url.trim()
    val urlOk = if (u.isEmpty()) builtIn else (u.startsWith("http://") || u.startsWith("https://"))
    val valid = name.isNotBlank() && urlOk
    val uriKeyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
    val hintStyle = MaterialTheme.typography.bodySmall

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("채널 이름") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("스트림 주소") },
                    placeholder = if (builtIn) { { Text("(기본 제공 주소)") } } else null,
                    supportingText = if (builtIn) {
                        {
                            Text(
                                if (url.isBlank()) "기본 제공 주소로 재생 중이에요. 필요할 때만 다른 주소를 입력해 교체하세요."
                                else "직접 입력한 주소로 재생해요. 비우면 기본 제공 주소로 돌아가요.",
                                style = hintStyle
                            )
                        }
                    } else null,
                    minLines = 2, maxLines = 5,               // 긴 주소는 줄바꿈해서 전체 표시
                    keyboardOptions = uriKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = logo, onValueChange = { logo = it },
                    label = { Text("로고 이미지 주소 (선택)") },
                    placeholder = if (builtIn) { { Text("(기본 제공 로고)") } } else null,
                    supportingText = if (builtIn) {
                        {
                            Text(
                                if (logo.isBlank()) "기본 제공 로고를 사용 중이에요."
                                else "직접 입력한 로고를 사용해요. 비우면 기본 제공 로고로 돌아가요.",
                                style = hintStyle
                            )
                        }
                    } else null,
                    maxLines = 3,
                    keyboardOptions = uriKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!builtIn) {
                    Text(
                        "m3u8(HLS), mp3, aac 스트림 주소를 지원해",
                        style = hintStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(name.trim(), u, logo.trim()) }
            ) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onReset != null) {
                    TextButton(onClick = onReset) { Text("기본값으로") }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

private val IconBack = svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
private val IconDrag = svgIcon("drag", "M20,9H4v2h16V9zM4,15h16v-2H4v2z")
private val IconDelete = svgIcon(
    "delete",
    "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
)