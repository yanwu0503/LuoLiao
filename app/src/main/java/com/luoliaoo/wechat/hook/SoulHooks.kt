package com.luoliaoo.wechat.hook

import android.view.View
import android.widget.Toast
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.ui.AnalysisIcon

/**
 * Installs the SOUL long-press menu entry. Sibling of [MenuHook] (WeChat) and [DouyinHooks], and it
 * does the same job for the third host.
 *
 * ============================ WHY SOUL IS THE EASIEST OF THE THREE ============================
 *
 * WeChat injects into an adapter-built menu and must recover the message from the view hierarchy.
 * Douyin has no item list at all -- its menu items are 40 obfuscated CLASSES -- so [DouyinHooks] has
 * to build a row out of plain views and append it to the PopupWindow, which is where its host crash
 * came from (a foreign LayoutParams reaching a container that validates the type at measure time).
 *
 * Soul hands us the whole thing on a plate. ONE method assembles the item list, builds the popup and
 * shows it, and its parameters include the pressed message AND the anchor view:
 *
 *   cn.soulapp.android.component.chat.utils.u0.y(          [classes12.dex]
 *       ConversationFragment,      arg0
 *       java.util.List,            arg1   <-- the menu item list handed to m0(List)
 *       android.view.View,         arg2   <-- the anchor view
 *       cn.soulapp.imlib.msg.ImMessage, arg3 <-- THE PRESSED MESSAGE
 *       int)                       arg4
 *
 * and its body, disassembled (offsets from `soul_dis.py code ... ^y$`):
 *
 *   [00a2] new-instance  ChatLongClickPopUp
 *   [00a6] invoke-direct <init>(Context)
 *   [00ac] invoke-virtual ChatLongClickPopUp->m0(List)          <-- the items become the menu HERE
 *   [00c2] invoke-direct  n0-><init>(ImMessage, ConversationFragment, ...)   <-- host's own listener
 *   [00c8] invoke-virtual ChatLongClickPopUp->p0(OnMenuItemClickListener)
 *   [00e8] invoke-virtual BasePopupWindow->g0(View)             <-- shown with our anchor
 *
 * SO THE DESIGN IS: append ONE more item object to `arg1` before the host consumes the list. Soul
 * then inflates the row, sets its text, wires its click and lays it out ITSELF -- exactly as it does
 * for 复制 / 撤回 / 转发.
 *
 * ============ WHY THAT REMOVES THE DOUYIN CRASH CLASS ENTIRELY (and why R1-R4 still apply) ============
 *
 *   * NO addView, so no foreign LayoutParams can ever reach a validating container. R1/R2 (the
 *     Douyin host crash) are structurally impossible on this path -- we never touch a ViewGroup.
 *   * NO label matching, so R3 does not apply: we know which item is ours by OBJECT IDENTITY, which
 *     no rename, no arsc string and no language change can break.
 *   * Identity is still enforced the R4 way, translated to this host. R4 says "ask the WINDOW, not the
 *     subtree", and the equivalent here is "ask the OWNER of the content": the item list owns the
 *     items, so "is our item already in it?" is a scan for our exact object reference, plus a
 *     per-anchor time guard for the case where Soul builds the list twice for one long press (the bug
 *     that produced FOUR buttons in WeChat -- see 必看.md).
 *
 * R1-R4's discipline is kept anyway, deliberately: this file still never trusts a view tree, still
 * logs every early return, and still refuses rather than guesses.
 *
 * ============================ THE SAFETY-CRITICAL PART: THE CLONED ACTION CODE ============================
 *
 * Our item is a CLONE of a real Soul item, so it carries a REAL action code -- and the host dispatcher
 * acts on that value (`u0.p` is a `packed-switch` on `$a.b()`, covering 复制 / 撤回 / 删除 / 举报 and
 * more). Two consequences drive this file's design, and both were found by disassembling rather than
 * by reasoning about what "should" happen:
 *
 *   1. The values must NEVER be invented. `(label, iconRes, actionCode)` is copied wholesale from a
 *      real item, so every int is one Soul itself produced. Where that is impossible, this file
 *      REFUSES to inject rather than substitute a placeholder -- see [cloneItem].
 *   2. The host dispatch must be SKIPPED for our row. `ChatLongClickPopUp$b.onClick` calls
 *      `l0(...)` -> `OnMenuItemClickListener.onMenuItemClick(...)` and passes our cloned action code
 *      along, so if that path ever ran for our row it would execute the TEMPLATE's action on the
 *      user's message -- tapping 分析 could copy or delete it. [installClickHook] therefore hooks the
 *      row's own `onClick` and skips the original, and it takes that decision BEFORE anything that can
 *      throw, so a failure downstream leaves a dead button rather than a destructive one.
 *
 * ============================ THE ICON: REUSED, NEVER REDRAWN ============================
 *
 * Requirement 3 (「长按信息增加分析图标采用原来的」) is satisfied by using the EXISTING
 * [AnalysisIcon] and [AnalysisIcon.toDrawable] verbatim -- no new drawing code exists in this file.
 *
 * HOW THE ICON GETS IN, verified from `m0(List)`'s disassembly:
 *
 *   [0039] check-cast          ChatLongClickPopUp$a                 <-- the item object
 *   [003b] invoke-virtual      BasePopupWindow->j()Activity
 *   [003f] invoke-static       LayoutInflater->from(Context)
 *   [0046] invoke-virtual      LayoutInflater->inflate(int, ViewGroup)  <-- ONE row per item
 *   [004c] invoke-virtual      View->findViewById(int)
 *   [0050] check-cast          android.widget.TextView
 *   [0052] invoke-virtual      ChatLongClickPopUp$a->c()String      <-- label
 *   [0056] invoke-virtual      TextView->setText(CharSequence)
 *   [005b] invoke-virtual      View->findViewById(int)
 *   [005f] check-cast          android.widget.ImageView
 *   [0061] invoke-virtual      ChatLongClickPopUp$a->a()I
 *   [0065] invoke-virtual      ImageView->setImageResource(int)     <-- icon, FROM A RESOURCE ID
 *
 * so the row HAS an ImageView and the icon slot exists -- but Soul fills it with `setImageResource`,
 * i.e. a resId from Soul's own resources. Our icon is a code-drawn `Drawable` produced in the
 * MODULE's process, which is not addressable by a host resId.
 *
 * So the icon is attached the way that is PROVEN to work on a host view: right after the host has
 * built the rows, we take OUR row (identified by position, which is provable here -- see [findOurRow])
 * and call `setImageDrawable(AnalysisIcon.toDrawable(...))` on its ImageView. That is the same
 * Drawable [MenuHook] hands to WeChat's MenuItem, from the same function, at the same 20 dp -- the
 * SAME icon, not a redrawn one. If the slot cannot be found, the entry still works and NO substitute
 * glyph is drawn; the failure is logged and [attachIcon] explains why.
 *
 * ============================ WHAT IS VERIFIED, AND WHAT IS NOT ============================
 *
 * VERIFIED from the dex (reproduce with the `_tools/soul_*.py` helpers):
 *   * `u0.y` is the single-chat menu path, confirmed at REGISTER level: `[00ac] m0(List)` is handed
 *     `v10`, which is the method's own `arg1` -- so the list appended to in [onMenuBuilding] is
 *     exactly the list Soul iterates to build the rows. No copying or re-wrapping happens in between.
 *   * The item type `ChatLongClickPopUp$a` is NOT obfuscated: fields `a` (String label), `b`, `c`
 *     (ints), methods `c()` label / `a()` / `b()`, constructor `(String,int,int)`.
 *   * `a()` is the ICON RESOURCE ID (passed to `ImageView.setImageResource` in `m0`) and `b()` is the
 *     ACTION CODE (`u0.p` is a `packed-switch` on it). Their VALUES are data, not code, so they are
 *     copied from a live item and never invented -- see [cloneItem].
 *   * The row's click handler `ChatLongClickPopUp$b` holds the item in its only `$a`-typed field and
 *     the popup in its only `ChatLongClickPopUp`-typed field, which is how [itemOf] and [popupOf] find
 *     them without depending on an obfuscated field NAME.
 *   * Soul's own dismissal is `BasePopupWindow.f(Z)` -- the call `$b.onClick` itself ends with.
 *
 * NOT VERIFIED (no device was attached -- see the probe in [install] and the open questions below):
 *   * whether Tinker (every Soul class carries `changeQuickRedirect`) intercepts `u0.y`;
 *   * the real label set of the single-chat menu at runtime;
 *   * the concrete `a`/`b` values of a cloned item.
 *
 * The probe answers all three in ONE device run. It is deliberately a probe and not a guess: this
 * file's behaviour on an unverified build is "log exactly what is there and still ship a working
 * entry", never "assume and crash".
 *
 * ============================ NO MULTI-SELECT ANYWHERE ============================
 *
 * Requirement 4 is a hard rule and this file has no multi-select code path at all: there is no
 * `step("multi-select")`, no bar hook, no selection getter. Soul has no such feature, and copying
 * Douyin's would be a host-crash risk for a screen Soul does not have.
 *
 * Comments ASCII-only; Chinese text uses \uXXXX escapes (see core/Log.kt).
 */
