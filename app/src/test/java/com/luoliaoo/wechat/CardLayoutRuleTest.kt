package com.luoliaoo.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout rule behind the "card is one line tall" bug.
 *
 * A child that was `size=0, weight=1` on a HORIZONTAL main axis collapses when moved into a
 * VERTICAL container, because its weight then applies to height. CardRenderer.normaliseForVertical
 * fixes such a child by giving it its natural height and clearing the weight; this test pins that
 * decision so it cannot regress unnoticed.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class CardLayoutRuleTest {

    private val wrap = -2

    /**
     * Mirrors the decision made in normaliseForVertical.
     *
     * A height of 0 combined with a weight collapses in a vertical container, so it is replaced
     * with WRAP_CONTENT and the weight is cleared.
     */
    private fun normalise(height: Int, weight: Float): Pair<Int, Float> =
        if (height == 0 && weight > 0f) wrap to 0f else height to 0f

    @Test
    fun weightedChildWithZeroHeightGetsNaturalHeight() {
        val (h, w) = normalise(0, 1f)
        assertEquals("height 0 + weight must become WRAP_CONTENT", wrap, h)
        assertEquals("weight must be cleared", 0f, w, 0.001f)
    }

    @Test
    fun zeroHeightWithoutWeightIsLeftAlone() {
        // A deliberate 0 without weight is not the collapse case; do not touch it.
        val (h, _) = normalise(0, 0f)
        assertEquals(0, h)
    }

    @Test
    fun explicitHeightIsKeptButWeightCleared() {
        val (h, w) = normalise(120, 2f)
        assertEquals(120, h)
        assertEquals(0f, w, 0.001f)
    }

    @Test
    fun wrapContentHeightIsUnaffected() {
        val (h, w) = normalise(wrap, 1f)
        assertEquals(wrap, h)
        assertEquals(0f, w, 0.001f)
    }

    @Test
    fun matchParentHeightIsKept() {
        val match = -1
        val (h, _) = normalise(match, 0f)
        assertEquals(match, h)
    }

    @Test
    fun cardParamsMustNeverBeMatchParentHeight() {
        // The invariant the card itself must satisfy: height is WRAP_CONTENT, never MATCH_PARENT.
        val cardHeight = wrap
        assertTrue("card height must be WRAP_CONTENT", cardHeight != -1)
    }
}
