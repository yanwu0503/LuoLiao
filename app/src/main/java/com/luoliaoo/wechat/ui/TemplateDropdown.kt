package com.luoliaoo.wechat.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants

/**
 * v10.1.0：可折叠的**下拉选择器**（用户要求）。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════
 * 【用户的原话】「选择模板改为 点击下拉 懂我意思吗？点一下 然后展开更多 然后可以点击选择
 *               同时最多显示3个 支持滑动」
 *
 * 拆成四条可验收的行为：
 *   1. **收起态只显示当前选中的那一个** —— 不再是一进去就铺开一整列单选行；
 *   2. **点一下展开**成更多选项；
 *   3. **展开后最多同时显示 3 行**，超出部分上下滑动；
 *   4. **点某项即选中并收起**。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════
 * 【为什么做成独立控件而不是在两个弹窗里各写一遍】
 *
 * 「选择模板」在两个地方出现（单条分析的 ScopeDialog.show 与多选后的 showReadOnly），
 * 两处**必须完全一致** —— 项目里已经有过"两个入口行为漂移"的教训（见 MenuHook 里
 * 第一遍/第二遍用了不同屏蔽词表的注释）。做成一个控件，两处只是各 new 一次，
 * 行为不可能不一致。
 *
 * 【视觉】刻意复用弹窗里既有的玻璃卡片语言（同配方的玻璃背景 + R_CARD 圆角 + FEATURE 底色），
 * 让"下拉框"看起来是同一套材质，而不是塞进来一个系统 Spinner。
 * 不用系统 Spinner/ListPopupWindow 的原因：那两者的弹层由**宿主主题**绘制
 * （与本文件里两处注释记录过的 RadioButton 问题同源 —— 在 QQ 上会变成完全不同的外观），
 * 而本模块的自绘 UI 必须在 5 个宿主上长得一样。
 *
 * @param names 全部候选名称（空列表时控件显示占位文字且不可展开）
 * @param selected 当前选中项的**下标**；越界时按 0 处理
 * @param placeholder 一个候选都没有时显示的文字
 * @param onPick 用户点选某项时回调（下标）。**不负责**收起 —— 收起由控件自己完成
 */
