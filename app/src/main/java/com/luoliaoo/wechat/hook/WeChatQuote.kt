package com.luoliaoo.wechat.hook

/**
 * v10.1.0: 微信「引用」消息的解析。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════
 * 【用户的需求】本地记录里，引用消息是一整段 XML；用户要求把它显示成人能读的一行：
 *
 *     [引用]仓库配货打包员"引用：面试什么岗位"
 *
 * 真机数据（`wxid_1jgcxxhf4r0612.json`，msgId 16077415）原文：
 *
 *     <msg><appmsg><title>仓库配货打包员</title><action>view</action><type>57</type>
 *       ...
 *       <refermsg>
 *         <type>1</type>
 *         <svrid>8170701947596618460</svrid>
 *         <fromusr>wxid_1jgcxxhf4r0612</fromusr>
 *         <chatusr>wxid_1jgcxxhf4r0612</chatusr>
 *         <displayname>瑞仓财务-小丹</displayname>
 *         <content>面试什么岗位</content>
 *         <msgsource>...</msgsource>
 *         <createtime>1790412691</createtime>
 *       </refermsg>
 *     </appmsg></msg>
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════
 * 【两个名字的区别 —— 这是最容易搞错的地方】
 *
 * 引用消息里有**两个**不同的人名，用户要的是第一个：
 *
 *   * `<title>`        = 「仓库配货打包员」。这是**引用消息自身**的标题，
 *                        通常是被引用内容所在的**群名/来源名**。**用户要求显示的就是它。**
 *   * `<displayname>`  = 「瑞仓财务-小丹」。这是**被引用的那个发言人的昵称**。
 *
 * 我一开始按直觉想用 displayname（"被引用的人"听起来才对），但用户明确写出的期望格式是
 * `[引用]仓库配货打包员"..."`，而 仓库配货打包员 在数据里**只出现在 `<title>`**。
 * 所以按 `<title>` 实现 —— 需求以用户给的样例为准，不以我的语义直觉为准。
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════
 * 【为什么手写解析而不是用 XML 库】
 *
 *   1. 微信的这段"XML"**并不总是合法 XML**。实测里 `<msgsource>` 的内容是被转义过的
 *      一整段带 `&#x0A;` 换行的 XML，而且不同版本还出现过 **截断的、未闭合的** 标签
 *      （用户这段同样引用的另一条消息 msgId 16077416 就已经被截断）。
 *      交给 XmlPullParser 遇到结构损坏会直接抛异常 —— 那样**一条坏消息就能让整行消失**。
 *   2. 我们只需要两个字段，正则取值的容错性远好于"必须整棵树合法"。
 *   3. 模块刻意保持零额外依赖（见 build.gradle.kts 的依赖说明）。
 *
 * 所以这里全程用正则 + 容错：**任何一个字段取不到都要能优雅退化**，绝不抛异常。
 * ══════════════════════════════════════════════════════════════════════════════════════════
 */
object WeChatQuote {

    /** 引用消息的显示前缀。与用户给的样例一致。 */
    const val PREFIX = "[\u5f15\u7528]"   // [引用]

    /** 用户样例里的字面前缀，位于被引用正文之前：`[引用]名字"引用：正文"`。 */
    private const val QUOTE_LABEL = "\u5f15\u7528\uff1a"   // 引用：

    /**
     * 微信 appmsg 里表示「引用」的 type 值。
     *
     * 57 = 引用（quote）。这个数字来自**真机数据本身**（`<type>57</type>` 紧跟在 `<title>` 之后），
     * 不是猜的；但它属于微信的内部分类，不同版本并非绝对稳定，所以 [parse] **不单独依赖它** ——
     * 真正的判据是「有没有 `<refermsg>` 段」，那才是引用消息的结构特征。
     * 这个常量只用于日志与快速预判。
     */
    const val APPMSG_TYPE_QUOTE = 57

    /** 判据用的结构标记：有它才是引用消息。 */
    private const val MARK_REFER = "<refermsg>"

    /**
     * 这条消息的正文看起来是不是一条「引用」消息。
     *
     * 【为什么用结构特征而不是 type 数字】`<refermsg>` 是引用消息**独有**的结构，
     * 而 `<type>57</type>` 只是一个可能随版本变化的分类号。以结构为准，
     * 版本升级时更不容易失效；两者都满足时当然更稳，但缺一时不该误判为空。
     */
    fun looksLikeQuote(content: String?): Boolean {
        val c = content ?: return false
        return c.contains(MARK_REFER)
    }