class SoulHooks(
    private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    private companion object {
        const val TAG = "LuoLiao"

        /**
         * The ONE method that assembles the single-chat menu, builds its popup and shows it.
         *
         * Verified in classes12.dex. NOT obfuscated -- the class is `cn.soulapp.android.component.
         * chat.utils.u0` and the method is a single letter, but the CLASS is stable and the method
         * signature is unique within it, so it is located by SIGNATURE (5 params, `List` at index 1,
         * `View` at index 2) rather than by the letter alone. A letter can be re-lettered by an
         * obfuscator update; the parameter shape cannot without changing what the method does.
         */
        const val MENU_HOST = "cn.soulapp.android.component.chat.utils.u0"

        /** The menu item model. Every member of it is un-obfuscated in name AND shape. */
        const val ITEM_CLASS = "cn.soulapp.android.component.chat.window.ChatLongClickPopUp\$a"

        /** The popup, used only to name it in diagnostics. */
        const val POPUP_CLASS = "cn.soulapp.android.component.chat.window.ChatLongClickPopUp"

        /**
         * The row's click handler -- the class that actually dispatches a tap.
         *
         * Verified from `ChatLongClickPopUp$b.onClick` (classes13.dex): it holds the row `View`, the
         * item `$a` and the action code `I`, and it is the frame that calls
         * `OnMenuItemClickListener.onMenuItemClick(...)`. Hooking it is what lets our row be routed
         * WITHOUT the host listener ever receiving our cloned action code -- see [installClickHook].
         */
        const val ROW_CLICK_CLASS = "cn.soulapp.android.component.chat.window.ChatLongClickPopUp\$b"

        /** The message class, used to sanity-check what the hook was handed. */
        const val MSG_CLASS = "cn.soulapp.imlib.msg.ImMessage"

        /** The instance field that holds the `changeQuickRedirect` (Tinker) patch slot. */
        const val TINKER_FIELD = "changeQuickRedirect"

        /**
         * Soul's OWN field that holds the menu item container on the single-chat popup.
         *
         * Verified from `ChatLongClickPopUp.M`'s disassembly: `iput-object ... n : android.widget.
         * GridLayout`. It is the `ViewGroup` every menu row is `addView`'d into, so it is the ONLY
         * correct place to look for our row -- not a window, not the anchor's root view.
         *
         * The group-chat popup uses the same field name with a different type (`LinearLayout`), so the
         * READ is type-agnostic (`as? ViewGroup`) and the group popup is simply not hooked at all.
         */
        const val CONTAINER_FIELD = "n"

        /** Our label, shared with every other host through Constants (分析). */
        val ITEM_TITLE: String = Constants.ITEM_TITLE

        /** Icon edge length in dp, matching WeChat's menu glyph and [MenuHook]. */
        const val ICON_SIZE_DP = 20

        /**
         * Marks a view subtree as OURS, for the window-level idempotency check.
         *
         * Reuses the module-wide tag so any other code that walks the tree looking for "our UI"
         * (the scope dialog's own guard) keeps working unchanged.
         */
        const val TAG_OUR_ROW = Constants.TAG_OWN_UI

        /**
         * The application-specific tag key for [TAG_OUR_ROW].
         *
         * `key >>> 24` must be >= 2 or `View.setTag(int,Object)` throws, and the module's own item ids
         * (`0x4C4C01`) are far below that -- they are RESOURCE ids, not tag keys. 0x4C4C0100 keeps the
         * same "Luoliao" mnemonic while satisfying the platform's rule.
         */
        const val OWN_TAG_KEY = 0x4C4C0100

        /** Same-window guard: Soul rebuilds its list within a frame; a real reopen is far slower. */
        const val REINJECT_GUARD_MS = 1200L

        /**
         * NOTE ON A REJECTED APPROACH -- read before adding any delay here.
         *
         * The icon was first attached from four posted callbacks (60/160/300/600 ms) that searched a
         * window for our row. That design is GONE and must not come back: the menu lives in its own
         * razerdp window, so the search rooted at the anchor could never succeed, and a timer makes
         * that failure silent ("row not found yet" is a legal state at every tick). The icon is now
         * attached synchronously in the AFTER-hook on Soul's own row builder. See [installRowIconHook].
         */
    }

    // ------------------------------------------------------------------ runtime probe state

    /** Fired counter for `u0.y`: proves the hook is INSTALLED and actually RUNNING. */
    @Volatile
    private var menuFired = 0

    /** Set once per process when the menu host class could not be resolved at all. */
    @Volatile
    private var menuHostReported = false

    /**
     * Diagnostic de-duplication, BY CLASS NAME.
     *
     * 必看.md is explicit: a count-based limit gets eaten by the conversation list, so the screen
     * this module actually cares about never appears in the log. A `Set<String>` keyed on the class
     * name means each DISTINCT thing is reported once, no matter how many events arrive.
     */
    private val reportedClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** One-shot flags, so the probe dumps its long lines exactly once per process. */
    @Volatile
    private var labelsDumped = false

    @Volatile
    private var itemShapeDumped = false

    /** Set once the click hook is confirmed installed, so the probe can report it. */
    @Volatile
    private var clickHookInstalled = false

    /** The anchor view of the last injection, and when it happened. See [REINJECT_GUARD_MS]. */
    @Volatile
    private var lastAnchor: View? = null

    @Volatile
    private var lastInjectAt = 0L

    /** The item object we appended, by identity. THE click-routing criterion. */
    @Volatile
    private var ownItem: Any? = null

    // ------------------------------------------------------------------ install

    /**
     * Installs every Soul hook. Called from `WeChatHooks`'s SOUL branch through the
     * `(ClassLoader, Resolver, AnalysisController)` contract.
     *
     * EVERY step is its own try/catch: a Soul internal rename must degrade this ONE feature, never
     * take the floating balloon down with it. That is the same rule `DouyinHooks` follows.
     */
    fun install() {
        // HOST GUARD, defence in depth. `WeChatHooks` already dispatches by `HostApp.current`, but this
        // class is the one that reaches for `cn.soulapp.*` and the ONE thing it must never do is run
        // inside WeChat or Douyin: every `loadClass` below would miss, and `u0`-shaped hooks would be
        // installed against a host that has no such menu. Guarding here means a future dispatch bug is
        // a log line instead of a mystery.
        if (HostApp.current != HostApp.SOUL) {
            Log.w(
                TAG,
                "soul: SoulHooks.install() was called in the " + HostApp.current.name +
                    " process -- REFUSING to install Soul's hooks here (nothing was changed)",
            )
            return
        }

        Log.i(
            TAG,
            "soul: installing the long-press menu hooks (host=" + HostApp.current.name +
                ", loader=" + loaderName() + ")",
        )

        try {
            installMenuHook()
        } catch (t: Throwable) {
            // NAME the failure. A silently-missing 分析 entry is the worst outcome, because the user
            // cannot tell "the module is broken" from "I pressed the wrong place".
            Log.e(TAG, "soul: installing the menu hook FAILED: " + t.javaClass.simpleName + ": " + t.message, t)
        }

        // The menu root has to be captured BEFORE the icon can be attached, and it is captured from
        // the popup's own row builder rather than from the anchor (see [installRowIconHook] for why
        // the anchor's root view is the WRONG window). Own try/catch: losing this hook costs the icon
        // only, never the entry.
        try {
            installRowIconHook()
        } catch (t: Throwable) {
            Log.e(TAG, "soul: attaching the menu icon FAILED: " + t.javaClass.simpleName + ": " + t.message, t)
        }

        // The click routing is a SEPARATE hook from the injection, in its own try/catch: if Soul
        // changes the row-click class the item must still APPEAR (a row with no action is a smaller
        // defect than no row at all), and the log has to say which half is missing.
        try {
            installClickHook()
        } catch (t: Throwable) {
            Log.e(TAG, "soul: installing the click routing FAILED: " + t.javaClass.simpleName + ": " + t.message, t)
        }

        installRuntimeProbe()
    }

    /**
     * Hooks the one method that builds and shows the single-chat menu.
     *
     * The method is located by SHAPE, not by its obfuscated letter: `u0` has 26 methods and several
     * take a `List`, so the discriminator is the FULL signature -- 5 parameters with `List` at index
     * 1, `View` at index 2 and the message at index 3. That triple is what makes it the menu
     * assembler and nothing else.
     */
    private fun installMenuHook() {
        val cls = loadClass(MENU_HOST)
        if (cls == null) {
            // NO SILENT RETURN. This is the line that tells the next debug round whether the class
            // name changed or the hook never fired.
            Log.e(
                TAG,
                "soul: " + MENU_HOST + " is NOT loadable -- the long-press 分析 entry is OFF " +
                    "(analysis cannot be started from the menu; the floating balloon still works). " +
                    "Everything else in the module is unaffected.",
            )
            return
        }

        val candidates = cls.declaredMethods.filter { m -> looksLikeMenuAssembler(m) }
        if (candidates.isEmpty()) {
            val sig = cls.declaredMethods.joinToString("\n    ") { m ->
                m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } +
                    ":" + m.returnType.simpleName
            }
            Log.e(
                TAG,
                "soul: no (Fragment,List,View,ImMessage,int) method on " + MENU_HOST +
                    " -- the menu hook is OFF. Actual methods:\n    " + sig,
            )
            return
        }

        var installed = 0
        for (m in candidates) {
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        // The list is mutated BEFORE the original runs, because the original is what
                        // hands it to m0(List) and shows the popup. There is no after-the-fact
                        // opportunity: by the time it returns, the menu is already on screen.
                        try {
                            onMenuBuilding(
                                list = chain.getArg(1),
                                anchor = chain.getArg(2),
                                msg = chain.getArg(3),
                            )
                        } catch (t: Throwable) {
                            // A throw here would run inside Soul's own menu construction, so it must
                            // never escape -- that is how a missing button becomes a broken chat.
                            Log.w(TAG, "soul: menu injection failed: " + t.message)
                        }
                        chain.proceed()
                    }
                installed++
                Log.i(
                    TAG,
                    "soul: hooked the menu assembler " + cls.name + "." + m.name +
                        m.parameterTypes.joinToString(",", "(", ")") { it.simpleName },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "soul: hooking " + m.name + " failed: " + t.message, t)
            }
        }
        if (installed == 0) {
            Log.e(
                TAG,
                "soul: " + candidates.size + " menu assembler candidate(s) found but NONE could be " +
                    "hooked -- the 分析 entry will not appear",
            )
        }
    }

    /**
     * True when `m` has the shape of the menu assembler.
     *
     * Deliberately checks all five positions rather than "contains a List": `u0` also has
     * `g(Context, ImMessage, List, View, boolean)` and `b(Context, boolean, List)`, and hooking
     * either would append our item to a list that is NOT the long-press menu.
     */
    private fun looksLikeMenuAssembler(m: java.lang.reflect.Method): Boolean {
        if (m.parameterCount != 5) return false
        val p = m.parameterTypes
        if (p[1] != java.util.List::class.java) return false
        if (p[2] != View::class.java) return false
        val msgType = loadClass(MSG_CLASS)
        return if (msgType != null) {
            p[3] == msgType
        } else {
            // The class is not loaded yet. Fall back to the NAME so the hook still installs, and say
            // so, because a name-only match is weaker evidence than a type match.
            p[3].name == MSG_CLASS
        }
    }

    // ------------------------------------------------------------------ injection

    /**
     * Appends our item to the menu item list.
     *
     * @param list   `arg1` -- the list the host is about to hand to `m0(List)`.
     * @param anchor `arg2` -- the anchor view, used as the identity/guard key and to build the
     *               `Drawable` (a `Context` is needed and the anchor always has one).
     * @param msg    `arg3` -- the pressed message.
     */
    private fun onMenuBuilding(list: Any?, anchor: Any?, msg: Any?) {
        menuFired++

        // ---- every early return names its reason (必看.md hard rule) ----

        if (list !is MutableList<*>) {
            noteOnce(
                "list-shape",
                "soul: the menu list is " + (list?.javaClass?.name ?: "null") +
                    ", not a MutableList -- cannot append the 分析 item; no entry in this menu",
            )
            return
        }
        if (msg == null) {
            noteOnce("no-msg", "soul: the menu was shown with a null message -- cannot analyse; no entry")
            return
        }
        val anchorView = anchor as? View
        if (anchorView == null) {
            noteOnce(
                "no-anchor",
                "soul: the menu was shown with anchor=" + (anchor?.javaClass?.name ?: "null") +
                    " (not a View) -- cannot attach the icon; no entry",
            )
            return
        }

        if (menuFired == 1) {
            Log.i(
                TAG,
                "soul: MENU HOOK FIRED (first time) -- items=" + list.size +
                    " anchor=" + anchorView.javaClass.name +
                    " msg=" + msg.javaClass.name,
            )
        }

        // ---- idempotency, judged the R4 way ----
        //
        // 必看.md recorded FOUR buttons in WeChat because two callbacks 61 ms apart each saw a tree
        // that did not yet contain our row. Soul has the same shape of bug available: it can build
        // the list more than once for one long press. So two independent guards, exactly as R4
        // requires: (1) is our item ALREADY in this list? (2) has this same anchor been served
        // within the guard window?
        if (containsOurItem(list)) {
            Log.i(
                TAG,
                "soul: the menu list already carries our item (" + list.size +
                    " items) -- not injecting twice",
            )
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (anchorView === lastAnchor && now - lastInjectAt < REINJECT_GUARD_MS) {
            Log.i(
                TAG,
                "soul: the same anchor was served " + (now - lastInjectAt) +
                    " ms ago (guard " + REINJECT_GUARD_MS + " ms) -- not injecting twice",
            )
            return
        }

        val item = cloneItem(list, msg) ?: return

        // The item type is Soul's, so an unchecked cast is required to add it to the list. It is
        // safe by construction: `item` was CLONED from an element already in this very list, so it
        // is the same class the list already holds. The cast is done here, at the single write site,
        // rather than by widening the parameter type and losing the runtime check above.
        //
        // The add is wrapped and VERIFIED: if Soul ever hands us an immutable list (or a `Collections.
        // unmodifiableList` wrapper, which reports `is MutableList` from Kotlin and throws only on
        // write), a silent no-op would be indistinguishable in the field from "the hook never fired".
        // So a failure here is loud and specific, and the counters are NOT advanced.
        try {
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).add(item)
        } catch (t: Throwable) {
            noteOnce(
                "list-immutable",
                "soul: the menu list is " + list.javaClass.name + " and REFUSED our item (" +
                    t.javaClass.simpleName + ") -- no 分析 entry. The list is not appendable; the " +
                    "append strategy does not apply to this build.",
            )
            return
        }
        // Verified by IDENTITY, not by `List.contains`: the item class declares no `equals`, so
        // today both agree, but an identity check cannot start lying if a future Soul build makes it
        // a data class whose `equals` ignores the id fields.
        var present = false
        for (o in list) {
            if (o === item) {
                present = true
                break
            }
        }
        if (!present) {
            noteOnce(
                "add-noop",
                "soul: the menu list " + list.javaClass.name + " accepted our item without error but " +
                    "it is NOT in the list afterwards -- no 分析 entry (unexpected list semantics)",
            )
            return
        }

        ownItem = item
        // The message is kept HERE, at injection time, because the click arrives long after this hook
        // returns and the row view cannot tell us which message it belongs to. Assigning it before
        // the injection is deliberate: a click can only follow a successful injection, so the field
        // is never written by a menu that never appeared.
        pendingMsg = msg
        lastAnchor = anchorView
        lastInjectAt = now

        Log.i(
            TAG,
            "soul: injected the (" + ITEM_TITLE + ") item into the long-press menu via=clone-of-" +
                "existing-item children=" + list.size + " anchorW=" + anchorView.width,
        )
        // The icon is attached by the AFTER-hook on Soul's own row builder, which fires once Soul has
        // inflated the rows -- so nothing is scheduled here. See [installRowIconHook].
    }

    /** True when our item is already in `list`, judged by OBJECT IDENTITY. */
    private fun containsOurItem(list: List<*>): Boolean {
        val ours = ownItem ?: return false
        for (o in list) {
            if (o === ours) return true
        }
        return false
    }

    /**
     * Builds our menu item by CLONING one the host already put in the list.
     *
     * ============================ WHY A CLONE, NOT A FRESH OBJECT ============================
     * `ChatLongClickPopUp$a` is `(String label, int a, int b)` and we know what NEITHER int means:
     *   * `a()` is passed to `ImageView.setImageResource(int)` in `m0`, so it is a DRAWABLE RESOURCE
     *     ID, not a flag;
     *   * `b()` is the ACTION CODE -- the host's own dispatcher compares it (`u0.p` reads `b()`,
     *     `ImMsgExtKt.m` switches on it).
     *
     * Inventing values for them would produce a row whose icon is a random resource and whose action
     * code may collide with a real menu entry -- i.e. tapping 分析 could delete a message. So the
     * item is CLONED from a real one, which guarantees both ints are values Soul itself produced,
     * and the LABEL field is then replaced with our real title (分析).
     *
     * The label field is the first `String` on the class (verified: `a`). It is written through the
     * FIELD rather than a setter because the class exposes only getters (`c()`), and writing a field
     * on an object we cloned is safe: nothing else holds a reference to it yet.
     *
     * THE LABEL MUST BE THE REAL TITLE, NOT A MARKER: `m0` passes `c()` -- this same field -- to
     * `TextView.setText`, so a sentinel here would be shown to the user. Identity is carried by the
     * object reference instead, which is strictly more reliable. See [isOurItem].
     *
     * If there is no existing item to clone, this REFUSES and says so. It does not synthesise one.
     * A menu with no 分析 entry is a missing feature; a menu with a wrong action code is a data-loss
     * bug, and the two are not comparable.
     * ==========================================================================================
     */
    private fun cloneItem(list: List<*>, msg: Any): Any? {
        val template = list.firstOrNull { it != null && it.javaClass.name == itemClassName() }
            ?: list.firstOrNull { it != null }

        if (template == null) {
            // Verified from `u0.y`'s register-level disassembly: when the list is empty Soul returns
            // BEFORE building the popup at all --
            //   [007e] if-eqz  v10, -> 0x0102      (null list: return)
            //   [0082] invoke-interface List->size()
            //   [008a] if-nez  v0, -> 0x0090       (non-empty: continue)
            //   [008e] goto    -> 0x0102           (EMPTY: return, no menu is shown)
            // so an empty list means NO MENU APPEARS and there is nothing to inject into -- this
            // refusal never costs the user a visible entry. It is reported rather than silent because
            // "list was empty" and "our hook never ran" would otherwise look identical in the log.
            noteOnce(
                "no-template",
                "soul: the menu list is EMPTY. Soul itself shows no menu in this case (verified: " +
                    "u0.y returns early on size()==0), so nothing is missing for the user -- and this " +
                    "file will NOT invent the item's (label, iconRes, action) triple, because the " +
                    "action code is unverified and a wrong one could fire a real menu action.",
            )
            return null
        }

        // Diagnostic: what is in the menu, and what the item we are about to clone looks like. This
        // is the ONE device-run answer to the recon report's open item 2 (the meaning of the two
        // ints) and item 3 (the real label set).
        if (!labelsDumped) {
            labelsDumped = true
            val labels = list.mapNotNull { readLabel(it) }
            Log.i(TAG, "soul PROBE: existing menu labels (" + labels.size + ") = " + labels)
        }
        if (!itemShapeDumped) {
            itemShapeDumped = true
            val a = callIntNoArg(template, "a")
            val b = callIntNoArg(template, "b")
            Log.i(
                TAG,
                "soul PROBE: item shape class=" + template.javaClass.name +
                    " label=" + readLabel(template) +
                    " a(iconRes)=" + a + " (0x" + Integer.toHexString(a) + ")" +
                    " b(actionCode)=" + b +
                    " fields=" + template.javaClass.declaredFields.joinToString(",") { it.name + ":" + it.type.simpleName },
            )
        }

        return try {
            // A SHALLOW CLONE of a real item: every int is a value Soul produced, so the icon and
            // the action code are guaranteed to be ones Soul already accepts.
            val field = findLabelField(template)
            val clone = cloneViaReflection(template)
            if (clone == null) {
                noteOnce(
                    "clone-failed",
                    "soul: could not clone the menu item class " + template.javaClass.name +
                        " -- no 分析 entry (refusing to construct one with invented ints)",
                )
                return null
            }
            if (field == null) {
                noteOnce(
                    "label-field",
                    "soul: the menu item class " + template.javaClass.name +
                        " exposes no String field to hold our label -- no 分析 entry",
                )
                return null
            }
            field.isAccessible = true
            // THE LABEL IS THE VISIBLE MENU TEXT -- it MUST be the real title, not a marker.
            //
            // This is the one place a sentinel would be a user-visible bug: `m0` calls `c()` (which
            // returns this very field) and passes it straight to `TextView.setText`, so anything
            // other than 分析 would be SHOWN to the user. Identity is therefore carried by the OBJECT
            // REFERENCE (`ownItem`), which is strictly stronger anyway -- no rename, no translation
            // and no duplicate label can confuse it. See [isOurItem].
            field.set(clone, ITEM_TITLE)
            clone
        } catch (t: Throwable) {
            Log.w(TAG, "soul: cloning the menu item failed: " + t.message)
            null
        }
    }

    /**
     * The item class name, resolved once.
     *
     * `Class.getSimpleName()` on an inner class returns just `a` (verified for Douyin's
     * `X.09NB`/`inner=a` in `_tools/DOUYIN_MENU_CRASH_P0.md`), so the comparison must use the FULL
     * name -- an inner class's simple name is not unique and would match unrelated objects.
     */
    private fun itemClassName(): String = ITEM_CLASS

    /** The first `String` instance field of the item -- the label, per the verified shape. */
    private fun findLabelField(item: Any): java.lang.reflect.Field? {
        var p: Class<*>? = item.javaClass
        var depth = 0
        while (p != null && depth < 4) {
            for (f in p.declaredFields) {
                if (f.type == String::class.java && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    return f
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * Shallow-clones via `Object.clone()`.
     *
     * `clone()` is a `protected` method on `java.lang.Object`, so it needs reflection and
     * `isAccessible`. It is preferred to the copy constructor because it cannot guess: it copies
     * whatever fields the class has, including any a future Soul build adds.
     *
     * `Cloneable` IS REQUIRED. `Object.clone()` throws `CloneNotSupportedException` for a class that
     * does not implement it, and Soul's `ChatLongClickPopUp$a` is declared as a plain
     * `extends java.lang.Object` with no interface list, so this path is EXPECTED TO FAIL on 6.37.0
     * and [shallowCopyFields] is the path that actually runs. That is fine -- both paths preserve the
     * template's own icon and action codes, which is the only property that matters -- but the two
     * are NOT interchangeable in cost, so which one ran is logged. An earlier comment here claimed
     * `Cloneable` was unnecessary; that was wrong and would have hidden the fallback being primary.
     */
    private fun cloneViaReflection(template: Any): Any? {
        val m = java.lang.Object::class.java.getDeclaredMethod("clone")
        m.isAccessible = true
        return try {
            val copy = m.invoke(template)
            Log.i(TAG, "soul PROBE: the menu item was cloned via Object.clone()")
            copy
        } catch (t: Throwable) {
            // Expected on 6.37.0 (see above): not Cloneable. Copy the fields explicitly instead; the
            // template's own icon/action codes are still carried over, so no int is invented.
            Log.i(
                TAG,
                "soul PROBE: Object.clone() refused the menu item (" + t.javaClass.simpleName +
                    ") -- falling back to an explicit field copy, which preserves the template's own " +
                    "icon and action codes",
            )
            shallowCopyFields(template)
        }
    }

    /**
     * Builds a copy when `Object.clone()` refuses (the expected path on 6.37.0).
     *
     * The ONLY accepted shape is the verified one: a single `(String, int, int)` constructor whose
     * `int` slots are then filled from the TEMPLATE's own `a()` / `b()` accessors. Verified layout
     * (`ChatLongClickPopUp$a`):
     *
     *   <init>(String, int, int)   writes fields  a : String, b : int, c : int
     *   a()I                       reads  b   -> the icon resource id
     *   b()I                       reads  c   -> the action code
     *   c()String                  reads  a   -> the label
     *
     * so `args = [label, iconRes, actionCode]` maps one-for-one onto the constructor's parameters --
     * confirmed against the enum's own construction sites, e.g. `ChatMsgLongClickMenu.<clinit>`
     * builds `GroupChatLongClickPopUp$a("<label>", <b>, <c>)`.
     *
     * ANY OTHER SHAPE IS REFUSED. A constructor this code cannot fill from the template would receive
     * the placeholder zeros built below, and those are indistinguishable from a real action code --
     * the dispatcher switches on that value, so a zero could select an arbitrary real entry. An
     * earlier version of this function only guarded the `size == 3` case, which left exactly that hole
     * open for a hypothetical other shape; that guard is now the precondition, not an afterthought.
     */
    private fun shallowCopyFields(template: Any): Any? {
        val cls = template.javaClass
        val ctor = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 3 &&
                c.parameterTypes[0] == String::class.java &&
                c.parameterTypes[1] == java.lang.Integer.TYPE &&
                c.parameterTypes[2] == java.lang.Integer.TYPE
        }
        if (ctor == null) {
            noteOnce(
                "copy-shape",
                "soul: the menu item class " + cls.name + " has no (String,int,int) constructor (it " +
                    "has " + cls.declaredConstructors.size + " constructor(s)) and is not cloneable -- " +
                    "REFUSING to build the 分析 item, because any other shape would need an invented " +
                    "action code passed to Soul's dispatcher. No entry.",
            )
            return null
        }

        // The int slots come from the TEMPLATE's OWN accessors, never from a placeholder: the host
        // dispatcher acts on the action code, so a wrong value could copy or delete a message.
        val iconRes = callIntOrNull(template, "a")
        val actionCode = callIntOrNull(template, "b")
        if (iconRes == null || actionCode == null) {
            noteOnce(
                "fallback-no-codes",
                "soul: cannot read the item's own icon/action codes (" +
                    cls.name + ".a()/b()) and the class is not cloneable -- REFUSING to build the " +
                    "分析 item rather than pass an invented action code to Soul's dispatcher. No entry.",
            )
            return null
        }

        ctor.isAccessible = true
        return try {
            ctor.newInstance("", iconRes, actionCode)
        } catch (t: Throwable) {
            Log.w(TAG, "soul: the item copy-constructor fallback failed: " + t.message)
            null
        }
    }

    // ------------------------------------------------------------------ icon
    //
    // The icon is attached from [installRowIconHook], which runs AFTER Soul's own row builder. There
    // is deliberately NO timer and NO window search here: see that function's comment for the two
    // concrete reasons the obvious-looking approaches were wrong (razerdp is not a PopupWindow, and
    // `ChatLongClickPopUp.M` is not on the menu path).

    /**
     * The popup's item container, captured from `m0(List)`'s own receiver.
     *
     * WHY THE ICON IS ATTACHED FROM `m0(List)` AND NOT FROM A TIMER:
     *
     * An earlier draft of this file attached the icon from a few posted callbacks that searched the
     * ANCHOR view's window for our row. That was wrong twice over, and both reasons were found by
     * disassembling Soul rather than by guessing:
     *
     *   1. `razerdp.basepopup.BasePopupWindow` is NOT `android.widget.PopupWindow` -- it extends
     *      `java.lang.Object` and keeps its decor in a `BasePopupHelper`. So the menu lives in its own
     *      window and `anchor.rootView` is the CHAT SCREEN: a search rooted there finds nothing, ever,
     *      and silently, because "no row" is a legal state for a timer.
     *   2. `ChatLongClickPopUp.M(View)` looked like the content-view setter, but it is NOT called on
     *      the menu path. `u0.y` calls only `<init>(Context)`, `m0(List)`, `p0(listener)`,
     *      `PrivateReactionBinder.b(...)` and `BasePopupWindow.g0(View)` -- `M` is not in that list, so
     *      a hook on it would have been installed and NEVER fired.
     *
     * `m0(List)` is the one method that is certainly called, and it is where the rows are built
     * (verified: `[003f] LayoutInflater.from`, `[0046] inflate`, `[0052] c()` label,
     * `[0056] TextView.setText`, `[0065] ImageView.setImageResource`, `[007f] ViewGroup.addView`).
     * So hooking it AFTER the original returns means every row -- ours included -- is already in the
     * container, and the icon is attached in the same frame the menu is built, with no timer, no
     * retry and no window guesswork.
     *
     * The container is Soul's own field `n`: `GridLayout` on the single-chat popup (read from
     * `ChatLongClickPopUp.M`'s `iput-object ... n : android.widget.GridLayout`) and `LinearLayout` on
     * the group popup. Group chat is out of scope for this task, so ONLY the single-chat class is
     * hooked -- see the class comment.
     */

    /**
     * Hooks the row builder AFTER it runs, so the ORIGINAL [AnalysisIcon] can be attached.
     *
     * This is an observation-and-decorate hook: on every failure path it does nothing at all, so it
     * can degrade the icon but never the menu.
     */
    private fun installRowIconHook() {
        val cls = loadClass(POPUP_CLASS)
        if (cls == null) {
            Log.e(
                TAG,
                "soul: " + POPUP_CLASS + " is not loadable, so the menu rows cannot be reached -- " +
                    "the 分析 entry still appears (Soul builds it), but the ORIGINAL icon cannot be " +
                    "attached. No substitute icon is drawn.",
            )
            return
        }
        val builder = cls.declaredMethods.firstOrNull { m ->
            m.parameterCount == 1 && m.parameterTypes[0] == java.util.List::class.java
        }
        if (builder == null) {
            Log.e(
                TAG,
                "soul: no (List) row builder on " + POPUP_CLASS + " -- the 分析 icon cannot be " +
                    "attached (the entry itself still works). Methods: " +
                    cls.declaredMethods.joinToString(",") { it.name },
            )
            return
        }
        LegacyHook.hook(builder)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .interceptAfter { chain ->
                try {
                    // `thisObject` is the popup, so the container is read straight off it -- no view
                    // tree search and no window assumption.
                    val popup = chain.thisObject
                    val container = readField(popup, CONTAINER_FIELD) as? android.view.ViewGroup
                    if (container == null) {
                        Log.w(
                            TAG,
                            "soul: the menu container field '" + CONTAINER_FIELD + "' on " +
                                popup.javaClass.name + " is absent -- the ORIGINAL 分析 icon cannot be " +
                                "attached (entry still works, no substitute icon drawn)",
                        )
                        return@interceptAfter
                    }
                    attachIconToOurRow(container)
                } catch (t: Throwable) {
                    Log.w(TAG, "soul: attaching the icon after " + builder.name + " failed: " + t.message)
                }
            }
        Log.i(
            TAG,
            "soul: hooked the menu row builder (" + cls.name + "." + builder.name +
                "(List)) AFTER the original, to attach the ORIGINAL 分析 icon",
        )
    }

    /** Reads a named instance field, searching up the superclass chain. */
    private fun readField(obj: Any, name: String): Any? {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 5) {
            try {
                val f = p.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                // not on this level -- keep climbing
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * Rows whose icon has already been replaced, keyed by the row VIEW.
     *
     * WHY PER-ROW AND NOT A SINGLE GLOBAL FLAG: Soul builds a FRESH row for every long press, so a
     * one-shot boolean would attach the icon to the first menu the user ever opened and to none
     * afterwards -- a bug that looks exactly like "the icon randomly stopped working". Keying on the
     * row makes the check mean what it should: "has THIS row got its icon yet?".
     *
     * A `WeakHashMap` because the rows are recycled by the host; holding them strongly would leak a
     * view per menu open, and each view holds its Activity's Context.
     */
    private val iconDone = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>(),
    )

    /**
     * Finds OUR row inside the menu container and gives it the original icon.
     *
     * The search is rooted at the CONTAINER Soul itself built the rows in -- not at a window and not
     * at the anchor -- which is what makes it reliable. This is still R4's discipline applied to this
     * host: ask the OWNER of the content, never a subtree that merely looks related.
     */
    private fun attachIconToOurRow(container: android.view.ViewGroup) {
        val row = findOurRow(container)
        if (row == null) {
            noteOnce(
                "row-not-found",
                "soul: the menu container " + container.javaClass.name + " has " + container.childCount +
                    " row(s) and none of them shows our marker -- the ORIGINAL 分析 icon stays " +
                    "unattached (the entry itself works)",
            )
            return
        }
        if (iconDone.containsKey(row)) {
            // This very row already got its icon. Soul can call the builder more than once for one
            // menu, and replacing the Drawable repeatedly is wasted work.
            return
        }
        iconDone[row] = true
        // Tag the row as OUR UI, using a KEYED tag rather than the single Object slot.
        //
        // `View` has exactly ONE `tag` field, and the keyed variant stores into a separate sparse
        // array, so this cannot clobber anything Soul may have put there. The key must be
        // application-specific (`key >>> 24 >= 2`) or `View.setTag(int,Object)` throws
        // IllegalArgumentException -- 0x4C4C0100 satisfies that, while the module's 0x4C4C01 item ids
        // would NOT (0x4C4C01 >>> 24 == 0), so those cannot be reused here.
        try {
            row.setTag(OWN_TAG_KEY, TAG_OUR_ROW)
        } catch (_: Throwable) {
            // A row that refuses a tag is not worth failing over.
        }
        attachIcon(row)
    }

    /**
     * Finds OUR row: the LAST child of the container.
     *
     * WHY POSITION AND NOT A TEXT MARKER: the label cannot be a marker -- it is the text the user
     * reads (`Analysed` via `TextView.setText`). And a label search would be wrong even if we wanted
     * one: a future Soul menu item could legitimately be called 分析.
     *
     * The position IS the identity here, and that is not a guess: we APPENDED our item to the item
     * list, and `m0` builds the rows by iterating that list and calling `addView` for each, so our
     * row is necessarily the last `addView`'d child. That is a direct consequence of the two
     * verified bodies ([`onMenuBuilding`] adds at the end; `m0` appends in list order), not an
     * assumption about Soul's layout.
     *
     * The row is still checked for sanity: it must contain a `TextView` (so it really is a menu row
     * and not, say, a header the host appended after us) and its text must be our title.
     */
    private fun findOurRow(container: android.view.ViewGroup): View? {
        if (container.childCount == 0) return null
        val candidate = container.getChildAt(container.childCount - 1) ?: return null
        // Sanity: our row shows our title. If a later Soul build appends something of its own AFTER
        // our item, this check fails and the icon is skipped (and logged) rather than misapplied to
        // an unrelated row.
        val label = firstTextView(candidate, 0)
        if (label == null || label.text?.toString() != ITEM_TITLE) {
            noteOnce(
                "row-shape",
                "soul: the last row in " + container.javaClass.name + " (childCount=" +
                    container.childCount + ") shows " +
                    (label?.text?.toString()?.let { "'" + it + "'" } ?: "no TextView") +
                    " instead of '" + ITEM_TITLE + "' -- the ORIGINAL icon was NOT attached, to " +
                    "avoid putting it on the wrong menu item",
            )
            return null
        }
        return candidate
    }

    /** The first `TextView` in `v`'s subtree, bounded. */
    private fun firstTextView(v: View, depth: Int): android.widget.TextView? {
        if (depth > 8) return null
        if (v is android.widget.TextView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                firstTextView(v.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * Puts the module's own icon glyph into our row's icon slot.
     *
     * The slot is the row's `ImageView` -- the SAME one `m0` fills with `setImageResource(textIconRes)`
     * for every other item. Overwriting it with `setImageDrawable` is safe and is NOT an extra
     * drawing: the value comes from [AnalysisIcon.toDrawable], the function WeChat's MenuItem path
     * already uses at the same 20 dp.
     */
    private fun attachIcon(row: View) {
        val ctx = row.context ?: run {
            noteOnce("icon-ctx", "soul: our row has a null Context -- cannot build the icon")
            return
        }
        val iv = firstImageView(row)
        if (iv == null) {
            // REPORTED, not silently replaced: requirement 3 forbids drawing a substitute icon, so a
            // missing icon slot is a fact to log, never a reason to invent a glyph.
            noteOnce(
                "icon-slot",
                "soul: our row " + row.javaClass.name + " has no ImageView, so the ORIGINAL 分析 " +
                    "icon cannot be attached (the entry itself works). No replacement icon is drawn " +
                    "-- see the class comment for why.",
            )
            return
        }
        val density = ctx.resources.displayMetrics.density
        val d = try {
            AnalysisIcon.toDrawable(ctx, (ICON_SIZE_DP * density).toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "soul: AnalysisIcon.toDrawable failed: " + t.message)
            return
        }
        try {
            iv.setImageDrawable(d)
            Log.i(
                TAG,
                "soul: attached the ORIGINAL 分析 icon (AnalysisIcon.toDrawable, " + ICON_SIZE_DP +
                    "dp) to the row's " + iv.javaClass.name + " -- same icon and same source as WeChat",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "soul: setImageDrawable on our row failed: " + t.message)
        }
    }

    private fun firstImageView(v: View): android.widget.ImageView? {
        var p: View? = v
        var depth = 0
        // Breadth is small (a menu row), so a bounded depth-first search is enough and cannot wander
        // into the rest of the window.
        fun dig(x: View, d: Int): android.widget.ImageView? {
            if (d > 6) return null
            if (x is android.widget.ImageView) return x
            if (x is android.view.ViewGroup) {
                for (i in 0 until x.childCount) {
                    dig(x.getChildAt(i), d + 1)?.let { return it }
                }
            }
            return null
        }
        p?.let { return dig(it, 0) }
        return null
    }

    // ------------------------------------------------------------------ click routing

    /**
     * Routes only OUR row to the analysis, by hooking the row's own click handler.
     *
     * ============================ WHY NOT WRAP THE HOST LISTENER ============================
     * The obvious design is to wrap Soul's `OnMenuItemClickListener` (installed by `p0`) and
     * intercept when the item is ours. That design was written, then REJECTED, because of what
     * `ChatLongClickPopUp$b.onClick` actually does (register-level disassembly, classes13.dex):
     *
     *   [0040] iget-object   v9, v8, $b.a : View              the row
     *   [0050] iget-wide     v5, v8, $b.b : J                 debounce timestamp
     *   [005c] iget-object   v9, v8, $b.c : ChatLongClickPopUp
     *   [0060] invoke-static ChatLongClickPopUp->l0(...)      the HOST listener
     *   [0076] iget-object   v4, v8, $b.e : ChatLongClickPopUp$a   <-- THE ITEM
     *   [007a] iget          v5, v8, $b.f : I                 <-- the ACTION CODE
     *   [007e] invoke-interface OnMenuItemClickListener->onMenuItemClick(View, $a, I)
     *
     * Our item is a CLONE, so its action code is whatever Soul put in the item we cloned from --
     * 复制, 撤回, 删除. The host listener is therefore being handed a REAL action code for our row,
     * and it acts on it. A listener wrapper that ever failed to intercept would not merely miss our
     * button -- it would run the template's action on the user's message. Tapping 分析 could delete.
     *
     * Wrapping is also the less reliable option in this codebase: it needs
     * `java.lang.reflect.Proxy` over a HOST interface, and nothing else in this module has ever used
     * one, so its behaviour inside Soul's loader would be unproven on the one path that matters.
     *
     * ============================ WHAT THIS DOES INSTEAD ============================
     * Hooking `$b.onClick` itself, BEFORE it runs, is strictly better on both counts:
     *
     *   * the interception point is upstream of `l0`, so when our row is tapped the host listener is
     *     NEVER INVOKED -- the destructive path is not "avoided by our logic", it is unreachable;
     *   * it hooks a concrete class in Soul with the same `LegacyHook` primitive every other hook in
     *     this module uses, so there is no unproven mechanism on the click path.
     *
     * The item is read from `$b`'s own field (type `$a`) rather than by name, so a re-lettering
     * obfuscator pass cannot silently turn "our row" into "some row": the field is located BY TYPE.
     * ==================================================================================
     */
    private fun installClickHook() {
        val rowClass = loadClass(ROW_CLICK_CLASS)
        if (rowClass == null) {
            Log.e(
                TAG,
                "soul: " + ROW_CLICK_CLASS + " is not loadable -- the 分析 entry will APPEAR but " +
                    "tapping it will do NOTHING (no analysis is started, and no other menu action is " +
                    "triggered either)",
            )
            return
        }
        val onClick = rowClass.declaredMethods.firstOrNull { m ->
            m.name == "onClick" && m.parameterCount == 1 && m.parameterTypes[0] == View::class.java
        }
        if (onClick == null) {
            Log.e(
                TAG,
                "soul: no onClick(View) on " + ROW_CLICK_CLASS + " -- the 分析 entry will appear but " +
                    "will not respond. Methods: " +
                    rowClass.declaredMethods.joinToString(",") { it.name },
            )
            return
        }
        LegacyHook.hook(onClick)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .intercept { chain ->
                val rowClick = chain.thisObject
                // Identify the row FIRST, outside the analysis's own try/catch, and decide the fate of
                // the host dispatch from that verdict alone.
                val item = try {
                    itemOf(rowClick)
                } catch (t: Throwable) {
                    Log.w(TAG, "soul: reading the item off " + rowClick.javaClass.name + " failed: " + t.message)
                    null
                }

                if (isOurItem(item)) {
                    // SKIP THE HOST DISPATCH UNCONDITIONALLY for our row.
                    //
                    // This must happen even if the analysis below throws. `$b.onClick` continues into
                    // `l0(...)` and hands the HOST listener our item's action code, which is a CLONE
                    // of a real entry -- 复制, 撤回 or 删除. Letting it run as a "safe fallback" after
                    // a failure would therefore not be safe at all: it would execute that real action
                    // on the user's message. A dead button is the acceptable failure; a deleted message
                    // is not. So the skip is decided here, before anything that can throw.
                    chain.skipOriginal()
                    try {
                        onOurItemClicked(chain.getArg(0) as? View, rowClick)
                    } catch (t: Throwable) {
                        // Reported loudly: the user tapped our entry and got nothing, and the log has
                        // to say that rather than leaving an unexplained missing card.
                        Log.w(TAG, "soul: the 分析 tap was accepted but starting it failed: " + t.message, t)
                    }
                }
            }
        Log.i(
            TAG,
            "soul: hooked " + rowClass.name + ".onClick BEFORE the host dispatch -- our row is routed " +
                "to the analysis and the host's own action is skipped for it",
        )
        clickHookInstalled = true
    }

    /**
     * Reads the item object out of a row-click handler instance.
     *
     * Located BY TYPE (`$a`), not by field name: `$b` also holds a `View` (`a`), a long (`b`), the
     * popup (`c`) and an int (`f`), and a name-based read would be the first thing an obfuscator
     * pass breaks. The type is a fact about what the class IS; the name is not.
     */
    private fun itemOf(rowClick: Any): Any? {
        var p: Class<*>? = rowClick.javaClass
        var depth = 0
        while (p != null && depth < 4) {
            for (f in p.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type.name == ITEM_CLASS) {
                    f.isAccessible = true
                    return f.get(rowClick)
                }
            }
            p = p.superclass
            depth++
        }
        noteOnce(
            "no-item-field",
            "soul: " + rowClick.javaClass.name + " has no field of type " + ITEM_CLASS +
                " -- cannot tell which row was tapped, so our row is not routed",
        )
        return null
    }

    /** True when `item` is the object WE appended. Identity, not equality, not a label. */
    private fun isOurItem(item: Any?): Boolean {
        if (item == null) return false
        // THE criterion: the exact object we created and put in the list. It cannot be forged by a
        // label, a translation, or a future Soul menu item that happens to be called 分析 -- which is
        // exactly why the label field is free to hold the real, user-visible title.
        return item === ownItem
    }

    /**
     * Runs the analysis for the message the menu was opened on.
     *
     * Uses `controller.startAnalysisFor(msg, view)` -- the SAME entry `MenuHook` and `DouyinHooks`
     * call -- so behaviour is identical across all three hosts.
     */
    private fun onOurItemClicked(row: View?, rowClick: Any?) {
        try {
            Log.i(
                TAG,
                "soul: OUR MENU ITEM TAPPED row=" + (row?.javaClass?.name ?: "null"),
            )

            // Close the menu first, exactly like Douyin: the scope dialog and the analysis card are
            // hosted in the chat screen, and leaving the popup on top makes them unreachable.
            closeMenu(rowClick)

            val msg = pendingMsg
            if (msg == null) {
                Log.w(TAG, "soul: our item was tapped but no message was captured -- nothing to analyse")
                return
            }

            val access = controller.access()
            if (!access.isText(msg)) {
                val ctx = row?.context ?: com.luoliaoo.wechat.core.AppContextHolder.get()
                if (ctx != null) {
                    Toast.makeText(
                        ctx,
                        HostApp.current.say("\u8be5\u6d88\u606f\u65e0\u6587\u672c"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                Log.i(TAG, "soul: no text in this message -- 该消息无文本")
                return
            }

            Log.i(
                TAG,
                "soul: analysing from the menu row -- talker=" + access.talker(msg) +
                    " len=" + access.content(msg).length,
            )
            controller.startAnalysisFor(msg, row)
        } catch (t: Throwable) {
            Log.w(TAG, "soul: could not start the analysis: " + t.message)
        }
    }

    /**
     * The message the menu was opened on, captured at injection time.
     *
     * Kept from `onMenuBuilding` because the click arrives LONG after the hook returns (Soul's own
     * listener is invoked by the platform, not by us), and the message is not reachable from the row
     * view. Douyin keeps its message the same way, for the same reason.
     */
    @Volatile
    private var pendingMsg: Any? = null

    /**
     * Dismisses Soul's popup using SOUL'S OWN dismissal call.
     *
     * `razerdp.basepopup.BasePopupWindow.f(Z)` is EXACTLY what the host itself calls to close this
     * menu: `ChatLongClickPopUp$b.onClick` ends with
     *
     *   [0084] iget-object   v9, v8, $b.c : ChatLongClickPopUp
     *   [0088] invoke-virtual BasePopupWindow->f(Z)V
     *
     * so calling it puts the popup through the same animation and cleanup path every other menu item
     * uses, instead of inventing a dismissal.
     *
     * WHY NOT A SYNTHETIC BACK KEY (the earlier design): dispatching `KEYCODE_BACK` to the decor root
     * relies on the popup being focusable AND on winning the key dispatch against the chat screen. If
     * it ever loses, the CHAT SCREEN handles BACK instead -- i.e. the user's 分析 tap would navigate
     * them out of the conversation. That is a much worse failure than a menu that lingers a moment, so
     * the host's own API is used and the popup is taken from the row-click object that invoked us
     * (never a stored reference, which could be stale after Soul rebuilds its menu).
     *
     * It is not fatal either way: the analysis has already been started, and a stale popup would be
     * dismissed by the user's next tap.
     */
    private fun closeMenu(rowClick: Any?) {
        val popup = popupOf(rowClick)
        if (popup == null) {
            // NOT a silent return (必看.md hard rule). The analysis still runs, so this is cosmetic --
            // but the log must say the menu was left open and WHY, or a future report of "the menu
            // stays on screen" would have nothing to point at.
            noteOnce(
                "close-no-popup",
                "soul: could not read the popup off " + (rowClick?.javaClass?.name ?: "null") +
                    " -- the menu is left open on screen. The analysis itself already started; the " +
                    "user's next tap dismisses the menu.",
            )
            return
        }
        try {
            val m = popup.javaClass.getMethod("f", java.lang.Boolean.TYPE)
            m.isAccessible = true
            m.invoke(popup, true)
        } catch (t: Throwable) {
            Log.w(TAG, "soul: dismissing the menu via BasePopupWindow.f(Z) failed (not fatal): " + t.message)
        }
    }

    /**
     * Reads the POPUP out of a row-click handler instance.
     *
     * Located BY TYPE (`ChatLongClickPopUp`), for the same reason [itemOf] locates the item by type:
     * `$b` holds two `View` fields and a `long`, so a name-based read would be the first thing a
     * re-lettering obfuscator pass breaks.
     */
    private fun popupOf(rowClick: Any?): Any? {
        if (rowClick == null) return null
        var p: Class<*>? = rowClick.javaClass
        var depth = 0
        while (p != null && depth < 4) {
            for (f in p.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type.name == POPUP_CLASS) {
                    f.isAccessible = true
                    return try {
                        f.get(rowClick)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ runtime probe

    /**
     * One-shot probe, mirroring the Douyin probe.
     *
     * PURPOSE: a device run must answer the questions static analysis cannot, in ONE pass:
     *   1. is the menu hook INSTALLED and DID IT FIRE (the 必看.md trap: "hook installed but fired = 0"
     *      is indistinguishable from a broken install unless the counter is logged);
     *   2. what does Soul's own `changeQuickRedirect` say (every Soul class is Tinker-patched, so a
     *      non-null redirect on `u0` means the body we hooked may not be the one that runs);
     *   3. which class loader each class resolved through (`Resolver.classLoader()` vs the raw ctor
     *      loader -- the DelegateLastClassLoader clone bug from 必看.md).
     *
     * It reports by CLASS NAME (a `Set`), never by event count, per the hard rule.
     */
    private fun installRuntimeProbe() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                Log.i(
                    TAG,
                    "soul PROBE: menu hook fired=" + menuFired +
                        " clickHook=" + clickHookInstalled +
                        " (fired=0 after a long press means the hook is installed but Soul ran a " +
                        "different body -- check the Tinker state below)",
                )
                probeClass(MENU_HOST)
                probeClass(POPUP_CLASS)
                probeClass(ITEM_CLASS)
                probeClass(ROW_CLICK_CLASS)
                probeClass(MSG_CLASS)
                Log.i(
                    TAG,
                    "soul PROBE: distinct class names reported=" + reportedClasses.size +
                        " " + reportedClasses,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "soul: the runtime probe failed: " + t.message)
            }
        }, 4000L)
    }

    /**
     * Reports one host class: which loader resolved it, and its Tinker redirect state.
     *
     * Reported ONCE per class name. This is the mechanism that keeps the log readable while still
     * covering every host class, which a count limit cannot do.
     */
    private fun probeClass(name: String) {
        if (!reportedClasses.add(name)) return
        val cls = loadClass(name)
        if (cls == null) {
            Log.w(TAG, "soul PROBE: " + name + " NOT loadable through Resolver.classLoader()")
            return
        }
        Log.i(
            TAG,
            "soul PROBE: " + name +
                " loader=" + shorten(cls.classLoader?.javaClass?.name) +
                " tinker=" + tinkerState(cls),
        )
    }

    /** `null` redirect means Tinker has NOT patched this class, i.e. our hook sees the real body. */
    private fun tinkerState(cls: Class<*>): String = try {
        val f = cls.getDeclaredField(TINKER_FIELD)
        f.isAccessible = true
        val v = f.get(null)
        if (v == null) "redirect=null (not patched -- our hook sees the real body)" else "redirect=" + v.javaClass.name
    } catch (_: Throwable) {
        "no " + TINKER_FIELD + " field"
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Reads the item's label through its `c()` accessor, falling back to the `String` field.
     *
     * Both paths are verified: `c()` is `iget-object ... a` and `a` is the class's only `String`
     * field (checked by disassembling `ChatLongClickPopUp$a.<init>` and `c()`).
     */
    private fun readLabel(item: Any?): String? {
        if (item == null) return null
        try {
            item.javaClass.getMethod("c").let {
                it.isAccessible = true
                (it.invoke(item) as? String)?.let { s -> return s }
            }
        } catch (_: Throwable) {
            // fall through to the field
        }
        return try {
            val f = findLabelField(item) ?: return null
            f.isAccessible = true
            f.get(item) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** Calls a no-arg int accessor (`a()` / `b()`), returning -1 when absent. */
    private fun callIntNoArg(obj: Any, name: String): Int = try {
        val m = obj.javaClass.getMethod(name)
        m.isAccessible = true
        (m.invoke(obj) as? Int) ?: -1
    } catch (_: Throwable) {
        -1
    }

    /**
     * Calls a no-arg int accessor, returning `null` when it is absent.
     *
     * Separate from [callIntNoArg] on purpose: `-1` is fine for a LOG LINE and unacceptable as a
     * value handed to Soul, so the two uses must not share a sentinel.
     */
    private fun callIntOrNull(obj: Any, name: String): Int? = try {
        val m = obj.javaClass.getMethod(name)
        m.isAccessible = true
        m.invoke(obj) as? Int
    } catch (_: Throwable) {
        null
    }

    /**
     * Loads a HOST class through `Resolver.classLoader()` FIRST.
     *
     * This is the DelegateLastClassLoader lesson from 必看.md, and it is the single most expensive
     * mistake this project has made: in a clone, `Class.forName` with the module's own loader returns
     * a DIFFERENT `Class` object for the same name, so every hook installs "successfully" and then
     * never fires. The raw constructor loader is only a fallback, for the case where the resolver is
     * not attached yet.
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

    private fun loaderName(): String = try {
        shorten(loader.javaClass.name) ?: "null"
    } catch (_: Throwable) {
        "null"
    }

    /** Last path segment only -- full loader names are noise in a log line. */
    private fun shorten(s: String?): String? = s?.substringAfterLast('.')

    /**
     * Logs a diagnostic line ONCE per key.
     *
     * 必看.md hard rule: every early return must leave a trace, otherwise "nothing happened" and
     * "the code never ran" look identical in the log -- and that ambiguity has cost this project
     * multiple rounds. The de-duplication is by KEY (a stable name), never by a counter.
     */
    private fun noteOnce(key: String, line: String) {
        if (reportedClasses.add("note:" + key)) {
            Log.w(TAG, line)
        }
    }
}