internal fun templateDropdown(
    ctx: Context,
    names: List<String>,
    selected: Int,
    placeholder: String,
    onPick: (Int) -> Unit,
): LinearLayout {

    /** 展开态。用数组持可变状态：Kotlin 的局部变量无法在闭包里跨函数共享。 */
    val expanded = booleanArrayOf(false)
    /** 当前选中下标。控件自己维护，避免调用方传进来的值与实际显示脱节。 */
    val current = intArrayOf(if (selected in names.indices) selected else 0)

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        tag = Constants.TAG_OWN_UI
    }

    // ---------------------------------------------------------------- 收起态：当前项
    //
    // 收起态这一行**同时是按钮**：点它就是"展开"。所以它也必须有 48dp 触控下限，
    // 并且整行可点（不是只有右边的箭头可点）—— 一个只有箭头能点的下拉框会让人以为坏了。
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        // 玻璃化：与弹窗内其它卡片同一材质，18dp 圆角、FEATURE 底、极淡描边。
        background = glassLocal(ctx, R_CARD_LOCAL, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
        setPadding(NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8))
        minimumHeight = NukeTheme.dp(ctx, DROPDOWN_TOUCH_DP)
        tag = Constants.TAG_OWN_UI
    }
    val headerLabel = TextView(ctx).apply {
        text = names.getOrNull(current[0]) ?: placeholder
        setTextColor(if (names.isEmpty()) NukeTheme.TEXT_DIM else NukeTheme.TEXT)
        textSize = NukeTheme.sp(ctx, 15f)
        typeface = Typeface.DEFAULT_BOLD
        // 文字占满剩余宽度：这样长模板名会把空间吃掉，箭头被推到最右。
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    /**
     * 右侧箭头。
     *
     * ★ v10.2.0：**加大**（用户反馈：「选择面板下拉的 ui 右侧那个小按钮 改大点 目前太小了」）★
     *
     * 【为什么原来显得小】原先是裸 TextView + 14sp，而 `▾`(U+25BE) 的字面含义就是
     * "SMALL triangle" —— 这个字形只占 em 框的约一半，14sp 实际画出来只有一个很小的三角。
     * 两个因素叠加（字号小 + 字形本身小），所以看起来像个装饰点而不是按钮。
     *
     * 【改法】三处一起动，缺一处都还是"小"：
     *   1. **字号 14sp → 24sp**（约 1.7 倍），直接放大字形；
     *   2. **给一个固定 34dp 的方形盒**，字形在盒里居中 —— 这样放大后不会因为
     *      WRAP_CONTENT + 行高变化把箭头挤歪，也让"按钮"有明确的边界；
     *   3. **加一层淡色圆形底**（ACCENT 极低透明度），让它在视觉上真的是个**按钮**
     *      —— 用户管它叫"小按钮"，说明在他的心理模型里它就是个按钮，
     *      那么给它按钮的形态比只放大字形更符合预期。
     *
     * `includeFontPadding = false` 是第 2 条的必要配套：字体自带的上下 padding 会让
     * 字形在固定盒里**偏下**，关掉它才能真的居中（这是 Android TextView 垂直居中的常见坑）。
     *
     * 【触控】这里**没有**给箭头单独设点击监听，因为整行 header 本身就是可点的按钮，
     * 箭头作为不可点的子控件会把触摸事件交给父容器 —— 点箭头=点整行=展开/收起，
     * 行为正确且不需要第二套逻辑。也因为有整行 48dp 的触控下限，
     * 箭头这个 34dp 的圆圈虽然小于 48dp 指引，但**有效触控目标仍是整行**，无障碍不受影响。
     */
    val headerArrow = TextView(ctx).apply {
        // ▾ / ▴ 都是纯文本，不依赖任何 drawable，5 个宿主表现一致。
        text = ARROW_DOWN
        setTextColor(NukeTheme.ACCENT)
        textSize = NukeTheme.sp(ctx, 24f)
        gravity = Gravity.CENTER
        // 关掉字体自带 padding，否则字形在固定尺寸的盒子里会偏下（见上）。
        includeFontPadding = false
        background = arrowChip(ctx)
        layoutParams = LinearLayout.LayoutParams(
            NukeTheme.dp(ctx, ARROW_CHIP_DP),
            NukeTheme.dp(ctx, ARROW_CHIP_DP),
        ).apply { leftMargin = NukeTheme.dp(ctx, 10) }
    }
    header.addView(headerLabel)
    header.addView(headerArrow)
    root.addView(header)

    // ---------------------------------------------------------------- 展开态：选项列表
    val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = glassLocal(ctx, R_CARD_LOCAL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
        setPadding(
            NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8),
            NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8),
        )
        tag = Constants.TAG_OWN_UI
    }

    /**
     * 选项列表外层。
     *
     * 【为什么要夹高度】用户要求"最多同时显示 3 个，支持滑动"。夹取用 MaxHeightScrollView
     * （与两处既有列表同一个类），高度上限 = 3 行 + 卡片内边距 —— 与它们同一套推导方式，
     * 不写死像素。
     *
     * ★★ v10.1.0 修：这里原先直接传 `DROPDOWN_LIST_DP`，**少了一次 dp→px 换算** ★★
     *
     * 【用户报的现象】「点击展开有问题 最多显示一个」。
     *
     * 【根因】`MaxHeightScrollViewLocal` 的构造参数是**像素**（与 ScopeDialog 里那个
     * MaxHeightScrollView 同一约定），而我传进去的是 **dp 数值 160**。
     * 密度 3 倍的机器上 160px ≈ 53dp —— 只够放下**一行** 48dp 的选项，
     * 于是"最多显示 3 个"变成了"最多显示 1 个"。
     *
     * 【为什么当时没发现】文字上写的是"3 行 + 卡片内边距"、常量名是 `_DP`，
     * 读起来完全正确；只有一个不产生编译错误的单位错配。**编译零警告**，
     * 静态检查也看不出 —— 只有真机（或一次单位核对）能发现。
     *
     * 【防复发】把参数名从 `capPx` 改成 `capPx`（已在类定义处强调是像素），
     * 并且**在这个调用点就地换算**，让"传进来的东西已经是 px"一眼可见。
     * 与 cappedRowScroll 的写法完全一致（它也是 `NukeTheme.dp(ctx, capDp)` 再传）。
     *
     * 【为什么要 requestDisallowInterceptTouchEvent】两个弹窗都套在**外层 ScrollView** 里，
     * 内层滚动区必须自己声明"别拦截我的手势"，否则拖动会被外层吃掉、看起来是"冻住的"。
     * 对 DOWN 和 MOVE 都声明 —— 父容器常在几个 MOVE 之后才开始拦截，只声明 DOWN 不够。
     * 这是本文件既有的做法（见 cappedRowScroll），此处保持一致。
     */
    val scroll = MaxHeightScrollViewLocal(ctx, NukeTheme.dp(ctx, DROPDOWN_LIST_DP)).apply {
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        addView(list)
        visibility = View.GONE
        tag = Constants.TAG_OWN_UI
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN ||
                e.actionMasked == MotionEvent.ACTION_MOVE
            ) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
    }
    root.addView(
        scroll,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = NukeTheme.dp(ctx, 6) },
    )

    /** 按当前状态重画两半。整体重建比逐控件改状态更好推理（与既有列表的做法一致）。 */
    fun render() {
        headerLabel.text = names.getOrNull(current[0]) ?: placeholder
        headerLabel.setTextColor(if (names.isEmpty()) NukeTheme.TEXT_DIM else NukeTheme.TEXT)
        headerArrow.text = if (expanded[0]) ARROW_UP else ARROW_DOWN

        list.removeAllViews()
        if (!expanded[0] || names.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        for (i in names.indices) {
            list.addView(
                row(
                    ctx = ctx,
                    label = names[i],
                    checked = i == current[0],
                    onClick = {
                        current[0] = i
                        // 用户第 4 条：点某项即选中**并收起**。
                        expanded[0] = false
                        onPick(i)
                        render()
                    },
                ),
            )
        }
        scroll.visibility = View.VISIBLE
    }

    header.setOnClickListener {
        // 没有任何候选时不要"展开一个空列表" —— 那是看起来像坏了的行为。
        if (names.isEmpty()) return@setOnClickListener
        expanded[0] = !expanded[0]
        render()
    }

    render()
    return root
}

