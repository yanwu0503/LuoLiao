package com.kwai.imsdk.msg

/**
 * A Kuaishou message that DOES declare both `getText()` and `getSummary()`, as a SUBCLASS of the
 * `KwaiMsg` fixture.
 *
 * ============================== WHY A SUBCLASS, NOT A STANDALONE CLASS ========================
 *
 * `KuaishouMsgAccess.isMessage` accepts a message by an EXACT NAME match when the host loader cannot
 * resolve the real class (which is the case in a JVM unit test). A standalone class named anything else
 * is therefore REJECTED before any getter logic runs, and `content()` returns "" -- a first attempt at
 * this file did exactly that and both of its tests failed with "expected <the real body> but was <>".
 * The failure was the test doing its job; the fix is to inherit the accepted name.
 *
 * ============================== WHY THE FIELD IS CLEARED ======================================
 *
 * The parent fixture carries a non-empty `text` FIELD ("kuaishou-body") because the field-first route
 * needs it. That route is checked BEFORE any getter, so this class sets `text = ""` in its constructor:
 * otherwise the field would answer first and the getter preference under test would never be reached.
 *
 * ============================== THE SHAPES THAT MATTER ========================================
 *
 *   1. `TEXT_ONLY`        getText() answers          -> the text must win
 *   2. `BOTH_EMPTY_TEXT`  getText() = "" , getSummary() = "[Photo]"
 *                                                    -> ★ the KImageMsg shape: the localized
 *                                                       placeholder must NOT be stored as the body
 *
 * The "summary is the only source" case needs its OWN class ([KwaiMsgSummaryOnly]) because a Kotlin
 * class cannot conditionally declare a method: `getText` would still be visible to reflection even if
 * it only ever returned null, and the guard under test keys on whether the method EXISTS. Putting both
 * cases in one class would have made the guard's own branch unreachable and the test meaningless.
 *
 * ============================== WHAT IT CANNOT PROVE ==========================================
 *
 * This is a SHAPE stand-in. It proves which getter the reader PREFERS; it does not prove what the real
 * `KImageMsg` returns at runtime -- that is F3 in KS_BUILD_PLAN.md and is device-only. Stated so nobody
 * mistakes it for device evidence.
 *
 * Comments ASCII-only (see core/Log.kt). Test sources only; never ships in the APK.
 */
@Suppress("unused")
class KwaiMsgBothGetters(shape: Shape) : KwaiMsg() {

    /** Which shape this instance imitates. */
    enum class Shape { TEXT_ONLY, BOTH_EMPTY_TEXT }

    private val shape: Shape = shape

    init {
        // Clear the inherited field so the FIELD route cannot answer before the getter route: the
        // preference between getText and getSummary is the entire subject of this fixture.
        this.text = ""
    }

    /** Present on BOTH shapes, mirroring the host, where `getText` is inherited by every subclass. */
    fun getText(): String? = when (shape) {
        Shape.TEXT_ONLY -> "the real body"
        // A non-text message: the getter exists but yields nothing. This is the KImageMsg shape.
        Shape.BOTH_EMPTY_TEXT -> ""
    }

    /** For BOTH_EMPTY_TEXT this is the localized display string; for TEXT_ONLY it mirrors getText. */
    fun getSummary(): String? = when (shape) {
        Shape.TEXT_ONLY -> "the real body"
        Shape.BOTH_EMPTY_TEXT -> "[Photo]"
    }
}

/**
 * A Kuaishou message that declares ONLY `getSummary()` -- no `getText()` anywhere in its hierarchy.
 *
 * This keeps the envelope's last-resort summary branch REACHABLE, so that a future Kuaishou subclass
 * carrying its display text nowhere else is not silently dropped. It is a separate class rather than a
 * mode of [KwaiMsgBothGetters] precisely because the guard under test asks "does a getText method
 * EXIST", which a single class cannot vary per instance.
 *
 * Comments ASCII-only (see core/Log.kt). Test sources only; never ships in the APK.
 */
@Suppress("unused")
class KwaiMsgSummaryOnly : KwaiMsg() {

    init {
        // Same reason as above: the inherited field must not answer before the getter under test.
        this.text = ""
    }

    /** No `getText()` is declared here, on purpose. */
    fun getSummary(): String? = "summary-only body"
}
