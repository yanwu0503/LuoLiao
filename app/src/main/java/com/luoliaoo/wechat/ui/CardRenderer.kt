package com.luoliaoo.wechat.ui

import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.luoliaoo.wechat.core.Log

/**
 * Places and maintains the analysis card directly below a chat message.
 *
 * PLACEMENT: the bubble is moved into a NEW vertical container together with the card:
 *
 *     before:  P = [ ..., bubble, ... ]          (P may be horizontal)
 *     after:   P = [ ..., V, ... ]               V = [ bubble, card ]   (V vertical)
 *
 * Orientation-independent, so the card can never become a stretched extra column, and it is
 * always directly below the message.
 *
 * RECYCLING SAFETY: each card records `ownerKey` (talker#msgId). When a row is reused for a
 * different message, the key no longer matches, the stale card is removed, and (if a state
 * exists for the new message) a fresh card is placed. See the file header of the generator
 * note in AnalysisController for the incident this prevents.
 */
object CardRenderer {

    private const val TAG = "LuoLiao"
    private const val TAG_WRAPPER = "luoliao_card_wrapper"

    /** Rough width of one Chinese character at the card's text size, in dp. */
    private const val CHAR_DP = 15f

    /** Finds the item View for `position` inside `rv`. */
    fun itemViewAt(rv: Any, position: Int): ViewGroup? {
        return try {
            val lm = rv.javaClass.methods.firstOrNull {
                it.name == "getLayoutManager" && it.parameterCount == 0
            }?.invoke(rv) ?: return null
            val find = lm.javaClass.methods.firstOrNull {
                it.name == "findViewByPosition" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return null
            (find.invoke(lm, position) as? ViewGroup)
        } catch (t: Throwable) {
            Log.w(TAG, "itemViewAt failed: " + t.message)
            null
        }
    }

    /**
     * Every currently attached item view of the RecyclerView.
     *
     * A read-only helper used by the controller's reconcile passes to find rows by message id. It
     * does not participate in placement, so the restored v1.0.25 layout is unaffected.
     */
    fun attachedRows(rv: Any): List<ViewGroup> {
        val out = ArrayList<ViewGroup>(16)
        try {
            val countM = rv.javaClass.methods.firstOrNull {
                it.name == "getChildCount" && it.parameterCount == 0
            } ?: return out
            val atM = rv.javaClass.methods.firstOrNull {
                it.name == "getChildAt" && it.parameterCount == 1
            } ?: return out
            val n = (countM.invoke(rv) as? Int) ?: return out
            for (i in 0 until n) {
                val v = atM.invoke(rv, i)
                if (v is ViewGroup) out.add(v)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attachedRows failed: " + t.message)
        }
        return out
    }

    fun recyclerViewOf(v: View?): Any? {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 25) {
            if (isRecyclerView(cur)) return cur
            val p = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return null
    }

    private fun isRecyclerView(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var d = 0
        while (c != null && d < 8) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
            d++
        }
        return false
    }

    /**
     * Removes every card whose ownerKey is not `expectedKey`.
     *
     * Called when a row is bound or recycled: a row showing message X must never contain a
     * card belonging to message Y (that is how a card leaked into another conversation).
     *
     * @return the surviving card for `expectedKey`, or null when there is none.
     */
    /**
     * Removes cards that provably belong to ANOTHER message; returns the survivor for `expectedKey`.
     *
     * CRITICAL NULL RULE. A null `expectedKey` means "this row's message could not be determined",
     * which is NOT a mismatch. The v1.0.25 version treated it as one and removed every card, which
     * is exactly why cards vanished on each reconcile pass. Here nothing is removed in that case.
     *
     * A card with a null ownerKey was most likely attached by us moments ago and not yet stamped,
     * so it is adopted rather than deleted. Only a card with a KNOWN, DIFFERENT key is removed.
     */
    fun enforceOwner(itemView: ViewGroup, expectedKey: String?): AnalysisCard? {
        if (expectedKey == null) return AnalysisCard.findIn(itemView)
        val all = ArrayList<AnalysisCard>(2)
        AnalysisCard.findAllIn(itemView, all)
        var keep: AnalysisCard? = null
        for (c in all) {
            if ((c.ownerKey == expectedKey || c.ownerKey == null) && keep == null) {
                keep = c
            } else {
                (c.parent as? ViewGroup)?.removeView(c)
                Log.i(TAG, "removed stale card owner=" + c.ownerKey +
                    " expected=" + expectedKey)
            }
        }
        return keep
    }

    /** Removes any card inside this row. */
    fun clear(itemView: ViewGroup) {
        try {
            AnalysisCard.removeFrom(itemView)
            unwrap(itemView)
        } catch (_: Throwable) {
            // best effort
        }
    }

    /**
     * Renders the card for `key` below the bubble in `itemView`.
     *
     * PLACEMENT (the v1.0.25 behaviour, restored): the bubble is moved into a NEW vertical
     * container that takes its former slot, and the card is appended inside it, so the card is
     * always directly below the message and never overlaps another view.
     *
     * @param widthChars card width in characters; <=0 uses the default
     * @param turns      the follow-up conversation shown inside the card
     * @param onFollowUp submits a follow-up question
     * @param onDelete   removes this card
     * @param onEdit     puts a suggested reply into WeChat's input box
     */
    fun render(
        itemView: ViewGroup,
        anchor: View?,
        state: Any,
        key: String,
        widthChars: Int,
        turns: List<com.luoliaoo.wechat.data.ChatTurn>,
        onReanalyze: () -> Unit,
        onFollowUp: (String) -> Unit,
        onDelete: () -> Unit,
        onEdit: (String) -> Unit,
    ): Boolean {
        return try {
            // A row may have been reused for a different message.
            var card = enforceOwner(itemView, key)

            if (card == null) {
                val bubble = bubbleOf(itemView, anchor) ?: run {
                    dumpTree(itemView, "no bubble found")
                    return false
                }
                card = attachBelow(itemView, bubble) ?: run {
                    dumpTree(itemView, "attachBelow failed")
                    return false
                }
                card.ownerKey = key
                Log.i(TAG, "card attached for key=" + key + " below " +
                    bubble.javaClass.simpleName)
            }

            card.render(state, turns, onReanalyze, onFollowUp, onDelete, onEdit)
            applyWidth(card, widthChars)
            Log.i(TAG, "card rendered for key=" + key + " w=" + card.width)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "card render failed", t)
            false
        }
    }

