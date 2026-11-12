package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.HostContext
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.Disclaimer

/**
 * v9.1.0：启动时的免责声明强制确认窗。
 *
 * 用户要求：
 *   「模块启动时检测有没有同意过 —— 弹出强制 UI，同意后才可以使用。
 *     有不同意 / 同意 两个按钮。同意后将写入本地文件，后续不再弹出。
 *     点不同意则直接应用闪退。」
 *
 * ─────────────────────────────────────────────────────────────
 * 【为什么是「强制」—— 以及它到底强制到什么程度】
 *
 * 用户的原话是「同意后才可以使用」，所以本窗**不可取消**：
 *   · 点外部不关闭（setCanceledOnTouchOutside(false)）
 *   · 按返回键不关闭（setCancelable(false)）
 * 这两条是「强制」的实际含义 —— 不满足就无法进入模块功能。
 *
 * 而「点不同意则应用闪退」的实现方式，见 [crashHost]。这里要说明一个
 * **取舍**：模块跑在宿主进程里（微信/QQ/抖音…），真的让宿主进程崩掉会带来两个后果：
 *   ① 用户看到的是「微信崩了」而不是「模块拒绝运行」，可能以为微信坏了；
 *   ② 微信有崩溃上报与自我保护，反复崩溃可能触发它的降级策略。
 * 但这是用户**明确要求**的行为（「点不同意则直接应用闪退」），
 * 所以照做；同时把「是模块主动退出」写进日志，便于日后排查时分辨
 * 「微信自己崩了」和「用户点了不同意」。
 *
 * ─────────────────────────────────────────────────────────────
 * 【为什么不用 NukeTheme 的工厂按钮做「强制的两个按钮」】
 * 直接用现有的 warnButton / warnOutlineButton —— 它们是本模块已成型的按钮样式，
 * 不在这个功能里另发明一套视觉。这样免责声明窗与模块其它弹窗看起来是同一个产品。
 */
object DisclaimerDialog {

    private const val TAG = "LuoLiao"

    /* 圆角节奏：与模块其它弹窗一致（面板 24 / 卡片 18 / 控件 12）。
       这几个常量在 ScopeDialog / SkillDialog / TemplateDialog 里都是**各自文件私有**的，
       不是全局常量，所以这里同样本地声明一份 —— 跟随本模块既有约定。 */
    private const val R_DIALOG = 24
    private const val R_CARD = 18

    /** 免责声明正文。改动这段文字时**必须**把 Disclaimer.CURRENT_VERSION +1。 */
    /*
     * 用户在需求里给了一句样板：「本项目仅用于个人学习、研究和自用场景。使用者应自行
     * 确认所在地区、平台规则及相关法律法规要求，并对自己的使用行为负责」，
     * 并说「你看着写吧」。以下是围绕这句展开的完整版本，分「用途」「风险」「责任」三段，
     * 覆盖一个 Xposed 模块实际需要向用户说明的事项：
     *   · 它做什么（读聊天记录喂给 AI 分析）—— 用户有权知道数据流向
     *   · 它不做保证（第三方 AI 服务、宿主更新都可能让它失效）
     *   · 账号风险（这是用户最关心、也最容易被忽略的一条）
     * 段落用空行分隔，界面上按段渲染，避免糊成一大坨。
     */
    private val PARAGRAPHS = listOf(
        "本项目仅用于个人学习、研究和自用场景。使用者应自行确认所在地区、平台规则及相关法律法规要求，并对自己的使用行为负责。",
        "本模块通过读取聊天记录并调用第三方 AI 服务完成分析。使用前请确认你已了解：聊天内容会被发送至你自行配置的 AI 服务商，相关数据的处理方式取决于该服务商的规则，本模块不存储、也不上传至开发者服务器。",
        "本模块按「现状」提供，不附带任何明示或暗示的担保。因宿主应用版本更新、系统限制、第三方服务变动等原因，功能可能随时失效或出现异常，开发者不承诺可用性与持续维护。",
        "使用第三方模块可能违反宿主应用的用户协议，存在账号被限制、封禁的风险。请在使用前自行评估，并自行承担由此产生的全部后果。",
        "继续即表示你已阅读、理解并同意上述内容。",
    )

