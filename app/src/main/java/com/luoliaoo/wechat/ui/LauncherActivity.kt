package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Entry page, shown when the module icon is tapped.
 *
 * Styled after Nuke's WebUI: a dark canvas with a blue glow, an eyebrow label, a heavy title, feature
 * panels and a single bright call to action.
 */
class LauncherActivity : Activity() {

    /**
     * 液态玻璃：系统在深浅模式之间切换时，把这一屏整个重建。
     *
     * 为什么需要它：模块的界面全部由 Kotlin 代码按当前主题绘制（NukeTheme），已经画好的视图
     * 不会自己变色。默认情况下 Activity 会因 uiMode 变化被系统重建，所以通常不需要这个回调；
     * 但如果宿主（或某些 ROM）声明了 `configChanges="uiMode"`，系统就**不会**重建，界面会停在
     * 旧主题上 —— 这正是审计报告指出的缺口（全项目此前 0 处 onConfigurationChanged）。
     *
     * 这里显式重建，保证「切了系统深色模式，界面一定跟着变」这条链路没有断点。
     * 用 recreate() 而不是手动 re-buildUi()：重建走正常的生命周期，任何后续新增的初始化逻辑
     * 都会自动被执行，不会漏掉。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            // 先让主题标志跟随新配置，再请求重建，避免重建出来的界面仍是旧色。
            NukeTheme.syncTheme(this)
            recreate()
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "onConfigurationChanged failed: " + t.message)
        }
    }

    /**
     * 5.0.0: the host the module's OWN screens speak for.
     *
     * These screens are never injected by LSPosed, so `HostApp.current` is only the default and cannot
     * say which app the user actually uses. Resolution is therefore "the first host that is actually
     * installed" (WeChat first, then Douyin), which is what makes a Douyin-only install read 抖音 here.
     */
    private val host: com.luoliaoo.wechat.core.HostApp
        get() = com.luoliaoo.wechat.core.HostApp.installed(this)
            .firstOrNull() ?: com.luoliaoo.wechat.core.HostApp.ui

    /** [text] with 微信 / 微信号 rewritten for [host]. */
    private fun h(text: String): String = host.say(text)

    /**
     * The hosts to show a version badge for: every INSTALLED host, or both when none is.
     *
     * Both branches are deliberate. A user with 微信 + 抖音 must see both versions; a user with neither
     * must still see 「微信 未安装」/「抖音 未安装」, because that is exactly the symptom of a missing
     * `<queries>` declaration, and an empty row would hide the fault.
     */
    private fun badgeHosts(): List<com.luoliaoo.wechat.core.HostApp> =
        com.luoliaoo.wechat.core.HostApp.installed(this)
            .ifEmpty { com.luoliaoo.wechat.core.HostApp.REAL_HOSTS }

