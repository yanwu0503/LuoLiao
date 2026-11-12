package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import com.luoliaoo.wechat.core.Log

/**
 * Reads and writes WeChat's own chat input box (item 2: 编辑, v4.0.0: 文本优化).
 *
 * NEVER sends the message: the sentence is placed in the input field so the user can read, edit and
 * send it themselves. The clipboard is only ever touched as a FALLBACK when no input box can be
 * reached, so the user never loses the text.
 *
 * v4.0.0: reading and writing share ONE decision about which field is the chat box
 * (locateInput), so the text-polish feature can never read its own dialog field or write into the
 * wrong one.
 */
object InputInjector {

    private const val TAG = "LuoLiao"

    /**
     * Finds the chat input box and fills it.
     *
     * @param from the view the user tapped, used to resolve the Activity and to exclude our card
     * @return true when an input box was found and filled
     */
    fun fill(from: View, text: String): Boolean {
        val target = locateInput(from)
        if (target == null) {
            Log.w(TAG, "edit: no usable input box; falling back to the clipboard")
            // item 2: the guaranteed fallback -- put it on the clipboard. This needs no view tree, so it
            // works even when WeChat has been restarted and the card's anchors are stale.
            fallbackCopy(from.context, text)
            return false
        }
        return try {
            target.requestFocus()
            target.setText(text)
            // Put the caret at the end so the user can keep typing.
            val e: Editable? = target.text
            if (e != null) target.setSelection(e.length)
            // item 4: VERIFY the text landed. The old version reported success even when the field
            // ended up empty, which is how "已填入输入框" appeared with nothing in the chat box.
            val landed = target.text?.toString().orEmpty()
            Log.i(TAG, "edit: filled " + target.javaClass.simpleName +
                " requested=" + text.length + " actual=" + landed.length)
            if (landed.isBlank()) {
                Log.w(TAG, "edit: the field is still empty after setText")
                fallbackCopy(from.context, text)
                return false
            }
            // v6.0.0: on Soul the write is verified EXACTLY, because "not blank" is too weak a test
            // there -- see SoulInput.verify for the failure (a partial/rewritten value) it catches.
            //
            // v7.0.0: KUAISHOU gets the same exact verification, and that is a deliberate extension of
            // the rule, not a slip. The old rule was "WeChat and Douyin keep the historical test",
            // because their behaviour is verified and must not be perturbed -- Kuaishou is a NEW host
            // whose input box is `ImeOptionsEmojiEditTextView` -> `EmojiEditText` -> `SafeEditText`
            // (measured on-device), a chain that also overrides an `InputConnectionWrapper`
            // (`deleteSurroundingText`, `deleteSurroundingTextInCodePoints`). A host that manipulates
            // the IME connection is exactly where "not blank" is too weak: 必看.md records this whole
            // failure class as 「写入校验只用 isBlank()，值被截断/改写时报成功，用户看到已填入却发出去半句」.
            //
            // Letting Kuaishou fall through to the weak test would have been the very
            // `else`-absorbs-a-new-host shape this version fixed twice elsewhere
            // (AnalysisController.logLiveReject and Resolver.msgInfoClass). It is therefore written as
            // an explicit `when` so a fifth host is a COMPILE ERROR here, not a silent downgrade.
            when (com.luoliaoo.wechat.core.HostApp.current) {
                com.luoliaoo.wechat.core.HostApp.WECHAT,
                com.luoliaoo.wechat.core.HostApp.DOUYIN,
                -> {
                    // Unchanged: the historical "not blank" test above is the whole check for these two.
                }

                com.luoliaoo.wechat.core.HostApp.SOUL,
                com.luoliaoo.wechat.core.HostApp.KUAISHOU,
                com.luoliaoo.wechat.core.HostApp.QQ,
                // v9.3.0: XIAOHONGSHU gets the exact verification, and it is the same deliberate
                // extension of the rule as Kuaishou above -- not a fall-through. Xiaohongshu's chat
                // input is a rich-text editor (the view tree shows a `RichEditTextPro` under the
                // chat page), i.e. a host that post-processes what it is given, which is exactly where
                // "not blank" hides a truncated value. 必看.md records that failure class as
                // 「写入校验只用 isBlank()，值被截断/改写时报成功」.
                com.luoliaoo.wechat.core.HostApp.XIAOHONGSHU,
                -> {
                    // SoulInput.verify is pure string comparison and is NOT Soul-specific: it compares
                    // what was requested with what actually landed. Reused rather than copied, so the
                    // failure names (blank / truncated / extra-text-appended / differs) stay identical
                    // and are already covered by unit tests.
                    val v = SoulInput.verify(text, landed)
                    val host = com.luoliaoo.wechat.core.HostApp.current
                    Log.i(TAG, "edit: " + host.name + " verify " + v.line())
                    if (!v.ok) {
                        Log.w(TAG, "edit: " + host.name + " setText did NOT stick (" + v.reason + "); copying instead")
                        fallbackCopy(from.context, text)
                        return false
                    }
                }

                com.luoliaoo.wechat.core.HostApp.UNKNOWN -> {
                    // No hooks are installed for UNKNOWN, so this is unreachable; written out to keep
                    // the `when` exhaustive over the host enum (the point is the compile-time guard).
                }
            }
            Toast.makeText(from.context, "已填入输入框，请确认后发送", Toast.LENGTH_SHORT).show()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "edit: fill failed", t)
            Toast.makeText(from.context, "填入失败：" + t.javaClass.simpleName,
                Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * True when the view is an instance of the named class or one of its ancestors.
     *
     * Walked by NAME rather than with an `is` check because the host's `EmojiEditText` is not on the
     * module's compile classpath (it is not a `compileOnly` stub), so a direct type test cannot be
     * written. The walk reports the RELATIONSHIP, which is what the device check needs: the recon that
     * established this class chain was static, so the first device run should confirm that the live
     * object really does descend from `EmojiEditText`.
     */
    private fun isInstanceOfName(v: View, className: String): Boolean {
        var p: Class<*>? = v.javaClass
        var depth = 0
        while (p != null && depth < 12) {
            if (p.name == className) return true
            p = p.superclass
            depth++
        }
        return false
    }

    /**
     * The effective maximum length of an EditText, or -1 when unbounded.
     *
     * Read from the installed filters rather than assumed: the field is reported to carry
     * `maxLength=5000`, and exceeding it is truncated SILENTLY (no exception, no error), so a fill can
     * look successful while the text was cut. Printing the real bound lets a device run confirm the
     * truncation limit instead of trusting the recon.
     */
    private fun maxLengthOf(v: EditText): Int {
        val fs = try {
            v.filters
        } catch (_: Throwable) {
            return -1
        }
        if (fs == null) return -1
        for (f in fs) {
            if (f is android.text.InputFilter.LengthFilter) {
                return try {
                    f.max
                } catch (_: Throwable) {
                    -1
                }
            }
        }
        return -1
    }

    /** The host's emoji-aware EditText base, checked by name because it is not on our classpath. */
    private const val EMOJI_EDIT_TEXT = "com.yxcorp.gifshow.widget.EmojiEditText"

    /**
     * v4.0.0 (长按悬浮球 → 文本优化): reads the chat input box's CURRENT text.
     *
     * Returns null when no input box can be reached, and "" when the box exists but is empty -- the two
     * cases are different for the caller (the first is "we could not look", the second is "there is
     * nothing typed yet"). Never throws: a long press must not take the chat screen down.
     */
    fun readCurrent(from: View): String? {
        val target = locateInput(from) ?: return null
        return try {
            val text = target.text?.toString().orEmpty()
            Log.i(TAG, "polish: read " + text.length + " char(s) from the chat input box")
            text
        } catch (t: Throwable) {
            Log.w(TAG, "polish: reading the input box failed: " + t.message)
            null
        }
    }

    /**
     * v4.0.0: the chat input box itself, so a caller can inspect or re-focus it.
     *
     * Exposed so the text-polish feature and 编辑 share ONE decision about which field is the chat box.
     */
    fun currentInputView(from: View): EditText? = locateInput(from)

    /**
     * v4.0.0: THE single place that decides which EditText is WeChat's chat input box.
     *
     * Both [fill] and [readCurrent] go through this, so reading and writing can never disagree about
     * which field they mean -- the failure mode would be reading our own dialog's field or writing into
     * the wrong one.
     *
     * The resolution order is unchanged from item 5: ActivityThread's top Activity first (it is right
     * whether the card is fresh or restored), then the context chain, then the attached view.
     */
    private fun locateInput(from: View): EditText? {
        // item 5: THE CURRENT ACTIVITY IS ASKED FIRST.
        //
        // The log shows the failing case searching the WRONG tree:
        //
        //     FAILING:  edit: found 0 EditText candidate(s)
        //     WORKING:  edit: found 2 EditText candidate(s)
        //
        // `activityOf(from.context)` used to be tried first, and on a card restored after a restart that
        // context leads to a stale Activity. Because it was non-null the fallbacks never ran, so the search
        // walked the wrong window, found nothing, and 编辑 said "未找到聊天输入框".
        //
        // ActivityThread's top Activity is the window the user is actually looking at, and it is correct
        // whether the card is fresh or restored -- so it comes first.
        val activity = currentActivityViaActivityThread()
            ?: activityOf(from.context)
            ?: activityFromAttachedView(from)
        if (activity == null) {
            Log.w(TAG, "edit: no Activity found from any source")
            return null
        }
        Log.i(TAG, "edit: using Activity " + activity.javaClass.simpleName +
            (if (activity.isFinishing) " (FINISHING!)" else ""))

        // Both roots: the decor view and the content view.
        val roots = ArrayList<View>(2)
        activity.window?.decorView?.let { roots.add(it) }
        try {
            activity.findViewById<ViewGroup>(android.R.id.content)?.let {
                if (roots.isEmpty() || roots[0] !== it) roots.add(it)
            }
        } catch (_: Throwable) {
            // the decor view is enough
        }
        if (roots.isEmpty()) {
            Log.w(TAG, "edit: no view root in " + activity.javaClass.simpleName)
            return null
        }

        val candidates = ArrayList<EditText>(8)
        for (r in roots) {
            collectEditTexts(r, candidates, 0, from)
        }
        Log.i(TAG, "edit: found " + candidates.size + " EditText candidate(s)")
        for (c in candidates) {
            Log.i(TAG, "  candidate " + c.javaClass.name +
                " w=" + c.width + " h=" + c.height +
                " visible=" + (c.visibility == View.VISIBLE) +
                " shown=" + c.isShown +
                " attached=" + c.isAttachedToWindow)
        }
        val target = when {
            isKuaishouHost() -> {
                // v7.0.0 Kuaishou: an EXACT decision with NO fallback to pick().
                //
                // This is the one place where the Kuaishou arm deliberately differs from the Soul arm
                // below. Soul falls back to the generic "lowest visible field" rule when its exact rule
                // declines; Kuaishou must NOT, because on Kuaishou that generic rule is DEMONSTRABLY
                // wrong: the device view tree contains a measurement-broken node with bounds
                // `140,0-16777090,140`, whose enormous `bottom` guarantees it wins any positional rule.
                // The write would then report success while the real chat box stayed empty -- 必看.md's
                // 「已填入 but the box is empty」 failure, reproduced exactly.
                //
                // Returning null instead lets fill() take its clipboard fallback, which never loses the
                // user's text, and makes the log say the truth. A wrong field is worse than no field.
                pickKuaishou(candidates)
            }
            isQqHost() -> {
                // v8.0.0 QQ: an EXACT decision with NO fallback to pick() -- the same shape as the
                // Kuaishou arm above, and for the same MEASURED reason (see QQInput's header):
                // QQ's live view tree also contains a measurement-broken node
                // (`androidx.appcompat.widget.AppCompatEditText ... 140,0-16777090,140`), whose
                // enormous `right`/`bottom` guarantees it wins any positional rule. Writing into it
                // would report success while the real chat box stayed empty.
                //
                // `pick()` is additionally unsafe here because a plain `instanceof EditText` walk also
                // finds `com.tencent.mobileqq.search.view.QuickPinyinEditText` (the search box), which
                // is live on screen at the same time as the chat box on some screens.
                //
                // The decision itself is `QQInput.choose`, which requires the measured CLASS+ID pair
                // and is pure logic (unit-tested off-device).
                pickQq(candidates)
            }
            isSoulHost() -> {
                // v6.0.0: Soul gets an EXACT-class decision first; WeChat and Douyin are untouched.
                pickSoul(candidates) ?: run {
                    // A Soul build whose chat field is not one of the two known classes would otherwise
                    // dead-end here. Falling back to the historical "lowest visible field" rule keeps the
                    // feature working (that rule already found the box before this change).
                    //
                    // This line deliberately does NOT name a cause: pickSoul() has just logged the exact
                    // reason ("no-soul-input-class" vs "all-rejected", with the per-view rejections above
                    // it), and guessing here could contradict that line. It says only WHICH rule runs next.
                    Log.w(
                        TAG,
                        "edit: SOUL exact chat-input rule declined (reason logged above); falling back" +
                            " to the generic lowest-visible rule",
                    )
                    pick(candidates)
                }
            }
            else -> pick(candidates)
        }
        if (target == null) {
            Log.w(TAG, "edit: no usable input box among " + candidates.size +
                " candidate(s) in " + activity.javaClass.simpleName)
        }
        return target
    }

    /**
     * v6.0.0: true when this process is Soul.
     *
     * Read through `HostApp.current` -- the SINGLE host decision, set once by ModuleEntry -- rather
     * than by testing the package name again, so there is one answer to "which app is this".
     */
    private fun isSoulHost(): Boolean =
        com.luoliaoo.wechat.core.HostApp.current == com.luoliaoo.wechat.core.HostApp.SOUL

    /**
     * v7.0.0: true when this process is Kuaishou.
     *
     * Read through `HostApp.current`, the single host decision, for the same reason as [isSoulHost].
     */
    private fun isKuaishouHost(): Boolean =
        com.luoliaoo.wechat.core.HostApp.current == com.luoliaoo.wechat.core.HostApp.KUAISHOU

    /**
     * v8.0.0: true when this process is QQ.
     *
     * Read through `HostApp.current`, the single host decision, for the same reason as [isSoulHost].
     */
    private fun isQqHost(): Boolean =
        com.luoliaoo.wechat.core.HostApp.current == com.luoliaoo.wechat.core.HostApp.QQ

    /**
     * v7.0.0: Kuaishou's chat input box, picked by CLASS / ID / LEAF -- never by position.
     *
     * WHY THIS EXISTS: see the long comment on the call site. In short, Kuaishou's view tree contains a
     * measurement-broken node (`right` ~= 16_777_090) that any positional rule would select, and the id
     * `0x7f090f08` is reused across 88 layouts including `SearchEmojiEditText`. So this replaces the
     * positional guess with evidence, and returns nothing rather than falling back when evidence is
     * absent.
     *
     * The DECISION lives in [KuaishouInput.choose], which is pure logic and unit-tested; this function
     * only adapts real views to it. Every rejection is logged here with the measured numbers, because a
     * bare "no input box" line cannot distinguish "the field was not there" from "our own gate was too
     * strict" -- the distinction that cost the Douyin round a full debug cycle.
     */
    private fun pickKuaishou(list: List<EditText>): EditText? {
        val candidates = ArrayList<KuaishouInput.Candidate>(list.size)
        for (c in list) {
            val loc = IntArray(2)
            try {
                c.getLocationOnScreen(loc)
            } catch (_: Throwable) {
                // A detached view cannot report a location; (0,0) keeps it comparable.
            }
            candidates.add(
                KuaishouInput.Candidate(
                    className = c.javaClass.name,
                    idValue = try {
                        c.id
                    } catch (_: Throwable) {
                        0
                    },
                    visible = c.visibility == View.VISIBLE,
                    ownUi = c.tag == com.luoliaoo.wechat.core.Constants.TAG_OWN_UI,
                    width = c.width,
                    height = c.height,
                    bottom = loc[1] + c.height,
                    leaf = isLeafEditText(c),
                ),
            )
        }
        // Per-candidate evidence, so a rejection can be explained rather than guessed.
        for ((i, c) in candidates.withIndex()) {
            Log.i(
                TAG,
                "  kuaishou candidate #" + i + " " + c.className +
                    " id=0x" + Integer.toHexString(c.idValue) +
                    " " + c.width + "x" + c.height +
                    " bottom=" + c.bottom +
                    " visible=" + c.visible + " ownUi=" + c.ownUi + " leaf=" + c.leaf +
                    " saneSize=" + KuaishouInput.saneSize(c) +
                    " search=" + KuaishouInput.looksLikeSearch(c.className),
            )
        }
        when (val choice = KuaishouInput.choose(candidates)) {
            is KuaishouInput.Choice.Found -> {
                val v = list[choice.index]
                Log.i(
                    TAG,
                    "edit: KUAISHOU chat input = " + v.javaClass.name +
                        " (reason=" + choice.reason +
                        " id=0x" + Integer.toHexString(candidates[choice.index].idValue) +
                        " " + v.width + "x" + v.height + ")" +
                        // The identity/size facts the picker relied on, printed together so the FIRST
                        // device run can confirm the static recon (t3 was static-only, no device):
                        // exact runtime class, whether the id matched the documented one, and whether
                        // the filters are what we assume.
                        " [device-check classSimple=" + v.javaClass.simpleName +
                        " isExactClass=" + (v.javaClass.name == KuaishouInput.INPUT_CLASS) +
                        " isEmojiEditText=" + isInstanceOfName(v, EMOJI_EDIT_TEXT) +
                        " idMatchesDocumented=" + (candidates[choice.index].idValue == KuaishouInput.INPUT_ID) +
                        " hasFilters=" + (v.filters != null && v.filters.isNotEmpty()) +
                        " filterCount=" + (v.filters?.size ?: 0) +
                        " maxLen=" + maxLengthOf(v) +
                        " inputType=0x" + Integer.toHexString(v.inputType) +
                        " enabled=" + v.isEnabled + " focusable=" + v.isFocusable + "]",
                )
                return v
            }
            is KuaishouInput.Choice.None -> {
                Log.w(
                    TAG,
                    "edit: KUAISHOU found NO chat input box (reason=" + choice.reason +
                        " considered=" + choice.considered + " of " + candidates.size +
                        "). NOT falling back to the positional rule on purpose: the view tree contains " +
                        "a measurement-broken node (right ~= 16777090) that would win it and report a " +
                        "successful fill while the real chat box stays empty. The clipboard fallback " +
                        "keeps the text instead.",
                )
                return null
            }
        }
    }

    /**
     * v8.0.0: QQ's chat input box, picked by the measured CLASS + ID pair -- never by position.
     *
     * WHY THIS EXISTS: see the long comment on the call site. In short, on QQ a bare
     * `instanceof EditText` walk also finds `com.tencent.mobileqq.search.view.QuickPinyinEditText` (the
     * search box) and a measurement-broken node (`right` ~= 16_777_090); the id `0x7f0a3a5c` is reused
     * by the share panel's comment box and by three other layouts. So this replaces the positional
     * guess with evidence, and returns nothing rather than falling back when evidence is absent.
     *
     * The DECISION lives in [QQInput.choose], which is pure logic and unit-tested; this function only
     * adapts real views to it. Every rejection is logged with the measured numbers, because a bare
     * "no input box" line cannot distinguish "the field was not there" from "our own gate was too
     * strict" -- the distinction that cost the Douyin round a full debug cycle.
     */
    private fun pickQq(list: List<EditText>): EditText? {
        val candidates = ArrayList<QQInput.Candidate>(list.size)
        for (c in list) {
            val loc = IntArray(2)
            try {
                c.getLocationOnScreen(loc)
            } catch (_: Throwable) {
                // A detached view cannot report a location; (0,0) keeps it comparable.
            }
            candidates.add(
                QQInput.Candidate(
                    className = c.javaClass.name,
                    idValue = try {
                        c.id
                    } catch (_: Throwable) {
                        0
                    },
                    visible = c.visibility == View.VISIBLE,
                    ownUi = c.tag == com.luoliaoo.wechat.core.Constants.TAG_OWN_UI,
                    width = c.width,
                    height = c.height,
                    bottom = loc[1] + c.height,
                    leaf = isLeafEditText(c),
                ),
            )
        }
        // Per-candidate evidence, so a rejection can be explained rather than guessed.
        for ((i, c) in candidates.withIndex()) {
            Log.i(TAG, "  qq candidate #" + i + " " + QQInput.describe(c))
        }
        when (val choice = QQInput.choose(candidates)) {
            is QQInput.Choice.Found -> {
                val v = list[choice.index]
                Log.i(
                    TAG,
                    "edit: QQ chat input = " + v.javaClass.name +
                        " (reason=" + choice.reason +
                        " id=0x" + Integer.toHexString(candidates[choice.index].idValue) +
                        " " + v.width + "x" + v.height + ")" +
                        // The identity/size facts the picker relied on, printed together so a device
                        // run can confirm the measured pair rather than trusting the doc:
                        " [device-check classSimple=" + v.javaClass.simpleName +
                        " isExactClass=" + (v.javaClass.name == QQInput.INPUT_CLASS) +
                        " idMatchesDocumented=" + (candidates[choice.index].idValue == QQInput.INPUT_ID) +
                        " hasFilters=" + (v.filters != null && v.filters.isNotEmpty()) +
                        " filterCount=" + (v.filters?.size ?: 0) +
                        " maxLen=" + maxLengthOf(v) +
                        " inputType=0x" + Integer.toHexString(v.inputType) +
                        " enabled=" + v.isEnabled + " focusable=" + v.isFocusable + "]",
                )
                return v
            }
            is QQInput.Choice.None -> {
                Log.w(
                    TAG,
                    "edit: QQ found NO chat input box (reason=" + choice.reason +
                        " considered=" + choice.considered + " of " + candidates.size +
                        "). NOT falling back to the positional rule on purpose: the view tree contains " +
                        "a measurement-broken node (right ~= 16777090) that would win it and report a " +
                        "successful fill while the real chat box stays empty, and a plain EditText walk " +
                        "also finds the search box. The clipboard fallback keeps the text instead.",
                )
                return null
            }
        }
    }

    /**
     * True when this EditText contains no EditText descendant.
     *
     * The real chat field is a leaf. A CONTAINER that also happens to be an EditText is not the field
     * the user types in, and choosing it would place text where nothing reads it -- a silent no-op that
     * looks like success.
     */
    private fun isLeafEditText(v: View): Boolean {
        if (v !is ViewGroup) return true
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child is EditText) return false
            if (child is ViewGroup && containsEditText(child, 0)) return false
        }
        return true
    }

