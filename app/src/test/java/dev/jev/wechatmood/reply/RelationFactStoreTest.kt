package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.PersistentAnalysisCacheTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 关系事实的存储、迁移与时间语义。 */
class RelationFactStoreTest {
    private lateinit var file: java.io.File

    @Before fun setUp() {
        file = java.io.File.createTempFile("relation-facts", ".db").also { it.delete() }
    }

    private fun db() = PersistentAnalysisCacheTest.Jdbc(file)

    private fun body(content: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun `legacy single row migrates into items exactly once`() {
        val key = ReplyContactKey("a".repeat(64))
        val legacy = JSONArray()
            .put(JSONObject().put("kind", "工作").put("text", "在游戏公司做运营")
                .put("evidence_time", 100).put("updated_at", 100).put("confidence", 0.8))
            .put(JSONObject().put("kind", "约定").put("text", "周五一起吃饭"))
            .toString()
        val seed = db()
        seed.execute("CREATE TABLE relation_facts (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        seed.execute("INSERT INTO relation_facts(contact_key, payload) VALUES(?, ?)", listOf(key.value, legacy))

        val store = RelationFactStore(seed)
        val migrated = store.list(key)
        assertEquals(2, migrated.size)
        assertTrue(migrated.all { it.factId.matches(Regex("[0-9a-f]{16}")) })
        assertTrue(migrated.all { it.status == RelationFact.STATUS_ACTIVE })
        assertEquals(100, migrated.single { it.kind == "工作" }.evidenceTime)

        // 再开一次不会把同一批旧数据插第二遍。
        val reopened = RelationFactStore(db())
        assertEquals(2, reopened.list(key).size)
    }

    @Test fun `rescheduled promise supersedes the old entry instead of piling up`() {
        val key = ReplyContactKey("b".repeat(64))
        val store = RelationFactStore(db())
        val saturday = FactTimes.parseLocal("2026-10-24T19:00")
        val sunday = FactTimes.parseLocal("2026-10-25T19:00")
        store.replace(key, RelationFacts.merge(emptyList(),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = saturday)), 1000))
        val merged = RelationFacts.merge(store.listAll(key),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = sunday)), 2000)
        store.replace(key, merged)

        val open = store.list(key)
        assertEquals(1, open.size)
        assertEquals(sunday, open.single().occurredAt)
        assertEquals(1, store.listAll(key).count { it.status == RelationFact.STATUS_SUPERSEDED })
    }

    @Test fun `same day change stays one promise`() {
        val saturday = FactTimes.parseLocal("2026-10-24T19:00")
        val laterSameDay = FactTimes.parseLocal("2026-10-24T20:30")
        val merged = RelationFacts.merge(listOf(RelationFact("约定", "周六一起吃饭", occurredAt = saturday)),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = laterSameDay)), 2000)
        assertEquals(1, merged.count { it.isOpen })
        assertEquals(0, merged.count { it.status == RelationFact.STATUS_SUPERSEDED })
        assertEquals(laterSameDay, merged.single { it.isOpen }.occurredAt)
    }

    @Test fun `static facts keep merging by similarity`() {
        val merged = RelationFacts.merge(listOf(RelationFact("工作", "在游戏公司做运营", 100, 100)),
            listOf(RelationFact("工作", "在游戏公司做运营主管", 200, 200)), 300)
        assertEquals(1, merged.size)
        assertEquals(100, merged[0].evidenceTime)
    }

    @Test fun `occurred at only accepts explicit dates`() {
        assertTrue(FactTimes.parseLocal("2026-10-23T19:00") > 0)
        assertTrue(FactTimes.parseLocal("2026-10-23") > 0)
        assertEquals(0L, FactTimes.parseLocal(""))
        assertEquals(0L, FactTimes.parseLocal("过两天"))
        assertEquals(0L, FactTimes.parseLocal("下周三"))
    }

    @Test fun `extractor maps evidence index to message id and keeps the timestamp`() {
        val facts = FactExtractor().parse(
            body("""[{"kind":"约定","text":"周五一起吃饭","evidence":2,"occurred_at":"2026-10-23T19:00"}]"""),
            messageIdAt = { index -> if (index == 2) 4242L else 0L })
        val fact = facts.single()
        assertEquals(4242L, fact.sourceChatMessageId)
        assertTrue(fact.occurredAt > 0)
    }

    @Test fun `extractor drops unusable timestamps instead of guessing`() {
        val facts = FactExtractor().parse(body("""[{"kind":"约定","text":"改天一起吃饭","occurred_at":"改天"}]"""))
        assertEquals(1, facts.size)
        assertEquals(0L, facts.single().occurredAt)
    }

    @Test fun `clear removes everything`() {
        val key = ReplyContactKey("c".repeat(64))
        val store = RelationFactStore(db())
        store.replace(key, listOf(RelationFact("城市", "在杭州")))
        store.saveMeta(key, RelationMeta(1234, 99))
        store.clear(key)
        assertEquals(0, store.listAll(key).size)
        assertEquals(0L, store.meta(key).lastAt)

        store.replace(key, listOf(RelationFact("城市", "在杭州")))
        store.clearAll()
        assertEquals(0, store.listAll(key).size)
    }
}
