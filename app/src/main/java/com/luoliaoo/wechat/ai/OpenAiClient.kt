package com.luoliaoo.wechat.ai

import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.AnalysisResult
import com.luoliaoo.wechat.data.ChatTurn
import com.luoliaoo.wechat.data.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible API client. Used only inside a host process (微信 / 抖音).
 *
 * SECURITY: the API key is never written to any log. On failure only the HTTP status and a
 * truncated body are logged.
 */
/**
 * One reply, with the token usage the API reported.
 *
 * item 4: the card shows what the request cost, so the counts travel with the text.
 */
data class AiReply(
    val text: String?,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    val hasUsage: Boolean get() = promptTokens > 0 || completionTokens > 0
    val totalTokens: Int get() = promptTokens + completionTokens
}

class OpenAiClient(private val cfg: Config) {

    private companion object {
        const val TAG = "LuoLiao"
        const val CONNECT_TIMEOUT = 15L
        const val READ_TIMEOUT = 120L
    }

    /**
     * The system prompt, spelled for the host this request is made from.
     *
     * WHY HERE and not where the prompt is STORED: `systemPrompt` is a persisted user setting, written
     * by the module's own process (which has no host of its own) and read by whichever host is running.
     * Rewriting it at save time would freeze 抖音号 into a config a WeChat user shares, so the swap
     * happens at the last moment instead -- on the way to the API, where the host is unambiguous.
     *
     * A user who typed their own prompt is *not* second-guessed: only a literal 微信 / 微信号 is
     * swapped, which is exactly the built-in wording. A prompt that never mentions 微信 passes through
     * byte-for-byte, so the result cache and every existing analysis are unaffected.
     */
    private val systemPrompt: String
        get() = com.luoliaoo.wechat.core.HostApp.say(cfg.systemPrompt)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    private fun endpoint(path: String): String {
        val base = cfg.baseUrl.trim().removeSuffix("/")
        return if (base.endsWith("/chat/completions") && path == "chat/completions") base
        else base + "/" + path
    }

