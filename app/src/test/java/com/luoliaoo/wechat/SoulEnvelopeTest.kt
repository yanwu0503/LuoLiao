package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.SoulEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Soul content codec behind SOUL-4 (chat history auto-capture).
 *
 * ================================ WHY THIS TEST CLASS EXISTS ==================================
 *
 * The Douyin round shipped a bug where one sticker wrote 20 KB of base64 into the history file
 * (必看.md, 「抖音聊天记录里存进大段 JSON」), and the user's correction was explicit:
 * 「仅写入纯文本的 不会写入 图片 直播分析 等等」. That rule is a REQUIREMENT, so it is asserted
 * here rather than left to a comment -- a later refactor that reintroduces raw JSON or a "[image]"
 * placeholder now fails the build.
 *
 * The tests are also the only place the classification can be exercised exhaustively: on a device
 * each content type needs a real message of that type to arrive. Here every verified Soul content
 * class is checked in one run.
 *
 * Everything under test is pure (no reflection, no Android, no Log), which is what makes that
 * possible -- see the class comment on [SoulEnvelope].
 *
 * Comments ASCII-only (see core/Log.kt).
 */
class SoulEnvelopeTest {

    // ------------------------------------------------------------ the text cases

    @Test
    fun verifiedTextClassesStoreTheirBody() {
        // TextMsg.text / StringMsg.content / PromptMsg.text -- each verified by dumping the dex.
        val cases = listOf(
            "cn.soulapp.imlib.msg.chat.TextMsg" to "hello there",
            "cn.soulapp.imlib.msg.chat.StringMsg" to "hi from StringMsg",
            "cn.soulapp.imlib.msg.chat.PromptMsg" to "a prompt line",
        )
        for ((cls, body) in cases) {
            val d = SoulEnvelope.decide(cls, body, recalled = false)
            assertEquals("class=" + cls, body, d.text)
            assertTrue("class=" + cls, d.store)
            assertEquals(SoulEnvelope.Reason.OK, d.reason)
        }
    }

    @Test
    fun aPlainStringBodyWithNoClassStillStores() {
        // Some paths hand back the serialised form as a bare String, so the envelope decides.
        val d = SoulEnvelope.decide(null, "just text", recalled = false)
        assertEquals("just text", d.text)
        assertTrue(d.store)
    }

    @Test
    fun userSentJsonIsStoredVerbatim() {
        // People really do send JSON to each other. Requiring a SOUL key in the envelope test is what
        // keeps a hand-written object from being mangled by the parser.
        val userJson = "{\"a\":1}"
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", userJson, recalled = false)
        assertEquals(userJson, d.text)
        assertFalse(SoulEnvelope.looksLikeEnvelope(userJson))
    }

    // ------------------------------------------------------------ THE non-text rule

    @Test
    fun nonTextContentClassesStoreNothingAndNeverTheRawPayload() {
        // Every other verified member of the content family. THIS is the 20 KB base64 guard.
        val nonText = listOf(
            "cn.soulapp.imlib.msg.chat.ImgMsg",
            "cn.soulapp.imlib.msg.chat.ImgMsgs",
            "cn.soulapp.imlib.msg.chat.AudioMsg",
            "cn.soulapp.imlib.msg.chat.VideoMsg",
            "cn.soulapp.imlib.msg.chat.ExpressionMsg",
            "cn.soulapp.imlib.msg.chat.UserCardMsg",
            "cn.soulapp.imlib.msg.chat.MusicMsg",
            "cn.soulapp.imlib.msg.chat.PositionMsg",
            "cn.soulapp.imlib.msg.chat.RePostMsg",
            "cn.soulapp.imlib.msg.chat.CallMsg",
            "cn.soulapp.imlib.msg.chat.ShareTagMsg",
            "cn.soulapp.imlib.msg.chat.SoulmateInviteMsg",
            "cn.soulapp.imlib.msg.chat.DiceFingerMsg",
            "cn.soulapp.imlib.msg.chat.PlanetBActionMsg",
            "cn.soulapp.imlib.msg.chat.VoiceChatMsg",
        )
        // A payload shaped like the real failure: long, JSON, full of URLs.
        val huge = "{\"url\":\"https://img.soulapp.cn/" + "A".repeat(20000) + "\"}"
        for (cls in nonText) {
            val d = SoulEnvelope.decide(cls, huge, recalled = false)
            assertEquals("class=" + cls + " must store nothing", "", d.text)
            assertFalse("class=" + cls + " must not store", d.store)
            assertEquals(SoulEnvelope.Reason.NON_TEXT_CLASS, d.reason)
        }
    }

