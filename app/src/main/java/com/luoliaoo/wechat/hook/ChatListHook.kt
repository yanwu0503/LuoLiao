package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.LegacyHook

import android.view.View
import android.view.ViewGroup
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.ui.AnalysisCard
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Card insertion: attaches the analysis card below the long-pressed message.
 *
 * CRITICAL: this file contains NO compile-time reference to androidx classes.
 * An earlier revision wrote the type androidx.recyclerview.widget.RecyclerView
 * directly; because that dependency is compileOnly, the reference failed to
 * resolve inside WeChat ("Failed resolution of: ...RecyclerView;").
 * Everything androidx-related is now looked up by NAME and driven by reflection.
 *
 * RECYCLING SAFETY: card content always comes from AnalysisController.cards keyed
 * by (talker#msgId); every bind re-renders and onViewRecycled hard-removes the
 * card, so a recycled item view can never show another message's card.
 *
 * DISCOVERY: hook the stable RecyclerView.setAdapter(Adapter) API, capture the
 * adapter, decide whether it is the chat adapter by checking whether it holds
 * message objects, then dynamically hook that adapter's bind callbacks. No WeChat
 * class name is hardcoded.
 */
class ChatListHook(
    
    private val controller: AnalysisController,
) {

    private companion object {
        const val TAG = "LuoLiao"
        const val RV_CLASS = "androidx.recyclerview.widget.RecyclerView"
        const val RV_ADAPTER_CLASS = "androidx.recyclerview.widget.RecyclerView\$Adapter"

        /** v2.5.2: rate-limits the per-event log line "recycledLogged" so the log stays readable. */
        private val recycledLogged = java.util.concurrent.atomic.AtomicInteger(0)

        /** v2.5.2: rate-limits the per-event log line "setAdapterLogged" so the log stays readable. */
        private val setAdapterLogged = java.util.concurrent.atomic.AtomicInteger(0)

        /** v2.5.2: rate-limits the per-event log line "watchdogLogged" so the log stays readable. */
        private val watchdogLogged = java.util.concurrent.atomic.AtomicInteger(0)
    }

    /** item 4: adapters whose message list was empty when they were first seen. */
    private val pendingAdapters =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())

    private val hookedClasses = ConcurrentHashMap.newKeySet<String>()

    /** The most recent RecyclerView seen by the setAdapter hook. */
    @Volatile
    private var lastSeenRv: Any? = null
    private val listFieldCache = ConcurrentHashMap<String, Field>()

    @Volatile
    private var rvClass: Class<*>? = null

    fun install() {
        // Resolve RecyclerView through the HOST classloader at runtime.
        val hostLoader = controller.hostClassLoader()
        val rv = try {
            Class.forName(RV_CLASS, false, hostLoader)
        } catch (t: Throwable) {
            Log.w(TAG, "RecyclerView not found via host loader; card insertion disabled", t)
            null
        }
        if (rv == null) return
        rvClass = rv
        Log.i(TAG, "RecyclerView resolved: " + rv.name)

        // THE FIX: resolve setAdapter on the class that DECLARES it.
        //
        // The old lookup searched `rv.methods` and matched on the PARAMETER type, so on WeChat it selected
        //   setAdapter found: androidx.recyclerview.widget.f2
        // where `f2` is WeChat's SUBCLASS. Hooking a subclass view of a method that the subclass does not
        // override registers the hook against the WRONG declaring class, and ART dispatches to the real
        // declaration -- so the hook NEVER fired. The log proves it:
        //   "hooked RecyclerView.setAdapter"   yet   "HEARTBEAT: ... adapters=0"
        //
        // The method is now taken from the RecyclerView class itself, and its declaring class is logged so the
        // next log proves which one was hooked.
        val adapterClsName = RV_ADAPTER_CLASS
        val setAdapter = try {
            rv.getDeclaredMethod("setAdapter", Class.forName(adapterClsName, false, rv.classLoader))
        } catch (_: Throwable) {
            rv.methods.firstOrNull { it.name == "setAdapter" && it.parameterCount == 1 }
        }

        if (setAdapter == null) {
            Log.w(TAG, "RecyclerView.setAdapter not found; card insertion disabled")
            return
        }
        Log.i(
            TAG,
            "setAdapter found: declaring=" + setAdapter.declaringClass.name +
                " declaringIsRecyclerView=" + (setAdapter.declaringClass.name == rv.name) +
                " param=" + setAdapter.parameterTypes[0].name,
        )

        try {
            LegacyHook.hook(setAdapter)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val adapter = chain.getArg(0)!!
                        val rv = chain.thisObject
                        if (rv != null) {
                            lastSeenRv = rv
                            controller.noteRecyclerView(rv)
                            updateFloatingVisibility(rv, adapter)
                        }
                        // v2.5.2: one line per adapter change (dozens per session) buried the useful lines.
                        if (setAdapterLogged.incrementAndGet() <= 12) Log.i(TAG, "setAdapter called: adapter=" +
                            (adapter?.javaClass?.name ?: "null"))
                        // The heartbeat reports this, so "the UI was never driven" is distinguishable from
                        // "the module is dead".
                        com.luoliaoo.wechat.core.ModuleStats.noteAdapter()
                        if (adapter != null) onAdapterSet(adapter)
                        if (rv != null) {
                            val cur = rv.javaClass.methods.firstOrNull {
                                it.name == "getAdapter" && it.parameterCount == 0
                            }?.invoke(rv)
                            if (cur != null && cur !== adapter) onAdapterSet(cur)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "setAdapter handling failed: " + t.message)
                    }
                    result
                }
            Log.i(TAG, "hooked RecyclerView.setAdapter (bonus discovery only)")
        } catch (t: Throwable) {
            Log.w(TAG, "installing card insertion failed: " + t.message, t)
        }

        installLayoutTrigger(rv)
    }

    /**
     * Hooks RecyclerView.onLayout to trigger a reconcile.
     *
     * This is the fix for cards vanishing after scrolling: onLayout runs when rows are
     * (re)laid out -- scrolling, switching chats, or coming back to the chat screen -- which is
     * exactly when a recycled row needs its card redrawn. The controller re-locates rows by
     * message id, so it does not depend on any cached View.
     */
    /**
     * Hooks RecyclerView.onLayout so a reconcile runs whenever rows are laid out.
     *
     * The previous version searched `rv.methods` (PUBLIC members only) and therefore never found it:
     * View.onLayout is PROTECTED, so it is absent from getMethods(). The log confirmed it --
     * "RecyclerView.onLayout not found" -- and that is why a card never came back after its row was
     * recycled by scrolling. The hierarchy is now walked with getDeclaredMethods(), which includes
     * protected members.
     */
    /**
     * A fallback that needs NO hook: walks the current Activity's view tree, finds every RecyclerView and
     * reads its adapter.
     *
     * WHY: if `setAdapter` never fires (as the log showed: `adapters=0`), nothing downstream works. Reading
     * the adapters directly cannot fail the same way, because it depends only on the view tree, which is
     * public API.
     */
    /**
     * Retries adapter discovery every few seconds until one is found.
     *
     * WHY: `setAdapter` never fired on WeChat 8.0.77 (the log showed `adapters=0`), so the module must be able
     * to find adapters without that hook. A view-tree walk is public API and cannot fail the same way. Once an
     * adapter has been seen the watchdog stops, so the cost is bounded.
     */
    fun startAdapterWatchdog() {
        if (adapterWatchdogStarted) return
        adapterWatchdogStarted = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val task = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                try {
                    if (com.luoliaoo.wechat.core.ModuleStats.adapters == 0) {
                        discoverAdaptersFromViews()
                        if (tries % 5 == 0) {
                            Log.i(
                                TAG,
                                "adapter watchdog: still 0 after " + tries + " tries" +
                                    " (views exist=" + (com.luoliaoo.wechat.core.AppContextHolder.get() != null) + ")",
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // never disturb WeChat
                }
                if (tries < 30) handler.postDelayed(this, 3000L)
            }
        }
        handler.postDelayed(task, 3000L)
        // v2.5.2: the watchdog retries every second while a screen loads.
        if (watchdogLogged.incrementAndGet() <= 10) Log.i(TAG, "adapter watchdog started (every 3s, up to 30 tries)")
    }

    @Volatile
    private var adapterWatchdogStarted = false

    fun discoverAdaptersFromViews() {
        try {
            // AppContextHolder exposes a Context, not an Activity, so the window is reached by walking up
            // the context chain. A failure here is silent: the fallback must never disturb WeChat.
            var ctx: android.content.Context? = com.luoliaoo.wechat.core.AppContextHolder.get()
            var activity: android.app.Activity? = null
            while (ctx is android.content.ContextWrapper) {
                if (ctx is android.app.Activity) {
                    activity = ctx
                    break
                }
                ctx = ctx.baseContext
            }
            val act = activity ?: return
            val root = act.window?.decorView ?: return
            val found = ArrayList<Any>(4)
            walkForRecyclerViews(root, found, 0)
            for (rvView in found) {
                val adapter = try {
                    rvView.javaClass.methods.firstOrNull {
                        it.name == "getAdapter" && it.parameterCount == 0
                    }?.invoke(rvView)
                } catch (_: Throwable) {
                    null
                }
                if (adapter != null) onAdapterSet(adapter)
            }
            if (found.isNotEmpty()) {
                Log.i(TAG, "adapter discovery from the view tree: " + found.size + " RecyclerView(s)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "adapter discovery failed: " + t.message)
        }
    }

    private fun walkForRecyclerViews(v: android.view.View, out: MutableList<Any>, depth: Int) {
        if (depth > 40) return
        val n = v.javaClass.name
        if (n.startsWith("androidx.recyclerview.widget.") && n != RV_CLASS) {
            out.add(v)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walkForRecyclerViews(v.getChildAt(i), out, depth + 1)
        }
    }

    private fun installLayoutTrigger(rv: Class<*>) {
        var onLayout: java.lang.reflect.Method? = null
        var c: Class<*>? = rv
        var depth = 0
        while (c != null && depth < 8 && onLayout == null) {
            for (m in c.declaredMethods) {
                if (m.name == "onLayout" && m.parameterCount == 4) {
                    onLayout = m
                    break
                }
            }
            c = c.superclass
            depth++
        }
        if (onLayout == null) {
            Log.w(TAG, "RecyclerView.onLayout not found even via declaredMethods")
            return
        }
        Log.i(TAG, "onLayout located on " + onLayout.declaringClass.name)
        try {
            LegacyHook.hook(onLayout)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        controller.requestReconcile()
                    } catch (_: Throwable) {
                        // never disturb layout
                    }
                    result
                }
            Log.i(TAG, "hooked RecyclerView.onLayout -> reconcile trigger")
            // Second trigger: the layout manager's scroll callbacks fire while rows recycle.
            installScrollTrigger(rv)
        } catch (t: Throwable) {
            Log.w(TAG, "hooking onLayout failed: " + t.message)
        }
    }

    /**
     * item 2a / 3: keeps the floating balloon in step with the screen.
     *
     * WeChat uses a different Activity for the contact list and for a chat, so this shows the balloon on
     * the chat screen and removes it everywhere else. Called from every RecyclerView it sees, which
     * covers both screens; the balloon therefore appears as soon as a chat is opened, with no analysis
     * needed.
     */
    private fun updateFloatingVisibility(rv: Any?, adapter: Any?) {
        try {
            val ctx = (rv as? View)?.context ?: return
            val panel = com.luoliaoo.wechat.ui.FloatingPanel

            // The ADAPTER identifies the screen: the log shows WeChat uses one Activity (LauncherUI) for
            // both the conversation list and a chat, so the Activity cannot tell them apart. Reporting
            // the adapter name here lets the panel decide correctly -- previously entering a chat was
            // mistaken for leaving it and the balloon was removed.
            val adapterName = adapter?.javaClass?.name
            panel.noteAdapter(adapterName)

            // The reported bug: chat B showed chat A's records. The adapter identity is what tells us the
            // user moved to another conversation, so the controller is told to drop the previous chat's
            // messages. A stable identity is used (class name + hash) so a re-bind of the SAME adapter
            // does not count as a change.
            try {
                val id = (adapterName ?: "none") + "#" + System.identityHashCode(adapter)
                controller.enterChatScreen(id)
                // Drop any cached talker at once, instead of waiting for the next tap.
                com.luoliaoo.wechat.ui.FloatingPanel.onChatScreenChanged()
            } catch (t: Throwable) {
                Log.w(TAG, "enterChatScreen failed: " + t.message)
            }

            // item 1: the balloon is GLOBAL. Whatever screen this RecyclerView belongs to, the
            // balloon is simply made sure to exist -- it is never hidden or closed.
            Log.i(
                TAG,
                "floating: ensuring the global balloon (adapter=" + (adapterName ?: "null") + ")",
            )
            panel.showGlobal(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "floating visibility update failed: " + t.message)
        }
    }

    private fun onAdapterSet(adapter: Any) {
        val cls = adapter.javaClass
        val name = cls.name
        if (hookedClasses.contains(name)) return

        // THE FIX: the list field is a CONVENIENCE, not a requirement.
        //
        // Gating the whole adapter on `findMessageListField` is what silently killed live history: the CHAT
        // adapter (com.tencent.mm.pluginsdk.ui.chat.lg) was seen, its list was empty at setAdapter time and
        // its element type was not recognisable, so the adapter was dropped and nothing was ever recorded.
        //
        // The message is now resolved with `adapter.getItem(position)`, exactly as CardBinder does, which
        // needs no knowledge of the adapter's fields. So the bind hook is installed for every chat adapter
        // and the list field is only an extra, optional source.
        val field = findMessageListField(adapter)
        if (field == null) {
            Log.i(TAG, "adapter " + name + " has no list field; using getItem(position) only")
        }
        // Publish this as the chat list so reconcile passes can find rows by message id.
        controller.noteRecyclerView(lastSeenRv)
        if (!hookedClasses.add(name)) return
        if (field != null) listFieldCache[name] = field

        Log.i(
            TAG,
            "CHAT ADAPTER HOOKED: " + name +
                " (list field=" + (field?.name ?: "none") + ")",
        )
        hookBind(cls)
        hookRecycle(cls)
    }

    /** Finds the List field holding message objects. */
    private fun findMessageListField(adapter: Any): Field? {
        // BEST EFFORT 1: a field holding a non-empty list of messages.
        var byContent: Field? = null
        // BEST EFFORT 2: a field whose DECLARED type is a list of message-like objects.
        //
        // The content test alone is why this returned null: a chat screen is usually created with an EMPTY
        // message list, and the rows arrive afterwards. Requiring a non-empty list therefore skipped the
        // adapter entirely -- `onBound` never ran and live history never recorded anything.
        var byType: Field? = null

        var cls: Class<*>? = adapter.javaClass
        var depth = 0
        while (cls != null && depth < 6) {
            for (f in cls.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue

                // --- by type
                if (byType == null && listElementLooksLikeMsg(f)) {
                    byType = f
                }

                // --- by content
                val v = try {
                    f.isAccessible = true
                    f.get(adapter) as? List<*>
                } catch (_: Throwable) {
                    null
                } ?: continue
                val first = v.firstOrNull() ?: continue
                if (looksLikeMsg(first) && byContent == null) {
                    byContent = f
                }
            }
            cls = cls.superclass
            depth++
        }

        val chosen = byContent ?: byType
        if (chosen != null) {
            Log.i(
                TAG,
                "message list field = " + chosen.name + " (by " +
                    (if (byContent != null) "content" else "type") + ")",
            )
        }
        return chosen
    }

    /**
     * item 4: does this List field's DECLARED element type look like a WeChat message?
     *
     * Kotlin/Java erasure hides the element type, but the generic signature is still available, and the
     * name of the element class is enough to recognise a message. This lets an EMPTY list be identified,
     * which the content check cannot do.
     */
    private fun listElementLooksLikeMsg(f: Field): Boolean {
        return try {
            val sig = f.genericType?.typeName ?: return false
            val looks = sig.contains("storage.e9") || sig.contains("Message") ||
                sig.contains("MsgInfo") || sig.contains("storage.")
            if (looks) Log.i(TAG, "list field " + f.name + " typed as " + sig)
            looks
        } catch (_: Throwable) {
            false
        }
    }

    private fun hookBind(adapterCls: Class<*>) {
        val bind = findInHierarchy(adapterCls, "onBindViewHolder", 2)
            ?: findInHierarchy(adapterCls, "onBindViewHolder", 3)
        if (bind == null) {
            Log.w(TAG, adapterCls.simpleName + " has no onBindViewHolder")
            return
        }
        try {
            LegacyHook.hook(bind)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val holder = chain.getArg(0)!!
                        val position = chain.getArg(1) as? Int ?: -1
                        if (holder != null) onBound(chain.thisObject, holder, position)
                    } catch (t: Throwable) {
                        Log.w(TAG, "card render failed: " + t.message)
                    }
                    result
                }
            Log.i(TAG, "hooked onBindViewHolder: " + adapterCls.simpleName)
        } catch (t: Throwable) {
            Log.w(TAG, "hook onBindViewHolder failed: " + t.message)
        }
    }

    private fun hookRecycle(adapterCls: Class<*>) {
        val recycle = findInHierarchy(adapterCls, "onViewRecycled", 1) ?: return
        try {
            LegacyHook.hook(recycle)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val itemView = viewOf(chain.getArg(0)!!)
                        if (itemView is ViewGroup) AnalysisCard.removeFrom(itemView)
                    } catch (_: Throwable) {
                        // best effort
                    }
                    result
                }
            // v2.5.2: fired 70 times per window; the hook is installed once, so this is noise.
            if (recycledLogged.incrementAndGet() <= 8) Log.i(TAG, "hooked onViewRecycled")
        } catch (t: Throwable) {
            Log.w(TAG, "hook onViewRecycled failed: " + t.message)
        }
    }

    /**
     * Called for every chat row bind.
     *
     * All card work is delegated to the controller, which enforces card ownership: a card
     * belonging to another message (i.e. a row recycled from a different conversation) is
     * removed before anything is drawn. This is what stops cards leaking across chats.
     */
    private fun onBound(adapter: Any, holder: Any, position: Int) {
        val itemView = viewOf(holder) as? ViewGroup ?: return

        var msg = findMessageInHolder(holder)
        if (msg == null) msg = messageAt(adapter, position)

        // tap-to-detect + item 1: remember the message, name the current chat, and record it live.
        if (msg != null) {
            controller.noteRecentMessage(msg)
            controller.onLiveMessage(msg)
        }
        controller.onChatRowBound(itemView, msg)
    }

    private fun messageAt(adapter: Any, position: Int): Any? {
        if (position < 0) return null

        // item 4: `getItem(position)` FIRST -- it needs no list field, so it works for the chat adapter
        // whose internals could not be identified.
        try {
            val getItem = adapter.javaClass.methods.firstOrNull {
                it.name == "getItem" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (getItem != null) {
                getItem.isAccessible = true
                val o = getItem.invoke(adapter, position)
                if (looksLikeMsg(o)) return o
            }
        } catch (_: Throwable) {
            // fall through to the list field
        }

        val name = adapter.javaClass.name
        var f = listFieldCache[name]
        if (f == null) {
            f = findMessageListField(adapter)
            if (f != null) listFieldCache[name] = f
        }
        if (f == null) return null
        val list = try {
            f.get(adapter) as? List<*>
        } catch (_: Throwable) {
            null
        } ?: return null
        val o = list.getOrNull(position) ?: return null
        return if (looksLikeMsg(o)) o else null
    }



    private fun findInHierarchy(cls: Class<*>, name: String, params: Int): Method? {
        var p: Class<*>? = cls
        var d = 0
        while (p != null && d < 6) {
            for (m in p.declaredMethods) {
                if (m.name == name && m.parameterCount == params) return m
            }
            p = p.superclass
            d++
        }
        return null
    }

    private fun viewOf(holder: Any?): View? {
        if (holder == null) return null
        if (holder is View) return holder
        return fieldOf(holder, "itemView") as? View
    }

    private fun findMessageInHolder(holder: Any): Any? {
        var cls: Class<*>? = holder.javaClass
        var depth = 0
        while (cls != null && depth < 6) {
            for (f in cls.declaredFields) {
                if (!looksLikeMsgClass(f.type)) continue
                val o = try {
                    f.isAccessible = true
                    f.get(holder)
                } catch (_: Throwable) {
                    null
                }
                if (o != null && looksLikeMsg(o)) return o
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    private fun looksLikeMsg(o: Any?): Boolean {
        if (o == null) return false
        return try {
            o.javaClass.methods.any { it.name == "getMsgId" && it.parameterCount == 0 } &&
                controller.access().content(o).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeMsgClass(c: Class<*>): Boolean = try {
        c.methods.any { it.name == "getMsgId" && it.parameterCount == 0 }
    } catch (_: Throwable) {
        false
    }

    private fun fieldOf(owner: Any, name: String): Any? {
        var p: Class<*>? = owner.javaClass
        var d = 0
        while (p != null && d < 6) {
            for (f in p.declaredFields) {
                if (f.name == name) {
                    return try {
                        f.isAccessible = true
                        f.get(owner)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            p = p.superclass
            d++
        }
        return null
    }

    /**
     * Extra trigger: RecyclerView.onScrolled(int,int), which fires on every scroll frame.
     *
     * onLayout alone is not enough: during a fast fling rows are rebound without a layout pass, and a
     * recycled row would keep a stale card. Two triggers make the redraw reliable.
     */
    private fun installScrollTrigger(rv: Class<*>) {
        var onScrolled: java.lang.reflect.Method? = null
        var c: Class<*>? = rv
        var depth = 0
        while (c != null && depth < 8 && onScrolled == null) {
            for (m in c.declaredMethods) {
                if (m.name == "onScrolled" && m.parameterCount == 2) {
                    onScrolled = m
                    break
                }
            }
            c = c.superclass
            depth++
        }
        if (onScrolled == null) {
            Log.i(TAG, "RecyclerView.onScrolled not found (layout trigger only)")
            return
        }
        try {
            LegacyHook.hook(onScrolled)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        controller.requestReconcile()
                    } catch (_: Throwable) {
                        // never disturb scrolling
                    }
                    result
                }
            Log.i(TAG, "hooked RecyclerView.onScrolled -> reconcile trigger")
        } catch (t: Throwable) {
            Log.w(TAG, "hooking onScrolled failed: " + t.message)
        }
    }
}