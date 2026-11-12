package com.luoliaoo.wechat

import com.luoliaoo.wechat.ui.KuaishouInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [KuaishouInput] -- choosing and verifying Kuaishou's chat input box (R20).
 *
 * ============================== WHY THESE TESTS ARE THE POINT OF R20 ==========================
 *
 * `InputInjector.pick()` takes the LOWEST visible EditText with a non-zero size. On Kuaishou that rule
 * is not merely imperfect, it is guaranteed to fail, because the device view tree contains a node
 * measured against an effectively unbounded constraint:
 *
 *     bounds 140,0-16777090,140
 *
 * Its `right` is ~16.7 million, so `width > 0` is TRUE and its `bottom` is the largest on screen. Any
 * positional rule therefore selects it, `setText` succeeds on it, and the user is told 已填入 while the
 * real chat box stays empty. 必看.md records this failure shape as
 * 「写入校验只看到成功，用户看到已填入却是空的」.
 *
 * These tests use that EXACT measured geometry, so they fail if anyone reintroduces a positional guess
 * or relaxes the sanity gate.
 *
 * The second trap is pinned too: id `0x7f090f08` is reused across 88 layouts INCLUDING
 * `SearchEmojiEditText`, so an id-only match can select a search field. Kuaishou's `resources.arsc` is
 * DVA-trimmed (20 types / 691 keys), so the id cannot even be resolved to a name to disambiguate -- the
 * negative class test is the only defence, and it is asserted here.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
class KuaishouInputTest {

    private val chat = "com.yxcorp.gifshow.message.widget.ImeOptionsEmojiEditTextView"
    private val search = "com.yxcorp.gifshow.widget.SearchEmojiEditText"

    /** The real chat field as the device reports it. */
    private fun realChat(bottom: Int = 2200, id: Int = KuaishouInput.INPUT_ID) =
        KuaishouInput.Candidate(
            className = chat,
            idValue = id,
            visible = true,
            ownUi = false,
            width = 691,
            height = 154,
            bottom = bottom,
            leaf = true,
        )

    /**
     * The measurement-broken node, with the geometry measured on the device.
     *
     * Its class is unknown (the dump could not name it), so it is modelled as a plain EditText -- which
     * is the most adversarial case, because nothing about its CLASS disqualifies it. Only the size gate
     * can reject it, which is exactly the property under test.
     */
    private fun brokenNode() = KuaishouInput.Candidate(
        className = "android.widget.EditText",
        idValue = 0,
        visible = true,
        ownUi = false,
        width = 16_777_090 - 140,
        height = 140,
        bottom = 16_777_090,
        leaf = true,
    )

    // ------------------------------------------------------------------ the R20 guard

    @Test
    fun `the measurement-broken node is rejected and the real chat box wins`() {
        // THE R20 regression guard, with the device's own numbers. A positional rule picks the broken
        // node because its bottom is the largest; the evidence-based rule picks the chat field.
        val list = listOf(brokenNode(), realChat())
        val choice = KuaishouInput.choose(list)
        assertTrue("expected a found choice, got $choice", choice is KuaishouInput.Choice.Found)
        assertEquals("must pick the real chat box, not the broken node", 1, (choice as KuaishouInput.Choice.Found).index)
        assertEquals(KuaishouInput.Reason.EXACT_CLASS, choice.reason)
    }

    @Test
    fun `the broken node alone yields NOTHING rather than a false success`() {
        // The decisive behavioural change: no fallback. A generic "lowest visible" rule would return the
        // broken node here and the user would see 已填入 over an empty chat box.
        val choice = KuaishouInput.choose(listOf(brokenNode()))
        assertTrue("expected None, got $choice", choice is KuaishouInput.Choice.None)
        assertEquals(
            KuaishouInput.Reason.NO_KUAISHOU_INPUT,
            (choice as KuaishouInput.Choice.None).reason,
        )
    }

    @Test
    fun `the sanity gate is what rejects it, not its class`() {
        // SELF-CHECK on the fixture: if the broken node were rejected for some other reason (a wrong
        // class name, ownUi, visibility), the two tests above would pass without exercising the gate.
        val b = brokenNode()
        assertFalse("the size gate must be the rejecting factor", KuaishouInput.saneSize(b))
        assertTrue("it is otherwise plausible: visible", b.visible)
        assertFalse("it is not our own UI", b.ownUi)
        assertFalse("its name is not a search marker", KuaishouInput.looksLikeSearch(b.className))
        assertTrue("it is a leaf", b.leaf)
    }

    @Test
    fun `a realistic screen size is still accepted`() {
        // The gate must not be so tight that a genuine phone is rejected. 1440x3200 is a real panel.
        assertTrue(KuaishouInput.saneSize(KuaishouInput.Candidate(className = chat, width = 1440, height = 154)))
        assertTrue(KuaishouInput.saneSize(KuaishouInput.Candidate(className = chat, width = 3840, height = 200)))
        assertTrue(KuaishouInput.saneSize(KuaishouInput.Candidate(className = chat, width = 100_000, height = 100_000)))
        assertFalse("just past the bound", KuaishouInput.saneSize(KuaishouInput.Candidate(className = chat, width = 100_001, height = 100)))
    }

