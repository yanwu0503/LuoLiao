package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.BuildInfo
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.HostContext
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.UpdateState

/**
 * ★ v9.9.9：更新提示的**作用域守卫**。
 *
 * 用户要求：「更新检测弹窗 仅在作用域app时显示 在其他任何地方都不会显示」。
 *
 * ─────────────────────────────────────────────────────────────
 * 【修的是什么问题】
 *
 * 原实现有**两条**显示路径（见 [UpdateDialog.maybeShow]）：
 *   ① 宿主 Activity 上的 Dialog —— 这条本来就是宿主内的，没问题；
 *   ② **系统悬浮窗**（`OverlayHost`，`TYPE_APPLICATION_OVERLAY`）—— 这条是兜底路径。
 *
 * 问题出在 ②：系统悬浮窗浮在**整个系统**之上。用户把微信切到后台、回到桌面、
 * 打开别的 App 之后，那个更新窗**依然挂在屏幕上**，点掉之前一直在。
 * 而且它来自模块，用户根本不知道它是谁弹的 —— 这就是「在其他任何地方都会显示」。
 *
 * ─────────────────────────────────────────────────────────────
 * 【判定标准：什么算「作用域 app」】
 *
 * 有两层含义，两层都要满足：
 *   a) **本进程是被 LSPosed 注入的宿主进程**。用 `HostApp.current.isSupported` 判断 ——
 *      它由 `ModuleEntry.handleLoadPackage` 在装入每个进程时设置，
 *      模块**自己的** App（SettingsActivity / LauncherActivity）不会被注入，
 *      因此 `setCurrent` 从不执行，`current` 保持默认值。
 *   b) **该宿主 App 此刻正在前台**。这一条要用系统 API 查实时前台包名，
 *      因为进程活着不等于它在前台（切到后台后进程仍在，悬浮窗也会仍在）。
 *
 * ⚠️ 关于 (a) 的一个**必须记住的陷阱**：`HostApp.current` 的默认值是 `WECHAT`
 *    （见 HostApp.kt 第 577 行及其注释，那个默认值是**故意**的，为了模块自己的界面
 *    不显示空标签）。所以**不能**只靠 `current.isSupported` 判断 ——
 *    在模块自己的进程里它也是 true。真正可靠的做法是看 `ModuleEntry` 是否真的执行过。
 *    本文件用 [com.luoliaoo.wechat.ModuleEntry.isHostProcess] 这个由入口点设置的标记来区分。
 */
private object UpdateScopeGuard {

    private const val TAG = "LuoLiao"

