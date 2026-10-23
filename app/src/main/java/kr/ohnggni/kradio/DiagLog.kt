package kr.ohnggni.kradio

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 원인 확인용 기록 (폰 로그는 금방 밀려나서 따로 남김). 최근 MAX_LINES줄만 유지.
 * 위치: /sdcard/Android/data/<패키지>/files/diag.log
 *   가져오기: adb pull /sdcard/Android/data/kr.ohnggni.kradio/files/diag.log
 */
object DiagLog {
    private const val MAX_LINES = 200
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun time(ms: Long): String = if (ms <= 0) "없음" else synchronized(fmt) { fmt.format(Date(ms)) }

    @Synchronized
    fun write(c: Context, msg: String) {
        Log.i("KRadio", "[진단] $msg")
        runCatching {
            val dir = c.applicationContext.getExternalFilesDir(null) ?: return
            val f = File(dir, "diag.log")
            val lines = if (f.exists()) f.readLines() else emptyList()
            val keep = (lines + "${time(System.currentTimeMillis())} $msg").takeLast(MAX_LINES)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }
}
