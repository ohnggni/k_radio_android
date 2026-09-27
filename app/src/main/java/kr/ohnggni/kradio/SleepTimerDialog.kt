package kr.ohnggni.kradio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val sleepHm = SimpleDateFormat("HH:mm", Locale.KOREA)

@Composable
fun SleepTimerDialog(
    sleepAt: Long?,
    onSetAt: (Long) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("취침 타이머") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sleepAt != null) {
                    Text(
                        "${sleepHm.format(Date(sleepAt))}에 종료 예정",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text("시간 후 종료", style = MaterialTheme.typography.labelLarge)
                listOf(listOf(1, 30, 45), listOf(60, 90)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            OutlinedButton(
                                onClick = { onSetAt(System.currentTimeMillis() + m * 60_000L) },
                                modifier = Modifier.weight(1f)
                            ) { Text("${m}분") }
                        }
                        if (row.size < 3) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    "종료 전 10초 동안 소리가 서서히 줄어들어요. 직접 정지하면 타이머도 꺼져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (sleepAt != null) TextButton(onClick = onCancelTimer) { Text("타이머 끄기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}