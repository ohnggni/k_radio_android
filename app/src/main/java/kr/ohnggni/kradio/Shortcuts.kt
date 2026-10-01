package kr.ohnggni.kradio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** 앱 아이콘 꾹 누르기: 즐겨찾기 채널 바로가기 */
object Shortcuts {
    const val ACTION_PLAY = "kr.ohnggni.kradio.PLAY"
    const val EXTRA_CHANNEL = "channel_id"

    /** 즐겨찾기가 바뀌거나 채널 이름·로고가 바뀔 때 호출 */
    fun update(c: Context, visible: List<Channel>, favorites: List<String>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(c)
                .coerceAtMost(ChannelPrefs.MAX_FAVORITES)
            val list = visible
                .filter { it.id in favorites }   // 채널 관리 순서, 숨긴 채널 제외
                .take(max)
                .mapIndexed { i, ch ->
                    val intent = Intent(c, MainActivity::class.java)
                        .setAction(ACTION_PLAY)
                        .putExtra(EXTRA_CHANNEL, ch.id)
                    ShortcutInfoCompat.Builder(c, "fav_${ch.id}")
                        .setShortLabel(ch.name.take(12))
                        .setLongLabel(ch.name)
                        .setIcon(iconFor(c, ch))
                        .setIntent(intent)
                        .setRank(i)
                        .build()
                }
            ShortcutManagerCompat.setDynamicShortcuts(c, list)
            Log.i("KRadio", "바로가기 갱신: ${list.size}개")
        }.onFailure { Log.w("KRadio", "바로가기 갱신 실패: ${it.message}") }
    }

    /** 채널 로고를 아이콘 모양(동그라미 등)으로 잘려도 안전한 가운데 영역에, 흰 배경 위로 배치 */
    private fun iconFor(c: Context, ch: Channel): IconCompat {
        val logo = ch.logo?.takeIf { it.startsWith(LogoCache.ASSET_PREFIX) }?.let { path ->
            runCatching {
                c.assets.open(path.removePrefix(LogoCache.ASSET_PREFIX)).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        } ?: return IconCompat.createWithResource(c, R.mipmap.ic_launcher)

        val size = 216
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val inner = size * 60 / 108            // 적응형 아이콘 안전 영역 안쪽
        val left = (size - inner) / 2f
        canvas.drawBitmap(
            logo, null,
            RectF(left, left, left + inner, left + inner),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return IconCompat.createWithAdaptiveBitmap(out)
    }
}