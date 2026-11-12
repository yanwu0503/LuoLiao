package com.luoliaoo.wechat.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.ai.OpenAiClient
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.SoLoader
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.SecureStore
import java.util.concurrent.Executors

/**
 * Module configuration page, styled after Nuke's WebUI.
 *
 * Fields and behaviour follow the requirement exactly: interface type / API Base URL / API Key /
 * model (selectable from the endpoint) / system prompt, plus the default analysis scope and
 * maintenance actions. Only the presentation changed.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
class SettingsActivity : Activity() {

    private companion object {
        const val TAG = "LuoLiao"
        const val KEY_HINT = "\u5bc6\u94a5\u4ec5\u4fdd\u5b58\u5728\u672c\u673a\u914d\u7f6e\u4e2d\uff0c\u4e0d\u4f1a\u5199\u5165\u65e5\u5fd7"
    }

    /**
     * 5.0.0: the host the module's OWN screens speak for.
     *
     * This process is never injected by LSPosed, so `HostApp.current` is only the default. Resolution is
     * "the first host that is actually installed" (WeChat first, then Douyin), which makes a Douyin-only
     * install read 抖音 instead of 微信 here.
     */
    private val host: com.luoliaoo.wechat.core.HostApp
        get() = com.luoliaoo.wechat.core.HostApp.installed(this)
            .firstOrNull() ?: com.luoliaoo.wechat.core.HostApp.ui

    /** [text] with 微信 / 微信号 rewritten for [host]. */
    private fun h(text: String): String = host.say(text)

    /**
     * The hosts to report on: every INSTALLED host, or both when none is.
     *
     * Same rule as the launcher's badge row -- see its `badgeHosts()`. Shared intent, not shared code:
     * the two screens build different shapes (badges vs. `key=value` lines) from the same list.
     */
    private fun installedHosts(): List<com.luoliaoo.wechat.core.HostApp> =
        com.luoliaoo.wechat.core.HostApp.installed(this)
            .ifEmpty { com.luoliaoo.wechat.core.HostApp.REAL_HOSTS }

    /**
     * 液态玻璃：系统在深浅模式之间切换时，把这一屏整个重建。
     *
     * 为什么需要它：这一屏的控件全部由 Kotlin 代码按当前主题绘制（NukeTheme），已经建好的视图
     * 不会自己换色。默认情况下 Activity 会因 uiMode 变化被系统重建，所以通常用不到这个回调；
     * 但若宿主或某些 ROM 声明了 `configChanges="uiMode"`，系统就**不重建**，界面会停在旧主题 ——
     * 这正是审计报告点出的缺口（此前全项目 0 处 onConfigurationChanged）。
     *
     * 用 recreate() 而非手动重跑 buildUi()：走正常生命周期，将来新增的初始化逻辑不会漏执行。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            // 先让主题标志跟随新配置，再重建，避免重建出来的界面仍是旧色。
            NukeTheme.syncTheme(this)
            recreate()
        } catch (t: Throwable) {
            Log.w(TAG, "onConfigurationChanged failed: " + t.message)
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etPrompt: EditText
    private lateinit var tvDiag: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "\u843d\u804a"
        try {
            com.luoliaoo.wechat.data.TemplateStore.attachFrom(this)
        } catch (_: Throwable) {
            // the dialog attaches it lazily anyway
        }
        // v4.0.0: the text-polish templates, so the module app and the WeChat process agree on the list.
        try {
            com.luoliaoo.wechat.data.PolishTemplateStore.attachFrom(this)
        } catch (_: Throwable) {
            // the dialog attaches it lazily anyway
        }
        // v4.1.0: the per-template Skill store, so a skill created here is the same one WeChat sees.
        try {
            com.luoliaoo.wechat.data.SkillStore.attachFrom(this)
        } catch (_: Throwable) {
            // the dialog attaches it lazily anyway
        }
        // The debug log lives in the module's own directory. Attach it and honour the stored switch, so the
        // log is already being kept when the user turns debugging on.
        try {
            com.luoliaoo.wechat.core.DebugLog.attach(this)
            com.luoliaoo.wechat.core.DebugLog.setEnabled(
                com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this).debugLog,
            )
        } catch (t: Throwable) {
            // diagnostics must never block the settings screen
        }
        buildUi()
    }

    private fun dp(v: Int) = NukeTheme.dp(this, v)

    private fun buildUi() {
        val cfg = ConfigRepo.load(this)
        val savedKey = SecureStore.loadDecrypted(this).ifBlank { cfg.apiKey }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        // ---- header
        root.addView(NukeTheme.eyebrow(this, "\u843d\u804a \u00b7 \u8bbe\u7f6e"))
        root.addView(NukeTheme.title(this, "\u914d\u7f6e", 30f))
        root.addView(NukeTheme.body(
            this,
            "\u63a5\u53e3\u4e0e\u5206\u6790\u504f\u597d\u90fd\u5728\u8fd9\u91cc\uff0c\u4fdd\u5b58\u540e\u7acb\u5373\u751f\u6548",
            NukeTheme.TEXT_MUTED,
            13f,
        ).apply { setPadding(0, dp(8), 0, dp(20)) })

        // ============================================================ API panel
        val apiCard = NukeTheme.sectionCard(this)
        apiCard.addView(NukeTheme.category(this, "API"))
        apiCard.addView(cardTitle("\u63a5\u53e3\u914d\u7f6e"))

        apiCard.addView(NukeTheme.fieldLabel(this, "\u63a5\u53e3\u7c7b\u578b"))
        apiCard.addView(readonly("OpenAI \u517c\u5bb9\u63a5\u53e3"))

        apiCard.addView(NukeTheme.fieldLabel(this, "API Base URL"))
        etBaseUrl = NukeTheme.input(this, cfg.baseUrl, "https://api.example.com/v1")
        apiCard.addView(etBaseUrl)

        apiCard.addView(NukeTheme.fieldLabel(this, "API Key"))
        etApiKey = NukeTheme.input(this, savedKey, "sk-...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        apiCard.addView(etApiKey)
        apiCard.addView(NukeTheme.hintText(this, KEY_HINT))
        apiCard.addView(NukeTheme.link(this, "\u663e\u793a / \u9690\u85cf\u5bc6\u94a5") {
            toggleKeyVisibility()
        })

        apiCard.addView(NukeTheme.fieldLabel(this, "\u6a21\u578b"))
        etModel = NukeTheme.input(this, cfg.model, "gpt-4o-mini")
        apiCard.addView(etModel)
        apiCard.addView(NukeTheme.link(this, "\u4ece\u63a5\u53e3\u9009\u62e9\u6a21\u578b") { fetchModels() })

        apiCard.addView(NukeTheme.fieldLabel(this, "\u7cfb\u7edf\u63d0\u793a\u8bcd"))
        etPrompt = NukeTheme.input(this, cfg.systemPrompt, "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            setText(cfg.systemPrompt)
        }
        apiCard.addView(etPrompt)
        apiCard.addView(NukeTheme.link(this, "\u6062\u590d\u9ed8\u8ba4\u63d0\u793a\u8bcd") {
            etPrompt.setText(Config.DEFAULT_PROMPT)
        })

        apiCard.addView(NukeTheme.primaryButton(this, "\u4fdd\u5b58") { save() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
        root.addView(apiCard)

        // ============================================================ scope panel
        val scopeCard = NukeTheme.sectionCard(this)
        scopeCard.addView(NukeTheme.category(this, "\u5206\u6790\u8303\u56f4"))
        scopeCard.addView(cardTitle("\u9ed8\u8ba4\u8303\u56f4"))
        scopeCard.addView(NukeTheme.body(
            this,
            "\u957f\u6309\u300c\u5206\u6790\u300d\u65f6\u9762\u677f\u4f1a\u9884\u586b\u8fd9\u4e9b\u503c",
            NukeTheme.TEXT_DIM,
            12f,
        ).apply { setPadding(0, dp(6), 0, 0) })

        val rowScope = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        // THE CRASH FIX: `numberField` returns a CONTAINER (label + input), so the EditText must be taken
        // from INSIDE it. Casting the container to EditText threw ClassCastException on every launch of this
        // screen. The fields are created here so their type is explicit and cannot be wrong again.
        val upField = numberField("\u5411\u4e0a", cfg.defaultUp.toString())
        val downField = numberField("\u5411\u4e0b", cfg.defaultDown.toString())
        rowScope.addView(upField, weight())
        rowScope.addView(downField, weight())
        scopeCard.addView(rowScope)

        val etUp = editTextIn(upField) ?: NukeTheme.input(this, cfg.defaultUp.toString(), "3")
        val etDown = editTextIn(downField) ?: NukeTheme.input(this, cfg.defaultDown.toString(), "3")

        scopeCard.addView(NukeTheme.fieldLabel(this, "\u65f6\u95f4\u533a\u95f4\uff1a\u56de\u6eaf\u5c0f\u65f6\u6570"))
        val etHours = NukeTheme.input(this, cfg.defaultRangeHours.toString(), "24")
        scopeCard.addView(etHours)

        scopeCard.addView(NukeTheme.fieldLabel(this, "\u5361\u7247\u5bbd\u5ea6\uff08\u5b57\u6570\uff0c\u6700\u5c0f 1\uff0c\u9ed8\u8ba4 25\uff09"))
        val etWidth = NukeTheme.input(this, cfg.cardWidth.toString(), "25")
        scopeCard.addView(etWidth)
        scopeCard.addView(NukeTheme.hintText(
            this,
            "\u6570\u5b57\u4ee3\u8868\u5361\u7247\u5bbd\u5ea6\u76f8\u5f53\u4e8e\u591a\u5c11\u4e2a\u6c49\u5b57\uff0c\u53ef\u4efb\u610f\u586b\u5927",
        ))

        scopeCard.addView(NukeTheme.secondaryButton(this, "\u4fdd\u5b58\u9ed8\u8ba4\u8303\u56f4") {
            val c = ConfigRepo.load(this@SettingsActivity)
            ConfigRepo.save(
                this@SettingsActivity,
                c.copy(
                    defaultUp = etUp.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 3,
                    defaultDown = etDown.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 3,
                    defaultRangeHours = etHours.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 24,
                    cardWidthChars = (etWidth.text.toString().toIntOrNull() ?: 25).coerceAtLeast(1),
                ),
            )
            // t4 BUG FIX: this button used to write the module-side prefs ONLY. The WeChat process reads
            // its own prefs (loadLocal) and otherwise the mirror, so 保存默认范围 looked like it did
            // nothing until the app was reopened AND the mirror happened to be rewritten. Both are now
            // written here, with the same values.
            commitEverywhere()
            toast("\u9ed8\u8ba4\u8303\u56f4\u5df2\u4fdd\u5b58\u5e76\u7acb\u5373\u751f\u6548")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        })
        root.addView(scopeCard)

        // ============================================================ diagnostics panel
        val diagCard = NukeTheme.sectionCard(this)
        diagCard.addView(NukeTheme.category(this, "\u8bca\u65ad"))
        // ============================================================ debug log (item)
        // A switch and a packaging button, so troubleshooting no longer needs the LSPosed export.
        val debugRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            tag = com.luoliaoo.wechat.core.Constants.TAG_OWN_UI
        }
        debugRow.addView(
            NukeTheme.body(this, "\u5f00\u542f\u8c03\u8bd5", NukeTheme.TEXT, 14f).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        val swDebug = android.widget.Switch(this).apply {
            isChecked = try {
                com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this@SettingsActivity).debugLog
            } catch (_: Throwable) {
                false
            }
            tag = com.luoliaoo.wechat.core.Constants.TAG_OWN_UI
            setOnCheckedChangeListener { _, checked ->
                try {
                    val c = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this@SettingsActivity)
                    // t4: mirror as well. With saveLocal alone the WeChat process never saw the switch,
                    // which is one of the controls the user was told "needs a restart".
                    com.luoliaoo.wechat.data.ConfigRepo
                        .saveLocalAndMirror(this@SettingsActivity, c.copy(debugLog = checked))
                    com.luoliaoo.wechat.data.ConfigRepo.flushMirror()
                    com.luoliaoo.wechat.core.DebugLog.setEnabled(checked)
                    toast(
                        if (checked) {
                            "\u8c03\u8bd5\u5df2\u5f00\u542f\uff0c\u65e5\u5fd7\u4f1a\u5199\u5165\u6587\u4ef6"
                        } else {
                            "\u8c03\u8bd5\u5df2\u5173\u95ed"
                        },
                    )
                } catch (t: Throwable) {
                    toast("\u4fdd\u5b58\u5931\u8d25\uff1a" + t.message)
                }
            }
        }
        debugRow.addView(swDebug)
        diagCard.addView(debugRow)
        diagCard.addView(
            NukeTheme.body(
                this,
                "\u5f00\u542f\u540e\u6a21\u5757\u4f1a\u8bb0\u5f55\u81ea\u5df1\u7684\u8c03\u8bd5\u65e5\u5fd7",
                NukeTheme.TEXT_DIM,
                11f,
            ).apply { setPadding(0, dp(2), 0, dp(10)) },
        )

        // ============================================================ v10.0.0 语音转文字
        //
        // 这个开关放在「诊断」卡片里、调试开关的下面，而不是新开一张卡片：
        // 它和调试开关一样，都是"改变模块在微信进程里的后台行为"的全局开关，
        // 归在一起比散落两处更好找。
        //
        // 它的取值必须走 saveLocalAndMirror：真正读它的是**微信进程**（AnalysisController.handleVoice），
        // 而设置界面跑在模块自己的进程里。只用 saveLocal 的话，"设置里关了、微信里还在转"。
        val voiceRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            tag = com.luoliaoo.wechat.core.Constants.TAG_OWN_UI
        }
        voiceRow.addView(
            NukeTheme.body(this, "\u8bed\u97f3\u8f6c\u6587\u5b57", NukeTheme.TEXT, 14f).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        val swVoice = android.widget.Switch(this).apply {
            isChecked = try {
                com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this@SettingsActivity).voiceToText
            } catch (_: Throwable) {
                true
            }
            tag = com.luoliaoo.wechat.core.Constants.TAG_OWN_UI
            setOnCheckedChangeListener { _, checked ->
                try {
                    val c = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(this@SettingsActivity)
                    com.luoliaoo.wechat.data.ConfigRepo
                        .saveLocalAndMirror(this@SettingsActivity, c.copy(voiceToText = checked))
                    com.luoliaoo.wechat.data.ConfigRepo.flushMirror()
                    toast(
                        if (checked) {
                            "\u8bed\u97f3\u8f6c\u6587\u5b57\u5df2\u5f00\u542f"
                        } else {
                            "\u8bed\u97f3\u8f6c\u6587\u5b57\u5df2\u5173\u95ed"
                        },
                    )
                } catch (t: Throwable) {
                    toast("\u4fdd\u5b58\u5931\u8d25\uff1a" + t.message)
                }
            }
        }
        voiceRow.addView(swVoice)
        diagCard.addView(voiceRow)
        diagCard.addView(
            NukeTheme.body(
                this,
                "\u5f00\u542f\u540e\u6536\u5230\u8bed\u97f3\u4f1a\u8c03\u7528\u5fae\u4fe1\u81ea\u5e26\u7684\u8f6c\u5199\uff0c" +
                    "\u4ee5\u3010\u8bed\u97f3\u8f6c\u6587\u5b57\u3011\u524d\u7f00\u5199\u5165\u672c\u5730\u804a\u5929\u8bb0\u5f55",
                NukeTheme.TEXT_DIM,
                11f,
            ).apply { setPadding(0, dp(2), 0, dp(10)) },
        )

        diagCard.addView(
            NukeTheme.warnButton(this, "\u6253\u5305\u65e5\u5fd7") {
                try {
                    com.luoliaoo.wechat.core.DebugLog.attach(this@SettingsActivity)
                    val path = com.luoliaoo.wechat.core.LogPacker.pack(this@SettingsActivity)
                    if (path == null) {
                        toast("\u6253\u5305\u5931\u8d25\uff0c\u8be6\u89c1\u65e5\u5fd7")
                    } else {
                        tvDiag.text = "\u65e5\u5fd7\u5df2\u6253\u5305\uff1a\n" + path
                        toast("\u5df2\u6253\u5305\u5230\uff1a" + path)
                    }
                } catch (t: Throwable) {
                    toast("\u6253\u5305\u5931\u8d25\uff1a" + t.message)
                }
            },
        )
        diagCard.addView(
            NukeTheme.link(this, "\u6e05\u7a7a\u8c03\u8bd5\u65e5\u5fd7") {
                com.luoliaoo.wechat.core.DebugLog.clear()
                toast("\u8c03\u8bd5\u65e5\u5fd7\u5df2\u6e05\u7a7a")
            },
        )

        diagCard.addView(cardTitle("\u8fd0\u884c\u72b6\u6001"))
        tvDiag = NukeTheme.body(this, "", NukeTheme.TEXT_DIM, 12f).apply {
            text = buildDiagText()
            setPadding(0, dp(8), 0, 0)
        }
        diagCard.addView(tvDiag)
        diagCard.addView(NukeTheme.link(this, "\u5237\u65b0\u8bca\u65ad") { tvDiag.text = buildDiagText() })
        root.addView(diagCard)

        // ============================================================ item 4: environment + contacts
        //
        // The user asked for the phone model, the system build and the android version alongside the module /
        // WeChat / API versions. All of these come from android.os.Build, which requires NO permission, so the
        // block is always populated even if a package lookup is refused.
        val infoCard = NukeTheme.sectionCard(this)
        infoCard.addView(NukeTheme.category(this, "\u8bbe\u5907\u4fe1\u606f"))
        infoCard.addView(cardTitle("\u73af\u5883"))
        infoCard.addView(
            NukeTheme.body(this, buildEnvText(), NukeTheme.TEXT_DIM, 12f).apply {
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        // The CONTACT block, in RED. QQ is marked as the preferred channel because that is what was asked
        // for. The four values to send are listed so a user reporting an incompatibility knows exactly what
        // to include.
        //
        // 液态玻璃升级：原先写死 0xFFFF3B30（iOS 系统红），是为深色底挑的值；浅色主题下对比度
        // 不足（约 3.2:1，低于 WCAG AA 的 4.5:1）。改用主题语义危险色，明暗各有一套且均已校验。
        val CONTACT_RED = NukeTheme.DANGER_TEXT
        infoCard.addView(
            TextView(this).apply {
                text = "TG\uff1aLUO1314AI\n" +
                    "QQ\uff1a628843841\uff08\u4f18\u5148\uff09\n" +
                    "\u9177\u5b89\uff1a\u8a00\u65e00503"
                setTextColor(CONTACT_RED)
                textSize = NukeTheme.sp(this@SettingsActivity, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(2))
            },
        )
        infoCard.addView(
            TextView(this).apply {
                text = h("\u6a21\u5757\u65e0\u6cd5\u4f7f\u7528\uff1f\u8fdb\u4f01\u9e45\u7fa4" +
                    "\u53d1\u5fae\u4fe1\u5b89\u88c5\u5305\u5373\u53ef\u517c\u5bb9\n") +
                    h("\u53d1\uff1a\u5fae\u4fe1\u5305\u7248\u672c \u00b7 \u624b\u673a\u578b\u53f7 \u00b7 ") +
                    "\u5b89\u5353\u7248\u672c \u00b7 LspAPI \u7248\u672c\n" +
                    "\u544a\u77e5\u4ee5\u4e0a\u56db\u4e2a\u5373\u53ef\uff0c\u8010\u5fc3\u7b49\u5f85" +
                    "\u517c\u5bb9\uff08\u5f88\u5feb\uff09"
                setTextColor(CONTACT_RED)
                textSize = NukeTheme.sp(this@SettingsActivity, 12f)
                setPadding(0, dp(2), 0, dp(4))
            },
        )
        root.addView(infoCard)

        // ============================================================ maintenance panel
        val cleanCard = NukeTheme.sectionCard(this)
        cleanCard.addView(NukeTheme.category(this, "\u7ef4\u62a4"))
        cleanCard.addView(cardTitle("\u6e05\u7406"))
        cleanCard.addView(NukeTheme.body(
            this,
            "\u6e05\u7406\u4e0d\u4f1a\u5f71\u54cd\u4f60\u7684 API \u914d\u7f6e",
            NukeTheme.TEXT_DIM,
            12f,
        ).apply { setPadding(0, dp(6), 0, dp(12)) })

        cleanCard.addView(NukeTheme.secondaryButton(this, "\u6e05\u7a7a\u5206\u6790\u7ed3\u679c\u7f13\u5b58") {
            com.luoliaoo.wechat.data.ResultCache.clearAll()
            com.luoliaoo.wechat.data.HistoryStore.clearAll()
            toast("\u7f13\u5b58\u5df2\u6e05\u7a7a")
        })
        cleanCard.addView(NukeTheme.dangerButton(this, "\u6e05\u7a7a\u6240\u6709\u8bb0\u5f55") {
            AlertDialog.Builder(this)
                .setTitle("\u6e05\u7a7a\u6240\u6709\u8bb0\u5f55")
                .setMessage("\u5c06\u5220\u9664\u6240\u6709\u5df2\u5206\u6790\u5361\u7247\u4e0e\u7f13\u5b58\u7ed3\u679c\uff0c\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002")
                .setNegativeButton("\u53d6\u6d88", null)
                .setPositiveButton("\u6e05\u7a7a") { _, _ ->
                    com.luoliaoo.wechat.data.ResultCache.clearAll()
                    com.luoliaoo.wechat.data.HistoryStore.clearAll()
                    toast("\u5df2\u6e05\u7a7a\u6240\u6709\u5206\u6790\u8bb0\u5f55")
                }
                .create()
                .showStyled()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        /* ★ v10.3.0：追问流式输出速度。
           用户指定的位置是**模板管理界面**里那个「清空所有记录」下面 —— 全工程有两个界面
           都有这个按钮，第一版我加到了这里（设置页），用户在实际使用的地方找不到它。
           现在正确的位置由 TemplateDialog 挂载；这里保留一份是为了让设置页也能改到同一个值，
           两处共用 [StreamSpeedControl]，不会出现"两套实现行为不一致"。 */
        StreamSpeedControl.attach(cleanCard, topPad = 18)

        root.addView(cleanCard)

        setContentView(ScrollView(this).apply {
            background = NukeTheme.canvas(this@SettingsActivity)
            addView(root)
        })
    }

    // ------------------------------------------------------------- widgets

    /** The bold title inside a section card. */
    private fun cardTitle(t: String) = TextView(this).apply {
        text = t
        setTextColor(NukeTheme.TEXT)
        textSize = NukeTheme.sp(this@SettingsActivity, 18f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8), 0, 0)
    }

    /** A labelled number field sized for a row of two. */
    private fun numberField(label: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(6), 0)
            addView(NukeTheme.fieldLabel(this@SettingsActivity, label))
            addView(NukeTheme.input(this@SettingsActivity, value, ""))
        }

    /** The first EditText inside a container, or null when there is none. */
    private fun editTextIn(root: ViewGroup): EditText? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is EditText) return c
            if (c is ViewGroup) editTextIn(c)?.let { return it }
        }
        return null
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun readonly(t: String) = TextView(this).apply {
        text = t
        textSize = NukeTheme.sp(this@SettingsActivity, 14f)
        setTextColor(NukeTheme.TEXT_MUTED)
        background = NukeTheme.rounded(NukeTheme.INPUT_BG, 12, this@SettingsActivity, NukeTheme.INPUT_BORDER)
        setPadding(dp(15), dp(13), dp(15), dp(13))
    }

    /**
     * item 4: the environment block.
     *
     * `android.os.Build` needs no permission, so these values are always available -- unlike the WeChat
     * version, which needs the <queries> declaration.
     */
    private fun buildEnvText(): String = buildString {
        append("\u624b\u673a\u578b\u53f7=").append(android.os.Build.MANUFACTURER)
            .append(' ').append(android.os.Build.MODEL).append('\n')
        append("\u624b\u673a\u7cfb\u7edf\u7248\u672c=").append(android.os.Build.DISPLAY).append('\n')
        append("\u5b89\u5353\u7248\u672c=").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        append("LSPosed API 102").append('\n')
        // 5.0.0: one line per INSTALLED host, so both 微信 and 抖音 are listed when both are present and
        // the version's app is unambiguous now that there are two. Falls back to both hosts shown as
        // 未安装 when nothing is installed -- that is the <queries> symptom, and it must stay visible.
        for (h in installedHosts()) {
            // v8.1.0: the BRAND actually installed (TIM, not QQ) -- see HostApp.displayNameOf.
            append(com.luoliaoo.wechat.core.HostApp.displayNameOf(this@SettingsActivity, h))
                .append("\u7248\u672c=")
                .append(
                    com.luoliaoo.wechat.core.HostApp.versionOf(this@SettingsActivity, h)
                        ?.takeIf { it.isNotBlank() }
                        ?: com.luoliaoo.wechat.core.HostApp.NOT_INSTALLED,
                )
                .append('\n')
        }
        append("\u6a21\u5757\u7248\u672c=").append(com.luoliaoo.wechat.BuildInfo.VERSION_NAME)
    }

    private fun buildDiagText(): String = buildString {
        append("This page runs in the module process.\n\n")
        append("- dexkit native library: ").append(SoLoader.statusText()).append('\n')
        // Read the INSTALLED versions. A host version decides which obfuscated class names exist, so a
        // hard-coded value misleads exactly when it matters most -- e.g. on 8.0.68, where nf5.g4 is gone.
        // BOTH hosts are reported even when one is absent, so "is Douyin even supported?" is answerable.
        for ((hostApp, version) in com.luoliaoo.wechat.core.HostApp.versionBadges(this@SettingsActivity)) {
            append("- installed ")
                .append(com.luoliaoo.wechat.core.HostApp.displayNameOf(this@SettingsActivity, hostApp))
                .append(": ")
                .append(version ?: "not installed").append('\n')
        }
        // 5.0.0: the scope line names BOTH hosts, not just com.tencent.mm. The old line told a Douyin
        // user to enable 微信 in LSPosed, which is simply the wrong instruction. Main packages only --
        // the clone family is 25 entries and would swamp the block; its count is reported separately.
        // v8.1.0: a host with more than one package lists ALL of them, so a TIM user is told to tick
        // com.tencent.tim rather than being sent looking for com.tencent.mobileqq.
        append("- scope: ").append(
            com.luoliaoo.wechat.core.HostApp.REAL_HOSTS.joinToString(", ") { h ->
                h.packages.joinToString(" / ") + " (" +
                    com.luoliaoo.wechat.core.HostApp.displayNameOf(this@SettingsActivity, h) + ")"
            },
        ).append('\n')
        append("- clone families: ").append(
            com.luoliaoo.wechat.core.HostApp.scopePackages().size,
        ).append(" package(s) accepted\n")
        append("- cached analyses: ").append(com.luoliaoo.wechat.data.ResultCache.size()).append('\n')
        // The checklist names every host too: the two apps are ticked and force-stopped the same way, so
        // the old single-host wording was wrong for whichever one the user actually runs.
        // buildString's receiver is the StringBuilder, so `this` here is NOT the Activity.
        val names = com.luoliaoo.wechat.core.HostApp.installedNames(this@SettingsActivity)
            .ifEmpty { listOf(com.luoliaoo.wechat.core.HostApp.displayNameOf(this@SettingsActivity, host)) }
            .joinToString("\u3001")
        append("\nIf the long-press menu has no analyze item, check:\n")
        append("1) the module is enabled in LSPosed with ").append(names).append(" in scope\n")
        append("2) ").append(names).append(" was force-stopped and reopened\n")
        append("3) the LSPosed log for tag=LuoLiao\n")
    }

    private fun save() {
        val base = etBaseUrl.text.toString().trim().ifBlank { Config().baseUrl }
        val key = etApiKey.text.toString().trim()
        val model = etModel.text.toString().trim().ifBlank { Config().model }
        val prompt = etPrompt.text.toString().ifBlank { Config.DEFAULT_PROMPT }

        val old = ConfigRepo.load(this)
        ConfigRepo.save(
            this,
            old.copy(baseUrl = base, apiKey = key, model = model, systemPrompt = prompt),
        )
        SecureStore.saveEncrypted(this, key)
        // The scope fields on THIS page (向上 / 向下 / 时间区间 / 卡片宽度) are deliberately included:
        // they are edited on the same screen, and a 保存 that silently kept the OLD ones was another way
        // a value could look like it had been ignored.
        commitEverywhere()
        toast("\u5df2\u4fdd\u5b58\u5e76\u7acb\u5373\u751f\u6548")
    }

    /**
     * t4: makes the module-side config visible to EVERY reader at once.
     *
     * Two scopes must both be written: `save`/`load` for this app, and `saveLocal`/`loadLocal` plus the
     * cross-process mirror for the WeChat process. Doing only one of them is what produced "改了要重启
     * 微信" -- the WeChat side kept the old values until the app was reopened.
     */
    private fun commitEverywhere() {
        try {
            val c = ConfigRepo.load(this)
            ConfigRepo.save(this, c)
            ConfigRepo.saveLocal(this, c)
            ConfigRepo.writeMirror(this, c)
            // The legacy private mirror is kept: older installs still read it.
            writeMirror(c)
            Log.i(TAG, "config committed to both scopes + mirror")
        } catch (t: Throwable) {
            Log.w(TAG, "commitEverywhere failed: " + t.message)
        }
    }

    private fun toggleKeyVisibility() {
        val cur = etApiKey.inputType
        val isPwd = (cur and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        etApiKey.inputType = if (isPwd) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        etApiKey.setSelection(etApiKey.text.length)
    }

    private fun fetchModels() {
        val base = etBaseUrl.text.toString().trim()
        val key = etApiKey.text.toString().trim()
        if (base.isBlank() || key.isBlank()) {
            toast("\u8bf7\u5148\u586b\u5199 Base URL \u4e0e API Key")
            return
        }
        val modelNow = etModel.text.toString().trim().ifBlank { Config().model }
        val cfg = Config(baseUrl = base, apiKey = key, model = modelNow)

        toast("\u6b63\u5728\u83b7\u53d6\u6a21\u578b\u5217\u8868\u2026")
        io.execute {
            val res = try {
                Result.success(OpenAiClient(cfg).listModels())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            runOnUiThread {
                res.fold(
                    onSuccess = { models ->
                        if (models.isEmpty()) {
                            toast("\u63a5\u53e3\u672a\u8fd4\u56de\u4efb\u4f55\u6a21\u578b")
                        } else {
                            showModelPicker(models)
                        }
                    },
                    onFailure = { t ->
                        Log.w(TAG, "fetching model list failed: " + t.message)
                        AlertDialog.Builder(this)
                            .setTitle("\u83b7\u53d6\u6a21\u578b\u5217\u8868\u5931\u8d25")
                            .setMessage(t.message ?: "\u672a\u77e5\u9519\u8bef")
                            .setPositiveButton("\u77e5\u9053\u4e86", null)
                            .create()
                            .showStyled()
                    },
                )
            }
        }
    }

    private fun showModelPicker(models: List<String>) {
        val arr = models.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\u9009\u62e9\u6a21\u578b\uff08" + models.size + " \u4e2a\uff09")
            .setItems(arr) { _, which ->
                etModel.setText(arr[which])
                toast("\u5df2\u9009\u62e9 " + arr[which])
            }
            .setNegativeButton("\u53d6\u6d88", null)
            .create()
            .showStyled()
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    /**
     * Writes a plain `key=value` mirror of the config into the module's data dir.
     *
     * The WeChat process normally reads config through libxposed remote preferences; this mirror is a
     * fallback channel reachable through the framework's remote-file API.
     */
    private fun writeMirror(c: Config) {
        try {
            val f = java.io.File(filesDir, "luoliao_config.txt")
            val sb = StringBuilder()
            sb.append("base_url=").append(c.baseUrl).append('\n')
            sb.append("api_key=").append(c.apiKey).append('\n')
            sb.append("model=").append(c.model).append('\n')
            sb.append("system_prompt=")
                .append(c.systemPrompt.replace("\n", "\\n")).append('\n')
            sb.append("default_up=").append(c.defaultUp).append('\n')
            sb.append("default_down=").append(c.defaultDown).append('\n')
            sb.append("default_range_hours=").append(c.defaultRangeHours).append('\n')
            f.writeText(sb.toString())
            Log.i(TAG, "config mirror written: " + f.absolutePath + " len=" + f.length())
        } catch (t: Throwable) {
            Log.w(TAG, "writing config mirror failed: " + t.message)
        }
    }
}
