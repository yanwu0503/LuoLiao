package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Template
import com.luoliaoo.wechat.data.TemplateStore

/**
 * ★★ 手动分析（「分析记录」面板左侧的「手动」按钮） ★★
 *
 * 【用户要求】
 *   「分析记录在官网左侧新增按钮 手动 弹出界面
 *    手动分析 / 选择模板 / XX / 点我一键将粘贴 / 输入框 请输入你复制后的内容
 *    / 配置 取消 确定
 *    跟长按然后分析 然后仅选择信息 一样 只不过是手动输入方式」
 *
 * 【一句话设计】这是「长按 → 分析 → 仅选择信息」的**同一个终点**，
 * 只把「那一条消息」的来源从微信的消息对象换成用户手输/粘贴的文本。
 *
 * ============================ 为什么这样复用，而不是新写一套 ============================
 *
 * 「仅选择信息」在代码里的落地是 [com.luoliaoo.wechat.hook.AnalysisController
 * .startAnalysisForSelection]（AnalysisController.kt:633）——它接收一个 `kept: List<String>`，
 * 把「用户最终保留的文本行」直接送去分析。它内部已经完成了一整条**不能重写、也不该重写**的链路：
 *
 *   1. **免责声明闸门** `DisclaimerDialog.ensureConsented`（:646）——绕过它等于给用户留了一个后门；
 *   2. **屏蔽词过滤** `PromptBuilder.isSkipped / isBlockedBy`（:658-661）——在**源头**过滤；
 *   3. **prompt 构造** `PromptBuilder.buildSelection(kept, target, ...)`（:680）；
 *   4. **模板解析** `currentConfig()`（:719）——让选中的模板决定 baseUrl / apiKey / model / 范围；
 *      这里含 v3.1.2 修过的那个「多选必然 HTTP 401」的坑（见该处长注释）；
 *   5. **卡片入库 + 面板跳转** `cards[key]` / `FloatingPanel.showCard(key)`（:725-734）；
 *   6. **AI 调用与用量回填** `OpenAiClient(cfg).analyzeWithUsage`（:739）。
 *
 * 如果这里另写一套 AI 调用，上面 6 条会各有一份副本，任何一条将来改动都会**只改一边** ——
 * 这个文件所在的模块已经被「同一个根因改了四处、第五处又冒出来」咬过五次
 * （见 AnalysisController.displayText 的长注释）。所以本文件**一个网络调用都不写**，
 * 只负责把用户输入变成 `kept` 这个入参。
 *
 * 【为什么是「一条」】「仅选择信息」= 只分析一条消息（ScopeMode.ONLY_ONE）。
 * 用户手输的内容**天然就是那一条**，所以 `kept` 就是单元素列表 `listOf(text)`。
 * 这与多选路径传进去的「用户保留的 N 行」是同一个入参形状，不需要任何特殊分支。
 *
 * ============================ 与另外两位成员的文件边界 ============================
 *
 * 本文件是**新建文件**，不改动任何既有文件的逻辑；唯一的外部改动是在 FloatingPanel 里
 * 加一个「手动」按钮（见 FloatingPanel.renderList 顶部）。这样手工分析入口与
 * 另外两位成员正在改的文件不会产生冲突。
 */
object ManualAnalysisDialog {

    private const val TAG = "LuoLiao"

    // ---- 圆角节奏：与全站冻结的 5 档一致（见 ScopeDialog 顶部注释），不出现第 6 档。
    private const val R_DIALOG = 24
    private const val R_CARD = 18
    private const val R_CONTROL = 12

    /** 无障碍：最小触控高度 48dp。本文件自绘的可点 View 都显式套用这个下限。 */
    private const val MIN_TOUCH_DP = 48

    /** 输入框最少显示 4 行（用户要求「多行」；4~6 行是任务书给的区间，取下沿避免弹窗过高）。 */
    private const val INPUT_MIN_LINES = 4

    /** 输入框最多 6 行，超出内部滚动 —— 与 [INPUT_MIN_LINES] 一起构成任务书要求的 4~6 行区间。 */
    private const val INPUT_MAX_LINES = 6

