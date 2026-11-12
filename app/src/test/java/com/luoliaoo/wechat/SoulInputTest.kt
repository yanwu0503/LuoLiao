package com.luoliaoo.wechat

import com.luoliaoo.wechat.ui.SoulInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.0.0: SOUL input-box capture (card 编辑 / 长按悬浮球).
 *
 * WHY THIS TEST EXISTS AND WHY IT IS PURE: there is NO device attached, so "which EditText did the
 * module pick in Soul, and did the text actually land?" cannot be answered by running it. Both
 * questions are therefore reduced to pure logic -- a class-chain classifier and a candidate
 * selector -- and pinned here. A Kotlin object with no Android imports is also the only shape that
 * this JVM test suite can exercise directly (`HostTextTest` sets the same precedent for host text).
 *
 * The class names below are NOT invented: they are the ones read out of Soul 6.37.0's dex
 * (`_tools/souldex/`, via `_tools/soul_xref.py`) and out of its binary layouts (`res/3Ph.xml`,
 * `res/4qg.xml`). See `ui/SoulInput.kt` for the byte-level evidence behind each.
 *
 * Comments are ASCII-only (see core/Log.kt).
 */
class SoulInputTest {

    // ------------------------------------------------------- the class chain classifier

    @Test
    fun theRealChatInputChainIsRecognisedAsLeaf() {
        // The EXACT chain Soul 6.37.0 ships, verified in the dex. If a future refactor of
        // SoulInput stops matching this, the feature silently dead-ends on a real device.
        val chain = listOf(
            "cn.soulapp.android.component.chat.view.ConversationPasteEditText",
            "cn.soulapp.android.square.view.PasteEditText",
            "cn.soulapp.android.view.CaptureTouchEditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "android.widget.EditText",
            "android.widget.TextView",
            "android.view.View",
        )
        assertEquals(SoulInput.Match.LEAF, SoulInput.classifyChain(chain))
    }

    @Test
    fun anUnchangedParentIsStillAcceptedButWeaker() {
        // A Soul update that merges the thin leaf away must not break input capture: the parent
        // still identifies the widget family. It is reported as PARENT precisely because
        // PasteEditText is ALSO the parent of Soul's square/publish fields, i.e. weaker evidence.
        val chain = listOf(
            "cn.soulapp.android.square.view.PasteEditText",
            "cn.soulapp.android.view.CaptureTouchEditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "android.widget.EditText",
        )
        assertEquals(SoulInput.Match.PARENT, SoulInput.classifyChain(chain))
    }

    @Test
    fun aVendorSubclassOfTheLeafStillWins() {
        // A cloner/thinner wrapper must not be rejected just because its own name is new.
        val chain = listOf(
            "cn.soulapp.android.component.chat.view.ConversationPasteEditTextX",
            "cn.soulapp.android.component.chat.view.ConversationPasteEditText",
            "cn.soulapp.android.square.view.PasteEditText",
        )
        assertEquals(SoulInput.Match.LEAF, SoulInput.classifyChain(chain))
    }

    @Test
    fun unrelatedSoulTextFieldsAreNotChatInputs() {
        // Every one of these exists in Soul 6.37.0 and every one is an EditText. Matching any of
        // them would write the user's reply into a search box, a miniprogram field or a dialog.
        val notChatInput = listOf(
            listOf("cn.soulapp.android.miniprogram.utils.input.MentionEditText"),
            listOf("cn.soulapp.cpnt_voiceparty.widget.MentionEditText"),
            listOf("com.vanniktech.emoji.EmojiEditText"),
            listOf("com.soul.srn.common.component.input.SRNEditText"),
            listOf("cn.android.lib.soul_view.MyEditText"),
            listOf("cn.soulapp.android.square.view.MyEditText"),
            listOf("cn.soulapp.android.component.chat.view.AddMusicUrlEditText"),
            listOf("android.widget.EditText"),
            listOf("androidx.appcompat.widget.AppCompatEditText"),
            listOf("com.google.android.material.textfield.TextInputEditText"),
            listOf("android.widget.SearchView\$SearchAutoComplete"),
        )
        for (chain in notChatInput) {
            assertEquals("must NOT match: " + chain[0], SoulInput.Match.NONE,
                SoulInput.classifyChain(chain))
        }
    }

