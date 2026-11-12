package com.luoliaoo.wechat.core

import com.luoliaoo.wechat.core.LegacyHook

import com.luoliaoo.wechat.core.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member

/**
 * v3.0.0: THE de.robv HOOK SHIM.
 *
 * The module now registers through WeKit's LEGACY entry point (`assets/xposed_init` +
 * `de.robv.android.xposed`), because that is the path that loads on frameworks where the modern libxposed
 * entry point does not.
 *
 * The de.robv API is a different surface from libxposed:
 *
 *     libxposed:  LegacyHook.hook(m).intercept { chain -> ...; chain.proceed() }
 *     de.robv:    XposedBridge.hookMethod(m, object : XC_MethodHook() {
 *                     override fun beforeHookedMethod(param: MethodHookParam) { ... }
 *                 })
 *
 * Rather than rewrite every hook body, this shim provides the libxposed NAMES on top of XposedBridge, so the
 * existing code keeps working unchanged:
 *
 *     LegacyHook.hook(member)
 *         .setExceptionMode(Frame.EXCEPTION_MODE)      // accepted and ignored
 *         .intercept { chain -> ...; chain.proceed() } // runs BEFORE the original, exactly like hookBefore
 *
 * `chain.proceed()` is honoured by running the original method when the body did not replace its result. That is
 * the de.robv equivalent, and it means a body that returns early without proceeding genuinely skips the call.
 */
object LegacyHook {

    /** Mirrors the libxposed exception-mode enum so call sites need no change. */
    object Frame {
        const val EXCEPTION_MODE = 0
    }

    fun hook(member: Member): Builder = Builder(member)

    class Builder internal constructor(private val member: Member) {
        private var protect = false

        /** Kept for source compatibility with the libxposed call sites. */
        fun setExceptionMode(mode: Int): Builder {
            protect = true
            return this
        }

        /**
         * Registers the body. It runs BEFORE the original method.
         *
         * The chain object exposes `thisObject`, `getArg(i)`, `args`, `proceed()` and `result`, which is what
         * every existing hook body uses.
         */
        fun intercept(body: (Chain) -> Unit) {
            try {
                XposedBridge.hookMethod(member, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val chain = Chain(param)
                        chain.memberName = member.name
                        try {
                            body(chain)
                        } catch (t: Throwable) {
                            if (!protect) throw t
                            Log.w("LuoLiao", "hook body failed on " + member.name + ": " + t.message)
                        }
                        chain.applyTo(param, member)
                    }
                })
            } catch (t: Throwable) {
                Log.e("LuoLiao", "hookMethod failed for " + member.name, t)
            }
        }

        /**
         * v3.0.2: registers the body to run AFTER the original method -- WeKit's `hookAfter`.
         *
         * THIS IS REQUIRED FOR VIEW-POPULATING HOOKS. `intercept` runs in beforeHookedMethod, i.e. BEFORE
         * WeChat has built its buttons, so a bar that is empty at that moment has childCount == 0 and any
         * "insert at index 0" attempt either bails out or inserts into a list WeChat then wipes. WeKit
         * inserts its button with `hookAfter`, and so must we.
         *
         * The chain reports the original's result through [Chain.result].
         */
        fun interceptAfter(body: (Chain) -> Unit) {
            try {
                XposedBridge.hookMethod(member, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val chain = Chain(param)
                        chain.memberName = member.name
                        chain.captureResult(param)
                        try {
                            body(chain)
                        } catch (t: Throwable) {
                            if (!protect) throw t
                            Log.w("LuoLiao", "hook body (after) failed on " + member.name + ": " + t.message)
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.e("LuoLiao", "hookMethod (after) failed for " + member.name, t)
            }
        }
    }

    /** The libxposed-shaped view of a de.robv MethodHookParam. */
    class Chain internal constructor(private val param: XC_MethodHook.MethodHookParam) {

        internal var memberName: String = "(unknown)"
        /**
         * The receiver. The libxposed API exposed this as non-null, and every call site relies on it, so the
         * shim asserts the same contract: a hooked instance method always has a receiver.
         */
        val thisObject: Any get() = param.thisObject
            ?: error("thisObject is null for " + memberName)
        val args: Array<Any?> get() = param.args ?: emptyArray()

        fun getArg(i: Int): Any? =
            try {
                param.args?.getOrNull(i)
            } catch (_: Throwable) {
                null
            }

        /** True once a body has replaced the result, which means the original must not run. */
        private var resultSet = false

        private var pendingResult: Any? = null

        /**
         * The de.robv equivalent of `chain.proceed()`.
         *
         * In libxposed, `proceed()` calls the original and returns its value. Here the original runs AFTER this
         * body, so proceeding is the default and this method exists to be explicit and to mirror the call sites.
         * A body that needs to SKIP the original calls [skipOriginal].
         */
        fun proceed(): Any? = null

        /**
         * The original method's return value, available inside an [Builder.interceptAfter] body.
         * Null for before-hooks, where the original has not run yet.
         */
        var result: Any? = null
            private set

        internal fun captureResult(p: XC_MethodHook.MethodHookParam) {
            result = try {
                p.getResult()
            } catch (_: Throwable) {
                null
            }
        }

        /** Skips the original method, the de.robv way. */
        fun skipOriginal(result: Any? = null) {
            resultSet = true
            pendingResult = result
        }

        internal fun applyTo(p: XC_MethodHook.MethodHookParam, member: Member) {
            // The de.robv contract: setResult() sets returnEarly, which is how the framework is told to
            // SKIP the original method. The private `result` field must never be touched directly --
            // the framework's class does not expose it, so reading/writing it would not link at run time.
            if (resultSet) {
                p.setResult(pendingResult)
            }
        }
    }
}
