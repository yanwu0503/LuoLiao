package com.luoliaoo.wechat.ai

import com.luoliaoo.wechat.core.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One optimisation proposal shown in the 文本优化 dialog.
 *
 * @param title  the style name, e.g. 更自然 / 更正式. Also the card's heading in the preview list.
 * @param text   the REWRITTEN message, ready to be put into the chat input box. Never blank for a
 *               proposal that survives parsing.
 * @param reason one short line saying what this version changes, shown under the text. Optional.
 */
data class PolishOption(
    val title: String,
    val text: String,
    val reason: String = "",
)

/**
 * Parses the model's answer to a polish request.
 *
 * THE CONTRACT: a polish request must yield AT MOST TWO proposals, and this parser must never throw.
 * The model is asked for JSON, but a model that answers with two plain paragraphs is still a perfectly
 * good answer -- so plain text is accepted too, split into proposals when it is clearly separated.
 *
 * Everything here is deliberately forgiving, because a long press that ends in a crash would take the
 * chat screen down with it. A failure degrades to "no proposals", which the UI reports normally.
 */
object TextPolishParser {

    private const val TAG = "LuoLiao"

    /** How many proposals the feature offers. Two, by design. */
    const val MAX_OPTIONS = 2

    /**
     * Parses `raw` into 0..2 proposals. Never throws, never returns more than [MAX_OPTIONS].
     */
    fun parse(raw: String?): List<PolishOption> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        val fromJson = tryParseJson(text)
        if (fromJson.isNotEmpty()) return fromJson.take(MAX_OPTIONS)

        // BROKEN JSON IS NOT PROSE.
        //
        // An answer beginning with { or [ was clearly MEANT to be JSON, so if it did not parse it is
        // garbage -- and the plain-text fallback below would offer that garbage as a "proposal", which
        // 确定 would then write into the user's chat box ("{ this is not json ]"). Returning nothing makes
        // the dialog report "没有可用的优化结果" instead, which is the honest outcome.
        //
        // A reply that does NOT start with a brace is ordinary prose (the model ignored the format
        // request), and that is still a perfectly usable answer -- it goes through the fallback.
        val first = text.firstOrNull()
        if (first == '{' || first == '[') {
            Log.w(TAG, "polish reply looks like JSON but did not parse; discarding it")
            return emptyList()
        }

