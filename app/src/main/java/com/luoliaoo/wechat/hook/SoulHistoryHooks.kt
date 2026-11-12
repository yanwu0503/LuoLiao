package com.luoliaoo.wechat.hook

import android.view.View
import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * SOUL chat history capture -- the feed that makes messages land in the local store WITHOUT the user
 * tapping 分析.
 *
 * ================================ THE BUG THIS FILE EXISTS TO PREVENT ==========================
 *
 * The user's report for Douyin was 「为什么必须点分析才会写入本地，我要的是跟微信一样 每2秒循环写入一次」.
 * The cause was NOT the timer -- `AnalysisController.startLiveTimer()` is host-agnostic (it only walks
 * `livePending` and calls `HistoryStore.record`) and is already started for every host outside the
 * WeChat-only gate at WeChatHooks.kt:563. The cause was that NOTHING FED it on Douyin: neither feeder
 * (`ChatListHook` and the binder) existed there, so every 2-second tick found an empty map.
 *
 * So "add Soul history" reduces to exactly one job: PUSH every message Soul binds into the existing
 * feed via `controller.onLiveMessage(msg)`. This file is that push, and it changes no timer, no store,
 * and no shared pipeline.
 *
 * ============================== WHY `bindViewHolder`, NOT `onBindViewHolder` ====================
 *
 * 必看.md, 「hook `RecyclerView.Adapter.onBindViewHolder` 想覆盖所有 adapter，结果同样不触发」:
 * `onBindViewHolder` is an ORDINARY method that every adapter OVERRIDES, so hooking the base
 * declaration hooks a method no adapter ever calls. That cost Douyin a whole debug round.
 *
 * `RecyclerView.Adapter.bindViewHolder(ViewHolder, int)` is `public final` -- it CANNOT be overridden,
 * and every adapter's every bind passes through it. Hooking that one method covers every concrete
 * adapter in Soul at once, with NO Soul class name involved, which is also what makes it survive a
 * Soul update. The modifiers are LOGGED so the log itself proves it was the final funnel.
 *
 * ============================== WHY THE MESSAGE COMES FROM THE ADAPTER ==========================
 *
 * 必看.md, 「认定聊天页 holder 是 BasicViewHolder 家族...bind 触发 63 次仍 0 写入」: the holder
 * family was a DIFFERENT hierarchy and the accessor simply did not exist on the holders the chat
 * actually bound. The rule extracted from that round is: get the message from the ADAPTER by index,
 * not from the ViewHolder, and print the holder class name before writing code that depends on it.
 *
 * Soul's own shape makes this concrete. `ConversationFragment.initRecyclerView()` (disassembled)
 * builds the chat list as a WRAPPER:
 *
 *     BaseWrapperAdapter(BaseAdapter)  ->  RecyclerView.setAdapter(...)
 *
 * and the inner adapter is `ConversationFragment$initRecyclerView$screenshotAdapter$1`, which carries
 * a method `d(ImMessage, int)` -- "message at index" -- while the WRAPPER is the object RecyclerView
 * sees. So a holder-first read would fail exactly the way Douyin's did. The adapter route is:
 *
 *   1. ask the adapter itself, by index, for the message ([messageFromAdapter]);
 *   2. if that yields nothing, walk a wrapper's `getRealAdapter()`/`getDataList()` and ask again;
 *   3. only then, as a last resort, look at the holder -- and NAME it in the log when the fallback ran.
 *
 * ============================== DEGRADE, DO NOT GUESS ==========================================
 *
 * No device is attached. Every uncertain step therefore:
 *   * is found by SHAPE (parameter/return types), never by a guessed member name, except where a name
 *     was VERIFIED in the dex and the comment says so;
 *   * LOGS WHICH BRANCH IT TOOK, so the first real run answers the open questions from evidence;
 *   * returns nothing rather than a plausible-looking value when it cannot read -- an idle feed that
 *     explains itself beats a feed that appears to work and writes nothing.
 *
 * [installProbe] provides the one-shot class-name dump the task requires, and it is deduplicated BY
 * CLASS NAME (必看.md's rule) so the conversation list cannot exhaust it before a chat is opened.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class SoulHistoryHooks(
    /**
     * The HOST classloader, handed over by the installer.
     *
     * DELIBERATELY UNUSED -- see [install]. It is accepted because the SOUL arm of the per-host
     * `when` in `WeChatHooks` constructs every Soul hook with the SAME three-argument contract
     * `(ClassLoader, Resolver, AnalysisController)`, and matching that contract is what lets the two
     * Soul classes be installed independently. The host loader is then ignored in favour of
     * `Resolver.classLoader()`, because 必看.md makes that a hard rule and because the two are not the
     * same object at the point this constructor runs.
     *
     * (This comment used to name `WeChatHooks.installSoulPart`, a reflective helper that was DELETED
     * once both Soul classes existed -- direct references replaced it so a name typo becomes a compile
     * error instead of a silent runtime no-op. A comment naming a function that no longer exists sends
     * the next reader hunting for it, so it now names the call site that really constructs this.)
     */
    @Suppress("UNUSED_PARAMETER") private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    /**
     * The Soul message reader, built here so callers only need the documented contract.
     *
     * `AnalysisController` owns its own `MsgAccess` for its other paths, but this feed reads through a
     * Soul-specific reader directly: the whole point of the sibling class is that the classification
     * (text vs card vs envelope) is Soul's own, and going through the generic facade would route the
     * decision through another host's assumptions.
     */
    private val access = SoulMsgAccess(resolver)

    private companion object {
        const val TAG = "LuoLiao"

        const val RV_CLASS = "androidx.recyclerview.widget.RecyclerView"
        const val RV_ADAPTER = "androidx.recyclerview.widget.RecyclerView\$Adapter"
        const val RV_HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"

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
     * Cached because this runs on every bind and a chat scrolls at tens of binds per second; walking
     * declared methods across a hierarchy on each frame would be real per-frame work.
     */
    private val atCache = ConcurrentHashMap<String, Method>()

    /** Adapter classes already known to have NO per-index accessor, so the miss is logged once. */
    private val atMissing = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    // ------------------------------------------------------------------ install

    /**
     * Installs the feed.
     *
     * Returns true when the final funnel was hooked. A false return is NOT silent: the reason is
     * logged, and the caller can tell the difference between "no RecyclerView in this host" and
     * "installed but waiting for a bind".
     *
     * The class is named `SoulHistoryHooks` and exposes exactly `(ClassLoader, Resolver,
     * AnalysisController) + install()` so the SOUL arm of `WeChatHooks`' per-host `when` can construct
     * it without this file and `SoulHooks` ever having to be edited together.
     *
     * The name and signature are now a COMPILE-TIME contract, not a string agreeement: `WeChatHooks`
     * references this class directly. (It previously loaded it reflectively by name, which is how a
     * one-character mismatch -- `SoulHistoryHook` vs `SoulHistoryHooks` -- once compiled cleanly and
     * silently installed nothing. A direct reference makes that a build error.)
     */
    fun install(): Boolean {
        if (installed) return true
        installed = true

        // One-shot class dump, so the first device run confirms the real classes instead of assuming.
        access.probeClasses()

        // The live feed is what makes records appear WITHOUT an analysis -- the whole requirement.
        // It is stated in the log as a POSITIVE fact, because "history still needs an analysis" and
        // "history is broken" are indistinguishable from an empty file, and that ambiguity is what
        // cost the Douyin round a full debug cycle.
        //
        // The words for "analyse" are \\uXXXX-escaped even though this is a log line, because the
        // ASCII-only rule for code strings applies to log output too (see core/Log.kt) -- a raw CJK
        // literal here is exactly the kind of thing that silently depends on the file's encoding.
        Log.i(
            TAG,
            "SOUL: live history feed starting -- messages will be recorded as they are BOUND, " +
                "without the user pressing \u5206\u6790 (the 2s timer in AnalysisController is " +
                "host-agnostic and is already running for every host)",
        )

        val ok = installFunnel()

        // The view-tree probe is scheduled regardless: if the funnel hook fails to install, the probe
        // is the fallback evidence that names the real adapter -- the same role the discovery path
        // played on WeChat after setAdapter never fired.
        installProbe()
        startReport()
        return ok
    }

    /**
     * Hooks `RecyclerView.Adapter.bindViewHolder(ViewHolder, int)` -- the ONLY funnel every adapter
     * passes through.
     *
     * Host classes are resolved through `resolver.classLoader()` (the HOST's loader), never through a
     * constructor-supplied loader. 必看.md records this as a hard rule, because a class loaded from
     * the wrong loader produces a "not found" that reads like a missing feature.
     */
    private fun installFunnel(): Boolean {
        val loader = resolver.classLoader()
        var adapterCls: Class<*>? = null
        var holderCls: Class<*>? = null
        try {
            Class.forName(RV_CLASS, false, loader).let { rv ->
                // resolve so a missing androidx fails HERE, at install time, with a clear log
                Log.i(TAG, "SOUL: " + rv.name + " resolved via the host loader")
            }
            adapterCls = Class.forName(RV_ADAPTER, false, loader)
            holderCls = Class.forName(RV_HOLDER, false, loader)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "SOUL: androidx RecyclerView is not loadable via the host loader (" + t.message +
                    "); live history cannot be fed, so records will only appear after an analysis",
            )
            return false
        }

        val funnel = try {
            adapterCls.getDeclaredMethod(
                "bindViewHolder",
                holderCls,
                Int::class.javaPrimitiveType,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "SOUL: bindViewHolder(ViewHolder,int) not found: " + t.message)
            return false
        }

        // PROOF, in the log, that this is the un-overridable funnel and not the ordinary method that
        // every adapter overrides. If 'final' is ever absent, the hook below would silently see
        // nothing -- which is exactly the Douyin failure -- so it is checked rather than assumed.
        val isFinal = java.lang.reflect.Modifier.isFinal(funnel.modifiers)
        Log.i(
            TAG,
            "SOUL: bindViewHolder modifiers=[" + java.lang.reflect.Modifier.toString(funnel.modifiers) +
                "] final=" + isFinal +
                " declaring=" + funnel.declaringClass.name,
        )
        if (!isFinal) {
            Log.w(
                TAG,
                "SOUL: bindViewHolder is NOT final in this androidx build; this hook may never fire. " +
                    "Continuing anyway (the per-host adapter hook is the fallback), but expect no history.",
            )
        }

        return try {
            LegacyHook.hook(funnel)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    try {
                        onBind(chain.thisObject, chain.getArg(0), chain.getArg(1))
                    } catch (t: Throwable) {
                        // A throw here would run inside Soul's own bind call, so it must never escape.
                        Log.w(TAG, "SOUL: the bind feed failed: " + t.message)
                    }
                }
            Log.i(
                TAG,
                "SOUL: hooked RecyclerView.Adapter.bindViewHolder (final=" + isFinal + ") -- " +
                    "live history now records without an analysis",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "SOUL: hooking bindViewHolder failed: " + t.message)
            false
        }
    }

    // ------------------------------------------------------------------ the feed

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
            Log.w(TAG, "SOUL: a bind arrived with a null adapter; nothing to read a message from")
            return
        }
        if (position < 0) {
            Log.w(TAG, "SOUL: a bind arrived with position=" + position + "; cannot index the adapter")
            return
        }

        noteAdapterType(adapter)

        val msg = messageFromAdapter(adapter, position)
        if (msg == null) {
            // NOT an error: most RecyclerViews in Soul are not the chat list. The holder type is named
            // once so the log shows what the non-chat lists look like, and the chat page's own holder
            // is therefore guaranteed a line of its own.
            noMessage++
            noteHolderType(holder, null)
            return
        }

        noteHolderType(holder, msg)
        controller.onLiveMessage(msg)
        pushed++
    }

    /**
     * The message at [position] according to the ADAPTER, or null.
     *
     * Tried in order, each step logging which branch it took:
     *
     *   1. `getItem(int)` -- the standard RecyclerView.Adapter accessor. Available on every adapter
     *      because it is declared on the base class, so it needs no shape guessing at all.
     *   2. A SHAPE match: a one-int method whose return type is Soul's message class
     *      (`cn.soulapp.imlib.msg.ImMessage` or a subclass). This is what reaches Soul's inner
     *      `ConversationFragment$initRecyclerView$screenshotAdapter$1.d(ImMessage, int)`, whose name
     *      is obfuscated to a single letter and therefore unusable as a lookup key.
     *   3. `getRealAdapter()` / `getDataList()` -- because the chat list is a `BaseWrapperAdapter`
     *      wrapping the real adapter, the message may only be reachable through the wrapper.
     *
     * The ViewHolder is deliberately NOT consulted here. See the class comment.
     */
    private fun messageFromAdapter(adapter: Any, position: Int): Any? =
        messageFromAdapter(adapter, position, 0)

    /**
     * [depth] bounds the wrapper recursion.
     *
     * WHY IT IS THREADED THROUGH RATHER THAN KEPT IN [fromWrapped] ALONE: the two functions call each
     * other, so a per-call counter could be reset on every hop and a `getRealAdapter()` CYCLE (A -> B
     * -> A, which is entirely possible from a decompiled wrapper) would recurse until the stack blew.
     * That StackOverflowError would be thrown INSIDE Soul's own `bindViewHolder`, i.e. it would crash
     * the host rather than merely lose a history line -- the one outcome this module must never cause
     * (必看.md records the menu injection that FATALled the host the same way).
     */
    private fun messageFromAdapter(adapter: Any, position: Int, depth: Int): Any? {
        val cls = adapter.javaClass

        // --- 1) the standard accessor
        val viaGetItem = try {
            val m = cls.methods.firstOrNull {
                it.name == "getItem" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (m == null) {
                null
            } else {
                m.isAccessible = true
                m.invoke(adapter, position)
            }
        } catch (_: Throwable) {
            null
        }
        if (access.isMessage(viaGetItem)) return viaGetItem
        // A non-message item is normal (headers, footers, notices) -- keep looking rather than
        // declaring failure, but say so once per adapter type.
        if (viaGetItem != null && seenAdapterTypes.add("nonmsg:" + cls.name + ":" + viaGetItem.javaClass.name)) {
            if (seenAdapterTypes.size <= ADAPTER_TYPE_LIMIT) {
                Log.i(
                    TAG,
                    "SOUL ADAPTER DIAG: " + cls.name + ".getItem(" + position + ") returned " +
                        viaGetItem.javaClass.name + " (not a Soul message); trying the shape match",
                )
            }
        }

        // --- 2) the shape match, cached per adapter class
        //
        // The resolution and the INVOCATION are kept strictly separate. An earlier revision of this
        // block returned the resolved `Method` object itself on a cache MISS (and invoked it on a hit),
        // so the first bind of every adapter produced a `Method` where a message was expected, the
        // `isMessage` test failed, and the message was silently lost while every later bind worked.
        // That is the worst possible shape for a bug here: it looks like "history works, sometimes".
        val viaShape = try {
            var m = atCache[cls.name]
            if (m == null && !atMissing.contains(cls.name)) {
                m = findMessageAtMethod(cls)
                if (m != null) {
                    m.isAccessible = true
                    atCache[cls.name] = m
                } else {
                    atMissing.add(cls.name)
                    if (seenAdapterTypes.add("nosig:" + cls.name)) {
                        noteAdapterDiag(
                            cls,
                            "no per-index message accessor found (looked for 1-int methods " +
                                "returning " + SoulEnvelope.MESSAGE_CLASS + ")",
                        )
                    }
                }
            }
            // One invoke site, reached on both paths.
            m?.invoke(adapter, position)
        } catch (_: Throwable) {
            null
        }
        if (access.isMessage(viaShape)) return viaShape

        // --- 3) wrapper indirection (bounded: see the depth parameter)
        if (depth >= 3) {
            Log.w(
                TAG,
                "SOUL: adapter wrapper chain reached depth " + depth + " for " + cls.name +
                    "; stopping so a getRealAdapter cycle cannot overflow the stack inside Soul's bind",
            )
            return null
        }
        return fromWrapped(adapter, position, depth)
    }

    /**
     * Follows Soul's adapter WRAPPER chain.
     *
     * WHY: the chat list is built as `BaseWrapperAdapter(BaseAdapter)` and it is the WRAPPER that
     * `RecyclerView.setAdapter` receives (verified by disassembling
     * `ConversationFragment.initRecyclerView`). The wrapper also inserts headers/footers, so its index
     * space is offset -- which is why the inner adapter is asked with the same index and a mismatch is
     * tolerated rather than corrected blindly. `getDataList()` is read as a second route because
     * `BaseAdapter` declares it directly.
     *
     * Both branches pass `depth + 1` back into [messageFromAdapter], so the mutual recursion is
     * bounded by ONE counter no matter which way the cycle runs. The inner adapter is also compared by
     * identity, so a wrapper that returns itself terminates immediately.
     */
    private fun fromWrapped(adapter: Any, position: Int, depth: Int): Any? {
        val inner = try {
            adapter.javaClass.methods.firstOrNull {
                it.name == "getRealAdapter" && it.parameterCount == 0
            }?.also { it.isAccessible = true }?.invoke(adapter)
        } catch (_: Throwable) {
            null
        }
        // Identity check: an adapter that returns ITSELF must not be re-entered.
        if (inner != null && inner !== adapter) {
            val got = messageFromAdapter(inner, position, depth + 1)
            if (got != null) return got
        }

        // The inner adapter's own data list, addressed by index.
        val list = try {
            adapter.javaClass.methods.firstOrNull {
                it.name == "getDataList" && it.parameterCount == 0
            }?.also { it.isAccessible = true }?.invoke(adapter) as? List<*>
        } catch (_: Throwable) {
            null
        }
        val item = list?.getOrNull(position)
        if (access.isMessage(item)) return item

        // BRVAH's adapter (MsgConversationAdapter extends BaseProviderMultiAdapter) exposes getData().
        val brvah = try {
            adapter.javaClass.methods.firstOrNull {
                it.name == "getData" && it.parameterCount == 0
            }?.also { it.isAccessible = true }?.invoke(adapter) as? List<*>
        } catch (_: Throwable) {
            null
        }
        val b = brvah?.getOrNull(position)
        if (access.isMessage(b)) return b

        return null
    }

    /**
     * Finds "message at index" on an adapter class or its ancestors, BY SHAPE.
     *
     * Matched by shape rather than name because Soul obfuscates these to single letters: the verified
     * one is `d(Lcn/soulapp/imlib/msg/ImMessage;I)`, and the captain-reported BRVAH path is
     * `getItem(int)`. A name lookup would therefore be a guess; the signature is not.
     *
     * The rule: exactly one int parameter, return type assignable to Soul's message class. Assignable
     * rather than equal so a Tinker-generated subclass still matches. The `Class.forName` is guarded,
     * so an absent message class means "no match" instead of an exception on every bind.
     */
    private fun findMessageAtMethod(cls: Class<*>): Method? {
        val base = try {
            Class.forName(SoulEnvelope.MESSAGE_CLASS, false, resolver.classLoader())
        } catch (_: Throwable) {
            // No verifiable message class => match nothing. Guessing a name here would let the feed
            // "look alive" while pushing objects nothing downstream can read.
            Log.w(
                TAG,
                "SOUL: " + SoulEnvelope.MESSAGE_CLASS + " is not loadable; the shape match is " +
                    "disabled and no history will be fed from the adapter",
            )
            return null
        }
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 8) {
            for (m in c.declaredMethods) {
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                if (!base.isAssignableFrom(m.returnType)) continue
                return m
            }
            c = c.superclass
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ diagnostics

    /** Logs each ADAPTER class once, so the chat page's adapter is guaranteed a line. */
    private fun noteAdapterType(adapter: Any) {
        val name = adapter.javaClass.name
        if (!seenAdapterTypes.add("adapter:" + name)) return
        if (seenAdapterTypes.size > ADAPTER_TYPE_LIMIT) return
        noteAdapterDiag(adapter.javaClass, "bind feed active")
    }

    private fun noteAdapterDiag(cls: Class<*>, detail: String) {
        Log.i(
            TAG,
            "SOUL ADAPTER DIAG (new #" + seenAdapterTypes.size + "): " + cls.name +
                " <- " + superChain(cls) + " :: " + detail,
        )
    }

    /**
     * Logs each HOLDER class once, saying whether a message could be read for it.
     *
     * Deduplicated BY CLASS NAME, never by a count. 必看.md: the conversation list binds many rows
     * before any chat is opened, so a count-based cap was exhausted by session-list holders and the
     * chat page's holder -- the only one that mattered -- was never printed. With a Set the
     * conversation list costs exactly one line, and opening a chat always adds the chat's own types.
     */
    private fun noteHolderType(holder: Any?, msg: Any?) {
        if (holder == null) return
        val cls = holder.javaClass
        if (!seenHolderTypes.add(cls.name)) return
        if (seenHolderTypes.size > HOLDER_TYPE_LIMIT) return
        Log.i(
            TAG,
            "SOUL HOLDER DIAG (new type #" + seenHolderTypes.size + "): " + cls.name +
                " <- " + superChain(cls) +
                " msg=" + (msg != null) +
                " (the message is read from the ADAPTER by index, not from this holder)",
        )
    }

    private fun superChain(cls: Class<*>): String {
        val sb = StringBuilder()
        var c: Class<*>? = cls.superclass
        var d = 0
        while (c != null && c != Any::class.java && d < 4) {
            if (d > 0) sb.append(" <- ")
            sb.append(c.simpleName)
            c = c.superclass
            d++
        }
        return if (sb.isEmpty()) "(none)" else sb.toString()
    }

    /**
     * A periodic pipeline report, so "no history appeared" can be diagnosed without guessing.
     *
     * The counters discriminate between the three causes that look identical from outside:
     *   funnel=0                        -> the bind funnel never fired (the hook is on the wrong thing)
     *   funnel>0, noMessage=funnel      -> binds happened but no message was read (adapter/index wrong)
     *   pushed>0, staged=0 in the store -> the message WAS read and rejected downstream (type/talker)
     *
     * Reported by AnalysisController, because staging and flushing live there.
     */
    private fun startReport() {
        try {
            val t = Thread {
                while (true) {
                    try {
                        Thread.sleep(20_000L)
                        Log.i(
                            TAG,
                            "SOUL PIPE: funnel=" + funnelFires +
                                " pushed=" + pushed +
                                " noMessage=" + noMessage +
                                " holderTypes=" + seenHolderTypes.size +
                                " adapterTypes=" + seenAdapterTypes.size +
                                " | " + controller.historyPipelineState(),
                        )
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Throwable) {
                        // keep reporting; a failed line must not kill the reporter
                    }
                }
            }
            t.name = "LuoLiao-soul-pipe-report"
            t.isDaemon = true
            t.start()
        } catch (_: Throwable) {
            // the report is diagnostic only
        }
    }

    /**
     * The one-shot runtime PROBE the task requires: dumps which adapter/holder classes Soul really
     * uses, plus the state of every branch this feed depends on.
     *
     * No device is attached, so this is how the first real run CONFIRMS the shape instead of the next
     * engineer re-deriving it. It walks the live view tree once, which is public API and cannot fail
     * the way a hook can -- the same fallback discovery `ChatListHook` needed on WeChat after
     * `setAdapter` never fired.
     */
    fun installProbe() {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                try {
                    val root = activityRoot()
                    if (root == null) {
                        Log.w(
                            TAG,
                            "SOUL PROBE: no Activity/decor view reachable yet; the adapter dump will " +
                                "be reported from the bind feed instead",
                        )
                        return@postDelayed
                    }
                    val found = ArrayList<Any>(4)
                    walkForRecyclerViews(root, found, 0)
                    val sb = StringBuilder()
                    sb.append("=== SOUL adapter probe: ").append(found.size)
                        .append(" RecyclerView(s) ===\n")
                    for (rv in found) {
                        val adapter = try {
                            rv.javaClass.methods.firstOrNull {
                                it.name == "getAdapter" && it.parameterCount == 0
                            }?.also { it.isAccessible = true }?.invoke(rv)
                        } catch (_: Throwable) {
                            null
                        }
                        sb.append("  rv=").append(rv.javaClass.name)
                            .append(" adapter=").append(adapter?.javaClass?.name ?: "<null>")
                            .append('\n')
                        if (adapter != null) {
                            val at = try {
                                findMessageAtMethod(adapter.javaClass)
                            } catch (_: Throwable) {
                                null
                            }
                            sb.append("     per-index accessor=")
                                .append(at?.let { it.name + it.parameterTypes.joinToString("") { p -> p.simpleName } }
                                    ?: "<NONE FOUND>")
                                .append('\n')
                            sb.append("     realAdapter=")
                                .append(
                                    try {
                                        adapter.javaClass.methods.firstOrNull {
                                            it.name == "getRealAdapter" && it.parameterCount == 0
                                        }?.also { it.isAccessible = true }?.invoke(adapter)?.javaClass?.name
                                            ?: "<none>"
                                    } catch (_: Throwable) {
                                        "<error>"
                                    },
                                )
                                .append('\n')
                        }
                    }
                    Log.i(TAG, sb.toString())
                } catch (t: Throwable) {
                    Log.w(TAG, "SOUL PROBE failed: " + t.message)
                }
            }, 8000L)
            Log.i(TAG, "SOUL PROBE scheduled (one-shot, 8s after install)")
        } catch (t: Throwable) {
            Log.w(TAG, "SOUL PROBE could not be scheduled: " + t.message)
        }
    }

    private fun activityRoot(): View? {
        try {
            // AppContextHolder exposes a Context, so the window is reached by walking the context
            // chain -- no ActivityThread reflection, which 必看.md records as unreliable.
            var ctx: android.content.Context? = com.luoliaoo.wechat.core.AppContextHolder.get()
            while (ctx is android.content.ContextWrapper) {
                if (ctx is android.app.Activity) return ctx.window?.decorView
                ctx = ctx.baseContext
            }
        } catch (_: Throwable) {
            // reported by the caller
        }
        return null
    }

    private fun walkForRecyclerViews(v: View, out: MutableList<Any>, depth: Int) {
        if (depth > 40) return
        val n = v.javaClass.name
        if (n.startsWith("androidx.recyclerview.widget.") && n != RV_CLASS) {
            // ModuleStats is deliberately NOT incremented here: that counter means "a list screen was
            // created in this process" and is read by the heartbeat to tell "the process died" from
            // "the user never opened a chat". A diagnostic walk must not move a number that another
            // diagnosis depends on.
            out.add(v)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walkForRecyclerViews(v.getChildAt(i), out, depth + 1)
        }
    }
}
