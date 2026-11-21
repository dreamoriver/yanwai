package dev.jev.wechatmood.reply

import android.content.Context
import org.json.JSONObject

/**
 * 长按图片/表情时的“一句话描述”缓存（方案 A：只有用户主动用它回复时才生成）。
 *
 * 模型看一眼图，写一句能确定的内容，按「聊天 + 消息 id」存下来；同一张图再次
 * 长按直接复用，不重复调用、不重复计费。描述失败不影响正常回复：拿不到就退回
 * 「没取到内容」的措辞，让模型按上下文判断。
 */
object ImageDigest {
    private const val PREFS = "yanwai_image_digest"
    private const val MAX_ENTRIES = 300
    private const val MAX_CHARS = 120

    /** 只留一句能进提示词的话：去换行、压空白、限长。纯函数，单测直接用。 */
    /**
     * 模型说「看不见/看不清/没有收到图片」时，那不是描述。这类话既不能显示在目标行，
     * 也不能塞进提示词，更不能写进缓存——否则同一张图以后每次都读到这句废话。
     */
    private val REJECT_PREFIX = listOf("抱歉", "我无法", "我不能", "没有收到", "未收到",
        "没有图片", "无法描述", "无法查看", "看不到图", "图片没有", "无法识别")

    fun usable(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.length < 2) return false
        if (REJECT_PREFIX.any { value.startsWith(it) }) return false
        // 「看不清」单独成句说明没信息量；长句里的「字太小看不清」仍是有用描述。
        if (value.length <= 12 && value.contains("看不清")) return false
        return true
    }

    fun normalize(raw: String?): String? {
        val clean = raw.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('：', ':', '"', '“', '”', '「', '」', '《', '》', '。', '.', ' ')
        return clean.take(MAX_CHARS).ifBlank { null }
    }

    fun cached(context: Context, talker: String, messageId: Long): String? {
        if (messageId <= 0) return null
        return runCatching {
            val name = key(talker, messageId)
            val raw = prefs(context).getString(name, null) ?: return null
            val text = JSONObject(raw).optString("text")
            if (!usable(text)) {
                // 早先版本把「没有收到图片」这类话存进来了，读到就顺手清掉。
                prefs(context).edit().remove(name).apply()
                return null
            }
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun store(context: Context, talker: String, messageId: Long, text: String): String? {
        val clean = normalize(text)?.takeIf(::usable) ?: return null
        if (messageId <= 0) return clean
        runCatching {
            val prefs = prefs(context)
            val value = JSONObject().put("text", clean).put("at", System.currentTimeMillis()).toString()
            val editor = prefs.edit().putString(key(talker, messageId), value)
            // 旧版本可能存过「看不清」这类无效描述，写新条目时顺手清掉。
            prefs.all.forEach { (name, raw) ->
                val value = runCatching { JSONObject(raw as String).optString("text") }.getOrNull()
                if (value != null && !usable(value)) editor.remove(name)
            }
            // 超上限时按写入时间丢掉最旧的几条，缓存不能无限长。
            val all = prefs.all
            if (all.size >= MAX_ENTRIES) {
                all.entries.mapNotNull { (name, raw) ->
                    val at = runCatching { JSONObject(raw as String).optLong("at") }.getOrDefault(0L)
                    name to at
                }.sortedBy { it.second }.take(all.size - MAX_ENTRIES + 1).forEach { editor.remove(it.first) }
            }
            editor.apply()
        }
        return clean
    }

    /** 缓存命中直接用；否则请求一次视觉模型并写回。任何失败都返回 null，不阻塞生成。 */
    suspend fun describe(context: Context, settings: ReplySettings, talker: String, messageId: Long,
        dataUri: String, onProgress: (() -> Unit)? = null): String? {
        cached(context, talker, messageId)?.let { return it }
        onProgress?.invoke()
        val text = try {
            ReplyHttpClient().describeImage(settings, dataUri)
        } catch (e: Exception) {
            // 只记类型和错误文本，不记图片内容，便于在 mood.log 里定位到底是服务拒绝还是解析失败。
            dev.jev.wechatmood.core.MoodLog.w(
                "IMAGE_DIGEST_FAILED ${e.javaClass.simpleName}: ${(e.message.orEmpty()).take(80)}")
            return null
        }
        val saved = store(context, talker, messageId, text)
        dev.jev.wechatmood.core.MoodLog.i("IMAGE_DIGEST_OK len=${text.length} saved=${saved != null}")
        return saved
    }

    private fun key(talker: String, messageId: Long) = "d:${talker.hashCode()}:$messageId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