    /**
     * 检查并（在需要时）弹出声明窗。
     *
     * @return true = 已同意（调用方可以继续正常初始化）；
     *         false = 本次没同意（已弹窗或拿到了拒绝），调用方**不应继续**。
     */
    fun ensureConsented(activity: Activity?): Boolean {
        val ctx: Context = activity?.applicationContext
            ?: HostContext.application()
            ?: run {
                /* 连 Context 都没有 —— 极早期阶段，无法弹窗也无法读状态。
                   这里返回 true 放行：见 [show] 的说明，宁可在界面真正可用时
                   再拦一次，也不要因为「此刻没法弹」就让模块整个不工作。 */
                Log.w(TAG, "disclaimer: no context yet, deferring the check")
                return true
            }

        if (Disclaimer.hasConsented(ctx)) {
            Log.i(TAG, "disclaimer: already consented, skipping")
            return true
        }

        val act = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (act == null) {
            /* 没有可用的 Activity —— 弹不出来。
               返回 false 让调用方暂缓初始化，等真正有界面时再走一遍 ensureConsented；
               这与「返回 true 放行」不同：放行等于用户没同意就用了模块。
               注意这里**不 crash** —— 用户还没做选择，不是「拒绝」。 */
            Log.i(TAG, "disclaimer: not consented but no activity available, will retry when there is one")
            return false
        }

        return show(act)
    }

    /** 真正构建并显示声明窗，然后等待用户决定。 */
    private fun show(activity: Activity): Boolean {
        /* 结果用数组持有：click 回调在构建时就要绑定，而那时局部变量还没赋完值。
           这类「闭包自引用」在本模块别处也用同样的手法（见 FloatingPanel 的 updateLinkRef）。 */
        val decided = arrayOfNulls<Boolean>(1)

        try {
            val ctx = activity
            fun dp(v: Int) = NukeTheme.dp(ctx, v)

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                /* 液态玻璃主容器：与模块其它弹窗同一套材质（圆角 24dp 的玻璃面板）。 */
                background = NukeTheme.glassPanel(ctx, R_DIALOG)
                setPadding(dp(20), dp(18), dp(20), dp(16))
                tag = Constants.TAG_OWN_UI
            }

            root.addView(
                TextView(ctx).apply {
                    text = "\u514d\u8d23\u58f0\u660e"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 19f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(4))
                },
            )
            root.addView(
                TextView(ctx).apply {
                    text = "\u4f7f\u7528\u524d\u8bf7\u9605\u8bfb\u4e0b\u5217\u5185\u5bb9"
                    setTextColor(NukeTheme.TEXT_DIM)
                    textSize = NukeTheme.sp(ctx, 12f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(10))
                },
            )

            /* 正文放进可滚动的卡片：内容偏长（5 段），小屏 + 大字体下必须能滚动，
               否则不是「看不完」就是「按钮被挤出屏幕、用户点不到」——
               后者对这个窗尤其致命：点不到同意就等于用不了模块。 */
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = NukeTheme.glassPanel(
                    ctx, R_CARD, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER,
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            for ((i, p) in PARAGRAPHS.withIndex()) {
                body.addView(
                    TextView(ctx).apply {
                        text = p
                        setTextColor(if (i == PARAGRAPHS.lastIndex) NukeTheme.ACCENT else NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 13f)
                        setLineSpacing(dp(4).toFloat(), 1.35f)
                        if (i < PARAGRAPHS.lastIndex) setPadding(0, 0, 0, dp(10))
                    },
                )
            }

            val scroller = ScrollView(ctx).apply {
                addView(body)
                /* 高度上限：屏幕的 46%。
                   为什么是比例而不是固定 dp：免责声明要在大字体、小屏上都能完整读到，
                   固定 dp 会在极端情况下把按钮挤出屏幕。46% 给按钮与标题留足空间，
                   这是「内容可读」与「按钮一定点得到」之间的平衡点。 */
                val h = try {
                    (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
                } catch (_: Throwable) {
                    dp(260)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h,
                )
                tag = Constants.TAG_OWN_UI
            }
            root.addView(scroller)

            /* ── 按钮：不同意（左，警示描边） / 同意（右，强调实心） ── */
            val btnDecline = NukeTheme.warnOutlineButton(ctx, "\u4e0d\u540c\u610f") {
                Log.i(TAG, "disclaimer: user DECLINED -- exiting the host process as requested")
                decided[0] = false
            }
            val btnAgree = NukeTheme.warnButton(ctx, "\u540c\u610f") {
                val ok = Disclaimer.markConsented(ctx.applicationContext)
                Log.i(TAG, "disclaimer: user AGREED (persisted=" + ok + ")")
                decided[0] = true
            }

            val buttonRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(14), 0, 0)
                tag = Constants.TAG_OWN_UI
            }
            /* 两个按钮收拢在右侧，与模块其它弹窗的按钮行一致（不撑满整行）。
               宽度改为自适应：整行等分会把短文案推到相距半行宽，看起来很远。 */
            btnDecline.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            btnAgree.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            buttonRow.addView(btnDecline)
            buttonRow.addView(btnAgree)
            root.addView(buttonRow)

