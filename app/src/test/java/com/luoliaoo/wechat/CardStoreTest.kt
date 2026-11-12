package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.data.AnalysisResult
import com.luoliaoo.wechat.data.CardStore
import com.luoliaoo.wechat.data.ChatTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for card persistence (item 7) and follow-up turns (item 9).
 *
 * The in-memory path is exercised here; the on-disk path needs an Android Context, which the
 * unit test runner does not provide, so serialisation is tested through the JSON helpers.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class CardStoreTest {

    private fun result() = AnalysisResult(
        question = "is she upset?",
        intentOptions = listOf(AnalysisResult.Option("yes", 70), AnalysisResult.Option("no", 30)),
        realIntent = listOf(
            AnalysisResult.Option("wants comfort", 55, "of course I care about you"),
            AnalysisResult.Option("wants a fight", 45, "let us talk tomorrow"),
        ),
        angerLevel = 6,
        comment = "she is waiting for you to notice",
    )

    @Test
    fun resultSurvivesJsonRoundTrip() {
        val json = CardStore.resultToJson(result())
        val back = CardStore.resultFromJson(json)
        assertEquals("is she upset?", back.question)
        assertEquals(2, back.intentOptions.size)
        assertEquals(70, back.intentOptions[0].probability)
        assertEquals(2, back.realIntent.size)
        // item 2: the suggested reply must survive persistence.
        assertEquals("of course I care about you", back.realIntent[0].reply)
        assertEquals(6, back.angerLevel)
        assertEquals("she is waiting for you to notice", back.comment)
    }

    @Test
    fun fallbackResultSurvivesRoundTrip() {
        val f = AnalysisResult.fallback("raw model text")
        val back = CardStore.resultFromJson(CardStore.resultToJson(f))
        assertTrue(back.isFallback)
        assertEquals("raw model text", back.rawFallback)
    }

    @Test
    fun missingReplyParsesAsNull() {
        val o = JSONObject()
            .put("label", "x")
            .put("p", 10)
        val arr = org.json.JSONArray().put(o)
        val root = JSONObject().put("real", arr)
        val r = CardStore.resultFromJson(root)
        assertNull(r.realIntent[0].reply)
    }

    @Test
    fun savedCardHoldsScopeAndTurns() {
        val turns = listOf(
            ChatTurn(fromMe = true, text = "why is she angry?", time = 1L),
            ChatTurn(fromMe = false, text = "she feels ignored", time = 2L),
        )
        val s = CardStore.Saved(
            key = "alice#100",
            talker = "alice",
            msgId = 100L,
            mode = ScopeMode.COUNT,
            up = 3,
            down = 3,
            startMs = 10L,
            endMs = 20L,
            result = result(),
            error = null,
            turns = turns,
        )
        assertEquals("alice#100", s.key)
        assertEquals(ScopeMode.COUNT, s.mode)
        assertEquals(2, s.turns.size)
        assertTrue(s.turns[0].fromMe)
        assertTrue(!s.turns[1].fromMe)
    }

    @Test
    fun storeIsKeyedPerConversation() {
        // The same msgId in two chats must be two distinct cards (item 6 of the earlier batch).
        CardStore.put(saved("alice#7", "alice", 7L, "alice result"))
        CardStore.put(saved("bob#7", "bob", 7L, "bob result"))
        assertEquals("alice result", CardStore.get("alice#7")?.result?.comment)
        assertEquals("bob result", CardStore.get("bob#7")?.result?.comment)
    }

    @Test
    fun removeDropsOnlyThatCard() {
        CardStore.put(saved("a#1", "a", 1L, "one"))
        CardStore.put(saved("a#2", "a", 2L, "two"))
        CardStore.remove("a#1")
        assertNull(CardStore.get("a#1"))
        assertNotNull(CardStore.get("a#2"))
    }

    @Test
    fun clearAllEmptiesTheStore() {
        CardStore.put(saved("x#1", "x", 1L, "x"))
        CardStore.clearAll()
        assertEquals(0, CardStore.size())
    }

    @Test
    fun chatTurnDefaultsToNow() {
        val t = ChatTurn(fromMe = true, text = "hi")
        assertTrue(t.time > 0)
    }

    private fun saved(key: String, talker: String, msgId: Long, comment: String) =
        CardStore.Saved(
            key = key,
            talker = talker,
            msgId = msgId,
            mode = ScopeMode.COUNT,
            up = 3,
            down = 3,
            startMs = 0L,
            endMs = 0L,
            result = result().copy(comment = comment),
            error = null,
            turns = emptyList(),
        )
}
