package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.ai.ScopeMode
import com.luoliaoo.wechat.core.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent storage for analysis cards (item 7).
 *
 * Cards are identified by "talker#msgId" and hold everything needed to redraw them after a
 * restart: the scope, the result, and the follow-up conversation. The transient View anchor
 * is deliberately NOT stored -- that was the bug behind a card vanishing when its row scrolled
 * out of view, and behind "无法重新分析：消息已离开屏幕".
 */
object CardStore {

    private const val TAG = "LuoLiao"
    private const val FILE_NAME = "cards.json"
    private const val MAX_ENTRIES = 1000

    /** One saved card. */
    data class Saved(
        val key: String,
        val talker: String,
        val msgId: Long,
        /**
         * The message this card analyses.
         *
         * item 2: stored so the record list can show it after a restart. It used to be rebuilt empty,
         * which is why the title lost "我爱你" and kept only "是 80% 不是 20%".
         */
        val text: String = "",
        /** item 1: token counts, persisted so the usage line survives a restart. */
        val usagePrompt: Int = 0,
        val usageCompletion: Int = 0,
        val mode: ScopeMode,
        val up: Int,
        val down: Int,
        val startMs: Long,
        val endMs: Long,
        val result: AnalysisResult?,
        val error: String?,
        val turns: List<ChatTurn>,
    )

    private val mem = LinkedHashMap<String, Saved>()

    @Volatile
    private var loaded = false

    private var dir: File? = null

    /** Called once from the WeChat process. */
    fun attach(workDir: File) {
        dir = workDir
    }

    /** Fallback when attach() was not called: use the app's private files dir. */
    fun attachFrom(ctx: Context) {
        if (dir == null) dir = File(ctx.filesDir, "luoliao")
    }

    @Synchronized
    fun get(key: String): Saved? {
        ensureLoaded()
        return mem[key]
    }

    @Synchronized
    fun put(s: Saved) {
        ensureLoaded()
        mem.remove(s.key)
        mem[s.key] = s
        while (mem.size > MAX_ENTRIES) {
            val it = mem.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        persist()
    }

    @Synchronized
    fun remove(key: String) {
        ensureLoaded()
        if (mem.remove(key) != null) {
            Log.i(TAG, "card store: removed " + key)
            persist()
        }
    }

    @Synchronized
    fun all(): List<Saved> {
        ensureLoaded()
        return mem.values.toList()
    }

    @Synchronized
    fun clearAll() {
        val n = mem.size
        mem.clear()
        loaded = true
        try {
            file()?.takeIf { it.exists() }?.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "card store delete failed: " + t.message)
        }
        Log.i(TAG, "card store cleared (" + n + " entries)")
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return mem.size
    }

