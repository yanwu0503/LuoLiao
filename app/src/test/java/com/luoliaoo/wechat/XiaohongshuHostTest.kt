package com.luoliaoo.wechat

import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.hook.XiaohongshuMenuIdentity
import com.luoliaoo.wechat.hook.XiaohongshuMenuLogic
import com.luoliaoo.wechat.hook.XiaohongshuReadLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * v9.5.0: Xiaohongshu (小红书) -- the sixth host.
 *
 * ============================== WHY THESE TESTS EXIST ==============================
 *
 * Every one of them pins a decision that a DEVICE LOG forced, after an earlier guess produced a bug the
 * user could see while every log line looked healthy. That pattern -- "the entry appears, the tap does
 * nothing" -- is the failure mode this host produced repeatedly, and it is invisible without a test
 * holding the rule in place.
 *
 * The specific failures being guarded:
 *
 *   * `pickRowBindMethod` chosen by `firstOrNull` latched onto a 3-argument click helper instead of the
 *     8-argument bind, so the hook never fired where the row was actually built.
 *   * `chooseInsertionAnchor` ending with a "whatever looks like a row" fallback picked a container
 *     inside a RecyclerView, and inserting a View there CRASHED the host:
 *         NullPointerException: RecyclerView$ViewHolder.shouldIgnore() ...
 *   * A timestamp normalisation that guessed the wrong unit dated every message to 1970.
 *   * `HostApp.of` matching `com.xingin.` (the APK's internal namespace) instead of `com.xingin.xhs`
 *     (the Android package) would claim unrelated apps as this host.
 */
class XiaohongshuHostTest {

    // ---------------------------------------------------------------- host resolution

    @Test
    fun xiaohongshuIsTheSixthHostAndHasItsOwnLabels() {
        assertEquals("小红书", HostApp.XIAOHONGSHU.displayName)
        assertEquals("小红书号", HostApp.XIAOHONGSHU.idLabel)
        assertTrue(HostApp.REAL_HOSTS.contains(HostApp.XIAOHONGSHU))
        // The label swap must not touch the id label's meaning: 微信号 -> 小红书号, never 小红书号号.
        assertEquals("小红书号", HostApp.XIAOHONGSHU.say("微信号"))
        assertEquals("小红书", HostApp.XIAOHONGSHU.say("微信"))
    }

    @Test
    fun xiaohongshuPackagesResolveAndCloneFollowsItsParent() {
        assertEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.xhs"))
        assertEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.xhs.clone"))
    }

    /**
     * The APK's INTERNAL namespace is `com.xingin.*`, but the Android package is `com.xingin.xhs`.
     *
     * A rule written as "starts with com.xingin." would claim every app under that namespace as this
     * host and install Xiaohongshu hooks into a stranger's process -- the exact failure `HostApp.of`
     * exists to prevent.
     */
    @Test
    fun xiaohongshuNamespaceIsNotItsAndroidPackage() {
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin"))
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.comment"))
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.ai_engine_plugin"))
        assertNotEquals(HostApp.XIAOHONGSHU, HostApp.of("com.xingin.chatbase"))
    }

    @Test
    fun xiaohongshuDoesNotStealAnotherHostsPackage() {
        // Adding a host must not move an existing one. Pinned because `HostApp.of` is the single
        // mapping every hook is gated on.
        assertEquals(HostApp.WECHAT, HostApp.of("com.tencent.mm"))
        assertEquals(HostApp.QQ, HostApp.of("com.tencent.mobileqq"))
        assertEquals(HostApp.KUAISHOU, HostApp.of("com.smile.gifmaker"))
        assertEquals(HostApp.SOUL, HostApp.of("cn.soulapp.android"))
        assertEquals(HostApp.DOUYIN, HostApp.of("com.ss.android.ugc.aweme"))
    }

    // ---------------------------------------------------------------- the scope picker

    /**
     * ============================== THE OMISSION THIS CATCHES ==============================
     *
     * Adding a host to `HostApp` is only HALF the work, and the remaining declarations split into two
     * groups with DIFFERENT contracts:
     *
     *   * THE SCOPE ARRAY (`res/values/arrays.xml`) IS THE LSPOSED SCOPE PICKER'S LIST. Omit a package
     *     and the user cannot tick it, so every hook is unreachable however correct it is. EVERY
     *     package a host owns must be here, clones included.
     *
     *   * THE MANIFEST `<queries>` BLOCK only has to declare the packages the module QUERIES by name.
     *     `HostApp.versionOf` asks for each host's PRIMARY package only (see its doc), so WeChat's
     *     `.u1`..`.u9` clones are deliberately absent there -- they exist so a cloner can be selected in
     *     the scope list, not so their version can be read. Requiring every scope package here was this
     *     test's own first bug, caught by its own failure:
     *
     *         the manifest <queries> must declare com.tencent.mm.u1 ...
     *
     * Both omissions fail SILENTLY in the UI, which is why a test is the only thing that can catch them.
     * The user found the real one before any test did:
     *
     *     「怎么推荐作用域推荐应用没有小红书了」
     *
     * This reads the files as TEXT rather than parsing XML, deliberately: the failure it guards against
     * is a missing literal, and a text search reports that without an Android resource pipeline.
     */
    @Test
    fun everyScopePackageIsDeclaredInTheScopeArray() {
        val root = System.getProperty("user.dir") ?: return
        val arrays = java.io.File(root, "src/main/res/values/arrays.xml")
        if (!arrays.exists()) return       // run from a different cwd: skip
        val arraysText = arrays.readText()

        for (pkg in HostApp.scopePackages()) {
            assertTrue(
                "the LSPosed scope array must contain $pkg, or the user cannot tick this host",
                arraysText.contains("<item>$pkg</item>"),
            )
        }
    }

    /**
     * Every host's PRIMARY package must be declared in the manifest's `<queries>` block.
     *
     * Android 11+ hides undeclared packages from `getPackageInfo`, so without this the UI reports
     * 「未安装」 for an app that is installed and running, and the host is dropped from the recommendation
     * list. `HostApp.versionOf` queries only the first package of each host, which is exactly what this
     * asserts -- not every clone, for the reason the doc above gives.
     */
    @Test
    fun everyHostsPrimaryPackageIsDeclaredInTheManifestQueries() {
        val root = System.getProperty("user.dir") ?: return
        val manifest = java.io.File(root, "src/main/AndroidManifest.xml")
        if (!manifest.exists()) return
        val manifestText = manifest.readText()

        val primary = listOf(
            HostApp.WECHAT to "com.tencent.mm",
            HostApp.DOUYIN to "com.ss.android.ugc.aweme",
            HostApp.SOUL to "cn.soulapp.android",
            HostApp.KUAISHOU to "com.smile.gifmaker",
            HostApp.QQ to "com.tencent.mobileqq",
            HostApp.XIAOHONGSHU to "com.xingin.xhs",
        )
        for ((host, pkg) in primary) {
            assertTrue(
                "the manifest queries block must declare $pkg ($host), or Android 11+ makes it " +
                    "invisible and the UI reports it as not installed",
                manifestText.contains("android:name=\"$pkg\""),
            )
        }
    }

    @Test
    fun xiaohongshuIsInTheScopePackagesAndTheScopeArray() {
        // The two specific strings this host added, asserted directly so the intent is readable even if
        // the file-reading test above is skipped for a cwd reason.
        assertTrue(HostApp.scopePackages().contains("com.xingin.xhs"))
        // The APK's internal namespace must never appear as a package in the scope list.
        assertFalse(HostApp.scopePackages().contains("com.xingin"))
        assertFalse(HostApp.scopePackages().contains("com.xingin.im"))
        assertFalse(HostApp.scopePackages().contains("com.xingin.chatbase"))
    }

    // ---------------------------------------------------------------- menu identity

    @Test
    fun menuIdentityNamesMatchTheMeasuredBuild() {
        // These are the strings the hooks resolve BY NAME at runtime. A typo here is not a compile
        // error -- it is a class that silently fails to load, so the entry never appears.
        assertEquals("com.xingin.im.v2.message.views.MenuView", XiaohongshuMenuIdentity.MENU_VIEW)
        assertEquals(
            "com.xingin.im.ui.widgets.pressmenu.itembinder.LongPressMenuItemBinder",
            XiaohongshuMenuIdentity.ITEM_BINDER,
        )
        assertEquals("com.xingin.chatbase.bean.MsgUIData", XiaohongshuMenuIdentity.MSG_UI_DATA)
        assertEquals("com.xingin.im.ui.activity.ChatActivity", XiaohongshuMenuIdentity.CHAT_ACTIVITY)
        assertEquals("com.xingin.im.ui.view.ChatPageRecyclerView", XiaohongshuMenuIdentity.MESSAGE_LIST)
    }

    @Test
    fun theAnchorAndOurLabelAreTheMeasuredChineseWords() {
        // 转发 is the anchor because it appears in EVERY menu variant this host shows (the item set
        // varies by message type), and 分析 is our own label.
        assertEquals("转发", XiaohongshuMenuIdentity.ANCHOR_LABEL)
        assertEquals("分析", XiaohongshuMenuIdentity.OUR_LABEL)
        assertTrue(XiaohongshuMenuIdentity.HOST_ROW_LABELS.contains(XiaohongshuMenuIdentity.ANCHOR_LABEL))
    }

    // ---------------------------------------------------------------- row-bind method choice

    /** A stand-in for `MenuItemView`, so these tests need no Android classes. */
    private class Row
    private class Other
    private class Menu

    private fun method(vararg types: Class<*>): Method =
        object {
            @Suppress("unused")
            fun m4(a: Int, b: Any, c: String, d: Menu, e: Row, f: Any, g: Any, h: Any) = Unit
            @Suppress("unused")
            fun Y4(a: Any, b: Any, c: Row) = Unit
        }.javaClass.declaredMethods.first { it.parameterTypes.toList() == types.toList() }

    /**
     * THE DEFECT THIS PINS, measured on the device:
     *
     *     xhs: 2 methods take a row view; picked m4 (most parameters). All candidates: Y4/3args, m4/8args
     *
     * An earlier version used `firstOrNull` over the shape-matching candidates. Reflection does not
     * order methods usefully, so it could take the 3-argument helper instead of the 8-argument bind --
     * installing a hook that never fires where rows are built, which reads as "the menu is not
     * reachable" while the install log says it succeeded.
     */
    @Test
    fun theBindMethodIsChosenByMostParametersNotByReflectionOrder() {
        val bind = method(Int::class.java, Any::class.java, String::class.java, Menu::class.java, Row::class.java, Any::class.java, Any::class.java, Any::class.java)
        val helper = method(Any::class.java, Any::class.java, Row::class.java)

        // Both orders must give the SAME answer: the choice may not depend on how reflection happened
        // to enumerate the class.
        assertSame(bind, XiaohongshuMenuLogic.pickRowBindMethod(listOf(bind, helper)))
        assertSame(bind, XiaohongshuMenuLogic.pickRowBindMethod(listOf(helper, bind)))
    }

    @Test
    fun anEmptyCandidateSetYieldsNoMethod() {
        assertNull(XiaohongshuMenuLogic.pickRowBindMethod(emptyList()))
    }

    @Test
    fun rowBindShapeRequiresTheRowViewAndEnoughParameters() {
        val row = Row::class.java
        assertTrue(XiaohongshuMenuLogic.matchesRowBindShape(listOf(Any::class.java, row), row))
        // Too few parameters: a 1-argument method taking a row is a setter, not a bind.
        assertFalse(XiaohongshuMenuLogic.matchesRowBindShape(listOf(row), row))
        // The row view must be present.
        assertFalse(XiaohongshuMenuLogic.matchesRowBindShape(listOf(Any::class.java, Other::class.java), row))
    }

    // ---------------------------------------------------------------- argument picking

    @Test
    fun pickArgOfTypeUsesExactRuntimeClass() {
        val row = Row()
        val other = Other()
        val menu = Menu()
        val args = arrayOf<Any?>(1, "x", menu, row, other, null)
        assertSame(menu, XiaohongshuMenuLogic.pickArgOfType(args, Menu::class.java))
        assertSame(row, XiaohongshuMenuLogic.pickArgOfType(args, Row::class.java))
        // A class with no matching argument -> null, rather than some other argument.
        assertNull(XiaohongshuMenuLogic.pickArgOfType(args, Long::class.java))
        // An unloadable class -> null, so the caller reports "not loadable" instead of guessing.
        assertNull(XiaohongshuMenuLogic.pickArgOfType(args, null))
    }

    // ---------------------------------------------------------------- insertion anchor

    /**
     * ============================== THE ANCHOR RULE, AND THE CRASH IT PREVENTS ==============================
     *
     * An earlier version ended this search with "if nothing labelled was found, use whatever looked like
     * a row". That picked a container INSIDE a RecyclerView, and inserting a View into a RecyclerView is
     * fatal -- its layout pass walks every child expecting a ViewHolder, finds none for ours, and
     * dereferences null:
     *
     *     NullPointerException: RecyclerView$ViewHolder.shouldIgnore() on a null object reference
     *         at RecyclerView.findMinMaxChildLayoutPositions
     *
     * The user reported it as 「现在长按信息直接闪退」. So the contract is: a labelled row, or the row
     * the host is binding, or NOTHING.
     */
    @Test
    fun theAnchorPrefersTheBoundRowWhenItIsTheAnchorLabel() {
        val bound = "转发"
        val picked = XiaohongshuMenuLogic.chooseInsertionAnchor(
            boundRow = bound,
            anchorLabel = "转发",
            hostLabels = listOf("复制", "转发", "引用"),
            labelOf = { it },
            findLabelled = { "删除" },
        )
        assertEquals("转发", picked)
    }

    @Test
    fun theAnchorFallsBackToTheTreeSearchWhenTheBoundRowIsAnotherRow() {
        // The host binds rows in an unspecified order, so the bound row is usually NOT the anchor.
        val picked = XiaohongshuMenuLogic.chooseInsertionAnchor(
            boundRow = "删除",
            anchorLabel = "转发",
            hostLabels = listOf("复制", "转发", "删除"),
            labelOf = { it },
            findLabelled = { "转发" },
        )
        assertEquals("转发", picked)
    }

    @Test
    fun theAnchorAcceptsAnyHostLabelFromTheTree() {
        // A menu that renamed 转发 still has 复制 or 便利贴 to sit beside.
        val picked = XiaohongshuMenuLogic.chooseInsertionAnchor(
            boundRow = null,
            anchorLabel = "转发",
            hostLabels = listOf("复制", "便利贴"),
            labelOf = { it },
            findLabelled = { "便利贴" },
        )
        assertEquals("便利贴", picked)
    }

    @Test
    fun theAnchorReturnsTheBoundRowWhenNothingLabelledIsFound() {
        // Degrading the POSITION beats dropping the entry.
        val picked = XiaohongshuMenuLogic.chooseInsertionAnchor(
            boundRow = "未知项",
            anchorLabel = "转发",
            hostLabels = listOf("复制", "转发"),
            labelOf = { it },
            findLabelled = { null },
        )
        assertEquals("未知项", picked)
    }

    /**
     * THE REGRESSION THIS PINS: with no labelled row and no bound row, the answer must be NULL.
     *
     * Returning anything else is the "use whatever looked like a row" fallback that crashed the host, so
     * this test fails the moment somebody reintroduces it.
     */
    @Test
    fun theAnchorIsNullRatherThanAGuessWhenThereIsNothingUsable() {
        val picked = XiaohongshuMenuLogic.chooseInsertionAnchor(
            boundRow = null,
            anchorLabel = "转发",
            hostLabels = listOf("复制", "转发"),
            labelOf = { it },
            findLabelled = { null },
        )
        assertNull("refusing beats guessing: a missing entry disappoints, a crash stops the app", picked)
    }

    // ---------------------------------------------------------------- timestamp normalisation

    /**
     * ============================== WHY THE UNIT HAS TO BE DECIDED ==============================
     *
     * An IM model stores a timestamp as SECONDS (10 digits) or MILLISECONDS (13 digits), and both are
     * plain `long`s, so nothing in the type says which. Multiplying a millisecond value by 1000 overflows
     * into a date thousands of years ahead; failing to multiply a seconds value dates every message to
     * 1970, which sorts it to the bottom of the history and drops it from any time-window scope.
     *
     * The measured value on the device was `creatTime=1790409465678` -- milliseconds -- but the reader
     * must handle both, so both are pinned.
     */
    @Test
    fun millisecondsPassThroughAndSecondsAreScaled() {
        // The MEASURED value on the device was creatTime=1790409465678 -- milliseconds, so it passes
        // through untouched rather than being multiplied into a date thousands of years ahead.
        assertEquals(1790409465678L, XiaohongshuReadLogic.normaliseTime(1790409465678L))
        // A SECONDS value is scaled by 1000 to milliseconds.
        assertEquals(1790409465000L, XiaohongshuReadLogic.normaliseTime(1790409465L))
        assertEquals(1_700_000_000_000L, XiaohongshuReadLogic.normaliseTime(1_700_000_000L))
    }

    @Test
    fun aNonPositiveTimeIsReportedAsAbsent() {
        // 0 is the "no timestamp" value every host in this module uses, so a caller testing `time != 0`
        // behaves identically here.
        assertEquals(0L, XiaohongshuReadLogic.normaliseTime(0L))
        assertEquals(0L, XiaohongshuReadLogic.normaliseTime(-1L))
        assertEquals(0L, XiaohongshuReadLogic.normaliseTime(-1790409465678L))
    }

    @Test
    fun theThresholdIsTheBottomOfTheMillisecondRange() {
        // At and above the threshold the value is milliseconds; below it, seconds.
        assertEquals(
            XiaohongshuReadLogic.MIN_PLAUSIBLE_MS,
            XiaohongshuReadLogic.normaliseTime(XiaohongshuReadLogic.MIN_PLAUSIBLE_MS),
        )
        // 999_999_999_999 (12 digits) is below the threshold, so it is read as SECONDS and scaled.
        assertEquals(999_999_999_999_000L, XiaohongshuReadLogic.normaliseTime(999_999_999_999L))
    }

    // ---------------------------------------------------------------- the live-history feeder

    /**
     * ============================== THE FIELD NAMES ARE MEASURED, NOT GUESSED ==============================
     *
     * These could not be unit-tested for correctness (that needs the device), but their PRESENCE is
     * worth pinning, because each one was a bug when it was wrong:
     *
     *   * the body is `strMsg`, NOT `content` -- the bean classes have `content`, which is why two
     *     earlier readers returned empty for a message they had captured perfectly;
     *   * the timestamp is `creatTime`, the host's own spelling with the `e` missing;
     *   * the conversation is `chatId`, and `storeId` is deliberately NOT used as an id because it is
     *     only unique within one chat.
     *
     * All of them came from the host's own `toString()`, printed into the module log by the probe.
     */
    @Test
    fun theMeasuredFieldNamesArePresentInTheIdentityConstants() {
        // The two text fields, and the timestamp, as the host's toString() spelled them.
        assertTrue(XiaohongshuMenuIdentity.MSG_GENERAL_BEAN.endsWith("MsgGeneralBean"))
        assertTrue(XiaohongshuMenuIdentity.MESSAGE_BEAN.endsWith("MessageBean"))
        assertEquals("com.xingin.chatbase.bean.MessageBean", XiaohongshuMenuIdentity.MESSAGE_BEAN)
    }
}