    @Test
    fun anUnknownSoulContentClassStoresNothing() {
        // A NEW subclass added by a Soul update must be non-text BY DEFAULT. This is the safe
        // direction: storing a card payload as if the user typed it is far worse than missing a line.
        // It is classified by NAMESPACE (it is a member of Soul's content family) rather than as an
        // outright stranger, so the log says "a content class we do not know" -- which is the honest
        // description and the one that tells the next engineer to add it to the allow-list.
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.SomeFutureCardMsg", "payload", false)
        assertEquals("", d.text)
        assertFalse(d.store)
        assertEquals(SoulEnvelope.Reason.NON_TEXT_CLASS, d.reason)
    }

    @Test
    fun aNonSoulClassStoresNothing() {
        val d = SoulEnvelope.decide("com.example.OtherThing", "payload", false)
        assertEquals("", d.text)
        assertEquals(SoulEnvelope.Reason.UNKNOWN_CLASS, d.reason)
    }

    @Test
    fun theSystemGeneratedProtocolClassIsNotUserText() {
        // ExtChatMsg IS deliberately not on the allow-list, and this test is the guard.
        //
        // It was twice recommended to me as "also carrying text, so omitting it drops real messages".
        // The dex says otherwise: it is built ONLY from app-generated protocol strings
        // (VoiceRtcEngine -> "requests a voice call with you", y4.O0 -> "agree", imlib.handler ->
        // com.soul.im.protos.ExtChatMessage inside CommandMessage handling), it has a `type:int`
        // discriminator, and chat.utils.y4.R1 SENDS it. So it is Soul talking, not the user.
        //
        // If someone later adds it to the allow-list "to be safe", this fails and they must argue
        // with the evidence above.
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.ExtChatMsg", "\u540c\u610f", false)
        assertEquals("", d.text)
        assertFalse(d.store)
        assertFalse(SoulEnvelope.TEXT_CONTENT_CLASSES.contains("cn.soulapp.imlib.msg.chat.ExtChatMsg"))
    }

    @Test
    fun theAllowListIsExactlyTheThreeVerifiedCarriers() {
        // Pinned so a future edit has to be deliberate. Each of the three was verified by dumping
        // fields: TextMsg.text, StringMsg.content, PromptMsg.text.
        assertEquals(
            setOf(
                "cn.soulapp.imlib.msg.chat.TextMsg",
                "cn.soulapp.imlib.msg.chat.StringMsg",
                "cn.soulapp.imlib.msg.chat.PromptMsg",
            ),
            SoulEnvelope.TEXT_CONTENT_CLASSES,
        )
    }

    // ------------------------------------------------------------ envelope handling

    @Test
    fun aSoulEnvelopeIsUnwrappedNotStoredRaw() {
        // The exact JSON shape Soul's own renderer parses (verified in ShiningTextItem:
        // getMsgContent() -> JSONObject -> optString("content")).
        val envelope = "{\"messageType\":\"TEXT\",\"content\":\"\u4f60\u597d\",\"notice\":\"\"}"
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", envelope, recalled = false)
        assertEquals("\u4f60\u597d", d.text)
        assertTrue(d.store)
    }

    @Test
    fun anEnvelopeWithNoTextFieldStoresNothingNotTheEnvelope() {
        // THE regression guard: an envelope that carries no human string must yield EMPTY. Returning
        // the envelope instead is precisely the 20 KB JSON bug.
        val envelope =
            "{\"messageType\":\"IMG\",\"msgContent\":\"https://img.soulapp.cn/x.png\",\"sessionId\":\"s1\"}"
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", envelope, recalled = false)
        assertEquals("", d.text)
        assertFalse(d.store)
        assertEquals(SoulEnvelope.Reason.ENVELOPE_WITHOUT_TEXT, d.reason)
    }

    @Test
    fun aConditionalJsonMsgWithPlainBodyStores() {
        // JsonMsg is used for real exchanged text, so a plain body is kept...
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.JsonMsg", "plain words", false)
        assertEquals("plain words", d.text)
    }

    @Test
    fun aConditionalJsonMsgCarryingAnEnvelopeStoresNothing() {
        // ...but the same class carrying an envelope is a card payload, so it is rejected.
        val d = SoulEnvelope.decide(
            "cn.soulapp.imlib.msg.chat.JsonMsg",
            "{\"messageType\":\"CARD\",\"msgContent\":\"{}\"}",
            false,
        )
        assertEquals("", d.text)
        assertEquals(SoulEnvelope.Reason.ENVELOPE_WITHOUT_TEXT, d.reason)
    }

