package com.luoliaoo.wechat.hook

/**
 * Xiaohongshu's identity data: the class names and shapes this host's hooks must match.
 *
 * ============================== WHERE EVERY NAME HERE COMES FROM ==============================
 *
 * NOTHING in this file is guessed. Each constant cites how it was measured, and the two methods used
 * are complementary -- one reports what is ON SCREEN, the other what EXISTS in the APK:
 *
 *   * `[ON-DEVICE]`  = `adb shell "su -c 'dumpsys activity top'"` while the user long-pressed a
 *     message. This prints the LIVE view tree with each node's REAL class name, its resource id and
 *     its geometry. It is the strongest evidence available: a class in this output is proven to be
 *     instantiated and attached at the moment the menu is on screen.
 *     NOTE `uiautomator dump` must NOT be used for this -- it forces a re-layout and DISMISSES the
 *     PopupWindow, so the menu is gone before it can be read. That mistake cost a round.
 *
 *   * `[DEX]` = the shipped APK (`com.xingin.xhs` 9.47.0, 180 MB, 24 dex) was unpacked and its
 *     class_defs read directly. This gives the superclass chain, the fields and the method
 *     signatures, which a view tree cannot show.
 *
 * The full recon record is `_tools/xhs/XIAOHONGSHU_RECON.md`.
 *
 * Comments are ASCII-only in this file; Chinese text uses \uXXXX escapes, matching core/Log.kt's rule.
 */
internal object XiaohongshuMenuIdentity {

    /**
     * The long-press menu's container view.
     *
     * `[ON-DEVICE]` appeared in the `dumpsys activity top` view tree while the menu was up.
     * `[DEX]` `super_of` reports it extends `androidx.constraintlayout.widget.ConstraintLayout`.
     *
     * This is the class a hook matches to know "the menu is now on screen", and the view our entry is
     * added to -- exactly the role `QQCustomMenuExpandableLayout` plays on QQ and
     * `ChatLongClickPopUp` plays on Soul.
     */
    const val MENU_VIEW = "com.xingin.im.v2.message.views.MenuView"

    /**
     * ONE menu row's view.
     *
     * `[ON-DEVICE]` present in the view tree alongside [MENU_VIEW].
     * `[DEX]` extends `ConstraintLayout`.
     *
     * PAIRED WITH `ImMenuItemLayoutBinding`, whose fields are the reason this host is easy: the binding
     * for a row is
     *
     *     a  android/widget/LinearLayout
     *     b  android/widget/ImageView
     *     c  android/widget/TextView
     *
     * i.e. a row is icon + label. Our entry clones exactly that shape, so it is indistinguishable in
     * geometry from the host's own rows -- the same technique [buildRowFor] uses on QQ.
     */
    const val ITEM_VIEW = "com.xingin.im.v2.message.views.MenuItemView"

    /**
     * The layout-binding class for a menu ROW, used to READ a host row's label and to verify the shape
     * our clone must reproduce.
     *
     * `[DEX]` classes5.dex, fields `a`/`b`/`c` as documented on [ITEM_VIEW].
     */
    const val ITEM_BINDING = "com.xingin.im.databinding.ImMenuItemLayoutBinding"

    /**
     * The layout-binding class for the menu as a whole.
     *
     * `[DEX]` classes5.dex AND classes6.dex (both declare it -- Xiaohongshu ships two copies, which is
     * normal for a multidex app whose modules were compiled separately).
     */
    const val MENU_BINDING = "com.xingin.im.databinding.ImMessageMenuBinding"

    /**
     * The menu's POPUP WINDOW, as a field on [MENU_VIEW].
     *
     * `[DEX]` `MenuView`'s field list contains `i : android/widget/PopupWindow`.
     *
     * WHY THIS MATTERS, and it is the single biggest difference from QQ: Xiaohongshu's menu is an
     * ordinary `android.widget.PopupWindow`. QQ's is a custom `BubblePopupWindow` that had to be found
     * by scanning the framework's root-view list, because no standard hook fired for it. Here the
     * standard anchors DO fire -- verified by `[DEX]`: Xiaohongshu references `showAtLocation` and
     * `showAsDropDown` in 20 of its 24 dex files. So the ordinary popup hooks are the PRIMARY route
     * and the window-list probe is only a fallback.
     */
    const val MENU_POPUP_FIELD = "i"

