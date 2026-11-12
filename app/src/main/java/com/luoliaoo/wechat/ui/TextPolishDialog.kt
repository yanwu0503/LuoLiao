package com.luoliaoo.wechat.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.ai.AiReply
import com.luoliaoo.wechat.ai.OpenAiClient
import com.luoliaoo.wechat.ai.PolishOption
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.PolishTemplateStore

/**
 * v4.0.0: the 文本优化 dialog, opened by LONG PRESSING the floating balloon.
 *
 * WHAT IT DOES
 *
 *   1. reads what is already typed in WeChat's chat input box,
 *   2. shows it in an EDITABLE preview area at the top (the user can fix it by hand first),
 *   3. asks the model for exactly TWO differently-styled rewrites, shown as two cards,
 *   4. tapping a card PREVIEWS it in that same top area, so what you see is what you will send,
 *   5. 确定 writes the previewed sentence into the chat input box through [InputInjector] -- the SAME
 *      path 编辑 uses, so it never sends anything by itself; 取消 closes and changes nothing.
 *
 * The 补充背景 field is context for the model only; it is never inserted into the message.
 *
 * WHY THE CHAT BOX IS READ BEFORE THE DIALOG IS SHOWN: reading goes through InputInjector's single
 * "which field is the chat box" decision, and reading first makes it impossible for our own field to
 * ever be the one that is read.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt). Chinese UI strings are literal.
 */
object TextPolishDialog {

    private const val TAG = "LuoLiao"

    /** The long-press entry point wired in FloatingPanel -- this signature is fixed. */
    fun show(ctx: Context, source: View) {
        val activity = unwrap(ctx) ?: ctx

        // Never let our own dialog be hijacked by the menu hook while it is being built.
        OwnUi.suppressFor(3000L)

        try {
            // ---- 1. read the chat input box BEFORE showing anything of our own
            val read = try {
                InputInjector.readCurrent(source)
            } catch (t: Throwable) {
                Log.w(TAG, "polish: reading the chat input box failed: " + t.message)
                null
            }
            if (read == null) {
                Log.w(TAG, "polish: no chat input box found; aborting")
                toast(activity, "落聊：没找到聊天输入框，请先打开一个聊天窗口")
                return
            }
            Log.i(TAG, "polish: opening the dialog with " + read.length + " char(s)")

            buildDialog(activity, source, read)
        } catch (t: Throwable) {
            Log.e(TAG, "polish: the dialog failed to open", t)
            toast(activity, "落聊：无法打开文本优化（" + t.javaClass.simpleName + "）")
        }
    }

    // ------------------------------------------------------------------ the dialog

    private fun buildDialog(activity: Context, source: View, initial: String) {
        fun dp(v: Int) = NukeTheme.dp(activity, v)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            tag = Constants.TAG_OWN_UI
        }

        content.addView(NukeTheme.eyebrow(activity, "落聊 · 文本优化"))
        // 液态玻璃化：标题由手写 TextView(22f/BOLD) 换成 NukeTheme.title 工厂，
        // 与 PolishTemplateDialog / SkillDialog 的标题层级一致，并自动跟随自适应字体与明暗双主题。
        content.addView(NukeTheme.title(activity, "文本优化", 22f))
        content.addView(
            NukeTheme.body(
                activity,
                "先确认下面的原文，需要补充说明就写进「补充背景」，然后让 AI 给你两个版本",
                NukeTheme.TEXT_MUTED,
                12f,
            ).apply { setPadding(0, dp(6), 0, dp(10)) },
        )