    @Test
    fun exactNamesAreTheOnesSoulShips() {
        // Pinned literally: a typo in a constant is a silent no-op on the device.
        assertTrue(SoulInput.isChatInputName("cn.soulapp.android.component.chat.view.ConversationPasteEditText"))
        assertTrue(SoulInput.isChatInputName("cn.soulapp.android.square.view.PasteEditText"))
        assertFalse(SoulInput.isChatInputName(null))
        assertFalse(SoulInput.isChatInputName(""))
        // A prefix/suffix near-miss must not be accepted by the NAME test.
        assertFalse(SoulInput.isChatInputName("cn.soulapp.android.square.view.PasteEditTextX"))
    }

    @Test
    fun anEmptyOrNullChainIsNone() {
        assertEquals(SoulInput.Match.NONE, SoulInput.classifyChain(emptyList()))
    }

    // ------------------------------------------------------------------- the selector

    private fun cand(
        name: String,
        match: SoulInput.Match,
        visible: Boolean = true,
        ownUi: Boolean = false,
        w: Int = 800,
        h: Int = 120,
        bottom: Int = 1000,
    ) = SoulInput.Candidate(name, match, visible, ownUi, w, h, bottom)

    private val leaf = "cn.soulapp.android.component.chat.view.ConversationPasteEditText"
    private val parent = "cn.soulapp.android.square.view.PasteEditText"

    @Test
    fun theLowestLeafWins() {
        val list = listOf(
            cand(leaf, SoulInput.Match.LEAF, bottom = 500),
            cand(leaf, SoulInput.Match.LEAF, bottom = 2000),
            cand(leaf, SoulInput.Match.LEAF, bottom = 900),
        )
        val c = SoulInput.choose(list)
        assertTrue(c is SoulInput.Choice.Found)
        assertEquals(1, (c as SoulInput.Choice.Found).index)
        assertEquals(SoulInput.Match.LEAF, c.match)
    }

    @Test
    fun aLeafBeatsALowerParent() {
        // The decisive rule of this task: Soul's square/publish fields share the PARENT class, and
        // they are frequently lower on screen than the chat bar inside a bottom sheet. Choosing the
        // parent there would paste the reply into the wrong field, so an exact hit wins on EVIDENCE,
        // not on position.
        val list = listOf(
            cand(leaf, SoulInput.Match.LEAF, bottom = 400),
            cand(parent, SoulInput.Match.PARENT, bottom = 3000),
        )
        val c = SoulInput.choose(list) as SoulInput.Choice.Found
        assertEquals(0, c.index)
        assertEquals(SoulInput.Match.LEAF, c.match)
    }

    @Test
    fun aParentIsUsedOnlyWhenNoLeafExists() {
        val list = listOf(
            cand("cn.soulapp.android.view.CaptureTouchEditText", SoulInput.Match.NONE),
            cand(parent, SoulInput.Match.PARENT, bottom = 700),
            cand(parent, SoulInput.Match.PARENT, bottom = 1500),
        )
        val c = SoulInput.choose(list) as SoulInput.Choice.Found
        assertEquals(2, c.index)
        assertEquals(SoulInput.Match.PARENT, c.match)
    }

    @Test
    fun ourOwnFollowUpFieldIsNeverChosen() {
        // The card's own follow-up EditText: picking it would make the module's 编辑 button write
        // into the module's own panel instead of the chat box.
        val list = listOf(
            cand(parent, SoulInput.Match.PARENT, ownUi = true, bottom = 3000),
            cand(leaf, SoulInput.Match.LEAF, bottom = 1000),
        )
        val c = SoulInput.choose(list) as SoulInput.Choice.Found
        assertEquals(1, c.index)
    }

    @Test
    fun anInvisibleOrSizelessFieldIsRejected() {
        val list = listOf(
            cand(leaf, SoulInput.Match.LEAF, visible = false, bottom = 3000),
            cand(leaf, SoulInput.Match.LEAF, w = 0, h = 0, bottom = 2500),
            cand(leaf, SoulInput.Match.LEAF, bottom = 1000),
        )
        val c = SoulInput.choose(list) as SoulInput.Choice.Found
        assertEquals(2, c.index)
    }

