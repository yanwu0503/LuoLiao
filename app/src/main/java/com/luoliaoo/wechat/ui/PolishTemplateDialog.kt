package com.luoliaoo.wechat.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.ai.OpenAiClient
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.PolishTemplate
import com.luoliaoo.wechat.data.PolishTemplateStore
import java.util.concurrent.Executors

/**
 * v4.0.0: management for the TEXT-POLISHING templates.
 *
 * This is the polish counterpart of [TemplateDialog], and it deliberately stays SEPARATE from it: the
 * analysis templates (恋爱 / 工作 / 快速) carry an address, a key, a model and an analysis prompt, while a
 * polish template is only a name plus a rewriting prompt. Mixing the two would let editing one silently
 * change the other.
 *
 * A template can be added, renamed and deleted, exactly like the analysis ones, and 恢复默认 brings back
 * the two shipped templates (恋爱 / 工作).
 */
object PolishTemplateDialog {

    private const val TAG = "LuoLiao"

    /**
     * v10.3.1：拉取模型列表用的单线程执行器。
     *
     * 「从接口选择模型」是一次**网络请求**，绝不能跑在 UI 线程上（会冻结宿主的聊天界面）。
     * 与 [ConfigDialog] 同款做法，单线程足够 —— 用户不可能同时在两处拉列表。
     */
    private val io = Executors.newSingleThreadExecutor()

    /** 网络结果回到 UI 线程，才能碰视图。 */
    private val main = Handler(Looper.getMainLooper())

    /** Opens the manager. `onChanged` is called whenever the store was modified. */
    fun show(ctx: Context, onChanged: (() -> Unit)? = null) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        OwnUi.suppressFor(3000L)