    /**
     * The menu's ITEM LIST, as a field on [MENU_VIEW].
     *
     * `[DEX]` `MenuView`'s field list contains `o : java/util/List`.
     *
     * This is the list a new entry would be appended to. Our implementation adds a VIEW rather than a
     * list element (the host's own render pass positions the views), but the field is recorded here
     * because it is how the host's rows are ENUMERATED for the anchor search.
     */
    const val MENU_LIST_FIELD = "o"

    /**
     * The menu-item DATA model, one instance per row.
     *
     * `[DEX]` classes2.dex. NOT the message model -- an early misread this constant's comment corrects,
     * because that misread would have sent the message reader to the wrong class.
     *
     * Its field names are readable English, which is why this host needs no obfuscation-agnostic
     * shape-matching fallback (QQ needed one, and it is what broke on QQ 9.1.97):
     *
     *     menuItemIcon      java/lang/String   the row's icon
     *     menuItemIconDark  java/lang/String   the dark-theme icon
     *     componentName     java/lang/String   what tapping the row DOES
     *     id                I                  the row's id
     *     isNeedShow        Z                  whether the row is shown
     *
     * `isNeedShow` is the reason the menu's item count CHANGES with the message type (the user
     * confirmed: a text message shows 复制/转发/引用/顶/便利贴/删除, an image shows
     * 转发/引用/便利贴/添加表情/删除). So the entry must never be positioned by index -- see
     * [HOST_ROW_LABELS].
     */
    const val ITEM_MODEL = "sk4.e"

    /**
     * The chat Activity, used as the scope check so our hooks only act inside the chat screen.
     *
     * `[ON-DEVICE]` `dumpsys window windows` reports
     * `Window{... u0 com.xingin.xhs/com.xingin.im.ui.activity.ChatActivity}`.
     */
    const val CHAT_ACTIVITY = "com.xingin.im.ui.activity.ChatActivity"

    /**
     * The message list, used by the history feeder.
     *
     * `[ON-DEVICE]` present in the chat page's view tree.
     * `[DEX]` extends `androidx.recyclerview.widget.RecyclerView`.
     */
    const val MESSAGE_LIST = "com.xingin.im.ui.view.ChatPageRecyclerView"

    /**
     * A message's TEXT view. Extends `AppCompatTextView` `[DEX]`.
     *
     * `[ON-DEVICE]` present in the chat page's view tree, which is how the message reader recovers a
     * message's text from a row when no adapter-level accessor is reachable.
     */
    const val MESSAGE_TEXT_VIEW =
        "com.xingin.im.ui.adapter.multi.text.ChatStickerDoubleClickTextView"

    /**
     * The menu ITEM BINDER -- the class that receives the MESSAGE, and therefore the best hook point
     * on this host.
     *
     * `[DEX]` measured structure:
     *
     *     LongPressMenuItemBinder extends da.c extends da.d      (da.d is MultiType's ItemViewBinder)
     *     <init>(jy7.n, com.xingin.chatbase.bean.MsgUIData)      <== the message is a CONSTRUCTOR arg
     *     onBindViewHolder(RecyclerView$ViewHolder, Object)      <== arg[1] IS the message
     *     onCreateViewHolder(LayoutInflater, ViewGroup)
     *     field e : com.xingin.chatbase.bean.MsgUIData
     *
     * ============================== WHY THIS AND NOT THE MENU CLASS ==============================
     *
     * This is the hook that actually WORKS, and the reason is worth recording because two earlier
     * attempts failed on the same misunderstanding: Xiaohongshu's long-press menu is a RecyclerView, so
     * its rows are produced by ADAPTER BINDERS -- there is no "build me a row" call to intercept.
     *
     *   * `MenuView#m4` LOOKS like the row builder (it takes a `MenuItemView`), and hooking it installed
     *     cleanly and then NEVER FIRED -- the device log showed `menusSeen=0` after many long-presses.
     *   * Walking the adapter's data list (`MultiTypeAdapter.d`, verified as a List of `ly7.c` items)
     *     reached the right objects but their payload sits behind OBFUSCATED types (`jy7.o`, `a:o`),
     *     which a name-based reader cannot follow.
     *
     * `onBindViewHolder` sidesteps both: the host HANDS this method the message as a plain argument, so
     * there is nothing to search for and nothing to guess. Its parameter is declared as `Object`
     * because MultiType's binder base is generic -- the runtime class is what identifies it, which is
     * exactly what [XiaohongshuMsgAccess.isMessage] tests.
     */
    const val ITEM_BINDER = "com.xingin.im.ui.widgets.pressmenu.itembinder.LongPressMenuItemBinder"

