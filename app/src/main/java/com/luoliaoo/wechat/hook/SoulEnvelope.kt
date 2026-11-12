package com.luoliaoo.wechat.hook

/**
 * SOUL content codec -- the PURE half of the Soul history feed.
 *
 * WHY THIS FILE IS SEPARATE FROM [SoulMsgAccess]
 * Everything here is a plain function over Strings and class NAMES: no reflection, no Android, no
 * `Log`. That is deliberate, because this is exactly the logic that once wrote 20 KB of base64 into
 * the Douyin history file (see 必看.md, 「抖音聊天记录里存进大段 JSON」). Pure code can be unit
 * tested on the JVM, so the "never store a non-text message" rule is enforced by an assertion that
 * runs in CI rather than by a comment that a later edit can quietly break.
 *
 * THE CONTRACT, COPIED FROM DouyinMsgAccess.content() AND NOT REINVENTED
 *   1. A non-text message returns EMPTY. Never a placeholder ("[image]"), never the raw envelope.
 *      A placeholder still occupies a line and still reaches the AI prompt as if the other side had
 *      said something, so it is just as wrong as raw JSON -- only smaller.
 *   2. Raw JSON is only ever PARSED, never stored. A JSON-looking payload with no text field
 *      yields EMPTY, not the payload.
 *   3. A payload that is not JSON at all is returned verbatim: people do send JSON to each other,
 *      so the envelope test must require BOTH a brace shape AND a Soul-owned key.
 *
 * EVERYTHING BELOW IS VERIFIED FROM Soul 6.37.0's OWN DEX (17 dex files), not guessed:
 *
 *   cn.soulapp.imlib.msg.ImMessage                 the message object (classes17.dex)
 *     getMsgId()Ljava/lang/String;                 msgId is a STRING, not a long
 *     getFrom() / getTo() / getOtherId()           participants
 *     getMsgType()I / getServerTime()J / getLocalTime()J
 *     getChatMessage()Lcn/soulapp/imlib/msg/chat/ChatMessage;
 *
 *   cn.soulapp.imlib.msg.chat.ChatMessage          the content carrier (classes17.dex)
 *     getMsgContent()Ljava/io/Serializable;        the actual content OBJECT
 *     getMsgType()I / getSessionId() / getExtString() / getNotice()
 *
 *   cn.soulapp.imlib.msg.chat.TopChatMsg           base of every content object
 *     toJson()Ljava/lang/String;                   WHY THIS FILE MUST EXIST
 *     and 16 verified subclasses -- the text ones carry `text`, the rest do not.
 *
 * Comments are ASCII-only; Chinese text uses \uXXXX escapes (see core/Log.kt).
 */
object SoulEnvelope {

    /**
     * The message class whose instances this module is willing to record.
     *
     * Matched by ASSIGNABILITY at runtime, with the name test as a fallback for the case where the
     * class is not loaded yet, so a Tinker-generated subclass is still recognised (every Soul class
     * carries `changeQuickRedirect`, i.e. Soul hot-patches at runtime).
     */
    const val MESSAGE_CLASS = "cn.soulapp.imlib.msg.ImMessage"

    /** Prefix of the content family, used to recognise a content object without loading it. */
    const val CONTENT_PREFIX = "cn.soulapp.imlib.msg.chat."

