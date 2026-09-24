package kr.ohnggni.kradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 기본 채널에 덧씌우는 수정 내용 (null = 원래 값 사용) */
data class ChannelOverride(
    val name: String? = null,
    val url: String? = null,   // 넣으면 이 주소로 고정 재생
    val logo: String? = null,
) {
    fun isEmpty() = name == null && url == null && logo == null
}

/** 사용자가 정한 채널 설정 (폰에만 저장) */
data class ChannelPrefsData(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val custom: List<Channel> = emptyList(),
    val overrides: Map<String, ChannelOverride> = emptyMap(),
)

object ChannelPrefs {
    const val PREFS = "kradio"
    const val KEY = "channel_prefs"
    const val CUSTOM_GROUP = "내 채널"

    fun read(context: Context): ChannelPrefsData {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return ChannelPrefsData()
        return runCatching {
            val o = JSONObject(text)
            fun strList(key: String) = o.optJSONArray(key)
                ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

            val custom = o.optJSONArray("custom")?.let { a ->
                (0 until a.length()).map { i ->
                    val c = a.getJSONObject(i)
                    Channel(
                        id = c.getString("id"),
                        name = c.getString("name"),
                        group = CUSTOM_GROUP,
                        type = "direct",
                        url = c.getString("url"),
                        logo = c.optString("logo").ifEmpty { null },
                    )
                }
            } ?: emptyList()

            val overrides = o.optJSONObject("overrides")?.let { ov ->
                ov.keys().asSequence().associateWith { id ->
                    val x = ov.getJSONObject(id)
                    ChannelOverride(
                        name = x.optString("name").ifEmpty { null },
                        url = x.optString("url").ifEmpty { null },
                        logo = x.optString("logo").ifEmpty { null },
                    )
                }
            } ?: emptyMap()

            ChannelPrefsData(strList("order"), strList("hidden").toSet(), custom, overrides)
        }.getOrElse { ChannelPrefsData() }
    }

    fun write(context: Context, data: ChannelPrefsData) {
        val o = JSONObject()
        o.put("order", JSONArray(data.order))
        o.put("hidden", JSONArray(data.hidden.toList()))
        o.put("custom", JSONArray().apply {
            data.custom.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("url", c.url)
                    c.logo?.let { put("logo", it) }
                })
            }
        })
        o.put("overrides", JSONObject().apply {
            data.overrides.forEach { (id, x) ->
                put(id, JSONObject().apply {
                    x.name?.let { put("name", it) }
                    x.url?.let { put("url", it) }
                    x.logo?.let { put("logo", it) }
                })
            }
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    /** 수정 내용 덧씌우기. 주소를 넣으면 방송사 API 대신 그 주소로 고정 재생 */
    private fun applyOverride(ch: Channel, o: ChannelOverride?): Channel {
        if (o == null) return ch
        var r = ch.copy(name = o.name ?: ch.name, logo = o.logo ?: ch.logo)
        if (o.url != null) {
            r = r.copy(type = "direct", url = o.url, api = null, extractJson = null, extractRegex = null)
        }
        return r
    }

    /** 기본 채널(수정 반영) + 내 채널을 사용자 순서로 정렬 (숨김 포함). 새 채널은 맨 끝에 */
    fun applyOrder(base: List<Channel>, p: ChannelPrefsData): List<Channel> {
        val all = base.map { applyOverride(it, p.overrides[it.id]) } + p.custom
        val byId = all.associateBy { it.id }
        val ordered = p.order.mapNotNull { byId[it] }
        val rest = all.filter { it.id !in p.order }
        return ordered + rest
    }

    /** 메인 목록·재생 순서용 (숨김 제외) */
    fun visible(base: List<Channel>, p: ChannelPrefsData): List<Channel> =
        applyOrder(base, p).filter { it.id !in p.hidden }

    fun newCustomId(): String = "custom_${System.currentTimeMillis()}"
}