/** 展开后最多同时显示几行（用户要求 3）。 */
private const val DROPDOWN_VISIBLE_ROWS = 3

/**
 * 一行选项的高度。
 *
 * 与 NukeTheme.radioRow 的 48dp 下限一致：同样是一行「圆点 + 15sp 文字」，同样必须满足触控下限。
 * 这里直接取 48 而不是引用 radioRow 的实现细节，也因为本控件要**替换**radioRow 作为
 * 模板选择器 —— 两者行高相同，替换后弹窗的视觉节奏不变。
 */
private const val DROPDOWN_ROW_DP = 48

/** 选项玻璃卡片的上下内边距（8 + 8），与 list 的 setPadding 对应。 */
private const val DROPDOWN_CARD_VPAD_DP = 8 + 8

/** 展开列表的最大高度：恰好 3 行 + 卡片内边距。 */
private const val DROPDOWN_LIST_DP = DROPDOWN_VISIBLE_ROWS * DROPDOWN_ROW_DP + DROPDOWN_CARD_VPAD_DP

/** 收起态那一行的触控下限（无障碍）。 */
private const val DROPDOWN_TOUCH_DP = 48

/** 下拉箭头。用文本而非 drawable：不依赖宿主资源，5 个宿主表现一致。 */
private const val ARROW_DOWN = "\u25be"   // ▾
private const val ARROW_UP = "\u25b4"     // ▴

/**
 * 箭头那个圆形底的直径（v10.2.0 加大）。
 *
 * 34dp：明显大于原先那个小三角的实际占位，又小于整行 48dp 的触控区
 * （有效触控目标是整行，见 headerArrow 的注释），所以既醒目又不撑高这一行。
 */
private const val ARROW_CHIP_DP = 34

/** 卡片圆角。与 ScopeDialog 里的 R_CARD 同值（全站冻结的节奏：弹窗 24 / 卡片 18 / 控件 12）。 */
private const val R_CARD_LOCAL = 18

/**
 * 玻璃卡片背景。
 *
 * 【为什么在这里再写一份】`glassPanel` 是 ScopeDialog 的 file-private 函数，本文件用不到它。
 * 把它改成 internal 公开出去也是可行的，但那会让"谁都可以改玻璃配方"变成可能 ——
 * 而玻璃配方是全站视觉一致性的根。这里按同样的配方复刻一份（同样的
 * CARD_BORDER 边缘光 → 令牌填充渐变 + 1dp 描边），视觉上与弹窗内其它卡片无法区分。
 *
 * 【为什么不用简单纯色】纯色卡片在这套液态玻璃语言里会显得"薄"，
 * 与 24dp 主容器、模板卡片放一起时能看出不是同一材质。全部颜色取自 NukeTheme 令牌，
 * 所以明暗主题切换时自动跟随。
 */
