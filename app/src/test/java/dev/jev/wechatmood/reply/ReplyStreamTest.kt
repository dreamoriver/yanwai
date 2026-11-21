package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 流式回复：第一个候选在整段 JSON 写完之前就要能被取出来显示。 */
class ReplyStreamTest {
    @Test fun `image rejection retries the same text evidence without streaming`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400))
            val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("finish_reason", "stop")
                .put("message", JSONObject().put("content", """{"replies":["好的"],"reason":""}""")))).toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val settings = ReplySettings.fromInput(server.url("/v1").toString(), "fake", "model")
            val image = "data:image/jpeg;base64,AA=="
            val context = ReplyContext("chat", listOf(ReplyMessage(1, "对方", 1, "看看这个")),
                targetMedia = "图片", targetImageDataUri = image)
            val reply = ReplyHttpClient().generate(settings, context, "", "", "")

            assertEquals("好的", reply.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            val retry = JSONObject(server.takeRequest().body.readUtf8())
            // 用户不要逐条蹦出，请求也不再走流式。
            assertEquals(false, first.getBoolean("stream"))
            assertEquals(false, retry.getBoolean("stream"))
            assertFalse(first.has("stream_options"))
            val parts = first.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals(image, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals(parts.getJSONObject(0).getString("text"),
                retry.getJSONArray("messages").getJSONObject(1).getString("content"))
        }
    }

    @Test fun `complete first candidate is extracted from a partial payload`() {
        assertEquals("最推荐的候选", ReplyHttpClient.firstReply("{\"replies\":[\"最推荐的候选\""))
    }

    @Test fun `incomplete string yields nothing`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"还没写完"))
        assertNull(ReplyHttpClient.firstReply("{\"reason\":\"没有候选\""))
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[]"))
    }

    @Test fun `escaped quotes and unicode survive`() {
        assertEquals("他说\"好\"", ReplyHttpClient.firstReply("{\"replies\":[\"他说\\\"好\\\"\",\"第二\"]}"))
        assertEquals("好", ReplyHttpClient.firstReply("{\"replies\":[\"\\u597d\"],\"reason\":\"x\"}"))
    }

    @Test fun `blank candidate is ignored`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"   \""))
    }
}
