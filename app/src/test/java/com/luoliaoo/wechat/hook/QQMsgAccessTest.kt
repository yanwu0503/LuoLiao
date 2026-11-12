package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Resolver
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.kernel.nativeinterface.GrayTipElement
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.RevokeElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The QQ message-reader contract: msgType/element -> text extraction, self vs other, group vs private,
 * and the non-text degradation.
 *
 * ============================== WHY THESE TESTS DRIVE THE REAL ENTRY POINT ====================
 *
 * 必看.md records the failure this file is written to avoid: a test that drives the path NEXT TO the one
 * the user actually takes. It goes green, the feature is broken, and the test reported safety. The
 * concrete instance is recorded in `KuaishouMsgAccessRoutingTest`: its first version asserted on a fake
 * class carrying neither host's field family, so both readers honestly returned "" and the test passed
 * with the dispatch arm DELETED.
 *
 * So every assertion below calls `QQMsgAccess`, through a `Resolver` whose host loader CANNOT load
 * anything -- and the fixtures in this repository occupy the REAL QQ class names
 * (`com.tencent.qqnt.kernel.nativeinterface.MsgRecord`, `...MsgElement`, `...TextElement`,
 * `com.tencent.mobileqq.aio.msg.AIOMsgItem`), so they are the classes the production reflection resolves
 * on the test classpath. Nothing here is a stand-in for the reflection path: `content()` really does
 * `Class.forName(MSG_CLASS)` -> `getElements()` -> `getElementType()` -> `getTextElement()` ->
 * `getContent()`, and the String that comes back is the String the module would store.
 *
 * ============================== THE FIRST-CALL RULE, EXPLICITLY TESTED =======================
 *
 * 必看.md: when resolution and invocation are two separate paths, a resolver can report 100% success
 * while the FIRST call failed, because nothing was cached -- the symptom is a pipeline that works
 * "sometimes". [firstCallIsNotSpecialCased] therefore asserts that call ONE produces the correct answer
 * on a FRESH reader instance, not that call two recovers. Every other test also builds its own instance,
 * so no assertion can be satisfied by a warmed-up cache.
 *
 * ============================== WHAT THESE TESTS DO NOT PROVE ================================
 *
 * They prove the READER: field selection, the element chain, the classification and every degradation
 * branch. They do NOT prove that QQ 9.3.65 populates these fields on a live message, that the UI hands
 * over an `AIOMsgItem` (only a device run shows that), or that the numeric constants match this build --
 * those come from the dex dumps and the on-device probe, and `QQ_MSG_ACCESS_NOTES.md` lists them as the
 * open items. Stated here so nobody mistakes a green run for device evidence.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQMsgAccessTest {

    /**
     * A loader that resolves NOTHING, so the reader's host-loader lookup always fails and every route has
     * to go through the classpath / exact-name fallbacks.
     *
     * This is the DELIBERATE condition, not an accident: it is the same loader `SoulMsgAccessNoGuessTest`
     * uses, and it is what makes the fixtures authoritative -- if a fixture ever stopped occupying the
     * real name, `Class.forName` would throw and the tests below would fail loudly instead of quietly
     * accepting a stand-in.
     */
    private class EmptyLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)

        override fun findClass(name: String): Class<*> =
            throw ClassNotFoundException(name)
    }

    /** A FRESH reader per test, so no assertion can be satisfied by a cached accessor. */
    private fun access(): QQMsgAccess = QQMsgAccess(Resolver(EmptyLoader(), "", File(".")))

    /**
     * A second reader whose loader CAN see the fixtures -- i.e. the loader the reader takes on a real
     * device, where `resolver.classLoader()` is the host loader and the QQ classes are really loadable.
     *
     * WHY BOTH ARE TESTED. With [EmptyLoader] every lookup falls back to `obj.javaClass` plus the
     * exact-name test, which is the DEGRADED path. On a device the `isAssignableFrom` branch is the live
     * one, and a subclass of `MsgRecord` would take it. So the same assertions are run under both
     * conditions: a reader that only worked in one of them would be a reader that silently changes
     * behaviour depending on whether the host class happened to be loaded yet.
     */
    private class FixtureLoader : ClassLoader(QQMsgAccessTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            super.loadClass(name, resolve)
    }

    private fun accessWithLoadableHost(): QQMsgAccess =
        QQMsgAccess(Resolver(FixtureLoader(), "", File(".")))

    // ------------------------------------------------------------------ fixtures

    private fun record(): MsgRecord = MsgRecord()

    /** An element carrying a real `TextElement` with [body]. */
    private fun textElement(body: String): MsgElement {
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_TEXT
        val t = TextElement()
        t.content = body
        e.textElement = t
        return e
    }

    /** An element of kind [type] with NO sub-object, i.e. a non-text message. */
    private fun bareElement(type: Int): MsgElement {
        val e = MsgElement()
        e.elementType = type
        return e
    }

    /** A gray-tip element that really carries a `RevokeElement`, i.e. a recall. */
    private fun revokeElement(): MsgElement {
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_GRAYTIP
        val g = GrayTipElement()
        g.subElementType = 1
        g.revokeElement = RevokeElement()
        e.grayTipElement = g
        return e
    }

    // ================================================================== PRECONDITIONS
    //
    // Asserted FIRST and separately, because every other test's meaning depends on them. If a fixture
    // stopped occupying the real QQ name, the tests below would still "pass" against the wrong object.

    @Test
    fun `the fixtures really occupy the qq class names`() {
        assertEquals(
            "the record fixture must occupy the REAL name or the reader's Class.forName fails and " +
                "every test below exercises the degraded path instead of the real one",
            QQEnvelope.MSG_CLASS,
            record().javaClass.name,
        )
        assertEquals(
            QQEnvelope.ELEMENT_CLASS,
            bareElement(QQEnvelope.ELEM_PIC).javaClass.name,
        )
        assertEquals(
            QQEnvelope.TEXT_ELEMENT_CLASS,
            TextElement().javaClass.name,
        )
        assertEquals(
            QQEnvelope.AIO_ITEM_CLASS,
            AIOMsgItem().javaClass.name,
        )
    }

    @Test
    fun `the STATIC o accessor is never used as a record getter`() {
        // ============================ THE TRAP TEST ============================
        // The real `AIOMsgItem#o()` is `acc=0x1019` = STATIC + FINAL + SYNTHETIC with body
        // `sget-object i1` (verified by `_tools/qq/t14_field_write_scan.py`). It LOOKS like a second
        // record getter and returns a well-formed MsgRecord -- but it is the STATIC `i1`, assigned once
        // in `<clinit>` and therefore SHARED by every message in the process.
        //
        // This test would have caught the earlier revision, which listed BOTH `getMsgRecord` and `o` as
        // candidates: with `getMsgRecord` present nothing looked wrong, but the moment it missed, `o`
        // returned a shared decoy. The consequences are silent -- blank analysis text, and every
        // history row collapsing onto one key because a shared empty record's getMsgId() is 0.
        //
        // The fixture reproduces the trap faithfully (`@JvmStatic fun o()` returning static `i1`), so a
        // regression fails HERE, loudly, instead of on a device.
        AIOMsgItem.i1 = null
        try {
            val poison = MsgRecord()
            poison.elements.add(textElement("POISON-shared-static"))
            poison.msgId = 999L
            poison.peerUid = "u_poison"
            AIOMsgItem.i1 = poison

            val real = MsgRecord()
            real.elements.add(textElement("the real message"))
            real.msgId = 7L
            real.peerUid = "u_real"

            val item = AIOMsgItem()
            item.e = real

            val a = access()
            assertEquals("the reader must read the INSTANCE field, not the static trap",
                "the real message", a.content(item))
            assertNotEquals("the static decoy must never be returned", "POISON-shared-static",
                a.content(item))
            assertNotEquals(999L, a.msgId(item))
            assertEquals(7L, a.msgId(item))
            assertNotEquals("u_poison", a.talker(item))

            // And the trap must be inert even when the instance record is ABSENT: the reader has to
            // report a failure rather than quietly adopt the shared object.
            val empty = AIOMsgItem()
            empty.e = null
            assertNull("with no instance record the wrapper must yield null, NOT the static decoy",
                access().unwrap(empty))
            assertFalse(access().isMessage(empty))
        } finally {
            AIOMsgItem.i1 = null
        }
    }

    @Test
    fun `a static no-arg method is rejected rather than invoked with a discarded receiver`() {
        // The general rule behind the test above, stated as its own property. `findNoArg` must reject
        // STATIC methods, because `Method.invoke(receiver)` on a static method silently DISCARDS the
        // receiver -- it cannot fail, so no downstream check can notice.
        //
        // Verified on the fixture directly, so this is not a claim about production behaviour alone:
        // the static creates a well-formed MsgRecord, and a reader that accepted it would report a
        // healthy message where there is none.
        AIOMsgItem.i1 = null
        try {
            val shared = MsgRecord()
            shared.elements.add(textElement("shared"))
            AIOMsgItem.i1 = shared

            val empty = AIOMsgItem()   // instance record deliberately left null
            assertNull(access().unwrap(empty))
        } finally {
            AIOMsgItem.i1 = null
        }
    }

    @Test
    fun `the host loader really cannot see qq classes`() {
        // If this ever became loadable, the reader would take the assignability branch and the
        // exact-name fallback would go untested -- and the tests below would prove less than they claim.
        var loadable = true
        try {
            Class.forName(QQEnvelope.MSG_CLASS, false, EmptyLoader())
        } catch (_: Throwable) {
            loadable = false
        }
        assertFalse("the empty loader must not resolve QQ classes", loadable)
    }

    @Test
    fun `the reader behaves identically with a loadable and an unloadable host class`() {
        // THE DEGRADATION MUST NOT CHANGE THE ANSWER. With EmptyLoader the reader uses the exact-name
        // test; with FixtureLoader it uses `isAssignableFrom`. Both are real device conditions (the QQ
        // class IS loaded on a device, and may NOT be loaded yet very early in process start), so an
        // answer that differs between them would be a reader whose behaviour depends on hook timing.
        val rec = record()
        rec.elements.add(textElement("same either way"))
        rec.peerUid = "u_x"
        rec.sendType = 1
        rec.chatType = QQEnvelope.CHAT_GROUP

        val unloadable = access()
        val loadable = accessWithLoadableHost()
        assertEquals(unloadable.content(rec), loadable.content(rec))
        assertEquals("same either way", loadable.content(rec))
        assertEquals(unloadable.talker(rec), loadable.talker(rec))
        assertEquals(unloadable.isSend(rec), loadable.isSend(rec))
        assertEquals(unloadable.isGroup(rec), loadable.isGroup(rec))
        assertEquals(unloadable.isText(rec), loadable.isText(rec))
    }

    @Test
    fun `a SUBCLASS of the record is accepted under BOTH loaders`() {
        // ============================ THIS TEST FOUND A REAL BUG ============================
        // It failed the first time it ran, and the failure was in the PRODUCTION code, not the test.
        //
        // QQ does not hand a hook a bare `MsgRecord`. The UI hands over an `AIOMsgItem` whose record is a
        // subclass, and QFix generates further subclasses at runtime. With the host class UNLOADABLE the
        // reader falls back to an exact-name match -- and `o.javaClass.name == MSG_CLASS` is FALSE for a
        // subclass. So every real message was rejected as "not a QQ message": a TOTAL, silent failure
        // whose only symptom is an empty history and a log line blaming the host.
        //
        // The bug could only be seen with the host loader blind, which is why the "unloadable" condition
        // is worth testing at all -- and why the assertion below is made under BOTH loaders rather than
        // only the convenient one. (Same family as the Douyin reader's recorded defect: a direct
        // equality test against the host class rejected every message that arrived as a `ProxyMessage`.)
        val sub = object : MsgRecord() {}
        sub.elements.add(textElement("subclass body"))
        sub.peerUid = "u_sub"

        assertEquals("subclass body", access().content(sub))
        assertEquals("subclass body", accessWithLoadableHost().content(sub))
        assertEquals("u_sub", access().talker(sub))
        assertTrue(access().isMessage(sub))
    }

    // ================================================================== the text path

    @Test
    fun `a text message yields the text element content`() {
        val a = access()
        val rec = record()
        rec.elements.add(textElement("hello from QQ"))
        assertEquals("hello from QQ", a.content(rec))
        assertEquals(QQEnvelope.Reason.OK, a.lastReason)
        assertTrue("a real chat line must count as text", a.isText(rec))
    }

    @Test
    fun `the body comes from the TextElement and NOT from a bare string on the element`() {
        // THE TRAP. `MsgElement.outerText` is a String hanging directly off the element; the fixture
        // populates it with a marker. A reader that grabbed "some String on the element" would return the
        // marker here. The production path must go through getTextElement().getContent().
        val a = access()
        val rec = record()
        val e = textElement("real body")
        assertEquals("TRAP-not-a-qq-field", e.outerText)
        rec.elements.add(e)
        val got = a.content(rec)
        assertEquals("real body", got)
        assertNotEquals("must not read the element-level String", "TRAP-not-a-qq-field", got)
    }

    @Test
    fun `the text element is found even when it is not the first element`() {
        // VERIFIED SHAPE, not a hypothetical: an @-mention or a reply attaches ITS OWN element to the
        // same message, so indexing elements[0] finds no text. The reader must scan.
        val a = access()
        val rec = record()
        rec.elements.add(bareElement(QQEnvelope.ELEM_REPLY))
        rec.elements.add(textElement("body after the reply wrapper"))
        assertEquals("body after the reply wrapper", a.content(rec))
        assertTrue(a.isText(rec))
    }

    @Test
    fun `an empty text element is NOT text`() {
        val a = access()
        val rec = record()
        rec.elements.add(textElement(""))
        val got = a.content(rec)
        assertFalse("an empty body is not a chat line", a.isText(rec))
        assertEquals(QQEnvelope.Reason.NON_TEXT, a.lastReason)
        assertNotEquals("", got)
    }

    @Test
    fun `the first call on a fresh reader is already correct`() {
        // THE FIRST-CALL RULE (必看.md). Nothing is warmed up: this instance has never resolved a member
        // before, and the very first content() call must produce the answer.
        val rec = record()
        rec.elements.add(textElement("first call body"))
        val fresh = access()
        assertEquals("first call body", fresh.content(rec))
        assertEquals(QQEnvelope.Reason.OK, fresh.lastReason)
    }

    @Test
    fun `repeated reads stay correct and do not degrade`() {
        // Idempotence: the reader is called on every row of every bind, so a cached accessor that goes
        // stale, or a reason that drifts, would show up as the SECOND read differing from the first.
        val a = access()
        val rec = record()
        rec.elements.add(textElement("stable"))
        repeat(50) {
            assertEquals("stable", a.content(rec))
            assertEquals(QQEnvelope.Reason.OK, a.lastReason)
        }
        assertTrue(a.isText(rec))
    }

    // ================================================================== non-text degradation

    @Test
    fun `a picture message degrades to a placeholder and is not text`() {
        val a = access()
        val rec = record()
        rec.elements.add(bareElement(QQEnvelope.ELEM_PIC))
        val got = a.content(rec)
        assertEquals(QQEnvelope.placeholderForType(QQEnvelope.ELEM_PIC), got)
        assertEquals(QQEnvelope.Reason.NON_TEXT, a.lastReason)
        assertFalse("a placeholder is NOT a chat line", a.isText(rec))
        assertNotEquals("must never be the empty string", "", got)
    }

    @Test
    fun `every covered element kind yields a readable non-empty placeholder`() {
        // The task's requirement: non-text messages give a READABLE placeholder, and never crash or
        // return "". Iterating the whole table is the point -- a single missing entry would be a row that
        // silently stores nothing.
        val kinds = listOf(
            QQEnvelope.ELEM_PIC, QQEnvelope.ELEM_PTT, QQEnvelope.ELEM_VIDEO,
            QQEnvelope.ELEM_FILE, QQEnvelope.ELEM_FACE, QQEnvelope.ELEM_MARKETFACE,
            QQEnvelope.ELEM_GRAYTIP, QQEnvelope.ELEM_ARKSTRUCT, QQEnvelope.ELEM_REPLY,
            QQEnvelope.ELEM_WALLET, QQEnvelope.ELEM_MULTIFORWARD, QQEnvelope.ELEM_MARKDOWN,
            QQEnvelope.ELEM_SHARELOCATION, QQEnvelope.ELEM_GIPHY, QQEnvelope.ELEM_AVRECORD,
        )
        for (k in kinds) {
            val a = access()
            val rec = record()
            rec.elements.add(bareElement(k))
            val got = a.content(rec)
            assertTrue("elementType=" + k + " must give a non-empty placeholder", got.isNotEmpty())
            assertEquals(
                "elementType=" + k + " must use its own placeholder",
                QQEnvelope.placeholderForType(k),
                got,
            )
            assertFalse("elementType=" + k + " must not count as text", a.isText(rec))
        }
    }

    @Test
    fun `an unknown element kind degrades to the generic placeholder, not to an exception`() {
        val a = access()
        val rec = record()
        rec.elements.add(bareElement(4242))
        val got = a.content(rec)
        assertEquals(QQEnvelope.UNKNOWN_NON_TEXT, got)
        assertEquals(QQEnvelope.Reason.NON_TEXT, a.lastReason)
        assertFalse(a.isText(rec))
    }

    @Test
    fun `a message with no elements at all degrades without throwing`() {
        val a = access()
        val rec = record()
        val got = a.content(rec)
        assertEquals(QQEnvelope.UNKNOWN_NON_TEXT, got)
        assertEquals(QQEnvelope.Reason.NON_TEXT, a.lastReason)
        assertFalse(a.isText(rec))
    }

    @Test
    fun `a market face whose content is an envelope never has that content read at all`() {
        // THE 20 KB DOUYIN BUG, in its QQ form, and the FIRST of two defences.
        //
        // A market face carries its own JSON INSIDE `TextElement.content`, so a reader that grabbed "the
        // first string on the element" would store a JSON blob in the user's history.
        //
        // The element kind is 11, NOT 1, so the loop in `content()` -- which reads the TextElement only
        // when `elementType == ELEM_TEXT` -- never looks inside it. The payload is never even read, which
        // is strictly stronger than reading it and then rejecting it.
        //
        // NOTE ON WHAT THIS TEST ASSERTED FIRST. The original version expected reason == ENVELOPE. That
        // was WRONG, and running it is how the mistake surfaced: the reason is NON_TEXT, because the
        // guard that fires here is the element-kind gate, not the envelope scan. The assertion was
        // over-specified -- it demanded that a particular DEFENCE be the one to fire, when the correct
        // requirement is simply that the payload never reaches the store. [the text element gate and the
        // envelope scan are tested separately below] pins the other defence so neither goes unexercised.
        val a = access()
        val rec = record()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_MARKETFACE
        val t = TextElement()
        t.content = "{\"emojiId\":\"123\",\"name\":\"a face\",\"m\":1}"
        e.textElement = t
        rec.elements.add(e)
        val got = a.content(rec)
        assertFalse("a market face must not be stored as a chat line", a.isText(rec))
        assertEquals(QQEnvelope.Reason.NON_TEXT, a.lastReason)
        assertEquals(QQEnvelope.placeholderForType(QQEnvelope.ELEM_MARKETFACE), got)
        assertFalse("the raw JSON must not leak through", got.contains("emojiId"))
    }

    @Test
    fun `an envelope-shaped body on a TEXT element is refused by the envelope scan`() {
        // THE SECOND DEFENCE, pinned separately so it cannot rot into dead code.
        //
        // The element kind IS text here, so the gate in the previous test does not apply and the payload
        // really is a candidate chat line. This is the case the envelope scan exists for: elementType 1
        // is not a guarantee that the content is prose, and some QQ builds put structured payloads in
        // `TextElement.content`.
        //
        // Without this test the scan could be deleted and every other test would still pass -- which is
        // precisely the "green but meaningless" shape 必看.md warns about.
        val a = access()
        val rec = record()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_TEXT
        val t = TextElement()
        t.content = "{\"emojiId\":\"123\",\"name\":\"a face\"}"
        e.textElement = t
        rec.elements.add(e)
        val got = a.content(rec)
        assertFalse("an envelope must not be stored as a chat line", a.isText(rec))
        assertEquals(QQEnvelope.Reason.ENVELOPE, a.lastReason)
        assertFalse("the raw JSON must not leak through", got.contains("emojiId"))
        assertNotEquals("", got)
    }

    @Test
    fun `a TEXT element carrying non-envelope prose is stored normally`() {
        // The envelope scan must not be so eager that it eats ordinary messages. Paired with the test
        // above: same element kind, same path, only the CONTENT differs -- so a scan that refused
        // everything would fail here.
        val a = access()
        val rec = record()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_TEXT
        val t = TextElement()
        t.content = "the emojiId field is what I was asking about"
        e.textElement = t
        rec.elements.add(e)
        assertEquals("the emojiId field is what I was asking about", a.content(rec))
        assertTrue(a.isText(rec))
    }

    @Test
    fun `a user who literally typed json still gets their text stored`() {
        // THE OPPOSITE DIRECTION, and the reason the envelope test is not just `startsWith("{")`. People
        // do send JSON to each other; rewriting that into a placeholder destroys their message, which is
        // worse than the bug the check prevents.
        val a = access()
        val rec = record()
        val typed = "{\"a\":1,\"b\":[2,3]}"
        rec.elements.add(textElement(typed))
        assertEquals(typed, a.content(rec))
        assertEquals(QQEnvelope.Reason.OK, a.lastReason)
        assertTrue(a.isText(rec))
    }

    @Test
    fun `a word that merely looks like an envelope key is still user text`() {
        // A structural requirement, tested: the key must be a real JSON KEY (quoted, followed by a
        // colon). A line that merely CONTAINS the word must be stored as typed.
        val a = access()
        val rec = record()
        rec.elements.add(textElement("the emojiId is what I was asking about"))
        assertEquals("the emojiId is what I was asking about", a.content(rec))
        assertTrue(a.isText(rec))
    }

    // ================================================================== recall

    @Test
    fun `a recalled message is never stored, even with a stale text element present`() {
        // QQ keeps the original elements alongside the revoke element (for the undo affordance), so a
        // reader that took the first text it found would make a withdrawn line reappear in the history.
        val a = access()
        val rec = record()
        rec.elements.add(textElement("this was withdrawn"))
        rec.elements.add(revokeElement())
        assertTrue("a real RevokeElement must be recognised", a.isRecalled(rec))
        assertFalse("a recalled line must not be stored", a.isText(rec))
        assertEquals(QQEnvelope.Reason.RECALLED, a.lastReason)
        assertEquals(QQEnvelope.RECALLED_PLACEHOLDER, a.content(rec))
    }

    @Test
    fun `a gray tip without a revoke element is not a recall`() {
        // The anchor is the OBJECT, not the number. A gray tip that is an ordinary system notice must not
        // be reported as a recall -- doing so would hide real system lines.
        val a = access()
        val rec = record()
        val e = MsgElement()
        e.elementType = QQEnvelope.ELEM_GRAYTIP
        e.grayTipElement = GrayTipElement()   // no revokeElement
        rec.elements.add(e)
        assertFalse(a.isRecalled(rec))
    }

    // ================================================================== identity: self vs other

    @Test
    fun `qq's own sendType short-circuit answers self versus other`() {
        // Values verified from MsgConstant (decoded from dex static_values) and from the disassembly of
        // com.tencent.mobileqq.aio.utils.as#j(MsgRecord)Z.
        val a = access()

        val mine = record()
        mine.sendType = 1 // KSENDTYPESELF
        assertTrue("sendType 1 is SELF", a.isSend(mine))

        val theirs = record()
        theirs.sendType = 0 // KSENDTYPERECV
        assertFalse("sendType 0 is RECV", a.isSend(theirs))

        // The asymmetry that is easy to get backwards: a message I sent from another device, or one I
        // forwarded, is still MINE -- and this module's prompt splits the conversation by who said it.
        val fromPc = record()
        fromPc.sendType = 2 // KSENDTYPEOTHERDEVICE
        assertTrue("a message I sent from my PC is still mine", a.isSend(fromPc))

        val forwarded = record()
        forwarded.sendType = 4 // KSENDTYPESELFFORWARD
        assertTrue("a message I forwarded is still mine", a.isSend(forwarded))
    }

    @Test
    fun `an undecidable sendType degrades to false rather than guessing`() {
        // 3 (LOCAL) / 5 (SELFMULTIFORWARD) / 6 (DROPPED) are NOT short-circuited by QQ's own rule, and my
        // own uid is unreadable in a JVM test, so the honest answer is false -- never a guess.
        val a = access()
        for (st in listOf(3, 5, 6, 99)) {
            val rec = record()
            rec.sendType = st
            assertFalse("sendType=" + st + " must degrade to false", a.isSend(rec))
        }
    }

    @Test
    fun `direction is decided by uid when sendType cannot answer`() {
        val a = access()
        // sendType 3 is not short-circuited and my uid is unavailable in this JVM, so the answer stays
        // false even when the uid matches mine -- and lastReason-independent, no exception. This pins the
        // DEGRADED behaviour so a future change that starts guessing is caught.
        val rec = record()
        rec.sendType = 3
        rec.senderUid = "u_mine"
        assertFalse(a.isSend(rec))
    }

    // ================================================================== group vs private

    @Test
    fun `chatType decides group versus private, and -1 is not a group`() {
        val a = access()
        val c2c = record()
        c2c.chatType = QQEnvelope.CHAT_C2C
        assertFalse("C2C is not a group", a.isGroup(c2c))

        val group = record()
        group.chatType = QQEnvelope.CHAT_GROUP
        assertTrue("chatType 2 is a group", a.isGroup(group))

        val disc = record()
        disc.chatType = 3
        assertTrue("a discussion has more than two participants", a.isGroup(disc))

        // UNREADABLE must not be silently treated as a private chat: mislabelling a group as private puts
        // no nickname in front of the lines and changes what the model is told about the conversation.
        assertEquals("an unreadable chatType is -1", -1, a.chatTypeOf(null))
        assertFalse(a.isGroup(null))
    }

    @Test
    fun `the conversation key prefers peerUid and falls back to peerUin`() {
        val a = access()
        val withUid = record()
        withUid.peerUid = "u_abc"
        withUid.peerUin = 10001L
        assertEquals("u_abc", a.talker(withUid))

        val withoutUid = record()
        withoutUid.peerUin = 10001L
        assertEquals("10001", a.talker(withoutUid))
    }

    @Test
    fun `a message with no conversation key yields an empty talker and does not throw`() {
        // A blank key makes the caller reject the message LOUDLY. A guessed key would split one
        // conversation across several history files, and the store cannot repair that afterwards.
        val a = access()
        val rec = record() // both peerUid and peerUin left at their zero values
        assertEquals("", a.talker(rec))
    }

    @Test
    fun `peerName is a display value and is never used as the conversation key`() {
        val a = access()
        val rec = record()
        rec.peerName = "Some Group"
        assertEquals("a display name must not become the store key", "", a.talker(rec))
        assertEquals("Some Group", a.chatName(rec))
    }

    // ================================================================== sender name

    @Test
    fun `the sender name follows qq's own preference order`() {
        // The order comes from disassembling QQ's own renderer
        // (com.tencent.qqnt.chathistory.util.MsgRecordExKt#a), not from guessing at the field names.
        val a = access()

        val all = record()
        all.sendMemberName = "the card"
        all.sendNickName = "the nick"
        all.sendRemarkName = "the remark"
        all.senderUin = 2222L
        assertEquals("the card wins when present", "the card", a.senderName(all))

        val noCard = record()
        noCard.sendNickName = "the nick"
        noCard.sendRemarkName = "the remark"
        noCard.senderUin = 2222L
        assertEquals("the nick is the next step", "the nick", a.senderName(noCard))

        val onlyRemark = record()
        onlyRemark.sendRemarkName = "the remark"
        onlyRemark.senderUin = 2222L
        assertEquals("the remark is the last name step", "the remark", a.senderName(onlyRemark))
    }

    @Test
    fun `a missing sender name degrades to the uin and never invents one`() {
        // The task's explicit rule: when the group nickname needs a second query that cannot be driven
        // from here, degrade to the uin rather than throwing or fabricating a name.
        val a = access()
        val rec = record()
        rec.senderUin = 2222L
        assertEquals("2222", a.senderName(rec))
        assertEquals(2222L, a.senderUin(rec))
    }

    @Test
    fun `a sender with neither name nor uin degrades to empty, not to a placeholder name`() {
        val a = access()
        val rec = record()
        assertEquals("", a.senderName(rec))
        assertEquals("no invented name", false, a.senderName(rec).contains("[", ignoreCase = true))
    }

    // ================================================================== id and time

    @Test
    fun `msgId and msgSeq are read as primitives and reported as 0 when absent`() {
        val a = access()
        val rec = record()
        rec.msgId = 123456789L
        rec.msgSeq = 42L
        assertEquals(123456789L, a.msgId(rec))
        assertEquals(42L, a.msgSeq(rec))

        val empty = record()
        assertEquals(0L, a.msgId(empty))
        assertEquals(0L, a.msgSeq(empty))
        assertEquals("QQ exposes no separate server id on the verified fields", 0L, a.msgSvrId(empty))
    }

    @Test
    fun `msgTime wins over timeStamp and seconds are normalised to millis`() {
        val a = access()

        val ms = record()
        ms.msgTime = 1_700_000_000_000L
        ms.timeStamp = 5L
        assertEquals(1_700_000_000_000L, a.createTime(ms))

        // A SECONDS value would be rejected by the shared `< 1e12` epoch gate in
        // AnalysisController.onLiveMessage, dropping EVERY message loudly but totally -- so the
        // normalisation is load-bearing, not cosmetic.
        val seconds = record()
        seconds.msgTime = 1_700_000_000L
        assertEquals(1_700_000_000_000L, a.createTime(seconds))

        val onlyServer = record()
        onlyServer.timeStamp = 1_700_000_000_000L
        assertEquals("timeStamp is the fallback when msgTime is absent", 1_700_000_000_000L, a.createTime(onlyServer))
    }

    @Test
    fun `a timestamp-less message reports 0 rather than a fabricated time`() {
        val a = access()
        assertEquals(0L, a.createTime(record()))
    }

    // ================================================================== the msgType field

    @Test
    fun `typeOf reports the message-level msgType and -1 when unknown`() {
        val a = access()
        val rec = record()
        rec.msgType = 2 // KMSGTYPEMIX
        rec.subMsgType = 5
        assertEquals(2, a.typeOf(rec))
        assertEquals(5, a.subTypeOf(rec))

        val unknown = record()
        // Left at the fixture's 0 -- which is KMSGTYPEUNKNOWN's real value, so 0 is a VALID answer here
        // and is passed through rather than normalised to -1. A non-message gives -1.
        assertEquals(0, a.typeOf(unknown))
        assertEquals(-1, a.typeOf("not a message"))
    }

    // ================================================================== the UI wrapper route

    @Test
    fun `an AIOMsgItem wrapper is unwrapped to its MsgRecord`() {
        // THE UI PATH. QQ hands a hook the ITEM, not the record, and this is the route whose failure
        // looks exactly like "the user never had any text messages".
        val a = access()
        val rec = record()
        rec.elements.add(textElement("body via the wrapper"))
        rec.peerUid = "u_group"
        rec.sendType = 1

        val item = AIOMsgItem()
        item.e = rec

        assertTrue("the wrapper must be recognised as a message", a.isMessage(item))
        assertNotNull("unwrap must reach the record", a.unwrap(item))
        assertEquals("body via the wrapper", a.content(item))
        assertEquals("u_group", a.talker(item))
        assertTrue(a.isSend(item))
        assertEquals(QQEnvelope.AIO_ITEM_CLASS, item.javaClass.name)
    }

    @Test
    fun `an object that is neither a record nor an item is rejected, not coerced`() {
        // A package-prefix test was rejected on purpose (see SoulMsgAccess): accepting a non-message
        // pushes it through the whole pipeline and into the store, and an empty feed is safer than a
        // fabricated one.
        val a = access()
        assertFalse(a.isMessage("a string"))
        assertFalse(a.isMessage(42))
        assertFalse(a.isMessage(Any()))
        assertNull(a.unwrap(Any()))
        assertEquals("", a.content(Any()))
        assertEquals(QQEnvelope.Reason.NOT_A_MESSAGE, a.lastReason)
        assertEquals("", a.talker(Any()))
        assertEquals("", a.senderName(Any()))
    }

    @Test
    fun `the reader never throws on a null message`() {
        // It is called from hooks on the host's main thread; an exception here would crash QQ.
        val a = access()
        assertFalse(a.isMessage(null))
        assertNull(a.unwrap(null))
        assertEquals("", a.content(null))
        assertEquals("", a.talker(null))
        assertEquals("", a.chatName(null))
        assertEquals("", a.senderName(null))
        assertEquals(0L, a.senderUin(null))
        assertFalse(a.isSend(null))
        assertFalse(a.isText(null))
        assertFalse(a.isRecalled(null))
        assertFalse(a.isGroup(null))
        assertEquals(0L, a.msgId(null))
        assertEquals(0L, a.createTime(null))
        assertEquals(-1, a.typeOf(null))
        assertEquals(-1, a.chatTypeOf(null))
        assertNull(a.messageAt(null, 0))
        assertNull(a.messageFromView(null))
        assertEquals(emptyList<Any>(), a.messagesInAdapter(null))
        assertEquals(emptyList<Any>(), a.selectedMessages(null))
        assertEquals("" to -1, a.sessionOf(null))
        assertEquals("" to -1, a.currentSession(null))
        assertFalse(a.looksLikeMessageList(null))
    }

    // ================================================================== multi-select

    @Test
    fun `the multi-select set reports EMPTY rather than a guess`() {
        // t14's requirement: the selection set is NOT established (t11 owns that), so it must be an
        // explicit, visible, empty answer. A caller showing "0 selected" is recoverable; a caller that
        // analyses the WRONG eight messages is not.
        val a = access()
        assertEquals(emptyList<Any>(), a.selectedMessages(null))
        assertEquals(emptyList<Any>(), a.selectedMessages(Any()))
    }
}
