package com.luoliaoo.wechat.hook

import android.view.View
import com.luoliaoo.wechat.BuildInfo
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * KUAISHOU hook installer -- the sibling of [DouyinHooks] and [SoulHooks].
 *
 * ============================ WHAT THIS FILE IS RESPONSIBLE FOR ===============================
 *
 * Three hooks, installed independently so one Kuaishou internal rename degrades ONE feature:
 *
 *   1. the live-history feed      -- pushes every bound message into `controller.onLiveMessage`,
 *                                    which is what makes records appear WITHOUT the user tapping
 *                                    分析 (the 2s flush timer is host-agnostic and already running);
 *   2. the long-press menu item   -- adds 分析 to Kuaishou's message option panel;
 *   3. the multi-select entry     -- the 完成 button path for batch analysis.
 *
 * ============================== THE TWO SUPPLIED CLASSES =====================================
 *
 *   KuaishouHooks(loader, resolver, controller).install()   -- this file
 *   KuaishouMsgAccess(resolver)                             -- the message reader
 *
 * The three-argument constructor matches [DouyinHooks], [SoulHooks] and [SoulHistoryHooks]
 * CHARACTER FOR CHARACTER, so the KUAISHOU arm of `WeChatHooks`' per-host `when` is a single direct
 * reference with no adaptation. A DIRECT reference, never `Class.forName`: 必看.md records that a
 * reflective bridge let `SoulHistoryHook` vs `SoulHistoryHooks` compile cleanly and install nothing
 * at runtime, while the log helpfully said the class "does not exist in this build".
 *
 * ############################################################################################
 * # CONSTRAINT (KS_BUILD_PLAN.md R21): `Resolver` is used for ONE method only -- classLoader(). #
 * # `points` / `resolve()` / `msgInfoClass()` are WeChat-only: inside Kuaishou every lookup      #
 * # through them returns null, which reads in the log like "the Kuaishou class does not exist"   #
 * # while it plainly does. Every host class here is resolved by name through the HOST loader.    #
 * ############################################################################################
 *
 * ============================== WHY `bindViewHolder`, NOT `onBindViewHolder` ====================
 *
 * 必看.md: `onBindViewHolder` is an ORDINARY method that every adapter OVERRIDES, so hooking the
 * base declaration hooks a method no adapter ever calls -- that cost Douyin a whole debug round.
 * `RecyclerView.Adapter.bindViewHolder(ViewHolder, int)` is `public final`: it cannot be overridden,
 * and every adapter's every bind passes through it. Verified present in THIS apk as
 * `access=0x11` (public final), identical to Soul. The modifiers are LOGGED so the log itself
 * proves it was the final funnel.
 *
 * ============================== WHY THE MESSAGE COMES FROM THE ADAPTER ==========================
 *
 * 必看.md: get the message from the ADAPTER by index, not from the ViewHolder. For Kuaishou that
 * advice is STRONGER than usual, because the tooling route is worse than useless:
 * `NextMessageAdapter.onBindViewHolder` -> `a2(...)` contains NO `setTag`/`getTag` (verified by
 * grepping the disassembly), and `h8l.f`'s fields are obfuscated `b/c/d/e`. So the Douyin
 * "read the message out of the holder's tag" route CANNOT work here and is not attempted.
 *
 * Kuaishou's own shape, all verified from the dex:
 *
 *     NextMessageAdapter.o2(I)Lcom/kwai/imsdk/msg/KwaiMsg;
 *         -> p8l.a.getItem(I)                      (the un-obfuscated ancestor data API)
 *         -> check-cast ...model.bean.b            (the row model)
 *         -> bean.b.h()Lcom/kwai/imsdk/msg/KwaiMsg; (its `originMsg` field)
 *
 * and `o2(I)` returns NULL for a position that is not a message row, which is a normal answer.
 *
 * ============================== THE WRAPPER TRAP (R19) ========================================
 *
 * `RecyclerView.getAdapter()` inside the chat page is NOT `NextMessageAdapter`. It is the
 * header/footer wrapper `Lp8l/f;`, whose `getItemCount()` is
 * `footerCount + headerCount + realAdapter.getItemCount()` and whose `onBindViewHolder` maps
 * positions in that combined space. Passing a wrapped position straight to `o2(I)` would shift every
 * message -- silently, which is the worst possible failure for a history feature.
 *
 * So the resolution order below unwraps FIRST:
 *   1. if the adapter itself answers `o2(int)` -> use it, and it gets the position it was given
 *      (a real `NextMessageAdapter` is only reached this way when it IS the adapter on the RV);
 *   2. otherwise unwrap the wrapper `Lp8l/f;` using `c2()` ONLY, and use that adapter with the
 *      position REWRITTEN by subtracting the header count (see `unwrapAdapter` for why `g2()`/`e2()`
 *      must NOT be tried as substitutes: they are the HEADER and FOOTER adapters, so a
 *      first-non-null loop silently reads every index against the wrong list);
 *   3. otherwise use `p8l.a.getItem(I)` on whatever adapter we hold, then unwrap a `bean.b` row via
 *      its `h()` accessor.
 *
 * Every step that cannot be taken is LOGGED with its reason -- 必看.md: an exit with no log is
 * indistinguishable from code that never ran.
 *
 * ============================== DEGRADE, DO NOT GUESS ==========================================
 *
 * No device is attached. Every uncertain step is found by SHAPE (parameter/return types) or by a
 * name VERIFIED in the dex (the comment says which), never by a guessed member name. Anything that
 * cannot be read returns nothing and says so. An idle feed that explains itself beats a feed that
 * appears to work and writes nothing.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouHooks(
    /**
     * The HOST classloader, handed over by the installer.
     *
     * DELIBERATELY UNUSED -- see [loadClass]. Accepted because the KUAISHOU arm of the per-host
     * `when` in `WeChatHooks` constructs every hook set with the SAME three-argument contract, and
     * matching that contract is what lets this class be installed with one uncommented line. The
     * host loader is then ignored in favour of `Resolver.classLoader()`, because 必看.md makes that
     * a hard rule: in a clone the two resolve the same name to DIFFERENT Class objects, and only the
     * host's copy is the one the host actually calls.
     */
    @Suppress("UNUSED_PARAMETER") private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    /**
     * The Kuaishou message reader, built here so callers only need the documented contract.
     *
     * The feed reads through the Kuaishou-specific reader directly rather than through the generic
     * `MsgAccess` facade: the classification (text vs placeholder vs binary payload) is Kuaishou's
     * own, and routing it through the facade would put another host's assumptions in the path.
     * (`MsgAccess` still needs its KUAISHOU branch for the ~29 analysis-path callers -- that is a
     * separate, equally required change.)
     */
    private val access = KuaishouMsgAccess(resolver)

    private companion object {
        const val TAG = "LuoLiao"

        const val RV_ADAPTER = "androidx.recyclerview.widget.RecyclerView\$Adapter"
        const val RV_HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"
        const val RV_CLASS = "androidx.recyclerview.widget.RecyclerView"

        /**
         * The chat-page adapter, verified: `NextMessageAdapter.o2(I)` returns the message.
         * Declared here so the log can name what we EXPECT to see next to what we actually got.
         */
        const val CHAT_ADAPTER = "com.yxcorp.gifshow.message.next.chat.children.message_list." +
            "children.message_item.NextMessageAdapter"

        /** The row model between the adapter and the message; its `h()` returns `originMsg`. */
        const val ROW_MODEL = "com.yxcorp.gifshow.message.next.chat.children.message_list." +
            "children.message_item.model.bean.b"

        /**
         * The long-press menu COMPONENT: `b.Z0(View,Rect,Point)` is the single funnel every message
         * long-press passes through (its only caller is `...children.b$h.invoke`, xref count 1).
         */
        const val MENU_HOST = "com.yxcorp.gifshow.message.next.chat.children.message_list." +
            "children.message_item.children.b"

        /**
         * The menu panel's item-area adapter: `aaj.d` extends `aaj.j` and its `d()` is
         * `check-cast r9j.k` on the List it returns, i.e. `List<r9j.k>` is what the RecyclerView binds.
         *
         * THIS is the append point. It is a CONCRETE class, unlike the `rqj.f` interface that feeds it,
         * and appending an object to a List that a RecyclerView will bind is structurally safe: the
         * host inflates and measures the row itself, so the Douyin LayoutParams crash cannot occur.
         */
        const val MENU_PANEL_LIST = "aaj.d"

        /** The option object the panel binds; we create ONE instance and remember it by identity. */
        const val MENU_ITEM = "r9j.f"

        /**
         * The option event whose constructor RECEIVES the assembled `List<r9j.k>`.
         *
         * `r9j.n$d.<init>(KwaiMsg,List,boolean,Rect,Point,r9j/m)` stores the list by reference
         * (`iput-object v3, r9j.n$d->c:Ljava/util/List;`), so this is the point where the host's own
         * option list is reachable and appendable. `b.Z0` builds it as a local, so it is NOT reachable
         * there; see [installMenuHook] for the full reasoning.
         */
        const val MENU_EVENT_CLASS = "r9j.n\$d"

        /** The click callback interface passed to [MENU_EVENT_CLASS] (`s9j.a.c(r9j.k)`). */
        const val MENU_CALLBACK_CLASS = "r9j.m"

        /**
         * Concrete implementations of [MENU_CALLBACK_CLASS], verified from the dex.
         *
         * `...children.b$c` is the option panel's own callback (its `c(r9j.k)` is what the row click
         * reaches). Kept as a list so a second implementation can be added without restructuring, and
         * each entry is re-verified against the interface at install.
         */
        val MENU_CALLBACK_IMPLS = listOf(
            "com.yxcorp.gifshow.message.next.chat.children.message_list.children.message_item." +
                "children.b\$c",
        )

        /**
         * The presenter that renders ONE option row.
         *
         * `aaj.g.onBind()` sets the row text from `r9j.k.d()` (a resource id), and `doBindView` binds the
         * TextView to field `g` via id `0x7f093263`. Our label override hooks `onBind` here.
         */
        const val MENU_ROW_PRESENTER = "aaj.g"

        /**
         * The host's long-press config holder on the message item component.
         *
         * `aaj`-side disassembly: `b->G:Lpqj/d`, and `pqj.d.a()` is `iget-boolean pqj.d->h:Z`. `b.Z0`
         * tests it IMMEDIATELY after its null checks and returns without building any option list when it
         * is false, so this single boolean decides whether the menu exists at all on a given device.
         */
        const val LONG_PRESS_CONFIG_FIELD = "G"

        /** Our menu row's text. The requirement is the SAME label the other hosts use. */
        const val MENU_LABEL = "\u5206\u6790"

        /**
         * The SECOND injected row, the floating-balloon switch -- the same pair every other host shows.
         *
         * `Constants.BALLON_ITEM_TITLE` (「浮窗」) is deliberately a DIFFERENT string from [MENU_LABEL]:
         * two rows sharing one label would be indistinguishable to the user (and to any label-based
         * routing), which is exactly the bug Constants.kt documents for v3.1.0.
         */
        const val MENU_LABEL_BALLOON = com.luoliaoo.wechat.core.Constants.BALLON_ITEM_TITLE

        /**
         * `r9j.f`'s field `d` default: `0x7f117648`, which resolves to `string` whose value is '装扮'.
         *
         * Recorded as EVIDENCE only -- field `d` is a real string-resource id that renders our row's
         * label, so it is never written (we overwrite the TextView instead). It is kept because it names
         * the host option our row used to be indistinguishable from.
         */
        const val DECORATION_LABEL_ID = 0x7f117648

        /**
         * A value that is not a valid resource id. NEVER WRITTEN ANYWHERE -- documentation only.
         *
         * Fields `b`/`c`/`d` ARE resource ids (drawables and a string) that the host resolves, so
         * injecting this into them could throw during bind. Field `a` is not patched either: the device
         * proved there is no neutral value to give it (see [reportInheritedCodes]).
         */
        const val NO_ICON_ID = -1

        /**
         * OUR two menu rows, in the order they appear: 分析 then 浮窗, matching the other three hosts.
         *
         * `actionCode` values are private to this module; they are only ever compared against
         * [oursActions], never handed to the host.
         */
        val OWN_MENU_ROWS: List<OwnMenuRow> = listOf(
            OwnMenuRow(MENU_LABEL, com.luoliaoo.wechat.core.Constants.OwnMenuAction.ANALYSE, 9001),
            OwnMenuRow(
                MENU_LABEL_BALLOON,
                com.luoliaoo.wechat.core.Constants.OwnMenuAction.BALLOON_SWITCH,
                9002,
            ),
        )

        /**
         * The icon size, in dp, taken FROM the shared icon rather than restated.
         *
         * Assigning `AnalysisIcon.SIZE_DP` instead of writing `20` again is deliberate: a second copy of
         * the number is free to drift, and a drift would ship a Kuaishou icon at a different physical
         * size from WeChat's and Soul's while every log line still said "attached the ORIGINAL icon".
         * With this alias there is exactly one number, so "same icon" is a fact of the code rather than
         * a convention someone has to remember.
         *
         * `SIZE_DP` is a `const val`, so this stays a compile-time constant.
         */
        const val ICON_SIZE_DP = com.luoliaoo.wechat.ui.AnalysisIcon.SIZE_DP

        /**
         * The row presenter's icon slot.
         *
         * `aaj.g.doBindView` binds field `f` to view id `0x7f095336`, the host's own `optionIconView`,
         * and `aaj.g.onBind` fills it from `r9j.k.c()` (the option's icon id). We overwrite that same
         * ImageView after the bind, so our icon lands in the host's real icon slot.
         */
        const val MENU_ICON_FIELD = "f"

        /**
         * The row presenter's CLICKABLE View.
         *
         * `aaj.g.e:Landroid/view/View;`, and `aaj.g.onBind` wraps the WHOLE row in a click listener there:
         *     [047b] iget-object v1, v0, Laaj/g;.e:Landroid/view/View;
         *     [0485] new-instance v3, Laaj/g$a;
         *     [0487] invoke-direct {v3, v0, v2}, Laaj/g$a;.<init>:(Laaj/g;Z)V
         *     [048a] invoke-virtual {v1, v3}, Landroid/view/View;.setOnClickListener:(...)V
         *
         * Every row of the panel binds its click here, which is why the tap on OUR row cannot be told
         * apart by the View: the distinguishing signal is the OPTION object handed to the callback, so the
         * fix keys off that identity in [installMenuClickHook] rather than off this View.
         *
         * Kept as a documented fact because it is the entry point of the whole tap chain, and because an
         * earlier attempt replaced this listener on our row -- rejected, since the panel's dismissal runs
         * in the CALLER (`aaj.b$c.c` [0021] `b->l()`), so suppressing the row's callback entirely would
         * have left the menu open after every tap.
         */
        const val MENU_ROW_VIEW_FIELD = "e"

        /**
         * Kuaishou's Robust hot-patch engine.
         *
         * Queried at install time (see [probePatchProxy]) because a registered redirect can make a
         * patched method body return early -- so a hook on it may appear dead for reasons that have
         * nothing to do with our hook.
         */
        const val PATCH_PROXY_CLASS = "com.kwai.robust.PatchProxy"

        /** Multi-select state: an INTERFACE (access=0x601), whose only implementation is [MULTI_IMPL]. */
        const val MULTI_STATE_IFACE = "esj.z"

        /** The ONLY implementation of [MULTI_STATE_IFACE]; verified by scanning every dex's interfaces. */
        const val MULTI_IMPL = "esj.l"

        /**
         * The `ObservableSet` field on [MULTI_IMPL], holding the selection's STRING KEYS.
         *
         * NOT a message set: `esj.l.g`/`esj.l.i` add and remove `o2k.b2.b(msg)` -- a String of the form
         * `sender + '_' + clientSeq` -- so its elements are keys, while the Map field's VALUES are the
         * messages. Named so the distinction is greppable rather than living only in a comment.
         */
        const val MULTI_SET_FIELD = "j"

        /**
         * The TOP multi-select bar: `sqj.c` (`ChatMultiChooseComponent`) -- 返回 / 完成.
         *
         * ⚠️ THIS IS NOT WHERE OUR BUTTON GOES. It is kept because the multi-select STATE is read
         * through it (`stateField=I`) and because naming it here prevents a future reader from
         * "fixing" the button back onto this bar.
         *
         * Device evidence for why: our first attempt hooked this component's `N1()` and the log said
         * "the container has 3 child(ren) but NO TextView to clone" -- three children with no TextView
         * is precisely this bar (a back view, a finish TextView, and the group container). The user
         * reported no 分析 button appeared, because we were decorating a bar they were not using.
         *
         * `extends csj.b`, a COMPONENT -- NOT a View, so never addView it.
         */
        const val MULTI_TOOLBAR = "sqj.c"

        /**
         * The BOTTOM multi-select action bar: `ukj.b` -- 逐条转发 / 合并转发 / 删除 / 举报.
         *
         * THIS is the bar the user operates, so THIS is where our 分析 button belongs.
         *
         * Found by working backwards from what the user SEES rather than from obfuscated class names:
         * the labels 逐条转发 / 合并转发 are string resources `0x7f115e62` / `0x7f115e64`; scanning the
         * APK's `r/**/*.xml` (AndResGuard-renamed layouts -- note layouts are NOT in `resources.arsc`)
         * for those two ids hits exactly one file, `r/as/eak.xml`, whose tree is:
         *
         *     ConstraintLayout  id/forward_msg_bottom_part (0x7f095fa5)   <-- the container
         *       View            id/forward_bottom_divider_line
         *       LinearLayout    id/forward_one_by_one (0x7f095fac)  [icon + TextView]
         *       LinearLayout    id/forward_together   (0x7f095fb0)  [icon + TextView]
         *       LinearLayout    id/delete_msg         (0x7f095f8e)  [icon + TextView]
         *       LinearLayout    id/report             (0x7f095fde)  [icon + TextView]
         *
         * and the ONLY class referencing those four button ids is `ukj.b` (classes16.dex). It shares
         * `csj.b` as its superclass and has its own `N1()` view-wiring method, like the top bar.
         */
        const val MULTI_BOTTOM_BAR = "ukj.b"

        /**
         * `ukj.b`'s field holding the bottom bar's container, assigned in its `N1()`.
         *
         * Disassembly of `ukj.b.N1()`:
         *     [000e] Component->C1()  [0015] #0x7f095fa6 (forward_msg_bottom_part_view_stub)
         *     [001c] check-cast ViewStub   [0020] iput->ukj.b.w   [0024] ViewStubHook.inflate
         *     [002a] iput->ukj.b.v        <-- the INFLATED bar itself, which is what we decorate
         *
         * So `v` is the container and `w` is the stub that produces it. Reading `w` would give a
         * ViewStub (not a ViewGroup), and reading `C1()` would give the whole component root rather
         * than this bar -- both were tried and are wrong.
         */
        const val BOTTOM_BAR_FIELD = "v"

        /**
         * How many times the bottom-bar attempt is retried when the failure is TIMING.
         *
         * Four attempts at 60 ms apart covers the stub inflate plus a layout pass, while staying short
         * enough not to be felt. The retry stops the moment the bar is found, so the normal path does
         * no waiting at all.
         */
        const val BOTTOM_BAR_RETRIES = 4

        /** Delay between bottom-bar retries, in milliseconds. */
        const val BOTTOM_BAR_RETRY_MS = 60L

        // NOTE: there deliberately are no `CONSTRAINT_FIELDS` / `CONSTRAINT_UNSET` / `CONSTRAINT_PARENT`
        // constants here. A previous revision reflected on constraint field NAMES such as "startToEnd"
        // to reposition the button; those names DO NOT EXIST in this APK, because
        // `ConstraintLayout$LayoutParams` is obfuscated to single-letter fields. Every reflective call
        // threw and was swallowed by a local catch, so the code was inert while looking correct.
        // Do not reintroduce name-based constraint reflection for this library.

        /**
         * The id given to our injected button.
         *
         * Needed because `ConstraintLayout` constrains one view TO ANOTHER BY ID, so the chain handover
         * (`chainAfter`) requires the clone to have one. The value is a fixed constant rather than a
         * resource id, so it can never collide with the host's own id space -- the module must not depend
         * on host resources for its own identity.
         */
        const val OUR_BUTTON_ID = 0x0F0F0001

        /**
         * Tag marking OUR toolbar button, so re-wiring the toolbar cannot stack duplicates.
         *
         * A tag on the View rather than a boolean flag: the CONTAINER can be a different instance after
         * a rebuild while a flag would still read "already added", silently leaving the new toolbar
         * without a button.
         */
        const val TOOLBAR_BUTTON_TAG = "luoliaoo.analysis.kuaishou.toolbar"

        /** The finish-button click handler; its `b` field IS the toolbar whose `I` is the state. */
        const val MULTI_CLICK_IMPL = "sqj.b"

        /** Cap on distinct HOLDER classes named in the diagnostic -- by TYPE, not by event. */
        const val HOLDER_TYPE_LIMIT = 10

        /** Cap on distinct ADAPTER classes named in the diagnostic -- by TYPE, not by event. */
        const val ADAPTER_TYPE_LIMIT = 10
    }

    @Volatile
    private var installed = false

    /** How many times the final funnel fired, so "the hook never ran" is distinguishable. */
    @Volatile
    private var funnelFires = 0

    /** How many messages were pushed into the live feed. */
    @Volatile
    private var pushed = 0

    /** How many binds produced no message at all (filtered, not an error). */
    @Volatile
    private var noMessage = 0

    /** Set once the bind funnel is hooked, so the final report can state it as a fact. */
    @Volatile
    private var bindHooked = false

    /**
     * Distinct HOLDER class names seen, capped by TYPE.
     *
     * The `Set` is the fix for 必看.md's 「诊断限流要按类名去重（Set<String>）而非计数」: a plain
     * counter was consumed by the conversation list's rows, so the chat page's holders -- the only
     * ones that matter -- were never printed.
     */
    private val seenHolderTypes = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    /** Distinct ADAPTER class names whose per-index accessor was resolved, or found missing. */
    private val seenAdapterTypes = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Resolved "message at index N" per adapter class name.
     *
     * Cached because this runs on every bind and a chat scrolls at tens of binds per second.
     *
     * 必看.md's 「第一次绑定静默丢消息」 bug lived exactly here: a shape match returned a `Method`
     * on the cache-miss path WITHOUT invoking it, so every adapter's FIRST bind stored nothing and
     * later binds worked -- "history works sometimes", the hardest possible symptom to diagnose.
     * The rule that prevents it: [messageAt] caches only the Method, and the INVOKE happens
     * unconditionally on the single path below, never on a branch that a first call can skip.
     */
    private val atCache = ConcurrentHashMap<String, Method>()

    /** Adapter classes already known to have NO per-index accessor, so the miss is logged once. */
    private val atMissing = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    // ------------------------------------------------------------------ install

    /**
     * The only public entry point. Shape matches [DouyinHooks.install] and [SoulHooks.install].
     *
     * Each step is wrapped so one failure cannot stop the others: a Kuaishou internal rename must
     * degrade ONE feature, never take the balloon or the history feed down with it.
     */
    fun install() {
        if (installed) {
            Log.i(TAG, "KUAISHOU: install() called twice; the second call is a no-op")
            return
        }
        installed = true

        Log.i(
            TAG,
            ">> kuaishou hooks: installing (host=" + HostApp.current.name +
                " versionName=" + BuildInfo.VERSION_NAME + ")",
        )

        // One-shot class dump FIRST, so the first device run confirms the real classes instead of
        // assuming them.
        step("message model") {
            access.probeClasses()
            logMessageModel()
        }

        // The hot-patch state is read BEFORE any hook is installed, because it determines how a later
        // "hook never fired" must be interpreted: a registered redirect can make a patched body return
        // early, which looks identical to a hook that was never installed. Reported, never worked around.
        step("hot-patch state") {
            probePatchProxy()
        }

        step("input box") {
            logInputBox()
        }

        // The history feed goes BEFORE the menu on purpose: the balloon's per-conversation filtering
        // reads `AnalysisController.detectCurrentTalker()`, which is fed ONLY by `onLiveMessage`. With
        // no feed the talker is always "", so the balloon shows every conversation at once -- and the
        // menu's 分析 item would work while the history stayed empty, which reads as two unrelated
        // bugs instead of one missing hook.
        step("message bind (live history)") {
            bindHooked = installMessageBindHook()
        }

        step("long-press menu") {
            installMenuHook()
        }

        step("multi-select") {
            installMultiSelectHook()
        }

        // ============================== WHY THIS IS ITS OWN STEP ====================================
        // The button lives on the toolbar, but it must NOT depend on the selection-READ path: that path
        // bails out early (with a `return`) when the state interface, the impl, `m()` or its return type
        // cannot be verified, and putting the button inside it would silently drop requirement 5's
        // toolbar entry for a reason that has nothing to do with the button.
        //
        // The two features are genuinely separable: the button needs only the toolbar class and its root
        // getter, while the read needs `esj.z`/`esj.l`. Wiring them as separate steps means each can
        // succeed alone and the final report names which one failed.
        // ==================== THE TOP BAR GETS NO BUTTON (device-proven) ====================
        //
        // `sqj.c` is the TOP bar (返回/完成), and `sqj.c.C1()` does NOT return that bar -- it returns
        // the CHAT PAGE ROOT. The device log proved it:
        //
        //     added our '分析' button to container
        //       com.yxcorp.gifshow.message.next.chat.utils.widget.NextTouchNotifyRelativeLayout
        //     (children now 4, clone of android.widget.FrameLayout)
        //
        // `NextTouchNotifyRelativeLayout` is the always-present chat root, so the button showed up
        // PERMANENTLY -- visible with no long-press and no multi-select at all. The user reported exactly
        // that ("没有长按也没有多选最上面却有一个分析的按钮").
        //
        // This used to be masked: the OLD rule required a direct TextView sibling to clone, and the root
        // has none, so it refused by accident. Widening the rule made the top-bar injection start
        // working -- against a target that was never a toolbar. So the fix is not a narrower rule, it is
        // NOT INJECTING HERE AT ALL: the bar the user operates is `ukj.b` (bottom), wired below.
        //
        // Kept as a commented-out record rather than deleted code, because the next editor will otherwise
        // re-add it: decorating "the multi-select component's root" looks obviously right and is wrong.

        // ==================== THE BOTTOM ACTION BAR IS WHERE THE BUTTON GOES ====================
        // `sqj.c` is the TOP bar (返回/完成). The user's 4 buttons (逐条转发/合并转发/删除/举报)
        // live on `ukj.b`, so THAT is the bar our 分析 entry must join. Separate step, so the final
        // report can name which of the two bars was wired.
        step("multi-select bottom bar button") {
            installBottomBarAnalysisButton()
        }

        Log.i(
            TAG,
            ">> kuaishou hooks: done (bindHooked=" + bindHooked +
                " menuHooked=" + menuHooked +
                " multiSelectHooked=" + multiSelectHooked +
                " bottomBarButtonHooked=" + bottomBarHooked +
                " installedSteps=" + stepsOk + "/" + stepsRun + ")",
        )
        startReport()
    }

    /** Per-step outcome, surfaced in the final line so a silent partial install is impossible. */
    @Volatile
    private var stepsRun = 0

    @Volatile
    private var stepsOk = 0

    @Volatile
    private var menuHooked = false

    @Volatile
    private var multiSelectHooked = false

    /**
     * True once the BOTTOM action bar's view-wiring method is hooked (the bar the user operates).
     *
     * This is the ONLY surface our button is injected into. The top bar used to have its own flag and
     * hook, removed after the device proved `sqj.c.C1()` returns the chat page ROOT: the button appeared
     * permanently, and the separate flag let the report read "installed" while the visible result was
     * wrong. One surface, one flag.
     */
    @Volatile
    private var bottomBarHooked = false

    /** How many times our toolbar button was actually added (a rebuild adds it again after re-wiring). */
    @Volatile
    private var buttonAdds = 0

    /** How many times the idempotence guard found our button already present and skipped. */
    @Volatile
    private var buttonAddsSkipped = 0

    /**
     * Toolbar containers that could not take a button, deduped by CONTAINER CLASS NAME (必看.md R9:
     * never a counter -- a counter is consumed by earlier runs and then hides the interesting case).
     */
    private val toolbarMissing: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap(),
    )

    /** How many times our menu item was tapped and the shared analysis path was entered. */
    @Volatile
    private var analysisStarts = 0

    /**
     * Runs one installation step, catching everything.
     *
     * The whole point is that a Kuaishou internal rename degrades ONE feature. Without this, an
     * exception in the menu step would abort `install()` before the history feed was wired, and the
     * symptom would be "history broken" -- pointing at entirely the wrong code.
     */
    private inline fun step(name: String, block: () -> Unit) {
        stepsRun++
        val t0 = System.currentTimeMillis()
        try {
            block()
            stepsOk++
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU: the '" + name + "' step threw " + t.javaClass.simpleName + ": " +
                    t.message + " -- the remaining steps continue, so this degrades ONE feature " +
                    "rather than the whole install",
            )
        }
        Log.i(
            TAG,
            "KUAISHOU: step '" + name + "' took " + (System.currentTimeMillis() - t0) + "ms",
        )
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Reports the message model the reader depends on.
     *
     * Prints PRESENT/MISSING by name, because "the class is not loadable" and "the class loaded but
     * an accessor is gone" are different failures with different fixes, and 必看.md's rule is that the
     * first device run must be sufficient to tell them apart.
     */
    private fun logMessageModel() {
        val cls = loadClass(KuaishouEnvelope.MSG_CLASS)
        if (cls == null) {
            Log.w(
                TAG,
                "KUAISHOU message model: " + KuaishouEnvelope.MSG_CLASS + " is NOT loadable via the " +
                    "host loader -- the history feed cannot work and every message will be rejected. " +
                    "If the class name is right, this is the ClassLoader trap from 必看.md; if the APK " +
                    "renamed it, the reader needs the new name.",
            )
            return
        }
        val wanted = listOf(
            "getText", "getSummary", "getSender", "getRealFrom", "getTarget", "getTargetType",
            "getMsgType", "getSentTime", "getCreateTime", "getId", "getSeq", "getUnknownTips",
        )
        val present = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (n in wanted) {
            if (hasNoArg(cls, n)) present += n else missing += n
        }
        Log.i(
            TAG,
            "KUAISHOU message model: " + cls.name + " loadable=true present=" + present +
                " missing=" + missing,
        )
        if (missing.isNotEmpty()) {
            Log.w(
                TAG,
                "KUAISHOU: the getters " + missing + " are absent on " + cls.name +
                    " -- the reader degrades those reads to neutral values and says so at the call " +
                    "site; a missing getText/getSummary means NO message can be stored",
            )
        }
        // Name the chat adapter and row model the bind hook will look for, so the first log states
        // what we expect next to what the device actually hands us.
        Log.i(
            TAG,
            "KUAISHOU: expecting chat adapter=" + CHAT_ADAPTER +
                " (accessor o2(int) -> KwaiMsg) rowModel=" + ROW_MODEL + " (accessor h() -> KwaiMsg)",
        )
    }

    /**
     * Reports the chat input box.
     *
     * This is a DIAGNOSTIC ONLY: `InputInjector` locates the field from the live view tree, so this
     * class must not implement a second lookup (a second source of truth is how two code paths start
     * disagreeing). What it does is name the expected class once, so a later "no chat input box"
     * failure can be checked against a logged expectation rather than re-derived from the APK.
     */
    private fun logInputBox() {
        val n = "com.yxcorp.gifshow.message.widget.ImeOptionsEmojiEditTextView"
        Log.i(
            TAG,
            "KUAISHOU chat input box: " + n + " (loadable=" + (loadClass(n) != null) +
                "); InputInjector locates the WIDGET from the live view tree by id 0x7f090f08 " +
                "plus an instanceof check on this class -- see R20 for why the id alone is not " +
                "enough (3 different classes reuse that id in 88 layouts, including the search box)",
        )
    }

    // ------------------------------------------------------------------ the history feed

    /**
     * Hooks `RecyclerView.Adapter.bindViewHolder(ViewHolder, int)` -- the ONLY funnel every adapter
     * passes through -- and feeds every bind into the existing live-history pipeline.
     *
     * Host classes are resolved through `resolver.classLoader()` (the HOST's loader), never through
     * the constructor-supplied loader. 必看.md records this as a hard rule, because a class loaded
     * from the wrong loader produces a "not found" that reads like a missing feature.
     *
     * @return true when the hook was attached.
     */
    private fun installMessageBindHook(): Boolean {
        val hostLoader = resolver.classLoader()
        try {
            Class.forName(RV_CLASS, false, hostLoader) // resolve, so a missing androidx fails HERE
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU: " + RV_CLASS + " is not loadable via the host loader (" + t.message +
                    "); live history cannot be fed, so records will only appear after an analysis",
            )
            return false
        }

        val adapterCls = try {
            Class.forName(RV_ADAPTER, false, hostLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: " + RV_ADAPTER + " is not loadable: " + t.message)
            return false
        }
        val holderCls = try {
            Class.forName(RV_HOLDER, false, hostLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: " + RV_HOLDER + " is not loadable: " + t.message)
            return false
        }

        val funnel = try {
            adapterCls.getDeclaredMethod("bindViewHolder", holderCls, Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: bindViewHolder(ViewHolder,int) not found: " + t.message)
            return false
        }

        // PROOF, in the log, that this is the un-overridable funnel and not the ordinary method that
        // every adapter overrides. If 'final' is ever absent, the hook below would silently see
        // nothing -- exactly the Douyin failure -- so it is checked rather than assumed.
        val isFinal = java.lang.reflect.Modifier.isFinal(funnel.modifiers)
        Log.i(
            TAG,
            "KUAISHOU: bindViewHolder modifiers=[" +
                java.lang.reflect.Modifier.toString(funnel.modifiers) + "] final=" + isFinal +
                " declaring=" + funnel.declaringClass.name,
        )
        if (!isFinal) {
            Log.w(
                TAG,
                "KUAISHOU: bindViewHolder is NOT final in this androidx build; this hook may never " +
                    "fire. Continuing anyway, but expect no history.",
            )
        }

        return try {
            /* ★ v9.1.1 修复（快手评论区点图片闪退）：intercept -> interceptAfter。
             *
             * ─────────────────────────────────────────────────────────────
             * 【崩溃现场（真机 2026-09-26 15:02:28，完整堆栈）】
             *
             *     java.lang.IllegalArgumentException: 未提供数据：zbg.a_f
             *       at PresenterV2.inject
             *       at xbg.g_f.doInject
             *       at PresenterV2.bind -> h8l.f.p
             *       at h8l.g.a2
             *       at h8l.g.onBindViewHolder
             *       at RecyclerView$Adapter.bindViewHolder      ← 我们挂的就是这一个
             *
             * 堆栈里没有我们的帧，所以它不是「我们抛的异常」，而是**我们让宿主进入了错误状态**。
             *
             * ─────────────────────────────────────────────────────────────
             * 【原因：我们在宿主绑完这一行之前，就反射进去读它的数据】
             *
             * `intercept` 走的是 `beforeHookedMethod`，即在**宿主自己的 onBindViewHolder 之前**执行。
             * 而 `onBind(...)` 内部会通过反射调用适配器的 `o2(int)` / `getItem(int)` 去取这一行的消息
             * （见 [messageFromAdapter]）。快手的 `h8l.f.p` -> `PresenterV2.bind` 是一条**状态机**：
             * Presenter 在 `moveToState` 的过程中要求「数据已就绪」，而我们的反射调用恰恰在
             * 宿主把数据准备到位**之前**就伸手去拿，于是 `inject` 判定「未提供数据」并抛异常。
             *
             * 真机日志里能清楚看到这个先后顺序（崩溃前 8 毫秒）：
             *
             *     15:02:28.135  KUAISHOU: could not determine a conversation for zbg.a_f
             *     15:02:28.141  bind produced no message (adapter=ubg.g0_f position=0)
             *     15:02:28.143  FATAL: 未提供数据：zbg.a_f
             *
             * ─────────────────────────────────────────────────────────────
             * 【为什么改成 interceptAfter 就对了】
             *
             * `interceptAfter` 走 `afterHookedMethod`，即**宿主把这一行完整绑完之后**我们才读。
             * 此时 Presenter 的状态机已经走完、数据已就绪，我们只是**旁观**，不再participate
             * 宿主的绑定流程 —— 这正是本模块对「视图填充类钩子」的既有规则：
             * 见 core/LegacyHook.kt 第 81 行「THIS IS REQUIRED FOR VIEW-POPULATING HOOKS」。
             *
             * 【为什么之前没暴露】
             * 聊天页的 `onBind` 读不到消息就会安静返回（`noMessage++`），链路上的干扰恰好没致命；
             * 而图片详情页的 Presenter 更严格，同样一次干扰就直接抛异常。
             * 也就是说这个钩子**一直在冒险**，只是图片页把它变成了必现的崩溃。
             *
             * 注：`interceptAfter` 里 `chain.result` 可用（本次不需要），
             * `setExceptionMode` 是 before/after 通用的，保留不变。 */
            LegacyHook.hook(funnel)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        onBind(chain.thisObject, chain.getArg(0), chain.getArg(1))
                    } catch (t: Throwable) {
                        // A throw here would run inside Kuaishou's own bind call, so it must never escape.
                        Log.w(TAG, "KUAISHOU: the bind feed failed: " + t.message)
                    }
                }
            Log.i(
                TAG,
                "KUAISHOU: hooked RecyclerView.Adapter.bindViewHolder (final=" + isFinal + ") -- " +
                    "live history now records without an analysis",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: hooking bindViewHolder failed: " + t.message)
            false
        }
    }

    /**
     * One bind: resolve the message, then push it into the EXISTING live feed.
     *
     * Every early exit states its reason (必看.md 「任何提前 return 都要打日志说明原因」), and the
     * class-level diagnostics are deduplicated by name so a busy list cannot bury the chat page's
     * types.
     */
    private fun onBind(adapter: Any?, holder: Any?, positionArg: Any?) {
        funnelFires++
        val position = (positionArg as? Int) ?: -1
        if (adapter == null) {
            Log.w(TAG, "KUAISHOU: a bind arrived with a null adapter; nothing to read a message from")
            return
        }
        if (position < 0) {
            Log.w(TAG, "KUAISHOU: a bind arrived with position=" + position + "; cannot index the adapter")
            return
        }

        noteAdapterType(adapter)

        val msg = messageFromAdapter(adapter, position, holder)
        if (msg == null) {
            // NOT an error: most RecyclerViews in Kuaishou are not the chat list. The holder type is
            // named so the log proves which one the chat page actually uses.
            noMessage++
            if (noMessage <= 3 || noMessage % 200 == 0) {
                Log.i(
                    TAG,
                    "KUAISHOU: bind produced no message (count=" + noMessage +
                        " adapter=" + adapter.javaClass.name +
                        " holder=" + (holder?.javaClass?.name ?: "<null>") +
                        " position=" + position +
                        ") -- expected while browsing lists that are not the chat",
                )
            }
            return
        }

        // The existing, host-agnostic pipeline: staging map + the 2s flush timer -> HistoryStore.
        // Nothing else is done here on purpose; this class does not touch the store directly.
        controller.onLiveMessage(msg)
        pushed++
        if (pushed <= 3 || pushed % 100 == 0) {
            val text = access.content(msg)
            Log.i(
                TAG,
                "KUAISHOU: pushed #" + pushed + " to the live feed (class=" +
                    KuaishouEnvelope.simpleName(msg.javaClass.name) +
                    " talker=" + access.talker(msg) +
                    " reason=" + access.lastReason +
                    " textLen=" + text.length + ")",
            )
        }
    }

    /**
     * Resolves the message object for one bound row.
     *
     * ORDER, and why:
     *   1. a direct `o2(int)` on the adapter, used with the POSITION AS GIVEN. `NextMessageAdapter`
     *      inherits `p8l.a` and indexes its own list, so a real chat adapter is self-consistent.
     *      A null return is normal (that position is not a message row).
     *   2. the wrapper route, because R19 establishes that the chat page's RV holds the
     *      header/footer wrapper `Lp8l/f;` -- NOT `NextMessageAdapter`. Only `c2()` (the MAIN adapter)
     *      is accepted; `g2()`/`e2()` are the header/footer adapters and trying them as fallbacks
     *      would silently read indices against the wrong list -- see `unwrapAdapter`. When we unwrap,
     *      the position is REWRITTEN with the header count subtracted, which is why the row offset is
     *      computed here rather than reused blindly.
     *   3. `p8l.a.getItem(int)` on whatever adapter we hold, then unwrap a `bean.b` row via `h()`.
     *   4. the holder, LAST and only as a shape-based fallback, named in the log when it runs.
     */
    private fun messageFromAdapter(adapter: Any, position: Int, holder: Any?): Any? {
        // 1. Direct o2(int).
        val direct = messageAt(adapter, position)
        if (direct != null) return direct

        // 2. Wrapper unwrap. R19: this is the CHAT PAGE's real shape, so this branch is expected to
        //    be the one that runs there.
        val inner = unwrapAdapter(adapter)
        if (inner != null) {
            val offset = headerCount(adapter)
            val innerPos = position - offset
            if (innerPos < 0) {
                // A header row: not a message, and computing a negative index would read the WRONG
                // row on some adapters, so it is refused explicitly.
                return null
            }
            val viaInner = messageAt(inner, innerPos)
            if (viaInner != null) {
                noteAdapterType(inner)
                return viaInner
            }
            // Fall through to the generic list read against the unwrapped adapter.
            return unwrapRow(getItemAt(inner, innerPos))
        }

        // 3. Generic list read against the adapter we were given.
        return unwrapRow(getItemAt(adapter, position))
    }

    /**
     * The "message at index" call, tried by SHAPE and then by two VERIFIED names.
     *
     * `o2(int)` is verified on `NextMessageAdapter`; `getItem(int)` is verified on its ancestor
     * `p8l.a`. Shape (one int parameter, returning something) is tried first so a renamed accessor in
     * a future build still works -- but note that the SHAPE match alone is not trusted to mean
     * "message", which is why [unwrapRow] and [isMessageLike] gate the result.
     *
     * CACHING RULE (必看.md's first-bind bug): the cache stores the Method only; `invoke` happens on
     * every path below, including the very first call, so a cache miss can never skip the read.
     */
    private fun messageAt(adapter: Any, position: Int): Any? {
        val cls = adapter.javaClass
        val cached = atCache[cls.name]
        if (cached != null) {
            val v = invokeInt(cached, adapter, position)
            if (v != null) return v as? Any?
        }

        val m = findIntAccessor(cls, "o2") ?: findIntAccessor(cls, "getItem")
        if (m == null) {
            if (atMissing.add(cls.name)) {
                Log.i(
                    TAG,
                    "KUAISHOU: adapter " + cls.name + " has neither o2(int) nor getItem(int); its " +
                        "rows cannot be read by index. If this is the CHAT adapter, the accessor was " +
                        "renamed by a Kuaishou update and this is the line that says so.",
                )
            }
            return null
        }
        atCache[cls.name] = m
        val v = invokeInt(m, adapter, position)
        return v
    }

    /** The generic `getItem(int)` from `p8l.a`, used when no verified accessor matched. */
    private fun getItemAt(adapter: Any, position: Int): Any? {
        val m = findIntAccessor(adapter.javaClass, "getItem") ?: return null
        return invokeInt(m, adapter, position)
    }

    /**
     * If [row] is a row MODEL rather than a message, read the message out of it.
     *
     * VERIFIED: `...model.bean.b.h()` is `iget-object f; return-object`, and the constructor's Kotlin
     * non-null assertion names that field `originMsg`. So `h()` is the row's message accessor.
     * A `KwaiMsg` passed in directly is returned unchanged, which is what makes this safe to call on
     * both shapes.
     */
    private fun unwrapRow(row: Any?): Any? {
        if (row == null) return null
        if (access.isMessage(row)) return row
        val h = findNoArg(row.javaClass, "h")
        if (h != null) {
            val v = try {
                h.invoke(row)
            } catch (_: Throwable) {
                null
            }
            if (v != null && access.isMessage(v)) return v
        }
        // Some builds expose it under a descriptive name; only accepted if it really IS a message.
        val alt = findNoArg(row.javaClass, "getOriginMsg")
        if (alt != null) {
            val v = try {
                alt.invoke(row)
            } catch (_: Throwable) {
                null
            }
            if (v != null && access.isMessage(v)) return v
        }
        if (!atMissing.add("row:" + row.javaClass.name)) return null
        Log.i(
            TAG,
            "KUAISHOU: a row of class " + row.javaClass.name + " is neither a KwaiMsg nor exposes a " +
                "message accessor (tried h() and getOriginMsg()); nothing was stored for it. If this " +
                "is the chat row model, " + ROW_MODEL + " changed shape and this is the line that says so.",
        )
        return null
    }

    /**
     * Walks a header/footer wrapper down to the adapter that actually holds the message rows.
     *
     * ============================== THE THREE GETTERS ARE NOT INTERCHANGEABLE =====================
     * This is a corrected version of a tempting mistake. `Lp8l/f;` exposes THREE no-arg adapter getters
     * and the obvious implementation -- "try each until one returns non-null" -- is WRONG, because they
     * return three DIFFERENT fields. Each was disassembled to its single field read:
     *
     *     c2()  ->  iget-object p8l.f->c:RecyclerView$Adapter;    <-- the MAIN (message) adapter
     *     g2()  ->  iget-object p8l.f->g:RecyclerView$Adapter;    <-- the HEADER adapter
     *     e2()  ->  iget-object p8l.f->h:RecyclerView$Adapter;    <-- the FOOTER adapter
     *
     * That assignment is confirmed independently by `getItemCount()`, which is exactly
     * `h2() + f2() + c.getItemCount()`, and by `h2()`/`f2()`, which are exactly
     * `g.getItemCount()/ h.getItemCount()` respectively (plus a `p8l.f$c` count).
     *
     * So the failure mode of a first-non-null loop is: when `c2()` is null but `g2()` is not, the loop
     * silently returns the HEADER adapter, and every index is then read against the wrong list. That
     * yields a message that belongs to a different row WITHOUT throwing -- the silent-wrong-message class
     * this whole step exists to prevent. Therefore only `c2` is accepted as the message adapter; the
     * header/footer getters are never used as a substitute.
     */
    private fun unwrapAdapter(adapter: Any): Any? {
        val m = findNoArg(adapter.javaClass, "c2") ?: return null
        val v = try {
            m.invoke(adapter)
        } catch (_: Throwable) {
            null
        }
        if (v != null && v !== adapter && isAdapter(v)) return v
        return null
    }

    /**
     * The wrapper's header-row count, or 0 when it exposes none.
     *
     * `h2()` is verified to be the HEADER count (`p8l.f->g`), NOT the footer's -- the two are separate
     * methods (`h2`/`f2`) and swapping them would shift every index by the wrong amount in exactly the
     * case where headers exist, producing correct-looking but wrong messages.
     */
    private fun headerCount(adapter: Any): Int = try {
        val m = findNoArg(adapter.javaClass, "h2")
        if (m == null) {
            0
        } else {
            (m.invoke(adapter) as? Int) ?: 0
        }
    } catch (_: Throwable) {
        0
    }

    /** True when the object is a `RecyclerView.Adapter`. */
    private fun isAdapter(v: Any): Boolean = try {
        Class.forName(RV_ADAPTER, false, resolver.classLoader()).isAssignableFrom(v.javaClass)
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------ menu / multi-select

    /**
     * Adds the 分析 item to Kuaishou's long-press message option panel.
     *
     * ============================== THE INJECTION POINT ==========================================
     * Verified chain (`_tools/ks/KS_MENU_RECON.md` sections 1.2-1.5, which I re-checked against the
     * APK myself):
     *
     *   b$f.accept(Object)      touch funnel: FlowExtKt.a(adapter, id 0x7f0952b1, mqj.j)
     *     -> new b$h            the gen.q implementation
     *     -> b$h.invoke(View,Rect,Point)   SOLE caller of:
     *     -> b.Z0(View,Rect,Point)V        SOLE implementation of tmj.h.Z0
     *          -> b.q2() -> jw8.a.j1() -> check-cast model.bean.b -> b.h() -> KwaiMsg
     *          -> new r9j.f + r9j.f.m(String) + List.add   <-- builds `List<r9j.k>` LOCALLY
     *          -> new r9j.n$d(KwaiMsg, List, Z, Rect, Point, r9j.m)
     *     -> skj.a.a(r9j/n)  ->  jkj.a$b.accept  ->  aaj.b.C(PanelType.OPTION_PANEL, Z)
     *     -> aaj.b.a()  ->  WindowManager.addView   [FLOAT_PANEL]
     *     -> aaj.d (RecyclerView + GridLayoutManager) binds the list
     *     -> aaj.g.onBind() renders each row: r9j.k->d()I -> getString -> TextView.setText
     *     -> aaj.g$a.onClick -> s9j.a.c(r9j.k)   <-- the row click callback
     *
     * ============================== WHY `r9j.n$d.<init>` AND NOT `b.Z0` ========================
     * This is the one place where the option LIST is reachable, and it is reachable CLEANLY:
     *
     *     r9j.n$d.<init>(KwaiMsg, List, Z, Rect, Point, r9j/m)
     *     [0034] iput-object v3, r9j.n$d->c:Ljava/util/List;   <-- only STORES the reference
     *
     * Because the constructor merely stores the reference, the List handed in IS the list the host is
     * about to bind. An `interceptAfter` hook on this constructor therefore receives the message
     * (`c` = arg 0) AND the live option list (`c2()`/field `c` = arg 1), and can append our own option
     * to it before the panel ever reads it.
     *
     * Hooking `b.Z0` instead would NOT work for adding an item: the list it builds is a LOCAL that is
     * never exposed, and `Z0` returns void, so a before-hook has no handle on it.
     *
     * ============================== WHY THE MENU USES NO `addView` ===============================
     * (Scope note: this rule is about the LONG-PRESS MENU. The multi-select TOOLBAR button added in t17
     * does add a View, because there the host's own container is a plain ViewGroup with host-built
     * children; see `addAnalysisButtonToToolbar` for why that case is different and safe.)
     *
     * The option area is a `RecyclerView + GridLayoutManager` (`aaj.d.g` / `aaj.d.h`), and the panel is
     * a `WindowManager` float whose root View is a plain `android.view.View`. Appending a View would both
     * fight the RecyclerView's LayoutParams validation -- the Douyin host-FATAL crash recorded in
     * 必看.md -- and be wiped on the next rebind, because the host re-binds the area from its own
     * `List<r9j.k>`. So this step APPENDS AN OBJECT TO THAT LIST and lets the host inflate, measure and
     * bind the row itself. That structurally eliminates the crash class rather than working around it.
     *
     * ============================== THE LABEL PROBLEM, AND HOW IT IS SOLVED =====================
     * A row's text is NOT taken from any settable string. Verified by disassembly of `aaj.g.onBind`:
     *
     *     r9j.k->d()I  ->  eql.r1->q(I)Ljava/lang/String;  ->  TextView->setText(CharSequence)
     *
     * and `eql.r1.q(I)` is `Resources->getString(I)`. `r9j.f.d()` merely returns the CONSTANT
     * `0x7f117648` set in its constructor, so a new `r9j.f` CANNOT carry our text through the normal
     * path: whatever we put in the option object, this route renders the host's own string id.
     *
     * ⚠️ CORRECTION (my own earlier claim here was WRONG and is worth recording). This comment used to
     * say that `r9j.f.m(String)` writes field `j` and that "a cross reference on `r9j.f.g()` finds ZERO
     * call sites -- so that field is never displayed". `ks_callsites.py` shows `r9j.f->g()` has FOUR
     * call sites (`aaj.b$c.c` twice, `aaj.b.C` twice). So `m(String)` is NOT dead code and `g()` IS
     * called -- `aaj.b.C` reads it as a non-empty-string test on the option.
     *
     * The conclusion happens to survive, for a DIFFERENT reason: `aaj.g.onBind` (the presenter that
     * renders the row) derives its text from `r9j.k->d()I` -> `Resources.getString`, never from `g()`.
     * So `m(String)` cannot set the VISIBLE label even though it is read elsewhere.
     *
     * This is exactly the "0 call sites is not the same as never called" trap: the search was hit by
     * polymorphic dispatch, where the call is recorded against the parent type. Treat any "xref = 0"
     * conclusion as unproven until the constructors and parent/interface methods are checked too.
     *
     * The solution is therefore to let the host build the row and then OVERWRITE what it rendered. The
     * row's TextView is `aaj.g.g` (verified: `bindWidget` on id `0x7f093263`), so an `interceptAfter` on
     * `aaj.g.onBind()` can set our label on the row whose option is ours. That keeps the whole panel
     * host-built (no addView, no crash class) while still showing the right text.
     */
    private fun installMenuHook() {
        val host = loadClass(MENU_HOST)
        if (host == null) {
            Log.w(
                TAG,
                "KUAISHOU: " + MENU_HOST + " is not loadable via the host loader, so the long-press " +
                    "menu cannot be hooked and the \u5206\u6790 item will not appear. History " +
                    "capture is unaffected.",
            )
            return
        }
        val hostLoader = resolver.classLoader()
        val z0 = try {
            host.getDeclaredMethod(
                "Z0",
                Class.forName("android.view.View", false, hostLoader),
                Class.forName("android.graphics.Rect", false, hostLoader),
                Class.forName("android.graphics.Point", false, hostLoader),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: Z0(View,Rect,Point) not found on " + host.name + ": " + t.message)
            return
        }

        // Resolve the option-list injection point. Everything the injection needs is verified here
        // BEFORE the step claims success, so a Kuaishou rename is reported now rather than discovered
        // later as a menu item that simply never appears.
        val optionCtor = resolveOptionCtor(hostLoader)
        if (optionCtor == null) {
            Log.w(
                TAG,
                "KUAISHOU: the option-list constructor " + MENU_EVENT_CLASS +
                    ".<init>(KwaiMsg,List,...) could not be resolved, so the \u5206\u6790 item CANNOT " +
                    "be added (the long-press anchor hook below is still installed). Menu injection " +
                    "requires it: it is the only point where the host's option list is reachable.",
            )
        } else {
            installOptionAppendHook(optionCtor)
        }

        // The label override, needed because a row's text comes from a resource id, not from a setter.
        installMenuLabelHook()

        // The tap routing, so the row we added actually does something. Installed even when the append
        // hook failed, so the log can distinguish "no row" from "row present but inert".
        installMenuClickHook()

        val ok = try {
            LegacyHook.hook(z0)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    try {
                        onLongPressMenu(chain.thisObject, chain.getArg(0))
                    } catch (t: Throwable) {
                        Log.w(TAG, "KUAISHOU: the long-press menu step failed: " + t.message)
                    }
                }
            Log.i(
                TAG,
                "KUAISHOU: hooked " + host.name + ".Z0(View,Rect,Point) -- the single funnel every " +
                    "message long-press passes through (fired counter is logged on the first 3 presses)",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: hooking Z0 failed: " + t.message)
            false
        }
        menuHooked = ok
    }

    /**
     * Resolves `r9j.n$d.<init>(KwaiMsg, List, Z, Rect, Point, r9j.m)`.
     *
     * Any of its parameter types may be unloadable, so each is resolved defensively and the MISSING one
     * is named -- a bare "constructor not found" would not say WHICH class renamed.
     */
    private fun resolveOptionCtor(hostLoader: ClassLoader): java.lang.reflect.Constructor<*>? {
        val event = loadClass(MENU_EVENT_CLASS) ?: run {
            Log.w(TAG, "KUAISHOU: " + MENU_EVENT_CLASS + " is not loadable")
            return null
        }
        val msgCls = loadClass(KuaishouEnvelope.MSG_CLASS)
        val listCls = java.util.List::class.java
        val zCls = java.lang.Boolean.TYPE
        val rectCls = try {
            Class.forName("android.graphics.Rect", false, hostLoader)
        } catch (_: Throwable) {
            null
        }
        val pointCls = try {
            Class.forName("android.graphics.Point", false, hostLoader)
        } catch (_: Throwable) {
            null
        }
        val cbCls = loadClass(MENU_CALLBACK_CLASS)
        val missing = listOfNotNull(
            if (msgCls == null) KuaishouEnvelope.MSG_CLASS else null,
            if (rectCls == null) "android.graphics.Rect" else null,
            if (pointCls == null) "android.graphics.Point" else null,
            if (cbCls == null) MENU_CALLBACK_CLASS else null,
        )
        if (missing.isNotEmpty()) {
            Log.w(TAG, "KUAISHOU: option ctor is unresolvable; these classes are missing: " + missing)
            return null
        }

        // ---- v7.0.0 DEVICE FIX: match by PARAMETER CLASS NAME, not by Class identity.
        //
        // ON-DEVICE FACT (PLK110, Kuaishou 14.8.30.50423): the exact-identity call below FAILED with
        // `NoSuchMethodException: r9j.n$d.<init> [class com.kwai.imsdk.msg.KwaiMsg, interface
        // java.util.List, boolean, class android.graphics.Rect, class android.graphics.Point,
        // interface r9j.m]` even though:
        //   * Google's `dexdump -d` lists that very ctor with that exact signature, and
        //   * the dex ON THE DEVICE is byte-identical to the local APK's
        //     (classes16.dex, 9,908,832 B, sha256 9e866299...), and
        //   * every parameter class loaded fine (no `is not loadable` line).
        // A signature that exists but is not found means the Class OBJECTS handed to
        // getDeclaredMethod are not the ones the ctor was defined with: Kuaishou is plugin-based and
        // ships its own `com.kuaishou.webkit.WebViewFactory$DexClassLoaderOptimize`, so the same name
        // can legitimately resolve through more than one loader. Reference equality is therefore the
        // wrong test; the NAME is the stable identity.
        //
        // So: scan the declared ctors and compare parameter type NAMES. This is strictly more
        // permissive than the old code (identical result whenever identity matching worked), and it
        // cannot pick the wrong ctor as long as the 6-marker shape is unique -- which we assert below.
        val want = listOf(
            KuaishouEnvelope.MSG_CLASS,   // com.kwai.imsdk.msg.KwaiMsg
            "java.util.List",
            "boolean",
            "android.graphics.Rect",
            "android.graphics.Point",
            MENU_CALLBACK_CLASS,          // r9j.m
        )
        val ctors = event.declaredConstructors
        // Every ctor we saw, with its param names -- printed once so a future Kuaishou change is
        // diagnosable from the log alone instead of requiring another dex session.
        for (c in ctors) {
            Log.i(
                TAG,
                "KUAISHOU: " + event.name + " ctor " + paramNamesOf(c) +
                    " loader=" + c.declaringClass.classLoader?.javaClass?.name,
            )
        }
        val byName = ctors.filter { paramNamesOf(it) == want }
        if (byName.size != 1) {
            Log.w(
                TAG,
                "KUAISHOU: expected exactly 1 ctor of " + event.name + " matching (KwaiMsg,List," +
                    "boolean,Rect,Point," + MENU_CALLBACK_CLASS + ") but found " + byName.size +
                    " -- a Kuaishou update changed the option event's shape",
            )
            return null
        }
        val picked = byName[0]
        // If the name-based match succeeded where identity failed, say so loudly: that is the proof
        // of the dual-loader theory and it means the OLD code was wrong, not Kuaishou.
        val identityWorked = runCatching {
            event.getDeclaredMethod("<init>", msgCls, listCls, zCls, rectCls, pointCls, cbCls)
        }.isSuccess
        if (!identityWorked) {
            Log.w(
                TAG,
                "KUAISHOU: ctor matched BY NAME but NOT by Class identity. The parameter Class objects " +
                    "we resolved are not the ones this ctor declares, i.e. Kuaishou loaded the same " +
                    "class name through a different loader. Using the ctor: " + paramNamesOf(picked) +
                    " | our msgCls loader=" + msgCls?.classLoader?.javaClass?.name +
                    " event loader=" + event.classLoader?.javaClass?.name +
                    " ctor param[0] loader=" + picked.parameterTypes[0].classLoader?.javaClass?.name,
            )
        }
        return picked
    }

    /** Parameter type NAMES of a ctor, for name-based matching (obfuscation-stable, loader-agnostic). */
    private fun paramNamesOf(c: java.lang.reflect.Constructor<*>): List<String> =
        c.parameterTypes.map { p ->
            when {
                p.isPrimitive -> p.name
                p.isArray -> p.name            // e.g. "[B", "[Ljava.lang.String;"
                else -> p.name
            }
        }

    /**
     * Appends our option to the host's list, after the host has finished building it.
     *
     * `interceptAfter` (not `intercept`): the constructor merely stores the reference, so a before-hook
     * would run while the list is still being filled by the caller -- appending then risks the host
     * overwriting or the row being placed before the real ones. After the constructor returns, the list
     * is complete and owned by the event object.
     */
    private fun installOptionAppendHook(ctor: java.lang.reflect.Constructor<*>) {
        try {
            LegacyHook.hook(ctor)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        appendAnalysisOption(chain.thisObject, chain.args)
                    } catch (t: Throwable) {
                        // Must never escape into Kuaishou's own construction.
                        Log.w(TAG, "KUAISHOU: appending the option failed: " + t.message)
                    }
                }
            Log.i(
                TAG,
                "KUAISHOU: hooked " + ctor.declaringClass.name + ".<init>(KwaiMsg,List,...) -- the " +
                    "option list is now appended to; the host builds and binds the row itself",
            )
            menuInjectHook = true
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: hooking the option ctor failed: " + t.message)
        }
    }

    /**
     * The append itself: build one `r9j.f`, remember it, add it to the host's list.
     *
     * The created instance is REMEMBERED BY IDENTITY. The row renderer (`aaj.g.onBind`) only knows the
     * `r9j.k` it was handed, and its text comes from a resource id we cannot change, so the label hook
     * needs to recognise OUR instance. An identity check is exact; matching on a field value would be a
     * guess, and R16 warns that reusing a host action code can trigger a real host feature.
     */
    private fun appendAnalysisOption(event: Any?, args: Array<Any?>) {
        if (event == null) return
        val options = args.getOrNull(1) as? MutableList<Any?> ?: run {
            Log.w(
                TAG,
                "KUAISHOU: the option event arrived without a mutable List (arg1=" +
                    (args.getOrNull(1)?.javaClass?.name ?: "<null>") +
                    "); the \u5206\u6790 item was not added. Kuaishou may have changed the field type.",
            )
            return
        }
        val itemCls = loadClass(MENU_ITEM)
        if (itemCls == null) {
            Log.w(TAG, "KUAISHOU: " + MENU_ITEM + " is not loadable; cannot build the option")
            return
        }
        // (the idempotence guard lives INSIDE the loop below, per row-role: a single early-return here
        // would wrongly block the second row whenever only one of the two had been added.)
        val msg = args.getOrNull(0)

        // ================================ TWO ROWS, LIKE EVERY OTHER HOST ================================
        // WeChat / Douyin / Soul each inject BOTH 「分析」 and 「浮窗」. The first Kuaishou version injected
        // only 分析, which is why the balloon switch was missing on this host alone (device report #3).
        //
        // Each row needs its OWN instance and its OWN identity->action entry, because at tap time the
        // ONLY thing identifying a row is its object identity (see installMenuClickHook).
        //
        // ======================= IDEMPOTENCE MUST BE CHECKED PER ROW-ROLE =======================
        // A PREVIOUS VERSION OF THIS LOOP WAS WRONG and is worth spelling out, because the mistake is
        // invisible in review and destructive on the device. It guarded with
        //     if (!oursIdentities.add(key)) continue
        // which tests the identity of the instance we JUST created -- always brand new, so `add` always
        // succeeds and the guard NEVER trips. On every panel rebuild (each long-press, each config change)
        // the loop appended TWO MORE rows, so the list grew without bound for the lifetime of the process.
        // The panel got progressively heavier with duplicate rows, which is exactly the kind of growth that
        // makes the whole menu fail to appear.
        //
        // The correct test is per ROLE, not per instance: "is a row with this action already in the
        // list?". That is what the original single-row code did (it scanned `options` for a known
        // identity), and it is what we do again below -- by scanning the list for a row whose identity we
        // already own AND whose recorded action matches.
        var added = 0
        for (spec in OWN_MENU_ROWS) {
            if (options.any { it != null && oursActions[System.identityHashCode(it)] == spec.action }) {
                menuSkips++
                continue
            }
            val inst = buildOwnMenuItem(itemCls, spec) ?: continue
            val key = System.identityHashCode(inst)
            // Record BEFORE adding, so a concurrent bind that sees the row already knows what it is.
            rememberOurs(key, inst, spec.action, msg)
            try {
                options.add(inst)
            } catch (t: Throwable) {
                oursInstances.remove(key)
                oursIdentities.remove(key)
                oursActions.remove(key)
                oursMessages.remove(key)
                Log.w(
                    TAG,
                    "KUAISHOU: adding the '" + spec.label + "' option to the host list failed (" +
                        t.message + "); this row will not appear for this press",
                )
                continue
            }
            added++
            menuAdds++
            if (menuAdds <= 6) {
                Log.i(
                    TAG,
                    "KUAISHOU MENU: appended the '" + spec.label + "' option #" + menuAdds +
                        " (listSize=" + options.size +
                        " msg=" + (msg?.javaClass?.name ?: "<null>") +
                        " msgId=" + (msg?.let { access.msgId(it) } ?: 0L) + ")",
                )
            }
        }
        if (added == 0 && menuSkips == 0) {
            Log.w(TAG, "KUAISHOU: neither of our menu rows could be added for this press")
        }
    }

    /**
     * Builds ONE of our menu rows.
     *
     * ================== THE INHERITED ACTION CODES, AND WHY NOTHING PATCHES THEM ==================
     * ON-DEVICE BUG (PLK110, 14.8.30.50423): tapping our 「分析」 row ALSO opened Kuaishou's
     * 「个性化装扮」 screen. What the disassembly plus the resource table PROVE:
     *
     *     `r9j.f.<init>()` hardcodes all four of its int fields, i.e. a brand-new row does not start
     *     empty -- it starts AS a copy of a real host option:
     *         a = 22          (an int code)
     *         b = 0x7f0734e2  (drawable izj.webp)
     *         c = 0x7f073af8  (drawable jox.webp)
     *         d = 0x7f117648  (string  '装扮')      <-- value resolved from the resource table below
     *
     *     `0x7f117648` resolves to `string`, value '装扮'. So our injected row, which reused the class
     *     verbatim, carried the identity of the host's OWN 装扮 option.
     *
     *     HONEST SCOPE: the exact line that turns the tap into the 装扮 navigation was NEVER pinned down.
     *     All we have is (a) those default codes, which resolve to a real host option, and (b) the
     *     observed navigation. That is a strong correlation, not a proof of the mechanism.
     *
     * **THIS BUG IS NOT FIXED.** Two attempts were made and BOTH were withdrawn -- writing sentinels into
     * the codes (risks throwing inside the host's resource lookups during bind) and skipping the host's
     * callback for our row (on the device it made Kuaishou's ENTIRE panel disappear). See
     * [reportInheritedCodes] for the full post-mortem. What remains is that our row still carries those
     * codes, so the navigation may still occur; the log says so plainly rather than implying a fix.
     *
     * The 装扮 link was resolved against the SECOND resource table (`assets/kwai_res/core_res`, see
     * 必看.md). The main `resources.arsc` cannot answer it at all -- its key pool has only 691 entries.
     */
    private fun buildOwnMenuItem(itemCls: Class<*>, spec: OwnMenuRow): Any? {
        val inst = try {
            val ctors = itemCls.declaredConstructors.filter { it.parameterTypes.isEmpty() }
            if (ctors.isEmpty()) {
                Log.w(
                    TAG,
                    "KUAISHOU: " + MENU_ITEM + " has no no-arg constructor (all " +
                        itemCls.declaredConstructors.size + " take arguments); cannot build the option",
                )
                return null
            }
            val ctor = ctors[0]
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: could not instantiate " + MENU_ITEM + ": " + t.message)
            return null
        }
        reportInheritedCodes(inst, spec)
        return inst
    }

    /**
     * Logs the identity code our row inherited. READ-ONLY -- it writes nothing.
     *
     * ================== THE 装扮 / 多选 BUG, AND WHY NOTHING IS PATCHED HERE ==================
     * Two device-verified attempts to fix this by editing the code both failed, and each failure taught
     * the same lesson: **the codes ARE the host's option table, so there is no neutral value.**
     *
     *   v1  field a = 22 (the host's own default)  ->  tapping our row opened 「个性化装扮」
     *   v2  field a = 19 (we believed "no action") ->  tapping our row started 「多选」
     *
     * v2 was MY error and it is worth recording precisely how it was made: I read `aaj.g$a.onClick`
     * through a keyword grep, which SILENTLY DROPPED the `move-result` instructions. Those are exactly the
     * instructions that carry a value into the following comparison, so the listing I reasoned about had a
     * hole in the register flow, and "19 means skip" was a conclusion about a truncated method. Printing
     * the method COMPLETE (`_tools/ks/ks_method.py`) shows the branch at [004d] is not a skip-the-feature
     * gate at all.
     *
     * Chain, now read end to end:
     *     aaj.g.onBind   [0485] new Laaj/g$a; [048a] View.setOnClickListener(aaj.g$a)
     *     aaj.g$a.onClick[0026] s9j/a->c(r9j.k)
     *     aaj.b$c.c      [001c] r9j.m->c(r9j.k)          <-- the ONLY invoke site in all 31 dex files
     *     children.b$c.c [0012] children.b->x2(r9j.k)    <-- the real action dispatcher
     *
     * The actual fix is in [installMenuClickHook]: for OUR row -- and ONLY ours, by object identity --
     * the host's callback body is skipped, so the host never reaches `children.b.x2`. Field `a` is left
     * exactly as the host built it, as are the resource-id fields b/c/d.
     */
    private fun reportInheritedCodes(inst: Any, spec: OwnMenuRow) {
        val code = KuaishouMenuIdentity.readActionCode(inst)
        Log.i(
            TAG,
            "KUAISHOU MENU: our '" + spec.label + "' row carries field " +
                KuaishouMenuIdentity.ACTION_CODE_FIELD + "=" + code + " (r9j.f's default; " +
                KuaishouMenuIdentity.CODE_OPENING_DECORATION + " = \u88c5\u626e, " +
                KuaishouMenuIdentity.CODE_MULTI_SELECT + " = \u591a\u9009). NOT patched on purpose: " +
                "there is no neutral value, because the host dispatches over its own option table. The " +
                "host's dispatch is skipped for this row in installMenuClickHook instead.",
        )
    }

    /** One of OUR rows: what the user reads, and what a tap does. */
    internal class OwnMenuRow(
        val label: String,
        val action: com.luoliaoo.wechat.core.Constants.OwnMenuAction,
        val actionCode: Int,
    )

    /**
     * Overrides the rendered label of OUR row.
     *
     * WHY THIS IS NECESSARY (see the long comment on [installMenuHook]): a row's text comes from
     * `r9j.k.d()` -> `Resources.getString(id)`, and `r9j.f`'s constructor hardcodes that id. Setting the
     * option's own `j` field via `r9j.f.m(String)` therefore cannot change the VISIBLE text -- not
     * because that field is unread (it IS read: 4 call sites of `r9j.f.g()`), but because the PRESENTER
     * that renders the row derives its string from `r9j.k.d()` and never consults `g()`. Overwriting the
     * TextView AFTER the host binds it is the only route that works without changing how the panel is
     * built.
     *
     * ⚠️ An earlier version of this comment justified the same conclusion with "`r9j.f.g()` has 0 call
     * sites", which is FALSE (verified with `ks_callsites.py`: 4 call sites). The conclusion survived
     * the correction; the reasoning did not, and the false version is exactly the "xref = 0 means never
     * called" trap -- polymorphic calls are recorded against the parent type, so a zero result must be
     * re-checked against constructors and parent/interface methods before it is believed.
     *
     * It touches ONLY the row whose option is our instance, so no host row is ever relabelled.
     */
    private fun installMenuLabelHook() {
        val rowCls = loadClass(MENU_ROW_PRESENTER) ?: run {
            Log.w(
                TAG,
                "KUAISHOU: " + MENU_ROW_PRESENTER + " is not loadable, so our row would render the " +
                    "host's own label for that option id instead of \u5206\u6790. The item still works; " +
                    "only its text is wrong.",
            )
            return
        }
        val onBind = findNoArg(rowCls, "onBind") ?: run {
            Log.w(TAG, "KUAISHOU: " + MENU_ROW_PRESENTER + ".onBind() not found; label not overridable")
            return
        }
        try {
            LegacyHook.hook(onBind)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        relabelOursIfAny(chain.thisObject)
                    } catch (_: Throwable) {
                        // never escape
                    }
                }
            menuLabelHooked = true
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: hooking the row onBind failed: " + t.message)
        }
    }

    /**
     * Rewrites the row's label AND attaches the ORIGINAL analysis icon, when (and only when) the row
     * shows OUR option instance.
     *
     * ============================== THE ICON IS THE HOST'S OWN SLOT ===============================
     * Requirement 3 asks for the menu entry to reuse the module's EXISTING 分析 icon, not a new glyph.
     * `aaj.g` (the row presenter) already owns a slot for one, and it is populated by the host itself:
     *
     *     doBindView:  0x7f095336  ->  aaj.g->f:Landroid/widget/ImageView;   (the "optionIconView")
     *     onBind:      ... invoke-virtual r9j.k->c()I            <-- the icon id
     *                  if-eq that, #17, +002a                    <-- 17 = "no icon" sentinel
     *                  ... ImageView->setImageDrawable(Drawable)
     *                  else: ImageView->setImageResource(I)
     *
     * So the slot exists, the host fills it from `r9j.k.c()` (= `r9j.f` field `a`, whose default is
     * `0x16` = 22, set in `<init>`), and `17` is the "draw nothing" case. A self-constructed `r9j.f`
     * carries the default icon id -- which is a REAL Kuaishou icon belonging to some other option, not
     * ours. Overwriting the ImageView after the host binds is therefore the correct move: same slot, same
     * layout, and the module's own drawable goes where a glyph belongs.
     *
     * ============================== WHY setImageDrawable, NOT setImageResource ===================
     * `AnalysisIcon.toDrawable` is the exact function WeChat's and Soul's menu paths already use, so the
     * pixels are identical across all hosts by construction rather than by redrawing. `setImageResource`
     * could only take a Kuaishou resource id, and the module has none.
     *
     * A missing ImageView is REPORTED, never worked around: requirement 3 forbids inventing a substitute
     * glyph, so a null slot is a fact to log (the entry still works) rather than a reason to draw one.
     */
    private fun relabelOursIfAny(presenter: Any?) {
        if (presenter == null) return
        val option = readObjectField(presenter, "c") ?: return
        val key = System.identityHashCode(option)
        if (!oursIdentities.contains(key)) return

        // Which of our rows is this? Both get the ORIGINAL icon; only the label differs.
        val label = labelFor(actionOf(key))

        // ---- 1. the label (see installMenuHook for why the text cannot come from the option object)
        val tv = readObjectField(presenter, "g")
        if (tv is android.widget.TextView) {
            tv.text = label
            menuRelabels++
            if (menuRelabels <= 4) {
                Log.i(TAG, "KUAISHOU MENU: relabelled our row to '" + label + "'")
            }
        } else {
            Log.w(
                TAG,
                "KUAISHOU: our menu row's TextView (aaj.g.g) is " +
                    (tv?.javaClass?.name ?: "<null>") + "; cannot set the label. The item still works.",
            )
        }

        // ---- 2. the ORIGINAL analysis icon, into the host's own icon slot
        attachAnalysisIcon(presenter)
    }

    /**
     * The 浮窗 row: toggle the floating balloon, exactly as WeChat's MenuHook and Douyin do.
     *
     * Shares `ConfigRepo.toggleBalloonEnabled` + `FloatingPanel` with the other hosts rather than
     * re-implementing the switch, so "balloon on" means the same thing, is persisted in the same place,
     * and behaves identically on all four hosts. A second implementation here would be free to drift.
     *
     * The balloon needs NO message, so this path deliberately does not touch [oursMessages].
     */
    private fun onBalloonRowClicked() {
        val ctx = com.luoliaoo.wechat.core.ModuleBridge.appContext
        if (ctx == null) {
            Log.w(TAG, "KUAISHOU MENU: balloon switch has no Context; the choice cannot be saved")
            return
        }
        try {
            val on = com.luoliaoo.wechat.data.ConfigRepo.toggleBalloonEnabled(ctx)
            Log.i(TAG, "KUAISHOU MENU: balloon switch -> " + (if (on) "ON" else "OFF"))
            if (on) {
                com.luoliaoo.wechat.ui.FloatingPanel.showGlobalOn(ctx)
            } else {
                com.luoliaoo.wechat.ui.FloatingPanel.hideGlobal()
            }
            android.widget.Toast.makeText(
                ctx,
                if (on) {
                    HostApp.current.say("\u6d6e\u7a97\u5df2\u5f00\u542f")
                } else {
                    HostApp.current.say("\u6d6e\u7a97\u5df2\u5173\u95ed")
                },
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } catch (t: Throwable) {
            Log.e(TAG, "KUAISHOU MENU: balloon switch failed: " + t.message, t)
        }
    }

    /** The on-screen label for one of our actions. */
    private fun labelFor(action: com.luoliaoo.wechat.core.Constants.OwnMenuAction): String = when (action) {
            com.luoliaoo.wechat.core.Constants.OwnMenuAction.BALLOON_SWITCH -> MENU_LABEL_BALLOON
            // ANALYSE is the default so an unknown action still renders the primary requirement.
            com.luoliaoo.wechat.core.Constants.OwnMenuAction.ANALYSE,
            com.luoliaoo.wechat.core.Constants.OwnMenuAction.NONE,
            -> MENU_LABEL
        }

    /** The action a given OUR-row instance performs, defaulting to ANALYSE. */
    private fun actionOf(key: Int): com.luoliaoo.wechat.core.Constants.OwnMenuAction =
        oursActions[key] ?: com.luoliaoo.wechat.core.Constants.OwnMenuAction.ANALYSE

    /**
     * Puts `AnalysisIcon.toDrawable` into the row's `optionIconView`.
     *
     * ============================== WHY THIS RUNS ON EVERY BIND, NOT ONCE ========================
     * The tempting optimisation -- remember the row and attach only the first time -- is WRONG, and the
     * disassembly says why. `aaj.g.onBind` sets the icon in BOTH of its branches:
     *
     *     [008e] if-eq c(), #17, +002a        <-- 17 = "no icon" sentinel
     *     normal path: ... ImageView->setImageDrawable(...)   [00da]
     *     sentinel path: ... ImageView->setImageResource(...) [00f2]
     *
     * Neither branch leaves the ImageView untouched. So the host REWRITES the slot on every single bind,
     * which means a one-shot attach would be silently undone the next time that row binds -- leaving the
     * host's own icon on OUR row, with no error and no log. Attaching on every bind is therefore the
     * correct behaviour, and it is cheap: one small drawable per bind.
     *
     * The BUG that this replaces: an earlier version guarded with a one-shot set, which would have shown
     * the wrong icon after any rebind.
     *
     * A missing ImageView is REPORTED, never worked around: requirement 3 forbids inventing a substitute
     * glyph, so a null slot is a fact to log (the entry still works) rather than a reason to draw one.
     */
    private fun attachAnalysisIcon(presenter: Any) {
        val iv = readObjectField(presenter, MENU_ICON_FIELD)
        if (iv !is android.widget.ImageView) {
            // Deduped by class NAME (必看.md R9: never by a counter -- a counter gets consumed by the
            // conversation list before the chat page ever binds).
            if (iconSlotMissing.add(iv?.javaClass?.name ?: "<null>")) {
                Log.i(
                    TAG,
                    "KUAISHOU: our menu row's icon slot (aaj.g.f, id 0x7f095336) is " +
                        (iv?.javaClass?.name ?: "<null>") + ", not an ImageView, so the ORIGINAL " +
                        "\u5206\u6790 icon cannot be attached. The entry itself works. No substitute " +
                        "icon is drawn -- requirement 3 forbids inventing one.",
                )
            }
            return
        }
        val ctx = try {
            iv.context
        } catch (_: Throwable) {
            null
        }
        if (ctx == null) {
            Log.w(TAG, "KUAISHOU: our row's ImageView has no Context; the icon was not attached")
            return
        }
        val sizePx = (ICON_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
        val d = try {
            com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, sizePx)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: AnalysisIcon.toDrawable failed: " + t.message)
            return
        }
        try {
            iv.setImageDrawable(d)
            iconAttaches++
            if (iconAttaches <= 3) {
                // The visibility is printed because "attached a drawable" and "the user can SEE an icon"
                // are different claims, and only the device run can confirm the second one.
                Log.i(
                    TAG,
                    "KUAISHOU MENU: attached the ORIGINAL \u5206\u6790 icon (AnalysisIcon.toDrawable, " +
                        ICON_SIZE_DP + "dp=" + sizePx + "px) to the host's own optionIconView " +
                        "(id 0x7f095336) -- same drawable, same function and same source as WeChat and " +
                        "Soul, so the glyph is identical by construction rather than redrawn. " +
                        "device-check imageViewVisibility=" + iv.visibility +
                        " (0=VISIBLE) hasDrawable=" + (iv.drawable != null) +
                        " intrinsic=" + (iv.drawable?.intrinsicWidth ?: -1) + "x" +
                        (iv.drawable?.intrinsicHeight ?: -1) +
                        " bounds=" + iv.width + "x" + iv.height,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: setImageDrawable on our row failed: " + t.message)
        }
    }

    /**
     * Routes OUR row's click into the SHARED analysis path.
     *
     * ============================== WHY THE HOOK IS ON THE CALLBACK, NOT ON onClick ===============
     * The row's click listener (`aaj.g$a.onClick`) is a HOST class and its `onClick` also drives host
     * behaviour, so hooking it would put our code inside a host interaction. Instead the click reaches
     * `s9j.a.c(r9j.k)` -- a one-argument callback whose parameter identifies WHICH option was tapped.
     * Hooking that interface implementation gives the tapped option directly, and the identity check
     * makes any non-our option a no-op. That is strictly safer than intercepting user intent.
     *
     * ============================== THE `PromptBuilder` PROHIBITION =============================
     * The task requires ZERO `PromptBuilder` references in this class, so both entry points go through
     * `AnalysisController`. That is not merely a style rule: going through the controller is what makes
     * the "two filter tables" bug (R6) STRUCTURALLY IMPOSSIBLE, because the controller owns the single
     * `templateBlockWords()`/scope decision. A direct `PromptBuilder` call here would be a second such
     * table, and the two would diverge the first time a user edited their settings.
     */
    private fun installMenuClickHook() {
        val cbCls = loadClass(MENU_CALLBACK_CLASS) ?: run {
            Log.w(
                TAG,
                "KUAISHOU: " + MENU_CALLBACK_CLASS + " is not loadable, so our row's tap cannot be " +
                    "routed to the analysis path. The row would appear and do nothing -- which is why " +
                    "this is reported loudly rather than silently skipped.",
            )
            return
        }
        val impls = findImplementations(cbCls)
        if (impls.isEmpty()) {
            Log.w(
                TAG,
                "KUAISHOU: no implementation of " + MENU_CALLBACK_CLASS + " was found, so the menu " +
                    "tap cannot be routed. The \u5206\u6790 row will appear but do nothing.",
            )
            return
        }
        var hooked = 0
        for (impl in impls) {
            val m = impl.declaredMethods.firstOrNull { it.name == "c" && it.parameterCount == 1 }
                ?: continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        // ===================== SKIP THE HOST DISPATCH FOR **OUR** ROW ONLY =====================
                        // THIS IS THE FIX for "tapping 分析 opens 个性化装扮 / starts 多选".
                        //
                        // The chain, read end to end out of the device dex (ks_method.py / ks_ifaceimpls.py):
                        //
                        //   aaj.g$a.onClick  [0026] s9j/a->c(r9j.k)        <-- the row's click callback
                        //   aaj.b$c.c        [001c] r9j.m->c(r9j.k)        <-- WE HOOK HERE
                        //                    [0021] b->l()                 <-- dismisses the panel, AFTER us
                        //   children.b$c.c   [0012] children.b->x2(r9j.k)  <-- the real action dispatcher
                        //
                        // So `r9j.m.c` is the single point where the host turns "a row was tapped" into "run
                        // that row's feature", and the panel dismissal happens at [0021] in the CALLER --
                        // after this method returns. Skipping the body for our row therefore suppresses
                        // ONLY the feature dispatch: the menu still closes normally, and host rows are
                        // untouched because `oursIdentities` matches nothing else.
                        //
                        // WHY THIS IS SAFE, AND WHY AN EARLIER ATTRIBUTION WAS WRONG:
                        // A previous build included this skip AND an unrelated idempotence bug that appended
                        // two rows on every long-press, so the panel grew until the menu stopped appearing.
                        // That failure was blamed on skipOriginal and the skip was reverted. The blame was
                        // WRONG -- the growth bug was the cause, and it is fixed (the guard is now per
                        // row-role, not per freshly-built instance). Re-verified: `r9j.m.c` has exactly ONE
                        // invoke site in all 31 dex files, and it is this tap path -- NOT panel construction.
                        //
                        // WHY NOT PATCH THE IDENTITY CODE INSTEAD: tried, twice, both device-verified as
                        // failures. field a = 22 -> 装扮 ; field a = 19 -> 多选. The codes ARE the host's
                        // option table, so there is no neutral value. See reportInheritedCodes.
                        val tapped = chain.getArg(0)
                        val isOurs = KuaishouMenuRouting.isOurRow(tapped, oursIdentities)
                        if (isOurs) {
                            // Skip BEFORE handling, so our row's feature can never run even if our own
                            // handler throws.
                            chain.skipOriginal()
                        }
                        try {
                            onOptionClicked(tapped)
                        } catch (t: Throwable) {
                            Log.w(TAG, "KUAISHOU: option click routing failed: " + t.message)
                        }
                    }
                hooked++
            } catch (_: Throwable) {
                // try the next implementation
            }
        }
        menuClickHooked = hooked > 0
        Log.i(
            TAG,
            "KUAISHOU: hooked " + hooked + " implementation(s) of " + MENU_CALLBACK_CLASS +
                ".c(r9j.k) -- our row's tap now enters the shared analysis path (" +
                impls.size + " candidate class(es) found)",
        )
    }

    /**
     * If the tapped option is OURS, start an analysis for the message the panel belongs to.
     *
     * Nothing happens for a host option: no analysis, no log spam, no host behaviour touched. The
     * message is the one captured when the option was appended, so the analysis matches the row the
     * user actually long-pressed.
     */
    private fun onOptionClicked(option: Any?) {
        if (option == null) return
        val key = System.identityHashCode(option)
        if (!oursIdentities.contains(key)) return
        val action = actionOf(key)
        val msg = oursMessages.remove(key)

        // The floating-balloon switch needs no message at all, so it is handled BEFORE the message
        // plumbing: requiring a message would make the balloon row silently inert whenever the panel was
        // rebuilt and the message lookup came up empty.
        if (action == com.luoliaoo.wechat.core.Constants.OwnMenuAction.BALLOON_SWITCH) {
            onBalloonRowClicked()
            return
        }

        if (msg == null) {
            Log.w(
                TAG,
                "KUAISHOU: our own option was tapped but its message is no longer known; nothing was " +
                    "analysed. This should not happen -- the message is recorded when the option is added",
            )
            return
        }
        analysisStarts++

        // MULTI-SELECT takes priority when the user has actually selected more than one message.
        //
        // Kuaishou routes its OWN 多选 item to `esj.z.g(msg)`, which adds the long-pressed message to
        // the selection, and our option rides the SAME list. So a tap can mean "analyse the selection"
        // or "analyse this one message", and the selection size is the only thing that distinguishes
        // them. Reading it here (rather than guessing) is what makes the two cases behave correctly.
        val selected = readSelectedMessages(currentMultiSelectState())
        if (KuaishouSelection.isBatch(selected)) {
            Log.i(
                TAG,
                "KUAISHOU MENU: our \u5206\u6790 option tapped with " + selected.size +
                    " message(s) selected [" + KuaishouSelection.describeForLog(selected) +
                    "] -- routing to the BATCH path",
            )
            startBatchAnalysis(selected)
            return
        }

        // Single-message path. The panel's own message is authoritative; `lastMenuMsg` is the fallback
        // for the (unexpected) case where the panel was built without our append hook recording it.
        val single = msg ?: lastMenuMsg
        if (single == null) {
            Log.w(
                TAG,
                "KUAISHOU MENU: our option was tapped but no message is known (neither the panel's " +
                    "message nor the last long-press); nothing was analysed",
            )
            return
        }

        Log.i(
            TAG,
            "KUAISHOU MENU: our \u5206\u6790 option tapped (start #" + analysisStarts +
                " selected=" + selected.size +
                " msg=" + single.javaClass.name +
                " textLen=" + access.content(single).length +
                " talker=" + access.talker(single) + ") -- entering the SHARED analysis path",
        )
        // The anchor is the long-press anchor when we have one (it is an attached View, which the
        // shared path needs to resolve an Activity), otherwise null and the controller reports why.
        try {
            controller.startAnalysisFor(single, lastMenuAnchor)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU: startAnalysisFor threw: " + t.message)
        }
    }

    /**
     * The live multi-select state object, or null when multi-select is not active.
     *
     * The toolbar (`sqj.c`) holds it in a field whose type is the `esj.z` interface, so the field is
     * located by TYPE rather than by the obfuscated letter (`I`) -- a Kuaishou update renames letters
     * far more often than it changes types. When there is no toolbar instance we have no state, and the
     * caller treats that as "single message".
     */
    private fun currentMultiSelectState(): Any? {
        val holder = multiSelectStateHolder?.get() ?: return null
        // The state field is located by TYPE (the `esj.z` interface) rather than by the obfuscated
        // letter `I`: a Kuaishou update renames single letters far more often than it changes types.
        val f = multiSelectStateField ?: return null
        return try {
            f.get(holder)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Runs the batch analysis for the selected messages.
     *
     * ============================== THE SHARED PATH, DELIBERATELY =================================
     * This mirrors `DouyinHooks.onMultiSelectAnalyseClicked` step for step: describe the messages with
     * the controller's own formatting, filter with `controller.templateBlockWords()` (NOT a hardcoded
     * list -- that is R6, the "two filter tables" bug), show the read-only scope dialog, then enter
     * `startAnalysisForSelection` with the SAME block words the filter used. Going through the
     * controller for both filtering and starting is what makes the two tables impossible to diverge.
     *
     * `PromptBuilder` is referenced only for the two pure predicates (`isSkipped`, `isBlockedBy`), which
     * is exactly how the Douyin path uses it -- the rule for THIS file is that no prompt is BUILT here.
     */
    private fun startBatchAnalysis(selected: List<Any>) {
        try {
            val anchor = lastMenuAnchor
            val activity = anchor?.let { findActivityOrNull(it) } ?: run {
                Log.w(
                    TAG,
                    "KUAISHOU multi-select: no Activity could be resolved from the long-press anchor; " +
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
                    "KUAISHOU multi-select: all " + described.size + " selected message(s) were " +
                        "non-text or blocked (words=" + words.joinToString("/") + "); nothing to analyse",
                )
                return
            }
            Log.i(
                TAG,
                "KUAISHOU multi-select: analysing " + lines.size + " of " + selected.size +
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
            Log.e(TAG, "KUAISHOU multi-select analysis failed: " + t.message, t)
        }
    }

    /** The Activity behind a view, or null. Mirrors the Douyin helper without duplicating its UI. */
    private fun findActivityOrNull(v: View): android.app.Activity? {
        var c: android.content.Context? = v.context
        var depth = 0
        while (c != null && depth < 10) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return try {
            val root = v.rootView
            var rc: android.content.Context? = root?.context
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

    /**
     * The concrete classes that implement [iface], resolved by KNOWN NAME and then VERIFIED.
     *
     * WHY NOT A SCAN: the APK has 343,739 classes, and enumerating them at install time would cost
     * seconds on the main thread for no benefit -- the implementation is already known and verified:
     *
     *     ...message_item.children.b$c  implements  r9j.m
     *       [0028] iget-object aaj.b$c->a:Laaj/b;
     *       [002c] invoke-virtual aaj.b->h()Lr9j/m;
     *       [0038] invoke-interface r9j.m->c(Lr9j/k;)V      <-- the row-click callback
     *
     * Each candidate is loaded and then CHECKED against the interface, so a renamed or removed class
     * degrades to "fewer candidates" with a log line -- it never becomes a wrong hook. That check is
     * what makes a by-name list safe to maintain.
     */
    private fun findImplementations(iface: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>(2)
        for (name in MENU_CALLBACK_IMPLS) {
            val cl = loadClass(name) ?: continue
            if (cl == iface || cl.isInterface) continue
            if (!iface.isAssignableFrom(cl)) {
                Log.w(
                    TAG,
                    "KUAISHOU: " + name + " no longer implements " + iface.name +
                        " (a Kuaishou update likely renamed the callback); this candidate is skipped",
                )
                continue
            }
            out.add(cl)
        }
        return out
    }

    /**
     * One long-press: record the anchor so the analysis path has a live View, then let the host
     * build its own panel.
     *
     * The message is NOT taken from here. `Z0` receives the anchor View, Rect and Point; the message
     * comes from the current row via `b.q2()` inside `Z0` itself. So this hook's job is to stash the
     * anchor -- the analysis path needs an ATTACHED View to resolve an Activity from -- and to log
     * that it fired, which is the evidence separating "hooked but inert" from "never hooked". That
     * distinction is what cost earlier rounds a full debug cycle, so it is stated explicitly rather
     * than left to be inferred from a missing menu item.
     *
     * The message is read through the SAME route `Z0` uses (`q2()` -> `j1()` -> row `h()`), so what is
     * logged here is the message the host itself is about to act on -- not a parallel guess.
     */
    private fun onLongPressMenu(thisObject: Any?, anchor: Any?) {
        menuFires++
        lastMenuAnchor = anchor as? View

        // ============================ THE EARLY-RETURN GATE, MEASURED =============================
        // `b.Z0` does NOT always build a menu. Its very first action after the null-checks is a CONFIG
        // test with an early return, which I read from the disassembly:
        //
        //     [0052] iget-object v1, b->G:Lpqj/d;
        //     [0056] invoke-virtual pqj.d->a()Z
        //     [005e] if-nez v1, +001d
        //     [0062] ... log " 触发了长按，但配置中不支持长按功能"
        //     [0096] return-void                                  <-- NO OPTION LIST IS EVER BUILT
        //
        // and `pqj.d.a()` is a single field read (`iget-boolean pqj.d->h:Z`), i.e. a boolean feature
        // flag. There is a SECOND gate later, before the list is built, whose failure logs
        // " 触发了长按但不满足展示条件, ".
        //
        // This is why the flag is worth reading here: t9 saw NO menu on a real device, and the
        // candidates were (a) the long-press never reaches `Z0`, (b) a config gate returns early, or
        // (c) the menu appears but the dump missed it. Without this line all three look identical from
        // the outside. With it, (b) is separated from (a) at the first press.
        //
        // READ-ONLY: the value is logged, never forced. Overriding a host feature flag to make a menu
        // appear is exactly the "distort the subject to pass the test" move that is forbidden, and it
        // would also risk popping the panel in a state Kuaishou does not expect.
        val configSupportsLongPress = readLongPressConfigFlag(thisObject)
        lastLongPressConfig = configSupportsLongPress
        if (configSupportsLongPress == false) {
            Log.w(
                TAG,
                "KUAISHOU LONG-PRESS: the funnel FIRED #" + menuFires + " but the host's own config " +
                    "gate says long-press is NOT supported (b->G:Lpqj/d, field h == false). `b.Z0` " +
                    "returns BEFORE building any option list, so no menu will appear and our " +
                    "\u5206\u6790 item cannot be reached on this device state. This is the host's " +
                    "decision and is NOT overridden -- reported only. The host itself logs " +
                    "'\u89e6\u53d1\u4e86\u957f\u6309\uff0c\u4f46\u914d\u7f6e\u4e2d\u4e0d\u652f\u6301" +
                    "\u957f\u6309\u529f\u80fd' for the same press.",
            )
        }
        val msg = messageFromLongPressHost(thisObject)
        if (menuFires <= 3) {
            Log.i(
                TAG,
                "KUAISHOU LONG-PRESS: menu hook FIRED #" + menuFires +
                    " thisObject=" + (thisObject?.javaClass?.name ?: "<null>") +
                    " anchor=" + (anchor?.javaClass?.name ?: "<null>") +
                    " msg=" + (msg?.javaClass?.name ?: "<unreadable>") +
                    " msgId=" + (msg?.let { access.msgId(it) } ?: 0L) +
                    " textLen=" + (msg?.let { access.content(it).length } ?: 0),
            )
        }
        lastMenuMsg = msg
    }

    /**
     * The message the long-press host is currently acting on.
     *
     * EXACTLY the route `Z0` uses, verified by disassembly:
     *   `b.q2()Lcom/kwai/component/social/component/adapter/a;`  (the adapter)
     *     -> `jw8.a.j1()Ljava/lang/Object;`                      (the current row)
     *     -> `check-cast model.bean.b` -> `b.h()` -> `KwaiMsg`
     *
     * Written reflectively rather than by cast because a cast would bind this file to the obfuscated
     * row-model class, and the whole point of resolving the chain by shape is that a Kuaishou update
     * degrades this one feature instead of breaking the build.
     */
    private fun messageFromLongPressHost(host: Any?): Any? {
        if (host == null) return null
        val q2 = findNoArg(host.javaClass, "q2") ?: return null
        val adapter = try {
            q2.invoke(host)
        } catch (_: Throwable) {
            null
        } ?: return null
        val j1 = findNoArg(adapter.javaClass, "j1") ?: return null
        val row = try {
            j1.invoke(adapter)
        } catch (_: Throwable) {
            null
        } ?: return null
        return unwrapRow(row)
    }

    @Volatile
    private var menuFires = 0

    /**
     * The host's long-press config flag as last read: true / false / null (unreadable).
     *
     * Three-valued deliberately -- see [readLongPressConfigFlag]. The report turns it into a verdict so
     * "our hook is broken" and "the host declines to show a menu" are never confused.
     */
    @Volatile
    private var lastLongPressConfig: Boolean? = null

    /** One-shot latch for the three-candidate selection probe, so it prints once per process. */
    private val selectionProbeDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once the option-list constructor is hooked (the actual injection point). */
    @Volatile
    private var menuInjectHook = false

    @Volatile
    private var menuLabelHooked = false

    /** True once the option-tap callback is hooked, so "row present but inert" is distinguishable. */
    @Volatile
    private var menuClickHooked = false

    /** How many options we have appended, and how many duplicate presses we declined. */
    @Volatile
    private var menuAdds = 0

    @Volatile
    private var menuSkips = 0

    @Volatile
    private var menuRelabels = 0

    /** How many times the ORIGINAL analysis icon was attached to our row. */
    @Volatile
    private var iconAttaches = 0

    /** Class names of presenters whose icon slot was not an ImageView (dedup by name, per R9). */
    private val iconSlotMissing: MutableSet<String> = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Identity hashes of the `r9j.f` instances WE created.
     *
     * Identity, not value: the row renderer receives a `r9j.k` whose text is a resource id we cannot
     * change, so the only exact way to know "this row is ours" is to recognise the very object we added.
     * A value match would risk relabelling a host row, and R16 warns that borrowing a host action code
     * can trigger a real host feature.
     *
     * `identityHashCode` is used rather than the object itself so the set holds ints and cannot retain
     * the option (and, through it, the message and its payload) beyond the panel's lifetime.
     */
    private val oursIdentities: MutableSet<Int> = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<Int, Boolean>(),
    )

    /**
     * Strong references to OUR option instances, keyed by identity hash.
     *
     * WITHOUT THIS, [oursIdentities] IS UNSOUND. `System.identityHashCode` is not unique for the lifetime
     * of a process: once an object is collected, the runtime may hand the same value to a NEW object. Our
     * identity set holds only the int, so nothing keeps our instance alive -- meaning a collected option
     * could leave a stale key behind, and a later Kuaishou row that happened to receive that recycled hash
     * would be MISTAKEN FOR OURS. The consequences are exactly the misrouting we are trying to eliminate:
     * tapping the host's own 「撤回」 or 「删除」 would be routed into our analysis path, and the host's
     * feature would not run.
     *
     * Holding the instance keeps the hash meaningful for as long as the key exists. The map is bounded
     * ([OURS_LIMIT]) and pruned oldest-first, so a long chat session cannot grow it without limit; the
     * bound is far larger than the handful of live panels that can exist at once, so pruning can only
     * discard identities whose panels are long gone.
     */
    private val oursInstances = ConcurrentHashMap<Int, Any>()

    /** Hard cap on remembered option instances; see [oursInstances] for why pruning is safe. */
    private val OURS_LIMIT = 256

    /** Remembers one of our option instances, keeping its identity hash valid and the map bounded. */
    private fun rememberOurs(key: Int, inst: Any, action: com.luoliaoo.wechat.core.Constants.OwnMenuAction, msg: Any?) {
        oursInstances[key] = inst
        oursIdentities.add(key)
        oursActions[key] = action
        oursMessages[key] = msg
        if (oursInstances.size <= OURS_LIMIT) return
        // Prune oldest-first. ConcurrentHashMap has no ordering, so we evict an arbitrary batch and drop
        // every per-key record together -- a half-removed key would be worse than a forgotten one.
        val stale = oursInstances.keys.take(oursInstances.size - OURS_LIMIT)
        for (k in stale) {
            oursInstances.remove(k)
            oursIdentities.remove(k)
            oursActions.remove(k)
            oursMessages.remove(k)
        }
    }

    /** Our option identity -> the message its panel belongs to, so the click can analyse the right one. */
    private val oursMessages = ConcurrentHashMap<Int, Any?>()

    /**
     * Which of OUR rows a given instance is (identity hash -> action).
     *
     * Kept SEPARATE from [oursMessages] because the two rows are distinct objects and a tap must resolve
     * to exactly one action. Storing the action here (rather than re-deriving it from the label) means the
     * routing never depends on UI text, which the host can re-render at any time.
     */
    private val oursActions = ConcurrentHashMap<Int, com.luoliaoo.wechat.core.Constants.OwnMenuAction>()

    @Volatile
    private var lastMenuAnchor: View? = null

    /**
     * The message of the most recent long-press.
     *
     * Kept as the LAST fallback for the option click: the authoritative message is the one captured when
     * OUR option was appended (that is per-panel), but if a panel were ever built without our append hook
     * running, this at least names the message the user just long-pressed rather than analysing nothing.
     */
    @Volatile
    private var lastMenuMsg: Any? = null

    /**
     * The live toolbar instance, so the multi-select state can be read on demand.
     *
     * Captured by hooking the toolbar's constructor rather than by walking the view tree, because
     * `sqj.c extends csj.b` is a COMPONENT and not a View -- there is nothing to find by traversing
     * views, and pretending otherwise is how a feature ends up permanently "not found". A weak
     * reference is used so the panel can be collected when Kuaishou closes it.
     */
    @Volatile
    private var multiSelectStateHolder: java.lang.ref.WeakReference<Any>? = null

    /** The toolbar's state field, resolved once by TYPE (`esj.z`) at install. */
    @Volatile
    private var multiSelectStateField: java.lang.reflect.Field? = null

    /**
     * Adds the batch-analysis entry to Kuaishou's multi-select toolbar.
     *
     * ============================== THE STATE MODEL (the source of truth) ========================
     * `esj.z` is an INTERFACE (`access=0x601`, all methods abstract) and `esj.l` is its ONLY
     * implementation -- verified by scanning every dex's interfaces table, not by guessing from a name.
     *
     * The selection lives in `esj.l.i:java.util.Map`, exposed by `m()`, and `esj.l.g(KwaiMsg)` shows
     * EXACTLY how it is populated:
     *
     *     [0020] iget-object v0, esj.l->i:Ljava/util/Map;
     *     [0024] invoke-static o2k.b2->b(KwaiMsg)Ljava/lang/String;
     *     [002c] invoke-interface java.util.Map->put(key, msg)
     *
     * TWO facts follow, and both matter:
     *   1. the VALUES are the `KwaiMsg` objects themselves -- not ids, not a lossy summary;
     *   2. the KEYS come from `o2k.b2.b(KwaiMsg)`, which is `getSender() + '_' + getClientSeq()`.
     *
     * ============================== WHY THIS DECISION WAS REVERSED FROM THE ORIGINAL PLAN ========
     * The blueprint originally said to key the selection by `KwaiMsg.getId()` and use `m()` as a
     * cross-check. Reading `esj.l.g` disproves that: the key is a COMPOSITE of sender and clientSeq, so
     * looking a message up by `getId()` would silently find NOTHING and the feature would report "no
     * messages selected" for a selection the user could see. So this reads the map's VALUES, which is
     * both simpler and correct, and it does not need to reproduce the key format at all.
     *
     * ============================== WHY NOT `sqj.c.J` / `frj.p` ==================================
     * `sqj.c.J:frj.i` -> `c():frj.p` -> `b():List` is a BARE List with no generic information, and the
     * message-side model `MsgMulltiChooseResponse$ChoosedMsg` carries only
     * `mFromUserId:String` + `mSeq:J`. A list of those cannot yield message objects, so that route
     * cannot feed `describeMessages`. The state route can, which is why it is used.
     */
    private fun installMultiSelectHook() {
        val state = loadClass(MULTI_STATE_IFACE)
        if (state == null) {
            Log.w(
                TAG,
                "KUAISHOU: the multi-select state interface " + MULTI_STATE_IFACE +
                    " is not loadable; batch analysis from multi-select is unavailable. Everything " +
                    "else is unaffected.",
            )
            return
        }
        val impl = loadClass(MULTI_IMPL)
        if (impl == null) {
            Log.w(TAG, "KUAISHOU: " + MULTI_IMPL + " is not loadable; multi-select unavailable")
            return
        }
        if (!state.isAssignableFrom(impl)) {
            Log.w(
                TAG,
                "KUAISHOU: " + MULTI_IMPL + " no longer implements " + MULTI_STATE_IFACE +
                    " (a Kuaishou update changed the hierarchy); multi-select unavailable rather " +
                    "than hooked against a guessed shape",
            )
            return
        }
        val sel = findNoArg(impl, "m")
        if (sel == null) {
            Log.w(
                TAG,
                "KUAISHOU: " + MULTI_IMPL + ".m() (the selected-message Map) is absent, so the " +
                    "selection cannot be read; multi-select unavailable. Expected m()Ljava/util/Map;",
            )
            return
        }
        if (sel.returnType.name != "java.util.Map") {
            Log.w(
                TAG,
                "KUAISHOU: " + MULTI_IMPL + ".m() returns " + sel.returnType.name +
                    ", not java.util.Map; the selection cannot be read the documented way. Multi-select " +
                    "is disabled rather than read through a guessed shape.",
            )
            return
        }

        // The toolbar entry: `sqj.c` (extends csj.b, a COMPONENT and NOT a View) holds the state in
        // field `I` and the finish button in `w`. The finish click is handled by `sqj.b.onClick`, which
        // is what sets a precise anchor for our entry and lets us know when the selection is "done".
        val toolbar = loadClass(MULTI_TOOLBAR)
        val finishBtn = toolbar?.let { findFieldOfType(it, "android.widget.TextView") }
        val clickImpl = loadClass(MULTI_CLICK_IMPL)
        val stateField = toolbar?.let { findFieldOfAssignableType(it, state) }

        Log.i(
            TAG,
            "KUAISHOU multi-select: state=" + impl.name + " m()=Map(verified)" +
                " add=" + findNoArg(impl, "g") + " refresh=" + findNoArg(impl, "r") +
                " exit=" + findNoArg(impl, "e") + " count=" + findNoArg(impl, "f") +
                " status=" + findNoArg(impl, "p") +
                " | toolbar=" + MULTI_TOOLBAR + " stateField=" + (stateField?.name ?: "<absent>") +
                " finishButton=" + (finishBtn?.name ?: "<absent>") +
                " clickImpl=" + MULTI_CLICK_IMPL + " loadable=" + (clickImpl != null),
        )

        // THE READING PATH IS VERIFIED — the selection Map yields real KwaiMsg objects (proved by the
        // disassembly of esj.l.g above), so this is the piece that makes batch analysis possible.
        multiSelectHooked = true

        // Capture the live toolbar so the selection can be read on demand. The state FIELD is resolved
        // now, by type, so a rename of the letter `I` cannot break the read later.
        multiSelectStateField = toolbar?.let { findFieldOfAssignableType(it, state) }
        if (toolbar != null) {
            val ctor = try {
                toolbar.getDeclaredConstructor()
            } catch (_: Throwable) {
                null
            }
            if (ctor != null) {
                try {
                    LegacyHook.hook(ctor)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .interceptAfter { chain ->
                            try {
                                multiSelectStateHolder =
                                    java.lang.ref.WeakReference(chain.thisObject)
                            } catch (_: Throwable) {
                                // never escape into the host's construction
                            }
                        }
                    Log.i(
                        TAG,
                        "KUAISHOU multi-select: hooked " + toolbar.name +
                            ".<init>() to track the live toolbar (state field=" +
                            (multiSelectStateField?.name ?: "<unresolved>") + ")",
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "KUAISHOU multi-select: could not hook the toolbar constructor (" + t.message +
                            "); the selection will read as empty. Reading the TOOLBAR's state field is " +
                            "only needed for the batch-detection path -- the selection itself is read " +
                            "through esj.z.m()",
                    )
                }
            }
        }

        // The tool menu ("更多") reuses the SAME option list, so multi-select's 分析 entry rides the
        // option-append hook installed by the menu step: `esj.z.g(msg)` puts the long-pressed message
        // into the selection, and pressing 分析 then analyses the whole selection. Logged explicitly so
        // the behaviour is not a hidden coupling.
        Log.i(
            TAG,
            "KUAISHOU multi-select: the 分析 entry uses the SAME option list as the long-press menu " +
                "(the host adds its own 多选 item as r9j.q and routes it to esj.z.g(msg)). When MORE " +
                "than one message is selected, the option click analyses the SELECTION through " +
                "startAnalysisForSelection; otherwise it analyses the single message. Selection is read " +
                "from " + MULTI_IMPL + ".m() (values are KwaiMsg objects, keyed by sender+'_'+clientSeq)",
        )

        // ================================ t17: THE TOOLBAR BUTTON ==================================
        // The requirement is a 分析 entry INSIDE multi-select. Relying on the long-press menu alone
        // does not satisfy it: once the user is in multi-select the toolbar is what is on screen, and
        // the long-press panel is a different surface. Our own button is installed by its OWN step in
        // `install()` -- see the note there for why it must not depend on this read path succeeding.
    }

    // ==================== THE TOP BAR INJECTION IS DELIBERATELY GONE ====================
    //
    // A `installMultiSelectToolbarButton()` used to live here, hooking `sqj.c.N1()` and adding our
    // 分析 button to `sqj.c.C1()`. The device proved that is the WRONG target:
    //
    //     added our '分析' button to container
    //       com.yxcorp.gifshow.message.next.chat.utils.widget.NextTouchNotifyRelativeLayout
    //
    // `NextTouchNotifyRelativeLayout` is the chat page ROOT, not a multi-select toolbar, so the button
    // was permanently visible -- present with no long-press and no multi-select, exactly as the user
    // reported. `C1()` returns a Component's root view, and for `sqj.c` that root is the whole page.
    //
    // It is deleted rather than narrowed because the target was never a toolbar; no rule change makes
    // it correct. The bar the user operates is `ukj.b` (wired in the step below). This note stays so
    // the next editor does not "restore" it: decorating a multi-select component's root view looks
    // obviously right and is wrong.


    /**
     * Puts our 「分析」 button on the BOTTOM multi-select action bar (`ukj.b`).
     *
     * ============================== WHY THIS EXISTS AT ALL ==============================
     * The first three builds added the button to `sqj.c` -- the TOP bar (返回 / 完成). The device log
     * said "the container has 3 child(ren) but NO TextView to clone", which is exactly the top bar's
     * shape, and the user reported no button. The lesson: adding a control to the WRONG surface looks
     * identical to a layout failure, so a refusal message must never be trusted as evidence about the
     * host until the container itself is confirmed to be the right one.
     *
     * Confirmed by evidence rather than by class-name guessing -- see [MULTI_BOTTOM_BAR]: the bar's
     * layout is `r/as/eak.xml`, its container id is `0x7f095fa5 id/forward_msg_bottom_part`, and the
     * only class referencing the four button ids is `ukj.b`.
     *
     * ============================== WHY IT RETRIES ==============================
     * `ukj.b.N1()` inflates the bar through `ViewStubHook.inflate` and only THEN assigns field `v`
     * ([002a]). If we read `v` before that, we get null; if the stub inflated but the host has not
     * populated children yet, we get an empty container. BOTH are timing, not breakage, so both are
     * retried with a short backoff. Reporting them as "the host changed its layout" is what sent the
     * previous investigation in the wrong direction.
     */
    private fun installBottomBarAnalysisButton() {
        val bar = loadClass(MULTI_BOTTOM_BAR) ?: run {
            Log.w(
                TAG,
                "KUAISHOU multi-select: " + MULTI_BOTTOM_BAR + " (the bottom action bar) is not " +
                    "loadable, so the 分析 button cannot be added. Long-press analysis still works.",
            )
            return
        }
        val wire = bar.declaredMethods.firstOrNull { it.name == "N1" } ?: run {
            Log.w(
                TAG,
                "KUAISHOU multi-select: " + MULTI_BOTTOM_BAR + ".N1() (view wiring) is absent, so there " +
                    "is no known moment at which the bottom bar exists. The 分析 button is not added.",
            )
            return
        }
        try {
            LegacyHook.hook(wire)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        // Retry across a few frames: the stub inflates inside this very method, and the
                        // host may populate children a moment later. Bounded, with increasing delay, and
                        // it stops as soon as the bar is found -- so the normal case does no waiting.
                        scheduleBottomBarAttempt(chain.thisObject, BOTTOM_BAR_RETRIES)
                    } catch (t: Throwable) {
                        Log.w(TAG, "KUAISHOU multi-select: bottom-bar button step threw: " + t.message)
                    }
                }
            bottomBarHooked = true
            Log.i(
                TAG,
                "KUAISHOU multi-select: hooked " + bar.name + ".N1() -- our \u5206\u6790 button goes on " +
                    "the BOTTOM action bar (field " + BOTTOM_BAR_FIELD + "), which is the bar the user " +
                    "operates. " + MULTI_TOOLBAR + " is the TOP bar and gets no button.",
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU multi-select: could not hook " + MULTI_BOTTOM_BAR + ".N1() (" + t.message +
                    "); the 分析 button is not added. Long-press analysis still works.",
            )
        }
    }

    /**
     * One attempt to add the button to the bottom bar, retrying on TIMING reasons only.
     *
     * The retry decision comes from the pure `Decision.retryable`, so "empty container" (timing) is
     * separated from "no cloneable child" (shape) in a way that is unit tested rather than re-decided
     * inline at each call site.
     */
    private fun scheduleBottomBarAttempt(barObject: Any?, attemptsLeft: Int) {
        if (barObject == null || attemptsLeft <= 0) return
        val container = readObjectField(barObject, BOTTOM_BAR_FIELD)
        if (container !is android.view.ViewGroup) {
            // The stub has not been inflated yet. Retry, and say plainly that this is timing.
            if (attemptsLeft == BOTTOM_BAR_RETRIES) {
                Log.i(
                    TAG,
                    "KUAISHOU multi-select: the bottom bar (field " + BOTTOM_BAR_FIELD + ") is " +
                        (container?.javaClass?.name ?: "<null>") + " -- not inflated yet. This is TIMING " +
                        "(the stub inflates inside N1()), so we retry up to " + BOTTOM_BAR_RETRIES +
                        " times rather than reporting a layout change.",
                )
            }
            postDelayedOnMain(BOTTOM_BAR_RETRY_MS) { scheduleBottomBarAttempt(barObject, attemptsLeft - 1) }
            return
        }
        val decision = addAnalysisButtonToToolbar(container)
        if (decision != null && decision.retryable && attemptsLeft > 1) {
            postDelayedOnMain(BOTTOM_BAR_RETRY_MS) { scheduleBottomBarAttempt(barObject, attemptsLeft - 1) }
        }
    }

    /** Runs [block] on the main thread after [ms], falling back to a direct call if no Looper is ready. */
    private fun postDelayedOnMain(ms: Long, block: () -> Unit) {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed(block, ms)
        } catch (t: Throwable) {
            // A missing main Looper would mean we are not on an Android runtime at all; just run it,
            // since the work is guarded by its own try/catch at every level.
            Log.w(TAG, "KUAISHOU multi-select: could not post a delayed retry (" + t.message + ")")
            try {
                block()
            } catch (_: Throwable) {
                // reported by the caller
            }
        }
    }

    /**
     * Adds ONE 「分析」 button to a container, idempotently. Returns the pure decision so the caller
     * can act on [KuaishouToolbar.Decision.retryable] (timing) instead of guessing.
     *
     * Re-entry is expected: `N1()` runs every time a bar is wired, and a bar can be re-wired on
     * configuration change or when the host rebuilds its components. The button is tagged, so a second
     * call finds the existing one and does nothing -- without that, re-wiring would stack duplicates.
     *
     * ============================== THE CLONE SOURCE IS A *CONTAINER* CHILD ==================
     * The bottom bar's direct children are four `LinearLayout`s (each = icon + TextView), NOT
     * TextViews. So requiring a `TextView` sibling would refuse on the very container we verified.
     * We clone any direct child's `LayoutParams`, which is the shape the host's own layout code already
     * accepted for its own buttons.
     */
    private fun addAnalysisButtonToToolbar(container: android.view.ViewGroup): KuaishouToolbar.Decision {
        val count = container.childCount
        val tags = ArrayList<Any?>(count)
        // Rank each child as a CLONE SOURCE. The bar's first child is a 1px divider View, and cloning
        // its params put our label-less button at the divider's own constraint position on the device.
        // So a container-like child (the host's real buttons are LinearLayouts) is preferred, and the
        // divider is only used if nothing better exists.
        val rank = ArrayList<Int>(count)
        for (i in 0 until count) {
            val c = container.getChildAt(i)
            tags.add(c?.tag)
            rank.add(
                when {
                    c == null || c.layoutParams == null -> KuaishouToolbar.RANK_UNUSABLE
                    c is android.view.ViewGroup -> KuaishouToolbar.RANK_BUTTON
                    c is android.widget.TextView -> KuaishouToolbar.RANK_BUTTON
                    else -> KuaishouToolbar.RANK_ANY
                },
            )
        }
        val decision = KuaishouToolbar.decide(tags, rank, TOOLBAR_BUTTON_TAG)
        if (!decision.add) {
            if (decision.reason == KuaishouToolbar.Reason.SKIP_ALREADY_PRESENT) {
                buttonAddsSkipped++
            }
            // Reported once per reason+container type, so a re-wire does not spam the log. Timing
            // retries are already narrated by the caller, so only non-retryable reasons are logged here.
            if (!decision.retryable &&
                toolbarMissing.add(decision.reason.name + ":" + container.javaClass.name)
            ) {
                Log.i(TAG, "KUAISHOU multi-select: " + KuaishouToolbar.explain(decision))
            }
            return decision
        }
        val ctx = try {
            container.context
        } catch (_: Throwable) {
            null
        }
        if (ctx == null) {
            Log.w(TAG, "KUAISHOU multi-select: the container has no Context; no button added")
            return decision
        }

        // The child the PURE decision chose, so the view actually cloned is the one that was logged.
        val sibling = container.getChildAt(decision.siblingIndex)
        if (sibling == null) {
            // Should be unreachable: the decision only says ADD when it saw a cloneable child there.
            // Reported rather than ignored, because it means the container changed between the reads.
            Log.w(
                TAG,
                "KUAISHOU multi-select: the container changed while deciding (sibling #" +
                    decision.siblingIndex + " is gone); no button added this time",
            )
            return decision
        }
        Log.i(TAG, "KUAISHOU multi-select: " + KuaishouToolbar.explain(decision))

        // A TextView is still preferred WHEN one is directly available, because its params are the
        // closest match to the host's own text buttons; otherwise any View works, since only the
        // params type needs to be one the host's layout code accepts.
        //
        // The result is then wrapped in OUR OWN TextView holding that params type. Wrapping rather than
        // requiring a TextView sibling is the whole point: the verified bottom bar has four
        // `LinearLayout` children and ZERO direct TextViews, so a TextView-only rule refuses on the
        // correct container -- which is exactly what the device log showed.
        //
        // ============================== THE COPY IS NOT ENOUGH ==============================
        // Device report #2: the button appeared but the host's 「逐条转发」 DISAPPEARED under it, and the
        // label we set was not visible. Cause: this bar is a `ConstraintLayout`, and copied params carry
        // the SOURCE child's CONSTRAINTS as well as its size. So the clone was pinned to exactly the
        // same position as the button we copied from -- i.e. drawn on top of it.
        //
        // Copying is still right (it gives a params TYPE the host's layout code accepts, which is what
        // avoids the uncatchable measure-time crash). What must NOT be copied is the position, so the
        // constraints are cleared and the clone is placed after the LAST host child instead. Doing it
        // this way keeps the working part and removes only the part that caused the overlap.
        val clone: android.widget.TextView? = try {
            android.widget.TextView(ctx).apply {
                val params = copyLayoutParams(sibling.layoutParams)
                if (params != null) layoutParams = params
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU multi-select: could not build the button with the host's sibling params (" +
                    t.message + "); no button added rather than guessing a params type",
            )
            null
        }
        if (clone == null) return decision

        // ============================== NO CONSTRAINT TWEAKING HERE (deliberate) ==============
        // An earlier version cleared the cloned constraints and re-linked the clone into the bar's
        // horizontal chain by reflecting on `startToEnd` / `endToEnd`. ALL OF IT WAS DEAD CODE:
        // `ConstraintLayout$LayoutParams` has its fields OBFUSCATED to single letters (a, b, c, ...),
        // so `getField("startToEnd")` threw NoSuchFieldException on every call and the surrounding
        // `catch (_: Throwable)` swallowed it. The log looked healthy while nothing happened.
        //
        // The clone is therefore left with the params it copied. They are a real params type the host's
        // layout code accepts (which is what avoids the uncatchable measure-time crash), and copying the
        // SOURCE's constraints means our button sits exactly where that source sits. That is why the
        // clone source matters so much: it decides the position, and it is chosen in `decide()`.
        val anchor: android.view.View = sibling
        clone.id = OUR_BUTTON_ID

        try {
            clone.text = MENU_LABEL
            clone.gravity = android.view.Gravity.CENTER
            clone.isSingleLine = true
            clone.isClickable = true
            clone.isFocusable = true
            clone.tag = TOOLBAR_BUTTON_TAG

            // The ORIGINAL icon, above the label, exactly as the host's own buttons arrange it.
            try {
                val density = ctx.resources.displayMetrics.density
                val d = com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, (ICON_SIZE_DP * density).toInt())
                clone.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null)
            } catch (t: Throwable) {
                // A label-only button is still a usable entry; requirement 3's "original icon" is
                // satisfied where possible and reported where not.
                Log.w(
                    TAG,
                    "KUAISHOU multi-select: could not attach the original \u5206\u6790 icon to the " +
                        "toolbar button (" + t.message + "); the button keeps its label only",
                )
            }

            // Param copy may legitimately be null (the sibling can lack params before a measure pass).
            // Assigning null to `layoutParams` is what threw "Layout parameters cannot be null" on
            // Douyin, so a null copy means "keep the constructor's own params" rather than "assign null".
            val copied = copyLayoutParams(sibling.layoutParams)
            if (copied != null) {
                try {
                    clone.layoutParams = copied
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "KUAISHOU multi-select: could not apply the cloned LayoutParams (" + t.message +
                            "); the button keeps its own. The button is still added.",
                    )
                }
            }

            clone.setOnClickListener { v -> onToolbarAnalysisClicked(v) }
            container.addView(clone)
            buttonAdds++

            // ============================== POST-LAYOUT DIAGNOSTIC ==============================
            // Device report #3 was "the button appears but tapping it does nothing". Two very different
            // causes look identical from outside: the listener never fired (wrong view / not clickable),
            // or it fired and the selection read came back empty. The log below separates them, and it
            // also reports the MEASURED size, because a button with no area cannot be tapped reliably
            // regardless of its listener -- which is exactly what cloning a 1px divider produced.
            //
            // Posted rather than read inline: dimensions are all zero until the next layout pass.
            try {
                clone.post {
                    val r = android.graphics.Rect()
                    clone.getGlobalVisibleRect(r)
                    Log.i(
                        TAG,
                        "KUAISHOU multi-select: our button is laid out (size=" + clone.width + "x" +
                            clone.height + ", visible=" + clone.isShown + ", clickable=" +
                            clone.isClickable + ", visibleRect=" + r.toShortString() + "). If a tap " +
                            "does nothing AND this size is 0x0, the tap never reached it -- that is a " +
                            "LAYOUT problem, not a selection problem.",
                    )
                }
            } catch (_: Throwable) {
                // Diagnostic only; never fatal.
            }

            Log.i(
                TAG,
                "KUAISHOU multi-select: added our '" + MENU_LABEL + "' button to container " +
                    container.javaClass.name + " (children now " + container.childCount + ", clone of " +
                    sibling.javaClass.name + ", anchor id=" + anchor.id + ", our id=" + clone.id +
                    ") -- it analyses the CURRENT selection via esj.z.m()",
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU multi-select: adding the button threw (" + t.message + "); the container is " +
                    "left exactly as the host built it",
            )
        }
        return decision
    }

    /**
     * The toolbar button's click: analyse whatever is selected right now.
     *
     * Reads the selection at CLICK time rather than caching it, because the user keeps ticking messages
     * while the toolbar is up. The anchor is the button's own root view, which is attached -- the shared
     * analysis path needs an attached view to resolve an Activity.
     */
    private fun onToolbarAnalysisClicked(v: android.view.View?) {
        try {
            val selected = readSelectedMessages(currentMultiSelectState())
            if (selected.isEmpty()) {
                Log.i(
                    TAG,
                    "KUAISHOU MULTISELECT: our \u5206\u6790 button was tapped but nothing is selected " +
                        "(esj.z.m() is empty), so nothing was analysed. This is the honest outcome " +
                        "rather than guessing which message the user meant.",
                )
                return
            }
            analysisStarts++
            if (KuaishouSelection.isBatch(selected)) {
                startBatchAnalysis(selected)
                return
            }
            // Exactly one selected: the ordinary single-message path, with the button as the anchor.
            val single = selected.first()
            Log.i(
                TAG,
                "KUAISHOU MULTISELECT: our \u5206\u6790 button was tapped with 1 message selected (" +
                    single.javaClass.name + "); routing to the single-message path",
            )
            controller.startAnalysisFor(single, v?.rootView)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU multi-select: the toolbar button's click failed: " + t.message)
        }
    }

    /**
     * A copy of [lp], or null when there is nothing safe to copy.
     *
     * Returns null rather than a default: assigning a params type the host's layout code does not expect
     * is what triggers the measure-time FATAL recorded for Douyin, and that crash is NOT catchable from
     * the injection site. Keeping the constructor's own params is the safe degradation.
     */
    private fun copyLayoutParams(lp: android.view.ViewGroup.LayoutParams?): android.view.ViewGroup.LayoutParams? {
        if (lp == null) return null
        return try {
            val ctor = lp.javaClass.getConstructor(lp.javaClass)
            ctor.isAccessible = true
            ctor.newInstance(lp) as? android.view.ViewGroup.LayoutParams
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The messages currently selected in Kuaishou's multi-select mode.
     *
     * Reads **the VALUES** of `esj.l.m()`, which the disassembly of `esj.l.g` proves are the `KwaiMsg`
     * objects themselves. Values are preferred over keys because the key is a COMPOSITE string
     * (`sender + '_' + clientSeq`) that this module would otherwise have to reproduce exactly, and any
     * mismatch would silently yield an empty selection -- the "nothing happened" failure this project
     * keeps re-recording.
     *
     * The DECISION (`KuaishouSelection.valuesOf`) is pure logic and unit-tested; this function only
     * performs the reflective read and reports what it found.
     *
     * @param stateObj the state object (normally `sqj.c.I`), or null
     * @return the messages; empty when nothing is readable
     */
    private fun readSelectedMessages(stateObj: Any?): List<Any> {
        if (stateObj == null) return emptyList()
        // One-shot evidence for WHICH source is correct, printed the first time a state object exists.
        // Deliberately here rather than at install: at install time no toolbar exists yet, so the probe
        // would only ever report "no state" and prove nothing.
        probeSelectionSources(stateObj)
        val m = findNoArg(stateObj.javaClass, "m") ?: run {
            Log.w(TAG, "KUAISHOU multi-select: no m() on " + stateObj.javaClass.name)
            return emptyList()
        }
        val raw = try {
            m.invoke(stateObj)
        } catch (t: Throwable) {
            Log.w(TAG, "KUAISHOU multi-select: m() threw " + t.message)
            null
        }
        val map = raw as? Map<*, *> ?: run {
            Log.w(
                TAG,
                "KUAISHOU multi-select: m() returned " + (raw?.javaClass?.name ?: "<null>") +
                    " instead of a Map; the selection cannot be read",
            )
            return emptyList()
        }
        val out = KuaishouSelection.valuesOf(map)
        // Second opinion from the host's OWN predicate (sqj.b.onClick uses this same combination).
        // Reported, never acted on: see crossCheckSelection for why a mismatch is logged rather than
        // silently switching which messages get analysed.
        crossCheckSelection(stateObj, out)
        if (out.isEmpty()) {
            Log.i(
                TAG,
                "KUAISHOU multi-select: the selection map is EMPTY (mapSize=" + map.size +
                    ") -- the user has not chosen any message, or the host cleared the selection",
            )
        }
        return out
    }

    /**
     * Prints ALL THREE candidate ways of reading the selection, with the element type of each.
     *
     * ============================== WHY ALL THREE, AND WHY IT IS NOT DEAD CODE ====================
     *
     * The team has had THREE contradictory answers for "where is the selected set", so the honest move
     * is to stop arguing from disassembly alone and print the evidence from the live object:
     *
     *   1. `esj.l.m()` -> field `i:java.util.Map`       -- what this module READS (values = KwaiMsg)
     *   2. `esj.l.j`  -> `ObservableSet`                -- holds STRING keys, not messages
     *   3. `sqj.c.J` -> `frj.i` -> `c():frj.p` -> `b()`  -- the paging result, NOT the selection
     *
     * The confusion that produced the wrong answers is worth naming, because it is a ONE-LETTER trap:
     * `esj.l` has a METHOD `i(KwaiMsg)Z` AND a FIELD `i:Map`. The method `i` is the DE-SELECT path --
     * its body does `Map.remove(key)` and `ObservableSet.remove(key)` -- while the FIELD `i` is the
     * Map that `m()` returns. Reading "the map that `i` fills" as if it described method `i` is exactly
     * how "the set holds strings" and "the values are messages" can both be stated confidently and
     * contradict each other:
     *
     *     esj.l.g(KwaiMsg)V   -> Map.put(key, msg)  AND  ObservableSet.add(key)   <-- SELECT
     *     esj.l.i(KwaiMsg)Z   -> Map.remove(key)    AND  ObservableSet.remove(key) <-- DE-SELECT
     *
     * BOTH facts are true of BOTH fields: the Map's VALUES are KwaiMsg, the ObservableSet's elements are
     * the String keys. So the map is the right source, and this print proves it on-device rather than
     * settling it by argument.
     *
     * Reported, never acted on: the two losing candidates are only INSPECTED. Nothing switches to them,
     * so a surprise here degrades to a log line instead of silently changing which messages are analysed.
     */
    private fun probeSelectionSources(stateObj: Any?) {
        if (stateObj == null) {
            // NOTE: the latch is deliberately NOT consumed here. A null state means "there is nothing to
            // inspect yet" (the user has not entered multi-select), not "the probe ran". Consuming the
            // one-shot on this path would suppress the real probe for the whole process -- the probe
            // would then never print, and its absence would look like agreement rather than a bug.
            return
        }
        if (!selectionProbeDone.compareAndSet(false, true)) return
        val sb = StringBuilder("KUAISHOU SELECTION PROBE (one-shot, all three candidates):")

        // 1. the field the module reads
        val map = try {
            findNoArg(stateObj.javaClass, "m")?.invoke(stateObj)
        } catch (_: Throwable) {
            null
        }
        if (map is Map<*, *>) {
            val firstVal = map.values.firstOrNull()
            sb.append("\n  [1] m()->Map (WE USE THIS): size=").append(map.size)
                .append(" valueClass=").append(firstVal?.javaClass?.name ?: "<empty>")
                .append(" isKwaiMsg=").append(firstVal?.let { access.isMessage(it) } ?: false)
                .append(" firstValue=").append(oneLine(firstVal))
                .append("\n      -> the values are MESSAGE OBJECTS, which is what describeMessages needs")
        } else {
            sb.append("\n  [1] m() returned ").append(map?.javaClass?.name ?: "<null>")
        }

        // 2. the ObservableSet field j: holds the STRING keys, not messages
        val set = readObjectField(stateObj, MULTI_SET_FIELD)
        if (set is Collection<*>) {
            val first = set.firstOrNull()
            sb.append("\n  [2] field ").append(MULTI_SET_FIELD).append(":ObservableSet: size=")
                .append(set.size).append(" firstClass=").append(first?.javaClass?.name ?: "<empty>")
                .append(" first=").append(oneLine(first))
                .append("\n      -> EXPECTED to be a STRING (sender+'_'+clientSeq). If this were the")
                .append(" message set, it would hold ").append(KuaishouEnvelope.MSG_CLASS)
        } else {
            sb.append("\n  [2] field ").append(MULTI_SET_FIELD).append(" is ")
                .append(set?.javaClass?.name ?: "<absent>")
        }

        // 3. the paging route, reported so the wrong one cannot be mistaken for the selection
        sb.append("\n  [3] sqj.c.J -> frj.i -> frj.p.b(): deliberately NOT read as the SOLE source -- it is ")
            .append("the MESSAGE-PAGE LOAD RESULT (its data source is the paging trigger), so on its own ")
            .append("it yields the whole loaded page rather than what the user ticked.")
            .append("\n      -> Kuaishou's OWN idiom is CANDIDATE SET + PREDICATE, verified in ")
            .append("sqj.b.onClick: [0x12c] frj.i->c():frj.p, [0x138] frj.p->b():List, ")
            .append("[0x19e] check-cast KwaiMsg, [0x1b8] esj.z->t(KwaiMsg)Z, [0x1c0] skip if unselected.")

        Log.i(TAG, sb.toString())
    }

    /**
     * Cross-checks the selection we actually use against the host's OWN predicate, and reports a
     * MISMATCH loudly.
     *
     * ============================== WHY A CROSS-CHECK AND NOT A REWRITE ========================
     *
     * Kuaishou's own idiom for "which messages are selected" is CANDIDATE SET + PREDICATE, proved by
     * disassembling `sqj.b.onClick`:
     *
     *     [0x12c] invoke-interface frj.i->c()Lfrj/p;       <- the paging data service
     *     [0x138] invoke-virtual   frj.p->b()Ljava/util/List;  <- CANDIDATE SET
     *     [0x19e] check-cast v3, Lcom/kwai/imsdk/msg/KwaiMsg;   <- elements ARE messages
     *     [0x1b8] invoke-interface esj.z->t(Lcom/kwai/imsdk/msg/KwaiMsg;)Z   <- PREDICATE "is selected"
     *     [0x1c0] if-eqz v6, -0x1b                          <- unselected -> skip
     *
     * The module reads `esj.z.m()`'s Map values instead. Those two routes are NOT obviously the same
     * thing, so rather than swap one unverified route for another this asks the host directly:
     * `esj.z.t(msg)` IS "is this message selected", and the Map's values IS "what the user selected".
     * If they ever disagree, the read is wrong and we must know -- silently analysing the wrong set is
     * exactly the kind of failure this module is built to avoid.
     *
     * WHY THE MAP IS STILL THE SOURCE: `esj.l.g`/`i`/`s` (select / de-select / clear) update the Map
     * AND the `ObservableSet` in LOCKSTEP, and the Set holds only the `String` keys that the Map is
     * keyed by. So the predicate and the Map answer the same question by construction, and the Map
     * carries the message objects needed downstream while the Set does not.
     *
     * Reports a disagreement and KEEPS the Map: the Map is what `describeMessages` can consume, and a
     * mismatch is a signal to fix the read deliberately, not to silently switch behaviour mid-session.
     */
    private fun crossCheckSelection(stateObj: Any?, selected: List<Any>) {
        if (selected.isEmpty()) return
        // ============================== THE PARAMETER-COUNT BUG ==============================
        // This used to call `findNoArg(stateObj.javaClass, "t")`, which requires parameterCount == 0.
        // But `esj.z->t` is `t(Lcom/kwai/imsdk/msg/KwaiMsg;)Z` -- ONE parameter (verified in the dex:
        // the interface declares 20 methods and `t` takes a KwaiMsg). So the lookup ALWAYS returned
        // null, the early return ALWAYS fired, and THIS WHOLE CROSS-CHECK NEVER RAN. The log line said
        // "not reachable on <class>", which reads like a host-version change and sent the reader
        // looking at the wrong thing: the truth was a parameter-count mismatch in our own lookup.
        //
        // Independently found by review. Kept as a comment because the failure mode -- a lookup that is
        // permanently null for a reason unrelated to the host -- is silent by construction.
        val predicate = findOneArg(stateObj!!.javaClass, "t") ?: run {
            Log.w(
                TAG,
                "KUAISHOU SELECTION CROSS-CHECK: esj.z->t(KwaiMsg)Z could not be resolved on " +
                    stateObj.javaClass.name + " (expected a ONE-argument method named 't'), so the " +
                    "selected set could not be re-verified against the host's own predicate. The Map " +
                    "route is still authoritative -- this only means the second opinion is unavailable.",
            )
            return
        }
        var agreed = 0
        val disagreed = ArrayList<String>(2)
        for (m in selected) {
            // `as? Boolean` alone is enough: `esj.z.t` returns a PRIMITIVE boolean, which boxes to a
            // real `Boolean`, so a null here can only mean the invocation itself failed -- and the
            // `catch` already covers that. The `?: false` this used to carry would have turned an
            // invocation failure into a false "NOT selected" accusation, i.e. a fabricated mismatch.
            val ok: Boolean? = try {
                predicate.invoke(stateObj, m) as? Boolean
            } catch (_: Throwable) {
                null
            }
            when (ok) {
                true -> agreed++
                false -> if (disagreed.size < 3) {
                    disagreed.add(KuaishouEnvelope.simpleName(m.javaClass.name))
                }
                null -> Unit // invocation failed; not evidence either way, do not report it as one
            }
        }
        if (disagreed.isEmpty()) {
            Log.i(
                TAG,
                "KUAISHOU SELECTION CROSS-CHECK ok: all " + agreed + " message(s) from esj.z->m() are " +
                    "confirmed selected by the host's own esj.z->t(msg) predicate",
            )
        } else {
            Log.w(
                TAG,
                "KUAISHOU SELECTION CROSS-CHECK MISMATCH: " + disagreed.size + " of " + selected.size +
                    " message(s) from esj.z->m() are reported NOT selected by esj.z->t(msg) " +
                    "(classes=" + disagreed.joinToString(",") + "). The Map was still used. A mismatch " +
                    "means the two views of the selection have diverged -- report this on-device",
            )
        }
    }

    /** One short line describing a value, for the probe. Never dumps a collection or a payload. */
    private fun oneLine(v: Any?): String {
        if (v == null) return "<null>"
        val s = try {
            v.toString()
        } catch (_: Throwable) {
            return "<toString threw>"
        }
        return if (s.length > 60) s.substring(0, 60) + "..." else s
    }

    /** The first field of the given type name anywhere in the hierarchy, or null. */
    private fun findFieldOfType(cls: Class<*>, typeName: String): java.lang.reflect.Field? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.type.name == typeName) return f
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /** The first field whose type is assignable to [type] anywhere in the hierarchy, or null. */
    private fun findFieldOfAssignableType(cls: Class<*>, type: Class<*>): java.lang.reflect.Field? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (!type.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                return f
            }
            p = p.superclass
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ report

    /** Periodic report so a silent feed cannot be mistaken for a working one. */
    private fun startReport() {
        val t = Thread({
            // Long enough that an install-time failure is already visible in the log, short enough
            // that the first device session sees the verdict without waiting.
            try {
                Thread.sleep(20_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            Log.i(
                TAG,
                "KUAISHOU report: funnelFires=" + funnelFires +
                    " pushed=" + pushed +
                    " noMessage=" + noMessage +
                    " menuFires=" + menuFires +
                    " menuAdds=" + menuAdds +
                    " menuSkips=" + menuSkips +
                    " menuRelabels=" + menuRelabels +
                    " iconAttaches=" + iconAttaches +
                    " bindHooked=" + bindHooked +
                    " menuHooked=" + menuHooked +
                    " menuInjectHook=" + menuInjectHook +
                    " menuLabelHooked=" + menuLabelHooked +
                    " menuClickHooked=" + menuClickHooked +
                    " analysisStarts=" + analysisStarts +
                    " multiSelectHooked=" + multiSelectHooked +
                        " buttonAdds=" + buttonAdds +
                    " buttonAddsSkipped=" + buttonAddsSkipped,
            )
            // The bottom action bar is the ONLY surface our button is injected into (the top bar's
            // injection was deleted -- `sqj.c.C1()` returns the chat page ROOT, so its button appeared
            // permanently). So a missing button has exactly two useful distinctions now:
            //   * the hook never installed     -> `bottomBarHooked=false`
            //   * installed but never fired    -> the user has not opened multi-select since install
            //   * fired but refused/retried    -> a LAYOUT shape problem, reported at the refusal site
            if (multiSelectHooked && !bottomBarHooked) {
                Log.w(
                    TAG,
                    "KUAISHOU report: multi-select is wired but the BOTTOM action bar (" + MULTI_BOTTOM_BAR +
                        ".N1()) was NOT hooked, so our \u5206\u6790 button has no surface. The multi-select " +
                        "requirement is then only served by the long-press path, which is a DIFFERENT " +
                        "surface -- treat this as PARTLY met, not met.",
                )
            } else if (bottomBarHooked && buttonAdds == 0 && buttonAddsSkipped == 0) {
                Log.w(
                    TAG,
                    "KUAISHOU report: the bottom-bar hook is installed but multi-select has not been " +
                        "opened since install, so no button has been added yet. This is EXPECTED until " +
                        "the user opens multi-select once -- it is NOT a failure.",
                )
            }
            if (menuFires > 0 && menuAdds == 0) {
                Log.w(
                    TAG,
                    "KUAISHOU report: the long-press funnel FIRED " + menuFires + " times but NO option " +
                        "was appended, so the \u5206\u6790 item never appeared. Check the " +
                        "'option ctor' lines above: either " + MENU_EVENT_CLASS +
                        ".<init> is not hookable or its argument shape changed",
                )
            }
            // The three-way verdict, so a device session states which of the indistinguishable cases
            // actually happened instead of leaving it to be inferred from a missing menu row.
            if (menuFires > 0 && menuAdds == 0) {
                when (lastLongPressConfig) {
                    false -> Log.w(
                        TAG,
                        "KUAISHOU report VERDICT: the host's own config gate says long-press is NOT " +
                            "supported (b->G:Lpqj/d.a() == false), so `b.Z0` returned before building " +
                            "any option list. The hook and the injection point are fine; the HOST " +
                            "declines to show a menu on this device state. Our item could not be " +
                            "reached, and that is not an injection failure.",
                    )
                    true -> Log.w(
                        TAG,
                        "KUAISHOU report VERDICT: the config gate says long-press IS supported " +
                            "(b->G:Lpqj/d.a() == true), yet no option was appended -- so the failure is " +
                            "AFTER the gate: either the later '\u4e0d\u6ee1\u8db3\u5c55\u793a\u6761" +
                            "\u4ef6' check returned, or " + MENU_EVENT_CLASS + ".<init> was not hooked " +
                            "on this build. Those two are the suspects, not the config flag.",
                    )
                    null -> Log.w(
                        TAG,
                        "KUAISHOU report VERDICT: the config gate could not be read (field " +
                            LONG_PRESS_CONFIG_FIELD + " or pqj/d.a() absent). Do NOT read this as " +
                            "\"disabled\" -- the value is unknown. Check whether a Kuaishou update " +
                            "renamed the field.",
                    )
                }
            }
            if (analysisStarts > 0) {
                Log.i(
                    TAG,
                    "KUAISHOU report: the \u5206\u6790 item was tapped " + analysisStarts +
                        " time(s) and the shared analysis path was entered. If no dialog appeared, the " +
                        "cause is inside the shared path (blank text / blank talker), NOT in this hook -- " +
                        "look for the 'blank-text' or 'blank-talker' rejection lines",
                )
            }
            if (bindHooked && funnelFires == 0) {
                Log.w(
                    TAG,
                    "KUAISHOU report: the bind hook was attached but NEVER fired. Look at, in order: " +
                        "(1) the process name -- this must be the MAIN Kuaishou process, since " +
                        "IMChatActivity declares no android:process (R22); (2) whether any chat list " +
                        "was actually scrolled, because bindViewHolder only runs when rows bind; " +
                        "(3) the Robust patch state logged at install",
                )
            }
            if (funnelFires > 0 && pushed == 0) {
                Log.w(
                    TAG,
                    "KUAISHOU report: the funnel FIRED " + funnelFires + " times but pushed NOTHING. " +
                        "That means no bind looked like a chat message: either the chat page was never " +
                        "opened, or the per-index accessor is not o2(int)/getItem(int) on the adapter " +
                        "the chat really uses. The 'bind produced no message' lines name the real " +
                        "adapter and holder classes",
                )
            }
        }, "LuoLiao-kuaishou-report")
        t.isDaemon = true
        t.start()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resolves a host class by name through the HOST loader.
     *
     * `resolver.classLoader()` is tried first and the constructor argument is only a fallback, with
     * the fallback LOGGED: in a clone the module's loader resolves the same name to a different Class
     * object, and a hook attached to that copy never fires while the install log looks healthy.
     */
    private fun loadClass(name: String): Class<*>? {
        try {
            return Class.forName(name, false, resolver.classLoader())
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KUAISHOU: " + name + " did not resolve via the host loader (" + t.javaClass.simpleName +
                    "); trying the loader handed to the constructor as a fallback",
            )
        }
        return try {
            Class.forName(name, false, loader)
        } catch (_: Throwable) {
            null
        }
    }

    /** A no-arg method anywhere in the hierarchy. */
    private fun findNoArg(cls: Class<*>, name: String): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.name != name || m.parameterCount != 0) continue
                return try {
                    m.isAccessible = true
                    m
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * An exactly-ONE-parameter method named [name] anywhere in the hierarchy.
     *
     * Exists because [findNoArg] requires zero parameters, and using it for `esj.z->t` (which takes a
     * KwaiMsg) made that lookup permanently null -- so the cross-check silently never ran while logging
     * a message that blamed the host. A lookup helper whose arity does not match the host's signature is
     * a silent failure by construction, so the arity is now named in the function it is used for.
     *
     * The parameter TYPE is not constrained: the host has changed the argument's static type across
     * versions before, and a wrong argument is rejected by the JVM at invoke time (caught by the
     * caller) rather than mis-binding to a different method.
     */
    private fun findOneArg(cls: Class<*>, name: String): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.name != name || m.parameterCount != 1) continue
                return try {
                    m.isAccessible = true
                    m
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * A one-int-parameter method anywhere in the hierarchy.
     *
     * `name` may be null to accept any name -- used only as the shape fallback, and its result is
     * always gated by [unwrapRow]/[isMessageLike] rather than trusted on the shape alone.
     */
    private fun findIntAccessor(cls: Class<*>, name: String): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.name != name) continue
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                return try {
                    m.isAccessible = true
                    m
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    private fun invokeInt(m: Method, target: Any, arg: Int): Any? = try {
        m.invoke(target, arg)
    } catch (_: Throwable) {
        null
    }

    /**
     * Reads the host's own "is long-press supported" flag, or null when it cannot be read.
     *
     * Verified route (disassembly of `b.Z0`):
     *
     *     iget-object b->G : Lpqj/d;   ->   invoke-virtual pqj/d->a()Z   ->   iget-boolean pqj/d->h:Z
     *
     * Three-valued on purpose, and the third value matters: `null` means "could not read" and must NOT
     * be reported as `false`. Collapsing the two would turn a missing field after a Kuaishou rename into
     * a confident claim that the feature is disabled -- a false diagnosis is worse than no diagnosis,
     * because it sends the next debug session hunting a config flag that was never the problem.
     *
     * The getter is called rather than the field `h` being read directly, so the value is whatever the
     * host would actually branch on, including any logic inside `a()`.
     */
    private fun readLongPressConfigFlag(host: Any?): Boolean? {
        if (host == null) return null
        val cfg = readObjectField(host, LONG_PRESS_CONFIG_FIELD) ?: return null
        val a = findNoArg(cfg.javaClass, "a") ?: return null
        return try {
            a.invoke(cfg) as? Boolean
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasNoArg(cls: Class<*>, name: String): Boolean = findNoArg(cls, name) != null

    /**
     * Reports whether Kuaishou's Robust hot-patch engine currently has a redirect registered for the
     * classes this module hooks.
     *
     * ============================== WHY THIS IS A RUNTIME CHECK, NOT A STATIC ONE ===============
     *
     * `com.kwai.robust.PatchProxy.apply*` prologue calls sit at the head of almost every business method
     * in this APK. Their contract, read from the bytecode, is:
     *
     *     [0000] invoke-static PatchProxy->applyVoidTwoRefs(obj, arg, cls, "16")Z
     *     [000e] move-result v0
     *     [0010] if-eqz v0, +0003   <-- when the patch HANDLES the call, the ORIGINAL BODY IS SKIPPED
     *     [0014] return-void
     *
     * `EmojiEditText.setText` uses patch id `"16"`. This engine is the same FAMILY as Soul's Tinker,
     * which 必看.md records as a hazard: when a patch is active for a class, a method body can return
     * early, so work that body would have done never happens and a hook on it can look dead.
     *
     * A STATIC reading cannot answer whether a patch is active -- that depends on what Kuaishou has
     * downloaded and applied AT THIS MOMENT. `PatchProxy.isSupport2(Class,String)` is that answer, and
     * its disassembly is essentially one map probe:
     *
     *     [0016] sget-boolean PatchProxy->sChangeQuickRedirectEmpty:Z
     *     [001e] sget-object PatchProxy->sChangeQuickRedirect:Ljava/util/Map;
     *     [0022] invoke-interface java/util/Map->containsKey(...)Z
     *
     * So this asks the engine directly and logs the verdict. It changes NO behaviour: it exists so that
     * "our hook fired but nothing happened" can be distinguished from "a patch short-circuited the
     * body", which are otherwise indistinguishable from a log and would send a debug session down the
     * wrong path.
     */
    private fun probePatchProxy() {
        try {
            val pp = try {
                Class.forName(PATCH_PROXY_CLASS, false, resolver.classLoader())
            } catch (_: Throwable) {
                null
            }
            if (pp == null) {
                Log.i(
                    TAG,
                    "KUAISHOU hot-patch probe: " + PATCH_PROXY_CLASS + " is not loadable, so no Robust " +
                        "hot-patch is active in this process -- hooks on the message/input classes " +
                        "cannot be short-circuited by it",
                )
                return
            }
            val empty = try {
                val f = pp.getDeclaredField("sChangeQuickRedirectEmpty")
                f.isAccessible = true
                f.getBoolean(null)
            } catch (_: Throwable) {
                false
            }
            val map = try {
                val f = pp.getDeclaredField("sChangeQuickRedirect")
                f.isAccessible = true
                f.get(null) as? Map<*, *>
            } catch (_: Throwable) {
                null
            }

            // Ask about the classes whose behaviour this module most depends on: the input field
            // (whose setText is patch "16"), the multi-select state, and the message model.
            val watched = ArrayList<Pair<String, Class<*>>>(3)
            loadClass("com.yxcorp.gifshow.widget.EmojiEditText")
                ?.let { watched.add("EmojiEditText(setText=16)" to it) }
            loadClass(MULTI_IMPL)?.let { watched.add(MULTI_IMPL + "(multi-select state)" to it) }
            loadClass(KuaishouEnvelope.MSG_CLASS)?.let { watched.add("KwaiMsg" to it) }
            val patched = ArrayList<String>(watched.size)
            for ((label, cls) in watched) {
                val hit = try {
                    map?.containsKey(cls) ?: false
                } catch (_: Throwable) {
                    false
                }
                if (hit) patched.add(label)
            }
            Log.i(
                TAG,
                "KUAISHOU hot-patch probe: " + PATCH_PROXY_CLASS +
                    " present=true redirectMapReadable=" + (map != null) +
                    " mapEmpty=" + empty +
                    " mapSize=" + (map?.size ?: -1) +
                    " patchedAmongWatched=" +
                    (if (patched.isEmpty()) "none" else patched.joinToString("/")),
            )
            if (patched.isNotEmpty()) {
                Log.w(
                    TAG,
                    "KUAISHOU hot-patch probe: a Robust redirect IS registered for " +
                        patched.joinToString("/") +
                        ". A patched method body can return early and SKIP its original work, so a hook " +
                        "on it may appear dead. Reported, not worked around: if a hook below never " +
                        "fires while everything else looks healthy, this line is the first suspect",
                )
            }
        } catch (t: Throwable) {
            // A diagnostic must never take down installation.
            Log.w(TAG, "KUAISHOU hot-patch probe failed: " + t.message)
        }
    }


    /**
     * Reads a named field anywhere in the hierarchy.
     *
     * Used for the host's own private fields (the row's option and its TextView). Returns null rather
     * than throwing, because every caller treats a missing field as "this row is not ours" or "the label
     * cannot be set" -- both of which degrade a single cosmetic behaviour instead of the feature.
     */
    private fun readObjectField(obj: Any, name: String): Any? {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name != name) continue
                return try {
                    f.isAccessible = true
                    f.get(obj)
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /**
     * Writes an `int` field anywhere in the hierarchy. Returns true when the write happened.
     *
     * Used for exactly ONE field, `r9j.f.a` -- the code `r9j.k.c()` reads. See [reportInheritedCodes] for
     * why that field (and only that field) is safe to change: every read of it in `aaj.g.onBind` and
     * `aaj.g$a.onClick` is an integer COMPARISON against a constant, never a resource lookup.
     *
     * Never throws: a missing or non-int field is reported as "a Kuaishou rename" rather than crashing,
     * and the row still works (it would merely keep the host's own code, i.e. the previous behaviour).
     */
    private fun writeIntField(obj: Any, name: String, value: Int): Boolean {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name != name || f.type != Integer.TYPE) continue
                return try {
                    f.isAccessible = true
                    f.setInt(obj, value)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            p = p.superclass
            depth++
        }
        return false
    }

    /**
     * Reads an `int` field anywhere in the hierarchy, or null when absent / not an int.
     *
     * The READ-side twin of [writeIntField], used to report what our row inherited (see
     * [reportInheritedCodes]). Never throws: a missing field means a Kuaishou rename, which is worth
     * reporting but not fatal.
     */
    private fun readIntField(obj: Any, name: String): Int? {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name != name || f.type != Integer.TYPE) continue
                return try {
                    f.isAccessible = true
                    f.getInt(obj)
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    /** Names each distinct adapter class once, so a busy list cannot bury the chat's adapter. */
    private fun noteAdapterType(adapter: Any) {        val n = adapter.javaClass.name
        if (seenAdapterTypes.size >= ADAPTER_TYPE_LIMIT) return
        if (!seenAdapterTypes.add(n)) return
        Log.i(
            TAG,
            "KUAISHOU ADAPTER (new #" + seenAdapterTypes.size + "): " + n +
                " itemCount=" + itemCountOf(adapter) +
                (if (n == CHAT_ADAPTER) "  <-- THIS IS THE EXPECTED CHAT ADAPTER" else ""),
        )
    }

    private fun itemCountOf(adapter: Any): Int = try {
        val m = findNoArg(adapter.javaClass, "getItemCount")
        (m?.invoke(adapter) as? Int) ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** Names each distinct holder class once (diagnostic only; the message comes from the adapter). */
    @Suppress("unused")
    private fun noteHolderType(holder: Any?) {
        val n = holder?.javaClass?.name ?: return
        if (seenHolderTypes.size >= HOLDER_TYPE_LIMIT) return
        if (!seenHolderTypes.add(n)) return
        Log.i(TAG, "KUAISHOU HOLDER (new #" + seenHolderTypes.size + "): " + n)
    }
}
