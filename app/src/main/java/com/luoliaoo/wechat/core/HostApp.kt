package com.luoliaoo.wechat.core

/**
 * Which app the module is currently running inside.
 *
 * WHY THIS EXISTS (5.0.0):
 * The module used to assume WeChat everywhere: `ModuleEntry` only continued for `com.tencent.mm`,
 * every UI string said 微信 / 微信号, and `Resolver`/`MenuHook`/`MsgAccess` were WeChat class names.
 * Adding Douyin support by sprinkling `if (isDouyin)` through those files would have made every
 * future bug a two-host bug.
 *
 * Instead the HOST is decided ONCE, here, and every host-dependent decision asks this object.
 * The two hosts differ in three ways only:
 *   1. the package name(s) that scope this module,
 *   2. the display name used in user-facing text (微信 vs 抖音),
 *   3. the name of the per-user identifier shown in the prompt (微信号 vs 抖音号).
 *
 * Comments are ASCII-only in this file; Chinese text uses \uXXXX escapes, matching core/Log.kt's rule.
 */

/**
 * The package lists, OUTSIDE the enum.
 *
 * WHY NOT in the companion: an enum constant's constructor arguments run BEFORE the companion object
 * is initialised, so `packages = WECHAT_PACKAGES` inside the constants was a compile error
 * ("Companion object of enum class 'Companion' is uninitialized here"). A top-level holder is
 * initialised on its own first access, so the constants can read it safely.
 */
private object Pkgs {

    /** 微信主包及其分身。Kept in sync with `res/values/arrays.xml`. */
    val WECHAT: List<String> = listOf(
        "com.tencent.mm",
        "com.tencent.mm.u1", "com.tencent.mm.u2", "com.tencent.mm.u3", "com.tencent.mm.u4",
        "com.tencent.mm.u5", "com.tencent.mm.u6", "com.tencent.mm.u7", "com.tencent.mm.u8",
        "com.tencent.mm.u9",
        "com.tencent.mm.clone", "com.tencent.mm.dual", "com.tencent.mm.multiapp",
        "com.tencent.mm.parallel", "com.tencent.mm.virtual",
    )

    /**
     * The Douyin packages, kept in sync with `res/values/arrays.xml`.
     *
     * WHY ONLY THREE, when WeChat above lists ten clones: the WeChat clone names (`u1`..`u9`,
     * `clone`, `dual`, ...) are known real names, but nobody has evidence of a real Douyin clone.
     * A device check found NO Douyin clone package installed at all. Eight invented `aweme.*` names
     * used to sit here, and their only effect was to make the module's own diagnostics print
     * "25 package(s) accepted" while the real scope list held 18 -- a wrong number is worse than a
     * short list, because it is the line a user reads to decide whether their clone is covered.
     *
     * Clones still WORK: [of] falls back to the `com.ss.android.ugc.aweme.` prefix below, so a
     * cloner that reports `com.ss.android.ugc.aweme.whatever` is still recognised as Douyin. This
     * list only decides what is listed by default and what the count says.
     *
     * `com.ss.android.ugc.trill` (TikTok) is NOT covered by that prefix and must stay listed.
     */
    val DOUYIN: List<String> = listOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.ss.android.ugc.trill",
    )

    /**
     * The Soul packages, kept in sync with `res/values/arrays.xml`.
     *
     * MEASURED, not guessed: the APK at
     * `E:\...\Soul_6.37.0.apk` was unpacked and its binary `AndroidManifest.xml` parsed directly
     * (both `aapt2` and `androguard` are absent on this machine, so `_tools/axml.py` reads the AXML
     * string pool and the START_ELEMENT chunks by hand). The `<manifest>` node reports
     *
     *     package="cn.soulapp.android" versionName="6.37.0" versionCode="26091510"
     *
     * which is the ONLY name this module needs for Soul: unlike WeChat, whose clones rewrite the
     * package to `com.tencent.mm.u1` etc., Soul ships no separate clone package, and a third-party
     * cloner that keeps the original package is already covered by the `cn.soulapp.android.`
     * prefix rule in [HostApp.of].
     *
     * WHY THE LIST IS STILL A LIST: the WeChat and Douyin entries above are lists, and the scope
     * array, the manifest `<queries>` block and `versionOf()` all iterate one. Collapsing Soul to a
     * bare String here would make this the one host with a different shape, and the first place a
     * future bug hides.
     */
    val SOUL: List<String> = listOf(
        "cn.soulapp.android",
    )

    /**
     * The Kuaishou packages, kept in sync with `res/values/arrays.xml`.
     *
     * MEASURED, not guessed: the shipped APK at
     * `<app-name>_14.8.30.50423.apk` (the leading part of the file
     * name is the app's Chinese name, which this ASCII-only file cannot spell) was read directly by
     * `_tools/axml.py`, which parses the binary `AndroidManifest.xml` string pool by hand (this
     * machine has neither `aapt2` nor `androguard`). The `<manifest>` node reports
     *
     *     package="com.smile.gifmaker" versionName="14.8.30.50423" versionCode="50423"
     *
     * which is the ONLY name this module needs for Kuaishou. Unlike WeChat, whose clones rewrite the
     * package to `com.tencent.mm.u1` etc., Kuaishou ships no separate main-line package, and a
     * third-party cloner that keeps the original package is already covered by the
     * `com.smile.gifmaker.` prefix rule in [HostApp.of].
     *
     * WHY A LIST, not a bare String: the WeChat / Douyin / Soul entries above are lists, and the
     * scope array, the manifest queries block and `versionOf()` all iterate one. Collapsing Kuaishou
     * to a String here would make it the one host with a different shape, which is the first place a
     * future bug hides.
     *
     * WHY A CLONE SUFFIX IS NOT LISTED (the WeChat-style `u1`..`u9` family): those names are KNOWN
     * real WeChat clone packages. No Kuaishou clone package name has been observed on a device, and
     * inventing eight of them would make the module's own diagnostic print a package count larger
     * than the scope list actually holds -- a wrong number is worse than a short list, because it is
     * the line a user reads to decide whether their clone is covered. This is the same reasoning the
     * Douyin entry above records, and it is why the PREFIX RULE plus this list is the whole story.
     */
    val KUAISHOU: List<String> = listOf(
        "com.smile.gifmaker",
    )
}