        try {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(8))
                tag = Constants.TAG_OWN_UI
            }

            content.addView(NukeTheme.eyebrow(ctx, "落聊 · 文本优化"))
            // 液态玻璃化：原先这里用裸 TextView(22f) 当标题、下一行又用 category(
            // 把「优化模板」当小标签用，两处语义是反的。改用内核的 title 工厂，
            // 标题与 SkillDialog / SettingsActivity 的标题层级一致（同为 22f 语义），
            // 并自动获得自适应字体与 TEXT token（明暗双主题都自动跟随）。
            content.addView(NukeTheme.title(ctx, "优化模板", 22f))
            content.addView(
                NukeTheme.body(
                    ctx,
                    "每个模板决定文本优化时的改写语气。选中的模板会立即生效，并记住到下次",
                    NukeTheme.TEXT_MUTED,
                    12f,
                ).apply { setPadding(0, dp(6), 0, dp(12)) },
            )

            val listBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            fun refresh() {
                listBox.removeAllViews()
                val all = try {
                    PolishTemplateStore.all()
                } catch (t: Throwable) {
                    Log.w(TAG, "polish template list failed: " + t.message)
                    emptyList()
                }
                if (all.isEmpty()) {
                    listBox.addView(
                        NukeTheme.body(ctx, "没有模板，可点「添加」新建一个", NukeTheme.TEXT_MUTED, 13f),
                    )
                }
                val selected = try {
                    PolishTemplateStore.selectedName()
                } catch (_: Throwable) {
                    ""
                }
                for (t in all) {
                    val card = NukeTheme.sectionCard(ctx)
                    val isActive = t.name == selected

                    // The active template is marked in the header AND bordered, so "which one is in
                    // effect" is never ambiguous.
                    //
                    // NOTE the explicit buildString: writing this as
                    //     t.name + if (isActive) " · 使用中" else "" + if (t.builtIn) " · 自带" else ""
                    // is a PRECEDENCE bug -- the else-branch swallows the second condition, so an active
                    // built-in template silently lost its 自带 marker.
                    card.addView(
                        NukeTheme.category(
                            ctx,
                            buildString {
                                append(t.name)
                                if (isActive) append("  · 使用中")
                                if (t.builtIn) append("  · 自带")
                            },
                        ),
                    )
                    if (isActive) {
                        // 液态玻璃化：选中态原来是 CARD 填充 + ACCENT 描边。玻璃化之后卡本体是
                        // featurePanel 的渐变玻璃，换成 CARD 纯色反而把玻璃质感抹掉了，所以选中态
                        // 改成「玻璃梯度底 FEATURE + ACCENT 描边」，和 TextPolishDialog 里
                        // 选中方案的写法完全一致（同一个「选中」在两处不能有两种样子）。
                        // 圆角：模板卡是「卡片级」→ R_CARD(18)（原先硬编码 18 字面量，
                        // 审计指出全项目圆角有 8 档，改用具名常量后与 sectionCard 本体保持一致）。
                        card.background = NukeTheme.panel(
                            NukeTheme.FEATURE, NukeTheme.ACCENT, NukeTheme.R_CARD, ctx,
                        )
                    }
                    card.addView(
                        TextView(ctx).apply {
                            // A short taste of the prompt, so two templates are told apart at a glance.
                            text = t.systemPrompt.trim().replace('\n', ' ').take(60) +
                                if (t.systemPrompt.trim().length > 60) "…" else ""
                            setTextColor(NukeTheme.TEXT_DIM)
                            // 自适应字号：原为裸 11f，会绕过 NukeTheme.sp 的字体缩放。
                            textSize = NukeTheme.sp(ctx, 11f)
                            setPadding(0, dp(6), 0, 0)
                        },
                    )

                    // ★ v10.3.1：这一个模板用的是哪个模型。
                    //
                    // 【为什么列出来而不是只在编辑表单里看】模板可以自带模型，而**列表页是唯一
                    // 能同时比较几个模板的地方** —— 两个语气模板配两个不同模型是很自然的用法，
                    // 只让用户在编辑里逐个点开看，他根本不知道自己现在用的是哪一个。
                    //
                    // 【为什么三项只写模型 + 是否自带接口】模型名是用户唯一关心的、能立刻验证的东西；
                    // 地址与 Key 写成一行会把卡片撑满，而"是否自带接口"这一个布尔已经足够回答
                    // "这个模板会不会跟着主配置走"。三项都不填时明确写「API：跟随主配置」——
                    // 这比留空好，因为留空会让用户以为"这项没做"。
                    card.addView(
                        TextView(ctx).apply {
                            text = if (PolishTemplateStore.hasOwnApi(t)) {
                                "模型：" + t.model.ifBlank { "跟随主配置" } + "  ·  自带接口"
                            } else {
                                "模型：" + t.model.ifBlank { "跟随主配置" } + "  ·  跟随主配置"
                            }
                            setTextColor(NukeTheme.TEXT_DIM)
                            textSize = NukeTheme.sp(ctx, 11f)
                            setPadding(0, dp(3), 0, 0)
                        },
                    )

                    val buttons = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(10), 0, 0)
                    }
                    buttons.addView(
                        NukeTheme.warnButton(ctx, if (isActive) "使用中" else "使用") {
                            try {
                                PolishTemplateStore.select(t.name)
                                Log.i(TAG, "polish template selected: " + t.name)
                                onChanged?.invoke()
                            } catch (e: Throwable) {
                                Log.w(TAG, "selecting the polish template failed: " + e.message)
                            }
                            refresh()
                        }.apply {
                            if (isActive) {
                                alpha = 0.6f
                                isEnabled = false
                            }
                        },
                    )
                    buttons.addView(
                        NukeTheme.warnOutlineButton(ctx, "编辑") {
                            edit(ctx, t) { refresh(); onChanged?.invoke() }
                        },
                    )
                    buttons.addView(
                        NukeTheme.dangerButton(ctx, "删除") {
                            try {
                                PolishTemplateStore.remove(t.name)
                                Log.i(TAG, "polish template deleted: " + t.name)
                                onChanged?.invoke()
                            } catch (e: Throwable) {
                                Log.w(TAG, "deleting the polish template failed: " + e.message)
                            }
                            refresh()
                        }.apply {
                            (layoutParams as? LinearLayout.LayoutParams)?.let {
                                it.width = 0
                                it.weight = 1f
                            }
                        },
                    )
                    card.addView(buttons)
                    listBox.addView(card)
                }
            }
            refresh()
            content.addView(listBox)

            content.addView(
                TextView(ctx).apply {
                    text = "存储位置：" + try {
                        PolishTemplateStore.dirPath()
                    } catch (_: Throwable) {
                        "unknown"
                    }
                    setTextColor(NukeTheme.TEXT_DIM)
                    // 自适应字号：原为裸 10f（全项目最小的字号），在「超大字体」宿主上会小到看不清，
                    // 同时又会绕过 NukeTheme.sp。改用 sp 版本后随字体缩放收敛。
                    textSize = NukeTheme.sp(ctx, 10f)
                    setPadding(0, dp(6), 0, 0)
                    setTextIsSelectable(true)
                },
            )

            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, dp(4))
            }
            actions.addView(
                NukeTheme.warnButton(ctx, "添加") {
                    edit(ctx, null) { refresh(); onChanged?.invoke() }
                },
            )
            actions.addView(
                NukeTheme.warnOutlineButton(ctx, "恢复默认") {
                    try {
                        PolishTemplateStore.restoreDefaults()
                        Log.i(TAG, "polish template defaults restored")
                        onChanged?.invoke()
                    } catch (e: Throwable) {
                        Log.w(TAG, "restoring polish template defaults failed: " + e.message)
                    }
                    refresh()
                },
            )
            content.addView(actions)

            val scroll = ScrollView(ctx).apply {
                addView(content)
                tag = Constants.TAG_OWN_UI
            }

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create()
            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(
                    // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                    NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, ctx),
                )
            } catch (_: Throwable) {
                // best effort
            }
            OwnUi.register(content, scroll, dlg.window?.decorView)
            Log.i(TAG, "polish template dialog shown")
        } catch (t: Throwable) {
            Log.e(TAG, "polish template dialog failed", t)
        }
    }

    /**
     * The add / edit form.
     *
     * A rename goes through [PolishTemplateStore.rename] rather than add+remove, so the template keeps its
     * position in the list.
     *
     * ★ v10.3.1：表单新增 **API Base URL / API Key / 模型** 三项（用户需求：
     * 「切换模板内新增填写 API Base URL 与 API Key 与 模型，功能跟模板管理里面的编辑差不多」），
     * 于是文本优化也能自选模型。
     *
     * 【字段顺序刻意与 [TemplateDialog] 的编辑表单对齐】名称 → 地址 → Key → 模型 → 提示词。
     * 两个表单编辑的是同一类东西（"一个模板"），读起来必须是同一个顺序；
     * 只在最后分叉（这里是「改写提示词」，那里是「提示词 + 屏蔽词」）。
     *
     * 【三项都留空 = 用主配置】这不是"没实现"，而是一等公民的行为：内置的两个模板就是这一档，
     * 所以老用户升级后**什么都不用填**，行为与 v10.3.0 逐字节一致。表单里用一行浅色提示
     * 把这件事讲明白，否则用户会以为"这三个框一定要填"。
     */
    fun edit(ctx: Context, existing: PolishTemplate?, done: () -> Unit) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        OwnUi.suppressFor(3000L)
        try {
            val form = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(6))
                tag = Constants.TAG_OWN_UI
            }
            // 液态玻璃化：表单标题改用 NukeTheme.title 工厂（原来手写 17f + BOLD），
            // 让它和列表页标题、SkillDialog 标题走同一套自适应字体与 token。
            form.addView(
                NukeTheme.title(
                    ctx,
                    if (existing == null) "新建优化模板" else "编辑优化模板",
                    17f,
                ),
            )

            form.addView(NukeTheme.fieldLabel(ctx, "名称"))
            val etName = NukeTheme.input(ctx, existing?.name.orEmpty(), "恋爱").apply {
                tag = Constants.TAG_OWN_UI
            }
            form.addView(etName)

            // ==================================================== v10.3.1：API 三项
            //
            // 主配置（ConfigDialog）里也有同样三项，两者是**回落关系**而不是副本：
            // 这里填了什么就用什么，没填就用主配置那一份。所以下面每个框的 hint 都写清楚
            // 「留空 = 用主配置」，并在下方给一行统一说明。
            form.addView(NukeTheme.fieldLabel(ctx, "API Base URL"))
            // 预填用**模板自己的值**（可能为空），而不是主配置的值：
            // 若把主配置的地址预填进来，用户一按保存就会把主配置**复制**进这个模板，
            // 从此主配置再改、这个模板不跟着变 —— 一个看不见的"配置分叉"。
            // 留空才是"跟着主配置走"的诚实表达。
            val etBase = NukeTheme.input(ctx, existing?.baseUrl.orEmpty(), "留空 = 用主配置").apply {
                tag = Constants.TAG_OWN_UI
            }
            form.addView(etBase)

            form.addView(NukeTheme.fieldLabel(ctx, "API Key"))
            val etKey = NukeTheme.input(ctx, existing?.apiKey.orEmpty(), "留空 = 用主配置").apply {
                // 与 ConfigDialog 同款：密钥默认遮蔽，并给一个显式的显示/隐藏开关。
                // 密码输入法的意义不只是防窥屏，也避免第三方输入法把 Key 记进它的词库。
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                tag = Constants.TAG_OWN_UI
            }
            form.addView(etKey)
            form.addView(
                NukeTheme.link(ctx, "显示 / 隐藏密钥") {
                    val cur = etKey.inputType
                    val isPwd = (cur and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                    etKey.inputType = if (isPwd) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    // 切完把光标放到末尾，否则会跳到开头（用户接着往下打字就会插在中间）。
                    etKey.setSelection(etKey.text.length)
                },
            )

            form.addView(NukeTheme.fieldLabel(ctx, "模型"))
            val etModel = NukeTheme.input(ctx, existing?.model.orEmpty(), "留空 = 用主配置").apply {
                tag = Constants.TAG_OWN_UI
            }
            form.addView(etModel)

            // ---- 「从接口选择模型」，与 ConfigDialog 的能力对齐
            //
            // 【为什么它读的是**表单里刚打的**地址与 Key，而不是已保存的主配置】
            // 因为这个功能要回答的问题是"这个地址上有什么模型"。用主配置的地址去问，
            // 拿回来的列表属于**另一个服务商** —— 用户选了它，保存下来的却是一个该服务端
            // 根本不存在的模型名，请求必然失败。所以探针必须用表单当前的值。
            //
            // 【留空时回落主配置，是刻意的】留空在保存后确实就是"用主配置的地址/Key"，
            // 所以拿主配置去探针正是**同一次请求将来实际会走的那条路**，两者一致。
            // 若这里报"请先填写 Base URL"，用户会以为留空是非法状态。
            val tvFetchStatus = TextView(ctx).apply {
                text = ""
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, dp(4), 0, 0)
                // 空状态隐藏，避免在表单里留一条什么都不说的空隙（与 TextPolishDialog 的状态行同款）。
                visibility = View.GONE
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                })
            }
            form.addView(
                NukeTheme.link(ctx, "从接口选择模型") {
                    val typedBase = etBase.text.toString().trim()
                    val typedKey = etKey.text.toString().trim()
                    val probe = try {
                        val shared = ConfigRepo.loadLocal(ctx)
                        Config(
                            baseUrl = typedBase.ifBlank { shared.effectiveBaseUrl },
                            apiKey = typedKey.ifBlank { shared.apiKey },
                            model = etModel.text.toString().trim().ifBlank { shared.effectiveModel },
                        )
                    } catch (_: Throwable) {
                        Config(baseUrl = typedBase, apiKey = typedKey)
                    }
                    if (probe.apiKey.isBlank()) {
                        // 两个来源都空 -> 拿不到列表。这是**真的缺 Key**，必须说清楚缺的是哪一个，
                        // 而不是笼统地说"获取失败"。
                        tvFetchStatus.setTextColor(NukeTheme.DANGER_TEXT)
                        tvFetchStatus.text = "请先填写 API Key（本模板或主配置任一处）"
                        return@link
                    }
                    tvFetchStatus.setTextColor(NukeTheme.ACCENT)
                    tvFetchStatus.text = "正在获取模型列表…"
                    io.execute {
                        val res = try {
                            Result.success(OpenAiClient(probe).listModels())
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                        // 对话框可能已被关掉；往一个已销毁的视图写文本会抛异常。
                        // 这里只做"尽力而为"的更新，失败就放弃（提示本来也不是关键路径）。
                        main.post {
                            try {
                                res.fold(
                                    onSuccess = { models ->
                                        if (models.isEmpty()) {
                                            tvFetchStatus.setTextColor(NukeTheme.DANGER_TEXT)
                                            tvFetchStatus.text = "接口未返回任何模型（可直接手动填写）"
                                        } else {
                                            tvFetchStatus.setTextColor(NukeTheme.OK)
                                            tvFetchStatus.text = "已获取 " + models.size + " 个，请选择"
                                            AlertDialog.Builder(
                                                ctx,
                                                android.R.style.Theme_DeviceDefault_Dialog_Alert,
                                            )
                                                .setTitle("选择模型（" + models.size + " 个）")
                                                .setItems(models.toTypedArray()) { _, which ->
                                                    etModel.setText(models[which])
                                                    tvFetchStatus.text = "已选择 " + models[which]
                                                }
                                                .setNegativeButton("取消", null)
                                                .create()
                                                .showStyled()
                                        }
                                    },
                                    onFailure = { t ->
                                        tvFetchStatus.setTextColor(NukeTheme.DANGER_TEXT)
                                        tvFetchStatus.text =
                                            "获取失败：" + (t.message ?: "未知错误") + "（可直接手动填写）"
                                    },
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "showing the model list failed: " + t.message)
                            }
                        }
                    }
                },
            )
            form.addView(tvFetchStatus)

            // ---- 三项的语义说明，放在这一组字段的最后：读到这里正好该问"那我到底要不要填"。
            form.addView(
                NukeTheme.hintText(
                    ctx,
                    "以上三项留空即使用主配置（设置里的那份）。只填模型 = 换模型不换服务商；" +
                        "三项都填 = 这个模板完全用自己的一套接口。",
                ),
            )

            form.addView(NukeTheme.fieldLabel(ctx, "改写提示词"))
            val etPrompt = NukeTheme.input(ctx, existing?.systemPrompt.orEmpty(), "").apply {
                minLines = 6
                maxLines = 12
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                setTextColor(NukeTheme.TEXT)
                tag = Constants.TAG_OWN_UI
            }
            form.addView(etPrompt)
            form.addView(
                NukeTheme.hintText(
                    ctx,
                    "提示词描述改写的语气。要求模型只输出可以直接发送的内容本身，不要 JSON、不要解释。",
                ),
            )

            val scroll = ScrollView(ctx).apply {
                addView(form)
                tag = Constants.TAG_OWN_UI
            }

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ ->
                    val name = etName.text.toString().trim()
                    val prompt = etPrompt.text.toString().trim()
                    // 三项在这里**原样保存**（不 ifBlank 成主配置的值）：留空是"跟随主配置"
                    // 这个独立语义，若在此处把主配置的值写进去，就等于把"跟随"变成了"固定"，
                    // 用户以后改主配置时这个模板不会跟着走，而且他根本不知道发生过这次固化。
                    val base = etBase.text.toString().trim()
                    val key = etKey.text.toString().trim()
                    val model = etModel.text.toString().trim()
                    if (name.isBlank()) {
                        Log.w(TAG, "polish template save skipped: blank name")
                        return@setPositiveButton
                    }
                    try {
                        if (existing == null) {
                            PolishTemplateStore.add(
                                PolishTemplate(
                                    name = name,
                                    systemPrompt = prompt,
                                    baseUrl = base,
                                    apiKey = key,
                                    model = model,
                                ),
                            )
                            Log.i(TAG, "polish template added: " + name)
                        } else {
                            // A rename keeps the position and the prompt.
                            if (existing.name != name) {
                                PolishTemplateStore.rename(existing.name, name)
                            }
                            // v10.3.1：这里必须走 [PolishTemplateStore.add] 的**完整重载**而不是
                            // `add(name, prompt)` —— 后者对 API 三项"没有意见"，而本表单有。
                            PolishTemplateStore.add(
                                PolishTemplate(
                                    name = name,
                                    systemPrompt = prompt,
                                    baseUrl = base,
                                    apiKey = key,
                                    model = model,
                                    // builtIn 跟随**被编辑的这条记录**，而不是按新名字去 store 里查：
                                    // 查名字会在"重命名撞上另一个自带模板"时把 builtIn 顺手带过去，
                                    // 而 builtIn=true 意味着「恢复默认」删不掉它 —— 那是不可见的损失。
                                    // 于是这里只信 existing 自己。
                                    builtIn = existing.builtIn,
                                ),
                            )
                            Log.i(
                                TAG,
                                "polish template updated: " + name +
                                    " model=" + model.ifBlank { "(主配置)" } +
                                    " baseUrl=" + base.ifBlank { "(主配置)" } +
                                    " keyPresent=" + key.isNotBlank(),
                            )
                        }
                        done()
                    } catch (t: Throwable) {
                        Log.w(TAG, "saving the polish template failed: " + t.message)
                    }
                }
                .create()
            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(
                    // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                    NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, ctx),
                )
            } catch (_: Throwable) {
                // best effort
            }
            OwnUi.register(form, scroll, dlg.window?.decorView)
        } catch (t: Throwable) {
            Log.e(TAG, "edit polish template failed", t)
        }
    }

    /** A simple picker, for callers that only need a name. */
    fun pick(ctx: Context, title: String, onPick: (PolishTemplate) -> Unit) {
        OwnUi.suppressFor(2000L)
        try {
            val names = PolishTemplateStore.all().map { it.name }.toTypedArray()
            if (names.isEmpty()) {
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage("没有模板，请先添加")
                    .setPositiveButton("知道了", null)
                    .create()
                    .showStyled()
                return
            }
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(names) { _, which ->
                    PolishTemplateStore.byName(names[which])?.let(onPick)
                }
                .setNegativeButton("取消", null)
                .create()
                .also { d ->
                    d.showStyled()
                    try {
                        d.window?.setBackgroundDrawable(
                            // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                    NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, ctx),
                        )
                    } catch (_: Throwable) {
                        // best effort
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "polish template picker failed", t)
        }
    }

}