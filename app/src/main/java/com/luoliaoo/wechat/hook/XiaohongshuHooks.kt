package com.luoliaoo.wechat.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * The Xiaohongshu long-press menu entry: adds a 分析 row that starts an analysis of the message the
 * user long-pressed.
 *
 * ============================== WHAT THIS HOST NEEDS, AND WHAT IT DOES NOT ==============================
 *
 * The user asked for ONE thing here: 「加到菜单里」 (option A) -- no floating balloon entry, no
 * multi-select toolbar. Xiaohongshu has no multi-select at all (「小红书没有多选 所以无需适配」), so
 * NONE of QQ's multi-select machinery is reused: no row registry, no selection accessor, no toolbar
 * watcher. Importing it would install hooks for a feature this host does not have.
 *
 * ============================== WHY THIS HOST IS THE EASIEST ONE ==============================
 *
 * Three measured properties, each of which removed work that the earlier hosts needed:
 *
 *   1. THE MENU IS A REAL `android.widget.PopupWindow`. `[DEX]` Xiaohongshu references
 *      `showAtLocation` and `showAsDropDown` across 20 of its 24 dex files, and `[ON-DEVICE]`
 *      `dumpsys window windows` during a long-press printed
 *      `Window{... u0 PopupWindow:...} contentSize=396x1050`. QQ's menu is a custom
 *      `BubblePopupWindow` for which no standard hook ever fired -- that is what forced QQ's
 *      root-view-list probe. Here the standard anchor works, so the probe is only a FALLBACK.
 *
 *   2. THE CLASS NAMES ARE NOT OBFUSCATED. `MenuView`, `MenuItemView`, `m4`, and the menu-item
 *      model's fields (`menuItemIcon`, `componentName`, `isNeedShow`) are all readable. QQ renames its
 *      render method every release (`A` on 9.3.65, `G`/`H`/`n` on 9.1.97), which is why QQ needs a
 *      shape-matching fallback. This host does not.
 *
 *   3. A ROW IS icon + label. `[DEX]` `ImMenuItemLayoutBinding` is
 *      `LinearLayout { ImageView + TextView }`, so our clone is three lines of view code and is
 *      geometrically identical to the host's own rows.
 *
 * ============================== THE ENTRY POINT, AND HOW IT WAS FOUND ==============================
 *
 * The menu is built by `MenuView` and its rows by
 *
 *     MenuView#m4(I, sk4/e, String, MenuView, MenuItemView, sk4/p3, View, rzb/j0) -> Unit
 *
 * which `[DEX]` shows is the only method on that class receiving BOTH the item model (`sk4/e`) and the
 * item VIEW (`MenuItemView`) -- i.e. the place a row is bound. Rather than hooking that 8-argument
 * method by name (a name that a future release may rename), this implementation finds it BY SHAPE:
 * a declared method on [XiaohongshuMenuIdentity.MENU_VIEW] that takes [XiaohongshuMenuIdentity.ITEM_VIEW]
 * as a parameter. That survives a rename and still pins the right method, because no other method on
 * the class takes a row view.
 *
 * ============================== PERFORMANCE AND SAFETY ==============================
 *
 * The hook body runs INSIDE the host's own menu-building call, on the UI thread. Therefore:
 *   * constant work only -- one idempotence check by tag and at most one view insertion;
 *   * every step is wrapped, because a throw here would propagate into Xiaohongshu's UI code and
 *     could crash or stutter the host, the one outcome this module must never cause;
 *   * logs are rate-limited BY SIGNATURE (a `Set<String>`), never by a counter. 必看.md records why:
 *     a counter lets one repeated cause consume the whole budget and hide a NEW one.
 *
 * Comments are ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class XiaohongshuHooks(
    /**
     * The HOST classloader, handed over by the installer.
     *
     * Accepted only to match the three-argument contract every other per-host hook uses. Host classes
     * are resolved through `resolver.classLoader()` instead, because 必看.md makes that a hard rule: a
     * class loaded from the wrong loader produces a "not found" that reads like a missing feature.
     */
    @Suppress("UNUSED_PARAMETER") private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    /**
     * The host's message reader.
     *
     * Constructed here rather than added to `MsgAccess`, so the two files cannot collide -- the same
     * arrangement `QQHistoryHooks` uses for `QQMsgAccess`.
     */
    private val access by lazy { XiaohongshuMsgAccess(resolver) }

    /** Bounded, signature-keyed log so a repeated cause cannot bury a new one. */
    private val logged = Collections.synchronizedSet(HashSet<String>())

    /** How many times the menu was seen with our row already present, so idempotence is provable. */
    @Volatile
    private var menusSeen = 0

    /** How many times a row was actually inserted. */
    @Volatile
    private var rowsInjected = 0

    /** How many times our row was tapped. */
    @Volatile
    private var taps = 0

    /** Injection attempts against popup windows, per window, so one window cannot starve another. */
    private val popupAttempts =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    /**
     * Roots whose full structure has already been dumped, so the expensive walk happens ONCE.
     *
     * Identity-keyed and weak: a menu window is short-lived, and holding it strongly here would keep
     * every menu ever opened alive for the life of the process -- the leak 必看.md records for host
     * caches. `Collections.newSetFromMap(WeakHashMap())` gives exactly that.
     */
    private val structureDumped: MutableSet<View> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))

    /** (root, kind) pairs already reported, so a repeating cause cannot flood the log. */
    private val notedRoots = Collections.synchronizedSet(HashSet<String>())

    /** Signature keys for [noteOnce], separate from [logged] because these are per-arg-shape. */
    private val noteKeys = Collections.synchronizedSet(HashSet<String>())

    /** How many times the item binder handed us a message -- proof the capture route works. */
    @Volatile
    private var binderSawMessage = 0

    /** How many times a BINDER produced the authoritative message. Drives the capture log. */
    @Volatile
    private var captures = 0

    /**
     * True when [o] is worth handing to [recordBinderMessage].
     *
     * DELIBERATELY LOOSER THAN `access.isMessage`: this is the raw argument check, and on this host the
     * binder's argument is a `ly7.*` MENU ENTRY whose payload (the real message) is one hop down. That
     * wrapper has no id/chatId/time of its own, so the strict `isMessage` test refuses it -- correctly,
     * as a MESSAGE -- while `unwrap` inside [recordBinderMessage] is what turns it into the message.
     *
     * So this test answers "could this object lead to a message", not "is this a message": host-model
     * objects pass, and the framework types that share the argument list (`int`, `String`, `View`,
     * `List`) do not.
     */
    private fun isHostCandidate(o: Any?): Boolean {
        if (o == null) return false
        if (o is String || o is CharSequence || o is Number || o is Boolean) return false
        if (o is Collection<*> || o is Map<*, *> || o is Array<*> || o is Enum<*>) return false
        if (o is android.view.View || o is android.content.Context) return false
        val n = o.javaClass.name
        if (n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("android.")) return false
        return true
    }

    /**
     * The message the user long-pressed.
     *
     * WHY THIS IS HELD: the menu is built from a message, and the click on our row arrives LONG after
     * `onMenuBuilt` returns -- by then the message is no longer reachable from the view tree. Soul's
     * implementation records the same thing for the same reason ([SoulHooks] keeps the message from
     * `onMenuBuilding`). Without it, tapping 分析 would have nothing to analyse.
     */
    @Volatile
    private var pendingMessage: Any? = null

    /** The menu root the current pending message came from, used to clear stale state. */
    @Volatile
    private var pendingMenu: View? = null

    fun install() {
        Log.i(
            TAG,
            ">> xiaohongshu hooks: installing (host=XIAOHONGSHU versionName=" +
                com.luoliaoo.wechat.BuildInfo.VERSION_NAME + ")",
        )

        var menuHooked = false
        try {
            menuHooked = installMenuHook()
        } catch (t: Throwable) {
            note("install-threw:" + t.javaClass.simpleName, "xhs: menu hook install threw: ${t.message}")
        }

        // THE MESSAGE HOOK, and on this host it is the important one. The menu is a RecyclerView, so
        // its rows come from adapter binders -- and the binder's `onBindViewHolder` is handed the
        // MESSAGE as a plain argument. That is the only route here that needs no searching: `m4` never
        // fires, and the adapter's data items hide their payload behind obfuscated types.
        var msgHooked = false
        try {
            msgHooked = installItemBinderHook()
        } catch (t: Throwable) {
            note("binder-threw:" + t.javaClass.simpleName, "xhs: item-binder hook install threw: ${t.message}")
        }

        // The popup anchors. These are the PRIMARY route on this host -- see the class docs -- and
        // they are installed even when the menu-shape hook succeeded, because they are the ones that
        // fire on a build whose method names moved.
        try {
            installPopupAnchors()
        } catch (t: Throwable) {
            note("popup-threw:" + t.javaClass.simpleName, "xhs: popup anchors threw: ${t.message}")
        }

        // The fallback of last resort: watch the framework's own root-view list. On QQ this was the
        // ONLY route that worked; here it exists so that a future release which stops using a standard
        // PopupWindow degrades to "found a bit later" rather than "no entry at all".
        try {
            installWindowProbe()
        } catch (t: Throwable) {
            note("probe-threw:" + t.javaClass.simpleName, "xhs: window probe threw: ${t.message}")
        }

        Log.i(
            TAG,
            ">> xiaohongshu hooks: done (menuHooked=" + menuHooked + " msgHooked=" + msgHooked +
                " menusSeen=" + menusSeen + " rowsInjected=" + rowsInjected + " taps=" + taps + ")",
        )
    }

    // ------------------------------------------------------------------ THE message hook

    /**
     * Records a message captured by the BINDER hooks -- the authoritative source.
     *
     * ============================== WHY THE BINDER ALWAYS WINS ==============================
     *
     * The host hands the binder the message for the message the user long-pressed; everything else this
     * class does is inference from a view tree. So a binder capture REPLACES the current value even when
     * the tree scan already set one, and it is never replaced by a textless tree object.
     *
     * This single funnel exists because the two binder hooks (constructor and bind) and the tree scan
     * all write [pendingMessage], and the measured bug was precisely an ordering problem between them:
     *
     *     16:48:00.813  binder CONSTRUCTOR captured MsgUIData text='ok'
     *     16:48:00.837  binder bind        captured MsgUIData text='ok'
     *     16:48:00.895  tree scan          captured ly7.c   text='<no text>'   <== clobbered
     *
     * Routing every capture through one place makes that impossible to reintroduce: there is now
     * exactly one line in the codebase that assigns [pendingMessage].
     *
     * @param raw the object the host handed over, possibly a wrapper.
     * @param source a short label for the log ("constructor", "bind").
     * @return true when this call set or improved [pendingMessage].
     */
    private fun recordBinderMessage(raw: Any, source: String): Boolean {
        // UNWRAP FIRST, JUDGE AFTER -- the order that makes a WRAPPER acceptable.
        //
        // The chat list's rows reach a hook as `ql4.a`, a wrapper holding the real `MsgUIData`, so any
        // code that tests the argument directly rejects a perfectly good capture. [XiaohongshuMsgAccess
        // .resolve] does exactly this in one step, and is used here for that reason.
        val msg = access.resolve(raw) ?: return false
        // The message must be readable; a wrapper with no id/chatId/time is not the message.
        if (!access.hasMessageData(msg)) return false
        val text = access.text(msg)

        val current = pendingMessage
        // A textless capture may still IMPROVE on nothing, but it must never replace a message that has
        // text -- the case that produced the empty card.
        if (current != null) {
            val currentText = access.text(current)
            if (currentText != null && text == null) return false
            if (current === msg) return true
        }
        pendingMessage = msg
        captures++
        if (captures == 1 || captures % 50 == 0) {
            Log.i(
                TAG,
                "xhs: message from the binder " + source + " (#" + captures + ") -- " +
                    msg.javaClass.name + " text='" + (text ?: "<no text>").take(30) + "'" +
                    " id=" + access.id(msg) + " chat=" + access.talker(msg),
            )
        }

        // ============================== FEED THE LIVE HISTORY RECORDER ==============================
        //
        // THIS is the line that makes history accumulate on its own. The field report:
        //
        //     「循环写入本地聊天记录没有用 只有点分析才会写入那个信息」
        //
        // Exactly right, and the cause is a missing FEEDER rather than a broken writer. The writer
        // already exists and is host-agnostic -- `AnalysisController.startLiveTimer` flushes every 2s
        // and its install log appears on this host:
        //
        //     live history: the binder is feeding the recorder
        //     live history timer started (every 2s)
        //
        // ...but on WeChat and Douyin the thing that FEEDS it is a host-specific hook (WeChat's
        // `ChatListHook`, Douyin's adapter binder). Neither exists for Xiaohongshu, so the timer kept
        // running with an empty queue and nothing was ever written. Pressing 分析 worked because that
        // path records the message it was handed directly, which is why history only ever contained the
        // one message the user analysed by hand.
        //
        // The binder is the right feeder: it is handed the message the host is about to SHOW, so every
        // message that reaches the screen is recorded once, with no extra hook and no scrolling.
        //
        // ONLY A MESSAGE WITH TEXT IS FED, matching the blank-text gate the other hosts' feeders apply
        // (`ChatListHook` rejects blank content too). An image message has no text, and feeding it would
        // put an empty line in the store.
        //
        // THE FEED IS WRAPPED because it runs inside the host's bind callback: a throw here would break
        // the chat list's rendering, so a failure must degrade to "history stops" rather than to a crash.
        if (text != null) {
            try {
                controller.onLiveMessage(msg)
            } catch (t: Throwable) {
                note(
                    "live-feed-threw:" + t.javaClass.simpleName,
                    "xhs: feeding the live-history recorder threw and was swallowed: " + t.message,
                )
            }
        }
        return true
    }

    /**
     * Hooks the menu item binder's CONSTRUCTOR, whose SECOND ARGUMENT is the message.
     *
     * ============================== WHY THE CONSTRUCTOR, NOT onBindViewHolder ==============================
     *
     * `onBindViewHolder` was tried first and it captures the WRONG OBJECT. The structure dump proves
     * what it actually receives:
     *
     *     class ly7.c extends jy7.l
     *     class jy7.l extends java.lang.Object
     *        .a : o = jy7.o {}
     *
     * and there are exactly SIX such classes -- `ly7.c`, `ly7.i`, `ly7.t`, `ly7.s`, `ly7.b0`, `ly7.f`
     * -- matching the menu's six rows (复制/转发/引用/顶/便利贴/删除). So `onBindViewHolder` is handed a
     * MENU ENTRY (one row's configuration), NOT the message. Its `jy7.o` payload holds the row's own
     * data, which is why every read came back empty: the code was looking for message text in a class
     * that describes a button.
     *
     * The message arrives at CONSTRUCTION instead. `[DEX]` measured on 9.47.0:
     *
     *     LongPressMenuItemBinder.<init>(jy7.n, com.xingin.chatbase.bean.MsgUIData)
     *     field e : com.xingin.chatbase.bean.MsgUIData
     *
     * The second parameter IS the `MsgUIData` for the message the user long-pressed, and the binder
     * stores it so every one of its rows can act on that message. Hooking the constructor therefore
     * yields the message DIRECTLY -- no unwrapping, no obfuscated-type traversal, no guessing which
     * slot holds it.
     *
     * `onBindViewHolder` is still hooked as a FALLBACK (see [installItemBinderHook]), because a release
     * could move the message from the constructor to the bind. Whichever fires with a readable message
     * wins; [pendingMessage] is replaced only by a value that actually carries text.
     */
    private fun installBinderConstructorHook(cls: Class<*>): Int {
        var hooked = 0
        for (ctor in cls.declaredConstructors) {
            // The measured signature has 2 parameters and the message is the SECOND. Accepted only when
            // the second parameter is a host model type -- NOT when it is a primitive or a String, so a
            // future overload taking (View, int) cannot be mistaken for it.
            if (ctor.parameterCount < 2) continue
            val second = ctor.parameterTypes[1]
            if (second.isPrimitive || second == String::class.java) continue
            try {
                LegacyHook.hook(ctor)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        try {
                            // The message is the constructor's SECOND argument, measured as
                            // `MsgUIData`. `messageOnHost` is NOT consulted here: at construction time
                            // the field is not yet assigned (the constructor is the code that assigns
                            // it), so the argument is the only source that exists.
                            val raw = chain.args.drop(1).firstOrNull { isHostCandidate(it) }
                            if (raw != null) {
                                recordBinderMessage(raw, "constructor")
                            }
                        } catch (t: Throwable) {
                            note(
                                "ctor-cb-threw:" + t.javaClass.simpleName,
                                "xhs: the binder-constructor hook threw and was swallowed: " + t.message,
                            )
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "xhs: hooked the item binder's CONSTRUCTOR " + ctor.parameterCount +
                        "args (arg[1] = " + second.name + ") -- this is where the MESSAGE arrives",
                )
            } catch (t: Throwable) {
                note("ctor-hook-failed:" + ctor.parameterCount, "xhs: hooking the binder constructor failed: ${t.message}")
            }
        }
        return hooked
    }

    /**
     * Hooks the menu item binder's `onBindViewHolder` as a FALLBACK message source.
     *
     * ============================== WHAT IT IS AND IS NOT GOOD FOR ==============================
     *
     * It is NOT the message: measured, it receives one of six `ly7.*` MENU-ENTRY objects (see
     * [installBinderConstructorHook]). But it IS the reliable signal that the long-press menu is being
     * populated, and it carries the ROW's own data -- so it is kept for two jobs:
     *
     *   1. as a FALLBACK message source, in case a release moves the message here;
     *   2. as the trigger that records WHICH menu is on screen, which the entry needs.
     *
     * BOTH overloads are hooked when present (`(VH, T)` and `(VH, T, List)`), for the reason
     * [XiaohongshuHistoryLogic.isBindMethod] documents: a RecyclerView calls the 3-argument payload
     * form for incremental updates, and hooking only the 2-argument one misses those.
     *
     * @return the number of hooks installed.
     */
    private fun installItemBinderHook(): Boolean {
        val cls = loadClass(XiaohongshuMenuIdentity.ITEM_BINDER)
        if (cls == null) {
            note(
                "no-binder",
                "xhs: " + XiaohongshuMenuIdentity.ITEM_BINDER + " is not loadable, so the message " +
                    "cannot be captured from the binder; the entry will appear but 分析 will have " +
                    "nothing to work on. That class is the measured menu-row binder on 9.47.0.",
            )
            return false
        }

        // THE PRIMARY ROUTE: the constructor, which receives the message itself.
        val ctorCount = try {
            installBinderConstructorHook(cls)
        } catch (t: Throwable) {
            note("ctor-install-threw:" + t.javaClass.simpleName, "xhs: binder-constructor install threw: ${t.message}")
            0
        }

        var hooked = 0
        for (m in cls.declaredMethods) {
            if (m.name != "onBindViewHolder") continue
            // arg[1] is the item (the message) in both overloads; arg[0] is the ViewHolder.
            if (m.parameterCount != 2 && m.parameterCount != 3) continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        try {
                            // ============================== TWO SOURCES, IN PRIORITY ORDER ==============================
                            //
                            // 1. THE BINDER INSTANCE (`thisObject`). `[DEX]` states that the constructor's
                            //    second argument -- the `MsgUIData` -- is STORED on the instance:
                            //
                            //        LongPressMenuItemBinder.<init>(jy7.n, ...MsgUIData)
                            //        LongPressMenuItemBinder  field e : ...MsgUIData
                            //
                            //    So the instance holds the message on every bind, even though MultiType
                            //    caches binders and the constructor itself runs only once. Read BY TYPE
                            //    (not by the obfuscated name `e`) so a rename cannot break it. This is the
                            //    source that produced the working capture in the log:
                            //        message captured from the binder bind (#1) -- MsgUIData text='ok'
                            //
                            // 2. THE ARGUMENTS, as a fallback for a build that passes the message to the
                            //    bind instead. On THIS build the argument is one of six `ly7.*` MENU
                            //    ENTRIES (measured via the GRAPH dump), and [isHostCandidate] plus
                            //    [recordBinderMessage] refuse those, so the fallback cannot clobber the
                            //    real message.
                            val fromInstance = access.messageOnHost(chain.thisObject)
                            if (fromInstance != null) {
                                recordBinderMessage(fromInstance, "bind/instance")
                            }
                            if (pendingMessage == null || access.text(pendingMessage) == null) {
                                chain.args.drop(1).firstOrNull { isHostCandidate(it) }?.let {
                                    recordBinderMessage(it, "bind/arg")
                                }
                            }
                        } catch (t: Throwable) {
                            note(
                                "bind-cb-threw:" + t.javaClass.simpleName,
                                "xhs: the binder bind hook threw and was swallowed: " + t.message,
                            )
                        }
                    }
                hooked++
                Log.i(
                    TAG,
                    "xhs: hooked the item binder's onBindViewHolder/" + m.parameterCount + "args -- " +
                        "the MESSAGE now arrives as an argument (arg[1])",
                )
            } catch (t: Throwable) {
                note("binder-hook-failed:" + m.parameterCount, "xhs: hooking onBindViewHolder/${m.parameterCount} failed: ${t.message}")
            }
        }
        if (hooked == 0) {
            note(
                "binder-no-overload",
                "xhs: " + cls.name + " declares no onBindViewHolder/2or3-arg method, so the message " +
                    "hook is unavailable. Declared: " + describeMethods(cls),
            )
        }
        return hooked > 0
    }

    /** Logs once per signature. Separate from [note] because these keys are per-argument-shape. */
    private fun noteOnce(key: String, message: String) {
        if (noteKeys.add(key)) Log.w(TAG, message)
    }

    // ------------------------------------------------------------------ menu

    /**
     * Hooks the row-binding method on [XiaohongshuMenuIdentity.MENU_VIEW], found BY SHAPE.
     *
     * @return true when a hook was installed.
     */
    private fun installMenuHook(): Boolean {
        val cls = loadClass(XiaohongshuMenuIdentity.MENU_VIEW)
        if (cls == null) {
            note(
                "no-menu-view",
                "xhs: " + XiaohongshuMenuIdentity.MENU_VIEW + " is not loadable, so the long-press " +
                    "分析 entry cannot be installed on this build",
            )
            return false
        }

        val itemCls = loadClass(XiaohongshuMenuIdentity.ITEM_VIEW)
        if (itemCls == null) {
            note(
                "no-item-view",
                "xhs: " + XiaohongshuMenuIdentity.ITEM_VIEW + " is not loadable, so a menu row cannot " +
                    "be recognised and the 分析 entry cannot be installed",
            )
            return false
        }

        val target = findRowBindMethod(cls, itemCls)
        if (target == null) {
            note(
                "no-row-bind:" + cls.name,
                "xhs: no method on " + cls.name + " takes " + XiaohongshuMenuIdentity.ITEM_VIEW +
                    " as a parameter, so the row bind could not be found BY SHAPE. Declared methods: " +
                    describeMethods(cls),
            )
            return false
        }

        Log.i(
            TAG,
            "xhs: menu row bind = " + cls.name + "#" + target.name +
                " modifiers=[" + Modifier.toString(target.modifiers) + "] params=" +
                target.parameterCount + " (found by SHAPE: it is the method that receives a row view)",
        )

        return try {
            LegacyHook.hook(target)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    // Runs INSIDE Xiaohongshu's menu-building call, on the UI thread. A throw here
                    // would propagate into the host's own code, so it must never escape.
                    try {
                        onMenuBuilt(chain.thisObject, chain.args)
                    } catch (t: Throwable) {
                        note(
                            "menu-threw:" + t.javaClass.simpleName,
                            "xhs: the menu hook threw and was swallowed: " + t.message,
                        )
                    }
                }
            Log.i(TAG, "xhs: hooked " + cls.name + "#" + target.name + " -- the menu is now reachable")
            true
        } catch (t: Throwable) {
            note("hook-failed:" + target.name, "xhs: hooking " + cls.name + "#" + target.name + " failed: ${t.message}")
            false
        }
    }

    /**
     * True when [m] is the row-binding method.
     *
     * ------------------------------ WHY A SHAPE TEST AND NOT A NAME ------------------------------
     * The measured signature is `m4(I, sk4/e, String, MenuView, MenuItemView, sk4/p3, View, rzb/j0)`,
     * but `m4` is a name that a Xiaohongshu release can change -- and the whole reason QQ needed a
     * shape fallback is that exactly this happened to it (`A` -> `G`/`H`/`n`).
     *
     * WHAT MAKES THE SHAPE UNIQUE: the method must take the ROW VIEW class ([itemCls]) as one of its
     * parameters. `[DEX]` shows exactly one method on `MenuView` does, so the test is precise rather
     * than merely permissive -- it cannot select a neighbouring helper.
     *
     * The predicate is a PURE function so a JVM unit test can pin the rule without a device.
     */
    /**
     * The row-bind method on [cls], chosen among ALL shape-matching candidates.
     *
     * ============================== WHY THIS COLLECTS RATHER THAN TAKES THE FIRST ==============
     * The first version used `firstOrNull`, on the belief that only one method could take a row view.
     * THE DEVICE LOG DISPROVED IT:
     *
     *     xhs: menu row bind = com.xingin.im.v2.message.views.MenuView#Y4 params=3
     *
     * `Y4` took only 3 parameters, but the measured bind (`m4`) takes 8 -- so `firstOrNull` had latched
     * onto a click/callback helper instead of the bind. The result was a hook that installed cleanly
     * and never fired: the user sees no 分析 entry and the log shows no error, which is the worst kind
     * of failure to diagnose.
     *
     * So every candidate is collected and [XiaohongshuMenuLogic.pickRowBindMethod] chooses among them,
     * preferring the one with the most parameters. The choice is logged with the FULL candidate list,
     * so the next build's log says exactly what was available and what was picked.
     */
    private fun findRowBindMethod(cls: Class<*>, itemCls: Class<*>): Method? {
        val candidates = try {
            cls.declaredMethods.filter { m ->
                XiaohongshuMenuLogic.matchesRowBindShape(
                    paramTypes = m.parameterTypes.toList(),
                    rowViewClass = itemCls,
                )
            }
        } catch (_: Throwable) {
            return null
        }
        if (candidates.isEmpty()) return null

        val picked = XiaohongshuMenuLogic.pickRowBindMethod(candidates)
        if (candidates.size > 1) {
            Log.i(
                TAG,
                "xhs: " + candidates.size + " methods take a row view; picked " + picked?.name +
                    " (most parameters). All candidates: " +
                    candidates.joinToString(", ") { it.name + "/" + it.parameterCount + "args" },
            )
        }
        return picked
    }

    /**
     * Called when the host binds ONE menu row. Adds OUR row beside it, once per menu.
     *
     * ============================== TWO FACTS THAT REWROTE THIS METHOD ==============================
     *
     * A device log corrected two wrong assumptions, and both corrections are load-bearing:
     *
     *   1. `m4` IS STATIC. The log prints `modifiers=[public static]`, so `chain.thisObject` is NULL on
     *      every call. The previous version began `if (menuRoot == null) return`, which meant the whole
     *      method returned immediately, every time -- the hook was installed, was firing, and did
     *      nothing at all. The user saw "no button" and the log showed no error, the hardest failure
     *      shape to read. A STATIC hook must take its subject from the ARGUMENTS.
     *
     *   2. THE HOST HANDS US THE MENU. The measured signature is
     *
     *          m4(int, sk4/e, String, MenuView, MenuItemView, sk4/p3, View, rzb/j0)
     *                              [3]      [4]
     *
     *      so arg[3] IS the menu root and arg[4] IS the row being bound. The previous version searched
     *      the whole view tree for a "row that looks like a row" -- a heuristic that picked a container
     *      inside a RecyclerView and CRASHED the host. It is now
     *      unnecessary: the menu is given, exactly, by position. A search that guesses is strictly
     *      worse than an argument that states.
     *
     * Because `m4` runs once PER ROW, this is called several times for one menu. That is why the
     * idempotence check is by TAG on the menu root: the first row to arrive adds our entry, and every
     * later row sees it already present and returns.
     *
     * @param thisObject `chain.thisObject` -- null, because the method is static. Accepted and ignored
     *   rather than dropped, so the call site does not have to know which kind of method it hooked.
     * @param args the method's arguments; arg[3] is the menu, arg[4] the row, arg[1] the item model.
     */
    private fun onMenuBuilt(
        @Suppress("UNUSED_PARAMETER") thisObject: Any?,
        args: Array<Any?>,
        /**
         * The menu root when the caller ALREADY knows it -- the popup and window-probe routes discover
         * the popup's content view directly and have no arguments to search. Null means "derive it
         * from the arguments", which is the row-bind route.
         */
        rootOverride: View? = null,
    ) {
        // The menu root, from the ARGUMENT -- `thisObject` is null, see fact 1 above. The fallback to
        // thisObject is kept for a future release that makes the method non-static, which would flip
        // the two sources and should not require a code change to keep working.
        val menuRoot = rootOverride
            ?: XiaohongshuMenuLogic.pickArgOfType(args, menuViewClass()) as? View
            ?: (thisObject as? View)

        if (menuRoot == null) {
            note(
                "no-menu-root",
                "xhs: the row bind fired but NEITHER thisObject nor any argument was a " +
                    XiaohongshuMenuIdentity.MENU_VIEW + ", so the menu root is unknown and the 分析 " +
                    "entry cannot be placed. Arguments were: " + describeArgs(args),
            )
            return
        }

        // ------------------------------------------------------------------ the message
        //
        // Recorded BEFORE the row is added, because the row's click handler needs it and by then this
        // call has long returned.
        //
        // ============================== THE OVERWRITE BUG, AND WHY THIS ORDER IS LOAD-BEARING ==========
        //
        // THE BINDER HOOKS RUN FIRST AND THEY WORK. The device log proves it:
        //
        //     16:48:00.813  message captured from the binder CONSTRUCTOR (#1) -- MsgUIData text='ok'
        //     16:48:00.837  message captured from the binder bind (#1) -- MsgUIData text='ok'
        //     16:48:00.895  message captured for 分析 -- ly7.c text='<no text>'      <== CLOBBERED IT
        //
        // The third line is this method: it found `ly7.c` -- a MENU ENTRY, one of six, one per button --
        // somewhere in the menu tree and assigned it over the real message the binder had just captured.
        // The card then rendered empty, which read as a capture failure when the capture was fine.
        //
        // So the rule now is: NEVER REPLACE A MESSAGE THAT HAS TEXT WITH ONE THAT HAS NONE. A capture
        // with text is strictly better evidence than a tree scan, because the binder is handed the
        // message by the host while a tree scan is inference.
        //
        // `pendingMessage` is only overwritten when the new candidate is better -- i.e. when the old one
        // had no text and the new one does. That keeps a textless IMAGE message updatable while making
        // it impossible for a menu entry to destroy a real message.
        //
        // ============================== THE `return` THAT BROKE THE BUTTON ==============================
        //
        // The first version of this guard was `if (already != null && text != null) return`, and it
        // removed the entry from the menu entirely. The field report:
        //
        //     「现在直接长按没有分析按钮了」
        //
        // The reason is that this method does TWO INDEPENDENT JOBS -- recording the message AND adding the
        // 分析 row -- and a `return` here skips the second one. It fired on every long-press as soon as
        // the binder had captured a message (which it now does reliably), so the entry never appeared.
        //
        // Capturing the message and placing the entry are therefore NOT allowed to share a control path:
        // the message block only ever ASSIGNS, and never returns. The idempotence check below, not this
        // guard, is what decides whether the row is added.
        //
        // NOTE the tree routes are consulted only when the binders have NOT already produced a texted
        // message, which is the normal case on this host -- so on 9.47.0 this block usually does nothing
        // at all, exactly as it should.
        val already = pendingMessage
        val haveTextedMessage = already != null && access.text(already) != null

        if (!haveTextedMessage) {
            val candidate = firstMessageFrom(args)
                ?: messageFromMenuTree(menuRoot)?.takeIf { access.hasMessageData(it) }
            val current = pendingMessage
            val currentText = current?.let { access.text(it) }
            val candidateText = candidate?.let { access.text(it) }
            val take = candidate != null &&
                (current == null || currentText == null || (candidateText != null && current === candidate))
            if (take) {
                pendingMessage = candidate
                pendingMenu = menuRoot
                Log.i(
                    TAG,
                    "xhs: message for 分析 = " + candidate?.javaClass?.name + " text='" +
                        (candidateText ?: "<no text>").take(24) + "'" +
                        (if (current != null && current !== candidate) " (replaced " + current.javaClass.simpleName + ")" else ""),
                )
            } else if (candidate != null) {
                noteOnce(
                    "kept-better:" + current?.javaClass?.name,
                    "xhs: kept the binder's message (" + current?.javaClass?.name + " text='" +
                        (currentText ?: "").take(16) + "') and IGNORED " + candidate.javaClass.name +
                        " from the menu tree -- the tree object carries no text, so it is a menu entry rather " +
                        "than the message",
                )
            } else {
                // Nothing from any route. Rather than leave the user with a 分析 button that silently
                // does nothing, print every place that was searched and what it held.
                logArgProbe(args)
                logMenuSources(menuRoot)
            }
        }

        // ------------------------------------------------------------------ idempotence
        //
        // BY TAG on the menu root, which is exact, and it matters MORE here than on any other host:
        // this method runs ONCE PER ROW, so without the check a 7-item menu would receive SEVEN copies
        // of 分析. A class-name or counter check would conflate "same kind of menu" with "same menu"
        // -- that mistake on QQ meant the entry appeared on the first long-press and never again.
        val existing = try {
            menuRoot.findViewWithTag<View>(XiaohongshuMenuIdentity.OUR_ROW_TAG)
        } catch (_: Throwable) {
            null
        }
        if (existing != null) {
            menusSeen++
            if (menusSeen % 40 == 0) {
                Log.i(TAG, "xhs: menu re-bound (" + menusSeen + " row binds seen); 分析 already present")
            }
            return
        }

        menusSeen++

        // ------------------------------------------------------------------ WHICH row we sit beside
        //
        // The host's own row objects are RecyclerView items, so they cannot be cloned as siblings.
        // What this needs is a HOST ROW to copy geometry from, and the tree gives us one -- see
        // [findHostRowForGeometry].
        val hostRow = findHostRowForGeometry(menuRoot)
        if (hostRow == null) {
            note(
                "no-anchor-row",
                "xhs: the menu rendered (root=" + menuRoot.javaClass.simpleName + ") but no host row " +
                    "was found among labels " + XiaohongshuMenuIdentity.HOST_ROW_LABELS +
                    ", so the 分析 entry was NOT added. Tree: " + treeSummary(menuRoot, 0),
            )
            return
        }

        // ------------------------------------------------------------------ WHERE the row goes
        //
        // THE MENU IS A RECYCLERVIEW, so the insertion target is the RecyclerView's PARENT -- a plain
        // container -- never the RecyclerView itself. Measured menu structure:
        //
        //     LinearLayout 1050x396 n=3
        //       RoundLinearLayout 1050x378 n=2      <== the insertion target (a LinearLayout)
        //         RecyclerView 1050x378 n=7         <== the menu rows; inserting here CRASHES
        //       AppCompatImageView 49x18
        //
        // WHY NOT THE RECYCLERVIEW, restated because it cost a crash: a RecyclerView requires every
        // child to have been added through its adapter so a ViewHolder exists. `addView` bypasses
        // that, and the layout pass then dereferences a ViewHolder that was never created:
        //
        //     NullPointerException: RecyclerView$ViewHolder.shouldIgnore() on a null reference
        //         at RecyclerView.findMinMaxChildLayoutPositions
        //
        // Adding to the RecyclerView's PARENT is safe: that container is a LinearLayout, which
        // accepts any child. Our row becomes a sibling of the menu strip, laid out by the same
        // LinearLayout -- so it appears as part of the menu without ever touching the adapter.
        val target = findInsertionTarget(hostRow)
        if (target == null) {
            note(
                "no-target",
                "xhs: none of the anchor row's ancestors is a safe insertion container (the menu is a " +
                    "RecyclerView and its wrapper chain is unusable), so 分析 was NOT added. Anchor=" +
                    hostRow.javaClass.simpleName + " ancestors=" + ancestorsOf(hostRow),
            )
            return
        }

        val ourRow = buildRow(hostRow) ?: run {
            note("build-failed", "xhs: the 分析 row could not be built from the host's row shape")
            return
        }

        try {
            // APPENDED to the wrapper, not inserted before the menu strip: the wrapper lays out the
            // RecyclerView plus any other children in ITS order, and putting our row after the strip
            // is what makes it read as the next entry rather than as something that pushed the menu
            // aside. This is the one host where appending and inserting differ in VISIBLE WIDTH --
            // on QQ the same choice was about a corner of a 14-child container.
            target.addView(ourRow)

            // The host's own layout pass may have already measured this container, so ask for a fresh
            // one -- otherwise the row is present in the tree but never laid out, which looks exactly
            // like "the injection did not happen". The whole chain is invalidated because the popup
            // itself must grow: a PopupWindow wraps its content, so adding a 189px row inside a 396px
            // popup only becomes visible if the popup re-measures.
            target.requestLayout()
            var p: View? = target
            var hops = 0
            while (p != null && hops < 6) {
                p.requestLayout()
                p = p.parent as? View
                hops++
            }

            rowsInjected++
            Log.i(
                TAG,
                "xhs: injected the 分析 row into " + target.javaClass.simpleName +
                    " (" + target.width + "x" + target.height + ", children now " +
                    target.childCount + ", copied geometry from '" + labelOf(hostRow) + "' " +
                    hostRow.width + "x" + hostRow.height + ", injection #" + rowsInjected + ")",
            )
        } catch (t: Throwable) {
            note("insert-threw:" + t.javaClass.simpleName, "xhs: inserting the 分析 row threw: ${t.message}")
        }
    }

    /**
     * The host menu row used ONLY to copy geometry from -- not an insertion sibling.
     *
     * ============================== WHY THE ANCHOR'S ROLE CHANGED ==============================
     *
     * On every earlier host the anchor row was the row our entry was inserted BESIDE. Here that is
     * impossible: Xiaohongshu's menu rows are RecyclerView items, so they exist only as long as the
     * adapter says so, and cloning one as a sibling is exactly the crash documented in [insertRow].
     *
     * The anchor is still needed for something the tree can give us: the SIZE and TEXT SIZE a menu row
     * uses (measured 175x189 with a 70x70 icon and 47px-tall label). Copying those is what makes our
     * row look like it belongs, and it is the same technique QQ uses (`copyLpFor`).
     *
     * A row qualifies by its LABEL, because the label is the only stable identity here -- the item
     * objects are obfuscated RecyclerView payloads.
     */
    private fun findHostRowForGeometry(root: View): View? {
        val rows = ArrayList<View>()
        collectRows(root, rows, 0)
        if (rows.isEmpty()) return null
        // 转发 is the anchor because the user reported it in EVERY menu variant (text: 复制/转发/引用/
        // 顶/便利贴/删除, image: 转发/引用/便利贴/添加表情/删除). Falling back to any known label keeps the
        // geometry source working when a release renames 转发.
        return rows.firstOrNull { labelOf(it) == XiaohongshuMenuIdentity.ANCHOR_LABEL }
            ?: rows.firstOrNull { XiaohongshuMenuIdentity.HOST_ROW_LABELS.contains(labelOf(it)) }
    }

    /**
     * The container our row is added to: the nearest ANCESTOR of a menu row that a plain `addView` is
     * safe on.
     *
     * ============================== THE RULE ==============================
     *
     * Walk up from the anchor row and take the first ancestor that is a `ViewGroup` and is NOT a
     * RecyclerView. The measured chain makes this land on `RoundLinearLayout`, the strip's wrapper:
     *
     *     RecyclerView            <-- skipped: addView here CRASHES
     *       RoundLinearLayout     <-- CHOSEN (a LinearLayout; measured direct parent)
     *         LinearLayout        <-- would also work, one level further out
     *
     * WHY "NOT A RECYCLERVIEW" AND NOT "IS A LINEARLAYOUT": a menu could be built on a different plain
     * container in a future release (`FrameLayout`, `ConstraintLayout`), and requiring a specific class
     * would silently stop working. The property that actually matters is the NEGATIVE one -- the
     * container must not be a RecyclerView -- so the test is written as that property.
     *
     * The walk is bounded and never returns the popup's own decor view: adding to the decor would put
     * our row outside the menu's rounded card, which reads as a rendering bug rather than a menu entry.
     */
    private fun findInsertionTarget(hostRow: View): ViewGroup? {
        var p: View? = hostRow.parent as? View
        var hops = 0
        while (p != null && hops < 6) {
            val g = p as? ViewGroup
            if (g != null && !p.javaClass.name.contains(RECYCLER_VIEW_MARKER)) {
                // Do not climb all the way out to the window root: the popup's decor is also a
                // FrameLayout, and our row would land outside the menu card.
                if (!p.javaClass.name.contains(MENU_WINDOW_MARKER)) return g
            }
            p = p.parent as? View
            hops++
        }
        return null
    }

    /** The anchor's ancestor chain as `Name(wxh) -> ...`, for the failure log. */
    private fun ancestorsOf(v: View): String {
        val sb = StringBuilder()
        var p: View? = v.parent as? View
        var hops = 0
        while (p != null && hops < 8) {
            sb.append(p.javaClass.simpleName).append('(').append(p.width).append('x')
                .append(p.height).append(") -> ")
            p = p.parent as? View
            hops++
        }
        return sb.toString()
    }

    /**
     * Collects the menu's own rows: a row is a container holding a non-empty short label.
     *
     * The `width > 0` requirement keeps an unmeasured row out -- a row that has not been laid out is
     * not one our clone can copy a size from, and accepting one is how a row ends up 0x0 and invisible
     * while the log still says "injected". (That is a real failure recorded in 必看.md for Kuaishou,
     * where a 1px divider was cloned.)
     *
     * NOTE this now runs during the WINDOW-PROBE path too, where the root is the whole popup. The
     * exclusions in [isMenuRow] are what keep that from collecting the chat list.
     */
    private fun collectRows(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 20) return
        if (isMenuRow(v)) out.add(v)
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            collectRows(c, out, depth + 1)
        }
    }

    /**
     * True when [v] has the shape of one menu row: a laid-out container holding a SHORT label.
     *
     * ============================== THE TWO EXCLUSIONS, EACH MEASURED ==============================
     *
     * The first version of this test accepted any laid-out container with SHORT text, and that is why
     * the menu was reported as having "no recognisable row" even though the structure dump showed
     * seven of them. Two exclusions fix it:
     *
     *   1. NEVER a RecyclerView. The menu's own item container is a RecyclerView, and it has
     *      descendants whose text could look like a label. Accepting it as a "row" would then make its
     *      PARENT the chosen insertion target of a row that is not a row.
     *
     *   2. The container must have a DIRECT CHILD that is a TextView. This is the shape the structure
     *      dump proves a real row has -- `LinearLayout 175x189 { ImageView 70x70, TextView 84x47 }` --
     *      and it is what separates a row from an intermediate wrapper. Without it the search returns
     *      the first wrapper with any text anywhere beneath it, which on a nested tree is the wrapper,
     *      not the row.
     *
     * The label bound is 8 characters because that is the widest real entry observed (添加表情 is 4,
     * 便利贴 is 3); it excludes a paragraph of message text that a stray container might hold.
     */
    private fun isMenuRow(v: View): Boolean {
        val g = v as? ViewGroup ?: return false
        if (v.javaClass.name.contains(RECYCLER_VIEW_MARKER)) return false
        if (g.width <= 0 || g.height <= 0) return false
        // A real row holds its label DIRECTLY, as one of its own children.
        var hasDirectLabel = false
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is TextView) {
                val t = c.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty() && t.length <= 8) hasDirectLabel = true
            }
        }
        if (!hasDirectLabel) return false
        val label = labelOf(g)
        return label.isNotEmpty() && label.length <= 8
    }

    /** The text of a row's first non-empty `TextView`, or "" when it has none. */
    private fun labelOf(v: View): String {
        if (v is TextView) return v.text?.toString()?.trim() ?: ""
        val g = v as? ViewGroup ?: return ""
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            if (c is TextView) {
                val t = c.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty()) return t
            }
        }
        return ""
    }

    /**
     * Builds our 分析 row by CLONING the host row's visual shape.
     *
     * ============================== WHY CLONE THE PARAMS ==============================
     * The measured row shape is `LinearLayout { ImageView + TextView }` (from
     * `ImMenuItemLayoutBinding`), but the host's own margins, padding, text size and icon size come
     * from its XML and theme -- none of which this module can read. Reusing the anchor's
     * `layoutParams` VERBATIM is what makes our row occupy the same slot as the host's; inventing
     * `LayoutParams(dp(64), dp(80))` is how an earlier host's entry ended up visibly different from
     * everything around it.
     *
     * The ICON is drawn by us (there is no host icon to copy for a feature the host does not have),
     * at 24dp, matching the host rows' own icon box.
     */
    private fun buildRow(hostRow: View): View? = try {
        val ctx = hostRow.context ?: return null

        // The host's real row is the LinearLayout wrapping the icon+label; when the anchor IS that
        // container we clone it directly, otherwise we find it so the geometry still comes from a row.
        val shapeSource = hostRow

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            tag = XiaohongshuMenuIdentity.OUR_ROW_TAG
            isClickable = true
            // The module's own accent, not the host's tint: this row is ours, and a colour that
            // follows the host theme would be invisible on one theme or the other.
            setBackgroundColor(0x00000000)
            layoutParams = copyLpFor(shapeSource)
            setPadding(
                shapeSource.paddingLeft, shapeSource.paddingTop,
                shapeSource.paddingRight, shapeSource.paddingBottom,
            )
        }

        // The icon. Drawn by AnalysisIcon, which every other host's entry uses -- one icon definition
        // for the whole module, so the entry looks the same in every app.
        //
        // `toDrawable(ctx, px)` rather than constructing the icon object: that is the entry point
        // QQHooks uses at line 662, and it takes PIXELS. Passing dp here would draw the icon at a
        // fraction of its intended size on a 3x-density screen.
        row.addView(
            ImageView(ctx).apply {
                setImageDrawable(
                    com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, dp(ctx, 24)),
                )
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
            },
        )

        // The label, at the anchor row's own text size so it matches its neighbours exactly.
        //
        // ITS COLOUR IS COPIED FROM THE HOST, never hardcoded, and the field report is why: the first
        // version set a dark colour (`0xFF1A1E2C`) and the user saw the ICON but NO TEXT. A hardcoded
        // dark colour is invisible on a dark-themed menu, and Xiaohongshu's menu is themed -- so the
        // host's own label is the only reliable source for a colour that is legible here. Copying it
        // makes our row legible on BOTH a light and a dark theme, with no theme detection at all.
        val hostLabel = findLabelView(shapeSource)
        val textSize = hostTextSizeOr(hostLabel, 12f)
        val textColor = hostLabel?.currentTextColor ?: DEFAULT_LABEL_COLOR
        row.addView(
            TextView(ctx).apply {
                text = XiaohongshuMenuIdentity.OUR_LABEL
                gravity = android.view.Gravity.CENTER
                setTextColor(textColor)
                // `setTextSize(SP, ...)` explicitly, NOT `textSize = textSize`: inside `apply` the bare
                // `textSize` on the left resolves to TextView's synthetic PROPERTY, and the outer
                // local of the same name is then read as its getter's backing value -- which the
                // compiler rejects as "'val' cannot be reassigned". The named setter also states the
                // unit, which is what the SP conversion above depends on.
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                // A single line, because the host's labels are single lines and a wrapped label would
                // change the row's height and push the icon off-centre.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )

        row.setOnClickListener {
            taps++
            Log.i(TAG, "xhs: our 分析 tapped (#" + taps + ")")
            onOurRowTapped(row)
        }
        row
    } catch (t: Throwable) {
        note("build-threw:" + t.javaClass.simpleName, "xhs: building the 分析 row threw: ${t.message}")
        null
    }

    /** Source 1: the first ARGUMENT the reader accepts as a message. */
    private fun firstMessageFrom(args: Array<Any?>): Any? = try {
        args.firstOrNull { access.isMessage(it) }
    } catch (t: Throwable) {
        note("msg-args:" + t.javaClass.simpleName, "xhs: scanning menu args for a message threw: ${t.message}")
        null
    }

    /**
     * Source 2: the message carried by the MENU'S OWN ITEMS.
     *
     * ============================== WHY THE ADAPTER IS THE ANSWER HERE ==============================
     *
     * The window-probe route has no arguments at all, so on this build -- where `m4` never fires --
     * the adapter is the ONLY place the menu's association with a message survives. The structure dump
     * measured it precisely:
     *
     *     >> RECYCLERVIEW adapter=com.drakeet.multitype.MultiTypeAdapter itemCount=7
     *          .d : List size=7 first=ly7.c
     *
     * So the route is: find the menu's RecyclerView, read `getAdapter()`, read the adapter's data List,
     * then ask each ITEM whether it holds a message. `ly7.c` is obfuscated, so the item is not matched
     * by name -- [XiaohongshuMenuMessageLogic.carriesMessage] looks at what the item HOLDS.
     *
     * WHY EVERY ITEM IS EXAMINED, not just the first: the menu's items are heterogeneous rows (icon
     * rows, dividers, the 举报 group). The message may sit on any of them, and the first item is not
     * privileged. The list is short (7 measured), so scanning it is cheap.
     *
     * WHY THIS IS SAFE TO DO INSIDE THE HOST'S UI CALL: it only READS. It never mutates the list, so
     * the host's own adapter state cannot be corrupted -- the failure mode this module must never
     * cause.
     */
    private fun messageFromMenuTree(root: View): Any? = try {
        val rv = findMenuRecyclerView(root)
        if (rv == null) {
            null
        } else {
            val adapter = rv.javaClass.getMethod("getAdapter").invoke(rv)
            if (adapter == null) null else messageFromAdapter(adapter)
        }
    } catch (t: Throwable) {
        note("msg-tree:" + t.javaClass.simpleName, "xhs: reading the menu's message from the tree threw: ${t.message}")
        null
    }

    /**
     * The message held by a menu adapter, read from its data list.
     *
     * The list field is found by SHAPE (a `List`/`Collection` field holding items), never by the
     * measured name `d`: `d` is an obfuscated name that changes between builds, and the structure dump
     * exists precisely so it does not have to be guessed. Any field whose value is a non-empty
     * collection is examined.
     */
    private fun messageFromAdapter(adapter: Any): Any? {
        val cls = adapter.javaClass
        var c: Class<*>? = cls
        var hops = 0
        while (c != null && c != Any::class.java && hops < 4) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!Collection::class.java.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val list = f.get(adapter) as? Collection<*> ?: continue
                    if (list.isEmpty()) continue
                    for (item in list) {
                        if (item == null) continue
                        // The item may BE the message, or may hold it behind an accessor.
                        if (access.isMessage(item)) return item
                        val inner = messageInside(item, 0)
                        if (inner != null) return inner
                    }
                } catch (_: Throwable) {
                    // skip an unreadable field and keep looking
                }
            }
            c = c.superclass
            hops++
        }
        return null
    }

    /**
     * A message reachable from a menu ITEM, at most [depth] levels deep.
     *
     * ============================== THE SUPERCLASS WALK, AND WHY IT IS THE WHOLE FIX ==============
     *
     * The SOURCES diagnostic measured the item classes and printed NOTHING for their fields:
     *
     *     item ly7.c
     *       fields:
     *
     * That is not "the item has no fields" -- it is "the item declares no fields ITSELF". `ly7.c`,
     * `ly7.i` and `ly7.t` are OBFUSCATED SUBTYPES, and the payload lives on a shared base class. So a
     * `declaredFields` scan alone finds nothing, which is exactly why the first version reported "no
     * message found" while looking straight at the right object.
     *
     * The walk therefore climbs the SUPERCLASS CHAIN at every level, for both accessors and fields.
     * That is the same lesson QQ's reader records for `AbsMsgListVB`: an obfuscated leaf type whose
     * data is declared by its parent is the normal shape in these apps, not an edge case.
     *
     * WHY RECURSIVE AND BOUNDED: a menu item wraps its payload, and the wrapping depth is a build
     * detail. Bounding at 4 levels covers the measured shapes while keeping this cheap -- it runs
     * inside a UI callback, and an unbounded walk over an object graph could stutter the menu.
     *
     * WHY THE RETURN-TYPE / FIELD-TYPE FILTER: only non-primitive, non-String types are followed, so
     * the walk cannot descend into the hundreds of framework references (Context, Drawable, ...) that
     * every view-adjacent object holds. Menu items are `com.*`/`sk4.*` types on this host.
     */
    private fun messageInside(item: Any, depth: Int): Any? {
        if (depth >= 4) return null
        if (access.isMessage(item)) return item

        // Accessors first -- cheap, and reachable through `getMethods()` which already includes the
        // inherited public ones, so no manual walk is needed on this side.
        try {
            for (m in item.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val rt = m.returnType
                if (rt.isPrimitive || rt == String::class.java) continue
                if (!isHostModelType(rt)) continue
                val v = try {
                    m.invoke(item)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (access.isMessage(v)) return v
                messageInside(v, depth + 1)?.let { return it }
            }
        } catch (_: Throwable) {
            // fall through to the field scan
        }

        // Fields, walking the SUPERCLASS CHAIN -- see the class note above.
        var c: Class<*>? = item.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 6) {
            try {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val tn = f.type.name
                    if (f.type.isPrimitive || tn == "java.lang.String") continue
                    if (!isHostModelType(f.type)) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(item)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (access.isMessage(v)) return v
                    messageInside(v, depth + 1)?.let { return it }
                }
            } catch (_: Throwable) {
                // an unreadable level is skipped; the walk continues upward
            }
            c = c.superclass
            hops++
        }
        return null
    }

    /**
     * True when [t] is a type worth descending into.
     *
     * `com.` and `sk4.` are where this host keeps its models: the root read of the live process found
     * the message model as `com.xingin.chatbase.bean.MessageBean` and the menu-item models in `sk4`
     * (`sk4.e`, `sk4.p3`, `sk4.a4`, `sk4.j5`). Everything else -- `android.*`, `java.*`, `kotlin.*` --
     * is framework scaffolding that would make the walk explode without ever holding a message.
     */
    private fun isHostModelType(t: Class<*>): Boolean {
        val n = t.name
        return n.startsWith("com.") || n.startsWith("sk4.")
    }

    /** The menu's own `RecyclerView`: the one whose adapter has the most items. */
    private fun findMenuRecyclerView(root: View): View? {
        val found = ArrayList<View>()
        collectRecyclerViews(root, found, 0)
        // The FIRST in the tree is the menu strip: the structure dump shows it directly under the
        // wrapper, before the (hidden, 0-item) secondary one.
        return found.firstOrNull { view ->
            try {
                val a = view.javaClass.getMethod("getAdapter").invoke(view)
                a != null && (a.javaClass.getMethod("getItemCount").invoke(a) as? Int ?: 0) > 0
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun collectRecyclerViews(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 14) return
        if (v.javaClass.name.contains(RECYCLER_VIEW_MARKER)) out.add(v)
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            g.getChildAt(i)?.let { collectRecyclerViews(it, out, depth + 1) }
        }
    }

    /**
     * Logs every place the message search looked, once, when nothing was found.
     *
     * This is the diagnostic that makes the next round a fix rather than a guess: it names the menu's
     * adapter, its data list, and the class of each item plus whatever that item exposes. Whatever the
     * real message model turns out to be, its name appears in this output.
     */
    private fun logMenuSources(root: View) {
        val key = "sources:" + System.identityHashCode(root)
        if (!logged.add(key)) return
        val sb = StringBuilder("xhs SOURCES: searching the menu for the message found nothing.\n")
        try {
            val rv = findMenuRecyclerView(root)
            sb.append("  menu RecyclerView: ")
                .append(rv?.javaClass?.name ?: "<none found>").append('\n')
            if (rv != null) {
                val adapter = rv.javaClass.getMethod("getAdapter").invoke(rv)
                sb.append("  adapter: ").append(adapter?.javaClass?.name ?: "null").append('\n')
                if (adapter != null) {
                    var c: Class<*>? = adapter.javaClass
                    var hops = 0
                    while (c != null && c != Any::class.java && hops < 4) {
                        for (f in c.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            if (!Collection::class.java.isAssignableFrom(f.type)) continue
                            f.isAccessible = true
                            val list = try {
                                f.get(adapter) as? Collection<*>
                            } catch (_: Throwable) {
                                null
                            }
                            sb.append("  list .").append(f.name).append(" : ")
                                .append(f.type.simpleName)
                                .append(" size=").append(list?.size ?: -1).append('\n')
                            list?.take(3)?.forEach { item ->
                                if (item == null) return@forEach
                                sb.append("     item ").append(item.javaClass.name)
                                sb.append(" isMessage=").append(access.isMessage(item)).append('\n')
                                // THE SUPERCLASS CHAIN IS DUMPED, not just `declaredFields`. The first
                                // version printed `fields:` EMPTY for `ly7.c` / `ly7.i` / `ly7.t` -- the
                                // items declare nothing themselves and inherit everything -- which made
                                // the log look like "these objects have no data" when the truth was
                                // "the data is one level up". A diagnostic that hides the answer is
                                // worse than none, because it sends the next round the wrong way.
                                var ic: Class<*>? = item.javaClass
                                var ih = 0
                                while (ic != null && ic != Any::class.java && ih < 5) {
                                    sb.append("       ").append(ic.name).append(": ")
                                    sb.append(
                                        try {
                                            ic.declaredFields
                                                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                                                .joinToString(",") { it.name + ":" + it.type.simpleName }
                                                .take(400)
                                        } catch (_: Throwable) {
                                            "<unreadable>"
                                        },
                                    ).append('\n')
                                    ic = ic.superclass
                                    ih++
                                }
                            }
                        }
                        c = c.superclass
                        hops++
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("  <search threw ").append(t.javaClass.simpleName).append(": ")
                .append(t.message).append(">\n")
        }
        Log.w(TAG, sb.toString())
    }

    /**
     * Logs the menu's arguments so the message model can be identified from a REAL device.
     *
     * ============================== WHY THIS EXISTS ==============================
     * The message model is the one thing recon could not settle: the reader must be handed the object
     * Xiaohongshu builds its menu from, and that object's class was not pinned from the APK alone --
     * the candidates live in the `sk4` package, whose members carry no readable field names of their
     * own, so a dex scan cannot say which one a long-press actually passes.
     *
     * Rather than guess, this prints, for EVERY argument:
     *   * its exact class name;
     *   * whether the reader already accepts it;
     *   * the no-argument accessors it exposes that look like message data (id / content / text /
     *     time / send), which is what the reader needs to bind to.
     *
     * One long-press then yields the answer in the module's own log, which the user can hand over.
     * Logged ONCE per argument-class signature, because the menu is rebuilt on every long-press and an
     * unconditional dump would flood the file.
     */
    private fun logArgProbe(args: Array<Any?>) {
        if (args.isEmpty()) return
        val sig = args.joinToString(",") { it?.javaClass?.name ?: "null" }
        if (!logged.add("probe:" + sig)) return
        val sb = StringBuilder()
        sb.append("xhs PROBE: menu args (").append(args.size).append("):")
        for ((i, a) in args.withIndex()) {
            if (a == null) {
                sb.append("\n   [").append(i).append("] null")
                continue
            }
            sb.append("\n   [").append(i).append("] ").append(a.javaClass.name)
            if (access.isMessage(a)) sb.append("   <== ACCEPTED as the message")
            sb.append(access.describeAccessors(a))
        }
        Log.w(TAG, sb.toString())
    }

    /**
     * Dumps the FULL structure of a candidate menu once, including any RecyclerView's adapter.
     *
     * ============================== WHY THIS IS NEEDED ==============================
     *
     * The `m4` hook installs cleanly and NEVER FIRES -- the device log shows the install line and then
     * nothing, ever, however many times the user long-presses. So `m4` is not the row builder despite
     * its shape, and guessing again from the APK is how the last two rounds were wasted.
     *
     * The crash stack already revealed the real structure:
     *
     *     NullPointerException: RecyclerView$ViewHolder.shouldIgnore() on a null reference
     *         at RecyclerView.findMinMaxChildLayoutPositions
     *
     * i.e. THE MENU CONTAINS A RECYCLERVIEW. A RecyclerView menu is populated through an ADAPTER, not
     * by adding child views -- and the recon already found the adapter's likely class,
     * `com.xingin.im.ui.widgets.pressmenu.itembinder.LongPressMenuItemBinder`, which declares
     * `onCreateViewHolder` and `onBindViewHolder` and a constructor taking `(n, MsgUIData)`.
     *
     * So the injection point is the adapter, and to hook it correctly this module needs three facts
     * that only the LIVE tree can give:
     *   1. the concrete RecyclerView class inside the menu;
     *   2. its ADAPTER's concrete class (that is what gets hooked);
     *   3. the adapter's DATA LIST field (that is what a new entry is appended to).
     *
     * Dumped ONCE per root, because this walks every node and reflects into adapters -- far too
     * expensive to repeat on every frame, and one dump answers the question for this build.
     */
    private fun dumpMenuStructure(root: View, why: String) {
        if (!structureDumped.add(root)) return
        if (structureDumped.size > 6) return
        val sb = StringBuilder()
        sb.append("xhs STRUCTURE (").append(why).append("): the menu tree is\n")
        appendNode(sb, root, 0)
        appendAdapterInfo(sb, root, 0)
        Log.w(TAG, sb.toString())
    }

    /** One line per view: indent, class, id, size, and text when it has any. */
    private fun appendNode(sb: StringBuilder, v: View, depth: Int) {
        if (depth > 14) return
        repeat(depth) { sb.append("  ") }
        sb.append(v.javaClass.name)
        try {
            val id = v.id
            if (id != -1) {
                sb.append(" id=").append(
                    try {
                        v.resources.getResourceEntryName(id)
                    } catch (_: Throwable) {
                        "0x" + Integer.toHexString(id)
                    },
                )
            }
        } catch (_: Throwable) {
            // id unreadable; keep the class name
        }
        sb.append(' ').append(v.width).append('x').append(v.height)
        if (v.visibility != View.VISIBLE) sb.append(" vis=").append(v.visibility)
        if (v is TextView) {
            val t = v.text?.toString()?.trim() ?: ""
            if (t.isNotEmpty()) sb.append(" text='").append(t.take(24)).append('\'')
        }
        if (v is android.view.ViewGroup) {
            sb.append(" n=").append(v.childCount)
            sb.append(" (").append(v.javaClass.superclass?.simpleName).append(')')
        }
        sb.append('\n')
        val g = v as? android.view.ViewGroup ?: return
        for (i in 0 until g.childCount) {
            g.getChildAt(i)?.let { appendNode(sb, it, depth + 1) }
        }
    }

    /**
     * For every RecyclerView in the tree: its adapter class and the adapter's candidate data fields.
     *
     * This is the part that names the injection point. `getAdapter()` is public API on RecyclerView, so
     * the adapter class is read without guessing; the adapter's own fields are then listed so the DATA
     * LIST a menu item is appended to can be identified by name.
     */
    private fun appendAdapterInfo(sb: StringBuilder, v: View, depth: Int) {
        if (depth > 14) return
        if (v.javaClass.name.contains(RECYCLER_VIEW_MARKER)) {
            sb.append("  >> RECYCLERVIEW ").append(v.javaClass.simpleName)
            try {
                val m = v.javaClass.getMethod("getAdapter")
                val adapter = m.invoke(v)
                if (adapter == null) {
                    sb.append(" adapter=NULL (not set yet)\n")
                } else {
                    sb.append(" adapter=").append(adapter.javaClass.name)
                    try {
                        val cnt = adapter.javaClass.getMethod("getItemCount").invoke(adapter)
                        sb.append(" itemCount=").append(cnt)
                    } catch (_: Throwable) {
                        sb.append(" itemCount=<unreadable>")
                    }
                    sb.append('\n')
                    // The data list. Listed by name with each field's type, so the list that holds the
                    // menu items is identifiable -- on this host the item model is a per-row object,
                    // so the target is a List/Collection field on the adapter.
                    try {
                        for (f in adapter.javaClass.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            val tn = f.type.name
                            val interesting = Collection::class.java.isAssignableFrom(f.type) ||
                                Map::class.java.isAssignableFrom(f.type) ||
                                tn.startsWith("com.xingin") || tn.startsWith("java.util")
                            if (!interesting) continue
                            f.isAccessible = true
                            val value = try {
                                f.get(adapter)
                            } catch (_: Throwable) {
                                null
                            }
                            sb.append("       .").append(f.name).append(" : ")
                                .append(f.type.simpleName)
                            if (value is Collection<*>) {
                                sb.append(" size=").append(value.size)
                                val first = value.firstOrNull()
                                if (first != null) {
                                    sb.append(" first=").append(first.javaClass.name)
                                }
                            } else if (value != null) {
                                sb.append(" = ").append(value.toString().take(60))
                            }
                            sb.append('\n')
                        }
                    } catch (t: Throwable) {
                        sb.append("       <fields unreadable: ").append(t.javaClass.simpleName).append(">\n")
                    }
                }
            } catch (t: Throwable) {
                sb.append(" adapter=<getAdapter threw ").append(t.javaClass.simpleName).append(">\n")
            }
        }
        val g = v as? android.view.ViewGroup ?: return
        for (i in 0 until g.childCount) {
            g.getChildAt(i)?.let { appendAdapterInfo(sb, it, depth + 1) }
        }
    }

    /** The anchor row's label `TextView`, so our label can copy its colour and size. */
    private fun findLabelView(v: View): TextView? {
        if (v is TextView) return v
        val g = v as? ViewGroup ?: return null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is TextView) {
                val t = c.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty()) return c
            }
        }
        return null
    }

    /** [host]'s text size in SP, or [fallback] when there is no host label to copy from. */
    private fun hostTextSizeOr(host: TextView?, fallback: Float): Float {
        if (host == null) return fallback
        // getTextSize() returns PIXELS; the TextView setter takes SP. Converting with the display's
        // scaled density keeps the two consistent -- passing raw pixels is a classic way to end up with
        // text 2.75x too large.
        val density = host.resources.displayMetrics.scaledDensity
        return if (density > 0f) host.textSize / density else host.textSize
    }

    /** The anchor's `layoutParams`, cloned, or a sane default when it has none yet. */
    private fun copyLpFor(src: View): ViewGroup.LayoutParams {
        val orig = src.layoutParams
        if (orig != null) {
            // A fresh instance, not the same object: sharing one LayoutParams between two views makes
            // the second one's measurement overwrite the first's, and both end up wrong.
            return when (orig) {
                is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(orig)
                is android.widget.FrameLayout.LayoutParams ->
                    android.widget.FrameLayout.LayoutParams(orig)
                else -> ViewGroup.LayoutParams(orig.width, orig.height)
            }
        }
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * The user tapped 分析: analyse the message the menu was built for.
     *
     * @param row OUR row, used as the anchor the scope dialog is positioned against. Passing it is
     *   what makes the dialog appear beside the button the user actually pressed -- the earlier
     *   call shape without a view is why a dialog could open behind the menu or at a screen corner.
     */
    private fun onOurRowTapped(row: View) {
        val msg = pendingMessage
        if (msg == null) {
            note(
                "tap-no-msg",
                "xhs: 分析 was tapped but no message was captured when the menu was built, so there " +
                    "is nothing to analyse. The menu's arguments did not contain a class the reader " +
                    "recognised -- see the PROBE output above for what the reader actually saw.",
            )
            return
        }
        try {
            Log.i(TAG, "xhs: starting analysis for " + msg.javaClass.name)
            controller.startAnalysisFor(msg, row)
        } catch (t: Throwable) {
            note("analysis-threw:" + t.javaClass.simpleName, "xhs: starting the analysis threw: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ popup anchors

    private fun installPopupAnchors() {
        var n = 0
        val pw = try {
            Class.forName("android.widget.PopupWindow", false, resolver.classLoader())
        } catch (_: Throwable) {
            null
        }
        if (pw == null) {
            note("no-popupwindow", "xhs: android.widget.PopupWindow is not loadable; popup anchors skipped")
            return
        }
        for (sig in POPUP_ANCHORS) {
            val m = findMethod(pw, sig.name, sig.argCount) ?: continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .interceptAfter { chain ->
                        try {
                            // AFTER, so the popup has been shown and may be laid out. Read the content
                            // view -- that is the menu tree -- and inject when it looks like a menu.
                            val content = popupContentView(chain.thisObject)
                            if (content != null) tryInject(content, "PopupWindow#" + sig.name)
                        } catch (t: Throwable) {
                            note(
                                "popup-threw:" + t.javaClass.simpleName,
                                "xhs: the popup anchor threw and was swallowed: " + t.message,
                            )
                        }
                    }
                n++
                Log.i(TAG, "xhs: hooked PopupWindow#" + sig.name + " (" + sig.argCount + " args)")
            } catch (t: Throwable) {
                note("popup-hook-failed:" + sig.name, "xhs: hooking PopupWindow#" + sig.name + " failed: ${t.message}")
            }
        }
        if (n == 0) {
            note(
                "no-popup-anchors",
                "xhs: NONE of the PopupWindow show methods could be hooked, so the popup route is " +
                    "unavailable and only the window probe remains",
            )
        }
    }

    /** A `PopupWindow`'s content view, read reflectively so no host class is imported. */
    private fun popupContentView(popup: Any?): View? {
        if (popup == null) return null
        try {
            val m = popup.javaClass.getMethod("getContentView")
            return m.invoke(popup) as? View
        } catch (_: Throwable) {
            return null
        }
    }

    // ------------------------------------------------------------------ window probe

    /**
     * The fallback of last resort: watch the framework's own root-view list.
     *
     * WHY IT IS KEPT even though the popup anchors work today: on QQ this was the ONLY route that
     * reached the menu, and it is the one mechanism that does not depend on the host using a standard
     * `PopupWindow` at all. A Xiaohongshu release that switches to a custom popup would otherwise take
     * the entry from "works" to "not installed" with no fallback.
     */
    private fun installWindowProbe() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val seen = HashSet<String>()
        val run = object : Runnable {
            @Volatile var tries = 0
            override fun run() {
                tries++
                if (tries > 7200) return   // ~10 minutes at 500ms, then stop for good
                try {
                    val list = rootViews()
                    for (v in list) {
                        val view = v as? View ?: continue
                        // Route the probe through tryInject so it shares EVERY guard the popup path
                        // has -- the size test, the structure dump, the anchor requirement and the
                        // RecyclerView refusal. Calling onMenuBuilt directly (as the first version
                        // did) skipped all of them, which is how a text-bearing container inside a
                        // RecyclerView became an insertion target and crashed the host.
                        tryInject(view, "window-list")
                    }
                } catch (_: Throwable) {
                    // never let the probe take the host down
                }
                h.postDelayed(this, 500L)
            }
        }
        h.postDelayed(run, 500L)
        Log.i(TAG, "xhs WINDOW-PROBE: watching the framework's root-view list (fallback route)")
    }

    /** The framework's live root views, read from `WindowManagerGlobal.mViews`. */
    private fun rootViews(): List<*> = try {
        val cls = Class.forName("android.view.WindowManagerGlobal", false, resolver.classLoader())
        val inst = cls.getMethod("getInstance").invoke(null)
        val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
        (f.get(inst) as? List<*>) ?: emptyList<Any>()
    } catch (_: Throwable) {
        emptyList<Any>()
    }

    // ------------------------------------------------------------------ shared injection

    /**
     * Tries to treat [content] as the long-press menu and inject into it.
     *
     * Bounded PER WINDOW, and that is not a detail: on QQ a single shared counter was consumed by the
     * screen's DecorView -- which can never hold a menu row -- so by the time the real menu appeared the
     * budget was spent and the injection never ran. The retry exists because the menu's window is
     * created BEFORE its rows are populated.
     */
    private fun tryInject(content: View, via: String) {
        val root = content as? ViewGroup ?: return

        // Already injected? By tag, which is exact.
        try {
            if (root.findViewWithTag<View>(XiaohongshuMenuIdentity.OUR_ROW_TAG) != null) return
        } catch (_: Throwable) {
            return
        }

        // A menu is a SMALL popup, not the whole screen. Without this test the screen's own DecorView
        // -- which contains the chat list and every sticker panel -- would be dumped and searched,
        // producing a huge meaningless log and, worse, an insertion target that crashes the host.
        // The measured menu is 396x1050 in a 1272-wide screen, so a screen-sized root is not a menu.
        if (!looksLikeMenuWindow(root)) return

        // Dump the structure ONCE, so the adapter and its data list are named from the live tree
        // rather than inferred from the APK.
        dumpMenuStructure(root, via)

        // Only a tree that actually contains host menu rows is worth working on. This is also what
        // keeps the WHOLE-SCREEN root out: the chat page contains message rows (also short labels),
        // but no label from the menu's own set, so nothing here matches and nothing is inserted.
        val hostRow = findHostRowForGeometry(root) ?: run {
            noteOncePerRoot(
                root, "no-rows",
                "xhs: the popup at " + via + " (" + root.javaClass.simpleName + " " + root.width +
                    "x" + root.height + ") contains no recognisable menu row, so 分析 was not added " +
                    "there. See the STRUCTURE dump above for what it does contain.",
            )
            return
        }

        val prev = popupAttempts[content] ?: 0
        val attempt = prev + 1
        popupAttempts[content] = attempt
        if (attempt > MAX_ATTEMPTS) return

        onMenuBuilt(null, arrayOfNulls(0), rootOverride = root)
        // If the injection still did not happen the host may be mid-layout, so retry shortly.
        val now = try {
            root.findViewWithTag<View>(XiaohongshuMenuIdentity.OUR_ROW_TAG) != null
        } catch (_: Throwable) {
            true
        }
        if (!now && hostRow.width <= 0) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.postDelayed({ tryInject(root, via + "/retry") }, RETRY_MS)
        }
    }

    /**
     * True when a root view is plausibly the long-press MENU rather than the whole screen.
     *
     * WHY SIZE IS THE TEST: the menu is measured at 396x1050 inside a 1272x2772 screen. A root that
     * fills the screen is the Activity's own DecorView -- never a menu -- and treating it as one makes
     * the anchor search wander into the chat list. Requiring the root to be clearly SMALLER than the
     * screen is a one-line test that removes the entire class of "injected into the wrong container"
     * failures, and it cannot reject a real menu: a popup menu is always a fraction of the screen.
     *
     * The bound is deliberately generous (90% of either dimension) so a future menu that grows does
     * not silently stop being recognised.
     */
    private fun looksLikeMenuWindow(root: View): Boolean {
        val dm = root.resources?.displayMetrics ?: return true
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        if (root.width <= 0 || root.height <= 0) return true      // not measured yet; allow the retry
        val tooWide = root.width >= sw * 0.9f
        val tooTall = root.height >= sh * 0.9f
        return !(tooWide && tooTall)
    }

    /** Notes a message at most once per root view, so a repeating cause cannot flood the log. */
    private fun noteOncePerRoot(root: View, key: String, message: String) {
        if (notedRoots.add(System.identityHashCode(root).toString() + ":" + key)) {
            Log.w(TAG, message)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, resolver.classLoader())
    } catch (_: Throwable) {
        null
    }

    /** The `MenuView` class, resolved lazily and cached so the hot path does not re-look it up. */
    @Volatile
    private var menuViewCls: Class<*>? = null
    private fun menuViewClass(): Class<*>? {
        menuViewCls?.let { return it }
        val c = loadClass(XiaohongshuMenuIdentity.MENU_VIEW)
        menuViewCls = c
        return c
    }

    /** The `MenuItemView` class, resolved lazily and cached. */
    @Volatile
    private var itemViewCls: Class<*>? = null
    private fun itemViewClass(): Class<*>? {
        itemViewCls?.let { return it }
        val c = loadClass(XiaohongshuMenuIdentity.ITEM_VIEW)
        itemViewCls = c
        return c
    }

    /** A compact `[i]ClassName` listing of a hook's arguments, for the failure logs. */
    private fun describeArgs(args: Array<Any?>): String =
        args.joinToString(" ") { a -> "[" + (a?.javaClass?.name ?: "null") + "]" }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? = try {
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == argCount }
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

    private fun treeSummary(v: View, depth: Int): String {
        if (depth > 6) return ""
        val sb = StringBuilder()
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
        sb.append(v.javaClass.simpleName)
        if (v is TextView) {
            val s = v.text?.toString()?.trim() ?: ""
            if (s.isNotEmpty()) sb.append(" text='").append(s.take(16)).append('\'')
        }
        val g = v as? ViewGroup
        if (g != null && g.childCount > 0) {
            sb.append(" n=").append(g.childCount)
            for (i in 0 until minOf(g.childCount, 8)) {
                g.getChildAt(i)?.let { sb.append(treeSummary(it, depth + 1)) }
            }
        }
        return sb.toString()
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Logs once per signature, so one repeated cause cannot hide a new one. */
    private fun note(key: String, message: String) {
        if (logged.add(key)) Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "LuoLiao"

        /** Retry budget per menu window, and the gap between tries. */
        const val MAX_ATTEMPTS = 20
        const val RETRY_MS = 100L

        /**
         * The window class marker for the fallback probe.
         *
         * Matched as a SUBSTRING rather than a full name: the outer/inner split is an implementation
         * detail that can move, while the feature's own name is stable. On Xiaohongshu the menu is a
         * plain `PopupWindow`, whose framework-internal root is `PopupWindow$PopupDecorView`, so this
         * marker is what the probe keys on -- the popup anchors are the primary route.
         */
        const val MENU_WINDOW_MARKER = "PopupWindow"

        /**
         * The marker that identifies a RecyclerView by class name, matched as a SUBSTRING.
         *
         * WHY BY NAME AND NOT `is RecyclerView`: the host may use a subclass (or an AndroidX-shaded
         * one) that does not share the identity this module's compile-time class does. Loading
         * `androidx.recyclerview.widget.RecyclerView` from the host's loader and testing
         * `isInstance` would work in principle, but it introduces a dependency on the host shipping
         * that exact class -- while the class NAME is what actually determines the fatal behaviour
         * (any RecyclerView refuses a child without a ViewHolder). A substring test on the name
         * cannot fail to match a subclass, which is the property needed here.
         */
        const val RECYCLER_VIEW_MARKER = "RecyclerView"

        /**
         * The label colour used ONLY when no host label could be copied.
         *
         * WHY THIS IS A FALLBACK AND NOT THE VALUE: the first version hardcoded a dark colour and the
         * user saw the icon with NO TEXT, because Xiaohongshu's menu is themed and a dark theme makes a
         * dark label invisible. The host's own label colour is copied whenever one is reachable; this
         * constant only covers the case where the anchor row had no `TextView` at all, and it is a mid
         * grey chosen to stay legible on both a light and a dark background rather than on just one.
         */
        const val DEFAULT_LABEL_COLOR = 0xFF888888.toInt()

        /** The `PopupWindow` methods every Android version routes a popup's display through. */
        val POPUP_ANCHORS = listOf(
            PopupAnchor("showAtLocation", 4),
            PopupAnchor("showAtLocation", 3),
            PopupAnchor("showAsDropDown", 1),
            PopupAnchor("showAsDropDown", 3),
            PopupAnchor("showAsDropDown", 4),
        )
    }

    private data class PopupAnchor(val name: String, val argCount: Int)
}
