package kr.ohnggni.kradio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 시·분 입력 + 위/아래 버튼.
 * 시는 1시간씩, 분은 5분 단위로 이동 (넘어가면 시도 같이), 꾹 누르면 연속.
 */
@Composable
fun TimeStepper(hText: String, mText: String, onChange: (String, String) -> Unit) {
    fun step(dHour: Int, dMin: Int) {
        var h = hText.toIntOrNull()?.coerceIn(0, 23) ?: 0
        var m = mText.toIntOrNull()?.coerceIn(0, 59) ?: 0
        if (dMin != 0) {
            var total = h * 60 + m
            total = if (dMin > 0) (total / 5 + 1) * 5 else ((total + 4) / 5 - 1) * 5
            total = ((total % 1440) + 1440) % 1440
            h = total / 60
            m = total % 60
        } else {
            h = ((h + dHour) % 24 + 24) % 24
        }
        onChange("%02d".format(h), "%02d".format(m))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperColumn(
            value = hText,
            onValue = { onChange(it, mText) },
            onUp = { step(1, 0) },
            onDown = { step(-1, 0) }
        )
        Text(" : ", style = MaterialTheme.typography.titleLarge)
        StepperColumn(
            value = mText,
            onValue = { onChange(hText, it) },
            onUp = { step(0, 1) },
            onDown = { step(0, -1) }
        )
    }
}

@Composable
private fun StepperColumn(
    value: String,
    onValue: (String) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatButton(IconUp, "올리기", onUp)
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(it.filter(Char::isDigit).take(2)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(68.dp)
        )
        RepeatButton(IconDown, "내리기", onDown)
    }
}

/** 한 번 누르면 한 칸, 꾹 누르고 있으면 0.4초 뒤부터 연속 */
@Composable
private fun RepeatButton(icon: ImageVector, desc: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                val gestures = this
                coroutineScope {
                    val scope = this
                    gestures.awaitEachGesture {
                        awaitFirstDown()
                        step()
                        val repeater = scope.launch {
                            delay(400)
                            while (isActive) {
                                step()
                                delay(90)
                            }
                        }
                        waitForUpOrCancellation()
                        repeater.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

private val IconUp = svgIcon("up", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")
private val IconDown = svgIcon("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")