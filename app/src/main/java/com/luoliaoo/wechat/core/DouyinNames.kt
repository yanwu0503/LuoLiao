package com.luoliaoo.wechat.core

/**
 * Every DOUYIN-side class / method / field name the module needs, in ONE place.
 *
 * WHY A SEPARATE FILE FROM Resolver.kt:
 * `Resolver` anchors WeChat through obfuscated names plus a DexKit string search. Douyin is the
 * opposite situation -- its IM data model is NOT obfuscated at all, so most of what we need is a
 * literal, greppable name. Only the chat UI layer (`X.*`) is obfuscated, and those few names are
 * marked CANDIDATE below and probed at runtime instead of trusted.
 *
 * HOW EVERY NAME BELOW WAS CONFIRMED (evidence, not guesswork):
 * The 39.9.0 APK was unpacked to 55 dex files (473 MB) and scanned with the tools in `_tools/`:
 *   `dy_xref.py fields <regex>`   -- prints a class's fields + method signatures
 *   `dy_xref.py str <regex>`      -- searches the string pool
 *   `dy_xref.py xref <regex>`     -- finds WHICH class/method references a string constant
 *   `dy_dis.py  code <cls> <m>`   -- disassembles a method (const-string + invoke targets)
 *   `dy_typeuse.py <regex>`       -- finds every class that constructs/calls a given type
 * The full write-up, with the raw command output behind each line, is
 * `_tools/DOUYIN_HOOK_POINTS.md`. Each constant below names its dex + how it was found.
 *
 * Comments are ASCII-only; Chinese text uses \uXXXX escapes (see core/Log.kt).
 */
object DouyinNames {

    // ==================================================================================
    // 1. THE MESSAGE MODEL  (NOT obfuscated -- verified line by line)
    // ==================================================================================

    /**
     * The base message object, CONFIRMED un-obfuscated.
     *
     * Found with `dy_xref.py classes "im\.core\.model\."`; class def lives in classes44.dex and
     * extends java.lang.Object. Its 137 methods keep their real names, which is what makes the
     * Douyin side far easier than WeChat's.
     */
    const val MESSAGE = "com.bytedance.im.core.model.Message"

    /**
     * The subclass the chat UI actually hands to a view holder.
     *
     * CONFIRMED: classes29.dex, `extends com.bytedance.im.core.model.Message`.
     * It holds `delegateMsg` (the real Message) and `mappedContent` (the parsed BaseContent) and
     * overrides the getters by delegating to `delegateMsg` -- verified by disassembling
     * `ProxyMessage.getMsgType()`, which is literally
     * `invoke-virtual Message->getMsgType()I`.
     *
     * WHY IT MATTERS: an `==` type test against [MESSAGE] rejects every real chat message. Callers
     * must use `isAssignableFrom`.
     */
    const val PROXY_MESSAGE = "com.ss.android.ugc.aweme.im.business.chat.msgcell.ProxyMessage"

    /** Prefix shared by every IM model class; used only as a last-resort fallback test. */
    const val IM_MODEL_PREFIX = "com.bytedance.im.core.model."

    /**
     * The parsed content object carried by a text message (Douyin's "BaseContent" family).
     *
     * CONFIRMED: classes29.dex, `extends ...im.sdk.chat.model.BaseContent`.
     */
    const val TEXT_CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.model.TextContent"

    /** Parent of [TEXT_CONTENT]; every message payload class derives from it. */
    const val BASE_CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.model.BaseContent"

    // ==================================================================================
    // 2. MESSAGE TEXT ACCESSORS
    // ==================================================================================

    /** Body as a JSON string. CONFIRMED getter on Message: `getContent()Ljava/lang/String;`. */
    const val M_CONTENT = "getContent"

    /**
     * THE plain-text FIELD on [TEXT_CONTENT]. CONFIRMED: `dy_xref.py fields "chat\.model\.TextContent"`
     * lists `java.lang.String text` among its 22 fields -- and the FIELD NAME IS NOT OBFUSCATED.
     *
     * This is the most reliable way to read the text of a text message: read the field, do not chase
     * a getter, because only ONE of TextContent's accessors kept a readable name (see [M_SET_TEXT]).
     */
    const val F_TEXT = "text"

    /**
     * The ONE accessor on [TEXT_CONTENT] that kept its real name. CONFIRMED in the same dump:
     * `setText(Ljava/lang/String;)V`. Every other reader/writer on that class is obfuscated to the
     * `LJJxx` form, which is why this file exposes the FIELD for reading and this method for writing.
     */
    const val M_SET_TEXT = "setText"

