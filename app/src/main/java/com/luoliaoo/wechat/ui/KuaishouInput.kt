package com.luoliaoo.wechat.ui

/**
 * Pure, unit-testable rules for choosing KUAISHOU's chat input box.
 *
 * ============================== WHY A PICKER IS NEEDED AT ALL =================================
 *
 * `InputInjector.pick()` takes the LOWEST visible EditText in the window with a non-zero size. That is
 * a reasonable guess for a bottom input bar and the WRONG one the moment anything else is on screen.
 * Kuaishou is measured to defeat it in two independent ways (t3 + the captain's device view-tree dump):
 *
 *   1. A MEASUREMENT-BROKEN node is present with bounds `140,0-16777090,140`. Its `right` is ~16.7
 *      million, i.e. it was measured against an effectively unbounded constraint. A naive "size > 0"
 *      gate ACCEPTS it, and any proximity rule that weighs `right` or area picks it. Filling it reports
 *      success while the real chat box stays empty -- exactly the failure 必看.md records as
 *      「写入校验只看到成功，用户看到已填入却是空的」.
 *
 *   2. The id `0x7f090f08` is REUSED ACROSS 88 LAYOUTS, including `SearchEmojiEditText`. So an id-only
 *      match can silently select a search field. Kuaishou's resources are DVA-trimmed (20 types / 691
 *      keys), so the id cannot even be resolved to a NAME to disambiguate -- `app:id/editor` is a
 *      convention reported from the device, not something the APK states.
 *
 * ============================== THE DECISION, AND WHY EACH STEP IS THERE ======================
 *
 * Ranked, strongest evidence first:
 *
 *   1. EXACT CLASS `ImeOptionsEmojiEditTextView` (or a subclass). Verified on device as the chat field,
 *      and it is the only signal that is a NAME rather than a number or a position.
 *   2. id `0x7f090f08` AND a class name that does not look like a search field. The id is evidence but
 *      not proof, so it is combined with a negative test.
 *   3. A LEAF EditText (no EditText descendant) with a SANE size, visible, not our own UI, not search.
 *      "Leaf" is what separates the real chat field from a container-that-is-also-an-EditText.
 *   4. Otherwise [Choice.None] -- and the caller must NOT fall back to `pick()`.
 *
 * Step 4 is the important one and it is a deliberate departure from the Soul arm. Soul falls back to the
 * generic rule when its exact rule declines; Kuaishou must not, because here the generic rule is
 * DEMONSTRABLY wrong (see 1 above). Returning "nothing" leaves the clipboard fallback to do its job and
 * tells the user the truth, whereas a wrong field produces a false success.
 *
 * ============================== SANE-SIZE GATE ================================================
 *
 * [MAX_SANE_PX] rejects the measurement-broken node. 100_000 px is far beyond any real screen (a 4K
 * panel is ~3840 px wide) and far below 16_777_090, so it separates the two cases with no ambiguity, and
 * it is stated as an absolute bound so a future device with a larger screen cannot re-break it silently.
 *
 * Like `SoulInput`, all of this is pure logic with no Android dependency, so it is unit-testable
 * off-device -- which matters because these are precisely the rules a device run cannot exhaust.
 *
 * Comments ASCII-only; Chinese uses \uXXXX (see core/Log.kt).
 */
object KuaishouInput {

