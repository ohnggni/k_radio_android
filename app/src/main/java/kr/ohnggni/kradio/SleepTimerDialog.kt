package kr.ohnggni.kradio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val sleepHm = SimpleDateFormat("HH:mm", Locale.KOREA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    sleepAt: Long?,
    programs: List<Program>,          // 현재 채널의 이어지는 방송 (최대 4개)
    onSetAt: (Long) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickTime by remember { mutableStateOf(false) }

    // ---------- 시각 지정 화면 ----------
    if (pickTime) {
        // 처음 값: 지금부터 30분 뒤를 5분 단위로 올림
        val init = remember {
            val c = Calendar.getInstance().apply { add(Calendar.MINUTE, 30) }
            val total = ((c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + 4) / 5 * 5) % 1440
            "%02d".format(total / 60) to "%02d".format(total % 60)
        }
        var hText by remember { mutableStateOf(init.first) }
        var mText by remember { mutableStateOf(init.second) }
        val h = hText.toIntOrNull()?.takeIf { it in 0..23 }
        val m = mText.toIntOrNull()?.takeIf { it in 0..59 }

        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("꺼질 시각") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeStepper(hText, mText) { nh, nm -> hText = nh; mText = nm }
                    Text(
                        "이미 지난 시각이면 다음 날로 설정돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = h != null && m != null,
                    onClick = { onSetAt(nextTimeAt(h!!, m!!)) }
                ) { Text("설정") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("취소") } }
        )
        return
    }

    // ---------- 기본 화면 ----------
    // 선택 버튼 공통 모양: 기본보다 낮고 글자도 한 단계 작게
    val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    val buttonMod = Modifier.height(38.dp)
    val buttonText = MaterialTheme.typography.bodyMedium

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("꺼짐 예약") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (sleepAt != null) {
                    Text(
                        "${sleepHm.format(Date(sleepAt))}에 꺼질 예정",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text("시간 후 꺼짐", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45).forEach { m ->
                        OutlinedButton(
                            onClick = { onSetAt(System.currentTimeMillis() + m * 60_000L) },
                            modifier = buttonMod.weight(1f),
                            contentPadding = compactPadding
                        ) { Text("${m}분", style = buttonText) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 90).forEach { m ->
                        OutlinedButton(
                            onClick = { onSetAt(System.currentTimeMillis() + m * 60_000L) },
                            modifier = buttonMod.weight(1f),
                            contentPadding = compactPadding
                        ) { Text("${m}분", style = buttonText) }
                    }
                    OutlinedButton(
                        onClick = { pickTime = true },
                        modifier = buttonMod.weight(1f),
                        contentPadding = compactPadding
                    ) { Text("지정..", style = buttonText, maxLines = 1) }
                }

                if (programs.isNotEmpty()) {
                    Text(
                        "방송 끝나면 꺼짐",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    programs.forEach { p ->
                        OutlinedButton(
                            onClick = { onSetAt(p.stop) },
                            modifier = buttonMod.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            // 제목은 길면 끝을 생략, 부제·종료 시각은 항상 표시
                            Text(
                                p.title,
                                style = buttonText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                listOfNotNull(
                                    p.subTitle?.takeIf { it.isNotBlank() },
                                    sleepHm.format(Date(p.stop))
                                ).joinToString(" · "),
                                style = buttonText,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                Text(
                    "정한 시각부터 10초 동안 소리가 서서히 줄어든 뒤 꺼져요. 직접 정지하면 예약도 취소돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            if (sleepAt != null) TextButton(onClick = onCancelTimer) { Text("예약 취소") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 시·분을 다음에 오는 그 시각으로 (이미 지났으면 내일) */
private fun nextTimeAt(hour: Int, minute: Int): Long {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DATE, 1)
    return c.timeInMillis
}