    /**
     * CONFIRMED getters on Message (all `dy_xref.py fields "im\.core\.model\.Message$"`):
     *   getConversationId()Ljava/lang/String;   -- the conversation this message belongs to
     *   getSender()J                            -- sender uid (numeric)
     *   getSecSender()Ljava/lang/String;        -- sender sec_uid (string form)
     *   isSelf()Z                               -- sent by me
     *   getMsgType()I                           -- message type discriminator
     *   getCreatedAt()J                         -- timestamp
     *   getMsgId()J                             -- message id
     *   isRecalled()Z                           -- recalled (must be skipped)
     */
    const val M_CONVERSATION_ID = "getConversationId"
    const val M_SENDER = "getSender"
    const val M_SEC_SENDER = "getSecSender"
    const val M_IS_SELF = "isSelf"
    const val M_MSG_TYPE = "getMsgType"
    const val M_CREATED_AT = "getCreatedAt"
    const val M_MSG_ID = "getMsgId"
    const val M_IS_RECALLED = "isRecalled"

    /**
     * Payload accessors on Message: `getContentObj()` returns the parsed BaseContent, and
     * `getContent()` returns its serialised JSON. CONFIRMED -- `getContentObj` appears in the
     * Message method dump as `LJI()Ljava/lang/Object;`'s public wrapper region; treat the JSON
     * [M_CONTENT] as the safe path and the object as an optimisation.
     */
    const val M_CONTENT_OBJ = "getContentObj"

    /**
     * Read [TEXT_CONTENT]'s text through ONE of these, probing in order until a non-blank string
     * comes back.
     *
     * WHY A LIST RATHER THAN A NAME: reading the FIELD [F_TEXT] is preferred, but the object handed
     * to a hook may be a subclass that re-declares the field, or a version where reflection on the
     * field is blocked. The two obfuscated-looking entries below were extracted from the TextContent
     * method dump as the string-returning no-arg methods (`LJJL`, `LJJJLZIJ`, `LJIJI`, `LJJJJL`,
     * `LJJJJLI`, `LJJLIIIIJ` all return `Ljava/lang/String;`) -- they are CANDIDATES because a
     * method that returns String is not necessarily the body.
     *
     * ORDER: field first (certain), then the candidates (best effort). A call site must accept an
     * empty string rather than treat it as an error.
     */
    val TEXT_READERS: List<String> = listOf(
        "LJJL",        // CANDIDATE: ()Ljava/lang/String; on TextContent
        "LJJJLZIJ",    // CANDIDATE: ()Ljava/lang/String; on TextContent
        "LJJJJL",      // CANDIDATE: ()Ljava/lang/String; on TextContent
        "LJJLIIIIJ",   // CANDIDATE: ()Ljava/lang/String; on TextContent
    )

    /**
     * Message types that mean "plain text", as a SET rather than a single number.
     *
     * *** THIS IS THE ONE VALUE IN THIS FILE THAT IS NOT TRUSTWORTHY. ***
     *
     * The scaffolded `DouyinMsgAccess` hardcoded a single `TYPE_TEXT = 7`. Decoding the enum in
     * classes44.dex shows that is WRONG for the proto enum: in
     * `com.bytedance.im.core.proto.MessageType` the ordinal 7 is `LEGACY_MESSAGE_TYPE_FILE`,
     * while text is 8 (`LEGACY_MESSAGE_TYPE_TEXT`) and 12 (`MESSAGE_TYPE_TEXT`).
     * Decoded output (dy_scan instruction walk of MessageType.<clinit>):
     *     7  = LEGACY_MESSAGE_TYPE_FILE
     *     8  = LEGACY_MESSAGE_TYPE_TEXT
     *     12 = MESSAGE_TYPE_TEXT
     *
     * `Message.getMsgType()` is a CLIENT-side field, and the client enum is not necessarily the
     * proto enum -- which is exactly why this list is a SET of candidates instead of one constant,
     * and why [isTextType] must ALSO accept the empty set when the body is non-blank.
     *
     * UNVERIFIED: the value the LIVE 39.9.0 client actually puts in `Message.msgType` for a text
     * message. Confirm on device by logging `getMsgType()` for a known text message (the probe in
     * `DouyinMsgAccess.probe` already prints it) and then narrowing this list.
     */
    val TEXT_MSG_TYPES: Set<Int> = setOf(7, 8, 12)

