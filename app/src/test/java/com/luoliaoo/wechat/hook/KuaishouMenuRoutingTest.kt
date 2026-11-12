package com.luoliaoo.wechat.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the routing decision behind the 装扮 / 多选 fix.
 *
 * `installMenuClickHook` suppresses the host's own dispatch (`chain.skipOriginal()`) when a tapped
 * long-press option is one of OURS. Both directions of that decision are dangerous, so both are pinned:
 *
 *   * missing one of ours  -> tapping 分析 opens 「个性化装扮」 / starts 「多选」 (the device bug)
 *   * matching a host row  -> the host's own 「撤回」/「删除」/「多选」 silently stops working
 *
 * The second is the worse failure, which is why the decision is a pure function instead of an inline
 * expression only the device could falsify.
 */
class KuaishouMenuRoutingTest {

    private val ours = java.util.Collections.newSetFromMap(java.util.HashMap<Int, Boolean>())
    private val oursObj = Any()
    private val hostObj = Any()

    init {
        ours.add(System.identityHashCode(oursObj))
    }

    @Test
    fun `one of our own rows is recognised`() {
        assertTrue(
            "our row must be recognised, or the host runs 装扮/多选 on tap",
            KuaishouMenuRouting.isOurRow(oursObj, ours),
        )
    }

    @Test
    fun `a HOST row is never claimed as ours`() {
        // The dangerous direction: claiming a host row would make 撤回/删除/多选 do nothing.
        assertFalse(
            "a host option must NOT be treated as ours, or we break the host's own menu",
            KuaishouMenuRouting.isOurRow(hostObj, ours),
        )
    }

    @Test
    fun `an unknown option is not ours`() {
        // "Unknown" must fail closed (i.e. NOT claimed), so a row we never created keeps working.
        assertFalse(KuaishouMenuRouting.isOurRow(Any(), ours))
    }

    @Test
    fun `a null option is not ours`() {
        // The host passes a real r9j.k; a null would come from a call shape we do not understand, and
        // treating it as "ours" would suppress the host's dispatch for something we cannot identify.
        assertFalse(KuaishouMenuRouting.isOurRow(null, ours))
    }

    @Test
    fun `two rows of ours are both recognised and the host row still is not`() {
        // Both injected rows (分析 and 浮窗) must be suppressed, or one of them keeps reaching the host.
        val balloon = Any()
        ours.add(System.identityHashCode(balloon))
        assertTrue(KuaishouMenuRouting.isOurRow(oursObj, ours))
        assertTrue(KuaishouMenuRouting.isOurRow(balloon, ours))
        assertFalse(KuaishouMenuRouting.isOurRow(hostObj, ours))
    }
}
