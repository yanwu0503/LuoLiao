package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Reads a Xiaohongshu message.
 *
 * ============================== WHY THIS HOST IS READABLE WITHOUT OBFUSCATION GAMBLING ==============
 *
 * Every earlier host forced this module to match method NAMES that were themselves obfuscated:
 * `AbsMsgListVB#y` on QQ (renamed every release), `j`/`t0`/`u0`/`y0`/`z0`/`U1` as content getters on
 * WeChat. Those names are meaningless, so the module had to carry per-host candidate lists and
 * shape fallbacks, and a wrong guess showed up on the device as "no text in this message".
 *
 * Xiaohongshu's IM module is almost entirely unobfuscated. `[DEX]` evidence: its menu classes are
 * `com.xingin.im.v2.message.views.MenuView` / `MenuItemView`, its menu-item model has the fields
 * `menuItemIcon`, `menuItemIconDark`, `componentName`, `isNeedShow`, and its message row model is
 * `com.xingin.im.v2.message.Message...`. So the reader below can bind by REAL names, and the failure
 * modes are honest ones: a name that is absent is reported as absent by name.
 *
 * ============================== THE BINDING STRATEGY, IN ORDER ==============================
 *
 * For each of the five facts (text / send-side / id / time / talker) the reader tries, in order:
 *
 *   1. a NO-ARG GETTER whose name is in the candidate list (`getContent`, `content`, ...);
 *   2. a FIELD whose name is in the candidate list.
 *
 * The winner is CACHED per (class, fact), because the k-th message must not repeat the k-times
 * reflective search the first one paid for -- on QQ that mistake cost measurable scroll smoothness,
 * and 必看.md records caching per CLASS rather than per OBJECT as the rule.
 *
 * WHICH CANDIDATE IS BELIEVED FIRST is not arbitrary; each list is ordered by the measured field name
 * and then by the textual/shape evidence, and the ordering is documented on each list.
 *
 * ============================== WHAT THIS READER REFUSES TO DO ==============================
 *
 * It never guesses from a value's shape alone (e.g. "the only String field must be the text"). That
 * heuristic is what makes a reader read a STICKER ID as the message body and hand it to the model as
 * chat content -- a silent, plausible-looking wrong answer, which is worse than reporting "no text".
 *
 * Comments are ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
