package com.luoliaoo.wechat.hook

/**
 * What QQ's NT long-press menu uses to tell one option from another, where that lives, and how OUR row
 * gets into the menu.
 *
 * ============================== THE FACTS THIS DESIGN RESTS ON ==============================
 *
 * 1. THE MENU ITEM'S IDENTITY IS A STRING KEY, NOT AN INT ACTION CODE.
 *
 *    Read end to end out of the device dex (`classes6.dex`). The dispatcher is
 *
 *        com.tencent.qqnt.aio.menu.processor.d#a(ui.c, ui.e, Bundle)   [42 code units]
 *          [0014] iget-object     processor.d.a : java.util.HashMap
 *          [0016] invoke-virtual  ui.e#g()Ljava/lang/String;    <-- the item's KEY
 *          [001a] invoke-virtual  java.util.HashMap#get(Ljava/lang/Object;)Ljava/lang/Object;
 *          [001e] check-cast      processor.a
 *          [0020] if-eq                                <-- null (unknown key) jumps past the dispatch
 *          [0022] invoke-interface processor.a#a(ui.c, ui.e, Bundle)
 *          [0026] invoke-virtual  ui.c#a(ui.e)         <-- otherwise the item is ADDED to the menu
 *
 *    The host's option table is therefore `HashMap<String, processor.a>` with 23 entries, all extracted
 *    from `processor.d#<init>` and recorded in [HOST_KEYS] below. (An earlier revision of this comment
 *    said 21; counting the `const-string/jumbo` + `HashMap#put` pairs in `processor.d#<init>` gives 23,
 *    and [HOST_KEYS] is asserted against that count by `QQMenuIdentityTest`.)
 *
 *    *** THIS IS WHY THIS HOST AVOIDS KUAISHOU'S ENTIRE BUG CLASS. ***
 *    Kuaishou dispatches on an INT from a closed table, so every number is already another feature:
 *    `KuaishouMenuIdentity` records three device-verified failures in a row (22 -> 个性化装扮,
 *    19 -> 多选), and the shipped fix had to be `chain.skipOriginal()` on the host's dispatcher.
 *    On QQ an unknown key simply finds nothing, so nothing we add can be routed into a host feature.
 *
 * 2. `ui.e` IS AN ABSTRACT CLASS, SO IT CANNOT BE PROXIED.
 *    `access_flags = 0x401` (public|abstract) in classes6.dex. `java.lang.reflect.Proxy` creates
 *    implementations of INTERFACES only, so an earlier design that proxied `ui.e` would have thrown
 *    `IllegalArgumentException: ... is not an interface` on the first long-press. It was caught by
 *    checking the flag instead of assuming it. This single fact is why the row is injected as a VIEW
 *    (see [ROW_TAG_OURS]) rather than as a `ui.e` item.
 *
 * 3. THE HOST'S OWN ITEM CLASSES CANNOT EXPRESS OUR IDENTITY.
 *    `menu.aq` / `menu.az` ARE constructible in shape -- `(Context, AIOMsgItem)`, and the host itself
 *    builds them in every content component's `f2` (`AIOTextContentComponent#f2`,
 *    `AIOPttContentComponent#f2`, `AIOReplyComponent#f2`, ...). But they HARDCODE their identity:
 *    `menu.aq#g()` returns the literal "RobotReportMenuItem" and `menu.aq#h()` returns
 *    `context.getString(<fixed res id>)`. Instantiating one would give a row labelled as the host's
 *    robot report whose tap runs the robot-report feature.
 *
 * 4. THE RENDER METHOD IS `QQCustomMenuExpandableLayout#A()V`, AND SETMENU STORES A COPY.
 *    `A()` is 2060 code units and is what calls the per-row builder `r(I, ui.e, Z, [F)`; all four
 *    strategies (`strategy.c/e/j/p#b`) call `setMenu(...)` and then `A()`.
 *    `ui.c#c()` returns a COPY (`new-instance ui.c` + `addAll` + `return-object`) and `setMenu` stores
 *    that copy, so anything appended to a container AFTER `setMenu` is never rendered. That is why the
 *    injection point is the render method, and why injecting an ITEM would have to happen before
 *    `setMenu` -- whereas injecting a VIEW happens after `A()` has built the tree.
 *
 * 5. THE MESSAGE IS REACHABLE FROM A ROW: `ui.e#d()Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;`.
 *    Its own users confirm it -- `menu.aq#k()` does `ui.e#d()` -> `AIOMsgItem#getMsgRecord()`, and
 *    `MenuMultiSelectProcessor#a` does `ui.e#d()` -> `AIOMsgItem#B0()I`. A row's own View also carries
 *    its `ui.e` as its tag (`QQCustomMenuExpandableLayout$c#onClick`: `View#getTag()`,
 *    `instance-of ui.e`). Both routes are used by [QQHooks], the tag one first.
 *
 * ============================== WHAT IS DELIBERATELY *NOT* HERE ==============================
 * Class and member NAMES only. Every reflective lookup, every hook and every piece of Android UI lives in
 * QQHooks.kt. This file stays free of android.* so the identity/predicate rules can be unit-tested on the
 * JVM -- the same split as `KuaishouMenuIdentity` / `KuaishouMenuRouting`, and for the same reason: these
 * rules are exactly what a device run falsifies, so they must be assertable offline.
 *
 * Comments ASCII-only; Chinese uses \uXXXX escapes (see core/Log.kt).
 */
