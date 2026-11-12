package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.TextPolishParser
import com.luoliaoo.wechat.ai.TextPolishPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextPolishParser 的容错测试。
 *
 * 这一层的硬约束有两条，都直接对应真机上的失败模式：
 *   1. **永不抛异常** —— 长按悬浮球触发的流程一旦抛出去会把聊天界面带崩；
 *   2. **最多两条** —— 「提出两个优化方案」是需求，解析出三条以上属于违约。
 */
class TextPolishTest {

    @Test
    fun parsesCleanJsonWithTwoOptions() {
        val raw = """
        {
          "options": [
            {"title":"更自然口语","text":"我今晚可能过不去啦，改天请你吃饭","reason":"把生硬的说法改软了"},
            {"title":"更正式得体","text":"今晚恐怕无法赴约，改日再约时间","reason":"改为正式的书面表达"}
          ]
        }
        """.trimIndent()

        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("更自然口语", out[0].title)
        assertTrue(out[0].text.contains("今晚"))
        assertEquals("改为正式的书面表达", out[1].reason)
    }

    @Test
    fun stripsMarkdownFence() {
        val raw = """
        ```json
        {"options":[{"title":"A","text":"第一条"},{"title":"B","text":"第二条"}]}
        ```
        """.trimIndent()
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("第一条", out[0].text)
        assertEquals("第二条", out[1].text)
    }

    @Test
    fun survivesPreambleAndTrailing() {
        val raw = "好的，这是两个版本：\n" +
            "{\"options\":[{\"title\":\"A\",\"text\":\"甲\"},{\"title\":\"B\",\"text\":\"乙\"}]}\n" +
            "希望有帮助！"
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("甲", out[0].text)
    }

    @Test
    fun acceptsBareArray() {
        val out = TextPolishParser.parse("[{\"text\":\"甲\"},{\"text\":\"乙\"}]")
        assertEquals(2, out.size)
    }

    @Test
    fun acceptsSingleObjectAsOneOption() {
        val out = TextPolishParser.parse("{\"title\":\"唯一方案\",\"text\":\"就这么说\"}")
        assertEquals(1, out.size)
        assertEquals("就这么说", out[0].text)
    }

    @Test
    fun acceptsChineseKeys() {
        val raw = "{\"方案\":[{\"标题\":\"温柔\",\"内容\":\"甲\"},{\"标题\":\"直接\",\"内容\":\"乙\"}]}"
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("温柔", out[0].title)
        assertEquals("乙", out[1].text)
    }

    /** The model answered with two labelled paragraphs instead of JSON: the user must not lose them. */
    @Test
    fun splitsLabelledPlainText() {
        val raw = """
        方案一：我今晚可能过不去啦，改天请你吃饭
        方案二：今晚恐怕无法赴约，改日再约
        """.trimIndent()
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("我今晚可能过不去啦，改天请你吃饭", out[0].text)
        assertEquals("今晚恐怕无法赴约，改日再约", out[1].text)
    }

    @Test
    fun splitsNumberedPlainText() {
        val raw = "1. 第一条内容\n2. 第二条内容"
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("第一条内容", out[0].text)
        assertEquals("第二条内容", out[1].text)
    }

    @Test
    fun splitsOnBlankLineWhenUnlabelled() {
        val raw = "甲说法，简短一点\n\n乙说法，正式一点"
        val out = TextPolishParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals("甲说法，简短一点", out[0].text)
        assertEquals("乙说法，正式一点", out[1].text)
    }

    /** 一段没有分隔的纯文本只能算一个方案 —— 硬造第二条只会给用户看一个重复项。 */
    @Test
    fun singleParagraphStaysSingleOption() {
        val out = TextPolishParser.parse("就这一句话，没有第二个版本")
        assertEquals(1, out.size)
        assertEquals("就这一句话，没有第二个版本", out[0].text)
    }

    @Test
    fun truncatesToAtMostTwo() {
        val raw = """
        {"options":[
          {"title":"A","text":"甲"},
          {"title":"B","text":"乙"},
          {"title":"C","text":"丙"},
          {"title":"D","text":"丁"}
        ]}
        """.trimIndent()
        val out = TextPolishParser.parse(raw)
        assertEquals(TextPolishParser.MAX_OPTIONS, out.size)
        assertEquals("甲", out[0].text)
        assertEquals("乙", out[1].text)
    }

    @Test
    fun dropsOptionsWithoutText() {
        val out = TextPolishParser.parse("{\"options\":[{\"title\":\"空\"},{\"title\":\"B\",\"text\":\"有效\"}]}")
        assertEquals(1, out.size)
        assertEquals("有效", out[0].text)
    }

    @Test
    fun emptyAndJunkNeverThrow() {
        assertEquals(0, TextPolishParser.parse(null).size)
        assertEquals(0, TextPolishParser.parse("").size)
        assertEquals(0, TextPolishParser.parse("   ").size)
        assertEquals(0, TextPolishParser.parse("{ this is not json ]").size)
        assertEquals(0, TextPolishParser.parse("{\"options\":[]}").size)
        assertTrue(TextPolishParser.parse("😀😀😀").size <= 2)
    }

    /** 超长输入也必须活着回来，且不超过两条。 */
    @Test
    fun hugeInputIsBounded() {
        val raw = "方案一：" + "甲".repeat(5000) + "\n方案二：" + "乙".repeat(5000)
        val out = TextPolishParser.parse(raw)
        assertTrue(out.size <= 2)
    }

    // ------------------------------------------------------------------ prompts

    @Test
    fun promptOmitsBackgroundWhenBlank() {
        val s = TextPolishPrompts.system("", "模板提示词")
        assertTrue(s.contains("模板提示词"))
        assertTrue(!s.contains("【补充背景】"))
        assertTrue(s.contains("恰好两个"))
    }

    @Test
    fun promptCarriesBackgroundWhenPresent() {
        val s = TextPolishPrompts.system("对方是我领导", "模板")
        assertTrue(s.contains("【补充背景】"))
        assertTrue(s.contains("对方是我领导"))
        // 背景必须被明确标注为「上下文」，否则模型会把它当成要发送的内容。
        assertTrue(s.contains("不是要发送的内容"))
    }

    @Test
    fun userPromptCarriesTheDraft() {
        val u = TextPolishPrompts.user("  我明天不来  ")
        assertTrue(u.contains("我明天不来"))
    }

    /**
     * v4.0.0: 补充背景 里写的字数要求必须被当成硬约束。
     *
     * 用户明确要求「可写 我想要XX个字左右 这样效果可能更好」，所以这句必须真的能影响提示词：
     * 有背景时提示词要显式把它标成硬性约束，否则模型只会把它当一句闲聊忽略掉。
     */
    @Test
    fun backgroundLengthRequirementIsHonoured() {
        val withBg = TextPolishPrompts.system("我想要 50 个字左右", "模板")
        assertTrue(withBg.contains("我想要 50 个字左右"))
        assertTrue(withBg.contains("硬性约束"))

        // 空背景时不应该出现任何「硬性约束」字样，避免模型凭空收紧长度。
        val noBg = TextPolishPrompts.system("", "模板")
        assertTrue(!noBg.contains("硬性约束"))
        assertTrue(!noBg.contains("补充背景"))
    }
}
