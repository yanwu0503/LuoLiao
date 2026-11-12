package com.luoliaoo.wechat.hook

/**
 * Pure, unit-testable rules for reading a KUAISHOU message body.
 *
 * WHY THIS FILE EXISTS SEPARATELY FROM [KuaishouMsgAccess]:
 * The distinction between "this is a chat line" and "this is a non-text message" is the single
 * decision that produced the largest recorded bug in this project -- Douyin's envelope JSON, where one
 * expression sticker wrote 20 KB into the chat history (必看.md). Getting it wrong is not a cosmetic
 * defect; it corrupts the store. So the rule lives here as pure Kotlin with no Android and no
 * reflection, and [KuaishouMsgAccess] only reads the object faithfully and hands it over.
 *
 * ======================== ALL FACTS BELOW ARE VERIFIED FROM THE DEX ==========================
 * Source: `_tools/ks/KS_MSG_RECON.md` (t1) plus my own re-verification via `_tools/ks/ksdex.py`
 * against `快手_14.8.30.50423.apk` (31 dex, 343,739 classes).
 *
 *   com.kwai.imsdk.msg.KwaiMsg                       [classes4.dex, NOT obfuscated]
 *     FIELD text            Ljava/lang/String;   getText()Ljava/lang/String;
 *     FIELD msgType         I                    getMsgType()I
 *     FIELD contentBytes    [B   <-- protobuf-nano BINARY
 *     FIELD extra           [B   <-- protobuf-nano BINARY
 *     FIELD localExtra      [B   <-- protobuf-nano BINARY
 *     FIELD unknownTips     Ljava/lang/String;
 *     METHOD getSummary()Ljava/lang/String;   <-- the host's own "what to show in the list" entry
 *                                                 ⚠️ NOT the body: for non-text classes it returns a
 *                                                 LOCALIZED string (KImageMsg -> getString(0x7f111182)),
 *                                                 so use getText() as the body. See SUMMARY_GETTER.
 *
 *   Subclasses, each with a stable `getName()` literal (verified by disassembly):
 *     com.kwai.imsdk.msg.TextMsg        getSummary() -> getText()
 *     com.kwai.imsdk.msg.RecalledMsg    getName() -> "imsdk_recalled_msg"
 *     com.kwai.imsdk.msg.ImageMsg       getName() -> "imsdk_image_msg"
 *     com.kwai.imsdk.msg.VideoMsg       getName() -> "imsdk_video_msg"
 *     com.kwai.imsdk.msg.AudioMsg       getName() -> "imsdk_audio_msg"
 *     com.kwai.imsdk.msg.ForwardMsg     getName() -> "imsdk_forward_msg"
 *     com.kwai.imsdk.msg.ReferenceMsg / UnsupportedMsg / com.kwai.imsdk.msg.NoticeMsg (extends TextMsg)
 *
 * WHY THE PLACEHOLDER TABLE IS BY CLASS, NOT BY NUMBER:
 * The dex has NO `MsgType` enum class (verified absent) and no `MSG_TYPE_*` constants on KwaiMsg.
 * Type semantics live only in a protocol registry (`pqj.c`, 94 `pqj.b` subclasses), and type numbers
 * 2, 5, 6, 7 and 14 do not appear in any protocol constructor. So a `when (msgType) { 0..15 }` would
 * be guessing. `instanceof` against the real subclass is evidence; a number would not be.
 *
 * WHY NEVER FALL BACK TO contentBytes / extra:
 * They are protobuf-nano payloads, not text. There is no "raw string" to fall back to, and any
 * toString() of a byte array produces garbage that would be stored as the user's chat line. For a
 * non-text message the only honest answer is a short placeholder or empty -- never the payload.
 *
 * ASCII-only in code; Chinese via \uXXXX (see core/Log.kt).
 */
object KuaishouEnvelope {

    /** The message base class, un-obfuscated in this APK. */
    const val MSG_CLASS = "com.kwai.imsdk.msg.KwaiMsg"

    /**
     * Text-message subclass. `getSummary()` on it forwards to `getText()`, verified by disassembly.
     * Kept as a name (not a Class) so this object stays pure and unit-testable off-device.
     */
    const val TEXT_MSG_CLASS = "com.kwai.imsdk.msg.TextMsg"

