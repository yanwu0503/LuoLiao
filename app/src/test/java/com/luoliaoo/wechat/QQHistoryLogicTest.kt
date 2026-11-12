package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.QQEnvelope
import com.luoliaoo.wechat.hook.QQHistoryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QQHistoryLogic] -- the pure decisions behind the QQ history feed.
 *
 * ============================== WHAT THESE TESTS PIN ==============================
 *
 * `QQHistoryHooks` runs inside QQ's own bind call and can only be exercised on a device. Its decisions
 * are therefore split into `QQHistoryLogic` as pure functions, and pinned here, because each of the
 * three below fails in a way a device run would NOT clearly reveal:
 *
 *   1. THE BIND SHAPE. A wrong predicate hooks NOTHING while reporting success -- no exception, no
 *      binder error, just an empty history that looks like "the user has not opened a chat yet".
 *      That silent-nothing class is exactly 必看.md's `onBindViewHolder` lesson.
 *
 *   2. THE DEDUP KEY. `(talker, msgId)` is the identity the store already keys by. Get it wrong in
 *      one direction and one conversation is split across two files; get it wrong in the other and
 *      a genuinely new message is rejected as a duplicate. Both are silent.
 *
 *   3. GROUP vs PRIVATE. QQ's chat-type numbers are QQ's own. Treating an unreadable (-1) value as
 *      "private" makes a misfiled group message look like a working feature.
 *
 * The hook itself is deliberately NOT mocked here: these tests cover the decisions, and the
 * reflection/hook installation is what the device run (and T20) verifies.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQHistoryLogicTest {

    // ---------------------------------------------------------------- 1. the bind shape

    @Test
    fun `the real bind shape matches`() {
        // AbsMsgListVB#y(holder, int, msgBase, List)V -- verified in classes3.dex:
        // acc=0x11 public|final, code_off=5062512, units=370.
        assertTrue(
            QQHistoryLogic.matchesBindShape(
                paramCount = 4,
                returnsVoid = true,
                msgBaseAssignable = true,
            ),
        )
    }

    @Test
    fun `three arguments is not the bind body`() {
        // The 3-arg shape is RecyclerView's payload overload, which QQ does NOT use for the real
        // bind (its 3-arg adapter method is the one that CALLS y). Hooking the 3-arg shape on this
        // class would attach to a method that is never invoked.
        assertFalse(
            QQHistoryLogic.matchesBindShape(paramCount = 3, returnsVoid = true, msgBaseAssignable = true),
        )
    }

    @Test
    fun `a value-returning method is not the bind body`() {
        // A 4-arg method that RETURNS a value is a different method that merely looks similar.
        assertFalse(
            QQHistoryLogic.matchesBindShape(paramCount = 4, returnsVoid = false, msgBaseAssignable = true),
        )
    }

    @Test
    fun `a method whose third parameter is not the message is not the bind body`() {
        assertFalse(
            QQHistoryLogic.matchesBindShape(paramCount = 4, returnsVoid = true, msgBaseAssignable = false),
        )
    }

    // ---------------------------------------------------------------- 2. the VM fallback shape

    @Test
    fun `the VM fallback finds the record at index 0 for the old single-arg shape`() {
        // `o0(MsgRecord)V` was the shape T19 recorded, and it must still work if QQ ever emits it.
        assertEquals(
            0,
            QQHistoryLogic.msgRecordParamIndex(
                paramCount = 1,
                returnsVoid = true,
                paramTypeNames = listOf("com.tencent.qqnt.kernel.nativeinterface.MsgRecord"),
            ),
        )
    }

    @Test
    fun `the VM fallback finds the record at index 1 for the REAL two-arg shape`() {
        // ============================ THE DEVICE-VERIFIED SHAPE ============================
        // T19 wrote `o0(MsgRecord)V` (one parameter). The dex says TWO:
        //   o0(Lcom/tencent/mobileqq/aio/msglist/AIOMsgListVM;Lcom/.../MsgRecord;)V   acc=0x9
        // `0x9` = public|static, with the declaring class repeated as the FIRST parameter -- the
        // shape Kotlin emits when a member body is extracted to a static helper.
        //
        // The device log proved the consequence of the old matcher: the fallback never installed
        // ("declares no void method taking a single MsgRecord parameter"). This test pins BOTH the
        // acceptance and the INDEX, because the old code called getArg(0) -- which on this
        // descriptor is the VM, not the message.
        assertEquals(
            1,
            QQHistoryLogic.msgRecordParamIndex(
                paramCount = 2,
                returnsVoid = true,
                paramTypeNames = listOf(
                    "com.tencent.mobileqq.aio.msglist.AIOMsgListVM",
                    "com.tencent.qqnt.kernel.nativeinterface.MsgRecord",
                ),
            ),
        )
    }

    @Test
    fun `the VM fallback takes the LAST record when a continuation follows`() {
        // A suspend form appends a Continuation AFTER the arguments, so scanning backwards and
        // taking the last match keeps the index correct instead of landing on the wrong arg.
        assertEquals(
            1,
            QQHistoryLogic.msgRecordParamIndex(
                paramCount = 3,
                returnsVoid = true,
                paramTypeNames = listOf(
                    "com.tencent.mobileqq.aio.msglist.AIOMsgListVM",
                    "com.tencent.qqnt.kernel.nativeinterface.MsgRecord",
                    "kotlin.coroutines.Continuation",
                ),
            ),
        )
    }

    @Test
    fun `the VM fallback rejects a non-void or record-free shape`() {
        val rec = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord"
        assertEquals(-1, QQHistoryLogic.msgRecordParamIndex(2, false, listOf("X", rec)))
        assertEquals(-1, QQHistoryLogic.msgRecordParamIndex(0, true, emptyList()))
        assertEquals(-1, QQHistoryLogic.msgRecordParamIndex(2, true, listOf(null, null)))
        // A record-free void method must not be mistaken for a feeder.
        assertEquals(-1, QQHistoryLogic.msgRecordParamIndex(1, true, listOf("java.lang.String")))
    }

    @Test
    fun `the VM fallback ignores MsgRecordParams, a REAL different class`() {
        // `MsgRecordParams` is a real class on AIOMsgItem (its field A). Matching by `contains`
        // instead of `endsWith` would accept it and the feed would push a non-record.
        assertFalse(QQHistoryLogic.isMsgRecordName("com.tencent.mobileqq.aio.msg.MsgRecordParams"))
        assertEquals(
            -1,
            QQHistoryLogic.msgRecordParamIndex(
                paramCount = 1,
                returnsVoid = true,
                paramTypeNames = listOf("com.tencent.mobileqq.aio.msg.MsgRecordParams"),
            ),
        )
    }

    // ---------------------------------------------------------------- 3. the dedup key

    @Test
    fun `the dedup key is talker plus msgId`() {
        assertEquals("12345#987", QQHistoryLogic.dedupKey("12345", 987L, 111L))
    }

    @Test
    fun `a zero msgId falls back to msgSeq`() {
        // QQMsgAccess.msgSeq documents msgSeq as the fallback id. A message with msgId == 0 is real
        // (a freshly bound row can have one) and must still be keyable.
        assertEquals("12345#111", QQHistoryLogic.dedupKey("12345", 0L, 111L))
    }

    @Test
    fun `a zero from both ids is not keyable`() {
        // Inventing an id here would store a duplicate on every re-bind instead of rejecting the row.
        assertEquals("0", QQHistoryLogic.dedupKey("12345", 0L, 0L))
        assertFalse(QQHistoryLogic.isKeyable(QQHistoryLogic.dedupKey("12345", 0L, 0L)))
    }

    @Test
    fun `a blank talker is not keyable`() {
        // Filing under a guessed conversation splits one chat across files -- the bug
        // QQMsgAccess.talker's own comment records.
        assertEquals("0", QQHistoryLogic.dedupKey("", 987L, 0L))
        assertEquals("0", QQHistoryLogic.dedupKey("   ", 987L, 0L))
        assertEquals("0", QQHistoryLogic.dedupKey(null, 987L, 0L))
    }

    @Test
    fun `the same message bound twice yields the same key`() {
        // The property the store relies on: a repeated bind must NOT look like a new message.
        assertEquals(
            QQHistoryLogic.dedupKey("12345", 987L, 0L),
            QQHistoryLogic.dedupKey("12345", 987L, 0L),
        )
    }

    @Test
    fun `different conversations with the same id do NOT collide`() {
        // msgId is only unique PER CONVERSATION in QQ, so the talker must be part of the key.
        assertFalse(
            QQHistoryLogic.dedupKey("111", 987L, 0L) == QQHistoryLogic.dedupKey("222", 987L, 0L),
        )
    }

    @Test
    fun `the talker is normalised so a padded id cannot split a chat`() {
        assertEquals(
            QQHistoryLogic.dedupKey("12345", 987L, 0L),
            QQHistoryLogic.dedupKey("  12345  ", 987L, 0L),
        )
    }

    @Test
    fun `a keyable key is recognised`() {
        assertTrue(QQHistoryLogic.isKeyable(QQHistoryLogic.dedupKey("12345", 987L, 0L)))
    }

    // ---------------------------------------------------------------- 4. group vs private

    @Test
    fun `QQ's own group numbers are group`() {
        // MsgConstant.KCHATTYPEGROUP = 2, KCHATTYPEDISC = 3, KCHATTYPETEMPC2CFROMGROUP = 100 --
        // the same set QQEnvelope.GROUP_CHAT_TYPES already holds.
        assertEquals("group", QQHistoryLogic.conversationKind(2))
        assertEquals("group", QQHistoryLogic.conversationKind(3))
        assertEquals("group", QQHistoryLogic.conversationKind(100))
    }

    @Test
    fun `a two-person chat is private`() {
        // KCHATTYPEC2C = 1
        assertEquals("private", QQHistoryLogic.conversationKind(1))
    }

    @Test
    fun `an unreadable chat type is unknown, NOT private`() {
        // Calling an unreadable value "private" makes a misfiled group message look like a working
        // feature; an admitted gap is strictly better.
        assertEquals("unknown", QQHistoryLogic.conversationKind(-1))
        assertFalse(QQHistoryLogic.conversationKind(-1) == "private")
    }

    @Test
    fun `conversationKind delegates to QQEnvelope rather than re-listing the numbers`() {
        // A second copy of QQ's enumeration is how the two drift apart, so the classification is
        // asserted to AGREE with QQEnvelope for every value either of them knows about.
        for (t in -1..110) {
            val expected = when {
                t < 0 -> "unknown"
                QQEnvelope.isGroupChatType(t) -> "group"
                else -> "private"
            }
            assertEquals("chatType=" + t, expected, QQHistoryLogic.conversationKind(t))
        }
    }

    // ---------------------------------------------------------------- 5. the diagnostic line

    @Test
    fun `describe names the kind, the talker and the id`() {
        val s = QQHistoryLogic.describe("12345", 1, 987L)
        assertTrue(s, s.contains("kind=private"))
        assertTrue(s, s.contains("talker=12345"))
        assertTrue(s, s.contains("msgId=987"))
    }

    @Test
    fun `describe marks a blank talker instead of printing nothing`() {
        // "(blank)" in the log is what makes a blank talker obvious; an empty value reads as noise.
        val s = QQHistoryLogic.describe("   ", 2, 0L)
        assertTrue(s, s.contains("(blank)"))
        assertTrue(s, s.contains("kind=group"))
    }

    // ---------------------------------------------------------------- 6. constants pin the dex facts

    @Test
    fun `the constants match the verified dex shape`() {
        // AbsMsgListVB#y(holder/a; ILcom/tencent/aio/data/msglist/a; Ljava/util/List;)V
        assertEquals(4, QQHistoryLogic.BIND_PARAM_COUNT)
        assertEquals(2, QQHistoryLogic.BIND_MSG_PARAM_INDEX)
        assertEquals("MsgRecord", QQHistoryLogic.MSG_RECORD_SUFFIX)
    }
}
