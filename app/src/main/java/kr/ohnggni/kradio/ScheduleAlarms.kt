package kr.ohnggni.kradio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Date
import android.content.ComponentName
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlin.math.roundToInt
import android.os.Bundle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

/** 켜짐 예약을 안드로이드 시스템에 등록/취소 */
object ScheduleAlarms {
    const val ACTION_FIRE = "kr.ohnggni.kradio.SCHEDULE_FIRE"
    const val EXTRA_ID = "schedule_id"

    private fun pending(c: Context, id: String, create: Boolean): PendingIntent? {
        val intent = Intent(c, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
                if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(c, id.hashCode(), intent, flags)
    }

    /** 켜진 예약은 다음 회차를 등록, 꺼진 예약은 취소 */
    fun rescheduleAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        PlaySchedules.read(c).forEach { s ->
            if (!s.enabled) {
                cancel(c, s.id)
                return@forEach
            }
            val at = s.nextTrigger()
            val pi = pending(c, s.id, create = true) ?: return@forEach
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // 정확도는 떨어지지만 동작은 함
            }
            Log.i("KRadio", "켜짐 예약 등록: ${s.timeLabel()} ${s.daysLabel()} → ${Date(at)}${if (exact) "" else " (비정확)"}")
        }
    }

    fun cancel(c: Context, id: String) {
        pending(c, id, create = false)?.let {
            c.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    /** 실행 후: 한 번짜리는 끄고, 반복은 다음 회차 등록 */
    fun afterFired(c: Context, id: String) {
        val list = PlaySchedules.read(c)
        val s = list.firstOrNull { it.id == id } ?: return
        if (s.days.isEmpty()) {
            PlaySchedules.write(c, list.map { if (it.id == id) it.copy(enabled = false) else it })
        }
        rescheduleAll(c)
    }
}

/** 예약 시각 도착, 재부팅·앱 업데이트·시간 변경 신호를 받음 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduleAlarms.ACTION_FIRE -> {
                val id = intent.getStringExtra(ScheduleAlarms.EXTRA_ID) ?: return
                Log.i("KRadio", "켜짐 예약 실행: $id")
                val s = PlaySchedules.read(context).firstOrNull { it.id == id }
                ScheduleAlarms.afterFired(context, id)   // 한 번짜리 끄기 + 다음 회차 등록
                if (s == null) return
                val pending = goAsync()                  // 재생 요청이 끝날 때까지 잠시 유지
                ScheduleStarter.start(context.applicationContext, s) { pending.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> ScheduleAlarms.rescheduleAll(context)
        }
    }
}
/** 백그라운드에서 채널 하나 틀기 (켜짐 예약·위젯 즐겨찾기 공용) */
object ScheduleStarter {

    /** 켜짐 예약: 예약 음량으로 맞추고 서서히 커지며 시작 */
    fun start(c: Context, s: PlaySchedule, done: () -> Unit) = playChannel(
        c, s.channelId, s.label, s.autoOff, volume = s.volume, fade = true, source = "켜짐 예약", done = done
    )

    fun playChannel(
        c: Context,
        channelId: String,
        label: String?,
        autoOff: Int,
        volume: Int?,          // null이면 폰 음량 그대로
        fade: Boolean,
        source: String,
        onSent: () -> Unit = {},   // 재생 명령을 보낸 직후 (위젯: 여기서 버튼 처리를 끝내야 다음 버튼이 안 밀림)
        done: () -> Unit,          // 연결 정리까지 끝난 뒤 (8초 후)
    ) {
        // 채널 확인: 숨기거나 삭제한 채널이면 엉뚱한 채널을 틀지 않고 알림만
        val base = ChannelRepository.loadCached(c)
        val channelPrefs = ChannelPrefs.read(c)
        val all = base?.let { ChannelPrefs.applyOrder(it, channelPrefs) }
        val visible = base?.let { ChannelPrefs.visible(it, channelPrefs) }
        val name = all?.firstOrNull { it.id == channelId }?.name ?: label ?: "예약한 채널"
        if (visible != null && visible.none { it.id == channelId }) {
            Log.w("KRadio", "$source 건너뜀: $name (숨김 또는 삭제)")
            ScheduleNotifier.show(
                c, 2003,
                "$source: 채널을 찾을 수 없어요",
                "$name 채널이 숨김 또는 삭제된 상태라 켜지 않았어요."
            )
            done()
            return
        }

        // 앱 음량이 음소거(0%)면 100%로
        if (AppVolume.get(c) == 0) AppVolume.set(c, 100)

        // 서비스에 '백그라운드에서 켜는 중' 표시 (포커스 보류, 음량, 서서히 커지기, 자동 꺼짐, 실패 알림)
        ScheduledStart.set(c, name, autoOff, volume ?: -1, fade)

        val future = MediaController.Builder(
            c, SessionToken(c, ComponentName(c, PlaybackService::class.java))
        )
            .setConnectionHints(Bundle().apply { putBoolean("scheduled", true) })
            .buildAsync()
        future.addListener({
            runCatching {
                val ctl = future.get()
                ctl.setMediaItem(MediaItem.Builder().setMediaId(channelId).build())
                ctl.prepare()
                ctl.play()
                Log.i("KRadio", "$source 재생 요청: $name")
            }.onFailure { Log.e("KRadio", "$source 재생 요청 실패", it) }
            onSent()
            // 서비스가 재생을 시작할 시간을 준 뒤 연결 해제
            Handler(Looper.getMainLooper()).postDelayed({
                MediaController.releaseFuture(future)
                done()
            }, 8_000)
        }, ContextCompat.getMainExecutor(c))
    }
}
/** 켜짐 예약 관련 알림 */
object ScheduleNotifier {
    fun show(
        c: Context,
        id: Int,
        title: String,
        text: String,
        channel: String = "schedule",
        channelName: String = "켜짐 예약",
    ) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            c, id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))   // 긴 안내도 펼쳐서 보이게
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}