    /** Notice messages derive from TextMsg, so they carry a real chat line too. */
    const val NOTICE_MSG_CLASS = "com.kwai.imsdk.msg.NoticeMsg"

    /** The field carrying a real text body. */
    const val TEXT_FIELD = "text"

    /** Getter for the text body. */
    const val TEXT_GETTER = "getText"

    /**
     * The host's own "what the user should see" entry point.
     *
     * ⚠️ NOT the body, and NOT a superset of `getText()`. An earlier version of this file claimed
     * exactly that ("getSummary() is the SUPERSET of getText() and is the right primary read") and the
     * claim was WRONG; it is left documented here because the wrong version is the tempting one.
     *
     * What the disassembly actually shows:
     *   KwaiMsg.getText() / KwaiMsg.getSummary()  -> both `iget-object KwaiMsg->text` (identical)
     *   TextMsg.getSummary()                      -> `invoke-virtual TextMsg->getText()` (trampoline)
     *   KImageMsg.getSummary()                    -> `Application->getString(0x7f111182)`   <<< !!
     *   KImageMsg.getText()                       -> NOT overridden
     *
     * The "summary is a superset" generalisation holds ONLY for the text classes that trampoline to
     * getText(). For every non-text class it returns a LOCALIZED UI PLACEHOLDER, so reading it as the
     * body would store "[图片]" / "[Photo]" as if the user had typed it -- varying with the device
     * language, and indistinguishable from literal user text. Our own locale-independent placeholder
     * therefore takes precedence; see `decide()` and `KuaishouMsgAccess.TEXT_GETTERS`.
     */
    const val SUMMARY_GETTER = "getSummary"

    /** Where an unsupported message keeps its own explanation. */
    const val UNKNOWN_TIPS_GETTER = "getUnknownTips"

    /**
     * Subclass simple-name -> short placeholder.
     *
     * NOTE: these are deliberately SHORT and human. They are not decoration -- a placeholder still
     * occupies a line in the history, still appears in the scope preview and is still fed to the
     * model. DouyinMsgAccess documents the same reasoning for why a placeholder is preferable to
     * dropping the row silently: a visible `[图片]` tells the user the capture is working and the
     * message was simply not text; a missing row looks like a broken hook.
     */
    val PLACEHOLDERS: Map<String, String> = linkedMapOf(
        // 已撤回
        "RecalledMsg" to "[\u5df2\u64a4\u56de]",
        // 图片
        "ImageMsg" to "[\u56fe\u7247]",
        "QPhotoMsg" to "[\u56fe\u7247]",
        // 视频
        "VideoMsg" to "[\u89c6\u9891]",
        // 语音
        "AudioMsg" to "[\u8bed\u97f3]",
        // 表情
        "KEmotionMsg" to "[\u8868\u60c5]",
        "EmotionMsg" to "[\u8868\u60c5]",
        // 引用
        "ReferenceMsg" to "[\u5f15\u7528]",
        "KReferenceMsg" to "[\u5f15\u7528]",
        // 转发
        "ForwardMsg" to "[\u8f6c\u53d1]",
        // 通话
        "KCallMsg" to "[\u901a\u8bdd]",
        "CallMsg" to "[\u901a\u8bdd]",
        // 链接 / 卡片
        "KLinkMsg" to "[\u94fe\u63a5]",
        "KHtmlTextMsg" to "[\u94fe\u63a5]",
        "KDCardMsg" to "[\u5361\u7247]",
        "KCommodityMsg" to "[\u5546\u54c1]",
        "KPreCommodityMsg" to "[\u5546\u54c1]",
        // 不支持
        "UnsupportedMsg" to "[\u4e0d\u652f\u6301\u7684\u6d88\u606f]",
        "KUnsupportedMsg" to "[\u4e0d\u652f\u6301\u7684\u6d88\u606f]",
    )

    /**
     * Sentinel for "the numeric type could not be read". `KuaishouMsgAccess.callInt` returns -1 when
     * the getter is missing or throws, and no type in the 87-row evidence table is -1, so it can never
     * collide with a real type.
     */
    const val MSG_TYPE_UNAVAILABLE = -1