    /**
     * True when [type] may be a plain-text message.
     *
     * Deliberately PERMISSIVE, and that is a correctness decision rather than laziness: the body
     * check in `DouyinMsgAccess.isText` already rejects a message with no text, so being strict here
     * can only ever DROP a real text message (the failure the user sees as "analysis found nothing"),
     * while being permissive at worst lets one extra message through to a body check that filters it.
     * A negative value is the getter's own "could not read" answer (see `callInt`), never a real type.
     */
    fun isTextType(type: Int): Boolean = type in TEXT_MSG_TYPES || type < 0

    // ==================================================================================
    // 3. THE LONG-PRESS MENU
    // ==================================================================================

    /**
     * Douyin's message long-press menu. CONFIRMED, classes29.dex.
     *
     * Found by `dy_typeuse.py "LongClickWindowManagerNew"`, which showed the ONE place it is
     * constructed: `...im.business.chat.msgcell.depend.MsgCellDependImpl.LJFF`.
     *
     * THE NAME IS STABLE (not obfuscated); its METHODS are obfuscated. It owns the fields
     * `currentMessage` (a Message), `sessionInfo` (a SessionInfo) and `isShowPopWindow` (boolean),
     * which are what a runtime probe should key on if the class name ever moves.
     */
    const val LONG_CLICK_MANAGER =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickWindowManagerNew"

    /**
     * The method that RETURNS the menu's PopupWindow. CONFIRMED by disassembling
     * `LongClickWindowManagerNew.LJ()`: its first instruction is
     * `invoke-virtual ...LJII()Landroid/widget/PopupWindow;` followed by
     * `android.widget.PopupWindow->isShowing()Z`.
     *
     * OBFUSCATED NAME: stable within 39.9.0 only. Resolve by RETURN TYPE, never by this string.
     */
    const val M_MENU_POPUP = "LJII"
    const val MENU_POPUP_RETURN = "android.widget.PopupWindow"

    /**
     * The method that SHOWS the menu with the pressed message. CONFIRMED signature:
     *   LIZIZ(Landroid/view/View;LX/1C7V;Lcom/bytedance/im/core/model/Message;
     *         Lcom/ss/android/ugc/aweme/im/sdk/chat/model/BaseContent;LX/1C4w;
     *         Lkotlin/jvm/functions/Function0;LX/1BZd;)V
     *
     * OBFUSCATED. Resolve by SHAPE, not by name: a void method whose parameters include BOTH
     * [MESSAGE] and [BASE_CONTENT], declared on [LONG_CLICK_MANAGER].
     */
    const val M_MENU_SHOW = "LIZIZ"

    /**
     * Dismisses the menu. CONFIRMED -- this one KEPT its real name on the manager
     * (it appears as `dismiss()V` in the class dump).
     */
    const val M_MENU_DISMISS = "dismiss"

    /**
     * THE STRING ANCHOR -- the most durable handle on this whole menu.
     *
     * CONFIRMED as a real `const-string` constant in classes29.dex, reached by disassembling
     * `X.1CC7.LIZIZ(View, TextView)`:
     *     [0010] invoke-virtual  android.widget.TextView->setText(Ljava/lang/CharSequence;)V
     *     [0017] invoke-virtual  android.view.View->setId(I)V
     *     [001a] const-string    com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickFuncBtn.bindRemote()
     *
     * A log/assertion literal survives re-obfuscation, so this is the anchor to search for when the
     * class name or the method names move. `dy_xref.py xref "LongClickFuncBtn"` reproduces the find.
     */
    const val ANCHOR_MENU_BIND_REMOTE =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickFuncBtn.bindRemote()"

    /**
     * A second, softer anchor: the manager logs this while debouncing a repeated show.
     * CONFIRMED as a const-string in `LongClickWindowManagerNew.LIZIZ`.
     */
    const val ANCHOR_MENU_DEBOUNCE = "LongClickWindowManagerNew show debounced key="

    /**
     * The base class of ONE menu row. OBFUSCATED (classes29.dex).
     *
     * Its `LJII()Ljava/lang/String;` returns a STABLE ENGLISH KEY identifying the row -- not a
     * translated label. That distinction is the single most important thing to know about this menu:
     * see [MENU_KEYS] and the warning on [MENU_LABELS_IN_ARSC].
     */
    const val MENU_ROW_BASE = "X.1CC7"

