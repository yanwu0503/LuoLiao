package com.luoliaoo.wechat.core

import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier

/**
 * Hook point resolution -- core of approach A, resilient to obfuscation.
 *
 * DESIGN: use ONE highly stable string as an anchor, then derive everything else
 * by walking the type graph via reflection. Even if WeChat renames every
 * obfuscated class, the chain can be rebuilt while that log string survives.
 *
 * Chain (verified against the real 8.0.77 APK; see
 * _research/HOOKPOINTS_8.0.77_VERIFIED.md):
 *
 *   anchor "OnCreateContextMMMenux: position:%s, msgid:%s"
 *     -> [menu producer]         (8.0.77 = ui.chatting.viewitems.n0)
 *          ctor params            -> [long-click entry] (viewitems.r0)
 *          ContextMenu param       -> [menu]             (nf5.g4)
 *   [long-click entry] a(r0, menu, X, int) param 3 -> [message] (storage.e9)
 *
 * Each layer is structurally validated; any failure degrades to the version table.
 */
class Resolver(
    private val loader: ClassLoader,
    private val apkPath: String,
    private val workDir: File,
) {

    enum class Status {
        DEXKIT,
        FALLBACK,

        /**
         * v7.0.0: resolution was deliberately NOT attempted for this host.
         *
         * Distinct from [FAILED] on purpose. On Douyin/Soul/Kuaishou the resolver skips the WeChat lookup by
         * design (the WeChat anchor strings and the `FALLBACK_*` class names cannot exist there), so every
         * hook point legitimately comes back null. Lumping that into [FAILED] makes an expected outcome look
         * like a defect: a Kuaishou log reading `status=FAILED` invites a hunt for a WeChat bug that does not
         * exist. Only the WECHAT host can actually FAIL.
         */
        SKIPPED,

        FAILED,
    }

    data class HookPoints(
        val menuClass: Class<*>?,
        val longClickClass: Class<*>?,
        val msgClass: Class<*>?,
        val producerClass: Class<*>?,
        val status: Status,
        val detail: String,
    )

    private companion object {
        const val TAG = "LuoLiao"

        // Anchor string: a WeChat log line, far more stable than class names.
        const val ANCHOR_MENU = "OnCreateContextMMMenux: position:%s, msgid:%s"

        // Verified obfuscated names for 8.0.77 (versionCode 3160), as fallback.
        const val FALLBACK_PRODUCER = "com.tencent.mm.ui.chatting.viewitems.n0"
        const val FALLBACK_LONGCLICK = "com.tencent.mm.ui.chatting.viewitems.r0"
        const val FALLBACK_LONGCLICK2 = "com.tencent.mm.ui.chatting.adapter.d0"
        /**
     * The fallback menu class, used ONLY if the string-anchor search fails.
     *
     * MEASURED: `nf5.g4` does NOT exist in WeChat 8.0.68 (versionCode 3020) -- it was present in 8.0.77.
     * A stale name here is dangerous because it fails SILENTLY, so the fallback is no longer trusted on its
     * own: it is tried, and if the class is absent the module logs that clearly and keeps looking by
     * SUPERCLASS instead (ContextMenu is a public Android type and cannot be obfuscated away).
     */
    const val FALLBACK_MENU = "nf5.g4"
        const val FALLBACK_MSG = "com.tencent.mm.storage.e9"
    }

    @Volatile
    var points: HookPoints? = null
        private set

    /**
     * v2.6.0: the HOST loader when one was recorded, otherwise the one passed in.
     *
     * WHY: WeKit resolves host classes through `application.baseContext.classLoader`. When this module used a
     * loader that cannot see the chat classes, DexKit still reported candidates and every `Class.forName`
     * failed -- silently. The host loader is therefore preferred.
     */
    fun classLoader(): ClassLoader = ModuleBridge.hostClassLoader ?: loader

    /**
     * ★ v10.0.0：把宿主 APK 的路径暴露给语音转写模块。
     *
     * 【为什么加这个访问器，而不是让 WeChatVoice 自己找 APK】
     * `apkPath` 是构造参数（私有字段），而它**必须**是 Resolver 收到的那一个 ——
     * 那一个已经在本项目里被验证过是可用的（`DexKitBridge.create(apkPath)` 在微信 8.0.77 上
     * 成功扫出过菜单锚点）。让 WeChatVoice 自己用 `applicationInfo.sourceDir` 再算一遍，
     * 就是给"同一个东西两处来源"，将来一处变了另一处不知道。
     *
     * 【留意】DexKit 扫一次这个 APK 是**昂贵**操作（278MB / 17 个 dex），
     * 所以调用方（WeChatHooks）只调用一次、且放在启动流程里做，不在热路径上。
     */
    fun apkPathForVoice(): String = apkPath

    /**
     * item 1 (v2.2.0): every class that contributes items to the long-press menu.
     *
     * NUKE'S POPULATION: WeChat calls `onCreateMMMenu(<menu>)` on ~747 classes (33 chat-related) while it
     * builds the context menu. Hooking those means the item is added by WeChat's OWN pass, which is why
     * Nuke's item always appears.
     *
     * Found by SHAPE, so no obfuscated name is involved and every WeChat version works:
     * `findClass` with a name matcher on the method plus a parameter check against the resolved menu class.
     *
     * Falls back to a bounded reflection scan of the loaded theme packages when DexKit is unavailable, so
     * the mechanism still works in a clone where DexKit is limited.
     */
    fun contributorClasses(menuCls: Class<*>): List<Class<*>> {
        val loader = classLoader()

        // ---- 1: DexKit, by shape
        val out = LinkedHashSet<Class<*>>()
        try {
            val wd = ModuleBridge.workDir
            if (SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
                val apk = File(apkPath)
                if (apk.isFile) {
                    DexKitBridge.create(apkPath).use { bridge ->
                        // item 1 (v2.3.0): SEARCH BY METHOD NAME -- Nuke's `.MethodOnCreateMenu`.
                        //
                        // The old call was `findClass { matcher { usingStrings("onCreateMMMenu") } }`, which
                        // matches classes whose CODE REFERENCES the string. The classes that DECLARE the
                        // method do not contain it, so that search returned the DISPATCHERS (14) instead of
                        // the 747 declaring classes -- and only 13 survived the parameter check. The button
                        // never appeared because the framework never called any class this module had hooked.
                        //
                        // `findMethod` with a name matcher returns the DECLARING classes, which is the
                        // population WeChat actually calls.
                        val found = bridge.findMethod {
                            searchPackages("com.tencent.mm")
                            matcher { name("onCreateMMMenu") }
                        }
                        Log.i(TAG, "contributorClasses: methods DECLARING onCreateMMMenu=" + found.size)

                        for (f in found) {
                            val owner = try {
                                f.className
                            } catch (_: Throwable) {
                                null
                            } ?: continue
                            val c = loadClass(owner) ?: continue
                            if (declaresMenuContributor(c, menuCls)) out.add(c)
                        }
                        Log.i(TAG, "contributorClasses: usable chat contributors=" + out.size)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "contributorClasses: dexkit path failed: " + t.message)
        }

        // ---- 2: reflection over the packages WeChat keeps for chat UI
        if (out.isEmpty()) {
            val pkgs = arrayOf(
                "com.tencent.mm.ui.chatting.viewitems",
                "com.tencent.mm.ui.chatting.component",
                "com.tencent.mm.ui.chatting",
                "com.tencent.mm.chatroom.ui",
            )
            for (pkg in pkgs) {
                for (c in classesInPackage(pkg, loader)) {
                    if (declaresMenuContributor(c, menuCls)) out.add(c)
                }
            }
            Log.i(TAG, "contributorClasses: reflection candidates=" + out.size)
        }
        return out.toList()
    }

    /**
     * v2.4.1: the message CLASS, resolved WeKit's way -- two strings, no method name.
     *
     *     val classMsgInfo by dexClass {
     *         searchPackages("com.tencent.mm.storage")
     *         matcher { usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]") }
     *     }
     *
     * The module previously identified a message by a method named `getMsgId`, which is obfuscated per
     * version -- the reason `extractMessage` returned null and the UI said 未能识别该消息. The CLASS is
     * version-proof, so any object assignable to it IS a message.
     */
    fun msgInfoClass(): Class<*>? {
        cachedMsgClass?.let { return it }
        // v7.0.0 HOST GUARD (found by ks-reviewer).
        //
        // This is the SECOND DexKit entry point, and the guard used to sit only in `loadClass`. That
        // left a hole with a real cost: the entry below scans `searchPackages("com.tencent.mm.storage")`
        // -- a WeChat package name -- against whatever APK this process is hosted by.
        //
        // WHY THE GUARD BELONGS HERE AND NOT ONLY IN `loadClass`:
        // `dexKitResolve()` is skipped on a non-WeChat host for a documented reason (scanning a huge
        // foreign APK finds nothing and can burn ~12s). But `dexKitResolve` is protected by a TIMEOUT
        // wrapper, whereas this function calls `DexKitBridge.create(apkPath)` DIRECTLY, with no timeout
        // at all. So on Kuaishou/Douyin/Soul this path would re-introduce exactly the cost the skip
        // exists to avoid, only unbounded.
        //
        // Reachability is not hypothetical, and it has TWO independent entry paths (both verified by
        // search, after an earlier draft of this comment got them wrong):
        //
        //   1. `AnalysisController.looksLikeMsg()` -> this function. `looksLikeMsg` is reached from
        //      `extractMessage`, which is reached from `messageOfRow` -> `resolveRow` -> `renderOn`,
        //      i.e. the CARD RENDER path.
        //   2. `MenuHook.msgFromTag()` -> this function DIRECTLY. Note this one does NOT go through any
        //      `looksLikeMsg` -- there is a second, unrelated `looksLikeMsg` in `ChatListHook` that
        //      never calls this function at all. See the note below; the two names are a trap.
        //
        // Cards ARE reachable on Kuaishou -- the balloon attaches there and history capture is being
        // added in v7.0.0 -- so the first Kuaishou card would trigger path 1.
        //
        // THE NAME TRAP, recorded because it cost time here: `AnalysisController.looksLikeMsg` calls
        // this function, while `ChatListHook.looksLikeMsg` does NOT (it tests `getMsgId` +
        // `access().content()`). Two same-named private helpers with different bodies in different
        // files -- reading either one and generalising to "the looksLikeMsg" gives the wrong reachability
        // answer. Verify by file, never by name.
        //
        // Returning null is correct rather than merely cheap: the search strings and the target package
        // are WeChat's, so on any other host the honest answer is "not resolvable here", and null is
        // exactly what the callers already treat as "no message class known".
        // Written as an exhaustive `when` rather than `if (current != WECHAT)` -- and that is not
        // stylistic. The `!= WECHAT` form was the very shape this version set out to eliminate: it
        // silently files every FUTURE host into the "not WeChat" branch, so adding a fifth host would
        // require no compiler complaint and no reviewer attention for the guard to be wrong. With a
        // `when` over the host enum, a new constant turns this into a COMPILE ERROR and the author has
        // to decide, at this line, whether the new host may run the WeChat DexKit search.
        // (ks-reviewer caught the first draft of this using the `!=` form -- which is precisely why the
        // rule is written down in 必看.md instead of being left to memory.)
        when (HostApp.current) {
            HostApp.WECHAT -> {
                // Falls through to the search below: unchanged, WeChat-only behaviour.
            }
            HostApp.DOUYIN, HostApp.SOUL, HostApp.KUAISHOU, HostApp.QQ,
            // v9.3.0: Xiaohongshu. Every name this Resolver knows is a WeChat name, so the DexKit
            // search cannot succeed here either -- same reasoning as the other four non-WeChat hosts.
            HostApp.XIAOHONGSHU,
            HostApp.UNKNOWN -> {
                Log.i(
                    TAG,
                    "host=" + HostApp.current.name + ": msgInfoClass skipped " +
                        "(WeChat-only DexKit search; returning null)",
                )
                return null
            }
        }
        try {
            val wd = ModuleBridge.workDir
            if (!SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) return null
            val apk = File(apkPath)
            if (!apk.isFile) return null
            DexKitBridge.create(apkPath).use { bridge ->
                val found = bridge.findClass {
                    searchPackages("com.tencent.mm.storage")
                    matcher {
                        usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
                    }
                }
                Log.i(TAG, "msgInfoClass: candidates=" + found.size)
                // v2.4.4: prefer the class that actually lives in com.tencent.mm.storage, and log them all.
                var best: Class<*>? = null
                var bestScore = -1
                for (f in found) {
                    val c = loadClass(f.name) ?: continue
                    var score = 10
                    if (c.name.startsWith("com.tencent.mm.storage")) score += 50
                    // the message class exposes the field WeChat stores content in
                    if (c.declaredFields.any { it.name == "field_content" }) score += 30
                    if (c.declaredFields.any { it.name == "field_msgId" }) score += 30
                    Log.i(TAG, "msgInfoClass: candidate " + c.name + " score=" + score)
                    if (score > bestScore) {
                        bestScore = score
                        best = c
                    }
                }
                val picked = best
                if (picked != null) {
                    cachedMsgClass = picked
                    Log.i(TAG, "msgInfoClass: RESOLVED " + picked.name + " (score=" + bestScore + ")")
                    return picked
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "msgInfoClass failed: " + t.message)
        }
        return null
    }

    @Volatile
    private var cachedMsgClass: Class<*>? = null

    /**
     * v2.7.0 (item 2): the MULTI-SELECT toolbar and its selected-messages getter, resolved WeKit's way.
     *
     * WeKit uses:
     *     MULTI_SELECT_BAR = "com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar"
     *     set-button-listener: paramTypes("int", "android.view.View$OnClickListener"),
     *                          returnType "void", string "set button listener error button index %d"
     *     selected messages  : a no-arg method returning java.util.List, declared in a class holding a field
     *                          of the bar type, whose body calls Collections.sort
     *
     * Both are found by SHAPE, so no obfuscated name is needed.
     *
     * @return (setButtonListener, getSelectedMessages) -- either may be null
     */
    fun multiSelectBarMethods(): Pair<java.lang.reflect.Method?, java.lang.reflect.Method?> {
        val all = multiSelectBarAll()
        return all.setListener to all.getSelected
    }

    /** v3.0.2: the three bar methods WeKit hooks, plus the selection getter. */
    data class MultiSelectBarMethods(
        val setListener: java.lang.reflect.Method?,
        val rebuildButtons: java.lang.reflect.Method?,
        val updateSelection: java.lang.reflect.Method?,
        val getSelected: java.lang.reflect.Method?,
    )

    /**
     * v3.0.2: resolves every method WeKit's multi-select integration hooks.
     *
     * WeKit (WeChatMessageContextMenuApi.kt) resolves FOUR things on
     * `com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar`:
     *
     *   methodMultiSetButtonListener  paramTypes("int", View$OnClickListener), void,
     *                                 string "set button listener error button index %d"
     *   methodMultiRebuildButtons     paramCount 0, void, invokes removeAllViews()
     *   methodMultiUpdateSelection    paramTypes("int"), void, invokes setEnabled(boolean)
     *   methodMultiGetSelectedMessages  runs on another class that HOLDS a bar field
     *
     * Rebuild matters because WeChat calls removeAllViews() when it rebuilds the bar, which drops any
     * child we inserted. WeKit re-attaches its cached button in that hook; without it the button shows
     * once and then disappears.
     */
    fun multiSelectBarAll(): MultiSelectBarMethods {
        var setListener: java.lang.reflect.Method? = null
        var rebuild: java.lang.reflect.Method? = null
        var updateSel: java.lang.reflect.Method? = null
        var getSelected: java.lang.reflect.Method? = null
        try {
            val wd = ModuleBridge.workDir
            if (!SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
                Log.w(TAG, "multiSelectBarAll: dexkit unavailable")
                return MultiSelectBarMethods(null, null, null, null)
            }
            val apk = File(apkPath)
            if (!apk.isFile) return MultiSelectBarMethods(null, null, null, null)

            val barCls = loadClass("com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar")
            if (barCls == null) {
                Log.w(TAG, "multiSelectBarAll: the multi-select bar class is absent on this build")
                return MultiSelectBarMethods(null, null, null, null)
            }

            DexKitBridge.create(apkPath).use { bridge ->
                // ---- 1. set-button-listener: (int, View$OnClickListener) -> void
                val listeners = bridge.findMethod {
                    matcher {
                        declaredClass("com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar")
                        paramTypes("int", "android.view.View\$OnClickListener")
                        returnType = "void"
                    }
                }
                Log.i(TAG, "multiSelectBarAll: set-listener candidates=" + listeners.size)
                for (f in listeners) {
                    val c = loadClass(f.className) ?: continue
                    val m = c.declaredMethods.firstOrNull {
                        it.name == f.methodName &&
                            it.parameterCount == 2 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            android.view.View.OnClickListener::class.java
                                .isAssignableFrom(it.parameterTypes[1])
                    }
                    if (m != null) {
                        setListener = m
                        Log.i(TAG, "multiSelectBarAll: set-listener RESOLVED " + c.name + "." + m.name)
                        break
                    }
                }

                // ---- 2. rebuildButtons: () -> void on the bar (the one that clears children)
                val noArgVoid = bridge.findMethod {
                    matcher {
                        declaredClass("com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar")
                        paramCount = 0
                        returnType = "void"
                    }
                }
                for (f in noArgVoid) {
                    val c = loadClass(f.className) ?: continue
                    val m = c.declaredMethods.firstOrNull {
                        it.name == f.methodName && it.parameterCount == 0 &&
                            it.returnType == Void.TYPE
                    } ?: continue
                    // WeKit identifies it by the removeAllViews() call inside; the constructor also
                    // matches the shape, so prefer a method that is NOT <init>.
                    if (m.name == "<init>") continue
                    if (rebuild == null) {
                        rebuild = m
                        Log.i(TAG, "multiSelectBarAll: rebuildButtons RESOLVED " + c.name + "." + m.name)
                    }
                }

                // ---- 3. updateSelection: (int) -> void
                val oneIntVoid = bridge.findMethod {
                    matcher {
                        declaredClass("com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar")
                        paramTypes("int")
                        returnType = "void"
                    }
                }
                for (f in oneIntVoid) {
                    val c = loadClass(f.className) ?: continue
                    val m = c.declaredMethods.firstOrNull {
                        it.name == f.methodName && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            it.returnType == Void.TYPE
                    }
                    if (m != null) {
                        updateSel = m
                        Log.i(TAG, "multiSelectBarAll: updateSelection RESOLVED " + c.name + "." + m.name)
                        break
                    }
                }

                // ---- 4. the selected-messages getter (owner must HOLD a bar field)
                val getters = bridge.findMethod {
                    searchPackages("com.tencent.mm.ui.chatting")
                    matcher {
                        paramCount = 0
                        returnType = "java.util.List"
                    }
                }
                Log.i(TAG, "multiSelectBarAll: getter candidates=" + getters.size)
                for (f in getters) {
                    val c = loadClass(f.className) ?: continue
                    val holdsBar = c.declaredFields.any { barCls.isAssignableFrom(it.type) }
                    if (!holdsBar) continue
                    val m = c.declaredMethods.firstOrNull {
                        it.name == f.methodName && it.parameterCount == 0 &&
                            java.util.List::class.java.isAssignableFrom(it.returnType)
                    }
                    if (m != null) {
                        getSelected = m
                        Log.i(TAG, "multiSelectBarAll: getter RESOLVED " + c.name + "." + m.name)
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "multiSelectBarAll failed: " + t.message)
        }
        return MultiSelectBarMethods(setListener, rebuild, updateSel, getSelected)
    }

    /**
     * v2.4.0: WEKIT'S ANCHOR -- the CHAT menu builder, found by two strings together.
     *
     * WeKit (open source, works on 8.0.65-8.0.78) resolves it as:
     *
     *     searchPackages("com.tencent.mm.ui.chatting.viewitems")
     *     matcher { usingEqStrings("MicroMsg.ChattingItem", "msg is null!") }
     *
     * Requiring BOTH strings inside that package pins the exact chat long-press menu builder. This module used
     * a single log-line string which matched a class that is not on the chat path -- which is why the hooks
     * installed but never fired (`menuAttempts` stayed 0 in every version).
     *
     * Returns the RESOLVED METHOD: the anchor string is logged BY it, so the method that contains it is the
     * menu builder. Hooking the method itself is what WeKit does.
     */
    fun chatMenuBuilder(): java.lang.reflect.Method? {
        try {
            val wd = ModuleBridge.workDir
            if (!SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
                Log.w(TAG, "chatMenuBuilder: dexkit unavailable")
                return null
            }
            val apk = File(apkPath)
            if (!apk.isFile) {
                Log.w(TAG, "chatMenuBuilder: apk missing")
                return null
            }
            DexKitBridge.create(apkPath).use { bridge ->
                val found = bridge.findMethod {
                    searchPackages("com.tencent.mm.ui.chatting.viewitems")
                    matcher {
                        usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
                    }
                }
                Log.i(TAG, "chatMenuBuilder: candidates=" + found.size)

                // v2.4.4: SCORE every candidate instead of taking the first.
                //
                // On 8.0.76 the two anchor strings appear in THREE dexes, so several classes match; taking the
                // first can select a builder that is not the chat long-press menu. The failure is silent --
                // hooks install, nothing appears. Scoring against the package and the parameter shape picks the
                // right one on every build, and the log lists them all so a bad pick is visible immediately.
                // v2.9.0: WEKIT'S RULE -- the match must be UNIQUE.
                //
                // WeKit's find() errors when DexKit returns more than one result:
                //     error("DexKit: Multiple methods found for key: $key, ...")
                // My scoring picked a "best" candidate instead, and that is precisely how two devices can hook
                // two DIFFERENT methods while only one of them ever fires.
                //
                // No scoring. No fallback. The first class that yields a (ContextMenu, View) method is used,
                // and if there is more than one candidate the ambiguity is LOGGED so it can be fixed rather
                // than silently guessed.
                val matches = ArrayList<Pair<java.lang.reflect.Method, String>>()
                for (f in found) {
                    val c = try {
                        f.className
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val cls = loadClass(c)
                    if (cls == null) {
                        Log.e(
                            TAG,
                            "chatMenuBuilder: LOAD FAILED for " + c +
                                " -- the class loader cannot see it, so NO menu item can appear",
                        )
                        continue
                    }
                    for (m in cls.declaredMethods) {
                        val ps = m.parameterTypes
                        if (ps.size < 2) continue
                        if (!android.view.ContextMenu::class.java.isAssignableFrom(ps[0])) continue
                        if (!android.view.View::class.java.isAssignableFrom(ps[1])) continue
                        matches.add(m to c)
                    }
                }

                if (matches.size > 1) {
                    Log.w(
                        TAG,
                        "chatMenuBuilder: WARNING " + matches.size + " candidates on this build: " +
                            matches.joinToString { it.second + "." + it.first.name },
                    )
                }
                for ((m, c) in matches) {
                    Log.i(
                        TAG,
                        "chatMenuBuilder: RESOLVED " + c + "." + m.name +
                            " loader=" + (m.declaringClass.classLoader?.javaClass?.name ?: "bootstrap"),
                    )
                }
                if (matches.isNotEmpty()) return matches[0].first
                Log.w(TAG, "chatMenuBuilder: no (ContextMenu, View) method among the candidates")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "chatMenuBuilder failed: " + t.message)
        }
        return null
    }

    /**
     * item 1 (v2.3.0): the classes that RECEIVE the menu-item click.
     *
     * Nuke carries the same string, and WeChat has 923 classes declaring `onMMMenuItemSelected` (67
     * chat-related). Hooking the menu-building side alone produces a button that does nothing, so the click
     * side is resolved the same way -- by METHOD NAME, which is version-proof.
     */
    fun clickHandlerClasses(): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        try {
            val wd = ModuleBridge.workDir
            if (SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
                val apk = File(apkPath)
                if (apk.isFile) {
                    DexKitBridge.create(apkPath).use { bridge ->
                        for (methodName in arrayOf("onMMMenuItemSelected", "onMenuItemClick")) {
                            val found = bridge.findMethod {
                                searchPackages("com.tencent.mm")
                                matcher { name(methodName) }
                            }
                            Log.i(TAG, "clickHandlerClasses: " + methodName + "=" + found.size)
                            for (f in found) {
                                val owner = try {
                                    f.className
                                } catch (_: Throwable) {
                                    null
                                } ?: continue
                                val c = loadClass(owner) ?: continue
                                out.add(c)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clickHandlerClasses failed: " + t.message)
        }
        return out.toList()
    }

    /** True when the class declares `onCreateMMMenu` whose first parameter is the menu class. */
    private fun declaresMenuContributor(c: Class<*>, menuCls: Class<*>): Boolean = try {
        c.declaredMethods.any { m ->
            m.name == "onCreateMMMenu" &&
                m.parameterTypes.isNotEmpty() &&
                menuCls.isAssignableFrom(m.parameterTypes[0])
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * The classes of one package, discovered without loading them.
     *
     * A class loader cannot enumerate, so this lists the dex first: it reads the package's dex entries through
     * DexKit when available, and otherwise returns what the module can reach by name. A missing enumeration
     * only means fewer candidates; the DexKit path above is the primary one.
     */
    private fun classesInPackage(pkg: String, loader: ClassLoader): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        try {
            val apk = File(apkPath)
            if (apk.isFile) {
                java.util.zip.ZipFile(apk).use { zip ->
                    val names = HashSet<String>()
                    for (abi in listOf("classes.dex")) {
                        val e = zip.getEntry(abi) ?: continue
                        // cheap textual scan of the dex string pool for this package
                        val buf = ByteArray(1 shl 22)
                        zip.getInputStream(e).use { ins ->
                            var off = 0
                            while (true) {
                                val n = ins.read(buf, off, buf.size - off)
                                if (n <= 0) break
                                off += n
                                if (off >= buf.size) break
                            }
                            val s = String(buf, 0, off, Charsets.ISO_8859_1)
                            val key = "L" + pkg.replace('.', '/') + "/"
                            var i = s.indexOf(key)
                            while (i >= 0) {
                                val j = s.indexOf(';', i)
                                if (j > i && j - i < 120) {
                                    names.add(s.substring(i + 1, j))
                                }
                                i = s.indexOf(key, i + 1)
                            }
                        }
                    }
                    for (n in names) {
                        val c = try {
                            Class.forName(n.replace('/', '.'), false, loader)
                        } catch (_: Throwable) {
                            null
                        }
                        if (c != null) out.add(c)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "classesInPackage(" + pkg + ") failed: " + t.message)
        }
        return out
    }

    /**
     * item (clone): resolves with a TIME LIMIT, and falls back when the limit is reached.
     *
     * WHY: `DexKitBridge.create(apkPath)` is a native call that maps and scans WeChat's whole APK. On user 0
     * that takes milliseconds; in a CLONE (a different android user) it never returned, which blocked the
     * install thread and left the module with no menu hook at all -- the reported "no 分析 button".
     *
     * This runs the resolution on its own thread and waits at most `timeoutMs`. If it does not finish, the
     * resolution is abandoned and the FALLBACK is used, which resolves the classes by their known names for
     * this WeChat version and needs no DexKit.
     *
     * The normal case is unaffected: user 0 finishes well inside the limit.
     */
    fun resolveWithTimeout(timeoutMs: Long = 12000L): HookPoints {
        var out: HookPoints? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val worker = Thread({
            try {
                out = resolve()
            } catch (t: Throwable) {
                Log.e(TAG, "resolveWithTimeout: resolve threw: " + t.javaClass.simpleName + ": " + t.message)
            } finally {
                latch.countDown()
            }
        }, "LuoLiao-resolve")
        worker.isDaemon = true

        val started = System.currentTimeMillis()
        try {
            worker.start()
        } catch (t: Throwable) {
            Log.w(TAG, "resolveWithTimeout: could not start the resolver: " + t.message)
            return fallbackForGuard()
        }

        val finished = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
        val took = System.currentTimeMillis() - started

        val got = out
        if (finished && got != null) {
            Log.i(TAG, "resolveWithTimeout: completed in " + took + "ms status=" + got.status)
            return got
        }

        Log.w(
            TAG,
            "resolveWithTimeout: DexKit did NOT finish in " + took +
                "ms (finished=" + finished + "); using the FALLBACK so the menu hooks are installed",
        )
        return fallbackForGuard()
    }

    /**
     * item (clone): the fallback points, as a PUBLIC factory.
     *
     * Used as a guard when `resolve()` fails, so the menu hooks are still installed from the version table
     * instead of the module ending up installed-but-inert -- which is what the clone's log showed.
     */
    fun fallbackForGuard(): HookPoints = fallbackResolve()

    fun resolve(): HookPoints {
        points?.let { return it }

        val viaDex = try {
            dexKitResolve()
        } catch (t: Throwable) {
            // A failure here used to vanish: the exception left resolve() and the caller's catch hid it, so
            // the clone silently fell back with no menu hooks. It is named now.
            Log.w(TAG, "dexKitResolve threw: " + t.javaClass.simpleName + ": " + t.message)
            null
        }
        if (viaDex != null) {
            points = viaDex
            return viaDex
        }

        Log.i(
            TAG,
            "resolve: using the FALLBACK (no string-anchor resolution)" +
                " workDirOk=" + runCatching { ModuleBridge.workDir.isDirectory }.getOrDefault(false),
        )
        val fb = fallbackResolve()
        points = fb
        return fb
    }

    private fun dexKitResolve(): HookPoints? {
        // `workDir` is evaluated here, INSIDE the function, so a problem with it is logged instead of
        // escaping as an argument-evaluation exception.
        val wd = try {
            ModuleBridge.workDir
        } catch (t: Throwable) {
            Log.w(TAG, "dexKitResolve: workDir unavailable: " + t.message)
            return null
        }
        Log.i(TAG, "dexKitResolve: entering (moduleAppInfo=" +
            (ModuleBridge.moduleAppInfo != null) + " workDir=" + wd.absolutePath + ")")
        if (!SoLoader.ensureLoaded(ModuleBridge.moduleAppInfo, wd)) {
            Log.w(TAG, "dexkit unavailable; skipping string anchor resolution")
            return null
        }
        val apk = File(apkPath)
        if (!apk.isFile) {
            Log.w(TAG, "invalid APK path: " + apkPath)
            return null
        }

        return try {
            DexKitBridge.create(apkPath).use { bridge ->
                Log.i(TAG, "DexKit bridge created, searching anchor string")
                val found = bridge.findClass {
                    searchPackages("com.tencent.mm")
                    matcher { usingStrings(ANCHOR_MENU) }
                }
                Log.i(TAG, "anchor match count=" + found.size)
                if (found.isEmpty()) {
                    Log.w(TAG, "DexKit found no menu producer via anchor")
                    return@use null
                }
                val producer = loadClass(found[0].name)
                if (producer == null) {
                    Log.w(TAG, "anchor class not loadable: " + found[0].name)
                    return@use null
                }
                Log.i(TAG, "anchor resolved: " + producer.name)

                var menuClass: Class<*>? = null
                var longClickClass: Class<*>? = null

                for (ctor in producer.declaredConstructors) {
                    for (p in ctor.parameterTypes) {
                        if (!isCandidate(p)) continue
                        if (longClickClass == null) longClickClass = p
                        else if (menuClass == null) menuClass = p
                    }
                }
                for (m in producer.declaredMethods) {
                    for (p in m.parameterTypes) {
                        if (android.view.ContextMenu::class.java.isAssignableFrom(p)) {
                            menuClass = p
                        }
                    }
                }

                var msgClass: Class<*>? = null
                if (longClickClass != null) msgClass = findMsgClassFrom(longClickClass)

                if (menuClass != null &&
                    !android.view.ContextMenu::class.java.isAssignableFrom(menuClass)
                ) {
                    Log.w(TAG, "menu class validation failed: " + menuClass.name)
                    menuClass = null
                }

                val detail = "anchor=" + producer.name +
                    " menu=" + (menuClass?.name ?: "unresolved") +
                    " longClick=" + (longClickClass?.name ?: "unresolved") +
                    " msg=" + (msgClass?.name ?: "unresolved")
                Log.i(TAG, "DexKit resolution: " + detail)

                if (menuClass == null && longClickClass == null) return@use null
                HookPoints(menuClass, longClickClass, msgClass, producer, Status.DEXKIT, detail)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DexKit resolution threw; falling back to version table", t)
            null
        }
    }

    private fun findMsgClassFrom(owner: Class<*>): Class<*>? {
        for (m in owner.declaredMethods) {
            for (p in m.parameterTypes) {
                if (p.isPrimitive || p.isArray || p.isInterface) continue
                if (android.view.View::class.java.isAssignableFrom(p)) continue
                if (android.content.Context::class.java.isAssignableFrom(p)) continue
                if (android.view.Menu::class.java.isAssignableFrom(p)) continue
                if (hasMsgShape(p)) return p
            }
        }
        return null
    }

    private fun hasMsgShape(c: Class<*>): Boolean {
        val names = HashSet<String>()
        for (m in c.methods) names.add(m.name)
        if (!names.contains("getMsgId")) return false
        return names.contains("getType") || names.contains("getCreateTime")
    }

    private fun isCandidate(p: Class<*>): Boolean =
        !p.isPrimitive && !p.isArray && !p.isInterface &&
            !Modifier.isAbstract(p.modifiers) &&
            p.name.startsWith("com.tencent.mm")

    private fun fallbackResolve(): HookPoints {
        // item 2: VERSION-INDEPENDENT FALLBACK.
        //
        // The old order was: try the hard-coded names, then the superclass search. The names only exist in
        // 8.0.77, so on 8.0.76 and 8.0.49 the first step always failed and the table could never work.
        //
        // Now the SUPERCLASS SEARCH comes FIRST, because it needs no name at all:
        // `android.view.ContextMenu` is a public Android type, obfuscation cannot rename it, so any WeChat
        // class assignable to it IS the context-menu implementation. That holds for every version.
        val bySuper = menuClassBySuperclass()
        val producer = loadClass(FALLBACK_PRODUCER)
        val longClick = loadClass(FALLBACK_LONGCLICK) ?: loadClass(FALLBACK_LONGCLICK2)
        var menu = bySuper ?: loadClass(FALLBACK_MENU)
        var msg = loadClass(FALLBACK_MSG)

        if (bySuper != null) {
            Log.i(TAG, "fallback: the menu class was found by SUPERCLASS (no name table): " + bySuper.name)
        }

        if (menu != null && !android.view.ContextMenu::class.java.isAssignableFrom(menu)) {
            Log.w(TAG, "fallback menu class validation failed: " + menu.name)
            menu = null
        }
        if (msg == null && longClick != null) msg = findMsgClassFrom(longClick)

        // item 2: the MESSAGE class, derived without any name table.
        //
        // WeChat stores a message in a class the longClick/`menu` classes accept as a parameter, so scanning
        // their method parameters finds it on any version. `findMsgClassFrom` already does exactly this for
        // the longClick class; it is applied to the menu class too, which is present even when the longClick
        // name is unknown -- the 8.0.76 and 8.0.49 case.
        if (msg == null && menu != null) msg = findMsgClassFrom(menu)

        // Report which mechanism produced each value, so a future log says whether the name table was even
        // needed. A version-independent result is the goal.
        Log.i(
            TAG,
            "fallback: superclass=" + (bySuper != null) +
                " tableMenu=" + (loadClass(FALLBACK_MENU) != null) +
                " tableMsg=" + (loadClass(FALLBACK_MSG) != null),
        )

        val ok = menu != null || longClick != null || msg != null

        // v7.0.0 KUAISHOU: the "version table" wording is WeChat-only and printing it on a non-WeChat host
        // is actively misleading. There is no version table on Douyin/Soul/Kuaishou -- the FALLBACK_* names
        // are WeChat class names, and `loadClass` refuses every one of them under those hosts, so all three
        // values are "unresolved" BY DESIGN. The old string nevertheless announced a WeChat version
        // (`8.0.77/vc3160`) inside a Kuaishou process, which reads as "the module picked the wrong version
        // table for Kuaishou" -- a bug report we cannot act on, because no table exists.
        val header = if (HostApp.current == HostApp.WECHAT) {
            "[version table 8.0.77/vc3160]"
        } else {
            "[no version table by design]"
        }
        val detail = header +
            " menu=" + (menu?.name ?: "unresolved") +
            " longClick=" + (longClick?.name ?: "unresolved") +
            " msg=" + (msg?.name ?: "unresolved")
        Log.i(TAG, "fallback resolution: " + detail)

        // A non-WeChat host reaching here is NOT a failure: the WeChat lookup is skipped deliberately (see
        // the guard in `loadClass`), so "nothing resolved" is the expected result, not a degradation. Only
        // WeChat can legitimately FAIL. Reporting SKIPPED keeps "by design" out of the FAILED bucket, which
        // otherwise sends every future reader hunting a WeChat resolution bug inside a Kuaishou log.
        //
        // Exhaustive `when` on the host, no `else ->`, and no `!= <host>` arm. The earlier form
        // (`HostApp.current != HostApp.WECHAT -> SKIPPED`) was an EXCLUDED-style test: it silently claimed
        // "every host except WeChat behaves this way", which is the pattern that broke when Kuaishou was
        // added -- a new host inherited a decision nobody made for it. Switching on the host itself makes
        // the next host a COMPILE ERROR here instead of an accidental inheritance.
        val status = if (ok) {
            Status.FALLBACK
        } else {
            when (HostApp.current) {
                // Reaching here without any hook point IS a real failure on the one host this Resolver
                // is written for.
                HostApp.WECHAT -> Status.FAILED
                // By design: every name this Resolver knows is a WeChat name, so nothing can resolve
                // and `loadClass` refused each lookup. Not a defect, and not actionable.
                HostApp.DOUYIN,
                HostApp.SOUL,
                HostApp.KUAISHOU,
                HostApp.QQ,
                // v9.3.0: Xiaohongshu. Its hooks are installed by XiaohongshuHooks, not by this
                // WeChat Resolver, so an unresolved WeChat hook point here is by design, not a defect.
                HostApp.XIAOHONGSHU,
                HostApp.UNKNOWN,
                -> Status.SKIPPED
            }
        }

        return HookPoints(menu, longClick, msg, producer, status, detail)
    }

    /**
     * Finds the menu class by its PUBLIC Android superclass.
     *
     * `android.view.ContextMenu` cannot be renamed, so any class in the WeChat package that is assignable to
     * it is a context-menu implementation. This is the version-proof path: unlike a class name or a log
     * string, a framework type survives every WeChat update and every re-obfuscation.
     *
     * README: this complements the DexKit search; it does not replace it.
     */
    private fun menuClassBySuperclass(): Class<*>? {
        val names = arrayOf(
            // 8.0.77-era names, kept so nothing that works today regresses
            "nf5.g4", "nf5.h4",
        )
        for (n in names) {
            val c = loadClass(n) ?: continue
            if (android.view.ContextMenu::class.java.isAssignableFrom(c)) return c
        }
        return null
    }

    /**
     * v3.1.0: THE CLONE FIX -- resolve every host class through `classLoader()`, never the raw `loader`.
     *
     * MEASURED in the clone (android user 999): every hook installed (`CHAT MENU BUILDER HOOKED`,
     * `MENU CLASS HOOKED: nf5.g4`, `contributors hooked by SHAPE: 411`) and NOT ONE ever fired, so
     * `menuAttempts` stayed 0 and the 分析 item never appeared. User 0 on the same phone worked.
     *
     * WHY: a clone runs WeChat under `dalvik.system.DelegateLastClassLoader`, where the PARENT loader is
     * consulted LAST. `ModuleBridge.hostClassLoader` (WeChat's own `app.baseContext.classLoader`) and the
     * `loader` this Resolver was constructed with (the module Context's loader) therefore resolve the SAME
     * name to TWO DIFFERENT Class objects. `Class.forName(n0, false, loader)` returned the parent's copy --
     * a class WeChat never instantiates -- so the hook was attached to a method that is never called.
     * The install log looks perfectly healthy, which is why this stayed invisible: only `fired` is missing.
     *
     * `classLoader()` already prefers the host loader and is what `contributorClasses` uses, so this makes
     * the two paths agree. A hook can only fire when its Method belongs to the loader WeChat is running.
     *
     * 5.0.0 HOST GATE: every name this Resolver knows is a WECHAT class name (`com.tencent.mm.*` and the
     * obfuscated fallbacks `nf5.g4` / `e9`). Inside Douyin none of them can exist, so each lookup wrote a
     * `class not found:` warning and the chain ended in `hook point resolution: status=FAILED` -- a log
     * that reads like a broken install while the module was working correctly. Skipping the attempt on a
     * non-WeChat host removes that noise at its single source, and is honest: the module is not looking
     * for anything here.
     */
    private fun loadClass(name: String): Class<*>? {
        // Exhaustive host dispatch, no `else ->`. The previous form was `!= HostApp.WECHAT`, an
        // EXCLUDED-style test that answers "should I skip?" for hosts nobody considered -- exactly how a
        // newly added host silently inherits WeChat's absence handling. Switching on the host makes adding
        // a fifth host a compile error here, which is the point.
        val wechat = when (HostApp.current) {
            HostApp.WECHAT -> true
            HostApp.DOUYIN,
            HostApp.SOUL,
            HostApp.KUAISHOU,
            HostApp.QQ,
            // v9.3.0: Xiaohongshu is NOT WeChat, so this Resolver refuses every WeChat class lookup
            // here rather than running WeChat's name probes inside Xiaohongshu's process.
            HostApp.XIAOHONGSHU,
            HostApp.UNKNOWN,
            -> false
        }
        if (!wechat) {
            Log.i(
                TAG,
                "host=" + HostApp.current.name + ": skipping the WeChat lookup for " + name +
                    " (this Resolver only knows WeChat class names)",
            )
            return null
        }
        return try {
            Class.forName(name, false, classLoader())
        } catch (t: Throwable) {
            Log.w(TAG, "class not found: " + name + " (" + t.javaClass.simpleName + ")")
            null
        }
    }
}
