package com.luoliaoo.wechat.ui

/**
 * v8.0.0: EVERYTHING QQ-specific about finding and writing QQ's chat input box.
 *
 * WHY A SIBLING FILE AND NOT A BRANCH INSIDE InputInjector
 * `InputInjector` is the path the WeChat and Douyin builds already depend on, and its resolution order
 * (ActivityThread's top Activity, then the LOWEST sized visible EditText) is calibrated on WeChat's
 * layout. Sprinkling `if (isQq)` through it would make every future input bug a four-host bug. QQ's
 * knowledge therefore lives here, and `InputInjector` consults this object ONLY when
 * `HostApp.current == QQ`, so the WeChat/Douyin/Soul/Kuaishou paths stay byte-for-byte what they were.
 *
 * ============================ HOW EVERY FACT BELOW WAS CONFIRMED ==============================
 *
 * This is NOT static recon alone: unlike t3 (Kuaishou, static-only, no device), a rooted device was
 * available, so every load-bearing claim here has a RUNTIME dump behind it. Commands and raw output are
 * quoted in `_tools/qq/QQ_INPUT_NOTES.md`; the summary:
 *
 *   1. CLASS, from the live view tree (`dumpsys activity top | grep -i edittext`, root shell):
 *
 *          com.tencent.mobileqq.aio.input.edit.AIOEditText{... 51,19-954,152 #7f0a3a5c app:id/input ...}
 *
 *      The SAME node line carries BOTH the class name AND the resource id, which is what makes the
 *      dual criterion below a measured pair rather than two independent guesses.
 *
 *   2. It IS an `android.widget.EditText`. Verified by dex hierarchy, not assumed:
 *      `qq.py sup com/tencent/mobileqq/aio/input/edit/AIOEditText` prints
 *          AIOEditText  ->  androidx.appcompat.widget.AppCompatEditText  ->  android.widget.EditText
 *      That is why `InputInjector`'s tree walk can even see it.
 *
 *   3. `setText` IS SAFE, settled by DISASSEMBLY rather than assumed:
 *      `AIOEditText` DOES override `setText(CharSequence, TextView$BufferType)` (classes3.dex), which is
 *      exactly the kind of override that could swallow or rewrite our text. It was disassembled:
 *
 *          [0002] branch 0x38 -> [0010]                    <-- QFix patch redirector, not taken normally
 *          [0010] invoke 0x6f android/widget/EditText->setText(Ljava/lang/CharSequence;Landroid/widget/TextView$BufferType;)V
 *          [0013] branch 0x28 -> [0420]
 *          [0015] new-instance v5, Ljava/lang/StringBuilder;      <-- only builds a LOG string
 *          [0024] const-string v5, ''
 *          [0028] invoke 0x71 com/tencent/qphone/base/util/QLog->i(...)V
 *          [002b] return-void
 *
 *      i.e. it calls super and then logs. It does NOT consume, transform or truncate the text. A plain
 *      `EditText.setText` is therefore the right write, and `getText()` is NOT overridden at all
 *      (`qq_dis.py ... getText` prints nothing), so reading back returns the real Editable.
 *
 *   4. THE FIELD MANIPULATES THE IME CONNECTION. `AIOEditText` has an inner subclass of
 *      `android.view.inputmethod.InputConnectionWrapper` (`AIOEditText$a`, classes6.dex) that overrides
 *      `deleteSurroundingText`. This is the SAME failure shape t3 found on Kuaishou, so "is it blank"
 *      is too weak a write check here too -- QQ is routed to the exact `SoulInput.verify` comparison in
 *      `InputInjector.fill` (that routing already exists and covers QQ).
 *
 *   5. `maxLength` TRUNCATES SILENTLY. The chat layouts set `maxLength=@ref/0x7f0b0024`, and
 *      `aapt2 dump resources` resolves that to the integer value `4500`. `TextView` applies it with a
 *      built-in `LengthFilter`, which DROPS the excess with no error -- a long fill is shortened and
 *      still looks like success. That is precisely `SoulInput.verify`'s `truncated`, and why the exact
 *      comparison is load-bearing rather than nice-to-have.
 *
 * ============================ WHY CLASS *AND* ID, NOT EITHER ALONE ============================
 *
 * 必看.md records this failure class twice: judging by `instanceof` alone OR by resource id alone both
 * mis-select, because a host's chat page can have a search box and the chat box on screen at once.
 * QQ reproduces BOTH halves of that trap, which is measured, not hypothesised:
 *
 *   (a) THE ID IS NOT UNIQUE ACROSS LAYOUTS. Scanning all 25,095 compiled layouts under the APK's
 *       obfuscated `r/` directory for the tag `com.tencent.mobileqq.aio.input.edit.AIOEditText` finds
 *       EIGHT layouts, using FOUR different ids:
 *
 *           r/i/8g.xml   id 0x7f0a2895   (dialog_list_up_pressed)
 *           r/i/8v.xml   id 0x7f0a3a5c   (id/input)          <-- the chat bar
 *           r/i/d06.xml  id 0x7f0a1e52   share panel 「给好友留言」
 *           r/i/d07.xml  id 0x7f0a1e52   share panel 「给好友留言」
 *           r/i/ep4.xml  id 0x7f0a3a5c   (id/input)
 *           r/i/ey7.xml  id 0x7f0a1e3e   「请输入文件标题」
 *           r/i/eyh.xml  id 0x7f0a3a5c   (id/input)
 *           r/i/eyt.xml  id 0x7f0a3a5c   (id/input)
 *
 *       So `id == 0x7f0a3a5c` matches the CHAT bar AND the share panel's comment box, and the class name
 *       `AIOEditText` alone matches six non-chat fields (share panel, file title, dialog). NEITHER test
 *       alone is sufficient. Requiring BOTH is what makes the choice safe -- and that pair is exactly
 *       what the runtime dump shows on one node.
 *
 *       REVERSE DIRECTION, also measured: `id/input` is carried by **19 layouts using 5 different
 *       widget classes** (`_tools/qq/qq_id_usage.py`) -- `XEditTextEx` x9, `AIOEditText` x4,
 *       `EditText` x3, `ClearableEditText` x2, `TKDListenFocusEditText` x1 -- and ALL of them are
 *       `android.widget.EditText` subclasses. So `id/input` + `instanceof EditText` is NOT sufficient
 *       either; see [NOT_CHAT_CLASSES]. The class test is not decoration, it is the discriminator.
 *
 *   (b) THE MEASUREMENT-BROKEN NODE IS HERE TOO. The live tree contains
 *
 *           androidx.appcompat.widget.AppCompatEditText{... 140,0-16777090,140 ...}
 *
 *       whose `right` is ~16.7 million -- the identical pathology t3 measured on Kuaishou. Any
 *       positional rule ("lowest visible EditText", or one weighing area/right) selects it and reports a
 *       successful fill while the real chat box stays empty. Note this node is NOT `AIOEditText` and not
 *       `id/input`, so the dual criterion rejects it for free.
 *
 *   (c) A SEARCH FIELD IS ALSO PRESENT: `com.tencent.mobileqq.search.view.QuickPinyinEditText`
 *       (`id 0x7f0a63e8`). It is a plain `EditText`, so a bare `instanceof EditText` walk finds it.
 *       The name test rejects it, and so does the class test.
 *
 * ============================ WHY THERE IS NO POSITIONAL FALLBACK ============================
 *
 * Steps are ranked, and when none has evidence this returns [Choice.None] rather than a positional
 * guess -- the same deliberate departure the Kuaishou arm makes and for the identical measured reason
 * ((b) above). Returning nothing lets `InputInjector.fill` take its clipboard fallback, which never
 * loses the user's text and tells the truth in the log. A wrong field is worse than no field: it
 * produces a false success ("已填入输入框") over an empty chat box.
 *
 * Note this is NOT a "decline because the class is widely referenced" style pseudo-rule -- 必看.md
 * records that mistake explicitly. The dual criterion below is used to ACCEPT the authoritative
 * class+id pair, not to reject it.
 *
 * Comments follow the house rule: ASCII-only; Chinese uses \uXXXX escapes.
 */
