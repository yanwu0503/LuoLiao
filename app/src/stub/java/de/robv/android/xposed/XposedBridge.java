package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.util.Set;

/**
 * Compile-only stub of the framework's {@code de.robv.android.xposed.XposedBridge}.
 *
 * SIGNATURES MUST MATCH THE REAL FRAMEWORK EXACTLY. This jar never ships inside the APK
 * (`compileOnly`), so at run time the framework's own class is used. If a signature here differs,
 * the module compiles fine and then dies at run time with NoSuchMethodError -- which is exactly what
 * happened when `hookMethod` was declared as returning void instead of XC_MethodHook.Unhook.
 *
 * Reference: https://github.com/rovo89/XposedBridge/blob/art/app/src/main/java/de/robv/android/xposed/XposedBridge.java
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    /**
     * Hook any method (or constructor). The real framework returns an Unhook, and LSPosed's
     * implementation follows the same contract, so the return type is REQUIRED here.
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * Hook ALL constructors of a class. Returns one Unhook per constructor, so a partially
     * installed set can be rolled back.
     *
     * The real framework signature is
     *     public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback)
     * and it is `Set`, NOT `List` -- the difference would compile here and die at run time with
     * NoSuchMethodError, which is the exact class of bug this stub file's header warns about.
     */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static int getXposedVersion() {
        return 0;
    }
}
