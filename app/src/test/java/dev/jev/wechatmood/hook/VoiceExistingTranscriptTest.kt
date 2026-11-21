package dev.jev.wechatmood.hook

import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyMessage
import dev.jev.wechatmood.reply.ReplyProtocol
import dev.jev.wechatmood.voice.VoicePreparation
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.voice.VoiceState
import dev.jev.wechatmood.voice.VoiceText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceExistingTranscriptTest {
    private fun source(id: Long) = VoiceSource("friend", id, id * 1000, 0, "file-$id", id + 100)
    private fun context(ids: LongRange) = ReplyContext("friend",
        ids.map { ReplyMessage(it, "对方", it * 1000, VoiceText.WAITING, source(it)) })

    @Test fun `voices already transcribed by wechat do not spend the native budget`() = runBlocking {
        var calls = 0
        val prepared = VoicePreparation.reply(context(1L..6L), existing = { if (it.id <= 4L) "已有${it.id}" else null }) {
            calls++
            "转写${it.id}"
        }
        // 4 条读库里已有，剩下 2 条用掉额度即可全部就绪；原生转写只该被调用两次。
        assertEquals(6, prepared.messages.count { it.voiceState == VoiceState.READY })
        assertEquals(2, calls)
        assertEquals("已有1", prepared.messages.first { it.id == 1L }.text)
    }

    @Test fun `skipped voice is not reported as a failed transcription`() = runBlocking {
        val prepared = VoicePreparation.reply(context(1L..5L)) { "转写${it.id}" }
        assertEquals(3, prepared.messages.count { it.voiceState == VoiceState.READY })
        assertEquals(2, prepared.messages.count { it.voiceState == VoiceState.SKIPPED })
        assertEquals(0, prepared.messages.count { it.voiceState == VoiceState.FAILED })
        val evidence = ReplyProtocol.evidence(prepared, "", "")
        // 跳过只是没轮到，不该出现在「无法转写」的计数里。
        assertEquals(0, evidence.getInt("unavailable_voice"))
        assertEquals(3, evidence.getInt("voice_transcripts"))
    }

    @Test fun `existing lookup failure falls back to native transcription`() = runBlocking {
        val prepared = VoicePreparation.reply(context(1L..2L), existing = { error("db busy") }) { "转写${it.id}" }
        assertEquals(2, prepared.messages.count { it.voiceState == VoiceState.READY })
    }

    /** 进度分母只能是真正要调原生转写的条数：已有转写不占额度，超过 3 条的也不转。 */
    @Test fun `plan reports only voices that will be transcribed`() = runBlocking {
        var planned = -1
        VoicePreparation.reply(context(1L..6L),
            existing = { if (it.id <= 4L) "已有${it.id}" else null },
            onPlan = { planned = it }) { "转写${it.id}" }
        assertEquals(2, planned)
    }

    @Test fun `plan is zero when every voice already has text`() = runBlocking {
        var planned = -1
        var calls = 0
        VoicePreparation.reply(context(1L..10L), existing = { "已有${it.id}" }, onPlan = { planned = it }) {
            calls++
            "不该调用"
        }
        assertEquals(0, planned)
        assertEquals(0, calls)
    }
}
