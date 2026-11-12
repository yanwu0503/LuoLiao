package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.PromptBuilder
import com.luoliaoo.wechat.ai.ScopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt assembly.
 *
 * v1.0.26 changed the contract on purpose (item 4): 按条数 / 按时间区间 now ask the model to
 * analyse the WHOLE segment and summarise it, instead of singling out the pressed message.
 * ONLY_ONE keeps the old single-message behaviour.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class PromptBuilderTest {

    private val base = 1_700_000_000_000L

    private fun lines() = listOf(
        ChatLine(base, false, "你今天是不是又为了我跟你说过什么？"),
        ChatLine(base + 1000, true, "我没有啊"),
        ChatLine(base + 2000, false, "你每次都这样"),
    )

    @Test
    fun includesAllLinesInOrder() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        val i1 = p.indexOf("你今天是不是")
        val i2 = p.indexOf("我没有啊")
        val i3 = p.indexOf("你每次都这样")
        assertTrue(i1 in 0 until i2)
        assertTrue(i2 < i3)
    }

    // ---------------------------------------------------- item 4: whole-segment modes

    @Test
    fun countModeAnalysesTheWholeSegment() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue(
            "COUNT must ask for a segment analysis",
            p.contains("整段对话"),
        )
        assertTrue(
            "COUNT must warn against analysing only the pressed message",
            p.contains("不要只围绕它作答"),
        )
    }

    @Test
    fun timeRangeModeAnalysesTheWholeSegment() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.TIME_RANGE)
        assertTrue(p.contains("整段对话"))
        assertTrue(p.contains("不要只围绕它作答"))
    }

    @Test
    fun segmentModesAskForSummarising() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue("must ask to summarise the situation", p.contains("总结"))
        assertTrue("must ask about the other side's overall intent",
            p.contains("整体"))
    }

    @Test
    fun onlyOneModeStillAnalysesASingleMessage() {
        val p = PromptBuilder.build(listOf(lines()[0]), lines()[0], ScopeMode.ONLY_ONE)
        assertTrue("ONLY_ONE must scope to one message", p.contains("只有下面这一条消息"))
        assertFalse("ONLY_ONE must not ask for a segment analysis", p.contains("整段对话"))
    }

    // ---------------------------------------------------- item 3: both speakers

    @Test
    fun labelsBothSpeakers() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue("my own lines must be present", p.contains("我："))
        assertTrue("the other side's lines must be present", p.contains("对方："))
    }

    @Test
    fun explainsWhoIsWho() {
        // item 5: the prompt now names the speakers and their count, and supplies my own wxid.
        val p = PromptBuilder.build(
            lines(), lines()[0], ScopeMode.COUNT, myWxid = "wxid_me", focusSpeaker = null,
        )
        assertTrue("must state the wxid header", p.contains("我的微信号是"))
        assertTrue("must state my wxid", p.contains("wxid_me"))
        assertTrue("must state how many speakers there are", p.contains("发言者共有"))
    }

    @Test
    fun transcriptUsesWxidTimestampAndContent() {
        // item 5 + item 7: "wxid：timestamp：content" using the message's own timestamp.
        val withIds = listOf(
            ChatLine(base, false, "hello", speaker = "wxid_other"),
            ChatLine(base + 5000, true, "hi", speaker = "wxid_me"),
        )
        val p = PromptBuilder.build(withIds, withIds[0], ScopeMode.COUNT, myWxid = "wxid_me")
        assertTrue("other side shown by wxid", p.contains("wxid_other"))
        assertTrue("my own line shown as 我", p.contains("我："))
        // the timestamp of each message, not of the long press
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(base + 5000))
        assertTrue("must use the message's own timestamp ($stamp)", p.contains(stamp))
    }

    @Test
    fun groupChatAsksToUseRealIds() {
        // item 5: with more than two speakers the model must not say 对方.
        val group = listOf(
            ChatLine(base, false, "a", speaker = "wxid_a"),
            ChatLine(base + 1, false, "b", speaker = "wxid_b"),
            ChatLine(base + 2, true, "c", speaker = "wxid_me"),
        )
        val p = PromptBuilder.build(group, group[0], ScopeMode.COUNT, myWxid = "wxid_me")
        assertTrue("must recognise a group chat", p.contains("群聊"))
        assertTrue("must forbid a generic 对方", p.contains("不要笼统地说"))
    }

    @Test
    fun focusSpeakerIsHonoured() {
        val p = PromptBuilder.build(
            lines(), lines()[0], ScopeMode.COUNT, myWxid = "", focusSpeaker = "wxid_target",
        )
        assertTrue("must name the focus person", p.contains("wxid_target"))
        assertTrue("must scope to that person", p.contains("只围绕这个人"))
    }

    @Test
    fun segmentModeUsesTimestampsForMood() {
        // item 6: timestamps drive attitude/mood judgement, but only for segment scopes.
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue("segment mode must use timestamps", p.contains("结合每行的时间戳"))

        val single = PromptBuilder.build(listOf(lines()[0]), lines()[0], ScopeMode.ONLY_ONE)
        assertTrue("single mode limits timestamps to time-of-day",
            single.contains("清晨"))
    }

    @Test
    fun bothDirectionsSurvive() {
        // A conversation with only my messages must still render (no isSend filtering).
        val onlyMine = listOf(
            ChatLine(base, true, "在吗"),
            ChatLine(base + 1, true, "睡了吗"),
        )
        val p = PromptBuilder.build(onlyMine, onlyMine[0], ScopeMode.COUNT)
        assertTrue(p.contains("在吗"))
        assertTrue(p.contains("睡了吗"))
        assertTrue(p.contains("我："))
    }

    // ---------------------------------------------------- item 2: reply instruction

    @Test
    fun asksForASuggestedReply() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue("must request a reply per intent", p.contains("reply"))
        // the new wording asks for a sentence I can send right now
        assertTrue("reply must be ready to send", p.contains("我现在就能发出去的话"))
    }

    @Test
    fun demandsJsonOutput() {
        val p = PromptBuilder.build(lines(), lines()[0], ScopeMode.COUNT)
        assertTrue(p.contains("JSON"))
    }

    // ---------------------------------------------------- follow-up context (item 9)

    @Test
    fun followUpContextCarriesTheConversation() {
        val c = PromptBuilder.followUpContext(lines(), lines()[0], "生气等级 6/10")
        assertTrue(c.contains("你今天是不是"))
        assertTrue(c.contains("生气等级 6/10"))
        assertTrue("follow-up must not ask for JSON", c.contains("不要输出 JSON"))
    }

    @Test
    fun followUpContextWorksWithoutSummary() {
        val c = PromptBuilder.followUpContext(lines(), lines()[0], "")
        assertTrue(c.contains("聊天记录"))
    }

    // ---------------------------------------------------- misc contract

    @Test
    fun timeFormatMatchesRequirement() {
        val s = PromptBuilder.formatTime(base)
        val re = Regex("\\d{2}月\\d{2}日\\d{2}时\\d{2}分\\d{2}秒")
        assertTrue("actual: $s", re.matches(s))
    }

    @Test
    fun defaultRangeIsOneDayBack() {
        val start = PromptBuilder.defaultRangeStart(24)
        val end = PromptBuilder.defaultRangeEnd()
        val diffHours = (end - start) / 3600_000.0
        assertTrue("diff: $diffHours", diffHours in 23.9..24.1)
    }

    @Test
    fun handlesEmptyLineList() {
        val p = PromptBuilder.build(emptyList(), lines()[0], ScopeMode.ONLY_ONE)
        assertTrue(p.contains("你今天是不是"))
    }

    // ---------------------------------------------------- skip markers (item 2)

    /**
     * The markers exclude a message from everything. Three markers are active; a message is excluded when it
     * CONTAINS any of them, which is a plain substring rule, so position does not matter.
     */
    @Test
    fun skipsEveryMarker() {
        // the original three letters, adjacent
        assertTrue(PromptBuilder.isSkipped("\u6211\u7231msg\u4f60"))
        // the newly added phrase
        assertTrue(PromptBuilder.isSkipped("\u8bf7\u4e86\u89e3\u8be6\u60c5"))
        assertTrue(PromptBuilder.isSkipped("\u4e86\u89e3\u8be6\u60c5"))
        // the newly added characters
        assertTrue(PromptBuilder.isSkipped("\u8868\u60c5:0"))
        assertTrue(PromptBuilder.isSkipped(":0"))
    }

    @Test
    fun keepsMessagesWithoutAMarker() {
        // the letters are not adjacent, so this must survive
        assertFalse(PromptBuilder.isSkipped("\u6211m\u7231s\u4f60g"))
        assertFalse(PromptBuilder.isSkipped("\u4f60\u597d\u554a"))
        assertFalse(PromptBuilder.isSkipped(""))
        assertFalse(PromptBuilder.isSkipped(null))
        // a lone colon or zero is not the marker
        assertFalse(PromptBuilder.isSkipped("\u8868\u60c5:"))
        assertFalse(PromptBuilder.isSkipped("0"))
    }

    @Test
    fun markerListIsExposedAndOrdered() {
        // the original marker stays first so anything reading SKIP_MARKER keeps its meaning
        assertTrue(PromptBuilder.SKIP_MARKERS.first() == PromptBuilder.SKIP_MARKER)
        assertTrue(PromptBuilder.SKIP_MARKERS.contains("msg"))
        assertTrue(PromptBuilder.SKIP_MARKERS.contains("\u4e86\u89e3\u8be6\u60c5"))
        assertTrue(PromptBuilder.SKIP_MARKERS.contains(":0"))
        // v8.1.0: the five non-text placeholders are block markers too. They are the strings the module
        // itself substitutes for an image / sticker / file / gray tip / recall, and blocking them is what
        // stops a sticker-heavy chat from analysing as "[表情]" repeated. Pinned EXACTLY, in order, so
        // adding or removing one is a visible edit here rather than a silent behaviour change.
        assertEquals(
            listOf("msg", "\u4e86\u89e3\u8be6\u60c5", ":0", "[\u56fe\u7247]", "[\u8868\u60c5]",
                   "[\u6587\u4ef6]", "[\u7cfb\u7edf\u63d0\u793a]", "[\u5df2\u64a4\u56de]"),
            PromptBuilder.SKIP_MARKERS,
        )
    }

    /**
     * v8.1.0: each placeholder marker actually blocks the string it is meant to block.
     *
     * The markers carry their brackets on purpose (see `SKIP_MARKER_PIC`), and that is what stops
     * "[\u56fe\u7247]" from matching an ordinary sentence that merely mentions \u56fe\u7247. Both halves
     * are asserted here: the placeholder is blocked, and the bare word is NOT.
     */
    @Test
    fun placeholderMarkersBlockThePlaceholderAndNotTheBareWord() {
        for (m in listOf("[\u56fe\u7247]", "[\u8868\u60c5]", "[\u6587\u4ef6]",
                         "[\u7cfb\u7edf\u63d0\u793a]", "[\u5df2\u64a4\u56de]")) {
            assertTrue("v8.1.0: '$m' must be skipped", PromptBuilder.isSkipped(m))
        }
        // The bare words are ordinary conversation and must survive.
        assertFalse(PromptBuilder.isSkipped("\u6211\u53d1\u4e86\u4e00\u5f20\u56fe\u7247\u7ed9\u4f60"))
        assertFalse(PromptBuilder.isSkipped("\u8fd9\u4e2a\u8868\u60c5\u5f88\u597d\u7b11"))
        assertFalse(PromptBuilder.isSkipped("\u6587\u4ef6\u5728\u54ea\u91cc"))
    }

    @Test
    fun skipMarkerInReportsWhichRuleFired() {
        assertTrue(PromptBuilder.skipMarkerIn("\u6211\u7231msg\u4f60") == "msg")
        assertTrue(PromptBuilder.skipMarkerIn("\u8bf7\u4e86\u89e3\u8be6\u60c5") == "\u4e86\u89e3\u8be6\u60c5")
        assertTrue(PromptBuilder.skipMarkerIn("\u8868\u60c5:0") == ":0")
        assertTrue(PromptBuilder.skipMarkerIn("\u4f60\u597d") == null)
        assertTrue(PromptBuilder.skipMarkerIn(null) == null)
    }

    @Test
    fun buildExcludesAMarkedLineFromThePrompt() {
        val marked = listOf(
            ChatLine(base, false, "\u4f60\u597d\u554a"),
            ChatLine(base + 1000, false, "\u8fd9\u662fmsg\u6d4b\u8bd5"),
            ChatLine(base + 2000, false, "\u8bf7\u4e86\u89e3\u8be6\u60c5"),
            ChatLine(base + 3000, true, "\u6211\u5728"),
        )
        val p = PromptBuilder.build(marked, marked[0], ScopeMode.ONLY_ONE)
        assertTrue("the plain line must be present", p.contains("\u4f60\u597d\u554a"))
        assertTrue("the reply must be present", p.contains("\u6211\u5728"))
        assertFalse("a msg line must be gone", p.contains("\u8fd9\u662fmsg\u6d4b\u8bd5"))
        assertFalse("a \u4e86\u89e3\u8be6\u60c5 line must be gone", p.contains("\u8bf7\u4e86\u89e3\u8be6\u60c5"))
    }
}
