package com.luoliaoo.wechat.core

import android.content.Context
import java.lang.ref.WeakReference

/**
 * Holds a Context captured from inside the WeChat process.
 *
 * WHY: the long-press menu injection hook receives (menu, msg, view) and, for the WeChat method that
 * actually fires, that `view` is null -- the log showed three "MENU ICON skipped: the anchor view has
 * no context" in a row. Building the icon needs a Context, so it is captured once, as soon as any
 * view is seen, and reused from then on.
 *
 * Only an application or activity context is stored (never a view), so no Activity is leaked.
 */
object AppContextHolder {

    @Volatile
    private var ref: WeakReference<Context>? = null

    /** Remembers a usable Context; ignores anything that cannot survive (e.g. a Theme wrapper). */
    fun remember(ctx: Context?) {
        if (ctx == null) return
        try {
            val app = ctx.applicationContext ?: return
            ref = WeakReference(app)
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** The most recently captured Context, or null when none was seen yet. */
    fun get(): Context? = ref?.get()

    // The module-Context holder was removed together with the lookup that fed it: obtaining our own
    // package's Context required framework reflection, and that is what once hung WeChat at startup.
    // The history store no longer needs it (it uses WeChat's external files dir).
}
