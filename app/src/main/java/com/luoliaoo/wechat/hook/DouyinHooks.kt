package com.luoliaoo.wechat.hook

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Installs the DOUYIN hooks. Sibling of [MenuHook], which does the same job for WeChat.
 *
 * ============================ REVISION HISTORY (read this first) ============================
 * v1 of this file hooked `BasicViewHolder.onLongClick`, believing it was the message long press.
 * THAT WAS WRONG, and the mistake is worth recording because it was made from a grep hit rather
 * than from the instruction stream: `onLongClick` contains the literal
 * `IMLongClickListener-TYPE_ITEM_AVATAR` and calls `GroupMemberManager` to build an `IMMember`, so
 * it is the **avatar** long press (tap a head to see who that is). Hooking it produced a module
 * that fired on avatar taps and never on messages -- exactly "the balloon works but long-pressing a
 * message shows no 分析 button".
 *
 * The real message menu, verified by disassembling the show path:
 *
 *   com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickWindowManagerNew
 *       .LIZIZ(View, X.1C7V, Message, BaseContent, X.1C4w, Function0, X.1BZd)   <- show the menu
 *       .LJII()  -> android.widget.PopupWindow                                  <- the window
 *
 * and the parameter list is the prize: the menu is shown with the **Message object as argument 2**,
 * so the module never has to recover the message from a view tag. (WeChat's path must, which is
 * where its `msgFromTag` / `pressedMsg` / ThreadLocal machinery came from.)
 *
 * ================================ WHY THERE IS NO MENU ROW ================================
 * WeChat's entry is a row injected into the menu's adapter. Douyin's menu items are individual
 * CLASSES (`X.1CC7` plus ~40 obfuscated subclasses) each returning an English key from `LJII()`
 * (`copy` / `recall` / `forward` / ...). Adding a 41st item would mean implementing an obfuscated
 * interface and hoping the list builder accepts a foreign object.
 *
 * So this module takes the ROBUST route instead: after Douyin shows its menu, we WALK THE
 * POPUPWINDOW'S VIEW TREE and append one row of our own -- plain TextView/LinearLayout, no Douyin
 * type involved. That survives Douyin renaming every one of those 40 classes, because none of them
 * is referenced here.
 *
 * Also from the reverse-engineering report, and load-bearing for the design above:
 *   * THE MENU LABELS ARE NOT IN THE DEX. 「复制」/「撤回」/「转发」 live in `resources.arsc`. So the
 *     WeChat approach of matching an item by its Chinese label CANNOT work here -- and our injected
 *     row must therefore be identified by a VIEW TAG, not by its text.
 *   * The message model is NOT obfuscated (`com.bytedance.im.core.model.Message`), so reading the
 *     message needs no name table.
 *
 * Comments ASCII-only; Chinese as \uXXXX (see core/Log.kt).
 */