    @Test
    fun envelopeDetectionRequiresASoulKeyNotJustBraces() {
        assertFalse(SoulEnvelope.looksLikeEnvelope("not json"))
        assertFalse(SoulEnvelope.looksLikeEnvelope("{broken"))
        assertFalse(SoulEnvelope.looksLikeEnvelope("{}"))
        assertFalse(SoulEnvelope.looksLikeEnvelope("{\"text\":\"hi\"}"))
        assertTrue(SoulEnvelope.looksLikeEnvelope("{\"messageType\":\"X\"}"))
        assertTrue(SoulEnvelope.looksLikeEnvelope("{\"msgContent\":\"X\"}"))
        assertTrue(SoulEnvelope.looksLikeEnvelope("{\"sessionId\":\"X\"}"))
    }

    @Test
    fun douyinEnvelopeKeysAreNotTreatedAsSouls() {
        // AweType et al. belong to Douyin. Copy-pasting them here would make a Douyin-shaped string
        // look like a Soul envelope; more importantly it documents that the key sets are per-host.
        assertFalse(SoulEnvelope.looksLikeEnvelope("{\"aweType\":700,\"text\":\"x\"}"))
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        // Soul escapes non-ASCII as \uXXXX, so this is the NORMAL path for Chinese text.
        assertEquals(
            "\u4f60\u597d\u4e16\u754c",
            SoulEnvelope.extractTextField("{\"content\":\"\\u4f60\\u597d\\u4e16\\u754c\"}"),
        )
    }

    @Test
    fun controlEscapesAreDecoded() {
        assertEquals("a\nb", SoulEnvelope.extractStringValue("{\"text\":\"a\\nb\"}", "text"))
        assertEquals("q\"q", SoulEnvelope.extractStringValue("{\"text\":\"q\\\"q\"}", "text"))
        assertEquals("s\\s", SoulEnvelope.extractStringValue("{\"text\":\"s\\\\s\"}", "text"))
    }

    @Test
    fun aDamagedEnvelopeYieldsNullNotAnException() {
        // A hand-rolled scan must never throw: it simply finds nothing, and the caller stores nothing.
        assertNull(SoulEnvelope.extractTextField("{\"text\":}"))
        assertNull(SoulEnvelope.extractTextField("{\"text\":"))
        assertNull(SoulEnvelope.extractTextField(""))
        assertNull(SoulEnvelope.extractTextField("{\"text\":\"\\uZZZZ\"}"))
    }

    @Test
    fun theFirstUsableTextKeyWins() {
        assertEquals("one", SoulEnvelope.extractTextField("{\"text\":\"one\",\"content\":\"two\"}"))
        assertEquals("two", SoulEnvelope.extractTextField("{\"content\":\"two\"}"))
    }

    // ------------------------------------------------------------ recall + blanks

    @Test
    fun recalledMessagesAreSkipped() {
        val d = SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", "hi", recalled = true)
        assertEquals("", d.text)
        assertEquals(SoulEnvelope.Reason.RECALLED, d.reason)
    }