    /**
     * Content classes that carry a chat line, VERIFIED by dumping their fields.
     *
     *   TextMsg    -> java.lang.String text
     *   StringMsg  -> java.lang.String content
     *   PromptMsg  -> java.lang.String text
     *
     * This is a POSITIVE allow-list on purpose. The family has 16 other subclasses (ImgMsg, AudioMsg,
     * VideoMsg, ExpressionMsg, UserCardMsg, MusicMsg, PositionMsg, RePostMsg, CallMsg, ...) and none
     * of them is a chat line. Classifying by "is it text?" means a NEW subclass added by a Soul
     * update is non-text by default -- the safe direction. The opposite rule ("anything not on a
     * deny-list is text") would eventually store a card's payload as if the user had typed it.
     *
     * ============================ WHY `ExtChatMsg` IS NOT HERE ==================================
     *
     * Two teammates (the captain and soul-recon) both advised that `ExtChatMsg` "also carries text"
     * and that omitting it would "silently drop real messages". That advice is REFUTED by evidence,
     * and it matters, so the reasoning is recorded rather than the conclusion:
     *
     *   * `ExtChatMsg` has fields `content:String` + `type:int`, and its only constructor is
     *     `(String,int)`. The `type` discriminator is the tell: a plain chat line has nothing to
     *     discriminate.
     *   * It is CONSTRUCTED with app-generated protocol strings, read off the const-strings at each
     *     construction site: `VoiceRtcEngine.d0` builds it with "\u7533\u8bf7\u4e0e\u4f60\u8bed\u97f3
     *     \u901a\u8bdd..." ("requests a voice call with you"), `chat.utils.y4.O0` with "\u540c\u610f"
     *     ("agree"), and `imlib.handler.h.d` builds it from `com.soul.im.protos.ExtChatMessage`
     *     inside `CommandMessage` handling, keyed by the ext string "bubble".
     *   * It flows in the COMMAND path, not the render path: `ConversationFragment.onCmdMsgReceive`,
     *     `Notifier.O0`/`getLaunchIntent`, and the two senders `y4.R1`/`y4.S1`/`y4.O0`, which wrap it
     *     with `ImMessage.createChatSendMsg` and `ChatManager.p0`.
     *   * Decisive: `chat.utils.y4.R1` SENDS it, with a fully app-generated \u4e2d\u6587 body.
     *
     * So an `ExtChatMsg` is Soul mechanically generating a line that the USER NEVER TYPED. Storing it
     * would fabricate a speaker, and the numbers back that up: the whole dex contains 13 `ExtChatMsg`
     * instruction references against 138 for `TextMsg`/`StringMsg`, i.e. it is a narrow command
     * channel, not a text carrier.
     *
     * (The blanket claim was probably an artefact of asking "which classes HAVE a String field" --
     * a field's existence says nothing about its meaning. `ExtChatMsg` and `TextMsg` both have one;
     * only a READER or a CONSTRUCTION SITE distinguishes them.)
     *
     * Bumping this allow-list to "" be safe "" would be the Douyin 20 KB bug in reverse: instead of
     * storing a card's payload, the module would store Soul's own UI prose as if the other person
     * had said it.
     */
    val TEXT_CONTENT_CLASSES: Set<String> = setOf(
        "cn.soulapp.imlib.msg.chat.TextMsg",
        "cn.soulapp.imlib.msg.chat.StringMsg",
        "cn.soulapp.imlib.msg.chat.PromptMsg",
    )

    /**
     * The one content class that is CONDITIONAL.
     *
     * `JsonMsg` carries `content` + `messageType` + `notice`, and it is used both for real exchanged
     * text (InteractEntryHelper reads it for `exchange_text`) and for card payloads. So it is not on
     * the allow-list and not on a deny-list: it is accepted only when its own unwrapped `content`
     * yields plain text that is not itself an envelope. See [decide].
     */
    val CONDITIONAL_CONTENT_CLASSES: Set<String> = setOf(
        "cn.soulapp.imlib.msg.chat.JsonMsg",
    )

    /** Field names read off a text content object, in order of preference. */
    val TEXT_FIELDS: List<String> = listOf("text", "content")

    /** Getter names on the content object that may expose the same string. */
    val TEXT_GETTERS: List<String> = listOf("getText", "getContent", "getMsgContent")

    /** JSON key that holds the human string inside a Soul envelope. */
    val TEXT_KEYS: List<String> = listOf("text", "content")

    /**
     * Keys that mark a JSON string as Soul's own envelope rather than a user's JSON message.
     *
     * Every name here was read off a VERIFIED Soul member: `messageType`/`notice` come from JsonMsg,
     * `msgContent` from ChatMessage.getMsgContent, `sessionId` from ChatMessage.getSessionId, and
     * `extString` from ChatMessage.getExtString. `aweType`-style keys are deliberately absent --
     * those belong to Douyin and must not be copy-pasted here.
     *
     * None of these is a common English word, so "did the user send JSON?" still answers itself: a
     * hand-written `{"content":"hi"}` is NOT treated as an envelope (no Soul key present) and is
     * returned verbatim.
     */
    val ENVELOPE_KEYS: List<String> = listOf(
        "messageType",
        "msgContent",
        "extString",
        "snapChat",
        "sessionId",
    )

    /** Why a message produced no line. ASCII only -- these are logged. */
    object Reason {
        const val OK = "text"
        const val NON_TEXT_CLASS = "non-text-content-class"
        const val UNKNOWN_CLASS = "unknown-content-class"
        const val NO_BODY = "blank-body"
        const val ENVELOPE_WITHOUT_TEXT = "envelope-has-no-text-field"
        const val RECALLED = "recalled"
        const val NO_MESSAGE = "not-a-soul-message"
    }

    /**
     * The outcome of classifying one message: the text to store (empty = store nothing) plus the
     * reason, so every rejection is explainable in the log instead of silent.
     */
    data class Decision(val text: String, val reason: String) {
        /** True only for a real, non-blank chat line. */
        val store: Boolean get() = text.isNotEmpty()
    }

