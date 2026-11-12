package com.luoliaoo.wechat.hook

import android.view.View
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Message field access for QQ (com.tencent.mobileqq 9.3.65), sibling of [MsgAccess],
 * [DouyinMsgAccess], [SoulMsgAccess] and [KuaishouMsgAccess].
 *
 * WHY A FIFTH READER INSTEAD OF A BRANCH
 * The five hosts agree only on the SHAPE of the answer (content / talker / isSend / type / time / id).
 * WeChat reads stable FIELD names on an obfuscated class; Douyin reads ordinary getters on an
 * un-obfuscated class; Soul reads its own `cn.soulapp.imlib` library; Kuaishou a self-developed IM SDK.
 * QQ is a SIXTH shape and the most layered one: the message is an NT `MsgRecord` whose TEXT IS NOT ON
 * THE RECORD AT ALL but inside a typed element inside an ArrayList. Sharing the caller while keeping
 * the reader separate is what stops one host's assumption from silently breaking another host's history.
 *
 * ==================== EVERY FACT BELOW WAS MEASURED, NOT RECALLED ======================
 * Sources, all produced for this task and kept under `_tools/qq/`:
 *   `out/t14_nt_model.txt`        MsgRecord / MsgElement / every element class, full member dump
 *   `out/t14_ui_item.txt`         AIOMsgItem and its 30 subclasses
 *   `out/t14_chain.txt`           the adapter -> holder -> position -> item chain
 *   `out/t14_msgconst_values.txt` MsgConstant's numeric values, decoded from dex static_values
 *   `out/t14_dis_*.txt`           bytecode disassembly of the host's own accessors
 *   `PPT` (probe scripts)         `qq_t14_probe{,2,3,4,5,6}.py`, `qq_t14_static_vals.py`
 *
 * The five facts this class is built on:
 *
 *  1. `com.tencent.qqnt.kernel.nativeinterface.MsgRecord` [classes.dex] -- 66 fields / 69 methods.
 *     `elements : java.util.ArrayList` and NO text field. Body lives in the elements.
 *  2. `com.tencent.mobileqq.aio.msg.AIOMsgItem` [classes3.dex] is what the UI binds, and it CARRIES the
 *     record: `getMsgRecord()` disassembles to `iget-object AIOMsgItem->e : MsgRecord; return-object`
 *     (verified, `out/t14_dis_item.txt`). So the UI path is NT, and this reader is the right one.
 *  3. `com.tencent.qqnt.kernel.nativeinterface.MsgConstant` [classes11.dex] holds 532 real `static final
 *     int` constants; `KELEMTYPETEXT = 1`, `KMSGTYPEUNKNOWN = 0` etc. decoded with a known-answer
 *     self-check (`KCHATTYPEC2C == 1`, `KCHATTYPEGROUP == 2`).
 *  4. The list is `com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.a`,
 *     a `RecyclerView.Adapter` whose `onCreateViewHolder` returns a class derived from
 *     `RecyclerView$ViewHolder` -- both anchors are framework types, so neither can be obfuscated away.
 *  5. QQ's OWN "is this mine" rule is a `sendType` short-circuit, disassembled and reproduced.
 *
 * ============ WHY THE ELEMENT LIST IS READ FIRST AND THE RECORD NEVER GUESSED ============
 * `MsgRecord.getMsgType()` is a message-level kind (TEXT=0/MIX=2/GRAYTIPS=5/GIFTPTT...) while
 * `MsgElement.getElementType()` is the element-level kind. They are DIFFERENT enums and a message can
 * hold several elements at once (a text plus an @ mention, a reply plus its quote). So:
 *   - the BODY is taken from the first element that is a real `TextElement` with non-empty content;
 *   - the PLACEHOLDER kind is taken from that same element when there is no text, and otherwise from
 *     the FIRST element of the list -- reported by [lastReason] so the log says which happened.
 * Neither number is ever used to answer "is this text": that comes from the `TextElement` OBJECT.
 *
 * ============================== QFIX HOT-PATCH WARNING ==================================
 * QQ ships Tencent's QFix. Every business method begins with an
 * `IPatchRedirector.hasPatch(S)` / `redirect(S, Object)` prologue, visible in the disassembly above.
 * [probeClasses] reports the patch state so the first device run can tell a QFix-patched build from a
 * clean one instead of silently reading the wrong branch.
 *
 * DEGRADING, NEVER GUESSING (the project's standing rule): when something cannot be read, this class
 * returns the NEUTRAL value ("" / 0L / false / -1) and LOGS which branch it took. It never invents a
 * plausible id, never falls back to a raw payload, and never lets a guessed class name make the
 * pipeline merely LOOK alive. An idle pipeline that says why beats a fake success.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class QQMsgAccess(
    /**
     * The HOST classloader, taken from [Resolver] and never from a constructor argument.
     *
     * 必看.md: resolving host classes through the wrong loader silently produces a "class not found"
     * that looks like a missing feature. A clone runs the host under `DelegateLastClassLoader`, where
     * the module's own loader resolves the SAME name to a DIFFERENT Class object -- so a hook attaches
     * to a method nobody calls and the only missing evidence is the `fired` line.
     *
     * ############################################################################################
     * # CONSTRAINT: `resolver.classLoader()` is the ONLY method of Resolver this class may touch.   #
     * # `points` / `resolve()` / `msgInfoClass()` are all WeChat-only and return null inside QQ,     #
     * # which reads in the log like "the QQ class does not exist" while it plainly does.             #
     * ############################################################################################
     */
    private val resolver: Resolver,
) {

    private companion object {
        const val TAG = "LuoLiao"

        /** The NT record. See the header: this is what AIOMsgItem wraps. */
        const val MSG_CLASS = QQEnvelope.MSG_CLASS

        /** The UI item that wraps it. Used by [messageFromView] via its getter. */
        const val AIO_ITEM_CLASS = QQEnvelope.AIO_ITEM_CLASS

        /** QFix's patch dispatcher; only for the diagnostic. */
        const val QFIX_CLASS = "com.tencent.mobileqq.qfix.redirect.IPatchRedirector"

        /** The framework RecyclerView, identified by NAME so no androidx type reference is needed. */
        const val RV_CLASS = "androidx.recyclerview.widget.RecyclerView"

        /** The framework ViewHolder, same reasoning. */
        const val RV_HOLDER_CLASS = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"

        /**
         * The namespace every QQ chat-list class lives in. VERIFIED: the list adapter is
         * `com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.a`, a direct
         * subclass of `androidx.recyclerview.widget.RecyclerView$Adapter` with
         * `onCreateViewHolder(Landroid/view/ViewGroup;I)Landroidx/recyclerview/widget/RecyclerView$ViewHolder;`
         * (dumped in `out/t14_chain.txt` and `out/t14_anchor_adapter.txt`).
         *
         * Used by [looksLikeMessageList] as the structural test that separates the MESSAGE list from
         * QQ's four other RecyclerViews on the same screen (chat list, @-member list, photo panel,
         * emoji panel).
         */
        const val MSGLIST_PACKAGE = "com.tencent.aio.part.root.panel.content.firstLevel.msglist"

        /** The exact adapter class, for logging and as a future direct hook target. */
        const val ADAPTER_CLASS = MSGLIST_PACKAGE + ".mvx.vb.ui.adapter.a"

        /**
         * Getter names on `AIOMsgItem` that hand back the NT record.
         *
         * ONLY the real getter is listed. An earlier revision also tried the obfuscated `o()`, and
         * that entry had to be removed because it is not what it looks like -- it FAILS DANGEROUSLY
         * rather than safely:
         *
         *   `o()` is declared STATIC. `Method.invoke(instance)` on a static method DISCARDS the
         *   receiver and returns the static field `i1`, which `<clinit>` initialises to
         *   `new MsgRecord()` -- Kotlin's default-parameter placeholder, a shared EMPTY record.
         *
         * So a miss on `getMsgRecord()` did not surface as null. It returned a well-formed but empty
         * MsgRecord, and every type check downstream still passed because the type IS MsgRecord.
         * Two device-visible consequences, both silent:
         *   * analysis shows blank message text;
         *   * history keys collapse -- an empty record's `getMsgId()` is 0 for every message, so
         *     every line in a conversation maps to the same key `talker#0` and all but one is dropped.
         *
         * A fallback that cannot fail loudly is worse than no fallback, so the list holds one name.
         * If a future QQ renames the getter, the miss is now visible as a null record instead of
         * being masked by an object that answers every question with a default.
         */
        val ITEM_MSG_GETTERS = listOf("getMsgRecord")

        /**
         * Getter names on `AIOMsgItem` for the conversation identifier.
         *
         * `D()Lcom/tencent/qqnt/kernelpublic/nativeinterface/Contact;` is verified on the item and
         * `Contact` carries exactly `chatType` / `peerUid` / `guildId`, so this is the item-level route
         * to the session when the record's own fields are not enough.
         */
        val ITEM_CONTACT_GETTERS = listOf("D", "getContact")

        /** Field name on `MsgRecord`: the element list. Verified as `java.util.ArrayList`. */
        const val FIELD_ELEMENTS = "elements"

        /** Field name on `MsgElement`: the kind number. */
        const val FIELD_ELEMENT_TYPE = "elementType"

        /** Element getters, tried in order, for the FIRST element's kind. */
        val TEXT_ELEMENT_GETTERS = listOf("getTextElement")

        /** Cap on distinct diagnostic lines -- by TYPE, not by event (必看.md's dedup rule). */
        const val DIAG_LIMIT = 24

        /** A SEPARATE budget for the binding lines, so they can never consume the diagnostic budget. */
        const val BIND_LIMIT = 24

        /** Maximum ViewParent hops when walking a view up to its row. */
        const val MAX_PARENT_HOPS = 30
    }

    /**
     * Diagnostic state, deduplicated BY NAME rather than by a counter.
     *
     * 必看.md: a count-based cap was consumed by the conversation list before the chat page ever bound,
     * so the one thing the diagnostic existed to reveal was never printed. A Set of names means the
     * conversation list contributes ONE line however many rows it binds, and opening a chat is
     * therefore guaranteed to add the chat's types.
     */
    private val seenNotes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    private val seenTypes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** Binding-diagnostic keys; see [noteBind] for why this is a third, separate budget. */
    private val seenBinds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    @Volatile
    private var probed = false

    @Volatile
    private var classesProbed = false

    // ---------------------------------------------------------------- resolved reflections
    //
    // ================== EVERY REFLECTIVE CALL GOES THROUGH bindOnce() =====================
    // 必看.md records the exact failure this guards: when resolution and invocation are written as two
    // separate paths, a resolver that reports 100% success on every call can still have failed on the
    // FIRST call -- the object it looked up was never cached, so the invocation silently found nothing
    // and the host looked like it had no such member. The symptom is a pipeline that works "sometimes".
    //
    // The rule ENFORCED HERE is therefore: this class holds NO `Method`/`Field` that a call site obtained
    // for itself. Every one of them is stored by [bindOnce] / [bindFieldOnce], which resolve ONCE, LOG
    // both the hit and every miss, and cache ONLY a success. A miss is RETRIED on the next call (a QQ
    // update, a lazily-loaded class, or an early process start can make it succeed later) but is logged
    // at most once per key, so a failure that persists is visible in the log exactly once instead of
    // flooding it or, worse, vanishing.
    //
    // The caches are INDEXED arrays rather than one `var` per member, so "which lookups did this class
    // actually make" is one readable list and the FIRST-CALL path is the same code as every later call.

    private object Slot {
        const val ELEMENTS_GETTER = 0
        const val ELEMENT_TYPE_GETTER = 1
        const val TEXT_ELEMENT_GETTER = 2
        const val TEXT_CONTENT_GETTER = 3
        const val COUNT = 4
    }

    private val accessorCache = arrayOfNulls<Accessor>(Slot.COUNT)

    @Volatile
    private var elementsFieldAcc: Accessor? = null

    @Volatile
    private var elementTypeFieldAcc: Accessor? = null

    @Volatile
    private var msgClass: Class<*>? = null

    @Volatile
    private var msgClassTried = false

    /** True once the `getTextElement` route has been reported on a REAL element. */
    @Volatile
    private var textRouteReported = false

    /** Cached so the host runtime is walked once, not on every message. */
    @Volatile
    private var cachedMyUid: String? = null

    /** Which branch [content] took on the last call, for the log. Read by [isText]. */
    @Volatile
    var lastReason: String = QQEnvelope.Reason.NO_BODY
        private set

    // ------------------------------------------------------------------ probe

    /**
     * One-shot runtime probe: dumps what the reader ACTUALLY sees on a real object.
     *
     * This is the deliverable the first device run needs. Static analysis proves the SHAPE; it does not
     * prove that the object handed to a hook is exactly that class, nor that QFix has not redirected
     * the getters. The probe prints the runtime class, the whole hierarchy, every accessor this reader
     * depends on, and -- side by side -- the reader's own verdicts, so one long-press in QQ settles
     * every open question at once.
     */
    fun probe(msg: Any?) {
        if (probed) return
        probed = true
        // A null message is a real possibility: a UI hook can fire before the item is bound. The probe
        // must still report -- silently returning would look identical to "the probe was never called".
        if (msg == null) {
            Log.i(TAG, "QQ probe: called with a NULL message (the hook fired before the item was bound)")
            return
        }
        val sb = StringBuilder()
        val unwrapped = unwrap(msg)
        sb.append("=== QQ message probe runtime=").append(msg.javaClass.name)
        sb.append(" unwrapped=").append(unwrapped?.javaClass?.name ?: "<none>").append(" ===\n")
        sb.append("  loader=").append(loaderName()).append('\n')
        sb.append("  isMessage=").append(isMessage(msg)).append('\n')

        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            sb.append("  [").append(depth).append("] ").append(p.name).append('\n')
            p = p.superclass
            depth++
        }

        val rec = unwrapped
        if (rec != null) {
            sb.append("  --- MsgRecord accessors as present on THIS object ---\n")
            for (n in listOf(
                "getMsgId", "getMsgSeq", "getMsgTime", "getTimeStamp", "getMsgType",
                "getSubMsgType", "getChatType", "getSendType", "getSendStatus", "getNameType",
                "getSenderUin", "getSenderUid", "getPeerUin", "getPeerUid", "getPeerName",
                "getSendMemberName", "getSendNickName", "getSendRemarkName", "getElements",
            )) {
                sb.append("    ").append(n).append("() = ")
                    .append(shorten(stringify(callAny(rec, n))))
                    .append('\n')
            }

            sb.append("  --- elements ---\n")
            val els = elementsOf(rec)
            sb.append("    count=").append(els?.size ?: -1).append('\n')
            els?.take(4)?.forEachIndexed { i, e0 ->
                // `List<*>` yields `Any?` elements; narrow once here so the helpers below can keep
                // their non-null `Any` signatures instead of every caller re-checking. A null element
                // is reported BY NAME rather than skipped, so the probe log cannot silently show fewer
                // items than the list really holds (必看.md: a diagnostic that hides its own gaps
                // reads like a healthy pipeline).
                val e = e0
                if (e == null) {
                    sb.append("    [").append(i).append("] <null element>\n")
                    return@forEachIndexed
                }
                sb.append("    [").append(i).append("] class=").append(e.javaClass.name)
                    .append(" elementType=").append(elementTypeOf(e))
                    .append(" textLen=").append(readTextElement(e)?.length ?: -1)
                    .append('\n')
            }

            sb.append("  --- identity / time ---\n")
            val st = callInt(rec, "getSendType")
            sb.append("    sendType=").append(st)
                .append(" isSelfFromSendType=").append(QQEnvelope.isSelfFromSendType(st))
                .append('\n')
            val chatType = chatTypeOf(rec)
            sb.append("    chatType=").append(chatType)
                .append(" isGroup=").append(QQEnvelope.isGroupChatType(chatType)).append('\n')
            val mt = callLong(rec, "getMsgTime")
            val ts = callLong(rec, "getTimeStamp")
            val picked = QQEnvelope.pickTime(mt, ts)
            sb.append("    msgTime=").append(mt).append(" timeStamp=").append(ts)
                .append(" -> ").append(picked.first).append(" ms branch=").append(picked.second)
                .append('\n')
            sb.append("    System.currentTimeMillis()=").append(System.currentTimeMillis()).append('\n')

            sb.append("  --- reader verdicts ---\n")
            sb.append("    content=").append(shorten(content(msg)))
                .append(" reason=").append(lastReason).append('\n')
            sb.append("    talker=").append(talker(msg)).append('\n')
            sb.append("    chatName=").append(chatName(msg)).append('\n')
            sb.append("    isSend=").append(isSend(msg)).append('\n')
            sb.append("    msgType=").append(typeOf(msg)).append('\n')
            sb.append("    msgId=").append(msgId(msg)).append('\n')
            sb.append("    createTime(ms)=").append(createTime(msg)).append('\n')
            sb.append("    isText=").append(isText(msg))
                .append(" isRecalled=").append(isRecalled(msg)).append('\n')
            sb.append("    senderName=").append(senderName(msg))
                .append(" senderUin=").append(senderUin(msg)).append('\n')
        } else {
            sb.append("  !! unwrap() found no MsgRecord -- this object is not a QQ NT message.\n")
        }
        Log.i(TAG, sb.toString())
    }

    /** Reports the QFix patch state of the reader's carrier class. Callable from the install path. */
    fun probeClasses() {
        if (classesProbed) return
        classesProbed = true
        Log.i(
            TAG,
            "QQ classes: " + MSG_CLASS + " loadable=" + loadable(MSG_CLASS) +
                " | " + AIO_ITEM_CLASS + " loadable=" + loadable(AIO_ITEM_CLASS) +
                " | QFix " + QFIX_CLASS + " present=" + loadable(QFIX_CLASS) + " (QQ ships Tencent " +
                "QFix: a patched method body is replaced through IPatchRedirector.hasPatch/redirect, " +
                "which the disassembly of AIOMsgItem.getMsgRecord shows at [0002]-[0011])",
        )
        Log.i(
            TAG,
            "QQ diagnostic getters to watch: getMsgRecord/getElements (body route), " +
                "getSendType (direction), getPeerUid/chatType (conversation) -- the probe prints all " +
                "of them on the first long-press",
        )
    }

    private fun loadable(name: String): Boolean = try {
        Class.forName(name, false, loader()) != null
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------ the ONE accessor path

    /**
     * A resolved member PLUS the single call that invokes it.
     *
     * ============== WHY RESOLUTION AND INVOCATION LIVE IN THE SAME OBJECT ==============
     * 必看.md records the failure precisely: when "resolve the member" and "call the member" are two
     * separate paths, a resolver can report 100% success on EVERY call while the invocation silently
     * finds nothing, because the object it looked up was never the one it later invoked. The symptom is
     * a feature that works "sometimes" and a log that never names a failure.
     *
     * So a slot here does not hold a `Method` that a call site then `.invoke()`s. It holds a callable
     * that ALREADY CLOSED OVER the exact member bound at resolution time, and [bindOnce] is the only
     * thing that produces one. There is therefore no way to resolve and then invoke two different
     * members, and no way to invoke without having resolved through the logged path.
     */
    private class Accessor(val member: java.lang.reflect.Member, private val call: (Any) -> Any?) {
        fun invoke(target: Any): Any? = call(target)
        val name: String get() = member.name
    }

    /**
     * Resolves a no-arg getter ONCE and returns an [Accessor] that calls exactly that member.
     *
     * `slot` is the cache index; `key` names the diagnostic lines so a miss is reported once per key
     * rather than once per call; `owner` is the runtime class to start from, so a subclass is handled.
     *
     * Returns null on a miss and the miss is LOGGED -- an unresolved accessor must never be silent,
     * because a silent null is exactly what makes a broken pipeline look like an idle one.
     *
     * THE FIRST CALL IS THE ONE THAT MATTERS. There is no separate "warm-up" path: this same method runs
     * on call 1 and on call 10_000, so a first-call failure cannot be masked by a later success. A miss
     * is RETRIED on the next call, which is what lets a class that loaded late recover.
     */
    private fun bindOnce(slot: Int, key: String, owner: Class<*>?, names: List<String>): Accessor? {
        accessorCache[slot]?.let { return it }
        if (owner == null) {
            noteBind(
                "bind-null-owner:" + key,
                "QQ: cannot bind $key -- no owner class (the host class was not loadable through the " +
                    "host classloader); this reader reports NEUTRAL values until that is fixed",
            )
            return null
        }
        for (n in names) {
            val m = findNoArg(owner, n)
            if (m != null) {
                val acc = Accessor(m) { target ->
                    try {
                        m.invoke(target)
                    } catch (_: Throwable) {
                        // A member that resolves but throws when called is reported by the CALLER's own
                        // degraded-value log; swallowing it here keeps the reader from crashing inside
                        // a host hook, and the miss is still visible because the caller sees null.
                        null
                    }
                }
                accessorCache[slot] = acc
                noteBind("bind-ok:" + key, "QQ: bound $key -> " + owner.name + "." + n + "()")
                return acc
            }
            noteBind(
                "bind-miss:" + key + ":" + n,
                "QQ: candidate " + owner.name + "." + n + "() NOT FOUND -- " +
                    (if (names.size > 1) "trying the next candidate" else "no candidate left"),
            )
        }
        noteBind(
            "bind-fail:" + key,
            "QQ: NO candidate for $key matched on " + owner.name +
                " (tried " + names.joinToString("/") + ") -- the degraded value is returned and " +
                "logged; this is a HOST CHANGE, not an empty conversation",
        )
        return null
    }

    /** The field twin of [bindOnce]; same contract, same single-callable shape. */
    private fun bindFieldOnce(key: String, owner: Class<*>?, name: String): Accessor? {
        if (owner == null) return null
        val f = findField(owner, name)
        if (f == null) {
            noteBind(
                "bindf-miss:" + key,
                "QQ: field `$name` not found on " + owner.name +
                    " -- falling back to the getter route",
            )
            return null
        }
        try {
            f.isAccessible = true
        } catch (_: Throwable) {
            // Not fatal on its own: the getter route may still succeed.
        }
        noteBind("bindf-ok:" + key, "QQ: bound field $key -> " + owner.name + "." + name)
        return Accessor(f) { target ->
            try {
                f.get(target)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Unwraps the object a hook actually receives into the NT `MsgRecord`.
     *
     * ================== WHY THIS EXISTS AT ALL ==================
     * The UI binds `AIOMsgItem` (or a subclass), NOT the record: `AIOMsgItem.getMsgRecord()` is
     * `iget-object ->e` on a `MsgRecord` field (verified). But a hook placed on the kernel, on a
     * message-list view model, or on a history-loading path can receive the RECORD directly. Both are
     * real entry points, so this accepts either rather than making the caller guess.
     *
     * Returns null when the object is neither, and [noteOnce] says so ONCE per class name. That log is
     * the single most useful line for diagnosing "the QQ hook fired but nothing was ever stored".
     */
    fun unwrap(obj: Any?): Any? {
        if (obj == null) return null
        if (isRecord(obj)) return obj
        for (n in ITEM_MSG_GETTERS) {
            val m = findNoArg(obj.javaClass, n) ?: continue
            val v = try {
                m.invoke(obj)
            } catch (_: Throwable) {
                null
            }
            if (v != null && isRecord(v)) return v
        }
        noteOnce(
            "unwrap-fail:" + obj.javaClass.name,
            "QQ: " + obj.javaClass.name + " is neither a " + MSG_CLASS + " nor an item exposing " +
                ITEM_MSG_GETTERS.joinToString("/") + "(); nothing can be read from it. If this is a " +
                "real QQ message item, its record accessor needs adding to ITEM_MSG_GETTERS.",
        )
        return null
    }

    /**
     * True when [msg] is a QQ NT message record.
     *
     * `isAssignableFrom` rather than equality: QQ's own `MsgRecord` is a plain class but the UI path
     * hands over `AIOMsgItem` subclasses and QFix can generate further ones. The fallback is an EXACT
     * name match -- never a package prefix, which is the mistake `SoulMsgAccess` records (the Soul
     * prefix would have accepted the unrelated helpers `msg.a` / `msg.b` living in the same namespace).
     *
     * Also accepts an AIO item that CARRIES a record, because that is what a UI hook sees.
     */
    fun isMessage(msg: Any?): Boolean {
        if (msg == null) return false
        if (isRecord(msg)) return true
        return unwrapOrNull(msg) != null
    }

    private fun unwrapOrNull(obj: Any?): Any? {
        if (obj == null) return null
        if (isRecord(obj)) return obj
        for (n in ITEM_MSG_GETTERS) {
            val m = findNoArg(obj.javaClass, n) ?: continue
            val v = try {
                m.invoke(obj)
            } catch (_: Throwable) {
                null
            }
            if (v != null && isRecord(v)) return v
        }
        return null
    }

    private fun isRecord(o: Any): Boolean {
        val cls = msgClassOnce()
        if (cls != null) return cls.isAssignableFrom(o.javaClass)
        // ================== THE DEGRADED PATH MUST ALSO ACCEPT SUBCLASSES ====================
        // The host class was not loadable, so `isAssignableFrom` is unavailable and the fallback is an
        // EXACT NAME match. Comparing `o.javaClass.name == MSG_CLASS` alone is WRONG, and a unit test
        // caught it: QQ's UI does not hand over a bare `MsgRecord`, it hands over an `AIOMsgItem` whose
        // record is a SUBCLASS, and QFix generates further subclasses at runtime. A direct equality test
        // therefore rejects every real message and reports "not a QQ message" -- a total, silent failure
        // whose only symptom is an empty history. (This is the same class of bug the Douyin reader
        // records: a direct equality test against the host class rejected every message that arrived as a
        // `ProxyMessage`.)
        //
        // Walking the SUPERCLASS CHAIN keeps the property that made exact-name matching attractive in the
        // first place: at every level the name must match QQ's class EXACTLY. No package prefix is
        // involved -- the prefix test is the one `SoulMsgAccess` records as a mistake, because it would
        // also accept the unrelated helpers `msg.a` / `msg.b` sharing a namespace.
        var c: Class<*>? = o.javaClass
        var depth = 0
        while (c != null && depth < 10) {
            if (c.name == MSG_CLASS) return true
            c = c.superclass
            depth++
        }
        return false
    }

    /**
     * The `MsgRecord` class, resolved ONCE through the host loader.
     *
     * A failed load is recorded here but NOT permanently poisoned: `msgClassTried` only suppresses the
     * repeated LOG, and each call retries, because the class may simply not be loaded yet when a hook
     * fires very early in process start. The exact-name fallback then keeps [isRecord] working.
     */
    private fun msgClassOnce(): Class<*>? {
        msgClass?.let { return it }
        val c = try {
            Class.forName(MSG_CLASS, false, loader())
        } catch (t: Throwable) {
            if (!msgClassTried) {
                msgClassTried = true
                Log.w(
                    TAG,
                    "QQ: " + MSG_CLASS + " is not loadable through the host classloader (" +
                        t.javaClass.simpleName + "); message identity DEGRADES to an exact-name match " +
                        "and every getter route is unavailable. A package-prefix match was rejected on " +
                        "purpose: accepting a non-message would push it through the whole pipeline.",
                )
            }
            null
        }
        if (c != null) msgClass = c
        return c
    }

    // ------------------------------------------------------------------ content

    /**
     * The chat line the user sees, a short placeholder for a known non-text message, or EMPTY.
     *
     * Every classification decision is delegated to [QQEnvelope.decide], which is pure and unit tested,
     * so this method's only job is to read the object faithfully and report the branch it took.
     *
     * It NEVER returns a raw payload: a market face's `TextElement.content` is a JSON blob, and storing
     * that is the Douyin 20 KB bug verbatim -- so [QQEnvelope] rejects a braced payload carrying a
     * QQ-owned key even when it arrived as "text".
     */
    fun content(msg: Any?): String {
        val rec = unwrap(msg)
        if (rec == null) {
            lastReason = QQEnvelope.Reason.NOT_A_MESSAGE
            return ""
        }

        // ============ THE ONE PLACE THE PAYLOAD IS REFUSED, AND WHY IT IS REFUSED HERE ============
        // `elements` is a list of `MsgElement`, each of which carries ONE typed sub-object. The body is
        // the FIRST element that is a real TextElement with non-empty content.
        //
        // Reading "the first element" instead would be wrong for a very common shape: an @-mention or a
        // reply attaches its own element to the SAME message, and a message whose first element is the
        // reply wrapper carries no text at all. So the loop below scans for a TEXT element rather than
        // indexing [0]; [firstElementType] is used separately only to LABEL a non-text message.
        val els = elementsOf(rec)
        var text: String? = null
        var textElementType = -1
        var firstType = -1
        if (els != null) {
            for ((i, e) in els.withIndex()) {
                if (e == null) continue
                val t = elementTypeOf(e)
                if (i == 0) firstType = t
                if (text == null && t == QQEnvelope.ELEM_TEXT) {
                    val body = readTextElement(e)
                    if (!body.isNullOrEmpty()) {
                        text = body
                        textElementType = t
                    }
                }
            }
        }
        // A record whose elements list could not be read at all is NOT the same as an empty one, and
        // the two must not look alike in the log.
        if (els == null) {
            noteOnce(
                "no-elements:" + rec.javaClass.name,
                "QQ: could not read the `elements` list off " + rec.javaClass.name +
                    " (both the getElements() getter and the `elements` field failed). The body lives " +
                    "ONLY in the elements, so every message will read as empty. This is a HOST CHANGE.",
            )
        }

        val recalled = isRecalled(rec)
        val d = QQEnvelope.decide(
            text = text,
            elementType = if (textElementType >= 0) textElementType
            else if (firstType >= 0) firstType else QQEnvelope.ELEM_UNKNOWN,
            recalled = recalled,
            isMessage = true,
        )
        lastReason = d.reason

        if (!d.store) {
            // Every rejection is explainable -- 必看.md: an exit with no log is indistinguishable
            // from code that never ran.
            noteOnce(
                "reject:" + d.reason + ":" + (if (firstType >= 0) firstType else -1),
                "QQ: skipped a message (reason=" + d.reason +
                    " textLen=" + (text?.length ?: 0) +
                    " elementType=" + (if (textElementType >= 0) textElementType else firstType) +
                    " elements=" + (els?.size ?: -1) +
                    " msgType=" + typeOf(rec) + ") -- nothing stored. The element payloads are " +
                    "deliberately NOT read as a fallback: a market face's content is JSON and a " +
                    "picture's is not text at all.",
            )
        } else {
            noteType(
                "text:" + (if (textElementType >= 0) textElementType else firstType),
                "QQ text message read from elementType=" + textElementType,
            )
        }
        return d.text
    }

    /**
     * The `Content` of the first `TextElement` on this element, or null.
     *
     * The `TextElement` OBJECT is the structural anchor: the element kind alone is a number, and the
     * project's rule is that an anchor beats a number. `getTextElement()` -> `TextElement.getContent()`
     * are both verified getters (`out/t14_nt_model.txt`, `out_t14_msgsupport.txt`).
     */
    private fun readTextElement(element: Any): String? {
        val te = bindOnce(
            Slot.TEXT_ELEMENT_GETTER,
            "element.getTextElement",
            element.javaClass,
            TEXT_ELEMENT_GETTERS,
        ) ?: return null
        val textEl = te.invoke(element) ?: return null

        if (!textRouteReported) {
            textRouteReported = true
            noteOnce(
                "text-route",
                "QQ body route: MsgElement.getTextElement() -> " + textEl.javaClass.name +
                    ".getContent() (elementType=" + elementTypeOf(element) + ")",
            )
        }
        val cg = bindOnce(
            Slot.TEXT_CONTENT_GETTER,
            "textElement.getContent",
            textEl.javaClass,
            listOf("getContent"),
        ) ?: return null
        return cg.invoke(textEl) as? String
    }

    /** The message's `elements` list, or null when it cannot be read at all. */
    private fun elementsOf(rec: Any): List<*>? {
        // The owner is the resolved MsgRecord class when it loaded, which is what lets this bind once
        // for the whole process instead of once per message subclass.
        val owner = msgClassOnce() ?: rec.javaClass
        val g = bindOnce(Slot.ELEMENTS_GETTER, "MsgRecord.getElements", owner, listOf("getElements"))
        val viaGetter = g?.invoke(rec)
        if (viaGetter is List<*>) return viaGetter

        // Field fallback: `elements : java.util.ArrayList` is verified on MsgRecord, and a subclass or
        // a QFix-patched accessor can leave the getter unusable while the field is intact.
        val f = elementsFieldAcc ?: bindFieldOnce(
            "MsgRecord.elements",
            owner,
            FIELD_ELEMENTS,
        )?.also { elementsFieldAcc = it }
        val viaField = f?.invoke(rec)
        if (viaField is List<*>) return viaField
        return null
    }

    /** `MsgElement.getElementType()`, or -1 with a logged reason. */
    private fun elementTypeOf(element: Any): Int {
        val g = bindOnce(
            Slot.ELEMENT_TYPE_GETTER,
            "MsgElement.getElementType",
            element.javaClass,
            listOf("getElementType"),
        )
        val viaGetter = g?.invoke(element)
        if (viaGetter is Int) return viaGetter

        val f = elementTypeFieldAcc ?: bindFieldOnce(
            "MsgElement.elementType",
            element.javaClass,
            FIELD_ELEMENT_TYPE,
        )?.also { elementTypeFieldAcc = it }
        val viaField = f?.invoke(element)
        if (viaField is Number) return viaField.toInt()

        noteOnce(
            "no-element-type:" + element.javaClass.name,
            "QQ: elementType unreadable on " + element.javaClass.name +
                " -- reported as -1, and the placeholder falls back to the generic label rather " +
                "than guessing a kind",
        )
        return -1
    }

    // ------------------------------------------------------------------ attributes

    /**
     * The conversation id this message belongs to, used to key the store.
     *
     * `peerUid` first (`MsgRecord.getPeerUid()Ljava/lang/String;`, verified), `peerUin` second. It names
     * the SAME conversation for both directions of a chat, which is what the store needs -- a chat keyed
     * by sender would split one conversation in two files. See [QQEnvelope.pickChatId] for why
     * `peerName` is deliberately NOT a candidate.
     *
     * A blank result makes the caller reject the message LOUDLY rather than file it under a guessed key.
     */
    fun talker(msg: Any?): String {
        val rec = unwrap(msg) ?: run {
            noteOnce("talker-no-msg", "QQ: talker() got a non-message object; no conversation key")
            return ""
        }
        val pick = QQEnvelope.pickChatId(callString(rec, "getPeerUid"), callLong(rec, "getPeerUin"))
        if (pick.first.isEmpty()) {
            noteOnce(
                "no-talker:" + rec.javaClass.name,
                "QQ: could not determine a conversation for " + rec.javaClass.name +
                    " (both peerUid and peerUin are blank); the message is skipped rather than filed " +
                    "under a guessed key, because a wrong key splits one conversation across files",
            )
        } else {
            noteOnce(
                "chat-key:" + pick.second,
                "QQ conversation key taken from " + pick.second +
                    " (chosen once; a key that is sometimes a uid and sometimes a uin would split " +
                    "one chat across several history files)",
            )
        }
        return pick.first
    }

    /**
     * The conversation's DISPLAY name, or "".
     *
     * Taken from `MsgRecord.getPeerName()` when present. In a group `peerName` is the group name; in a
     * two-person chat QQ often leaves it blank and the caller falls back to `ChatContext`'s own
     * resolution, so a blank here is the normal, non-fatal case and is NOT logged as an error.
     *
     * This is a DISPLAY value and is therefore never used as a store key -- see [talker].
     */
    fun chatName(msg: Any?): String {
        val rec = unwrap(msg) ?: return ""
        val name = callString(rec, "getPeerName")?.trim().orEmpty()
        if (name.isEmpty()) {
            noteOnce(
                "no-chat-name",
                "QQ: peerName is blank on this record; the conversation name falls back to what the " +
                    "caller already knows. NOT an error -- QQ leaves peerName empty for many " +
                    "two-person chats.",
            )
        }
        return name
    }

    /**
     * The sender's display name, in QQ's own order of preference.
     *
     * Delegates the ORDER to [QQEnvelope.pickSenderName], which documents the disassembly it came from.
     * Never throws and never returns null: a group nickname needs a SECOND QUERY when the server did not
     * attach the member card, and this module cannot drive that query, so the degradation chain ends at
     * the raw uin.
     */
    fun senderName(msg: Any?): String {
        val rec = unwrap(msg) ?: return ""
        val pick = QQEnvelope.pickSenderName(
            memberName = callString(rec, "getSendMemberName"),
            nickName = callString(rec, "getSendNickName"),
            remarkName = callString(rec, "getSendRemarkName"),
            senderUin = callLong(rec, "getSenderUin"),
        )
        if (pick.first.isEmpty()) {
            noteOnce(
                "no-sender",
                "QQ: no sender name and senderUin is 0 -- the message is skipped rather than labelled " +
                    "with an invented name",
            )
        } else if (pick.second != "sendMemberName") {
            // Recorded once because it is the degraded answer the task asks to be explicit about.
            noteOnce(
                "sender-step:" + pick.second,
                "QQ sender name came from " + pick.second +
                    " (the group member card sendMemberName was blank, so the nickname/uIN was used; " +
                    "the real card needs a MemberInfo second query this module cannot drive)",
            )
        }
        return pick.first
    }

    /** The sender's numeric QQ number, or 0L. */
    fun senderUin(msg: Any?): Long = unwrap(msg)?.let { callLong(it, "getSenderUin") } ?: 0L

    /**
     * True when I sent the message.
     *
     * ================== THIS IS THE STRONGEST IDENTITY READ OF THE FIVE HOSTS ==================
     * QQ's own rule was disassembled and is reproduced in [QQEnvelope.isSelfFromSendType]: `sendType`
     * short-circuits for 0 / 1 / 2 / 4, and 2 (OTHERDEVICE) and 4 (SELFFORWARD) count as MINE.
     *
     * For the values QQ does NOT short-circuit on (3 LOCAL, 5 SELFMULTIFORWARD, 6 DROPPED, anything
     * unknown) this falls back to comparing `senderUid` with my own uid, and when that is unavailable it
     * returns FALSE and SAYS SO once, naming the value. That degradation is deliberate and is the same
     * policy the Kuaishou reader states: a fabricated direction is indistinguishable from a correct one
     * in the UI, and the WeChat round already shipped exactly that bug.
     */
    fun isSend(msg: Any?): Boolean {
        val rec = unwrap(msg) ?: return false
        val st = callInt(rec, "getSendType")
        val verdict = QQEnvelope.isSelfFromSendType(st)
        if (verdict != null) return verdict

        // Not short-circuited by QQ's own rule. Try the uid comparison the host itself uses.
        val senderUid = callString(rec, "getSenderUid")?.trim().orEmpty()
        val myUid = myUid()
        if (senderUid.isNotEmpty() && !myUid.isNullOrEmpty()) {
            val self = senderUid == myUid
            noteOnce(
                "isSend-uid",
                "QQ direction decided by uid comparison (sendType=" + st +
                    " is not one QQ short-circuits on): senderUid==mine -> " + self,
            )
            return self
        }
        noteOnce(
            "isSend-degraded:" + st,
            "QQ: sendType=" + st + " is not one QQ's own isSelf short-circuits on (0/1/2/4), and my " +
                "own uid is unavailable (mAppRuntime.getCurrentUid()), so isSend() returns FALSE for " +
                "these messages and says so. No direction is guessed on purpose -- a wrong direction " +
                "looks exactly like a right one.",
        )
        return false
    }

    /**
     * My own uid, read from the host's app runtime -- the SAME source QQ's own isSelf uses.
     *
     * DISASSEMBLED from `com.tencent.mobileqq.aio.utils.as#j(MsgRecord)Z` [classes3.dex]:
     *
     *     invoke mqq/app/MobileQQ->waitAppRuntime()Lmqq/app/AppRuntime;
     *     invoke mqq/app/AppRuntime->getCurrentUin()Ljava/lang/String;
     *     invoke mqq/app/AppRuntime->getCurrentUid()Ljava/lang/String;
     *     invoke android/text/TextUtils->equals(CharSequence, CharSequence)Z
     *
     * Both classes are FRAMEWORK-HOST types, not module types, so they are reached reflectively through
     * the host loader and a failure is neutral (null) rather than fatal. `getCurrentUid` is preferred for
     * the comparison because `MsgRecord.senderUid` is a uid, not a uin.
     *
     * DONE ONCE: the runtime is a process-wide singleton, so the value is cached after the first
     * successful read. A failure is retried (the runtime may not exist yet during early process start).
     */
    private fun myUid(): String? {
        cachedMyUid?.let { return it }
        val runtime = try {
            val mqq = Class.forName("mqq.app.MobileQQ", false, loader())
            val wait = mqq.getDeclaredMethod("waitAppRuntime")
            wait.isAccessible = true
            wait.invoke(null)
        } catch (_: Throwable) {
            null
        }
        if (runtime == null) {
            noteOnce(
                "no-appruntime",
                "QQ: mqq.app.MobileQQ.waitAppRuntime() is not reachable, so my own uid cannot be read; " +
                    "direction falls back to QQ's sendType short-circuit only",
            )
            return null
        }
        for (n in listOf("getCurrentUid", "getCurrentUin")) {
            val v = callString(runtime, n)?.trim().orEmpty()
            if (v.isNotEmpty()) {
                cachedMyUid = v
                noteOnce("my-uid:" + n, "QQ: my own uid read via AppRuntime." + n + "()")
                return v
            }
        }
        noteOnce(
            "no-my-uid",
            "QQ: AppRuntime exposes neither getCurrentUid() nor getCurrentUin(), so the uid fallback " +
                "in isSend() is unavailable",
        )
        return null
    }

    /**
     * The message `msgType`.
     *
     * NOTE this is the MESSAGE-level kind (`KMSGTYPE*`), a different enum from
     * `MsgElement.elementType`. Read as the first PART of `MsgRecord.getMsgType()`, which QQ may return
     * as `-1`; that is passed through rather than normalised, so a caller can tell "unknown" from
     * "KMSGTYPEUNKNOWN (0)".
     */
    fun typeOf(msg: Any?): Int = unwrap(msg)?.let { callInt(it, "getMsgType") } ?: -1

    /** `msgType`'s sub-kind, or -1. */
    fun subTypeOf(msg: Any?): Int = unwrap(msg)?.let { callInt(it, "getSubMsgType") } ?: -1

    /**
     * The message id, or 0L when unavailable.
     *
     * `MsgRecord.msgId` is a primitive `J` (verified), so this is a plain read. 0L makes the caller
     * fall through to [QQEnvelope.syntheticId] instead of dropping the message, the same contract the
     * other four readers honour.
     */
    fun msgId(msg: Any?): Long = unwrap(msg)?.let { callLong(it, "getMsgId") } ?: 0L

    /** `msgSeq`, the host's own ordering key, or 0L. Part of the synthetic-id identity. */
    fun msgSeq(msg: Any?): Long = unwrap(msg)?.let { callLong(it, "getMsgSeq") } ?: 0L

    /**
     * Always 0L: QQ's NT record exposes no separate server id on the fields verified here.
     *
     * Same contract as the Douyin / Soul / Kuaishou readers -- 0 keeps `ResultCache`'s key composition
     * working (it treats 0 as "absent") without a special case at every call site.
     */
    fun msgSvrId(msg: Any?): Long = 0L

    /**
     * The message time, normalised to MILLISECONDS.
     *
     * `msgTime` first, `timeStamp` second -- both verified `J` fields on `MsgRecord`. The unit is not
     * documented, so [QQEnvelope.toMillis] normalises by magnitude and the branch is logged once: a
     * seconds value reaching `AnalysisController.onLiveMessage` unnormalised would be rejected by its
     * `< 1e12` epoch gate and EVERY message would be dropped, loudly but totally.
     */
    fun createTime(msg: Any?): Long {
        val rec = unwrap(msg) ?: return 0L
        val picked = QQEnvelope.pickTime(callLong(rec, "getMsgTime"), callLong(rec, "getTimeStamp"))
        if (picked.second == "none") {
            noteOnce(
                "bad-time-zero:" + rec.javaClass.name,
                "QQ: no usable timestamp on " + rec.javaClass.name +
                    " (getMsgTime/getTimeStamp both <= 0); the shared live feed will reject this " +
                    "message at its epoch gate",
            )
            return 0L
        }
        noteOnce(
            "time-unit:" + picked.second,
            "QQ: timestamp -> " + picked.first + " ms (branch=" + picked.second +
                "). If this branch is not 'ms', the normalisation above is what keeps the epoch gate " +
                "in onLiveMessage from dropping every message.",
        )
        return picked.first
    }

    /**
     * `chatType`, or -1 when unreadable.
     *
     * `MsgRecord.chatType : I` is verified. -1 is returned rather than defaulting to C2C so that
     * [isGroup] can tell "a private chat" from "could not read", which matters because a private chat
     * mislabelled as a group puts a nickname in front of every line.
     */
    fun chatTypeOf(msg: Any?): Int = unwrap(msg)?.let { callInt(it, "getChatType") } ?: -1

    /** True when this is a multi-party conversation; see [QQEnvelope.isGroupChatType]. */
    fun isGroup(msg: Any?): Boolean = QQEnvelope.isGroupChatType(chatTypeOf(msg))

    /**
     * True when the message was recalled, detected by the STRUCTURAL anchor.
     *
     * `RevokeElement` is a verified member of `GrayTipElement` (`getRevokeElement()`), and a revoke
     * arrives with `MsgElement.elementType == KELEMTYPEGRAYTIP (8)` plus a `GrayTipElement` whose
     * `subElementType` / `revokeElement` is populated. So the test is: an element that has a
     * `getGrayTipElement()` returning an object that has a `getRevokeElement()` -- an anchor made of
     * types, not of a number.
     *
     * The ELEMENT NUMBER 8 is used only as a cheap pre-filter, and a message is accepted as recalled
     * only when the `RevokeElement` object is really there. A stale text element on the same record is
     * therefore not enough to store a withdrawn line (see [QQEnvelope.decide]).
     */
    fun isRecalled(msg: Any?): Boolean {
        val rec = unwrap(msg) ?: return false
        val els = elementsOf(rec) ?: return false
        for (e in els) {
            if (e == null) continue
            val t = elementTypeOf(e)
            if (t != QQEnvelope.ELEM_GRAYTIP) continue
            val gray = callAny(e, "getGrayTipElement") ?: continue
            if (callAny(gray, "getRevokeElement") != null) return true
        }
        return false
    }

    /**
     * Only plain text counts (the standing requirement), decided by whether a usable body exists.
     *
     * The type NUMBER is deliberately not the test: a `MsgElement` can carry several elements at once
     * (text + @ mention, reply + quote), so the object is authoritative and the number is not -- and the
     * project's rule is that a structural anchor beats a magic number. Requiring the resolved body to be
     * a real chat line (not a placeholder, not a recalled line, not an envelope) is [QQEnvelope.decide]'s
     * job; this reads its verdict off [lastReason] rather than re-deriving it, so the two can never
     * disagree.
     */
    fun isText(msg: Any?): Boolean {
        content(msg)
        return lastReason == QQEnvelope.Reason.OK
    }

    // ------------------------------------------------------------------ the UI reverse lookup

    /**
     * The message object behind a long-pressed or clicked View.
     *
     * THE CHAIN (every link verified, `out/t14_chain.txt` / `out/t14_q3_rv.txt`):
     *
     *   View
     *     -> walk ViewParents until a `androidx.recyclerview.widget.RecyclerView` (matched by NAME,
     *        because a compile-time androidx reference cannot resolve inside QQ -- the same rule
     *        `ChatContext` follows)
     *     -> `rv.findContainingViewHolder(view)`      (declared on the framework RecyclerView)
     *     -> `holder.getBindingAdapterPosition()`     (or Absolute/Adapter -- all three tried)
     *     -> `adapter.getItem(position)`
     *     -> `AIOMsgItem.getMsgRecord()`              (unwrapped by [unwrap])
     *
     * Each step logs a MISS explicitly; a null return is always preceded by the reason. Returns null when
     * any link fails, and never throws.
     */
    fun messageFromView(v: View?): Any? {
        val rv = findRecyclerView(v)
        if (rv == null) {
            noteOnce(
                "rv-not-found",
                "QQ: no RecyclerView ancestor above the pressed view (walked up to " + MAX_PARENT_HOPS +
                    " parents). The long-press did not start inside the message list -- this is the " +
                    "normal case for a press on the input bar or the title.",
            )
            return null
        }
        val pos = positionOf(rv, v)
        if (pos < 0) {
            noteOnce(
                "rv-no-position",
                "QQ: found a RecyclerView but could not map the view to an adapter position " +
                    "(findContainingViewHolder returned null, or its position getters all returned " +
                    "-1). The view is probably a row that is mid-recycle.",
            )
            return null
        }
        val item = itemAtPosition(rv, pos)
        if (item == null) {
            noteOnce(
                "rv-no-item:" + pos,
                "QQ: adapter.getItem(" + pos + ") returned null; nothing to read at that position",
            )
            return null
        }
        val rec = unwrap(item)
        if (rec == null) {
            noteOnce(
                "rv-item-not-msg:" + item.javaClass.name,
                "QQ: adapter position " + pos + " holds " + item.javaClass.name +
                    ", which exposes no MsgRecord. In the message list this is a TIME SEPARATOR or a " +
                    "system row rather than a message -- the adapter interleaves them. Returning null " +
                    "rather than the raw item: an item nobody can read a body from would enter the " +
                    "pipeline as a message that mysteriously analyses as empty.",
            )
            return null
        }
        return rec
    }

    /** The message at a known adapter position, unwrapped to the `MsgRecord`, or null. */
    fun messageAt(rv: Any?, position: Int): Any? {
        if (rv == null || position < 0) return null
        val item = itemAtPosition(rv, position) ?: return null
        return unwrap(item)
    }

    /**
     * Walks up ViewParents to the RecyclerView, typed as Any so no androidx type is referenced.
     *
     * Matched by walking the CLASS HIERARCHY rather than by equality: QQ's list is
     * `androidx.recyclerview.widget.AIORecycleView extends InnerAIORecycleView extends ... RecyclerView`
     * (verified: `AIORecycleView` [classes3.dex] has `getLayoutManager()Landroidx/recyclerview/widget/
     * AIOLayoutManager;` and its own `dispatchDraw`), so an equality test on `RecyclerView` would reject
     * the very list the module needs.
     */
    fun findRecyclerView(v: View?): Any? {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < MAX_PARENT_HOPS) {
            if (isRecyclerView(cur)) return cur
            val p = cur.parent
            cur = if (p is View) p else null
            depth++
        }
        return null
    }

    private fun isRecyclerView(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var depth = 0
        while (c != null && depth < 10) {
            if (c.name == RV_CLASS) return true
            c = c.superclass
            depth++
        }
        return false
    }

    /**
     * Maps a view to its adapter position through the framework ViewHolder API.
     *
     * Uses `findContainingViewHolder` (a `RecyclerView` method) and then the holder's position getter,
     * trying Binding first and Adapter last -- the order `ChatContext.positionOf` established, because a
     * holder "mid-recycle" reports -1 from the newer getters while the older one still works.
     */
    fun positionOf(rv: Any, view: View?): Int {
        if (view == null) return -1
        val m = findOneArg(rv.javaClass, "findContainingViewHolder", View::class.java)
        if (m == null) {
            noteOnce(
                "no-findContainingViewHolder",
                "QQ: " + rv.javaClass.name + " has no findContainingViewHolder(View); this is NOT an " +
                    "androidx RecyclerView at all, or a very old androidx build",
            )
            return -1
        }
        val holder = try {
            m.invoke(rv, view)
        } catch (_: Throwable) {
            null
        } ?: return -1
        for (name in listOf(
            "getBindingAdapterPosition",
            "getAbsoluteAdapterPosition",
            "getAdapterPosition",
        )) {
            val pm = findNoArg(holder.javaClass, name) ?: continue
            val p = try {
                pm.invoke(holder)
            } catch (_: Throwable) {
                null
            }
            if (p is Int && p >= 0) return p
        }
        return -1
    }

    /** The adapter attached to [rv], typed as Any. */
    fun adapterOf(rv: Any?): Any? {
        if (rv == null) return null
        val m = findNoArg(rv.javaClass, "getAdapter") ?: run {
            noteOnce(
                "no-getAdapter",
                "QQ: " + rv.javaClass.name + " declares no getAdapter(); the list cannot be reached",
            )
            return null
        }
        return try {
            m.invoke(rv)
        } catch (_: Throwable) {
            null
        }
    }

    /** The item the adapter holds at a real position, or null. */
    fun itemAtPosition(rv: Any?, position: Int): Any? {
        if (rv == null || position < 0) return null
        val adapter = adapterOf(rv) ?: return null
        val getItem = findOneArg(adapter.javaClass, "getItem", Int::class.javaPrimitiveType)
            ?: run {
                noteOnce(
                    "no-getItem:" + adapter.javaClass.name,
                    "QQ: adapter " + adapter.javaClass.name + " declares no getItem(int), so " +
                        "position-based access is unavailable. Note the verified QQ list adapter is " +
                        "backed by AutoCancelAsyncListDiffer, which DOES expose u()Ljava/util/List; " +
                        "if this failure is seen on a real device, that getter is the fallback to add.",
                )
                return null
            }
        return try {
            getItem.invoke(adapter, position)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Every message the adapter currently holds.
     *
     * Returns an empty list rather than null, so a caller can always iterate. Only items that really
     * carry a message are included -- the adapter interleaves time separators and system rows, and
     * feeding those into the history feed is how a card lands on an unrelated row (the WeChat bug
     * `ChatContext.itemAtPosition` records).
     */
    fun messagesInAdapter(rv: Any?): List<Any> {
        if (rv == null) return emptyList()
        val adapter = adapterOf(rv) ?: return emptyList()
        val count = callItemCount(adapter)
        if (count <= 0) {
            noteOnce(
                "empty-adapter",
                "QQ: adapter itemCount=" + count + "; nothing to collect. If a chat page is open, the " +
                    "count getter is not the one this reader expects",
            )
            return emptyList()
        }
        val out = ArrayList<Any>(count)
        for (i in 0 until count) {
            val item = itemAtPosition(rv, i) ?: continue
            val rec = unwrap(item) ?: continue
            out.add(rec)
        }
        noteOnce(
            "adapter-collected:" + count,
            "QQ: adapter held " + count + " row(s), " + out.size + " carried a MsgRecord (the rest are " +
                "time separators / system rows)",
        )
        return out
    }

    private fun callItemCount(adapter: Any): Int {
        val m = findNoArg(adapter.javaClass, "getItemCount") ?: return -1
        return try {
            (m.invoke(adapter) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * The conversation identity for the CURRENT page: chatType and the peer id.
     *
     * ================== TWO ROUTES, IN PRIORITY ORDER, AND THE FIRST IS FREE ==================
     * 1. The adapter's own items. Every `AIOMsgItem` carries a `MsgRecord`, and that record carries
     *    `peerUid` / `peerUin` / `chatType` / `peerName` -- so the session can be read off the FIRST
     *    message without touching QQ's session object at all. This is the route [sessionFromRecord]
     *    takes and it needs no new hook point.
     * 2. The item-level `Contact`: `AIOMsgItem.D()` returns
     *    `com.tencent.qqnt.kernelpublic.nativeinterface.Contact`, which has exactly
     *    `getChatType()I` / `getPeerUid()Ljava/lang/String;` (verified dump). Used when the record's
     *    fields are blank, e.g. for a local echo before the server fills the record in.
     *
     * A caller that has a message in hand should prefer [sessionOf], which needs neither route.
     * Returns (chatId, chatType) with ("", -1) when nothing could be read, and always logs why.
     */
    fun currentSession(rv: Any?): Pair<String, Int> {
        val msgs = messagesInAdapter(rv)
        for (m in msgs) {
            val s = sessionOf(m)
            if (s.first.isNotEmpty() || s.second >= 0) return s
        }
        // Route 2: an item that at least carries a Contact, even when its record is incomplete.
        if (rv != null) {
            val adapter = adapterOf(rv)
            val count = if (adapter != null) callItemCount(adapter) else 0
            for (i in 0 until maxOf(count, 0)) {
                val item = itemAtPosition(rv, i) ?: continue
                for (n in ITEM_CONTACT_GETTERS) {
                    val c = callAny(item, n) ?: continue
                    val uid = callString(c, "getPeerUid")?.trim().orEmpty()
                    val ct = callInt(c, "getChatType")
                    if (uid.isNotEmpty() || ct >= 0) {
                        noteOnce(
                            "session-from-contact:" + n,
                            "QQ session read from " + item.javaClass.name + "." + n +
                                "() -> Contact (chatType=" + ct + ")",
                        )
                        return uid to ct
                    }
                }
            }
        }
        noteOnce(
            "no-session",
            "QQ: could not determine the current session (no message in the adapter yielded a " +
                "peerUid/chatType, and no item exposed a Contact). Callers that already hold a " +
                "message must use sessionOf(msg) instead -- it needs no list at all.",
        )
        return "" to -1
    }

    /** (chatId, chatType) for one message; ("", -1) when unreadable. Never throws. */
    fun sessionOf(msg: Any?): Pair<String, Int> {
        val rec = unwrap(msg) ?: return "" to -1
        return talker(rec) to chatTypeOf(rec)
    }

    /**
     * The messages currently SELECTED in QQ's multi-select mode.
     *
     * ============================================================================
     * TODO(t11) -- NOT IMPLEMENTED, AND DELIBERATELY NOT GUESSED.
     * ============================================================================
     * The selection state was NOT established. What the dex DOES show (recorded so the next attempt
     * starts from facts rather than from zero):
     *
     *   - QA_BASELINE / CAPTAIN_FINDING_NT_STACK.md: multi-select is real
     *     (`MultiSelect`/`multiselect` 1,521 byte hits; `select_mode`/`SelectMode` 265).
     *   - `com.tencent.mobileqq.aio.helper.SelectableMenuHelper` [classes6.dex] -- 20 fields, 20 methods,
     *     fields include `#f : java.util.List`. A strong candidate for the selected set.
     *   - `com.tencent.mobileqq.aio.event.MultiSelectEvent$GetSelectMsgList` / `SetSelectMsgList` each
     *     hold `#d : java.util.List` -- the EVENT payloads for the selection, which is the shape a hook
     *     would observe rather than poll.
     *   - `com.tencent.qqnt.aio.helper.multiselect.c` / `.j` hold `#e`/`#d : java.util.List`.
     *   - `com.tencent.qqnt.msgselect.api.a` holds `#g : java.util.List`.
     *
     * WHICH of those is live on the 9.3.65 chat page, and whether the list holds `AIOMsgItem`s or
     * `MsgRecord`s, needs a RUNTIME check (t11's remit). Returning any of them on the strength of a
     * field name would be exactly the "plausible-looking API" this project's 必看.md forbids, so this
     * returns an EMPTY list: the honest "I do not know" -- a caller that shows "0 selected" is
     * recoverable, a caller that shows the wrong 8 messages analysed is not.
     *
     * The logging below fires ONCE so the absence is visible in a logcat rather than looking like a
     * user who simply never selected anything.
     */
    fun selectedMessages(rv: Any?): List<Any> {
        noteOnce(
            "multiselect-todo",
            "QQ: the multi-select set is NOT implemented (task t11 owns the runtime decision). " +
                "Candidate carriers found in the dex, none confirmed live: " +
                "aio.helper.SelectableMenuHelper#f:List, " +
                "aio.event.MultiSelectEvent\$GetSelectMsgList#d:List, " +
                "qqnt.aio.helper.multiselect.c#e:List / .j#d:List, " +
                "qqnt.msgselect.api.a#g:List. This returns an empty list on purpose -- an empty " +
                "selection is recoverable, a WRONG one is not.",
        )
        // The parameter is accepted so the signature is stable for the caller and for t11's follow-up;
        // it is deliberately unused rather than being fed to a guess.
        return emptyList()
    }

    /**
     * True when [rv]'s adapter looks like the QQ message list.
     *
     * EXISTS SO A FUTURE HOOK CAN DECIDE WHERE TO ATTACH rather than attaching to the first RecyclerView
     * it sees (QQ has several on one screen: the chat list, the message list, the @ member list, the
     * photo panel). The test is STRUCTURAL -- the adapter's class hierarchy must contain a class that
     * declares `onBindViewHolder` AND whose name lives under the verified aio msglist namespace -- plus a
     * fallback that the adapter is a `RecyclerView.Adapter` descendant, which cannot be obfuscated away.
     */
    fun looksLikeMessageList(rv: Any?): Boolean {
        val adapter = adapterOf(rv) ?: return false
        val isAdapter = try {
            val base = Class.forName(RV_CLASS + "\$Adapter", false, loader())
            base.isAssignableFrom(adapter.javaClass)
        } catch (_: Throwable) {
            false
        }
        if (!isAdapter) return false
        var c: Class<*>? = adapter.javaClass
        var depth = 0
        while (c != null && depth < 10) {
            if (c.name.startsWith(MSGLIST_PACKAGE)) return true
            c = c.superclass
            depth++
        }
        // A RecyclerView.Adapter that is NOT in the msglist namespace: report once, because it is the
        // signature of QQ having moved the list and this module silently attaching to the wrong one.
        noteOnce(
            "foreign-adapter:" + adapter.javaClass.name,
            "QQ: " + adapter.javaClass.name + " is a RecyclerView.Adapter but NOT in the verified " +
                "msglist namespace (com.tencent.aio.part.root.panel.content.firstLevel.msglist...). " +
                "Treating it as a NON-message list so the module cannot attach to the chat list or the " +
                "@-member list by accident.",
        )
        return false
    }

    // ------------------------------------------------------------------ shared reflection helpers
    //
    // These are the ONLY places a Method/Field is looked up, and they never cache: caching is
    // [bindOnce]'s job, so a cache can never be filled by a lookup that was not logged.

    /**
     * A no-arg INSTANCE method named [name] on [owner] (or an inherited one), or null.
     *
     * ================== STATIC METHODS ARE REJECTED ON PURPOSE ==================
     * Every caller of this helper wants a PER-INSTANCE accessor: the record, the elements list, the
     * text content, the adapter's item count. A STATIC method matching the same name is a trap, because
     * `Method.invoke(receiver)` on a static method SILENTLY DISCARDS the receiver and returns a
     * class-level value. It does not throw and it does not return null, so nothing downstream can tell
     * the difference:
     *
     *   `AIOMsgItem#o()` is exactly this (verified: `acc=0x1019` = STATIC+FINAL+SYNTHETIC, body
     *   `sget-object i1`). It looks like a second record getter, but it returns the static `i1` -- one
     *   object, assigned once in `<clinit>`, SHARED by every message. Using it means blank analysis text
     *   and history keys collapsing to a single `talker#0` row.
     *
     * `o` was already removed from [ITEM_MSG_GETTERS]; this guard means that even if someone adds a
     * static name back, the lookup returns null and the caller's own "no candidate matched" log fires --
     * a LOUD failure instead of a silent one. The rejection is logged once per name so it cannot hide.
     *
     * (The one place that genuinely needs a static method -- `mqq.app.MobileQQ.waitAppRuntime()` --
     * deliberately does NOT use this helper: it resolves through `getDeclaredMethod` at its own call
     * site, precisely because it is static.)
     */
    private fun findNoArg(owner: Class<*>?, name: String): Method? {
        var c: Class<*>? = owner
        var depth = 0
        while (c != null && depth < 10) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterCount != 0) continue
                if (isStatic(m)) {
                    noteBind(
                        "static-rejected:" + name + ":" + c.name,
                        "QQ: " + c.name + "." + name + "() is STATIC, so it cannot return a value for " +
                            "THIS object -- `invoke(receiver)` would discard the receiver and hand back a " +
                            "class-level value shared by every message. Returned as a MISS so the caller " +
                            "reports 'no candidate matched' instead of silently reading a shared object.",
                    )
                    return null
                }
                try {
                    m.isAccessible = true
                } catch (_: Throwable) {
                    // Not fatal: the public route may still work through invoke().
                }
                return m
            }
            // Also accept a PUBLIC inherited method, which a subclass override may hide from
            // declaredMethods.
            for (m in c.methods) {
                if (m.name == name && m.parameterCount == 0 && !isStatic(m)) return m
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /** `Modifier.isStatic`, with the modifier bits read defensively (a host class can be exotic). */
    private fun isStatic(m: Method): Boolean = try {
        java.lang.reflect.Modifier.isStatic(m.modifiers)
    } catch (_: Throwable) {
        false
    }

    private fun findOneArg(owner: Class<*>?, name: String, arg: Class<*>?): Method? {
        var c: Class<*>? = owner
        var depth = 0
        while (c != null && depth < 10) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == 1 &&
                    (arg == null || m.parameterTypes[0] == arg ||
                        m.parameterTypes[0].isAssignableFrom(arg))
                ) {
                    try {
                        m.isAccessible = true
                    } catch (_: Throwable) {
                        // Same as above.
                    }
                    return m
                }
            }
            for (m in c.methods) {
                if (m.name == name && m.parameterCount == 1 &&
                    (arg == null || m.parameterTypes[0] == arg ||
                        m.parameterTypes[0].isAssignableFrom(arg))
                ) {
                    return m
                }
            }
            c = c.superclass
            depth++
        }
        return null
    }

    private fun findField(owner: Class<*>?, name: String): Field? {
        var c: Class<*>? = owner
        var depth = 0
        while (c != null && depth < 10) {
            for (f in c.declaredFields) {
                if (f.name == name) return f
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /**
     * Invokes a no-arg accessor by NAME without caching.
     *
     * Used only for the DIAGNOSTIC and secondary reads (`getPeerUid`, `getSendNickName`, ...) where the
     * cost of a second lookup is irrelevant and a cache would add state for no benefit. The BODY route
     * never uses it -- see the reflection-field comment for why that distinction matters.
     */
    private fun callAny(obj: Any, name: String): Any? = try {
        findNoArg(obj.javaClass, name)?.invoke(obj)
    } catch (_: Throwable) {
        null
    }

    private fun callString(obj: Any, name: String): String? = callAny(obj, name) as? String

    private fun callInt(obj: Any, name: String): Int = when (val v = callAny(obj, name)) {
        is Int -> v
        is Number -> v.toInt()
        else -> -1
    }

    private fun callLong(obj: Any, name: String): Long = when (val v = callAny(obj, name)) {
        is Long -> v
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    /** The host classloader, resolved once through [Resolver]. */
    private fun loader(): ClassLoader = resolver.classLoader()

    private fun loaderName(): String = try {
        loader().javaClass.name
    } catch (_: Throwable) {
        "<unavailable>"
    }

    // ------------------------------------------------------------------ logging

    /**
     * One line per KEY, ever, up to [DIAG_LIMIT] distinct keys.
     *
     * The key IS the dedup -- see the note on [seenNotes] for why a Set beats a counter. Once the cap is
     * reached, a NEW key is dropped but an ALREADY-SEEN one is still suppressed, so the cap can never
     * turn a one-per-key rule into a per-call flood.
     *
     * CHOICE, STATED: a dropped line is silent. The alternative -- logging everything after the cap --
     * was rejected because this reader runs on every row of every bind, and the whole point of the cap is
     * that the FIRST occurrence of each DIFFERENT kind of failure is what diagnoses the bug. Which keys
     * were seen is inspectable through the debug log file, not from a counter here.
     */
    private fun noteOnce(key: String, message: String) {
        if (seenNotes.size >= DIAG_LIMIT && !seenNotes.contains(key)) return
        if (seenNotes.add(key)) Log.i(TAG, message)
    }

    /** The same one-per-key rule for per-element-type lines, kept in its own budget. */
    private fun noteType(key: String, message: String) {
        if (seenTypes.size >= DIAG_LIMIT && !seenTypes.contains(key)) return
        if (seenTypes.add(key)) Log.i(TAG, message)
    }

    /**
     * A THIRD, separate budget for the binding lines.
     *
     * WHY BINDING IS NOT LOGGED THROUGH [noteOnce]: each of those messages is a paragraph, and there are
     * more of them than any other kind. Sharing the diagnostic cap would let the FIRST message's bind
     * chatter fill it before a single message had been classified -- the exact failure 必看.md records
     * for a count-based cap. A separate Set and a separate limit means the binding story and the
     * classification story each get their full budget.
     */
    private fun noteBind(key: String, message: String) {
        if (seenBinds.size >= BIND_LIMIT && !seenBinds.contains(key)) return
        if (seenBinds.add(key)) Log.i(TAG, message)
    }

    private fun shorten(s: String?): String = when {
        s == null -> "<null>"
        s.length <= 60 -> s
        else -> s.substring(0, 60) + "...(" + s.length + ")"
    }

    private fun stringify(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is ByteArray -> "<byte[" + v.size + "]>"
        else -> v.toString()
    }
}
