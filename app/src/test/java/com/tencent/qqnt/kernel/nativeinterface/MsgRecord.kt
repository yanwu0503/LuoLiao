package com.tencent.qqnt.kernel.nativeinterface

/**
 * A TEST FIXTURE that occupies the exact namespace and name of QQ's real NT message record.
 *
 * ============================== WHY IT MUST BE NAMED EXACTLY THIS =============================
 *
 * `QQMsgAccess` resolves its body route by `Class.forName("com.tencent.qqnt.kernel.nativeinterface
 * .MsgRecord", false, hostLoader)` and, in the degraded branch, falls back to an EXACT NAME MATCH. A
 * fixture named anything else would be rejected as "not a QQ message" and every assertion below would
 * pass or fail for the wrong reason -- which is exactly the bluff the Kuaishou test records
 * (`KuaishouMsgAccessRoutingTest`'s first version carried neither field family, so both readers
 * honestly returned "" and the test passed with the dispatch arm DELETED).
 *
 * In the unit-test JVM there is NO host loader (`Resolver.classLoader()` is `ModuleBridge
 * .hostClassLoader ?: loader`, and `hostClassLoader` is null because nothing bootstrapped the host), so
 * the ONLY way `Class.forName` can succeed is if the class is on the TEST classpath -- which is what
 * this file arranges. The reader's reflection therefore runs for real: `getElements()` is looked up by
 * name, `getTextElement()` by name, `getContent()` by name, and the whole chain from
 * `content()` down to the returned String is the production code.
 *
 * ============================== WHAT THIS FIXTURE DELIBERATELY REPRODUCES ======================
 *
 * The shape below is the VERIFIED QQ shape (`_tools/qq/out/t14_nt_model.txt`), including the parts that
 * are easy to get wrong:
 *
 *   * `elements : java.util.ArrayList` -- the body is NOT on the record.
 *   * `msgTime : long` AND `timeStamp : long` -- TWO time fields, and the reader must pick and normalise.
 *   * `sendType : int` -- the direction, with QQ's own short-circuit values (0/1/2/4).
 *   * `chatType : int` -- 1 = C2C, 2 = GROUP.
 *   * `senderUid` is a UID STRING while `senderUin` is a LONG -- the two are not interchangeable, and
 *     the reader's `isSend` uid comparison depends on that distinction.
 *   * the three sender-name fields with DIFFERENT values, so the preference ORDER is observable.
 *
 * ============================== WHAT IT CANNOT PROVE =========================================
 *
 * It is a SHAPE stand-in, not the real class: the real `MsgRecord` has 66 fields and 69 methods, is
 * produced by the NT kernel from JNI, and its getters are QFIX-redirected. So this proves the READER's
 * classification, field selection and degradation rules -- it does NOT prove that QQ 9.3.65 really
 * populates these fields on a live message. That is what the on-device probe
 * (`QQMsgAccess.probe`) is for, and the QQ_MSG_ACCESS_NOTES.md says so explicitly.
 *
 * Comments ASCII-only (see core/Log.kt). The file lives in test sources only; it never ships.
 */
@Suppress("unused")
open class MsgRecord {

    // ------------------------------------------------------------------ identity
    @JvmField
    var msgId: Long = 0L

    @JvmField
    var msgSeq: Long = 0L

    /** `msgTime`: the field the reader prefers. */
    @JvmField
    var msgTime: Long = 0L

    /** `timeStamp`: the server-scale twin, used only when msgTime is unreadable. */
    @JvmField
    var timeStamp: Long = 0L

    // ------------------------------------------------------------------ kind
    @JvmField
    var msgType: Int = 0

    @JvmField
    var subMsgType: Int = 0

    @JvmField
    var chatType: Int = 1

    @JvmField
    var sendType: Int = 0

    @JvmField
    var sendStatus: Int = 0

    @JvmField
    var nameType: Int = 0

    // ------------------------------------------------------------------ participants
    @JvmField
    var senderUin: Long = 0L

    @JvmField
    var senderUid: String = ""

    @JvmField
    var peerUin: Long = 0L

    @JvmField
    var peerUid: String = ""

    @JvmField
    var peerName: String = ""

    /**
     * The group member card. QQ fills this from the server for many group messages; when it is absent
     * the real client issues a `MemberInfo` second query, which this fixture cannot reproduce -- so a
     * blank value here is the case the reader must degrade from.
     */
    @JvmField
    var sendMemberName: String = ""

    @JvmField
    var sendNickName: String = ""

    @JvmField
    var sendRemarkName: String = ""

    // ------------------------------------------------------------------ the body carrier
    /**
     * `elements : java.util.ArrayList` -- the ONLY place a chat line lives.
     *
     * Typed as `ArrayList` rather than `List` because that is the verified declared type, which is what
     * makes this fixture exercise the same assignability path the real object takes.
     */
    @JvmField
    var elements: ArrayList<MsgElement> = ArrayList()

    // ------------------------------------------------------------------ getters
    //
    // The reader binds these BY NAME through the host loader, so the names must be exactly QQ's. The
    // fields above are public and would be found by a FIELD scan, but the reader prefers the getter, so
    // both routes exist here and a missing getter would silently exercise the field fallback instead.

    fun getMsgId(): Long = msgId
    fun getMsgSeq(): Long = msgSeq
    fun getMsgTime(): Long = msgTime
    fun getTimeStamp(): Long = timeStamp
    fun getMsgType(): Int = msgType
    fun getSubMsgType(): Int = subMsgType
    fun getChatType(): Int = chatType
    fun getSendType(): Int = sendType
    fun getSendStatus(): Int = sendStatus
    fun getNameType(): Int = nameType
    fun getSenderUin(): Long = senderUin
    fun getSenderUid(): String = senderUid
    fun getPeerUin(): Long = peerUin
    fun getPeerUid(): String = peerUid
    fun getPeerName(): String = peerName
    fun getSendMemberName(): String = sendMemberName
    fun getSendNickName(): String = sendNickName
    fun getSendRemarkName(): String = sendRemarkName
    fun getElements(): ArrayList<MsgElement> = elements
}
