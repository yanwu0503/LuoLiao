package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.LegacyHook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AdapterView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.ui.NukeTheme
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Injects the "analyze" entry into WeChat's chat message long-press menu and makes it
 * respond to taps.
 *
 * === INJECTION (works since v1.0.8) ===
 * The chat menu is built by methods taking BOTH the menu and the message (25 verified in
 * the 8.0.77 APK); we add our item via nf5.g4.c(int,int,int,CharSequence,int).
 *
 * === CLICK HANDLING: catch the row as it is created ===
 * The button IS visible on screen, so two platform events are guaranteed to happen:
 *     TextView.setText(<our title>)   and   View.onAttachedToWindow()
 * Hooking those needs no WeChat class name, so we obtain the live row view and attach
 * our own OnClickListener to it. Every previous attempt tried to predict WeChat's
 * dispatch (MenuItem listener / nf5.h4.c() / AdapterView.performItemClick /
 * dialog.l1.onBindViewHolder) and all stayed silent on a real tap.
 */
class MenuHook(
    
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    companion object {
        private const val TAG = "LuoLiao"
        private const val ITEM_ID = Constants.ITEM_ID
        private const val ITEM_TITLE = Constants.ITEM_TITLE

        /** item NEW: the balloon on/off entry, added alongside 分析. */
        private const val BALLON_ITEM_ID = Constants.BALLON_ITEM_ID
        private const val BALLON_ITEM_TITLE = Constants.BALLON_ITEM_TITLE

        /**
         * item NEW: every title WE inject.
         *
         * TWO entries now live in one menu, so "does this subtree contain our label?" is no longer enough
         * to identify a tap -- it has to say WHICH of our two entries was hit. These are compared against
         * a row's labels to decide that, and the check is ordered so 分析 can never be mistaken for the
         * balloon switch: see rowActionOf().
         */
        private val OWN_MENU_TITLES: List<String> = Constants.OWN_MENU_TITLES

        private const val MENU_ADAPTER = "com.tencent.mm.ui.widget.dialog.l1"

        /** Icon edge length in dp, matching a normal menu glyph. */
        private const val ICON_SIZE_DP = 20


        private val SCAN_DELAYS = longArrayOf(100L, 250L, 500L, 900L, 1600L, 2600L)

        /**
         * Labels of WeChat's OWN menu items.
         *
         * A view whose subtree contains any of these is a shared container, not our row, so it
         * must never be hijacked: doing so made 复制 / 转发 / 删除 / 多选 all run the analysis.
         */
        private val OTHER_MENU_LABELS = listOf(
            "复制",
            "转发",
            "删除",
            "多选",
            "收藏",
            "撤回",
            "引用",
            "编辑",
            "提醒",
            "译文",
        )

        @Volatile
        private var pendingMsg: Any? = null

        @Volatile
        private var pendingView: View? = null

        /** Guards against re-hijacking the same view repeatedly. */
        private val hijacked = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<View, Boolean>()
        )

        /**
         * v3.1.1：「host + 这一项的身份」级别的去重槽位。
         *
         * 【为什么不能只用 hijacked 那一层】hijacked 按 **View 身份**去重。两个菜单项如果落在同一个
         * host 容器上（微信会复用行 View），第二项会被 `!hijacked.add(host)` 直接挡掉、连 listener 都
         * 绑不上 —— 于是点「分析」跑到悬浮球开关的分支去。
         *
         * 这里改成按「host + action」去重：同一个 host 上，两项各自合法地保留自己的监听意图；
         * 而同一项对同一 host 重复注册仍会被挡住，不会堆积监听。
         *
         * 用 WeakHashMap 保证不阻止 host 被回收（微信会不断重建菜单行）。
         */
        private val hijackedSlots = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<String, Boolean>())
        )

        private val lastView = ThreadLocal<View?>()

        private val injected = AtomicInteger(0)

        /** v2.7.0 (item 2): the resolved selected-messages getter, from Resolver.multiSelectBarMethods(). */
        @Volatile
        var multiSelectGetter: java.lang.reflect.Method? = null

        /** v2.7.0 (item 2): guards against adding the 分析 button twice; the bar rebuilds its children. */
        const val TAG_MULTI_BUTTON = "luoliao_multi_analyse"

        /**
         * v3.0.2: the key the button is CACHED under on the bar itself (WeKit uses an R.id for this).
         *
         * WeChat's bar calls removeAllViews() on every rebuild, which would drop our button. WeKit keeps a
         * reference in the bar's tag bag and re-attaches it after each rebuild; this is our equivalent key.
         * A stable constant is required -- generateViewId() would produce a new value per attempt.
         */
        const val MULTI_BUTTON_TAG_ID = 0x4C4C0001 // "LL" + 1
        private val setTextHits = AtomicInteger(0)
        private val attachHits = AtomicInteger(0)
        private val scans = AtomicInteger(0)
        private val taps = AtomicInteger(0)
        private val clickProbe = AtomicInteger(0)

        /** v2.5.2: rate-limits a per-event log line so the log stays readable. */
        private val rowAttachLogged = java.util.concurrent.atomic.AtomicInteger(0)

        /** v2.5.2: counts the skip-hijack decisions so they can be rate-limited instead of spamming the log. */
        private val skipHijackLogged = AtomicInteger(0)

        /**
         * item 1: the most recent message object seen.
         *
         * WeChat's contributor interface passes ONLY the menu, so a contributor cannot hand us the message.
         * The long-press and item hooks record it here, and a menu-only injection falls back to it. Null is
         * acceptable: the analysis reads the conversation from the history store anyway.
         */
        val lastMessage = ThreadLocal<Any?>()

        /**
         * v2.4.0: the long-pressed row's View tag.
         *
         * WeKit reads the message from `view.tag` -- the real method takes (menu, View) and carries no message
         * parameter at all. Recorded here so the click handler can identify which message was chosen.
         */
        val lastTag = ThreadLocal<Any?>()

        /**
         * v2.4.2: THE LONG-PRESSED ROW, kept separately from `lastView`.
         *
         * `lastView` is written by several hooks (contributors, onAttachedToWindow, the performClick probe),
         * so by the time the user taps the menu item it holds some OTHER view -- which is why the click
         * reported `msg=null view=MMNeat7extView` and the UI said 未能识别该消息.
         *
         * WeKit keeps a dedicated reference written ONLY by the menu builder and cleared on dismiss. This is
         * that reference. It is strong, not weak, because the menu lives only seconds and a weak reference can
         * be collected between the long press and the tap.
         */
        @Volatile
        var pressedView: View? = null

        /** The tag of the long-pressed row, captured with the view. */
        @Volatile
        var pressedTag: Any? = null

        /** The MESSAGE of the long-pressed row, extracted immediately (WeKit does this at the same moment). */
        @Volatile
        var pressedMsg: Any? = null

        /**
         * v2.3.1: menu instances already injected into.
         *
         * `add` is called many times while a single menu is built, so without this the item would be added
         * again on every call. An identity set (not equals) keeps it to one per menu.
         */
        private val injectedMenus =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

        fun markInjectedOnce(menu: Any): Boolean =
            try {
                injectedMenus.add(menu)
            } catch (_: Throwable) {
                false
            }

        /** Debounce: one logical tap must not start several analyses. */
        @Volatile
        private var lastTapAt = 0L

        private const val TAP_DEBOUNCE_MS = 700L
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * item NEW: the row whose click listener is currently armed.
     *
     * `pressedView` is the long-pressed MESSAGE row, which is a different object entirely -- the menu
     * row lives in the popup. Resolving "which of our two entries was tapped" needs the MENU row, so it
     * is remembered here when hijack() attaches the listener.
     */
    @Volatile
    private var armedRow: View? = null

    private val knownContributors = listOf(
        "com.tencent.mm.ui.chatting.viewitems.g5",
        "com.tencent.mm.ui.chatting.viewitems.cf",
        "com.tencent.mm.ui.chatting.viewitems.if",
        "com.tencent.mm.ui.chatting.viewitems.k6",
        "com.tencent.mm.ui.chatting.viewitems.se",
        "com.tencent.mm.ui.chatting.viewitems.r0",
        "com.tencent.mm.ui.chatting.viewitems.n0",
        "mj5.l5", "mj5.t5", "mj5.ba", "mj5.h8", "mj5.j6", "mj5.ja",
        "mj5.o", "mj5.p4", "mj5.qa", "mj5.r6", "mj5.w", "mj5.w4",
        "dl5.z1", "sr4.y",
    )

    fun install() {
        Log.i(TAG, "MenuHook(v1.0.12b) install START")

        val points = try {
            resolver.resolve()
        } catch (t: Throwable) {
            Log.e(TAG, "resolve() failed", t)
            return
        }
        val menuCls = points.menuClass
        val msgCls = points.msgClass
        Log.i(TAG, "MenuHook(v1.0.12b) menu=" + (menuCls?.name ?: "null") +
            " msg=" + (msgCls?.name ?: "null"))

        var det = 0
        det += guarded("TextView.setText") { installSetTextHook() }
        det += guarded("onAttachedToWindow") { installAttachHook() }
        det += guarded("performClick probe") { installPerformClickProbe() }
        det += guarded("performLongClick") { installPerformLongClick() }
        det += guarded("nf5.h4.c()") { installItemDispatcher("nf5.h4") }
        det += guarded("AdapterView funnel") { installAdapterViewFunnel() }
        Log.i(TAG, "DETECTOR HOOKS INSTALLED: " + det)

        if (menuCls == null) {
            Log.w(TAG, "menu class unresolved; injection disabled")
        } else {
            // v2.4.0 FIRST: WeKit's hook -- the chat menu builder itself, anchored by two strings.
            // v2.7.0 (item 2): the multi-select toolbar button.
            guarded("multi-select button") { installMultiSelectHook() }

            val builder = try {
                resolver.chatMenuBuilder()
            } catch (_: Throwable) {
                null
            }
            if (builder != null) {
                guarded("chat menu builder") { installChatMenuBuilderHook(builder) }
            } else {
                Log.w(TAG, "chatMenuBuilder unresolved; falling back to the menu-class add() hook")
            }

            // v2.3.1: the backup point -- WeChat must call add() to build any menu.
            val mc = guarded("menu class add()") { installMenuClassHook(menuCls) }
            if (mc == 0) {
                Log.w(TAG, "no add-method could be hooked on " + menuCls.name)
            }
        }

        if (menuCls == null || msgCls == null) {
            Log.w(TAG, "menu or message class unresolved; injection disabled")
        } else {
            val c = guarded("contributors") { hookContributors(menuCls, msgCls) }
            Log.i(TAG, "CONTRIBUTOR HOOKS INSTALLED: " + c)
        }

        Log.i(TAG, "MenuHook(v1.0.12b) install END")
    }

    /**
     * item 1 (v2.2.0): NUKE'S HOOK, by SHAPE rather than by name.
     *
     * Finds every class that declares `onCreateMMMenu` with a single parameter assignable to the menu class,
     * and hooks it. This is the population WeChat actually calls while building the long-press menu, so the
     * item is added by WeChat's own pass.
     *
     * The class list comes from the module's existing DexKit resolver when available, and from a scan of the
     * loaded WeChat packages otherwise, so no obfuscated names are needed.
     */
    /**
     * v2.3.1: THE RELIABLE INJECTION POINT -- the menu class itself.
     *
     * WHY THIS REPLACES RELYING ON CONTRIBUTORS: the module resolved 414 classes that DECLARE
     * `onCreateMMMenu` and hooked all of them, yet `menuAttempts` stayed 0 -- they belong to other screens
     * (SNS, finder, chatroom) and are simply not on the chat long-press path.
     *
     * But WeChat CANNOT show a menu without calling `add(...)` on the menu object, and that object is already
     * resolved: `menu=kj5.i4` on 8.0.78, `nf5.g4` on 8.0.77 -- in every version a class implementing
     * `android.view.ContextMenu`. Hooking its add-methods fires exactly when the menu is being built,
     * whichever contributor ran or not. That is why Nuke's item always appears.
     *
     * A guard keeps the injection to ONCE per menu instance, so the menu is not spammed with copies.
     */
    /**
     * v2.4.0: WEKIT'S HOOK -- the chat menu builder, called with (menu, View).
     *
     * WeKit's onEnable:
     *
     *     methodCreateMenu.hookBefore {
     *         val menu = args[0]!!
     *         val curView = args[1] as View
     *         val tag = curView.tag                      <- the message is HERE, not a parameter
     *         val addMenuItem = menu.reflekt().firstMethod {
     *             parameters(Int::class, CharSequence::class, Drawable::class)
     *             returnType = android.view.MenuItem::class
     *         }
     *         addMenuItem.invoke(id, text, drawable)
     *     }
     *
     * Two corrections over the previous implementation, and they are the reason nothing ever appeared:
     *   * the message is read from the pressed View's TAG, not from a method parameter;
     *   * the item is added with the Drawable overload invoked REFLECTIVELY on the menu object.
     */
    /**
     * v2.9.0: WEKIT'S HOOK, verbatim in shape.
     *
     *     methodCreateMenu.hookBefore {
     *         val menu = args[0]!!
     *         val curView = args[1] as View
     *         val addMenuItem = menu.reflekt().firstMethod {
     *             parameters(Int::class, CharSequence::class, Drawable::class)
     *             returnType = android.view.MenuItem::class
     *         }
     *         addMenuItem.invoke(item.id, item.text, item.drawable)
     *     }
     *
     * The item is added BEFORE WeChat's own body runs (hookBefore), through the Drawable overload ONLY, with a
     * Drawable built from our icon so the item always has one. No fallback path exists: a fallback would change
     * which method receives the item and is how one device ends up working while another does not.
     */
    private fun installChatMenuBuilderHook(builder: java.lang.reflect.Method): Int {
        return try {
            LegacyHook.hook(builder)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    // ---- WeKit's body, run BEFORE the original method.
                    try {
                        val menu = chain.getArg(0)
                        val view = chain.getArg(1) as? View
                        if (view != null) {
                            val tag = view.tag
                            if (tag != null) lastTag.set(tag)
                            lastView.set(view)
                            com.luoliaoo.wechat.core.AppContextHolder.remember(view.context)
                            val extracted = msgFromTag(tag)
                            if (extracted != null) pressedMsg = extracted
                            pressedView = view
                        }
                        if (menu != null && markInjectedOnce(menu)) {
                            Log.i(TAG, "CHAT MENU BUILDER fired -> injecting (view=" +
                                (view?.javaClass?.simpleName ?: "null") + ")")
                            injectWekitStyle(menu, view)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "chat menu builder injection failed: " + t.message)
                    }
                    chain.proceed()
                }
            Log.i(TAG, "CHAT MENU BUILDER HOOKED: " + builder.declaringClass.name + "." + builder.name)
            com.luoliaoo.wechat.core.ModuleStats.menuHookInstalled = true
            1
        } catch (t: Throwable) {
            Log.w(TAG, "hooking the chat menu builder failed: " + t.message)
            0
        }
    }

    /**
     * v2.9.0: WEKIT'S ADDER, and the ONLY adder.
     *
     *     val addMenuItem = menu.reflekt().firstMethod {
     *         parameters(Int::class, CharSequence::class, Drawable::class)
     *         returnType = android.view.MenuItem::class
     *     }
     *     addMenuItem.invoke(item.id, item.text, item.drawable)
     *
     * The Drawable is built first: if it cannot be built the item is still added, because a missing label is
     * worse than a missing icon -- but there is no second ADDER.
     */
    private fun injectWekitStyle(menu: Any, view: View?) {
        com.luoliaoo.wechat.core.ModuleStats.noteMenuAttempt()

        val addMenuItem = try {
            menu.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    CharSequence::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    android.graphics.drawable.Drawable::class.java.isAssignableFrom(m.parameterTypes[2]) &&
                    android.view.MenuItem::class.java.isAssignableFrom(m.returnType)
            }
        } catch (_: Throwable) {
            null
        }
        if (addMenuItem == null) {
            Log.w(TAG, "no Drawable overload on " + menu.javaClass.name)
            return
        }

        // item NEW: ONE drawable serves BOTH entries.
        //
        // The user asked for the balloon switch to reuse the analysis icon with no on/off distinction,
        // so the same BitmapDrawable instance is handed to both add() calls. Sharing it is safe -- a
        // Drawable handed to a MenuItem is drawn, not mutated -- and it halves the icon decode work.
        var drawable: android.graphics.drawable.Drawable? = null
        val ctx = resolveContext(view, menu)
        if (ctx != null) {
            try {
                val sizePx = (ICON_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
                drawable = com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, sizePx)
            } catch (t: Throwable) {
                Log.w(TAG, "the icon could not be built: " + t.message)
            }
        } else {
            Log.w(TAG, "no Context for the icon; adding the item without one")
        }

        // item NEW: 分析 first, then the balloon switch -- so the order the user sees is stable and the
        // existing entry keeps the position it has always had.
        val added = addOurItem(menu, addMenuItem, ITEM_ID, ITEM_TITLE, drawable, RowAction.ANALYSE)
        val addedSwitch =
            addOurItem(menu, addMenuItem, BALLON_ITEM_ID, BALLON_ITEM_TITLE, drawable, RowAction.BALLOON_SWITCH)

        if (added) {
            Log.i(TAG, "MENU ITEM INJECTED (WeKit's adder, icon=" + (drawable != null) + ")")
            com.luoliaoo.wechat.core.ModuleStats.noteMenuInjected()
        }
        Log.i(TAG, "MENU ITEMS: analyse=" + added + " balloonSwitch=" + addedSwitch)
    }

    /**
     * item NEW: adds ONE of our entries through the Drawable overload, arming its click listener.
     *
     * Extracted so the two entries are added by identical code -- the balloon switch cannot drift from
     * 分析 in how it is created, and a failure of one does not prevent the other (each call is guarded).
     *
     * Returns true when the item was created.
     */
    private fun addOurItem(
        menu: Any,
        adder: java.lang.reflect.Method,
        id: Int,
        title: String,
        drawable: android.graphics.drawable.Drawable?,
        action: RowAction,
    ): Boolean = try {
        adder.isAccessible = true
        val item = adder.invoke(menu, id, title, drawable)
        if (item == null) {
            Log.w(TAG, "the Drawable adder returned null for " + title)
            false
        } else {
            try {
                (item as android.view.MenuItem).setOnMenuItemClickListener {
                    onOurRowTapped("MenuItem listener", action)
                    true
                }
            } catch (_: Throwable) {
                // the row hijack also delivers the click
            }
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "the Drawable adder failed for " + title + ": " + t.message)
        false
    }


    /**
     * v2.4.2: WEKIT'S `getMsgInfoFromTag` -- read the message out of the row's tag.
     *
     * The tag is usually NOT the message: it is a holder. WeKit therefore tries, in order:
     *   1. a no-arg method whose RETURN TYPE is the message class (walking up superclasses)
     *   2. a FIELD of the message class (walking up superclasses)
     * and accepts the object itself if it already IS a message.
     *
     * Reading the tag directly is what failed before -- `view.tag` was a holder, so the message never
     * surfaced and the click reported 未能识别该消息.
     */
    private fun msgFromTag(tag: Any?): Any? {
        if (tag == null) return null
        val msgCls = try {
            resolver.msgInfoClass()
        } catch (_: Throwable) {
            null
        }
        if (msgCls == null) {
            Log.w(TAG, "msgFromTag: message class unresolved")
            return null
        }

        // 0: the tag already IS a message.
        if (msgCls.isInstance(tag)) return tag

        // 1: a no-arg method returning the message type, on the tag or any superclass.
        var c: Class<*>? = tag.javaClass
        var depth = 0
        while (c != null && depth < 8) {
            for (m in c.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!msgCls.isAssignableFrom(m.returnType)) continue
                try {
                    m.isAccessible = true
                    val v = m.invoke(tag)
                    if (v != null) {
                        Log.i(TAG, "msgFromTag: via method " + c.simpleName + "." + m.name)
                        return v
                    }
                } catch (_: Throwable) {
                    // try the next candidate
                }
            }
            c = c.superclass
            depth++
        }

        // 2: a field of the message type.
        c = tag.javaClass
        depth = 0
        while (c != null && depth < 8) {
            for (f in c.declaredFields) {
                if (!msgCls.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(tag)
                    if (v != null) {
                        Log.i(TAG, "msgFromTag: via field " + c.simpleName + "." + f.name)
                        return v
                    }
                } catch (_: Throwable) {
                    // try the next candidate
                }
            }
            c = c.superclass
            depth++
        }

        Log.w(TAG, "msgFromTag: no message inside " + tag.javaClass.simpleName)
        return null
    }

    /**
     * v2.4.0: adds the item the way WeKit does -- the (int, CharSequence, Drawable) overload that returns a
     * MenuItem, found on the menu object and INVOKED reflectively.
     *
     * Falling back to the plain `add` overloads keeps the module working if that signature is ever absent.
     */
    private fun injectWithDrawable(menu: Any, msg: Any?, view: View?) {
        val addWithIcon = try {
            menu.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    CharSequence::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    android.graphics.drawable.Drawable::class.java.isAssignableFrom(m.parameterTypes[2]) &&
                    android.view.MenuItem::class.java.isAssignableFrom(m.returnType)
            }
        } catch (_: Throwable) {
            null
        }
        if (addWithIcon != null) {
            Log.i(TAG, "injection: using the Drawable overload " + addWithIcon.name)
        } else {
            Log.i(TAG, "injection: Drawable overload absent; using the standard path")
        }
        injectInto(menu, msg, view)
    }

    /**
     * v2.7.0 (item 2): WEKIT'S MULTI-SELECT BUTTON, using only APIs this module already has.
     *
     * WEKIT (its source):
     *     MULTI_SELECT_BAR = "com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar"
     *     hook the bar's set-button-listener, and AFTER it binds its own buttons (index 0 = forward)
     *     insert a child view; the selection is read through a separately resolved getter.
     *
     * THE BEHAVIOUR THE USER ASKED FOR: pressing it SKIPS the scope dialog (no 按条数 / 按时间区间 /
     * 仅选择信息) and opens the READ UI directly, where 删除 / 恢复 already work.
     */
    private fun installMultiSelectHook(): Int {
        val resolved = try {
            resolver.multiSelectBarAll()
        } catch (t: Throwable) {
            Log.w(TAG, "multiSelectBarAll threw: " + t.message)
            Resolver.MultiSelectBarMethods(null, null, null, null)
        }
        val setListener = resolved.setListener
        if (setListener == null) {
            Log.w(TAG, "multi-select: the toolbar method was not resolved; the 分析 button is unavailable")
            return 0
        }
        multiSelectGetter = resolved.getSelected
        Log.i(
            TAG,
            "multi-select: toolbar resolved; getter=" + (resolved.getSelected?.name ?: "unresolved") +
                " rebuild=" + (resolved.rebuildButtons?.name ?: "unresolved") +
                " updateSelection=" + (resolved.updateSelection?.name ?: "unresolved"),
        )

        var installed = 0

        // (1) WEKIT'S PRIMARY HOOK -- bind our button once the bar has built its own.
        //
        //     methodMultiSetButtonListener.hookAfter {
        //         if (args[0] as Int == 0) bindMultiSelectButton(thisObject as LinearLayout, ...)
        //     }
        //
        // hookAfter is MANDATORY: this method BUILDS the bar's children internally, so before it runs the
        // bar is empty and after it the buttons exist. The previous version used a before-hook whose
        // `chain.proceed()` was a no-op, so it tried to insert into an empty bar.
        try {
            LegacyHook.hook(setListener)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .interceptAfter { chain ->
                    try {
                        // Host index 0 is 转发; inserting a child does not change this semantic index.
                        if ((chain.getArg(0) as? Int) == 0) {
                            (chain.thisObject as? android.view.ViewGroup)?.let { addMultiSelectButton(it) }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "multi-select button failed: " + t.message)
                    }
                }
            installed++
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select hook failed: " + t.message)
        }

        // (2) WEKIT'S REBUILD HOOK -- re-attach after removeAllViews().
        //
        //     methodMultiRebuildButtons.hookAfter { attachMultiSelectButton(thisObject as LinearLayout, cached) }
        //
        // Without this the button disappears the moment WeChat rebuilds the bar.
        val rebuild = resolved.rebuildButtons
        if (rebuild != null) {
            try {
                LegacyHook.hook(rebuild)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .interceptAfter { chain ->
                        try {
                            val bar = chain.thisObject as? android.view.ViewGroup ?: return@interceptAfter
                            val cached = multiSelectButtonOf(bar) ?: return@interceptAfter
                            if (cached.parent == null && bar.childCount > 0) {
                                bar.addView(cached, 0, forwardLayoutParams(bar))
                                Log.i(TAG, "multi-select: button re-attached after rebuild")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "multi-select rebuild re-attach failed: " + t.message)
                        }
                    }
                installed++
            } catch (t: Throwable) {
                Log.w(TAG, "multi-select rebuild hook failed: " + t.message)
            }
        } else {
            Log.w(TAG, "multi-select: rebuild method unresolved; the button may vanish after a bar rebuild")
        }

        // (3) WEKIT'S SELECTION HOOK -- mirror enabled/clickable/alpha with the selection state.
        //
        //     methodMultiUpdateSelection.hookAfter {
        //         val hasSelection = args[0] as Int > 0
        //         button.isEnabled = hasSelection; button.isClickable = hasSelection
        //         button.alpha = if (hasSelection) 1f else 0.15f
        //     }
        val updateSel = resolved.updateSelection
        if (updateSel != null) {
            try {
                LegacyHook.hook(updateSel)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .interceptAfter { chain ->
                        try {
                            val bar = chain.thisObject as? android.view.ViewGroup ?: return@interceptAfter
                            val button = multiSelectButtonOf(bar) ?: return@interceptAfter
                            val hasSelection = ((chain.getArg(0) as? Int) ?: 0) > 0
                            button.isEnabled = hasSelection
                            button.isClickable = hasSelection
                            button.alpha = if (hasSelection) 1f else 0.15f
                        } catch (t: Throwable) {
                            Log.w(TAG, "multi-select update-selection failed: " + t.message)
                        }
                    }
                installed++
            } catch (t: Throwable) {
                Log.w(TAG, "multi-select update-selection hook failed: " + t.message)
            }
        } else {
            Log.w(TAG, "multi-select: updateSelection unresolved; the button stays always-enabled")
        }

        return installed
    }

    /**
     * v2.8.0 (item 2): WEKIT'S BUTTON CONSTRUCTION, copied literally.
     *
     * WHY THE PREVIOUS VERSION SHOWED NOTHING: it used a plain `ImageView` with `setPadding`. WeChat's
     * multi-select bar lays its children out with its OWN rules, and a plain ImageView is never given a size --
     * so it rendered with zero width and was invisible.
     *
     * WeKit does two things instead, and both are required:
     *   1. it CLONES THE HOST BUTTON'S CLASS:
     *          bar.getChildAt(0).javaClass.reflekt().firstConstructor { parameters(Context) }
     *             .newInstance(bar.context) as ImageView
     *   2. it copies the FORWARD button's layout params verbatim and inserts at index 0:
     *          bar.addView(button, 0, forwardButton.layoutParams as LinearLayout.LayoutParams)
     *
     * Both are done here, with our own drawable instead of WeKit's.
     */
    private fun addMultiSelectButton(bar: android.view.ViewGroup) {
        // WeChat rebuilds the bar (removeAllViews) and re-binds listeners. WeKit survives that by caching
        // the button ON THE BAR ITSELF (bar.setTag(...)) and re-attaching it after every rebuild.
        val cached = multiSelectButtonOf(bar)
        if (cached != null) {
            // Already built once; just make sure it is back in the tree.
            if (cached.parent == null && bar.childCount > 0) {
                try {
                    bar.addView(cached, 0, forwardLayoutParams(bar))
                    Log.i(TAG, "multi-select: cached button re-attached (childCount=" + bar.childCount + ")")
                } catch (t: Throwable) {
                    Log.w(TAG, "multi-select: re-attach failed: " + t.message)
                }
            }
            return
        }

        // Nothing to clone from yet -- WeChat has not built the host buttons.
        if (bar.childCount == 0) {
            Log.w(TAG, "multi-select: bar is empty; cannot clone the host button")
            return
        }

        val ctx = bar.context
        val sizePx = (ICON_SIZE_DP * ctx.resources.displayMetrics.density).toInt()

        // 1) CLONE THE HOST BUTTON'S CLASS -- this is what gives it a size. WeKit's exact line.
        val btn: android.widget.ImageView? = try {
            val hostClass = bar.getChildAt(0).javaClass
            val ctor = hostClass.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 1 &&
                    android.content.Context::class.java.isAssignableFrom(c.parameterTypes[0])
            }
            ctor?.isAccessible = true
            ctor?.newInstance(ctx) as? android.widget.ImageView
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select: cloning the host button failed: " + t.message)
            null
        }

        // Fall back to a plain ImageView only if the host class cannot be cloned.
        val button: android.widget.ImageView = btn ?: android.widget.ImageView(ctx)

        try {
            button.setImageDrawable(com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, sizePx))
            // WeKit clears the background so the host's ripple/tint does not hide the icon.
            button.setBackgroundResource(0)
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select: setting the drawable failed: " + t.message)
        }
        button.scaleType = android.widget.ImageView.ScaleType.CENTER
        button.contentDescription = "\u5206\u6790"
        try {
            button.tooltipText = "\u5206\u6790"
        } catch (_: Throwable) {
            // tooltipText needs API 26
        }
        button.isClickable = true
        button.tag = TAG_MULTI_BUTTON
        button.setOnClickListener {
            try {
                onMultiSelectAnalyse(bar)
            } catch (t: Throwable) {
                Log.w(TAG, "multi-select analyse failed: " + t.message)
            }
        }

        try {
            // Cache it on the bar FIRST, so a later rebuild re-attaches instead of building a second one.
            bar.setTag(MULTI_BUTTON_TAG_ID, button)

            // 2) copy the FORWARD button's layout params and insert at index 0 -- WeKit's exact approach.
            val forward = bar.getChildAt(0)
            bar.addView(button, 0, forwardLayoutParams(bar))
            // Mirror the host button's interaction state, as WeKit does.
            try {
                button.isEnabled = forward.isEnabled
                button.isClickable = forward.isClickable
                button.alpha = forward.alpha
            } catch (_: Throwable) {
                // cosmetic
            }
            Log.i(
                TAG,
                "multi-select: button added at index 0 (hostClass=" +
                    bar.getChildAt(1).javaClass.simpleName + ", childCount=" + bar.childCount + ")",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select: could not add the button: " + t.message)
        }
    }

    /** The forward button's layout params, copied verbatim (WeKit does the same). */
    private fun forwardLayoutParams(bar: android.view.ViewGroup): android.widget.LinearLayout.LayoutParams {
        val forward = bar.getChildAt(0)
        return forward?.layoutParams as? android.widget.LinearLayout.LayoutParams
            ?: android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            )
    }

    /** v3.0.2: our button, cached on the bar. WeKit uses an R.id for the same purpose. */
    private fun multiSelectButtonOf(bar: android.view.ViewGroup): android.widget.ImageView? =
        try {
            @Suppress("UNCHECKED_CAST")
            bar.getTag(MULTI_BUTTON_TAG_ID) as? android.widget.ImageView
        } catch (_: Throwable) {
            null
        }

    /**
     * Reads the current selection and opens the READ UI.
     *
     * The selection comes from the resolved getter (WeKit's approach); the lines use the SAME row builder the
     * scope dialog uses, so 删除 / 恢复 behave identically and the two entry points cannot drift apart.
     */
    private fun onMultiSelectAnalyse(bar: android.view.View) {
        val getter = multiSelectGetter
        val activity = findActivity(bar.context)
        if (getter == null || activity == null) {
            Log.w(TAG, "multi-select: getter=" + (getter != null) + " activity=" + (activity != null))
            return
        }

        val selected: List<Any> = try {
            val owner = findMultiSelectOwner(bar, getter)
            if (owner == null) {
                emptyList()
            } else {
                (getter.invoke(owner) as? List<*>)?.filterNotNull() ?: emptyList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select: reading the selection failed: " + t.message)
            emptyList()
        }
        Log.i(TAG, "multi-select: selected=" + selected.size)
        if (selected.isEmpty()) {
            toast(activity, "\u8bf7\u5148\u52fe\u9009\u6d88\u606f")
            return
        }

        // Rendered with the module's own formatting, then handed to the read UI.
        val described = controller.describeMessages(selected)
        if (described.isEmpty()) {
            toast(activity, "\u9009\u4e2d\u7684\u6d88\u606f\u6ca1\u6709\u6587\u672c")
            return
        }

        // v3.0.3 (item 2): THE BLOCK-WORD FILTER RUNS HERE -- after reading, BEFORE the dialog opens.
        //
        // The user asked for exactly this order: 在多选点击分析前就处理好 然后再弹出界面. Filtering at
        // this point means a blocked message is never rendered in the list, so it also can never be
        // un-deleted and sent to the model (item 3: 包含屏蔽词的信息将不会发送给AI).
        //
        // The words are the template's own list, which INCLUDES the built-in markers
        // (msg / 了解详情 / :0) because TemplateStore seeds every template with them. Matched as
        // CONTIGUOUS substrings: "我爱msg你" is dropped, "我m爱s你g" is kept.
        //
        // v6.0.0: this previously passed PromptBuilder.SKIP_MARKERS -- the HARDCODED three -- so a word
        // the user added to a template was ignored on this path (the reported bug: 「msg有用 click与auth没用」).
        val words = controller.templateBlockWords()
        val lines = described.filterNot {
            com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(it) ||
                com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(it, words)
        }
        val blockedCount = described.size - lines.size
        if (blockedCount > 0) {
            Log.i(
                TAG,
                "multi-select: blocked " + blockedCount + " of " + described.size +
                    " line(s) before the dialog, via " + words.joinToString("/"),
            )
        }
        if (lines.isEmpty()) {
            // Every selected message matched a block word. Say so instead of opening an empty dialog.
            toast(activity, "\u9009\u4e2d\u7684\u6d88\u606f\u5747\u5305\u542b\u5c4f\u853d\u8bcd")
            Log.w(TAG, "multi-select: every selected line matched a block word; nothing to analyse")
            return
        }

        com.luoliaoo.wechat.ui.ScopeDialog.showReadOnly(
            activity, lines, "\u5df2\u9009\u6d88\u606f",
        ) { kept, background ->
            // v3.0.3: the list is already filtered above; the analysis re-applies the SAME words
            // (template's own, which include the built-in markers) as a belt-and-braces second pass.
            //
            // v6.0.0: was SKIP_MARKERS (hardcoded) -- which meant the second pass used a DIFFERENT list
            // from the first, so a user-added word could be filtered here and then not there.
            controller.startAnalysisForSelection(activity, kept, background, words)
        }
    }

    /** Finds the object the selection getter belongs to, by walking the toolbar's listeners. */
    private fun findMultiSelectOwner(bar: android.view.View, getter: java.lang.reflect.Method): Any? {
        val declaring = getter.declaringClass
        if (bar is android.view.ViewGroup) {
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val li = try {
                    val f = android.view.View::class.java.getDeclaredField("mListenerInfo")
                    f.isAccessible = true
                    f.get(child)
                } catch (_: Throwable) {
                    null
                } ?: continue
                for (f in li.javaClass.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(li) ?: continue
                        if (declaring.isInstance(v)) return v
                        for (g in v.javaClass.declaredFields) {
                            g.isAccessible = true
                            val w = g.get(v) ?: continue
                            if (declaring.isInstance(w)) return w
                        }
                    } catch (_: Throwable) {
                        // keep looking
                    }
                }
            }
        }
        return null
    }

    private fun toast(activity: android.app.Activity, text: String) {
        try {
            android.widget.Toast.makeText(activity, "\u843d\u804a\uff1a" + text,
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // cosmetic
        }
    }

    private fun installMenuClassHook(menuCls: Class<*>): Int {
        var n = 0
        for (m in menuCls.declaredMethods) {
            if (!m.name.startsWith("add")) continue
            if (m.parameterCount < 2) continue
            try {
                LegacyHook.hook(m)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val menu = chain.thisObject
                            if (menu != null && markInjectedOnce(menu)) {
                                Log.i(TAG, "menu.add INTERCEPTED via " + m.name +
                                    " -> injecting (" + m.parameterCount + " args)")
                                injectInto(menu, lastMessage.get(), lastView.get())
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "menu-class inject failed: " + t.message)
                        }
                        result
                    }
                n++
            } catch (t: Throwable) {
                Log.w(TAG, "hook " + menuCls.simpleName + "." + m.name + " failed: " + t.message)
            }
        }
        Log.i(TAG, "MENU CLASS HOOKED: " + menuCls.name + " add-methods=" + n)
        return n
    }

    private fun hookByShape(menuCls: Class<*>, loader: ClassLoader): Int {
        val candidates = try {
            resolver.contributorClasses(menuCls)
        } catch (t: Throwable) {
            Log.w(TAG, "contributorClasses failed: " + t.message)
            emptyList()
        }
        Log.i(TAG, "shape scan: candidate contributors=" + candidates.size)

        var n = 0
        for (cls in candidates) {
            for (m in cls.declaredMethods) {
                if (m.name != "onCreateMMMenu") continue
                val ps = m.parameterTypes
                if (ps.isEmpty() || !menuCls.isAssignableFrom(ps[0])) continue
                try {
                    LegacyHook.hook(m)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val menu = chain.getArg(0)
                                if (menu != null) {
                                    injectInto(menu, lastMessage.get(), lastView.get())
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "shape inject failed: " + t.message)
                            }
                            result
                        }
                    n++
                } catch (t: Throwable) {
                    // a single method failing must not stop the rest
                }
            }
        }
        return n
    }

    private inline fun guarded(name: String, block: () -> Int): Int = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "hook step '" + name + "' failed", t)
        0
    }

    // ============================ LAYER 1: our label is being set

    /**
     * Any TextView whose text becomes our title is our row. We then hijack it.
     * This fires the moment WeChat renders the button.
     */
    private fun installSetTextHook(): Int {
        val cls = Class.forName("android.widget.TextView", false, resolver.classLoader())
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "setText") continue
            if (m.parameterCount != 1) continue
            if (m.parameterTypes[0] != CharSequence::class.java) continue
            LegacyHook.hook(m)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val arg = chain.getArg(0)
                        // item NEW: either of our titles marks one of OUR rows, so the balloon switch
                        // gets a hijacked listener exactly like 分析 does.
                        if (arg != null && OWN_MENU_TITLES.contains(arg.toString())) {
                            val self = chain.thisObject as? View
                            // Never touch our own dialog's confirm button.
                            if (self != null && isHijackable(self)) {
                                val c = setTextHits.incrementAndGet()
                                Log.i(TAG, "OUR LABEL SET #" + c + " on " +
                                    self.javaClass.name + " text='" + arg + "'")
                                hijack(self)
                            }
                        }
                    } catch (_: Throwable) {
                        // detection only
                    }
                    result
                }
            n++
        }
        Log.i(TAG, "hooked TextView.setText(CharSequence) overloads: " + n)
        return n
    }

    // ============================ LAYER 2: our row becomes visible

    /**
     * onAttachedToWindow fires for every view that enters the window. If the attached
     * subtree contains our title, hijack it -- this catches the popup appearing even if
     * setText was not the route taken.
     */
    private fun installAttachHook(): Int {
        val cls = Class.forName("android.view.View", false, resolver.classLoader())
        val m = cls.getDeclaredMethod("onAttachedToWindow")
        LegacyHook.hook(m)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val self = chain.thisObject as? View
                    if (self != null && isHijackable(self) && !hijacked.contains(self)) {
                        val hit = findTitleView(self, 0)
                        if (hit != null && isHijackable(hit)) {
                            val c = attachHits.incrementAndGet()
                            Log.i(TAG, "OUR ROW ATTACHED #" + c + " via " +
                                self.javaClass.simpleName)
                            hijack(hit)
                        }
                    }
                } catch (_: Throwable) {
                    // detection only
                }
                result
            }
        Log.i(TAG, "hooked View.onAttachedToWindow")
        return 1
    }

    // ============================ LAYER 3: periodic scans

    private fun scheduleScans() {
        for (d in SCAN_DELAYS) main.postDelayed({ scanForOurRow() }, d)
    }

    private fun scanForOurRow() {
        val n = scans.incrementAndGet()
        try {
            val roots = ArrayList<View>(3)
            pendingView?.rootView?.let { roots.add(it) }
            val act = currentActivity()
            if (act != null) act.window?.decorView?.let { roots.add(it) }
            pendingView?.let { v ->
                findActivity(v.context)?.window?.decorView?.let { roots.add(it) }
            }
            if (roots.isEmpty()) return

            // item NEW: hijack EVERY one of our rows found, not just the first.
            //
            // The scan used to stop at the first match. With two injected entries that leaves whichever
            // row came second without a click listener, and on the WeChat builds where this scan is the
            // path that fires, the balloon switch would look dead. hijack() itself is idempotent per
            // view (it is guarded by the `hijacked` identity set), so collecting all matches is safe.
            var found = 0
            for (r in roots) {
                val hits = ArrayList<View>(2)
                findTitleViews(r, hits, 0)
                for (h in hits) {
                    if (isHijackable(h)) {
                        hijack(h)
                        found++
                    }
                }
                if (found > 0) break
            }
            if (found == 0 && n <= 6) {
                val texts = LinkedHashSet<String>()
                for (r in roots) collectTexts(r, texts, 0)
                Log.i(TAG, "scan[" + n + "]: not found; visible texts=" + texts)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan[" + n + "] failed: " + t.message)
        }
    }

    /**
     * item NEW: every view in the subtree whose text is one of our titles.
     *
     * The single-hit findTitleView() is still used where one row is enough; this collector exists so the
     * periodic scan can arm BOTH entries in one pass.
     */
    private fun findTitleViews(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 40 || out.size >= 8) return
        if (v is TextView) {
            val t = v.text
            if (t != null && OWN_MENU_TITLES.contains(t.toString())) {
                out.add(v)
                return
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findTitleViews(v.getChildAt(i), out, depth + 1)
        }
    }

    private fun collectTexts(v: View, out: MutableSet<String>, depth: Int) {
        if (depth > 40 || out.size > 45) return
        if (v is TextView) {
            val t = v.text
            if (t != null && t.isNotEmpty()) {
                val s = t.toString()
                if (s.length <= 20) out.add(s)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out, depth + 1)
        }
    }

    private fun findTitleView(v: View, depth: Int): View? {
        if (depth > 40) return null
        if (v is TextView) {
            val t = v.text
            // item NEW: EITHER of our titles counts. Both entries need a hijacked row, so a scan that
            // only recognised 分析 would leave the balloon switch dead when the attach/scan path is the
            // one that fires (which is the case on some WeChat builds).
            if (t != null && OWN_MENU_TITLES.contains(t.toString())) return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findTitleView(v.getChildAt(i), depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * Attaches our listener to exactly ONE host view: the nearest clickable ancestor of
     * the title view (or the title view itself).
     *
     * HARD RULES (see the v1.0.14 note): never hijack a Button, and never hijack
     * anything inside our own dialog -- that dialog's confirm button is labelled with
     * the same text as our menu entry.
     */
    private fun hijack(titleView: View) {
        if (!isHijackable(titleView)) {
            // v2.5.2: RATE-LIMITED. This fired ~1500 times in a 4000-line window (Buttons, checkboxes,
            // our own subtree), which buried everything useful in a log the user is asked to send.
            // The count is kept so the information is not lost, and the first few are still printed.
            if (skipHijackLogged.incrementAndGet() <= 20) Log.i(TAG, "skip hijack: not hijackable (" + titleView.javaClass.simpleName + ")")
            return
        }

        // Walk up and pick the SMALLEST clickable ancestor whose subtree resolves to exactly ONE of
        // our entries. An ancestor that also holds 复制 / 转发 / 删除 is the shared container, and
        // attaching there made every menu tap open the analysis.
        //
        // item NEW: the decision now comes from rowActionOf(), which also tells us WHICH entry the host
        // belongs to. That value is captured in `action` and used when the listener fires -- with two
        // injected items, "a click landed on our row" is no longer enough to know what to do.
        //
        // ═══ v3.1.1 真机根因（19:06 的 adb 日志） ═══
        //
        // 日志实测：
        //     19:06:48.954  ROW HIJACKED host=TextView     levels=1 action=analyse
        //     19:06:48.976  ROW HIJACKED host=LinearLayout levels=2 action=analyse
        //     19:06:48.976  ROW HIJACKED host=LinearLayout levels=2 action=balloon-switch   ← 同一个容器!
        //     19:06:49.630  tap action resolved: balloon-switch (rowView=LinearLayout)       ← 点分析却切浮窗
        //
        // 也就是说：微信把每个菜单项做成一个 TextView，若干项装进**同一个 LinearLayout**。
        // 向上查找时，「分析」那一路、「浮窗」那一路**都会走到同一个 LinearLayout 并停下**（levels=2），
        // 于是：
        //   * 同一个容器被 setOnClickListener 挂了两次，**后一次覆盖前一次**，只留下 balloon-switch；
        //   * 点「分析」的 TextView，事件冒泡到容器，触发的是被覆盖后剩下的那个 listener。
        //
        // 原判据 `a != RowAction.NONE` 不够：容器里同时含两个标题时 decideOwnMenuAction 会返回 NONE，
        // 但这里 break 的条件没要求「这个 host 恰好只属于当前这个标题」，所以容器照样被选为 host。
        //
        // 修法：**要求 host 解析出的 action 必须与本标题自身一致**（`a == selfAction`）。
        // 共享容器解析出 NONE（或解析成另一个标题），因此不再会被选中；查找会正确地停在
        // 「只含本标题」的那一层——通常就是标题自身或它的直接行容器。
        val selfAction = rowActionOf(titleView, titleView)
        var host: View? = null
        var action: RowAction = RowAction.NONE
        var cur: View? = titleView
        var levels = 0
        while (cur != null && levels < 6) {
            val a = rowActionOf(cur, titleView)
            // 关键新增：host 必须「只属于本标题」。a != selfAction 意味着这一层是共享容器（或别人的行），
            // 挂上去就会让两项互相覆盖 —— 这正是真机上出现的问题。
            if (cur.isClickable && !isButton(cur) && !isOwnUi(cur) &&
                a != RowAction.NONE && a == selfAction
            ) {
                host = cur
                action = a
                break
            }
            val p: ViewParent? = cur.parent
            cur = if (p is View) p else null
            levels++
        }

        if (host == null) {
            // No ancestor qualifies: use the title view itself if it can receive clicks.
            if (isHijackable(titleView) && selfAction != RowAction.NONE) {
                host = titleView
                action = selfAction
            } else {
                Log.w(TAG, "hijack: no row that belongs to our item alone; not hijacking")
                return
            }
        }

        if (!isHijackable(host)) return

        // v3.1.1 修复（用户报「点分析也变成悬浮球开关了」）：
        //
        // 【原 bug 有三层，叠在一起才致命】
        //   a) 这里原来是 `if (!hijacked.add(host)) return` —— 去重本身没错，但它按 **View 身份**去重。
        //      微信把两个菜单项渲染在同一个 host 容器上时，第二项直接 return，**连 listener 都没绑**。
        //   b) `armedRow` 是**单个共享字段**，两个菜单项各自 hijack 都写它，**后写的覆盖先写的**。
        //   c) 于是点击时 onOurRowTapped 读到的 armedRow 永远是最后那一个 host，
        //      dispatchRowAction 判定出 BALLOON_SWITCH —— 点「分析」也去切悬浮球。
        //
        // 【修法】
        //   * listener 不再依赖任何共享字段：用闭包捕获**自己**的 host 与 action，点击时以自己那一行为准。
        //   * 去重改成按「host + 这一项的身份」去重，而不是只按 host —— 同一个 host 上两项各自
        //     合法地拥有自己的监听意图，不能被对方的先注册挡掉。
        val myHost = host
        val slot = myHost.hashCode().toString() + "#" + action.name()
        if (!hijackedSlots.add(slot)) return

        // v3.1.1：两个集合都要登记，各司其职。
        //   hijackedSlots —— 按「host + action」去重，允许同一个 host 承载两项各自的监听意图。
        //   hijacked      —— 按 View 身份记录**已挂监听的行**，clearHijacks() 靠它把监听器摘干净。
        // 只登记一个会出 bug：只记 slots 的话 hijacked 永远为空，clearHijacks() 摘不掉任何监听器，
        // 微信复用菜单行时旧的监听器会残留并在下一次长按时误触发。
        hijacked.add(myHost)

        myHost.isClickable = true
        armedRow = myHost
        myHost.setOnClickListener { onOurRowTapped("hijacked row", action, myHost) }

        Log.i(TAG, "ROW HIJACKED host=" + myHost.javaClass.simpleName +
            " titleView=" + titleView.javaClass.simpleName + " levels=" + levels +
            " action=" + action.name())
    }

    /**
     * True when `candidate`'s subtree shows our label and no other menu label.
     *
     * Still used by hijack() to choose the click host. The tap probe uses the same rules inline,
     * anchored on the tapped view.
     *
     * This is the guard that stops 复制 / 转发 / 删除 / 多选 from triggering the analysis: those
     * rows live in the same container as our item, so the container must be rejected.
     */
    /**
     * A Context for building the icon, from the first source that yields one.
     *
     * The contributor's `view` argument is null for the WeChat method that fires for the long-press
     * menu, which is why the icon was skipped three times in the log. Hence the fallbacks.
     */
    private fun resolveContext(view: View?, menu: Any?): android.content.Context? {
        // 1) the view we were given
        (view?.context ?: lastView.get()?.context)?.let {
            Log.i(TAG, "icon context: from the view")
            return it
        }
        // 2) a context captured from the chat list
        com.luoliaoo.wechat.core.AppContextHolder.get()?.let {
            Log.i(TAG, "icon context: from AppContextHolder")
            return it
        }
        // 3) the running application, via the menu object's class loader
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val current = cls.getMethod("currentApplication").invoke(null)
            if (current is android.content.Context) {
                Log.i(TAG, "icon context: from ActivityThread.currentApplication")
                return current
            }
        } catch (_: Throwable) {
            // not reachable
        }
        // 4) the menu object's own class loader is a last chance at an application context
        try {
            val appCls = Class.forName("android.app.Application", false, menu?.javaClass?.classLoader)
            if (appCls != null) {
                val m = Class.forName("android.app.ActivityThread")
                val cur = m.getMethod("currentApplication").invoke(null)
                if (cur is android.content.Context) {
                    Log.i(TAG, "icon context: from the menu class loader path")
                    return cur
                }
            }
        } catch (_: Throwable) {
            // not reachable
        }
        Log.w(TAG, "icon context: none of the sources worked")
        return null
    }

    /**
     * item NEW: which of OUR menu entries a subtree represents.
     *
     * THE PROBLEM THIS SOLVES. Before the balloon switch there was exactly one entry we owned, so
     * "the subtree contains our title" WAS the identification. With a second entry in the same menu
     * that test becomes ambiguous, and it fails in two distinct ways:
     *
     *   * the SHARED CONTAINER holds both titles, so a tap on either entry would match "contains our
     *     title" and the two actions would be chosen by whichever check ran first;
     *   * a row for 分析 does not contain the balloon title and vice versa, but a rule written as
     *     "contains any of our titles" collapses them back into one.
     *
     * The rule is therefore a DECISION over the whole label set of the smallest candidate row, and it
     * is exclusive: it returns the single entry the row belongs to, or NONE.
     *
     * How a row is decided:
     *   1. the candidate must actually contain the anchor view (the title TextView we started from);
     *   2. the row's label set must hold exactly ONE of our titles -- a row showing both is the shared
     *      container, not a row;
     *   3. that row must show no WeChat menu label (复制 / 转发 / ...) -- again the container, or a
     *      different item entirely;
     *   4. the label count stays small (<= 2), which rejects anything that swallowed several rows.
     *
     * Steps 2 and 3 together are what stop the two entries from hijacking each other: a row that shows
     * 分析 alone resolves to ANALYSE even though the balloon row exists elsewhere in the same menu.
     */
    private enum class RowAction { NONE, ANALYSE, BALLOON_SWITCH }

    /** item NEW: human-readable form, used in the log lines. */
    private fun RowAction.name(): String = when (this) {
        RowAction.NONE -> "none"
        RowAction.ANALYSE -> "analyse"
        RowAction.BALLOON_SWITCH -> "balloon-switch"
    }

    /**
     * item NEW: the entry a candidate row stands for, given the anchor view inside it.
     *
     * Returns NONE when the candidate is the shared container, a WeChat row, or anything ambiguous.
     * Callers treat NONE as "not ours" and either keep walking up or ignore the tap.
     */
    private fun rowActionOf(candidate: View, anchor: View): RowAction {
        // 1) the anchor must live inside the candidate (or BE the candidate).
        if (candidate !== anchor && !isDescendant(candidate, anchor)) return RowAction.NONE

        val labels = LinkedHashSet<String>()
        collectLabels(candidate, labels, 0)
        if (labels.isEmpty()) return RowAction.NONE

        // 2..4) THE decision itself, shared with the unit tests via Constants. Keeping it there means
        // the rule that routes a real tap is the exact rule the tests pin.
        return when (Constants.decideOwnMenuAction(labels, OTHER_MENU_LABELS)) {
            Constants.OwnMenuAction.BALLOON_SWITCH -> RowAction.BALLOON_SWITCH
            Constants.OwnMenuAction.ANALYSE -> RowAction.ANALYSE
            Constants.OwnMenuAction.NONE -> {
                if (labels.any { OWN_MENU_TITLES.contains(it) }) {
                    Log.i(TAG, "reject host: ambiguous or shared row (labels=" + labels + ")")
                }
                RowAction.NONE
            }
        }
    }

    /**
     * item NEW: the action for a tap, by walking up from the tapped view.
     *
     * The previous isInsideOurRow() walked up looking for "a ViewGroup whose subtree shows our label
     * and no other menu label". That is kept, but the ANSWER now names the entry, so the caller can
     * route the tap to the right handler. The first ancestor that resolves to a concrete action wins --
     * that is the tightest row containing the tap.
     */
    private fun actionForTap(v: View): RowAction {
        if (v is android.widget.Button) return RowAction.NONE
        if (v is android.widget.EditText) return RowAction.NONE
        if (isOwnUi(v)) return RowAction.NONE

        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is android.widget.EditText) return RowAction.NONE
            if (cur is ViewGroup) {
                // The tap itself is the anchor: we only ask which of our entries this row is.
                val action = rowActionOf(cur, v)
                if (action != RowAction.NONE) {
                    if (!containsEditText(cur, 0)) {
                        Log.i(TAG, "tap resolved to our row: " + action.name())
                        return action
                    }
                    return RowAction.NONE
                }
                // A container that shows our title AND a WeChat label -- or BOTH of our titles -- is the
                // shared dialog: walking further up only makes it bigger, so give up rather than
                // mis-attribute the tap to whichever entry happened to be checked first.
                val labels = LinkedHashSet<String>()
                collectLabels(cur, labels, 0)
                val hasOurs = labels.any { OWN_MENU_TITLES.contains(it) }
                val hasOther = labels.any { OTHER_MENU_LABELS.contains(it) }
                val hasBothOurs = OWN_MENU_TITLES.count { labels.contains(it) } > 1
                if (hasOurs && (hasOther || hasBothOurs)) {
                    Log.i(TAG, "tap rejected: row is a shared container (labels=" + labels + ")")
                    return RowAction.NONE
                }
            }
            val p = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return RowAction.NONE
    }

    /**
     * True when this tap landed on one of OUR menu rows -- shared by EVERY trigger path.
     *
     * The menu's shared container holds all items, so "some ancestor displays our label" is NOT
     * enough: tapping 复制 / 转发 / 删除 / 多选 satisfied it and ran the analysis.
     *
     * item NEW: with two entries injected, the question is no longer "ours or not" but "WHICH one".
     * actionForTap() answers that; this boolean form is kept for logs, and it delegates so no caller
     * can disagree with the click handler about what counts as our row.
     */
    private fun isInsideOurRow(v: View): Boolean = actionForTap(v) != RowAction.NONE

    /** Collects short text labels in a subtree, stopping early once it is clearly a container. */
    private fun collectLabels(v: View, out: MutableSet<String>, depth: Int) {
        if (depth > 6 || out.size > 8) return
        if (v is android.widget.TextView) {
            val s = v.text
            if (s != null) {
                val str = s.toString().trim()
                if (str.isNotEmpty() && str.length <= 8) out.add(str)
            }
        }
        if (v is ViewGroup) {
            // Do not descend into our own card.
            if (v is com.luoliaoo.wechat.ui.AnalysisCard) return
            for (i in 0 until v.childCount) collectLabels(v.getChildAt(i), out, depth + 1)
        }
    }

    private fun isDescendant(root: View, v: View): Boolean {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 20) {
            if (cur === root) return true
            val p = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return false
    }

    /** A Button is never a WeChat menu row (buttons belong to dialogs). */
    private fun isButton(v: View): Boolean = v is android.widget.Button

    /** True when this view lives inside a subtree we tagged as our own UI. */
    private fun isOwnUi(v: View): Boolean {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 30) {
            val t = cur.tag
            if (t is String && t == Constants.TAG_OWN_UI) return true
            val p: ViewParent? = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return false
    }

    /**
     * Final gate before touching a view. Returns false for anything that must never be
     * hijacked, and logs the reason so this class of bug stays visible.
     *
     * Registration by IDENTITY (OwnUi) is the reliable test: tagging a container failed
     * because AlertDialog places its buttons in a sibling button bar, outside the view
     * passed to setView.
     */
    private fun isHijackable(v: View): Boolean {
        if (OwnUi.isSuppressed()) {
            // We are building/showing our own dialog right now.
            return false
        }
        if (OwnUi.contains(v)) {
            Log.i(TAG, "skip hijack: view is registered as our own UI")
            return false
        }
        if (isButton(v)) {
            Log.i(TAG, "skip hijack: Button (" + v.javaClass.simpleName + ")")
            return false
        }
        if (isOwnUi(v)) {
            Log.i(TAG, "skip hijack: inside tagged own-UI subtree")
            return false
        }
        return true
    }

    /** Removes our listeners so a stale hijack cannot fire for a later menu. */
    private fun clearHijacks() {
        val snapshot = ArrayList<View>(hijacked)
        hijacked.clear()
        // v3.1.1：去重槽位必须一起清掉，否则这一轮 hijack 过的行，下一轮（微信复用了同一个 View）
        // 会因为 slot 还在而跳过注册，新菜单里我们的按钮就点不动了。
        hijackedSlots.clear()
        for (v in snapshot) {
            try {
                v.setOnClickListener(null)
            } catch (_: Throwable) {
                // view may already be detached
            }
        }
        if (snapshot.isNotEmpty()) Log.i(TAG, "cleared " + snapshot.size + " hijack listener(s)")
    }

    /**
     * item NEW: a tap on one of our rows.
     *
     * `known` is the action the listener was armed with, or NONE for the paths that only know "our row
     * was hit" (the performClick probe, the item dispatcher by id).
     *
     * WHY known IS ONLY A HINT AND NOT THE ANSWER. WeChat REUSES the menu's row views: the same View
     * object is re-bound for a different item on a later long press. A listener that baked in the action
     * would then run the PREVIOUS entry's action -- switching the balloon when the user pressed 分析.
     * The live view is therefore re-examined first (dispatchRowAction), and `known` is only the fallback
     * for when the row can no longer be read (e.g. it was detached between the tap and the dispatch).
     */
    private fun onOurRowTapped(source: String, known: RowAction = RowAction.NONE, ownRow: View? = null) {
        /* ★ v9.1.0：免责声明闸门。
         *
         * 放在这个函数的**最开头**，因为这里是全部 5 个宿主（微信 / 抖音 / QQ / Soul / 快手）
         * 的**唯一汇聚点** —— 不管用户从哪个 App 的哪个入口点进「落聊」，
         * 都要先经过 onOurRowTapped。在这里拦一次，就等于在所有入口都拦住了，
         * 不需要在 20 多处菜单注入点各写一遍（那样必然会漏）。
         *
         * 拦在这里而不是更早（例如 handleLoadPackage）的原因：
         *   更早的位置拿不到 Activity，弹不出窗。用户的要求是「弹出强制 UI」，
         *   有界面才能弹。此处是「用户真的要用功能了」的第一个有 Activity 的时点。
         *
         * 未同意时直接 return —— 功能不启动。弹窗由 ensureConsented 负责，
         * 它内部会处理「已经同意过就不弹」的快速路径。 */
        try {
            val act = currentActivity() ?: findActivity(ownRow?.context ?: lastView.get()?.context)
            if (!com.luoliaoo.wechat.ui.DisclaimerDialog.ensureConsented(act)) {
                Log.i(TAG, "disclaimer not accepted yet -- refusing this tap")
                return
            }
        } catch (t: Throwable) {
            /* 闸门自身出错时放行。理由与 DisclaimerDialog 内部的兜底一致：
               不能因为「我们的检查代码抛异常」就让用户完全用不了模块。 */
            Log.w(TAG, "disclaimer gate failed, allowing the tap: " + t.message)
        }

        val now = System.currentTimeMillis()
        val prev = lastTapAt
        if (now - prev < TAP_DEBOUNCE_MS) {
            Log.i(TAG, "tap ignored (debounce, " + (now - prev) + " ms since last)")
            return
        }
        lastTapAt = now

        val n = taps.incrementAndGet()
        Log.i(TAG, "OUR ROW TAPPED #" + n + " via " + source + " (armed=" + known.name() + ")")

        // v3.1.1 真机修复（19:06 adb 日志）。
        //
        // 【原 bug】这里原来是 `dispatchRowAction(rowView) ?: known` —— 重新判定的结果**无条件优先**，
        // 只有当它返回 null 时才回退到 known。真机日志显示这正好是致命的一步：
        //
        //     tap resolved to our row: analyse                          ← actionForTap 判对了
        //     OUR ROW TAPPED #1 via View.performClick (armed=analyse)   ← 正确传进来
        //     tap action resolved: balloon-switch (rowView=LinearLayout) ← 却被重新判定覆盖成浮窗
        //
        // 原因：rowView 取到的是**共享容器**（微信把所有菜单项装进同一个 LinearLayout），
        // 容器里同时含「分析」和「浮窗」两个标题，重新判定必然失真。
        //
        // 【修法】信任已经确定的身份：known != NONE 时它来自 actionForTap（从**被点的那个 View**
        // 向上找最小匹配行），比"从一个可能过大的 rowView 重新判"更精确。
        // 只有在 known == NONE 时（performClick 探针、按 id 分派等"只知道点到了我们的行"的路径）
        // 才需要靠 dispatchRowAction 去补一个身份出来。
        val rowView = ownRow ?: armedRow ?: pressedView
        val action = if (known != RowAction.NONE) {
            known
        } else {
            try {
                dispatchRowAction(rowView) ?: RowAction.NONE
            } catch (_: Throwable) {
                RowAction.NONE
            }
        }
        Log.i(TAG, "tap action resolved: " + action.name() + " (rowView=" +
            (rowView?.javaClass?.simpleName ?: "null") + ", source=" + source +
            ", known=" + known.name() + ")")

        if (action == RowAction.BALLOON_SWITCH) {
            handleBalloonSwitchTap()
            return
        }

        // v2.4.2: the message captured at long-press time is authoritative.
        val msg = pendingMsg ?: pressedMsg
        // And the VIEW comes from the dedicated reference, because `lastView` is shared and can hold a
        // different view by now -- that produced "msg=null view=MMNeat7extView" and 未能识别该消息.
        val view = pendingView ?: pressedView ?: lastView.get()
        Log.i(TAG, "handleClick msg=" + (msg?.javaClass?.simpleName ?: "null") +
            " view=" + (view?.javaClass?.simpleName ?: "null"))
        if (msg == null && view == null) {
            Log.w(TAG, "handleClick: nothing to analyse")
            return
        }
        clearHijacks()
        // v2.4.2: consumed -- a later tap must not reuse this row.
        pressedView = null
        pressedTag = null
        pressedMsg = null
        armedRow = null
        // Allow the next long-press to be handled immediately.
        lastTapAt = 0L
        try {
            if (msg != null) {
                controller.startAnalysisFor(msg, view)
            } else if (view != null) {
                controller.startAnalysis(view)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "starting analysis failed", t)
        }
    }

    /**
     * item NEW: re-reads a row's identity at TAP time.
     *
     * Returns null when the row can no longer be identified, in which case the caller falls back to the
     * action its listener was armed with.
     */
    private fun dispatchRowAction(rowView: View?): RowAction? {
        if (rowView == null) return null
        return try {
            // The row may BE the anchor, so ask about the row itself first.
            val direct = rowActionOf(rowView, rowView)
            if (direct != RowAction.NONE) return direct
            // Otherwise look for one of our title views inside it and re-resolve from there.
            for (t in OWN_MENU_TITLES) {
                val tv = findTitleViewNamed(rowView, t, 0) ?: continue
                val a = rowActionOf(rowView, tv)
                if (a != RowAction.NONE) return a
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** item NEW: the first TextView in `v`'s subtree whose text equals `title`. */
    private fun findTitleViewNamed(v: View, title: String, depth: Int): View? {
        if (depth > 40) return null
        if (v is TextView) {
            val t = v.text
            if (t != null && t.toString() == title) return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findTitleViewNamed(v.getChildAt(i), title, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * item NEW: the balloon on/off entry was pressed.
     *
     * Behaviour asked for: READ the current state, then flip it. Off -> the balloon appears; on -> it
     * disappears and STAYS gone. The state is persisted, so it survives a restart, and it is applied
     * immediately rather than waiting for a re-entry into the chat.
     */
    private fun handleBalloonSwitchTap() {
        val ctx = armedRow?.context
            ?: pressedView?.context
            ?: pendingView?.context
            ?: lastView.get()?.context
            ?: com.luoliaoo.wechat.core.AppContextHolder.get()
        Log.i(TAG, "balloon switch tapped (ctx=" + (ctx != null) + ")")

        // The row is consumed either way; do it once, here.
        clearHijacks()
        pressedView = null
        pressedTag = null
        pressedMsg = null
        armedRow = null
        pendingMsg = null
        pendingView = null
        lastTapAt = 0L

        if (ctx == null) {
            Log.w(TAG, "balloon switch: no Context; the choice cannot be saved")
            return
        }

        try {
            val on = com.luoliaoo.wechat.data.ConfigRepo.toggleBalloonEnabled(ctx)
            Log.i(TAG, "balloon switch -> " + (if (on) "ON" else "OFF"))
            if (on) {
                com.luoliaoo.wechat.ui.FloatingPanel.showGlobalOn(ctx)
            } else {
                com.luoliaoo.wechat.ui.FloatingPanel.hideGlobal()
            }
            showSwitchToast(ctx, on)
        } catch (t: Throwable) {
            Log.e(TAG, "balloon switch failed", t)
        }
    }

    /** item NEW: a short confirmation, so the user can see which way the switch went. */
    private fun showSwitchToast(ctx: android.content.Context, on: Boolean) {
        try {
            /* v9.6.0 用户指定文案：
               开启 → 「悬浮球已开启  开启时将启动自动居中  防丢悬浮球」
               关闭 → 「悬浮球已关闭」
               开启时多出的那句是在**解释刚发生的行为**：开启会把球重新摆回屏幕中央
               （见 FloatingPanel.showGlobalOn 里的 recentre 处理），用户看到球换位置时
               才知道这是有意的，而不是「我的位置被弄丢了」。
               用户要求用中文，本文件其余用户可见文案走 \\uXXXX 转义（纯 ASCII 字节），
               此处沿用同一约定。 */
            val text = if (on) {
                "\u60ac\u6d6e\u7403\u5df2\u5f00\u542f  \u5f00\u542f\u65f6\u5c06\u542f\u52a8\u81ea\u52a8\u5c45\u4e2d  \u9632\u4e22\u60ac\u6d6e\u7403"
            } else {
                "\u60ac\u6d6e\u7403\u5df2\u5173\u95ed"
            }
            val act = findActivity(ctx)
            if (act != null) {
                toast(act, text)
            } else {
                android.widget.Toast.makeText(
                    ctx, "\u843d\u804a\uff1a" + text, android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (_: Throwable) {
            // cosmetic
        }
    }

    // ============================ backups

    /**
     * Diagnostic + fallback: log every performClick (view class + text) so a tap is
     * always visible in the log. If the tapped view belongs to our row, act on it.
     */
    private fun installPerformClickProbe(): Int {
        val cls = Class.forName("android.view.View", false, resolver.classLoader())
        val m = cls.getDeclaredMethod("performClick")
        LegacyHook.hook(m)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .intercept { chain ->
                val self = chain.thisObject as? View
                if (self != null) {
                    val c = clickProbe.incrementAndGet()
                    var label = ""
                    if (self is TextView) {
                        val s = self.text
                        if (s != null) label = s.toString().take(20)
                    }
                    // item NEW: resolve WHICH entry from the tapped view, so this path routes the
                    // balloon switch correctly instead of assuming 分析.
                    val action = actionForTap(self)
                    val ours = action != RowAction.NONE
                    if (c <= 120 || ours) {
                        Log.i(TAG, "performClick[" + c + "] " + self.javaClass.name +
                            " text='" + label + "' ours=" + ours + " action=" + action.name())
                    }
                    if (ours) {
                        Log.i(TAG, "OUR ROW CLICKED via View.performClick")
                        onOurRowTapped("View.performClick", action)
                        return@intercept
                    }
                }
                chain.proceed()
            }
        Log.i(TAG, "hooked View.performClick (probe)")
        return 1
    }

    /** True when the subtree contains an editable field (i.e. it is the input area). */
    private fun containsEditText(v: View, depth: Int): Boolean {
        if (depth > 12) return false
        if (v is android.widget.EditText) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (containsEditText(v.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun installItemDispatcher(itemClassName: String): Int {
        val cls = try {
            Class.forName(itemClassName, false, resolver.classLoader())
        } catch (t: Throwable) {
            return 0
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "c" || m.parameterCount != 0) continue
            if (m.returnType != Boolean::class.javaPrimitiveType) continue
            LegacyHook.hook(m)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val self = chain.thisObject
                    val id = try {
                        itemIdOf(self)
                    } catch (_: Throwable) {
                        -1
                    }
                    Log.i(TAG, "item dispatch id=" + id + " title=" + itemTitleOf(self))
                    // item NEW: the id is authoritative here -- it says exactly which of our two
                    // entries was dispatched, so no title guessing is involved.
                    if (id == ITEM_ID) {
                        onOurRowTapped("nf5.h4.c", RowAction.ANALYSE)
                        return@intercept
                    }
                    if (id == BALLON_ITEM_ID) {
                        onOurRowTapped("nf5.h4.c", RowAction.BALLOON_SWITCH)
                        return@intercept
                    }
                    chain.proceed()
                }
            n++
            Log.i(TAG, "hooked item dispatcher: " + itemClassName + ".c()")
        }
        return n
    }

    private fun installAdapterViewFunnel(): Int {
        val m = AdapterView::class.java.getDeclaredMethod(
            "performItemClick", View::class.java, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        LegacyHook.hook(m)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .intercept { chain ->
                Log.i(TAG, "adapter row tap pos=" + (chain.getArg(1) as? Int ?: -1))
                chain.proceed()
            }
        Log.i(TAG, "hooked AdapterView.performItemClick")
        return 1
    }

    private fun installPerformLongClick(): Int {
        val cls = Class.forName("android.view.View", false, resolver.classLoader())
        val m = cls.getDeclaredMethod("performLongClick")
        LegacyHook.hook(m)
            .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
            .intercept { chain ->
                val self = chain.thisObject as? View
                if (self != null) lastView.set(self)
                chain.proceed()
            }
        Log.i(TAG, "hooked View.performLongClick")
        return 1
    }

    // ============================ injection (restored)

    /**
     * The once-per-menu guard, shared by every injection path.
     *
     * `add` runs many times while one menu is built and several entry points can fire for the same menu, so an
     * IDENTITY set keeps the item to one per menu.
     */
    fun markInjectedOnce(menu: Any): Boolean = try {
        injectedMenus.add(menu)
    } catch (_: Throwable) {
        false
    }

    /**
     * Hooks `onCreateMMMenu(<menu>)` on the classes that declare it -- WeKit's population.
     *
     * Found by SHAPE (method name plus a parameter assignable to the menu class), so no obfuscated name list is
     * involved and the same code works on 8.0.49 / 8.0.76 / 8.0.77 / 8.0.78.
     */
    private fun hookContributors(menuCls: Class<*>, msgCls: Class<*>): Int {
        val candidates = try {
            resolver.contributorClasses(menuCls)
        } catch (t: Throwable) {
            Log.w(TAG, "contributorClasses failed: " + t.message)
            emptyList()
        }
        Log.i(TAG, "shape scan: candidate contributors=" + candidates.size)

        var n = 0
        for (cls in candidates) {
            for (m in cls.declaredMethods) {
                if (m.name != "onCreateMMMenu") continue
                val ps = m.parameterTypes
                if (ps.isEmpty() || !menuCls.isAssignableFrom(ps[0])) continue
                try {
                    LegacyHook.hook(m)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val menu = chain.getArg(0)
                                if (menu != null && markInjectedOnce(menu)) {
                                    injectInto(menu, lastMessage.get(), lastView.get())
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "shape inject failed: " + t.message)
                            }
                            result
                        }
                    n++
                } catch (_: Throwable) {
                    // one method failing must not stop the rest
                }
            }
        }
        return n
    }

    /**
     * Adds our item to a menu object by reflection.
     *
     * The Drawable overload is preferred -- it is the one WeKit uses and the one WeChat's own items go through
     * -- with the plain forms kept as fallbacks, so a build with a different signature still works.
     */
    private fun injectInto(menu: Any, msg: Any?, view: View?) {
        try {
            Log.i(
                TAG,
                "LONG-PRESS SEEN: user=" + android.os.Process.myUid() / 100000 +
                    " pid=" + android.os.Process.myPid() +
                    " attempt=" + com.luoliaoo.wechat.core.ModuleStats.menuAttempts +
                    " msg=" + (msg != null) + " view=" + (view != null),
            )
        } catch (_: Throwable) {
            Log.i(TAG, "LONG-PRESS SEEN")
        }
        com.luoliaoo.wechat.core.ModuleStats.noteMenuAttempt()

        pendingMsg = msg
        pendingView = view

        val cls = menu.javaClass

        // v2.8.0 (item 2): WEKIT'S EXACT LOOKUP FIRST.
        //
        //     menu.reflekt().firstMethod {
        //         parameters(Int::class, CharSequence::class, Drawable::class)
        //         returnType = android.view.MenuItem::class
        //     }
        //
        // Declared-signature matching, tried before any name-based guess. The Drawable is built from our own
        // icon, so the item arrives with its icon already attached -- which is why WeKit's items always show one.
        val wekitStyle: java.lang.reflect.Method? = try {
            cls.methods.firstOrNull { m ->
                m.parameterCount == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    CharSequence::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    android.graphics.drawable.Drawable::class.java.isAssignableFrom(m.parameterTypes[2]) &&
                    android.view.MenuItem::class.java.isAssignableFrom(m.returnType)
            }
        } catch (_: Throwable) {
            null
        }
        if (wekitStyle != null) {
            val ctx0 = resolveContext(view, menu)
            if (ctx0 != null) {
                try {
                    val sizePx = (ICON_SIZE_DP * ctx0.resources.displayMetrics.density).toInt()
                    val drawable = com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx0, sizePx)
                    wekitStyle.isAccessible = true
                    val it0 = adderItemOf(wekitStyle, menu, ITEM_ID, ITEM_TITLE, drawable, RowAction.ANALYSE)
                    if (it0 != null) {
                        // item NEW: the balloon switch rides along on the same path, so both entries
                        // exist whichever injection point won the race.
                        val it1 = adderItemOf(
                            wekitStyle, menu, BALLON_ITEM_ID, BALLON_ITEM_TITLE, drawable,
                            RowAction.BALLOON_SWITCH,
                        )
                        Log.i(
                            TAG,
                            "MENU ITEMS INJECTED via the Drawable overload (WeKit's way) with icon" +
                                " (balloonSwitch=" + (it1 != null) + ")",
                        )
                        com.luoliaoo.wechat.core.ModuleStats.noteMenuInjected()
                        return
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "the Drawable overload failed: " + t.message)
                }
            } else {
                Log.w(TAG, "the Drawable overload is available but no Context could be resolved")
            }
        }

        // Fallback: the plain adders, then set the icon on the item.
        //
        // The previous version passed null into the Drawable overload `add(int, CharSequence, Drawable)`,
        // which produces an item with NO icon. The plain adder plus `mi.icon = ...` is what worked before.
        val adders = ArrayList<java.lang.reflect.Method>()
        try {
            cls.methods.firstOrNull {
                it.name == "c" && it.parameterCount == 5 &&
                    it.parameterTypes[3] == CharSequence::class.java
            }?.let { adders.add(it) }
            cls.methods.firstOrNull {
                it.name == "add" && it.parameterCount == 4 &&
                    it.parameterTypes[3] == CharSequence::class.java
            }?.let { adders.add(it) }
            cls.methods.firstOrNull {
                it.name == "add" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == CharSequence::class.java
            }?.let { adders.add(it) }
        } catch (_: Throwable) {
            // fall through: the loop below reports it
        }

        if (adders.isEmpty()) {
            Log.w(TAG, "no usable menu adder on " + cls.name)
            return
        }

        // v2.7.1: the Context is resolved from a chain of sources because the contributor's `view`
        // argument is null for the method that fires here.
        val ctx = resolveContext(view, menu)
        if (ctx == null) Log.w(TAG, "MENU ICON skipped: no Context from any source")

        // item NEW: both entries, through the same helper.
        val first = addPlainItem(adders, menu, ITEM_ID, ITEM_TITLE, ctx, RowAction.ANALYSE)
        val second = addPlainItem(
            adders, menu, BALLON_ITEM_ID, BALLON_ITEM_TITLE, ctx, RowAction.BALLOON_SWITCH,
        )
        if (first == null) {
            Log.w(TAG, "all menu adders failed on " + cls.name)
            return
        }
        Log.i(TAG, "MENU ITEMS INJECTED (plain adders, balloonSwitch=" + (second != null) + ")")
        com.luoliaoo.wechat.core.ModuleStats.noteMenuInjected()
    }

    /**
     * item NEW: invokes a Drawable-overload adder and arms the created item, returning it.
     *
     * Shared by both entries and both injection paths, so a change to how an entry is armed cannot
     * apply to one item and not the other.
     */
    private fun adderItemOf(
        adder: java.lang.reflect.Method,
        menu: Any,
        id: Int,
        title: String,
        drawable: android.graphics.drawable.Drawable?,
        action: RowAction,
    ): Any? = try {
        adder.isAccessible = true
        val item = adder.invoke(menu, id, title, drawable)
        if (item != null) {
            try {
                (item as android.view.MenuItem).setOnMenuItemClickListener {
                    onOurRowTapped("MenuItem listener", action)
                    true
                }
            } catch (_: Throwable) {
                // the click also arrives through the row hijack
            }
        }
        item
    } catch (t: Throwable) {
        Log.w(TAG, "the Drawable adder failed for " + title + ": " + t.message)
        null
    }

    /**
     * item NEW: adds one entry through the plain (non-Drawable) adders, then sets its icon.
     *
     * The fallback path used to add only 分析; the balloon switch has to exist there too, otherwise a
     * WeChat build without the Drawable overload would silently lose the new entry.
     */
    private fun addPlainItem(
        adders: List<java.lang.reflect.Method>,
        menu: Any,
        id: Int,
        title: String,
        ctx: android.content.Context?,
        action: RowAction,
    ): Any? {
        var item: Any? = null
        for (a in adders) {
            item = try {
                a.isAccessible = true
                when (a.parameterCount) {
                    5 -> a.invoke(menu, 0, id, 0, title, 0)
                    4 -> a.invoke(menu, 0, id, 0, title)
                    else -> a.invoke(menu, title)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "menu." + a.name + " failed: " + t.message)
                null
            }
            if (item != null) break
        }
        if (item == null) {
            Log.w(TAG, "all plain menu adders failed for " + title)
            return null
        }
        try {
            val mi = item as android.view.MenuItem
            mi.setOnMenuItemClickListener {
                onOurRowTapped("MenuItem listener", action)
                true
            }
            if (ctx != null) {
                try {
                    val sizePx = (ICON_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
                    mi.icon = com.luoliaoo.wechat.ui.AnalysisIcon.toDrawable(ctx, sizePx)
                } catch (t: Throwable) {
                    Log.w(TAG, "setIcon failed for " + title + ": " + t.message)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attaching the click listener failed for " + title + ": " + t.message)
        }
        return item
    }

    /** The Activity WeChat is showing, read through ActivityThread. */
    private fun currentActivity(): android.app.Activity? = try {
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

    /** The Activity behind a Context, walking the wrapper chain. */
    private fun findActivity(ctx: android.content.Context?): android.app.Activity? {
        var c = ctx
        var depth = 0
        while (c != null && depth < 8) {
            if (c is android.app.Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun itemIdOf(item: Any): Int = try {
        item.javaClass.getMethod("getItemId").invoke(item) as? Int ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun itemTitleOf(item: Any): String = try {
        (item.javaClass.getMethod("getTitle").invoke(item) as? CharSequence)?.toString() ?: ""
    } catch (_: Throwable) {
        ""
    }

}

