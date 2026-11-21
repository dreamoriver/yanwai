package dev.jev.wechatmood.voice

/** Identity only. No audio bytes, host objects or raw voice payload enter prompts. */
data class VoiceSource(val talker: String, val id: Long, val time: Long, val sent: Int,
    val fileToken: String = "", val serverId: Long = 0) {
    val key: String get() = listOf(talker, id.toString(), time.toString(), sent.toString(), fileToken, serverId.toString())
        .joinToString("") { "${it.length}:$it" }
    override fun toString() = "VoiceSource(id=$id)"
}

/** SKIPPED 是「这次没轮到转写」，不是失败：两者必须分开，否则提示词里的缺失数量会翻倍。 */
enum class VoiceState { NONE, WAITING, READY, FAILED, SKIPPED }

object VoiceText {
    const val WAITING = "【语音，等待转写】"
    const val FAILED = "【语音未能转写，内容未知】"
    /** 本次预算用尽、没有调用转写。内容未知，但微信里可能仍有转写文本。 */
    const val SKIPPED = "【语音，未转写】"
}
