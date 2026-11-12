package com.luoliaoo.wechat.hook

/**
 * Pure, unit-testable rules for putting our 「分析」 entry on Kuaishou's multi-select
 * **bottom action bar** (the row holding 逐条转发 / 合并转发 / 删除 / 举报).
 *
 * ============================== WHICH BAR, AND WHY THAT COST US THREE BUILDS ==============
 * Kuaishou has TWO bars during multi-select, and they are different components:
 *
 *   TOP    `sqj.c` (`ChatMultiChooseComponent`) -- 返回 / 完成 + two `ViewStub`s.
 *          Its `N1()` resolves `navi_multi_choose_finish` / `_back`. This is what we hooked first,
 *          and it is NOT the bar the user operates.
 *   BOTTOM `ukj.b` -- 逐条转发 / 合并转发 / 删除 / 举报. Container id
 *          `0x7f095fa5 id/forward_msg_bottom_part`, reached through field `v` (inflated from the
 *          `ViewStub` in field `w`, id `0x7f095fa6`). **This is the bar the user sees.**
 *
 * The device log from the first attempt said, verbatim:
 *     "the container has 3 child(ren) but NO TextView to clone ... NO button was added"
 * Three children and no TextView is exactly the TOP bar. So that message was not evidence of a
 * host-layout problem -- it was evidence that WE HAD THE WRONG CONTAINER. A refusal that reads like
 * "the host changed its layout" is dangerous: it sends the next person looking in the wrong place,
 * which is why [Reason] now separates "wrong time" from "wrong shape".
 *
 * ============================== WHY THE CLONE SOURCE IS NOT A TextView ANY MORE ===========
 * The bottom bar's layout (`r/as/eak.xml`) is:
 *
 *     ConstraintLayout  id/forward_msg_bottom_part        <-- container
 *       View            id/forward_bottom_divider_line
 *       LinearLayout    id/forward_one_by_one   (icon + TextView)
 *       LinearLayout    id/forward_together     (icon + TextView)
 *       LinearLayout    id/delete_msg           (icon + TextView)
 *       LinearLayout    id/report               (icon + TextView)
 *
 * The container's DIRECT children are four `LinearLayout`s. It has **zero direct `TextView`
 * children** -- the TextViews are grandchildren. So the previous "find a direct TextView sibling"
 * rule would still have refused even after we pointed it at the right bar. The clone source is
 * therefore "the first direct child whose params we can copy", preferring a container-like child,
 * since that is the shape the host's own layout code already accepted for its own buttons.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
object KuaishouToolbar {

    /** Why a button was or was not added, so the log line can name the outcome rather than imply it. */
    enum class Reason {
        /** No child carries our tag yet, and a cloneable sibling exists. */
        ADD,

        /** Our button is already a child of this container: adding again would duplicate it. */
        SKIP_ALREADY_PRESENT,

        /**
         * The container exists but is EMPTY. Distinct from [REFUSE_NO_SIBLING] because an empty
         * container is normal *before* the host populates it, so this is a timing signal, not a
         * structural defect.
         */
        DEFER_NOT_YET_INFLATED,

        /**
         * The container has children, but none can supply a `LayoutParams` type known to be accepted.
         *
         * A STRUCTURAL refusal: the host's layout is not the shape we verified. Refuse rather than
         * guess, because inventing params produced an uncatchable measure-time FATAL on Douyin.
         */
        REFUSE_NO_SIBLING,
    }

    /** The result of the decision, with the evidence the log line needs. */
    class Decision(val reason: Reason, val siblingIndex: Int, val childCount: Int, val siblingRank: Int = 0) {
        val add: Boolean get() = reason == Reason.ADD

        /**
         * True when the failure is TIMING (the host has not populated/inflated yet) rather than SHAPE.
         *
         * The caller retries on this and gives up on the others. Conflating the two is what made the
         * first device report ("3 children, no TextView") read as a host-layout change when it was
         * really "we asked the wrong bar", so the distinction is part of the public contract now.
         */
        val retryable: Boolean get() = reason == Reason.DEFER_NOT_YET_INFLATED
    }

    /** A child that looks like one of the host's own BUTTONS (a container with content inside). */
    const val RANK_BUTTON = 2

    /** A child we could technically clone params from, but which may be decorative (e.g. a divider). */
    const val RANK_ANY = 1

    /** Not usable as a clone source (no LayoutParams, so there is no type to copy). */
    const val RANK_UNUSABLE = 0

    /**
     * Decides whether to add our button to a toolbar container.
     *
     * @param childTags  the `View.getTag()` of every child, in order (null for an untagged child)
     * @param rank       how good each child is as a CLONE SOURCE, positionally matching [childTags]:
     *                   higher is better, 0 means "cannot be cloned". The Android side assigns
     *                   [RANK_BUTTON] / [RANK_ANY] / [RANK_UNUSABLE].
     * @param ourTag     the tag marking our own button
     *
     * Positional lists rather than Android types, so this stays pure and testable off-device.
     *
     * ============================== WHY A RANK, NOT A BOOLEAN ==============================
     * A boolean "is cloneable" was not enough, and the device proved it expensively. The bottom bar's
     * FIRST child is a 1px divider `View`, so cloning "the first cloneable child" copied the DIVIDER's
     * `LayoutParams`. On the device our button then rendered with no room for its label and sat at the
     * divider's constraint position (the top-right of the third button) instead of beside the host's
     * own buttons -- which reads like "the hook failed" even though it had worked.
     *
     * So the caller ranks candidates by how button-like they are, and this picks the BEST one rather
     * than the first acceptable one.
     */
    fun decide(childTags: List<Any?>, rank: List<Int>, ourTag: Any): Decision {
        val n = childTags.size
        // A tag match wins even when a sibling exists: the idempotence check must come FIRST, or a
        // re-wire would add a second button next to the one already there.
        for (t in childTags) {
            if (t != null && t == ourTag) {
                return Decision(Reason.SKIP_ALREADY_PRESENT, -1, n)
            }
        }
        if (n == 0) {
            // Empty is a TIMING state, not a defect: the host inflates this bar lazily, so an empty
            // container on the first attempt is expected and must be retried, not reported as breakage.
            return Decision(Reason.DEFER_NOT_YET_INFLATED, -1, 0)
        }
        // The BEST-ranked child, earliest on a tie -- so the result is deterministic for a given
        // container, which keeps the log line reproducible across runs.
        var bestIndex = -1
        var bestRank = RANK_UNUSABLE
        for (i in 0 until minOf(n, rank.size)) {
            if (rank[i] > bestRank) {
                bestRank = rank[i]
                bestIndex = i
            }
        }
        if (bestIndex < 0) {
            return Decision(Reason.REFUSE_NO_SIBLING, -1, n)
        }
        return Decision(Reason.ADD, bestIndex, n, bestRank)
    }

    /**
     * A one-line explanation for the log, keyed off the reason.
     *
     * Kept beside the decision so a new reason cannot be added without the log noticing: the `when` is
     * exhaustive with no `else`, so a missing branch fails to compile.
     */
    fun explain(d: Decision): String = when (d.reason) {
        Reason.ADD ->
            "adding our button (container children=" + d.childCount +
                ", cloning sibling #" + d.siblingIndex + " which the host itself placed here)"
        Reason.SKIP_ALREADY_PRESENT ->
            "our button is ALREADY a child of this container, so it was not added again " +
                "(children=" + d.childCount + "). This is the normal case on re-entry to multi-select."
        Reason.DEFER_NOT_YET_INFLATED ->
            "the container is EMPTY (children=0). This is a TIMING state -- the host inflates this bar " +
                "lazily -- so the caller retries with a delay. NOT a host-layout change."
        Reason.REFUSE_NO_SIBLING ->
            "the container has " + d.childCount + " child(ren) but NONE can supply a LayoutParams type " +
                "known to be accepted, so this is a STRUCTURAL mismatch with the layout we verified. " +
                "NO button was added: inventing params is what produced an uncatchable measure-time " +
                "crash on Douyin."
    }
}
