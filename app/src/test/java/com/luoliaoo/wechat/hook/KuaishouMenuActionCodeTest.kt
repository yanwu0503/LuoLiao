package com.luoliaoo.wechat.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what Kuaishou's long-press menu uses to tell options apart, and what the module is therefore
 * allowed to touch.
 *
 * ============================== WHY THIS TEST EXISTS ==============================
 * Two device-verified attempts to fix our row by editing an identity CODE both failed:
 *
 *   field a = 22 (the host's own default)  ->  tapping our row opened 「个性化装扮」
 *   field a = 19 (we believed "no action") ->  tapping our row started 「多选」
 *
 * So this test does NOT assert "we write 19". It asserts the opposite, because that is the truth:
 * the module must not write the code at all, and the behaviour fix lives at the View layer.
 *
 * ============================== THE OFF-BY-ONE THIS PINS ==============================
 * The getter letters do not match the field letters (read from the `iget` instructions with
 * `_tools/ks/ks_gettermap.py`):
 *
 *     r9j.f.a() reads field b
 *     r9j.f.b() reads field c
 *     r9j.f.c() reads field a     <-- so a row built with field a = 22 answers c() == 22
 *     r9j.f.d() reads field d
 *
 * The fixture reproduces that MISMATCH on purpose: a fixture whose getter and field agree would pass
 * against the buggy layout and prove nothing.
 */
class KuaishouMenuActionCodeTest {

    /** Mirrors `r9j.f`: field `a` holds 22, and the no-arg getter `c` is the one that reads it. */
    private class FakeOption {
        @JvmField
        var a: Int = 22

        @JvmField
        var b: Int = 0x7f0734e2

        @JvmField
        var c: Int = 0x7f073af8

        @JvmField
        var d: Int = 0x7f117648

        /** The host's identity code. Reads FIELD `a` -- the whole point of the fixture. */
        fun readIdentityCode(): Int = a
    }

    @Test
    fun `the code read off our row is the host's dress-up code unless something changes it`() {
        // Documents the trap: an untouched `r9j.f` ALREADY claims to be the 装扮 option, because its
        // constructor hardcodes 22 into field a, and the getter named `c` reads field a.
        val o = FakeOption()
        assertEquals(KuaishouMenuIdentity.CODE_OPENING_DECORATION, o.readIdentityCode())
        assertEquals("the getter must read field a, not the field named c", 22, o.readIdentityCode())
    }

    @Test
    fun `the reader used by the module reports the live code and writes nothing`() {
        val o = FakeOption()
        assertEquals(22, KuaishouMenuIdentity.readActionCode(o))
        // Re-read: a reader that wrote anything would be a re-introduction of the disproven patch.
        assertEquals(22, KuaishouMenuIdentity.readActionCode(o))
        assertEquals("the fixture must be untouched by reading", 22, o.readIdentityCode())
    }

    @Test
    fun `multi-select and decoration are DIFFERENT codes, so 19 was never neutral`() {
        // This is the regression that shipped: 19 is the host's multi-select code, not a spare value.
        // Pinning both numbers (and their inequality) is what stops someone "reusing 19 as neutral".
        assertEquals(19, KuaishouMenuIdentity.CODE_MULTI_SELECT)
        assertEquals(22, KuaishouMenuIdentity.CODE_OPENING_DECORATION)
        assertNotEquals(
            "19 and 22 must not be conflated: 19 starts 多选 and 22 opens 装扮",
            KuaishouMenuIdentity.CODE_MULTI_SELECT,
            KuaishouMenuIdentity.CODE_OPENING_DECORATION,
        )
    }

    @Test
    fun `the row's clickable View is field e, not the icon or the label`() {
        // aaj.g.onBind [048a] wraps field `e` in the click listener. Taking over the WRONG field would
        // leave the host still receiving taps, so this name is pinned.
        assertEquals("e", KuaishouMenuIdentity.ROW_VIEW_FIELD)
        assertEquals("a", KuaishouMenuIdentity.ACTION_CODE_FIELD)
    }

    @Test
    fun `a missing field degrades to null instead of throwing`() {
        // A Kuaishou rename is a realistic future change, and this runs from the option constructor path.
        assertNull("a class with no int field 'a' must report null, not throw", KuaishouMenuIdentity.readActionCode(Any()))
    }
}
