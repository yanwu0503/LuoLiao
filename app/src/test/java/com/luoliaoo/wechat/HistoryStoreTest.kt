package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local history store behind item 3.
 *
 * The user reported that the analysed window kept shifting: each analysis only saw the messages the
 * adapter happened to hold. Recording every read and merging it back makes the scope the union of all
 * history for that chat. These tests cover the merge semantics that the merge relies on.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class HistoryStoreTest {

    private fun line(id: Long, time: Long, text: String, me: Boolean = false) =
        HistoryStore.Line(msgId = id, time = time, isSend = me, text = text, speaker = "wxid_x")

    @Test
    fun emptyHistoryIsEmpty() {
        assertEquals(0, HistoryStore.count("nobody"))
        assertTrue(HistoryStore.lines("nobody").isEmpty())
    }

    @Test
    fun recordingThenReadingWorks() {
        HistoryStore.record("t1", listOf(line(1, 100, "a"), line(2, 200, "b")))
        val got = HistoryStore.lines("t1")
        assertEquals(2, got.size)
        // ordered by time
        assertEquals(100L, got[0].time)
        assertEquals(200L, got[1].time)
    }

    @Test
    fun duplicatesByMsgIdAreIgnored() {
        HistoryStore.record("t2", listOf(line(1, 100, "a")))
        HistoryStore.record("t2", listOf(line(1, 100, "a"), line(2, 200, "b")))
        assertEquals(2, HistoryStore.count("t2"))
    }

    @Test
    fun blankTextIsNotStored() {
        HistoryStore.record("t3", listOf(line(1, 100, ""), line(2, 200, "ok")))
        assertEquals(1, HistoryStore.count("t3"))
    }

    @Test
    fun zeroMsgIdIsNotStored() {
        HistoryStore.record("t4", listOf(line(0, 100, "no id")))
        assertEquals(0, HistoryStore.count("t4"))
    }

    @Test
    fun conversationsAreIsolated() {
        HistoryStore.record("alice", listOf(line(1, 100, "hi alice")))
        HistoryStore.record("bob", listOf(line(1, 100, "hi bob")))
        assertEquals("hi alice", HistoryStore.lines("alice")[0].text)
        assertEquals("hi bob", HistoryStore.lines("bob")[0].text)
    }

    @Test
    fun directionIsPreserved() {
        HistoryStore.record("t5", listOf(line(1, 100, "mine", me = true), line(2, 200, "theirs")))
        val got = HistoryStore.lines("t5")
        assertTrue(got[0].isSend)
        assertTrue(!got[1].isSend)
    }

    @Test
    fun clearAllEmptiesEveryConversation() {
        HistoryStore.record("c1", listOf(line(1, 100, "x")))
        HistoryStore.record("c2", listOf(line(2, 200, "y")))
        HistoryStore.clearAll()
        assertEquals(0, HistoryStore.count("c1"))
        assertEquals(0, HistoryStore.count("c2"))
    }

    @Test
    fun mergingKeepsTheUnionOrderedByTime() {
        // Mirrors what ChatContext.collect does: live + history, deduplicated by id, time ordered.
        val live = listOf(line(3, 300, "live-new"), line(2, 200, "both"))
        val hist = listOf(line(2, 200, "both"), line(1, 100, "hist-old"))
        val merged = LinkedHashMap<Long, HistoryStore.Line>()
        for (l in live) merged[l.msgId] = l
        for (l in hist) if (!merged.containsKey(l.msgId)) merged[l.msgId] = l
        val ordered = merged.values.sortedBy { it.time }
        assertEquals(3, ordered.size)
        assertEquals(100L, ordered[0].time)
        assertEquals(200L, ordered[1].time)
        assertEquals(300L, ordered[2].time)
        assertEquals("both", ordered[1].text)
    }

    @Test
    fun olderMessagesBecomeReachable() {
        // The original complaint: the oldest visible message kept moving. With history merged in,
        // a message recorded earlier is still available even if the adapter no longer holds it.
        HistoryStore.clearAll()
        HistoryStore.record("t6", listOf(line(1, 100, "very old")))
        val available = HistoryStore.lines("t6") + listOf(line(9, 900, "new"))
        assertTrue(available.any { it.text == "very old" })
    }

    // ============================================================================================
    // SOUL-4 additions. The Soul feed re-binds every row on every scroll, so the store's idempotency
    // is not a nicety -- it is what stops one message becoming fifty. These tests pin the exact
    // behaviour the Soul feed depends on, using the key rule its reader produces.
    // ============================================================================================

    @Test
    fun repeatedBindsOfTheSameSyntheticKeyStayOneLine() {
        // Soul messages often arrive with no usable id (ImMessage.getMsgId() is a String and can be
        // empty), so the feed falls back to a deterministic synthetic key. Re-binding that same row --
        // which a RecyclerView does constantly -- must NOT add a second line.
        HistoryStore.clearAll()
        val id = com.luoliaoo.wechat.hook.SoulEnvelope.syntheticId("peer", 1_700_000_000_000L, false, "hi")
        repeat(50) {
            HistoryStore.record("soul1", listOf(line(id, 1_700_000_000_000L, "hi")))
        }
        assertEquals(1, HistoryStore.count("soul1"))
    }

    @Test
    fun aRebindWithTheSameRealIdStaysOneLine() {
        HistoryStore.clearAll()
        HistoryStore.record("soul2", listOf(line(4242, 1_700_000_000_000L, "hello")))
        HistoryStore.record("soul2", listOf(line(4242, 1_700_000_000_000L, "hello")))
        assertEquals(1, HistoryStore.count("soul2"))
        assertEquals("hello", HistoryStore.lines("soul2")[0].text)
    }

    @Test
    fun soulTextOnlyContractKeepsNonTextOutOfTheStore() {
        // The reader returns "" for a non-text message, and HistoryStore already refuses a blank
        // text. Together they are the 20 KB base64 guard, asserted end to end.
        HistoryStore.clearAll()
        val decision = com.luoliaoo.wechat.hook.SoulEnvelope.decide(
            "cn.soulapp.imlib.msg.chat.ImgMsg",
            "{\"messageType\":\"IMG\",\"msgContent\":\"AAAA\"}",
            recalled = false,
        )
        HistoryStore.record("soul3", listOf(line(1, 100, decision.text)))
        assertEquals(0, HistoryStore.count("soul3"))
    }

    @Test
    fun aSoulConversationIsIsolatedFromEveryOtherConversation() {
        // The Soul feed keys by the peer id; two peers must never share a file.
        HistoryStore.clearAll()
        HistoryStore.record("soulA", listOf(line(1, 100, "for A")))
        HistoryStore.record("soulB", listOf(line(1, 100, "for B")))
        assertEquals("for A", HistoryStore.lines("soulA")[0].text)
        assertEquals("for B", HistoryStore.lines("soulB")[0].text)
    }

    @Test
    fun soulTalkerNormalisationCollapsesTwoSpellingsOfOneChat() {
        // HistoryStore.key() strips everything after '@' and lowercases, so a Soul peer id that the
        // two accessors spell differently still lands in ONE conversation instead of a populated
        // entry plus an empty one.
        HistoryStore.clearAll()
        HistoryStore.record("PeerXyz", listOf(line(1, 100, "one")))
        assertEquals(1, HistoryStore.count("peerxyz"))
        assertEquals(1, HistoryStore.count("PeerXyz"))
    }
}
