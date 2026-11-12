package com.luoliaoo.wechat.core

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Identity registry of views owned by our own UI.
 *
 * The menu hook detects its injected entry by watching TextView.setText for our title.
 * Our scope dialog used to label its confirm button with that same title, so the hook
 * hijacked our own button and replaced its click listener -- tapping "confirm" then
 * re-opened the dialog.
 *
 * Tagging a container is not reliable, because AlertDialog builds its buttons OUTSIDE
 * the view passed to setView (they are siblings in a separate button bar). Registering
 * the actual View instances avoids that entirely.
 */
object OwnUi {

    private val views = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun register(vararg candidates: View?) {
        for (v in candidates) {
            if (v != null) views.add(v)
        }
    }

    fun contains(v: View?): Boolean = v != null && views.contains(v)

    /**
     * While non-zero, the menu hook must not hijack anything: we are building or
     * showing our own dialog, and AlertDialog creates its buttons (calling setText on
     * them) during show() -- before they can be registered.
     */
    @Volatile
    private var suppressUntil = 0L

    fun suppressFor(millis: Long) {
        suppressUntil = System.currentTimeMillis() + millis
    }

    fun isSuppressed(): Boolean = System.currentTimeMillis() < suppressUntil
}
