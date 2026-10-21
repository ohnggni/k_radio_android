package kr.ohnggni.kradio

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 기본 채널 설정 암호문 풀기.
 * 형식: "KR1:" + base64(nonce 12바이트 + 암호문 + 태그 16바이트), AES-256-GCM.
 * 평문(직접 지정한 주소의 channels.json)은 그대로 돌려줌.
 * 키는 빌드할 때 비공개 secrets.properties에서 두 조각으로 들어옴(build.gradle.kts).
 */
object ConfigCrypto {
    private const val MAGIC = "KR1:"
    private val AAD = "KRadio-config-v1".toByteArray()

    fun isEncrypted(text: String): Boolean = text.trimStart().startsWith(MAGIC)

    /** 암호문이면 풀어서, 아니면 그대로. 키가 안 맞거나 내용이 바뀌었으면 예외 */
    fun open(text: String): String {
        if (!isEncrypted(text)) return text
        val raw = Base64.decode(text.trim().substring(MAGIC.length), Base64.DEFAULT)
        require(raw.size > 12 + 16) { "암호문이 너무 짧음" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, raw, 0, 12))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private fun key(): ByteArray {
        val a = Base64.decode(BuildConfig.CK_A, Base64.DEFAULT)
        val b = Base64.decode(BuildConfig.CK_B, Base64.DEFAULT)
        return ByteArray(32) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }
}
