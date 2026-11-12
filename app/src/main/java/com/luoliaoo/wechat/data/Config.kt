package com.luoliaoo.wechat.data

import android.content.Context
import android.content.SharedPreferences
import com.luoliaoo.wechat.core.Log
import java.io.File

/**
 * ★ v10.3.0：追问流式输出的打字机速度。
 *
 * 单独放一个 object 而不是把三个数字写进 [Config] 的默认值里，是因为**夹取逻辑必须只有一份**：
 * 设置界面（拖动滑杆）、配置镜像读回、以及真正做节流的 `AnalysisController` 三处都要夹取，
 * 散落的字面量迟早会出现"界面允许 500、运行时夹到 300"这种两套边界。
 */
object StreamSpeed {

    /**
     * 0 = 关闭打字机（收到即显示）。
     *
     * ⚠️ 它是一个**独立档位**，不是"最慢档"。理清这一点很重要，因为它的语义与数字大小**相反**：
     * 数字越大越慢（1 字/秒 ≈ 每秒一个字），而 0 是**最快**的（整段直接显示）。
     * 用户要求"最低 0"，指的是**取值范围的下界是 0**，不是"0 最慢"。
     *
     * 夹取时必须**先把 0 放行**，否则 `coerceIn(MIN, MAX)` 会把 0 顶成 1，
     * "不限速"这个选项就永远选不到了。
     */
    const val OFF = 0

    /** 最慢档：1 字/秒。 */
    const val MIN = 1

    /**
     * 默认值：**15 字/秒**（用户指定）。
     *
     * 逐字效果清晰可见，而一段 200 字的回答约 13 秒出完，不用等太久。
     */
    const val DEFAULT = 15

    /**
     * ★ 上界**已取消**（用户要求「移除上限20 改成无上限」）。
     *
     * ─────────────────────────────────────────────────────────────────────────
     * 【先说清楚一件事实，避免用户误解这个改动的效果】
     * 打字机只能**放慢**显示，**不能加快**。文字到达速度的上限是模型和网络的产出速度；
     * 当设定值高到超过它时，打字机每一拍都能追平可用文本，表现就与「0（不限速）」完全一样。
     * 所以把上限从 20 提到无穷大，**大多数情况下观感不会有变化** ——
     * 真正会让用户"想调更快"的场景是设定值**偏低**（例如他想让 20 更快一点，
     * 而 20 之上其实已经没有可加快的余量了）。
     * 保留无上限仍然有意义的场合：模型产出极快（例如本地小模型、或极短回答），
     * 此时高设定值确实能少等几拍。
     *
     * 【为什么仍然需要一个"存储上界"】
     * 不是为了限制用户，而是为了**算术安全**：速度值会参与 `已过毫秒 × 速度` 的乘法，
     * 若放任任意大，这个乘法会溢出 Int/Long，导致"算出来的应显示字数"变成**负数或乱值**，
     * 表现是文字乱跳、倒退、甚至整段瞬间消失 —— 那是比"太慢"严重得多的 bug。
     * 所以取值放宽到 Int 能表示的范围（21 亿字/秒，实际上等于"瞬时全显"），
     * 而在 [AnalysisController] 的计时里改为**Long 运算 + 先比较后转换**，从根上消除溢出。
     */
    const val MAX: Int = Int.MAX_VALUE

    /**
     * 唯一合法的夹取入口。
     *
     * 先把 [OFF] 当作合法的特殊值放行，再对其余值夹到 [MIN]..[MAX]。
     * WHY 不能直接 `coerceIn(MIN, MAX)`：那样 0 会被顶成 1，用户就再也选不到"不限速"了。
     */
    fun clamp(v: Int): Int {
        if (v <= OFF) return OFF
        return v.coerceIn(MIN, MAX)
    }

    /**
     * 从用户输入解析速度值。[text] 为 null、空、或非数字时返回 null（表示"这次不改动"）。
     *
     * WHY 用 Long 解析、再夹到 Int 范围：输入框现在是**无上限**的，
     * 用户完全可能打出一个超过 Int 的大数（例如 99999999999）。
     * 若用 `toIntOrNull()` 直接解析，那种输入会返回 null，
     * 界面只能报「只能输入数字」—— 但用户输入的确是数字，这个提示就会让人一头雾水。
     * 先按 Long 解析、超界再夹到 [MAX]，语义是"你要的最快，我就给你最快"。
     */
    fun parse(text: String?): Int? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val asLong = raw.toLongOrNull() ?: return null
        if (asLong <= OFF) return OFF
        if (asLong >= MAX.toLong()) return MAX
        return asLong.toInt().coerceAtLeast(MIN)
    }

    /**
     * 每 [cps] 个字符需要多少毫秒。0（关闭）返回 0，调用方据此走"直接显示"分支。
     *
     * 用 Double 计算再取整：整数除法下 `1000 / 15` 会得到 66，与真值 66.7 的差
     * 会让实际速度比标称慢一点点，累积到长回答上就看得出来。
     */
    fun intervalMs(cps: Int): Long {
        val v = clamp(cps)
        if (v == OFF) return 0L
        return Math.max(1L, Math.round(1000.0 / v))
    }
}

/**
 * Configuration.
 *
 * TWO storage scopes on purpose:
 *   * loadLocal(ctx) / saveLocal(ctx, c)  -- the CURRENT process's own prefs. Inside WeChat
 *     the writer and the reader are the same process, so this always works. Primary path.
 *   * loadRemote(ctx)             -- cross-process fallback for config typed in the
 *     module's own app.
 */
