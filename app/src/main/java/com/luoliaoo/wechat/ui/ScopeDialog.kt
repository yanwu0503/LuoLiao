package com.luoliaoo.wechat.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.PromptBuilder
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.TemplateStore

/**
 * Scope picker.
 *
 * COUNT       up N / down N around the pressed message
 * TIME_RANGE  absolute window
 * ONLY_ONE    just the pressed message
 *
 * The confirm button is labelled 确定 (never 分析) so the menu hook can never confuse it with the
 * injected menu entry.
 */
/**
 * item 3: lets the template radio update the scope widgets, which are declared later in the layout.
 */
private class ScopeFieldHolder {
    var up: android.widget.EditText? = null
    var down: android.widget.EditText? = null
    var start: android.widget.EditText? = null
    var end: android.widget.EditText? = null
}

// ---------------------------------------------------------------- 液态玻璃尺度（冻结值）
//
// 圆角节奏取自 LIQUID-GLASS-CONTRACT，全站只有这 5 档，不出现第 6 档：
//   弹窗主容器 24 / 卡片 18 / 控件 12 / chip 10 / 徽标 6
// 用具名常量而不是散落的数字字面量，是为了让「圆角失控」在编译期就看得见。

private const val R_DIALOG = 24
private const val R_CARD = 18
private const val R_CONTROL = 12
private const val R_CHIP = 10
private const val R_BADGE = 6

/**
 * 无障碍：最小触控高度 48dp。
 *
 * NukeTheme 的公共控件已由主题内核统一抬高到 48dp，但本文件自绘的按钮不经过那些工厂方法，
 * 会漏掉这条保障。凡是本文件自己 new 出来的可点 View，都显式套用这个下限。
 * 只抬高「可点区域」，不改字号、不改配色、不改视觉尺寸。
 */
private const val MIN_TOUCH_DP = 48

// ------------------------------------------------------------------ 紧凑化（用户第 2 项）
//
// 用户原话：「分析范围的UI界面太大了 请优化 尤其是按钮之间的高度差太大了 而且字也大」。
//
// 真机实测（1272×2772 @560dpi = 363×792dp）：
//   弹窗内容实测高度约 **1040dp**，而屏幕可用高度只有约 **673dp**（留 15% 给系统栏与边距），
//   超出约 **367dp** —— 也就是说用户必须滚动才能看全，这正是「界面太大」的来源。
//
// 为什么不直接压每行高度（那是最大头）：
//   模板行 4×48、记录行 5×54、模式行 3×48 合计 642dp，是内容主体。
//   但每行 48dp 是 **MIN_TOUCH_DP 触控标准**撑起来的（无障碍下限），
//   压它会让按钮难按 —— 这是团队早就裁决过的红线（宁可可见按钮高一点，
//   也不用 TouchDelegate 隐形热区，因为要跨 5 个宿主运行、父容器边界不可靠）。
//
// 所以本轮的取舍是：**压「观感上的大」，不压「可点区域」**。具体压三处：
//   ① 面板的内边距（纯留白，压了不损失任何功能）
//   ② 标题的上方留白（标题之间不需要那么大的间隔）
//   ③ 「按钮之间的高度差」—— 用户点名的这一项，指按钮行与上方内容之间的落差，
//      通过收紧按钮行内边距与上方间距来减小。
//
// 下方所有数值都改自实测发现偏大的地方，且**只减 padding/margin，不碰 minimumHeight**。

// ------------------------------------------------------------------ 列表可见行数（用户第 3/4 项）
//
// 用户要求：「聊天记录显示最多同时 5 个，支持上下滑动」。
//
// 这里刻意**不写死像素**，而是从控件自己的高度下限推算，避免以后 NukeTheme 调整触控高度时
// 这里变成两个互相矛盾的数字：
//
//   记录行  = renderReadRows 每行内部的「删除 / 恢复」按钮高度下限 48dp + 行自身上下 padding 3+3。
//             ⚠️ 用户要的是「5 **条**消息」，所以按 5 行算；某条消息文字换行变高时，滚动区
//             自然后移，可见条数少于 5 —— 这是可接受的（不裁切、不报错）。
//
// 列表还包在一张玻璃卡片里，卡片有上下内边距：这部分必须算进上限，
// 否则最后一行会被裁掉半行（而不是「正好 5 行」）。
//
// ★ v10.1.0：**模板列表的三个常量（TEMPLATE_VISIBLE_ROWS / TEMPLATE_ROW_DP /
//   TEMPLATE_CARD_VPAD_DP / TEMPLATE_LIST_DP）已删除**，因为模板选择器从「常驻单选列表」
//   改成了「点击展开的下拉框」（用户要求）。它的高度上限现在是控件**内部**的事
//   （见 TemplateDropdown.kt 的 DROPDOWN_LIST_DP = 3 行 + 卡片内边距），
//   由那个文件自己维护，不再需要这里替它算 —— 留在本文件只会变成第二个真相。

/** 聊天记录一次最多显示几条（用户要求 5）。 */
private const val READ_VISIBLE_ROWS = 5

/** renderReadRows 每行自身的上下 padding（3 + 3），按钮的 48dp 叠在它里面。 */
private const val READ_ROW_VPAD_DP = 3 + 3

/** 一行聊天记录的高度下限 = 删除/恢复按钮的 48dp + 行 padding。 */
private const val READ_ROW_DP = MIN_TOUCH_DP + READ_ROW_VPAD_DP

/** 读取玻璃卡片的上下内边距（6 + 6）。 */
private const val READ_CARD_VPAD_DP = 6 + 6

/** 聊天记录列表的最大高度：恰好 5 条 + 卡片内边距。 */
private const val READ_LIST_DP = READ_VISIBLE_ROWS * READ_ROW_DP + READ_CARD_VPAD_DP

/**
 * 液态玻璃面板 —— 本文件私有实现，**不改动 NukeTheme**（主题内核正在同步改造那个文件）。
 *
 * 一张 GradientDrawable 同时表达玻璃的三件事：
 *   ① 受光面：左上→右下的渐变，起点取自 CARD_BORDER 的极淡「边缘光」，向右下过渡到主体填充；
 *   ② 主体：令牌填充 + 可控透明度，让下层透出一点点，这是「通透」的来源；
 *   ③ 厚度：1dp 描边即玻璃的边缘光，没有它整块就只是一团半透明色。
 *
 * 圆角按全站冻结的节奏传入：弹窗 24dp / 卡片 18dp / 控件 12dp / chip 10dp。
 * 全部颜色都来自 NukeTheme 现有 token，所以宿主是微信/抖音/QQ/Soul/快手都一样。
 */
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

/**
 * 只给现有令牌换一层透明度，用来派生玻璃高光 / 淡色底。
 *
 * 刻意不写死任何颜色值：rgb 位取自传入的 NukeTheme token，只有 alpha 是本地的，
 * 这样明暗主题切换时这些派生色会跟着令牌一起变。
 */
private fun glassVeil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * 标题区与内容区之间的柔和分隔 —— 不新增任何视图。
 *
 * 竖向渐变：全透明 → 极淡的主题色，落在标题下方形成一条「光带」。
 * 它比加一条生硬的横线更符合玻璃语言，同时完成了层次分割。
 */
private fun titleRule(ctx: Context, radiusDp: Int = R_CARD): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(android.graphics.Color.TRANSPARENT, glassVeil(NukeTheme.ACCENT, 0x1F)),
).apply {
    cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
}

/**
 * 主操作按钮 —— 实心强调色玻璃块。
 *
 * 按钮行需要「主次分明」，而 NukeTheme 现有的 warnButton 是固定的琥珀黄实心块：
 * 在已玻璃化的深色面板上，黄色实心块会显得像一块贴纸，且不属于本次冻结的玻璃语言。
 * 因此按钮行的主操作改用现有 token 里的强调色 ACCENT 实心 + 近黑文字（TEXT_ON_ACCENT），
 * 次操作沿用 NukeTheme 的描边按钮 —— 只改样式，onClick 回调完全不变。
 *
 * 圆角 12dp，遵守全站控件的圆角节奏；marginEnd 与 NukeTheme 的按钮保持一致，
 * 这样按钮行原有的间距节奏不会被破坏。
 */
private fun glassActionButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextColor(NukeTheme.TEXT_ON_ACCENT)
        textSize = NukeTheme.sp(ctx, 14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        // 顶部到中部的微渐变，让实心块也带一点「受光」的玻璃感。
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(NukeTheme.ACCENT, NukeTheme.ACCENT_STRONG),
        ).apply {
            cornerRadius = NukeTheme.dp(ctx, R_CONTROL).toFloat()
            setStroke(NukeTheme.dp(ctx, 1), glassVeil(NukeTheme.TEXT, 0x55))
        }
        setPadding(NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12), NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12))
        // 无障碍：本按钮自绘，不走 NukeTheme 的工厂方法，因此自己保证 48dp 触控高度，
        // 否则会比同屏那些已被内核抬高的按钮更难按。
        // 【取舍说明】本按钮有背景（实心强调色玻璃块），最小高度会把可见按钮一起抬到 48dp ——
        // 这是团队裁决的选项 (a)，不是疏漏。否决 TouchDelegate 隐形热区的理由：它需要挂在比
        // 子 View 更大的父容器上（等宽权重布局下边界不可靠），且依赖宿主 measure 时序，
        // 而模块要跨微信/抖音/QQ/Soul/快手 5 个宿主运行。
        minimumHeight = NukeTheme.dp(ctx, MIN_TOUCH_DP)
        minimumWidth = NukeTheme.dp(ctx, MIN_TOUCH_DP)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginEnd = NukeTheme.dp(ctx, 8) }
    }