    /**
     * 宿主 App 此刻是否处于**前台**。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么不能用 getRunningAppProcesses()】
     * 我最初就是这么写的，它是**错的**，记在这里以免以后有人再写一遍：
     * 从 Android 5.1 / 6.0 起系统收紧了权限 —— `getRunningAppProcesses()`
     * **只返回调用者自己的进程**。我们在宿主进程里调用，返回值里就只有宿主自己，
     * 其 `importance` 必然是 FOREGROUND，于是这个判断**永远为真**，等于完全没做检查。
     * 这类「看着在工作、其实空转」的代码是最难发现的，所以明确留档。
     *
     * 【正确做法：看宿主有没有「非 paused 的 Activity」】
     * 宿主的 Activity 全部 paused 就意味着它已经退到后台。这正是
     * `UsageReporter.hostActivity()` 用的同一套机制（走 `ActivityThread.mActivities`），
     * 不需要任何系统权限，也不受上面的限制影响。
     *
     * ⚠️ 两者**必须**用同一个信号源：否则会出现「守卫判定在前台、
     * 但取 Activity 又取不到」的自相矛盾状态（那种状态下代码会退到系统悬浮窗 —— 正是要避免的）。
     *
     * @return true = 前台（有 Activity 正在显示）
     */
    private fun hostIsForeground(): Boolean = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
        val f = at.getDeclaredField("mActivities")
        f.isAccessible = true
        /* ★★ v9.9.9 关键修复：必须按 **Map** 接收，不能按 HashMap。
         *
         * 真机实测（Android 17）打出来的类型是 `android.util.ArrayMap`：
         *     mActivities raw={BinderProxy@...=ActivityRecord{...LauncherUI}}
         *     mActivities class=android.util.ArrayMap
         * 而本文件原来写的是 `as? java.util.HashMap<*, *>` ——
         * ArrayMap **不是** HashMap 的子类，这个转换会**静默返回 null**
         * （`as?` 不抛异常），随后 `?.values` 得到 null，
         * 于是「读不到任何 Activity」和「宿主真的没有 Activity」变得无法区分，
         * 被外层 catch 一吞，表现为「宿主不在前台 / 拿不到 Activity」。
         *
         * ArrayMap 和 HashMap 唯一的共同祖先是 `java.util.Map`，
         * 所以这里必须退到 Map —— 这也是全模块唯一正确的接法。
         * 同一个错误在 UsageReporter / MenuHook / DouyinHooks 里都有，已一并修掉。 */
        val entries = (f.get(thread) as? java.util.Map<*, *>)?.values()
        var found = false
        entries?.forEach { rec ->
            try {
                if (rec == null) return@forEach
                /* ActivityClientRecord.paused == false 表示它正显示在前台。
                   字段名从 API 1 至今没变过（与 hostActivity() 同一依据）。 */
                val pf = rec.javaClass.getDeclaredField("paused")
                pf.isAccessible = true
                if ((pf.get(rec) as? Boolean) == false) found = true
            } catch (_: Throwable) {
                // 单个 record 读不出来不影响其它 record
            }
        }
        found
    } catch (t: Throwable) {
        Log.w(TAG, "update-scope: cannot check the foreground state: " + t.message)
        false
    }

    /**
     * 现在允许弹更新提示吗？
     *
     * 三道检查全部通过才返回 true；任何一道不通过都**抑制**（不是「延后」——
     * 后台路径每分钟会重新走一遍，所以抑制的自然结果就是「等下次机会」）。
     *
     * @param hostPkg 当前进程所属的包名（通常是宿主自己）
     * @return true = 允许
     */
    fun allowed(hostPkg: String?): Boolean {
        /* ① 必须是真正被注入了钩子的宿主进程。
            用 ModuleEntry.isHostProcess 而不是 HostApp.current.isSupported —— 原因见上面的注释。 */
        if (!com.luoliaoo.wechat.ModuleEntry.isHostProcess) {
            Log.i(TAG, "update-scope: not a host process (module's own UI); no prompt")
            return false
        }

        /* ② 这个包必须是我们支持的宿主之一（用户勾了别的 App 也不会弹）。 */
        if (com.luoliaoo.wechat.core.HostApp.of(hostPkg) ==
            com.luoliaoo.wechat.core.HostApp.UNKNOWN
        ) {
            Log.i(TAG, "update-scope: host is not one of our scope apps; no prompt")
            return false
        }

        /* ③ 该 App 必须正在前台 —— 这是本次修复的核心：
            后台运行时不再弹系统悬浮窗（那条路径会浮在整系统之上，切到任何 App 都还看得见）。 */
        if (!hostIsForeground()) {
            Log.i(TAG, "update-scope: host is not in the foreground; not prompting")
            return false
        }
        return true
    }
}

/**
 * 新版本提示弹窗。
 *
 * 触发方式：模块定时上报「有人在用」时，服务端在同一个响应里告诉我们
 * 「最新版是 x.y.z」；若比当前版本新，就在宿主里弹窗。
 *
 * 两个按钮：
 *   · 点我下载   —— 打开浏览器跳转到下载页，并清掉静默状态
 *   · 暂不下载   —— 记住「跳过的这个版本号」，24 小时内不再提示它
 *
 * 「只提示一次」是按**版本号**记的，不是一个布尔开关 —— 这一点很关键：
 *   用户跳过了 v1 之后，如果官方发布了 v2，应该再提示一次。
 *   用布尔开关 + 宿主进程长期存活（微信可以几天不重启）会导致
 *   「跳过 v1 之后，v2/v3 全都收不到提示」，用户被永久困在旧版本上。
 *
 * 设计上刻意「不挡路」：
 *   · 可以点窗口外部关掉，不会把用户困在弹窗里；
 *   · 不劫持宿主任何按钮，纯粹是我们自己的一个窗口；
 *   · 整个流程包在 try/catch 里 —— 更新提示出任何问题都绝不能影响
 *     用户正常用微信/抖音，那比「收不到更新提示」严重得多。
 */
