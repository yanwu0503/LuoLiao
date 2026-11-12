package com.luoliaoo.wechat.ui

import com.luoliaoo.wechat.core.Log

/**
 * v6.0.0: EVERYTHING Soul-specific about reading and writing Soul's chat input box.
 *
 * WHY A SIBLING FILE AND NOT A BRANCH INSIDE InputInjector
 * `InputInjector` is the path the WeChat and Douyin builds already depend on, and its resolution
 * order (ActivityThread's top Activity, then the LOWEST sized visible EditText) is calibrated on
 * WeChat's layout. Sprinkling `if (isSoul)` through it would make every future input bug a
 * three-host bug. Soul's knowledge therefore lives here, and `InputInjector` consults this object
 * ONLY when `HostApp.current == SOUL`, so the WeChat/Douyin path stays byte-for-byte what it was.
 *
 * HOW EVERY NAME BELOW WAS CONFIRMED -- this is static recon and there is NO device attached, so a
 * claim without a quoted byte range would be a guess:
 *
 *   APK: E:\...\Soul_6.37.0.apk   package cn.soulapp.android   versionName 6.37.0
 *        (manifest parsed with _tools/axml.py; the 17 dex files extracted to _tools/souldex/)
 *
 *   1. CLASS HIERARCHY, read from the class_defs with `_tools/soul_xref.py
 *      classes "chat\.view\..*EditText"` and `fields`:
 *          cn.soulapp.android.component.chat.view.ConversationPasteEditText   [classes12.dex]
 *            extends cn.soulapp.android.square.view.PasteEditText            [classes17.dex]
 *            extends cn.soulapp.android.view.CaptureTouchEditText            [classes17.dex]
 *            extends androidx.appcompat.widget.AppCompatEditText             [classes.dex]
 *            extends android.widget.EditText                                 (framework)
 *      So it IS an `android.widget.EditText`, which is why `InputInjector`'s tree walk can find it.
 *
 *   2. IT IS ALSO THE CHAT INPUT, not some other field. Its layout is named in the APK's binary
 *      resources: scanning all 13528 `.xml` entries for the class descriptor hits exactly
 *          res/3Ph.xml  and  res/4qg.xml
 *      and both declare one
 *          <cn.soulapp.android.component.chat.view.ConversationPasteEditText
 *              id=@ref/0x7f090e20 maxLines=4 layout_weight=0x3f800000 hint=@ref/0x7f110ffe />
 *      inside a ConstraintLayout whose siblings are the message
 *      `androidx.recyclerview.widget.RecyclerView` and a row of
 *      `cn.soulapp.android.view.CaptureTouchImageView`s, i.e. the chat screen's bottom input bar.
 *      (The id NUMERIC value is quoted above but deliberately NOT keyed on: the two layouts are
 *      obfuscated file names, and a resource id is a number no runtime code can look up by name
 *      here. The class name is the durable handle, exactly as on Douyin.)
 *
 *   3. A PLAIN setText() REACHES SOUL'S OWN STATE -- it is NOT a rich/Spannable model needing the
 *      app's own writer. Evidence, method by method:
 *
 *      (a) NO `setText` / `getText` / `append` / `setFilters` override exists anywhere in the chain.
 *          Every class of the chain was checked for those names:
 *              ConversationPasteEditText -> getCurrentTextLength, onCreateInputConnection only
 *              PasteEditText             -> getPaste, extendSelection, onTextChanged,
 *                                           onTextContextMenuItem, selectAll, setSelection(I),
 *                                           setSelection(II), setSelected
 *              CaptureTouchEditText      -> onTouchEvent, a(Context, AttributeSet) only
 *              AppCompatEditText         -> getText (a plain delegate), background-tint plumbing
 *          The text therefore lives in the standard EditText/Editable, and `setText` is the real
 *          setter.
 *
 *      (b) `PasteEditText.onTextChanged(CharSequence, int, int, int)` [classes17.dex] is clipboard
 *          bookkeeping and nothing else. Disassembled with
 *          `_tools/soul_dis_reg.py "square\.view\.PasteEditText$" "^onTextChanged$"`:
 *              [007c] invoke-super android.widget.EditText->onTextChanged(CharSequence,III)V
 *              [0082] iget-object  v11, v9, PasteEditText.c:CharSequence
 *              [0086] if-nez v11, -> 0x008c
 *              [008a] return-void                <- field c is null for us: returns here
 *              [008c] ..toString()
 *              [00a0] invoke-static java.util.Objects->equals(Ljava/lang/Object;Ljava/lang/Object;)Z
 *              [00ac] iput-boolean v8(=0), PasteEditText.b:Z
 *              [00b2] iput-object  v10(=null), PasteEditText.c:CharSequence
 *          It reads NO app state and writes only its own two scratch fields (b, c), which arm and
 *          consume the clipboard "paste" shortcut. A programmatic setText cannot desynchronise
 *          anything: onTextChanged fires, the super runs, and the method returns immediately
 *          because `c` is null. There is no private text model that could miss the write.
 *
 *      (c) Soul's own send button is driven by a TextWatcher that reads the EDITABLE itself, so it
 *          sees a programmatic write. `cn.soulapp.android.component.chat.fragment.MsgFragment$l`
 *          [classes12.dex] implements android.text.TextWatcher; its afterTextChanged(Editable) does
 *          `s.toString()` -> `android.text.TextUtils.isEmpty(CharSequence)` ->
 *          `MsgFragment.h0(MsgFragment)Z` and `MsgFragment.n0(MsgFragment, String)V` (the draft).
 *          `n0` forwards to `MsgFragment.h2(String)`, the "search chat users" debounce. Nothing
 *          reads a private copy of the typed text.
 *
 *      (d) `ConversationPasteEditText.onCreateInputConnection` wraps the IME connection in
 *          `ConversationPasteEditText$a extends android.view.inputmethod.InputConnectionWrapper`.
 *          That inner class declares ONE member -- `<init>(ConversationPasteEditText,
 *          InputConnection, boolean)` -- and overrides NO method, so it cannot reject or rewrite
 *          programmatically inserted text.
 *
 *      CONCLUSION: no RichEdit workaround is needed for Soul, and this file deliberately does not
 *      invent one. What it DOES do is PROVE the write landed, because a failure must never be
 *      silent: [verify] re-reads and classifies, and the caller logs the verdict and falls back.
 *
 *   4. THE ONE REAL RISK IS *WHICH* EditText IS PICKED. Soul's chat screen is not the process's only
 *      EditText: the APK also defines a search-box watcher
 *      (`ChatSearchFragment$b`), miniprogram input
 *      (`cn.soulapp.android.miniprogram.utils.input.MentionEditText`), voice-party input
 *      (`cn.soulapp.cpnt_voiceparty.widget.MentionEditText`), emoji input
 *      (`com.vanniktech.emoji.EmojiEditText`), SRN fields
 *      (`com.soul.srn.common.component.input.SRNEditText`) and dialog bars such as
 *      (`QuotaInputBarController$a`). `InputInjector.pick()` takes the LOWEST visible field, which
 *      is right for a bottom bar but would equally take a bottom sheet's field. An exact
 *      class-name match removes that guesswork -- that is the entire reason for this file.
 *
 * NOTE: comments are ASCII-only in this file, per the project rule in `core/Log.kt`.
 */
