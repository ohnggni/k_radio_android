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
 * 원본(최대 1MB급)을 그대로 넣으면 세션 통신 한도에 걸릴 수 있어 512×512 JPEG로 줄여 저장.
 * 원본 비율이 제각각이라(가로 배너형 등) 오토가 직사각형으로 그리지 않게 가운데 기준 정사각으로 자름
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
    // "_sq": 정사각 자르기 도입 전(비율 그대로) 저장본과 구분 → 옛 파일은 안 쓰고 다시 만듦
    private fun file(ctx: Context, url: String) = File(dir(ctx), sha1(url) + "_sq.jpg")

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

    /** 가운데 기준 정사각으로 잘라 MAX_SIDE×MAX_SIDE 이하로 (먼저 2의 배수로 줄여 읽어 메모리 절약) */
    private fun decodeScaled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        if (square !== bmp) bmp.recycle()
        if (side <= MAX_SIDE) return square
        val scaled = Bitmap.createScaledBitmap(square, MAX_SIDE, MAX_SIDE, true)
        if (scaled !== square) square.recycle()
        return scaled
    }

    /** 오래된 것부터 지워 KEEP_FILES개만 유지 (정사각 이전 옛 저장본은 바로 지움) */
    private fun trim(ctx: Context) {
        val all = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") } ?: return
        all.filter { !it.name.endsWith("_sq.jpg") }.forEach { it.delete() }
        all.filter { it.name.endsWith("_sq.jpg") }.sortedByDescending { it.lastModified() }
            .drop(KEEP_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
