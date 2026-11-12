package com.luoliaoo.wechat

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.luoliaoo.wechat.core.DebugLog
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.ModuleBridge
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.data.CardStore
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.HistoryStore
import com.luoliaoo.wechat.data.ResultCache
import com.luoliaoo.wechat.data.TemplateStore
import com.luoliaoo.wechat.hook.AnalysisController
import com.luoliaoo.wechat.hook.ChatListHook
import com.luoliaoo.wechat.hook.MenuHook
import com.luoliaoo.wechat.hook.MsgAccess
import com.luoliaoo.wechat.hook.SettingsHook
import com.luoliaoo.wechat.core.LegacyHook

/**
 * Installs every hook inside the WeChat process.
 *
 * APPLICATION ACQUISITION -- three independent paths, all feeding an idempotent
 * doInstall():
 *
 *   A) Instrumentation.callApplicationOnCreate(Application)
 *      The Application arrives as a PARAMETER, so nothing needs guessing.
 *      This is the primary path.
 *   B) Application.onCreate()  -- public and genuinely declared by Application.
 *   C) poll ActivityThread.currentApplication() until the singleton appears.
 *
 * Failure history (why A exists):
 *   - onPackageReady() runs BEFORE the Application object is created, so
 *     ActivityThread.currentApplication() returns null there.
 *   - Application does NOT declare attachBaseContext (it inherits it from
 *     ContextWrapper), so getDeclaredMethod threw NoSuchMethodException.
 */
