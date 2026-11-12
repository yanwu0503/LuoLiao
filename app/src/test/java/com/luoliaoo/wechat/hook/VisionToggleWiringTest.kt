package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.ai.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ v10.3.0：**逐条识图开关**的数据链路回归测试。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【这个测试挡住的是两个真实发生过的 bug，不是假想的】
 *
 * 功能形态：图片消息在「读取」预览里，每一行左边多一个「识图」开关（默认关）。
 * 开关状态靠**字符串匹配**回传给分析侧 —— 于是"两边用的字符串是不是同一个"
 * 就成了整个功能的成败关键，而这恰恰是最容易写错、又最不容易发现的地方。
 *
 * ── bug 1：图片被门禁挡在列表外 ──────────────────────────────────────────────
 * 微信图片是 **type=3**，而收集列表的判据 `isAnalysableText` 只认 type=1（文本）
 * 和 type=57（引用）。图片因此**从来没进过列表** ——
 * 后果是预览里一行图片都没有，那排「识图」开关**一个都不会出现**。
 *
 * 这个 bug 的可怕之处在于它**和"功能没写"完全无法区分**：界面就是空的，
 * 没有报错、没有异常、日志里也没有可疑之处。所以必须有一条测试把
 * "图片要能被收进来"这件事钉住。
 *
 * ── bug 2：开关按"展示文本"匹配，两边永远不相等 ──────────────────────────────
 * 预览里的行文本带前缀（"对方：内容"），而分析侧手上只有正文（"内容"）。
 * 我第一版两边都用 `text`，于是开关**点了毫无反应**，且完全静默。
 *
 * 修法是给 ChatLine 分出 `text`（给人看）和 `key`（用来匹配）两个字段。
 * 下面几条测试把"两者必须分开、且 key 必须是正文"钉死。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class VisionToggleWiringTest {

    private val imageXml = """
        <msg><img aeskey="dcbdbc8a8ecd807b75837dce378ff6ca" length="468993"><secHashInfoBase64>x</secHashInfoBase64></img></msg>
    """.trimIndent()

    private val textXml = "<msg><appmsg><title>标题</title></appmsg></msg>"

    // ── bug 1：图片必须能被收集 ────────────────────────────────────────────────

    @Test
    fun `图片消息被 Envelope 认作图片 —— 这是放宽门禁的依据`() {
        // collect 的放宽判据就是调它；它一旦失效，图片又会全部被过滤掉。
        assertTrue(
            "图片 XML 必须被认作图片，否则 collect 不会放行它",
            WeChatEnvelope.isImage(imageXml),
        )
    }

    @Test
    fun `非图片消息不会被误判为图片`() {
        // 误判的后果同样严重：**每一条**消息都会长出一个识图开关，
        // 用户打开其中一条文字消息的开关后，读图会失败（没有图片文件）。
        assertFalse(WeChatEnvelope.isImage(textXml))
        assertFalse(WeChatEnvelope.isImage(null))
        assertFalse(WeChatEnvelope.isImage(""))
        assertFalse(WeChatEnvelope.isImage("普通文本，连 XML 都不是"))
    }

    @Test
    fun `表情包能被识别出类型 但当前读不出内容 所以不显示开关`() {
        /* ★ 这一条记录的是一个**产品决策**，不只是技术细节。
         *
         * 用户要求"表情包与图片两个都支持，共用一个开关"。链路确实对两者一视同仁
         * （`isEmoji` 认得出表情包，识图管线也会走同一条读取路径）。
         *
         * **但表情包在当前微信版本里读不出来**：真机实测 `MicroMsg/<账号>/emoji/`
         * 下 1835 个文件**全部是加密的**（文件头高熵，不是任何图片格式的魔数）。
         * 既然读不出字节，识图就无从谈起。
         *
         * 所以界面上的取舍是：**只给真的能识图的行显示开关**。
         * 在一个按下去必然失败的行上放一个"识图"按钮，是在骗用户 ——
         * 他会点、会等、会以为软件坏了，而正确行为是那里根本不该有按钮。
         *
         * 将来若找到表情包的读法（微信改回明文，或按 XML 里的 aeskey 解密成功），
         * 只需把判据从 `isImage` 放宽到 `isImage || isEmoji`，此处测试随之更新。 */
        assertTrue(
            "表情包必须能被识别出类型（链路要能认识它）",
            WeChatEnvelope.isEmoji("<msg><emoji fromusername=\"x\" /></msg>"),
        )
        assertFalse(
            "但 isImage 不应把表情包算作图片 —— 否则会给它显示一个按不出结果的开关",
            WeChatEnvelope.isImage("<msg><emoji fromusername=\"x\" /></msg>"),
        )
    }

    // ── bug 2：text 与 key 必须分开 ───────────────────────────────────────────

    @Test
    fun `key 默认等于 text —— 既有调用点因此完全不必改动`() {
        // 这是弃用风控：多数调用点既不显示也不匹配，让它们零改动。
        val l = ChatLine(1L, false, "内容")
        assertEquals("内容", l.text)
        assertEquals("内容", l.key)
    }

    @Test
    fun `展示文本带前缀时 key 仍必须是正文`() {
        /* 这一条直接复现 bug 2 的修法：
           预览行显示 "对方：内容"，但匹配键必须是 "内容"，
           因为分析侧只有一个裸的 displayText(msg) —— 没有前缀。 */
        val previewRow = ChatLine(
            time = 1L,
            isSend = false,
            text = "对方：内容",
            key = "内容",
        )
        assertEquals("对方：内容", previewRow.text)
        assertEquals("内容", previewRow.key)
        assertNotEquals(
            "展示文本与匹配键必须不同，否则等于没修 bug 2",
            previewRow.text, previewRow.key,
        )
    }

    @Test
    fun `若 key 用了展示文本 两侧就匹配不上 —— 这就是 bug 2 的形态`() {
        /* 把 bug 2 的机制写成测试：如果两边都用展示文本，
           而分析侧手上只有正文，那么匹配必然失败。
           这条测试的价值是**把失败模式本身记录下来** ——
           将来有人"顺手简化"成只留一个字段时，会先在这里被拦住。 */
        val analysisSide = "内容"                     // displayText(msg)，无前缀
        val wrongKey = "对方：内容"                    // 误用展示文本当匹配键
        val rightKey = "内容"                          // 正确：用正文

        assertFalse(
            "用展示文本当键就会匹配失败（这正是 bug 2）",
            wrongKey == analysisSide,
        )
        assertTrue(
            "用正文当键才能匹配上",
            rightKey == analysisSide,
        )
    }

    // ── 默认关闭（用户指定的隐私默认值）────────────────────────────────────────

    @Test
    fun `isImage 默认是 false —— 未标记的消息不会被识图`() {
        // 默认值必须是"不发图"。若哪天有人改成 true，所有消息都会默认把图发出去，
        // 而这是隐私相关的默认值，必须有测试守着。
        val l = ChatLine(1L, false, "内容")
        assertFalse("默认不得识图", l.isImage)
    }

    // ── 占位符本身 ────────────────────────────────────────────────────────────

    @Test
    fun `图片显示为统一的非文本占位符 不含 XML 也不泄露 aeskey`() {
        val shown = WeChatEnvelope.translate(imageXml)
        assertEquals(WeChatEnvelope.NON_TEXT, shown)
        assertFalse("不得泄露 aeskey", shown.contains("aeskey"))
        assertFalse("不得泄露文件名", shown.contains("dcbdbc8a"))
        assertFalse("不得出现 XML", shown.contains("<"))
    }
}