    // ------------------------------------------------------------------ the search-box trap

    @Test
    fun `a search box is never chosen even when it carries the chat id`() {
        // The id is reused by SearchEmojiEditText across 88 layouts, so the id alone is not proof. With
        // no exact-class candidate present, the id route must decline and the None verdict must be
        // returned rather than the search field.
        val list = listOf(
            KuaishouInput.Candidate(
                className = search,
                idValue = KuaishouInput.INPUT_ID, // the SAME id as the chat box
                visible = true,
                width = 691,
                height = 154,
                bottom = 300,
                leaf = true,
            ),
        )
        val choice = KuaishouInput.choose(list)
        assertTrue("a search box must never be chosen, got $choice", choice is KuaishouInput.Choice.None)
    }

    @Test
    fun `the chat box wins over a lower search box carrying the same id`() {
        // Even with the search box positioned LOWER (so a positional rule would prefer it), the
        // exact-class candidate must win.
        val list = listOf(realChat(bottom = 1000), KuaishouInput.Candidate(
            className = search,
            idValue = KuaishouInput.INPUT_ID,
            visible = true,
            width = 691,
            height = 154,
            bottom = 9999,
            leaf = true,
        ))
        val choice = KuaishouInput.choose(list) as KuaishouInput.Choice.Found
        assertEquals("the exact class must beat position", 0, choice.index)
        assertEquals(KuaishouInput.Reason.EXACT_CLASS, choice.reason)
    }

    @Test
    fun `search marker detection covers the documented class`() {
        assertTrue(KuaishouInput.looksLikeSearch(search))
        assertTrue(KuaishouInput.looksLikeSearch("a.b.SomeSearchEditText"))
        assertFalse("the chat field is not a search box", KuaishouInput.looksLikeSearch(chat))
    }

    // ------------------------------------------------- the EmojiEditText family trap

    @Test
    fun `the search box IS an EmojiEditText, so a family fallback would accept it`() {
        // VERIFIED BY DISASSEMBLY, and it is worse than "the id is reused":
        //
        //   com.yxcorp.plugin.search.widget.SearchEmojiEditText
        //     -> SearchOverflowFadeEmojiEditText
        //     -> com.yxcorp.gifshow.widget.EmojiEditText   <-- it IS one
        //     -> SafeEditText -> AppCompatEditText -> EditText
        //
        // So a plausible-looking fallback such as "any EmojiEditText subclass of a sane size" would
        // ACCEPT the search field. This test pins the consequence: such a candidate is not merely a
        // weak match, it must be actively DISQUALIFIED, and the disqualification must survive the fact
        // that every other property (id, size, visibility, leaf-ness) looks perfect.
        val searchThatLooksPerfect = KuaishouInput.Candidate(
            className = search,
            idValue = KuaishouInput.INPUT_ID,
            visible = true,
            width = 691,
            height = 154,
            bottom = 300,
            leaf = true,
        )
        assertTrue("size is believable", KuaishouInput.saneSize(searchThatLooksPerfect))
        assertTrue("it is visible", searchThatLooksPerfect.visible)
        assertTrue("it is a leaf", searchThatLooksPerfect.leaf)
        assertTrue(
            "the size gate must NOT be what saves us here",
            KuaishouInput.saneSize(searchThatLooksPerfect),
        )
        assertFalse(
            "the SEARCH EXCLUSION must be the thing that rejects it",
            KuaishouInput.eligible(searchThatLooksPerfect),
        )
    }

    @Test
    fun `an all-search candidate list yields None, never the search box`() {
        // The end-to-end consequence: on a search page there is no chat box, and the answer must be
        // "not found" rather than the search field. Returning the search field would type the analysis
        // text into Kuaishou's search bar.
        val list = listOf(
            KuaishouInput.Candidate(
                className = search,
                idValue = KuaishouInput.INPUT_ID,
                visible = true,
                width = 691,
                height = 154,
                bottom = 300,
                leaf = true,
            ),
            KuaishouInput.Candidate(
                className = "com.yxcorp.plugin.search.widget.SearchOverflowFadeEmojiEditText",
                idValue = KuaishouInput.INPUT_ID,
                visible = true,
                width = 691,
                height = 154,
                bottom = 400,
                leaf = true,
            ),
        )
        val choice = KuaishouInput.choose(list)
        assertTrue("expected None, got $choice", choice is KuaishouInput.Choice.None)
        assertEquals(KuaishouInput.Reason.NO_KUAISHOU_INPUT, (choice as KuaishouInput.Choice.None).reason)
    }

    // ------------------------------------------------------------------ the ranking

