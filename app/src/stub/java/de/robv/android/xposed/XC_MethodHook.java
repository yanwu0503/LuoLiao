package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-only stub of the framework's {@code de.robv.android.xposed.XC_MethodHook}.
 *
 * Mirrors the real class exactly (see XposedBridge.java for why that matters):
 *   - MethodHookParam keeps a PRIVATE `result` field exposed through getResult()/setResult().
 *     The module must never read the field directly -- the framework's class has no such
 *     accessible field, so `param.result` would not even link at run time.
 *   - Unhook is an inner class of XC_MethodHook, which is what hookMethod() returns.
 */
public abstract class XC_MethodHook extends XCallback {

    public static class MethodHookParam extends Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public boolean returnEarly;

        public Object getResult() { return result; }

        public void setResult(Object r) {
            this.result = r;
            this.returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable t) {
            this.throwable = t;
            this.returnEarly = true;
        }

        public boolean hasThrowable() { return throwable != null; }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public static class Param {
    }

    /** Returned by XposedBridge.hookMethod; lets a hook be removed again. */
    public class Unhook {
        public final Member hookMethod;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public Member getHookedMethod() { return hookMethod; }

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}