data class Config(
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-flash",

    /**
     * ★ v10.3.0：**识图模型** —— 专门用来看图的那个模型。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【为什么必须单独有一个"识图模型"，而不是直接用 [model]】
     *
     * 因为**绝大多数主力模型不支持视觉输入**。工程的默认模型就是 `deepseek-flash`，
     * 它是纯文本模型：把图片塞给它，轻则服务端忽略图片（用户以为在看图，其实没有），
     * 重则整个请求报错。而用户完全可能用任何模型（这也是本工程的设计前提）。
     *
     * 所以这里把"看图"和"分析"**拆成两次请求**，中间用文字当桥梁：
     *
     *     图片 ──[识图模型]──> 一段图片描述文字 ──[模板模型]──> 分析结果
     *
     * 【这个拆分解开了三个死结】
     *   1. 模板模型可以是任何模型，不必支持视觉 —— 它只收到文字，与看不了图无关；
     *   2. 识图结果**变成了文字**，所以能存进本地记录、能参与追问、能被重新分析，
     *      而"把 base64 图片存在卡片里"既存不下也没意义；
     *   3. 识图模型可以换。用户哪天换了更好的视觉模型，只改这一项，模板不用动。
     *
     * 【为什么三个字段要独立存，不复用模板的三项】
     * 识图模型常常与主力模型**不是同一家**（例如主力用 DeepSeek 做分析、
     * 识图用另一个支持视觉的服务）。共用一份配置会逼用户二选一。
     * 这三项留空时**回落到主配置**（见 [visionBaseUrl] 等），
     * 所以"我的模型本来就支持视觉"的用户什么都不用填也能用。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    val visionBaseUrl: String = "",

    /** 识图模型的 API Key，留空则回落用 [apiKey]。 */
    val visionApiKey: String = "",

    /**
     * 识图模型名，例如 `gpt-4o-mini` / `qwen-vl-plus` / `glm-4v`。
     *
     * **留空 = 关闭识图**（此时图片消息仍按 `[图片]` 占位符分析，与旧版行为一致）。
     * 用"留空关闭"而不是另加一个布尔开关，是因为它本身就是最诚实的表达：
     * 没有配识图模型，当然就识不了图。两个开关会出现"开了但没填模型"的自相矛盾状态。
     */
    val visionModel: String = "",

    /**
     * ★ v10.3.0：**视觉等级** —— 用哪一档提示词让识图模型看图。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【它到底改变什么：提示词的长度与要求，不是模型的能力】
     *
     * ⚠️ 这一点必须说清，否则用户会以为"高"就是换了个更强的模型 —— 不是。
     * 等级只改**提示词**（见 [VisionLevel]），模型本身由 [visionModel] 决定。
     * 之所以分等级，是因为识图的"详细程度"是一个真实存在的取舍：
     *
     *   * **低**：只要求"一句话说明这是什么"。适合"我只想知道对方发的是什么东西"，
     *     token 消耗最小，速度最快。缺点：看不清聊天截图里的具体文字。
     *   * **中**：要求描述主要内容 + 抄录图中**关键文字**。日常聊天的默认档。
     *   * **高**：要求逐字抄录全部文字、描述人物/场景/物品/情绪。适合分析聊天截图、
     *     需要模型读图里文字的场景。token 明显更贵。
     *   * **极其高**：逐字抄录 **+ 结构还原**（谁对谁说了什么、界面元素、时间戳、
     *     图片之间的关系）+ 明确区分"看到的"与"看不清的"。最贵，也最适合
     *     "图里信息很多、我需要完整上下文"的场景。
     *
     * 【为什么用"等级"而不是让用户自己写提示词】
     * 自己写提示词对绝大多数用户是负担（要懂怎么让视觉模型好好描述），
     * 而且很容易写出让模型开始**分析**而非**描述**的提示词 —— 那会污染主模型的判断
     * （见 `VisionClient.DESCRIPTION_PROMPT` 的说明）。用固定的四档，
     * 保证每一档都守住"只描述、不分析"这条底线。
     *
     * 【等级越高越贵，界面必须提示】这是用户的原话要求：
     * 「注：不同等级消耗 token 不一样请合理分配」。所以 UI 上必须把这句话写出来。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    val visionLevel: Int = VisionLevel.MEDIUM,

    val systemPrompt: String = DEFAULT_PROMPT,
    val defaultUp: Int = 3,
    val defaultDown: Int = 3,
    val defaultRangeHours: Int = 24,
    /**
     * Card width in CHINESE CHARACTERS (item 3). 0 or less is treated as the default.
     * Minimum 1, default 10, no upper limit -- the card simply follows the requested width.
     */
    val cardWidthChars: Int = 25,
    /** Card accent colour, ARGB (item 3). */
    val accentColor: Int = CardTheme.DEFAULT_ACCENT,
    /** Card body font size in sp. Kept for stored configs; the card no longer themes. */
    val fontSp: Float = CardTheme.DEFAULT_FONT_SP,
    /**
     * Floating panel width, in CHINESE CHARACTERS, measured with the card's own font metric.
     *
     * The card now lives in the floating window, so this controls the PANEL width (and therefore the
     * card's), not a width inside a message row.
     */
    val panelWidthChars: Int = 25,
    /** Maximum height of the floating panel's scrollable body, in dp. */
    val panelMaxHeightDp: Int = 420,
    /**
     * item 3: adapt the panel's text to the device's font scale.
     *
     * OFF uses the designed sizes verbatim; ON divides by the system font scale so a phone set to a very
     * large font does not blow the panel's text up until a title wraps one character per line.
     */
    /**
     * item 3: the floating balloon's TRANSPARENCY, in PERCENT (0 = opaque, 80 = the maximum).
     *
     * Stored as transparency rather than alpha because that is how the user asked for it; the view's alpha is
     * `1 - balloonAlpha / 100`. Clamped to 0..80 everywhere it is used, so a hand-edited value cannot make the
     * balloon invisible or un-clickable.
     */
    val balloonAlpha: Int = 0,

    /**
     * item NEW: the floating balloon's SIZE, as a percentage of its designed size (100 = as designed).
     *
     * THE USER ASKED FOR THIS BESIDE THE TRANSPARENCY CONTROL: both are properties of the same
     * balloon, and a slider is used for both so the two controls behave identically.
     *
     * Stored as a PERCENTAGE rather than a dp value, and that is deliberate: the balloon's designed
     * size is `BALLOON_DP` in `FloatingPanel`, and a percentage keeps this setting meaningful if that
     * design size is ever adjusted -- a stored absolute dp would silently pin the balloon to today's
     * number. It also keeps the config readable ("120%").
     *
     * The range is bounded on both sides, because this value is handed straight to a layout: too small
     * and the balloon cannot be tapped (it is the only way to open the panel), too large and it covers
     * the chat. 50..200 is wide enough to be useful and narrow enough that neither failure is reachable.
     */
    val balloonScale: Int = 100,

    /**
     * item NEW: whether the floating balloon is SHOWN at all.
     *
     * The long-press menu entry 落聊悬浮球开关 flips this value and it is persisted immediately, so the
     * choice survives a WeChat restart. TRUE is the historical behaviour (the balloon was always on), so
     * an install that never touched the switch looks exactly as it did before.
     *
     * The flag is honoured in FloatingPanel.attach(), which is the single funnel every caller goes
     * through -- the startup request, the watchdog's re-attach and followActivity included. Gating it
     * there is what stops the balloon from reappearing a second after it was switched off.
     */
    val balloonEnabled: Boolean = true,

    /**
     * ★ v9.3.0：悬浮球位置（屏幕坐标，px）。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么需要持久化】
     * 用户要求：「悬浮球固定位置 —— 重启应用后不再自动居中，而是原本位置」。
     *
     * 原实现把位置存在 `FloatingPanel` 的 `lockedScreenX/lockedScreenY` 两个**内存变量**里，
     * 进程一重启就归 -1，于是 `applyPosition` 走「没有锁定 → 死居中」分支
     * （见 FloatingPanel 第 749/756 行）。用户每次打开微信，辛苦拖到的位置都白费。
     *
     * ─────────────────────────────────────────────────────────────
     * 【为什么存 SCREEN 坐标，而不是「相对父容器的偏移」】
     * 宿主（微信/QQ/抖音…）的聊天内容区在切页面时会移动、伸缩，内容区自己的宽度高度都不是常量。
     * 如果存相对偏移，换一个内容区尺寸位置就偏了；存屏幕绝对坐标则每次都能反推出正确偏移
     * （`balloonX = lockedScreenX - parentLocationOnScreen[0]`），这正是原实现的思路，沿用即可。
     *
     * 【为什么用 -1 表示「未设置」而不是 0】
     * 0 是**合法坐标**（贴屏幕左/上边缘）。用 0 当哨兵会让「用户把球拖到左上角」
     * 在下次启动时被误判成「没设过」而重新居中 —— 这是个典型的哨兵值反噬。
     * 配置里存的是 int，-1 在真实屏幕坐标里不可能出现，因此是安全的哨兵。
     *
     * 【越界怎么办 —— 不在这里夹取】
     * 屏幕旋转、换分辨率、换设备（配置跟着备份走）都可能让旧坐标落在屏幕外。
     * 这里**不做**合法性判断，交给 `applyPosition` 的 `coerceIn` 处理 ——
     * 它本来就会把坐标夹进父容器范围。若在这里再夹一次，就会出现「两处各有一套边界规则」，
     * 将来改一处忘一处。坐标的边界知识只应该有一个来源。
     */
    val balloonPosX: Int = -1,
    val balloonPosY: Int = -1,

    val adaptiveFont: Boolean = true,
    /** item: keep the module's own debug log, so a problem can be packaged into one file. */
    val debugLog: Boolean = false,

    /**
     * ★ v10.0.0：**语音转文字**开关。
     *
     * 【这个开关做什么】开着时，微信里收到/看到一条语音消息，模块会调用**微信自带的转写引擎**
     * 把语音转成文字，并以 `[语音转文字]xxx` 的形式写进本地聊天记录（HistoryStore）。
     *
     * 【为什么默认开】用户提的需求就是"语音转文字写入本地"，默认关会让功能看起来没生效。
     *
     * 【为什么必须给一个关的入口】转写不是免费的：
     *   * 它会驱动微信向服务器发起一次语音识别请求（走微信自己的通道，模块不碰网络）；
     *   * 每条语音要轮询到转写完成，最多 30 秒；
     *   * 语音量大的人（尤其群聊）会持续产生这个开销。
     * 所以必须让用户能关掉，而不是塞给他一个无法关闭的后台行为。
     *
     * 【关掉后的确切行为】语音消息**既不入库也不触发转写** ——
     * 注意这意味着它的 XML 原文也不会进本地记录（见 AnalysisController.handleVoice 前的分流）。
     * 也就是说关掉开关 = 本地记录里完全没有语音这一行，这是刻意的：
     * 写入 `<voicemsg aeskey=... voiceurl=...>` 这种原文对用户毫无用处。
     */
    val voiceToText: Boolean = true,

    /**
     * ★ v10.3.0：**图片 / 表情包识别**的**全局默认值**，默认**关闭**（用户指定）。
     *
     * ⚠️【这个字段现在的角色变了，不要误读】
     * 识图已经改成**逐条消息**控制（在「读取」预览里每条消息的左侧各有一个开关）。
     * 所以本字段**不再是"整个功能的总开关"**，而是：
     *
     *     「新打开一条消息时，这个开关的初始状态」
     *
     * 也就是说它是**默认值**而不是**总闸**。用户逐条调整的结果不会写回这里 ——
     * 每个聊天、每条消息各自记各自的，见 [ImagePick]（存在 `CardStore` 的作用域里）。
     *
     * 【为什么默认关闭，而不是默认开启】
     * 这是用户明确指定的，而且这个默认值是对的，理由是**隐私**：
     * 识图意味着图片会**离开手机**、发到用户配置的 AI 服务端。默认开启等于
     * "用户只要分析一条图片消息，图片就自动发出去了" —— 用户没有做过任何同意的动作。
     * 默认关闭则相反：什么都不发，只有用户**主动为某条消息打开开关**才会发。
     *
     * 这也与 [voiceToText] 的取向形成有意对照，两者理由不同、结论自然不同：
     *   * 语音转文字的默认开启，是因为微信**本来就会**转写语音（模块只是抄一份结果），
     *     不额外产生任何数据外发；
     *   * 识图是**真的把用户数据送到第三方**，所以必须默认关、必须用户逐条同意。
     *
     * 【关掉时会发生什么】图片消息仍然**正常入库、正常分析**，
     * 只是在提示词里显示为 `[图片]` 占位符（与语音的 `[语音]` 占位符同一套处理）。
     * 不会因为关掉就让图片消息消失（那会破坏对话上下文的完整性）。
     *
     * 【图片与表情包共用一个开关】用户明确指定"共用一个开关"。
     * 技术上也确实只能共用：见 [WeChatImage] 的实测说明 ——
     * 图片（`image2/`）是明文可读的，而表情包（`emoji/`）是**加密**的，
     * 后者当前根本读不出来。所以实际生效的是图片那部分；表情包等将来找到读法后自动一并生效，
     * 不需要再加第二个开关。
     */
    val imageAnalysis: Boolean = false,

    /**
     * ★ v10.3.0：**追问 AI 的流式输出速度**（每秒绘制的字符数）。
     *
     * ─────────────────────────────────────────────────────────────
     * 【这个数字到底是什么，不是什么】
     * 它**不控制模型生成得多快** —— 那是服务端的事，客户端唯一能做的是"多快把已经收到的字画到屏幕上"。
     * 流式回答到达时往往是成批、突发的（一个 chunk 可能带来十几个字），如果每来一批就立刻整块贴上，
     * 观感是"抽搐式地一大段一大段蹦"，反而比一次性出全文更难受。
     *
     * 所以这里做的是**打字机节流**：收到的字符先进缓冲区，按本值给定的速率逐个吐到卡片上。
     *
     * ⚠️【方向：数字越大越慢，别搞反】本值是**每秒钟显示多少个字**（字/秒）。
     * 所以 5 比 20 慢，而 [StreamSpeed.OFF]（0）是**最快**的 —— 它关闭打字机、整段直接显示。
     * 这一点与直觉相反（数字大反而慢），是本字段最容易写错的地方：
     * 早期版本的注释就误写成"值越大出字越快"，与实现完全相反。
     *
     * ─────────────────────────────────────────────────────────────
     * 【默认 15】逐字效果清晰可见，一段 200 字的回答约 13 秒出完，不用等太久。
     * 0 有单独含义：见下。
     *
     * 【0 = 关闭打字机，收到多少直接显示多少】这是给"我只想快点看到结果"的用户的出口，
     * 也是服务端明明已经流完了、而我们还在慢慢吐字时唯一能立刻追上进度的档位。
     *
     * 【无上限】上界已经取消（用户要求）。但打字机只能**放慢**、不能加快 ——
     * 当设定值超过模型的产出速度时，每一拍都能追平可用文本，表现等同于 0（不限速）。
     * 取值仍受 Int 范围约束，那是为了防止计时算术溢出，不是为了限制用户。
     */
    val streamSpeed: Int = StreamSpeed.DEFAULT,
) {

    /** item 5: the model actually used, with the shipped default applied only when empty. */
    val effectiveModel: String get() = model.ifBlank { "deepseek-flash" }

    /** item 5: the base URL actually used, with the shipped default applied only when empty. */
    val effectiveBaseUrl: String get() = baseUrl.ifBlank { "https://api.deepseek.com" }

    /**
     * ★ v10.3.0：识图模型的三项解析值，**留空时回落到主配置**。
     *
     * 【为什么做成"回落"而不是"必须填"】
     * 有一类用户的主模型本来就支持视觉（例如直接用 `qwen-vl-plus` 当模板模型）。
     * 对他们来说，识图模型与主模型是同一个，逼他们再填三遍 URL 和 Key 是纯粹的负担。
     * 回落让"只填模型名、URL 与 Key 留空"自动复用主配置 —— 这正是那类用户想要的行为。
     */
    val visionBaseUrlResolved: String get() = visionBaseUrl.ifBlank { baseUrl }

    val visionApiKeyResolved: String get() = visionApiKey.ifBlank { apiKey }

    /**
     * 识图是否**真正**可用 —— 两个条件都满足才算。
     *
     * 【为什么是两个条件，不能合并成一个】
     *   * [imageAnalysis] 回答"用户**现在这条消息**要不要识图"（逐条开关，默认关闭）；
     *   * [visionModel] 回答"用哪个模型看"（能力配置，用户在模板管理里填）。
     *
     * ⚠️【"开了但没填模型"是一个真实存在的状态，必须给出可见反馈】
     * 用户完全可能先打开某条消息的识图开关，过一会儿才去填识图模型。
     * 这种状态**不能**静默当成"已开启"（图片发不出去却毫无提示，用户会以为功能坏了），
     * 也**不能**静默当成"已关闭"（那会悄悄推翻用户刚做的选择）。
     * 处理方式：按未配置处理（退化为纯文本分析），**同时在界面上明确提示去填模型**。
     * 见 `ScopeDialog` 里逐条开关旁的状态文字。
     */
    val visionEnabled: Boolean get() = imageAnalysis && visionModel.isNotBlank()

    /** Effective panel/card width in characters, never below 1. */
    val cardWidth: Int
        get() {
            val w = if (panelWidthChars > 0) panelWidthChars else cardWidthChars
            return if (w < 1) 1 else w
        }

    /** Appearance bundle handed to the card renderer. */
    fun cardTheme(): CardTheme = CardTheme(accent = accentColor, fontSp = fontSp)

    companion object {
        const val GROUP = "luoliao"
        const val DEFAULT_CARD_WIDTH = 25

        val DEFAULT_PROMPT: String = """你是对话意图分析专家。请分析用户提供的微信聊天记录，判断对方消息的真实意图。

严格只输出如下 JSON，不要输出任何其它文字、不要用 markdown 代码块包裹：
{
  "intent": {
    "question": "把对方字面在问的问题改写成一个明确的是/否问题",
    "options": [
      {"label": "是", "probability": 0-100 整数, "reason": "为什么判为是，一句话"},
      {"label": "不是", "probability": 0-100 整数, "reason": "为什么判为不是，一句话"}
    ]
  },
  "real_intent": [
    {"label": "意图描述", "probability": 0-100 整数, "reply": "针对这个意图，我可以直接发出去的一句话回复"},
    {"label": "意图描述", "probability": 0-100 整数, "reply": "针对这个意图，我可以直接发出去的一句话回复"}
  ],
  "anger_level": 0-10 整数,
  "comment": "一句话总结，可选"
}

要求：
- intent.options 的概率之和应为 100
- real_intent 给 2-4 条，概率之和应为 100
- anger_level 表示对方生气程度：0=完全不生气，10=非常生气
- real_intent 要指出字面背后的真实动机（如「想确认你在不在乎她」「想吵架」等）
- intent.options 的每一项都必须给 reason：一句话说明为什么给出这个概率
- real_intent 的每一项也必须给 reason：一句话说明判断依据
- 每条 real_intent 的 reply 必须是「我」可以直接发送给对方的一句话，口语化、简短、贴合该意图""".trimIndent()
    }
}