    /** Method on [MENU_ROW_BASE] returning the row's stable key. CONFIRMED via `dy_xref.py cstr`. */
    const val M_MENU_KEY = "LJII"

    /**
     * The row keys Douyin ships, each CONFIRMED by dumping the string constants of the ~40
     * subclasses listed in `X.1CC7.<clinit>` and reading each one's [M_MENU_KEY].
     *
     * USE THE KEY, NEVER A TRANSLATED LABEL. These keys are what `X.1CC7.LIZIZ` feeds into
     * `X.1izO.LIZ().LIZ()`'s Map lookup to obtain the display string, so they are also the identity
     * Douyin itself uses.
     *
     * Key -> meaning. The three the module cares about are marked.
     */
    val MENU_KEYS: Map<String, String> = linkedMapOf(
        "copy" to "copy the message body",                  // <-- needed for reading a quote
        "recall" to "recall (unsend)",                      // <--
        "forward" to "forward",                             // <--
        "multi" to "multi-select",
        "quote" to "quote / reply with reference",
        "delete" to "delete",
        "favourite" to "favourite",
        "edit" to "edit",
        "top" to "pin to top",
        "top_cancel" to "unpin",
        "search" to "search",
        "report" to "report",
        "feedback" to "feedback",
        "thread" to "thread / discussion",
        "to_text" to "speech to text",
        "fold_text" to "collapse the transcript",
        "ask_ai" to "ask the AI assistant",
        "group_ask_ai" to "ask the AI assistant (group)",
        "ask_pet_ai" to "ask the pet AI",
        "group_favorites" to "group favourites",
        "group_already_favorites" to "already in group favourites",
        "add_to_photo_album" to "save to photo album",
        "remove_from_photo_album" to "remove from photo album",
        "decorate" to "decorate",
        "decorate_emoticon" to "decorate the emoticon",
        "media_message_edit" to "edit media",
        "similar_emoji" to "find a similar emoji",
        "open_album" to "open the album",
        "star_shop" to "star shop",
        "publish" to "publish",
        "publish_ai_polishing" to "publish with AI polish",
        "like" to "like",
        "like_cancel" to "unlike",
        "shinebo_like" to "shinebo like",
        "shinebo_like_cancel" to "cancel shinebo like",
        "format_dislike" to "dislike",
        "format_dislike_cancel" to "cancel dislike",
        "dislike" to "dislike (pet AI)",
        "dislike_cancel" to "cancel dislike (pet AI)",
    )

    /**
     * Why the module must NOT search for menu rows by their Chinese label.
     *
     * MEASURED, and this is the trap that would silently break a WeChat-style port:
     *   - The WeChat implementation finds its menu rows by matching the literal text
     *     "\u590d\u5236" / "\u8f6c\u53d1" against the row's TextViews.
     *   - In Douyin those strings are NOT in any dex. `dy_xref.py xref` over
     *     copy/recall/forward/multi/quote/delete/favourite matched 171 places, of which only 7 are
     *     inside `com.ss.android.ugc.aweme.im.*` -- and all 7 are unrelated search-box hints
     *     (PickerSearchEditUI, SearchHeadListView, ...).
     *   - The labels live in resources.arsc instead (measured byte counts there: copy 135,
     *     recall 57, forward 90), and `X.1CC7.LIZIZ` proves it: it calls
     *     `Context.getString(int)` to set the row text.
     *
     * CONSEQUENCE: any label-matching code must be replaced by [M_MENU_KEY] / [MENU_KEYS] lookups.
     * A label match would compile, run, and never fire.
     */
    const val MENU_LABELS_IN_ARSC = true

    /**
     * The MENU CONFIG MODEL THAT IS A DEAD END -- recorded so nobody re-derives it.
     *
     * `im.sdk.chat.menu.bean.MenuItemStruct` and `im.sdk.chat.menu.api.MenuApi` DO exist
     * (classes46.dex) and look exactly like a menu definition. `dy_typeuse.py MenuItemStruct` shows
     * EVERY consumer is in `...im.business.menusettingpage.*` (IMChatMenuViewModel,
     * IMChatMenuMainFragment, PrivateMenuApapter, MenuItemViewHolder) plus the preview fragment.
     * That is the user-facing "long-press menu SETTINGS page", where the user reorders their menu --
     * NOT the code path that builds the menu in a live chat. The live menu is the hardcoded
     * [MENU_ROW_BASE] family.
     *
     * Similarly `im.sdk.chat.menu.view.MenuBubbleLayout` is only the bubble's background painter
     * (its fields are Paint / BlurMaskFilter / RADIUS), and `im.format.rips.longclick.
     * FormatLongClickFunBtnServiceLogic` serves only the "format chat" sub-scenario.
     */
    const val MENU_SETTINGS_MODEL = "com.ss.android.ugc.aweme.im.sdk.chat.menu.bean.MenuItemStruct"

