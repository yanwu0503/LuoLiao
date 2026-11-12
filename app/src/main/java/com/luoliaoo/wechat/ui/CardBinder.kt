package com.luoliaoo.wechat.ui

import com.luoliaoo.wechat.core.LegacyHook

import android.view.View
import android.view.ViewGroup
import com.luoliaoo.wechat.core.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs recycling cleanup on the CHAT adapter.
 *
 * Why this exists: ChatListHook's adapter discovery relied on RecyclerView.setAdapter firing
 * after our module loaded, which never happened for the chat page ("chat adapter discovered"
 * was 0 in the logs). Without cleanup, a card stayed inside a row after WeChat recycled it,
 * so it appeared in unrelated conversations.
 *
 * Here we deliberately take the adapter from the RecyclerView we ALREADY hold at analysis
 * time, so no discovery or timing assumption is involved. The adapter's onBindViewHolder and
 * onViewRecycled are hooked to strip cards whose owner does not match the row's message.
 */
class CardBinder(
    
    private val loader: ClassLoader,
) {

    private companion object {
        const val TAG = "LuoLiao"
    }

    private val hooked = ConcurrentHashMap.newKeySet<String>()

    /** How many binds we have seen; logged periodically so the hook can be proven to fire. */
    private val binds = java.util.concurrent.atomic.AtomicLong()

    /**
     * item 4: called for every bound row's message, so the live-history recorder sees real messages even
     * when the chat-adapter hook cannot find the adapter's message list.
     */
    var liveMessageSink: ((Any) -> Unit)? = null

    /** The adapter we hooked, used to resolve a row's message from its bind position. */
    @Volatile
    private var adapterRef: Any? = null

    /**
     * The message the adapter holds at `position`.
     *
     * This is the authoritative answer to "what is this row showing", which is what the stale-card
     * cleanup needs. Reading it from a ViewHolder field failed often enough that the cleanup became a
     * no-op, and cards survived into recycled rows.
     */
    private fun msgAtPosition(position: Int): Any? {
        if (position < 0) return null
        val adapter = adapterRef ?: return null
        var cls: Class<*>? = adapter.javaClass
        var d = 0
        while (cls != null && d < 6) {
            for (m in cls.declaredMethods) {
                if (m.name != "getItem" || m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                return try {
                    m.isAccessible = true
                    m.invoke(adapter, position)
                } catch (_: Throwable) {
                    null
                }
            }
            cls = cls.superclass
            d++
        }
        return null
    }

    /** Render callback supplied by the controller: (itemView, message) -> Unit. */
    var onRowBound: ((ViewGroup, Any?) -> Unit)? = null

    /**
     * Called when a row is recycled, so the controller can drop a card that no longer belongs to it.
     *
     * Kept separate from onRowBound because the controller must apply a stricter rule here: it may
     * only remove a card when the row's contents prove a different owner.
     */
    var onRowRecycled: ((ViewGroup) -> Unit)? = null

    /**
     * Hooks the adapter currently attached to `rv`.
     * Safe to call repeatedly; each adapter class is hooked once.
     */
    fun ensureHooked(rv: Any?) {
        if (rv == null) return
        val adapter = try {
            rv.javaClass.methods.firstOrNull {
                it.name == "getAdapter" && it.parameterCount == 0
            }?.invoke(rv)
        } catch (t: Throwable) {
            Log.w(TAG, "getAdapter failed: " + t.message)
            null
        } ?: return

        val cls = adapter.javaClass
        if (!hooked.add(cls.name)) return

        // Store the adapter so a bind can resolve its message BY POSITION (authoritative).
        adapterRef = adapter

        // onBindViewHolder(holder, position) is preferred: the position lets us ask the adapter what
        // message this row is being bound to, instead of digging a field out of the holder (which
        // failed and made the stale-card cleanup a no-op).
        val bind2 = findInHierarchy(cls, "onBindViewHolder", 2)
        val bind3 = if (bind2 == null) findInHierarchy(cls, "onBindViewHolder", 3) else null
        val bind = bind2 ?: bind3
        if (bind != null) {
            try {
                LegacyHook.hook(bind)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val n = binds.incrementAndGet()
                            val holder = chain.getArg(0)
                            val itemView = viewOf(holder)
                            if (n <= 3 || n % 50 == 0L) {
                                Log.i(TAG, "bind #" + n + " itemView=" + (itemView != null))
                            }
                            if (itemView is ViewGroup) {
                                // Authoritative message: adapter.getItem(position).
                                val msg = if (bind2 != null) {
                                    val pos = (chain.getArg(1) as? Int) ?: -1
                                    msgAtPosition(pos) ?: msgOf(holder)
                                } else {
                                    msgOf(holder)
                                }
                                // item 4: also feed the LIVE HISTORY recorder from here.
                                //
                                // This path resolves the message with adapter.getItem(position) and needs
                                // no knowledge of the adapter's internals, so it works even when
                                // ChatListHook cannot identify the message list field -- which is exactly
                                // the case that left live history with nothing to write.
                                if (msg != null) {
                                    try {
                                        liveMessageSink?.invoke(msg)
                                    } catch (_: Throwable) {
                                        // the sink is best effort
                                    }
                                }
                                onRowBound?.invoke(itemView, msg)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "onBind cleanup failed: " + t.message)
                        }
                        result
                    }
                Log.i(TAG, "card binder: hooked " + cls.simpleName + ".onBindViewHolder(" +
                    (if (bind2 != null) "holder,position)" else "holder,*)") +
                    " -- message resolved by position=" + (bind2 != null))
            } catch (t: Throwable) {
                Log.w(TAG, "card binder: hooking bind failed: " + t.message)
            }
        } else {
            Log.w(TAG, "card binder: " + cls.simpleName + " has no onBindViewHolder")
        }

        // ---------------------------------------------------------------- recycle cleanup
        //
        // A row recycled WITH a card still inside it is carried to another message, which is how a
        // card ends up displayed under a message that is not its own. Binding-time cleanup alone is
        // not enough: when a row is reused, its card must go.
        //
        // The v1.0.20 version deleted unconditionally and lost cards (2254 removals). This one removes
        // a card ONLY when the row's contents prove a different owner is showing; a card whose owner
        // matches, or whose owner cannot be established, is left untouched -- so scrolling can never
        // destroy a card.
        val recycle = findInHierarchy(cls, "onViewRecycled", 1)
        if (recycle != null) {
            try {
                LegacyHook.hook(recycle)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val holder = chain.getArg(0)
                            val itemView = viewOf(holder)
                            if (itemView is ViewGroup) {
                                onRowRecycled?.invoke(itemView)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "recycle cleanup failed: " + t.message)
                        }
                        result
                    }
                Log.i(TAG, "card binder: hooked " + cls.simpleName + ".onViewRecycled")
            } catch (t: Throwable) {
                Log.w(TAG, "card binder: hooking recycle failed: " + t.message)
            }
        } else {
            Log.i(TAG, "card binder: " + cls.simpleName + " has no onViewRecycled")
        }
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
        var p: Class<*>? = holder.javaClass
        var d = 0
        while (p != null && d < 6) {
            for (f in p.declaredFields) {
                if (f.name == "itemView" && View::class.java.isAssignableFrom(f.type)) {
                    return try {
                        f.isAccessible = true
                        f.get(holder) as? View
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

    /** The message object held by a ViewHolder, if any. */
    private fun msgOf(holder: Any?): Any? {
        if (holder == null) return null
        var p: Class<*>? = holder.javaClass
        var d = 0
        while (p != null && d < 6) {
            for (f in p.declaredFields) {
                val t = f.type
                val isMsg = try {
                    t.methods.any { it.name == "getMsgId" && it.parameterCount == 0 }
                } catch (_: Throwable) {
                    false
                }
                if (!isMsg) continue
                val o = try {
                    f.isAccessible = true
                    f.get(holder)
                } catch (_: Throwable) {
                    null
                }
                if (o != null) return o
            }
            p = p.superclass
            d++
        }
        return null
    }
}