    /**
     * 把引用消息解析成 `[引用]标题"引用：正文"`。
     *
     * @return 解析成功时返回格式化文本；**不是引用消息 / 缺关键字段 / 结构损坏**时返回 null，
     *         由调用方决定退化行为（当前设计：[MsgAccess.displayText] 会退回占位符，
     *         绝不把原始 XML 交给用户或模型）。
     */
    fun parse(content: String?): String? {
        val raw = content ?: return null
        if (!looksLikeQuote(raw)) return null

        return try {
            val title = tagText(raw, "title")
            val body = referContent(raw)

            // 正文是**必须**的：没有它这条引用就没有信息量，不如让调用方退化。
            // 注意要排除掉"正文其实是另一段 XML/空"的情况 —— 那说明结构不是我们理解的这样。
            val text = body?.let { decodeEntities(it) }?.trim().orEmpty()
            if (text.isBlank() || looksLikeMarkup(text)) return null

            // 标题可以为空（例如引用来自单聊时没有群名），此时只显示正文，不要留一个空引号。
            val who = title?.let { decodeEntities(it) }?.trim().orEmpty()
            if (who.isBlank()) {
                PREFIX + "\"" + QUOTE_LABEL + text + "\""
            } else {
                PREFIX + who + "\"" + QUOTE_LABEL + text + "\""
            }
        } catch (_: Throwable) {
            // 解析失败不是异常情况 —— 微信的结构随版本变化，退化为 null 让调用方处理。
            null
        }
    }

    /**
     * 取 `<refermsg>` 段里的 `<content>`，也就是**被引用的那句原文**。
     *
     * 必须限定在 refermsg 段内查找：appmsg 外层还有一个 `<title>`，
     * 而外层结构里并没有第二个 content —— 但限定范围能让"将来外层多出 content"时不抓错。
     */
    private fun referContent(raw: String): String? {
        val start = raw.indexOf(MARK_REFER)
        if (start < 0) return null
        val end = raw.indexOf("</refermsg>", start)
        // 允许没有闭合标签（实测存在被截断的引用），此时取到字符串末尾即可。
        val seg = if (end > start) raw.substring(start, end) else raw.substring(start)
        return tagText(seg, "content")
    }

    /**
     * 取某个标签的文本内容；取不到返回 null。
     *
     * 用「最近的 `<tag>`」而不是「第一个」：同名的标签在嵌套结构里可能出现多次，
     * 而我们总是想要当前这一段里最贴身的那个。
     */
    private fun tagText(segment: String, tag: String): String? {
        val open = "<" + tag + ">"
        val i = segment.indexOf(open)
        if (i < 0) return null
        val from = i + open.length
        val close = "</" + tag + ">"
        val j = segment.indexOf(close, from)
        // 没有闭合标签时视为结构损坏，返回 null（不要冒险截到别的标签里去）。
        if (j < 0) return null
        return segment.substring(from, j)
    }

    /**
     * 反转义 XML 实体。
     *
     * 微信会把引用原文里的特殊字符转义存进去。真机见过的有 `&lt; &gt; &amp; &quot; &apos;`
     * 以及**数字实体** `&#x0A;`（换行）—— 后者在 `<msgsource>` 里大量出现。
     * 不还原的话用户会看到 `&#x0A;` 这种噪声。
     *
     * 顺序很重要：`&amp;` 必须**最后**处理。反过来的话，
     * `&amp;lt;`（本意是字面量 "&lt;"）会被先还原成 `&lt;`、再被还原成 `<`，多转了一层。
     */
    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        var out = s
        out = out.replace("&lt;", "<").replace("&gt;", ">")
        out = out.replace("&quot;", "\"").replace("&apos;", "'")
        out = out.replace(NUMERIC_ENTITY) { m ->
            try {
                val hex = m.groupValues[1]
                val cp = if (hex.isNotEmpty()) hex.toInt(16) else m.groupValues[2].toInt()
                // 控制字符会让这一行在 JSON/日志里变形，用空格兜住；其余按码点还原。
                val ch = String(Character.toChars(cp))
                if (cp < 0x20 && ch != "\n" && ch != "\t") " " else ch
            } catch (_: Throwable) {
                m.value
            }
        }
        out = out.replace("&amp;", "&")
        return out
    }

    /** `&#x0A;` / `&#10;` 两种数字实体形式。组 1 = 十六进制，组 2 = 十进制。 */
    private val NUMERIC_ENTITY = Regex("&#x([0-9A-Fa-f]+);|&#([0-9]+);")

    /** 还原后若还剩成片的标签，说明这根本不是"人能读的一句话"（多半是解析错位）。 */
    private fun looksLikeMarkup(text: String): Boolean =
        text.startsWith("<") && text.endsWith(">")
}