    /**
     * Numeric `KwaiMsg.msgType` -> a KEY of [PLACEHOLDERS].
     *
     * ============================== WHY A SECOND, NUMERIC PATH EXISTS =============================
     * The class-name path above only works when the runtime object IS the concrete subclass. If the
     * bound object is instead a wrapper or a future subclass we do not know, the name matches nothing
     * and the message silently degrades to "" -- invisible to the user, and indistinguishable from a
     * hook that never ran. `msgType` does not have that weakness: it is read straight off the base
     * class, so it is the SAME number whichever subclass or wrapper the object happens to be.
     *
     * The two paths are additive, never exclusive: the name is tried FIRST because it is the most
     * specific evidence, and only when it misses does the number get a turn. That ordering means a
     * class we already handle produces byte-identical output to before -- this cannot make any
     * currently-correct behaviour worse.
     *
     * EVIDENCE (not guessed). Every pair below comes from `_tools/ks/out/T22_MSGTYPE_CLASS.tsv`
     * (task t22): the authoritative `pqj.c.b(I)` dispatch chain cross-checked against each protocol
     * factory's constructor argument -- two independent derivations, 87 rows, ZERO conflicts. Only the
     * 15 types that carry an explicit label are listed; the other 72 rows have no label and are
     * deliberately absent rather than invented, so they still fall through to the summary/blank path.
     *
     * The values are KEYS into [PLACEHOLDERS] rather than literal strings, so the two paths can never
     * drift apart and a locale-independent token stays defined in exactly one place.
     * `KuaishouMsgTypeTableTest` pins that every key here resolves, and that the type path and the
     * class path agree wherever both can answer.
     *
     * NOT CONFIRMED: the real device bound object's class. This table is therefore DEFENSIVE -- it
     * covers the case where the name misses; it is not a proven fix for an observed device failure.
     */
    val MSG_TYPE_KEYS: Map<Int, String> = linkedMapOf(
        1 to "ImageMsg",           // KImageMsg
        3 to "AudioMsg",           // KAudioMsg
        4 to "VideoMsg",           // KVideoMsg
        11 to "RecalledMsg",       // KRecalledMsg
        12 to "KReferenceMsg",     // KReferenceMsg
        13 to "ForwardMsg",        // ForwardMsg
        15 to "KCallMsg",          // KCallMsg
        1004 to "QPhotoMsg",       // KQPhotoMsg
        1006 to "KHtmlTextMsg",    // KHtmlTextMsg
        1008 to "KEmotionMsg",     // KEmotionMsg
        1009 to "KLinkMsg",        // KLinkMsg
        1014 to "KEmotionMsg",     // KEmotionMsg
        1017 to "KHtmlTextMsg",    // KHtmlTextMsg
        2000 to "KCommodityMsg",   // KCommodityMsg
        2001 to "KPreCommodityMsg", // KPreCommodityMsg
    )

    /**
     * Subclass simple-names that mean "this is a recalled message".
     *
     * VERIFIED: `RecalledMsg.getName()` returns the literal `imsdk_recalled_msg`, and
     * `KRecalledMsg` is the application-layer name for msgType 11.
     */
    val RECALLED_NAMES: Set<String> = setOf("RecalledMsg", "KRecalledMsg")

    /** Why a body was or was not accepted. Strings, so a log line can carry them verbatim. */
    object Reason {
        /** A real chat line. */
        const val OK = "text"

        /** A usable body found on the summary getter. */
        const val OK_SUMMARY = "text-summary"

        /** A non-text message, mapped to a short placeholder. */
        const val PLACEHOLDER = "non-text-placeholder"

        /** Recalled message. */
        const val RECALLED = "recalled"

        /** The object is not a Kuaishou message at all. */
        const val NOT_A_MESSAGE = "not-a-kuaishou-message"

        /** Nothing readable: no text, no summary, no placeholder match. */
        const val NO_BODY = "blank-body"

        /** The payload is the binary protobuf field -- refused on purpose. */
        const val BINARY_PAYLOAD = "binary-payload-refused"
    }

    /** The classification outcome. `store` distinguishes "write this" from "write nothing". */
    data class Decision(val text: String, val reason: String) {
        val store: Boolean get() = text.isNotEmpty()
    }