    /**
     * The menu's message model.
     *
     * `[DEX]` `com.xingin.chatbase.bean.MsgUIData`, 71 fields, all readable English:
     *
     *     msgId      java/lang/String     the message id
     *     chatId     java/lang/String     the conversation
     *     creatTime  J                    the timestamp (the host really does spell it `creatTime`)
     *     msgType    I                    the message type
     *     msgGeneralBean  MsgGeneralBean  <== holds `content`, the message TEXT
     *     imageMsg / dslBean / dynamicCard / msgVoiceCallBean ...   the other content shapes
     *
     * NOTE where this class LIVES: `com.xingin.chatbase.bean`, NOT `com.xingin.im.*`. That is why the
     * reader's package test had to be widened -- the first version listed only the IM packages, so the
     * REAL message was rejected and every tap on the injected entry reported "no message captured".
     * The truth came from a ROOT READ of the live process, whose loaded-class list contained
     * `Lcom/xingin/chatbase/bean/MsgUIData;`.
     */
    const val MSG_UI_DATA = "com.xingin.chatbase.bean.MsgUIData"

    /**
     * The bean holding a message's TEXT.
     *
     * `[DEX]` `com.xingin.chatbase.bean.MsgGeneralBean` declares `content` (the text), plus
     * `subType`, `operationInfo` and the reply/quote fields. Reached from [MSG_UI_DATA] through the
     * `msgGeneralBean` field, which is why the reader has a NESTED content lookup.
     */
    const val MSG_GENERAL_BEAN = "com.xingin.chatbase.bean.MsgGeneralBean"

    /**
     * The standalone message bean, used by quote/reply payloads.
     *
     * `[DEX]` `com.xingin.chatbase.bean.MessageBean` declares `content`, `id`, `createdAt`, `senderId`
     * and `refMessage`. It is NOT what the menu binds (that is [MSG_UI_DATA]) but it appears inside
     * `MsgUIData.refMessageBean`, so the reader treats it as a message too.
     */
    const val MESSAGE_BEAN = "com.xingin.chatbase.bean.MessageBean"

    /**
     * The labels the host's own rows carry.
     *
     * `[USER]` the user read these off the screen and reported them, together with the fact that the
     * SET changes with the message type:
     *
     *     another person's text : 复制 转发 引用 顶 便利贴 删除  (+ 举报 on a second row)
     *     your own text         : 复制 转发 引用 顶 便利贴 撤回
     *     an image              : 转发 引用 便利贴 添加表情 删除 举报
     *
     * The anchor we locate our entry against is [ANCHOR_LABEL], chosen because it is the ONE that
     * appears in all three sets. Positioning by index would break on the image case, which is exactly
     * the failure that put the QQ entry in a corner until it was changed to anchor on a sibling.
     */
    val HOST_ROW_LABELS = listOf(
        "\u590d\u5236",   // 复制
        "\u8f6c\u53d1",   // 转发
        "\u5f15\u7528",   // 引用
        "\u9876",         // 顶
        "\u4fbf\u5229\u8d34", // 便利贴
        "\u5220\u9664",   // 删除
        "\u64a4\u56de",   // 撤回
        "\u4e3e\u62a5",   // 举报
        "\u6dfb\u52a0\u8868\u60c5", // 添加表情
    )

    /**
     * The row our entry is inserted AFTER.
     *
     * WHY THIS ONE: it is the only label present in every menu variant the user reported, including
     * the image menu which drops 复制 entirely. Anchoring on 复制 would put our entry nowhere on an
     * image message; anchoring on 转发 works in all three cases and also reads naturally, because
     * 分析 is an adjacent "do something with this message" action.
     *
     * When the anchor is not found the implementation falls back to appending at the end rather than
     * failing, so a future menu reordering degrades the POSITION, not the feature.
     */
    const val ANCHOR_LABEL = "\u8f6c\u53d1"   // 转发

    /** Our own row's label, and the tag that makes the injection idempotent. */
    const val OUR_LABEL = "\u5206\u6790"                       // 分析
    const val OUR_ROW_TAG = "luoliaoo.analysis.xhs.row"
}