internal object QQMenuIdentity {

    // ------------------------------------------------------------------ menu plumbing

    /** The menu container. `ui.c` -- holds a `CopyOnWriteArrayList<ui.e>`. */
    const val MENU_CLASS = "com.tencent.qqnt.aio.menu.ui.c"

    /** One menu row. `ui.e` -- ABSTRACT (0x401), so it is never instantiated directly. */
    const val ITEM_CLASS = "com.tencent.qqnt.aio.menu.ui.e"

    /** The renderer. `setMenu(ui.c, View)` stores the container; `A()` builds the rows. */
    const val MENU_LAYOUT_CLASS = "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout"

    /**
     * The SECOND NT menu layout -- which is why the injection has TWO independent anchors.
     *
     * `com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout extends LinearLayout` and exposes the SAME
     * `setMenu(ui.c, View)` / `removeAllViews()` / `setPopupWindow(...)` / `setContainerBottom(...)` surface
     * as [MENU_LAYOUT_CLASS]: it is the text-only variant of the same menu. It has NO `A()` method, so a
     * hook on `A()` ALONE would never fire if QQ chose this layout.
     *
     * That is not a hypothetical: the on-device probe reported `#A()V` as never called, which is
     * incompatible with the bytecode (all four strategy classes -- `strategy.c/e/j/p#b` -- invoke it, and
     * `strategy.c#b` constructs [MENU_LAYOUT_CLASS] itself). The most likely explanation is that the
     * rendered menu is the OTHER layout, so the injection must not depend on which one wins.
     *
     * ⚠ DO NOT CONFUSE THIS WITH `com.tencent.mobileqq.utils.dialogutils.QQCustomMenuNoIconLayout`.
     * That is a DIFFERENT class in a different package; it is the one actually constructed by
     * profilecard / qrscan / markdown code, and it is NOT the chat path. The two share a simple name --
     * exactly the trap the recon recorded. Always use the fully-qualified name.
     */
    const val MENU_LAYOUT_ALT_CLASS = "com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout"

    /** The per-row click listener whose `onClick` is the funnel into `ui.e#k()`. */
    const val ROW_CLICK_CLASS = "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout\$c"

    /** The dispatcher interface. `processor.a#a(ui.c, ui.e, Bundle)`. */
    const val PROCESSOR_IFACE = "com.tencent.qqnt.aio.menu.processor.a"

    /** The dispatcher that OWNS the key table. */
    const val PROCESSOR_DISPATCH_CLASS = "com.tencent.qqnt.aio.menu.processor.d"

    // --- methods, by their real names in 9.3.65

    /** `ui.c#a(ui.e)V` -- append one item. */
    const val MENU_ADD = "a"

    /** `ui.c#e()I` -- item count. */
    const val MENU_SIZE = "e"

    /** `ui.c#b(I)ui.e` -- item at index. */
    const val MENU_GET = "b"

    /** `ui.e#g()String` -- the dispatch key. */
    const val ITEM_KEY = "g"

    /** `ui.e#h()String` -- what the row DISPLAYS (the host implements it as `Context.getString(id)`). */
    const val ITEM_LABEL = "h"

    /** `ui.e#b()I` -- the icon's drawable resource id. */
    const val ITEM_ICON = "b"

    /** `ui.e#d()AIOMsgItem` -- the row's message. */
    const val ITEM_MESSAGE = "d"

    /** `ui.e#k()V` -- run the row's feature. Exactly ONE invoke site in all 41 dex files. */
    const val ITEM_CLICK = "k"

    /** `QQCustomMenuExpandableLayout#setMenu(ui.c, View)V` -- stores the container, then renders. */
    const val LAYOUT_SET_MENU = "setMenu"

    /** `QQCustomMenuExpandableLayout#A()V` -- the render method. Our injection anchor. */
    const val LAYOUT_RENDER = "A"

    // --- fields, read by TYPE wherever possible

    /** The `ui.c` field on [MENU_LAYOUT_CLASS]. Read by TYPE in code; recorded here as evidence. */
    const val LAYOUT_MENU_FIELD = "e"

    /** The `LinearLayout` that the host's rows are added to. Read by TYPE in code. */
    const val LAYOUT_ROWS_FIELD = "J"

    // ------------------------------------------------------------------ OUR row

    /**
     * Tag marking a row as OURS.
     *
     * A View tag rather than a boolean flag: after a rebuild the CONTAINER is a different instance while
     * a flag would still read "already added", silently leaving the new menu without a row. The same
     * reasoning as `KuaishouHooks.TOOLBAR_BUTTON_TAG`.
     */
    const val ROW_TAG_OURS = "luoliaoo.analysis.qq.row"

