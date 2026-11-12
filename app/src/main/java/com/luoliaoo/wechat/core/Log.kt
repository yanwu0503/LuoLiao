package com.luoliaoo.wechat.core

import com.luoliaoo.wechat.BuildConfig


/**
 * Unified log sink.
 *
 * WHY THIS CLASS EXISTS (hard lesson learned):
 * android.util.Log writes to logcat ONLY -- it does NOT appear in the LSPosed
 * exported log. Since the LSPosed log is the primary troubleshooting channel for
 * a module, using android.util.Log means effectively logging nothing.
 *
 * This is a drop-in stand-in for android.util.Log (same i/w/e/d methods and
 * INFO/WARN/ERROR constants). Every line goes to BOTH:
 *   1. Xposed log  -- visible in the LSPosed export (primary channel)
 *   2. logcat      -- convenient for live adb logcat inspection
 *
 * ============================== TWO BUILD FLAVOURS (v7.0.1) ==============================
 *
 * `BuildConfig.LOG_ENABLED` is `false` in release and `true` in debug, so:
 *
 *   release -> SILENT. Every method below returns immediately and -- because the flag is a compile-time
 *              constant -- R8 folds the guard, deletes the branch, and with it the message strings.
 *              The release APK does not merely skip logging, it does not CONTAIN the log text.
 *   debug   -> FULL. Every message reaches logcat and the module's own log file (see [DebugLog]).
 *
 * WHY A COMPILE-TIME FLAG AND NOT A SETTING: a runtime switch leaves every message in the dex as a
 * string, so "logging is off" would still be inspectable, and the cost of building each message is paid
 * on the HOST's main thread on every message the user reads. The flag has to be a `const` so R8 can
 * remove the call rather than just the output.
 *
 * CONSEQUENCE FOR EDITORS: do not replace `if (!LOG_ENABLED) return 0` with a runtime check, and do not
 * move the flag into a `var`. Either change would silently put the log text back into the release build,
 * and the only symptom would be a larger APK -- which is easy to miss.
 */
object Log {

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    /**
     * True only in the debug build.
     *
     * `const val` on purpose: it inlines to a literal at every use site, which is what lets R8 delete the
     * guarded branches (and the strings they carry) from the release build entirely.
     */
    private const val LOG_ENABLED = BuildConfig.LOG_ENABLED

    fun v(tag: String?, msg: String): Int = write(VERBOSE, tag, msg, null)
    fun d(tag: String?, msg: String): Int = write(DEBUG, tag, msg, null)
    fun i(tag: String?, msg: String): Int = write(INFO, tag, msg, null)
    fun w(tag: String?, msg: String): Int = write(WARN, tag, msg, null)
    fun e(tag: String?, msg: String): Int = write(ERROR, tag, msg, null)

    fun v(tag: String?, msg: String, tr: Throwable?): Int = write(VERBOSE, tag, msg, tr)
    fun d(tag: String?, msg: String, tr: Throwable?): Int = write(DEBUG, tag, msg, tr)
    fun i(tag: String?, msg: String, tr: Throwable?): Int = write(INFO, tag, msg, tr)
    fun w(tag: String?, msg: String, tr: Throwable?): Int = write(WARN, tag, msg, tr)
    fun e(tag: String?, msg: String, tr: Throwable?): Int = write(ERROR, tag, msg, tr)

    fun getStackTraceString(t: Throwable?): String =
        android.util.Log.getStackTraceString(t)

    /** True when this build writes logs. Used by the settings UI to be honest about the flavour. */
    fun isBuildLogging(): Boolean = LOG_ENABLED

    private fun write(priority: Int, tag: String?, msg: String, tr: Throwable?): Int {
        // First statement in the only writer, so a release build writes nothing at all: no logcat line,
        // no file append. Everything below exists only in the debug build.
        if (!LOG_ENABLED) return 0
        val safeTag = tag ?: "LuoLiao"
        try {
            if (tr != null) {
                android.util.Log.println(priority, safeTag, msg + "\n" + getStackTraceString(tr))
            } else {
                android.util.Log.println(priority, safeTag, msg)
            }
        } catch (_: Throwable) {
            // logcat unavailable in some early boot phases
        }
        // The module's OWN log, so troubleshooting no longer depends on exporting the LSPosed log and the
        // result can be packaged into a single file from inside the app.
        try {
            DebugLog.append(
                priority,
                safeTag,
                if (tr != null) msg + " | " + (tr.message ?: tr.javaClass.simpleName) else msg,
            )
        } catch (_: Throwable) {
            // logging must never break the module
        }
        return 0
    }
}