private fun glassLocal(
    ctx: Context,
    radiusDp: Int = R_CARD_LOCAL,
    fill: Int = NukeTheme.FEATURE,
    border: Int = NukeTheme.FEATURE_BORDER,
    fillAlpha: Int = 0xCC,
): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable(
    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
    intArrayOf(veil(border, 0x99), veil(fill, fillAlpha), veil(fill, fillAlpha)),
).apply {
    cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
    setStroke(NukeTheme.dp(ctx, 1), border)
}

/** 只给令牌换一层透明度，派生玻璃高光/淡色底。不写死任何颜色值。 */
private fun veil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * 一行选项：圆点 + 文字，整行可点。
 *
 * 与 NukeTheme.radioRow 视觉一致（同样的 18dp 圆点、10dp 间距、选中用 ACCENT 色、48dp 触控），
 * 但**不复用它**：radioRow 的点击语义是"选中后由调用方重画整个列表"（见其 onTap 注释），
 * 而下拉框需要"选中后收起"，两者的交互契约不同。复制视觉、区分行为，
 * 比硬把一个控件掰成两种语义更不容易出错。
 */
private fun row(ctx: Context, label: String, checked: Boolean, onClick: () -> Unit): LinearLayout {
    val dotSize = NukeTheme.dp(ctx, 18)
    val r = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(0, NukeTheme.dp(ctx, 8), 0, NukeTheme.dp(ctx, 8))
        minimumHeight = NukeTheme.dp(ctx, DROPDOWN_ROW_DP)
        setOnClickListener { onClick() }
        tag = Constants.TAG_OWN_UI
    }
    // 圆点：用形状 drawable 手绘，不走宿主主题的 RadioButton（原因见本文件顶部说明）。
    r.addView(
        View(ctx).apply {
            background = dotDrawable(ctx, checked)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = NukeTheme.dp(ctx, 10)
            }
            tag = Constants.TAG_OWN_UI
        },
    )
    r.addView(
        TextView(ctx).apply {
            text = label
            textSize = NukeTheme.sp(ctx, 15f)
            setTextColor(if (checked) NukeTheme.ACCENT else NukeTheme.TEXT)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
    )
    return r
}

/**
 * 箭头那个圆形底。
 *
 * 淡色圆，颜色由 ACCENT 派生（`veil` 只改透明度，不写死颜色值），所以明暗主题都跟随。
 * 透明度取 0x1F（约 12%）：足以让人看出"这是一个按钮"，又不会和标题文字抢注意力 ——
 * 用户要的是"大一点"，不是"变成一个显眼的主按钮"。
 */
private fun arrowChip(ctx: Context): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(veil(NukeTheme.ACCENT, 0x1F))
        setSize(NukeTheme.dp(ctx, ARROW_CHIP_DP), NukeTheme.dp(ctx, ARROW_CHIP_DP))
    }

/** 选中 = 实心圆点；未选中 = 描边圆环。颜色全部走主题 token，亮/暗色自动适配。 */private fun dotDrawable(ctx: Context, checked: Boolean): android.graphics.drawable.Drawable {
    val d = NukeTheme.dp(ctx, 18)
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        if (checked) {
            setColor(NukeTheme.ACCENT)
            setStroke(NukeTheme.dp(ctx, 4), NukeTheme.FEATURE)
        } else {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(NukeTheme.dp(ctx, 1), NukeTheme.FEATURE_BORDER)
        }
        setSize(d, d)
    }
}

/**
 * 有高度上限的 ScrollView。
 *
 * 与 ScopeDialog 里那个 MaxHeightScrollView 同一个思路：只夹**最大**高度，
 * 内容比上限矮时保持 WRAP_CONTENT（不留空白、不出滚动条），达到上限才真的滚动。
 * 独立声明一份是因为那个类是 ScopeDialog 的 file-private 内部类。
 */
private class MaxHeightScrollViewLocal(ctx: Context, private val capPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(capPx, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