    /**
     * THE decision: what (if anything) to store for a Kuaishou message.
     *
     * ============================== ORDER, AND WHY `getSummary()` IS NOT THE BODY ================
     * An EARLIER version of this function trusted `getSummary()` before the placeholder table, on the
     * reasoning that "the summary is the host's own display entry, so it is a superset of the text".
     * Disassembly shows that reasoning is WRONG, and the wrongness is user-visible. Measured:
     *
     *   KwaiMsg.getText() / KwaiMsg.getSummary()   -> BOTH `iget-object KwaiMsg->text` (identical)
     *   TextMsg.getSummary()                       -> `invoke-virtual TextMsg->getText()` (trampoline)
     *   KImageMsg.getSummary()                     -> `Application->getString(0x7f111182)`   <<< !!
     *   KImageMsg.getText()                        -> NOT OVERRIDDEN (inherits the base field read)
     *
     * So for a NON-text message the summary is a LOCALIZED UI PLACEHOLDER, not content. Storing it as
     * the user's words has two consequences that cannot be undone later:
     *
     *   1. the stored row varies with the device LANGUAGE ("[图片]" vs "[Photo]") for one unchanged APK;
     *   2. it becomes indistinguishable from a user who literally typed "[图片]".
     *
     * The order below therefore puts OUR OWN stable placeholder for known non-text classes BEFORE any
     * use of the summary. Our placeholder is a fixed ASCII-ish token that does not change with locale,
     * so the two failure modes above disappear. `getSummary()` is kept only as a LAST resort for an
     * UNRECOGNISED class that produced display text, where it is the only signal available at all.
     *
     * ORDER:
     *   1. a non-empty `getText()` wins outright -- the only case the requirement cares about, and the
     *      field that carries the real body (also non-null-safe in the host: it returns '' not null);
     *   2. a recalled message is named as such, never stored as its (often emptied) text;
     *   3. a KNOWN non-text subclass gets OUR placeholder -- locale-independent and explicit;
     *   4. an unrecognised class with a non-empty summary stores the summary (last resort, logged);
     *   5. otherwise store NOTHING -- the honest answer, rather than a byte-array dump.
     *
     * @param className the RUNTIME class name (may be a subclass or a wrapper).
     * @param text      `getText()` result, or null.
     * @param summary   `getSummary()` result, or null.
     * @param recalled  whether a recall was detected by class or flag.
     */
    fun decide(
        className: String?,
        text: String?,
        summary: String?,
        recalled: Boolean,
        msgType: Int = MSG_TYPE_UNAVAILABLE,
    ): Decision {
        val simple = simpleName(className)
        val body = text?.trim().orEmpty()

        // 1. A real text body is the answer, whatever else the object claims to be.
        if (body.isNotEmpty()) {
            return Decision(body, Reason.OK)
        }

        // 2. Recall beats placeholder: "已撤回" is more informative than "[图片]".
        //    The numeric path is consulted too, so a recalled message whose class name we do not know
        //    is still named as recalled -- type 11 is `KRecalledMsg` in the evidence table.
        if (recalled || simple in RECALLED_NAMES ||
            MSG_TYPE_KEYS[msgType] in RECALLED_NAMES
        ) {
            return Decision(PLACEHOLDERS.getValue("RecalledMsg"), Reason.RECALLED)
        }

        // 3. A KNOWN non-text subclass -> OUR placeholder. This comes BEFORE the summary on purpose:
        //    the host's summary for these classes is a localized UI string (proof above), so using it
        //    would store a translation that changes with the device locale.
        //
        //    TWO PATHS, NAME FIRST. `placeholderFor` is the name path and is tried first because it is
        //    the most specific evidence. `byType` is the numeric path, reached ONLY when the name
        //    matched nothing -- so for every class already in the table the outcome is unchanged, and
        //    the number can only ever turn a former "" into a placeholder. See `MSG_TYPE_KEYS`.
        val ph = placeholderFor(simple) ?: byType(msgType)
        if (ph != null) {
            return Decision(ph, Reason.PLACEHOLDER)
        }

        // 4. Unknown class (not a text type we recognise, not a non-text type we know) that still
        //    produced display text: the summary is the only signal there is. Kept as a last resort so a
        //    future Kuaishou subclass shows SOMETHING rather than vanishing -- and reachable only when
        //    the class is absent from the placeholder table, so it can never override a stable token.
        val s = summary?.trim().orEmpty()
        if (s.isNotEmpty()) {
            return Decision(s, Reason.OK_SUMMARY)
        }

        // 5. Unknown and empty -> store nothing. Never the binary payload.
        return Decision("", Reason.NO_BODY)
    }

