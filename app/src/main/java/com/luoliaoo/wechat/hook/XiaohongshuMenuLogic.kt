package com.luoliaoo.wechat.hook

import java.lang.reflect.Method

/**
 * The pure predicates that decide "is this the menu's row-bind method" on Xiaohongshu.
 *
 * ============================== WHY THE RULE LIVES IN ITS OWN FILE ==============================
 *
 * The rule is extracted from [XiaohongshuHooks] so a plain JVM unit test can pin it. On a device the
 * only way to learn that the rule is wrong is to long-press a message in Xiaohongshu and watch the log
 * -- a slow loop that needs the user. A JVM test cannot prove the rule matches the REAL method (that
 * needs the APK), but it does prove the rule rejects the shapes it must reject, which is where a
 * too-permissive predicate does its damage: it silently hooks a neighbouring helper, the feature
 * appears not to work, and nothing in the log says why.
 *
 * The same arrangement exists for QQ (`QQHistoryLogic`) and for the same reason.
 */
internal object XiaohongshuMenuLogic {

    /**
     * The minimum number of parameters the row-bind method has.
     *
     * The measured signature is
     * `m4(int, sk4/e, String, MenuView, MenuItemView, sk4/p3, View, rzb/j0)` -- EIGHT parameters.
     * The only reason this constant is not 8 is that a release could legitimately drop a trailing
     * argument; the SHAPE test below carries the real discriminating power, so the count only has to
     * exclude a trivial one-argument accessor.
     */
    const val MIN_PARAMS = 2

    /**
     * Picks the row-bind method among the candidates whose shape matches.
     *
     * ============================== WHY MORE THAN ONE CANDIDATE EXISTS ==============================
     *
     * A DEVICE LOG PROVED THE ORIGINAL ASSUMPTION WRONG. The first version of this rule took the
     * FIRST declared method whose parameters included `MenuItemView`, and its comment claimed that
     * `[DEX]` showed "EXACTLY ONE of MenuView's 43 methods takes MenuItemView". The shipped build
     * disproves that in one line of the module's own log:
     *
     *     xhs: menu row bind = com.xingin.im.v2.message.views.MenuView#Y4 params=3
     *
     * TWO methods take a row view -- the measured 8-argument `m4`, and a 3-argument `Y4`. A
     * `firstOrNull` therefore selected whichever the VM happened to report first, and on this build
     * that was `Y4`, which is not the bind at all. The feature then behaved as "the hook installed but
     * the row was never added", which reads as a silent failure rather than an error.
     *
     * ============================== THE DISCRIMINATOR ==============================
     *
     * The BIND takes the most arguments, and that is structural rather than a guess: a bind method is
     * handed its holder, its position, its payload, its data model, its parent view and its callbacks,
     * because it has to populate a whole row. A click/callback helper is handed the row and an index.
     * So among shape-matching candidates the one with the MOST parameters is the bind.
     *
     * WHY THE TIE-BREAK IS STILL DETERMINISTIC: candidates are compared by parameter count and then by
     * name, so the same build always resolves to the same method -- a rule that flipped between runs
     * would make a bug impossible to reproduce.
     *
     * @param candidates the methods whose shape matched, in any order.
     * @return the bind method, or null when nothing matched.
     */
    fun pickRowBindMethod(candidates: List<Method>): Method? =
        candidates.maxWithOrNull(
            compareBy({ it.parameterCount }, { it.name }),
        )

    /**
     * True when a method with these parameter types is a POSSIBLE row-bind method.
     *
     * ============================== THE RULE ==============================
     *
     * A method qualifies when AT LEAST ONE of its parameters is exactly [rowViewClass].
     *
     * WHY THAT IS ENOUGH TO NARROW, but NOT enough to choose: `[DEX]` reading of `MenuView`'s declared
     * methods shows more than one method receives a `MenuItemView` (measured: the 8-argument `m4` and
     * the 3-argument `Y4`). So this test selects the CANDIDATE SET, and [pickRowBindMethod] makes the
     * final choice. Treating this test as the whole rule is the defect the device log exposed.
     *
     * WHY IT IS A SHAPE AND NOT THE NAME `m4`: `m4` is a name a Xiaohongshu release can change, and
     * that is not hypothetical -- it is precisely what happened to QQ, whose render method is `A` on
     * 9.3.65 and `G`/`H`/`n` on 9.1.97. A name-based rule would have to be re-measured on every
     * release; this one keeps working because "the method that receives a row view" is what the method
     * IS, not what it is called.
     *
     * WHY EXACT CLASS EQUALITY AND NOT `isAssignableFrom`: unlike QQ -- where the binder's argument is
     * a SUBCLASS of the message base and the check had to be `isAssignableFrom` -- here the parameter
     * type IS `MenuItemView` verbatim in the measured signature. An assignability test would also
     * accept a method taking some parent of `MenuItemView`, which could well be a layout algorithm
     * rather than the row bind.
     */
    fun matchesRowBindShape(paramTypes: List<Class<*>>, rowViewClass: Class<*>): Boolean {
        if (paramTypes.size < MIN_PARAMS) return false
        return paramTypes.any { it == rowViewClass }
    }