    /**
     * A FALSE POSITIVE to never chase again.
     *
     * `X.1gXK` was proposed as the menu because it references delete/report/reply. Disassembly of
     * its single method shows the truth: its strings include
     * `com.ss.android.ugc.aweme.feed.danmaku.ui.DanmakuActionBubble.initDiggActionNew()` together
     * with `danmaku_id` / `danmaku_user_id` / `danmaku_inter_impression`, and `dy_typeuse.py
     * "1gXK"` shows its ONLY caller is `...feed.danmaku.DanmakuService.ye`. It is the DANMAKU
     * (video bullet-comment) long-press bubble, in the feed, not IM chat.
     */
    const val FALSE_MENU_CANDIDATE_DANMAKU = "X.1gXK"

    /**
     * The OTHER long press -- the AVATAR, not the message.
     *
     * `BasicViewHolder.onLongClick(View)` was originally believed to be the message long-press
     * entry, because the class is the shared view holder for every message type. Disassembling it
     * settles the question: it contains the literal `IMLongClickListener-TYPE_ITEM_AVATAR` and calls
     * `GroupMemberManager.LJIIJ(...)` to fetch an IMMember, i.e. it opens the MEMBER CARD when the
     * avatar is pressed.
     *
     * STILL USEFUL, just not as the menu entry: [M_HOLDER_CURRENT_MSG] and [M_HOLDER_SESSION] on the
     * same object are un-obfuscated and give the module the current message and session for free.
     */
    const val AVATAR_LONG_PRESS_HOLDER =
        "com.ss.android.ugc.aweme.im.business.chat.msgcell.BasicViewHolder"

    /** Un-obfuscated getter CONFIRMED on [AVATAR_LONG_PRESS_HOLDER]; returns the pressed Message. */
    const val M_HOLDER_CURRENT_MSG = "getCurrentMsg"

    /** Un-obfuscated getter CONFIRMED on [AVATAR_LONG_PRESS_HOLDER]; returns the SessionInfo. */
    const val M_HOLDER_SESSION = "getSessionInfo"

    /** The anchor string inside that avatar long-press path -- proves which gesture it serves. */
    const val ANCHOR_AVATAR_LONG_PRESS = "IMLongClickListener-TYPE_ITEM_AVATAR"

    // ==================================================================================
    // 4. THE INPUT BOX
    // ==================================================================================

    /**
     * Douyin's chat input box. CONFIRMED, classes39.dex:
     * `com.ss.android.ugc.aweme.im.sdk.chat.view.RichEditText extends
     *  com.bytedance.ies.dmt.ui.widget.DmtEditText` (which is an android.widget.EditText).
     *
     * Found with `dy_xref.py classes "im\..*EditText"`. THIS IS THE PRIMARY MATCH to prefer over the
     * module's existing "walk the view tree for any EditText" heuristic: it is exact, so it cannot
     * pick up an unrelated search field the way the heuristic can.
     */
    const val INPUT_EDIT = "com.ss.android.ugc.aweme.im.sdk.chat.view.RichEditText"

    /**
     * Subclass of [INPUT_EDIT], CONFIRMED in the same dex. It adds
     * `onTextContextMenuItem(I)Z` -- the input box's OWN copy/paste/select-all context menu, which is
     * the hook point if the module ever wants to add a row there.
     */
    const val INPUT_EDIT_FIXED = "com.ss.android.ugc.aweme.im.widget.FixedEditText"

    /** Subclass of [INPUT_EDIT_FIXED], CONFIRMED; adds `showContextMenu()`. */
    const val INPUT_EDIT_SEARCHABLE = "com.ss.android.ugc.aweme.im.widget.SearchableEditText"

    /**
     * The input box's own context-menu dispatcher. CONFIRMED on [INPUT_EDIT_FIXED]:
     * `onTextContextMenuItem(I)Z`.
     */
    const val M_INPUT_CONTEXT_MENU = "onTextContextMenuItem"

