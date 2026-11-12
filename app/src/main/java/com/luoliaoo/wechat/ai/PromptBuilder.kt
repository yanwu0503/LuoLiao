package com.luoliaoo.wechat.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One chat message used for analysis.
 *
 * `time` is the message's OWN send/receive timestamp (item 7), never the time of the long press.
 */
data class ChatLine(
    val time: Long,
    val isSend: Boolean,
    val text: String,
    /** The sender's host-side id (wxid on WeChat, 抖音号 on Douyin), used to tell speakers apart (item 5). */
    val speaker: String? = null,
    /**
     * ★ v10.3.0：这条消息是不是**图片/表情包**（由 `WeChatEnvelope.isImage` 判定）。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【为什么必须单独有一个字段，而不能靠 text 判断】
     * 显示已经统一成 `[非文本消息]`（图片、语音、文件、位置……全都一样）。
     * 也就是说**从文本上根本无法区分**"这条是图片"和"这条是语音"。
     *
     * 但两者后续行为完全不同：语音有自己的转写链路，图片要走识图链路。
     * 靠文本去猜必然出错 —— 这正是需要一个显式标记的原因。
     *
     * 【它服务于什么】「读取」预览里每条**图片**消息左侧的「识图」开关（默认关）
     * 需要知道"哪几行该显示这个开关"。没有这个字段，界面就无从判断。
     *
     * 【为什么放在 ChatLine 而不是另开一个平行列表】
     * 另开列表会引入"两个列表必须保持同序同长"的隐式约束，一旦任何一处
     * filter / sort 只作用于其中一个（本文件里就有这类操作），两者立刻错位，
     * 表现是"开关显示在了错误的消息上" —— 一个很难察觉但后果严重（发错图）的 bug。
     * 包进同一个数据类，错位在类型层面就不可能发生。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    val isImage: Boolean = false,
    /**
     * ★ v10.3.0：这条消息的**稳定标识**，用于"用户为哪条消息打开了识图"的匹配。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【为什么不能用 [text] 当标识 —— 这是我已经踩过的一个真实 bug】
     *
     * `text` 在**不同调用点含义不同**：
     *   * 读取预览里的行是**带前缀的展示文本**（"对方：内容"）；
     *   * `AnalysisController` 手里的 `displayText(msg)` 是**不带前缀的正文**。
     *
     * 而识图开关是按这个字符串匹配的。我最初直接拿 `text` 当标识，于是两边永远不相等 ——
     * 表现是**开关点了没有任何效果**，而且不报错、不崩溃，只是图片从来没被发出去。
     * 这类"匹配键不一致"的 bug 极难从现象定位，因为它看起来像功能没实现。
     *
     * 所以现在把"给用户看的文本"和"用来匹配的键"**分成两个字段**：
     *   * [text] —— 爱怎么加前缀就怎么加，UI 随便用；
     *   * [key]  —— 永远等于**消息的正文**（`displayText` 的输出），两个进程、
     *     两个入口都用它匹配，不受展示格式影响。
     *
     * 【为什么默认取 text】绝大多数调用点不关心这个区分（既不显示也不匹配），
     * 让默认值等于 text 可以让它们完全不必改动 —— 只有识图链路会显式传它。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    val key: String = text,
) {
    /** "我" for my own messages, otherwise the other side's label. */
    val who: String get() = if (isSend) "我" else (speaker?.takeIf { it.isNotBlank() } ?: "对方")

    /**
     * "wxid：timestamp：content" (item 5).
     *
     * My own id is replaced with the literal 我 so the model needs no extra mapping.
     */
    fun display(sdf: SimpleDateFormat): String {
        val id = if (isSend) "我" else (speaker?.takeIf { it.isNotBlank() } ?: "对方")
        return id + "：" + sdf.format(Date(time)) + "：" + text
    }
}

/** How much of the conversation to analyse. */
enum class ScopeMode {
    /** By count: up to N before and N after the pressed message. */
    COUNT,

    /** By absolute time range. */
    TIME_RANGE,

    /** Only the pressed message. */
    ONLY_ONE,
}

/**
 * Builds the prompt sent to the model.
 *
 * 5.0.0: every prompt literal is passed through [HOST] before it is appended, so the model is told it
 * is reading 抖音聊天记录 when the module runs inside Douyin. Hardcoding 微信 here made the model
 * describe a Douyin chat as a WeChat one -- and because these strings are the model's only context,
 * the mistake was invisible in the UI and only showed up in the answers.
 */