    // ------------------------------------------------------------- persistence

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                parse(o)?.let { mem[it.key] = it }
            }
            Log.i(TAG, "card store: loaded " + mem.size + " card(s) from disk")
        } catch (t: Throwable) {
            Log.w(TAG, "card store read failed: " + t.message)
        }
    }

    private fun persist() {
        val f = file() ?: return
        try {
            val root = JSONObject()
            for ((k, v) in mem) root.put(k, serialise(v))
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
            Log.i(TAG, "card store: persisted " + mem.size + " card(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "card store write failed: " + t.message)
        }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    private fun serialise(s: Saved): JSONObject = JSONObject().apply {
        put("key", s.key)
        put("talker", s.talker)
        put("msgId", s.msgId)
            put("text", s.text)
        put("usagePrompt", s.usagePrompt)
        put("usageCompletion", s.usageCompletion)
        put("mode", s.mode.name)
        put("up", s.up)
        put("down", s.down)
        put("startMs", s.startMs)
        put("endMs", s.endMs)
        s.result?.let { put("result", resultToJson(it)) }
        s.error?.let { put("error", it) }
        val arr = JSONArray()
        for (t in s.turns) {
            arr.put(JSONObject().apply {
                put("me", t.fromMe)
                put("text", t.text)
                put("time", t.time)
                /* ★ v10.3.0：`pending` 与 `partial` 也必须落盘。
                   原来只存 me/text/time 三个字段，于是"正在生成中"这个状态和流式的半截答案
                   在重启后就没了 —— 卡片会退回一条**空白的 AI 气泡**（text 为空），
                   比显示"思考中"更像坏掉了。
                   两个字段都写，读取侧对老数据自然回落到默认值（false / 空串），
                   所以这是一个向后兼容的纯增量改动。 */
                put("pending", t.pending)
                put("partial", t.partial)
            })
        }
        put("turns", arr)
    }

    private fun parse(o: JSONObject): Saved? {
        return try {
        val key = o.optString("key", "")
        if (key.isBlank()) return null
        val turns = ArrayList<ChatTurn>()
        val arr = o.optJSONArray("turns")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                turns += ChatTurn(
                    fromMe = t.optBoolean("me", false),
                    text = t.optString("text", ""),
                    time = t.optLong("time", 0L),
                    // ★ v10.3.0：老数据没有这两个键时回落到默认值，行为与改动前一致。
                    pending = t.optBoolean("pending", false),
                    partial = t.optString("partial", ""),
                )
            }
        }
        Saved(
            key = key,
            talker = o.optString("talker", ""),
            msgId = o.optLong("msgId", 0L),
                text = o.optString("text", ""),
                usagePrompt = o.optInt("usagePrompt", 0),
                usageCompletion = o.optInt("usageCompletion", 0),
            mode = runCatching { ScopeMode.valueOf(o.optString("mode", "COUNT")) }
                .getOrDefault(ScopeMode.COUNT),
            up = o.optInt("up", 3),
            down = o.optInt("down", 3),
            startMs = o.optLong("startMs", 0L),
            endMs = o.optLong("endMs", 0L),
            result = o.optJSONObject("result")?.let { resultFromJson(it) },
            error = o.optString("error", "").ifBlank { null },
            turns = turns,
        )
        } catch (t: Throwable) {
            Log.w(TAG, "card store parse failed: " + t.message)
            null
        }
    }

    // ---- result (de)serialisation, shared shape with ResultCache

    fun resultToJson(r: AnalysisResult): JSONObject = JSONObject().apply {
        put("question", r.question)
        put("intent", optionsToJson(r.intentOptions))
        put("real", optionsToJson(r.realIntent))
        put("anger", r.angerLevel)
        if (r.comment != null) put("comment", r.comment)
        if (r.rawFallback != null) put("raw", r.rawFallback)
    }

    fun resultFromJson(o: JSONObject): AnalysisResult = AnalysisResult(
        question = o.optString("question", ""),
        intentOptions = optionsFromJson(o.optJSONArray("intent")),
        realIntent = optionsFromJson(o.optJSONArray("real")),
        angerLevel = o.optInt("anger", -1),
        comment = o.optString("comment", "").ifBlank { null },
        rawFallback = o.optString("raw", "").ifBlank { null },
    )

    private fun optionsToJson(list: List<AnalysisResult.Option>): JSONArray =
        JSONArray().apply {
            for (o in list) {
                put(JSONObject().apply {
                    put("label", o.label)
                    put("p", o.probability)
                    if (o.reply != null) put("reply", o.reply)
                    if (o.reason != null) put("reason", o.reason)
                })
            }
        }

    private fun optionsFromJson(a: JSONArray?): List<AnalysisResult.Option> {
        if (a == null) return emptyList()
        val out = ArrayList<AnalysisResult.Option>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += AnalysisResult.Option(
                label = o.optString("label", ""),
                probability = o.optInt("p", 0),
                reply = o.optString("reply", "").ifBlank { null },
                reason = o.optString("reason", "").ifBlank { null },
            )
        }
        return out
    }
}
