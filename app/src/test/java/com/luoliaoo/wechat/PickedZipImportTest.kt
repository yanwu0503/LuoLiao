package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.Skill
import com.luoliaoo.wechat.data.SkillStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the zip handling that the SYSTEM FILE PICKER now feeds.
 *
 * The picker itself needs a device (it is a framework chooser) and so is not unit-testable. What IS
 * testable -- and what actually carried the import bug -- is everything after it: given the bytes of a
 * picked file, does the import work? These tests drive that exact boundary: raw bytes in, skills out.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class PickedZipImportTest {

    private lateinit var root: File

    private val love = "恋爱"
    private val work = "工作"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("luoliao-picked-zip").toFile()
        SkillStore.detach()
        SkillStore.attach(root)
    }

    @After
    fun tearDown() {
        SkillStore.detach()
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((path, text) in entries) {
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun md(name: String, body: String, desc: String = "") =
        buildString {
            append("---\nname: ").append(name).append('\n')
            if (desc.isNotBlank()) append("description: ").append(desc).append('\n')
            append("---\n\n").append(body).append('\n')
        }

    /** Exactly what SkillDialog does with a Picked: size guard, parse, import. */
    private fun importPicked(bytes: ByteArray) = SkillStore.skillsFromZip(bytes)

    private fun names(t: String = "恋爱") = SkillStore.all(t).map { it.name }

    // ------------------------------------------------------------------ the ① path (one skill)

    @Test
    fun aPickedSingleSkillArchiveImportsUnderTheChosenName() {
        val bytes = zip("SKILL.md" to md("github-private-backup", "正文内容", "备份说明"))
        val res = importPicked(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(1, res.skills.size)

        // ① asks the user for a display name before writing; the body must survive the rename.
        val incoming = res.skills.single()
        assertTrue(SkillStore.add(love, incoming.copy(name = "我的备份", description = "改过的说明")))
        val stored = SkillStore.all(love).single()
        assertEquals("我的备份", stored.name)
        assertEquals("改过的说明", stored.description)
        assertEquals(incoming.content, stored.content)
    }

    @Test
    fun aPickedSingleSkillArchiveWithMoreThanOneSkillIsReportedNotImported() {
        // ① accepts exactly one skill; ③ is for many. The check must happen BEFORE anything is written.
        val bytes = zip(
            "A/SKILL.md" to md("A", "a"),
            "B/SKILL.md" to md("B", "b"),
        )
        val res = importPicked(bytes)
        assertTrue(res.ok)
        assertEquals(2, res.skills.size)
        // This is the guard the dialog applies -- the store itself stays untouched.
        assertEquals(0, SkillStore.count(love))
        assertTrue(res.skills.size > 1)
    }

    // ------------------------------------------------------------------ the ③ path (many skills)

    @Test
    fun aPickedMultiSkillArchiveImportsEverySkill() {
        val bytes = zip(
            "A/SKILL.md" to md("A", "a-body"),
            "B/SKILL.md" to md("B", "b-body"),
            "C/SKILL.md" to md("C", "c-body"),
        )
        val res = importPicked(bytes)
        assertTrue(res.message, res.ok)
        val r = SkillStore.importMany(love, res.skills)
        assertTrue(r.message, r.ok)
        assertEquals(3, r.added)
        assertEquals(listOf("A", "B", "C"), names())
    }

    @Test
    fun aPickedArchiveOfSomebodyElsesExportImportsCleanly() {
        // The whole point of ③: a zip produced by 导出 from another device must load here.
        SkillStore.add(work, Skill(name = "远端甲", description = "d1", content = "第一行\n第二行"))
        SkillStore.add(work, Skill(name = "远端乙", content = "另一段"))
        val exported = SkillStore.zipOf(SkillStore.all(work))!!

        // Carry the BYTES across, as the picker does -- no file, no path, no shared storage.
        val res = importPicked(exported)
        assertTrue(res.message, res.ok)
        assertEquals(2, res.skills.size)

        val r = SkillStore.importMany(love, res.skills)
        assertTrue(r.message, r.ok)
        assertEquals(listOf("远端甲", "远端乙"), names(love))
        assertEquals("第一行\n第二行", SkillStore.byName(love, "远端甲")?.content)
        assertEquals("d1", SkillStore.byName(love, "远端甲")?.description)
    }

    @Test
    fun aPickedArchiveWithAgentToolingStillImportsOnlyTheSkills() {
        // The "agent 相关移除" rule survives the switch to the system picker.
        val bytes = zip(
            "A/SKILL.md" to md("A", "keep"),
            ".agent-teams/team.json" to "{\"agents\":[]}",
            "agents/reviewer.md" to md("reviewer", "not a skill"),
            "AGENT.md" to md("agent", "not a skill"),
            "mcp.json" to "{}",
        )
        val res = importPicked(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(listOf("A"), res.skills.map { it.name })
    }

    // ------------------------------------------------------------------ the failure paths

    @Test
    fun aPickedNonZipFileGivesAReadableReasonAndChangesNothing() {
        SkillStore.add(love, Skill(name = "已有的", content = "不能被动"))
        val res = importPicked("this is a text file, not a zip".toByteArray())
        assertFalse(res.ok)
        assertTrue(res.message.isNotBlank())
        // The existing skill must be untouched.
        assertEquals(listOf("已有的"), names())
        assertEquals("不能被动", SkillStore.all(love).single().content)
    }

    @Test
    fun aPickedZipWithNoSkillGivesAReadableReasonAndChangesNothing() {
        SkillStore.add(love, Skill(name = "已有的", content = "c"))
        val res = importPicked(zip("readme.txt" to "hi", "data/config.json" to "{}"))
        assertFalse(res.ok)
        assertTrue(res.message, res.message.contains("没有找到 Skill"))
        assertEquals(listOf("已有的"), names())
    }

    @Test
    fun aPickedEmptyFileIsRefused() {
        val res = importPicked(ByteArray(0))
        assertFalse(res.ok)
        assertEquals(0, SkillStore.count(love))
    }

    @Test
    fun aPickedZipLargerThanTheCapIsAlreadyRefusedByTheStore() {
        // The dialog checks the byte count first; the store enforces the same cap independently, so a
        // caller that forgot cannot push an oversized archive through.
        val huge = ByteArray(SkillStore.MAX_ZIP_BYTES.toInt() + 1)
        val res = SkillStore.skillsFromZip(huge)
        assertFalse(res.ok)
        assertTrue(res.message, res.message.contains("过大"))
    }

    @Test
    fun aPickedZipWithAWrongEntryPathStillFindsTheSkill() {
        // Some tools wrap everything in one extra top-level folder. The folder is what names the skill, so
        // the FIRST path segment is used and the skill still comes back.
        val bytes = zip(
            "my-collection/谈判开场/SKILL.md" to md("谈判开场", "内容"),
        )
        val res = importPicked(bytes)
        assertTrue(res.message, res.ok)
        assertEquals(1, res.skills.size)
        // The name comes from the front matter when present, which is the safer source.
        assertEquals("谈判开场", res.skills.single().name)
        assertTrue(res.skills.single().content.contains("内容"))
    }

    @Test
    fun aPickedZipWithAWindowsStylePathIsHandled() {
        // A zip made on Windows may carry backslashes; the entry name must not be taken literally.
        val bytes = zip("A\\SKILL.md" to md("A", "windows path"))
        val res = importPicked(bytes)
        assertTrue(res.message, res.ok)
        assertEquals("A", res.skills.single().name)
    }

    @Test
    fun aPickedArchiveSurvivesADiskRoundTrip() {
        val bytes = zip("A/SKILL.md" to md("A", "第一行\n第二行", "说明"))
        val res = importPicked(bytes)
        assertTrue(SkillStore.importMany(love, res.skills).ok)
        // A restart: the content must come back byte-for-byte from SKILL.md.
        SkillStore.detach()
        SkillStore.attach(root)
        val stored = SkillStore.all(love).single()
        assertEquals("A", stored.name)
        assertEquals("说明", stored.description)
        assertEquals("第一行\n第二行", stored.content)
    }
}
