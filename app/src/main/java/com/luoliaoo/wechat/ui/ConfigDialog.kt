package com.luoliaoo.wechat.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.ai.OpenAiClient
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.TemplateStore
import java.util.concurrent.Executors

/**
 * Configuration panel, rendered INSIDE the WeChat process and styled after Nuke's WebUI.
 *
 * Why in-process: the config used to be written by the module app and read by the WeChat process
 * through a cross-process channel that returned nothing ("remotePrefs OK: entries=0", "api_key is
 * empty"), so analysis always aborted with "configure the API key first". Writing and reading in the
 * SAME process removes that whole class of problem.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object ConfigDialog {

    private const val TAG = "LuoLiao"
    private val io = Executors.newSingleThreadExecutor()

    /** Set while the panel is open, so its own buttons can close it. */
    @Volatile
    private var dismissRef: (() -> Unit)? = null

    /**
     * item 4: asks which template to use, then opens the configuration.
     *
     * The chooser comes first because a template decides the endpoint, the key, the model and the prompt
     * -- exactly the values this panel edits. When no template exists the panel opens directly, so the
     * user is never locked out of the settings.
     */
    fun showWithTemplateChoice(ctx: Context, onSaved: (() -> Unit)? = null) {
        // item 3: 配置 opens the TEMPLATE MANAGER. Each template has an 编辑 button that opens its own
        // configuration panel, so the manager is the single entry point for everything.
        Log.i(TAG, "opening the template manager as the config entry")
        TemplateDialog.show(ctx) { t ->
            TemplateDialog.applyToConfig(ctx, t)
            Log.i(TAG, "template applied from the manager: " + t.name)
        }
    }

    /**
     * item 3: the configuration panel for ONE template.
     *
     * @param templateName when set, saving writes the values back into that template (so every template
     *                     keeps its own address, key, model, prompt and default scope)
     */
    fun showForTemplate(ctx: Context, templateName: String, onSaved: (() -> Unit)? = null) {
        show(ctx, onSaved, templateName)
    }

    fun show(ctx: Context, onSaved: (() -> Unit)? = null, templateName: String? = null) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        // Keep the menu hook away from our own widgets (its entry is also called 分析).
        OwnUi.suppressFor(2500L)

        try {
            val cfg = ConfigRepo.loadLocal(ctx)
            Log.i(TAG, "config dialog: loaded current=" + cfg.baseUrl + " model=" + cfg.model +
                " keyPresent=" + cfg.apiKey.isNotBlank())

            // item 1: the template is looked up FRESH, every time the panel opens, and the result is
            // logged -- so "always 恋爱" can be diagnosed from a log instead of guessed at.
            val tmpl = templateName?.let {
                try {
                    TemplateStore.byName(it)
                } catch (_: Throwable) {
                    null
                }
            }
            Log.i(
                TAG,
                "config dialog for template='" + (templateName ?: "none") +
                    "' found=" + (tmpl != null) +
                    " model=" + (tmpl?.model ?: "-") +
                    " promptLen=" + (tmpl?.systemPrompt?.length ?: -1) +
                    " up=" + (tmpl?.defaultUp ?: -1) +
                    " hours=" + (tmpl?.defaultRangeHours ?: -1),
            )

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(12))
                tag = Constants.TAG_OWN_UI
            }

            // ---- header
            // 液态玻璃化：标题区不再手写 TextView + 写死 24f/BOLD，改用 NukeTheme.title。
            // 工厂内部走 NukeTheme.sp（自适应字体）与 TEXT token，所以宿主设为「超大字体」时
            // 标题会跟着缩放而不会撑破玻璃面板的外边距。
            content.addView(NukeTheme.eyebrow(ctx, "\u843d\u804a \u00b7 \u914d\u7f6e"))
            content.addView(
                NukeTheme.title(
                    ctx,
                    if (templateName != null) "\u6a21\u677f\uff1a$templateName" else "\u914d\u7f6e",
                    24f,
                ),
            )
            content.addView(NukeTheme.body(
                ctx,
                "\u63a5\u53e3\u7c7b\u578b\uff1aOpenAI \u517c\u5bb9\u63a5\u53e3",
                NukeTheme.TEXT_MUTED,
                12f,
            ).apply { setPadding(0, dp(6), 0, dp(16)) })

            // ---- status line: declared FIRST, because the template handlers near the top also report
            // their result through it.
            //
            // 玻璃化：这行状态原来是一个没有背景的裸文本，和上方玻璃卡之间缺少「块」的边界。
            // 给它一个 FEATURE_ALT 玻璃底（12dp 圆角 + 细描边），它就成了标题区和第一张卡之间的
            // 一条独立玻璃带；文字颜色仍由各处的 setTextColor 动态切换，逻辑未动。
            val tvStatus = TextView(ctx).apply {
                text = ""
                // 自适应字号：原来是裸 13f，会绕过 NukeTheme.sp 的字体缩放。
                textSize = NukeTheme.sp(ctx, 13f)
                setTextColor(NukeTheme.ACCENT)
                // 圆角：状态带是「控件级」→ R_CONTROL(12)。它原来就是 12，
                // 改成具名常量只是为了让全项目没有散落的圆角字面量（审计指出有 8 档）。
                background = NukeTheme.rounded(
                    NukeTheme.FEATURE_ALT, NukeTheme.R_CONTROL, ctx, NukeTheme.FEATURE_BORDER,
                )
                setPadding(dp(13), dp(11), dp(13), dp(11))
                setLineSpacing(dp(3).toFloat(), 1.4f)
                // 与玻璃卡之间留出呼吸感，避免和 API 卡贴在一起。
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(14) }

                // 状态行初始为空。带玻璃底的空 TextView 会在面板顶部留下一块「什么都没有的玻璃条」，
                // 所以这里按「有没有内容」自动显隐：空 → GONE（回到改造前完全不可见的观感），
                // 有内容 → VISIBLE。
                //
                // 这是这条玻璃带自己的显示状态，不参与任何业务判断，所以下面各处
                // `tvStatus.text = ...` 的调用点一行都不用改，也就不会碰到保存/校验逻辑。
                visibility = View.GONE
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                })
            }

            // ============================================================ API card
            val apiCard = NukeTheme.sectionCard(ctx)
            apiCard.addView(NukeTheme.category(ctx, "API"))

            apiCard.addView(NukeTheme.fieldLabel(ctx, "API Base URL"))
            // item 5: prefill from the EFFECTIVE value (shipped default when empty) but save what the
            // user actually types, so a saved value always wins.
            val etBase = NukeTheme.input(
                ctx,
                tmpl?.baseUrl?.ifBlank { null } ?: cfg.effectiveBaseUrl,
                "https://api.deepseek.com",
            )
            apiCard.addView(etBase)

            apiCard.addView(NukeTheme.fieldLabel(ctx, "API Key"))
            val etKey = NukeTheme.input(
                ctx,
                tmpl?.apiKey?.ifBlank { null } ?: cfg.apiKey,
                "sk-...",
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            apiCard.addView(etKey)
            apiCard.addView(NukeTheme.hintText(
                ctx,
                "\u5bc6\u94a5\u4ec5\u4fdd\u5b58\u5728\u672c\u673a\u914d\u7f6e\u4e2d\uff0c\u4e0d\u4f1a\u5199\u5165\u65e5\u5fd7",
            ))
            apiCard.addView(NukeTheme.link(ctx, "\u663e\u793a / \u9690\u85cf\u5bc6\u94a5") {
                val cur = etKey.inputType
                val isPwd = (cur and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                etKey.inputType = if (isPwd) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                etKey.setSelection(etKey.text.length)
            })

            apiCard.addView(NukeTheme.fieldLabel(ctx, "\u6a21\u578b"))
            val etModel = NukeTheme.input(
                ctx,
                tmpl?.model?.ifBlank { null } ?: cfg.effectiveModel,
                "deepseek-flash",
            )
            apiCard.addView(etModel)
            val tvFetch = NukeTheme.link(ctx, "\u4ece\u63a5\u53e3\u9009\u62e9\u6a21\u578b") {}
            apiCard.addView(tvFetch)

            apiCard.addView(NukeTheme.fieldLabel(ctx, "\u7cfb\u7edf\u63d0\u793a\u8bcd"))
            // item 1: the prompt comes from the TEMPLATE being edited.
            //
            // The previous code passed the template's prompt to the constructor and then called
            // `setText(cfg.systemPrompt)` inside apply{}, which OVERWROTE it with the shared config's
            // prompt (the love prompt). That is why every template appeared to be 恋爱.
            val etPrompt = NukeTheme.input(
                ctx,
                tmpl?.systemPrompt?.ifBlank { null } ?: cfg.systemPrompt,
                "",
            ).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
            apiCard.addView(etPrompt)
            content.addView(apiCard)

            // ============================================================ Skill management (v4.1.0)
            //
            // item 2: ABOVE the 默认分析范围 card.
            //
            // The order is the reading order of the page: the Skills shape WHAT the model is told, and the
            // scope below only limits HOW MUCH chat it reads. Requirements come before parameters.
            //
            // Shown only for a NAMED template: a Skill is keyed by template name, and the un-named panel
            // (the fallback config entry) has no template to attach one to.
            if (templateName != null) {
                try {
                    content.addView(com.luoliaoo.wechat.ui.SkillDialog.card(ctx, templateName))
                } catch (t: Throwable) {
                    Log.w(TAG, "skill section failed: " + t.message)
                }
            }

            // ============================================================ scope card
            // item 7: the default analysis scope belongs to the TEMPLATE, so it is edited here.
            val scopeCard = NukeTheme.sectionCard(ctx)
            scopeCard.addView(NukeTheme.category(ctx, "\u9ed8\u8ba4\u5206\u6790\u8303\u56f4"))

            // 玻璃化：这一行的「向上 / 条 / 向下 / 条」是裸 TextView + 裸 13f 字号。
            // 改成 NukeTheme.sp(ctx, 13f)，让它们和面板里其它文字一起跟随自适应字体缩放
            // （宿主设置「超大字体」时，原来这几个字不会缩，输入框却会缩，行内基线就对不齐了）。
            val rowCounts = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            rowCounts.addView(TextView(ctx).apply {
                text = "\u5411\u4e0a"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 13f)
            })
            val etUp = NukeTheme.input(ctx, (tmpl?.defaultUp ?: cfg.defaultUp).toString(), "3").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) }
            }
            rowCounts.addView(etUp)
            rowCounts.addView(TextView(ctx).apply {
                text = "\u6761"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 13f)
                setPadding(dp(4), 0, dp(16), 0)
            })
            rowCounts.addView(TextView(ctx).apply {
                text = "\u5411\u4e0b"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 13f)
            })
            val etDown = NukeTheme.input(ctx, (tmpl?.defaultDown ?: cfg.defaultDown).toString(), "3")
                .apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    layoutParams = LinearLayout.LayoutParams(
                        dp(64), ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
                }
            rowCounts.addView(etDown)
            rowCounts.addView(TextView(ctx).apply {
                text = "\u6761"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 13f)
                setPadding(dp(4), 0, 0, 0)
            })
            scopeCard.addView(rowCounts)

            scopeCard.addView(
                NukeTheme.fieldLabel(
                    ctx,
                    "\u65f6\u95f4\u533a\u95f4\u9ed8\u8ba4\u56de\u6eaf\uff08\u5c0f\u65f6\uff09",
                ),
            )
            val etHours = NukeTheme.input(
                ctx, (tmpl?.defaultRangeHours ?: cfg.defaultRangeHours).toString(), "24",
            ).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            scopeCard.addView(etHours)
            content.addView(scopeCard)

            fun current(): Config {
                // item 4: the floating-window fields are gone from this panel, so only the values that
                // belong to a TEMPLATE are read here.
                val base = ConfigRepo.loadLocal(ctx)
                return Config(
                    baseUrl = etBase.text.toString().trim().ifBlank { Config().baseUrl },
                    apiKey = etKey.text.toString().trim(),
                    model = etModel.text.toString().trim().ifBlank { Config().model },
                    systemPrompt = etPrompt.text.toString().ifBlank { Config.DEFAULT_PROMPT },
                    defaultUp = etUp.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 3,
                    defaultDown = etDown.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 3,
                    defaultRangeHours = etHours.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 24,
                    // The floating-window settings are NOT editable here; keep whatever is configured.
                    cardWidthChars = base.cardWidthChars,
                    panelWidthChars = base.panelWidthChars,
                    panelMaxHeightDp = base.panelMaxHeightDp,
                    accentColor = base.accentColor,
                    fontSp = base.fontSp,
                )
            }

            fun saveNow(): Boolean {
                val c = current()
                // item 3 / 7: when a template is being edited, the values go back into THAT template, so
                // each template keeps its own address, key, model, prompt and scope.
                if (templateName != null) {
                    try {
                        val old = TemplateStore.byName(templateName)
                        if (old != null) {
                            TemplateStore.update(
                                templateName,
                                old.copy(
                                    baseUrl = c.baseUrl,
                                    apiKey = c.apiKey,
                                    model = c.model,
                                    systemPrompt = c.systemPrompt,
                                    defaultUp = c.defaultUp,
                                    defaultDown = c.defaultDown,
                                    defaultRangeHours = c.defaultRangeHours,
                                ),
                            )
                            Log.i(TAG, "template saved: " + templateName + " model=" + c.model)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "saving the template failed: " + t.message)
                    }
                }
                if (c.apiKey.isBlank()) {
                    tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                    tvStatus.text = "\u8bf7\u586b\u5199 API Key"
                    return false
                }
                // t4: saveLocalAndMirror, so the module app sees the change without waiting for the next
                // time it happens to write a mirror itself. Previously only the local prefs were written.
                ConfigRepo.saveLocalAndMirror(ctx, c)
                val check = ConfigRepo.loadLocal(ctx)
                if (check.apiKey.isBlank()) {
                    tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                    tvStatus.text = "\u4fdd\u5b58\u5931\u8d25\uff1a\u8bf7\u91cd\u8bd5"
                    return false
                }
                // The mirror write is debounced; a deliberate save pushes it out at once.
                try {
                    ConfigRepo.flushMirror()
                } catch (_: Throwable) {
                    // best effort
                }
                // t4: the template may have changed the width / height / font scale, so a panel that is
                // already open is updated now rather than on its next open.
                com.luoliaoo.wechat.ui.FloatingPanel.onConfigGeometryChanged(ctx)
                com.luoliaoo.wechat.ui.NukeTheme.invalidateFontCache()
                tvStatus.setTextColor(NukeTheme.OK)
                tvStatus.text = "\u5df2\u4fdd\u5b58\uff08Key \u957f\u5ea6 " + check.apiKey.length + "\uff09"
                Log.i(TAG, "config saved in-process; keyLen=" + check.apiKey.length)
                Toast.makeText(ctx, "\u843d\u804a\uff1a\u914d\u7f6e\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
                return true
            }

            // "fetch models" wiring
            tvFetch.setOnClickListener {
                val c = current()
                if (c.baseUrl.isBlank() || c.apiKey.isBlank()) {
                    tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                    tvStatus.text = "\u8bf7\u5148\u586b\u5199 Base URL \u4e0e API Key"
                    return@setOnClickListener
                }
                tvStatus.setTextColor(NukeTheme.ACCENT)
                tvStatus.text = "\u6b63\u5728\u83b7\u53d6\u6a21\u578b\u5217\u8868\u2026"
                io.execute {
                    val res = try {
                        Result.success(OpenAiClient(c).listModels())
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    (ctx as? android.app.Activity)?.runOnUiThread {
                        res.fold(
                            onSuccess = { models ->
                                if (models.isEmpty()) {
                                    tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                                    tvStatus.text = "\u63a5\u53e3\u672a\u8fd4\u56de\u4efb\u4f55\u6a21\u578b"
                                } else {
                                    AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                        .setTitle("\u9009\u62e9\u6a21\u578b\uff08" + models.size + " \u4e2a\uff09")
                                        .setItems(models.toTypedArray()) { _, which ->
                                            etModel.setText(models[which])
                                            tvStatus.text = "\u5df2\u9009\u62e9 " + models[which]
                                        }
                                        .setNegativeButton("\u53d6\u6d88", null)
                                        .create()
                                        .showStyled()
                                }
                            },
                            onFailure = { t ->
                                tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                                tvStatus.text = "\u83b7\u53d6\u5931\u8d25\uff1a" + (t.message ?: "\u672a\u77e5\u9519\u8bef")
                            },
                        )
                    }
                }
            }

            // item 7: 取消 / 保存 as the SAME yellow buttons the scope panel uses, placed inside the
            // card so there is one consistent look.
            val buttonRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, dp(4))
                tag = Constants.TAG_OWN_UI
            }
            val btnCancel = NukeTheme.warnOutlineButton(ctx, "\u53d6\u6d88") { dismissRef?.invoke() }
            val btnSave = NukeTheme.warnButton(ctx, "\u4fdd\u5b58") {
                if (saveNow()) dismissRef?.invoke()
            }
            (btnSave.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            buttonRow.addView(btnCancel)
            buttonRow.addView(btnSave)
            content.addView(buttonRow)

            // The template form is long, so the panel scrolls.
            val scroll = ScrollView(ctx).apply {
                addView(content)
                tag = Constants.TAG_OWN_UI
            }

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .create()

            dlg.setCanceledOnTouchOutside(true)
            dlg.showStyled()
            dismissRef = { dlg.dismiss() }

            try {
                dlg.window?.setBackgroundDrawable(
                    // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                    NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, ctx),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "styling the config dialog window failed: " + t.message)
            }

            OwnUi.register(
                content,
                scroll,
                etBase,
                etKey,
                etModel,
                etPrompt,
                etHours,
                dlg.getButton(AlertDialog.BUTTON_POSITIVE),
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE),
                dlg.window?.decorView,
            )
            Log.i(TAG, "config dialog shown")
        } catch (t: Throwable) {
            Log.e(TAG, "config dialog FAILED", t)
            try {
                Toast.makeText(
                    ctx,
                    "\u843d\u804a\uff1a\u65e0\u6cd5\u6253\u5f00\u914d\u7f6e\uff08" + t.javaClass.simpleName + "\uff09",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) {
                // nothing else we can do
            }
        }
    }
}