    /**
     * The same rule, over a [Method], for call sites that hold one.
     *
     * Kept as a separate entry point rather than making callers build a parameter-type list, so the
     * device path and the test path cannot drift into two slightly different rules.
     */
    fun matchesRowBindShape(m: Method, rowViewClass: Class<*>): Boolean =
        matchesRowBindShape(m.parameterTypes.toList(), rowViewClass)

    /**
     * The first argument whose RUNTIME class is exactly [type], or null.
     *
     * ============================== WHY A RUNTIME TEST AND NOT THE SIGNATURE ==============================
     *
     * The measured signature already tells us the POSITIONS -- arg[3] is `MenuView`, arg[4] is
     * `MenuItemView`. So why not just read `args[3]` and `args[4]`?
     *
     * Because the positions come from ONE measured build, and a positional read breaks SILENTLY when a
     * release reorders or inserts an argument: `args[3]` would simply be some other object, the cast
     * would fail, and the feature would disappear with no error that mentions the real cause. Matching
     * on what the arguments ARE is stable across that change, and it is exactly as precise here,
     * because a menu-building call holds one menu and one row.
     *
     * WHY EXACT CLASS AND NOT `isInstance`: `isInstance` would also accept a SUBCLASS, and the host's
     * own `MenuView`-derived types are not the container we want to insert into. The measured class is
     * the concrete one, so equality is both correct and tighter.
     *
     * WHY null WHEN [type] IS null: the caller may not have been able to load the class (a different
     * build, a rename). Returning null makes the caller report "the class was not loadable" instead of
     * indexing an argument list on a guess.
     */
    fun pickArgOfType(args: Array<Any?>, type: Class<*>?): Any? {
        if (type == null) return null
        for (a in args) {
            if (a != null && a.javaClass == type) return a
        }
        return null
    }

    /**
     * Chooses the row our entry is inserted BESIDE.
     *
     * ============================== THE ORDER, AND WHY EACH STEP IS THERE ==============================
     *
     * 1. [boundRow] when its label is [anchorLabel]. This is the row the host is binding right now,
     *    read from the arguments rather than guessed from the tree, so it is the strongest available
     *    evidence and it needs no search at all.
     *
     * 2. [findLabelled], the tree search. This is what makes the entry land beside 转发 when the host
     *    binds some OTHER row first (the binds arrive in an unspecified order, so step 1 misses on
     *    most of them). The search is the slower route, which is why it is second.
     *
     * 3. [boundRow] AGAIN, whatever its label. A menu whose labels we do not recognise (a new
     *    release's wording, or a menu type the user has not shown us) still gets the entry, because
     *    the host told us this is a row of this menu. Falling back to it keeps the feature working
     *    while degrading only the POSITION.
     *
     * THERE IS DELIBERATELY NO FINAL "ANY VIEW" FALLBACK, and that is a correctness requirement, not
     * caution -- removing the equivalent fallback is what fixed a CRASH:
     *
     *     NullPointerException: RecyclerView$ViewHolder.shouldIgnore() on a null object reference
     *         at RecyclerView.findMinMaxChildLayoutPositions
     *
     * An earlier version ended with "if nothing labelled was found, use whatever looked like a row".
     * That picked a container inside a RecyclerView, and inserting a View into a RecyclerView is fatal
     * -- its layout pass walks every child expecting a ViewHolder, finds none for ours, and
     * dereferences null. Xiaohongshu force-closed on every long-press.
     *
     * @param labelOf reads a row's label; injected so this stays a pure function a unit test can drive
     *   without Android views.
     * @return the chosen row, or null when there is nothing usable -- in which case the caller reports
     *   the reason and skips the entry. Refusing beats guessing: a missing entry disappoints the user,
     *   a crash stops them using the app.
     */
    fun <T> chooseInsertionAnchor(
        boundRow: T?,
        anchorLabel: String,
        hostLabels: List<String>,
        labelOf: (T) -> String,
        findLabelled: () -> T?,
    ): T? {
        if (boundRow != null && labelOf(boundRow) == anchorLabel) return boundRow
        findLabelled()?.let { if (labelOf(it) == anchorLabel) return it }
        // The tree search also accepts ANY host label, which is more forgiving than the anchor alone
        // -- a menu that renamed 转发 still has 复制 or 便利贴 to sit beside.
        findLabelled()?.let { found ->
            if (hostLabels.contains(labelOf(found))) return found
        }
        return boundRow
    }
}