        val fromText = splitPlainText(text)
        return fromText.take(MAX_OPTIONS)
    }

    // ------------------------------------------------------------------ JSON

    /**
     * Tries every JSON shape the model might produce:
     *
     *     {"options":[{"title":..,"text":..,"reason":..}, ...]}
     *     {"方案":[{"标题":..,"内容":..}, ...]}
     *     [{"title":..,"text":..}, ...]                      <- a bare array
     *     {"title":..,"text":..,"reason":..}                 <- a SINGLE proposal, not a list
     *     {"option1":{...},"option2":{...}}                  <- two named keys
     *
     * An empty result means "not JSON, or JSON with nothing usable in it" -- the caller then falls back
     * to the plain-text split.
     */
    private fun tryParseJson(text: String): List<PolishOption> {
        val body = extractJsonBody(text) ?: return emptyList()
        return try {
            val first = body.trimStart().firstOrNull()
            when {
                first == '[' -> fromArray(JSONArray(body))

                first == '{' -> {
                    val root = JSONObject(body)
                    // 1) an explicit array under any of the usual keys
                    for (key in ARRAY_KEYS) {
                        root.optJSONArray(key)?.let { arr ->
                            val out = fromArray(arr)
                            if (out.isNotEmpty()) return out
                        }
                    }
                    // 2) two named objects (option1 / option2, 方案一 / 方案二, ...)
                    val named = ArrayList<PolishOption>(MAX_OPTIONS)
                    for (k in root.keys()) {
                        val o = root.optJSONObject(k) ?: continue
                        optionFrom(o, k)?.let { named.add(it) }
                        if (named.size >= MAX_OPTIONS) break
                    }
                    if (named.isNotEmpty()) return named
                    // 3) the object IS one proposal
                    optionFrom(root, "")?.let { return listOf(it) }
                    emptyList()
                }

                else -> emptyList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "polish reply is not usable JSON: " + t.message)
            emptyList()
        }
    }

    /** Keys the model might wrap its list of proposals in. */
    private val ARRAY_KEYS = listOf("options", "results", "polish", "polished", "方案", "versions")

    /**
     * Strips the markdown fence and any surrounding chatter, returning the JSON body or null.
     *
     * WHY NOT ResultParser.extractJson ALONE: that helper looks for `{ ... }` ONLY, because every analysis
     * reply is an object. A polish reply may legitimately be a bare ARRAY, `[{...},{...}]`.
     *
     * WHICH FORM IS TRIED FIRST MATTERS, and this is not theoretical -- it was caught by a unit test. For
     * `[{"text":"甲"},{"text":"乙"}]`, extractJson finds the span `{"text":"甲"},{"text":"乙"}` and org.json
     * parses it SUCCESSFULLY as a single object: it tolerates the missing separator and folds the second
     * object's members into the first, so the two proposals silently collapse into ONE (the last one wins).
     * The leading character therefore decides which form is attempted first.
     */
    private fun extractJsonBody(raw: String): String? {
        val cleaned = raw
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isEmpty()) return null

        return if (cleaned.first() == '[') {
            extractArray(cleaned) ?: ResultParser.extractJson(cleaned)
        } else {
            ResultParser.extractJson(cleaned) ?: extractArray(cleaned)
        }
    }

    /** The outermost `[ ... ]` span, when it really is a JSON array. */
    private fun extractArray(cleaned: String): String? {
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val sub = cleaned.substring(start, end + 1)
        return if (runCatching { JSONArray(sub) }.isSuccess) sub else null
    }

    private fun fromArray(arr: JSONArray): List<PolishOption> {
        val out = ArrayList<PolishOption>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            optionFrom(o, "")?.let { out.add(it) }
            if (out.size >= MAX_OPTIONS) break
        }
        return out
    }

    /**
     * Reads ONE proposal.
     *
     * The text may legitimately be called text / content / result / reply / 内容 / 优化后 -- the module
     * cannot control what a given model names its fields, so several aliases are accepted.
     */
    private fun optionFrom(o: JSONObject, fallbackTitle: String): PolishOption? {
        val text = firstNonBlank(o, TEXT_KEYS) ?: return null
        val title = firstNonBlank(o, TITLE_KEYS)
            ?: fallbackTitle.takeIf { it.isNotBlank() }
            ?: ""
        val reason = firstNonBlank(o, REASON_KEYS) ?: ""
        return PolishOption(title = title, text = text, reason = reason)
    }

    private val TEXT_KEYS = listOf("text", "content", "result", "reply", "message", "optimized", "polished", "内容", "优化后", "正文")
    private val TITLE_KEYS = listOf("title", "name", "style", "label", "标题", "风格", "方案")
    private val REASON_KEYS = listOf("reason", "why", "explain", "note", "说明", "理由")

    private fun firstNonBlank(o: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v.trim()
        }
        return null
    }

    // ------------------------------------------------------------- plain text

    /**
     * Splits a NON-JSON answer into proposals.
     *
     * The model is told to answer with JSON, but an answer like
     *
     *     方案一：……
     *     方案二：……
     *
     * is a perfectly good result and the user must not lose it. Two strategies, in order:
     *
     *   1. split on a line that carries a numbered/named marker (方案一/方案二/1./2./第一版/第二版 ...),
     *   2. otherwise split on a blank line between the two halves of the answer.
     *
     * A single paragraph stays a SINGLE proposal: inventing a second one would show the user a
     * duplicate that says nothing new.
     */
    private fun splitPlainText(text: String): List<PolishOption> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        // 1) a marker at the START of a line.
        val lines = clean.split('\n')
        val heads = ArrayList<Int>()
        for ((i, line) in lines.withIndex()) {
            if (i == 0) continue // the first line may carry the marker of the first proposal
            if (isSeparatorLine(line)) heads.add(i)
        }
        if (heads.isNotEmpty()) {
            val out = ArrayList<PolishOption>(MAX_OPTIONS)
            var start = 0
            for (h in heads) {
                if (h <= start) continue
                chunkToOption(lines.subList(start, h).joinToString("\n"))?.let { out.add(it) }
                if (out.size >= MAX_OPTIONS) return out
                start = h
            }
            if (start < lines.size) {
                chunkToOption(lines.subList(start, lines.size).joinToString("\n"))?.let { out.add(it) }
            }
            if (out.isNotEmpty()) return out
        }

        // 2) a blank line between two halves.
        val parts = clean.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val out = ArrayList<PolishOption>(MAX_OPTIONS)
            for (p in parts) {
                chunkToOption(p)?.let { out.add(it) }
                if (out.size >= MAX_OPTIONS) break
            }
            if (out.isNotEmpty()) return out
        }

        // 3) one paragraph -> one proposal.
        return listOfNotNull(chunkToOption(clean))
    }

    /** True when a line looks like the header of the next proposal rather than body text. */
    private fun isSeparatorLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (t.length > MAX_HEADER_LEN) return false
        for (p in SEPARATOR_PREFIXES) {
            if (t.startsWith(p)) return true
        }
        return false
    }

    /** A header must be short; a long sentence that happens to start with "1." is body text. */
    private const val MAX_HEADER_LEN = 24

    private val SEPARATOR_PREFIXES = listOf(
        "方案一", "方案二", "方案三",
        "方案1", "方案2", "方案3",
        "方案 1", "方案 2", "方案 3",
        "第一版", "第二版", "第三版",
        "版本一", "版本二",
        "选项一", "选项二",
        "1.", "2.", "3.", "4.",
        "1、", "2、", "3、", "4、",
        "1）", "2）", "3）",
        "一、", "二、", "三、",
        "其一", "其二",
    )

    /**
     * Turns one chunk into a proposal, stripping the header ("方案一：") into the TITLE and keeping the
     * rest as the text. A chunk whose body ends up empty is dropped -- the caller falls back.
     */
    private fun chunkToOption(chunk: String): PolishOption? {
        var body = chunk.trim()
        if (body.isEmpty()) return null

        var title = ""
        val nl = body.indexOf('\n')
        val firstLine = if (nl >= 0) body.substring(0, nl).trim() else body.trim()

        // "方案一：正文" on ONE line -> title + body.
        for (p in SEPARATOR_PREFIXES) {
            if (!firstLine.startsWith(p)) continue
            val rest = firstLine.removePrefix(p).trimStart()
            val afterColon = rest.trimStart('：', ':', ' ', '\t', '.', '、', '）', ')')
            if (afterColon.isNotEmpty() && afterColon.length < body.length) {
                // The header and the body share a line.
                title = p.trimEnd('.', '、', '：', ':')
                body = if (nl >= 0) {
                    (afterColon + "\n" + body.substring(nl + 1)).trim()
                } else {
                    afterColon.trim()
                }
            } else if (nl >= 0) {
                // The header has the line to itself.
                title = p.trimEnd('.', '、', '：', ':')
                body = body.substring(nl + 1).trim()
            }
            break
        }

        if (body.isEmpty()) return null
        return PolishOption(title = title, text = body, reason = "")
    }
}

