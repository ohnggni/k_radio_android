package kr.ohnggni.kradio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(
    schedules: List<PlaySchedule>,
    channels: List<Channel>,
    epg: EpgData?,
    onBack: () -> Unit,
    onSave: (PlaySchedule) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<PlaySchedule?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(IconBackSchedule, contentDescription = "뒤로") }
                Text("켜짐 예약", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    "정한 시각에 채널을 자동으로 틀어줘요. 중요한 방송을 놓치지 않을 때 써보세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (schedules.isEmpty()) {
                item {
                    Text(
                        "아직 예약이 없어요",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(schedules.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { s ->
                val ch = channels.firstOrNull { it.id == s.channelId }
                val dim = if (s.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = s }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.timeLabel(), style = MaterialTheme.typography.headlineSmall, color = dim)
                        Text(
                            listOfNotNull(s.daysLabel(), ch?.name ?: "채널을 찾을 수 없음", s.label)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull("음량 ${s.volume}%", s.autoOffLabel().ifEmpty { null })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        onDelete(s.id)
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                "${s.timeLabel()} 예약을 삭제했어요",
                                actionLabel = "되돌리기",
                                duration = SnackbarDuration.Short
                            )
                            if (r == SnackbarResult.ActionPerformed) onSave(s)
                        }
                    }) {
                        Icon(IconDeleteSchedule, contentDescription = "삭제")
                    }
                    Switch(checked = s.enabled, onCheckedChange = { onSave(s.copy(enabled = it)) })
                }
                HorizontalDivider()
            }
            item {
                OutlinedButton(
                    onClick = { editing = PlaySchedule() },   // 채널은 직접 고르게
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("+ 예약 추가") }
            }
        }
    }

    editing?.let { s ->
        ScheduleEditDialog(
            initial = s,
            isNew = schedules.none { it.id == s.id },
            channels = channels,
            epg = epg,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(s.id); editing = null },
        )
    }
}

