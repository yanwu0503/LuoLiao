package com.luoliaoo.wechat.hook

/**
 * Pure, unit-testable rules for reading QQ's multi-select state.
 *
 * ============================== CORRECTION: WHAT `MsgListVMWrapper#N()` REALLY RETURNS ==============================
 * An earlier revision of this file (and of QQHooks) claimed `N()` IS the selection, based on
 * `childvm.j#l` calling `N()` and then building `MultiSelectEvent$GetSelectMsgList` from the result.
 * THAT WAS WRONG, and it caused a user-visible bug: long-pressing ONE message analysed SEVEN.
 *
 * The disassembly shows the missing step. `childvm.j#l` does NOT trust `N()` as-is -- it FILTERS it,
 * element by element, through `AIOMsgItem#K2()Z`:
 *
 *     [0097] GetSelectMsgList#a()Ljava/util/List;
 *     [009e] CopyOnWriteArrayList#subList(II)
 *     [00b6] Iterator#next()
 *     [00bb] check-cast com.tencent.mobileqq.aio.msg.AIOMsgItem
 *     [00bd] AIOMsgItem#K2()Z          <-- THE PER-MESSAGE "is selected" FLAG
 *     [00c1] if-eq                     <-- keep only the entries where it is true
 *     [00c3] ArrayList#add(...)
 *
 * So `N()` is the MESSAGE LIST, and the selection is `N()` filtered by `K2()`.
 *
 * Two independent facts confirm it, and both are cheap to re-check:
 *   1. `impl.b#<init>` assigns `i = new CopyOnWriteArrayList()` -- an EMPTY list -- and the only other
 *      writer is `impl.b#c0`, which fills it from the repository on `MsgListRepoOpType.LoadPrePage`
 *      (`msglist.mvx.data.d#a(c)` -> `data.l#c()` -> `l.a : List`). That is a page of chat history, not
 *      a selection.
 *   2. `N()` has ~19 callers including `ScrollMsgListVM$scrollListener$2$a#onScrollStateChange`, which
 *      needs the message list to compute scroll geometry and has nothing to do with selection.
 *      `childvm.j#h` also reads `N()`, takes its LAST element and calls `K2()` on it -- meaningless for
 *      a selection, obvious for "the newest message in the list".
 *
 * `K2()` is called from ONLY multi-select-aware places (`MultiSelectMsgListChildVM$...#invokeSuspend`,
 * `NtAIOShareActionSheet#onItemClick`, `msgselect.msglist.c#A`, `childvm.j#h/i/l`), which is what
 * identifies it as the selection predicate rather than a general-purpose flag.
 *
 * ============================== WHY THIS FILE NO LONGER READS THE COUNT ==============================
 * `MultiSelectUIState$UpdateSelectCount` (field `d : I`) still exists and [countOf] still reads it, but
 * it is only a LOG CROSS-CHECK now. The batch path needs the MESSAGES, and the count cannot supply them.
 * The messages come from [selectedOnly] applied to the host's own list.
 *
 * ============================== THE SAFETY RULE THAT MATTERS MOST ==============================
 * If the selection predicate cannot be resolved at all (a QQ rename), [selectedOnly] returns EMPTY, and
 * an empty selection sends the caller down the SINGLE-message path. That direction is deliberate:
 * analysing the one message the user actually long-pressed is a small, visible, correct behaviour,
 * whereas treating the whole message list as "selected" pushes messages the user never chose into an AI
 * analysis -- a correctness AND privacy failure. When in doubt, analyse less.
 *
 * Values are deduplicated by IDENTITY, not equality: the same message object added twice (a double tap
 * on the checkbox) must not be analysed twice, while two genuinely identical consecutive messages are
 * two messages and dropping one would silently lose a line of chat. This mirrors
 * `KuaishouSelection.valuesOf`, and the reasoning is the same one recorded there.
 *
 * Comments ASCII-only; Chinese uses \uXXXX escapes (see core/Log.kt).
 */
internal object QQMenuSelection {

    /**
     * The per-message "is this message selected" predicate: `AIOMsgItem#K2()Z`.
     *
     * Public on the host class (`access_flags = 0x1`), zero-argument and boolean-returning, so it can be
     * invoked reflectively on any element of the host's list without touching a field letter. Resolved by
     * NAME because the whole point is that the host calls it, and a rename must degrade to "no selection
     * known" (see the safety rule above) rather than to a wrong answer.
     */
    const val SELECTED_FLAG_METHOD = "K2"

