package com.luoliaoo.wechat.data

import android.content.Context
import android.os.Environment
import com.luoliaoo.wechat.core.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Persistent chat history, kept where a WeChat restart cannot destroy it.
 *
 * WHY RECORDS USED TO DISAPPEAR
 * The store lived under `ctx.filesDir/luoliao/history` while running INSIDE the WeChat process, where
 * ctx is WeChat's own context. The real path was therefore
 *
 *     /data/user/0/com.tencent.mm/files/luoliao/history/
 *
 * i.e. inside WECHAT's private data. WeChat clears its own files directory, and the system wipes an
 * app's files when its data is cleared -- either of which deleted our history. That is exactly the
 * reported "restarting WeChat loses the records".
 *
 * THE NEW LOCATION, in order of preference:
 *   1. the MODULE's external files dir  /sdcard/Android/data/com.luoliaoo.wechat/files/history/
 *      owned by our package, so WeChat cannot clear it, and reachable without root
 *   2. the module's internal files dir (when there is no external storage)
 *   3. a plain external path for the same package
 *   4. the old WeChat-process directory, as a last resort
 *
 * Writes are ATOMIC (temp file, fsync, rename), so a kill mid-write cannot leave a truncated file.
 * Every write is also appended to a JSONL journal, so data can be recovered even if the main file were
 * ever damaged.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object HistoryStore {

    private const val TAG = "LuoLiao"
    private const val MAX_PER_TALKER = 20000

    /** Wire form of one stored message. */
    data class Line(
        val msgId: Long,
        val time: Long,
        val isSend: Boolean,
        val text: String,
        val speaker: String?,
    )

    private var dir: File? = null
    private var moduleCtx: Context? = null

    /** talker -> (msgId -> line), loaded lazily per talker. */
    private val cache = HashMap<String, LinkedHashMap<Long, Line>>()

    private val io = Executors.newSingleThreadExecutor()

    /**
     * Chooses the storage directory.
     *
     * @param weChatCtx the WeChat-process context (a fallback only)
     * @param moduleCtx our own context, when the framework can supply it
     */
    /**
     * Chooses the storage directory.
     *
     * @param weChatCtx the WeChat-process context; its EXTERNAL files dir is the preferred location
     * @param moduleCtx optional; only used when the caller already has one, NEVER resolved here
     */
    fun attach(weChatCtx: Context?, moduleCtx: Context? = null) {
        if (moduleCtx != null) this.moduleCtx = moduleCtx.applicationContext
        dir = pickDir(weChatCtx)
        dir?.let {
            it.mkdirs()
            Log.i(TAG, "history store at " + it.absolutePath + " (writable=" + it.canWrite() + ")")
        } ?: Log.e(TAG, "history store: NO writable directory found")
    }

    private fun pickDir(weChatCtx: Context?): File? {
        val candidates = ArrayList<Pair<String, File>>()

        // 1) WeChat's EXTERNAL files dir. Public API, no reflection, and WeChat's cache cleaning does
        //    not touch it -- this is the location that survives a restart.
        try {
            weChatCtx?.getExternalFilesDir(null)?.let {
                candidates.add("wechat-external" to File(it, "luoliao/history"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history: external dir unavailable: " + t.message)
        }

        // 2) the module's own dirs, but ONLY if a module Context was already provided. It is never
        //    resolved here: obtaining it needs framework reflection, which once hung WeChat at startup.
        try {
            val mc = moduleCtx
            if (mc != null) {
                mc.getExternalFilesDir(null)?.let {
                    candidates.add("module-external" to File(it, "history"))
                }
                candidates.add("module-internal" to File(mc.filesDir, "history"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history: module dirs unavailable: " + t.message)
        }

        // 3) the same external location expressed as a plain path, in case getExternalFilesDir is null.
        try {
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null) {
                candidates.add(
                    "sdcard-wechat" to
                        File(ext, "Android/data/com.tencent.mm/files/luoliao/history"),
                )
            }
        } catch (_: Throwable) {
            // not available
        }

        // 4) the old internal location (last resort; WeChat may clean it).
        try {
            weChatCtx?.let { candidates.add("wechat-files" to File(it.filesDir, "luoliao/history")) }
        } catch (_: Throwable) {
            // not available
        }

        for ((label, d) in candidates) {
            try {
                if (!d.exists() && !d.mkdirs()) continue
                if (!d.isDirectory) continue
                val probe = File(d, ".probe")
                probe.writeText("ok")
                probe.delete()
                Log.i(TAG, "history: using " + label + " -> " + d.absolutePath)
                return d
            } catch (t: Throwable) {
                Log.w(TAG, "history: " + label + " not usable: " + t.message)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ writes

    /**
     * Records the messages seen for `talker`; duplicates are ignored -- with ONE deliberate exception:
     * a real content line REPLACES a placeholder/raw-XML line for the same msgId (see [isUpgrade]).
     *
     * ============================ v10.0.0: why the exception exists ============================
     *
     * 【用户报的 bug】「读取读不到刚转写的语音，重启微信之后却可以」。
     *
     * 【第一层原因】语音消息（type=34）的 field_content 是整段 `<msg><voicemsg ...></msg>` XML，
     * 改造前它被当成正文写进本地记录，**先占住了这个 msgId**。之后语音转写成功、要写
     * `[语音转文字]xxx` 时，走到下面 `old != null -> continue` 就**被丢弃了** —— 永远是 XML 那行。
     *
     * 【第二层原因，也是"重启才行"的关键】那行 XML 含 "msg"，而 "msg" 正是
     * PromptBuilder.SKIP_MARKER（内置屏蔽词）。所以它**同时**被屏蔽词过滤掉、显示为空。
     * 于是用户看到的现象就是"刚转写的语音读不到，重启一下又有了" ——
     * 重启后走的是另一条已在内存里成型的路径，掩盖了这个竞态。
     *
     * 两层叠加的净效果：**转写结果被静默吞掉**。这就是为什么"先屏蔽 msg"和"后转文字"
     * 这两件事的顺序必须被显式处理 —— 它们会为同一个 msgId 打架。
     */
    @Synchronized
    fun record(talker: String, lines: List<Line>) {
        if (talker.isBlank() || lines.isEmpty()) return
        val k = key(talker)
        val map = cache.getOrPut(k) { load(talker) }
        var added = 0
        var upgraded = 0
        for (l in lines) {
            if (l.msgId == 0L || l.text.isBlank()) continue
            val old = map[l.msgId]
            if (old != null) {
                // v10.0.0: a real line must be able to REPLACE a raw-XML/placeholder line, otherwise a
                // transcript written after the raw voice XML is silently dropped (see the class note).
                if (isUpgrade(old, l)) {
                    map[l.msgId] = l
                    upgraded++
                    continue
                }
                // Otherwise keep the richer copy: a later read may carry a speaker the first one lacked.
                if (old.speaker == null && l.speaker != null) map[l.msgId] = l
                continue
            }
            map[l.msgId] = l
            added++
        }
        if (added > 0 || upgraded > 0) {
            trim(map)
            save(talker, map)
            Log.i(
                TAG,
                "history: +" + added + " for " + k + (if (upgraded > 0) " (replaced " + upgraded + " placeholder line(s))" else "") +
                    " (total " + map.size + ")",
            )
        }
    }

    /**
     * Whether `incoming` should overwrite `existing` for the same msgId.
     *
     * Only ONE direction is allowed: a line that is NOT raw XML may replace one that IS. That is the
     * transcript-replaces-voice-XML case. The reverse is never allowed, so a late re-bind of the raw
     * message (WeChat re-delivering the voice row) cannot undo a transcript we already stored.
     *
     * Deliberately narrow: it does NOT let arbitrary text overwrite arbitrary text, so no existing
     * dedup guarantee is weakened.
     */
    private fun isUpgrade(existing: Line, incoming: Line): Boolean =
        looksLikeRawXml(existing.text) && !looksLikeRawXml(incoming.text)

    /**
     * True for the app-message / voice / image payloads WeChat stores in field_content.
     *
     * These are never real conversational text, yet they are non-blank -- which is exactly why they used
     * to slip past the blank-text check and occupy a msgId.
     */
    private fun looksLikeRawXml(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<?xml") || t.startsWith("<msg")
    }

    // ------------------------------------------------------------------ reads

    /**
     * Everything recorded for `talker`, oldest first.
     *
     * Falls back to disk whenever the in-memory map is empty, so a concurrently created empty entry can
     * never hide stored data.
     */
    @Synchronized
    fun lines(talker: String): List<Line> {
        if (talker.isBlank()) return emptyList()
        val k = key(talker)
        val cached = cache[k]
        if (cached != null && cached.isNotEmpty()) {
            return cached.values.sortedBy { it.time }
        }
        val fromDisk = load(talker)
        val merged = LinkedHashMap<Long, Line>()
        cached?.let { for (v in it.values) merged[v.msgId] = v }
        for (v in fromDisk.values) merged[v.msgId] = v
        if (merged.isNotEmpty()) {
            cache[k] = merged
            Log.i(TAG, "history: loaded " + merged.size + " from disk for " + k)
        }
        return cache[k]?.values?.sortedBy { it.time } ?: emptyList()
    }

    /**
     * Re-reads this talker from DISK, discarding any in-memory copy first, and returns the fresh lines.
     *
     * v10.0.0, requested by the user: 「点击读取时立即重新获取一遍本地，然后再出现ui」.
     *
     * WHY THE CACHE HAD TO BE BYPASSED: [lines] deliberately prefers the in-memory map (it is filled
     * when the chat screen opens and is the fast path for the live feed). That makes it a SNAPSHOT --
     * anything written to the file by another moment in time is invisible until the process restarts.
     * The user's symptom -- 「重启之后就能看到」 -- is exactly what a stale snapshot looks like, so the
     * read button must not serve it.
     *
     * The cache is DROPPED rather than merged, so a line deleted or corrected on disk actually disappears
     * instead of surviving in memory. Writes still go through [record], so nothing here weakens the
     * dedup guarantees -- this only changes where a READ looks.
     *
     * A missing/corrupt file is not an error: [load] returns whatever it could salvage (it also replays
     * the journal), and an empty result is a legitimate answer.
     */
    @Synchronized
    fun reloadFromDisk(talker: String): List<Line> {
        if (talker.isBlank()) return emptyList()
        return try {
            val k = key(talker)
            cache.remove(k)
            val fresh = load(talker)
            if (fresh.isNotEmpty()) cache[k] = fresh
            Log.i(TAG, "history: reloaded " + fresh.size + " line(s) from disk for " + k)
            fresh.values.sortedBy { it.time }
        } catch (t: Throwable) {
            Log.w(TAG, "history: reload failed for " + talker + ": " + t.message)
            // Fall back to whatever we already had, so a transient IO error cannot blank the UI.
            lines(talker)
        }
    }

    @Synchronized
    fun count(talker: String): Int = lines(talker).size

    /**
     * One stored line by its msgId, or null.
     *
     * v10.0.0: used to read back a VOICE transcript we wrote ourselves. It exists as its own accessor
     * rather than a `lines(talker).firstOrNull { it.msgId == id }` because that form builds and sorts
     * the ENTIRE conversation (thousands of lines) just to look up one id -- and this runs from the UI
     * path, once per voice message rendered.
     *
     * Goes through [lines] deliberately so it sees exactly what every other reader sees (in-memory
     * cache first, disk fallback), instead of inventing a second source of truth.
     */
    @Synchronized
    fun lineFor(talker: String, msgId: Long): Line? {
        if (talker.isBlank() || msgId == 0L) return null
        return try {
            cache[key(talker)]?.get(msgId) ?: lines(talker).firstOrNull { it.msgId == msgId }
        } catch (_: Throwable) {
            null
        }
    }

    /** Every talker with stored history, for diagnostics. */
    @Synchronized
    fun talkers(): List<String> {
        val out = ArrayList<String>()
        try {
            dir?.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".json")) out.add(f.name.removeSuffix(".json"))
            }
        } catch (_: Throwable) {
            // best effort
        }
        return out
    }

    /** The directory actually in use, for diagnostics. */
    fun location(): String = dir?.absolutePath ?: "(none)"

    /**
     * Whether the store's directory exists and accepts writes, probed for real.
     *
     * Reported by the pipeline diagnostic because an unwritable directory is the one failure that
     * looks like "the feed is broken" while actually being downstream of it. Probes by WRITING, not
     * by `canWrite()`: under scoped storage `canWrite()` can answer true and the write still fail.
     */
    fun isWritable(): Boolean {
        // Block body, not an expression body: the early `return false` guards are not allowed in a
        // `= try {}` form (Kotlin: "Returns are prohibited for functions with an expression body").
        return try {
            val d = dir ?: return false
            if (!d.isDirectory) return false
            val probe = File(d, ".probe")
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
        try {
            dir?.listFiles()?.forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "history clear failed: " + t.message)
        }
        Log.i(TAG, "history cleared")
    }

    // ------------------------------------------------------------- persistence

    /**
     * Normalises a talker id so one conversation always maps to one entry.
     *
     * Two spellings of the same chat used to produce a populated entry and an EMPTY one, so reads
     * landing on the empty key saw zero history (the log showed 152 -> 0 -> 152 for one chat).
     */
    private fun key(talker: String): String {
        val t = talker.trim()
        if (t.isEmpty()) return t
        val base = t.substringBefore('@').lowercase()
        return if (base.isNotEmpty()) base else t.lowercase()
    }

    /**
     * Public form of [key], for callers that must agree with the store's own normalisation.
     *
     * The live-staging map is keyed by the RAW talker while the store keys by the normalised one, so a
     * caller that flushes "whatever is staged for this chat" has to try both spellings. Exposing the
     * rule beats duplicating it: two copies of a normalisation rule drift apart, and the symptom would
     * be history that silently fails to line up.
     */
    fun normaliseKey(talker: String): String = key(talker)

    private fun file(talker: String): File? {
        val d = dir ?: return null
        val safe = talker.replace(Regex("[^A-Za-z0-9_@.-]"), "_").take(80)
        return File(d, safe + ".json")
    }

    /** Every write is also appended here, so a damaged main file can still be recovered. */
    private fun journal(): File? = dir?.let { File(it, "journal.jsonl") }

    private fun load(talker: String): LinkedHashMap<Long, Line> {
        val out = LinkedHashMap<Long, Line>()
        val f = file(talker)
        if (f != null && f.isFile) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("msgId", 0L)
                    if (id == 0L) continue
                    out[id] = Line(
                        msgId = id,
                        time = o.optLong("time", 0L),
                        isSend = o.optBoolean("me", false),
                        text = o.optString("text", ""),
                        speaker = o.optString("speaker", "").ifBlank { null },
                    )
                }
                Log.i(TAG, "history: read " + out.size + " line(s) from " + f.name)
            } catch (t: Throwable) {
                Log.w(TAG, "history: " + f.name + " unreadable: " + t.message)
            }
        }
        recoverFromJournal(talker, out)
        return out
    }

    private fun recoverFromJournal(talker: String, out: LinkedHashMap<Long, Line>) {
        val j = journal() ?: return
        if (!j.isFile) return
        var added = 0
        try {
            val k = key(talker)
            j.forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                try {
                    val o = JSONObject(raw)
                    if (o.optString("talker", "") != k) return@forEachLine
                    val id = o.optLong("msgId", 0L)
                    if (id == 0L || out.containsKey(id)) return@forEachLine
                    out[id] = Line(
                        msgId = id,
                        time = o.optLong("time", 0L),
                        isSend = o.optBoolean("me", false),
                        text = o.optString("text", ""),
                        speaker = o.optString("speaker", "").ifBlank { null },
                    )
                    added++
                } catch (_: Throwable) {
                    // skip a damaged journal line
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history: journal read failed: " + t.message)
        }
        if (added > 0) Log.i(TAG, "history: recovered " + added + " line(s) from the journal")
    }

    /**
     * Writes the whole map atomically: a temp file, an fsync, then a rename.
     *
     * A plain writeText() interrupted by a kill leaves a truncated JSON file, which is one more way
     * records appeared lost. A rename is atomic, so the file is either the old content or the new one.
     */
    private fun save(talker: String, map: LinkedHashMap<Long, Line>) {
        val f = file(talker)
        if (f == null) {
            Log.w(TAG, "history: no directory; cannot save " + talker)
            return
        }
        val snapshot = map.values.toList()
        io.execute {
            try {
                val arr = JSONArray()
                for (l in snapshot) {
                    arr.put(
                        JSONObject().apply {
                            put("msgId", l.msgId)
                            put("time", l.time)
                            put("me", l.isSend)
                            put("text", l.text)
                            l.speaker?.let { s -> put("speaker", s) }
                        },
                    )
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                java.io.FileOutputStream(tmp).use { fos ->
                    fos.write(arr.toString().toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    // rename can fail on some filesystems; fall back to a direct write
                    f.writeText(arr.toString())
                    tmp.delete()
                }
                Log.i(TAG, "history: saved " + snapshot.size + " line(s) to " + f.absolutePath)
                appendJournal(talker, snapshot)
            } catch (t: Throwable) {
                Log.e(TAG, "history: save failed for " + f.name + ": " + t.message)
            }
        }
    }

    /** Appends the newest lines to the journal (best effort). */
    private fun appendJournal(talker: String, lines: List<Line>) {
        val j = journal() ?: return
        try {
            val k = key(talker)
            java.io.FileOutputStream(j, true).use { fos ->
                val w = java.io.BufferedWriter(java.io.OutputStreamWriter(fos, Charsets.UTF_8))
                for (l in lines) {
                    val o = JSONObject().apply {
                        put("talker", k)
                        put("msgId", l.msgId)
                        put("time", l.time)
                        put("me", l.isSend)
                        put("text", l.text)
                        l.speaker?.let { s -> put("speaker", s) }
                    }
                    w.write(o.toString())
                    w.write("\n")
                }
                w.flush()
                fos.fd.sync()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "history: journal append failed: " + t.message)
        }
    }

    /** Keeps the newest MAX_PER_TALKER entries. */
    private fun trim(map: LinkedHashMap<Long, Line>) {
        if (map.size <= MAX_PER_TALKER) return
        val sorted = map.values.sortedByDescending { it.time }.take(MAX_PER_TALKER)
        map.clear()
        for (l in sorted.sortedBy { it.time }) map[l.msgId] = l
        Log.i(TAG, "history: trimmed to " + map.size)
    }
}
