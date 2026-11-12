package com.kwai.imsdk.msg

/**
 * A TEST FIXTURE that occupies the exact namespace and name of Kuaishou's real message base class.
 *
 * ============================== WHY IT MUST BE NAMED EXACTLY THIS =============================
 *
 * The R1 guard needs to prove that a Kuaishou message is read by the KUAISHOU reader and NOT by the
 * WeChat reader. A first attempt at that test used an arbitrary fake class and proved NOTHING: the
 * Kuaishou reader rejected it as "not a Kuaishou message" and the WeChat reader could not find its
 * `field_content`, so BOTH returned `""` -- the test passed with the Kuaishou arm of `MsgAccess`
 * deleted. Mutation-testing the fix is what exposed that, and this fixture is the correction.
 *
 * The fixture works because of how `KuaishouMsgAccess.isMessage` degrades: it first tries
 * `Class.forName("com.kwai.imsdk.msg.KwaiMsg", false, hostLoader)`, and in a JVM unit test the host
 * loader is `EmptyLoader`, which throws. The fallback is an EXACT NAME MATCH -- so a class literally
 * named `com.kwai.imsdk.msg.KwaiMsg` is accepted, and the reader's REAL body-reading path runs.
 *
 * Having both field families on ONE object is what makes the test decisive:
 *
 *   * a Kuaishou field (`text`, `target`)   -- read by the Kuaishou reader
 *   * a WeChat field  (`field_content`, `field_talker`, `field_isSend`, ...) -- read by the WeChat reader
 *
 * So the two readers MUST return different values, and the assertions compare against the WeChat value.
 * If the `isKuaishou` arm is removed from any method of `MsgAccess`, that method returns the WeChat
 * value and the test fails. That is a real regression guard rather than a tautology.
 *
 * ============================== WHAT IT STILL CANNOT PROVE ====================================
 *
 * This is a SHAPE stand-in, not a copy of the real class: the real `KwaiMsg` carries ~20 fields
 * (including the `contentBytes`/`extra`/`localExtra` protobuf payloads) and a subclass hierarchy
 * (`TextMsg`, `ImageMsg`, ...) that the test JVM cannot load. It proves the DISPATCH and the reader's
 * classification rules; the real field READS remain covered by the on-device probe in
 * `KuaishouMsgAccess.probe`. Stated here so nobody mistakes this for device evidence.
 *
 * Deliberately provided:
 *   * NO `getId()` method   -> exercises the boxed-`id` field fallback in `msgId`
 *   * NO `getText()`/`getSummary()` methods -> exercises the field-first route
 *   * NO `getMsgType()`     -> `typeOf` must report -1 for the Kuaishou reader
 *   * NO `getSentTime()`/`getCreateTime()` -> `createTime` must report 0 (the honest neutral)
 *   * `getType()` = 1       -> the WeChat reader's `typeOf` (and therefore `isText`) would answer
 *                             1 / true, which is the value the Kuaishou route must NOT produce
 *
 * Comments ASCII-only (see core/Log.kt). The file lives in test sources only; it never ships.
 */
@Suppress("unused")
open class KwaiMsg {

    // ------------------------------------------------------------------ Kuaishou-shaped data

    /** The verified Kuaishou body field. Read FIRST by the Kuaishou reader. */
    @JvmField
    var text: String = "kuaishou-body"

    /** The verified Kuaishou conversation field. */
    @JvmField
    var target: String = "kuaishou-talker"

    @JvmField
    var targetType: Int = 1

    /** A BOXED Long, exactly like the real `KwaiMsg.id`, so the null-safe read is exercised. */
    @JvmField
    var id: Long? = 999L

    @JvmField
    var sender: String = "kwai-sender"

    @JvmField
    var seq: Long = 5L

    // ------------------------------------------------------------------ WeChat-shaped data
    //
    // These exist ONLY so the WeChat reader would answer with these values if it were wrongly used.
    // They are the trap the R1 test is fishing for; they are not part of Kuaishou's real model.

    @JvmField
    var field_content: String = "wechat-body"

    @JvmField
    var field_talker: String = "wechat-talker"

    @JvmField
    var field_isSend: Boolean = true

    @JvmField
    var field_msgId: Long = 111L

    @JvmField
    var field_msgSvrId: Long = 222L

    @JvmField
    var field_createTime: Long = 1_700_000_000_000L

    /**
     * The WeChat reader's type accessor. Returning 1 (= `TYPE_TEXT`) means the WeChat route would
     * report a text message, while the Kuaishou route reports -1 (no `getMsgType()` here). The two
     * disagree on purpose.
     */
    @JvmField
    var typeValue: Int = 1

    fun getType(): Int = typeValue
}
