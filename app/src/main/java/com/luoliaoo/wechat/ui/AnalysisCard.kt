package com.luoliaoo.wechat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.AnalysisResult
import com.luoliaoo.wechat.data.ChatTurn

/**
 * The in-place analysis card (built in code; no XML resources).
 *
 * APPEARANCE IS FIXED ON PURPOSE. A customisable accent colour / font size was tried and removed
 * on request: the hard-coded palette below is the version that looked right.
 *
 * Content always comes from AnalysisController/CardStore keyed by (talker#msgId), never from the
 * host View, so recycling and restarts are safe.
 *
 * Callbacks and the last state are stored on the instance, so 展开/折叠 keeps working for any
 * number of taps and the follow-up draft survives a re-render.
 *
 * ---------------------------------------------------------------- 液态玻璃（本次改造）
 *
 * 本文件自带一套配色，**刻意不依赖 NukeTheme**：卡片渲染在宿主（微信/抖音/QQ/快手/Soul）的
 * 聊天界面里，是模块里唯一「贴在别人 UI 上」的界面，用 NukeTheme 的深色面板色会与宿主的浅色
 * 聊天页打架。但**视觉语言必须与 NukeTheme 同源**，否则同一屏里新旧两套风格会显得混搭：
 *   - 圆角节奏统一为 卡片 18 / 内部小块 12 / chip 10 / 徽标 6（细进度条 2~4 保留，属控件内部细节）；
 *   - 描边逻辑统一为「1dp hairline + TL_BR 双色渐变面」，与 NukeTheme.canvas()/featurePanel() 同一套；
 *   - 强调色沿用同一族蓝紫（比原值加深，见 Palette 注释里的对比度核算）。
 *
 * 【明暗宿主都要清晰可读】这是本文件最关键的设计点。卡片背景是半透明的玻璃，底下透出来的
 * 是宿主的聊天底色（可能是微信的浅灰，也可能是深色模式的近黑），所以**不能依赖宿主背景提供
 * 对比度**：主层玻璃的不透明度被刻意定在 ≥0xFA（250/255 ≈ 98%），于是无论底下是纯黑还是纯白，
 * 卡片最终呈现的底色都收敛在 #E9ECF5 ~ #FFFFFF 这个很窄的浅色区间内。文字对比度因此是
 * 「自带」的、可预先核算的（每个色值旁的注释就是按最坏情况 #E9ECF5 算出来的）。
 * 半透明只用来让卡片透气、与宿主背景轻微融合，它不足以影响可读性 —— 实测最差一档仍有 4.84:1。
 * 注意这是**浅色玻璃**（近白底 + 深色文字），刻意不跟随宿主切换明暗：卡片是宿主界面里的
 * 一块「外来面板」，自带确定的对比度比跟着宿主变脸色更可靠，也避免在深色聊天页里出现
 * 一块突兀的深色方块。
 *
 * 【性能】禁止任何大面积实时模糊（RenderEffect/blur）：卡片随消息列表反复出现与复用，
 * 模糊是 GPU 逐帧开销，会直接拖垮微信列表滚动。玻璃质感全部由静态 GradientDrawable 分层
 * 模拟（渐变面 + 描边 + 顶部受光高光），绘制成本是常量级。
 */
class AnalysisCard(context: Context) : LinearLayout(context) {

    /** Which (talker#msgId) this card displays. Used to detect a recycled row. */
    var ownerKey: String? = null

    var collapsed: Boolean = false

    /** Draft text of the follow-up input, preserved across re-renders. */
    private var draft: String = ""

    private var lastState: Any? = null

    /** The state currently laid out. */
    private var renderedState: Any? = null

    /**
     * ★ v10.3.0：**已删除** followUpField / renderedTurnCount / renderedTail / streamingBubble 四个字段。
     *
     * 它们全都属于"增量刷新"那套机制，而那套机制在本类里已经不存在了：
     *   * `followUpField`（内联输入框）从未被赋值过 —— 内联 EditText 早已废弃，改用
     *     [FollowUpDialog] 弹窗，所以以它为条件的"跳过重建"分支是死代码；
     *   * `renderedTurnCount` / `renderedTail` 只被那个死分支使用；
     *   * `streamingBubble` 是流式第一版"抓住活控件直接改写"留下的，那条路走不通（见 [refreshTurns]）。
     * 留着它们会让"卡片的刷新流程到底是什么"变得含糊，所以整体删干净。
     */
    private var lastTurns: List<ChatTurn> = emptyList()

    /**
     * ★ v10.3.0：流式追问正在改写的那一个气泡 TextView（由 [thinkingBubble] 登记）。
     *
     * 【为什么需要一个引用】流式增量的热路径必须窄到"只改一段文字"：
     * 一旦走整卡重建，就会连带 `removeAllViews()` 整个面板（标题栏、联系人行、用量行都要重建），
     * 屏幕会肉眼可见地狂闪，用户的原话是"眼睛都要花掉了"。所以增量只做
     * `streamTarget.text = ...`，由 TextView 自己完成一次局部重绘。
     *
     * 【为什么它不会变成"悬空引用"】[thinkingBubble] 每次创建气泡都会**重新赋值**，
     * 而整卡重建或面板重画一定会重新创建气泡 —— 也就是说这个引用要么指向当前真正显示的那个
     * TextView，要么被下一次创建覆盖。
     *
     * 失效判定用 `parent == null`：`removeAllViews()` 会立刻把子 View 的 parent 摘成 null，
     * 所以一个已脱离视图树的控件能被可靠地识别出来。[updateStreamText] 遇到失效引用时
     * 返回 false，让调用方回退到整卡重画（那一次会重建它）。**宁可多重建一次，
     * 也不能把字静默写进一个看不见的控件里** —— 后者就是"卡住不动"那种最难查的现象。
     */
    private var streamTarget: TextView? = null

    /**
     * ★ v10.3.0：当前那条气泡是否还是"思考中"的外观（强调色 + 强调描边）。
     *
     * 只为了让 [updateStreamText] 在**第一次**写入真内容时把外观换成正式气泡，
     * 之后就一直保持 false，热路径回到"每帧只改一次 text"。
     */
    private var thinkingLook = false

    private var onReanalyzeCb: (() -> Unit)? = null
    private var onFollowUpCb: ((String) -> Unit)? = null
    private var onDeleteCb: (() -> Unit)? = null
    private var onEditCb: ((String) -> Unit)? = null


    // ------------------------------------------------------------------ palette

