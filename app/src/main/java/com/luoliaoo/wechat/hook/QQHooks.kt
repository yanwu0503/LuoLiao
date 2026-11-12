package com.luoliaoo.wechat.hook

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ (com.tencent.mobileqq) 9.3.65 hooks: the long-press menu's 分析 entry and the multi-select
 * adaptation.
 *
 * ============================== READ THIS BEFORE EDITING ==============================
 * QQ 9.3.65 runs its chat page on the NT stack (`com.tencent.qqnt.*`), NOT the classic
 * `com.tencent.mobileqq.data.MessageRecord` one. Every class named here was checked with `qq.py def`
 * (a real `class_def`) and most with `qq_invoke.py` (a real `invoke-*` instruction), so none of them is
 * a SMAP source-file name -- the trap 蹇呯湅.md records, where a byte hit in a string pool looked like a
 * class and every hook silently never fired.
 *
 * DEVICE FACTS that shaped the design (`_tools/qq/T11_RECON_MENU_INPUT_MULTISELECT.md`):
 *   * The chat page is NOT a separate Activity. Entering a chat keeps
 *     `com.tencent.mobileqq.activity.SplashActivity` resumed, because NT is single-Activity +
 *     Fragment/MVI. => nothing here may decide "are we in a chat" from an Activity name.
 *   * The long-press menu is a SEPARATE `Panel:` window, so `uiautomator dump` CANNOT see its rows.
 *     Verifying the injected row on a device therefore needs a screenshot (or this log), not an
 *     accessibility dump -- an earlier check "found no menu" for exactly that reason.
 *
 * ============================== WHY THE ROW IS A VIEW, NOT A MENU ITEM ==============================
 * The menu's own item type `ui.e` is an ABSTRACT CLASS (`access_flags=0x401`), so
 * `java.lang.reflect.Proxy` cannot implement it -- it creates interfaces only, and the first
 * long-press would have thrown `IllegalArgumentException`. Defining a subclass is impossible without dex
 * surgery, and the host's own item classes (`menu.aq`, `menu.az`) hardcode their identity:
 * `menu.aq#g()` returns the literal "RobotReportMenuItem", so reusing one would produce a row that
 * says the wrong thing AND runs the host's robot-report feature.
 *
 * So QQ renders its own rows and we append OURS to the same container, copying a real row's visual
 * properties. This is the pattern `MenuHook` already uses for WeChat, it needs no class we cannot
 * create, and it reuses the module's existing `AnalysisIcon`.
 *
 * The host's STRING-keyed dispatch table still matters: it is what proves nothing we add can be routed
 * into a host feature (see [QQMenuIdentity]'s KDoc 1). That is why this host needs no
 * `chain.skipOriginal()` equivalent -- the bug class Kuaishou fought does not exist here.
 *
 * ============================== WHAT THIS FILE DOES NOT DO ==============================
 * No message reading, no prompt building, no analysis UI: those are the shared `AnalysisController`'s
 * job and every host enters it through the same calls. This file only puts a row in the menu, recognises
 * its tap, and hands the right message(s) over.
 *
 * Comments ASCII-only; Chinese uses \uXXXX escapes (see core/Log.kt).
 */
class QQHooks(
    private val loader: ClassLoader,
    private val resolver: com.luoliaoo.wechat.core.Resolver,
    private val controller: AnalysisController,
) {

    // ------------------------------------------------------------------ state

    @Volatile
    private var installed = false

    /** How many times a rendered menu was seen, so "the hook never ran" is distinguishable. */
    @Volatile
    private var menusSeen = 0

    /** How many times our row was appended. */
    @Volatile
    private var injected = 0

    /** How many times our row was tapped and routed. */
    @Volatile
    private var taps = 0

    @Volatile
    private var menuHooked = false

    /** Set once the host's selection accessor is hooked, so the report states it as a fact. */
    @Volatile
    private var selectionHooked = false

    /**
     * The message from the most recent long-press.
     *
     * The row's own message (read from its `ui.e` tag) is authoritative and is preferred; this covers the
     * case where our row survived a rebuild that left it without a tag, so a tap is never inert for a
     * reason the user cannot see.
     */
    @Volatile
    private var lastMenuMessage: Any? = null

    /**
     * The menu's own view, captured at render time.
     *
     * ============================== WHY THIS IS THE PANEL, NOT A CHAT VIEW ==============================
     * On QQ the long-press menu is a SEPARATE `Panel:` window (device-verified: contentSize 185x595 at
     * frame [339,994][934,1179]). So this anchor ISN'T in the chat page's view tree, and it must NOT be
     * handed to code that walks UP looking for the chat's `RecyclerView` -- `ChatContext.findRecyclerView`
     * would climb to the Panel's decor view and find nothing, silently leaving the shared path with an
     * empty message list.
     *
     * So this field has exactly ONE job: resolve the Activity (`ContextWrapper` chain, which the Panel's
     * themed context still reaches). Everything that needs a CHAT view goes through
     * [chatAnchorIn], which searches DOWN from that Activity instead.
     */
    @Volatile
    private var lastMenuAnchor: View? = null

    /** Diagnostics de-duplicated by KEY, not by event count (see 蹩呯湅.md on log flooding). */
    private val reminded = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * The host's OWN message list, CAPTURED by hooking the accessor the host itself calls.
     *
     * ============================== WHY THIS EXISTS (the recon's decisive result) ==============================
     * An earlier revision read the selection out of the multi-select BAR's view (`MultiSelectBarVB`) by
     * walking its declared fields. That CANNOT work, and the dex proves it: every field on
     * `MultiSelectBarVB` is a `View` or a `kotlin.Lazy` lambda -- there is NO collection field at all. The
     * code compiled, logged nothing wrong, and would have returned an empty selection forever.
     *
     * ============================== THIS IS THE MESSAGE LIST, NOT THE SELECTION ==============================
     * WHAT IT IS: the object `MsgListVMWrapper#N()` returns -- the conversation's loaded messages.
     *
     * WHAT IT IS NOT: the user's ticked selection. An earlier revision of this file asserted the opposite,
     * and that error WAS the device bug: long-pressing one message reported "7 message(s) selected"
     * (7 = the messages loaded) and opened the batch dialog 分析已选消息 instead of 分析范围.
     *
     * THE PROOF, and it is worth re-checking rather than trusting this comment:
     *   * `impl.b#<init>` assigns `i = new CopyOnWriteArrayList()` -- EMPTY. The only other writer is
     *     `impl.b#c0`, which fills it from the repository on `MsgListRepoOpType.LoadPrePage`:
     *     `c0 -> data.d#a(c) -> data.l#c() -> data.l#a : List`. A page of chat history.
     *   * `N()` has ~19 callers including `ScrollMsgListVM$scrollListener$2$a#onScrollStateChange`
     *     (scroll geometry) and `childvm.j#h`, which takes its LAST element -- meaningless for a
     *     selection, obvious for "the loaded messages".
     *   * The host DERIVES the selection from this list by filtering each element with
     *     `AIOMsgItem#K2()Z` (see `childvm.j#l` at [00bd]/[00c1]).
     *
     * The trace below is therefore about WHERE THE MESSAGE LIST COMES FROM, which is what makes the field
     * `i` reachable at all -- not evidence that it is a selection. [readSelectedMessages] applies the
     * missing `K2()` step.
     *
     * Chain:
     *
     *     com.tencent.mobileqq.aio.msglist.childvm.j#l(MsgIntent)    [classes6, 1514 code units]
     *       [0040] g#d()            -> MsgListVMWrapper
     *       [0044] MsgListVMWrapper#N()Ljava/util/List;             <-- the message list
     *       [004e] new CopyOnWriteArrayList(<that list>)
     *       [0087] new MultiSelectEvent$GetSelectMsgList(<list>, int, marker)
     *     and further down the SAME method, the filter that turns it into a selection:
     *       [00bd] AIOMsgItem#K2()Z
     *       [00c1] if-eq                 <-- keep only the selected entries
     *       [00c3] ArrayList#add(...)
     *
     *     MsgListVMWrapper#N() -> (base) msglist.mvx.vm.a#N()   [abstract]
     *     msglist.mvx.vm.impl.b#N()Ljava/util/List;  [classes3, 22 code units]
     *       [0013] iget-object impl.b.i : java.util.concurrent.CopyOnWriteArrayList
     *       [0015] return-object
     *
     * ============================== WHY WE HOOK `N()` INSTEAD OF FINDING THE OBJECT ==============================
     * `N()` is the host's only public way to reach the message list, and the host calls it whenever that
     * list changes. Hooking it in AFTER mode therefore hands us the exact list object the host uses, with
     * NO view-tree walking, NO adapter lookup and NO assumption about where the view-model lives.
     * Discovering the object by reflection instead would mean guessing a traversal that the host already
     * performs internally -- the kind of plausible-looking guess this project forbids.
     *
     * And because what comes back is the host's LIVE `CopyOnWriteArrayList` field `i` (not a copy), one
     * capture stays current as the user ticks and unticks boxes: the predicate is re-evaluated on every
     * tap, so a stale snapshot is never used.
     */
    @Volatile
    private var hostSelection: Any? = null

    // ------------------------------------------------------------------ install

    /**
     * The only public entry point. Shape matches [KuaishouHooks.install] and [DouyinHooks.install].
     *
     * Each step is wrapped so one failure cannot stop the others: a QQ internal rename must degrade ONE
     * feature, never the whole host integration. The final line names which steps succeeded, so a partial
     * install can never look like a full one.
     */
    fun install() {
        if (installed) {
            Log.i(TAG, "QQ: install() called twice; the second call is a no-op")
            return
        }
        installed = true

        Log.i(
            TAG,
            ">> qq hooks: installing (host=" + HostApp.current.name +
                " versionName=" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME + ")",
        )

        step("message model") { logMessageModel() }
        step("menu model") { logMenuModel() }
        // v8.0.0: print the menu classes' own method tables. Added because QQ 9.1.97 reports the class
        // present but our render method gone (`#A()V not found on this build`), which leaves nothing to
        // guess from -- see [dumpMenuMethods].
        step("menu methods") { dumpMenuMethods() }
        step("long-press menu") { menuHooked = installMenuHook() }
        step("selection accessor") { selectionHooked = installSelectionHook() }

        // Multi-select needs NO extra hook on QQ. The host's own 多选 item
        // (`MenuMultiSelectProcessor`, key `MultiSelectMenuItem`) puts the user in multi-select mode, and
        // our row rides the SAME menu. The selection is then DERIVED, not intercepted: we hold the host's
        // message list (captured above) and narrow it with the host's own `AIOMsgItem#K2()` predicate on
        // every tap -- see [readSelectedMessages]. This step only confirms the classes are reachable and
        // states the decision out loud.
        step("multi-select state") { probeMultiSelect() }
        // v8.0.0: a VERSION-INDEPENDENT menu anchor, added after QQ 9.1.97 broke the named one.
        //
        // On 9.1.97 the install log still reports `menuHooked=true ... menusSeen=0`: the render method
        // WAS found and hooked, and then never invoked even once. That is the signature of a class that
        // exists but is no longer on the path this build uses -- so every menu on that version would be
        // missing our rows while every log line read healthy.
        //
        // See [watchForMenuPopup] for why the framework's own PopupWindow is the anchor that cannot be
        // renamed out from under us.
        step("menu popup watcher") { watchForMenuPopup() }
        // v8.0.0: the 分析 entry in the multi-select TOOLBAR, like WeChat's. The toolbar has no render
        // method of its own that we can name (see [injectMultiSelectToolbar]), so it is installed by
        // watching the message list: every time rows are bound we look for the bar and add our entry if
        // it is there. A no-op when the user is not in multi-select.
        step("multi-select toolbar") { watchForMultiSelectToolbar() }

        Log.i(
            TAG,
            ">> qq hooks: done (menuHooked=" + menuHooked +
                " menusSeen=" + menusSeen +
                " rowsInjected=" + injected +
                " taps=" + taps +
                " steps=" + stepsOk + "/" + stepsRun + ")",
        )
    }

    @Volatile
    private var stepsRun = 0

    @Volatile
    private var stepsOk = 0

    /**
     * Runs one install step, counting it and logging the failure instead of propagating it.
     *
     * ONE overload only, deliberately. A second `step(name, body: () -> Boolean)` overload was written
     * first and the compiler rejected every call as an "Overload resolution ambiguity": a lambda whose body
     * ends in a statement (an assignment, or a Unit-returning call) is compatible with BOTH signatures, so
     * Kotlin cannot pick one. The Boolean form bought nothing -- a caller that has a Boolean simply assigns
     * it inside the lambda -- so it was deleted rather than disambiguated.
     */
    private fun step(name: String, body: () -> Unit) {
        stepsRun++
        try {
            body()
            stepsOk++
        } catch (t: Throwable) {
            Log.w(TAG, "QQ: step '" + name + "' failed: " + t.message, t)
        }
    }

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    private fun missing(name: String, why: String) {
        if (reminded.add("missing:" + name)) {
            Log.w(TAG, "QQ: " + name + " is not loadable (" + why + ")")
        }
    }

    private fun rememberOnce(key: String, message: String) {
        if (reminded.add(key)) Log.w(TAG, message)
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Probes the NT message model so the first device run CONFIRMS the real classes.
     *
     * `AIOMsgItem` carries TWO `MsgRecord`-typed fields in 9.3.65 (`i1` and `e`), so this counts them and
     * the log states the ambiguity: that is why [messageFromItem] calls the `getMsgRecord()` GETTER and
     * never reads a field by letter -- a field-by-letter read would be a coin flip between two objects.
     */
    private fun logMessageModel() {
        val item = loadClass(QQEnvelope.AIO_ITEM_CLASS)
        val record = loadClass(QQEnvelope.MSG_CLASS)
        if (item == null) missing(QQEnvelope.AIO_ITEM_CLASS, "message carrier")
        if (record == null) missing(QQEnvelope.MSG_CLASS, "NT message model")
        if (item == null || record == null) return

        var recordFields = 0
        run {
            var p: Class<*>? = item
            var depth = 0
            while (p != null && depth < 6) {
                for (f in p.declaredFields) {
                    if (record.isAssignableFrom(f.type)) recordFields++
                }
                p = p.superclass
                depth++
            }
        }
        Log.i(
            TAG,
            "QQ message model: " + QQEnvelope.AIO_ITEM_CLASS + " (MsgRecord-typed fields=" + recordFields +
                ", so the getter is used, never a field letter) + " + QQEnvelope.MSG_CLASS,
        )
    }

    /** Probes the menu classes and resolves the members the injection needs. */
    private fun logMenuModel() {
        val container = loadClass(QQMenuIdentity.MENU_CLASS)
        val item = loadClass(QQMenuIdentity.ITEM_CLASS)
        val layout = loadClass(QQMenuIdentity.MENU_LAYOUT_CLASS)
        if (container == null) missing(QQMenuIdentity.MENU_CLASS, "menu container")
        if (item == null) missing(QQMenuIdentity.ITEM_CLASS, "menu item")
        if (layout == null) missing(QQMenuIdentity.MENU_LAYOUT_CLASS, "menu renderer")
        if (layout == null || item == null) return

        Log.i(
            TAG,
            "QQ menu model: " + QQMenuIdentity.MENU_CLASS + " + " + QQMenuIdentity.ITEM_CLASS +
                " (abstract, so rows are injected as Views) + " + QQMenuIdentity.MENU_LAYOUT_CLASS +
                " (render=" + QQMenuIdentity.LAYOUT_RENDER + ", hostKeys=" + QQMenuIdentity.HOST_KEYS.size +
                ", ourRowTag=" + QQMenuIdentity.ROW_TAG_OURS + ")",
        )
    }

    /**
     * Hooks the host's message-list accessor: `MsgListVMWrapper#N()Ljava/util/List;`.
     *
     * WHY THIS IS THE RIGHT ANCHOR, from the dex: the host's own multi-select child VM calls exactly this
     * method, then narrows the result with `AIOMsgItem#K2()Z` to obtain the selection. Hooking it in AFTER
     * mode hands us the very list object the host uses for both purposes.
     *
     * IT IS NOT THE SELECTION BY ITSELF -- it is the MESSAGE LIST. See [hostSelection] for the proof and
     * [readSelectedMessages] for the filtering step that turns it into a selection. An earlier revision
     * treated the return value as the selection and produced the "one long-press analysed 7 messages"
     * bug.
     *
     * IT IS DECLARED ON THE WRAPPER AND INHERITED FROM AN ABSTRACT BASE, so both are hooked when both
     * resolve: `MsgListVMWrapper#N()` delegates to its `f` field's `N()`, and the concrete
     * `msglist.mvx.vm.impl.b#N()` is what actually returns the list. Hooking only the wrapper would still
     * work (we see the RESULT), but hooking both makes the capture independent of which one the host calls
     * after a refactor.
     *
     * Hooked in AFTER mode because the value we want is the method's RESULT -- an `intercept` (before) hook
     * would see no result at all on the first run.
     */
    private fun installSelectionHook(): Boolean {
        val wrapper = loadClass(MSG_LIST_WRAPPER_CLASS)
        val impl = loadClass(MSG_LIST_IMPL_CLASS)
        if (wrapper == null) missing(MSG_LIST_WRAPPER_CLASS, "message-list VM wrapper")
        if (impl == null) missing(MSG_LIST_IMPL_CLASS, "message-list VM impl")

        var hooked = 0
        for (cls in listOfNotNull(wrapper, impl)) {
            val target = cls.declaredMethods.firstOrNull {
                it.name == MSG_LIST_SELECTED_ACCESSOR && it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType)
            } ?: continue
            try {
                LegacyHook.hook(target)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .interceptAfter { chain ->
                        val list = chain.result
                        if (list is List<*>) {
                            // The host's LIVE list, not a copy (impl.b#N returns its own field). Storing it
                            // means every later read sees the current ticks with no polling.
                            hostSelection = list
                            selectionCaptures++
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "QQ SELECT: hooked " + cls.name + "#" + MSG_LIST_SELECTED_ACCESSOR +
                        "()Ljava/util/List; -- the host's own selected-message accessor",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "QQ SELECT: hooking " + cls.name + "#N() failed: " + t.message)
            }
        }
        if (hooked == 0) {
            Log.w(
                TAG,
                "QQ SELECT: the selection accessor was NOT hooked, so multi-select batch analysis " +
                    "cannot see the selection (the single-message path still works)",
            )
        }
        return hooked > 0
    }

    /** How many times the host asked for its selection, so "never called" is distinguishable. */
    @Volatile
    private var selectionCaptures = 0

    /**
     * How many reconnaissance tree dumps have run, capped so the log cannot be flooded.
     *
     * Two runs are enough and are deliberately MORE than one: the first captures the menu's own
     * window, and by the second the chat page's toolbar has finished its entrance animation and its
     * children exist -- an animation still running is exactly how a "there is nothing there" reading
     * happens by accident.
     */
    private val dumpRuns = java.util.concurrent.atomic.AtomicInteger(0)

    // ------------------------------------------------------------------ multi-select toolbar recon

    /**
     * Dumps the live view tree so the multi-select TOOLBAR and the CHECKED MESSAGES can be located.
     *
     * WHY A RUNTIME DUMP AND NOT DEX ANALYSIS. The toolbar is a plain `View` built by the chat
     * fragment; its interesting parts are the parent chain and the per-row `CheckBox` state, and
     * neither exists anywhere in the dex as a name we could look up. The device `uiautomator dump`
     * is not enough either -- it FLATTENS obfuscated classes to `android.widget.LinearLayout` and
     * reports no class names at all, so it cannot say which container to inject into.
     *
     * WHAT IT PRINTS, and why each part is needed:
     *   * every view's REAL class name, so the toolbar container can be identified rather than
     *     assumed (`MultiSelectBarVB` is a fingerprint/state class -- it declares NO collection
     *     field -- so the view that actually holds the buttons is a different class);
     *   * the `CheckBox` nodes with their checked state, so the checked messages can be told from the
     *     unchecked ones ON THE SAME SCREEN. That contrast is the whole point: both earlier attempts
     *     at "which messages are selected" used a source that answered true for EVERY message, and a
     *     source can only be proved wrong (or right) by seeing it disagree with the checkboxes.
     *
     * Runs at most twice, on the main thread, and swallows everything: this is a diagnostic and it
     * must never be the reason a tap does nothing.
     */
    private fun dumpMultiSelectTree(tag: String) {
        val runs = dumpRuns.incrementAndGet()
        if (runs > 2) return
        val activity = lastMenuAnchor?.let { findActivityOrNull(it) } ?: return
        try {
            val root = activity.window?.decorView ?: return
            val sb = StringBuilder()
            sb.append("QQ MS-DUMP(").append(tag).append(") run=").append(runs).append('\n')
            collectTree(root, 0, sb)
            // Chunked: a single Log line is truncated by the platform at ~4KB, and a truncated dump
            // is worse than none because it looks complete.
            for (line in sb.toString().split('\n')) {
                if (line.isNotEmpty()) Log.i(TAG, line)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MS-DUMP failed: " + t.message)
        }
    }

    private fun collectTree(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 22) return
        val id = try {
            if (v.id != View.NO_ID) {
                v.resources.getResourceEntryName(v.id)
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        }
        val extra = when (v) {
            is android.widget.CheckBox -> " CHECKED=" + v.isChecked + " checkable=" + v.isClickable
            is android.widget.TextView -> {
                val t = v.text?.toString()?.trim() ?: ""
                if (t.isEmpty()) "" else " text='" + t.take(24) + "'"
            }
            else -> ""
        }
        val b = android.graphics.Rect()
        v.getGlobalVisibleRect(b)
        sb.append("  ".repeat(depth.coerceAtMost(18)))
            .append(v.javaClass.name)
            .append(if (id.isEmpty()) "" else "#" + id)
            .append(extra)
            .append(" ").append(b.width()).append('x').append(b.height())
            .append(" @").append(b.left).append(',').append(b.top)
            .append('\n')
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            collectTree(c, depth + 1, sb)
        }
    }

    // ------------------------------------------------------------------ multi-select toolbar inject

    /**
     * Watches for the multi-select toolbar and installs our entry into it.
     *
     * WHY A WATCHER AND NOT A HOOK. Everywhere else in this file the trigger is a host method whose
     * NAME is known (`#A()V` for the menu, `setMenu` for its container). The multi-select toolbar has
     * no such name available: a device dump reports its container as a bare `android.widget.LinearLayout`
     * with obfuscated children, so there is nothing to bind to. What IS reliable is the toolbar's
     * presence in the chat Activity's view tree, so it is found by looking -- on a light repeating
     * check that costs almost nothing and simply does not find anything when the user is not in
     * multi-select.
     *
     * THE CADENCE is deliberately slow (700ms): this walks a view hierarchy, and the point is to
     * notice a toolbar that stays up for as long as the user is selecting -- not to catch the exact
     * frame it appears. The check stops itself after a bounded number of tries so a chat screen left
     * open all day cannot turn this into a permanent poll.
     *
     * Runs on the UI thread: reading view state from a background thread is not safe when the host may
     * be laying out at the same time.
     */
    private fun watchForMultiSelectToolbar() {
        // NOTE: this runs at INSTALL time, before any menu has ever been shown, so it deliberately
        // does NOT require an anchor here. Requiring one is what made the first version a no-op: the
        // log read `no anchor view yet` on every start and the watcher was never started again.
        //
        // The watch now begins the moment the host renders a long-press menu -- that is the step that
        // puts the user on the way to multi-select, and by then `lastMenuAnchor` exists. See the
        // `startToolbarWatch()` call in `onMenuRendered`.
        Log.i(
            TAG,
            "QQ MS-TOOLBAR: watcher armed -- it starts on the first long-press menu, because the " +
                "toolbar only exists once the user is in multi-select",
        )
        toolbarWatchArmed = true
        startToolbarWatch()
    }

    /** True once the watcher has been armed, so a late menu can start it. */
    @Volatile
    private var toolbarWatchArmed = false

    /** True while the repeating check is alive, so it is never started twice. */
    @Volatile
    private var toolbarWatchRunning = false

    /**
     * Starts the repeating check that looks for the multi-select toolbar.
     *
     * Idempotent: a menu renders several times per second during its open animation, and without the
     * `toolbarWatchRunning` guard that would start dozens of overlapping polls.
     */
    private fun startToolbarWatch() {
        if (!toolbarWatchArmed || toolbarInstalled || toolbarWatchRunning) return
        toolbarWatchRunning = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val tick = object : Runnable {
            override fun run() {
                if (toolbarInstalled) {
                    toolbarWatchRunning = false
                    return
                }
                tries++
                try {
                    val act = lastMenuAnchor?.let { findActivityOrNull(it) }
                    val root = act?.window?.decorView
                    if (root != null) {
                        if (injectMultiSelectToolbar(root)) {
                            toolbarInstalled = true
                            toolbarWatchRunning = false
                            Log.i(TAG, "QQ MS-TOOLBAR: installed after " + tries + " check(s)")
                            return
                        }
                    }
                } catch (_: Throwable) {
                }
                if (tries < 900) {
                    handler.postDelayed(this, 700L)
                } else {
                    toolbarWatchRunning = false
                }
            }
        }
        handler.postDelayed(tick, 700L)
    }

    /**
     * True once our entry is in the CURRENT toolbar, so the watcher can stop.
     *
     * Cleared whenever a long-press menu renders again: the toolbar is rebuilt every time the user
     * re-enters multi-select (and the host re-creates its bar on each chat switch), so a flag that
     * stayed true would leave the SECOND and every later toolbar without our entry -- a failure that
     * looks exactly like "sometimes the button is there, sometimes not".
     */
    @Volatile
    private var toolbarInstalled = false

    /**
     * Finds the multi-select TOOLBAR and puts our 分析 button in it, like WeChat's.
     *
     * THE TOOLBAR, measured on the device while multi-select was on (uiautomator dump, screen
     * 1272x2772):
     *
     *     LinearLayout [0,2525][1272,2716]                      <- the bar
     *       FrameLayout desc=转发 [85,2544][237,2696]
     *       FrameLayout desc=收藏 [322,2544][474,2696]
     *       FrameLayout desc=截图 [559,2544][711,2696]
     *       FrameLayout desc=删除 [796,2544][948,2696]
     *       FrameLayout desc=更多 [1034,2544][1186,2696]
     *
     * Five buttons of 152px with 85px gaps, and the last one ends at 1186 of 1272 -- so there IS room
     * to the right of them, which is where our entry goes.
     *
     * WHY IT IS FOUND BY CONTENT-DESCRIPTION, NOT BY CLASS ID. The dump shows the real classes
     * obfuscated to `android.widget.FrameLayout`, and the container as a bare `LinearLayout`; the
     * dex name of the toolbar view (if it even has one) is not reachable from here. The button
     * descriptions, on the other hand, are stable user-facing labels -- the host cannot rename 删除
     * without renaming it for its own users. So the bar is located by FINDING THE ROW OF BUTTONS,
     * whatever class it happens to be, which also survives a QQ refactor that swaps the container.
     *
     * SAFETY, learned the hard way on this very menu: inserting into a host row pushed the host's
     * last item off the edge. This bar has slack (see the coordinates above), and we additionally
     * REFUSE to inject when the anchor row is already wider than the screen -- better to skip the
     * entry than to hide one of the host's own buttons.
     */
    private fun injectMultiSelectToolbar(root: View): Boolean {
        val bar = findToolbarRow(root) ?: return false
        if (bar.findViewWithTag<View>(QQMenuIdentity.ROW_TAG_OURS) != null) {
            return true
        }
        val ctx = bar.context ?: return false
        try {
            val anchor = firstButtonIn(bar) ?: return false
            // A REAL icon above the label, cloned from the host's own button so ours is the same size
            // and sits at the same height. The first version drew TEXT ONLY, which is why the user
            // reported "there is no icon" -- every neighbouring entry has one.
            val hostIcon = findByType(anchor, ImageView::class.java, 0)
            val iconPx = hostIcon?.let {
                val w = if (it.width > 0) it.width else it.layoutParams?.width ?: 0
                if (w in 1..4096) w else dpToPx(ctx, ICON_DP)
            } ?: dpToPx(ctx, ICON_DP)

            val label = TextView(ctx).apply {
                text = QQMenuIdentity.OUR_ITEM_LABEL
                if (hostIcon != null || anchor is ViewGroup) {
                    // Match the host's label styling where one can be found, so our entry does not
                    // stand out as a different kind of thing.
                    val hostText = findByType(anchor, TextView::class.java, 0)
                    textSize = if (hostText != null) {
                        hostText.textSize / ctx.resources.displayMetrics.scaledDensity
                    } else {
                        12f
                    }
                    setTextColor(hostText?.currentTextColor ?: com.luoliaoo.wechat.ui.NukeTheme.TEXT)
                } else {
                    textSize = 12f
                    setTextColor(com.luoliaoo.wechat.ui.NukeTheme.TEXT)
                }
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }

            val icon = ImageView(ctx).apply {
                setScaleType(ImageView.ScaleType.FIT_CENTER)
                setImageDrawable(com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, iconPx))
            }

            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                tag = QQMenuIdentity.ROW_TAG_OURS
                setOnClickListener { onMultiSelectAnalysisTapped() }
                addView(icon)
                addView(label)
            }

            val item = FrameLayout(ctx).apply {
                isClickable = true
                isFocusable = true
                tag = QQMenuIdentity.ROW_TAG_OURS
                setOnClickListener { onMultiSelectAnalysisTapped() }
                // CLICKABLE AT EVERY LEVEL, and this is not redundant: the tap that "does nothing"
                // usually dies on an ancestor that is not clickable, because a touch only reaches a
                // child when every ancestor between it and the touched point accepts the event.
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }

            // ================= POSITION: INSERT DIRECTLY BEFORE THE ANCHOR BUTTON =================
            //
            // NOT into "the container found by scanning", which is what the previous two attempts did
            // and why the entry kept landing in the wrong place. The device log was explicit:
            //
            //     injected '分析' at index 0 of LinearLayout (barChildren=14, anchorW=152)
            //
            // 14 children is not the 5-button row -- the scan had found a wider ancestor that merely
            // CONTAINS the buttons, so index 0 of it is nowhere near 转发.
            //
            // The anchor (转发) itself is reliable -- it was located by its content description, and
            // the log confirms the right width (152px). So the placement does not need to identify the
            // row at all: take the anchor's OWN parent and put our entry immediately before the anchor.
            // "To the left of 转发" then holds no matter how the row is nested.
            val parent = anchor.parent as? ViewGroup
            if (parent == null) {
                Log.w(TAG, "QQ MS-TOOLBAR: the anchor button has no ViewGroup parent; not injected")
                return false
            }
            val at = parent.indexOfChild(anchor).coerceAtLeast(0)

            // WIDTH AND MARGINS, to stay on screen. The row is already full: five 152px buttons with
            // 85px gaps is 1100px of a 1272px screen, so only ~172px of slack exists. Ours is
            // therefore NARROWER than a host button and carries NO extra margins -- 152 -> ~106px,
            // which fits the slack exactly.
            val lp = anchor.layoutParams
            if (lp != null) {
                val cloned = copyLpFor(parent, lp)
                if (cloned.width > 0) {
                    cloned.width = (cloned.width * TOOLBAR_WIDTH_SCALE).toInt()
                }
                if (cloned is ViewGroup.MarginLayoutParams) {
                    cloned.leftMargin = 0
                    cloned.rightMargin = 0
                }
                item.layoutParams = cloned
            }

            parent.addView(item, at)
            parent.requestLayout()
            Log.i(
                TAG,
                "QQ MS-TOOLBAR: injected '" + QQMenuIdentity.OUR_ITEM_LABEL + "' before " +
                    anchor.contentDescription + " (parent=" + parent.javaClass.simpleName +
                    " children=" + parent.childCount + " at=" + at +
                    " anchorW=" + anchor.width + " ourW=" + item.layoutParams?.width + ")",
            )
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MS-TOOLBAR: inject failed: " + t.message)
            return false
        }
    }

    /**
     * The horizontal row of multi-select buttons, found by their content descriptions.
     *
     * Searches a bounded number of nodes and returns the SMALLEST container that holds at least three
     * of the known labels: the smallest one is the row itself, whereas an ancestor (the chat fragment)
     * also "contains" them and would be the wrong thing to add a sixth button to.
     */
    private fun findToolbarRow(root: View): ViewGroup? {
        var best: ViewGroup? = null
        var bestScore = 0
        val report = ArrayList<String>()
        fun walk(v: View, depth: Int) {
            if (depth > 30) return
            val g = v as? ViewGroup ?: return
            var hits = 0
            var firstTop = Int.MIN_VALUE
            var sameRow = true
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i) ?: continue
                val d = c.contentDescription?.toString() ?: ""
                if (d.isEmpty()) continue
                if (report.size < 10) report.add(d)
                if (!TOOLBAR_LABELS.contains(d)) continue
                hits++
                // THE ROW TEST, and it is what the first version got wrong: it accepted a container
                // that merely CONTAINED the buttons somewhere in its subtree, and injected into a
                // 14-child container instead (device log: `injected ... buttons=14`). The entry then
                // landed in a corner, outside the bar, and taps did nothing. A real toolbar row holds
                // its buttons as DIRECT children, on ONE line.
                val r = android.graphics.Rect()
                c.getGlobalVisibleRect(r)
                if (firstTop == Int.MIN_VALUE) {
                    firstTop = r.top
                } else if (kotlin.math.abs(r.top - firstTop) > 40) {
                    sameRow = false
                }
            }
            // >= 4 direct button children, all vertically aligned, and a HORIZONTAL-ish container:
            // exactly the measured shape (5 buttons, [0,2525][1272,2716], each 152px wide, 85px apart).
            if (hits >= 4 && sameRow) {
                val score = hits * 100 - g.childCount
                if (score > bestScore) {
                    bestScore = score
                    best = g
                }
            }
            for (i in 0 until g.childCount) {
                walk(g.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        if (best == null) {
            rememberOnce(
                "toolbarMissing",
                "QQ MS-TOOLBAR: no toolbar ROW found (need >=4 direct child buttons on one line). " +
                    "Descriptions seen: " + (if (report.isEmpty()) "<none>" else report.joinToString(" | ")),
            )
        }
        return best
    }

    private fun firstButtonIn(bar: ViewGroup): View? {
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i) ?: continue
            val d = c.contentDescription?.toString() ?: ""
            if (d.isNotEmpty() && TOOLBAR_LABELS.contains(d)) return c
        }
        return null
    }

    /**
     * Our toolbar entry's tap: analyse the messages the user has TICKED.
     *
     * Wired to [readCheckedMessages], which reads the on-screen `CheckBox` states -- the one source
     * that has been shown to disagree with itself across rows (checked and unchecked rows coexist in
     * a real dump), unlike the two earlier candidates that answered "selected" for everything.
     */
    private fun onMultiSelectAnalysisTapped() {
        multiSelectTaps++
        Log.i(TAG, "QQ MS-TOOLBAR: our " + QQMenuIdentity.OUR_ITEM_LABEL + " tapped (#" + multiSelectTaps + ")")
        try {
            val root = lastMenuAnchor?.let { findActivityOrNull(it) }?.window?.decorView
            if (root != null) {
                root.post { dumpMultiSelectTree("mstap#" + multiSelectTaps) }
            }
        } catch (_: Throwable) {
        }
        val checked = readCheckedMessages()
        if (checked.isEmpty()) {
            Log.w(
                TAG,
                "QQ MS-TOOLBAR: no message is ticked, so nothing was analysed (an empty batch would " +
                    "have analysed the whole conversation by accident)",
            )
            return
        }
        Log.i(
            TAG,
            "QQ MS-TOOLBAR: analysing " + checked.size + " ticked message(s) [" +
                QQMenuSelection.describeForLog(checked) + "]",
        )
        startBatchAnalysis(checked)
    }

    /** How many times our multi-select entry was tapped, for the log. */
    @Volatile
    private var multiSelectTaps = 0

    /**
     * The messages the user has TICKED, read from the on-screen `CheckBox` rows.
     *
     * THE ONE SOURCE THAT HAS ACTUALLY BEEN OBSERVED TO DIFFER PER ROW. A real dump taken in
     * multi-select mode showed both states side by side:
     *
     *     CheckBox checked=false [31,465]     CheckBox checked=true  [31,1837]
     *     CheckBox checked=false [31,1051]    CheckBox checked=true  [31,2029]
     *     CheckBox checked=false [31,1444]    CheckBox checked=true  [31,2221]
     *
     * Both earlier attempts at this question failed for the same reason: their source answered
     * "selected" for EVERY message, so a one-message long-press looked like a 7-message selection.
     * This one is different in kind -- the `CheckBox` state is what the USER sees, so it cannot
     * silently disagree with the screen.
     *
     * Each checked box is climbed to the message ROW, and the row's message object is read the same
     * way the long-press menu's row is ([messageFromItem] over the row's tag), so the batch path
     * receives exactly the objects the analysis understands.
     */
    private fun readCheckedMessages(): List<Any> {
        val out = ArrayList<Any>()
        try {
            val activity = lastMenuAnchor?.let { findActivityOrNull(it) } ?: return emptyList()
            val root = activity.window?.decorView ?: return emptyList()
            val boxes = ArrayList<android.widget.CheckBox>()
            collectCheckBoxes(root, boxes, 0)
            val reader = msgAccess()
            Log.i(
                TAG,
                "QQ MS-TOOLBAR: found " + boxes.size + " checkbox(es), " +
                    (boxes.count { it.isChecked }) + " ticked",
            )
            // ================= THE POSITION-BASED ROUTE, tried FIRST =================
            //
            // A checked box and the message it belongs to are in the SAME adapter row, so the row's
            // index into the message list is the reliable link between them. This is far stronger than
            // climbing view tags: the chat page's rows carry no menu item (`ui.e`), which is why the
            // earlier tag-climbing attempt resolved 0 messages out of a ticked box
            // (`8 checkbox(es), 1 ticked, 0 resolved to messages`).
            //
            // Each ticked box is mapped to its row's position, and that position indexes the host's
            // OWN adapter -- the same list the selection bar itself works from.
            // ================= THE ROUTE THAT WORKS: THE BIND-TIME REGISTRY =================
            //
            // Everything else was tried on the device and came back empty. The full evidence:
            //
            //     adapter exposed 0 message(s) for index lookup
            //     checked box @[1333] rv=AIORecycleView pos=3 msg=null byIndex=false
            //
            // The list and the row index are correct; only the ACCESSORS fail, because QQ's list is
            // backed by a differ that exposes neither `getItem(int)` nor a list of messages.
            //
            // So the pairing is taken from where it genuinely exists: `QQHistoryHooks` hooks the bind
            // body (`AbsMsgListVB#y(holder, int, msg, List)`) for the history feature, and arg 0/arg 2
            // of that call are the row holder and its message. It records them, and this reads back.
            //
            // THE BRIDGE IS `findContainingViewHolder`, and it is the part that makes this work. The
            // registry is keyed by QQ's own holder object (not a View -- keying by View left it empty,
            // `row registry size=0`), and `RecyclerView.findContainingViewHolder(box)` is the
            // supported way to get that exact object from a view inside the row. `positionOf` already
            // proved this call succeeds on this device by returning `pos=3`.
            for (b in boxes) {
                if (!b.isChecked) continue
                val rv = reader?.findRecyclerView(b)
                val holder = if (rv != null) containmentHolder(rv, b) else null
                var msg = QQRowRegistry.get(holder)
                if (msg == null) msg = QQRowRegistry.find(b)
                Log.i(
                    TAG,
                    "QQ MS-TOOLBAR: ticked box @[" + visibleTop(b) + "] holder=" +
                        (holder?.javaClass?.name ?: "null") + " -> " +
                        (msg?.javaClass?.name ?: "null") + " (registry=" + QQRowRegistry.size() + ")",
                )
                if (msg == null) continue
                if (!out.contains(msg)) out.add(msg)
            }

            // Fallback, only when the registry has nothing at all: the old adapter route. Kept because
            // a build that changes the bind shape would otherwise lose the feature entirely, and a
            // partial answer beats none -- but the registry is what the device actually needs.
            if (out.isEmpty()) {
                for (b in boxes) {
                    if (!b.isChecked) continue
                    val rv = reader?.findRecyclerView(b)
                    val pos = if (rv != null) reader.positionOf(rv, b) else -2
                    val msg = if (rv != null && pos >= 0) reader.messageAt(rv, pos) else null
                    if (msg == null) continue
                    if (!out.contains(msg)) out.add(msg)
                }
            }

            // Fallback: the tag/field climb, for a build where the boxes are not inside the list.
            if (out.isEmpty()) {
                for (b in boxes) {
                    if (!b.isChecked) continue
                    val msg = messageFromCheckedRow(b) ?: continue
                    if (!out.contains(msg)) out.add(msg)
                }
            }
            Log.i(
                TAG,
                "QQ MS-TOOLBAR: " + out.size + " ticked message(s) resolved from " +
                    (boxes.count { it.isChecked }) + " ticked box(es)",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MS-TOOLBAR: reading the ticked messages failed: " + t.message)
        }
        return out
    }

    private fun collectCheckBoxes(v: View, into: MutableList<android.widget.CheckBox>, depth: Int) {
        if (depth > 30) return
        if (v is android.widget.CheckBox) {
            into.add(v)
            return
        }
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            collectCheckBoxes(c, into, depth + 1)
        }
    }

    /**
     * The on-screen Y of a view, for a diagnostic line that can be lined up with a screenshot.
     */
    private fun visibleTop(v: View): Int {
        val r = android.graphics.Rect()
        v.getGlobalVisibleRect(r)
        return r.top
    }

    /**
     * A short "Class#id<Class#id<..." of a view's ancestors, so a diagnostic can show WHERE a checked
     * box sits. The obfuscated class names are what identify whether the box is inside the message
     * list or somewhere else entirely.
     */
    /**
     * The RecyclerView ViewHolder containing [v], or null.
     *
     * This is the BRIDGE between the bind-time registry (keyed by QQ's holder object) and a ticked
     * `CheckBox` (an ordinary view). `findContainingViewHolder` is public androidx API, and
     * [QQMsgAccess.positionOf] already proves it succeeds on this device -- it returns `pos=3` for a
     * ticked row. `positionOf` discards the holder after reading its position; this keeps it.
     *
     * The method is looked up by walking the class chain, because QQ's list is `AIORecycleView extends
     * InnerAIORecycleView extends ... RecyclerView` and a `getDeclaredMethod` on the concrete class
     * alone would miss an inherited declaration.
     */
    private fun containmentHolder(rv: Any, v: View): Any? = try {
        var c: Class<*>? = rv.javaClass
        var m: java.lang.reflect.Method? = null
        while (c != null && m == null) {
            m = try {
                c.getDeclaredMethod("findContainingViewHolder", View::class.java)
            } catch (_: Throwable) {
                null
            }
            c = c.superclass
        }
        if (m == null) {
            null
        } else {
            m.isAccessible = true
            m.invoke(rv, v)
        }
    } catch (_: Throwable) {
        null
    }

    private fun describeAncestry(v: View): String {
        val sb = StringBuilder()
        var cur: View? = v
        var hops = 0
        while (cur != null && hops < 6) {
            if (hops > 0) sb.append('<')
            sb.append(cur.javaClass.simpleName)
            val id = cur.id
            if (id != View.NO_ID) {
                try {
                    sb.append('#').append(cur.resources.getResourceEntryName(id))
                } catch (_: Throwable) {
                }
            }
            cur = cur.parent as? View
            hops++
        }
        return sb.toString()
    }

    /** The message behind a ticked box: climb to the row and read the message out of it.
     *
     * WHY THE FIRST VERSION FOUND NOTHING (device log: `found 8 checkbox(es), 1 ticked,
     * 0 resolved to messages`). It only accepted a row whose tag was a `ui.e` MENU ITEM, because that
     * is where the long-press path finds the message. The chat page's own message rows are a different
     * code path and do not carry a menu item -- so every climb ended without a message.
     *
     * FOUR SOURCES ARE TRIED, cheapest and most specific first, and the one that worked is logged:
     *
     *   1. a menu item on any ancestor (`ui.e`) -- the long-press path's source, kept for the case
     *      where the host reuses menu rows inside the chat list;
     *   2. a tag that IS a message or carries one (any non-String tag, read with [messageFromItem]
     *      when a no-arg getter exists);
     *   3. the ViewHolder-style holder: many QQ rows keep their item in a field on the row's own
     *      class -- every `AIOMsgItem`-typed field on the ancestor's class is tried;
     *   4. `QQMsgAccess.messageFromView`, the module's existing view-to-message fallback.
     *
     * Each is bounded and non-throwing; a row that yields nothing contributes nothing rather than a
     * wrong message.
     */
    private fun messageFromCheckedRow(box: View): Any? {
        // 1 + 2: walk up looking for a tag that is not a String.
        var v: View? = box
        var hops = 0
        while (v != null && hops < 10) {
            val t = v.tag
            if (t != null && t !is String) {
                messageFromItem(t)?.let { return it }
                // The tag may BE the message (not a menu item wrapping one).
                if (looksLikeMessage(t)) return t
            }
            v = v.parent as? View
            hops++
        }

        // 3: an AIOMsgItem-typed field on the row class or any ancestor view's class.
        v = box
        hops = 0
        while (v != null && hops < 10) {
            msgFieldOn(v.javaClass)?.let { return it }
            v = v.parent as? View
            hops++
        }

        // 4: the module's existing fallback, which walks the view's own context.
        return try {
            msgAccess()?.messageFromView(box)
        } catch (_: Throwable) {
            null
        }
    }

    /** True when [o] is one of the host's message types, judged by the reader's own test. */
    private fun looksLikeMessage(o: Any?): Boolean = try {
        o != null && msgAccess()?.isMessage(o) == true
    } catch (_: Throwable) {
        false
    }

    /**
     * The first `AIOMsgItem`-typed instance field's value on [c], or null.
     *
     * QQ's rows hold their item in a field (`AIOBubbleMsgItemVB.e` is the row's root view, and the
     * neighbouring fields hold the item). Reading them is the only way in when neither a tag nor a
     * menu item is present, which is exactly the chat-list case this function exists for.
     */
    private fun msgFieldOn(c: Class<*>): Any? {
        var cls: Class<*>? = c
        var depth = 0
        while (cls != null && cls != Any::class.java && depth < 6) {
            for (f in cls.declaredFields) {
                val tn = f.type.name
                if (tn != "com.tencent.mobileqq.aio.msg.AIOMsgItem" &&
                    !tn.endsWith(".aio.data.msglist.a")
                ) {
                    continue
                }
                try {
                    f.isAccessible = true
                    val value = f.get(null) ?: continue
                    if (looksLikeMessage(value)) return value
                } catch (_: Throwable) {
                    // An instance field read with a null receiver throws; that is expected here and
                    // simply moves on to the next candidate.
                }
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    /** The QQ reader, created once, for the view-based fallbacks. */
    private var qqReader: QQMsgAccess? = null

    private fun msgAccess(): QQMsgAccess? {
        qqReader?.let { return it }
        return try {
            QQMsgAccess(resolver).also { qqReader = it }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Our toolbar entry's width, as a fraction of a host button's.
     *
     * The bar is already full at five buttons (measured 152px each across a 1272px screen), so a
     * sixth of the same width overflows the edge. Our label is shorter than the host's, so a narrower
     * slot both fits and still reads correctly.
     */
    private val TOOLBAR_WIDTH_SCALE = 0.80f

    /** The labels the multi-select bar's buttons carry; three matches identify the row. */
    private val TOOLBAR_LABELS = listOf(        "\u8f6c\u53d1",   // 转发
        "\u6536\u85cf",   // 收藏
        "\u622a\u56fe",   // 截图
        "\u5220\u9664",   // 删除
        "\u66f4\u591a",   // 更多
        "\u8f6c\u8d26",   // 转账 -- present in other multi-select contexts
    )

    private fun probeMultiSelect() {
        val uiState = loadClass(MULTISELECT_UI_STATE)
        val bar = loadClass(MULTISELECT_BAR)
        if (uiState == null) missing(MULTISELECT_UI_STATE, "multi-select UI state")
        if (bar == null) missing(MULTISELECT_BAR, "multi-select bar")
        Log.i(
            TAG,
            "QQ multi-select: bar=" + (bar != null) + " state=" + (uiState != null) +
                " -- READ-ONLY by design: the host's own " + QQMenuIdentity.HOST_KEY_MULTI_SELECT +
                " entry drives the mode, we only read the result",
        )
    }

    // ------------------------------------------------------------------ menu injection

    /**
     * Installs the menu injection, on BOTH possible layouts.
     *
     * TWO ANCHORS, deliberately, because the on-device probe reported that
     * `QQCustomMenuExpandableLayout#A()V` was never invoked. That contradicts the bytecode -- all four
     * strategy classes (`strategy.c/e/j/p#b`) call `A()`, and `strategy.c#b` constructs that very layout --
     * so the likeliest reading is that the menu the user saw was the OTHER NT layout,
     * [QQMenuIdentity.MENU_LAYOUT_ALT_CLASS], which has no `A()` at all.
     *
     * Anchoring on `setMenu(ui.c, View)` instead is layout-INDEPENDENT for this reason: it is declared by
     * BOTH NT layouts with an identical signature, and every strategy calls it before rendering. It is also
     * the point at which the container is stored, so the message for the row is available even if the row
     * itself cannot be appended.
     *
     * So both are hooked:
     *   * `A()` if it exists -- the render method, giving a finished tree to append a correctly styled row to;
     *   * `setMenu` on both layouts -- the guarantee that at least the MENU SEEN / message-capture path runs
     *     even when the row cannot be added, so a device log always says what happened.
     *
     * Returns true when at least one anchor is in place.
     */
    private fun installMenuHook(): Boolean {
        val itemCls = loadClass(QQMenuIdentity.ITEM_CLASS) ?: run {
            missing(QQMenuIdentity.ITEM_CLASS, "menu item")
            return false
        }
        var hooked = 0

        // --- anchor 1: the render method, when this layout has one
        val layout = loadClass(QQMenuIdentity.MENU_LAYOUT_CLASS)
        if (layout != null) {
            // REMEMBER `itemCls` FOR THE SHAPE-BASED HOOKS. They fire from a Lambda later, and the item
            // class is resolved per install; a captured local would be re-read from a stale scope.
            renderItemCls = itemCls
            // SHAPE FIRST, NAME SECOND. A hardcoded name is what silently disabled ALL menu injection on
            // QQ 9.1.97: the class was there, `A()` was not. See [installMenuRenderByShape].
            val shaped = installMenuRenderByShape(layout)
            hooked += shaped.size
            if (shaped.isNotEmpty()) {
                Log.i(
                    TAG,
                    "QQ MENU: render hooked by SHAPE on " + layout.simpleName + " (" + shaped.size +
                        " candidate(s)); the classic name " + QQMenuIdentity.LAYOUT_RENDER +
                        " is " + (if (shaped.contains(QQMenuIdentity.LAYOUT_RENDER)) "among them" else "absent here"),
                )
            }
            val render = layout.declaredMethods.firstOrNull {
                it.name == QQMenuIdentity.LAYOUT_RENDER && it.parameterCount == 0 &&
                    it.returnType == Void.TYPE
            }
            if (render != null && !shaped.contains(QQMenuIdentity.LAYOUT_RENDER)) {
                try {
                    LegacyHook.hook(render)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .interceptAfter { chain ->
                            try {
                                onMenuRendered(chain.thisObject, itemCls)
                            } catch (t: Throwable) {
                                Log.w(TAG, "QQ MENU: appending our row failed: " + t.message)
                            }
                        }
                    hooked++
                    Log.i(
                        TAG,
                        "QQ MENU: hooked " + layout.name + "#" + QQMenuIdentity.LAYOUT_RENDER +
                            "()V (AFTER) -- the render method all four menu strategies reach",
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "QQ MENU: hooking " + QQMenuIdentity.LAYOUT_RENDER + " failed: " + t.message)
                }
            } else if (shaped.isEmpty()) {
                Log.w(
                    TAG,
                    "QQ: " + layout.name + "#" + QQMenuIdentity.LAYOUT_RENDER +
                        "()V not found on this build AND no shape candidate could be hooked; " +
                        "relying on the setMenu anchor",
                )
            }
        } else {
            missing(QQMenuIdentity.MENU_LAYOUT_CLASS, "menu renderer")
        }

        // --- anchor 2: setMenu on BOTH NT layouts (layout-independent)
        for (name in listOf(QQMenuIdentity.MENU_LAYOUT_CLASS, QQMenuIdentity.MENU_LAYOUT_ALT_CLASS)) {
            val cls = loadClass(name)
            if (cls == null) {
                missing(name, "NT menu layout")
                continue
            }
            val setMenu = cls.declaredMethods.firstOrNull { m ->
                m.name == QQMenuIdentity.LAYOUT_SET_MENU && m.parameterCount == 2 &&
                    m.parameterTypes[0].name == QQMenuIdentity.MENU_CLASS
            }
            if (setMenu == null) {
                Log.w(TAG, "QQ MENU: " + cls.name + "#setMenu(ui.c, View) not found")
                continue
            }
            try {
                LegacyHook.hook(setMenu)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .interceptAfter { chain ->
                        try {
                            // The container is what setMenu was handed; our row can still be appended to it
                            // on the layouts whose render method we do not control.
                            onMenuContainerStored(chain.thisObject, chain.getArg(0), itemCls)
                        } catch (t: Throwable) {
                            Log.w(TAG, "QQ MENU: the setMenu anchor failed: " + t.message)
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "QQ MENU: hooked " + cls.name + "#setMenu(ui.c, View) (AFTER) -- layout-independent anchor",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "QQ MENU: hooking " + cls.name + "#setMenu failed: " + t.message)
            }
        }

        if (hooked == 0) {
            Log.w(TAG, "QQ MENU: NO menu anchor could be hooked, so the \u5206\u6790 row is not injected")
        }
        return hooked > 0
    }

    /**
     * The layout-independent anchor's handler: called right after ANY NT menu layout stores its container.
     *
     * WHAT IT GUARANTEES: that a device log always records the menu and captures the message, even on a
     * layout whose render method we cannot hook. That is the difference between "the row did not appear"
     * and "the feature never ran at all" -- the exact ambiguity that made the earlier probe inconclusive.
     *
     * DELIBERATELY DOES NOT APPEND A ROW HERE. At this moment the container exists but the layout has not
     * rendered yet, so there is no host row to model our look on and no parent view to add to. The append
     * belongs to the render anchor ([onMenuRendered]); this one only observes and captures.
     */
    private fun onMenuContainerStored(layout: Any?, container: Any?, itemCls: Class<*>) {
        if (layout is View) lastMenuAnchor = layout
        menusSeen++
        val message = firstMessageIn(container, itemCls)
        if (message != null) lastMenuMessage = message
        rememberOnce(
            "setMenuAnchor",
            "QQ MENU: setMenu anchor active on " + (layout?.javaClass?.name ?: "?") +
                " -- the layout-independent path observed a menu" +
                (if (message != null) " (message captured)" else " (no message on the container)"),
        )
    }

    /**
     * Reads the first message found among a container's items.
     *
     * The container is `ui.c` (field `a : List`, methods `e()I` count and `b(I)ui.e` get). Read by METHOD
     * NAME with a fallback to the single `List` field, so a rename cannot silently disable the capture:
     * the field is the only one on the class, so the by-type route is unambiguous.
     */
    private fun firstMessageIn(container: Any?, itemCls: Class<*>): Any? {
        if (container == null) return null
        val items: List<Any> = try {
            val sizeM = container.javaClass.methods.firstOrNull {
                it.name == QQMenuIdentity.MENU_SIZE && it.parameterCount == 0 &&
                    it.returnType == Integer.TYPE
            }
            val getM = container.javaClass.methods.firstOrNull {
                it.name == QQMenuIdentity.MENU_GET && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
            if (sizeM != null && getM != null) {
                val n = (sizeM.invoke(container) as? Int) ?: 0
                (0 until n).mapNotNull { i ->
                    try {
                        getM.invoke(container, i)
                    } catch (_: Throwable) {
                        null
                    }
                }
            } else {
                // Fallback: the container's `a : List` field, read by TYPE (the only List it declares).
                var list: List<Any> = emptyList()
                outer@ for (f in container.javaClass.declaredFields) {
                    if (!List::class.java.isAssignableFrom(f.type)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(container)
                        if (v is List<*>) {
                            list = v.filterNotNull()
                            break@outer
                        }
                    } catch (_: Throwable) {
                        // try the next field
                    }
                }
                list
            }
        } catch (_: Throwable) {
            emptyList()
        }
        for (item in items) {
            if (!itemCls.isInstance(item)) continue
            val m = messageFromItem(item)
            if (m != null) return m
        }
        return null
    }

    /**
     * Appends our 分析 row to a menu QQ has just rendered, and records the menu's message.
     *
     * The row is modelled on a REAL host row: its `TextView` supplies the text size, colour and typeface,
     * and its `ImageView` supplies the icon's layout params. Copying beats hardcoding because the menu's
     * geometry is theme- and density-dependent, and a hardcoded dp value would look wrong in one of night
     * mode / a large-font setting / a tablet.
     */
    // ------------------------------------------------------------------ version-independent menu anchor

    /**
     * Finds the long-press menu WITHOUT naming any QQ class, by hooking the framework's `PopupWindow`.
     *
     * ============================== WHY THIS EXISTS ==============================
     * On QQ 9.1.97 the named anchor failed in the quietest possible way. The install log reads
     *
     *     hooked com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout#A()V -- the render method
     *     >> qq hooks: done (menuHooked=true menusSeen=0 rowsInjected=0 taps=0 steps=6/6)
     *
     * `menuHooked=true` says the method was found and the hook installed successfully. `menusSeen=0`
     * says it was never CALLED. Every line of that report is healthy and the feature does not work --
     * exactly the failure mode 必看.md warns about, where "the hook installed" is mistaken for "the
     * hook runs".
     *
     * A named anchor is only valid for the class layout it was read from. The QQ long-press menu is a
     * `PopupWindow`, and that type is `android.widget.PopupWindow` on EVERY version of every app --
     * it is the framework's, not QQ's, so it cannot be renamed, moved or re-obfuscated. Hooking it
     * gives an anchor that a host update cannot invalidate.
     *
     * WHAT IS HOOKED, and why each one:
     *   * `PopupWindow#showAtLocation` and `PopupWindow#showAsDropDown` -- a menu that appears must
     *     call one of them, and by then the content view exists;
     *   * then the content view is examined for our target shape (see [tryInjectIntoPopupContent]).
     *
     * The hook runs AFTER the original, so the popup is on screen and its children are measured --
     * injecting before layout is what makes a row measure 0x0 and be invisible while the log says
     * "injected".
     */
    private fun watchForMenuPopup() {
        var hooked = 0
        for (name in POPUP_SHOW_METHODS) {
            val n = name.first
            val argc = name.second
            val cls = android.widget.PopupWindow::class.java
            val m = cls.declaredMethods.firstOrNull { it.name == n && it.parameterCount == argc }
            if (m == null) {
                Log.w(TAG, "QQ MENU-POPUP: PopupWindow#" + n + " with " + argc + " args not found")
                continue
            }
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            val popup = chain.thisObject as? android.widget.PopupWindow
                            val content = popup?.contentView
                            if (content != null) {
                                // Deferred: at show-time the content has not been laid out yet, and a
                                // child added now is measured 0x0 and invisible.
                                content.post { tryInjectIntoPopupContent(content, n) }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "QQ MENU-POPUP: " + n + " probe failed: " + t.message)
                        }
                    }
                hooked++
                Log.i(TAG, "QQ MENU-POPUP: hooked PopupWindow#" + n + " (" + argc + " args) -- a " +
                    "version-independent anchor")
            } catch (t: Throwable) {
                Log.w(TAG, "QQ MENU-POPUP: hooking PopupWindow#" + n + " failed: " + t.message)
            }
        }
        if (hooked == 0) {
            Log.w(
                TAG,
                "QQ MENU-POPUP: NO PopupWindow show method could be hooked, so this build has no " +
                    "version-independent menu anchor at all; only the named one is in play",
            )
        } else {
            popupWatcherArmed = true
        }
        installWindowProbe()
        installBubblePopupHook()
    }

    /**
     * Watches the framework's own window list and reports anything that appears in this process.
     *
     * ============================== WHY THE WINDOW LIST ==============================
     * QQ 9.1.97 defeated every other anchor, and each failure was different:
     *
     *   * the named render method `#A()V` does not exist (`... not found on this build`);
     *   * the shape-based render candidates `[G,H,n]` hook fine and never fire (`menusSeen=0`);
     *   * the `PopupWindow` show hooks install and never fire;
     *   * `WindowManagerGlobal#addView` is private and cannot be reflected into.
     *
     * Calling it "the menu is not a PopupWindow on this version" is a guess; reading the window list is
     * not. `WindowManagerGlobal` keeps every root view this process has added, and a root view's class
     * name says exactly what kind of surface it is. It is the framework's list, so -- like
     * `PopupWindow` -- a QQ update cannot rename it away.
     *
     * WHAT IT DOES: on a slow repeating check, walks the framework's root-view list and logs any root
     * whose class has not been reported yet. That is a DIAGNOSTIC whose output is meant to be read
     * after a long-press: it names the classes that actually appear, so the next fix can target one of
     * them instead of another name copied from a different QQ build.
     */
    private fun installWindowProbe() {
        try {
            val cls = loadClass(WINDOW_MANAGER_GLOBAL)
            if (cls == null) {
                Log.w(TAG, "QQ WINDOW-PROBE: " + WINDOW_MANAGER_GLOBAL + " not reachable")
                return
            }
            val getInstance = cls.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val instance = getInstance.invoke(null)
            if (instance == null) {
                Log.w(TAG, "QQ WINDOW-PROBE: WindowManagerGlobal.getInstance() returned null")
                return
            }
            val mViews = cls.getDeclaredField("mViews")
            mViews.isAccessible = true
            Log.i(TAG, "QQ WINDOW-PROBE: watching the framework's root-view list")
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        val views = mViews.get(instance)
                        val list = views as? List<*>
                        if (list != null) {
                            for (v in list) {
                                val view = v as? View ?: continue
                                val key = view.javaClass.name
                                // The dedup below is for the LOG ONLY. Using it to gate injection was a
                                // real bug: the menu window is the SAME class every time (`$e`), so from
                                // the second long-press on, `seenWindowClasses.add` returned false and the
                                // injection was skipped -- the button appeared on the first try and never
                                // again, which reads as "sometimes it works".
                                if (seenWindowClasses.add(key) && seenWindowClasses.size <= 40) {
                                    Log.i(
                                        TAG,
                                        "QQ WINDOW-PROBE: NEW ROOT VIEW -- " + key + " " +
                                            view.width + "x" + view.height + " children=" +
                                            (view as? ViewGroup)?.childCount + " (" +
                                            list.size + " root(s) alive)",
                                    )
                                }
                                // ============ INJECT FROM THE WINDOW LIST -- THIS IS THE FIX ============
                                //
                                // The window probe already FOUND the menu (device log: `NEW ROOT VIEW --
                                // com.tencent.qqnt.aio.widget.BubblePopupWindow$e 1005x536 children=3`)
                                // and it holds the live View. The bubble-popup hooks install and fire,
                                // but their receiver is null (`thisObject is null for b` -- `b` is a
                                // STATIC method on that class), so the hook has no object to work from.
                                //
                                // This path has the object and needs no receiver at all: the framework
                                // hands us every root view as it appears, so the menu is injected the
                                // moment its window exists -- by SHAPE, with no dependence on any QQ
                                // class name, method name, or even a QQ object reference.
                                if (view is ViewGroup && view.childCount > 0) {
                                    // ONLY if our row is not already in it. The check is by TAG on the
                                    // view itself, which is exact -- unlike the class-name dedup above,
                                    // which conflates "same kind of window" with "same window".
                                    if (view.findViewWithTag<View>(QQMenuIdentity.ROW_TAG_OURS) == null &&
                                        view.findViewWithTag<View>(QQMenuIdentity.ROW_TAG_OURS_BALLOON) == null
                                    ) {
                                        tryInjectIntoPopupContent(view, "window-list")
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    if (probeTicks++ < 1200) handler.postDelayed(this, 500L)
                }
            }
            handler.postDelayed(tick, 500L)
        } catch (t: Throwable) {
            Log.w(TAG, "QQ WINDOW-PROBE: install failed: " + t.message)
        }
    }

    /** Root-view classes already reported, so the probe reports each KIND once rather than per tick. */
    private val seenWindowClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Probe ticks spent, bounding the diagnostic's lifetime. */
    @Volatile
    private var probeTicks = 0

    /** The framework class that adds every window, including popups and dialogs. */
    private val WINDOW_MANAGER_GLOBAL = "android.view.WindowManagerGlobal"

    /** The framework class that adds every window, including popups and dialogs. */
    private val MENU_BUBBLE_CLASS = "com.tencent.qqnt.aio.widget.BubblePopupWindow"

    /**
     * Hooks QQ's OWN bubble popup -- the class the long-press menu actually uses on 9.1.97.
     *
     * ============================== HOW THIS WAS FOUND ==============================
     * Not by guessing a name and not by disassembling the 389 MB APK. The window probe read the
     * framework's live root-view list while the user held a long-press, and printed:
     *
     *     QQ WINDOW-PROBE: NEW ROOT VIEW --
     *         com.tencent.qqnt.aio.widget.BubblePopupWindow$e   1005x536   children=3
     *
     * That is the menu, named by the running process. The earlier four attempts all failed because each
     * one assumed a mechanism:
     *
     *   * the render method `#A()V` -- does not exist on 9.1.97 (`... not found on this build`);
     *   * the shape-based candidates `[G,H,n]` -- hook cleanly, never fire (`menusSeen=0`);
     *   * `android.widget.PopupWindow` show methods -- hook cleanly, never fire: QQ uses ITS OWN
     *     popup class, which does not call the framework's show methods;
     *   * `WindowManagerGlobal#addView` -- private, so not reflectable.
     *
     * The outer class is also the one `QQCustomMenuExpandableLayout` names in its own signature
     * (`setPopupWindow(BubblePopupWindow, f)`), which independently confirms it is the menu's host.
     *
     * WHAT IS HOOKED: the `show` entry points of that class, whichever exist, because a popup that
     * becomes visible must go through one of them. The inner class `$e` is the root VIEW we saw; the
     * outer one is what presents it, so both are tried.
     */
    private fun installBubblePopupHook() {
        var hookedNames = ArrayList<String>()
        for (clsName in listOf(MENU_BUBBLE_CLASS, MENU_BUBBLE_CLASS + "\$e")) {
            val cls = loadClass(clsName)
            if (cls == null) {
                Log.w(TAG, "QQ MENU-BUBBLE: " + clsName + " absent on this build")
                continue
            }
            // `show` is the conventional name; accept any parameter list, since the overloads differ
            // between QQ builds and the popup cannot appear without calling one of them.
            val shows = cls.declaredMethods.filter {
                it.name == "show" || it.name == "a" || it.name == "b"
            }
            for (m in shows) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                try {
                    LegacyHook.hook(m)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .intercept { chain ->
                            chain.proceed()
                            try {
                                val self = chain.thisObject
                                val content = when (self) {
                                    is android.widget.PopupWindow -> self.contentView
                                    is View -> self
                                    else -> null
                                }
                                if (content != null) {
                                    content.post { tryInjectIntoPopupContent(content, "bubble:" + m.name) }
                                } else {
                                    // The outer class is not a View: reach the root view it holds.
                                    val v = bubbleRootOf(self)
                                    if (v != null) {
                                        v.post { tryInjectIntoPopupContent(v, "bubble-field:" + m.name) }
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "QQ MENU-BUBBLE: " + m.name + " probe failed: " + t.message)
                            }
                        }
                    hookedNames.add(cls.simpleName + "#" + m.name + "/" + m.parameterCount)
                } catch (t: Throwable) {
                    Log.w(TAG, "QQ MENU-BUBBLE: hooking " + cls.simpleName + "#" + m.name + " failed: " + t.message)
                }
            }
        }
        Log.i(
            TAG,
            "QQ MENU-BUBBLE: hooked [" + hookedNames.joinToString(" ") +
                "] -- QQ's own bubble popup, found by reading the live window list",
        )
    }

    /**
     * The root `View` a `BubblePopupWindow` presents, found by walking its `View`-typed fields.
     *
     * The outer popup class is not itself a View (the window probe showed the ROOT VIEW as its inner
     * class `$e`), so the view has to be read out of it. Every field whose value is a `View` is tried
     * and the first `ViewGroup` wins, because that is what a container of menu rows must be.
     */
    private fun bubbleRootOf(o: Any?): View? {
        if (o == null) return null
        var cls: Class<*>? = o.javaClass
        var depth = 0
        while (cls != null && depth < 5) {
            for (f in cls.declaredFields) {
                if (!View::class.java.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(o) as? ViewGroup ?: continue
                    if (v.childCount > 0) return v
                } catch (_: Throwable) {
                }
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    /** True once the PopupWindow watcher is live, so the fallback is known to be available. */
    @Volatile
    private var popupWatcherArmed = false

    /**
     * Decides whether a popup that just appeared is QQ's long-press menu, and injects into it.
     *
     * THE SHAPE TEST, not the class name: the content must be a `ViewGroup` holding at least three
     * direct children that are all clickable and all carry a visible label. That is what a menu row is
     * on every QQ build examined, and it is also what many OTHER popups are, so the check is
     * deliberately conservative about what it touches: it never modifies the host's children, it only
     * APPENDS one of ours, and a popup that is not a menu simply looks untouched afterwards.
     *
     * Runs at most a handful of times per popup opening, and only when the named anchor has not already
     * done the job -- so on a version where the named anchor works, this changes nothing.
     */
    private fun tryInjectIntoPopupContent(content: View, via: String) {
        val root = content as? ViewGroup ?: return
        // ============ ONLY THE MENU WINDOW IS WORTH RETRYING, AND THE BUDGET IS PER WINDOW ============
        //
        // The device log shows exactly why the previous version gave up while the menu was right there:
        //
        //     attempt 12, root=DecorView 1272x2772 children=2 -- retrying
        //     ... after 12 attempts no window carried a host menu row
        //     QQ WINDOW-PROBE: NEW ROOT VIEW -- BubblePopupWindow$e 1005x536 children=3
        //
        // The shared attempt counter was consumed by the SCREEN's DecorView (which can never hold a
        // menu row), so by the time the ACTUAL menu window appeared at 1005x536 the budget was spent.
        // Two fixes, and both are needed:
        //
        //   1. the counter is now PER WINDOW (below), so one uninteresting window cannot starve another;
        //   2. the DecorView and other non-popup roots are skipped outright -- the menu is a popup, and
        //      scanning the whole screen on every tick is both useless and the reason the budget ran out.
        val isMenuish = root.javaClass.name.contains(MENU_WINDOW_MARKER)
        if (!isMenuish) return

        val prev = popupAttempts[content] ?: 0
        val attempt = prev + 1
        popupAttempts[content] = attempt
        if (attempt > POPUP_MAX_ATTEMPTS) return

        // Reuse the whole existing injection: the same host-row finder, the same look, the same message
        // reading and the same idempotence check. This fallback only supplies a DIFFERENT WAY IN -- the
        // menu itself is built by exactly the same code as before, so there is one behaviour to reason
        // about rather than two that can drift apart.
        val itemCls = loadClass(QQMenuIdentity.ITEM_CLASS)
        if (itemCls == null) {
            Log.w(TAG, "QQ MENU-POPUP: " + QQMenuIdentity.ITEM_CLASS + " is absent on this build")
            return
        }
        // SEARCH THE WHOLE SUBTREE, not just the direct children. The measured menu root
        // (`BubblePopupWindow$e`, 1005x536, children=3) is a WRAPPER -- the rows live below it, so a
        // direct-child scan finds no host row and the injection would report "not a menu" on a menu.
        //
        // TWO WAYS TO FIND A ROW, in order: by the `ui.e` tag (exact, and how 9.3.65 works), then by
        // SHAPE (icon + label in a clickable, laid-out container). The second exists because this is a
        // different QQ build and the tag assumption is exactly the kind of carry-over that has already
        // broken this feature twice -- see [findHostRowByShape].
        var hostRow = findHostRowDeep(root, itemCls, 0)
        var howFound = "tag"
        if (hostRow == null) {
            hostRow = findHostRowByShape(root, 0)
            howFound = "shape"
        }
        if (hostRow == null) {
            Log.i(
                TAG,
                "QQ MENU-POPUP: menu window " + root.javaClass.simpleName + " " +
                    root.width + "x" + root.height + " has no row yet (attempt " + attempt + "/" +
                    POPUP_MAX_ATTEMPTS + ", children=" + root.childCount + ", " +
                    descendants(root) + " descendant(s)) -- retrying",
            )
            // THE TREE IS DUMPED ON THE FIRST ATTEMPT, once per window. If the shape-based row search
            // keeps missing, the reason is in here: the class names and TAGS of what the menu actually
            // contains. That is the difference between "the rows are not built yet" (a timing problem,
            // and the retry loop handles it) and "the rows do not carry the tag we look for" (a shape
            // problem, which no amount of retrying fixes).
            if (attempt == 1) {
                Log.i(TAG, "QQ MENU-POPUP: window tree = " + treeSummary(root, 0))
            }
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.postDelayed({ tryInjectIntoPopupContent(root, via + "/retry") }, POPUP_RETRY_MS)
            return
        }
        popupInjections++
        Log.i(
            TAG,
            "QQ MENU-POPUP: a real menu via " + via + " (attempt " + attempt + ", found by " +
                howFound + ", hostRow=" + hostRow.javaClass.simpleName + " " +
                hostRow.width + "x" + hostRow.height + ") -- routing it through the normal injection",
        )
        onMenuRendered(root, itemCls)
    }

    /** Injection attempts per WINDOW, so one uninteresting window cannot starve another. */
    private val popupAttempts = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>(),
    )

    /** How many descendant views a tree holds, for a diagnostic that says "this is still empty". */
    private fun descendants(v: ViewGroup): Int {
        var n = 0
        for (i in 0 until v.childCount) {
            n++
            val c = v.getChildAt(i) as? ViewGroup ?: continue
            n += descendants(c)
        }
        return n
    }

    /**
     * A compact one-line-per-level description of a view tree: class, size, tag and text.
     *
     * The TAG and the TEXT are the two things that decide whether [findHostRow] can recognise a menu
     * row, so a dump that omits them cannot explain a miss. Bounded in depth and breadth so a large
     * popup cannot produce an unreadable log line.
     */
    private fun treeSummary(v: View, depth: Int): String {
        if (depth > 10) return ""
        val sb = StringBuilder()
        val r = android.graphics.Rect()
        v.getGlobalVisibleRect(r)
        sb.append('\n')
        repeat(depth.coerceAtMost(12)) { sb.append("  ") }
        sb.append(v.javaClass.simpleName)
        val t = v.tag
        if (t != null) sb.append(" tag=").append(t.javaClass.simpleName).append(':').append(t.toString().take(40))
        if (v is android.widget.TextView) {
            val s = v.text?.toString()?.trim() ?: ""
            if (s.isNotEmpty()) sb.append(" text='").append(s.take(20)).append('\'')
        }
        if (v is ViewGroup && v.childCount > 0) {
            sb.append(" n=").append(v.childCount)
            val g = v
            for (i in 0 until minOf(g.childCount, 8)) {
                g.getChildAt(i)?.let { sb.append(treeSummary(it, depth + 1)) }
            }
        }
        return sb.toString()
    }

    /**
     * The substring identifying the menu's window class: `...aio.widget.BubblePopupWindow$e`.
     *
     * Matched as a SUBSTRING rather than the full name on purpose -- the outer/inner split (`$e`) is an
     * obfuscation detail that can move, while `BubblePopupWindow` is the feature's own name and was
     * observed as the root view on the device at exactly the moment the menu appeared.
     */
    private val MENU_WINDOW_MARKER = "BubblePopupWindow"

    /** Retry budget per menu window, and the gap between tries. */
    private val POPUP_MAX_ATTEMPTS = 20
    private val POPUP_RETRY_MS = 100L

    /**
     * The message menu finds a row through the whole subtree, not only among direct children.
     *
     * WHY THE DEPTH MATTERS: the menu's window root is a wrapper (`BubblePopupWindow$e` holds 3 children,
     * none of which is a row), so a direct-child scan reports "no host row" on a real menu and the
     * injection silently does nothing -- which is exactly the symptom. [findHostRow] searches children
     * only; this walks down to find a row wherever it is nested.
     */
    private fun findHostRowDeep(v: ViewGroup, itemCls: Class<*>, depth: Int): View? {
        if (depth > 8) return null
        findHostRow(v, itemCls, 0)?.let { return it }
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i) as? ViewGroup ?: continue
            findHostRowDeep(c, itemCls, depth + 1)?.let { return it }
        }
        return null
    }

    /** How many popups were recognised as the real menu, so the log can be bounded. */
    @Volatile
    private var popupInjections = 0

    /**
     * Finds the menu's RENDER method by SHAPE, so a renamed render method cannot break injection.
     *
     * ============================== THE BUG THIS FIXES, FROM THE DEVICE ==============================
     * On QQ 9.1.97 the install log said, verbatim:
     *
     *     QQ: com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout#A()V not found on this build
     *     >> qq hooks: done (menuHooked=true menusSeen=0 rowsInjected=0 taps=0 steps=6/6)
     *
     * The class was present and its `setMenu` was found -- only the NAME we hardcoded from 9.3.65 was
     * gone. `dumpMenuMethods` then printed the class's real method table on 9.1.97:
     *
     *     void-no-arg methods=[G, H, dismiss, n, removeAllViews]
     *
     * Against 9.3.65's `[A, ...]`. So `A` became a one-letter name again, and hardcoding it is what
     * broke a whole QQ version while every log line still read healthy.
     *
     * ============================== HOW THE RENDER ONE IS CHOSEN ==============================
     * Candidates are the no-arg `void` methods minus the ones that CANNOT be the renderer:
     *
     *   * `dismiss`, `removeAllViews` are inherited framework/Android names and mean what they say;
     *   * a method that is never called when the menu opens is rejected by EVIDENCE, not by name:
     *     see [menuRenderCandidates] -- all plausible names are hooked, each logs which one fired, and
     *     the ones that fire are the render method on THIS build. Guessing a single name is what failed.
     *
     * Hooking several candidates is cheap and self-correcting: the real render method runs 2-3 times a
     * second while the menu animates open, and a wrong candidate simply never fires.
     */
    private fun installMenuRenderByShape(cls: Class<*>): List<String> {
        val candidates = cls.declaredMethods.filter { m ->
            m.parameterCount == 0 &&
                m.returnType == Void.TYPE &&
                m.name != "dismiss" &&
                m.name != "removeAllViews" &&
                !java.lang.reflect.Modifier.isStatic(m.modifiers)
        }
        val hookedNames = ArrayList<String>()
        for (m in candidates) {
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            onMenuRendered(chain.thisObject, renderItemCls ?: return@intercept)
                        } catch (t: Throwable) {
                            Log.w(TAG, "QQ MENU: render via #" + m.name + " threw: " + t.message)
                        }
                    }
                hookedNames.add(m.name)
            } catch (t: Throwable) {
                Log.w(TAG, "QQ MENU: hooking #" + m.name + "()V failed: " + t.message)
            }
        }
        Log.i(
            TAG,
            "QQ MENU: render candidates by SHAPE on " + cls.simpleName + " = [" +
                hookedNames.joinToString(",") + "] (the one that fires is the render method on this " +
                "build; a hardcoded name is what broke 9.1.97)",
        )
        return hookedNames
    }

    /** The `ui.e` class, resolved once, so the render callback can pass it on. */
    @Volatile
    private var renderItemCls: Class<*>? = null

    /** The `PopupWindow` entry points a shown menu must pass through: (method name, parameter count). */
    private val POPUP_SHOW_METHODS = listOf(
        // showAtLocation(View parent, int gravity, int x, int y) -- FOUR parameters, not three. The
        // first version listed 3 and the device log said `showAtLocation with 3 args not found`, so the
        // single most likely entry point for a menu popup was never hooked at all.
        "showAtLocation" to 4,
        "showAtLocation" to 3,
        "showAsDropDown" to 1,
        "showAsDropDown" to 2,
        "showAsDropDown" to 3,
        "showAsDropDown" to 4,
    )

    /**
     * Prints every method of the menu classes, so a RENAMED render method can be identified from the log.
     *
     * WHY THIS IS NEEDED, in one line from the device: on QQ 9.1.97 the install log reads
     *
     *     QQCustomMenuExpandableLayout#A()V not found on this build
     *
     * -- the CLASS is there (its `setMenu` was found and hooked) but the method we inject through is
     * gone. Guessing a replacement name is hopeless (it is R8-obfuscated), and downloading the 389 MB
     * APK to disassemble it is slow. Printing the class's own method table answers it directly: the
     * render method is the no-arg `void` method that the others call, and its neighbours are visible.
     */
    private fun dumpMenuMethods() {
        for (name in listOf(QQMenuIdentity.MENU_LAYOUT_CLASS, QQMenuIdentity.MENU_LAYOUT_ALT_CLASS)) {
            val cls = loadClass(name)
            if (cls == null) {
                Log.w(TAG, "QQ MENU-METHODS: " + name + " is ABSENT on this build")
                continue
            }
            val sb = StringBuilder()
            sb.append("QQ MENU-METHODS: ").append(cls.name)
                .append(" super=").append(cls.superclass?.name)
                .append(" -- ")
            val voidNoArg = ArrayList<String>()
            for (m in cls.declaredMethods) {
                val n = m.parameterCount
                if (m.returnType == Void.TYPE && n == 0) voidNoArg.add(m.name)
            }
            sb.append("void-no-arg methods=[").append(voidNoArg.joinToString(",")).append("] ")
            val twoArg = cls.declaredMethods
                .filter { it.parameterCount == 2 }
                .map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
            sb.append("2-arg=[").append(twoArg.joinToString(" ")).append("] ")
            Log.i(TAG, sb.toString())
            if (voidNoArg.size > 3) {
                Log.i(
                    TAG,
                    "QQ MENU-METHODS: " + cls.simpleName + " has " + voidNoArg.size +
                        " void-no-arg methods, so the render one cannot be picked by SHAPE alone on " +
                        "this build; the full list is above",
                )
            }
        }
    }

    private fun onMenuRendered(layout: Any?, itemCls: Class<*>) {
        val root = layout as? ViewGroup ?: return
        menusSeen++
        // ASSIGNED HERE, and this line is load-bearing: without it `lastMenuAnchor` stays null forever, so
        // the Activity can never be resolved and BOTH the single-message and the batch path would be dead
        // on a device while every log line still looked healthy. An earlier revision of this file declared
        // the field and never assigned it.
        lastMenuAnchor = root

        // Our row's anchor: find a host row first, so we can copy its look AND read its message.
        //
        // TWO FINDERS, and BOTH must be here. The tag-based one is exact and is how 9.3.65 works; the
        // shape-based one covers a build whose rows are not tagged the same way. Adding the shape finder
        // only to the popup ENTRY was a real mistake and the device showed it plainly:
        //
        //     QQ MENU-POPUP: a real menu via window-list (found by shape, hostRow=LinearLayout 191x242)
        //     QQ MENU: the menu rendered but no host row (a ui.e-tagged view) was found, so our row was
        //              NOT added
        //
        // The entry had found the row; this function then looked again its own way, failed, and gave up
        // -- so the menu was located twenty times in a row with nothing to show for it.
        var hostRow = findHostRow(root, itemCls, 0)
        if (hostRow == null) hostRow = findHostRowByShape(root, 0)
        if (hostRow == null) {
            rememberOnce(
                "noHostRow",
                "QQ MENU: the menu rendered but NO host row was found by tag OR by shape, so our row " +
                    "was NOT added. Tree: " + treeSummary(root, 0),
            )
            return
        }
        val parent = hostRow.parent as? ViewGroup ?: return

        // Idempotence per CONTAINER INSTANCE: a re-render creates a new container, so the check is on the
        // parent, not on a flag. A flag would read "already added" with the new menu row-less.
        //
        // BOTH of our tags are checked, not just the 分析 one: a previous render may have added the
        // balloon row and then failed before the analysis row, and checking one tag would then add a
        // second balloon row on the next pass (the menu's `A()` re-runs about three times a second
        // during its open animation, so a duplicate accumulates fast).
        if (parent.findViewWithTag<View>(QQMenuIdentity.ROW_TAG_OURS) != null ||
            parent.findViewWithTag<View>(QQMenuIdentity.ROW_TAG_OURS_BALLOON) != null
        ) {
            Log.i(TAG, "QQ MENU: our row is already in " + parent.javaClass.simpleName + "; not adding twice")
            return
        }

        val message = messageFromItem(hostRow.tag)
        if (message != null) lastMenuMessage = message

        // DIAGNOSTIC (v8.0.0, added after a device run): the row was reaching the container and the log
        // said `injected`, yet the user could not SEE it. Nothing in the previous log could tell those two
        // situations apart -- a row added to a finished layout is measured 0x0 and is invisible while every
        // line still reads healthy. These numbers are what separate "added" from "visible", so they are
        // printed BEFORE our own layout is touched.
        Log.i(
            TAG,
            "QQ MENU: layout root=" + root.javaClass.simpleName +
                " " + root.width + "x" + root.height +
                " childCount=" + root.childCount +
                " | hostRow=" + hostRow.javaClass.simpleName +
                " " + hostRow.width + "x" + hostRow.height +
                " lp=" + describeLp(hostRow.layoutParams) +
                " | parent=" + parent.javaClass.simpleName +
                " " + parent.width + "x" + parent.height +
                " childCount=" + parent.childCount,
        )

        val row = buildRow(hostRow, message) ?: return

        // THE MULTI-SELECT TOOLBAR WATCH STARTS HERE, and the placement is the fix for a real failure.
        //
        // The watcher cannot run at install time: the toolbar only exists while the user is in
        // multi-select, and the FIRST version required `lastMenuAnchor` at install and therefore
        // logged `no anchor view yet` forever without ever checking anything. The long-press menu is
        // the moment the Anchor becomes available AND the step that leads to multi-select (its 多选
        // entry is what enters the mode), so this is where the watch belongs.
        if (toolbarWatchArmed) {
            // Re-arm: a new menu means a NEW toolbar is about to be built, so the previous success no
            // longer says anything about the current one.
            toolbarInstalled = false
            startToolbarWatch()
        }

        // The row's OWN params, in the PARENT's type. See [copyLpFor]: a bare `ViewGroup.LayoutParams`
        // is accepted by every container but drops the container-specific fields, and in the menu's
        // `LinearLayout` row container that is how a row ends up measured but never placed. The host
        // row's params are the reference because they are, by construction, params this exact parent
        // already accepted and laid out correctly.
        hostRow.layoutParams?.let { hp ->
            row.layoutParams = copyLpFor(parent, hp)
        }

        // OUR SECOND ROW: 浮窗 (the floating-balloon switch), matching the other four hosts.
        //
        // Built through the SAME factory as 分析 so the two cannot drift apart, and tagged
        // separately so the idempotence check below does not mistake one for the other.
        // A null balloon row is not fatal: the 分析 row is the feature the user asked for first, so a
        // failure here degrades to "one entry instead of two" rather than losing the whole menu.
        val balloonRow = buildRowFor(
            hostRow,
            QQMenuIdentity.OUR_BALLOON_LABEL,
            QQMenuIdentity.ROW_TAG_OURS_BALLOON,
        ) { onBalloonRowTapped() }
        balloonRow?.let { hostRow.layoutParams?.let { hp -> it.layoutParams = copyLpFor(parent, hp) } }

        try {
            // INDEX 0, not append -- this is the B change the user asked for.
            //
            // WHY: the device log showed the row added, measured (191x242) and VISIBLE, yet the user
            // could not see it. The host lays its own rows out at a FIXED width, so a row appended
            // after them is pushed past the container's edge and clipped. Inserting at index 0 puts
            // our row FIRST, at the left edge, where it cannot be clipped by its own siblings.
            // `addView(view, index, params)` is used because the 2-arg `addView(view, index)` overload
            // would re-derive layout params and discard the ones set just above.
            //
            // ORDER: 浮窗 goes in at 0 first, then 分析 at 0, so the finished row reads
            //     分析 | 浮窗 | 复制 | 转发 | ...
            // which is the order the user asked for. Inserting 分析 first would put 浮窗 ahead of it.
            val balloonLp = balloonRow?.layoutParams
            if (balloonRow != null) {
                if (balloonLp != null) {
                    parent.addView(balloonRow, 0, balloonLp)
                } else {
                    parent.addView(balloonRow, 0)
                }
            }
            val lp = row.layoutParams
            if (lp != null) {
                parent.addView(row, 0, lp)
            } else {
                parent.addView(row, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MENU: adding our row to " + parent.javaClass.name + " failed: " + t.message)
            return
        }

        // ROW-1 TRIM: adding our rows made the first row too wide, which pushed the host's LAST item
        // off the container's right edge. The user reported exactly that: "the analysis button squeezed
        // the fifth button (multi-select) out of the first row".
        //
        // See [trimRow] for the fix and for the measured geometry behind it.
        //
        // Deferred to `post` because child widths and the sibling rows are only final after layout.
        parent.post {
            try {
                trimRow(parent)
            } catch (t: Throwable) {
                // Never let the trim break the injection that already succeeded.
                Log.w(TAG, "QQ MENU: row-1 trim failed: " + t.message)
            }
        }

        // WHY THE REQUEST PASSES BELOW ARE NEEDED -- this is the fix for the "injected but invisible"
        // device report, and each line addresses a different way the row can end up 0x0:
        //
        //   * `A()` finishes by laying out the menu, so a row added AFTER it returns has never been
        //     measured. Without a re-measure its width and height stay 0 and it paints nothing.
        //   * The chain is walked with `requestLayout()` because a `requestLayout` only reaches the
        //     parent of the view it is called on; asking the immediate parent alone can stop below the
        //     menu root, which is the view whose `onMeasure` is overridden.
        //   * `invalidate()` is separate from `requestLayout()`: a view already laid out with the correct
        //     size still needs a redraw to appear.
        run {
            row.requestLayout()
            var v: View? = parent
            var hops = 0
            while (v != null && hops < 8) {
                v.requestLayout()
                v.invalidate()
                v = v.parent as? View
                hops++
            }
        }

        injected++
        com.luoliaoo.wechat.core.ModuleStats.noteMenuInjected()
        Log.i(
            TAG,
            "QQ MENU: injected '" + QQMenuIdentity.OUR_ITEM_LABEL + "' row #" + injected +
                " into " + parent.javaClass.simpleName +
                " (message=" + (message?.javaClass?.simpleName ?: "unknown") +
                ", menusSeen=" + menusSeen +
                ", lp=" + describeLp(row.layoutParams) +
                ")",
        )

        // SECOND DIAGNOSTIC: measured one frame later. This is the line that answers "did it become
        // visible?" without a screenshot, which matters because the module cannot capture one from inside
        // the host process. A 0x0 here means the measure pass did not pick our row up.
        row.post {
            try {
                Log.i(
                    TAG,
                    "QQ MENU: our row measured " + row.width + "x" + row.height +
                        " visibility=" + row.visibility +
                        " attached=" + row.isAttachedToWindow +
                        " parentChildCount=" + parent.childCount +
                        " onScreen=" + isOnScreen(row, root) +
                        " loc=" + locationOf(row) +
                        " parentLoc=" + locationOf(parent) +
                        " rootSize=" + root.width + "x" + root.height,
                )
                // Full geometry dump: this is what turns "the row says VISIBLE" into an actual answer
                // about whether the user CAN see it. A row that is VISIBLE, measured and attached can
                // still be drawn entirely outside its container (clipped) or behind a sibling -- and
                // neither is visible in any of the numbers printed above on their own.
                dumpGeometry(root, "after-inject")
            } catch (_: Throwable) {
                // A diagnostic must never be able to break the feature it describes.
            }
        }
    }

    /** Where a view sits in its WINDOW, which is what "can the user see it" actually depends on. */
    private fun locationOf(v: View): String {
        return try {
            val xy = IntArray(2)
            v.getLocationInWindow(xy)
            "[" + xy[0] + "," + xy[1] + "]"
        } catch (_: Throwable) {
            "[?]"
        }
    }

    /**
     * True when [v]'s window rectangle actually intersects [root]'s.
     *
     * This is the test that separates the two failures that look identical in a log: a row correctly
     * placed (intersects) versus a row pushed outside its container by siblings that were laid out
     * before it (does not intersect). Both read `visibility=VISIBLE attached=true`.
     */
    private fun isOnScreen(v: View, root: View): Boolean {
        return try {
            val a = IntArray(2)
            val b = IntArray(2)
            v.getLocationInWindow(a)
            root.getLocationInWindow(b)
            val vLeft = a[0]
            val vRight = a[0] + v.width
            val rLeft = b[0]
            val rRight = b[0] + root.width
            val intersects = vRight > rLeft && vLeft < rRight
            intersects
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Logs the menu's real view tree, with each node's on-window rectangle.
     *
     * Kept deliberately verbose and bounded: the point is to see WHERE our row landed relative to its
     * siblings, and a truncated dump of `class left,top WxH vis text` is enough to spot both clipping
     * (our right edge past the container's) and ordering. A one-off diagnostic that answers the question
     * completely beats several rounds of guessing.
     */
    private fun dumpGeometry(root: View, why: String) {
        try {
            val sb = StringBuilder("QQ MENU geometry (" + why + "): root=" + root.javaClass.simpleName +
                " " + root.width + "x" + root.height + " " + locationOf(root) + "\n")
            var count = 0
            fun walk(v: View, depth: Int, indexInParent: Int) {
                if (count > 30 || depth > 6) return
                count++
                val xy = IntArray(2)
                v.getLocationInWindow(xy)
                val tag = try {
                    v.tag
                } catch (_: Throwable) {
                    null
                }
                val marker = when {
                    tag == QQMenuIdentity.ROW_TAG_OURS -> "  <<< OURS"
                    tag != null -> "  (tag=" + tag.javaClass.simpleName + ")"
                    else -> ""
                }
                val text = (v as? TextView)?.text?.toString()?.take(8) ?: ""
                sb.append("  ".repeat(depth + 1))
                    .append("#").append(indexInParent).append(' ')
                    .append(v.javaClass.simpleName)
                    .append(' ').append(xy[0]).append(',').append(xy[1])
                    .append(' ').append(v.width).append('x').append(v.height)
                    .append(" vis=").append(v.visibility)
                    .append(if (text.isNotEmpty()) " \"" + text + "\"" else "")
                    .append(marker)
                    .append('\n')
                val g = v as? ViewGroup ?: return
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i) ?: continue
                    walk(c, depth + 1, i)
                }
            }
            walk(root, 0, 0)
            Log.i(TAG, sb.toString().trimEnd())
        } catch (_: Throwable) {
            // Diagnostics must never break the feature they describe.
        }
    }

    /** Renders LayoutParams readably: class, size and (when present) margins. */
    private fun describeLp(lp: ViewGroup.LayoutParams?): String {
        if (lp == null) return "null"
        val margins = (lp as? ViewGroup.MarginLayoutParams)
            ?.let { "[m=" + it.leftMargin + "," + it.topMargin + "," + it.rightMargin + "," + it.bottomMargin + "]" }
            ?: ""
        return lp.javaClass.simpleName + "(" + lp.width + "x" + lp.height + ")" + margins
    }

    // ------------------------------------------------------------------ row-1 trim

    /**
     * Removes from OUR row any label that the menu ALSO shows in a SIBLING row.
     *
     * THE PROBLEM THIS SOLVES, measured on the user's device: our two rows made the first row one
     * item wider than its container, so the host's own LAST first-row item (its 多选 entry) was
     * pushed past the right edge and could not be tapped. The user reported it as
     * "the analysis button squeezed the fifth button (multi-select) out of the first row".
     *
     * WHY THIS IS A RULE AND NOT TWO HARDCODED LABELS: QQ already shows 删除 and 多选 a SECOND time
     * in its lower row. The user's own account of the menu is
     *     row 1: 复制 转发 部分选中 删除 多选
     *     row 2: 多选 删除 引用 收藏 [+ an expand button]
     * so dropping a duplicate from our row takes NO feature away -- the entry stays where it already
     * was, one row down. That property is what makes this safe, and it is CHECKED rather than
     * assumed: a label is only removed when the same label is found in a sibling row on this very
     * menu. If QQ ever stops duplicating them, nothing matches and nothing is removed -- which is
     * the old (merely crowded) behaviour, not a new breakage.
     *
     * It deliberately never touches:
     *   * our own rows (skipped by tag),
     *   * any sibling row (only reads them),
     *   * any label that appears in only one place.
     */
    private fun trimRow(ourRow: ViewGroup) {
        // MEASURED STRUCTURE, from the module's own geometry dump on the user's device:
        //
        //   row 1 = [ours:分析] [ours:浮窗] 复制 转发 部分选中 删除 多选    7 rows x 191px = 1337px
        //   row 1 container width                                            967px  <-- OVERFLOWS by 370px
        //   row 2 = 引用 收藏 ...                        (tag `am`; NOT a repeat of row 1)
        //
        // The overflow is the whole bug: with 1337px of rows in a 967px container, the rows past the
        // edge -- 删除 and 多选, the LAST TWO -- cannot be reached. That is the user's report, "the
        // analysis button squeezed the fifth button (multi-select) out of the first row".
        //
        // HISTORY, so the next editor does not repeat it: an earlier version of this function tried to
        // REMOVE row-1 items whose label ALSO appeared in the sibling row 2. It removed 0 items, because
        // the premise was wrong -- row 2 holds 引用/收藏, not 删除/多选. The two rows are genuinely
        // different, so nothing is duplicated and nothing may be deleted. Verified against the same
        // geometry dump: row 2's only child carries tag `am`, and no 删除/多选 row exists outside row 1.
        //
        // THE FIX INSTEAD: make the row FIT. Every row in this container is a LinearLayout child whose
        // width is set to a fixed pixel count, so the row cannot reflow on its own. We recompute that
        // width from the container's real width and the number of children, and apply it to EVERY child
        // (both of ours included). All seven entries stay present and tappable; they simply share the
        // width evenly. Device-verified: "container=967px, rows=7 -> each=138px, resized=7", and
        // 7 x 138 = 966 <= 967, so the row now fits with 1px to spare.
        //
        // Widths, not visibility, is the right lever because the host sizes each row for its own five
        // entries -- adding two more means the total no longer matches the container, and only the width
        // controls the total. It is also self-limiting: if a future QQ build changes the row count, the
        // arithmetic simply produces a different even split, and if the container is not yet measured
        // (`width <= 0`) we leave the host's own sizing completely alone.
        val count = ourRow.childCount
        val total = ourRow.width
        if (count <= 0 || total <= 0) {
            Log.i(
                TAG,
                "QQ MENU: row-1 fit skipped (count=" + count + " width=" + total + ") -- " +
                    "leaving the host's own sizing alone",
            )
            return
        }

        val each = total / count
        var resized = 0
        for (i in 0 until count) {
            val child = ourRow.getChildAt(i) ?: continue
            val lp = child.layoutParams ?: continue
            if (lp.width != each) {
                lp.width = each
                child.layoutParams = lp
                resized++
            }
        }

        ourRow.requestLayout()
        Log.i(
            TAG,
            "QQ MENU: row-1 fit -- container=" + total + "px, rows=" + count +
                " -> each=" + each + "px, resized=" + resized,
        )
    }

    /**
     * Labels of every TextView inside [v] while skipping [skip]'s subtree.
     *
     * [skip] is our own row: its labels are the ones we are deciding about, so counting them as
     * "available elsewhere" would make every one of our rows delete itself.
     */
    private fun collectLabels(v: View, skip: View, into: MutableSet<String>) {
        if (v === skip) return
        val text = labelOf(v)
        if (text != null) into.add(text)
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            collectLabels(c, skip, into)
        }
    }

    /** A row's own label: the text of the TextView it contains, or null when it has none. */
    private fun labelOf(v: View): String? {
        val direct = (v as? TextView)?.text?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct
        val tv = findByType(v, TextView::class.java, 0) ?: return null
        return tv.text?.toString()?.trim()?.ifEmpty { null }
    }

    /** True when [v] is one of OUR injected rows (never a host row). */
    private fun isOurRow(v: View): Boolean = try {
        QQMenuIdentity.isAnyOfOurRowTags(v.tag)
    } catch (_: Throwable) {
        false
    }

    /**
     * Finds a host row: a view whose tag is a `ui.e`.
     *
     * The tag IS the item -- `QQCustomMenuExpandableLayout$c#onClick` reads `View#getTag()`, checks
     * `instance-of ui.e` and calls `ui.e#k()` on it, so the renderer must have stored the item there. This
     * makes "a row" identifiable by structure rather than by a repeated resource id: the device dump shows
     * every row-sharing id (`mh_`, `p2r`, `root`, ...) repeated 7 times, so ids cannot be used, while the
     * tag is exact.
     */
    private fun findHostRow(root: View, itemCls: Class<*>, depth: Int): View? {
        if (depth > 40) return null
        val tag = try {
            root.tag
        } catch (_: Throwable) {
            null
        }
        if (tag != null && itemCls.isInstance(tag)) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            findHostRow(child, itemCls, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * A menu row found by STRUCTURE, for a build that no longer tags its rows with `ui.e`.
     *
     * ============================== WHY A SECOND WAY TO FIND A ROW ==============================
     * [findHostRow] identifies a row by its TAG being a `ui.e` instance, and that is exact when it works:
     * on 9.3.65 a row's tag IS its menu item (`QQCustomMenuExpandableLayout$c#onClick` reads
     * `View#getTag()` and casts to `ui.e`). But 9.1.97 is a DIFFERENT BUILD with a renamed render method
     * (`A` -> `G/H/n`), so the assumption that its rows are tagged the same way is exactly the kind of
     * carry-over that has already broken this feature twice.
     *
     * The fallback recognises the row by the SHAPE the host's own builder produces:
     *
     *     LinearLayout(VERTICAL)  ->  ImageView  +  TextView
     *
     * which is documented in [buildRow] and comes from `QQCustomMenuExpandableLayout#r`. A clickable,
     * laid-out container holding an icon and a short label is a menu row on any build of this menu.
     *
     * It returns the OUTERMOST such view, so our entry is inserted beside the host's rows rather than
     * inside one.
     */
    private fun findHostRowByShape(root: View, depth: Int): View? {
        if (depth > 30) return null
        val g = root as? ViewGroup ?: return null
        if (looksLikeMenuRow(g)) return g
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            findHostRowByShape(c, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * True when [g] has the structure of one menu row: a laid-out, clickable container holding both an
     * `ImageView` and a non-empty `TextView`.
     *
     * The `width > 0` requirement is what keeps this from matching a row that has not been laid out yet:
     * an unmeasured row is not something our clone can copy a size from, and accepting one is how a row
     * ends up 0x0 and invisible while the log says "injected".
     */
    private fun looksLikeMenuRow(g: ViewGroup): Boolean {
        var icon: View? = null
        var label: String? = null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            when (c) {
                is android.widget.ImageView -> if (icon == null) icon = c
                is android.widget.TextView -> {
                    val t = c.text?.toString()?.trim() ?: ""
                    if (t.isNotEmpty() && t.length <= 8) label = t
                }
            }
        }
        if (icon == null || label == null) return false
        if (g.width <= 0 || g.height <= 0) return false
        return g.isClickable || g.isLongClickable || label.isNotEmpty()
    }

    /**
     * Builds our row by cloning a host row's visual shape.
     *
     * Shape, from `QQCustomMenuExpandableLayout#r`: a `LinearLayout` (VERTICAL) holding an `ImageView`
     * then a `TextView`, with `setText` from `ui.e#h()` and `setImageResource` from `ui.e#b()`. We
     * reproduce that layout so our row is indistinguishable in geometry from the host's, then replace the
     * text, the icon and the click behaviour with ours.
     */
    private fun buildRow(hostRow: View, message: Any?): View? =
        buildRowFor(hostRow, QQMenuIdentity.OUR_ITEM_LABEL, QQMenuIdentity.ROW_TAG_OURS) {
            onOurRowTapped(message)
        }

    /**
     * Builds ONE of our menu rows, cloning a host row's visual shape.
     *
     * SHARED BY BOTH ENTRIES -- 分析 and 浮窗 -- through this one factory, exactly as `DouyinHooks`
     * does ("Build ONE button per entry, through a shared factory, so 分析 and 浮窗 cannot drift").
     * Two hand-written builders is how the two rows end up with different text sizes, paddings or
     * icon scaling after a later edit touches only one of them.
     *
     * Shape, from `QQCustomMenuExpandableLayout#r`: a `LinearLayout` (VERTICAL) holding an `ImageView`
     * then a `TextView`, with `setText` from `ui.e#h()` and `setImageResource` from `ui.e#b()`. We
     * reproduce that layout so our rows are indistinguishable in geometry from the host's.
     */
    private fun buildRowFor(
        hostRow: View,
        label: String,
        tag: String,
        onClick: () -> Unit,
    ): View? {
        val hostText = findByType(hostRow, TextView::class.java, 0) ?: run {
            rememberOnce(
                "noHostText",
                "QQ MENU: the host row has no TextView, so our row could not be styled like it; " +
                    "no row was added",
            )
            return null
        }
        val ctx: Context = hostRow.context ?: return null

        val text = TextView(ctx).apply {
            text = label
            textSize = hostText.textSize / ctx.resources.displayMetrics.scaledDensity
            setTextColor(hostText.currentTextColor)
            typeface = hostText.typeface
            gravity = hostText.gravity
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = hostText.includeFontPadding
            setPaddingRelative(
                hostText.paddingStart, hostText.paddingTop,
                hostText.paddingEnd, hostText.paddingBottom,
            )
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            // OUR marker. The tag is what makes idempotence per-container work and is also our
            // identity. Each of our rows gets its OWN tag so the two are told apart: sharing one tag
            // would make "is our row already here?" answer yes for either and silently drop the
            // second entry.
            //
            // `this.tag`, not `tag`: inside `apply {}` the receiver's own `View.tag` shadows the
            // `tag: String` parameter of this function, so a bare `tag = tag` assigns the val to
            // itself and fails to compile. `DouyinHooks.kt:2825` uses `this.tag = tag` for the same
            // reason.
            this.tag = tag
            setOnClickListener { onClick() }
        }

        // The icon, reusing the module's existing mechanism so the QQ row matches every other host.
        val hostIcon = findByType(hostRow, ImageView::class.java, 0)
        val sizePx = hostIcon?.let { if (it.width > 0) it.width else it.layoutParams?.width ?: 0 } ?: 0
        val iconPx = if (sizePx in 1..4096) sizePx else dpToPx(ctx, ICON_DP)
        try {
            val icon = ImageView(ctx).apply {
                setScaleType(ImageView.ScaleType.FIT_CENTER)
                setImageDrawable(com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, iconPx))
            }
            if (hostIcon != null && hostIcon.layoutParams != null) {
                row.addView(icon, copyLpFor(row, hostIcon.layoutParams))
            } else {
                row.addView(icon)
            }
        } catch (t: Throwable) {
            // An icon is cosmetic: a text-only row is still a working row, so this must not abort.
            Log.w(TAG, "QQ MENU: our row's icon could not be built: " + t.message)
        }

        row.addView(text, hostText.layoutParams?.let { copyLpFor(row, it) })
        return row
    }

    /** The first descendant (or self) of the given type. */
    private fun <T : View> findByType(root: View, cls: Class<T>, depth: Int): T? {
        if (depth > 40) return null
        if (cls.isInstance(root)) {
            @Suppress("UNCHECKED_CAST")
            return root as T
        }
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            findByType(child, cls, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Copies layout params so the TARGET parent's own type is used.
     *
     * WHY THIS REPLACED A PLAIN `ViewGroup.LayoutParams` COPY: the old version returned
     * `ViewGroup.LayoutParams(w, h)`, which every `ViewGroup` accepts but which carries no
     * container-specific fields. In a `LinearLayout` that silently drops `layout_weight`, and in a
     * `RelativeLayout` it drops every rule -- so the row could be added, measured and still land
     * somewhere invisible or at zero size. That is one of the two ways the row reached the menu and
     * the user still saw nothing (the other is the missing re-measure in [onMenuRendered]).
     *
     * The target type is chosen by asking the parent what it would generate itself, so this stays
     * correct if QQ changes the row container (the container is `QQCustomMenuExpandableLayout$e`,
     * a `LinearLayout`, but nothing here assumes that).
     */
    private fun copyLpFor(parent: ViewGroup, lp: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        // `ViewGroup.generateLayoutParams` is PROTECTED, so it cannot be called directly -- a first
        // attempt did exactly that and failed to compile: "it is protected in 'android/view/ViewGroup'".
        // Reflection reaches it without widening anything.
        val made: ViewGroup.LayoutParams? = try {
            val m = ViewGroup::class.java.getDeclaredMethod(
                "generateLayoutParams",
                ViewGroup.LayoutParams::class.java,
            )
            m.isAccessible = true
            m.invoke(parent, lp) as? ViewGroup.LayoutParams
        } catch (_: Throwable) {
            null
        } ?: guessLpFor(parent, lp)
        if (made is ViewGroup.MarginLayoutParams && lp is ViewGroup.MarginLayoutParams) {
            made.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        }
        // `made` is provably non-null here: the `?:` above assigns [guessLpFor]'s result whenever the
        // reflective call yields null, and [guessLpFor] always returns a value. The `!!` states that to
        // the compiler instead of widening this function's return type for every caller.
        return made!!
    }

    /**
     * Builds params of the parent's OWN nested `LayoutParams` class, for when reflection is unavailable.
     *
     * Walks up from the parent's class looking for `<ClassName>$LayoutParams`, which is how every
     * framework container names it. The walk matters because the menu's row container is a SUBCLASS
     * (`QQCustomMenuExpandableLayout$e extends LinearLayout`) that declares no `LayoutParams` of its
     * own, so the first hit lands on a superclass. If nothing matches, the plain type is returned --
     * still accepted by `addView`, so the worst case is the previous behaviour, not a crash.
     */
    private fun guessLpFor(parent: ViewGroup, lp: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        var c: Class<*>? = parent.javaClass
        while (c != null && c != ViewGroup::class.java) {
            try {
                val nested = Class.forName(c.name + "\$LayoutParams")
                if (ViewGroup.LayoutParams::class.java.isAssignableFrom(nested)) {
                    val ctor = nested.getConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    ctor.isAccessible = true
                    return ctor.newInstance(lp.width, lp.height) as ViewGroup.LayoutParams
                }
            } catch (_: Throwable) {
                // No nested type at this level; keep walking up.
            }
            c = c.superclass
        }
        return ViewGroup.LayoutParams(lp.width, lp.height)
    }

    // NOTE: an earlier `cloneLp(lp)` helper was DELETED here. It returned a bare
    // `ViewGroup.LayoutParams(w, h)`, which `addView` accepts but which carries no container-specific
    // fields -- so in a LinearLayout it dropped `layout_weight` and in a RelativeLayout it dropped every
    // rule, leaving a row that is added and measured yet invisible. [copyLpFor] replaced it by asking the
    // PARENT for its own params type. Keeping both would leave a silent trap for the next editor.

    private fun dpToPx(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()

    /**
     * `ui.e#d()` -> the item's message.
     *
     * The GETTER, never a field: `AIOMsgItem` holds two `MsgRecord`-typed fields, so reading by letter
     * would be a coin flip. `qq_invoke.py` shows the host itself reaches its message exactly this way
     * (`menu.aq#k()`, `MenuMultiSelectProcessor#a`).
     */
    /**
     * The message a host menu item carries, found by WALKING THE SUPERCLASS CHAIN.
     *
     * THIS IS THE FIX for "tapping 分析 does nothing". The device log said, on every menu since the
     * first:
     *
     *     QQ MENU: com.tencent.qqnt.aio.menu.ui.e#d() not resolved: com.tencent.qqnt.aio.menu.h.d []
     *
     * `getDeclaredMethod` searches ONE class -- the concrete item (`...menu.h`) -- and `d()` is not
     * declared there: it is declared on the abstract base `...menu.ui.e` and inherited. So the lookup
     * threw on every single menu, `lastMenuMessage` stayed null forever, and the row was built with
     * `message=unknown`. It was invisible until now because the tap used to fall into the BATCH path,
     * which does not need a single message -- so disabling the bogus selection accessor (which was
     * necessary and correct) exposed this older bug underneath it.
     *
     * WALKING THE CHAIN, not `getMethod`: `getMethod` finds public methods anywhere in the hierarchy
     * but requires PUBLIC, and these are obfuscated members whose access flags differ per QQ build.
     * Walking `getDeclaredMethod` level by level with `isAccessible = true` finds them regardless.
     */
    private fun messageFromItem(item: Any?): Any? {
        if (item == null) return null
        var c: Class<*>? = item.javaClass
        var depth = 0
        while (c != null && depth < 12) {
            try {
                val m = c.getDeclaredMethod(QQMenuIdentity.ITEM_MESSAGE)
                if (m.parameterCount == 0 && m.returnType != Void.TYPE) {
                    m.isAccessible = true
                    val v = m.invoke(item)
                    if (v != null) {
                        // Logged once per process: this is the line that proves the message reached
                        // the menu row, and without it a future break here looks exactly like a dead
                        // tap with no explanation.
                        rememberOnce(
                            "itemMessageOk",
                            "QQ MENU: the message for our row came from " + c.name + "#" +
                                QQMenuIdentity.ITEM_MESSAGE + "() -> " + v.javaClass.name +
                                " (found by walking up from " + item.javaClass.name + ")",
                        )
                        return v
                    }
                    return null
                }
            } catch (_: Throwable) {
                // Not declared at this level; keep walking. A miss here is expected, not an error.
            }
            c = c.superclass
            depth++
        }
        rememberOnce(
            "itemMessage",
            "QQ MENU: no no-arg " + QQMenuIdentity.ITEM_MESSAGE + "() on " + item.javaClass.name +
                " or any ancestor, so our row has no message of its own; the tap falls back to the " +
                "last long-press",
        )
        return null
    }

    // ------------------------------------------------------------------ tap handling

    /**
     * Last-resort message lookup: read it out of the menu that is ON SCREEN RIGHT NOW.
     *
     * WHY A THIRD SOURCE IS NEEDED. Our row is injected the moment the menu renders, but the host
     * fills its `ui.c` container with items over the following frames -- so at injection time
     * `findHostRow` may not have found a host row yet and the message captured was `unknown`, which
     * is precisely what the device log showed:
     *
     *     injected '分析' row #1 into e (message=unknown, ...)
     *
     * By the time the user TAPS, that work is long finished: the menu on screen has its host rows and
     * each carries the long-pressed message. Reading it here turns the failure into a success without
     * depending on when the animation happened to be at injection time.
     *
     * Read-only, and every failure returns null so the caller keeps its own error path.
     */
    private fun messageFromLiveMenu(): Any? {
        val root = lastMenuAnchor ?: return null
        return try {
            // Start from OUR row's container when it can be found: that is the row holding the host's
            // own items, and searching from there cannot pick up an unrelated list elsewhere.
            //
            // The item class is resolved through `loadClass`, not taken from the NAME constant: the
            // helper matches on an already-loaded `Class`, so passing the string would not compile
            // (it did not) and would also have to be re-resolved on every tap.
            val itemCls = loadClass(QQMenuIdentity.ITEM_CLASS) ?: return null
            val parent = oursRowParent(root) ?: root
            val hostRow = findHostRow(parent, itemCls, 0)
                ?: findHostRow(root, itemCls, 0)
                ?: return null
            val msg = messageFromItem(hostRow.tag)
            if (msg != null) {
                Log.i(
                    TAG,
                    "QQ MENU: recovered the message for the tap from the live menu -> " +
                        msg.javaClass.name,
                )
            }
            msg
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MENU: reading the live menu for a message failed: " + t.message)
            null
        }
    }

    /**
     * The container holding our injected row, or null when it is not in this tree.
     *
     * Kept as a small dedicated walk instead of a general "find by tag" helper: the only caller wants
     * our row's PARENT, and returning the parent directly avoids a second search.
     */
    private fun oursRowParent(root: View): View? {
        val g = root as? ViewGroup ?: return null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            if (isOurRow(c)) return g
            oursRowParent(c)?.let { return it }
        }
        return null
    }

    /**
     * The 浮窗 (floating-balloon) row's tap: flips the balloon switch, exactly as WeChat's
     * `MenuHook.handleBalloonSwitchTap` and Douyin's equivalent do.
     *
     * The three steps are copied deliberately rather than re-invented, because a switch that saves
     * the flag but does not show or hide the balloon leaves the user with a setting that disagrees
     * with the screen -- the exact failure `MenuHook` records having fixed:
     *   1. persist the new value,
     *   2. show or hide the panel to match,
     *   3. toast, so the change is confirmed without reopening the menu.
     *
     * The Context comes from the last menu we rendered: that view belongs to QQ's own window, and
     * `ConfigRepo.toggleBalloonEnabled` needs a Context only to reach the module's config file.
     */
    private fun onBalloonRowTapped() {
        Log.i(TAG, "QQ MENU: our " + QQMenuIdentity.OUR_BALLOON_LABEL + " row tapped")
        val ctx: Context? = try {
            lastMenuAnchor?.context
        } catch (_: Throwable) {
            null
        }
        if (ctx == null) {
            Log.w(
                TAG,
                "QQ MENU: " + QQMenuIdentity.OUR_BALLOON_LABEL + " tapped with no Context, so the " +
                    "switch cannot be saved; nothing changed",
            )
            return
        }
        try {
            val on = com.luoliaoo.wechat.data.ConfigRepo.toggleBalloonEnabled(ctx)
            Log.i(TAG, "QQ MENU: " + QQMenuIdentity.OUR_BALLOON_LABEL + " -> " + (if (on) "ON" else "OFF"))
            if (on) {
                com.luoliaoo.wechat.ui.FloatingPanel.showGlobalOn(ctx)
            } else {
                com.luoliaoo.wechat.ui.FloatingPanel.hideGlobal()
            }
            showBalloonToast(ctx, on)
        } catch (t: Throwable) {
            Log.w(TAG, "QQ MENU: balloon switch failed: " + t.message)
        }
    }

    /**
     * The confirmation toast, so the user can SEE which way the switch went.
     *
     * Without it the switch works but reports nothing, which the user reads as "the entry is
     * broken" -- the exact report that led here ("the 浮窗 tap no longer shows 悬浮球已打开/已关闭").
     * The wording is copied from `MenuHook.showSwitchToast` rather than re-written, so all five
     * hosts say the same thing.
     *
     * The Activity is preferred over the raw Context because a Context that is not an Activity
     * cannot show a toast on some Android versions. The fallback prefixes the app name, which is
     * what `MenuHook` does for the same reason.
     */
    private fun showBalloonToast(ctx: Context, on: Boolean) {
        try {
            val text = if (on) "\u60ac\u6d6e\u7403\u5df2\u6253\u5f00" else "\u60ac\u6d6e\u7403\u5df2\u5173\u95ed"
            // The Activity is preferred over the raw Context: a non-Activity Context cannot show a
            // toast on some Android versions. `lastMenuAnchor` is the menu View we rendered into, and
            // climbing from it reaches the chat Activity, so `findActivityOrNull` takes a View.
            val act = lastMenuAnchor?.let { findActivityOrNull(it) }
            if (act != null) {
                android.widget.Toast.makeText(act, text, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(
                    ctx, "\u843d\u804a\uff1a" + text, android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (_: Throwable) {
            // Cosmetic: the switch itself already happened and is persisted.
        }
    }

    /**
     * Runs the analysis for our row's tap.
     *
     * The single/batch decision reads the host's live multi-select state: QQ's own 多选 entry seeds the
     * selection with the long-pressed message, and our row sits in the same menu, so a selection of more
     * than one is the only case that should take the batch path (see [QQMenuSelection.isBatch] for why
     * `> 1` and not `>= 1`).
     */
    private fun onOurRowTapped(message: Any?) {
        taps++
        // RECON: dump the live tree BEFORE anything else, so the multi-select toolbar and the checked
        // rows are captured even when the analysis path below decides to do nothing. Capped at two
        // runs (see [dumpRuns]) and posted, because a tap handler runs with the menu still closing
        // and reading view state mid-animation is what produces a misleading "empty" dump.
        try {
            val root = lastMenuAnchor
            root?.post { dumpMultiSelectTree("tap#" + taps) }
        } catch (_: Throwable) {
        }
        val selected = readSelectedMessages()
        if (QQMenuSelection.isBatch(selected)) {
            Log.i(
                TAG,
                "QQ MENU: '" + QQMenuIdentity.OUR_ITEM_LABEL + "' tapped with " + selected.size +
                    " message(s) selected [" + QQMenuSelection.describeForLog(selected) +
                    "] -- routing to the BATCH path",
            )
            startBatchAnalysis(selected)
            return
        }

        val single = message ?: lastMenuMessage ?: messageFromLiveMenu()
        if (single == null) {
            Log.w(
                TAG,
                "QQ MENU: '" + QQMenuIdentity.OUR_ITEM_LABEL + "' was tapped but no message is known " +
                    "(the row's own message, the last long-press, and the live menu all came up " +
                    "empty); nothing was analysed",
            )
            return
        }
        Log.i(
            TAG,
            "QQ MENU: '" + QQMenuIdentity.OUR_ITEM_LABEL + "' tapped (start #" + taps +
                " msg=" + single.javaClass.name +
                " textLen=" + safeContent(single).length +
                " talker=" + safeTalker(single) +
                ") -- entering the SHARED analysis path",
        )
        // ============================== THE ANCHOR MUST BE A CHAT-PAGE VIEW ==============================
        // `startAnalysisFor` -> `proceed` calls `ChatContext.findRecyclerView(anchorView)`, which walks UP
        // from the anchor looking for the `RecyclerView`. The menu is a separate `Panel:` window, so passing
        // it here would climb to that window's decor and find no message list -- the shared path would run
        // with `adapterMsgs=0`. So we resolve a view inside the ACTIVE CHAT, never the menu itself.
        val anchor = chatAnchor()
        if (anchor == null) {
            Log.w(
                TAG,
                "QQ MENU: '" + QQMenuIdentity.OUR_ITEM_LABEL + "' was tapped but no chat-page anchor view " +
                    "could be resolved (the menu is a separate Panel window), so the analysis UI cannot " +
                    "locate the message list; nothing was shown",
            )
            return
        }
        try {
            controller.startAnalysisFor(single, anchor)
        } catch (t: Throwable) {
            Log.w(TAG, "QQ: startAnalysisFor threw: " + t.message)
        }
    }

    /**
     * A view inside the ACTIVE CHAT page, from which the shared path can find the message list.
     *
     * See the comment at the call site: the long-press menu lives in its own `Panel:` window, so it can
     * never serve as the shared path's anchor. This resolves the Activity (which the Panel's themed context
     * still reaches) and then picks a chat-page view under it.
     */
    private fun chatAnchor(): View? {
        val activity = lastMenuAnchor?.let { findActivityOrNull(it) } ?: return null
        val decor = try {
            activity.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: return null
        return chatAnchorIn(decor)
    }

    // ------------------------------------------------------------------ multi-select

    /**
     * Reads the currently selected messages, or an empty list when multi-select is not active.
     *
     * ============================== THE HOST'S ACCESSOR RETURNS THE WHOLE LIST ==============================
     * The list captured by [installSelectionHook] is the object the host returns from
     * `MsgListVMWrapper#N()`. It is NOT the selection: it is the CONVERSATION'S MESSAGE LIST.
     *
     * PROOF (9.3.65 dex), two independent facts:
     *   1. `impl.b#<init>` assigns `i = new CopyOnWriteArrayList()` (EMPTY), and the only other writer
     *      is `impl.b#c0`, which fills it from the repository on `MsgListRepoOpType.LoadPrePage`:
     *      `c0 -> data.d#a(c) -> data.l#c() -> data.l#a : List`. That is a page of chat history.
     *   2. `N()` has ~19 callers, including `ScrollMsgListVM$scrollListener$2$a#onScrollStateChange`
     *      (scroll geometry) and `childvm.j#h`, which takes its LAST element -- both meaningless for a
     *      selection, obvious for "the loaded messages".
     *
     * This is exactly what produced the user-visible bug: a long-press of ONE message reported
     * "7 message(s) selected" (7 = the loaded messages) and opened the read-only 分析已选消息 dialog the
     * batch path uses, instead of 分析范围.
     *
     * ============================== THE FIX: APPLY THE HOST'S OWN SELECTION PREDICATE ==============================
     * The host never treats `N()` as the selection either -- it FILTERS it with `AIOMsgItem#K2()Z`.
     * From `childvm.j#l` (the batch builder):
     *     [00bd] invoke-virtual AIOMsgItem#K2()Z
     *     [00c1] if-eq                 <-- keep only the entries where it is TRUE
     *     [00c3] ArrayList#add(...)
     * `K2()` is called from only multi-select-aware call sites (`MultiSelectMsgListChildVM`,
     * `NtAIOShareActionSheet#onItemClick`, `msgselect.msglist.c#A`), which is what identifies it as the
     * selection predicate. It is public and zero-argument, so it can be invoked reflectively.
     *
     * So we do what the host does: narrow the list with `K2()`. This keeps GENUINE multi-select working
     * (a real 3-message selection reports 3 and takes the batch path) while a lone long-press now yields
     * at most one entry -- whatever `K2()` says about the pressed message -- and therefore takes the
     * SINGLE path.
     *
     * ============================== WHY THIS IS NOT A GUESS ==============================
     * An earlier revision simply returned `emptyList()` here, disabling batch analysis entirely. That
     * direction was SAFE but lossy: it silently removed a working feature. Applying the host's own
     * predicate is the same guarantee without the loss, and it fails the same safe way -- if `K2()` cannot
     * be resolved (a QQ rename), [QQMenuSelection.selectedOnly] is handed a predicate that returns false
     * for everything, so the result is empty and the caller takes the single path. Analysing the one
     * message the user actually long-pressed is always correct; treating the whole conversation as
     * "selected" would push messages the user never chose into an AI request -- a correctness AND privacy
     * failure. Degrade to less, never to more.
     */
    private fun readSelectedMessages(): List<Any> {
        // ======================= BATCH IS DISABLED: NO TRUSTWORTHY SOURCE ON QQ =======================
        //
        // This ALWAYS returns empty, which sends every tap down the SINGLE-message path. That is the
        // only correct answer available, and the history of this function is why:
        //
        //   1) The host's accessor (`MsgListVMWrapper#N()`) returns the WHOLE conversation, not the
        //      selection -- proved by the host's own caller, which reads it, takes `subList`, then
        //      filters every element before emitting `SetSelectMsgList`. Using it made a one-message
        //      long-press look like "7 of 7 selected".
        //
        //   2) Filtering that list with the host's `AIOMsgItem#K2()Z` DOES NOT FIX IT. On 9.3.65 that
        //      method is effectively CONSTANT TRUE for real messages, so the filter passes all 7
        //      through and the batch path is taken again. Measured on the device:
        //
        //          QQ multi-select: host list held 7 message(s), of which 7 are SELECTED (predicate=K2)
        //          '分析' tapped with 7 message(s) selected -- routing to the BATCH path
        //
        //      `K2` is not a selection flag. It is overridden per message type (`MarkdownMsgItem`
        //      declares its own), and its body reads fields that have nothing to do with a tick. A
        //      predicate that answers TRUE for everything is indistinguishable from "all selected",
        //      which is the same failure as (1) wearing a disguise.
        //
        // The real selection travels in the `SetSelectMsgList` / `GetSelectMsgList` EVENT payload, and
        // subscribing to it is the documented way to restore batch analysis. Until that exists, the
        // honest behaviour is the single-message path: it analyses exactly the message the user
        // long-pressed and can send nothing the user did not choose.
        //
        // A count-based guess ("batch when > 2") is rejected: a genuine two-message selection would be
        // misrouted, and this repository's rule is degrade, never guess.
        if (hostSelection != null || selectionCaptures > 0) {
            rememberOnce(
                "batchDisabledNoTrustedSource",
                "QQ multi-select: batch analysis is DISABLED -- neither the host's accessor (the whole " +
                    "conversation) nor AIOMsgItem#K2 (constant true) identifies a real selection, so " +
                    "every tap analyses the long-pressed message only",
            )
        }
        return emptyList()
    }

    /** The pre-correction body, kept for when a trustworthy selection source exists. */
    private fun readSelectedMessagesViaPredicate(): List<Any> {
        val raw = hostSelection

        // The host's list, normalised (identity-deduped, nulls dropped).
        val all = QQMenuSelection.valuesOf(raw)
        if (all.isEmpty()) return emptyList()

        // Cheap insurance on a path that is otherwise trusted: if a future QQ build returns the menu's
        // own view list here, analysing `TextView`s would put nonsense in front of the user. The guard
        // rejects that before the predicate is even consulted.
        if (!QQMenuSelection.looksLikeMessageStore(all)) {
            rememberOnce(
                "selectionNotMessages",
                "QQ multi-select: the host's list held " + all[0].javaClass.name +
                    ", which is not a message; ignoring it and analysing only the long-pressed message",
            )
            return emptyList()
        }

        // Narrow it with the host's OWN predicate, exactly as the host's batch path does.
        val predicate = selectionPredicate(all[0])
        val selected = QQMenuSelection.selectedOnly(all) { predicate(it) }

        Log.i(
            TAG,
            "QQ multi-select: host list held " + all.size + " message(s), of which " + selected.size +
                " are SELECTED (predicate=" + (if (predicateResolved) SELECTED_FLAG else "unresolved") +
                ", captures=" + selectionCaptures + ")",
        )
        if (selected.isEmpty()) {
            // The normal long-press case: the user is not in multi-select, so nothing is ticked and the
            // caller analyses the single long-pressed message.
            Log.i(
                TAG,
                "QQ multi-select: no message is ticked, so this tap analyses ONLY the long-pressed " +
                    "message (the single-message path)",
            )
        }
        return selected
    }

    /**
     * True once [SELECTED_FLAG] was found on the host's message class.
     *
     * Kept as a flag only so the log can distinguish "the host says nothing is selected" (normal, a plain
     * long-press) from "we could not ask the host" (a rename, batch silently unavailable). Those two look
     * identical in the output above and have completely different fixes.
     */
    @Volatile
    private var predicateResolved = false

    /**
     * Builds the "is this message selected" predicate from the host's own `AIOMsgItem#K2()Z`.
     *
     * Resolved reflectively ONCE per install-shaped lifetime and then reused: this runs on every row tap,
     * and a method lookup per element of a 200-message list would be waste. The predicate deliberately
     * returns FALSE when the method is missing (see [readSelectedMessages]) so an unresolvable rename
     * degrades to the single-message path instead of promoting the whole conversation to "selected".
     */
    private fun selectionPredicate(sample: Any): (Any) -> Boolean {
        val cached = selectedFlag
        if (cached != null) {
            return { item -> invokeBoolean(cached, item) }
        }
        val m = try {
            sample.javaClass.methods.firstOrNull {
                it.name == SELECTED_FLAG &&
                    it.parameterCount == 0 &&
                    (it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java)
            }
        } catch (_: Throwable) {
            null
        } ?: run {
            rememberOnce(
                "noSelectedFlag",
                "QQ multi-select: " + sample.javaClass.name + "#" + SELECTED_FLAG + "()Z was not found, so " +
                    "the message list cannot be narrowed to the selection; every tap analyses the " +
                    "long-pressed message (batch disabled, nothing else affected)",
            )
            return { false }
        }
        m.isAccessible = true
        selectedFlag = m
        predicateResolved = true
        return { item -> invokeBoolean(m, item) }
    }

    /** Invokes a resolved zero-arg boolean method, treating any failure as "not selected". */
    private fun invokeBoolean(m: java.lang.reflect.Method, item: Any): Boolean = try {
        when (val r = m.invoke(item)) {
            is Boolean -> r
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * The host's per-message selection predicate, resolved once.
     *
     * NOT named `K2` here on purpose -- [SELECTED_FLAG] is the single place the host's method name is
     * written, so a future re-check has exactly one string to verify.
     */
    @Volatile
    private var selectedFlag: java.lang.reflect.Method? = null

    /**
     * The host's `AIOMsgItem#K2()Z` -- the per-message "is this selected" predicate.
     *
     * ONE string, so a future version re-check has a single place to verify. Resolved reflectively
     * against the message instance's own class (the concrete type is `aio.msg.ao` / `FileMsgItem` etc.,
     * all subclasses of `AIOMsgItem`), which is why the lookup is by name and signature rather than by
     * declaring class.
     */
    private val SELECTED_FLAG = QQMenuSelection.SELECTED_FLAG_METHOD

    // DELETED: `readSelectedMessagesViaAccessor()`, the pre-correction body that returned the host's
    // `MsgListVMWrapper#N()` list AS the selection. It is not kept as a fallback because its result was
    // wrong in a way no type check could catch -- the elements ARE real messages, they are simply ALL of
    // them. Keeping it would preserve the exact bug it caused (one long-press analysing 7 messages).
    // [readSelectedMessages] now narrows the same list with the host's own predicate.

    /**
     * The view the SHARED path needs: something inside the chat page from which
     * `ChatContext.findRecyclerView` can climb to the message list.
     *
     * Uses the multi-select bar when it is on screen (it is a chat-page view, so climbing from it reaches
     * the message list), otherwise falls back to the Activity's content view. The menu's own view is NEVER
     * returned: it belongs to a different window and climbing from it finds no chat list at all.
     */
    private fun chatAnchorIn(root: View): View {
        val barCls = loadClass(MULTISELECT_BAR)
        if (barCls != null) {
            findInTree(root, barCls, 0)?.let { return it }
        }
        // The first child of the decor is the Activity content; the decor itself works but is noisier.
        val content = try {
            (root as? ViewGroup)?.getChildAt(0)
        } catch (_: Throwable) {
            null
        }
        return content ?: root
    }

    // NOTE: the earlier `selectionFrom(bar)` field-walking reader was DELETED, not kept as a fallback.
    // `MultiSelectBarVB` declares no collection field at all (every field is a View or a kotlin.Lazy), so
    // that reader could only ever return an empty list -- keeping it would preserve the exact silent failure
    // it caused. The messages now come from the host's `MsgListVMWrapper#N()`, narrowed by the host's own
    // `AIOMsgItem#K2()` selection predicate (see [readSelectedMessages]).

    /**
     * Runs the batch analysis through the SAME shared path every host uses: describe with the controller's
     * own formatting, filter with `controller.templateBlockWords()` (never a hardcoded list -- that is the
     * "two filter tables" bug), show the read-only scope dialog, then start with the words the filter used.
     */
    private fun startBatchAnalysis(selected: List<Any>) {
        try {
            val activity = lastMenuAnchor?.let { findActivityOrNull(it) } ?: run {
                Log.w(
                    TAG,
                    "QQ multi-select: no Activity could be resolved from the long-press anchor; " +
                        "cannot show the analysis dialog",
                )
                return
            }
            val described = controller.describeMessages(selected)
            val words = controller.templateBlockWords()
            val lines = described.filterNot {
                com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(it) ||
                    com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(it, words)
            }
            if (lines.isEmpty()) {
                Log.w(
                    TAG,
                    "QQ multi-select: all " + described.size + " selected message(s) were non-text or " +
                        "blocked (words=" + words.joinToString("/") + "); nothing to analyse",
                )
                return
            }
            Log.i(
                TAG,
                "QQ multi-select: analysing " + lines.size + " of " + selected.size +
                    " selected message(s) (blocked/filtered=" + (selected.size - lines.size) + ")",
            )
            com.luoliaoo.wechat.ui.ScopeDialog.showReadOnly(
                activity,
                lines,
                HostApp.current.say("\u5df2\u9009\u6d88\u606f"),
            ) { kept, background ->
                controller.startAnalysisForSelection(activity, kept, background, words)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "QQ multi-select analysis failed: " + t.message, t)
        }
    }

    /** Depth-first search for the first view of [cls] in [root]'s subtree. */
    private fun findInTree(root: View, cls: Class<*>, depth: Int): View? {
        if (depth > 40) return null
        if (cls.isInstance(root)) return root
        val group = root as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            findInTree(child, cls, depth + 1)?.let { return it }
        }
        return null
    }

    /** The Activity behind a view, or null. Mirrors the Kuaishou/Douyin helper without duplicating UI. */
    private fun findActivityOrNull(v: View): android.app.Activity? {
        var c: Context? = v.context
        var depth = 0
        while (c != null && depth < 10) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return try {
            var rc: Context? = v.rootView?.context
            var d = 0
            while (rc != null && d < 10) {
                if (rc is android.app.Activity) return rc
                rc = (rc as? android.content.ContextWrapper)?.baseContext
                d++
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reads the message text through the shared accessor, never throwing out of a hook body. */
    private fun safeContent(msg: Any): String = try {
        controller.access().content(msg) ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun safeTalker(msg: Any): String = try {
        controller.access().talker(msg) ?: ""
    } catch (_: Throwable) {
        ""
    }

    companion object {
        private const val TAG = "LuoLiao/QQ"

        /**
         * The user-visible multi-select bar.
         *
         * Used ONLY to pick a chat-page anchor view for the shared path (it is a convenient chat-page view
         * when multi-select is active). It is NOT a selection source: the bar declares no collection field
         * at all -- every field is a `View` or a `kotlin.Lazy` -- which is why the earlier field-walking
         * reader could never have worked.
         */
        private const val MULTISELECT_BAR =
            "com.tencent.mobileqq.aio.input.multiselect.MultiSelectBarVB"

        /** The multi-select UI state class, for the reachability probe. */
        private const val MULTISELECT_UI_STATE =
            "com.tencent.mobileqq.aio.input.multiselect.MultiSelectUIState"

        /**
         * The message-list view-model WRAPPER, which declares the selection accessor.
         *
         * `MsgListVMWrapper#N()Ljava/util/List;` [classes3, 34 code units] reads its `f` field (a
         * `msglist.mvx.vm.a`) and returns that object's `N()`.
         */
        private const val MSG_LIST_WRAPPER_CLASS =
            "com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vm.MsgListVMWrapper"

        /**
         * The CONCRETE implementation of the selection accessor -- the method that actually returns the list.
         *
         * `impl.b#N()` [classes3, 22 code units] is `iget-object impl.b.i : CopyOnWriteArrayList` followed by
         * `return-object`, so field `i` IS the live selection. Hooked as well as the wrapper so the capture
         * survives a refactor that changes which of the two the host calls.
         */
        private const val MSG_LIST_IMPL_CLASS =
            "com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vm.impl.b"

        /**
         * The selected-messages accessor, by its real (single-letter, obfuscated) name.
         *
         * `N` is NOT a guess: it is the name the host's own multi-select child VM invokes
         * (`aio.msglist.childvm.j#l` -> `MsgListVMWrapper#N()Ljava/util/List;`), and the host's cast
         * assertion in that same method names the element type
         * (`List<com.tencent.mobileqq.aio.msg.AIOMsgItem>`).
         */
        private const val MSG_LIST_SELECTED_ACCESSOR = "N"

        /** Icon size used only when the host's own icon has no usable width. */
        private const val ICON_DP = 20
    }
}
