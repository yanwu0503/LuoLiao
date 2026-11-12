package com.luoliaoo.wechat.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v10.1.0: 引用消息解析。
 *
 * 【样本全部来自真机】下面引用的 XML 是用户设备上 `wxid_1jgcxxhf4r0612.json`（msgId 16077415）
 * 的**原文**，不是我编的。用真实 payload 做测试是关键：我手写过"看起来合理"的假样本，
 * 它不会暴露真实数据里的转义、截断和多余字段。
 */
class WeChatQuoteTest {

    /** 真机原文：msgId=16077415，`<title>仓库配货打包员</title>`，引用「面试什么岗位」。 */
    private val realQuote = "<msg><appmsg><title>仓库配货打包员</title><action>view</action>" +
        "<type>57</type><finderLiveProductShare><isPriceBeginShow>false</isPriceBeginShow>" +
        "</finderLiveProductShare><gameshare><appbrandext><priority>-1</priority>" +
        "<appbrandext><duration>-1</duration></appbrandext></gameshare><refermsg>" +
        "<type>1</type><svrid>8170701947596618460</svrid>" +
        "<fromusr>wxid_1jgcxxhf4r0612</fromusr><chatusr>wxid_1jgcxxhf4r0612</chatusr>" +
        "<displayname>瑞仓财务-小丹</displayname><content>面试什么岗位</content>" +
        "<msgsource>&lt;msgsource&gt;&#x0A;&#x09;&lt;bizflag&gt;0&lt;/bizflag&gt;&#x0A;&lt;/msgsource&gt;</msgsource>" +
        "<createtime>1790412691</createtime></refermsg></appmsg></msg>"

    @Test
    fun `parses the real device quote into the requested format`() {
        // 用户明确给出的期望格式，逐字符对齐。
        assertEquals("[引用]仓库配货打包员\"引用：面试什么岗位\"", WeChatQuote.parse(realQuote))
    }

    @Test
    fun `uses the title as the name, not displayname`() {
        // 需求以用户给的样例为准：样例里的人是「仓库配货打包员」，它在数据里只出现在 <title>；
        // <displayname> 是「瑞仓财务-小丹」，是另一个语义（被引用者昵称），**不该**出现在结果里。
        val out = WeChatQuote.parse(realQuote)!!
        assertTrue("应含 <title> 的值", out.contains("仓库配货打包员"))
        assertFalse("不应含 <displayname> 的值", out.contains("瑞仓财务-小丹"))
    }

    @Test
    fun `does not leak the msgsource escaping into the output`() {
        val out = WeChatQuote.parse(realQuote)!!
        assertFalse("不应出现转义残留", out.contains("&lt;"))
        assertFalse("不应出现数字实体残留", out.contains("&#x0A;"))
        assertFalse("不应出现 msgsource 噪声", out.contains("msgsource"))
        assertFalse(out.contains("bizflag"))
    }

    @Test
    fun `plain text is not a quote`() {
        assertNull(WeChatQuote.parse("面试什么岗位"))
        assertNull(WeChatQuote.parse(""))
        assertNull(WeChatQuote.parse(null))
        assertFalse(WeChatQuote.looksLikeQuote("你好"))
    }

    @Test
    fun `voice xml is not a quote`() {
        // 语音消息的 XML 同样非空，但它没有 <refermsg>，绝不能被当成引用。
        val voice = "<msg><voicemsg endflag=\"1\" voiceformat=\"4\" aeskey=\"abc\" " +
            "voiceurl=\"7f0c\" isPlayed=\"true\"></voicemsg></msg>"
        assertNull(WeChatQuote.parse(voice))
        assertFalse(WeChatQuote.looksLikeQuote(voice))
    }

    @Test
    fun `image xml is not a quote`() {
        val img = "<?xml version=\"1.0\"?><msg><img aeskey=\"690b\" length=\"568235\"></img></msg>"
        assertNull(WeChatQuote.parse(img))
    }

    @Test
    fun `truncated refermsg is still tolerated`() {
        // 真机上存在被截断的引用（用户的 msgId 16077416 就是这样：content 之后没有闭合标签）。
        // 需求是"尽可能解析出来"，而不是"结构必须完美" —— 一条坏消息不该让整行消失。
        val truncated = "<msg><appmsg><title>某个群</title><type>57</type><refermsg>" +
            "<type>1</type><displayname>张三</displayname><content>好的，下午大概几点方便？</content>"
        assertEquals("[引用]某个群\"引用：好的，下午大概几点方便？\"", WeChatQuote.parse(truncated))
    }

    @Test
    fun `decodes xml entities in the quoted body`() {
        val q = "<msg><appmsg><title>群名</title><refermsg>" +
            "<content>a &lt; b &amp; c &gt; d</content></refermsg></appmsg></msg>"
        assertEquals("[引用]群名\"引用：a < b & c > d\"", WeChatQuote.parse(q))
    }

