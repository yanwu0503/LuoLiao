package com.luoliaoo.wechat

import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.hook.KuaishouEnvelope
import com.luoliaoo.wechat.hook.MsgAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The R1 regression guard: [MsgAccess] must route KUAISHOU to the Kuaishou reader.
 *
 * ============================== WHY THIS IS THE MOST IMPORTANT TEST FOR KUAISHOU ==============
 *
 * `MsgAccess` is a host-agnostic facade, and until this revision it had NO Kuaishou arm. A `KwaiMsg`
 * therefore fell through to the WECHAT reader, which looks for the field `field_content` and then probes
 * WeChat's obfuscated accessors (`j`, `t0`, `u0`, `y0`, `z0`, `U1`). None of those exist on the real
 * `KwaiMsg` -- it has `text`, `sender`, `target` and not one `field_*` member -- so `content()` returned
 * `""`.
 *
 * The failure that produces is the WORST KIND, and it is not hypothetical: it already happened once on
 * Soul. The 分析 menu item APPEARS normally and the tap does nothing, because
 * `AnalysisController.startAnalysisFor` sees blank text and reports 「未找到可分析的文本消息」; the live
 * history feed rejects every message at its blank-text gate, so the store stays empty -- while EVERY
 * LOG LINE LOOKS HEALTHY. Nothing says "a reader was missing"; it just looks like the user never had any
 * text messages.
 *
 * A test is the only defence, because the symptom is indistinguishable from normal idle behaviour.
 *
 * ============================== HOW THE DISPATCH IS PROVEN ====================================
 *
 * DISPATCH reads `HostApp.current`, so each test sets the host and compares the facade's answer against
 * what the OTHER host's reader would have produced for the same object.
 *
 * The fixture `com.kwai.imsdk.msg.KwaiMsg` (test sources only) carries BOTH field families -- Kuaishou's
 * `text`/`target` AND WeChat's `field_content`/`field_talker`/`field_isSend`/`typeValue`. So the two
 * readers are guaranteed to DISAGREE, and every assertion below is phrased against the WeChat value.
 *
 * WHY THIS MATTERS -- the first version of this test was a BLUFF, and it was caught by mutation:
 * it used a fake class carrying neither family, so both readers honestly returned `""` and the test
 * passed even with the `isKuaishou` arms DELETED from `MsgAccess`. A test that cannot fail is worse than
 * no test, because it reports safety. The fixture above is the correction; the mutation procedure is
 * recorded in the task report so the next reader can re-run it.
 *
 * ============================== WHAT THIS STILL CANNOT PROVE ==================================
 *
 * The fixture is a SHAPE stand-in, not the real class: the real `KwaiMsg` carries ~20 fields (including
 * the `contentBytes`/`extra`/`localExtra` protobuf payloads) and a subclass hierarchy (`TextMsg`,
 * `ImageMsg`, ...) that the test JVM cannot load. So this proves the DISPATCH and the neighbour-host
 * isolation; the real field READS are covered by the on-device probe in `KuaishouMsgAccess.probe`.
 * Stated explicitly so nobody mistakes this for device evidence.
 *
 * `HostApp.current` is restored via `setCurrent` in a `finally` so one failure cannot poison the others.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouMsgAccessRoutingTest {

    /** A loader that resolves nothing, so no host class is reachable from the test JVM. */
    private class EmptyLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw ClassNotFoundException(name)

        override fun findClass(name: String): Class<*> =
            throw ClassNotFoundException(name)
    }

    private fun access(): MsgAccess = MsgAccess(Resolver(EmptyLoader(), "", File(".")))

    /** The discriminating fixture: Kuaishou fields AND WeChat fields on one object. */
    private fun kwaiMsg(): com.kwai.imsdk.msg.KwaiMsg = com.kwai.imsdk.msg.KwaiMsg()

    private inline fun <T> asHost(host: HostApp, block: () -> T): T {
        val prev = HostApp.current
        HostApp.setCurrent(host)
        try {
            return block()
        } finally {
            HostApp.setCurrent(prev)
        }
    }

    // ------------------------------------------------------------------ fixtures self-check

    @Test
    fun `the fixture really carries both field families`() {
        // PRECONDITION. If this fixture ever lost one family, the assertions below would silently stop
        // discriminating and go back to being a bluff -- which is exactly what happened the first time.
        val m = kwaiMsg()
        assertEquals("kuaishou-body", m.text)
        assertEquals("wechat-body", m.field_content)
        assertEquals("kuaishou-talker", m.target)
        assertEquals("wechat-talker", m.field_talker)
        assertNotEquals(
            "the two readers MUST disagree on content or this whole test is vacuous",
            m.text,
            m.field_content,
        )
        assertNotEquals(
            "the two readers MUST disagree on talker or this whole test is vacuous",
            m.target,
            m.field_talker,
        )
    }

    // ------------------------------------------------------------------ the R1 guard

    @Test
    fun `content does NOT come from the wechat field when the host is kuaishou`() {
        // THE R1 regression guard. Without the Kuaishou arm this returned the WeChat value
        // "wechat-body"; with it, the Kuaishou body ("kuaishou-body") is read instead. Deleting the arm
        // makes this fail -- verified by mutation.
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().content(msg) }
        assertNotEquals("must not read field_content", "wechat-body", got)
        assertEquals("kuaishou-body", got)
    }

    @Test
    fun `talker does NOT come from the wechat field when the host is kuaishou`() {
        // A wrong talker is worse than a blank one: the store groups by talker, so the WeChat value
        // would file every Kuaishou message under a bogus conversation that never exists.
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().talker(msg) }
        assertNotEquals("must not read field_talker", "wechat-talker", got)
        assertEquals("kuaishou-talker", got)
    }

    @Test
    fun `isSend does NOT come from the wechat field when the host is kuaishou`() {
        // field_isSend is TRUE and the Kuaishou reader honestly answers false (its sender field is
        // unresolved -- see F1). The values differ, so the arms cannot be confused.
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().isSend(msg) }
        assertFalse("the Kuaishou reader must not read field_isSend", got)
    }

    @Test
    fun `msgSvrId is 0 for kuaishou and NOT the wechat server id`() {
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().msgSvrId(msg) }
        assertEquals("Kuaishou has no server id", 0L, got)
        assertNotEquals("must not read field_msgSvrId", 222L, got)
    }

    @Test
    fun `msgId reads the kuaishou id and not the wechat one`() {
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().msgId(msg) }
        assertEquals("the Kuaishou `id` field must be read", 999L, got)
        assertNotEquals("must not read field_msgId", 111L, got)
    }

    @Test
    fun `typeOf does NOT come from the wechat getType when the host is kuaishou`() {
        // The fixture's getType() returns 1 (= TYPE_TEXT). The Kuaishou reader has no getMsgType() to
        // call, so it must answer -1 -- the honest neutral. If the arms were swapped this would be 1 and
        // isText() would claim a text message on WeChat's evidence alone.
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().typeOf(msg) }
        assertEquals(-1, got)
        assertNotEquals("must not read WeChat's getType()", 1, got)
    }

    @Test
    fun `isText does not accept a message on wechat evidence`() {
        // Under WeChat the fixture IS "a text message" (type 1). Under Kuaishou the verdict must come
        // from the Kuaishou body rule instead. `isText` returning true here for the wrong reason is the
        // subtle half of R1: the item appears AND the pipeline accepts it, but with the wrong text.
        val msg = kwaiMsg()
        val ksText = asHost(HostApp.KUAISHOU) { access().isText(msg) }
        assertTrue("the Kuaishou body rule accepts a real text body", ksText)

        val wxText = asHost(HostApp.WECHAT) { access().isText(msg) }
        assertTrue("under WeChat it is text for a DIFFERENT reason (type == 1)", wxText)
        // Same boolean, different evidence -- which is why the content assertions above matter: the
        // boolean alone would not have caught the missing arm.
    }

    @Test
    fun `createTime does not fall back to the wechat field`() {
        // field_createTime is a plausible millisecond value, so a fall-through would look CORRECT and
        // silently paper over the missing arm -- the most dangerous shape of this bug. The Kuaishou
        // reader answers 0 here (the fixture has no sentTime/createTime), which is visibly different.
        val msg = kwaiMsg()
        val got = asHost(HostApp.KUAISHOU) { access().createTime(msg) }
        assertEquals("no Kuaishou timestamp on the fixture -> neutral 0", 0L, got)
        assertNotEquals("must not read field_createTime", 1_700_000_000_000L, got)
    }

    @Test
    fun `every routed method has a kuaishou arm`() {
        // R1 said all TEN entry points change together (9 methods + probe). A missed one is a partial
        // failure that mimics a different bug: e.g. working content() with a broken createTime() makes
        // every message get rejected at the epoch gate instead.
        val msg = kwaiMsg()
        asHost(HostApp.KUAISHOU) {
            val a = access()
            a.probe(msg) // must not throw, and must take the Kuaishou probe
            assertEquals("kuaishou-body", a.content(msg))
            assertEquals("kuaishou-talker", a.talker(msg))
            assertEquals(0L, a.createTime(msg))
            assertEquals(999L, a.msgId(msg))
            assertEquals(0L, a.msgSvrId(msg))
            assertEquals(-1, a.typeOf(msg))
            assertTrue(a.isText(msg))
            assertFalse(a.isSend(msg))
        }
    }

    // ------------------------------------------------------------------ host isolation

    @Test
    fun `the wechat host still reads the wechat fields`() {
        // The mirror image of the guard: the new arm must not have stolen the WeChat path. This is the
        // regression the Soul round recorded in the opposite direction (one host inheriting another's
        // reader), so both directions are pinned.
        val msg = kwaiMsg()
        val content = asHost(HostApp.WECHAT) { access().content(msg) }
        val talker = asHost(HostApp.WECHAT) { access().talker(msg) }
        assertEquals("wechat-body", content)
        assertEquals("wechat-talker", talker)
        assertTrue(asHost(HostApp.WECHAT) { access().isSend(msg) })
        assertEquals(222L, asHost(HostApp.WECHAT) { access().msgSvrId(msg) })
    }

    @Test
    fun `the host constant is restored after each test`() {
        // Because every test restores it in a finally, a leftover KUAISHOU would leak into the shared
        // JVM and make unrelated host-text tests fail. Asserted rather than assumed.
        val before = HostApp.current
        asHost(HostApp.KUAISHOU) { assertEquals(HostApp.KUAISHOU, HostApp.current) }
        assertEquals(before, HostApp.current)
    }

    // ------------------------------------------------------------------ the constants R1 depends on

    @Test
    fun `the wechat content candidates do not overlap the kuaishou accessors`() {
        // Documents WHY the missing arm was fatal rather than merely lossy: the WeChat reader's
        // candidate accessor names are WeChat-only, so no accidental overlap could have saved it. If this
        // list ever gained a name that IS on KwaiMsg, the reasoning changes and this test should be
        // revisited deliberately.
        val wechatOnly = listOf("j", "t0", "u0", "y0", "z0", "U1")
        for (n in wechatOnly) {
            assertNotEquals(
                "the WeChat candidate '$n' must not be a documented Kuaishou accessor",
                n,
                KuaishouEnvelope.TEXT_GETTER,
            )
        }
        assertEquals("getText", KuaishouEnvelope.TEXT_GETTER)
        assertEquals("text", KuaishouEnvelope.TEXT_FIELD)
    }

    @Test
    fun `the kuaishou message class name is the un-obfuscated imsdk one`() {
        assertEquals("com.kwai.imsdk.msg.KwaiMsg", KuaishouEnvelope.MSG_CLASS)
        assertEquals(
            "the test fixture must occupy the real class name, or the exact-name fallback cannot match",
            "com.kwai.imsdk.msg.KwaiMsg",
            kwaiMsg().javaClass.name,
        )
        assertNull("an unknown class has no placeholder", KuaishouEnvelope.placeholderFor("FakeKwaiMsg"))
    }
}
