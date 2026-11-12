package com.luoliaoo.wechat.core

import android.content.Context
import android.content.pm.ApplicationInfo
import com.luoliaoo.wechat.ModuleEntry
import java.io.File

/** Process-wide bridge holding Context / module info / resolver results. */
object ModuleBridge {

    private const val TAG = "LuoLiao"

    @Volatile
    var appContext: Context? = null
        private set

    /**
     * v2.6.0: THE HOST CLASS LOADER, solved the way WeKit solves it.
     *
     * WeKit's StartupAgent does exactly this:
     *
     *     val realClassLoader = application.baseContext.classLoader
     *     ReflectionClassLoader.value = realClassLoader
     *
     * `baseContext` carries WeChat's OWN classes. Using a module-side loader instead produces a
     * DEVICE-DEPENDENT failure that looks healthy in the log:
     *
     *     DexKit still succeeds (it reads the APK file, not a loader)  -> "candidates=1"
     *     Class.forName(name, false, loader) then fails
     *     the candidate is skipped, no hook is installed, NO BUTTON APPEARS
     *
     * That is why the button shows on some phones and not others.
     */
    @Volatile
    var hostClassLoader: ClassLoader? = null
        private set

    /**
     * Picks the FIRST loader that can actually SEE a WeChat class, preferring `baseContext` exactly as
     * WeKit does. A loader that cannot load `com.tencent.mm.app.Application` is useless for hooking and is
     * rejected LOUDLY instead of failing later in silence.
     */
    fun rememberHostLoader(app: android.app.Application?) {
        // v2.7.1: THE CLONE FIX.
        //
        // MEASURED in the clone: the hook INSTALLS (30x) but NEVER FIRES (0), while user 0 shows 24 firings.
        // A hook that installs and never fires was installed on a Method object from a loader that the running
        // WeChat does not use -- so the class it belongs to is never the one that executes.
        //
        // Accepting a loader merely because it can load `com.tencent.mm.app.Application` is NOT enough: in a
        // clone several loaders can do that, and only one is the loader whose classes are instantiated.
        //
        // So a candidate must be able to load the CHAT class the hook targets -- that is the class our hook has
        // to match. WeChat's own `baseContext.classLoader` is tried first, exactly as WeKit does.
        val probes = arrayOf(
            "com.tencent.mm.ui.chatting.viewitems.ChattingItemTranslate",
            "com.tencent.mm.app.Application",
        )

        val candidates = ArrayList<Pair<String, ClassLoader>>(4)
        try {
            app?.baseContext?.classLoader?.let { candidates.add("baseContext" to it) }
        } catch (_: Throwable) {
            // baseContext can be unavailable very early
        }
        try {
            app?.classLoader?.let { candidates.add("application" to it) }
        } catch (_: Throwable) {
            // ignore
        }
        try {
            appContext?.classLoader?.let { candidates.add("appContext" to it) }
        } catch (_: Throwable) {
            // ignore
        }

        var best: ClassLoader? = null
        var bestLabel = ""
        var bestScore = -1
        for ((label, loader) in candidates) {
            var score = 0
            for ((i, probe) in probes.withIndex()) {
                val ok = try {
                    loader.loadClass(probe)
                    true
                } catch (_: Throwable) {
                    false
                }
                // the CHAT class is worth far more than Application: it is what our hook matches
                if (ok) score += if (i == 0) 100 else 1
                Log.i(
                    TAG,
                    "host loader candidate '" + label + "': " + probe + " -> " + ok,
                )
            }
            if (score > bestScore) {
                bestScore = score
                best = loader
                bestLabel = label
            }
        }

        if (best != null && bestScore > 0) {
            hostClassLoader = best
            Log.i(
                TAG,
                "host class loader ACCEPTED: " + bestLabel + " (" + best.javaClass.name +
                    ") score=" + bestScore,
            )
        } else if (!isWeChatHost()) {
            // v7.0.0: exhaustive host dispatch (`isWeChatHost` switches on every host with no `else ->`)
            // instead of the earlier bare `HostApp.current != HostApp.WECHAT`. Same behaviour, but adding a
            // fifth host is now a compile error in that helper rather than a silent inheritance here.
            // 5.0.0: the probes ABOVE are WeChat class names, so a non-WeChat host scores 0 by
            // construction -- it is not a failure. Reporting E here sent whoever read the Douyin log
            // chasing a host-loader bug that does not exist, while the module was working fine (the
            // resolvers all fall back to `?: loader`). The message now says what is actually true.
            Log.i(
                TAG,
                "host loader probe skipped: the probes are WeChat class names and host=" +
                    HostApp.current.name + ". The module uses the framework loader instead.",
            )
        } else {
            Log.e(
                TAG,
                "NO USABLE host class loader among " + candidates.size +
                    " candidate(s); no hook can fire and no menu item will appear",
            )
        }
    }