object SoulInput {

    private const val TAG = "LuoLiao"

    /**
     * THE chat input box. VERIFIED (see the hierarchy and the layout in the header comment), so it
     * may be keyed on directly. The name is not obfuscated: Soul obfuscates much of its own code
     * (`a`, `b`, `LJII`-style members abound), but this class keeps its package and name -- the
     * same situation as Douyin's `im.sdk.chat.view.RichEditText`.
     */
    const val CHAT_INPUT_LEAF = "cn.soulapp.android.component.chat.view.ConversationPasteEditText"

    /**
     * Its direct superclass, matched as a SECOND chance.
     *
     * WHY BOTH AND NOT ONLY THE LEAF: the leaf adds only `getCurrentTextLength` and an IME wrapper,
     * so a Soul update could plausibly merge it away; the parent still identifies the same widget
     * family. `PasteEditText` is ALSO the parent of Soul's square/publish fields, so a bare parent
     * match is weaker evidence -- [choose] therefore prefers a leaf match over a parent match even
     * when the parent match sits lower on screen, and reports which one it used.
     */
    const val CHAT_INPUT_PARENT = "cn.soulapp.android.square.view.PasteEditText"

    /** Both names, leaf first, so a caller can report exactly what it looked for. */
    val CHAT_INPUT_CLASSES: List<String> = listOf(CHAT_INPUT_LEAF, CHAT_INPUT_PARENT)