/**
 * v8.0.0: the QQ packages, OUTSIDE the enum for the same reason as [Pkgs] above.
 *
 * Declared as its own top-level object so this host's reasoning stays in one place: QQ ships REAL
 * sub-processes, which the four hosts above do not.
 *
 * MEASURED, not guessed: `_tools/qq/qq_manifest.py <apk> <outdir>` parsed the shipped APK's binary
 * `AndroidManifest.xml` by hand (this machine has neither `aapt2` nor `androguard`), and the output in
 * `_tools/qq/manifest_tree.txt` line 1 reports
 *
 *     <manifest versionCode=16240 versionName=9.3.65
 *               package=com.tencent.mobileqq ...>
 *
 * which is the ONLY package name this module needs for QQ.
 *
 * WARNING: THE SUB-PROCESSES ARE REAL AND ARE HANDLED ELSEWHERE, NOT HERE. The same manifest declares
 * `com.tencent.mobileqq:video` and `com.tencent.mobileqq:MSF` (`_tools/qq/pkgfamily.txt`). Those are
 * PROCESS names, not packages, and listing them in this list would be wrong twice over: `scopePackages()`
 * feeds the LSPosed scope UI, which needs packages, and `versionOf()` calls `getPackageInfo` on the FIRST
 * entry only. LSPosed reports a `:`-suffixed process against `packageName = com.tencent.mobileqq`, and
 * [of] already resolves that to QQ; [ofProcess] additionally strips the suffix for clones that report the
 * original package in the process name. So the sub-processes need no entry here -- and a wrong entry
 * would make the module's own package count disagree with the scope list, which is the failure the
 * Douyin comment above records.
 *
 * ============================ WHY THE PREFIX RULE IS SAFE HERE, MEASURED ====================
 * The `com.tencent.mobileqq.` prefix was NOT widened on a hunch. Measured on the user's device:
 *
 *     adb shell "pm list packages | grep -E 'mobileqq|tencent.tim|minihd|qqi'"
 *         package:com.tencent.mobileqq          <<< the ONLY QQ package
 *
 * i.e. QQ ships exactly ONE package and there is NO tim / minihd / qqi / HD / lite family to cover.
 * That is why this host follows the Soul/Kuaishou precedent (exact match + prefix for a cloner)
 * rather than growing a WeChat-style list of ten invented clone names.
 *
 * THE SAME COMMAND, SEARCHED WIDER, IS WHY THIS PREFIX MUST NEVER BE `com.tencent.`:
 *
 *     package:com.tencent.mobileqq        <<< QQ
 *     package:com.tencent.qqmusic         <<< a music player, NOT QQ
 *     package:com.tencent.qqlive          <<< a video app, NOT QQ
 *
 * `com.tencent.qqmusic` and `com.tencent.qqlive` are BOTH installed on that device and both begin
 * with a `com.tencent.qq` stem. A rule as loose as `com.tencent.` (or even `com.tencent.qq`) would
 * resolve them to QQ and install QQ's hooks into a music player -- which is precisely the class of
 * bug this file's UNKNOWN default exists to prevent. The prefix used here is the FULL package name
 * plus a dot, so it can only ever match a cloner that kept the real package and appended to it.
 *
 * A THIRD-PARTY PACKAGE MUST STAY OUT OF THIS FAMILY: `io.github.qqglassnav.replica` is also
 * installed. It is somebody else's app whose NAME mentions QQ; it does not share the
 * `com.tencent.mobileqq` package, so `of` already answers UNKNOWN for it, and it must never be
 * added to the list below. The rule that keeps it out is the same one that keeps the Tencent apps
 * out: match the PACKAGE, never a substring that merely looks like the product name.
 *
 * HOW `com.tencent.mobileqq.` INTERACTS WITH THE OTHER HOST PREFIXES: `com.tencent.mm.` (WeChat) and
 * `com.tencent.mobileqq.` (QQ) share the `com.tencent.` vendor prefix but neither is a leading
 * substring of the other (they diverge at the third segment, `mm` vs `mobileqq`), so no QQ package
 * can be answered as WeChat or the reverse. The exact-match list is consulted before any prefix test
 * anyway, in [of].
 *
 * WHY A LIST, not a bare String: the WeChat / Douyin / Soul / Kuaishou entries above are lists, and the
 * scope array, the manifest queries block and `versionOf()` all iterate one. Collapsing QQ to a String
 * here would make it the one host with a different shape, which is the first place a future bug hides.
 */
