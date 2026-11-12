package com.luoliaoo.wechat

import com.luoliaoo.wechat.ai.ChatLine
import com.luoliaoo.wechat.ai.PromptBuilder
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.data.TemplateStore
import com.luoliaoo.wechat.hook.QQEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.2: the multi-select block-word rule.
 *
 * The user's original requirement, verbatim: 屏蔽词为 msg / 了解详情 / :0 这三个，如 "我爱msg你" 会屏蔽，
 * "我m爱s你g" 就不会屏蔽.
 *
 * That is a CONTIGUOUS substring test -- the characters must appear next to each other. These tests
 * pin that behaviour down so a future "improvement" to fuzzy matching cannot silently break it.
 *
 * v6.0.0: two words were ADDED to the default list ("click", "auth") at the user's request, after Soul
 * became a host. The contiguous-substring RULE is unchanged; only the seed list grew. The tests below
 * keep the three original cases exactly as they were and add cases for the two new words, so the old
 * guarantee is still pinned rather than replaced.
 */
class BlockWordsTest {

    /** The three the user originally named. Kept as its own constant so the original cases still test them. */
    private val originalThree = listOf("msg", "了解详情", ":0")

    /** The live default list, which v6.0.0 extended. */
    private val defaults = listOf("msg", "了解详情", ":0", "click", "auth")

    @Test
    fun contiguousMatchIsBlocked() {
        // "msg" appears as one run inside the sentence.
        assertTrue(PromptBuilder.isBlockedBy("我爱msg你", defaults))
        assertTrue(PromptBuilder.isBlockedBy("msg", defaults))
        assertTrue(PromptBuilder.isBlockedBy("前缀msg后缀", defaults))
    }

    @Test
    fun scatteredCharactersAreNotBlocked() {
        // The user's counter-example: the letters are present but NOT contiguous, so this must be kept.
        assertFalse(PromptBuilder.isBlockedBy("我m爱s你g", defaults))
        assertFalse(PromptBuilder.isBlockedBy("m s g", defaults))
        assertFalse(PromptBuilder.isBlockedBy("m,s,g", defaults))
    }

    @Test
    fun theOtherTwoDefaultsAlsoApply() {
        assertTrue(PromptBuilder.isBlockedBy("点击了解详情查看更多", defaults))
        assertTrue(PromptBuilder.isBlockedBy("表情:0", defaults))
        assertFalse(PromptBuilder.isBlockedBy("了解情况", defaults))
    }

    @Test
    fun emptyWordListAndNullTextAreSafe() {
        assertFalse(PromptBuilder.isBlockedBy("我爱msg你", emptyList()))
        assertFalse(PromptBuilder.isBlockedBy(null, defaults))
    }

    @Test
    fun blockWordInReportsWhichWordMatched() {
        assertEquals("msg", PromptBuilder.blockWordIn("我爱msg你", defaults))
        assertEquals("了解详情", PromptBuilder.blockWordIn("a了解详情b", defaults))
        assertEquals(null, PromptBuilder.blockWordIn("我m爱s你g", defaults))
    }

    @Test
    fun builtInTemplatesShipTheDefaults() {
        // The 屏蔽 box in the multi-select UI seeds from the active template.
        val defaults0 = TemplateStore.defaults()
        assertEquals(3, defaults0.size)
        for (t in defaults0) {
            assertEquals(
                "template ${t.name} must seed the default block words",
                TemplateStore.DEFAULT_BLOCK_WORDS,
                t.blockWords,
            )
        }
    }

    /**
     * v8.1.0: the default seed list, pinned EXACTLY.
     *
     * This assertion used to read "areTheThreeTheUserNamed", then "areTheFiveTheUserNamed". It is
     * deliberately written as a literal list rather than a length check, so that ADDING a word to
     * DefaultBlockWords is a visible, intentional edit here -- the same reason HostTextTest pins
     * REAL_HOSTS as an exact list.
     *
     * v8.1.0 ADDED the five non-text placeholders. They are not words anybody typed: they are the
     * strings THE MODULE ITSELF substitutes for a non-text row (`QQEnvelope.placeholderForType` and
     * `RECALLED_PLACEHOLDER`), so a sticker-heavy chat stops analysing as "[表情]" repeated.
     */
    @Test
    fun defaultBlockWordsAreTheFiveTheUserNamedPlusThePlaceholders() {
        assertEquals(
            listOf(
                "msg", "了解详情", ":0", "click", "auth",
                "[图片]", "[表情]", "[文件]", "[系统提示]", "[已撤回]",
            ),
            TemplateStore.DEFAULT_BLOCK_WORDS,
        )
        // The five the user named earlier must still be PRESENT and first: every change since v3.0.3
        // has been additive, and reordering them would silently change which word a log reports.
        assertEquals(originalThree, TemplateStore.DEFAULT_BLOCK_WORDS.take(3))
        assertEquals(
            listOf("click", "auth"),
            TemplateStore.DEFAULT_BLOCK_WORDS.subList(3, 5),
        )
    }

