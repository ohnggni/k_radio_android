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


/** 시작 시 재생 설정 (앱을 새로 켤 때 + 블루투스 재생 버튼) */
object StartupSettings {
    const val MODE_NONE = "none"
    const val MODE_LAST = "last"
    const val MODE_FIXED = "fixed"

    /** 정지 후 이 시간이 지나 앱을 다시 열면 새로 켠 것으로 보고 자동 재생 */
    const val AUTO_START_IDLE_MS = 10 * 60 * 1000L

    private const val KEY_MODE = "startup_mode"
    private const val KEY_CHANNEL = "startup_channel"

    private fun sp(c: Context) = c.getSharedPreferences(ChannelPrefs.PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): String = sp(c).getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
    fun fixedChannel(c: Context): String? = sp(c).getString(KEY_CHANNEL, null)

    fun save(c: Context, mode: String, channelId: String?) {
        sp(c).edit().apply {
            putString(KEY_MODE, mode)
            if (channelId == null) remove(KEY_CHANNEL) else putString(KEY_CHANNEL, channelId)
        }.apply()
    }

    private fun lastVisible(c: Context, visible: List<Channel>): String? =
        sp(c).getString("last_channel", null)?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id

    /** 지정 채널이 숨김·삭제됐으면 null */
    private fun fixedVisible(c: Context, visible: List<Channel>): String? =
        fixedChannel(c)?.takeIf { id -> visible.any { it.id == id } }

    /** 앱을 새로 켤 때 재생할 채널 (null = 재생 안 함) */
    fun launchChannelId(c: Context, visible: List<Channel>): String? = when (mode(c)) {
        MODE_LAST -> lastVisible(c, visible)
        MODE_FIXED -> fixedVisible(c, visible) ?: lastVisible(c, visible)
        else -> null
    }

    /** 블루투스 재생 버튼(이어 듣기): 지정 채널이면 그 채널, 아니면 마지막 채널 */
    fun resumeChannelId(c: Context, visible: List<Channel>): String? =
        (if (mode(c) == MODE_FIXED) fixedVisible(c, visible) else null) ?: lastVisible(c, visible)
}