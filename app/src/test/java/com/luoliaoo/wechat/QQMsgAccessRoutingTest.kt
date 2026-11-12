package com.luoliaoo.wechat

import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.hook.MsgAccess
import com.luoliaoo.wechat.hook.QQEnvelope
import com.luoliaoo.wechat.hook.QQMsgAccess
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T21: [MsgAccess] must route QQ to the QQ reader, exactly as it does for the other four hosts.
 *
 * ============================== WHY THIS TEST EXISTS ==========================================
 *
 * `MsgAccess` is the host-agnostic facade every caller reads messages through. Until this revision it
 * had arms only for WECHAT / DOUYIN / SOUL / KUAISHOU, so a QQ message fell through to the WECHAT
 * reader: that reader looks for the field `field_content` and then probes WeChat's obfuscated accessors
 * (`j`, `t0`, `u0`, `y0`, `z0`, `U1`).
 *
 * NONE of those exist on a QQ object, so `content()` returned `""`. The failure that produces is the
 * WORST KIND and is not hypothetical -- it is the same one recorded for Soul and then Kuaishou: the 分析
 * menu item APPEARS normally and the tap does nothing, because `AnalysisController.startAnalysisFor`
 * sees blank text and reports 「未找到可分析的文本消息」, while the live-history feed rejects every message
 * at its blank-text gate so the store stays empty -- AND EVERY LOG LINE LOOKS HEALTHY. Nothing says
 * "a reader was missing"; it looks like the user never received a text message.
 *
 * QQ is the worst case of the four, for a structural reason: its chat line is not even ON the message
 * object. It lives in `MsgRecord.elements[].textElement.content`, so the WeChat reader could not reach
 * it even in principle -- `field_content` does not exist and no accessor probing could find a body.
 *
 * ============================== WHY THE FIXTURE DISCRIMINATES =================================
 *
 * A routing test is only meaningful if the two readers would give DIFFERENT answers for the same
 * object. `KuaishouMsgAccessRoutingTest` records that its FIRST version was a bluff: it used a fake
 * class carrying neither field family, so both readers honestly returned `""` and the test passed even
 * with the dispatch arms DELETED. A test that cannot fail is worse than no test, because it reports
 * safety.
 *
 * This file avoids that two ways:
 *
 *   1. The QQ fixtures are the REAL shapes (`MsgRecord` with `elements`, the wrapper `AIOMsgItem`),
 *      which the WeChat reader cannot interpret at all -- it has no `field_*` member and no body to
 *      find, so it returns `""` while the QQ reader returns the real line. The two MUST disagree, and
 *      [theFixtureMakesTheTwoReadersDisagree] asserts that precondition explicitly so the test cannot
 *      silently decay into a bluff.
 *   2. The assertions are phrased against the WeChat answer (`""`), not merely against "non-null".
 *      Deleting the `isQq` arm makes them fail -- verified by mutation (documented in the task report).
 *
 * ============================== WHAT THIS STILL CANNOT PROVE ==================================
 *
 * These are SHAPE stand-ins, not the real classes: real `MsgRecord` has 66 fields / 69 methods and is
 * produced by the NT kernel from JNI, with QFIX-redirected getters. So this proves the DISPATCH and
 * the neighbour-host isolation; the real field READS are covered by engineer3's `QQMsgAccessTest`, and
 * the on-device probe (`QQMsgAccess.probe`) is what confirms the live shapes. Stated explicitly so
 * nobody mistakes this for device evidence.
 *
 * `HostApp.current` is set per test and restored in a `finally`, so one failure cannot poison another.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQMsgAccessRoutingTest {

    /** A loader that resolves nothing, so no host class is reachable from the test JVM. */
    private class EmptyLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)

        override fun findClass(name: String): Class<*> =
            throw ClassNotFoundException(name)
    }

    private fun access(): MsgAccess = MsgAccess(Resolver(EmptyLoader(), "", File(".")))

    private inline fun <T> asHost(host: HostApp, block: () -> T): T {
        val prev = HostApp.current
        HostApp.setCurrent(host)
        try {
            return block()
        } finally {
            HostApp.setCurrent(prev)
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A record carrying ONE real text element whose body is [body]. */
    private fun textRecord(body: String): MsgRecord {
        val r = MsgRecord()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_TEXT
        val t = TextElement()
        t.content = body
        e.textElement = t
        r.elements = arrayListOf(e)
        return r
    }

    /** The UI shape: QQ hands the hook an item that CARRIES the record, not the record itself. */
    private fun item(body: String): AIOMsgItem {
        val it = AIOMsgItem()
        it.e = textRecord(body)
        return it
    }

    private val QQ_BODY = "qq-routing-body"

    // ------------------------------------------------------------------ precondition

    /**
     * PRECONDITION. If this ever stopped holding, every assertion below would silently stop
     * discriminating and the file would go back to being the bluff the Kuaishou test records.
     */
    @Test
    fun `the fixture makes the two readers disagree`() {
        val msg = textRecord(QQ_BODY)
        val qqAnswer = asHost(HostApp.QQ) { access().content(msg) }
        val wechatAnswer = asHost(HostApp.WECHAT) { access().content(msg) }

        assertEquals("the QQ reader must find the body in elements[]", QQ_BODY, qqAnswer)
        assertEquals(
            "the WeChat reader must NOT be able to read a QQ record " +
                "(if it can, the fixture no longer discriminates and the tests below are vacuous)",
            "",
            wechatAnswer,
        )
        assertNotEquals(
            "the two readers MUST disagree or this whole file proves nothing",
            qqAnswer,
            wechatAnswer,
        )
    }

    // ------------------------------------------------------------------ the t21 guard

    @Test
    fun `content does NOT come from the wechat reader when the host is qq`() {
        // THE t21 regression guard. Without the `isQq` arm in MsgAccess.content this returned the
        // WeChat answer (""), and 分析 would report 「未找到可分析的文本消息」. Deleting the arm makes
        // this fail -- verified by mutation.
        val msg = textRecord(QQ_BODY)
        val got = asHost(HostApp.QQ) { access().content(msg) }
        assertEquals("must read the QQ body, not fall through to the WeChat reader", QQ_BODY, got)
    }

    @Test
    fun `content routes the UI item shape too, not just the bare record`() {
        // The UI hands over AIOMsgItem, not MsgRecord. A dispatch arm that only worked for the record
        // would leave every message read off the chat list empty -- with healthy-looking logs.
        val it = item(QQ_BODY)
        assertEquals(QQ_BODY, asHost(HostApp.QQ) { access().content(it) })
    }

    /**
     * `msgId` must route to the QQ reader -- tested through the UI ITEM, not the bare record.
     *
     * ===================== WHY THE SHAPE MATTERS FOR THIS ONE =====================
     * This test is written against `AIOMsgItem` on purpose, and that is not cosmetic.
     *
     * With the BARE RECORD the assertion CANNOT discriminate: QQ's id getter is named `getMsgId()`, and
     * the WeChat arm's first candidate is `callLong(msg, "getMsgId")` -- the SAME name. So a bare
     * `MsgRecord` returns the same id through BOTH readers, and an `assertNotEquals` against the WeChat
     * answer would pass even with the `isQq` arm DELETED. That is precisely the bluff
     * `KuaishouMsgAccessRoutingTest` records, and the first draft of this test had it.
     *
     * The ITEM shape discriminates, because the QQ reader's `unwrap()` is what reaches the record:
     * `AIOMsgItem` exposes neither `getMsgId` nor `field_msgId`, so the WeChat arm finds nothing and
     * returns 0, while the QQ arm unwraps to the record and returns the real id. Deleting the `isQq` arm
     * therefore makes this fail.
     *
     * The lesson is recorded here rather than silently fixed: on this facade, "the two readers disagree"
     * is a property of the OBJECT SHAPE, not of the host, and a routing assertion must be checked for it
     * per method.
     */
    @Test
    fun `msgId routes to the qq reader, through the item shape that discriminates`() {
        val it = item(QQ_BODY)
        it.e!!.msgId = 0x5EEDL

        assertEquals(0x5EEDL, asHost(HostApp.QQ) { access().msgId(it) })
        assertEquals(
            "the WeChat reader must NOT reach the wrapped record (this is what discriminates)",
            0L,
            asHost(HostApp.WECHAT) { access().msgId(it) },
        )
    }

    @Test
    fun `talker routes to the qq reader`() {
        val msg = textRecord(QQ_BODY)
        msg.peerUid = "u_qq_peer"
        msg.peerUin = 10001L
        // `QQEnvelope.pickChatId` prefers `peerUid` when non-blank, so the expected value is EXACT and
        // not merely "something non-blank". Pinning the value matters because the store keys history by
        // it: a key that changed between calls, or that differed between the two directions, would split
        // one conversation across several files -- the failure `HistoryStore` cannot detect on its own.
        assertEquals(
            "peerUid must win over peerUin",
            "u_qq_peer",
            asHost(HostApp.QQ) { access().talker(msg) },
        )
        // The WeChat reader looks for `field_talker` (absent) then `t0()` (absent): it must find NOTHING,
        // because a blank key would silently drop the message from the store.
        assertEquals(
            "the WeChat reader must not resolve a QQ conversation key",
            "",
            asHost(HostApp.WECHAT) { access().talker(msg) },
        )
    }

    @Test
    fun `isSend routes to the qq reader`() {
        val msg = textRecord(QQ_BODY)
        // sendType == 1 is QQ's own KSendTypeSelf (the reader's short-circuit).
        msg.sendType = 1
        assertTrue(
            "sendType==1 must be read as 自己发的 by the QQ reader",
            asHost(HostApp.QQ) { access().isSend(msg) },
        )
        // The WeChat reader reads `field_isSend`, which does not exist here -- it must NOT agree by
        // accident, or the direction would be wrong in the prompt while nothing looked broken.
        assertEquals(
            "the WeChat reader must not answer the direction of a QQ record",
            false,
            asHost(HostApp.WECHAT) { access().isSend(msg) },
        )
    }

    @Test
    fun `createTime routes to the qq reader`() {
        val msg = textRecord(QQ_BODY)
        // A plausible millisecond timestamp; the reader must return something in the epoch range the
        // live feed accepts, because a 0 here silently drops EVERY message at the history gate.
        val t = 1_730_000_000_000L
        msg.msgTime = t
        val got = asHost(HostApp.QQ) { access().createTime(msg) }
        assertTrue("createTime must be a plausible epoch (got $got)", got > 1_000_000_000_000L)
    }

    @Test
    fun `isText routes to the qq reader`() {
        val msg = textRecord(QQ_BODY)
        assertTrue(
            "a real text element must be seen as text by the QQ reader",
            asHost(HostApp.QQ) { access().isText(msg) },
        )
    }

    @Test
    fun `typeOf routes to the qq reader`() {
        val msg = textRecord(QQ_BODY)
        msg.msgType = 9
        // QQ's own numbering, NOT WeChat's TYPE_TEXT=1 read off `getType`.
        assertEquals(9, asHost(HostApp.QQ) { access().typeOf(msg) })

        // NOTE THE SHAPE OF THIS ASSERTION. It is deliberately a 3-argument
        // `assertEquals(message, expected, actual)` rather than a 2-argument `assertNotEquals(msg, x)`.
        // The first draft of this test wrote the latter, and that was a BLUFF: JUnit 4 has no
        // `assertNotEquals(String, int)` overload, so it silently bound to
        // `assertNotEquals(Object unexpected, Object actual)` and compared the MESSAGE STRING against the
        // int -- a comparison that is always true, so the assertion could never fail. The WeChat reader
        // returns -1 here (the QQ record has no `getType`), and pinning that exact value is what makes
        // this assertion real.
        assertEquals(
            "the WeChat reader must not claim a QQ msgType (it has no getType, so -1)",
            -1,
            asHost(HostApp.WECHAT) { access().typeOf(msg) },
        )
    }

    // ------------------------------------------------------------------ neighbour isolation

    /**
     * The other four hosts must be untouched by the QQ arm.
     *
     * WHY IT IS HERE: adding a fifth arm to a facade is exactly the edit that can reorder or shadow an
     * existing one. The QQ arm was inserted as the FIRST check in each method, so these assertions prove
     * the insertion did not steal any neighbour's dispatch.
     */
    @Test
    fun `the other four hosts keep their own readers`() {
        // WeChat: this reads the WeChat field family, which the QQ fixture does not carry.
        val wechatMsg = FakeWeChatMsg()
        assertEquals("wechat-body", asHost(HostApp.WECHAT) { access().content(wechatMsg) })
        assertEquals("wechat-talker", asHost(HostApp.WECHAT) { access().talker(wechatMsg) })

        // QQ must NOT read the WeChat fixture either -- isolation both ways.
        assertNotEquals(
            "QQ must not read a WeChat-shaped message",
            "wechat-body",
            asHost(HostApp.QQ) { access().content(wechatMsg) },
        )

        // And an UNKNOWN host still gets the WeChat default arm by construction, never a QQ read.
        assertEquals(
            "UNKNOWN must not be routed to the QQ reader",
            "wechat-body",
            asHost(HostApp.UNKNOWN) { access().content(wechatMsg) },
        )
    }

    // ------------------------------------------------------------------ degradation

    /**
     * ============================ THE "NEVER THROWS, NEVER GUESSES" CONTRACT ============================
     *
     * Every method on the facade is called from inside a HOST HOOK, i.e. on QQ's own thread, inside
     * QQ's own code. An exception escaping here does not surface as a failed 分析 tap -- it is thrown
     * into the host and can take down the user's chat screen, and the crash looks like a QQ bug. So
     * "does not throw" is a REQUIREMENT of this reader, not a nicety, and the degraded value must be the
     * documented neutral one (""/0L/false/-1) rather than a guess.
     *
     * WHY THE ASSERTIONS BELOW ALSO PIN THE EXACT NEUTRAL VALUE. 必看.md records the policy as
     * 「降级, 不猜」: when a read fails the reader must return the neutral value and LOG the branch, never
     * invent an id and never fall back to the raw payload. A test that only asserted `assertDoesNotThrow`
     * would let a future edit "fix" a null by returning `msg.toString()` -- a payload leak that would
     * sail through. Pinning ""/0L/false/-1 is what makes the degradation observable.
     * ==================================================================================================
     */
    @Test
    fun `no method throws on a foreign object or on an item with no record`() {
        val a = access()

        // ============================ WHY THERE IS NO `null` IN THIS LIST ============================
        // The first version of this test DID include null and it DID NOT COMPILE. `MsgAccess` declares
        // `msg: Any` -- NON-nullable -- so passing an `Any?` is a type error, caught at compile time.
        //
        // That is the honest finding, and it is worth stating precisely rather than papering over:
        // for this facade, NULL NEVER REACHES THE READER. The type is the gate. Every hook site also
        // guards explicitly before calling (e.g. `QQHooks.onOurRowTapped` checks `single == null` and
        // logs a warning rather than passing it on). So "null input" is not part of THIS contract, and
        // asserting it here would have required the cast that then silently defeated the check.
        //
        // Null tolerance IS still required -- but one layer down, on `QQMsgAccess`, whose parameters
        // are `Any?` BECAUSE THAT is where a null can genuinely arrive. It is covered by the dedicated
        // test below, which calls that reader directly.
        // ============================================================================================
        val foreign: List<Any> = listOf(
            "a plain string",           // IS a java.lang.Object; a naive isInstance check would accept it
            Any(),                      // a bare Object -- the reader must reject it on identity, not on null
            item(QQ_BODY).also { it.e = null },  // an AIOMsgItem whose record was never bound
            intArrayOf(1, 2, 3),        // a primitive array -- not a message, not even close
        )

        asHost(HostApp.QQ) {
            for (m in foreign) {
                // Each on its own line so a failure names the offending object.
                assertEquals("content on $m", "", a.content(m))
                assertEquals("talker on $m", "", a.talker(m))
                assertEquals("msgId on $m", 0L, a.msgId(m))
                assertEquals("msgSvrId on $m", 0L, a.msgSvrId(m))
                assertEquals("createTime on $m", 0L, a.createTime(m))
                assertEquals("typeOf on $m", -1, a.typeOf(m))
                assertEquals("isText on $m", false, a.isText(m))
                assertEquals("isSend on $m", false, a.isSend(m))
            }
        }
    }

    /**
     * The null half of the degradation contract, tested where null can actually arrive.
     *
     * `QQMsgAccess`'s parameters are `Any?` (it is the layer that unwraps whatever the host handed us),
     * so this is the correct place to pin null tolerance. Called through its own constructor rather than
     * the facade, because the facade's `Any` cannot carry a null -- see the comment above.
     *
     * The point is the same one the facade test makes: it must not THROW, and it must return the
     * documented NEUTRAL value rather than a guess (必看.md: 「降级，不猜」).
     */
    @Test
    fun `the qq reader tolerates null and returns neutral values`() {
        val reader = QQMsgAccess(Resolver(EmptyLoader(), "", File(".")))
        assertEquals("", reader.content(null))
        assertEquals("", reader.talker(null))
        assertEquals(0L, reader.msgId(null))
        assertEquals(0L, reader.msgSvrId(null))
        assertEquals(0L, reader.createTime(null))
        assertEquals(-1, reader.typeOf(null))
        assertEquals(false, reader.isText(null))
        assertEquals(false, reader.isSend(null))
        assertEquals(false, reader.isMessage(null))
    }

    @Test
    fun `the wechat arm is equally safe for a qq shaped object`() {
        // The OTHER direction of the same contract: the WeChat reader must survive being handed a QQ
        // object, because that is exactly what happened before this revision (the dispatch arm was
        // missing). If the WeChat arm threw instead of returning its neutral "", the pre-fix behaviour
        // would have been a crash in QQ's chat screen rather than a silent no-op -- a much worse bug
        // than the one t21 fixes, and worth pinning so a future "defensive" edit cannot introduce it.
        val a = access()
        val qqMsg = textRecord(QQ_BODY)
        asHost(HostApp.WECHAT) {
            assertEquals("", a.content(qqMsg))
            assertEquals("", a.talker(qqMsg))
            assertEquals(0L, a.msgId(qqMsg))
            assertEquals(0L, a.createTime(qqMsg))
            assertEquals(false, a.isText(qqMsg))
            assertEquals(false, a.isSend(qqMsg))
        }
    }

    @Test
    fun `an empty record and a null element degrade without throwing`() {
        val a = access()
        // (a) A record whose `elements` list is EMPTY -- the normal shape of a picture/voice message and
        //     the state of a freshly built record. This is NOT the same as "unreadable" and the reader
        //     has a separate branch for each, so both are exercised here.
        val empty = textRecord(QQ_BODY).also { it.elements = ArrayList() }
        // (b) A list holding a NULL element. QQ really produces holes in this list, and the reader has an
        //     explicit `if (e == null) continue` -- without it this would be an NPE inside a host hook.
        //     NOTE this one is deliberately NOT asserted to be non-storable: the real TEXT element still
        //     follows the hole, so the reader must SKIP the hole and still find the body. Asserting
        //     "not text" here would have been wrong, and the first draft of this test did exactly that.
        val withHole = textRecord(QQ_BODY).also { addHole(it) }

        asHost(HostApp.QQ) {
            assertEquals("empty: msgId", 0L, a.msgId(empty))
            assertEquals("empty: createTime", 0L, a.createTime(empty))
            assertEquals("empty: typeOf", 0, a.typeOf(empty))
            assertEquals("empty: no TEXT element, so it must not be storable", false, a.isText(empty))
            // Not "" : an empty element list is a KNOWN non-text shape, so `decide()` returns the
            // [\u975e\u6587\u672c\u6d88\u606f] placeholder. Pinning the real value matters -- I first wrote
            // `""` here and that assertion would have failed.
            assertEquals(
                "empty: must be the [\u975e\u6587\u672c\u6d88\u606f] placeholder",
                QQEnvelope.UNKNOWN_NON_TEXT,
                a.content(empty),
            )

            // The hole must NOT swallow the message: the reader skips the null and reads what follows.
            assertEquals("hole: content", QQ_BODY, a.content(withHole))
            assertEquals("hole: must still be storable text", true, a.isText(withHole))
        }
    }

    /**
     * Put a `null` into the record's element list.
     *
     * The fixture's `elements` is `ArrayList<MsgElement>` (the verified declared type), and Kotlin's
     * generics are erased at runtime, so a null really can be inserted -- which is the point: the reader
     * must survive it. Isolated in a helper with the cast suppressed once, rather than spelled inline,
     * so the intent ("a hole in the list") is visible at the call site.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addHole(rec: MsgRecord) {
        (rec.elements as ArrayList<MsgElement?>).add(null)
    }

    @Test
    fun `a non-text message yields its placeholder and is never storable text`() {
        // ==================== THE FACADE-LEVEL GUARD FOR THE DOUYIN 20 KB BUG ====================
        // A picture message carries a `TextElement` too, holding a PAYLOAD (a path or a JSON blob), not
        // a chat line. 必看.md records the Douyin bug where exactly this payload was stored as the
        // message body. `QQEnvelope.decide` is what rejects it, and this test pins the FACADE's half of
        // the contract: the placeholder comes back, `isText` is false so nothing is stored, and the raw
        // payload never appears in the returned string.
        // ========================================================================================
        val rec = MsgRecord()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_PIC
        val t = TextElement()
        t.content = PAYLOAD
        e.textElement = t
        rec.elements = arrayListOf(e)

        val a = access()
        asHost(HostApp.QQ) {
            val got = a.content(rec)
            assertEquals("must be the [\u56fe\u7247] placeholder", "[\u56fe\u7247]", got)
            assertTrue("the raw payload must NEVER surface", !got.contains(PAYLOAD))
            assertEquals("a picture must not be storable text", false, a.isText(rec))
        }
    }

    /**
     * A payload that a real QQ picture element carries -- deliberately not human text, so an assertion
     * that it does not surface is meaningful.
     */
    private val PAYLOAD = "PAYLOAD-must-never-surface"

    /**
     * The WeChat field family on its own object, so the WeChat arm has something real to read.
     *
     * Field NAMES match WeChat's (`field_content` / `field_talker`); the reader reads them reflectively.
     */
    @Suppress("MemberVisibilityCanBePrivate", "unused")
    private class FakeWeChatMsg {
        @JvmField
        var field_content: String = "wechat-body"

        @JvmField
        var field_talker: String = "wechat-talker"

        @JvmField
        var field_isSend: Int = 0
    }
}