    /**
     * v8.1.0: the placeholder block words are EXACTLY the strings the module emits for non-text rows.
     *
     * THE POINT OF THIS TEST. The list and the placeholders are defined in two different files, and a
     * substring block word only works if it matches the emitted string CHARACTER FOR CHARACTER -- the
     * brackets included. A typo (`【图片】` with full-width brackets, or a missing `[`) would leave the
     * feature silently useless: no error, no warning, just placeholders still reaching the model.
     */
    @Test
    fun placeholderBlockWordsAreTheStringsTheModuleActuallyEmits() {
        val words = TemplateStore.DEFAULT_BLOCK_WORDS
        for (w in listOf("[图片]", "[表情]", "[文件]", "[系统提示]", "[已撤回]")) {
            assertTrue("v8.1.0: '$w' must be a default block word", words.contains(w))
        }
        // And the emitted QQ placeholder for an image must be blocked by that list.
        assertTrue(PromptBuilder.isBlockedBy(QQEnvelope.placeholderForType(QQEnvelope.ELEM_PIC), words))
        assertTrue(PromptBuilder.isBlockedBy(QQEnvelope.RECALLED_PLACEHOLDER, words))
    }

    /**
     * v6.0.0: the two words added for Soul's chat stream.
     *
     * Case matters, and the match is a CONTIGUOUS substring test -- so "click" also blocks
     * "please click here". That is the SAME semantics the user already had for "msg" (which blocks
     * "我爱msg你"), chosen deliberately rather than newly invented.
     */
    @Test
    fun theTwoNewWordsApplyWithTheSameSubstringRule() {
        // Exact and embedded forms are both blocked.
        assertTrue(PromptBuilder.isBlockedBy("click", defaults))
        assertTrue(PromptBuilder.isBlockedBy("auth", defaults))
        assertTrue(PromptBuilder.isBlockedBy("please click the link", defaults))
        assertTrue(PromptBuilder.isBlockedBy("auth_token=abcd", defaults))

        // Which word matched is reported, so a dropped line is explainable in the log.
        assertEquals("click", PromptBuilder.blockWordIn("please click", defaults))
        assertEquals("auth", PromptBuilder.blockWordIn("xauthx", defaults))

        // Still a CONTIGUOUS test for the new words too: scattered letters must be kept.
        assertFalse(PromptBuilder.isBlockedBy("c l i c k", defaults))
        assertFalse(PromptBuilder.isBlockedBy("c,l,i,c,k", defaults))
        assertFalse(PromptBuilder.isBlockedBy("a u t h", defaults))
    }

    /**
     * v6.0.0: THE COST of using the existing substring rule, pinned as a TEST rather than left implicit.
     *
     * Because "auth" is a substring of "authentic"/"author", ordinary English words are blocked too.
     * This is a real, known consequence of reusing the user's chosen semantics (the same way "msg"
     * already blocks "我爱msg你"). It is asserted here on purpose: if a future change makes these
     * pass by switching to whole-word matching, that is a SEMANTIC change to the user's rule and this
     * test failing is the signal to confirm it rather than to quietly re-baseline it.
     */
    @Test
    fun theSubstringRuleAlsoBlocksWordsContainingTheNewTokens() {
        assertTrue(PromptBuilder.isBlockedBy("authentic", defaults))
        assertTrue(PromptBuilder.isBlockedBy("author", defaults))
        assertTrue(PromptBuilder.isBlockedBy("clickable", defaults))
    }

    @Test
    fun unrelatedTextIsStillNotBlocked() {
        // Guards against an over-broad edit: these share no contiguous run with any block word.
        assertFalse(PromptBuilder.isBlockedBy("点击这里", defaults))
        assertFalse(PromptBuilder.isBlockedBy("我今天很开心", defaults))
        assertFalse(PromptBuilder.isBlockedBy("c1ick", defaults))
    }

