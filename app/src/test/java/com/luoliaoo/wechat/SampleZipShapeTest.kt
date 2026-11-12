package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.Skill
import com.luoliaoo.wechat.data.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the importer against the SHAPE of the example archive a user supplied.
 *
 * That archive (`shili.zip`) holds exactly one entry -- `SKILL.md` at the root, carrying a name, a
 * description and a body in YAML front matter, thousands of characters of Chinese. The file itself is not
 * committed, so its content is reproduced here: this is what "① 导入 zip（一个 Skill）" must accept, and it
 * is the layout most agent skills in the wild come in.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class SampleZipShapeTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("luoliao-sample-zip").toFile()
        SkillStore.detach()
        SkillStore.attach(root)
    }

    @After
    fun tearDown() {
        SkillStore.detach()
        root.deleteRecursively()
    }

    /** The example's shape: one SKILL.md at the root, front matter, a long Chinese body. */
    private fun exampleArchive(): ByteArray {
        val body = buildString {
            append("# GitHub 私人仓库自动备份\n\n")
            append("## 核心规则\n\n")
            repeat(60) { i ->
                append("- 规则 ").append(i).append("：适用于模组、网站、桌面程序、脚本、服务端、插件、游戏。\n")
            }
            append("\n## 修改前检查\n\n")
            append("1. 确认项目根目录、当前分支和远程仓库。\n")
            append("2. 读取 git status 和最近提交记录。\n")
        }
        val text = "---\n" +
            "name: github-private-backup\n" +
            "description: 为所有类型的源码项目执行 GitHub 私人仓库持续备份。\n" +
            "---\n\n" + body
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun theExampleArchiveParsesIntoOneNamedSkill() {
        val res = SkillStore.skillsFromZip(exampleArchive())
        assertTrue(res.message, res.ok)
        assertEquals(1, res.skills.size)
        val s = res.skills.single()
        assertEquals("github-private-backup", s.name)
        assertTrue(s.description.startsWith("为所有类型的源码项目"))
        assertTrue(s.content.contains("核心规则"))
        assertTrue(s.content.contains("修改前检查"))
        // The body must NOT still carry the front matter.
        assertTrue(s.description.isNotBlank())
        assertTrue(!s.content.contains("name: github-private-backup"))
    }

    @Test
    fun theExampleArchiveRoundTripsThroughDisk() {
        val res = SkillStore.skillsFromZip(exampleArchive())
        assertTrue(SkillStore.add("恋爱", res.skills.single()))
        // Reload: the content must come back byte-for-byte from SKILL.md, front matter and all.
        SkillStore.detach()
        SkillStore.attach(root)
        val stored = SkillStore.all("恋爱").single()
        assertEquals("github-private-backup", stored.name)
        assertEquals(res.skills.single().content, stored.content)
        assertEquals(res.skills.single().description, stored.description)
        // And it reaches the prompt.
        assertTrue(SkillStore.instructions("恋爱").contains("核心规则"))
    }

    @Test
    fun theExampleSurvivesReExportAndReImport() {
        // 导入别人给的 zip -> 导出成自己的 zip -> 另一个人再导入, all with the same content.
        val first = SkillStore.skillsFromZip(exampleArchive()).skills.single()
        SkillStore.add("恋爱", first)
        val reExported = SkillStore.zipOf(SkillStore.all("恋爱"))!!
        val second = SkillStore.skillsFromZip(reExported)
        assertTrue(second.message, second.ok)
        assertEquals(first.name, second.skills.single().name)
        assertEquals(first.description, second.skills.single().description)
        assertEquals(first.content, second.skills.single().content)
    }

    @Test
    fun theExampleIsRefusedWhenTheNameIsTooLongForTheStore() {
        // The same document with an over-long name must be rejected BEFORE anything is written.
        val text = "---\nname: " + "x".repeat(SkillStore.MAX_NAME + 1) + "\n---\n\nbody\n"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val res = SkillStore.skillsFromZip(out.toByteArray())
        org.junit.Assert.assertFalse(res.ok)
        assertEquals(0, SkillStore.count("恋爱"))
    }

    @Test
    fun aLongBodyIsKeptWhole() {
        // The example body is thousands of characters; the importer must not silently truncate it.
        val body = "行".repeat(3000)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(("---\nname: big\n---\n\n" + body).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val s = SkillStore.skillsFromZip(out.toByteArray()).skills.single()
        assertEquals(3000, s.content.length)
        assertEquals(body, s.content)
    }

    @Test
    fun aSkillRenamedOnImportKeepsTheWholeBody() {
        // ① asks for a display name: the rename must not touch the content.
        val incoming = SkillStore.skillsFromZip(exampleArchive()).skills.single()
        assertTrue(SkillStore.add("恋爱", incoming.copy(name = "我的备份", description = "改过的说明")))
        val stored = SkillStore.all("恋爱").single()
        assertEquals("我的备份", stored.name)
        assertEquals("改过的说明", stored.description)
        assertEquals(incoming.content, stored.content)
    }
}