object UpdateDialog {

    private const val TAG = "LuoLiao"

    /**
     * 本进程内已经提示过的**版本号**。
     *
     * 用版本号而不是 true/false：同一版本不重复打扰，
     * 但官方一发新版本就能立刻再提示（配合 UpdateState 的 24 小时静默）。
     */
    @Volatile
    private var shownVersion: String? = null

    /**
     * 有更新时尝试弹窗。
     *
     * @param activity 宿主当前显示的 Activity，可能为 null（后台进程/切到后台时）
     * @param latest   服务端给的最新版本号
     * @param url      下载地址
     * @param whatsNew 更新说明，可为空
     * @param force    是否强制更新（低于服务端配置的最低版本时）
     *
     * 整个方法永不抛异常：调用点在上报线程里，
     * 一个未捕获的异常会静默杀掉那条线程，之后就再也不上报了。
     */
    fun maybeShow(
        activity: Activity?,
        latest: String,
        url: String,
        whatsNew: String,
        force: Boolean,
    ) {
        try {
            if (latest.isBlank()) return
            if (url.isBlank()) {
                Log.w(TAG, "update: new version $latest but no download url configured, skip")
                return
            }

            /* 同一个版本只提示一次。force 时连这个也跳过（强制更新要反复提醒）。 */
            if (!force && shownVersion == latest) {
                Log.i(TAG, "update: version $latest already prompted in this process, skip")
                return
            }

            /* Context 必须有 —— 读「是否处于静默期」也要用它。
               优先 Activity（弹窗观感最好），退一步用 Application。 */
            val app: Context = activity?.applicationContext
                ?: HostContext.application()
                ?: run {
                    Log.i(TAG, "update: no context at all, will retry on the next check")
                    return
                }

            /* 24 小时静默：只有「跳过的就是当前这个 latest」时才生效。
               官方一发新版本，这里就会返回 true，于是重新提示。 */
            if (!force && !UpdateState.shouldPrompt(app, latest)) {
                Log.i(TAG, "update: $latest silenced for 24h, skip")
                return
            }

            /* ★ v9.9.9：作用域守卫 —— 只在作用域 App 里显示（用户要求）。
               放在这里：已在上面拿到可用的 Context（`app`），且**在决定用哪种方式显示之前** ——
               因为要拦的正是下面那条 OverlayHost（系统悬浮窗）兜底路径，
               那条会把窗浮在整个系统上，切到任何 App 都还看得见。
               注意它排在静默期判断**之后**：静默期只是个布尔短路，顺序不影响结果，
               而放在这里可以复用上面已经校验过的 Context，不必再取一次。 */
            val hostPkg = try {
                app.packageName
            } catch (_: Throwable) {
                null
            }
            if (!UpdateScopeGuard.allowed(hostPkg)) return

            val ctx: Context = unwrap(activity ?: app)
            OwnUi.suppressFor(2500L)

            /* 「怎么关掉这个提示」由实际显示方式决定（Dialog 用 dismiss，
               悬浮窗用 removeView），所以先做一个可变容器：按钮被点击时读它。
               面板必须在知道显示方式之前就把点击写进按钮，所以不能直接传闭包。 */
            val closer = Closer()
            val panel = buildPanel(ctx, latest, url, whatsNew, force, closer)

            /* 优先挂在宿主 Activity 上 —— 观感最好、最不容易被系统限制。
               后台进程没有 Activity，就退到系统悬浮窗。 */
            val act = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (act != null && showAsDialog(act, panel, closer)) {
                shownVersion = latest
                Log.i(TAG, "update: prompt shown via dialog (current=" +
                    BuildInfo.VERSION_NAME + " latest=$latest force=$force)")
                return
            }

            if (OverlayHost.canShow()) {
                /* 先把关闭动作设好再显示：显示成功后再设也能工作，
                   但中间那一瞬间如果用户手速极快点了按钮，就没有动作可执行。
                   先设后显更严谨，且没有额外代价。 */
                closer.set { OverlayHost.hide() }
                if (OverlayHost.show(panel)) {
                    shownVersion = latest
                    Log.i(TAG, "update: prompt shown via overlay (current=" +
                        BuildInfo.VERSION_NAME + " latest=$latest force=$force)")
                    return
                }
            }

            /* 两种方式都没成功。**不记 shownVersion**，下一轮继续试 ——
               这是实测踩到的坑：后台进程第一次拿到 Context 时 Activity 还是 null，
               若此刻就标记「已提示」，新版就再也弹不出来了。 */
            Log.i(TAG, "update: no host activity and no overlay permission, will retry later")
        } catch (t: Throwable) {
            Log.e(TAG, "update: prompt failed (ignored to protect the host): " + t.message, t)
        }
    }