    @Test
    fun recallWinsOverEverythingElse() {
        // Even a perfectly good text line is dropped when the host says it was withdrawn.
        assertFalse(SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", "hi", true).store)
    }

    @Test
    fun blankBodiesStoreNothing() {
        assertEquals("", SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", "", false).text)
        assertEquals("", SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", "   ", false).text)
        assertEquals("", SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", null, false).text)
        assertEquals(
            SoulEnvelope.Reason.NO_BODY,
            SoulEnvelope.decide("cn.soulapp.imlib.msg.chat.TextMsg", null, false).reason,
        )
    }

    @Test
    fun aMissingContentClassIsNotAFailureForAPlainBody() {
        // null class only happens on the bare-String route, where the envelope is the only test.
        assertEquals("plain", SoulEnvelope.decide(null, "plain", false).text)
        assertEquals("", SoulEnvelope.decide(null, "{\"messageType\":\"X\"}", false).text)
    }

    // ------------------------------------------------------------ id parsing + idempotency

    @Test
    fun soulMsgIdIsAStringAndIsConverted() {
        // ImMessage.getMsgId() returns Ljava/lang/String; -- verified -- so this is a real conversion.
        assertEquals(123456789L, SoulEnvelope.parseMsgId("123456789"))
        assertEquals(42L, SoulEnvelope.parseMsgId("  42  "))
    }

    @Test
    fun anUnusableMsgIdBecomesZeroSoTheCallerFallsBack() {
        // 0L means "no usable id", which makes the caller use a synthetic key. Returning a plausible
        // fake id instead would silently collide two different messages.
        assertEquals(0L, SoulEnvelope.parseMsgId(null))
        assertEquals(0L, SoulEnvelope.parseMsgId(""))
        assertEquals(0L, SoulEnvelope.parseMsgId("   "))
        assertEquals(0L, SoulEnvelope.parseMsgId("not-a-number"))
        assertEquals(0L, SoulEnvelope.parseMsgId("0"))
        assertEquals(0L, SoulEnvelope.parseMsgId("-5"))
    }

    @Test
    fun theSyntheticIdIsStableSoRepeatedBindsCannotDuplicate() {
        // A RecyclerView re-binds constantly, so THE key property is: same message => same id.
        val a = SoulEnvelope.syntheticId("peer1", 1_700_000_000_000L, false, "hi")
        val b = SoulEnvelope.syntheticId("peer1", 1_700_000_000_000L, false, "hi")
        assertEquals(a, b)
    }

    @Test
    fun theSyntheticIdSeparatesDifferentMessages() {
        val base = SoulEnvelope.syntheticId("peer1", 1_700_000_000_000L, false, "hi")
        val otherText = SoulEnvelope.syntheticId("peer1", 1_700_000_000_000L, false, "ho")
        val otherTalker = SoulEnvelope.syntheticId("peer2", 1_700_000_000_000L, false, "hi")
        val otherDir = SoulEnvelope.syntheticId("peer1", 1_700_000_000_000L, true, "hi")
        val otherTime = SoulEnvelope.syntheticId("peer1", 1_700_000_000_001L, false, "hi")
        assertTrue(base != otherText)
        assertTrue(base != otherTalker)
        assertTrue(base != otherDir)
        assertTrue(base != otherTime)
    }

    @Test
    fun aSyntheticIdCannotCollideWithARealOne() {
        // The high bit range is reserved so a synthetic key can never equal a real Soul id.
        val id = SoulEnvelope.syntheticId("peer", 1L, false, "x")
        assertTrue("id=" + id, id >= 0x100000000L)
    }

    @Test
    fun syntheticIdsCollapseUnusableHostIdsOntoOneLine() {
        // End to end: two binds of the same id-less message must produce ONE store key, mirroring what
        // HistoryStore.record does with it.
        fun keyOf(rawId: String, time: Long, text: String): Long {
            val parsed = SoulEnvelope.parseMsgId(rawId)
            return if (parsed != 0L) parsed else SoulEnvelope.syntheticId("peer", time, false, text)
        }
        assertEquals(
            keyOf("", 1_700_000_000_000L, "same"),
            keyOf("", 1_700_000_000_000L, "same"),
        )
        // ...while a REAL id is used as-is.
        assertEquals(777L, keyOf("777", 1_700_000_000_000L, "same"))
    }

    // ------------------------------------------------------------ time units

    @Test
    fun millisecondsAreLeftAlone() {
        val ms = 1_700_000_000_000L
        assertEquals(ms, SoulEnvelope.toMillis(ms))
        assertEquals("ms", SoulEnvelope.timeBranch(ms))
    }

    @Test
    fun secondsAreScaledUp() {
        // If Soul's serverTime turns out to be SECONDS, every message would otherwise be rejected by
        // the shared feed's placeholder guard and the history would stay empty -- the exact bug being
        // fixed. The magnitude fix lives here, asserted.
        assertEquals(1_700_000_000_000L, SoulEnvelope.toMillis(1_700_000_000L))
        assertEquals("s->ms", SoulEnvelope.timeBranch(1_700_000_000L))
    }

    @Test
    fun implausibleTimesArePassedThroughSoTheSharedGuardStillRejectsThem() {
        // NOT coerced into a fake epoch: an unset time must stay recognisable as unset.
        assertEquals(5L, SoulEnvelope.toMillis(5L))
        assertEquals("too-small", SoulEnvelope.timeBranch(5L))
        assertEquals(0L, SoulEnvelope.toMillis(0L))
        assertEquals("non-positive", SoulEnvelope.timeBranch(0L))
        assertEquals(-1L, SoulEnvelope.toMillis(-1L))
    }

    @Test
    fun theTimeBranchIsPureAndSoIsRaceFree() {
        // timeBranch derives from its argument only. An earlier revision stored the branch in a shared
        // var, which made concurrent messages race and made the tests order-dependent.
        assertEquals("ms", SoulEnvelope.timeBranch(1_700_000_000_000L))
        assertEquals("s->ms", SoulEnvelope.timeBranch(1_700_000_000L))
        assertEquals("ms", SoulEnvelope.timeBranch(1_700_000_000_000L))
    }
}
