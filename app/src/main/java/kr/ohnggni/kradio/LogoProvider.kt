package kr.ohnggni.kradio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 앱에 내장된 채널 로고(assets/logos 폴더의 png)를 content:// 주소로 제공.
 * 오토는 같은 주소면 그림을 다시 그리지 않아서, 이미지 데이터를 넘길 때의 깜빡임이 없어짐.
 * 읽기 전용, logos 폴더의 png 파일 이름만 허용.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("읽기 전용")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("이름 없음")
        if (!NAME_RE.matches(name)) throw FileNotFoundException(name)
        val ctx = context ?: throw FileNotFoundException("context 없음")
        val file = cachedFile(ctx, name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private val NAME_RE = Regex("[A-Za-z0-9_.-]+\\.png")

        /** 채널 로고 설정값 → 외부에서 열 수 있는 주소 (내장: content://, 인터넷: 그대로) */
        fun uriFor(context: Context, logo: String?): Uri? {
            if (logo.isNullOrBlank()) return null
            if (logo.startsWith("http")) return Uri.parse(logo)
            if (!logo.startsWith(LogoCache.ASSET_PREFIX)) return null
            val name = logo.removePrefix(LogoCache.ASSET_PREFIX).substringAfterLast('/')
            if (!NAME_RE.matches(name)) return null
            return Uri.parse("content://${context.packageName}.logos/$name")
        }
    }
}