    /** How far up a class hierarchy a name match is attempted. */
    private const val MAX_SUPER_DEPTH = 8

    /** How strongly a class is identified as Soul's chat input. */
    enum class Match {
        /** Not a known chat-input class. */
        NONE,

        /** The class, or an ancestor, IS the exact chat-input class Soul ships. */
        LEAF,

        /** Only the WEAKER parent is in the chain: probably the chat box, could be another field. */
        PARENT,
    }

    /** True when [name] is exactly one of the known chat-input implementation classes. */
    fun isChatInputName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        for (k in CHAT_INPUT_CLASSES) {
            if (k == name) return true
        }
        return false
    }

    /**
     * Classifies a SUPERCLASS CHAIN (the class itself first, then its superclasses).
     *
     * Takes plain strings rather than a `Class` so the decision is pure logic and can be pinned by a
     * JVM unit test with no Android runtime -- and "which class did it match" is exactly the
     * question that cannot be answered from this machine any other way, because no device is
     * attached. [chainOf] builds the list from a real Class.
     *
     * LEAF wins over PARENT whenever both are present, which is the normal case:
     * ConversationPasteEditText's chain contains PasteEditText too.
     */
    fun classifyChain(chain: List<String>): Match {
        var d = 0
        var parent = false
        for (n in chain) {
            if (d >= MAX_SUPER_DEPTH) break
            if (n == CHAT_INPUT_LEAF) return Match.LEAF
            if (n == CHAT_INPUT_PARENT) parent = true
            d++
        }
        return if (parent) Match.PARENT else Match.NONE
    }

    /**
     * The superclass chain of [cls] as names, class itself first. Null-safe and depth-bounded, so a
     * synthetic class or an odd loader cannot spin.
     */
    fun chainOf(cls: Class<*>?): List<String> {
        val out = ArrayList<String>(8)
        var c: Class<*>? = cls
        var d = 0
        while (c != null && d < MAX_SUPER_DEPTH) {
            out.add(c.name)
            c = c.superclass
            d++
        }
        return out
    }

    /** Convenience for a real class: its classification. */
    fun matchOf(cls: Class<*>?): Match = classifyChain(chainOf(cls))

    /**
     * One EditText offered to [choose], reduced to the fields the decision needs.
     *
     * WHY A PLAIN DATA CARRIER: the Android-facing caller builds these from real views, while the
     * DECISION stays pure Kotlin, so the selection rule (which field is the chat box) is testable
     * here on this machine.
     */
    data class Candidate(
        val className: String,
        val match: Match,
        /** android.view.View.VISIBLE. */
        val visible: Boolean,
        /** True when the view carries Constants.TAG_OWN_UI -- one of OUR own fields. */
        val ownUi: Boolean,
        val width: Int,
        val height: Int,
        /** Screen-space bottom edge; used only to pick the lowest among equally strong matches. */
        val bottom: Int,
    )

    /** What [choose] decided, WITH the reason, so the caller can log a line proving it ran. */
    sealed class Choice {
        /** @param index index into the list passed to [choose]. */
        data class Found(val index: Int, val match: Match) : Choice()

        /** @param reason short ASCII token naming the rule that rejected everything. */
        data class None(val reason: String, val considered: Int) : Choice()
    }

    /**
     * THE decision: which candidate is Soul's chat input box.
     *
     * Rules, in order -- each exists because a plausible alternative picks the wrong field:
     *   1. the class must be a known chat-input class (LEAF preferred): this is the whole point of
     *      the Soul path, and what the generic "lowest EditText" rule cannot do;
     *   2. VISIBLE with a REAL SIZE, so the throwaway 0x0 field cannot win;
     *   3. NOT one of our own fields (TAG_OWN_UI), which would make the module write into itself;
     *   4. among the survivors the LOWEST on screen wins -- and a LEAF match beats a PARENT match
     *      even if the parent match sits lower, because the exact class Soul ships is stronger
     *      evidence than position.
     *
     * Returns [Choice.None] with a reason instead of null-and-silence: an early exit with no log is
     * indistinguishable from code that never ran (see the project's rules), so the caller logs it.
     */
    fun choose(candidates: List<Candidate>): Choice {
        if (candidates.isEmpty()) return Choice.None("no-candidates", 0)

        var bestLeaf = -1
        var bestLeafBottom = Int.MIN_VALUE
        var bestParent = -1
        var bestParentBottom = Int.MIN_VALUE
        var considered = 0

        for (i in candidates.indices) {
            val c = candidates[i]
            if (c.match == Match.NONE) continue

            considered++
            if (!c.visible) {
                Log.i(TAG, "soulinput: rejected (not VISIBLE) " + c.className)
                continue
            }
            if (c.ownUi) {
                Log.i(TAG, "soulinput: rejected (our own UI) " + c.className)
                continue
            }
            if (c.width <= 0 || c.height <= 0) {
                Log.i(
                    TAG,
                    "soulinput: rejected (no size) " + c.className + " " + c.width + "x" + c.height,
                )
                continue
            }
            if (c.match == Match.LEAF) {
                if (c.bottom > bestLeafBottom) {
                    bestLeafBottom = c.bottom
                    bestLeaf = i
                }
            } else if (c.bottom > bestParentBottom) {
                bestParentBottom = c.bottom
                bestParent = i
            }
        }

        if (bestLeaf >= 0) return Choice.Found(bestLeaf, Match.LEAF)
        if (bestParent >= 0) return Choice.Found(bestParent, Match.PARENT)
        return Choice.None(if (considered == 0) "no-soul-input-class" else "all-rejected", considered)
    }

    /**
     * The result of re-reading the field after a write: did the value actually stick?
     *
     * @param ok           true when the field holds the requested text
     * @param reason       short ASCII token; the caller logs it, so a failure is never silent
     */
    data class Verdict(val ok: Boolean, val reason: String, val requestedLen: Int, val landedLen: Int) {

        /** One ASCII log line, used verbatim by the caller so the two can never disagree. */
        fun line(): String = buildString {
            append("ok=").append(ok)
            append(" reason=").append(reason)
            append(" requestedLen=").append(requestedLen)
            append(" landedLen=").append(landedLen)
        }
    }

    /**
     * THE verification: compares what we asked for with what the field actually reports.
     *
     * WHY AN EXACT COMPARISON AND NOT JUST "IS IT EMPTY": `InputInjector.fill` already rejects a
     * blank field, which catches "nothing landed at all". It cannot catch the other real failure --
     * a PARTIAL or REWRITTEN value (an input filter, a maxLength, an IME committing something else).
     * That case used to report success and show the user a success toast over a half-written
     * message, which is the failure this function exists to name.
     *
     * Pure string logic on purpose: this is the half of the Soul path that CAN be tested here.
     */
    fun verify(requested: String, landed: String): Verdict {
        if (requested == landed) return Verdict(true, "exact", requested.length, landed.length)
        if (requested.isNotBlank() && landed.isBlank()) {
            return Verdict(false, "blank", requested.length, landed.length)
        }
        if (requested.isEmpty() && landed.isNotEmpty()) {
            return Verdict(false, "unexpected-text", 0, landed.length)
        }
        // A surrounding-whitespace difference still means the value IS in the box; only the padding
        // differs. Reported as a success, but with its own reason so the log does not claim "exact".
        if (requested.trim() == landed.trim()) {
            return Verdict(true, "surrounding-whitespace-differs", requested.length, landed.length)
        }
        if (landed.length < requested.length && requested.startsWith(landed)) {
            return Verdict(false, "truncated", requested.length, landed.length)
        }
        if (landed.length > requested.length && landed.startsWith(requested)) {
            return Verdict(false, "extra-text-appended", requested.length, landed.length)
        }
        return Verdict(false, "differs", requested.length, landed.length)
    }
}
