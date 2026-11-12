package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards behind the "card under the wrong message" fixes (items 3 and 4) and the 1:1 labelling
 * (item 2).
 *
 * The placement guard itself lives in CardRenderer and needs a View hierarchy, so what is pinned here
 * is the DECISION it makes, expressed as pure logic -- mirroring verifyRow(): a row may only receive a
 * card when its own message id matches; a mismatch refuses, and an unreadable row is trusted.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class RowVerifyTest {

    /**
     * Mirrors CardRenderer.verifyRow.
     *
     * @return null to allow the attach, or the row's actual id to refuse it
     */
    private fun verify(expectId: Long, rowId: Long?): Long? {
        if (expectId == 0L) return null
        if (rowId == null) return null          // unreadable row: trust the caller
        return if (rowId == expectId) null else rowId
    }

    @Test
    fun matchingRowIsAllowed() {
        assertNull(verify(100L, 100L))
    }

    @Test
    fun mismatchedRowIsRefused() {
        // The reported bug: a recycled row showing a different message must not receive the card.
        assertEquals(200L, verify(100L, 200L))
    }

    @Test
    fun unreadableRowIsTrusted() {
        // Rows WeChat does not tag would otherwise never get a card at all.
        assertNull(verify(100L, null))
    }

    @Test
    fun zeroExpectIdDisablesTheCheck() {
        assertNull(verify(0L, 999L))
    }

    @Test
    fun severalKeysCannotShareOneRow() {
        // "A pile of cards all showing the same analysis": each key must only accept its own row.
        val rowId = 500L
        val results = listOf(500L, 501L, 502L).map { verify(it, rowId) }
        assertEquals(1, results.count { it == null })   // exactly the owner is allowed
        assertEquals(2, results.count { it != null })   // the others are refused
    }

    // -------------------------------------------------------------- item 2

    @Test
    fun twoPersonChatLabelsTheOtherSide() {
        // ChatLine.who falls back to 我 / 对方 when no speaker is stored.
        val mine = ChatLine(time = 1L, isSend = true, text = "hi", speaker = null)
        val theirs = ChatLine(time = 2L, isSend = false, text = "hello", speaker = null)
        assertEquals("我", mine.who)
        assertEquals("对方", theirs.who)
    }

    @Test
    fun groupChatKeepsRealIds() {
        val member = ChatLine(time = 1L, isSend = false, text = "hi", speaker = "wxid_bob")
        assertEquals("wxid_bob", member.who)
    }

    @Test
    fun whoPrefersTheStoredSpeakerWhenPresent() {
        val explicit = ChatLine(time = 1L, isSend = false, text = "x", speaker = "wxid_someone")
        assertTrue(explicit.who.contains("wxid_"))
    }
}
