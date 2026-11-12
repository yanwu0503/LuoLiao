package com.luoliaoo.wechat

import com.luoliaoo.wechat.hook.KuaishouToolbar
import com.luoliaoo.wechat.hook.KuaishouToolbar.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the multi-select BOTTOM-BAR button's decisions.
 *
 * ============================== WHY THE TARGET MOVED (three wasted builds) ====================
 *
 * Kuaishou has TWO bars in multi-select and they are different components:
 *
 *   TOP    `sqj.c` -- 返回 / 完成.        <-- what we hooked first; the user never sees our button here
 *   BOTTOM `ukj.b` -- 逐条转发 / 合并转发 / 删除 / 举报.  <-- what the user actually operates
 *
 * The device log from the top-bar attempt read "the container has 3 child(ren) but NO TextView to
 * clone". Three children and no TextView is EXACTLY the top bar's shape -- so that message was not
 * evidence about the host, it was evidence we were decorating the wrong bar.
 *
 * ============================== WHY THERE IS A RANK, NOT A BOOLEAN ===========================
 *
 * After the bar was fixed, the device showed the button but WRONG: no label, and positioned at the
 * top-right of the third button rather than among the host's own buttons. The log line explained it:
 *
 *     "added our '分析' button to container ConstraintLayout
 *      (children now 6, clone of android.view.View, id=2131320737)"
 *
 * `2131320737` = `0x7f095fa1` = `id/forward_bottom_divider_line` -- we cloned the params of a 1px
 * DIVIDER, so the button inherited a line's height and the line's constraint position. "First
 * cloneable child" was therefore too crude: the bar's first child is exactly that divider. The
 * decision now takes a per-child RANK and picks the best one, so a real button wins over decoration.
 *
 * ============================== WHAT IS ACTUALLY AT RISK =====================================
 *
 * The Android half (`addView`) cannot run off-device. The three decisions that CAN go wrong do not
 * need Android, and each has a user-visible consequence:
 *
 *   1. RE-ENTRY. `N1()` runs on entry to multi-select and again on configuration change. Without a tag
 *      check the button STACKS. The device log showed `buttonAdds=4`, i.e. it was added repeatedly.
 *   2. CLONE SOURCE. The bar's direct children are a divider `View` plus four `LinearLayout`s, and it
 *      has ZERO direct `TextView` children. A "find a direct TextView" rule refuses on the CORRECT
 *      container; a "find any child" rule clones the DIVIDER. Both are wrong, in different ways.
 *   3. TIMING vs SHAPE. An EMPTY container is normal before the host populates it, so it must be
 *      RETRIED; a populated container with nothing cloneable is a real mismatch and must NOT be.
 *
 * ============================== WHAT IT CANNOT PROVE =========================================
 *
 * It proves the DECISION, not how the button looks. Appearance was settled on the device, and the
 * counters `buttonAdds=` / `buttonAddsSkipped=` remain the way to tell "never wired" from "refused"
 * from "added" in the field.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouToolbarTest {

    private val ourTag = "luoliaoo.analysis.kuaishou.toolbar"

    /** The bar's real shape: a divider `View` first, then the four host buttons (`LinearLayout`s). */
    private val realBar = listOf(
        KuaishouToolbar.RANK_ANY,      // id/forward_bottom_divider_line  <-- the trap
        KuaishouToolbar.RANK_BUTTON,   // forward_one_by_one
        KuaishouToolbar.RANK_BUTTON,   // forward_together
        KuaishouToolbar.RANK_BUTTON,   // delete_msg
        KuaishouToolbar.RANK_BUTTON,   // report
    )

    @Test
    fun `the divider is NOT chosen even though it is the first child`() {
        // ★ THE DEVICE BUG, pinning the regression. The divider is rank ANY and comes first; the
        // buttons are rank BUTTON and come later. The best rank must win, not the earliest index --
        // cloning the divider produced a label-less button at a line's constraint position.
        val d = KuaishouToolbar.decide(
            childTags = List(5) { null },
            rank = realBar,
            ourTag = ourTag,
        )
        assertEquals(Reason.ADD, d.reason)
        assertEquals("must clone a real BUTTON, not the leading divider", 1, d.siblingIndex)
        assertEquals(KuaishouToolbar.RANK_BUTTON, d.siblingRank)
    }

    @Test
    fun `a divider-only container still gets a button rather than refusing`() {
        // A decorative child is a poor clone source but not a structural failure: better to add a
        // possibly-plain button than to refuse and leave the user with nothing.
        val d = KuaishouToolbar.decide(
            childTags = listOf(null, null),
            rank = listOf(KuaishouToolbar.RANK_ANY, KuaishouToolbar.RANK_ANY),
            ourTag = ourTag,
        )
        assertEquals(Reason.ADD, d.reason)
        assertEquals(0, d.siblingIndex)
        assertEquals(KuaishouToolbar.RANK_ANY, d.siblingRank)
    }

    @Test
    fun `an already-present button is NOT added a second time`() {
        // The duplicate-stacking guard: this is what makes repeated `N1()` calls harmless. The device
        // log showed buttonAdds=4 without it mattering visually, which is exactly the risk.
        val d = KuaishouToolbar.decide(
            childTags = listOf(null, ourTag, null),
            rank = listOf(KuaishouToolbar.RANK_ANY, KuaishouToolbar.RANK_BUTTON,
                KuaishouToolbar.RANK_BUTTON),
            ourTag = ourTag,
        )
        assertEquals(Reason.SKIP_ALREADY_PRESENT, d.reason)
        assertFalse("adding here would duplicate the button", d.add)
    }

    @Test
    fun `the tag check wins over the clone-source search`() {
        // Ordering matters: if the rank search ran first, a re-wire would find a good sibling and add a
        // SECOND button even though ours is already there.
        val d = KuaishouToolbar.decide(
            childTags = listOf(ourTag),
            rank = listOf(KuaishouToolbar.RANK_BUTTON),
            ourTag = ourTag,
        )
        assertEquals(Reason.SKIP_ALREADY_PRESENT, d.reason)
    }

    @Test
    fun `an EMPTY container is retryable, not a refusal`() {
        // ★ TIMING vs SHAPE. The host inflates this bar lazily, so empty-on-first-look is expected.
        // Reporting it as a failure is what sent the earlier investigation after a host-layout change.
        val d = KuaishouToolbar.decide(
            childTags = emptyList(),
            rank = emptyList(),
            ourTag = ourTag,
        )
        assertEquals(Reason.DEFER_NOT_YET_INFLATED, d.reason)
        assertTrue("an empty container must be retried, not given up on", d.retryable)
        assertFalse(d.add)
    }

    @Test
    fun `a populated container with nothing usable is NOT retryable`() {
        // Children exist but none can donate params. Retrying this forever would just spam the log; it
        // is a genuine mismatch with the layout we verified.
        val d = KuaishouToolbar.decide(
            childTags = listOf(null, null),
            rank = listOf(KuaishouToolbar.RANK_UNUSABLE, KuaishouToolbar.RANK_UNUSABLE),
            ourTag = ourTag,
        )
        assertEquals(Reason.REFUSE_NO_SIBLING, d.reason)
        assertFalse("a structural refusal must not be retried", d.retryable)
        assertFalse(d.add)
        assertTrue(
            "the reason must explain the refusal: " + KuaishouToolbar.explain(d),
            KuaishouToolbar.explain(d).contains("measure-time crash"),
        )
    }

    @Test
    fun `only an empty container is retryable, across every reason`() {
        // Guards the retry POLICY as a whole: if a future reason were marked retryable by accident, the
        // caller would loop on it. Written as a sweep so a new Reason cannot slip in unnoticed.
        for (r in Reason.values()) {
            val d = KuaishouToolbar.Decision(r, 0, 1)
            assertEquals(
                "only DEFER_NOT_YET_INFLATED may be retried, but " + r + " disagreed",
                r == Reason.DEFER_NOT_YET_INFLATED,
                d.retryable,
            )
        }
    }

    @Test
    fun `the best rank wins, and ties go to the earliest child`() {
        // Determinism: the same container must yield the same clone source, or the log line would not
        // be reproducible between runs.
        val d = KuaishouToolbar.decide(
            childTags = List(4) { null },
            rank = listOf(
                KuaishouToolbar.RANK_BUTTON,
                KuaishouToolbar.RANK_ANY,
                KuaishouToolbar.RANK_BUTTON,
                KuaishouToolbar.RANK_ANY,
            ),
            ourTag = ourTag,
        )
        assertEquals(Reason.ADD, d.reason)
        assertEquals("earliest of the joint-best must win", 0, d.siblingIndex)
    }

    @Test
    fun `every reason has a non-empty explanation`() {
        // `explain` uses an exhaustive `when` with no `else`, so this also guards that no reason was
        // added without the log being able to describe it.
        for (r in Reason.values()) {
            val text = KuaishouToolbar.explain(KuaishouToolbar.Decision(r, 0, 1))
            assertTrue("reason " + r + " must be explainable", text.isNotEmpty())
        }
    }

    @Test
    fun `a positional list shorter than the tags does not crash the search`() {
        // Defensive: both lists are built in one loop on the Android side, so a mismatch means a bug
        // there. It must degrade to "nothing usable" rather than throw inside a hook.
        val d = KuaishouToolbar.decide(
            childTags = listOf(null, null, null),
            rank = listOf(KuaishouToolbar.RANK_ANY),
            ourTag = ourTag,
        )
        assertEquals("a divider-only prefix is still usable", Reason.ADD, d.reason)
        val d2 = KuaishouToolbar.decide(
            childTags = listOf(null, null, null),
            rank = listOf(KuaishouToolbar.RANK_UNUSABLE),
            ourTag = ourTag,
        )
        assertEquals(Reason.REFUSE_NO_SIBLING, d2.reason)
        assertFalse(d2.add)
    }
}
