package kr.ohnggni.kradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 안드로이드 오토 연결 여부.
 * 오토 앱이 제공하는 연결 상태(content://androidx.car.app.connection, 0 = 미연결)를 조회한다.
 * 주의: 조회하면 꺼져 있던 오토 앱이 통째로 깨어나서(수 초) 위젯·앱이 느려진다.
 * 그래서 오토가 이미 깨어 있을 때(연결·해제 신호, 오토가 재생 서비스에 접속할 때)만 조회하고,
 * 결과는 재생 서비스가 저장해두고 위젯·앱 화면은 저장값(isConnected)만 읽는다.
 * 매니페스트에 <queries><provider android:authorities="androidx.car.app.connection" /></queries> 필요.
 */
object CarLink {
    const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    const val GEARHEAD = "com.google.android.projection.gearhead"
    const val KEY = "car_connected"   // ChannelPrefs.PREFS에 저장되는 마지막 확인값

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    /** 저장된 연결 상태 (오토에 묻지 않음, 즉시) */
    fun isConnected(c: Context): Boolean = sp(c).getBoolean(KEY, false)

    /** 재생 서비스만 씀 */
    fun store(c: Context, on: Boolean) {
        if (isConnected(c) != on) sp(c).edit().putBoolean(KEY, on).apply()
    }
    private val URI: Uri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()

    /** 지금 오토에 연결돼 있는지 (다른 앱 호출이라 메인 스레드에서 부르지 말 것) */
    fun query(c: Context): Boolean = runCatching {
        c.contentResolver.query(URI, arrayOf("CarConnectionState"), null, null, null)?.use { cur ->
            val i = cur.getColumnIndex("CarConnectionState")
            i >= 0 && cur.moveToNext() && cur.getInt(i) != 0
        } ?: false
    }.getOrDefault(false)

    /** 연결·해제 신호마다 조회해서 onChange 호출 (메인 스레드). 신호를 보낸 오토는 깨어 있으니 조회해도 빠름 */
    fun watch(c: Context, scope: CoroutineScope, onChange: (Boolean) -> Unit): BroadcastReceiver {
        val check = {
            scope.launch {
                val on = withContext(Dispatchers.IO) { query(c) }
                onChange(on)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                check()
            }
        }
        ContextCompat.registerReceiver(
            c, receiver, IntentFilter(ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED
        )
        return receiver
    }
}
