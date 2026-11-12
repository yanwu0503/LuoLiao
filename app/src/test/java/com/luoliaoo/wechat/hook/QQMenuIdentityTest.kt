package com.luoliaoo.wechat.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards QQ's menu-row identity and the constants the injection depends on.
 *
 * ============================== WHY THESE ARE PURE FUNCTIONS ==============================
 * On QQ the row is injected as a VIEW with our own click listener, so the routing decision looks
 * self-evident and an inlined expression would "obviously work". It is still pinned here, because the
 * identity rules are exactly the part a device run falsifies and a phone cannot tell us WHICH branch was
 * taken. Both directions are dangerous:
 *
 *   * missing our own row -> tapping 分析 does nothing;
 *   * claiming a host row  -> the host's own 撤回 / 删除 / 多选 silently stops working, which is worse
 *     because it breaks a feature the user already had. `KuaishouMenuRoutingTest` records the same
 *     asymmetry for that host, where the failure mode was live on device.
 *
 * The constants are asserted too: `OUR_ITEM_KEY` must not be one of the host's 21 keys, because that fact
 * is what makes an unknown key inert in `processor.d#a` -- the property that lets this host skip the
 * `skipOriginal` hack Kuaishou needed.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQMenuIdentityTest {

    private val ours = java.util.Collections.newSetFromMap(java.util.HashMap<Int, Boolean>())
    private val oursObj = Any()
    private val hostObj = Any()

    init {
        ours.add(System.identityHashCode(oursObj))
    }

    // ---------------------------------------------------------------- row identity

    @Test
    fun `our own registered item is recognised`() {
        assertTrue(
            "our row must be recognised, or tapping it does nothing",
            QQMenuIdentity.isOurItem(oursObj, ours),
        )
    }

    @Test
    fun `a HOST item is never claimed as ours`() {
        // The dangerous direction: claiming a host row would break 撤回 / 删除 / 多选.
        assertFalse(
            "a host option must NOT be treated as ours, or we break the host's own menu",
            QQMenuIdentity.isOurItem(hostObj, ours),
        )
    }

    @Test
    fun `an unknown item and a null item are not ours`() {
        // "Unknown" fails closed, so a row we never created keeps working. A null comes from a call shape
        // we do not understand, and guessing "ours" there is what starts breaking host rows.
        assertFalse(QQMenuIdentity.isOurItem(Any(), ours))
        assertFalse(QQMenuIdentity.isOurItem(null, ours))
    }

    @Test
    fun `an empty registry claims nothing`() {
        // Before the first injection there is nothing of ours, so nothing may be claimed.
        val empty = java.util.Collections.newSetFromMap(java.util.HashMap<Int, Boolean>())
        assertFalse(QQMenuIdentity.isOurItem(oursObj, empty))
    }

    // ---------------------------------------------------------------- row tag

    @Test
    fun `the row tag identifies our view and only our view`() {
        assertTrue(QQMenuIdentity.isOurRowTag(QQMenuIdentity.ROW_TAG_OURS))
        // A host row's tag is its `ui.e` ITEM, not a String -- so the type check alone excludes it.
        assertFalse("a ui.e item tag must not read as ours", QQMenuIdentity.isOurRowTag(hostObj))
        assertFalse(QQMenuIdentity.isOurRowTag(null))
        // A near-miss string must not match: an earlier injection could otherwise suppress a later one.
        assertFalse(QQMenuIdentity.isOurRowTag(QQMenuIdentity.ROW_TAG_OURS + ".2"))
        assertFalse(QQMenuIdentity.isOurRowTag("luoliaoo.analysis"))
    }

    // ---------------------------------------------------------------- key identity

    @Test
    fun `our key is recognised`() {
        assertTrue(QQMenuIdentity.isOurKey(QQMenuIdentity.OUR_ITEM_KEY))
        assertFalse(QQMenuIdentity.isOurKey(null))
        assertFalse(QQMenuIdentity.isOurKey(""))
    }

    @Test
    fun `our key does NOT collide with any of the host's dispatch keys`() {
        // ============================== THE LOAD-BEARING ASSERTION ==============================
        // `processor.d#a` looks our key up in the host's HashMap and, when it finds nothing, the `if-eq`
        // at [0020] skips the dispatch entirely -- so our row cannot trigger a host feature. That property
        // holds ONLY while our key is absent from the host's table. If a future transcript adds a key that
        // matches ours, this test fails instead of the collision reaching a device.
        for (k in QQMenuIdentity.HOST_KEYS) {
            assertFalse(
                "our key collides with host key '" + k + "', which would make our row dispatch into " +
                    "a host feature",
                QQMenuIdentity.isOurKey(k),
            )
        }
    }

    @Test
    fun `the host key table is transcribed, not empty`() {
        // A silently-empty table would make the collision check above vacuous.
        assertEquals(23, QQMenuIdentity.HOST_KEYS.size)
        assertTrue(QQMenuIdentity.HOST_KEYS.contains(QQMenuIdentity.HOST_KEY_MULTI_SELECT))
        // The multi-select key is the one our batch path depends on existing: QQ's own 多选 entry is what
        // puts the user in a selection in the first place.
        assertTrue(QQMenuIdentity.isOurKey(QQMenuIdentity.HOST_KEY_MULTI_SELECT).not())
    }

    // ---------------------------------------------------------------- class names

    @Test
    fun `the menu class names are fully qualified, never simple names`() {
        // THE same-simple-name trap recorded in the recon: `QQCustomMenuNoIconLayout` exists in BOTH
        // `com.tencent.mobileqq.utils.dialogutils` (NOT the chat path) and `com.tencent.qqnt.aio.menu.ui`
        // (the NT chat path). A simple-name lookup picks whichever the loader finds first, so every name
        // here must carry its package.
        for (n in listOf(
            QQMenuIdentity.MENU_CLASS,
            QQMenuIdentity.ITEM_CLASS,
            QQMenuIdentity.MENU_LAYOUT_CLASS,
            QQMenuIdentity.ROW_CLICK_CLASS,
            QQMenuIdentity.PROCESSOR_IFACE,
            QQMenuIdentity.PROCESSOR_DISPATCH_CLASS,
        )) {
            assertTrue("'" + n + "' must be fully qualified", n.contains('.'))
            assertTrue("'" + n + "' must be the NT chat path", n.startsWith("com.tencent.qqnt.aio.menu"))
        }
    }

    @Test
    fun `the row click listener is addressed as an inner class`() {
        // `QQCustomMenuExpandableLayout$c`, the inner class whose onClick is the failure funnel. Written
        // with a '$' because that is its binary name -- a '.' here would silently load nothing.
        assertTrue(QQMenuIdentity.ROW_CLICK_CLASS.contains('$'))
        assertEquals(
            QQMenuIdentity.MENU_LAYOUT_CLASS,
            QQMenuIdentity.ROW_CLICK_CLASS.substringBefore('$'),
        )
    }

    @Test
    fun `our row label is the shared 分析 title`() {
        // The QQ row must read exactly like every other host's, so it delegates rather than duplicating
        // the string.
        assertEquals(
            com.luoliaoo.wechat.core.Constants.ITEM_TITLE,
            QQMenuIdentity.OUR_ITEM_LABEL,
        )
    }
}
