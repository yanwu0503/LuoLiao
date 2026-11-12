package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log
import java.lang.reflect.Method

/**
 * Message field access for DOUYIN, sibling of [MsgAccess].
 *
 * WHY A SECOND CLASS INSTEAD OF A BRANCH IN MsgAccess:
 * WeChat's message object stores its fields under stable NAMES (`field_content`) on an obfuscated
 * class, so MsgAccess reads fields and falls back to obfuscated accessors. Douyin is the opposite:
 * its message class is NOT obfuscated at all (`com.bytedance.im.core.model.Message`) and the data is
 * behind ordinary getters. The only thing the two share is the SHAPE of the answer
 * (content / talker / isSend / type / time / id), so they share the caller, not the reader.
 *
 * VERIFIED FROM DEX (douyin 39.9.0, classes44.dex) -- `getContent`, `getConversationId`, `getSender`,
 * `isSelf`, `getMsgType`, `getCreatedAt`, `getMsgId`, `getSecSender`, `getConversationType`,
 * `isRecalled` all exist with those exact names and `()Ljava/lang/String;` / `()J` / `()I` / `()Z`
 * signatures. Unlike WeChat there is no name table to guess.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class DouyinMsgAccess {

    private companion object {
        const val TAG = "LuoLiao"

        /** 抖音消息基类（未混淆）。The class every message object in a chat derives from. */
        const val MSG_CLASS = "com.bytedance.im.core.model.Message"

        /**
         * 抖音单聊消息的 msgType 值。
         *
         * THE DEVICE SETTLED THIS. A real text message long-pressed in the chat screen reported
         *
         *     getMsgType() = 7        (device log, Douyin 39.9.0)
         *
         * while static analysis of the enum suggested 7 might mean FILE and 8/12 might be the text
         * values. The device value wins for the type this module actually receives, but the enum is
         * read in more than one place in Douyin, so [TEXT_TYPES] accepts all three rather than
         * betting the whole feature on one number. Being permissive here is safe because [isText]
         * ALSO requires a non-blank body, so a FILE message cannot slip through on a number alone.
         */
        const val TYPE_TEXT = 7

        /**
         * Every msgType that may carry a chat line: the confirmed 7, plus the 8/12 that the enum
         * analysis offered for text. Accepting a superset costs nothing (the body check still
         * filters) and protects the feature if a Douyin update renumbers the enum.
         */
        val TEXT_TYPES = setOf(7, 8, 12)

        /**
         * Keys that mark a string as Douyin's content ENVELOPE rather than a chat line.
         *
         * Used by [looksLikeEnvelope] to decide whether JSON parsing is warranted. Requiring one of
         * these (instead of just a leading '{') is what keeps a user who literally sends JSON as a
         * chat message from having their text rewritten by the parser.
         */
        val ENVELOPE_KEYS = listOf("aweType", "item_type_local", "instruction_type")

        /** Message types that carry text we must never treat as a chat line. */
        val NON_CHAT_HINTS = listOf(
            "com.ss.android.ugc.aweme.im.business.chat.msgcell.ProxyMessage",
        )
    }

    /**
     * One-shot runtime probe, mirroring MsgAccess.probe.
     *
     * Douyin's message class is documented above from the dex, but the OBJECT handed to a hook may
     * be a subclass or a wrapper, so this dumps what is ACTUALLY there. It is the first thing to
     * read when a Douyin hook fires but the analysis comes out empty -- exactly the role the
     * WeChat probe played.
     */
    fun probe(msg: Any) {
        if (probed) return
        probed = true
        val sb = StringBuilder()
        sb.append("=== DOUYIN message probe ").append(msg.javaClass.name).append(" ===\n")

        val wanted = listOf(
            "getContent", "getConversationId", "getSender", "getSecSender", "isSelf",
            "getMsgType", "getCreatedAt", "getMsgId", "getConversationType", "isRecalled",
            "getConversationShortId", "getIndex", "getUuid",
        )
        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!wanted.contains(m.name)) continue
                val v = try {
                    m.isAccessible = true
                    m.invoke(msg)
                } catch (_: Throwable) {
                    "<inaccessible>"
                }
                sb.append("  ").append(m.name).append("() = ").append(v).append('\n')
            }
            p = p.superclass
            depth++
        }
        Log.i(TAG, sb.toString())
    }

    @Volatile
    private var probed = false

    /** Cached accessors, resolved once. Douyin's names are stable, so this rarely re-probes. */
    @Volatile
    private var contentGetter: Method? = null

    @Volatile
    private var resolved = false

    /**
     * True when [msg] is a Douyin message object.
     *
     * `isAssignableFrom` rather than `==` because the chat list hands out
     * `ProxyMessage` (a SUBCLASS) rather than the base class -- see the class
     * comment. Direct equality silently rejected every real message.
     */
    fun isMessage(msg: Any?): Boolean {
        if (msg == null) return false
        return try {
            val base = Class.forName(MSG_CLASS, false, msg.javaClass.classLoader)
            base.isAssignableFrom(msg.javaClass)
        } catch (_: Throwable) {
            // The class may not be loaded yet; fall back to the name test so a hook
            // that fires early still recognises its own object.
            msg.javaClass.name == MSG_CLASS ||
                msg.javaClass.name.startsWith("com.bytedance.im.core.model.")
        }
    }

    /**
     * Message body -- the ACTUAL text the user sees, never Douyin's content envelope.
     *
     * ---------------------------------------------------------------------------------------------
     * WHY THIS PARSES JSON (the bug this fixes, reported by the user as 乱码)
     *
     * `Message.getContent()` does NOT return a chat line. It returns Douyin's serialised content
     * envelope, e.g.
     *
     *   {"aweType":700,"createdAt":0,"instruction_type":0,"is_card":false,
     *    "item_type_local":-1,"msgHint":"","text":"\u6d4b\u8bd5\u66f4\u6539","type":0}
     *
     * The first version returned that string verbatim, so the AI prompt received the whole JSON and
     * the analysis card showed it as the message. The user's report -- 「应该是 测试更改 而不是很多
     * 乱码」 -- is exactly this: the real text is the `text` field inside the envelope.
     *
     * The ROOT cause is Douyin's model shape, not a bug in the getter: the platform stores a message
     * as `Message` (metadata) plus a `BaseContent` subclass, and the on-the-wire form of that content
     * IS the JSON above. `TextContent` (com.ss.android.ugc.aweme.im.sdk.chat.model.TextContent) holds
     * the human string in a field named `text`, which is what this extracts.
     *
     * WHY NOT CALL TextContent DIRECTLY: reaching it needs the live `ProxyMessage.getContent()`-style
     * accessor on the wrapper the chat screen uses, which is obfuscated and was not confirmed. Parsing
     * the envelope is the version-stable route: the `text` KEY is plain ASCII in the payload and is
     * far less likely to change than any obfuscated accessor.
     *
     * Falls back to the raw string when there is no `text` field (a non-text message, or a future
     * envelope), because showing something is better than showing nothing.
     * ---------------------------------------------------------------------------------------------
     */
    fun content(msg: Any): String {
        val raw = rawContent(msg)
        if (raw.isEmpty()) return ""
        // A message whose TYPE is a known non-text type never yields a chat line, whatever its
        // payload looks like. Checked FIRST because some non-text messages carry a plain string that
        // is not an envelope at all (a card id, a resource name), and the envelope test below would
        // let that through as if the other side had typed it.
        if (!isTextType(typeOf(msg))) return ""
        // Not an envelope: return as-is. This also covers every plain-text case, so the parse below
        // only ever runs on content that actually looks like Douyin's JSON.
        if (!looksLikeEnvelope(raw)) return raw
        // A real text field wins.
        extractTextField(raw)?.let { return it }
        // ================== NO TEXT FIELD: THIS IS A NON-TEXT MESSAGE ==================
        //
        // The device showed what happened without this branch: images, videos, stickers and voice
        // notes were stored as their ENTIRE content envelope. One sticker produced a 20 KB line of
        // URLs and base64, and a shared video produced even more -- history files grew to tens of
        // kilobytes of machine JSON that the user cannot read and the AI should never be fed.
        //
        // Real examples captured from the device:
        //   {"aweType":501,...,"emoji_type":"favorite_emoji",...}   a sticker
        //   {"aweType":0,...,"itemId":"7546907495592168714",...}     a shared video
        //   {"resource_url":{...},"duration":2520,...}               a voice note
        //
        // The user then refined the requirement -- 「仅写入纯文本的 不会写入 图片 直播分析 等等」 --
        // so a non-text message must not become a chat line AT ALL. It returns EMPTY, and because
        // [isText] rejects a blank body, images / videos / live shares / stickers / system notices
        // stay out of the history file entirely. Empty is also what every caller already treats as
        // "skip this message" (they test isBlank), so no call site needed changing.
        //
        // WHY NOT A PLACEHOLDER ANY MORE: a placeholder still occupies a line, still appears in the
        // scope preview and still reaches the AI prompt as if the other side had said something.
        // ===============================================================================
        return ""
    }

    /**
     * True when [t] is a message type that can carry a chat line, or is unreadable.
     *
     * An unreadable type (-1) is ACCEPTED, not rejected: Douyin does not always expose a usable
     * `getMsgType()` for every cell, and the payload checks downstream are the real filter. Rejecting
     * on an unknown type would silently drop real text messages, which is worse than letting a
     * non-text one reach the envelope test -- where it returns empty anyway.
     */
    private fun isTextType(t: Int): Boolean = TEXT_TYPES.contains(t) || t < 0 || t == 0

    /** The untouched `getContent()` string, used by [probe] and by diagnostics. */
    fun rawContent(msg: Any): String {
        val cached = contentGetter
        if (cached != null) {
            try {
                return cached.invoke(msg) as? String ?: ""
            } catch (_: Throwable) {
                contentGetter = null
            }
        }
        val m = findNoArg(msg.javaClass, "getContent", String::class.java) ?: return ""
        return try {
            val v = m.invoke(msg) as? String ?: ""
            if (v.isNotEmpty()) contentGetter = m
            v
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * True when [s] is Douyin's serialised content envelope rather than a plain chat line.
     *
     * A chat line can legitimately start with '{' (people send JSON to each other), so this requires
     * BOTH the brace shape AND one of Douyin's own envelope keys. Requiring a known key is what stops
     * the parser from mangling a message that merely looks like JSON.
     */
    private fun looksLikeEnvelope(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2 || t[0] != '{' || t[t.length - 1] != '}') return false
        for (k in ENVELOPE_KEYS) {
            if (t.contains("\"" + k + "\"")) return true
        }
        return false
    }

    /**
     * Pulls the `text` field out of [json], or null when it is absent/undecodable.
     *
     * Deliberately hand-rolled instead of org.json: this runs inside the Douyin process, and a
     * hand-rolled scan cannot fail on an unexpected shape the way a parser exception would -- it
     * simply finds nothing and the caller keeps the raw string. The envelope is machine-generated
     * with no nesting around `text`, so a targeted scan is sufficient and cannot be confused by
     * escapes in OTHER fields.
     */
    private fun extractTextField(json: String): String? {
        val key = "\"text\""
        var i = json.indexOf(key)
        while (i >= 0) {
            var j = i + key.length
            // skip spaces and the colon
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
                                // \uXXXX -- Douyin escapes EVERY non-ASCII character this way, so
                                // this branch is the normal path for Chinese text, not an edge case.
                                val hex = json.substring(j + 2, minOf(j + 6, json.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    j += 4
                                } else {
                                    sb.append(n)
                                }
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
                val out = sb.toString()
                if (out.isNotEmpty()) return out
            }
            i = json.indexOf(key, i + 1)
        }
        return null
    }

    /** Conversation id -- Douyin's equivalent of WeChat's talker. */
    fun talker(msg: Any): String {
        findNoArg(msg.javaClass, "getConversationId", String::class.java)?.let {
            return try {
                it.invoke(msg) as? String ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
        return ""
    }

    /** True when the message was sent by me. Douyin spells this isSelf(). */
    fun isSend(msg: Any): Boolean {
        findNoArg(msg.javaClass, "isSelf", java.lang.Boolean.TYPE)?.let {
            return try {
                it.invoke(msg) as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
        }
        // Some wrappers expose isSelf() as Boolean (boxed).
        findNoArg(msg.javaClass, "isSelf", java.lang.Boolean::class.java)?.let {
            return try {
                it.invoke(msg) as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    fun typeOf(msg: Any): Int = callInt(msg, "getMsgType")

    fun msgId(msg: Any): Long = callLong(msg, "getMsgId")

    /** Douyin's created-at is in SECONDS (getCreatedAt); WeChat's is in seconds too. */
    fun createTime(msg: Any): Long = callLong(msg, "getCreatedAt")

    /** Sender's numeric uid; 0 when unreadable. */
    fun sender(msg: Any): Long = callLong(msg, "getSender")

    /** Sender's sec_uid, the string form used for display and for group labels. */
    fun secSender(msg: Any): String {
        findNoArg(msg.javaClass, "getSecSender", String::class.java)?.let {
            return try {
                it.invoke(msg) as? String ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
        return ""
    }

    /** 1 = single chat, larger = group. Used to decide the 我/对方 labelling. */
    fun conversationType(msg: Any): Int = callInt(msg, "getConversationType")

    /**
     * Only plain text is worth sending to the AI.
     *
     * The type test alone is not enough: an image or a card message still has a
     * non-null object, and Douyin renumbers MsgType across releases. Requiring a
     * NON-BLANK body as well means a message is analysed only when there is
     * actually something to analyse, which is the property that matters.
     */
    fun isText(msg: Any): Boolean {
        if (content(msg).isBlank()) return false
        val t = typeOf(msg)
        if (TEXT_TYPES.contains(t)) return true
        // An unreadable type (-1) with real text is still a text message.
        return t < 0 || t == 0
    }

    /** Recalled messages leak into the list and must be skipped. */
    fun isRecalled(msg: Any): Boolean {
        findNoArg(msg.javaClass, "isRecalled", java.lang.Boolean.TYPE)?.let {
            return try {
                it.invoke(msg) as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    /** True when the object is a message AND usable as a chat line. */
    fun usable(msg: Any?): Boolean =
        isMessage(msg) && !isRecalled(msg as Any) && isText(msg)

    // ---- reflection helpers, walking up the hierarchy like MsgAccess does ----

    private fun findNoArg(cls: Class<*>, name: String, ret: Class<*>): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (m in p.declaredMethods) {
                if (m.name != name) continue
                if (m.parameterCount != 0) continue
                if (!ret.isAssignableFrom(m.returnType)) continue
                m.isAccessible = true
                return m
            }
            p = p.superclass
            depth++
        }
        return null
    }

    private fun callInt(obj: Any, name: String): Int {
        findNoArg(obj.javaClass, name, Integer.TYPE)?.let {
            return try {
                it.invoke(obj) as? Int ?: -1
            } catch (_: Throwable) {
                -1
            }
        }
        return -1
    }

    private fun callLong(obj: Any, name: String): Long {
        findNoArg(obj.javaClass, name, java.lang.Long.TYPE)?.let {
            return try {
                it.invoke(obj) as? Long ?: 0L
            } catch (_: Throwable) {
                0L
            }
        }
        return 0L
    }
}