    // ============================================================ 私有绘制工具
    //
    // 【为什么不直接用 ScopeDialog 里的 glassPanel】
    // 那个函数是 ScopeDialog.kt 的 **private** 顶层函数（文件级私有），跨文件不可见。
    // 改成 public 就意味着动别人的文件 —— 本次任务的硬约束是「只新建本文件 + FloatingPanel 加一个按钮」，
    // 所以这里复制同一份实现（同为 private，不影响任何既有调用点）。
    // 复制的是**样式规则**而不是业务逻辑：它只画一张圆角玻璃底，没有分支、没有状态，
    // 因此不存在「两边逻辑漂移」的风险；而业务逻辑（分析链路）一律复用，绝不复制。

    /** 液态玻璃面板：渐变受光面 + 半透明主体 + 1dp 边缘光描边。取值全部来自 NukeTheme 令牌。 */
    private fun glassPanel(
        ctx: Context,
        radiusDp: Int = R_DIALOG,
        fill: Int = NukeTheme.CARD,
        border: Int = NukeTheme.CARD_BORDER,
        fillAlpha: Int = 0xEE,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(glassVeil(border, 0x99), glassVeil(fill, fillAlpha), glassVeil(fill, fillAlpha)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
        setStroke(NukeTheme.dp(ctx, 1), border)
    }

    /** 只换 alpha，rgb 位取自传入的令牌 —— 明暗主题切换时派生色会跟着令牌一起变。 */
    private fun glassVeil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * 主操作按钮 —— 实心强调色玻璃块（与 ScopeDialog 的 glassActionButton 同一视觉语言）。
     *
     * 【无障碍】本按钮自绘、不走 NukeTheme 的工厂方法，所以自己保证 48dp 触控高度。
     * 这里刻意用**可见的 48dp**而不是 TouchDelegate 隐形热区：隐形热区需要挂在比子 View 更大的
     * 父容器上（等宽权重布局下边界不可靠），且依赖宿主 measure 时序，而模块要跨
     * 微信/抖音/QQ/Soul/快手 5 个宿主运行。可见目标本身达标是更好的无障碍目标。
     */
    private fun glassActionButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(NukeTheme.TEXT_ON_ACCENT)
            textSize = NukeTheme.sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(NukeTheme.ACCENT, NukeTheme.ACCENT_STRONG),
            ).apply {
                cornerRadius = NukeTheme.dp(ctx, R_CONTROL).toFloat()
                setStroke(NukeTheme.dp(ctx, 1), glassVeil(NukeTheme.TEXT, 0x55))
            }
            setPadding(
                NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
                NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
            )
            minimumHeight = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            minimumWidth = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            // 宽度 0 + weight 1 等分整行，与 ScopeDialog 的按钮行保持一致。
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = NukeTheme.dp(ctx, 8) }
        }

    /** 标题之间的柔和渐变光带（与 ScopeDialog.titleRule 同源，理由同上：对方是文件私有的）。 */
    private fun titleRule(ctx: Context, radiusDp: Int = R_CARD): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(android.graphics.Color.TRANSPARENT, glassVeil(NukeTheme.ACCENT, 0x1F)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
    }

    // ============================================================ 工具

    /**
     * 把宿主 Context 拆成 Activity。
     *
     * 【为什么必须拆】[AnalysisController.startAnalysisForSelection] 的第一个参数就是 Activity，
     * 而悬浮面板里的 Context 常常是 **ContextThemeWrapper / ContextWrapper**（微信聊天页就是这样），
     * 直接强转会 ClassCastException。这里走 baseContext 链，与 FloatingPanel.activityOf /
     * FollowUpDialog.unwrap 的做法一致。
     */
    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        var depth = 0
        while (c is ContextWrapper && depth < 12) {
            if (c is Activity) return c
            c = c.baseContext
            depth++
        }
        return c as? Activity
    }

    /**
     * 读系统剪贴板的**最近一条**文本。
     *
     * 【为什么返回 String? 而不是空字符串】必须要能区分「剪贴板是空的/没有文本」和
     * 「剪贴板里就是一段空文本」。前者要明确提示用户（任务书硬性要求：不要静默失败），
     * 后者按「没内容」处理即可。null 表示前者。
     *
     * 【为什么要 primaryClip 判空】Android 10 之后 `primaryClip` 在没有焦点/无内容时返回 null；
     * 部分宿主还会直接抛 SecurityException。这里全部兜住 —— 一个读剪贴板的失败
     * 不应该让整个弹窗崩掉。
     */
    private fun readClipboard(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip: ClipData? = cm?.primaryClip
        if (clip == null || clip.itemCount <= 0) {
            null
        } else {
            // item 0 就是「最近一条」—— ClipboardManager 的 primaryClip 只保留最新的一次复制。
            val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (text.isNullOrEmpty()) null else text
        }
    } catch (t: Throwable) {
        Log.w(TAG, "reading the clipboard failed: " + t.message)
        null
    }

    /** 统一的主线程 toast。文案前缀「落聊：」与模块其它入口一致。 */
    private fun toast(ctx: Context, text: String) {
        try {
            Toast.makeText(ctx, "\u843d\u804a\uff1a" + text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // a missing toast is not worth failing the dialog over
        }
    }

    // ============================================================ 入口

    /**
     * 打开「手动分析」弹窗。
     *
     * @param ctx    宿主 Context（悬浮面板传进来的是面板自己的 Context，通常是 Activity 包装过的）
     * @param onDone 分析**已经发起**之后的回调（弹窗已关闭）。给调用方一个刷新面板的机会；
     *               默认空实现，所以不传也完全可用。
     */
    fun show(ctx: Context, onDone: () -> Unit = {}) {
        // OwnUi.suppressFor：与模块其它弹窗一致。不调用的话宿主菜单的 hook 会把我们自己的
        // 「确定」等文字当成它注入的菜单项抢走点击事件（见 OwnUi 的类注释）。
        OwnUi.suppressFor(2000L)

        val activity = activityOf(ctx)
        if (activity == null) {
            // 没有 Activity 就没法走分析链路（startAnalysisForSelection 与免责声明都需要它）。
            // 明确告知，而不是静默失败。
            Log.w(TAG, "manual analysis: no Activity in the context chain")
            toast(ctx, "\u65e0\u6cd5\u6253\u5f00\u624b\u52a8\u5206\u6790\uff08\u672a\u627e\u5230\u754c\u9762\uff09")
            return
        }

        try {
            buildAndShow(activity, ctx, onDone)
        } catch (t: Throwable) {
            Log.e(TAG, "manual analysis dialog failed: " + t.message)
            toast(ctx, "\u624b\u52a8\u5206\u6790\u6253\u5f00\u5931\u8d25")
        }
    }

    /**
     * 真正构建弹窗。抽出来是为了让 [show] 的 try/catch 覆盖住整个构建过程 ——
     * 一行内部异常不应该把宿主的聊天界面带下去。
     */
    private fun buildAndShow(activity: Activity, rawCtx: Context, onDone: () -> Unit) {
        // 用 Activity 作为视图 Context：宿主是微信分身时，面板自己的 Context 有时拿不到正确的主题，
        // 而 dialog 的内容视图用 Activity 构建最稳。
        val ctx: Context = activity
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        // ---------------------------------------------------------- 1. 面板容器
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE)
            setPadding(dp(20), dp(14), dp(20), dp(12))
            tag = Constants.TAG_OWN_UI
        }

        // ---------------------------------------------------------- 2. 标题：手动分析
        content.addView(NukeTheme.eyebrow(ctx, "\u843d\u804a \u00b7 \u624b\u52a8\u5206\u6790"))
        content.addView(
            TextView(ctx).apply {
                text = "\u624b\u52a8\u5206\u6790"
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 20f)
                typeface = Typeface.DEFAULT_BOLD
                background = titleRule(ctx)
                setPadding(0, dp(2), 0, dp(6))
            },
        )

        // ---------------------------------------------------------- 3. 选择模板
        //
        // 【为什么复用 TemplateStore 而不是 TemplateDialog.show】
        // 任务书原话是「复用 TemplateDialog.pickTemplate」——但经查**该函数不存在**
        // （TemplateDialog 对外只有 show(ctx, onPicked) 这个「模板管理」窗口，
        // 以及 applyToConfig(ctx, t)）。真正的选择器形态在 ScopeDialog 里：
        // 一列 NukeTheme.radioRow，选中即 TemplateStore.select(name) + applyToConfig。
        // 因此这里照抄那套**选择器**（同一个 radioRow、同一个 TemplateStore API、
        // 同一个 applyToConfig），保证「手动分析」选模板的行为与「分析范围」里选模板完全一致。
        //
        // 注意 applyToConfig 只接受 Template 对象，而 radioRow 回调给的是 index，
        // 所以先用 TemplateStore.all() 建表，再用 byName 取回来 —— 这比到处传对象更少出错。
        content.addView(
            TextView(ctx).apply {
                text = "\u9009\u62e9\u6a21\u677f"
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(4))
            },
        )

        val templateNames = try {
            TemplateStore.all().map { it.name }
        } catch (t: Throwable) {
            Log.w(TAG, "manual analysis: reading templates failed: " + t.message)
            emptyList()
        }

        // 当前选中的模板名：先看 TemplateStore 里存的选择，退回到第一个模板。
        // 弹窗重建（每次点选都整体重建）时靠这个变量记住用户的选择。
        val pickedIndex = intArrayOf(
            try {
                val cur = TemplateStore.selectedName()
                templateNames.indexOf(cur).coerceAtLeast(0)
            } catch (_: Throwable) {
                0
            },
        )

        val ruleGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassPanel(ctx, R_CARD, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            tag = Constants.TAG_OWN_UI
        }

        /**
         * 当前模板名的可见反馈。
         *
         * 【为什么单独要一行文字】用户点的是一列「XX」，但 radioRow 的选中态只是那个 18dp 圆点，
         * 在小屏上不够醒目；而且模板名可能很长被省略号截断。所以选中后额外显示一行
         * 「当前模板：XX」，让「我到底选中了哪个」这件事**没有歧义**（任务书明确要求
         * 「选中后要有可见反馈」）。
         */
        val currentLabel = TextView(ctx).apply {
            setTextColor(NukeTheme.TEXT_DIM)
            textSize = NukeTheme.sp(ctx, 12f)
            setPadding(0, dp(4), 0, 0)
        }

        /** 应用一个模板：选中它 + 写进共享配置（地址/Key/模型/提示词/默认范围）。 */
        fun applyTemplate(name: String) {
            try {
                TemplateStore.select(name)
            } catch (t: Throwable) {
                Log.w(TAG, "manual analysis: TemplateStore.select failed: " + t.message)
            }
            val t: Template? = try {
                TemplateStore.byName(name)
            } catch (_: Throwable) {
                null
            }
            if (t != null) {
                // 与「分析范围」窗口里的 applyTemplate 做的是同一件事：
                // 让该模板的 baseUrl / apiKey / model / systemPrompt 立刻生效。
                // 这正是「手动分析」能重用同一条分析链路的前提 ——
                // startAnalysisForSelection 内部的 currentConfig() 会解析模板来决定端点与 Key。
                TemplateDialog.applyToConfig(ctx, t)
            }
            currentLabel.text = "\u5f53\u524d\u6a21\u677f\uff1a" + name
            Log.i(TAG, "manual analysis: template applied: " + name)
        }

        fun renderTemplates() {
            ruleGroup.removeAllViews()
            if (templateNames.isEmpty()) {
                ruleGroup.addView(
                    NukeTheme.body(ctx, "\u6ca1\u6709\u6a21\u677f\uff0c\u53ef\u5148\u70b9\u4e0b\u65b9\u300c\u914d\u7f6e\u300d\u6dfb\u52a0", NukeTheme.TEXT_MUTED, 13f),
                )
            }
            for (i in templateNames.indices) {
                ruleGroup.addView(
                    NukeTheme.radioRow(ctx, i, templateNames[i], i == pickedIndex[0]) { picked ->
                        pickedIndex[0] = picked
                        templateNames.getOrNull(picked)?.let { applyTemplate(it) }
                        // 整体重建：radioRow 的圆点是靠「按 index 判等」画出来的，
                        // 重建比逐个换 drawable 更不容易出现两个同时选中。
                        renderTemplates()
                    },
                )
            }
        }
        renderTemplates()
        content.addView(ruleGroup)
        content.addView(currentLabel)
        // 弹窗打开时就先把当前模板的配置应用一次，保证「不改模板直接确定」也用的是模板里的端点与 Key。
        templateNames.getOrNull(pickedIndex[0])?.let { applyTemplate(it) }

        // ---------------------------------------------------------- 4. 点我一键粘贴（按钮）
        //
        // 用户原话：「点我一键将粘贴 《就是把剪贴板里的最近一个粘贴到输入框》」。
        // 所以它的语义是**填充输入框**，不是直接发起分析。
        content.addView(
            TextView(ctx).apply {
                text = "\u5185\u5bb9"
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(4))
            },
        )

        // ---------------------------------------------------------- 5. 输入框（多行，hint 固定）
        //
        // 【顺序说明】用户给的界面清单是「点我一键将粘贴 → 输入框 请输入你复制后的内容」，
        // 即粘贴按钮在输入框**上方**。下面先建输入框对象，再建粘贴按钮（按钮要引用输入框），
        // 但 addView 的顺序按用户要求排：按钮先加、输入框后加。
        val etInput = NukeTheme.input(ctx, "", "\u8bf7\u8f93\u5165\u4f60\u590d\u5236\u540e\u7684\u5185\u5bb9").apply {
            // NukeTheme.input 给的是单行观感（默认 minLines 1），这里改成 4~6 行多行框。
            setSingleLine(false)
            minLines = INPUT_MIN_LINES
            maxLines = INPUT_MAX_LINES
            gravity = Gravity.TOP or Gravity.START
            // 输入长文本时内部可滚动（maxLines 之外 overflow），避免把弹窗撑破。
            isVerticalScrollBarEnabled = true
            tag = Constants.TAG_OWN_UI
        }

        val btnPaste = NukeTheme.secondaryButton(ctx, "\u70b9\u6211\u4e00\u952e\u7c98\u8d34") {
            val text = readClipboard(ctx)
            if (text == null) {
                // 任务书硬性要求：剪贴板为空时给明确提示，不要静默失败。
                toast(ctx, "\u526a\u8d34\u677f\u91cc\u6ca1\u6709\u6587\u672c")
                Log.i(TAG, "manual analysis: clipboard is empty")
            } else {
                // 【为什么是 REPLACE 而不是 append】用户要的是「把最近一个粘贴到输入框」，
                // 且重复点击应当得到「就是剪贴板那一条」这个确定结果；
                // append 会让第二次点击把同样一段再拼一遍，产生重复内容。
                etInput.setText(text)
                // 光标移到末尾，用户点完就能继续补字。
                etInput.setSelection(etInput.text?.length ?: 0)
                toast(ctx, "\u5df2\u7c98\u8d34")
                Log.i(TAG, "manual analysis: pasted " + text.length + " char(s) from the clipboard")
            }
        }
        content.addView(btnPaste)
        // 输入框紧跟粘贴按钮之后（用户给的顺序：点我一键粘贴 → 输入框）。
        // 加 6dp 上边距，让按钮与输入框之间有呼吸感（NukeTheme.input 自带的 LayoutParams 是
        // MATCH_PARENT/WRAP_CONTENT，这里只在它上面补一个 topMargin，不改宽度策略）。
        (etInput.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = dp(6)
            etInput.layoutParams = lp
        }
        content.addView(etInput)

        // ---------------------------------------------------------- 6. 按钮行：配置 / 取消 / 确定
        val dlgRef = arrayOfNulls<android.app.Dialog>(1)

        fun closeDialog() {
            try {
                dlgRef[0]?.dismiss()
            } catch (_: Throwable) {
                // already gone
            }
        }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            background = glassPanel(ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            tag = Constants.TAG_OWN_UI
        }

        // 配置 —— 打开模块的模板管理窗口（TemplateDialog.show，签名已确认：show(ctx, onPicked?)）。
        // 用户在这里改完模板后，本弹窗不强制关闭：返回后重新点「选择模板」即可看到新列表。
        // 这是刻意的 —— 关掉弹窗会让用户刚粘好的内容白丢一次。
        val btnConfig = NukeTheme.warnOutlineButton(ctx, "\u914d\u7f6e") {
            Log.i(TAG, "manual analysis: opening the template manager")
            try {
                TemplateDialog.show(ctx) {
                    // 模板被编辑后立刻重新应用一次，让新的 Key/模型当场生效。
                    templateNames.getOrNull(pickedIndex[0])?.let { applyTemplate(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "manual analysis: opening the manager failed: " + t.message)
            }
        }
        buttonRow.addView(btnConfig)

        val btnCancel = NukeTheme.warnOutlineButton(ctx, "\u53d6\u6d88") {
            Log.i(TAG, "manual analysis: cancelled")
            closeDialog()
        }
        buttonRow.addView(btnCancel)

        val btnOk = glassActionButton(ctx, "\u786e\u5b9a") {
            val text = etInput.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                // 与「多选没有内容就不发起分析」一致：宁可提示，也不要发一个空 prompt 给模型。
                toast(ctx, "\u8bf7\u5148\u8f93\u5165\u6216\u7c98\u8d34\u5185\u5bb9")
                Log.i(TAG, "manual analysis: empty input; nothing to analyse")
                return@glassActionButton
            }

            // ---- 这里就是「同一个终点」 ------------------------------------------------
            //
            // kept 的每一项就是「一条被保留的消息行」，而手动输入天然只有一条 ——
            // 这正是「仅选择信息（只分析这一条）」的语义。
            //
            // 【为什么前面不加「我：」/「对方：」前缀】
            // 长按路径的前缀来自 describeMessages()（它知道消息是谁发的）。手动输入没有发送者，
            // 编造一个「对方：」会污染 prompt；而 buildSelection 本身就是按「一列文本行」
            // 处理 kept 的，不带前缀完全合法 —— 用户自己的文本原样送进去才最忠实。
            val kept = listOf(text)
            val controller = com.luoliaoo.wechat.hook.AnalysisController.instance
            if (controller == null) {
                // 控制器没起来（模块初始化失败/宿主进程没挂上 hook）。明确失败，不要静默。
                toast(ctx, "\u5206\u6790\u5668\u672a\u5c31\u7eea\uff0c\u8bf7\u91cd\u542f\u5fae\u4fe1\u540e\u91cd\u8bd5")
                Log.w(TAG, "manual analysis: AnalysisController.instance is null")
                return@glassActionButton
            }

            // 屏蔽词用当前模板里的那一套（含内置标记），与多选路径 MenuHook 的取法完全一致，
            // 这样「手动」和「长按/多选」受同一套屏蔽词约束，不会有一个入口能绕过。
            val words = try {
                controller.templateBlockWords()
            } catch (t: Throwable) {
                Log.w(TAG, "manual analysis: reading block words failed: " + t.message)
                emptyList()
            }

            closeDialog()
            onDone()

            // background 传空串：手动分析界面上没有「补充背景」框（用户给的界面清单里也没有），
            // 传空等于「不补充背景」，与长按路径的默认行为一致。
            controller.startAnalysisForSelection(activity, kept, "", words)
        }
        buttonRow.addView(btnOk)

        // 按钮行：三个按钮等分（glassActionButton 与 warnOutlineButton 都带 weight 1），
        // 与「分析范围」窗口的 配置/取消/确定 是同一套布局，观感一致。
        content.addView(buttonRow)

        // ---------------------------------------------------------- 7. 显示
        //
        // 用 ScrollView 包一层：字体放大到极端值时按钮行仍可滚动到（与 ScopeDialog 的做法一致，
        // 面板高度本身受限于屏幕，不能假设内容一定放得下）。
        val outer = android.widget.ScrollView(ctx).apply {
            addView(content)
            tag = Constants.TAG_OWN_UI
        }

        val dlg = android.app.Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        dlgRef[0] = dlg
        dlg.setContentView(outer)
        dlg.setCanceledOnTouchOutside(true)
        // showStyled() 是 DialogStyle.kt 里的扩展，内部会调 NukeTheme.styleDialogButtons
        // 修好平台按钮在深色画布上「太淡看不清」的问题。
        dlg.showStyled()
        try {
            // 让 24dp 圆角的玻璃面板自己可见，而不是套在系统对话框的方形底上。
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        } catch (t: Throwable) {
            Log.w(TAG, "manual analysis: window styling failed: " + t.message)
        }
        Log.i(TAG, "manual analysis: dialog shown (templates=" + templateNames.size + ")")
    }
}
