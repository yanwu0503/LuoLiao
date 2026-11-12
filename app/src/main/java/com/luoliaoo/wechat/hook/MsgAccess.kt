package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import java.lang.reflect.Method

/**
 * Message field access layer -- the HOST-AGNOSTIC FACADE.
 *
 * 5.0.0: this class now fronts BOTH hosts, because ~29 call sites in
 * `AnalysisController`, plus `ChatContext`, `MenuHook` and `ChatListHook`, all
 * read messages through `access.xxx(msg)`. Making the facade host-aware meant
 * ONE file changed instead of threading an `if (isDouyin)` through the 95 KB
 * controller -- and it keeps the public contract (content / talker / isSend /
 * type / time / id) identical for every existing caller.
 *
 * THE TWO HOSTS ARE OPPOSITES:
 *
 *   WeChat  -- obfuscated class (`com.tencent.mm.storage.e9`) but stable FIELD
 *              names (`field_content`, `field_talker`, ...). Reads fields, falls
 *              back to probing obfuscated no-arg accessors once.
 *   Douyin  -- NOT obfuscated (`com.bytedance.im.core.model.Message`) and the
 *              data sits behind ordinary getters: getContent() /
 *              getConversationId() / isSelf() / getMsgType() / getCreatedAt().
 *
 * So the SHAPE of the answer is shared and the reader is not: WeChat's logic
 * lives in this file (unchanged from 4.1.0), Douyin's in [DouyinMsgAccess].
 * Dispatch is by `HostApp.current`, resolved once per process.
 */
class MsgAccess(private val resolver: Resolver) {

    /**
     * Douyin's reader. Constructed eagerly (it is stateless until first use) so a
     * Douyin call never pays a lazy-init race on the main thread.
     */
    private val douyin = DouyinMsgAccess()

    /**
     * v6.0.0: Soul's reader, LAZY rather than eager.
     *
     * WHY LAZY WHERE DOUYIN IS EAGER: `DouyinMsgAccess` takes no arguments, so constructing it costs
     * nothing in any host. Soul's reader needs the [Resolver] (it resolves `cn.soulapp.imlib.*`
     * through the HOST classloader -- see 必看.md on why a host class must never be resolved through
     * the module's loader). Constructing it eagerly would build a Soul-specific object inside every
     * WeChat and Douyin process for no reason; lazy means it exists only where it is used.
     *
     * `by lazy` is thread-safe by default, which matters because the live-history feed and the
     * analysis path can both reach it from different threads.
     */
    private val soul: SoulMsgAccess by lazy { SoulMsgAccess(resolver) }

    /**
     * v7.0.0: Kuaishou's reader, LAZY for the same reason as [soul] -- it needs the [Resolver] to
     * reach `com.kwai.imsdk.*` through the HOST classloader, and building it eagerly would create a
     * Kuaishou-specific object inside every WeChat / Douyin / Soul process for nothing.
     */
    private val kuaishou: KuaishouMsgAccess by lazy { KuaishouMsgAccess(resolver) }

    /**
     * v8.0.0: QQ's reader, LAZY for the same reason as [soul] and [kuaishou] -- it needs the [Resolver]
     * to reach `com.tencent.qqnt.kernel.nativeinterface.*` through the HOST classloader, and building it
     * eagerly would create a QQ-specific object inside every WeChat / Douyin / Soul / Kuaishou process
     * for nothing.
     */
    private val qq: QQMsgAccess by lazy { QQMsgAccess(resolver) }

    /**
     * v9.3.0: Xiaohongshu's reader, LAZY for the same reason as [soul], [kuaishou] and [qq] -- it needs
     * the [Resolver] to reach `com.xingin.chatbase.bean.MsgUIData` through the HOST classloader, and
     * building it eagerly would create a Xiaohongshu-specific object inside every other host's process
     * for nothing.
     */
    private val xiaohongshu: XiaohongshuMsgAccess by lazy { XiaohongshuMsgAccess(resolver) }

