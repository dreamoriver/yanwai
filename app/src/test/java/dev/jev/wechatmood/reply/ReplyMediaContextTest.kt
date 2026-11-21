package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyMediaContextTest {
    private val settings = ReplySettings.fromInput("https://example.com", "key", "test-model")
    private fun link(id: Long = 1) = MessageMetadata(49, 0,
        "<msg><appmsg><title>一篇文章</title><des>这是摘要</des><type>5</type></appmsg></msg>", "friend", id, id * 1000)
    private fun forward(items: Int, id: Long = 5): MessageMetadata {
        // 发送者交替：同一个人连续说话会被 ForwardDigest 压缩成一条，测不到 40 条上限。
        val rows = (1..items).joinToString("") {
            val who = if (it % 2 == 0) "甲" else "乙"
            """<dataitem datatype="1"><datadesc>第${it}条</datadesc><sourcename>$who</sourcename></dataitem>"""
        }
        val xml = """<msg><appmsg><title>聊天记录</title><type>19</type><recorditem><![CDATA[""" +
            """<recordinfo><title>群聊的聊天记录</title><datalist count="$items">$rows</datalist></recordinfo>]]>""" +
            """</recorditem></appmsg></msg>"""
        return MessageMetadata(49, 0, xml, "friend", id, id * 1000)
    }

    @Test fun `link card keeps its title instead of an unparsed placeholder`() {
        val context = ReplyContext.collect("friend", listOf(link()))
        assertFalse(context.messages.toString().contains("未能解析"))
        assertTrue(context.messages.single().text.contains("一篇文章"))
        // 链接不算省略的媒体：它已经以卡片摘要进入上下文。
        assertEquals(0, context.omittedMedia)
    }

    @Test fun `forwarded chat record discloses how many entries were dropped`() {
        val context = ReplyContext.collect("friend", listOf(forward(60)), 100)
        val text = context.messages.single().text
        assertTrue(text, text.contains("共 60 条"))
        assertTrue(text, text.contains("只取前 40 条"))
    }

    @Test fun `media placeholders keep their place but never crowd out real text`() {
        val records = (1L..9L).map { MessageMetadata(3, 0, "private-$it", "friend", it, it * 1000) }
        val context = ReplyContext.collect("friend", records, 6)
        // 6 条里媒体最多占三分之一，保留的是最近的 2 条。
        assertEquals(2, context.messages.size)
        assertTrue(context.messages.all { it.placeholder })
        assertEquals("【图片】", context.messages.last().text)
        assertTrue(context.trimmed)
        assertFalse(context.messages.toString().contains("private-"))
        // 被上限丢掉的 7 条仍如实计入省略。
        assertEquals(7, context.omittedMedia)
    }

    @Test fun `focus text survives when the long pressed message is outside the window`() {
        val context = ReplyContext("friend", listOf(ReplyMessage(2, "对方", 2000, "在吗")),
            focusMessageId = 99, focusText = "上次说的那件事")
        val evidence = ReplyProtocol.evidence(context, "", "", "", focusMessageId = 99)
        val target = evidence.getJSONObject("reply_target")
        assertEquals("text", target.getString("type"))
        assertTrue(target.getString("text").contains("上次说的那件事"))
        assertEquals(1, evidence.getJSONArray("messages").length())
    }

    @Test fun `image payload and hint follow whether the picture was actually attached`() {
        val base = ReplyContext("friend", listOf(ReplyMessage(1, "对方", 1000, "看我拍的")), focusMessageId = 1)
        val attached = base.copy(targetMedia = "图片", targetImageDataUri = "data:image/jpeg;base64,AAAA")
        val withImage = ReplyProtocol.payload(settings, attached, "", "", "")
        val system = withImage.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("已随消息附上"))
        val parts = withImage.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        val evidence = JSONObject(parts.getJSONObject(0).getString("text"))
        assertTrue(evidence.getBoolean("media_included"))

        val missing = ReplyProtocol.payload(settings, base.copy(targetMedia = "图片"), "", "", "")
        val missingSystem = missing.getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(missingSystem.contains("已随消息附上"))
        assertTrue(missingSystem.contains("没取到内容"))
        // 没图时不能再把多模态结构发给模型，否则又变成一次必然失败的请求。
        assertTrue(missing.getJSONArray("messages").getJSONObject(1).get("content") is String)
    }

    @Test fun `emoji placeholder is described as an emoji not as a picture`() {
        val context = ReplyContext.collect("friend", listOf(MessageMetadata(47, 0, "<msg/>", "friend", 7, 7000)))
        assertEquals("【表情】", context.messages.single().text)
        assertTrue(context.messages.single().placeholder)
    }
}