    /**
     * 用户**主动**点「点我更新」并且服务端确认有新版本时调用。
     *
     * 【为什么不能直接复用 maybeShow】maybeShow 是给「后台自动提示」用的，它有两道
     * 刻意的拦截：
     *   ① `!force && shownVersion == latest` —— 同一版本本次进程内只提示一次
     *   ② `!force && !UpdateState.shouldPrompt(...)` —— 用户点过「暂不下载」后 24 小时静默
     * 这两道对后台提示是对的（否则每分钟轮询会反复骚扰用户），
     * 但**放到用户主动点击上就变成了 bug**：用户明明点了「点我更新」，
     * 却因为自己昨天点过「暂不下载」而**什么都没发生** —— 这就是「点了没反应」，
     * 是本模块明确要消灭的体验缺陷。
     *
     * 【修法】写一个专用入口，**无条件**显示（跳过上面两道），
     * 因为「用户刚刚亲口问了一次」本身就是最强的意图表达，压过任何历史静默记录。
     * 同时**特意不借用 maybeShow 的 force 参数**：force 除了跳过拦截，
     * 还会让面板多显示一个「强制更新」警告框、并且不再记静默期 ——
     * 那是「版本过低必须升级」的语义，和「用户主动查了一下」不是一回事。
     * 借用它会把一个普通更新伪装成强制更新，误导用户。
     *
     * @param force 由服务端决定（低于最低支持版本时为 true），如实透传，不篡改
     * @return 是否真的把更新窗显示出来了。
     *   **调用方应当检查它**：为 false 时说明宿主此刻没有可用的 Activity、
     *   也没有悬浮窗权限（或参数为空），此时应给用户一句提示，
     *   而不是让「点我更新」看起来毫无反应 —— 那正是本模块要消灭的体验缺陷。
     */
    fun showForManualCheck(
        activity: Activity?,
        latest: String,
        url: String,
        whatsNew: String,
        force: Boolean,
    ): Boolean {
        try {
            /* ★★ v9.0.1（用户实测反馈后修改）：
               这里原先要求 `url` 也非空，否则直接返回 false。

               但真机上暴露了问题：服务端的 LL_LATEST_VERSION 还是 8.1.0、
               而本机是 9.0.1，两者不一致 → 按用户第 7 项规则算「有更新」，
               可服务端**没配下载地址** → 弹窗被这里挡掉 →
               用户点了「点我更新」只看到一句「无法弹出更新窗」，
               **看不到版本对比、也看不到更新说明**。

               用户明确要求改成：「弹窗出来，但按钮改为去官网」。
               所以现在只要 latest 非空就显示弹窗；没有下载地址时，
               按钮文案从「点我下载」变成「去官网」，点击改为打开**官网首页**
               （模块自己的站点，恒定可用），而不是那个空的下载链接。 */
            if (latest.isBlank()) {
                Log.w(TAG, "manual update: latest blank, nothing to show")
                return false
            }

            /* ★ v9.9.9：作用域守卫。
               这一条在**手动路径**上理论上永远不会拦住任何合法操作 ——
               因为本方法只被悬浮面板里的「点我更新」按钮调用，
               而面板本身只有在宿主界面里才存在（用户得先能点到那个按钮）。
               也就是说：能调到这里，必然人在作用域 App 里。

               既然如此为什么还要加？
               因为下面同样有一条 `OverlayHost`（系统悬浮窗）兜底路径 ——
               若 Activity 恰好为空就会走它，而系统悬浮窗会浮在整系统之上。
               用户的要求是「在其他任何地方都不会显示」，
               那就不能依赖「调用方一定在前台」这个**间接**前提：
               一旦将来有新的调用点（比如从设置 App 里也放一个检查按钮），
               那条间接前提就悄悄失效了，而这种失效不会有任何报错。
               直接判一次，成本是几次反射读，换来的是这条不变量**自带保证**。 */
            val manualHostPkg = try {
                activity?.applicationContext?.packageName
                    ?: HostContext.application()?.packageName
            } catch (_: Throwable) {
                null
            }
            if (!UpdateScopeGuard.allowed(manualHostPkg)) return false

            val app: Context = activity?.applicationContext
                ?: HostContext.application()
                ?: run {
                    Log.i(TAG, "manual update: no context at all")
                    return false
                }

            val ctx: Context = unwrap(activity ?: app)
            OwnUi.suppressFor(2500L)

            val closer = Closer()
            val panel = buildPanel(ctx, latest, url, whatsNew, force, closer)

            val act = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (act != null && showAsDialog(act, panel, closer)) {
                /* 记下 shownVersion，避免用户关掉之后后台又立刻弹一次同一个版本。
                   注意这里记它只是为了「不重复」，与 maybeShow 的拦截语义不同：
                   我们跳过的只是「同一版本重复提示」，不是「用户没资格看」。 */
                shownVersion = latest
                Log.i(TAG, "manual update: shown via dialog (latest=$latest force=$force)")
                return true
            }

            if (OverlayHost.canShow()) {
                closer.set { OverlayHost.hide() }
                if (OverlayHost.show(panel)) {
                    shownVersion = latest
                    Log.i(TAG, "manual update: shown via overlay (latest=$latest force=$force)")
                    return true
                }
            }

            /* 两种方式都失败 —— 返回 false，让调用方给用户一句提示，
               不能静默。不记 shownVersion：万一只是这一刻没有 Activity，
               用户稍后再点一次应该还能看到。 */
            Log.w(TAG, "manual update: could not display (no activity, no overlay)")
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "manual update: failed (ignored to protect the host): " + t.message, t)
            return false
        }
    }

    /**
     * 「怎么关掉这个提示」的可变容器。
     *
     * 存在的理由：按钮的点击逻辑必须在**创建面板时**就绑定好，
     * 但此刻还不知道最终会用 Dialog 还是悬浮窗显示 ——
     * 两种方式的关闭方法完全不同。于是按钮统一调用这个容器的 close()，
     * 而由显示方式在成功后填入真正的动作。
     */
    private class Closer {
        @Volatile
        private var action: (() -> Unit)? = null

        fun set(a: () -> Unit) {
            action = a
        }

        fun close() {
            try {
                action?.invoke()
            } catch (_: Throwable) {
                // 关不掉也不能崩：这是提示窗，不是主功能
            }
        }
    }

    /** 宿主进程切换时调用，让提示状态跟着重置。 */
    fun resetForNewProcess() {
        shownVersion = null
    }

    /**
     * 组装提示面板（Dialog 与悬浮窗共用同一份 UI，避免两处各写一遍而走样）。
     *
     * @param closeRef 由调用方在成功显示后填入「关闭」动作，按钮被点击时调用它。
     * @return 可以直接交给 Dialog.setContentView 或 WindowManager.addView 的根视图。
     */
    private fun buildPanel(
        ctx: Context,
        latest: String,
        url: String,
        whatsNew: String,
        force: Boolean,
        closer: Closer,
    ): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
            background = NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, ctx)
            setPadding(dp(20), dp(20), dp(20), dp(14))
            tag = Constants.TAG_OWN_UI
        }

        content.addView(NukeTheme.eyebrow(ctx, "落聊 · 新版本"))

        // 液态玻璃化：主标题改用 NukeTheme.title 工厂。
        // 原来是手写 TextView + NukeTheme.sp(ctx, 18f) + DEFAULT_BOLD —— sp 是对的，
        // 但字体、行距、颜色都另写一遍，与其它对话框的标题不在同一套规则里。
        // 换成工厂后与 PolishTemplateDialog / TextPolishDialog 的标题层级一致。
        content.addView(
            NukeTheme.title(ctx, "已更新新版本，请前去更新吧！", 18f).apply {
                setPadding(0, dp(6), 0, dp(10))
                tag = Constants.TAG_OWN_UI
            },
        )

        /* 版本号对比：让用户一眼看出自己落后多少，
           而不是只看到一个抽象的「有新版本」。
           玻璃化：保留 BADGE_BG 填充 + 10dp 圆角这枚徽标，ACCENT 文字压在上面。
           不额外加描边 —— 徽标本身是「实心小片」，加边框反而会把它放大成一张卡，
           在玻璃面板上多出第四条边界。填充色是 token，宿主浅色/深色主题下都靠自身填充保证可读。 */
        content.addView(TextView(ctx).apply {
            text = "当前版本 " + BuildInfo.VERSION_NAME + "  →  最新版本 " + latest
            setTextColor(NukeTheme.ACCENT)
            textSize = NukeTheme.sp(ctx, 13f)
            // 圆角：版本徽标是「徽标级」→ R_BADGE(6)。原先这里是 10，
            // 但 10 是 chip 的档位（R_CHIP）；徽标应当比 chip 更方，用具名常量归位。
            background = NukeTheme.rounded(NukeTheme.BADGE_BG, NukeTheme.R_BADGE, ctx)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            tag = Constants.TAG_OWN_UI
        })

        if (whatsNew.isNotBlank()) {
            // 液态玻璃化：更新说明收进一张玻璃卡（sectionCard = featurePanel 渐变玻璃 + 18dp 圆角）。
            // 原来「更新内容」小标题和正文都直接贴在大面板上，和上方的版本徽标没有分界；
            // 收进卡片后是「面板 → 玻璃卡 → 文本」的三层结构，与其它对话框一致，
            // 而且更新说明通常较长，成块的玻璃底也让它在滚动时更容易被识别成一段独立内容。
            val whatsNewCard = NukeTheme.sectionCard(ctx)
            whatsNewCard.addView(
                NukeTheme.category(ctx, "更新内容"),
            )
            whatsNewCard.addView(TextView(ctx).apply {
                text = whatsNew
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 13f)
                setLineSpacing(dp(4).toFloat(), 1.4f)
                setPadding(0, dp(6), 0, 0)
                tag = Constants.TAG_OWN_UI
            })
            content.addView(whatsNewCard)
        }

        /* ── 按钮 ──
           ★ v9.0.1：没有下载地址时，按钮改为「去官网」并打开官网首页。
           为什么不是「禁用按钮」或「隐藏按钮」：
             用户手动点了「点我更新」就是想了解新版本，给他一个**能点的出口**
             永远好过一个灰掉的按钮 —— 后者只会让人觉得「又坏了」。
           官网是模块自己的站点，恒定可用，且上面本来就有下载入口。 */
        val hasUrl = url.isNotBlank()
        val btnDownload = NukeTheme.warnButton(
            ctx,
            if (hasUrl) "点我下载" else "去官网",
        ) {
            if (hasUrl) {
                Log.i(TAG, "update: user tapped download, opening browser -> " + url)
                try {
                    /* 用 app 上下文启动浏览器，避免持有 Activity 引用造成泄漏；
                       这里已经不在 Activity 上，所以必须带 NEW_TASK。 */
                    openInBrowser(ctx.applicationContext, url)
                    /* 点了下载就清掉静默状态：万一用户其实没装成功，
                       下次还能再提示，不会因为点过一次就再也看不到。 */
                    UpdateState.clear(ctx.applicationContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "update: cannot open browser: " + t.message)
                    toast(ctx, "打不开浏览器，请手动访问下载页")
                }
            } else {
                /* 服务端没配下载地址 —— 退到官网首页。
                   注意**不改用静默逻辑**：用户没拿到更新，别记「已跳过」，
                   否则他把服务端配好之后还得等 24 小时才能再被提示。 */
                Log.i(TAG, "update: no download url; opening the official site instead")
                try {
                    /* 复用 AnalysisCard 里既有的官网地址常量，不另写一份 ——
                       两处各写一个字符串，将来改域名必然漏掉一个。 */
                    openInBrowser(
                        ctx.applicationContext,
                        com.luoliaoo.wechat.ui.AnalysisCard.OFFICIAL_SITE,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "update: cannot open browser: " + t.message)
                    toast(ctx, "打不开浏览器，请手动访问官网")
                }
            }
            closer.close()
        }

        val btnLater = NukeTheme.warnOutlineButton(ctx, "暂不下载") {
            Log.i(TAG, "update: user deferred, silent 24h for version $latest")
            /* 只有非强制更新才记录静默；强制更新时照样关窗但不记录，
               于是下一轮还会再提示 —— 这是 force 唯一的实际作用。 */
            if (!force) UpdateState.snooze(ctx.applicationContext, latest)
            closer.close()
        }
        (btnLater.layoutParams as LinearLayout.LayoutParams).marginEnd = 0

        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(2))
            tag = Constants.TAG_OWN_UI
            addView(btnDownload)
            addView(btnLater)
        })

        if (force) {
            // 液态玻璃化：强制更新的警告原来是一行裸文字。改用 NukeTheme.errorBox ——
            // 这是模块里「需要注意的提示」的既有玻璃样式（DANGER_BG 半透明填充 + DANGER_BORDER 描边
            // + DANGER_TEXT 文字），FloatingPanel 也用同一个盒子。同一类提示在模块里只有一种样子。
            // 文案与触发条件（force）完全未动。
            content.addView(NukeTheme.errorBox(ctx, "此版本过旧，建议尽快更新").apply {
                tag = Constants.TAG_OWN_UI
            })
        }

        val scroll = ScrollView(ctx).apply {
            addView(content)
            tag = Constants.TAG_OWN_UI
        }
        /* 悬浮窗模式下要留出屏幕边距，否则卡片会顶到状态栏 */
        scroll.setPadding(dp(16), dp(24), dp(16), dp(24))

        OwnUi.register(content, scroll, btnDownload, btnLater)
        return scroll
    }

    /**
     * 用 Dialog 显示。返回是否成功。
     *
     * 失败不抛异常：调用方会退到悬浮窗方案。
     */
    private fun showAsDialog(activity: Activity, panel: View, closer: Closer): Boolean = try {
        val ctx = unwrap(activity)
        val dlg = android.app.Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        dlg.setContentView(panel)
        /* 点外面关掉 —— 决不能把用户困在弹窗里。模块跑在微信进程内，
           挡住微信会让用户以为微信坏了。 */
        dlg.setCanceledOnTouchOutside(true)
        dlg.showStyled()
        try {
            dlg.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(0x00000000),
            )
            val lp = dlg.window?.attributes
            if (lp != null) {
                lp.width = (ctx.resources.displayMetrics.widthPixels * 0.90f).toInt()
                dlg.window?.attributes = lp
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update: dialog styling failed: " + t.message)
        }
        /* 面板里的按钮在创建时就绑定了 closer.close()，这里才把真正的
           关闭动作填进去 —— 因为此刻才知道是用 Dialog 显示。 */
        closer.set {
            try {
                dlg.dismiss()
            } catch (_: Throwable) {
                // 已经关了
            }
        }
        OwnUi.register(dlg.window?.decorView)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "update: Dialog failed (" + t.javaClass.simpleName + "), will try overlay")
        false
    }

    /**
     * 用浏览器打开下载页。
     *
     * 必须带 FLAG_ACTIVITY_NEW_TASK：这里的 ctx 是 applicationContext，
     * 不带这个 flag 启动 Activity 会抛 AndroidRuntimeException。
     */
    private fun openInBrowser(ctx: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            android.widget.Toast.makeText(ctx, "落聊：" + msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // 连 Toast 都失败就没别的办法了
        }
    }

    /** 把 Context 拆到 Activity（自绘面板的惯例做法）。 */
    private fun unwrap(ctx: Context): Context {
        var c: Context? = ctx
        var depth = 0
        while (c != null && depth < 8) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
            depth++
        }
        return ctx
    }
}