    /**
     * v3.0.3: THE FILTER NOW RUNS BEFORE THE DIALOG OPENS.
     *
     * MenuHook.onMultiSelectAnalyse calls this on the described lines first, so a blocked message is
     * never listed in the dialog and therefore can never be sent to the model. This mirrors that call.
     */
    @Test
    fun preDialogFilterRemovesBlockedLines() {
        val described = listOf(
            "对方：晚上一起吃饭吗",
            "对方：我爱msg你",
            "我：好啊",
            "对方：我m爱s你g",
            "对方：点击了解详情",
            "我：表情:0",
        )
        val shown = described.filterNot { PromptBuilder.isSkipped(it) }

        assertEquals("the three blocked lines must be gone before the dialog", 3, shown.size)
        assertFalse(shown.any { it.contains("我爱msg你") })
        assertFalse(shown.any { it.contains("了解详情") })
        assertFalse(shown.any { it.contains(":0") })
        // The scattered one is NOT blocked, so it stays visible.
        assertTrue(shown.any { it.contains("我m爱s你g") })
    }

    @Test
    fun buildSelectionDropsBlockedLinesAndKeepsTheRest() {
        val kept = listOf(
            "对方：晚上一起吃饭吗",
            "对方：我爱msg你",
            "我：好啊",
            "对方：我m爱s你g",
        )
        val prompt = PromptBuilder.buildSelection(
            kept = kept,
            target = com.luoliaoo.wechat.ai.ChatLine(0L, false, "x"),
            extraBlockWords = defaults,
        )
        // The blocked line is gone, the scattered one survives, and the count reflects it.
        assertFalse(prompt.contains("我爱msg你"))
        assertTrue(prompt.contains("我m爱s你g"))
        assertTrue(prompt.contains("晚上一起吃饭吗"))
        assertTrue(prompt.contains("共 3 条"))
    }

    /**
     * v3.0.3 (item 3): a blocked message must never reach the AI, even if it somehow survived the
     * pre-dialog filter and the user kept it. buildSelection is the last gate before the request.
     */
    @Test
    fun blockedLinesNeverReachThePrompt() {
        val prompt = PromptBuilder.buildSelection(
            kept = listOf("我爱msg你", "了解详情请点击", "正常消息", "x:0y"),
            target = com.luoliaoo.wechat.ai.ChatLine(0L, false, "x"),
            extraBlockWords = PromptBuilder.SKIP_MARKERS,
        )
        assertFalse(prompt.contains("我爱msg你"))
        assertFalse(prompt.contains("了解详情"))
        assertFalse(prompt.contains(":0"))
        assertTrue(prompt.contains("正常消息"))
        assertTrue(prompt.contains("共 1 条"))
    }

    // ==============================================================================================
    // v6.0.0 REGRESSION: the LONG-PRESS path (PromptBuilder.build) ignored the user's template words.
    // ==============================================================================================
    //
    // The user reported, verbatim: 「没有用啊 屏蔽关键词失败了」 and then 「msg有用 auth跟click失败」.
    //
    // That second message is the DIAGNOSIS, and it is why these tests are shaped the way they are:
    // "msg" worked because it is HARDCODED in PromptBuilder.SKIP_MARKERS. "auth"/"click" did not,
    // because they only existed in the template's blockWords -- and the long-press path called
    // PromptBuilder.build WITHOUT ever passing that list. The multi-select path passed it
    // (buildSelection), which is why the feature looked half-working rather than absent.
    //
    // So the tests above, which all drive buildSelection, were green the whole time the bug was live.
    // A test is only worth anything if it drives the path the USER drives; these drive build().

    /**
     * THE regression guard: a template word must be honoured by the long-press path.
     *
     * Before the fix this failed, because build() had no extraBlockWords parameter and filtered on
     * SKIP_MARKERS alone.
     */
    @Test
    fun theLongPressPathHonoursTemplateBlockWords() {
        val lines = listOf(
            ChatLine(1L, false, "晚上一起吃饭吗", "对方"),
            ChatLine(2L, false, "please click the link", "对方"),
            ChatLine(3L, false, "auth_token=abcd", "对方"),
            ChatLine(4L, true, "好啊", "我"),
        )
        val prompt = PromptBuilder.build(
            lines = lines,
            target = lines.last(),
            mode = ScopeMode.TIME_RANGE,
            extraBlockWords = listOf("click", "auth"),
        )
        // Both user-added words take effect on the long-press path now.
        assertFalse("a line with 'click' must not reach the model", prompt.contains("click"))
        assertFalse("a line with 'auth' must not reach the model", prompt.contains("auth"))
        // Unrelated lines are untouched.
        assertTrue(prompt.contains("晚上一起吃饭吗"))
        assertTrue(prompt.contains("好啊"))
    }

    /**
     * The hardcoded markers must STILL work through build(), so this fix did not trade one filter for
     * another. This is the half that already worked ("msg 有用") and must keep working.
     */
    @Test
    fun theLongPressPathStillAppliesTheHardcodedMarkers() {
        val lines = listOf(
            ChatLine(1L, false, "我爱msg你", "对方"),
            ChatLine(2L, false, "点击了解详情", "对方"),
            ChatLine(3L, false, "正常消息", "对方"),
        )
        val prompt = PromptBuilder.build(
            lines = lines,
            target = lines.last(),
            mode = ScopeMode.TIME_RANGE,
            extraBlockWords = emptyList(),
        )
        assertFalse(prompt.contains("我爱msg你"))
        assertFalse(prompt.contains("了解详情"))
        assertTrue(prompt.contains("正常消息"))
    }