internal class XiaohongshuMsgAccess(
    private val resolver: Resolver,
) {

    /**
     * The packages a Xiaohongshu message may live in, plus the bare default package.
     *
     * ============================== WHY `chatbase` IS HERE ==============================
     *
     * The first version listed only `com.xingin.im.` and `com.xingin.xhs.`, on the assumption that a
     * chat model lives in the chat module. A ROOT-READ OF THE LIVE PROCESS DISPROVED IT: the class
     * descriptors loaded in the running app include
     *
     *     Lcom/xingin/chatbase/bean/MsgUIData;
     *     Lcom/xingin/chatbase/bean/MessageBean;
     *
     * -- the message models live in `com.xingin.chatbase`, a shared base library, NOT in the IM
     * module. So `isMessage` answered FALSE for the real message, no candidate was ever accepted, and
     * every tap on the injected entry logged "no message was captured": the button appeared and did
     * nothing.
     *
     * ============================== WHY THE EMPTY PREFIX IS HERE ==============================
     *
     * This host ALSO uses classes in the DEFAULT (unnamed) package -- the SOURCES diagnostic measured
     * menu items whose inherited field is typed `jy7.o` and whose value class prints as plain `o`. A
     * name-based test can never match those with a `com.` prefix, so the empty string is listed to
     * accept any class with NO dot in its name.
     *
     * That is not reckless here, and the reason is the explicit rejections in [isMessage]: a class
     * from the default package can only reach this function as an object sitting in a menu-bind
     * argument list, and every framework or payload type that list also contains -- String, Collection,
     * Number, Boolean, array, Enum, View, Context -- is refused by name before the prefix test runs.
     *
     * WHY NOT SIMPLY ACCEPT EVERYTHING: a menu-building method's arguments include several
     * non-message objects (the measured signature has an `int`, a `String`, a `View` and a callback
     * type). Accepting all of them would hand the analyser a menu label as chat content, which is a
     * silent, plausible-looking WRONG answer -- worse than reporting "no message".
     */
    val MESSAGE_PACKAGES = listOf(
        "com.xingin.chatbase.",
        "com.xingin.im.",
        "com.xingin.entities.",
        "com.xingin.xhs.",
        "",
    )

    /** Facts the reader can extract, used as cache keys. */
    private enum class Fact { CONTENT, SEND, ID, TIME, TALKER }

    /**
     * One resolved accessor, cached per (class, fact).
     *
     * `WeakHashMap<Class<*>, …>` is the right container: a Class is freed only when its loader is, so
     * holding one strongly would pin a whole classloader -- the leak 必看.md records for host caches.
     */
    private val cache: MutableMap<Class<*>, MutableMap<Fact, Accessor>> =
        Collections.synchronizedMap(WeakHashMap())

    /** One log line per distinct failure, so a repeated cause cannot bury a new one. */
    private val logged = Collections.synchronizedSet(HashSet<String>())

    /**
     * True when [o] is a Xiaohongshu MESSAGE.
     *
     * ============================== THE MEASURED FAILURE THIS TEST FIXES ==============================
     *
     * The first version accepted any object whose class sat in a host package. That was too loose, and
     * the device log shows exactly what it cost:
     *
     *     16:48:00.813 xhs: message captured from the binder CONSTRUCTOR (#1) -- MsgUIData text='ok'
     *     16:48:00.837 xhs: message captured from the binder bind (#1) -- MsgUIData text='ok'
     *     16:48:00.895 xhs: message captured for 分析 -- ly7.c text='<no text>'
     *
     * The binder captured the REAL message with its text (`ok`), and then the menu-tree scan REPLACED
     * it with `ly7.c` -- a MENU ENTRY (one of six, one per button) that merely happens to live in a
     * host package. The card rendered empty, and it looked like a capture failure when the capture had
     * actually succeeded.
     *
     * ============================== THE RULE ==============================
     *
     * [o] is a message when it is on the measured message-class list, OR when it carries the fields
     * only a message has -- an id, a chat id, or a creation time:
     *
     *     MsgUIData : msgId, chatId, creatTime        <== a message
     *     MessageBean : id, uuid, createdAt           <== a message
     *     ly7.c : no such field, only `jy7.l.a`       <== a menu entry, NOT a message
     *
     * WHY THIS STILL ACCEPTS A NON-TEXT MESSAGE: an image has no text but DOES have `msgId`/`chatId`, so
     * it passes. "Is this a message" and "does it carry text" are deliberately different questions --
     * conflating them is what makes an image message report "not a message" instead of "no text".
     *
     * WHY THE EXPLICIT REJECTIONS COME FIRST: they are cheap, and they are the types that actually sit
     * beside a message in the calls this module hooks -- a `String`, the `int` position, the `View`, a
     * `List` payload. Refusing them by name keeps the field test from having to run on them at all.
     */
    fun isMessage(o: Any?): Boolean {
        if (o == null) return false
        if (o is String || o is CharSequence) return false
        if (o is Collection<*> || o is Map<*, *>) return false
        if (o is Number || o is Boolean) return false
        if (o is Array<*>) return false
        if (o is Enum<*>) return false
        if (o is android.view.View) return false
        if (o is android.content.Context) return false
        val n = o.javaClass.name
        if (!n.contains('.')) {
            // A default-package class: this host uses them for helpers, and none is a message, so it is
            // refused unless the explicit field test below passes.
            return hasReadableMessageData(o)
        }
        if (n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("android.")) return false

        // The measured message classes, accepted by name so they work even before their fields are
        // readable (a build mid-construction, or an obfuscated field name we have not bound yet).
        if (n == XiaohongshuMenuIdentity.MSG_UI_DATA || n == XiaohongshuMenuIdentity.MESSAGE_BEAN) return true

        // Any other host class: accepted only when it actually carries message data. This is the test
        // that rejects the menu entries -- `ly7.c` and its siblings have none of these fields.
        if (!MESSAGE_PACKAGES.any { n.startsWith(it) }) return false
        return hasReadableMessageData(o)
    }

    // ------------------------------------------------------------------ the five facts

    /**
     * The message's own text, or null when it carries none.
     *
     * ============================== WHY IT LOOKS NESTED ==============================
     *
     * `[DEX]` `com.xingin.chatbase.bean.MsgUIData` -- the object the menu binder hands over -- declares
     * NO `content` field at all. Its text lives one level down, in a nested bean:
     *
     *     MsgUIData.msgGeneralBean.content                    (the plain text message)
     *     MsgUIData.msgContentBean.content                    (a quoted/replied payload)
     *     MsgUIData.hintMsg                                   (a system hint, e.g. "对方撤回了一条消息")
     *     MsgUIData.imageMsg / dslBean / dynamicCard / ...     (a non-text message: NO text at all)
     *
     * So a top-level `content` lookup returns null on the common case, which is exactly what the first
     * reader did. [NESTED_CONTENT_HOLDERS] is the measured list of beans to look inside, and it is
     * ordered so the plain-text field is found before the quoted one.
     *
     * A NON-TEXT message legitimately yields null here, and that is the honest answer: an image has no
     * text, and returning the image URL as "chat content" would be a plausible-looking WRONG answer.
     * The caller reports "\u8be5\u6d88\u606f\u65e0\u6587\u672c" instead.
     */
    fun text(msg: Any?): String? {
        // 1. the message's own text, when it has a top-level content field
        directText(msg)?.let { return it }
        // 2. one level down, through the measured content holders
        for (holder in NESTED_CONTENT_HOLDERS) {
            val bean = readFieldByName(msg, holder) ?: continue
            directText(bean)?.let { return it }
        }
        // 3. a raw hint string (a recalled-message notice is still something the model can use)
        val hint = readFieldByName(msg, "hintMsg")
        if (hint is String && hint.isNotBlank()) return hint
        return null
    }

    /** A `String`/`CharSequence` field among [CONTENT_NAMES] on [o] itself, or null. */
    private fun directText(o: Any?): String? {
        if (o == null) return null
        for (n in CONTENT_NAMES) {
            val v = readFieldByName(o, n) ?: readGetterByName(o, n)
            val s = when (v) {
                is String -> v
                is CharSequence -> v.toString()
                else -> null
            }
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /** Reads a field by name, walking the superclass chain (obfuscated leaves inherit their data). */
    private fun readFieldByName(o: Any?, name: String): Any? {
        if (o == null) return null
        var c: Class<*>? = o.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 6) {
            try {
                val f = c.getDeclaredField(name)
                if (!Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    return f.get(o)
                }
            } catch (_: Throwable) {
                // absent at this level; walk up
            }
            c = c.superclass
            hops++
        }
        return null
    }

    /** Calls a no-arg accessor named [name] or its `get`/`is` spelling, or null. */
    private fun readGetterByName(o: Any?, name: String): Any? {
        if (o == null) return null
        val wanted = listOf(
            name,
            "get" + name.replaceFirstChar { it.uppercase() },
            "is" + name.replaceFirstChar { it.uppercase() },
        ).distinct()
        for (w in wanted) {
            try {
                val m = o.javaClass.getMethod(w)
                if (m.parameterCount == 0 && Modifier.isPublic(m.modifiers)) {
                    m.isAccessible = true
                    return m.invoke(o)
                }
            } catch (_: Throwable) {
                // try the next spelling
            }
        }
        return null
    }

    /** True when the message was sent by the account holder rather than received. */
    fun isSend(msg: Any?): Boolean {
        val v = read(msg, Fact.SEND) ?: return false
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.lowercase()) {
                "true", "1", "send", "sent", "out", "self" -> true
                else -> false
            }
            else -> false
        }
    }

    /**
     * The message's stable id, or 0 when it has none.
     *
     * WHY 0 AND NOT -1: 0 is the "no id" value this codebase already uses for WeChat/QQ, so a caller
     * that tests `id != 0` behaves identically on every host. Returning -1 here would make
     * Xiaohongshu the one host where that idiom means something else.
     */
    fun id(msg: Any?): Long {
        val v = read(msg, Fact.ID) ?: return 0L
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    /** The message's timestamp in milliseconds, or 0 when it has none. */
    fun time(msg: Any?): Long {
        val v = read(msg, Fact.TIME) ?: return 0L
        val raw = when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
        return XiaohongshuReadLogic.normaliseTime(raw)
    }

    /** The conversation this message belongs to, or null. */
    fun talker(msg: Any?): String? {
        val v = read(msg, Fact.TALKER) ?: return null
        return when (v) {
            is String -> v.ifBlank { null }
            is CharSequence -> v.toString().ifBlank { null }
            else -> null
        }
    }

    // ======================================================================
    // THE [MsgAccess] FACADE CONTRACT
    // ======================================================================
    //
    // `MsgAccess` is the single facade every caller in the module reads messages through
    // (`AnalysisController`, `ChatContext`, the live-history feed). Its methods are declared with
    // NON-NULL types on other hosts -- `content(): String`, `talker(): String` -- so these adapters
    // exist to present exactly that shape while the reader above works in `String?`.
    //
    // The conversion is not cosmetic: `MsgAccess` dispatches to these by host, and a null there would
    // reach `ChatLine.text` as a null and be printed as the literal "null" in a prompt.

    /** The message body, or "" -- the facade's non-null contract. */
    fun content(msg: Any?): String = text(msg) ?: ""

    /** The conversation id, or "" -- the facade's non-null contract. */
    fun talkerId(msg: Any?): String = talker(msg) ?: ""

    /** The message's wire id as a LONG, parsed from the string id this host uses. */
    fun msgId(msg: Any?): Long = id(msg)

    /**
     * Always 0: `MsgUIData` has no server id.
     *
     * MEASURED from the host's own `toString()`: the id-bearing fields are `msgUUID` (a GUID string)
     * and `msgId` (`5f89bad9...b578`, a composite string) plus `storeId` (an int local row number).
     * There is no separate server-assigned numeric id, so 0 -- the same "absent" value
     * [QQMsgAccess.msgSvrId] returns for the same reason -- keeps `ResultCache`'s key composition
     * working without a special case at the call site.
     */
    fun msgSvrId(@Suppress("UNUSED_PARAMETER") msg: Any?): Long = 0L

    /** The message's timestamp in milliseconds, or 0. */
    fun createTime(msg: Any?): Long = time(msg)

    /** The host's own message type number, or -1 when it exposes none. */
    fun typeOf(msg: Any?): Int {
        val v = readFieldByName(msg, "msgType") ?: return -1
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: -1
            else -> -1
        }
    }

    /**
     * True when this is a plain text message.
     *
     * Requires a NON-BLANK BODY rather than trusting [typeOf], for the reason the Soul and Kuaishou
     * readers give: the numbering is not stable across releases and no type constant could be confirmed
     * statically. A blank body is the honest test -- an image or voice message has `strMsg` empty, so it
     * is refused by the same line that accepts text.
     */
    fun isText(msg: Any?): Boolean = !content(msg).isBlank()

    /** The message a host object HOLDS, found by TYPE rather than by field name.
     *
     * ============================== WHY BY TYPE ==============================
     *
     * `[DEX]` on 9.47.0:
     *
     *     LongPressMenuItemBinder.<init>(jy7.n, com.xingin.chatbase.bean.MsgUIData)
     *     LongPressMenuItemBinder  field e : com.xingin.chatbase.bean.MsgUIData
     *
     * The binder STORES the message it was constructed with, in a field whose obfuscated name (`e`) is
     * exactly the kind of name a release renames. The field's TYPE is not obfuscated though -- it is
     * the real class -- so matching on the type is what makes this survive a rename.
     *
     * Tried in order:
     *   1. a field whose DECLARED type is the measured message class;
     *   2. a field whose VALUE's runtime class is the measured message class;
     *   3. any field whose value [isMessage] accepts AND that carries readable data.
     *
     * Step 3 exists because a build could pass a subclass. It requires readable data (an id, a chat id
     * or a timestamp) so that a menu-entry wrapper -- which has none of those -- cannot be mistaken
     * for the message. That distinction is the whole reason the earlier attempts read an empty card:
     * they accepted the WRAPPER.
     *
     * @return the message, or null when [host] holds none.
     */
    fun messageOnHost(host: Any?): Any? {
        if (host == null) return null
        // The host may BE the message (a future build that hooks the message holder directly).
        if (isMessage(host) && hasReadableMessageData(host)) return host

        val wanted = loadHostClass(XiaohongshuMenuIdentity.MSG_UI_DATA)
            ?: loadHostClass(XiaohongshuMenuIdentity.MESSAGE_BEAN)

        var c: Class<*>? = host.javaClass
        var hops = 0
        var best: Any? = null
        while (c != null && c != Any::class.java && hops < 6) {
            try {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type.isPrimitive || f.type == String::class.java) continue
                    // (1) declared type matches the measured message class
                    if (wanted != null && f.type == wanted) {
                        f.isAccessible = true
                        val v = try {
                            f.get(host)
                        } catch (_: Throwable) {
                            null
                        }
                        if (v != null) return v
                    }
                    // (2)/(3) the VALUE decides
                    if (!isHostModel(f.type) && !Collection::class.java.isAssignableFrom(f.type)) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(host)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (wanted != null && wanted.isInstance(v) && hasReadableMessageData(v)) return v
                    if (isMessage(v) && hasReadableMessageData(v) && best == null) best = v
                }
            } catch (_: Throwable) {
                // skip an unreadable level
            }
            c = c.superclass
            hops++
        }
        return best
    }

    /**
     * The real message inside [o], or [o] itself when it already is one.
     *
     * ============================== WHY UNWRAPPING IS NEEDED HERE ==============================
     *
     * The item-binder hook fires correctly -- the device log proves it:
     *
     *     xhs: message captured from the binder (#1) -- ly7.c text='<no text>'
     *
     * ...and that line is also the bug. The argument is `ly7.c`, NOT the message: it is the menu ROW's
     * wrapper. `[DEX]` gives the chain exactly:
     *
     *     ly7.c extends jy7.l
     *     jy7.l declares ONE field, `a`
     *     LongPressMenuItemBinder.<init>(jy7.n, com.xingin.chatbase.bean.MsgUIData)
     *
     * so the `MsgUIData` the binder was constructed with is reachable one hop down, while `ly7.c` has
     * no text of its own -- which is why the capture logged `<no text>` and 分析 would have had nothing
     * to send.
     *
     * ORDER, and it matters: the host's REAL message class is looked for FIRST, by name, so the result
     * is the object the rest of the module expects (`msgId`, `chatId`, `creatTime`, `msgGeneralBean`)
     * rather than whatever anonymous payload happens to be nearest. Only then does it fall back to a
     * shape-based search, which keeps this working on a build that renames the wrapper.
     *
     * @return the message, or [o] unchanged when it already is one, or null when [o] is null.
     */
    fun unwrap(o: Any?): Any? {
        return resolve0(o)
    }

    /**
     * Turns ANY object a hook receives into an analysable message, or null.
     *
     * ============================== THE ORDER THAT MAKES A WRAPPER WORK ==============================
     *
     * THIS EXISTS BECAUSE TWO OF THIS MODULE'S OWN FIXES CONTRADICTED EACH OTHER, and the device log
     * shows both halves:
     *
     *     17:19:06  xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
     *     ...after isMessage was tightened to require real message data...
     *     (no capture line at all -- every chat row was rejected before it could be unwrapped)
     *
     * The chat list hands over `ql4.a`: a WRAPPER whose payload is the `MsgUIData` inside it. So:
     *
     *   * `isMessage` had to be tightened, or a menu entry (`ly7.c`) would be accepted as chat content;
     *   * but the tightened test REJECTS the wrapper, so the unwrap never ran and NOTHING was captured --
     *     the hook kept firing (the log proves that) while the history stayed empty.
     *
     * The resolution is order: UNWRAP FIRST, JUDGE THE RESULT. A wrapper is not required to look like a
     * message; it only has to CONTAIN one, and the unwrap searches for the measured message classes
     * inside any object regardless of what that object is. The strict test then runs on what was found.
     *
     * THIS IS THE ONLY ENTRY POINT A HOOK SHOULD CALL: `access.resolve(arg)` instead of
     * `if (access.isMessage(arg)) ...`.
     *
     * @return the message, or null when [o] neither is one nor holds one.
     */
    fun resolve(o: Any?): Any? {
        if (o == null) return null
        // Already a real message: keep it. This is the long-press path, where the host hands the
        // `MsgUIData` over directly.
        if (isMessage(o) && hasReadableMessageData(o)) return o
        // A wrapper: hand back the message inside it, if there is one.
        val found = resolve0(o)?.takeIf { hasMessageData(it) }
        // ============================== THE LAST RESORT: DUMP IT ==============================
        //
        // MEASURED, and this is the line that shows the unwrap is still not reaching the payload:
        //
        //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
        //     live: rejected a blank-text message for 625ed046000000001000c61b
        //
        // So `ql4.a` IS the chat row and the hook sees it, but nothing with text comes back out. The
        // wrapper's field is not a plain reference to a `MsgUIData` -- it may hold it behind a SUPPERCLASS
        // field, a nested holder, or an obfuscated type the traversal refuses to enter.
        //
        // Rather than guess a third time, the object's whole reachable structure is dumped ONCE per class
        // (guarded inside the dump). The graph prints every field, its declared type and its RUNTIME type,
        // which is the only reliable way to see where the real message sits.
        if (found == null || directText(found) == null) {
            dumpGraph(o, "chat row wrapper whose message could not be unwrapped")
        }
        return found
    }

    private fun resolve0(o: Any?): Any? {
        if (o == null) return null

        // ============================== WHY THE NAME SEARCH RUNS FIRST, NOT LAST ==============================
        //
        // MEASURED on the device -- the chat list's rows reach the universal hook as a WRAPPER:
        //
        //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
        //
        // `ql4.a` passes [isMessage] (it carries a chat id, which is why it looks message-like at all)
        // but has NO text: it is the chat row's wrapper, and the real `MsgUIData` -- the object with
        // `strMsg`, the one the menu path proves readable -- is inside it. Exactly the shape the menu
        // showed with `ly7.c` wrapping its payload.
        //
        // The previous version returned `o` itself as soon as it "had readable message data", which is
        // true of a wrapper too. That single early return is why the chat history stayed empty while the
        // long-press (which is handed the `MsgUIData` directly) worked: the wrapper satisfied the test
        // and the text-bearing object inside was never reached.
        //
        // So the measured message class is looked for FIRST, by name, and the "it already looks like a
        // message" shortcut is now LAST -- after every attempt to find something better has failed.
        //
        // PREFERENCE IS BY TEXT, not merely by class: of two candidates the one that actually carries
        // chat text is returned, because that is what the caller needs. That keeps this correct when a
        // wrapper holds several message-ish objects (a quoted reply beside the real body).

        // 1. the measured message classes, by name -- the exact object the module reads fields from
        var fallback: Any? = null
        for (name in listOf(XiaohongshuMenuIdentity.MSG_UI_DATA, XiaohongshuMenuIdentity.MESSAGE_BEAN)) {
            val cls = loadHostClass(name) ?: continue
            val hit = findInstanceOf(o, cls, 0) ?: continue
            if (directText(hit) != null) return hit
            if (fallback == null) fallback = hit
        }
        if (fallback != null) return fallback

        // 2. shape-based: any reachable value that is a message AND carries text
        findTextedMessageInside(o, 0)?.let { return it }

        // 3. any reachable value that is a message at all (a textless image message legitimately lands
        //    here -- it is a real message, it just has no text)
        findMessageInside(o, 0)?.let { return it }

        // 4. ============================== TEXT, WHEREVER IT IS ==============================
        //
        // MEASURED, and this is the case that kept the chat history empty:
        //
        //     xhs: UNIVERSAL hook captured a message -- ql4.a text='<no text>'
        //
        // `ql4.a` is the chat ROW, but it is not a `MsgUIData` and no `MsgUIData` was reachable from it
        // by the searches above -- so the wrapper came back with no text and the recorder dropped it.
        //
        // Rather than guess the wrapper's shape a third time, this step asks the only question that
        // actually matters to the caller: does the object hold the TEXT anywhere? `strMsg` and `hintMsg`
        // are the names the host's own `toString()` proved, so a deep scan for those two names finds the
        // payload whatever class wraps it.
        //
        // Depth 4 because a chat row measured two levels of wrapper before its data.
        findTextNearby(o, 0)?.let { return it }

        // 5. nothing better: hand back what we were given, so a caller can still try (and will report
        //    "no text" rather than crashing).
        return o
    }

    /**
     * The first object reachable from [o] that carries a MESSAGE BODY, found by [CONTENT_NAMES].
     *
     * ============================== WHY A DEEP TEXT SCAN IS THE RIGHT LAST STEP ==============================
     *
     * Every earlier step asks "is this a message object?". This one asks "does this hold the text?", and
     * that is a strictly easier question: the field names are KNOWN from the host's own `toString()`
     * (`strMsg` for the body, `hintMsg` for a hint), so any wrapper -- however many layers, whatever it
     * is called -- can be searched for them by name.
     *
     * This is what makes the chat list work. The long-press path never needed it, because the host hands
     * over a real `MsgUIData` there; the chat list hands over `ql4.a`, and this step is what reaches the
     * body inside it.
     *
     * It returns the OBJECT holding the text rather than the string, so the caller still gets the id and
     * timestamp from the same object and the history entry is complete.
     */
    private fun findTextNearby(o: Any, depth: Int): Any? {
        if (depth > 4) return null
        // The object itself may be the text holder.
        if (directText(o) != null && hasReadableMessageData(o)) return o
        for (v in reachableValues(o)) {
            if (directText(v) != null) return v
            findTextNearby(v, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * The first value reachable from [o] that is a message AND has text -- the preferred unwrap target.
     *
     * Separate from [findMessageInside] because preference is by TEXT: a wrapper can hold both the real
     * body and a quoted/reply payload, and only one of them is what the user pressed on.
     */
    private fun findTextedMessageInside(o: Any, depth: Int): Any? {
        if (depth > 3) return null
        for (v in reachableValues(o)) {
            if (isMessage(v) && directText(v) != null) return v
            findTextedMessageInside(v, depth + 1)?.let { return it }
        }
        return null
    }

    /** True when [o] exposes the data the module actually reads, i.e. it is not just a wrapper. */
    fun hasMessageData(o: Any?): Boolean {
        if (o == null) return false
        val n = o.javaClass.name
        // The measured message classes are accepted outright: they ARE the message, even mid-build when
        // a field has not been assigned yet.
        if (n == XiaohongshuMenuIdentity.MSG_UI_DATA || n == XiaohongshuMenuIdentity.MESSAGE_BEAN) return true
        return hasReadableMessageData(o)
    }

    private fun hasReadableMessageData(o: Any): Boolean =
        readFieldByName(o, "msgId") != null ||
            readFieldByName(o, "chatId") != null ||
            readFieldByName(o, "creatTime") != null

    /** The first instance of [type] reachable from [o] within [depth] levels, or null. */
    private fun findInstanceOf(o: Any, type: Class<*>, depth: Int): Any? {
        if (depth > 3) return null
        if (type.isInstance(o)) return o
        if (!isHostModel(o.javaClass)) return null
        for (v in reachableValues(o)) {
            if (type.isInstance(v)) return v
            findInstanceOf(v, type, depth + 1)?.let { return it }
        }
        return null
    }

    /** The first value reachable from [o] that [isMessage] accepts, or null. */
    private fun findMessageInside(o: Any, depth: Int): Any? {
        if (depth > 3) return null
        for (v in reachableValues(o)) {
            if (isMessage(v) && hasReadableMessageData(v)) return v
            findMessageInside(v, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * The non-trivial values [o] holds -- its fields (superclass chain included) and no-arg accessors.
     *
     * BOUNDED and FILTERED: only host model types and collections are followed, so the traversal
     * cannot descend into the framework objects every view-adjacent class holds. This runs inside the
     * host's bind callback, so an unbounded walk could stutter the menu.
     */
    private fun reachableValues(o: Any): List<Any> {
        val out = ArrayList<Any>(4)
        var c: Class<*>? = o.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 5) {
            try {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val t = f.type
                    if (t.isPrimitive || t == String::class.java) continue
                    if (!isHostModel(t) && !Collection::class.java.isAssignableFrom(t)) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(o)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (v is Collection<*>) {
                        v.firstOrNull()?.let { out.add(it) }
                    } else {
                        out.add(v)
                    }
                }
            } catch (_: Throwable) {
                // skip an unreadable level
            }
            c = c.superclass
            hops++
        }
        return out
    }

    /**
     * True when [t] is a type this host keeps its data in.
     *
     * `com.` covers the measured models (`com.xingin.chatbase.bean.*`), `sk4.` the menu-item models
     * found alive in the running process, and a name with NO DOT covers the default-package types this
     * host also uses (`jy7.o`, and the plain `o` the SOURCES diagnostic printed). The last case is the
     * one that made an earlier version give up: its filter required `com.`/`sk4.`, so it refused to
     * follow the field that actually holds the payload.
     */
    private fun isHostModel(t: Class<*>): Boolean {
        val n = t.name
        return n.startsWith("com.") || n.startsWith("sk4.") || n.startsWith("jy7.") ||
            n.startsWith("ly7.") ||
            // MEASURED: the CHAT list's row wrapper is `ql4.a`. Without a prefix here the traversal
            // refuses to descend into it, so the real `MsgUIData` inside is never reached -- the same
            // class of failure the `ly7.` prefix above was added to fix for the menu.
            n.startsWith("ql4.") ||
            !n.contains('.')
    }

    /** A host class by name, or null when this build does not ship it. */
    private fun loadHostClass(name: String): Class<*>? = try {
        Class.forName(name, false, resolver.classLoader())
    } catch (_: Throwable) {
        null
    }

    /**
     * Dumps EVERYTHING reachable from [o] -- used when a message was captured but read as empty.
     *
     * ============================== WHY THIS IS A DUMP AND NOT A SEARCH ==============================
     *
     * The item-binder hook now fires and captures an object, but its text reads empty:
     *
     *     xhs: message captured for 分析 -- ly7.c text='<no text>'
     *
     * `ly7.c` is NOT the message -- it is the menu ROW's wrapper. Two attempts to unwrap it from APK
     * analysis have now failed, for the same reason each time: the field types are decided at RUNTIME.
     * `[DEX]` says `jy7.l` declares a field `a` of type `jy7.o`, while the SOURCES diagnostic printed
     * the SAME slot as `a:o` -- a type in the DEFAULT package. Static analysis cannot reconcile those,
     * and guessing again would waste another round.
     *
     * So this prints, for the captured object, its whole reachable graph ONE level out plus the
     * superclass chain: every field name, its declared type, the RUNTIME class of its value, and for
     * a value that is a String, the string itself. Whatever the message really is, and whichever slot
     * holds it, its name and class appear in that output -- which turns the next change into a
     * one-line fix rather than a third guess.
     *
     * Bounded in every direction (5 fields deep, 30 entries) so a pathological object cannot flood the
     * log or stall the bind callback this runs inside.
     */
    fun dumpGraph(o: Any?, label: String) {
        if (o == null) {
            Log.w(TAG, "xhs GRAPH (" + label + "): null")
            return
        }
        val key = "graph:" + o.javaClass.name
        if (!logged.add(key)) return
        val sb = StringBuilder()
        sb.append("xhs GRAPH (").append(label).append("): ").append(o.javaClass.name).append('\n')
        var c: Class<*>? = o.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 5) {
            sb.append("  class ").append(c.name)
            sb.append(" extends ").append(c.superclass?.name ?: "-").append('\n')
            var n = 0
            try {
                for (f in c.declaredFields) {
                    if (n >= 30) break
                    if (Modifier.isStatic(f.modifiers)) continue
                    n++
                    sb.append("     .").append(f.name).append(" : ").append(f.type.simpleName)
                    // The VALUE is what matters: the declared type is a lie whenever the host uses an
                    // interface or a default-package type. Read it and print its real class.
                    val v = try {
                        f.isAccessible = true
                        f.get(o)
                    } catch (_: Throwable) {
                        null
                    }
                    if (v == null) {
                        sb.append(" = null\n")
                    } else {
                        sb.append(" = ").append(v.javaClass.name)
                        when (v) {
                            is String -> if (v.isNotEmpty()) sb.append(" '").append(v.take(40)).append('\'')
                            is Number, is Boolean -> sb.append(' ').append(v)
                            is Collection<*> -> {
                                sb.append(" size=").append(v.size)
                                v.firstOrNull()?.let { sb.append(" first=").append(it.javaClass.name) }
                            }
                            else -> {
                                // One more level: name the first two host-model fields of the value, so
                                // a nested container is identifiable without another dump.
                                sb.append(" {").append(summariseFields(v, 4)).append('}')
                            }
                        }
                        sb.append('\n')
                    }
                }
            } catch (_: Throwable) {
                sb.append("     <unreadable>\n")
            }
            c = c.superclass
            hops++
        }
        Log.w(TAG, sb.toString())
    }

    /** `name:Type=value` for up to [max] non-static fields of [o], for the nested summary. */
    private fun summariseFields(o: Any, max: Int): String {
        val parts = ArrayList<String>(max)
        var c: Class<*>? = o.javaClass
        var hops = 0
        while (c != null && c != Any::class.java && hops < 3 && parts.size < max) {
            try {
                for (f in c.declaredFields) {
                    if (parts.size >= max) break
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type.isPrimitive || f.type == String::class.java) continue
                    f.isAccessible = true
                    val v = try {
                        f.get(o)
                    } catch (_: Throwable) {
                        null
                    }
                    parts.add(f.name + ":" + (v?.javaClass?.simpleName ?: f.type.simpleName))
                }
            } catch (_: Throwable) {
                // skip
            }
            c = c.superclass
            hops++
        }
        return parts.joinToString(",")
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * A short description of the accessors [o] exposes that look like message data.
     *
     * Used by the probe when NO argument was accepted, and by the reader's own failure log. It lists
     * the no-argument GETTERS and the non-static fields whose names suggest message data, because that
     * is precisely what a person needs to extend the candidate lists below.
     *
     * Bounded to 24 entries: a host model can expose hundreds of accessors, and a dump that long is
     * unreadable in a log file.
     */
    fun describeAccessors(o: Any?): String {
        if (o == null) return ""
        val cls = o.javaClass
        val sb = StringBuilder()
        try {
            val keys = Regex("(content|text|body|msg|message|id|time|send|from|to|user|talk|conv|session)")
            val getters = cls.methods
                .filter { it.parameterCount == 0 && it.name != "getClass" }
                .filter { keys.containsMatchIn(it.name) }
                .map { it.name }
                .distinct()
                .take(24)
            val fields = cls.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .filter { keys.containsMatchIn(it) }
                .distinct()
                .take(24)
            sb.append("\n        getters: ").append(if (getters.isEmpty()) "<none>" else getters.joinToString(","))
            sb.append("\n        fields : ").append(if (fields.isEmpty()) "<none>" else fields.joinToString(","))
        } catch (t: Throwable) {
            sb.append("\n        <accessors unreadable: ").append(t.javaClass.simpleName).append('>')
        }
        return sb.toString()
    }

    /** Prints what the reader can see in [msg]. Called once per long-press by the hook. */
    fun probe(msg: Any?) {
        if (msg == null) {
            Log.w(TAG, "xhs PROBE: no message object")
            return
        }
        Log.w(
            TAG,
            "xhs PROBE: " + msg.javaClass.name + describeAccessors(msg) +
                "\n        text=" + text(msg) + " send=" + isSend(msg) +
                " id=" + id(msg) + " time=" + time(msg) + " talker=" + talker(msg),
        )
    }

    // ------------------------------------------------------------------ binding

    /** Reads one fact, binding the accessor on first use and caching it afterwards. */
    private fun read(msg: Any?, fact: Fact): Any? {
        if (msg == null) return null
        val cls = msg.javaClass
        val perClass = cache.getOrPut(cls) { Collections.synchronizedMap(HashMap()) }
        val acc = perClass.getOrPut(fact) { bind(cls, fact) }
        if (acc is Accessor.None) return null
        return (acc as? Accessor.Found)?.let { a ->
            try {
                a.read(msg)
            } catch (t: Throwable) {
                note(
                    "read-failed:" + cls.name + "#" + fact,
                    "xhs: reading " + fact + " via " + a.name + " on " + cls.name + " threw: " + t.message,
                )
                null
            }
        }
    }

    /**
     * Finds the accessor for one fact on one class.
     *
     * Order: every candidate NAME as a no-arg getter, then every candidate name as a field. The
     * candidate order is significant and documented on each list.
     */
    private fun bind(cls: Class<*>, fact: Fact): Accessor {
        val names = candidatesFor(fact)
        for (n in names) {
            val m = findNoArgGetter(cls, n)
            if (m != null && m.returnType != Void.TYPE) {
                Log.i(TAG, "xhs: bound " + fact + " -> " + cls.simpleName + "#" + m.name + "() " + m.returnType.simpleName)
                return Accessor.Found(m.name) { t -> m.invoke(t) }
            }
        }
        for (n in names) {
            val f = findField(cls, n)
            if (f != null) {
                Log.i(TAG, "xhs: bound " + fact + " -> " + cls.simpleName + "." + f.name + " " + f.type.simpleName)
                return Accessor.Found(f.name) { t -> f.get(t) }
            }
        }
        // Reported once per class+fact. This is the line that says a name is simply not present, which
        // is the honest failure -- as opposed to a wrong value read from a shape-matched stranger.
        note(
            "unbound:" + cls.name + "#" + fact,
            "xhs: NO accessor for " + fact + " on " + cls.name + "; tried names " + names,
        )
        return Accessor.None
    }

    /**
     * The candidate names per fact, each list ordered by evidence.
     *
     * ============================== ALL OF THESE ARE MEASURED, NOT GUESSED ==============================
     *
     * The authoritative source is the host's own `toString()`, printed by the probe into the module log:
     *
     *     MsgUIData(msgUUID=3ffe0a50-5bdd-4a23-b00f-f0d28e474a3d,
     *               msgId=5f89bad9000000000100a893.625ed046000000001000c61b.1eab77af945b578,
     *               storeId=12, creatTime=1790409465678, showTime=15:57, msgType=1,
     *               senderId=5f89bad9000000000100a893, receiverId=625ed046000000001000c61b,
     *               chatId=625ed046000000001000c61b, hintMsg=ok, strMsg=\u6211, ...)
     *
     * Every name below appears there verbatim. The earlier lists were built from the bean classes and
     * were wrong -- `content` matched nothing, so the reader reported an empty message for a message it
     * had captured perfectly.
     *
     * CONTENT -- see [CONTENT_NAMES]; `strMsg` is the body.
     *
     * ID -- `msgId` is the wire id and is what the history store keys on. `storeId` is the host's local
     * row number and is NOT used: it is only unique within one chat, so keying on it would merge two
     * conversations' messages.
     *
     * TIME -- `creatTime` (the host's own spelling, missing an `e`). NOT `impressionTime`, which is
     * about read receipts, not when the message was sent.
     *
     * TALKER -- `chatId`, the conversation. `localChatId` is `chatId@senderId` and is a DIFFERENT
     * key (it identifies a per-peer view of the chat), so `chatId` comes first:
     * `625ed046000000001000c61b` rather than `625ed046000000001000c61b@5f89bad9...`.
     *
     * SEND -- not present as a boolean on this model. The measured fields that reveal the side are
     * `senderId` (compared against the account's own id) and the `msgType`. [isSend] therefore reports
     * the host's own answer when it has one and otherwise false, which is the same neutral default the
     * other hosts use -- inventing a side would mis-attribute the conversation in the prompt.
     */
    private fun candidatesFor(fact: Fact): List<String> = when (fact) {
        Fact.CONTENT -> CONTENT_NAMES
        Fact.SEND -> listOf("isSend", "getIsSend", "isSelf", "getIsSelf", "fromMe", "getFromMe", "sendType", "getSendType")
        Fact.ID -> listOf("msgId", "getMsgId", "msgUUID", "getMsgUUID", "id", "getId", "serverId", "getServerId")
        Fact.TIME -> listOf("creatTime", "getCreatTime", "createTime", "getCreateTime", "time", "getTime", "showTime", "getShowTime")
        Fact.TALKER -> listOf("chatId", "getChatId", "talker", "getTalker", "localChatId", "getLocalChatId", "sessionId", "getSessionId")
    }

    /** A no-arg method named [name] (or `get`+name, capitalised), accessible. */
    private fun findNoArgGetter(cls: Class<*>, name: String): Method? {
        val wanted = listOf(
            name,
            if (name.startsWith("get") || name.startsWith("is")) name
            else "get" + name.replaceFirstChar { it.uppercase() },
            if (name.startsWith("get") || name.startsWith("is")) name
            else "is" + name.replaceFirstChar { it.uppercase() },
        ).distinct()
        for (w in wanted) {
            try {
                val m = cls.getMethod(w)
                if (m.parameterCount == 0 && Modifier.isPublic(m.modifiers)) {
                    m.isAccessible = true
                    return m
                }
            } catch (_: Throwable) {
                // not present under this name; try the next spelling
            }
        }
        return null
    }

    /** A field named [name] on [cls] or any superclass, made accessible. */
    private fun findField(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(name)
                if (!Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    return f
                }
            } catch (_: Throwable) {
                // absent here; walk up
            }
            c = c.superclass
        }
        return null
    }

    private fun note(key: String, message: String) {
        if (logged.add(key)) Log.w(TAG, message)
    }

    /** A bound accessor, or the record that no name matched. */
    private sealed class Accessor {
        abstract val name: String
        abstract fun read(target: Any): Any?

        class Found(override val name: String, private val fn: (Any) -> Any?) : Accessor() {
            override fun read(target: Any): Any? = fn(target)
        }

        object None : Accessor() {
            override val name: String get() = "<none>"
            override fun read(target: Any): Any? = null
        }
    }

    private companion object {
        const val TAG = "LuoLiao"

        /**
         * The field names that hold a message's TEXT.
         *
         * ============================== MEASURED FROM THE HOST'S OWN toString() ==============================
         *
         * Earlier versions guessed from the bean classes and got this wrong twice, because the real text
         * does not live in a nested bean at all. The device log settled it: `AnalysisController`'s own
         * probe printed `MsgUIData`'s `toString()`, which lists every field with its value:
         *
         *     MsgUIData(msgUUID=3ffe0a50-..., msgId=5f89...b578, creatTime=1790409465678,
         *               msgType=1, senderId=5f89bad9..., chatId=625ed046...,
         *               hintMsg=ok, strMsg=\u6211, imageMsg=MsgImageBean(...), voiceMsg=...)
         *
         * So the text fields are `strMsg` (the message body) and `hintMsg` (a hint/system notice).
         * NEITHER is named `content`, which is why every earlier lookup returned empty even though the
         * message had been captured perfectly.
         *
         * ORDER: `strMsg` first because it is the real body. `hintMsg` second -- it carries a system
         * notice ("ok" in the measured sample, and things like a recall notice), which is still text the
         * model can use and is the only text a non-plain message has.
         *
         * The other shapes are a message with NO text (`imageMsg`, `voiceMsg`, `multimsg`, `dslBean`,
         * `dynamicCard`), and they are deliberately absent: returning an image URL as chat content would
         * be a plausible-looking WRONG answer, worse than reporting "\u8be5\u6d88\u606f\u65e0\u6587\u672c".
         */
        val CONTENT_NAMES = listOf(
            "strMsg",       // the message body (measured)
            "hintMsg",      // a system hint / notice
            "content",      // the name the nested beans use
            "msgContent",   // a quoted payload
            "text",
            "body",
        )

        /**
         * The nested beans that hold the content, tried in order after the direct names.
         *
         * MEASURED from `com.xingin.chatbase.bean.MsgUIData`'s field list:
         *
         *     msgGeneralBean   MsgGeneralBean   <- an ordinary text message's content
         *     msgContentBean   MsgContentBean   <- a quoted/replied payload's content
         *
         * Kept AFTER the direct names: on the measured build `strMsg` already carries the body, so these
         * are only reached for a message shape that stores its text in a nested bean instead.
         */
        val NESTED_CONTENT_HOLDERS = listOf("msgGeneralBean", "msgContentBean")
    }
}

