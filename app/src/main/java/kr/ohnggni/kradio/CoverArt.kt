package kr.ohnggni.kradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * "지금 재생 중" 정보(알림·잠금화면·오토·워치)에 넣을 프로그램 표지.
 * 원본(최대 1MB급)을 그대로 넣으면 세션 통신 한도에 걸릴 수 있어 긴 변 512px JPEG로 줄여 저장.
 * 준비된 것만 쓰고(없으면 채널 로고), 준비는 백그라운드에서. 실패는 조용히 (30분 뒤 다시 시도)
 */
object CoverArt {
    private const val MAX_SIDE = 512
    private const val MAX_DOWNLOAD = 3 * 1024 * 1024
    private const val KEEP_FILES = 80
    private const val RETRY_MS = 30 * 60 * 1000L

    private val memory = ConcurrentHashMap<String, ByteArray>()   // 최근 몇 개만
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "cover_art").apply { mkdirs() }
    private fun file(ctx: Context, url: String) = File(dir(ctx), sha1(url) + ".jpg")

    /** 줄인 표지가 준비됐는지 (파일만 확인, 메인 스레드 가능) */
    fun isReady(ctx: Context, url: String): Boolean = memory.containsKey(url) || file(ctx, url).exists()

    /** 줄인 표지 데이터 (준비 안 됐으면 null) */
    fun bytes(ctx: Context, url: String): ByteArray? {
        memory[url]?.let { return it }
        val f = file(ctx, url)
        if (!f.exists()) return null
        val b = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (memory.size >= 4) memory.clear()
        memory[url] = b
        return b
    }

    /** 이미 준비됐거나 받는 중·최근 실패면 false, 아니면 받아서 줄여 저장하고 성공 여부 */
    suspend fun prepare(ctx: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        if (isReady(ctx, url)) return@withContext false
        val failed = failedAt[url]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return@withContext false
        if (!inFlight.add(url)) return@withContext false
        try {
            val raw = download(url)
            val bmp = decodeScaled(raw) ?: error("그림 해석 실패")
            val out = file(ctx, url)
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            tmp.renameTo(out)
            trim(ctx)
            Log.i("KRadio", "표지 그림 준비: ${out.length() / 1024}KB")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[url] = System.currentTimeMillis()
            Log.w("KRadio", "표지 그림 준비 실패: ${url.take(60)} - ${e.message}")
            false
        } finally {
            inFlight.remove(url)
        }
    }

    private fun download(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KRadio")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    buf.write(b, 0, n)
                    if (buf.size() > MAX_DOWNLOAD) error("너무 큼")
                }
                buf.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 긴 변이 MAX_SIDE 이하가 되도록 (먼저 2의 배수로 줄여 읽고, 남는 건 정확히 축소) */
    private fun decodeScaled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = maxOf(bmp.width, bmp.height)
        if (side <= MAX_SIDE) return bmp
        val k = MAX_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** 오래된 것부터 지워 KEEP_FILES개만 유지 */
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
