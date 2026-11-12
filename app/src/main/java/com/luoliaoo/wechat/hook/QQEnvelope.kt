package com.luoliaoo.wechat.hook

/**
 * Pure, unit-testable rules for reading a QQ message body.
 *
 * WHY THIS FILE EXISTS SEPARATELY FROM [QQMsgAccess]
 *
 * The distinction between "this is a chat line" and "this is a non-text message" is the single
 * decision that produced the largest recorded bug in this project -- Douyin's envelope JSON, where one
 * expression sticker wrote 20 KB into the chat history (see 必看.md 「抖音聊天记录里存进大段 JSON」). QQ
 * makes that trap MORE likely than any other host, because its chat line legitimately arrives wrapped
 * in TWO layers:
 *
 *     MsgRecord.elements:ArrayList<MsgElement>
 *        -> MsgElement.textElement.textElement:TextElement      (elementType == 1)
 *             -> TextElement.content:String                     <-- the chat line
 *
 * and, for a market-face (商城表情) message, `TextElement.content` itself is a JSON blob such as
 * `{"m":1,"name":"...","emojiId":"..."}`. Storing that as the user's line is the exact Douyin bug.
 *
 * So the rule lives here as pure Kotlin -- no Android, no reflection -- and [QQMsgAccess] only reads
 * the object faithfully and hands the fields over. That way the "never store a non-text message" rule
 * is enforced by an assertion that runs in CI rather than by a comment a later edit can quietly break.
 *
 * ==================== EVERY FACT BELOW IS FROM THE DEX, NOT FROM MEMORY ======================
 * Verified against QQ 9.3.65 (41 dex, sha-free; index `_tools/qq/qq_index.pkl`, 428,076 classes).
 * Raw dumps: `_tools/qq/out/t14_*.txt`. Tools: `_tools/qq/qq_t14_probe*.py`, `qq_dis.py`.
 *
 *   com.tencent.qqnt.kernel.nativeinterface.MsgRecord        [classes.dex]  access=0x11
 *     66 fields / 69 methods. The ones this module reads:
 *       J  msgId           J  msgTime          J  msgSeq        J  timeStamp
 *       I  msgType         I  subMsgType       I  chatType      I  sendType  I  sendStatus
 *       I  nameType        J  senderUin        J  peerUin       J  fromUid
 *       Ljava/lang/String; senderUid  peerUid  peerName
 *                          sendMemberName  sendNickName  sendRemarkName
 *       Ljava/util/ArrayList; elements
 *     `elements` is the FIRST-class body carrier: `MsgRecord#elements : java.util.ArrayList`
 *     (dumped in `out/t14_elements.txt`), i.e. the chat line is NOT on the record itself.
 *
 *   com.tencent.qqnt.kernel.nativeinterface.MsgElement        [classes.dex]  access=0x11
 *     38 fields, one per element kind, plus:
 *       I  elementType   I  elementGroupId   J  elementId   [B extBufForUI
 *     77 methods. Verified getters used below:
 *       getTextElement()  getPicElement()  getPttElement()   getVideoElement()
 *       getFileElement()  getFaceElement() getMarketFaceElement()
 *       getGrayTipElement() getArkElement() getReplyElement() getMarkdownElement()
 *       getMultiForwardMsgElement() getStructLongMsgElement() ...
 *
 *   com.tencent.qqnt.kernel.nativeinterface.TextElement       [classes.dex]
 *     `getContent()Ljava/lang/String;`   <-- the chat line
 *     `getAtType()I` / `getAtNtUid()` / `getAtRoleName()` carry the @ mention metadata, which is WHY
 *     a group line can contain a literal "@" that is not user text.
 *
 * ============================== WHY THE TYPE NUMBERS ARE TRUSTWORTHY HERE ====================
 * Unlike Kuaishou -- where NO `MsgType` enum existed and 2/5/6/7/14 appeared in no constructor, so a
 * `when (msgType)` would have been pure guessing -- QQ ships the numbers as real constants:
 *
 *   com.tencent.qqnt.kernel.nativeinterface.MsgConstant     [classes11.dex]  532 static final int
 *
 * and they are COMPILE-TIME constants, so they live in the dex `static_values` array (NOT in a
 * `<clinit>`) -- which is why `qq_const_vals.py` printed 「无 <clinit>」 for this class and a
 * dedicated reader was needed (`_tools/qq/qq_t14_static_vals.py`). Decoded values
 * (`_tools/qq/out/t14_msgconst_values.txt`):
 *
 *   KELEMTYPETEXT = 1   KELEMTYPEPIC = 2      KELEMTYPEFILE = 3    KELEMTYPEPTT = 4
 *   KELEMTYPEVIDEO = 5  KELEMTYPEFACE = 6     KELEMTYPEREPLY = 7   KELEMTYPEGRAYTIP = 8
 *   KELEMTYPEWALLET = 9 KELEMTYPEARKSTRUCT = 10  KELEMTYPEMARKETFACE = 11
 *   KELEMTYPEMULTIFORWARD = 16   KELEMTYPEGIPHY = 15   KELEMTYPEMARKDOWN = 14
 *   KELEMTYPESTRUCTLONGMSG = 13  KELEMTYPESHARELOCATION = 28 ...
 *
 *   KMSGTYPEUNKNOWN = 0  KMSGTYPENULL = 1   KMSGTYPEMIX = 2   KMSGTYPEFILE = 3
 *   KMSGTYPESTRUCT = 4   KMSGTYPEGRAYTIPS = 5   KMSGTYPEPTT = 6   KMSGTYPEVIDEO = 7
 *   KMSGTYPEMULTIMSGFORWARD = 8   KMSGTYPEREPLY = 9   KMSGTYPEWALLET = 10
 *   KMSGTYPEARKSTRUCT = 11  KMSGTYPESTRUCTLONGMSG = 12  KMSGTYPEGIPHY = 13
 *   KMSGTYPEGIFT = 14  KMSGTYPETEXTGIFT = 15  KMSGTYPEFILTER = 32 ...
 *
 *   KCHATTYPEC2C = 1     KCHATTYPEGROUP = 2   KCHATTYPEDISC = 3   KCHATTYPETEMPC2CFROMGROUP = 100
 *
 *   KSENDTYPERECV = 0    KSENDTYPESELF = 1    KSENDTYPEOTHERDEVICE = 2
 *   KSENDTYPELOCAL = 3   KSENDTYPESELFFORWARD = 4   KSENDTYPESELFMULTIFORWARD = 5
 *   KSENDTYPEDROPPED = 6
 *
 * SELF-CHECK ON THAT DECODER (必看.md: every decoder needs a known-answer test, and a positive test
 * alone lets a plausible-looking wrong output through): `KCHATTYPEC2C` decoded to **1** and
 * `KCHATTYPEGROUP` to **2**, which is the QQNT public enumeration, and `KCHATTYPEDISC` to 3. A
 * mis-ordered `encoded_array` read would have produced garbage, so those three values are the proof
 * that the ordering is right. The decoder still REPORTS a count mismatch (532 static fields vs 531
 * encoded values -- QQ's own dex stores zero-initialised fields implicitly), and the values used here
 * are all before that point, which is stated rather than hidden.
 *
 * ============ THE NUMBERS ARE A HINT, THE ELEMENT OBJECT IS THE EVIDENCE ====================
 * Even with real constants, this file does NOT trust a bare `elementType` number to decide "is this
 * text": [decide] requires the text to have come from an actual `TextElement` whose `content` is
 * non-empty (or from a `MsgElement` whose `getTextElement()` returned a usable object). That is the
 * project's standing rule -- a structural anchor beats a magic number, because an anchor survives a
 * renumbering and a number does not. The numbers are used only for the PLACEHOLDER TABLE, where a
 * wrong guess costs a slightly wrong label rather than a corrupted history row.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
object QQEnvelope {

    /**
     * The NT message model every QQ chat message derives from.
     *
     * Matched by ASSIGNABILITY at runtime with the name test as a fallback, so a subclass or a wrapper
     * is still recognised. QQ's own UI item carries the record as `AIOMsgItem.getMsgRecord()` rather
     * than BEING the record, so [QQMsgAccess] also unwraps that -- but this is the object whose fields
     * are read once unwrapped.
     */
    const val MSG_CLASS = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord"

    /** One element of a message. The body lives in exactly one of its 38 typed fields. */
    const val ELEMENT_CLASS = "com.tencent.qqnt.kernel.nativeinterface.MsgElement"

    /**
     * The text element class -- the ONLY carrier of a chat line.
     *
     * Kept as a name (not a Class) so this object stays pure and unit-testable off-device.
     */
    const val TEXT_ELEMENT_CLASS = "com.tencent.qqnt.kernel.nativeinterface.TextElement"

    /**
     * The QQ UI item that WRAPS the record: `com.tencent.mobileqq.aio.msg.AIOMsgItem` [classes3.dex].
     *
     * VERIFIED by disassembly (`qq_dis.py`), which is the evidence that the UI path really is NT:
     *
     *   AIOMsgItem#getMsgRecord()   [0012] iget-object v48, AIOMsgItem->e : MsgRecord
     *   AIOMsgItem#o()              [0002] return-object v0        (a second accessor, same field)
     *   AIOMsgItem#getMsgId()       [0017] iget-object .. AIOMsgItem->e : MsgRecord
     *                               [0019] iget-wide   .. MsgRecord->msgId : J
     *   AIOMsgItem#isSelf()         [0026] iget-object .. AIOMsgItem->e : MsgRecord
     *                               [0028] invoke com/tencent/mobileqq/aio/utils/as->j(MsgRecord)Z
     *
     * The last one is a cross-check worth keeping: QQ itself does NOT trust a boolean on the item to
     * answer "did I send this" -- it delegates to `aio.utils.as.j(MsgRecord)`, reproduced in
     * [isSelfFromSendType].
     */
    const val AIO_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"

    // ------------------------------------------------------------------ elementType numbers
    //
    // All verified from MsgConstant's static_values (see the header). Only the entries used by
    // [placeholderForType] are listed; an unlisted number degrades to UNKNOWN_NON_TEXT rather than
    // being forced into a wrong label.

    const val ELEM_TEXT = 1
    const val ELEM_PIC = 2
    const val ELEM_FILE = 3
    const val ELEM_PTT = 4
    const val ELEM_VIDEO = 5
    const val ELEM_FACE = 6
    const val ELEM_REPLY = 7
    const val ELEM_GRAYTIP = 8
    const val ELEM_WALLET = 9
    const val ELEM_ARKSTRUCT = 10
    const val ELEM_MARKETFACE = 11
    const val ELEM_LIVEGIFT = 12
    const val ELEM_STRUCTLONGMSG = 13
    const val ELEM_MARKDOWN = 14
    const val ELEM_GIPHY = 15
    const val ELEM_MULTIFORWARD = 16
    const val ELEM_INLINEKEYBOARD = 17
    const val ELEM_INTEXTGIFT = 18
    const val ELEM_CALENDAR = 19
    const val ELEM_YOLOGAMERESULT = 20
    const val ELEM_AVRECORD = 21
    const val ELEM_FEED = 22
    const val ELEM_ONLINEFILE = 23
    const val ELEM_ACTIVITY = 25
    const val ELEM_TOFU = 26
    const val ELEM_FACEBUBBLE = 27
    const val ELEM_SHARELOCATION = 28
    const val ELEM_TASKTOPMSG = 29
    const val ELEM_FILTER = 49
    const val ELEM_PETADDITIONALINFO = 50
    const val ELEM_UNKNOWN = 0

    /**
     * `sendType` values that mean "this is mine".
     *
     * VERIFIED at the bytecode level, and this is the single most valuable find for this file. QQ does
     * not have an "isSelf" boolean on `MsgRecord`; its own code answers the question in
     * `com.tencent.mobileqq.aio.utils.as#j(MsgRecord)Z` [classes3.dex], disassembled to:
     *
     *     [0006] iget v48, MsgRecord->sendType : I
     *     [0009] branch 0x32 -> [0037]        (if-nez sendType -> return v1, where v1 = 1)
     *     [000b] const/4 v2, #2
     *     [000c] branch 0x32 -> [0037]        (sendType == 2 -> return 1, OTHERDEVICE)
     *     [000e] const/4 v2, #4
     *     [000f] branch 0x32 -> [0037]        (sendType == 4 -> return 1, SELFFORWARD)
     *     [0013..] fall through: compare senderUid with AppRuntime.getCurrentUin(),
     *              then with AppRuntime.getCurrentUid(), via TextUtils.equals; equal -> return 0
     *     [0036] const/4 v1, #0               (nothing matched -> 0)
     *     [0037] return v1
     *
     * i.e. QQ's OWN rule is: `sendType` in {0 (RECV), 1 (SELF), 2 (OTHERDEVICE), 4 (SELFFORWARD)}
     * short-circuits, and everything else falls back to a uid comparison. Reading 0/1/2/4 as
     * "short-circuit" and treating 3 (LOCAL) / 5 (SELFMULTIFORWARD) / 6 (DROPPED) as "cannot decide
     * from the number" is therefore not a guess: it is the host's own control flow.
     *
     * Note the asymmetry that matters here and is easy to get backwards: QQ returns TRUE for
     * OTHERDEVICE and SELFFORWARD -- a message I sent from my PC, or forwarded by me, is still MINE,
     * and this module's prompt splits the conversation by who said it.
     */
    val SELF_SEND_TYPES = setOf(1, 2, 4)

    /** `sendType` values meaning "definitely NOT mine" without needing my own uid. */
    val OTHER_SEND_TYPES = setOf(0)

    /**
     * `chatType` values for a two-person chat, verified from MsgConstant.
     *
     * 1 = C2C, 3 = DISC (讨论组). A 讨论组 is listed as a group-shaped conversation here because a
     * discussion has more than two participants, which is what [isGroupChatType] is actually asked.
     * 100 = TEMPC2CFROMGROUP (群临时会话), also multi-party.
     */
    val GROUP_CHAT_TYPES = setOf(2, 3, 100)

    const val CHAT_C2C = 1
    const val CHAT_GROUP = 2

    // ------------------------------------------------------------------ reasons

    /**
     * Which branch [decide] took. The caller logs this, so an exit is never silent (必看.md: an exit
     * with no log is indistinguishable from code that never ran).
     */
    object Reason {
        const val OK = "ok"
        const val NOT_A_MESSAGE = "not-a-message"
        const val NO_BODY = "no-body"
        const val NON_TEXT = "non-text"
        const val RECALLED = "recalled"
        const val ENVELOPE = "envelope-without-text"
    }

    /**
     * The placeholder stored for a known non-text message, or EMPTY.
     *
     * ================== WHY THIS TABLE IS BY ELEMENT NUMBER, AND WHY THAT IS SAFE =================
     * Every other host in this project classifies a non-text message by CLASS (Kuaishou's `KImageMsg`,
     * Douyin's envelope keys) because no type enum was verifiable. QQ is the opposite: the numbers are
     * real compile-time constants shipped in the dex, so the number IS the structural anchor here.
     *
     * The asymmetry that makes this safe even if a number is read wrongly: a placeholder is DATA, not
     * a chat line. [decide] never lets a placeholder be returned as `OK`, so a wrong label can only
     * make the stored row read "[语音]" instead of "[视频]" -- it can never make a non-text message
     * look like user text, and it can never let a payload through.
     *
     * ================== WHY NON-TEXT GETS A PLACEHOLDER AT ALL ===================================
     * Kuaishou's and Soul's envelopes return EMPTY for a non-text message, with the reasoning that "a
     * placeholder still occupies a line and still reaches the AI prompt as if the other side had said
     * something". That reasoning is honoured here too -- through the TYPE, not through the text:
     * [QQMsgAccess.isText] reports FALSE for any row whose reason is PLACEHOLDER, and the QQ history
     * feed is required to filter on `isText` before storing. So the placeholder exists for the UI
     * (「对方发来一张图片」 is what a reader wants to see) and is explicitly NOT a chat line.
     *
     * This is stated because it is a deliberate divergence from the two sibling envelopes; a future
     * reader comparing the three files must not conclude that one of them is simply wrong.
     */
    fun placeholderForType(elementType: Int): String? = when (elementType) {
        ELEM_PIC -> "[\u56fe\u7247]"                       // [图片]
        ELEM_PTT -> "[\u8bed\u97f3]"                       // [语音]
        ELEM_VIDEO -> "[\u89c6\u9891]"                     // [视频]
        ELEM_FILE -> "[\u6587\u4ef6]"                      // [文件]
        ELEM_ONLINEFILE -> "[\u5728\u7ebf\u6587\u4ef6]"    // [在线文件]
        ELEM_FACE -> "[\u8868\u60c5]"                      // [表情]
        ELEM_MARKETFACE -> "[\u5546\u57ce\u8868\u60c5]"    // [商城表情]
        ELEM_FACEBUBBLE -> "[\u6c14\u6ce1\u8868\u60c5]"    // [气泡表情]
        ELEM_GIPHY -> "[GIF]"                              // [GIF]
        ELEM_ARKSTRUCT -> "[\u5361\u7247\u6d88\u606f]"     // [卡片消息]
        ELEM_MARKDOWN -> "[Markdown]"                      // [Markdown]
        ELEM_MULTIFORWARD -> "[\u5408\u5e76\u8f6c\u53d1]"  // [合并转发]
        ELEM_STRUCTLONGMSG -> "[\u957f\u6d88\u606f]"       // [长消息]
        ELEM_REPLY -> "[\u5f15\u7528\u56de\u590d]"         // [引用回复]
        ELEM_WALLET -> "[\u7ea2\u5305/\u94b1\u5305]"        // [红包/钱包]
        ELEM_LIVEGIFT -> "[\u793c\u7269]"                  // [礼物]
        ELEM_INTEXTGIFT -> "[\u793c\u7269]"                // [礼物]
        ELEM_GRAYTIP -> "[\u7cfb\u7edf\u63d0\u793a]"        // [系统提示]
        ELEM_AVRECORD -> "[\u8bed\u97f3\u901a\u8bdd]"      // [语音通话]
        ELEM_CALENDAR -> "[\u65e5\u7a0b]"                  // [日程]
        ELEM_SHARELOCATION -> "[\u4f4d\u7f6e]"             // [位置]
        ELEM_FEED -> "[\u52a8\u6001]"                      // [动态]
        ELEM_ACTIVITY -> "[\u6d3b\u52a8]"                  // [活动]
        ELEM_YOLOGAMERESULT -> "[\u6e38\u620f\u7ed3\u679c]" // [游戏结果]
        ELEM_TOFU -> "[\u6d88\u606f]"                      // [消息] (tofu = unsupported glyph)
        ELEM_FILTER -> "[\u6d88\u606f]"                    // [消息]
        ELEM_TASKTOPMSG -> "[\u7f6e\u9876\u6d88\u606f]"    // [置顶消息]
        ELEM_INLINEKEYBOARD -> "[\u6d88\u606f]"            // [消息]
        ELEM_PETADDITIONALINFO -> "[\u6d88\u606f]"         // [消息]
        else -> null
    }

    /** The one placeholder used when the element kind could not be identified at all. */
    const val UNKNOWN_NON_TEXT = "[\u975e\u6587\u672c\u6d88\u606f]" // [非文本消息]

    // ------------------------------------------------------------------ the decision

    /**
     * The verdict for one message.
     *
     * @param store  true when the value is a genuine chat line and may be recorded
     * @param text   the chat line when [store] is true, otherwise a placeholder or ""
     * @param reason one of [Reason], so the caller's log names the branch
     */
    data class Decision(val store: Boolean, val text: String, val reason: String)

    /**
     * The whole classification, as a pure function of what the reader managed to read.
     *
     * INPUTS ARE ALL OPTIONAL ON PURPOSE: a `MsgRecord` whose `elements` list is empty is normal for a
     * recall notice or a still-loading message, and this must return EMPTY rather than throw.
     *
     * @param text       `TextElement.content` of the first usable text element, or null
     * @param elementType `MsgElement.elementType` of the element the text came from (or of the first
     *                    element when there is no text), or -1 when unknown
     * @param recalled   true when a recall element was seen
     * @param isMessage  false when the object is not a QQ message at all
     * @param envelopeLike true when [text] looks like the host's own JSON envelope rather than a line
     */
    fun decide(
        text: String?,
        elementType: Int,
        recalled: Boolean,
        isMessage: Boolean = true,
        envelopeLike: Boolean = false,
    ): Decision {
        if (!isMessage) return Decision(false, "", Reason.NOT_A_MESSAGE)

        // A recalled message is NEVER stored as text even when a stale content element is still
        // attached to the record. QQ keeps the original `elements` alongside the revoke element for
        // the "undo" affordance, so reading the text first would make a withdrawn line reappear in the
        // history as if the other side had said it.
        if (recalled) {
            return Decision(false, RECALLED_PLACEHOLDER, Reason.RECALLED)
        }

        val body = text?.trim().orEmpty()
        if (body.isEmpty()) {
            // No text element at all. That is the NORMAL shape of a picture / voice / file / card
            // message, so a placeholder is the right answer -- and `store` is FALSE, which is what the
            // caller checks, so nothing here can be mistaken for a chat line.
            val ph = if (elementType == ELEM_UNKNOWN) UNKNOWN_NON_TEXT
            else placeholderForType(elementType) ?: UNKNOWN_NON_TEXT
            return Decision(false, ph, Reason.NON_TEXT)
        }

        // ========================= WHY AN ENVELOPE IS REJECTED OUTRIGHT ==========================
        // A market-face (`elementType == 11`) carries its own JSON in `TextElement.content`, and a QQ
        // "multi-message" card carries a JSON array. Storing either is the Douyin 20 KB bug verbatim.
        //
        // The check below is deliberately CONSERVATIVE in both directions:
        //   * it requires a brace/bracket SHAPE and a QQ-owned key, so a user who literally types
        //     `{"a":1}` in a chat still gets their text stored ([looksLikeHostEnvelope] says why);
        //   * when it fires, the result is a placeholder rather than the payload, so the failure mode
        //     is a short wrong label, never a large wrong body.
        if (envelopeLike || looksLikeHostEnvelope(body)) {
            val ph = placeholderForType(elementType) ?: UNKNOWN_NON_TEXT
            return Decision(false, ph, Reason.ENVELOPE)
        }

        return Decision(true, body, Reason.OK)
    }

    /**
     * The placeholder stored for a recalled message.
     *
     * Kept as a value on purpose: a recall is a real event a reader wants to see in the history, but it
     * is not something either side "said", so it must go through the same placeholder gate as a picture.
     */
    const val RECALLED_PLACEHOLDER = "[\u5df2\u64a4\u56de]" // [已撤回]

    /**
     * Keys that mark a string as QQ's OWN element envelope rather than a chat line.
     *
     * ================== WHY A KEY LIST, NOT "STARTS WITH {" ==================
     * Douyin's envelope test was tightened after the fact for exactly this reason: people do send JSON
     * to each other. A bare `startsWith("{")` would rewrite such a message into a placeholder and the
     * user would lose their text -- a silent data loss that looks like a working feature.
     *
     * These keys are QQ-owned names taken from the element model, NOT invented:
     *   `emojiId` / `marketFaceName` / `m` / `name` belong to `MarketFaceElement` (商城表情),
     *   `app` / `view` / `prompt` belong to `ArkElement` (卡片),
     *   `busiId` / `xml` / `json` belong to `XmlElement` / `JsonGrayElement`.
     *
     * A braced string carrying NONE of them is treated as user text, which is the safe direction: the
     * worst case is that a user who typed a QQ-internal JSON blob verbatim sees it stored as typed.
     */
    val ENVELOPE_KEYS = listOf(
        "emojiId", "marketFaceName", "faceId",
        "app", "view", "prompt", "config",
        "busiId", "templateID", "mqqapi",
    )

    /**
     * True when [s] looks like QQ's own element envelope rather than something a person typed.
     *
     * Requires BOTH a container shape AND at least one [ENVELOPE_KEYS] entry that is present as a real
     * JSON KEY (`"key"` followed by a colon), not merely mentioned inside a value.
     *
     * ================== WHY THIS IS HAND-ROLLED AND NOT org.json ==================
     * Same reason `DouyinMsgAccess.looksLikeEnvelope` is: this runs inside the QQ process on every
     * message, and a hand-rolled scan CANNOT FAIL. An `org.json` parse would either be handed a
     * truncated payload (QQ caps `TextElement.content`) and throw, or would need a try/catch whose
     * else-branch decides the user's text is safe -- i.e. the security-relevant decision would live in
     * an exception path. Here it is an explicit, testable line.
     *
     * The key test is deliberately STRUCTURAL: `"emojiId"` must be followed by a colon (after optional
     * whitespace), so a chat message that merely CONTAINS the word `emojiId` is not treated as an
     * envelope. Requiring `${...}` to be the outer shape is not enough on its own either -- a user can
     * paste JSON -- which is why both conditions are required together.
     */
    fun looksLikeHostEnvelope(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return false
        val first = t[0]
        if (first != '{' && first != '[') return false
        return hasEnvelopeKey(t)
    }

    private fun hasEnvelopeKey(t: String): Boolean {
        for (k in ENVELOPE_KEYS) {
            val needle = "\"" + k + "\""
            var i = t.indexOf(needle)
            while (i >= 0) {
                var j = i + needle.length
                // Optional whitespace, then a colon. That is what makes it a KEY rather than the word
                // appearing inside a value.
                while (j < t.length && (t[j] == ' ' || t[j] == '\t' || t[j] == '\n' ||
                        t[j] == '\r')
                ) {
                    j++
                }
                if (j < t.length && t[j] == ':') return true
                i = t.indexOf(needle, i + 1)
            }
        }
        return false
    }

    // ------------------------------------------------------------------ identity

    /**
     * True when [chatType] is a multi-party conversation.
     *
     * The number is QQ's own (`MsgConstant.KCHATTYPEGROUP = 2`, `KCHATTYPEDISC = 3`), decoded from the
     * dex rather than assumed. When `chatType` cannot be read at all the caller passes -1 and this
     * returns false -- the NEUTRAL answer, not a guess: mislabelling a private chat as a group puts a
     * nickname in front of every line and changes what the model is told about the conversation.
     */
    fun isGroupChatType(chatType: Int): Boolean = chatType in GROUP_CHAT_TYPES

    /**
     * True when `sendType` says the message is mine, using QQ's own short-circuit rule.
     *
     * See [SELF_SEND_TYPES] for the disassembly this reproduces. Returns null when the number is one
     * the host does not short-circuit on (3 LOCAL, 5 SELFMULTIFORWARD, 6 DROPPED, anything unknown),
     * which tells the caller "you must decide by uid comparison or report the degraded answer".
     */
    fun isSelfFromSendType(sendType: Int): Boolean? = when {
        sendType in SELF_SEND_TYPES -> true
        sendType in OTHER_SEND_TYPES -> false
        else -> null
    }

    /**
     * Which unit a raw `msgTime` was in: "ms", "s->ms", "too-small" or "non-positive".
     *
     * Separate from [toMillis] so a caller can LOG the decision without the conversion having to
     * remember it, and copied in behaviour (not reinvented) from the Soul and Kuaishou envelopes.
     * `MsgRecord.msgTime` is a `J` with no documented unit; QQNT's own protocol carries the server
     * timestamp in SECONDS (`timeStamp`), so a seconds value reaching a millisecond-expecting caller
     * is a live possibility and would be rejected by the shared `< 1e12` epoch gate in
     * `AnalysisController.onLiveMessage` -- a silent total drop.
     */
    fun timeBranch(raw: Long): String = when {
        raw <= 0L -> "non-positive"
        // Already a plausible epoch in ms (2001-09-09 .. year 33658).
        raw >= 1_000_000_000_000L -> "ms"
        // A plausible epoch in SECONDS (1973 .. 33658). Scale it up.
        raw >= 1_000_000_000L -> "s->ms"
        else -> "too-small"
    }

    /** Normalises [raw] to milliseconds using the branch [timeBranch] reports. */
    fun toMillis(raw: Long): Long = when (timeBranch(raw)) {
        "s->ms" -> raw * 1000L
        else -> raw
    }

    /**
     * The timestamp to use, from the candidates the record offers, plus the branch actually taken.
     *
     * `msgTime` first (`MsgRecord.getMsgTime()J`), `timeStamp` second. Both are verified fields; the
     * order is chosen because `msgTime` is the one the AIO item's own id path reads next to
     * `msgId`, while `timeStamp` is the server-scale value.
     */
    fun pickTime(msgTime: Long, timeStamp: Long): Pair<Long, String> {
        val raw = if (msgTime > 0L) msgTime else timeStamp
        if (raw <= 0L) return 0L to "none"
        return toMillis(raw) to timeBranch(raw)
    }

    /**
     * A deterministic synthetic id, used when the host exposes no usable id (`msgId` == 0).
     *
     * Copied in behaviour from `KuaishouEnvelope.syntheticId`: `HistoryStore.record` dedupes by msgId,
     * so this must be STABLE for the same message and DIFFERENT for different ones. A hash of the
     * identity tuple gives both. Masked positive so it can never collide with a real id's sign.
     *
     * INCLUDE `seq` WHEN AVAILABLE: two identical lines sent in the same second by the same person are
     * a real pattern ("哈哈" twice), and `msgSeq` is the host's own ordering key, so it is part of the
     * identity here.
     */
    fun syntheticId(chatId: String, time: Long, senderUin: Long, seq: Long, text: String): Long {
        val h = (chatId + "|" + time + "|" + senderUin + "|" + seq + "|" + text).hashCode().toLong()
        return h and 0x7FFFFFFFL
    }

    /**
     * The last path segment of a class name, for log lines.
     *
     * QQ's aio classes are nested (`...holder.a`, `...msg.AIOMsgItem$b`), so the full name is noise in a
     * logcat line and the simple name is what a reader greps for.
     */
    fun simpleName(name: String?): String = when {
        name == null -> "?"
        name.contains('$') -> name.substringAfterLast('$')
        name.contains('.') -> name.substringAfterLast('.')
        else -> name
    }

    /**
     * The sender label to store, in QQ's own order of preference.
     *
     * ================== WHY THIS ORDER, AND HOW IT WAS ESTABLISHED ==================
     * NOT guessed from the field names. QQ's own chat-history renderer was disassembled
     * (`qq_dis.py com.tencent.qqnt.chathistory.util.MsgRecordExKt a`, [classes29.dex]) and its
     * nickname assignment for a group message is:
     *
     *     [0086] iget-object  MsgRecord->peerUid          (group uin)
     *     ...
     *     [0144] iget-object  MsgRecord->sendNickName
     *     [014c] invoke       MsgRecordExKt->c(TextView, CharSequence, int)
     *
     * i.e. the value the host actually renders for a group line is `sendNickName`. The other two
     * candidates are real fields on the same record:
     *     `sendMemberName` -- the troop card (群名片), which the host prefers when it EXISTS (the
     *                        member-info branch above reads a `MemberInfo` first and only falls back
     *                        to `sendNickName`),
     *     `sendRemarkName` -- the remark I set for that person, which matters in a one-to-one chat.
     *
     * So the order implemented is: member card, then nickname, then remark, then the raw uin. Every
     * step is a real field on `MsgRecord`; a blank one falls through rather than being stored blank.
     *
     * ================== THE SECOND-QUERY LIMITATION, STATED PLAINLY ==================
     * In a GROUP, `sendMemberName` is populated only for messages the server sent with the member card
     * attached. When it is blank, QQ issues a SECOND QUERY (`MemberInfo` / troop member lookup) which
     * runs through the host's coroutine scope and cannot be driven from this module without a live
     * scope. Rather than pretend, this function degrades to `sendNickName` and finally to the uin, and
     * the caller logs which step was taken. That degradation is the honest answer: a uin is still a
     * stable, correct identifier for "who said this", while an invented name is not.
     */
    fun pickSenderName(
        memberName: String?,
        nickName: String?,
        remarkName: String?,
        senderUin: Long,
    ): Pair<String, String> {
        val m = memberName?.trim().orEmpty()
        if (m.isNotEmpty()) return m to "sendMemberName"
        val n = nickName?.trim().orEmpty()
        if (n.isNotEmpty()) return n to "sendNickName"
        val r = remarkName?.trim().orEmpty()
        if (r.isNotEmpty()) return r to "sendRemarkName"
        if (senderUin != 0L) return senderUin.toString() to "senderUin"
        return "" to "none"
    }

    /**
     * The conversation key this message belongs to.
     *
     * `peerUid` is the NT conversation identifier and is what QQ's own `Contact.getPeerUid()` returns
     * (`com.tencent.qqnt.kernelpublic.nativeinterface.Contact` has exactly `chatType:I`,
     * `peerUid:String`, `guildId:String` -- dumped in `out/t14_msgsupport.txt`). `peerUin` is the
     * numeric twin on the record.
     *
     * ================== WHY A MISSING KEY IS "" AND NOT A FALLBACK ==================
     * A conversation key that is SOMETIMES a uid and sometimes a uin, or sometimes message-scoped,
     * splits one chat across several history files and the store can never repair that afterwards.
     * So this returns "" and the caller refuses the message LOUDLY. `peerName` is deliberately NOT a
     * candidate: it is a DISPLAY name, i.e. it changes when the other side renames themselves, which
     * would silently split the history at the moment of the rename.
     */
    fun pickChatId(peerUid: String?, peerUin: Long): Pair<String, String> {
        val u = peerUid?.trim().orEmpty()
        if (u.isNotEmpty()) return u to "peerUid"
        if (peerUin != 0L) return peerUin.toString() to "peerUin"
        return "" to "none"
    }
}
