package com.luoliaoo.wechat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.VisionLevel

/**
 * ★ v10.3.0：**识图模型配置** —— 一个按钮 + 一个弹窗。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【它解决的问题：主模型看不到图】
 *
 * 工程的默认模型是 `deepseek-flash`（纯文本）。把图片塞给它，轻则被忽略（静默失效），
 * 重则整个请求报错。而用户完全可能用任何模型 —— 这是本工程的设计前提。
 *
 * 所以识图做成**两次请求**：
 *
 *     图片 ──[识图模型]──> 一段文字描述 ──[模板模型]──> 分析结果
 *
 * 本弹窗就是在配第一步的那个模型。配上之后，**任何**模板模型都能用上识图能力。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么是「按钮 + 弹窗」而不是把四个字段直接铺在模板管理页上】
 *
 * 用户指定了这个形态。它也确实更合适：
 *   * 识图是**可选功能**，多数用户不会天天改。把四个字段（含一个说明、一个链接）
 *     常驻在模板管理页上，会把那一页撑得很长，而其中三项对多数人是噪音；
 *   * 弹窗允许把「Key 只存本机、不写日志」这类说明写足，而不会占用主页空间。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【为什么 baseUrl / apiKey 留空是合法的】
 *
 * 有一类用户的主模型本身就支持视觉（例如直接拿 `qwen-vl-plus` 当模板模型）。
 * 对他们来说识图模型 == 主模型，逼他们再填一遍 URL 和 Key 是纯粹的负担。
 * 所以这两项留空时**回落到主配置**（见 `Config.visionBaseUrlResolved`）。
 * 只有**模型名**是必填的 —— 不填模型名就无从发请求，所以它同时充当开关：
 * 留空 = 不启用识图。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object VisionConfigControl {

    private const val TAG = "LuoLiao"

    /** 与设置页同一条 Key 提示语，保证两处说法一致。 */
    private const val KEY_HINT = "密钥仅保存在本机配置中，不会写入日志"

    /**
     * 在 [parent] 上挂一个"配置识图模型"按钮。
     *
     * @param topPad 与上一个控件之间的间距（dp）。
     */
    fun attach(parent: LinearLayout, topPad: Int = 18) {
        val ctx = parent.context
        fun dp(v: Int) = NukeTheme.dp(ctx, v)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(topPad), 0, 0)
            tag = Constants.TAG_OWN_UI
        }

        box.addView(NukeTheme.fieldLabel(ctx, "识图模型"))

        // 状态行：让用户不点进弹窗也知道**当前有没有配**。
        // 这是必要的 —— 弹窗形态下配置状态被藏起来了，没有一个外部指示器的话，
        // 用户会反复点进去确认"我到底配没配"。
        val tvState = NukeTheme.body(ctx, "", NukeTheme.TEXT_DIM, 11f)
        fun refreshState() {
            val c = try {
                com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx)
            } catch (_: Throwable) {
                null
            }
            tvState.text = if (c != null && c.visionModel.isNotBlank()) {
                "当前： " + c.visionModel + "（" + VisionLevel.name(c.visionLevel) + "）"
            } else {
                "未配置。配置后，读取预览里每条图片消息左侧会出现「识图」开关。"
            }
        }

        box.addView(
            NukeTheme.primaryButton(ctx, "配置识图模型") {
                showDialog(ctx, onSaved = { refreshState() })
            },
        )
        box.addView(tvState.apply { setPadding(0, dp(6), 0, 0) })
        refreshState()

        parent.addView(box)
    }

    /**
     * 弹出配置窗口。
     *
     * 【保存时机】只在点「保存」时写盘，不做输入即保存。
     * 理由见 `persist` 的说明 —— 这里有三个文本框，逐字保存会产生大量中间态写入。
     */
    fun showDialog(ctx: Context, onSaved: () -> Unit = {}) {
        val activity = findActivity(ctx) ?: ctx
        fun dp(v: Int) = NukeTheme.dp(activity, v)

        val cfg = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "vision dialog: reading config failed: " + t.message)
            com.luoliaoo.wechat.data.Config()
        }

        /* ── 内容容器 ──────────────────────────────────────────────────────────
           套一层 ScrollView：在小屏手机 + 大字体设置下，四个字段 + 说明会超出屏幕高度。
           不套的话底部按钮会被挤出可见区域，用户就点不到保存。 */
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
            tag = Constants.TAG_OWN_UI
        }

        // ── API Base URL ──────────────────────────────────────────────────────
        content.addView(NukeTheme.fieldLabel(activity, "API Base URL"))
        val etBase = NukeTheme.input(activity, cfg.visionBaseUrl, "请输入地址")
        content.addView(etBase)
        content.addView(
            NukeTheme.hintText(activity, "留空则使用模板里那一个地址"),
        )

        // ── API Key ───────────────────────────────────────────────────────────
        content.addView(
            NukeTheme.fieldLabel(activity, "API Key").apply { setPadding(0, dp(12), 0, 0) },
        )
        val etKey = NukeTheme.input(activity, cfg.visionApiKey, "sk-XX").apply {
            // 默认遮蔽，与设置页一致 —— Key 是敏感信息，不该一打开弹窗就明晃晃地显示。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(etKey)
        // 用户要求的两条提示，逐字采用。
        content.addView(NukeTheme.hintText(activity, KEY_HINT))
        content.addView(NukeTheme.link(activity, "显示 / 隐藏密钥") { toggleKeyVisibility(etKey) })
        content.addView(NukeTheme.hintText(activity, "留空则使用模板里那一个 Key"))

        // ── 模型 ──────────────────────────────────────────────────────────────
        content.addView(
            NukeTheme.fieldLabel(activity, "模型").apply { setPadding(0, dp(12), 0, 0) },
        )
        val etModel = NukeTheme.input(activity, cfg.visionModel, "请填写可识图的模型")
        content.addView(etModel)
        content.addView(
            NukeTheme.hintText(activity, "请填写可识图的模型，否则无法使用（可直接手填）"),
        )
        content.addView(
            NukeTheme.link(activity, "从接口选择模型") {
                fetchModels(activity, etBase, etKey, etModel)
            },
        )

        // ── 视觉等级 ──────────────────────────────────────────────────────────
        content.addView(
            NukeTheme.fieldLabel(activity, "视觉等级").apply { setPadding(0, dp(14), 0, 0) },
        )
        content.addView(
            NukeTheme.hintText(activity, "等级只改变描述图片的详细程度，不会换模型"),
        )

        val tvLevelHint = NukeTheme.body(activity, "", NukeTheme.TEXT_DIM, 11f)
        val levelButtons = ArrayList<TextView>(VisionLevel.ALL.size)
        var selectedLevel = VisionLevel.clamp(cfg.visionLevel)

        fun paintLevels() {
            // 具名函数保证"所有按钮的外观永远一致地反映 selectedLevel"。
            // 若在每个 onClick 里各写一遍，很容易出现点了 A 之后 B 还亮着（漏更新一处）。
            for ((i, btn) in levelButtons.withIndex()) {
                val lv = VisionLevel.ALL[i]
                val on = lv == selectedLevel
                btn.setTextColor(if (on) NukeTheme.TEXT else NukeTheme.TEXT_DIM)
                btn.background = if (on) {
                    NukeTheme.rounded(
                        glassVeil(NukeTheme.ACCENT_STRONG, 0x33),
                        NukeTheme.R_CHIP, activity, NukeTheme.ACCENT_STRONG,
                    )
                } else {
                    NukeTheme.rounded(
                        glassVeil(NukeTheme.TEXT_DIM, 0x18),
                        NukeTheme.R_CHIP, activity, NukeTheme.INPUT_BORDER,
                    )
                }
            }
            tvLevelHint.text = VisionLevel.costHint(selectedLevel)
        }

        val levelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        for (lv in VisionLevel.ALL) {
            val btn = TextView(activity).apply {
                text = VisionLevel.name(lv)
                textSize = NukeTheme.sp(activity, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                // 48dp 触控高度：本控件自绘，不走 NukeTheme 的工厂方法，必须自己保证。
                minimumHeight = dp(48)
                isClickable = true
                tag = Constants.TAG_OWN_UI
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener {
                    selectedLevel = lv
                    paintLevels()
                }
            }
            levelButtons.add(btn)
            levelRow.addView(btn)
        }
        content.addView(levelRow)
        content.addView(tvLevelHint.apply { setPadding(0, dp(6), 0, 0) })

        // 用户要求的那句成本提示，逐字采用。
        content.addView(
            NukeTheme.body(
                activity,
                "注：不同等级消耗的 token 不一样，请合理分配。",
                NukeTheme.TEXT_DIM,
                11f,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )

        val scroll = ScrollView(activity).apply {
            addView(content)
            tag = Constants.TAG_OWN_UI
        }

        val dlg = android.app.AlertDialog.Builder(activity)
            .setTitle("配置识图模型")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("清空", null)
            .setNegativeButton("取消", null)
            .create()

        dlg.setOnShowListener {
            /* 「保存」用 null listener 创建、在这里挂真实回调，是为了能在校验失败时
               **不关闭弹窗**。用 Builder 的 listener 形式则一定会关闭 ——
               用户填错了模型名会丢掉刚输入的全部内容，只能重填一遍。
               这是本工程里配置类弹窗的既有做法（SettingsActivity 同）。 */
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                persist(
                    activity,
                    etModel.text?.toString().orEmpty(),
                    etBase.text?.toString().orEmpty(),
                    etKey.text?.toString().orEmpty(),
                    selectedLevel,
                )
                onSaved()
                dlg.dismiss()
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                // 「清空」= 关闭识图（把模型名清掉）。不删 URL/Key，
                // 因为用户下次可能只想重填模型名，把 Key 也删了等于逼他重新找一遍。
                etModel.setText("")
                persist(activity, "", etBase.text?.toString().orEmpty(),
                    etKey.text?.toString().orEmpty(), selectedLevel)
                onSaved()
                dlg.dismiss()
            }
        }
        dlg.showStyled()
    }

    /**
     * 遮蔽 / 显示 API Key。
     *
     * 与 `SettingsActivity.toggleKeyVisibility` 同一套 InputType 切换，不另发明一种做法。
     * 切换后把光标移到末尾：Android 在切换 inputType 时会重置光标位置，
     * 不移动的话用户接着打字会从中间插入，看起来像是输入错乱。
     */
    private fun toggleKeyVisibility(et: EditText) {
        val cur = et.inputType
        val isPwd = (cur and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
        et.inputType = if (isPwd) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        et.setSelection(et.text?.length ?: 0)
    }

    /**
     * 从识图接口拉取模型列表，让用户点选。
     *
     * 【为什么要单独用识图那三项去拉，而不是用主配置】
     * 因为识图模型常常与主模型**不是同一家**（主力 DeepSeek 做分析、识图用另一家视觉服务）。
     * 用主配置去拉，会列出主模型那家的模型名，而用户在找的是另一家的 —— 列表完全没用。
     *
     * 【为什么允许 baseUrl/Key 留空时仍能拉】此时回落到主配置，
     * 正好服务于"我的主模型本来就支持视觉"那类用户：他们什么都不填，
     * 直接点这个链接就能看到自己那家的可用模型。
     */
    private fun fetchModels(
        activity: Context,
        etBase: EditText,
        etKey: EditText,
        etModel: EditText,
    ) {
        val current = try {
            com.luoliaoo.wechat.data.ConfigRepo.loadLocal(activity)
        } catch (_: Throwable) {
            com.luoliaoo.wechat.data.Config()
        }
        // 用界面上**当前输入的**值（而不是已保存的值）去拉，这样用户改完地址就能立刻试，
        // 不必先保存一次。留空则回落到主配置 —— 与真正发请求时的规则完全一致。
        val base = etBase.text?.toString()?.trim().orEmpty().ifBlank { current.baseUrl }
        val key = etKey.text?.toString()?.trim().orEmpty().ifBlank { current.apiKey }

        if (base.isBlank() || key.isBlank()) {
            toast(activity, "请先填写地址与 Key")
            return
        }

        val probe = current.copy(
            baseUrl = base,
            apiKey = key,
            model = etModel.text?.toString()?.trim().orEmpty().ifBlank { current.model },
        )

        toast(activity, "正在获取模型列表…")
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        io.execute {
            val res = try {
                Result.success(com.luoliaoo.wechat.ai.OpenAiClient(probe).listModels())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            (activity as? Activity)?.runOnUiThread {
                res.fold(
                    onSuccess = { models ->
                        if (models.isEmpty()) {
                            toast(activity, "接口未返回任何模型")
                        } else {
                            pickModel(activity, models, etModel)
                        }
                    },
                    onFailure = { t ->
                        Log.w(TAG, "vision: fetching model list failed: " + t.message)
                        android.app.AlertDialog.Builder(activity)
                            .setTitle("获取模型列表失败")
                            .setMessage(t.message ?: "未知错误")
                            .setPositiveButton("知道了", null)
                            .create()
                            .showStyled()
                    },
                )
            }
            io.shutdown()
        }
    }

    /**
     * 模型选择列表。
     *
     * 【为什么要提示"没看到视觉模型不代表不能用"】
     * 多数服务商的 `/models` 接口**只返回模型 id，不标明哪些支持视觉** ——
     * 协议里根本没有这个字段。所以列表里不会出现"可识图"的标记。
     * 若不说明，用户会以为"这个接口里没有能识图的模型"，从而放弃。
     */
    private fun pickModel(activity: Context, models: List<String>, etModel: EditText) {
        val arr = models.toTypedArray()
        android.app.AlertDialog.Builder(activity)
            .setTitle("选择模型（" + models.size + " 个）")
            .setMessage("接口不标注哪些模型支持识图，请自行选择可识图的模型。")
            .setItems(arr) { _, which ->
                etModel.setText(arr[which])
                etModel.setSelection(arr[which].length)
                toast(activity, "已选择 " + arr[which])
            }
            .setNegativeButton("取消", null)
            .create()
            .showStyled()
    }

    /**
     * 写盘 —— 并**同时写镜像**。
     *
     * ⚠️【必须用 saveLocalAndMirror，不能用 saveLocal】
     * 这是本工程反复踩过的坑（`balloon_alpha`、`voice_to_text` 都栽过）：
     * 界面在**模块 App 进程**里写，而真正发识图请求的是**微信进程**。
     * 只写本地 prefs 的话，微信进程读不到 —— 表现为"填了模型但永远不生效"，
     * 而且**没有任何报错**，是最难排查的一类失败。
     *
     * 【为什么只在「保存」时写，不做输入即保存】
     * 这里有三个文本框，模型名会被逐字打出来（"qwen-vl-plus" 有 12 个字符）。
     * 每敲一个字符就写一次镜像文件会白跑十几次磁盘写入，而且中间态（"q"、"qw"…）
     * 会短暂地被宿主进程读到并拿去发请求 → 必然失败并刷一屏日志。
     */
    private fun persist(
        ctx: Context,
        model: String,
        baseUrl: String,
        apiKey: String,
        level: Int,
    ) {
        try {
            val old = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx)
            val updated = old.copy(
                visionModel = model.trim(),
                visionBaseUrl = baseUrl.trim(),
                visionApiKey = apiKey.trim(),
                visionLevel = VisionLevel.clamp(level),
            )
            com.luoliaoo.wechat.data.ConfigRepo.saveLocalAndMirror(ctx, updated)
            Log.i(
                TAG,
                // ⚠️ 只记"有没有配"，**绝不记 Key 本身** —— 与那条面向用户的承诺一致。
                "vision config saved: model=" +
                    updated.visionModel.ifBlank { "(empty)" } +
                    " level=" + VisionLevel.name(updated.visionLevel) +
                    " customBase=" + updated.visionBaseUrl.isNotBlank() +
                    " customKey=" + updated.visionApiKey.isNotBlank(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "vision config: saving failed: " + t.message)
        }
    }

    /** 从任意 Context 里找出 Activity —— 弹窗必须用 Activity 而不是 Application。 */
    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // 宿主进程里 Toast 可能不可用；弹窗本身照常工作。
        }
    }
}

/**
 * 只给现有令牌换一层透明度，用来派生玻璃高光 / 淡色底。
 *
 * 【为什么本文件要自带一份】它在本工程里是**每个对话框各有一份**的私有工具
 * （`TemplateDialog` / `ScopeDialog` / `SkillDialog` / `ManualAnalysisDialog` 都各有一份），
 * 刻意没有抽成公共函数 —— 各自的 alpha 取值与设计意图不同。
 * 这里沿用同样的做法；若要抽取公共实现，那四个文件应一并迁移，
 * 那是一次独立改动，不应夹在本功能里顺带做。
 */
private fun glassVeil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