    /**
     * v7.0.0: true only for the WeChat host, decided by an EXHAUSTIVE `when` on the host.
     *
     * Replaces call sites that asked `HostApp.current != HostApp.WECHAT`. An excluded-style test is how a
     * newly added host silently inherits a decision nobody made for it (Kuaishou was added exactly that
     * way). Enumerating the hosts makes the next host a COMPILE ERROR here, so it gets a deliberate answer
     * instead of someone else's default.
     */
    private fun isWeChatHost(): Boolean = when (HostApp.current) {
        HostApp.WECHAT -> true
        HostApp.DOUYIN,
        HostApp.SOUL,
        HostApp.KUAISHOU,
        HostApp.QQ,
        // v9.3.0: Xiaohongshu. NOT WeChat -- its module context must be resolved the way the other
        // four non-WeChat hosts do (through the host's own filesDir), never through WeChat's anchors.
        HostApp.XIAOHONGSHU,
        HostApp.UNKNOWN,
        -> false
    }

    /** Our module's ApplicationInfo, needed to locate libdexkit.so. */
    @Volatile
    var moduleAppInfo: ApplicationInfo? = null

    /**
     * OUR module's own Context.
     *
     * This is the clone-safe way to reach the module APK: a clone runs as another Android user and cannot
     * read user 0's /data/app path, but a Context created for the module package always can. Without this,
     * DexKit's extraction failed in the clone and the module silently did nothing there.
     */
    @Volatile
    var moduleContext: Context? = null

    /**
     * Builds the module Context through ActivityThread's package Context factory.
     *
     * Called from the WeChat process, where our own package is NOT the running package, so a plain
     * createPackageContext would be refused on newer Android; the ActivityThread path is the one that
     * works from inside a hooked process.
     */
    fun ensureModuleContext(modulePkg: String, loader: ClassLoader?): Context? {
        moduleContext?.let { return it }

        // PATH A (preferred): ActivityThread's package Context factory.
        //
        // Called from the WeChat process, where our own package is NOT the running package, so a plain
        // createPackageContext would be refused on newer Android; the ActivityThread path is the one that
        // works from inside a hooked process.
        try {
            val at = Class.forName("android.app.ActivityThread", false, loader)
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val getSystem = at.getDeclaredMethod("getSystemContext").invoke(current) as? Context
            val ctx = getSystem?.createPackageContext(modulePkg, Context.CONTEXT_IGNORE_SECURITY)
            if (ctx != null) {
                moduleContext = ctx
                Log.i(TAG, "module context ready -> " + ctx.applicationInfo.sourceDir)
                return ctx
            }
            Log.w(TAG, "module context: path A returned null; trying the modulePath fallback")
        } catch (t: Throwable) {
            Log.w(TAG, "module context: path A failed (" + t.message + "); trying the modulePath fallback")
        }

        // PATH B (fallback, v8.0.0): build the Context WITHOUT asking PackageManager.
        //
        // WHY THIS EXISTS -- measured on QQ 9.3.65, and it is QQ-SPECIFIC:
        //
        //     W LuoLiao : module context unavailable: Application package
        //                 com.luoliaoo.wechat.debug not found
        //
        // In QQ that failure is REPRODUCIBLE (identical twice, across force-stop restarts) while the
        // SAME APK gets a working context inside WeChat. The cause is not our package: QQ's process
        // has another module hooking the PackageManager package filter --
        //
        //     I LSPosedFramework: (system)[...] (PmsHookTarget34) @shouldFilterApplication:
        //         query from com.tencent.mobileqq
        //
        // -- so the lookup for our own package is filtered out and `createPackageContext` reports it
        // as "not found" even though it is installed and running. A lookup that is filtered will
        // NEVER start working, so retrying path A is pointless: we need a route that does not ask
        // the PackageManager at all.
        //
        // That route already has its input available: the Zygote hook handed us the module APK path
        // in `ModuleEntry.modulePath` (it is logged on every start), but nothing ever used it for the
        // Context. From that path we can build a Context whose resources point at our own APK.
        //
        // This is deliberately additive: path A still runs first and is preferred, because it returns
        // a Context the framework fully understands. This path only rescues the QQ case.
        val apkPath = ModuleEntry.modulePath
        if (apkPath.isNullOrBlank()) {
            Log.w(TAG, "module context: modulePath is empty; no fallback possible")
            return null
        }
        return try {
            val at = Class.forName("android.app.ActivityThread", false, loader)
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val ctx = buildContextFromApk(at, current, modulePkg, apkPath, loader)
            if (ctx != null) {
                moduleContext = ctx
                Log.i(TAG, "module context ready via modulePath fallback -> " + apkPath)
            } else {
                Log.w(TAG, "module context: modulePath fallback produced no context")
            }
            ctx
        } catch (t: Throwable) {
            Log.w(TAG, "module context unavailable (both paths failed): " + t.message)
            null
        }
    }