object ConfigRepo {

    /** v2.5.2: rate-limits the loadLocal log line. */
    private val loadLocalLogged = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * v3.1.0: A DEBOUNCED DISK WRITER for the cross-process mirror.
     *
     * WHY: every floating-window control is now "change it and it takes effect at once". Writing the
     * mirror synchronously from `onProgressChanged` / `afterTextChanged` would put a multi-file disk
     * write on the UI thread for EVERY keystroke of a drag or a typed number -- exactly the stutter the
     * user reported. So the mirror write is coalesced: the first change schedules a write 300 ms later,
     * and further changes inside that window only replace the pending `Config`. The prefs themselves
     * (`saveLocal`) are still written immediately, because those are the authoritative in-process store
     * and `SharedPreferences.apply()` is already asynchronous.
     */
    private const val MIRROR_DEBOUNCE_MS = 300L

    private val mirrorLock = Any()
    private var pendingMirror: Config? = null
    private var mirrorScheduled = false

    /** Context that can reach this user's shared storage; set once, reused by later writes. */
    @Volatile
    private var mirrorCtx: Context? = null

    /**
     * v3.1.0: saves the config AND schedules the cross-process mirror write.
     *
     * This is the single entry point every in-process control should use: it does `saveLocal` and then
     * makes sure the WeChat/App-side mirror follows, so "changed it in the panel" no longer depends on
     * the module app being opened before it is visible to the other process.
     */
    fun saveLocalAndMirror(ctx: Context, c: Config) {
        saveLocal(ctx, c)
        scheduleMirror(ctx, c)
    }