        // ---- the polish template in use, switchable without leaving this dialog
        val tvTemplate = TextView(activity).apply {
            text = templateLabel()
            setTextColor(NukeTheme.ACCENT)
            // 自适应字号：原为裸 12f，会绕过 NukeTheme.sp。
            textSize = NukeTheme.sp(activity, 12f)
        }
        // ★ v10.3.1：这一行现在还必须回答"用哪个模型、哪份接口"。
        // 模板可以自带地址/Key/模型（留空则回落主配置），而模板是按名字**持久化选中**的 ——
        // 用户改了 A 模板的模型、实际却选着 B 模板，是很容易发生且完全不可见的事。
        // 所以把生效的模型直接写在模板名下面：这一行是用户判断"我刚才那次修改生效了吗"的唯一依据。
        val tvModelLine = TextView(activity).apply {
            text = modelLabel(activity)
            setTextColor(NukeTheme.TEXT_DIM)
            textSize = NukeTheme.sp(activity, 11f)
            setPadding(0, NukeTheme.dp(activity, 2), 0, 0)
        }
        val templateText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvTemplate)
            addView(tvModelLine)
        }
        val templateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        templateRow.addView(
            templateText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        templateRow.addView(
            NukeTheme.link(activity, "切换模板") {
                PolishTemplateDialog.show(activity) {
                    // The label follows whatever the manager left selected.
                    tvTemplate.text = templateLabel()
                    // 管理器里可能刚改过这个模板的模型，所以模型行也要跟着刷新 ——
                    // 只刷新名字的话，用户改完模型回来看到的仍是旧模型名，会以为没保存成功。
                    tvModelLine.text = modelLabel(activity)
                }
            },
        )
        content.addView(templateRow)

        // ---- the EDITABLE preview: what 确定 will actually put in the chat box
        //
        // 液态玻璃化：两个输入区各自收进一张玻璃卡（sectionCard = featurePanel 渐变玻璃 + 18dp 圆角）。
        // 玻璃面板 → 玻璃卡 → 控件 的三层结构才立得住；原来两个 EditText 直接贴在大面板上，
        // 玻璃层次完全看不出来，视觉上和改造前没有区别。fieldLabel 一并移入卡内，使「标签 + 输入」
        // 成为一个视觉整体，与配置面板（ConfigDialog 的 apiCard/scopeCard）结构对齐。
        // 注意：etPreview / etBackground 的变量、类型与所有后续用法（读文本、设 error、注册 OwnUi）
        // 都保持原样，只是它们的父容器从 content 换成了玻璃卡。
        val sourceCard = NukeTheme.sectionCard(activity)
        sourceCard.addView(NukeTheme.fieldLabel(activity, "原文（可直接编辑）"))
        val etPreview = NukeTheme.input(activity, initial, "在这里输入或修改要优化的话").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setTextColor(NukeTheme.TEXT)
            // Our own field must never be mistaken for the chat input box.
            tag = Constants.TAG_OWN_UI
        }
        sourceCard.addView(etPreview)
        content.addView(sourceCard)

        // ---- 补充背景: context for the model, never part of the message
        val backgroundCard = NukeTheme.sectionCard(activity)
        backgroundCard.addView(NukeTheme.fieldLabel(activity, "补充背景（可选）"))
        val etBackground = NukeTheme.input(activity, "", "例如：她昨天生日我忘了；对方是甲方领导").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setTextColor(NukeTheme.TEXT)
            tag = Constants.TAG_OWN_UI
        }
        backgroundCard.addView(etBackground)

        // v4.0.0 FIX 2: a RED requirement on the line BELOW the grey example.
        //
        // The user asked for this explicitly. It is the single most useful extra instruction here: without
        // a length the two versions come back at whatever length the model fancies, and a chat message that
        // is three times too long is not usable. The prompt carries the rule (see TextPolishPrompts.system),
        // so anything typed in the box above ("我想要 50 个字左右") is honoured as a hard constraint.
        //
        // 这行红色提示放在玻璃卡内部，紧贴它所说明的输入框（原来它在大面板上，和输入框之间没有视觉纽带，
        // 看起来像一条孤立说明）。
        backgroundCard.addView(
            NukeTheme.hintText(
                activity,
                "可以加要求，例如：我想要 50 个字左右",
                // 原来是硬编码的微信「自己气泡」粉 0xFFFF6B81。
                // 语义最近的是 NukeTheme.DANGER_TEXT：同为高对比暖粉色系，用途也是「必须注意」的
                // 强调行；更关键的是它是 token —— 内核做明暗双主题（Configuration.uiMode）时会一并
                // 重新定义，硬编码的色值永远不会跟着变，在浅色主题下必然翻车。
                NukeTheme.DANGER_TEXT,
            ),
        )
        content.addView(backgroundCard)

        val btnRun = NukeTheme.primaryButton(activity, "开始优化") {}.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
        }
        content.addView(btnRun)

        // ---- the live status line
        // 玻璃化：加一层 FEATURE_ALT 玻璃底，作为「操作区（按钮）」与「结果区（两张方案卡）」之间的
        // 分隔带 —— 否则这条状态文字悬在两块玻璃之间，没有归属感。文字颜色仍由各处 setTextColor
        // 动态切换（成功/失败/进行中），业务逻辑未动。
        val tvStatus = TextView(activity).apply {
            text = ""
            setTextColor(NukeTheme.TEXT_MUTED)
            // 自适应字号：原为裸 12f，会绕过 NukeTheme.sp 的字体缩放。
            textSize = NukeTheme.sp(activity, 12f)
            // 圆角走统一节奏：状态行是「控件级」表面 → R_CONTROL(12)，不再手写数字字面量。
            background = NukeTheme.rounded(
                NukeTheme.FEATURE_ALT, NukeTheme.R_CONTROL, activity, NukeTheme.FEATURE_BORDER,
            )
            setPadding(dp(13), dp(11), dp(13), dp(11))
            setLineSpacing(dp(3).toFloat(), 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }

            // 状态行初始为空。带玻璃底的空 TextView 会在按钮下方留下一块「什么都没有的玻璃条」，
            // 所以按「有没有内容」自动显隐：空 → GONE，有内容 → VISIBLE。
            // 这只是这条玻璃带的显示状态，不参与任何业务判断，所以各处 `tvStatus.text = ...`
            // 的调用点一行都不用改。
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        content.addView(tvStatus)

        // ---- where the two proposals appear
        val resultBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(resultBox)

        // ---- the SEPARATE preview of the picked proposal (v4.0.0 FIX)
        //
        // The preview used to be shown by copying the chosen proposal INTO the 原文 field. That overwrote
        // the user's own draft the moment a card was tapped, so the original wording was lost -- and the
        // next 「重新优化」 optimised the model's previous answer instead of what the user wrote. The preview
        // is now its own read-only label, and picking a card never touches the 原文 field.
        val tvPreviewHead = NukeTheme.fieldLabel(activity, "优化预览（确定将写入这段）").apply {
            visibility = View.GONE
        }

        // ---- v7.0.2: a COPY button on the RIGHT of the preview header (user request)
        //
        // WHY IT IS ITS OWN ROW RATHER THAN APPENDED TO THE LABEL: a `fieldLabel` is a single TextView, so
        // putting a button "beside" it needs a horizontal container -- two views cannot share one row
        // otherwise, and setting a compound drawable would make the whole label clickable anyway.
        //
        // The header row carries the same visibility as the label it holds, so the button appears and
        // disappears WITH the preview. A copy button visible while there is nothing to copy would be a
        // button that does nothing, which is worse than no button.
        //
        // DECLARED BEFORE THE BUTTON on purpose: the click handler reads `tvPreview.text` when it FIRES,
        // so the preview view must already be in scope. Declaring the button first would not compile, and
        // capturing the text eagerly would be worse -- it would copy whatever was previewed at BUILD time
        // rather than what the user is looking at.
        val tvPreview = TextView(activity).apply {
            text = ""
            visibility = View.GONE
            setTextColor(NukeTheme.TEXT)
            // 自适应字号：原为裸 14f，绕过 NukeTheme.sp。
            textSize = NukeTheme.sp(activity, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // 玻璃化：预览区保留原来的 FEATURE_ALT 玻璃底（控件级圆角 + 细描边），
            // 它是「即将写入聊天框的最终文本」的容器，与上方可编辑的原文卡明确区分开 ——
            // 原文是可改的输入玻璃，预览是只读的结果玻璃，两者不能混。
            // 圆角：只读结果块属「控件级」→ R_CONTROL(12)，不再手写 12。
            background = NukeTheme.panel(
                NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, NukeTheme.R_CONTROL, activity,
            )
            setLineSpacing(dp(3).toFloat(), 1.25f)
        }
        val btnCopyPreview = NukeTheme.secondaryButton(activity, "复制") {
            copyPreviewToClipboard(activity, tvPreview.text.toString())
        }
        val previewHeadRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(
                tvPreviewHead,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                btnCopyPreview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        content.addView(previewHeadRow)
        content.addView(tvPreview)

        val scroll = ScrollView(activity).apply {
            addView(content)
            tag = Constants.TAG_OWN_UI
        }

        // ---- the two closing actions: 取消 changes nothing, 确定 writes the preview
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val btnCancel = NukeTheme.secondaryButton(activity, "取消") {}.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(10) }
        }
        val btnOk = NukeTheme.primaryButton(activity, "确定") {}.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actions.addView(btnCancel)
        actions.addView(btnOk)
        content.addView(actions)

        val dlg = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .create()

        // An in-flight request must not touch views after the dialog is gone.
        var alive = true
        val main = Handler(Looper.getMainLooper())

        // Tracks the rendered cards so exactly one can look selected, plus the proposal each holds.
        val cards = ArrayList<View>()
        var selectedIndex = -1
        var chosen: String = ""

        fun highlight(index: Int) {
            selectedIndex = index
            for ((i, v) in cards.withIndex()) {
                v.background = if (i == index) {
                    // The chosen proposal gets an accent border, so "what will be sent" is obvious.
                    // 圆角走统一节奏：方案卡是「卡片级」→ R_CARD(18)，不再手写 14。
                    NukeTheme.panel(NukeTheme.FEATURE, NukeTheme.ACCENT, NukeTheme.R_CARD, activity)
                } else {
                    NukeTheme.panel(
                        NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, NukeTheme.R_CARD, activity,
                    )
                }
            }
        }

        /**
         * Shows a proposal in the PREVIEW area only.
         *
         * v4.0.0 FIX: this must NEVER write into the 原文 field. It used to, which destroyed the user's own
         * draft -- the reported bug 「优化后的预览点击后不会修改原文」 came from exactly that: the 原文 box was
         * being reused as the preview display instead of having its own area.
         */
        fun preview(text: String) {
            chosen = text
            tvPreview.text = text
            // v7.0.2: the ROW carries the header AND the copy button, so toggling the row is what makes
            // both appear. Toggling only the label would leave the copy button hidden while a preview is
            // on screen. `tvPreviewHead` is hidden individually too so that the row's centring does not
            // reserve space for an invisible label.
            previewHeadRow.visibility = View.VISIBLE
            tvPreviewHead.visibility = View.VISIBLE
            tvPreview.visibility = View.VISIBLE
        }

        fun render(options: List<PolishOption>, reply: AiReply, error: String?) {
            if (!alive) return
            resultBox.removeAllViews()
            cards.clear()
            selectedIndex = -1

            if (options.isEmpty()) {
                tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                tvStatus.text = error ?: "AI 没有返回可用的优化结果，请重试"
                btnRun.isEnabled = true
                btnRun.alpha = 1f
                return
            }

            tvStatus.setTextColor(NukeTheme.OK)
            tvStatus.text = buildString {
                append("已生成 ").append(options.size).append(" 个方案，点一下即可预览")
                if (reply.hasUsage) {
                    append("  ·  ").append(reply.totalTokens).append(" tokens")
                }
            }

            for ((i, o) in options.withIndex()) {
                val card = optionCard(activity, i, o) { preview(o.text); highlight(i) }
                cards.add(card)
                resultBox.addView(card)
            }
            // The first proposal is previewed immediately, so 确定 always has something sensible.
            // NOTE: this only fills the PREVIEW area -- the 原文 field above keeps the user's own draft.
            highlight(0)
            preview(options[0].text)

            btnRun.isEnabled = true
            btnRun.alpha = 1f
            btnRun.text = "重新优化"
        }

        fun run() {
            // v4.0.0 FIX: the 原文 field is the ONE source of truth for what gets optimised. It is never
            // overwritten by picking a card anymore, so reading it directly is both correct and what the
            // user sees -- 重新优化 always works on their own wording, round after round.
            val source = etPreview.text.toString().trim()
            if (source.isEmpty()) {
                etPreview.error = "请先输入要优化的话"
                return
            }
            val background = etBackground.text.toString().trim()

            // ★ v10.3.1：模板负责**语气**，也负责**用哪个模型/哪份接口**。
            //
            // 【为什么走 PolishTemplateStore.resolve 而不是在这里各读各的】
            // 「用什么模型」这个问题必须只有一个答案来源，否则就会出现"界面显示一个模型、
            // 请求实际发去另一个"的分裂（本项目为屏蔽词踩过一次同源的坑：两条路径读两张表）。
            // resolve() 是唯一的漏斗：它把"模板优先、留空回落主配置"的规则收敛在一处，
            // 界面上的 modelLabel() 与这里**调的是同一个函数**，所以显示与行为不可能不一致。
            //
            // 【resolve 也在 try 里】它要读配置与模板文件；任何一次磁盘读取失败都不该让
            // 「开始优化」按钮直接崩掉宿主的对话框。
            val resolved = try {
                PolishTemplateStore.resolve(currentConfig(activity))
            } catch (t: Throwable) {
                Log.w(TAG, "polish: resolving the template settings failed: " + t.message)
                null
            }
            val templatePrompt = resolved?.systemPrompt.orEmpty()
            val templateName = resolved?.templateName.orEmpty()

            // ⚠️ 没有 resolved 就没有模板，也就没有改写提示词。此时**不能**退化成"空提示词也要试一次"：
            // 那会把原文原样发给模型、拿回一段没有任何语气约束的文本，用户以为功能坏了却看不出原因。
            if (resolved == null) {
                tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                tvStatus.text = "读取模板失败，请点「切换模板」重新选一个"
                return
            }

            // The request's own settings: the template's three fields when filled, otherwise the
            // shared config's. `currentConfig` already resolves through the cross-process mirror, so a
            // clone or a restarted host still sees the values the user typed.
            val cfg = try {
                currentConfig(activity).copy(
                    baseUrl = resolved.baseUrl,
                    apiKey = resolved.apiKey,
                    model = resolved.model,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "polish: building the request config failed: " + t.message)
                Config(baseUrl = resolved.baseUrl, apiKey = resolved.apiKey, model = resolved.model)
            }

            if (cfg.apiKey.isBlank()) {
                // 提示要指出**该去哪里填**。模板自带 Key 之后，"配置里填 Key"不再是唯一答案，
                // 而用户很可能正是在模板里清空了 Key（= 想回落主配置）却忘了主配置也没填。
                tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                tvStatus.text = "还没有配置 API Key：请在「切换模板 → 编辑」里填，" +
                    "或去「配置」里的主配置填一个"
                return
            }

            Log.i(
                TAG,
                "polish: requesting, template=" + templateName + " model=" + cfg.model +
                    " baseUrl=" + cfg.baseUrl +
                    " keyLen=" + cfg.apiKey.length +
                    " draft=" + source.length + " background=" + background.length,
            )
            btnRun.isEnabled = false
            btnRun.alpha = 0.6f
            tvStatus.setTextColor(NukeTheme.TEXT_MUTED)
            tvStatus.text = "正在优化…"
            resultBox.removeAllViews()
            cards.clear()
            // The previous round's preview no longer applies to this request.
            chosen = ""
            tvPreview.text = ""
            tvPreview.visibility = View.GONE
            // v7.0.2: hide the WHOLE row, so the copy button goes with the preview it copies from. If only
            // the label were hidden, the button would remain on screen with nothing behind it.
            previewHeadRow.visibility = View.GONE
            tvPreviewHead.visibility = View.GONE

            // The request blocks, so it must never run on the UI thread -- the chat screen would freeze.
            Thread {
                var options: List<PolishOption> = emptyList()
                var reply = AiReply(null)
                var error: String? = null
                try {
                    val r = OpenAiClient(cfg).polish(source, background, templatePrompt)
                    options = r.first
                    reply = r.second
                    if (options.isEmpty() && reply.text.isNullOrBlank()) {
                        error = "请求失败，请检查网络或 API 配置"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "polish: the request failed", t)
                    error = "请求异常：" + (t.message ?: t.javaClass.simpleName)
                }
                main.post { render(options, reply, error) }
            }.apply { isDaemon = true }.start()
        }

        btnRun.setOnClickListener { run() }
        btnCancel.setOnClickListener {
            Log.i(TAG, "polish: cancelled")
            try {
                dlg.dismiss()
            } catch (_: Throwable) {
                // already gone
            }
        }
        btnOk.setOnClickListener {
            // v4.0.0: 确定 writes the PICKED PROPOSAL (the preview), never the 原文 field -- the 原文 is the
            // user's own draft and must survive intact if they close the dialog without choosing.
            val text = chosen.trim()
            if (text.isEmpty()) {
                tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
                tvStatus.text = "请先点一个方案预览，再点确定"
                return@setOnClickListener
            }
            Log.i(TAG, "polish: writing " + text.length + " char(s) back to the chat input box")
            val ok = try {
                // The SAME write path 编辑 uses: fill the chat box, never send.
                InputInjector.fill(source, text)
            } catch (t: Throwable) {
                Log.e(TAG, "polish: writing to the input box failed", t)
                false
            }
            if (ok) Log.i(TAG, "polish: wrote to the chat input box") else {
                Log.w(TAG, "polish: falling back (clipboard) -- see the toast from InputInjector")
            }
            try {
                dlg.dismiss()
            } catch (_: Throwable) {
                // already gone
            }
        }

        dlg.setOnDismissListener { alive = false }
        dlg.setCanceledOnTouchOutside(true)
        dlg.showStyled()
        try {
            dlg.window?.setBackgroundDrawable(
                // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                NukeTheme.panel(NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, activity),
            )
        } catch (_: Throwable) {
            // best effort
        }
        OwnUi.register(content, scroll, btnRun, btnOk, btnCancel, dlg.window?.decorView)
        Log.i(TAG, "polish: dialog shown")
    }

    /**
     * One proposal card.
     *
     * The whole card is the target, because "点一下就能预览" is the entire interaction; the text is not
     * separately selectable so a tap can never be swallowed by text selection.
     */
    private fun optionCard(
        ctx: Context,
        index: Int,
        o: PolishOption,
        onPick: () -> Unit,
    ): LinearLayout {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 圆角：方案卡是「卡片级」→ R_CARD(18)。
            background = NukeTheme.panel(
                NukeTheme.FEATURE, NukeTheme.FEATURE_BORDER, NukeTheme.R_CARD, ctx,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // 无障碍：整张卡是点击目标（「点一下即可预览」是它的全部交互），
            // 卡片本身的内边距已让它远高于 48dp；这里显式声明最小高度，
            // 是为了让「模型只回了一行短文案」时也仍然好点 —— 不改变任何视觉尺寸，
            // 只是在内容不足 48dp 时把可点区域补到 48dp。
            minimumHeight = dp(48)
            isClickable = true
            setOnClickListener { onPick() }
            tag = Constants.TAG_OWN_UI
        }
        val margin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
        card.layoutParams = margin

        // Header: 方案一 / 方案二, plus the model's own style name when it gave one.
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ordinal = if (index == 0) "方案一" else "方案二"
        head.addView(NukeTheme.badge(ctx, ordinal))
        val style = o.title.trim()
        if (style.isNotEmpty() && style != ordinal) {
            head.addView(
                TextView(ctx).apply {
                    text = style
                    setTextColor(NukeTheme.TEXT)
                    // 自适应字号：原为裸 13f，绕过 NukeTheme.sp。
                    textSize = NukeTheme.sp(ctx, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        card.addView(head)

        card.addView(
            TextView(ctx).apply {
                text = o.text
                setTextColor(NukeTheme.TEXT)
                // 自适应字号：这段是「将要发出去的话」的正文，原来写死 15f，
                // 在宿主「超大字体」下会溢出玻璃卡，改用 sp 版本。
                textSize = NukeTheme.sp(ctx, 15f)
                setLineSpacing(dp(4).toFloat(), 1.4f)
                setPadding(0, dp(8), 0, 0)
            },
        )

        val reason = o.reason.trim()
        if (reason.isNotEmpty()) {
            card.addView(
                NukeTheme.hintText(ctx, reason).apply { setPadding(0, dp(6), 0, 0) },
            )
        }
        return card
    }

    // ------------------------------------------------------------------ helpers

    /** "当前模板：恋爱", plus a nudge when there is none. */
    private fun templateLabel(): String = try {
        val n = PolishTemplateStore.selectedName()
        if (n.isBlank()) "当前模板：无（点「切换模板」新建一个）" else "当前模板：$n"
    } catch (_: Throwable) {
        "当前模板：读取失败"
    }

    /**
     * ★ v10.3.1：这一行说明**这次优化实际会用哪个模型、哪份接口**。
     *
     * 【为什么要显示而不只是让它在后台生效】用户改了模板的模型之后，唯一能确认"改对了"的方式
     * 就是看见它。而这里最容易出错的地方恰恰是**模板的选中状态**：模板是按名字持久化选择的，
     * 用户完全可能编辑了「恋爱」却选着「工作」。没有这一行，他只会看到"模型没变"，
     * 然后去反复检查自己刚填的那一栏 —— 而他填的那一栏其实一个字都没错。
     *
     * 【为什么明确区分「模板」与「主配置」】这是两种不同的来源，且只有前者能解释
     * "为什么我改主配置的模型没用"。所以措辞必须点名来源，而不是只报模型名。
     */
    private fun modelLabel(ctx: Context): String = try {
        val t = PolishTemplateStore.active()
        val r = PolishTemplateStore.resolve(
            try {
                ConfigRepo.loadLocal(ctx)
            } catch (_: Throwable) {
                Config()
            },
        )
        // 逐项标注来源，因为三项是**各自独立**回落的：完全可能出现
        // 「模型来自模板、地址与 Key 来自主配置」这一档，笼统写一句"来自模板"就是错的。
        buildString {
            append("模型：").append(r.model)
            append(if (t?.model?.isNotBlank() == true) "（模板）" else "（主配置）")
            val ownBase = t?.baseUrl?.isNotBlank() == true
            val ownKey = t?.apiKey?.isNotBlank() == true
            if (ownBase || ownKey) {
                append("  ·  接口：")
                append(if (ownKey) "模板 Key" else "主配置 Key")
                if (ownBase) append(" + 模板地址") else append(" + 主配置地址")
            }
        }
    } catch (_: Throwable) {
        "模型：读取失败"
    }

    /**
     * The API settings a polish request should use.
     *
     * [com.luoliaoo.wechat.hook.AnalysisController.currentConfig] is preferred because it resolves the
     * endpoint, key and model through the analysis template AND falls back to the cross-process mirror;
     * a clone or a restarted WeChat would otherwise see an empty config. The polish PROMPT is deliberately
     * NOT taken from here -- it comes from PolishTemplateStore.
     *
     * ★ v10.3.1：**这里的地址/Key/模型只是"主配置那一份"**，不再是最终答案。
     * 最终答案是 [PolishTemplateStore.resolve]（模板优先、留空回落这一份），
     * 它由 `run()` 在发出请求前合成。所以本函数现在扮演的角色是
     * 「resolve 的兜底输入」，而不是"请求设置"本身 —— 名字保留是因为它的**来源**没变。
     *
     * ⚠️ 不要改回"直接用本函数的结果发请求"：那会让模板自带的模型**静默失效**，
     * 而界面上却依然显示着模板的模型（因为 modelLabel 用的是 resolve），
     * 于是"显示与行为不一致"这种最难查的 bug 会立刻回来。
     */
    private fun currentConfig(ctx: Context): Config = try {
        com.luoliaoo.wechat.hook.AnalysisController.instance?.currentConfig()
            ?: ConfigRepo.loadLocal(ctx)
    } catch (t: Throwable) {
        Log.w(TAG, "polish: config resolution failed, using the local config: " + t.message)
        try {
            ConfigRepo.loadLocal(ctx)
        } catch (_: Throwable) {
            Config()
        }
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // a missing toast must not fail the feature
        }
    }

    /**
     * v7.0.2: copies the previewed sentence to the system clipboard.
     *
     * REPORTS BOTH OUTCOMES, because a copy button has no other feedback: the text goes somewhere the user
     * cannot see, so with no toast the button is indistinguishable from a dead one. The two messages also
     * differ, so a screenshot alone says whether it worked.
     *
     * REFUSES TO COPY EMPTY TEXT rather than putting an empty clip on the clipboard: that would silently
     * DESTROY whatever the user had copied before, which is a real loss for a button they pressed by
     * accident. Saying "there is nothing to copy" is the honest outcome.
     *
     * `getSystemService` is annotated `@NonNull` in the platform but can be null on a broken context, and
     * a module must never crash the host's dialog: the cast is therefore `as?` and the failure path reports
     * rather than throws.
     */
    private fun copyPreviewToClipboard(ctx: Context, text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "polish: the copy button was tapped but the preview is empty; not touching the clipboard")
            toast(ctx, "没有可复制的内容")
            return
        }
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm == null) {
                Log.w(TAG, "polish: the clipboard service is unavailable; nothing was copied")
                toast(ctx, "复制失败：剪贴板不可用")
                return
            }
            cm.setPrimaryClip(ClipData.newPlainText("落聊", text))
            Log.i(TAG, "polish: copied the preview to the clipboard (" + text.length + " chars)")
            toast(ctx, "已复制到剪贴板")
        } catch (t: Throwable) {
            // Some Android versions restrict clipboard writes to the foreground app; report it rather
            // than letting the exception escape into the host's dialog.
            Log.w(TAG, "polish: the clipboard write failed: " + t.message)
            toast(ctx, "复制失败：" + t.javaClass.simpleName)
        }
    }

    private fun unwrap(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
