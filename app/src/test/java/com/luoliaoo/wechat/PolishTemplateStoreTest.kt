package com.luoliaoo.wechat

import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.PolishTemplate
import com.luoliaoo.wechat.data.PolishTemplateStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the TEXT-POLISHING template store.
 *
 * The store is a singleton, so every test attaches it to its own temporary files dir and detaches it
 * afterwards; that also exercises the real on-disk path (one JSON file per template plus _order.txt)
 * rather than an in-memory shortcut.
 *
 * Comments are ASCII-only on purpose (see core/Log.kt).
 */
class PolishTemplateStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("luoliao-polish-test").toFile()
        PolishTemplateStore.detach()
        PolishTemplateStore.attach(root)
    }

    @After
    fun tearDown() {
        PolishTemplateStore.detach()
        root.deleteRecursively()
    }

    /** Re-opens the same directory, which is what a process restart does. */
    private fun reload() {
        PolishTemplateStore.detach()
        PolishTemplateStore.attach(root)
    }

    private fun names(): List<String> = PolishTemplateStore.all().map { it.name }

    private fun files(): List<String> =
        File(root, "polishes").listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ------------------------------------------------------------------ defaults

    @Test
    fun firstRunShipsExactlyTwoTemplates() {
        assertEquals(listOf("恋爱", "工作"), names())
    }

    @Test
    fun bothDefaultsCarryAPolishPromptAndAreBuiltIn() {
        val all = PolishTemplateStore.all()
        assertEquals(2, all.size)
        for (t in all) {
            assertTrue("${t.name} must be built-in", t.builtIn)
            assertTrue("${t.name} must carry a prompt", t.systemPrompt.isNotBlank())
        }
    }

    @Test
    fun theTwoPromptsAreDifferent() {
        val all = PolishTemplateStore.all()
        assertEquals("the love and work prompts must not be the same text", 2, all.map { it.systemPrompt }.toSet().size)
    }

    /**
     * The prompts are for REWRITING, not for analysing: the model must answer with the finished message,
     * never with the analysis JSON the analysis templates ask for.
     *
     * Note the check is on the JSON SCHEMA KEYWORDS and not on the word "JSON" itself -- a polish prompt
     * legitimately says 不要 JSON, which is exactly the instruction that keeps the reply plain text.
     */
    @Test
    fun promptsAreNotAnalysisJsonPrompts() {
        for (t in PolishTemplateStore.all()) {
            assertFalse("${t.name} must not ask for the analysis schema", t.systemPrompt.contains("real_intent"))
            assertFalse("${t.name} must not ask for the analysis schema", t.systemPrompt.contains("anger_level"))
            assertFalse("${t.name} must not ask for the analysis schema", t.systemPrompt.contains("intent"))
            assertFalse("${t.name} must not embed a JSON object", t.systemPrompt.contains("{\""))
            assertTrue("${t.name} must forbid JSON output", t.systemPrompt.contains("不要 JSON"))
        }
    }

    @Test
    fun defaultsFunctionIsTheSameTwo() {
        assertEquals(listOf("恋爱", "工作"), PolishTemplateStore.defaults().map { it.name })
    }

    // ------------------------------------------------------------------ add / rename / remove

    @Test
    fun addAppendsAndSurvivesReload() {
        PolishTemplateStore.add(PolishTemplate("家人", "把话说得更亲近一点。"))
        assertEquals(listOf("恋爱", "工作", "家人"), names())

        reload()
        assertEquals(listOf("恋爱", "工作", "家人"), names())
        assertEquals("把话说得更亲近一点。", PolishTemplateStore.byName("家人")?.systemPrompt)
    }

    @Test
    fun addWithTheSameNameReplacesInPlace() {
        PolishTemplateStore.add("恋爱", "改成温柔的版本。")
        assertEquals(listOf("恋爱", "工作"), names())
        assertEquals("改成温柔的版本。", PolishTemplateStore.byName("恋爱")?.systemPrompt)
    }

    @Test
    fun addIgnoresABlankName() {
        PolishTemplateStore.add("   ", "x")
        assertEquals(2, PolishTemplateStore.all().size)
    }

    @Test
    fun renameKeepsThePromptAndTheSlot() {
        PolishTemplateStore.rename("工作", "职场")
        assertEquals(listOf("恋爱", "职场"), names())
        assertTrue(PolishTemplateStore.byName("工作") == null)
        assertTrue(PolishTemplateStore.byName("职场")!!.systemPrompt.isNotBlank())

        reload()
        assertEquals(listOf("恋爱", "职场"), names())
    }

    @Test
    fun renameOntoAnExistingNameLeavesOneTemplate() {
        PolishTemplateStore.add("临时", "随便说说。")
        PolishTemplateStore.rename("临时", "工作")
        assertEquals(listOf("恋爱", "工作"), names())
    }

    @Test
    fun renameOfAnUnknownTemplateDoesNothing() {
        PolishTemplateStore.rename("不存在", "新名字")
        assertEquals(listOf("恋爱", "工作"), names())
    }

    @Test
    fun removeDeletesTheTemplateAndItsFile() {
        PolishTemplateStore.add("家人", "更亲近一点。")
        assertTrue(files().contains("家人.json"))

        PolishTemplateStore.remove("家人")
        assertEquals(listOf("恋爱", "工作"), names())
        assertFalse("a removed template must not leave its file behind", files().contains("家人.json"))

        reload()
        assertEquals(listOf("恋爱", "工作"), names())
    }

    @Test
    fun removingEverythingDoesNotResurrectTheDefaults() {
        PolishTemplateStore.remove("恋爱")
        PolishTemplateStore.remove("工作")
        assertTrue(PolishTemplateStore.isEmpty())

        reload()
        assertTrue("an emptied store must stay empty after a restart", PolishTemplateStore.isEmpty())
    }

    // ------------------------------------------------------------------ restore defaults

    @Test
    fun restoreDefaultsGoesBackToTheTwo() {
        PolishTemplateStore.add("家人", "更亲近一点。")
        PolishTemplateStore.rename("恋爱", "暧昧")
        assertEquals(3, PolishTemplateStore.all().size)

        PolishTemplateStore.restoreDefaults()
        assertEquals(listOf("恋爱", "工作"), names())

        reload()
        assertEquals(listOf("恋爱", "工作"), names())
        // The renamed one must be gone, not merged in.
        assertNull(PolishTemplateStore.byName("暧昧"))
        assertNull(PolishTemplateStore.byName("家人"))
    }

    @Test
    fun restoreDefaultsWorksAfterEverythingWasDeleted() {
        PolishTemplateStore.remove("恋爱")
        PolishTemplateStore.remove("工作")
        PolishTemplateStore.restoreDefaults()
        assertEquals(listOf("恋爱", "工作"), names())
    }

    // ------------------------------------------------------------------ order

    @Test
    fun storedOrderIsStableAcrossReloads() {
        PolishTemplateStore.add("A")
        PolishTemplateStore.add("B")
        PolishTemplateStore.add("C")
        val before = names()

        reload()
        assertEquals(before, names())
        reload()
        assertEquals(before, names())
    }

    /**
     * A fresh install writes 恋爱 before 工作, so the order file must keep it: the picker opens on 恋爱.
     */
    @Test
    fun theShippedOrderIsLoveThenWork() {
        assertEquals("恋爱", PolishTemplateStore.all()[0].name)
        reload()
        assertEquals("恋爱", PolishTemplateStore.all()[0].name)
        assertTrue(File(File(root, "polishes"), "_order.txt").isFile)
    }

    @Test
    fun aTemplateMissingFromTheOrderFileIsStillLoaded() {
        // Simulates a file that appeared without an entry in _order.txt.
        File(root, "polishes").mkdirs()
        File(File(root, "polishes"), "陌生人.json").writeText(
            JSONObject().put("name", "陌生人").put("systemPrompt", "打个招呼。").toString(),
        )
        reload()
        assertTrue(names().contains("陌生人"))
        assertTrue(names().contains("恋爱"))
    }

    // ------------------------------------------------------------------ selection

    @Test
    fun selectionDefaultsToTheFirstTemplate() {
        assertEquals("恋爱", PolishTemplateStore.selectedName())
        assertEquals("恋爱", PolishTemplateStore.active()?.name)
    }

    @Test
    fun selectIsPersisted() {
        PolishTemplateStore.select("工作")
        assertEquals("工作", PolishTemplateStore.selectedName())
        assertEquals("工作", PolishTemplateStore.active()?.name)

        reload()
        assertEquals("工作", PolishTemplateStore.selectedName())
        assertEquals("工作", PolishTemplateStore.active()?.name)
    }

    @Test
    fun selectingAnUnknownNameIsIgnored() {
        PolishTemplateStore.select("不存在")
        assertEquals("恋爱", PolishTemplateStore.selectedName())
    }

    @Test
    fun removingTheSelectedTemplateFallsBackToTheFirst() {
        PolishTemplateStore.select("工作")
        PolishTemplateStore.remove("工作")
        assertEquals("恋爱", PolishTemplateStore.selectedName())
        assertEquals("恋爱", PolishTemplateStore.active()?.name)
    }

    @Test
    fun renameCarriesTheSelectionAlong() {
        PolishTemplateStore.select("工作")
        PolishTemplateStore.rename("工作", "职场")
        assertEquals("职场", PolishTemplateStore.selectedName())

        reload()
        assertEquals("职场", PolishTemplateStore.selectedName())
    }

    // ------------------------------------------------------------------ json / names

    @Test
    fun templateSurvivesJsonRoundTrip() {
        val t = PolishTemplate(name = "恋爱", systemPrompt = "只说一句话。", builtIn = true)
        val back = PolishTemplateStore.templateFromJson(PolishTemplateStore.templateJson(t))
        assertNotNull(back)
        assertEquals("恋爱", back!!.name)
        assertEquals("只说一句话。", back.systemPrompt)
        assertTrue(back.builtIn)
    }

    @Test
    fun jsonWithoutANameFallsBackThenRejects() {
        assertNull(PolishTemplateStore.templateFromJson(JSONObject().put("systemPrompt", "x")))
        assertEquals(
            "文件名兜底",
            PolishTemplateStore.templateFromJson(JSONObject().put("systemPrompt", "x"), "文件名兜底")?.name,
        )
    }

    @Test
    fun safeNameEscapesSeparatorsButKeepsChinese() {
        assertEquals("恋爱", PolishTemplateStore.safeName("恋爱"))
        assertEquals("a_b", PolishTemplateStore.safeName("a/b"))
        assertEquals("polish", PolishTemplateStore.safeName("   "))
    }

    // ================================================================ v10.3.1：模板自带的 API 三项
    //
    // 这一组测的是「文本优化也能自选模型」那条需求的数据侧。UI 侧的表单没法在 JVM 单测里驱动
    // （需要真实 Context），所以**边界全部压到 store 这一层来钉住**：
    // 落盘往返、老文件兼容、逐项回落，这三件事一旦错，表现都是"用户填了模型但没生效"。

    @Test
    fun apiFieldsSurviveJsonRoundTrip() {
        val t = PolishTemplate(
            name = "恋爱",
            systemPrompt = "只说一句话。",
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test-123",
            model = "my-polish-model",
        )
        val back = PolishTemplateStore.templateFromJson(PolishTemplateStore.templateJson(t))
        assertNotNull(back)
        assertEquals("https://api.example.com/v1", back!!.baseUrl)
        assertEquals("sk-test-123", back.apiKey)
        assertEquals("my-polish-model", back.model)
    }

    @Test
    fun apiFieldsSurviveADiskRoundTrip() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "职场",
                systemPrompt = "正式一点。",
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-disk",
                model = "disk-model",
            ),
        )
        // 真正的重启：关掉再打开同一个目录。
        reload()
        val t = PolishTemplateStore.byName("职场")
        assertNotNull(t)
        assertEquals("https://api.example.com/v1", t!!.baseUrl)
        assertEquals("sk-disk", t.apiKey)
        assertEquals("disk-model", t.model)
    }

    /**
     * ★ 老文件（v10.3.0 及以前写下的 JSON）里**没有**这三个键。
     *
     * 这是升级路径的关键断言：老装机打开后必须是「三项为空 = 回落主配置」，
     * 也就是**与升级前逐字节一致**的行为。任何"更贴心"的默认值都会让用户觉得
     * 升级动了他的配置。
     */
    @Test
    fun jsonWrittenBeforeThisVersionReadsBackAsFallBackToTheSharedConfig() {
        // 逐字还原 v10.3.0 写下的形状：只有 name / systemPrompt / builtIn。
        val legacy = JSONObject()
            .put("name", "恋爱")
            .put("systemPrompt", "老提示词")
            .put("builtIn", true)
        val t = PolishTemplateStore.templateFromJson(legacy)
        assertNotNull(t)
        assertEquals("老提示词", t!!.systemPrompt)
        assertTrue(t.builtIn)
        assertEquals("", t.baseUrl)
        assertEquals("", t.apiKey)
        assertEquals("", t.model)
        // 空 = 不用自己的接口，所以升级后仍然走主配置。
        assertFalse(PolishTemplateStore.hasOwnApi(t))
    }

    /** 三项全空 = 全部回落主配置：这是自带模板的档位，必须与 v10.3.0 的行为一致。 */
    @Test
    fun anEmptyTemplateFallsBackToTheSharedConfigForEveryField() {
        val shared = Config(
            baseUrl = "https://shared.example.com",
            apiKey = "sk-shared",
            model = "shared-model",
        )
        val r = PolishTemplateStore.resolve(shared)
        assertEquals("https://shared.example.com", r.baseUrl)
        assertEquals("sk-shared", r.apiKey)
        assertEquals("shared-model", r.model)
        // 内置的「恋爱」模板就是这一档（它有意不填 URL/Key/模型）。
        assertEquals("恋爱", r.templateName)
        assertTrue(r.systemPrompt.isNotBlank())
    }

    /**
     * ★ 逐项回落：只填模型 → 地址与 Key 仍来自主配置。
     *
     * 这条是「换模型不换服务商」的直接表达，也是三项**各自独立**回落（而不是"有一项为空就整组回落"）
     * 的证据。整组回落会让只想换个模型的用户被迫把地址和 Key 也抄一遍。
     */
    @Test
    fun onlyTheModelFilledStillUsesTheSharedAddressAndKey() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "换模型",
                systemPrompt = "温柔一点。",
                model = "my-own-model",
            ),
        )
        PolishTemplateStore.select("换模型")
        val shared = Config(
            baseUrl = "https://shared.example.com",
            apiKey = "sk-shared",
            model = "shared-model",
        )
        val r = PolishTemplateStore.resolve(shared)
        assertEquals("my-own-model", r.model)
        assertEquals("https://shared.example.com", r.baseUrl)
        assertEquals("sk-shared", r.apiKey)
        assertTrue(PolishTemplateStore.hasOwnApi(PolishTemplateStore.active()))
    }

    /** 只填地址与 Key → 模型仍来自主配置（换服务商不换模型）。 */
    @Test
    fun onlyTheEndpointFilledStillUsesTheSharedModel() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "换服务商",
                systemPrompt = "简短一点。",
                baseUrl = "https://other.example.com",
                apiKey = "sk-other",
            ),
        )
        PolishTemplateStore.select("换服务商")
        val shared = Config(
            baseUrl = "https://shared.example.com",
            apiKey = "sk-shared",
            model = "shared-model",
        )
        val r = PolishTemplateStore.resolve(shared)
        assertEquals("https://other.example.com", r.baseUrl)
        assertEquals("sk-other", r.apiKey)
        assertEquals("shared-model", r.model)
    }

    /** 三项全填 → 完全用模板自己的一套，主配置一个字都不参与。 */
    @Test
    fun aFullyConfiguredTemplateIgnoresTheSharedConfig() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "全独立",
                systemPrompt = "随便说。",
                baseUrl = "https://own.example.com",
                apiKey = "sk-own",
                model = "own-model",
            ),
        )
        PolishTemplateStore.select("全独立")
        val r = PolishTemplateStore.resolve(
            Config(baseUrl = "https://shared.example.com", apiKey = "sk-shared", model = "shared-model"),
        )
        assertEquals("https://own.example.com", r.baseUrl)
        assertEquals("sk-own", r.apiKey)
        assertEquals("own-model", r.model)
        assertEquals("随便说。", r.systemPrompt)
    }

    /**
     * ★ 回归：`add(name, prompt)` 这层便利重载**不能**清掉已经填好的 API 三项。
     *
     * 这条钉的是一个真实会发生的破坏：老代码用 "名字 + 提示词" 构造了一个三项全空的模板，
     * 于是任何"只改提示词"的保存路径都会把用户填好的地址/Key/模型静默抹掉，
     * 表现是"我什么都没动，怎么又走回主配置了"。
     */
    @Test
    fun thePromptOnlyOverloadKeepsTheApiFieldsOfAnExistingTemplate() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "保留",
                systemPrompt = "第一版提示词",
                baseUrl = "https://keep.example.com",
                apiKey = "sk-keep",
                model = "keep-model",
            ),
        )
        // 只改提示词。
        PolishTemplateStore.add("保留", "第二版提示词")

        val t = PolishTemplateStore.byName("保留")
        assertNotNull(t)
        assertEquals("第二版提示词", t!!.systemPrompt)
        assertEquals("https://keep.example.com", t.baseUrl)
        assertEquals("sk-keep", t.apiKey)
        assertEquals("keep-model", t.model)
    }

    /** 没有同名老记录时，便利重载仍然是一个"API 三项全空"的新模板（= 回落主配置）。 */
    @Test
    fun thePromptOnlyOverloadCreatesAnEmptyApiSetWhenThereIsNothingToKeep() {
        PolishTemplateStore.add("新模板", "随便写。")
        val t = PolishTemplateStore.byName("新模板")
        assertNotNull(t)
        assertEquals("", t!!.baseUrl)
        assertEquals("", t.apiKey)
        assertEquals("", t.model)
        assertFalse(PolishTemplateStore.hasOwnApi(t))
    }

    /** 自带模板的 API 三项默认为空：升级后老用户不需要填任何东西。 */
    @Test
    fun theShippedTemplatesShipWithNoApiOverrides() {
        for (t in PolishTemplateStore.defaults()) {
            assertEquals("", t.baseUrl)
            assertEquals("", t.apiKey)
            assertEquals("", t.model)
            assertFalse(PolishTemplateStore.hasOwnApi(t))
        }
    }

    /** 名称为空的重命名不会让模板丢掉自己那一套接口。 */
    @Test
    fun renamingKeepsTheApiFields() {
        PolishTemplateStore.add(
            PolishTemplate(
                name = "旧名",
                systemPrompt = "提示词",
                baseUrl = "https://rename.example.com",
                apiKey = "sk-rename",
                model = "rename-model",
            ),
        )
        PolishTemplateStore.rename("旧名", "新名")
        reload()
        val t = PolishTemplateStore.byName("新名")
        assertNotNull(t)
        assertEquals("https://rename.example.com", t!!.baseUrl)
        assertEquals("sk-rename", t.apiKey)
        assertEquals("rename-model", t.model)
    }
}
