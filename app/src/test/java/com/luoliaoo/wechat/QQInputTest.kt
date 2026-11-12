package com.luoliaoo.wechat

import com.luoliaoo.wechat.ui.QQInput
import com.luoliaoo.wechat.ui.SoulInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.0.0: QQ (com.tencent.mobileqq 9.3.65) input-box capture (card 编辑 / 长按悬浮球).
 *
 * WHY THIS TEST EXISTS AND WHY IT IS PURE: "which EditText did the module pick in QQ, and did the text
 * actually land?" is answered at runtime by the picker, but the DECISION rules are pure logic and this
 * is the only place they can be pinned exhaustively off-device. Every rule exercised here was derived
 * from a measurement, and the measurement is quoted next to the test so a future reader can tell a
 * regression from a deliberate change.
 *
 * The class names, ids and sizes below are NOT invented. They come from:
 *   - the LIVE view tree on the rooted device (`dumpsys activity top`, root shell), whose EditText
 *     lines included, verbatim:
 *         com.tencent.mobileqq.aio.input.edit.AIOEditText{... 51,19-954,152 #7f0a3a5c app:id/input ...}
 *         com.tencent.mobileqq.search.view.QuickPinyinEditText{... 0,0-108,114 #7f0a63e8 ...}
 *         androidx.appcompat.widget.AppCompatEditText{... 140,0-16777090,140 #7f0a0653 ...}
 *   - all 25,095 compiled layouts under the APK's obfuscated `r/` directory, which show
 *     `AIOEditText` declared in EIGHT layouts using FOUR different ids (see QQInput's header).
 *   - `aapt2 dump resources`, which resolves `0x7f0a3a5c` to `id/input` and the layout's
 *     `maxLength` ref to the integer `4500`.
 *
 * Comments are ASCII-only (see core/Log.kt); Chinese uses \uXXXX escapes.
 */
class QQInputTest {

    // ---------------------------------------------------------------- the measured class+id pair

    /**
     * The exact live pair must win, and it must be reported as the class+id tier -- that tier is the
     * only one whose evidence came from a single runtime line, so a regression that demotes it (for
     * example by matching the id before the class) must fail here.
     */
    @Test
    fun theLiveClassPlusIdPairIsChosenAsTheTopTier() {
        val chosen = QQInput.choose(
            listOf(
                // The search box, live at the same time on the main screen.
                QQInput.Candidate(
                    className = "com.tencent.mobileqq.search.view.QuickPinyinEditText",
                    idValue = 0x7f0a63e8, width = 108, height = 114, bottom = 114, leaf = true,
                ),
                // THE chat box, exactly as the device reported it.
                QQInput.Candidate(
                    className = QQInput.INPUT_CLASS,
                    idValue = QQInput.INPUT_ID, width = 903, height = 133, bottom = 153, leaf = true,
                ),
            ),
        )
        assertTrue(chosen is QQInput.Choice.Found)
        val found = chosen as QQInput.Choice.Found
        assertEquals(QQInput.Reason.CLASS_AND_ID, found.reason)
        assertEquals(1, found.index)
    }

    /**
     * THE reason the task required a DUAL criterion, expressed as a test.
     *
     * The id `0x7f0a3a5c` is reused by QQ's share panel comment box (`r/i/d06.xml`/`d07.xml` declare an
     * `AIOEditText` with a different id, but `8v`/`ep4`/`eyh`/`eyt` share this one) and by other
     * layouts. An id-only matcher would therefore accept a field whose CLASS is not the chat widget. It
     * must instead rank below the class test.
     */
    @Test
    fun theIdAloneDoesNotOutrankTheRealClass() {
        val chosen = QQInput.choose(
            listOf(
                // Wrong class, right id: must NOT be reported as the class+id tier.
                QQInput.Candidate(
                    className = "com.example.SomeOtherEditText",
                    idValue = QQInput.INPUT_ID, width = 500, height = 100, bottom = 200, leaf = true,
                ),
                // Right class, right id: the real pair, ranked first.
                QQInput.Candidate(
                    className = QQInput.INPUT_CLASS,
                    idValue = QQInput.INPUT_ID, width = 903, height = 133, bottom = 153, leaf = true,
                ),
            ),
        )
        val found = chosen as QQInput.Choice.Found
        assertEquals(QQInput.Reason.CLASS_AND_ID, found.reason)
        assertEquals(1, found.index)
    }

    /** An id match on a non-chat class is still usable, but only at the weakest tier. */
    @Test
    fun theIdAloneIsAcceptedAtTheWeakestTier() {
        val chosen = QQInput.choose(
            listOf(
                QQInput.Candidate(
                    className = "com.example.SomeOtherEditText",
                    idValue = QQInput.INPUT_ID, width = 500, height = 100, bottom = 200, leaf = true,
                ),
            ),
        )
        val found = chosen as QQInput.Choice.Found
        assertEquals(QQInput.Reason.BY_ID, found.reason)
        assertEquals(0, found.index)
    }

    /**
     * A QQ update that re-themes the input bar (there IS a `skin_aio_input_bar_bg_local_albums`
     * resource) may change the id while keeping the widget. The class alone must still be accepted, and
     * must rank ABOVE a bare id match on an unknown class.
     */
    @Test
    fun anIdChangeDoesNotBreakCaptureBecauseTheClassStillWins() {
        val chosen = QQInput.choose(
            listOf(
                QQInput.Candidate(
                    className = "com.other.UnknownEditText",
                    idValue = QQInput.INPUT_ID, width = 500, height = 100, bottom = 200, leaf = true,
                ),
                QQInput.Candidate(
                    className = QQInput.INPUT_CLASS,
                    idValue = 0x7f0a9999, width = 903, height = 133, bottom = 153, leaf = true,
                ),
            ),
        )
        val found = chosen as QQInput.Choice.Found
        assertEquals(QQInput.Reason.EXACT_CLASS, found.reason)
        assertEquals(1, found.index)
    }

    // -------------------------------------------------------- the traps a bare walk would fall into

    /**
     * THE measurement-broken node. `right = 16777090` is the identical pathology t3 measured on
     * Kuaishou. Any positional rule ("lowest visible EditText", or one weighing right/area) picks it and
     * reports a successful fill while the real chat box stays empty -- 必看.md's
     * "\u5df2\u586b\u5165 but the box is empty" failure.
     *
     * Note it is also `AppCompatEditText` with a UNIQUE id (`0x7f0a0653`), so neither the class test nor
     * the id test can select it. This test pins that: the broken node must never be chosen, and its
     * absurd height must be rejected by the sane-size gate.
     */
    @Test
    fun theMeasurementBrokenNodeIsRejectedByTheSaneSizeGate() {
        val broken = QQInput.Candidate(
            className = "androidx.appcompat.widget.AppCompatEditText",
            idValue = 0x7f0a0653, width = 16776950, height = 140, bottom = 140, leaf = true,
        )
        assertFalse("a 16.7-million-px field must not be called sane", QQInput.saneSize(broken))
        assertFalse(QQInput.eligible(broken))

        // With ONLY the broken node present, the honest answer is "nothing", never a guess.
        val chosen = QQInput.choose(listOf(broken))
        assertTrue(chosen is QQInput.Choice.None)
        assertEquals(QQInput.Reason.NO_QQ_INPUT, (chosen as QQInput.Choice.None).reason)    }

    /**
     * The broken node must not be chosen even when a REAL chat box is present -- a positional tie-break
     * would still prefer it, because its `bottom`/area dwarf everything on screen. This is asserted by
     * checking that the real pair wins while the broken node is in the same list.
     */
    @Test
    fun theRealBoxWinsEvenWithTheBrokenNodePresent() {
        val chosen = QQInput.choose(
            listOf(
                QQInput.Candidate(
                    className = "androidx.appcompat.widget.AppCompatEditText",
                    idValue = 0x7f0a0653, width = 16776950, height = 140, bottom = 140, leaf = true,
                ),
                QQInput.Candidate(
                    className = QQInput.INPUT_CLASS,
                    idValue = QQInput.INPUT_ID, width = 903, height = 133, bottom = 153, leaf = true,
                ),
            ),
        )
        val found = chosen as QQInput.Choice.Found
        assertEquals(1, found.index)
    }

    /** The search field is a plain `EditText`, so a bare `instanceof` walk finds it. Name test rejects it. */
    @Test
    fun theSearchFieldIsRejectedByName() {
        assertTrue(QQInput.looksLikeSearch("com.tencent.mobileqq.search.view.QuickPinyinEditText"))
        val search = QQInput.Candidate(
            className = "com.tencent.mobileqq.search.view.QuickPinyinEditText",
            idValue = QQInput.INPUT_ID, // even with the chat id, the name disqualifies it
            width = 900, height = 120, bottom = 2000, leaf = true,
        )
        assertFalse(QQInput.eligible(search))

        val chosen = QQInput.choose(listOf(search))
        assertTrue(chosen is QQInput.Choice.None)
    }

    // ----------------------------------------------- the measured id-decoy gate (a real correction)

    /**
     * ============================ THIS TEST EXISTS BECAUSE OF A MEASURED CORRECTION ==============
     * An independent second dump of the live chat page (`uiautomator dump`, `_tools/qq/qa/ui_chat.xml`)
     * shows `id/input` occurring EXACTLY ONCE on that screen, which invites the conclusion "`id/input`
     * is globally unique, so `id + instanceof EditText` is enough".
     *
     * That inference is true for ONE SCREEN and false for the APK. `_tools/qq/qq_id_usage.py` scans all
     * 25,095 compiled layouts and finds `android:id="@ref/0x7f0a3a5c"` on **19 layouts across 5
     * different widget classes**, every one of them an `EditText` subclass:
     *
     *     com.tencent.widget.XEditTextEx                                x9
     *     com.tencent.mobileqq.aio.input.edit.AIOEditText               x4   <-- the chat box
     *     EditText                                                      x3
     *     com.tencent.mobileqq.widget.ClearableEditText                 x2
     *     com.tencent.common.danmaku.edit.views.TKDListenFocusEditText  x1
     *
     * So a danmaku editor or a ClearableEditText field would satisfy `id/input` + `instanceof EditText`
     * and be filled instead of the chat box -- a false success of exactly the kind 必看.md records.
     * The decoy gate below is therefore load-bearing, and these classes are NOT invented: each was read
     * out of the layouts that carry the id.
     */
    @Test
    fun theMeasuredIdDecoyClassesAreRejected() {
        // Each of these is an EditText subclass, so only a NAME test can exclude them.
        assertTrue(QQInput.looksLikeIdDecoy("com.tencent.widget.XEditTextEx"))
        assertTrue(QQInput.looksLikeIdDecoy("com.tencent.mobileqq.widget.ClearableEditText"))
        assertTrue(
            QQInput.looksLikeIdDecoy(
                "com.tencent.common.danmaku.edit.views.TKDListenFocusEditText",
            ),
        )
        // The real chat field must NOT be treated as a decoy, or nothing would ever be chosen.
        assertFalse(QQInput.looksLikeIdDecoy(QQInput.INPUT_CLASS))
        // A plain android.widget.EditText is the raw XML tag used by jt/jw/k1; it is not a decoy
        // class either (it is simply weaker evidence, which is why it sits at tier 3).
        assertFalse(QQInput.looksLikeIdDecoy("android.widget.EditText"))
    }

    /**
     * The decoy gate must actually change the OUTCOME, not merely exist: a decoy carrying the id must
     * not be selected, and the report must say `id-on-decoy` rather than the generic `no-qq-input`,
     * because those two are different diagnoses.
     */
    @Test
    fun aDecoyCarryingTheIdIsNotSelectedAndIsReportedSeparately() {
        val decoy = QQInput.Candidate(
            className = "com.tencent.widget.XEditTextEx",
            idValue = QQInput.INPUT_ID, width = 900, height = 130, bottom = 2400, leaf = true,
        )
        // It IS eligible by the generic gates -- that is the whole point.
        assertTrue(QQInput.eligible(decoy))

        val chosen = QQInput.choose(listOf(decoy))
        assertTrue(chosen is QQInput.Choice.None)
        assertEquals(QQInput.Reason.ID_ON_DECOY, (chosen as QQInput.Choice.None).reason)
    }

    /** A decoy must lose to the real class+id pair even when it appears FIRST in the list. */
    @Test
    fun theRealChatBoxBeatsADecoyThatComesFirst() {
        val chosen = QQInput.choose(
            listOf(
                QQInput.Candidate(
                    className = "com.tencent.widget.XEditTextEx",
                    idValue = QQInput.INPUT_ID, width = 900, height = 130, bottom = 2400, leaf = true,
                ),
                QQInput.Candidate(
                    className = QQInput.INPUT_CLASS,
                    idValue = QQInput.INPUT_ID, width = 903, height = 133, bottom = 2558, leaf = true,
                ),
            ),
        )
        val found = chosen as QQInput.Choice.Found
        assertEquals(QQInput.Reason.CLASS_AND_ID, found.reason)
        assertEquals(1, found.index)
    }

    /** With nothing on screen at all, the reason must stay the generic one, not `id-on-decoy`. */
    @Test
    fun anEmptyTreeReportsNoQqInputNotIdOnDecoy() {
        assertEquals(QQInput.Reason.NO_CANDIDATES, (QQInput.choose(emptyList()) as QQInput.Choice.None).reason)
        val onlyUnrelated = QQInput.Candidate(
            className = "com.example.Plain",
            idValue = 0x7f0a1234, width = 100, height = 50, bottom = 100, leaf = true,
        )
        assertEquals(
            QQInput.Reason.NO_QQ_INPUT,
            (QQInput.choose(listOf(onlyUnrelated)) as QQInput.Choice.None).reason,
        )
    }

    /**
     * A 0x0 throwaway field must not win. Some layouts declare an `AIOEditText` with
     * `layout_height=0xfffffffe`/`0x1` combinations, and an unmeasured view reports 0x0.
     */
    @Test
    fun aZeroSizedFieldIsRejected() {        val zero = QQInput.Candidate(
            className = QQInput.INPUT_CLASS,
            idValue = QQInput.INPUT_ID, width = 0, height = 0, bottom = 0, leaf = true,
        )
        assertFalse(QQInput.saneSize(zero))
        assertTrue(QQInput.choose(listOf(zero)) is QQInput.Choice.None)
    }

    /** Our own UI must never be selected, or the module would write into itself. */
    @Test
    fun ourOwnFieldIsRejectedEvenWithTheRightClassAndId() {
        val ours = QQInput.Candidate(
            className = QQInput.INPUT_CLASS,
            idValue = QQInput.INPUT_ID, width = 900, height = 120, bottom = 1500,
            ownUi = true, leaf = true,
        )
        assertFalse(QQInput.eligible(ours))
        assertTrue(QQInput.choose(listOf(ours)) is QQInput.Choice.None)
    }

    /** An invisible field is not the chat box the user is looking at. */
    @Test
    fun anInvisibleFieldIsRejected() {
        val hidden = QQInput.Candidate(
            className = QQInput.INPUT_CLASS,
            idValue = QQInput.INPUT_ID, width = 900, height = 120, bottom = 1500,
            visible = false, leaf = true,
        )
        assertFalse(QQInput.eligible(hidden))
        assertTrue(QQInput.choose(listOf(hidden)) is QQInput.Choice.None)
    }

    // ------------------------------------------------------------------------ class-name matching

    /**
     * The class test must be a NAME test, not a package-prefix test. QQ ships OTHER `EditText`s whose
     * names share the `aio`/`input` vocabulary -- `com.tencent.guild.aio.input.widget.GuildAIOInputEditText`
     * is the measured example -- so a prefix rule would accept a Guild (channel) input box.
     */
    @Test
    fun aSiblingAioInputWidgetIsNotAcceptedByAPrefixRule() {
        assertFalse(QQInput.isInputClass("com.tencent.guild.aio.input.widget.GuildAIOInputEditText"))
        assertTrue(QQInput.isInputClass(QQInput.INPUT_CLASS))
    }

    /**
     * QQ ships RFix/Tinker (1.9.14.20). A hot-patch can wrap the field in a generated subclass that
     * keeps the simple name but changes the package, so the simple-name route must still match.
     */
    @Test
    fun aHotPatchGeneratedSubclassWithTheSameSimpleNameIsAccepted() {
        // The durable part is the SIMPLE name: an RFix-generated subclass in a different package must
        // still be recognised, or a hot-patched QQ would silently dead-end input capture.
        assertTrue(QQInput.isInputClass("some.other.pkg.AIOEditText"))
        // A nested generated class is NOT accepted, and that is correct rather than a gap: the attached
        // view is the OUTER widget, never a synthetic inner class. Pinned so the rule is explicit.
        assertFalse(QQInput.isInputClass("some.pkg.AIOEditText\$Generated"))
    }

    /** Empty/null names must never match, or a stale view with no class info could be chosen. */
    @Test
    fun aMissingClassNameIsNotAMatch() {
        assertFalse(QQInput.isInputClass(null))
        assertFalse(QQInput.isInputClass(""))
        assertFalse(QQInput.isInputClass("   "))
    }

    /** The documented constants must be pinned, since the whole picker is calibrated on them. */
    @Test
    fun theMeasuredConstantsAreTheOnesTheDeviceReported() {
        assertEquals("com.tencent.mobileqq.aio.input.edit.AIOEditText", QQInput.INPUT_CLASS)
        assertEquals(0x7f0a3a5c, QQInput.INPUT_ID)
        assertEquals(0x7f0a77e0, QQInput.SEND_BTN_ID)
        // aapt2 resolved the chat layout's maxLength ref (0x7f0b0024) to this integer.
        assertEquals(4500, QQInput.MAX_LENGTH)
    }

    // ------------------------------------------------------------- the write-verification contract

    /**
     * QQ is routed to `SoulInput.verify` in `InputInjector.fill`, so the four failure names the task
     * requires must be producible for QQ's value shapes. These repeat Soul's vocabulary on purpose:
     * the point is that QQ CANNOT drift to a different vocabulary, and that a QQ-length value (near
     * MAX_LENGTH) is classified as `truncated` rather than silently succeeding.
     */
    @Test
    fun anExactWriteVerifies() {
        val v = SoulInput.verify("\u4f60\u597d\u4e16\u754c", "\u4f60\u597d\u4e16\u754c")
        assertTrue(v.ok)
        assertEquals("exact", v.reason)
    }

    /** `maxLength` = 4500 truncates SILENTLY via a built-in LengthFilter. */
    @Test
    fun aTruncatedWriteIsNamedTruncated() {
        val requested = "a".repeat(5000)
        val landed = "a".repeat(QQInput.MAX_LENGTH)
        val v = SoulInput.verify(requested, landed)
        assertFalse(v.ok)
        assertEquals("truncated", v.reason)
        assertEquals(5000, v.requestedLen)
        assertEquals(4500, v.landedLen)
    }

    /** A field that keeps our text but appends its own (a prefix/suffix splicer) must be named. */
    @Test
    fun anAppendedWriteIsNamedExtraTextAppended() {
        val requested = "\u4f60\u597d"
        val landed = requested + "\u56de\u590d"
        val v = SoulInput.verify(requested, landed)
        assertFalse(v.ok)
        assertEquals("extra-text-appended", v.reason)
    }

    /** A rewritten value (an emoticon/rich-text renderer) must be named `differs`, not accepted. */
    @Test
    fun aRewrittenWriteIsNamedDiffers() {
        val v = SoulInput.verify("abc123", "xyz789")
        assertFalse(v.ok)
        assertEquals("differs", v.reason)
    }

    /** A write that landed nowhere at all is `blank` -- the failure `isBlank()` alone would catch. */
    @Test
    fun aBlankWriteIsNamedBlank() {
        val v = SoulInput.verify("\u4f60\u597d", "")
        assertFalse(v.ok)
        assertEquals("blank", v.reason)
    }

    /**
     * `QQInput.landed` is the picker-side narrower check: it must require a NON-EMPTY exact match, so
     * that neither an empty box nor a partial value can be reported as a landed write.
     */
    @Test
    fun thePickersLandedCheckRequiresNonEmptyExactMatch() {
        assertTrue(QQInput.landed("hello", "hello"))
        assertFalse("an empty box must not count as landed", QQInput.landed("", "hello"))
        assertFalse("a partial value must not count", QQInput.landed("he", "hello"))
        assertFalse("an appended value must not count", QQInput.landed("helloX", "hello"))
        assertFalse("a null Editable must not count", QQInput.landed(null, "hello"))
    }

    /**
     * Regression guard for the exact shape that produced a false success in the field: the box is
     * NON-EMPTY but holds the wrong text. A check that only asks "is something there?" would pass it.
     */
    @Test
    fun aNonEmptyButWrongValueIsNotASuccess() {
        assertFalse(QQInput.landed("\u5176\u4ed6\u5185\u5bb9", "\u4f60\u597d"))
        val v = SoulInput.verify("\u4f60\u597d", "\u5176\u4ed6\u5185\u5bb9")
        assertFalse(v.ok)
    }

    // ----------------------------------------------------------------------------- logging helpers

    /**
     * `describe` is what makes a rejection explainable in logcat rather than a bare "no input box"; it
     * must carry the class, the id in hex, the size, and the two gate results.
     */
    @Test
    fun describeCarriesTheEvidenceNeededToExplainARejection() {
        val s = QQInput.describe(
            QQInput.Candidate(
                className = QQInput.INPUT_CLASS,
                idValue = QQInput.INPUT_ID, width = 903, height = 133, bottom = 153, leaf = true,
            ),
        )
        assertTrue(s.contains(QQInput.INPUT_CLASS))
        assertTrue(s.contains("7f0a3a5c"))
        assertTrue(s.contains("903x133"))
        assertTrue(s.contains("saneSize=true"))
        assertTrue(s.contains("search=false"))
    }
}
