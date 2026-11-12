package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.KuaishouEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [KuaishouEnvelope] -- the rules that decide what a Kuaishou message contributes
 * to the local history.
 *
 * ============================== WHY THESE ARE THE HIGHEST-VALUE TESTS ==========================
 *
 * The decision this object encodes is the one that produced the largest recorded bug in the project:
 * Douyin's envelope JSON, where a single expression sticker wrote ~20 KB of base64 into the user's chat
 * history (必看.md 「抖音聊天记录里存进大段 JSON」). Kuaishou is a WORSE shape for the same mistake,
 * because its non-text payloads are `contentBytes` / `extra` / `localExtra` -- protobuf-nano BYTE
 * ARRAYS, not even JSON. A reader that fell back to "whatever the object has" would store a byte-array
 * dump as a chat line.
 *
 * So the tests below are written to FAIL if anyone:
 *   * makes a binary payload storable (there is no path in the code that can, and this pins it);
 *   * treats a placeholder as a real text message (placeholders must not pass `isText`'s rule);
 *   * drops a real text message that also happens to be flagged recalled;
 *   * accepts an unknown empty class instead of storing nothing;
 *   * returns an unnormalised timestamp.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouEnvelopeTest {

    // ------------------------------------------------------------------ the text case

    @Test
    fun `a text body is stored verbatim`() {
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.TextMsg",
            text = "hello there",
            summary = null,
            recalled = false,
        )
        assertTrue("plain text must be stored", d.store)
        assertEquals("hello there", d.text)
        assertEquals(KuaishouEnvelope.Reason.OK, d.reason)
    }

    @Test
    fun `text wins over a placeholder even for a non-text class name`() {
        // A subclass that carries text must never be reduced to "[image]" just because its class name
        // looks non-textual. The body is the evidence; the name is only a hint.
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.ImageMsg",
            text = "look at this",
            summary = null,
            recalled = false,
        )
        assertEquals("look at this", d.text)
        assertEquals(KuaishouEnvelope.Reason.OK, d.reason)
    }

    @Test
    fun `whitespace-only text is not a chat line`() {
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.TextMsg",
            text = "   \n  ",
            summary = null,
            recalled = false,
        )
        assertFalse("a blank body must not be stored as text", d.store)
    }

    // ------------------------------------------------------------------ summary fallback

    @Test
    fun `summary is used when the text field is empty AND the class is not a known non-text type`() {
        // `TextMsg` is deliberately NOT in the placeholder table, so for it the summary is the only
        // remaining signal and is used as a last resort. (Verified: TextMsg.getSummary() is a
        // trampoline to getText(), so in practice the text route answers first; this covers the
        // subclass that carries its body only in the summary.)
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.TextMsg",
            text = "",
            summary = "from summary",
            recalled = false,
        )
        assertEquals("from summary", d.text)
        assertEquals(KuaishouEnvelope.Reason.OK_SUMMARY, d.reason)
    }

    @Test
    fun `an IMAGE's localized summary is NEVER stored as the body`() {
        // ★ THE regression this guards. Disassembly of `KImageMsg.getSummary()`:
        //
        //     invoke-static rx9.a->b()Landroid/app/Application;
        //     const v1, #0x7f111182
        //     invoke-virtual Application->getString(I)Ljava/lang/String;
        //
        // i.e. a LOCALIZED UI placeholder ("[图片]" / "[Photo]"), while `KImageMsg` does NOT override
        // `getText()` at all. An earlier version of `decide` trusted the summary before its own
        // placeholder table, so that string would have been stored as if the user had typed it. Two
        // consequences, both unfixable after the fact:
        //   1. the stored row changes with the device LANGUAGE for one unchanged APK;
        //   2. it is indistinguishable from a user who literally typed "[图片]".
        val d = KuaishouEnvelope.decide(
            className = "com.yxcorp.gifshow.message.host.msg.base.types.image.KImageMsg",
            text = "",                 // getText() inherits the base field read -> empty for an image
            summary = "[\u56fe\u7247]",   // the localized placeholder from getString(0x7f111182)
            recalled = false,
        )
        assertEquals(
            "the host's localized placeholder must be replaced by OUR stable one",
            "[\u56fe\u7247]",
            d.text,
        )
        assertEquals(
            "must be classified as a placeholder, not as real user text",
            KuaishouEnvelope.Reason.PLACEHOLDER,
            d.reason,
        )
    }

    @Test
    fun `an unrecognised non-text class keeps its summary rather than vanishing`() {
        // The last-resort branch must stay reachable, otherwise a future Kuaishou subclass that carries
        // its display text ONLY in the summary would store nothing at all -- a silent data loss.
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.SomeFutureMsg",
            text = "",
            summary = "display text for an unknown class",
            recalled = false,
        )
        assertEquals("display text for an unknown class", d.text)
        assertEquals(KuaishouEnvelope.Reason.OK_SUMMARY, d.reason)
    }

    @Test
    fun `a localized English placeholder is also replaced`() {
        // The language-dependence is the whole point: the SAME message on an English device must store
        // the same token as on a Chinese one. Both are mapped to our single stable placeholder.
        val zh = KuaishouEnvelope.decide("KImageMsg", text = "", summary = "[\u56fe\u7247]", recalled = false)
        val en = KuaishouEnvelope.decide("KImageMsg", text = "", summary = "[Photo]", recalled = false)
        assertEquals("one message, one stored token, whatever the device language", zh.text, en.text)
        assertEquals(KuaishouEnvelope.Reason.PLACEHOLDER, en.reason)
    }

    // ------------------------------------------------------------------ non-text placeholders

    @Test
    fun `known non-text subclasses get a short placeholder`() {
        val expected = mapOf(
            "com.kwai.imsdk.msg.ImageMsg" to "[\u56fe\u7247]",
            "com.kwai.imsdk.msg.VideoMsg" to "[\u89c6\u9891]",
            "com.kwai.imsdk.msg.AudioMsg" to "[\u8bed\u97f3]",
            "com.kwai.imsdk.msg.KEmotionMsg" to "[\u8868\u60c5]",
        )
        for ((cls, ph) in expected) {
            val d = KuaishouEnvelope.decide(cls, text = null, summary = null, recalled = false)
            assertEquals("placeholder for $cls", ph, d.text)
            assertEquals(KuaishouEnvelope.Reason.PLACEHOLDER, d.reason)
            assertTrue("a placeholder is storable", d.store)
        }
    }

    @Test
    fun `an application-layer K-prefixed name maps to the same placeholder`() {
        // Kuaishou has BOTH the IM SDK name (ImageMsg) and an application-layer KImageMsg for the same
        // concept. Missing one would silently produce no row for half the images.
        assertEquals(
            KuaishouEnvelope.placeholderFor("com.kwai.imsdk.msg.ImageMsg"),
            KuaishouEnvelope.placeholderFor("com.yxcorp.gifshow.KImageMsg"),
        )
    }

    @Test
    fun `a wrapper class name still resolves its placeholder`() {
        // The UI binds KwaiMsgWrapper extends KwaiMsg, and an inner/nested name is also possible, so the
        // lookup must key on the simple name rather than the full one.
        assertEquals("[\u56fe\u7247]", KuaishouEnvelope.placeholderFor("a.b.c.ImageMsg"))
        assertEquals("[\u56fe\u7247]", KuaishouEnvelope.placeholderFor("a.b.Outer\$ImageMsg"))
    }

    // ------------------------------------------------------------------ recalled

    @Test
    fun `a recalled message is named rather than stored as its emptied text`() {
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.RecalledMsg",
            text = null,
            summary = null,
            recalled = false, // detection by CLASS NAME, which is the verified route
        )
        assertEquals("[\u5df2\u64a4\u56de]", d.text)
        assertEquals(KuaishouEnvelope.Reason.RECALLED, d.reason)
    }

    @Test
    fun `an explicit recall flag wins over a non-text placeholder`() {
        // "recalled" is strictly more informative than "[image]", so the recall branch must come first.
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.ImageMsg",
            text = null,
            summary = null,
            recalled = true,
        )
        assertEquals("[\u5df2\u64a4\u56de]", d.text)
        assertEquals(KuaishouEnvelope.Reason.RECALLED, d.reason)
    }

    @Test
    fun `a recalled message that still carries text keeps the text`() {
        // Real bodies are the requirement; a recall flag must not erase one.
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.RecalledMsg",
            text = "original words",
            summary = null,
            recalled = true,
        )
        assertEquals("original words", d.text)
        assertEquals(KuaishouEnvelope.Reason.OK, d.reason)
    }

    // ------------------------------------------------------------------ the honest empty

    @Test
    fun `an unknown empty subclass stores NOTHING - never the binary payload`() {
        // This is the anti-envelope rule. An unrecognised future subclass with no readable body must
        // produce NO row: a fabricated row is indistinguishable from a correct one, while a missing row
        // is visible and the reader logs the class that needs adding.
        val d = KuaishouEnvelope.decide(
            className = "com.kwai.imsdk.msg.SomeFutureMsgType",
            text = null,
            summary = null,
            recalled = false,
        )
        assertFalse("nothing may be stored for an unreadable body", d.store)
        assertEquals("", d.text)
        assertEquals(KuaishouEnvelope.Reason.NO_BODY, d.reason)
    }

    @Test
    fun `the binary payload field names are refusable on their own`() {
        // There is no code path that reads these fields, and this test documents which names are
        // forbidden so a future "just read whatever is there" fallback fails review immediately.
        assertTrue(KuaishouEnvelope.isBinaryFieldName("contentBytes"))
        assertTrue(KuaishouEnvelope.isBinaryFieldName("extra"))
        assertTrue(KuaishouEnvelope.isBinaryFieldName("localExtra"))
        assertFalse(KuaishouEnvelope.isBinaryFieldName("text"))
        assertFalse(KuaishouEnvelope.isBinaryFieldName("summary"))
        assertNull("a non-text, non-known class has no placeholder", KuaishouEnvelope.placeholderFor("Zzz"))
    }

    // ------------------------------------------------------------------ the two-path (name, then msgType) lookup

    @Test
    fun `CASE 1 -- a class name that matches is UNCHANGED by the numeric path`() {
        // The captain's requirement: adding the numeric path must not alter any currently-correct
        // behaviour. For a class already in the table the output must be byte-identical whether the
        // caller supplies a matching type, a CONTRADICTING type, or no type at all.
        val cases = listOf(
            "com.kwai.imsdk.msg.ImageMsg" to "[\u56fe\u7247]",
            "com.yxcorp.gifshow.message.host.msg.base.types.image.KImageMsg" to "[\u56fe\u7247]",
            "com.kwai.imsdk.msg.VideoMsg" to "[\u89c6\u9891]",
        )
        for ((cls, expected) in cases) {
            val noType = KuaishouEnvelope.decide(cls, text = "", summary = null, recalled = false)
            val agree = KuaishouEnvelope.decide(
                cls, text = "", summary = null, recalled = false, msgType = 1,
            )
            // A deliberately WRONG number: the name is the more specific evidence and must still win.
            val contradict = KuaishouEnvelope.decide(
                cls, text = "", summary = null, recalled = false, msgType = 1014,
            )
            assertEquals("$cls without a type", expected, noType.text)
            assertEquals("$cls with a matching type", expected, agree.text)
            assertEquals("the NAME must outrank a contradicting type", expected, contradict.text)
            assertEquals(KuaishouEnvelope.Reason.PLACEHOLDER, contradict.reason)
        }
    }

    @Test
    fun `CASE 2 -- a class name that misses falls through to the numeric type`() {
        // The case the numeric path exists for: a wrapper or unknown subclass whose name matches
        // nothing. Before this path such a message stored "" and looked like a broken hook.
        val cls = "com.yxcorp.gifshow.message.detail.message_list.KwaiMsgWrapper"
        assertNull("precondition: this name must match nothing", KuaishouEnvelope.placeholderFor(cls))

        val image = KuaishouEnvelope.decide(
            cls, text = "", summary = null, recalled = false, msgType = 1,
        )
        assertEquals("the numeric path must supply the placeholder", "[\u56fe\u7247]", image.text)
        assertEquals(KuaishouEnvelope.Reason.PLACEHOLDER, image.reason)
        assertTrue("a placeholder found by type is storable", image.store)

        // ...and the same for other evidence-backed types.
        assertEquals(
            "[\u89c6\u9891]",
            KuaishouEnvelope.decide(cls, text = "", summary = null, recalled = false, msgType = 4).text,
        )
        assertEquals(
            "[\u8bed\u97f3]",
            KuaishouEnvelope.decide(cls, text = "", summary = null, recalled = false, msgType = 3).text,
        )
    }

    @Test
    fun `CASE 3 -- neither path matches, so the existing fallback still decides`() {
        val cls = "com.yxcorp.gifshow.message.detail.message.list.SomeCardMsg"
        // 1039 is a real type in the dispatch table but carries NO label in the evidence, so it must
        // NOT be given an invented one -- it falls through exactly as before.
        val withSummary = KuaishouEnvelope.decide(
            cls, text = "", summary = "card display text", recalled = false, msgType = 1039,
        )
        assertEquals("an unlabelled type must not override the summary path",
            "card display text", withSummary.text)
        assertEquals(KuaishouEnvelope.Reason.OK_SUMMARY, withSummary.reason)

        val nothing = KuaishouEnvelope.decide(
            cls, text = "", summary = null, recalled = false, msgType = 1039,
        )
        assertFalse("nothing readable on either path -> store nothing", nothing.store)
        assertEquals(KuaishouEnvelope.Reason.NO_BODY, nothing.reason)

        // An UNREADABLE type (-1, what `callInt` yields when the getter is absent) must not match.
        assertNull(KuaishouEnvelope.byType(KuaishouEnvelope.MSG_TYPE_UNAVAILABLE))
        assertFalse(
            KuaishouEnvelope.decide(
                cls, text = "", summary = null, recalled = false,
                msgType = KuaishouEnvelope.MSG_TYPE_UNAVAILABLE,
            ).store,
        )
    }

    @Test
    fun `a recall detected only by numeric type is still named as recalled`() {
        // Type 11 is KRecalledMsg. For a wrapper the class name says nothing, so the number is the only
        // signal that this message was withdrawn -- and "已撤回" must beat any placeholder.
        val d = KuaishouEnvelope.decide(
            "com.yxcorp.gifshow.message.detail.message_list.KwaiMsgWrapper",
            text = "", summary = null, recalled = false, msgType = 11,
        )
        assertEquals(KuaishouEnvelope.Reason.RECALLED, d.reason)
        assertTrue(d.store)
    }

    @Test
    fun `every numeric table entry resolves to a real placeholder and agrees with the name path`() {
        // Pins the table against typos: a key that does not exist in PLACEHOLDERS would silently make
        // that type unstorable, and a typo'd KEY would only show up on a device.
        assertTrue("the table must not be empty", KuaishouEnvelope.MSG_TYPE_KEYS.isNotEmpty())
        for ((type, key) in KuaishouEnvelope.MSG_TYPE_KEYS) {
            val resolved = KuaishouEnvelope.byType(type)
            assertTrue("msgType $type -> key '$key' must resolve in PLACEHOLDERS", resolved != null)
            assertEquals("byType must return the table's token", KuaishouEnvelope.PLACEHOLDERS.getValue(key), resolved)

            // Where the key is itself a class simple-name, both paths must give the SAME token --
            // otherwise the two paths would disagree depending on which one answered.
            val viaName = KuaishouEnvelope.placeholderFor(key)
            if (viaName != null) {
                assertEquals("paths must agree for '$key'", viaName, resolved)
            }
        }
        assertTrue("the label column in the evidence table was 15 rows", KuaishouEnvelope.MSG_TYPE_KEYS.size == 15)
    }

    // ------------------------------------------------------------------ timestamps

    @Test
    fun `timestamps are normalised to milliseconds by magnitude`() {
        // The unit is NOT confirmed statically, and the live feed rejects any time below 1e12, so an
        // unnormalised seconds value would drop EVERY message. Normalisation is what prevents that.
        val millis = 1_700_000_000_000L
        assertEquals(millis, KuaishouEnvelope.toMillis(millis))
        assertEquals("seconds -> millis", millis, KuaishouEnvelope.toMillis(1_700_000_000L))
        assertEquals("micros -> millis", millis, KuaishouEnvelope.toMillis(1_700_000_000_000_000L))
        assertEquals(0L, KuaishouEnvelope.toMillis(0L))
    }

    @Test
    fun `every normalised timestamp clears the epoch gate in the live feed`() {
        // The gate is `createTime < 1_000_000_000_000L` -> rejected. Asserting it here means a change to
        // the normalisation cannot quietly reintroduce "history stays empty and every line looks fine".
        val samples = listOf(1_700_000_000L, 1_700_000_000_000L, 1_700_000_000_000_000L)
        for (raw in samples) {
            val ms = KuaishouEnvelope.toMillis(raw)
            assertTrue(
                "raw=$raw normalised to $ms must clear the 1e12 epoch gate",
                ms >= 1_000_000_000_000L,
            )
        }
    }

    @Test
    fun `time branches are named for the log`() {
        assertEquals("zero", KuaishouEnvelope.timeBranch(0L))
        assertEquals("seconds", KuaishouEnvelope.timeBranch(1_700_000_000L))
        assertEquals("millis", KuaishouEnvelope.timeBranch(1_700_000_000_000L))
        assertEquals("micros", KuaishouEnvelope.timeBranch(1_700_000_000_000_000L))
    }

    // ------------------------------------------------------------------ identity helpers

    @Test
    fun `a synthetic id is stable and distinct`() {
        val a = KuaishouEnvelope.syntheticId("talker", 1_700_000_000_000L, false, "hi")
        val b = KuaishouEnvelope.syntheticId("talker", 1_700_000_000_000L, false, "hi")
        val c = KuaishouEnvelope.syntheticId("talker", 1_700_000_000_000L, true, "hi")
        assertEquals("the same message must produce the same id", a, b)
        assertFalse("direction must change the id", a == c)
        assertTrue("the id must be positive", a > 0L)
    }

    @Test
    fun `simple names handle nested and plain classes`() {
        assertEquals("ImageMsg", KuaishouEnvelope.simpleName("com.kwai.imsdk.msg.ImageMsg"))
        assertEquals("d", KuaishouEnvelope.simpleName("r9j.n\$d"))
        assertEquals("", KuaishouEnvelope.simpleName(null))
        assertEquals("", KuaishouEnvelope.simpleName("  "))
    }
}