    /**
     * v3.1.0: writes the mirror after [MIRROR_DEBOUNCE_MS], coalescing every change in between.
     *
     * Called on the UI thread; the actual file IO happens on the main looper after the user has stopped
     * moving the control, so a drag cannot queue one write per frame.
     */
    fun scheduleMirror(ctx: Context, c: Config) {
        mirrorCtx = ctx.applicationContext ?: ctx
        synchronized(mirrorLock) {
            pendingMirror = c
            if (mirrorScheduled) return
            mirrorScheduled = true
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ flushMirror() }, MIRROR_DEBOUNCE_MS)
    }

    /**
     * v3.1.0: writes the pending mirror NOW.
     *
     * Called by the debounce timer, and directly by a control that is about to close (a dialog's
     * 完成 / 保存 button), so a value is never lost because the panel went away before the timer fired.
     */
    fun flushMirror() {
        val c: Config
        synchronized(mirrorLock) {
            c = pendingMirror ?: return
            pendingMirror = null
            mirrorScheduled = false
        }
        val ctx = mirrorCtx ?: return
        try {
            writeMirror(ctx, c)
        } catch (t: Throwable) {
            Log.w(TAG, "mirror flush failed: " + t.message)
        }
    }

    /**
     * Every place a config mirror is looked for, in order, for the CURRENT android user.
     *
     * WHY THIS EXISTS: the app and WeChat are different packages with different private data, AND every
     * android user (a clone is a user) has its own copy of both. A config written by the app for user 0 is
     * invisible to WeChat running as user 999, which is why a clone can look "broken" when it simply has no
     * configuration. Listing the candidates makes that visible instead of mysterious.
     */
    fun mirrorPaths(ctx: Context?): List<File> {
        val user = android.os.Process.myUid() / 100000
        val base = if (user == 0) "/storage/emulated/0" else "/storage/emulated/" + user
        val out = ArrayList<File>(6)
        // the app's own dirs, for this user
        try {
            ctx?.getExternalFilesDir(null)?.let { out.add(File(it, "config-mirror.properties")) }
        } catch (_: Throwable) {
            // ignore
        }
        try {
            ctx?.filesDir?.let { out.add(File(it, "config-mirror.properties")) }
        } catch (_: Throwable) {
            // ignore
        }
        // the locations the WeChat process can reach for this user
        out.add(File(base + "/Android/data/com.luoliaoo.wechat/files/config-mirror.properties"))
        out.add(File(base + "/Android/data/com.tencent.mm/files/luoliao/config-mirror.properties"))
        out.add(File("/data/user/" + user + "/com.luoliaoo.wechat/files/config-mirror.properties"))
        out.add(File("/data/user/" + user + "/com.tencent.mm/files/luoliao/config-mirror.properties"))
        return out
    }

    /** Reads the first mirror that has a usable API key, logging which one won. */
    fun loadMirror(ctx: Context?): Config? {
        for (f in mirrorPaths(ctx)) {
            try {
                if (!f.isFile || f.length() == 0L) continue
                val props = java.util.Properties()
                f.reader(Charsets.UTF_8).use { props.load(it) }
                val key = props.getProperty("apiKey", "")
                val url = props.getProperty("baseUrl", "")
                if (key.isBlank() && url.isBlank()) continue
                Log.i(TAG, "config mirror used: " + f.absolutePath)
                return readFrom(
                    object : android.content.SharedPreferences {
                        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>()
                        override fun getString(k: String?, d: String?): String? =
                            props.getProperty(k, d)
                        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = d
                        override fun getInt(k: String?, d: Int): Int =
                            props.getProperty(k)?.toIntOrNull() ?: d
                        override fun getLong(k: String?, d: Long): Long =
                            props.getProperty(k)?.toLongOrNull() ?: d
                        override fun getFloat(k: String?, d: Float): Float =
                            props.getProperty(k)?.toFloatOrNull() ?: d
                        override fun getBoolean(k: String?, d: Boolean): Boolean =
                            props.getProperty(k)?.toBooleanStrictOrNull() ?: d
                        override fun contains(k: String?): Boolean = props.containsKey(k)
                        override fun edit(): android.content.SharedPreferences.Editor =
                            throw UnsupportedOperationException()
                        override fun registerOnSharedPreferenceChangeListener(
                            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
                        ) = Unit
                        override fun unregisterOnSharedPreferenceChangeListener(
                            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
                        ) = Unit
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "mirror unreadable " + f.name + ": " + t.message)
            }
        }
        return null
    }

    /** Writes the mirror to EVERY candidate so the WeChat process of this user can find one. */
    fun writeMirror(ctx: Context, c: Config): String {
        val body = buildString {
            append("baseUrl=").append(c.baseUrl).append('\n')
            append("apiKey=").append(c.apiKey).append('\n')
            append("model=").append(c.model).append('\n')
            /* ★ v10.3.0：识图模型三项必须进镜像。
               理由与 apiKey 相同且**更严重**：识图请求真正发起的地方是
               **微信进程**（AnalysisController -> VisionClient），而这三项是在模块 App 里填的。
               不进镜像的话，用户在界面里填好了识图模型，微信进程读到的仍是空 ——
               表现为"配了模型但图片永远没有被识图"，而且没有任何报错，
               是整个功能里最难排查的一种失败。

               ⚠️ 这里**必须用 KEY_ 常量拼键名**，不能像上面 baseUrl/apiKey/model 那样手写驼峰字面量。
               原因见 [KEY_VISION_MODEL] 的注释：readFrom 是按常量（下划线命名）读的，
               手写驼峰会写出一个**永远读不回来**的键 —— 本文件里 baseUrl/apiKey/model
               三行正是这个问题（写了 `baseUrl=`，读的是 `base_url`），
               只是它们有 loadLocal 兜底才没暴露。新字段没有兜底，写错就等于功能不存在。 */
            append(KEY_VISION_BASE_URL).append('=').append(c.visionBaseUrl).append('\n')
            append(KEY_VISION_API_KEY).append('=').append(c.visionApiKey).append('\n')
            append(KEY_VISION_MODEL).append('=').append(c.visionModel).append('\n')
            // 视觉等级同样必须进镜像：读它的地方在微信进程（VisionClient.describe）。
            append(KEY_VISION_LEVEL).append('=').append(VisionLevel.clamp(c.visionLevel)).append('\n')
            append("systemPrompt=").append(c.systemPrompt).append('\n')
            append("defaultUp=").append(c.defaultUp).append('\n')
            append("defaultDown=").append(c.defaultDown).append('\n')
            append("defaultRangeHours=").append(c.defaultRangeHours).append('\n')
            append("cardWidthChars=").append(c.cardWidth).append('\n')
            append("panelWidthChars=").append(c.panelWidthChars).append('\n')
            append("panelMaxHeightDp=").append(c.panelMaxHeightDp).append('\n')
            append("adaptiveFont=").append(c.adaptiveFont).append('\n')
            // v3.1.0: these three were MISSING from the mirror, so a value set in one process was
            // silently dropped when the other process read the mirror back -- the balloon came back
            // fully opaque and the debug switch reset. The keys match what loadLocal/readFrom read.
            append("balloonAlpha=").append(c.balloonAlpha).append('\n')
            // item NEW: the balloon on/off switch travels the same way, so a toggle made in the WeChat
            // process is not undone by a later mirror read (and vice versa).
            append("balloonEnabled=").append(c.balloonEnabled).append('\n')
            /* ★ v9.3.0: 悬浮球位置也要进镜像。
               注意这里用的是 **KEY 常量**（下划线命名），而不是上面那两行那种驼峰字面量 ——
               因为读回时走的是 `readFrom` -> `p.getInt(KEY_BALLOON_POS_X, ...)`，
               键名必须与常量**逐字符相同**才能取到（Properties 的键是大小写敏感的）。
               用常量拼键名而不是手写字符串，就不会再出现「写 `balloonAlpha`、读 `balloon_alpha`」
               这类对不上的情况（上面那两行正是这个历史问题，此处不顺手改动它们，
               因为那属于另一个已存在的行为，改了会影响其它值，不在本次需求范围内）。 */
            append(KEY_BALLOON_POS_X).append('=').append(c.balloonPosX).append('\n')
            append(KEY_BALLOON_POS_Y).append('=').append(c.balloonPosY).append('\n')
            // v10.0.0: 语音转文字开关也进镜像。设置界面在模块 App 里改、判断在微信进程里做，
            // 不镜像就会出现"设置里关了、微信里还在转"。用常量拼键名，与 readFrom 逐字符一致。
            append(KEY_VOICE_TO_TEXT).append('=').append(c.voiceToText).append('\n')
            // v10.3.0: 图片识别开关同样进镜像 —— 开关在模块 App 里改、读图在微信进程里做。
            append(KEY_IMAGE_ANALYSIS).append('=').append(c.imageAnalysis).append('\n')
            // v10.3.0: 流式速度同样进镜像。设置界面在模块 App 里改、节流在宿主进程里做，
            // 不镜像就会出现"设置里调了速度、追问还是按默认速度出字"。
            append(KEY_STREAM_SPEED).append('=').append(StreamSpeed.clamp(c.streamSpeed)).append('\n')
            append("debugLog=").append(c.debugLog).append('\n')
            append("accentColor=").append(c.accentColor).append('\n')
            append("fontSp=").append(c.fontSp).append('\n')
        }
        var written = 0
        for (f in mirrorPaths(ctx)) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(body)
                written++
            } catch (_: Throwable) {
                // some candidates are not writable from here; that is expected
            }
        }
        Log.i(TAG, "config mirror written to " + written + " location(s) for user " +
            android.os.Process.myUid() / 100000)
        val first = mirrorPaths(ctx).firstOrNull { it.isFile }?.absolutePath ?: "(none)"
        return first
    }


    private const val TAG = "LuoLiao"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- local scope

    /** Reads config from THIS process's own preferences (authoritative inside WeChat). */
    fun loadLocal(ctx: Context): Config {
        val p = prefs(ctx)
        val d = Config()
        val stored = try {
            SecureStore.loadLocal(ctx)
        } catch (_: Throwable) {
            ""
        }
        val c = Config(
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            apiKey = stored.ifBlank { p.getString(KEY_API_KEY, "") ?: "" },
            // item 4: the STORED value wins; only a genuinely absent key falls back.
            model = p.getString(KEY_MODEL, "") ?: "",
            adaptiveFont = p.getBoolean(KEY_ADAPTIVE_FONT, true),
            balloonAlpha = p.getInt(KEY_BALLOON_ALPHA, d.balloonAlpha),
            balloonScale = p.getInt(KEY_BALLOON_SCALE, d.balloonScale),
            balloonEnabled = p.getBoolean(KEY_BALLOON_ENABLED, d.balloonEnabled),
            balloonPosX = p.getInt(KEY_BALLOON_POS_X, d.balloonPosX),
            balloonPosY = p.getInt(KEY_BALLOON_POS_Y, d.balloonPosY),
            debugLog = p.getBoolean(KEY_DEBUG_LOG, false),
            voiceToText = p.getBoolean(KEY_VOICE_TO_TEXT, d.voiceToText),
            imageAnalysis = p.getBoolean(KEY_IMAGE_ANALYSIS, d.imageAnalysis),
            visionBaseUrl = p.getString(KEY_VISION_BASE_URL, d.visionBaseUrl) ?: "",
            visionApiKey = p.getString(KEY_VISION_API_KEY, d.visionApiKey) ?: "",
            visionModel = p.getString(KEY_VISION_MODEL, d.visionModel) ?: "",
            visionLevel = VisionLevel.clamp(p.getInt(KEY_VISION_LEVEL, d.visionLevel)),
            systemPrompt = p.getString(KEY_PROMPT, d.systemPrompt) ?: d.systemPrompt,
            defaultUp = p.getInt(KEY_UP, d.defaultUp),
            defaultDown = p.getInt(KEY_DOWN, d.defaultDown),
            defaultRangeHours = p.getInt(KEY_HOURS, d.defaultRangeHours),
            cardWidthChars = p.getInt(KEY_CARD_WIDTH, d.cardWidthChars),
            accentColor = p.getInt(KEY_ACCENT, d.accentColor),
            fontSp = p.getFloat(KEY_FONT_SP, d.fontSp),
            panelWidthChars = p.getInt(KEY_PANEL_WIDTH, d.panelWidthChars),
            panelMaxHeightDp = p.getInt(KEY_PANEL_HEIGHT, d.panelMaxHeightDp),
            streamSpeed = StreamSpeed.clamp(p.getInt(KEY_STREAM_SPEED, d.streamSpeed)),
        )
        // v2.5.2: loadLocal runs on every read (106 lines in one window) and the values do not
        // change between reads, so only the first few are logged.
        if (loadLocalLogged.incrementAndGet() <= 8) Log.i(TAG, "loadLocal: baseUrl=" + c.baseUrl + " model=" + c.model +
                " keyPresent=" + c.apiKey.isNotBlank() + " keyLen=" + c.apiKey.length +
                " promptLen=" + c.systemPrompt.length + " cardWidth=" + c.cardWidth,
        )
        return c
    }

    /** Writes config into THIS process's own preferences. */
    fun saveLocal(ctx: Context, c: Config) {
        try {
            SecureStore.saveLocal(ctx, c.apiKey)
        } catch (t: Throwable) {
            Log.w(TAG, "saveLocal: key store failed: " + t.message)
        }
        prefs(ctx).edit()
            .putString(KEY_BASE_URL, c.baseUrl)
            .putString(KEY_MODEL, c.model)
            .putBoolean(KEY_ADAPTIVE_FONT, c.adaptiveFont)
            .putString(KEY_VISION_BASE_URL, c.visionBaseUrl)
            .putString(KEY_VISION_API_KEY, c.visionApiKey)
            .putString(KEY_VISION_MODEL, c.visionModel)
            .putInt(KEY_VISION_LEVEL, VisionLevel.clamp(c.visionLevel))

            .putInt(KEY_BALLOON_ALPHA, c.balloonAlpha)
            .putInt(KEY_BALLOON_SCALE, c.balloonScale)
            .putBoolean(KEY_BALLOON_ENABLED, c.balloonEnabled)
            .putInt(KEY_BALLOON_POS_X, c.balloonPosX)
            .putInt(KEY_BALLOON_POS_Y, c.balloonPosY)
            .putBoolean(KEY_DEBUG_LOG, c.debugLog)
            .putBoolean(KEY_VOICE_TO_TEXT, c.voiceToText)
            .putBoolean(KEY_IMAGE_ANALYSIS, c.imageAnalysis)
            .putString(KEY_PROMPT, c.systemPrompt)
            .putInt(KEY_UP, c.defaultUp)
            .putInt(KEY_DOWN, c.defaultDown)
            .putInt(KEY_HOURS, c.defaultRangeHours)
            // v3.1.0: this wrote `c.cardWidth` (the EFFECTIVE width, which falls back to
            // panelWidthChars when set). The stored cardWidthChars was therefore overwritten with the
            // panel width on every save, and editing the panel width silently rewrote the card width
            // too -- one of the reasons a change in the panel appeared not to stick. The field's own
            // value belongs here; `cardWidth` stays a read-time derivation.
            .putInt(KEY_CARD_WIDTH, c.cardWidthChars)
            .putInt(KEY_ACCENT, c.accentColor)
            .putFloat(KEY_FONT_SP, c.fontSp)
            .putInt(KEY_PANEL_WIDTH, c.panelWidthChars)
            .putInt(KEY_PANEL_HEIGHT, c.panelMaxHeightDp)
            .putInt(KEY_STREAM_SPEED, StreamSpeed.clamp(c.streamSpeed))
            .apply()
        Log.i(
            TAG,
            "saveLocal: baseUrl=" + c.baseUrl + " model=" + c.model +
                " keyPresent=" + c.apiKey.isNotBlank() + " cardWidth=" + c.cardWidth,
        )
    }

    // ------------------------------------------------------------ module scope

    /**
     * item NEW: flips the balloon switch and persists it, returning the NEW value.
     *
     * One place does the read-modify-write, so the menu entry, the settings page and any future caller
     * cannot disagree about what "toggle" means. The whole call is exception-safe: a menu tap runs inside
     * the WeChat process, where an uncaught throwable would take the chat screen down with it.
     *
     * The write goes to THIS process's own scope (loadLocal/saveLocal), which is the authoritative one
     * inside WeChat, and is then mirrored so the module's own app sees the same state.
     */
    fun toggleBalloonEnabled(ctx: Context): Boolean = try {
        val cur = loadLocal(ctx)
        val next = !cur.balloonEnabled
        // Read-modify-write on ONE snapshot, so a concurrent change between the two reads cannot make
        // the copy carry a different value than the one that was just inverted.
        saveLocal(ctx, cur.copy(balloonEnabled = next))
        try {
            writeMirror(ctx, cur.copy(balloonEnabled = next))
        } catch (_: Throwable) {
            // the mirror is a cross-process convenience, not the source of truth here
        }
        Log.i(TAG, "balloonEnabled -> " + next + " (persisted in this process and mirrored)")
        next
    } catch (t: Throwable) {
        Log.w(TAG, "toggleBalloonEnabled failed: " + t.message)
        // Report "unchanged" by reading back; a failed flip must not be reported as success.
        try {
            loadLocal(ctx).balloonEnabled
        } catch (_: Throwable) {
            true
        }
    }

    /** item NEW: current balloon switch state, never throwing. */
    fun balloonEnabled(ctx: Context?): Boolean = try {
        if (ctx == null) true else loadLocal(ctx).balloonEnabled
    } catch (_: Throwable) {
        true
    }

    /**
     * ★ v10.0.0: 语音转文字是否开启，**永不抛异常**。
     *
     * 【为什么要包一层 try】这个函数在**微信的消息绑定热路径上**被调用（每绑定一行就查一次）。
     * 读 prefs 理论上不会失败，但一旦它抛了，异常会顺着 onBindViewHolder 冒到微信的 RecyclerView 里，
     * 轻则这一行不显示、重则整个聊天页崩 —— 而它只是个"要不要转写"的判断，不值得冒这个险。
     *
     * 【为什么失败时返回 true】与 balloonEnabled 同一个取向：读取失败意味着"配置不可知"，
     * 此时按**默认值**行事（默认开），而不是悄悄把用户要的功能关掉。
     */
    fun voiceToText(ctx: Context?): Boolean = try {
        if (ctx == null) true else loadLocal(ctx).voiceToText
    } catch (_: Throwable) {
        true
    }

    /**
     * ★ v10.3.0：图片识别开关的安全读取。理由与 [voiceToText] 完全相同 ——
     * 它在消息绑定热路径上被调用，读失败时按**默认值（开）**行事，而不是把功能悄悄关掉。
     *
     * 注意：真正的读图动作发生在**分析发起时**（用户点击之后），不在绑定热路径上。
     * 这里只读一个布尔值，不会碰文件系统、不会读图片字节。
     */
    fun imageAnalysis(ctx: Context?): Boolean = try {
        if (ctx == null) true else loadLocal(ctx).imageAnalysis
    } catch (_: Throwable) {
        true
    }

    /** Module app side: plain preferences plus the encrypted archive. */
    fun load(ctx: Context): Config {
        val p = prefs(ctx)
        val d = Config()
        return Config(
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            apiKey = try {
                SecureStore.loadDecrypted(ctx)
            } catch (_: Throwable) {
                ""
            }.ifBlank { p.getString(KEY_API_KEY, "") ?: "" },
            // item 4: the STORED value wins; only a genuinely absent key falls back.
            model = p.getString(KEY_MODEL, "") ?: "",
            adaptiveFont = p.getBoolean(KEY_ADAPTIVE_FONT, true),
            debugLog = p.getBoolean(KEY_DEBUG_LOG, false),
            voiceToText = p.getBoolean(KEY_VOICE_TO_TEXT, d.voiceToText),
            imageAnalysis = p.getBoolean(KEY_IMAGE_ANALYSIS, d.imageAnalysis),
            /* ★ v10.3.0 识图配置。
               ⚠️【这里少一行就会出现最难查的 bug】本函数是**模块 App 侧**的读取路径。
               识图配置只在模块 App 里填，而请求在微信进程里发 —— 所以：
                 * 漏掉这里的 vision* → 模块 App 界面自己读不到（填完重开就变空）；
                 * 漏掉 readFrom() 里的 → 微信进程读镜像时拿不到（功能静默失效）。
               两条路都必须有，缺一条都会表现成"配了没用"，而且没有报错。 */
            visionBaseUrl = p.getString(KEY_VISION_BASE_URL, d.visionBaseUrl) ?: "",
            visionApiKey = p.getString(KEY_VISION_API_KEY, d.visionApiKey) ?: "",
            visionModel = p.getString(KEY_VISION_MODEL, d.visionModel) ?: "",
            visionLevel = VisionLevel.clamp(p.getInt(KEY_VISION_LEVEL, d.visionLevel)),
            // v3.1.0: balloonAlpha used to be dropped here, so the module-side 悬浮窗控制 read 0 and a
            // save from this scope silently reset the transparency the user had set in WeChat.
            balloonAlpha = p.getInt(KEY_BALLOON_ALPHA, d.balloonAlpha),
            // v8.0.0: the same treatment balloonAlpha needed -- a value the user sets in one scope must
            // not be dropped when this scope saves, or the balloon silently jumps back to 100% the next
            // time another setting is written.
            balloonScale = p.getInt(KEY_BALLOON_SCALE, d.balloonScale),
            balloonEnabled = p.getBoolean(KEY_BALLOON_ENABLED, d.balloonEnabled),
            balloonPosX = p.getInt(KEY_BALLOON_POS_X, d.balloonPosX),
            balloonPosY = p.getInt(KEY_BALLOON_POS_Y, d.balloonPosY),
            systemPrompt = p.getString(KEY_PROMPT, d.systemPrompt) ?: d.systemPrompt,
            defaultUp = p.getInt(KEY_UP, d.defaultUp),
            defaultDown = p.getInt(KEY_DOWN, d.defaultDown),
            defaultRangeHours = p.getInt(KEY_HOURS, d.defaultRangeHours),
            cardWidthChars = p.getInt(KEY_CARD_WIDTH, d.cardWidthChars),
            accentColor = p.getInt(KEY_ACCENT, d.accentColor),
            fontSp = p.getFloat(KEY_FONT_SP, d.fontSp),
            panelWidthChars = p.getInt(KEY_PANEL_WIDTH, d.panelWidthChars),
            panelMaxHeightDp = p.getInt(KEY_PANEL_HEIGHT, d.panelMaxHeightDp),
            streamSpeed = StreamSpeed.clamp(p.getInt(KEY_STREAM_SPEED, d.streamSpeed)),
        )
    }

    fun save(ctx: Context, c: Config) {
        prefs(ctx).edit()
            .putString(KEY_BASE_URL, c.baseUrl)
            .putString(KEY_API_KEY, c.apiKey)
            .putString(KEY_MODEL, c.model)
            .putBoolean(KEY_ADAPTIVE_FONT, c.adaptiveFont)
            .putBoolean(KEY_DEBUG_LOG, c.debugLog)
            .putBoolean(KEY_VOICE_TO_TEXT, c.voiceToText)
            .putBoolean(KEY_IMAGE_ANALYSIS, c.imageAnalysis)
            .putString(KEY_VISION_BASE_URL, c.visionBaseUrl)
            .putString(KEY_VISION_API_KEY, c.visionApiKey)
            .putString(KEY_VISION_MODEL, c.visionModel)
            .putInt(KEY_VISION_LEVEL, VisionLevel.clamp(c.visionLevel))
            .putInt(KEY_BALLOON_ALPHA, c.balloonAlpha)
            .putInt(KEY_BALLOON_SCALE, c.balloonScale)
            .putBoolean(KEY_BALLOON_ENABLED, c.balloonEnabled)
            .putInt(KEY_BALLOON_POS_X, c.balloonPosX)
            .putInt(KEY_BALLOON_POS_Y, c.balloonPosY)
            .putString(KEY_PROMPT, c.systemPrompt)
            .putInt(KEY_UP, c.defaultUp)
            .putInt(KEY_DOWN, c.defaultDown)
            .putInt(KEY_HOURS, c.defaultRangeHours)
            // t4: same fix as saveLocal -- the field's OWN value, not the derived effective width.
            .putInt(KEY_CARD_WIDTH, c.cardWidthChars)
            .putInt(KEY_ACCENT, c.accentColor)
            .putFloat(KEY_FONT_SP, c.fontSp)
            .putInt(KEY_PANEL_WIDTH, c.panelWidthChars)
            .putInt(KEY_PANEL_HEIGHT, c.panelMaxHeightDp)
            .putInt(KEY_STREAM_SPEED, StreamSpeed.clamp(c.streamSpeed))
            .apply()
    }

    /** Cross-process fallback: redirected prefs, then libxposed remote prefs. */
    fun loadRemote(ctx: android.content.Context): Config? {
        // v3.0.0: `getRemotePreferences` was a libxposed-only API and is gone with the legacy switch.
        // The same job is done by the config MIRROR the module already writes; it is how a clone's config
        // reaches the WeChat process without any framework-specific call.
        return try {
            loadMirror(ctx)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readFrom(sp: SharedPreferences): Config {
        val d = Config()
        Log.i(TAG, "readFrom: entries=" + sp.all.size)
        return Config(
            baseUrl = sp.getString(KEY_BASE_URL, "") ?: "",
            apiKey = sp.getString(KEY_API_KEY, "") ?: "",
            model = sp.getString(KEY_MODEL, "") ?: "",
            adaptiveFont = sp.getBoolean(KEY_ADAPTIVE_FONT, d.adaptiveFont),
            debugLog = sp.getBoolean(KEY_DEBUG_LOG, d.debugLog),
            balloonAlpha = sp.getInt(KEY_BALLOON_ALPHA, d.balloonAlpha),
            balloonEnabled = sp.getBoolean(KEY_BALLOON_ENABLED, d.balloonEnabled),
            balloonPosX = sp.getInt(KEY_BALLOON_POS_X, d.balloonPosX),
            balloonPosY = sp.getInt(KEY_BALLOON_POS_Y, d.balloonPosY),
            voiceToText = sp.getBoolean(KEY_VOICE_TO_TEXT, d.voiceToText),
            imageAnalysis = sp.getBoolean(KEY_IMAGE_ANALYSIS, d.imageAnalysis),
            /* ★ v10.3.0：识图配置走镜像进入**微信进程**最关键的一步。
               微信进程没有自己的 prefs 可读（配置是用户在模块 App 里填的），
               `currentConfig()` 在 loadLocal 拿不到 key 时会回落到 loadMirror → 本函数。
               所以**这里才是微信进程真正拿到识图配置的地方** ——
               漏掉这几行，识图功能在真机上会完全静默失效（不报错、只是从不发请求）。

               ⚠️ 键名必须与 writeMirror 用同一个常量。本文件历史上已经有过
               `baseUrl=` / `base_url` 这种写读不一致（见 KEY_VISION_MODEL 的注释），
               那种错不会报错，只会安静地返回空值。 */
            visionBaseUrl = sp.getString(KEY_VISION_BASE_URL, d.visionBaseUrl) ?: "",
            visionApiKey = sp.getString(KEY_VISION_API_KEY, d.visionApiKey) ?: "",
            visionModel = sp.getString(KEY_VISION_MODEL, d.visionModel) ?: "",
            visionLevel = VisionLevel.clamp(sp.getInt(KEY_VISION_LEVEL, d.visionLevel)),
            systemPrompt = sp.getString(KEY_PROMPT, d.systemPrompt) ?: d.systemPrompt,
            defaultUp = sp.getInt(KEY_UP, d.defaultUp),
            defaultDown = sp.getInt(KEY_DOWN, d.defaultDown),
            defaultRangeHours = sp.getInt(KEY_HOURS, d.defaultRangeHours),
            cardWidthChars = sp.getInt(KEY_CARD_WIDTH, d.cardWidthChars),
            accentColor = sp.getInt(KEY_ACCENT, d.accentColor),
            fontSp = sp.getFloat(KEY_FONT_SP, d.fontSp),
            panelWidthChars = sp.getInt(KEY_PANEL_WIDTH, d.panelWidthChars),
            panelMaxHeightDp = sp.getInt(KEY_PANEL_HEIGHT, d.panelMaxHeightDp),
            streamSpeed = StreamSpeed.clamp(sp.getInt(KEY_STREAM_SPEED, d.streamSpeed)),
        )
    }

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ADAPTIVE_FONT = "adaptive_font"
 const val KEY_BALLOON_ALPHA = "balloon_alpha"

    /**
     * The balloon's size key. Public for the same reason as [KEY_BALLOON_ALPHA]: the host-process side
     * reads it to size the balloon, and the settings UI writes it.
     */
    const val KEY_BALLOON_SCALE = "balloon_scale"

    /**
     * item NEW: the balloon on/off switch key.
     *
     * Public like KEY_BALLOON_ALPHA, because the WeChat-process side flips it through the same repo
     * functions and a future settings UI has to read it back.
     */
    const val KEY_BALLOON_ENABLED = "balloon_enabled"

    /**
     * ★ v9.3.0: the balloon's last dragged position, in SCREEN pixels.
     *
     * Public for the same reason as the keys above: `FloatingPanel` (running inside the host process)
     * reads and writes them through this repo, and the settings side has to be able to clear them.
     *
     * -1 means "never dragged"; see the field docs on [Config.balloonPosX] for why 0 is NOT the sentinel.
     */
    const val KEY_BALLOON_POS_X = "balloon_pos_x"
    const val KEY_BALLOON_POS_Y = "balloon_pos_y"
    private const val KEY_DEBUG_LOG = "debug_log"
    private const val KEY_PROMPT = "system_prompt"
    private const val KEY_UP = "default_up"
    private const val KEY_DOWN = "default_down"
    private const val KEY_HOURS = "default_range_hours"
    private const val KEY_CARD_WIDTH = "card_width_chars"
    private const val KEY_ACCENT = "card_accent_color"
    private const val KEY_FONT_SP = "card_font_sp"
    private const val KEY_PANEL_WIDTH = "panel_width_chars"
    private const val KEY_PANEL_HEIGHT = "panel_max_height_dp"

    /**
     * ★ v10.0.0：语音转文字开关的键。
     *
     * 与其它键不同，这个键在**两个进程**里都要读到：设置界面（模块 App）写它，
     * 而真正用它做判断的是**微信进程**（AnalysisController.handleVoice）。
     * 所以它必须同时进 saveLocal/save 与镜像（writeMirror/readFrom），
     * 否则"在设置里关了、微信里还在转"——这正是 balloonAlpha 当年踩过的坑
     * （见 writeMirror 里那段注释：写了 balloonAlpha 却按 balloon_alpha 读）。
     * 这里用**常量拼键名**，不再手写字符串，从根上避免键名对不上。
     */
    const val KEY_VOICE_TO_TEXT = "voice_to_text"

    /**
     * ★ v10.3.0：图片/表情包识别开关的键。
     *
     * 与 [KEY_VOICE_TO_TEXT] 完全同理：开关在**模块 App** 里改，读图发生在**微信进程**，
     * 所以必须同时进 saveLocal / save / readFrom 四路与镜像，否则会出现
     * "设置里关了、微信还在把图片发出去" —— 那不只是功能没生效，而是**隐私设置没有生效**，
     * 后果比一个开关失灵严重得多。
     */
    const val KEY_IMAGE_ANALYSIS = "image_analysis"

    /**
     * ★ v10.3.0：识图模型三项的键。
     *
     * ⚠️【为什么必须是常量而不是手写字面量】本文件已经存在一个**真实存在、尚未被触发的**键名错位：
     * `writeMirror` 写的是 `baseUrl=` / `apiKey=` / `model=`（驼峰），
     * 而回读用的 `readFrom` 读的是 `base_url` / `api_key` / `model`（下划线）。
     * 也就是说镜像里的这三项**从来没被读回去过**。
     *
     * 它至今没有暴露，是因为 `currentConfig()` 先用 `loadLocal` 拿配置、只在 key 为空时才查镜像 ——
     * 而在微信进程里 `loadLocal` 通常能读到值，于是镜像这条路被掩盖了。
     * 但"通常"不等于"总是"：跨用户（应用分身）场景下 loadLocal 读不到，就只剩镜像这一条路，
     * 那时这三项会静默变成空。
     *
     * 新字段**没有 loadLocal 兜底**（识图配置只在模块 App 里存在），
     * 所以一旦写错就是"功能完全不存在且毫无线索"。这里坚持用常量拼键名，与读端逐字符一致。
     */
    const val KEY_VISION_BASE_URL = "vision_base_url"
    const val KEY_VISION_API_KEY = "vision_api_key"
    const val KEY_VISION_MODEL = "vision_model"

    /**
     * ★ v10.3.0：视觉等级（低/中/高/极其高）的键。取值含义见 [VisionLevel]。
     *
     * 与其余 vision_* 键完全同理：界面在模块 App 写、请求在宿主进程发，必须进镜像。
     * 存的是**整数**（不是字符串），所以夹取统一走 [VisionLevel.clamp] ——
     * 镜像文件是纯文本，手改过之后可能出现越界值，读回时夹取能保证运行时永远拿到四档之一。
     */
    const val KEY_VISION_LEVEL = "vision_level"

    /**
     * ★ v10.3.0：追问流式速度的键。
     *
     * 与 [KEY_VOICE_TO_TEXT] 同样必须**两个进程都读到**：设置界面（模块 App）写它，
     * 真正拿它做节流判断的是宿主进程（`AnalysisController.followUp`）。
     * 所以它必须同时进 saveLocal/save/readFrom 与镜像（writeMirror），并用常量拼键名。
     */
    const val KEY_STREAM_SPEED = "stream_speed"
}
