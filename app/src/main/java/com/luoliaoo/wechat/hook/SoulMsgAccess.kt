package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Message field access for SOUL, sibling of [MsgAccess] and [DouyinMsgAccess].
 *
 * WHY A THIRD READER INSTEAD OF A BRANCH: the three hosts agree only on the SHAPE of the answer
 * (content / talker / isSend / type / time / id). WeChat reads stable field NAMES on an obfuscated
 * class; Douyin reads ordinary getters on an un-obfuscated class; Soul is a THIRD shape -- its own IM
 * library (`cn.soulapp.imlib`), un-obfuscated, but with a nested content object and a STRING msg id.
 * Sharing the caller while keeping the reader separate is what stops one host's assumption from
 * silently breaking another host's history.
 *
 * ============================= EVERY FACT BELOW IS VERIFIED, NOT GUESSED =======================
 * Read from Soul 6.37.0's own dex (17 files, `_tools/souldex`), via `python soul_xref.py`:
 *
 *   cn.soulapp.imlib.msg.ImMessage                       [classes17.dex]
 *     fields:  msgId:String, from:String, to:String, date:String, msgType:I,
 *              msgStatus:I, msgSource:I, msgReceiveStatus:I, isAck:I,
 *              serverTime:J, localTime:J, acceptedMsgId:String,
 *              quoteMsg:ImMessage, maps:ConcurrentHashMap, callBackRef:WeakReference
 *     methods: getMsgId()Ljava/lang/String;   <-- A STRING, not a long
 *              getFrom() / getTo() / getOtherId() / getMsgType()I
 *              getServerTime()J / getLocalTime()J / getChatMessage()
 *
 *   cn.soulapp.imlib.msg.chat.ChatMessage                [classes17.dex]
 *     methods: getMsgContent()Ljava/io/Serializable;  getMsgType()I
 *              getSessionId() / getExtString() / getNotice()
 *              getExt(String) / put(String,Serializable)
 *
 *   cn.soulapp.imlib.msg.chat.TopChatMsg (base of 16 subclasses) [classes17.dex]
 *     TextMsg.text / StringMsg.content / PromptMsg.text carry a chat line.
 *     ImgMsg, AudioMsg, VideoMsg, ExpressionMsg, UserCardMsg, MusicMsg, PositionMsg,
 *     RePostMsg, CallMsg, ... carry none.
 *
 *   cn.soulapp.imlib.j0->g()Ljava/lang/String;           [classes17.dex]
 *     the logged-in user's own id ("getUserId" off the host's InitListener). Used ONLY to label the
 *     direction, and the branch taken is logged because it is the one piece a device must confirm.
 *
 * HOW THE OBJECT IS ACTUALLY READ AT RUNTIME -- and why that matters
 * The dex proves the SHAPE; it does not prove that the object handed to a hook is exactly that class.
 * `cn.soulapp.android.component.chat.widget.ShiningTextItem` was disassembled to see how Soul ITSELF
 * reads a text message, and it does:
 *
 *     imMessage.getChatMessage().getMsgContent()   ->  (String) ->  JSONObject -> optString("content")
 *
 * i.e. THE CONTENT REALLY CAN COME BACK AS ENVELOPE JSON, exactly like Douyin's. That is the 20 KB
 * base64 bug waiting to happen again (必看.md 「抖音聊天记录里存进大段 JSON」), so content() delegates
 * every classification decision to [SoulEnvelope], which is pure, unit-tested code whose contract is
 * "a non-text message yields EMPTY -- never a placeholder, never raw JSON".
 *
 * DEGRADING, NEVER GUESSING (explicit instruction from the captain): when something cannot be read,
 * this class returns the NEUTRAL value ("" / 0L / false) and logs which branch it took. It never
 * invents a plausible id, never falls back to the raw payload, and never lets a guessed class name
 * make the pipeline merely LOOK alive. An idle pipeline that says why beats a fake success.
 *
 * ============================== TINKER HOT-PATCH WARNING ======================================
 * Every Soul class carries `static cn.soul.android.plugin.ChangeQuickRedirect changeQuickRedirect`
 * and its methods begin with `PatchProxy.proxy(...)`. Soul can therefore REPLACE a method body at
 * runtime, so a hook installed before a patch lands may end up bound to an implementation that is no
 * longer called -- which looks exactly like the Douyin "the hook never fired" round. [probe] prints
 * whether that field is null for the classes this reader depends on, so the first device run can
 * rule the variable out instead of being confused by it.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class SoulMsgAccess(
    /**
     * The HOST classloader, taken from [Resolver] and never from a constructor argument.
     *
     * WHY IT IS REQUIRED RATHER THAN OPTIONAL: 必看.md records that resolving host classes through the
     * wrong loader silently produces a "class not found" that looks like a missing feature. Making the
     * resolver a constructor parameter means no call site can accidentally reach for a loader that
     * belongs to the module instead of the host.
     */
    private val resolver: Resolver,
) {

    private companion object {
        const val TAG = "LuoLiao"

        /** 灵魂消息基类（未混淆）。The class every chat message object in Soul derives from. */
        const val MSG_CLASS = "cn.soulapp.imlib.msg.ImMessage"

        /** The class that owns the logged-in user's id. `j0.g()` -> getUserId(). */
        const val IDS_CLASS = "cn.soulapp.imlib.j0"

        /** The static field Tinker uses to redirect a method. Null = not hot-patched right now. */
        const val REDIRECT_FIELD = "changeQuickRedirect"

        /** Cap on distinct adapter/content classes named in the diagnostic -- by TYPE, not by event. */
        const val DIAG_LIMIT = 10

        /**
         * Ext keys a recall is carried under.
         *
         * VERIFIED as the names Soul itself uses: `recall_msg` and `recallContent` are read by
         * `ConversationFragment.onCmdMsgReceive`, and `ChatManager.P/S` persist a `recallMsgIds`
         * list under the same namespace. Checked through `getStringTransExt`/`getExt`, both of which
         * `ChatMessage` declares.
         */
        val RECALL_KEYS = listOf("recall_msg", "recallContent", "recallMsgId")

        /**
         * The notice text Soul writes into a recalled message. Its literal `\uXXXX` forms are taken
         * from the verified strings in the dex, so this file stays ASCII-only.
         */
        const val RECALL_NOTICE = "\u5df2\u64a4\u56de"   // 已撤回
    }

    /**
     * Diagnostic state, deduplicated BY CLASS NAME rather than by a counter.
     *
     * 必看.md, 「诊断日志...被会话列表的 8 次绑定占满」: a count-based cap was consumed by the
     * conversation list before the chat page ever bound, so the one thing the diagnostic existed to
     * reveal was never printed. A Set of names means the conversation list contributes ONE line
     * however many rows it binds, and opening a chat is therefore guaranteed to add the chat's types.
     */
    private val seenTypes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    private val seenNotes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    @Volatile
    private var probed = false

    @Volatile
    private var resolved = false

    /** Cached accessors, so a 60-bind-per-second chat scroll does no repeated method walking. */
    @Volatile
    private var chatMessageGetter: Method? = null

    @Volatile
    private var msgContentGetter: Method? = null

    @Volatile
    private var myUidCache: String? = null

    @Volatile
    private var myUidTried = false

    /** The branch [content], [talker] and [isSend] took on the last call, for the log. */
    @Volatile
    var lastBranch: String = "none"
        private set

    /** The reason the last [content] call did or did not produce a line. */
    @Volatile
    var lastReason: String = SoulEnvelope.Reason.OK
        private set

    // ------------------------------------------------------------------ probe

    /**
     * One-shot runtime probe: dumps the classes this reader ACTUALLY depends on.
     *
     * This is the deliverable the task demands -- no device is attached, so the first real run must be
     * able to CONFIRM the adapter / content classes rather than have them assumed. Everything is
     * printed once per process, deduplicated by class name, and every uncertain step says which branch
     * it took.
     */
    fun probe(msg: Any) {
        if (probed) return
        probed = true
        val sb = StringBuilder()
        sb.append("=== SOUL message probe ").append(msg.javaClass.name).append(" ===\n")
        sb.append("  loader=").append(loaderName()).append('\n')
        sb.append("  isMessage=").append(isMessage(msg)).append('\n')

        // The whole hierarchy, so a Tinker-generated subclass is visible as itself.
        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            sb.append("  [").append(depth).append("] ").append(p.name)
                .append(" redirect=").append(redirectState(p)).append('\n')
            p = p.superclass
            depth++
        }

        sb.append("  --- ImMessage accessors as present on THIS object ---\n")
        for (n in listOf(
            "getMsgId", "getFrom", "getTo", "getOtherId", "getMsgType",
            "getServerTime", "getLocalTime", "getChatMessage", "getStringExt", "isAnonymityChat",
        )) {
            sb.append("    ").append(n).append("() = ").append(callAny(msg, n) ?: "<absent>").append('\n')
        }

        // The content object is the thing the whole "text only" rule turns on, so it is named here.
        val cm = chatMessage(msg)
        sb.append("  chatMessage=").append(cm?.javaClass?.name ?: "<unreadable>").append('\n')
        if (cm != null) {
            val content = msgContent(cm)
            sb.append("  contentObject=").append(content?.javaClass?.name ?: "<null>").append('\n')
            sb.append("  contentClassString=").append(content?.let { it.javaClass.name } ?: "<null>")
                .append('\n')
            sb.append("  contentRead=").append(shorten(readBody(content))).append('\n')
            sb.append("  notice=").append(shorten(callString(cm, "getNotice"))).append('\n')
            sb.append("  sessionId=").append(shorten(callString(cm, "getSessionId"))).append('\n')
        }

        // The reader functions themselves, so the first run shows the verdicts side by side.
        sb.append("  --- reader verdicts ---\n")
        sb.append("    content=").append(shorten(content(msg)))
            .append(" reason=").append(lastReason).append('\n')
        sb.append("    talker=").append(talker(msg)).append(" branch=").append(lastBranch).append('\n')
        sb.append("    isSend=").append(isSend(msg)).append('\n')
        sb.append("    msgType=").append(typeOf(msg)).append('\n')
        sb.append("    msgId(raw)=").append(callString(msg, "getMsgId"))
            .append(" -> ").append(msgId(msg)).append('\n')
        sb.append("    serverTime=").append(callLong(msg, "getServerTime"))
            .append(" localTime=").append(callLong(msg, "getLocalTime"))
            .append(" -> ms=").append(createTime(msg)).append('\n')
        sb.append("    isText=").append(isText(msg)).append(" isRecalled=").append(isRecalled(msg))
            .append('\n')
        sb.append("    myUid=").append(if (myUid().isEmpty()) "<unavailable>" else myUid()).append('\n')
        Log.i(TAG, sb.toString())
    }

    /** Reports the Tinker state of the reader's two carrier classes; callable from the install path. */
    fun probeClasses() {
        if (resolved) return
        resolved = true
        Log.i(
            TAG,
            "SOUL classes: " + MSG_CLASS + " redirect=" + redirectStateByName(MSG_CLASS) +
                " | " + IDS_CLASS + " redirect=" + redirectStateByName(IDS_CLASS) +
                " (null = not hot-patched right now)",
        )
    }

    // ------------------------------------------------------------------ identity

    /** The host classloader, resolved once through [Resolver]. */
    private fun loader(): ClassLoader = resolver.classLoader()

    private fun loaderName(): String = try {
        loader().javaClass.name
    } catch (_: Throwable) {
        "<unavailable>"
    }

    /**
     * True when [msg] is a Soul message object.
     *
     * `isAssignableFrom` rather than equality, because Soul hot-patches classes and the chat list can
     * hand out a subclass; a direct equality test is what silently rejected every Douyin message that
     * arrived as `ProxyMessage`.
     *
     * ===================== THE FALLBACK USED TO GUESS. IT NO LONGER DOES. =======================
     *
     * This method previously fell back to `name.startsWith("cn.soulapp.imlib.msg.")` when the base
     * class could not be loaded -- and that is a GUESS, for two reasons that were both verifiable
     * before the fact, so it was not a defensible one:
     *
     *   1. An earlier revision of that namespace is not a promise about its contents. Dumping it
     *      shows THREE members, not one: `ImMessage`, but also the obfuscated helpers `msg.a` and
     *      `msg.b`. A prefix match accepts those too, so a helper object would be handed to the whole
     *      downstream pipeline as if it were a message -- the "looks alive, does nothing" failure.
     *   2. It logged NOTHING on the fallback path. So when it mis-fired there was no evidence at all
     *      that the weak route had been taken, which is exactly the ambiguity 必看.md keeps warning
     *      about ("an exit with no log is indistinguishable from code that never ran").
     *
     * The requirement for this class is: DEGRADE, DO NOT GUESS -- an idle feed that explains itself
     * beats a feed that appears to work. So when the class cannot be resolved, this now:
     *   * accepts only an EXACT name match (the one name that was verified, not a whole namespace);
     *   * records the weak route ONCE, naming the class and saying why.
     *
     * The cost is real and deliberate: a Tinker-generated subclass arriving before its base class is
     * loadable is not recognised, so one message may be missed. A missed line is recoverable and the
     * log says exactly what happened; a fabricated "message" is not, because it is already in the
     * store by the time anyone notices.
     */
    fun isMessage(msg: Any?): Boolean {
        if (msg == null) return false
        return try {
            val base = Class.forName(MSG_CLASS, false, loader())
            base.isAssignableFrom(msg.javaClass)
        } catch (t: Throwable) {
            // EXACT name only -- never the namespace. See SoulMsgAccessNoGuessTest, which pins this.
            //
            // v6.0.0 FIX: this branch used to read
            //
            //     val exact = msg.javaClass.name == MSG_CLASS ||
            //         msg.javaClass.name.startsWith("cn.soulapp.imlib.msg.")
            //
            // while the comment above it said "Exact name only -- never the namespace". The code and
            // its OWN comment contradicted each other, and the code was the wrong half: that namespace
            // holds THREE members -- ImMessage plus the obfuscated helpers msg.a and msg.b -- so the
            // prefix test accepted both helpers. A helper admitted here is treated as a real message by
            // every later stage, which is a store that LOOKS like it works; and the old path logged
            // nothing, so a mis-fire left no trace at all. Degrading to an exact match means a genuine
            // future rename makes the feed go IDLE (safe, and visible in the log) instead of
            // fabricating rows.
            val exact = msg.javaClass.name == MSG_CLASS
            if (!exact) {
                noteOnce(
                    "isMessage-degraded",
                    "SOUL: " + MSG_CLASS + " is not loadable (" + t.javaClass.simpleName +
                        "), so message identity is DEGRADED to an exact-name match only; " +
                        msg.javaClass.name + " was therefore NOT accepted and nothing is stored for " +
                        "it. (A namespace/prefix match was rejected on purpose: that namespace also " +
                        "contains the helpers msg.a and msg.b, and accepting those would push a " +
                        "non-message through the whole pipeline. An empty feed is safer than a " +
                        "fabricated one.)",
                )
            }
            exact
        }
    }

    // ------------------------------------------------------------------ content

    /**
     * The chat line the user sees, or EMPTY.
     *
     * The whole "record only plain text" requirement lands here. Every classification is delegated to
     * [SoulEnvelope.decide], which is pure and unit tested, so this method's only job is to read the
     * object faithfully and report the branch it took.
     */
    fun content(msg: Any): String {
        val cm = chatMessage(msg)
        if (cm == null) {
            lastReason = SoulEnvelope.Reason.NO_BODY
            noteOnce(
                "no-chat-message:" + msg.javaClass.name,
                "SOUL: " + msg.javaClass.name + " has no readable getChatMessage(); " +
                    "nothing was stored for it (branch=no-content-carrier)",
            )
            return ""
        }
        val content = msgContent(cm)
        if (content == null) {
            lastReason = SoulEnvelope.Reason.NO_BODY
            return ""
        }
        val cls = if (content is String) "java.lang.String" else content.javaClass.name
        val body = readBody(content)
        val d = SoulEnvelope.decide(cls, body, isRecalled(msg))
        lastReason = d.reason
        lastBranch = "content:" + cls
        if (!d.store) {
            // Every rejection is explainable -- 必看.md 「任何提前退出都要打一行日志说明原因」.
            noteOnce(
                "reject:" + cls + ":" + d.reason,
                "SOUL: skipped a message (contentClass=" + cls + " reason=" + d.reason +
                    " rawLen=" + (body?.length ?: 0) + ") -- nothing stored",
            )
        }
        return d.text
    }

    /** The `ChatMessage` carrier, or null when it cannot be read. */
    private fun chatMessage(msg: Any): Any? {
        val cached = chatMessageGetter
        if (cached != null) {
            try {
                val v = cached.invoke(msg)
                if (v != null) return v
            } catch (_: Throwable) {
                chatMessageGetter = null
            }
        }
        val m = findNoArg(msg.javaClass, "getChatMessage") ?: return null
        return try {
            val v = m.invoke(msg)
            if (v != null) chatMessageGetter = m
            v
        } catch (_: Throwable) {
            null
        }
    }

    /** `ChatMessage.getMsgContent()` -- the Serializable content object (or a raw String). */
    private fun msgContent(cm: Any): Any? {
        val cached = msgContentGetter
        if (cached != null) {
            try {
                return cached.invoke(cm)
            } catch (_: Throwable) {
                msgContentGetter = null
            }
        }
        val m = findNoArg(cm.javaClass, "getMsgContent") ?: return null
        return try {
            val v = m.invoke(cm)
            if (v != null) msgContentGetter = m
            v
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Reads the textual `text`/`content` field off the content object.
     *
     * Field FIRST, getter second: the fields were verified by name on each subclass
     * (`TextMsg.text`, `StringMsg.content`, `JsonMsg.content`), so the field is the direct read, and a
     * getter is only a fallback for a subclass that hides it. Verified against Soul's own usage in
     * ShiningTextItem, which goes through the serialised form instead -- both routes are covered
     * because the reader must work whichever shape the device produces.
     */
    private fun readBody(content: Any?): String? {
        if (content == null) return null
        if (content is String) return content
        for (name in SoulEnvelope.TEXT_FIELDS) {
            val v = readField(content, name)
            if (v is String && v.isNotEmpty()) return v
        }
        for (name in SoulEnvelope.TEXT_GETTERS) {
            val v = callString(content, name)
            if (!v.isNullOrEmpty()) return v
        }
        // The content object may itself BE the serialised envelope under a generic name.
        val ext = callString(content, "getExtString")
        if (!ext.isNullOrEmpty()) return ext
        return null
    }

    // ------------------------------------------------------------------ attributes

    /**
     * The conversation id this message belongs to, used to key the store.
     *
     * `getOtherId()` is tried FIRST. It is the peer of the conversation (the dex shows
     * `getConversation(it.otherId)` and `genUserIdEcpt(message.otherId)`), so it names the SAME
     * conversation for both directions of the chat -- which is what the store needs, because a chat
     * keyed by sender would split one conversation in two.
     *
     * When it is blank the direction is resolved against my own id and the opposite side is used.
     * `lastBranch` records which route ran, so the first device log answers the question instead of
     * leaving it to be inferred from a file that did not appear.
     */
    fun talker(msg: Any): String {
        val other = callString(msg, "getOtherId")?.trim().orEmpty()
        if (other.isNotEmpty()) {
            lastBranch = "otherId"
            return other
        }
        val from = callString(msg, "getFrom")?.trim().orEmpty()
        val to = callString(msg, "getTo")?.trim().orEmpty()
        val mine = myUid()
        if (mine.isNotEmpty()) {
            if (from == mine && to.isNotEmpty()) {
                lastBranch = "from==mine->to"
                return to
            }
            if (to == mine && from.isNotEmpty()) {
                lastBranch = "to==mine->from"
                return from
            }
        }
        // `ChatMessage.sessionId`, tried only after the peer id.
        //
        // WHY IT IS NOT FIRST, despite being the obvious "conversation id": `otherId` is computed by
        // Soul from the SAME `from`/`to` fields this test just read (verified by disassembling
        // `ImMessage.getOtherId`, which compares `from` against `j0.g()` and returns the opposite
        // side), so it is guaranteed to name one conversation for BOTH directions. `sessionId` is a
        // separate value that is SET in various chat screens (17 writers verified in the dex), and a
        // value that is sometimes blank or screen-scoped would split one conversation into two files --
        // the failure the store cannot repair. So it is a LAST RESORT before giving up, and the log
        // says which route was used, which is what makes the first device run answer this properly.
        val session = chatSessionId(msg)
        if (session.isNotEmpty()) {
            lastBranch = "sessionId"
            return session
        }
        lastBranch = if (from.isNotEmpty()) "from" else "empty"
        if (from.isEmpty()) {
            noteOnce(
                "no-talker:" + msg.javaClass.name,
                "SOUL: could not determine a conversation for " + msg.javaClass.name +
                    " (otherId/sessionId/from/to all blank); the message is skipped rather than " +
                    "filed under a guessed key",
            )
        }
        return from
    }

    /**
     * `ImMessage.getChatMessage().getSessionId()`, or "" when either hop is unavailable.
     *
     * Wrapped in one try/catch because it is a two-hop reflective read on a per-message path: a
     * Tinker-patched or absent member must degrade to "no session id" and not interrupt the feed.
     */
    private fun chatSessionId(msg: Any): String {
        return try {
            val cm = chatMessage(msg) ?: return ""
            callString(cm, "getSessionId")?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * True when I sent the message.
     *
     * `getOtherId()` is the OTHER side, so `from == otherId` means they sent it. That test needs no
     * knowledge of my own id, which is why it is tried first -- `j0.g()` depends on the host's
     * InitListener and can legitimately be unavailable early in a process.
     */
    fun isSend(msg: Any): Boolean {
        val other = callString(msg, "getOtherId")?.trim().orEmpty()
        val from = callString(msg, "getFrom")?.trim().orEmpty()
        val to = callString(msg, "getTo")?.trim().orEmpty()
        if (other.isNotEmpty()) {
            if (from.isNotEmpty() && from == other) return false
            if (to.isNotEmpty() && to == other) return true
        }
        val mine = myUid()
        if (mine.isNotEmpty()) {
            if (from == mine) return true
            if (to == mine) return false
        }
        return false
    }

    /** Soul's message type. The content CLASS is the real type test; see [SoulEnvelope]. */
    fun typeOf(msg: Any): Int = callInt(msg, "getMsgType")

    /**
     * Soul's `getMsgId()` returns a STRING (verified), so this is a real conversion, not a cast.
     *
     * 0L means "no usable id", which deliberately makes the caller fall through to a synthetic key
     * instead of dropping the message -- a freshly bound message has no id yet, and requiring one is
     * exactly why Douyin's history only appeared after an analysis.
     */
    fun msgId(msg: Any): Long = SoulEnvelope.parseMsgId(callString(msg, "getMsgId"))

    /**
     * Soul has NO server-message id. Returns 0L, the same neutral value [DouyinMsgAccess] returns, so
     * ResultCache's key composition treats it as absent. Documented rather than invented: a plausible
     * fake id would silently collide two different messages.
     */
    fun msgSvrId(msg: Any): Long = 0L

    /**
     * Message time in MILLISECONDS, from `serverTime` (preferred, it survives a device clock change)
     * falling back to `localTime`. [SoulEnvelope.toMillis] normalises the magnitude and records which
     * branch it took, because the shared live feed rejects anything below a plausible epoch and a
     * SECONDS-valued host field would therefore reject every message.
     */
    fun createTime(msg: Any): Long {
        val server = callLong(msg, "getServerTime")
        if (server > 0L) return SoulEnvelope.toMillis(server)
        val local = callLong(msg, "getLocalTime")
        if (local > 0L) return SoulEnvelope.toMillis(local)
        return 0L
    }

    /** Only plain text is analysed and stored (per requirement). */
    fun isText(msg: Any): Boolean = content(msg).isNotBlank()

    /**
     * True when Soul marks the message as withdrawn.
     *
     * ============================ HONEST LIMIT -- READ THIS BEFORE TRUSTING IT ====================
     * A per-message recall FLAG could NOT be confirmed statically: `ImMessage` exposes no
     * `isRecalled()` (unlike Douyin's `Message`), and `ChatMessage$MsgType` has no constants at all in
     * the dex, so no recall msgType value can be pinned down. What IS verified is that recall travels
     * as its own signal -- `recall_msg` / `recallContent` are read by
     * `ConversationFragment.onCmdMsgReceive`, and `ChatManager.P/S` persist a `recallMsgIds` list.
     *
     * So this checks the EXT keys Soul itself uses and DEGRADES TO FALSE when it cannot read them --
     * it never guesses a msgType. `lastReason`/the one-time log state that the fallback ran, so the
     * first device run can confirm whether a recalled message slipped through instead of the gap
     * being discovered later as "the history has a message that was deleted".
     *
     * This is the captain's rule applied literally: degrade, do not guess. A missed recall costs one
     * extra line; a guessed msgType could silently drop every real message.
     * ==============================================================================
     */
    fun isRecalled(msg: Any): Boolean {
        val cm = chatMessage(msg) ?: return recallFallback(msg)
        for (key in RECALL_KEYS) {
            val v = callString(cm, "getStringTransExt", key)
            if (!v.isNullOrEmpty()) return true
            val e = callAny(cm, "getExt", key)
            if (e is String && e.isNotEmpty()) return true
        }
        // The body may have been replaced by Soul's own recall notice while the flag stayed unreadable.
        val notice = callString(cm, "getNotice")
        if (notice != null && notice.contains(RECALL_NOTICE)) return true
        return recallFallback(msg)
    }

    private fun recallFallback(msg: Any): Boolean {
        noteOnce(
            "recall-unconfirmed",
            "SOUL: no per-message recall flag could be read on " + msg.javaClass.name +
                " (ImMessage has no isRecalled and MsgType carries no constants); " +
                "recall is detected via the ext keys " + RECALL_KEYS +
                " and the notice text only -- recalled messages may still be recorded",
        )
        return false
    }

    /** True when the object is a Soul message AND usable as a chat line. */
    fun usable(msg: Any?): Boolean =
        isMessage(msg) && msg != null && !isRecalled(msg) && isText(msg)

    // ------------------------------------------------------------------ self id

    /**
     * My own Soul id, read through `cn.soulapp.imlib.j0.g()` (verified static, no-arg).
     *
     * Cached and probed once. An empty result is RETURNED AS EMPTY and logged -- the caller then falls
     * back to the `otherId` comparison, which needs no self id at all. A plausible-looking guess here
     * would mislabel every message's direction, so there is none.
     */
    private fun myUid(): String {
        myUidCache?.let { return it }
        if (myUidTried) return ""
        myUidTried = true
        val out = try {
            val cls = Class.forName(IDS_CLASS, false, loader())
            val m = cls.declaredMethods.firstOrNull {
                it.name == "g" && it.parameterCount == 0 && it.returnType == String::class.java
            }
            m?.isAccessible = true
            (m?.invoke(null) as? String)?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        myUidCache = out
        if (out.isEmpty()) {
            noteOnce(
                "no-self-uid",
                "SOUL: my own id is unavailable (" + IDS_CLASS + ".g()); direction falls back to " +
                    "comparing from/to against getOtherId()",
            )
        }
        return out
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Logs one line per distinct KEY, never per event.
     *
     * The key embeds the class name and the reason, so a chat that binds 60 rows a second still
     * produces one line per KIND of rejection -- the whole point of 必看.md's dedup rule.
     */
    private fun noteOnce(key: String, line: String) {
        if (seenNotes.size >= DIAG_LIMIT) return
        if (!seenNotes.add(key)) return
        Log.i(TAG, line)
    }

    /** Names the classes this reader ends up using, once each. Used by the install log. */
    fun noteType(name: String, detail: String) {
        if (seenTypes.size >= DIAG_LIMIT) return
        if (!seenTypes.add(name)) return
        Log.i(TAG, "SOUL TYPE (new #" + seenTypes.size + "): " + name + " " + detail)
    }

    private fun redirectState(c: Class<*>): String = try {
        val f: Field = c.getDeclaredField(REDIRECT_FIELD)
        f.isAccessible = true
        if (f.get(null) == null) "null" else "SET"
    } catch (_: Throwable) {
        "absent"
    }

    private fun redirectStateByName(name: String): String = try {
        redirectState(Class.forName(name, false, loader()))
    } catch (_: Throwable) {
        "unloadable"
    }

    private fun shorten(s: String?): String {
        if (s == null) return "<null>"
        return if (s.length > 60) s.substring(0, 60) + "...(" + s.length + " chars)" else s
    }

    // ------------------------------------------------------------------ reflection

    /**
     * Finds a no-arg method anywhere in the hierarchy.
     *
     * Walks superclasses because Soul's real objects are subclasses of the dex-documented classes
     * (Tinker patching produces them), and `getMethods()` alone would also drag in every inherited
     * Object method.
     */
    private fun findNoArg(cls: Class<*>, name: String): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.name != name || m.parameterCount != 0) continue
                return try {
                    m.isAccessible = true
                    m
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    private fun readField(obj: Any, name: String): Any? {
        var p: Class<*>? = obj.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name != name) continue
                return try {
                    f.isAccessible = true
                    f.get(obj)
                } catch (_: Throwable) {
                    null
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    private fun callAny(obj: Any, name: String): Any? = try {
        findNoArg(obj.javaClass, name)?.invoke(obj)
    } catch (_: Throwable) {
        null
    }

    private fun callAny(obj: Any, name: String, arg: String): Any? = try {
        val m = obj.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        m?.isAccessible = true
        m?.invoke(obj, arg)
    } catch (_: Throwable) {
        null
    }

    private fun callString(obj: Any, name: String): String? = callAny(obj, name) as? String

    private fun callString(obj: Any, name: String, arg: String): String? =
        callAny(obj, name, arg) as? String

    private fun callInt(obj: Any, name: String): Int = (callAny(obj, name) as? Int) ?: -1

    private fun callLong(obj: Any, name: String): Long = (callAny(obj, name) as? Long) ?: 0L
}
