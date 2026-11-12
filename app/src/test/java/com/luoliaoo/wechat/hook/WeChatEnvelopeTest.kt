package com.luoliaoo.wechat.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeChatEnvelope] 的回归测试。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么这个测试必须存在】
 * 本文件的第一版有一个**真实上线过的 bug**：它按 **根标签** 查表，
 * 而微信把所有消息都包在 `<msg>` 外壳里 —— 真正的类型是它的**第一个子标签**。
 * 于是查表永远命中 `msg`（不在表里），全部落到兜底，
 * 用户在真机上看到**每一条**消息都是兜底占位符。
 *
 * 这个 bug 的教训是：**"表里没有"和"看错了层级"表现完全一样**，
 * 光看现象会误以为分类表写错了，从而在错误的层面反复修改。
 * 所以这里用**真机日志里逐字抄下来的 XML**做题面，把"层级"这件事钉死。
 *
 * 测试数据来源：OnePlus PLK110 上 `LuoLiao` 日志的 `PROMPT >>>` 输出，
 * 即 `MsgAccess.content()` 对一个图片消息的真实返回值。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class WeChatEnvelopeTest {

    /**
     * 真机上一条**图片消息**的 `field_content` 原文（截取了前段，属性值保留真实形状）。
     *
     * ⚠️ 注意结构：`<msg>` 是外壳，`<img>` 才是类型。
     */
    private val realImageXml = """
        <msg><img aeskey="dcbdbc8a8ecd807b75837dce378ff6ca" encryver="1" cdnthumbaeskey="dcbdbc8a8ecd807b75837dce378ff6ca" cdnthumburl="305f020100044b304902010002043ad5b13d02033d11ff0204c2d09b2402046ab96c79042463336561616331312d373335352d343339322d623233372d346361323532373462396661020405152a01020100040d004c511f000000000000000000" cdnthumblength="3261" cdnthumbheight="508" cdnthumbwidth="232" length="468993" hdlength="520218" md5="c0e24f8ebb9caa4e7a722432546fcc5f" originsourcemd5="365473b3b69c7310d39784ee3f83ed75"><secHashInfoBase64>eyJwaGFzaCI6IjEwMDAwMDEwMDAwMDEwMTAiLCJwZHFIYXNoIjoiZmIwNWVmMDE2ZmE3NTRiYjA0</secHashInfoBase64></img></msg>
    """.trimIndent()

    @Test
    fun `图片消息显示为统一的非文本占位符`() {
        // 显示统一：所有非文本类型都是同一个占位符（用户指定）。
        assertEquals(WeChatEnvelope.NON_TEXT, WeChatEnvelope.translate(realImageXml))
    }

    @Test
    fun `所有非文本类型显示完全相同`() {
        val xmls = listOf(
            "<msg><img aeskey=\"a\" /></msg>",
            "<msg><emoji fromusername=\"x\" /></msg>",
            "<msg><voicemsg voicelength=\"3\" /></msg>",
            "<msg><videomsg length=\"10\" /></msg>",
            "<msg><location x=\"1\" y=\"2\" /></msg>",
            "<msg><wcpayinfo sendertitle=\"x\" /></msg>",
        )
        for (x in xmls) {
            assertEquals(
                "对 " + x.take(30) + " 的显示应当统一",
                WeChatEnvelope.NON_TEXT, WeChatEnvelope.translate(x),
            )
        }
    }

    @Test
    fun `msg 外壳里的子标签仍能被识别出真实类型`() {
        // 显示统一了，但**类型识别必须保留** —— 识图链路靠它判断"这是不是图片"。
        assertEquals("img", WeChatEnvelope.kindOf("<msg><img aeskey=\"a\" /></msg>"))
        assertEquals("emoji", WeChatEnvelope.kindOf("<msg><emoji fromusername=\"x\" /></msg>"))
        assertEquals("voicemsg", WeChatEnvelope.kindOf("<msg><voicemsg voicelength=\"3\" /></msg>"))
    }

    @Test
    fun `isImage 是识图链路的主判据`() {
        assertTrue(WeChatEnvelope.isImage(realImageXml))
        assertTrue(WeChatEnvelope.isImage("<msg><img aeskey=\"a\" /></msg>"))
        // 表情包也走同一条读图路径（当前读不出来，见 WeChatEnvelope.KIND_EMOJI）。
        assertTrue(WeChatEnvelope.isEmoji("<msg><emoji fromusername=\"x\" /></msg>"))
        // 语音不是图片 —— 它有自己的转写链路，不能误判成图片去读文件。
        assertFalse(WeChatEnvelope.isImage("<msg><voicemsg voicelength=\"3\" /></msg>"))
        assertFalse(WeChatEnvelope.isImage("你好，在吗"))
    }

    @Test
    fun `没有 msg 外壳时根标签本身就是类型`() {
        assertEquals("sysmsg", WeChatEnvelope.kindOf("<sysmsg type=\"revokemsg\"></sysmsg>"))
        assertEquals(WeChatEnvelope.NON_TEXT, WeChatEnvelope.translate("<sysmsg type=\"revokemsg\"></sysmsg>"))
    }

    @Test
    fun `带 XML 声明和注释也能正确取到子标签`() {
        val xml = "<?xml version=\"1.0\"?>\n<!-- comment -->\n<msg><img aeskey=\"a\" /></msg>"
        assertEquals("img", WeChatEnvelope.kindOf(xml))
    }

    @Test
    fun `未知的非文本标签落到占位符而不是泄漏 XML`() {
        // 这是安全性要求：任何没覆盖到的新类型，都**不能**把原始 XML 显示给用户。
        val out = WeChatEnvelope.translate("<msg><brandnewthing secret=\"aeskey-value\" /></msg>")
        assertEquals(WeChatEnvelope.NON_TEXT, out)
        assertFalse("绝不能泄漏 XML 原文", out.contains("<"))
        assertFalse("绝不能泄漏 aeskey", out.contains("aeskey-value"))
    }

    @Test
    fun `普通文本一个字节都不受影响`() {
        // 本函数跑在消息绑定热路径上，普通文本必须原样返回 ——
        // 一旦它误伤普通文本，等于全工程的消息内容都被破坏。
        assertEquals("你好，在吗", WeChatEnvelope.translate("你好，在吗"))
        assertEquals("价格是 <100 元", WeChatEnvelope.translate("价格是 <100 元"))
        assertEquals("a < b > c", WeChatEnvelope.translate("a < b > c"))
        assertEquals("", WeChatEnvelope.translate(""))
        assertEquals("", WeChatEnvelope.translate(null))
        // 普通文本没有"类型"
        assertNull(WeChatEnvelope.kindOf("你好"))
        assertNull(WeChatEnvelope.kindOf(""))
        assertNull(WeChatEnvelope.kindOf(null))
    }

    @Test
    fun `畸形与截断的 XML 不抛异常`() {
        // 消息内容可能是被截断的（长消息、损坏的记录）。
        // 要求只有一个：**不抛** —— 异常会冒到微信的 onBindViewHolder 里并导致聊天页崩。
        val junk = listOf(
            "<msg><img aeskey=\"unclosed",
            "<",
            "<>",
            "<msg>",
            "</msg>",
            "<msg></msg>",
            "<msg><img ",
            "<msg><img/>",
        )
        for (j in junk) {
            WeChatEnvelope.translate(j)
            WeChatEnvelope.kindOf(j)
        }
    }

    @Test
    fun `属性值里的尖括号不会干扰识别`() {
        // cdnthumburl 这类属性值里可能出现 '>'，不能让扫描读错标签名。
        val xml = "<msg><img aeskey=\"a>b\" cdnthumburl=\"x>y\" /></msg>"
        assertEquals(WeChatEnvelope.NON_TEXT, WeChatEnvelope.translate(xml))
        assertEquals("img", WeChatEnvelope.kindOf(xml))
    }
}
