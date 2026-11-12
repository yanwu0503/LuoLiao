package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log

/**
 * ★ v10.3.0：微信的**信封翻译** —— 把非文本消息的原始 XML 变成可显示的占位符。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么需要这个文件：其他 5 个宿主都有，只有微信没有】
 *
 * 本工程对"非文本消息"的处理是统一的一套：宿主把图片/语音/文件等消息的原始载荷
 * 收在 `field_content` / element 链里，模块必须把它**翻译**掉再往下走。已有的翻译器：
 *   * QQ    -> `QQEnvelope.placeholderForType`
 *   * 快手  -> `KuaishouEnvelope`
 *   * 抖音  -> `DouyinEnvelope`
 * **微信没有** —— `MsgAccess.content()` 对微信直接返回原样字符串。
 * 普通文本没问题，但图片消息的 `field_content` 是**原始 XML**：
 *
 * ```
 * <msg><img aeskey="..." encryver="1" cdnthumburl="..." cdnthumbaeskey="..." /></msg>
 * ```
 *
 * 【用户报的现象】「显示乱码 如 `<msg><immgaeskey=` 等等」——
 * 卡片上和发给模型的内容都是这段 XML。它既是显示问题，也是**分析问题**：
 * 模型收到的"对方说了什么"是一串 aeskey 和 cdn 地址，于是分析结果必然跑偏。
 *
 * 【为什么不能只在显示层修】本工程已被这个坑咬过多次（见 `displayText` 的注释）：
 * 只在写入端或只在显示端处理，就会出现"本地记录里对了、但送给模型的还是 XML"。
 * 所以翻译必须做在**唯一的出口**上 —— `AnalysisController.displayText()`，
 * 那里是语音、长按预览、多选读 UI、追问共同的入口。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【一、显示统一为 `[非文本消息]`（用户指定）】
 *
 * 所有非文本消息都翻译成**同一个**占位符 `[非文本消息]`，不再区分
 * `[图片]` / `[表情]` / `[语音]`。这是用户在看到真机效果后明确指定的。
 *
 * ⚠️【一个必须知道的连锁反应，不是 bug】占位符与 `PromptBuilder.SKIP_MARKERS` 是**联动**的：
 * 那张表里的 `[图片]` / `[表情]` 会被**过滤掉**（不进提示词），
 * 而 `[非文本消息]` **不在**那张表里，所以这些行会**进入**提示词。
 * 也就是说：改成统一占位符之后，图片/表情所在的行会作为「对方：[非文本消息]」
 * 参与分析，而不是被整行丢弃。
 *
 * 这不一定是坏事 —— 它保留了对话的节奏（"对方在这里发了个东西"），
 * 但确实与旧行为不同，所以写在这里，避免下一个人把它当成 regression 去"修"。
 * 若哪天要恢复"丢弃非文本行"，把占位符加进 `SKIP_MARKERS` 即可，不必改本文件。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【二、类型识别保留，而且**识图功能依赖它**】
 *
 * 显示虽然统一了，但 [kindOf] 仍然会把真实的类型标签（`img` / `emoji` / `voicemsg` …）
 * 解析出来。识图链路必须靠它判断"这条消息是不是图片" ——
 * 没有这个判断，就无法知道该读哪个文件、也就无法发识图请求。
 *
 * 所以**不要**因为"显示已经不再区分类型"就把分类删掉：
 * 它从"给用户看的标签"变成了"给程序用的判据"。见 [isImage]。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【三、第一版的 bug：必须记住的层级陷阱】
 *
 * 第一版按 **根标签** 查分类表，而微信把所有消息都包在 `<msg>` 外壳里 ——
 * 真正的类型是它的**第一个子标签**。于是查表永远命中 `msg`（不在表里），
 * 全部落到兜底，用户在真机上看到**每一条**消息都是兜底占位符。
 *
 * 这个 bug 的教训：**"表里没有"和"看错了层级"表现完全一样**，
 * 光看现象会误以为分类表写错了，从而在错误的层面反复修改。
 * 见 [readTag] —— 它现在明确区分这两层，并由回归测试钉死
 * （`WeChatEnvelopeTest`，用的是真机日志里逐字抄下来的 XML）。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object WeChatEnvelope {

    private const val TAG = "LuoLiao"

    /** 所有非文本消息统一使用的占位符，与 QQ 侧同名常量保持一致。 */
    const val NON_TEXT = "[\u975e\u6587\u672c\u6d88\u606f]" // [非文本消息]

    /**
     * 微信消息 XML 的**固定外壳**标签。
     *
     * 微信的每一条非文本消息都长这样：
     *
     *     <msg><img aeskey="..." /></msg>
     *     <msg><emoji ... /></msg>
     *     <msg><voicemsg ... /></msg>
     *
     * 也就是说类型标签**不是根标签**，而是根标签的第一个子元素。
     * 详见文件头的「层级陷阱」。
     */
    private const val WRAPPER = "msg"

    // ── 类型标签常量 ──────────────────────────────────────────────────────────
    // 只给**识图链路真正会用到**的两个起常量，其余标签照样能被 kindOf 解析出来，
    // 只是没有常量引用 —— 它们的用途仅剩"发现新类型"的日志。

    /** 图片消息的标签。识图链路靠它决定"要不要读图并送给识图模型"。 */
    const val KIND_IMAGE = "img"

    /**
     * 表情包消息的标签。
     *
     * ⚠️ 当前**读不出来**：见 `com.luoliaoo.wechat.ai.WeChatImage` 的实测说明 ——
     * `MicroMsg/<账号>/emoji/` 下的文件全部是加密的。这里保留常量，
     * 是为了让识图链路能识别出"这是表情包"并走同一条读取路径；
     * 将来若找到读法（或微信改回明文），无需改动本文件即自动生效。
     */
    const val KIND_EMOJI = "emoji"

    /**
     * 已知的非文本类型标签。
     *
     * 【它现在的用途只剩一个：区分"已知类型"与"新出现的类型"】
     * 两者的**显示结果完全相同**（都是 [NON_TEXT]），所以这张表不影响用户看到什么。
     * 它存在的意义是日志：[translate] 会把不在表里的标签名打出来 ——
     * 微信加了新消息格式时，靠这行日志就能直接知道该补哪个标签，
     * 不必再对 APK 做一次逆向。
     */
    private val KNOWN_KINDS: Set<String> = setOf(
        "img", "emoji", "voicemsg", "videomsg", "appmsg", "location", "appattach",
        "wcpayinfo", "gift", "pat", "sysmsg", "mmpaymask", "transfer", "chathistory",
        "refermsg", "hardlink", "gamelist", "recorditem", "finderfeed", "findernative",
        "finderlive",
    )

    /**
     * 把一条消息的原始载荷翻译成可显示/可送模型的文本。
     *
     * @param raw `MsgAccess.content()` 的返回值（普通文本，或一段 XML）。
     * @return 普通文本**原样返回**；任何非文本 XML 返回 [NON_TEXT]。
     */
    fun translate(raw: String?): String {
        val kind = kindOf(raw) ?: return raw ?: ""
        // 非文本：统一占位符，绝不泄漏 XML。
        if (kind !in KNOWN_KINDS) {
            // 新类型：记日志，方便日后补表。这不影响用户看到的内容。
            Log.i(TAG, "envelope: unmapped non-text tag <" + kind + "> -> " + NON_TEXT)
        }
        return NON_TEXT
    }

    /**
     * 取一条消息的**类型标签**（`img` / `emoji` / `voicemsg` …）；普通文本返回 null。
     *
     * 【为什么显示已经统一了还要这个函数】识图链路靠它工作 ——
     * 它必须能判断"这条消息是不是图片"，才能决定去读哪个文件、要不要发识图请求。
     * 换句话说：[kindOf] 是**给程序用的判据**，[translate] 是**给人看的文本**，
     * 两者共用同一套解析，但不能互相替代。
     *
     * @return 非文本消息的类型标签（小写）；内容不是 XML 时返回 null。
     */
    fun kindOf(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || !s.startsWith("<")) return null
        val root = readTag(s, 0) ?: return null
        /* 关键的两层结构：微信把消息包在 <msg> 外壳里，类型是它的第一个子标签。
         * 少数消息（如某些系统消息）不走外壳，此时根标签本身就是类型。 */
        return if (root.name == WRAPPER) {
            readTag(s, root.end)?.name ?: WRAPPER
        } else {
            root.name
        }
    }

    /** 这条消息是不是图片 —— 识图链路的主判据。 */
    fun isImage(raw: String?): Boolean = kindOf(raw) == KIND_IMAGE

    /** 这条消息是不是表情包。见 [KIND_EMOJI] 关于"当前读不出来"的说明。 */
    fun isEmoji(raw: String?): Boolean = kindOf(raw) == KIND_EMOJI

    /** 一个已解析出的标签：[name] 是标签名（小写），[end] 是其闭合 `>` 之后的下标。 */
    private class Tag(val name: String, val end: Int)

    /**
     * 从 [from] 开始读第一个标签，返回标签名与它结束的位置；读不到返回 null。
     *
     * 手写扫描而不是用 `XmlPullParser`：这里每次消息绑定都会跑，
     * 而起一个解析器实例去读前 20 个字符的开销完全不成比例。
     * 也**故意不追求完整 XML 解析** —— 我们要的只是"这个标签叫什么"，
     * 面对畸形/截断的内容必须能安全返回 null，而不是抛异常打断消息绑定。
     *
     * 会跳过：前导空白、`<?xml ... ?>` 声明、`<!-- 注释 -->`、以及闭合标签。
     * 自闭合标签（`<img ... />`，微信里很常见）不需要特殊处理 ——
     * 我们只读标签名和它 `>` 的位置，不关心它是否自闭合。
     */
    private fun readTag(s: String, from: Int): Tag? {
        var i = from
        while (i < s.length) {
            // 找到下一个 '<'
            while (i < s.length && s[i] != '<') i++
            if (i >= s.length) return null
            // 跳过 <?xml ... ?>
            if (i + 1 < s.length && s[i + 1] == '?') {
                val e = s.indexOf("?>", i)
                if (e < 0) return null
                i = e + 2
                continue
            }
            // 跳过 <!-- ... -->
            if (s.startsWith("<!--", i)) {
                val e = s.indexOf("-->", i)
                if (e < 0) return null
                i = e + 3
                continue
            }
            // 闭合标签 </msg>：跳过它继续找（正常情况下 <msg> 外壳里不应出现，
            // 但畸形内容可能出现，跳过比直接失败更稳）
            if (i + 1 < s.length && s[i + 1] == '/') {
                val e = s.indexOf('>', i)
                if (e < 0) return null
                i = e + 1
                continue
            }
            // 读标签名
            val start = i + 1
            var j = start
            while (j < s.length &&
                (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '-' || s[j] == ':')
            ) {
                j++
            }
            if (j == start) {
                // `<` 后面不是合法标签名（例如 "价格是 <100 元" 这种普通文本），
                // 返回 null = 当普通文本处理。这一条保护了本函数的热路径安全性。
                return null
            }
            val name = s.substring(start, j).lowercase()
            // 找到这个标签的 '>'，作为"下一个标签从哪开始"的起点。
            // 属性值里也可能出现 '>'（例如 cdnthumburl="a>b"），用第一个 '>' 会
            // 让起点略偏 —— 但标签名已经正确拿到，所以不影响本函数的结果。
            val gt = s.indexOf('>', j)
            return Tag(name, if (gt < 0) s.length else gt + 1)
        }
        return null
    }
}
