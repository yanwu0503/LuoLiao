package com.luoliaoo.wechat

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.Log

/**
 * v3.0.0: THE LEGACY ENTRY POINT, written the way WeKit's `legacy` flavour writes it.
 *
 * WEKIT:
 *     public class Xp51HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
 *         public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
 *             if (PackageNames.isWeChat(lpparam.packageName)) { ModuleLoader.init(...); }
 *         }
 *         public void initZygote(StartupParam startupParam) { modulePath = startupParam.modulePath; }
 *     }
 *
 * It registers through `assets/xposed_init` ONLY -- no META-INF/xposed, no libxposed API at all. That is why
 * WeKit's legacy build loads on frameworks where the modern entry point does not.
 *
 * The class name in assets/xposed_init points here.
 */
class ModuleEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "LuoLiao"

        /** The module APK path, from initZygote. Needed to locate libdexkit.so. */
        @Volatile
        var modulePath: String? = null
            private set

        /**
         * ★ v9.9.9：本进程是否**真的**是被 LSPosed 注入的宿主进程。
         *
         * ─────────────────────────────────────────────────────────────
         * 【为什么必须有这个标记，而不能用 `HostApp.current.isSupported`】
         *
         * 模块自己的界面（SettingsActivity / LauncherActivity）跑在**模块自己的进程**里，
         * LSPosed 从不注入它，所以 `handleLoadPackage` 不执行、`setCurrent` 不被调用 ——
         * 但 `HostApp.current` 的**默认值是 `WECHAT`**（见 HostApp.kt 第 577 行，
         * 那个默认值是故意设的，为了模块自己的界面不显示空标签）。
         *
         * 于是 `current.isSupported` 在模块自己的进程里**也是 true** ——
         * 拿它当「这是宿主进程」的证据会得到错误答案。
         *
         * 这个标记只在 [handleLoadPackage] 真正跑过之后才为 true，是**唯一可靠**的判据。
         *
         * 【用途】`UpdateScopeGuard` 用它实现「更新检测弹窗仅在作用域 App 显示」：
         * 模块自己的界面、以及任何没被注入的进程都不会弹。
         */
        @Volatile
        var isHostProcess: Boolean = false
            private set

        /**
         * 5.0.0: the module serves TWO hosts (WeChat / Douyin), so the entry asks HostApp rather
         * than testing for WeChat alone.
         *
         * `HostApp.of` is the SINGLE place mapping a package to a host. It returns UNKNOWN for an
         * unrelated app the user may have ticked by mistake, and UNKNOWN installs NOTHING -- guessing
         * a host here would run WeChat's class-name probes inside a stranger's process.
         *
         * The process name is passed as a second opinion because a CLONE sometimes reports the
         * original package there while `packageName` carries the rewritten one.
         *
         * NOTE: the old `isWeChatProcess` helper was DELETED in 5.0.0 rather than left in place. It
         * was unused after this change, and a stale "is this WeChat?" predicate next to a two-host
         * entry point invites someone to trust it again.
         */
        fun hostFor(pkg: String?, process: String?): HostApp =
            HostApp.ofProcess(pkg, process)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // WeKit does exactly this, and only this, in initZygote.
        modulePath = startupParam.modulePath
        Log.i(TAG, ">>> " + BuildInfo.BANNER + " <<< initZygote modulePath=" + startupParam.modulePath)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            Log.i(
                TAG,
                ">>> " + BuildInfo.BANNER + " <<< process=" + lpparam.processName +
                    " package=" + lpparam.packageName,
            )
            Log.i(TAG, "MODULE LOADED INTO: " + lpparam.processName)

            // 5.0.0: the module now serves BOTH 微信 and 抖音. The scope array declares the packages,
            // so anything that reaches here was ticked by the user -- but "ticked" is not "supported":
            // HostApp.of returns UNKNOWN for an unrelated app, and UNKNOWN must install nothing.
            val host = hostFor(lpparam.packageName, lpparam.processName)
            HostApp.setCurrent(host)
            if (!host.isSupported) {
                Log.i(
                    TAG,
                    "unsupported host; skip (package=" + lpparam.packageName +
                        " process=" + lpparam.processName + ")",
                )
                return
            }
            Log.i(
                TAG,
                "host=" + host.name + " (" + host.displayName + ") -- installing hooks",
            )

            /* ★ v9.9.9：到这里才能确认「这是一个真正的作用域宿主进程」——
               我们在宿主的进程里、且这个宿主是我们支持的。
               标记放在 `!host.isSupported` 那个 return **之后**，这一点很关键：
               用户可能误勾了无关的 App，那种进程同样会被注入，但它不是我们的作用域，
               更新弹窗不该在它里面出现。
               用它实现「更新检测弹窗仅在作用域 App 显示」（见 UpdateScopeGuard）。 */
            isHostProcess = true

            val app = try {
                Class.forName("android.app.ActivityThread", false, lpparam.classLoader)
                    .getDeclaredMethod("currentApplication")
                    .invoke(null) as? Application
            } catch (_: Throwable) {
                null
            }
            if (app == null) {
                Log.w(TAG, "no Application yet; the Activity capture path will retry")
            }

            Log.i(
                TAG,
                "CLONE DIAGNOSTICS: package=" + lpparam.packageName +
                    " process=" + lpparam.processName +
                    " classLoader=" + lpparam.classLoader.javaClass.name,
            )

            // Hand everything to the existing installer with the HOST class loader, exactly as the libxposed
            // entry used to. The installer reads HostApp.current to pick its hook set, so the host must be
            // set BEFORE this call -- which it is, a few lines above.
            WeChatHooks(lpparam.classLoader, lpparam.appInfo.sourceDir)
                .installAllForLegacy(app, modulePath)
        } catch (t: Throwable) {
            Log.e(TAG, "handleLoadPackage failed", t)
        }
    }
}
