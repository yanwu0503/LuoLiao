package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.CardTheme
import com.luoliaoo.wechat.data.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card appearance settings (item 3).
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class CardThemeTest {

    @Test
    fun defaultsAreTheAccentBlueAnd13sp() {
        val t = CardTheme()
        assertEquals(CardTheme.DEFAULT_ACCENT, t.accent)
        assertEquals(13f, t.fontSp, 0.001f)
    }

    @Test
    fun configExposesItsTheme() {
        val c = Config(accentColor = 0xFF07C160.toInt(), fontSp = 15f)
        val t = c.cardTheme()
        assertEquals(0xFF07C160.toInt(), t.accent)
        assertEquals(15f, t.fontSp, 0.001f)
    }

    @Test
    fun configDefaultsMatchCardThemeDefaults() {
        val c = Config()
        assertEquals(CardTheme.DEFAULT_ACCENT, c.accentColor)
        assertEquals(CardTheme.DEFAULT_FONT_SP, c.fontSp, 0.001f)
        assertEquals(25, c.cardWidth)
    }

    // ---------------------------------------------------------- colour parsing

    @Test
    fun parsesSixDigitHexWithHash() {
        assertEquals(0xFF4C6FF0.toInt(), CardTheme.parseColor("#4C6FF0"))
    }

    @Test
    fun parsesSixDigitHexWithoutHash() {
        assertEquals(0xFF07C160.toInt(), CardTheme.parseColor("07C160"))
    }

    @Test
    fun parsesEightDigitHex() {
        assertEquals(0x80123456.toInt(), CardTheme.parseColor("#80123456"))
    }

    @Test
    fun fallsBackOnGarbage() {
        assertEquals(CardTheme.DEFAULT_ACCENT, CardTheme.parseColor("not a colour"))
        assertEquals(CardTheme.DEFAULT_ACCENT, CardTheme.parseColor(""))
        assertEquals(CardTheme.DEFAULT_ACCENT, CardTheme.parseColor(null))
        assertEquals(CardTheme.DEFAULT_ACCENT, CardTheme.parseColor("#12"))
    }

    @Test
    fun fallsBackToTheSuppliedValue() {
        val custom = 0xFF123456.toInt()
        assertEquals(custom, CardTheme.parseColor("zzz", custom))
    }

    @Test
    fun roundTripsThroughHex() {
        for ((_, color) in CardTheme.PRESETS) {
            assertEquals(color, CardTheme.parseColor(CardTheme.toHex(color)))
        }
    }

    @Test
    fun toHexProducesSevenChars() {
        val s = CardTheme.toHex(0xFF4C6FF0.toInt())
        assertEquals(7, s.length)
        assertTrue(s.startsWith("#"))
    }

    @Test
    fun presetsAreUniqueAndOpaque() {
        val colors = CardTheme.PRESETS.map { it.second }
        assertEquals(colors.size, colors.toSet().size)
        for (c in colors) {
            assertEquals("alpha must be FF", 255, (c ushr 24) and 0xFF)
        }
    }
}
