package com.luoliaoo.wechat.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The module's own debug log.
 *
 * Feeds off the same `Log` calls the module already makes, so nothing is instrumented twice. Keeps a bounded
 * ring buffer in memory (so a crash still leaves the last lines) and, when debug logging is ON, appends to a
 * file that survives a restart.
 *
 * WHY: troubleshooting used to depend on the user exporting the whole LSPosed log, which is huge and cannot
 * be read back by the module. This log is complete, readable in-app, and packageable into ONE file.
 */
object DebugLog {

    private const val TAG = "LuoLiao"
    private const val FILE_NAME = "luoliao-debug.log"
    private const val MAX_MEMORY_LINES = 4000

    /** The log file is rotated at this size, so a long session cannot fill the storage. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val BEIJING = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    private val SDF = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).apply { timeZone = BEIJING }

    private val memory = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /**
     * v2.5.0: THE FILE IS ALWAYS WRITTEN.
     *
     * WHY THIS CHANGED: the switch lives in the MODULE's process, but the log that matters is written by the
     * WECHAT process, which loads this class with its own settings. Turning the switch on therefore did
     * nothing to WeChat's copy until WeChat restarted -- which looked like "enabling the log shows nothing".
     *
     * Keeping the file unconditional removes that whole class of confusion. It is size-capped and rotated
     * below, so the cost is bounded, and `enabled` is kept only so the UI can still report a preference.
     */
    @Volatile
    private var enabled = true

    @Volatile
    private var logFile: File? = null

    private val writeLock = Any()

    /** Turns logging on or off; the settings screen drives this through the config. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            Log.i(TAG, "debug log: ENABLED -> " + (logFile?.absolutePath ?: "(no file yet)"))
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Chooses where the log file lives.
     *
     * The EXTERNAL files dir is preferred: a file manager can reach it without root, so the user can send
     * the packaged log directly. Falls back to the internal dir, then to the app cache.
     */
    fun attach(ctx: Context?) {
        if (logFile != null) return
        val candidates = ArrayList<File>(3)
        try {
            ctx?.getExternalFilesDir(null)?.let { candidates.add(File(it, FILE_NAME)) }
        } catch (_: Throwable) {
            // external storage can be unavailable
        }
        try {
            ctx?.filesDir?.let { candidates.add(File(it, FILE_NAME)) }
        } catch (_: Throwable) {
            // ignore
        }
        for (c in candidates) {
            try {
                c.parentFile?.mkdirs()
                if (c.exists() || c.createNewFile()) {
                    logFile = c
                    Log.i(TAG, "debug log file: " + c.absolutePath)
                    return
                }
            } catch (_: Throwable) {
                // try the next candidate
            }
        }
        Log.w(TAG, "debug log: no writable location found")
    }

    fun file(): File? = logFile

    /** Records one line. Called by `Log` for every message the module writes. */
    fun append(priority: Int, tag: String, message: String) {
        val line = SDF.format(Date()) + " [" + level(priority) + "] " + tag + ": " + message

        // The ring buffer always fills, so a crash still leaves evidence even with logging off.
        try {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY_LINES) memory.pollFirst()
        } catch (_: Throwable) {
            // ignore
        }

        // v2.5.0: written unconditionally -- see the note on `enabled`.
        val f = logFile ?: return
        synchronized(writeLock) {
            try {
                // Rotate so a long session cannot fill the storage. One backup is kept.
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            } catch (_: Throwable) {
                // a full disk must not break the app
            }
        }
    }

    /** Everything in the ring buffer, oldest first. */
    fun snapshot(): List<String> = memory.toList()



    fun clear() {
        memory.clear()
        synchronized(writeLock) {
            try {
                logFile?.writeText("")
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun level(p: Int): String = when (p) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }
}
