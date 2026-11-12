package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ResultParser
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ResultCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the batch of fixes in v1.0.25.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class V125FixesTest {

    // ---------------------------------------------------------------- item 3

    @Test
    fun cardWidthDefaultsToTwentyFiveCharacters() {
        // item 8 of the v1.0.26 batch changed the default from 10 to 25.
        assertEquals(25, Config().cardWidth)
        assertEquals(25, Config().panelWidthChars)
    }

    @Test
    fun cardWidthIsClampedToOneMinimum() {
        // The width now sizes the floating panel; an unset panel width falls back to cardWidthChars.
        assertEquals(1, Config(panelWidthChars = 0, cardWidthChars = 0).cardWidth)
        assertEquals(1, Config(panelWidthChars = -5, cardWidthChars = -5).cardWidth)
        assertEquals(1, Config(panelWidthChars = 1).cardWidth)
        // A panel width wins over the legacy field.
        assertEquals(30, Config(panelWidthChars = 30, cardWidthChars = 0).cardWidth)
    }

    @Test
    fun cardWidthHasNoUpperLimit() {
        assertEquals(999, Config(panelWidthChars = 999).cardWidth)
        assertEquals(100000, Config(panelWidthChars = 100000).cardWidth)
    }

    // ---------------------------------------------------------------- item 4

    @Test
    fun parserReadsPerOptionReply() {
        val json = """
            {
              "intent": {"question": "test?", "options": [
                {"label": "yes", "probability": 70},
                {"label": "no", "probability": 30}
              ]},
              "real_intent": [
                {"label": "wants attention", "probability": 60, "reply": "I am here for you"},
                {"label": "is upset", "probability": 40, "reply": "sorry, let us talk"}
              ],
              "anger_level": 6
            }
        """.trimIndent()

        val r = ResultParser.parse(json)
        assertTrue("should not fall back", !r.isFallback)
        assertEquals(2, r.realIntent.size)
        assertEquals("I am here for you", r.realIntent[0].reply)
        assertEquals("sorry, let us talk", r.realIntent[1].reply)
        // intent options carry no reply in this payload
        assertNull(r.intentOptions[0].reply)
        assertEquals(6, r.angerLevel)
    }

    @Test
    fun parserAcceptsReplyAliases() {
        val json = """
            {
              "real_intent": [
                {"label": "a", "probability": 50, "suggestion": "alt alias"},
                {"label": "b", "probability": 50, "answer": "another alias"}
              ],
              "anger_level": 1
            }
        """.trimIndent()
        val r = ResultParser.parse(json)
        assertEquals("alt alias", r.realIntent[0].reply)
        assertEquals("another alias", r.realIntent[1].reply)
    }

    @Test
    fun parserSurvivesMissingReply() {
        val json = """
            {"real_intent":[{"label":"x","probability":100}],"anger_level":0}
        """.trimIndent()
        val r = ResultParser.parse(json)
        assertTrue(!r.isFallback)
        assertNull(r.realIntent[0].reply)
    }

    // ---------------------------------------------------------------- item 6

    @Test
    fun cacheKeysAreIsolatedPerConversation() {
        // The same message id in two different chats must produce different cache keys,
        // otherwise analysing chat A could replay a result belonging to chat B.
        val a = ResultCache.keyOf("alice", 100L, 5L, "fp", "m", "p")
        val b = ResultCache.keyOf("bob", 100L, 5L, "fp", "m", "p")
        assertNotEquals(a, b)
    }

    @Test
    fun cacheKeyIsStableForIdenticalInputs() {
        val a = ResultCache.keyOf("alice", 100L, 5L, "fp", "m", "p")
        val b = ResultCache.keyOf("alice", 100L, 5L, "fp", "m", "p")
        assertEquals(a, b)
    }

    @Test
    fun cacheKeyChangesWhenScopeChanges() {
        val a = ResultCache.keyOf("alice", 100L, 5L, "fp1", "m", "p")
        val b = ResultCache.keyOf("alice", 100L, 5L, "fp2", "m", "p")
        assertNotEquals(a, b)
    }

    // ---------------------------------------------------------------- item 7

    @Test
    fun clearAllEmptiesTheCache() {
        ResultCache.clearAll()
        assertEquals(0, ResultCache.size())
    }

    @Test
    fun cacheRoundTripsReply() {
        val key = ResultCache.keyOf("alice", 1L, 2L, "fp", "m", "p")
        assertNotNull(key)
        // Storing requires no directory to be attached; this must not throw.
        ResultCache.put(
            key,
            com.luoliaoo.wechat.data.AnalysisResult(
                question = "q",
                intentOptions = listOf(com.luoliaoo.wechat.data.AnalysisResult.Option("a", 50)),
                realIntent = listOf(
                    com.luoliaoo.wechat.data.AnalysisResult.Option("b", 50, "suggested sentence"),
                ),
                angerLevel = 3,
                comment = "c",
            ),
        )
        val got = ResultCache.get(key)
        assertNotNull(got)
        assertEquals("suggested sentence", got!!.realIntent[0].reply)
        ResultCache.clearAll()
    }
}