class DouyinHooks(
    private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    private companion object {
        const val TAG = "LuoLiao"

        /** The menu window manager. Verified: classes29.dex. */
        const val WINDOW_MGR =
            "com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickWindowManagerNew"

        /**
         * The un-obfuscated message model. Verified: classes44.dex.
         * Kept because the menu parameter type is checked with `isAssignableFrom` against it.
         */
        const val MESSAGE = "com.bytedance.im.core.model.Message"

        /**
         * The all-message view holder.
         *
         * Its `getCurrentMsg()` is still useful as a second way to read the pressed message, but it
         * is NOT the menu entry point (see the class comment). Recorded here so the next reader does
         * not repeat v1's mistake.
         */
        const val HOLDER = "com.ss.android.ugc.aweme.im.business.chat.msgcell.BasicViewHolder"

        /**
         * Douyin's message-list adapter -- the feed point for live history. Verified: classes29.dex.
         *
         * Its `onBindViewHolder(ViewHolder, int)` is the analogue of the WeChat adapter method this
         * module already hooks, and the method name is not obfuscated. See
         * [installMessageBindHook] for why the module needs it at all.
         */
        const val BIND_ADAPTER =
            "com.ss.android.ugc.aweme.im.business.chat.msgcell.common.CellComposeAdapter"

        /**
         * Douyin's multi-select ACTION TOOLBAR. Verified: classes6.dex.
         *
         * ---------------------------------------------------------------------------------------------
         * A REVERSE-ENGINEERING CORRECTION, and the first class named here was WRONG.
         *
         * The obvious candidate was `feature.multiSelect.MultiSelectUI`, because that is the class
         * whose name says "multi select". It is not the toolbar: the device log showed its root is a
         * `MultiSelectTipsView` banner (`[HORIZONTAL] children=2`), i.e. the "N selected" strip. The
         * multi-select feature is split across separate RIPS components and only this one owns the
         * buttons.
         *
         * The real toolbar, confirmed by its own telemetry string `"bottom_tool_bar"`:
         *
         *     com.ss.android.ugc.aweme.im.business.chat.feature.multichoosemessage.MultiChooseFuncUI
         *       extends com.ss.android.ugc.aweme.rips.NestedRipsUI
         *       onViewCreated(View)  -- where its 5 buttons are wired
         *
         * TWO CONSEQUENCES that shaped the code below:
         *
         *   1. The buttons are STATIC XML (`r/z-b.xml`), and the class NEVER calls addView. So there
         *      is no "rebuild buttons" method to hook, unlike WeChat's toolbar -- the injection has to
         *      append to the row itself, and `onViewCreated` is the right moment.
         *
         *   2. Its buttons are `width=0dp` + `weight` (equal shares). Appending a WRAP_CONTENT child
         *      would squeeze the existing buttons, and appending `0dp` WITHOUT a weight gives an
         *      invisible zero-width button -- which would look exactly like a failed injection. The
         *      button is therefore CLONED from a real sibling so its layoutParams already carry the
         *      right weight (see [injectMultiSelectButton]).
         * ---------------------------------------------------------------------------------------------
         */
        const val MULTI_SELECT_UI =
            "com.ss.android.ugc.aweme.im.business.chat.feature.multichoosemessage.MultiChooseFuncUI"

        /**
         * The multi-select LOGIC object, which owns the selection state.
         *
         * Its interface `ListLogicApi` (reached via `MultiSelectLogic.LJZ()`) declares the two
         * accessors this feature needs, both un-obfuscated:
         *
         *     getSelectedData()Landroidx/lifecycle/LiveData;
         *     getMultiSelectInf()LX/1C0m;      -- and X.1C0m.LIZ() returns a TreeSet
         *
         * Used to READ the selected messages for 逐条 / 合并 analysis.
         */
        const val MULTI_SELECT_LOGIC =
            "com.ss.android.ugc.aweme.im.business.chat.feature.multiSelect.MultiSelectLogic"

        /** Douyin's multi-select action labels, used to locate the action row at runtime. */
        val MULTI_SELECT_LABELS: Set<String> = setOf(
            "\u5220\u9664",            // 删除 delete
            "\u4e3e\u62a5",            // 举报 report
            "\u8f6c\u53d1",            // 转发 forward
            "\u6536\u85cf",            // 收藏 favourite
            "\u4fdd\u5b58",            // 保存 save
            "\u5206\u4eab",            // 分享 share
            "\u590d\u5236",            // 复制 copy
            // ================= ENGLISH, FOR THE NON-CHINESE HOSTS =================
            // A review caught that the long-press menu had been given English labels while THIS list
            // had not, so the fix was only half applied: on TikTok (`com.ss.android.ugc.trill`, a real
            // entry in the scope list) the toolbar is English, MIN_LABELS_IN_ROW would never be met,
            // and the 分析 button would silently never appear -- the same failure the menu fix was
            // meant to prevent, on the multi-select path.
            //
            // Matching is case-insensitive via [isMultiSelectLabel], for the same reason as the menu.
            // =====================================================================
            "Delete",
            "Report",
            "Forward",
            "Favourite",
            "Favorite",
            "Save",
            "Share",
            "Copy",
            "Select",
            "Multi-select",
        )

        /**
         * How many action labels must share one container before it counts as the multi-select row.
         *
         * TWO, not three. The long-press menu uses three; here reverse engineering could not expand
         * every button's text (one is an indirect `@string/_` reference), so a three-label bar risked
         * rejecting the genuine row. The field-based route above is the primary identification, and
         * this only runs as its fallback -- so a slightly looser bar is the right trade.
         */
        const val MULTI_MIN_LABELS = 2

        /** How many times to poll for the multi-select row before giving up. */
        const val MULTI_SCAN_TRIES = 12

        /**
         * Delay between polls, in ms.
         *
         * The row does not exist when `onViewCreated` fires -- the buttons come from XML that Douyin
         * inflates into the toolbar afterwards -- so the scan waits rather than injecting into an
         * unmeasured container (the mistake that put the long-press button in the wrong place).
         */
        const val MULTI_SCAN_DELAY_MS = 80L

        /**
         * How long after an injection the same window refuses another one, in ms.
         *
         * The device measured the duplicate pair 61 ms apart, so 1200 ms covers the rebuild frames
         * with a wide margin while staying far below the time it takes a user to close and reopen a
         * menu -- a real reopen is therefore never suppressed.
         */
        const val INJECT_GUARD_MS = 1200L

        /** Tag marking the button this module injects, so it is never matched by its own label. */
        const val TAG_OUR_ROW = "luoliao_douyin_menu_row"

        /**
         * Tag for the 浮窗 (floating-balloon) entry, distinct from [TAG_OUR_ROW].
         *
         * A SEPARATE tag, not a shared one: [alreadyHasOurButton] asks "is our button in this tree?",
         * and one shared tag would answer yes for either entry -- so a menu that kept one and lost the
         * other would never be repaired, and a mismatch could add a duplicate instead.
         */
        const val TAG_BALLOON_ROW = "luoliao_douyin_balloon_row"

        /** The label of our injected row, matching the WeChat entry so the two hosts feel the same. */
        const val ROW_TITLE = "\u5206\u6790"   // 分析

        /**
         * The floating-balloon switch label, taken from WeChat's own entry (Constants.BALLON_ITEM_TITLE
         * is 浮窗) so the two hosts present the same word for the same action.
         */
        const val BALLOON_TITLE = "\u6d6e\u7a97"   // 浮窗

        /**
         * Douyin's OWN menu labels, used to LOCATE the button row at runtime.
         *
         * WHY A HARDCODED LIST IS ACCEPTABLE HERE (and why it does not suffer the arsc trap):
         * the reverse-engineering report established that these strings live in `resources.arsc`,
         * NOT in the dex -- so they can never be found by scanning the APK, and a static lookup
         * would silently find nothing. But this list is not used for a static lookup: it is compared
         * against `TextView.getText()` on the LIVE tree, where the label is already resolved to the
         * user's actual language. No arsc access is required.
         *
         * The list only has to cover ONE label per menu state to work, since any single hit locates
         * the row. Several are listed so the search succeeds on messages where some items are hidden
         * (e.g. 撤回 only appears for the sender's own messages).
         *
         * If Douyin ever re-words all of these, the anchor falls back to shape-based detection and
         * the log records which branch ran.
         */
        val MENU_LABELS: Set<String> = setOf(
            "\u590d\u5236",            // 复制 copy
            "\u8f6c\u53d1",            // 转发 forward
            "\u591a\u9009",            // 多选 multi-select
            "\u5220\u9664",            // 删除 delete
            "\u4e3e\u62a5",            // 举报 report
            "\u5f15\u7528",            // 引用 quote
            "\u6536\u85cf",            // 收藏 favourite
            "\u64a4\u56de",            // 撤回 recall
            // ================= ENGLISH, FOR THE NON-CHINESE HOSTS =================
            // The scope list includes `com.ss.android.ugc.trill`, which is TikTok: its menu is in
            // English (or another Latin language), so a Chinese-only list would never match there and
            // the button would silently never appear -- a review flagged exactly this, and it is a
            // real hole rather than a theoretical one.
            //
            // These are matched with `equalsIgnoreCase` below, because menu capitalisation differs
            // between Android versions and Douyin builds.
            //
            // Ambiguity is not a concern here the way it would be for a single-label test: the row is
            // accepted only when MIN_LABELS_IN_ROW of these appear together under ONE container, and
            // "Copy" + "Forward" + "Delete" co-occurring is as strong a signal in English as 复制 +
            // 转发 + 删除 is in Chinese. A lone "Copy" elsewhere still accumulates only one.
            // =====================================================================
            "Copy",
            "Forward",
            "Share",
            "Select",
            "Multi-select",
            "Delete",
            "Report",
            "Save",
            "Reply",
            "Quote",
            "Favorite",
            "Favourite",
            "Recall",
            "Unsend",
            // The remaining Chinese entries, kept after the English block for readability.
            "\u7f16\u8f91",            // 编辑 edit
            "\u56de\u590d",            // 回复 reply
        )

        /**
         * How many of [MENU_LABELS] must appear under ONE container before it is treated as the
         * menu's item row.
         *
         * THREE, not one. A single menu word is weak evidence: 回复 / 复制 also appear in OTHER
         * surfaces of the chat screen, so one occurrence can point at a container that is not the
         * menu. A real long-press menu shows 复制 + 转发 + 多选 + 删除 together, so requiring
         * co-occurrence identifies it by what the user actually sees.
         *
         * -----------------------------------------------------------------------------------------
         * CORRECTED: this used to blame `ImAutoLayerTabLayout` as an "emoji strip" that the shape
         * guesses kept selecting. That attribution was WRONG and has been retracted after
         * disassembly showed `ImAutoLayerTabLayout` IS the long-press menu's own item row -- Douyin
         * constructs its params and addViews the menu items into it itself.
         *
         * The real lesson the counter survives on is different, and still holds: the menu row's
         * identity must be established by evidence INSIDE the container (labels), never by a shape
         * guess such as "any horizontal LinearLayout with children", because such a rule has no way
         * to tell the menu from any other row on the screen.
         * -----------------------------------------------------------------------------------------
         *
         * If Douyin ever trims its menu to fewer items, lower this -- but lower it deliberately, and
         * keep the measure verification in [injectRow] as the backstop.
         */
        const val MIN_LABELS_IN_ROW = 3

        /**
         * Cap on diagnostic lines per kind. A chat list binds constantly; without a cap the
         * diagnostic would bury the very log it exists to clarify.
         */
        const val DIAG_LIMIT = 8

        /**
         * How many times / how long to keep looking for the menu's item container after the window
         * is shown.
         *
         * The PopupWindow's content is inflated on the same frame, but Douyin also animates it in,
         * and on a slow first open the container is not attached yet. Polling a few frames is far
         * cheaper than a wrong "no row" result, and it is bounded so nothing leaks.
         */
        const val SCAN_TRIES = 12
        const val SCAN_INTERVAL_MS = 50L
    }

    @Volatile
    private var menuHooked = false

    /**
     * Whether the message bind hook installed, so the install log states it explicitly.
     *
     * Worth its own flag: without this hook the module still works, but history silently reverts to
     * "only on analysis" -- which is exactly the bug the user reported. A single log line makes that
     * difference visible instead of leaving it to be rediscovered from an empty directory.
     */
    private var bindHooked = false

    /** Whether the multi-select entry point was hooked, so the install log states it. */
    private var multiSelectHooked = false

    /** How many times each bind hook fired -- see [startBindReport] for why this is counted. */
    @Volatile
    private var bindFires = 0

    @Volatile
    private var baseBindFires = 0

    @Volatile
    private var baseBindHooked = false

    /** Holders seen without a getCurrentMsg(), capped so the log cannot flood. */
    @Volatile
    private var bindMisses = 0

    /**
     * Holder classes already reported, so the diagnostic lists TYPES instead of events.
     *
     * A set of names rather than a counter: the conversation list binds many rows before any chat is
     * opened, and a plain counter was exhausted by those, hiding the chat page's holders entirely
     * (see [diagnoseHolder]).
     */
    private val seenHolderClasses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Resolved "message at index" per adapter class name.
     *
     * Cached because the lookup runs on every single bind -- a chat scrolls at 60 binds/second, and
     * walking declared methods across four class levels each time would be real per-frame work.
     */
    private val atCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

    /** Distinct (talker, text) pairs already reported, so [diagnoseMessage] prints each one once. */
    private val seenDiag = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** The menu tree is dumped once per process; a repeat on every long press would flood the log. */
    @Volatile
    private var treeDumped = false

    /**
     * The Douyin label that located the item row, for the log.
     *
     * Recorded because it is the EVIDENCE for the anchor choice: "found the row via 转发" is
     * checkable, whereas "injected into LinearLayout" was not -- and the three earlier anchor
     * guesses each looked fine in the log while putting the button in the wrong place.
     */
    @Volatile
    private var matchedLabel: String? = null

    /**
     * Set by [findItemRowByLabel] when it found menu labels but too few of them TOGETHER, meaning it
     * deliberately declined to inject.
     *
     * Distinguishes "refused" from "not found" so [findRowAnchor] can stop instead of falling
     * through to the shape-based anchors. The review that added this was right about the MECHANISM --
     * a refusal that silently falls through is worse than no guard, because a shape guess then picks
     * whatever happens to match, with no evidence at all that it is the menu. See the comment on
     * [findRowAnchor].
     *
     * (The earlier wording named the emoji strip as the container being re-selected. That
     * attribution was retracted -- `ImAutoLayerTabLayout` is the menu row itself -- but the guard is
     * still correct for the reason above: the fallback had no positive evidence either way.)
     */
    @Volatile
    private var refusedByLabel = false

    /** Guards against injecting twice into the SAME window instance. */
    private val injectedWindows = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<PopupWindow, Boolean>(),
    )

    fun install() {
        Log.i(TAG, ">> douyin hooks: installing (host=" + HostApp.current.name + ")")
        step("message model") { logMessageModel() }
        step("input box") { logInputBox() }
        step("menu window") { installMenuWindow() }
        step("message bind (live history)") { installMessageBindHook() }
        step("multi-select") { installMultiSelectHook() }
        Log.i(TAG, ">> douyin hooks: done (menuHooked=" + menuHooked +
            " bindHooked=" + bindHooked + " multiSelectHooked=" + multiSelectHooked + ")")
    }

    /**
     * Hooks Douyin's adapter bind so every message Douyin renders feeds the live history recorder.
     *
     * ============================ THIS IS DOUYIN'S EQUIVALENT OF WECHAT'S BINDER ==================
     *
     * The user asked for history 「跟微信一样 每2秒循环写入一次」 instead of only on analysis. WeChat
     * achieves that by PUSHING: `binder.liveMessageSink` fires from the adapter's bind method
     * (AnalysisController line 69) and stages each message, while a 2-second timer flushes the staged
     * lines. Douyin had no such feeder, which is why nothing was written until an analysis ran.
     *
     * An earlier attempt walked the view tree every two seconds to collect messages. The user rejected
     * it -- 「为什么要用识图啊 跟微信那样不好吗」 -- correctly: it was indirect, it duplicated a
     * mechanism the project already had, and it would have re-read the same rows forever. This hook
     * restores the push model, so BOTH hosts now use the identical pipeline: host binds a row -> stage
     * -> 2-second flush.
     *
     * THE HOOK POINT, verified in classes29.dex:
     *
     *   com.ss.android.ugc.aweme.im.business.chat.msgcell.common.CellComposeAdapter
     *     onBindViewHolder(Landroidx/recyclerview/widget/RecyclerView$ViewHolder;I)V
     *
     * `CellComposeAdapter` is Douyin's message-list adapter (it declares getItemViewType / getItemId /
     * onBindViewHolder and lives in the msgcell package), and `onBindViewHolder` is the exact analogue
     * of the WeChat adapter method this module already hooks. The method name is NOT obfuscated.
     *
     * The message is read from the holder via `getCurrentMsg()` (also un-obfuscated), matched by SHAPE
     * because the holder's concrete class varies by cell type.
     * =============================================================================================
     */
    private fun installMessageBindHook() {
        val cls = loadClass(BIND_ADAPTER) ?: run {
            Log.w(
                TAG,
                "douyin: the message adapter is not loadable (" + BIND_ADAPTER + "); live history " +
                    "will only be written on analysis -- the rest of the module is unaffected",
            )
            return
        }
        // Find the 2-arg bind, by NAME + SHAPE. Douyin also declares a 3-arg overload
        // (ViewHolder, int, List) for payloads; hooking THAT one as well would double-report every
        // message, so only the plain 2-arg form is used.
        val bind = cls.declaredMethods.firstOrNull { m ->
            m.name == "onBindViewHolder" && m.parameterCount == 2
        } ?: run {
            Log.w(TAG, "douyin: " + BIND_ADAPTER + " has no 2-arg onBindViewHolder; not hooking")
            return
        }
        XposedBridge.hookMethod(bind, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    bindFires++
                    val holder = param.args[0] ?: return
                    val msg = messageOf(holder)
                    if (msg == null) {
                        // DIAGNOSTIC, logged sparingly: the hook firing with a holder whose accessor
                        // is missing means the holder family changed, which is different from the hook
                        // not firing at all -- and only the log can tell those apart.
                        if (bindMisses < 3) {
                            bindMisses++
                            Log.w(
                                TAG,
                                "douyin: bind fired but the holder " +
                                    holder.javaClass.name + " has no getCurrentMsg()",
                            )
                        }
                        return
                    }
                    controller.onLiveMessage(msg)
                } catch (t: Throwable) {
                    // A throw here would run inside Douyin's own bind call, so it must never escape.
                    Log.w(TAG, "douyin: live message from the bind hook failed: " + t.message)
                }
            }
        })
        bindHooked = true
        Log.i(
            TAG,
            "douyin: hooked the message bind path (" + bind.declaringClass.name + "." + bind.name +
                ") -- live history now records without an analysis",
        )

        // A SECOND, WIDER NET: hook androidx RecyclerView.Adapter.bindViewHolder.
        //
        // WHY A SECOND HOOK IS NEEDED -- learned from the user's report. The bind hook above
        // installed cleanly (`bindHooked=true`) and then NEVER FIRED, while tapping 分析 DID write
        // history. That combination proves the pipeline works and only the FEED point was wrong:
        // `CellComposeAdapter` is a message adapter, but the chat screen evidently binds through a
        // different adapter (Douyin routes cells through several adapters/delegates).
        //
        // THE FIRST ATTEMPT AT THIS NET WAS ALSO WRONG, and the reason matters:
        // `RecyclerView.Adapter.onBindViewHolder(ViewHolder, int)` is an ordinary method that every
        // adapter OVERRIDES. Hooking the base declaration hooks a method no adapter actually calls,
        // so it would have failed exactly like CellComposeAdapter did.
        //
        // `bindViewHolder(ViewHolder, int)` is `public final` in RecyclerView.Adapter -- it cannot be
        // overridden, and it is the single funnel every adapter's bind passes through. Hooking a
        // FINAL method is therefore the one place that covers all concrete adapters at once, with no
        // Douyin class name involved, which is also what makes it survive a Douyin update.
        //
        // Non-chat holders are filtered by requiring getCurrentMsg(), so a RecyclerView full of
        // unrelated rows produces one failed method lookup each and never false history.
        try {
            val rvClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView",
                false,
                cls.classLoader,
            )
            val adapterClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$Adapter",
                false,
                cls.classLoader,
            )
            val holderClass = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                false,
                cls.classLoader,
            )
            val funnel = adapterClass.getDeclaredMethod(
                "bindViewHolder",
                holderClass,
                Int::class.javaPrimitiveType,
            )
            Log.i(
                TAG,
                "douyin: bindViewHolder modifiers=0x" +
                    java.lang.reflect.Modifier.toString(funnel.modifiers) +
                    " (must include 'final' for this hook to see every adapter)",
            )
            XposedBridge.hookMethod(funnel, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        baseBindFires++
                        val holder = param.args[0] ?: return
                        val position = (param.args[1] as? Int) ?: -1
                        // ADAPTER FIRST, holder second -- see messageAt() for the device evidence
                        // that forced this order.
                        val msg = messageAt(param.thisObject, position)
                            ?: messageOf(holder)
                            ?: run {
                                diagnoseHolder(holder, null)
                                return
                            }
                        diagnoseHolder(holder, msg)
                        diagnoseMessage(msg)
                        controller.onLiveMessage(msg)
                    } catch (_: Throwable) {
                        // never let this escape into Douyin's bind path
                    }
                }
            })
            baseBindHooked = true
            // Touching rvClass keeps the lookup honest: it is resolved so a missing class fails HERE,
            // at install time with a clear log, rather than silently at first bind.
            rvClass.name
            Log.i(
                TAG,
                "douyin: hooked RecyclerView.Adapter.bindViewHolder (final) as the catch-all feed " +
                    "-- covers every concrete adapter Douyin uses",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "douyin: the generic adapter hook failed: " + t.message)
        }

        // A periodic report, so "no history was written" can be diagnosed without guessing.
        // bindFires==0 means the feed point is still wrong; bindFires>0 with no file means the
        // problem is downstream (staging or flushing), which is a completely different fix.
        startBindReport()
    }

    /**
     * Logs each DISTINCT holder class once, saying whether the message accessor was reachable.
     *
     * WHY DEDUPED BY CLASS, NOT CAPPED BY COUNT -- this cost a whole debug round.
     *
     * The first version printed the first [DIAG_LIMIT] holders and stopped. The log then showed eight
     * rows that were ALL session-list holders (`NewRipsSessionViewHolder`, `BaseCellViewHolder` from
     * `message2.onlinelist_refactor`), because the conversation list binds many rows before the user
     * opens a chat. The eight slots were consumed before the CHAT screen ever bound, so the one thing
     * the diagnostic existed to reveal -- which holder the chat page uses -- was never printed.
     *
     * Deduping by class name fixes that properly: the session list contributes ONE line no matter how
     * many rows it binds, so opening a chat is guaranteed to add the chat's holders. The cap then
     * limits distinct TYPES rather than raw events, which is what "tell me the shapes involved"
     * actually means.
     *
     * The `msg` argument decides the verdict: an accessor found on a holder that also yields a message
     * is the feed working, and the two cases are logged differently so a working type is not confused
     * with a near-miss.
     */
    private fun diagnoseHolder(holder: Any, msg: Any?) {
        val cls = holder.javaClass
        if (!seenHolderClasses.add(cls.name)) return
        if (seenHolderClasses.size > DIAG_LIMIT) return

        val chain = StringBuilder(cls.simpleName)
        var c: Class<*>? = cls.superclass
        var d = 0
        while (c != null && d < 4) {
            chain.append(" <- ").append(c.simpleName)
            c = c.superclass
            d++
        }
        // Which level of the hierarchy declares the accessor, if any.
        var owner: String? = null
        var p: Class<*>? = cls
        var pd = 0
        while (p != null && pd < 8) {
            if (p.declaredMethods.any { it.name == "getCurrentMsg" && it.parameterCount == 0 }) {
                owner = p.simpleName
                break
            }
            p = p.superclass
            pd++
        }
        Log.i(
            TAG,
            "douyin BIND DIAG (new type #" + seenHolderClasses.size + "): " + chain +
                " accessor@" + (owner ?: "NOT FOUND") +
                " msg=" + (msg != null),
        )
    }

    /**
     * Logs, ONCE per distinct content, the message read out of a holder.
     *
     * The point is to see the ACTUAL text and talker the bind hook produces, so a downstream failure
     * (blank talker, blank text, a rejected type) is visible as itself rather than as "no file
     * appeared". If this line shows correct text and no history is still written, the fault is
     * provably downstream of the feed, which is a different investigation.
     */
    private fun diagnoseMessage(msg: Any) {
        try {
            val acc = controller.access()
            val tk = acc.talker(msg)
            val text = acc.content(msg)
            val isText = acc.isText(msg)
            val t = acc.createTime(msg)
            val id = acc.msgId(msg)
            val key = tk + "|" + text
            if (seenDiag.contains(key)) return
            if (seenDiag.size >= DIAG_LIMIT) return
            seenDiag.add(key)
            Log.i(
                TAG,
                "douyin MSG DIAG: talker='" + tk + "' isText=" + isText +
                    " time=" + t + " msgId=" + id +
                    " text='" + (if (text.length > 40) text.substring(0, 40) + "..." else text) + "'",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "douyin MSG DIAG failed: " + t.message)
        }
    }

    /**
     * Reports the state of the history pipeline every 20 seconds, regardless of activity.
     *
     * The question "why is nothing written?" has three possible answers, and this line separates
     * them without any further guessing:
     *   * genericBind=0          -> the bind feed never fired (feed point is wrong)
     *   * genericBind>0, staged=0 -> messages were read but rejected before staging (type/talker)
     *   * staged>0, written=0     -> staging works and the FLUSH is failing (disk/permission)
     *
     * reported by AnalysisController, because that is where staging and flushing actually live.
     */
    private fun startBindReport() {
        try {
            val t = Thread {
                while (true) {
                    try {
                        Thread.sleep(20_000L)
                        Log.i(
                            TAG,
                            "douyin PIPE: adapterBind=" + bindFires +
                                " genericBind=" + baseBindFires +
                                " finalHooked=" + baseBindHooked +
                                " holderTypes=" + seenHolderClasses.size +
                                " | " + controller.historyPipelineState(),
                        )
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Throwable) {
                        // keep reporting; a failed line must not kill the reporter
                    }
                }
            }
            t.name = "LuoLiao-douyin-pipe-report"
            t.isDaemon = true
            t.start()
        } catch (_: Throwable) {
            // the report is diagnostic only
        }
    }

    /**
     * The message at [position] according to the ADAPTER, or null.
     *
     * ==================== WHY THE ADAPTER, NOT THE HOLDER (device evidence) ======================
     *
     * The first implementation read the message off the view holder via `getCurrentMsg()`, because
     * that accessor exists on `BasicViewHolder` and is not obfuscated. The device diagnostic proved
     * that assumption wrong:
     *
     *   douyin BIND DIAG (new type #6): MsgCellComposeViewHolder <- CellComposeViewHolder
     *       <- SplitViewHolder <- ImRecyclerView$ImViewHolder
     *       accessor@NOT FOUND  msg=false
     *
     * The chat page's holder is `MsgCellComposeViewHolder`, which descends from `SplitViewHolder` and
     * is NOT in the `BasicViewHolder` family at all. So `getCurrentMsg()` was never reachable on the
     * holders the chat actually binds, and every one of the 63 bind events produced no message --
     * `staged=0`, hence `written=0` and the empty history the user reported.
     *
     * The adapter, by contrast, DOES expose the mapping: `CellComposeAdapter` declares
     *
     *     LLLILZ(I)Lcom/bytedance/im/core/model/Message;      (classes29.dex)
     *
     * i.e. "message at index N", with a real `Message` return type that is NOT obfuscated. That is
     * the same relationship as WeChat's adapter exposing its message list, and it is found by SHAPE
     * (one int parameter, Message return type) so the obfuscated NAME does not matter.
     *
     * The holder route is kept as a fallback, because a holder that does carry the accessor is
     * cheaper and some cell types may only be reachable that way.
     * =============================================================================================
     */
    private fun messageAt(adapter: Any?, position: Int): Any? {
        if (adapter == null || position < 0) return null
        try {
            // Cache the resolved method per adapter class: this runs on every bind, and the lookup
            // walks declared methods of four classes, which is not free at chat scroll speed.
            val cls = adapter.javaClass
            var m = atCache[cls.name]
            if (m == null) {
                m = findMessageAtMethod(cls)
                if (m != null) {
                    m.isAccessible = true
                    atCache[cls.name] = m
                }
            }
            val got = m?.invoke(adapter, position) ?: return null
            return if (isMessage(got)) got else null
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Finds "message at index" on an adapter class or its ancestors.
     *
     * Matched by SHAPE, not name: exactly one int parameter and a return type whose name ends in
     * `.Message`. The name ends in Message rather than equalling a fixed string so a subclass or a
     * wrapper still matches, while the tag check in [isMessage] keeps a look-alike class from being
     * passed downstream as if it were a message.
     */
    private fun findMessageAtMethod(cls: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 6) {
            for (m in c.declaredMethods) {
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                if (!m.returnType.name.endsWith(".Message")) continue
                if (m.returnType.name.contains("im.core")) return m
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /** True when [o] is the Douyin message model, checked without hard-depending on the class. */
    private fun isMessage(o: Any): Boolean = try {
        o.javaClass.name == MESSAGE || o.javaClass.name.startsWith("com.bytedance.im.core.model.")
    } catch (_: Throwable) {
        false
    }

    /**
     * Adds the module's 分析 entry to Douyin's MULTI-SELECT action row.
     *
     * ============================== WHAT THIS IS FOR (user request) ==============================
     *
     * 「多选新增分析 原有按钮 逐条分析 合并分析 删除 举报 新增 按钮 分析 图标用原本的」
     *
     * After entering multi-select in Douyin, a row of actions appears (删除 / 举报 / ...). The user
     * wants 分析 in that row, next to them, supporting both 逐条 and 合并 analysis. WeChat has the
     * same feature (MenuHook.installMultiSelectHook / addMultiSelectButton), so this mirrors it.
     *
     * THE WECHAT VERSION HOOKS A CONCRETE CLASS and REWRITES ITS BUTTON LIST. Douyin is different:
     * its chat screen is RIPS-based (`BasicRipsLogic` / `RipsUI`), with the multi-select state in
     *
     *   com.ss.android.ugc.aweme.im.business.chat.feature.multiSelect.MultiSelectLogic   (classes29)
     *   com.ss.android.ugc.aweme.im.business.chat.feature.multiSelect.MultiSelectUI      (classes29)
     *
     * `MultiSelectUI.onViewCreated(View)` is the point where the multi-select chrome is built, so
     * that is what this hooks, and the row is then found IN THE LIVE VIEW TREE by its labels -- the
     * same technique that fixed the long-press menu, and deliberately so: it depends on no obfuscated
     * class, id or orientation.
     *
     * WHY THE FIRST VERSION OF THIS IS A DIAGNOSTIC ONLY: the previous two attempts at Douyin
     * injection both failed by assuming a structure (the holder family; the menu container), and both
     * times a printed view tree settled it in one round. Rather than guess again, this hooks the entry
     * point and DUMPS the tree, so the row can be identified from real output.
     * =============================================================================================
     */
    private fun installMultiSelectHook() {
        val cls = loadClass(MULTI_SELECT_UI) ?: run {
            Log.w(
                TAG,
                "douyin: the multi-select UI class is not loadable (" + MULTI_SELECT_UI +
                    "); multi-select 分析 is unavailable on this build",
            )
            return
        }
        val m = cls.declaredMethods.firstOrNull { it.name == "onViewCreated" } ?: run {
            Log.w(TAG, "douyin: " + MULTI_SELECT_UI + " has no onViewCreated; not hooking")
            return
        }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    multiSelectHost = param.thisObject
                    val root = param.args[0] as? View
                    // ================= WHY THE VIEW IS NOT SEARCHED ANY MORE =================
                    // The first versions walked the view tree (and even looked the Activity up
                    // through ActivityThread) to find the toolbar. Both were the wrong approach and
                    // the user said so plainly: the hook is ALREADY holding the object that owns the
                    // toolbar, so the toolbar must be READ FROM THE OBJECT, not hunted for in the UI.
                    //
                    // The class is known (verified in classes6.dex): MultiChooseFuncUI holds its
                    // 5-button LinearLayout in a FIELD and wires it in this very method. So the field
                    // is read directly -- that is what a hook is for, and it cannot pick the wrong
                    // view the way a label scan can.
                    // ========================================================================
                    Log.i(
                        TAG,
                        "douyin MULTISELECT: onViewCreated (root=" +
                            (root?.javaClass?.name ?: "null") + "); the toolbar is read from the " +
                            "host object's own fields",
                    )
                    multiSelectRoot = root
                    // The fields are populated INSIDE onViewCreated, and this hook runs after it, so
                    // the toolbar is available now -- no polling, no delay. If it is somehow not, the
                    // scan helper is still the fallback.
                    val row = fieldRowOf(multiSelectHost)
                    if (row != null) {
                        onMultiSelectRowReady(row)
                    } else {
                        Log.i(
                            TAG,
                            "douyin MULTISELECT: no toolbar field yet after onViewCreated; " +
                                "falling back to a short poll",
                        )
                        scanMultiSelectRow(0)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "douyin MULTISELECT diagnostics failed: " + t.message)
                }
            }
        })
        multiSelectHooked = true
        Log.i(
            TAG,
            "douyin: hooked the multi-select entry (" + MULTI_SELECT_UI + ".onViewCreated)",
        )
    }

    /**
     * The multi-select root view, kept so the button can be injected into the row once it is built.
     *
     * The tree is NOT complete when onViewCreated fires -- Douyin adds the action row afterwards --
     * so the reference is retained and used on a short delay instead of injecting immediately. That
     * is the same lesson the long-press menu taught: injecting at the first opportunity put the
     * button in an unmeasured container.
     */
    @Volatile
    private var multiSelectRoot: View? = null

    /**
     * The `MultiChooseFuncUI` instance whose `onViewCreated` fired, kept so its own FIELDS can be
     * searched for the toolbar rather than the obfuscated field name being hardcoded. See
     * [fieldRowOf].
     */
    @Volatile
    private var multiSelectHost: Any? = null

    /**
     * Polls the current window for the multi-select ACTION ROW and injects 分析 into it.
     *
     * WHY THE WHOLE WINDOW, NOT the view handed to onViewCreated: the device showed that parameter is
     * the `MultiSelectTipsView` banner ("N 条消息已选" style), which is a SIBLING of the action row,
     * not its parent. The multi-select chrome is assembled from several independent RIPS components,
     * so the row must be located from the window root like the long-press menu was.
     *
     * POLLING IS DELAYED, not immediate: onViewCreated fires before Douyin has added the action row,
     * so an immediate scan finds nothing. This polls on the UI thread's queue for a short window,
     * which is the same "wait for the real layout" rule the menu injection follows.
     */
    private fun scanWindowForMultiSelectRow() {
        scanMultiSelectRow(0)
    }

    /**
     * One scan attempt, rescheduled until the row appears or [MULTI_SCAN_TRIES] is exhausted.
     *
     * Runs on the UI thread via [Handler.postDelayed] because it both READS and WRITES the view tree;
     * touching views off the UI thread is what makes a "works on my machine" module flaky. `post`
     * also naturally waits for the frame that builds the row.
     */
    private fun scanMultiSelectRow(attempt: Int) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                // NO SILENT RETURNS. The first version returned quietly when the Activity or its
                // decor view was unavailable, and the result was a log with neither a scan record nor
                // an error -- which cost a whole round and could only be explained by re-reading this
                // function. Every exit below states its reason.
                //
                // THE VIEW ROOT IS TAKEN FROM THE HOOK, NOT FROM AN ACTIVITY LOOKUP. The Activity
                // route failed on every attempt
                //   ("no Activity reachable (attempt 0)")
                // while the hook already holds the multi-select subtree, so the window root is
                // reached by walking UP from it -- no ActivityThread reflection involved.
                val start = multiSelectRoot
                if (start == null) {
                    if (attempt == 0) {
                        Log.w(TAG, "douyin MULTISELECT scan: the hook captured no root view")
                    }
                    return@postDelayed
                }
                var rootView: View = start
                var guard = 0
                while (guard < 40) {
                    val p = rootView.parent
                    if (p !is View) break
                    rootView = p
                    guard++
                }
                val row = findMultiSelectRow(rootView)
                if (row != null && row.width > 0) {
                    // ROUTED THROUGH onMultiSelectRowReady, not injected directly.
                    //
                    // A review found this call site had only the per-TREE guard while the field route
                    // above had all three -- the very same gap that produced the duplicate button on
                    // the device, left open on the path taken when the field lookup fails. Funnelling
                    // both routes through one function is what makes that class of bug impossible
                    // here: the guards cannot drift apart because there is only one place they live.
                    onMultiSelectRowReady(row)
                    return@postDelayed
                }
                if (attempt == 0 || attempt == MULTI_SCAN_TRIES - 1) {
                    // Dumped only on the first and last attempt: a full tree is expensive to log and
                    // the first one shows what exists immediately, the last what exists after waiting.
                    val sb = StringBuilder()
                    dumpTree(rootView, sb, 0)
                    Log.i(
                        TAG,
                        "douyin MULTISELECT scan #" + attempt + " (row=" +
                            (row?.javaClass?.name ?: "null") +
                            " width=" + (row?.width ?: -1) +
                            " root=" + rootView.javaClass.name + "):\n" + sb,
                    )
                }
                if (attempt < MULTI_SCAN_TRIES) {
                    scanMultiSelectRow(attempt + 1)
                } else {
                    Log.w(
                        TAG,
                        "douyin MULTISELECT: no action row found after " + MULTI_SCAN_TRIES +
                            " scans; 分析 is unavailable in multi-select on this build",
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "douyin MULTISELECT scan failed: " + t.message, t)
            }
        }, MULTI_SCAN_DELAY_MS)
    }

    /**
     * Finds the multi-select action row.
     *
     * TWO IDENTIFICATION ROUTES, tried in order, because they fail in different ways:
     *
     *   1. BY THE HOST'S OWN VIEW: reverse engineering established that `MultiChooseFuncUI` holds the
     *      5-button LinearLayout in a FIELD, and that `onViewCreated` is where it is wired. Rather
     *      than depend on the obfuscated field name, the class's own declared fields are scanned for a
     *      `LinearLayout` that actually contains several TextView buttons -- a shape test that cannot
     *      pick the wrong view.
     *   2. BY LABELS: the same co-occurrence technique the long-press menu uses. Kept as a fallback
     *      so a Douyin update that changes the field layout degrades to a still-correct result.
     *
     * The label route alone is NOT enough here: reverse engineering found the fourth button's text is
     * an indirect `@string/_` reference it could not expand, so the label list may be incomplete --
     * which is exactly why the field route is tried first.
     */
    private fun findMultiSelectRow(root: View): ViewGroup? {
        // (1) the host's own field
        fieldRowOf(multiSelectHost)?.let { return it }
        // (2) labels
        return findRowByLabels(root)
    }

    /**
     * The toolbar among the views inside [root], or null.
     *
     * ============================ SCORED BY EVIDENCE, NOT BY SHAPE ==============================
     *
     * The first version returned the first container with two or more TextViews. That is a shape
     * rule with the same weakness that mislocated the long-press menu: plenty of rows contain two
     * text views (a title bar, a tips strip), so "first match in breadth-first order" is a coin toss.
     *
     * It now scores every container by HOW MANY OF ITS CHILDREN DISPLAY ONE OF DOUYIN'S OWN ACTION
     * LABELS, and returns the best-scoring one. That is positive evidence about what the container IS,
     * which is the standard the menu work settled on, and it cannot be satisfied by an unrelated row
     * that merely happens to have two labels of text.
     *
     * Breadth-first with a full scan rather than early return, because the winner must be chosen from
     * all candidates; the visit cap keeps that bounded on a deep chat-screen tree.
     * ===========================================================================================
     */
    private fun findToolbarInside(root: ViewGroup): ViewGroup? {
        val queue = ArrayDeque<ViewGroup>()
        queue.add(root)
        var visited = 0
        var best: ViewGroup? = null
        var bestScore = 0
        while (queue.isNotEmpty() && visited < 400) {
            val v = queue.removeFirst()
            visited++
            var labels = 0
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                // A child counts when it IS a label, or when it CONTAINS exactly one -- Douyin wraps
                // each action in a clickable item that holds the TextView, so the button's own label
                // sits one level below the row. Counting both levels is what makes the row itself
                // (not the wrapper's parent) win the score.
                if (child is android.widget.TextView) {
                    if (isMultiSelectLabel(child.text?.toString()?.trim().orEmpty())) labels++
                } else if (child is ViewGroup && child.childCount == 1) {
                    val only = child.getChildAt(0)
                    if (only is android.widget.TextView &&
                        isMultiSelectLabel(only.text?.toString()?.trim().orEmpty())
                    ) {
                        labels++
                    }
                }
                if (child is ViewGroup) queue.add(child)
            }
            if (labels > bestScore) {
                bestScore = labels
                best = v
            }
        }
        if (best != null) {
            Log.i(
                TAG,
                "douyin MULTISELECT: toolbar candidate " + best.javaClass.name +
                    " scored " + bestScore + " action label(s) among " + visited + " containers",
            )
        }
        return best
    }

    /** True when [text] is one of Douyin's multi-select action labels. */
    private fun isMultiSelectLabel(text: String): Boolean {
        if (text.isEmpty()) return false
        if (MULTI_SELECT_LABELS.contains(text)) return true
        for (l in MULTI_SELECT_LABELS) {
            if (l.equals(text, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Injects the 分析 button into a toolbar that has been located, exactly once.
     *
     * Kept separate from the locating code because BOTH routes land here (the field read and the
     * polling fallback), and because the once-only decision belongs in one place: a toolbar that is
     * rebuilt while multi-select stays open must not accumulate a second button.
     */
    private fun onMultiSelectRowReady(row: ViewGroup) {
        if (alreadyHasOurButton(row) || windowAlreadyHasOurButton(row)) {
            Log.i(TAG, "douyin MULTISELECT: the 分析 button is already in this toolbar")
            return
        }
        if (injectedTooRecently(currentWindowOf(row))) {
            Log.i(TAG, "douyin MULTISELECT: injected moments ago into this window; skipping")
            return
        }
        injectMultiSelectButton(row)
        noteInjected(currentWindowOf(row))
    }

    /**
     * The 5-button toolbar held in one of the host object's fields, or null.
     *
     * Identified by SHAPE: a LinearLayout with at least two TextView children. That is what a row of
     * text buttons is, and no other field of this class can satisfy it -- so the obfuscated name is
     * irrelevant, which is the same reasoning that let the module survive the message-holder and menu
     * renames.
     */
    private fun fieldRowOf(host: Any?): ViewGroup? {
        if (host == null) {
            Log.w(TAG, "douyin MULTISELECT: the host object was not captured; using the label route")
            return null
        }
        // ======================= THE OBJECT'S OWN API COMES FIRST =======================
        // `RipsUI` -- the base class of MultiChooseFuncUI -- declares
        //     getRootView()Landroid/view/View;
        // which is exactly the accessor for "the view this UI object is responsible for".
        // Asking the OBJECT is what the hook should do; hunting through the tree is the fallback,
        // not the primary route. Verified present in classes31.dex.
        //
        // This is tried BEFORE the field scan because it is a declared, stable API, whereas field
        // names are obfuscated and their declared types may be wider than the values they hold.
        // ================================================================================
        try {
            val getRoot = host.javaClass.methods.firstOrNull {
                it.name == "getRootView" && it.parameterCount == 0
            }
            val rootView = getRoot?.invoke(host) as? ViewGroup
            if (rootView != null) {
                Log.i(
                    TAG,
                    "douyin MULTISELECT: the host's getRootView() returned " +
                        rootView.javaClass.name + " children=" + rootView.childCount,
                )
                // The toolbar is somewhere inside the object's own root view, so this search is
                // scoped to the subtree the object OWNS -- unlike the earlier window-wide scan,
                // which could in principle match an unrelated row.
                findToolbarInside(rootView)?.let { return it }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "douyin MULTISELECT: getRootView() was not usable: " + t.message)
        }
        var c: Class<*>? = host.javaClass
        var depth = 0
        val seen = StringBuilder()
        while (c != null && depth < 8) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    // ===================== THE DECLARED TYPE IS NOT THE QUESTION =====================
                    // The first version only looked at fields DECLARED as ViewGroup, and the device
                    // log came back with an empty candidate list:
                    //     "no ViewGroup field looked like the toolbar; fields seen:"
                    // -- i.e. this class stores its views behind a WIDER type (View, Object, or a
                    // RIPS interface). So every field is now read and its VALUE is tested instead:
                    // what the object actually points at is the fact that matters, not how the field
                    // was typed when the compiler emitted it.
                    // ===============================================================================
                    val value = f.get(host)
                    if (value !is ViewGroup) continue
                    // SCORED BY LABEL EVIDENCE, the same standard as [findToolbarInside]: a container
                    // is a candidate when its children display Douyin's own action words. Counting
                    // TextViews instead was the first version's mistake -- two text views is a title
                    // bar or a tips strip just as easily as a toolbar.
                    var labels = 0
                    for (i in 0 until value.childCount) {
                        val child = value.getChildAt(i)
                        if (child is android.widget.TextView) {
                            if (isMultiSelectLabel(child.text?.toString()?.trim().orEmpty())) labels++
                        } else if (child is ViewGroup && child.childCount == 1) {
                            val only = child.getChildAt(0)
                            if (only is android.widget.TextView &&
                                isMultiSelectLabel(only.text?.toString()?.trim().orEmpty())
                            ) {
                                labels++
                            }
                        }
                    }
                    // Every ViewGroup value is recorded, so a failed match still shows what the class
                    // actually holds -- a log line here is what turns "no button appeared" into a
                    // specific next step.
                    seen.append("\n  ").append(c.simpleName).append('.').append(f.name)
                        .append(" (").append(f.type.simpleName).append(") = ")
                        .append(value.javaClass.simpleName)
                        .append(" children=").append(value.childCount)
                        .append(" labels=").append(labels)
                    // TWO action labels minimum: Douyin's toolbar has five, while a wrapper holding a
                    // single label (a title, a tip) is rejected.
                    if (labels >= 2) {
                        Log.i(
                            TAG,
                            "douyin MULTISELECT: found the toolbar in field '" + f.name +
                                "' of " + c.simpleName + " (" + value.childCount +
                                " children, " + labels + " action label(s))",
                        )
                        return value
                    }
                } catch (_: Throwable) {
                    // a field that cannot be read is skipped, not fatal
                }
            }
            c = c.superclass
            depth++
        }
        Log.i(
            TAG,
            "douyin MULTISELECT: no field value looked like the toolbar; fields seen:" + seen,
        )
        return null
    }

    /**
     * The action row located by co-occurrence of Douyin's own action labels.
     *
     * The count bar is LOWER here than for the long-press menu (2 rather than 3) because reverse
     * engineering could not expand every button's text -- one is an indirect string reference -- so a
     * higher bar could reject the real row. Two co-occurring action words are still far stronger than
     * the single-label test that mislocated the menu.
     */
    private fun findRowByLabels(root: View): ViewGroup? {
        val counts = LinkedHashMap<ViewGroup, MutableSet<String>>()

        fun note(label: String, v: View) {
            // THREE levels, matching [findItemRowByLabel]. A review pointed out this path had been
            // left at four while the menu path was narrowed to three, so the two behaved differently
            // on the same tree -- and an over-wide climb is exactly what lets a SHARED ANCESTOR
            // collect both subtrees' labels and out-score the real row.
            var p: android.view.ViewParent? = v.parent
            var level = 0
            while (p is ViewGroup && level < 3) {
                counts.getOrPut(p) { LinkedHashSet() }.add(label)
                p = p.parent
                level++
            }
        }

        fun walk(v: View, depth: Int) {
            if (depth > 24) return
            if (v is android.widget.TextView && v.text != null) {
                val t = v.text.toString().trim()
                // isMultiSelectLabel, not a raw `contains`: the list now carries English words for
                // TikTok, and a case-sensitive exact test would miss "Delete" vs "delete".
                if (t.isNotEmpty() && isMultiSelectLabel(t)) note(t, v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)

        // Ranking identical to [findItemRowByLabel]: most distinct labels wins, and on a tie the
        // DEEPER container wins, because an ancestor sees all of the row's labels too and would
        // otherwise be able to win on iteration order alone.
        var best: ViewGroup? = null
        var bestScore = -1
        var bestDepth = -1
        for ((container, labels) in counts) {
            if (labels.size < MULTI_MIN_LABELS) continue
            var depth = 0
            var p: android.view.ViewParent? = container
            while (p != null && depth < 40) {
                p = p.parent
                depth++
            }
            val score = labels.size * 100
            if (score > bestScore || (score == bestScore && depth > bestDepth)) {
                bestScore = score
                bestDepth = depth
                best = container
            }
        }
        return best
    }

    /**
     * Adds the 分析 button to the multi-select action row.
     *
     * ============================ WHY THE BUTTON IS A CLONE, NOT A NEW VIEW =====================
     *
     * Douyin's five toolbar buttons are declared in `r/z-b.xml` as `width=0dp` + `weight=8dp` -- five
     * EQUAL SHARES of the bar. Reverse engineering flagged three distinct traps with that layout, and
     * cloning a real sibling avoids all three at once:
     *
     *   * a WRAP_CONTENT child would take its natural width and squeeze the other five off-balance;
     *   * a `0dp` child WITHOUT a weight has zero width -- an invisible button, which is
     *     indistinguishable from a failed injection and would send the next debug round down a
     *     completely wrong path;
     *   * the container's accepted layoutParams class comes from a real child, so it cannot be wrong.
     *
     * The sibling is `DmtButton extends AppCompatButton` (i.e. also a TextView), so it carries the
     * icon + label arrangement Douyin's own buttons use, and its `layoutParams` instance is copied
     * into a fresh object rather than shared.
     * =============================================================================================
     */
    private fun injectMultiSelectButton(row: ViewGroup) {
        try {
            val ctx = row.context ?: return
            val density = ctx.resources.displayMetrics.density

            // Clone the first child that is a TextView -- the class Douyin's own buttons use, and the
            // one whose layoutParams carry the weight. Falling back to the generic builder keeps the
            // feature alive on a build where the clone fails.
            val sibling = (0 until row.childCount)
                .map { row.getChildAt(it) }
                .firstOrNull { it is android.widget.TextView }
            // Logged BEFORE the clone attempt: knowing which sibling was chosen (and whether one was
            // found at all) is what distinguishes "the toolbar has no text buttons" from "the clone
            // failed", and those need opposite fixes.
            Log.i(
                TAG,
                "douyin MULTISELECT: toolbar " + row.javaClass.name + " children=" + row.childCount +
                    " sibling=" + (sibling?.javaClass?.name ?: "null"),
            )

            val btn: View = if (sibling != null) {
                val clone = try {
                    val ctor = sibling.javaClass.getConstructor(android.content.Context::class.java)
                    ctor.isAccessible = true
                    (ctor.newInstance(ctx) as? android.widget.TextView)
                } catch (_: Throwable) {
                    null
                }
                if (clone != null) {
                    clone.text = ROW_TITLE
                    clone.gravity = Gravity.CENTER
                    clone.isSingleLine = true
                    clone.isClickable = true
                    clone.isFocusable = true
                    clone.tag = TAG_OUR_ROW
                    // The icon is placed ABOVE the label (`..., 0, top, 0, 0`) to match Douyin's own
                    // buttons, which are icon-over-text. The Drawable is resolved into a local first:
                    // passing a nullable straight into the 4-arg overload is ambiguous in Kotlin
                    // ("None of the following candidates is applicable"), so the null case is handled
                    // explicitly with the label-only call.
                    val ic = iconDrawable(ctx, (20 * density).toInt())
                    if (ic != null) {
                        try {
                            clone.setCompoundDrawablesWithIntrinsicBounds(null, ic, null, null)
                        } catch (_: Throwable) {
                            // the label alone is still usable
                        }
                    }
                    clone.setOnClickListener { v -> onMultiSelectAnalyseClicked(v, row) }
                    // ============== "Layout parameters cannot be null" (seen on the device) ==========
                    // The first version assigned the result of the copy unconditionally, and the device
                    // log showed the injection dying with exactly that message: Android's
                    // `setLayoutParams(null)` throws it. The sibling can legitimately have no params
                    // yet (it is a child of a row that has not been through a measure pass when this
                    // runs), so a null copy is now EXPECTED and handled: the assignment is skipped and
                    // the button keeps the params its own constructor produced.
                    // =================================================================================
                    val lp = copyLayoutParamsOf(sibling) ?: weightedParamsFor(row)
                    if (lp != null) {
                        clone.layoutParams = lp
                    }
                    clone
                } else {
                    buildMenuButton(
                        ctx = ctx,
                        title = ROW_TITLE,
                        tag = TAG_OUR_ROW,
                        iconSize = (22 * density).toInt(),
                        onClick = { v -> onMultiSelectAnalyseClicked(v, row) },
                    )
                }
            } else {
                buildMenuButton(
                    ctx = ctx,
                    title = ROW_TITLE,
                    tag = TAG_OUR_ROW,
                    iconSize = (22 * density).toInt(),
                    onClick = { v -> onMultiSelectAnalyseClicked(v, row) },
                )
            }

            // ==================== THE NULL-PARAMS GUARANTEE ====================
            // The device log caught the injection dying with
            //     "douyin MULTISELECT injection failed: Layout parameters cannot be null"
            // which is Android's `addView`/`setLayoutParams` rejecting a null. `new DmtButton(context)`
            // legitimately starts with NO params -- a view created in code has none until one is
            // assigned -- so a clone that failed to inherit them arrives here null and kills the
            // whole injection. This closes the hole for good: whatever route produced the button, it
            // ends up carrying SOMETHING valid, with the row's own params class preferred because a
            // validating container accepts only its own type.
            // ===================================================================
            if (btn.layoutParams == null) {
                val fallback = weightedParamsFor(row)
                    ?: firstChildLayoutParams(row)?.let { copyLayoutParamsOf(row.getChildAt(0)) }
                    ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                btn.layoutParams = fallback
                Log.i(
                    TAG,
                    "douyin MULTISELECT: the button had no params; supplied " +
                        fallback.javaClass.simpleName,
                )
            }

            row.addView(btn)

            // Same safety net as the long-press row: force the measure here, where a throw is still
            // catchable, instead of letting Douyin's own later pass kill the process.
            if (!verifyMeasurable(row)) {
                try {
                    row.removeView(btn)
                } catch (_: Throwable) {
                    // already logged below
                }
                Log.e(
                    TAG,
                    "douyin MULTISELECT: " + row.javaClass.name +
                        " REJECTED our button during measure -- removed it to protect Douyin",
                )
                return
            }
            Log.i(
                TAG,
                "douyin MULTISELECT: injected (" + ROW_TITLE + ") into " +
                    row.javaClass.name + " children=" + row.childCount +
                    " cloned=" + (sibling != null) +
                    " lpClass=" + (btn.layoutParams?.javaClass?.simpleName ?: "null") +
                    " w=" + btn.width,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "douyin MULTISELECT injection failed: " + t.message, t)
        }
    }

    /**
     * LayoutParams for a new button in [row], built to match how Douyin lays that row out.
     *
     * Used when the sibling has no usable params to copy. Reverse engineering established the row is a
     * `LinearLayout` whose children are `width=0dp` + equal `weight`, so an equal share is the correct
     * shape here: it makes our button sit in the row exactly like a native one. A plain WRAP_CONTENT
     * child would instead squeeze the native buttons, and a `0dp` child with NO weight would be
     * invisible -- the failure mode that would look like "injection did not happen".
     *
     * Returns null when the row accepts neither form, in which case the caller leaves the button's own
     * params alone rather than forcing something the container rejects.
     */
    private fun weightedParamsFor(row: ViewGroup): ViewGroup.LayoutParams? {
        // The row's OWN params class first: a container that validates its children (Douyin's
        // ImAutoLayerTabLayout does, and it crashed the host when given foreign params) accepts only
        // its own type, and that type may not be a LinearLayout.LayoutParams at all.
        val own = defaultLayoutParamsOf(row)
        return try {
            when {
                own is LinearLayout.LayoutParams -> {
                    own.width = 0
                    own.height = ViewGroup.LayoutParams.MATCH_PARENT
                    own.weight = 1f
                    own
                }
                own != null -> {
                    own.width = 0
                    own.height = ViewGroup.LayoutParams.MATCH_PARENT
                    own
                }
                else -> null
            }
        } catch (_: Throwable) {
            own
        }
    }

    /**
     * A fresh layoutParams instance of the same class and size as [view]'s, or null.
     *
     * Constructed through the class's own (Context, AttributeSet) constructor when it has one, which
     * is the constructor every `LayoutParams` subclass used from XML must provide. Sharing the
     * sibling's actual instance is not an option: Android forbids two views holding one
     * LayoutParams, and the second view would silently lose its layout.
     */
    private fun copyLayoutParamsOf(view: View): ViewGroup.LayoutParams? {
        // A sibling whose params were never set is a NORMAL state here, not an error: `getLayoutParams`
        // may legitimately return null before the row has been through a layout pass, and the caller
        // handles a null answer. Returning early keeps the reflective work below from running on a
        // known-impossible input.
        val src = view.layoutParams ?: return null
        val made = try {
            val ctor = src.javaClass.getConstructor(
                android.content.Context::class.java,
                android.util.AttributeSet::class.java,
            )
            ctor.newInstance(view.context, null) as? ViewGroup.LayoutParams
        } catch (_: Throwable) {
            null
        }
        val out = made ?: try {
            src.javaClass.getConstructor().newInstance() as? ViewGroup.LayoutParams
        } catch (_: Throwable) {
            null
        } ?: return null
        try {
            out.width = src.width
            out.height = src.height
            if (out is LinearLayout.LayoutParams && src is LinearLayout.LayoutParams) {
                out.weight = src.weight
            }
        } catch (_: Throwable) {
            // the class may refuse direct sizing; the constructed defaults stand
        }
        return out
    }

    /** The analysis icon as a Drawable, for a cloned TextView's compound drawable. */
    private fun iconDrawable(ctx: Context, sizePx: Int): android.graphics.drawable.Drawable? = try {
        com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, sizePx)
    } catch (t: Throwable) {
        Log.w(TAG, "douyin: the icon could not be built: " + t.message)
        null
    }

    /**
     * The current Activity, found the same way MenuHook finds it.
     *
     * Needed because the analysis UI is a dialog and must be shown by an Activity, not by a View's
     * context (which may be a ContextWrapper with no window).
     */
    private fun currentActivityOf(): android.app.Activity? = try {
        val at = Class.forName("android.app.ActivityThread", false, resolver.classLoader())
        val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
        val f = at.getDeclaredField("mActivities")
        f.isAccessible = true
        /* ★★ v9.9.9 关键修复：按 **Map** 接收，不能按 HashMap ——
           Android 17 上该字段的实际类型是 android.util.ArrayMap，
           `as? HashMap` 会静默返回 null，导致本方法永远取不到 Activity。
           详见 UpdateDialog.hostIsForeground 里的完整说明。 */
        val entries = (f.get(thread) as? java.util.Map<*, *>)?.values()
        entries?.firstNotNullOfOrNull { rec ->
            try {
                val g = rec!!.javaClass.getDeclaredField("activity")
                g.isAccessible = true
                g.get(rec) as? android.app.Activity
            } catch (_: Throwable) {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * The Activity behind a View's context, walking the ContextWrapper chain.
     *
     * This is the reliable route at TAP time, and it exists because the reflection route above
     * returned null during the multi-select scan -- a dialog cannot be shown without a real Activity,
     * and a View always knows its own, so this is tried FIRST and the reflection route is the backup.
     */
    private fun activityOf(ctx: Context?): android.app.Activity? {
        var c = ctx
        var depth = 0
        while (c != null && depth < 10) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    /**
     * Handles a tap on the multi-select 分析 button: reads the SELECTED messages and analyses them.
     *
     * ======================== THE SELECTION API (reverse-engineered, then used) ==================
     *
     * Unlike `getCurrentMsg()` on the holder -- which turned out not to exist on the chat page's
     * holder family -- this path was verified BEFORE being written, and it is the SAME call Douyin's
     * own toolbar buttons make:
     *
     *     MultiChooseFuncUI.LJZL()  ->  ListLogicApi
     *     ListLogicApi.getSelectedData()  ->  LiveData, whose value is a Set<Message>
     *
     * The method names are NOT obfuscated, so they are looked up by NAME; `LJZL()` is obfuscated, so
     * it is found by SHAPE (no args, returns the interface `ListLogicApi`).
     *
     * WHY THIS REPLACES THE WECHAT-STYLE APPROACH: WeChat's version hooks a toolbar "rebuild" method
     * and hijacks its button list. Douyin has no such method -- its buttons are static XML and the
     * class never calls addView -- so the selection is read on demand at tap time instead.
     * =============================================================================================
     */
    private fun onMultiSelectAnalyseClicked(v: View, row: ViewGroup) {
        try {
            Log.i(TAG, "douyin MULTISELECT: 分析 tapped")
            val activity = activityOf(v.context) ?: currentActivityOf() ?: run {
                Log.w(TAG, "douyin MULTISELECT: no Activity; cannot show the analysis dialog")
                return
            }

            val selected = readSelectedMessages()
            Log.i(TAG, "douyin MULTISELECT: selected=" + selected.size)
            if (selected.isEmpty()) {
                Toast.makeText(
                    v.context,
                    HostApp.current.say("\u8bf7\u5148\u52fe\u9009\u6d88\u606f"),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }

            // Rendered with the module's own formatting, then handed to the read UI -- exactly the
            // WeChat flow, so the user sees the same dialog and the same 删除 choices on both hosts.
            val described = controller.describeMessages(selected)
            // v6.0.0: the template's words (which include the built-in markers), not the hardcoded three,
            // so a word the user added works on Douyin too. Same bug as WeChat's menu path.
            val words = controller.templateBlockWords()
            val lines = described.filterNot {
                com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(it) ||
                    com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(it, words)
            }
            if (lines.isEmpty()) {
                Toast.makeText(
                    v.context,
                    HostApp.current.say("\u9009\u4e2d\u7684\u6d88\u606f\u6ca1\u6709\u6587\u672c"),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }

            com.luoliaoo.wechat.ui.ScopeDialog.showReadOnly(
                activity,
                lines,
                HostApp.current.say("\u5df2\u9009\u6d88\u606f"),
            ) { kept, background ->
                controller.startAnalysisForSelection(
                    activity,
                    kept,
                    background,
                    words,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "douyin MULTISELECT analysis failed: " + t.message, t)
        }
    }

    /**
     * The messages Douyin currently has selected, or an empty list.
     *
     * Walks the exact chain Douyin's own buttons use: the multi-select host object -> the list API ->
     * `getSelectedData()` -> the LiveData's value -> the `Set<Message>` inside it.
     *
     * Every step is logged on failure, because there are four places it can break and each needs a
     * different fix -- whereas a silent empty list would look like "the user selected nothing".
     */
    private fun readSelectedMessages(): List<Any> {
        val host = multiSelectHost ?: run {
            Log.w(TAG, "douyin MULTISELECT: the host object was not captured; cannot read selection")
            return emptyList()
        }
        return try {
            val api = listLogicApiOf(host) ?: run {
                Log.w(TAG, "douyin MULTISELECT: the list API was not reachable from the host")
                return emptyList()
            }
            val getSelected = api.javaClass.methods.firstOrNull {
                it.name == "getSelectedData" && it.parameterCount == 0
            } ?: run {
                Log.w(TAG, "douyin MULTISELECT: the list API has no getSelectedData()")
                return emptyList()
            }
            val live = getSelected.invoke(api) ?: run {
                Log.w(TAG, "douyin MULTISELECT: getSelectedData() returned null")
                return emptyList()
            }
            // LiveData.getValue() is the synchronous read; the analysis runs on a tap, when the value
            // is already set, so observing would add a thread hop for no benefit.
            val value = try {
                live.javaClass.getMethod("getValue").invoke(live)
            } catch (t: Throwable) {
                Log.w(TAG, "douyin MULTISELECT: reading the LiveData value failed: " + t.message)
                null
            } ?: run {
                Log.w(TAG, "douyin MULTISELECT: the selection LiveData has no value yet")
                return emptyList()
            }
            // Reverse engineering found the payload is a Set<Message>; a Collection test covers Set and
            // List both, so a future change from Set to List does not break this.
            val out = ArrayList<Any>()
            if (value is Collection<*>) {
                for (m in value) {
                    if (m != null) out.add(m)
                }
            } else {
                Log.w(
                    TAG,
                    "douyin MULTISELECT: the selection payload is " +
                        value.javaClass.name + ", not a Collection",
                )
            }
            Log.i(
                TAG,
                "douyin MULTISELECT: selection type=" +
                    value.javaClass.simpleName + " size=" + out.size,
            )
            out
        } catch (t: Throwable) {
            Log.w(TAG, "douyin MULTISELECT: reading the selection failed: " + t.message)
            emptyList()
        }
    }

    /**
     * The `ListLogicApi` behind the multi-select host, or null.
     *
     * Tries the obfuscated accessor `LJZL()` by SHAPE first (no args, return type named ListLogicApi),
     * then any declared method whose return type is that interface -- so a renamed accessor still
     * resolves. Reading a FIELD of that type is the last resort, for a build that stores rather than
     * exposes it.
     */
    private fun listLogicApiOf(host: Any): Any? {
        var c: Class<*>? = host.javaClass
        var depth = 0
        while (c != null && depth < 5) {
            for (m in c.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!m.returnType.name.endsWith("ListLogicApi")) continue
                try {
                    m.isAccessible = true
                    (m.invoke(host))?.let { return it }
                } catch (_: Throwable) {
                    // try the next candidate
                }
            }
            for (f in c.declaredFields) {
                if (!f.type.name.endsWith("ListLogicApi")) continue
                try {
                    f.isAccessible = true
                    (f.get(host))?.let { return it }
                } catch (_: Throwable) {
                    // try the next candidate
                }
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /**
     * The message behind a view holder, or null.
     *
     * `getCurrentMsg()` is looked up by NAME up the class hierarchy: the holder belongs to the
     * BasicViewHolder family and the message FIELD is obfuscated, while this accessor kept its name.
     *
     * KEPT AS A FALLBACK ONLY. The device proved the chat page's holders are in a DIFFERENT family
     * (`MsgCellComposeViewHolder <- CellComposeViewHolder <- SplitViewHolder`) where this accessor does
     * not exist, which is why [messageAt] is now tried first.
     */
    private fun messageOf(holder: Any): Any? {
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && depth < 6) {
            try {
                val m = c.getDeclaredMethod("getCurrentMsg")
                m.isAccessible = true
                return m.invoke(holder)
            } catch (_: Throwable) {
                // the accessor may live on a superclass
            }
            c = c.superclass
            depth++
        }
        return null
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "douyin " + name + " FAILED: " + t.javaClass.simpleName + ": " + t.message, t)
        }
    }

    /**
     * Reports whether the message class is loadable, and its shape.
     *
     * This is the FIRST line to read when Douyin yields an empty analysis: it proves whether the
     * module is looking at the message model the dex documented, as opposed to the hook having
     * missed. Every getter listed here was confirmed present in 39.9.0.
     */
    private fun logMessageModel() {
        val cls = loadClass(MESSAGE) ?: run {
            Log.e(
                TAG,
                "douyin: message class NOT loadable (" + MESSAGE + ") -- analysis cannot work; " +
                    "the model moved in this Douyin version",
            )
            return
        }
        val wanted = listOf(
            "getContent", "getConversationId", "isSelf", "getMsgType",
            "getCreatedAt", "getMsgId", "getSender", "getSecSender", "isRecalled",
        )
        val present = ArrayList<String>(wanted.size)
        val missing = ArrayList<String>(0)
        for (n in wanted) {
            if (cls.methods.any { it.name == n && it.parameterCount == 0 }) {
                present.add(n)
            } else {
                missing.add(n)
            }
        }
        Log.i(
            TAG,
            "douyin message model: " + cls.name +
                " present=" + present.joinToString(",") +
                " missing=" + (if (missing.isEmpty()) "none" else missing.joinToString(",")),
        )
        if (missing.contains("getContent")) {
            Log.e(TAG, "douyin: getContent() MISSING -- analysis will produce empty results")
        }
    }

    /**
     * Records which class Douyin's chat input box is.
     *
     * `com.ss.android.ugc.aweme.im.sdk.chat.view.RichEditText` was confirmed from the dex. This
     * module does NOT need the name to READ the box -- `InputInjector` walks the view tree for the
     * lowest visible EditText, which is host-independent and already works in Douyin (the balloon's
     * text polish is callable there). It is logged so that a future failure has the answer ready:
     * if the tree walk ever picks the wrong field, this is the class to match on instead.
     */
    private fun logInputBox() {
        val cls = loadClass("com.ss.android.ugc.aweme.im.sdk.chat.view.RichEditText")
        Log.i(
            TAG,
            "douyin chat input box: " + (cls?.name ?: "RichEditText NOT loadable") +
                " (InputInjector locates it by view tree, not by name)",
        )
    }

    /**
     * Hooks the menu's show method and injects our row once the window is up.
     *
     * The hook is installed on `LIZIZ` because:
     *   * it is the single show path (both the `LIZ` overloads are empty stubs),
     *   * its parameter 2 is the `Message`, so the row we inject can analyse the right message,
     *   * it returns void and we call `proceed()` FIRST, so Douyin's own menu is built exactly as
     *     it would have been and our row is appended to a finished tree.
     *
     * Failure to find the method is NOT fatal: it is logged as an E line naming the class, which is
     * the signal that Douyin renamed it in an update.
     */
    private fun installMenuWindow() {
        val cls = loadClass(WINDOW_MGR) ?: run {
            Log.e(
                TAG,
                "douyin: " + WINDOW_MGR + " not loadable -- the long-press menu hook is OFF " +
                    "(analysis cannot be started from the menu; the balloon still works)",
            )
            return
        }

        // The show method is identifiable by its SHAPE, not its name: it is obfuscated, and it takes
        // (View, <iface>, Message, <content>, ...).
        //
        // ======================== WHY THE PARAMETER COUNT IS NOT FIXED ========================
        // This used to require EXACTLY 7 parameters, and that is precisely what broke on Douyin
        // 40.5.0: the show method gained a trailing `android.graphics.Rect` and became 8. The hook
        // silently matched nothing, the menu was never intercepted, and the 分析 button simply
        // disappeared -- with no crash and no error, because a hook that is never installed cannot
        // report anything.
        //
        // The signature is now matched on its STABLE PREFIX -- a View first and a Message third --
        // and the extra trailing parameter is allowed. Douyin has now changed this signature once,
        // and the two facts that identify it (it takes the anchor view, and it takes the message)
        // are the ones that carry meaning; the count was only ever an artifact of one build.
        //
        // The LAST matching candidate wins, because when a class carries several overloads the
        // WIDEST one is the current path and the narrower ones are legacy wrappers. On 40.5.0 that
        // picks `LJIIIIZZ` (8 params) over the older `LIZIZ` (8 too, but with a lambda-subclass
        // Function0) -- see the ambiguity note below.
        // =====================================================================================
        val candidates = cls.declaredMethods.filter { m ->
            m.parameterCount >= 3 &&
                View::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[2].name == MESSAGE
        }
        // Among the candidates, prefer one whose declared types include a REAL `Function0` -- the
        // Kotlin function interface, which only the live show path uses. The older overload takes a
        // generated lambda SUBCLASS instead, and hooking that would intercept a path Douyin no longer
        // calls. This is a preference, not a requirement, so a build without it still gets a hook.
        val show = candidates.lastOrNull { m ->
            m.parameterTypes.any { it.name == "kotlin.jvm.functions.Function0" }
        } ?: candidates.lastOrNull()
        if (show == null) {
            val sig = cls.declaredMethods.joinToString("\n    ") { m ->
                m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } +
                    ":" + m.returnType.simpleName
            }
            Log.e(
                TAG,
                "douyin: no (View,?,Message,?,?,Function0,?) method on " + WINDOW_MGR +
                    " -- menu hook OFF. Actual methods:\n    " + sig,
            )
            return
        }

        try {
            LegacyHook.hook(show).intercept { chain ->
                // Let Douyin build and show its menu FIRST, so our row is appended to a tree that
                // already exists and already has its final measured width.
                val result = chain.proceed()
                try {
                    val self = chain.thisObject
                    val msg = chain.args.getOrNull(2)
                    if (msg != null) {
                        onMenuShown(self, msg)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "douyin: menu injection failed: " + t.message)
                }
                result
            }
            menuHooked = true
            Log.i(
                TAG,
                "douyin: hooked the menu show path (" + show.name +
                    show.parameterTypes.joinToString(",", "(", ")") { it.simpleName } + ")",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "douyin: hooking the menu show path failed: " + t.message, t)
        }
    }

    /**
     * Given the manager that just showed its menu, find the PopupWindow and add our button.
     *
     * The window is reached through the manager's own `LJII()` accessor rather than a field, so the
     * obfuscated FIELD name does not matter. Its name IS obfuscated, so it is found by shape:
     * a no-arg method returning PopupWindow.
     *
     * ---------------------------------------------------------------------------------------------
     * THE "SECOND LONG PRESS SHOWS NOTHING" BUG (reported by the user, fixed here)
     *
     * Symptom: the first long press showed 分析; the second long press showed nothing; leaving the
     * chat and coming back restored it.
     *
     * Cause: this method used to guard with
     *
     *     if (injectedWindows.contains(win)) return
     *
     * which skips injection for a window instance we have already handled. Douyin REUSES one
     * PopupWindow object for the whole chat screen while REBUILDING its content view on each open --
     * so our button (a child of the OLD content) was gone, but the guard still said "already done",
     * and the menu reopened without it. Switching conversations created a different window object,
     * which is why that appeared to fix it.
     *
     * The fix is to stop trusting the window's IDENTITY and check the actual question instead:
     * "is our button IN the tree right now?" -- see [alreadyHasOurButton], called after the content
     * is resolved. The window set is kept only to avoid unbounded growth, not as a gate.
     * ---------------------------------------------------------------------------------------------
     */
    private fun onMenuShown(mgr: Any, msg: Any) {
        val win = popupWindowOf(mgr) ?: run {
            Log.w(TAG, "douyin: the menu manager exposed no PopupWindow; button not injected")
            return
        }
        val content = win.contentView ?: run {
            Log.w(TAG, "douyin: the menu PopupWindow has no contentView yet; button not injected")
            return
        }
        // Remember the window purely to keep the set from holding objects forever. It is NOT used to
        // decide whether to inject -- see the class comment above.
        injectedWindows.add(win)

        // The content is inflated on the show frame, so poll briefly instead of assuming it is
        // already attached -- a slow first open otherwise loses the button with no retry.
        val ctx = content.context
        pollForRow(ctx, content, msg, 0)
    }

    /**
     * True when a view tagged [TAG_OUR_ROW] already exists under [root].
     *
     * This is the REAL idempotency check, replacing the window-identity one that caused the
     * "second long press loses the button" bug. It asks the only question that matters after Douyin
     * rebuilds its menu: is our entry present in THIS tree? A rebuilt tree answers no, and the
     * button is re-added.
     *
     * BOTH of our tags count, so a menu that somehow kept only one entry is still recognised as
     * "already injected" and never gets a duplicate -- re-adding on top of an existing pair would
     * show the user two 分析 buttons.
     */
    private fun alreadyHasOurButton(root: View): Boolean {
        var found = false
        fun walk(v: View, depth: Int) {
            if (depth > 24 || found) return
            val t = v.tag
            if (TAG_OUR_ROW == t || TAG_BALLOON_ROW == t) {
                found = true
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    /**
     * True when our button is already somewhere in the SAME WINDOW as [content].
     *
     * ======================== WHY THE TREE CHECK WAS NOT ENOUGH (device-verified) ================
     *
     * [alreadyHasOurButton] asks "is our button in THIS tree?", which fixed the "second long press
     * loses the button" bug but left a visible defect: the button appeared TWICE, side by side with
     * its twin (`分析 浮窗 分析 浮窗`), both genuinely rendered at identical y coordinates.
     *
     * The device log showed the cause directly -- two injections 61 ms apart, into what the code saw
     * as two different trees:
     *
     *     03:26:57.571  injected ... children=9     (7 native + our 2)
     *     03:26:57.631  injected ... children=11    (7 native + our 4)
     *
     * Douyin rebuilds the menu's contentView on the show frame, and the callback fires once per
     * instance. Each tree genuinely had no tagged button, so each passed the per-tree check, and both
     * ended up in the window. The question that actually needs asking is therefore about the WINDOW,
     * not the subtree: this walks UP to the topmost ancestor and checks there, so a second content
     * instance in the same window is seen as "already done".
     * =============================================================================================
     */
    private fun windowAlreadyHasOurButton(content: View): Boolean {
        var top: View = content
        var guard = 0
        while (guard < 40) {
            val p = top.parent
            if (p !is View) break
            top = p
            guard++
        }
        if (alreadyHasOurButton(top)) return true
        // The two content instances seen on the device were SIBLINGS under a common parent rather than
        // one nested in the other, so the direct ancestor chain alone can miss the twin. The content's
        // immediate parent is therefore checked too -- that is the shallowest node both instances
        // must share.
        val parent = content.parent
        if (parent is View && alreadyHasOurButton(parent)) return true
        return false
    }

    /**
     * When the last injection happened, and for which window.
     *
     * A TIME GUARD in addition to the window scan above, because the device showed the two injections
     * landing 61 ms apart: a window that is mid-rebuild can briefly hold both the old and the new
     * content, and during that frame neither the window nor the parent scan necessarily sees a
     * sibling that is about to be removed. Refusing a second injection inside
     * [INJECT_GUARD_MS] for the same window closes that window of opportunity, and cannot
     * suppress a legitimate injection because a real reopen is far slower than the rebuild frame.
     */
    @Volatile
    private var lastInjectAt = 0L

    @Volatile
    private var lastInjectWindow: Any? = null

    /** True when this window was injected into so recently that a repeat must be a rebuild artifact. */
    private fun injectedTooRecently(win: Any?): Boolean {
        val since = System.currentTimeMillis() - lastInjectAt
        return win != null && win === lastInjectWindow && since in 0 until INJECT_GUARD_MS
    }

    /**
     * The window that owns [v], or null.
     *
     * Obtained by walking to the topmost ancestor and reading its `getWindowToken()`, because the
     * duplicate-injection guard must be scoped to ONE window: two genuinely different windows (a
     * re-opened menu in a new conversation) must each be allowed their own injection.
     */
    private fun currentWindowOf(v: View): Any? = try {
        var top: View = v
        var guard = 0
        while (guard < 40) {
            val p = top.parent
            if (p !is View) break
            top = p
            guard++
        }
        top.windowToken ?: top
    } catch (_: Throwable) {
        null
    }

    /** Records a successful injection, for [injectedTooRecently]. */
    private fun noteInjected(win: Any?) {
        lastInjectAt = System.currentTimeMillis()
        lastInjectWindow = win
    }

    private fun pollForRow(ctx: Context, content: View, msg: Any, attempt: Int) {
        val anchor = findRowAnchor(content)
        // WAIT FOR A REAL LAYOUT before touching anything.
        //
        // The device dump showed every view at 0x0 on the first frames, and the first version
        // injected at that moment: the button was added to a tree that had not been measured, so it
        // took a full-width line instead of sharing the item row. Re-polling until the anchor is
        // actually sized is what makes the neighbouring items' measurements available to copy.
        val laidOut = anchor != null && anchor.width > 0
        if (anchor != null && laidOut) {
            // THE IDEMPOTENCY CHECK: ask whether our button is in THIS tree, every single time.
            //
            // This replaces a window-identity guard that made the second long press lose the button
            // (Douyin reuses one PopupWindow but rebuilds its content; the guard said "already
            // injected" while the button had been discarded with the old content). Checking the tree
            // is self-correcting: a rebuilt menu simply does not contain our tag, so it is re-added.
            if (alreadyHasOurButton(content)) {
                Log.i(TAG, "douyin: the menu already shows our button; nothing to do")
                return
            }
            // THE WINDOW-LEVEL CHECK, added after the device showed the button TWICE side by side.
            // Douyin rebuilds the menu content on the show frame, so two callbacks 61 ms apart each
            // saw a tree with no tagged button and each injected; both survived in the window. Asking
            // about the whole window (and refusing a repeat within the rebuild window) is what stops
            // the duplicate, while the per-tree check above stays as the cheap first test.
            if (windowAlreadyHasOurButton(content)) {
                Log.i(
                    TAG,
                    "douyin: our button is already present in this window (a rebuilt content " +
                        "view); not injecting a duplicate",
                )
                noteInjected(currentWindowOf(content))
                return
            }
            if (injectedTooRecently(currentWindowOf(content))) {
                Log.i(
                    TAG,
                    "douyin: injected moments ago into this window; skipping the rebuild frame",
                )
                return
            }
            if (!treeDumped) {
                treeDumped = true
                val sb = StringBuilder()
                dumpTree(content, sb, 0)
                Log.i(
                    TAG,
                    "douyin menu tree (anchor=" + anchor.javaClass.simpleName +
                        " " + anchor.width + "x" + anchor.height +
                        " children=" + anchor.childCount +
                        " via=" + (matchedLabel?.let { "label '" + it + "'" } ?: "shape") +
                        "):\n" + sb,
                )
            }
            injectRow(anchor, msg)
            noteInjected(currentWindowOf(content))
            return
        }
        if (attempt >= SCAN_TRIES) {
            if (anchor != null) {
                // The tree exists but never got measured. Inject anyway rather than lose the entry,
                // and say so -- a button that is present but mis-sized beats no button at all.
                //
                // ================= THE GUARDS ARE REPEATED HERE ON PURPOSE =================
                // A review caught this branch calling injectRow with NO guard at all: it is reached
                // after the loop gives up, which can happen on a later callback for the SAME window,
                // so it could add a second button exactly like the path that produced the duplicate
                // pair on the device. The check is repeated rather than hoisted because this is a
                // different exit -- a reviewer must be able to see it is guarded without tracing back
                // through the loop above.
                // =========================================================================
                if (alreadyHasOurButton(content) || windowAlreadyHasOurButton(content)) {
                    Log.i(
                        TAG,
                        "douyin: our button is already present; the never-measured branch " +
                            "injects nothing",
                    )
                    return
                }
                if (injectedTooRecently(currentWindowOf(content))) {
                    Log.i(
                        TAG,
                        "douyin: injected moments ago into this window; the never-measured " +
                            "branch injects nothing",
                    )
                    return
                }
                Log.w(
                    TAG,
                    "douyin: the menu never reported a width after " + attempt +
                        " tries; injecting into " + anchor.javaClass.simpleName + " anyway",
                )
                injectRow(anchor, msg)
                // Recorded so a later callback for the same window is refused -- the guard above
                // reads this, and without the matching write it would never fire.
                noteInjected(currentWindowOf(content))
            } else {
                val sb = StringBuilder()
                dumpTree(content, sb, 0)
                Log.w(
                    TAG,
                    "douyin: our button was NOT injected -- no container in the menu. Tree:\n" + sb,
                )
            }
            return
        }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    pollForRow(ctx, content, msg, attempt + 1)
                } catch (_: Throwable) {
                    // the window may have been dismissed while we waited
                }
            }, SCAN_INTERVAL_MS)
        } catch (_: Throwable) {
            // posting failed; the row is simply not added
        }
    }

    /**
     * True when [text] is one of Douyin's own menu labels.
     *
     * Case-INSENSITIVE, because the list now carries English words for TikTok and other non-Chinese
     * hosts, and menu capitalisation is not stable across Android versions or Douyin builds. For the
     * Chinese entries this changes nothing -- they have no case -- so one test serves both.
     *
     * A cheap first pass by exact match keeps the common (Chinese) path allocation-free; the
     * case-insensitive scan only runs on a miss.
     */
    private fun isMenuLabel(text: String): Boolean {
        if (MENU_LABELS.contains(text)) return true
        for (l in MENU_LABELS) {
            if (l.equals(text, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * The containers to append our button to, best candidate first.
     *
     * ---------------------------------------------------------------------------------------------
     * HISTORY: THREE wrong guesses, each corrected from device evidence. Keeping them recorded,
     * because each one looked reasonable on paper.
     *
     *   v1: "the deepest vertical LinearLayout" -> the button landed on its own line.
     *   v2: "a horizontal LinearLayout with >1 child" -> still wrong.
     *   v3: "prefer the RecyclerView's parent" -> still wrong.
     *
     * The device tree dump finally showed WHY all three failed: at the moment the menu is shown the
     * item list is EMPTY and every view is 0x0, so a shape rule had nothing meaningful to match and
     * settled on whatever happened to fit.
     *
     * ---------------------------------------------------------------------------------------------
     * A CORRECTION TO THE ORIGINAL EXPLANATION, because the first version of this comment named the
     * wrong culprit. It claimed the vertical column below was the EMOJI panel and that
     * `ImAutoLayerTabLayout` was an emoji tab strip. DISASSEMBLY RETRACTED THAT: Douyin constructs
     * the params and addViews the menu items into `ImAutoLayerTabLayout` itself, so that class IS the
     * long-press menu's item row -- confirmed independently by a device dump in which all six native
     * items (回复 复制 转发 多选 删除 举报) had identical ancestors and the container measured 1050px
     * wide with six 175x210 children.
     *
     * The METHODOLOGICAL conclusion is what survives, and it is the same either way: you cannot tell
     * what a container IS by guessing from its shape. Establish it from evidence inside it.
     * ---------------------------------------------------------------------------------------------
     * THE FIX: stop guessing by SHAPE and find the items by their own LABELS.
     *
     * Douyin's menu labels live in `resources.arsc` (not the dex -- a reported trap), so they cannot
     * be searched statically. But at RUNTIME the TextView displays them, so the row can be located
     * by walking the tree for a TextView whose text IS a known menu label and taking its PARENT --
     * the real button row, whatever its class or orientation happens to be.
     *
     * That is self-verifying: if a label matches, the parent demonstrably holds menu items, which is
     * a far stronger statement than "this container is horizontal".
     *
     * Order, most reliable first:
     *   1. [findItemRowByLabel] -- the row that shows several menu labels together;
     *   2. a HORIZONTAL LinearLayout that already has children (a real button row);
     *   3. the last vertical container with children.
     *
     * ============================ "REFUSED" IS NOT "NOT FOUND" ==============================
     *
     * A REVIEW CAUGHT A REAL GAP HERE, and it is worth spelling out because it silently
     * re-enabled the crash path.
     *
     * The label search has TWO different empty outcomes:
     *   * NOT FOUND -- no label matched at all. The menu is shaped in a way this method does not
     *     recognise, so falling through to the shape-based anchors is a reasonable guess.
     *   * REFUSED -- labels matched, but no single container showed enough of them TOGETHER, so the
     *     search deliberately declined to inject (see [MIN_LABELS_IN_ROW]). This is a POSITIVE
     *     finding: the evidence was contradictory, so no container can be justified.
     *
     * Both used to `return null`, and the null then fell through to the shape fallback, which has no
     * evidence behind it at all. The careful refusal was therefore undone one line later.
     * `refusedByLabel` carries the distinction so a REFUSED outcome stops here and injects nothing.
     *
     * The measure verification in [injectRow] is still the backstop; this just stops relying on it.
     * ========================================================================================
     */
    private fun findRowAnchor(root: View): ViewGroup? {
        refusedByLabel = false
        findItemRowByLabel(root)?.let { return it }
        if (refusedByLabel) {
            Log.i(
                TAG,
                "douyin: the label search refused this menu; skipping the shape fallback too " +
                    "(a shape guess here has no evidence behind it). " +
                    "No button in this menu -- the balloon is unaffected.",
            )
            return null
        }

        var horizFilled: ViewGroup? = null
        var vertFallback: ViewGroup? = null
        var vertDepth = -1

        fun walk(v: View, depth: Int) {
            if (depth > 24) return
            if (v is ViewGroup) {
                val horizontal = v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL
                if (horizontal && v.childCount > 0 && horizFilled == null) horizFilled = v
                if (!horizontal && v.childCount > 0 && depth > vertDepth) {
                    vertDepth = depth
                    vertFallback = v
                }
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)

        return horizFilled ?: vertFallback
    }

    /**
     * Finds the menu's button row by locating a TextView that displays one of Douyin's OWN menu
     * labels, then returning that TextView's parent.
     *
     * WHY THE PARENT AND NOT THE TextView ITSELF: Douyin wraps each label in a clickable item view
     * that carries the touch target and the weight. Appending to the label's parent therefore joins
     * the row as an equal sibling, which is the whole point of this method.
     *
     * WHY THIS SURVIVES A DOUYIN UPDATE: it keys on the LABEL THE USER SEES, not on any class,
     * id, orientation or index. If Douyin renames every class and re-lays out the menu, a row still
     * showing 复制 and 转发 is still found.
     *
     * Also returns the matched label so the log records the evidence (see [injectRow]).
     */
    private fun findItemRowByLabel(root: View): ViewGroup? {
        // COLLECT EVERY LABEL, not the first one.
        //
        // -----------------------------------------------------------------------------------------
        // WHY A SINGLE LABEL IS NOT ENOUGH:
        //
        // The first version of this method stopped at the FIRST match and searched a list that
        // included 回复. That single-label test is what this co-occurrence requirement replaced.
        //
        // ============================== THE ORIGINAL STORY WAS WRONG ==============================
        // This comment used to claim the search "locked onto the EMOJI panel, climbed to
        // `ImAutoLayerTabLayout`, and injecting there killed the app". DISASSEMBLY RETRACTED THAT:
        // `回复` is the menu's OWN first button, and `ImAutoLayerTabLayout` IS the menu row -- Douyin
        // constructs the params and addViews the menu items into it itself.
        //
        // So the single-label search had actually climbed to the CORRECT container. The crash was
        // caused by the LAYOUTPARAMS TYPE being wrong for that container, and had nothing to do with
        // the label being weak. Two independent facts had been fused into one false story.
        //
        // WHY THE COUNTER STAYS ANYWAY: it is still the right rule, for its own reason -- one menu
        // word is weak evidence about what a container IS, and this method must not stake a crash on
        // it. Keeping the rule while fixing the story matters, because the false version invites the
        // WRONG NEXT CONCLUSION: "labels are unreliable, so identify the container by class name".
        // That conclusion is a trap -- labels are read from the live tree in the user's own language
        // and survive renames, whereas class names are obfuscated. The rule guards against weak
        // evidence; it does not mean the evidence is bad.
        //
        // The genuine lesson: ONE label is weak evidence. A message long-press menu always shows
        // SEVERAL of its labels TOGETHER (复制 + 转发 + 多选 + 删除 ...), so requiring CO-OCCURRENCE
        // inside one container distinguishes the real menu from any panel that happens to contain one
        // word.
        // -----------------------------------------------------------------------------------------
        val parents = LinkedHashMap<ViewGroup, MutableSet<String>>()

        fun note(label: String, v: View) {
            // Record the label against every ancestor in reach, with a count of distinct labels
            // seen under each. The real row accumulates several; a stray 回复 elsewhere accumulates
            // one.
            //
            // THREE levels, not four. A review pointed out that an over-wide climb lets a SHARED
            // ANCESTOR accumulate the labels of BOTH subtrees, so it can out-score the actual row --
            // and the ranking below would then pick that ancestor. Narrowing the reach makes that
            // much less likely, and the depth tie-break below closes the remaining case.
            var p: android.view.ViewParent? = v.parent
            var level = 0
            while (p is ViewGroup && level < 3) {
                parents.getOrPut(p) { LinkedHashSet() }.add(label)
                p = p.parent
                level++
            }
        }

        fun walk(v: View, depth: Int) {
            if (depth > 24) return
            if (v is android.widget.TextView && v.text != null) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty() && isMenuLabel(t)) note(t, v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)

        if (parents.isEmpty()) return null

        val picked = pickLabelRow(parents, MIN_LABELS_IN_ROW)
        if (picked == null) {
            // REFUSED, not "not found": labels were present but never enough together. Flag it so
            // findRowAnchor does not fall through to the shape guesses, which carry no evidence at
            // all. Log what WAS seen so a re-worded menu stays diagnosable.
            refusedByLabel = true
            val seen = parents.entries
                .sortedByDescending { it.value.size }
                .take(3)
                .joinToString(" | ") { e ->
                    e.key.javaClass.simpleName + "=" + e.value.joinToString(",")
                }
            Log.w(
                TAG,
                "douyin: no container showed " + MIN_LABELS_IN_ROW + " menu labels together " +
                    "(menu labels are " + MENU_LABELS.size + " known); NOT injecting, because a " +
                    "shape guess here has no evidence behind it. Strongest candidates: " + seen,
            )
            return null
        }
        val (best, bestLabels) = picked

        matchedLabel = bestLabels.joinToString(",")
        Log.i(
            TAG,
            "douyin: item row = " + best.javaClass.simpleName +
                " with " + bestLabels.size + " labels (" + bestLabels.joinToString(",") + ")",
        )
        return best
    }

    /**
     * Chooses the row container from per-container label evidence: the best candidate and its labels.
     *
     * ============================ ONE RANKING, USED BY BOTH PATHS ==============================
     *
     * The long-press menu and the multi-select toolbar each used to carry their OWN copy of this
     * ranking, and they DRIFTED: a review found the menu path had been narrowed to a 3-level ancestor
     * climb while the toolbar path was still at 4, so the same tree was judged differently depending
     * on which feature was running. Extracting it means the rule exists once and cannot diverge.
     *
     * THE RULE, in order:
     *   1. most DISTINCT labels wins -- that is the positive evidence, and [min] is the threshold
     *      below which a container is not considered the row at all;
     *   2. on a tie, the DEEPER container wins. An ancestor that contains the real row also sees all
     *      of the row's labels, so on count alone the two tie and the ancestor could win by iteration
     *      order; the deepest of the tied containers is the row itself.
     *
     * WHY STEP 2 IS ONLY A TIE-BREAK, and its residual risk: because label count dominates, a future
     * tree in which some OTHER subtree contributes one additional distinct menu label would let an
     * ancestor win outright at N+1 vs N, with the depth rule never consulted. The deeper fix would be
     * to descend from the best candidate into the deepest child carrying the SAME label set. That is
     * not done here because the current device tree does not need it (verified: parent 7 labels vs
     * row 7 labels -> tie -> the row wins) and every extra rule is another thing to get wrong; it is
     * recorded so the next person meets the idea instead of rediscovering the failure.
     * =========================================================================================
     */
    private fun pickLabelRow(
        parents: Map<ViewGroup, MutableSet<String>>,
        min: Int,
    ): Pair<ViewGroup, Set<String>>? {
        var best: ViewGroup? = null
        var bestScore = -1
        var bestDepth = -1
        var bestLabels: Set<String> = emptySet()
        for ((container, labels) in parents) {
            if (labels.size < min) continue
            var depth = 0
            var p: android.view.ViewParent? = container
            while (p != null && depth < 40) {
                p = p.parent
                depth++
            }
            if (labels.size > bestScore || (labels.size == bestScore && depth > bestDepth)) {
                bestScore = labels.size
                bestDepth = depth
                best = container
                bestLabels = labels
            }
        }
        return best?.let { it to bestLabels }
    }

    private fun describeChildren(v: View): String = try {
        if (v !is ViewGroup) {
            v.javaClass.simpleName + "(" + v.width + "x" + v.height + ")"
        } else {
            (0 until v.childCount).joinToString(",") { i ->
                val c = v.getChildAt(i)
                c.javaClass.simpleName + "(" + c.width + "x" + c.height + ")"
            }
        }
    } catch (_: Throwable) {
        "?"
    }

    /**
     * Dumps the menu's view tree, so a layout that does NOT match the expected shape can be fixed
     * from the log instead of by guessing.
     *
     * This exists because the first implementation DID guess (deepest vertical box) and put the entry
     * on its own line, which is only visible in a screenshot. Class names are obfuscated, so the dump
     * records what is stable: the View class, its orientation, its size and whether it is clickable.
     */
    private fun dumpTree(v: View, sb: StringBuilder, depth: Int) {
        if (depth > 12) return
        val indent = "  ".repeat(depth + 1)
        val orient = if (v is LinearLayout) {
            if (v.orientation == LinearLayout.HORIZONTAL) " [HORIZONTAL]" else " [VERTICAL]"
        } else {
            ""
        }
        val label = if (v is android.widget.TextView) {
            " text='" + v.text + "'"
        } else {
            ""
        }
        // The view's own class name is included because the multi-select tree is diagnosed from this
        // dump alone: obfuscated names (X.1C5O) are still stable identifiers ACROSS one build, which
        // is what a hook needs, and the simple name is what the log can be matched back against.
        sb.append(indent).append(v.javaClass.name)
            .append(orient)
            .append(" ").append(v.width).append('x').append(v.height)
            .append(" children=").append(if (v is ViewGroup) v.childCount else 0)
            .append(if (v.isClickable) " clickable" else "")
            .append(label)
            .append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), sb, depth + 1)
        }
    }

    /**
     * Appends the module's button to the menu's item row and wires it to the analysis.
     *
     * ================================ THE CRASH, AND WHY THIS FILE LOOKS PARANOID ================
     *
     * The first version that found the CORRECT row still took Douyin down. The device log:
     *
     *   FATAL EXCEPTION: main
     *   java.lang.Exception: the layoutParam of child view in ImHadesGroupView must be
     *                        AutoLayerTabLayoutParams!
     *     at ...readedpage.hades.view.ImAutoLayerTabLayout.onMeasure
     *     at android.widget.LinearLayout.measureVertical
     *     at android.view.View.measure
     *
     * Douyin's `ImAutoLayerTabLayout` -- the strip that holds 回复 / 复制 / 转发 / 多选 / 删除 -- is a
     * custom ViewGroup that VALIDATES the runtime class of every child's layoutParams inside its own
     * `onMeasure`, and throws when it is not its inner `AutoLayerTabLayoutParams`. Our plain
     * `LinearLayout.LayoutParams` therefore crashed the app on the next layout pass.
     *
     * TWO CONSEQUENCES, both implemented below:
     *
     *   1. The button must be given layoutParams of the CONTAINER'S OWN type. `generateLayoutParams`
     *      is the public ViewGroup API for exactly this: it builds an instance of whatever class this
     *      container requires. Copying a sibling's instance would share one object between two views.
     *
     *   2. THE CRASH IS *NOT* CATCHABLE AT THE addView SITE. It fires later, in Douyin's own measure
     *      pass, outside any try/catch of ours. So after adding we FORCE A MEASURE inside our own
     *      try/catch and REMOVE the view if it throws -- converting a process kill into a logged,
     *      recovery-able failure. Without this, any future layout Douyin ships that dislikes our
     *      child crashes the host app again, which is unacceptable for a module.
     * =============================================================================================
     *
     * The button is built from plain Android views on purpose -- see the class comment. Its IDENTITY
     * is carried by the view tag, because the menu labels live in resources.arsc and therefore
     * cannot be used to recognise a button.
     */
    private fun injectRow(anchor: ViewGroup, msg: Any) {
        try {
            val ctx = anchor.context
            val density = ctx.resources.displayMetrics.density

            // ---- ICON: the SAME one the WeChat entry uses --------------------------------------
            //
            // The user asked for 「微信分析的同款图标」, so this reuses AnalysisIcon rather than
            // drawing a second icon: one icon means the two hosts cannot drift apart visually, and
            // MenuHook already proves the drawable renders inside a host process (it is drawn with a
            // Path, needing no resource from the module APK -- which matters here for the same reason
            // it matters in WeChat: the module's resources are not reliably reachable from Douyin).
            //
            // Douyin's items are icon-ABOVE-text, matching the 175x210 item boxes measured on the
            // device, so the icon is added first and the label below it.
            val iconSize = (22 * density).toInt()

            // Build ONE button per entry, through a shared factory, so 分析 and 浮窗 cannot drift
            // apart in size, colour or icon handling -- the same reason MenuHook extracted its own
            // addOurItem() for WeChat's two entries.
            val analyseCol = buildMenuButton(
                ctx = ctx,
                title = ROW_TITLE,
                tag = TAG_OUR_ROW,
                iconSize = iconSize,
                onClick = { v ->
                    // Close the menu first, or the analysis panel opens behind it.
                    closeMenu(anchor)
                    onOurRowClicked(v, msg)
                },
            )

            // item NEW (user request 1): the 浮窗 (floating-balloon) on/off entry, matching WeChat.
            //
            // Uses the tag TAG_BALLOON_ROW so [alreadyHasOurButton] recognises it as ours, and a
            // SEPARATE tag from the analysis row because the two must be told apart: one shared tag
            // would make "is our button present?" true for either, and could re-add a duplicate.
            val balloonCol = buildMenuButton(
                ctx = ctx,
                title = BALLOON_TITLE,
                tag = TAG_BALLOON_ROW,
                iconSize = iconSize,
                onClick = { v ->
                    closeMenu(anchor)
                    onBalloonRowClicked(v)
                },
            )
            val col = analyseCol

            // THE LAYOUT PARAMS MUST BE ONE OF THE CONTAINER'S OWN, OR DOUYIN CRASHES.
            //
            // This is not a style preference -- it is a hard crash, observed on the device:
            //
            //   java.lang.Exception: the layoutParam of child view in ImHadesGroupView must be
            //                        AutoLayerTabLayoutParams!
            //     at ...readedpage.hades.view.ImAutoLayerTabLayout.onMeasure
            //     at android.widget.LinearLayout.measureVertical
            //
            // `ImAutoLayerTabLayout` (the strip holding 回复 / 复制 / 转发 / 多选 / 删除) VALIDATES
            // the runtime class of every child's layoutParams inside its own onMeasure and throws on
            // anything foreign, which took the whole app down on the next layout pass.
            //
            // `generateLayoutParams` is the public ViewGroup API that builds an instance of exactly
            // the class the container demands, so it is used FIRST. Copying a sibling's instance is
            // the fallback -- but note it would SHARE one object between two views, so it is only
            // used when generateLayoutParams is unavailable.
            // `generateDefaultLayoutParams` is preferred over `generateLayoutParams(null)`: the
            // container OVERRIDES the default one (that is where its required type comes from),
            // whereas a container may leave `generateLayoutParams` at the base implementation, which
            // would hand back a plain class and be rejected. `generateLayoutParams(null)` is kept as
            // the fallback for a container that does the opposite.
            //
            // It is reached REFLECTIVELY because the method is `protected` on ViewGroup, so Kotlin
            // cannot call it directly -- the compile error was
            // "Cannot access 'fun generateDefaultLayoutParams()': it is protected in 'ViewGroup'".
            // Reflection is legitimate here: the override is the container's own and returns exactly
            // the class it will accept.
            val siblingLp = firstChildLayoutParams(anchor)
            val lp: ViewGroup.LayoutParams = defaultLayoutParamsOf(anchor)
                ?: try {
                    anchor.generateLayoutParams(null)
                } catch (_: Throwable) {
                    siblingLp ?: LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
            // Match the siblings' size when the class allows it, so our button is the same shape as
            // 复制 / 转发 rather than a differently-sized odd one out.
            try {
                if (siblingLp != null) {
                    lp.width = siblingLp.width
                    lp.height = siblingLp.height
                }
            } catch (_: Throwable) {
                // some custom params refuse direct width/height; the generated defaults stand
            }
            // BOTH entries get the SAME layoutParams instance from the container, so neither can be
            // shaped differently from the other or from Douyin's own items.
            col.layoutParams = lp
            balloonCol.layoutParams = lp
            // A fresh instance for the second button: sharing ONE LayoutParams object between two
            // views is illegal in Android (the last view to be laid out wins, and the other is
            // measured with the wrong values), so the balloon button is given its own copy.
            try {
                balloonCol.layoutParams = defaultLayoutParamsOf(anchor) ?: lp
            } catch (_: Throwable) {
                // the shared instance stands; still better than no entry
            }
            try {
                if (siblingLp != null) {
                    balloonCol.layoutParams.width = siblingLp.width
                    balloonCol.layoutParams.height = siblingLp.height
                }
            } catch (_: Throwable) {
                // custom params may refuse direct sizing
            }

            anchor.addView(col)
            anchor.addView(balloonCol)

            // ============================ THE SAFETY NET (this is the important part) ==========
            //
            // THE CRASH IS NOT CATCHABLE AT THE addView SITE. It fires in DOUYIN'S OWN measure
            // pass, on a later frame, outside any try/catch of ours -- which is exactly how the
            // previous build killed the host app twice. `try { addView } catch` is useless here.
            //
            // So we FORCE THE MEASURE OURSELVES, synchronously, while we can still undo it: if the
            // container rejects our child (its own onMeasure throws), we remove the views and the
            // menu simply has no 分析 entry. A missing button is an acceptable failure; killing
            // Douyin is not, and a module that crashes its host gets uninstalled.
            //
            // The measure is forced on the anchor with the anchor's own current dimensions, which
            // is the same pass that would have thrown later.
            if (!verifyMeasurable(anchor)) {
                try {
                    anchor.removeView(col)
                } catch (_: Throwable) {
                    // removal failed; the menu may still lay out, but we have already logged loudly
                }
                try {
                    anchor.removeView(balloonCol)
                } catch (_: Throwable) {
                    // as above
                }
                Log.e(
                    TAG,
                    "douyin: the menu container (" + anchor.javaClass.simpleName +
                        ") REJECTED our buttons during measure -- removed them to protect Douyin. " +
                        "The 分析 entry is unavailable in this menu; the balloon still works.",
                )
                return
            }

            Log.i(
                TAG,
                "douyin: injected the (" + ROW_TITLE + " + " + BALLOON_TITLE + ") buttons into " +
                    anchor.javaClass.simpleName +
                    " via=" + (matchedLabel?.let { "label '" + it + "'" } ?: "shape fallback") +
                    " children=" + anchor.childCount +
                    " lpClass=" + lp.javaClass.simpleName +
                    " anchorW=" + anchor.width,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "douyin: injecting the row failed: " + t.message, t)
        }
    }

    /**
     * Forces [anchor] to measure NOW, returning false when its own onMeasure threw.
     *
     * This is the single most important safety mechanism in this file, and it exists because of a
     * concrete crash. Douyin's `ImAutoLayerTabLayout` throws from `onMeasure` when a child carries
     * the wrong layoutParams class:
     *
     *     java.lang.Exception: the layoutParam of child view in ImHadesGroupView must be
     *                          AutoLayerTabLayoutParams!
     *       at ImAutoLayerTabLayout.onMeasure
     *
     * That throw happens on the NEXT FRAME, during Douyin's own layout pass -- NOT inside our
     * `addView` call, so `try { addView } catch` cannot see it. The observed result was a hard
     * `FATAL EXCEPTION: main` twice in a row, with the OS flagging Douyin as an unstable app.
     *
     * By calling `measure()` ourselves while the call stack is still OURS, the same throw is raised
     * INSIDE our try/catch, at a point where the offending child can still be removed. The failure
     * mode becomes "no 分析 button in this menu" instead of "the user's Douyin crashes".
     *
     * `measure()` with the same specs the parent would have used is deliberate: a different spec
     * could pass a check that the real pass then fails.
     */
    private fun verifyMeasurable(anchor: ViewGroup): Boolean = try {
        val w = if (anchor.width > 0) anchor.width else View.MeasureSpec.makeMeasureSpec(
            anchor.resources.displayMetrics.widthPixels,
            View.MeasureSpec.AT_MOST,
        )
        val h = if (anchor.height > 0) anchor.height else View.MeasureSpec.makeMeasureSpec(
            anchor.resources.displayMetrics.heightPixels,
            View.MeasureSpec.AT_MOST,
        )
        // A direct integer spec matching the current size is what the parent will re-use on the next
        // real pass; falling back to AT_MOST only when the view has never been laid out.
        val wSpec = if (anchor.width > 0) {
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY)
        } else {
            w
        }
        val hSpec = if (anchor.height > 0) {
            View.MeasureSpec.makeMeasureSpec(anchor.height, View.MeasureSpec.EXACTLY)
        } else {
            h
        }
        anchor.measure(wSpec, hSpec)
        true
    } catch (t: Throwable) {
        Log.w(
            TAG,
            "douyin: measure check rejected our button: " + t.javaClass.simpleName + ": " + t.message,
        )
        false
    }

    /**
     * Builds one menu button: icon above a white label, clickable, identified by [tag].
     *
     * Extracted so 分析 and 浮窗 are produced by identical code. Two hand-built views would drift --
     * one keeping the old black text, or a different icon size -- and the user compares them side by
     * side in the same row, where any difference is immediately visible.
     */
    private fun buildMenuButton(
        ctx: Context,
        title: String,
        tag: String,
        iconSize: Int,
        onClick: (View) -> Unit,
    ): LinearLayout {
        val icon: View? = try {
            com.luoliaoo.wechat.ui.AnalysisIcon(ctx).also { v ->
                v.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "douyin: the icon could not be built: " + t.message)
            null
        }

        val label = TextView(ctx).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            // WHITE text, per the user's request: Douyin's menu is a DARK bubble, so the black used
            // before was unreadable there. The neighbours' own labels are white too, which this matches.
            setTextColor(android.graphics.Color.WHITE)
            isSingleLine = true
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            this.tag = tag
            icon?.let { addView(it) }
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnClickListener { v -> onClick(v) }
        }
    }

    /**
     * The container's own default layoutParams, fetched reflectively, or null when unavailable.
     *
     * `ViewGroup.generateDefaultLayoutParams()` is `protected`, so Kotlin refuses a direct call --
     * the build failed with "it is protected in 'ViewGroup'". Reflection reaches the CONTAINER'S
     * OVERRIDE, which is the whole point: that override is where a custom container declares the
     * params class it will accept (Douyin's `ImAutoLayerTabLayout` demands its own type and throws
     * otherwise), so an instance of it cannot be rejected by the measure-time check.
     */
    private fun defaultLayoutParamsOf(anchor: ViewGroup): ViewGroup.LayoutParams? = try {
        val m = ViewGroup::class.java.getDeclaredMethod("generateDefaultLayoutParams")
        m.isAccessible = true
        m.invoke(anchor) as? ViewGroup.LayoutParams
    } catch (t: Throwable) {
        Log.i(
            TAG,
            "douyin: generateDefaultLayoutParams unavailable (" + t.javaClass.simpleName +
                "); falling back to generateLayoutParams",
        )
        null
    }

    /**
     * The layoutParams instance of the anchor's first child, used as the TEMPLATE for our button.
     *
     * Needed because Douyin's view groups validate their children's layoutParams class (see the
     * crash note in [injectRow]), so our button must be given an instance of the SAME class the
     * existing items use. Taking it from a real sibling is the only reliable source: the class is
     * an inner type of an obfuscated control and cannot be constructed by name.
     */
    private fun firstChildLayoutParams(anchor: ViewGroup): ViewGroup.LayoutParams? {
        for (i in 0 until anchor.childCount) {
            val lp = anchor.getChildAt(i).layoutParams
            if (lp != null) return lp
        }
        return null
    }

    /**
     * Dismisses the menu window our row lives in.
     *
     * Closing the menu matters for more than tidiness: the analysis panel and the scope dialog are
     * hosted in the chat screen, and leaving a PopupWindow on top of them makes the dialog
     * unreachable. The window is reached from the ROW itself rather than from a saved manager
     * reference, so a stale reference after Douyin rebuilds its menu cannot dismiss the wrong thing.
     *
     * Three ways, in order, because the way a PopupWindow is reachable depends on where Douyin
     * attached it:
     *   1. the content view's own OnDismissListener is not public API, so we instead ask the
     *      window's decor root;
     *   2. `View.getParent()` up to a `PopupDecorView`, whose `getWindowId`-carrying root is bound
     *      to a PopupWindow we cannot get a reference to from here;
     *   3. the reliable one: simulate the user's own dismissal by posting a back press to the
     *      content view -- this is what Douyin itself does when an item is tapped.
     *
     * Only (3) is used, and it is wrapped: if it fails the menu simply stays open.
     */
    private fun closeMenu(anchor: View) {
        try {
            val content = anchor.rootView ?: return
            // Douyin's own item clicks dismiss via the window; a back key event on the decor has the
            // same effect and needs no reference to the PopupWindow.
            val ev = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_BACK,
            )
            content.dispatchKeyEvent(ev)
            content.dispatchKeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_BACK,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "douyin: closing the menu failed (not fatal): " + t.message)
        }
    }

    /**
     * The row was tapped: run the analysis for the message the menu was opened on.
     *
     * This is deliberately the SAME entry the WeChat path uses
     * (`AnalysisController.startAnalysisFor(msg, view)`), so the card, the scope dialog, the cache
     * and the cost accounting are shared code -- not a Douyin-only reimplementation.
     */
    private fun onOurRowClicked(v: View, msg: Any) {
        try {
            if (!com.luoliaoo.wechat.hook.DouyinMsgAccess().usable(msg)) {
                Toast.makeText(
                    v.context,
                    HostApp.current.say("\u8be5\u6d88\u606f\u65e0\u6587\u672c"),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            Log.i(
                TAG,
                "douyin: analysing from the menu row -- talker=" +
                    controller.access().talker(msg) +
                    " len=" + controller.access().content(msg).length,
            )
            controller.startAnalysisFor(msg, v)
        } catch (t: Throwable) {
            Log.e(TAG, "douyin: starting the analysis failed: " + t.message, t)
            try {
                Toast.makeText(
                    v.context,
                    HostApp.current.say("\u65e0\u6cd5\u5f00\u59cb\u5206\u6790"),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (_: Throwable) {
                // a failed toast must not mask the original error
            }
        }
    }

    /**
     * The 浮窗 entry: flips the floating-balloon switch, exactly as WeChat's entry does.
     *
     * Shares ConfigRepo.toggleBalloonEnabled and FloatingPanel with the WeChat path rather than
     * reimplementing the toggle. That matters beyond tidiness: the balloon state is a single
     * persisted flag, so a second implementation could invert it differently or skip the mirror
     * write, and the user would see the balloon disagree between the two hosts.
     *
     * WeChat's version (MenuHook.handleBalloonSwitchTap) does the same three things in the same
     * order -- toggle, show/hide, toast -- because that order is what makes the tap feel immediate.
     */
    private fun onBalloonRowClicked(v: View) {
        val ctx = try {
            v.context
        } catch (_: Throwable) {
            null
        } ?: run {
            Log.w(TAG, "douyin: balloon switch has no Context; the choice cannot be saved")
            return
        }
        try {
            val on = com.luoliaoo.wechat.data.ConfigRepo.toggleBalloonEnabled(ctx)
            Log.i(TAG, "douyin: balloon switch -> " + (if (on) "ON" else "OFF"))
            if (on) {
                com.luoliaoo.wechat.ui.FloatingPanel.showGlobalOn(ctx)
            } else {
                com.luoliaoo.wechat.ui.FloatingPanel.hideGlobal()
            }
            Toast.makeText(
                ctx,
                if (on) {
                    HostApp.current.say("\u6d6e\u7a97\u5df2\u5f00\u542f")
                } else {
                    HostApp.current.say("\u6d6e\u7a97\u5df2\u5173\u95ed")
                },
                Toast.LENGTH_SHORT,
            ).show()
        } catch (t: Throwable) {
            Log.e(TAG, "douyin: balloon switch failed: " + t.message, t)
        }
    }

    /** No-arg method returning PopupWindow, found by shape because its name is obfuscated. */
    private fun popupWindowOf(mgr: Any): PopupWindow? {
        var p: Class<*>? = mgr.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            for (m in p.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!PopupWindow::class.java.isAssignableFrom(m.returnType)) continue
                try {
                    m.isAccessible = true
                    val w = m.invoke(mgr)
                    if (w is PopupWindow) return w
                } catch (_: Throwable) {
                    // try the next accessor
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * Loads a Douyin class through the HOST loader first.
     *
     * The host loader matters: in a clone the module loader resolves a different Class object for
     * the same name, and hooking that one silently never fires (this is the documented WeChat clone
     * bug in 必看.md). `resolver.classLoader()` already prefers the host loader.
     */
    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, resolver.classLoader())
    } catch (_: Throwable) {
        try {
            Class.forName(name, false, loader)
        } catch (_: Throwable) {
            null
        }
    }
}