    /**
     * Builds a Context for OUR own APK from its path, without any PackageManager query.
     *
     * Two strategies, in order of fidelity:
     *   1. `ActivityThread.getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)` -- the factory
     *      the framework itself uses for an installed package. We hand it an ApplicationInfo whose
     *      `sourceDir` is our APK, so it builds the real LoadedApk (resources, class loader, ...).
     *   2. `Resources` + `ContextImpl.createAppContext` -- used when (1) is unavailable, e.g. a
     *      framework where the private signature moved.
     *
     * Only the handful of reflective calls below are QQ-clone-sensitive, so each is separately
     * guarded and each failure is NAMED in the log: a silent null here is the exact shape of bug
     * this file exists to make visible (必看.md: "an idle pipeline that says why beats a fake success").
     */
    private fun buildContextFromApk(
        activityThread: Class<*>,
        current: Any?,
        modulePkg: String,
        apkPath: String,
        loader: ClassLoader?,
    ): Context? {
        if (current == null) {
            Log.w(TAG, "modulePath fallback: currentActivityThread is null")
            return null
        }

        // Strategy 1: getPackageInfoNoCheck(...) -> LoadedApk -> makeApplicationContext(false)
        try {
            val loadedApk = loadApkWithoutCheck(activityThread, current, modulePkg, apkPath, loader)
            if (loadedApk != null) {
                // LoadedApk.makeApplicationContext(boolean) -> Context
                val makeCtx = loadedApk.javaClass.getDeclaredMethod(
                    "makeApplicationContext",
                    Boolean::class.javaPrimitiveType,
                )
                makeCtx.isAccessible = true
                val ctx = makeCtx.invoke(loadedApk, false) as? Context
                if (ctx != null) return ctx
                Log.w(TAG, "modulePath fallback: strategy 1 returned null; trying strategy 2")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "modulePath fallback: strategy 1 failed (" + t.message + "); trying strategy 2")
        }

        // Strategy 2: create an app Context over our APK's Resources.
        //
        // NOTE: `ContextImpl.createAppContext(ActivityThread, LoadedApk)` needs a REAL LoadedApk,
        // which strategy 1 is what produces. Passing null there yields a Context with no package
        // identity and no working resource lookups, i.e. a Context that looks non-null but fails
        // later -- the "fake success" this file tries to avoid. So strategy 2 is only attempted
        // when a LoadedApk is actually available; otherwise we report failure by name.
        try {
            val loadedApkCls = loadedApkClassOrNull(loader) ?: run {
                Log.w(TAG, "modulePath fallback: strategy 2 skipped (LoadedApk unavailable)")
                return null
            }
            val apkLoaded = loadApkWithoutCheck(activityThread, current, modulePkg, apkPath, loader)
                ?: run {
                    Log.w(TAG, "modulePath fallback: strategy 2 skipped (no LoadedApk instance)")
                    return null
                }

            val ctxImplCls = Class.forName("android.app.ContextImpl", false, loader)
            val createAppContext = ctxImplCls.getDeclaredMethod(
                "createAppContext",
                Class.forName("android.app.ActivityThread", false, loader),
                loadedApkCls,
            )
            createAppContext.isAccessible = true
            return createAppContext.invoke(null, current, apkLoaded) as? Context
        } catch (t: Throwable) {
            Log.w(TAG, "modulePath fallback: strategy 2 failed (" + t.message + ")")
            return null
        }
    }

