package com.luoliaoo.wechat.hook

import android.view.View
import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.core.Log

/**
 * Gathers the context to analyse from the long-pressed message.
 *
 * NO compile-time androidx reference: RecyclerView is identified by class NAME
 * and used purely through reflection, because a direct type reference fails to
 * resolve inside WeChat.
 *
 * APPROACH:
 *   View -> walk up parents to the RecyclerView -> take its adapter
 *        -> reflectively find the List field holding message objects
 *        -> filter to text messages, then apply the scope mode
 */
class ChatContext(
    private val access: MsgAccess,
    private val hostLoader: ClassLoader,
    /**
     * Upper bound on messages sent to the model. 0 means UNLIMITED.
     *
     * This used to be 200, which silently truncated every scope to the newest 200 lines -- the reported
     * "为什么只能获取最近的200条信息". It now defaults to unlimited, as requested.
     */
    private val maxLines: Int = 0,
) {

    private companion object {
        const val TAG = "LuoLiao"
        const val RV_CLASS = "androidx.recyclerview.widget.RecyclerView"
    }

    /** @return the RecyclerView instance, typed as Any to avoid a type reference. */
    fun findRecyclerView(v: View?): Any? {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 25) {
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
        while (c != null && depth < 8) {
            if (c.name == RV_CLASS) return true
            c = c.superclass
            depth++
        }
        return false
    }

    /** The adapter's item count, or -1 when it cannot be read. */
    fun countOf(rv: Any): Int {
        val adapter = adapterOf(rv) ?: return -1
        val m = adapter.javaClass.methods.firstOrNull {
            it.name == "getItemCount" && it.parameterCount == 0
        } ?: return -1
        return try {
            (m.invoke(adapter) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * The item the adapter holds at a real RecyclerView POSITION.
     *
     * This is the position-accurate accessor. `adapterMessages()` returns only MESSAGE objects and
     * skips the time separators / system rows WeChat interleaves, so its indices do NOT correspond to
     * adapter positions. Using them for itemViewAt() shifted every card by the number of skipped rows
     * -- the reason cards landed on unrelated rows (a row whose text was a timestamp).
     */
    fun itemAtPosition(rv: Any, position: Int): Any? {
        if (position < 0) return null
        val adapter = adapterOf(rv) ?: return null
        val getItem = adapter.javaClass.methods.firstOrNull {
            it.name == "getItem" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null
        return try {
            getItem.invoke(adapter, position)
        } catch (_: Throwable) {
            null
        }
    }

    /** True when the adapter exposes getItem(int), i.e. position-accurate access is possible. */
    fun supportsPositions(rv: Any): Boolean {
        val adapter = adapterOf(rv) ?: return false
        return adapter.javaClass.methods.any {
            it.name == "getItem" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
    }

    /** Reflectively pulls the message list out of the adapter. */
    fun adapterMessages(rv: Any): List<Any> {
        val adapter = adapterOf(rv) ?: return emptyList()
        return extractList(adapter) ?: extractFromItemCount(adapter) ?: emptyList()
    }

    private fun adapterOf(rv: Any): Any? {
        val m = rv.javaClass.methods.firstOrNull {
            it.name == "getAdapter" && it.parameterCount == 0
        } ?: return null
        return try {
            m.invoke(rv)
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractList(owner: Any): List<Any>? {
        var cls: Class<*>? = owner.javaClass
        var depth = 0
        while (cls != null && depth < 6) {
            for (f in cls.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue
                val v = try {
                    f.isAccessible = true
                    f.get(owner) as? List<*>
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (v.isEmpty()) continue
                val first = v.firstOrNull() ?: continue
                if (looksLikeMessage(first)) {
                    @Suppress("UNCHECKED_CAST")
                    return v as List<Any>
                }
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    private fun extractFromItemCount(adapter: Any): List<Any>? {
        val countMethod = adapter.javaClass.methods.firstOrNull {
            it.name == "getItemCount" && it.parameterCount == 0
        } ?: return null
        val count = try {
            countMethod.invoke(adapter) as? Int
        } catch (_: Throwable) {
            null
        } ?: return null

        val getItem = adapter.javaClass.methods.firstOrNull {
            it.name == "getItem" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.java
        } ?: return null

        if (count <= 0) return null
        // Raised from 500 so paging can reach far back (item 1: unlimited).
        val limit = minOf(count, 20000)
        val out = ArrayList<Any>(limit)
        for (i in 0 until limit) {
            val o = try {
                getItem.invoke(adapter, i)
            } catch (_: Throwable) {
                null
            }
            if (o != null) out += o
        }
        return out.ifEmpty { null }
    }

    private fun looksLikeMessage(o: Any?): Boolean {
        if (o == null) return false
        return o.javaClass.methods.any { it.name == "getMsgId" && it.parameterCount == 0 }
    }

    /** Maps a view back to its adapter position, via reflection. */
    fun positionOf(rv: Any, view: View): Int {
        val m = rv.javaClass.methods.firstOrNull {
            it.name == "findContainingViewHolder" && it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return -1
        val holder = try {
            m.invoke(rv, view)
        } catch (_: Throwable) {
            null
        } ?: return -1

        for (name in listOf("getBindingAdapterPosition", "getAbsoluteAdapterPosition",
                            "getAdapterPosition")) {
            val pm = holder.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: continue
            val p = try {
                pm.invoke(holder) as? Int
            } catch (_: Throwable) {
                null
            }
            if (p != null && p >= 0) return p
        }
        return -1
    }

    /** Notifies the adapter that one row changed, via reflection. */
    fun notifyItemChanged(rv: Any, position: Int) {
        val adapter = adapterOf(rv) ?: return
        val m = adapter.javaClass.methods.firstOrNull {
            it.name == "notifyItemChanged" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return
        try {
            m.invoke(adapter, position)
        } catch (_: Throwable) {
            // adapter may be mid-update; ignore
        }
    }

    // ---------------------------------------------------------- scope logic

    /**
     * v6.0.0: the block words IN FORCE right now: the active template's list, falling back to the
     * built-in markers when it cannot be read.
     *
     * WHY NOT PromptBuilder.SKIP_MARKERS ANY MORE (user-reported bug, twice: 「屏蔽关键词失败了」, then
     * 「msg有用 click与auth没用」): SKIP_MARKERS is a HARDCODED three-entry list -- "msg" / "了解详情" /
     * ":0". Filtering on it alone is why "msg" appeared to work while every word the user added to a
     * template did nothing. The template's list is a SUPERSET of the markers (TemplateStore seeds each
     * template with them), so reading the template keeps the old behaviour and gains the user's words.
     */
    private fun wordsInUse(): List<String> = try {
        // A DIRECT reference, not reflection: `instance` is a public property on the companion, so a
        // rename becomes a compile error instead of a silent fallback to the old, shorter list.
        com.luoliaoo.wechat.hook.AnalysisController.instance
            ?.templateBlockWords()
            ?.takeIf { it.isNotEmpty() }
            ?: com.luoliaoo.wechat.ai.PromptBuilder.SKIP_MARKERS
    } catch (_: Throwable) {
        // Chosen deliberately: this runs on every collect(), so it must never throw, and the built-in
        // markers are a correct (if narrower) answer rather than a silent no-op.
        com.luoliaoo.wechat.ai.PromptBuilder.SKIP_MARKERS
    }

    /**
     * True when this text carries any word in force, built-in markers included.
     *
     * v10.0.0: a VOICE TRANSCRIPT is exempt. The block-word list exists to drop WeChat's raw XML payloads
     * (`<msg>...`, `<appmsg ...>`, and the "了解详情"/":0" tails of link cards) -- machine noise that is
     * never conversational text. A transcript produced by [VoicePrefix] is the opposite: real speech the
     * user asked to have analysed.
     *
     * Without this exemption the user's own sentence could silently vanish: markers are matched with
     * `contains`, so a transcript of "你把 msg 发我一下" contains "msg" and the whole line would be
     * dropped. That is the same class of bug as the raw-XML one -- the filter cannot tell "XML that
     * happens to contain a marker" from "a person who said the word".
     */
    private fun isExcluded(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (isVoiceTranscript(text)) return false
        return com.luoliaoo.wechat.ai.PromptBuilder.isSkipped(text) ||
            com.luoliaoo.wechat.ai.PromptBuilder.isBlockedBy(text, wordsInUse())
    }

    /** True for a line this module wrote itself from a voice transcription. */
    private fun isVoiceTranscript(text: String): Boolean =
        text.startsWith(com.luoliaoo.wechat.voice.VoicePrefix.TEXT)

    /**
     * True for WeChat's raw message payloads (`<msg>`, `<?xml ...`) as stored in field_content.
     *
     * Used ONLY to decide which of two candidates for the same msgId deserves to be shown. Mirrors
     * HistoryStore.looksLikeRawXml, which applies the same rule on the write side.
     */
    private fun isRawXmlPayload(text: String?): Boolean {
        val t = text?.trimStart() ?: return false
        return t.startsWith("<?xml") || t.startsWith("<msg")
    }

    fun collect(
        all: List<Any>,
        target: Any,
        mode: ScopeMode,
        up: Int = 0,
        down: Int = 0,
        startMs: Long = 0,
        endMs: Long = 0,
        /** Messages read in earlier sessions, merged in so the window is not limited to what is
         *  currently attached to the list (item 3). */
        history: List<com.luoliaoo.wechat.data.HistoryStore.Line> = emptyList(),
        /**
         * v10.0.0: turn the shared window into a display window.
         *
         * WHY A FLAG INSTEAD OF CHANGING ONLY_ONE ITSELF: this one function feeds BOTH consumers --
         * the 读取 preview and the prompt sent to the AI. The user asked for the 读取 button to show the
         * local records even in 仅选择信息 mode, but 仅选择信息 must KEEP analysing exactly one message.
         * Without a flag, satisfying the display request would silently widen the analysis scope.
         *
         * When true, ONLY_ONE returns the target PLUS [up]/[down] surrounding messages, which is what a
         * preview should show. The analysis path leaves it false, so its output is unchanged.
         */
        previewWindow: Boolean = false,
    ): List<ChatLine> {
        // The target is always available even when the adapter list is empty.
        if (all.isEmpty() && history.isEmpty()) return listOf(toLine(target))

        // item 2: a message containing the skip marker is EXCLUDED here, at the SOURCE.
        //
        // This is the one place both consumers share:
        //     collect() -> the reader -> the preview rows
        //     collect() -> runAnalysis -> the prompt
        // Filtering only inside PromptBuilder left the message visible in the UI, which is exactly the bug
        // that was reported. Doing it here means the preview, the history, the prompt and a follow-up all
        // see the same list.
        /* v10.1.0：屏蔽词必须筛**展示文本**，不能筛原始 content。
         *
         * 【为什么】引用消息的 content 是整段 XML，含 `<msg>` 和 `<appmsg>` —— 而 "msg" 正是内置
         * 屏蔽词。原来的写法 `isExcluded(access.content(m))` 会让**每一条引用消息**都被屏蔽掉，
         * 用户永远看不到引用的内容（这正是"引用没修好"的形态之一）。
         *
         * 【改成 displayTextOf 后的行为，逐类核对过，没有变松】
         *   * 普通文本   —— 展示文本 == content，行为一模一样；
         *   * 引用消息   —— 展示文本是 `[引用]标题"引用：正文"`，不含 "msg"，因此**不再被误杀**；
         *                   而用户自己配置的屏蔽词仍然生效（它会出现在这段展示文本里）；
         *   * 链接卡片等 —— `WeChatQuote.parse` 返回 null，展示文本就是原始 XML，
         *                   照旧被 "msg" 挡掉 —— **老行为完整保留**。
         * 也就是说：只有"本来就不该被挡"的那一类被放行了。 */
        /* ★ v10.3.0：这里必须**额外**放行图片消息。
         *
         * 微信图片是 type=3，而 `isAnalysableText` 只认 type=1/57 ——
         * 不放行的话图片根本进不了这个列表，于是：
         *   * 「读取」预览里没有任何图片行 → 用户要的那排「识图」开关一个都不出现；
         *   * 分析时也完全没有图片。
         * 现象是"界面空空如也"，与"功能没写"无法区分，所以在这里堵住。
         *
         * 【为什么只在这里变宽，不动 isAnalysableText】那个函数还管**写入本地记录**，
         * 放宽它会让历史库塞满没有信息量的 `[非文本消息]`。收进本次上下文
         * 与永久存下来是两件事，不能共用一个判据 —— 详见 MsgAccess.isImageMessage。 */
        val textMsgs = all.filter { m ->
            (access.isAnalysableText(m) || access.isImageMessage(m)) &&
                displayTextOf(m).isNotBlank() &&
                !isExcluded(displayTextOf(m))
        }
        val skippedLive = all.count { m ->
            access.isAnalysableText(m) && isExcluded(displayTextOf(m))
        }
        if (skippedLive > 0) {
            // item 2: name the words actually in use, so a user can tell which rule excluded what.
            Log.i(
                TAG,
                "collect: skipped " + skippedLive + " live message(s) via " +
                    wordsInUse().joinToString("/"),
            )
        }

        // ---- item 3: merge the recorded history with the live messages -------------------
        // Keyed by msgId so a message present in both appears once; ordered by time so the scope
        // (by count / by time range) behaves the same as before.
        val merged = LinkedHashMap<Long, ChatLine>()
        for (m in textMsgs) {
            val id = access.msgId(m)
            // v10.1.0: 合成键也必须用 displayTextOf，与"写入时使用的文本"一致。
            // 否则同一条引用在 live（XML）与 history（[引用]…）两侧会算出**两个不同的合成键**，
            // 于是一条消息显示两遍 —— 引用消息的 msgId 一般非 0、走不到这里，但规则要保持一致。
            val key = if (id != 0L) id else (access.createTime(m) * 31 + displayTextOf(m).hashCode())
            merged[key] = toLine(m)
        }
        var skippedHistory = 0
        for (l in history) {
            if (l.text.isBlank()) continue
            // item 2: a `<msg>` message recorded in an earlier session is excluded as well, otherwise it
            // would reappear in the preview from the history store.
            if (isExcluded(l.text)) {
                skippedHistory++
                continue
            }
            val key = if (l.msgId != 0L) l.msgId else (l.time * 31 + l.text.hashCode())
            if (merged.containsKey(key)) {
                // ★ v10.0.0: 这里原本无条件 `continue`，是「转写读不到」的最后一环 ——
                //   同一个 msgId 上，live 侧可能还挂着一行**原始 XML**（语音/图片/appmsg 都会），
                //   而 history 侧是**转写成品**。无条件跳过就等于让 XML 赢，转写永远显示不出来。
                //
                //   规则与 HistoryStore.isUpgrade 一致且同样是单向的：**只有**当已存在的是原始
                //   XML 载荷、而新来的是真正文时，才改用真正文；反向绝不允许（否则微信重绑一次
                //   原始消息就会把转写覆盖掉，等于白转）。
                val existing = merged[key]
                if (existing != null && isRawXmlPayload(existing.text) && !isRawXmlPayload(l.text)) {
                    merged[key] = ChatLine(
                        time = l.time,
                        isSend = l.isSend,
                        text = l.text,
                        speaker = if (speakerCount in 3..Int.MAX_VALUE) l.speaker else null,
                    )
                }
                continue
            }
            // item 1: history lines carry a raw talker id; normalise it with the same rule so a
            // one-to-one chat never displays a wxid.
            merged[key] = ChatLine(
                time = l.time,
                isSend = l.isSend,
                text = l.text,
                speaker = if (speakerCount in 3..Int.MAX_VALUE) l.speaker else null,
            )
        }
        if (merged.isNotEmpty()) {
            Log.i(
                TAG,
                "collect: live=" + textMsgs.size + " history=" + history.size +
                    " merged=" + merged.size + " skippedMarker=" + (skippedLive + skippedHistory),
            )
        }

        // The scope now works on ChatLine directly, so both sources are treated identically.
        val ordered = merged.values.sortedBy { it.time }
        val targetLine = toLine(target)

        val lines = when (mode) {
            /* v10.0.0: 仅选择信息。
             *
             * 分析路径（previewWindow=false）保持原样：只送 target 一条 —— 这是「仅选择信息」的定义，
             * 不能因为"读取按钮要能看记录"就被悄悄改宽。
             *
             * 预览路径（previewWindow=true）额外带上 up/down 条上下文，这样用户点「读取」能真的
             * 看到这个会话前后的本地记录，而不是被一句"该模式不需要读取"挡回去。
             * up/down 用的是对话框里「向上/向下」输入框的值，用户本来就能控制窗口大小。 */
            ScopeMode.ONLY_ONE -> {
                if (!previewWindow) {
                    listOf(targetLine)
                } else {
                    var idx = ordered.indexOfFirst {
                        it.time == targetLine.time && it.text == targetLine.text
                    }
                    if (idx < 0) {
                        idx = ordered.indices.minByOrNull {
                            kotlin.math.abs(ordered[it].time - targetLine.time)
                        } ?: -1
                    }
                    if (idx < 0) {
                        listOf(targetLine)
                    } else {
                        val from = (idx - up).coerceAtLeast(0)
                        val to = (idx + down + 1).coerceAtMost(ordered.size)
                        ArrayList(ordered.subList(from, to))
                    }
                }
            }

            ScopeMode.COUNT -> {
                var idx = ordered.indexOfFirst { it.time == targetLine.time && it.text == targetLine.text }
                if (idx < 0) {
                    // Closest by time, so a target whose exact stamp is missing still works.
                    idx = ordered.indices.minByOrNull {
                        kotlin.math.abs(ordered[it].time - targetLine.time)
                    } ?: -1
                }
                if (idx < 0) {
                    listOf(targetLine)
                } else {
                    val from = (idx - up).coerceAtLeast(0)
                    val to = (idx + down + 1).coerceAtMost(ordered.size)
                    ArrayList(ordered.subList(from, to))
                }
            }

            ScopeMode.TIME_RANGE -> {
                val s = if (startMs > 0) startMs else 0L
                val e = if (endMs > 0) endMs else Long.MAX_VALUE
                val inRange = ordered.filter { it.time in s..e }
                inRange.ifEmpty { listOf(targetLine) }
            }
        }

        val result = ArrayList<ChatLine>(lines.size + 1)
        result.addAll(lines)
        if (result.none { it.time == targetLine.time && it.text == targetLine.text }) {
            result.add(targetLine)
        }
        result.sortBy { it.time }

        // maxLines == 0 means unlimited: hand back everything collected.
        return if (maxLines > 0 && result.size > maxLines) {
            Log.i(TAG, "collect: truncating " + result.size + " -> " + maxLines + " (maxLines set)")
            ArrayList(result.subList(result.size - maxLines, result.size))
        } else {
            result
        }
    }

    /** Old implementation kept below for reference; the merged version above supersedes it. */
    private fun collectLegacy(
        all: List<Any>,
        target: Any,
        mode: ScopeMode,
        up: Int,
        down: Int,
        startMs: Long,
        endMs: Long,
    ): List<ChatLine> {
        // v10.1.0: isAnalysableText，让引用消息也能进这份上下文（否则只有纯文本才被看到）。
        // 同理用 displayTextOf：原始 content 是 XML、会被屏蔽词误杀，见 collect() 里的长注释。
        val textMsgs = all.filter { m ->
            access.isAnalysableText(m) && displayTextOf(m).isNotBlank()
        }
        if (textMsgs.isEmpty()) return emptyList()

        val lines = when (mode) {
            // ONLY_ONE analyses just this message, but the caller wants the surrounding
            // lines as background, so they are supplied separately by the controller.
            ScopeMode.ONLY_ONE -> listOf(target)

            ScopeMode.COUNT -> {
                var idx = -1
                for (i in textMsgs.indices) {
                    if (textMsgs[i] === target) {
                        idx = i
                        break
                    }
                }
                if (idx < 0) {
                    for (i in textMsgs.indices) {
                        if (sameMsg(textMsgs[i], target)) {
                            idx = i
                            break
                        }
                    }
                }
                if (idx < 0) {
                    listOf(target)
                } else {
                    val from = (idx - up).coerceAtLeast(0)
                    val to = (idx + down + 1).coerceAtMost(textMsgs.size)
                    textMsgs.subList(from, to)
                }
            }

            ScopeMode.TIME_RANGE -> {
                val s = if (startMs > 0) startMs else 0L
                val e = if (endMs > 0) endMs else Long.MAX_VALUE
                val inRange = textMsgs.filter { access.createTime(it) in s..e }
                inRange.ifEmpty { listOf(target) }
            }
        }

        val result = ArrayList<ChatLine>(lines.size + 1)
        for (m in lines) result += toLine(m)

        val tTime = access.createTime(target)
        // v10.0.0: MUST be displayTextOf, not access.content -- the lines above were built with
        // displayTextOf, so comparing against raw content() would compare a transcript against the
        // raw voice XML, never match, and append the target a SECOND time as a duplicate row.
        val tText = displayTextOf(target)
        var hasTarget = false
        for (l in result) {
            if (l.time == tTime && l.text == tText) {
                hasTarget = true
                break
            }
        }
        if (!hasTarget) result += toLine(target)

        // maxLines == 0 means unlimited: hand back everything collected.
        return if (maxLines > 0 && result.size > maxLines) {
            Log.i(TAG, "collect: truncating " + result.size + " -> " + maxLines + " (maxLines set)")
            ArrayList(result.subList(result.size - maxLines, result.size))
        } else {
            result
        }
    }

    private fun sameMsg(a: Any, b: Any): Boolean {
        val idA = access.msgId(a)
        if (idA != 0L && idA == access.msgId(b)) return true
        val svrA = access.msgSvrId(a)
        return svrA != 0L && svrA == access.msgSvrId(b)
    }

    /**
     * Converts a message into a ChatLine.
     *
     * BOTH directions are kept: my own messages are labelled 我 and the other side's 对方
     * (item 3). Filtering by isSend here would have removed my half of the conversation.
     */
    /**
     * Converts a message into a ChatLine.
     *
     * ITEM 2: in a one-to-one chat the other side must read as 对方, not as its wxid. The speaker is
     * therefore normalised against the number of distinct talkers in this conversation: with exactly
     * two participants every non-mine line is 对方, and only a group keeps real ids.
     *
     * v10.0.0: the text comes from AnalysisController.displayText, NOT from access.content directly.
     * A WeChat VOICE message (type=34) has a NON-empty field_content -- a whole <voicemsg aeskey=...
     * voiceurl=...> XML -- so reading content() here sent that XML into the AI prompt for a voice
     * target. displayText returns the transcript (or a [语音] placeholder) instead. It is the SAME
     * function the reader preview and the long-press target use, deliberately: four sites had this bug
     * one after another, so the rule now lives in one place.
     */
    private fun toLine(m: Any): ChatLine = ChatLine(
        time = access.createTime(m),
        isSend = access.isSend(m),
        text = displayTextOf(m),
        speaker = normalisedSpeaker(m),
        /* ★ v10.3.0：标记这条是不是图片。
         *
         * 【为什么要在这里算，而不是等界面自己去判】
         * 判据是消息的**原始 XML 类型**（`<msg><img .../></msg>`），而那个原始值只在
         * 这一层还拿得到 —— 到了 `ChatLine` 里 text 已经被翻译成统一的 `[非文本消息]`，
         * 图片和语音再也分不出来。所以必须在**翻译发生之前**把结论记下来。
         *
         * 【为什么用 access.content 而不是 displayTextOf 的结果】
         * displayTextOf 返回的是翻译后的文本（语音还会返回转写文字），
         * 而类型判断要的是**原始载荷**。两者是不同的东西，不能混用 ——
         * 用错了的表现是"所有语音都被当成图片"（因为语音的 content 也是 XML）。
         */
        isImage = try {
            WeChatEnvelope.isImage(access.content(m))
        } catch (_: Throwable) {
            false
        },
    )

    /** displayText when the controller is up; a plain content() read is only a last resort. */
    private fun displayTextOf(m: Any): String = try {
        com.luoliaoo.wechat.hook.AnalysisController.instance?.displayText(m) ?: access.content(m)
    } catch (_: Throwable) {
        ""
    }

    /** Number of distinct speakers seen in the current conversation. */
    @Volatile
    private var speakerCount: Int = 0

    /**
     * The label a message should be shown under (item 1).
     *
     * ONE rule for every display site: my messages are 我; in a one-to-one conversation the other
     * side is 对方; only a group (three or more participants) shows real ids. The reader previously
     * printed a raw wxid because it trusted a stored speaker id.
     */
    fun labelFor(isSend: Boolean, speaker: String?): String = when {
        isSend -> "\u6211"
        speakerCount in 3..Int.MAX_VALUE -> speaker?.takeIf { it.isNotBlank() } ?: "\u5bf9\u65b9"
        else -> "\u5bf9\u65b9"
    }

    /** Lets the caller tell the context how many participants the conversation has. */
    fun setSpeakerCount(n: Int) {
        speakerCount = n
    }

    /**
     * The label to store for a message's sender.
     *
     * Returns null when the two-person form should be used (the renderer then falls back to
     * 我 / 对方). A group keeps the real talker id so individual members stay distinguishable.
     */
    private fun normalisedSpeaker(m: Any): String? {
        if (access.isSend(m)) return null
        // item 2: exactly two participants -> the other side is simply 对方.
        if (speakerCount in 1..2) return null
        val t = access.talker(m)
        return t.ifBlank { null }
    }

    fun fingerprint(lines: List<ChatLine>): String {
        val sb = StringBuilder()
        for (l in lines) {
            sb.append(l.time).append(':').append(l.isSend).append(':')
                .append(l.text.hashCode()).append(';')
        }
        return Integer.toHexString(sb.toString().hashCode())
    }
}