class WeChatHooks(
    private val loader: ClassLoader,
    private val apkPath: String,
) {

    /**
     * v3.0.0: the entry the LEGACY (de.robv) registration calls.
     *
     * The module no longer receives a libxposed `LegacyHook`; hooks go through [LegacyHook], which wraps
     * `XposedBridge` the way WeKit's legacy build does. `modulePath` is the APK path the framework reported in
     * initZygote, which is what locating libdexkit.so needs.
     */
    fun installAllForLegacy(app: android.app.Application?, modulePathFromZygote: String?) {
        installAll()
        if (app != null) {
            try {
                doInstall(app)
            } catch (t: Throwable) {
                Log.w(TAG, "legacy: doInstall failed: " + t.message)
            }
        } else {
            Log.w(TAG, "legacy: no Application yet; the Activity capture path will install later")
        }
    }

    private companion object {
        const val TAG = "LuoLiao"
        const val POLL_INTERVAL_MS = 50L
        const val MAX_POLLS = 200

        @Volatile
        var installed = false

        /** v2.5.2: doInstall logs SKIP on EVERY Application capture (124 in one window). */
        private val installSkipLogged = java.util.concurrent.atomic.AtomicInteger(0)

        /** v2.5.2: rate-limits the heartbeat line; the values go to the published snapshot instead. */
        private val heartbeatLogged = java.util.concurrent.atomic.AtomicInteger(0)

    /** When the most recent install ATTEMPT began, so a worker that never entered can be retried. */
    @Volatile
    private var startingAt = 0L

    /** Set only once the worker's body has actually entered, which is the honest "started" signal. */
    @Volatile
    private var startedAt = 0L
    }

    /**
     * MultiApp: capture the Application from an ACTIVITY, for the case where `handleBindApplication`
     * already ran before the module was loaded.
     *
     * A clone may start its process in an order that differs from the normal one, so hooking
     * `handleBindApplication` can be too late. An Activity is created AFTER that point, and it always
     * carries the Application -- so installing from `Activity.onCreate` recovers the clone.
     *
     * The Activity also gives a perfectly good Context, which is what the stores need.
     */
    private fun installActivityCapture(loader: ClassLoader) {
        try {
            val activityCls = Class.forName("android.app.Activity", false, loader)
            for (name in arrayOf("onCreate", "onResume")) {
                for (m in activityCls.declaredMethods) {
                    if (m.name != name) continue
                    try {
                        LegacyHook.hook(m).intercept { chain ->
                            val self = chain.thisObject
                            if (self is android.app.Activity) {
                                val app = self.application
                                if (app != null) {
                                    if (name == "onCreate") {
                                        Log.i(TAG, "Application captured via Activity." + name)
                                    }
                                    doInstall(app)
                                }
                                // item 1: the balloon must follow the screen the user is now on.
                                //
                                // "+ -> 相册" opens a DIFFERENT Activity, which covers the chat window the
                                // balloon lives in. Without this the balloon stayed attached to a window
                                // nobody could see, and sending the image rebuilt the chat window without it.
                                if (name == "onResume") {
                                    try {
                                        com.luoliaoo.wechat.ui.FloatingPanel.followActivity(self)
                                    } catch (_: Throwable) {
                                        // best effort
                                    }
                                }
                            }
                            chain.proceed()
                        }
                        Log.i(TAG, "hooked Activity." + name + " (clone capture)")
                    } catch (t: Throwable) {
                        Log.w(TAG, "hooking Activity." + name + " failed: " + t.message)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "the Activity capture could not be armed: " + t.message)
        }
    }

    /**
     * MultiApp: capture the Application through a path that does not rely on Instrumentation.
     *
     * In an app clone the normal three capture paths can all miss, and without an Application nothing is
     * installed at all -- which is exactly what the log shows for user 999 (history paths present, no
     * hooks). `ActivityThread.handleBindApplication` runs in every application process and RECEIVES the
     * Application as a parameter, so it cannot be bypassed by a cloner.
     */
    private fun installBindApplicationHook(loader: ClassLoader) {
        val names = arrayOf(
            "android.app.ActivityThread",
        )
        for (n in names) {
            try {
                val cls = Class.forName(n, false, loader)
                for (m in cls.declaredMethods) {
                    if (m.name != "handleBindApplication") continue
                    if (m.parameterTypes.size != 1) continue
                    try {
                        LegacyHook.hook(m).intercept { chain ->
                            val arg = chain.args.firstOrNull()
                            if (arg is android.app.Application) {
                                Log.i(TAG, "Application captured via ActivityThread.handleBindApplication")
                                doInstall(arg)
                            }
                            chain.proceed()
                        }
                        Log.i(TAG, "hooked " + n + ".handleBindApplication")
                    } catch (t: Throwable) {
                        Log.w(TAG, "handleBindApplication hook failed: " + t.message)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot hook " + n + ".handleBindApplication: " + t.message)
            }
        }
    }

    fun installAll() {
        // Attach the log as early as possible: everything after this point is recorded in a file the module
        // owns, which logcat's ring buffer cannot discard.
        try {
            com.luoliaoo.wechat.core.AppContextHolder.get()?.let {
                com.luoliaoo.wechat.core.DebugLog.attach(it)
                com.luoliaoo.wechat.core.DebugLog.setEnabled(true)
            }
        } catch (_: Throwable) {
            // the app context is attached later as well
        }
        // MultiApp: this must be armed BEFORE the Application exists, because in a clone that is the only
        // path that fires.
        // item 3: build OUR module Context FIRST. A clone (another Android user) cannot read the module's
        // /data/app path, so this is the only reliable way to reach the module APK for DexKit extraction --
        // the clone's DexKit load is exactly where it stopped.
        try {
            // v7.0.1: the package name comes from BuildConfig, NOT a literal. The debug build carries an
            // `.debug` applicationIdSuffix so it can be installed ALONGSIDE the release build (which is
            // the point of having two flavours) -- and with a hardcoded literal the debug build would then
            // ask PackageManager for a package that is not itself, fail to find it, and silently lose the
            // module context (and with it DexKit extraction). Reading the field keeps both flavours correct
            // by construction.
            com.luoliaoo.wechat.core.ModuleBridge.ensureModuleContext(
                com.luoliaoo.wechat.BuildConfig.APPLICATION_ID,
                com.luoliaoo.wechat.core.ModuleBridge.appContext?.classLoader
                    ?: javaClass.classLoader,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "module context setup failed: " + t.message)
        }

        try {
            val loader = com.luoliaoo.wechat.core.ModuleBridge.appContext?.classLoader
                ?: javaClass.classLoader
                ?: ClassLoader.getSystemClassLoader()
            // Two clone-safe captures: the Application may already have been created by the time we load,
            // so BOTH are armed and whichever fires first wins (doInstall is idempotent).
            installBindApplicationHook(loader)
            installActivityCapture(loader)
        } catch (t: Throwable) {
            Log.w(TAG, "arming the clone-safe captures failed: " + t.message)
        }
        Log.i(TAG, "installAll: " + BuildInfo.BANNER)

        // Usage counting: one anonymous POST per minute so the site can show how many people are
        // using the module. Deliberately started HERE (module init, not the hook worker) so it runs
        // in both hosts and in clones. It sends no content and no identifier -- see UsageReporter's
        // header comment for the exact privacy boundary. Never throws, and never blocks startup:
        // the first request is delayed by INITIAL_DELAY_SEC and runs on its own daemon thread.
        try {
            com.luoliaoo.wechat.data.UsageReporter.start()
        } catch (t: Throwable) {
            Log.w(TAG, "usage reporter failed to start: " + t.message)
        }

        // Fast path: the Application may already exist.
        val existing = currentApplication()
        if (existing != null) {
            Log.i(TAG, "Application already available: " + existing.javaClass.name)
            doInstall(existing)
            return
        }

        // Path A (primary): receive the Application as a method parameter.
        hookInstrumentation()
        // Path B: Application.onCreate().
        hookApplicationOnCreate()
        // Path C: poll the ActivityThread singleton.
        startPolling()
    }

    /**
     * doInstall is idempotent; whichever path wins performs the real work.
     * The heavy lifting runs on a worker thread because DexKit parsing takes a
     * second or two and must not block WeChat startup.
     */
    /**
     * Starts the install work exactly once, and RECOVERS if the worker never begins.
     *
     * THE CLONE BUG: the old version set `installed = true` BEFORE starting the thread:
     *
     *     installed = true
     *     Thread({ installOnWorker(app) }, "LuoLiao-install").start()
     *
     * `installOnWorker` logs its banner as its first statement, so the clone's log -- which shows two
     * captures and then NO install banner -- proves the worker's body never ran. The capture paths fire at
     * the very start of process startup, where a thread can be killed before its first statement (the
     * clone watchdog, or a re-exec). The flag then stayed true forever, so no later capture could retry and
     * the module was inert for the life of the process. On user 0 the thread usually survived, which is why
     * only the clone failed.
     *
     * Now the claim is TIME-BOUNDED: a worker that has not entered within a few seconds is retried, and a
     * worker that throws releases the claim.
     */
    private fun doInstall(app: Application) {
        val now = System.currentTimeMillis()

        if (installed) {
            // v2.5.2: rate-limited; the install state is in the published snapshot.
            if (installSkipLogged.incrementAndGet() <= 5) Log.i(
                TAG,
                "doInstall: SKIP, already installed (installed=true startedAt=" + startedAt +
                    " balloons=" + com.luoliaoo.wechat.core.ModuleStats.balloons + ")",
            )
            return
        }

        // Another attempt started very recently: do not spawn a duplicate.
        if (startingAt > 0 && now - startingAt < INSTALL_RETRY_MS) {
            // v2.5.2: rate-limited; the install state is in the published snapshot.
            if (installSkipLogged.incrementAndGet() <= 5) Log.i(
                TAG,
                "doInstall: SKIP, an attempt started " + (now - startingAt) +
                    "ms ago (installed=false startingAt=" + startingAt + ")",
            )
            return
        }

        startingAt = now
        Log.i(TAG, "install: starting the worker (attempt at " + now + ")")

        val t = Thread({
            try {
                // Mark the claim as REAL only now, so a worker that never enters cannot block a retry.
                startedAt = System.currentTimeMillis()
                installOnWorker(app)
                installed = true
                // v2.5.2: mirror the flag where the published snapshot can see it.
                com.luoliaoo.wechat.core.ModuleStats.moduleInstalled = true
                Log.i(
                    TAG,
                    "install: worker COMPLETED (user=" + android.os.Process.myUid() / 100000 +
                        " took=" + (System.currentTimeMillis() - startedAt) + "ms)",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "installOnWorker failed", t)
                // Release the claim so a later capture can try again.
                startingAt = 0L
                startedAt = 0L
            }
        }, "LuoLiao-install")
        t.isDaemon = true
        try {
            t.start()
        } catch (e: Throwable) {
            Log.e(TAG, "install: could not start the worker: " + e.message)
            startingAt = 0L
            return
        }

        // Watchdogs: if the worker never entered, retry on the main thread. Several attempts at growing
        // delays, because a single one may fall inside the same hostile early-start window that killed the
        // first worker. Every attempt is a no-op once the install has completed.
        scheduleRetries(app, 0)
    }

    /** item: retries the install a few times, at growing delays, until it has completed. */
    private fun scheduleRetries(app: Application, index: Int) {
        val delays = longArrayOf(3000L, 8000L, 20000L)
        if (index >= delays.size) {
            if (!installed) {
                Log.e(TAG, "install: gave up after " + delays.size + " retries (installed=false)")
            }
            return
        }
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (installed) return@postDelayed
                if (startedAt != 0L) return@postDelayed
                Log.w(
                    TAG,
                    "install: the worker did not start (retry " + (index + 1) + "/" + delays.size + ")",
                )
                startingAt = 0L
                doInstall(app)
                scheduleRetries(app, index + 1)
            }, delays[index])
        } catch (_: Throwable) {
            // the retries are best effort
        }
    }

    /** How long to wait before concluding that an install attempt never began. */
    private val INSTALL_RETRY_MS = 3000L

    /**
     * A heartbeat, so an exported log always shows whether this process is ALIVE and still hooked.
     *
     * WHY: the clone's sessions in the log are tiny and show no activity after installation. Without a
     * heartbeat it is impossible to tell "the process died" from "the user never opened the app". This line
     * resolves that: if heartbeats continue, the module is alive and the missing `setAdapter called` means
     * the UI is simply not being used.
     */
    private fun startHeartbeat() {
        try {
            val user = android.os.Process.myUid() / 100000
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
                override fun run() {
                    try {
                        com.luoliaoo.wechat.core.ModuleStats.noteHeartbeat()
                        // v2.5.2: publish the counters so the module's process can READ them, and keep the
                        // heartbeat itself quiet unless something changed -- it ran every 30s for the whole session.
                        com.luoliaoo.wechat.core.ModuleStats.publish()
                        // v2.5.2: the heartbeat ran every 30s for the whole session (160 lines in one window).
                        // The counters are PUBLISHED instead, and only the first few lines are logged.
                        if (heartbeatLogged.incrementAndGet() <= 4) Log.i(TAG, "HEARTBEAT: user=" + user +
                                " pid=" + android.os.Process.myPid() +
                                " installed=" + installed +
                                " adapters=" + com.luoliaoo.wechat.core.ModuleStats.adapters +
                                " balloons=" + com.luoliaoo.wechat.core.ModuleStats.balloons +
                                " menuAttempts=" + com.luoliaoo.wechat.core.ModuleStats.menuAttempts +
                                " menuInjected=" + com.luoliaoo.wechat.core.ModuleStats.menuInjected,
                        )
                    } catch (_: Throwable) {
                        // never let the heartbeat break anything
                    }
                    try {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this, 30000L)
                    } catch (_: Throwable) {
                        // best effort
                    }
                }
            }, 30000L)
            Log.i(TAG, "heartbeat started (every 30s, user=" + user + ")")
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat could not start: " + t.message)
        }
    }


    private fun installOnWorker(app: Application) {
        // THE LOG FILE MUST EXIST BEFORE ANYTHING IS LOGGED.
        //
        // It used to be attached at the END of the install path, so every line the install produced went only
        // to logcat -- where it was lost -- and the module's own file began after the interesting part. This
        // is the same ordering mistake as the earlier workDir bug: the thing that records the evidence was
        // set up after the code whose evidence we needed.
        try {
            com.luoliaoo.wechat.core.DebugLog.attach(app)
            com.luoliaoo.wechat.core.DebugLog.setEnabled(true)
            Log.i(TAG, "=== installing hooks (" + BuildInfo.BUILD_ID + ")" +
                " user=" + android.os.Process.myUid() / 100000 +
                " logFile=" + (com.luoliaoo.wechat.core.DebugLog.file()?.absolutePath ?: "none"))
        } catch (t: Throwable) {
            Log.i(TAG, "=== installing hooks (" + BuildInfo.BUILD_ID + ") [log file unavailable]")
        }
        Log.i(TAG, "=== Application=" + app.javaClass.name)

        // v2.6.0: resolve the host class loader FIRST, the way WeKit does.
        //
        // Every later step (DexKit results, Class.forName, the menu hook) depends on the loader carrying
        // WeChat's own classes. Getting this wrong is invisible until the button fails to appear.
        try {
            com.luoliaoo.wechat.core.ModuleBridge.rememberHostLoader(app)
        } catch (t: Throwable) {
            Log.w(TAG, "rememberHostLoader failed: " + t.message)
        }
        // v3.0.0: `xposed.moduleApplicationInfo` was a libxposed API. The de.robv path has no equivalent, so
        // the module's ApplicationInfo is read from the module Context, which the module already builds.
        ModuleBridge.moduleAppInfo = try {
            apkPath.let { java.io.File(it) }.let { f ->
                if (f.isFile) {
                    val pm = app.packageManager
                    pm.getPackageArchiveInfo(f.absolutePath, 0)?.applicationInfo
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }

        // v2.5.2: give the counters somewhere to publish.
        //
        // The counters are incremented HERE (WeChat's process), but the debug screen runs in the MODULE's
        // process, where every counter is 0 -- which is exactly why the self-check showed all zeros while
        // WeChat's own log showed adapters=8 menuAttempts=1.
        try {
            com.luoliaoo.wechat.core.ModuleStats.attachSnapshot(app)
        } catch (_: Throwable) {
            // publishing is best effort
        }

        val resolver = Resolver(loader, apkPath, app.filesDir)

        // THE CLONE FIX: initialise the bridge BEFORE resolving.
        //
        // `Resolver.dexKitResolve()` reads `ModuleBridge.workDir`, which needs `appContext`. This call used
        // to come AFTER resolve(), so during resolution appContext was null, workDir threw while being
        // evaluated as an argument, and the exception escaped into the caller's catch -- silently. The
        // module then fell back with no string-anchor resolution and no menu hooks, which is precisely the
        // clone's log (it goes from "=== Application=..." straight to "contributor classes loaded" with no
        // dexkit line at all). A fresh clone process always hits this; the original process sometimes had
        // appContext already set, which is why user 0 usually worked and 999 never did.
        ModuleBridge.initInWeChat(app, resolver)

        // The resolution is guarded so a failure cannot leave the worker silent: the clone's log showed
        // `dexKitResolve: entering` and then NOTHING, which left the module installed but with no menu hook
        // and no evidence of why.
        val points = try {
            // 5.0.0 HOST GATE: DexKit resolution maps the HOST's dex looking for the WECHAT anchor
            // strings built into Resolver (`com.tencent.mm.ui.chatting.*`). Inside Douyin that means
            // scanning 473 MB of dexes to find nothing, and only returning when the 12 s timeout
            // expires. Skipping it on a non-WeChat host is not an optimisation -- it is the difference
            // between a ~12-second and a near-instant Douyin startup, with the same fallback result
            // either way. `fallbackForGuard()` is exactly what the timeout would have produced.
            if (com.luoliaoo.wechat.core.HostApp.current == com.luoliaoo.wechat.core.HostApp.WECHAT) {
                // item (clone): bounded resolution. DexKit maps WeChat's whole APK, which in a CLONE
                // (another android user) did not return at all -- that blocked the install and left no
                // menu hook. A time limit guarantees the install completes, using the fallback when
                // DexKit cannot deliver.
                resolver.resolveWithTimeout(12000L)
            } else {
                Log.i(
                    TAG,
                    "host=" + com.luoliaoo.wechat.core.HostApp.current.name +
                        ": DexKit resolution skipped (WeChat anchors do not apply here)",
                )
                resolver.fallbackForGuard()
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "resolve() THREW: " + t.javaClass.simpleName + ": " + t.message +
                    " -- hook installation continues, DexKit is optional",
            )
            resolver.fallbackForGuard()
        }
        Log.i(
            TAG,
            "hook point resolution: status=" + points.status +
                " user=" + android.os.Process.myUid() / 100000,
        )
        Log.i(TAG, "hook point detail: " + points.detail)
        ResultCache.attach(ModuleBridge.workDir)
        // Card state is persisted next to the cache so cards survive a restart (item 7).
        CardStore.attach(ModuleBridge.workDir)
        // Local record of everything read, so a scope is not limited to what the
        // adapter currently holds (item 3).
        // item 1: the history store also receives OUR context, so it can store under
        // /sdcard/Android/data/com.luoliaoo.wechat/ rather than inside WeChat's own data directory,
        // which WeChat and the system both clear -- the reason records vanished on restart.
        HistoryStore.attach(app)
        // item 3: the scope panel picks a template in THIS process, so the store is attached here too.
        // It lives beside the config, because a picked template writes to the config this process reads.
        TemplateStore.attach(ModuleBridge.workDir)
        // v4.0.0: the TEXT-POLISH templates live in a separate store, and the 文本优化 dialog runs in THIS
        // process -- without this attach the store would fall back to its two shipped defaults and every
        // template the user added or renamed would silently be missing on the next long press.
        com.luoliaoo.wechat.data.PolishTemplateStore.attach(ModuleBridge.workDir)
        // v4.1.0: the per-template Skill store. It lives beside the templates, because a Skill is filed
        // under its template's name and the 编辑模板 page edits both in the same dialog.
        com.luoliaoo.wechat.data.SkillStore.attach(ModuleBridge.workDir)
        Log.i(TAG, "history location: " + HistoryStore.location())

        val access = MsgAccess(resolver)
        val controller = AnalysisController(app, resolver, access)
        AnalysisController.setInstance(controller)
        // Rehydrate previously analysed cards; reconcile draws them when a chat is opened.
        // item 2: the 2-second live-history timer (one task, writes only what is new).
        safely("live history timer") { controller.startLiveTimer() }
        safely("restore cards") { controller.restoreFromDisk() }

        // item 1: the balloon is global, so it is brought up as soon as the module is installed --
        // no analysis, no chat screen, nothing else required.
        //
        // 5.0.0: THIS IS THE HOST-INDEPENDENT HALF of the module. FloatingPanel, the text-polish
        // dialog and the AI card stack touch NO WeChat class: FloatingPanel imports only core/
        // Constants, OwnUi, Log and the data/ stores (see its import block). That is why the balloon
        // can be raised on Douyin on day one, before any Douyin message hook exists, and why it is
        // raised here -- OUTSIDE the WeChat-only block below.
        safely("floating balloon") {
            val ctx = ModuleBridge.appContext
            if (ctx != null) {
                com.luoliaoo.wechat.ui.FloatingPanel.showGlobal(ctx)
                Log.i(TAG, "floating: balloon requested at startup")
            }
        }

        // ---- Host-specific hooks --------------------------------------------------------------
        //
        // Everything in the WeChat branch resolves classes by WeChat's obfuscated names
        // (`com.tencent.mm.*`), so it is meaningless in another host: each step would burn its
        // retries and log a failure for a class that cannot exist. They are gated rather than
        // deleted so the WeChat path is byte-for-byte what it was in 4.1.0.
        //
        // v6.0.0: THIS IS NOW AN EXPLICIT per-host DISPATCH, and that change is a BUG FIX, not
        // tidying. The old shape was `if (WeChat) { ... } else { DouyinHooks(...) }` -- a
        // two-host assumption hiding in an `else`. Once a THIRD host existed, `HostApp.of` returned
        // SOUL (which is `isSupported`) and Soul fell straight into the Douyin branch, so
        // `DouyinHooks.install()` ran inside Soul with no host guard at all: it probed
        // `com.bytedance.im.core.model.*` through Soul's classloader (every lookup null) and, worse,
        // would have injected Douyin's MULTI-SELECT hook into Soul -- a direct breach of the
        // requirement that Soul gets no multi-select adaptation, and a real host-crash risk given
        // the LayoutParams findings recorded in `_tools/DOUYIN_MENU_CRASH_P0.md`.
        //
        // An `else` is the wrong shape for a set of hosts that keeps growing: each new host silently
        // inherits whichever host happened to be last. Every branch now names its host explicitly,
        // and an unhandled host falls through to a LOGGED no-op instead of borrowing another app's
        // hooks.
        //
        // v7.0.0: the FOURTH host (快手 / com.smile.gifmaker) was added here, and it is the first
        // real test of that shape. Every enum constant now has an arm above, so the compiler's
        // non-exhaustive-`when` warning for `HostApp.KUAISHOU` -- present the moment the constant was
        // added, while this arm did not exist yet -- is gone. The warning is the signal: it is what
        // tells the next person adding a host that a branch is still missing, before the host ever
        // reaches a device and silently installs nothing.
        when (com.luoliaoo.wechat.core.HostApp.current) {

            com.luoliaoo.wechat.core.HostApp.WECHAT -> {
                safely("menu injection") { MenuHook(resolver, controller).install() }
                safely("card insertion") {
                    val clh = ChatListHook(controller)
                    clh.install()
                    // The setAdapter hook never fired on WeChat 8.0.77, so adapters are ALSO discovered
                    // from the view tree, which needs no hook at all. This is what makes the module
                    // version-proof here.
                    clh.startAdapterWatchdog()
                }
                safely("settings entry") { SettingsHook(loader).install() }

                // ==================================================== v10.0.0 语音转文字
                //
                // 两个 install 是**独立的**，放在两个 `safely` 里，理由与本文件其它地方一致：
                // 它们的失败原因完全不同，一个块里的异常会让另一件事也做不成。
                //
                //   1. 数据库句柄采集：hook WCDB 的 rawQuery，把微信**已经打开的** EnMicroMsg.db
                //      句柄弱引用存下来。它只依赖 com.tencent.wcdb.*（一个库的类名，很稳定），
                //      与转写引擎无关 —— 就算引擎定位失败，它也能独立工作。
                //
                //   2. 转写引擎连接：DexKit 扫锚点定位 TransformComponent / ChattingContext。
                //      这一步要用 DexKit 扫 278MB 的 APK，比第 1 步慢得多、也更可能失败
                //      （微信换版本把锚点字符串改了就失效）。
                //
                // 顺序上先装 1 再装 2：句柄采集是纯 hook，立刻完成；DexKit 扫描耗时较长，
                // 让它排在后面，不拖延前者的生效时间。
                //
                // 两者都必须传**宿主的 ClassLoader**（`resolver.classLoader()`），
                // 绝不能用模块自己的 loader —— 那会拿到另一个同名 Class，是必看.md 记过的坑。
                safely("voice db handles") {
                    com.luoliaoo.wechat.voice.WeChatVoiceDb.install(resolver.classLoader())
                }
                safely("voice engine") {
                    com.luoliaoo.wechat.voice.WeChatVoice.install(
                        resolver.apkPathForVoice(),
                        resolver.classLoader(),
                    )
                }
            }

            com.luoliaoo.wechat.core.HostApp.DOUYIN -> {
                Log.i(
                    TAG,
                    "host=DOUYIN: WeChat-only hooks (menu / card / settings) skipped",
                )
                // 5.0.0: Douyin's message menu + message reader are installed by DouyinHooks. It is
                // deliberately called through `safely` and must never throw: a Douyin internal rename
                // would otherwise take the balloon down with it.
                safely("douyin hooks") {
                    com.luoliaoo.wechat.hook.DouyinHooks(loader, resolver, controller).install()
                }
            }

            com.luoliaoo.wechat.core.HostApp.SOUL -> {
                Log.i(
                    TAG,
                    "host=SOUL: WeChat-only hooks (menu / card / settings) skipped; " +
                        "no multi-select is installed for Soul (it has no such feature)",
                )
                // v6.0.0: Soul's own hooks, split into TWO independent classes so that two people (or
                // two future work sessions) never have to edit one file:
                //
                //   SoulHooks        -- the long-press menu entry (the analyze item + its icon)
                //   SoulHistoryHooks -- automatic chat-history capture
                //
                // Each is installed in its OWN `safely` block, so a failure in one cannot take the
                // other down -- the same reason DouyinHooks is wrapped. The two are ordered only for
                // log readability; neither depends on the other.
                //
                // v6.0.0 FINAL: DIRECT references, not the reflective bridge this arm used while the
                // two files were being written. The bridge was a deliberate, temporary scaffold, and
                // it was retired on purpose rather than left in place, because reflection defeats the
                // compiler exactly where it matters: a one-character name typo
                // (`SoulHistoryHook` vs `SoulHistoryHooks`) compiled cleanly and returned null at
                // runtime, which shows up on a device as "the class is not present in this build"
                // while the file IS present -- a log line that actively misleads. The verifier caught
                // that live. With direct references the same typo is a COMPILE ERROR, which is the
                // whole point: this is a silently-failing area, so the strongest available check
                // belongs here.
                safely("soul menu hooks") {
                    com.luoliaoo.wechat.hook.SoulHooks(loader, resolver, controller).install()
                }
                safely("soul history hooks") {
                    com.luoliaoo.wechat.hook.SoulHistoryHooks(loader, resolver, controller).install()
                }
            }

            com.luoliaoo.wechat.core.HostApp.KUAISHOU -> {
                Log.i(
                    TAG,
                    "host=KUAISHOU: WeChat-only hooks (menu / card / settings) skipped",
                )
                // v7.0.0: Kuaishou is the FOURTH host and gets its OWN explicit branch. This is the
                // whole point of the exhaustive `when` above: an `else` (or a shared Douyin arm) would
                // silently run another app's class-name probes and multi-select hook inside Kuaishou,
                // which is exactly the bug the SOUL arm above records -- Soul inherited DouyinHooks
                // and would have had Douyin's MULTI-SELECT injected into it.
                //
                // RESERVED CALL POINT. Kuaishou's own hooks are being written in a separate class,
                // `com.luoliaoo.wechat.hook.KuaishouHooks`, whose entry point is `install()`. The
                // block below is where it is called from, and it is deliberately EMPTY in this build:
                // the class does not exist yet, and a direct reference to a class that is not there
                // is a COMPILE error, so an empty `safely` is the only shape that both reserves the
                // site and keeps this build green.
                //
                // The `safely` wrapper is kept for the same reason DouyinHooks and the two Soul
                // classes use it: a Kuaishou internal rename must degrade ONE feature, never take the
                // floating balloon down with it. When the class lands, the body becomes
                //
                //     com.luoliaoo.wechat.hook.KuaishouHooks(loader, resolver, controller).install()
                //
                // and it must be a DIRECT reference, never `Class.forName`. That is not style: the
                // SOUL arm records a one-character typo (`SoulHistoryHook` vs `SoulHistoryHooks`) that
                // compiled cleanly through a reflective bridge and returned null at runtime, showing
                // up on a device as "the class is not present in this build" while the file WAS
                // present -- a log line that actively misleads. Direct references turn that typo into
                // a compile error, which is the strongest check available in a silently-failing area.
                safely("kuaishou hooks") {
                    com.luoliaoo.wechat.hook.KuaishouHooks(loader, resolver, controller).install()
                }
            }

            com.luoliaoo.wechat.core.HostApp.QQ -> {
                Log.i(
                    TAG,
                    "host=QQ: WeChat-only hooks (menu / card / settings) skipped",
                )
                // v8.0.0: QQ (com.tencent.mobileqq) is the FIFTH host and gets its OWN explicit branch,
                // for the reason the KUAISHOU arm below spells out: a shared or `else` arm would run
                // another app's class-name probes inside QQ.
                //
                // QQ IS NOT A SECOND WECHAT, even though both are Tencent apps and both package names
                // start with `com.tencent.`. QQ 9.3.65 runs its chat page on its own NT stack
                // (`com.tencent.qqnt.*`), NOT on the classic `com.tencent.mobileqq.data.MessageRecord`
                // model that WeChat-style hooks would look for. The classic model survives in this APK
                // only as 36 residual references across 41 dex files, against 21,674 for
                // `com/tencent/qqnt/kernel`. So the WeChat menu/card/settings hooks above are skipped
                // deliberately, and a QQ reader must be written against the NT model.
                //
                // RESERVED CALL POINT, now WIRED (v8.0.0): the QQ hook class landed, so the
                // placeholder below is replaced by a DIRECT reference.
                //
                // It must be a DIRECT reference, never `Class.forName` -- the SOUL arm above
                // records the one-character typo that compiled cleanly through a reflective bridge
                // and returned null at runtime, showing up on a device as "the class is not present
                // in this build" while the file WAS present. A direct reference turns that same
                // typo into a COMPILE error, which is where it belongs.
                safely("qq hooks") {
                    com.luoliaoo.wechat.hook.QQHooks(loader, resolver, controller).install()
                }

                // THE HISTORY FEEDER, and it is a SEPARATE install from the menu hooks on purpose.
                //
                // The menu row and the history are independent features that fail independently: the
                // feeder hooks the message-list binder while the menu hooks the long-press menu. In
                // one `safely` block a throw from either would disable both, and the user would lose
                // history because a menu class was renamed.
                //
                // The 2-second flush timer is NOT started here: `startLiveTimer` already runs for
                // every host at WeChatHooks.kt:541, BEFORE the host-specific section. Without this
                // feeder the timer wakes every 2s and finds nothing staged, which is exactly the
                // state the user reported as "history does not get written".
                safely("qq history feed") {
                    com.luoliaoo.wechat.hook.QQHistoryHooks(loader, resolver, controller).install()
                }
            }

            com.luoliaoo.wechat.core.HostApp.XIAOHONGSHU -> {
                Log.i(
                    TAG,
                    "host=XIAOHONGSHU: WeChat-only hooks (menu / card / settings) skipped",
                )
                // v9.3.0: Xiaohongshu (小红书 / com.xingin.xhs) is the SIXTH host and gets its OWN
                // explicit branch, for the reason the KUAISHOU and QQ arms above spell out: a shared or
                // `else` arm would run another app's class-name probes inside it.
                //
                // WHAT IS DELIBERATELY ABSENT HERE: MULTI-SELECT. The user confirmed
                // 「小红书没有多选 所以无需适配」, so there is no toolbar injection and no batch path.
                // QQ's `QQMenuSelection`/`QQRowRegistry`/multi-select toolbar machinery must NOT be
                // reused for this host -- it would install hooks for a feature the host does not have,
                // and its row registry would sit empty forever the way QQ's did before it was fixed.
                //
                // TWO SEPARATE `safely` BLOCKS, for the reason the QQ arm records: the menu entry and
                // the history feeder fail independently, and one block would let a renamed menu class
                // take the history down with it.
                //
                // Both must be DIRECT references, never `Class.forName` -- the SOUL arm above records
                // the one-character typo that compiled cleanly through a reflective bridge and returned
                // null at runtime, showing up on a device as "the class is not present in this build"
                // while the file WAS present.
                safely("xiaohongshu hooks") {
                    com.luoliaoo.wechat.hook.XiaohongshuHooks(loader, resolver, controller).install()
                }

                // The history feeder. The 2-second flush timer is NOT started here: `startLiveTimer`
                // already runs for every host at WeChatHooks.kt:541, BEFORE this host-specific section.
                // Without this feeder the timer wakes every 2s and finds nothing staged, which is
                // exactly the state the user reported on QQ as "only the analysed message is stored".
                safely("xiaohongshu history feed") {
                    com.luoliaoo.wechat.hook.XiaohongshuHistoryHooks(loader, resolver, controller)
                        .install()
                }
            }

            com.luoliaoo.wechat.core.HostApp.UNKNOWN -> {
                // Unreachable in practice -- ModuleEntry refuses an unsupported host before this
                // method runs. Kept explicit so the `when` proves it covers every host constant and a
                // future host added without a branch becomes a compile error rather than a silent
                // fall-through into someone else's hooks.
                Log.w(TAG, "host=UNKNOWN reached installOnWorker; installing nothing")
            }
        }

        // The module's own debug log: attach it and honour the switch, so troubleshooting does not depend
        // on exporting the LSPosed log.
        safely("debug log") {
            DebugLog.attach(app)
            // Do not let the stored preference turn it OFF: during a diagnosis the file is the only reliable
            // channel, and the setting defaults to on.
            DebugLog.setEnabled(true)
        }
        if (ConfigRepo.loadLocal(app).debugLog) {
            Log.i(TAG, "debug log: ON in this process (user=" + android.os.Process.myUid() / 100000 + ")")
        }

        // A heartbeat, so an exported log always shows whether this process is alive and hooked.
        safely("heartbeat") { startHeartbeat() }

        Log.i(TAG, "=== all hook installation steps finished")
    }

    private inline fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, name + " install FAILED", t)
        }
    }

    /**
     * Path A: hook Instrumentation.callApplicationOnCreate(Application).
     * The Application is passed in, so no reflection guessing is involved.
     */
    private fun hookInstrumentation() {
        try {
            val cls = Class.forName("android.app.Instrumentation", false, loader)
            val m = cls.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
            Log.i(TAG, "hooking Instrumentation.callApplicationOnCreate")

            val main = Handler(Looper.getMainLooper())
            LegacyHook.hook(m)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val app = chain.getArg(0)
                    val result = chain.proceed()
                    if (app is Application) {
                        Log.i(TAG, "Application captured via Instrumentation: " + app.javaClass.name)
                        main.post { doInstall(app) }
                    }
                    result
                }
            Log.i(TAG, "Instrumentation hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "hook Instrumentation failed", t)
        }
    }

    /** Path B: Application.onCreate() is public and declared by Application. */
    private fun hookApplicationOnCreate() {
        try {
            val appCls = Class.forName("android.app.Application", false, loader)
            val m = appCls.getDeclaredMethod("onCreate")
            Log.i(TAG, "hooking Application.onCreate")

            val main = Handler(Looper.getMainLooper())
            LegacyHook.hook(m)
                .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject
                    if (self is Application) {
                        Log.i(TAG, "Application captured via onCreate: " + self.javaClass.name)
                        main.post { doInstall(self) }
                    }
                    result
                }
            Log.i(TAG, "Application.onCreate hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "hook Application.onCreate failed", t)
        }
    }

    /** Path C: poll for the Application singleton. */
    private fun startPolling() {
        val main = Handler(Looper.getMainLooper())
        val task = object : Runnable {
            private var attempt = 0

            override fun run() {
                if (installed) return
                val app = currentApplication()
                if (app != null) {
                    Log.i(TAG, "Application found by polling (attempt " + attempt + ")")
                    doInstall(app)
                    return
                }
                attempt++
                if (attempt < MAX_POLLS) {
                    main.postDelayed(this, POLL_INTERVAL_MS)
                } else {
                    Log.w(TAG, "gave up polling for Application after " + attempt + " attempts")
                }
            }
        }
        main.postDelayed(task, POLL_INTERVAL_MS)
    }

    private fun currentApplication(): Application? = try {
        val at = Class.forName("android.app.ActivityThread", false, loader)
        val current = at.getDeclaredMethod("currentApplication").invoke(null)
        current as? Application
    } catch (t: Throwable) {
        null
    }
}
