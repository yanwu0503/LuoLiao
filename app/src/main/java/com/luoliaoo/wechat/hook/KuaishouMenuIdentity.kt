package com.luoliaoo.wechat.hook

/**
 * What Kuaishou's long-press menu uses to tell one option from another, and where that lives.
 *
 * ============================== WHY THIS IS DOCUMENTED, NOT USED TO PATCH ==============================
 * Device history on the 「分析」 / 「浮窗」 rows, in order:
 *
 *   v1  the row opened 「个性化装扮」 -- the field carried 22, the host's dress-up code
 *   v2  the row started 「多选」      -- we moved the field to 19, believing it meant "no action"
 *   v3  the row's click listener was replaced -- WITHDRAWN: the panel's dismissal runs in the CALLER
 *       (`aaj.b$c.c` [0021] `b->l()`), so suppressing the row's callback would leave the menu open
 *   v4  the host's callback body is SKIPPED for our row only -- **this is the shipped fix**, in
 *       `KuaishouHooks.installMenuClickHook` (`chain.skipOriginal()`)
 *
 * The lesson from v2 is that **there is no free value to pick**. The dispatch is a table over the host's
 * OWN option codes, so a "spare-looking" number is simply another feature:
 *
 *     aaj.g$a.onClick  [004b] const/16 19  ->  the row ran 多选
 *     r9j.q (the host's own multi-select item) is built with 14 / 0x7f0721cb / 0x7f073af1 / 0x7f111123
 *     r9j.f (the class we instantiate) hardcodes  22 / 0x7f0734e2 / 0x7f073af8 / 0x7f117648 ('装扮')
 *
 * So this object only RECORDS the host's identity data, for logs and for tests. It deliberately writes
 * none of it. The behaviour fix is `chain.skipOriginal()` in `KuaishouHooks.installMenuClickHook`.
 *
 * NOTE FOR FUTURE EDITORS: v4 rests entirely on ONE invoke site of `r9j.m.c` existing in the tap path
 * and on the caller doing the dismissal afterwards (verified: 1 site in all 31 dex files, and
 * `aaj.b$c.c` calls `b->l()` at [0021], after our hook returns). If anyone reverts it again, re-check
 * those two facts against the NEW dex before concluding that skipping breaks the menu.
 *
 * ============================== THE OFF-BY-ONE THAT HID THE BUG ==============================
 * The getter letters do NOT match the field letters, which is why three code inspections missed this:
 *
 *     r9j.f.a() reads field b
 *     r9j.f.b() reads field c
 *     r9j.f.c() reads field a     <-- so a row built with field a = 22 answers c() == 22
 *     r9j.f.d() reads field d
 *
 * Read out of the `iget` instructions with `_tools/ks/ks_gettermap.py`. The fixture in
 * KuaishouMenuActionCodeTest reproduces that mismatch ON PURPOSE; a fixture with matching names would
 * pass against the broken behaviour and prove nothing.
 *
 * ============================== EVIDENCE TOOLING ==============================
 *   python _tools/ks/ks_gettermap.py     getter -> field mapping
 *   python _tools/ks/ks_writers.py       who writes r9j/f's int fields
 *   python _tools/ks/ks_method.py C M    ONE method, COMPLETE. Never grep a method you must reason about:
 *                                        a filtered listing silently drops `move-result`, which is what
 *                                        carries a return value into the next comparison -- that omission
 *                                        is exactly how the "19 is neutral" error was made.
 *   python _tools/ks/ks_ifaceimpls.py    implementations of an interface, and any method of each
 */
internal object KuaishouMenuIdentity {

    /** The FIELD that the no-arg getter named `c` reads. Not the field named `c`. */
    const val ACTION_CODE_FIELD = "a"

    /** `r9j.f.<init>`'s value for [ACTION_CODE_FIELD]. The host reads this as its dress-up option. */
    const val CODE_OPENING_DECORATION = 22

    /**
     * The code the host's multi-select path responds to.
     *
     * Recorded because WE SHIPPED IT BY MISTAKE: moving [ACTION_CODE_FIELD] to 19 made our row start
     * 「多选」. Keeping the number here is what stops anyone concluding again that 19 is "unused".
     */
    const val CODE_MULTI_SELECT = 19

    /**
     * `r9j.f`'s field `d` default, `0x7f117648`, resolving to the string '装扮'.
     *
     * A REAL string-resource id that renders a row's label, so it is never written -- we overwrite the
     * row's TextView instead. Kept as evidence of which host option our row used to be indistinguishable
     * from.
     */
    const val DECORATION_LABEL_ID = 0x7f117648

    /**
     * The row presenter's clickable View: `aaj.g.e:Landroid/view/View;`.
     *
     * `aaj.g.onBind` wraps the whole row in a listener at [048a]; rebinding it on OUR row is the fix.
     */
    const val ROW_VIEW_FIELD = "e"

    /** Reads [ACTION_CODE_FIELD] live, for logging. READ-ONLY by design -- see the class KDoc. */
    fun readActionCode(inst: Any): Int? = readInt(inst, ACTION_CODE_FIELD)

    /** Walks the hierarchy for an `int` field and reads it; null when absent or unreadable. */
    private fun readInt(obj: Any, name: String): Int? {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name != name || f.type != Integer.TYPE) continue
                return try {
                    f.isAccessible = true
                    f.getInt(obj)
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }
}