object QQInput {

    /**
     * QQ's chat input field, verified in the LIVE view tree on device.
     *
     * `com.tencent.mobileqq.aio.input.edit.AIOEditText` extends
     * `androidx.appcompat.widget.AppCompatEditText` -> `android.widget.EditText`.
     *
     * The name is SEMANTIC (the `aio`/`input`/`edit` segments are readable, not obfuscated) and it is
     * DEFINED in the APK -- confirmed with `qq.py def <class> --exact`, which reports
     * `DEFINED com.tencent.mobileqq.aio.input.edit.AIOEditText [dex=classes3.dex ...]`.
     * Per the task rules, a byte-substring hit is NOT evidence (SMAP source names also match); the
     * class_def check is.
     */
    const val INPUT_CLASS = "com.tencent.mobileqq.aio.input.edit.AIOEditText"

    /**
     * The chat input box's resource id, measured on the SAME live node as [INPUT_CLASS]:
     *
     *     com.tencent.mobileqq.aio.input.edit.AIOEditText{... #7f0a3a5c app:id/input ...}
     *
     * Resolved to the NAME `id/input` with `aapt2 dump resources` (authoritative), which prints
     *     `resource 0x7f0a3a5c id/input`
     *
     * IMPORTANT -- this id is NOT unique (see the header): `r/i/8v.xml`, `r/i/ep4.xml`, `r/i/eyh.xml`
     * and `r/i/eyt.xml` all declare an `AIOEditText` with it, and the share panel uses a different id
     * (`0x7f0a1e52`). It is therefore used only IN COMBINATION with the class name.
     */
    const val INPUT_ID = 0x7f0a3a5c