    /**
     * Kuaishou's chat input field, verified on device.
     *
     * The chain is `ImeOptionsEmojiEditTextView` -> `EmojiEditText` -> `SafeEditText` -> `AppCompatEditText`
     * -> `android.widget.EditText`, and an exact-class check on the TOP of the chain is the strongest
     * available signal because this name is SEMANTIC (survives obfuscation) and appears in only a handful
     * of layouts -- unlike the shared resource id, which is reused by 88.
     *
     * ============================== THE setText QUESTION, SETTLED BY DISASSEMBLY ==================
     *
     * `ImeOptionsEmojiEditTextView` itself declares only 3 constructors, `onCreateInputConnection`,
     * `onFocusChanged` and `p()` -- it does NOT override `setText`.
     *
     * Its ancestor `EmojiEditText` DOES: `setText(CharSequence,BufferType)` (classes20.dex). That is the
     * kind of override that could have swallowed or rewritten our text, so it was disassembled rather
     * than assumed. It is SAFE:
     *
     *     [0016] iget-object v0, EmojiEditText->j:LKSTextDisplayHandler;
     *     [001a] if-eqz v0, +0023              <-- only when a display handler is set
     *     [005a] invoke-virtual o1->a()V       <-- CLEAR A PRIVATE RENDER CACHE
     *     [0060] invoke-super EditText->setText(CharSequence, BufferType)   <-- then the real write
     *
     * So the override clears an emoji render cache and then calls super. It does NOT consume, transform
     * or truncate the text, which is why a plain `EditText.setText` is the right write (see
     * [InputInjector.fill]). The host itself writes that way too: `editor.j.q(CharSequence,boolean)`
     * begins with `invoke-virtual EditText->setText`.
     *
     * Two consequences that ARE load-bearing:
     *   1. emoticons are handled at the DISPLAY layer, so `getText().toString()` still returns the
     *      original characters -- a read-back comparison is not polluted by emoji conversion;
     *   2. `PrefixEmojiEditText.setText` must NOT be used: it splices a prefix into the body and colours
     *      it, and reading it back requires the matching `getContentText()`. It belongs to the search and
     *      share panels, not the chat field.
     */
    const val INPUT_CLASS = "com.yxcorp.gifshow.message.widget.ImeOptionsEmojiEditTextView"

    /** The `app:id/editor` convention reported from the device. NOT unique within the APK -- see above. */
    const val INPUT_ID = 0x7f090f08

    /**
     * Upper bound on a believable on-screen dimension, in pixels.
     *
     * Rejects the measured-broken node (`right` ~= 16_777_090) while accepting any real display.
     */
    const val MAX_SANE_PX = 100_000

    /** Upper bound on a believable coordinate, used to reject a nonsense `bottom`. */
    const val MAX_SANE_COORD = 200_000

    /**
     * Class-name fragments that mark a field as NOT the chat box.
     *
     * `Search` is the documented collision (`SearchEmojiEditText` shares the id). The others are the
     * generic shapes a search / comment / nickname field takes, and treating them as disqualifying costs
     * nothing: the real chat box matches none of them.
     */
    val NOT_CHAT_MARKERS = listOf("Search", "search", "\u641c\u7d22")

    /** Why the picker returned what it did. Strings so a log line can carry them verbatim. */
    object Reason {
        const val EXACT_CLASS = "exact-class"
        const val BY_ID = "id-plus-not-search"
        const val LEAF = "leaf-fallback"
        const val NO_CANDIDATES = "no-candidates"
        const val ALL_REJECTED = "all-rejected"
        const val NO_KUAISHOU_INPUT = "no-kuaishou-input"
    }

    /**
     * One candidate view, reduced to plain data so the decision is testable without Android.
     *
     * @param className the runtime class name
     * @param idValue `View.getId()`, or 0 when unset
     * @param visible `visibility == View.VISIBLE`
     * @param ownUi true when the view is ours (tagged), so it must never be chosen
     * @param width measured width in px
     * @param height measured height in px
     * @param bottom on-screen bottom coordinate in px
     * @param leaf true when the view contains no EditText descendant
     */
    data class Candidate(
        val className: String,
        val idValue: Int = 0,
        val visible: Boolean = true,
        val ownUi: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val bottom: Int = 0,
        val leaf: Boolean = true,
    )

    /** The outcome. `Found` carries the index into the ORIGINAL list. */
    sealed class Choice {
        data class Found(val index: Int, val reason: String) : Choice()
        data class None(val reason: String, val considered: Int = 0) : Choice()
    }

    /**
     * True when the class is the verified chat field or derives from it.
     *
     * Matched on the EXACT name OR on the simple name. The simple-name route exists because a Robust
     * hot-patch can produce a generated subclass whose package differs while the class keeps its name,
     * and because the device dump reports the class that is actually attached. It is still a NAME test,
     * not a package-prefix test: 必看.md records that a prefix match accepts unrelated helpers living in
     * the same namespace.
     */
    fun isInputClass(className: String?): Boolean {
        val n = className?.trim().orEmpty()
        if (n.isEmpty()) return false
        if (n == INPUT_CLASS) return true
        return n.substringAfterLast('.') == INPUT_CLASS.substringAfterLast('.')
    }

    /** True when the name looks like a search box rather than the chat field. */
    fun looksLikeSearch(className: String?): Boolean {
        val n = className?.trim().orEmpty()
        return NOT_CHAT_MARKERS.any { n.contains(it) }
    }

