package com.luoliaoo.wechat.ai

import android.util.Base64
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.Config
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ v10.3.0：**识图客户端** —— 把图片变成一段文字，再交给主模型分析。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【它解决的问题：主模型看不到图】
 *
 * 本工程的默认模型是 `deepseek-flash`，一个**纯文本模型**。把图片塞给它：
 *   * 好的情况：服务端**忽略**图片，用户以为在看图，其实模型只看到 `[图片]` 占位符 ——
 *     **静默失效**，最难发现的一种；
 *   * 坏的情况：服务端直接报错，整次分析失败。
 *
 * 所以这里把"看图"和"分析"**拆成两次请求**，中间用文字当桥梁：
 *
 * ```
 *   图片 ──[本文件 / 识图模型]──> 一段图片描述 ──[OpenAiClient / 模板模型]──> 分析结果
 * ```
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【这个拆分解开了三个死结】
 *
 *   1. **模板模型可以是任何模型**，不必支持视觉 —— 它只收到文字，与看不看得见图无关。
 *      用户不必为了用识图功能而换掉自己习惯的模型。
 *   2. **识图结果变成了文字**，所以能存进本地记录、能参与追问、能被重新分析。
 *      "把 base64 图片存在卡片里"既存不下（几十 MB）也没有意义。
 *   3. **识图模型可以独立更换**。哪天有更好的视觉模型，只改这一项，模板一行不动。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么它自己写一套 HTTP，不复用 OpenAiClient】
 *
 * 复用会把两件不同的事绑在一起。具体差异有三处，每一处复用都会出问题：
 *   * **端点不同**：识图模型可能是另一家服务，有自己的 baseUrl 与 key；
 *   * **systemPrompt 不同**：`OpenAiClient` 的 system 是"分析聊天意图并输出 JSON"，
 *     而识图需要的是"如实描述这张图" —— 用错提示词会让视觉模型也去吐分析 JSON；
 *   * **返回值不同**：这里要的是**自由文本**，不是 `AnalysisResult`。
 *
 * 复用还会带出一个隐蔽后果：`OpenAiClient` 的 systemPrompt 会被
 * `HostApp.say()` 做"微信/微信号 → 抖音号"的宿主词替换 —— 对分析提示词是对的，
 * 对"描述这张图"没有意义且会造成不必要的耦合。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【容错立场：识图失败绝不能让分析失败】
 *
 * 图片是**增强**，不是分析的前提。所以本文件的公开入口**永不抛异常**，
 * 失败时返回 null，调用方按"这次没有图片描述"继续走纯文本分析。
 * 宁可用户拿到一份没看图的正常分析，也不要他看到一次失败。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object VisionClient {

    private const val TAG = "LuoLiao"

    /**
     * 识图请求的超时。
     *
     * 比 `OpenAiClient` 的 120 秒短：这是一次"看图说话"，输出通常只有几百字，
     * 而**它挡在主分析前面**——用户要等它完成才能拿到结果。
     * 让它卡两分钟等于让整个分析卡两分钟，那比"这次没看图"糟糕得多。
     */
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 60L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 给识图模型的**指令** —— 由用户选定的**视觉等级**决定（见 `VisionLevel`）。
     *
     * ─────────────────────────────────────────────────────────────────────────────
     * 【为什么这一段不写在这里，而放在 `VisionLevel`】
     * 因为它是**四档**而不是一档，且每一档都要守住同一条底线（只描述、不分析）。
     * 四段提示词放同一个文件里，才能一眼看出"它们只差详细程度、不差规则" ——
     * 若拆开存放，很容易出现某一档悄悄漏掉"不是图片就跳过"这类硬规则。
     * 关于这条底线的完整理由，见 `VisionLevel.prompt` 的文档注释。
     *
     * 【本文件负责的部分】把用户选的等级翻译成提示词、发请求、认 SKIP 标记、包一层说明。
     */
    private fun descriptionPrompt(cfg: Config): String =
        com.luoliaoo.wechat.data.VisionLevel.prompt(cfg.visionLevel)

    /**
     * 用识图模型看一张（或多张）图，返回文字描述。
     *
     * @param images 已编码好的图片。为空时直接返回 null（调用方不必先判断）。
     * @return 描述文字；**任何失败都返回 null**，绝不抛异常。
     */
    fun describe(cfg: Config, images: List<OpenAiClient.ImagePart>): String? {
        if (images.isEmpty()) return null

        // 没配识图模型 = 用户没打算用这个功能，安静返回。
        // 这里不打日志：它是**每个不带图的配置都会走到**的常态路径，打了会刷屏。
        if (!cfg.visionEnabled) return null

        val base = cfg.visionBaseUrlResolved.trim().removeSuffix("/")
        if (base.isEmpty()) {
            Log.w(TAG, "vision: no baseUrl; skipping")
            return null
        }
        val endpoint =
            if (base.endsWith("/chat/completions")) base else base + "/chat/completions"

        return try {
            val body = JSONObject().apply {
                put("model", cfg.visionModel)
                // 不要流式：这一步挡在主分析前面，逐字返回没有意义，
                // 反而要多写一套 SSE 解析。
                put("stream", false)
                put("messages", buildMessages(descriptionPrompt(cfg), images))
            }

            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + cfg.visionApiKeyResolved)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.i(
                TAG,
                "vision: requesting model=" + cfg.visionModel + " level=" +
                    com.luoliaoo.wechat.data.VisionLevel.name(cfg.visionLevel) +
                    " images=" + images.size + " baseUrl=" + base,
            )

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    /* ⚠️ 把 HTTP 状态与响应体一起记下来。这一步失败的原因高度分散：
                       模型名写错(404)、key 不对(401)、服务商不支持视觉(400)、
                       图片太大(413)。只记"识图失败"等于让用户无从下手。 */
                    Log.w(TAG, "vision: HTTP " + resp.code + " body=" + text.take(300))
                    return null
                }
                val out = extractText(text)
                if (out.isNullOrBlank()) {
                    Log.w(TAG, "vision: empty description; body=" + text.take(200))
                    return null
                }
                /* ★ 认"跳过"标记。
                 *
                 * 提示词要求模型在"这不是图片 / 看不到内容"时只回复 SKIP（见
                 * `VisionLevel.SKIP_TOKEN`），这里必须把它当作**没有描述**处理。
                 *
                 * 【不这样做的后果】字符串 "SKIP" 会被当成图片描述拼进主模型提示词，
                 * 主模型于是以为图里写着 "SKIP" —— 一个纯粹的垃圾信息被当成事实。
                 * 这种失败很隐蔽：不报错、不崩溃，只是分析结果莫名其妙地偏了。 */
                if (com.luoliaoo.wechat.data.VisionLevel.isSkip(out)) {
                    Log.i(TAG, "vision: model reported SKIP (not an image / unreadable)")
                    return null
                }
                Log.i(TAG, "vision: description len=" + out.length)
                out
            }
        } catch (t: Throwable) {
            // 网络异常、超时、DNS、TLS……全部归到"这次没看到图"。
            Log.w(TAG, "vision: request failed: " + t.message)
            null
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 构造识图请求的 messages。
     *
     * 结构与 `OpenAiClient.buildUserContent` 完全一致（文字在前、图片在后），
     * 因为它们是同一套 OpenAI 兼容协议 —— 差异只在提示词内容。
     */
    private fun buildMessages(prompt: String, images: List<OpenAiClient.ImagePart>): JSONArray {
        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        for (img in images) {
            content.put(JSONObject().apply {
                put("type", "image_url")
                put(
                    "image_url",
                    JSONObject().apply {
                        put("url", "data:" + img.mime + ";base64," + img.base64)
                    },
                )
            })
        }
        return JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }
    }

    /**
     * 从兼容格式的响应里取出正文。
     *
     * ⚠️【`optString` 的陷阱】与 `OpenAiClient.textOrNull` 是**同一个坑**：
     * 当 JSON 里的值是 `null`（而不是缺少这个键）时，`optString` 返回的是
     * **字符串 `"null"`**（4 个字符），不是默认值。若不判断就会把 `"null"` 当成描述，
     * 于是一句"null"被塞进提示词。必须先 `isNull` 再取。
     */
    private fun extractText(raw: String): String? {
        return try {
            val obj = JSONObject(raw)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
            if (msg.isNull("content")) return null
            val c = msg.optString("content", "")
            c.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "vision: parsing the response failed: " + t.message)
            null
        }
    }

    /**
     * 把一段图片描述**包装**成要交给主模型的文字。
     *
     * 【为什么要有这一步，而不直接把描述拼进聊天记录】
     * 必须让主模型明确知道："下面这段是**图片的内容**，不是对方说的话"。
     * 否则主模型会把描述当成对方发的文字消息，从而在
     * "对方说了什么"这个判断上得出完全错误的结论。
     *
     * 【为什么保留"这不是你说的"这种口语化表述】
     * 主模型的提示词是中文、任务是判断聊天意图，用清晰的短句标注
     * 比用 `[IMAGE_DESCRIPTION]` 这类机器标记更不容易被忽略。
     */
    fun wrapForPrompt(description: String): String =
        "【图片内容（由识图模型识别，不是对方发的文字）】\n" + description.trim()

    /**
     * 把一个图片文件读成 [OpenAiClient.ImagePart]。
     *
     * 放在这里而不是调用方，是为了让"读文件 + 编码"只有一处实现；
     * 失败返回 null（读图是增强，读不到就跳过这张）。
     */
    fun encode(mime: String, bytes: ByteArray): OpenAiClient.ImagePart? = try {
        OpenAiClient.ImagePart(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    } catch (t: Throwable) {
        Log.w(TAG, "vision: encoding failed: " + t.message)
        null
    }
}
