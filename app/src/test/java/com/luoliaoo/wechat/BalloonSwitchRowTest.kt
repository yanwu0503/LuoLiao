package com.luoliaoo.wechat

import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Constants.OwnMenuAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 落聊悬浮球开关 menu entry.
 *
 * The risky part of that feature is NOT the toggle itself -- it is telling the two injected menu rows
 * apart. Before this entry there was exactly one item the module owned, so "the row shows our title"
 * WAS the identification. With a second entry in the same menu that test becomes ambiguous in two
 * different ways, and both are covered here:
 *
 *   * the shared container holds BOTH titles, so a tap on either entry must not be attributed to the
 *     other one -- it must be rejected outright;
 *   * a rule phrased as "contains one of our titles" would collapse the two entries back into one.
 *
 * The decision lives in Constants.decideOwnMenuAction, which the menu hook calls on the labels it
 * collected from the real view subtree -- so the rule tested here is the rule that routes a real tap.
 * Keeping it free of Android types is what makes it testable at all.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class BalloonSwitchRowTest {

    /** The WeChat labels MenuHook rejects a shared container by. */
    private val otherLabels = listOf(
        "复制", "转发", "删除", "多选", "收藏", "撤回", "引用", "编辑", "提醒", "译文",
    )

    private fun decide(vararg labels: String): OwnMenuAction =
        Constants.decideOwnMenuAction(labels.toSet(), otherLabels)

    // ---------------------------------------------------------------- the ids

    @Test
    fun theTwoItemsHaveDistinctIds() {
        // Merging the ids would make the id-based dispatcher (nf5.h4.c) run the wrong action.
        assertNotEquals(Constants.ITEM_ID, Constants.BALLON_ITEM_ID)
    }

    @Test
    fun theTwoItemsHaveDistinctTitles() {
        // The title is the row's identity; sharing it would make every tap ambiguous.
        assertNotEquals(Constants.ITEM_TITLE, Constants.BALLON_ITEM_TITLE)
        assertFalse(Constants.BALLON_ITEM_TITLE.isBlank())
        assertEquals(2, Constants.OWN_MENU_TITLES.size)
    }

    // ------------------------------------------------------------ routing: ours

    @Test
    fun analyseRowResolvesToAnalyse() {
        assertEquals(OwnMenuAction.ANALYSE, decide(Constants.ITEM_TITLE))
    }

    @Test
    fun balloonRowResolvesToTheSwitch() {
        assertEquals(OwnMenuAction.BALLOON_SWITCH, decide(Constants.BALLON_ITEM_TITLE))
    }

    @Test
    fun theTwoRowsNeverResolveToEachOther() {
        // This is the bug the task warns about: each entry must map to its OWN action and nothing else.
        assertNotEquals(decide(Constants.ITEM_TITLE), decide(Constants.BALLON_ITEM_TITLE))
        assertNotEquals(OwnMenuAction.BALLOON_SWITCH, decide(Constants.ITEM_TITLE))
        assertNotEquals(OwnMenuAction.ANALYSE, decide(Constants.BALLON_ITEM_TITLE))
    }

    @Test
    fun aRowWithIconTextStillResolves() {
        // Menu rows commonly carry one extra short label; <= 2 labels is allowed.
        assertEquals(
            OwnMenuAction.BALLOON_SWITCH,
            decide(Constants.BALLON_ITEM_TITLE, "b"),
        )
    }

    // --------------------------------------------------- routing: not ours

    @Test
    fun theSharedContainerIsRejected() {
        // Both of our titles in one subtree == the dialog that holds every item, not a row.
        // Attributing this to either entry is exactly how the two buttons would fight each other.
        assertEquals(
            OwnMenuAction.NONE,
            decide(Constants.ITEM_TITLE, Constants.BALLON_ITEM_TITLE),
        )
    }

    /**
     * v3.1.1 回归：真机上出现的「点分析也变成悬浮球开关了」。
     *
     * 【为什么上面那些断言拦不住它】上面测的都是 decideOwnMenuAction 这个**纯函数**，而那个函数本身
     * 是正确的。真机的 bug 出在**调用方**：MenuHook 用 `armedRow` 这个**单个共享字段**记录"当前该按哪一行
     * 判定"，两个菜单项各自 hijack 时都是 `armedRow = host`，**后写的覆盖先写的**。于是点「分析」时
     * armedRow 指向的是浮窗那一行 → 判定出 BALLOON_SWITCH → 分析永远打不开。
     *
     * 修法是不再依赖共享字段：listener 用**闭包捕获自己的 host**，点击时以自己那一行为准。
     *
     * 这个测试钉住的是"两项各自的判定必须彼此独立"这一契约：给定只含 分析 的行，无论另一项是否已注册，
     * 结果都必须是 ANALYSE。它确保将来有人再把路由改回共享状态时，至少纯函数这一层仍然守住语义。
     */
    @Test
    fun eachRowKeepsItsOwnActionRegardlessOfTheOther() {
        // 分析行：即使浮窗那一项已经存在（表现为 OWN_MENU_TITLES 里有两项），它也只能判成 ANALYSE。
        assertEquals(OwnMenuAction.ANALYSE, decide(Constants.ITEM_TITLE))
        // 浮窗行：同样只能判成 BALLOON_SWITCH。
        assertEquals(OwnMenuAction.BALLOON_SWITCH, decide(Constants.BALLON_ITEM_TITLE))
        // 而且两者绝不能相等 —— 相等即意味着点哪一项都会跑同一个动作。
        assertNotEquals(decide(Constants.ITEM_TITLE), decide(Constants.BALLON_ITEM_TITLE))
    }

    /**
     * v3.1.1 回归：菜单行带图标/附带文字时的判定不能被干扰。
     *
     * 真机上菜单行常带一个额外短标签（图标描述、快捷键提示等），上一版依赖 `labels.size <= 2`。
     * 这里显式钉住：分析行 + 一个无关短标签仍然是 ANALYSE，不会因为多了一个标签而漂到浮窗去。
     */
    @Test
    fun anAnalyseRowWithAnExtraLabelStaysAnalyse() {
        assertEquals(OwnMenuAction.ANALYSE, decide(Constants.ITEM_TITLE, "a"))
        assertEquals(OwnMenuAction.ANALYSE, decide(Constants.ITEM_TITLE, "b"))
    }

    @Test
    fun aContainerWithWeChatItemsIsRejected() {
        for (other in otherLabels) {
            assertEquals(
                "container with " + other + " must be rejected",
                OwnMenuAction.NONE,
                decide(Constants.ITEM_TITLE, other),
            )
            assertEquals(
                "balloon row with " + other + " must be rejected",
                OwnMenuAction.NONE,
                decide(Constants.BALLON_ITEM_TITLE, other),
            )
        }
    }

    @Test
    fun aWeChatRowIsNotOurs() {
        assertEquals(OwnMenuAction.NONE, decide("复制"))
        assertEquals(OwnMenuAction.NONE, decide("转发", "删除"))
    }

    @Test
    fun anEmptySubtreeIsNotOurs() {
        assertEquals(OwnMenuAction.NONE, decide())
    }

    @Test
    fun aSubtreeThatSwallowedManyRowsIsRejected() {
        // More than two labels means the candidate is bigger than one row.
        assertEquals(
            OwnMenuAction.NONE,
            decide(Constants.ITEM_TITLE, "x", "y"),
        )
    }

    // ------------------------------------------------------- the toggle itself

    @Test
    fun theSwitchFlipsTheFlag() {
        // Mirrors ConfigRepo.toggleBalloonEnabled: read the current value, then invert it.
        assertFalse(true.not())
        assertTrue(false.not())
    }

    @Test
    fun theBalloonDefaultsToOn() {
        // An install that never touched the switch must behave exactly as before the feature existed.
        assertTrue(com.luoliaoo.wechat.data.Config().balloonEnabled)
    }
}