    /** Bounded depth-first search for any EditText below [v]. */
    private fun containsEditText(v: ViewGroup, depth: Int): Boolean {
        if (depth > 10) return false
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child is EditText) return true
            if (child is ViewGroup && containsEditText(child, depth + 1)) return true
        }
        return false
    }

    /**
     * v6.0.0: Soul's chat input box, picked by CLASS rather than by position.
     *
     * WHY THIS EXISTS AT ALL: `pick()` takes the lowest visible EditText in the window, which is the
     * right guess for a bottom input bar and the wrong one the moment a bottom sheet, a search box or
     * a dialog field is also on screen -- Soul has all three (see `SoulInput`'s header). Soul's chat
     * box is a known, unobfuscated class, so the guess can be replaced by a fact.
     *
     * The DECISION lives in [SoulInput.choose], which is pure logic and unit-tested; this function
     * only adapts real views to it. Every rejection is logged by [SoulInput.choose] itself, and the
     * outcome is logged here, so a run in Soul always says what happened.
     */
    private fun pickSoul(list: List<EditText>): EditText? {
        val candidates = ArrayList<SoulInput.Candidate>(list.size)
        for (c in list) {
            val loc = IntArray(2)
            try {
                c.getLocationOnScreen(loc)
            } catch (_: Throwable) {
                // A detached view cannot report a location; (0,0) keeps it comparable.
            }
            candidates.add(
                SoulInput.Candidate(
                    className = c.javaClass.name,
                    match = SoulInput.matchOf(c.javaClass),
                    visible = c.visibility == View.VISIBLE,
                    ownUi = c.tag == com.luoliaoo.wechat.core.Constants.TAG_OWN_UI,
                    width = c.width,
                    height = c.height,
                    bottom = loc[1] + c.height,
                ),
            )
        }
        when (val choice = SoulInput.choose(candidates)) {
            is SoulInput.Choice.Found -> {
                val v = list[choice.index]
                Log.i(
                    TAG,
                    "edit: SOUL chat input = " + v.javaClass.name +
                        " (match=" + choice.match +
                        " w=" + v.width + " h=" + v.height + ")",
                )
                return v
            }
            is SoulInput.Choice.None -> {
                Log.w(
                    TAG,
                    "edit: SOUL found no chat input box (reason=" + choice.reason +
                        " considered=" + choice.considered + " of " + candidates.size + ")",
                )
                return null
            }
        }
    }

    /**
     * Picks the most likely chat input box:
     *   * must be visible and attached
     *   * must NOT be inside our own card (the tapped view's ancestor chain)
     *   * must NOT be one of our follow-up fields (checked by tag)
     *   * prefers the lowest on screen, which is where WeChat puts its input bar
     */
    /**
     * item 4: chooses the chat input box.
     *
     * The log showed the search FINDING the right field and this function DISCARDING it:
     *
     *     candidate MMEditText w=841 h=127 visible=true      <- WeChat's chat input
     *     edit: no usable input box
     *
     * `isShown` was the culprit: it returns false while our own panel covers the screen, so the only
     * requirement that mattered rejected the only correct candidate.
     *
     * The test is now what actually distinguishes a usable input box:
     *   * visibility VISIBLE
     *   * a REAL SIZE (width > 0 AND height > 0) -- the throwaway 0x0 field is still rejected
     *   * not one of our own fields
     * and the LOWEST such field wins, which is where WeChat keeps its chat bar.
     */
    private fun pick(list: List<EditText>): EditText? {
        var best: EditText? = null
        var bestBottom = -1
        for (c in list) {
            if (c.visibility != View.VISIBLE) continue
            if (c.tag == com.luoliaoo.wechat.core.Constants.TAG_OWN_UI) continue
            if (c.width <= 0 || c.height <= 0) {
                Log.i(TAG, "  rejected (no size): " + c.javaClass.simpleName +
                    " " + c.width + "x" + c.height)
                continue
            }
            val loc = IntArray(2)
            c.getLocationOnScreen(loc)
            val bottom = loc[1] + c.height
            if (bottom > bestBottom) {
                bestBottom = bottom
                best = c
            }
        }
        best?.let {
            Log.i(TAG, "edit: chose " + it.javaClass.simpleName + " " + it.width + "x" + it.height)
        }
        return best
    }

    /** Collects EditTexts in the tree, skipping any subtree that is our own card. */
    private fun collectEditTexts(v: View, out: MutableList<EditText>, depth: Int, from: View) {
        if (depth > 30) return
        if (v is EditText) {
            out.add(v)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                // Never descend into our own card: its follow-up field must not be chosen.
                if (child is AnalysisCard) continue
                if (child.tag == com.luoliaoo.wechat.core.Constants.TAG_OWN_UI) continue
                collectEditTexts(child, out, depth + 1, from)
            }
        }
    }

    /**
     * item 1: the Activity of the view's CURRENT window, when the context chain is stale.
     *
     * `from.getRootView().getContext()` reflects where the view is attached NOW, which behaves correctly
     * even when the view was created in another window (a restored card).
     */
    private fun activityFromAttachedView(from: View): Activity? {
        return try {
            val root = from.rootView ?: return null
            var c: Context? = root.context
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** item 1: ActivityThread.currentActivityThread().getTopActivity(), reflectively. */
    private fun currentActivityViaActivityThread(): Activity? {
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val thread = cls.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            for (name in arrayOf("getTopActivity", "currentActivity", "getResumedActivity")) {
                try {
                    val m = cls.getDeclaredMethod(name, *emptyArray<Class<*>>())
                    m.isAccessible = true
                    val a = m.invoke(thread)
                    if (a is Activity) return a
                } catch (_: Throwable) {
                    // try the next accessor
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * item 3: the guaranteed fallback, used by EVERY failure path.
     *
     * `copyToClipboard` plus the user-facing message, so no branch can return without giving the user
     * something usable. Previously two early returns skipped it, which is why "未找到聊天输入框" appeared
     * with no clipboard copy.
     */
    private fun fallbackCopy(ctx: Context, text: String) {
        copyToClipboard(ctx, text)
        try {
            Toast.makeText(
                ctx,
                com.luoliaoo.wechat.core.HostApp.say(
                    "由于微信重启此卡片编辑失效，已为你复制至剪贴板",
                ),
                Toast.LENGTH_LONG,
            ).show()
        } catch (_: Throwable) {
            // a missing toast must not undo the copy
        }
    }

    /**
     * item 2: puts the text on the clipboard.
     *
     * The fallback of choice when no input box can be reached: it needs no view tree, no Activity and no
     * focus, so it works in every situation the injection cannot handle.
     */
    private fun copyToClipboard(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("luoliao", text))
            Log.i(TAG, "edit: copied " + text.length + " chars to the clipboard")
        } catch (e: Throwable) {
            Log.w(TAG, "edit: clipboard copy failed: " + e.message)
        }
    }

    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
