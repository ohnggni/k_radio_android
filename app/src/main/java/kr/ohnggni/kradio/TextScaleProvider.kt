package kr.ohnggni.kradio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 앱 안 글자 크기 적용. FOLLOW면 폰 글꼴 크기 그대로, 아니면 폰 설정과 상관없이 고정 배율.
 * 글자(sp)만 바뀌고 버튼·여백(dp)은 그대로.
 */
@Composable
fun AppTextScale(scale: Int, content: @Composable () -> Unit) {
    if (scale == TextScale.FOLLOW) {
        content()
        return
    }
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = scale / 100f),
        content = content
    )
}