    /**
     * Best-effort classes for the input AREA (the container, not the EditText).
     *
     * CANDIDATES -- these names exist in the dex (classes24.dex / classes11.dex) but their
     * relationship to the live input box was NOT disassembled in this pass. They are listed so a
     * runtime probe has somewhere to look beyond the EditText itself; nothing should key on them.
     */
    val INPUT_AREA_CANDIDATES: List<String> = listOf(
        "com.ss.android.ugc.aweme.im.business.chat.feature.bottomarea.SingleChatBottomArea",
        "com.ss.android.ugc.aweme.im.format.chatroom.single.input.FormatChatBottomArea",
        "com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.new.InputDrawerUI2",
    )

    /**
     * UNVERIFIED: the input box's layout id.
     *
     * A search of the APK's `res/layout` XML for the tag `RichEditText` returned ZERO hits,
     * so the input area is likely inflated from a remote/dynamic template or built in code. The
     * strategy is therefore to find the instance, not the id: walk `decorView` for a
     * [INPUT_EDIT] `is`-check, exactly as the module's existing InputInjector does for plain
     * EditText. Do not add a guessed `R.id` here.
     *
     * NOTE (why the path above is written without a glob): the literal "slash-star" seq
     * inside a Kotlin block comment OPENS A NESTED COMMENT, because Kotlin block comments
     * nest. Written the obvious way it swallowed every following `/`-star terminator and the
     * whole file failed to compile with "Unclosed comment". Keep globs out of these comments.
     */
    const val INPUT_ID_UNVERIFIED = true

    // ==================================================================================
    // 5. CONVERSATION / PEER IDENTITY
    // ==================================================================================

    /**
     * The conversation record. CONFIRMED, classes44.dex (78 fields, names NOT obfuscated):
     *   conversationId (String), conversationShortId (long), conversationType (int),
     *   member (Member), singleChatMembers (List), memberIds (List), members (List)
     */
    const val CONVERSATION = "com.bytedance.im.core.model.Conversation"

    /** CONFIRMED field/getter: the conversation id -- Douyin's stand-in for WeChat's `talker`. */
    const val F_CONVERSATION_ID = "conversationId"

    /**
     * `Conversation.getConversationId()` -- the SAME method name as [M_CONVERSATION_ID] on Message.
     *
     * Kept as its own constant because the two live on different classes and a rename of one would
     * not imply the other, but the literal is identical, so it is spelled once here rather than
     * duplicated (a duplicate `const` in one object is a compile error -- caught by the compiler).
     */
    const val M_CONV_GET_ID = M_CONVERSATION_ID

    /** CONFIRMED field: the single-chat peer (a Member). */
    const val F_CONVERSATION_MEMBER = "member"

    /** CONFIRMED field: list of the single chat's members. */
    const val F_CONVERSATION_SINGLE_MEMBERS = "singleChatMembers"

    /**
     * A conversation member. CONFIRMED, classes44.dex, names not obfuscated:
     *   uid (String), secUid (String), alias (String), conversationId (String)
     *
     * NOTE: unlike WeChat, Douyin has NO `talker` field that IS the other party's id. In a 1:1 chat
     * the peer must be derived: either `Conversation.member` / `singleChatMembers`, or -- far more
     * simply -- from the pressed message itself ([M_SENDER] / [M_SEC_SENDER]), which every hook
     * point in section 3 already hands us.
     */
    const val MEMBER = "com.bytedance.im.core.model.Member"
    const val F_MEMBER_UID = "uid"
    const val F_MEMBER_SEC_UID = "secUid"

    /**
     * The official conversationId -> uid parser. CONFIRMED, classes44.dex:
     *   `getUidFromConversationId(Ljava/lang/String;)J`
     *   `findConversationIdByUid(IJ)Ljava/lang/String;`
     *
     * Prefer this over hand-parsing the id string: the format is an internal detail that has
     * changed before.
     */
    const val CONV_MODEL_EXT = "com.bytedance.im.core.model.IConversationModelMultiInstanceExt"
    const val M_UID_FROM_CONVERSATION = "getUidFromConversationId"

