package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import java.security.MessageDigest
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceState
import dev.jev.wechatmood.voice.VoiceText

data class ReplyMessage(val id: Long, val speaker: String, val time: Long, val text: String,
    val voice: VoiceSource? = null, val voiceState: VoiceState = if (voice == null) VoiceState.NONE else VoiceState.WAITING,
    /** 群聊里发送者的 wxid / 微信号，和 speaker（显示名）配对，缺名字时用它去库里查。 */
    val senderId: String = "",
    /** 微信消息类型；统计要按它分图片/语音，页面消息拿不到时按普通文本算。 */
    val type: Int = 1,
    /** 只有类型、没有正文的占位（图片、表情等）。历史锚点核对时要跳过它，否则会误判成「结果比页面旧」。 */
    val placeholder: Boolean = false)
/** Identity check only. Media payloads stay out of the reply context and model input. */
data class ReplyHistoryAnchor(val id: Long, val type: Int, val sent: Int, val time: Long, val digest: String) {
    fun matches(record: MessageMetadata): Boolean = this == from(record)
    companion object {
        fun from(record: MessageMetadata) = ReplyHistoryAnchor(record.messageId, record.type, record.isSend, record.createdAt,
            MessageDigest.getInstance("SHA-256").digest(record.content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
    }
}
enum class ReplyContextSource { LOADED_PAGE, LOCAL_HISTORY }
data class ReplyContext(val talker: String, val messages: List<ReplyMessage>, val omittedMedia: Int = 0,
    val trimmed: Boolean = false, val latestLoadedId: Long = messages.lastOrNull()?.id ?: 0,
    val source: ReplyContextSource = ReplyContextSource.LOADED_PAGE, val historyFailure: String? = null,
    val requestedMessages: Int = ReplyHistoryLimit.DEFAULT, val historyAnchor: ReplyHistoryAnchor? = null,
    val background: ContactBackground = ContactBackground(), val focusMessageId: Long = 0,
    val targetMedia: String? = null, val targetImageDataUri: String? = null,
    /** 长按图片时由视觉模型生成的一句话描述，按消息缓存，只在这轮提示词里用。 */
    val targetImageDigest: String? = null, val focusText: String? = null,
    val facts: List<RelationFact> = emptyList(),
    /** 「我」最近的真实文字消息（从旧到新），只给口吻画像用；不含图片/表情/语音占位。 */
    val styleTexts: List<String> = emptyList()) {
    val fingerprint: String get() {
        val text = messages.joinToString("\u0000") { "${it.id}:${it.speaker.length}:${it.speaker}:${it.time}:${it.text.length}:${it.text}:${it.voice?.key.orEmpty()}:${it.voiceState}" }
        return MessageDigest.getInstance("SHA-256").digest("$talker:$latestLoadedId:${background.revision}:$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_MESSAGES = 100
        /** 单次请求的聊天正文上限：太长会撞小模型的上下文，也会拖慢响应。 */
        const val MAX_CHARACTERS = 12000
        /** 没有正文、只能以类型占位进入上下文的媒体消息。 */
        private val MEDIA_TYPES = setOf(3, 42, 43, 47, 48, 62)
        /** 媒体占位的可读名字；模型靠它知道对方发了什么，但读不到内容。 */
        internal fun mediaLabel(type: Int): String = when (type) {
            3 -> "图片"
            42 -> "名片"
            43 -> "视频"
            47 -> "表情"
            48 -> "位置"
            62 -> "小视频"
            else -> "类型$type"
        }
        /** 合并转发给模型的上限：条数与每条字数，超出后要明确告诉模型被截断。 */
        private const val FORWARD_ITEMS = 40
        private const val FORWARD_ITEM_CHARS = 150
        private const val FORWARD_BUDGET_CHARS = 4000
        private fun keyOf(record: MessageMetadata) =
            if (record.messageId > 0) "id:${record.messageId}" else "${record.createdAt}:${record.isSend}:${record.content.hashCode()}"

        fun collect(talker: String, records: List<MessageMetadata>, maxMessages: Int = MAX_MESSAGES): ReplyContext {
            require(maxMessages in 1..MAX_MESSAGES)
            val same = records.filter { it.talker == talker }.distinctBy { keyOf(it) }
            // 引用消息即使解析不出正文也要保留：丢掉它会让模型对着上下文里别的消息作答。
            // 超过 1000 字的单条消息只是分析功能的限制，回复上下文由下面的总预算裁剪，
            // 这里按内容是否可用筛选，不能因为正文超长就把整条消息丢掉。
            // 图片、表情这类没有正文的消息也要留一条占位：漏掉它们，模型就看不到
            // 「说了句话又连发三张图」这种节奏，只能对着一个省略数量瞎猜。
            val eligible = same.filter { it.isSend in 0..1 && when (it.type) {
                1 -> it.content.isNotBlank()
                34 -> it.voiceSource() != null
                in MEDIA_TYPES -> true
                49, 822083633 -> true
                else -> it.plainText() != null
            } }
            var budget = MAX_CHARACTERS
            var trimmed = eligible.size > maxMessages
            // 媒体占位不能把有正文的消息挤光：最多占所选条数的三分之一，多出来的按时间从早到晚丢弃。
            val mediaCap = (maxMessages / 3).coerceAtLeast(1)
            var mediaUsed = 0
            val text = eligible.takeLast(maxMessages).asReversed().filter { record ->
                if (record.type !in MEDIA_TYPES) return@filter true
                if (mediaUsed < mediaCap) { mediaUsed++; true } else { trimmed = true; false }
            }.asReversed()
            val mediaKeys = eligible.filter { it.type in MEDIA_TYPES }.mapTo(HashSet()) { keyOf(it) }
            val includedMedia = HashSet<String>()
            val selected = text.asReversed().mapNotNull { record ->
                if (budget <= 0) { trimmed = true; return@mapNotNull null }
                if (record.type in MEDIA_TYPES) includedMedia += keyOf(record)
                // 引用消息的 content 是 XML，取解析后的文本，别把标签塞给模型。
                val body = when {
                    record.type == 34 -> VoiceText.WAITING
                    record.type in MEDIA_TYPES -> "【${mediaLabel(record.type)}】"
                    record.type == 49 || record.type == 822083633 -> {
                        // 引用消息要把「被引用的原文」和「这次新写的话」一起给模型，
                        // 只留其中一边很容易答非所问；解析失败也要留占位，不能整条丢掉。
                        val own = record.plainText().orEmpty()
                        val quoted = record.quotedMessage()
                        val quotedText = quoted?.text?.takeIf { it.isNotBlank() }?.take(200)
                        // 合并转发：正文是 XML，回复时模型看不到，先把要点摊平给它。
                        val forwarded = if (own.isBlank() && quotedText == null && ForwardDigest.isForward(record.content))
                            ForwardDigest.parse(record.content) else null
                        when {
                            forwarded != null -> {
                                val shown = forwarded.items.take(FORWARD_ITEMS)
                                val more = if (forwarded.items.size > shown.size)
                                    "（共 ${forwarded.items.size} 条，只取前 ${shown.size} 条）" else ""
                                "【合并转发：${forwarded.title.take(40)}】$more\n" + shown.joinToString("\n") { item ->
                                    (if (item.sender.isBlank()) "" else item.sender + ": ") + item.text.take(FORWARD_ITEM_CHARS)
                                }.take(FORWARD_BUDGET_CHARS)
                            }
                            quotedText != null -> "【引用${quoted.displayName?.take(32).orEmpty()}：$quotedText】$own"
                            own.isNotBlank() -> own
                            // 链接、文件、小程序、红包这些卡片没有对话正文，但标题和描述
                            // 是模型判断「对方在说什么」的唯一依据，不能再写成「未能解析」。
                            else -> record.evidenceBody()?.take(200)?.let { "【${record.evidenceKind()}：$it】" }
                                ?: "【${record.evidenceKind()}】"
                        }
                    }
                    record.isSend == 0 && talker.endsWith("@chatroom") && record.content.contains(":\n") ->
                        record.content.substringAfter(":\n")
                    else -> record.content
                }
                val end = minOf(body.length, budget).let {
                    if (it < body.length && it > 0 && body[it - 1].isHighSurrogate() && body[it].isLowSurrogate()) it - 1 else it
                }
                val cut = body.take(end)
                if (cut.length != body.length) trimmed = true
                budget -= cut.length
                ReplyMessage(record.messageId, record.speaker(), record.createdAt, cut, record.voiceSource(),
                    senderId = record.senderId(), type = record.type, placeholder = record.type in MEDIA_TYPES)
            }.asReversed()
            // 只统计真正没进上下文的媒体：占位已经进去了，不该重复报成省略。
            val omitted = mediaKeys.count { it !in includedMedia }
            return ReplyContext(talker, selected, omitted, trimmed, same.lastOrNull()?.messageId ?: 0,
                requestedMessages = maxMessages,
                historyAnchor = same.lastOrNull { it.messageId > 0 && it.createdAt > 0 && it.isSend in 0..1 }
                    ?.let(ReplyHistoryAnchor::from))
        }
    }
}