    /**
     * Tag for our SECOND row, the 浮窗 (floating-balloon) switch.
     *
     * A SEPARATE tag, not the one above: idempotence is checked by looking for a view carrying our
     * tag, so two rows sharing one tag would make "is our row already here?" answer yes for either
     * and silently drop whichever is added second. Every other host injects BOTH entries
     * (`Constants.OWN_MENU_TITLES`), and the user asked for both here as well.
     */
    const val ROW_TAG_OURS_BALLOON = "luoliaoo.balloon.qq.row"

    /**
     * Our 浮窗 row's label, from the shared constant so all five hosts word it identically.
     *
     * Deliberately DIFFERENT from [OUR_ITEM_LABEL]: the tap is identified by asking which of our
     * titles a row's subtree shows, so two rows sharing a title would make every tap ambiguous --
     * tapping 分析 could flip the balloon and vice versa. `Constants.OWN_MENU_TITLES` documents the
     * same rule for the other hosts.
     */
    val OUR_BALLOON_LABEL: String get() = com.luoliaoo.wechat.core.Constants.BALLON_ITEM_TITLE

    /** True when [tag] marks a view as our own injected row. */
    fun isOurRowTag(tag: Any?): Boolean = tag is String && tag == ROW_TAG_OURS

    /** True when [tag] marks one of OUR injected rows, either the 分析 or the 浮窗 one. */
    fun isAnyOfOurRowTags(tag: Any?): Boolean =
        tag is String && (tag == ROW_TAG_OURS || tag == ROW_TAG_OURS_BALLOON)

    /**
     * Decides whether a tapped menu item is OURS, by instance identity.
     *
     * Kept as a pure function over (instance, known keys) so BOTH directions are testable:
     *   * false negative -> our row does nothing;
     *   * false positive -> a host row (撤回 / 删除 / 多选) silently stops working, which is worse,
     *     because it breaks a feature the user already had.
     *
     * A null item is NOT ours: the host always passes a real `ui.e`, so a null comes from a re-entrant or
     * reordered call we do not understand, and treating "unknown" as "ours" is precisely what starts
     * breaking host rows (`KuaishouMenuRouting` documents the same asymmetry).
     */
    fun isOurItem(tapped: Any?, ourInstanceKeys: Set<Int>): Boolean {
        if (tapped == null) return false
        return ourInstanceKeys.contains(System.identityHashCode(tapped))
    }

    /**
     * A second, independent identity signal: OUR key, should the host ever hand us something whose
     * identity no longer matches.
     *
     * On QQ this is a defensive extra rather than a load-bearing check, because the row is injected as a
     * View and carries our own click listener. It exists so the routing code has one predicate that is
     * true for our row and for nothing else.
     */
    fun isOurKey(key: String?): Boolean = key != null && key == OUR_ITEM_KEY

    /**
     * OUR key, in the same namespace as the host's.
     *
     * Recorded even though the shipped row is a View, for two reasons: it is the identity our row would
     * use if it were ever built as a `ui.e` item, and it documents WHY an unknown key is safe (see KDoc 1).
     * No host key starts with this prefix, and a future QQ build adding its own items cannot make this
     * key fire something else.
     */
    const val OUR_ITEM_KEY = "LuoLiaoAnalysisMenuItem"

    /**
     * OUR row label, shared with every other host so the QQ row reads the same.
     *
     * ASCII-only rule: the Chinese value lives in `Constants.ITEM_TITLE`, this only re-exports it.
     */
    val OUR_ITEM_LABEL: String get() = com.luoliaoo.wechat.core.Constants.ITEM_TITLE

    /**
     * The host key for ITS multi-select entry.
     *
     * From `processor.d#<init>` [0086]: `MultiSelectMenuItem` maps to `MenuMultiSelectProcessor`. We never
     * trigger it; it is recorded so a log can distinguish "the user entered multi-select" from "our code
     * did something".
     */
    const val HOST_KEY_MULTI_SELECT = "MultiSelectMenuItem"

    /**
     * The host's 21 dispatch keys, transcribed from `processor.d#<init>`.
     *
     * EVIDENCE, NOT A SWITCH: nothing in the code branches on this list. It exists so a diagnostic line
     * can state that a row belongs to the host, and so a future reader can confirm our key is not among
     * them without re-running the disassembler.
     */
    val HOST_KEYS: List<String> = listOf(
        "RevokeMenuItem", "DelMenuItem", "AddEssenceMenuItem", "TroopTodoMenuItem",
        "ReplyMenuItem", "RelatedEmotionMenuItem", "AskQBaoMenuItem", "YuanbaoAiMenuItem",
        "MultiSelectMenuItem", "SaveWeiYunMenuItem", "UpcomingMenuItem", "ScreenShotMenuItem",
        "SendToPcMenuItem", "AddOneMenuItem", "ForwardGroupAlbumMenuItem", "RobotReportMenuItem",
        "JumpDressUpMenuItem", "TranslateMenuItem", "MakeSameMenuItem", "TroopDragonLadderMenuItem",
        "ShootSameMenuItem", "SendSameMenuItem", "PartialSelectMenuItem",
    )
}