    /**
     * THE decision function: given what was read off a message, decide what (if anything) to store.
     *
     * Kept free of reflection so the tests can drive it directly, including with the exact strings a
     * device produced. An early "no" always carries its reason -- 必看.md's 「任何提前退出都要打一行
     * 日志说明原因」, applied at the type level.
     *
     * @param contentClassName the runtime class name of `ChatMessage.getMsgContent()`, or null when
     *        the content object could not be read at all
     * @param rawBody the string read off that object (`text`/`content`), or the raw string when the
     *        content came back as a String rather than an object
     * @param recalled whether the host says the message was withdrawn
     */
    fun decide(contentClassName: String?, rawBody: String?, recalled: Boolean): Decision {
        if (recalled) return Decision("", Reason.RECALLED)
        if (rawBody.isNullOrBlank()) return Decision("", Reason.NO_BODY)

        val cls = contentClassName?.trim().orEmpty()
        val body = rawBody.trim()

        // A bare String body (some paths hand back the serialised form directly) has no class to
        // classify, so the ENVELOPE decides: parse it, and if it is not an envelope it is literal
        // text. This is the only place a class name may be absent.
        if (cls.isEmpty() || cls == "java.lang.String") {
            return fromBody(body, Reason.OK)
        }

        if (TEXT_CONTENT_CLASSES.contains(cls)) {
            // A verified text class: the body is the line. Still passed through fromBody so a text
            // class carrying an envelope cannot smuggle JSON into the store.
            return fromBody(body, Reason.OK)
        }

        if (CONDITIONAL_CONTENT_CLASSES.contains(cls)) {
            // JsonMsg: accept only a plain, non-envelope body. An envelope here is a card payload.
            if (looksLikeEnvelope(body)) return Decision("", Reason.ENVELOPE_WITHOUT_TEXT)
            return fromBody(body, Reason.OK)
        }

        // Every other member of the family is a card / image / voice / video / sticker / system
        // notice. Nothing is stored, and the raw payload is never the fallback.
        if (cls.startsWith(CONTENT_PREFIX)) return Decision("", Reason.NON_TEXT_CLASS)
        return Decision("", Reason.UNKNOWN_CLASS)
    }

    /**
     * Normalises a candidate body: unwrap an envelope, or reject it.
     *
     * The envelope branch is where the Douyin 20 KB base64 bug is structurally prevented: an envelope
     * with no readable text field returns EMPTY rather than the envelope itself.
     */
    fun fromBody(body: String, okReason: String): Decision {
        if (body.isEmpty()) return Decision("", Reason.NO_BODY)
        if (!looksLikeEnvelope(body)) return Decision(body, okReason)
        val t = extractTextField(body)
        if (t.isNullOrBlank()) return Decision("", Reason.ENVELOPE_WITHOUT_TEXT)
        // The unwrapped text may itself be an envelope on a nested payload; one more pass, no loop.
        if (looksLikeEnvelope(t)) {
            val t2 = extractTextField(t)
            if (t2.isNullOrBlank()) return Decision("", Reason.ENVELOPE_WITHOUT_TEXT)
            return Decision(t2.trim(), okReason)
        }
        return Decision(t.trim(), okReason)
    }

    /**
     * True when [s] is Soul's serialised envelope rather than a plain chat line.
     *
     * Requires BOTH the brace shape AND one of Soul's own keys. Requiring a known key is what stops
     * the parser from mangling a message that merely looks like JSON -- someone really can send
     * `{"a":1}` in a chat, and that must be stored as typed.
     */
    fun looksLikeEnvelope(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return false
        if (t[0] != '{' || t[t.length - 1] != '}') return false
        for (k in ENVELOPE_KEYS) {
            if (t.contains("\"" + k + "\"")) return true
        }
        return false
    }

    /**
     * Pulls the first textual key out of [json], or null when there is none.
     *
     * Hand-rolled rather than org.json, for the same reason DouyinMsgAccess is: this runs inside the
     * HOST's process, and a hand-rolled scan cannot throw on an unexpected shape the way a parser
     * exception can -- it simply finds nothing, and the caller then stores nothing. The envelope is
     * machine-generated with no nesting around the text key, so a targeted scan is sufficient.
     *
     * Douyin escapes every non-ASCII character as \uXXXX, so the unescape branch is the NORMAL path
     * for Chinese text here too, not an edge case.
     */
    fun extractTextField(json: String): String? {
        for (key in TEXT_KEYS) {
            val got = extractStringValue(json, key)
            if (!got.isNullOrEmpty()) return got
        }
        return null
    }