    /**
     * v9.3.0: true when this process is Xiaohongshu, so every method below routes to [xiaohongshu].
     *
     * ================== WHY THIS GATE IS LOAD-BEARING, NOT TIDYING ==================
     * The FIFTH instance of exactly the failure this file has recorded four times (Douyin, Soul,
     * Kuaishou, QQ), and it was observed live on the device before this gate existed:
     *
     *     16:52:55.431  xhs: message from the binder constructor (#1) --
     *                   com.xingin.chatbase.bean.MsgUIData text='ok' id=0 chat=625ed046...
     *     16:52:56.406  target text= fromMe=false        <== the message was captured, then read as EMPTY
     *
     * The capture worked perfectly -- `XiaohongshuHooks` had the real `MsgUIData`, text and all -- but
     * every READ goes through this facade, and without this gate a `MsgUIData` fell through to the
     * WECHAT arm at the bottom of each method. That arm looks for the field `field_content` and then
     * probes WeChat's obfuscated accessors (`j`/`t0`/`u0`/`y0`/`z0`/`U1`); a `MsgUIData` has none of
     * them -- its body is `strMsg` -- so `content()` returned "" and the card rendered empty while every
     * log line looked healthy.
     *
     * The user-visible symptom is the one described in the four comments below: the 分析 item appears,
     * the tap registers, and the analysis reports 「未找到可分析的文本消息」. Nothing anywhere says "a
     * reader was missing", which is why each host has needed this gate added explicitly.
     *
     * IT MATTERS MOST HERE because Xiaohongshu's field names are the least guessable of any host: the
     * body is `strMsg`, the conversation is `chatId`, the timestamp is `creatTime` (the host's own
     * misspelling) and the id is `msgId`. No generic fallback could have found them.
     * ================================================================================
     */
    private val isXiaohongshu: Boolean
        get() = com.luoliaoo.wechat.core.HostApp.current ==
            com.luoliaoo.wechat.core.HostApp.XIAOHONGSHU

    /** True when this process is Douyin, so every method below routes to [douyin]. */
    private val isDouyin: Boolean
        get() = com.luoliaoo.wechat.core.HostApp.current ==
            com.luoliaoo.wechat.core.HostApp.DOUYIN

    /**
     * v7.0.0: true when this process is Kuaishou, so every method below routes to [kuaishou].
     *
     * ================== WHY THIS GATE IS LOAD-BEARING, NOT TIDYING ==================
     * Exactly the failure recorded for Soul below, and it WILL reproduce here: without this gate a
     * Kuaishou `KwaiMsg` falls through to the WECHAT reader, which looks for `field_content` and then
     * probes WeChat's obfuscated accessors (`j`/`t0`/`u0`/`y0`/`z0`/`U1`). NONE of those exist on
     * `KwaiMsg` (verified against the APK: the class has `text`, `sender`, `target`, and no
     * `field_*` members at all), so `content()` returns "".
     *
     * The failure that produces is the worst kind: the 分析 item APPEARS and the tap does nothing,
     * because `AnalysisController.startAnalysisFor` sees blank text and reports 「未找到可分析的文本消息」,
     * while the live-history feed rejects every message at its blank-text gate so history stays empty
     * -- and every log line looks healthy.
     *
     * Same reasoning as `isDouyin`/`isSoul`: the reader is chosen by HOST, never by trying one and
     * falling back, because a wrong reader fails silently instead of loudly.
     * ================================================================================
     */
    private val isKuaishou: Boolean
        get() = com.luoliaoo.wechat.core.HostApp.current ==
            com.luoliaoo.wechat.core.HostApp.KUAISHOU

    /**
     * v6.0.0: true when this process is Soul, so every method below routes to [soul].
     *
     * ================== WHY THIS GATE IS LOAD-BEARING, NOT TIDYING ==================
     * Without it, a Soul `ImMessage` fell through to the WECHAT reader below. That reader looks for
     * `field_content` and then probes WeChat's obfuscated accessors (`t0`/`U0`/`U1`...), none of which
     * exist on a Soul object, so `content()` returned "".
     *
     * The failure that would have produced is the worst kind: the 分析 item APPEARS and the tap does
     * nothing, because `AnalysisController.startAnalysisFor` sees blank text and reports
     * 「未找到可分析的文本消息」. The live-history feed would likewise reject every message at its
     * blank-text gate, so history would stay empty while every log line looked healthy.
     *
     * Same reasoning as `isDouyin` above: the reader is chosen by HOST, never by trying one and
     * falling back, because a wrong reader fails silently instead of loudly.
     * ================================================================================
     */
    private val isSoul: Boolean
        get() = com.luoliaoo.wechat.core.HostApp.current ==
            com.luoliaoo.wechat.core.HostApp.SOUL

