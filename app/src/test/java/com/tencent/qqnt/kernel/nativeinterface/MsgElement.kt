package com.tencent.qqnt.kernel.nativeinterface

/**
 * A TEST FIXTURE that occupies the exact namespace and name of QQ's real `MsgElement`.
 *
 * WHY IT IS NAMED EXACTLY THIS: same reason as `MsgRecord` in this package -- [com.luoliaoo.wechat.hook
 * .QQMsgAccess] binds `getElementType()` and `getTextElement()` by name on whatever class the real
 * object turns out to be, and a fixture in another namespace would never be reached by the production
 * reflection path, so the test would prove nothing about the code that ships.
 *
 * ============================== THE SHAPE, AND WHY EACH FIELD IS HERE =========================
 *
 * The real `MsgElement` (verified: `_tools/qq/out/t14_MsgElement.txt`, 38 fields / 77 methods) carries
 * ONE typed sub-object per element kind:
 *
 *     elementType : int          elementId : long        elementGroupId : int
 *     textElement : TextElement  picElement : PicElement  pttElement : PttElement
 *     ... and 26 more
 *
 * Only THREE of those matter to the reader, and they are the three that make the tests below decisive:
 *
 *   * `elementType`  -- the number the placeholder table is keyed by.
 *   * `textElement`  -- the structural anchor. The reader refuses to treat a message as text unless a
 *                       REAL TextElement object with non-empty `content` was obtained, because a number
 *                       can be renumbered by a QQ update and an object cannot.
 *   * `outerText`    -- DELIBERATELY NOT A REAL QQ FIELD. It exists to make a specific wrong
 *                       implementation FAIL: a reader that fell back to "some string hanging off the
 *                       element" would return this value, and every text assertion in the test would
 *                       change. It is the trap, and the trap fixture is documented here so nobody later
 *                       mistakes it for a discovered QQ field.
 *
 * `grayTipElement` is present with a nested `revokeElement` because that pair is the STRUCTURAL anchor
 * [com.luoliaoo.wechat.hook.QQMsgAccess.isRecalled] uses -- a recall is recognised by the presence of a
 * `getRevokeElement()` on the gray-tip object, never by a number alone.
 *
 * Comments ASCII-only (see core/Log.kt). Test sources only; it never ships.
 */
@Suppress("unused")
open class MsgElement {

    /** `elementType : I` -- 1 = TEXT, 2 = PIC, 4 = PTT, 8 = GRAYTIP (verified from MsgConstant). */
    @JvmField
    var elementType: Int = 0

    @JvmField
    var elementId: Long = 0L

    @JvmField
    var elementGroupId: Int = 0

    /** The text sub-object. Present only when `elementType == 1`. */
    @JvmField
    var textElement: TextElement? = null

    /** The gray-tip sub-object. Present only when `elementType == 8`. */
    @JvmField
    var grayTipElement: GrayTipElement? = null

    /**
     * THE TRAP -- not a QQ field.
     *
     * A string hanging directly off the element, deliberately populated in the fixtures. A reader that
     * "found a String on the element and used it" would return this instead of the TextElement's content,
     * so the content assertions below would fail. It exists to make that wrong implementation impossible
     * to ship silently.
     */
    @JvmField
    var outerText: String = "TRAP-not-a-qq-field"

    fun getElementType(): Int = elementType
    fun getElementId(): Long = elementId
    fun getElementGroupId(): Int = elementGroupId
    fun getTextElement(): TextElement? = textElement
    fun getGrayTipElement(): GrayTipElement? = grayTipElement
}

/**
 * A TEST FIXTURE for QQ's real `com.tencent.qqnt.kernel.nativeinterface.TextElement`.
 *
 * Verified members (`_tools/qq/out_TextElement.txt`, 12 fields / 27 methods):
 *   `content : String`   -- the chat line. THIS is the only field the reader uses.
 *   `atType : I`, `atNtUid : String`, `atRoleName : String` -- @-mention metadata.
 *
 * The @ fields are present because they are the reason a group line can contain a literal "@" that is
 * not user text; keeping them here documents that the reader must NOT treat them as a body.
 */
@Suppress("unused")
open class TextElement {

    /** The chat line. */
    @JvmField
    var content: String = ""

    @JvmField
    var atType: Int = 0

    @JvmField
    var atNtUid: String = ""

    @JvmField
    var atRoleName: String = ""

    @JvmField
    var atUid: Long = 0L

    fun getContent(): String = content
    fun getAtType(): Int = atType
    fun getAtNtUid(): String = atNtUid
    fun getAtRoleName(): String = atRoleName
    fun getAtUid(): Long = atUid
}

/**
 * A TEST FIXTURE for QQ's real gray-tip element.
 *
 * Verified (`_tools/qq/out/t14_revoke_gray.txt`): `com.tencent.qqnt.kernelpublic.nativeinterface
 * .GrayTipElement` has 16 fields including `revokeElement : RevokeElement` and
 * `subElementType : I`. Only the revoke member is modelled here, because it is the one the reader
 * anchors on.
 *
 * NOTE: in the real APK this class lives under `kernelpublic`, NOT `kernel`. It is placed in the
 * `kernel` package here ON PURPOSE, and the reason is a real robustness property of the reader: it never
 * names this class, it only calls `getGrayTipElement()` on the element and then `getRevokeElement()` on
 * whatever came back. Nothing in the production code depends on this position, so the fixture's own
 * package cannot make a wrong implementation pass.
 */
@Suppress("unused")
open class GrayTipElement {

    @JvmField
    var subElementType: Int = 0

    @JvmField
    var revokeElement: RevokeElement? = null

    fun getSubElementType(): Int = subElementType
    fun getRevokeElement(): RevokeElement? = revokeElement
}

/**
 * A TEST FIXTURE for `RevokeElement`, whose mere presence marks a message as recalled.
 *
 * Verified fields (`_tools/qq/out/t14_revoke_gray.txt`): `wording`, `operatorNick`, `isSelfOperate`,
 * `origMsgSenderUid`, ... The reader only tests for non-null, so only the class needs to exist; the
 * fields are kept to show the shape a future "who recalled it" feature would read.
 */
@Suppress("unused")
open class RevokeElement {

    @JvmField
    var wording: String = ""

    @JvmField
    var operatorNick: String = ""

    @JvmField
    var isSelfOperate: Boolean = false

    fun getWording(): String = wording
    fun getOperatorNick(): String = operatorNick
    fun getIsSelfOperate(): Boolean = isSelfOperate
}
