package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.core.Log
import org.json.JSONObject
import java.io.File

/**
 * A reusable TEXT-POLISHING template: a name plus the system prompt that turns a draft into something the
 * user can send as-is.
 *
 * This is deliberately NOT the analysis [Template]: an analysis template asks the model to judge the other
 * person and answer with JSON, while a polish template asks the model to REWRITE what the user typed and
 * answer with the finished sentence alone. The two live side by side so neither can disturb the other.
 *
 * @param name         the template name, shown in the picker
 * @param systemPrompt the system prompt sent with the polish request (no JSON, plain text out)
 * @param baseUrl      v10.3.1: this template's own API base URL (empty = fall back to the shared config)
 * @param apiKey       v10.3.1: this template's own API key (empty = fall back to the shared config)
 * @param model        v10.3.1: this template's own model id (empty = fall back to the shared config)
 * @param builtIn      true for the shipped templates, which can be restored after deletion
 */
data class PolishTemplate(
    val name: String,
    val systemPrompt: String = "",
    /**
     * ★ v10.3.1：**文本优化模板自带的 API 三项**，与 [Template] 的同名三项语义完全一致。
     *
     * 【为什么文本优化需要自己的模型/地址/Key】用户明确要求「给文本优化也加上自主选择模型」。
     * 改写的语气（模板）与改写的**算力**本来是两件事，但语气与模型其实是强相关的：
     * 一个专门调过的改写模型往往比主力分析模型更适合改写，而两者常常**不在同一家服务上**。
     * 主配置只有一份地址/Key/模型，逼用户为文本优化去改主配置、改完分析又得改回来，是不可用的。
     *
     * 【为什么留空 = 回落，而不是另加一个「使用独立配置」开关】因为留空本身就是最诚实的表达：
     * 没填就是没配。加一个布尔开关会出现「开关打开但三项全空」这种自相矛盾的状态，
     * 而那种状态在界面上必然要说谎（要么假装用了独立配置，要么假装没开）。
     * 这正是 [Config.visionBaseUrl] 那一组字段已经采用并被验证过的做法 —— 同一问题用同一答案。
     *
     * 三项**各自独立**回落（见 [resolve]），所以「只换模型、地址和 Key 沿用主配置」
     * 或「只换服务商、模型沿用主配置」都能直接表达，不需要用户把三项都抄一遍。
     */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val builtIn: Boolean = false,
)

/**
 * ★ v10.3.1：文本优化一次请求实际要用的设置（模板优先，留空回落主配置）。
 *
 * 与 [Resolved]（分析侧）刻意分成两个类型，理由和 [PolishTemplate] 与 [Template] 分成两个类型一样：
 * 分析侧多出 up/down/rangeHours/blockWords 四项，而文本优化只有这五项。
 * 合成一个类型就得让每一个字段都回答"另一条路径用不用它"，那正是两边互相污染的开始。
 */
data class ResolvedPolish(
    val templateName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 模板的改写提示词。空串表示"没有模板"；文本优化在没有模板时本来就发不出请求。 */
    val systemPrompt: String,
)