object PromptBuilder {

    /**
     * The host this prompt is being built for.
     *
     * Read per call, never cached in a field: one process serves exactly one host, but that host is
     * decided by `ModuleEntry` AFTER this object may have been loaded, so a captured value could be
     * the default WeChat. `HostApp.current` is `@Volatile`, which is what makes a per-call read safe.
     */
    private val HOST: com.luoliaoo.wechat.core.HostApp
        get() = com.luoliaoo.wechat.core.HostApp.current

    /** [text] spelled for the host this prompt is for (微信/微信号 -> 抖音/抖音号). */
    private fun h(text: String): String = HOST.say(text)

    /**
     * item 2: a message whose text CONTAINS any of these is excluded from everything.
     *
     *     msg          我爱msg你        -> excluded   (the letters appear consecutively)
     *                  我m爱s你g        -> kept       (the letters are not adjacent)
     *     了解详情      请了解详情        -> excluded
     *     :0           表情:0           -> excluded
     *
     * WHY A LIST: a chat can carry several kinds of content that must never be analysed -- a test message, a
     * code, a password, a "view details" link, a sticker id. One marker could not cover them.
     *
     * The rule is unchanged: a plain SUBSTRING test, so where the marker appears in the message does not
     * matter. `SKIP_MARKER` stays as the first entry so any code or note that still refers to it keeps the
     * original meaning.
     */
    const val SKIP_MARKER = "msg"

    /**
     * v8.0.0: the placeholder a non-text message is rendered as, and the recall marker.
     *
     * THESE ARE NOT TYPED BY ANYONE. They are the strings the module itself substitutes when a row
     * carries no text (`QQEnvelope.placeholderForType` and `RECALLED_PLACEHOLDER`). Blocking them is
     * what keeps a sticker-heavy chat from analysing as "[表情]" repeated: the placeholders would
     * otherwise consume the prompt's line budget and hide the real conversation.
     *
     * The brackets are part of each marker on purpose -- they are what stops "[图片]" from matching an
     * ordinary sentence that merely mentions 图片.
     */
    const val SKIP_MARKER_PIC = "[\u56fe\u7247]"              // [图片]
    const val SKIP_MARKER_FACE = "[\u8868\u60c5]"             // [表情]
    const val SKIP_MARKER_FILE = "[\u6587\u4ef6]"             // [文件]
    const val SKIP_MARKER_GRAYTIP = "[\u7cfb\u7edf\u63d0\u793a]" // [系统提示]
    const val SKIP_MARKER_RECALLED = "[\u5df2\u64a4\u56de]"   // [已撤回]

    /**
     * item 2: every marker that excludes a message. Order is not significant.
     *
     * WHY THE PLACEHOLDERS ARE HERE *AND* IN `TemplateStore.DEFAULT_BLOCK_WORDS`, exactly as `msg` is:
     * the two layers fail differently. This list is a compile-time constant, so it applies on every
     * device immediately and cannot be lost; the template list is per-template, lives in JSON, and
     * reaches an already-installed device only through `migrateBuiltIns`. Belt and braces -- the
     * hardcoded layer guarantees the behaviour, and the JSON layer is what makes it visible and
     * editable in the UI.
     */
    val SKIP_MARKERS: List<String> = listOf(
        SKIP_MARKER,
        "了解详情",
        ":0",
        SKIP_MARKER_PIC,
        SKIP_MARKER_FACE,
        SKIP_MARKER_FILE,
        SKIP_MARKER_GRAYTIP,
        SKIP_MARKER_RECALLED,
    )

    /**
     * item 2: true when this text must not be analysed.
     *
     * The ONLY place the decision is made, so the UI list, the history filter and the prompt all agree.
     */
    fun isSkipped(text: String?): Boolean {
        if (text == null) return false
        for (m in SKIP_MARKERS) {
            if (m.isNotEmpty() && text.contains(m)) return true
        }
        return false
    }

    /**
     * item 2: WHICH marker excludes this text, or null when it is kept.
     *
     * Exists so the log can say which rule fired. With three markers, "skipped" alone no longer tells the
     * user why a line is missing.
     */
    fun skipMarkerIn(text: String?): String? {
        if (text == null) return null
        for (m in SKIP_MARKERS) {
            if (m.isNotEmpty() && text.contains(m)) return m
        }
        return null
    }

    // item 6: both formatters are pinned to BEIJING time, so what the user sees does not depend on the
    // phone's own time zone.
    private val BEIJING: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

