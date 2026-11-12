package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.ChatTurn
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo

/**
 * The module's floating window.
 *
 * ARCHITECTURE CHANGE. Cards used to be inserted into the message row (the bubble was wrapped in a
 * new vertical container with the card beneath it). That approach is abandoned, because a row is
 * RECYCLED by WeChat: the card stayed inside the view, the view was reused for a different message,
 * and the card then appeared under a message that was not its own -- repeatedly, despite every
 * ownership check I added.
 *
 * A card now lives in an overlay that never enters WeChat's view tree, so it cannot be carried into
 * another message. Placement is no longer a question of identifying the right row: there is no row
 * involved at all.
 *
 * Two surfaces:
 *   * the balloon  -- a small round button, draggable, always available
 *   * the panel    -- opened by tapping the balloon; shows either the LIST of cached analyses or one
 *                     CARD. Tapping the balloon while the panel is open closes it.
 *
 * The panel's width and maximum height come from the configuration.
 */
object FloatingPanel {

    private const val TAG = "LuoLiao"

    /**
     * ★ v10.3.0：当前屏幕上那张分析卡片（由 [renderCard] 在 addView 之后赋值）。
     *
     * 只被 [streamText] 的**快路径**使用：直接改这张卡里那条气泡的文字，
     * 从而避免每个字都重建整个面板（那会让屏幕狂闪）。
     *
     * 它**允许失效** —— 卡片每次 render 都会被 new 一个全新的实例，所以这个引用
     * 可能指向一张已经被替换掉的卡。写好兜底比"保证它永远有效"更现实：
     * 快路径会通过 `TextView.parent == null` 发现失效，然后回退到一次完整重画。
     */
    @Volatile
    private var liveCard: AnalysisCard? = null

    /**
     * ★ v10.3.0：面板上那行「本次用量」的 TextView（由 [render] 创建时登记）。
     *
     * 存在的理由与 [liveCard] 相同：让 [setUsage] 能**就地改写一行文字**，
     * 而不是为了一行小字把整个面板 `removeAllViews()` 重画 ——
     * 那正是用户报的"AI 思考好了以后闪一下"。
     *
     * 允许失效：面板每次 render 都会 new 一个全新的 TextView，所以旧引用会指向
     * 已经被移除的控件。[setUsage] 用 `parent == null` 判断失效并回退到重画。
     */
    @Volatile
    private var usageLine: TextView? = null

    /**
     * ★ v10.3.0：流式追问的**文本入口**（主线程调用）。
     *
     * ─────────────────────────────────────────────────────────────
     * 【两条路径，快路径优先】
     *   ① 快路径：当前屏幕上正好是这张卡 → 直接改那一条气泡的文字。
     *      `AnalysisCard.updateStreamText` 只做 `TextView.text = ...`，
     *      由控件自己完成一次局部重绘。**这是流式唯一能接受的开销**。
     *   ② 慢路径：卡片还没建出来、或那条气泡已失效 → 走一次 [refreshForStream] 完整重画。
     *      重画会把 [AnalysisCard] 的气泡重新创建，快路径随后又能继续用。
     *
     * 【为什么必须有快路径：整块重画会让屏幕狂闪】
     * [render] 的第一件事是 `p.removeAllViews()` —— 它重建的是**整个面板**：
     * 标题栏、四个按钮、联系人行、用量行、版本行，最后才是卡片。
     * 早期版本让每个增量都走这条路，结果每收到一个字就整屏重建一次，
     * 用户的原话是"疯狂闪屏幕，眼睛都要花掉了"。所以快路径不是优化，是**必需**。
     *
     * 【为什么慢路径也要留着】它保证"内容永远不会因为找不到目标而丢掉"：
     * 万一引用失效（面板刚被关闭过、卡片刚被重建），这一次重画就把它找回来。
     * 两条路径互为兜底 —— 比起"为了省事只留一条"，这个组合既不会闪、也不会卡住不动。
     *
     * @param key  卡片标识。与当前显示的不一致就直接丢弃（用户可能已经切到别的记录）。
     * @param text 累计至今的完整回答。整段设定而不是追加，避免漏掉一个增量就永久缺字。
     */
    fun streamText(key: String, text: String) {
        try {
            if (selected != key) return
            val c = liveCard
            // ① 快路径：只改一行文字，不碰任何父级。
            if (c != null && c.updateStreamText(text)) {
                // 快路径通了，说明界面已跟上；解除"回退已用过"的封禁。
                streamFallbackUsed = false
                return
            }
            /* ② 慢路径，但**每条流式只允许一次**。
               为什么必须限次数：快路径失败的场景里有一种是"重画也修不好"的 ——
               例如用户此刻正停在**分析记录列表**（不是卡片），此时 thinkingBubble 根本不会执行，
               于是重画一百次也建不出可写的目标。若不限次数，就会变成"每帧整屏重画一次"，
               屏幕狂闪的问题原封不动地回来（只是换了个触发条件）。
               限一次之后：能修好的（引用刚失效）第一次就修好；
               修不好的（不在卡片视图）就安静地跳过，等用户切回卡片时由既有的渲染路径补齐。 */
            if (streamFallbackUsed) return
            streamFallbackUsed = true
            refreshForStream()
        } catch (t: Throwable) {
            Log.w(TAG, "floating: streamText failed: " + t.message)
        }
    }

    /**
     * ★ v10.3.0：一条新的流式追问开始时调用，复位"回退已用过"的封禁。
     *
     * 不复位的话，第一条追问用掉那次回退机会后，**后续每一条追问**都无法再回退重建，
     * 只要它的快路径第一次没命中（例如卡片刚被切换、刚重建过），整条流式就会一个字都不显示。
     * 由控制器在追问发起时显式调用，语义比"在某处顺手清一下"清楚得多。
     */
    fun beginStream(key: String) {
        try {
            streamFallbackUsed = false
        } catch (_: Throwable) {
        }
    }

    /** ★ v10.3.0：本次流式是否已经用过"回退重建"（见 [streamText]）。 */
    @Volatile
    private var streamFallbackUsed = false

    /**
     * ★ v10.3.0：流式追问**落定**时的刷新入口（主线程调用）。
     *
     * ─────────────────────────────────────────────────────────────────────────
     * 【为什么不能直接调 reconcile / refresh：那会让卡片"闪一下"】
     * 追问结束时原先调的是 `reconcile()` → `refresh()` → `render()`，
     * 而 `render()` 的第一件事是 `p.removeAllViews()` —— 把**整个面板**拆掉重画。
     * 屏幕上就是"整块面板先消失、再拼回来"的那一下闪。
     *
     * 更要紧的是：**这次重画在视觉上是多余的**。落定那一刻，打字机已经把整段答案
     * 逐字显示完了，屏幕上那条气泡的内容与最终答案**完全一致**（外观也已在第一次
     * 写入真内容时换成了正式气泡的配色）。重画只是把一样的东西拆了又拼。
     *
     * 【什么时候仍然必须重画】只有一种：答案是空的（请求失败）。
     * 此时 `settlePendingTurn` 会把那条占位气泡**整条删掉** —— 要让一个控件消失，
     * 就地改文字是做不到的，只能重建。
     *
     * @param key  卡片标识，与当前显示不一致就直接丢弃。
     * @param text 最终答案。为空表示这次追问失败，占位气泡应当消失。
     */
    fun settleStreamText(key: String, text: String) {
        try {
            if (selected != key) return
            if (text.isEmpty()) {
                // 占位气泡要整条消失 → 必须重建（见上面的说明）。
                refreshForStream()
                return
            }
            val c = liveCard
            /* 就地更新到最后文本。正常情况下它与屏幕上已有的内容相同，因此这次调用
               不会产生任何视觉变化 —— 这正是不闪的原因。
               万一引用已失效（面板被关过、卡片被重建过），才回退到一次完整重画。 */
            if (c == null || !c.settleStreamText(text)) refreshForStream()
        } catch (t: Throwable) {
            Log.w(TAG, "floating: settleStreamText failed: " + t.message)
        }
    }

    /**
     * ★ v10.3.0：完整重画一次面板（流式慢路径 / 落定后的收尾刷新）。
     *
     * 它很贵（见 [streamText] 的说明），所以只允许在"必须重建"时调用：
     * 卡片首次出现、引用失效、以及占位气泡需要消失。
     */
    fun refreshForStream() {
        try {
            refresh()
        } catch (t: Throwable) {
            Log.w(TAG, "floating: refreshForStream failed: " + t.message)
        }
    }

    /** Balloon edge length in dp. */
    private const val BALLOON_DP = 52

    /**
     * item NEW: the balloon-size percentage bounds, and the floor the dp result may never go below.
     *
     * 50% is half the designed balloon -- small enough to be a real "make it smaller", still a
     * comfortable tap target. 200% is 104dp, generous without covering the chat. `MIN_BALLOON_DP` is a
     * second, independent floor on the computed dp: the percentage clamp alone would still allow a
     * tiny value if `BALLOON_DP` were ever reduced, and a balloon below ~24dp is not reliably tappable.
     */
    private const val BALLOON_SCALE_MIN = 50
    private const val BALLOON_SCALE_MAX = 200
    private const val MIN_BALLOON_DP = 28

    /**
     * v9.0.1 / t3: 标题栏按钮的统一宽度（dp）。
     *
     * 用户要求「四个按钮都在分析记录的右边 并且大小一致」。按钮的文案长度相同
     * （「官网」「关闭」「透明」「大小」各 2 字，卡片模式还多一个「重选」），
     * 但若各自按内容自适应仍会因字体度量差异而宽窄参差 —— 这正是用户看到的问题，
     * 所以改为一律取固定宽度。
     *
     * ============================ t3：为什么是 44dp（56 → 44 的账） ============================
     *
     * 【背景】用户要求「分析记录在官网左侧新增按钮 手动」，即列表模式从 4 个按钮变成 5 个：
     *     手动 | 官网 | 透明 | 大小 | 关闭
     *
     * 【关键：这个常量不是随便缩的，是为了「标题空间不变」反解出来的】
     *   面板内容宽 = **306dp**（文件里既有的推导：360dp 窄屏 × 94% − 左右内边距 32dp）。
     *   标题「分析记录」@14sp 粗体 ≈ **56~58dp**（原注释按 58dp 计，这里按 56dp 更乐观地估）。
     *
     *   改动前的账（4 个按钮，W=56、间距 4dp）：
     *       4 × (56 + 4) = 240dp   →   标题剩 306 − 240 = **66dp**   →   66 > 58，**刚好够，标题不截断**
     *
     *   如果只是「加一行 headerBtn」而不动宽度（5 个按钮，W=56、间距 4dp）：
     *       5 × (56 + 4) = 300dp   →   标题剩 306 − 300 = **6dp**    →   **标题彻底废掉**
     *     （这就是不能直接插按钮的原因：标题会被压成几乎不可见。）
     *
     *   把宽度改成 **44dp**（间距仍 4dp）：
     *       5 × (44 + 4) = 240dp   →   标题剩 306 − 240 = **66dp**   →   **与改动前完全持平** ✅
     *
     *   ★ 这正是取 44 的唯一理由：**它让「5 个按钮」恰好等于「原来 4 个按钮」占用的横向空间**，
     *     所以标题的空间一点没少（66dp），五个按钮也全部放得下。
     *     换成任何比 44 大的值（45、50、56…）都会重新吃掉标题空间；44 是被这个等式钉死的上限值。
     *     45dp 时 5×49 = 245 → 标题剩 61dp（仍勉强够，但已开始变紧），
     *     所以 44 是「标题空间不打折」这个目标下的最优解，不要再往上调。
     *
     * 【为什么 44 能装下两个字】按钮 44dp − 左右内边距各 6dp = **32dp 文字区**；
     *   两字标签 @12sp 粗体 ≈ 2 × 12 = **24dp** → 32 > 24，绰绰有余 ✅
     *   （五个标签「手动」「官网」「透明」「大小」「关闭」全部是 2 字，宽度需求一致，
     *     因此 44dp 固定宽度下它们**等宽等高、都不会被 ellipsize**。
     *     这也是「大小一致」这个既有要求的延续 —— 文案要变长（例如再拼百分号）才会截断，
     *     当前纯两字标签不会。）
     *
     * 【卡片模式的连带影响 —— 无害】MODE_CARD 那排（重选 | 透明 | 大小 | 关闭）只有 4 个按钮，
     *   同样用这个常量，所以也会一起从 56dp 变成 44dp：
     *     4 × (44 + 4) = 192dp → 卡片模式标题剩 306 − 192 = **114dp**（比原来 66dp 更宽裕）。
     *   即对卡片模式**只有好处**（标题空间变大），没有任何按钮变截断，确认无害。
     *   （卡片模式的标签「重选」「透明」「大小」「关闭」也全是 2 字，44dp 下同样装得下。）
     *
     * 【触控不降级】只改宽度，不碰 minimumHeight：NukeTheme.secondaryButton 内部已设
     *   minimumHeight = 48dp，headerBtn 没有覆盖它 → 每个按钮**触控高度仍是 48dp**。
     *   （可见宽度 44dp 略小于 48dp 的无障碍建议横向值，这是「标题 + 5 个按钮共存在 306dp 内」
     *     的固有取舍；上一版更窄的 36dp 已被否决，44dp 是更保手感的取值。）
     *
     * 【为什么不是 60dp（历史）】曾用 60dp，但当时文案带了数值（「大小 200%」@12sp 粗体约需 67dp），
     * 按钮会被截成「大小 200…」，且标题只剩 50dp 也会被截断。改回纯两字标签后，
     * 固定宽度就够用了。当前数值改在点击后弹出的对话框里显示，信息未丢失。
     *
     * 文案仍有 ellipsize 兜底：宿主字体被系统放大到极端值时收成省略号，而不是把整排撑爆。
     */
    private const val HEAD_BTN_W_DP = 44

    /**
     * 标题栏按钮之间的间距（dp）。
     *
     * 保持既有的 4dp 不变 —— 44dp 是按「间距 4dp」这个前提反解出来的（见 HEAD_BTN_W_DP 的账）。
     * 不要为了「再省一点」而单独调小它：那会破坏 5×(44+4)=240dp 这个与改动前持平的等式，
     * 让 44dp 这个取值的推导依据失效。
     */
    private const val HEAD_BTN_GAP_DP = 4

    /**
     * v4.0.0: how long the finger must stay on the balloon to count as a LONG press (文本优化).
     *
     * 500 ms is the platform's own ViewConfiguration long-press timeout, so the gesture feels identical to
     * every other long press in WeChat.
     */
    private const val LONG_PRESS_MS = 500L

    /** item 1: the minimum gap between re-attach attempts. */
    private const val REATTACH_COOLDOWN_MS = 3000L

    /** v4.0.0: how many times showCard waits for the overlay host before giving up. */
    private const val SHOW_CARD_RETRIES = 10

    /** v4.0.0: the gap between those attempts. */
    private const val SHOW_CARD_RETRY_MS = 150L

    /** What the panel is showing. */
    private const val MODE_LIST = 0
    private const val MODE_CARD = 1

    // ------------------------------------------------------- 液态玻璃视觉（t5。
    //
    // 悬浮球与悬浮面板是这个模，*用户最常看，*的界面，而且它们浮在宿主 App 的界面之上：
    // 宿主背景可能是浅色（微信白天聊天背景接近白色）也可能是深色，所以这里的「玻璃观感」
    // 不只是审美，它同时必须在两种底色上都清晰可见、可点（铁律 4）。
    //
    // 【本节的边界，非常重要】这里只决定「面板背景长什么样」。
    // 不读写任何状态、不注册任何监听、不参与任何尺寸/位置计算，因此对
    // 「拖，DRAG / 边界吸附 / 位置持久，/ watchdog 重挂，/ 面板尺寸与高度预，/
    //  滚动位置记忆 / 渲染节流 / Provider 回调 / 点击长按判定 / 透明度与缩放应用」
    // 这条逻辑，*完全没有任何接触，*（铁，2）。
    //
    // 【t5 修订：改为直接调，NukeTheme.glassPanel()。
    // 皮肤的第一版在这里自己拼了一个「NukeTheme.panel 底板 + 一层内沿高光」的分层 Drawable。
    // 当时这么写是因为 NukeTheme 还没有玻璃表面工厂；现在 theme-core 已经
    // 落地，NukeTheme.glassPanel()，自拼版本有两个**变暗/变错的隐，*，必须换掉：
    //
    //   1. 自拼版的高光，*写死的白，*，x26FFFFFF）。theme-core 明确警告过这一，——
    //      写死的半透明白在浅色主题下是「白叠在近白上」，等于什么都没画；而且它不随主题走。
    //      是双主题皮肤里最容易漏掉的一，bug。NukeTheme.GLASS_SHEEN 会按明暗给两种。
    //      （深色给白、浅色给带强调色的浅蓝），这才是正确的来源。
    //   2. 自拼版为了不让高光盖住描边而做，1dp 内缩、并把高光圆角减掉这 1dp。
    //      NukeTheme.glassPanel() 的做法是**每一层同圆角、零内缩**，靠各自的渐变淡，——
    //      这正，theme-core 注明的「手拼玻璃面板最常见的翻车点」（内缩/小圆角会让角。
    //      露出角的杂边）。用工厂就没有这个风险。
    //
    // 另外 glassPanel() 的身体层，withAlpha(fill, 0xF0) ，*半透明**填充，。
    // NukeTheme.panel() 的填充是不透明，——「通透」正是需求里要的观感。
    //
    // 【为什么不用背景图 / 真实高斯模糊】这，View 活在**宿主进程**里（微信/抖音/QQ/快手），
    // 模块自己的资源不可靠；真正的背景模糊（RenderEffect）要 API 31+，且对宿主窗。
    // 抓不到可模糊的内容。所以「液态玻璃」用分层半透明实现，API 21+ 全部可用。

    // ------------------------------------------------------------------ data

    /** One selectable analysis record. */
    data class Entry(
        val key: String,
        val title: String,
        val snippet: String,
        val time: Long,
        /** Which chat this record belongs to, so the list can show one chat only (item 2). */
        val talker: String = "",
        /** item 4: the token line for this record, when the API reported usage. */
        val usage: String = "",
    )

    /** Everything needed to draw one card. */
    data class CardViewData(
        val key: String,
        val state: Any,
        val turns: List<ChatTurn>,
    )

    // -------------------------------------------------------------- providers
    // Supplied by the controller, so this file knows nothing about its internals.

    var listProvider: (() -> List<Entry>)? = null