private object QQPkgs {

    /**
     * The QQ family: 手机QQ and TIM.
     *
     * ============================== WHY TIM BELONGS TO THE QQ HOST ==============================
     * TIM (\u817e\u8baf\u5f81\u7a0b, "Tencent Travel") is QQ's office build: a separate APK, a separate
     * package, but the SAME NT chat engine. That is not an assumption -- the shipped TIM 4.1.0 APK
     * (`E:\tim\TIM.apk`, 154 MB, 24 dex) was unpacked and its string pools scanned directly for every
     * class this module hooks on QQ. ALL of them are present, in the same packages:
     *
     *     com/tencent/qqnt/aio/menu/ui/QQCustomMenuExpandableLayout   <- the long-press menu container
     *     com/tencent/qqnt/aio/menu/ui/QQCustomMenuNoIconLayout       <- its no-icon twin
     *     com/tencent/qqnt/aio/menu/ui/e                              <- the menu ITEM (row payload)
     *     com/tencent/qqnt/aio/widget/BubblePopupWindow               <- the popup the menu lives in
     *     com/tencent/mobileqq/aio/msg/AIOMsgItem                     <- the message row model
     *     com/tencent/qqnt/kernel/nativeinterface/MsgRecord           <- the native message record
     *     com/tencent/aio/.../msglist/mvx/vb/core/AbsMsgListVB        <- the list view-binder
     *     com/tencent/mobileqq/aio/input/edit/AIOEditText             <- the input box
     *     com/tencent/mobileqq/aio/event/MultiSelectEvent             <- multi-select's event payload
     *
     * NOTE what is NOT here: `com/tencent/mobileqq/aio/msglist/holder/AIOMsgItemHolder` is absent. That
     * is why the row registry keys by the BIND ARGUMENT (`AbsMsgListVB#y`'s arg0) and by holder
     * IDENTITY rather than by any holder class name -- see QQRowRegistry. A registry written against
     * the holder's name would work on QQ and silently find nothing on TIM.
     *
     * WHY ONE HOST RATHER THAN A SIXTH: the module's rule is that a host owns a package family and a
     * hook set. TIM and QQ are the same hook set on the same engine, differing only in brand text --
     * so a sixth enum constant would duplicate every host branch in the codebase and make every future
     * bug a two-host bug, which is exactly what the five-branch design exists to prevent. The
     * DIFFERENCES are captured where they actually live: in [displayName]/[idLabel], which is what
     * user-facing text is built from.
     *
     * `com.tencent.tim` is the ONLY TIM package observed on the device (`pm list packages`), matching
     * QQ's own single-package shape. A cloner that appends a suffix is covered by the prefix rule.
     */
    val QQ: List<String> = listOf(
        "com.tencent.mobileqq",
        "com.tencent.tim",
    )
}

/**
 * v9.3.0: the Xiaohongshu packages, OUTSIDE the enum for the same reason as [Pkgs] and [QQPkgs].
 *
 * MEASURED, not guessed. `adb shell pm list packages | grep -iE 'xhs|xiaohongshu|rednote'` on the
 * user's device reports exactly one package, and `dumpsys package com.xingin.xhs` gives
 *
 *     versionName=9.47.0  versionCode=9470809  minSdk=21  targetSdk=36
 *
 * so unlike WeChat (whose clones rewrite the package to `com.tencent.mm.u1` etc.) there is no
 * clone-package family to enumerate. A third-party cloner that appends a suffix is already covered
 * by the `com.xingin.xhs.` prefix rule in [HostApp.of].
 *
 * WHY THE PREFIX IS THE FULL PACKAGE NAME PLUS A DOT, never a bare `com.xingin.`: the same
 * `pm list packages` output is the evidence -- Xiaohongshu's vendor publishes several apps under
 * `com.xingin.*`, and a loose rule would resolve them to Xiaohongshu and install its hooks into an
 * unrelated app. This is exactly the failure the QQ entry above records for `com.tencent.qqmusic`.
 *
 * WHY A LIST, not a bare String: every other host entry is a list, and the scope array, the manifest
 * `<queries>` block and `versionOf()` all iterate one. Collapsing this host to a String would make it
 * the one host with a different shape, which is the first place a future bug hides.
 */