    /**
     * The host names as one phrase: 「微信」/「抖音」/「微信、抖音」.
     *
     * Used in the header and the guide steps so those sentences never depend on a single host -- a
     * 微信-only user reads exactly the historical wording, while a user with both is told about both.
     */
    private fun hostNames(): String =
        com.luoliaoo.wechat.core.HostApp.installedNames(this)
            .ifEmpty { listOf(com.luoliaoo.wechat.core.HostApp.displayNameOf(this, host)) }
            .joinToString("\u3001")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NukeTheme.canvas(this@LauncherActivity)
            setPadding(dp(22), dp(40), dp(22), dp(28))
        }

        // v5.0.0: the eyebrow names the hosts the module serves rather than the literal "WECHAT", which
        // was wrong the moment a second host existed. Both installed hosts are named so a user running
        // 微信 + 抖音 is not told the page is about only one of them.
        root.addView(NukeTheme.eyebrow(this, hostNames() + " \u00b7 AI \u5206\u6790"))
        root.addView(NukeTheme.title(this, "\u843d\u804a", 32f))
        root.addView(NukeTheme.body(
            this,
            // Host-neutral: the sentence names BOTH hosts instead of assuming one.
            "\u957f\u6309" + hostNames() + "\u6d88\u606f \u2192 \u5206\u6790\n" +
                "AI \u5e2e\u4f60\u5224\u65ad\u5bf9\u65b9\u7684\u771f\u5b9e\u610f\u56fe\u4e0e\u751f\u6c14\u7b49\u7ea7",
            NukeTheme.TEXT_MUTED,
            15f,
        ).apply { setPadding(0, dp(10), 0, 0) })

        // ---- status chip
        val statusWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        statusWrap.addView(NukeTheme.badge(this, "LSPosed"))
        // 5.0.0: one badge per INSTALLED host, so both 微信 and 抖音 are surfaced when both are present.
        // Versions are READ, never hard-coded -- the old badge claimed 8.0.77 even on 8.0.68.
        //
        // When NOTHING is installed the two hosts are shown as 未安装 instead: that is the original
        // diagnostic (the manifest's "微信未安装" bug), and an empty badge row would hide a broken
        // <queries> declaration behind a screen that simply looks short.
        for (h in badgeHosts()) {
            statusWrap.addView(
                NukeTheme.badge(
                    this,
                    com.luoliaoo.wechat.core.HostApp.label(
                        h, com.luoliaoo.wechat.core.HostApp.versionOf(this, h),
                    ),
                ),
            )
        }
        statusWrap.addView(NukeTheme.badge(this, "API 102"))
        root.addView(statusWrap)

        // ============================================================ MAIN_ENV_UI (item 4)
        //
        // The user asked for this on the MAIN screen, not in the settings page. It sits directly under the
        // badge row so every version number is in one place.
        //
        // All of these come from android.os.Build, which needs NO permission. The WeChat version above needs
        // the <queries> declaration, which is the "微信未安装" fix.
        val infoCard = NukeTheme.sectionCard(this)
        infoCard.addView(NukeTheme.category(this, "\u73af\u5883\u4fe1\u606f"))
        infoCard.addView(
            TextView(this).apply {
                text = buildEnvText()
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        // The CONTACT block, in RED. QQ is marked as preferred, and the four values to send are listed so a
        // user reporting an incompatible WeChat build knows exactly what to include.
        //
        // 液态玻璃升级：原先这里写死 0xFFFF3B30（iOS 系统红）。那是**为深色底挑的颜色**，
        // 一旦切到浅色主题，这个红在近白玻璃上对比度只有约 3.2:1，不满足 WCAG AA 正文要求。
        // 改用主题的语义危险色：它由 NukeTheme 按明暗各给一套，两套都经过对比度校验。
        val CONTACT_RED = NukeTheme.DANGER_TEXT

        infoCard.addView(
            TextView(this).apply {
                text = "TG\uff1aLUO1314AI\n" +
                    "QQ\uff1a628843841\uff08\u4f18\u5148\uff09\n" +
                    "\u9177\u5b89\uff1a\u8a00\u65e00503"
                setTextColor(CONTACT_RED)
                textSize = NukeTheme.sp(ctx, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(4))
            },
        )
        infoCard.addView(
            TextView(this).apply {
                text = h("\u6a21\u5757\u65e0\u6cd5\u4f7f\u7528\uff1f\u8fdb\u4f01\u9e45\u7fa4" +
                    "\u53d1\u5fae\u4fe1\u5b89\u88c5\u5305\u5373\u53ef\u517c\u5bb9\n") +
                    h("\u53d1\uff1a\u5fae\u4fe1\u5305\u7248\u672c \u00b7 \u624b\u673a\u578b\u53f7 \u00b7 ") +
                    "\u5b89\u5353\u7248\u672c \u00b7 LspAPI \u7248\u672c\n" +
                    "\u544a\u77e5\u4ee5\u4e0a\u56db\u4e2a\u5373\u53ef\uff0c\u8010\u5fc3\u7b49\u5f85" +
                    "\u517c\u5bb9\uff08\u5f88\u5feb\uff09"
                setTextColor(CONTACT_RED)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        root.addView(infoCard)

        // 「打开配置」 is REMOVED: configuration happens where the user actually configures it --
        // 微信 -> 长按消息 -> 分析 -> 配置 -> 模板管理. This screen is a guide, not a settings entry.
                // v2.6.0: the 调试 screen was REMOVED.
        //
        // It reported counters from the MODULE's process while the hooks run in WECHAT's, so its
        // "self-check" always read zeros, and its log view scanned nine candidate paths of which most
        // cannot exist. Logging is now the module's own file in WeChat's directory -- the LSPosed
        // convention -- which a file manager or the LSPosed log can both reach.

        // ---- a feature panel holding the usage steps
        val steps = NukeTheme.sectionCard(this)
        steps.addView(NukeTheme.category(this, "\u4f7f\u7528\u6b65\u9aa4"))
        steps.addView(TextView(this).apply {
            text = "\u5982\u4f55\u5f00\u59cb"
            setTextColor(NukeTheme.TEXT)
            textSize = NukeTheme.sp(ctx, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(6))
        })
        // The steps the user specified, verbatim, numbered and spaced so they are readable at a glance.
        // 5.0.0: steps 1-3 name EVERY installed host instead of one of them. A Douyin-only user is told
        // to tick and restart 抖音; a user with both is told about both; a 微信-only user reads exactly
        // the historical wording, because hostNames() then returns 微信 alone.
        val hm = hostNames()
        val stepLines = arrayOf(
            "1\uff1a\u5728 LSPosed \u4e2d\u542f\u7528 \u5e76\u52fe\u9009" + hm,
            "2\uff1a\u5f3a\u5236\u505c\u6b62" + hm + "\u540e\u91cd\u65b0\u6253\u5f00/\u91cd\u542f\u624b\u673a",
            "3\uff1a\u6253\u5f00" + hm + "\u957f\u6309\u4e00\u6761\u4fe1\u606f\u70b9\u51fb\u5206\u6790\u70b9\u51fb\u914d\u7f6e AI \u7684 Url \u4ee5\u53ca key \u4ee5\u53ca\u6a21\u578b",
            "4\uff1a\u70b9\u51fb\u5206\u6790\u9009\u62e9\u597d\u540e\u70b9\u51fb\u786e\u8ba4\u5373\u53ef\u8fdb\u884c\u5206\u6790",
            "5\uff1a\u70b9\u51fb\u60ac\u6d6e\u7a97\u6253\u5f00\u754c\u9762 \u5728\u8054\u7cfb\u4eba\u5bf9\u8bdd\u5904\u4f1a\u81ea\u52a8\u5224\u65ad \u4ec5\u663e\u793a\u8be5\u8054\u7cfb\u4eba\u5386\u53f2\u7684\u8bb0\u5f55 \u907f\u514d\u6742\u4e71",
            "6\uff1a\u70b9\u51fb\u60ac\u6d6e\u7403\u8fdb\u884c \u6253\u5f00/\u5173\u95ed \u754c\u9762",
            "7\uff1a\u957f\u6309\u60ac\u6d6e\u7403 \u4f18\u5316\u804a\u5929\u8f93\u5165\u6846\u5185\u7684\u6587\u5b57 " +
                "\u7ed9\u51fa\u4e24\u4e2a\u65b9\u6848 \u786e\u5b9a\u540e\u76f4\u63a5\u5199\u56de\u8f93\u5165\u6846",
        )
        for (i in stepLines.indices) {
            val line = stepLines[i]
            steps.addView(
                TextView(this).apply {
                    text = line
                    setTextColor(NukeTheme.TEXT_DIM)
                    textSize = NukeTheme.sp(this@LauncherActivity, 13f)
                    setLineSpacing(dp(5).toFloat(), 1.3f)
                    setPadding(0, if (i == 0) dp(8) else dp(10), 0, 0)
                },
            )
        }
        root.addView(steps, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) })

        // ---- v10.1.0: 优化模板卡片从这里**移除**（用户要求「APP界面 指 打开app 内的优化模板移除」）。
        //
        // 【为什么可以安全移除】它不是这个功能的唯一入口：优化模板依然可以在
        // **微信内**的「文本优化」对话框里管理（PolishTemplateDialog.show 的另一个调用点）。
        // 也就是说用户只是想在这块"安装说明"页面上不再看到它，而不是要删掉这个功能，
        // 所以这里只删入口卡片，PolishTemplateDialog 本体与另一处入口**保持不动**。
        //
        // 原来的代码在此处创建 polishCard + 「管理优化模板」按钮，并调用 polishSummary()
        // 显示模板数量。两者一并删除，polishSummary() 也随之下线（见文件末尾的说明）。

        setContentView(ScrollView(this).apply {
            background = NukeTheme.canvas(this@LauncherActivity)
            addView(root)
        })

        // Keep the mirror file fresh: it is a fallback config channel for the WeChat
        // process, whose own preference redirect can be unavailable.
        writeMirrorOnStart()

        // item: the SHARED writer covers every location this android user can reach,
        // including the ones the WeChat process of the same user reads. The private writer below
        // only wrote our own data dir, which WeChat cannot see -- and a clone could never see it.
        try {
            com.luoliaoo.wechat.data.ConfigRepo.writeMirror(
                this,
                com.luoliaoo.wechat.data.ConfigRepo.load(this),
            )
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "shared mirror failed: " + t.message)
        }
        // item 3: templates are edited in this process, so the store is attached here.
        try {
            com.luoliaoo.wechat.core.DebugLog.attach(this)
            com.luoliaoo.wechat.core.DebugLog.setEnabled(
                com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this).debugLog,
            )
        } catch (_: Throwable) {
            // ignore
        }
        try {
            com.luoliaoo.wechat.data.TemplateStore.attachFrom(this)
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "template store attach failed: " + t.message)
        }
        // v4.0.0: the text-polish template store, attached alongside the analysis one so both are usable
        // the moment the module app opens.
        try {
            com.luoliaoo.wechat.data.PolishTemplateStore.attachFrom(this)
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "polish template store attach failed: " + t.message)
        }
        // v4.1.0: the per-template Skill store, attached here too so the module app's own process files
        // skills in the SAME place the WeChat process reads them from.
        try {
            com.luoliaoo.wechat.data.SkillStore.attachFrom(this)
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "skill store attach failed: " + t.message)
        }
    }

    // v10.1.0: polishSummary() 已随「优化模板」卡片一并移除 —— 它是那张卡片的唯一使用者。
    // 注意 PolishTemplateStore.attachFrom(this)（见 init 里）**必须保留**：
    // 它负责把优化模板存储挂到本进程，微信进程的「文本优化」对话框仍然读它。
    // 删入口卡片 ≠ 删功能，这两件事在这里被分得很清楚。

    /**
     * item 4: the environment block shown on the main screen.
     *
     * `android.os.Build` needs no permission, so these values are always populated -- unlike the WeChat
     * version, which requires the <queries> declaration.
     */
    private fun buildEnvText(): String = buildString {
        append("\u624b\u673a\u578b\u53f7=").append(android.os.Build.MANUFACTURER)
            .append(' ').append(android.os.Build.MODEL).append('\n')
        append("\u624b\u673a\u7cfb\u7edf\u7248\u672c=").append(android.os.Build.DISPLAY).append('\n')
        append("\u5b89\u5353\u7248\u672c=").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(')').append('\n')
        append("LSPosed API 102").append('\n')
        // 5.0.0: one line per host, so the reported version says WHICH app it belongs to. The old single
        // 「微信版本=…」 line was ambiguous the moment a second host existed.
        for ((hostApp, version) in com.luoliaoo.wechat.core.HostApp.versionBadges(this@LauncherActivity)) {
            // v8.1.0: the BRAND actually installed, not the host's default name -- otherwise a TIM user
            // reads 「QQ版本=4.1.0」 for an app they do not have.
            append(com.luoliaoo.wechat.core.HostApp.displayNameOf(this@LauncherActivity, hostApp))
                .append("\u7248\u672c=")
                .append(version?.takeIf { it.isNotBlank() }
                    ?: com.luoliaoo.wechat.core.HostApp.NOT_INSTALLED)
                .append('\n')
        }
        append("\u6a21\u5757\u7248\u672c=").append(com.luoliaoo.wechat.BuildInfo.VERSION_NAME)
    }

    /** Writes the config mirror on every launch of the module UI. */
    private fun writeMirrorOnStart() {
        try {
            val c = com.luoliaoo.wechat.data.ConfigRepo.load(this)
            val f = java.io.File(filesDir, "luoliao_config.txt")
            val sb = StringBuilder()
            sb.append("base_url=").append(c.baseUrl).append('\n')
            sb.append("api_key=").append(c.apiKey).append('\n')
            sb.append("model=").append(c.model).append('\n')
            sb.append("system_prompt=")
                .append(c.systemPrompt.replace("\n", "\\n")).append('\n')
            sb.append("default_up=").append(c.defaultUp).append('\n')
            sb.append("default_down=").append(c.defaultDown).append('\n')
            sb.append("default_range_hours=").append(c.defaultRangeHours).append('\n')
            f.writeText(sb.toString())
            com.luoliaoo.wechat.core.Log.i(
                "LuoLiao", "mirror written on start: " + f.absolutePath + " len=" + f.length(),
            )
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w("LuoLiao", "mirror write on start failed: " + t.message)
        }
    }
}