    /** Reads `"key" : "value"` out of [json], decoding JSON escapes; null when absent. */
    fun extractStringValue(json: String, key: String): String? {
        val needle = "\"" + key + "\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && (json[j] == ' ' || json[j] == ':')) j++
            if (j < json.length && json[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < json.length) {
                    val c = json[j]
                    if (c == '\\' && j + 1 < json.length) {
                        val n = json[j + 1]
                        when (n) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = json.substring(j + 2, minOf(j + 6, json.length))
                                // A DAMAGED escape invalidates the whole value. The first revision
                                // appended the literal 'u' and kept scanning, which turned
                                // `"\uZZZZ"` into the junk string "uZZZ" -- i.e. it INVENTED text
                                // where the payload was unreadable. Returning null makes the caller
                                // store nothing, which is the whole contract of this file (and is
                                // asserted by aDamagedEnvelopeYieldsNullNotAnException).
                                val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                                if (code == null) return null
                                sb.append(code.toChar())
                                j += 4
                            }
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            else -> sb.append(n)
                        }
                        j += 2
                        continue
                    }
                    if (c == '"') break
                    sb.append(c)
                    j++
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            i = json.indexOf(needle, i + 1)
        }
        return null
    }

    /**
     * Parses Soul's STRING msgId into the long the store keys on.
     *
     * `ImMessage.getMsgId()` returns `Ljava/lang/String;` -- verified -- so this is not a cast but a
     * real conversion, and it can legitimately fail (a non-numeric or empty id). 0L means "no usable
     * id", which makes the caller fall through to [syntheticId] instead of dropping the message;
     * dropping was the Douyin bug, because a freshly bound message often has no id yet.
     */
    fun parseMsgId(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        val n = s.toLongOrNull() ?: return 0L
        return if (n <= 0L) 0L else n
    }

    /**
     * A stable key for a message that has no usable host id.
     *
     * Deterministic on (talker, time, direction, text), so the SAME message always produces the SAME
     * key. That is what makes the store idempotent across repeated binds: re-binding a row cannot
     * create a duplicate, which is required because a RecyclerView re-binds constantly. The value is
     * forced into a high range so it cannot collide with a real Soul id.
     *
     * Mirrors AnalysisController.syntheticId deliberately, so a message recorded by this feed and
     * one recorded by any other path collapse into a single line instead of two.
     */
    fun syntheticId(talker: String, time: Long, isSend: Boolean, text: String): Long {
        val h = (talker + "|" + time + "|" + isSend + "|" + text).hashCode().toLong()
        return (h and 0xFFFFFFFFL) or 0x100000000L
    }

    /**
     * Normalises a host timestamp to MILLISECONDS.
     *
     * WHY THIS EXISTS AT ALL: the shared live feed rejects any message whose time is below
     * 1_000_000_000_000 (see AnalysisController.onLiveMessage), because a value that small is a
     * placeholder rather than an epoch. Soul's `serverTime`/`localTime` were not confirmed to be
     * milliseconds, and if they turn out to be SECONDS then every single message would be rejected
     * and the history would stay empty -- which is precisely the reported failure this task is
     * fixing. So the magnitude is normalised here, and [timeBranch] reports WHICH unit was assumed so
     * the first device run proves it instead of leaving it to be inferred from silence.
     *
     * This is a unit fix keyed on magnitude, NOT a guess about which field is correct: no value is
     * coerced in a way that changes the ORDER of messages, and a too-small value is passed through
     * unchanged so the shared feed's own placeholder guard still rejects it.
     *
     * Deliberately PURE -- no shared mutable state. An earlier revision recorded the branch in a
     * `@Volatile var` on this object, which made two concurrent messages race and, worse, made the
     * unit tests order-dependent. The branch is now derived on demand from the same input.
     */
    fun toMillis(raw: Long): Long = when (timeBranch(raw)) {
        "s->ms" -> raw * 1000L
        else -> raw
    }

    /**
     * Which unit [raw] was interpreted as: "ms", "s->ms", "too-small" or "non-positive".
     *
     * Separate from [toMillis] so a caller can LOG the decision without the conversion having to
     * remember it. Kept public because the first device run is expected to quote this string.
     */
    fun timeBranch(raw: Long): String = when {
        raw <= 0L -> "non-positive"
        // Already a plausible epoch in ms (2001-09-09 .. year 33658).
        raw >= 1_000_000_000_000L -> "ms"
        // A plausible epoch in SECONDS (1973 .. 33658). Scale it up.
        raw >= 1_000_000_000L -> "s->ms"
        else -> "too-small"
    }
}