    /**
     * The conversation lookup entry point. CONFIRMED by `dy_typeuse.py ConversationListModel`,
     * which showed the call pattern used in 12+ places:
     *   `com.bytedance.ies.im.core.api.client.ConversationListModel.Companion.inst()`
     *     -> `IConversationListModel.getConversation(String)` -> Conversation
     */
    const val CONVERSATION_LIST_MODEL =
        "com.bytedance.ies.im.core.api.client.ConversationListModel"
    const val I_CONVERSATION_LIST_MODEL = "com.bytedance.im.core.model.IConversationListModel"
    const val M_GET_CONVERSATION = "getConversation"

    /**
     * The chat session object, CONFIRMED classes31.dex (107 fields, names not obfuscated).
     * It carries `conversationId` plus the Douyin-side chat flags, and it is what
     * [M_HOLDER_SESSION] returns.
     */
    const val SESSION_INFO = "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo"

    // ==================================================================================
    // 6. MY OWN DOUYIN IDENTITY
    // ==================================================================================

    /**
     * The account service proxy. CONFIRMED, classes13.dex:
     *   `userService()Lcom/ss/android/ugc/aweme/account/service/IAccountUserService;`
     *
     * Call it reflectively as static `userService()`, then ask the returned object. Reach it by the
     * SERVICE INTERFACE below rather than the concrete impl, because the impl class name is a
     * feature-module name that moves between releases.
     */
    const val ACCOUNT_PROXY = "com.ss.android.ugc.aweme.account.AccountProxyService"
    const val M_USER_SERVICE = "userService"

    /** The interface returned by [M_USER_SERVICE]. CONFIRMED, classes13.dex. */
    const val ACCOUNT_USER_SERVICE = "com.ss.android.ugc.aweme.account.service.IAccountUserService"

    /** CONFIRMED on that interface: my numeric uid. */
    const val M_CUR_USER_ID = "getCurUserId"

    /** CONFIRMED on that interface: my sec_uid. */
    const val M_CUR_SEC_USER_ID = "getCurSecUserId"

    /** CONFIRMED on that interface: my full profile object (`com...profile.model.User`). */
    const val M_CUR_USER = "getCurUser"

    /**
     * The IM-side shortcut to the same thing. CONFIRMED, classes31.dex -- its body is
     * `invoke-static ...im.sdk.lowperformance.UserOptHelper->getCurrentUser()`.
     * Use it as a fallback when the account service is not reachable from the hook.
     */
    const val IM_APP_UTIL = "com.ss.android.ugc.aweme.im.sdk.utils.AppUtil"
    const val M_IM_CURRENT_USER = "getCurrentUser"

    /**
     * The IM user record. CONFIRMED, classes31.dex, `extends com.ss.android.ugc.aweme.im.service.model.IMContact`.
     *
     * Relevant FIELDS (names NOT obfuscated -- verified by dumping the class):
     *   uniqueId (String), shortId (String), uid (String), secUid (String),
     *   nickName / nickNamePinyin / nickNameInitial (String)
     *
     * *** CAUTION -- the ACCESSORS ARE NOT ALL NAMED LIKE THE FIELDS. ***
     * Measured on this exact class: only these String-returning accessors keep readable names --
     *   getDisplayName(), getNickname(), getSecUid(), getSingleId(), getUid(), getUidS()
     * There is NO `getUniqueId()` and NO `getShortId()` declared on IMUser (or on its parent
     * IMContact): both were checked directly and are absent. The `uniqueId` / `shortId` FIELDS
     * exist, but their accessors are among the ~44 obfuscated `LJJxx`/`LJLxx` methods on the class,
     * so an accessor for them must be FOUND, not named.
     *
     * (`getUniqueId()` does exist elsewhere in the APK -- on `X.0RYj` / `X.0RZE`, the profile
     * `User` models -- which is why the naive `method("getUniqueId")` lookup is a trap: it resolves
     * on some OTHER object and returns a plausible-looking wrong id.)
     */
    const val IM_USER = "com.ss.android.ugc.aweme.im.service.model.IMUser"

    /** Field-name fallbacks for [MY_ID_CANDIDATES] when a getter is absent. CONFIRMED field names. */
    val MY_ID_FIELDS: List<String> = listOf("uniqueId", "shortId", "uid")

