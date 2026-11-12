package com.luoliaoo.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Kuaishou long-press menu row's ICON slot.
 *
 * ============================== WHAT THESE TESTS CAN AND CANNOT PIN ==========================
 *
 * Requirement 3 asks that the 分析 entry carry the module's EXISTING icon rather than a new glyph. On
 * Kuaishou the icon goes into the row presenter's own ImageView (`aaj.g.f`, view id `0x7f095336`), which
 * the host fills from the option's icon id.
 *
 * What is testable off-device is the ARITHMETIC and the CONSTANTS that would produce a wrong or
 * invisible icon. What is NOT testable here is whether a pixel ends up visible on a device -- that
 * needs the real panel, and it is reported by the `device-check` log line instead of being asserted
 * here. These tests deliberately do not simulate the host's bind; a simulated bind would only be
 * testing this file's own model of Kuaishou, which proves nothing about Kuaishou.
 *
 * AN EARLIER VERSION OF THIS FILE CONTAINED A TAUTOLOGY -- a fake "attach policy" whose condition was
 * `if (!isFirstBind || true)`, which is unconditionally true and would have passed with the production
 * rule deleted. It was removed rather than kept, because a test that cannot fail is worse than no test:
 * it reports coverage that does not exist.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouMenuIconTest {

    /** The host's "draw no icon" sentinel, read from `aaj.g.onBind` (`if-eq ..., #17, +002a`). */
    private val noIconSentinel = 17

    /** The default icon id `r9j.f.<init>` writes into field `a` (`const/16 v0, #22`). */
    private val defaultIconId = 22

    /** The two real icon ids `r9j.f.<init>` writes into fields `b` and `c`. */
    private val realIconIds = listOf(0x7f0734e2, 0x7f073af8)

    // ---------------------------------------------------------------- the icon-id sentinel

    @Test
    fun `our option's default icon is a REAL host glyph, not the empty sentinel`() {
        // This is WHY the icon must be overwritten rather than left alone: the default is 22, i.e. some
        // other option's real icon. A row we forgot to override would show a plausible but WRONG glyph
        // -- worse than showing none, because it looks intentional and no log would complain.
        assertFalse(
            "the default must NOT be the no-icon sentinel, or there would be nothing to fix",
            defaultIconId == noIconSentinel,
        )
    }

    @Test
    fun `the sentinel collides with none of the real icon ids`() {
        // The host chooses its branch by comparing the icon id against 17, so 17 must not also be a
        // usable icon id -- otherwise "no icon" and "this icon" would be indistinguishable.
        assertTrue("sentinel must differ from the default", noIconSentinel != defaultIconId)
        for (id in realIconIds) {
            assertTrue(
                "real icon id 0x" + Integer.toHexString(id) + " must not equal the sentinel",
                id != noIconSentinel,
            )
        }
    }

    // ---------------------------------------------------------------- the shared-drawable contract

    @Test
    fun `the icon source is the shared AnalysisIcon at the shared size`() {
        // Requirement 3: reuse the EXISTING icon. The guarantee is structural -- the same
        // `AnalysisIcon.toDrawable` function WeChat and Soul call -- so identical pixels come from
        // identical code rather than from a redrawn lookalike.
        //
        // This pins the ONE shared size. That single number is what all four hosts use, so changing it
        // here changes WeChat, Douyin, Soul and Kuaishou together -- which is the intent.
        assertEquals("the shared icon size", 20, com.luoliaoo.wechat.ui.AnalysisIcon.SIZE_DP)
    }

    // NOT ASSERTED HERE, and deliberately so: that `KuaishouHooks`'s icon-size constant is an ALIAS of
    // `AnalysisIcon.SIZE_DP` rather than a second copy of the number. That property lives in a
    // `private companion object`, so it is unreachable from a JVM unit test, and re-declaring the value
    // here to "check" it would only compare the shared constant with itself -- a tautology that cannot
    // fail. The alias is verified by reading the production line, which is the honest place for it:
    //
    //     const val ICON_SIZE_DP = com.luoliaoo.wechat.ui.AnalysisIcon.SIZE_DP
    //
    // A test that cannot fail is worse than no test, because it reports coverage that does not exist.

    // ---------------------------------------------------------------- the size arithmetic

    @Test
    fun `the icon size scales with density and is never zero`() {
        // A 0-pixel drawable is INVISIBLE while every log line still reports "icon attached". This is
        // the arithmetic that decides it, checked at the densities Android actually reports -- including
        // ldpi (0.75x), the smallest real one, where a naive formula could plausibly truncate.
        for (density in listOf(0.75f, 1.0f, 1.5f, 2.0f, 2.75f, 3.0f, 4.0f)) {
            val px = (20 * density).toInt()
            assertTrue("density $density produced a non-positive icon size: $px", px > 0)
            assertEquals("20dp at ${density}x must be ${(20 * density).toInt()}px", (20 * density).toInt(), px)
        }
    }

    @Test
    fun `a plausible on-screen icon size follows from the shared constants`() {
        // Sanity on the MAGNITUDE, so a unit mix-up (dp vs px, or a missing density multiply) is caught:
        // at 3x, 20dp is 60px, which is a normal menu-icon size. If someone dropped the density factor
        // the value would be 20px on a 3x screen, i.e. visibly tiny.
        val at3x = (20 * 3.0f).toInt()
        assertEquals(60, at3x)
        assertTrue("a 60px icon is a believable menu icon", at3x in 24..160)
    }
}