@Composable
private fun ScheduleEditDialog(
    initial: PlaySchedule,
    isNew: Boolean,
    channels: List<Channel>,
    epg: EpgData?,
    onDismiss: () -> Unit,
    onSave: (PlaySchedule) -> Unit,
    onDelete: () -> Unit,
) {
    var hText by remember { mutableStateOf("%02d".format(initial.hour)) }
    var mText by remember { mutableStateOf("%02d".format(initial.minute)) }
    var days by remember { mutableStateOf(initial.days) }
    var chId by remember { mutableStateOf(initial.channelId) }
    var pickChannel by remember { mutableStateOf(initial.channelId.isEmpty()) }
    var volume by remember { mutableFloatStateOf(initial.volume.toFloat()) }
    var autoOff by remember { mutableIntStateOf(initial.autoOff) }
    var label by remember { mutableStateOf(initial.label) }

    val hour = hText.toIntOrNull()?.takeIf { it in 0..23 }
    val minute = mText.toIntOrNull()?.takeIf { it in 0..59 }
    val channel = channels.firstOrNull { it.id == chId }
    val valid = channel != null && hour != null && minute != null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(if (isNew) "켜짐 예약 추가" else "켜짐 예약 수정") },
        text = {
            // 라디오 버튼·칩의 기본 최소 터치 크기(48dp)를 줄여 목록을 촘촘하게
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // ---------- 채널 ----------
                    SectionLabel("채널")
                    OutlinedButton(onClick = { pickChannel = !pickChannel }, modifier = Modifier.fillMaxWidth()) {
                        Text(channel?.name ?: "채널 선택")
                    }
                    if (pickChannel) {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            channels.forEach { ch ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clickable { chId = ch.id; pickChannel = false; label = null },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = chId == ch.id,
                                        onClick = { chId = ch.id; pickChannel = false; label = null }
                                    )
                                    Text(ch.name, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    // ---------- 방송에서 고르기 ----------
                    if (channel != null && !pickChannel) {
                        val programs = epg?.schedule(channel.epg) ?: emptyList()
                        SectionDivider()
                        if (programs.isNotEmpty()) {
                            SectionLabel("방송에서 고르기")
                            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                programs.forEach { p ->
                                    val c = Calendar.getInstance().apply { timeInMillis = p.start }
                                    val ph = c.get(Calendar.HOUR_OF_DAY)
                                    val pm = c.get(Calendar.MINUTE)
                                    val selected = label == p.title && hour == ph && minute == pm
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                                else MaterialTheme.colorScheme.surface
                                            )
                                            .clickable {
                                                hText = "%02d".format(ph)
                                                mText = "%02d".format(pm)
                                                autoOff = AUTO_OFF_PROGRAM_END
                                                label = p.title
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            dayTimeLabel(p.start),
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.width(76.dp)
                                        )
                                        Text(
                                            if (p.subTitle.isNullOrBlank()) p.title else "${p.title} · ${p.subTitle}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Hint("방송을 누르면 시작 시각과 '방송 끝나면 꺼짐'이 자동으로 맞춰져요.")
                        } else {
                            Hint("이 채널은 편성 정보가 없어 시각을 직접 정해주세요.")
                        }
                    }

                    // ---------- 시각 ----------
                    SectionDivider()
                    val nowText by produceState(initialValue = nowLabel()) {
                        while (true) {
                            delay(30_000)   // 창을 열어둔 동안 30초마다 갱신
                            value = nowLabel()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("시각")
                        Spacer(Modifier.width(8.dp))
                        Hint("지금 $nowText")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeNumberField(hText) { hText = it; label = null }
                        Text(" : ", style = MaterialTheme.typography.titleLarge)
                        TimeNumberField(mText) { mText = it; label = null }
                        // 입력한 시각이 실제로 언제인지 풀어서 표시 (오전·오후 실수 방지)
                        if (hour != null && minute != null) {
                            val next = PlaySchedule(hour = hour, minute = minute, days = days).nextTrigger()
                            Hint("→ ${nextTriggerLabel(next)}")
                        }
                    }

                    // ---------- 반복 ----------
                    SectionDivider()
                    SectionLabel("반복")
                    Row(Modifier.fillMaxWidth()) {
                        WEEK_ORDER.forEach { d ->
                            val on = d in days
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { days = if (on) days - d else days + d },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        dayName(d),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Hint(
                        if (days.isEmpty()) "요일을 고르지 않으면 한 번만 켜져요"
                        else PlaySchedule(days = days).daysLabel()
                    )

                    // ---------- 음량 ----------
                    SectionDivider()
                    SectionLabel("시스템 음량 ${volume.roundToInt()}%")
                    VolumeSlider(
                        value = volume,
                        onValueChange = { volume = ((it / 5).roundToInt() * 5).toFloat() },
                        valueRange = 10f..100f
                    )
                    Hint("켜질 때 휴대폰 미디어 음량을 이 크기로 맞춰요.")

                    // ---------- 자동 꺼짐 ----------
                    SectionDivider()
                    SectionLabel("자동 꺼짐")
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(
                            listOf(30 to "30분", 60 to "60분", 90 to "90분"),
                            listOf(AUTO_OFF_PROGRAM_END to "방송 끝나면", 0 to "안 함"),
                        ).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (v, text) ->
                                    FilterChip(
                                        selected = autoOff == v,
                                        onClick = { autoOff = v },
                                        label = { Text(text) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            enabled = true,
                            hour = hour ?: 0,
                            minute = minute ?: 0,
                            days = days,
                            channelId = chId,
                            volume = volume.roundToInt(),
                            autoOff = autoOff,
                            label = label,
                        )
                    )
                }
            ) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("삭제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 두 자리 숫자 입력 칸 (시 또는 분) */
@Composable
private fun TimeNumberField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(2)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(68.dp)
    )
}

private val scheduleHm = SimpleDateFormat("HH:mm", Locale.KOREA)
private val scheduleMd = SimpleDateFormat("M/d", Locale.KOREA)

/** "오늘 07:00" / "내일 07:00" / "10/2 07:00" */
private fun dayTimeLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    val diff = (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)) -
            (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR))
    val day = when (diff) {
        0 -> "오늘"
        1 -> "내일"
        else -> scheduleMd.format(Date(t))
    }
    return "$day ${scheduleHm.format(Date(t))}"
}
private val nowFmt = SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA)
private fun nowLabel(): String = nowFmt.format(Date())
private val nextFmt = SimpleDateFormat("M/d(E) a h:mm", Locale.KOREA)

/** "오늘 10/1(목) 오후 8:14" / "내일 ..." / "10/3(토) ..." */
private fun nextTriggerLabel(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = Calendar.getInstance()
    val diff = (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)) -
            (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR))
    val prefix = when (diff) {
        0 -> "오늘 "
        1 -> "내일 "
        else -> ""
    }
    return prefix + nextFmt.format(java.util.Date(t))
}
private val IconBackSchedule =
    svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
private val IconDeleteSchedule = svgIcon(
    "delete",
    "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"
)