/**
 * 把一段「行列表」包成「最多显示 N 行、超出可滑动」的滚动区（用户第 3/4 项）。
 *
 * 设计要点（为什么这么写）：
 *
 *  1. **高度上限按内容实测**：容器先以 WRAP_CONTENT 参与布局，`MaxHeightScrollView` 内部用
 *     AT_MOST(cap) 测量 —— 内容比 cap 矮时高度等于内容本身（不留空白、不出滚动条），
 *     内容比 cap 高时才夹到 cap。
 *  2. **溢出时必须改成「确定高度」**：这是本文件已经踩过的坑（见下面 readScroll 的原注释）：
 *     WRAP_CONTENT 的 ScrollView 在内容超出时会「量到内容那么高」然后被父容器裁掉，
 *     结果是**看着被切掉、却拖不动**。所以布局完成后一旦发现高度已经顶到上限，
 *     就把 layoutParams.height 换成确定的 cap，ScrollView 才真的会滚动。
 *     反过来，矮于上限时写回 WRAP_CONTENT —— 这两个列表都是**整体重建**的
 *     （`renderTemplates` 每次点选重建、`renderReadRows` 每次读取重建），
 *     高度必须能跟着新内容重新自适应，不能把上次的确定高度粘住。
 *  3. **不写死像素**：`capDp` 由调用方用「单行高度 × 可见行数」推出来（见文件顶部的常量），
 *     单行高度又来自控件自己的 48dp 触控下限。
 *  4. **触控不被外层吞掉**：本文件的两个弹窗都套在**外层 ScrollView** 里（`scroll` / `outer`），
 *     滚动区必须自己声明「别拦截我的手势」，否则内层列表看起来是「冻住的」。
 *     这里对 ACTION_DOWN / MOVE 都声明，与 FloatingPanel 的既有做法一致 ——
 *     父容器若在几个 MOVE 之后才开始拦截，只有 DOWN 声明是不够的。
 *
 * 纯展示容器：不动任何回调，不碰列表的 data 逻辑（`renderTemplates` / `renderReadRows` 照旧整体重建）。
 */
private fun cappedRowScroll(
    ctx: Context,
    rows: android.view.View,
    capDp: Int,
    topMarginDp: Int,
    visible: Boolean,
): ScrollView {
    val cap = NukeTheme.dp(ctx, capDp)
    return MaxHeightScrollView(ctx, cap).apply {
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        addView(rows)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = NukeTheme.dp(ctx, topMarginDp) }
        visibility = if (visible) View.VISIBLE else View.GONE
        tag = Constants.TAG_OWN_UI
        setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                e.actionMasked == android.view.MotionEvent.ACTION_MOVE
            ) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        // 内容实测后决定容器高度：
        //   · 行列表比上限矮 → WRAP_CONTENT，容器刚好包住内容（无空白、无滚动条）；
        //   · 行列表达到/超过上限 → 固定成 cap，ScrollView 才会真的滚动（见注释 2）。
        //
        // 【监听必须挂在 rows 上，不能挂在 ScrollView 自己身上】
        // 一旦高度被固定成 cap，ScrollView 的高度就再也不变了；此时若内容**变少**
        // （例如用户把条数改小后再点一次「读取」，renderReadRows 整体重建出更少的行），
        // 挂在 ScrollView 上的监听因为 bottom-top 仍是 cap 而直接 return，
        // 结果就是留着一大块空白又无法收缩。挂在 rows 上则不受容器是否被夹取影响：
        // ScrollView 测量子 View 时用的是 UNSPECIFIED 高度，所以 rows 的高度永远是
        // 「内容的真实高度」，内容变多变少都能观察到。
        rows.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val contentH = bottom - top
            if (contentH <= 0 || contentH == oldBottom - oldTop) return@addOnLayoutChangeListener
            val lp = layoutParams
            val wanted = if (contentH <= cap) ViewGroup.LayoutParams.WRAP_CONTENT else cap
            if (lp.height != wanted) {
                lp.height = wanted
                layoutParams = lp
            }
        }
        // 首次布局之前 rows 还没有高度，先把「未超过上限」当作初始态；
        // 上面那个监听会在第一次 layout 后立刻纠正成 cap（如果内容确实超了）。
    }
}

/**
 * 把按钮行里的按钮从「等宽撑满」改成「按文字自适应宽度、彼此紧挨」（用户第 4 项）。
 *
 * 【原来为什么显得「取消」离「分析 / 确定」很远】
 * `NukeTheme.warnOutlineButton` 与 `glassActionButton` 的 LayoutParams 都是
 * `LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)` —— 宽度 0 + weight 1，
 * 即**等分整行**。两个按钮时各占半行，三个按钮时各占 1/3 行。
 * 于是按钮的**文字**被居中到各自那一大块的正中：
 *   · 多选（取消 / 分析）      → 两个文字中心相距约半行宽；
 *   · 分析范围（配置/取消/确定）→ 相隔约 1/3 行宽。
 * 按钮块本身确实是相邻的，但用户看到的是**文字**，所以感受就是「距离太远」。
 *
 * 【改法】只改**本文件内**实例的 LayoutParams：宽度改为 WRAP_CONTENT（按文字 + 左右 padding
 * 自适应），margin 保持很小的固定值，整行由 buttonRow 的 gravity 收拢（多选是 END，
 * 分析范围也改为 END）。NukeTheme 一行不改，两个按钮工厂的默认行为对别处仍然有效。
 *
 * 【无障碍不受影响】两个工厂都已设 minimumWidth = minimumHeight = 48dp，
 * 这里只动宽度策略，48dp 的触控下限原样保留（本函数不碰 minimumWidth / minimumHeight）。
 */
private fun compactInRow(ctx: Context, button: View, marginEndDp: Int) {
    button.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = NukeTheme.dp(ctx, marginEndDp) }
}

object ScopeDialog {

    private const val TAG = "LuoLiao"

    // 注：读取列表的高度常量 READ_LIST_DP 已上移到文件顶部（与模板列表的 TEMPLATE_LIST_DP 放在一起），
    // 因为用户第 4 项的要求是「最多同时显示 5 条」，而它的推导依赖 MIN_TOUCH_DP，属于同一组常量。
    // 原来的 240dp（约十条 13sp 文本行）已按用户要求改为「5 条」。

    /** item 3: the template the user chose from the scope panel, shown as the button's label. */
    @Volatile
    private var selectedTemplateName: String? = null

    /**
     * item 6: the analysis window (hours) of the ACTIVE template.
     *
     * The dialog used to receive the window from its caller, which read it from the shared config -- so
     * changing the template did not change the start time. Asking the template directly fixes that.
     */
    private fun templateHours(): Int {
        return try {
            selectedTemplateName?.let { n ->
                TemplateStore.byName(n)?.defaultRangeHours?.let { return it }
            }
            TemplateStore.active()?.defaultRangeHours
                ?: TemplateStore.all().firstOrNull()?.defaultRangeHours
                ?: 24
        } catch (_: Throwable) {
            24
        }
    }

    /** The label for 选择模板: the chosen name, or the configured one, or a prompt. */
    private fun currentTemplateName(): String {
        selectedTemplateName?.let { return it }
        return try {
            TemplateStore.all().firstOrNull()?.name ?: "\u672a\u9009\u62e9"
        } catch (_: Throwable) {
            "\u672a\u9009\u62e9"
        }
    }

    /**
     * item 2: the other side's lines in the read list.
     *
     * 玻璃化改造：原先写死 0xFFFF5A5A / 0xFF07C160 两个硬编码色，在浅色主题下会与新的玻璃底
     * 撞色，且不属于任何令牌。语义最接近的现有 token 是危险文字色 DANGER_TEXT 与成功色 OK，
     * 所以直接引用它们 —— 主题内核换值后这里自动跟随，不再有「只有这一处不跟着变」的孤岛。
     *
     * ============================ 语义色「借用关系」登记 ============================
     *
     * 这两个常量并不是真正的语义状态色，而是**说话人区分色**（谁说的），却被借用去表达颜色。
     * 团队已评估并**明确裁决：保持借用，不新增槽位**。理由：
     *   1. 新增 speaker-a / speaker-b 槽位会让 NukeTheme 的语义体系膨胀，而「对方 / 我」本质是
     *      说话人区分，不是「成功 / 失败」这类语义状态；
     *   2. 借用 OK / DANGER_TEXT 已经在视觉上达成了「红绿分明」的目的，且两者都是明暗自适应
     *      的 token，跨主题取值正确；
     *   3. 重新设计需要新增 4 个 token（明暗各 2），收益只是命名更准，代价是内核复杂度上升。不值。
     *
     * 【借用关系一览 —— 供后续维护者理解，不是 bug】
     *   - RED_OTHER  （读取列表里「对方」说的话）借用 NukeTheme.DANGER_TEXT（危险文字色）
     *   - GREEN_MINE （读取列表里「我」说的话）  借用 NukeTheme.OK（成功色）
     *   另有两处同源的借用，见 renderReadRows()：
     *   - 「恢复」按钮的文字/描边借用 NukeTheme.OK
     *   - 「删除」按钮的文字/描边借用 NukeTheme.DANGER_TEXT / DANGER_BORDER
     * 如果将来主题内核新增了真正的说话人色槽位，把上面 4 处的引用换掉即可（改动量：4 行）。
     * ==============================================================================
     */
    private val RED_OTHER: Int get() = NukeTheme.DANGER_TEXT

    /**
     * item 2: my own lines in the read list. 原 0xFF07C160（微信绿），改用成功语义 token。
     * 借用关系的完整说明见 [RED_OTHER] 上方的登记块。
     */
    private val GREEN_MINE: Int get() = NukeTheme.OK

    /** Set while a panel is open, so its own buttons can close it. */
    @Volatile
    private var dismissRef: (() -> Unit)? = null

    /** Loads the messages matching the current settings, as display lines. */
    fun interface Reader {
        fun read(mode: ScopeMode, up: Int, down: Int, startMs: Long, endMs: Long): List<String>

        /**
         * ★ v10.3.0：与 [read] 同样的窗口，但返回**带类型信息的行**。
         *
         * ═══════════════════════════════════════════════════════════════════════════
         * 【为什么要有这个方法，而不直接改 read 的返回类型】
         * `Reader` 被**五个宿主**共用（微信 / 抖音 / 快手 / QQ / 小红书），
         * 直接改签名会让另外四个宿主的实现全部编译不过 —— 而它们并不需要识图。
         *
         * 所以这里用**默认实现**扩展：默认退化为"把 read 的字符串包成 ChatLine"，
         * 于是所有既有实现**一行都不用改**、行为完全不变；
         * 只有微信这一侧覆盖它，返回带 `isImage` 的真实数据。
         *
         * 【默认实现为什么把 time 填 0】
         * 因为 `read` 返回的字符串里**没有时间**（是"我：内容"这种展示文本）。
         * 默认路径只服务于不需要类型信息的宿主，而它们也不用时间对齐任何东西。
         * 填 0 而不是编一个假时间，是为了让"这里没有真实时间"这件事在数据上就可见 ——
         * 编一个 now() 会让下游误以为它是真的。
         * ═══════════════════════════════════════════════════════════════════════════
         */
        fun readDetailed(
            mode: ScopeMode,
            up: Int,
            down: Int,
            startMs: Long,
            endMs: Long,
        ): List<com.luoliaoo.wechat.ai.ChatLine> =
            read(mode, up, down, startMs, endMs).map {
                com.luoliaoo.wechat.ai.ChatLine(0L, false, it)
            }
    }

