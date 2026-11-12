# ============================================================ 落聊 R8 / ProGuard 规则
#
# WHY THIS FILE HAS TO EXIST AT ALL:
# `release` now runs R8 (see app/build.gradle.kts: it is what removes the log strings, so the release
# build contains no log text at all). R8 renames and deletes freely, and this module is reached in ways
# R8 cannot see:
#
#   * the framework loads `ModuleEntry` BY NAME from `assets/xposed_init` -- R8 sees no caller, so it
#     would otherwise rename or strip the entry point and the module would simply never load;
#   * every host hook resolves the HOST's obfuscated members BY STRING via reflection. The host classes
#     are not ours, so R8 will not touch them -- but our own helper/enum/tag classes are reached from
#     those reflective paths and must keep their shape.
#
# THE FAILURE MODE THIS PREVENTS IS SILENT, which is why the rules are explicit rather than relying on
# R8's defaults: a stripped entry point produces no crash, no log, nothing -- the module just does not
# run. Any future edit that adds a new framework-facing entry point must add it here too.

# ---------------------------------------------------------------- the framework entry point
# Named in assets/xposed_init; loaded by the framework. Must keep the exact name AND the class.
-keep class com.luoliaoo.wechat.ModuleEntry { *; }
-keepnames class com.luoliaoo.wechat.ModuleEntry

# ---------------------------------------------------------------- ★ RELEASE MUST NOT CONTAIN LOG TEXT
#
# THIS IS THE RULE THAT ACTUALLY MAKES "release has no logs" TRUE, and it is not optional.
#
# `core/Log.kt` also guards every method with `if (!LOG_ENABLED) return 0`, but that guard ALONE is not
# enough, and the first release build proved it: the APK came out with the log strings still inside
# ("KUAISHOU multi-select" appeared 23 times in the dex). The reason is that the guard lives in `write()`,
# one call deeper than the strings. R8 does not propagate a constant ACROSS a method call, so the callers
# kept building `"KUAISHOU multi-select: " + ...` and passing it in, and the concatenated literals stayed
# in the string pool even though nothing would ever print them.
#
# `-assumenosideeffects` tells R8 that calling these methods has no observable effect, so it deletes the
# CALLS. With the calls gone the arguments are unused, and R8 then removes the string literals too. That
# is what turns "we do not log" into "the text is not in the binary".
#
# SIGNATURES ARE INSTANCE METHODS, NOT STATIC: `object Log` in Kotlin compiles to a class with an
# `INSTANCE` field, so `Log.i(..)` is `Log.INSTANCE.i(..)`. Writing `public static` here would silently
# match nothing and the strings would come back -- which is exactly the kind of quiet no-op this file
# exists to prevent, so it is spelled out.
#
# DO NOT add the two-arg-with-Throwable overloads to this list blindly in a future edit without checking:
# they are the same shape, and they ARE included below because release should print nothing at all.
-assumenosideeffects class com.luoliaoo.wechat.core.Log {
    public int v(java.lang.String, java.lang.String);
    public int d(java.lang.String, java.lang.String);
    public int i(java.lang.String, java.lang.String);
    public int w(java.lang.String, java.lang.String);
    public int e(java.lang.String, java.lang.String);
    public int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public int i(java.lang.String, java.lang.String, java.lang.Throwable);
    public int w(java.lang.String, java.lang.String, java.lang.Throwable);
    public int e(java.lang.String, java.lang.String, java.lang.Throwable);
}

# The legacy de.robv API interfaces are provided by the framework at runtime (compileOnly here), so the
# implementing class must keep the interface methods it is called through.
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookZygoteInit {
    public void initZygote(de.robv.android.xposed.IXposedHookZygoteInit$StartupParam);
}
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookInitPackageResources {
    public void handleInitPackageResources(de.robv.android.xposed.callbacks.XC_InitPackageResources$InitPackageResourcesParam);
}

# ---------------------------------------------------------------- XposedBridge callbacks
# `XC_MethodHook` subclasses are invoked by the framework through the abstract callback methods.
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
    protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
}

# ---------------------------------------------------------------- reflective hook helpers
# `LegacyHook` builds hooks reflectively and the module resolves host members by NAME at runtime; these
# members are looked up as strings, so renaming them breaks the lookup with no compile-time error.
#
# PACKAGE IS `core`, NOT `hook` -- an earlier revision of this file said `hook.LegacyHook`, which matched
# nothing. R8 does not warn about a rule that matches no class, so the mistake was silent; it was found by
# checking `mapping.txt` for the class and noticing it was absent. That is the general lesson for this
# file: every `-keep` here must be confirmed against `mapping.txt` after a release build, because a
# mistyped rule fails OPEN (nothing is kept) rather than erroring.
-keep class com.luoliaoo.wechat.core.LegacyHook { *; }
-keep class com.luoliaoo.wechat.core.ModuleBridge { *; }

# ---------------------------------------------------------------- enums used by name
# Kotlin enums are looked up by `valueOf`/`name` in config and scope handling; R8's enum unboxing would
# break those lookups.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------- tags and constants
# Toolbar/menu identity is carried as a STRING TAG on host views and compared at runtime. If R8 inlined
# the constant the comparison logic would still work, but keeping it makes the value readable in a
# decompiled APK, which is how the tag is documented in the source.
-keepclassmembers class com.luoliaoo.wechat.hook.KuaishouHooks {
    public static final java.lang.String TOOLBAR_BUTTON_TAG;
}

# ---------------------------------------------------------------- Activities reachable from the launcher
# Declared in AndroidManifest.xml; the system starts them BY NAME, so the class names must survive.
#
# BOTH live in the `ui` package (verified, not assumed -- an earlier revision of this file listed them in
# the ROOT package, where no such class exists, so those two rules protected nothing while still looking
# correct). The `ui.**` rule below covers them; they are also named explicitly so that a future move of
# either Activity out of `ui` cannot silently drop the protection.
-keep class com.luoliaoo.wechat.ui.** { *; }
-keep class com.luoliaoo.wechat.ui.SettingsActivity { *; }
-keep class com.luoliaoo.wechat.ui.LauncherActivity { *; }

# Android components (Activities/Services/Receivers/Providers) referenced from the manifest are
# instantiated by the framework by name.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ---------------------------------------------------------------- keep attributes R8 would drop
# Reflection reads member NAMES and signatures; without these, `getDeclaredMethod("foo", X)` fails even
# when the member was kept.
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*

# ---------------------------------------------------------------- standard noise
-dontwarn de.robv.android.xposed.**
-dontwarn android.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Keep line numbers so a crash report from a release build is still diagnosable (names are obfuscated,
# but the line number maps back through the mapping file R8 writes next to the APK).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
