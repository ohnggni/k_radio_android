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
 * 오토 앱이 제공하는 연결 상태(content://androidx.car.app.connection, 0 = 미연결)를 조회하고,
 * 오토가 연결·해제 때 보내는 신호를 받으면 다시 조회한다. (androidx.car.app의 CarConnection과 같은 방식)
 * 매니페스트에 <queries><provider android:authorities="androidx.car.app.connection" /></queries> 필요.
 */
object CarLink {
    const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    private val URI: Uri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()

    /** 지금 오토에 연결돼 있는지 (다른 앱 호출이라 메인 스레드에서 부르지 말 것) */
    fun query(c: Context): Boolean = runCatching {
        c.contentResolver.query(URI, arrayOf("CarConnectionState"), null, null, null)?.use { cur ->
            val i = cur.getColumnIndex("CarConnectionState")
            i >= 0 && cur.moveToNext() && cur.getInt(i) != 0
        } ?: false
    }.getOrDefault(false)

    /** 연결·해제 신호마다 다시 조회해서 onChange 호출 (메인 스레드). 등록 직후에도 한 번 조회 */
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
        check()
        return receiver
    }
}
