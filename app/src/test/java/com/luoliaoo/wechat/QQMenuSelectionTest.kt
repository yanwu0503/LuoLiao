package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.QQMenuSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QQMenuSelection] -- reading QQ's multi-select state.
 *
 * ============================== WHAT THESE TESTS PIN ==============================
 *
 * CORRECTION (this header previously stated the opposite, and the wrongness caused a device bug):
 * QQ DOES carry a per-message selection flag -- `com.tencent.mobileqq.aio.msg.AIOMsgItem#K2()Z`. The
 * earlier claim that "nothing mentions select" came from searching for fields/methods whose NAME contains
 * "select", which `K2` does not. The host's own batch builder filters its message list with `K2()`
 * (`childvm.j#l` at [00bd]/[00c1]), which is what identifies it.
 *
 * So the read path is: take the host's message list, narrow it with `K2()`. The narrowing rule lives in
 * [QQMenuSelection.selectedOnly] and is pinned below, because getting it wrong is the exact failure the
 * user hit: a long-press of ONE message analysed SEVEN (the loaded messages), and the dialog said
 * 分析已选消息 instead of 分析范围.
 *
 * The dangerous directions, both pinned below:
 *   * treating the host's list as "the selection" -> messages the user never chose get analysed
 *     (a correctness AND privacy failure);
 *   * a predicate that throws -> must not abort the batch, and must not promote the item into it;
 *   * taking the batch path for one message -> a plain "analyse this" press changes the dialog.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQMenuSelectionTest {

    /** A stand-in for a selected message. */
    private class Msg(val label: String)

    // ---------------------------------------------------------------- valuesOf

    @Test
    fun `a list of messages is returned as is`() {
        val a = Msg("a")
        val b = Msg("b")
        val got = QQMenuSelection.valuesOf(listOf(a, b))
        assertEquals(2, got.size)
        assertTrue("the message objects themselves must come back", got[0] === a && got[1] === b)
    }

    @Test
    fun `a set and an array are both accepted`() {
        // The bar's container type is not known for certain: a HashSet and a bare array are both
        // plausible, and refusing either would silently lose a real selection.
        val a = Msg("a")
        val b = Msg("b")
        assertEquals(2, QQMenuSelection.valuesOf(linkedSetOf(a, b)).size)
        assertEquals(2, QQMenuSelection.valuesOf(arrayOf(a, b)).size)
    }

    @Test
    fun `null elements are skipped`() {
        // A null entry is not a message; passing it down the analysis pipeline would be worse than
        // dropping it.
        val a = Msg("a")
        val got = QQMenuSelection.valuesOf(listOf(a, null))
        assertEquals(1, got.size)
        assertTrue(got[0] === a)
    }

    @Test
    fun `null, empty and unrecognisable sources yield an empty list`() {
        assertTrue(QQMenuSelection.valuesOf(null).isEmpty())
        assertTrue(QQMenuSelection.valuesOf(emptyList<Any>()).isEmpty())
        // A String is not a collection; this must not throw inside a hook.
        assertTrue(QQMenuSelection.valuesOf("not a collection").isEmpty())
        assertTrue(QQMenuSelection.valuesOf(42).isEmpty())
    }

    @Test
    fun `the SAME object added twice collapses to one`() {
        // A double tap on the checkbox must not analyse a message twice.
        val a = Msg("a")
        assertEquals(1, QQMenuSelection.valuesOf(listOf(a, a)).size)
    }

    @Test
    fun `two identical but distinct messages BOTH survive`() {
        // Dedup is by IDENTITY, not by equality. Two genuinely identical messages really are two
        // messages, and dropping one would silently lose a line of the user's chat.
        assertEquals(
            "distinct messages must not be merged",
            2,
            QQMenuSelection.valuesOf(listOf(Msg("same"), Msg("same"))).size,
        )
    }

    // ---------------------------------------------------------------- isBatch

    @Test
    fun `batch is taken only for MORE than one message`() {
        // QQ's own 多选 entry seeds the selection with the long-pressed message, so a single selection
        // must stay on the single-message path -- otherwise a plain press behaves like a mode change.
        assertFalse(QQMenuSelection.isBatch(null))
        assertFalse(QQMenuSelection.isBatch(emptyList()))
        assertFalse("one message is NOT a batch", QQMenuSelection.isBatch(listOf(Msg("a"))))
        assertTrue(QQMenuSelection.isBatch(listOf(Msg("a"), Msg("b"))))
    }

    // ---------------------------------------------------------------- looksLikeMessageStore

    @Test
    fun `a list of host views is rejected as a message store`() {
        // THE guard for the bar's own fields. `MultiSelectBarVB` exposes mSelectText:TextView,
        // mDeleteBtn:View, mMultiButtonBar:LinearLayout, ... and reading one of those as "messages"
        // would feed the analysis a batch of widgets.
        //
        // `android.widget.TextView` here is the TEST FIXTURE in app/src/test/java/android/widget, whose
        // class name really does begin with `android.widget.` -- a fixture in this test's own package
        // would be accepted by the rule and would prove nothing.
        val views = listOf(android.widget.TextView("mSelectText"), android.widget.TextView("mDeleteBtn"))
        assertFalse(
            "a row of widgets must never be read as a selection",
            QQMenuSelection.looksLikeMessageStore(views),
        )
    }

    @Test
    fun `an empty or null candidate is not a message store`() {
        assertFalse(QQMenuSelection.looksLikeMessageStore(null))
        assertFalse(QQMenuSelection.looksLikeMessageStore(emptyList<Any>()))
        assertFalse(QQMenuSelection.looksLikeMessageStore("nope"))
    }

    @Test
    fun `a list of real message-like objects is accepted`() {
        assertTrue(QQMenuSelection.looksLikeMessageStore(listOf(Msg("a"))))
    }

    // ---------------------------------------------------------------- countOf

    @Test
    fun `the selected count is read from the recorded field`() {
        val state = UpdateSelectCount(3)
        assertEquals(3, QQMenuSelection.countOf(state))
    }

    @Test
    fun `a state object that is not the count state yields null`() {
        // `MultiSelectUIState` has six subclasses; only `UpdateSelectCount` carries a count. Returning a
        // made-up number for the others would make the cross-check log lie.
        assertNull(QQMenuSelection.countOf(null))
        assertNull("no int field means no count", QQMenuSelection.countOf(Msg("a")))
    }

    /**
     * Mirrors `MultiSelectUIState$UpdateSelectCount`, whose single int field `d` holds the count.
     *
     * `unused` exists so the CLASS has two int fields when set -- proving the reader does not blindly
     * pick "the only int" when the recorded name is present.
     */
    private class UpdateSelectCount(val d: Int, @Suppress("unused") val other: Int = 0)

    // ---------------------------------------------------------------- selectedOnly

    /**
     * Mirrors the host's `AIOMsgItem#K2()Z`: a per-message flag, NOT a property of the list.
     *
     * This is what the production predicate is built from, and the bug it fixes is that the host's
     * `MsgListVMWrapper#N()` returns the whole conversation. `flag` stands in for `K2()`'s answer.
     */
    private class SelectableMsg(val label: String, val flag: Boolean)

    @Test
    fun `the host list is narrowed to the selected entries`() {
        // THE BUG, in one assertion: 7 loaded messages, 1 ticked. The old code returned all 7 and the
        // single long-press became a batch analysis of the whole visible conversation.
        val list = listOf(
            SelectableMsg("a", false),
            SelectableMsg("b", true),
            SelectableMsg("c", false),
            SelectableMsg("d", false),
            SelectableMsg("e", false),
            SelectableMsg("f", false),
            SelectableMsg("g", false),
        )
        val got = QQMenuSelection.selectedOnly(list) { (it as SelectableMsg).flag }
        assertEquals("exactly the ticked message must survive", 1, got.size)
        assertEquals("b", (got[0] as SelectableMsg).label)
    }

    @Test
    fun `a plain long-press selects nothing, so the single path is taken`() {
        // The normal case for the 分析 row: the user is NOT in multi-select, so no message is ticked.
        // An empty result is what routes the tap to the single-message path (分析范围).
        val loaded = (1..7).map { SelectableMsg("m$it", false) }
        val selected = QQMenuSelection.selectedOnly(loaded) { (it as SelectableMsg).flag }
        assertTrue("nothing is ticked", selected.isEmpty())
        assertFalse(
            "an empty selection must NOT take the batch path",
            QQMenuSelection.isBatch(selected),
        )
    }

    @Test
    fun `a genuine multi-select still takes the batch path`() {
        // The other direction: the fix must not break real batch analysis. Three ticked messages must
        // still reach the batch path, otherwise the "correction" would have removed a working feature.
        val list = listOf(
            SelectableMsg("a", true),
            SelectableMsg("b", false),
            SelectableMsg("c", true),
            SelectableMsg("d", true),
        )
        val selected = QQMenuSelection.selectedOnly(list) { (it as SelectableMsg).flag }
        assertEquals(3, selected.size)
        assertTrue(QQMenuSelection.isBatch(selected))
    }

    @Test
    fun `exactly one ticked message stays on the single path`() {
        // `isBatch` is `> 1`: one ticked message IS the message the user pressed, so it must show the
        // single-message dialog (分析范围), not the read-only batch dialog (分析已选消息).
        val one = QQMenuSelection.selectedOnly(listOf(SelectableMsg("a", true))) {
            (it as SelectableMsg).flag
        }
        assertEquals(1, one.size)
        assertFalse("one selection is not a batch", QQMenuSelection.isBatch(one))
    }

    @Test
    fun `a throwing predicate excludes the item instead of aborting or promoting it`() {
        // The predicate is a REFLECTIVE call into the host, so one element can fail on a subclass that
        // lacks the method. Neither acceptable outcome is "throw out of the whole batch" nor "treat the
        // failure as selected" -- the latter would push an unchosen message into an AI request.
        val list = listOf(
            SelectableMsg("boom", true),
            SelectableMsg("ok", true),
            SelectableMsg("no", false),
        )
        val got = QQMenuSelection.selectedOnly(list) { item ->
            if ((item as SelectableMsg).label == "boom") error("host exploded") else item.flag
        }
        assertEquals("the good selections must survive the bad element", 1, got.size)
        assertEquals("ok", (got[0] as SelectableMsg).label)
    }

    @Test
    fun `an empty or null list yields no selection`() {
        // The capture may not have happened yet (the host has not called its accessor), which must read
        // as "no selection" rather than crashing on a null.
        assertTrue(QQMenuSelection.selectedOnly(null) { true }.isEmpty())
        assertTrue(QQMenuSelection.selectedOnly(emptyList()) { true }.isEmpty())
    }

    @Test
    fun `the predicate is only asked about entries that are present`() {
        // Guards against a reader that consults the predicate for an index past the end: the counter
        // must equal the list size, not exceed it.
        var calls = 0
        val list = (1..5).map { SelectableMsg("m$it", it % 2 == 0) }
        QQMenuSelection.selectedOnly(list) { calls++; (it as SelectableMsg).flag }
        assertEquals(5, calls)
    }

    // ---------------------------------------------------------------- logging

    @Test
    fun `the log description is bounded`() {
        // A 200-message selection must not write a multi-kilobyte line into a log read on a phone.
        val many = (1..200).map { Msg("m$it") }
        val line = QQMenuSelection.describeForLog(many, limit = 6)
        assertTrue("must mention the total", line.contains("200 total"))
        assertTrue("must be short", line.length < 140)
        assertEquals("<empty>", QQMenuSelection.describeForLog(emptyList()))
        assertEquals("<empty>", QQMenuSelection.describeForLog(null))
    }
}
