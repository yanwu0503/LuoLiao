package android.widget

/**
 * TEST FIXTURE -- a class deliberately placed in Android's OWN widget namespace.
 *
 * ============================== WHY IT MUST LIVE HERE ==============================
 *
 * `QQMenuSelection.looksLikeMessageStore` rejects a candidate message collection whose FIRST element's
 * class name starts with `android.view.` / `android.widget.` / `androidx.` / `android.graphics.` -- the
 * guard that stops the multi-select BAR's own fields (`mSelectText:TextView`, `mDeleteBtn:View`,
 * `mMultiButtonBar:LinearLayout`, ...) from being read as a batch of messages.
 *
 * To prove that rule works, the test needs an object whose class name REALLY begins with `android.widget.`.
 * A fixture in the test's own package would be accepted by the production rule and the test would pass
 * against a BROKEN revision too -- and a test that cannot fail is not a test. `NamespaceHelperFixture` in
 * `cn.soulapp.imlib.msg` exists for exactly the same reason.
 *
 * This class is NOT a View: it derives from nothing and carries nothing. It is only ever constructed by
 * `QQMenuSelectionTest`, it is never on the main source set, so it cannot reach a device build.
 *
 * Comments ASCII-only (see core/Log.kt).
 */
class TextView(val fakeLabel: String = "")