    /**
     * The placeholder for a runtime class name, or null when the class is not a known non-text type.
     *
     * Walks the name by trailing segments so both the IM SDK names (`ImageMsg`) and the application
     * names (`KImageMsg`) are covered, and a wrapper's inner name still matches.
     */
    fun placeholderFor(className: String?): String? {
        val simple = simpleName(className)
        if (simple.isEmpty()) return null
        PLACEHOLDERS[simple]?.let { return it }
        // Tolerate the K-prefixed application-layer spelling of the same concept.
        if (simple.startsWith("K")) {
            PLACEHOLDERS[simple.substring(1)]?.let { return it }
        }
        return null
    }

    /**
     * The placeholder for a numeric `KwaiMsg.msgType`, or null when the number is unknown.
     *
     * The second path of the two-path lookup (see [MSG_TYPE_KEYS]). Deliberately returns null for
     * anything not in the table -- including [MSG_TYPE_UNAVAILABLE] -- so an unreadable or unlisted
     * type falls through to the summary/blank branches instead of being given an invented label.
     */
    fun byType(msgType: Int): String? = MSG_TYPE_KEYS[msgType]?.let { PLACEHOLDERS[it] }

    /** The last dot-segment of a class name, or "" when there is nothing usable. */
    fun simpleName(className: String?): String {
        val n = className?.trim().orEmpty()
        if (n.isEmpty()) return ""
        val dot = n.lastIndexOf('.')
        val dollar = n.lastIndexOf('$')
        val cut = maxOf(dot, dollar)
        return if (cut >= 0 && cut + 1 < n.length) n.substring(cut + 1) else n
    }

    /**
     * True when the name looks like one of the binary payload fields.
     *
     * Used only to REFUSE: if a caller ever hands this function a body that came from `contentBytes`,
     * `extra` or `localExtra`, the answer must be "do not store" rather than "store the dump".
     */
    fun isBinaryFieldName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n == "contentBytes" || n == "extra" || n == "localExtra"
    }

    /**
     * Normalises a Kuaishou timestamp to milliseconds.
     *
     * THE UNIT IS NOT CONFIRMED STATICALLY (t1 states this explicitly: no `div-long` evidence was
     * found to prove milliseconds). Naming convention is not evidence, so this normalises by
     * MAGNITUDE and reports which branch it took, exactly like `SoulEnvelope.toMillis`.
     *
     * The downstream gate in `AnalysisController.onLiveMessage` rejects any time below 1e12, so a
     * seconds value that reached it unnormalised would drop EVERY message -- loudly in the log
     * (`bad-time:`), but totally. Hence normalisation here.
     */
    fun toMillis(raw: Long): Long = when (timeBranch(raw)) {
        "zero" -> 0L
        "seconds" -> raw * 1000L
        "micros" -> raw / 1000L
        "millis" -> raw
        // Implausibly large: leave it alone rather than invent a scaling.
        else -> raw
    }

    /** The magnitude bucket of a raw timestamp, for the diagnostic log. */
    fun timeBranch(raw: Long): String = when {
        raw <= 0L -> "zero"
        raw < 100_000_000_000L -> "seconds"       // < 1e11 -> seconds (1973..5138)
        raw < 100_000_000_000_000L -> "millis"    // 1e11..1e14 -> milliseconds
        raw < 100_000_000_000_000_000L -> "micros" // 1e14..1e17 -> microseconds
        else -> "unknown"
    }

    /**
     * A deterministic synthetic id, used when the host exposes no usable id.
     *
     * Copied in behaviour from `SoulEnvelope.syntheticId`: `HistoryStore.record` dedupes by msgId, so
     * this must be STABLE for the same message and DIFFERENT for different ones. A hash of the
     * identity tuple gives both. Masked positive so it can never collide with a real id's sign.
     */
    fun syntheticId(talker: String, time: Long, isSend: Boolean, text: String): Long {
        val h = (talker + "|" + time + "|" + isSend + "|" + text).hashCode().toLong()
        return h and 0x7FFFFFFFL
    }
}
