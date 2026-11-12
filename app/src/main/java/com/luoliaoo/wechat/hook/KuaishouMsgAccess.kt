package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method

/**
 * Message field access for KUAISHOU, sibling of [MsgAccess], [DouyinMsgAccess] and [SoulMsgAccess].
 *
 * WHY A FOURTH READER: the four hosts agree only on the SHAPE of the answer (content / talker /
 * isSend / type / time / id). WeChat reads stable FIELD names on an obfuscated class; Douyin reads
 * ordinary getters on an un-obfuscated class; Soul reads its own `cn.soulapp.imlib` library. Kuaishou
 * is a fourth shape again -- a self-developed IM SDK (`com.kwai.imsdk`), NOT obfuscated at the entity
 * level, with a BOXED `Long` id and protobuf-nano byte[] payloads for non-text messages. Sharing the
 * caller while keeping the reader separate is what stops one host's assumption from silently breaking
 * another host's history.
 *
 * ============================= EVERY FACT BELOW IS VERIFIED, NOT GUESSED =======================
 * Read from Kuaishou 14.8.30.50423's own dex (31 files, 343,739 classes) via `_tools/ks/ksdex.py`.
 * Sources: `_tools/ks/KS_MSG_RECON.md` (t1, static) and `KS_DEVICE_FACTS_CAPTAIN.md` (device).
 *
 *   com.kwai.imsdk.msg.KwaiMsg                       [classes4.dex]  access=0x1
 *     super=Object   ifaces=[Cloneable, dna.a, Serializable]
 *     FIELD id            Ljava/lang/Long;   <-- BOXED, so it can be null
 *     FIELD text          Ljava/lang/String;
 *     FIELD sender        Ljava/lang/String;
 *     FIELD realFrom      Ljava/lang/String;
 *     FIELD target        Ljava/lang/String;
 *     FIELD targetType    I
 *     FIELD msgType       I
 *     FIELD sentTime      J
 *     FIELD createTime    J
 *     FIELD seq           J
 *     FIELD clientSeq     J
 *     FIELD contentBytes  [B    <-- protobuf-nano BINARY
 *     FIELD extra         [B    <-- protobuf-nano BINARY
 *     FIELD localExtra    [B    <-- protobuf-nano BINARY
 *     FIELD unknownTips   Ljava/lang/String;
 *     FIELD searchableContent Ljava/lang/String;
 *
 *   getText() is a single field read (verified by disassembly: `iget-object text; return-object`),
 *   which is why the field is preferred over the getter here.
 *
 * THE OBJECT THE UI ACTUALLY BINDS IS A SUBCLASS:
 *   `...message_item.model.bean.KwaiMsgWrapper extends KwaiMsg` [classes15.dex] -- so every read uses
 *   `isAssignableFrom`, never class equality. A direct equality test is what silently rejected every
 *   Douyin message that arrived as `ProxyMessage`.
 *
 * ============================== ROBUST HOT-PATCH WARNING ======================================
 * Kuaishou ships Kwai Robust + a Tinker fork. Every business method begins with a
 * `com.kwai.robust.PatchProxy.apply*` prologue whose `if-eqz` can short-circuit the real body.
 * Unlike Soul there is NO per-class `changeQuickRedirect` FIELD to inspect -- only 7 such fields exist
 * in the whole APK, all inside the framework proxy classes themselves. So [probeClasses] reports the
 * patch state through `PatchProxy`'s own static API instead of a field read, and reports "unknown"
 * plainly when that API is absent rather than pretending to a verdict.
 *
 * DEGRADING, NEVER GUESSING (the project's standing rule): when something cannot be read, this class
 * returns the NEUTRAL value ("" / 0L / false) and logs which branch it took. It never invents a
 * plausible id, never falls back to the raw payload, and never lets a guessed class name make the
 * pipeline merely LOOK alive. An idle pipeline that says why beats a fake success.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouMsgAccess(
    /**
     * The HOST classloader, taken from [Resolver] and never from a constructor argument.
     *
     * WHY IT IS REQUIRED RATHER THAN OPTIONAL: 必看.md records that resolving host classes through the
     * wrong loader silently produces a "class not found" that looks like a missing feature. A clone
     * runs the host under `DelegateLastClassLoader`, where the module's own loader resolves the SAME
     * name to a DIFFERENT Class object -- so the hook attaches to a method nobody calls and the only
     * missing evidence is the `fired` line.
     *
     * ############################################################################################
     * # CONSTRAINT (KS_BUILD_PLAN.md R21): `resolver.classLoader()` is the ONLY method of Resolver #
     * # this class is allowed to touch. `points` / `resolve()` / `msgInfoClass()` are all WeChat-only #
     * # and every lookup through them returns null inside Kuaishou, which reads in the log like      #
     * # "the Kuaishou class does not exist" while it plainly does.                                    #
     * ############################################################################################
     */
    private val resolver: Resolver,
) {

    private companion object {
        const val TAG = "LuoLiao"

        /** The un-obfuscated IM entity every Kuaishou chat message derives from. */
        const val MSG_CLASS = KuaishouEnvelope.MSG_CLASS

        /** Class owning the Robust patch API; used only for the diagnostic. */
        const val PATCH_CLASS = "com.kwai.robust.PatchProxy"

        /** Cap on distinct diagnostic lines -- by TYPE, not by event (必看.md's dedup rule). */
        const val DIAG_LIMIT = 12

        /**
         * Field candidates for the text body, in priority order.
         *
         * `text` is the verified field. `searchableContent` is listed because the dex shows it
         * populated for messages that are searchable -- it is a real string field, so it is a
         * legitimate second read, not a guess.
         */
        val TEXT_FIELDS = listOf("text", "searchableContent")

        /**
         * Getter candidates, in priority order: `getText` FIRST.
         *
         * ============================== WHY THIS ORDER WAS REVERSED =================================
         * An earlier version read `getSummary` first, reasoning that "the summary is the host's display
         * entry, so it is a superset of the text". Disassembly proves that wrong in the case that
         * matters. Measured:
         *
         *   KwaiMsg.getText() / KwaiMsg.getSummary()  -> identical (`iget-object KwaiMsg->text`)
         *   TextMsg.getSummary()                      -> `invoke-virtual TextMsg->getText()` (trampoline)
         *   TextMsg.getText()                         -> reads `mTextContent.a`, null-safe (returns '')
         *   KImageMsg.getSummary()                    -> `Application->getString(0x7f111182)`  <<< !!
         *   KImageMsg.getText()                       -> NOT overridden at all
         *
         * So `getSummary` is NOT a superset: for non-text classes it returns a LOCALIZED UI STRING.
         * Reading it first would store "[图片]" / "[Photo]" as if the user had typed it, making the
         * stored history depend on the device language and indistinguishable from literal user text.
         *
         * `getText` is also the cheaper call on the text path (no extra PatchProxy hop through the
         * summary trampoline), and it is non-null-safe in the host (returns '' rather than null).
         * `getSummary` is retained as the SECOND candidate so a future subclass that carries its body
         * only in the summary still yields something -- `KuaishouEnvelope.decide` decides which of the
         * two values is actually stored, and it prefers our own placeholder over a summary.
         */
        val TEXT_GETTERS = listOf("getText", "getSummary")
    }

    /**
     * Diagnostic state, deduplicated BY CLASS NAME rather than by a counter.
     *
     * 必看.md: a count-based cap was consumed by the conversation list before the chat page ever
     * bound, so the one thing the diagnostic existed to reveal was never printed. A Set of names means
     * the conversation list contributes ONE line however many rows it binds, and opening a chat is
     * therefore guaranteed to add the chat's types.
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
    private var classesProbed = false

    /** Cached accessors, so a fast chat scroll does no repeated method walking. */
    @Volatile
    private var textGetter: Method? = null

    /** True once the body-field scan has found the field it will keep re-reading. */
    @Volatile
    private var textFieldsTried = false

    @Volatile
    private var bodyField: java.lang.reflect.Field? = null
    /** Which branch [content] took on the last call, for the log. */
    @Volatile
    var lastReason: String = KuaishouEnvelope.Reason.NO_BODY
        private set

    // ------------------------------------------------------------------ probe

    /**
     * One-shot runtime probe: dumps what the reader ACTUALLY sees on a real object.
     *
     * This is the deliverable the task demands. It prints the RUNTIME class (the UI binds a
     * `KwaiMsgWrapper` subclass, which static analysis alone could not confirm), the whole hierarchy,
     * every accessor this reader depends on, and `getText()` vs `getSummary()` side by side -- the F3
     * item in KS_BUILD_PLAN.md, which is the single fact that decides which getter [content] prefers.
     */
    fun probe(msg: Any) {
        if (probed) return
        probed = true
        val sb = StringBuilder()
        sb.append("=== KUAISHOU message probe ").append(msg.javaClass.name).append(" ===\n")
        sb.append("  loader=").append(loaderName()).append('\n')
        sb.append("  isMessage=").append(isMessage(msg)).append('\n')

        // The whole hierarchy, so a Robust/Tinker-generated subclass is visible as itself.
        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            sb.append("  [").append(depth).append("] ").append(p.name).append('\n')
            p = p.superclass
            depth++
        }

        sb.append("  --- KwaiMsg accessors as present on THIS object ---\n")
        for (n in listOf(
            "getId", "getText", "getSummary", "getSender", "getRealFrom", "getTarget",
            "getTargetType", "getMsgType", "getSentTime", "getCreateTime", "getSeq",
            "getClientSeq", "getUnknownTips", "getSearchableContent",
        )) {
            sb.append("    ").append(n).append("() = ")
                .append(shorten(stringify(callAny(msg, n))))
                .append('\n')
        }

        // F3: the two body getters, side by side, so the first device run answers which to prefer.
        val t = callString(msg, "getText")
        val s = callString(msg, "getSummary")
        sb.append("  --- F3 body getters ---\n")
        sb.append("    getText()    len=").append(t?.length ?: -1)
            .append(" val=").append(shorten(t)).append('\n')
        sb.append("    getSummary() len=").append(s?.length ?: -1)
            .append(" val=").append(shorten(s)).append('\n')

        // F2: the timestamp, next to the device clock, so the unit is decided by observation.
        val st = callLong(msg, "getSentTime")
        val ct = callLong(msg, "getCreateTime")
        sb.append("  --- F2 timestamps ---\n")
        sb.append("    sentTime=").append(st).append(" branch=")
            .append(KuaishouEnvelope.timeBranch(st)).append('\n')
        sb.append("    createTime=").append(ct).append(" branch=")
            .append(KuaishouEnvelope.timeBranch(ct)).append('\n')
        sb.append("    System.currentTimeMillis()=").append(System.currentTimeMillis()).append('\n')

        // F1: the senders, so `isSend` can be decided by observation rather than assumption.
        sb.append("  --- F1 identity ---\n")
        sb.append("    sender=").append(shorten(callString(msg, "getSender"))).append('\n')
        sb.append("    realFrom=").append(shorten(callString(msg, "getRealFrom"))).append('\n')
        sb.append("    target=").append(shorten(callString(msg, "getTarget")))
            .append(" targetType=").append(callInt(msg, "getTargetType")).append('\n')

        // The reader's own verdicts, so the first run shows them side by side.
        sb.append("  --- reader verdicts ---\n")
        sb.append("    content=").append(shorten(content(msg)))
            .append(" reason=").append(lastReason).append('\n')
        sb.append("    talker=").append(talker(msg)).append('\n')
        sb.append("    isSend=").append(isSend(msg)).append('\n')
        sb.append("    msgType=").append(typeOf(msg)).append('\n')
        sb.append("    msgId=").append(msgId(msg)).append('\n')
        sb.append("    createTime(ms)=").append(createTime(msg)).append('\n')
        sb.append("    isText=").append(isText(msg))
            .append(" isRecalled=").append(isRecalled(msg)).append('\n')
        sb.append("    placeholder=").append(
            KuaishouEnvelope.placeholderFor(msg.javaClass.name) ?: "<none>",
        ).append('\n')
        Log.i(TAG, sb.toString())
    }

    /** Reports the Robust patch state of the reader's carrier class. Callable from the install path. */
    fun probeClasses() {
        if (classesProbed) return
        classesProbed = true
        val loaded = try {
            Class.forName(MSG_CLASS, false, loader()) != null
        } catch (_: Throwable) {
            false
        }
        Log.i(
            TAG,
            "KUAISHOU classes: " + MSG_CLASS + " loadable=" + loaded +
                " | Robust patch state=" + patchState() +
                " (Kuaishou has no per-class changeQuickRedirect field; only 7 exist APK-wide, all " +
                "inside the framework proxy, so the state is read from PatchProxy's own API)",
        )
        Log.i(
            TAG,
            "KUAISHOU diagnostic getters to watch: getText/getSummary (F3), sentTime (F2), " +
                "sender/realFrom/target (F1) -- the probe prints all of them on the first message",
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
     * True when [msg] is a Kuaishou message object.
     *
     * `isAssignableFrom` rather than equality: the UI binds `KwaiMsgWrapper extends KwaiMsg`, and
     * Robust/Tinker can produce further subclasses. A direct equality test is exactly what silently
     * rejected every Douyin message that arrived as `ProxyMessage`.
     *
     * The fallback is an EXACT name match (`KuaishouEnvelope.MSG_CLASS`), never a package prefix --
     * see the same rule and its full reasoning in [SoulMsgAccess.isMessage], where a prefix match
     * would have accepted the unrelated helper classes `msg.a` / `msg.b` living in the same namespace.
     */
    fun isMessage(msg: Any?): Boolean {
        if (msg == null) return false
        return try {
            val base = Class.forName(MSG_CLASS, false, loader())
            base.isAssignableFrom(msg.javaClass)
        } catch (t: Throwable) {
            val exact = msg.javaClass.name == MSG_CLASS
            if (!exact) {
                noteOnce(
                    "isMessage-degraded",
                    "KUAISHOU: " + MSG_CLASS + " is not loadable (" + t.javaClass.simpleName +
                        "), so message identity is DEGRADED to an exact-name match only; " +
                        msg.javaClass.name + " was therefore NOT accepted. (A package-prefix match " +
                        "was rejected on purpose: accepting a non-message would push it through the " +
                        "whole pipeline and into the store. An empty feed is safer than a " +
                        "fabricated one.)",
                )
            }
            exact
        }
    }

    // ------------------------------------------------------------------ content

    /**
     * The chat line the user sees, a short placeholder for a known non-text message, or EMPTY.
     *
     * The whole "record only plain text" requirement lands here. Every classification decision is
     * delegated to [KuaishouEnvelope.decide], which is pure and unit tested, so this method's only job
     * is to read the object faithfully and report the branch it took.
     *
     * It NEVER returns the protobuf payload: `contentBytes` / `extra` / `localExtra` are byte arrays,
     * and a byte-array dump stored as a chat line is the Douyin 20 KB bug in a worse form.
     */
    fun content(msg: Any): String {
        if (!isMessage(msg)) {
            lastReason = KuaishouEnvelope.Reason.NOT_A_MESSAGE
            return ""
        }

        val text = readBody(msg)
        val summary = callString(msg, "getSummary")
        val recalled = isRecalled(msg)

        val d = KuaishouEnvelope.decide(
            className = msg.javaClass.name,
            text = text,
            // ==================== WHY THE SUMMARY IS WITHHELD HERE ==============================
            // `decide` keeps a last-resort branch that stores the summary when nothing else produced a
            // body. That branch must NOT be offered a display string for a class that HAS a `getText`,
            // or the localized-placeholder bug reappears through the back door -- which is exactly what
            // a unit test caught: for an unknown class with `getText() == ""` and a summary of
            // "[Photo]", fixing `readBody` alone still stored "[Photo]", because `decide` received the
            // raw summary independently.
            //
            // The evidence says withholding it is correct in general, not just for the classes we
            // happen to know: the host's pattern is that a NON-text message has an EMPTY `text` and a
            // PLACEHOLDER summary (KImageMsg.getSummary() -> getString(0x7f111182)), while a genuine
            // text message populates `text`. So "getText exists but is empty" means "no body", and a
            // non-empty summary at that point is a display string rather than content.
            //
            // The last-resort branch stays reachable only for a class that declares NO `getText` at all
            // (fixture: KwaiMsgSummaryOnly), so a future subclass carrying its body nowhere else still
            // yields something instead of being dropped silently.
            summary = if (declaresTextGetter(msg)) null else summary,
            recalled = recalled,
            // ==================== WHY THE NUMERIC TYPE IS PASSED TOO ============================
            // The class-name path inside `decide` only matches when the runtime object IS the concrete
            // subclass. `msgType` is read off the base class, so it is the same number whichever
            // subclass or wrapper the object is -- it survives the case the name does not.
            //
            // Read through the shared `callInt`, which yields -1 (`MSG_TYPE_UNAVAILABLE`) when the
            // getter is missing or throws, so a host change degrades to the name path rather than
            // inventing a type. See `KuaishouEnvelope.MSG_TYPE_KEYS` for the evidence behind the table.
            msgType = typeOf(msg),
        )
        lastReason = d.reason

        if (!d.store) {
            // Every rejection is explainable -- 必看.md: an exit with no log is indistinguishable
            // from code that never ran.
            noteOnce(
                "reject:" + KuaishouEnvelope.simpleName(msg.javaClass.name) + ":" + d.reason,
                "KUAISHOU: skipped a message (class=" +
                    KuaishouEnvelope.simpleName(msg.javaClass.name) +
                    " reason=" + d.reason +
                    " textLen=" + (text?.length ?: 0) +
                    " summaryLen=" + (summary?.length ?: 0) +
                    " msgType=" + typeOf(msg) +
                    ") -- nothing stored. The binary payload fields contentBytes/extra/localExtra " +
                    "were deliberately NOT read. If this line names a class that SHOULD carry text, " +
                    "the placeholder table in KuaishouEnvelope needs that subclass added (or, for a " +
                    "type the table cannot name, its msgType added to MSG_TYPE_KEYS).",
            )
        } else if (d.reason == KuaishouEnvelope.Reason.PLACEHOLDER) {
            noteType(
                KuaishouEnvelope.simpleName(msg.javaClass.name),
                "non-text -> " + d.text + " (msgType=" + typeOf(msg) + ")",
            )
        }
        return d.text
    }

    /**
     * The raw body string, WITHOUT classification.
     *
     * Field first, getter second: `getText()` was verified by disassembly to be a single
     * `iget-object text; return-object`, so the direct field read is the same value with one less
     * call. The getter is the fallback for a subclass that hides the field.
     */
    private fun readBody(msg: Any): String? {
        // Field route. `bodyField` caches the Field that WORKED; `textFieldsTried` records that the
        // scan itself already ran, so a message whose text is genuinely empty does not re-scan the
        // whole hierarchy on every call.
        val cachedField = bodyField
        if (cachedField != null) {
            try {
                val v = cachedField.get(msg)
                if (v is String && v.isNotEmpty()) return v
            } catch (_: Throwable) {
                bodyField = null
            }
        }
        if (!textFieldsTried) {
            for (name in TEXT_FIELDS) {
                val fld = findField(msg.javaClass, name) ?: continue
                val v = try {
                    fld.isAccessible = true
                    fld.get(msg)
                } catch (_: Throwable) {
                    null
                }
                if (v is String && v.isNotEmpty()) {
                    bodyField = fld
                    return v
                }
            }
            // Only mark the scan as done once a field was found; otherwise a subclass that declares
            // the field further down would never be discovered. The cost of re-scanning for messages
            // that truly have no text is bounded by the field-count walk, which is small.
            if (bodyField != null) textFieldsTried = true
        }

        // Getter route: `getText` first, then `getSummary` ONLY as a fallback for a subclass that
        // carries its body nowhere else. See TEXT_GETTERS for why the order was reversed.
        //
        // ⚠️ IMPORTANT: what this returns is offered to `KuaishouEnvelope.decide` as the TEXT value, so
        // a summary picked up here must not be able to masquerade as a real text body. The check below
        // guards exactly that: `getSummary` is accepted ONLY when the object declares no `getText()` at
        // all. For a class that HAS getText and returns empty (KImageMsg is the concrete case: it
        // overrides getSummary to a localized UI string and does not override getText), the summary is
        // deliberately NOT returned -- otherwise a stored row would read "[图片]" in the user's own
        // words, varying by device language. `decide` then gives it our stable placeholder instead.
        val cachedGetter = textGetter
        if (cachedGetter != null) {
            try {
                val v = cachedGetter.invoke(msg) as? String
                if (!v.isNullOrEmpty()) return v
            } catch (_: Throwable) {
                textGetter = null
            }
        }
        val textDeclared = declaresTextGetter(msg)
        for (name in TEXT_GETTERS) {
            // Skip the summary when the class could have answered through getText: a summary is a
            // display string, not content, and using it here would defeat the placeholder table.
            if (name != "getText" && textDeclared) continue
            val m = findNoArg(msg.javaClass, name) ?: continue
            val v = try {
                m.invoke(msg) as? String
            } catch (_: Throwable) {
                null
            }
            if (!v.isNullOrEmpty()) {
                textGetter = m
                noteOnce(
                    "body-getter:" + name,
                    "KUAISHOU body getter resolved: " + msg.javaClass.name + "." + name +
                        "() (len=" + v.length + ") -- F3 in KS_BUILD_PLAN.md asks the device probe " +
                        "to print getText and getSummary side by side; whichever the probe shows " +
                        "populated for a text message is the one that took effect here",
                )
                return v
            }
        }
        return null
    }

    /**
     * True when this message object EXPOSES a no-arg `getText()`.
     *
     * Single source of truth for "could this class have answered through the real text getter", shared
     * by [readBody] (which refuses the summary as a body in that case) and [content] (which withholds the
     * summary from the envelope's last-resort branch). Those two decisions MUST agree: if they diverge,
     * the localized-placeholder bug returns through whichever route is still open -- which is precisely
     * what happened when only [readBody] was fixed.
     *
     * A missing `getText` is reported once per class name, so a future subclass that genuinely lacks the
     * getter is visible in the log rather than silently routed down the last-resort path.
     */
    private fun declaresTextGetter(msg: Any): Boolean {
        val has = findNoArg(msg.javaClass, "getText") != null
        if (!has) {
            noteOnce(
                "no-getText:" + KuaishouEnvelope.simpleName(msg.javaClass.name),
                "KUAISHOU: " + msg.javaClass.name + " declares no getText(), so its getSummary() is " +
                    "used as a last resort. That is the ONLY case where a summary becomes the stored " +
                    "body -- for every class that HAS getText, an empty result means 'no text' and a " +
                    "non-empty summary is a localized display string, which must not be stored.",
            )
        }
        return has
    }

    /** The raw value of a named field, for the probe only. */
    private fun readFieldRaw(msg: Any, name: String): Any? = try {
        findField(msg.javaClass, name)?.let {
            it.isAccessible = true
            it.get(msg)
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ attributes

    /**
     * The conversation id this message belongs to, used to key the store.
     *
     * `target` is the verified conversation field (`KwaiMsg.target:String` paired with `targetType:I`),
     * and it names the SAME conversation for both directions of a chat -- which is what the store
     * needs, because a chat keyed by sender would split one conversation in two files.
     *
     * When `target` is blank, `seq`/`sender` are NOT used as substitutes: a value that is sometimes
     * blank or message-scoped would split one conversation across files, and the store cannot repair
     * that afterwards. A blank talker makes `onLiveMessage` reject the message loudly instead.
     */
    fun talker(msg: Any): String {
        // FIELD FIRST, getter second -- the same order `content()` uses, and for the same reason: the
        // field read cannot be broken by a subclass that overrides the getter with extra logic, and the
        // real class has BOTH (`target:Ljava/lang/String;` and `getTarget()Ljava/lang/String;`).
        //
        // Found by a unit test, not by review: the first version of this method read only
        // `getTarget()`, so an object carrying the field but not the getter produced NO talker and the
        // message was dropped -- the "history stays empty, logs look healthy" failure this file exists
        // to prevent. The field route is the direct read; the getter is the fallback.
        val fieldVal = readFieldRaw(msg, "target")
        val target = ((fieldVal as? String) ?: callString(msg, "getTarget"))?.trim().orEmpty()
        if (target.isNotEmpty()) return target
        noteOnce(
            "no-talker:" + msg.javaClass.name,
            "KUAISHOU: could not determine a conversation for " + msg.javaClass.name +
                " (both the `target` field and getTarget() are blank); the message is skipped rather " +
                "than filed under a guessed key, because a wrong key splits one conversation across " +
                "several history files",
        )
        return ""
    }

    /**
     * True when I sent the message.
     *
     * ============================ THIS IS THE WEAKEST READ IN THIS FILE ==========================
     * Kuaishou's sender identity was NOT established: the dex shows `sender`, `realFrom` and `target`
     * as plain strings with no documented "which one is me" relationship, and no entry point yielding
     * the logged-in uid was found (unlike Soul's `cn.soulapp.imlib.j0.g()`, which is verified static).
     *
     * So this does NOT guess. It requires a POSITIVE match against my own id, and since my id is not
     * obtainable it currently returns false and says so -- once, naming the fields involved. The
     * consequence is real and is recorded rather than hidden: every message is labelled as theirs, so
     * the prompt's "me / them" split is wrong until the device run (F1 in KS_BUILD_PLAN.md) identifies
     * the field. A fabricated direction would be indistinguishable from a correct one in the UI, and
     * the WeChat round already shipped exactly that bug.
     * =============================================================================================
     */
    fun isSend(msg: Any): Boolean {
        noteOnce(
            "isSend-unresolved",
            "KUAISHOU: the sender field could not be established (see F1 in KS_BUILD_PLAN.md); " +
                "isSend() returns false for every message, so the prompt will label both sides as " +
                "them until the device probe prints sender/realFrom/target side by side. No value is " +
                "guessed here on purpose -- a wrong direction looks exactly like a right one.",
        )
        return false
    }

    /** Kuaishou's message type. The subclass is the real type test; see [KuaishouEnvelope]. */
    fun typeOf(msg: Any): Int = callInt(msg, "getMsgType")

    /**
     * The message id, or 0L when unavailable.
     *
     * `KwaiMsg.id` is a BOXED `Ljava/lang/Long;` (verified), so this reads it as an object and
     * unboxes safely -- a `readField(...) as Long` cast would throw whenever it is null, which is the
     * normal state for a freshly bound message. 0L then makes the caller fall through to a synthetic
     * key instead of dropping the message, the same contract Douyin and Soul readers honour.
     */
    fun msgId(msg: Any): Long {
        val viaGetter = callAny(msg, "getId")
        val n = when (viaGetter) {
            is Long -> viaGetter
            is Number -> viaGetter.toLong()
            is String -> viaGetter.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        if (n != 0L) return n
        val raw = readFieldRaw(msg, "id")
        return when (raw) {
            is Long -> raw
            is Number -> raw.toLong()
            else -> 0L
        }
    }

    /**
     * Always 0L: Kuaishou exposes no separate server id.
     *
     * Same contract as the Douyin and Soul readers -- 0 keeps `ResultCache`'s key composition working
     * (it treats 0 as "absent") without a special case at every call site.
     */
    fun msgSvrId(msg: Any): Long = 0L

    /**
     * The message time, normalised to MILLISECONDS.
     *
     * `sentTime` is read first because it is the semantically correct field for history ordering
     * (`createTime` is when the record was created, which for a locally echoed message can precede the
     * send). The unit is not confirmed statically, so [KuaishouEnvelope.toMillis] normalises by
     * magnitude and the branch is logged once -- a seconds value reaching
     * `AnalysisController.onLiveMessage` unnormalised would be rejected by its `< 1e12` gate and EVERY
     * message would be dropped, loudly but totally.
     */
    fun createTime(msg: Any): Long {
        val sent = callLong(msg, "getSentTime")
        val created = callLong(msg, "getCreateTime")
        val raw = if (sent > 0L) sent else created
        if (raw <= 0L) {
            noteOnce(
                "bad-time-zero:" + msg.javaClass.name,
                "KUAISHOU: no usable timestamp on " + msg.javaClass.name +
                    " (getSentTime/getCreateTime both <= 0); the shared live feed will reject this " +
                    "message at its epoch gate",
            )
            return 0L
        }
        val ms = KuaishouEnvelope.toMillis(raw)
        noteOnce(
            "time-unit:" + KuaishouEnvelope.timeBranch(raw),
            "KUAISHOU: timestamp " + raw + " -> " + ms + " ms (branch=" +
                KuaishouEnvelope.timeBranch(raw) + ", field=" +
                (if (sent > 0L) "sentTime" else "createTime") + "). If this branch is not 'millis', " +
                "the normalisation above is what keeps the epoch gate in onLiveMessage from " +
                "dropping every message.",
        )
        return ms
    }

    /**
     * Only plain text counts (the standing requirement), decided by whether a usable body exists.
     *
     * The type NUMBER is deliberately not used: the dex has no `MsgType` enum class and no
     * `MSG_TYPE_*` constants, and type numbers 2/5/6/7/14 never appear in any of the 94 protocol
     * constructors. So there is no number to trust. Requiring the resolved body to be a real chat line
     * (not a placeholder) is the only honest test, exactly as the Soul reader concluded for its own
     * missing constants.
     */
    fun isText(msg: Any): Boolean {
        if (!isMessage(msg)) return false
        // content() resolves the body and records WHY it accepted or rejected it, so the verdict is
        // read off lastReason rather than re-deriving the classification (which would risk the two
        // disagreeing). A placeholder is NOT text: the requirement is plain text only.
        content(msg)
        return when (lastReason) {
            KuaishouEnvelope.Reason.OK, KuaishouEnvelope.Reason.OK_SUMMARY -> true
            else -> false
        }
    }

    /**
     * True when the message was recalled.
     *
     * Detected by the SUBCLASS (`RecalledMsg`, whose `getName()` returns the verified literal
     * `imsdk_recalled_msg`, and the application-layer `KRecalledMsg` for msgType 11). There is no
     * per-message recall boolean on `KwaiMsg` -- `unknownTips` exists but carries an explanation for
     * unsupported types generally, so it is NOT treated as a recall flag; doing so would silently
     * swallow ordinary messages.
     */
    fun isRecalled(msg: Any): Boolean {
        if (!isMessage(msg)) return false
        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            val simple = KuaishouEnvelope.simpleName(p.name)
            if (simple in KuaishouEnvelope.RECALLED_NAMES) return true
            p = p.superclass
            depth++
        }
        return false
    }

    /** True when the object is a Kuaishou message AND usable as a chat line. */
    fun usable(msg: Any?): Boolean =
        isMessage(msg) && msg != null && !isRecalled(msg) && isText(msg)

    // ------------------------------------------------------------------ diagnostics

    /**
     * Logs one line per distinct KEY, never per event.
     *
     * The key embeds the class name and the reason, so a chat that binds many rows a second still
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
        Log.i(TAG, "KUAISHOU TYPE (new #" + seenTypes.size + "): " + name + " " + detail)
    }

    /**
     * The Robust patch state, read from `PatchProxy`'s own static API.
     *
     * Kuaishou has no per-class redirect field, so there is nothing to inspect the way the Soul reader
     * inspects `changeQuickRedirect`. Rather than pretend to a verdict, this tries the plausible
     * static entry points and reports `unknown` when none exists -- which is itself the useful answer,
     * because it tells the next reader that a patch-state check is not available here.
     */
    private fun patchState(): String {
        val cls = try {
            Class.forName(PATCH_CLASS, false, loader())
        } catch (_: Throwable) {
            return "PatchProxy-unloadable"
        }
        val names = listOf("isSupported", "isEnable", "isEnabled", "isPatched", "isSupport")
        val found = mutableListOf<String>()
        for (n in names) {
            val m = cls.declaredMethods.firstOrNull {
                it.name == n && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            } ?: continue
            val v = try {
                m.isAccessible = true
                m.invoke(null)
            } catch (_: Throwable) {
                null
            }
            found += n + "()=" + (v ?: "<threw>")
        }
        return if (found.isEmpty()) {
            "unknown (no static boolean probe on " + PATCH_CLASS + "; the apply* prologue is " +
                "call-conditional, not field-based, so it does not block Xposed hooks)"
        } else {
            found.joinToString(",")
        }
    }

    private fun shorten(s: String?): String {
        if (s == null) return "<null>"
        return if (s.length > 60) s.substring(0, 60) + "...(" + s.length + " chars)" else s
    }

    /** A printable form of a probed value without dumping a byte array wholesale. */
    private fun stringify(v: Any?): String = when (v) {
        null -> "<null>"
        is ByteArray -> "<byte[" + v.size + "]>"
        else -> v.toString()
    }

    // ------------------------------------------------------------------ reflection

    /**
     * Finds a no-arg method anywhere in the hierarchy.
     *
     * Walks superclasses because the real objects are subclasses of the dex-documented classes (the
     * UI binds `KwaiMsgWrapper`), and `getMethods()` alone would also drag in every inherited Object
     * method.
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

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 8) {
            for (f in p.declaredFields) {
                if (f.name == name) return f
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

    private fun callString(obj: Any, name: String): String? = callAny(obj, name) as? String

    private fun callInt(obj: Any, name: String): Int = when (val v = callAny(obj, name)) {
        is Int -> v
        is Number -> v.toInt()
        else -> -1
    }

    private fun callLong(obj: Any, name: String): Long = when (val v = callAny(obj, name)) {
        is Long -> v
        is Number -> v.toLong()
        else -> 0L
    }
}