/**
 * Persistent store for the polishing templates.
 *
 * Ships with exactly TWO: 恋爱 and 工作. Supports add, rename and delete, and remembers which template was
 * picked last. Written atomically, one JSON file per template, so a kill during a save can never truncate
 * a file and a write can only ever affect the template it belongs to.
 *
 * Layout (same style as [TemplateStore], separate directory so the two stores never collide):
 *
 *     <files>/luoliao/polishes/<name>.json     one template each
 *     <files>/luoliao/polishes/_order.txt      the display order, one safe name per line
 *     <files>/luoliao/polish_selected.txt      the selected template's name
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object PolishTemplateStore {

    private const val TAG = "LuoLiao"

    /** The store's directory under the module app's own files dir. */
    private const val DIR_NAME = "polishes"
    private const val ORDER_FILE = "_order.txt"
    private const val SELECTED_FILE = "polish_selected.txt"

    private const val DEFAULT_NAME_LOVE = "恋爱"
    private const val DEFAULT_NAME_WORK = "工作"

    private var dir: File? = null
    private val items = ArrayList<PolishTemplate>()
    private var loaded = false

    /** The name of the template the next polish should use, or null for "the first one". */
    private var selected: String? = null

    // ------------------------------------------------------------------ attach

    /** Attaches the store to the module app's files dir (the production entry point). */
    fun attachFrom(ctx: Context) {
        attach(File(ctx.filesDir, "luoliao"))
    }

    /**
     * Test seam: the same entry point with the files dir injected, so the on-disk round trip can be
     * exercised by a plain JVM unit test (a real Android Context is not available there).
     */
    fun attachFrom(ctx: Context, filesDir: File) {
        attach(filesDir)
    }

    /** Attaches the store to a base directory (the module app's own files dir). */
    @Synchronized
    fun attach(baseDir: File) {
        dir = File(baseDir, DIR_NAME)
        loaded = false
        load()
    }

    /**
     * Drops every in-memory trace of the previous directory. Only meant for tests, where one JVM runs many
     * cases against different temp dirs; calling it in production would simply force a reload.
     */
    @Synchronized
    fun detach() {
        dir = null
        items.clear()
        selected = null
        loaded = false
    }

    // ------------------------------------------------------------------ reads

    /**
     * Every template, in the stored order.
     *
     * Before [attach] this answers with the two shipped defaults, so a caller that only needs the names
     * (a picker built during an early boot phase) never sees an empty list.
     */
    @Synchronized
    fun all(): List<PolishTemplate> {
        ensureLoaded()
        return items.toList()
    }

    @Synchronized
    fun byName(name: String): PolishTemplate? {
        ensureLoaded()
        return find(name)
    }

    @Synchronized
    fun isEmpty(): Boolean {
        ensureLoaded()
        return items.isEmpty()
    }

    /** The name of the template the next polish should use: the picked one, else the first. */
    @Synchronized
    fun selectedName(): String {
        ensureLoaded()
        selected?.let { n -> if (items.any { it.name == n }) return n }
        return items.firstOrNull()?.name.orEmpty()
    }

    /** The template the next polish should use, or null when the store is empty. */
    @Synchronized
    fun active(): PolishTemplate? {
        ensureLoaded()
        return find(selectedName())
    }

    /**
     * ★ v10.3.1：文本优化这一次请求**实际**要用的地址、Key、模型与提示词。
     *
     * 【为什么要有这个漏斗，而不是调用方各读各的】与 [TemplateStore.resolve] 是同一个理由：
     * 一旦"当前模板"的语义分散在多个调用点，就会出现某一条路径读了模板、另一条读了主配置
     * 的经典分裂（本项目已经为屏蔽词踩过一次：多选路径读了词表、长按路径没读，
     * 表现为"加了词却没生效"）。文本优化目前只有一个请求入口，但这个入口仍然只应该有一个
     * 「我要用什么」的答案来源。
     *
     * 【回落是**逐项**的，不是"有一项为空就整组回落"】
     * 三项各自 `ifBlank` 到主配置，所以：
     *   * 只填模型 → 地址与 Key 用主配置（换模型不换服务商）；
     *   * 只填地址与 Key → 模型用主配置（换服务商不换模型）；
     *   * 三项全空 → 与 v10.3.0 的行为**逐字节一致**（自带模板就是这一档）。
     * 「整组回落」会让"只想换个模型"的用户被迫把地址和 Key 也抄一遍，
     * 而且他抄错一个字符就会静默失败 —— 逐项回落没有这个风险。
     */
    @Synchronized
    fun resolve(cfg: Config): ResolvedPolish {
        val t = active()
        return ResolvedPolish(
            templateName = t?.name.orEmpty(),
            baseUrl = t?.baseUrl?.ifBlank { null } ?: cfg.effectiveBaseUrl,
            apiKey = t?.apiKey?.ifBlank { null } ?: cfg.apiKey,
            model = t?.model?.ifBlank { null } ?: cfg.effectiveModel,
            systemPrompt = t?.systemPrompt.orEmpty(),
        )
    }

    /**
     * ★ v10.3.1：这一次文本优化**是不是用着模板自己的模型**（而不是主配置的）。
     *
     * 【为什么要单独问这一个问题】界面上必须能回答"我改的那一项到底生效了吗"。
     * 用户填了模型名却看到请求仍走主配置时会以为功能坏了 —— 而真实原因可能是
     * 那一次优化用的是**另一个模板**（模板是按名字选中并持久化的，用户很容易改到没在用的那个）。
     * 所以对话框里要显示「来源：模板 / 主配置」，这个判断就是它的依据。
     */
    fun hasOwnApi(t: PolishTemplate?): Boolean =
        t != null && (t.baseUrl.isNotBlank() || t.apiKey.isNotBlank() || t.model.isNotBlank())

    // ------------------------------------------------------------------ writes

    /** Adds a template, or replaces the one that already carries this name. */
    @Synchronized
    fun add(t: PolishTemplate) {
        ensureLoaded()
        val name = t.name.trim()
        if (name.isBlank()) return
        val clean = t.copy(name = name)
        val i = items.indexOfFirst { it.name == name }
        if (i >= 0) {
            items[i] = clean
        } else {
            items.add(clean)
        }
        persist()
        Log.i(TAG, "polish template added: " + name + " (total " + items.size + ")")
    }

    /**
     * Convenience: add a user template by name and prompt.
     *
     * ★ v10.3.1：当**同名模板已经存在**时，它的 API 三项会被保留，而不是被清空。
     *
     * 【为什么必须保留】这个重载只表达"名字 + 提示词"两件事，对 API 三项**没有意见**
     * （调用方根本没传）。旧实现直接构造了一个三项全空的模板，于是任何走这条路的
     * 「只改提示词」都会**静默把用户填好的地址/Key/模型清掉** —— 用户看到的是
     * "我什么都没动，怎么又走回主配置了"。这正是本项目已经踩过的
     * 「加字段时只管写、没管旧值」那一类坑（见 必看.md 的屏蔽词条目）。
     *
     * 「没意见」和「要求清空」是**两种不同的意图**，用一个函数同时表达它们必然出错；
     * 真正要清空时，调用方应该走 [add] 的完整重载并显式传空串。
     */
    @Synchronized
    fun add(name: String, systemPrompt: String = "") {
        val was = find(name.trim())
        add(
            PolishTemplate(
                name = name,
                systemPrompt = systemPrompt,
                // 名字没变的老记录的 API 三项原样带过；没有老记录时为空（= 回落主配置）。
                baseUrl = was?.baseUrl.orEmpty(),
                apiKey = was?.apiKey.orEmpty(),
                model = was?.model.orEmpty(),
            ),
        )
    }

    /**
     * Renames a template, keeping its prompt.
     *
     * The renamed template keeps the slot of the OLD name in the order, and when the new name is already
     * taken the older duplicate is dropped, so two templates can never share a name.
     */
    @Synchronized
    fun rename(oldName: String, newName: String) {
        ensureLoaded()
        val to = newName.trim()
        if (to.isBlank()) return
        val i = items.indexOfFirst { it.name == oldName }
        if (i < 0 || oldName == to) return
        val moved = items[i].copy(name = to)
        items.removeAll { it.name == to }
        val at = items.indexOfFirst { it.name == oldName }
        if (at >= 0) {
            items[at] = moved
        } else {
            items.add(moved)
        }
        if (selected == oldName) {
            selected = to
            persistSelection()
        }
        persist()
        Log.i(TAG, "polish template renamed: " + oldName + " -> " + to)
    }

    @Synchronized
    fun remove(name: String) {
        ensureLoaded()
        if (!items.removeAll { it.name == name }) return
        if (selected == name) {
            selected = null
            persistSelection()
        }
        persist()
        Log.i(TAG, "polish template removed: " + name)
    }

    /**
     * Recreates exactly the two shipped templates, dropping every user template.
     *
     * "Restore defaults" means exactly that: the edited prompts and the added templates are gone.
     */
    @Synchronized
    fun restoreDefaults() {
        ensureLoaded()
        val removed = items.size
        selected = null
        persistSelection()
        items.clear()
        items.addAll(defaults())
        persist()
        Log.i(TAG, "polish template defaults restored: removed " + removed + ", recreated " + items.size)
    }

    /** Picks the template a later polish should use. An unknown name is ignored. */
    @Synchronized
    fun select(name: String) {
        ensureLoaded()
        if (items.any { it.name == name }) {
            selected = name
            persistSelection()
            Log.i(TAG, "polish template selected: " + name)
        }
    }

    /** Clears the store (used by the settings UI when the user deletes everything). */
    @Synchronized
    fun clear() {
        ensureLoaded()
        items.clear()
        selected = null
        persistSelection()
        persist()
    }

    /** The directory the polish template files live in, for display. */
    fun dirPath(): String = try {
        dir?.absolutePath ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    // ------------------------------------------------------------------ built-ins

    /**
     * The TWO templates that ship with the module.
     *
     * Both prompts are OPTIMISING prompts, not analysis prompts: the model receives the user's draft and
     * answers with the finished message only, so the result can be copied straight into the input box.
     * Their voice differs -- 恋爱 sounds like a person who cares, 工作 sounds like a colleague who is
     * polite but leaves nothing on the table.
     */
    fun defaults(): List<PolishTemplate> = listOf(
        PolishTemplate(name = DEFAULT_NAME_LOVE, systemPrompt = LOVE_POLISH_PROMPT, builtIn = true),
        PolishTemplate(name = DEFAULT_NAME_WORK, systemPrompt = WORK_POLISH_PROMPT, builtIn = true),
    )

    // ------------------------------------------------------------------ persistence

    private fun ensureLoaded() {
        if (loaded) return
        load()
    }

    @Synchronized
    private fun load() {
        loaded = true
        val d = dir
        if (d == null) {
            // No directory yet: the shipped templates are what a picker should show.
            items.clear()
            items.addAll(defaults())
            return
        }

        items.clear()
        items.addAll(readAllFiles(d))
        loadSelection()
        if (items.isEmpty() && !hasStoredState(d)) {
            // First run: seed the two built-ins.
            items.addAll(defaults())
            persist()
            Log.i(TAG, "polish templates: seeded " + items.size + " built-in template(s)")
        } else {
            Log.i(TAG, "polish templates: loaded " + items.size + " from " + d.absolutePath)
        }
    }

    /**
     * True once the store has been written at least once.
     *
     * This is what tells "first run" apart from "the user deleted every template": an existing directory
     * must never resurrect the defaults, or a deletion would undo itself on the next start.
     */
    private fun hasStoredState(d: File): Boolean = try {
        d.isDirectory && (File(d, ORDER_FILE).isFile || (d.listFiles()?.any { it.name.endsWith(".json") } == true))
    } catch (_: Throwable) {
        false
    }

    /**
     * Writes the wanted set of files and removes the ones whose template is gone.
     *
     * Writes go through a `.tmp` file first, so a kill mid-write leaves either the old or the new content
     * and never a half-written JSON file.
     */
    private fun persist() {
        val d = dir ?: return
        try {
            d.mkdirs()
            val wanted = HashSet<String>()
            for (tp in items) {
                val safe = safeName(tp.name)
                wanted.add(safe)
                writeAtomic(File(d, safe + ".json"), templateJson(tp).toString())
            }

            // Remove the files of templates that no longer exist.
            try {
                d.listFiles()?.forEach { child ->
                    val n = child.name
                    if (n.endsWith(".json")) {
                        val base = n.removeSuffix(".json")
                        if (!wanted.contains(base)) {
                            if (child.delete()) Log.i(TAG, "polish templates: removed " + n)
                        }
                    }
                }
            } catch (_: Throwable) {
                // best effort
            }

            // The order, so the list stays stable across restarts.
            writeAtomic(File(d, ORDER_FILE), items.joinToString("\n") { safeName(it.name) })

            Log.i(TAG, "polish templates: saved " + items.size + " file(s) in " + d.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "polish templates save failed: " + t.message)
        }
    }

    /** Atomic write: a temporary file in the same directory is renamed onto the target. */
    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "polish template write failed: " + target.name + " (" + t.message + ")")
        }
    }

    private fun persistSelection() {
        val d = dir ?: return
        try {
            d.mkdirs()
            writeAtomic(File(d, SELECTED_FILE), selected.orEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "saving the selected polish template failed: " + t.message)
        }
    }

    private fun loadSelection() {
        val d = dir ?: return
        try {
            val f = File(d, SELECTED_FILE)
            if (f.isFile) selected = f.readText().trim().ifBlank { null }
        } catch (_: Throwable) {
            // the first template is used
        }
    }

    /**
     * A filesystem-safe name for a template ("恋爱" stays readable, separators are escaped).
     *
     * Public so the file naming can be pinned down by a unit test: the name IS the file name, so a change
     * here silently moves every template on disk.
     */
    fun safeName(name: String): String {
        val out = StringBuilder()
        for (ch in name) {
            out.append(
                when (ch) {
                    '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> '_'
                    '\n', '\r', '\t' -> '_'
                    else -> ch
                },
            )
        }
        return out.toString().trim().ifBlank { "polish" }
    }

    /**
     * The on-disk shape of one template.
     *
     * Versioned by absence, not by a field: an older file simply has no key, and every read has a default.
     *
     * ★ v10.3.1 追加了 baseUrl / apiKey / model 三个键。**老文件里没有它们**，
     * [templateFromJson] 的 `optString(key, "")` 于是读到空串 —— 而空串在本模板里就表示
     * 「回落主配置」，也就是改造前的行为。所以**升级不需要任何迁移代码**，
     * 老装机打开后仍然是「用主配置的地址/Key/模型」，不会因为升级而改变行为。
     *
     * ⚠️ 反过来说：`builtIn` 的默认值必须是 **false**、这三个键的默认值必须是 **空串**，
     * 任何"更贴心"的默认（例如给自带模板填上 deepseek 的地址）都会让老文件在
     * **没被用户改过**的情况下改变行为 —— 那一次升级就会被用户感知成"我的配置被动了"。
     */
    fun templateJson(tp: PolishTemplate): JSONObject = JSONObject().apply {
        put("name", tp.name)
        put("systemPrompt", tp.systemPrompt)
        put("baseUrl", tp.baseUrl)
        put("apiKey", tp.apiKey)
        put("model", tp.model)
        put("builtIn", tp.builtIn)
    }

    /** Rebuilds a template from its stored JSON, or null when it carries no usable name. */
    fun templateFromJson(o: JSONObject, fallbackName: String = ""): PolishTemplate? {
        val n = o.optString("name", fallbackName).trim()
        if (n.isBlank()) return null
        return PolishTemplate(
            name = n,
            systemPrompt = o.optString("systemPrompt", ""),
            // 老文件没有这三个键 -> optString 返回 ""（"留空 = 回落主配置"），行为与升级前一致。
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            builtIn = o.optBoolean("builtIn", false),
        )
    }

    /** Reads every template file, ordered by _order.txt when present. */
    private fun readAllFiles(d: File): List<PolishTemplate> {
        val found = HashMap<String, PolishTemplate>()
        try {
            d.listFiles()?.forEach { child ->
                if (!child.name.endsWith(".json")) return@forEach
                try {
                    val o = JSONObject(child.readText())
                    val t = templateFromJson(o, child.name.removeSuffix(".json")) ?: return@forEach
                    found[safeName(t.name)] = t
                } catch (e: Throwable) {
                    Log.w(TAG, "polish template file unreadable: " + child.name + " (" + e.message + ")")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reading polish template files failed: " + e.message)
        }

        val order = ArrayList<String>()
        try {
            val of = File(d, ORDER_FILE)
            if (of.isFile) {
                of.readText().split("\n").forEach { if (it.isNotBlank()) order.add(it.trim()) }
            }
        } catch (_: Throwable) {
            // fall back to the directory order
        }

        val out = ArrayList<PolishTemplate>(found.size)
        for (k in order) {
            found.remove(k)?.let { out.add(it) }
        }
        // Anything the order file does not mention keeps a stable order by name.
        found.keys.sorted().forEach { k -> found[k]?.let { out.add(it) } }
        return out
    }

    private fun find(name: String): PolishTemplate? = items.firstOrNull { it.name == name }

    // ------------------------------------------------------------------ prompts

    /**
     * The romance polishing prompt used by the 恋爱 template.
     *
     * Plain text out, one message, ready to send: no JSON, no explanation, no quoting.
     */
    val LOVE_POLISH_PROMPT: String = """
你是恋爱聊天里最会说话的那个人。用户会给你一段他自己写的草稿：可能太直、太舔、太冲、太啰嗦，或者根本不知道怎么说。你的任务只有一个——把它改成一句可以直接发出去的话。

只输出改写后的内容本身。不要 JSON，不要说明，不要解释你改了什么，不要用引号包起来，不要加任何前后缀，不要出现「优化后」「建议」之类的字眼。

改写要求：
- 保留用户原本的意思和事实，不编造没有发生的事，不加承诺、不加称号、不加他不知道的细节
- 口语，短。一句话最好，最多不超过三行，能一口气说完
- 语气根据语境来：她生气就先安抚再讲事，她撒娇就顺着哄，她冷淡就别追问，只给一个不施压的回应
- 不要油腻，不要土味情话，不要「宝贝」「亲爱的」这类没铺垫的称呼，不要表情符号堆砌，最多一个
- 不要讲道理，不要长篇大论，不要问一连串问题
- 如果用户只是想拒绝、想解释、想道歉，就帮他把话说利落，别替他加戏
""".trimIndent()

    /**
     * The work polishing prompt used by the 工作 template.
     *
     * Plain text out, professional and short: it must read like a colleague, not like a chatbot.
     */
    val WORK_POLISH_PROMPT: String = """
你是职场沟通顾问，专门帮人把工作消息改写得专业、简短、有分寸。用户会给你一段他自己写的草稿，可能太软、太冲、太模糊，或者情绪上头。你的任务只有一个——把它改成一条可以直接发出去的消息。

只输出改写后的内容本身。不要 JSON，不要说明，不要解释你改了什么，不要用引号包起来，不要加任何前后缀，不要出现「优化后」「建议」之类的字眼。

改写要求：
- 保留原有的事实、数字、时间点和诉求，绝不编造、不夸大、不替用户做他没答应的承诺
- 简洁，一般一到四行。先说结论，再说必要的背景，能一句说完就别写两句
- 礼貌但不卑微：该确认的就明确确认，该要时间的就给出一个具体时间，该推进的就给出下一步
- 该留痕的地方要说清楚（谁做什么、什么时候、结果是什么），让这条消息本身就能当凭证
- 该拒绝、该划分责任、该把球传回去的，就直说，不要含糊，也不要道歉过多
- 不用感叹号，不用表情符号，不用网络用语，不加称呼和客套的长铺垫
""".trimIndent()
}
