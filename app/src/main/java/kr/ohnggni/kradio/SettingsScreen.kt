package kr.ohnggni.kradio

import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsScreen(
    customConfig: String?,        // null = KRadio 기본 제공
    customEpg: String?,
    configError: String?,
    epgError: String?,
    reloading: Boolean,
    onBack: () -> Unit,
    onOpenManage: () -> Unit,
    onOpenGuide: () -> Unit,
    onSaveConfig: (String?) -> Unit,
    onSaveEpg: (String?) -> Unit,
    onReload: () -> Unit,
    appVersion: String,
    newVersion: String?,
    onOpenUpdate: () -> Unit,
    onOpenNotes: () -> Unit,
    channels: List<Channel>,
    startupMode: String,
    startupChannel: String?,
    textScale: Int,
    onSaveTextScale: (Int) -> Unit,
    onSaveStartup: (String, String?) -> Unit,
) {
    var editConfig by remember { mutableStateOf(false) }
    var editEpg by remember { mutableStateOf(false) }
    var editStartup by remember { mutableStateOf(false) }
    var editTextScale by remember { mutableStateOf(false) }

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
                IconButton(onClick = onBack) { Icon(IconBackSettings, contentDescription = "뒤로") }
                Text("설정", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SectionTitle("채널", IconSecChannel, first = true)
            SettingRow(
                title = "채널 관리",
                value = "순서 변경 · 숨기기 · 추가 · 수정",
                onClick = onOpenManage
            )
            SettingRow(
                title = "시작 시 재생",
                value = when (startupMode) {
                    StartupSettings.MODE_LAST -> "마지막으로 들은 채널"
                    StartupSettings.MODE_FIXED -> channels.firstOrNull { it.id == startupChannel }
                        ?.let { "지정 채널 · ${it.name}" }
                        ?: "지정 채널 (찾을 수 없어 마지막 채널로 재생)"
                    else -> "재생 안 함"
                },
                onClick = { editStartup = true }
            )


            SectionTitle("화면", IconSecDisplay)
            SettingRow(
                title = "글자 크기",
                value = TextScale.label(textScale),
                onClick = { editTextScale = true }
            )

            SectionTitle("데이터 출처", IconSecData)
            SettingRow(
                title = "채널 설정",
                value = customConfig ?: "KRadio 기본 제공",
                error = configError,
                onClick = { editConfig = true }
            )
            SettingRow(
                title = "편성표 (EPG)",
                value = customEpg ?: if (customConfig != null) "채널 설정 파일에 지정된 편성표" else "KRadio 기본 제공",
                error = epgError,
                onClick = { editEpg = true }
            )
            SettingRow(
                title = "데이터 형식 안내",
                value = "직접 데이터를 만들어 쓸 때 참고하세요",
                onClick = onOpenGuide
            )
            OutlinedButton(
                onClick = onReload,
                enabled = !reloading,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(if (reloading) "불러오는 중..." else "지금 다시 불러오기") }

            Text(
                "기본 제공 데이터 서버에 문제가 생기면, 직접 준비한 채널 설정(channels.json)과 " +
                        "편성표(XMLTV) 주소를 지정해서 계속 사용할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SectionTitle("앱 정보", IconSecInfo)
            SettingRow(
                title = "버전",
                value = newVersion?.let { "$appVersion · 새 버전 $it 있음" } ?: appVersion,
                onClick = onOpenUpdate
            )
            SettingRow(
                title = "변경 내역",
                value = "버전별로 바뀐 점",
                onClick = onOpenNotes
            )
            SettingRow(
                title = "업데이트 받기",
                value = "다운로드 폴더 열기",
                onClick = onOpenUpdate
            )
        }
    }

    if (editConfig) {
        SourceDialog(
            title = "채널 설정 주소",
            current = customConfig,
            guide = "KRadio의 channels.json과 같은 형식의 파일 주소를 입력하세요.",
            onDismiss = { editConfig = false },
            onSave = { onSaveConfig(it); editConfig = false }
        )
    }
    if (editEpg) {
        SourceDialog(
            title = "편성표(EPG) 주소",
            current = customEpg,
            guide = "XMLTV 형식(xmltv.xml) 파일 주소를 입력하세요. 비워두면 채널 설정 파일에 지정된 편성표나 기본 제공 편성표를 사용해요.",
            onDismiss = { editEpg = false },
            onSave = { onSaveEpg(it); editEpg = false }
        )
    }
    if (editTextScale) {
        TextScaleDialog(
            current = textScale,
            onDismiss = { editTextScale = false },
            onSave = { onSaveTextScale(it); editTextScale = false }
        )
    }
    if (editStartup) {
        StartupDialog(
            channels = channels,
            currentMode = startupMode,
            currentChannel = startupChannel,
            onDismiss = { editStartup = false },
            onSave = { mode, id -> onSaveStartup(mode, id); editStartup = false }
        )
    }
}