/**
 * The pure part of Xiaohongshu message reading: the rules a JVM unit test can pin without a device.
 *
 * Same reason [XiaohongshuMenuLogic] exists -- a wrong rule here produces a plausible-looking WRONG
 * answer (a time 1000x off lands the message in 1970), which is far harder to notice than a crash.
 */
internal object XiaohongshuReadLogic {

    /**
     * A plausible epoch-millisecond window, used to tell seconds from milliseconds.
     *
     * WHY THIS IS NEEDED AND WHY THESE BOUNDS: IM models variously store a timestamp as SECONDS
     * (10 digits, e.g. 1767225600) or MILLISECONDS (13 digits, e.g. 1767225600000). Both are plain
     * `long`s, so nothing in the type says which. Multiplying a millisecond value by 1000 overflows
     * into a date thousands of years ahead; failing to multiply a seconds value dates the message to
     * 1970 and it sorts to the very bottom of the history.
     *
     * The window runs from 2001-09-09 to 2286-11-20 -- the full signed-64-bit range a millisecond
     * timestamp cannot exceed, narrowed at the bottom so that any value large enough to be a real
     * millisecond timestamp is treated as one. A seconds value (about 1.7e9 today) falls far below it;
     * a millisecond value (about 1.7e12) falls inside it. The two ranges do not overlap, which is what
     * makes the test decisive rather than a guess.
     */
    const val MIN_PLAUSIBLE_MS = 1_000_000_000_000L     // 2001-09-09

    /**
     * Normalises a raw timestamp to milliseconds.
     *
     * Returns 0 for a non-positive value, which the module already treats as "no timestamp" -- the
     * same answer WeChat and QQ give -- so a caller testing `time != 0` behaves identically on every
     * host.
     */
    fun normaliseTime(raw: Long): Long = when {
        raw <= 0L -> 0L
        raw >= MIN_PLAUSIBLE_MS -> raw          // already milliseconds
        else -> raw * 1000L                     // seconds
    }
}