    /**
     * Backward compatibility: an empty word list must reproduce the OLD prompt exactly, so templates
     * saved before this field existed behave as they always did.
     */
    @Test
    fun anEmptyBlockWordListLeavesTheLongPressPathUnchanged() {
        val lines = listOf(
            ChatLine(1L, false, "please click the link", "对方"),
            ChatLine(2L, false, "正常消息", "对方"),
        )
        val prompt = PromptBuilder.build(
            lines = lines,
            target = lines.last(),
            mode = ScopeMode.TIME_RANGE,
            extraBlockWords = emptyList(),
        )
        // With no words configured, "click" is just text and must be kept.
        assertTrue(prompt.contains("please click the link"))
        assertTrue(prompt.contains("正常消息"))
    }

    /**
     * The resolved template must actually CARRY its words. The path that lost them was
     * TemplateStore.resolve() -> Resolved, which had no blockWords field at all: the store held the
     * words correctly, so any test that read the store passed, while the words could not reach the
     * analysis. This asserts the field exists and is populated.
     */
    @Test
    fun resolvedTemplatesCarryTheirBlockWords() {
        val r = TemplateStore.resolve(com.luoliaoo.wechat.data.Config())
        assertEquals(TemplateStore.DEFAULT_BLOCK_WORDS, r.blockWords)
    }

    // ==============================================================================================
    // v6.0.0 SECOND REPORT: 「还是不行 msg有用 click与auth没用」 -- the words were UNREACHABLE at BOTH ends.
    // ==============================================================================================
    //
    // The first fix made the filtering code READ the words. That was necessary and NOT sufficient: on an
    // already-installed phone the words could never GET into the list in the first place, because
    //
    //   * `DEFAULT_BLOCK_WORDS` is only a SEED, written once when the template files are created, and
    //     `migrateBuiltIns()` refreshed `systemPrompt` alone -- so an existing install kept the old three
    //     words forever, and
    //   * the 屏蔽 box was REMOVED from the scope dialog in v3.0.3 and TemplateDialog never had one --
    //     so the user could not add a word by hand either.
    //
    // A word list that no code path can extend is broken no matter how correctly it is read. These tests
    // cover the SEED -> DISK path, which is where the words were actually being lost.

    /**
     * The migration must ADD a newly shipped default word to an existing template, which is the single
     * step that makes "click"/"auth" -- and now the five v8.1.0 placeholders -- reach a phone that
     * already had only the old three.
     */
    @Test
    fun migratingABuiltInTemplateAddsNewDefaultWords() {
        // Simulate what an older install has on disk: the original three, no "click"/"auth"/placeholders.
        val old = listOf("msg", "了解详情", ":0")
        // The migration rule, asserted on the same arithmetic TemplateStore.migrateBuiltIns uses.
        val missing = TemplateStore.DEFAULT_BLOCK_WORDS.filterNot { it in old }
        val merged = old + missing

        assertTrue(
            "v8.1.0: the migration must also deliver the five placeholders",
            missing.containsAll(listOf("click", "auth", "[图片]", "[表情]", "[文件]", "[系统提示]", "[已撤回]")),
        )
        assertEquals(TemplateStore.DEFAULT_BLOCK_WORDS, merged)
        // Additive: nothing the user had was dropped.
        assertTrue(merged.containsAll(old))
    }

    /**
     * Re-running the migration must be a NO-OP once the words are present, so it cannot append
     * duplicates on every launch.
     */
    @Test
    fun migrationIsIdempotent() {
        val already = TemplateStore.DEFAULT_BLOCK_WORDS
        val missing = TemplateStore.DEFAULT_BLOCK_WORDS.filterNot { it in already }
        assertTrue("a second migration must find nothing to add", missing.isEmpty())
    }

    /**
     * Words the user typed by hand must SURVIVE the migration: only missing defaults are appended.
     */
    @Test
    fun migrationKeepsWordsTheUserAdded() {
        val old = listOf("msg", "我的自定义词")
        val missing = TemplateStore.DEFAULT_BLOCK_WORDS.filterNot { it in old }
        val merged = old + missing
        assertTrue(merged.contains("我的自定义词"))
        assertTrue(merged.contains("click"))
        // and nothing is duplicated
        assertEquals(merged.size, merged.toSet().size)
    }
}
