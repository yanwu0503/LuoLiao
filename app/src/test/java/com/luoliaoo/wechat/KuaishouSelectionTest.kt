package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.KuaishouSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [KuaishouSelection] -- reading Kuaishou's multi-select state.
 *
 * ============================== WHAT THESE TESTS PIN ==========================================
 *
 * The blueprint originally specified reading the selection through `frj.p.b():List` and flagged the
 * weakness itself: that list is BARE, and `MsgMulltiChooseResponse$ChoosedMsg` carries only
 * `mFromUserId:String` + `mSeq:J`, so it cannot yield message objects. Disassembling `esj.l.g(KwaiMsg)`
 * settled the question -- the selection Map's VALUES are the real `KwaiMsg` objects, keyed by a
 * composite string `sender + '_' + clientSeq`.
 *
 * The trap these tests guard against is REBUILDING THAT KEY. A key-based lookup works for the common
 * case and fails silently otherwise (clientSeq 0 on an unsent message, a differently-spelled sender on
 * a local echo), and the failure is an EMPTY selection for a selection the user can see on screen --
 * indistinguishable from "the feature is broken".
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouSelectionTest {

    /** A stand-in for a selected message. */
    private class Msg(val label: String)

    @Test
    fun `values are returned, keys are ignored`() {
        // The whole point: the map's values ARE the messages, whatever the keys look like.
        val a = Msg("a")
        val b = Msg("b")
        val map = linkedMapOf("sender1_1" to a, "sender2_2" to b)
        val got = KuaishouSelection.valuesOf(map)
        assertEquals(2, got.size)
        assertTrue("the message objects themselves must come back", got[0] === a && got[1] === b)
    }

    @Test
    fun `an unrecognisable key format does not lose the selection`() {
        // THE regression guard for the key trap. None of these keys follow the documented format, and
        // every one of them must still yield its message -- a key-based lookup would return NOTHING here
        // while the user sees two selected messages on screen.
        val a = Msg("a")
        val b = Msg("b")
        val map = linkedMapOf(
            "" to a,                       // empty key
            "totally-different-format" to b,
        )
        val got = KuaishouSelection.valuesOf(map)
        assertEquals("a key-format mismatch must not drop messages", 2, got.size)
    }

    @Test
    fun `null values are skipped`() {
        val a = Msg("a")
        val map = linkedMapOf("k1" to a, "k2" to null)
        val got = KuaishouSelection.valuesOf(map)
        assertEquals(1, got.size)
        assertTrue(got[0] === a)
    }

    @Test
    fun `an empty or null map yields an empty list`() {
        assertTrue(KuaishouSelection.valuesOf(null).isEmpty())
        assertTrue(KuaishouSelection.valuesOf(emptyMap<String, Any>()).isEmpty())
    }

    @Test
    fun `the SAME object added twice collapses to one`() {
        // A double tap on the checkbox must not analyse a message twice.
        val a = Msg("a")
        val map = linkedMapOf("k1" to a, "k2" to a)
        assertEquals(1, KuaishouSelection.valuesOf(map).size)
    }

    @Test
    fun `two identical but distinct messages BOTH survive`() {
        // Dedup is by IDENTITY, not by equality/toString: two genuinely identical messages really are
        // two messages, and dropping one would silently lose a line of the user's chat.
        val map = linkedMapOf("k1" to Msg("same"), "k2" to Msg("same"))
        assertEquals(
            "distinct messages must not be merged",
            2,
            KuaishouSelection.valuesOf(map).size,
        )
    }

    @Test
    fun `batch is taken only for MORE than one message`() {
        // Kuaishou pre-selects the long-pressed message when entering multi-select, so a single
        // selection must stay on the single-message path.
        assertFalse(KuaishouSelection.isBatch(null))
        assertFalse(KuaishouSelection.isBatch(emptyList()))
        assertFalse("one message is NOT a batch", KuaishouSelection.isBatch(listOf(Msg("a"))))
        assertTrue(KuaishouSelection.isBatch(listOf(Msg("a"), Msg("b"))))
    }

    @Test
    fun `the log description is bounded`() {
        // A 200-message selection must not write a multi-kilobyte line into a log read on a phone.
        val many = (1..200).map { Msg("m$it") }
        val line = KuaishouSelection.describeForLog(many, limit = 6)
        assertTrue("must mention the total", line.contains("200 total"))
        assertTrue("must be short", line.length < 120)
        assertEquals("<empty>", KuaishouSelection.describeForLog(emptyList()))
    }

    @Test
    fun `the diagnostic key format matches the host disassembly`() {
        // `o2k.b2.b(KwaiMsg)` = getSender() + '_' + getClientSeq(). Recorded here so a future log line
        // can compare what the host wrote with what we read -- this is for DIAGNOSIS ONLY and is never
        // used for lookup.
        assertEquals("u123_456", KuaishouSelection.keyOf("u123", 456L))
        assertEquals("_0", KuaishouSelection.keyOf(null, 0L))
        assertEquals("u1_0", KuaishouSelection.keyOf("  u1  ", 0L))
    }
}