    /**
     * ORDER MATTERS -- this is the "douyin ID" the user actually sees and types.
     *
     * MEASURED DISTINCTION, and getting it wrong shows the user a meaningless number:
     *   uid       = internal numeric id    -> NOT what the user calls their \u6296\u97f3\u53f7
     *   sec_uid   = "MS4wLjABAAAA..."      -> a URL-safe opaque id, not user-visible either
     *   uniqueId  = the handle the user SET in their profile  <-- THIS ONE
     *
     * ONLY the last entry is CONFIRMED as a real accessor on [IM_USER]. The first two are recorded
     * as the FIELDS they are, to be read reflectively -- do NOT call `method("getUniqueId")` on an
     * IMUser: see the caution on [IM_USER], that name belongs to a different model.
     *
     * FALLBACK ORDER: reflectively read the field `uniqueId`, then `shortId`, then take `getUid()`.
     * `getUid()` is the only one of the three with a verified accessor, so it is the natural last
     * resort even though it is the least human-readable.
     */
    val MY_ID_CANDIDATES: List<String> = listOf(
        "getUid",          // CONFIRMED accessor on IMUser (returns the `uid` field)
        "getUidS",         // CONFIRMED accessor on IMUser (returns the `uid` field, string form)
        "getSingleId",     // CONFIRMED accessor on IMUser; meaning NOT verified
    )

    /**
     * The accessors that are CONFIRMED to exist on an [IM_USER], for a runtime probe to prefer.
     *
     * Present and verified: getDisplayName, getNickname, getSecUid, getSingleId, getUid, getUidS.
     * Absent (do NOT look these up by name): getUniqueId, getShortId.
     */
    val IM_USER_ACCESSORS_CONFIRMED: List<String> = listOf(
        "getUid", "getUidS", "getSecUid", "getDisplayName", "getNickname", "getSingleId",
    )

    /** Accessor names that are ABSENT on [IM_USER] -- recorded so nobody re-derives the bug. */
    val IM_USER_ACCESSORS_ABSENT: List<String> = listOf("getUniqueId", "getShortId")

    // ==================================================================================
    // 7. DEGRADATION CONTRACT
    // ==================================================================================

    /**
     * What must DEGRADE GRACEFULLY (never throw) when a Douyin hook point is missing.
     *
     * Mirrors the WeChat side's rule in `WeChatHooks.safely`: a Douyin update that renames ONE class
     * must never take down the module, the balloon, or the user's ability to use WeChat. Every entry
     * below is a case where the correct behaviour is "log, disable that single feature, carry on".
     *
     * The list is documentation with teeth: each item names the feature that must keep working.
     *
     * WHY THIS IS A LIST AND NOT CODE: the enforcement lives at each call site (each hook step is
     * individually wrapped, and each accessor returns a neutral value -- "" / 0 / false -- rather than
     * throwing), the same shape as `DouyinMsgAccess`. Keeping the CONTRACT here means a reviewer can
     * check the call sites against one checklist instead of re-deriving it.
     */
    val DEGRADE: List<String> = listOf(
        // --- identity ---------------------------------------------------------------
        "message class not loadable -> analysis reports empty; the balloon, the settings screen " +
            "and WeChat must all still work",
        "getContent() missing -> treat the message as having no text (empty string), do not throw",

        // --- menu -------------------------------------------------------------------
        "long-press menu class or its show method not found -> the \"analyse\" entry is simply " +
            "absent; Douyin's own menu must be untouched (never return true from a long-click hook, " +
            "which would suppress Douyin's menu)",
        "PopupWindow accessor not found -> skip appending our row; do NOT create a second window",

        // --- input box --------------------------------------------------------------
        "input box class not found -> text polish falls back to the module's own dialog and the " +
            "clipboard, exactly as it already does on WeChat when the EditText cannot be located",

        // --- identity ---------------------------------------------------------------
        "account service unreachable -> OMIT the \"my <idLabel>\" line from the prompt entirely. " +
            "The model still understands the conversation from the me/them labels; a thrown " +
            "exception here would abort the whole analysis",
        "no uniqueId/shortId/uid readable -> same as above: omit the line, never substitute a " +
            "placeholder that the model could mistake for a real id",

        // --- conversation -----------------------------------------------------------
        "conversation lookup fails -> rely on Message.getConversationId()/getSender(), which the " +
            "long-press path already provides; do not retry in a loop",
    )

    /**
     * The user-facing label for this host's id -- "\u6296\u97f3\u53f7" on Douyin.
     *
     * NOTE: this deliberately delegates to HostApp rather than duplicating the literal, so there is
     * exactly ONE place that knows the wording (see HostApp.idLabel and core/Constants.kt's rule
     * that Chinese literals are escaped).
     */
    val idLabel: String get() = HostApp.DOUYIN.idLabel
}
