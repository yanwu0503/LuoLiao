package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.VisionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VisionLevel] 的回归测试。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么这个测试必须存在】
 *
 * 识图提示词有四档，每一档都要守住**同一条硬规则**。最容易出的错不是某一档写错，
 * 而是**某一档悄悄漏掉了硬规则** —— 例如极高档忘了写"不是图片就跳过"，
 * 于是那一档会把垃圾内容当描述送进主模型。这种错在界面上完全看不出来，
 * 只有对着四档逐个检查才能发现，所以由测试来固化。
 *
 * 用户明确要求了两条：
 *   1. 「如果是非图片请直接跳过必须跳过仅识别图片」
 *   2. 「配置视觉模型那边可以开视觉等级，不同等级不同提示词」
 * 下面分别对应。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class VisionLevelTest {

    @Test
    fun `四档提示词都必须包含 跳过 规则`() {
        // 这是用户的原话要求：「如果是非图片请直接跳过必须跳过仅识别图片」。
        // 四档一个都不能漏。
        for (lv in VisionLevel.ALL) {
            val p = VisionLevel.prompt(lv)
            assertTrue(
                "等级「" + VisionLevel.name(lv) + "」的提示词缺少 SKIP 规则",
                p.contains(VisionLevel.SKIP_TOKEN),
            )
            assertTrue(
                "等级「" + VisionLevel.name(lv) + "」没有说明「不是图片」的处理方式",
                p.contains("不是一张图片"),
            )
        }
    }

    @Test
    fun `四档提示词都必须禁止分析与推测`() {
        // 硬规则：识图模型看不到完整对话，它做的任何判断都会污染主模型。
        // 这条比"跳过"更隐蔽 —— 漏了它不会报错，只会让分析结果变得不可靠。
        for (lv in VisionLevel.ALL) {
            val p = VisionLevel.prompt(lv)
            assertTrue(
                "等级「" + VisionLevel.name(lv) + "」没有禁止分析",
                p.contains("不要分析"),
            )
        }
    }

    @Test
    fun `四档提示词互不相同 且详细度递增`() {
        val prompts = VisionLevel.ALL.map { VisionLevel.prompt(it) }
        // 两两不同：否则"等级"就是假的，用户选了高档却拿到低档的提示词。
        assertEquals(
            "四档提示词必须两两不同",
            prompts.size, prompts.toSet().size,
        )
        // 大致单调：越高档的提示词越长（要求越细自然写得越长）。
        // 用"严格递增"而不是"非递减"，因为两档一样长说明其中一档基本是复制粘贴的。
        for (i in 1 until prompts.size) {
            assertTrue(
                "「" + VisionLevel.name(VisionLevel.ALL[i]) + "」的提示词应当比" +
                    "「" + VisionLevel.name(VisionLevel.ALL[i - 1]) + "」更详细",
                prompts[i].length > prompts[i - 1].length,
            )
        }
    }

    @Test
    fun `高档要求逐字抄录 低档不要求`() {
        // 这是"等级真的在做事"的实质检查：只比长度可能被无意义的填充骗过。
        assertTrue(VisionLevel.prompt(VisionLevel.HIGH).contains("逐字"))
        assertTrue(VisionLevel.prompt(VisionLevel.EXTREME).contains("逐字"))
        // 低档只要求一句话
        assertTrue(VisionLevel.prompt(VisionLevel.LOW).contains("一句话"))
    }

    @Test
    fun `极高档额外要求标注看不清的部分`() {
        // 编造内容是本功能最危险的失效模式：捏造的文字会污染分析且用户无法察觉。
        // 极高档信息量最大、最容易被编造，所以必须显式要求标注不确定项。
        val p = VisionLevel.prompt(VisionLevel.EXTREME)
        assertTrue("极高档必须要求区分看清/看不清", p.contains("看不清"))
    }

    @Test
    fun `clamp 把越界值收敛到合法档位`() {
        assertEquals(VisionLevel.LOW, VisionLevel.clamp(VisionLevel.LOW))
        assertEquals(VisionLevel.EXTREME, VisionLevel.clamp(VisionLevel.EXTREME))
        // 越界（镜像文件被手改过、或旧版本写过别的值）
        assertEquals(VisionLevel.MEDIUM, VisionLevel.clamp(-1))
        assertEquals(VisionLevel.MEDIUM, VisionLevel.clamp(99))
        assertEquals(VisionLevel.MEDIUM, VisionLevel.clamp(Int.MAX_VALUE))
        assertEquals(VisionLevel.MEDIUM, VisionLevel.clamp(Int.MIN_VALUE))
    }

    @Test
    fun `name 与 costHint 对每个合法档位都有值`() {
        for (lv in VisionLevel.ALL) {
            assertTrue(VisionLevel.name(lv).isNotBlank())
            assertTrue(VisionLevel.costHint(lv).isNotBlank())
        }
        // 名字不能重复，否则界面上两个按钮长得一样、用户分不清。
        val names = VisionLevel.ALL.map { VisionLevel.name(it) }
        assertEquals("档位名必须两两不同", names.size, names.toSet().size)
    }

    @Test
    fun `isSkip 认得模型的各种跳过写法`() {
        assertTrue(VisionLevel.isSkip("SKIP"))
        assertTrue(VisionLevel.isSkip("skip"))
        assertTrue(VisionLevel.isSkip("  SKIP  "))
        assertTrue(VisionLevel.isSkip("SKIP."))
        assertTrue(VisionLevel.isSkip("(SKIP)"))
        assertTrue(VisionLevel.isSkip("**SKIP**"))
        assertTrue(VisionLevel.isSkip("SKIP。"))
    }

    @Test
    fun `isSkip 不会误杀正常描述`() {
        /* 这是本测试最重要的一条。
         *
         * 【为什么】如果 isSkip 的判定过宽（例如"包含 SKIP 就算"），
         * 那么一段**真实描述**里只要出现这个单词就会被整段丢弃 ——
         * 用户看到的是"图片没被识别"，而实际上模型看得很清楚。
         * 这种误杀比漏判难查得多：不报错，只是功能莫名其妙地不生效。
         *
         * 所以判定必须是"整段回复就等于 SKIP"，而不是"含有 SKIP"。 */
        assertFalse(VisionLevel.isSkip(null))
        assertFalse(VisionLevel.isSkip(""))
        assertFalse(VisionLevel.isSkip("   "))
        // 正常描述里出现 SKIP 这个词，**不能**被判为跳过
        assertFalse(VisionLevel.isSkip("图里写着 SKIP 字样"))
        assertFalse(VisionLevel.isSkip("SKIP 是一个英文单词，出现在这段描述里"))
        assertFalse(VisionLevel.isSkip("这是一张聊天截图，对方说了 SKIP"))
        // 普通描述
        assertFalse(VisionLevel.isSkip("一张风景照，有山和水"))
    }

    @Test
    fun `skip 标记本身是个稳定常量`() {
        // 提示词里写的与代码里认的必须是同一个串，否则模型回 "SKIP" 而我们不认。
        // 这里把两边的绑定钉死：改了一边忘了另一边，这个测试会失败。
        assertEquals("SKIP", VisionLevel.SKIP_TOKEN)
        for (lv in VisionLevel.ALL) {
            assertNotEquals(
                "提示词里必须确实写了这个标记",
                -1, VisionLevel.prompt(lv).indexOf(VisionLevel.SKIP_TOKEN),
            )
        }
    }
}
