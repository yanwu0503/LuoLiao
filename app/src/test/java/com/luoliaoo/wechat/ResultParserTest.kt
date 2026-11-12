package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ResultParser
import com.luoliaoo.wechat.data.AnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ResultParser 的容错测试 —— 模型输出不可控，解析必须永不崩溃 */
class ResultParserTest {

    @Test
    fun parsesCleanJson() {
        val raw = """
        {
          "intent": {
            "question": "她真的在问『你记不记得』吗？",
            "options": [{"label":"是","probability":7},{"label":"不是","probability":93}]
          },
          "real_intent": [
            {"label":"想确认你在不在乎她","probability":72},
            {"label":"单纯考验记忆力","probability":8},
            {"label":"生气想吵架","probability":20}
          ],
          "anger_level": 9,
          "comment": "语气偏质问"
        }
        """.trimIndent()

        val r = ResultParser.parse(raw)
        assertTrue(!r.isFallback)
        assertEquals(2, r.intentOptions.size)
        assertEquals("是", r.intentOptions[0].label)
        assertEquals(7, r.intentOptions[0].probability)
        assertEquals(3, r.realIntent.size)
        assertEquals(9, r.angerLevel)
        assertEquals("语气偏质问", r.comment)
    }

    @Test
    fun stripsMarkdownFence() {
        val raw = """
        ```json
        {"real_intent":[{"label":"试探","probability":80}],"anger_level":3}
        ```
        """.trimIndent()
        val r = ResultParser.parse(raw)
        assertTrue(!r.isFallback)
        assertEquals(1, r.realIntent.size)
        assertEquals(3, r.angerLevel)
    }

    @Test
    fun handlesPreambleAndTrailing() {
        val raw = "好的，以下是分析结果：\n{\"real_intent\":[{\"label\":\"撒娇\",\"probability\":60}],\"anger_level\":2}\n希望有帮助！"
        val r = ResultParser.parse(raw)
        assertTrue(!r.isFallback)
        assertEquals("撒娇", r.realIntent[0].label)
    }

    @Test
    fun fallsBackOnPlainText() {
        val r = ResultParser.parse("抱歉，我无法分析这段内容。")
        assertTrue(r.isFallback)
        assertNotNull(r.rawFallback)
    }

    @Test
    fun fallsBackOnEmpty() {
        assertTrue(ResultParser.parse("").isFallback)
        assertTrue(ResultParser.parse(null).isFallback)
    }

    @Test
    fun fallsBackOnBrokenJson() {
        assertTrue(ResultParser.parse("{ this is not json ]").isFallback)
    }

    @Test
    fun clampsAngerLevel() {
        val r = ResultParser.parse("{\"real_intent\":[{\"label\":\"x\",\"probability\":50}],\"anger_level\":99}")
        assertTrue(r.angerLevel <= 10)
    }

    @Test
    fun acceptsCamelCaseKeys() {
        val r = ResultParser.parse("{\"realIntent\":[{\"label\":\"测试\",\"probability\":50}],\"angerLevel\":5}")
        assertTrue(!r.isFallback)
        assertEquals(5, r.angerLevel)
    }

    @Test
    fun skipsOptionsWithoutLabel() {
        val r = ResultParser.parse("{\"real_intent\":[{\"probability\":50},{\"label\":\"有效\",\"probability\":30}],\"anger_level\":1}")
        assertEquals(1, r.realIntent.size)
        assertEquals("有效", r.realIntent[0].label)
    }

    @Test
    fun fallbackHasNoStructuredData() {
        val r = AnalysisResult.fallback("raw")
        assertTrue(r.isFallback)
        assertTrue(r.intentOptions.isEmpty())
        assertEquals(-1, r.angerLevel)
    }
}
