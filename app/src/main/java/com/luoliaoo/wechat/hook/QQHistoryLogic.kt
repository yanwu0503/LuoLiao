package com.luoliaoo.wechat.hook

/**
 * The pure decisions behind [QQHistoryHooks], kept out of the hook so they fail on the JVM instead of
 * on a phone.
 *
 * ============================== WHY THIS FILE EXISTS ==============================
 *
 * [QQHistoryHooks] runs inside QQ's own bind call and can only be exercised on a device. Everything in
 * it that can be WRONG in a way a device run would not clearly reveal is therefore expressed here as a
 * pure function and pinned by `QQHistoryLogicTest`:
 *
 *   * the SHAPE test that picks the bind method -- a wrong predicate silently hooks nothing;
 *   * the dedup-key composition -- a wrong key files one conversation under two names, or worse,
 *     hides a new message as a duplicate;
 *   * the group/private classification -- QQ's numbers are its own, and guessing them is how a group
 *     message ends up looking like a private one.
 *
 * This mirrors `QQMenuIdentity` / `QQMenuSelection`, which split the same way for the same reason.
 *
 * ============================== THE DEDUP KEY AND WHY IT IS *NOT* A LOCAL SET ===================
 *
 * The dedup key is **`(talker, msgId)`**, and it is enforced by the EXISTING pipeline, not here:
 * `HistoryStore.record` keys its map by `msgId` per talker file and skips ids it already holds
 * (`HistoryStore.kt` `load`/`record`, `data class Line(msgId, ...)`).
 *
 * A private `Set<Long>` in the hook would be WRONG, and the reason is worth stating because a local
 * cache looks like the obvious implementation:
 *   * it is not persisted, so every QQ restart would re-admit messages already on disk;
 *   * it is not shared with the analysis path, so the same message could be written twice by two
 *     feeders that each think they are first;
 *   * it would grow without bound, because nothing but the store knows the 20 000-line cap
 *     (`HistoryStore.MAX_PER_TALKER`).
 *
 * So [dedupKey] exists to make the identity EXPLICIT and testable (and to order the two feeders'
 * logs), while the authoritative dedup stays in the store.
 *
 * Which is also why `"o"` must NOT be used as a record source: `AnalysisController` derives the id
 * from the record, and an empty placeholder record yields id 0 for every row, which the key above
 * would collapse into ONE stored message. See `QQHistoryHooks`'s header and
 * `_tools/qq/T26_QQ_HISTORY_IMPL.md`.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
object QQHistoryLogic {

    /** `AbsMsgListVB#y(holder, int, msgBase, List)V` -- four parameters. */
    const val BIND_PARAM_COUNT = 4

    /** ...and the message is the third one (index 2). Verified from the dex descriptor. */
    const val BIND_MSG_PARAM_INDEX = 2

    /**
     * The VM fallback's parameter count is NOT fixed -- see [msgRecordParamIndex]. It is 2 as
     * emitted today (`o0(AIOMsgListVM, MsgRecord)V`, a `public|static` helper) but the matcher is
     * deliberately count-agnostic, because pinning the count is exactly what broke the fallback.
     */

    /** Suffix of the NT record class, matched by NAME so a load failure cannot hide the method. */
    const val MSG_RECORD_SUFFIX = "MsgRecord"

    /**
     * True when a method's shape is the message-list bind body.
     *
     * The three conditions are all load-bearing:
     *   * `paramCount == 4` -- `y` takes (holder, position, message, payloads);
     *   * `returnsVoid` -- a render method returns nothing, so a value-returning 4-arg method is a
     *     different method that merely looks similar;
     *   * `msgBaseAssignable` -- the 3rd parameter must accept the message base type. Assignability
     *     rather than equality, because the concrete `AIOMsgItem` subclasses are what really arrive.
     */
    fun matchesBindShape(
        paramCount: Int,
        returnsVoid: Boolean,
        msgBaseAssignable: Boolean,
    ): Boolean = paramCount == BIND_PARAM_COUNT && returnsVoid && msgBaseAssignable

    /**
     * The parameter index of the NT record on a candidate feeder method, or -1 when there is none.
     *
     * ============================== CORRECTED 2026-09-26 (device evidence) =====================
     *
     * T19 recorded the VM entry as `o0(MsgRecord)V` -- ONE parameter. The dex says TWO:
     *
     *     o0(Lcom/tencent/mobileqq/aio/msglist/AIOMsgListVM;Lcom/.../MsgRecord;)V   acc=0x9
     *
     * `0x9` is `public|static`, and the declaring class is repeated as the FIRST parameter -- the
     * shape Kotlin emits for a member body extracted to a static helper. The device log proved the
     * consequence: the old single-parameter matcher found nothing and the fallback never installed
     * ("declares no void method taking a single MsgRecord parameter").
     *
     * That is why this returns an INDEX rather than a boolean. The old code called
     * `chain.getArg(0)`, which on this descriptor is the VM, not the message -- so a matcher that
     * merely accepted two parameters would have fed a non-message into the pipeline.
     *
     * Scanning BACKWARDS and taking the LAST record parameter keeps this correct for a suspend form
     * too, where a `Continuation` follows the arguments.
     */
    fun msgRecordParamIndex(
        paramCount: Int,
        returnsVoid: Boolean,
        paramTypeNames: List<String?>,
    ): Int {
        if (!returnsVoid || paramCount < 1) return -1
        for (i in paramCount - 1 downTo 0) {
            if (isMsgRecordName(paramTypeNames.getOrNull(i))) return i
        }
        return -1
    }

    /**
     * True when [typeName] names the NT record.
     *
     * `endsWith` rather than a full descriptor match: the record is
     * `com.tencent.qqnt.kernel.nativeinterface.MsgRecord`, and matching the tail keeps the check
     * working if the package moves -- while still refusing an unrelated class that merely contains
     * the word in the middle.
     */
    fun isMsgRecordName(typeName: String?): Boolean =
        typeName != null && typeName.endsWith(MSG_RECORD_SUFFIX)

    /**
     * The stored identity of a message: `(talker, msgId)`.
     *
     * `msgId` first (QQ's own stable local id, the same value `HistoryStore` keys by), `msgSeq` as the
     * documented fallback (`QQMsgAccess.msgSeq`). A zero from BOTH means the message cannot be keyed;
     * `0` is returned to make that visible rather than inventing an id, because a fabricated key would
     * store a duplicate instead of being rejected.
     *
     * A blank talker also yields `0`: filing a message under a guessed conversation splits one chat
     * across files, which is the bug `QQMsgAccess.talker`'s own comment records.
     */
    fun dedupKey(talker: String?, msgId: Long, msgSeq: Long): String {
        val t = talker?.trim().orEmpty()
        if (t.isEmpty()) return "0"
        val id = if (msgId != 0L) msgId else msgSeq
        if (id == 0L) return "0"
        return t + "#" + id
    }

    /** True when [dedupKey] produced a usable identity (i.e. the message may be stored). */
    fun isKeyable(key: String): Boolean = key != "0" && key.contains('#')

    /**
     * The conversation bucket a message belongs to: `"group"` or `"private"`, using QQ's OWN numbers
     * (`MsgConstant.KCHATTYPEGROUP = 2`, `KCHATTYPEDISC = 3`, `KCHATTYPETEMPC2CFROMGROUP = 100` --
     * the set `QQEnvelope.GROUP_CHAT_TYPES` already holds).
     *
     * This delegates to [QQEnvelope.isGroupChatType] rather than re-listing the numbers: a second copy
     * of QQ's enumeration is exactly how the two drift apart.
     *
     * `-1` (unreadable) is reported as `"unknown"` rather than silently as `"private"`: a group message
     * misfiled as private is worse than an admitted gap, because it looks like a working feature.
     *
     * NOTE: this is a DIAGNOSTIC label only. The store separates conversations by the talker value
     * itself (a group id and a user uin are different strings, hence different files), and
     * `AnalysisController` keys cards with the raw `access.talker(msg)`. Prefixing the talker with
     * this label would split QQ's history from what the floating panel reads, so it is deliberately
     * NOT used as a key component.
     */
    fun conversationKind(chatType: Int): String = when {
        chatType < 0 -> "unknown"
        QQEnvelope.isGroupChatType(chatType) -> "group"
        else -> "private"
    }

    /** One log-safe line describing a message, for the diagnostics the task requires. */
    fun describe(talker: String?, chatType: Int, msgId: Long): String =
        "kind=" + conversationKind(chatType) +
            " talker=" + (talker?.trim().orEmpty().ifEmpty { "(blank)" }) +
            " msgId=" + msgId
}