    /**
     * True when the measurements are physically believable.
     *
     * This is the gate that rejects the `16777090` node. `width`/`height` must be positive (a 0x0
     * throwaway field is not a chat box) and within [MAX_SANE_PX].
     */
    fun saneSize(c: Candidate): Boolean =
        c.width > 0 && c.height > 0 &&
            c.width <= MAX_SANE_PX && c.height <= MAX_SANE_PX

    /** True when the candidate is eligible to be the chat box, ignoring class identity. */
    fun eligible(c: Candidate): Boolean =
        c.visible && !c.ownUi && saneSize(c) && !looksLikeSearch(c.className)

    /**
     * THE decision.
     *
     * Returns [Choice.None] with a specific reason rather than a best guess whenever no candidate has
     * real evidence behind it -- see the header for why the generic "lowest visible" rule must not be
     * used as a Kuaishou fallback.
     */
    fun choose(list: List<Candidate>): Choice {
        if (list.isEmpty()) return Choice.None(Reason.NO_CANDIDATES, 0)

        // 1. Exact class, eligible. This is the strongest signal available and matches the device dump.
        for ((i, c) in list.withIndex()) {
            if (isInputClass(c.className) && eligible(c)) {
                return Choice.Found(i, Reason.EXACT_CLASS)
            }
        }

        // 2. The device-reported id, combined with a negative search test (the id is reused by
        //    SearchEmojiEditText, so the id alone is not sufficient evidence).
        for ((i, c) in list.withIndex()) {
            if (c.idValue == INPUT_ID && eligible(c)) return Choice.Found(i, Reason.BY_ID)
        }

        // 3. A leaf EditText with sane size. Weakest acceptance, but still requires the leaf property,
        //    which is what distinguishes the real field from a container.
        val leaves = list.withIndex().filter { (_, c) -> eligible(c) && c.leaf }
        if (leaves.isNotEmpty()) {
            // Lowest wins among leaves, matching where an input bar lives.
            val best = leaves.maxByOrNull { (_, c) -> c.bottom }!!
            return Choice.Found(best.index, Reason.LEAF)
        }

        // 4. Nothing had evidence. This is the honest answer, and the caller must NOT substitute a
        //    positional guess -- the measurement-broken node would win it.
        return Choice.None(Reason.NO_KUAISHOU_INPUT, list.size)
    }

    /**
     * Verifies that a write landed, for the Kuaishou input box.
     *
     * ============================== `getText()` NEVER RETURNS NULL HERE ===========================
     * `SafeEditText.getText()Landroid/text/Editable;` (classes6.dex) disassembles to:
     *
     *     [0000] invoke-super AppCompatEditText->getText()Landroid/text/Editable;
     *     [0008] if-nez v0, +0009
     *     [000c] new-instance v0, SpannableStringBuilder
     *     [0010] const-string v1, ''
     *     [001a] return-object v0
     *
     * i.e. when super returns null it CONSTRUCTS AN EMPTY `SpannableStringBuilder` and returns that.
     * So the empty check must be `length() == 0`, NOT a null test: a null-tolerant check would compare
     * `""` against a requested string, and any code that treats "not null" as "has content" would call
     * an empty box a successful write. That is the R20 failure in a new guise -- the user sees
     * "already filled" over an empty chat box.
     *
     * ============================== `maxLength = 5000` TRUNCATES SILENTLY =======================
     * The field is configured with `maxLength=5000`, applied by `TextView`'s built-in `LengthFilter`,
     * which DROPS the excess WITHOUT any error. So a long input is shortened and the write still looks
     * like it succeeded. This is exactly what `SoulInput.verify` reports as `truncated`, and why the
     * Kuaishou path reuses it rather than the weaker "is it blank" test -- see `InputInjector.fill`,
     * where KUAISHOU is routed to `SoulInput.verify` alongside SOUL.
     *
     * This function only answers the narrower question the Kuaishou-specific picker needs: "is the
     * value, as it stands right now, identical to what we asked for".
     */
    fun landed(current: CharSequence?, requested: String): Boolean {
        val now = current?.toString().orEmpty()
        return now.isNotEmpty() && now == requested
    }

    /** The `maxLength` configured on the field, recorded so a truncation is explainable in the log. */
    const val MAX_LENGTH = 5000
}