    /**
     * Narrows a host collection to the entries the host itself considers SELECTED.
     *
     * [isSelected] is supplied by the caller because the predicate is a host method and this object
     * deliberately contains no reflection (it is compiled and unit-tested on the JVM, without Android or
     * the host's classes on the classpath). Keeping the filter here means the RULE -- "an entry that
     * throws or fails the predicate is not selected" -- is pinned by a test rather than living in
     * untested hook code.
     *
     * An entry whose predicate THROWS is treated as not selected. A reflective call can fail per element
     * (an unexpected subclass, a hidden method), and one bad element must not abort the whole batch or,
     * worse, promote the entire list into the selection.
     */
    fun selectedOnly(items: List<Any>?, isSelected: (Any) -> Boolean): List<Any> {
        if (items.isNullOrEmpty()) return emptyList()
        // FILTER, and this is load-bearing -- do not "simplify" it back to `return items`.
        //
        // Returning the input unchanged is the ORIGINAL BUG: the caller passes the host's list, which
        // holds EVERY message in the conversation, so a single long-press looked like "everything is
        // selected" and every tap took the batch path. That is exactly the user's report -- one
        // long-press produced the read-only 分析已选消息 dialog instead of 分析范围.
        //
        // A mutation test temporarily replaced this line with `return items` to prove the tests catch
        // it; that stub was left behind in the production file and broke the whole build. The correct
        // body is this filter.
        return items.filter { item ->
            try {
                isSelected(item)
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * A selected count of `> 1` is what makes a tap the BATCH path.
     *
     * `> 1` rather than `>= 1`, deliberately: a single selected message is exactly the message the user
     * long-pressed, and the SINGLE-message path ("分析范围", where the user can still choose the scope)
     * is the right answer for it. Taking the batch path there would show a read-only "分析已选消息" dialog
     * instead, which is what the user reported as a bug: "why is the long-press button 分析已选消息 and not
     * 分析范围". So a selection of exactly one stays on the single path.
     *
     * The count is only meaningful because the caller has ALREADY narrowed the host's list to the truly
     * selected entries via [selectedOnly]. Before that correction this predicate saw the whole message
     * list and every long-press took the batch path.
     */
    fun isBatch(selected: List<Any>?): Boolean = (selected?.size ?: 0) > 1

    /**
     * Normalises whatever collection of selected messages a caller reached into a clean list.
     *
     * Accepts `null`, any [Collection], and any [Array] (including a `[Lcom/...AIOMsgItem;`), because the
     * multi-select store is reached reflectively and its concrete shape can differ between the bar
     * implementations (`MultiSelectBarVB` vs `AskQBaoMultiSelectBarVB`) -- the two hosts' bars do not
     * have to expose the same container type. Anything else yields an empty list rather than throwing:
     * a multi-select bar we cannot read is a feature that does not fire, never a crash in a hook.
     *
     * Null ELEMENTS are skipped, not replaced by a placeholder: a null entry is not a message, and
     * passing it into `describeMessages` would push it through the whole analysis pipeline.
     */
    fun valuesOf(source: Any?): List<Any> {
        val items: List<Any> = when (source) {
            null -> return emptyList()
            is List<*> -> source.filterNotNull()
            is Collection<*> -> source.filterNotNull()
            is Array<*> -> source.filterNotNull()
            else -> return emptyList()
        }
        if (items.isEmpty()) return emptyList()

        val out = ArrayList<Any>(items.size)
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )
        for (v in items) {
            if (!seen.add(v)) continue
            out.add(v)
        }
        return out
    }

    /**
     * Reads the host's authoritative selected COUNT from a `MultiSelectUIState$UpdateSelectCount`
     * instance, or null when [state] is not that state at all.
     *
     * The field is `d` (`I`) in 9.3.65. Located by TYPE and NAME rather than trusting the letter alone
     * would be ideal, but this class has exactly ONE instance field besides the qfix `$redirector_`,
     * so the reader also accepts "the single int field" when the name has changed -- a QQ rename must
     * not silently turn a count into "unknown" and make the cross-check log lie about it having run.
     */
    fun countOf(state: Any?): Int? {
        if (state == null) return null
        val cls = state.javaClass
        // Preferred: the recorded name.
        run {
            var p: Class<*>? = cls
            var depth = 0
            while (p != null && depth < 6) {
                for (f in p.declaredFields) {
                    if (f.name == COUNT_FIELD && f.type == Integer.TYPE) {
                        return try {
                            f.isAccessible = true
                            f.getInt(state)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                p = p.superclass
                depth++
            }
        }
        // Fallback: the only int field on the class (qfix's redirector is an object, not an int).
        return try {
            val ints = cls.declaredFields.filter { it.type == Integer.TYPE }
            if (ints.size != 1) return null
            val f = ints[0]
            f.isAccessible = true
            f.getInt(state)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A ONE-LINE description of the selection for the log, so a batch run states what it saw.
     *
     * Simple names only and capped: a 200-message selection would otherwise write a multi-kilobyte line
     * into a log that a human reads on a phone. Same policy as `KuaishouSelection.describeForLog`.
     */
    fun describeForLog(selected: List<Any>?, limit: Int = 6): String {
        if (selected.isNullOrEmpty()) return "<empty>"
        val names = selected.take(limit).joinToString(",") { it.javaClass.simpleName }
        return if (selected.size > limit) names + ",...(" + selected.size + " total)" else names
    }

    /** The int field that carries the selected count on `MultiSelectUIState$UpdateSelectCount`. */
    const val COUNT_FIELD = "d"

    /**
     * Class-name fragments that mark a container as NOT a message list.
     *
     * Used by [looksLikeMessageStore] to reject the multi-select bar's own view fields: the bar exposes
     * `mSelectText : TextView`, `mDeleteBtn : View`, `mMultiButtonBar : LinearLayout` and so on, and any
     * of those read as a "collection of things" if the caller only checks for `Collection`. Reading a
     * view list as messages would produce a batch of `TextView`s and an analysis of garbage.
     */
    private val VIEW_HINTS = listOf(
        "android.view.", "android.widget.", "androidx.", "android.graphics.",
    )

    /**
     * True when [candidate] plausibly holds MESSAGES: a non-empty collection whose first element is not
     * a View/Drawable.
     *
     * This is a heuristic, and it is deliberately biased the safe way: a false negative costs the batch
     * path (we fall back to the single long-pressed message, which the user can see happened), while a
     * false positive would analyse `TextView`s and put nonsense in front of the user.
     */
    fun looksLikeMessageStore(candidate: Any?): Boolean {
        val list = when (candidate) {
            is List<*> -> candidate
            is Collection<*> -> candidate.toList()
            else -> return false
        }
        if (list.isEmpty()) return false
        val first = list.firstOrNull() ?: return false
        val n = first.javaClass.name
        return VIEW_HINTS.none { n.startsWith(it) }
    }
}