    @Test
    fun `amp is decoded last so double-escaping is not collapsed twice`() {
        // `&amp;lt;` 本意是字面量 "&lt;"。若先替换 &amp; 就会被多还原一层成 "<"，属于数据失真。
        val q = "<msg><appmsg><title>群</title><refermsg>" +
            "<content>&amp;lt;tag&amp;gt;</content></refermsg></appmsg></msg>"
        assertEquals("[引用]群\"引用：&lt;tag&gt;\"", WeChatQuote.parse(q))
    }

    @Test
    fun `quote without a title still shows the body`() {
        // 单聊引用可能没有群名/标题，此时不该留下一个空引号或半个 [引用]。
        val q = "<msg><appmsg><refermsg><content>只是一句话</content></refermsg></appmsg></msg>"
        assertTrue(WeChatQuote.parse(q)!!.contains("只是一句话"))
        assertFalse(WeChatQuote.parse(q)!!.contains("\"\""))
    }

    @Test
    fun `quote with an empty body is rejected`() {
        // 正文是必需的：没有它这条引用毫无信息量，应让调用方退化成占位符而不是写一行空引用。
        val q = "<msg><appmsg><title>群</title><refermsg><content></content></refermsg></appmsg></msg>"
        assertNull(WeChatQuote.parse(q))
    }

    @Test
    fun `does not mistake an outer title for the refermsg content`() {
        // 取值必须限定在 <refermsg> 段内。这里 refermsg 之外也有一个 <content>，
        // 若实现改为"全文找第一个 content"就会抓错。
        val q = "<msg><appmsg><title>群</title><content>外层噪声</content><refermsg>" +
            "<content>真正的引用</content></refermsg></appmsg></msg>"
        assertEquals("[引用]群\"引用：真正的引用\"", WeChatQuote.parse(q))
    }

    @Test
    fun `never throws on damaged input`() {
        // 健壮性：任何畸形输入都只能返回 null，绝不能抛 —— 这个函数在写本地记录的热路径上，
        // 抛一次就会让那条消息整条丢失。
        for (bad in listOf("<refermsg>", "<refermsg><content>", "<</>>", "<refermsg></refermsg>",
            "<refermsg><content>abc", "\u0000<refermsg>", "<msg><appmsg>")) {
            try {
                WeChatQuote.parse(bad)
            } catch (t: Throwable) {
                throw AssertionError("parse() 不该抛异常，输入=" + bad, t)
            }
        }
    }

    // ================================================================================
    // v10.1.0 回归：多选「显示 包含屏蔽词」
    // ================================================================================

    @Test
    fun `formatted quote is NOT caught by the built-in msg block word`() {
        /* 【这条测试锁的是用户报的那个 bug】原话：
         *     「多选状态下的引用有问题 显示 包含屏蔽词」
         *
         * 【完整链路】多选分析是「先过滤、再弹窗」：
         *     describeMessages(selected)            -> 每行 "我/对方：<文本>"
         *     MenuHook 用 PromptBuilder 筛屏蔽词     -> 命中则丢弃
         *     全部被丢 -> toast「选中的消息均包含屏蔽词」
         *
         * 引用消息的**原始 content** 是 `<msg><appmsg>...`，含 "msg"，而 "msg" 是内置屏蔽词；
         * 所以只要 describeMessages 吐的是 XML，**每一条引用都会在这一步被杀掉**，
         * 用户看到的就是"均包含屏蔽词"。
         *
         * 修法是在源头（describeMessages -> displayText）把 XML 换成 `[引用]…` 正文。
         * 这个测试直接断言"换出来的文本不会被屏蔽词命中"，把修法的**目的**钉住 ——
         * 只断言"解析结果字符串相等"是不够的，那样即使屏蔽词又把它吃了测试也照样绿。
         */
        val line = "对方：" + WeChatQuote.parse(realQuote)!!
        val words = com.luoliaoo.wechat.ai.PromptBuilder.SKIP_MARKERS
        assertFalse(
            "格式化后的引用不该被内置屏蔽词命中（否则多选会报「均包含屏蔽词」）",
            com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(line),
        )
        assertFalse(
            "用户模板里的词也不该命中它",
            com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(line, words),
        )
    }

    @Test
    fun `the RAW quote xml WOULD be caught -- which is why the source fix is required`() {
        // 反证：同一条消息若不经 displayText 而直接用原始 content，必然被屏蔽词吃掉。
        // 这条测试解释了"为什么必须在源头修"，也是为了在将来有人把 displayText 改回 content() 时立刻失败。
        assertTrue(
            "原始 XML 含 <msg>，必然命中内置屏蔽词 msg",
            com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(realQuote),
        )
    }

    @Test
    fun `a user block word still applies to the formatted quote`() {
        // 修法不能把用户自己的屏蔽词也一起废掉：屏蔽词必须仍然作用在**正文**上。
        // 用户在引用正文里写了「面试」，就该能挡住它。
        val line = "对方：" + WeChatQuote.parse(realQuote)!!
        assertTrue(
            "用户自定义屏蔽词应仍能命中引用正文",
            com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(line, listOf("面试")),
        )
    }
}
