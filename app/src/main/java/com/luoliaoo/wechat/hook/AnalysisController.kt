package com.luoliaoo.wechat.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.OpenAiClient
import com.luoliaoo.wechat.ai.PromptBuilder
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.core.HostApp
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.Resolver
import com.luoliaoo.wechat.data.AnalysisResult
import com.luoliaoo.wechat.data.CardStore
import com.luoliaoo.wechat.data.ChatTurn
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.DbReader
import com.luoliaoo.wechat.data.HistoryStore
import com.luoliaoo.wechat.data.ResultCache
import com.luoliaoo.wechat.ui.CardRenderer
import com.luoliaoo.wechat.ui.ScopeDialog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Orchestrates the analysis flow:
 *   long-press -> 分析 -> scope dialog -> gather messages -> call AI
 *   -> render the card below the pressed message -> persist the result.
 *
 * CARD STATE IS KEYED BY (talker#msgId) AND NEVER STORED ON A VIEW. That is what allows a card
 * to survive scrolling (the row is recycled), and a restart (only ids are persisted).
 */
class AnalysisController(
    private val app: Context,
    
    private val resolver: Resolver,
    private val access: MsgAccess,
) {

    companion object {
        private const val TAG = "LuoLiao"

        /**
         * Cap on rejection diagnostics.
         *
         * A chat list re-binds constantly, so an uncapped log would emit thousands of lines and bury
         * the evidence it exists to provide. Enough lines to see the PATTERN (which field is empty)
         * without flooding.
         */
        private const val DIAG_REJECT_LIMIT = 6

        @Volatile
        private var instanceRef: AnalysisController? = null

        val instance: AnalysisController? get() = instanceRef

        fun setInstance(c: AnalysisController) {
            instanceRef = c
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val chatContext = ChatContext(access, resolver.classLoader())

    private val binder = com.luoliaoo.wechat.ui.CardBinder(resolver.classLoader())

    init {
        // item 4: the binder resolves each row's message with adapter.getItem(position), which needs no
        // knowledge of the adapter's internals. Feeding the live recorder from there makes live history
        // independent of the chat-adapter hook -- the path that was silently failing.
        try {
            binder.liveMessageSink = { msg -> onLiveMessage(msg) }
            Log.i(TAG, "live history: the binder is feeding the recorder")
        } catch (t: Throwable) {
            Log.w(TAG, "attaching the live sink failed: " + t.message)
        }
    }

    /** Card states, keyed by "talker#msgId". */
    val cards = ConcurrentHashMap<String, CardState>()

    /** Follow-up conversations, keyed the same way (item 9). */
    private val turns = ConcurrentHashMap<String, MutableList<ChatTurn>>()

    /** Scope + ids per card. No View is stored here. */
    private class Scope(
        val talker: String,
        val msgId: Long,
        /**
         * item 1: the token counts of the analysis, kept with the card so the usage line survives a
         * WeChat restart exactly like the result does.
         */
        val usagePrompt: Int = 0,
        val usageCompletion: Int = 0,
        /** The message text; the card uses it to verify a row before attaching. */
        val text: String,
        val mode: ScopeMode,
        val up: Int,
        val down: Int,
        val startMs: Long,
        val endMs: Long,
    )

    private val scopes = ConcurrentHashMap<String, Scope>()

    /** Last known RecyclerView, used by reconcile. */
    @Volatile
    private var lastRv: Any? = null

    /**
     * Authoritative row -> card key mapping.
     *
     * Filled in onRowBound, where the real message object is available from the ViewHolder. This
     * is what reconcile must trust: reading the message from View.tag produced null keys (WeChat
     * does not tag item views), and a null key used to trigger deletion of the card that had just
     * been drawn -- the reason no card stayed visible.
     */
    private val rowKeys = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, String>(),
    )

    /**
     * Anchor hint per key: the message View captured at long-press time.
     *
     * This is what made v1.0.25 work and what v1.0.26 threw away. It is only a HINT (the view may
     * die when its row is recycled), but while it is alive it is the most direct way to draw a
     * card, needing no message lookup at all.
     */
    private val anchors = ConcurrentHashMap<String, java.lang.ref.WeakReference<View>>()

    /**
     * The long-pressed View per key.
     *
     * This is v1.0.25's mechanism and it is what makes the card appear: the AI reply arrives tens
     * of seconds later, and the anchor captured at long-press time is the only reliable way to say
     * where the card belongs. Row/message reverse lookups are tried after this, never before.
     */
    private class AnchorRecord(
        val key: String,
        val rv: Any?,
        val anchor: View,
        /** The message this anchor was captured for; used to detect a recycled view. */
        val msgId: Long,
        val text: String,
    )

    private val records = ConcurrentHashMap<String, AnchorRecord>()

    /** Rows we successfully drew a card into, so pass 1 can find them again. */
    private val knownRows = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, String>(),
    )

    /**
     * The most recent messages seen on the chat screen, newest last.
     *
     * Used to name the CURRENT conversation without analysing anything; capped so it cannot grow.
     */
    private val recentMessages = java.util.concurrent.ConcurrentLinkedDeque<Any>()

    /** item 1: messages staged for the next history flush, per talker. */
    private val livePending =
        java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashMap<Long,
            com.luoliaoo.wechat.data.HistoryStore.Line>>()

    /** item 1: the last flush time per talker, so writes are batched to one per second. */
    private val liveLastFlush = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** item 1: the chat the live feed most recently saw a message in. */
    @Volatile
    private var currentTalkerHint: String = ""

    /** The last talker reported by detectCurrentTalker, so the log is not spammed. */
    @Volatile
    private var lastDetectedTalker: String = ""

    /** item 1: one background thread for all history writes. */
    private object LiveWriter {
        private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "LuoLiao-history").apply { isDaemon = true }
        }

        fun submit(work: () -> Unit) {
            try {
                exec.execute(work)
            } catch (_: Throwable) {
                // a rejected write is not worth crashing over
            }
        }
    }

    /** item 2: guards the 2-second history timer so it is started once. */
    @Volatile
    private var liveTimerStarted = false

    /**
     * items: identifies the chat screen the ring currently describes.
     *
     * The hook calls `enterChatScreen` whenever the ADAPTER changes, which is what tells us the user
     * moved to another conversation. The ring is cleared at that moment, so it can never mix two chats --
     * the cause of "chat B shows chat A's records".
     */
    @Volatile
    private var chatAdapterId: String = ""

    /** A counter bumped on every chat-screen change, used to ignore stale reads. */
    @Volatile
    private var chatGeneration: Int = 0

    /**
     * Called when the chat screen changes (a new adapter instance).
     *
     * Clears the ring and the talker hint, so nothing from the previous conversation can leak into the
     * next one.
     */
    fun enterChatScreen(adapterId: String) {
        if (adapterId == chatAdapterId) return
        chatAdapterId = adapterId
        chatGeneration++
        try {
            recentMessages.clear()
        } catch (_: Throwable) {
            // best effort
        }
        currentTalkerHint = ""
        Log.i(TAG, "chat screen changed: adapter=" + adapterId +
            " gen=" + chatGeneration + " (ring cleared)")
    }

    /** Called by the chat hook whenever a message row is bound. */
    fun noteRecentMessage(msg: Any) {
        try {
            recentMessages.addLast(msg)
            while (recentMessages.size > 40) recentMessages.pollFirst()
        } catch (_: Throwable) {
            // a full ring is not worth failing over
        }
    }

    /** item 1: the most recently stored usage, replayed into the panel after a restart. */
    @Volatile
    private var restoreUsage: Pair<Int, Int>? = null

    private val running = ConcurrentHashMap.newKeySet<String>()


    @Volatile
    private var lastMode: ScopeMode = ScopeMode.COUNT

    sealed interface CardState {
        object Loading : CardState
        data class Done(val result: AnalysisResult) : CardState
        data class Error(val message: String) : CardState
    }

    fun hostClassLoader(): ClassLoader = resolver.classLoader()

    init {
    }


    /** Cached WeChat id of the logged-in user (item 5). */
    @Volatile
    private var cachedMyWxid: String? = null

    /**
     * My own WeChat id.
     *
     * WeChat keeps it in the account storage; the field name is not obfuscated ("field_username"
     * on the account info object). We try a couple of well-known accessors and cache the result.
     * When it cannot be determined the prompt simply omits it -- analysis still works, and
     * isSend-based labelling (我 / 对方) already covers the one-to-one case.
     */
    private fun myWxid(): String {
        cachedMyWxid?.let { return it }
        val found = try {
            val at = Class.forName("android.app.ActivityThread", false, resolver.classLoader())
            val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val app = at.getDeclaredMethod("getApplication").invoke(thread)
            val ctx = app as android.content.Context

            // 1) the account info object held by the app
            val guess = tryAccountInfo(ctx)
            guess ?: ""
        } catch (_: Throwable) {
            ""
        }
        cachedMyWxid = found
        if (found.isNotEmpty()) Log.i(TAG, "myWxid resolved: " + found)
        else Log.i(TAG, "myWxid unavailable; prompt will omit it")
        return found
    }

    /** Looks for a user object exposing field_username / getUsername() in the app's storage. */
    private fun tryAccountInfo(ctx: android.content.Context): String? {
        // WeChat stores the account under a shared preference "system_config_prefs" style key.
        try {
            for (name in listOf("system_config_prefs", "login_weixin_username", "auth_info_key_prefs")) {
                val sp = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                val v = sp.getString("login_weixin_username", null)
                    ?: sp.getString("_auth_uin", null)
                    ?: sp.getString("main_authenticate_uuid", null)
                if (!v.isNullOrBlank() && v.length > 3) return v
            }
        } catch (_: Throwable) {
            // try the next source
        }
        // Fall back to the accounts storage through reflection on the account helper.
        return null
    }

    /** Loads persisted cards so they can be reconciled after a restart (item 7). */
    fun restoreFromDisk() {
        try {
            CardStore.attachFrom(app)
            val saved = CardStore.all()
            var n = 0
            for (s in saved) {
                scopes[s.key] = Scope(
                    // item 2: keep the stored message text, so the record list shows the same title
                    // before and after a restart instead of losing it.
                    talker = s.talker,
                    msgId = s.msgId,
                    // item 2: the usage MUST be restored too. It was saved and read back correctly, but
                    // this constructor never copied it, so the in-memory value was 0 after a restart and
                    // the panel showed "暂无".
                    usagePrompt = s.usagePrompt,
                    usageCompletion = s.usageCompletion,
                    text = s.text,
                    mode = s.mode,
                    up = s.up,
                    down = s.down,
                    startMs = s.startMs,
                    endMs = s.endMs,
                )
                when {
                    s.result != null -> cards[s.key] = CardState.Done(s.result)
                    s.error != null -> cards[s.key] = CardState.Error(s.error)
                    else -> continue
                }
                if (s.turns.isNotEmpty()) {
                    turns[s.key] = ArrayList(s.turns)
                }
                // item 1: hand the stored usage to the panel so the line is right after a restart.
                if (s.usagePrompt > 0 || s.usageCompletion > 0) {
                    restoreUsage = s.usagePrompt to s.usageCompletion
                    // item 2: seed the panel's per-key map as well, so EVERY restored record shows its
                    // own usage rather than only the most recent one.
                    com.luoliaoo.wechat.ui.FloatingPanel.setUsage(
                        s.key, s.usagePrompt, s.usageCompletion,
                    )
                }
                n++
            }
            Log.i(TAG, "restoreFromDisk: " + n + " card(s) restored (of " + saved.size + ")")

            // item 2: make the restored records visible immediately. They were already in `cards`, but
            // the panel's list is built from providers that used to be installed lazily on first use, so
            // nothing appeared until an analysis ran. Installing them here means the history is on screen
            // as soon as the app starts.
            if (n > 0) {
                main.post {
                    try {
                        installPanelProviders()
                        // item 1: replay the stored usage so the line is not blank after a restart.
                        restoreUsage?.let { (pq, cq) ->
                            com.luoliaoo.wechat.ui.FloatingPanel.setUsage(pq, cq)
                        }
                        com.luoliaoo.wechat.ui.FloatingPanel.refresh()
                        Log.i(TAG, "restoreFromDisk: panel refreshed with " + n + " record(s)")
                    } catch (t: Throwable) {
                        Log.w(TAG, "restoreFromDisk: panel refresh failed: " + t.message)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restoreFromDisk failed: " + t.message)
        }
    }

    // ---------------------------------------------------------------- entry

    /** Preferred entry: the menu hook captured the exact message and its view. */
    fun startAnalysisFor(msg: Any, view: View?) {
        val anchor = view
        if (anchor == null) {
            Log.w(TAG, "no anchor view; cannot show the scope dialog")
            return
        }
        val activity = findActivity(anchor.context)
        if (activity == null) {
            Log.w(TAG, "no Activity found; cannot show the scope dialog")
            return
        }
        /* ★ v9.1.0：免责声明闸门（第三条，也是**最可靠**的一条）。
         *
         * 为什么前面两处拦了这里还要拦：MenuHook.onOurRowTapped 与 FloatingPanel.openPanel
         * 各自只覆盖「某个宿主的某条入口」，而本函数是**全部 5 个宿主
         * （微信 / 抖音 / QQ / Soul / 快手）所有分析路径的共同终点** ——
         * DouyinHooks、KuaishouHooks、QQHooks、SoulHooks、MenuHook 最终都调到这里。
         * 拦在这里，任何我没预料到的入口都自动被覆盖。
         *
         * 这是纵深防御：入口层拦是为了**不弹声明就不让用户看到界面**（体验），
         * 这里是**最后一道保险** —— 万一某个宿主的入口改了、或将来新增第 6 个宿主，
         * 免责声明依然不会被绕过。
         *
         * 放在 activity 判空之后：ensureConsented 需要 Activity 才能弹窗。 */
        if (!com.luoliaoo.wechat.ui.DisclaimerDialog.ensureConsented(activity)) {
            Log.i(TAG, "disclaimer not accepted yet -- analysis refused")
            return
        }
        access.probe(msg)
        proceed(activity, anchor, msg)
    }


    /**
     * v2.7.0 (item 2): render a set of selected messages as the read UI's lines.
     *
     * Uses the module's own accessor so the text, the speaker and the formatting match every other entry
     * point. Messages without text are skipped, because there is nothing to analyse.
     */
    /**
     * v2.7.0 (item 2): render a set of selected messages as the read UI's lines.
     *
     * Uses the module's own accessor so the text, the speaker and the formatting match every other entry
     * point. Messages without text are skipped, because there is nothing to analyse.
     *
     * ============================== v10.0.0: 语音消息 ==============================
     *
     * 用户报：「长按语音然后仅选择信息，会出现乱码，而不是转文字的」。
     *
     * 【根因】本函数原来无条件走 `access.content(m)`，而微信语音消息的 `field_content`
     * **不是空的**，是整段 XML（真机日志实测）：
     *
     *     <msg><voicemsg endflag="1" voiceformat="4" voicelength="6694" length="9026"
     *          aeskey="20f359446e2bca93980fa4f44d6632df" voiceurl="7f0c00010220..."
     *          clientmsgid="..." isPlayed="true"></voicemsg></msg>
     *
     * 所以「空白就跳过」这道检查根本拦不住它，用户看到的"乱码"正是这段 XML。
     * 这与 onLiveMessage 里那个被写进本地记录的同类缺陷是**同一个根因**：
     * 微信语音没有"正文"，但它的 content 字段非空，于是所有"读 content"的地方都会拿到 XML。
     *
     * 【修法】语音走**专用分支**，且与 onLiveMessage 不同 —— 这里**不能异步**。
     * 本函数是同步返回 List<String> 的，多选读 UI 立刻要用这些行渲染。
     * 所以这里只做**同步可得的**两种取值：
     *   1. 微信本地库 `VoiceTransText` 里已经转好的文字（同步一次 SQL 查询，很快）；
     *   2. 取不到就给一个**诚实的占位符**，而不是把 XML 丢给用户。
     * 绝不在这里触发转写：那要好几秒，会把 UI 卡住，也超出了"渲染选中行"该做的事。
     *
     * 【为什么不再退回 content】宁可显示「[语音]」也不要显示 aeskey/voiceurl：
     * XML 对用户和模型都是纯噪声（`PromptBuilder.SKIP_MARKERS` 里的 "msg" 也只是碰巧把它挡在
     * prompt 之外，UI 列表里照样是乱码）。占位符至少语义正确。
     */
    fun describeMessages(msgs: List<Any>): List<String> {
        val out = ArrayList<String>(msgs.size)
        for (m in msgs) {
            try {
                val who = if (access.isSend(m)) "\u6211" else "\u5bf9\u65b9"

                // ★ v10.1.0：统一走 displayText，**不再自己读 content()**。
                //
                // 这里原本是：
                //     if (access.isVoice(m)) { ...语音分支...; continue }
                //     val text = access.content(m)        <-- 第 5 处同一根因
                //
                // 【用户报的 bug】「多选状态下的引用有问题 显示 包含屏蔽词」。
                // 引用消息的 content 是整段 `<msg><appmsg>...`，于是：
                //   1. 这里原样吐出 XML；
                //   2. MenuHook 拿到这批行后**再筛一次屏蔽词**（它的位置在 describeMessages
                //      **之后**、弹窗**之前**，是刻意的"先过滤再弹窗"顺序）；
                //   3. "msg" 正是内置屏蔽词 -> 每条引用都被判"含屏蔽词" -> 全部被挡掉，
                //      全被挡光时那条 toast「选中的消息均包含屏蔽词」就弹出来了。
                //
                // 【为什么不能只在 MenuHook 那边放松过滤】那样做等于为了显示引用而**关掉用户自己的
                // 屏蔽词**，会把用户明确要挡的东西放进来。正确的修法和前面四处一样：
                // 在**源头**把 XML 换成正文，屏蔽词照旧作用在正文上。
                // 这样引用显示为 `[引用]标题"引用：正文"`，用户自定义的屏蔽词也仍然能命中它。
                //
                // 【语音分支为什么并入 displayText 也安全】displayText 内部对语音调用同一个
                // voiceTextForSelection（返回转写文字或 `[语音]` 占位符），行为与原来的专用分支一致。
                val text = displayText(m)
                if (text.isBlank()) continue
                out.add(who + "\uff1a" + text)
            } catch (_: Throwable) {
                // a single unreadable message must not stop the rest
            }
        }
        return out
    }

    /**
     * v10.0.0: 取一条消息**用于展示/分析**的文本，语音走专用分支。
     *
     * ══════════════════════════════════════════════════════════════════════════════════
     * 【为什么必须有这个统一入口 —— 这是一个被同一个根因咬了四次的地方】
     *
     * 微信语音消息（type=34）的 `field_content` **不是空的**，而是整段 XML：
     *     <msg><voicemsg endflag="1" voiceformat="4" voicelength="6694" length="9026"
     *          aeskey="..." voiceurl="..." clientmsgid="..." isPlayed="true"></voicemsg></msg>
     *
     * 于是**所有直接读 `content()` 的地方都会把这段 XML 当正文**：
     *   1. `onLiveMessage` —— 已修（语音提前分流 + 转写后写 `[语音转文字]xxx`）；
     *   2. `describeMessages`（多选读 UI）—— 用户报的「仅选择信息出现乱码」；
     *   3. 长按分析的目标行 / 仅选择信息预览 / 追问 —— 同一个 XML；
     *   4. `ChatContext.toLine`（**送进 AI 的那一份**）—— 也叫 [displayText]，见下。
     *
     * 前三次是各改一处，所以第 4 处又冒出来了。这个函数就是那个唯一入口：
     * **凡是"要显示或要发给模型"的文本都从这里取**，语音在源头就被换成非 XML 的内容。
     * 它是 public 的，正是为了让 [com.luoliaoo.wechat.hook.ChatContext] 复用**同一个实现** ——
     * 抄一份规则过去，下次改动就会漏掉一边。
     *
     * 【语音返回什么】本地库里已转好的文字（同步查一次，带 `[语音转文字]` 前缀，与写入本地
     * 记录的格式一致）；查不到就是 `[语音]` 占位符。**绝不返回 XML**。
     *
     * v10.1.0 追加：**引用消息**（type=57 的 appmsg）同样在这里就地转成人能读的一行，
     * 格式 `[引用]标题"引用：被引用的原文"` —— 详见 [WeChatQuote]。它是同一条原则的第五个实例：
     * 凡是"content 非空但内容不是给人看的话"的消息，都必须在这个唯一入口被换掉。
     * ══════════════════════════════════════════════════════════════════════════════════
     */
    fun displayText(msg: Any): String = try {
        when {
            access.isVoice(msg) -> voiceTextForSelection(msg)

            else -> {
                val raw = access.content(msg)
                /* v10.1.0：引用消息。
                 *
                 * 【为什么要在这里做，而不是只在写本地记录时做】这里是**唯一**的
                 * "要显示/要发给模型"出口（语音、长按预览、多选读 UI、追问都走它）。
                 * 只改写入端的话，用户会在"本地记录里对了、但分析时送的还是 XML"之间反复拉扯 ——
                 * 这个坑本文件已经被咬过四次，不再重复。
                 *
                 * 【不是引用消息时 parse 返回 null，raw 原样返回】—— 普通文本一个字节都不受影响。 */
                val quoted = WeChatQuote.parse(raw) ?: raw
                /* ★ v10.3.0：非文本消息的信封翻译。
                 *
                 * 【用户报的现象】「显示乱码 如 `<msg><immgaeskey=` 等等」。
                 * 图片消息的 `field_content` 是**原始 XML**（`<msg><img aeskey="..." .../></msg>`），
                 * 而在这之前它被当作"对方说的话"直接显示、并原样送进了提示词。
                 * 后果不只是难看：模型收到的内容是 aeskey 与 cdn 地址，
                 * 于是分析结果整个跑偏。
                 *
                 * 【为什么放在引用解析之后】顺序有讲究：引用消息的 XML 里**也**含 `<img>` 之类的
                 * 内嵌标签（"对方引用了你的图片"）。先让 WeChatQuote 把引用拆开、
                 * 拿到它内部真正的那段文本，再对结果做信封翻译 —— 否则会把整条引用消息
                 * 误判成一张图片，把用户真正想分析的那句话丢掉。
                 *
                 * 【普通文本完全不受影响】translate 只在内容以 `<` 开头时才可能改动它，
                 * 其余一律原样返回。 */
                WeChatEnvelope.translate(quoted)
            }
        }
    } catch (_: Throwable) {
        ""
    }

    /**
     * v10.0.0: 同步取一条语音的转写文字，供多选读 UI 渲染。
     *
     * 【为什么是同步的、且不触发转写】见 [displayText] 的说明 —— 这里要立刻返回。
     * 触发转写要好几秒（要上传、等服务器），会卡住 UI，也超出了"取一行文本"该做的事。
     *
     * 【查库为什么要快】它只是一次按主键的 LIMIT 1 查询，走微信已经打开的句柄（见 WeChatVoiceDb），
     * 没有开库、没有解密、没有网络。相比把 XML 渲染给用户，这点开销完全值得。
     *
     * @return 转写文字；没有则返回 `[语音]` 占位符（绝不返回 XML）
     */
    private fun voiceTextForSelection(msg: Any): String {
        val placeholder = "[\u8bed\u97f3]"   // [语音]
        try {
            val msgId = access.msgId(msg)
            val file = access.voiceFile(msg)
            val talker = access.talker(msg)
            val when_ = access.createTime(msg)

            // ---- 来源 1：微信自己的 VoiceTransText 表 ----
            //
            // ★★ v10.0.0 第二个 bug（用户报「读取读不到刚写入的转写，重启微信之后却可以」）★★
            //
            // 这里原本**只有**这一个来源，查不到就返回占位符 —— 这是错的，原因很隐蔽：
            // WeChatVoiceDb 依赖**微信自己已经打开的那个数据库句柄**（用 WeakReference 保存，≤4 个）。
            // 句柄一旦被 GC 回收、或微信还没在这个进程里查过 EnMicroMsg.db，existingText 就返回 null。
            //
            // 于是出现用户观察到的现象：
            //   * 刚重启微信时 —— 微信用力查询自己的库，句柄刚被捕获，查得到 -> 显示转写 ✓
            //   * 用了一会儿之后 —— 弱引用被回收，查不到 -> 显示 [语音] ✗
            //   * 而"重启微信就好了"—— 因为重启又捕获了新句柄。
            // 这与"数据有没有写进本地记录"**毫无关系**：本地记录里一直都有，只是这里没去看。
            //
            // 用一个随时可能失效的依赖去决定显示什么，是这个 bug 的根本问题。
            val fromWx = if (msgId != 0L && file.isNotBlank() && talker.isNotBlank()) {
                com.luoliaoo.wechat.voice.WeChatVoiceDb.existingText(
                    com.luoliaoo.wechat.voice.VoiceSource(
                        talker = talker,
                        id = msgId,
                        time = when_,
                        sent = if (access.isSend(msg)) 1 else 0,
                        fileToken = file,
                        serverId = access.msgSvrId(msg),
                    ),
                )
            } else {
                null
            }
            if (!fromWx.isNullOrBlank()) {
                // 与写进本地记录的格式保持一致，用户在两个地方看到的是同一种呈现。
                return com.luoliaoo.wechat.voice.VoicePrefix.wrap(fromWx)
            }

            // ---- 来源 2：我们自己的本地记录（可靠的那个）----
            //
            // 转写成功后本来就写进了本地记录（见 recordVoiceTranscript），所以**这里通常一定有**。
            // 它完全不依赖微信的库句柄，因此不会因为句柄被回收而失效。
            if (msgId != 0L && talker.isNotBlank()) {
                val stored = com.luoliaoo.wechat.data.HistoryStore.lineFor(talker, msgId)
                if (stored != null && stored.text.isNotBlank()) {
                    // 存的已经是带 [语音转文字] 前缀的成品，直接用，不要重复加前缀。
                    Log.i(TAG, "voice text: used the stored transcript for id=" + msgId)
                    return stored.text
                }
            }

            // ---- 都没有：诚实的占位符，绝不返回 XML ----
            return placeholder
        } catch (t: Throwable) {
            Log.w(TAG, "voice text lookup failed: " + t.message)
            return placeholder
        }
    }

    /**
     * v2.7.0 (item 2): analyse an EXPLICIT set of lines, skipping the scope dialog entirely.
     *
     * The user asked that the multi-select entry go straight to the read UI and then to the analysis, with no
     * 按条数 / 按时间区间 / 仅选择信息 step. `kept` is already the final list -- the read UI applied the user's
     * 删除 choices -- so it is sent as-is and never filtered by a count or a time window.
     *
     * A synthetic ChatLine carries the last kept line as the "target", which is what the card and the panel
     * key are built from.
     */
    fun startAnalysisForSelection(
        activity: Activity,
        kept: List<String>,
        /** v3.0.3: the optional background the user typed in the multi-select dialog. */
        background: String = "",
        /** v3.0.3: block words for THIS analysis. A line containing any of them is dropped. */
        blockWords: List<String> = emptyList(),
    ) {
        /* ★ v9.1.0：免责声明闸门（多选路径）。
         *
         * 这一条覆盖「多选消息后分析」—— 它不经过长按菜单，也不经过悬浮球面板，
         * 而是由 MenuHook / DouyinHooks / KuaishouHooks / QQHooks 的多选界面直接调用。
         * 少了这一处，用户不点单条消息、改用多选，就能绕过免责声明。 */
        if (!com.luoliaoo.wechat.ui.DisclaimerDialog.ensureConsented(activity)) {
            Log.i(TAG, "disclaimer not accepted yet -- multi-select analysis refused")
            return
        }
        if (kept.isEmpty()) {
            Log.w(TAG, "multi-select: every line was deleted; nothing to analyse")
            return
        }
        Log.i(TAG, "multi-select analysis: " + kept.size + " line(s)")

        // v3.0.3: drop the blocked lines HERE, at the entry, so the prompt, the card and any follow-up
        // all work from the same filtered list -- the same "filter at the source" rule collect() follows.
        val usable = kept.filterNot {
            com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(it) ||
                com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(it, blockWords)
        }
        val blocked = kept.size - usable.size
        if (blocked > 0) {
            Log.i(TAG, "multi-select: blocked " + blocked + " line(s) via " + blockWords.joinToString("/"))
        }
        if (usable.isEmpty()) {
            Log.w(TAG, "multi-select: every selected line matched a block word; nothing to analyse")
            return
        }

        val talker = try {
            detectCurrentTalker() ?: ""
        } catch (_: Throwable) {
            ""
        }
        val target = com.luoliaoo.wechat.ai.ChatLine(
            System.currentTimeMillis(), false, usable.last(),
        )
        val prompt = try {
            com.luoliaoo.wechat.ai.PromptBuilder.buildSelection(
                kept = usable,
                target = target,
                myWxid = myWxid(),
                focusSpeaker = focusSpeaker,
                background = background,
                extraBlockWords = blockWords,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "multi-select: building the prompt failed: " + t.message)
            return
        }
        Log.i(TAG, "multi-select PROMPT >>>\n" + prompt)

        // The rest of the pipeline is the module's own: the same card store, the same panel entry, the same
        // blocking request call, so a multi-select result behaves exactly like any other.
        val key = "selection:" + talker + ":" + System.currentTimeMillis()

        // ═══ v3.1.2 修复（用户报「多选的分析提示模型返回为空」） ═══
        //
        // 【原 bug】这里原来是 `ConfigRepo.load(activity)`，而长按分析走的是 `currentConfig()`。
        // 两者差别是致命的：
        //   * currentConfig() 会先做 **TemplateStore.resolve**：让当前选中的模板决定 key / model /
        //     baseUrl / prompt / 范围，然后才用共享配置补空缺；它还有 **mirror 兜底**（跨进程/分身场景
        //     唯一的通道）。
        //   * ConfigRepo.load() 只读**模块 App 自己那份** prefs，既没有模板解析、也没有 mirror 兜底。
        //     在微信进程里它读到的 apiKey 往往不是模板里的那一个。
        //
        // 真机日志证据（19:12）：
        //     multi-select PROMPT >>>           ← prompt 正常
        //     AI request failed: HTTP 401 Authentication Fails (auth header format should be Bearer sk-...)
        //     multi-select tokens: prompt=0 completion=0
        //     multi-select finished: fallback=true anger=-1   ← 用户看到的就是「模型返回为空」
        // 同时段长按分析一切正常，且日志里 keyPresent=true —— 说明 key 存在，只是多选这条路径
        // 取到的不是模板里那个正确的 key。用户「多选不是加了选择模板吗」的直觉是对的：模板确实选了，
        // 但这条路径根本没去解析模板。
        //
        // 【修法】与其它四条路径统一使用 currentConfig()。这样多选也遵守「模板决定端点/key/模型」，
        // 并且享受 mirror 兜底；取不到配置时再退回 load()，保持与原来相同的降级行为。
        val cfg = try {
            currentConfig() ?: com.luoliaoo.wechat.data.ConfigRepo.load(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "multi-select: currentConfig failed, falling back to load(): " + t.message)
            com.luoliaoo.wechat.data.ConfigRepo.load(activity)
        }
        cards[key] = CardState.Loading
        running.add(key)
        main.post {
            try {
                installPanelProviders()
                com.luoliaoo.wechat.ui.FloatingPanel.showCard(key)
            } catch (t: Throwable) {
                Log.w(TAG, "multi-select: showing the card failed: " + t.message)
            }
        }

        io.execute {
            /* ★ v10.3.0 说明：多选分析**不带图片**，这是它的固有限制。
               这条路径的输入是一批**选中的文字行**（`usable`），不是消息对象，
               而图片路径只存在于消息对象上（`field_imgPath`）。
               要在多选里支持图片，需要保留每条被选中消息的对象引用并逐条读图 ——
               那是一个独立改动（涉及选择状态的保存结构），不在本次范围内。
               所以长按单条消息分析能看图，多选分析暂时只能看文字。 */
            try {
                // item 4: one request returns the result and the token usage, exactly as runAnalysis does.
                val (result, reply) = com.luoliaoo.wechat.ai.OpenAiClient(cfg).analyzeWithUsage(prompt)
                try {
                    main.post {
                        com.luoliaoo.wechat.ui.FloatingPanel.setUsage(
                            key, reply.promptTokens, reply.completionTokens,
                        )
                    }
                    Log.i(TAG, "multi-select tokens: prompt=" + reply.promptTokens +
                        " completion=" + reply.completionTokens)
                } catch (_: Throwable) {
                    // usage is informational
                }
                running.remove(key)
                Log.i(TAG, "multi-select finished: fallback=" + result.isFallback +
                    " anger=" + result.angerLevel)
                main.post {
                    cards[key] = CardState.Done(result)
                    try {
                        com.luoliaoo.wechat.ui.FloatingPanel.showCard(key)
                        com.luoliaoo.wechat.ui.FloatingPanel.refresh()
                    } catch (_: Throwable) {
                        // the panel refreshes on its own timer
                    }
                }
            } catch (t: Throwable) {
                running.remove(key)
                Log.e(TAG, "multi-select analysis failed: " + t.message)
                main.post {
                    cards[key] = CardState.Error(t.message ?: "\u5931\u8d25")
                    try {
                        com.luoliaoo.wechat.ui.FloatingPanel.refresh()
                    } catch (_: Throwable) {
                        // ignore
                    }
                    toast(activity, "\u843d\u804a\uff1a" + (t.message ?: "\u5931\u8d25"))
                }
            }
        }
    }

    /** Fallback entry: only a View is available. */
    fun startAnalysis(view: View) {
        val activity = findActivity(view.context)
        if (activity == null) {
            Log.w(TAG, "no Activity found; cannot show the scope dialog")
            return
        }
        /* ★ v9.1.0：免责声明闸门（兜底路径）。
         * 与 startAnalysisFor / startAnalysisForSelection 同一道闸门 ——
         * 三个入口全部拦上，才算覆盖了所有分析路径。 */
        if (!com.luoliaoo.wechat.ui.DisclaimerDialog.ensureConsented(activity)) {
            Log.i(TAG, "disclaimer not accepted yet -- analysis refused")
            return
        }
        val msg = extractMessage(view)
        if (msg == null) {
            Log.w(TAG, "no message found from the view")
            toast(activity, "\u843d\u804a\uff1a\u672a\u80fd\u8bc6\u522b\u8be5\u6d88\u606f")
            return
        }
        access.probe(msg)
        proceed(activity, view, msg)
    }

    private fun proceed(activity: Activity, anchorView: View, msg: Any) {
        val rv = chatContext.findRecyclerView(anchorView)
        if (rv != null) lastRv = rv
        // item 2: publish the open chat, so the panel's list shows only this chat's records.
        // proceed() has no local `talker` yet, so it is read from the message.
        try {
            com.luoliaoo.wechat.ui.FloatingPanel.currentTalker = access.talker(msg)
        } catch (_: Throwable) {
            // best effort
        }
        val all: List<Any> = if (rv != null) chatContext.adapterMessages(rv) else emptyList()
        Log.i(TAG, "analysis start: rv=" + (rv != null) + " adapterMsgs=" + all.size +
            " anchor=" + anchorView.javaClass.simpleName)

        // ===================== 5.0.0 DOUYIN: the live history feed does not exist here ==========
        //
        // THE BUG THIS FIXES (reported by the user as 聊天记录写入失败):
        // in Douyin nothing ever wrote to HistoryStore. The device showed the store attached and
        // writable -- `history store at .../com.ss.android.ugc.aweme/files/luoliao/history
        // (writable=true)`, `live history timer started` -- yet the directory stayed EMPTY, with not
        // one `live: wrote N line(s)` in the log.
        //
        // Cause: the ONLY two feeders of the live recorder are WeChat-side.
        //   * ChatListHook:506 `controller.onLiveMessage(msg)`  -- ChatListHook is gated off on Douyin
        //   * the adapter binder bound at line 69              -- that binder is WeChat's adapter
        // so `onLiveMessage` was never called and nothing was ever staged or flushed.
        //
        // `recordHistory` alone could not cover the gap either: it records the `all` list, and on
        // Douyin `adapterMessages(rv)` returns EMPTY (ChatContext reads WeChat's adapter by
        // reflection), so it returned at its `if (all.isEmpty()) return` guard every time.
        //
        // The fix records the message the user actually pressed, which the module already holds and
        // has already proven readable. That is exactly the message the user wants kept, it needs no
        // adapter, and it makes history grow with use on Douyin just as it does on WeChat.
        // `all` is still recorded below when it is non-empty, so WeChat behaviour is unchanged.
        //
        // v7.0.0: exhaustive host dispatch instead of the earlier `HostApp.current != HostApp.WECHAT`.
        // The question being asked is "can this host fill `all` from the adapter?", and only WeChat can
        // (see the two WeChat-only feeders above). Writing it as a negation let a newly added host inherit
        // "no adapter messages" without anyone deciding that for it -- the exact mechanism that let
        // Kuaishou slip in unconsidered. Switching on the host makes the next host a COMPILE ERROR here.
        // The result is identical for every host that exists today.
        val adapterCanFillAll = when (HostApp.current) {
            HostApp.WECHAT -> true
            HostApp.DOUYIN,
            HostApp.SOUL,
            HostApp.KUAISHOU,
            HostApp.QQ,
            // v9.3.0: Xiaohongshu. Its message list exposes no usable whole-conversation getter, so
            // this host cannot fill the context from its adapter -- same answer as the four above,
            // reached deliberately rather than by inheriting WeChat's `true`.
            HostApp.XIAOHONGSHU,
            HostApp.UNKNOWN,
            -> false
        }
        if (!adapterCanFillAll && all.isEmpty()) {
            recordOne(access, msg)
        }

        try {
            binder.ensureHooked(rv)
        } catch (t: Throwable) {
            Log.w(TAG, "ensureHooked failed: " + t.message)
        }

        val targetLine = ChatLine(
            time = access.createTime(msg),
            isSend = access.isSend(msg),
            // v10.0.0: via displayText, so a VOICE target shows its transcript (or [语音])
            // instead of the <voicemsg ...> XML -- see displayText for the full reasoning.
            text = displayText(msg),
            /* ★ v10.3.0：这条消息是不是图片 —— 决定「仅选择信息」预览卡上
               要不要出现「识图」开关（`ScopeDialog` 读 targetPreview.isImage）。

               ⚠️ 易漏点：这个字段不设的话默认是 false，于是**开关永远不显示**，
               而现象只是"看不到按钮"，没有任何报错 —— 所以这里必须显式赋值。

               【为什么和 ChatContext.toLine 用的是同一个判据】
               两边都走 `WeChatEnvelope.isImage(access.content(msg))`：
               类型判断必须依据**原始 XML**，不能依据已经翻译过的展示文本。
               两处用同一个函数，才能保证"预览里有开关"与"分析时真的会读图"
               指向的是同一条消息。 */
            isImage = try {
                com.luoliaoo.wechat.hook.WeChatEnvelope.isImage(access.content(msg))
            } catch (_: Throwable) {
                false
            },
        )
        Log.i(TAG, "target text=" + targetLine.text + " fromMe=" + targetLine.isSend)

        val cfg = currentConfig()

        // item 5: how many distinct speakers appear, so the dialog can offer a group target.
        val speakers = LinkedHashSet<String>()
        for (m in all) {
            val t = access.talker(m)
            if (t.isNotBlank()) speakers.add(t)
        }
        val speakerCount = if (speakers.isEmpty()) 2 else speakers.size

        // item 2: the Reader lets the dialog list what would actually be analysed.
        /* ★ v10.3.0：这里用 object 表达式**同时**实现 read 与 readDetailed。
         *
         * 【为什么让两个方法共用同一段读取逻辑】读取要落盘、要重读磁盘、要建窗口，
         * 是一串有副作用的操作。写两份必然逐渐漂移 —— 表现是"预览里看到的行"
           与"readDetailed 里的行"不一致，而识图开关是按行文本匹配的，
           一旦不一致就会**张冠李戴**（把 A 图的开关落到 B 图上）。
           所以把读取收敛到一个局部函数 `doRead`，两个入口都调它。 */
        val reader = object : ScopeDialog.Reader {
            private fun doRead(
                mode: ScopeMode, up: Int, down: Int, startMs: Long, endMs: Long,
            ): List<com.luoliaoo.wechat.ai.ChatLine> {
                // Record what the adapter already holds. This is instant and does NOT scroll the chat:
                // driving the list was what froze the UI and moved the user's view, and it also detached
                // the row the card needed.
                try {
                    recordHistory(access.talker(msg), all)
                } catch (t: Throwable) {
                    Log.w(TAG, "reader record failed: " + t.message)
                }
                // ★ v10.0.0: 先把暂存区落盘，再读历史 —— 否则刚刚转写完成的语音不在 HistoryStore 里，
                //   用户点「读取」会看不到它（详见 flushNow 的注释）。
                flushNow(access.talker(msg))
                // ★ v10.0.0: 用户要求「点击读取时立即重新获取一遍本地」，所以这里**丢弃内存缓存**、
                //   强制从磁盘重读。原因：缓存是在聊天页打开时载入的，之后别的进程/时机写进磁盘的行
                //   不会自动反映进来；用户点读取就是想"看到此刻最新的本地记录"。
                //   代价只是一次文件读取（几十 KB），而读取是用户主动动作、不是热路径。
                val history = try {
                    HistoryStore.reloadFromDisk(access.talker(msg))
                } catch (_: Throwable) {
                    emptyList()
                }
                return try {
                    // previewWindow = true: 这是「读取」预览，不是分析。ONLY_ONE 下也显示上下文，
                    // 但分析路径不受影响（仍只送一条）—— 见 ChatContext.collect 的 previewWindow 注释。
                    chatContext.collect(
                        all, msg, mode, up, down, startMs, endMs, history, previewWindow = true,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "dialog read failed: " + t.message)
                    emptyList()
                }
            }

            /** 展示文本的唯一构造规则 —— 两个方法都必须用它，才能保证逐字节一致。 */
            private fun display(l: com.luoliaoo.wechat.ai.ChatLine): String =
                // item 1: the same labelling rule as everywhere else -- a one-to-one chat shows
                // 对方, never the raw wxid.
                chatContext.labelFor(l.isSend, l.speaker) + "：" + l.text

            override fun read(
                mode: ScopeMode, up: Int, down: Int, startMs: Long, endMs: Long,
            ): List<String> = doRead(mode, up, down, startMs, endMs).map { display(it) }

            /**
             * ★ v10.3.0：带类型信息的版本。
             *
             * `text` 是**最终展示文本**（含"我/对方："前缀），`key` 是**消息正文**。
             * 两者必须分开：`ScopeDialog` 显示 text，但用 key 去匹配识图开关 ——
             * 因为 `runAnalysis` 手上只有正文（`displayText(msg)`），拿不到前缀。
             * 详见 [com.luoliaoo.wechat.ai.ChatLine.key] 里记录的踩坑经过。
             */
            override fun readDetailed(
                mode: ScopeMode, up: Int, down: Int, startMs: Long, endMs: Long,
            ): List<com.luoliaoo.wechat.ai.ChatLine> =
                doRead(mode, up, down, startMs, endMs).map { l ->
                    com.luoliaoo.wechat.ai.ChatLine(
                        time = l.time,
                        isSend = l.isSend,
                        text = display(l),
                        speaker = l.speaker,
                        // 类型标记从 collect 一路带过来，这里只是搬运，不重新判断。
                        isImage = l.isImage,
                        // ⚠️ key 用**正文**（l.text），不是 display(l) —— 见上面的说明。
                        key = l.text,
                    )
                }
        }

        // item 2: with exactly two participants the other side must read as 对方, not as its wxid.
        chatContext.setSpeakerCount(speakerCount)

        ScopeDialog.show(
            activity = activity,
            initialMode = lastMode,
            defaultUp = cfg?.defaultUp ?: 3,
            defaultDown = cfg?.defaultDown ?: 3,
            defaultHours = cfg?.defaultRangeHours ?: 24,
            targetPreview = targetLine,
            speakerCount = speakerCount,
            reader = reader,
            onConfirm = { mode, up, down, startMs, endMs, focus, excluded, background, imageOn ->
                // v2.7.0 (item 1): keep the user's background note for this one analysis.
                pendingBackground = background
                focusSpeaker = focus
                excludedLines = excluded
                /* ★ v10.3.0：记住"用户为哪几条消息打开了识图"。
                   它只在本次分析有效，用完即清（见 runAnalysis 的消费点）——
                   与 excludedLines / pendingBackground 同一套"per-analysis"约定。
                   不清的话，下一次分析会沿用上一次的图片选择，那就是**把用户
                   没同意过的图片发出去**，属于隐私事故而非普通 bug。 */
                imageOnLines = imageOn
                runAnalysis(activity, anchorView, rv, all, msg, mode, up, down, startMs, endMs)
            },
        )
    }

    /**
     * item 1: message lines the user deleted in the scope preview.
     *
     * Applied when the prompt is built, so a deleted message is never analysed. Cleared on every new
     * analysis so a stale deletion cannot leak into the next one.
     */
    @Volatile
    private var excludedLines: Set<String> = emptySet()

    /**
     * ★ v10.3.0：用户在读取预览里**逐条打开识图**的那些行文本。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * 【为什么与 excludedLines 完全平行地放在这里】
     * 两者都来自同一次对话框交互、都只对**这一次分析**有效，所以用同一套生命周期：
     * 在 ScopeDialog 的 onConfirm 里赋值、在 runAnalysis 里消费、随即清空。
     * 放在一起可以让"这个状态是 per-analysis"这件事在代码结构上直接可见。
     *
     * 【⚠️ 为什么用完必须清空】
     * 不清的话，下一次分析会沿用上一次的图片选择 —— 那意味着**用户没有同意过的
     * 图片被发送到 AI 服务端**。这不是普通的渲染 bug，而是隐私事故：
     * 用户对"这次要不要发图"的同意，绝不能被静默地沿用到下一次。
     *
     * 【空集合的语义】"这次分析不看任何图" —— 也就是默认状态。
     * 所以省略清空（保持空集）是安全的，而忘记清空（保持非空）是危险的，
     * 这个不对称决定了下面的清空必须写在**消费点**而不是仅依赖下一次赋值。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    @Volatile
    private var imageOnLines: Set<String> = emptySet()

    /** Person the analysis is focused on (group chats, item 5). */
    @Volatile
    private var focusSpeaker: String? = null

    /**
     * v2.7.0 (item 1): the background the user typed in the scope dialog.
     *
     * It applies to ONE analysis and is cleared with the other per-analysis state, so a note written for one
     * conversation cannot leak into the next.
     */
    @Volatile
    private var pendingBackground: String = ""

    // -------------------------------------------------------------- analysis

    /**
     * ★ v10.3.0：把一条消息的图片读出来、编码好，供多模态请求使用。
     *
     * 这是"图片识别"功能在**分析链路**上的唯一入口，三个分析调用点（长按、多选、重新分析）
     * 都走它，保证行为一致 —— 否则很容易出现"长按能看图、重新分析看不到"这种分裂。
     *
     * 【为什么不抛异常，只返回空列表】
     * 图片是**增强**不是前提。这条消息没有图、或者是加密的表情包、或者原图还没下载到本地，
     * 都是**完全正常**的情况，正确响应是"这次分析不带图"，而不是让分析失败。
     *
     * 【为什么 Context 要传 activity 而不是宿主 Application】
     * [com.luoliaoo.wechat.ai.WeChatImage] 只用它反推 `filesDir.parentFile` 来定位微信数据目录，
     * 这两者的 `filesDir` 都指向 `/data/data/com.tencent.mm/files`，所以结果相同。
     * 用调用方已有的 activity 可以少一次全局查找；传 null 时安全退化为空列表。
     */
    private fun collectImages(
        ctx: android.content.Context?,
        msg: Any?,
    ): List<com.luoliaoo.wechat.ai.OpenAiClient.ImagePart> {
        if (msg == null) return emptyList()
        return try {
            val imgPath = access.imageFile(msg)
            if (imgPath.isBlank()) return emptyList()
            com.luoliaoo.wechat.ai.ImageEncoder.collect(ctx, msg, imgPath)
        } catch (t: Throwable) {
            Log.w(TAG, "collectImages failed: " + t.message)
            emptyList()
        }
    }

    private fun runAnalysis(
        activity: Activity?,
        anchorView: View,
        rv: Any?,
        all: List<Any>,
        msg: Any,
        mode: ScopeMode,
        up: Int,
        down: Int,
        startMs: Long,
        endMs: Long,
        forceRefresh: Boolean = false,
    ) {
        val talker = access.talker(msg)
        val msgId = access.msgId(msg)
        val key = talker + "#" + msgId
        if (rv != null) lastRv = rv
        // The view captured at long-press time: the most direct way to draw this card later.
        anchors[key] = java.lang.ref.WeakReference(anchorView)
        anchors["anchor:" + key] = java.lang.ref.WeakReference(anchorView)
        records[key] = AnchorRecord(
            key, rv, anchorView,
            msgId = msgId,
            // v10.0.0: displayText -- this text is shown on the card, so a VOICE long-press must
            // carry the transcript rather than the <voicemsg> XML.
            text = displayText(msg),
        )

        // Show the floating balloon as soon as an analysis begins, so the user can open the record
        // list while the AI is still working. Cards live here now, never inside a message row.
        try {
            val act = anchorView.context
            installPanelProviders()
            com.luoliaoo.wechat.ui.FloatingPanel.attach(act)
        } catch (t: Throwable) {
            Log.w(TAG, "attaching the floating panel failed: " + t.message)
        }

        // v1.0.25 / v1.0.34 ORDER: the anchor first (needs no message lookup), then the ancestor
        // fallback, and only then the id scan. This is the order that made cards appear.
        var row: ViewGroup? = rowViaAnchor(rv, anchorView)
        if (row == null) row = rowFromAnchor(anchorView)
        if (row == null) row = resolveRow(rv, msg)
        Log.i(TAG, "initial row lookup: anchor=" + (row != null) +
            " (rv=" + (rv != null) + " anchorAttached=" + anchorView.isAttachedToWindow + ")")

        Log.i(TAG, "runAnalysis: mode=" + mode + " up=" + up + " down=" + down +
            " allMsgs=" + all.size + " talker=" + talker + " msgId=" + msgId +
            " row=" + (row != null))

        val cfg = currentConfig()
        if (cfg == null || cfg.apiKey.isBlank()) {
            Log.w(TAG, "GUARD: no usable config")
            setState(key, CardState.Error(
                "\u8bf7\u5148\u914d\u7f6e API Key\uff08\u5361\u7247\u4e0a\u7684\u300c\u914d\u7f6e\u300d\u6309\u94ae\uff09",
            ), talker, msgId, displayText(msg), mode, up, down, startMs, endMs)
            renderOn(row, key, msg)
            toastNow("\u843d\u804a\uff1a\u8bf7\u5148\u914d\u7f6e API Key")
            return
        }

        // item 3: record everything read so far, then analyse the merged view (live + history) so
        // the window is never limited to whatever the adapter happens to hold right now.
        recordHistory(talker, all)
        // ★ v10.0.0: 同样先落盘再读 —— 分析路径走的是同一个 collect()，
        //   不补这一步的话，刚转写完的语音会漏出这次分析（与「读取」是同一个 bug）。
        flushNow(talker)
        val history = try {
            HistoryStore.lines(talker)
        } catch (t: Throwable) {
            Log.w(TAG, "history read failed: " + t.message)
            emptyList()
        }
        val collected = chatContext.collect(all, msg, mode, up, down, startMs, endMs, history)
        Log.i(TAG, "collect returned " + collected.size + " line(s) for mode=" + mode +
            " (live=" + all.size + " history=" + history.size + ")")

        // item 1: the messages the user deleted in the preview never reach the AI.
        //
        // The identity is the SAME string the preview showed -- `labelFor(...) + "：" + text` -- so what was
        // struck through is exactly what is left out.
        val excludedNow = excludedLines
        val lines = if (excludedNow.isEmpty()) {
            collected
        } else {
            val kept = collected.filterNot { l ->
                excludedNow.contains(chatContext.labelFor(l.isSend, l.speaker) + "\uff1a" + l.text)
            }
            Log.i(TAG, "analysis: excluded " + (collected.size - kept.size) + " deleted line(s)")
            kept
        }
        if (lines.isEmpty()) {
            setState(key, CardState.Error("\u672a\u627e\u5230\u53ef\u5206\u6790\u7684\u6587\u672c\u6d88\u606f"), talker, msgId, displayText(msg), mode, up, down, startMs, endMs)
            renderOn(row, key, msg)
            toastNow("\u843d\u804a\uff1a\u672a\u627e\u5230\u53ef\u5206\u6790\u7684\u6587\u672c\u6d88\u606f")
            return
        }

        val fingerprint = chatContext.fingerprint(lines)
        val cacheKey = ResultCache.keyOf(
            talker, msgId, access.msgSvrId(msg), fingerprint,
            cfg.model, ResultCache.hashPrompt(cfg.systemPrompt),
        )
        val cached = if (forceRefresh) null else ResultCache.get(cacheKey)
        if (cached != null) {
            Log.i(TAG, "cache hit")
            setState(key, CardState.Done(cached), talker, msgId, displayText(msg), mode, up, down, startMs, endMs)
            renderOn(row, key, msg)
            return
        }

        setState(
            key, CardState.Loading, talker, msgId, displayText(msg),
            mode, up, down, startMs, endMs,
        )
        renderOn(row, key, msg)
        toastNow("\u5df2\u63d0\u4ea4\u5206\u6790\uff0c\u8bf7\u7b49\u5f85\u2026")

        if (!running.add(key)) {
            Log.d(TAG, "already analysing; ignoring duplicate")
            return
        }

        val targetLine = ChatLine(
            time = access.createTime(msg),
            isSend = access.isSend(msg),
            // v10.0.0: displayText -- a long-pressed VOICE must not become XML here either.
            text = displayText(msg),
        )
        // v6.0.0: the long-press path must filter on the SAME words the multi-select path uses, or a
        // word the user added to a template silently does nothing here (the reported bug). Read once,
        // log the count, and pass it down.
        val block = templateBlockWords()
        val blockedHere = lines.count {
            com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(it.text, block)
        }
        if (blockedHere > 0) {
            Log.i(TAG, "analysis: dropped " + blockedHere + " line(s) carrying a block word (" +
                block.joinToString("/") + ")")
        } else {
            Log.i(TAG, "analysis: no line carried a block word (words=" + block.joinToString("/") + ")")
        }

        val prompt = PromptBuilder.build(
            lines, targetLine, mode, myWxid(), focusSpeaker,
            background = pendingBackground,
            extraBlockWords = block,
        )
        // The deletion applies to THIS analysis only; a stale one must not affect the next.
        excludedLines = emptySet()
        // v2.7.0 (item 1): the background is per-analysis too.
        pendingBackground = ""

        Log.i(TAG, "analysis started: lines=" + lines.size + " mode=" + mode +
            " promptLen=" + prompt.length + " model=" + cfg.model)
        Log.i(TAG, "PROMPT >>>\n" + prompt)

        io.execute {
            /* ═══════════════════════════════════════════════════════════════════════
               ★ v10.3.0：识图两段式流程（用户设计的方案）

                   图片 ──[识图模型]──> 文字描述 ──[模板模型]──> 分析结果

               【为什么必须拆成两次请求，而不是把图直接发给模板模型】
               工程的默认模型 `deepseek-flash` 是**纯文本模型**。直接发图：
                 * 服务端**忽略**图片 → 用户以为在看图，其实模型只看到占位符（静默失效，最难发现）；
                 * 或服务端报错 → 整次分析失败。
               拆开之后，模板模型只收到**文字**，与它支不支持视觉彻底无关 ——
               用户不必为了用识图而换掉自己习惯的模型。

               【顺带解决的第二个问题】描述变成了文字，所以能进本地记录、能参与追问、
               能被重新分析。"把 base64 图片存进卡片"既存不下也没有意义。

               【顺序：先识图，再分析】识图的描述要作为**输入**拼进主提示词，
               所以必须等它返回。这也是为什么识图那一步的超时设得比主请求短
               （见 VisionClient）—— 它挡在主分析前面，卡住就等于整个分析卡住。

               【失败处理】识图任一环节失败都返回 null，此时**照常走纯文本分析**。
               宁可用户拿到一份没看图的正常分析，也不要他看到一次失败。
               下面所有分支的终点都是"发一次纯文本请求"，没有"因识图失败而中断"的路径。
               ═══════════════════════════════════════════════════════════════════════ */

            // 第一步：读图（在 io 线程，要读磁盘 + base64，可能几十毫秒）。
            // `collectImages` 内部全程兜异常，失败返回空列表。
            /* ★ v10.3.0：识图是**逐条**决定的，不再是全局开关。
               判据 = 用户长按的这条消息，是否在他打开识图的那几行里。
               `displayText(msg)` 就是那行文本 —— 与读取预览里显示的字符串
               用**同一个函数**产出，所以两边必然一致（这正是当初把
               "显示文本"收敛到 displayText 这一个出口的用意）。

               `imageOnLines` 在这里就地取一份并**立刻清空**：见它的字段注释 ——
               同意不能被沿用到下一次分析。 */
            val wantedImage = imageOnLines
            imageOnLines = emptySet()
            val thisLineIsOn = wantedImage.contains(displayText(msg))
            val images = if (thisLineIsOn) collectImages(activity, msg) else emptyList()
            if (thisLineIsOn) {
                Log.i(TAG, "image: this message is marked for vision; read " + images.size + " image(s)")
            }

            // 第二步：识图模型把图转成文字。没有图/没配识图模型/识图失败 → 都得到 null。
            val description = if (images.isEmpty()) {
                null
            } else {
                try {
                    com.luoliaoo.wechat.ai.VisionClient.describe(cfg, images)
                } catch (t: Throwable) {
                    // describe() 自己承诺不抛，这里再兜一层：它挡在主分析前面，
                    // 任何漏出来的异常都会让整次分析失败，代价太大。
                    Log.w(TAG, "vision: describe threw: " + t.message)
                    null
                }
            }

            // 第三步：把描述拼进主提示词，用**模板模型**做真正的分析。
            val finalPrompt = if (description.isNullOrBlank()) {
                prompt
            } else {
                /* ⚠️ 必须把它**标注**清楚是"图片内容"而不是对方说的话。
                   否则主模型会把描述当成对方发的文字消息，从而在
                   "对方说了什么"这个判断上得出完全错误的结论。 */
                prompt + "\n\n" + com.luoliaoo.wechat.ai.VisionClient.wrapForPrompt(description)
            }

            Log.i(
                TAG,
                "analysis: images=" + images.size +
                    " visionDesc=" + (description?.length ?: 0) +
                    " finalPromptLen=" + finalPrompt.length +
                    " model=" + cfg.model,
            )
            if (description != null) {
                // 单独打一行，方便在真机上直接检查"识图到底看到了什么"。
                Log.i(TAG, "VISION >>>\n" + description)
            }

            // Single blocking request: the card shows Loading and then Done.
            // 到这里已经是**纯文本**请求 —— 识图的结果、以及"识图是否可用"，
            // 都在上面被消化成了一段文字，所以这里永远用 analyzeWithUsage。
            val (result, reply) = OpenAiClient(cfg).analyzeWithUsage(finalPrompt)
            try {
                main.post {
                    // item 1: remember it on the scope so it is persisted with the card.
                    scopes[key]?.let { sc ->
                        scopes[key] = Scope(
                            talker = sc.talker, msgId = sc.msgId, text = sc.text,
                            usagePrompt = reply.promptTokens,
                            usageCompletion = reply.completionTokens,
                            mode = sc.mode, up = sc.up, down = sc.down,
                            startMs = sc.startMs, endMs = sc.endMs,
                        )
                    }
                    persist(key)
                    com.luoliaoo.wechat.ui.FloatingPanel.setUsage(
                        key, reply.promptTokens, reply.completionTokens,
                    )
                }
                Log.i(TAG, "analysis tokens: prompt=" + reply.promptTokens +
                    " completion=" + reply.completionTokens)
            } catch (_: Throwable) {
                // usage is informational
            }
            ResultCache.put(cacheKey, result)
            running.remove(key)
            Log.i(TAG, "analysis finished: fallback=" + result.isFallback +
                " anger=" + result.angerLevel +
                " intentCount=" + result.intentOptions.size +
                " realCount=" + result.realIntent.size)
            main.post {
                val state = CardState.Done(result)
                cards[key] = state
                persist(key)
                toastNow(
                    if (result.isFallback) "\u843d\u804a\uff1a\u5206\u6790\u5931\u8d25\uff0c\u8be6\u60c5\u89c1\u5361\u7247"
                    else "\u843d\u804a\uff1aAI \u5206\u6790\u5b8c\u6210",
                )
                // Reconcile from state: the row is re-found by message id, so this works even
                // if the row scrolled away while the request was in flight.
                reconcile()
            }
        }
    }

    private fun setState(
        key: String,
        state: CardState,
        talker: String,
        msgId: Long,
        text: String,
        mode: ScopeMode,
        up: Int,
        down: Int,
        startMs: Long,
        endMs: Long,
    ) {
        cards[key] = state
        val prevUsage = scopes[key]
        scopes[key] = Scope(
            talker, msgId,
            usagePrompt = prevUsage?.usagePrompt ?: 0,
            usageCompletion = prevUsage?.usageCompletion ?: 0,
            text = text, mode = mode, up = up, down = down, startMs = startMs, endMs = endMs,
        )
        persist(key)
    }

    /** Counters behind [historyPipelineState]; diagnostic only. */
    @Volatile
    private var liveFlushes = 0

    @Volatile
    private var liveWrites = 0

    /** Rejections logged so far, capped by [DIAG_REJECT_LIMIT]. */
    @Volatile
    private var liveRejects = 0

    /**
     * v6.0.0 (Soul), v7.0.0 (Douyin/Soul/Kuaishou): distinct rejection SIGNATURES already logged, so a
     * cap cannot hide a new cause.
     *
     * ============================== THE BUG THIS FIXES ==============================
     * The three rejections in [onLiveMessage] shared ONE counter capped at
     * [DIAG_REJECT_LIMIT] (6). On WeChat that is harmless: the first few binds are the messages it is
     * about, so the cause is visible by line 6.
     *
     * On SOUL it is dangerous, for a reason that is specific to Soul: `SoulEnvelope.toMillis` exists
     * precisely because Soul's `serverTime`/`localTime` unit was NEVER confirmed statically (see that
     * function and `SoulMsgAccess.createTime`). If Soul reports SECONDS, the epoch guard below rejects
     * EVERY message -- and the log shows about six rejection lines and then goes SILENT while the user
     * sees an empty history. That is indistinguishable from "the hook never ran", which is precisely
     * the state that cost a whole debugging round on Douyin (必看.md, 「诊断日志被会话列表的 8 次绑定
     * 占满」).
     *
     * The fix is the one 必看.md prescribes: de-duplicate BY SIGNATURE (like Douyin's
     * `seenHolderClasses`), not by event count. A signature is "kind + reason", so the first blank
     * talker, the first blank body and the first bad time all log once each and a NEW cause can never
     * be crowded out by a repeated old one.
     *
     * SCOPED TO NON-WECHAT HOSTS (v7.0.0): originally this was Soul-only, and the decision was an
     * `if (SOUL) ... else ...`. Adding the fourth host (Kuaishou) made that shape wrong: Kuaishou
     * silently inherited the count-capped policy. The policy is now selected by an explicit `when`
     * covering every host constant. By-signature logging applies to DOUYIN, SOUL and KUAISHOU -- i.e.
     * the hosts that actually have a message reader installed for them. WECHAT keeps its verified
     * count-capped behaviour byte-for-byte, and the unreachable UNKNOWN case does too.
     * ================================================================================
     */
    private val liveRejectKinds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /**
     * Logs one live-history rejection, choosing the policy by HOST.
     *
     * @param kind a stable, low-cardinality signature (NOT free text -- this is a Set key)
     * @param line the full message, already built by the caller
     */
    private fun logLiveReject(kind: String, line: String) {
        // v7.0.0 BUG FIX (found by ks-reviewer; the SHAPE was the defect, not the host list):
        //
        // This used to be `if (SOUL) { by-signature; return }` followed by a count-capped fallback
        // whose comment still claimed it served "WeChat / Douyin". That is the same "an `else` silently
        // absorbs every future host" shape 必看.md records for `WeChatHooks` -- and it had already
        // misfired: KUAISHOU fell into the count-capped branch.
        //
        // WHY THIS MATTERS MOST ON THE NEWEST HOST: the count cap is global (`liveRejects < 6`), so ONE
        // repeated cause (a blank talker, a bad timestamp) burns all six lines and every DIFFERENT
        // cause after it is never printed. Kuaishou history capture is brand new, and these lines are
        // the most direct evidence for "the history file is empty" -- not the ONLY channel
        // (`historyPipelineState()` reports staged/flushes/written independently), but the only one that
        // names WHICH cause rejected the messages. Losing them would look exactly like "the hook never
        // ran", which 必看.md records as a whole wasted debugging round on Douyin.
        //
        // SCOPE OF THIS CHANGE -- read before assuming it only touches Kuaishou:
        // by-signature logging now applies to DOUYIN and SOUL as well, not just the new host. Douyin
        // therefore moves from the count cap to by-signature too. That is intended and low risk (the
        // by-signature policy is strictly more informative and its upper bound is the same 6 lines), but
        // it IS a behaviour change to an already-shipped host, so it is stated here rather than left for
        // someone to discover. WeChat is the one host left untouched, deliberately.
        //
        // Selected by an explicit `when` on the host rather than an `else`. WeChat keeps the count cap
        // byte-for-byte,
        // because its behaviour is verified and this change must not perturb it.
        when (HostApp.current) {
            HostApp.WECHAT -> {
                // Unchanged since before 5.0.0: verified behaviour, deliberately not touched.
                if (liveRejects < DIAG_REJECT_LIMIT) {
                    liveRejects++
                    Log.w(TAG, line)
                }
            }
            // The hosts that have a message reader installed: each DISTINCT cause logs once, however
            // many messages repeat it. Still bounded (by DIAG_REJECT_LIMIT distinct kinds), so a
            // pathological host cannot flood the log either.
            // Note UNKNOWN below is also "not WeChat" yet takes the count-capped path -- so the rule is
            // phrased as "these hosts", not "every non-WeChat host". (A comment that said the latter
            // would contradict the very next branch.)
            // v9.3.0: Xiaohongshu joins this group -- it HAS a message reader, so a distinct rejection
            // cause is real information rather than noise.
            HostApp.DOUYIN, HostApp.SOUL, HostApp.KUAISHOU, HostApp.QQ, HostApp.XIAOHONGSHU -> {
                if (liveRejectKinds.size >= DIAG_REJECT_LIMIT && !liveRejectKinds.contains(kind)) return
                if (liveRejectKinds.add(kind)) Log.w(TAG, line)
            }
            HostApp.UNKNOWN -> {
                // Unreachable (no hooks are installed for UNKNOWN), but written out so that adding a
                // fifth host is a COMPILE ERROR at this site rather than a silent fall-through into
                // WeChat's count-capped policy.
                if (liveRejects < DIAG_REJECT_LIMIT) {
                    liveRejects++
                    Log.w(TAG, line)
                }
            }
        }
    }

    /**
     * A one-line report of the history pipeline's internal state, for the periodic diagnostic.
     *
     * Written because "nothing was written to disk" has several causes that look identical from
     * outside, and the user asked for evidence rather than inference
     * (「你加debug到lsposed然后分析原因不就好了吗」). The numbers answer the three questions that
     * actually discriminate between the causes:
     *
     *   staged  -- is anything reaching the staging map? Zero means the feed or the filters rejected
     *              every message, so the flush has nothing to do and is not at fault.
     *   flushes -- has the 2-second timer run at all? Zero means the timer never started, which would
     *              make every other number irrelevant.
     *   written -- how many batches made it to disk? staged>0 with written=0 isolates the fault to
     *              HistoryStore/the filesystem, not to the host feed.
     *
     * Also reports the store's directory and whether it is writable, because an unwritable directory
     * is the one failure that produces staged>0 and written=0.
     */
    fun historyPipelineState(): String = try {
        var staged = 0
        for ((_, m) in livePending) {
            synchronized(m) { staged += m.size }
        }
        "staged=" + staged + " flushes=" + liveFlushes + " written=" + liveWrites +
            " storeDir=" + (com.luoliaoo.wechat.data.HistoryStore.location() ?: "(none)") +
            " writable=" + com.luoliaoo.wechat.data.HistoryStore.isWritable()
    } catch (t: Throwable) {
        "pipeline state failed: " + t.message
    }

    /**
     * Records ONE message into the local history, for hosts with no adapter to enumerate.
     *
     * 5.0.0 DOUYIN. On Douyin neither live-history feeder exists (see the note in [proceed]), so this
     * is what keeps the store growing there. It goes through the [MsgAccess] facade, so it is
     * host-agnostic by construction rather than by a second implementation.
     *
     * The id rule MATCHES [onLiveMessage] deliberately -- real id, else server id, else the same
     * deterministic synthetic key. Sharing the rule is what makes a message recorded here and one
     * recorded by the live feed collapse into a single line instead of two duplicates.
     */
    private fun recordOne(acc: MsgAccess, msg: Any) {
        try {
            val talker = acc.talker(msg)
            if (talker.isBlank()) return
            // v10.1.0: isAnalysableText，让引用消息也能走这条路入库，理由同 recordHistory。
            if (!acc.isAnalysableText(msg)) return
            // v10.1.0: displayText，引用消息在这里就地格式化，不写 XML。
            val text = displayText(msg)
            if (text.isBlank()) return
            val when_ = acc.createTime(msg)
            val isSend = acc.isSend(msg)

            val real = acc.msgId(msg)
            val svr = acc.msgSvrId(msg)
            val id: Long = when {
                real != 0L -> real
                svr != 0L -> svr
                else -> syntheticId(talker, when_, isSend, text)
            }

            com.luoliaoo.wechat.data.HistoryStore.record(
                talker,
                listOf(
                    com.luoliaoo.wechat.data.HistoryStore.Line(
                        msgId = id,
                        time = when_,
                        isSend = isSend,
                        text = text,
                        speaker = null,
                    ),
                ),
            )
            Log.i(TAG, "history: recorded the pressed message for " + talker + " (id=" + id + ")")
        } catch (t: Throwable) {
            Log.w(TAG, "recordOne failed: " + t.message)
        }
    }

    /**
     * Records the messages currently held by the adapter into the local history (item 3).
     *
     * Called on every analysis and on every dialog read, so the stored window keeps growing instead of
     * being capped at whatever is on screen.
     */
    private fun recordHistory(talker: String, all: List<Any>) {
        if (talker.isBlank()) return

        // v3.1.0 性能修复（surveyor t1 证据）：删掉了这里对 DbReader.recentMessages 的调用。
        //
        // WHY：它 100% 无法成功，却每次分析都在主线程上做一次昂贵操作。
        //   * EnMicroMsg.db 是 SQLCipher 加密的，plain SQLiteDatabase.openDatabase 必然抛
        //     SQLiteDatabaseCorruptException "file is not a database"（SQLITE_NOTADB）——DbReader 的
        //     类注释自己就标着 "!! UNUSED"，并写明这个类不实现 SQLCipher 就无法工作。
        //   * 用户日志实测：15:41:07.517 `db located: .../EnMicroMsg.db size=2593465344`（2.5GB），
        //     .521 立刻抛异常。全日志「db located」16 次、「db read failed」36 次——零次成功。
        //   * 也就是说，这里付出的是「主线程定位并尝试打开一个 2.5GB 文件」的代价，换来的是必然的失败。
        //
        // 功能零损失：历史记录本来就由下面的 deepCollect + HistoryStore 收集（DbReader 注释也是这么写的）。
        // DbReader.kt 保留在仓库里作为参考，但不再被运行时调用。

        if (all.isEmpty()) return
        try {
            val out = ArrayList<HistoryStore.Line>(all.size)
            for (m in all) {
                // v10.1.0: isAnalysableText（不是 isText）—— 引用消息必须也能入库，
                // 否则它会像以前那样被这条门禁整条丢掉。
                if (!access.isAnalysableText(m)) continue
                // v10.1.0: displayText（不是 access.content）—— 引用消息在这里就地转成
                // `[引用]标题"引用：正文"`，绝不把 XML 写进本地记录。
                val text = displayText(m)
                if (text.isBlank()) continue
                out.add(
                    HistoryStore.Line(
                        msgId = access.msgId(m),
                        time = access.createTime(m),
                        isSend = access.isSend(m),
                        text = text,
                        speaker = access.talker(m).ifBlank { null },
                    ),
                )
            }
            HistoryStore.record(talker, out)
        } catch (t: Throwable) {
            Log.w(TAG, "recordHistory failed: " + t.message)
        }
    }

                /** Writes the card's state to disk so a restart can redraw it (item 7). */
    private fun persist(key: String) {
        try {
            val s = scopes[key] ?: return
            val st = cards[key]
            CardStore.put(
                CardStore.Saved(
                    key = key,
                    talker = s.talker,
                    msgId = s.msgId,
                    // item 3: the message text MUST be passed here. It was added to Saved and to
                    // CardStore's reader/writer, but this constructor never set it, so every save wrote
                    // an empty text and the title lost the message after a restart -- the reported bug.
                    text = s.text.ifBlank { records[key]?.text.orEmpty() },
                    // item 1: the usage travels with the card.
                    usagePrompt = s.usagePrompt,
                    usageCompletion = s.usageCompletion,
                    mode = s.mode,
                    up = s.up,
                    down = s.down,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    result = (st as? CardState.Done)?.result,
                    error = (st as? CardState.Error)?.message,
                    turns = turns[key]?.toList() ?: emptyList(),
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed: " + t.message)
        }
    }

    // ------------------------------------------------------------- reconcile

    /** Debounce so onLayout storms (scrolling) do not flood the main thread. */
    @Volatile
    private var reconcileScheduled = false

    /**
     * Called from RecyclerView.onLayout: schedules one reconcile pass, coalescing bursts.
     */
    fun requestReconcile() {
        if (reconcileScheduled) return
        reconcileScheduled = true
        main.postDelayed({
            reconcileScheduled = false
            reconcile()
        }, 60L)
    }

        /**
     * Keeps the floating panel in sync.
     *
     * The previous version hunted rows for cards on every scroll. That entire approach is gone: a card
     * is never inserted into WeChat's view tree, so there is nothing to re-locate when rows recycle.
     */
    fun reconcile() {
        main.post {
            try {
                installPanelProviders()
                com.luoliaoo.wechat.ui.FloatingPanel.refresh()
            } catch (t: Throwable) {
                Log.w(TAG, "reconcile failed: " + t.message)
            }
        }
    }

    /** True when some attached row already shows a card for `key`. */
    private fun hasCardFor(key: String, rows: List<ViewGroup>): Boolean {
        for (row in rows) {
            val card = com.luoliaoo.wechat.ui.AnalysisCard.findIn(row) ?: continue
            if (card.ownerKey == key) return true
        }
        return false
    }

    /**
     * The row for the long-pressed view -- the v1.0.25 mechanism.
     *
     * Uses RecyclerView.findContainingViewHolder(anchor).getBindingAdapterPosition() and then
     * findViewByPosition(pos), so it needs neither the message object nor any adapter hook. This is
     * the path that made v1.0.25 work, and it is tried FIRST.
     */
    private fun rowViaAnchor(rv: Any?, anchor: View?): ViewGroup? {
        if (rv == null || anchor == null) return null
        return try {
            val pos = chatContext.positionOf(rv, anchor)
            if (pos < 0) {
                Log.i(TAG, "rowViaAnchor: position unavailable for the anchor")
                return null
            }
            val row = CardRenderer.itemViewAt(rv, pos)
            Log.i(TAG, "rowViaAnchor: pos=" + pos + " row=" + (row != null))
            row
        } catch (t: Throwable) {
            Log.w(TAG, "rowViaAnchor failed: " + t.message)
            null
        }
    }

        /**
     * Shows the card for `key` in the floating panel.
     *
     * `row` is kept only so existing call sites compile: nothing is inserted into WeChat's view tree
     * any more. That is the point of the change -- WeChat recycles rows, so a card living inside one
     * was carried into another message and displayed under it. With the card in an overlay, placement
     * does not depend on identifying a row at all.
     */
    private fun drawOn(row: ViewGroup?, key: String, state: CardState, msg: Any?): Boolean {
        return try {
            val ctx = row?.context
                ?: records[key]?.anchor?.context
                ?: (lastRv as? View)?.context
            if (ctx == null) {
                Log.w(TAG, "drawOn: no Context; cannot show the floating card for " + key)
                return false
            }
            installPanelProviders()
            // v4.0.0: attach() builds the overlay HOST that the card is rendered into. It no longer
            // refuses to run when the balloon is switched off -- the host is needed for cards either way,
            // and only the round button is omitted. Leaving this call in place is what makes the analysis
            // work with the balloon hidden.
            com.luoliaoo.wechat.ui.FloatingPanel.attach(ctx)
            com.luoliaoo.wechat.ui.FloatingPanel.showCard(key)
            Log.i(TAG, "drawOn: card shown in the floating panel for " + key)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "drawOn failed", t)
            false
        }
    }

    /**
     * Gives the floating panel its data sources, once.
     *
     * Kept here rather than in the panel so the panel file stays independent of this controller.
     */
    /**
     * item 4a: the best display name for a chat.
     *
     * Priority: the remark the user set, then the nickname, then the id. All three live in WeChat's
     * own contact storage, reached reflectively through the class loader the resolver already holds.
     * Any failure returns null and the caller keeps the stored title.
     */
    private fun displayNameFor(talker: String): String? {
        if (talker.isBlank()) return null
        try {
            val cl = resolver.classLoader()
            // 1) the remark: WXContacts / ContactsStorage embed a per-chat remark field.
            for (cls in arrayOf(
                "com.tencent.mm.storage.ae",
                "com.tencent.mm.storage.ah",
                "com.tencent.mm.storage.au",
            )) {
                try {
                    val k = Class.forName(cls, false, cl)
                    val instance = try {
                        // These storages are usually reached through the plugin's account accessor; the
                        // static field holding the singleton is named differently per build, so scan.
                        var found: Any? = null
                        for (f in k.declaredFields) {
                            if (f.name.startsWith("INSTANCE") ||
                                f.type == k && java.lang.reflect.Modifier.isStatic(f.modifiers)
                            ) {
                                f.isAccessible = true
                                found = f.get(null)
                                if (found != null) break
                            }
                        }
                        found
                    } catch (_: Throwable) {
                        null
                    }
                    if (instance == null) continue
                    for (m in instance.javaClass.methods) {
                        val n = m.name
                        if (m.parameterCount != 1) continue
                        if (m.parameterTypes[0] != String::class.java) continue
                        if (!n.startsWith("getRemark") && !n.startsWith("getNickname") &&
                            !n.startsWith("queryRemark")
                        ) {
                            continue
                        }
                        val r = try {
                            m.invoke(instance, talker) as? String
                        } catch (_: Throwable) {
                            null
                        }
                        if (!r.isNullOrBlank()) {
                            Log.i(TAG, "displayName: " + n + " -> " + r)
                            return r
                        }
                    }
                } catch (_: Throwable) {
                    // try the next candidate
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "displayNameFor failed: " + t.message)
        }
        return null
    }

    /**
     * item 4: the creation time of the message a card belongs to.
     *
     * Prefers the live message object, then the stored history for that chat, and finally 0 (which the
     * panel renders as an empty string rather than a bogus 1970 date).
     */
    private fun messageTimeFor(key: String): Long {
        try {
            val scope = scopes[key]
            // 1) the live message, when it is still in the adapter
            val rv = lastRv
            if (rv != null && scope != null) {
                val msg = findMessageById(rv, scope.talker, scope.msgId)
                if (msg != null) {
                    val t = access.createTime(msg)
                    if (t > 0L) return t
                }
            }
            // 2) the stored history line for that message id
            if (scope != null) {
                for (l in HistoryStore.lines(scope.talker)) {
                    if (l.msgId == scope.msgId && l.time > 0L) return l.time
                }
                // 3) any history at all for this chat: better than a 1970 date
                HistoryStore.lines(scope.talker).lastOrNull { it.time > 0L }?.let {
                    return it.time
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "messageTimeFor failed: " + t.message)
        }
        return 0L
    }

    private fun installPanelProviders() {
        try {
            val panel = com.luoliaoo.wechat.ui.FloatingPanel
            if (panel.listProvider != null) return

            panel.listProvider = {
                val out = ArrayList<com.luoliaoo.wechat.ui.FloatingPanel.Entry>()
                for ((k, st) in cards) {
                    val scope = scopes[k]
                    val msgText = if (scope != null && scope.text.isNotBlank()) {
                        scope.text
                    } else {
                        records[k]?.text.orEmpty()
                    }
                    out.add(
                        com.luoliaoo.wechat.ui.FloatingPanel.Entry(
                            key = k,
                            // item 3: the MESSAGE is the title; the wxid is no longer shown.
                            title = msgText.take(60).ifBlank { "\u5206\u6790" },
                            talker = scope?.talker ?: k.substringBefore('#'),
                            snippet = when (st) {
                                is CardState.Done ->
                                    msgText.take(60).ifBlank {
                                        st.result.intentOptions.joinToString("  ") { o ->
                                            o.label + " " + o.probability + "%"
                                        }
                                    }
                                is CardState.Error -> st.message
                                else -> "\u6b63\u5728\u5206\u6790\u2026"
                            },
                            // item 4: the REAL message time. `scope.msgId` used to be passed here, and a
                            // message id is a small number, so it rendered as 1970-01-01 (the frozen
                            // "01-01 12:27"). The live message is preferred; the stored history line is the
                            // fallback, so the time is right even before anything is on screen.
                            time = messageTimeFor(k),
                            // item 1 / 4: the stored usage, so the second line shows the tokens.
                            usage = scopes[k]?.let { sc ->
                                if (sc.usagePrompt > 0 || sc.usageCompletion > 0) {
                                    "\u8f93\u5165 " + sc.usagePrompt + " \u00b7 \u8f93\u51fa " +
                                        sc.usageCompletion + " \u00b7 \u5408\u8ba1 " +
                                        (sc.usagePrompt + sc.usageCompletion) + " Tokens"
                                } else {
                                    ""
                                }
                            }.orEmpty(),
                        ),
                    )
                }
                out.sortedByDescending { it.time }
            }

            panel.cardProvider = { k ->
                val st = cards[k]
                if (st == null) {
                    null
                } else {
                    com.luoliaoo.wechat.ui.FloatingPanel.CardViewData(
                        key = k,
                        state = st,
                        turns = turns[k]?.toList() ?: emptyList(),
                    )
                }
            }

            // item 4a: the contact's remark, then nickname, then the raw id.
            panel.titleProvider = { k ->
                try {
                    val talker = scopes[k]?.talker ?: k.substringBefore('#')
                    displayNameFor(talker)
                } catch (_: Throwable) {
                    null
                }
            }

            // item 2: ONE source of truth for the usage line. The list and the card both ask this, so
            // they can never disagree.
            panel.usageProvider = { k ->
                val sc = scopes[k]
                if (sc == null || (sc.usagePrompt <= 0 && sc.usageCompletion <= 0)) {
                    ""
                } else {
                    "\u8f93\u5165 " + sc.usagePrompt + " \u00b7 \u8f93\u51fa " + sc.usageCompletion +
                        " \u00b7 \u5408\u8ba1 " + (sc.usagePrompt + sc.usageCompletion) + " Tokens"
                }
            }

            // tap-to-detect: the panel asks for the current chat's contact on demand.
            panel.talkerProvider = { detectCurrentTalker() }

            panel.onReanalyze = { k -> reanalyze(k) }
            panel.onFollowUp = { k, q -> followUp(k, q) }
            panel.onDelete = { k -> deleteCard(k) }
            panel.onEdit = { k, t ->
                val anchor = records[k]?.anchor
                if (anchor == null || !com.luoliaoo.wechat.ui.InputInjector.fill(anchor, t)) {
                    toastNow("\u843d\u804a\uff1a\u672a\u627e\u5230\u804a\u5929\u8f93\u5165\u6846")
                }
            }
            Log.i(TAG, "floating panel: providers installed")
        } catch (t: Throwable) {
            Log.w(TAG, "installPanelProviders failed: " + t.message)
        }
    }

    /** Convenience: render on a specific row (used right after starting an analysis). */
    private fun renderOn(row: ViewGroup?, key: String, msg: Any?) {
        val state = cards[key] ?: return
        val rec = records[key]
        main.post {
            // ---------------------------------------------------------------
            // Same order as v1.0.25: given row, then the ANCHOR position, then the
            // ancestor, and only last the message-id scan. Every step logs its result,
            // so a failure says exactly which lookup died instead of leaving it to guesswork.
            // ---------------------------------------------------------------
            Log.i(
                TAG,
                "renderOn START key=" + key +
                    " givenRow=" + (row != null) +
                    " rec=" + (rec != null) +
                    " anchorAttached=" + (rec?.anchor?.isAttachedToWindow ?: false) +
                    " lastRv=" + (lastRv != null),
            )

            var source = ""
            var target = row
            if (target != null) source = "given"

            if (target == null && rec != null) {
                target = rowViaAnchor(rec.rv, rec.anchor)
                if (target != null) source = "ANCHOR"
            }
            if (target == null && rec != null) {
                target = rowFromAnchor(rec.anchor)
                if (target != null) source = "ancestor"
            }
            if (target == null) {
                target = resolveRow(lastRv, msg)
                if (target != null) source = "idScan"
            }

            if (target == null) {
                Log.w(TAG, "renderOn FAILED to resolve a row; scheduling retries")
                retryRender(key, msg, 0)
                return@post
            }
            val ok = drawOn(target, key, state, msg)
            Log.i(TAG, "renderOn resolved row via " + source + " drawOk=" + ok)
        }
    }

    /**
     * Retries rendering a few times when the row is not attached yet.
     *
     * v1.0.25 did the same (5 attempts, 400 ms apart) and it matters: right after the AI replies the
     * list may be mid-layout, and a single attempt would silently give up.
     */
    private fun retryRender(key: String, msg: Any?, attempt: Int) {
        if (attempt >= 5) {
            Log.w(TAG, "retryRender: gave up for " + key)
            return
        }
        main.postDelayed({
            val rec = records[key]
            val state = cards[key]
            if (state == null || rec == null) return@postDelayed
            var target = rowViaAnchor(rec.rv, rec.anchor)
            if (target == null) target = rowFromAnchor(rec.anchor)
            if (target == null) target = resolveRow(lastRv, msg)
            if (target == null) {
                Log.i(TAG, "retryRender attempt " + (attempt + 1) + ": still no row")
                retryRender(key, msg, attempt + 1)
                return@postDelayed
            }
            if (drawOn(target, key, state, msg)) {
                Log.i(TAG, "retryRender: drew a card on attempt " + (attempt + 1))
            }
        }, 400L)
    }

    /** The message displayed by a row, read from the itemView's own tag or its ViewHolder. */
    private fun messageOfRow(row: ViewGroup): Any? {
        // Our wrapper keeps the bubble as its first child; the message is on the row or a child.
        val fromTag = extractMessage(row)
        if (fromTag != null) return fromTag
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c is View) {
                val m = extractMessage(c)
                if (m != null) return m
            }
        }
        return null
    }

    /** Walks the RecyclerView's attached children to find the row showing `msg`. */
    private fun resolveRow(rv: Any?, msg: Any?): ViewGroup? {
        if (rv == null || msg == null) return null
        val wantId = access.msgId(msg)
        if (wantId == 0L) return null
        val wantTalker = access.talker(msg)
        for (row in CardRenderer.attachedRows(rv)) {
            val m = messageOfRow(row) ?: continue
            if (access.msgId(m) == wantId && access.talker(m) == wantTalker) return row
        }
        return null
    }

    /** The bubble the card should sit under: the message text view inside the row. */
    private fun anchorOf(row: ViewGroup): View? {
        var best: View? = null
        var bestArea = 0
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(row to 0)
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (depth > 6) continue
            // Skip our own card: while streaming it holds the longest text in the row, so it
            // would out-measure the message and be chosen as the anchor.
            if (v is com.luoliaoo.wechat.ui.AnalysisCard) continue
            if (v is android.widget.TextView && v.text?.isNotEmpty() == true) {
                val area = v.width * v.height
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to depth + 1)
            }
        }
        return best
    }

    private fun rowFromAnchor(anchor: View): ViewGroup? {
        var cur: View? = anchor
        var depth = 0
        while (cur != null && depth < 6) {
            val parent = cur.parent
            if (parent is ViewGroup && parent.height > 0) return parent
            cur = parent as? View
            depth++
        }
        return null
    }

    // ------------------------------------------------------- card binder paths

    /** Lets ChatListHook publish the chat RecyclerView for reconcile passes. */
    fun noteRecyclerView(rv: Any?) {
        if (rv != null) lastRv = rv
    }

    /** Public entry used by ChatListHook for every chat row bind. */
    /**
     * Kept for ChatListHook's call site. Cards are no longer placed inside rows, so nothing is done
     * with a row bind -- the floating panel is driven by the analysis state instead.
     */
    fun onChatRowBound(itemView: ViewGroup, msg: Any?) {
        // intentionally empty: see the class note on the floating panel
    }

        // ---------------------------------------------------------------- public

    fun stateFor(msg: Any): CardState? = cards[keyFor(msg)]

    fun keyFor(msg: Any): String = access.talker(msg) + "#" + access.msgId(msg)

    fun access(): MsgAccess = access

    fun messageOf(view: View): Any? = extractMessage(view)

    fun turnsFor(key: String): List<ChatTurn> = turns[key]?.toList() ?: emptyList()

    /**
     * Re-runs the analysis with the SAME scope, immediately (item 2).
     *
     * The message is looked up by id from the live adapter, so scrolling no longer breaks it
     * (item 5).
     */
    /**
     * 重新分析 (item 4).
     *
     * Records the scope that produced this card, DELETES the card, then runs the analysis again from
     * scratch -- exactly "记住模式 -> 删除卡片 -> 重新生成". Because the stored scope is used, the same
     * range is analysed, and the forced refresh skips the cache so a fresh answer is produced.
     */
    /**
     * item 5: re-runs the analysis with the SAME scope.
     *
     * It no longer insists on the live message. The stored scope carries the talker, the message id, the
     * mode and the range, and the message text is in the record, so the analysis can always be rebuilt;
     * the live message only refreshes the surrounding context when it happens to be on screen. That
     * removes the "无法重新分析（消息已离开屏幕）" refusal.
     */
    fun reanalyze(key: String) {
        val s = scopes[key]
        if (s == null) {
            Log.w(TAG, "reanalyze: no stored scope for " + key)
            toastNow("\u843d\u804a\uff1a\u65e0\u6cd5\u91cd\u65b0\u5206\u6790\uff08\u65e0\u8bb0\u5f55\uff09")
            return
        }
        val rec = records[key]
        val rv = rec?.rv ?: lastRv

        // The live message, when it is still reachable -- used for the freshest context only.
        var liveMsg: Any? = if (rec != null) extractMessage(rec.anchor) else null
        if (liveMsg == null) liveMsg = findMessageById(rv, s.talker, s.msgId)

        // The messages around it: the live adapter if any, otherwise the stored history.
        var all: List<Any> = if (rv != null) {
            try {
                chatContext.adapterMessages(rv)
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (liveMsg == null) {
            // The message is off screen. Rebuild from what is stored, which is exactly what the user
            // expects a stored record to be able to do.
            Log.i(
                TAG,
                "reanalyze: the message is off screen; rebuilding from the stored scope " +
                    "(talker=" + s.talker + " msgId=" + s.msgId + ")",
            )
            val text = s.text.ifBlank { rec?.text.orEmpty() }
            if (text.isBlank()) {
                Log.w(TAG, "reanalyze: no stored text either; cannot rebuild")
                toastNow("\u843d\u804a\uff1a\u65e0\u6cd5\u91cd\u65b0\u5206\u6790\uff08\u65e0\u5b58\u50a8\u5185\u5bb9\uff09")
                return
            }
            reanalyzeFromStored(key, s, text)
            return
        }

        val anchorView = rec?.anchor ?: (rv?.let { rowFromAnchor(it as View) }) ?: null
        val anchor = anchorView ?: (rec?.anchor)
        if (anchor == null && liveMsg == null) {
            Log.w(TAG, "reanalyze: nothing to draw from")
            toastNow("\u843d\u804a\uff1a\u65e0\u6cd5\u91cd\u65b0\u5206\u6790")
            return
        }

        Log.i(TAG, "reanalyze: mode=" + s.mode + " with the live message; re-generating")
        deleteCardQuietly(key)
        val finalMsg = liveMsg
        val finalAnchor = anchor ?: lastRv?.let { it as? View }
        toastNow("\u843d\u804a\uff1a\u6b63\u5728\u91cd\u65b0\u5206\u6790\u2026")
        main.post {
            runAnalysis(
                activity = finalAnchor?.let { findActivity(it.context) },
                anchorView = finalAnchor ?: return@post,
                rv = rv,
                all = all,
                msg = finalMsg,
                mode = s.mode,
                up = s.up,
                down = s.down,
                startMs = s.startMs,
                endMs = s.endMs,
                forceRefresh = true,
            )
        }
    }

    /**
     * item 5: re-analyses a stored record whose message is no longer on screen.
     *
     * The window is rebuilt from the stored HISTORY for that chat, centred on the message id, so the
     * same scope produces the same kind of context without needing the live list at all.
     */
    /**
     * 用**本地历史记录**（而不是活着的消息对象）重建一次分析。
     *
     * ★ v10.3.0 说明：这条路径**不会带图片**，这是它的固有限制，不是遗漏。
     * 它只在"消息已经滚出屏幕、拿不到消息对象"时才会走到，而图片路径存在
     * `field_imgPath` 字段上 —— 那个字段只存在于**消息对象**里，历史记录
     * （[HistoryStore]）存的是文本行，本来就没有媒体信息可用。
     * 换句话说：这里没有图可带，不是"忘了带"。
     *
     * 与之相对，用户看到的那条消息**还在屏幕上**时，重新分析会走 [runAnalysis]，
     * 那条路径是带图的（见其中 `collectImages` 的调用）。
     * 所以同一个"重新分析"按钮，在屏幕内的消息上能看图、在滚出屏幕的记录上只能看文字 ——
     * 这是可接受的降级：前者是用户真实在看的上下文，后者只是历史回放。
     */
    private fun reanalyzeFromStored(key: String, s: Scope, text: String) {
        val stored = try {
            HistoryStore.lines(s.talker)
        } catch (_: Throwable) {
            emptyList()
        }
        if (stored.isEmpty()) {
            Log.w(TAG, "reanalyzeFromStored: no stored history for " + s.talker)
            toastNow("\u843d\u804a\uff1a\u8be5\u804a\u5929\u65e0\u5386\u53f2\u8bb0\u5f55\uff0c\u8bf7\u6253\u5f00\u8be5\u804a\u5929\u540e\u91cd\u8bd5")
            return
        }

        // Build the prompt window from the stored lines, using the same rules as the live path.
        val summary = buildString {
            for (l in stored) {
                append(if (l.isSend) "\u6211" else (l.speaker ?: "\u5bf9\u65b9"))
                append("\uff1a").append(l.text).append('\n')
            }
        }
        val cfg = currentConfig()
        if (cfg == null || cfg.apiKey.isBlank()) {
            toastNow("\u843d\u804a\uff1a\u8bf7\u5148\u914d\u7f6e API Key")
            return
        }

        Log.i(TAG, "reanalyzeFromStored: " + stored.size + " stored line(s) for " + s.talker)
        toastNow("\u843d\u804a\uff1a\u6b63\u5728\u91cd\u65b0\u5206\u6790\u2026")
        main.post { setState(key, CardState.Loading, s.talker, s.msgId, text, s.mode, s.up, s.down, s.startMs, s.endMs) }

        io.execute {
            try {
                val target = ChatLine(0L, false, text)
                // v6.0.0: the re-analysis path filters on the template's words too. It used to take the
                // stored lines verbatim, so a re-analysis could re-introduce a line the first run had
                // dropped -- the same word would appear to work once and then stop working.
                val prompt = PromptBuilder.build(
                    lines = stored.map {
                        ChatLine(it.time, it.isSend, it.text, it.speaker)
                    },
                    target = target,
                    mode = s.mode,
                    myWxid = myWxid(),
                    focusSpeaker = null,
                    extraBlockWords = templateBlockWords(),
                )
                val (result, reply) = OpenAiClient(cfg).analyzeWithUsage(prompt)
                main.post {
                    cards[key] = CardState.Done(result)
                    persist(key)
                    installPanelProviders()
                    com.luoliaoo.wechat.ui.FloatingPanel.attach(app)
                    com.luoliaoo.wechat.ui.FloatingPanel.showCard(key)
                    // item 4: the token line for this run.
                    // item 1: remember it on the scope so it is persisted with the card.
                    scopes[key]?.let { sc ->
                        scopes[key] = Scope(
                            talker = sc.talker, msgId = sc.msgId, text = sc.text,
                            usagePrompt = reply.promptTokens,
                            usageCompletion = reply.completionTokens,
                            mode = sc.mode, up = sc.up, down = sc.down,
                            startMs = sc.startMs, endMs = sc.endMs,
                        )
                    }
                    persist(key)
                    com.luoliaoo.wechat.ui.FloatingPanel.setUsage(
                        key, reply.promptTokens, reply.completionTokens,
                    )
                    Log.i(TAG, "reanalyzeFromStored: done, tokens=" + reply.totalTokens)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reanalyzeFromStored failed: " + t.message)
                main.post {
                    setState(
                        key, CardState.Error(t.message ?: "\u91cd\u65b0\u5206\u6790\u5931\u8d25"),
                        s.talker, s.msgId, text, s.mode, s.up, s.down, s.startMs, s.endMs,
                    )
                    installPanelProviders()
                    com.luoliaoo.wechat.ui.FloatingPanel.refresh()
                }
            }
        }
    }


    /** Removes a card without the toast, used by 重新分析. */
    private fun deleteCardQuietly(key: String) {
        cards.remove(key)
        turns.remove(key)
        CardStore.remove(key)
        records.remove(key)?.let { }
        Log.i(TAG, "deleteCardQuietly: " + key)
    }

    /**
     * item 1 (earlier): replaces the "AI 正在思考 ing" placeholder with the real answer.
     *
     * If the answer is empty the placeholder is removed as well, so a card never keeps a spinner that
     * will not resolve.
     */
    private fun settlePendingTurn(key: String, answer: String) {
        try {
            val list = turns[key] ?: return
            val i = list.indexOfFirst { it.pending }
            if (i >= 0) {
                if (answer.isBlank()) {
                    list.removeAt(i)
                } else {
                    list[i] = ChatTurn(fromMe = false, text = answer)
                }
            } else if (answer.isNotBlank()) {
                list.add(ChatTurn(fromMe = false, text = answer))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "settlePendingTurn failed: " + t.message)
        }
    }

    fun deleteCard(key: String) {
        Log.i(TAG, "deleteCard: " + key)
        cards.remove(key)
        scopes.remove(key)
        turns.remove(key)
        records.remove(key)
        anchors.remove(key)
        anchors.remove("anchor:" + key)
        CardStore.remove(key)

        // Forget any row that was pointing at this key.
        synchronized(rowKeys) {
            val it = rowKeys.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value == key) it.remove()
            }
        }
        synchronized(knownRows) {
            val it = knownRows.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value == key) it.remove()
            }
        }

        // Remove the card by OWNER KEY, which needs no message lookup and therefore cannot fail.
        // The previous version located the row via messageOfRow(row), which returns null for rows
        // WeChat does not tag -- so the card was never actually removed.
        var removed = 0
        try {
            val rv = lastRv
            if (rv != null) {
                for (row in CardRenderer.attachedRows(rv)) {
                    val found = ArrayList<com.luoliaoo.wechat.ui.AnalysisCard>(2)
                    com.luoliaoo.wechat.ui.AnalysisCard.findAllIn(row, found)
                    for (card in found) {
                        if (card.ownerKey == key) {
                            (card.parent as? ViewGroup)?.removeView(card)
                            removed++
                            Log.i(TAG, "deleteCard: removed the card from a row")
                        }
                    }
                    if (removed > 0) row.requestLayout()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "deleteCard: stripping failed: " + t.message)
        }
        // Drop the overlay card as well (the card no longer lives inside WeChat's tree).
        try {
        } catch (_: Throwable) {
            // best effort
        }
        Log.i(TAG, "deleteCard done: removed=" + removed)
        toastNow(
            if (removed > 0) "\u843d\u804a\uff1a\u5df2\u5220\u9664\u8be5\u5361\u7247"
            else "\u843d\u804a\uff1a\u5361\u7247\u5df2\u5220\u9664\uff08\u5f53\u524d\u4e0d\u5728\u5c4f\u5e55\u4e0a\uff09",
        )
    }

    /**
     * Multi-turn follow-up (item 9): appends my question, asks the model with the conversation
     * as context, then appends the answer as a new bubble.
     */
    fun followUp(key: String, question: String) {
        val cfg = currentConfig()
        if (cfg == null || cfg.apiKey.isBlank()) {
            toastNow("\u843d\u804a\uff1a\u8bf7\u5148\u914d\u7f6e API Key")
            return
        }
        val list = turns.getOrPut(key) { java.util.Collections.synchronizedList(ArrayList()) }
        list.add(ChatTurn(fromMe = true, text = question))
        // item 1: a placeholder answer, so the card shows "AI 正在思考 ing" instead of looking frozen.
        list.add(ChatTurn(fromMe = false, text = "", pending = true))
        persist(key)
        reconcile()
        toastNow("\u843d\u804a\uff1a\u6b63\u5728\u8ffd\u95ee\u2026")

        val s = scopes[key]
        val rv = lastRv
        val msg = if (s != null) findMessageById(rv, s.talker, s.msgId) else null

        io.execute {
            // Rebuild the context from the live messages when possible; otherwise use the
            // analysis summary alone so a follow-up still works after a restart.
            val lines = if (rv != null && s != null) {
                val all = try {
                    chatContext.adapterMessages(rv)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (all.isNotEmpty() && msg != null) {
                    chatContext.collect(all, msg, s.mode, s.up, s.down, s.startMs, s.endMs)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val summary = (cards[key] as? CardState.Done)?.result?.let { r ->
                buildString {
                    r.question.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                    for (o in r.realIntent) {
                        append(o.label).append(' ').append(o.probability).append("%\n")
                    }
                    if (r.angerLevel >= 0) append("生气等级 ").append(r.angerLevel).append("/10")
                }
            }.orEmpty()

            val target = if (msg != null) {
                // v10.0.0: displayText, so a follow-up on a VOICE message uses its transcript.
                ChatLine(access.createTime(msg), access.isSend(msg), displayText(msg))
            } else {
                ChatLine(0L, false, "")
            }
            val context = PromptBuilder.followUpContext(lines, target, summary, myWxid())

            /* ★ v10.3.0: 流式追问（数据模型驱动）。
               ============================================================
               【设计要点，改之前先读完 —— 我第一版在这里走错过方向】
               第一版是"找到屏幕上那个活着的 TextView，把字直接写进去"。那是错的，
               而且错得很隐蔽：本模块的卡片是 **overlay**，画在悬浮面板里，
               `FloatingPanel.renderCard()` **每次 render 都 new 一张全新的 AnalysisCard**。
               于是：
                 * 抓到的控件引用，下一次重画就变成被丢弃的旧对象；
                 * 写进去的字，下次重画就被覆盖回去；
                 * 而且我最初还是去**微信消息列表**里找的，那里根本没有卡片。
               现象就是"一个字都不流、最后闪一下、然后卡在 AI 正在思考 ing"。

               【现在的路子】把"已经收到的半截答案"**放进数据模型**，让既有的渲染路径去画：
                 * 收到的增量写进 `ChatTurn.partial`；
                 * 调**本来就有**的 `FloatingPanel.refresh()` 重画；
                 * 全程不碰任何 View 引用。
               这样复用唯一一条已验证可用的渲染路径，没有活视图查找、没有跨帧残留引用，
               崩溃面也最小。`ChatTurn.partial` 与 `pending` 一起进 CardStore，重启也不丢。
               ============================================================ */
            val speedCps = com.luoliaoo.wechat.data.StreamSpeed.clamp(cfg.streamSpeed)
            /* speedCps == 0 表示"不限速"（收到即显示）。用一个布尔量表达这个分支，
               比在下游到处比较 `interval == 0L` 清楚 —— 尤其是节拍已经与"每字间隔"解耦之后，
               interval 不再是这条路径上的判据。 */
            val unlimited = speedCps == com.luoliaoo.wechat.data.StreamSpeed.OFF
            /* 已收到的完整文本。用 AtomicReference 而不是 StringBuilder：后者非线程安全，
               网络线程 append 与主线程读取并发会读到半截（表现为末尾缺字）。
               这里整段替换不可变 String，主线程读到的永远是某个完整时刻的快照。 */
            val received = java.util.concurrent.atomic.AtomicReference("")
            /* 请求是否已结束。打字机靠它区分「暂时没有新字」与「永远不会再有了」。 */
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            /* 收尾开关：打字机与请求结束两条路都可能收尾，撞上会把答案落定两次。 */
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            /* 已揭示到第几个字（打字机用）。0 表示不限速模式，直接全量显示。 */
            var revealed = 0

            /* 打字机的**刷新节拍**。
               为什么不能直接按 interval 定时：interval 是"每字间隔"，最快档 300 字/秒 = 每 3ms 一次，
               比屏幕刷新（约 16.7ms/帧）还快 5 倍。那样大部分刷新都落在同一帧里被丢掉，
               纯属白做重排 —— 表现为"字没变快，手机却发烫 + 屏幕在抖"。
               所以节拍单独定：**每秒最多刷新 60 次**，每次按经过的时间算出"此刻应该显示到第几个字"。
               这样无论用户把速度拉到多快，刷新次数都有硬上限，而显示速度仍然精确等于用户设定值。 */
            /* 刷新节拍：夹在 16..50ms 之间，也就是**最多每秒 60 次**（与屏幕刷新同频）。
               下界 16ms：再快也超过屏幕刷新率，多余的刷新会落在同一帧里被丢掉，纯属白做重排
                 （表现为"字没变快，手机却发烫 + 屏幕在抖"）。
               上界 50ms：再慢就会让相邻两次刷新之间积压太多字，看起来像一顿一顿地跳。
               跟 [interval] 挂钩是为了在**慢速**时不空转：例如 5 字/秒 = 每 200ms 一个字，
               若仍固定 16ms 醒一次，十几次唤醒都是白跑（算出来 due 没变、什么都不用画）。
               夹取本身也兜住了"无上限"：即使 speedCps 极大导致 interval = 1ms，
               也会被夹到 16ms，不会出现每秒上千次的空转。 */
            val interval = com.luoliaoo.wechat.data.StreamSpeed.intervalMs(speedCps)
            val tickMs = if (unlimited) 16L else interval.coerceIn(16L, 50L)
            /* 打字机的坐标：从 [basePos]（当时已显示到第几个字）出发，在 [baseAt] 时刻起步，
               之后按 speedCps 线性前进。
               WHY 是一对而不是只有一个起始时刻：因为下面在"追上可用文本"时必须能**把基准挪走**
               （见 ticker 里的重置逻辑），只记一个起始时刻就无法表达"从当前位置重新计时"。 */
            var baseAt = android.os.SystemClock.uptimeMillis()
            var basePos = 0

            /* 把"当前该显示的文本"写回数据模型，并触发**增量**刷新。
               WHY 必须在主线程：改的是共享的 turns 列表，且要碰 UI。
               WHY 调 streamText 而不是 refresh：refresh 会 removeAllViews 重建**整个面板**
               （标题栏、四个按钮、联系人行、用量行全都要重来），每来一个字就闪一次屏幕 ——
               那是用户实测到的"疯狂闪屏"。streamText 走快路径只改那一条气泡的 text；
               只有目标失效时才回退到一次重画（且整条流式最多回退一次）。 */
            fun publish(reveal: Int) {
                val fullText = received.get()
                val shownText = if (reveal >= fullText.length) fullText else fullText.substring(0, reveal)
                main.post {
                    try {
                        setTurnPartial(key, shownText)
                        com.luoliaoo.wechat.ui.FloatingPanel.streamText(key, shownText)
                    } catch (t: Throwable) {
                        Log.w(TAG, "stream publish failed: " + t.message)
                    }
                }
            }

            /* 唯一收尾点。先让打字机停下，再落定完整答案。
               顺序不能反：落定会重建卡片，若打字机之后还有一帧在飞，就会把旧文本又写回去。
               停打字机用 [finished] 这个原子标志（它同时兼任"已收尾"的判据），
               而不是 removeCallbacks：后者需要一个稳定的 token，而 token 必须在 ticker 之前声明，
               声明顺序会让代码读起来是"先有收尾、后有计时器"，反而更容易被后人改错。 */
            fun finish(answer: String) {
                if (!finished.compareAndSet(false, true)) return
                done.set(true)
                main.post {
                    settlePendingTurn(key, answer)
                    persist(key)
                    /* ★ 落定走 settleStreamText，而不是 reconcile()。
                       reconcile 会调 FloatingPanel.refresh() → render()，
                       而 render 的第一步是 removeAllViews() —— **整个面板拆掉重画**，
                       屏幕上就是用户报的那"闪一下"。而这次重画在视觉上是多余的：
                       打字机已经把整段答案逐字显示完了，屏幕上那条气泡的内容与最终答案一致。
                       settleStreamText 就地收尾（内容相同时不做任何视觉改动），
                       只有在答案是空的时候才真的重建（那种情况下占位气泡必须整条消失）。 */
                    try {
                        com.luoliaoo.wechat.ui.FloatingPanel.settleStreamText(key, answer)
                    } catch (t: Throwable) {
                        Log.w(TAG, "settle stream text failed: " + t.message)
                        reconcile()
                    }
                }
            }

            /* 按时长"追平"的打字机：每次醒来根据已经过的时间重算该显示到第几个字。
               WHY 用"追平"而不是"每次 +1 字"：定时器不保证准时（主线程忙时会晚点），
               逐次 +1 会把延迟累积成"越写越慢"，长回答能慢出好几秒。
               按时间算则任何一次晚到都会在下一次自动补上，总时长始终贴合用户设定的速度。

               ─────────────────────────────────────────────────────────────────────
               【⛔ 这里曾经有一个"攒信用"的 bug，表现是"思考完瞬间蹦出一大段、然后才慢慢输出"】
               第一版把计时基准设在**请求发起的那一刻**，然后永远不挪。可是首字延迟有几秒 ——
               模型在思考、一个字都还没吐出来。这几秒里时间照样在累积，等于**攒下了一大笔"信用"**。
               等第一段文字到达时，一算 `已过 4 秒 × 45 字/秒 = 180 字`，
               于是瞬间把 180 个字全放出来，之后才回到正常速度。视觉上就是**断成两截**。
               网络中途卡顿同理：卡 2 秒就攒 90 个字的信用，恢复时猛地跳一大段。

               【判据与修法】"没有文本可显示"的等待**不算数**。
               于是每当我们发现打字机已经追平了当前可用文本（`revealed >= fullText.length`），
               就把基准挪到这里（`baseAt = now; basePos = revealed`），等于宣布：
               "从这一刻、这个位置重新开始计时"。
               这样无论首字延迟多久、中途卡多少次，出字速度永远精确等于用户设定值，
               而且**永远不会出现追赶式的大跳**。
               ───────────────────────────────────────────────────────────────────── */
            val ticker = object : Runnable {
                override fun run() {
                    // 已收尾就不再自己续命（见 finish 的注释）。
                    if (finished.get()) return
                    try {
                        val fullText = received.get()
                        if (speedCps == com.luoliaoo.wechat.data.StreamSpeed.OFF) {
                            // 不限速：收到即全量显示，没有打字机节流。
                            if (revealed < fullText.length) {
                                revealed = fullText.length
                                publish(revealed)
                            }
                        } else if (revealed < fullText.length) {
                            val now = android.os.SystemClock.uptimeMillis()
                            /* 到此刻"应该"已经显示了几个字（按用户设定的字/秒换算）。
                               ─────────────────────────────────────────────────────────────
                               【⚠️ 上限取消后这里必须防溢出，否则会卡死】
                               `(now - baseAt) * speedCps` 在 Long 里不会溢出，
                               但**除以 1000 之后仍可能超出 Int 范围**，此时 `.toInt()` 会
                               **回绕成负数**。后果不是"慢一点"，而是：
                                 due 为负 → `due > revealed` 永远不成立 → revealed 永不前进
                                 → 下面那句"追平就重置基准"也不成立 → 基准时间越攒越大
                                 → due 一直在乱值之间跳，**文字一个字都不显示、也不会收尾**。
                               速度上界从 20 放开到 Int 上限之后，这条路从"理论上可能"
                               变成了"用户随手填个大数就会踩到"，所以必须在这里挡死。
                               做法：在 Long 域里先夹到"当前可用文本长度"再转 Int ——
                               反正超过长度的部分本来也没有字可显示，夹掉不会有任何损失。 */
                            val dueLong = basePos.toLong() +
                                ((now - baseAt) * speedCps.toLong() / 1000L)
                            val due = dueLong.coerceAtMost(fullText.length.toLong()).toInt()
                            if (due > revealed) {
                                revealed = due
                                publish(revealed)
                            }
                        }
                        /* 追平了可用文本 ⇒ 说明此刻的等待是"上游还没给字"，不是"打字机还没吐完"。
                           把基准挪到当前位置，把这段等待的时间丢掉（见上面的大段说明）。
                           放在 publish 之后、判断收尾之前，两个分支都能覆盖到：
                           不限速时 revealed 已等于长度，同样会走到这里重置（无害）。 */
                        if (revealed >= fullText.length) {
                            baseAt = android.os.SystemClock.uptimeMillis()
                            basePos = revealed
                        }
                        if (done.get() && revealed >= fullText.length) {
                            finish(fullText)
                            return
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "stream ticker failed: " + t.message)
                        finish(received.get())
                        return
                    }
                    main.postDelayed(this, tickMs)
                }
            }

            Log.i(TAG, "follow-up stream start: speed=" + speedCps + " cps tick=" + tickMs + "ms key=" + key)
            // 在第一个增量到达之前复位面板的流式状态（见 FloatingPanel.beginStream 的说明）。
            try {
                com.luoliaoo.wechat.ui.FloatingPanel.beginStream(key)
            } catch (_: Throwable) {
            }
            main.postDelayed(ticker, tickMs)

            val reply = try {
                OpenAiClient(cfg).followUpStreamWithUsage(context, list.toList()) { delta ->
                    val now = received.get() + delta
                    received.set(now)
                    /* 不限速模式下由这里直接发布，不必等下一拍 —— 用户选"不限速"要的就是"尽快看到"。
                       有节流时不在这里发布：由 ticker 按时间追平，避免每个增量都刷一次屏。 */
                    if (unlimited) publish(Int.MAX_VALUE)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "follow-up stream failed: " + t.message)
                null
            }
            Log.i(
                TAG,
                "follow-up stream end: replyLen=" + (reply?.text?.length ?: -1) +
                    " receivedLen=" + received.get().length + " tokens=" + (reply?.totalTokens ?: 0),
            )

            // item 4: refresh the token line once the follow-up answer has arrived.
            if (reply != null && reply.hasUsage) {
                try {
                    main.post {
                        com.luoliaoo.wechat.ui.FloatingPanel.setUsage(
                            reply.promptTokens, reply.completionTokens,
                        )
                    }
                } catch (_: Throwable) {
                    // usage is informational
                }
            }

            /* 优先用解析出的完整回答；若服务端忽略 stream 直接返回整段 JSON，reply.text 已是正文；
               若请求整体失败，退回已收到的增量（可能为空 → 占位气泡被移除，不留假 spinner）。 */
            val answer = reply?.text.orEmpty().ifBlank { received.get() }
            received.set(answer)
            /* 标记"不会再有新内容了"，然后交给 ticker 收尾。
               WHY 不在这里直接把全文显示出去：那样会把打字机的作用一笔抹掉 ——
               答案一到就整段蹦出来，用户设定的"逐字速度"形同虚设
               （我上一版就是在这里多发了一次全量 publish，属于自己挖的坑）。
               正确做法是只置标志，让 ticker 按它自己的节奏把剩下的字吐完，吐完再由它收尾。 */
            done.set(true)
            if (unlimited) {
                // 不限速模式没有节流，ticker 一拍之内就会追平并收尾；这里直接收尾更快。
                finish(answer)
            } else {
                main.post {
                    // ticker 已经追平（答案很短、或上游比打字机慢）→ 立刻收尾，不必再等下一拍。
                    if (revealed >= answer.length) finish(answer)
                }
            }
        }
    }

    /**
     * ★ v10.3.0: 把流式"半截答案"写进数据模型（主线程）。
     *
     * 这就是**唯一**的流式落笔点 —— 不碰任何 View。渲染完全交给既有的
     * `FloatingPanel.refresh()` → `renderCard()` → `AnalysisCard.bubble()` 那条路径，
     * 因为那条路径已经被证明能正确画出追问气泡。
     *
     * 找不到 pending 那条就什么都不做：可能用户已经切走、或请求已被取消，
     * 此时静默跳过比往错误的条目上写更安全。
     */
    private fun setTurnPartial(key: String, partial: String) {
        try {
            val list = turns[key] ?: return
            val i = list.indexOfFirst { it.pending }
            if (i < 0) return
            // 只有内容真的变了才写，避免每帧都触发一次重画。
            if (list[i].partial == partial) return
            list[i] = list[i].copy(partial = partial)
        } catch (t: Throwable) {
            Log.w(TAG, "setTurnPartial failed: " + t.message)
        }
    }

    /** Finds a message in the live adapter by talker + msgId (works after scrolling). */
    private fun findMessageById(rv: Any?, talker: String, msgId: Long): Any? {
        if (rv == null || msgId == 0L) return null
        val all = try {
            chatContext.adapterMessages(rv)
        } catch (_: Throwable) {
            emptyList()
        }
        for (m in all) {
            if (access.msgId(m) == msgId && access.talker(m) == talker) return m
        }
        return null
    }

    /** Removes every card (item 7 of the previous batch). */
    fun clearAllCards() {
        Log.i(TAG, "clearAllCards: " + cards.size + " card(s)")
        // Nothing is inserted into WeChat's tree any more; the panel is the only surface.
        try {
            com.luoliaoo.wechat.ui.FloatingPanel.showList()
        } catch (_: Throwable) {
            // best effort
        }
        cards.clear()
        scopes.clear()
        turns.clear()
        CardStore.clearAll()
        ResultCache.clearAll()
        HistoryStore.clearAll()
        toastNow("\u843d\u804a\uff1a\u5df2\u6e05\u7a7a\u6240\u6709\u5206\u6790\u8bb0\u5f55")
    }

    fun cardCount(): Int = cards.size

    // ----------------------------------------------------------------- utils

    // ---------------------------------------------------------------- live history

    /**
     * item 1: records a message into the chat history AS IT ARRIVES.
     *
     * Called from the chat hook for every message it binds. Everything here is cheap and defensive:
     *
     *   * the work is handed to a SINGLE background thread, so the UI is never blocked on disk
     *   * the message must have a non-empty text AND a real timestamp, so a half-built row (the list binds
     *     a row before its text is set) is skipped rather than stored as an empty or wrong line
     *   * a message already recorded is skipped -- HistoryStore keys lines by (talker, timestamp), so even
     *     a repeated bind cannot duplicate or overwrite a line
     *   * writes are BATCHED: at most one flush per talker per second
     *
     * This gives the user live history without a per-message disk write.
     */
    fun onLiveMessage(msg: Any) {
        try {
            val tk = access.talker(msg)
            if (tk.isBlank()) {
                // Logged: a blank talker means the feed reached us with a message we cannot key,
                // which is a different fault from the feed never reaching us at all.
                // v6.0.0: routed through logLiveReject, which de-duplicates by signature on Soul so a
                // repeated cause cannot crowd out a new one out of the 6-line budget.
                logLiveReject(
                    "blank-talker:" + msg.javaClass.simpleName,
                    "live: rejected a message with a blank talker (" + msg.javaClass.simpleName + ")",
                )
                return
            }
            // ============================================================ v10.0.0: 记住当前会话
            //
            // ★★ 这一小段必须在语音分支**之前** —— 它修的是一个"语音转写写入本地时灵时不灵"的 bug ★★
            //
            // 【原 bug】这段代码本来在下面（文本路径的末尾），而语音分支在它之前就 `return` 了。
            // 于是**只要用户滑到的这一屏里第一条是语音**，`currentTalkerHint` 就不会被更新。
            // 而 handleVoice 把 `currentTalkerHint == talker` 当作"用户还在这个会话"的判据传给
            // WeChatVoice 的 allowed() —— 它在轮询里每 300ms 复查一次，一旦为 false 就放弃转写。
            //
            // 结果就是两个必现的坏情况：
            //   1. `enterChatScreen()` 刚把 hint 清成 ""（第 232 行），此时进任何聊天页，
            //      第一条语音的 allowed() 立刻返回 false -> 转写被放弃；
            //   2. 更糟的是，handleVoice 在**投递之前**就已经把 msgId 加进了 voiceHandled，
            //      而放弃路径**不会**把它撤回来（只有"队列满"那条路会撤）——
            //      于是这条语音被永久标记为"处理过"，**再也不会重试**。
            //
            // 这解释了用户报的"前面好好的、后来坏了"：我上次真机验证时，恰好有文本消息先绑定、
            // 把 hint 填上了，所以蒙对了；一旦先进一个有语音的会话就必坏。
            //
            // 【修法】把"记住当前会话"提到所有分支之前。它本来就不该只在文本路径里做 ——
            // 会话是哪一个，与这一条消息是文本还是语音毫无关系；语音同样是权威的说话人来源。
            if (!tk.equals(currentTalkerHint)) {
                currentTalkerHint = tk
                // `tk` is the talker OF THIS MESSAGE, so it is authoritative -- and the ring/hint are
                // reset on every screen change, so an old chat cannot keep ownership of the panel.
                com.luoliaoo.wechat.ui.FloatingPanel.currentTalker = tk
                Log.i(TAG, "live: current chat = " + tk + " gen=" + chatGeneration)
            }

            // ============================================================ v10.0.0: VOICE
            //
            // 【必须在 text.isBlank() 之前判断 —— 这一条是实测出来的，不是推导的】
            //
            // 我最初把语音分支写在 `if (text.isBlank())` 里，那是**永远不会触发**的死代码。
            // 真机日志（_logs/20260923_145223/all.txt:50522）证明微信语音的 field_content
            // **不是空的**，而是整段 XML：
            //
            //     <msg><voicemsg endflag="1" voiceformat="4" voicelength="5465" length="9561"
            //          aeskey="..." voiceurl="..." clientmsgid="..." isPlayed="true"></voicemsg></msg>
            //
            // 所以语音消息会**顺利通过** blank-text 检查（XML 非空），然后被当作普通文本写进本地
            // 聊天记录 —— 用户的历史文件里躺着一大段 aeskey/voiceurl。这是改造前的既存行为
            // （onLiveMessage 从来没有 isText 判断，只有 recordOne 有），本次一并修正。
            //
            // 判断放在 talker 检查之后、content 读取之前：语音不参与任何文本路径，
            // 也不该让它的 XML 进入后面的时间戳/合成 id 逻辑。
            if (access.isVoice(msg)) {
                handleVoice(msg, tk)
                return
            }

            val text = displayText(msg)
            if (text.isBlank()) {
                logLiveReject(
                    "blank-text",
                    "live: rejected a blank-text message for " + tk,
                )
                return
            }

            val isSend = access.isSend(msg)
            val when_ = access.createTime(msg)
            // A real message time is a plausible epoch; anything smaller is a placeholder. The time is also
            // part of the synthetic key below, so a message without one cannot be keyed safely.
            if (when_ < 1_000_000_000_000L) {
                // v6.0.0: the SIGNATURE carries the actual value (and its magnitude class), because on
                // Soul this specific rejection is the one that would silently empty the history if the
                // host reports SECONDS rather than milliseconds -- and the value is what tells the two
                // apart on a first device run.
                logLiveReject(
                    "bad-time:" + (when_ / 1_000_000_000L) + ":" + (when_ > 0L),
                    "live: rejected a message with time=" + when_ + " for " + tk +
                        " (magnitude=" + (if (when_ <= 0L) "none" else (when_ / 1_000_000_000L).toString() + "e9") +
                        ") -- if this is ~1.7e9 the host reports SECONDS and toMillis did not normalise it",
                )
                return
            }

            // THE FIX: a freshly bound message usually has field_msgId == 0, because WeChat assigns the
            // database id only when the conversation is written. Requiring a nonzero id therefore skipped
            // exactly the messages this recorder exists to capture -- history only ever appeared after an
            // analysis. A STABLE synthetic key is used instead, so nothing is skipped and duplicates are
            // still impossible:
            //     msgId  ->  msgSvrId  ->  a deterministic hash of (talker, time, isSend, text)
            val real = access.msgId(msg)
            val svr = access.msgSvrId(msg)
            val id: Long = when {
                real != 0L -> real
                svr != 0L -> svr
                else -> syntheticId(tk, when_, isSend, text)
            }

            val line = com.luoliaoo.wechat.data.HistoryStore.Line(
                msgId = id,
                time = when_,
                isSend = isSend,
                text = text,
                speaker = null,
            )

            // v10.0.0: 「记住当前会话」原本在这里，已上移到本函数开头（语音分支之前）——
            // 放在这里会让语音消息（提前 return 的那条路）永远不更新 currentTalkerHint，
            // 详见本函数开头那段注释。

            // item 2: just STAGE it. A single 2-second timer flushes everything, so nothing is written
            // per message and the cost does not scale with how fast the chat moves.
            val staged = livePending.getOrPut(tk) { java.util.LinkedHashMap() }
            synchronized(staged) {
                staged[id] = line
            }
        } catch (t: Throwable) {
            Log.w(TAG, "live record failed: " + t.message)
        }
    }

    // ================================================================ v10.0.0 语音转文字

    /**
     * **已经成功转写并写进本地记录**的语音（msgId 集合）。
     *
     * ══════════════════════════════════════════════════════════════════════════════════
     * ★★ v10.0.0 修的一个真 bug：这个集合的语义被我用错了，导致语音转写"时灵时不灵" ★★
     *
     * 【它现在表示什么】只表示"**已经成功写入过**，不必再试"。
     * 它**不再**表示"正在转写中"，那是 [voiceInFlightIds] 的职责 —— 两个概念此前被合成一个集合，
     * 正是 bug 的来源。
     *
     * 【原 bug 的确切机制】
     * 旧代码在**投递转写之前**就把 msgId 加进来（当作"正在进行"的去重），可是：
     *   * 转写**失败**（引擎没启动 / 超时 / 语音文件不在本机）时根本不会回调 onDone，
     *     也就没有任何代码把它从集合里删掉；
     *   * 于是这条语音被**永久**标记为"处理过"，之后无论用户怎么退出重进聊天、怎么滑动，
     *     都不会再试一次。用户看到的就是"转写功能坏了"。
     *
     * 失败的语音里有一大类是**暂时性**的（当时网络不好、微信转写队列忙），
     * 永久拉黑它们显然不对。现在失败一律撤除标记，允许后续重试。
     *
     * 【为什么要防"反复重试刷屏"】靠的是另外两条，而不是这个集合：
     *   1. [voiceInFlightIds] —— 同一条语音在转写途中不会被重复投递；
     *   2. WeChatVoice 内部的**失败冷却**（30 秒，按 source.key）—— 刚失败的不会立刻重来。
     * 两条叠加，既不会重复提交，也不会无限重试。
     *
     * 用 msgId 作键而不是 VoiceSource.key：key 还包含 time/imgPath，一旦微信回填了其中某个字段，
     * key 就会变、去重失效 —— 而 msgId 是这条消息在数据库里的主键，从一开始就定了。
     *
     * 容量上界 512，先进先出淘汰：一个会话里不可能同时有 512 条已转语音，
     * 被淘汰的都是很久以前的，真被重新转一次也无害（微信侧已有结果会直接复用）。
     * ══════════════════════════════════════════════════════════════════════════════════
     */
    private val voiceDone = java.util.Collections.synchronizedSet(
        object : java.util.LinkedHashSet<Long>() {
            override fun add(element: Long): Boolean {
                val ok = super.add(element)
                if (size > 512) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return ok
            }
        },
    )

    /**
     * **正在排队或转写中**的语音 msgId。
     *
     * 【为什么需要它，而不是只用一个集合】"正在进行"与"已完成"必须分开：
     *   * 只判"已完成"-> 转写途中被反复绑定会重复投递同一条语音（浪费、且会互相踩状态）；
     *   * 只判"已登记"-> 就是上面那个 bug：失败后永不重试。
     * 分成两个精确的集合，两个问题同时消失。
     *
     * 与 [voiceInFlight] 计数器的关系：计数器负责**并发上限**（最多 4 条同时在队列里），
     * 这个集合负责**同一条不重复投递**。缺任何一个都会有明确的行为缺陷。
     */
    private val voiceInFlightIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 正在排队/转写中的语音数，用来给并发封顶。 */
    private val voiceInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 同时最多允许几条语音在转写队列里。
     *
     * 【为什么封顶】`WeChatVoice.submitAsync` 用的是**单线程** worker（转写组件本身是单任务的有状态
     * 对象，必须串行）。若用户一次滑进一个有 20 条语音的会话，20 个任务会排成一队，
     * 每条最多 30 秒 —— 队尾的要等好几分钟，期间用户已经滑走了。
     * 4 条既能让正在看的那几条先转出来，又不会堆积。
     * 超出上限的直接放弃（不标记为已处理），下次绑定这条语音时还会再试。
     */
    private val VOICE_QUEUE_LIMIT = 4

    /**
     * v10.0.0: 把一条语音交给微信的转写引擎，转成功就写进本地聊天记录。
     *
     * 【为什么是异步的，且立刻返回】
     * 这个函数在**微信主线程的 onBindViewHolder 里**被调用。转写要好几秒（要上传、要等服务器），
     * 在主线程上等会直接卡死聊天界面 —— 一个绑定周期卡 30 秒，用户会以为微信崩了。
     * 所以这里只做"判断 + 投递"，马上返回；真正的等待发生在 WeChatVoice 自己的后台线程上。
     *
     * 【为什么转不到就不写任何东西】
     * 宁可本地记录里**没有**这条语音，也不要写一条 `[语音转文字]` 加空白、
     * 让用户以为转出来是空的。转写失败只记日志，不进历史。
     */
    private fun handleVoice(msg: Any, talker: String) {
        try {
            // 只在微信生效。转写引擎（TransformComponent）是微信独有的，
            // 其它宿主的 type 编号也各不相同（见 MsgAccess.isVoice 的注释）。
            if (HostApp.current != HostApp.WECHAT) return

            // 开关。用户关掉后，语音既不入库也不触发转写。
            if (!ConfigRepo.voiceToText(app)) return

            // 引擎没连上（DexKit 没定位到、或版本不匹配）就静默跳过 ——
            // WeChatVoice.install 已经把原因写进日志了，这里不再重复刷屏。
            if (!com.luoliaoo.wechat.voice.WeChatVoice.isReady) return

            val msgId = access.msgId(msg)
            // 没有真实 msgId 就无法定位这条语音（转写引擎的 state()/text() 都按 msgId 索引），
            // 也不能安全去重 —— 直接放弃，不要用合成 id 去赌。
            if (msgId == 0L) return

            // 已经成功转写过的，不必再试。
            if (voiceDone.contains(msgId)) return

            // 正在转写中的，不要重复投递（适配器会反复重绑同一行）。
            // ★ 注意这里用的是 voiceInFlightIds 而不是 voiceDone —— 分开这两个概念正是本次修的核心：
            //   用 voiceDone 当"进行中"标记，会让失败后永远无法重试。
            if (!voiceInFlightIds.add(msgId)) return

            // 从这一行起，任何 return / 异常都必须调用 releaseVoiceId(msgId) 撤除"进行中"标记，
            // 否则这条语音会被永久挡住。用 try/finally 统一兜住，而不是在每个分支手写一遍 ——
            // 手写正是上一版漏掉失败路径的原因。
            var handedOff = false
            try {
                val when_ = access.createTime(msg)
                if (when_ < 1_000_000_000_000L) {
                    Log.w(TAG, "voice: skipped id=" + msgId + " (implausible time=" + when_ + ")")
                    return
                }

                val file = access.voiceFile(msg)
                if (file.isBlank()) {
                    // 没有语音文件名就无法与 VoiceTransText.cmsgId 匹配，也读不出结果 —— 放弃。
                    Log.w(TAG, "voice: id=" + msgId + " has no voice file name; cannot transcribe")
                    return
                }

                if (voiceInFlight.get() >= VOICE_QUEUE_LIMIT) {
                    Log.i(TAG, "voice: queue full, deferred id=" + msgId)
                    return
                }

                val isSend = access.isSend(msg)
                val source = com.luoliaoo.wechat.voice.VoiceSource(
                    talker = talker,
                    id = msgId,
                    time = when_,
                    sent = if (isSend) 1 else 0,
                    fileToken = file,
                    serverId = access.msgSvrId(msg),
                )

                voiceInFlight.incrementAndGet()
                Log.i(TAG, "voice: transcribing id=" + msgId + " for " + talker)

                // 【为什么要用一个 AtomicBoolean 守住计数】
                // submitAsync 在**转写成功**时回调 onDone，在失败/放弃时**不回调**。
                // 于是收尾有两个可能的时机，而它们可能都发生：
                //   * 成功 -> onDone 里收尾；
                //   * 一直没成功 -> 35 秒后兜底收尾。
                // 不互斥的话，一条先失败（兜底收尾）又在 35 秒前成功的语音会被收尾两次，
                // 计数越减越小，队列上限形同虚设 —— 最终表现为转写全被"队列满"挡住。
                // 用 compareAndSet 保证**恰好收尾一次**。
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                val settle = {
                    if (settled.compareAndSet(false, true)) {
                        voiceInFlight.decrementAndGet()
                        releaseVoiceId(msgId)
                    }
                }

                handedOff = true
                com.luoliaoo.wechat.voice.WeChatVoice.submitAsync(
                    source = source,
                    allowed = {
                        // 每轮轮询都会问一次：用户还在这个会话、且开关还开着吗？
                        // 切走了就放弃 —— 否则会把 A 会话的语音记录塞进 B 会话。
                        //
                        // ★ 这个判据能成立，依赖 currentTalkerHint 已被本条消息更新过 ——
                        //   所以"记住当前会话"那段必须在 onLiveMessage 里语音分支**之前**执行。
                        //   此前它排在语音分支后面，语音消息永远走不到，于是这里恒为 false、
                        //   每条语音的转写刚投递就被放弃。详见 onLiveMessage 开头那段注释。
                        ConfigRepo.voiceToText(app) && currentTalkerHint == talker
                    },
                    onDone = { transcript ->
                        // 先把成功结果写进本地记录，**再**标记完成 ——
                        // 顺序反了的话，若写入抛异常，这条语音会被当成"已完成"而永不重试。
                        recordVoiceTranscript(source, transcript, when_, isSend)
                        settle()
                    },
                )
                // 兜底：失败路径不回调 onDone，35 秒后（> 转写自身 30 秒超时）强制收尾。
                // 收尾会同时释放并发名额**并撤除进行中标记**，所以失败过的语音下次绑定还会再试
                // （真正的防刷屏由 WeChatVoice 内部按 source.key 的 30 秒失败冷却负责）。
                main.postDelayed({ settle() }, 35_000L)
            } finally {
                // 没成功交接给 submitAsync 的每一条退出路径，都在这里撤掉"进行中"标记。
                if (!handedOff) releaseVoiceId(msgId)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "voice: handleVoice failed: " + t.message)
        }
    }

    /** 撤除"进行中"标记，让这条语音在下次绑定时还能重试。 */
    private fun releaseVoiceId(msgId: Long) {
        try {
            voiceInFlightIds.remove(msgId)
        } catch (_: Throwable) {
            // 一个集合的移除失败不值得影响主流程
        }
    }

    /**
     * v10.0.0: 把转写结果写进本地聊天记录，前缀 `[语音转文字]`。
     *
     * 【msgId 直接用语音自己的 id】
     * 这是安全的，因为 [handleVoice] 对语音**提前 return** 了，语音的 XML 原文不会再被写成一行 ——
     * 所以这个 msgId 在本地记录里是**空缺**的，转写行填进去不会与任何已有行冲突。
     * （若原文行还在，HistoryStore 按 msgId 去重会把这个转写行直接丢掉，功能就会静默失效。)
     */
    private fun recordVoiceTranscript(
        source: com.luoliaoo.wechat.voice.VoiceSource,
        transcript: String?,
        time: Long,
        isSend: Boolean,
    ) {
        try {
            // 可空是刻意的：submitAsync 的回调类型是 (String?) -> Unit，转写失败时根本不会回调，
            // 但类型上仍是可空的。这里统一在入口挡掉 null/空白，而不是在调用点到处判。
            val text = transcript?.trim().orEmpty()
            if (text.isBlank()) return
            val line = com.luoliaoo.wechat.data.HistoryStore.Line(
                msgId = source.id,
                time = time,
                isSend = isSend,
                text = com.luoliaoo.wechat.voice.VoicePrefix.wrap(text),
                speaker = null,
            )
            // 走和普通消息同一条暂存 + 2 秒定时刷新的通道，不另开写盘路径。
            val staged = livePending.getOrPut(source.talker) { java.util.LinkedHashMap() }
            synchronized(staged) {
                staged[source.id] = line
            }
            // ★ 只有**真的写进暂存**之后才标记完成。这就是 [voiceDone] 而非旧的 voiceHandled 的意义：
            //   它如实表示"已成功产出并交付了一行记录"，所以不需要（也不应该）在失败时撤除。
            voiceDone.add(source.id)
            Log.i(
                TAG,
                "voice: staged a transcript for " + source.talker +
                    " (id=" + source.id + " len=" + text.length + ")",
            )

            /* ★★ v10.0.0：转写一完成就**立即落盘**，不等 2 秒定时器 ★★
             *
             * 【用户报的现象，而且是用户自己定位到的】原话：
             *   「1：发送语音 转文字 写入本地 读取不到是因为没有立即写入本地」
             *   「2：当再次发送一次文本信息时 写入后 附带转文字的写入了 说明转文字不会写入
             *     而文本会写入」
             * 两条都准确。第 2 条尤其关键：它说明转写**已经在内存里**、只是没被触发落盘 ——
             * 下一条文本消息到来时，定时器那一轮把暂存区里**攒着的转写一并刷了**，
             * 于是看起来"转写要等下一条消息才写进去"。
             *
             * 【为什么必须立刻写，而不是等定时器】普通文本消息按 2 秒批量落盘是有道理的：
             * 聊天在快速滚动，攒一批再写能避免每行一次 IO。但语音转写完全不同 ——
             *   * 它**频率很低**（用户说一句话才有一条），根本没有批量可言；
             *   * 它**代价很高**（要上传、等服务器、轮询几十秒才拿到结果），
             *     结果到手却让它漂在内存里等定时器，一旦进程被回收就白转了；
             *   * 用户转完语音的**下一步动作就是点「读取」**，所以"可见性"必须在转写完成的那一刻成立。
             *
             * 让可见性依赖"下一条消息什么时候来"，是把两个不相干的时钟绑在了一起。
             * 定时器仍然保留并继续服务普通消息，这里只是给转写补一次立即落盘。
             *
             * 【为什么直接调 flushLive 是安全的】它幂等：取出暂存、写进 HistoryStore（按 msgId 去重）、
             * 清空暂存；暂存为空直接返回。这次先刷掉了转写行，定时器下一轮该刷什么还刷什么。 */
            flushLive(source.talker)
        } catch (t: Throwable) {
            Log.w(TAG, "voice: staging the transcript failed: " + t.message)
        }
    }

    /**
     * item 2: the 2-second history timer.
     *
     * ONE task flushes every talker with staged lines, so the write cost is a constant two seconds
     * regardless of how many messages arrive. Duplicates never reach the disk: HistoryStore keys lines by
     * msgId and skips any it already has.
     *
     * ================================ 5.0.0: THE SAME PIPELINE IN BOTH HOSTS ======================
     *
     * The user reported 「为什么必须点分析才会写入本地，我要的是跟微信一样 每2秒循环写入一次」. That
     * was accurate: the timer only FLUSHES what something else STAGED, and on Douyin nothing staged
     * anything, so each tick found an empty map and returned without writing.
     *
     * The fix is NOT in this method -- it is a bind hook, mirroring how WeChat feeds the recorder:
     *   * WeChat:  `binder.liveMessageSink` fires from the adapter's bind method (line 69)
     *   * Douyin:  DouyinHooks.installMessageBindHook() hooks CellComposeAdapter.onBindViewHolder and
     *              calls onLiveMessage for each row Douyin binds
     *
     * Both then stage into the same map and this timer flushes it every two seconds. So the cadence
     * the user asked for now holds on Douyin with no analysis, and WeChat is untouched.
     * =============================================================================================
     */
    fun startLiveTimer() {
        if (liveTimerStarted) return
        liveTimerStarted = true
        var sched: java.util.concurrent.ScheduledExecutorService? = null
        try {
            sched = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "LuoLiao-live").apply { isDaemon = true }
            }
            sched.scheduleWithFixedDelay(
                {
                    try {
                        val talkers = ArrayList(livePending.keys)
                        for (tk in talkers) flushLive(tk)
                    } catch (t: Throwable) {
                        Log.w(TAG, "live timer tick failed: " + t.message)
                    }
                },
                2000L,
                2000L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            Log.i(TAG, "live history timer started (every 2s)")
        } catch (t: Throwable) {
            Log.w(TAG, "live history timer could not start: " + t.message)
        }
    }

    /**
     * Reads the messages currently on screen and stages them for the next flush.
     *
     * Runs on the TIMER THREAD, never the UI thread, because it walks a live view hierarchy --
     * touching views from a background thread is safe for reads of this kind (no layout is triggered)
     * and keeps the 2-second cadence from ever stalling the chat UI.
     *
     * Every step is defensive: a chat screen can be closed, recycled or rebuilt between two ticks, and
     * a throw here would kill the timer permanently (a ScheduledExecutorService cancels a task that
     * throws), which is a silent, unrecoverable loss of the whole feature.
     */
    private fun collectFromScreen() {
        // 5.0.0 DOUYIN: NOTHING TO DO HERE ANY MORE.
        //
        // This used to walk the view tree every two seconds. The user rejected that approach --
        // 「为什么要用识图啊 跟微信那样不好吗？或者跟抖音按分析触发的一样」 -- and they were right on
        // both counts: scanning views is indirect, and WeChat does not do it.
        //
        // WeChat's live history is PUSHED: `binder.liveMessageSink` fires from the adapter's bind
        // method (line 69) and feeds onLiveMessage immediately. Douyin's equivalent bind point was
        // found and hooked instead -- see DouyinHooks.installMessageBindHook(), which hooks
        // `CellComposeAdapter.onBindViewHolder` (classes29.dex) and calls onLiveMessage for every row
        // Douyin binds.
        //
        // So the pipeline is now the SAME in both hosts: the host tells us about each message, we
        // stage it, and this 2-second timer flushes the staged lines to disk. The timer no longer
        // needs to collect anything, and this method is kept only as the documented seam where the
        // rejected approach used to live.
    }

    /**
     * Flushes the staged lines of one talker. Runs on the timer thread.
     *
     * item 2: duplicates are skipped for us -- HistoryStore keys lines by msgId and ignores any it already
     * holds -- so a re-flush can never write the same message twice.
     */
    /**
     * A stable key for a message that has no database id yet.
     *
     * Deterministic on (talker, time, direction, text), so the SAME message always produces the SAME key:
     * the message is recorded immediately, and re-binding the row cannot create a duplicate. The sign bit
     * is cleared so the value is always positive and cannot collide with a real id by sign.
     */
    private fun syntheticId(talker: String, time: Long, isSend: Boolean, text: String): Long {
        val h = (talker + "|" + time + "|" + isSend + "|" + text).hashCode().toLong()
        // Keep it in a range no real WeChat id uses, and always positive.
        return (h and 0xFFFFFFFFL) or 0x100000000L
    }

    /**
     * v10.0.0: 立刻把某个会话的**暂存行**写进 [HistoryStore]，不等 2 秒定时器。
     *
     * ══════════════════════════════════════════════════════════════════════════════════
     * ★ 这个函数修的是用户报的：「点击读取时不会读取刚刚写入的语音转文字，重启微信之后却可以」
     *
     * 【根因：暂存区与读取路径之间的时序缺口】
     * 语音转写完成后，[recordVoiceTranscript] 把它放进 [livePending]（暂存区），
     * 由 [startLiveTimer] 每 2 秒调 [flushLive] 才真正写进 HistoryStore。这是普通消息也走的设计 ——
     * 为了「不每条消息都写盘」。
     *
     * 但「读取」读的是 `HistoryStore.lines(talker)`，而**暂存区不在 HistoryStore 里**。
     * 于是用户在转写完成后的这 2 秒窗口内点读取，就读不到那条转写 —— 看起来就是"功能没生效"。
     * 重启微信后自然能读到（那时早就 flush 了），这正是用户观察到「重启之后却可以」的原因，
     * 也是这个 bug 只在"刚转写完就点读取"时出现、难以复现的原因。
     *
     * 【为什么修在这里而不是缩短定时器】把 2 秒改小只会缩小窗口、不能消除它，
     * 而且会让写盘频率上升。读取是**用户主动发起**的动作，它需要的是"读到此刻为止的一切"，
     * 所以正确做法是在读取前**主动补齐**，而不是让读取去猜数据有没有落盘。
     *
     * 【为什么安全】[flushLive] 本身是幂等的：它取出暂存、写进 HistoryStore（按 msgId 去重）、
     * 清空暂存；暂存为空时直接返回。多调一次没有任何副作用，定时器下一次该做什么还做什么。
     * ══════════════════════════════════════════════════════════════════════════════════
     */
    fun flushNow(talker: String) {
        try {
            if (talker.isBlank()) return
            // 暂存区按**原始** talker 作键（见 onLiveMessage 的 livePending.getOrPut(tk)），
            // 而调用方可能给的是归一化过的形式，所以这里两种都试一次。
            for (k in listOf(talker, HistoryStore.normaliseKey(talker))) {
                if (k.isBlank()) continue
                if (livePending.containsKey(k)) flushLive(k)
            }
        } catch (t: Throwable) {
            // 补齐失败不能让读取本身失败 —— 读取最多拿到旧数据，与修复前行为一致。
            Log.w(TAG, "flushNow failed for " + talker + ": " + t.message)
        }
    }

    private fun flushLive(talker: String) {
        liveFlushes++
        val staged = livePending[talker] ?: run {
            // Logged so "the timer ran but had nothing to flush" is distinguishable from "the timer
            // never ran" -- the distinction that localises the fault to the feed or to the flush.
            if (liveFlushes <= 3) {
                Log.i(TAG, "live: flush #" + liveFlushes + " for " + talker + " -- nothing staged")
            }
            return
        }
        val batch: List<com.luoliaoo.wechat.data.HistoryStore.Line>
        synchronized(staged) {
            if (staged.isEmpty()) return
            batch = ArrayList(staged.values)
            staged.clear()
        }
        try {
            com.luoliaoo.wechat.data.HistoryStore.record(talker, batch)
            liveWrites++
            Log.i(TAG, "live: wrote " + batch.size + " line(s) for " + talker)
        } catch (t: Throwable) {
            Log.w(TAG, "live flush failed for " + talker + ": " + t.message)
            // Put them back so the next tick retries rather than losing them.
            synchronized(staged) {
                for (l in batch) staged.putIfAbsent(l.msgId, l)
            }
        }
    }

    /** item 8: where the JSON files live, for the manager to display. */
    fun storageLocation(): String = try {
        com.luoliaoo.wechat.data.HistoryStore.location() +
            "  |  " + com.luoliaoo.wechat.data.TemplateStore.dirPath()
    } catch (t: Throwable) {
        "unknown"
    }

    /**
     * item (tap-to-detect): the contact of the chat currently on screen, WITHOUT analysing anything.
     *
     * The newest message that has been bound to the chat list is read directly: each message carries
     * `field_talker`, so the newest one names the conversation being displayed. This is cheap, needs no
     * network, and makes the record list correct the moment the balloon is tapped instead of only after a
     * first analysis.
     *
     * @return the talker id, or "" when no chat is open
     */
    fun detectCurrentTalker(): String {
        // The ring now holds ONLY the current chat's messages (it is cleared on every screen change), so
        // scanning it cannot reach another conversation. A majority vote among the few newest non-blank
        // talkers makes a single bad read harmless.
        val recent = try {
            recentMessages.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        val votes = LinkedHashMap<String, Int>()
        var considered = 0
        for (i in recent.indices.reversed()) {
            if (considered >= 12) break
            val msg = recent[i]
            val tk = try {
                access.talker(msg)
            } catch (_: Throwable) {
                ""
            }
            if (tk.isBlank()) continue
            considered++
            votes[tk] = (votes[tk] ?: 0) + 1
        }

        if (votes.isNotEmpty()) {
            // Highest count wins; the newest wins a tie.
            var best = ""
            var bestN = 0
            for ((k, n) in votes) {
                if (n >= bestN) {
                    bestN = n
                    best = k
                }
            }
            if (best.isNotBlank()) {
                val confirmed = confirmAgainstLiveList(best)
                if (confirmed != lastDetectedTalker) {
                    lastDetectedTalker = confirmed
                    Log.i(TAG, "detect: current chat = " + confirmed +
                        " (votes=" + votes + " gen=" + chatGeneration + ")")
                }
                return confirmed
            }
        }

        // Nothing bound yet in this screen: prefer the live hint.
        if (currentTalkerHint.isNotBlank()) return currentTalkerHint
        return ""
    }

    /**
     * item (talker): validates a talker against the messages currently in the list.
     *
     * The live list is the ground truth -- it is the conversation physically on screen. If the vote and
     * the live list disagree, the live list is used. This is the last line of defence against "chat B
     * shows chat A's records".
     *
     * @return the talker to use, or the input when nothing could be read
     */
    private fun confirmAgainstLiveList(candidate: String): String {
        try {
            val live = ArrayList<String>(4)
            val recent = recentMessages.toList()
            // The newest few messages are the ones the list is showing right now.
            var taken = 0
            for (i in recent.indices.reversed()) {
                if (taken >= 6) break
                val msg = recent[i]
                val tk = try {
                    access.talker(msg)
                } catch (_: Throwable) {
                    ""
                }
                if (tk.isNotBlank()) {
                    live.add(tk)
                    taken++
                }
            }
            if (live.isEmpty()) return candidate
            // If the live messages all agree on someone else, believe them.
            val major = live.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: candidate
            if (major != candidate) {
                Log.w(
                    TAG,
                    "detect: vote said '" + candidate + "' but the live list says '" + major +
                        "'; using the live list (live=" + live + ")",
                )
                return major
            }
        } catch (t: Throwable) {
            Log.w(TAG, "detect: live-list check failed: " + t.message)
        }
        return candidate
    }


    /**
     * v6.0.0: the ACTIVE template's block words, for the LONG-PRESS analysis path.
     *
     * WHY A SEPARATE ACCESSOR: [currentConfig] returns a `Config`, which has no block-word field, and
     * widening `Config` would touch the persisted format. Reading the template's resolved words here
     * keeps [currentConfig]'s behaviour (and every existing caller) untouched.
     *
     * THE BUG THIS FIXES (user: 「屏蔽关键词失败了 ... msg 有用 auth 跟 click 失败」): the long-press path
     * called `PromptBuilder.build` with no block words at all, so only the hardcoded SKIP_MARKERS
     * ("msg"/"了解详情"/":0") filtered anything -- those three are hardcoded, which is exactly why "msg"
     * appeared to work while the user's own added words ("auth", "click") did nothing. The multi-select
     * path passed its words through; this path never did.
     *
     * Returns DEFAULT_BLOCK_WORDS when the store cannot be read, so the three original markers keep
     * working even if the template file is unreadable.
     */
    fun templateBlockWords(): List<String> = try {
        val ctx = com.luoliaoo.wechat.core.ModuleBridge.appContext ?: app
        com.luoliaoo.wechat.data.TemplateStore
            .resolve(ConfigRepo.loadLocal(ctx))
            .blockWords
            .ifEmpty { com.luoliaoo.wechat.data.TemplateStore.DEFAULT_BLOCK_WORDS }
    } catch (t: Throwable) {
        Log.w(TAG, "block words: template read failed (" + t.message +
            "); falling back to the built-in defaults")
        com.luoliaoo.wechat.data.TemplateStore.DEFAULT_BLOCK_WORDS
    }

    fun currentConfig(): Config? {
        val ctx = com.luoliaoo.wechat.core.ModuleBridge.appContext ?: app
        val local = try {
            com.luoliaoo.wechat.data.TemplateStore.resolve(ConfigRepo.loadLocal(ctx)).let { r ->
                        // item 4: the ACTIVE template decides the endpoint, key,
                        // model, prompt and scope; the shared config fills only the
                        // gaps, so templates never overwrite each other.
                        Log.i(TAG, "analysis settings from template='" + r.templateName +
                            "' model=" + r.model + " baseUrl=" + r.baseUrl +
                            " keyPresent=" + r.apiKey.isNotBlank())
                        ConfigRepo.loadLocal(ctx).copy(
                            baseUrl = r.baseUrl,
                            apiKey = r.apiKey,
                            model = r.model,
                            systemPrompt = r.systemPrompt,
                            defaultUp = r.up,
                            defaultDown = r.down,
                            defaultRangeHours = r.rangeHours,
                        )
                    }
        } catch (t: Throwable) {
            Log.w(TAG, "loadLocal failed: " + t.message)
            null
        }
        if (local != null && local.apiKey.isNotBlank()) return local
        // The mirror is the channel that survives the app/WeChat package and android-user split, so it is
        // consulted before giving up -- an empty config in a clone is a configuration problem, not a bug.
        val mirror = try {
            ConfigRepo.loadMirror(ctx)
        } catch (_: Throwable) {
            null
        }
        if (mirror != null && mirror.apiKey.isNotBlank()) {
            Log.i(TAG, "config: using the MIRROR (user=" + android.os.Process.myUid() / 100000 + ")")
            return mirror
        }
        val remote = ConfigRepo.loadRemote(ctx)
        if (remote != null && remote.apiKey.isNotBlank()) return remote
        return local ?: remote
    }

    private fun extractMessage(view: View): Any? {
        val t = view.tag
        if (looksLikeMsg(t)) return t

        var cur: View? = view.parent as? View
        var depth = 0
        while (cur != null && depth < 12) {
            val tt = cur.tag
            if (looksLikeMsg(tt)) return tt
            cur = cur.parent as? View
            depth++
        }

        val rv = CardRenderer.recyclerViewOf(view)
        if (rv != null) {
            val m = findMsgInViewHolder(rv, view)
            if (m != null) return m
        }
        return null
    }

    private fun findMsgInViewHolder(rv: Any, view: View): Any? {
        val fm = rv.javaClass.methods.firstOrNull {
            it.name == "findContainingViewHolder" && it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return null
        val holder = try {
            fm.invoke(rv, view)
        } catch (_: Throwable) {
            null
        } ?: return null
        return findMsgField(holder)
    }

    private fun findMsgField(owner: Any): Any? {
        var cls: Class<*>? = owner.javaClass
        var depth = 0
        while (cls != null && depth < 6) {
            for (f in cls.declaredFields) {
                if (!looksLikeMsgClass(f.type)) continue
                val o = try {
                    f.isAccessible = true
                    f.get(owner)
                } catch (_: Throwable) {
                    null
                }
                if (looksLikeMsg(o)) return o
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    /**
     * v2.4.1: is this object a message?
     *
     * WEKIT'S RULE, and it is version-proof: resolve the message CLASS once (two strings, no method name) and
     * accept anything assignable to it.
     *
     * The old test required a method literally named `getMsgId`. WeChat keeps the id in the FIELD
     * `field_msgId` and the accessor names are obfuscated per version, so that test failed and the UI reported
     * 未能识别该消息 even though the tag DID hold a message.
     *
     * The name-based test is kept as a LAST resort so nothing that used to work becomes worse.
     */
    private fun looksLikeMsg(o: Any?): Boolean {
        if (o == null) return false

        // 1: the resolved class -- the reliable test.
        val cls = try {
            resolver.msgInfoClass()
        } catch (_: Throwable) {
            null
        }
        if (cls != null && cls.isInstance(o)) return true

        // 2: the old heuristic, still useful when the class could not be resolved.
        return try {
            o.javaClass.methods.any { it.name == "getMsgId" && it.parameterCount == 0 } &&
                access.content(o).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeMsgClass(c: Class<*>): Boolean = try {
        c.methods.any { it.name == "getMsgId" && it.parameterCount == 0 }
    } catch (_: Throwable) {
        false
    }

    private fun findActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun toastNow(text: String) {
        val ctx = com.luoliaoo.wechat.core.ModuleBridge.appContext ?: app
        main.post {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                // cosmetic
            }
        }
    }

    private fun toast(activity: Activity, text: String) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // cosmetic
        }
    }
}
