package dev.jev.wechatmood.reply

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageDigestTest {
    private val settings = ReplySettings.fromInput("https://example.com", "key", "test-model")
    private fun envelope(text: String) =
        """{"choices":[{"finish_reason":"stop","message":{"content":${JSONObject.quote(text)}}}]}"""

    @Test fun `description is cleaned into one line and bounded`() {
        assertEquals("一只橘猫 趴在键盘上", ImageDigest.normalize("「一只橘猫\n趴在键盘上」"))
        assertEquals("看不清", ImageDigest.normalize("  看不清。 "))
        assertNull(ImageDigest.normalize("   "))
        assertEquals(120, requireNotNull(ImageDigest.normalize("字".repeat(500))).length)
    }

    @Test fun `rejection text is not treated as a description`() {
        assertFalse(ImageDigest.usable("没有收到图片，无法描述，看不清"))
        assertFalse(ImageDigest.usable("看不清"))
        assertFalse(ImageDigest.usable("抱歉，我不能查看图片"))
        assertTrue(ImageDigest.usable("一张信用卡签约页，写着授信额度"))
        // 长句里的「看不清」仍是有用信息，不能一句否掉。
        assertTrue(ImageDigest.usable("字太小看不清，只能看到蓝色卡片"))
    }

    @Test fun `digest parse falls back to reasoning content when content is empty`() {
        // 推理模型常把正文放在 reasoning_content，content 为空；取最后一行当描述。
        val body = """{"choices":[{"finish_reason":"stop","message":{"content":"",""" +
            """"reasoning_content":"用户要我描述这张图。\n一张信用卡签约页，写着授信额度"}}]}"""
        assertEquals("一张信用卡签约页，写着授信额度", ReplyProtocol.parseDigest(body))
    }

    @Test fun `digest parse rejects a refusal even when http succeeded`() {
        assertThrows(IllegalStateException::class.java) {
            ReplyProtocol.parseDigest(envelope("没有收到图片，无法描述"))
        }
    }

    @Test fun `digest payload attaches the image and does not stream`() {
        val payload = ReplyProtocol.digestPayload(settings, "data:image/jpeg;base64,AAA")
        assertFalse(payload.getBoolean("stream"))
        val parts = payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAA", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun `digest response is parsed but empty output fails closed`() {
        assertEquals("一只猫", ReplyProtocol.parseDigest(envelope("一只猫")))
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parseDigest(envelope("   ")) }
    }

    @Test fun `reply hint carries the cached description only when the image is attached`() {
        val base = ReplyContext("friend", listOf(ReplyMessage(1, "对方", 1000, "看这个")), focusMessageId = 1,
            targetMedia = "图片", targetImageDataUri = "data:image/jpeg;base64,AAA", targetImageDigest = "一只橘猫")
        val attached = ReplyProtocol.payload(settings, base, "", "", "")
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(attached, attached.contains("一只橘猫"))
        assertTrue(attached, attached.contains("已随消息附上"))

        // 没带图时描述不该出现，否则模型会以为自己看过图。
        val without = ReplyProtocol.payload(settings,
            base.copy(targetImageDataUri = null, targetImageDigest = null), "", "", "")
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(without.contains("一只橘猫"))
        assertTrue(without.contains("没取到内容"))
    }
}
