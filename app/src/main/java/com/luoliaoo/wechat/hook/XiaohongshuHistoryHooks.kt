package com.luoliaoo.wechat.hook

import android.view.View
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Feeds Xiaohongshu's history: records messages AS THEY ARE DISPLAYED, without the user pressing 分析.
 *
 * ============================== WHY A FEEDER IS NEEDED AT ALL ==============================
 *
 * The module's history pipeline already exists and is host-agnostic:
 *
 *   * `AnalysisController.startLiveTimer()` schedules a fixed-delay task every **2000 ms**
 *     (`AnalysisController.kt:2220` function, `:2228` the `scheduleWithFixedDelay` call);
 *   * the timer is started for EVERY host at `WeChatHooks.kt:541`, BEFORE the host-specific section;
 *   * the feeder's only job is to hand messages to `AnalysisController.onLiveMessage(msg)`, which
 *     STAGES them; the timer does the writing.
 *
 * So a host that never calls `onLiveMessage` has a timer waking every 2 seconds and finding nothing
 * staged. That is exactly the defect the user reported on QQ in these words:
 *
 *     「历史聊天记录只会写入长按分析过的那一条」
 *
 * and the fix there was to add the missing feeder, not to touch the timer. The same shape applies
 * here: WITHOUT THIS CLASS, Xiaohongshu history would contain only the messages the user manually
 * analysed.
 *
 * ============================== HOW THIS HOST EXPOSES ITS MESSAGES ==============================
 *
 * Two independent routes, both installed, because they observe different moments and cannot duplicate
 * each other -- the store keys by (talker, msgId):
 *
 *   1. THE ADAPTER BIND. `[DEX]` `MessageRecyclerView`/`ChatPageRecyclerView` are ordinary
 *      `RecyclerView`s, so the message rows are bound through
 *      `RecyclerView.Adapter#onBindViewHolder(VH, int)`. Hooking that on the CONCRETE adapter class
 *      found in the view tree is the most direct route: it fires once per visible row, which is
 *      precisely "the messages the user can see".
 *
 *   2. THE ROW VIEW. `ChatStickerDoubleClickTextView` is the message text view `[ON-DEVICE]`. When
 *      route 1 cannot be resolved (an adapter class this build names differently), the text view's
 *      own attachment is enough to recover the message TEXT, and a text-only record still populates
 *      the history -- which is what the user sees and cares about.
 *
 * ============================== WHY NOT USE THE WINDOW PROBE FOR THIS ==============================
 *
 * Because history must work with no menu on screen. A feeder keyed on the long-press menu would
 * recreate the original defect: messages would only be recorded while a menu was open.
 *
 * Comments are ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class XiaohongshuHistoryHooks(
    @Suppress("UNUSED_PARAMETER") private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    private val access by lazy { XiaohongshuMsgAccess(resolver) }

    private val logged = Collections.synchronizedSet(HashSet<String>())

    /** Message objects already handed over, by identity, so a re-bind does not re-stage the same row. */
    private val staged = Collections.newSetFromMap(
        Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>()),
    )

    @Volatile
    private var emitted = 0

    /** Set once the sweep has reported success, so the log gets one line rather than one per tick. */
    @Volatile
    private var sweepLogged = false

    /** How many times the universal binder ran -- proof the hook is alive on any screen. */
    @Volatile
    private var universalBinds = 0

    /** True until the universal binder has reported its first captured message. */
    @Volatile
    private var universalFirst = true

    /** True until the LIVE message adapter has reported its first captured message. */
    @Volatile
    private var adapterFirst = true

    /**
     * The adapter class found in the view tree, recorded so the primary hook knows what to bind.
     *
     * Held as a Class rather than a name so the hook can be installed the moment it is discovered.
     */
    @Volatile
    private var adapterHooked: Class<*>? = null

    fun install(): Boolean {
        if (installed) return true
        installed = true

        Log.i(
            TAG,
            "xhs: live history feed starting -- messages are recorded as they are BOUND, without the " +
                "user pressing \u5206\u6790. The 2s writer already exists (AnalysisController." +
                "startLiveTimer, 2000ms, started host-agnostically at WeChatHooks.kt:541); this class " +
                "only supplies the missing FEEDER.",
        )

        // ============================== ROUTE 0: THE UNIVERSAL BINDER -- THE DEFINITIVE ROUTE ==========
        //
        // Everything below this comment is a FALLBACK. This is the route that settles the problem.
        //
        // WHY IT WORKS, and why nothing else did: this host builds EVERY list with MultiType, and every
        // MultiType row class shares one base. Decoding the APK's class table shows it directly:
        //
        //     Lcom/xingin/im/ui/widgets/pressmenu/itembinder/LongPressMenuItemBinder; -> Lda/c;
        //     Lcom/xingin/im/ui/adapter/viewholder/IMShareTopicItemViewBinder;        -> Lda/c;
        //     Lcom/xingin/im/ui/adapter/GroupShortCutItemViewBinder;                  -> Lda/c;
        //     Lcom/xingin/im/v2/group/member/adapter/AddMemberItemViewBinder;         -> Lda/c;
        //     ...every MultiType row binder in the app
        //
        // `da.c` is MultiType's `ItemViewBinder`. Its base declares:
        //
        //     public abstract void onBindViewHolder(VH holder, T item);
        //     public void onBindViewHolder(VH holder, T item, List<Object> payloads) {
        //         onBindViewHolder(holder, item);          <== the base implementation
        //     }
        //
        // `MultiTypeAdapter` ALWAYS calls the THREE-argument form, so hooking that one method on `da.c`
        // receives `item` for every row the app binds -- chat messages included -- as a plain argument.
        // No view tree, no adapter list, no reflective drilling: the host hands it over.
        //
        // WHY THIS BEATS EVERY EARLIER ATTEMPT ON THIS HOST:
        //   * `IMDiffMultiTypeAdapter#onBindViewHolder` has only 2 params and inherits BaseRecyclerView
        //     Adapter's, so its arguments are (ViewHolder, position) -- no item at all;
        //   * the adapter's data list was unreadable behind a diff wrapper and obfuscated accessors;
        //   * `findMessageRecyclerView` matched the wrong list of two on the same page.
        //
        // THE SAME HOOK ALSO FEEDS THE MENU: `LongPressMenuItemBinder` is a `da.c` subclass, so a
        // long-press row's bind passes its item here too. That is why one hook covers both surfaces.
        val universal = installMultiTypeAdapterHook()

        // Route 1: the adapter bind, discovered from the live view tree. It cannot be hooked by name,
        // because the concrete adapter class is an implementation detail of this build.
        val fromTree = tryDiscoverAdapter()

        // Route 2: the text view, as the fallback for a build whose adapter is unreachable.
        val fromText = installTextViewHook()

        // A watchdog keeps trying to find the adapter while the user is in the chat: the first entry
        // into a conversation may happen long after startup, and the adapter class does not exist in
        // the view tree until then.
        startAdapterWatchdog()

        Log.i(
            TAG,
            "xhs: live history feed install -- universalBinder=" + universal +
                " adapterFromTree=" + fromTree + " textViewFallback=" + fromText,
        )
        return universal || fromTree || fromText
    }

    /**
     * Hooks `MultiTypeAdapter#onBindViewHolder(VH, position)` -- the one method EVERY MultiType list in
     * this app calls, for every row it draws.
     *
     * ============================== WHY THIS AND NOT THE BINDER BASE ==============================
     *
     * The first attempt hooked `da.c` (MultiType's `ItemViewBinder` base) and the device log refused it
     * with a decisive line:
     *
     *     xhs: da.c declares no onBindViewHolder/3-arg method.
     *          Declared: onCreateViewHolder(Context,ViewGroup), onCreateViewHolder(LayoutInflater,ViewGroup)
     *
     * That is not a naming mistake -- it is how MultiType is designed: `ItemViewBinder` declares
     * `onBindViewHolder(holder, item)` as ABSTRACT, so the base class has no body to hook. Each of the
     * app's hundreds of row binders implements it separately, and hooking every one is not possible.
     *
     * The DISPATCHER is the right place instead. `MultiTypeAdapter` is the library class that looks up
     * the binder for a position and calls it, and it is NOT obfuscated -- the app's own view tree proves
     * the name (and the items field) directly:
     *
     *     >> RECYCLERVIEW adapter=com.drakeet.multitype.MultiTypeAdapter itemCount=6
     *          .d : List size=6 first=ly7.c
     *
     * So its `onBindViewHolder(VH, int)` is called for every row of every MultiType list -- the chat
     * message list included -- with the adapter as `thisObject`. The item is then the entry at that
     * position in the adapter's own list, read through [itemAt] (by FIELD, because `d` is obfuscated).
     *
     * WHY THIS IS THE DEFINITIVE ROUTE FOR THIS HOST: it depends on ONE third-party library class name
     * that the app cannot rename (it ships pre-compiled) and on ONE integer argument. It does not care
     * what the chat adapter subclass is called, how the list views are nested, or whether a data-list
     * accessor exists -- the three things that each broke an earlier attempt.
     *
     * @return true when at least one hook was installed.
     */
    private fun installMultiTypeAdapterHook(): Boolean {
        val cls = try {
            Class.forName(MULTITYPE_ADAPTER, false, resolver.classLoader())
        } catch (_: Throwable) {
            null
        }
        if (cls == null) {
            note(
                "no-multitype-adapter",
                "xhs: " + MULTITYPE_ADAPTER + " is not loadable, so the universal history feed is " +
                    "unavailable and the adapter routes below are used instead.",
            )
            return false
        }

        var hooked = 0
        for (m in cls.declaredMethods) {
            if (m.name != "onBindViewHolder") continue
            // The 2-argument form is the one RecyclerView itself calls on the adapter. The 3-argument
            // form is also taken when present, since a subclass may route payload updates through it.
            if (m.parameterCount != 2 && m.parameterCount != 3) continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        // Runs on the UI thread inside EVERY MultiType bind in the app -- chat scrolling
                        // included. A throw here would break the host's rendering, so nothing escapes.
                        try {
                            universalBinds++
                            // arg[1] is the position for the 2-arg form. The 3-arg form's arg[1] is the
                            // item itself, so both are covered: `itemAt` returns null for a non-integer
                            // and the argument is then used directly.
                            val arg1 = chain.args.getOrNull(1)
                            val raw = when {
                                arg1 is Int -> itemAt(chain.thisObject, arg1)
                                arg1 != null && access.isMessage(arg1) -> arg1
                                else -> null
                            }
                            // ============================== UNWRAP FIRST, JUDGE AFTER ==============================
                            //
                            // MEASURED on the device, and this pair of lines is the whole bug:
                            //
                            //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
                            //     live: rejected a blank-text message for 625ed046000000001000c61b
                            //
                            // `ql4.a` IS the chat row, so the hook sees every message the user scrolls past --
                            // but it is a WRAPPER, and the `MsgUIData` with `strMsg` inside it was never
                            // reached, so the recorder got a textless object and dropped it.
                            //
                            // The wrapper cannot be judged directly: a tight `isMessage` refuses it (which is
                            // what silently stopped every capture earlier), and a loose one accepts menu
                            // entries (`ly7.c`) as chat content. [XiaohongshuMsgAccess.resolve] settles the
                            // order -- unwrap, THEN judge what was unwrapped -- and is the ONLY entry point a
                            // hook should use.
                            val item = access.resolve(raw)
                            if (item == null) {
                                // Not a message row -- normal for most of the app's lists (feed cards,
                                // settings, contacts). Reported once per class so the log proves the hook
                                // is alive and shows what the app binds, without a line per scrolled row.
                                val name = raw?.javaClass?.name ?: arg1?.javaClass?.name ?: "null"
                                note(
                                    "universal-non-msg:" + name,
                                    "xhs: the universal MultiType hook is alive (bind #" + universalBinds +
                                        "); this row is " + name + ", not a message",
                                )
                                return@intercept
                            }
                            if (universalFirst) {
                                universalFirst = false
                                Log.i(
                                    TAG,
                                    "xhs: UNIVERSAL hook captured a message -- " + item.javaClass.name +
                                        " text='" + (access.text(item) ?: "<no text>").take(30) + "'" +
                                        " (from MultiTypeAdapter#onBindViewHolder)",
                                )
                            }
                            stage(item)
                        } catch (t: Throwable) {
                            note(
                                "universal-threw:" + t.javaClass.simpleName,
                                "xhs: the universal MultiType hook threw and was swallowed: " + t.message,
                            )
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "xhs: hooked the UNIVERSAL feed -- " + cls.name + "#onBindViewHolder/" +
                        m.parameterCount + "args. Every MultiType list row in this app (the chat message " +
                        "list included) now feeds the history recorder.",
                )
            } catch (t: Throwable) {
                note("universal-hook-failed", "xhs: hooking " + cls.name + "#onBindViewHolder failed: ${t.message}")
            }
        }
        if (hooked == 0) {
            note(
                "universal-no-method",
                "xhs: " + cls.name + " declares no onBindViewHolder/2or3-arg method. Declared: " +
                    describeMethods(cls),
            )
        }
        return hooked > 0
    }

    /**
     * The item at [position] in a MultiType adapter's data list, or null.
     *
     * `[MEASURED]` from the app's own view tree: the adapter is
     * `com.drakeet.multitype.MultiTypeAdapter` and its items live in a `List` field named `d`
     * (`.d : List size=6 first=ly7.c`). That name is OBFUSCATED, so it is not used directly -- the list
     * is found by TYPE instead, walking the superclass chain so a subclass that keeps the items in a
     * parent is still read. A `setItems`/`setList` style accessor would be a setter, which is why the
     * measured methods were `F0()` and `Q0(List)` and why fields are the route.
     */
    private fun itemAt(adapter: Any?, position: Int): Any? {
        if (adapter == null || position < 0) return null
        val list = adapterItems(adapter) ?: return null
        return list.getOrNull(position)
    }

    /**
     * Hooks MultiType's `ItemViewBinder.onBindViewHolder(holder, item, payloads)` -- the one method
     * every list row in this app passes through.
     *
     * KEPT AS A FALLBACK: on the measured build this installs nothing, because `da.c` declares only
     * `onCreateViewHolder` (see [installMultiTypeAdapterHook] for the log line and the reason --
     * `onBindViewHolder` is abstract in that base). It is retained because the check is cheap and a
     * future release could move the payload form there.
     *
     * @return true when the hook was installed.
     */
    private fun installUniversalBinderHook(): Boolean {
        val cls = try {
            Class.forName(MULTITYPE_ITEM_BINDER, false, resolver.classLoader())
        } catch (_: Throwable) {
            null
        }
        if (cls == null) {
            note(
                "no-multitype-base",
                "xhs: " + MULTITYPE_ITEM_BINDER + " is not loadable, so the universal binder hook is " +
                    "unavailable and the history feed falls back to the adapter routes below. That base " +
                    "class is what every list row in this app binds through -- see the class table in " +
                    "XiaohongshuHistoryHooks.install.",
            )
            return false
        }

        var hooked = 0
        for (m in cls.declaredMethods) {
            if (m.name != "onBindViewHolder") continue
            // ONLY the payload overload. The 2-argument one is ABSTRACT here (each binder implements
            // it), so hooking it on this class would install a hook that nothing ever calls.
            if (m.parameterCount != 3) continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        // Runs on the UI thread inside EVERY list bind in the app. A throw here would
                        // break the host's rendering, so nothing may escape.
                        try {
                            universalBinds++
                            // arg[1] is the item. Read positionally because this signature is fixed by
                            // MultiType itself (it is a library base, not host code), and scanned
                            // defensively so an unexpected argument cannot be mistaken for the item.
                            val item = chain.args.getOrNull(1)
                            // UNWRAP FIRST, THEN JUDGE. MEASURED: the chat list's rows arrive here as
                            // `ql4.a`, a WRAPPER holding the real `MsgUIData` --
                            //
                            //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
                            //
                            // Testing the raw argument rejects it (no text of its own), which is why an
                            // earlier tightening of `isMessage` silently stopped ALL chat captures while
                            // the hook kept firing. `resolve` unwraps first and judges the result, so a
                            // wrapper is accepted and a menu entry (`ly7.c`, which holds no message) is
                            // still refused.
                            val msg = access.resolve(item)
                            if (msg == null) {
                                // Not a message row: normal for most of the app's lists. Reported once
                                // -- with the class -- so the log proves the hook is alive and shows what
                                // the app actually binds, without printing one line per row scrolled.
                                val name = item?.javaClass?.name ?: "null"
                                note(
                                    "universal-non-msg:" + name,
                                    "xhs: the universal binder is alive (bind #" + universalBinds + "); " +
                                        "this row's item is " + name + ", not a message",
                                )
                            }
                            if (msg != null) {
                                if (universalFirst) {
                                    universalFirst = false
                                    Log.i(
                                        TAG,
                                        "xhs: UNIVERSAL binder captured a message -- " +
                                            msg.javaClass.name + " text='" +
                                            (access.text(msg) ?: "<no text>").take(24) + "'",
                                    )
                                }
                                // ============================== CAPTURED BUT TEXTLESS ==============================
                                //
                                // MEASURED on the device, and this line is what located the chat message:
                                //
                                //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
                                //
                                // Dumped once per class: the graph names every field and its RUNTIME type,
                                // which is the only way to find a field whose name this reader cannot guess
                                // (`strMsg` was already a surprise on `MsgUIData`).
                                if (access.text(msg) == null) {
                                    access.dumpGraph(msg, "chat message with no readable text")
                                }
                                stage(msg)
                            }
                        } catch (t: Throwable) {
                            note(
                                "universal-threw:" + t.javaClass.simpleName,
                                "xhs: the universal binder hook threw and was swallowed: " + t.message,
                            )
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "xhs: hooked the UNIVERSAL binder -- " + cls.name +
                        "#onBindViewHolder/3args. Every list row in this app (chat messages included) " +
                        "now passes its item to the history recorder.",
                )
            } catch (t: Throwable) {
                note("universal-hook-failed", "xhs: hooking " + cls.name + "#onBindViewHolder/3 failed: ${t.message}")
            }
        }
        if (hooked == 0) {
            note(
                "universal-no-method",
                "xhs: " + cls.name + " declares no onBindViewHolder/3-arg method. Declared: " +
                    describeMethods(cls),
            )
        }
        return hooked > 0
    }

    // ------------------------------------------------------------------ route 1: adapter bind

    /**
     * Hooks `onBindViewHolder` on the message adapter, once its concrete class is known.
     *
     * Resolved from the VIEW TREE rather than from a class name, and that is the whole point: the
     * adapter a `RecyclerView` uses is set at runtime and its class is a build implementation detail.
     * `RecyclerView#getAdapter()` returns it without any name being guessed -- the same technique
     * `ChatListHook.startAdapterWatchdog` uses on WeChat for the same reason (its `setAdapter` hook
     * never fired on 8.0.77, and the view-tree route is what made that host version-proof).
     */
    private fun tryDiscoverAdapter(): Boolean {
        val rv = findMessageRecyclerView() ?: return false
        val adapter = try {
            (rv.javaClass.getMethod("getAdapter").invoke(rv))
        } catch (_: Throwable) {
            null
        } ?: return false

        val cls = adapter.javaClass
        // ============================== HOOK THE SUBCLASS, NOT THE LIBRARY BASE ==============================
        //
        // THE MEASUREMENT THAT FORCED THIS: hooking `com.drakeet.multitype.MultiTypeAdapter` directly
        // DOES work -- the device log proves it fires, over and over:
        //
        //     xhs: the universal MultiType hook is alive (bind #1); this row is NewTabLayout$e
        //     xhs: the universal MultiType hook is alive (bind #8); this row is tna.b
        //     ...and the menu's six rows all appear there too: ly7.c, ly7.i, ly7.t, ly7.s, ly7.b0, ly7.f
        //
        // ...but NOT ONE CHAT MESSAGE EVER DID. The chat's rows never reach the base class's
        // implementation, for the same reason `da.c` had no `onBindViewHolder` to hook: the chat adapter
        // is a SUBCLASS that OVERRIDES the method, so the base's body is never executed for its rows.
        //
        // That is why a `MultiTypeAdapter`-only hook captures the MENU (whose adapter is the plain class,
        // measured as `adapter=com.drakeet.multitype.MultiTypeAdapter itemCount=6`) but never the CHAT
        // (whose adapter overrides the bind).
        //
        // SO THE HOOK GOES ON THE RUNTIME CLASS. `getAdapter()` returns the object the RecyclerView is
        // actually using, and `adapter.javaClass` is that subclass -- discovered, never guessed, so this
        // works whatever the host renamed it to. Hooked on ITS `onBindViewHolder`, the interception
        // happens before the override can bypass it.
        //
        // BOTH overloads of the SUBCLASS are hooked when it declares them, and the base's are still hooked
        // by [installMultiTypeAdapterHook] for the lists that do not override.
        val onBind = cls.declaredMethods.firstOrNull { it.name == "onBindViewHolder" } ?: run {
            note(
                "no-onbind:" + cls.name,
                "xhs: the message adapter " + cls.name + " declares no onBindViewHolder, so route 1 cannot " +
                    "bind it. Declared: " + describeMethods(cls),
            )
            return false
        }

        // Install on EVERY declared onBindViewHolder of this class, not just the first found.
        var installed = 0
        for (m in cls.declaredMethods) {
            if (m.name != "onBindViewHolder") continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        try {
                            // The item: for the 2-arg form `(ViewHolder, position)` it must be READ OFF THE
                            // ADAPTER, because the signature does not carry it -- that is the whole reason
                            // [itemAt] exists and the reason a plain argument scan found nothing here. The
                            // 3-arg form carries it directly.
                            val arg1 = chain.args.getOrNull(1)
                            val item = when {
                                arg1 is Int -> itemAt(chain.thisObject, arg1)
                                arg1 != null && access.isMessage(arg1) -> arg1
                                else -> null
                            }
                            val msg = if (item != null && access.isMessage(item)) item else null
                            if (msg != null) {
                                if (adapterFirst) {
                                    adapterFirst = false
                                    Log.i(
                                        TAG,
                                        "xhs: the message adapter captured a message -- " +
                                            msg.javaClass.name + " text='" +
                                            (access.text(msg) ?: "<no text>").take(30) + "' (from " +
                                            cls.simpleName + "#onBindViewHolder/" + m.parameterCount + ")",
                                    )
                                }
                                stage(msg)
                            } else {
                                // Not a message: report the first few rows ONCE per adapter class, so the
                                // log shows what the chat list actually binds when it is not a message.
                                note(
                                    "adapter-row:" + cls.name + ":" + m.parameterCount + ":" +
                                        (item?.javaClass?.name ?: arg1?.javaClass?.name ?: "null"),
                                    "xhs: " + cls.simpleName + "#onBindViewHolder/" + m.parameterCount +
                                        " bound a row that is not a message: " +
                                        (item?.javaClass?.name ?: arg1?.javaClass?.name ?: "null"),
                                )
                            }
                        } catch (t: Throwable) {
                            note(
                                "adapter-bind-threw:" + t.javaClass.simpleName,
                                "xhs: the message-adapter bind hook threw and was swallowed: " + t.message,
                            )
                        }
                    }
                installed++
                Log.i(
                    TAG,
                    "xhs: patched the LIVE message adapter's bind -- " + cls.name +
                        "#onBindViewHolder/" + m.parameterCount + "args (discovered from the view tree, " +
                        "so the name is whatever the host uses)",
                )
            } catch (t: Throwable) {
                note("adapter-hook-failed:" + cls.name, "xhs: hooking " + cls.name + "#onBindViewHolder failed: ${t.message}")
            }
        }

        return if (installed > 0) {
            adapterHooked = cls
            Log.i(
                TAG,
                "xhs: adapter hooked -- " + cls.name + " (" + installed + " bind method(s))",
            )
            true
        } else {
            false
        }
    }

    /**
     * The FIRST-run version of the adapter hook, kept for the class-name fallback.
     *
     * Retained because [tryDiscoverAdapter] needs a message `RecyclerView` to exist; this variant takes a
     * class directly, which is what a build that exposes a known adapter class would use.
     */
    private fun hookAdapterClass(cls: Class<*>): Boolean {
        val onBind = findOnBind(cls) ?: run {
            note(
                "no-onbind:" + cls.name,
                "xhs: the message adapter is " + cls.name + " but it declares no suitable " +
                    "onBindViewHolder method, so route 1 cannot bind. Declared: " +
                    describeMethods(cls),
            )
            return false
        }

        return try {
            LegacyHook.hook(onBind)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    // Runs on the UI thread inside the host's bind. A throw here would take the host
                    // down mid-scroll, so it must never escape.
                    try {
                        onBound(chain.args)
                    } catch (t: Throwable) {
                        note(
                            "bind-threw:" + t.javaClass.simpleName,
                            "xhs: the adapter-bind hook threw and was swallowed: " + t.message,
                        )
                    }
                }
            adapterHooked = cls
            Log.i(
                TAG,
                "xhs: adapter hooked -- " + cls.name + "#" + onBind.name +
                    " modifiers=[" + Modifier.toString(onBind.modifiers) + "] params=" + onBind.parameterCount,
            )
            true
        } catch (t: Throwable) {
            note("adapter-hook-failed:" + cls.name, "xhs: hooking " + cls.name + "#" + onBind.name + " failed: ${t.message}")
            false
        }
    }

    /**
     * True when [m] is an `onBindViewHolder` this feeder can use.
     *
     * ACCEPTS BOTH OVERLOADS, and that is the point of the shape test rather than a name lookup:
     *
     *   * `onBindViewHolder(VH, int)`          -- the original API;
     *   * `onBindViewHolder(VH, int, List)`    -- the payload overload, present since API 19 and the
     *     one a modern RecyclerView actually calls when a partial update arrives.
     *
     * Xiaohongshu's rows are updated incrementally (an IM list re-binds rows as new messages arrive),
     * so hooking only the 2-argument form would MISS most binds -- the failure would look like
     * "history only gets the first screenful", which is exactly the class of bug this feeder exists to
     * prevent. Both are hooked; the `staged` set deduplicates the pair.
     */
    private fun findOnBind(cls: Class<*>): Method? {
        // A BLOCK body, not `= try { ... }`: this search walks the superclass chain and RETURNS from
        // inside the loop, and Kotlin forbids `return` in a function written with an expression body.
        try {
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                val hit = c.declaredMethods.firstOrNull { m ->
                    XiaohongshuHistoryLogic.isBindMethod(
                        name = m.name,
                        paramTypes = m.parameterTypes.toList(),
                        returnType = m.returnType,
                    )
                }
                if (hit != null) {
                    hit.isAccessible = true
                    return hit
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
            // An unreadable class is reported as "no bind found" by the caller.
        }
        return null
    }

    /**
     * A one-line, de-duplicated list of a class's declared FIELDS, for the "no readable item list" log.
     *
     * Exists beside [describeMethods] because the two failures are different in kind and the log has to
     * tell them apart: "no accessor" sends the reader to the field scan, while "no field either" means
     * the data is genuinely somewhere else (a wrapper's inner object). The device log that led to the
     * drill-down read `Methods: F0(), Q0(List)` -- a getter with no usable return and a LIST SETTER --
     * which alone was not enough to know whether a list field existed.
     *
     * Superclass fields are included because the measured adapter keeps its items in a base class.
     */
    private fun describeFields(cls: Class<*>): String {
        val out = ArrayList<String>(16)
        var c: Class<*>? = cls
        var hops = 0
        while (c != null && c != Any::class.java && hops < 4 && out.size < 24) {
            try {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val label = if (c == cls) "" else c.simpleName + "."
                    out.add(label + f.name + ":" + f.type.simpleName)
                }
            } catch (_: Throwable) {
                // skip
            }
            c = c.superclass
            hops++
        }
        return if (out.isEmpty()) "<none>" else out.joinToString(", ")
    }

    /** Called by the adapter hook: stage whichever argument is a message. */
    private fun onBound(args: Array<Any?>) {
        var hit = false
        for (a in args) {
            // ============================== WHY THE HOLDER IS SEARCHED, NOT JUST THE ARGS ==============
            //
            // This adapter's `onBindViewHolder` takes only TWO parameters -- measured on the device:
            //
            //     xhs: adapter hooked -- com.xingin.im.utils.diff.IMDiffMultiTypeAdapter#onBindViewHolder
            //          modifiers=[public] params=2
            //
            // Two parameters means `(ViewHolder, position)`: there is NO message in this signature at
            // all. The previous version scanned the arguments only, so it found nothing on every bind
            // and the live history stayed empty while the install log looked healthy -- exactly the
            // failure the user reported (「循环写入本地聊天记录没有用」).
            //
            // `IMDiffMultiTypeAdapter` is a GENERIC diffing adapter: it delegates the real bind to
            // MultiType's per-item `ItemViewBinder`, and THAT binder is the class handed the message.
            // The message is therefore reachable through the ViewHolder the adapter passes in, so the
            // holder is searched for a host model. [XiaohongshuMsgAccess.messageOnHost] does that by
            // TYPE, which is what makes it work through an obfuscated field name.
            if (access.isMessage(a)) {
                stage(a)
                hit = true
                continue
            }
            // A ViewHolder (or any object) that HOLDS the message.
            val held = access.messageOnHost(a)
            if (held != null) {
                stage(held)
                hit = true
            }
        }

        // ============================== THE ONE-SHOT DIAGNOSTIC ==============================
        //
        // If a bind produced no message, the next question is "what WAS in the arguments" -- and that
        // cannot be answered from the module's side without printing it. Dumped ONCE per argument shape
        // (the key is the argument classes), so a busy chat does not flood the log, while the shape that
        // matters appears if the holder route above ever stops working.
        //
        // This is deliberately kept: the holder route depends on the host parking its message on the
        // ViewHolder, and a release that stops doing so would otherwise fail silently again.
        if (!hit && args.isNotEmpty()) {
            note(
                "bind-shape:" + args.joinToString(",") { it?.javaClass?.name ?: "null" },
                "xhs: a message-list bind produced no message. Arguments were: " +
                    args.joinToString(" | ") { it?.javaClass?.name ?: "null" } +
                    ". If this is the ONLY such line, the holder search found the message on later " +
                    "binds; if it repeats for every bind, the message is not on the ViewHolder and this " +
                    "shape is what has to be read instead.",
            )
            args.firstOrNull()?.let { access.dumpGraph(it, "message-list ViewHolder (no message found)") }
        }
        if (hit) emitted++
    }

    // ------------------------------------------------------------------ route 2: the text view

    /**
     * Hooks `TextView#setText` only as far as needed to recover a message's TEXT from its row.
     *
     * WHY THIS IS A WEAKER ROUTE, stated plainly: it recovers TEXT but not necessarily a message
     * OBJECT, so the record it produces may lack an id and a timestamp. It exists because "history with
     * text but no id" is far more useful to the user than "no history", and the store tolerates a
     * missing id by falling back to a content hash.
     *
     * It does NOT hook `TextView#setText` globally -- that would run on every text in the app. It is
     * restricted to the message text view class, measured on-device.
     */
    private fun installTextViewHook(): Boolean {
        val cls = loadClass(XiaohongshuMenuIdentity.MESSAGE_TEXT_VIEW) ?: run {
            note(
                "no-text-view",
                "xhs: " + XiaohongshuMenuIdentity.MESSAGE_TEXT_VIEW + " is not loadable, so the " +
                    "text-view fallback feeder is unavailable",
            )
            return false
        }
        val setText = try {
            cls.declaredMethods.firstOrNull { m ->
                m.name == "setText" && m.parameterCount == 1 &&
                    CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])
            }
        } catch (_: Throwable) {
            null
        } ?: run {
            note("no-settext", "xhs: " + cls.name + " declares no setText(CharSequence), so the text-view fallback is unavailable")
            return false
        }

        return try {
            LegacyHook.hook(setText)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        val text = chain.getArg(0)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            // Recover the adapter's message object from the row when possible: the
                            // text view's own row tag is set by the adapter on most IM builds, and
                            // that is what turns a text-only record into a full one.
                            //
                            // `as? View` because a hook's `thisObject` is typed `Any` -- the class was
                            // resolved by NAME at runtime, so the compiler cannot know it is a View
                            // even though the method was looked up on a TextView subclass. The cast
                            // failing is handled, not assumed away.
                            val msg = messageFromRow(chain.thisObject as? View)
                            if (msg != null) {
                                stage(msg)
                            } else if (text.length > 1) {
                                note(
                                    "text-only-once",
                                    "xhs: the text-view fallback is recording TEXT WITHOUT a message " +
                                        "object (id/time will be unavailable for these rows). Text " +
                                        "seen: '" + text.take(30) + "'",
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        note(
                            "settext-threw:" + t.javaClass.simpleName,
                            "xhs: the text-view hook threw and was swallowed: " + t.message,
                        )
                    }
                }
            Log.i(TAG, "xhs: text-view fallback hooked -- " + cls.name + "#setText")
            true
        } catch (t: Throwable) {
            note("settext-hook-failed", "xhs: hooking " + cls.name + "#setText failed: ${t.message}")
            false
        }
    }

    /**
     * Tries to recover a message object from a row view.
     *
     * Walks up to 8 parents looking for a `getItem`/`getData`/`getMessage`-style accessor or a
     * field holding a message, and returns the first thing [XiaohongshuMsgAccess.isMessage] accepts.
     * Bounded because a view tree can be deep and this runs on every text set.
     */
    private fun messageFromRow(v: View?): Any? {
        if (v == null) return null
        var cur: View? = v
        var hops = 0
        while (cur != null && hops < 8) {
            hops++
            // A ViewHolder is often the view's tag; ask it first, since that is the documented place
            // an adapter parks its item.
            try {
                val tag = cur.tag
                if (tag != null) {
                    foundIn(tag)?.let { return it }
                }
            } catch (_: Throwable) {
                // tag unreadable; keep walking
            }
            foundIn(cur)?.let { return it }
            cur = (cur.parent as? View)
        }
        return null
    }

    /** The first message-ish value reachable from [o] through a short list of accessors. */
    private fun foundIn(o: Any): Any? {
        if (access.isMessage(o)) return o
        for (n in ROW_ACCESSORS) {
            try {
                val m = o.javaClass.methods.firstOrNull { it.name == n && it.parameterCount == 0 }
                if (m != null) {
                    val v = m.invoke(o)
                    if (access.isMessage(v)) return v
                }
            } catch (_: Throwable) {
                // try the next accessor
            }
        }
        return null
    }

    // ------------------------------------------------------------------ watchdog

    /**
     * Keeps trying to find the message adapter, then SWEEPS its data list on a timer.
     *
     * ============================== WHY A SWEEP, NOT ONLY A BIND HOOK ==============================
     *
     * The bind hook alone is not reliable on this host, measured on the device:
     *
     *     17:04:59.857  xhs: adapter hooked -- ...IMDiffMultiTypeAdapter#onBindViewHolder params=2
     *     ... app restarted, and in the NEW process that line NEVER APPEARS AGAIN, even while the user
     *     scrolled a conversation for minutes:
     *     17:05:11.337  xhs: live history feed install -- adapterFromTree=false textViewFallback=false
     *
     * So the hook succeeds in one process and not the next. Two reasons, both visible in those lines:
     *
     *   * `tryDiscoverAdapter` needs the message `RecyclerView` to ALREADY EXIST when it runs. At
     *     startup it does not (the user is not in a chat yet), and `findMessageRecyclerView` walks the
     *     framework's LIVE root-view list, which the watchdog re-reads every 500ms -- so it eventually
     *     finds it, but only once the user has actually entered a conversation;
     *   * more important, the adapter is REPLACED when the conversation changes (a per-chat adapter), so
     *     a hook bound to the first instance stops mattering.
     *
     * THE SWEEP MAKES THE FEATURE INDEPENDENT OF BOTH. It re-reads whatever adapter is live right now,
     * pulls its DATA LIST, and stages every message in it. That is the same strategy the WeChat reader
     * uses for `adapterMessages` (`ChatContext`), and it needs no hook to survive an adapter swap: a new
     * adapter is simply read on the next tick.
     *
     * The bind hook is STILL installed, because it is cheaper and catches a message the moment it is
     * bound. The two are complementary and both funnel into [stage], which de-duplicates by identity.
     *
     * WHY 2 SECONDS: it matches the writer's own period (`AnalysisController.startLiveTimer`, 2000ms),
     * so the sweep never produces items faster than they can be flushed. A chat page holds only the
     * visible rows plus a small window, so each tick reads a few dozen objects at most.
     */
    private fun startAdapterWatchdog() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val run = object : Runnable {
            @Volatile var tries = 0
            override fun run() {
                tries++
                if (tries > 7200) return       // ~60 min at 500ms, then stop for good
                try {
                    // Install the bind hook the first time an adapter is reachable. This keeps the
                    // cheap, immediate route; the sweep below is what guarantees the history grows.
                    if (adapterHooked == null) tryDiscoverAdapter()
                    // ALWAYS sweep, whether or not the hook is installed.
                    sweepAdapterList()
                } catch (_: Throwable) {
                    // never let the watchdog take the host down
                }
                h.postDelayed(this, 2000L)
            }
        }
        h.postDelayed(run, 500L)
    }

    /**
     * Reads the live message adapter's DATA LIST and stages every message in it.
     *
     * ============================== WHY THIS IS THE RELIABLE ROUTE ==============================
     *
     * A `RecyclerView` adapter always exposes its items, and this host's chat adapter was measured in
     * the view tree as:
     *
     *     >> RECYCLERVIEW ChatPageRecyclerView adapter=com.xingin.im.ui.adapter.multi.adapter.ChatPageMultiAdapter itemCount=15
     *
     * with 15 messages live at that moment. Reading those 15 objects needs no hook at all, and because
     * the sweep re-reads on a timer it also survives the adapter being REPLACED when the conversation
     * changes -- which is what made the hook-only version work in one process and not the next.
     *
     * `getItems()` / `getData()` / `getList()` are tried by name, then a direct field scan for a
     * `List`-typed field, and finally MultiType's own `d` field (the name measured on the MENU adapter,
     * `com.drakeet.multitype.MultiTypeAdapter`, whose `.d : List size=6` held its items). Every one of
     * those is a guess about a NAME, which is why the field scan runs too: it needs no name.
     */
    private fun sweepAdapterList() {
        // ============================== EVERY CANDIDATE IS TRIED, NOT JUST THE FIRST ==============
        //
        // The view tree was measured with TWO different message list adapters at different moments:
        //
        //     >> RECYCLERVIEW ChatPageRecyclerView adapter=...ChatPageMultiAdapter itemCount=15
        //     xhs: adapter hooked -- com.xingin.im.utils.diff.IMDiffMultiTypeAdapter#onBindViewHolder
        //
        // `ChatPageMultiAdapter` is the real per-conversation adapter (15 messages live), while
        // `IMDiffMultiTypeAdapter` is the diffing base the host also exposes during loading -- and it
        // was the one the first-match search returned, which is why the sweep reported "no readable item
        // list" while a perfectly readable list of 15 messages existed elsewhere in the same tree.
        //
        // So each candidate `RecyclerView` is tried until one yields items. Cheap: the search returns as
        // soon as items are found, and a tree has only a handful of matching views.
        val candidates = ArrayList<View>(4)
        collectMessageRecyclerViews(candidates)
        // ============================== FALLBACK: EVERY RecyclerView, WHEN NONE MATCHED BY NAME ======
        //
        // The name filter above (`ChatPageRecyclerView`, `MessageRecyclerView`) was measured in the view
        // tree once, but this host renames and re-nests its list views between screens and versions, and
        // a name that stops matching fails SILENTLY -- the sweep simply finds nothing while the chat page
        // sits there with 15 messages loaded. That is the failure this whole feature already suffered
        // once on this host, so the name is treated as a fast path rather than as the only path.
        //
        // The fallback is safe because the items are still vetted: only an object that
        // [XiaohongshuMsgAccess.isMessage] accepts AND that carries readable message data is staged, so a
        // settings list or a contact list yields nothing rather than polluting the history.
        if (candidates.isEmpty()) collectAnyRecyclerViews(candidates)
        if (candidates.isEmpty()) return

        var reported = 0
        for (rv in candidates) {
            val adapter = try {
                rv.javaClass.getMethod("getAdapter").invoke(rv)
            } catch (_: Throwable) {
                null
            } ?: continue

            val items = adapterItems(adapter)
            if (items == null) {
                // Reported ONCE per adapter class, so a tree with several unreachable list views cannot
                // flood the log while still naming every class that was tried.
                note(
                    "no-items:" + adapter.javaClass.name,
                    "xhs: the adapter " + adapter.javaClass.name + " (" + rv.javaClass.simpleName +
                        ") exposes no readable item list. Methods: " + describeMethods(adapter.javaClass) +
                        " Fields: " + describeFields(adapter.javaClass),
                )
                continue
            }

            for (raw in items) {
                // The list holds WRAPPERS on this host (the menu adapter's items were `ly7.*` objects
                // whose payload sits one hop down), so unwrap before judging -- but only accept
                // something that is actually a message, so a diff wrapper or a header cannot be staged
                // as chat content.
                if (!access.isMessage(raw) && !access.hasMessageData(raw)) continue
                val msg = access.unwrap(raw) ?: continue
                if (!access.hasMessageData(msg)) continue
                // Textless messages are skipped by the controller's own gate, but skipping here avoids
                // staging an image message on every tick.
                if (access.text(msg) == null) continue
                stage(msg)
                reported++
            }

            if (reported > 0 && !sweepLogged) {
                sweepLogged = true
                Log.i(
                    TAG,
                    "xhs: history sweep found " + reported + " message(s) in " + adapter.javaClass.name +
                        " (itemCount=" + items.size + ") -- live history is now fed without pressing 分析",
                )
            }
            if (reported > 0) return
        }
    }

    /** Collects EVERY `RecyclerView` in every window -- the name-independent fallback for the sweep. */
    private fun collectAnyRecyclerViews(out: MutableList<View>) {
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal", false, resolver.classLoader())
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            val views = (f.get(inst) as? List<*>) ?: return
            for (v in views) {
                val view = v as? View ?: continue
                collectIfRecycler(view, 0, out)
            }
        } catch (_: Throwable) {
            // no readable window list
        }
    }

    /** Appends [v] when it IS a `RecyclerView`, recursing through children either way. */
    private fun collectIfRecycler(v: View, depth: Int, out: MutableList<View>) {
        if (depth > 24) return
        if (v.javaClass.name.startsWith("androidx.recyclerview.widget.")) out.add(v)
        val g = v as? android.view.ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            collectIfRecycler(c, depth + 1, out)
        }
    }

    /** Collects every message-list `RecyclerView` in every window, bounded and de-duplicated. */
    private fun collectMessageRecyclerViews(out: MutableList<View>) {
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal", false, resolver.classLoader())
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            val views = (f.get(inst) as? List<*>) ?: return
            for (v in views) {
                val view = v as? View ?: continue
                collectRecyclerViews(view, 0, out)
            }
        } catch (_: Throwable) {
            // No root views readable: reported as "not found" by the caller.
        }
    }

    /**
     * The adapter's item list, or null when none is readable.
     *
     * ============================== WHAT THE DEVICE SAID, AND WHY THIS DRILLS DOWN ==============
     *
     * The first version looked for the list on the adapter itself and failed with a log line that
     * named the exact obstacle:
     *
     *     xhs: the message adapter com.xingin.im.utils.diff.IMDiffMultiTypeAdapter exposes no
     *          readable item list... Methods: F0(), Q0(List)
     *
     * Two facts are in that line, and both change the search:
     *
     *   * The only two methods are `F0()` -- a no-arg that returns something which is NOT a non-empty
     *     `List` -- and `Q0(List)`, which TAKES a list and is therefore a setter. There is no `getItems`
     *     style accessor at all, so no name list can ever find the data.
     *   * The class is a DIFF adapter (`IMDiffMultiTypeAdapter extends ... MultitypeAdapter`), i.e. a
     *     WRAPPER: it holds a diff result and delegates the real items to an inner MultiType adapter.
     *     The items are one object down, not on this instance.
     *
     * So the search is now a DRILL-DOWN rather than a name list:
     *
     *   1. an explicit accessor, when one exists (unchanged -- cheap and exact);
     *   2. any `List`-typed field, superclass chain included;
     *   3. RECURSE into a host-model-typed field that itself holds a list -- which is what finds the
     *      inner `MultiTypeAdapter`, and it is reached by TYPE rather than by the field's name.
     *
     * Depth is bounded (3 levels) because this runs on the UI thread every 2s, and each level is a cheap
     * field read. The first non-empty list wins, so the common case stays a single reflection walk.
     */
    private fun adapterItems(adapter: Any): List<*>? {
        return adapterItemsAt(adapter, 0)
    }

    private fun adapterItemsAt(o: Any, depth: Int): List<*>? {
        if (depth > 3) return null

        // 1. an explicit accessor
        for (n in listOf("getItems", "getData", "getList", "getCurrentList", "getMessageList")) {
            try {
                val m = o.javaClass.methods.firstOrNull { it.name == n && it.parameterCount == 0 }
                    ?: continue
                val v = m.invoke(o)
                if (v is List<*> && v.isNotEmpty()) return v
            } catch (_: Throwable) {
                // try the next name
            }
        }

        // No-arg accessors that return a LIST but are obfuscated (`F0()` above is the measured case):
        // tried before the field scan because a method is the intended way to ask. Restricted to a
        // declared `List` return type so a getter for something else cannot be invoked by accident.
        try {
            for (m in o.javaClass.methods) {
                if (m.parameterCount != 0) continue
                if (!List::class.java.isAssignableFrom(m.returnType)) continue
                val v = try {
                    m.invoke(o)
                } catch (_: Throwable) {
                    null
                }
                if (v is List<*> && v.isNotEmpty()) return v
            }
        } catch (_: Throwable) {
            // no callable list accessors
        }

        // 2. and 3. a direct list field, or a host-model field to recurse into.
        var c: Class<*>? = o.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 6) {
            try {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val t = f.type
                    val isList = List::class.java.isAssignableFrom(t)
                    // Skip primitives/Strings and the framework types a view-adjacent object holds.
                    if (!isList && (t.isPrimitive || t == String::class.java)) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(o)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (v is List<*>) {
                        if (v.isNotEmpty()) return v
                        continue
                    }
                    if (v is Collection<*> || v is Map<*, *>) continue
                    val n = v.javaClass.name
                    // Only dive into this host's own models. `com.`/`com.xingin.` cover the inner
                    // adapter and its item types; framework and module classes are skipped so the walk
                    // cannot wander into a View hierarchy or the module's own objects.
                    if (!n.startsWith("com.") && !n.startsWith("sk4.")) continue
                    adapterItemsAt(v, depth + 1)?.let { return it }
                }
            } catch (_: Throwable) {
                // skip this level
            }
            c = c.superclass
            hops++
        }
        return null
    }

    /** The live message `RecyclerView`, found by class name in the framework's root-view list. */
    private fun findMessageRecyclerView(): View? {
        // BLOCK body, not `= try { ... }`: the loop RETURNS the first hit, which an expression body
        // forbids.
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal", false, resolver.classLoader())
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            val views = (f.get(inst) as? List<*>) ?: emptyList<Any>()
            for (v in views) {
                val view = v as? View ?: continue
                val hit = findRecyclerView(view, 0)
                if (hit != null) return hit
            }
        } catch (_: Throwable) {
            // No root views readable: reported as "not found" by the caller.
        }
        return null
    }

    /** Depth-bounded search for a message `RecyclerView` under [v]. */
    private fun findRecyclerView(v: View, depth: Int): View? {
        val out = ArrayList<View>(2)
        collectRecyclerViews(v, depth, out)
        return out.firstOrNull()
    }

    /**
     * Appends every message-list `RecyclerView` found under [v], instead of stopping at the first.
     *
     * WHY ALL OF THEM: the same window can hold more than one message-list adapter -- the device was
     * measured with `ChatPageMultiAdapter` (15 messages) AND `IMDiffMultiTypeAdapter` (whose items are
     * not directly readable) in the same chat page. Returning only the first made the history sweep read
     * the unusable one, so it found nothing while the readable list sat right next to it.
     */
    private fun collectRecyclerViews(v: View, depth: Int, out: MutableList<View>) {
        if (depth > 24) return
        val g = v as? android.view.ViewGroup
        if (g != null) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i) ?: continue
                collectRecyclerViews(c, depth + 1, out)
            }
        }
        val n = v.javaClass.name
        if (n == XiaohongshuMenuIdentity.MESSAGE_LIST ||
            n.contains("ChatPageRecyclerView") ||
            n.contains("MessageRecyclerView")
        ) {
            // The DECLARED class is checked because the message-list view class is what identifies it --
            // an adapter is not enough, since nearly every list in the app has one.
            out.add(v)
        }
    }

    // ------------------------------------------------------------------ staging

    /**
     * Hands one message to the existing pipeline.
     *
     * The `staged` set is keyed by IDENTITY, not by id: a message whose id could not be read (the
     * text-view route) would otherwise be staged on every scroll frame, and identity is exact for the
     * case that matters -- the SAME object re-bound.
     */
    private fun stage(msg: Any?) {
        if (msg == null) return
        // ============================== RESOLVE, BUT NEVER UNDO A GOOD RESULT ==============================
        //
        // MEASURED, and this is the pair of lines that shows the bug:
        //
        //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
        //     live: rejected a blank-text message for 625ed046000000001000c61b
        //
        // The caller (the universal hook) has ALREADY run `resolve`, which unwraps a chat row's wrapper
        // and returns the `MsgUIData` holding `strMsg`. Re-unwrapping here would search again and, on a
        // wrapper that yields no payload, hand back the WRAPPER itself -- overwriting the good object with
        // the textless one. That is the same "good value clobbered by a later step" failure this module
        // has already hit twice, so the rule is explicit:
        //
        //   * a message that ALREADY has text is passed through untouched;
        //   * only a textless input is resolved further (an image message legitimately stays textless).
        val real = if (access.text(msg) != null) msg else (access.resolve(msg) ?: return)
        val body = access.text(real)
        if (!staged.add(real)) return
        try {
            controller.onLiveMessage(real)
            emitted++
            if (emitted == 1 || emitted % 200 == 0) {
                Log.i(
                    TAG,
                    "xhs: history feeder has staged " + emitted + " messages (latest " +
                        real.javaClass.name +
                        " text='" + (body ?: "").take(20) + "')",
                )
            }
        } catch (t: Throwable) {
            note("stage-threw:" + t.javaClass.simpleName, "xhs: staging a message threw: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, resolver.classLoader())
    } catch (_: Throwable) {
        null
    }

    private fun describeMethods(cls: Class<*>): String = try {
        cls.declaredMethods.joinToString(", ") { m ->
            m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
        }
    } catch (_: Throwable) {
        "<unreadable>"
    }

    private fun note(key: String, message: String) {
        if (logged.add(key)) Log.w(TAG, message)
    }

    @Volatile
    private var installed = false

    private companion object {
        const val TAG = "LuoLiao"

        /**
         * MultiType's `ItemViewBinder` base -- the common ancestor of EVERY list-row binder in this app.
         *
         * ============================== THIS IS THE DECISIVE CONSTANT ==============================
         *
         * Decoded from the APK's class table (`[DEX]`, dumping `class_def_item.superclass_idx` for every
         * class), every MultiType row binder in Xiaohongshu descends from this ONE class:
         *
         *     Lcom/xingin/im/ui/widgets/pressmenu/itembinder/LongPressMenuItemBinder; -> Lda/c;
         *     Lcom/xingin/im/ui/adapter/viewholder/IMShareTopicItemViewBinder;        -> Lda/c;
         *     Lcom/xingin/im/ui/adapter/GroupShortCutItemViewBinder;                  -> Lda/c;
         *     Lcom/xingin/im/v2/group/member/adapter/AddMemberItemViewBinder;         -> Lda/c;
         *     Lcom/xingin/comment/consumer/list/itembinder/topic/TopicItemViewBinder; -> Lda/c;
         *     ...and every other row binder in the app
         *
         * `da.c` is `com.drakeet.multitype.ItemViewBinder` after R8 renaming, confirmed by the library's
         * own entry point also surviving in the table:
         *
         *     Lcom/drakeet/multitype/MultiTypeAdapter;  ->  Landroidx/recyclerview/widget/RecyclerView$Adapter;
         *     Lcom/xingin/im/utils/diff/IMDiffMultiTypeAdapter; -> Lcom/drakeet/multitype/MultiTypeAdapter;
         *
         * Its 3-argument `onBindViewHolder(holder, item, payloads)` is the method `MultiTypeAdapter`
         * ALWAYS calls, and the item it passes is the row's data -- a chat message, when the row is a
         * message. So this single hook receives every message the app renders, on every screen, without
         * depending on an adapter class name, a view-tree shape or a data-list accessor.
         *
         * WHY THE OBfuscATED NAME IS ACCEPTABLE HERE, unlike everywhere else in this module: every other
         * obfuscated name was a guess about HOST code, which changes between releases. This one belongs to
         * a THIRD-PARTY LIBRARY whose renaming is stable within a build, and the constant is paired with
         * [MULTITYPE_ADAPTER] -- if a future release renames it, the install log names the class that
         * could not be loaded, and the adapter routes below still run.
         *
         * WHY NO `da.d`/OTHER CANDIDATES ARE TRIED: `da.c` was CONFIRMED as the parent of the menu-row
         * binder on THIS exact build (9.47.0), which is the binding this module runs against. Adding
         * speculative second names would risk hooking an unrelated `da.*` class and running on every bind
         * in the app for nothing.
         */
        const val MULTITYPE_ITEM_BINDER = "da.c"

        /** MultiType's adapter, logged only as corroboration that [MULTITYPE_ITEM_BINDER] is the library. */
        const val MULTITYPE_ADAPTER = "com.drakeet.multitype.MultiTypeAdapter"

        /**
         * Accessors tried when recovering a message object from a row view.
         *
         * These are VIEW-HOLDER accessor names, not message-model names, so they are the same words
         * on any host: an adapter parks its item behind one of them. Ordered by how common the name
         * is in Android IM code, and kept short because this runs per text set.
         */
        val ROW_ACCESSORS = listOf(
            "getItem", "getData", "getMessage", "getMsg", "getItemData",
            "getModel", "getMessageData", "getCurrentItem",
        )
    }
}

/**
 * The pure part of the history feeder: the rule that decides whether a method is a bind we want.
 *
 * Extracted so a JVM unit test can pin it, for the same reason [XiaohongshuMenuLogic] is: a
 * too-narrow rule silently misses binds (the symptom is "only the first screenful of history") and a
 * too-wide one hooks unrelated adapter methods.
 */
internal object XiaohongshuHistoryLogic {

    /**
     * True when a method named [name] with the given signature is an `onBindViewHolder` overload.
     *
     * ------------------------------ THE RULE ------------------------------
     * The name must be `onBindViewHolder`, the return type void, and the parameters must be
     * (holder, int) or (holder, int, List) -- i.e. 2 or 3 parameters where the FIRST is not a
     * primitive (a ViewHolder is always an object) and the SECOND is a primitive int (the position).
     *
     * WHY THE SHAPE AND NOT JUST THE NAME: `RecyclerView.Adapter` has exactly these two overloads, but
     * an obfuscated subclass may declare its own method that happens to be called `onBindViewHolder`,
     * and more importantly a future build may move the body to a differently-named method. The
     * positional test -- holder first, position second -- is what identifies the ROW BIND rather than
     * some other callback.
     *
     * WHY BOTH ARITIES: see [XiaohongshuHistoryHooks.findOnBind]. An IM list rebinds rows
     * incrementally, so the 3-argument payload overload is the one a modern RecyclerView calls most;
     * accepting only the 2-argument form is a real way to record merely the first screenful.
     */
    fun isBindMethod(name: String, paramTypes: List<Class<*>>, returnType: Class<*>): Boolean {
        if (name != "onBindViewHolder") return false
        if (returnType != Void.TYPE) return false
        if (paramTypes.size != 2 && paramTypes.size != 3) return false
        // arg 0: the ViewHolder. It must be an object -- a primitive here is not a holder.
        if (paramTypes[0].isPrimitive) return false
        // arg 1: the position. It must be an int.
        if (paramTypes[1] != Integer.TYPE) return false
        // arg 2, when present: the payload list.
        if (paramTypes.size == 3 && !List::class.java.isAssignableFrom(paramTypes[2])) return false
        return true
    }
}