    private val SDF = SimpleDateFormat("MM月dd日HH时mm分ss秒", Locale.CHINA).apply {
        timeZone = BEIJING
    }

    /** Shorter stamp for the model: keeps the prompt compact while staying unambiguous. */
    private val SDF_SHORT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = BEIJING
    }

    /**
     * @param myWxid my own host-side id (wxid / 抖音号), supplied so the model marks 我 correctly (item 5)
     * @param focusSpeaker when set, the analysis is about this specific person (group chats)
     */
    /**
     * v2.7.0 (item 2): build the prompt for an EXPLICIT set of messages.
     *
     * The multi-select entry has no count/time window -- the user picked the messages themselves -- so this
     * takes the lines verbatim. The skip markers still apply, because a message the user chose to exclude
     * globally must not reach the model either.
     */
    fun buildSelection(
        kept: List<String>,
        target: ChatLine,
        myWxid: String = "",
        focusSpeaker: String? = null,
        /** v3.0.3: the optional background the user typed, e.g. "此人是我领导". */
        background: String = "",
        /** v3.0.3: extra block words for THIS analysis, on top of the built-in markers. */
        extraBlockWords: List<String> = emptyList(),
    ): String = buildString {
        // 5.0.0: h() rewrites 「微信 / 微信号」 to the host's labels, so this literal stays the
        // WeChat-shaped source text and the Douyin wording is derived from it.
        append(h("\u4f60\u662f\u4e00\u4e2a\u5fae\u4fe1\u804a\u5929\u5206\u6790\u5e08\u3002\n"))
        append("\u4e0b\u9762\u662f\u7528\u6237\u4ece\u804a\u5929\u91cc\u9009\u4e2d\u7684\u6d88" +
            "\u606f\uff0c\u8bf7\u5206\u6790\u5b83\u4eec\u3002\n")
        if (myWxid.isNotBlank()) {
            append(h("\u6211\u7684\u5fae\u4fe1\u53f7\u662f\uff1a")).append(myWxid)
                .append("\uff08\u6807\u4e3a\u300c\u6211\u300d\u7684\u90fd\u662f\u6211\u53d1\u7684\uff09\u3002\n")
        }
        // v3.0.3: the user's background note, when present. Same wording as the scope-dialog path so the
        // two entry points produce an identical prompt shape.
        val bg = background.trim()
        if (bg.isNotEmpty()) {
            append("\u3010\u80cc\u666f\u4fe1\u606f\u3011\n").append(bg).append('\n')
        }
        append('\n')
        append("\u3010\u9009\u4e2d\u7684\u6d88\u606f\u3011\n")
        var n = 0
        for (line in kept) {
            if (isSkipped(line) || isBlockedBy(line, extraBlockWords)) continue
            append(line).append('\n')
            n++
        }
        append('\n')
        append("\u5171 ").append(n).append(" \u6761\u3002\n")
        append('\n')
        append("\u8bf7\u8f93\u51fa\u4e25\u683c\u7684 JSON\uff0c" +
            "\u5305\u542b\u610f\u56fe\u6982\u7387\u3001\u771f\u5b9e\u610f\u56fe" +
            "\u6982\u7387\u5217\u8868\u3001\u751f\u6c14\u7b49\u7ea7\u3002")
    }

    /**
     * v3.0.3: the block-word test used by the multi-select entry.
     *
     * A plain SUBSTRING test, exactly like [isSkipped]: "我爱msg你" is blocked, "我m爱s你g" is not,
     * because the characters must appear CONTIGUOUSLY to count as a match.
     */
    fun isBlockedBy(text: String?, words: List<String>): Boolean {
        if (text == null || words.isEmpty()) return false
        for (w in words) {
            if (w.isNotEmpty() && text.contains(w)) return true
        }
        return false
    }

    /** v3.0.3: WHICH block word matched, or null. Used to log why a line was dropped. */
    fun blockWordIn(text: String?, words: List<String>): String? {
        if (text == null) return null
        for (w in words) {
            if (w.isNotEmpty() && text.contains(w)) return w
        }
        return null
    }

    fun build(
        lines: List<ChatLine>,
        target: ChatLine,
        mode: ScopeMode,
        myWxid: String = "",
        focusSpeaker: String? = null,
        /**
         * v2.7.0 (item 1): optional background the user typed in the scope dialog, e.g. "此人是我领导".
         *
         * Empty means "no background", and the prompt is then byte-for-byte what it was before.
         */
        background: String = "",
        /**
         * v6.0.0: the user's block words for THIS analysis, read from the active template.
         *
         * WHY THIS PARAMETER EXISTS (user-reported bug: 「屏蔽关键词失败了 ... msg 有用 auth 跟 click 失败」):
         * the long-press path called build() without any block words, so it filtered on the hardcoded
         * SKIP_MARKERS only -- "msg" / "了解详情" / ":0". Those three worked; every word the user added to
         * a template (like "auth" and "click") was silently ignored, because build() never received the
         * template's list. Only the MULTI-SELECT path (buildSelection, via startAnalysisForSelection) ever
         * passed block words through, which is exactly why the feature looked half-broken.
         *
         * The defaults still apply on top of these, so passing an empty list preserves the old behaviour
         * byte-for-byte.
         */
        extraBlockWords: List<String> = emptyList(),
    ): String {
        // item 2: anything carrying the marker is dropped BEFORE anything else is computed, so it cannot
        // appear in the prompt, change the speaker list, or be used as the target.
        //
        // v6.0.0: the built-in markers AND the template's own words, through one filter, so the two entry
        // points (long-press and multi-select) can no longer disagree about what is blocked.
        val usable = lines.filterNot {
            isSkipped(it.text) || isBlockedBy(it.text, extraBlockWords)
        }
        val sb = StringBuilder()
        val speakers = usable.map { it.speaker?.takeIf { s -> s.isNotBlank() } ?: "对方" }
            .toSet()

        sb.append(h("以下是一段微信聊天记录，每行格式为「发送者：时间：内容」。\n"))
        sb.append("时间就是这条消息本身发送/接收的时间，请把它当作真实聊天时间使用。\n\n")

        if (myWxid.isNotBlank()) {
            sb.append(h("我的微信号是：")).append(myWxid)
                .append("（标为「我」的消息都是我发的）\n")
        }
        sb.append("这段记录里出现的发言者共有 ").append(speakers.size).append(" 个：")
        sb.append(speakers.joinToString("、")).append("\n\n")

        if (focusSpeaker != null && focusSpeaker.isNotBlank()) {
            sb.append("本次分析重点针对：").append(focusSpeaker).append("\n")
            sb.append("请只围绕这个人的话来判断，其他人的话仅作背景。\n\n")
        } else if (speakers.size <= 2) {
            sb.append("这是一对一聊天，请把「我」之外的那个人当作「对方」。\n\n")
        } else {
            sb.append(h("这是群聊。请直接使用每个人的微信号来称呼他们，\n"))
            sb.append("不要笼统地说「对方」；如果要泛指，请说明是哪一位。\n\n")
        }

        for (l in usable) {
            sb.append(if (l === target) ">> " else "   ")
            sb.append(l.speakerLabel(myWxid, SDF_SHORT)).append('\n')
        }

        sb.append("\n---\n")

        when (mode) {
            ScopeMode.COUNT, ScopeMode.TIME_RANGE -> {
                sb.append("分析对象：上面这【整段对话】，而不是某一条消息。\n")
                sb.append("（带 >> 的那条是用户长按的消息，只用来表示用户最关心哪一句，\n")
                sb.append(" 不要只围绕它作答。）\n\n")
                sb.append("分析要求：\n")
                sb.append("1. 结合整段对话的来龙去脉，总结当前这段关系/这次交流的状况。\n")
                sb.append("2. 判断对方的整体真实意图与情绪（不是某一句话的意图）。\n")
                sb.append("3. 【重要】结合每行的时间戳判断对方的语气、状态、情绪和态度变化：\n")
                sb.append("   例如回复间隔突然变长、深夜发消息、连续追问、长时间沉默后才回。\n")
                sb.append("   请把这个时间维度的观察写进 comment 里。\n")
                sb.append("4. intent.question 写成对整段对话核心问题的「是/否」提问。\n")
                sb.append("5. real_intent 给出整段对话背后 2-4 种可能的真实意图。\n")
                sb.append("6. anger_level 是对方当前的整体生气程度（0-10）。\n")
                sb.append("7. 每条 real_intent 都要给 reply：一句我现在就能发出去的话。\n")
                sb.append("8. intent.options 与 real_intent 的每一项都要给 reason：\n")
                sb.append("   一句话说明为什么这样判断。\n")
                sb.append(h("   口语化、自然、简短，像真人发微信。\n"))
            }

            ScopeMode.ONLY_ONE -> {
                sb.append("分析对象：只有下面这一条消息。\n")
                sb.append(">> ").append(target.speakerLabel(myWxid, SDF_SHORT)).append('\n')
                sb.append("（上面的记录仅作背景参考。）\n\n")
                sb.append("分析要求：\n")
                sb.append("1. intent.question 把这条消息字面在问的事情改写成明确的「是/否」问题。\n")
                sb.append("2. real_intent 给出这条消息背后 2-4 种可能的真实意图。\n")
                sb.append("3. anger_level 是这条消息体现出的生气程度（0-10）。\n")
                sb.append("4. 只有一条消息时，时间戳只能用来判断是清晨/白天/傍晚/深夜，\n")
                sb.append("   不要据此推断态度变化或回复节奏。\n")
                sb.append("5. 每条 real_intent 都要给 reply：一句我现在就能发出去的话。\n")
                sb.append("6. intent.options 与 real_intent 的每一项都要给 reason：\n")
                sb.append("   一句话说明为什么这样判断。\n")
            }
        }

        sb.append("\n严格按系统提示词要求的 JSON 格式输出，不要输出任何其它内容。")
        // v2.7.0 (item 1): the user's background note, when present.
        //
        // It is placed BEFORE the conversation so the model reads it as context rather than as something said
        // in the chat, and it is omitted entirely when blank.
        val bg = background.trim()
        if (bg.isNotEmpty()) {
            // v2.7.0 (item 1): the user's note, clearly labelled so the model treats it as CONTEXT and not as
            // something said in the chat. Placed first, before the conversation block.
            val withBg = StringBuilder()
            withBg.append("\u3010\u80cc\u666f\u8865\u5145\u3011").append(bg).append('\n')
            withBg.append("\uff08\u4ee5\u4e0a\u662f\u7528\u6237\u63d0\u4f9b\u7684\u80cc\u666f" +
                "\uff0c\u4e0d\u662f\u804a\u5929\u5185\u5bb9\uff0c\u8bf7\u7ed3\u5408\u5b83" +
                "\u7406\u89e3\u4e0b\u9762\u7684\u5bf9\u8bdd\u3002\uff09\n\n")
            withBg.append(sb)
            return withBg.toString()
        }

        return sb.toString()
    }

    /** "wxid：MM-dd HH:mm:ss：text", with my own id shown as 我. */
    private fun ChatLine.speakerLabel(myWxid: String, sdf: SimpleDateFormat): String {
        val id = when {
            isSend -> "我"
            !speaker.isNullOrBlank() && myWxid.isNotBlank() && speaker == myWxid -> "我"
            !speaker.isNullOrBlank() -> speaker
            else -> "对方"
        }
        return id + "：" + sdf.format(Date(time)) + "：" + text
    }

    /** Context for the follow-up conversation (item 9). */
    fun followUpContext(
        lines: List<ChatLine>,
        target: ChatLine,
        summary: String,
        myWxid: String = "",
    ): String = buildString {
        // item 2: the same exclusion applies to a follow-up conversation.
        append(h("你是一个微信聊天分析师，正在和我讨论下面这段聊天记录。\n"))
        append("请用中文、口语化、直接回答，不要输出 JSON，不要客套。\n")
        append("需要给可发送的句子时，直接给句子本身，不要加引号或解释。\n")
        if (myWxid.isNotBlank()) {
            append(h("我的微信号是：")).append(myWxid).append("（标为「我」的都是我发的）。\n")
        }
        append("\n聊天记录（发送者：时间：内容）：\n")
        for (l in lines) append(l.speakerLabel(myWxid, SDF_SHORT)).append('\n')
        append("\n我最关心的消息：\n")
        append(target.speakerLabel(myWxid, SDF_SHORT)).append('\n')
        if (summary.isNotBlank()) {
            append("\n之前的分析结论：\n").append(summary).append('\n')
        }
    }

    fun formatTime(t: Long): String = SDF.format(Date(t))

    fun defaultRangeEnd(): Long = System.currentTimeMillis()

    /**
     * item 6: the start of the range for a template.
     *
     * `End` is NOW and `start` is `hours` before it, both in Beijing time -- exactly the requested
     * "24 小时就是 昨天同一时刻" semantics.
     */
    fun defaultRangeStart(hours: Int): Long =
        System.currentTimeMillis() - hours.coerceAtLeast(1) * 3600_000L

    /** item 6: the range as (start, end) epochs, for a template's hours. */
    fun beijingRange(hours: Int): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        return (end - hours.coerceAtLeast(1) * 3600_000L) to end
    }
}