    /**
     * 卡片配色。**常量名一律保持不变**（外部虽未引用，但这是既有的语义命名，改名只会制造噪音）。
     *
     * 每个色值都标了「画在玻璃底上的实测对比度」。玻璃底不是一个固定色：它是半透明面叠在
     * **未知宿主背景**上的合成结果，所以下面的比值一律按**最坏情况**（宿主为纯黑，合成出最暗、
     * 对深色文字最不利的底色 #E9ECF5）计算，而不是按理想白底。算法见类注释。
     *
     * 本次玻璃化的取色规则只有一条：**所有文字色都要在最坏底色上过 4.5:1**，否则调深到过。
     * 这意味着强调色和语义色都比原来深（原值只按浅色白底取值，在玻璃底上会掉到 2.4~3.9）。
     */
    private object Palette {

        // ------------------------------------------------------------ 强调色（蓝紫一族）
        // 与 NukeTheme 的 periwinkle 同族，但比它深：NukeTheme 的强调色是给**深色面板**用的，
        // 直接搬到浅色玻璃上会严重不达标（#91A9FF on 浅底 ≈ 1.9:1）。
        const val ACCENT = 0xFF3A5AD9.toInt()     // 玻璃底 4.88:1 / 白字压其上 5.76:1
        const val ACCENT_2 = 0xFF6A46D9.toInt()   // 玻璃底 5.09:1 / 白字压其上 6.02:1

        // ------------------------------------------------------------ 玻璃层
        // 卡片主体。0xFA = 250/255 = 98% 不透明 —— 这是「明暗宿主都自带对比度」的关键参数：
        // 无论底下是纯黑还是纯白聊天背景，合成后的卡片底色都被压在 #E9ECF5 ~ #FFFFFF 之间，
        // 于是上面所有文字的对比度都能**预先算出来**，不依赖宿主提供对比度。
        const val CARD_BG = 0xFDFFFFFF.toInt()
        /** 顶/底两端的玻璃渐变：左上更亮（受光），右下偏冷（厚度感）。 */
        const val CARD_TOP = CARD_BG
        const val CARD_BOTTOM = 0xFAEEF1FA.toInt()
        /**
         * 受光高光：整卡高度自上而下由 30% 白衰减到全透明。
         *
         * 刻意做满全高而不是只在顶部铺一条色带：卡片高度是 WRAP_CONTENT（随内容变化），
         * 拿不到确定高度，用「顶部 N%」的 inset 写法会在内容变高时露出色带断口。
         */
        const val SHEEN = 0x4DFFFFFF.toInt()
        /** 1dp hairline：半透明冷灰，在浅色玻璃上收出边界，同时不会在深色宿主上「看反」。 */
        const val CARD_STROKE = 0x333B4660.toInt()

        // 嵌套玻璃面（回复块 / chip / 气泡）：画在卡片**之上**，所以它透出来的是卡片本身（近白），
        // 而不是宿主背景 —— 这里可以放心用更强的半透明，既出层次又不可能掉对比度。
        const val GLASS_INNER = 0xD9FFFFFF.toInt()
        const val INNER_STROKE = 0x293B4660.toInt()

        /** 进度条轨道：半透明「凹槽」，嵌在玻璃里而不是一块实色贴片。 */
        const val TRACK = 0x2E2A3348.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        // ------------------------------------------------------------ 文字
        // 括号内 = 最坏玻璃底 #E9ECF5（即 98% 不透明的玻璃压在**纯黑**宿主背景上）的实测对比度。
        const val TXT_TITLE = 0xFF2A2A33.toInt()     // 12.04:1
        const val TXT_QUESTION = 0xFF22222A.toInt()  // 13.37:1
        const val TXT_LABEL = 0xFF2E2E38.toInt()     // 11.37:1
        const val TXT_REPLY = 0xFF3A3A44.toInt()     // 9.52:1
        const val TXT_COMMENT = 0xFF63636F.toInt()   // 5.02:1（原 #6B6B78 余量过小）
        /**
         * 区段小标题（11sp）。原来是 #8A8A96，在浅底上只有 **3.19:1** —— 11sp 是小号正文，
         * 必须 ≥4.5:1，所以整档调深到 5.41:1。这是审计点名的硬性不达标项。
         */
        const val TXT_SECTION = 0xFF5E5E6A.toInt()   // 5.41:1
        const val TXT_MUTED = 0xFF5A5A66.toInt()     // 5.76:1
        /** 失败原因文字（原来是就绪字面量 0xFF8A4A4A），收进调色板并核算：5.63:1 达标。 */
        const val TXT_ERROR = 0xFF8A4A4A.toInt()     // 5.63:1

        // ------------------------------------------------------------ 语义色
        // 这四个色同时担两个角色：生气等级**数字**的文字色、进度条/分段条的**填充色**。
        // 当文字用时必须过 4.5:1，而原值（尤其 GREEN/ORANGE/AMBER）在浅底上只有 2.2~2.7，
        // 白字压在其上也只有 2.4~3.0，两头都不达标 —— 所以四色整体加深：
        // 加深后既可作小号文字，也仍然可以作填充，不必为两个用途分裂成两套色。
        //
        // 【与 NukeTheme 的关系：两套**并行**的语义体系，不要合并】
        // NukeTheme 有它自己的 4 档语义槽位（OK / WARN / CAUTION / DANGER，各含明暗两值 + BG/BORDER）。
        // 本文件的 DANGER/ORANGE/AMBER/GREEN 与它们**语义对应但取值不同**，这是刻意为之，不是疏漏：
        //   · NukeTheme 的槽位假设「面板自有底色」——底色是模块自己画的，所以可以按深色面板取色；
        //   · 本卡片贴在**不可知的宿主聊天底色**上（微信/抖音/QQ 的明暗主题都不受我们控制），
        //     所以必须「自带对比度保障」：玻璃层不透明度锁死在 ≥0xFA，让合成底色收敛到
        //     #E9ECF5~#FFFFFF，四色因此必须按这个浅底反算，天然比 NukeTheme 的深色面板取值更深。
        // 结论：**两套体系并行的根因是「宿主底色不可知」**。若将来要把它们统一，切入点在这里——
        // 得先让卡片拿到一个确定的底色前提（例如固定跟随宿主明暗、或改用不透明卡片），
        // 否则合并后必然有一侧掉对比度。在此之前请保持自包含、不要 import NukeTheme。
        const val DANGER = 0xFFC22B2B.toInt()        // 玻璃底 4.84:1 / 白字压其上 5.72:1
        const val GREEN = 0xFF1C7040.toInt()         // 5.17:1
        const val ORANGE = 0xFF9E5200.toInt()        // 4.86:1
        const val AMBER = 0xFF7E6100.toInt()         // 4.94:1
    }