    @Test
    fun everyRejectedFieldYieldsAReasonNotSilence() {
        // The project rule: a branch that can no-op must log WHY. None carries the reason token so
        // the caller can print a line that proves the path ran.
        val wrongClassOnly = listOf(cand("android.widget.EditText", SoulInput.Match.NONE))
        val a = SoulInput.choose(wrongClassOnly)
        assertTrue(a is SoulInput.Choice.None)
        assertEquals("no-soul-input-class", (a as SoulInput.Choice.None).reason)
        assertEquals(0, a.considered)

        val allBad = listOf(cand(leaf, SoulInput.Match.LEAF, visible = false))
        val b = SoulInput.choose(allBad)
        assertTrue(b is SoulInput.Choice.None)
        assertEquals("all-rejected", (b as SoulInput.Choice.None).reason)
        assertEquals(1, b.considered)

        val empty = SoulInput.choose(emptyList())
        assertEquals("no-candidates", (empty as SoulInput.Choice.None).reason)
    }

    @Test
    fun aWrongClassOnscreenDoesNotBlockAValidLeaf() {
        // Real situation: Soul's window holds several EditTexts at once. The classifier ignores the
        // ones it does not know and still returns the chat box.
        val list = listOf(
            cand("com.vanniktech.emoji.EmojiEditText", SoulInput.Match.NONE, bottom = 3000),
            cand("cn.soulapp.android.miniprogram.utils.input.MentionEditText", SoulInput.Match.NONE),
            cand(leaf, SoulInput.Match.LEAF, bottom = 1000),
        )
        val c = SoulInput.choose(list) as SoulInput.Choice.Found
        assertEquals(2, c.index)
    }

    // ------------------------------------------------------------------ the write verdict

    @Test
    fun anExactWriteIsReportedOk() {
        val v = SoulInput.verify("你好，在吗", "你好，在吗")
        assertTrue(v.ok)
        assertEquals("exact", v.reason)
        assertEquals(5, v.requestedLen)
        assertEquals(5, v.landedLen)
    }

    @Test
    fun aWriteThatDidNotLandIsNamedNotIgnored() {
        // THE failure this task must never leave silent: setText on Soul's chart box did not stick.
        val v = SoulInput.verify("hello world", "")
        assertFalse(v.ok)
        assertEquals("blank", v.reason)
        assertTrue("the verdict must be loggable in one ASCII line", v.line().contains("reason=blank"))
    }

    @Test
    fun aTruncatedWriteIsAFailureNotASuccess() {
        // This is the case the OLD check missed: the field is non-blank, so "is it empty?" said
        // success while the user saw a half-written message.
        val v = SoulInput.verify("hello world", "hello")
        assertFalse(v.ok)
        assertEquals("truncated", v.reason)
    }

    @Test
    fun anAppendedWriteIsAFailure() {
        val v = SoulInput.verify("hello", "hello world")
        assertFalse(v.ok)
        assertEquals("extra-text-appended", v.reason)
    }

    @Test
    fun aRewrittenWriteIsAFailure() {
        val v = SoulInput.verify("hello", "HELLO")
        assertFalse(v.ok)
        assertEquals("differs", v.reason)
    }

    @Test
    fun aWhitespaceOnlyDifferenceStillCountsAsLanded() {
        // An input filter may trim or pad; the value IS in the box, so this must not send the user
        // to the clipboard. Its own reason keeps the log honest about what happened.
        val v = SoulInput.verify("hello", "  hello ")
        assertTrue(v.ok)
        assertEquals("surrounding-whitespace-differs", v.reason)
    }

    @Test
    fun clearingAnEmptyBoxWithEmptyTextIsOk() {
        // The degenerate case must not be reported as "unexpected-text".
        assertTrue(SoulInput.verify("", "").ok)
    }

    @Test
    fun textAppearingWhenNothingWasRequestedIsAFailure() {
        val v = SoulInput.verify("", "leftover")
        assertFalse(v.ok)
        assertEquals("unexpected-text", v.reason)
    }

    @Test
    fun aWhitespaceOnlyRequestAgainstABlankFieldIsOk() {
        // "   " landed as "": trims to the same thing, so it is the whitespace case, not "blank".
        val v = SoulInput.verify("   ", " ")
        assertTrue(v.ok)
        assertEquals("surrounding-whitespace-differs", v.reason)
    }

    @Test
    fun theVerdictLineIsAlwaysASCIISoItSurvivesTheLog() {
        // The module's DebugLog is plain text; a Chinese literal in the verdict would be the kind of
        // encoding trap 必看.md records. The reason tokens and the line format must stay ASCII.
        for (v in listOf(
            SoulInput.verify("a", "a"),
            SoulInput.verify("a", ""),
            SoulInput.verify("a", "b"),
            SoulInput.verify("hello", "hello world"),
        )) {
            assertTrue("must be ASCII: " + v.line(), v.line().all { it.code in 0x20..0x7E })
        }
    }
}