    /**
     * The single place that turns our APK path into a `LoadedApk`.
     *
     * Kept as its own function because BOTH fallback strategies need it, and because a failure here
     * is the most likely reason the whole fallback is unavailable -- so it logs its own reason.
     */
    private fun loadApkWithoutCheck(
        activityThread: Class<*>,
        current: Any?,
        modulePkg: String,
        apkPath: String,
        loader: ClassLoader?,
    ): Any? {
        if (current == null) {
            Log.w(TAG, "modulePath fallback: currentActivityThread is null")
            return null
        }
        return try {
            val compatCls = Class.forName("android.content.res.CompatibilityInfo", false, loader)
            val defaultField = compatCls.getDeclaredField("DEFAULT_COMPATIBILITY_INFO")
            defaultField.isAccessible = true
            val compat = defaultField.get(null)
            val m = activityThread.getDeclaredMethod(
                "getPackageInfoNoCheck",
                ApplicationInfo::class.java,
                compatCls,
            )
            m.isAccessible = true
            m.invoke(current, syntheticAppInfo(modulePkg, apkPath), compat)
        } catch (t: Throwable) {
            Log.w(TAG, "modulePath fallback: getPackageInfoNoCheck failed (" + t.message + ")")
            null
        }
    }

    /**
     * A minimal ApplicationInfo pointing at OUR APK.
     *
     * Built by hand rather than read from the PackageManager, which is the entire point of the
     * fallback (see [ensureModuleContext]). `flags = 0` is correct: a Context is only read from,
     * so claiming extra capabilities would describe something we do not have.
     */
    private fun syntheticAppInfo(modulePkg: String, apkPath: String): ApplicationInfo =
        ApplicationInfo().apply {
            packageName = modulePkg
            sourceDir = apkPath
            publicSourceDir = apkPath
            flags = 0
        }

    /** `LoadedApk` is a package-private framework class; resolved separately so a miss is explained. */
    private fun loadedApkClassOrNull(loader: ClassLoader?): Class<*>? = try {
        Class.forName("android.app.LoadedApk", false, loader)
    } catch (t: Throwable) {
        Log.w(TAG, "modulePath fallback: LoadedApk not resolvable (" + t.message + ")")
        null
    }


    @Volatile
    var resolver: Resolver? = null
        private set

    @Volatile
    var inWeChatProcess = false
        private set

    /**
     * A writable directory for the module's files.
     *
     * It MUST NOT throw. The previous version called `error("context not initialised")` when appContext was
     * null, and because `workDir` is passed as an ARGUMENT its exception fired BEFORE the function body --
     * so `dexKitResolve()` never logged anything, the failure was swallowed by the caller's catch, and the
     * clone silently fell back with no menu hooks. A throwing getter on a path used for logging is a trap.
     */
    val workDir: File
        get() {
            val ctx = appContext
            val base = ctx?.filesDir
            if (base == null) {
                // Fall back to a directory that always exists and is writable.
                Log.w(TAG, "workDir: appContext is null; using the cache fallback")
                val alt = File("/data/local/tmp/luoliao")
                if (alt.exists() || alt.mkdirs()) return alt
                return File(System.getProperty("java.io.tmpdir") ?: "/tmp", "luoliao").apply { mkdirs() }
            }
            return File(base, "luoliao").apply { mkdirs() }
        }

    /** True when a real Context is available, so callers can log the reason instead of throwing. */
    val hasContext: Boolean get() = appContext != null

    fun initInWeChat(ctx: Context, resolver: Resolver) {
        appContext = ctx
        this.resolver = resolver
        inWeChatProcess = true
        Log.i(TAG, "ModuleBridge initialised in WeChat process")
    }

    fun initStandalone(ctx: Context) {
        appContext = ctx
        inWeChatProcess = false
    }
}