    /**
     * 拉取服务端的模型列表。
     *
     * ═══════════════════════════════════════════════════════════════════════════════
     * ★ v10.3.0：**本函数不做任何"哪些模型可用"的判断，也绝不因此挡住用户。**
     *
     * 【用户明确要求】"从接口获取模型不应该锁死哪些模型可选，应该没有限制，
     * 让用户自己判断"。所以这里只做一件事：**把服务端说的模型名尽量原样列出来**。
     * 不筛 gpt/claude/qwen，不看名字里有没有 "vl"/"vision"，
     * 因为"哪个模型支持识图"是**服务端的私有知识**，本地靠名字猜必然出错，
     * 而且会随服务端更新而失效。
     *
     * ── 真机踩过的坑（本函数第二版修的就是它）──────────────────────────────────
     * 用户的 OpenAI 兼容中转站 `https://api.littlecold.cn/v1` 对 `/models`
     * 返回的是 **HTML**（一个错误页），而不是 JSON。原实现直接 `JSONObject(body)`
     * 于是抛 `Value <!doctype of type java.lang.String cannot be converted to JSONObject`，
     * 用户看到的是"获取模型列表失败"，**完全不知道该怎么办**。
     *
     * 这类"OpenAI 兼容"服务端的实际形状千奇百怪，所以：
     *   * 同时认 `data[].id`（标准）、`data` 是字符串数组、`models[].name`、
     *     以及顶层就是字符串数组这几种常见写法；
     *   * 一个都认不出来时抛的异常**必须说人话**（见下），
     *     告诉用户可以手填模型名 —— 因为"列不出来"根本不影响功能，
     *     模型名本来就可以手输，列表只是锦上添花。
     * ═══════════════════════════════════════════════════════════════════════════════
     */
    fun listModels(): List<String> {
        val req = Request.Builder()
            .url(endpoint("models"))
            .header("Authorization", "Bearer " + cfg.apiKey)
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP " + resp.code + ": " + body.take(300))
            }
            return parseModelList(body)
        }
    }

    /**
     * 从响应体里尽力取出模型名。
     *
     * 【为什么要容错这么多种形状】"OpenAI 兼容"是个很松的约定 ——
     * 实测中转站会返回 HTML 错误页、也有服务端把 `data` 写成字符串数组。
     * 只要能把名字取出来就让用户能点选，取不出来才退化。
     *
     * 【为什么顶层是数组也认】有些网关（以及部分自建代理）直接返回
     * `["gpt-4o", "gpt-4o-mini"]`，不带 `data` 外壳。按标准形状解析会一个都拿不到。
     */
    private fun parseModelList(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()

        // 不是 JSON（例如被网关换成 HTML 错误页）→ 直接说清楚，不要抛解析异常。
        // ⚠️ 先做这个判断，否则 JSONObject 的报错信息里带 `<` / `<!doctype`，
        //    用户看到一串英文类型错误，完全不明白发生了什么。
        if (!text.startsWith("{") && !text.startsWith("[")) {
            throw IllegalStateException(
                "接口没有返回模型列表。\n\n" +
                    "服务端返回的内容不是 JSON，通常是因为这个地址不支持 /models。\n" +
                    "模型名可以直接手动填写，不影响使用。\n\n" +
                    "返回内容开头：" + text.take(120),
            )
        }

        val out = LinkedHashSet<String>()
        try {
            if (text.startsWith("[")) {
                // 顶层字符串数组
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).orEmpty()
                    if (id.isNotBlank()) out += id
                }
            } else {
                val obj = JSONObject(text)
                // ① 标准：data[].id
                val data = obj.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        // data 里可能直接是字符串，也可能是对象
                        val asStr = data.optString(i).orEmpty()
                        if (data.optJSONObject(i) == null && asStr.isNotBlank()) {
                            out += asStr
                            continue
                        }
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) out += id
                    }
                }
                // ② 少数服务端用 models[].name / models[].id
                val models = obj.optJSONArray("models")
                if (models != null) {
                    for (i in 0 until models.length()) {
                        val o = models.optJSONObject(i) ?: continue
                        val name = o.optString("name").ifBlank { o.optString("id") }
                        if (name.isNotBlank()) out += name
                    }
                }
            }
        } catch (t: Throwable) {
            // 形状比预期还怪：不抛，返回已经拿到的部分（可能为空）。
            // 空列表由调用方提示"可手动填写"，比一个类型错误有用得多。
            Log.w(TAG, "models: parsing failed, returning what we got: " + t.message)
        }
        // ⚠️ 不排序、不过滤。服务端的顺序通常按版本新旧排列，对用户更有参考价值；
        //    排序会打乱它，而"筛选"更是用户明确禁止的。
        return out.toList()
    }

    /** Runs one analysis. Never throws: failures come back as a displayable result. */
    /**
     * item 4: the same analysis, plus the token usage.
     *
     * Runs the request once and reports both, so the caller can show the cost without a second call.
     */
    fun analyzeWithUsage(userContent: String): Pair<AnalysisResult, AiReply> {
        val reply = chat(buildMessages(userContent))
        val result = try {
            ResultParser.parse(reply.text)
        } catch (t: Throwable) {
            Log.w(TAG, "AI reply parse threw: " + t.message)
            AnalysisResult.fallback("请求异常：" + t.message)
        }
        return result to reply
    }

    /**
     * v4.0.0: one text-polish request (long press on the balloon → 文本优化).
     *
     * Asks the model for two differently-styled rewrites of `text` and returns them together with the
     * token usage, so the dialog can show both without a second call.
     *
     * @param background      the user's optional 补充背景 note; blank means "no background at all" and the
     *                        prompt then contains no background block
     * @param templatePrompt  the chosen polish template's prompt, i.e. the style base
     *
     * NEVER throws and never returns more than two proposals: a failed request comes back as an empty list
     * plus an AiReply with no text, which the dialog reports as an error instead of crashing.
     */
    fun polish(text: String, background: String, templatePrompt: String): Pair<List<PolishOption>, AiReply> {
        val reply = try {
            chat(buildPolishMessages(text, background, templatePrompt))
        } catch (t: Throwable) {
            Log.w(TAG, "polish request threw: " + t.javaClass.simpleName + ": " + t.message)
            AiReply(null)
        }
        val options = try {
            TextPolishParser.parse(reply.text)
        } catch (t: Throwable) {
            Log.w(TAG, "polish reply parse threw: " + t.message)
            emptyList()
        }
        Log.i(
            TAG,
            "polish: " + options.size + " option(s), tokens=" + reply.totalTokens +
                " (background=" + background.trim().length + " char(s))",
        )
        return options to reply
    }

    /** v4.0.0: the system + user messages for a polish request, mirroring buildMessages(). */
    private fun buildPolishMessages(
        text: String,
        background: String,
        templatePrompt: String,
    ): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", TextPolishPrompts.system(background, templatePrompt))
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", TextPolishPrompts.user(text))
        })
    }

    /** item 4: the follow-up reply with its usage. */
    fun followUpWithUsage(context: String, turns: List<ChatTurn>): AiReply {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", context)
        })
        for (t in turns) {
            if (t.pending) continue
            messages.put(JSONObject().apply {
                put("role", if (t.fromMe) "user" else "assistant")
                put("content", t.text)
            })
        }
        return chat(messages)
    }

    fun analyze(userContent: String): AnalysisResult {
        return try {
            val body = post(buildMessages(userContent))
            if (body == null) return AnalysisResult.fallback("请求失败")
            ResultParser.parse(body)
        } catch (t: Throwable) {
            Log.w(TAG, "AI request threw: " + t.javaClass.simpleName + ": " + t.message)
            AnalysisResult.fallback("请求异常：" + t.message)
        }
    }

    /**
     * Multi-turn follow-up (item 9). Returns the assistant's plain text.
     *
     * @param context the chat segment + original analysis, as the system message
     * @param turns the follow-up conversation so far
     */
    fun followUp(context: String, turns: List<ChatTurn>): String {
        return try {
            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", context)
            })
            for (t in turns) {
                messages.put(JSONObject().apply {
                    put("role", if (t.fromMe) "user" else "assistant")
                    put("content", t.text)
                })
            }
            post(messages) ?: "（无回复）"
        } catch (t: Throwable) {
            Log.w(TAG, "follow-up threw: " + t.javaClass.simpleName + ": " + t.message)
            "请求异常：" + t.message
        }
    }

    private fun buildMessages(userContent: String): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })
    }

    /**
     * ★ v10.3.0：把一条用户消息的 `content` 构造成**多模态数组**（文字 + 若干图片）。
     *
     * ─────────────────────────────────────────────────────────────────────────────
     * 【OpenAI 兼容的多模态格式】
     * 纯文本时 `content` 是一个字符串；要带图片时必须换成一个**数组**，每项形如：
     * ```
     * {"type":"text",      "text":"..."}
     * {"type":"image_url", "image_url":{"url":"data:image/jpeg;base64,<...>"}}
     * ```
     * 图片用 **data URI**（base64 内联）而不是 URL：微信的图片只存在于本机，
     * 没有任何公网地址可以给服务端去抓，所以必须把字节直接塞进请求体。
     *
     * 【为什么把文字放在数组第一项】
     * 多数兼容实现按顺序拼接，文字在前能让模型先拿到上下文（这是哪段对话、要分析什么），
     * 再看图。反过来（图在前）在部分服务端上会让模型先描述图片、
     * 从而答不出"这条消息表达了什么意图"这类与上下文强相关的问题。
     *
     * 【为什么每张图单独 try】
     * 读一张图可能失败（文件不存在、格式不认识、太大被拒）。失败的图**跳过并记日志**，
     * 而不是让整次分析失败 —— 用户宁愿"少看一张图但拿到了分析结果"，
     * 也不愿"因为一张表情包坏了，整条消息分析不出来"。
     */
    private fun buildUserContent(text: String, images: List<ImagePart>): Any {
        if (images.isEmpty()) return text
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("type", "text")
            put("text", text)
        })
        for (img in images) {
            arr.put(JSONObject().apply {
                put("type", "image_url")
                put(
                    "image_url",
                    JSONObject().apply {
                        put("url", "data:" + img.mime + ";base64," + img.base64)
                    },
                )
            })
        }
        return arr
    }

    /**
     * ★ v10.3.0：一次**带图**的分析。
     *
     * 与 [analyzeWithUsage] 唯一的区别是 user 消息的 content 变成多模态数组；
     * system 提示词、返回解析、失败降级全部复用同一条路径，不另写一套。
     *
     * @param images 已编码好的图片（见 [ImagePart]）。为空时自动退化成纯文本请求，
     *               所以调用方不必区分"有图/没图"两种调用方式。
     */
    fun analyzeWithImages(userContent: String, images: List<ImagePart>): Pair<AnalysisResult, AiReply> {
        val reply = chat(buildMessagesWithImages(userContent, images))
        val result = try {
            ResultParser.parse(reply.text)
        } catch (t: Throwable) {
            Log.w(TAG, "AI reply parse threw: " + t.message)
            AnalysisResult.fallback("请求异常：" + t.message)
        }
        return result to reply
    }

    /** [buildMessages] 的多模态版本：同样的两条消息，但 user 的 content 可以是数组。 */
    private fun buildMessagesWithImages(userContent: String, images: List<ImagePart>): JSONArray =
        JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", buildUserContent(userContent, images))
            })
        }

    /**
     * ★ v10.3.0：一张待发送的图片。
     *
     * @param mime     MIME 类型（image/jpeg、image/png、image/gif、image/webp）。
     *                 服务端靠它决定怎么解码，报错会让请求整体失败，所以必须给对。
     * @param base64   图片字节的 base64 编码（**不含** `data:` 前缀，前缀在拼 URL 时加）。
     */
    data class ImagePart(val mime: String, val base64: String)

    /**
     * v10.3.0: **流式**追问。
     *
     * 【为什么单独一个方法而不是给 chat() 加参数】`chat()` 是"取回整段 JSON、读 choices[0].message.content"
     * 的阻塞式实现，与 SSE 的读取模型完全不同（一个是 `body.string()`，另一个是逐行读 `body.source()`）。
     * 把它塞进同一个函数会让两套解析逻辑互相穿插，而这是本工程明令避免的形状。
     *
     * 【为什么 MUST 走挂起而不是回调】调用方（AnalysisController.followUp）需要把每个增量片段
     * 送回主线程刷新卡片；`onDelta` 会在**当前线程**（io 线程池）被回调，由调用方自己 post 到 main，
     * 这样本类不必知道任何 UI 的存在。
     *
     * 【@param onDelta 的契约】只收增量文本，**不是累计文本**。OpenAI 的每个 chunk 给的就是增量
     * (`choices[0].delta.content`)，直接透传最不容易出错；调用方负责累加。
     *
     * 【返回值】完整的助手回答 + 用量。若服务端不支持 stream（少数兼容实现会忽略 stream 参数、
     * 直接返回普通 JSON），这里**会退化成一次性解析**，而不是把整段 JSON 当正文吐给用户 ——
     * 见下面 `sawSse` 的分支。这条退化路径是必需的：不支持流的接口如果按行解析，
     * 用户会在卡片上看到 `{"id":"chatcmpl-...` 这种原始 JSON。
     *
     * NEVER throws: failures come back as `AiReply(null)`.
     */
    fun followUpStreamWithUsage(
        context: String,
        turns: List<ChatTurn>,
        onDelta: (String) -> Unit,
    ): AiReply {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", context)
        })
        for (t in turns) {
            if (t.pending) continue
            messages.put(JSONObject().apply {
                put("role", if (t.fromMe) "user" else "assistant")
                put("content", t.text)
            })
        }

        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.3)
            put("messages", messages)
            put("stream", true)
        }
        val req = Request.Builder()
            .url(endpoint("chat/completions"))
            .header("Authorization", "Bearer " + cfg.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp -> readStream(resp, onDelta) }
        } catch (t: Throwable) {
            Log.w(TAG, "AI stream threw: " + t.javaClass.simpleName + ": " + t.message)
            AiReply(null)
        }
    }

    /**
     * Reads an SSE body, feeding each text delta to [onDelta] and accumulating the answer.
     *
     * The two shapes it must survive:
     *   * **real SSE** -- lines of `data: {...}`, terminated by `data: [DONE]`;
     *   * **a plain JSON reply** -- what a server that ignores `stream:true` sends. Detected because
     *     NO line ever started with `data:`.
     *
     * Empty/comment lines are skipped: SSE uses a leading `:` for comments and servers send blank
     * lines between events.
     */
    private fun readStream(resp: Response, onDelta: (String) -> Unit): AiReply {
        if (!resp.isSuccessful) {
            val body = try {
                resp.body?.string().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            Log.w(TAG, "AI stream failed: HTTP " + resp.code + " " + body.take(200))
            return AiReply(null)
        }
        val source: BufferedSource = resp.body?.source() ?: return AiReply(null)

        val sb = StringBuilder()
        // Every line that was NOT an SSE `data:` line, kept so a server that ignored `stream` can
        // still be parsed as an ordinary completion (see the tail of this function).
        val raw = StringBuilder()
        var sawSse = false
        var promptTokens = 0
        var completionTokens = 0

        while (true) {
            val line = try {
                source.readUtf8Line() ?: break
            } catch (t: Throwable) {
                // A truncated stream still has whatever arrived so far, which is worth keeping --
                // the caller renders a partial answer rather than an error.
                Log.w(TAG, "AI stream read ended early: " + t.message)
                break
            }
            // Only collect for the non-SSE fallback while no `data:` line has been seen yet; once this
            // is a real stream, the raw buffer is dead weight.
            if (!sawSse && !line.startsWith("data:")) raw.append(line)
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break

            val chunk = try {
                JSONObject(data)
            } catch (_: Throwable) {
                continue
            }
            /* ⚠️ 只有解析出**真正的流式 chunk 形状**才承认这是 SSE。
               为什么不能见到 `data:` 就算数：普通 JSON 回答如果被压成一行、而正文里恰好含有
               `data:` 这样的字样（例如模型在回答里写了 "data: xxx"），就会把整段 JSON 误判成流，
               用户最终看到的是原始 JSON 而不是答案。判据必须是"这一行解析后确实有 choices[0].delta"，
               这才是 SSE 与普通正文之间**语义上**的区别，而不是前缀长得像。 */
            val choices = chunk.optJSONArray("choices")
            val deltaObj = choices?.optJSONObject(0)?.optJSONObject("delta")
            if (choices == null || deltaObj == null) continue
            sawSse = true
            // OpenAI reports usage in a final chunk when stream_options.include_usage is set;
            // most compatible servers simply omit it, so these stay 0 and the caller shows no
            // token line rather than a wrong one.
            chunk.optJSONObject("usage")?.let { u ->
                promptTokens = u.optInt("prompt_tokens", promptTokens)
                completionTokens = u.optInt("completion_tokens", completionTokens)
            }
            /* ⛔ 这里曾经是 `deltaObj.optString("content").orEmpty()`，那个写法会让每个空包
               贡献一个字符串 "null"，屏幕上就出现 nullnullnull 刷屏。详见 [textOrNull] 的注释。
               改用 textOrNull 后：JSON null 与键缺失都得到 null，只保留**真正有内容**的包；
               而模型真的回答出「null」这个词时它仍是字符串 "null"，会被原样保留。 */
            val delta = deltaObj.textOrNull("content").orEmpty()
            if (delta.isNotEmpty()) {
                sb.append(delta)
                try {
                    onDelta(delta)
                } catch (t: Throwable) {
                    // A UI failure must not abort the download -- the answer is still wanted.
                    Log.w(TAG, "AI stream delta callback failed: " + t.message)
                }
            }
        }

        if (!sawSse) {
            // The server ignored `stream` and sent a whole completion instead. Parse it exactly like
            // chat() does, so the user gets their answer rather than raw JSON -- and hand it over as a
            // single "delta" so the caller's rendering path stays uniform.
            Log.i(TAG, "AI stream: no SSE, falling back to a plain reply")
            val text = try {
                val root = JSONObject(raw.toString())
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.textOrNull("content")
            } catch (t: Throwable) {
                Log.w(TAG, "AI stream: plain-reply fallback failed: " + t.message)
                null
            }
            val usage = try {
                JSONObject(raw.toString()).optJSONObject("usage")
            } catch (_: Throwable) {
                null
            }
            if (text.isNullOrEmpty()) return AiReply(null)
            try {
                onDelta(text)
            } catch (_: Throwable) {
                // rendering is best effort
            }
            return AiReply(text, usage?.optInt("prompt_tokens", 0) ?: 0, usage?.optInt("completion_tokens", 0) ?: 0)
        }
        Log.i(TAG, "AI stream done: " + sb.length + " char(s), tokens=" + (promptTokens + completionTokens))
        return AiReply(sb.toString().ifBlank { null }, promptTokens, completionTokens)
    }

    /** Shared POST; returns the assistant text or null on HTTP failure. */
    /**
     * 从 JSON 里安全地取一个字符串字段。
     *
     * ─────────────────────────────────────────────────────────────────────────────
     * 【⛔ 绝对不要直接用 optString 取 content —— 这是 Android 自带 JSON 库的一个著名陷阱】
     *
     * `JSONObject.optString("content")` 在遇到 JSON 的 **null 值**时，
     * **返回的是字符串 `"null"`（4 个字符），而不是空串**。
     * 只有键**完全不存在**时它才返回默认值。
     *
     * 流式场景正好踩满这个坑：OpenAI 兼容服务端在**最后一个 chunk** 里发的是
     * ```
     * data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}
     * ```
     * 以及若干 `{"delta":{}}` 形态的空包。用 optString 读它们，每个包都会得到 `"null"`，
     * 于是答案末尾（以及屏幕上）就出现 `nullnullnull...` 一直刷屏 ——
     * 用户看到的原话是「ai 一直输出 nullnull 一直刷屏」。
     *
     * 【为什么这个 bug 特别容易在流式里暴露】
     * 非流式只读一次 content，就算读到 "null" 也只是答案错一次；
     * 流式是**每个包都读一次**，而空包在正常流里成串出现，于是就变成了刷屏级别的噪声。
     *
     * 【判据为什么是 isNull 而不是"等于字符串 null"】
     * 理论上模型完全可以正常回答出「null」这个词，那时 content 是字符串 `"null"`，
     * 是**合法内容**，必须原样保留。只有 `isNull(key)`（真正的 JSON null）才该当空串处理。
     * 用后者去判断会把模型正常的回答吃掉，那是更隐蔽的错误。
     */
    private fun JSONObject.textOrNull(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    /**
     * One request, returning the assistant text and the token usage.
     *
     * This mirrors post() but reads the whole JSON body, because `usage` is a sibling of `choices` and
     * post() returns only the message content.
     */
    private fun chat(messages: JSONArray): AiReply {
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.3)
            put("messages", messages)
        }
        return try {
            val req = Request.Builder()
                .url(endpoint("chat/completions"))
                .header("Authorization", "Bearer " + cfg.apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "AI request failed: HTTP " + resp.code + " " + body.take(200))
                    return AiReply(null)
                }
                val root = JSONObject(body)
                // textOrNull 而不是 optString：见该函数的注释（JSON null ≠ 空串）。
                val text = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.textOrNull("content")
                val usage = root.optJSONObject("usage")
                val prompt = usage?.optInt("prompt_tokens", 0) ?: 0
                val completion = usage?.optInt("completion_tokens", 0) ?: 0
                Log.i(TAG, "AI usage: prompt=" + prompt + " completion=" + completion)
                AiReply(text, prompt, completion)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AI chat threw: " + t.className() + ": " + t.message)
            AiReply(null)
        }
    }

    private fun Throwable.className(): String = javaClass.simpleName

    private fun post(messages: JSONArray): String? {
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.3)
            put("messages", messages)
        }

        val req = Request.Builder()
            .url(endpoint("chat/completions"))
            .header("Authorization", "Bearer " + cfg.apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "AI request failed with HTTP " + resp.code)
                return "请求失败 HTTP " + resp.code + "\n" + body.take(400)
            }
            return JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.textOrNull("content")
        }
    }
}