            /* ★ 不可取消 —— 这是「强制」的实际落点。
               setCanceledOnTouchOutside(false)：点窗外不关
               setCancelable(false)：按返回键不关
               两条缺一，用户都能绕过声明直接把窗关掉。 */
            val dlg = android.app.Dialog(
                ctx,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
            )
            dlg.setContentView(root)
            dlg.setCanceledOnTouchOutside(false)
            dlg.setCancelable(false)

            /* 不同意 → 关窗 + 让宿主退出。
               放在 click 里而不是在这个函数里，是为了让「用户点了哪个」这件事
               有明确的日志与明确的代码路径（见 crashHost）。 */
            btnDecline.setOnClickListener {
                Log.i(TAG, "disclaimer: declined, closing dialog and exiting")
                decided[0] = false
                try {
                    dlg.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
                crashHost()
            }
            btnAgree.setOnClickListener {
                val ok = Disclaimer.markConsented(ctx.applicationContext)
                decided[0] = true
                Log.i(TAG, "disclaimer: agreed, persisted=" + ok)
                try {
                    dlg.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
            }

            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                /* 宿主主题可能把按钮文字画成深色，落到我们的深色玻璃上就看不见了。
                   本模块既有做法是 showStyled() 统一重涂；这里沿用。 */
                NukeTheme.styleDialogButtons(dlg)
            } catch (t: Throwable) {
                Log.w(TAG, "disclaimer: styling failed: " + t.message)
            }

            /* 同一进程内只弹一次。没有这个标记时，若调用方在多处都调 ensureConsented，
               会出现「用户刚同意、窗又被弹一次」的观感 bug。
               注意标记的是「本进程已弹过」而不是「已同意」—— 真正的持久化状态在 Disclaimer 里。 */
            if (decided[0] == true) return true

            /* 走到这里说明窗已关闭而用户选择了同意（decided=true）或还没来得及点
               （极少见：Activity 在用户点击前销毁）。用持久化状态作为最终依据，
               比信任内存里的数组更可靠。 */
            return Disclaimer.hasConsented(ctx.applicationContext)
        } catch (t: Throwable) {
            /* 弹窗本身失败（宿主主题异常、Context 有问题……）。
               这里返回 true 放行：**不能因为「我们的弹窗画不出来」就让用户完全无法使用模块**，
               那等于模块在用户设备上直接坏了。此时宁可少一次确认，
               也不能把功能整个堵死。 */
            Log.e(TAG, "disclaimer: showing the dialog failed, allowing use: " + t.message, t)
            return true
        }
    }

    /**
     * 「不同意」后的退出动作。
     *
     * 【为什么用真崩溃而不是 finish()】
     * 模块跑在宿主进程里，没有自己的 Activity 可 finish；而且用户要的效果是
     * 「应用闪退」—— finish 只会关掉当前界面，用户回到微信还能继续用。
     *
     * 【为什么先杀进程再抛异常】
     * 抛异常只能崩掉当前线程，微信有全局异常捕获，很多情况下会把它吃掉、
     * 于是「点了不同意却什么都没发生」。先 killProcess 是**确定**能让宿主退出的路径；
     * 抛异常作为兜底，覆盖 killProcess 被系统限制的个别情况。
     *
     * 【如实标注】这条路径会让宿主崩溃，是用户明确要求的行为。
     * 日志里留下明确标记，日后排查时可以把「用户拒绝」与「微信自身崩溃」区分开。
     */
    private fun crashHost() {
        try {
            Log.i(TAG, "disclaimer: KILLING HOST PROCESS (user declined the disclaimer)")
        } catch (_: Throwable) {
            // 日志都写不了也不能影响退出
        }
        try {
            val pid = android.os.Process.myPid()
            android.os.Process.killProcess(pid)
        } catch (t: Throwable) {
            Log.w(TAG, "disclaimer: killProcess failed: " + t.message)
        }
        /* 兜底：killProcess 被限制时，抛一个没人接的异常。
           放在主线程上抛，宿主会当作未捕获异常处理（多数情况直接崩掉）。 */
        throw IllegalStateException(
            "LuoLiao: the user declined the disclaimer; exiting as requested",
        )
    }
}