/** 구역 제목: 연한 동그라미 안의 단색 아이콘 + 굵은 제목 (구분선 대신 위쪽 여백) */
@Composable
private fun SectionTitle(text: String, icon: ImageVector, first: Boolean = false) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 8.dp else 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// 구역 아이콘 (단색)
private val IconSecChannel = svgIcon("sec_channel",
    "M3.24,6.15C2.51,6.43 2,7.17 2,8v12c0,1.1 0.89,2 2,2h16c1.11,0 2,-0.9 2,-2V8c0,-1.11 -0.89,-2 -2,-2H8.3l8.26,-3.34L15.88,1 3.24,6.15zM7,20c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM20,12h-2v-2h-2v2H4V8h16v4z")
private val IconSecDisplay = svgIcon("sec_display",
    "M9,4v3h5v12h3V7h5V4H9zM3,12h3v7h3v-7h3V9H3V12z")
private val IconSecData = svgIcon("sec_data",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
private val IconSecInfo = svgIcon("sec_info",
    "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z")

@Composable
private fun SettingRow(
    title: String,
    value: String,
    error: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (error != null) {
            Text(
                "연결 실패 · 저장된 정보로 표시 중",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SourceDialog(
    title: String,
    current: String?,
    guide: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    val t = text.trim()
    val valid = t.isEmpty() || t.startsWith("http://") || t.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("주소") },
                    placeholder = { Text("(KRadio 기본 제공)") },
                    supportingText = {
                        Text(
                            if (t.isEmpty()) "비워두면 KRadio 기본 제공 데이터를 사용해요."
                            else "직접 지정한 주소를 사용해요.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 2, maxLines = 5,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    guide,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(t.ifEmpty { null }) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text("기본값으로") }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

@Composable
private fun StartupDialog(
    channels: List<Channel>,
    currentMode: String,
    currentChannel: String?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    var mode by remember { mutableStateOf(currentMode) }
    var chId by remember { mutableStateOf(currentChannel ?: channels.firstOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시작 시 재생") },
        text = {
            Column {
                Text(
                    "앱을 켤 때와 차에서 블루투스 재생 버튼을 누를 때 재생할 채널이에요.\n" +
                            "정지한 지 ${StartupSettings.AUTO_START_IDLE_MS / 60_000}분이 지난 뒤 앱을 다시 열어도 새로 켠 것으로 보고 재생해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ModeOption("재생 안 함", mode == StartupSettings.MODE_NONE) { mode = StartupSettings.MODE_NONE }
                ModeOption("마지막으로 들은 채널", mode == StartupSettings.MODE_LAST) { mode = StartupSettings.MODE_LAST }
                ModeOption("지정 채널", mode == StartupSettings.MODE_FIXED) { mode = StartupSettings.MODE_FIXED }
                if (mode == StartupSettings.MODE_FIXED) {
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp)
                    ) {
                        channels.forEach { ch ->
                            ModeOption(ch.name, chId == ch.id) { chId = ch.id }
                        }
                    }
                }
                if (mode == StartupSettings.MODE_NONE) {
                    Text(
                        "재생 안 함이어도 블루투스 재생 버튼을 누르면 마지막 채널이 재생돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != StartupSettings.MODE_FIXED || chId != null,
                onClick = { onSave(mode, if (mode == StartupSettings.MODE_FIXED) chId else null) }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun TextScaleDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var sel by remember { mutableStateOf(current) }
    // 앱 배율을 덮어써도 Configuration의 fontScale은 폰 설정값 그대로
    val phone = (LocalConfiguration.current.fontScale * 100).roundToInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("글자 크기") },
        text = {
            Column {
                Text(
                    "지금 폰 글꼴 크기: $phone%\n" +
                            "폰 글꼴을 크게 쓰는데 앱 화면이 비좁으면 앱만 따로 크기를 정할 수 있어요. 위젯은 폰 설정을 따라요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextScale.OPTIONS.forEach { v ->
                    ModeOption(TextScale.label(v), sel == v) { sel = v }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(sel) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private val IconBackSettings =
    svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