    /**
     * v2.7.0 (item 2): the READ UI on its own, for the multi-select entry.
     *
     * The user asked that multi-select analysis SKIP the scope dialog (no 按条数 / 按时间区间 / 仅选择信息) and
     * go straight to the read preview, which already supports 删除 / 恢复. This shows exactly that list and
     * returns the lines the user kept, so nothing is duplicated and the two entry points cannot drift apart.
     *
     * @param onConfirm receives the lines the user did NOT delete
     */
    fun showReadOnly(
        activity: Activity,
        lines: List<String>,
        title: String,
        onConfirm: (kept: List<String>, background: String) -> Unit,
    ) {
        val ctx: Context = unwrap(activity)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        OwnUi.suppressFor(2500L)

        try {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：外层容器改为液态玻璃面板（24dp 圆角 + 边缘光描边 + 半透明主体）。
                // 这里用本文件私有的 glassPanel，避免动 NukeTheme.kt。
                background = glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE)
                // 紧凑：主容器的上下留白 20/12dp → 14/8dp（左右 20dp 不动，保证文字不贴边）。
                setPadding(dp(20), dp(14), dp(20), dp(8))
                tag = Constants.TAG_OWN_UI
            }
            content.addView(NukeTheme.eyebrow(ctx, "\u843d\u804a \u00b7 \u5206\u6790\u5df2\u9009\u6d88\u606f"))

            // ---- v3.0.3 (item 1): 选择模板 -------------------------------------------
            //
            // Built EXACTLY like the scope dialog's template radio list, so the two entry points behave
            // identically: picking a template selects it globally and updates the block words below.
            content.addView(
                TextView(ctx).apply {
                    text = "\u9009\u62e9\u6a21\u677f"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 15f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 紧凑：标题上方留白 10dp → 6dp（下方 4dp 已经是实际间距）。
                    setPadding(0, dp(6), 0, dp(4))
                },
            )
            val templateNames = try {
                TemplateStore.all().map { it.name }
            } catch (_: Throwable) {
                emptyList()
            }
            // v3.0.3: the block-word holder is gone with the 屏蔽 box.
            //
            // v8.0.0: HAND-DRAWN rows instead of `RadioButton`. A host `RadioButton`'s indicator is
            // drawn from the HOST's theme, and this dialog is built with the host's Context -- on QQ
            // that produced a large tick in a filled circle with near-white unchecked rings, which the
            // user reported as looking nothing like the other hosts. `buttonTintList` (the previous
            // approach) can only recolour that shape, not replace it. See [NukeTheme.radioRow].
            //
            // ★ v10.1.0：整块单选列表**换成下拉选择器**（用户要求）★
            //
            // 用户原话：「选择模板改为 点击下拉 懂我意思吗？点一下 然后展开更多 然后可以点击选择
            //          同时最多显示3个 支持滑动」
            //
            // 改动本身很小：原来是把所有模板名铺成一个常驻的单选列表，现在收起时只显示当前项、
            // 点一下才展开（展开后最多 3 行可滑）。控件见 [templateDropdown]。
            //
            // 【为什么"选中的下标"仍由这里持有】控件的 current 是它自己的显示状态，
            // 而 TemplateStore.select 是全局副作用 —— 两者必须同步。这里保留 pickedIndex
            // 作为**唯一事实来源**：控件回调里先改它、再 select、再让控件重画，
            // 顺序与原来的 radioRow 版本完全一致，所以下面 applyTemplate 之外的逻辑一行未改。
            val pickedIndex = intArrayOf(
                currentTemplateName().let { n -> templateNames.indexOf(n).coerceAtLeast(0) },
            )
            // 下拉选择器（纯展示容器，回调里走的是原来 radioRow 分支里的同一套逻辑）。
            //
            // 【为什么不再需要 cappedRowScroll 包一层】上限现在是控件**内部**的事
            // （展开列表自己夹 3 行并声明不拦截手势），外层再套一层只会让手势归属更绕。
            content.addView(
                templateDropdown(
                    ctx = ctx,
                    names = templateNames,
                    selected = pickedIndex[0],
                    placeholder = "\u6682\u65e0\u6a21\u677f",   // 暂无模板
                ) { picked ->
                    pickedIndex[0] = picked
                    val n = templateNames.getOrNull(picked)
                    Log.i(TAG, "multi-select: template dropdown picked: " + n)
                    if (n != null) TemplateStore.select(n)
                },
            )

            // ---- v3.0.3 (item 3): 补充背景 -------------------------------------------
            //
            // The same optional box the scope dialog offers. Empty means "no background", so the prompt
            // is byte-for-byte what it was before.
            content.addView(
                TextView(ctx).apply {
                    text = "\u8865\u5145\u80cc\u666f"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 15f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 紧凑：标题上方留白 14dp → 8dp。
                    setPadding(0, dp(8), 0, dp(4))
                },
            )
            val etBackground = android.widget.EditText(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                setHintTextColor(NukeTheme.TEXT_MUTED)
                hint = "\u53ef\u4e3a\u7a7a  \u7528\u4e8e\u8865\u5145  \u5982\uff1a\u6b64\u4eba\u662f" +
                    "\u6211\u9886\u5bfc/\u5979\u6628\u5929\u521a\u5206\u624b"
                textSize = NukeTheme.sp(ctx, 13f)
                setSingleLine(false)
                maxLines = 3
                background = NukeTheme.rounded(NukeTheme.INPUT_BG, R_CONTROL, ctx, NukeTheme.INPUT_BORDER)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                tag = Constants.TAG_OWN_UI
            }
            content.addView(etBackground)

            // v3.0.3: the 屏蔽 box was REMOVED from this dialog on the user's request. Block words are
            // applied by the CALLER (MenuHook.onMultiSelectAnalyse) BEFORE this dialog opens, using the
            // built-in markers, so a blocked message is never listed here and can never be sent to the AI.

            // ---- the read rows -------------------------------------------------------
            val tvHeader = TextView(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 14f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                // 紧凑：表头上方留白 14dp → 8dp。
                setPadding(0, dp(8), 0, dp(6))
            }
            content.addView(tvHeader)

