package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.core.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A reusable analysis template: everything an analysis needs, in one named bundle.
 *
 * @param name       the template name, shown in the picker
 * @param baseUrl    API base URL
 * @param apiKey     API key (empty for a built-in; the configured key is kept when empty)
 * @param model      model id
 * @param systemPrompt the system prompt
 */
data class Template(
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    /** item 7: each template owns its own default analysis scope. */
    val defaultUp: Int = 3,
    val defaultDown: Int = 3,
    val defaultRangeHours: Int = 24,
    /**
     * v3.0.3: BLOCK WORDS for this template.
     *
     * A message containing any of these as a CONTIGUOUS substring is dropped from the analysis --
     * "我爱msg你" is blocked, "我m爱s你g" is not. Empty means "use the built-in markers"
     * (PromptBuilder.SKIP_MARKERS: msg / 了解详情 / :0).
     */
    val blockWords: List<String> = emptyList(),
    /** True for the shipped templates, which can be restored after deletion. */
    val builtIn: Boolean = false,
)

/**
 * item 4: the settings an analysis should actually use.
 *
 * A template owns its own address, key, model, prompt and scope. Any field it leaves EMPTY falls back to
 * the shared config, so a shipped template (which has no key) still works with the key the user entered
 * once, while a template that DOES carry its own key uses it exclusively.
 */
data class Resolved(
    val templateName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val up: Int,
    val down: Int,
    val rangeHours: Int,
    /**
     * v6.0.0: the ACTIVE template's block words.
     *
     * They are carried on [Resolved] for the same reason the model and system prompt are: resolve() is
     * the single funnel every analysis reads its settings from, so putting them here is what makes the
     * long-press path see the same list the multi-select path already saw.
     *
     * THE BUG THIS FIXES (user-reported: 「屏蔽关键词失败了 ... msg 有用 auth 跟 click 失败」): the
     * template's words were stored and editable, but the long-press analysis built its prompt through a
     * path that never received them -- so only the hardcoded SKIP_MARKERS ("msg"/"了解详情"/":0") had any
     * effect, and every word the user ADDED appeared to be ignored. Empty means "no extra words", which
     * keeps the old behaviour for any template saved before this field existed.
     */
    val blockWords: List<String> = emptyList(),
)

