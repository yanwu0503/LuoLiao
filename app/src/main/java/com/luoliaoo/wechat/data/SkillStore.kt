package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.core.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A Skill: a named instruction block that belongs to ONE analysis template.
 *
 * @param name        the skill name, shown in the list and used as its FOLDER name
 * @param description a one-line summary, stored in the markdown front matter
 * @param content     the markdown BODY (what the front matter describes)
 * @param builtIn     reserved for shipped skills, which can be restored after deletion
 */
data class Skill(
    val name: String,
    val description: String = "",
    val content: String = "",
    val builtIn: Boolean = false,
)

/**
 * The outcome of one import.
 *
 * An import is ALL OR NOTHING: everything is parsed and validated before a single byte is written, so a
 * malformed archive can never leave the existing skills half replaced.
 */
data class SkillImportResult(
    val ok: Boolean,
    val added: Int = 0,
    val message: String = "",
    /** The skills that were read, so a caller can show what it is about to take. */
    val skills: List<Skill> = emptyList(),
)

/**
 * Persistent per-template Skill store, in AGENT-SKILL LAYOUT.
 *
 * A Skill belongs to the template it was created under, and each skill is a FOLDER holding a markdown
 * file -- the same shape an agent skill has, so a skill written for one tool works in the other:
 *
 *     <files>/luoliao/skills/<template>/<skill name>/SKILL.md
 *     <files>/luoliao/skills/<template>/_order.txt          the display order, one folder per line
 *
 * The markdown carries YAML front matter, exactly like the skills people already have on disk:
 *
 *     ---
 *     name: 谈判开场
 *     description: 先问需求
 *     ---
 *
 *     # 正文...
 *
 * WHY A FOLDER PER SKILL: a skill may grow supporting files, and the folder name is what users see in a
 * file manager. Reading is deliberately lenient -- `SKILL.md` is preferred, any other single `.md` in the
 * folder is accepted -- so a hand-made folder still loads.
 *
 * WHY NO IN-MEMORY CACHE: the manager runs both inside WeChat and in the module app, and the two are
 * different processes. Every read goes to disk, so a skill created in one process is visible in the other
 * immediately and a stale cache can never resurrect a deleted skill.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object SkillStore {

    private const val TAG = "LuoLiao"

    /** The store's directory under the module's files dir. */
    private const val DIR_NAME = "skills"
    private const val ORDER_FILE = "_order.txt"

    /** The markdown file inside a skill folder. */
    const val SKILL_FILE = "SKILL.md"

    /** Field limits, enforced on every write AND on every imported entry. */
    const val MAX_NAME = 64
    const val MAX_DESCRIPTION = 200
    const val MAX_CONTENT = 20000

    /** The most skills one import may carry, so a stray huge archive cannot block the UI thread. */
    const val MAX_IMPORT = 200

    /** The most skills one template may hold in total. */
    const val MAX_TOTAL = 200

    /**
     * The largest skills block appended to a template's system prompt, in characters.
     *
     * A Skill is an instruction, not a document store: an unbounded block would eventually exceed the
     * model's context window and make EVERY analysis fail. What does not fit is reported in the block
     * itself, so a truncated prompt is visible instead of silent.
     */
    const val MAX_INSTRUCTIONS = 8000

    /** The largest archive accepted for import. */
    const val MAX_ZIP_BYTES = 8L * 1024 * 1024

    @Volatile
    private var baseDir: File? = null

    // ------------------------------------------------------------------ attach

    /** Attaches the store to the module's files dir (the same one [TemplateStore] uses). */
    @Synchronized
    fun attach(dir: File) {
        baseDir = dir
    }

    /** Convenience for the module app. */
    fun attachFrom(ctx: Context) {
        attach(File(ctx.filesDir, "luoliao"))
    }

    /**
     * Attaches only when nothing is attached yet.
     *
     * The manager can be opened from a process that never called [attach] (the module app's own UI), and an
     * unattached store silently answers "no skills" -- which looks exactly like data loss.
     */
    @Synchronized
    fun ensureAttached(ctx: Context) {
        if (baseDir == null) attachFrom(ctx)
    }

    /** Drops the attachment. Only meant for tests, which run many cases in one JVM. */
    @Synchronized
    fun detach() {
        baseDir = null
    }

    // ------------------------------------------------------------------ paths

    /**
     * A filesystem-safe name ("谈判开场" stays readable, separators are escaped).
     *
     * Public so the naming can be pinned down by a unit test: the safe name IS the folder name, so a change
     * here silently moves every skill on disk.
     */
    fun safeName(name: String, fallback: String = "skill"): String {
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
        // A leading dot would make the skill invisible to a file manager; a trailing dot breaks on Windows.
        return out.toString().trim().trim('.').ifBlank { fallback }
    }

    private fun dirFor(templateName: String): File? {
        val base = baseDir ?: return null
        return File(File(base, DIR_NAME), safeName(templateName, "template"))
    }

    /** The directory one template's skill folders live in, for display. */
    fun dirPath(templateName: String): String = try {
        dirFor(templateName)?.absolutePath ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    // ------------------------------------------------------------------ reads

    /** Every skill of one template, in the stored order. */
    @Synchronized
    fun all(templateName: String): List<Skill> {
        val d = dirFor(templateName) ?: return emptyList()
        val found = HashMap<String, Skill>()
        try {
            d.listFiles()?.forEach { child ->
                if (!child.isDirectory) return@forEach
                try {
                    val s = skillFromFolder(child) ?: return@forEach
                    found[safeName(s.name)] = s
                } catch (e: Throwable) {
                    Log.w(TAG, "skill folder unreadable: " + child.name + " (" + e.message + ")")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reading skill folders failed: " + e.message)
            return emptyList()
        }

        val order = ArrayList<String>()
        try {
            val of = File(d, ORDER_FILE)
            if (of.isFile) {
                of.readText().split("\n").forEach { if (it.isNotBlank()) order.add(it.trim()) }
            }
        } catch (_: Throwable) {
            // fall back to a name order
        }

        val out = ArrayList<Skill>(found.size)
        for (k in order) {
            found.remove(k)?.let { out.add(it) }
        }
        // Anything the order file does not mention keeps a stable order by name.
        found.keys.sorted().forEach { k -> found[k]?.let { out.add(it) } }
        return out
    }

    /** How many skills one template carries -- what the manager shows as "共 N 个". */
    @Synchronized
    fun count(templateName: String): Int = all(templateName).size

    @Synchronized
    fun byName(templateName: String, skillName: String): Skill? =
        all(templateName).firstOrNull { it.name == skillName }

    /**
     * The existing skill that would collide with [name], or null.
     *
     * A collision is either the SAME display name or the same FOLDER name: "a/b" and "a_b" are two names
     * but one folder, so accepting both would silently drop one of them on the next save.
     *
     * @param ignore a name to skip, so editing a skill does not collide with itself
     */
    @Synchronized
    fun conflict(templateName: String, name: String, ignore: String? = null): Skill? {
        val want = name.trim()
        if (want.isEmpty()) return null
        val wantDir = safeName(want)
        return all(templateName).firstOrNull {
            it.name != ignore && (it.name == want || safeName(it.name) == wantDir)
        }
    }

    /**
     * The instruction block a template's skills contribute to its system prompt, or "" when it has none.
     *
     * Appended by [TemplateStore.resolve], which is the single funnel every analysis reads its prompt from
     * -- so attaching a Skill here is what makes one take effect, and there is no second place to keep in
     * step. An empty result leaves the prompt byte-for-byte what it was before, which is what keeps the
     * result cache and every existing analysis unchanged.
     */
    fun instructions(templateName: String): String {
        val all = try {
            all(templateName)
        } catch (t: Throwable) {
            Log.w(TAG, "reading the skills of " + templateName + " failed: " + t.message)
            return ""
        }
        val usable = all.filter { it.name.isNotBlank() && it.content.isNotBlank() }
        if (usable.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n【本模板的 Skill】\n")
        sb.append("以下是本模板附带的补充要求，请与上面的要求一并严格遵循：\n")

        var used = 0
        var included = 0
        for (s in usable) {
            val entry = buildString {
                append("\n## ").append(s.name).append('\n')
                if (s.description.isNotBlank()) append(s.description).append('\n')
                append(s.content.trim()).append('\n')
            }
            if (used + entry.length > MAX_INSTRUCTIONS) break
            sb.append(entry)
            used += entry.length
            included++
        }
        if (included < usable.size) {
            sb.append("\n（其余 ").append(usable.size - included).append(" 个 Skill 因长度上限未包含）\n")
            Log.w(
                TAG,
                "skills block truncated for " + templateName + ": included " + included +
                    " of " + usable.size,
            )
        }
        Log.i(TAG, "skills in effect for " + templateName + ": " + included + " (" + used + " chars)")
        return sb.toString()
    }

    /**
     * The field rules, in ONE place.
     *
     * The create / edit form and the import both call this, so the two can never disagree about what a
     * valid skill is -- and the rules are covered by unit tests instead of living only inside a dialog.
     *
     * @return the reason it is invalid, ready to show to the user, or null when it is fine
     */
    fun validate(name: String, description: String, content: String): String? = when {
        name.isBlank() -> "Skill 名称不能为空"
        name.length > MAX_NAME -> "Skill 名称最多 " + MAX_NAME + " 字"
        description.length > MAX_DESCRIPTION -> "说明最多 " + MAX_DESCRIPTION + " 字"
        content.length > MAX_CONTENT -> "内容最多 " + MAX_CONTENT + " 字"
        else -> null
    }

    // ------------------------------------------------------------------ writes

    /**
     * Adds a skill, or replaces the one that already carries this name.
     *
     * @return false when the name is blank (nothing to file under), when it would share a FOLDER with a
     *         different skill, or when nothing could be written
     */
    @Synchronized
    fun add(templateName: String, skill: Skill): Boolean = save(templateName, null, skill)

    /**
     * Saves a skill, optionally under a NEW name.
     *
     * The edit form can change the name, so this is the one write path for it: [renamedFrom] names the
     * skill being replaced, which keeps its POSITION in the list and lets the old folder be removed in the
     * same pass. Doing it as `add` + `rename` instead would leave TWO skills behind, because the freshly
     * added name is exactly what the rename would then collide with.
     *
     * @param renamedFrom the skill's previous name, or null when this is a plain add / edit
     */
    @Synchronized
    fun save(templateName: String, renamedFrom: String?, skill: Skill): Boolean {
        val name = skill.name.trim()
        if (name.isEmpty()) return false
        // The limits are enforced HERE too, not only in the form: an oversized value must not be able to
        // enter through a path that forgot to check first. The form calls validate() for the MESSAGE.
        if (validate(name, skill.description, skill.content) != null) return false

        val items = all(templateName).toMutableList()
        val clean = skill.copy(name = name)

        // The entry this save REPLACES: the old name when the form renamed it, the name itself otherwise.
        val replacing = if (renamedFrom != null && renamedFrom != name) renamedFrom else name
        val i = items.indexOfFirst { it.name == replacing }

        // The collision check must run even when a replacement was found: renaming B onto A while both
        // exist would otherwise overwrite B's slot with A's name and leave TWO entries called A. The entry
        // being replaced is excluded, because keeping its own name is not a conflict.
        val ignore = if (i >= 0) replacing else null
        if (conflict(templateName, name, ignore = ignore) != null) return false

        if (i >= 0) {
            items[i] = clean
        } else {
            // "a/b" and "a_b" are two display names but ONE folder, so storing both would silently drop
            // one of them on the next save. Refused here, not merely warned about in the UI.
            items.add(clean)
        }
        if (items.size > MAX_TOTAL) return false

        val ok = persist(templateName, items, oldNames = listOfNotNull(ignore.takeIf { it != name }))
        Log.i(
            TAG,
            "skill saved: " + name +
                (if (renamedFrom != null && renamedFrom != name) " (was " + renamedFrom + ")" else "") +
                " (template " + templateName + ", ok=" + ok + ")",
        )
        return ok
    }

    /**
     * Renames a skill, keeping its description, content and position in the list.
     *
     * The caller validates blank / duplicate names first (it is the only side that can SHOW the reason), so
     * a false here means "nothing changed": blank, unknown, duplicate or a failed write.
     */
    @Synchronized
    fun rename(templateName: String, oldName: String, newName: String): Boolean {
        val to = newName.trim()
        if (to.isEmpty() || to == oldName) return false
        if (validate(to, "", "") != null) return false
        val items = all(templateName).toMutableList()
        val i = items.indexOfFirst { it.name == oldName }
        if (i < 0) return false
        if (conflict(templateName, to, ignore = oldName) != null) return false
        items[i] = items[i].copy(name = to)
        val ok = persist(templateName, items, oldNames = listOf(oldName))
        Log.i(TAG, "skill renamed: " + oldName + " -> " + to + " (ok=" + ok + ")")
        return ok
    }

    @Synchronized
    fun remove(templateName: String, skillName: String): Boolean {
        val items = all(templateName).toMutableList()
        if (!items.removeAll { it.name == skillName }) return false
        val ok = persist(templateName, items, oldNames = listOf(skillName))
        Log.i(TAG, "skill removed: " + skillName + " (template " + templateName + ", ok=" + ok + ")")
        return ok
    }

    /**
     * Copies one skill into [templateName], all or nothing.
     *
     * A conflicting name is reported and NOT overwritten, so importing can never silently replace a local
     * skill -- the counterpart of the whole-document rule in the archive importers.
     */
    @Synchronized
    fun importFrom(templateName: String, skill: Skill): SkillImportResult {
        val name = skill.name.trim()
        if (name.isEmpty()) return SkillImportResult(false, message = "导入失败：Skill 名称为空")
        conflict(templateName, name)?.let {
            return SkillImportResult(false, message = "导入失败：本模板已有同名 Skill「" + it.name + "」")
        }
        val ok = add(templateName, skill.copy(name = name, builtIn = false))
        return if (ok) {
            SkillImportResult(
                true,
                added = 1,
                message = "导入成功：新增 1 个（" + name + "）",
                skills = listOf(skill.copy(name = name, builtIn = false)),
            )
        } else {
            SkillImportResult(false, message = "导入失败：写入失败，请检查存储权限")
        }
    }

    /**
     * Copies SEVERAL skills into [templateName] in ONE write.
     *
     * Names that already exist here are SKIPPED and reported, never overwritten: an import from another
     * template is additive. Applying them one by one would leave a partial result if a later write failed,
     * so the merge happens in memory and is saved once.
     */
    @Synchronized
    fun importMany(templateName: String, skills: List<Skill>): SkillImportResult {
        val incoming = skills.filter { it.name.trim().isNotEmpty() }
        if (incoming.isEmpty()) return SkillImportResult(false, message = "导入失败：没有可导入的 Skill")

        val items = all(templateName).toMutableList()
        val addedNames = ArrayList<String>(incoming.size)
        val skippedNames = ArrayList<String>(incoming.size)
        val takenDirs = HashSet(items.map { safeName(it.name) })

        for (s in incoming) {
            val clean = s.copy(name = s.name.trim(), builtIn = false)
            val dir = safeName(clean.name)
            if (items.any { it.name == clean.name } || takenDirs.contains(dir)) {
                skippedNames.add(clean.name)
                continue
            }
            items.add(clean)
            takenDirs.add(dir)
            addedNames.add(clean.name)
        }

        if (addedNames.isEmpty()) {
            return SkillImportResult(
                false,
                message = "导入失败：已存在同名 Skill（" + skippedNames.joinToString("、") + "），未做任何改动",
            )
        }
        if (items.size > MAX_TOTAL) {
            return SkillImportResult(
                false,
                message = "导入失败：模板内 Skill 总数不能超过 " + MAX_TOTAL + " 个（当前 " +
                    all(templateName).size + " 个）",
            )
        }
        if (!persist(templateName, items)) {
            return SkillImportResult(false, message = "导入失败：写入失败，请检查存储权限")
        }
        val msg = buildString {
            append("导入成功：新增 ").append(addedNames.size).append(" 个")
            if (skippedNames.isNotEmpty()) {
                append("；跳过同名 ").append(skippedNames.size).append(" 个：")
                append(skippedNames.joinToString("、"))
            }
        }
        Log.i(
            TAG,
            "skills imported into " + templateName + ": added=" + addedNames.size +
                " skipped=" + skippedNames.size,
        )
        return SkillImportResult(true, added = addedNames.size, message = msg)
    }

    // ------------------------------------------------------------------ markdown

    /**
     * One skill as a markdown document with YAML front matter -- exactly the shape an agent skill uses.
     *
     * The front matter carries the name and the description; the body below it carries the content. Both
     * values are quoted only when they HAVE to be, so the file stays readable when opened by hand.
     */
    fun toMarkdown(s: Skill): String = buildString {
        append("---\n")
        append("name: ").append(yamlScalar(s.name)).append('\n')
        if (s.description.isNotBlank()) {
            append("description: ").append(yamlScalar(s.description)).append('\n')
        }
        append("---\n\n")
        append(s.content.trim().replace("\r\n", "\n"))
        if (!endsWith("\n")) append('\n')
    }

    /**
     * Reads a skill back out of a markdown document.
     *
     * Lenient on purpose: front matter is optional (the file name then supplies the name), a missing
     * description is empty, and CRLF, a BOM or leading blank lines are all tolerated. Skills are written by
     * hand in other tools, so refusing an almost-correct file would make the importer useless.
     *
     * @param fallbackName the name to use when the document has no `name:` (normally the folder name)
     */
    fun fromMarkdown(text: String, fallbackName: String = ""): Skill? {
        val raw = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        var name = ""
        var description = ""
        var body = raw

        val trimmed = raw.trimStart('\n', ' ', '\t')
        if (trimmed.startsWith("---")) {
            val afterOpen = trimmed.substring(3).removePrefix("\n")
            val end = afterOpen.indexOf("\n---")
            if (end >= 0) {
                val front = afterOpen.substring(0, end)
                body = afterOpen.substring(end + 4).removePrefix("\n")
                for (line in front.split("\n")) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val sep = t.indexOf(':')
                    if (sep <= 0) continue
                    val key = t.substring(0, sep).trim().lowercase()
                    val value = unquote(t.substring(sep + 1).trim())
                    when (key) {
                        "name", "title" -> if (name.isEmpty()) name = value
                        "description", "desc", "summary" -> if (description.isEmpty()) description = value
                    }
                }
            }
        }

        val finalName = name.trim().ifBlank { fallbackName.trim() }
        if (finalName.isEmpty()) return null
        return Skill(
            name = finalName,
            description = description.trim().take(MAX_DESCRIPTION),
            content = body.trim('\n').trimEnd(),
        )
    }

    /** Quotes a YAML scalar only when it would otherwise be misread. */
    private fun yamlScalar(v: String): String {
        val needsQuote = v.isBlank() ||
            v != v.trim() ||
            v.startsWith("-") || v.startsWith("?") || v.startsWith(":") ||
            v.any { it == ':' || it == '#' || it == '"' || it == '\'' || it == '\n' }
        if (!needsQuote) return v
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    private fun unquote(v: String): String {
        if (v.length >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            val inner = v.substring(1, v.length - 1)
            return inner.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return v
    }

    // ------------------------------------------------------------------ archive import

    /**
     * Reads skills out of a ZIP archive.
     *
     * TWO layouts are accepted, because both are things users actually have:
     *
     *   1. A SKILL ARCHIVE -- one skill per folder, `<name>/SKILL.md` (what this module exports, and what a
     *      collection of agent skills looks like). Every folder with a markdown file becomes a skill.
     *   2. A SINGLE SKILL -- `SKILL.md` (or one lone `.md`) at the root, with its front matter carrying the
     *      name. This is the "one skill someone sent me" case.
     *
     * ONLY markdown is read: any other file in the archive is ignored, and entries that belong to agent
     * TOOLING rather than to the skill itself (`.agent-teams/`, `.claude/`, `agents/`, `AGENT.md`,
     * MCP/settings files) are skipped -- they describe how a particular agent was configured, not what the
     * skill instructs, and importing them would put unrelated text into the model's prompt.
     *
     * The whole archive is parsed before anything is written, so a bad archive leaves the store untouched.
     */
    fun skillsFromZip(bytes: ByteArray): SkillImportResult {
        if (bytes.isEmpty()) return SkillImportResult(false, message = "导入失败：文件为空")
        if (bytes.size > MAX_ZIP_BYTES) {
            return SkillImportResult(false, message = "导入失败：压缩包过大（上限 8 MB）")
        }

        val mdByFolder = LinkedHashMap<String, String>()
        val rootMd = LinkedHashMap<String, String>()
        var ignored = 0

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                var guard = 0
                while (entry != null && guard < 4000) {
                    guard++
                    val rawName = entry.name.replace('\\', '/')
                    if (entry.isDirectory || shouldSkipEntry(rawName)) {
                        if (!entry.isDirectory) ignored++
                        entry = zip.nextEntry
                        continue
                    }
                    if (!rawName.lowercase().endsWith(".md")) {
                        ignored++
                        entry = zip.nextEntry
                        continue
                    }
                    // Guard against an entry that names itself thousands of levels deep or escapes the root.
                    if (rawName.contains("..") || rawName.length > 300) {
                        ignored++
                        entry = zip.nextEntry
                        continue
                    }
                    val text = readEntry(zip)
                    val parts = rawName.split('/').filter { it.isNotEmpty() }
                    if (parts.size <= 1) {
                        rootMd[rawName] = text
                    } else {
                        // "<folder>/.../SKILL.md" and "<folder>/<anything>.md" both key on the FOLDER.
                        val folder = parts[0]
                        val isSkillFile = parts.last().equals(SKILL_FILE, ignoreCase = true)
                        // Prefer SKILL.md; otherwise keep the first markdown in that folder, so a folder is
                        // never read twice.
                        if (isSkillFile || !mdByFolder.containsKey(folder)) {
                            mdByFolder[folder] = text
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            return SkillImportResult(
                false,
                message = "导入失败：压缩包无法读取（" + (t.message ?: "不是有效的 zip") + "）",
            )
        }

        // Layout 1 wins when it is there; layout 2 covers "a single skill, loose at the root".
        val out = ArrayList<Skill>()
        for ((folder, text) in mdByFolder) {
            val s = fromMarkdown(text, folder)
            if (s != null) out.add(s)
        }
        if (out.isEmpty()) {
            for ((fileName, text) in rootMd) {
                val s = fromMarkdown(text, fileName.removeSuffix(".md"))
                if (s != null) out.add(s)
            }
        }

        if (out.isEmpty()) {
            return SkillImportResult(
                false,
                message = "导入失败：压缩包里没有找到 Skill" +
                    "（需要 <Skill名>/SKILL.md，或根目录一个 .md 文件）" +
                    if (ignored > 0) "；已忽略 $ignored 个无关文件" else "",
            )
        }
        if (out.size > MAX_IMPORT) {
            return SkillImportResult(false, message = "导入失败：一次最多导入 " + MAX_IMPORT + " 个 Skill")
        }
        for (s in out) {
            // The same rules the form uses, so an archive cannot smuggle in a skill the form would refuse.
            validate(s.name, s.description, s.content)?.let {
                return SkillImportResult(false, message = "导入失败：「" + s.name + "」" + it)
            }
        }
        Log.i(TAG, "zip parsed: " + out.size + " skill(s), ignored " + ignored + " unrelated file(s)")
        return SkillImportResult(true, message = "", skills = out)
    }

    /** True for an entry that belongs to agent tooling rather than to the skill itself. */
    fun shouldSkipEntry(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.any { it in AGENT_DIRS }) return true
        val leaf = parts.lastOrNull() ?: return true
        if (leaf in AGENT_FILES) return true
        return false
    }

    /**
     * Directories and files that describe HOW an agent was configured, not what the skill instructs.
     *
     * Importing these would put unrelated text into the model's prompt -- the "agent 相关移除" rule.
     */
    private val AGENT_DIRS = setOf(
        ".agent-teams", ".claude", ".cursor", ".continue", ".aider", ".github",
        "agents", "agent", ".agents", "mcp", ".mcp",
    )

    private val AGENT_FILES = setOf(
        "agent.md", "agents.md", "claude.md", "mcp.json", ".mcp.json",
        "settings.json", "settings.local.json", "config.json", ".gitignore",
    )

    private fun readEntry(zip: ZipInputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = zip.read(chunk)
            if (n <= 0) break
            total += n
            // A single markdown file is capped at the content limit; anything larger is truncated rather
            // than allowed to exhaust memory.
            if (total > MAX_CONTENT * 4) break
            buf.write(chunk, 0, n)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Writes the chosen skills out as ONE zip, in the layout [skillsFromZip] reads back.
     *
     *     <skill name>/SKILL.md
     *
     * Returns the bytes so the caller can hand them to a share sheet or save them; nothing is written to
     * disk here, which keeps the export independent of where it is going.
     */
    fun zipOf(skills: List<Skill>): ByteArray? {
        if (skills.isEmpty()) return null
        return try {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val used = HashSet<String>()
                for (s in skills) {
                    var folder = safeName(s.name)
                    // Two skills can never share a folder: the second would overwrite the first.
                    var n = 2
                    while (used.contains(folder)) {
                        folder = safeName(s.name) + "-" + n
                        n++
                    }
                    used.add(folder)
                    val entry = ZipEntry(folder + "/" + SKILL_FILE)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    zip.write(toMarkdown(s).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "building the skill zip failed: " + t.message)
            null
        }
    }

    // ------------------------------------------------------------------ template lifecycle

    /**
     * Moves a template's skills when the TEMPLATE is renamed.
     *
     * Without this a rename would silently orphan every skill: the new name would show an empty list and
     * the old folders would sit on disk forever.
     */
    @Synchronized
    fun renameTemplate(oldName: String, newName: String): Boolean {
        if (oldName == newName) return false
        val from = dirFor(oldName) ?: return false
        val to = dirFor(newName) ?: return false
        return try {
            if (!from.isDirectory) return false
            if (to.exists()) to.deleteRecursively()
            var ok = from.renameTo(to)
            if (!ok) {
                // A rename across filesystems fails; a copy always works.
                from.copyRecursively(to, overwrite = true)
                from.deleteRecursively()
                ok = to.isDirectory
            }
            Log.i(TAG, "skills moved with the template: " + oldName + " -> " + newName + " ok=" + ok)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "moving the skills of " + oldName + " failed: " + t.message)
            false
        }
    }

    /** Drops a template's skills when the TEMPLATE is deleted, so nothing is orphaned on disk. */
    @Synchronized
    fun removeTemplate(templateName: String): Boolean {
        val d = dirFor(templateName) ?: return false
        return try {
            if (!d.exists()) return false
            val ok = d.deleteRecursively()
            Log.i(TAG, "skills removed with the template: " + templateName + " ok=" + ok)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "removing the skills of " + templateName + " failed: " + t.message)
            false
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes the wanted set of skill folders and removes the ones whose skill is gone.
     *
     * Each markdown file goes through a `.tmp` file first, so a kill mid-write leaves either the old or the
     * new content and never a half-written document.
     *
     * @param oldNames folders to remove even though they are not being rewritten (a rename or a delete)
     */
    private fun persist(
        templateName: String,
        items: List<Skill>,
        oldNames: List<String> = emptyList(),
    ): Boolean {
        val d = dirFor(templateName) ?: return false
        var ok = true
        try {
            d.mkdirs()
            val wanted = HashSet<String>()
            for (s in items) {
                val folder = safeName(s.name)
                wanted.add(folder)
                val dir = File(d, folder)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "skill folder could not be created: " + dir.absolutePath)
                    ok = false
                    continue
                }
                if (!writeAtomic(File(dir, SKILL_FILE), toMarkdown(s))) ok = false
            }

            // Remove what is no longer wanted: the explicit old names plus any folder the order file does
            // not mention. This is what makes a rename leave exactly ONE folder behind.
            val doomed = HashSet<String>(wanted)
            for (n in oldNames) {
                val folder = safeName(n)
                if (!doomed.contains(folder)) {
                    try {
                        if (File(d, folder).deleteRecursively()) Log.i(TAG, "skill folder removed: " + folder)
                    } catch (t: Throwable) {
                        Log.w(TAG, "removing the folder of " + n + " failed: " + t.message)
                    }
                }
            }
            try {
                d.listFiles()?.forEach { child ->
                    if (child.isDirectory && !wanted.contains(child.name)) {
                        if (child.deleteRecursively()) Log.i(TAG, "skill folder removed: " + child.name)
                    }
                }
            } catch (_: Throwable) {
                // best effort
            }

            if (!writeAtomic(File(d, ORDER_FILE), items.joinToString("\n") { safeName(it.name) })) ok = false

            Log.i(TAG, "skills: saved " + items.size + " folder(s) for " + templateName + " in " + d.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "skill save failed: " + t.message)
            ok = false
        }
        return ok
    }

    /** Atomic write: a temporary file in the same directory is renamed onto the target. */
    private fun writeAtomic(target: File, text: String): Boolean = try {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "skill write failed: " + target.name + " (" + t.message + ")")
        false
    }

    /**
     * Reads one skill folder.
     *
     * `SKILL.md` is preferred; any other single markdown file is accepted, so a folder made by hand still
     * loads. A folder with no markdown at all is not a skill.
     */
    private fun skillFromFolder(dir: File): Skill? {
        val files = dir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".md") } ?: return null
        if (files.isEmpty()) return null
        val chosen = files.firstOrNull { it.name.equals(SKILL_FILE, ignoreCase = true) } ?: files.first()
        val text = try {
            chosen.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "markdown unreadable in " + dir.name + ": " + t.message)
            return null
        }
        return fromMarkdown(text, dir.name)
    }
}
