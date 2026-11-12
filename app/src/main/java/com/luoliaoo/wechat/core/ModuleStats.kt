package com.luoliaoo.wechat.core

/**
 * Process-wide counters reported by the heartbeat.
 *
 * WHY THIS EXISTS: the clone's sessions in an exported log are tiny and show no activity after
 * installation. Without these counters it is impossible to tell "the process died" from "the user never
 * opened a chat". `adapters` incrementing proves WeChat created a list screen here; the heartbeat proves the
 * process is still alive. Together they make the clone's state unambiguous.
 *
 * The state lives here rather than in WeChatHooks because that class keeps its state in a PRIVATE companion
 * object, which other classes cannot reach.
 */
object ModuleStats {

    @Volatile
    var adapters: Int = 0
        private set

    @Volatile
    var balloons: Int = 0
        private set

    @Volatile
    var heartbeats: Int = 0
        private set

    fun noteAdapter() {
        adapters++
    }

    fun noteBalloon() {
        balloons++
    }

    fun noteHeartbeat() {
        heartbeats++
    }

    // ============================================================ v2.5.2: CROSS-PROCESS PUBLISHING
    //
    // WHY: `ModuleStats` is an object, so every process has its OWN instance. The counters are incremented in
    // the WECHAT process, but the debug screen runs in the MODULE's process -- where every counter is 0. That
    // is exactly why the self-check showed all zeros while WeChat's own log showed real values.
    //
    // The counters are therefore written to a file the module can read. It is small, rewritten on a timer (not
    // per event), and read back by the debug screen.
    private const val SNAPSHOT_NAME = "luoliao-stats.txt"

    @Volatile
    private var snapshotFile: java.io.File? = null

    /** The WeChat process calls this once, so there is somewhere to publish. */
    fun attachSnapshot(ctx: android.content.Context?) {
        if (snapshotFile != null) return
        try {
            val dir = ctx?.getExternalFilesDir(null) ?: ctx?.filesDir ?: return
            snapshotFile = java.io.File(dir, SNAPSHOT_NAME)
        } catch (_: Throwable) {
            // publishing is best effort
        }
    }

    /** Writes the current counters so another process can read them. Called by the heartbeat. */
    fun publish() {
        val f = snapshotFile ?: return
        try {
            val text = buildString {
                append("adapters=").append(adapters).append('\n')
                append("balloons=").append(balloons).append('\n')
                append("installed=").append(moduleInstalled).append('\n')
                append("heartbeats=").append(heartbeats).append('\n')
                append("menuHookInstalled=").append(menuHookInstalled).append('\n')
                append("menuAttempts=").append(menuAttempts).append('\n')
                append("menuInjected=").append(menuInjected).append('\n')
                append("user=").append(android.os.Process.myUid() / 100000).append('\n')
                append("pid=").append(android.os.Process.myPid()).append('\n')
                append("when=").append(System.currentTimeMillis()).append('\n')
            }
            f.writeText(text)
        } catch (_: Throwable) {
            // a failure here must never affect the module
        }
    }

    /**
     * Reads the published counters, from THIS process first and from any process that published otherwise.
     *
     * The screen runs in the module's process, so the local values are usually all zero; the published file
     * from WeChat is what carries the truth.
     */
    fun readPublished(): Map<String, String> {
        val out = HashMap<String, String>()
        val candidates = ArrayList<java.io.File>(4)
        snapshotFile?.let { candidates.add(it) }
        for (u in intArrayOf(0, android.os.Process.myUid() / 100000, 999)) {
            for (pkg in arrayOf("com.tencent.mm", com.luoliaoo.wechat.BuildConfig.APPLICATION_ID)) {
                candidates.add(java.io.File(
                    "/storage/emulated/" + u + "/Android/data/" + pkg + "/files/" + SNAPSHOT_NAME,
                ))
            }
        }
        // prefer the newest file that parses
        var bestTime = -1L
        var best: Map<String, String>? = null
        for (f in candidates) {
            val m = try {
                if (!f.isFile || f.length() <= 0L) null else parse(f.readText())
            } catch (_: Throwable) {
                null
            } ?: continue
            val when_ = m["when"]?.toLongOrNull() ?: 0L
            if (when_ > bestTime) {
                bestTime = when_
                best = m
            }
        }
        if (best != null) out.putAll(best)
        return out
    }

    private fun parse(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.split('\n')) {
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return m
    }

    /**
     * How many times the menu injection ran, and how many times it actually produced an item.
     *
     * WHY: "the 分析 button did not appear" is ambiguous. With these two numbers the heartbeat says which:
     *   attempts=0            -> no long press ever reached the module
     *   attempts>0, injected=0 -> the injection ran but was rejected (a row-matching problem)
     */
    @Volatile
    var menuAttempts: Int = 0
        private set

    @Volatile
    var menuInjected: Int = 0
        private set

    fun noteMenuAttempt() {
        menuAttempts++
    }

    fun noteMenuInjected() {
        menuInjected++
    }

    /**
     * v2.5.0: whether the CHAT MENU BUILDER hook is installed.
     *
     * The self-check reports this because it is the one condition that decides whether ANY item can appear:
     * without it the module is loaded but inert, which is indistinguishable from "not installed" unless the
     * screen says so.
     */
    @Volatile
    var menuHookInstalled: Boolean = false

    /** v2.5.2: mirrors WeChatHooks' install flag, so the published snapshot can carry it. */
    @Volatile
    var moduleInstalled: Boolean = false
}