/**
 * Persistent template store.
 *
 * Ships with 恋爱 and 工作; supports add, rename and delete. Written atomically so a kill during a save
 * cannot truncate the file.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object TemplateStore {

    private const val TAG = "LuoLiao"

    /** item 5: the shipped defaults, shown as placeholders and used on a first run. */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-flash"
    private const val FILE_NAME = "templates.json"

    /**
     * v3.0.3: THE DEFAULT BLOCK WORDS.
     *
     * These are the markers the module has always skipped (PromptBuilder.SKIP_MARKERS). They are now the
     * seed value of every built-in template's 屏蔽 list, so the box in the multi-select UI starts populated
     * with exactly the words the user expects, and they stay editable per template.
     *
     * v6.0.0: "click" and "auth" added on the user's request, after Soul became a host. Soul's chat
     * stream carries machine-generated protocol noise that is not conversation, and those two tokens
     * are the ones the user sees polluting the analysis. They go in the SAME list as the original
     * three, so they behave identically: a plain SUBSTRING test (see PromptBuilder.isBlockedBy), so
     * any line merely CONTAINING "click" or "auth" is dropped -- that is the existing semantics the
     * user chose, not a new rule.
     *
     * CONSEQUENCE worth knowing: because the test is a substring test, an ordinary English sentence
     * like "please click the link" is blocked too. That is consistent with "msg" already blocking
     * "我爱msg你", and the list is per-template editable, so a user who dislikes it can remove them.
     *
     * v8.0.0: the four MEDIA/GRAY-TIP PLACEHOLDERS added on the user's request:
     *     [图片] [表情] [系统提示] [已撤回]
     *
     * WHY THEY BELONG IN THIS LIST. These are not things anybody typed. They are the strings THE
     * MODULE ITSELF substitutes for a non-text message when it has to show something
     * (`QQEnvelope.placeholderForType` -> "[图片]"/"[表情]"/"[系统提示]", and `RECALLED_PLACEHOLDER`
     * -> "[已撤回]"). Without blocking them, a chat full of stickers and images turns into an
     * analysis where the "conversation" is mostly the word [表情] repeated -- the placeholders crowd
     * out the real lines inside the prompt's line budget.
     *
     * Each is written EXACTLY as the module emits it, brackets included. The `[` `]` matter: they
     * are what keeps this from matching an ordinary sentence that merely contains the word 图片.
     */
    val DEFAULT_BLOCK_WORDS: List<String> = listOf(
        "msg", "了解详情", ":0", "click", "auth",
        // The placeholders this module itself writes for non-text rows.
        "[\u56fe\u7247]",      // [图片]
        "[\u8868\u60c5]",      // [表情]
        "[\u6587\u4ef6]",      // [文件]
        "[\u7cfb\u7edf\u63d0\u793a]", // [系统提示]
        "[\u5df2\u64a4\u56de]", // [已撤回]
    )

    private var file: File? = null
    private val items = ArrayList<Template>()
    private var loaded = false

    /** item 4: the currently selected template's name. */
    private var selected: String? = null

    /** Attaches the store to a directory (the module app's own files dir). */
    @Synchronized
    fun attach(dir: File) {
        file = File(dir, FILE_NAME)
        loaded = false
        ensureLoaded()
    }

    /** Convenience for the module app. */
    fun attachFrom(ctx: Context) {
        attach(File(ctx.filesDir, "luoliao"))
    }

    /**
     * Drops every in-memory trace of the previous directory. Only meant for tests, where one JVM runs many
     * cases against different temp dirs; calling it in production would simply force a reload.
     */
    @Synchronized
    fun detach() {
        file = null
        items.clear()
        selected = null
        loaded = false
    }

    // ------------------------------------------------------------------ built-ins

    /**
     * item 5 / 7: the THREE templates that ship with the module.
     *
     *   恋爱  3 / 3 / 24     the original romance analysis
     *   工作  10 / 10 / 24   a wider window, because work threads move slowly
     *   快速  3 / 3 / 24     answer fast above all else
     *
     * The key is left EMPTY on purpose: the shipped templates must never carry a secret, and the config
     * field shows the placeholder 请输入你的密钥 instead.
     */
    fun defaults(): List<Template> = listOf(
        Template(
            name = "恋爱",
            baseUrl = DEFAULT_BASE_URL,
            model = DEFAULT_MODEL,
            systemPrompt = LOVE_PROMPT,
            defaultUp = 3,
            defaultDown = 3,
            defaultRangeHours = 24,
            blockWords = DEFAULT_BLOCK_WORDS,
            builtIn = true,
        ),
        Template(
            name = "工作",
            baseUrl = DEFAULT_BASE_URL,
            model = DEFAULT_MODEL,
            systemPrompt = WORK_PROMPT,
            defaultUp = 10,
            defaultDown = 10,
            defaultRangeHours = 24,
            blockWords = DEFAULT_BLOCK_WORDS,
            builtIn = true,
        ),
        Template(
            name = "快速",
            baseUrl = DEFAULT_BASE_URL,
            model = DEFAULT_MODEL,
            systemPrompt = FAST_PROMPT,
            defaultUp = 3,
            defaultDown = 3,
            defaultRangeHours = 24,
            blockWords = DEFAULT_BLOCK_WORDS,
            builtIn = true,
        ),
    )

    /** Every template, in the stored order. */
    @Synchronized
    fun all(): List<Template> {
        ensureLoaded()
        return items.toList()
    }

    @Synchronized
    fun byName(name: String): Template? {
        ensureLoaded()
        return items.firstOrNull { it.name == name }
    }

    @Synchronized
    fun isEmpty(): Boolean {
        ensureLoaded()
        return items.isEmpty()
    }

    // ------------------------------------------------------------------ writes

    @Synchronized
    fun add(t: Template) {
        ensureLoaded()
        if (t.name.isBlank()) return
        items.removeAll { it.name == t.name }
        items.add(t)
        persist()
        Log.i(TAG, "template added: " + t.name + " (total " + items.size + ")")
    }

    @Synchronized
    fun update(oldName: String, t: Template) {
        ensureLoaded()
        val i = items.indexOfFirst { it.name == oldName }
        if (i >= 0) {
            items[i] = t
        } else {
            items.add(t)
        }
        persist()
        Log.i(TAG, "template updated: " + oldName + " -> " + t.name)
    }

    /** item 3: rename, keeping everything else about the template. */
    @Synchronized
    fun rename(oldName: String, newName: String) {
        ensureLoaded()
        if (newName.isBlank()) return
        val i = items.indexOfFirst { it.name == oldName }
        if (i < 0) return
        items[i] = items[i].copy(name = newName)
        persist()
        Log.i(TAG, "template renamed: " + oldName + " -> " + newName)
    }

    @Synchronized
    fun remove(name: String) {
        ensureLoaded()
        if (items.removeAll { it.name == name }) {
            persist()
            Log.i(TAG, "template removed: " + name)
        }
    }

    /**
     * item 5: 恢复默认 DELETES every template and recreates the two shipped ones.
     *
     * This intentionally clears user templates, their keys and their prompts -- "restore defaults" means
     * exactly that. The old behaviour, which only ADDED a missing built-in, left edited templates and
     * their stale keys in place.
     */
    @Synchronized
    fun restoreDefaults() {
        ensureLoaded()
        val removed = items.size
        items.clear()
        items.addAll(defaults())
        // Write an empty list first so a crash mid-way cannot leave the old keys on disk.
        persist()
        Log.i(TAG, "template defaults restored: removed " + removed +
            ", recreated " + items.size + " (恋爱 / 工作)")
    }

    /** item 8: the directory the template JSON files live in, for display. */
    fun dirPath(): String = try {
        File(file?.parentFile, "templates").absolutePath
    } catch (_: Throwable) {
        "unknown"
    }

    /** item 4: the NAME of the template the next analysis should use. */
    @Synchronized
    fun selectedName(): String {
        ensureLoaded()
        selected?.let { n -> if (items.any { it.name == n }) return n }
        return items.firstOrNull()?.name.orEmpty()
    }

    @Synchronized
    fun select(name: String) {
        ensureLoaded()
        if (items.any { it.name == name }) {
            selected = name
            persistSelection()
            Log.i(TAG, "template selected: " + name)
        }
    }

    /** item 4: the template an analysis should use, with the shared config as a fallback. */
    @Synchronized
    fun active(): Template? {
        ensureLoaded()
        return byName(selectedName())
    }

    private fun persistSelection() {
        val f = file ?: return
        try {
            val sel = java.io.File(f.parentFile, "template_selected.txt")
            val tmp = java.io.File(f.parentFile, sel.name + ".tmp")
            tmp.writeText(selected.orEmpty())
            if (!tmp.renameTo(sel)) {
                sel.writeText(selected.orEmpty())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "saving the selected template failed: " + t.message)
        }
    }

    private fun loadSelection(f: java.io.File) {
        try {
            val sel = java.io.File(f.parentFile, "template_selected.txt")
            if (sel.isFile) selected = sel.readText().trim().ifBlank { null }
        } catch (_: Throwable) {
            // the first template is used
        }
    }

    @Synchronized
    fun clear() {
        items.clear()
        persist()
    }

    // ------------------------------------------------------------- persistence

    /**
     * item 3: repairs the two built-ins when an older build stored the wrong prompt.
     *
     * Both templates once showed the same text because the love prompt was the one saved for each. Any
     * built-in whose stored prompt differs from the SHIPPED prompt is refreshed here, so the fix reaches
     * installs that already have the bad data.
     *
     * v6.0.0: brings an ALREADY-INSTALLED built-in template up to the current default block words.
     *
     * THE BUG THIS FIXES (user, twice: 「屏蔽关键词失败了」 then 「msg有用 click与auth没用」):
     *
     * `DEFAULT_BLOCK_WORDS` is only a SEED. It is written to disk once, when the template files are
     * first created. A phone that already ran an earlier version has a per-template JSON file whose
     * `blockWords` array holds whatever shipped back then -- the original three -- and NOTHING ever
     * updated it:
     *
     *   * this function only refreshed `systemPrompt`,
     *   * the block-word box was REMOVED from the scope dialog (v3.0.3), and
     *   * TemplateDialog never had one.
     *
     * So `DEFAULT_BLOCK_WORDS` could grow ("click", "auth") and still never reach an installed device,
     * with no way for the user to add a word by hand: the word list was unreachable in both directions.
     * That is why "msg" worked (hardcoded in PromptBuilder.SKIP_MARKERS) while every ADDED word did
     * nothing at all.
     *
     * POLICY -- additive, never destructive: any default word the template is MISSING is appended, and
     * the user's own words are kept in place. A word the user deliberately DELETED is not distinguished
     * from one that never arrived (both are simply absent), so it would come back; that is a known and
     * deliberate trade -- re-adding is visible and fixable now that the dialog has an editable field,
     * whereas silently keeping the old list leaves the feature permanently broken.
     */
    private fun migrateBuiltIns() {
        var changed = false
        for (d in defaults()) {
            val i = items.indexOfFirst { it.name == d.name }
            if (i < 0) continue
            val cur = items[i]
            var next = cur
            if (cur.systemPrompt != d.systemPrompt) {
                Log.i(TAG, "template migrate: refreshing the prompt of " + d.name)
                next = next.copy(systemPrompt = d.systemPrompt)
                changed = true
            }
            // v6.0.0: ADD any default block word this template does not have yet, keeping the order the
            // user has and appending the new ones, so an installed device finally sees "click"/"auth".
            val missing = d.blockWords.filterNot { it in cur.blockWords }
            if (missing.isNotEmpty()) {
                Log.i(
                    TAG,
                    "template migrate: adding block word(s) " + missing.joinToString("/") +
                        " to '" + d.name + "' (had " + cur.blockWords.joinToString("/") + ")",
                )
                next = next.copy(blockWords = cur.blockWords + missing)
                changed = true
            }
            if (next !== cur) items[i] = next
        }
        if (changed) persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file
        if (f == null) {
            items.clear()
            items.addAll(defaults())
            return
        }

        val dir = File(f.parentFile, "templates")

        // item 2: migrate a legacy single file the first time.
        if (f.isFile) migrateFromSingleFile(f)

        val loadedItems = readAllFiles(dir)
        items.clear()
        if (loadedItems.isEmpty()) {
            // First run (or everything was deleted): seed the built-ins.
            items.addAll(defaults())
            persist()
            Log.i(TAG, "templates: seeded " + items.size + " built-in template(s)")
        } else {
            items.addAll(loadedItems)
            Log.i(TAG, "templates: loaded " + items.size + " from " + dir.absolutePath)
        }
        loadSelection(f)
        migrateBuiltIns()
    }

    /**
     * item 2: ONE FILE PER TEMPLATE.
     *
     * `templates/<name>.json` holds a single template, and `templates/_order.txt` records the order.
     *
     * The previous single-array file was written from TWO processes (the module app and WeChat), so the
     * two copies could land on top of each other and a template could appear duplicated or overwritten.
     * With a file per template, a write can only ever affect the template it belongs to.
     */
    private fun persist() {
        val f = file ?: return
        try {
            val dir = File(f.parentFile, "templates")
            dir.mkdirs()

            // Write each template to its own file, atomically.
            val wanted = HashSet<String>()
            for (tp in items) {
                val safe = safeName(tp.name)
                wanted.add(safe)
                val target = File(dir, safe + ".json")
                val tmp = File(dir, safe + ".json.tmp")
                tmp.writeText(templateJson(tp).toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(templateJson(tp).toString())
                    tmp.delete()
                }
            }

            // Remove files whose template no longer exists.
            try {
                dir.listFiles()?.forEach { child ->
                    val n = child.name
                    if (n.endsWith(".json")) {
                        val base = n.removeSuffix(".json")
                        if (!wanted.contains(base)) {
                            child.delete()
                            Log.i(TAG, "templates: removed " + n)
                        }
                    }
                }
            } catch (_: Throwable) {
                // best effort
            }

            // The order, so the list is stable.
            try {
                val order = File(dir, "_order.txt")
                val otmp = File(dir, "_order.txt.tmp")
                otmp.writeText(items.joinToString("\n") { safeName(it.name) })
                if (!otmp.renameTo(order)) {
                    order.writeText(items.joinToString("\n") { safeName(it.name) })
                    otmp.delete()
                }
            } catch (_: Throwable) {
                // the order is cosmetic
            }

            Log.i(TAG, "templates: saved " + items.size + " file(s) in " + dir.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "templates save failed: " + t.message)
        }
    }

    /** A filesystem-safe name for a template ("恋爱" stays readable, separators are escaped). */
    private fun safeName(name: String): String {
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
        return out.toString().trim().ifBlank { "template" }
    }

    private fun templateJson(tp: Template): JSONObject = JSONObject().apply {
        put("name", tp.name)
        put("baseUrl", tp.baseUrl)
        put("apiKey", tp.apiKey)
        put("model", tp.model)
        put("systemPrompt", tp.systemPrompt)
        put("defaultUp", tp.defaultUp)
        put("defaultDown", tp.defaultDown)
        put("defaultRangeHours", tp.defaultRangeHours)
        // v3.0.3: the block words, one JSON array entry each.
        put("blockWords", JSONArray().apply { tp.blockWords.forEach { put(it) } })
        put("builtIn", tp.builtIn)
    }

    /** v3.0.3: reads the block words of a template, tolerating older files that have none. */
    private fun readBlockWords(o: JSONObject): List<String> {
        val arr = o.optJSONArray("blockWords") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val w = arr.optString(i, "").trim()
            if (w.isNotEmpty()) out.add(w)
        }
        return out
    }

    /** item 2: reads every template file, ordered by _order.txt when present. */
    private fun readAllFiles(dir: File): List<Template> {
        val found = HashMap<String, Template>()
        try {
            dir.listFiles()?.forEach { child ->
                if (!child.name.endsWith(".json")) return@forEach
                try {
                    val o = JSONObject(child.readText())
                    val n = o.optString("name", child.name.removeSuffix(".json"))
                    if (n.isBlank()) return@forEach
                    found[safeName(n)] = Template(
                        name = n,
                        baseUrl = o.optString("baseUrl", ""),
                        apiKey = o.optString("apiKey", ""),
                        model = o.optString("model", ""),
                        systemPrompt = o.optString("systemPrompt", ""),
                        defaultUp = o.optInt("defaultUp", 3),
                        defaultDown = o.optInt("defaultDown", 3),
                        defaultRangeHours = o.optInt("defaultRangeHours", 24),
                        // v3.0.3: missing (older builds) means "use the built-in markers".
                        blockWords = readBlockWords(o),
                        builtIn = o.optBoolean("builtIn", false),
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "template file unreadable: " + child.name + " (" + e.message + ")")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reading template files failed: " + e.message)
        }

        val order = ArrayList<String>()
        try {
            val of = File(dir, "_order.txt")
            if (of.isFile) {
                of.readText().split("\n").forEach { if (it.isNotBlank()) order.add(it.trim()) }
            }
        } catch (_: Throwable) {
            // fall back to the directory order
        }

        val out = ArrayList<Template>(found.size)
        for (k in order) {
            found.remove(k)?.let { out.add(it) }
        }
        // anything not listed keeps a stable order by name
        found.keys.sorted().forEach { k -> found[k]?.let { out.add(it) } }
        return out
    }

    /** item 2: migrates the old single-file `templates.json` into per-template files, then removes it. */
    private fun migrateFromSingleFile(f: File) {
        try {
            val arr = JSONArray(f.readText())
            val dir = File(f.parentFile, "templates")
            dir.mkdirs()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isBlank()) continue
                val target = File(dir, safeName(name) + ".json")
                if (target.isFile) continue
                target.writeText(o.toString())
                n++
            }
            Log.i(TAG, "templates: migrated " + n + " template(s) to one-file-per-template")
            f.renameTo(File(f.parentFile, "templates.json.migrated"))
        } catch (e: Throwable) {
            Log.w(TAG, "template migration failed: " + e.message)
        }
    }

    /**
     * item 4: resolves the analysis settings from the ACTIVE template.
     *
     * The template wins for every field it defines; the shared `cfg` fills the gaps. Nothing is written
     * back to `cfg`, so templates never overwrite each other.
     */
    @Synchronized
    fun resolve(cfg: Config): Resolved {
        val t = active()
        val name = t?.name.orEmpty()
        return Resolved(
            templateName = name,
            baseUrl = t?.baseUrl?.ifBlank { null } ?: cfg.effectiveBaseUrl,
            apiKey = t?.apiKey?.ifBlank { null } ?: cfg.apiKey,
            model = t?.model?.ifBlank { null } ?: cfg.effectiveModel,
            // v4.1.0: this template's Skills are appended HERE, because resolve() is the single funnel
            // every analysis reads its system prompt from -- so attaching a Skill takes effect everywhere
            // at once, with no second place to keep in step. With no skills the block is empty and the
            // prompt is byte-for-byte what it was before, which is what keeps the result cache and every
            // existing analysis unchanged.
            systemPrompt = (t?.systemPrompt?.ifBlank { null } ?: cfg.systemPrompt) +
                skillsBlock(name),
            up = t?.defaultUp ?: cfg.defaultUp,
            down = t?.defaultDown ?: cfg.defaultDown,
            rangeHours = t?.defaultRangeHours ?: cfg.defaultRangeHours,
            // v6.0.0: without this line the words were never reachable from the long-press path, which
            // is the whole bug. Falls back to the defaults so an un-edited/absent template still filters.
            blockWords = t?.blockWords?.takeIf { it.isNotEmpty() } ?: DEFAULT_BLOCK_WORDS,
        )
    }

    /**
     * The Skill instructions of one template, or "" when it has none or the store cannot be read.
     *
     * Guarded on purpose: a failure to read skills must never take an analysis down with it.
     */
    private fun skillsBlock(templateName: String): String {
        if (templateName.isBlank()) return ""
        return try {
            SkillStore.instructions(templateName)
        } catch (t: Throwable) {
            Log.w(TAG, "reading the skills of " + templateName + " failed: " + t.message)
            ""
        }
    }

    // ------------------------------------------------------------------ prompts

    /** The romance-analysis prompt used by the 恋爱 template. */
    val LOVE_PROMPT: String = """
你是恋爱对话分析师，专门帮人读懂暧昧、试探、冷落、撒娇背后的真实心意。

你现在看到的是两个人的微信聊天记录。请判断对方此刻的真实心理，以及「我」应该怎么回。

只输出如下 JSON，不要输出任何其它文字、不要用 markdown 代码块包裹：
{
  "intent": {
    "question": "把对方这句话改写成恋爱语境下的关键疑问，例如：她是在试探我在不在乎她吗？",
    "options": [
      {"label": "是", "probability": 0-100 整数, "reason": "依据，一句话"},
      {"label": "不是", "probability": 0-100 整数, "reason": "依据，一句话"}
    ]
  },
  "real_intent": [
    {"label": "真实心意，例如：想确认你还在乎她", "probability": 0-100 整数, "reason": "判断依据", "reply": "我可以直接发出去的一句话，温柔、自然、不油腻"}
  ],
  "anger_level": 0-10 整数,
  "comment": "一句话点破现在的局面"
}

判断要点：
- 关注关系信号：回复变短、已读不回、语气词变化、突然客气、突然黏人、开始阴阳怪气
- 关注潜台词：试探、撒娇、求关注、吃醋、赌气、想要道歉、想被哄
- anger_level 是对方的「生气/委屈」程度：0=完全没事，10=非常生气或非常受伤
- real_intent 给 2-4 条，越可能越靠前，概率之和为 100
- reply 要是能直接发的一句话：口语、短、贴合当下气氛。哄就好好哄，别讲道理
- intent.options 概率之和为 100，每一项和每一条 real_intent 都要给 reason
""".trimIndent()
    /**
     * item 7: the 快速 template's prompt.
     *
     * It does not specialise in any domain -- it exists to get an ANSWER FAST, so it forbids the model from
     * deliberating, forbids long output and forbids hedge words.
     */
    val FAST_PROMPT: String = """
你是即时回复助手。你唯一的目标是：用最快的判断给出一句能直接发出去的回复。

只输出如下 JSON，不要输出任何其它文字、不要用 markdown 代码块包裹：
{
  "intent": {
    "question": "对方这句话最直接的意图是什么，一句话",
    "options": [
      {"label": "是", "probability": 0-100 整数, "reason": "一句话"},
      {"label": "不是", "probability": 0-100 整数, "reason": "一句话"}
    ]
  },
  "real_intent": [
    {"label": "意图，6 字以内", "probability": 0-100 整数, "reason": "一句话", "reply": "直接可以发出去的一句话"}
  ],
  "anger_level": 0-10 整数,
  "comment": "一句话"
}

硬性要求：
- 不要分析，不要权衡，不要解释你的思路，直接给结论
- real_intent 只给 1-2 条，越短越好
- reply 必须是 20 字以内的一句话，口语、能直接发
- 每一项的 reason 不超过 15 字
- intent.options 和 real_intent 概率之和分别为 100
""".trimIndent()

    /** A work-context prompt, used by the 工作 template. */
    val WORK_PROMPT: String = """
你是职场沟通顾问，服务的是一名普通职场人。你要读的是同事、上级或客户发来的工作消息，帮他判断对方真正想要什么，以及他该怎么回才不吃亏。

只输出如下 JSON，不要输出任何其它文字、不要用 markdown 代码块包裹：
{
  "intent": {
    "question": "把这条消息改写成职场语境下的关键疑问，例如：对方是在催我承诺一个期限吗？",
    "options": [
      {"label": "是", "probability": 0-100 整数, "reason": "依据，一句话"},
      {"label": "不是", "probability": 0-100 整数, "reason": "依据，一句话"}
    ]
  },
  "real_intent": [
    {"label": "职场诉求，例如：想把延期的责任推给我", "probability": 0-100 整数, "reason": "判断依据", "reply": "我可以直接发出去的专业回复，礼貌但不背锅"}
  ],
  "anger_level": 0-10 整数,
  "comment": "一句话说明他现在最该注意什么"
}

判断要点（这里不分析情绪，只分析事情的走向）：
- 看责任：谁在要什么，是否在甩责任、抢功劳、把风险转给他
- 看期限：是否在挤时间、是否合理、是否需要争取
- 看关系：这是例行同步、私下提醒，还是已经抄送上级在施压
- 看动作：是否需要留痕（书面确认）、是否需要向上汇报、是否可以拖一拖
- anger_level 在这里表示对方的「紧迫或不满」程度：0=例行通知，10=明显问责或施压
- real_intent 给 2-4 条，概率之和为 100
- reply 要专业、简短、有分寸：该确认就确认，该要时间就要时间，该留痕就留痕
- intent.options 概率之和为 100，每一项和每一条 real_intent 都要给 reason
""".trimIndent()
}
