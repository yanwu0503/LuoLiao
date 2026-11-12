package com.luoliaoo.wechat.core

import com.luoliaoo.wechat.BuildConfig

/**
 * Cross-cutting constants shared by the UI and hook layers.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object Constants {

    /**
     * Title of the menu entry we inject. Also used as a view marker: any view inside
     * our own dialog carries this tag, so the menu hook never hijacks it.
     *
     * Why this matters: the scope dialog's confirm button is labelled with the SAME
     * text, and an earlier version hijacked that button -- so tapping it re-opened the
     * dialog instead of running the analysis.
     */
    const val ITEM_TITLE = "\u5206\u6790"

    /** Tag marking view subtrees owned by our own UI. */
    const val TAG_OWN_UI = "luoliao_own_ui"

    /** The injected menu entry id (kept far from WeChat's own small ids). */
    const val ITEM_ID = 0x4C4C01

    /**
     * item NEW: the SECOND injected menu entry -- the floating-balloon on/off switch.
     *
     * Its title is deliberately DIFFERENT from ITEM_TITLE. The click detection works by asking
     * "does this row's subtree display one of OUR titles, and no other menu label?", so two entries
     * sharing one title would be indistinguishable and every tap would be ambiguous -- the 分析 row
     * could switch the balloon and vice versa. Two distinct titles make each row identifiable.
     *
     * The id is a distinct value in the same private range, so the two items never collide.
     */
    const val BALLON_ITEM_ID = 0x4C4C02

    /**
     * item NEW: the label of the floating-panel on/off menu entry.
     *
     * v3.1.1：按用户要求由「落聊悬浮球开关」缩短为「\u6d6e\u7a97」（浮窗）。
     * 标题同时也是**行的身份**，所以它必须继续与 ITEM_TITLE（分析）保持不同 —— 两个标题相同则每次
     * 点击都无法区分是哪一项，这正是 v3.1.0 出现「点分析也切悬浮球」的土壤之一。
     */
    const val BALLON_ITEM_TITLE = "\u6d6e\u7a97"

    /**
     * item NEW: every title this module injects into WeChat's menu.
     *
     * Used by the click detector to tell a row WE own from WeChat's own row (复制 / 转发 / ...) and
     * from the shared container that holds all of them.
     */
    val OWN_MENU_TITLES: List<String> = listOf(ITEM_TITLE, BALLON_ITEM_TITLE)

    /** Which of our entries a menu row belongs to. */
    enum class OwnMenuAction { NONE, ANALYSE, BALLOON_SWITCH }

    /**
     * item NEW: THE pure decision behind the click routing -- which of our entries a row is.
     *
     * Extracted from MenuHook so it can be unit-tested without an Android View hierarchy. This is the
     * single place where the "two entries in one menu" ambiguity is resolved, and the menu hook calls it
     * with the labels it collected from the row's subtree.
     *
     * @param labels     every short text label found in the candidate row's subtree
     * @param othersLabels WeChat's own menu labels (复制 / 转发 / ...)
     *
     * Rules, in order -- each one exists to reject a real failure mode:
     *   1. EXACTLY ONE of our titles must be present. Zero means the row is not ours; TWO means this is
     *      the shared container that holds both entries, and attributing it to either would make the two
     *      entries fire each other's action.
     *   2. No WeChat label may be present. A subtree showing 复制 as well is the container, not our row --
     *      this is the guard that stopped 复制 / 转发 / 删除 from running the analysis.
     *   3. At most two labels in total, which rejects anything that swallowed several rows.
     */
    fun decideOwnMenuAction(labels: Set<String>, othersLabels: Collection<String>): OwnMenuAction {
        if (labels.isEmpty()) return OwnMenuAction.NONE

        val ours = OWN_MENU_TITLES.filter { labels.contains(it) }
        if (ours.isEmpty()) return OwnMenuAction.NONE
        // Two of our titles in one subtree == the shared container, never a single row.
        if (ours.size > 1) return OwnMenuAction.NONE

        for (l in labels) {
            if (othersLabels.contains(l)) return OwnMenuAction.NONE
        }
        if (labels.size > 2) return OwnMenuAction.NONE

        return when (ours[0]) {
            BALLON_ITEM_TITLE -> OwnMenuAction.BALLOON_SWITCH
            ITEM_TITLE -> OwnMenuAction.ANALYSE
            else -> OwnMenuAction.NONE
        }
    }

    /**
     * The MODULE's own package and settings Activity.
     *
     * WHY NOT `Context.getPackageName()`: inside the WeChat process that returns "com.tencent.mm", so
     * building a component name from it produced com.tencent.mm/<our class> -- an Activity that does not
     * exist in WeChat, and startActivity failed with "Unable to find explicit activity class".
     *
     * WHY IT IS `BuildConfig.APPLICATION_ID` AND NOT A LITERAL (v7.0.1): the module now ships TWO
     * flavours, and the debug one carries an `.debug` applicationIdSuffix so it can be installed next to
     * the release build. A literal here would name a package that is NOT the one running, so the debug
     * build would fail to open its own settings screen and to find its own data directory -- with the
     * failure looking like a missing feature rather than a wrong package name. `APPLICATION_ID` is
     * generated per variant, so both flavours are correct by construction.
     *
     * `const` was dropped deliberately: BuildConfig fields for AGP 8 are compile-time constants for the
     * RELEASE variant but not usable in a `const val` here without breaking the debug variant's merge,
     * and these values are read at call time anyway, so nothing depends on inlining them.
     */
    val MODULE_PKG: String = BuildConfig.APPLICATION_ID
    val MODULE_SETTINGS_ACTIVITY: String = BuildConfig.APPLICATION_ID + ".ui.SettingsActivity"
    val MODULE_LAUNCHER_ACTIVITY: String = BuildConfig.APPLICATION_ID + ".ui.LauncherActivity"

    // ==========================================================================================
    // Usage counting (anonymous). Consumed by data/UsageReporter.kt.
    //
    // Kept here so the whole deployment surface is in ONE place: moving the server only ever means
    // editing this block. The knobs the task asked to be configurable -- the endpoint and the report
    // interval -- are USAGE_ENDPOINT and USAGE_INTERVAL_SEC (the statistics window is server-side).
    //
    // PRIVACY: this endpoint receives "someone is using it" and nothing else. The client IP is read
    // by the SERVER from the TCP connection -- the module cannot know its own public IP behind
    // carrier NAT, so it does not send one, and no device id exists anywhere in this path.
    // ==========================================================================================

    /**
     * Where the module reports usage.
     *
     * ⚠️ 必须用 https —— 这是 2026-09-25 在真机上查出 301 之后的结论。
     *
     * 事件经过：先用 http 上报，真机日志显示
     *     usage: HTTP 301
     *     usage: send failed: unexpected end of stream on http://aill.luoyu.ltd/...
     * 原因是服务端已启用 https 并**强制跳转**（http → 301 → https），
     * 而本模块刻意关闭了重定向跟随（见 UsageReporter 的 followRedirects(false)，
     * 那是为了防止地址被劫持跳到别处），于是上报直接失败、统计恒为 0。
     *
     * 现在 https 已可用（证书 CN=aill.luoyu.ltd，SAN 匹配，2026-12-24 到期），
     * 所以直接用 https —— 顺带绕开了 Android 9+ 的明文流量限制
     * （原来用 http 时那个风险是真实存在的：宿主进程的策略我们改不了）。
     *
     * 如果将来证书过期或服务端又变回只支持 http，改这个常量并重新打包即可。
     */
    const val USAGE_ENDPOINT = "https://aill.luoyu.ltd/api/report.php"

    /**
     * Seconds between reports.
     *
     * 这个周期同时也是**检查更新的周期** —— 版本结论由服务端放在上报响应里
     * 一起带回（见 UsageReporter.handleUpdate），所以不必单独发请求。
     *
     * 60 秒是需求要求的：官网「当前使用人数」按 1 小时窗口统计，
     * 上报越密只会增加服务端压力，对数字没有帮助。
     * 更新提示的实时性也因此是「最多 1 分钟延迟」，足够。
     */
    const val USAGE_INTERVAL_SEC = 60L

    /**
     * Whether to report at all.
     *
     * A build-time switch, so a user who does not want to be counted is not forced to: setting this
     * to false and rebuilding disables the feature entirely, with no other change needed anywhere.
     */
    const val USAGE_ENABLED = true

    /** Builds an Intent that opens our config page from any process. */
    fun settingsIntent(): android.content.Intent =
        android.content.Intent().apply {
            setClassName(MODULE_PKG, MODULE_SETTINGS_ACTIVITY)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
}