    /**
     * v8.0.0: true when this process is QQ, so every method below routes to [qq].
     *
     * ================== WHY THIS GATE IS LOAD-BEARING, NOT TIDYING ==================
     * The fourth instance of exactly the failure this file has now recorded three times (Douyin, Soul,
     * Kuaishou), and it is the one that would have shipped silently. Without this gate a QQ
     * `MsgRecord`/`AIOMsgItem` falls through to the WECHAT reader below, which looks for the field
     * `field_content` and then probes WeChat's obfuscated accessors (`j`/`t0`/`u0`/`y0`/`z0`/`U1`).
     * NONE of those exist on a QQ object -- the verified NT model has `elements`, `msgType`, `peerUid`
     * and not one `field_*` member (see [QQMsgAccess]'s header for the dumps) -- so `content()`
     * returned "".
     *
     * The failure that produces is the worst kind: the 分析 item APPEARS and the tap does nothing,
     * because `AnalysisController.startAnalysisFor` sees blank text and reports
     * 「未找到可分析的文本消息」, while the live-history feed rejects every message at its blank-text
     * gate so history stays empty -- and every log line looks healthy. Nothing says "a reader was
     * missing"; it looks like the user simply had no text messages.
     *
     * ORDER OF THE GATES BELOW: they are host-exclusive (one process is exactly one host), so the order
     * is not load-bearing for correctness -- but each is checked BEFORE the WeChat default arm, and the
     * `else` arm is WeChat by construction, never "any host we forgot".
     * ================================================================================
     */
    private val isQq: Boolean
        get() = com.luoliaoo.wechat.core.HostApp.current ==
            com.luoliaoo.wechat.core.HostApp.QQ

    private companion object {
        const val TAG = "LuoLiao"

        /** WeChat message type for plain text. */
        const val TYPE_TEXT = 1

        /**
         * v10.0.0: WeChat message type for a VOICE message (语音).
         *
         * 34 is WeChat's own number for voice and it is stable across versions -- the reference
         * implementation (`yanwai-main`) hardcodes `type = 34` in its SQL as well, because this value
         * lives in the DATABASE, where it cannot be obfuscated away.
         */
        const val TYPE_VOICE = 34

        /**
         * v10.0.0: the message field that carries the VOICE FILE NAME.
         *
         * WeChat REUSES `field_imgPath` for voice messages: for a voice it holds something like
         * "voice2/xxx.amr". That value is what the local `VoiceTransText` table keys on as `cmsgId`,
         * and it is also the second argument of the host's `text(msgId, fileToken)` call -- so a
         * wrong field here means "the transcript never matches", silently.
         */
        const val FIELD_VOICE_FILE = "field_imgPath"
    }