/**
 * The prompts sent for a polish request.
 *
 * Two deliberate choices:
 *
 *   * the BACKGROUND the user typed is extra context, never the message itself, so it is labelled as such
 *     and omitted ENTIRELY when blank -- an empty 【补充背景】 block would otherwise invite the model to
 *     invent one;
 *   * the user's chosen polish template is the style base, and the two proposals must differ from EACH
 *     OTHER, which is what makes offering two worth anything.
 */
object TextPolishPrompts {

    /** The system prompt: template voice + the two-proposal contract. */
    fun system(background: String, templatePrompt: String): String = buildString {
        val tpl = templatePrompt.trim()
        if (tpl.isNotEmpty()) {
            append(tpl).append('\n')
        } else {
            append("你是中文改写助手，帮用户把已经写好的一段话改得更得体、更像人话。\n")
        }

        append('\n')
        append("【本次任务】用户会给你一段他自己要发出去的草稿。请给出**恰好两个**改写版本，")
        append("两个版本必须在语气或侧重点上有明显区别，让人一眼能看出该选哪个。\n")

        val bg = background.trim()
        if (bg.isNotEmpty()) {
            // The background is CONTEXT, not content: saying so stops the model from treating it as
            // something the user wants to send.
            append("【补充背景】").append(bg).append('\n')
            append("（以上是用户提供的背景信息，不是要发送的内容，请结合它来理解草稿；")
            append("不要把它照抄进结果里。）\n")
            // The user is invited to add a LENGTH here ("我想要 50 个字左右"), and a constraint that is
            // merely mentioned in passing is easily ignored -- so it is called out with a priority rule.
            append("注意：如果上面包含字数、条数或长度的要求（例如「50 个字左右」「别超过两行」），")
            append("那是本次改写的硬性约束，两个方案都必须满足；此时字数要求优先于下面的长度建议。\n")
        } else {
            // No background at all: say nothing about it, so no empty block is implied.
            append("用户没有提供额外背景。\n")
        }

        append('\n')
        append("【输出格式】严格只输出下面的 JSON，不要输出任何其它文字，不要用 markdown 代码块包裹：\n")
        append("{\"options\":[{\"title\":\"方案一的风格名，4-8 个字\",")
        append("\"text\":\"改写后可以直接发送的完整内容\",")
        append("\"reason\":\"一句话说明这个版本改了什么\"},")
        append("{\"title\":\"方案二的风格名，4-8 个字\",\"text\":\"...\",\"reason\":\"...\"}]}\n")
        append('\n')
        append("【硬性要求】\n")
        append("1. options 必须正好两条，标题要能体现两个版本的差别（例如「更自然口语」与「更正式得体」）。\n")
        append("2. text 必须是完整、能直接发送的内容本身：不要引号包裹，不要「优化后」「建议」之类的字眼，")
        append("不要解释，不要加任何前后缀。\n")
        append("3. 保留用户原意和事实：不编造，不承诺他没答应的事，不加他不知道的细节。\n")
        append("4. 两条 text 都要用中文，长度和原文相当（除非用户明确要求了字数）。\n")
    }

    /** The user message: the draft, clearly delimited so the model cannot confuse it with instructions. */
    fun user(text: String): String = buildString {
        append("【需要改写的草稿】\n")
        append(text.trim()).append('\n')
    }
}
