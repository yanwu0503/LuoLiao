package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.Skill
import com.luoliaoo.wechat.data.SkillStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for the per-template Skill store (v4.1.0, md-folder layout).
 *
 * The store is a singleton with no in-memory cache, so every test attaches it to its own temporary files
 * dir. That also exercises the REAL on-disk layout -- `<template>/<skill name>/SKILL.md` plus `_order.txt`
 * -- rather than an in-memory shortcut, and `reload()` is exactly what a process restart does, which is how
 * "刷新页面后数据仍保留" is verified rather than assumed.
 *
 * The markdown round trip and the zip round trip get the same treatment: a skill written to disk is read
 * back THROUGH DISK, so a change that only works in memory cannot pass.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class SkillStoreTest {

    private lateinit var root: File

    private val love = "恋爱"
    private val work = "工作"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("luoliao-skill-test").toFile()
        SkillStore.detach()
        SkillStore.attach(root)
    }

    @After
    fun tearDown() {
        SkillStore.detach()
        root.deleteRecursively()
    }

    /** Re-opens the same directory, which is what a process restart does. */
    private fun reload() {
        SkillStore.detach()
        SkillStore.attach(root)
    }

    private fun names(t: String = "恋爱"): List<String> = SkillStore.all(t).map { it.name }

    private fun templateDir(t: String = "恋爱"): File =
        File(File(root, "skills"), SkillStore.safeName(t))

    private fun files(t: String = "恋爱"): List<String> =
        templateDir(t).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun md(t: String, skill: String): File =
        File(File(templateDir(t), SkillStore.safeName(skill)), SkillStore.SKILL_FILE)

    /** Builds a zip the way another tool would: text entries at the given paths. */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun skillMd(name: String, description: String = "", body: String = ""): String = buildString {
        append("---\n")
        append("name: ").append(name).append('\n')
        if (description.isNotBlank()) append("description: ").append(description).append('\n')
        append("---\n\n")
        append(body)
        append('\n')
    }

    // ------------------------------------------------------------------ empty state

    @Test
    fun aFreshTemplateHasNoSkills() {
        assertEquals(0, SkillStore.count(love))
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun insertingAnEmptyNameIsRefused() {
        assertFalse(SkillStore.add(love, Skill(name = "   ", content = "x")))
        assertEquals(0, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ create

    @Test
    fun createKeepsNameDescriptionAndContent() {
        assertTrue(
            SkillStore.add(love, Skill(name = "谈判开场", description = "先问需求", content = "第一步：…")),
        )
        val s = SkillStore.all(love).single()
        assertEquals("谈判开场", s.name)
        assertEquals("先问需求", s.description)
        assertEquals("第一步：…", s.content)
        assertEquals(1, SkillStore.count(love))
    }

    @Test
    fun createSurvivesAReload() {
        SkillStore.add(love, Skill(name = "谈判开场", description = "d", content = "c"))
        reload()
        assertEquals(listOf("谈判开场"), names())
        assertEquals("c", SkillStore.all(love).single().content)
    }

    @Test
    fun aSkillIsStoredAsAFolderHoldingSkillMd() {
        SkillStore.add(love, Skill(name = "谈判开场", content = "正文"))
        // The layout the user described: 恋爱 folder -> 谈判开场 folder -> SKILL.md
        val f = md(love, "谈判开场")
        assertTrue("expected " + f.absolutePath, f.isFile)
        val text = f.readText()
        assertTrue(text.startsWith("---\n"))
        assertTrue(text.contains("name: 谈判开场"))
        assertTrue(text.contains("正文"))
    }

    @Test
    fun addWithTheSameNameReplacesInsteadOfDuplicating() {
        SkillStore.add(love, Skill(name = "A", content = "one"))
        SkillStore.add(love, Skill(name = "A", content = "two"))
        assertEquals(listOf("A"), names())
        assertEquals("two", SkillStore.all(love).single().content)
    }

    @Test
    fun skillsAreIsolatedPerTemplate() {
        SkillStore.add(love, Skill(name = "A", content = "love"))
        SkillStore.add(work, Skill(name = "B", content = "work"))
        assertEquals(listOf("A"), names(love))
        assertEquals(listOf("B"), names(work))
    }

    @Test
    fun theSameSkillNameCanExistInTwoTemplates() {
        SkillStore.add(love, Skill(name = "A", content = "love"))
        SkillStore.add(work, Skill(name = "A", content = "work"))
        assertEquals("love", SkillStore.all(love).single().content)
        assertEquals("work", SkillStore.all(work).single().content)
    }

    // ------------------------------------------------------------------ edit

    @Test
    fun editingContentKeepsThePositionInTheList() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        SkillStore.add(love, Skill(name = "C", content = "3"))
        SkillStore.save(love, "B", Skill(name = "B", content = "changed"))
        assertEquals(listOf("A", "B", "C"), names())
        assertEquals("changed", SkillStore.byName(love, "B")?.content)
    }

    @Test
    fun savingUnderANewNameFromTheEditFormLeavesExactlyOneSkill() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        val old = SkillStore.byName(love, "B")!!
        // The edit form changes the name in ONE call; add + rename would leave two entries.
        assertTrue(SkillStore.save(love, old.name, old.copy(name = "B2")))
        assertEquals(listOf("A", "B2"), names())
    }

    @Test
    fun savingUnderANewNameSurvivesAReload() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        val old = SkillStore.byName(love, "A")!!
        SkillStore.save(love, old.name, old.copy(name = "A2"))
        reload()
        assertEquals(listOf("A2"), names())
    }

    @Test
    fun savingUnderANameTakenByAnotherSkillIsRefused() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        val b = SkillStore.byName(love, "B")!!
        assertFalse(SkillStore.save(love, b.name, b.copy(name = "A")))
        assertEquals(listOf("A", "B"), names())
    }

    @Test
    fun savingTheSameNameEditsInPlace() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        val a = SkillStore.byName(love, "A")!!
        assertTrue(SkillStore.save(love, a.name, a.copy(content = "2")))
        assertEquals(listOf("A"), names())
        assertEquals("2", SkillStore.all(love).single().content)
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun renameKeepsContentAndPosition() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", description = "d", content = "2"))
        SkillStore.add(love, Skill(name = "C", content = "3"))
        assertTrue(SkillStore.rename(love, "B", "B2"))
        assertEquals(listOf("A", "B2", "C"), names())
        assertEquals("2", SkillStore.byName(love, "B2")?.content)
        assertEquals("d", SkillStore.byName(love, "B2")?.description)
    }

    @Test
    fun renameLeavesNoStaleFolderBehind() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.rename(love, "A", "A2")
        assertEquals(setOf("A2", "_order.txt"), files().toSet())
        assertFalse(md(love, "A").exists())
        assertTrue(md(love, "A2").isFile)
    }

    @Test
    fun renameToABlankNameIsRefused() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        assertFalse(SkillStore.rename(love, "A", "   "))
        assertEquals(listOf("A"), names())
    }

    @Test
    fun renameToAnExistingNameIsRefused() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        assertFalse(SkillStore.rename(love, "B", "A"))
        assertEquals(listOf("A", "B"), names())
    }

    @Test
    fun renameToTheSameNameIsANoOpNotAFailure() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        assertFalse(SkillStore.rename(love, "A", "A"))
        assertEquals(listOf("A"), names())
    }

    @Test
    fun renameOfAnUnknownSkillIsRefused() {
        assertFalse(SkillStore.rename(love, "nope", "x"))
        assertEquals(0, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ conflict

    @Test
    fun conflictSeesTheSameDisplayName() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        assertNotNull(SkillStore.conflict(love, "A"))
        assertNull(SkillStore.conflict(love, "B"))
    }

    @Test
    fun conflictAlsoSeesTwoNamesThatShareOneFolder() {
        // "a/b" and "a_b" are DIFFERENT display names but the SAME folder name, so storing both would
        // silently drop one of them on the next save. The store refuses the second outright.
        assertTrue(SkillStore.add(love, Skill(name = "a/b", content = "1")))
        assertNotNull(SkillStore.conflict(love, "a_b"))
        assertFalse(SkillStore.add(love, Skill(name = "a_b", content = "2")))
        assertEquals(1, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deleteRemovesTheFolderToo() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        assertTrue(SkillStore.remove(love, "A"))
        assertEquals(listOf("B"), names())
        assertFalse(File(templateDir(love), SkillStore.safeName("A")).exists())
    }

    @Test
    fun deleteOfAnUnknownSkillReportsFailureAndChangesNothing() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        assertFalse(SkillStore.remove(love, "nope"))
        assertEquals(listOf("A"), names())
    }

    @Test
    fun deletingEverySkillLeavesAnEmptyListThatStaysEmpty() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.remove(love, "A")
        reload()
        assertEquals(emptyList<String>(), names())
        assertEquals(0, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ markdown

    @Test
    fun markdownCarriesTheFrontMatterAndTheBody() {
        val text = SkillStore.toMarkdown(Skill(name = "N", description = "D", content = "line1\nline2"))
        assertTrue(text.startsWith("---\nname: N\ndescription: D\n---\n\n"))
        assertTrue(text.contains("line1\nline2"))
    }

    @Test
    fun markdownRoundTripsThroughText() {
        val original = Skill(name = "谈判开场", description = "先问需求", content = "第一行\n第二行")
        val back = SkillStore.fromMarkdown(SkillStore.toMarkdown(original), "fallback")
        assertEquals(original.name, back?.name)
        assertEquals(original.description, back?.description)
        assertEquals(original.content, back?.content)
    }

    @Test
    fun markdownWithoutFrontMatterFallsBackToTheFolderName() {
        // A folder made by hand, with no YAML at all, must still load.
        val back = SkillStore.fromMarkdown("# 标题\n\n正文", "手写的Skill")
        assertEquals("手写的Skill", back?.name)
        assertEquals("", back?.description)
        assertTrue(back!!.content.contains("正文"))
    }

    @Test
    fun markdownToleratesCrlfAndABom() {
        val text = "\uFEFF---\r\nname: A\r\n---\r\n\r\nbody\r\n"
        val back = SkillStore.fromMarkdown(text, "x")
        assertEquals("A", back?.name)
        assertEquals("body", back?.content)
    }

    @Test
    fun markdownQuotesOnlyWhatNeedsQuoting() {
        val plain = SkillStore.toMarkdown(Skill(name = "简单名", content = "x"))
        assertTrue(plain.contains("name: 简单名"))
        val tricky = SkillStore.toMarkdown(Skill(name = "带:冒号", content = "x"))
        assertTrue(tricky.contains("name: \"带:冒号\""))
    }

    @Test
    fun aFolderWithNoMarkdownIsNotASkill() {
        val d = File(templateDir(love), "空文件夹")
        d.mkdirs()
        File(d, "readme.txt").writeText("hi")
        assertEquals(0, SkillStore.count(love))
    }

    @Test
    fun aFolderWithANonStandardMarkdownFileStillLoads() {
        val d = File(templateDir(love), "手写")
        d.mkdirs()
        File(d, "note.md").writeText(skillMd("手写", body = "内容"))
        reload()
        assertEquals(listOf("手写"), names())
    }

    @Test
    fun skillMdWinsOverAnotherMarkdownInTheSameFolder() {
        val d = File(templateDir(love), "双文件")
        d.mkdirs()
        File(d, "other.md").writeText(skillMd("错误", body = "wrong"))
        File(d, SkillStore.SKILL_FILE).writeText(skillMd("正确", body = "right"))
        reload()
        assertEquals(listOf("正确"), names())
    }

    // ------------------------------------------------------------------ zip import

    @Test
    fun zipImportReadsOneSkillPerFolder() {
        val bytes = zipOf(
            "谈判开场/SKILL.md" to skillMd("谈判开场", "先问需求", "第一步"),
            "收尾/SKILL.md" to skillMd("收尾", "", "最后一步"),
        )
        val res = SkillStore.skillsFromZip(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(2, res.skills.size)
        assertEquals(setOf("谈判开场", "收尾"), res.skills.map { it.name }.toSet())
        assertEquals("第一步", res.skills.first { it.name == "谈判开场" }.content)
    }

    @Test
    fun zipImportReadsASingleSkillLooseAtTheRoot() {
        // The example archive: one SKILL.md at the top level, name supplied by its front matter.
        val bytes = zipOf("SKILL.md" to skillMd("github-private-backup", "备份", "正文"))
        val res = SkillStore.skillsFromZip(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(1, res.skills.size)
        assertEquals("github-private-backup", res.skills.single().name)
    }

    @Test
    fun zipImportFallsBackToTheFileNameWhenTheRootFileHasNoFrontMatter() {
        val bytes = zipOf("my-skill.md" to "just a body")
        val res = SkillStore.skillsFromZip(bytes)
        assertTrue(res.message, res.ok)
        assertEquals("my-skill", res.skills.single().name)
    }

    @Test
    fun zipImportIgnoresNonMarkdownFiles() {
        val bytes = zipOf(
            "A/SKILL.md" to skillMd("A", body = "x"),
            "A/notes.txt" to "irrelevant",
            "A/script.py" to "print(1)",
            "LICENSE" to "MIT",
        )
        val res = SkillStore.skillsFromZip(bytes)
        assertEquals(1, res.skills.size)
        assertEquals("A", res.skills.single().name)
    }

    @Test
    fun zipImportSkipsAgentTooling() {
        // The "agent 相关移除" rule: how an agent was CONFIGURED is not part of what a skill instructs.
        val bytes = zipOf(
            "A/SKILL.md" to skillMd("A", body = "keep me"),
            ".agent-teams/config.json" to "{\"x\":1}",
            "agents/reviewer.md" to skillMd("reviewer", body = "not a skill"),
            "A/AGENT.md" to skillMd("agent-file", body = "not a skill"),
            ".claude/settings.json" to "{}",
            "mcp.json" to "{}",
            "CLAUDE.md" to skillMd("claude", body = "not a skill"),
        )
        val res = SkillStore.skillsFromZip(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(listOf("A"), res.skills.map { it.name })
        assertTrue(res.skills.single().content.contains("keep me"))
    }

    @Test
    fun zipImportSkipsATraversalEntry() {
        val bytes = zipOf(
            "../../etc/passwd.md" to "evil",
            "A/SKILL.md" to skillMd("A", body = "good"),
        )
        val res = SkillStore.skillsFromZip(bytes)
        assertEquals(listOf("A"), res.skills.map { it.name })
    }

    @Test
    fun zipImportOfSomethingThatIsNotAZipIsRefused() {
        val res = SkillStore.skillsFromZip("definitely not a zip".toByteArray())
        assertFalse(res.ok)
        assertTrue(res.message, res.skills.isEmpty())
    }

    @Test
    fun zipImportOfAnEmptyFileIsRefused() {
        assertFalse(SkillStore.skillsFromZip(ByteArray(0)).ok)
    }

    @Test
    fun aZipWithNoMarkdownIsRefusedWithAReadableReason() {
        val res = SkillStore.skillsFromZip(zipOf("readme.txt" to "hi", "a/b.json" to "{}"))
        assertFalse(res.ok)
        assertTrue(res.message, res.message.contains("没有找到 Skill"))
    }

    @Test
    fun zipImportOfAnOverlongNameIsRefusedBeforeAnythingIsWritten() {
        val long = "x".repeat(SkillStore.MAX_NAME + 1)
        val res = SkillStore.skillsFromZip(zipOf("$long/SKILL.md" to skillMd(long, body = "y")))
        assertFalse(res.ok)
        assertTrue(res.message, res.message.contains("最多"))
    }

    @Test
    fun shouldSkipEntryRecognisesAgentPaths() {
        assertTrue(SkillStore.shouldSkipEntry(".agent-teams/team.json"))
        assertTrue(SkillStore.shouldSkipEntry("AGENT.md"))
        assertTrue(SkillStore.shouldSkipEntry("a/agents/x.md"))
        assertTrue(SkillStore.shouldSkipEntry("mcp.json"))
        assertFalse(SkillStore.shouldSkipEntry("谈判开场/SKILL.md"))
        assertFalse(SkillStore.shouldSkipEntry("SKILL.md"))
    }

    // ------------------------------------------------------------------ zip export

    @Test
    fun zipExportUsesTheSameLayoutTheImporterReads() {
        val skills = listOf(
            Skill(name = "A", description = "da", content = "ca"),
            Skill(name = "B", description = "", content = "cb"),
        )
        val bytes = SkillStore.zipOf(skills)
        assertNotNull(bytes)

        // Round trip through the archive: this is the whole point of "导出为 zip 且导入支持该 zip".
        val back = SkillStore.skillsFromZip(bytes!!)
        assertTrue(back.message, back.ok)
        assertEquals(listOf("A", "B"), back.skills.map { it.name })
        assertEquals("ca", back.skills[0].content)
        assertEquals("da", back.skills[0].description)
        assertEquals("cb", back.skills[1].content)
    }

    @Test
    fun zipExportOfNothingIsNull() {
        assertNull(SkillStore.zipOf(emptyList()))
    }

    @Test
    fun zipExportDoesNotCollideTwoSkillsThatShareAFolderName() {
        // safeName folds separators, so two different names can map to one folder. Both must still come
        // back, which means the second gets a distinct folder rather than overwriting the first.
        val back = SkillStore.skillsFromZip(
            SkillStore.zipOf(
                listOf(Skill(name = "a/b", content = "one"), Skill(name = "a_b", content = "two")),
            )!!,
        )
        assertTrue(back.message, back.ok)
        assertEquals(2, back.skills.size)
        assertEquals(setOf("one", "two"), back.skills.map { it.content }.toSet())
    }

    @Test
    fun aFullExportImportRoundTripThroughDiskKeepsEverything() {
        SkillStore.add(love, Skill(name = "A", description = "da", content = "第一行\n第二行"))
        SkillStore.add(love, Skill(name = "B", description = "", content = "body b"))

        val bytes = SkillStore.zipOf(SkillStore.all(love))!!

        // Land it in ANOTHER template, as a file would be carried to another user.
        val res = SkillStore.importMany(work, SkillStore.skillsFromZip(bytes).skills)
        assertTrue(res.message, res.ok)
        assertEquals(listOf("A", "B"), names(work))
        assertEquals("第一行\n第二行", SkillStore.byName(work, "A")?.content)
        assertEquals("da", SkillStore.byName(work, "A")?.description)

        reload()
        assertEquals(listOf("A", "B"), names(work))
    }

    // ------------------------------------------------------------------ importFrom

    @Test
    fun importFromAnotherTemplateCopiesTheSkill() {
        SkillStore.add(love, Skill(name = "A", description = "d", content = "c"))
        val res = SkillStore.importFrom(work, SkillStore.all(love).single())
        assertTrue(res.message, res.ok)
        assertEquals(1, res.added)
        assertEquals(listOf("A"), names(work))
        assertEquals("c", SkillStore.all(work).single().content)
    }

    @Test
    fun importFromAnotherTemplateRefusesAConflictingName() {
        SkillStore.add(love, Skill(name = "A", content = "from love"))
        SkillStore.add(work, Skill(name = "A", content = "local"))
        val res = SkillStore.importFrom(work, SkillStore.all(love).single())
        assertFalse(res.ok)
        // The local skill is untouched: a copy must never silently replace one.
        assertEquals("local", SkillStore.all(work).single().content)
    }

    // ------------------------------------------------------------------ importMany

    @Test
    fun importManyAddsEveryNewSkill() {
        val res = SkillStore.importMany(
            work,
            listOf(Skill(name = "A", content = "1"), Skill(name = "B", content = "2")),
        )
        assertTrue(res.message, res.ok)
        assertEquals(2, res.added)
        assertEquals(listOf("A", "B"), names(work))
    }

    @Test
    fun importManySkipsConflictingNamesAndReportsThem() {
        SkillStore.add(work, Skill(name = "A", content = "local"))
        val res = SkillStore.importMany(
            work,
            listOf(Skill(name = "A", content = "incoming"), Skill(name = "B", content = "new")),
        )
        assertTrue(res.message, res.ok)
        assertEquals(1, res.added)
        assertEquals(listOf("A", "B"), names(work))
        // Skipped, NOT overwritten.
        assertEquals("local", SkillStore.byName(work, "A")?.content)
        assertTrue(res.message, res.message.contains("跳过同名"))
    }

    @Test
    fun importManyWithEverythingConflictingChangesNothing() {
        SkillStore.add(work, Skill(name = "A", content = "local"))
        val res = SkillStore.importMany(work, listOf(Skill(name = "A", content = "incoming")))
        assertFalse(res.ok)
        assertEquals("local", SkillStore.all(work).single().content)
    }

    @Test
    fun importManySkipsTwoSourcesThatShareOneFolder() {
        val res = SkillStore.importMany(
            love,
            listOf(Skill(name = "a/b", content = "1"), Skill(name = "a_b", content = "2")),
        )
        assertTrue(res.message, res.ok)
        assertEquals(1, res.added)
        assertEquals(1, SkillStore.count(love))
    }

    @Test
    fun importManyOfAnEmptyListIsRefused() {
        assertFalse(SkillStore.importMany(love, emptyList()).ok)
    }

    @Test
    fun importManySurvivesAReload() {
        SkillStore.importMany(work, listOf(Skill(name = "A", content = "1")))
        reload()
        assertEquals(listOf("A"), names(work))
    }

    @Test
    fun theTotalIsCapped() {
        val many = (0 until SkillStore.MAX_TOTAL + 5).map { Skill(name = "s$it", content = "c") }
        val res = SkillStore.importMany(love, many)
        assertFalse(res.ok)
        assertEquals(0, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ template lifecycle

    @Test
    fun renamingTheTemplateMovesItsSkills() {
        SkillStore.add(love, Skill(name = "A", content = "c"))
        assertTrue(SkillStore.renameTemplate(love, "爱情"))
        assertEquals(listOf("A"), names("爱情"))
        assertEquals(0, SkillStore.count(love))
        assertEquals("c", SkillStore.all("爱情").single().content)
    }

    @Test
    fun deletingTheTemplateDropsItsSkills() {
        SkillStore.add(love, Skill(name = "A", content = "c"))
        assertTrue(SkillStore.removeTemplate(love))
        assertEquals(0, SkillStore.count(love))
        assertFalse(templateDir(love).exists())
    }

    @Test
    fun theSkillsAreFiledUnderTheTemplateName() {
        SkillStore.add(love, Skill(name = "A", content = "c"))
        assertTrue(SkillStore.dirPath(love).endsWith(SkillStore.safeName(love)))
        assertTrue(templateDir(love).isDirectory)
    }

    // ------------------------------------------------------------------ safeName

    @Test
    fun safeNameEscapesSeparatorsAndFallsBack() {
        assertEquals("a_b", SkillStore.safeName("a/b"))
        assertEquals("a_b", SkillStore.safeName("a\\b"))
        assertEquals("a_b", SkillStore.safeName("a:b"))
        assertEquals("skill", SkillStore.safeName("   "))
        assertEquals("x", SkillStore.safeName("x."))
    }

    @Test
    fun aNameWithASeparatorStillRoundTrips() {
        assertTrue(SkillStore.add(love, Skill(name = "a/b", content = "c")))
        reload()
        assertEquals(listOf("a/b"), names())
    }

    // ------------------------------------------------------------------ file layout

    @Test
    fun oneFolderPerSkillPlusTheOrderFile() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        assertEquals(setOf("A", "B", "_order.txt"), files().toSet())
        assertTrue(md(love, "A").isFile)
        assertTrue(md(love, "B").isFile)
    }

    @Test
    fun theOrderFileDrivesTheDisplayOrder() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        SkillStore.add(love, Skill(name = "B", content = "2"))
        SkillStore.add(love, Skill(name = "C", content = "3"))
        File(templateDir(love), "_order.txt").writeText("C\nA\nB")
        reload()
        assertEquals(listOf("C", "A", "B"), names())
    }

    @Test
    fun aSkillMissingFromTheOrderFileIsStillListed() {
        SkillStore.add(love, Skill(name = "A", content = "1"))
        File(templateDir(love), "_order.txt").writeText("")
        reload()
        assertEquals(listOf("A"), names())
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun theFieldRulesAreSharedByTheFormAndTheImporter() {
        assertNull(SkillStore.validate("A", "", ""))
        assertNotNull(SkillStore.validate("", "", ""))
        assertNotNull(SkillStore.validate("x".repeat(SkillStore.MAX_NAME + 1), "", ""))
        assertNotNull(SkillStore.validate("A", "x".repeat(SkillStore.MAX_DESCRIPTION + 1), ""))
        assertNotNull(SkillStore.validate("A", "", "x".repeat(SkillStore.MAX_CONTENT + 1)))
    }

    @Test
    fun theStoreRefusesAnOversizedValueEvenWhenTheFormDidNot() {
        // The form calls validate() for the MESSAGE, but the store must not depend on it: an oversized
        // value must not be able to enter through a path that forgot to check.
        assertFalse(SkillStore.add(love, Skill(name = "A", content = "x".repeat(SkillStore.MAX_CONTENT + 1))))
        assertEquals(0, SkillStore.count(love))
        assertFalse(SkillStore.add(love, Skill(name = "x".repeat(SkillStore.MAX_NAME + 1), content = "c")))
        assertEquals(0, SkillStore.count(love))
    }

    // ------------------------------------------------------------------ prompt block

    @Test
    fun aTemplateWithoutSkillsAddsNothingToThePrompt() {
        assertEquals("", SkillStore.instructions(love))
    }

    @Test
    fun aSkillWithNoContentAddsNothingToThePrompt() {
        SkillStore.add(love, Skill(name = "A", content = "  "))
        assertEquals("", SkillStore.instructions(love))
    }

    @Test
    fun aSkillWithContentIsWrittenIntoThePromptBlock() {
        SkillStore.add(love, Skill(name = "谈判开场", description = "先问需求", content = "第一步"))
        val block = SkillStore.instructions(love)
        assertTrue(block.contains("【本模板的 Skill】"))
        assertTrue(block.contains("## 谈判开场"))
        assertTrue(block.contains("先问需求"))
        assertTrue(block.contains("第一步"))
    }

    @Test
    fun theBlockOnlyCarriesThisTemplatesSkills() {
        SkillStore.add(love, Skill(name = "A", content = "love-body"))
        SkillStore.add(work, Skill(name = "B", content = "work-body"))
        val block = SkillStore.instructions(love)
        assertTrue(block.contains("love-body"))
        assertFalse(block.contains("work-body"))
    }

    @Test
    fun resolvePutsTheSkillsIntoTheSystemPromptAndRemovingThemRestoresIt() {
        com.luoliaoo.wechat.data.TemplateStore.attach(root)
        try {
            val t = com.luoliaoo.wechat.data.Template(
                name = love,
                systemPrompt = "基础提示词",
            )
            com.luoliaoo.wechat.data.TemplateStore.add(t)
            com.luoliaoo.wechat.data.TemplateStore.select(love)

            val before = com.luoliaoo.wechat.data.TemplateStore.resolve(
                com.luoliaoo.wechat.data.Config(),
            ).systemPrompt
            assertFalse(before.contains("【本模板的 Skill】"))

            SkillStore.add(love, Skill(name = "A", content = "必须用敬语"))
            val during = com.luoliaoo.wechat.data.TemplateStore.resolve(
                com.luoliaoo.wechat.data.Config(),
            ).systemPrompt
            assertTrue(during.contains("基础提示词"))
            assertTrue(during.contains("必须用敬语"))

            SkillStore.remove(love, "A")
            val after = com.luoliaoo.wechat.data.TemplateStore.resolve(
                com.luoliaoo.wechat.data.Config(),
            ).systemPrompt
            assertEquals(before, after)
        } finally {
            com.luoliaoo.wechat.data.TemplateStore.detach()
        }
    }

    @Test
    fun thePromptBlockIsCappedAndSaysSo() {
        val big = "x".repeat(4000)
        SkillStore.add(love, Skill(name = "A", content = big))
        SkillStore.add(love, Skill(name = "B", content = big))
        SkillStore.add(love, Skill(name = "C", content = big))
        val block = SkillStore.instructions(love)
        assertTrue(block.length < SkillStore.MAX_INSTRUCTIONS + 400)
        assertTrue("expected a truncation note", block.contains("未包含"))
    }

    @Test
    fun theCapStillIncludesWhatFits() {
        // 3500 each: the block header plus TWO entries fit under the 8000 cap, the third does not.
        val big = "x".repeat(3500)
        SkillStore.add(love, Skill(name = "A", content = big))
        SkillStore.add(love, Skill(name = "B", content = big))
        SkillStore.add(love, Skill(name = "C", content = big))
        val block = SkillStore.instructions(love)
        assertTrue(block.contains("## A"))
        assertTrue(block.contains("## B"))
        assertFalse(block.contains("## C"))
    }

    // ------------------------------------------------------------------ acceptance

    /**
     * The whole acceptance sequence of the feature, in one test.
     *
     * Every step is followed by a reload, so an operation that only appears to work (because a cached list
     * still holds the old data) fails here instead of silently passing.
     */
    @Test
    fun theFiveOperationsAllTakeEffectAndSurviveARestart() {
        // 1. entering the page: an empty list, not an error
        assertEquals(0, SkillStore.count(love))

        // 2. create
        assertTrue(SkillStore.add(love, Skill(name = "甲", description = "第一个", content = "第一")))
        reload()
        assertEquals(1, SkillStore.count(love))
        assertEquals(listOf("甲"), names())

        // 3. edit (same name, new content)
        val jia = SkillStore.byName(love, "甲")!!
        assertTrue(SkillStore.save(love, jia.name, jia.copy(content = "第一改")))
        reload()
        assertEquals("第一改", SkillStore.byName(love, "甲")?.content)

        // 4. rename
        assertTrue(SkillStore.rename(love, "甲", "乙"))
        reload()
        assertEquals(listOf("乙"), names())
        assertEquals("第一改", SkillStore.byName(love, "乙")?.content)
        assertEquals("第一个", SkillStore.byName(love, "乙")?.description)

        // 5. a duplicate name is refused, and nothing changes.
        //
        // Two layers: the STORE refuses renaming onto a taken name, and the FORM asks conflict() first so
        // it can show which skill is in the way. Both are checked here because the form's message is what
        // the user actually reads.
        SkillStore.add(love, Skill(name = "丙", content = "第三者"))
        assertFalse(SkillStore.rename(love, "乙", "丙"))
        assertNotNull(SkillStore.conflict(love, "丙", ignore = "乙"))
        reload()
        assertEquals("第一改", SkillStore.byName(love, "乙")?.content)
        assertEquals("第三者", SkillStore.byName(love, "丙")?.content)
        SkillStore.remove(love, "丙")

        // 6. delete
        assertTrue(SkillStore.remove(love, "乙"))
        reload()
        assertEquals(0, SkillStore.count(love))

        // 7. the full import -> export -> import loop still works from an empty state
        SkillStore.add(love, Skill(name = "丙", description = "d", content = "导出我"))
        val zip = SkillStore.zipOf(SkillStore.all(love))!!
        assertTrue(SkillStore.importMany(work, SkillStore.skillsFromZip(zip).skills).ok)
        reload()
        assertEquals(listOf("丙"), names(work))
        assertEquals("导出我", SkillStore.all(work).single().content)
    }
}
