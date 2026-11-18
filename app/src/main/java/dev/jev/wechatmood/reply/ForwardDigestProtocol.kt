package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 合并转发的摘要请求：只要一段纯文本要点，不强制 JSON，兼容性最好。 */
object ForwardDigestProtocol {
    fun payload(settings: ReplySettings, message: ForwardDigest.Message, stream: Boolean = true): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions))
            .put(JSONObject().put("role", "user").put("content", message.transcript()))
        val request = JSONObject().put("model", settings.model).put("stream", stream)
        if (stream) request.put("stream_options", JSONObject().put("include_usage", true))
        return request.put("max_tokens", 900).put("temperature", 0.3).put("messages", messages)
    }

    fun parse(body: String): String {
        val root = JSONObject(body)
        check(!root.has("error")) { root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "模型返回错误" } }
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason")
        check(finish == "stop") { "摘要被截断（finish_reason=$finish），请重试或换输出上限更高的模型" }
        val content = choice.getJSONObject("message").getString("content").trim()
        check(content.isNotBlank()) { "模型没有返回正文，换一个非推理模型试试" }
        return content
    }

    private val instructions = """
        你帮我整理一条微信「合并转发」的聊天记录。

        要求：
        - 用两三句话把这段记录讲清楚：谁和谁在聊什么、聊到哪一步、有没有结论。像跟人转述一样，一段话写下来。
        - 不要逐条复述每条消息，不要编号列表，不要「要点：」「总结：」这类小标题。
        - 人名、时间、金额、数量、地点、约定这些具体信息要原样保留，聊天风格和口气可以概括。
        - 如果还有没定下来的事，最后用一句话点出来，不要强行编结论。
        - 记录里写成 [图片] [语音] [聊天记录] 的只当占位，不要猜内容；没出现的信息不要补。
        - 只输出这段转述，纯文本，不要 Markdown。
    """.trimIndent()
}
