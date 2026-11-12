package com.luoliaoo.wechat.ai

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.AnalysisResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the model's JSON reply, tolerating every deviation:
 *  - strips ```json fences
 *  - extracts the outermost { ... }
 *  - missing/mistyped fields fall back to defaults instead of throwing
 *  - reads the optional per-intent `reply` (used by the card's 复制 button)
 *  - total failure degrades to showing the raw text
 */
object ResultParser {

    private const val TAG = "LuoLiao"

    fun parse(raw: String?): AnalysisResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return AnalysisResult.fallback("（模型返回为空）")

        val json = extractJson(text)
        if (json == null) {
            Log.i(TAG, "model reply is not JSON; degrading to raw text")
            return AnalysisResult.fallback(text)
        }

        return try {
            val root = JSONObject(json)

            val question: String
            val intentOptions: List<AnalysisResult.Option>
            val intentObj = root.optJSONObject("intent")
            if (intentObj != null) {
                question = intentObj.optString("question", "")
                intentOptions = readOptions(intentObj.optJSONArray("options"))
            } else {
                question = root.optString("question", "")
                intentOptions = readOptions(root.optJSONArray("options"))
            }

            var realArr = root.optJSONArray("real_intent")
            if (realArr == null) realArr = root.optJSONArray("realIntent")
            val realIntent = readOptions(realArr)

            val anger = when {
                root.has("anger_level") -> root.optInt("anger_level", -1)
                root.has("angerLevel") -> root.optInt("angerLevel", -1)
                else -> -1
            }

            if (intentOptions.isEmpty() && realIntent.isEmpty() && anger < 0) {
                return AnalysisResult.fallback(text)
            }

            AnalysisResult(
                question = question,
                intentOptions = intentOptions,
                realIntent = realIntent,
                angerLevel = anger.coerceIn(-1, 10),
                comment = root.optString("comment", "").ifBlank { null },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "JSON parse threw; degrading: " + t.message)
            AnalysisResult.fallback(text)
        }
    }

    private fun readOptions(arr: JSONArray?): List<AnalysisResult.Option> {
        if (arr == null) return emptyList()
        val out = ArrayList<AnalysisResult.Option>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label", "")
            if (label.isNotBlank()) {
                val p = o.optDouble("probability", 0.0).toInt().coerceIn(0, 100)
                // "reply" is the suggested sentence; accept a couple of aliases.
                val reply = listOf("reply", "suggest", "suggestion", "answer")
                    .firstNotNullOfOrNull { k ->
                        o.optString(k, "").ifBlank { null }
                    }
                // item 1: the reason behind an option, shown as 原因：XX.
                val reason = listOf("reason", "why", "explain", "basis")
                    .firstNotNullOfOrNull { k ->
                        o.optString(k, "").ifBlank { null }
                    }
                out += AnalysisResult.Option(label, p, reply, reason)
            }
        }
        return out
    }

    internal fun extractJson(text: String): String? {
        val cleaned = text
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.startsWith("{")) {
            if (runCatching { JSONObject(cleaned) }.isSuccess) return cleaned
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val sub = cleaned.substring(start, end + 1)
        return if (runCatching { JSONObject(sub) }.isSuccess) sub else null
    }
}