    /**
     * item 4a: the best display name for a record (the contact's remark, then nickname, then id).
     *
     * Supplied by the controller because resolving a remark needs WeChat's own data. Returning null
     * leaves the stored title in place.
     */
    var titleProvider: ((String) -> String?)? = null

    /** item 2: the chat currently open, so the list can be limited to it. */
    @Volatile
    var currentTalker: String = ""

    /**
     * item 2: token lines PER RECORD KEY.
     *
     * This used to be one global string, so the card showed the LAST usage set while the list showed the
     * value stored for that record -- the two disagreed, which is exactly what was reported. Keeping them
     * per key makes both surfaces consistent by construction.
     */
    private val usageByKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Builds the usage line for a key, or "" when none was recorded. */
    fun usageLine(key: String): String = usageByKey[key].orEmpty()

    /**
     * tap-to-detect: asks the controller which chat is on screen.
     *
     * Set by the controller. Called on tap and on render, so the record list is filtered by the CURRENT
     * chat immediately instead of only after the first analysis.
     */
    var talkerProvider: (() -> String)? = null

    /** Throttles the provider so a render loop cannot call it constantly. */
    @Volatile
    private var talkerCheckedAt = 0L

    /** Refreshes `currentTalker` from the provider, at most once per second. */
    private fun refreshTalker(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - talkerCheckedAt < 1000L) return
        talkerCheckedAt = now
        val tk = try {
            talkerProvider?.invoke().orEmpty()
        } catch (_: Throwable) {
            ""
        }

        if (force) {
            // A FORCED refresh happens on a tap or when the panel opens, and its answer is authoritative --
            // including a blank one, which means "no chat open". Keeping the old value here was the bug that
            // made chat B display chat A's records.
            if (tk != currentTalker) {
                Log.i(TAG, "floating: current chat = " + tk.ifBlank { "(none)" } +
                    " [was " + currentTalker.ifBlank { "(none)" } + "]")
                currentTalker = tk
                // The visible list must follow the new chat immediately.
                if (expanded) render()
            }
            return
        }

