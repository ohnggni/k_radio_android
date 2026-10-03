package kr.ohnggni.kradio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest

/**
 * 채널 로고를 content:// 주소로 제공 (안드로이드 오토·알림·워치용).
 * 오토는 인터넷 주소 그림을 직접 불러오지 않고, 같은 주소면 다시 그리지 않음.
 *
 * - 내장 로고(assets/logos 폴더의 png): content://<패키지>.logos/<파일명>
 * - 인터넷 로고(직접 추가·수정한 채널): content://<패키지>.logos/r/<주소 해시>
 *   한 번 내려받아 폰에 저장해두고 계속 그 파일을 제공 (로고 주소를 바꾸면 새 주소로 다시 받음)
 * 읽기 전용, 위 두 형식만 허용.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("읽기 전용")
        val ctx = context ?: throw FileNotFoundException("context 없음")
        val seg = uri.pathSegments
        val file = when {
            seg.size == 2 && seg[0] == REMOTE && HASH_RE.matches(seg[1]) -> remoteFile(ctx, seg[1])
            seg.size == 1 && NAME_RE.matches(seg[0]) -> cachedFile(ctx, seg[0])
            else -> throw FileNotFoundException(uri.toString())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 인터넷 로고: 저장본이 없으면 그 자리에서 내려받음 (오토가 먼저 찾는 경우) */
    private fun remoteFile(ctx: Context, hash: String): File {
        val f = File(remoteDir(ctx), hash)
        if (f.exists()) return f
        val url = runCatching { File(remoteDir(ctx), "$hash.url").readText() }.getOrNull()
            ?: throw FileNotFoundException(hash)
        if (!download(url, f)) throw FileNotFoundException(url)
        return f
    }

    /** assets 파일은 직접 열 수 없어서 캐시 폴더에 한 번 복사 (앱 업데이트마다 새 폴더) */
    @Synchronized
    private fun cachedFile(ctx: Context, name: String): File {
        val stamp = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val root = File(ctx.cacheDir, "logos")
        val dir = File(root, stamp.toString())
        if (!dir.exists()) {
            root.listFiles()?.forEach { it.deleteRecursively() }   // 이전 버전 로고 정리
            dir.mkdirs()
        }
        val file = File(dir, name)
        if (!file.exists()) {
            val tmp = File(dir, "$name.tmp")
            try {
                ctx.assets.open("logos/$name").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw FileNotFoundException("$name: ${e.message}")
            }
            tmp.renameTo(file)
        }
        return file
    }

    override fun getType(uri: Uri): String {
        val seg = uri.pathSegments
        if (seg.size == 2 && seg[0] == REMOTE) {
            val ctx = context ?: return "image/png"
            val url = runCatching { File(remoteDir(ctx), "${seg[1]}.url").readText() }.getOrNull()
            return url?.let { URLConnection.guessContentTypeFromName(Uri.parse(it).path) } ?: "image/png"
        }
        return "image/png"
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private val NAME_RE = Regex("[A-Za-z0-9_.-]+\\.png")
        private val HASH_RE = Regex("[0-9a-f]{40}")
        private const val REMOTE = "r"
        private const val MAX_BYTES = 3 * 1024 * 1024

        private fun remoteDir(c: Context) = File(c.filesDir, "logo_remote").apply { mkdirs() }

        private fun hashOf(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun isRemote(logo: String) = logo.startsWith("http://") || logo.startsWith("https://")

        /** 채널 로고 설정값 → 외부에서 열 수 있는 content:// 주소 */
        fun uriFor(context: Context, logo: String?): Uri? {
            if (logo.isNullOrBlank()) return null
            if (isRemote(logo)) {
                val hash = hashOf(logo)
                // 제공자가 해시로 원래 주소를 찾을 수 있게 짝을 남겨둠 (없을 때만, 아주 작은 파일)
                val map = File(remoteDir(context), "$hash.url")
                if (!map.exists()) runCatching { map.writeText(logo) }
                return Uri.parse("content://${context.packageName}.logos/$REMOTE/$hash")
            }
            if (!logo.startsWith(LogoCache.ASSET_PREFIX)) return null
            val name = logo.removePrefix(LogoCache.ASSET_PREFIX).substringAfterLast('/')
            if (!NAME_RE.matches(name)) return null
            return Uri.parse("content://${context.packageName}.logos/$name")
        }

        /** 인터넷 로고 저장본 (없으면 null, 내려받지 않음 → 메인 스레드에서 불러도 됨) */
        fun remoteBytes(context: Context, url: String): ByteArray? {
            if (!isRemote(url)) return null
            val f = File(remoteDir(context), hashOf(url))
            return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
        }

        /** 인터넷 로고를 미리 내려받아 둠 (저장본이 없을 때만). 네트워크 작업이라 백그라운드에서 부를 것 */
        fun prefetch(context: Context, url: String): Boolean {
            if (!isRemote(url)) return false
            uriFor(context, url)   // 주소 짝 기록
            val f = File(remoteDir(context), hashOf(url))
            if (f.exists()) return false
            return download(url, f)
        }

        @Synchronized
        private fun download(url: String, dest: File): Boolean {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            return try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KRadio")
                }
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) error("너무 큼")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                tmp.renameTo(dest)
                Log.i("KRadio", "인터넷 로고 저장: $url")
                true
            } catch (e: Exception) {
                tmp.delete()
                Log.w("KRadio", "인터넷 로고 받기 실패: $url (${e.message})")
                false
            }
        }
    }
}