            val readList = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：读取结果是一列「列表项容器」，整体做成一张内嵌玻璃卡片（18dp），
                // 每行文字直接落在卡片上，省掉每行各自的背景，滚动时也更省绘制。
                background = glassPanel(ctx, R_CARD, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val excluded = LinkedHashSet<String>()
            renderReadRows(readList, lines, excluded, tvHeader)

            // 用户第 4 项（多选）：聊天记录最多同时显示 5 条，超出的部分上下滑动。
            // 原来这里是写死的 dp(360)，无论几条消息都占 360dp —— 这正是用户说「目前太长了」的主因之一。
            // 现在与 show() 走同一个 cappedRowScroll / READ_LIST_DP：
            //   条数 < 5  → 收缩到实际内容高度（不再有 360dp 的大片空白）；
            //   条数 > 5  → 固定到 5 条的高度并上下滚动。
            // renderReadRows 仍在**进入滚动容器之前**整体构建 readList，删除/恢复逻辑一行未动。
            content.addView(cappedRowScroll(ctx, readList, READ_LIST_DP, 0, true))

            val dlgRef = arrayOfNulls<android.app.Dialog>(1)
            val buttonRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // 用户第 4 项：原来是 Gravity.END，但真正的「太远」来自按钮等宽撑满（见 compactInRow）。
                // 现在按钮按文字自适应宽度并紧挨在一起，END 让这一小簇贴着右边缘，观感最紧凑。
                gravity = android.view.Gravity.END
                // 玻璃化：与 show 的操作条同一材质，两个入口的按钮行观感一致。
                background = glassPanel(ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
                // 紧凑：操作条的上下内边距 10dp → 8dp。
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val btnCancelMulti = NukeTheme.warnOutlineButton(ctx, "\u53d6\u6d88") {
                try {
                    dlgRef[0]?.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
            }
            buttonRow.addView(btnCancelMulti)
            val btnAnalyse = glassActionButton(ctx, "\u5206\u6790") {
                val kept = lines.filterNot { excluded.contains(it) }
                val bg = etBackground.text?.toString().orEmpty()
                try {
                    dlgRef[0]?.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
                Log.i(
                    TAG,
                    "multi-select read UI: kept " + kept.size + " of " + lines.size +
                        " background=" + bg.isNotBlank(),
                )
                onConfirm(kept, bg)
            }
            buttonRow.addView(btnAnalyse)
            // 取消 → 分析 之间只留 8dp（原来是等分半行），两个按钮现在真正挨在一起。
            // 只在**布局参数**上生效：两个按钮的 onClick 回调、文字、颜色全部未动。
            compactInRow(ctx, btnCancelMulti, 8)
            compactInRow(ctx, btnAnalyse, 0)
            content.addView(buttonRow)

            val outer = android.widget.ScrollView(ctx).apply {
                addView(content)
                tag = Constants.TAG_OWN_UI
            }
            val dlg = android.app.Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            dlgRef[0] = dlg
            dlg.setContentView(outer)
            dlg.setCanceledOnTouchOutside(true)
            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                forceReadableText(outer)
            } catch (t: Throwable) {
                Log.w(TAG, "showReadOnly: styling failed: " + t.message)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "showReadOnly failed: " + t.message)
        }
    }

    fun show(
        activity: Activity,
        initialMode: ScopeMode,
        defaultUp: Int,
        defaultDown: Int,
        defaultHours: Int,
        targetPreview: ChatLine,
        /** Number of distinct speakers, used to decide whether to ask for a target (item 5). */
        speakerCount: Int = 2,
        reader: Reader? = null,
        onConfirm: (
            mode: ScopeMode,
            up: Int, down: Int,
            startMs: Long, endMs: Long,
            focusSpeaker: String?,
            /** item 1: the message texts the user deleted; they must not reach the AI. */
            excluded: Set<String>,
            /** v2.7.0 (item 1): optional background the user typed, e.g. "此人是我领导". */
            background: String,
            /**
             * ★ v10.3.0：用户在读取预览里**逐条打开识图**的那些行文本。
             *
             * 空集合 = 全部关闭（用户指定的默认值），调用方据此判断"这一次要不要识图"。
             *
             * ⚠️【Kotlin 不允许函数类型参数带默认值】所以这个参数**没有**默认值，
             * 每个调用点都必须显式传 —— 编译期强制，反而比默认值更安全：
             * 将来新增调用方时不可能"忘了传"而悄悄沿用某个隐式行为。
             */
            imageOn: Set<String>,
        ) -> Unit,
    ) {
        val ctx: Context = unwrap(activity)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        OwnUi.suppressFor(2500L)

        try {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：与 showReadOnly 用同一个玻璃面板，两个入口观感完全一致。
                background = glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE)
                // 紧凑：主容器的上下留白 20/12dp → 14/8dp（左右 20dp 不动，保证文字不贴边）。
                setPadding(dp(20), dp(14), dp(20), dp(8))
                tag = Constants.TAG_OWN_UI
            }

            // ---- header
            content.addView(NukeTheme.eyebrow(ctx, "\u843d\u804a \u00b7 \u5206\u6790\u8303\u56f4"))
            // item 3: the scope widgets are declared BELOW, but the template radio needs to update
            // them, so a holder is created first and filled in when they are built.
            val scopeFields = ScopeFieldHolder()

            // v2.7.0 (item 1): the BACKGROUND box.
            //
            // Its hint is the grey placeholder text the user asked for, and it is genuinely optional: the
            // dialog produces the same prompt as before when it is left empty.
            val etBackground = android.widget.EditText(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                setHintTextColor(NukeTheme.TEXT_MUTED)
                hint = "\u53ef\u4e3a\u7a7a  \u7528\u4e8e\u8865\u5145  \u5982\uff1a\u6b64\u4eba\u662f" +
                    "\u6211\u9886\u5bfc/\u5979\u6628\u5929\u521a\u5206\u624b"
                textSize = NukeTheme.sp(ctx, 13f)
                setSingleLine(false)
                maxLines = 3
                background = NukeTheme.rounded(NukeTheme.INPUT_BG, R_CONTROL, ctx, NukeTheme.INPUT_BORDER)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                tag = Constants.TAG_OWN_UI
            }

            // item 3: 选择模板 as a RADIO list, styled exactly like the 按条数 / 按时间区间 / 仅选择信息 rows.
            content.addView(
                TextView(ctx).apply {
                    text = "\u9009\u62e9\u6a21\u677f"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 15f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 紧凑：标题上方留白 14dp → 6dp，与 showReadOnly 的「选择模板」保持一致。
                    setPadding(0, dp(6), 0, dp(4))
                },
            )
            val templateNames = try {
                TemplateStore.all().map { it.name }
            } catch (_: Throwable) {
                emptyList()
            }
            // item 1: hand-drawn rows, same as the single-message dialog and for the same reason --
            // a host `RadioButton` takes its indicator from the HOST's theme, so QQ's oversized tick
            // and near-white unchecked rings made this dialog look unlike the other four hosts.
            //
            // The mutual exclusion the old comment describes (never two checked at once) is now
            // structural rather than enforced by a RadioGroup: the rows are rebuilt from ONE index, so
            // exactly one of them can be drawn as checked.
            //
            // ★ v10.1.0：同样换成下拉选择器（用户要求），见 [templateDropdown]。
            // pickedIndex 仍是唯一事实来源；applyTemplate 一个字都没改。
            val pickedIndex = intArrayOf(
                currentTemplateName().let { n -> templateNames.indexOf(n).coerceAtLeast(0) },
            )
            fun applyTemplate(n: String) {
                TemplateStore.byName(n)?.let { t ->
                    // The template becomes the active one; nothing is copied into the shared config.
                    TemplateStore.select(n)
                    selectedTemplateName = n
                    // item 3: refresh the scope fields IMMEDIATELY, so 按条数 / 按时间区间 show this
                    // template's values without reopening the dialog.
                    try {
                        scopeFields.up?.setText(t.defaultUp.toString())
                        scopeFields.down?.setText(t.defaultDown.toString())
                        // item 6: the START TIME is recomputed from THIS template's hours, as
                        // now - hours, and the END TIME is refilled with now, so the pair always
                        // describes the same Beijing-time range.
                        val (st, en) = PromptBuilder.beijingRange(t.defaultRangeHours)
                        scopeFields.start?.setText(PromptBuilder.formatTime(st))
                        scopeFields.end?.setText(PromptBuilder.formatTime(en))
                        Log.i(TAG, "scope fields refreshed from " + n + ": up=" + t.defaultUp +
                            " down=" + t.defaultDown + " hours=" + t.defaultRangeHours)
                    } catch (e: Throwable) {
                        Log.w(TAG, "refreshing the scope fields failed: " + e.message)
                    }
                }
            }
            // The template the dialog opened on is applied once, so the fields show its values even
            // before the user touches anything -- what `buttons[idx].isChecked = true` used to achieve
            // through the RadioGroup's change listener.
            //
            // ⚠️ 顺序要求：必须在 addView 之前先套用一次（原来的写法也是先 renderTemplates()、
            // 再 applyTemplate）。放在 addView 之后也可以，但保持与原来一致的时序能让
            // scopeFields 的初始值在控件出现前就位 —— 避免用户看到字段先空一下再被填上。
            templateNames.getOrNull(pickedIndex[0])?.let { applyTemplate(it) }
            content.addView(
                templateDropdown(
                    ctx = ctx,
                    names = templateNames,
                    selected = pickedIndex[0],
                    placeholder = "\u6682\u65e0\u6a21\u677f",   // 暂无模板
                ) { picked ->
                    pickedIndex[0] = picked
                    val n = templateNames.getOrNull(picked)
                    Log.i(TAG, "template dropdown picked: " + n)
                    if (n != null) applyTemplate(n)
                },
            )


            content.addView(TextView(ctx).apply {
                text = "\u9009\u62e9\u8303\u56f4"
                setTextColor(NukeTheme.TEXT)
                /* ★ 用户第 1 项：「选择范围」要和「选择模板」完全一样。
                   
                   之前这里比其他标题多了一条 `background = titleRule(ctx)` 的渐变光带。
                   那条光带就是「看起来像按钮」的原因 —— 一个带背景色块、又有左右边界感的
                   矩形，在视觉上就是控件而不是标题。用户的原话是
                   「目前选择范围有跟按钮一样的 ui」，说的正是它。
                   
                   现在**完全对齐「选择模板」**：
                     · 去掉背景（连光带一起）→ 纯文字标题
                     · 字号 15f + BOLD（与选择模板一致，第 3 项已统一）
                     · padding (0, 6, 0, 4) —— 与选择模板**逐值相同**
                   
                   为什么敢去掉那条「区分模板区/范围区」的光带：
                   两个标题本来就已经靠字号+粗体+上下留白分开了，光带是重复表达；
                   而它带来的负面影响（像按钮）反而更大 —— 用户会去点它。 */
                textSize = NukeTheme.sp(ctx, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(4))
            })

            // ---- mode selection
            //
            // v8.0.0: HAND-DRAWN rows, for the same reason as the template list above -- a host
            // `RadioButton` inherits the host's theme, so QQ's large tick and near-white unchecked
            // rings made this dialog look unlike the same dialog in the other four hosts.
            //
            // `modeIndex` replaces the RadioGroup's own checked-id bookkeeping: the rows are rebuilt
            // on each pick, and the mode is read from this one variable, so there is no second source
            // of truth that could disagree with what is on screen.
            val modeValues = listOf(ScopeMode.COUNT, ScopeMode.TIME_RANGE, ScopeMode.ONLY_ONE)
            val modeLabels = listOf("按条数", "按时间区间", "仅选择信息（只分析这一条）")
            val modeIndex = intArrayOf(modeValues.indexOf(initialMode).coerceAtLeast(0))
            // A local function cannot be referenced before it is declared, and `updatePanels` is
            // defined further down (it needs the panels built above it). This indirection breaks that
            // cycle: the tap calls whatever is currently stored, and the real function is installed
            // as soon as it exists. Until then the tap still redraws the dots, which is the part the
            // user sees.
            // 注：refreshPanels / updatePanels / group / countPanel 等都是局部函数与局部变量，
            // 它们的声明顺序在 Kotlin 里是硬约束，故玻璃化只改样式属性，绝不调整声明位置。
            var refreshPanels: () -> Unit = {}
            val group = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：三种范围模式是一组单选，装进一张内嵌玻璃卡片，
                // 与上面的模板卡片保持同一套材质语言。
                background = glassPanel(ctx, R_CARD, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
                // 紧凑（用户第 2 项）：卡片上下内边距 4dp → 2dp。
                // 三行单选本身已经是 48dp 触控高度，卡片再留 4+4 就显得空。
                setPadding(dp(14), dp(2), dp(14), dp(2))
            }
            fun renderModes() {
                group.removeAllViews()
                for (i in modeValues.indices) {
                    group.addView(
                        NukeTheme.radioRow(ctx, i, modeLabels[i], i == modeIndex[0]) { picked ->
                            modeIndex[0] = picked
                            // Redraw the dots FIRST so the selection responds immediately even if the
                            // panel refresh throws.
                            renderModes()
                            refreshPanels()
                        },
                    )
                }
            }
            renderModes()
            content.addView(group)

            // ---- count panel
            val countPanel = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 玻璃化：按条数是一组输入控件，给它们一层 12dp 的玻璃卡片底，
                // 使「输入区」在 24dp 主容器里自成一个层次（控件级圆角）。
                background = glassPanel(ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
                // 紧凑（用户第 2 项）：上下内边距 8dp → 5dp。
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }
            countPanel.addView(TextView(ctx).apply {
                text = "向上："
                textSize = NukeTheme.sp(ctx, 13f)
                /* ★ v9.1.2 修复（亮色模式下看不见）：这里原本**完全没有设颜色**，
                   于是用了宿主（微信/快手…）主题的默认文字色 —— 亮色模式下是近白色，
                   而我们的玻璃面板底是浅色的 → 白字浅底，看不见。
                   改为主题令牌 TEXT：明暗两套值由 NukeTheme 提供，跟随模块自己的主题，
                   不再受宿主默认色影响。 */
                setTextColor(NukeTheme.TEXT)
            })
            val etUp = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(defaultUp.toString())
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 13f)
                background = NukeTheme.inputStates(ctx)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    dp(64), ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            scopeFields.up = etUp
            countPanel.addView(etUp)
            countPanel.addView(TextView(ctx).apply {
                text = "  条　向下："
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(NukeTheme.TEXT) // v9.1.2: was unset -> host default (invisible in light mode)
            })
            val etDown = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(defaultDown.toString())
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 13f)
                background = NukeTheme.inputStates(ctx)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    dp(64), ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            scopeFields.down = etDown
            countPanel.addView(etDown)
            countPanel.addView(TextView(ctx).apply {
                text = "  条"
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(NukeTheme.TEXT) // v9.1.2: was unset -> host default (invisible in light mode)
            })
            content.addView(countPanel)

            // ---- time panel
            val timePanel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：与按条数面板同一层次（控件级 12dp 玻璃底），
                // 两个面板互斥显示，位置与尺寸因此完全对齐，切换时不会跳动。
                background = glassPanel(ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
                // 紧凑（用户第 2 项）：与按条数面板同步 8dp → 5dp。
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }
            timePanel.addView(TextView(ctx).apply {
                text = "起始时间"
                textSize = NukeTheme.sp(ctx, 12f)
                setTextColor(NukeTheme.TEXT_DIM)
            })
            val etStart = EditText(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                background = NukeTheme.inputStates(ctx)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = NukeTheme.sp(ctx, 13f)
                // item 6: the START is the ACTIVE TEMPLATE's hours before NOW, in Beijing time.
                setText(PromptBuilder.formatTime(PromptBuilder.defaultRangeStart(templateHours())))
            }
            scopeFields.start = etStart
            timePanel.addView(etStart)
            timePanel.addView(TextView(ctx).apply {
                text = "结束时间（点下方可填入当前时间）"
                textSize = NukeTheme.sp(ctx, 12f)
                setTextColor(NukeTheme.TEXT_DIM)
                setPadding(0, dp(3), 0, 0)
            })
            val etEnd = EditText(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                background = NukeTheme.inputStates(ctx)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = NukeTheme.sp(ctx, 13f)
                setText(PromptBuilder.formatTime(System.currentTimeMillis()))
            }
            scopeFields.end = etEnd
            timePanel.addView(etEnd)
            timePanel.addView(TextView(ctx).apply {
                text = "点此填入当前时间"
                textSize = NukeTheme.sp(ctx, 12f)
                setTextColor(NukeTheme.ACCENT)
                setPadding(0, dp(3), 0, 0)
                // 无障碍：这是一个自绘的「行内文字动作」，高度只有约 21dp，远低于 48dp 建议值，
                // 手指很难点中。NukeTheme.link 由主题内核做的是同一件事，这里保持一致的做法。
                //
                // 【取舍说明 — 刻意用「可见的 48dp」而非 TouchDelegate 隐形热区】
                // 本控件没有背景（纯文字），所以抬高最小高度不会改变任何可见像素。
                // 团队曾评估过改用 TouchDelegate 扩大隐形热区的方案，最终否决，原因：
                //   1. TouchDelegate 必须挂在父容器上，且父容器必须大于子 View —— 本控件是
                //      wrap_content 自适应宽度，父容器边界不明确，TouchDelegate 可能根本不生效
                //      （这是该方案最常见的失效原因）；
                //   2. 它的行为依赖宿主的 measure/layout 时序，而本模块要跑在微信/抖音/QQ/Soul/
                //      快手 5 个宿主里，兼容性风险不可控；
                //   3. 可见目标本身达到 48dp 比「只有隐形热区」更易按，是更优的无障碍目标。
                // 后续维护者请勿误以为这是疏漏。
                minimumHeight = dp(MIN_TOUCH_DP)
                minimumWidth = dp(MIN_TOUCH_DP)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    etEnd.setText(PromptBuilder.formatTime(System.currentTimeMillis()))
                }
            })
            content.addView(timePanel)

            // ---- item 3: the single-message preview shows ONLY for ONLY_ONE
            /* ★ v10.3.0：用户**打开了识图**的那些消息（默认空 = 全部关闭）。
               ⚠️ 声明位置必须在最前面：它被**两个**入口共用 ——
                 * 「仅选择信息」预览卡上的开关（下面不远处就用到它）；
                 * 「读取」列表里每一行图片的开关。
               放在后面会导致"未声明就使用"，而两个入口共用同一个集合是有意的：
               用户在两个入口之间切换时，已经做出的选择不会丢。 */
            val imageOn = LinkedHashSet<String>()

            val previewBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：单条消息预览是一张独立的内嵌玻璃卡片（18dp），
                // 它只在「仅选择信息」模式下出现，独立成卡片后与其它模式的层次保持一致。
                background = glassPanel(ctx, R_CARD, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
                setPadding(dp(14), dp(6), dp(14), dp(7))
            }
            previewBox.addView(TextView(ctx).apply {
                text = "将分析这条消息："
                textSize = NukeTheme.sp(ctx, 12f)
                setTextColor(NukeTheme.TEXT_DIM)
            })
            previewBox.addView(TextView(ctx).apply {
                val who = if (targetPreview.isSend) "我" else "对方"
                text = who + "：" + targetPreview.text.take(120)
                textSize = NukeTheme.sp(ctx, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(NukeTheme.TEXT)
                // 玻璃化：预览的正文块做成「徽标级」小玻璃（10dp 圆角 + 极淡描边），
                // 与它所在的外层玻璃卡片形成第二层通透，而不是一块死板的实色。
                background = glassPanel(ctx, R_CHIP, NukeTheme.BADGE_BG, NukeTheme.INPUT_BORDER, 0xEE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            })

            /* ═══════════════════════════════════════════════════════════════════════
               ★ v10.3.0：**「仅本条信息」的识图开关**（用户要求）

               【为什么这个模式需要它自己的一份，而不能复用读取预览里的那个】
               「仅选择信息」模式下**不需要点「读取」** —— 它本来就只分析长按的那一条，
               所以用户根本不会去展开读取列表，那条路径上的开关他永远看不到。
               这里在**预览卡上直接给一个**，才是这个模式下唯一合理的入口。

               【只在这条消息是图片时出现】`targetPreview.isImage` 由 ChatContext 判定。
               对一句文字显示"识图"是噪音，而且会诱导用户点一个不可能生效的开关。

               【状态与读取预览共用同一个 `imageOn` 集合】
               两边都用**行文本**作键，而本预览显示的正是 `targetPreview.text` ——
               与读取预览里那一行的文本同源（都由 displayText 产出）。
               共用意味着：用户在两个入口之间切换时，选择不会丢。
               ═══════════════════════════════════════════════════════════════════════ */
            if (targetPreview.isImage) {
                // ⚠️ 用 `key`（正文）而不是 `text`（展示文本）—— 见 ChatLine.key 的说明。
                // targetPreview 这一侧的 text 本来就**不带**"我/对方："前缀，
                // 所以两者此刻恰好相同；但仍然显式用 key，以免将来有人给
                // targetPreview 加了前缀之后这里静默失效。
                val targetKey = targetPreview.key
                val visionRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                    tag = Constants.TAG_OWN_UI
                }
                val visionBtn = TextView(ctx).apply {
                    text = "识图"
                    textSize = NukeTheme.sp(ctx, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    // 48dp 触控高度：本控件自绘，必须自己保证。
                    minimumHeight = dp(MIN_TOUCH_DP)
                    minimumWidth = dp(MIN_TOUCH_DP)
                    isClickable = true
                    tag = Constants.TAG_OWN_UI
                }
                val tvVisionTip = TextView(ctx).apply {
                    textSize = NukeTheme.sp(ctx, 11f)
                    setTextColor(NukeTheme.TEXT_DIM)
                    setPadding(dp(8), 0, 0, 0)
                }

                fun paintVision() {
                    val on = imageOn.contains(targetKey)
                    visionBtn.setTextColor(if (on) NukeTheme.OK else NukeTheme.TEXT_DIM)
                    visionBtn.background = NukeTheme.rounded(
                        glassVeil(if (on) NukeTheme.OK else NukeTheme.TEXT_DIM, if (on) 0x22 else 0x14),
                        R_CHIP, ctx,
                        if (on) NukeTheme.OK else NukeTheme.INPUT_BORDER,
                    )
                    // 说明文字随状态变化 —— 开启时明确告诉用户"图片会被发出去"。
                    // 这是隐私相关的关键信息，不能只在别处写一次。
                    tvVisionTip.text = if (on) {
                        "将把这张图片发给识图模型"
                    } else {
                        "关闭（图片不会离开手机）"
                    }
                }

                visionBtn.setOnClickListener {
                    if (imageOn.contains(targetKey)) {
                        imageOn.remove(targetKey)
                        Log.i(TAG, "single preview: vision OFF")
                    } else {
                        imageOn.add(targetKey)
                        Log.i(TAG, "single preview: vision ON")
                    }
                    paintVision()
                }
                paintVision()
                visionRow.addView(visionBtn)
                visionRow.addView(tvVisionTip)
                previewBox.addView(visionRow)
            }
            content.addView(previewBox)

            // ---- item 3: range hint + 读取 button for the segment modes
            val rangeBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 紧凑：与上方模式卡片的间距 10dp → 6dp。
                setPadding(0, dp(3), 0, 0)
            }
            rangeBox.addView(TextView(ctx).apply {
                text = "将读取聊天记录中的对应内容后整体分析"
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(NukeTheme.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            val tvRead = TextView(ctx).apply {
                text = "读取"
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(NukeTheme.ACCENT)
                // 玻璃化：强调色描边 + 由 ACCENT 派生的极淡填充，做成一枚「玻璃小控件」，
                // 而不是原来的实心 CARD 底（在玻璃面板上会显得像一块补丁）。
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(glassVeil(NukeTheme.ACCENT, 0x2E), glassVeil(NukeTheme.ACCENT, 0x14)),
                ).apply {
                    cornerRadius = dp(R_CONTROL).toFloat()
                    setStroke(dp(1), NukeTheme.ACCENT)
                }
                setPadding(dp(14), dp(5), dp(14), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
                // 无障碍：本控件是自绘的、不走 NukeTheme 的工厂方法，因此自己保证 48dp 触控高度。
                // 【取舍说明】本控件**有**背景（玻璃胶囊），所以 minimumHeight 会把可见胶囊一起
                // 抬高到 48dp。这是团队明确裁决过的选择（选项 a），不是疏漏：
                // 团队否决了「TouchDelegate 隐形热区」方案，因为它需要父容器大于子 View（本控件
                // wrap_content 不满足）、且行为依赖宿主 measure 时序，而模块要跨 5 个宿主；
                // 相比之下，一个真正 48dp 高的可见胶囊本身就是更好的无障碍目标，也更符合
                // 「可点击区域尺寸合理」的要求。用户要求的是「点得到」，没有要求「视觉尺寸不许变」。
                minimumHeight = dp(MIN_TOUCH_DP)
                minimumWidth = dp(MIN_TOUCH_DP)
                gravity = Gravity.CENTER
                isClickable = true
            }
            rangeBox.addView(tvRead)
            // item 2: the read list is a chat-log panel -- my lines in green, the other side in
            // white. White needs a dark background to be readable on this light dialog.
            // item 1: the preview is a LIST OF ROWS, so every message can carry its own 删除 button.
            // A single TextView could not host per-line controls.
            val readList = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：与 showReadOnly 的读取列表同一材质，两个入口观感一致。
                background = glassPanel(ctx, R_CARD, NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, 0xCC)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val tvReadList = TextView(ctx).apply {
                text = ""
                textSize = NukeTheme.sp(ctx, 13f)
                /* ★ v9.1.2 修复：这里原本没设文字颜色。
                   它的背景是 INPUT_BG —— **亮色模式下是白色**（见 NukeTheme 第 204 行
                   `INPUT_BG = pick(0xFFFFFFFF, 0xFF0B1020)`），
                   配宿主默认的近白文字就是「白底白字」，完全看不见。
                   同一个 bug 在「向上/向下/条」三处也一样，已一并修掉。 */
                setTextColor(NukeTheme.TEXT)
                setLineSpacing(dp(4).toFloat(), 1f)
                // 玻璃化：兜底文案框也走玻璃面板，圆角收在控件级的 12dp。
                background = glassPanel(ctx, R_CONTROL, NukeTheme.INPUT_BG, NukeTheme.INPUT_BORDER, 0xEE)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            // item 1: 用户第 4 项 —— 读取预览最多同时显示 5 条（原先约十条 / 240dp），超出的部分上下滑动。
            // 高度上限 READ_LIST_DP = 5 × (48dp 行 + 6dp 行距) + 12dp 卡片内边距，见文件顶部常量。
            // 改用共享的 cappedRowScroll：高度按内容实测（条数少时不留空白、不出滚动条），
            // 只有真的超过 5 条才固定到上限并滚动；同时保留「手势不被外层 ScrollView 吞掉」
            // 与 TAG_OWN_UI 标记。这里传 false 是因为它初始必须隐藏，由 读取 按钮切到 VISIBLE。
            val readScroll = cappedRowScroll(ctx, readList, READ_LIST_DP, 8, false)
            rangeBox.addView(readScroll)
            content.addView(rangeBox)

            // ---- item 5: in a group, let the user name the person to analyse
            val focusBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 紧凑：与上方读取区的间距 10dp → 6dp。
                setPadding(0, dp(3), 0, 0)
            }
            val etFocus = EditText(ctx).apply {
                setTextColor(NukeTheme.TEXT)
                background = NukeTheme.inputStates(ctx)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = NukeTheme.sp(ctx, 13f)
                hint = "留空则不指定"
                /* ★ v9.1.2：补上占位文字的显式颜色。
                   不设的话 hint 走宿主主题默认色，在亮色模式下同样会与浅色输入框底
                   对比不足（本对话框里另外两处输入框都已显式设过 TEXT_MUTED，
                   只有这一处漏了 —— 属于同类疏漏，一并补齐以保持一致）。 */
                setHintTextColor(NukeTheme.TEXT_MUTED)
            }
            if (speakerCount > 2) {
                focusBox.addView(TextView(ctx).apply {
                    // 5.0.0: 「微信号」 becomes 抖音号 inside Douyin -- this label tells the user which
                    // id to type, so naming the wrong app would send them looking for the wrong value.
                    text = com.luoliaoo.wechat.core.HostApp.say("分析对象（群聊可填某人的微信号）")
                    textSize = NukeTheme.sp(ctx, 12f)
                    setTextColor(NukeTheme.TEXT_DIM)
                })
                focusBox.addView(etFocus)
                content.addView(focusBox)
            }

            // ONE source of truth: the index the user last picked. The old code asked the RadioGroup
            // which button was checked; with hand-drawn rows there is no such group, and deriving the
            // mode from the variable the taps already write is what keeps "what is shown" and "what is
            // read" from drifting apart.
            fun currentMode(): ScopeMode =
                modeValues.getOrNull(modeIndex[0]) ?: ScopeMode.COUNT

            fun parseTimeOrNull(s: String): Long? {
                val t = s.trim()
                if (t.isEmpty()) return null
                val re = Regex("(\\d{1,2})月(\\d{1,2})日(\\d{1,2})时(\\d{1,2})分(\\d{1,2})秒")
                val m = re.find(t) ?: return null
                return try {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.MONTH, m.groupValues[1].toInt() - 1)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
                    cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
                    cal.set(java.util.Calendar.MINUTE, m.groupValues[4].toInt())
                    cal.set(java.util.Calendar.SECOND, m.groupValues[5].toInt())
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                } catch (_: Throwable) {
                    null
                }
            }

            // item 1: message texts the user deleted, so they can be excluded from the analysis.
            val excluded = LinkedHashSet<String>()
            // A header above the rows, showing the total and how many are excluded.
            val tvReadHeader = TextView(ctx).apply {
                text = ""
                textSize = NukeTheme.sp(ctx, 12f)
                setTextColor(NukeTheme.TEXT_MUTED)
                setPadding(dp(2), 0, 0, dp(6))
                visibility = View.GONE
            }
            rangeBox.addView(tvReadHeader)

            // item 2: the 读取 button fills tvReadResult with "我/对方：XX" lines
            tvRead.setOnClickListener {
                if (reader == null) {
                    readScroll.visibility = View.VISIBLE
                    tvReadList.text = "（无法读取）"
                    return@setOnClickListener
                }
                val mode = currentMode()
                /* ★ v10.0.0 修复：ONLY_ONE 原本在这里**直接拒绝读取**。
                 *
                 * 用户报「读取的历史聊天记录还是不显示」，根因就在这里 —— 不只是语音的问题：
                 *     if (mode == ScopeMode.ONLY_ONE) {
                 *         tvReadList.text = "仅选择信息模式不需要读取…"
                 *         return
                 *     }
                 * 「仅选择信息」是长按语音/消息最常用的入口，而它恰好**永远不读**。
                 * 用户点「读取」得到的是一句说明文字，自然认为"读取坏了"。
                 *
                 * 【为什么原来的理由不成立】注释说"不需要读取，将只分析上面那一条" —— 这在
                 * **分析范围**上是对的（ONLY_ONE 确实只送一条给 AI），但「读取」按钮的职责是
                 * **给你看聊天记录**，与本次分析送几条无关。用"分析只用一条"去否掉"让我看一眼
                 * 记录"，把两件事混成了一件。团队裁决：读取一律可用，各模式都读。
                 *
                 * 【各模式读什么】交给 reader 决定（见 AnalysisController 里的实现）：
                 * ONLY_ONE 读这一条 + 它的上下文，COUNT / TIME_RANGE 读各自范围。 */
                val up = etUp.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: defaultUp
                val down = etDown.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: defaultDown
                val start = parseTimeOrNull(etStart.text.toString())
                    ?: PromptBuilder.defaultRangeStart(defaultHours)
                val end = parseTimeOrNull(etEnd.text.toString()) ?: System.currentTimeMillis()
                /* ★ v10.3.0：改成 readDetailed —— 它会带回"这一行是不是图片"。
                   ⚠️【关键约束：行文本必须与以前逐字节相同】
                   `detailed` 里每个 ChatLine 的 `text` 就是**最终展示文本**
                   （由 Reader 实现方拼好 label，与它给 `read` 的字符串完全一致）。
                   所以这里直接取 `it.text`，而不是自己再拼一次 label ——
                   自己拼就有可能拼出与原来不同的字符串，而 `excluded` 等既有逻辑
                   是**按字符串匹配**的，一旦不一致就会出现"删除按钮点了没反应"
                   这种极难定位的 bug。类型信息是**附加**的，不能顺带改动文本。 */
                val detailed = try {
                    reader.readDetailed(mode, up, down, start, end)
                } catch (t: Throwable) {
                    Log.w(TAG, "read failed: " + t.message)
                    emptyList()
                }
                val lines = detailed.map { it.text }
                /* ★ v10.3.0：**展示文本 → 匹配键**的映射。
                 *
                 * 【为什么需要这张表】两件事用的是**不同的字符串**：
                 *   * 行显示、删除/恢复 用的是 `text`（带"我/对方："前缀）；
                 *   * 识图开关最终要交给 `AnalysisController` 去和消息正文比对，
                 *     而那边手上只有正文，没有前缀。
                 * 于是列表里用 text 定位、提交时换成 key。少了这一步，
                 * 开关点下去就永远匹配不上（这正是本功能第一版的 bug）。
                 *
                 * 【重复文本的取舍】两条展示文本完全相同的消息会在这里互相覆盖。
                 * 这是**可接受**的，因为 `excluded`（既有的删除功能）本来就是按文本去重的，
                 * 两者行为必须一致；不一致反而更糟（用户会遇到"删得掉但开不了"之类）。 */
                val keyOfLine = HashMap<String, String>(detailed.size * 2)
                for (l in detailed) keyOfLine[l.text] = l.key
                // 哪些行是图片 → 只有这些行才会渲染「识图」开关。
                val imageRows = detailed.filter { it.isImage }.map { it.text }.toSet()
                // 每次重新读取都清空开关状态：新读出来的是另一批消息，
                // 沿用上一批的选择会张冠李戴（把 A 图的"已开启"落到 B 图上，
                // 那意味着**用户没同意过的图片被发出去**，是隐私事故）。
                imageOn.clear()
                Log.i(
                    TAG,
                    "scope read: mode=" + mode + " lines=" + lines.size +
                        " imageRows=" + imageRows.size,
                )

                // item 1: one row per message, each with its own 删除 / 恢复 button.
                excluded.clear()
                readList.removeAllViews()
                if (lines.isEmpty()) {
                    /* ★ v10.0.0: 读不到时说清楚**为什么**。
                     * 原本这里会 renderReadRows(空列表)，于是面板一片空白 —— 用户无法区分
                     * "功能坏了"和"这个会话确实没有可分析的记录"，这正是本 bug 拖了这么久的
                     * 原因之一（空白既可能是 bug 也可能是空数据）。空结果必须自带解释。 */
                    tvReadList.visibility = View.VISIBLE
                    tvReadList.text = "没有读到可显示的内容。\n" +
                        "· 若这个会话还没有历史记录，先正常聊几句再试\n" +
                        "· 语音需要先在微信里转成文字，转好后点读取即可看到"
                    readScroll.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                tvReadList.visibility = View.GONE
                renderReadRows(readList, lines, excluded, tvReadHeader, imageRows, imageOn, keyOfLine)
                readScroll.visibility = View.VISIBLE
            }

            fun updatePanels() {
                val m = currentMode()
                countPanel.visibility = if (m == ScopeMode.COUNT) View.VISIBLE else View.GONE
                timePanel.visibility = if (m == ScopeMode.TIME_RANGE) View.VISIBLE else View.GONE
                // item 3: preview only for ONLY_ONE; range box only for the segment modes.
                previewBox.visibility = if (m == ScopeMode.ONLY_ONE) View.VISIBLE else View.GONE
                rangeBox.visibility = if (m == ScopeMode.ONLY_ONE) View.GONE else View.VISIBLE
            }
            // The per-mode panel visibility is refreshed by `renderModes`' own tap handler through
            // `refreshPanels`, which is wired to this function HERE -- the function could not be
            // referenced where the rows are built, because it needs the panels declared above it.
            refreshPanels = { updatePanels() }
            // Sets the panels for the INITIAL mode, before the user touches anything.
            updatePanels()

            content.addView(TextView(ctx).apply {
                text = "注：仅统计文本消息，表情包/图片/语音不计入"
                textSize = NukeTheme.sp(ctx, 11f)
                setTextColor(NukeTheme.TEXT_MUTED)
                setPadding(0, dp(8), 0, 0)
            })

                        // ---- the three actions live INSIDE the card, in yellow, so there is only one surface.
            val confirmNow: () -> Unit = {
                val mode = currentMode()
                val up = etUp.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: defaultUp
                val down = etDown.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: defaultDown
                val start = parseTimeOrNull(etStart.text.toString())
                    ?: PromptBuilder.defaultRangeStart(defaultHours)
                val end = parseTimeOrNull(etEnd.text.toString()) ?: System.currentTimeMillis()
                val focus = etFocus.text.toString().trim().ifBlank { null }
                Log.i(
                    TAG,
                    "scope confirmed: mode=" + mode + " up=" + up + " down=" + down +
                        " focus=" + focus,
                )
                onConfirm(
                    mode, up, down, start, end, focus, excluded.toSet(),
                    // v2.7.0 (item 1): trimmed; an empty box keeps the old behaviour exactly.
                    etBackground.text?.toString()?.trim() ?: "",
                    // ★ v10.3.0：用户逐条打开的识图开关。空集合 = 都不识图（默认）。
                    // 注意传的是**行文本**，调用方要靠它去匹配哪几条消息要识图。
                    imageOn.toSet(),
                )
            }

            val buttonRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // 用户第 4 项：配置 / 取消 / 确定 三个按钮原来等宽撑满整行，文字被推到相隔约 1/3 行宽，
                // 看起来就是「取消离确定太远」。整行改为末尾收拢 + 按钮自适应宽度（见 compactInRow）。
                gravity = android.view.Gravity.END
                // 玻璃化：按钮行不再直接贴在内容尾部，而是放进一条 12dp 的玻璃操作条。
                // 描边 + 略深填充把「操作区」与「设置区」分开，这是层次递进的最后一层。
                background = glassPanel(ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC)
                // 紧凑（用户第 2 项）：操作条上下内边距 8dp → 6dp。
                // 用户点名的「按钮之间的高度差太大」就出在这里：操作条 8dp 内边距 + 按钮自身
                // 48dp 触控高度，会让按钮看起来比上方内容厚一截。收内边距能让它贴紧内容。
                // ⚠️ 按钮自己的 minimumHeight = 48dp **不动** —— 那是可点区域，不是留白。
                setPadding(dp(8), dp(6), dp(8), dp(6))
                tag = Constants.TAG_OWN_UI
            }
            val btnConfig = NukeTheme.warnOutlineButton(ctx, "\u914d\u7f6e") {
                Log.i(TAG, "scope dialog: opening config")
                openSettings(ctx)
            }
            val btnCancel = NukeTheme.warnOutlineButton(ctx, "\u53d6\u6d88") {
                Log.i(TAG, "scope dialog: cancelled")
                try {
                    dismissRef?.invoke()
                } catch (_: Throwable) {
                    // already gone
                }
            }
            val btnConfirm = glassActionButton(ctx, "\u786e\u5b9a") {
                confirmNow()
                try {
                    dismissRef?.invoke()
                } catch (_: Throwable) {
                    // already gone
                }
            }
            buttonRow.addView(btnConfig)
            buttonRow.addView(btnCancel)
            buttonRow.addView(btnConfirm)
            // 三个按钮紧挨在一起，间距只有 8dp（原来是等分 1/3 行宽）。
            // 只改布局参数：三个按钮的 onClick（配置→openSettings、取消→dismiss、确定→confirmNow+dismiss）
            // 与它们传给 onConfirm 的参数完全未动。
            compactInRow(ctx, btnConfig, 8)
            compactInRow(ctx, btnCancel, 8)
            compactInRow(ctx, btnConfirm, 0)
            // v2.7.0 (item 1): the background box sits with the scope controls, above the buttons.
            content.addView(
                TextView(ctx).apply {
                    text = "\u8865\u5145\u80cc\u666f\uff08\u53ef\u9009\uff09"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 紧凑：按钮上方的这个标题留白 14dp → 8dp。
                    setPadding(0, dp(8), 0, dp(6))
                },
            )
            content.addView(
                etBackground,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            content.addView(buttonRow)

            val scroll = ScrollView(ctx).apply {
                addView(content)
                tag = Constants.TAG_OWN_UI
            }

            // A plain Dialog carrying ONLY our card: no platform title, no platform button bar.
            val dlg = android.app.Dialog(
                ctx,
                android.R.style.Theme_DeviceDefault_Dialog_Alert,
            )
            dlg.setContentView(scroll)
            dlg.setCanceledOnTouchOutside(true)
            dlg.showStyled()
            // v2.4.4: HARD GUARANTEE against a host theme.
            //
            // The dialog is built with the WeChat process's context inside a clone, so the platform dialog
            // theme decides the default text colour -- and it is DARK, which is invisible on our dark card.
            // Rather than trusting the theme, every TextView that ended up nearly black is repainted.
            try {
                forceReadableText(scroll)
            } catch (t: Throwable) {
                Log.w(TAG, "forceReadableText failed: " + t.message)
            }
            try {
                // A transparent window background leaves the card as the single visible surface.
                dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                val lp = dlg.window?.attributes
                if (lp != null) {
                    lp.width = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt()
                    dlg.window?.attributes = lp
                }
            } catch (t2: Throwable) {
                Log.w(TAG, "styling the scope window failed: " + t2.message)
            }
            dismissRef = { dlg.dismiss() }

            try {
                // v8.0.0: the three mode radios are gone -- the mode rows are now hand-drawn
                // `LinearLayout`s holding a plain `View` dot and a `TextView`, and there is no
                // `RadioButton` object left to register. The rows' Texts are not registered either:
                // `OwnUi` exists so the HOST menu hook does not hijack our own widgets, and the mode
                // labels are ordinary `TextView`s -- the same kind the dialog already ships (标题,
                // 选择模板, 补充背景) which have never been registered.
                OwnUi.register(
                    content,
                    scroll,
                    etUp,
                    etDown,
                    etStart,
                    etEnd,
                    etFocus,
                    tvRead,
                    readScroll,
                    tvReadList,
                    btnConfig,
                    btnCancel,
                    btnConfirm,
                    dlg.window?.decorView,
                )
                Log.i(TAG, "scope dialog shown; own views registered")
            } catch (t: Throwable) {
                Log.w(TAG, "registering own views failed: " + t.message)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scope dialog FAILED to show", t)
            try {
                android.widget.Toast.makeText(
                    ctx,
                    "落聊：无法显示范围面板（" + t.javaClass.simpleName + "）",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) {
                // nothing more we can do
            }
        }
    }

    /** Opens the in-process config panel (item 2 of an earlier batch). */
    /**
     * v2.4.4: repaints any TextView whose colour is effectively BLACK with the theme's normal text colour.
     *
     * WHY: inside a clone the dialog inherits the HOST's dialog theme, whose primary text colour is dark. The
     * dialog already sets colours on most of its Texts, but anything that misses one renders black-on-dark and
     * looks like missing text. Checking the resolved colour catches every such case, including TextViews built
     * by helpers that do not set one.
     */
    private fun forceReadableText(root: android.view.View) {
        val stack = ArrayDeque<android.view.View>()
        stack.addLast(root)
        var fixed = 0
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
            if (v !is android.widget.TextView) continue
            val c = try {
                v.currentTextColor
            } catch (_: Throwable) {
                continue
            }
            // "effectively black" = all channels low; leave deliberately dark text on a light chip alone by
            // only touching text that would be invisible on our dark surfaces.
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < 90 && g < 90 && b < 90) {
                v.setTextColor(NukeTheme.TEXT)
                fixed++
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "forceReadableText: repainted " + fixed + " near-black text(s) to the theme colour")
        }
    }

    private fun openSettings(ctx: Context) {
        try {
            // item 4: ask which template to use before showing the configuration.
            ConfigDialog.showWithTemplateChoice(ctx)
        } catch (t: Throwable) {
            Log.e(TAG, "in-process config dialog FAILED", t)
            try {
                ctx.startActivity(Constants.settingsIntent())
            } catch (t2: Throwable) {
                Log.e(TAG, "fallback launch FAILED", t2)
            }
        }
    }


    /**
     * Renders the read list with per-line colours (item 2).
     *
     * My own messages are green, the other side white, on the dark panel behind this TextView.
     * Lines arrive as "我：..." / "对方：..." from the controller.
     */
    /**
     * item 1: builds one ROW per read message, each with its own 删除 / 恢复 button.
     *
     * Deleting does NOT remove the row: the text gets a strike-through (the way a text editor shows a
     * deletion), it is greyed, the message is recorded as excluded so it never reaches the AI, and the button
     * becomes 恢复. Pressing 恢复 restores the text, the colour and the AI visibility.
     *
     * The row is kept visible on purpose -- the user must be able to see WHAT was removed and undo it.
     */
    private fun renderReadRows(
        container: android.widget.LinearLayout,
        lines: List<String>,
        excluded: MutableSet<String>,
        header: android.widget.TextView?,
        /**
         * ★ v10.3.0：哪些**行文本**对应一条图片消息。
         *
         * 【为什么用行文本做键，而不是下标】与 [excluded] 同一套做法。
         * 用下标会有"删除一行后所有下标平移"的问题；行文本稳定得多。
         * 代价是"两条完全相同的图片消息"会被视为同一行 —— 这与 `excluded` 的
         * 既有行为一致（它也是按文本去重的），所以不引入新的不一致。
         *
         * 【默认空集合】既有的四个宿主调用本函数时不传它，行为与改动前**完全一致**
         * （没有任何行是图片 → 不渲染任何识图开关）。
         */
        imageRows: Set<String> = emptySet(),
        /**
         * 用户**打开了识图**的图片行。默认关闭 —— 这是用户明确要求的默认值。
         *
         * 【为什么默认关闭】识图意味着图片会**离开手机**发到 AI 服务端。
         * 默认开启等于"用户一分析就自动把图发出去了"，用户没做过同意的动作。
         * 默认关闭则相反：只有主动为某条消息打开，才会发。
         *
         * ⚠️【里面存的是**匹配键**，不是展示文本】
         * 展示文本带"我/对方："前缀，而最终要拿它去和消息正文比对，
         * 所以由 [keyOfLine] 换算后再放进来。详见 keyOfLine 的说明。
         */
        imageOn: MutableSet<String> = LinkedHashSet(),
        /** 展示文本 → 匹配键。没有映射时回落到展示文本本身（例如非微信宿主）。 */
        keyOfLine: Map<String, String> = emptyMap(),
    ) {
        val ctx = container.context
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        fun refreshHeader() {
            header?.text = "\u5171 " + lines.size + " \u6761\uff0c\u5df2\u5220\u9664 " +
                excluded.size + " \u6761\uff08\u4e0d\u4f1a\u53d1\u7ed9 AI\uff09"
            header?.visibility = View.VISIBLE
        }

        if (lines.isEmpty()) {
            container.addView(
                TextView(ctx).apply {
                    text = "\uff08\u672a\u8bfb\u5230\u6d88\u606f\uff09"
                    setTextColor(NukeTheme.TEXT_MUTED)
                    textSize = NukeTheme.sp(ctx, 13f)
                },
            )
            header?.visibility = View.GONE
            return
        }

        for (line in lines) {
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                tag = Constants.TAG_OWN_UI
            }

            // item 2: 我 -> green, the other side -> red, as before.
            // 玻璃化改造：两个颜色改由 RED_OTHER / GREEN_MINE 给出（均为 NukeTheme 令牌的 getter），
            // 不再有写死的 0xFF07C160 / 0xFFFF5A5A。
            val baseColour = if (line.startsWith("\u6211")) GREEN_MINE else RED_OTHER

            val tvText = TextView(ctx).apply {
                text = line
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(baseColour)
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            }

            val btn = TextView(ctx).apply {
                text = "\u5220\u9664"
                textSize = NukeTheme.sp(ctx, 12f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                // 无障碍：读取列表每一行都带这个 删除/恢复 按钮，它原本只有约 30dp 高。
                // 它又是密集列表里最常被点的控件，必须自己保证 48dp 触控高度
                // （本控件自绘，不走 NukeTheme 的工厂方法）。
                // 【取舍说明】本控件有背景（危险色/成功色胶囊），minimumHeight 会把可见胶囊一起
                // 抬到 48dp —— 这是团队裁决的选项 (a)，不是疏漏。否决 TouchDelegate 隐形热区的
                // 理由同上面的「读取」按钮：wrap_content 父容器不可靠 + 跨 5 宿主 measure 时序风险。
                minimumHeight = dp(MIN_TOUCH_DP)
                minimumWidth = dp(MIN_TOUCH_DP)
                isClickable = true
                tag = Constants.TAG_OWN_UI
            }

            fun applyState(deleted: Boolean) {
                if (deleted) {
                    // the strike-through: a line through the middle of the characters
                    tvText.text = NukeTheme.struck(line, NukeTheme.TEXT_DIM)
                    btn.text = "\u6062\u590d"
                    btn.setTextColor(NukeTheme.OK)
                    // 玻璃化：原来的 0x2207C160 是写死的微信绿。改为由成功色 token 派生透明度，
                    // 语义不变（成功/恢复），但主题换值时会自动跟随。
                    btn.background = NukeTheme.rounded(
                        glassVeil(NukeTheme.OK, 0x22), R_CHIP, ctx, NukeTheme.OK,
                    )
                } else {
                    tvText.text = line
                    tvText.setTextColor(baseColour)
                    btn.text = "\u5220\u9664"
                    btn.setTextColor(NukeTheme.DANGER_TEXT)
                    // 玻璃化：危险色底同样由 DANGER_TEXT 派生，保证「红」永远来自同一套 danger 体系。
                    btn.background = NukeTheme.rounded(
                        glassVeil(NukeTheme.DANGER_TEXT, 0x22), R_CHIP, ctx, NukeTheme.DANGER_BORDER,
                    )
                }
            }

            btn.setOnClickListener {
                if (excluded.contains(line)) {
                    excluded.remove(line)
                    applyState(false)
                    Log.i(TAG, "read row restored: " + line.take(24))
                } else {
                    excluded.add(line)
                    applyState(true)
                    Log.i(TAG, "read row deleted: " + line.take(24))
                }
                refreshHeader()
            }

            applyState(false)
            row.addView(tvText)

            /* ═══════════════════════════════════════════════════════════════════════
               ★ v10.3.0：**逐条识图开关**（用户指定：放在「删除」按钮**左侧**，默认关闭）

               【只出现在图片行上】`imageRows` 只在"这一行的消息确实是图片"时才包含它。
               非图片行不加任何控件 —— 对着一句文字或一条语音显示"识图"是纯粹的噪音，
               而且会诱导用户去点一个不会生效的开关。

               【默认关闭，且每个对话框独立】开关状态存在本次对话框的 `imageOn` 里，
               不写回配置。这是刻意的：
                 * 默认关闭是**隐私默认值** —— 图片会离开手机，必须用户主动同意；
                 * 不持久化意味着"这次我看了图"不会变成"以后每次都自动看图"。
                   用户下次分析仍从关闭开始，每次都明确表达一次意愿。

               【为什么放在删除**左侧】** 用户明确指定。补充一个实际理由：
               删除是"排除这条"（危险色、破坏性），识图是"增强这条"（功能开关）。
               把功能开关放在破坏性按钮左边，符合"破坏性操作永远在最右侧"的普遍习惯，
               减少误触删除的概率。
               ═══════════════════════════════════════════════════════════════════════ */
            if (imageRows.contains(line)) {
                // ⚠️ 存进 imageOn 的是**匹配键**（正文），不是行文本（带前缀）——
                // 最终要和分析侧的 displayText(msg) 比对，前缀会让它永远不相等。
                val visionKey = keyOfLine[line] ?: line
                val visionBtn = TextView(ctx).apply {
                    text = "识图"
                    textSize = NukeTheme.sp(ctx, 12f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    // 与删除按钮同样的 48dp 触控高度要求（本控件自绘，不走 NukeTheme 工厂）。
                    minimumHeight = dp(MIN_TOUCH_DP)
                    minimumWidth = dp(MIN_TOUCH_DP)
                    isClickable = true
                    tag = Constants.TAG_OWN_UI
                    // 右侧与删除按钮留 6dp，两者不粘连。
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, 0, dp(6), 0) }
                }

                fun paintVision() {
                    val on = imageOn.contains(visionKey)
                    // 开启用强调色、关闭用低调的中性色 —— 一眼能看出哪几条会被识图。
                    visionBtn.setTextColor(if (on) NukeTheme.OK else NukeTheme.TEXT_DIM)
                    visionBtn.background = NukeTheme.rounded(
                        glassVeil(if (on) NukeTheme.OK else NukeTheme.TEXT_DIM, if (on) 0x22 else 0x14),
                        R_CHIP, ctx,
                        if (on) NukeTheme.OK else NukeTheme.INPUT_BORDER,
                    )
                }

                visionBtn.setOnClickListener {
                    if (imageOn.contains(visionKey)) {
                        imageOn.remove(visionKey)
                        Log.i(TAG, "read row vision OFF: " + visionKey.take(24))
                    } else {
                        imageOn.add(visionKey)
                        Log.i(TAG, "read row vision ON: " + visionKey.take(24))
                    }
                    paintVision()
                }
                paintVision()
                row.addView(visionBtn)
            }

            row.addView(btn)
            container.addView(row)
        }
        refreshHeader()
    }

    private fun colourise(lines: List<String>): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        if (lines.isEmpty()) {
            sb.append("（未读到消息）")
            span(sb, 0, sb.length, NukeTheme.TEXT_MUTED)
            return sb
        }
        val header = "共 " + lines.size + " 条\n"
        sb.append(header)
        span(sb, 0, header.length, NukeTheme.TEXT_MUTED)
        for (line in lines) {
            val start = sb.length
            sb.append(line).append('\n')
            // item 2: 我 -> green, the other side -> red.
            // 玻璃化改造：同样改用令牌 getter，去掉写死的 0xFF07C160 / RED_OTHER。
            val color = if (line.startsWith("我")) GREEN_MINE else RED_OTHER
            span(sb, start, sb.length, color)
        }
        return sb
    }

    private fun span(
        sb: android.text.SpannableStringBuilder,
        start: Int,
        end: Int,
        color: Int,
    ) {
        sb.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun unwrap(ctx: Context): Activity {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return ctx as Activity
    }
}
