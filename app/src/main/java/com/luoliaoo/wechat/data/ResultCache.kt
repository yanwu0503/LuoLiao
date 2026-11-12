package com.luoliaoo.wechat.data

import com.luoliaoo.wechat.core.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Analysis result cache (memory + disk).
 *
 * Key = sha256(talker + msgId/msgSvrId + scope fingerprint + model + prompt), so identical
 * inputs never re-spend tokens and any settings change counts as a different input.
 *
 * clearAll() backs the config panel's "清空所有记录" button (item 7).
 */
object ResultCache {

    private const val TAG = "LuoLiao"
    private const val FILE_NAME = "results.json"
    private const val MAX_ENTRIES = 300

    private val mem = LinkedHashMap<String, AnalysisResult>()

    @Volatile
    private var loaded = false

    private var dir: File? = null

    /** Called by the WeChat process during initialisation. */
    fun attach(workDir: File) {
        dir = workDir
    }

    fun keyOf(
        talker: String,
        msgId: Long,
        msgSvrId: Long,
        scopeFingerprint: String,
        model: String,
        promptHash: String,
    ): String {
        val raw = talker + "|" + msgId + "|" + msgSvrId + "|" + scopeFingerprint +
            "|" + model + "|" + promptHash
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    fun hashPrompt(prompt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    @Synchronized
    fun get(key: String): AnalysisResult? {
        ensureLoaded()
        return mem[key]
    }

    @Synchronized
    fun put(key: String, value: AnalysisResult) {
        ensureLoaded()
        mem.remove(key)
        mem[key] = value
        while (mem.size > MAX_ENTRIES) {
            val it = mem.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        persist()
    }

    /** Removes every cached result, in memory and on disk (item 7). */
    @Synchronized
    fun clearAll() {
        val n = mem.size
        mem.clear()
        loaded = true
        file()?.let { f ->
            try {
                if (f.exists()) f.delete()
            } catch (t: Throwable) {
                Log.w(TAG, "cache file delete failed: " + t.message)
            }
        }
        Log.i(TAG, "cache cleared (" + n + " entries)")
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return mem.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val parsed = parse(o)
                if (parsed != null) mem[k] = parsed
            }
            Log.i(TAG, "loaded " + mem.size + " cached results")
        } catch (t: Throwable) {
            Log.w(TAG, "cache read failed: " + t.message)
        }
    }

    private fun persist() {
        val f = file() ?: return
        try {
            val root = JSONObject()
            for ((k, v) in mem) root.put(k, serialise(v))
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "cache write failed: " + t.message)
        }
    }

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    private fun serialise(r: AnalysisResult): JSONObject = JSONObject().apply {
        put("question", r.question)
        put("intent", optionsToJson(r.intentOptions))
        put("real", optionsToJson(r.realIntent))
        put("anger", r.angerLevel)
        if (r.comment != null) put("comment", r.comment)
        if (r.rawFallback != null) put("raw", r.rawFallback)
    }

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

    private fun parse(o: JSONObject): AnalysisResult? = try {
        AnalysisResult(
            question = o.optString("question", ""),
            intentOptions = optionsFromJson(o.optJSONArray("intent")),
            realIntent = optionsFromJson(o.optJSONArray("real")),
            angerLevel = o.optInt("anger", -1),
            comment = o.optString("comment", "").ifBlank { null },
            rawFallback = o.optString("raw", "").ifBlank { null },
        )
    } catch (t: Throwable) {
        null
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