    // ------------------------------------------------------------------ attach

    private fun attachBelow(itemView: ViewGroup, bubble: View): AnalysisCard? {
        val parent = bubble.parent as? ViewGroup ?: return null
        val ctx = itemView.context

        // Already wrapped by us: reuse that column.
        if (parent.tag == TAG_WRAPPER) {
            val existing = parent.getChildAt(parent.childCount - 1)
            if (existing is AnalysisCard) return existing
            val c = AnalysisCard(ctx)
            parent.addView(c)
            return c
        }

        val bubbleIndex = parent.indexOfChild(bubble)
        val bubbleLp = bubble.layoutParams
        val align = if (bubbleLp is LinearLayout.LayoutParams) bubbleLp.gravity else -1

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_WRAPPER
            layoutParams = bubbleLp
        }

        parent.removeView(bubble)
        bubble.layoutParams = LinearLayout.LayoutParams(
            bubbleLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            bubbleLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        wrapper.addView(bubble)

        val card = AnalysisCard(ctx)
        wrapper.addView(card)

        parent.addView(wrapper, bubbleIndex.coerceIn(0, parent.childCount))

        if (align == Gravity.RIGHT || align == Gravity.END) {
            (bubble.layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.END
            (card.layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.END
        } else if (align == Gravity.CENTER_HORIZONTAL) {
            (bubble.layoutParams as? LinearLayout.LayoutParams)?.gravity =
                Gravity.CENTER_HORIZONTAL
        }

        parent.requestLayout()
        Log.i(TAG, "card attached: wrapper in " + parent.javaClass.simpleName +
            " bubbleIndex=" + bubbleIndex + " parentOrientation=" + orientationName(parent))
        return card
    }

    /** Undoes our wrapper so a cleared row keeps working. */
    private fun unwrap(itemView: ViewGroup) {
        val wrappers = ArrayList<LinearLayout>(1)
        collectWrappers(itemView, wrappers)
        for (w in wrappers) {
            if (w.childCount == 0) continue
            val parent = w.parent as? ViewGroup ?: continue
            val bubble = w.getChildAt(0)
            val lp = w.layoutParams
            w.removeView(bubble)
            parent.removeView(w)
            bubble.layoutParams = lp
            parent.addView(bubble, parent.indexOfChild(itemView).coerceAtLeast(0))
            parent.requestLayout()
        }
    }

    private fun collectWrappers(v: View, out: MutableList<LinearLayout>) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c is LinearLayout && c.tag == TAG_WRAPPER) {
                    out.add(c)
                } else {
                    collectWrappers(c, out)
                }
            }
        }
    }

    // -------------------------------------------------------------------- width

    /**
     * Sets the card width from a character count (item 3).
     *
     * The character is measured with the card's own Paint, so the result matches the actual
     * font: width = ceil(chars * oneCharWidth) + padding, clamped to the available width.
     */
    private fun applyWidth(card: AnalysisCard, widthChars: Int) {
        try {
            val chars = if (widthChars < 1) 10 else widthChars
            val dm = card.resources.displayMetrics
            val paint = Paint()
            paint.textSize = 13f * dm.scaledDensity
            // Measure a wide CJK glyph for a stable per-character width.
            val one = paint.measureText("汉")
            val padH = (24 * dm.density).toInt()
            val wanted = (chars * one).toInt() + padH
            val screen = dm.widthPixels
            val max = (screen * 0.78f).toInt()
            val target = wanted.coerceIn(padH + 1, max.coerceAtLeast(padH + 1))

            val lp = (card.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(target, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (lp.width != target) {
                lp.width = target
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                card.layoutParams = lp
                card.requestLayout()
                Log.i(TAG, "card width=" + target + "px for " + chars +
                    " chars (char=" + one + "px screen=" + screen + ")")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyWidth failed: " + t.message)
        }
    }

    // ------------------------------------------------------------- bubble lookup

    /**
     * The view the card should be placed under.
     *
     * IMPORTANT: this must NEVER return null for a row that has content. The previous version only
     * accepted a backgrounded child ViewGroup ("the bubble"), but WeChat rows frequently have no
     * such container -- a real logged example was a plain horizontal LinearLayout holding
     * ProgressBar + AnimImageView + MMNeat7extView. bubbleOf() returned null there, render() bailed
     * out, and NO CARD WAS EVER DRAWN for that message shape.
     *
     * Order of preference:
     *   1. an explicitly detected bubble (a backgrounded container), when the row has one
     *   2. the long-pressed anchor, when it belongs to this row (it IS the message text view)
     *   3. the largest TextView in the row that is not inside our card
     *   4. the largest child view of the row
     */
    private fun bubbleOf(itemView: ViewGroup, anchor: View?): View? {
        // 1) the anchor itself is the most reliable "below this" target.
        // The anchor must not be part of our own card, and it must live in this row.
        if (anchor != null && isInside(itemView, anchor) && !isInsideOurCard(anchor)) {
            Log.i(TAG, "bubbleOf: using the anchor " + anchor.javaClass.simpleName)
            return anchor
        }

        // 2) an explicit bubble, if this row has one.
        var bubble: View? = null
        var bubbleArea = 0
        // 3) / 4) broader fallbacks, tracked in the same pass.
        var bestText: View? = null
        var textArea = 0
        var bestChild: View? = null
        var childArea = 0

        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(itemView to 0)
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (depth > 8) continue
            if (v is AnalysisCard || isInsideOurCard(v)) continue

            if (v !== itemView) {
                val area = v.width * v.height
                if (v is ViewGroup && v.background != null && v.tag != TAG_WRAPPER &&
                    area > bubbleArea
                ) {
                    bubbleArea = area
                    bubble = v
                }
                if (v is TextView && v.text?.isNotEmpty() == true && area > textArea) {
                    textArea = area
                    bestText = v
                }
                if (area > childArea) {
                    childArea = area
                    bestChild = v
                }
            }
            if (v is ViewGroup) {
                for (k in 0 until v.childCount) stack.addLast(v.getChildAt(k) to depth + 1)
            }
        }

        bubble?.let {
            Log.i(TAG, "bubbleOf: using a backgrounded container " + it.javaClass.simpleName)
            return it
        }
        bestText?.let {
            Log.i(TAG, "bubbleOf: no bubble; using the largest text view " + it.javaClass.simpleName)
            return it
        }
        bestChild?.let {
            Log.i(TAG, "bubbleOf: no bubble and no text; using the largest child " +
                it.javaClass.simpleName)
            return it
        }

        // A row with no children at all: last resort, the row itself.
        Log.w(TAG, "bubbleOf: empty row; falling back to the row container")
        return itemView
    }

    /** True when `v` is, or lives inside, one of our cards. */
    private fun isInsideOurCard(v: View): Boolean {
        if (v is AnalysisCard) return true
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur is AnalysisCard) return true
            val p = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return false
    }

    private fun isInside(root: ViewGroup, v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === root) return true
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return false
    }

    // -------------------------------------------------------------- diagnostics

    private fun orientation(vg: ViewGroup): Int {
        var c: Class<*>? = vg.javaClass
        var d = 0
        var isLinear = false
        while (c != null && d < 8) {
            if (c.name == "android.widget.LinearLayout") {
                isLinear = true
                break
            }
            c = c.superclass
            d++
        }
        if (!isLinear) return -1
        return try {
            (vg.javaClass.getMethod("getOrientation").invoke(vg) as? Int)
                ?: LinearLayout.VERTICAL
        } catch (_: Throwable) {
            LinearLayout.VERTICAL
        }
    }

    private fun orientationName(vg: ViewGroup): String = when (orientation(vg)) {
        LinearLayout.HORIZONTAL -> "HORIZONTAL"
        LinearLayout.VERTICAL -> "VERTICAL"
        else -> "n/a"
    }

    private fun dumpTree(itemView: ViewGroup, reason: String) {
        val sb = StringBuilder()
        sb.append("layout dump (").append(reason).append("): row=")
            .append(itemView.javaClass.simpleName)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(itemView to 0)
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (depth > 4) continue
            sb.append("\\n").append("  ".repeat(depth + 1)).append(v.javaClass.simpleName)
            if (v is ViewGroup) {
                val o = orientation(v)
                if (o >= 0) sb.append(if (o == LinearLayout.VERTICAL) "(V)" else "(H)")
                sb.append("[").append(v.childCount).append("]")
            } else if (v is TextView) {
                val t = v.text
                if (t != null) sb.append(" '").append(t.toString().take(10)).append("'")
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to depth + 1)
            }
        }
        Log.i(TAG, sb.toString())
    }
}