        // A background refresh only ever moves the filter TO a known chat, so a momentary blank reading
        // cannot empty the list while the user is looking at it.
        if (tk.isNotBlank() && tk != currentTalker) {
            Log.i(TAG, "floating: current chat changed to " + tk +
                " [was " + currentTalker.ifBlank { "(none)" } + "]")
            currentTalker = tk
            if (expanded) render()
        }
    }

    /**
     * Called when the chat screen changes, so the cached talker is dropped at once instead of waiting for
     * the next tap.
     */
    fun onChatScreenChanged() {
        talkerCheckedAt = 0L
        refreshTalker(force = true)
    }

    /** item 2: the panel asks the controller for a key's usage, so there is ONE source of truth. */
    var usageProvider: ((String) -> String)? = null
    var cardProvider: ((String) -> CardViewData?)? = null
    var onReanalyze: ((String) -> Unit)? = null
    var onFollowUp: ((String, String) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null
    var onEdit: ((String, String) -> Unit)? = null

    // ----------------------------------------------------------------- state

    /**
     * The Activity the overlay is attached to.
     *
     * Held as a WeakReference instead of a view tag: View.setTag(int, Object) REJECTS framework
     * resource ids ("The key must be an application-specific resource id"), and using
     * android.R.id.content.hashCode() as the key threw IllegalArgumentException here, which the
     * surrounding catch swallowed -- so the balloon silently never appeared.
     */
    private var ownerRef: java.lang.ref.WeakReference<Activity>? = null

    /**
     * The balloon's position, held as a FRACTION of the parent (0..1).
     *
     * Distance-in-pixels was the reason the balloon drifted: the parent's size changes when the keyboard
     * opens, when the screen rotates and when WeChat swaps its content view, and an absolute margin then
     * lands somewhere completely different. A fraction is stable across all of those, and it is clamped
     * so the balloon can never end up off screen (item 5).
     */
    /** item NEW: the watchdog runs once per process. */
    private var watchdogStarted = false

    /** The last context/activity seen, so the watchdog can re-attach without a fresh caller. */
    @Volatile
    private var lastContext: Context? = null

    private var lastActivityRef: java.lang.ref.WeakReference<Activity>? = null

    /**
     * The balloon's position, in PIXELS relative to its parent. -1 means "not placed yet", in which case
     * it is centred on first layout (item 3).
     *
     * WHY PIXELS AND NOT A FRACTION: the position used to be stored as a fraction of the parent and
     * re-applied on every layout change. WeChat RESIZES the window when moving between the conversation
     * list and a chat (input bar, keyboard, insets), so the same fraction resolved to a different pixel
     * position and the balloon appeared to move on its own:
     *
     *     list: 0.5 * 2575 = 1287
     *     chat: 0.5 * 1900 =  950        <- a 337px jump, exactly the reported drift
     *
     * Pixels are what the user actually placed, so pixels are stored. A layout change now only CLAMPS
     * the position to keep the balloon on screen; it never rescales it.
     */
    private var balloonX = -1
    private var balloonY = -1

    /**
     * The LOCKED position, in absolute SCREEN coordinates. -1 means "not locked yet".
     *
     * Set when a drag finishes, and honoured from then on: the balloon is pinned to this screen point
     * through window resizes, keyboard changes, content-view replacements and watchdog re-attachments.
     * Dragging again simply moves the lock to the new point.
     */
    private var lockedScreenX = -1
    private var lockedScreenY = -1

    /**
     * item 2: true while the user's finger is down on the balloon.
     *
     * The lock must NOT be applied during a drag: doing so snapped the balloon back to the locked point
     * on every move, which is why it could not be dragged at all after a single tap.
     */
    private var dragging = false

    /**
     * v3.1.0 埋点（captain 加）：手指按在悬浮球上的时刻（uptimeMillis）。
     *
     * 用途：把用户感知的「点击悬浮球，1-2 秒」拆成两段，判断时间到底花在哪一段：
     *     TAP_HAND - tapDownAt   → 触摸事件从按下到真正送进我们代码的延迟（主线程排队）
     *     TAP_DONE - TAP_HAND    → 我们自己构建面板 + 渲染的耗时
     *
     * 判读规则（surveyor t1 给出）：
     *     sinceDown 大（>500ms，，主线程被别的东西占着（keystore 风暴 / 其它 Xposed 模块），不是我们。
     *     buildCost 大（>100ms） → 我们自己的渲染慢
     *     两者都小但用户仍觉得卡 ，问题不在本模块，往系统，其它模块。
     *
     * 注意：这，*诊断埋点**。若后续确认卡顿已解决，应连，:509 的赋值和 :950/:966 两处日志一起删除，
     * 不要留一个恒，0 的假指标误导以后的排查。
     */
    private var tapDownAt = 0L

    /**
     * v3.1.0：上一，render() 实际用到的「存储高度上限」（像素，dp(panelMaxHeightDp)）。
     *
     * 用来判断「最大高度」这个设置有没有变。取 -1 表示「还没渲染过，无从比较」——
     * 首次必须当作「变了」处理，否则用户第一次改高度会被当成没变而跳过重建。
     */
    private var lastAppliedStoredCapPx = -1

    /** 读配置里的「最大高度」上限，换算成像素；无效值返，0（表示不设上限，按屏幕额度走）。*/
    private fun storedCapPxOf(cfg: Config, ctx: Context): Int = try {
        val v = cfg.panelMaxHeightDp
        if (v > 0) NukeTheme.dp(ctx, v) else 0
    } catch (_: Throwable) {
        0
    }

    /** item 1: throttles re-attachment, so a bad state cannot become a once-a-second loop. */
    private var lastReattachAt = 0L
    private var panelScrollY = 0

    /** The panel's body, so its scroll position can be preserved across re-renders (item 2b). */
    private var currentScroll: MaxHeightScrollView? = null

    /**
     * v2.8.0 (item 3): WHAT the body currently shows.
     *
     * The scroll offset is restored only while this identity is unchanged. Restoring it unconditionally made a
     * new, taller card open at an offset meant for the previous one, so the top looked clipped and the buttons
     * at the bottom stayed out of reach.
     */
    private var lastRenderedKey: String? = null
    private var lastRenderedMode: Int = -1

    private var host: FrameLayout? = null
    private var balloon: View? = null
    private var panel: LinearLayout? = null
    private var expanded = false
    private var mode = MODE_LIST
    private var selected: String? = null

    /** True when the panel is attached to a live window. */
    fun isAttached(): Boolean = host?.parent != null

    /**
     * item 3: the balloon belongs to the CHAT screen only.
     *
     * WeChat's contact list and chat screens are different Activities, so the overlay follows the
     * Activity: it is attached on a chat screen and removed anywhere else. Recognised by class name,
     * which the log confirms is how WeChat names its chat screen.
     */
    /**
     * item 1: is this a CHAT screen (as opposed to the conversation list)?
     *
     * The log shows the host Activity is LauncherUI for BOTH screens: WeChat switches between the
     * conversation list and a chat with Fragments inside one Activity. So the Activity cannot tell them
     * apart, and the previous test (looking for "ChattingUI") never matched -- entering a chat was taken
     * for leaving it, and the balloon was removed.
     *
     * The reliable discriminator is the ADAPTER of the chat RecyclerView, which the log names:
     *     com.tencent.mm.ui.chatting.component.ve    <- the chat message list
     *     com.tencent.mm.pluginsdk.ui.chat.lg
     * A chat list adapter means the chat screen; anything else (the conversation list) does not.
     */
    private val CHAT_ADAPTER_MARKERS = arrayOf(
        "chatting.component",
        "pluginsdk.ui.chat",
        "chatting.viewitems",
        "chatting.ui",
    )

    private fun isChatAdapterName(name: String?): Boolean {
        val n = name ?: return false
        for (m in CHAT_ADAPTER_MARKERS) {
            if (n.contains(m)) return true
        }
        return false
    }

    /**
     * ★ v9.1.1：图片 / 视频「大图查看页」的类名标记。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么需要这个 —— 快手评论区的崩溃是怎么来的】
     *
     * 真机崩溃日志（com.smile.gifmaker，2026-09-26 14:57:39）：
     *
     *     java.lang.IndexOutOfBoundsException: Inconsistency detected.
     *         Invalid item position 1(offset:1).state:2
     *         androidx.viewpager2.widget.ViewPager2$RecyclerViewImpl
     *         context: com.yxcorp.gifshow.comment.image.ImageDetailActivity
     *       at RecyclerView.dispatchLayoutStep2
     *       at LinearLayoutManager.onLayoutChildren   ← 在「布局填充」过程中崩的
     *
     * 堆栈里**没有**我们的代码帧，所以它不是「我们的代码抛异常」，而是**我们触发的**。
     * 模块日志给出了毫秒级因果链：
     *
     *     14:57:39.905  floating: BALLOON ATTACHED ... activity=ImageDetailActivity
     *     14:57:39.932  FATAL: IndexOutOfBoundsException ... ImageDetailActivity
     *                   ↑ 相隔 27 毫秒
     *
     * 机制：`attach()` 会往 Activity 的 decorView 上**加一个 MATCH_PARENT 的全屏覆盖层**，
     * 这必然让整个界面重新走一遍 measure/layout。而 ViewPager2 恰好在这一刻处于
     * 「adapter 数据与已绑定的 ViewHolder 记账不一致」的瞬间状态 ——
     * 这是 ViewPager2/RecyclerView 的著名固有缺陷（数据变更与布局竞争），
     * **正常情况下它会自己撞上也会崩，但概率极低**；我们的覆盖层等于**主动去踩这一脚**，
     * 把低概率事件变成必现。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么是「不在这些页面挂载」，而不是去修那 27 毫秒的时序】
     *
     * 时序问题无法从外部修：我们没法知道 ViewPager2 此刻是否记账一致，
     * 也没法在它的 layout 中间插队。而**这个页面本来就不需要悬浮球** ——
     * 用户在图片详情页看大图，既不会长按消息、也不会用我们的分析功能。
     * 不挂载，既消除了崩溃，又不损失任何功能。
     *
     * 「有功能却不提供」与「提供了会崩」之间，选后者显然是错的。
     *
     * 【为什么按类名判断】
     * 这是模块既有的做法（`isChatActivity` 同样按类名），因为我们是跨 5 个宿主运行的
     * Xposed 模块，拿不到宿主的编译期类型。用**多个标记词**而不是全等匹配，
     * 以便宿主小版本更新改了包路径时仍能命中。
     */
    private val MEDIA_VIEWER_MARKERS = arrayOf(
        // 快手：评论区图片 / 大图查看（本次崩溃现场）
        "ImageDetailActivity",
        "PhotoDetailActivity",
        // 通用命名（各宿主的图片/视频查看页常见写法）
        "ImagePreview",
        "PhotoView",
        "ImageViewer",
        "GalleryActivity",
        "MediaPreview",
        "VideoDetailActivity",
        // 微信：朋友圈/聊天图片查看
        "ImagePreviewUI",
        "GalleryUI",
    )

    /**
     * 该 Activity 是不是「图片/视频大图查看页」—— 是的话**不挂载悬浮层**。
     *
     * 判定失败时返回 false（即**照常挂载**）：宁可保持原有行为，
     * 也不要因为一个名字没匹配上就让悬浮球在所有页面消失（那等于功能没了）。
     * 所以这里的取舍与「读状态失败当未同意」相反 —— 那里是安全优先，这里是可用性优先。
     */
    private fun isMediaViewerActivity(activity: Activity?): Boolean {
        val n = activity?.javaClass?.name ?: return false
        for (m in MEDIA_VIEWER_MARKERS) {
            if (n.contains(m)) return true
        }
        return false
    }

    /** 已经为哪些媒体页类名打过「不挂载」日志（避免 1 秒看门狗把它刷屏）。 */
    private val mediaViewerLogged = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * item 1: whether the given context is the chat screen.
     *
     * Uses the adapter reported by ChatListHook when available, and falls back to the Activity only for
     * a class that plainly names a chat screen.
     */
    fun isChatScreen(ctx: Context?): Boolean {
        lastChatAdapterName?.let { return isChatAdapterName(it) }
        return isChatActivity(activityOf(ctx))
    }

    /** The most recent adapter class name seen by ChatListHook. */
    @Volatile
    private var lastChatAdapterName: String? = null

    /** Called by ChatListHook for every adapter it sees, so the screen can be identified. */
    fun noteAdapter(adapterClassName: String?) {
        if (adapterClassName != null) lastChatAdapterName = adapterClassName
    }

    /** True when `adapterClassName` belongs to a chat screen. */
    fun isChatAdapter(adapterClassName: String?): Boolean = isChatAdapterName(adapterClassName)

    /**
     * Kept for callers that only have an Activity. The class-name test alone is not enough for this
     * WeChat build (one Activity serves both screens), so it is only a secondary signal.
     */
    private fun isChatActivity(activity: Activity?): Boolean {
        val n = activity?.javaClass?.name ?: return false
        return n.contains("ChattingUI") || n.contains("plugin.chatting")
    }

    /** item 2a: attach the balloon globally, as soon as WeChat has a chat screen. */
    fun showGlobal(ctx: Context?) {        if (ctx == null) {
            Log.i(TAG, "floating: showGlobal without a context")
            return
        }
        attach(ctx)
    }

    // ------------------------------------- item NEW: the balloon on/off switch

    /**
     * item NEW: true while the user has switched the balloon OFF.
     *
     * WHY A LATCH AND NOT JUST detach():
     *
     * detach() removes the view once, but the balloon has THREE automatic revival paths that would bring
     * it straight back:
     *   1. the 1-second watchdog re-attaches whenever it finds no host;
     *   2. followActivity() runs on every Activity.onResume;
     *   3. the analysis flow calls attach() directly.
     *
     * "Switched off" therefore has to be a STATE, not an event. Every one of those paths funnels through
     * attach(), so a single early return there covers them all.
     */
    @Volatile
    private var hidden = false

    /** True when the balloon is switched off in this process. */
    fun isHidden(): Boolean = hidden

    /**
     * item NEW: hides the balloon BUTTON and keeps it hidden until switched back on.
     *
     * v4.0.0 FIX: this used to call detach(), which tore down the whole overlay -- including the surface the
     * analysis cards are rendered into. Switching the balloon off therefore also killed the cards.
     *
     * Now only the BUTTON is removed from the (still attached) host. The host itself is KEPT, because
     * AnalysisController renders into it; when there is no host at all there is nothing to render into, so
     * the next attach() builds one WITHOUT the button.
     */
    fun hideGlobal() {
        // Set the latch FIRST and on the calling thread, so a racing attach() from the watchdog cannot
        // slip past it while the posted body is still queued.
        hidden = true
        onMainThread("hideGlobal") {
            hidden = true
            try {
                val root = host
                val b = balloon
                if (root != null && b != null) {
                    root.removeView(b)
                    balloon = null
                    Log.i(TAG, "floating: balloon HIDDEN (button removed; the host stays for cards)")
                } else {
                    // No host yet: the latch alone is enough, attach() will skip adding the button.
                    Log.i(TAG, "floating: balloon HIDDEN (no host yet; attach() will omit the button)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "floating: hiding the balloon failed -> " + describe(t))
            }
        }
    }

    /**
     * item NEW: clears the latch and shows the button again.
     *
     * v4.0.0 FIX: it now reconciles the button on the host that is ALREADY up, so the balloon reappears
     * immediately instead of waiting for the next screen change. attach() also reconciles, so a switch
     * flipped while another screen is open is honoured as soon as anything touches the overlay.
     */
    fun showGlobalOn(ctx: Context?) {
        hidden = false
        /* ★ v9.6.0 用户新增功能：「点了浮窗之后启动时自动居中」。
         *
         * 【为什么需要这个 —— 「防丢悬浮球」】
         * 用户可以把球拖到屏幕任意位置，包括紧贴边缘、或**半掩在状态栏/键盘下方**。
         * 位置又会被持久化（v9.3.0），于是下次启动球仍在那里；某些情况下
         * （换分辨率、系统栏高度变化、旋转屏幕）球会落到几乎点不到的地方，
         * 而它**是打开面板的唯一入口** —— 点不到就等于整个功能没了，用户无从自救。
         *
         * 把「开关打开」这一动作定义为「把球摆回屏幕中央」，就给了用户一个
         * **一定能按到**的恢复手段：关掉再打开即可。
         *
         * 【为什么在这里就把持久化的坐标一起清掉，而不是只改内存】
         * 只改内存的话，本次会居中，但磁盘上那个「点不到的位置」还在 ——
         * 下次启动 v9.3.0 的恢复逻辑又把球摆回那个坏位置，问题原样复发。
         * 必须**同时**清掉持久化坐标，改动才是完整的。
         * 注意写到 -1（=「从未拖过」）而不是 0：0 是合法坐标（左上角），
         * 用它当哨兵会让「用户本就想把球放左上角」在下次启动被误判成没设过。
         *
         * 【为什么用标志位而不是直接改 lockedScreenX】
         * `attach()` 里恢复坐标的分支是 `if (lockedScreenX < 0)`，而 attach 是异步入主线程执行的
         * （见下面 onMainThread）。如果在这里直接置 lockedScreenX = -1，那么 attach 从磁盘读到的
         * 可能仍是被清之前的旧值（写盘还没落），两个异步动作会打架。
         * 用一个明确的「本次要居中」标志贯穿，语义无歧义，也不依赖时序。 */
        forceCentreOnNextAttach = true
        /* 取一个可用的 Context 来清掉磁盘上的坐标。
           三级回退与文件里其它地方一致：调用方给的 -> 上次记的 -> 当前宿主。 */
        val clearCtx = ctx ?: lastContext ?: host?.context
        if (clearCtx != null) {
            try {
                val repo = com.luoliaoo.wechat.data.ConfigRepo
                val cfg = repo.loadLocal(clearCtx)
                if (cfg.balloonPosX >= 0 || cfg.balloonPosY >= 0) {
                    repo.saveLocalAndMirror(clearCtx, cfg.copy(balloonPosX = -1, balloonPosY = -1))
                    Log.i(TAG, "floating: cleared the saved position -> the balloon will centre")
                }
            } catch (t: Throwable) {
                // 清不掉也不影响本次居中：forceCentreOnNextAttach 已经置位
                Log.w(TAG, "floating: could not clear the saved position: " + t.message)
            }
        }
        Log.i(TAG, "floating: balloon switched ON by the user")
        if (ctx == null) {
            Log.i(TAG, "floating: no context yet; the balloon will attach on the next screen event")
            return
        }
        onMainThread("showGlobalOn") { attach(ctx) }
    }

    /**
     * v9.6.0: set when the user switches the balloon ON, consumed by the next [attach].
     *
     * Meaning: "this attach must place the balloon at the centre, ignoring both the in-memory lock and
     * any persisted position". Cleared once consumed so a later ordinary attach (screen change,
     * watchdog, followActivity) returns to the normal locked behaviour.
     */
    @Volatile
    private var forceCentreOnNextAttach = false

    // ---------------------------------------------------------------- balloon

    /**
     * The balloon: the supplied picture, cropped to a circle (items 1 and 3).
     *
     * It is GLOBAL -- never hidden for being on the "wrong" screen. WeChat uses one Activity for the
     * conversation list and the chat, so any screen test was guesswork and made the balloon disappear
     * where the user wanted it.
     */
    private fun buildBalloon(ctx: Context): View {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        // item NEW: the user's size, as a percentage of the designed size. Read HERE, at build time,
        // because the size is a layout dimension -- `applyBalloonScale` changes it on a live balloon by
        // relaying out this same view, and a fresh attach must start from the stored value rather than
        // from the design default.
        val size = dp(balloonSizeDp(ctx))
        val box = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            // ============================== THE WHITE EDGE, AND WHY IT IS GONE ==============================
            // The user reported "growing it leaves a white border all around". Two causes, both here:
            //
            //   1. this background is a SOLID WHITE disc (`pill(0xFFFFFFFF)`), and the artwork inside it
            //      is a CIRCLE of the same size. A circle inscribed in a square touches the edges only at
            //      four points, so at every other point the white square-corner shows through -- and the
            //      bigger the balloon, the more visible that white ring;
            //   2. the artwork's own ring is drawn INSIDE its circle at a stroke proportional to the size,
            //      so the picture never quite reaches the view's edge either.
            //
            // The fix is to make the disc the ONLY thing behind the artwork: the background is now the
            // artwork's own circle at full size (see `BalloonIcon.circular`), and the shadow is cast by
            // the view's elevation, which follows the view's outline. No white is left to show.
            background = null
            elevation = dp(6).toFloat()
            isClickable = true
            tag = Constants.TAG_OWN_UI
        }

        // item 3: the user's picture, cropped to a circle.
        //
        // ============================== THE CHILD IS BUILT AT THE CURRENT SIZE ==============================
        // and it is REBUILT whenever the size changes -- see [applyBalloonScaleNow]. That is not an
        // optimisation, it is the fix for the two symptoms the user reported:
        //
        //   * "shrinking it turns it into a SQUARE": the child's params are FIXED PIXELS taken at build
        //     time, so shrinking the parent left this circle at its old size and simply CLIPPED it. A
        //     centred circle clipped by a smaller square loses its four round edges first, so what is
        //     left reads as a square;
        //   * "growing it leaves white all around": growing the parent left this circle unchanged and
        //     smaller than the box, so the white background disc showed around it.
        //
        // Both are the same mistake -- only the CONTAINER was resized, never the artwork. Rebuilding the
        // child at the new size fixes both and keeps the image sharp (the source is 256px, so an upscale
        // past that is avoided by rebuilding from the source rather than stretching a bitmap).
        val artView = View(ctx).apply { tag = BALLOON_ART_TAG }
        val art = BalloonIcon.circular(ctx, size)
        if (art != null) {
            artView.background = art
        } else {
            // Fallback: the drawn icon, so the balloon is never invisible if decoding failed.
            Log.w(TAG, "floating: balloon artwork unavailable; using the drawn fallback")
            // t5 液态玻璃兜底：这里原来，`background = null` ——一旦图片解码失败，
            // 悬浮球就变成一，*完全透明**的方块；球还在、还能拖能点，但用户在界面上
            // 什么都看不见（只有 alpha=0 时真的会消失，而找不到悬浮球是这个模块最危险。
            // 故障，因为没有别的入口）。所以兜底改画一，*实心，*，颜色全，NukeTheme
            // 的公开 token：浅色宿主背景上，ACCENT_STRONG 的深色描边定轮廓。
            // 深色宿主背景上靠 ACCENT 的亮色圆面发亮。
            // 只用 NukeTheme 的公开 API：pill()（内部即 shape = OVAL），
            // 而不，rounded(...) + size/2 当圆，——rounded() 的第三个参数，**dp**。
            // 把像素，size/2 传进去会得到 size/2*density 的圆角，只能，Skia 把半。
            // 钳到「短边的一半」才碰巧画成圆。用 OVAL 是明确的、与密度无关的写法。
            // 描边，ACCENT_STRONG，让球面在浅色宿主背景上也有一圈可见的轮廓。
            artView.background = NukeTheme.pill(NukeTheme.ACCENT, ctx).apply {
                setStroke(NukeTheme.dp(ctx, 2), NukeTheme.ACCENT_STRONG)
            }
        }
        box.addView(
            artView,
            FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER },
        )

        // A CIRCULAR SHADOW. Without this the elevation shadow follows the view's default RECTANGULAR
        // outline, so a round balloon casts a square shadow -- which is the "it looks like a square"
        // reading again, in a different form.
        try {
            box.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            box.clipToOutline = true
        } catch (_: Throwable) {
            // Cosmetic: on a very old framework the balloon keeps a rectangular shadow.
        }

        // Drag to move. The position is stored in PIXELS, so a window resize cannot move the balloon.
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false

        // ------------------------------------------------ v4.0.0: LONG PRESS (文本优化)
        //
        // The balloon now answers to two gestures:
        //   * SHORT tap (no movement)  -> open/close the panel, EXACTLY as before
        //   * LONG press (500 ms still) -> open the text-polish dialog
        //
        // WHY A TIMER AND NOT setOnLongClickListener: the balloon already owns its OnTouchListener for
        // dragging, and a long-click listener would only fire when performClick() runs -- which never
        // happens here because ACTION_UP is consumed. A postDelayed keyed to the same gesture is the
        // reliable way to tell "held still" from "tapped" and from "dragged".
        //
        // The three rules that keep the short tap intact:
        //   1. the timer is CANCELLED the moment the finger moves past the drag threshold,
        //   2. it is cancelled on ACTION_UP and ACTION_CANCEL as well,
        //   3. once it has FIRED, the following ACTION_UP must not also open the panel -- otherwise one
        //      long press would both polish the text and toggle the panel.
        val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var longPressFired = false
        val longPressTask = Runnable {
            if (moved || longPressFired) return@Runnable
            longPressFired = true
            Log.i(TAG, "floating: LONG PRESS on the balloon -> text polish")
            onBalloonLongPressed(box)
        }

        /**
         * Applies the stored pixel position, centring on first use and clamping so the balloon stays
         * reachable. It deliberately does NOT rescale on a layout change -- that was the drift.
         */
        fun applyPosition(v: View, recentre: Boolean) {
            val parentVg = v.parent as? ViewGroup ?: return
            val pw = parentVg.width
            val ph = parentVg.height
            if (pw <= 0 || ph <= 0) return
            val w = if (v.width > 0) v.width else NukeTheme.dp(v.context, BALLOON_DP)
            val h = if (v.height > 0) v.height else NukeTheme.dp(v.context, BALLOON_DP)

            if (!dragging && !recentre && lockedScreenX >= 0 && lockedScreenY >= 0) {
                // LOCKED: derive the parent-relative offset from the locked SCREEN point. This is what
                // keeps the balloon still when WeChat moves or resizes the content area.
                val loc = IntArray(2)
                parentVg.getLocationOnScreen(loc)
                balloonX = (lockedScreenX - loc[0]).coerceIn(0, (pw - w).coerceAtLeast(0))
                balloonY = (lockedScreenY - loc[1]).coerceIn(0, (ph - h).coerceAtLeast(0))
            } else if (recentre || balloonX < 0 || balloonY < 0) {
                // No lock yet: start dead centre (item 3).
                balloonX = (pw - w) / 2
                balloonY = (ph - h) / 2
            } else {
                balloonX = balloonX.coerceIn(0, (pw - w).coerceAtLeast(0))
                balloonY = balloonY.coerceIn(0, (ph - h).coerceAtLeast(0))
            }

            val lp = v.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(w, h)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = balloonX
            lp.topMargin = balloonY
            v.layoutParams = lp
        }

        // Every layout change re-applies the LOCKED screen position (or clamps when there is no lock).
        // WeChat resizes and moves its content area between screens; pinning to a screen point is what
        // makes the balloon stay put.
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPosition(box, recentre = false)
            if (lockedScreenX >= 0) box.post { applyPosition(box, recentre = false) }
        }
        box.post { applyPosition(box, recentre = lockedScreenX < 0) }

        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = balloonX.toFloat()
                    startY = balloonY.toFloat()
                    moved = false
                    // v4.0.0: arm the long-press timer for THIS gesture. `longPressFired` is reset here,
                    // so the next press gets its own chance even after a previous long press.
                    longPressFired = false
                    longPressHandler.removeCallbacks(longPressTask)
                    longPressHandler.postDelayed(longPressTask, LONG_PRESS_MS)
                    // v3.1.0 埋点：记下手指按下的时刻，供 onBalloonTapped 区分
                    // 「事件投递慢」与「我们渲染慢」。见那里的详细说明。
                    tapDownAt = android.os.SystemClock.uptimeMillis()
                    // item 2: while the finger is down the lock is suspended, so the drag actually moves
                    // the balloon instead of being overwritten by the locked position.
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentVg = v.parent as? ViewGroup
                    val pw = parentVg?.width ?: 0
                    val ph = parentVg?.height ?: 0
                    if (pw > 0 && ph > 0) {
                        if (kotlin.math.abs(e.rawX - downX) > 12 ||
                            kotlin.math.abs(e.rawY - downY) > 12
                        ) {
                            moved = true
                            // v4.0.0: a drag is never a long press. Cancelling here also means a press that
                            // lingers, then moves, then comes back still counts as a drag.
                            longPressHandler.removeCallbacks(longPressTask)
                        }
                        val w = if (v.width > 0) v.width else NukeTheme.dp(v.context, BALLOON_DP)
                        val h = if (v.height > 0) v.height else NukeTheme.dp(v.context, BALLOON_DP)
                        balloonX = (startX + (e.rawX - downX)).toInt()
                            .coerceIn(0, (pw - w).coerceAtLeast(0))
                        balloonY = (startY + (e.rawY - downY)).toInt()
                            .coerceIn(0, (ph - h).coerceAtLeast(0))
                        applyPosition(v, recentre = false)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // The drag is over; from here the (new) lock governs again.
                    dragging = false
                    // v4.0.0: whatever happens next, this gesture can no longer become a long press.
                    longPressHandler.removeCallbacks(longPressTask)
                    if (moved) {
                        // LOCK: record where the balloon is on the SCREEN, so every later layout pass
                        // pins it to exactly this point. Dragging again just moves the lock.
                        try {
                            val loc = IntArray(2)
                            v.getLocationOnScreen(loc)
                            lockedScreenX = loc[0]
                            lockedScreenY = loc[1]
                            Log.i(
                                TAG,
                                "floating: position locked to screen " + lockedScreenX + "," +
                                    lockedScreenY,
                            )
                            /* ★ v9.3.0：同时写盘。上面两行只是**内存**里的锁，进程一重启就丢，
                               于是下次启动又回到居中 —— 用户要的正是「重启后还在原位置」。
                               写在这里（拖动结束时）而不是拖动过程中：MOVE 事件每秒几十个，
                               每次都写盘会明显卡顿；拖动结束只发生一次。 */
                            saveBalloonPos(v.context, lockedScreenX, lockedScreenY)
                        } catch (t: Throwable) {
                            Log.w(TAG, "floating: could not lock the position: " + t.message)
                        }
                    } else if (longPressFired) {
                        // v4.0.0: the long press already acted. Opening the panel as well would make one
                        // gesture do two things, so the tap is swallowed here.
                        Log.i(TAG, "floating: tap swallowed (this gesture was a long press)")
                    } else {
                        Log.i(TAG, "floating: SHORT TAP on the balloon -> panel")
                        onBalloonTapped()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // v4.0.0: a cancelled gesture must never fire the long press later.
                    longPressHandler.removeCallbacks(longPressTask)
                    true
                }
                else -> false
            }
        }
        return box
    }

    // ------------------------------------------------------------- lifecycle

    /** Runs `body` on the main thread, immediately when already there. */
    private fun onMainThread(why: String, body: () -> Unit) {
        try {
            if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
                body()
                return
            }
            Log.i(TAG, "floating: " + why + " called off the main thread; posting")
            android.os.Handler(android.os.Looper.getMainLooper()).post { body() }
        } catch (t: Throwable) {
            Log.e(TAG, "floating: " + why + " dispatch failed -> " + describe(t))
        }
    }

    /** item 1: attaches the balloon. It is global, so this is the only visibility rule. */
    fun attach(ctx: Context) {
        // v4.0.0 FIX: the on/off switch used to return HERE, which broke the analysis cards.
        //
        // THE BUG: an analysis card is not drawn inside a message row -- it is rendered in THIS overlay
        // (AnalysisController.drawOn -> attach(ctx) -> showCard(key)). The card therefore needs the host
        // FrameLayout to exist. The early return below meant that with the balloon switched OFF the host
        // was never created, so attach() silently did nothing, showCard() found `host == null` and
        // openPanel() bailed at `host?.context ?: lastContext ?: return` -- the analysis finished and NO
        // CARD EVER APPEARED. Exactly what the user reported.
        //
        // THE FIX: separate the two concerns that were wrongly fused.
        //   * the HOST is the overlay surface -- needed whenever a card may be shown;
        //   * the BALLOON is the round button -- only shown when the switch is ON.
        // So the host is always built, and the switch now only decides whether the BUTTON is added.
        if (!hidden && !balloonEnabledFromConfig(ctx)) {
            Log.i(TAG, "floating: the balloon switch is OFF -- attaching the host without the button")
        }
        val appCtx = try {
            ctx.applicationContext ?: ctx
        } catch (_: Throwable) {
            ctx
        }
        if (android.os.Looper.myLooper() !== android.os.Looper.getMainLooper()) {
            onMainThread("attach") { attach(appCtx) }
            return
        }
        val activity = activityOf(ctx)
        if (activity == null) {
            lastContext = appCtx
            return
        }
        lastActivityRef = java.lang.ref.WeakReference(activity)
        lastContext = appCtx

        /* ★ v9.1.1：图片/视频大图页**不挂载**悬浮层 —— 修快手评论区点图片闪退。
         *
         * 完整因果链与取舍见 [MEDIA_VIEWER_MARKERS] 上方的注释。一句话概括：
         * 我们加的全屏覆盖层会强制界面重新布局，而 ViewPager2 在「数据与 ViewHolder 记账
         * 不一致」的瞬间被强制布局就会抛 IndexOutOfBoundsException。
         * 真机日志显示崩溃发生在悬浮球挂载后 **27 毫秒**，且崩溃现场正是
         * `com.yxcorp.gifshow.comment.image.ImageDetailActivity`（快手评论区图片详情页）。
         *
         * 【为什么这里先 detach 再 return，而不是只 return】
         * 用户从聊天页进入图片页时，上一页的 host 还挂在**上一个 Activity 的 decorView** 上。
         * 只 return 的话，那个残留的 host（虽然属于已不可见的 Activity）不会引起新页面布局 ——
         * 但 balloon/panel/host 这些静态引用会指向旧 Activity，下一帧的
         * `syncBalloonVisibility` / watchdog 可能又去动它。
         * 先 detach 把状态清干净，回到聊天页时 attach 会重新构建，这是**更确定**的状态。
         *
         * 【为什么放在 activity 判空之后、existing 检查之前】
         *   ① 需要 activity 才能判断类名；
         *   ② 必须在 `existing != null` 那条「已经挂过就 bringToFront」的快速路径**之前** ——
         *      否则从图片页返回聊天页再进图片页时，会因为 existing 复用而绕过这道检查。 */
        if (isMediaViewerActivity(activity)) {
            /* 只在**每个类名第一次**打日志。
               原因：1 秒看门狗会反复调用 attach()，而用户在图片页停留时每一秒都会命中这里 ——
               真机上实测刷出「每秒一条、永不停止」的日志（15:09:04 到 15:09:13 连续 10 条），
               既撑大日志文件，也会把真正有用的信息挤出可读范围。
               去重集合与 noteAdapterType 的做法一致。 */
            val n = activity.javaClass.name
            if (mediaViewerLogged.add(n)) {
                Log.i(
                    TAG,
                    "floating: media viewer page (" + n +
                        ") -- not attaching the overlay (this is what crashed Kuaishou's image page)",
                )
            }
            detach()
            return
        }

        val existing = host
        if (existing != null && existing.parent != null && ownerRef?.get() === activity) {
            try {
                existing.bringToFront()
            } catch (_: Throwable) {
                // best effort
            }
            // v4.0.0: the switch may have flipped while this host was already up, so the button is
            // reconciled here too -- otherwise turning the balloon off left it on screen until the next
            // screen change.
            syncBalloonVisibility(existing)
            /* ★ v9.6.0：这条快速路径也必须处理「刚开启要居中」。
             *
             * 为什么不能只靠下面那条新分支：开关打开时，悬浮层**通常已经挂在当前 Activity 上**
             * （球是关掉的，但承载卡片的 host 一直在，见 v4.0.0 的说明），于是这里提前 return，
             * 根本走不到下面。实测表现就是「点了开启、提示说会居中，球却还在原来的位置」。
             *
             * 【为什么这里不能直接调 applyPosition】
             * 它是 `buildBalloon` 内部的**局部函数**（第 791 行），在 attach 里不可见 ——
             * 这一点是编译报错告诉我的，不是凭印象断言的。
             * 所以这里只负责**把锁清干净**，然后让重建出的球走下面那条正常分支
             * （applyPosition 会因 lockedScreenX < 0 而居中）。
             *
             * 【为什么要 detach 重建而不是就地挪动】
             * 我们没法从外部调用那个局部函数，而「就地改 LayoutParams」等于把居中算法
             * 抄第二遍 —— 两份实现迟早会不一致。重建的成本只是构建一个悬浮球视图，
             * 却保证了两条路径用的是**同一份**定位代码。 */
            if (forceCentreOnNextAttach) {
                Log.i(TAG, "floating: the switch was just turned on -- rebuilding centred")
                detach()
            } else {
                return
            }
        }
        if (existing != null) detach()

        /* ★ v9.3.0：启动时**恢复上次拖到的位置**，不再自动居中。
         *
         * 用户要求：「悬浮球固定位置 —— 重启应用后不再自动居中，而是原本位置」。
         *
         * 【放在这里的原因】
         * 必须在下方的 `applyPosition` 被调用**之前**把 `lockedScreenX/Y` 填好。
         * `applyPosition` 的分支是：
         *     lockedScreenX >= 0  -> 锁定到该屏幕点
         *     否则                 -> 死居中（第 756 行 `recentre` 分支）
         * 所以只要在构建视图前把锁恢复出来，后面所有既有的锁定逻辑（布局变化、
         * 键盘弹出、看门狗重挂）都会**自动**继续把球钉在正确位置，
         * 一行都不用改 —— 这是复用既有机制而不是新开一条路径。
         *
         * 【为什么只在 lockedScreenX < 0 时恢复】
         * 本进程内已经拖过球的话，内存里的锁是**最新**的（用户刚拖的），
         * 而磁盘上的值可能还是上一轮的。无条件覆盖会让「拖完球、切个页面」
         * 就把球弹回旧位置。所以只在没有锁（=本进程还没拖过）时才从配置读。
         *
         * 【配置读取失败/值为 -1 时】
         * 什么都不做，`lockedScreenX` 保持 -1 -> 走原有居中分支。
         * 也就是「没拖过的用户」行为完全不变，这是必须保住的老行为。 */
        if (forceCentreOnNextAttach) {
            /* ★ v9.6.0：用户刚把悬浮球开关打开 -> 强制居中（防丢球）。
               标志位在这里消费掉（一次性），所以之后由布局变化/看门狗触发的普通 attach
               会回到正常的「锁定位置」行为，不会每帧都被拉回中心。
               同时把内存里的锁也清成 -1，令下面的恢复分支不会再去读磁盘
               （磁盘坐标已在 showGlobalOn 里清掉了，这里只是让内存状态与之一致）。 */
            forceCentreOnNextAttach = false
            lockedScreenX = -1
            lockedScreenY = -1
            balloonX = -1
            balloonY = -1
            Log.i(TAG, "floating: centring the balloon (the switch was just turned on)")
        }
        if (lockedScreenX < 0 || lockedScreenY < 0) {
            try {
                val saved = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(activity)
                if (saved.balloonPosX >= 0 && saved.balloonPosY >= 0) {
                    lockedScreenX = saved.balloonPosX
                    lockedScreenY = saved.balloonPosY
                    Log.i(
                        TAG,
                        "floating: restored the saved balloon position " + lockedScreenX + "," +
                            lockedScreenY,
                    )
                } else {
                    Log.i(TAG, "floating: no saved balloon position; the balloon will centre")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "floating: could not read the saved balloon position: " + t.message)
            }
        }

        val root: FrameLayout
        val b: View
        try {
            root = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                tag = Constants.TAG_OWN_UI
            }
            b = buildBalloon(activity)
            // The button is added ONLY when it should be visible. The host itself is added regardless, so
            // cards keep working with the balloon switched off.
            if (balloonVisible()) root.addView(b)
        } catch (t: Throwable) {
            Log.e(TAG, "floating: building the balloon failed -> " + describe(t))
            return
        }

        // item 1: the overlay goes somewhere the framework will accept. addContentView is the public
        // API for adding a view to an Activity's window, so it cannot fail on a LayoutParams mismatch;
        // the content view and the decor view follow.
        val errors = StringBuilder()
        var attachedVia: String? = null

        // 1) the DECOR view: the window's root, so anything added here sits above the whole Activity,
        //    including the chat fragment that covered the balloon in v1.0.48.
        if (attachedVia == null) {
            try {
                val decor = activity.window?.decorView as? ViewGroup
                if (decor != null) {
                    decor.addView(root)
                    attachedVia = "decorView"
                } else {
                    errors.append(" [decorView: null]")
                }
            } catch (t: Throwable) {
                errors.append(" [decorView: ").append(describe(t)).append(']')
            }
        }

        // 2) the content view
        if (attachedVia == null) {
            try {
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                if (content != null) {
                    content.addView(root)
                    attachedVia = "contentView"
                } else {
                    errors.append(" [contentView: null]")
                }
            } catch (t: Throwable) {
                errors.append(" [contentView: ").append(describe(t)).append(']')
            }
        }

        // 3) addContentView -- kept as a fallback only: it puts the view INSIDE the content area, which
        //    the chat screen draws over, so it is no longer the first choice.
        if (attachedVia == null) {
            try {
                activity.addContentView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                attachedVia = "addContentView"
            } catch (t: Throwable) {
                errors.append(" [addContentView: ").append(describe(t)).append(']')
            }
        }

        // 4) WindowManager: added to the window directly, needing no ViewGroup parent at all.
        if (attachedVia == null) {
            try {
                val wm = activity.getSystemService(Context.WINDOW_SERVICE)
                    as? android.view.WindowManager
                if (wm != null) {
                    val lp = android.view.WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    )
                    lp.token = activity.window?.attributes?.token
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    wm.addView(root, lp)
                    attachedVia = "WindowManager"
                } else {
                    errors.append(" [WindowManager: null]")
                }
            } catch (t: Throwable) {
                errors.append(" [WindowManager: ").append(describe(t)).append(']')
            }
        }

        if (attachedVia == null) {
            Log.e(TAG, "floating: every parent strategy failed ->" + errors)
            return
        }

        host = root
        balloon = b
        ownerRef = java.lang.ref.WeakReference(activity)

        // The heartbeat reports this. It was never called, so the counter said "0 balloons" even while the
        // user could SEE one -- the one number I told them to rely on for diagnosing the clone.
        applyBalloonAlpha()
        // item NEW: the stored SIZE, applied on every attach. `buildBalloon` already sizes the view from
        // the config, so this is a re-assert rather than the source of truth -- it matters for a balloon
        // that was built before the setting changed, and it keeps size and transparency applied at the
        // same moment in the same place.
        applyBalloonScale()
        try {
            com.luoliaoo.wechat.core.ModuleStats.noteBalloon()
            Log.i(
                TAG,
                "floating: BALLOON ATTACHED (user=" + android.os.Process.myUid() / 100000 +
                    ", total=" + com.luoliaoo.wechat.core.ModuleStats.balloons +
                    ", via=" + attachedVia +
                    ", activity=" + activity.javaClass.simpleName + ")",
            )
        } catch (_: Throwable) {
            // counting is a nicety
        }
        try {
            OwnUi.register(root, b)
        } catch (_: Throwable) {
            // registration is a nicety, not a requirement
        }

        try {
            root.bringToFront()
        } catch (_: Throwable) {
            // best effort
        }
        try {
            root.post {
                try {
                    root.bringToFront()
                    root.requestLayout()
                } catch (_: Throwable) {
                    // best effort
                }
                Log.i(
                    TAG,
                    "floating: balloon geometry attached=" + b.isAttachedToWindow +
                        " size=" + b.width + "x" + b.height +
                        " visible=" + (b.visibility == View.VISIBLE),
                )
            }
        } catch (_: Throwable) {
            // best effort
        }

        Log.i(
            TAG,
            "floating: balloon attached [" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME + "] to " +
                activity.javaClass.simpleName + " via " + attachedVia +
                (if (errors.isNotEmpty()) " (earlier attempts:" + errors + ")" else ""),
        )
        startWatchdog()
    }

    /** Removes the overlay. */
    fun detach() {
        val h = host
        /* 日志去重：只在**真的拆掉了东西**时打这一条。
           原先无条件 Log.i 在「媒体页不挂载」路径上会被 1 秒看门狗反复命中，
           而那时什么都没拆 —— 刷出来的「detached」是假信息（真机实测每秒一条）。

           ⚠️ 注意这里**只影响日志，不影响下面任何一行状态重置**。
           曾经想写成「什么都没挂就 early return」，那是错的：`expanded` / `currentScroll`
           可能仍持有上一次面板留下的值，跳过重置会让下一次 openPanel 误判为「已经展开」。
           代价是一个多余的布尔判断，收益是绝不动摇既有语义。 */
        val hadSomething = h != null || balloon != null || panel != null
        try {
            val parentVg = h?.parent as? ViewGroup
            if (parentVg != null) {
                parentVg.removeView(h)
            }
        } catch (_: Throwable) {
            // already gone
        }
        host = null
        balloon = null
        panel = null
        currentScroll = null
        expanded = false
        if (hadSomething) Log.i(TAG, "floating: detached")
    }

    /**
     * NEW: a 1-second watchdog.
     *
     * WeChat replaces its content view, recreates the host Activity and hides fragments, all of which
     * can silently drop our overlay. Rather than guessing which of those happened, the balloon is
     * simply checked once a second and re-attached when it is gone.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    checkAlive()
                } catch (_: Throwable) {
                    // never let the watchdog die
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(tick, 1000L)
        Log.i(TAG, "floating: watchdog started (checks every 1s)")
    }

    /**
     * Keeps the balloon alive AND on top.
     *
     * The v1.0.48 log showed the balloon attached once and then never again:
     *
     *     floating: balloon attached ... via addContentView
     *     floating: balloon geometry attached=true size=165x165 visible=true
     *
     * `isAttachedToWindow` stayed true, so the watchdog saw nothing wrong -- but WeChat had added the
     * chat screen ON TOP of the content view we attached to, so the balloon was covered rather than
     * removed. Detecting that is unreliable, so the balloon is simply RAISED on every tick. Raising is
     * cheap (no re-layout) and defeats any view placed above ours.
     */
    private fun checkAlive() {
        val activity = currentHostActivity()
        val h = host

        // The balloon is only visible if it lives in the window that is ON TOP.
        //
        // item 1: the previous test was `h.parent == null`, which misses the case that actually breaks it:
        // after "+ -> 相册", the balloon is still attached -- to the CHAT Activity, which the album has
        // covered. Nothing looked dead, so the watchdog only called bringToFront() on an invisible window and
        // the balloon never came back. Comparing the balloon's OWNER with the Activity on top catches it.
        val owner = ownerRef?.get()
        val wrongWindow = owner != null && activity != null && owner !== activity
        val hostDead = h == null || h.parent == null || activity == null || wrongWindow

        if (hostDead) {
            if (activity == null) {
                // Nothing to attach to right now; WeChat is between screens. Wait for the next tick
                // instead of re-attaching onto a dead window.
                return
            }
            // Moving to a DIFFERENT Activity is a legitimate, expected event, so it is not subject to the
            // cooldown -- only repeated failures are.
            if (!wrongWindow &&
                System.currentTimeMillis() - lastReattachAt < REATTACH_COOLDOWN_MS
            ) {
                return
            }
            lastReattachAt = System.currentTimeMillis()
            Log.i(
                TAG,
                "floating: re-attaching (dead=" + (h == null || h.parent == null) +
                    " wrongWindow=" + wrongWindow +
                    " owner=" + (owner?.javaClass?.simpleName ?: "none") +
                    " top=" + activity.javaClass.simpleName + ")",
            )
            host = null
            balloon = null
            panel = null
            currentScroll = null
            attach(activity)
            return
        }

        // Still attached: keep it above anything WeChat added after us.
        try {
            h.bringToFront()
            balloon?.bringToFront()
        } catch (_: Throwable) {
            // best effort
        }
    }

    /**
     * item 1: the Activity the balloon should live in RIGHT NOW.
     *
     * The previous version reused `lastContext`, which could belong to a finished Activity. This asks the
     * balloon's own window first (that is where it must be drawn), then falls back to the newest Activity
     * we have seen, and rejects any Activity that is finishing or destroyed.
     */
    private fun currentHostActivity(): Activity? {
        val candidates = ArrayList<Activity>(3)
        try {
            host?.let { h ->
                var c: android.content.Context? = h.context
                while (c is android.content.ContextWrapper) {
                    if (c is Activity) {
                        candidates.add(c)
                        break
                    }
                    c = c.baseContext
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
        lastActivityRef?.get()?.let { candidates.add(it) }
        (lastContext as? Activity)?.let { candidates.add(it) }

        for (a in candidates) {
            if (a.isFinishing) continue
            if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed) continue
            return a
        }
        return null
    }


    /**
     * v4.0.0: LONG PRESSING the balloon opens 文本优化.
     *
     * @param anchor the balloon view, used as the reference for every lookup -- it is guaranteed to be
     *               attached to the live window, which is what InputInjector needs to find WeChat's chat
     *               input box (a detached view would resolve to a stale Activity).
     *
     * Anything that goes wrong here is logged and swallowed: a long press must never take the chat
     * screen down with it.
     */
    private fun onBalloonLongPressed(anchor: View) {
        try {
            // The chat filter is refreshed first, exactly like a tap does, so anything the dialog reads
            // refers to the conversation the user is actually looking at.
            refreshTalker(force = true)
            val ctx = host?.context ?: lastContext ?: anchor.context
            Log.i(
                TAG,
                "floating: opening the text-polish dialog (ctx=" +
                    ctx.javaClass.simpleName + ", mode=" + mode + ")",
            )
            com.luoliaoo.wechat.ui.TextPolishDialog.show(ctx, anchor)
        } catch (t: Throwable) {
            Log.e(TAG, "floating: the text-polish dialog failed to open", t)
            try {
                android.widget.Toast.makeText(
                    anchor.context,
                    "\u843d\u804a\uff1a\u65e0\u6cd5\u6253\u5f00\u6587\u672c\u4f18\u5316\uff08" +
                        t.javaClass.simpleName + "\uff09",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) {
                // a missing toast is not worth failing over
            }
        }
    }

    /** Tapping the balloon: open the list, or close the panel when it is already open. */
    private fun onBalloonTapped() {
        // v3.1.0 埋点（surveyor t1 建议）：用户报「点击悬浮球，1-2 秒才出现 UI」，但日志里 58 。
        // "panel opened" 全部发生在点击后 0.0~0.3s（实，11/46/54/126ms）——*日志里根本没有那 1~2 秒的足迹**。
        // 这说明时间可能花在「触摸事件被系统/微信延迟送达」而不是「我们的代码慢」。
        // 光看代码猜是没用的，所以这里打三个时间戳，用它们的差值来区分到底是哪一段慢：
        //   TAP_DOWN  —— 手指按下（ACTION_DOWN）的时刻
        //   TAP_HAND  —— onBalloonTapped 真正被调用的时刻（TAP_HAND - TAP_DOWN = 事件投递延迟）
        //   TAP_DONE  —— 面板 + 卡片渲染完成（TAP_DONE - TAP_HAND = 我们自己的构建耗时）
        val tHand = android.os.SystemClock.uptimeMillis()
        Log.i(
            TAG,
            "TAP_BREAKDOWN: TAP_HAND=" + tHand +
                " sinceDown=" + (tHand - tapDownAt) + "ms" +
                " (0 = \u89e6\u6478\u4e8b\u4ef6\u7acb\u5373\u9001\u8fbe\uff1b\u5927\u503c = " +
                    "\u4e8b\u4ef6\u5728\u6392\u961f\uff0c\u4e0d\u662f\u6211\u4eec\u6e32\u67d3\u6162)",
        )
        if (expanded) {
            closePanel()
        } else {
            mode = MODE_LIST
            // tap-to-detect: find out which chat is open BEFORE the list is built, so the filter is
            // right on the very first tap rather than after a first analysis.
            refreshTalker(force = true)
            openPanel()
        }
        val tDone = android.os.SystemClock.uptimeMillis()
        Log.i(
            TAG,
            "TAP_BREAKDOWN: TAP_DONE=" + tDone + " buildCost=" + (tDone - tHand) +
                "ms totalFromDown=" + (tDone - tapDownAt) + "ms",
        )
    }

    private fun openPanel() {
        /* ★ v9.1.0：免责声明闸门（第二条）。
         *
         * 为什么这里也必须拦：用户有**两条**进入功能的路径 ——
         *   ① 长按消息 → 菜单里的「落聊」   → 经过 MenuHook.onOurRowTapped（已拦）
         *   ② 点悬浮球                     → 直接到这里（原先**没拦**）
         *
         * 只拦①的话，没同意过的用户点一下悬浮球就能正常使用模块，免责声明形同虚设。
         * 这个漏洞是靠真机日志发现的：装好 9.1.0 后点悬浮球，日志里**完全没有**
         * disclaimer 相关记录 —— 闸门根本没被执行到。
         *
         * 未同意时不打开面板，而是弹声明窗（ensureConsented 内部处理「已同意则静默放行」）。 */
        try {
            if (!com.luoliaoo.wechat.ui.DisclaimerDialog.ensureConsented(activityOf(host?.context))) {
                Log.i(TAG, "floating: disclaimer not accepted yet -- panel not opened")
                return
            }
        } catch (t: Throwable) {
            /* 与 MenuHook 同一条取舍：闸门自身出错时放行，
               不能因为检查代码抛异常就让用户完全打不开面板。 */
            Log.w(TAG, "floating: disclaimer gate failed, opening anyway: " + t.message)
        }

        refreshTalker(force = true)
        val ctx = host?.context ?: lastContext ?: return
        try {
            val old = panel
            if (old != null) (old.parent as? ViewGroup)?.removeView(old)
            val p = buildPanel(ctx)
            panel = p
            host?.addView(p)
            try {
                balloon?.bringToFront()
            } catch (_: Throwable) {
                // best effort
            }
            expanded = true
            render()
            Log.i(TAG, "floating: panel opened (mode=" + mode + ")")
        } catch (t: Throwable) {
            Log.e(TAG, "floating: openPanel failed", t)
        }
    }

    private fun closePanel() {
        try {
            val p = panel
            (p?.parent as? ViewGroup)?.removeView(p)
        } catch (_: Throwable) {
            // already gone
        }
        panel = null
        currentScroll = null
        expanded = false
        Log.i(TAG, "floating: panel closed")
    }

    /**
     * Opens the module's website in the device's DEFAULT browser.
     *
     * An explicit ACTION_VIEW with an http URL is exactly what makes Android route to the chosen browser,
     * with no chooser and no in-app WebView. NEW_TASK is required because the panel lives inside WeChat's
     * process and is not backed by one of our own activities.
     *
     * A failure is reported rather than swallowed -- a button that silently does nothing is
     * indistinguishable from a broken one.
     */
    private fun openOfficialSite(ctx: Context) {
        val url = com.luoliaoo.wechat.ui.AnalysisCard.OFFICIAL_SITE
        try {
            ctx.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "opening the official site failed: " + t.message)
            try {
                android.widget.Toast.makeText(
                    ctx,
                    "\u65e0\u6cd5\u6253\u5f00\u6d4f\u89c8\u5668\uff0c\u8bf7\u624b\u52a8\u8bbf\u95ee " + url,
                    android.widget.Toast.LENGTH_LONG,
                )
                    .show()
            } catch (_: Throwable) {
                // nothing else we can do
            }
        }
    }

    /** Jumps straight to a card (used when an analysis finishes). */
    fun showCard(key: String) {
        selected = key
        mode = MODE_CARD
        // v4.0.0: the card is the ONLY way an analysis result is ever shown, so it must not be dropped when
        // the host happens to be missing for a moment. attach() normally builds it synchronously, but it
        // gives up when no Activity can be resolved (e.g. WeChat is between screens); a short retry rides
        // that out instead of silently losing the result.
        if (host == null) {
            retryShowCard(key, attempt = 0)
            return
        }
        if (!expanded) openPanel() else render()
    }

    /** v4.0.0: retries showCard a few times while waiting for the overlay host to exist. */
    private fun retryShowCard(key: String, attempt: Int) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (attempt >= SHOW_CARD_RETRIES) {
            Log.w(TAG, "floating: giving up on showing the card for " + key + " (no overlay host)")
            return
        }
        if (host == null) {
            val ctx = lastContext
            if (ctx != null) {
                try {
                    attach(ctx)
                } catch (_: Throwable) {
                    // the retry below is the fallback
                }
            }
        }
        if (host != null) {
            if (!expanded) openPanel() else render()
            return
        }
        Log.i(TAG, "floating: host not ready for " + key + "; retry " + (attempt + 1))
        handler.postDelayed({ retryShowCard(key, attempt + 1) }, SHOW_CARD_RETRY_MS)
    }

    /** item: 重，-- back to the selection list. */
    fun showList() {
        mode = MODE_LIST
        if (!expanded) openPanel() else render()
    }

    /** Re-renders whatever is on screen. */
    fun refresh() {
        if (expanded) render()
    }

    /**
     * v3.1.0 (t4): re-applies panel geometry (width / max height) from the config, live.
     *
     * WHY THIS EXISTS: the panel width is read ONCE, in buildPanel(), from `ConfigRepo.loadLocal`, and the
     * body height cap is derived inside render(). Changing 宽度 in the 悬浮窗控，form therefore only
     * showed up after the panel was CLOSED and RE-OPENED -- which the user reasonably read as "the change
     * did not take effect, restart WeChat". This applies the new geometry to the panel that is on screen
     * right now, so a save is visible immediately without reopening anything.
     *
     * The width is set directly and the body is then RE-RENDERED, rather than the height cap being
     * recomputed here: the cap depends on the panel's measured fixed rows (see render()), so duplicating
     * that arithmetic would be a second source of truth that drifts. render() is cheap, and it re-derives
     * the cap from the panel's new width, which is precisely what a width change needs.
     */
    fun onConfigGeometryChanged(ctx: Context? = null) {
        val p = panel ?: return
        val c = ctx ?: p.context ?: return
        try {
            val cfg = try {
                ConfigRepo.loadLocal(c)
            } catch (_: Throwable) {
                Config()
            }
            // width: the same measurement buildPanel() uses, so re-opening later is a no-op change.
            val width = panelWidthPx(c, cfg)
            val widthChanged = (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.width != width) {
                    lp.width = width
                    p.layoutParams = lp
                    true
                } else {
                    false
                }
            } ?: false

            // captain 复核补丁 v2：重建的判定条件。
            //
            // 【第一版写错了】我最初写「宽度没变时高度预算也没变（预算由面板宽，+ 固定行推导）」。
            // perfeng 核查后指出这个理，*不成，*：panelBudget 只由 屏幕，- topMargin - 内边，推导。
            // 与面板宽度无关。理由错了，但我顺着这条线索查出了一个真问题 —— 见下。
            //
            // 【真问题】t3 弃用 panelMaxHeightDp 之后，那个设置在界面上是**死的**（改了永远不变）。
            // 我已让它作为「上限」重新参，maxH 的计算（，render() 里的 storedCap）。
            // 于是：只改「最大高度」而不改宽度时，maxH 会变，但宽度没变 —— 若只按宽度判断就会跳过重建，
            // 设置又不生效了，等于白修。所以判定必须同时看这两个输入。
            //
            // 什么时候真的需要重建：
            //   * 宽度变了      ，文字换行，，fixedH ，，maxH 。
            //   * 存储的高度上限变，，maxH 直接。
            // 两者都没变时跳过：render() ，removeAllViews() 重建整棵树，白白冲掉用户的滚动位置和输入焦点
            // （用户刚要滑到卡片底部看清内容，一改设置就被弹回顶部）。
            val storedCapChanged = lastAppliedStoredCapPx != -1 &&
                lastAppliedStoredCapPx != storedCapPxOf(cfg, c)
            if (widthChanged || storedCapChanged) {
                render()
            } else {
                Log.i(TAG, "floating: geometry unchanged; skipping the rebuild (keeps scroll/focus)")
            }
            Log.i(
                TAG,
                "floating: panel geometry re-applied live (w=" + width +
                    "px, widthChanged=" + widthChanged +
                    ", storedCapChanged=" + storedCapChanged + ")",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "floating: live geometry update failed: " + t.message)
        }
    }

    /**
     * item 4: sets the token line and re-renders.
     *
     * Called by the controller when an analysis or a follow-up answer arrives, so the counts stay
     * current -- including after a follow-up, which is what the user asked for.
     */
    fun setUsage(prompt: Int, completion: Int) {
        // Backwards-compatible entry point: applies to the currently selected record.
        selected?.let { setUsage(it, prompt, completion) }
    }

    /** item 2: records the usage for ONE key, so the list and the card can never disagree. */
    fun setUsage(key: String, prompt: Int, completion: Int) {
        if (prompt <= 0 && completion <= 0) {
            usageByKey.remove(key)
        } else {
            usageByKey[key] = "\u8f93\u5165 " + prompt + " \u00b7 \u8f93\u51fa " + completion +
                " \u00b7 \u5408\u8ba1 " + (prompt + completion) + " Tokens"
        }
        /* ★ v10.3.0：只更新那一行文字，不重画整块面板。
           ─────────────────────────────────────────────────────────────────────
           【原来是 `if (expanded) render()`，那正是用户报的"闪一下"】
           `render()` 的第一步是 `p.removeAllViews()` —— 把整个面板（标题栏、四个按钮、
           联系人行、用量行、版本行、卡片）全部拆掉重画。
           而这个函数被调用在各种 token 用量刷新的时机上，其中就包括**追问答案到达的那一刻**：
           于是用户看到的是"AI 思考好了 → 整块面板闪一下"。
           为了一行小字去拆整个界面，代价和观感都完全不成比例。

           【就地改写】用量行是面板上一个常驻的 TextView，把它记在 [usageLine] 里，
           这里直接改它的 text —— 只有那一行会重绘，面板其余部分纹丝不动。
           找不到它（面板还没建、或处于列表模式）时才回退到重画，保证内容不会丢。 */
        val tv = usageLine
        if (tv != null && tv.parent != null) {
            try {
                tv.text = usageText(key)
                return
            } catch (_: Throwable) {
                // 落到下面的重画兜底
            }
        }
        if (expanded) render()
    }

    /**
     * 用量行的显示文案。与 [render] 里创建该行时的写法**必须完全一致**，
     * 否则就地更新后会出现"这一行变了样"（例如少了「本次用量：」前缀）。
     * 所以这里复用同一个 [usageFor]，而不是另拼一遍字符串。
     */
    private fun usageText(key: String?): String =
        "本次用量：" + usageFor(key).ifBlank { "暂无数据" }

    /**
     * item NEW: the persisted on/off switch, read defensively.
     *
     * Defaults to TRUE (shown) when the value cannot be read, which is the module's historical behaviour --
     * a broken config read must never make the balloon disappear without the user asking.
     */
    private fun balloonEnabledFromConfig(ctx: Context?): Boolean = try {
        if (ctx == null) true else ConfigRepo.loadLocal(ctx).balloonEnabled
    } catch (_: Throwable) {
        true
    }

    /**
     * v4.0.0: should the round BUTTON be on screen right now?
     *
     * Checks the in-process latch first (set by hideGlobal before its posted body runs, so a racing
     * attach cannot slip past), then the persisted switch.
     */
    private fun balloonVisible(): Boolean {
        if (hidden) return false
        val ctx = host?.context ?: lastContext
        return balloonEnabledFromConfig(ctx)
    }

    /**
     * v4.0.0: adds or removes the button on an EXISTING host, without touching the host itself.
     *
     * Needed because the switch can flip while a host is already attached: turning the balloon off in the
     * module app must remove the button from the overlay that is on screen, and turning it back on must
     * bring it back -- all while the host keeps rendering analysis cards either way.
     */
    private fun syncBalloonVisibility(root: FrameLayout) {
        try {
            val want = balloonVisible()
            val b = balloon
            val hasIt = b != null && b.parent === root
            if (want && !hasIt) {
                val fresh = balloon ?: buildBalloon(root.context)
                balloon = fresh
                root.addView(fresh)
                Log.i(TAG, "floating: balloon button ADDED (switch is ON)")
            } else if (!want && hasIt) {
                root.removeView(b)
                Log.i(TAG, "floating: balloon button REMOVED (switch is OFF; the host stays for cards)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "floating: reconciling the balloon button failed -> " + describe(t))
        }
    }

    /** item 2: the usage line for a key, preferring the controller's value when it has one. */
    private fun usageFor(key: String?): String {
        if (key == null) return ""
        val fromController = try {
            usageProvider?.invoke(key)
        } catch (_: Throwable) {
            null
        }
        return fromController?.takeIf { it.isNotBlank() }
            ?: usageByKey[key]
            ?: ""
    }

    /** item 2: brings the balloon to the front, used when the screen changes under us. */
    /**
     * item 1: called whenever an Activity resumes, so the balloon follows the screen immediately.
     *
     * Waiting for the 1-second watchdog was too slow for flows that open several screens in a row
     * (+ -> 相册 -> preview -> send): the balloon could be left in a covered window for several ticks.
     */
    fun followActivity(activity: android.app.Activity?) {
        if (activity == null) return
        try {
            onMainThread("followActivity") { attach(activity) }
        } catch (_: Throwable) {
            // best effort
        }
    }

    /**
     * item NEW: the balloon's size in dp, after the user's percentage is applied.
     *
     * The percentage is CLAMPED, and that clamp is not cosmetic. This number becomes a View's width and
     * height, and the balloon is the only way to open the panel -- a hand-edited or corrupted value
     * could otherwise make it 0dp (untappable, the feature gone with no way back) or several thousand dp
     * (covering the entire chat). `BALLOON_SCALE_MIN`..`BALLOON_SCALE_MAX` keeps both unreachable.
     */
    private fun balloonSizeDp(ctx: Context): Int {
        val pct = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx).balloonScale
        } catch (_: Throwable) {
            100
        }.coerceIn(BALLOON_SCALE_MIN, BALLOON_SCALE_MAX)
        return (BALLOON_DP * pct / 100f).toInt().coerceAtLeast(MIN_BALLOON_DP)
    }

    /**
     * item NEW: applies the stored balloon size to the live balloon.
     *
     * Called at attach and after the slider moves. `buildBalloon` sizes the view when it is CREATED, so
     * a size change has to resize the existing view -- otherwise the setting only takes effect after the
     * balloon is re-attached (i.e. after a restart), which reads as "the slider does nothing".
     */
    fun applyBalloonScale() {
        val v = balloon ?: return
        val px = NukeTheme.dp(v.context, balloonSizeDp(v.context))
        try {
            v.layoutParams = (v.layoutParams ?: FrameLayout.LayoutParams(px, px)).apply {
                width = px
                height = px
            }
            v.requestLayout()
        } catch (_: Throwable) {
            // A size that fails to apply is cosmetic; the balloon keeps its previous size.
        }
    }

    /**
     * item 3: applies the stored balloon transparency.
     *
     * The config stores TRANSPARENCY in percent (0 = opaque, 80 = maximum), so the view alpha is
     * `1 - alpha/100`. Clamped here as well as in the UI, so a hand-edited value cannot make the balloon
     * invisible or impossible to tap.
     */
    fun applyBalloonAlpha() {
        val v = balloon ?: return
        val pct = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(v.context).balloonAlpha
        } catch (_: Throwable) {
            0
        }.coerceIn(0, 80)
        applyBalloonAlphaNow(pct)
    }

    /**
     * v3.1.0 (t4): applies a transparency the caller ALREADY knows.
     *
     * The slider used to save the value and then reach back into SharedPreferences to read it again on
     * every progress tick. The value is right there, so it is applied directly -- one less disk read on
     * the UI thread per frame of a drag.
     */
    private fun applyBalloonAlphaNow(pct: Int) {
        val v = balloon ?: return
        try {
            v.alpha = 1f - (pct.coerceIn(0, 80) / 100f)
        } catch (_: Throwable) {
            // alpha is cosmetic
        }
    }


    /**
     * The shared slider dialog used by BOTH balloon controls -- transparency and size.
     *
     * WHY ONE BUILDER FOR TWO DIALOGS. They are the same control with different ranges and wording, and
     * they had drifted: each was written separately with a bare `AlertDialog`, a plain `TextView` caption
     * and an unstyled `SeekBar`, so neither matched the module's own look (panels, cards, accent-coloured
     * buttons). The user asked for a UI on both, and building it once is also what stops the next edit
     * from improving one and leaving the other behind.
     *
     * WHAT THE UI IS, and why each part:
     *   * `NukeTheme.eyebrow` for the small accent caption -- the same header treatment as every other
     *     module dialog, so a balloon setting reads as part of the module rather than a system prompt;
     *   * a LARGE value readout, because the number is the thing being set and the user should see it
     *     change while dragging rather than reading it out of the caption;
     *   * the range's two end labels, so "50%" and "200%" mean something without being memorised;
     *   * a styled `SeekBar` with the module's accent as its thumb tint.
     */
    private fun showSliderDialog(
        ctx: android.content.Context,
        title: String,
        start: Int,
        min: Int,
        max: Int,
        format: (Int) -> String,
        lowLabel: String,
        highLabel: String,
        onChange: (Int) -> Unit,
        onCommit: (Int) -> Unit,
    ) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                NukeTheme.dp(ctx, 20), NukeTheme.dp(ctx, 4),
                NukeTheme.dp(ctx, 20), NukeTheme.dp(ctx, 6),
            )
        }

        // The big readout: the number, in the accent colour, at a size that reads as the subject of the
        // dialog rather than as a caption.
        val value = TextView(ctx).apply {
            text = format(start)
            setTextColor(NukeTheme.ACCENT)
            textSize = NukeTheme.sp(ctx, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        box.addView(
            value,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = NukeTheme.dp(ctx, 6) },
        )

        // The two ends, so the scale is legible at a glance.
        val ends = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        ends.addView(
            NukeTheme.hintText(ctx, lowLabel).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        ends.addView(
            NukeTheme.hintText(ctx, highLabel).apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        box.addView(ends)

        val bar = android.widget.SeekBar(ctx).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (start - min).coerceIn(0, this.max)
            // The module's accent on the thumb, so the control matches the rest of the UI instead of the
            // host theme. `buttonTintList` beats styling the whole widget: the track stays native
            // (correct on every host) while the draggable part is ours.
            try {
                this.thumbTintList = android.content.res.ColorStateList.valueOf(NukeTheme.ACCENT)
            } catch (_: Throwable) {
            }
        }
        box.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = NukeTheme.dp(ctx, 2) },
        )

        val persist = android.os.Handler(android.os.Looper.getMainLooper())
        var pending = start
        val pendingSave = Runnable { onCommit(pending) }
        fun scheduleSave(v: Int) {
            pending = v
            persist.removeCallbacks(pendingSave)
            persist.postDelayed(pendingSave, 300L)
        }

        bar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (p + min).coerceIn(min, max)
                    value.text = format(v)
                    // Live preview, zero IO -- see the class docs on why storage is never touched here.
                    onChange(v)
                    scheduleSave(v)
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    persist.removeCallbacks(pendingSave)
                    onCommit(pending)
                }
            },
        )

        val dlg = android.app.AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("\u5b8c\u6210") { _, _ ->
                try {
                    com.luoliaoo.wechat.data.ConfigRepo.flushMirror()
                } catch (_: Throwable) {
                }
            }
            .create()
        dlg.setOnDismissListener {
            try {
                com.luoliaoo.wechat.data.ConfigRepo.flushMirror()
            } catch (_: Throwable) {
            }
        }
        dlg.showStyled()
    }

    /** item NEW: the balloon-size slider (client of [showSliderDialog]). */
    private fun showBalloonScaleDialog(ctx: android.content.Context) {
        val start = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx).balloonScale
        } catch (_: Throwable) {
            100
        }.coerceIn(BALLOON_SCALE_MIN, BALLOON_SCALE_MAX)
        showSliderDialog(
            ctx = ctx,
            title = "\u60ac\u6d6e\u7403\u5927\u5c0f",
            start = start,
            min = BALLOON_SCALE_MIN,
            max = BALLOON_SCALE_MAX,
            format = { it.toString() + "%" },
            lowLabel = "\u6700\u5c0f " + BALLOON_SCALE_MIN + "%",
            highLabel = "\u6700\u5927 " + BALLOON_SCALE_MAX + "%",
            onChange = { applyBalloonScaleNow(it) },
            onCommit = { saveBalloonScale(ctx, it) },
        )
    }

    /** item 3: the balloon-transparency slider (client of [showSliderDialog]). */
    private fun showBalloonAlphaDialogStyled(ctx: android.content.Context) {
        val start = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx).balloonAlpha
        } catch (_: Throwable) {
            0
        }.coerceIn(0, 80)
        showSliderDialog(
            ctx = ctx,
            title = "\u60ac\u6d6e\u7403\u900f\u660e\u5ea6",
            start = start,
            min = 0,
            max = 80,
            format = { it.toString() + "%" },
            lowLabel = "0% \u4e0d\u900f\u660e",
            highLabel = "80% \u6700\u900f\u660e",
            onChange = { applyBalloonAlphaNow(it) },
            onCommit = { saveBalloonAlpha(ctx, it) },
        )
    }
    /**
     * item 3: saves the transparency and applies it to the balloon at once.
     *
     * Kept separate from [saveBalloonScale] rather than shared through a setter lambda: the two write
     * DIFFERENT config fields (`balloonAlpha` / `balloonScale`) on an immutable `Config`, so a shared
     * version would need a copy-lambda anyway and would be harder to read than these two short functions.
     * What IS shared is the no-change guard and `saveLocalAndMirror`, which is where the real substance
     * lives -- see the comments inside.
     */
    private fun saveBalloonAlpha(ctx: android.content.Context, value: Int) {
        val v = value.coerceIn(0, 80)
        try {
            val repo = com.luoliaoo.wechat.data.ConfigRepo
            val cfg = repo.loadLocal(ctx)
            // t4: 值没变就什么都不做。拖动结束时，flush 常常与防抖任务重复触发同一次保存，
            // 这里挡掉第二次，避免白写一，prefs（SecureStore 那条路径尤其贵）。
            if (cfg.balloonAlpha == v) {
                applyBalloonAlphaNow(v)
                return
            }
            // v3.1.0 (t4): saveLocalAndMirror, not saveLocal. With only the local write the module app
            // kept showing the OLD transparency and pushed that stale value back on its next save -- the
            // "changed it but it did not take" report.
            repo.saveLocalAndMirror(ctx, cfg.copy(balloonAlpha = v))
        } catch (_: Throwable) {
            // persistence is best effort
        }
        // The value is already known here, so the balloon is updated without a second config read.
        applyBalloonAlphaNow(v)
    }

    private fun applyBalloonScaleNow(pct: Int) {
        val v = balloon ?: return
        val p = pct.coerceIn(BALLOON_SCALE_MIN, BALLOON_SCALE_MAX)
        val px = NukeTheme.dp(v.context, (BALLOON_DP * p / 100f).toInt().coerceAtLeast(MIN_BALLOON_DP))
        try {
            // THE CONTAINER AND THE ARTWORK MUST BOTH BE RESIZED, and resizing only the container is
            // exactly what the user reported as two bugs: the circle stayed at its old size, so shrinking
            // clipped it into a SQUARE and growing left WHITE all around it. The artwork is rebuilt from
            // the source at the new size rather than stretched, so it also stays sharp.
            v.layoutParams = (v.layoutParams ?: FrameLayout.LayoutParams(px, px)).apply {
                width = px
                height = px
            }
            val art = v.findViewWithTag<View>(BALLOON_ART_TAG)
            if (art != null) {
                art.layoutParams = (art.layoutParams ?: FrameLayout.LayoutParams(px, px)).apply {
                    width = px
                    height = px
                }
                BalloonIcon.circular(v.context, px)?.let { art.background = it }
            }
            v.requestLayout()
            v.invalidate()
        } catch (_: Throwable) {
            // cosmetic
        }
    }

    /** Marks the balloon's artwork child, so a resize can find it and rebuild it at the new size. */
    private val BALLOON_ART_TAG = "luoliaoo.balloon.art"

    /** Saves the size and applies it, mirroring [saveBalloonAlpha] including its no-change guard. */
    private fun saveBalloonScale(ctx: android.content.Context, value: Int) {
        val v = value.coerceIn(BALLOON_SCALE_MIN, BALLOON_SCALE_MAX)
        try {
            val repo = com.luoliaoo.wechat.data.ConfigRepo
            val cfg = repo.loadLocal(ctx)
            if (cfg.balloonScale == v) {
                applyBalloonScaleNow(v)
                return
            }
            // saveLocalAndMirror for the same reason the transparency uses it: with only the local
            // write the module app keeps a stale value and pushes it back on its next save.
            repo.saveLocalAndMirror(ctx, cfg.copy(balloonScale = v))
        } catch (_: Throwable) {
            // persistence is best effort
        }
        applyBalloonScaleNow(v)
    }

    /**
     * ★ v9.3.0：把悬浮球当前所在的**屏幕坐标**持久化，重启后不再自动居中。
     *
     * 用户要求：「悬浮球固定位置 —— 重启应用后不再自动居中，而是原本位置」。
     *
     * 【为什么在拖动结束时存屏幕坐标，而不是存相对偏移】
     * 与 config 字段的注释同一个理由：宿主的聊天内容区会移动伸缩，只有屏幕绝对坐标
     * 才能在每次启动时反推出正确偏移。这里存的就是 `lockedScreenX/Y` 正在用的那两个值 ——
     * 也就是说**存的是同一个语义**，不是另发明一套坐标。
     *
     * 【为什么用 saveLocalAndMirror】
     * 与 [saveBalloonScale] 完全一致：只写本地的话，模块设置 App 那边会拿旧值，
     * 下次它一保存就把位置覆盖回默认。这是本项目已有结论（见上面那段注释）。
     *
     * 【为什么失败只记日志不重试】
     * 位置是纯偏好。写不进去顶多是「这次重启回到了居中」，不影响任何功能，
     * 为此弹提示或重试都不划算。但不能静默 —— 用户很可能正是因为「位置没记住」来报障的。
     */
    private fun saveBalloonPos(hostCtx: Context, screenX: Int, screenY: Int) {
        try {
            val repo = com.luoliaoo.wechat.data.ConfigRepo
            val cfg = repo.loadLocal(hostCtx)
            /* 位置没变就不写。拖动一次会走到这里，但「拖动后又拖回原位」、以及
               布局变化触发的重复调用都可能命中 —— 避免无意义的磁盘写入与镜像刷新。 */
            if (cfg.balloonPosX == screenX && cfg.balloonPosY == screenY) return
            repo.saveLocalAndMirror(
                hostCtx,
                cfg.copy(balloonPosX = screenX, balloonPosY = screenY),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "floating: could not persist the balloon position: " + t.message)
        }
    }

    fun raise() {
        try {
            host?.bringToFront()
            balloon?.bringToFront()
        } catch (_: Throwable) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ panel

    private fun buildPanel(ctx: Context): LinearLayout {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        val cfg = try {
            ConfigRepo.loadLocal(ctx)
        } catch (_: Throwable) {
            Config()
        }
        val width = panelWidthPx(ctx, cfg)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // t5 液态玻璃：直接，NukeTheme 的玻璃表面工厂（三层：半透明身体 + 顶部高光 +
            // 左上受光），圆角走全站统一，R_PANEL(24dp)，不再硬编码数字。
            // 宽度/高度/位置一概不，——panelWidthPx ，render() 的高度预算都建立，*外框**之上。
            // 与圆角和背景无关。
            background = NukeTheme.glassPanel(ctx, NukeTheme.R_PANEL)
            // 内边距保持原值：render() 会用自己，p.paddingTop/paddingBottom 反推
            // 滚动区高度预算，所以任，padding 改动都会连带影响 maxH ——不动它，
            // 就保证悬浮面板的尺寸/高度限制行为与改动前完全一致（铁律 2）。
            setPadding(dp(16), dp(14), dp(16), dp(14))
            tag = Constants.TAG_OWN_UI
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    // Top-centre, so the balloon (right edge, vertically centred) is never covered.
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(90)
                }
        }
    }

    /** The configured width, measured with the same character metric the card uses. */
    private fun panelWidthPx(ctx: Context, cfg: Config): Int {
        return try {
            val dm = ctx.resources.displayMetrics
            val paint = Paint()
            paint.textSize = 13f * dm.scaledDensity
            val one = paint.measureText("\u6c49")
            val padH = (32 * dm.density).toInt()
            val wanted = (cfg.cardWidth * one).toInt() + padH
            val max = (dm.widthPixels * 0.94f).toInt()
            wanted.coerceIn(padH + 1, max.coerceAtLeast(padH + 1))
        } catch (_: Throwable) {
            (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        // tap-to-detect: keep the filter current while the panel is open (a chat switch must be noticed).
        refreshTalker()
        val ctx = panel?.context ?: return
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        val p = panel ?: return
        try {
            // item 2b (earlier): remember the scroll position; re-rendering used to reset it to the top.
            currentScroll?.let { sv -> panelScrollY = sv.scrollY }

            p.removeAllViews()

            // t3: a previous render may have pinned the panel height to a pixel budget (see below). Reset it
            // before rebuilding, or a card that is shorter than the last one would inherit the old, larger
            // height and leave a block of empty space.
            (p.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                p.layoutParams = lp
            }

            // ---- 标题栏：标题 + 四个等宽按钮，全部同一排
            //
            // 【v9.0.1 布局整改（用户明确要求）】
            //   列表模式：分析记录 | 官网 | 关闭 | 透明 | 大小
            //   卡片模式：分析卡片 | 重选 | 透明 | 大小 | 关闭
            //
            // 改之前有两个问题（都是用户点出来的）：
            //   ① 「透明」「大小」在标题栏**下面独占第二排**（原来那段独立 row 已删除），
            //      面板白白多占一行高度，而它们本来就是悬浮球的属性、和「关闭」同级；
            //   ② 按钮各自按文案宽度自适应，于是「官网」(2 字)、「关闭」(2 字)、
            //      「透明」「大小」宽窄不一 —— 用户要求「大小一致」。
            //
            // 做法：标题设 weight=1 吃掉剩余宽度并允许省略号截断，四个按钮一律取
            // 固定宽度 HEAD_BTN_W_DP，因此严格等宽等高。
            //
            // 为什么给固定宽度而不是 weight：weight 均分会按比例分配，文案长短不一时
            // 每个按钮的视觉留白仍不一致；固定宽度才能做到「大小一致」这个明确要求。
            //
            // 宽度取值经过实测计算，见 HEAD_BTN_W_DP 的注释。
            val headBtnW = dp(HEAD_BTN_W_DP)
            /* 标题栏专用按钮。
               
               【为什么不直接，NukeTheme.secondaryButton。
               实测算过宽度：面板内容宽，306dp，60dp 窄屏 × 94% ，面板内边距）。
               四个按钮 + 标题必须挤进这个宽度。，secondaryButton ，14sp 字号 + 左右，16dp
               内边距，单是「透明 100%」就要约 92dp ——四个按钮 368dp，*远超 306dp**。
               结果必然是文字被截成「透明 1…」，比改之前还难看。
               
               所以这里做一个紧凑版，2sp 字号 + 左右，6dp 内边，+ 固定 60dp 宽。
               「透明 100%」在 12sp 下约 52dp 文字，+ 12dp 内边，= 64dp。
               ，「关闭，「官网，「重选」只，24dp 文字，同样撑，60dp ——
               四个按钮因此**严格等宽等高**（用户要求「大小一致」）。
               
               【触控不降级】高度仍，NukeTheme ，minimumHeight = 48dp 兜底
               （secondaryButton 内部已设，我们没覆盖），无障碍标准不受影响。*/
            fun headerBtn(label: String, onClick: () -> Unit): TextView =
                NukeTheme.secondaryButton(ctx, label, onClick).apply {
                    /* 覆盖字号与内边距 ——secondaryButton 给的，14sp / 16dp。
                       在标题栏这一排太宽。样式（背景/圆角/颜色）仍沿用工厂。
                       所以四个按钮与面板其它按钮看起来是同一套材质。*/
                    textSize = NukeTheme.sp(ctx, 12f)
                    /* t3: 左右内边距保持 6dp（未改动）。
                       44dp − 2×6dp = **32dp 文字区**，装两字标签（@12sp ≈ 24dp）绰绰有余，
                       所以宽度从 56 收到 44 之后**不需要**再动内边距 —— 文字依然不会截断。
                       这笔账与 44dp 的取值一起推导在 HEAD_BTN_W_DP 的注释里。
                       （NukeTheme.secondaryButton 给的是 14sp / 16dp 内边距，在标题栏这一排太宽：
                         单是「透明 100%」就要约 92dp，四个按钮 368dp 远超 306dp 的内容宽。）*/
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        headBtnW,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(HEAD_BTN_GAP_DP) }
                    /* 文案过长时收成单，+ 省略号：固定宽度下这是必需的，
                       否则文案会被压成两行、把整排撑高。
                       t3：当前五个标签（手动/官网/透明/大小/关闭）+ 卡片模式的（重选/透明/大小/关闭）
                       全部是 2 字，44dp 宽度下**都不会触发**这个兜底；它只在宿主把系统字体
                       放大到极端值时生效。*/
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                TextView(ctx).apply {
                    /* v9.0.1: 标题不再带尖括号说明。
                       原文案是「分析记，长按信息-分析-使用>，「分析卡，可上下滑，」，
                       加进这一排后必然挤爆 ——而用户给的排版里标题就是简洁的两个词。
                       那条「怎么用」的引导信息移到了面板底部的说明区（见下面的 HINT 行）。
                       内容没丢，只是不再占着标题位置。*/
                    text = if (mode == MODE_LIST) {
                        "\u5206\u6790\u8bb0\u5f55"
                    } else {
                        "\u5206\u6790\u5361\u7247"
                    }
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            /* ★ v9.0.1 用户第 6 项：按钮顺序统一为
                    [重选(仅卡片)] [手动(仅列表)] [官网(仅列表)] 透明 大小 关闭

               列表模式：手动 | 官网 | 透明 | 大小 | 关闭      ← t3 新增「手动」，在官网左侧
               卡片模式：重选 | 透明 | 大小 | 关闭

               两种模式因此**结构完全一致**（只是首位那个模式专属按钮不同），
               所以下面写成一段线性代码，而不是原来的 if/else 两大块 ——
               两块分支里重复了三行 addView，顺序一旦要调就得改两处，
               正是这次改顺序时最容易出错的地方。

               ★ 用户第 3 项：卡片模式**移除「官网」**。
               卡片模式关注的是「这一条分析结果」，官网是模块级入口，
               放在这里挤掉了本该给操作按钮的空间（用户明确要求移除）。
               列表模式保留官网 —— 那是模块的主界面，官网入口属于它。

               ★ 用户第 4 项：按钮名就是「透明」「大小」两个词，**不带百分号**。
               早先版本曾拼成「透明 40%」「大小 120%」，但实测算过：
               面板内容宽约 306~310dp，按钮固定宽度时标题所剩无几，
               而「分析记录」@14sp 粗体需约 56dp —— 数值一拼进去两者都会被截断。
               当前数值改在点击后弹出的对话框里显示（那里本来就有进度条与实时百分比），
               信息并未丢失。
               注：t3 加入第 5 个按钮（手动）后，按钮宽度与间距已相应收窄，
               最新的完整宽度推算见 HEAD_BTN_W_DP 的注释。 */
            if (mode == MODE_CARD) {
                head.addView(headerBtn("\u91cd\u9009") { showList() })
            } else {
                /* ★★ t3：手动分析（用户原话「分析记录在官网左侧新增按钮 手动」）★★

                   位置严格按用户要求：插在「官网」**之前**，列表模式的顺序为
                       手动 | 官网 | 透明 | 大小 | 关闭
                   卡片模式不加 —— 与「官网」同理，「手动」是模块级入口，
                   它属于列表（模块主界面），而卡片模式关注的是「这一条分析结果」。

                   【为什么敢加第 5 个按钮 —— 宽度已算过，见 HEAD_BTN_W_DP 的长注释】
                   改动前 4 个按钮 @56dp + 4dp 间距 = 240dp，标题剩 66dp（刚好够 58dp 的标题）。
                   直接插第 5 个 56dp 按钮 = 5×(56+4) = 300dp → 标题只剩 **6dp** → 标题报废。
                   所以本次把 HEAD_BTN_W_DP 从 56 收到 **44**：
                       5 × (44 + 4) = 240dp → 标题剩 **66dp**，**与改动前完全持平** → 标题不受损。
                   44dp 下文字区 = 44 − 12 = 32dp > 两字标签的 24dp → 五个标签都不会被 ellipsize。
                   只改宽度、**不碰 minimumHeight**（仍是 48dp 触控高度），也不改内边距。 */
                head.addView(headerBtn("\u624b\u52a8") {
                    Log.i(TAG, "floating: opening the manual analysis dialog from the header")
                    try {
                        /* ctx 是面板自己的 Context（可能是 ContextWrapper 而非 Activity），
                           ManualAnalysisDialog 内部会自己沿 baseContext 链拆出 Activity；
                           拆不到会 toast 明确提示，不会静默。 */
                        com.luoliaoo.wechat.ui.ManualAnalysisDialog.show(ctx) {
                            // 分析已发起：刷新面板，让新记录尽快出现。
                            // （真正的卡片跳转由 startAnalysisForSelection 内部的
                            //   FloatingPanel.showCard(key) 负责，这里只是保险。）
                            refresh()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "floating: the manual analysis dialog failed to open", t)
                        try {
                            android.widget.Toast.makeText(
                                ctx,
                                "\u843d\u804a\uff1a\u65e0\u6cd5\u6253\u5f00\u624b\u52a8\u5206\u6790\uff08" +
                                    t.javaClass.simpleName + "\uff09",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        } catch (_: Throwable) {
                            // a missing toast is not worth failing over
                        }
                    }
                })
                /* 官网：打开官网是普通 ACTION_VIEW，交给系统默认浏览器。
                   它是模块的属性而非某一个界面的属性，所以放列表模式。 */
                head.addView(headerBtn("\u5b98\u7f51") { openOfficialSite(ctx) })
            }
            head.addView(headerBtn("\u900f\u660e") { showBalloonAlphaDialogStyled(ctx) })
            head.addView(headerBtn("\u5927\u5c0f") { showBalloonScaleDialog(ctx) })
            head.addView(headerBtn("\u5173\u95ed") { closePanel() })
            p.addView(head)

            // ---- item 3: the contact lines, in RED
            //
            // t5 液态玻璃：这里原来是硬编码，0xFFFF3B30（iOS 系统红）。语义保，——
            // 联系方式就是要「红、醒目」，与下面那几行说明文字（ACCENT 强调，/ TEXT_DIM 弱化色）
            // 拉开层级。但取值改为走 NukeTheme 体系：NukeTheme.DANGER_TEXT 就是这套主题。
            // 「危，警示」文字色，与面板里「删除」按钮（renderList 里的删除钮）用的是同一，token。
            // 这样一来，内核（theme-core）调整警示色时，悬浮面板会跟着变，而不是留下一。
            // 和全站对不上的孤立硬编码颜色层（铁律 3、铁，1）。
            val CONTACT_RED = NukeTheme.DANGER_TEXT
            /* 说明性文字行的辅助函数（一行一，TextView）。
               联系方式那三行不再走，——它们已并成一排（见下），
               但下面三条说明文字仍然各占一行，所以这，helper 继续保留。*/
            val line: (String, Int) -> Unit = { txt, color ->
                p.addView(
                    TextView(ctx).apply {
                        text = txt
                        setTextColor(color)
                        textSize = NukeTheme.sp(ctx, 11f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(2), 0, dp(2))
                    },
                )
            }
            /* v9.0.1: 三行联系方式并成**一，*（用户明确要求「都在一，不用换行」）。
               
               改之，TG / QQ / 酷安 各占一整行 ——它们是页脚信息，不是内容。
               三整行把下面的说明文字和滚动区一路往下推。现在放进一个横向容器，
               每个之间留间距，整排只占一行高度。
               
               间距，marginStart 而不是靠空格拼接：空格宽度随宿主字体变化，不可控。*/
            run {
                val contactRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                var first = true
                fun contact(txt: String) {
                    contactRow.addView(
                        TextView(ctx).apply {
                            text = txt
                            setTextColor(CONTACT_RED)
                            textSize = NukeTheme.sp(ctx, 11f)
                            typeface = Typeface.DEFAULT_BOLD
                            maxLines = 1
                            setPadding(0, dp(2), 0, dp(2))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { if (!first) marginStart = dp(10) },
                    )
                    first = false
                }
                contact("TG\uff1aLUO1314AI")
                contact("QQ\uff1a628843841")
                contact("\u9177\u5b89\uff1a\u8a00\u65e00503")

                /* ★ v9.0.1 防溢出：整排外面套一层横向 ScrollView。
                   
                   【为什么需要】验证员用真实字体度量算过：三个联系方式加 2×10dp 间距
                   约 **300dp**，而面板内容宽在 360dp 屏上只有 **306dp**（306.4 − 32 内边距）
                   —— 只剩 **6.4dp** 余量。而在 320dp 的老小屏上只有 269dp，
                   **直接溢出 31dp**。
                   
                   更要紧的是：横向 LinearLayout 在宽度不足时**不会换行、不会滚动**，
                   它会把最后一个子 View 推出右边界**直接裁掉** ——
                   也就是说小屏上「酷安：言无0503」会缺一半，而且用户毫无办法。
                   本文件此前 `HorizontalScrollView` / `clipChildren` / `setHorizontallyScrolling`
                   一个都没有，是真实的缺口。
                   
                   【为什么选横向滚动而不是别的】
                     · 缩小字号：会破坏用户「都在一排」里隐含的"看得清"诉求，且同样治不了极端小屏；
                     · 三个 View 用 weight=1：会把文字压到换行或省略号，信息仍然看不全；
                     · 横向滚动：**字号不变、不换行、小屏上划一下能看全** ——
                       唯一不牺牲内容的做法。
                   
                   【观感】关掉滚动条（isHorizontalScrollBarEnabled = false）：
                   内容放得下时它和普通一行毫无区别，放不下时才悄悄可滑。
                   这正是「不降级」的兜底 —— 大屏无感，小屏保命。 */
                val contactScroller = android.widget.HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    /* 不裁剪：确保子 View 的 padding 与文字边缘完整 */
                    clipToPadding = false
                    addView(contactRow)
                }
                p.addView(contactScroller)
            }
            /* ★ v9.0.1 用户要求：移除原本紧跟其后的两行说明文字 —
                 「建议常看更新 目前开发阶段更新较快」
                 「有更新内容需求请联系开发者<无更新内容 更新速度看情况>」
               它们是页脚宣传语，用户明确要求删掉，让面板更紧凑。
               删掉后联系方式行下面直接就是用法提示与版本排。 */
            line(
                if (mode == MODE_LIST) {
                    "\u957f\u6309\u4fe1\u606f \u2192 \u70b9\u300c\u5206\u6790\u300d\u5373\u51fa\u73b0\u5728\u8fd9\u91cc"
                } else {
                    "\u5361\u7247\u53ef\u4e0a\u4e0b\u6ed1\u52a8"
                },
                NukeTheme.TEXT_DIM,
            )
            /* v9.0.1: 顺序按用户给的排，——卡片模式下「本次用量」在版本，*之前**
               （列表模式下这一行是「请选择一条记录查看卡片」，同样留在原位）。*/
            val usageTv = TextView(ctx).apply {
                text = if (mode == MODE_LIST) {
                    "\u8bf7\u9009\u62e9\u4e00\u6761\u8bb0\u5f55\u67e5\u770b\u5361\u7247"
                } else {
                    "\u672c\u6b21\u7528\u91cf\uff1a" +
                        usageFor(selected).ifBlank { "\u6682\u65e0\u6570\u636e" }
                }
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 12f)
                typeface = Typeface.DEFAULT_BOLD
                /* ★ v9.0.1 用户第 5 项：这一行与版本排之间的间距收小。
                   原来是 (0, 6, 0, 2)；改为 (0, 2, 0, 0) ——
                       · 上边距 6→2：与上方用法提示贴近，不再留空档
                       · 下边距 2→0：与版本排之间**不留内边距**，
                         两行之间只靠 TextView 自身的行高自然分开
                   为什么要连上边距一起改：用户说的是「距离太远」，
                   而这是三行（用法提示 / 本行 / 版本排）连在一起的视觉段，
                   只压下边距会让它贴住版本排、却与上面脱开，观感更碎。 */
                setPadding(0, dp(2), 0, 0)
                includeFontPadding = false
            }
            /* ★ v10.3.0：记下这一行，让 [setUsage] 能就地改写它。
               注意必须在 addView 之前赋值吗？不 —— 只要在 addView 之后 parent 才会非空，
               而 setUsage 判断的正是 `parent != null`，所以这里紧跟创建处赋值最稳妥：
               万一后续 addView 抛出异常，引用虽在但 parent 仍为 null，setUsage 会自动回退到重画。 */
            usageLine = usageTv

            // ---- v9.0.1: 版本，——当前版本 / 最新版，/ 点我更新，同一。
            //
            // 用户要求：「当前版，XX 最新版，XX 点我更新《都在一，点我更新是字不是按钮
            //            点击，触发更新检测的UI》。
            //
            // 【「点我更新」为什么是 link 而不是按钮】用户明确要求「是字不是按钮」，
            // ，NukeTheme.link 正是本模块的纯文字可点控件（无背景、无描边、只有强调色文字），
            // 与按钮的视觉层级天然区分。
            //
            // 【它为什么是个新功能】改之前，用，*没有任何主动检查更新的入口** ——
            // 更新检测只藏在那条，60 秒一次的人数上报心跳里，用户想查也查不了。
            // 现在点它就会真的向服务端查一次（UsageReporter.checkUpdateNow）。
            /* ★ v9.0.1 用户第 1+2 项：「最新版本」一栏读**缓存**，并按是否一致上色。
               
               【第 1 项：为什么读缓存】用户要求「每 60 秒/第一次加载模块时获取一次最新版本，
               记录到缓存，分析记录与分析卡片的最新版本采用读取缓存」。
               这很对：render() 每次打开面板、每次重绘都会跑，在里面发网络请求是灾难，
               而版本号几乎不变，缓存完全够用。缓存的刷新由 UsageReporter 的
               60 秒上报心跳顺带完成（复用同一个请求，不新增网络目标）。
               `cachedLatest()` 是纯内存读取，不联网、不阻塞。
               
               【第 2 项：颜色语义】用户要求「如果版本一致 显示灰色的字 如果不一致 显示红色的」。
                 · 一致   → TEXT_DIM（灰）
                 · 不一致 → DANGER_TEXT（红，与联系方式同一个警示红 token）
                 · 还没取到（空串）→ 显示 `--`，灰
               
               判定用 UsageReporter.versionsDiffer()，它与后台/手动检查共用同一套规则
               （用户第 7 项：只要与本机版本不一致就算有更新，不比大小）。 */
            val cachedLatest = try {
                com.luoliaoo.wechat.data.UsageReporter.cachedLatest()
            } catch (_: Throwable) {
                ""
            }
            val latestDiffers = try {
                com.luoliaoo.wechat.data.UsageReporter.versionsDiffer(cachedLatest)
            } catch (_: Throwable) {
                false
            }
            val latestTv = TextView(ctx).apply {
                text = if (cachedLatest.isBlank()) {
                    "\u6700\u65b0\u7248\u672c\uff1a--"
                } else {
                    "\u6700\u65b0\u7248\u672c\uff1a" + cachedLatest
                }
                setTextColor(if (latestDiffers) NukeTheme.DANGER_TEXT else NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            /* 用数组持有自身引用：link 的点击回调在**创建，*就要绑定好，
               而那时代码还没执行到「把这个 View 赋给某个变量」这一步，
               局部变量在闭包里直接自引用也无法通过编译。
               数组是这里最直白的解法（本文件别处也用同样的 intArrayOf 手法）。*/
            val updateLinkRef = arrayOfNulls<TextView>(1)
            val updateLink = NukeTheme.link(ctx, "\u70b9\u6211\u66f4\u65b0") {
                /* 防连点：点击后立刻禁用，回调里再恢复。
                   用户确认过的交互是「先显示检测中，再出结果」。*/
                val tv = latestTv
                val self = updateLinkRef[0]
                tv.text = "\u6700\u65b0\u7248\u672c\uff1a\u68c0\u6d4b\u4e2d\u2026"
                tv.setTextColor(NukeTheme.TEXT_DIM)
                self?.isEnabled = false
                self?.alpha = 0.5f
                try {
                    com.luoliaoo.wechat.data.UsageReporter.checkUpdateNow { result ->
                        /* 回调已在主线程（UsageReporter 内部做了 Handler 切线程）。*/
                        try {
                            self?.isEnabled = true
                            self?.alpha = 1f
                            when (result.status) {
                                "update" -> {
                                    tv.text = "\u6700\u65b0\u7248\u672c\uff1a" + result.latest
                                    tv.setTextColor(NukeTheme.ACCENT)
                                    val shown = com.luoliaoo.wechat.ui.UpdateDialog
                                        .showForManualCheck(
                                            currentHostActivity(),
                                            result.latest,
                                            result.url,
                                            result.whatsNew,
                                            result.force,
                                        )
                                    if (!shown) {
                                        /* ★★ v9.9.9：这段提示原来**只归因于「服务端没配下载地址」**，
                                           而真机排查证明那个归因是错的方向 —— 它把我自己也骗了。

                                           【踩过的坑】实测日志是：
                                             update-scope: host is not in the foreground; not prompting
                                           也就是弹窗是被**我们自己的作用域守卫**拦下的，
                                           跟服务端有没有配 url 毫无关系。但用户（和我）
                                           看到的是「尚未提供下载地址」，于是往服务端查了半天，
                                           而服务端其实一切正常。

                                           真正的根因是另一个 bug（读 ActivityThread.mActivities 时
                                           误按 HashMap 转型，Android 17 上实际是 ArrayMap，
                                           导致永远取不到 Activity / 前台判定失效）。已在 v9.9.9 修掉。

                                           【改法】`shown == false` 只说明「没显示出来」，
                                           它**不足以**断定原因。所以现在按**有没有下载地址**分两种话说：
                                             · 有地址却显示不出来 -> 说「显示失败」，别赖服务端
                                             · 真的没地址         -> 才说「尚未提供下载地址」
                                           归因准确比句子好看重要得多：错误的信息会让排查
                                           往完全错误的方向走，这比不说还糟。 */
                                        val hasUrl = result.url.isNotBlank()
                                        val msg = if (hasUrl) {
                                            "\u670d\u52a1\u5668\u7248\u672c\uff1a" + result.latest +
                                                "\uff0c\u4f46\u5f39\u7a97\u672a\u80fd\u663e\u793a"
                                        } else {
                                            "\u670d\u52a1\u5668\u7248\u672c\uff1a" + result.latest +
                                                "\uff0c\u4e0e\u672c\u673a\u4e0d\u4e00\u81f4" +
                                                "\uff0c\u4f46\u5c1a\u672a\u63d0\u4f9b\u4e0b\u8f7d\u5730\u5740"
                                        }
                                        android.widget.Toast.makeText(
                                            ctx, msg, android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                                "latest" -> {
                                    /* v9.0.1（用户第 2 项）：与初始渲染用**同一套**显示规则 ——
                                       显示服务端版本号，按「与本机是否一致」上色。
                                       
                                       这里不再使用「隐藏服务端版本号」那种特殊处理：
                                       既然颜色本身已经表达了「一致（灰）/ 不一致（红）」，
                                       再藏起数字就是两套逻辑，反而容易自相矛盾。
                                       
                                       注意：判定用 versionsDiffer()（字符串是否相等），
                                       与用户第 7 项的规则同源，**没有任何比大小的逻辑**。 */
                                    val differs = com.luoliaoo.wechat.data.UsageReporter
                                        .versionsDiffer(result.latest)
                                    tv.text = if (result.latest.isBlank()) {
                                        "\u6700\u65b0\u7248\u672c\uff1a--"
                                    } else {
                                        "\u6700\u65b0\u7248\u672c\uff1a" + result.latest
                                    }
                                    tv.setTextColor(
                                        if (differs) {
                                            NukeTheme.DANGER_TEXT
                                        } else {
                                            NukeTheme.TEXT_DIM
                                        },
                                    )
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "\u5df2\u662f\u6700\u65b0\u7248\u672c",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                else -> {
                                    tv.text = "\u6700\u65b0\u7248\u672c\uff1a--"
                                    tv.setTextColor(NukeTheme.TEXT_DIM)
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "\u68c0\u6d4b\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } catch (t: Throwable) {
                            /* 回调体绝不能让异常冒出去 ——这是宿主进程。*/
                            Log.w(TAG, "floating: manual update callback failed: " + t.message)
                        }
                    }
                } catch (t: Throwable) {
                    self?.isEnabled = true
                    self?.alpha = 1f
                    tv.text = "\u6700\u65b0\u7248\u672c\uff1a--"
                    Log.w(TAG, "floating: manual update check failed: " + t.message)
                }
            }
            updateLinkRef[0] = updateLink

            val versionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                /* ★ v9.0.1 用户第 5 项：版本排与下方列表的间距收小。
                   原来是 (0, 2, 0, 8)；改为 (0, 0, 0, 4) ——
                       · 上边距 2→0：紧贴上面的「本次用量 / 请选择一条记录」行
                       · 下边距 8→4：与下方滚动区之间从 8dp 减到 4dp
                   用户原话是「版本与列表距离也有点远 请修复 弄紧凑点」，
                   8dp 的面板内边距加上滚动区自己的首元素上边距，观感上就是一段空档。 */
                setPadding(0, 0, 0, dp(4))
            }
            versionRow.addView(
                TextView(ctx).apply {
                    text = "\u5f53\u524d\u7248\u672c\uff1a" + com.luoliaoo.wechat.BuildInfo.VERSION_NAME
                    setTextColor(NukeTheme.ACCENT)
                    textSize = NukeTheme.sp(ctx, 11f)
                    maxLines = 1
                },
            )
            versionRow.addView(
                latestTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(10) },
            )
            versionRow.addView(
                updateLink,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(10) },
            )

            p.addView(usageTv)
            p.addView(versionRow)

            // ---- body, inside a height-capped ScrollView so the panel never exceeds the screen
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            if (mode == MODE_LIST) renderList(ctx, body) else renderCard(ctx, body)

            // v3.1.1 (t3): THE CAP IS A BUDGET INSIDE THE PANEL, NOT A FRACTION OF THE SCREEN.
            //
            // The previous version set the cap to 82% of the raw display height and treated that as the
            // PANEL's budget. But the ScrollView is only the LAST child of the panel: above it sit the header
            // row, the balloon-alpha row, five contact lines, the usage line and the version line, and the
            // whole panel is pushed down by topMargin(90dp). The panel therefore measured
            //     dp(90) + <fixed rows> + 0.82 * screen
            // which is taller than the display, so the BOTTOM of the ScrollView -- and with it the last part of
            // the card -- was laid out BELOW the screen edge. No drag amount can reach it, which is exactly the
            // reported "分析卡片无法滑动到最底部".
            //
            // The cap must therefore be what is LEFT of the display after everything that is not the scroller
            // has taken its share. We measure the fixed part from the panel itself (after the rows above were
            // added and the last row added below is included by the caller) and subtract the real paddings.
            //
            // Every number is derived, nothing is hard-coded: a large font scale or a tall phone simply shifts
            // the budget, instead of silently clipping the tail of the card.
            val panelLp = p.layoutParams as? FrameLayout.LayoutParams
            val panelTopMargin = panelLp?.topMargin ?: 0
            val panelVPadding = p.paddingTop + p.paddingBottom
            // 12dp of breathing room at the very bottom so the last line is not flush with the screen edge.
            val bottomGap = dp(12)
            val screenH = try {
                ctx.resources.displayMetrics.heightPixels
            } catch (_: Throwable) {
                0
            }
            // t3 复核修正：contentW 的兜底与面板宽度上限共用同一个比例，避免两处漂移。
            val screenW = try {
                ctx.resources.displayMetrics.widthPixels
            } catch (_: Throwable) {
                0
            }
            // Usable height for the whole panel = display minus where the panel starts minus its own frame.
            val panelBudget = if (screenH > 0) {
                screenH - panelTopMargin - panelVPadding - bottomGap
            } else {
                dp(560)
            }
            // The fixed part = everything already inside the panel that is NOT the scroller. `p` currently
            // holds exactly those rows, so measuring it now (the scroller has not been added yet) gives the
            // height of that stack -- including any row the caller adds after this point, because the
            // measurement happens right here, at the end of the fixed rows.
            //
            // The width spec must be the panel's REAL content width, otherwise wrapping TextViews report a
            // height for a wrong width and the subtraction is off. `p.width` is 0 before the first layout, so
            // the configured panel width is used.
            //
            // t3 复核修正：这里必须减掉面板自身的左右内边距。lp.width 是面板的**外框**宽度，，TextView
            // 实际排版可用的是「外，- paddingLeft - paddingRight」。不减掉的话测量宽度偏大，文字换行变少，
            // fixedH 就会偏小，maxH 随之偏大 —— 面板底部重新溢出屏幕，等于原 bug 没修掉。
            // lp.width ，WRAP_CONTENT(-2) ，0 时不能直接当像素用，此时退回屏幕宽度的 94%（与
            // panelWidthPx 的上限保持一致），而不是退化成 1px（那会让所有文字疯狂换行，fixedH 爆大）。
            val rawW = panelLp?.width ?: 0
            val contentW = if (rawW > 0) {
                (rawW - p.paddingLeft - p.paddingRight).coerceAtLeast(1)
            } else {
                (screenW * 0.94f).toInt().coerceAtLeast(1)
            }
            val wSpec = View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(panelBudget, View.MeasureSpec.AT_MOST)
            p.measure(wSpec, hSpec)
            // measuredHeight of the panel includes its own vertical padding; panelVPadding was already
            // subtracted from panelBudget, so remove it here too to keep the arithmetic consistent.
            val fixedH = (p.measuredHeight - panelVPadding).coerceAtLeast(0)
            // Whatever is left is the scroller's budget. The floor is small on purpose: it is far better to
            // show a couple of lines and still be able to scroll to the end than to show more and clip it.
            val minBody = dp(120)
            var maxH = (panelBudget - fixedH).coerceAtLeast(minBody)

            // v3.1.0（captain 复核补丁）：让「最大高度」设置重新生效。
            //
            // 【问题】t3 把存储值弃用、改成「屏幕说了算」之后，panelMaxHeightDp 变成，*死设，*。
            // 用户在悬浮窗控制里改，，存进 prefs ，mirror ，但渲染时根本没人读它 ，界面永远不变。
            // 这正好是需，4 要消灭的「改了不生效」。
            //
            // 【修法】把它作，*上限**而不是替代值：屏幕能给的额度（panelBudget - fixedH）仍然是硬约束，
            // 用户设的值只能在这个额度之内进一步调小。这样两个目标同时成立：
            //   * t3 的修复不被破，——面板永远不可能超出屏幕，卡片尾部始终滑得到；
            //   * 设置重新有意，——用户想要一个更矮的面板时，改小就能立刻看到。
            // 用户把值设得比屏幕额度还大时不报错，静默按屏幕额度走（那才是设备真正给得起的）。
            val storedCap = storedCapPxOf(
                try {
                    com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx)
                } catch (_: Throwable) {
                    Config()
                },
                ctx,
            )
            val beforeStored = maxH
            if (storedCap in 1 until maxH) {
                maxH = storedCap.coerceAtLeast(minBody)
            }
            // 记下本次实际采用的上限，，onConfigGeometryChanged 判断「最大高度」设置是否变化。
            lastAppliedStoredCapPx = storedCap
            Log.i(
                TAG,
                "floating: body cap=" + maxH + "px (screen=" + screenH +
                    ", storedCap=" + storedCap + "px" +
                    ", screenBudget=" + beforeStored + "px" +
                    ", storedApplied=" + (maxH != beforeStored) +
                    ", topMargin=" + panelTopMargin + ", vPad=" + panelVPadding +
                    ", fixed=" + fixedH + ", budget=" + panelBudget + ")",
            )

            val scroll = MaxHeightScrollView(ctx, maxH).apply {
                isVerticalScrollBarEnabled = true
                addView(body)
                // v2.9.0 (item 1): WRAP_CONTENT here let the container be laid out TALLER than the cap, and the
                // overflow was then simply unreachable -- no amount of dragging could reach the bottom buttons.
                // A bounded height makes the ScrollView itself do the scrolling.
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    maxH,
                )
                tag = Constants.TAG_OWN_UI
                // v2.8.0 (item 3): a drag inside the body must scroll the BODY, never be swallowed by an
                // ancestor. Without this the panel can appear unable to scroll at all.
                // t3: the disallow is asserted on every event, not just ACTION_DOWN -- a parent that starts
                // intercepting after a few MOVE events would otherwise steal the gesture mid-scroll and the
                // card would stop short of its bottom.
                setOnTouchListener { v, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN ||
                        e.actionMasked == MotionEvent.ACTION_MOVE
                    ) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
            }
            p.addView(scroll)

            // t3: BELT AND BRACES. The cap above already fits the panel into the display, but the panel is
            // still WRAP_CONTENT, so an extreme font scale could push it past the bottom edge again and the
            // last rows would be unreachable exactly as before. Bounding the PANEL's own height to the same
            // budget makes the overflow structurally impossible: worst case the scroller shrinks, it can never
            // be laid out below the screen.
            //
            // LinearLayout is not a RelativeLayout, so it has no maximumHeight. Instead the panel is measured
            // with an AT_MOST budget: LinearLayout reports min(content, budget), and the difference is absorbed
            // by the scroller. Only when the content still insists on being taller is the height pinned.
            // WRAP_CONTENT remains the preferred height so the panel stays compact for a short card.
            panelLp?.let { lp ->
                val wExact = View.MeasureSpec.makeMeasureSpec(
                    lp.width.coerceAtLeast(1),
                    View.MeasureSpec.EXACTLY,
                )
                val hBounded = View.MeasureSpec.makeMeasureSpec(
                    panelBudget,
                    View.MeasureSpec.AT_MOST,
                )
                p.measure(wExact, hBounded)
                lp.height = if (p.measuredHeight > panelBudget) {
                    panelBudget
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
                p.layoutParams = lp
            }

            currentScroll = scroll

            // v2.8.0 (item 3): RESTORE THE OFFSET ONLY FOR THE SAME CARD.
            //
            // Unconditionally restoring it is what left the card "displayed incompletely": a new, taller card
            // opened at an offset meant for the previous one, so the top was clipped and the bottom buttons
            // stayed out of reach. A card or mode CHANGE now starts at the top.
            val sameCard = (lastRenderedKey == selected) && (lastRenderedMode == mode)
            lastRenderedKey = selected
            lastRenderedMode = mode
            if (sameCard && panelScrollY > 0) {
                scroll.post { scroll.scrollTo(0, panelScrollY) }
            } else {
                panelScrollY = 0
                scroll.post { scroll.scrollTo(0, 0) }
            }
            OwnUi.register(p, scroll)
        } catch (t: Throwable) {
            Log.e(TAG, "floating: render failed", t)
        }
    }

    /**
     * item 2: the record list.
     *
     * Each row is exactly:
     *     the long-pressed message, at most 10 characters (then an ellipsis)
     *     本次用量：XX
     *     the Beijing time
     * with 删除 on the right.
     */
    private fun renderList(ctx: Context, into: LinearLayout) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        /* t3 注：「手动」按钮**不在**这里，而在标题栏里（见 render() 中 head 的列表模式分支，
           位于「官网」之前）。这里曾短暂放过一个全宽版本，现已按用户澄清的位置要求移回标题栏。
           移回的前提是把标题栏按钮从 56dp 收窄到 44dp，
           使 5 个按钮占用的横向空间（5×48=240dp）与原来 4 个按钮（4×60=240dp）持平，
           于是标题空间不变（66dp）、标题不会被截断 —— 完整推算见 HEAD_BTN_W_DP 的注释。 */

        val all = try {
            listProvider?.invoke() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "floating: listProvider failed: " + t.message)
            emptyList()
        }
        // item 2 (earlier): only the chat currently open.
        val items = if (currentTalker.isBlank()) all else all.filter { it.talker == currentTalker }

        if (items.isEmpty()) {
            into.addView(
                NukeTheme.body(
                    ctx,
                    if (all.isEmpty()) {
                        // 5.0.0: 「在微信里长按…」 -> 在抖音里长按… inside Douyin.
                        com.luoliaoo.wechat.core.HostApp.say(
                            "\u8fd8\u6ca1\u6709\u5206\u6790\u8bb0\u5f55\u3002\u5728\u5fae\u4fe1\u91cc" +
                                "\u957f\u6309\u4e00\u6761\u6d88\u606f\uff0c\u70b9\u300c\u5206\u6790" +
                                "\u300d\u540e\u5c31\u4f1a\u51fa\u73b0\u5728\u8fd9\u91cc\u3002",
                        )
                    } else {
                        "\u5f53\u524d\u804a\u5929\u8fd8\u6ca1\u6709\u5206\u6790\u8bb0\u5f55\u3002"
                    },
                    NukeTheme.TEXT_MUTED,
                    13f,
                ),
            )
            return
        }

        for (e in items) {
            val row = NukeTheme.sectionCard(ctx).apply {
                isClickable = true
                setOnClickListener {
                    selected = e.key
                    mode = MODE_CARD
                    render()
                }
            }

            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    // line 1: the message, at most 10 characters
                    addView(
                        TextView(ctx).apply {
                            text = if (e.title.isBlank()) "\u5206\u6790" else clip(e.title, 10)
                            setTextColor(NukeTheme.TEXT)
                            textSize = NukeTheme.sp(ctx, 15f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                    // line 2: the usage
                    addView(
                        TextView(ctx).apply {
                            text = "\u672c\u6b21\u7528\u91cf\uff1a" +
                                usageFor(e.key).ifBlank {
                                    if (e.usage.isNotBlank()) e.usage else "\u6682\u65e0"
                                }
                            setTextColor(NukeTheme.TEXT_MUTED)
                            textSize = NukeTheme.sp(ctx, 12f)
                            maxLines = 1
                            setPadding(0, dp(4), 0, 0)
                        },
                    )
                    // line 3: the Beijing time
                    addView(
                        TextView(ctx).apply {
                            text = formatTime(e.time)
                            setTextColor(NukeTheme.TEXT_DIM)
                            textSize = NukeTheme.sp(ctx, 11f)
                            setPadding(0, dp(3), 0, 0)
                        },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )

            val del = TextView(ctx).apply {
                text = "\u5220\u9664"
                setTextColor(NukeTheme.DANGER_TEXT)
                textSize = NukeTheme.sp(ctx, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                // t5：删除按钮的底色原来是硬编码，0x22FF6B81，3% 红），与 NukeTheme 。
                // 危险风格同义但不同源。改，NukeTheme.DANGER_BG ——它与 errorBox() 用的。
                // 同一，token（危险底 + DANGER_BORDER 描边 + DANGER_TEXT 文字），
                // 所以卡片列表里的删除钮和面板的错误框现在是同一套危险样式（铁律 1）。
                // 圆角 8 -> R_CHIP(10)：与 errorBox() 的小方块圆角统一，不再硬编码数字。
                background = NukeTheme.rounded(
                    NukeTheme.DANGER_BG,
                    NukeTheme.R_CHIP,
                    ctx,
                    NukeTheme.DANGER_BORDER,
                )
                setPadding(dp(10), dp(6), dp(10), dp(6))
                // t5 无障碍：这个「删除」小按钮原来的有效点击区，44x28dp，低，Android
                // 建议，48dp 最小触控目标（无障碍审，t6 指出全项，setMinimumHeight 零命中）。
                // 这里只补「最小触控尺寸」，不改它的位置、更不改悬浮，面板的任何尺寸。
                //
                // 【为什么撑大它是安全的，且不会动到面板几何】这一行在 renderList() 里被加进
                // body，，body ，*最，*才放，MaxHeightScrollView 的；render() 计算固定高度
                // fixedH（也就是 maxH 预算里要扣掉的那部分）时，量的是**尚未加入滚动区的面板**。
                // 所以列表内容再高再矮都不参，maxH 的计算，只在滚动区内部滚动。
                // 换言之：这一行最多长高几，dp（默认字级下，45dp ，48dp），
                // 面板宽度、高度上限、悬浮球尺寸与位置全部不变。
                minimumHeight = dp(48)
                minimumWidth = dp(48)
                isClickable = true
                setOnClickListener {
                    Log.i(TAG, "floating: deleting the record " + e.key)
                    try {
                        onDelete?.invoke(e.key)
                    } catch (t: Throwable) {
                        Log.w(TAG, "floating: delete failed: " + t.message)
                    }
                    render()
                }
            }
            head.addView(del)
            row.addView(head)
            into.addView(row)
        }
    }

    private fun renderCard(ctx: Context, into: LinearLayout) {
        val key = selected
        if (key == null) {
            into.addView(NukeTheme.body(ctx, "\u672a\u9009\u62e9\u8bb0\u5f55", NukeTheme.TEXT_MUTED, 13f))
            return
        }
        val data = try {
            cardProvider?.invoke(key)
        } catch (t: Throwable) {
            Log.w(TAG, "floating: cardProvider failed: " + t.message)
            null
        }
        if (data == null) {
            into.addView(
                NukeTheme.body(
                    ctx,
                    "\u8be5\u8bb0\u5f55\u5df2\u4e0d\u5b58\u5728\uff0c\u8bf7\u91cd\u9009",
                    NukeTheme.TEXT_MUTED,
                    13f,
                ),
            )
            return
        }
        try {
            val card = AnalysisCard(ctx)
            card.ownerKey = key
            card.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            card.render(
                data.state,
                data.turns,
                onReanalyze = { onReanalyze?.invoke(key) },
                onFollowUp = { q -> onFollowUp?.invoke(key, q) },
                onDelete = {
                    onDelete?.invoke(key)
                    showList()
                },
                onEdit = { t -> onEdit?.invoke(key, t) },
            )
            into.addView(card)
            /* ★ v10.3.0：记下当前**真正在屏幕上**的这张卡片，供流式追问的快路径就地改写文字。
               为什么必须在这里记：卡片是每次 render 时 new 出来的（见上一行），
               所以只有本函数知道"这一刻哪一张是活的"。
               WHY 不能去微信消息列表里找：卡片早已改成 overlay 方案，**根本不挂在微信的
               RecyclerView 行里**（见 CardRenderer 的注释），在那边找必然是 null ——
               这正是"流式一个字都不显示、只在最后一刻闪一下"的原因。
               注意这个引用只用于**快路径**；失效时 [streamText] 会自动回退到完整重画，
               所以即使它偶尔指向一张刚被替换掉的卡片，也只会多一次重画，不会丢内容。 */
            liveCard = card
        } catch (t: Throwable) {
            Log.e(TAG, "floating: rendering the card failed", t)
            into.addView(
                NukeTheme.errorBox(
                    ctx,
                    "\u5361\u7247\u6e32\u67d3\u5931\u8d25\uff1a" + (t.message ?: t.javaClass.simpleName),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /** item 2: at most `max` characters, with an ellipsis when it had to cut. */
    private fun clip(s: String, max: Int): String {
        val t = s.replace("\n", " ").trim()
        return if (t.length <= max) t else t.take(max) + "\u2026"
    }

    /**
     * item 2: BEIJING time, explicitly.
     *
     * The device may be in any time zone, so the zone is pinned to Asia/Shanghai rather than relying on
     * the phone's setting.
     */
    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        return try {
            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault(),
            )
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            sdf.format(java.util.Date(ms))
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * The Activity behind a Context.
     *
     * Walks the full ContextWrapper chain (which handles ContextThemeWrapper, used by WeChat's chat
     * screen), and then tries reflection for a `getActivity()`-style accessor, because some contexts
     * expose their Activity that way instead of through baseContext.
     */
    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        var d = 0
        while (c is ContextWrapper && d < 12) {
            if (c is Activity) return c
            c = c.baseContext
            d++
        }
        // Reflection fallback: mBase / getActivity style accessors.
        var cur: Any? = ctx
        var k = 0
        while (cur != null && k < 6) {
            for (name in arrayOf("getActivity", "getHost")) {
                try {
                    val m = cur.javaClass.getMethod(name)
                    val r = m.invoke(cur)
                    if (r is Activity) return r
                } catch (_: Throwable) {
                    // not that accessor
                }
            }
            val here: Any = cur
            for (fn in arrayOf("mActivity", "mHost", "mContext")) {
                try {
                    val fd = here.javaClass.getDeclaredField(fn)
                    fd.isAccessible = true
                    val r = fd.get(here)
                    if (r is Activity) return r
                    if (r is Context && r !== cur) {
                        cur = r
                        break
                    }
                } catch (_: Throwable) {
                    // no such field
                }
            }
            cur = (cur as? ContextWrapper)?.baseContext
            k++
        }
        return null
    }

    /**
     * A compact but complete description of a throwable.
     *
     * The LSPosed export only renders the message text, so a bare Log.e(tag, "attach failed", t) tells
     * us nothing. The exception class, its message, our own stack frames and the cause all go into the
     * TEXT, so the next log names the failure.
     */
    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        sb.append(t.javaClass.name)
        if (!t.message.isNullOrBlank()) sb.append(": ").append(t.message)
        var n = 0
        for (e in t.stackTrace) {
            if (e.className.contains("luoliaoo") || n < 4) {
                sb.append(" | ").append(e.className.substringAfterLast('.')).append(':')
                    .append(e.lineNumber)
                n++
            }
            if (n >= 8) break
        }
        val cause = t.cause
        if (cause != null && cause !== t) {
            sb.append(" || caused by ").append(cause.javaClass.name)
            if (!cause.message.isNullOrBlank()) sb.append(": ").append(cause.message)
        }
        return sb.toString()
    }
}