    @Test
    fun `the id route works when the class is a Robust-generated subclass`() {
        // A hot-patched subclass may carry a different package while keeping the field's identity, so
        // the id route is the documented second line rather than the first.
        val list = listOf(
            KuaishouInput.Candidate(
                className = "com.kwai.robust.GeneratedEditText",
                idValue = KuaishouInput.INPUT_ID,
                visible = true,
                width = 691,
                height = 154,
                bottom = 100,
                leaf = true,
            ),
        )
        val choice = KuaishouInput.choose(list) as KuaishouInput.Choice.Found
        assertEquals(0, choice.index)
        assertEquals(KuaishouInput.Reason.BY_ID, choice.reason)
    }

    @Test
    fun `the leaf rule is the last resort and prefers the lowest`() {
        val list = listOf(
            KuaishouInput.Candidate(className = "android.widget.EditText", width = 100, height = 40, bottom = 100, leaf = true),
            KuaishouInput.Candidate(className = "android.widget.EditText", width = 100, height = 40, bottom = 900, leaf = true),
        )
        val choice = KuaishouInput.choose(list) as KuaishouInput.Choice.Found
        assertEquals("lowest wins among leaves", 1, choice.index)
        assertEquals(KuaishouInput.Reason.LEAF, choice.reason)
    }

    @Test
    fun `a non-leaf container is never chosen`() {
        // Choosing a container would place text where nothing reads it -- a silent no-op.
        val list = listOf(
            KuaishouInput.Candidate(className = "android.widget.EditText", width = 691, height = 154, bottom = 500, leaf = false),
        )
        assertTrue(KuaishouInput.choose(list) is KuaishouInput.Choice.None)
    }

    @Test
    fun `our own UI is never chosen`() {
        val list = listOf(realChat().copy(ownUi = true))
        assertTrue(KuaishouInput.choose(list) is KuaishouInput.Choice.None)
    }

    @Test
    fun `an invisible chat box is not chosen`() {
        val list = listOf(realChat().copy(visible = false))
        assertTrue(KuaishouInput.choose(list) is KuaishouInput.Choice.None)
    }

    @Test
    fun `a zero-sized throwaway field is not chosen`() {
        val list = listOf(realChat().copy(width = 0, height = 0))
        assertTrue(KuaishouInput.choose(list) is KuaishouInput.Choice.None)
    }

    @Test
    fun `an empty candidate list reports a specific reason`() {
        val choice = KuaishouInput.choose(emptyList()) as KuaishouInput.Choice.None
        assertEquals(KuaishouInput.Reason.NO_CANDIDATES, choice.reason)
        assertEquals(0, choice.considered)
    }

    // ------------------------------------------------------------------ class identity

    @Test
    fun `class identity matches the device-reported name and its simple name`() {
        assertTrue(KuaishouInput.isInputClass(chat))
        // A Robust-generated subclass in another package still keeps the class name.
        assertTrue(KuaishouInput.isInputClass("com.other.pkg.ImeOptionsEmojiEditTextView"))
        assertFalse(KuaishouInput.isInputClass(search))
        assertFalse(KuaishouInput.isInputClass("android.widget.EditText"))
        assertFalse(KuaishouInput.isInputClass(null))
        assertFalse(KuaishouInput.isInputClass(""))
    }

    // ------------------------------------------------------------------ write verification

    @Test
    fun `an empty field is never reported as a successful write`() {
        // SafeEditText.getText() never returns null, so the meaningful test is emptiness. A null-tolerant
        // check would report success over a field that is still blank.
        assertFalse(KuaishouInput.landed("", "hello"))
        assertFalse(KuaishouInput.landed(null, "hello"))
        assertFalse(KuaishouInput.landed("", ""))
    }

    @Test
    fun `whitespace that matches the request DID land`() {
        // Deliberate: `landed` answers "did the write take", not "is the text meaningful". If the caller
        // asked for spaces and the field holds exactly those spaces, the write landed -- reporting a
        // failure there would send the user a clipboard copy over a write that actually worked. The
        // MEANINGFULNESS of the text is not this function's question: the chat pipeline's own blank-text
        // gate handles that (see AnalysisController's `blank-text` rejection).
        assertTrue(KuaishouInput.landed("   ", "   "))
        assertFalse("a mismatch is still a failure", KuaishouInput.landed("  ", "   "))
    }

    @Test
    fun `an exact write is reported as landed`() {
        assertTrue(KuaishouInput.landed("hello", "hello"))
    }

    @Test
    fun `a truncated write is NOT reported as landed`() {
        // maxLength=5000 silently truncates, and a "not blank" test would call that success -- the exact
        // 必看.md failure where the user sees 已填入 but sends half a sentence.
        val requested = "x".repeat(KuaishouInput.MAX_LENGTH + 500)
        val landed = requested.substring(0, KuaishouInput.MAX_LENGTH)
        assertFalse("a silently truncated value must not count as landed", KuaishouInput.landed(landed, requested))
    }

    @Test
    fun `maxLength is recorded as the value the field configures`() {
        assertEquals(5000, KuaishouInput.MAX_LENGTH)
    }
}
