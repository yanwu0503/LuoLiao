package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.LegacyHook
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ chat history capture -- the feed that makes QQ messages land in the local store WITHOUT the user
 * tapping 分析.
 *
 * ================================ THE BUG THIS FILE EXISTS TO PREVENT ==========================
 *
 * The user's report (the same one Soul's header quotes) was 「为什么必须点分析才会写入本地，我要的是
 * 跟微信一样 每2秒循环写入一次」. That has TWO halves and BOTH must hold:
 *
 *   * the 2-second writer -- ALREADY EXISTS and is host-agnostic:
 *     `AnalysisController.startLiveTimer()` schedules a fixed-delay task at **2000 ms**
 *     (`AnalysisController.kt:2220` function; `:2228` the `scheduleWithFixedDelay` call; `:2237-2238`
 *     the two `2000L` initial-delay/period arguments; `:2241` the
 *     "live history timer started (every 2s)" log), and it is started OUTSIDE the WeChat-only gate
 *     at `WeChatHooks.kt:541`, so the QQ process gets it too. **Device-confirmed**: the QQ log shows
 *     that exact line and the flush that followed it.
 *   * the FEEDER -- nothing pushed QQ messages into it, so every 2-second tick found an empty
 *     `livePending` map and returned without writing. THIS FILE IS THAT FEEDER.
 *
 * It is a FIXED-DELAY (not fixed-rate) schedule: the next tick starts 2 s after the previous one
 * FINISHES, so a slow flush cannot stack ticks. The user asked for "every 2 seconds" and gets
 * exactly that, with no tick pile-up under I/O load.
 *
 * So this file adds no timer, no store, and no new format. It only calls
 * [AnalysisController.onLiveMessage], the single documented entry point of the existing pipeline
 * (exactly what `SoulHistoryHooks.kt:324`, `DouyinHooks.kt:498/575` and `KuaishouHooks.kt:844` do).
 *
 * ============================== WHY THE HOOK TARGET IS *NOT* `ye.a#y` ==========================
 *
 * T19's report named `ye.a#y(holder/a; ILcom/tencent/aio/data/msglist/a; List;)V` as the prime hook
 * point. Reading the dex flags proves that CANNOT WORK, and this file records the correction:
 *
 *     Lye/a;   acc=0x601 = public|interface|abstract          <-- it is an INTERFACE
 *     ye.a#y   acc=0x401 = public|abstract                    <-- NO method body
 *
 * An abstract/interface method has no code, so a hook on it fires for NOBODY: the module would
 * install "successfully" and then receive zero binds with no error anywhere. That is the same
 * failure class as 必看.md's `RecyclerView.Adapter.onBindViewHolder` entry.
 *
 * The body lives in the single IMPLEMENTOR:
 *
 *     Lcom/tencent/aio/part/root/panel/content/firstLevel/msglist/mvx/vb/core/AbsMsgListVB;
 *         acc=0x401 (abstract class), implements ye.a
 *     AbsMsgListVB#y   acc=0x11 = public|FINAL   code_off=5062512   units=370
 *
 * `public final` is what makes this the right target: it CANNOT be overridden, so every concrete
 * message-list VB in the APK passes through this one body. And its 3rd parameter is ALREADY the
 * message object:
 *
 *     AIOMsgItem.super == Lcom/tencent/aio/data/msglist/a;   ==  y's 3rd parameter type
 *
 * ...so the item is handed over directly -- no adapter-position arithmetic, no ViewHolder field
 * lookup, no `getItem(int)` (which this adapter does not even declare).
 *
 * ============================== THE FALLBACK, AND WHY IT IS SECOND =============================
 *
 * `AbsMsgListVB#y` is an obfuscation-stable NAME only in the sense that it is `y` today; a QQ update
 * may rename it. So the feeder degrades in two steps, and says in the log which one it used:
 *
 *   1. SHAPE match on `AbsMsgListVB`: a declared method whose LAST parameter is the message base
 *      type, taking 4 parameters, returning void. Name-independent.
 *   2. `com.tencent.mobileqq.aio.msglist.AIOMsgListVM#o0(MsgRecord)V` -- the data-arrives-in-the-VM
 *      entry, which hands over the `MsgRecord` itself.
 *
 * Both feed the same pipeline, so a message seen twice is stored once (the store keys by
 * `(talker, msgId)`), which is why installing BOTH is safe rather than duplicating.
 *
 * ============================== WHY THE RECORD IS READ VIA `getMsgRecord()` ====================
 *
 * T19 found that `AIOMsgItem` holds TWO fields of type `MsgRecord`, and only one is the message:
 *
 *     [static  ] MsgRecord i1    <-- Kotlin default-argument placeholder, set to `new MsgRecord()`
 *                                    in `<clinit>` and never written again; `o()` is static and
 *                                    simply returns it, so it is ALWAYS an empty record
 *     [instance] MsgRecord e     <-- the real per-message record; `getMsgRecord()` reads this
 *
 * `QQMsgAccess.ITEM_MSG_GETTERS` originally listed `"getMsgRecord"` FIRST and `"o"` second. The
 * `"o"` entry was a SILENT-WRONG-RESULT hazard and has since been REMOVED (t14/engineer3):
 * `getMsgRecord()` is the only getter now, which is the correct reader.
 *
 * Why `"o"` was dangerous, kept because it is the reason the list must stay narrow: `o()` is
 * `public|static` and `Method.invoke(instance)` IGNORES the receiver, so it returned the shared
 * empty `i1` while every `isRecord` test still passed (`isAssignableFrom` accepts it). A fallback
 * list therefore cannot be "harmless extra candidates" -- each name must return THAT message, or it
 * silently poisons the reader. The extra hazard for THIS file: an empty record's `getMsgId()` is 0,
 * so every message would collapse onto one `(talker, 0)` key and the conversation would store a
 * single line. Details in `_tools/qq/T26_QQ_HISTORY_IMPL.md`.
 *
 * ============================== PERFORMANCE AND SAFETY =========================================
 *
 * The hook body executes INSIDE QQ's own bind call, on the UI thread, once per row. Therefore:
 *
 *   * constant work only: a type check and one reflective call. The pipeline does the I/O on its own
 *     single-threaded executor and batches writes at 2000 ms, so nothing here scales with message rate;
 *   * every step is wrapped, because a throw here would propagate into QQ's bind and could crash or
 *     stutter the host -- the one outcome this module must never cause;
 *   * logs are rate-limited BY SIGNATURE (`Set<String>`), never by a counter, per 必看.md: a counter
 *     lets a repeated cause consume the whole budget and hide a NEW one.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQHistoryHooks(
    /**
     * The HOST classloader, handed over by the installer.
     *
     * Accepted only to match the three-argument contract every other per-host hook uses. Host classes
     * are resolved through `resolver.classLoader()` instead, because 必看.md makes that a hard rule:
     * a class loaded from the wrong loader produces a "not found" that reads like a missing feature.
     */
    @Suppress("UNUSED_PARAMETER") private val loader: ClassLoader,
    private val resolver: Resolver,
    private val controller: AnalysisController,
) {

    /**
     * The QQ message reader.
     *
     * `QQMsgAccess` is a class, not an object, and t14 owns it -- this file CONSTRUCTS it rather than
     * adding to it, so the two tasks cannot collide. Its only Resolver dependency is
     * `resolver.classLoader()`.
     */
    private val access = QQMsgAccess(resolver)

    private companion object {
        const val TAG = "LuoLiao"

        /**
         * The message-list VB base class -- the class that really carries the bind body.
         * Verified DEFINED in classes3.dex, acc=0x401, implements `ye.a`.
         */
        const val ABS_MSG_LIST_VB =
            "com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.core.AbsMsgListVB"

        /** The message base type on 手机QQ -- see [resolveMsgBase] for why it is not used directly. */
        const val MSG_BASE_QQ = "com.tencent.aio.data.msglist.a"

        /**
         * The message base type on TIM. A DIFFERENT CLASS, measured from the shipped APK.
         *
         * WHY THIS CONSTANT EXISTS, and it is the whole TIM bug: the module used to hardcode
         * [MSG_BASE_QQ] and look for a bind method whose 3rd parameter is that type. TIM's message
         * hierarchy is the SAME SHAPE but a DIFFERENT NAME:
         *
         *     手机QQ:  AIOMsgItem  extends  com.tencent.aio.data.msglist.a   -> slot 'y'
         *     TIM:     AIOMsgItem  extends  com.tencent.aio.data.a.a        -> slot 'o'
         *
         * `com.tencent.aio.data.msglist.a` does not exist in TIM at ALL -- the whole `aio.data.msglist`
         * package is absent (TIM has only 10 classes under `com.tencent.aio.data.`, and `msglist` is not
         * one of them). So `Class.forName` failed, the primary feeder gave up, and the module silently
         * fell back to the VM feeder -- which only sees a message when one ARRIVES or is opened, not when
         * a row BINDS. That is exactly the reported symptom: 「只会写入长按分析过的那一条」.
         */
        const val MSG_BASE_TIM = "com.tencent.aio.data.a.a"

        /** Fallback: the VM entry that hands over the NT record itself. */
        const val MSG_LIST_VM = "com.tencent.mobileqq.aio.msglist.AIOMsgListVM"

        /**
         * The concrete message class, whose SUPERCLASS is the type every bind method takes.
         *
         * Present under this SAME name in 手机QQ and TIM (verified in both APKs), which is what makes it
         * the reliable anchor for [resolveMsgBase] -- the class NAME is stable across the two apps even
         * though the parent it points at is not.
         */
        const val AIO_MSG_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem"

        /** The verified fallback entry name. Preferred over the shape so the pick is deterministic. */
        const val VM_ENTRY_NAME = "o0"

        /** Cap on distinct signatures logged per bucket, so a busy list cannot bury a new cause. */
        const val LOG_SIGNATURE_LIMIT = 40
    }

    @Volatile
    private var installed = false

    /** How many times the hook body ran, so "the hook never fired" is distinguishable from "no messages". */
    @Volatile
    private var bindsSeen = 0

    /** How many binds yielded a message that was pushed into the pipeline. */
    @Volatile
    private var pushed = 0

    /** How many binds carried no message (headers, time separators, footers) -- normal, not an error. */
    @Volatile
    private var skipped = 0

    @Volatile
    private var hookTarget = "(none)"

    /**
     * Which distinct log signatures have already been emitted.
     *
     * Deduplicated by SIGNATURE (`Set<String>`), not by a counter: 必看.md records that a
     * count-based limiter lets one repeated cause consume the whole budget and suppress a NEW one,
     * which is precisely the information a bug report needs.
     */
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** Per-class resolution results, so the shape probe runs once per class rather than per bind. */
    private val methodCache = ConcurrentHashMap<String, Method>()

    /** Classes already proven to have no usable binder, so the miss is not re-probed every bind. */
    private val methodMissing = ConcurrentHashMap.newKeySet<String>()

    // ------------------------------------------------------------------ row -> message registry

    /**
     * Records that [msg] was bound to [holder].
     *
     * The HOLDER is what the toolbar can reach -- it is the row, and the ticked `CheckBox` lives inside
     * it. The key recorded is the holder itself when it is a `View`, so a lookup can be made from any
     * descendant by climbing parents.
     *
     * The storage lives in [QQRowRegistry] (a top-level object) rather than on this class: the only
     * companion object here is `private`, and the toolbar in `QQHooks` must be able to read the
     * registry back.
     */
    private fun rememberRow(holder: Any?, msg: Any?) {
        // NO "is it a View" CHECK, and that check was the whole bug: the bind argument is QQ's own
        // `holder/a`, not an Android View, so requiring a View made this a no-op and the registry
        // stayed empty forever (`row registry size=0`). Whatever the holder is, it is a stable object
        // that the view side can reach through `RecyclerView.findContainingViewHolder`.
        QQRowRegistry.put(holder, msg)
    }

    /**
     * Installs the feeder. Never throws: a failure is logged and reported as `false` so the caller
     * (`QQHooks`/`WeChatHooks`) can carry on with the rest of the QQ installation.
     */
    fun install(): Boolean {
        if (installed) return true
        installed = true

        Log.i(
            TAG,
            "QQ: live history feed starting -- messages are recorded as they are BOUND, without the " +
                "user pressing \u5206\u6790. The 2s writer already exists (AnalysisController." +
                "startLiveTimer, 2000ms, started host-agnostically at WeChatHooks.kt:541); this class " +
                "only supplies the missing FEEDER.",
        )

        // The primary target: the real bind body, resolved BY SHAPE so a rename of `y` cannot break it.
        val primary = installAbsMsgListVbHook()

        // The fallback is installed REGARDLESS of the primary's outcome: the two see different
        // moments (per-row bind vs record-arrives-in-VM), and a duplicate is impossible because the
        // store keys by (talker, msgId). Two independent feeders also mean a QQ rename of either one
        // still leaves history working -- which is what the user actually cares about.
        val fallback = installMsgListVmHook()

        Log.i(
            TAG,
            "QQ: live history feed install result -- primary(AbsMsgListVB#y)=" + primary +
                " fallback(AIOMsgListVM#o0)=" + fallback +
                " target=" + hookTarget,
        )
        if (!primary && !fallback) {
            Log.w(
                TAG,
                "QQ: BOTH history feeders failed to install, so QQ history will only appear after an " +
                    "analysis. The hooks are resolved by SHAPE, so this means the QQ 9.3.65 chat list " +
                    "is not reachable this way; report the log lines above.",
            )
        }
        return primary || fallback
    }

    // ------------------------------------------------------------------ hook 1: the real bind body

    /**
     * Hooks the message list's bind body -- `AbsMsgListVB#y(holder, int, msgBase, List)V`.
     *
     * Resolved by SHAPE rather than by the obfuscated name `y`: a declared method taking 4
     * parameters whose 3rd parameter is the message base type and whose return type is void. The
     * dex confirms exactly one such method (acc=0x11 public|final, 370 code units).
     */
    /**
     * The message base type, resolved from the HOST rather than from a hardcoded name.
     *
     * ============================== WHY NOT A CONSTANT ==============================
     * The module hardcoded `com.tencent.aio.data.msglist.a` (手机QQ's name). On TIM that class does not
     * exist -- the entire `aio.data.msglist` package is absent -- so the lookup failed, the primary
     * feeder refused to install, and QQ history silently degraded to "only the message you analysed".
     * The device log shows the exact line:
     *
     *     QQ: com.tencent.aio.data.msglist.a is not loadable
     *     QQ: live history feed install result primary(AbsMsgListVB#y)=false fallback(AIOMsgListVM#o0)=true
     *
     * ORDER OF RESOLUTION, most authoritative first:
     *
     *   1. ASK `AIOMsgItem` FOR ITS SUPERCLASS. This is not a guess and not a name table: the class the
     *      binder passes IS the class `AIOMsgItem` derives from, so `getSuperclass()` is the definition
     *      of the thing being searched for. It works on 手机QQ (`...msglist.a`) and on TIM
     *      (`...data.a.a`) without either name appearing in this file as a requirement, and it survives
     *      the next rename too. If the hierarchy ever gains an intermediate class the shape test below
     *      still accepts it, because it uses `isAssignableFrom` rather than equality.
     *
     *   2. The two known names, as a fallback for a build where `AIOMsgItem` itself is not loadable.
     *
     * WHY THIS DIRECTIOON (superclass) RATHER THAN ASKING THE BINDER: the binder's method list is what
     * we are trying to search, so using it to decide what to search FOR would be circular; `AIOMsgItem`
     * is a stable, separately-named class in both apps.
     */
    private fun resolveMsgBase(): Class<*>? {
        val loader = resolver.classLoader()

        // 1. the authoritative route: the parent of the class that actually arrives in a bind.
        try {
            val item = Class.forName(AIO_MSG_ITEM, false, loader)
            val sup = item.superclass
            if (sup != null && sup != Any::class.java) {
                Log.i(
                    TAG,
                    "QQ: message base resolved from " + AIO_MSG_ITEM + " -> " + sup.name +
                        " (asked the host instead of assuming a name; TIM names this class differently)",
                )
                return sup
            }
        } catch (t: Throwable) {
            note(
                "msgbase-from-super:" + t.javaClass.simpleName,
                "QQ: could not read " + AIO_MSG_ITEM + "'s superclass (" + t.message +
                    "); falling back to the known names",
            )
        }

        // 2. the known names.
        for (name in listOf(MSG_BASE_QQ, MSG_BASE_TIM)) {
            try {
                val c = Class.forName(name, false, loader)
                Log.i(TAG, "QQ: message base resolved by known name -> " + c.name)
                return c
            } catch (_: Throwable) {
                // try the next
            }
        }
        return null
    }

    private fun installAbsMsgListVbHook(): Boolean {
        val cls = try {
            Class.forName(ABS_MSG_LIST_VB, false, resolver.classLoader())
        } catch (t: Throwable) {
            note(
                "no-absvb",
                "QQ: " + ABS_MSG_LIST_VB + " is not loadable via the host loader (" + t.message +
                    "); the primary history feeder cannot be installed",
            )
            return false
        }

        val msgBase = resolveMsgBase() ?: run {
            note(
                "no-msgbase",
                "QQ: neither " + MSG_BASE_QQ + " nor " + MSG_BASE_TIM + " is loadable, so the message " +
                    "base type is unknown and the primary history feeder cannot be installed",
            )
            return false
        }

        val target = findBindMethod(cls, msgBase) ?: run {
            note(
                "no-bind-method:" + cls.name,
                "QQ: no 4-argument void method on " + cls.name + " takes " + msgBase.name +
                    " as its 3rd parameter; the primary history feeder cannot be installed. " +
                    "Declared 4-arg methods: " + fourArgSummary(cls),
            )
            return false
        }

        val isFinal = Modifier.isFinal(target.modifiers)
        hookTarget = cls.name + "#" + target.name

        Log.i(
            TAG,
            "QQ: history feeder target = " + hookTarget + " modifiers=[" +
                Modifier.toString(target.modifiers) + "] final=" + isFinal +
                " params=" + target.parameterCount,
        )

        return try {
            LegacyHook.hook(target)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    // Runs BEFORE the original, inside QQ's own bind call. A throw here would run in
                    // QQ's stack, so it must never escape.
                    try {
                        onBind(chain.getArg(2))
                        // v8.0.0: also remember WHICH VIEW this message was bound to.
                        //
                        // This hook is the only place in the module where a message and its ROW are
                        // available together: arg 0 is the holder and arg 2 is the message. The
                        // multi-select toolbar needs exactly that pairing and nothing else worked --
                        // the adapter's per-position getter and its whole-list getter both returned
                        // nothing on the device (`adapter exposed 0 message(s)`), because QQ's list
                        // delegates to `AutoCancelAsyncListDiffer` and exposes neither.
                        //
                        // Rebuilding the link at the moment it genuinely exists costs one map put per
                        // bind and removes the need to guess at the list's internals at all.
                        rememberRow(chain.getArg(0), chain.getArg(2))
                    } catch (t: Throwable) {
                        note(
                            "bind-threw:" + t.javaClass.name,
                            "QQ: the bind feeder threw and was swallowed: " + t.message,
                        )
                    }
                }
            Log.i(
                TAG,
                "QQ: hooked " + hookTarget + " -- live history now records as rows bind",
            )
            true
        } catch (t: Throwable) {
            note("hook-failed:" + target.name, "QQ: hooking " + hookTarget + " failed: " + t.message)
            false
        }
    }

    /**
     * The bind body, found by shape.
     *
     * Matching rules, in order of strictness:
     *   * declared on the class (not inherited), 4 parameters, `void` return;
     *   * 3rd parameter is ASSIGNABLE FROM the message base type -- `isAssignableFrom` rather than
     *     equality, because `AIOMsgItem` subclasses are what actually arrives and a QQ update could
     *     widen the declared type.
     */
    private fun findBindMethod(cls: Class<*>, msgBase: Class<*>): Method? {
        methodCache[cls.name]?.let { return it }
        if (methodMissing.contains(cls.name)) return null

        val found = try {
            // The predicate itself is a PURE function in QQHistoryLogic, so the rule that decides
            // "is this the bind body" is pinned by a JVM test rather than only by a device run.
            cls.declaredMethods.firstOrNull { m ->
                QQHistoryLogic.matchesBindShape(
                    paramCount = m.parameterCount,
                    returnsVoid = m.returnType == Void.TYPE,
                    msgBaseAssignable = m.parameterTypes.size > QQHistoryLogic.BIND_MSG_PARAM_INDEX &&
                        msgBase.isAssignableFrom(m.parameterTypes[QQHistoryLogic.BIND_MSG_PARAM_INDEX]),
                )
            }
        } catch (_: Throwable) {
            null
        }

        if (found == null) {
            methodMissing.add(cls.name)
            return null
        }
        try {
            found.isAccessible = true
        } catch (_: Throwable) {
            // Not fatal: the hook does not need setAccessible, and a public method stays public.
        }
        methodCache[cls.name] = found
        return found
    }

    /** Names the declared 4-argument methods, so a shape mismatch is diagnosable from the log alone. */
    private fun fourArgSummary(cls: Class<*>): String = try {
        cls.declaredMethods
            .filter { it.parameterCount == 4 }
            .joinToString("; ") { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
            }
            .ifEmpty { "(none)" }
    } catch (_: Throwable) {
        "(unreadable)"
    }

    // ------------------------------------------------------------------ hook 2: the VM fallback

    /**
     * Hooks `AIOMsgListVM#o0(MsgRecord)V` -- the fallback feeder.
     *
     * T19 verified this is the only call site that pushes a `MsgRecord` into the message-list VM
     * (called from `com.tencent.mobileqq.aio.msglist.w#run()` in classes6.dex). Its parameter is the
     * record itself, so nothing needs unwrapping.
     *
     * Resolved by SHAPE as well: a declared void method on the VM whose single parameter's name ends
     * in `MsgRecord`. Matching by the TYPE NAME rather than by the class object keeps this working
     * even if the record class fails to load through this loader.
     */
    private fun installMsgListVmHook(): Boolean {
        val cls = try {
            Class.forName(MSG_LIST_VM, false, resolver.classLoader())
        } catch (t: Throwable) {
            note("no-vm", "QQ: " + MSG_LIST_VM + " is not loadable (" + t.message + ")")
            return false
        }

        // The message is NOT necessarily argument 0. See QQHistoryLogic.msgRecordParamIndex: the
        // verified descriptor is `o0(AIOMsgListVM, MsgRecord)V` (public|static, the Kotlin
        // member-body-extracted-to-static-helper shape), so arg 0 is the VM and arg 1 is the record.
        // Taking the index from the matcher is what keeps `getArg` correct.
        //
        // The known name `o0` is tried FIRST, then the shape. `Class.getDeclaredMethods` order is
        // explicitly unspecified by the JVM, so a shape-only `firstOrNull` over a class with more
        // than one MsgRecord method would pick an arbitrary one -- and once hooked, there is no
        // second chance. Preferring the verified name makes the choice deterministic across devices.
        val candidates = try {
            cls.declaredMethods.filter { m ->
                QQHistoryLogic.msgRecordParamIndex(
                    paramCount = m.parameterCount,
                    returnsVoid = m.returnType == Void.TYPE,
                    paramTypeNames = m.parameterTypes.map { it.name },
                ) >= 0
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val target = candidates.firstOrNull { it.name == VM_ENTRY_NAME } ?: candidates.firstOrNull()

        if (target == null) {
            note(
                "no-vm-method:" + cls.name,
                "QQ: " + cls.name + " declares no void method taking a MsgRecord parameter; " +
                    "the fallback feeder cannot be installed",
            )
            return false
        }
        val paramIndex = QQHistoryLogic.msgRecordParamIndex(
            paramCount = target.parameterCount,
            returnsVoid = target.returnType == Void.TYPE,
            paramTypeNames = target.parameterTypes.map { it.name },
        )
        if (candidates.size > 1) {
            note(
                "vm-multi-candidate:" + cls.name,
                "QQ: " + cls.name + " has " + candidates.size + " MsgRecord-taking void methods (" +
                    candidates.joinToString(",") { it.name } + "); chose " + target.name,
            )
        }

        val argIndex = paramIndex
        return try {
            LegacyHook.hook(target)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    try {
                        onBind(chain.getArg(argIndex))
                    } catch (t: Throwable) {
                        note(
                            "vm-threw:" + t.javaClass.name,
                            "QQ: the VM feeder threw and was swallowed: " + t.message,
                        )
                    }
                }
            Log.i(
                TAG,
                "QQ: hooked " + cls.name + "#" + target.name + " (fallback feeder, msg arg=" +
                    argIndex + " of " + target.parameterCount + ") -- records arriving in the " +
                    "message-list VM are also captured",
            )
            true
        } catch (t: Throwable) {
            note("vm-hook-failed", "QQ: hooking " + cls.name + "#" + target.name + " failed: " + t.message)
            false
        }
    }

    // ------------------------------------------------------------------ the feed

    /**
     * One bind (or one record arriving in the VM): classify, then push into the EXISTING pipeline.
     *
     * Constant cost by design. The only reflection is `QQMsgAccess.isMessage`, which caches its
     * results; the text/time/flag extraction and all I/O happen later, on the pipeline's own thread,
     * inside `onLiveMessage` and its 2000 ms batch writer.
     *
     * The pipeline is the ONE place that decides whether a message is storable: it re-derives
     * `talker` / `content` / `createTime` itself and rejects an unusable one LOUDLY. So this method
     * does NOT duplicate those rules -- it only filters out rows that are not messages at all
     * (time separators, headers, footers, avatars), because feeding those would make
     * `onLiveMessage` log a rejection for something that was never a message.
     */
    private fun onBind(candidate: Any?) {
        bindsSeen++
        if (candidate == null) {
            // A null argument is a REAL anomaly (not a non-message row): the hook fired, QQ passed
            // nothing. Counted and named once, because it would otherwise look like "no messages".
            skipped++
            note("null-arg", "QQ: a bind reached the history feeder with a null message argument")
            return
        }
        try {
            // `isMessage` accepts either the UI item (`AIOMsgItem`) or the record itself, using
            // `isAssignableFrom` rather than equality -- which matters because the 42 descendants of
            // `AIOMsgItem` (FileMsgItem, GrayTipsMsgItem, ArkMsgItem, ...) are what really arrive.
            if (!access.isMessage(candidate)) {
                skipped++
                // Named by class ONCE per type: a time separator or header is the normal case and must
                // not fill the log, but a NEW unexpected type is exactly what a bug report needs.
                note(
                    "not-msg:" + candidate.javaClass.name,
                    "QQ: a bind carried " + candidate.javaClass.name +
                        " which is not a message (a time separator, header or footer); skipped",
                )
                return
            }

            // THE ONE CALL that feeds the existing pipeline. It stages the line; the 2000 ms timer
            // in AnalysisController flushes it. Nothing here writes to disk.
            controller.onLiveMessage(candidate)
            pushed++

            if (pushed == 1) {
                // The FIRST push names the dedup key and the bucket, so a support log answers
                // "which conversation, group or private, and what id" from one line. Reading the
                // talker here is cheap (a cached reflective call) and happens ONCE, not per message.
                val tk = try {
                    access.talker(candidate)
                } catch (_: Throwable) {
                    ""
                }
                val id = try {
                    access.msgId(candidate)
                } catch (_: Throwable) {
                    0L
                }
                val seq = try {
                    access.msgSeq(candidate)
                } catch (_: Throwable) {
                    0L
                }
                val chatType = try {
                    access.chatTypeOf(candidate)
                } catch (_: Throwable) {
                    -1
                }
                Log.i(
                    TAG,
                    "QQ: FIRST message pushed into the live history feed -- history now writes itself " +
                        "every 2s without an analysis. Dedup key is (talker, msgId) = " +
                        QQHistoryLogic.dedupKey(tk, id, seq) +
                        " " + QQHistoryLogic.describe(tk, chatType, id) +
                        " class=" + candidate.javaClass.name,
                )
            }
        } catch (t: Throwable) {
            // onLiveMessage is itself defensive, but this hook runs in QQ's bind stack, so the guard
            // is kept here too rather than trusted.
            note(
                "push-threw:" + t.javaClass.name,
                "QQ: onLiveMessage threw from the history feeder and was swallowed: " + t.message,
            )
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Logs a line once per distinct SIGNATURE.
     *
     * 必看.md: rate-limiting by event COUNT lets a repeated cause exhaust the budget and suppress a
     * genuinely new one -- which is the information a bug report is made of. Signatures are capped as
     * a second line of defence against an unexpected flood.
     */
    private fun note(signature: String, message: String) {
        if (!logged.add(signature)) return
        if (logged.size > LOG_SIGNATURE_LIMIT) return
        Log.w(TAG, message)
    }

    /** A one-line state summary, so a support log answers "did the feeder run and what did it see". */
    fun describe(): String =
        "history feeder: target=" + hookTarget +
            " bindsSeen=" + bindsSeen +
            " pushed=" + pushed +
            " skipped=" + skipped +
            " distinctLogs=" + logged.size
}
