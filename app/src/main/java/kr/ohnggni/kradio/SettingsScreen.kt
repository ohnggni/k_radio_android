package kr.ohnggni.kradio

import androidx.compose.foundation.clickable
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
) {
    var editConfig by remember { mutableStateOf(false) }
    var editEpg by remember { mutableStateOf(false) }

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
            SectionTitle("채널")
            SettingRow(
                title = "채널 관리",
                value = "순서 변경 · 숨기기 · 추가 · 수정",
                onClick = onOpenManage
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("데이터 출처")
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
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("앱 정보")
            SettingRow(
                title = "버전",
                value = newVersion?.let { "$appVersion · 새 버전 $it 있음" } ?: appVersion,
                onClick = onOpenUpdate
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
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

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

private val IconBackSettings =
    svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")