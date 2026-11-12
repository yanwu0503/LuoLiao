package com.luoliaoo.wechat.hook

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Which message is currently bound to which VIEW HOLDER.
 *
 * ============================== WHY THIS EXISTS ==============================
 * QQ's multi-select toolbar must answer "which MESSAGES has the user ticked". The ticks are easy and
 * reliable -- `CheckBox.isChecked`, i.e. exactly what the user sees -- but turning a ticked row into
 * its message object defeated every other route, each measured on the device:
 *
 *   * `AIOMsgItem#K2()Z`         -- TRUE for every message, so one long-press looked like a
 *                                   seven-message selection. Not a selection predicate at all;
 *   * `MsgListVMWrapper#N()`     -- returns the ENTIRE conversation, not the selection;
 *   * adapter `getItem(int)`     -- null for a position that was itself correct
 *                                   (`rv=AIORecycleView pos=3 msg=null`);
 *   * adapter whole-list getter  -- `adapter exposed 0 message(s) for index lookup`.
 *
 * The last two fail because QQ's list delegates to `AutoCancelAsyncListDiffer`, which exposes neither
 * a per-position getter nor a message list. So the pairing is not LOOKED UP at all -- it is RECORDED
 * at the one moment it genuinely exists.
 *
 * That moment is `AbsMsgListVB#y(holder, int, msg, List)` -- the bind body. `QQHistoryHooks` already
 * hooks it for the history feature, and arg 0 / arg 2 of that call are the row holder and its message.
 *
 * ============================== KEYED BY HOLDER, NOT BY VIEW ==============================
 * The first version keyed by `View`, on the assumption that the bind argument is the row's view. The
 * device disproved it: `row registry size=0` -- nothing was ever stored, because the argument's type
 * is QQ's own `holder/a`, NOT an Android `View`. A registry that is always empty looks exactly like a
 * registry that is never consulted, which is why the size is logged.
 *
 * What DOES work is the holder object itself, because the view side has a legitimate way to reach it:
 * `RecyclerView.findContainingViewHolder(view)` -- the very call [QQMsgAccess.positionOf] already uses
 * successfully (`pos=3` on the device). So:
 *
 *     bind time:  holder -> msg           (this object)
 *     tap time:   box -> holder           (RecyclerView.findContainingViewHolder)
 *                 holder -> msg           (this object)
 *
 * Both halves are things Android and QQ actually provide, and neither depends on the adapter's
 * internals.
 *
 * ============================== WEAK, ON PURPOSE ==============================
 * Holders are RECYCLED: the same holder carries different messages over time, so only the latest
 * binding is meaningful and a stale entry would be a wrong answer. Keys are identity-based (a
 * `WeakHashMap` keys on identity, not `equals`) and weak, so a chat left open for hours cannot pin
 * every holder the host has created -- when QQ drops one, its entry goes with it.
 */
object QQRowRegistry {

    private val holders = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /** Records that [msg] is the message currently shown by [holder]. */
    fun put(holder: Any?, msg: Any?) {
        if (holder == null || msg == null) return
        try {
            holders[holder] = msg
        } catch (_: Throwable) {
        }
    }

    /** The message recorded for [holder], or null. */
    fun get(holder: Any?): Any? {
        if (holder == null) return null
        return try {
            holders[holder]
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The message bound to [v] or to the nearest ancestor that has one, when the holder of [v] is not
     * itself known.
     *
     * Kept alongside [get] because some rows publish the holder object on the view's tag; this walks
     * up testing each tag. Bounded, and never throws -- a miss returns null and the caller analyses
     * nothing rather than something wrong.
     */
    fun find(v: View?): Any? {
        var cur = v
        var hops = 0
        while (cur != null && hops < 12) {
            val t = cur.tag
            if (t != null) {
                get(t)?.let { return it }
            }
            cur = cur.parent as? View
            hops++
        }
        return null
    }

    /** How many holders are remembered, for the diagnostic log (0 is the tell-tale of a key mismatch). */
    fun size(): Int = try {
        holders.size
    } catch (_: Throwable) {
        -1
    }
}