    /**
     * v10.0.0: WeChat's own message type number, read straight off the message object.
     *
     * WHY THIS EXISTS SEPARATELY FROM [typeOf]: [typeOf] is a host-dispatched facade whose WeChat arm
     * falls back to `getType()`. For the voice path we need the RAW number and a hard 0 when it cannot
     * be read -- the caller compares it against TYPE_VOICE, and a -1 default would be one more value to
     * reason about. Everything here is read through [readField], so it works on any host object.
     */
    fun rawTypeOf(msg: Any): Int {
        val v = readField(msg, "field_type")
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Short -> v.toInt()
            is Byte -> v.toInt()
            is Number -> v.toInt()
            else -> callInt(msg, "getType") ?: 0
        }
    }

    /**
     * v10.0.0: true when this message is a VOICE message.
     *
     * Only meaningful on WeChat -- the number 34 is WeChat's own encoding, and this module's other
     * hosts each number their types differently ([DouyinMsgAccess] records that Douyin renumbers them
     * between releases). So this is deliberately NOT added to the host dispatch above: the voice
     * feature is WeChat-only by construction, because the transcription ENGINE it drives
     * (`TransformComponent`) only exists in WeChat.
     */
    fun isVoice(msg: Any): Boolean = rawTypeOf(msg) == TYPE_VOICE

    /**
     * v10.1.0: true when this is a WeChat 「引用」 (quote) message.
     *
     * 【为什么需要单独的判据】引用消息在数据库里是 **type=57 的 appmsg**，不是 type=1 的纯文本，
     * 所以 [isText] 对它返回 **false** —— 结果是引用消息会被**所有** isText 门禁挡在门外
     * （本地记录不写、分析不收、多选读 UI 不显示）。用户报的"引用功能没修"正是这个形态。
     *
     * 【判据为什么用内容而不是 type 数字】与 [WeChatQuote] 一致，以 `<refermsg>` 这个
     * **结构特征**为准：type 分类号是微信的内部分类，版本之间并非绝对稳定；
     * 而"有没有被引用段"才是引用消息的定义。两者都满足时当然更稳，但只认结构不会误判。
     *
     * 【只在微信生效】其它宿主各有自己的引用表示（QQ 用元素类型、
     * 小红书/快手又是另一套），本函数不代它们做判断 —— 与 [isVoice] 同样的边界处理。
     */
    fun isQuote(msg: Any): Boolean {
        if (isXiaohongshu || isQq || isKuaishou || isSoul || isDouyin) return false
        return try {
            WeChatQuote.looksLikeQuote(content(msg))
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * v10.1.0: true when this message carries text the module should treat as a chat line.
     *
     * This is [isText] **plus** the引用 case. Every write/collect gate should use THIS rather than raw
     * `isText`, otherwise quotes keep being dropped exactly as before.
     *
     * 【为什么不直接把 isText 改宽】[isText] 的语义是"宿主意义上的纯文本消息"，
     * 别处（宿主路由测试等）依赖这个严格含义。引用是"**可当作文本分析的**消息"这一更宽的概念，
     * 所以另立一个名字，让两个概念各自诚实 —— 改 isText 会让别的判断悄悄变宽。
     */
    fun isAnalysableText(msg: Any): Boolean = isText(msg) || isQuote(msg)

    /**
     * ★ v10.3.0：这条消息是**图片/表情包**吗 —— 用于把它们也收进可分析列表。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【为什么必须单独有它：不加的话整个识图功能是死的，而且看不出为什么】
     *
     * 微信的图片消息是 **type=3**、表情包是 **type=47**，而 [isAnalysableText]
     * 只放行 type=1（文本）和 type=57（引用）。于是图片**根本进不了 collect 的列表** ——
     * 后果是一连串静默失效：
     *   * 「读取」预览里永远看不到图片行 → 用户按需求要找的那排「识图」开关**一个都不出现**；
     *   * 上下文里也没有图片 → 用户会以为"这功能没做"，而不是"图片被过滤掉了"。
     * 现象是"界面里什么都没有"，与"代码没写"完全一样，所以这个坑必须在这里堵住。
     *
     * 【为什么不直接放宽 isAnalysableText】
     * 那个函数还服务于**写入本地记录**（`recordHistory` / `recordOne`）。
     * 一旦放宽，历史库里会被塞进一条条 `[非文本消息]` —— 既没有信息量，
     * 还会把真正的文本记录冲淡。收进"这一次的上下文"和"永久存下来"是**两件事**，
     * 所以另立一个判据，让 collect 单独变宽，而写入路径保持原样。
     *
     * 【判据为什么要看 XML 而不是 type】与 [WeChatEnvelope] 一致：type 号在不同宿主/版本
     * 各不相同，而 `<img>` / `<emoji>` 标签是消息内容里的事实。非微信宿主的内容
     * 不是这种 XML，`isImage` 自然返回 false，不会误判。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    fun isImageMessage(msg: Any): Boolean = try {
        WeChatEnvelope.isImage(content(msg))
    } catch (_: Throwable) {
        false
    }

    /**
     * v10.0.0: the voice file name of a voice message, or "" when absent.
     *
     * See [FIELD_VOICE_FILE] for why this is `field_imgPath` and why getting it wrong fails silently.
     */
    fun voiceFile(msg: Any): String {
        val v = readField(msg, FIELD_VOICE_FILE)
        return if (v is String) v else ""
    }

    /**
     * ★ v10.3.0：一条**图片/表情包**消息的媒体文件名，没有则 ""。
     *
     * ─────────────────────────────────────────────────────────────────────────────
     * 【为什么直接复用 `field_imgPath`，而不是去找微信的图片专用字段】
     * 因为实测证明**它就是同一个字段**。微信在消息对象上只放了一个 `field_imgPath`，
     * 按消息类型赋予不同含义：语音消息里它是语音文件名（`voice2/…amr`），
     * 图片消息里它就是图片文件名。所以不需要、也不应该另找一个"图片字段"——
     * 去找只会引入一个可能不存在的名字，把"能读到"变成"读不到"。
     *
     * 【为什么单独起一个函数名而不是直接调 voiceFile】
     * 纯为了**可读性**：在图片代码里写 `voiceFile(msg)` 会让下一个人以为这是语音专用逻辑，
     * 从而在重构时把它删掉或加上 "type == 34" 的判断 —— 那会直接打断图片功能。
     * 两个名字指向同一份读取逻辑（本函数就是转发），既诚实又不会被误删。
     *
     * 【⚠️ 已知的不确定性，不要当成 bug】
     * `field_imgPath` 在图片/表情包消息上**具体存什么，尚未在真机上被逐条确认**：
     * 可能是文件名、可能是相对路径、也可能是空（图片还没下载时必然为空）。
     * 所以调用方必须把"拿到空值"当作**正常情况**处理 —— 那就是"这条消息现在没有可读的图"，
     * 不是错误。见 [com.luoliaoo.wechat.ai.ImageEncoder.collect] 的处理。
     */
    fun imageFile(msg: Any): String = voiceFile(msg)

    @Volatile
    private var contentGetter: Method? = null

    @Volatile
    private var probed = false

    private val contentCandidates = listOf("j", "t0", "u0", "y0", "z0", "U1")

    /**
     * One-shot probe: reports which accessor returns the body and which field
     * holds the talker. Logged once per process so the answers appear in the
     * LSPosed log.
     */
    fun probe(msg: Any) {
        // Douyin's reader has its own probe: it dumps the getters that ACTUALLY
        // exist on the object handed to the hook, which may be the ProxyMessage
        // subclass rather than the base class documented from the dex.
        if (isDouyin) {
            douyin.probe(msg)
            return
        }
        // v6.0.0 Soul: its own probe, for the same reason. It additionally dumps the CONTENT object's
        // runtime class (`getMsgContent()`) -- the single most valuable line for Soul, because the
        // content-class allow-list in SoulEnvelope decides whether a message is text or a card, and
        // the real class name is the only thing that can confirm the allow-list is complete.
        // (Static analysis could not confirm it: new subclasses may appear on any Soul update.)
        if (isSoul) {
            soul.probe(msg)
            return
        }
        // v7.0.0 Kuaishou: its own probe, for the same reason. It additionally prints getText() and
        // getSummary() SIDE BY SIDE plus the raw timestamp next to the device clock -- those are the
        // two facts (F3, F2 in KS_BUILD_PLAN.md) that cannot be settled statically and that decide
        // which getter content() prefers and whether the epoch gate will accept the time.
        if (isKuaishou) {
            kuaishou.probe(msg)
            return
        }
        // v8.0.0 QQ: its own probe, for the same reason -- and it is the one that additionally reports
        // the QFix patch state, because EVERY QQ business class carries an instance `$redirector_`
        // field (verified: `com.tencent.qqnt.aio.menu.ui.c` has it, as does the reader's carrier class).
        // A redirected getter is the one runtime condition that would make every read below return a
        // neutral value while the install log stays healthy, so it must be excluded on the device.
        if (isQq) {
            qq.probe(msg)
            return
        }
        if (probed) return
        probed = true

        val sb = StringBuilder()
        sb.append("=== message probe ").append(msg.javaClass.name).append(" ===\n")

        val wanted = listOf(
            "field_content", "field_talker", "field_isSend", "field_type",
            "field_createTime", "field_msgId", "field_msgSvrId", "field_imgPath",
        )
        var p: Class<*>? = msg.javaClass
        var depth = 0
        while (p != null && depth < 6) {
            for (f in p.declaredFields) {
                if (wanted.indexOf(f.name) < 0) continue
                try {
                    f.isAccessible = true
                    sb.append("  [field] ").append(f.name).append(" = ")
                        .append(f.get(msg)).append('\n')
                } catch (t: Throwable) {
                    sb.append("  [field] ").append(f.name).append(" read failed\n")
                }
            }
            p = p.superclass
            depth++
        }

        sb.append("  --- no-arg String methods ---\n")
        val all = LinkedHashSet<Method>()
        p = msg.javaClass
        depth = 0
        while (p != null && depth < 6) {
            for (m in p.declaredMethods) all.add(m)
            p = p.superclass
            depth++
        }
        for (m in all) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java) continue
            try {
                m.isAccessible = true
                sb.append("  ").append(m.name).append("() = ").append(m.invoke(msg)).append('\n')
            } catch (_: Throwable) {
                // inaccessible accessor
            }
        }

        Log.i(TAG, sb.toString())
    }

    fun typeOf(msg: Any): Int = when {
        // v9.3.0: Xiaohongshu first, same narrowest-test-first shape as the others.
        isXiaohongshu -> xiaohongshu.typeOf(msg)
        // v8.0.0: QQ second, same narrowest-test-first shape as the others.
        isQq -> qq.typeOf(msg)
        // v7.0.0: checked FIRST (narrowest test first). Order is host-exclusive, so no host can
        // borrow another's reader -- see the `isKuaishou` comment for the failure that produces.
        isKuaishou -> kuaishou.typeOf(msg)
        isSoul -> soul.typeOf(msg)
        isDouyin -> douyin.typeOf(msg)
        else -> callInt(msg, "getType") ?: -1
    }

    /**
     * Only plain text is analysed (per requirement).
     *
     * WeChat compares against its own TYPE_TEXT (1). Douyin's numbering differs
     * (text is 7 in `IMEnum$MsgType`) and is renumbered between releases, so
     * [DouyinMsgAccess.isText] also requires a non-blank body instead of trusting
     * the number alone.
     *
     * v6.0.0 Soul: same permissive strategy, for a stronger reason. `ChatMessage$MsgType` and
     * `TextMsg$TxtType` have NO constants in Soul's dex at all (their class_data is empty and no
     * `<clinit>` is reachable), so no text type number can be pinned down statically. Requiring a
     * non-blank body -- which `SoulEnvelope`'s content-class allow-list has already vetted -- is the
     * only honest test available.
     */
    fun isText(msg: Any): Boolean = when {
        isXiaohongshu -> xiaohongshu.isText(msg)
        isQq -> qq.isText(msg)
        isKuaishou -> kuaishou.isText(msg)
        isSoul -> soul.isText(msg)
        isDouyin -> douyin.isText(msg)
        else -> typeOf(msg) == TYPE_TEXT
    }

    /**
     * True when the message was sent by ME.
     *
     * field_isSend holds an INTEGER (the probe logged "field_isSend = 1"), not a Boolean.
     * The previous implementation only accepted Boolean, then fell back to a non-existent
     * obfuscated method "d3" -- so it always returned false and every message was labelled
     * 对方 in the prompt and the scope dialog.
     */
    fun isSend(msg: Any): Boolean {
        if (isXiaohongshu) return xiaohongshu.isSend(msg)
        if (isQq) return qq.isSend(msg)
        if (isKuaishou) return kuaishou.isSend(msg)
        if (isSoul) return soul.isSend(msg)
        if (isDouyin) return douyin.isSend(msg)
        val v = readField(msg, "field_isSend")
        return when (v) {
            is Boolean -> v
            is Int -> v != 0
            is Long -> v != 0L
            is Short -> v.toInt() != 0
            is Byte -> v.toInt() != 0
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    fun msgId(msg: Any): Long {
        if (isXiaohongshu) return xiaohongshu.msgId(msg)
        if (isQq) return qq.msgId(msg)
        if (isKuaishou) return kuaishou.msgId(msg)
        if (isSoul) return soul.msgId(msg)
        if (isDouyin) return douyin.msgId(msg)
        val n = callLong(msg, "getMsgId")
        if (n != null) return n
        val v = readField(msg, "field_msgId")
        if (v is Long) return v
        return 0L
    }

    fun msgSvrId(msg: Any): Long {
        // Douyin's Message has no server-id field; 0 keeps ResultCache's key
        // composition working (it treats 0 as "absent") without a special case
        // at every call site.
        // v6.0.0 Soul: same shape -- ImMessage has no server id either, so 0 as well.
        // v7.0.0 Kuaishou: same again -- KwaiMsg carries `id` and `seq` but no server id.
        // v8.0.0 QQ: same again -- MsgRecord carries msgId/msgSeq/clientSeq/cntSeq but no server id
        // (verified against the 66-field dump), so [QQMsgAccess.msgSvrId] returns 0 by contract.
        if (isXiaohongshu) return xiaohongshu.msgSvrId(msg)
        if (isQq) return qq.msgSvrId(msg)
        if (isKuaishou) return kuaishou.msgSvrId(msg)
        if (isSoul) return 0L
        if (isDouyin) return 0L
        val v = readField(msg, "field_msgSvrId")
        if (v is Long) return v
        return 0L
    }

    fun createTime(msg: Any): Long {
        // v8.0.0 QQ: `MsgRecord` has TWO time fields (`msgTime` and `timeStamp`) whose unit could not be
        // confirmed statically, so [QQMsgAccess.createTime] picks and normalises through
        // [QQEnvelope.toMillis]. This is the same hazard as Soul below: an un-normalised seconds value
        // would fall under the live feed's epoch gate and silently drop EVERY message.
        if (isXiaohongshu) return xiaohongshu.createTime(msg)
        if (isQq) return qq.createTime(msg)
        // v6.0.0 Soul: `SoulMsgAccess.createTime` normalises the magnitude through
        // `SoulEnvelope.toMillis`, because Soul's time unit (seconds vs milliseconds) could NOT be
        // confirmed statically. This matters downstream: the shared live feed rejects any time below
        // a plausible epoch, so an un-normalised seconds value would silently drop EVERY message.
        if (isKuaishou) return kuaishou.createTime(msg)
        if (isSoul) return soul.createTime(msg)
        if (isDouyin) return douyin.createTime(msg)
        val n = callLong(msg, "getCreateTime")
        if (n != null) return n
        val v = readField(msg, "field_createTime")
        if (v is Long) return v
        return 0L
    }

    fun talker(msg: Any): String {
        // Douyin spells the conversation `getConversationId()`; there is no
        // `field_talker` on its message object at all.
        // v6.0.0 Soul: `ChatMessage.sessionId` is the conversation id, with `ImMessage.getOtherId()`
        // as the fallback. The value MUST be stable for a given conversation, because HistoryStore
        // groups records by talker -- an unstable value splits one conversation into several files.
        // v7.0.0 Kuaishou: `KwaiMsg.target` is the conversation id (paired with `targetType`), and it
        // names the SAME conversation for both directions -- which the store needs, because a chat
        // keyed by sender would split one conversation in two.
        // v8.0.0 QQ: `MsgRecord` offers THREE candidate identifiers (`peerUid` String, `peerUin` long,
        // `peerName`), and which one is stable for BOTH a C2C and a group chat could not be settled
        // statically. [QQMsgAccess.talker] decides and logs the branch; the store only needs the value
        // to be stable per conversation and identical in both directions.
        if (isXiaohongshu) return xiaohongshu.talkerId(msg)
        if (isQq) return qq.talker(msg)
        if (isKuaishou) return kuaishou.talker(msg)
        if (isSoul) return soul.talker(msg)
        if (isDouyin) return douyin.talker(msg)
        val v = readField(msg, "field_talker")
        if (v is String) return v
        return callString(msg, "t0") ?: ""
    }

    fun content(msg: Any): String {
        // v8.0.0 QQ: THIS is the line t21 exists for. QQ is the FIFTH host and the fourth time this
        // exact omission has appeared in this file -- Douyin's `ProxyMessage`, Soul's `ImMessage` and
        // Kuaishou's `KwaiMsg` were each briefly read by the WeChat arm below, returning "" while the
        // menu item appeared normally. See `isQq` above for the full failure description.
        //
        // QQ needs it more than any of them, for a structural reason: its chat line is not even on the
        // message object. It lives in `MsgRecord.elements[].textElement.content`, so the WeChat reader
        // could not reach it even in principle -- `field_content` does not exist and no amount of
        // accessor probing would find a body. [QQMsgAccess] walks the element chain and refuses raw
        // payloads (a market face's "text" is a JSON blob -- the Douyin 20 KB bug).
        // v9.3.0 Xiaohongshu: THE line that decides whether tapping 分析 does anything, for the exact
        // reason spelled out for QQ / Kuaishou / Soul below. Verified on the device: the binder captured
        // `MsgUIData text='ok'` and this method still returned "" because the object fell through to the
        // WeChat arm, which looks for `field_content` and probes `j`/`t0`/`u0`/`y0`/`z0`/`U1` -- none of
        // which exist on `MsgUIData`, whose body is the field `strMsg`.
        if (isXiaohongshu) return xiaohongshu.content(msg)
        if (isQq) return qq.content(msg)
        // v7.0.0 Kuaishou: THIS is the line that decides whether tapping 分析 does anything, for the
        // exact reason spelled out for Soul below. Without it a `KwaiMsg` reaches the WeChat reader,
        // which looks for `field_content` and then probes `j`/`t0`/`u0`/`y0`/`z0`/`U1` -- none of
        // which exist on `KwaiMsg`, so this returned "" and the analysis reported
        // 「未找到可分析的文本消息」 while the menu item had appeared normally.
        if (isKuaishou) return kuaishou.content(msg)
        // v6.0.0 Soul: this is THE line that decides whether tapping 分析 does anything. Without it a
        // Soul message reached the WeChat reader below, which looks for `field_content` and then
        // probes WeChat-only accessors (`t0`/`U0`/`U1`...) -- none of which exist on a Soul object --
        // so this returned "" and the analysis reported 「未找到可分析的文本消息」 while the menu item
        // had appeared normally.
        if (isSoul) return soul.content(msg)
        if (isDouyin) return douyin.content(msg)
        val f = readField(msg, "field_content")
        if (f is String) return f

        val cached = contentGetter
        if (cached != null) {
            try {
                return cached.invoke(msg) as? String ?: ""
            } catch (_: Throwable) {
                // fall through and re-probe
            }
        }

        for (name in contentCandidates) {
            val m = findNoArgStringMethod(msg.javaClass, name) ?: continue
            val v = try {
                m.invoke(msg) as? String
            } catch (_: Throwable) {
                null
            }
            if (v != null && v.isNotEmpty()) {
                contentGetter = m
                Log.i(TAG, "content accessor resolved: " + msg.javaClass.name + "." + name + "()")
                return v
            }
        }
        return ""
    }

    private fun findNoArgStringMethod(cls: Class<*>, name: String): Method? {
        var p: Class<*>? = cls
        var depth = 0
        while (p != null && depth < 6) {
            for (m in p.declaredMethods) {
                if (m.name == name && m.parameterCount == 0 && m.returnType == String::class.java) {
                    m.isAccessible = true
                    return m
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
        while (p != null && depth < 6) {
            for (f in p.declaredFields) {
                if (f.name == name) {
                    return try {
                        f.isAccessible = true
                        f.get(obj)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            p = p.superclass
            depth++
        }
        return null
    }

    private fun callInt(obj: Any, name: String): Int? = try {
        obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(obj) as? Int
    } catch (_: Throwable) {
        null
    }

    private fun callLong(obj: Any, name: String): Long? = try {
        obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(obj) as? Long
    } catch (_: Throwable) {
        null
    }

    private fun callString(obj: Any, name: String): String? = try {
        obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(obj) as? String
    } catch (_: Throwable) {
        null
    }
}