private object XhsPkgs {

    /**
     * 小红书 (Xiaohongshu / RED). ONE package, confirmed on the device.
     *
     * NOTE the chat UI lives under the `com.xingin.im.*` package namespace INSIDE this APK -- that is
     * a Java package, not an Android package, and it is why the hook class names below start with
     * `com.xingin.im.` while the Android package is `com.xingin.xhs`. Confusing the two would produce
     * a scope entry that can never match.
     */
    val XHS: List<String> = listOf(
        "com.xingin.xhs",
    )
}

enum class HostApp(
    /** Human-facing app name, used in toasts and card text. */
    val displayName: String,
    /** The `…号` identifier label (微信号 / 抖音号), used in the prompt and dialogs. */
    val idLabel: String,
    /** Every package this host runs under, main package plus its clones/派生 versions. */
    val packages: List<String>,
) {
    WECHAT(
        displayName = "\u5fae\u4fe1",          // 微信
        idLabel = "\u5fae\u4fe1\u53f7",        // 微信号
        packages = Pkgs.WECHAT,
    ),

    DOUYIN(
        displayName = "\u6296\u97f3",          // 抖音
        idLabel = "\u6296\u97f3\u53f7",        // 抖音号
        packages = Pkgs.DOUYIN,
    ),

    /**
     * v6.0.0: the third host.
     *
     * `displayName` is the app's own brand name "Soul", NOT a transliteration: the app is written
     * 「Soul」 everywhere including its launcher label, so any Chinese rendering would be a name the
     * user has never seen. It is ASCII, so `say()` leaves it alone.
     *
     * `idLabel` is 「Soul\u53f7」(Soul号) -- the direct analogue of 微信号 / 抖音号, which is the
     * only thing this label is for: the prompt tells the model "the other side's <idLabel> is ...".
     * Soul's own UI calls the field 「Soul ID」, but the surrounding sentence is Chinese, so the
     * Chinese-shaped label reads correctly while 「Soul ID」 would not.
     *
     * NOTE the constructor reads `Pkgs.SOUL` -- a TOP-LEVEL object, never the companion. An enum
     * constant's arguments are evaluated before the companion is initialised, and reading a
     * companion `val` from here is a compile error (this exact mistake is recorded in 必看.md).
     */
    SOUL(
        displayName = "Soul",
        idLabel = "Soul\u53f7",               // Soul号
        packages = Pkgs.SOUL,
    ),

    /**
     * v7.0.0: the fourth host, Kuaishou.
     *
     * `displayName` is the app's own Chinese brand name (two characters, written above as the
     * `\uXXXX` escapes this file's header requires), as it appears on its launcher label. It is
     * Chinese, so `say()` DOES rewrite the WeChat words into it -- unlike Soul, whose brand name is
     * ASCII and therefore passes through `say()` untouched.
     *
     * `idLabel` is the Chinese long form: the same brand name plus the Chinese id suffix (the
     * `\uXXXX` escape above), which is the direct analogue of the WeChat and Douyin labels. That is
     * the only thing this label is for: the prompt tells the model "the other side's <idLabel> is
     * ...". The app's own profile screen labels that field the same way, so this is the wording the
     * user has actually seen.
     *
     * NOTE the constructor reads `Pkgs.KUAISHOU` -- a TOP-LEVEL object, never the companion. An enum
     * constant's arguments are evaluated before the companion is initialised, and reading a companion
     * `val` from here is a compile error (this exact mistake is recorded in the project notes).
     */
    KUAISHOU(
        displayName = "\u5feb\u624b",          // 快手
        idLabel = "\u5feb\u624b\u53f7",        // 快手号
        packages = Pkgs.KUAISHOU,
    ),

    /**
     * v8.0.0: the FIFTH host, QQ (手机QQ).
     *
     * `displayName` is the app's own brand name "QQ", NOT a Chinese rendering: the app is labelled
     * 「QQ」 on its launcher icon and in every store listing, so 「腾讯QQ」 or a transliteration would be a
     * name the user has never seen on their own device. It is ASCII, so `say()` leaves it alone -- the
     * same property the Soul entry above records, and the reason `say()` needs no special case here.
     *
     * `idLabel` is 「QQ\u53f7」(QQ号) -- the direct analogue of 微信号 / 抖音号 / 快手号, which is the
     * only thing this label is for: the prompt tells the model "the other side's <idLabel> is ...". QQ's
     * own profile screen calls the field 「QQ号」, so this is the wording the user has actually seen.
     *
     * NOTE the constructor reads `QQPkgs.QQ` -- a TOP-LEVEL object, never the companion. An enum
     * constant's arguments are evaluated BEFORE the companion is initialised, so reading a companion
     * `val` from here is a compile error. This is not a hypothetical: it is recorded in 必看.md twice,
     * and it is why the package lists in this file have never lived in the companion.
     */
    QQ(
        displayName = "QQ",
        idLabel = "QQ\u53f7",                  // QQ号
        packages = QQPkgs.QQ,
    ),

    /**
     * v9.3.0: the SIXTH host, 小红书 (Xiaohongshu / RED).
     *
     * `displayName` is the app's own brand name as its users write it. 「小红书」 is the launcher
     * label and the name in every store listing, so it is what the user reads on their own device.
     * Note it is Chinese, so `say()` DOES rewrite the WeChat words into it -- unlike Soul ("Soul")
     * and QQ ("QQ"), whose ASCII brand names pass through `say()` untouched.
     *
     * `idLabel` is 「小红书号」, the direct analogue of 微信号 / 抖音号 / 快手号 / QQ号, and it IS the
     * real field name: Xiaohongshu's own profile screen calls it 小红书号. That is the only thing this
     * label is for -- the prompt tells the model "the other side's <idLabel> is ...".
     *
     * ============================== WHAT IS DIFFERENT ABOUT THIS HOST ==============================
     * Two things, and both remove work rather than add it:
     *
     *   1. NO MULTI-SELECT. The user confirmed it: 「小红书没有多选 所以无需适配」. So there is no
     *      toolbar injection and no batch-selection path here -- the QQ multi-select machinery must
     *      NOT be reused, and this host's hook set is the single-message one only.
     *
     *   2. THE MENU IS A REAL `android.widget.PopupWindow`. Measured with
     *      `dumpsys window windows` during a long-press, which printed
     *      `Window{... u0 PopupWindow:...}  contentSize=396x1050`. QQ's menu is a custom
     *      `BubblePopupWindow` that had to be found through the framework's root-view list; this one
     *      can be intercepted at `PopupWindow#showAtLocation` / `#showAsDropDown` directly. The
     *      window-list probe is kept as a fallback rather than the only route.
     *
     * And one thing that makes it EASIER than QQ: Xiaohongshu's IM module is barely obfuscated. Its
     * menu classes carry their real names --
     * `com.xingin.im.v2.message.views.MenuView` / `MenuItemView` -- and the menu-item model's fields
     * are readable English (`menuItemIcon`, `componentName`, `isNeedShow`). There is no equivalent of
     * QQ's `AbsMsgListVB#y`-renamed-per-build problem, which is why this host needs no shape-matching
     * fallback for its class names.
     *
     * NOTE the constructor reads `XhsPkgs.XHS` -- a TOP-LEVEL object, never the companion. An enum
     * constant's arguments are evaluated BEFORE the companion is initialised, so reading a companion
     * `val` from here is a compile error. This is recorded in 必看.md, which is why the package lists
     * in this file have never lived in the companion.
     */
    XIAOHONGSHU(
        displayName = "\u5c0f\u7ea2\u4e66",        // 小红书
        idLabel = "\u5c0f\u7ea2\u4e66\u53f7",      // 小红书号
        packages = XhsPkgs.XHS,
    ),

    /**
     * A package the module was injected into that it does not understand.
     *
     * This is NOT dead: LSPosed keeps its scope list after a version change, and the user may tick
     * another app by mistake. Every host-dependent path must survive UNKNOWN rather than crash, so
     * UNKNOWN answers with neutral text and refuses to install hooks.
     */
    UNKNOWN(
        displayName = "App",
        idLabel = "ID",
        packages = emptyList(),
    );

    /** True when this host has a hook implementation. */
    val isSupported: Boolean get() = this != UNKNOWN

    /**
     * The brand name for a SPECIFIC package, when one host covers more than one brand.
     *
     * ============================== WHY THIS EXISTS ==============================
     * The QQ host owns two apps: 手机QQ (`com.tencent.mobileqq`) and TIM (`com.tencent.tim`). They are
     * one host because they share the NT engine and therefore the hook set -- but they are two
     * DIFFERENT PRODUCTS with two different names, and a TIM user seeing 「QQ 版本=4.1.0」 would be
     * reading a label for an app they do not have installed.
     *
     * Every host except QQ maps every package to its own [displayName], so this is the identity for
     * them; only QQ distinguishes. That is deliberate -- a general per-package brand table for five
     * hosts would be five times the code to say the same thing four times.
     *
     * TIM's own launcher label is 「TIM」 (three capitals, ASCII), which is what its users see, so no
     * Chinese rendering is invented here -- the same rule the Soul entry records.
     */
    fun displayNameFor(pkg: String?): String = when {
        this == QQ && pkg == TIM_PACKAGE -> "TIM"
        else -> displayName
    }

    /**
     * The `…号` label for a specific package. TIM users have a QQ number underneath, so the label
     * stays 「QQ号」 -- TIM has no separate account identifier of its own, and inventing one would tell
     * the model to look for a field that does not exist.
     */
    fun idLabelFor(pkg: String?): String = when {
        this == QQ && pkg == TIM_PACKAGE -> "QQ\u53f7"   // QQ号
        else -> idLabel
    }

    /**
     * [text] with every 微信 turned into this host's name, and every 微信号 into its id label.
     *
     * Used for the FEW strings that are shared between hosts. Anything host-specific enough to need
     * its own wording is written per-host instead of rewritten, because a blind replace would turn
     * 「微信重启」into nonsense.
     *
     * NOTE: this is an INSTANCE method, called as `HostApp.say(t)` (current host) or
     * `host.say(t)` (a host picked by the caller). A call site that forgets to resolve a host keeps
     * 微信 on the Douyin side, which is the bug this layer exists to remove.
     */
    fun say(text: String): String {
        if (this == WECHAT) return text
        var s = text
        // 微信号 must be replaced BEFORE 微信, or the longer词 is half-eaten by the shorter rule.
        s = s.replace("\u5fae\u4fe1\u53f7", idLabel)
        s = s.replace("\u5fae\u4fe1", displayName)
        return s
    }

    companion object {

        /** Badge text for a host whose package is absent (or invisible to `queries`). */
        const val NOT_INSTALLED = "\u672a\u5b89\u88c5"   // 未安装

        /**
         * TIM's package. It belongs to the QQ HOST (same NT engine, same hooks) but is a DIFFERENT
         * PRODUCT with its own name, which is the whole reason [displayNameFor] exists.
         */
        const val TIM_PACKAGE = "com.tencent.tim"

        /** The package lists, exposed for the scope UI and the manifest checks. */
        val WECHAT_PACKAGES: List<String> get() = Pkgs.WECHAT
        val DOUYIN_PACKAGES: List<String> get() = Pkgs.DOUYIN
        val SOUL_PACKAGES: List<String> get() = Pkgs.SOUL
        val KUAISHOU_PACKAGES: List<String> get() = Pkgs.KUAISHOU
        /** v8.0.0: QQ's main package, for the scope UI and the manifest checks. */
        val QQ_PACKAGES: List<String> get() = QQPkgs.QQ
        /** v9.3.0: Xiaohongshu's package, for the scope UI and the manifest checks. */
        val XIAOHONGSHU_PACKAGES: List<String> get() = XhsPkgs.XHS

        /** The hosts the module really serves, in display order. UNKNOWN is deliberately absent. */
        val REAL_HOSTS: List<HostApp> =
            listOf(WECHAT, DOUYIN, SOUL, KUAISHOU, QQ, XIAOHONGSHU)

        /**
         * The host a plain JVM unit test pretends to run in.
         *
         * WHY: every user-facing string is produced for a host, and a unit test has no LSPosed to call
         * [setCurrent]. Without this the tests could only ever assert the WeChat wording, and the
         * Douyin wording -- the whole point of 5.0.0 -- would go untested. Null means "use [current]".
         */
        @Volatile
        var override: HostApp? = null
            private set

        /** The host that user-facing text is built for right now. */
        val ui: HostApp get() = override ?: current

        /**
         * THE resolution function. Everything host-dependent goes through this.
         *
         * Rules, in order -- each exists for a real case:
         *   1. an exact, known WeChat package wins;
         *   2. an unknown `com.tencent.mm.*` is still WeChat (a cloner the user made up, e.g.
         *      `com.tencent.mm.newclone`), which is why the prefix test follows the exact one;
         *   3. `com.ss.android.ugc.aweme*` is Douyin, including the `.lite`/`.multi` variants;
         *   4. `cn.soulapp.android*` is Soul, by the same exact-then-prefix rule;
         *   5. `com.smile.gifmaker*` is Kuaishou, by that same rule;
         *   6. `com.tencent.mobileqq*` is QQ, by that same rule;
         *   7. `com.xingin.xhs*` is Xiaohongshu, by that same rule;
         *   8. anything else is UNKNOWN and gets no hooks -- never guess a host, because a wrong
         *      guess installs a WeChat hook into an unrelated app and crashes it.
         *
         * A NOTE ON THE PREFIX RULES: they are what makes a CLONE work. A cloner typically keeps
         * the original package and appends or substitutes a suffix (`cn.soulapp.android.clone`), so
         * matching the bare name alone would silently refuse to hook it. The prefix cannot over-match
         * across hosts here because no host's prefix is a prefix of another's. The six are
         * `com.tencent.mm.`, `com.ss.android.ugc.aweme.`, `cn.soulapp.android.`, `com.smile.gifmaker.`,
         * `com.tencent.mobileqq.` and `com.xingin.xhs.`.
         *
         * WARNING: QQ AND WECHAT SHARE A VENDOR PREFIX (`com.tencent.`) AND THIS IS THE ONE PLACE THAT COULD
         * GO WRONG. They do NOT share a HOST prefix: `com.tencent.mm.` is not a leading substring of
         * `com.tencent.mobileqq.` and neither is the reverse (they diverge at the third segment, `mm`
         * vs `mobileqq`), so no package can match both rules. The QQ test is written AFTER the WeChat
         * one and that ordering is therefore not load-bearing -- but do NOT "simplify" either rule to a
         * bare `com.tencent.` test, which WOULD merge the two hosts and send every QQ message through
         * the WeChat reader. The exact-match list is consulted before any prefix test anyway.
         */
        fun of(pkg: String?): HostApp {
            if (pkg.isNullOrEmpty()) return UNKNOWN
            if (WECHAT_PACKAGES.contains(pkg)) return WECHAT
            if (pkg.startsWith("com.tencent.mm.")) return WECHAT
            if (DOUYIN_PACKAGES.contains(pkg)) return DOUYIN
            if (pkg.startsWith("com.ss.android.ugc.aweme.")) return DOUYIN
            if (SOUL_PACKAGES.contains(pkg)) return SOUL
            if (pkg.startsWith("cn.soulapp.android.")) return SOUL
            if (KUAISHOU_PACKAGES.contains(pkg)) return KUAISHOU
            if (pkg.startsWith("com.smile.gifmaker.")) return KUAISHOU
            if (QQ_PACKAGES.contains(pkg)) return QQ
            if (pkg.startsWith("com.tencent.mobileqq.")) return QQ
            // v8.1.0: TIM is the QQ host (see QQPkgs.QQ for the measured evidence). The prefix is the
            // full name plus a dot, for the same reason QQ's is: `com.tencent.` alone would swallow
            // qqmusic, qqlive, tmgp.* and the rest of the Tencent family installed on this device.
            if (pkg.startsWith("com.tencent.tim.")) return QQ
            // v9.3.0: Xiaohongshu is the SIXTH host, by the same exact-then-prefix rule as Soul and
            // Kuaishou. The prefix is the full package name plus a dot: `com.xingin.` alone would
            // match the vendor's other apps and install this host's hooks into one of them -- the same
            // over-match the QQ entry above guards against with com.tencent.qqmusic.
            if (XIAOHONGSHU_PACKAGES.contains(pkg)) return XIAOHONGSHU
            if (pkg.startsWith("com.xingin.xhs.")) return XIAOHONGSHU
            return UNKNOWN
        }

        /** Packages whose process the module is willing to hook. */
        fun scopePackages(): List<String> =
            WECHAT_PACKAGES + DOUYIN_PACKAGES + SOUL_PACKAGES + KUAISHOU_PACKAGES + QQ_PACKAGES +
                XIAOHONGSHU_PACKAGES

        /**
         * The host THIS PROCESS is running inside.
         *
         * Set once by `ModuleEntry.handleLoadPackage` before any hook is installed, which is what
         * makes `HostApp.current` trustworthy for every later caller: prompt building, card text,
         * dialogs and the floating panel all read it instead of hardcoding an app name.
         *
         * DEFAULT IS WECHAT, deliberately, and this is NOT arbitrary: the module's OWN process
         * (SettingsActivity / LauncherActivity) is never injected by LSPosed, so `setCurrent` is
         * never called there. Those screens must keep working, and before 5.0.0 they were
         * WeChat-only -- defaulting to WECHAT preserves that exactly, while `UNKNOWN` would blank
         * out every label on the module's own home screen.
         *
         * A `@Volatile` field rather than a ThreadLocal because the install worker thread sets it
         * while the main thread may already be reading it; both must see the same host.
         */
        @Volatile
        var current: HostApp = WECHAT
            private set

        /** Called once per process from ModuleEntry; logs so the exported log names the host. */
        fun setCurrent(host: HostApp) {
            current = host
            Log.i("LuoLiao", "host resolved: " + host.name +
                " display=" + host.displayName + " idLabel=" + host.idLabel)
        }

        /**
         * Pins the host that user-facing text is built for. UNIT TESTS ONLY; pass null to clear.
         *
         * Production code must go through [setCurrent], which is the real per-process decision.
         */
        fun setUiOverrideForTest(host: HostApp?) {
            override = host
        }

        /**
         * [text] rewritten for the host THIS process serves: 「微信 / 微信号」 -> the host's labels.
         *
         * THE call for the prompt builder, the in-host toasts and the dialogs shown inside a host. It
         * reads [current] (not [ui]) because those call sites only run inside a host process, where
         * `setCurrent` has already decided.
         */
        fun say(text: String): String = current.say(text)

        /**
         * Every real host with its installed version, in a fixed order.
         *
         * Returns BOTH hosts even when one is absent: the launcher badge used to print a single
         * 「微信 未安装」, so a Douyin-only user had no hint Douyin was supported at all. Null version
         * means "not installed / not visible".
         */
        fun versionBadges(ctx: android.content.Context?): List<Pair<HostApp, String?>> =
            REAL_HOSTS.map { it to versionOf(ctx, it) }

        /**
         * This host plus its version, e.g. 「微信 8.0.77」/「抖音 未安装」.
         *
         * One place so the launcher badge, the environment block and the diagnostics all spell a host
         * the same way -- three copies of this label is how they drift apart.
         *
         * v8.1.0: the name is asked for the INSTALLED PACKAGE, not just the host, so a TIM user reads
         * 「TIM 4.1.0」 rather than 「QQ 4.1.0」. The two are the same host but not the same product.
         */
        fun label(host: HostApp, version: String?): String =
            label(host, version, null)

        /** [label] for a known package, when the caller has resolved which one is installed. */
        fun label(host: HostApp, version: String?, pkg: String?): String =
            host.displayNameFor(pkg) + " " +
                (version?.takeIf { it.isNotBlank() } ?: NOT_INSTALLED)

        /**
         * The host label with the name of whichever of its packages is actually installed.
         *
         * Used by the screens, which hold a Context and can therefore find out. Falls back to the
         * host's default name when neither package is visible, which is the same wording the old
         * single-package code printed.
         */
        fun label(ctx: android.content.Context?, host: HostApp, version: String?): String {
            val pkg = host.packages.firstOrNull { ctx != null && pkgInstalled(ctx, it) }
            return label(host, version, pkg)
        }

        /**
         * The BRAND NAME of [host] as installed on this device, e.g. "TIM" rather than "QQ".
         *
         * This is the function the screens must use. `host.displayName` alone is right for four of the
         * five hosts and WRONG for QQ on a device that has TIM: it would print 「QQ」 next to TIM's
         * version number, i.e. name an app the user does not have. The distinction lives here rather
         * than in each screen because there are five screens, and the one that got it wrong would be
         * the one nobody checked.
         *
         * Falls back to the host's own [displayName] when no package is installed (the 「未安装」 case),
         * which is the wording those screens already used.
         */
        fun displayNameOf(ctx: android.content.Context?, host: HostApp): String {
            if (ctx == null) return host.displayName
            val pkg = host.packages.firstOrNull { pkgInstalled(ctx, it) }
            return host.displayNameFor(pkg)
        }

        /**
         * The installed hosts' brand names as one phrase: 「微信、TIM」.
         *
         * Replaces the `installed(...).joinToString { it.displayName }` idiom at the call sites, which
         * printed 「微信、QQ」 for a TIM user. One function, so the fix cannot be applied to one screen
         * and missed on the next.
         */
        fun installedNames(ctx: android.content.Context?): List<String> {
            if (ctx == null) return emptyList()
            return installed(ctx).map { displayNameOf(ctx, it) }
        }

        /** The hosts the user actually has installed, for the module's own multi-host screens. */
        fun installed(ctx: android.content.Context): List<HostApp> {
            // Sized from REAL_HOSTS, NOT a literal. This was `ArrayList(2)` when there were two hosts,
            // and v6.0.0 made it a lie that would silently under-size the moment a third host appeared
            // (an ArrayList grows, so it was never a crash -- just a stale "2" that misleads the next
            // reader, which is exactly how the "only two hosts" assumption spreads).
            val out = ArrayList<HostApp>(REAL_HOSTS.size)
            for (h in REAL_HOSTS) {
                // ANY package, not just the first. See [versionOf] for why this matters now that QQ
                // owns two (手机QQ and TIM): a TIM-only user must still be listed as having QQ.
                if (h.packages.any { pkgInstalled(ctx, it) }) out.add(h)
            }
            return out
        }

        /** True when [pkg] is installed AND visible to this process. Never throws. */
        private fun pkgInstalled(ctx: android.content.Context, pkg: String): Boolean = try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }

        /**
         * Version name of [host]'s app, or null when it is not installed / not visible.
         *
         * ANY package of the host is tried, not only the first. This became load-bearing in v8.1.0:
         * the QQ host owns TWO packages (手机QQ `com.tencent.mobileqq` and TIM `com.tencent.tim`), and
         * `packages.firstOrNull()` alone would have reported 「QQ 未安装」 on a device that has TIM
         * installed but not 手机QQ -- a wrong answer on the very screen a user reads to decide whether
         * their app is supported.
         *
         * Never throws: the module's own screens must keep working on a device where a host is absent,
         * and `getPackageInfo` raises NameNotFoundException (or a SecurityException on Android 11+
         * without a `queries` entry) rather than returning null.
         */
        fun versionOf(ctx: android.content.Context?, host: HostApp): String? {
            if (ctx == null) return null
            for (pkg in host.packages) {
                try {
                    return ctx.packageManager.getPackageInfo(pkg, 0).versionName
                } catch (_: Throwable) {
                    // try the host's next package
                }
            }
            return null
        }

        /** Picks the host from a list of process names (a clone renames the process, not the package). */
        fun ofProcess(packageName: String?, processName: String?): HostApp {
            val byPkg = of(packageName)
            if (byPkg.isSupported) return byPkg
            // A clone may report the ORIGINAL package in processName (e.g. "com.tencent.mm:appbrand0"),
            // so the suffix after ':' is stripped before the prefix tests.
            val bare = processName?.substringBefore(':')
            return of(bare)
        }
    }
}