    /** The id of the send button next to the chat box; recorded for log correlation, never a handle. */
    const val SEND_BTN_ID = 0x7f0a77e0

    /**
     * Upper bound on a believable on-screen dimension, in pixels.
     *
     * Rejects the measured-broken node (`right` ~= 16_777_090, the same pathology t3 measured on
     * Kuaishou) while accepting any real display: a 4K panel is ~3840 px wide. Stated as an absolute
     * bound so a future larger screen cannot silently re-break it.
     */
    const val MAX_SANE_PX = 100_000

    /** Upper bound on a believable coordinate, used to reject a nonsense `bottom`. */
    const val MAX_SANE_COORD = 200_000

    /**
     * The `maxLength` QQ's chat layouts apply, resolved from `@ref/0x7f0b0024` by
     * `aapt2 dump resources` -> `integer/o` = `4500`.
     *
     * Recorded so a truncation is EXPLAINABLE in the log rather than mysterious: `TextView` enforces it
     * with a built-in `LengthFilter`, which drops the excess with no error, so a fill longer than this
     * is shortened silently and would otherwise look like a success.
     */
    const val MAX_LENGTH = 4500

    /**
     * Class-name fragments that mark a field as NOT the chat box.
     *
     * `Search` is the documented collision a bare `instanceof EditText` walk hits
     * (`com.tencent.mobileqq.search.view.QuickPinyinEditText` is a live `EditText`). The Chinese
     * fragment is the localised form of the same idea. Treating these as disqualifying costs nothing,
     * because the real chat box matches none of them.
     */
    val NOT_CHAT_MARKERS = listOf("Search", "search", "Pinyin", "\u641c\u7d22")

    /**
     * Widget classes that ALSO declare [INPUT_ID] but are demonstrably NOT the chat box.
     *
     * ================== WHY THIS LIST EXISTS (it is a measured correction) ====================
     * An independent second dump of the live chat page (`uiautomator dump`, `_tools/qq/qa/ui_chat.xml`)
     * showed that `id/input` occurs EXACTLY ONCE on the chat screen, which naturally suggests that
     * `id/input` + `instanceof EditText` is a sufficient criterion. That inference is TRUE FOR THAT
     * SCREEN and FALSE FOR THE APK:
     *
     * scanning all 25,095 compiled layouts for `android:id="@ref/0x7f0a3a5c"` (tool:
     * `_tools/qq/qq_id_usage.py`) finds it used in **19 layouts by 5 DIFFERENT widget classes**:
     *
     *     com.tencent.widget.XEditTextEx                                x9   (a2h/akh/aqm/av2/d09/eea/ez/g2/ji)
     *     com.tencent.mobileqq.aio.input.edit.AIOEditText               x4   (8v/ep4/eyh/eyt)  <-- the chat box
     *     EditText                                                      x3   (jt/jw/k1)
     *     com.tencent.mobileqq.widget.ClearableEditText                 x2   (co6/jz)
     *     com.tencent.common.danmaku.edit.views.TKDListenFocusEditText  x1   (co_)
     *
     * ALL FIVE descend from `android.widget.EditText`, so `id + instanceof EditText` does NOT
     * discriminate: on a screen showing a danmaku (bullet-comment) editor or a `ClearableEditText`
     * field, that criterion selects the WRONG field and the write reports a false success. The id
     * alone is therefore NOT a class-safe anchor, and the class test is load-bearing.
     *
     * The chat page's own `id/input` IS unique per screen, so tier 1 (class+id) is unambiguous there.
     * This list guards only tier 3 (id without a recognised class), where a single-screen dump cannot
     * tell us whether the id belongs to the chat widget or to a sibling editor.
     */
    val NOT_CHAT_CLASSES = listOf(
        "com.tencent.widget.XEditTextEx",
        "com.tencent.mobileqq.widget.ClearableEditText",
        "com.tencent.common.danmaku.edit.views.TKDListenFocusEditText",
    )