    companion object {
        private const val TAG = "LuoLiao"

        // ---------------------------------------------------------------- 圆角节奏
        // 与主体系（NukeTheme 的 24/18/12/10/6）对齐后的取值。原文件是 10/7/5/4/2，
        // 与旁边的模块面板/对话框差太多，同屏会读成「新旧混搭」。这里只保留四个语义档：
        // 卡片 18、内部小块 12、chip 10、徽标 6；细进度条/分段条（2~4dp）属控件内部细节，不动。
        private const val RADIUS_CARD = 18
        private const val RADIUS_INNER = 12
        private const val RADIUS_CHIP = 10
        private const val RADIUS_BADGE = 6

        /** The card's 官网 button target. */
        const val OFFICIAL_SITE = "https://aill.luoyu.ltd/"

        fun findIn(container: ViewGroup): AnalysisCard? {
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i)
                if (c is AnalysisCard) return c
                if (c is ViewGroup) {
                    val nested = findIn(c)
                    if (nested != null) return nested
                }
            }
            return null
        }

        fun findAllIn(container: ViewGroup, out: MutableList<AnalysisCard>) {
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i)
                if (c is AnalysisCard) out.add(c)
                if (c is ViewGroup) findAllIn(c, out)
            }
        }

        fun removeFrom(container: ViewGroup) {
            var found = false
            for (i in container.childCount - 1 downTo 0) {
                val c = container.getChildAt(i)
                if (c is AnalysisCard) {
                    container.removeViewAt(i)
                    found = true
                    continue
                }
                if (c is ViewGroup && findIn(c) != null) removeFrom(c)
            }
            if (found) container.requestLayout()
        }
    }

    init {
        orientation = VERTICAL
        background = glassCard(RADIUS_CARD)
        // 内边距保持改造前的 12/10（不外扩）：tap48 给点击行带来了额外高度，
        // 若再把 padding 一起加大，卡片在聊天列表里会明显变得臃肿。两者相抵，净高度变化最小。
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
            marginEnd = dp(6)
        }
    }

    // ------------------------------------------------------------------ 玻璃绘制
    //
    // 所有玻璃面都在这里手工分层拼装。**没有一处使用模糊**：质感完全靠
    // 「渐变面 + 1dp 描边 + 顶部受光高光」三层叠加模拟，绘制成本与普通 Drawable 相同。

    /**
     * 卡片主体：两层叠加的玻璃。
     *
     * 底层 = TL_BR 双向渐变（左上受光偏白、右下偏冷），模拟一块有厚度的玻璃板；
     * 顶层 = 自上而下的白色渐隐带，从 30% 透明的白平滑衰减到全透明，模拟镜面受光；
     * 描边 = 1dp hairline，收出卡片边界。
     *
     * 高光是**整卡高度**的连续衰减，不是顶部一条硬边色带：LayerDrawable 的 inset 只能按像素
     * 指定，而卡片高度是 WRAP_CONTENT（随内容变化），拿不到确定高度。用一条贯穿全高的
     * 渐变替代「限制在顶部 N% 」的写法，既不需要知道高度，也不会在内容变高时露出色带断口。
     *
     * 为什么用 LayerDrawable 而不是一张渐变图：这是纯代码绘制（模块没有自己的资源），
     * LayerDrawable 能让「面」和「高光」用各自的几何与透明度组合，而不必把两者烘焙进同一张图。
     */
    private fun glassCard(radiusDp: Int): Drawable {
        val face = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Palette.CARD_TOP, Palette.CARD_BOTTOM),
        ).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Palette.CARD_STROKE)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.SHEEN, Color.TRANSPARENT),
        ).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
            cornerRadius = dp(radiusDp).toFloat()
        }
        return LayerDrawable(arrayOf(face, sheen))
    }

    /**
     * 嵌套玻璃面：回复块、chip、气泡、追问输入框的底。
     *
     * 它画在卡片**之上**，所以透出来的是卡片本身（近白 #E9ECF5~#FFFFFF），
     * 而不是宿主聊天背景 —— 这里可以放心用更强的半透明来做层次，不可能影响文字对比度。
     *
     * @param border 描边色；chip 这类「同色描边按钮」传入语义色，其余传默认 hairline。
     */
    private fun glassInner(radiusDp: Int, border: Int = Palette.INNER_STROKE): Drawable {
        val face = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Palette.GLASS_INNER, Palette.GLASS_INNER),
        ).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), border)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.SHEEN, Color.TRANSPARENT),
        ).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
            cornerRadius = dp(radiusDp).toFloat()
        }
        return LayerDrawable(arrayOf(face, sheen))
    }

    /**
     * 实心强调面：徽标、编辑 pill、我的气泡、进度条填充。
     *
     * 用 TL_BR 微渐变代替纯色 —— 纯色贴片在玻璃体系里会像一块「塑料贴纸」，同色系的高低位渐变
     * 才能保持玻璃语汇一致（这也正是 NukeTheme 对 featurePanel 的做法）。
     *
     * **渐变方向是「原色 → 略暗」，而不是「略亮 → 原色」**：白字压在这些面上，如果受光端比原色
     * 更亮，白字与该端的对比度就会被拉低（实测受光端 4.75:1，余量已经很薄）。改成向下变暗后，
     * 整个面上最亮的地方就是原色本身，于是白字对比度**恒等于原色的核算值**（≥5.7:1），
     * 不会因为渐变而变差。同时「上亮下暗」在视觉上同样成立了受光感。
     */
    private fun solidAccent(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(color, darken(color, 0.12f)),
    ).apply {
        gradientType = GradientDrawable.LINEAR_GRADIENT
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** 把颜色朝黑色压暗 [amount]（0..1），用于实心面的渐变的「厚度端」。 */
    private fun darken(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    /**
     * 把一个「视觉上很小」的控件包成 ≥48dp 的有效点击目标。
     *
     * 无障碍要求触控目标 ≥48dp，但卡片里 chip / 编辑 pill / 展开折叠在视觉上只有 19~26dp 高。
     * 做法是外面套一个 48dp 高的**透明容器**，容器才是真正的点击目标（承载同一回调），
     * 里面的 pill 保持原来的尺寸、字体与内边距，**像素级外观不变**。
     *
     * 为什么不直接 setMinimumHeight(48dp) 在 pill 自己身上：那会把 pill 的**背景**一起撑成
     * 48dp 高，玻璃面变成一条粗色块，视觉直接坏掉。容器是透明无背景的，所以撑高它不影响观感。
     *
     * ⚠️ **不要改回「用负 margin 抵消增高」的写法。** 那个方案**已被实际验证为无效**：
     * 负 margin 确实能让父行高度不变，但父行因而比容器矮，而 ViewGroup 默认 `clipChildren=true`
     * 会把越界的子控件**裁掉**（chip 的上下边会被切掉），且**触摸事件只投递给父行自身边界内的
     * 区域**，于是有效点击区仍然只有父行那么高 —— 视觉和触控两头都不达标。它看着聪明，实际
     * 什么也没做到。
     *
     * 正确做法就是当前实现：让**承载行本身**具备 48dp 的触控高度。这也正是 Material 对 chip 的
     * 标准做法（chip 视觉 32dp、触控行 48dp）—— 不是权宜之计，而是业界验证过的模式。
     *
     * 【代价与裁决】这些行会比改造前高约 20dp，卡片整体略微更「透气」。
     * 该取舍已由队长裁决**维持现状**，理由是：需求要的是「可点击区域尺寸合理」，
     * 并没有要求「视觉高度不许变」；而**够不到的可点区域是真实的功能缺陷**，
     * 高度变化只是观感调整——功能缺陷的优先级更高。
     * 为把净增高压到最小，本文件**刻意没有**同步加大卡片 padding（保持原 12/10）。
     *
     * 若将来确实需要压回高度，正确方向是**降低视觉密度**（例如把 chip 收进一行、
     * 或缩短文案），而**不是**改回负 margin／TouchDelegate 那类"玄学扩容"。
     */
    private fun tap48(inner: TextView, onClick: () -> Unit, startMarginDp: Int = 0): View {
        val target = dp(48)
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 宽度同样保底 48dp；文字短的 chip（如「官网」）因此也能拿到横向的点击余量。
            minimumWidth = target
            minimumHeight = target
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(startMarginDp) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        inner.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        box.addView(inner)
        return box
    }


    // ------------------------------------------------------------------ render

    fun render(
        state: Any,
        turns: List<ChatTurn>,
        onReanalyze: () -> Unit,
        onFollowUp: (String) -> Unit,
        onDelete: () -> Unit,
        onEdit: (String) -> Unit,
    ) {
        lastState = state
        lastTurns = turns
        onReanalyzeCb = onReanalyze
        onFollowUpCb = onFollowUp
        onDeleteCb = onDelete
        onEditCb = onEdit
        rebuild()
    }

    /**
     * 渲染当前状态（整卡重建）。
     *
     * ─────────────────────────────────────────────────────────────────────────
     * ★ v10.3.0：删掉了原来的"状态没变就跳过重建"分支，因为它是**死代码**。
     *
     * 那段的守卫条件是 `state === renderedState && followUpField != null && ...`，
     * 而 [followUpField] 是内联输入框的引用 —— 内联 EditText 早就因为"在 RecyclerView 行里
     * 丢焦点、丢文本、丢输入法会话"而废弃，改由 [FollowUpDialog] 弹窗输入。
     * 结果是**全类没有任何地方给它赋过值**，它恒为 null，这个分支永远不成立。
     *
     * 留着它的代价不是"多几行"，而是它会让人误判流程：流式追问的每次增量都走整卡重建，
     * 而读代码的人会以为"大部分情况会走增量刷新"。删掉后事实就只有一个：
     * **每次 render 都是整卡重建**，这既让 [refreshTurns] 的实现有据可依，
     * 也让"为什么只重建就能显示出半截答案"变得一目了然。
     */
    private fun rebuild() {
        val state = lastState ?: return
        renderedState = state
        removeAllViews()
        val reanalyze = onReanalyzeCb ?: {}
        val followUp = onFollowUpCb ?: {}
        val delete = onDeleteCb ?: {}
        val edit = onEditCb ?: {}

        when (state) {
            is com.luoliaoo.wechat.hook.AnalysisController.CardState.Loading ->
                renderLoading()

            is com.luoliaoo.wechat.hook.AnalysisController.CardState.Error ->
                renderError(state.message, reanalyze, delete)

            is com.luoliaoo.wechat.hook.AnalysisController.CardState.Done ->
                renderResult(state.result, reanalyze, delete, edit)

        }

        if (lastTurns.isNotEmpty()) {
            addView(sectionTitle("追问"))
            for (t in lastTurns) addView(bubble(t))
        }

        if (state is com.luoliaoo.wechat.hook.AnalysisController.CardState.Done) {
            addView(followUpInput(followUp))
        }

        selfHeal()
    }

    // ------------------------------------------------------------------ states

    private fun renderLoading() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(ProgressBar(context).apply {
            layoutParams = LayoutParams(dp(16), dp(16))
        })
        row.addView(TextView(context).apply {
            text = "AI 正在分析…"
            setTextColor(Palette.TXT_MUTED)
            textSize = NukeTheme.sp(context, 13f)
            setPadding(dp(8), 0, 0, 0)
        })
        addView(row)
    }

    private fun renderError(message: String, reanalyze: () -> Unit, delete: () -> Unit) {
        addView(header("分析失败", -1, null))
        addView(TextView(context).apply {
            text = message
            // 原先是就绪字面量 0xFF8A4A4A，现收进调色板（TXT_ERROR）并核算过 5.63:1。
            setTextColor(Palette.TXT_ERROR)
            textSize = NukeTheme.sp(context, 13f)
            setPadding(0, dp(6), 0, 0)
        })
        addView(footer(reanalyze, delete))
    }

    private fun renderResult(
        r: AnalysisResult,
        reanalyze: () -> Unit,
        delete: () -> Unit,
        edit: (String) -> Unit,
    ) {
        addView(header(if (collapsed) summary(r) else "落聊分析", r.angerLevel) {
            collapsed = !collapsed
            rebuild()
        })

        if (r.isFallback) {
            addView(TextView(context).apply {
                text = r.rawFallback ?: ""
                setTextColor(Palette.TXT_REPLY)
                textSize = NukeTheme.sp(context, 13f)
                setPadding(0, dp(8), 0, 0)
            })
            addView(footer(reanalyze, delete))
            return
        }
        if (collapsed) return

        if (r.question.isNotBlank()) {
            addView(sectionTitle("字面问题"))
            addView(TextView(context).apply {
                text = r.question
                setTextColor(Palette.TXT_QUESTION)
                textSize = NukeTheme.sp(context, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }
        for (o in r.intentOptions) addView(optionRow(o, Palette.ACCENT, edit))

        if (r.realIntent.isNotEmpty()) {
            addView(sectionTitle("当前真实意图"))
            for (o in r.realIntent) addView(optionRow(o, Palette.ACCENT_2, edit))
        }

        if (r.angerLevel >= 0) {
            addView(sectionTitle("生气等级"))
            val ar = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ar.addView(TextView(context).apply {
                text = r.angerLevel.toString()
                setTextColor(angerColor(r.angerLevel))
                textSize = NukeTheme.sp(context, 22f)
                typeface = Typeface.DEFAULT_BOLD
            })
            ar.addView(TextView(context).apply {
                text = " /10"
                setTextColor(Palette.TXT_SECTION)
                textSize = NukeTheme.sp(context, 12f)
                setPadding(0, dp(6), dp(10), 0)
            })
            ar.addView(angerBar(r.angerLevel), LayoutParams(0, dp(10), 1f))
            addView(ar)
        }

        r.comment?.let {
            addView(TextView(context).apply {
                text = it
                setTextColor(Palette.TXT_COMMENT)
                textSize = NukeTheme.sp(context, 12f)
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        addView(footer(reanalyze, delete))
    }

    // ------------------------------------------------------------------ pieces

    private fun header(titleText: String, anger: Int, onToggle: (() -> Unit)?): View {
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(context).apply {
            text = "落聊"
            setTextColor(Color.WHITE)
            textSize = NukeTheme.sp(context, 10f)
            typeface = Typeface.DEFAULT_BOLD
            // 徽标：实心强调面（渐变）。白字压在其上，对比度 = ACCENT 的核算值 5.76:1。
            background = solidAccent(Palette.ACCENT, RADIUS_BADGE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        })
        if (anger >= 0) {
            head.addView(TextView(context).apply {
                text = "🔥" + anger
                /* ★ v9.1.2 修复：这里原本没设文字颜色。
                   emoji 本身自带颜色不受影响，但**后面的数字**用的是宿主主题默认色 ——
                   亮色模式下可能与卡片底的对比不足。跟隔壁的标题（Palette.TXT_TITLE）
                   统一，因为它们是同一行里紧挨着的两段文字，颜色不一致会显得脏。 */
                setTextColor(Palette.TXT_TITLE)
                textSize = NukeTheme.sp(context, 11f)
                setPadding(dp(6), 0, 0, 0)
            })
        }
        head.addView(TextView(context).apply {
            text = "  " + titleText
            setTextColor(Palette.TXT_TITLE)
            textSize = NukeTheme.sp(context, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onToggle != null) {
            val toggle = TextView(context).apply {
                text = if (collapsed) "展开" else "折叠"
                setTextColor(Palette.ACCENT)
                textSize = NukeTheme.sp(context, 12f)
                setPadding(dp(10), dp(4), dp(2), dp(4))
            }
            // 展开/折叠的视觉高度只有约 22dp，远低于 48dp 的无障碍下限。
            // tap48 把它包进 48dp 高的透明点击行，视觉尺寸与字体完全不变。
            head.addView(tap48(toggle, onToggle))
        }
        return head
    }

    private fun sectionTitle(t: String): View = TextView(context).apply {
        text = t
        setTextColor(Palette.TXT_SECTION)
        textSize = NukeTheme.sp(context, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(2))
    }

    /**
     * One option: label + percent (+ 编辑 when a reply exists), the suggested reply block, then the
     * probability bar -- the layout requested by the user:
     *   label  55%  编辑
     *   回复：...
     *   [progress bar]
     */
    private fun optionRow(
        o: AnalysisResult.Option,
        color: Int,
        edit: (String) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextView(context).apply {
            text = o.label
            setTextColor(Palette.TXT_LABEL)
            textSize = NukeTheme.sp(context, 13f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(context).apply {
            text = o.probability.toString() + "%"
            setTextColor(color)
            textSize = NukeTheme.sp(context, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        })
        if (!o.reply.isNullOrBlank()) {
            val editPill = TextView(context).apply {
                text = "编辑"
                setTextColor(Color.WHITE)
                textSize = NukeTheme.sp(context, 11f)
                // 实心强调面。白字压其上，对比度 = color 的核算值（ACCENT 5.76:1 / ACCENT_2 6.02:1）。
                background = solidAccent(color, RADIUS_CHIP)
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }
            // 编辑 pill 视觉高度约 19dp：用 tap48 包成 48dp 点击行。
            // startMarginDp = 8 保留改造前该 pill 与百分比之间的间距。
            line.addView(tap48(editPill, { edit(o.reply.trim()) }, startMarginDp = 8))

            // ================================================== v10.3.1：复制 pill
            //
            // 【需求】在「编辑」旁边加一个「复制」，把**这一条回复的内容**写进系统剪贴板。
            //
            // 【为什么是「本条」而不是「整卡」】卡片底部本来就有一个「复制全部」（footer），
            // 它导出整份分析。用户要的是「只想把这条建议发出去」时不必先开编辑框再全选，
            // 所以这里复制的是**这一个 option 的 reply**，与「编辑」写进输入框的**是同一段文字**
            // （两者都取 `o.reply.trim()`）。这条一致性是刻意的：同一行上的两个按钮若处理不同的
            // 文本，用户永远猜不准哪个对应哪段。
            //
            // 【为什么做成描边式（glassInner）而不是第二个实心 pill】实心面在这套玻璃语汇里
            // 表示「主操作」，一行里出现两个实心块会分不出主次；复制是次要动作（不改变任何状态），
            // 所以改用与 chip 同款的同色描边玻璃 —— 与卡片其它次级控件同一语言。
            // 文字用 `color`（该 section 的语义色），对比度沿用 chip 的核算值。
            //
            // 【为什么同样包 tap48】复制 pill 与编辑 pill 视觉尺寸完全一致（11sp / 3dp 内边距，
            // 约 19dp 高），若不包就会成为本行里唯一够不到 48dp 的点击目标。
            val copyPill = TextView(context).apply {
                text = "复制"
                setTextColor(color)
                textSize = NukeTheme.sp(context, 11f)
                background = glassInner(RADIUS_CHIP, border = color)
                setPadding(dp(9), dp(3), dp(9), dp(3))
            }
            line.addView(tap48(copyPill, { copyToClipboard(o.reply.trim()) }, startMarginDp = 6))
        }
        row.addView(line)

        // The reason behind this option, shown above the bar.
        if (!o.reason.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                text = "原因：" + o.reason.trim()
                setTextColor(Palette.TXT_COMMENT)
                textSize = NukeTheme.sp(context, 12f)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(4), 0, 0)
            })
        }

        if (!o.reply.isNullOrBlank()) {
            row.addView(TextView(context).apply {
                text = "回复：" + o.reply.trim()
                setTextColor(Palette.TXT_REPLY)
                textSize = NukeTheme.sp(context, 13f)
                setLineSpacing(dp(3).toFloat(), 1f)
                // 嵌套玻璃面：叠在卡片之上，透出的是卡片本身（近白），可放心用更强半透明。
                background = glassInner(RADIUS_INNER)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            })
        }

        row.addView(probabilityBar(o.probability, color))
        return row
    }

    private fun probabilityBar(probability: Int, color: Int): View {
        val p = probability.coerceIn(0, 100)
        val track = LinearLayout(context).apply {
            orientation = HORIZONTAL
            // 轨道是半透明的「凹槽」而不是一块实色贴片：玻璃体系里，凹槽要能透出底下的卡片，
            // 只有填充条是实心强调色，这样一眼能分出「槽」和「值」。圆角 4dp 属控件内部细节，保留。
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Palette.TRACK)
                setStroke(dp(1), Palette.INNER_STROKE)
            }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7),
            ).apply { topMargin = dp(5) }
        }
        if (p > 0) {
            track.addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(color, darken(color, 0.12f)),
                ).apply {
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, p.toFloat(),
                )
            })
        }
        if (p < 100) {
            track.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - p).toFloat(),
                )
            })
        }
        return track
    }

    private fun angerBar(level: Int): View {
        val segments = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(2), 0, 0, 0)
        }
        for (i in 1..10) {
            val on = i <= level
            segments.addView(View(context).apply {
                // 分段条：2dp 圆角属控件内部细节（保留）。点亮的段用语义色渐变，
                // 熄灭的段用半透明胶槽色，保持与进度条同一套「槽 + 值」语汇。
                background = if (on) {
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(angerColor(level), darken(angerColor(level), 0.14f)),
                    ).apply {
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        cornerRadius = dp(2).toFloat()
                    }
                } else {
                    GradientDrawable().apply {
                        cornerRadius = dp(2).toFloat()
                        setColor(Palette.TRACK)
                    }
                }
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = if (i < 10) dp(2) else 0
                }
            })
        }
        return segments
    }

    private fun footer(reanalyze: () -> Unit, delete: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(chip("复制全部", Palette.ACCENT, onClick = {
            // 无状态时也要给出提示：`lastState?.let {}` 在空状态下**什么都不做**，
            // 而「点了没反应」与「按钮坏了」在用户眼里是同一件事。
            copyToClipboard(lastState?.let { toPlainText(it) }.orEmpty())
        }))
        row.addView(chip("重新分析", Palette.ACCENT, reanalyze))
        row.addView(chip("官网", Palette.ACCENT, onClick = { openOfficialSite() }))
        row.addView(chip("删除", Palette.DANGER, delete))
        return row
    }

    /**
     * Opens the project's website in the device's DEFAULT browser.
     *
     * An explicit ACTION_VIEW with an http URL is what makes Android hand the link to the browser the user
     * chose, with no chooser dialog and no in-app WebView. The flag is required because this runs from a
     * view inside WeChat's process, not from one of our own activities.
     *
     * A failure (no browser installed, or the OEM blocked it) is reported instead of vanishing: a button
     * that silently does nothing is indistinguishable from a broken one.
     */
    private fun openOfficialSite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_SITE)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "opening the official site failed: " + t.message)
            try {
                Toast.makeText(context, "无法打开浏览器，请手动访问 " + OFFICIAL_SITE, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
                // nothing else we can do
            }
        }
    }

    /**
     * item 1: the placeholder shown while a follow-up answer is being generated.
     *
     * Without it the card stopped updating after the question was added, which read as a hang.
     */
    /**
     * item 1: 答案还在生成时的占位气泡。
     *
     * 没有它，卡片在用户提出问题后就不再变化，看起来像卡死。
     *
     * ★ v10.3.0 改造：接手**流式半截答案**。
     *   * [partial] 为空 → 还是「AI 正在思考 ing」（首字尚未到达）；
     *   * [partial] 非空 → 直接画这半截文本，并换成正式 AI 气泡的配色/描边。
     * 之所以在这个函数里做而不是另开一条渲染路径：它是 pending 气泡**唯一的创建点**，
     * 整卡重建与增量追加最终都走到这里，所以无论气泡怎么出现都覆盖得到。
     */
    private fun thinkingBubble(partial: String = ""): View {
        val streaming = partial.isNotEmpty()
        val wrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            gravity = Gravity.START
        }
        val tv = TextView(context).apply {
            text = if (streaming) partial else "AI \u6b63\u5728\u601d\u8003 ing"
            textSize = NukeTheme.sp(context, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (streaming) {
                // 已经有真内容了，就用和落定后一致的外观，避免出完字还留着"思考中"的样式。
                setTextColor(Palette.TXT_LABEL)
                background = glassInner(RADIUS_INNER)
            } else {
                setTextColor(Palette.ACCENT)
                // 嵌套玻璃面 + 强调色描边：与 footer 的 chip 同一套「同色描边按钮」语汇，
                // 读起来就是「正在发生」而不是一块静止的提示条。圆角对齐 RADIUS_INNER(12)。
                background = glassInner(RADIUS_INNER, border = Palette.ACCENT)
            }
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        wrap.addView(tv)
        /* ★ v10.3.0：把它登记为流式增量的落笔目标（见 [updateStreamText]）。
           在这里登记而不是去查找，是因为**这里是"等待中"气泡唯一的创建点** ——
           整卡重建与面板重画最终都走到这里，所以每次重建后这个引用都会自动刷新成
           当前真正显示的那个 TextView。
           [thinkingLook] 与外观同步记录，让热路径知道"要不要顺手换掉思考中的配色"。 */
        streamTarget = tv
        thinkingLook = !streaming
        return wrap
    }

    /**
     * ★ v10.3.0：把**新的追问数据**画到卡上（主线程）。
     *
     * 这是流式输出在本类里唯一的入口，而且它**不接收文本** —— 文本来自 [lastTurns]，
     * 也就是数据模型本身。这是刻意的：
     *
     *   第一版曾经让调用方直接传文本、并抓住一个"活着的 TextView"去改写。那条路在本模块
     *   结构上走不通 —— 卡片是 overlay，`FloatingPanel` 每次重画都 new 一张全新的
     *   AnalysisCard，旧引用立刻作废，写进去的字下一次重画就被覆盖。
     *   现象就是"一个字都不流、最后闪一下"。所以这里改成只接受"数据变了"这个事实。
     *
     * 【实现选择：整卡重建，而不是只换那一条气泡】
     * 曾经想过"只重画最后那条气泡"来省开销，但那需要记录气泡在子 View 里的下标，
     * 而气泡后面还跟着「追问 AI」按钮，一旦下标算错就会**把按钮删掉**。
     * 权衡之下这里选择整卡重建：
     *   * render() 那条路径本来就被证明画得对（非流式时代的追问就是靠它显示的）；
     *   * 卡片重建是纯 View 构造，没有 IO、没有网络，代价远小于一次网络往返；
     *   * 少一套"下标维护"就少一类崩溃。
     * 若将来实测确实卡顿，再考虑窄化 —— 那时也应先加测量，而不是先加机制。
     */
    fun refreshTurns(turns: List<ChatTurn>) {
        try {
            lastTurns = turns
            /* 直接调 rebuild()，而不是再走一遍 render()：
               * render() 的第一件事就是把这些回调重新赋一遍，而它们本来就还在；
               * lastState 是可空类型，转发给 render(state: Any) 还要多做一次空判断；
               * rebuild() 恰恰就是"用当前数据重画"的那一步，语义正好。
               还在加载中/出错状态时 lastState 一定非空（render 之前先赋的），
               所以这里的 null 判断只是为了防御性收口。 */
            if (lastState != null) rebuild()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshTurns failed: " + t.message)
        }
    }

    /**
     * ★ v10.3.0：流式增量的**热路径** —— 只改那一条气泡的文字（主线程）。
     *
     * ─────────────────────────────────────────────────────────────────────────
     * 【为什么必须窄化到这一步：整卡重建会让屏幕狂闪】
     * 上一版让每个增量都走"整卡重建"，结果是每收到一个字就：
     *     面板 removeAllViews() → 重建标题栏/联系人行/用量行 → 重建整张卡片
     * 屏幕肉眼可见地狂闪，用户的原话是"眼睛都要花掉了"。所以热路径**不能碰任何重建**。
     *
     * 【做法：在气泡里预埋一个 TextView，找到它就只改它的 text】
     * [thinkingBubble] 创建那个气泡时把它的 [TextView] 记在 [streamTarget] 上。
     * 之后每个增量只做一件事：`streamTarget.text = 新文本`。
     * 只要改一个字，`TextView` 自己只做一次局部重绘，不重建任何父级、不动滚动位置、
     * 标题栏和按钮完全不受影响。
     *
     * 【什么时候走整卡重建】只有当 [streamTarget] 已经失效时（卡片被重建过、
     * 面板被关过、或这条追问已经落定）。此时交回 [refreshTurns] 走一次正常重画，
     * 由它重新建立 [streamTarget]，后续增量又能回到"只改文字"的快路径。
     * 这样两种路径互为兜底，不会出现"卡住不动"。
     *
     * @return true 表示已就地更新（无需重建）；false 表示调用方应当整卡重画一次。
     */
    fun updateStreamText(text: String): Boolean {
        val v = streamTarget ?: return false
        return try {
            // parent == null ⇒ 这个控件已经被 removeAllViews() 摘下来了，写进去用户也看不见。
            if (v.parent == null) return false
            if (v.text.toString() == text) return true
            /* 第一次写入真内容时顺手把外观从"思考中"换成正式气泡的样子。
               为什么在这里做而不是重建气泡：这个函数就是热路径，**绝不能重建**。
               但只改文字又会留下"AI 正在思考 ing"那套强调色 + 强调描边 ——
               出完字还顶着"正在思考"的配色会让人以为还没结束。
               两全的做法：只在 `thinkingLook == true` 那一次多改两行属性（一次性开销），
               之后每帧仍然只有一次 `text = ...`。 */
            if (thinkingLook) {
                thinkingLook = false
                v.setTextColor(Palette.TXT_LABEL)
                v.background = glassInner(RADIUS_INNER)
            }
            v.text = text
            true
        } catch (t: Throwable) {
            Log.w(TAG, "updateStreamText failed: " + t.message)
            false
        }
    }

    /**
     * ★ v10.3.0：流式落定后就地收尾（主线程）。
     *
     * 【为什么必须做这一步，而不是只改文字就够】
     * 落定时控制器把数据模型里那条 `pending = true` 换成了正式回答（`settlePendingTurn`），
     * 但**卡片自己缓存的 [lastTurns] 还是旧的**（仍带 pending）。
     * 只要之后发生任何一次重建（用户切列表再切回来、面板被系统重建），
     * `rebuild()` 就会照着那份旧数据把气泡重新画成「AI 正在思考 ing」——
     * 已经答完的问题又"退回去思考"，这是最难理解的一类现象。
     * 所以这里把缓存的尾部同步成落定后的样子。
     *
     * @return true 表示就地收尾成功；false 表示调用方应当走完整重画。
     */
    fun settleStreamText(text: String): Boolean {
        return try {
            if (!updateStreamText(text)) return false
            /* 同步缓存。只替换尾部那一条：流式追问是串行的，
               这次落定改动的必然是最后一条（更早的轮次在这次操作里没被碰过）。 */
            if (lastTurns.isNotEmpty()) {
                val tail = lastTurns.last()
                if (tail.pending && !tail.fromMe) {
                    lastTurns = lastTurns.dropLast(1) + ChatTurn(
                        fromMe = false,
                        text = text,
                        time = tail.time,
                    )
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "settleStreamText failed: " + t.message)
            false
        }
    }

    /**
     * 追问气泡：我的在右，AI 的在左。
     *
     * item 1：答案还在生成时（pending）画成强调色的提示，而不是留空 —— 否则卡片看起来像卡死了。
     * 处理放在**这里**，同时覆盖两条渲染路径（render() 的整卡重建与增量追加），
     * 占位提示不可能被其中一条漏掉。
     *
     * ★ v10.3.0：pending 期间如果有**流式半截答案**（[ChatTurn.partial]），就画它而不是"正在思考"。
     *   这样"思考中"与"逐字出字"是同一套渲染，不会互相打架，也不需要任何控件引用。
     *   半截答案到达后立刻换成正式气泡的配色/描边，避免出完字还留着"思考中"的强调样式。
     */
    private fun bubble(t: ChatTurn): View {
        if (t.pending) return thinkingBubble(t.partial)
        val wrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            gravity = if (t.fromMe) Gravity.END else Gravity.START
        }
        wrap.addView(TextView(context).apply {
            text = t.text
            textSize = NukeTheme.sp(context, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (t.fromMe) setTextColor(Color.WHITE) else setTextColor(Palette.TXT_LABEL)
            // 「我」的气泡 = 实心强调面（白字压其上 5.76:1）；AI 的气泡 = 嵌套玻璃面（深字 12.72:1）。
            // 两者都叠在卡片之上，所以无论宿主聊天背景是明是暗都不受影响。
            background = if (t.fromMe) {
                solidAccent(Palette.ACCENT, RADIUS_INNER)
            } else {
                glassInner(RADIUS_INNER)
            }
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        return wrap
    }

        /**
     * The card's follow-up control.
     *
     * A single button that opens FollowUpDialog. An inline EditText was tried and abandoned: the card
     * lives in a constantly re-laid-out RecyclerView row, where the field lost focus, its text and its
     * IME session.
     */
    private fun followUpInput(onFollowUp: (String) -> Unit): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        wrap.addView(chip("追问 AI", Palette.ACCENT, onClick = {
            com.luoliaoo.wechat.ui.FollowUpDialog.show(context, "") { text ->
                onFollowUp(text)
            }
        }))
        // 提示文字与 chip 同排。chip 现在被 tap48 包成了 48dp 高的点击容器，
        // 该容器用负 margin 抵消高度，所以这一行的实际高度与改造前一致。
        wrap.addView(TextView(context).apply {
            text = "点击后输入"
            textSize = NukeTheme.sp(context, 12f)
            setTextColor(Palette.TXT_SECTION)
        })
        return wrap
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit): View {
        val inner = TextView(context).apply {
            text = label
            setTextColor(color)
            textSize = NukeTheme.sp(context, 12f)
            // chip 是描边式玻璃按钮：嵌套玻璃面 + 1dp 同色描边（按钮语义色由调用方决定）。
            background = glassInner(RADIUS_CHIP, border = color)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        // chip 视觉高度约 26dp：包成 48dp 点击行，外观不变；横向间距用 marginEnd 表达，
        // 这样每个 chip 的点击区互不重叠。
        return tap48(inner, onClick).also { box ->
            (box.layoutParams as? LayoutParams)?.marginEnd = dp(8)
        }
    }

    /**
     * 把一段文本写进系统剪贴板，并把**两种结果都报出来**。
     *
     * 调用方现在有两处：底部的「复制全部」（导出整份分析），以及每条回复右侧的「复制」（v10.3.1）。
     *
     * 【为什么空内容不写剪贴板】剪贴板写入是**破坏性**的：写一个空串会把用户之前复制的东西
     * 悄悄清掉，而用户可能只是误触。所以空内容直接提示「没有可复制的内容」并**保持剪贴板原样** ——
     * 「复制全部」在卡片还处于「分析中」这类状态时确实会取到空串，这条守卫是真实生效的路径，
     * 不是防御性写法。
     *
     * 【为什么成功与失败都要提示】剪贴板写入用户**看不见**：没有提示的话，按钮坏了与按钮好了
     * 在屏幕上完全一样。两条消息措辞不同，所以只看一张截图也能判断走的是哪一条。
     *
     * 【为什么用 as? 而不是 as】`getSystemService` 在平台注解上是 @NonNull，但在某些被包装过的
     * 宿主 Context 上确实可能拿到 null。模块绝不能因为一次复制而**崩掉宿主的聊天界面**，
     * 所以拿不到服务就如实报告，而不是让 ClassCastException/NPE 逃逸出去。
     */
    private fun copyToClipboard(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "copy: nothing to copy, the clipboard is left untouched")
            toast("没有可复制的内容")
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                Log.w(TAG, "copy: the clipboard service is unavailable")
                toast("复制失败：剪贴板不可用")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("落聊", text))
            Log.i(TAG, "copy: wrote " + text.length + " char(s) to the clipboard")
            toast("已复制到剪贴板")
        } catch (t: Throwable) {
            // 部分 Android 版本只允许前台应用写剪贴板；把失败讲清楚而不是抛给宿主。
            Log.w(TAG, "copy: the clipboard write failed: " + t.message)
            toast("复制失败：" + t.javaClass.simpleName)
        }
    }

    /**
     * A toast that can never take the card down with it.
     *
     * 卡片渲染在宿主的聊天界面里，Toast 在少数机型/被包装的 Context 上会抛异常；
     * 提示失败本身不该变成一次崩溃。
     */
    private fun toast(msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // a missing toast must not fail the card
        }
    }

    private fun toPlainText(state: Any): String {
        val r = (state as? com.luoliaoo.wechat.hook.AnalysisController.CardState.Done)?.result
            ?: return ""
        if (r.isFallback) return r.rawFallback.orEmpty()
        val sb = StringBuilder()
        if (r.question.isNotBlank()) sb.append(r.question).append('\n')
        for (o in r.intentOptions) {
            sb.append(" - ").append(o.label).append("：").append(o.probability).append("%\n")
            o.reason?.let { sb.append("   原因：").append(it).append('\n') }
        }
        if (r.realIntent.isNotEmpty()) {
            sb.append("当前真实意图\n")
            for (o in r.realIntent) {
                sb.append(" - ").append(o.label).append("：").append(o.probability).append("%\n")
                o.reason?.let { sb.append("   原因：").append(it).append('\n') }
                o.reply?.let { sb.append("   回复：").append(it).append('\n') }
            }
        }
        if (r.angerLevel >= 0) sb.append("生气等级：").append(r.angerLevel).append("/10\n")
        r.comment?.let { sb.append(it).append('\n') }
        return sb.toString().trim()
    }

    private fun summary(r: AnalysisResult): String {
        if (r.isFallback) return "（原始输出）"
        val top = r.realIntent.maxByOrNull { it.probability }
        return buildString {
            append(top?.let { it.label + " " + it.probability + "%" } ?: "—")
            if (r.angerLevel >= 0) append("  ·  生气 ").append(r.angerLevel).append("/10")
        }
    }

    private fun angerColor(level: Int): Int = when {
        level >= 8 -> Palette.DANGER
        level >= 5 -> Palette.ORANGE
        level >= 3 -> Palette.AMBER
        else -> Palette.GREEN
    }

    /**
     * Resets the card's layout params once if it measured deceptively short despite having
     * children.
     *
     * A one-line height with a Populated child tree means the height constraint collapsed (the bug
     * chased through several versions). Rather than failing silently, the constraint is replaced
     * with WRAP_CONTENT and the event is logged, so the failure is both visible and self-correcting.
     */
    private fun selfHeal() {
        post {
            try {
                if (childCount == 0) return@post
                val h = height
                val minLine = dp(40)
                if (h in 1 until minLine) {
                    val lp = layoutParams
                    if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        Log.w(
                            TAG,
                            "card measured only " + h + "px with " + childCount +
                                " children; resetting height to WRAP_CONTENT",
                        )
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        layoutParams = lp
                        requestLayout()
                    } else {
                        Log.w(TAG, "card measured only " + h + "px; parent may be constraining it")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "selfHeal failed: " + t.message)
            }
        }
    }

    /*
     * ★ v10.3.0：这里曾经有一个 refreshTurnBubblesIfNeeded()，已删除。它记录的**根因**必须留下。
     *
     * ============================ 追问完成后卡在「AI 正在思考 ing」的真正原因 ============================
     *
     * 旧实现靠"气泡数量变没变"来决定要不要刷新：
     *     if (lastTurns.size <= renderedTurnCount) return
     *
     * 这条判据是错的 —— 追问的整个生命周期里，**列表长度从来没变过**：
     *   * 用户提问时压入 2 条：我的问题 + 一条 `pending = true` 的**占位**回答；
     *   * 答案回来时只是把占位那条**原地改写**（pending 置 false、text 换成真答案）。
     * 长度始终是 2，所以这条守卫在"答案到达"这个唯一需要刷新的时刻**恰好提前 return**，
     * 于是占位气泡永远停在「AI 正在思考 ing」。
     *
     * 【为什么这个 bug 特别值得记下来】
     * 它表现成"请求卡住了"，但 `cards.json` 证明答案**早就落盘了** ——
     * 也就是说：看着像网络问题、像 AI 不返回，实际是**渲染没跟上**。
     * 排查时如果去查网络、查 API Key、查模型，会全都在正确的方向上做无用功。
     * 判据本身（"数量变了才算变"）在"原地替换"这种数据更新方式面前是失效的，
     * 这是这一类 bug 的共同形状：**用结构变化去近似内容变化**。
     *
     * 【现在怎么解决的】不再做增量判断。`rebuild()` 每次整卡重建，
     * 而 [refreshTurns] 直接调它 —— 重建一定会读到最新的 `turns`，
     * 因此 `partial` / `pending` / `text` 的任何变化都必然被画出来，不可能再漏。
     */

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()
}
