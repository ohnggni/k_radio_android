package kr.ohnggni.kradio

import android.content.Context

/** 데이터 출처 설정 (기본값은 화면에 주소를 노출하지 않음) */
object SourceSettings {
    internal const val DEFAULT_CONFIG_URL =
        "https://raw.githubusercontent.com/ohnggni/k_radio_android/main/channels.json"
    internal const val DEFAULT_EPG_URL =
        "https://raw.githubusercontent.com/ohnggni/k_radio_android/epg/xmltv.xml"

    internal const val DEFAULT_UPDATE_URL =
        "https://drive.google.com/drive/folders/1U7HPF74C_B9WWrs1177V78zh0L0NzzrR?usp=sharing"
    const val KEY_CONFIG = "source_config_url"
    const val KEY_EPG = "source_epg_url"

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    /** 직접 지정한 주소 (null = 기본값 사용) */
    fun customConfigUrl(c: Context): String? = sp(c).getString(KEY_CONFIG, null)
    fun customEpgUrl(c: Context): String? = sp(c).getString(KEY_EPG, null)

    fun setCustomConfigUrl(c: Context, url: String?) = set(c, KEY_CONFIG, url)
    fun setCustomEpgUrl(c: Context, url: String?) = set(c, KEY_EPG, url)

    private fun set(c: Context, key: String, url: String?) {
        sp(c).edit().apply {
            if (url.isNullOrBlank()) remove(key) else putString(key, url.trim())
        }.apply()
    }

    /** 실제로 쓸 채널 설정 주소 */
    fun configUrl(c: Context): String = customConfigUrl(c) ?: DEFAULT_CONFIG_URL

    /** 실제로 쓸 EPG 주소: 직접 지정 → 채널 설정 파일의 epgUrl → 기본값 */
    fun epgUrl(c: Context): String =
        customEpgUrl(c) ?: ChannelRepository.epgUrl ?: DEFAULT_EPG_URL

    /** 업데이트 다운로드 주소: 설정 파일의 updateUrl → 기본 드라이브 폴더 */
    fun updateUrl(): String = ChannelRepository.updateUrl ?: DEFAULT_UPDATE_URL
}