    /** Why the picker returned what it did. Strings so a log line can carry them verbatim. */
    object Reason {
        /** Class name AND id BOTH matched: the measured pair. Strongest evidence. */
        const val CLASS_AND_ID = "class+id"

        /** Class matched exactly and the field was otherwise eligible, but the id differed. */
        const val EXACT_CLASS = "exact-class"

        /** The id matched a non-search field of an unrecognised class, but not a known decoy. */
        const val BY_ID = "id-plus-not-search"

        /** The id matched, but the class is one of [NOT_CHAT_CLASSES] -- a measured decoy. */
        const val ID_ON_DECOY = "id-on-decoy"

        const val NO_CANDIDATES = "no-candidates"
        const val ALL_REJECTED = "all-rejected"
        const val NO_QQ_INPUT = "no-qq-input"
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
     * Matched on the EXACT name OR on the simple name. The simple-name route exists because a QFix/RFix
     * hot-patch (QQ ships RFix 1.9.14.20) can produce a generated subclass whose package differs while
     * the class keeps its name, and because the device dump reports the class that is actually attached.
     * It is still a NAME test, not a package-prefix test: 必看.md records that a prefix match accepts
     * unrelated helpers living in the same namespace -- and QQ really does ship other `EditText`s with
     * the same shape of name (`com.tencent.guild.aio.input.widget.GuildAIOInputEditText`), which a
     * prefix test would wrongly accept.
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
     * True when the class is a MEASURED decoy that also carries [INPUT_ID] -- see [NOT_CHAT_CLASSES].
     *
     * A simple-name test, like [isInputClass], so a hot-patched or repackaged copy is still caught.
     * Deliberately NOT a prefix test: 必看.md records that prefix matching accepts unrelated helpers in
     * the same namespace.
     */
    fun looksLikeIdDecoy(className: String?): Boolean {
        val n = className?.trim().orEmpty()
        if (n.isEmpty()) return false
        val simple = n.substringAfterLast('.')
        return NOT_CHAT_CLASSES.any { it.substringAfterLast('.') == simple }
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
     * THE decision: a ranked choice whose top tier is the measured class+id PAIR.
     *
     * Returns [Choice.None] with a specific reason rather than a best guess whenever no candidate has
     * real evidence behind it -- see the header for why the generic "lowest visible" rule must not be
     * used as a QQ fallback.
     */
    fun choose(list: List<Candidate>): Choice {
        if (list.isEmpty()) return Choice.None(Reason.NO_CANDIDATES, 0)

        // 1. THE MEASURED PAIR: exact class AND the documented id on the SAME node. This is the only
        //    tier whose evidence came from one runtime line, so it is checked first and wins outright.
        for ((i, c) in list.withIndex()) {
            if (isInputClass(c.className) && c.idValue == INPUT_ID && eligible(c)) {
                return Choice.Found(i, Reason.CLASS_AND_ID)
            }
        }

        // 2. Exact class, eligible, id differing. Accepted because the CLASS is the durable handle: the
        //    id is reused by the share panel and other layouts, and QQ can re-theme/re-skin the input bar
        //    (there is a `skin_aio_input_bar_bg_local_albums` resource) without changing the widget.
        //    This is still a NAME test, so it cannot be satisfied by the search field or the
        //    measurement-broken node.
        for ((i, c) in list.withIndex()) {
            if (isInputClass(c.className) && eligible(c)) {
                return Choice.Found(i, Reason.EXACT_CLASS)
            }
        }

        // 3. The documented id on a non-search field whose class we do not recognise. This tier is the
        //    WEAKEST and was tightened after measurement: `id/input` is carried by 19 layouts using 5
        //    different EditText subclasses (see NOT_CHAT_CLASSES), so an id match alone is not proof.
        //    `eligible` already excludes search fields, invisible views and broken sizes; the extra
        //    `!looksLikeIdDecoy` gate additionally rejects the three measured competing widget classes
        //    (XEditTextEx / ClearableEditText / TKDListenFocusEditText), which are all `EditText`s and
        //    would therefore slip past an `instanceof EditText` test.
        var sawIdOnDecoy = false
        for ((i, c) in list.withIndex()) {
            if (c.idValue != INPUT_ID || !eligible(c)) continue
            if (looksLikeIdDecoy(c.className)) {
                sawIdOnDecoy = true
                continue
            }
            return Choice.Found(i, Reason.BY_ID)
        }

        // 4. No evidence. Deliberately NO positional fallback: the live tree contains a
        //    measurement-broken node (`140,0-16777090,140`) that any positional rule would select,
        //    producing a false success over an empty chat box. Returning None lets the clipboard
        //    fallback keep the user's text and makes the log truthful.
        //
        //    `ID_ON_DECOY` is reported separately so a log can distinguish "the id was present but
        //    belonged to a known competing widget" from "nothing resembling the chat box was on screen"
        //    -- two very different diagnoses that a single `NO_QQ_INPUT` would merge.
        return Choice.None(
            if (sawIdOnDecoy) Reason.ID_ON_DECOY else Reason.NO_QQ_INPUT,
            list.size,
        )
    }

    /**
     * Verifies that a write landed, for the QQ input box.
     *
     * ============================== WHY AN EXACT COMPARISON =====================================
     * `InputInjector.fill` already rejects a blank field, which catches "nothing landed at all". It
     * cannot catch the other real failures, and QQ has measured reasons for both:
     *
     *   - TRUNCATION is real here: `maxLength` resolves to [MAX_LENGTH] = 4500, enforced by a built-in
     *     `LengthFilter` that drops excess SILENTLY. A long fill is shortened and still reports success.
     *   - REWRITING is plausible here: `AIOEditText` installs an `InputConnectionWrapper`
     *     (`AIOEditText$a`) overriding `deleteSurroundingText`, and runs emoticon-span logic in
     *     `onTextChanged`. Disassembly of `setText` shows the value itself is passed through untouched,
     *     but a field that manipulates the IME connection is exactly where "not blank" is too weak.
     *
     * The failure NAMES come from `SoulInput.verify` and are reused rather than re-invented, so the two
     * hosts cannot drift apart and the existing unit tests cover the same vocabulary
     * (`truncated` / `extra-text-appended` / `differs` / `blank`).
     *
     * `getText()` is NOT overridden by `AIOEditText` (`qq_dis.py ... getText` prints no method), so the
     * read-back is the real `Editable` and this comparison is meaningful. Unlike Kuaishou's
     * `SafeEditText`, QQ will not substitute an empty `SpannableStringBuilder` for null -- but the
     * caller passes `?` through `orEmpty()`, so both shapes are handled identically.
     *
     * Pure string logic on purpose: this is the half of the QQ path that CAN be tested off-device.
     */
    fun landed(current: CharSequence?, requested: String): Boolean {
        val now = current?.toString().orEmpty()
        return now.isNotEmpty() && now == requested
    }

    /** Small helper so the picker's own logging cannot drift from the decision it reports. */
    fun describe(c: Candidate): String =
        c.className + " id=0x" + Integer.toHexString(c.idValue) +
            " " + c.width + "x" + c.height +
            " bottom=" + c.bottom +
            " visible=" + c.visible + " ownUi=" + c.ownUi + " leaf=" + c.leaf +
            " saneSize=" + saneSize(c) + " search=" + looksLikeSearch(c.className) +
            " idDecoy=" + looksLikeIdDecoy(c.className)
}
