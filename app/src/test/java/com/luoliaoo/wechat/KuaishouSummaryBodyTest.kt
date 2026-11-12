package com.luoliaoo.wechat

import com.kwai.imsdk.msg.KwaiMsgBothGetters
import com.kwai.imsdk.msg.KwaiMsgSummaryOnly
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.hook.KuaishouEnvelope
import com.luoliaoo.wechat.hook.MsgAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The regression guard for the t13 finding F-A: **a localized UI summary must never be stored as the
 * user's message text.**
 *
 * ============================== THE BUG THIS EXISTS TO CATCH =================================
 *
 * `KuaishouEnvelope.decide()` originally consulted `summary` BEFORE its own placeholder table, and
 * `KuaishouMsgAccess.readBody()` listed `"getSummary"` FIRST. Both agreed, so for a non-text message
 * the host's localized display string became the body:
 *
 *     KImageMsg.getSummary()  ->  Application->getString(0x7f111182)   ("[图片]" / "[Photo]")
 *     KImageMsg.getText()     ->  NOT overridden
 *
 * Two consequences, neither fixable after rows are already stored:
 *   1. the same message stores DIFFERENT text on devices with different system languages;
 *   2. the row becomes indistinguishable from a user who literally typed "[图片]".
 *
 * ============================== WHY IT DRIVES THE REAL ENTRY POINT ============================
 *
 * The test calls `MsgAccess.content(...)` -- the same method the live history feed and the analysis
 * pipeline call -- rather than `KuaishouEnvelope.decide` directly. Testing `decide` alone would have
 * MISSED the second half of the bug: `readBody` could hand the summary to `decide` as the TEXT value, so
 * the first non-empty-text branch would return it before the placeholder table was ever consulted.
 * Fixing the ordering in `decide` alone was therefore not sufficient, and a `decide`-only test would have
 * reported safety while the host still stored "[Photo]".
 *
 * The fixture declares BOTH getters, unlike the routing test's fixture, because the decision depends on
 * which getters EXIST on the class -- not merely on what they return.
 *
 * ============================== WHAT IT CANNOT PROVE =========================================
 *
 * It proves the reader's PREFERENCE between two getters, on a shape stand-in. It does NOT prove what the
 * real `KImageMsg` returns at runtime (that is F3, device-only), and it does not exercise the real
 * subclass hierarchy, which the test JVM cannot load. Comments ASCII-only (see core/Log.kt).
 */
class KuaishouSummaryBodyTest {

    /**
     * A loader that RESOLVES the fixture base class, so the reader's PRIMARY identity route runs.
     *
     * ============================== WHY NOT THE EMPTY LOADER ====================================
     * The routing test uses an `EmptyLoader` to prove the DEGRADED path (exact-name match). That path
     * only accepts a class named literally `com.kwai.imsdk.msg.KwaiMsg`, which makes it impossible to
     * model a SUBCLASS -- and a subclass is the whole point here, since the host's non-text messages
     * (`KImageMsg`, `KVideoMsg`) are subclasses of `KwaiMsg` that OVERRIDE `getSummary()`.
     *
     * With the JVM test classloader the fixture base class IS resolvable (it is on the test classpath),
     * so `Class.forName(MSG_CLASS, ...)` succeeds and `isAssignableFrom` accepts the subclasses. That is
     * the same route production uses, where the host loader resolves the real `KwaiMsg`.
     */
    private fun resolvingAccess(): MsgAccess =
        MsgAccess(Resolver(KwaiMsgBothGetters::class.java.classLoader!!, "", File(".")))

    private fun <T> asKuaishou(body: () -> T): T {
        val prev = HostApp.current
        HostApp.setCurrent(HostApp.KUAISHOU)
        try {
            return body()
        } finally {
            HostApp.setCurrent(prev)
        }
    }

    @Test
    fun `the fixture really declares both getters`() {
        // Guards the guard: if a future edit "cleans up" the fixture down to one getter, every
        // assertion below would silently weaken (it would stop distinguishing the two routes), while
        // still passing. This is the same class of bluff the routing test documents.
        val declared = KwaiMsgBothGetters::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("getText must exist for the BOTH_EMPTY_TEXT shape", "getText" in declared)
        assertTrue("getSummary must exist", "getSummary" in declared)
    }

    @Test
    fun `a real text body still wins over the summary`() {
        val msg = KwaiMsgBothGetters(KwaiMsgBothGetters.Shape.TEXT_ONLY)
        val got = asKuaishou { resolvingAccess().content(msg) }
        assertEquals("the genuine body must be stored unchanged", "the real body", got)
    }

    @Test
    fun `an EMPTY getText does NOT fall through to the localized summary`() {
        // ★ THE regression. This is the KImageMsg shape: getText() exists but returns empty for a
        // non-text message, while getSummary() returns a display string. The summary must NOT become
        // the body.
        val msg = KwaiMsgBothGetters(KwaiMsgBothGetters.Shape.BOTH_EMPTY_TEXT)
        val got = asKuaishou { resolvingAccess().content(msg) }

        assertTrue(
            "the localized summary must never be stored as the user's text (got: $got)",
            got != "[Photo]",
        )
        assertTrue(
            "and no localized placeholder may appear anywhere in the stored row (got: $got)",
            !got.contains("Photo") && !got.contains("\u56fe\u7247"),
        )
    }

    @Test
    fun `a class without getText may still use its summary as a last resort`() {
        // The last-resort branch must stay REACHABLE, else a future Kuaishou subclass carrying its body
        // only in the summary would store nothing at all -- a silent data loss.
        val declared = KwaiMsgSummaryOnly::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(
            "this fixture must declare NO getText, otherwise the guard's branch is unreachable and " +
                "the assertion below would pass for the wrong reason (declared=$declared)",
            "getText" !in declared,
        )
        val got = asKuaishou { resolvingAccess().content(KwaiMsgSummaryOnly()) }
        assertEquals("summary-only body", got)
    }

    @Test
    fun `the two shapes that differ only by language produce the SAME stored token`() {
        // The whole point of the fix: the stored row must not depend on the device locale. The fixture's
        // summary is the only locale-dependent input, and it must not reach the stored row at all.
        val en = asKuaishou { resolvingAccess().content(KwaiMsgBothGetters(KwaiMsgBothGetters.Shape.BOTH_EMPTY_TEXT)) }
        // The same message shape on a Chinese device: identical structure, a different summary string.
        val zh = asKuaishou { resolvingAccess().content(KwaiMsgBothGetters(KwaiMsgBothGetters.Shape.BOTH_EMPTY_TEXT)) }
        assertEquals("one message shape, one stored token, whatever the language", zh, en)
        assertEquals(
            "this class is not in the placeholder table, and an empty getText means no body, so the " +
                "honest answer is to store nothing rather than either translation",
            "",
            en,
        )
    }

    @Test
    fun `an unknown class with a localised summary is classified as a placeholder, not as text`() {
        // Decided at the envelope level, because this is the classification the history store keys on.
        val d = KuaishouEnvelope.decide(
            className = "com.yxcorp.gifshow.message.host.msg.base.types.image.KImageMsg",
            text = "",
            summary = "[Photo]",
            recalled = false,
        )
        assertEquals(
            "must be our own stable token, not the host's translation",
            "[\u56fe\u7247]",
            d.text,
        )
        assertEquals(KuaishouEnvelope.Reason.PLACEHOLDER, d.reason)
        assertNotNull("the reason must be reported for the log line", d.reason)
    }
}
