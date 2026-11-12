package com.luoliaoo.wechat.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Config
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.Template
import com.luoliaoo.wechat.data.TemplateStore

/**
 * Template management (item 3).
 *
 * A template bundles base URL, API key, model and system prompt under a name, so switching between
 * "恋爱" and "工作" changes the whole analysis configuration in one tap.
 *
 * Anything may be added, renamed or deleted. The two shipped templates can be deleted too, and
 * 恢复默认 brings them back.
 */
object TemplateDialog {

    private const val TAG = "LuoLiao"

    // ---------------------------------------------------------------- 液态玻璃尺度（冻结值）
    //
    // 圆角节奏取自 LIQUID-GLASS-CONTRACT，全站只有这 5 档，不出现第 6 档：
    //   弹窗主容器 24 / 卡片 18 / 控件 12 / chip 10 / 徽标 6
    // 用具名常量而不是散落的数字字面量，是为了让「圆角失控」在编译期就看得见。

    private const val R_DIALOG = 24
    private const val R_CARD = 18
    private const val R_CONTROL = 12
    private const val R_CHIP = 10
    private const val R_BADGE = 6

    /**
     * 无障碍：最小触控高度 48dp。
     *
     * NukeTheme 的公共控件已由主题内核统一抬高到 48dp，但本文件自绘的按钮不经过那些工厂方法，
     * 会漏掉这条保障。凡是本文件自己 new 出来的可点 View，都显式套用这个下限。
     * 只抬高「可点区域」，不改字号、不改配色、不改视觉尺寸。
     */
    private const val MIN_TOUCH_DP = 48

    /**
     * 液态玻璃面板 —— 本文件私有实现，**不写入 NukeTheme.kt**（主题内核正在同步改造那个文件）。
     *
     * 一张 GradientDrawable 同时表达玻璃的三层结构：
     *   ① 受光面：左上→右下的渐变，起点是描边色的极淡版本，向右下过渡到主体填充；
     *   ② 主体：令牌填充 + 显式 alpha，半透明才有「通透」的玻璃感，传不透明色就退化成色块；
     *   ③ 厚度：1dp 描边就是玻璃的边缘光，这是「看起来是一块玻璃」而不是「一块半透明色」的关键。
     *
     * 圆角遵守全站冻结的节奏：弹窗 24dp / 卡片 18dp / 控件 12dp / chip 10dp。
     * 颜色全部取自 NukeTheme 现有 token —— 对话框可能跑在微信/抖音/QQ/Soul/快手宿主进程里，
     * 宿主主题各不相同，只有完全自绘 + 只用我们自己的令牌，观感才是稳定可预期的。
     */
    private fun glassPanel(
        ctx: Context,
        radiusDp: Int = R_DIALOG,
        fill: Int = NukeTheme.CARD,
        border: Int = NukeTheme.CARD_BORDER,
        fillAlpha: Int = 0xEE,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(glassVeil(border, 0x99), glassVeil(fill, fillAlpha), glassVeil(fill, fillAlpha)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
        setStroke(NukeTheme.dp(ctx, 1), border)
    }

    /**
     * 只给现有令牌换一层透明度，用来派生玻璃高光 / 淡色底。
     *
     * 不硬编码任何颜色值：rgb 三位取自传入的 NukeTheme token，只有 alpha 是本地的，
     * 因此明暗主题切换或主题内核调整令牌时，这些派生色会自动跟着变。
     */
    private fun glassVeil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * 标题区与内容区之间的柔和分隔 —— **不新增任何视图**。
     *
     * 给已有的标题 TextView 铺一层竖向渐变（全透明 → 极淡主题色），在标题下方形成一条「光带」。
     * 相比额外插入一条横线，它不改变视图层级，也更贴合玻璃语言。
     */
    private fun titleRule(ctx: Context, radiusDp: Int = R_CONTROL): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(android.graphics.Color.TRANSPARENT, glassVeil(NukeTheme.ACCENT, 0x1F)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
    }

    /**
     * 主操作按钮 —— 实心强调色玻璃块（控件级 12dp 圆角）。
     *
     * 按钮行需要主次分明，而 NukeTheme 现有的 warnButton 是固定琥珀黄实心块：放在已玻璃化的
     * 深色面板上像一块贴纸，也不属于本次冻结的玻璃语言。因此主操作改用 ACCENT 实心 +
     * 近黑文字（TEXT_ON_ACCENT），次操作继续沿用 NukeTheme 的描边按钮。
     * **只改样式，onClick 回调原样透传，layoutParams 与 NukeTheme 的按钮完全一致。**
     */
    private fun glassActionButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(NukeTheme.TEXT_ON_ACCENT)
            textSize = NukeTheme.sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(NukeTheme.ACCENT, NukeTheme.ACCENT_STRONG),
            ).apply {
                cornerRadius = NukeTheme.dp(ctx, R_CONTROL).toFloat()
                setStroke(NukeTheme.dp(ctx, 1), glassVeil(NukeTheme.TEXT, 0x55))
            }
            setPadding(
                NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
                NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
            )
            // 无障碍：本按钮自绘，不走 NukeTheme 的工厂方法，因此自己保证 48dp 触控高度，
            // 否则会比同屏那些已被内核抬高的按钮更难按。
            // 【取舍说明】本按钮有背景（实心强调色玻璃块），最小高度会把可见按钮一起抬到 48dp ——
            // 这是团队裁决的选项 (a)，不是疏漏。否决 TouchDelegate 隐形热区的理由：它需要挂在比
            // 子 View 更大的父容器上（等宽权重布局下边界不可靠），且依赖宿主 measure 时序，
            // 而模块要跨 5 个宿主运行。可见目标本身达到 48dp 是更好的无障碍目标。
            minimumHeight = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            minimumWidth = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = NukeTheme.dp(ctx, 8) }
        }

    /**
     * Opens the manager -- the module's configuration entry point (item 3).
     *
     * The template list is capped at about three rows; everything below it (the window controls and
     * 清空所有记录) is OUTSIDE that cap, so it stays reachable no matter how many templates exist.
     */
    fun show(ctx: Context, onPicked: ((Template) -> Unit)? = null) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        OwnUi.suppressFor(3000L)

        try {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(8))
                tag = Constants.TAG_OWN_UI
            }

            content.addView(NukeTheme.eyebrow(ctx, "\u843d\u804a \u00b7 \u914d\u7f6e"))
            content.addView(
                TextView(ctx).apply {
                    text = "\u6a21\u677f\u7ba1\u7406"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 22f)
                    typeface = Typeface.DEFAULT_BOLD
                    // 玻璃化：标题区与内容区之间的明度分隔 —— 给标题铺一层渐变光带。
                    background = titleRule(ctx)
                },
            )
            content.addView(
                NukeTheme.body(
                    ctx,
                    "\u6bcf\u4e2a\u6a21\u677f\u90fd\u6709\u81ea\u5df1\u7684\u5730\u5740\u3001Key" +
                        "\u3001\u6a21\u578b\u3001\u63d0\u793a\u8bcd\u548c\u9ed8\u8ba4\u8303\u56f4",
                    NukeTheme.TEXT_MUTED,
                    12f,
                ).apply { setPadding(0, dp(6), 0, dp(12)) },
            )

            // ---- the template list, capped at ~3 rows
            val listBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            fun refresh() {
                listBox.removeAllViews()
                val all = TemplateStore.all()
                if (all.isEmpty()) {
                    listBox.addView(
                        NukeTheme.body(ctx, "\u6ca1\u6709\u6a21\u677f\uff0c\u53ef\u70b9\u300c\u6dfb\u52a0\u300d", NukeTheme.TEXT_MUTED, 13f),
                    )
                }
                for (t in all) {
                    val card = NukeTheme.sectionCard(ctx)
                    card.addView(
                        NukeTheme.category(
                            ctx,
                            t.name + if (t.builtIn) "  \u00b7 \u81ea\u5e26" else "",
                        ),
                    )
                    val detail = buildString {
                        if (t.model.isNotBlank()) append("\u6a21\u578b ").append(t.model)
                        if (t.baseUrl.isNotBlank()) {
                            if (isNotEmpty()) append("  ")
                            append(t.baseUrl)
                        }
                    }
                    if (detail.isNotBlank()) {
                        card.addView(
                            TextView(ctx).apply {
                                text = detail
                                setTextColor(NukeTheme.TEXT_DIM)
                                textSize = NukeTheme.sp(ctx, 11f)
                                setPadding(0, dp(6), 0, 0)
                            },
                        )
                    }

                    val buttons = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(10), 0, 0)
                    }
                    // 编辑 opens THIS template's configuration panel.
                    // 玻璃化：一行的三个操作里「编辑」是主操作，改用强调色玻璃实心块，
                    // 与「重命名」（描边）/「删除」（危险色）拉开主次。回调与布局参数都不变。
                    buttons.addView(
                        glassActionButton(ctx, "\u7f16\u8f91") {
                            Log.i(TAG, "editing template " + t.name)
                            ConfigDialog.showForTemplate(ctx, t.name) {
                                // Apply the edited template to the live config, then redraw the list.
                                TemplateStore.byName(t.name)?.let { updated ->
                                    TemplateDialog.applyToConfig(ctx, updated)
                                }
                                refresh()
                            }
                        },
                    )
                    buttons.addView(
                        NukeTheme.warnOutlineButton(ctx, "\u91cd\u547d\u540d") {
                            promptRename(ctx, t) { refresh() }
                        },
                    )
                    buttons.addView(
                        NukeTheme.dangerButton(ctx, "\u5220\u9664") {
                            // v4.1.0: the template's Skills are dropped with it. Leaving the directory
                            // behind would orphan them, and a later template of the same name would
                            // silently inherit skills the user thought were deleted.
                            try {
                                com.luoliaoo.wechat.data.SkillStore.removeTemplate(t.name)
                            } catch (t2: Throwable) {
                                Log.w(TAG, "removing the template's skills failed: " + t2.message)
                            }
                            TemplateStore.remove(t.name)
                            Log.i(TAG, "template deleted: " + t.name)
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

            // item 5: NO row cap -- every template shows at once, and the whole panel scrolls if needed.
            content.addView(listBox)

            // item 8: show WHERE the JSON files live, so they can be found.
            content.addView(
                TextView(ctx).apply {
                    text = "存储位置：" + try {
                        com.luoliaoo.wechat.hook.AnalysisController.instance?.storageLocation()
                            ?: com.luoliaoo.wechat.data.TemplateStore.dirPath()
                    } catch (_: Throwable) {
                        com.luoliaoo.wechat.data.TemplateStore.dirPath()
                    }
                    setTextColor(NukeTheme.TEXT_DIM)
                    textSize = NukeTheme.sp(ctx, 10f)
                    setPadding(0, dp(6), 0, 0)
                    setTextIsSelectable(true)
                },
            )

            // ---- BELOW the cap: always reachable
            val actions = LinearLayout(ctx).apply {
                /* v10.2.0：由 HORIZONTAL 改为 VERTICAL。
                 *
                 * 【为什么必须改】用户第 2 项要求这一区有**四个**按钮（添加/导入/恢复默认/导出），
                 * 且顺序是「添加下面导入、恢复默认下面导出」—— 也就是两列各两个。
                 * 原来只有「添加 | 恢复默认」两个，横向排一行刚好；
                 * 若仍用 HORIZONTAL 放四个，每个按钮分到的宽度直接砍半：
                 * 「恢复默认」4 个字在半个宽度里必然被 ellipsize 成「恢复默…」。
                 * 改成 VERTICAL 后每个按钮占满整行宽度，四个文案一个都不会被截断。
                 *
                 * 子按钮自身的 layoutParams 是 weight=1f（横向时均分宽度用）；
                 * 在 VERTICAL 容器里 weight 作用于**高度**均分，会让按钮高矮不一，
                 * 所以下面每个按钮都显式把 weight 归零、宽度设为 MATCH_PARENT —— 见 addRow()。*/
                orientation = LinearLayout.VERTICAL
                // 玻璃化：底部操作行独立成一条玻璃操作条（控件级 12dp 圆角 + 边缘光描边），
                // 与上方列表之间有明度差，避免整块面板糊成一片。
                background = glassPanel(
                    ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC,
                )
                setPadding(
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                )
            }
            /* 往竖排操作条里加一个占满整行的按钮。
             *
             * 【为什么必须重设 layoutParams】NukeTheme 的按钮工厂（secondaryButton /
             * warnOutlineButton / glassActionButton）内部都把 layoutParams 设成
             * `weight=1f` + 宽度 0 —— 那是为**横向**操作行写的（横向时 weight 均分宽度）。
             * 直接放进 VERTICAL 容器，weight 会变成均分**高度**，四个按钮高矮不同；
             * 宽度 0 更会让整个按钮塌成一条竖线。所以这里统一覆写为 MATCH_PARENT + weight 0。
             * 不这么做的话，症状是"按钮突然全都很窄/很高"，而根因在工厂而不是在这里。 */
            val addRow: (android.widget.TextView) -> Unit = { b ->
                b.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                actions.addView(b)
            }
            // 玻璃化：添加是主操作 → 强调色实心玻璃；恢复默认为次操作 → 保持描边。
            //
            // v10.2.0 用户第 2 项：「模板管理处 添加下面加一个导入 恢复默认下面加一个导出」。
            //
            // 【为什么是"下面"而不是塞进同一行】用户的措辞是按**视觉顺序**说的：
            // 「添加」的下一个位置放导入、「恢复默认」的下一个位置放导出。
            // 这一行现在是「添加 | 恢复默认」，如果硬塞成一行四个，
            // 每个按钮分到的宽度会砍半 —— 而这一行已经用了 weight=1f 均分，
            // 「恢复默认」4 个字在更窄的按钮里必然被 ellipsize 成「恢复默…」。
            // 所以按用户的字面顺序**另起两行**，一行一个全宽按钮，文案一个都不会被截断。
            addRow(
                glassActionButton(ctx, "\u6dfb\u52a0") {
                    editTemplate(ctx, null) { refresh() }
                },
            )
            // 「添加」下面 = 导入（从别人的 zip 读模板）。
            addRow(
                NukeTheme.secondaryButton(ctx, "\u5bfc\u5165") {
                    Log.i(TAG, "templates: opening the import picker")
                    com.luoliaoo.wechat.ui.TemplatePortDialog.showImport(ctx) { refresh() }
                },
            )
            addRow(
                NukeTheme.warnOutlineButton(ctx, "\u6062\u590d\u9ed8\u8ba4") {
                    TemplateStore.restoreDefaults()
                    Log.i(TAG, "template defaults restored")
                    refresh()
                },
            )
            // 「恢复默认」下面 = 导出（打包成 zip 分享给别人）。
            addRow(
                NukeTheme.secondaryButton(ctx, "\u5bfc\u51fa") {
                    Log.i(TAG, "templates: opening the export sheet")
                    com.luoliaoo.wechat.ui.TemplatePortDialog.showExport(ctx) { refresh() }
                },
            )
            content.addView(actions)

            // ---- item 3: the floating-window controls live here now
            val cfg = try {
                ConfigRepo.loadLocal(ctx)
            } catch (_: Throwable) {
                Config()
            }
            val clearCard = NukeTheme.sectionCard(ctx)
            clearCard.addView(NukeTheme.category(ctx, "\u7ef4\u62a4"))
            val tvMsg = TextView(ctx).apply {
                text = ""
                setTextColor(NukeTheme.TEXT_MUTED)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, dp(6), 0, 0)
            }
            clearCard.addView(
                NukeTheme.dangerButton(ctx, "\u6e05\u7a7a\u6240\u6709\u8bb0\u5f55") {
                    AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("\u6e05\u7a7a\u6240\u6709\u8bb0\u5f55")
                        .setMessage("\u5c06\u5220\u9664\u6240\u6709\u5df2\u5206\u6790\u5361\u7247\u4e0e\u7f13\u5b58")
                        .setNegativeButton("\u53d6\u6d88", null)
                        .setPositiveButton("\u6e05\u7a7a") { _, _ ->
                            try {
                                com.luoliaoo.wechat.hook.AnalysisController.instance?.clearAllCards()
                                tvMsg.setTextColor(NukeTheme.OK)
                                tvMsg.text = "\u5df2\u6e05\u7a7a"
                            } catch (t: Throwable) {
                                tvMsg.setTextColor(NukeTheme.DANGER_TEXT)
                                tvMsg.text = "\u6e05\u7a7a\u5931\u8d25\uff1a" + t.message
                            }
                        }
                        .create()
                        .showStyled()
                },
            )
            clearCard.addView(tvMsg)

            /* ★ v10.3.0：追问流式输出速度滑杆，放在「清空所有记录」**下面**（用户指定位置）。
               注意：全工程有**两个**界面都有「清空所有记录」按钮 —— 模板管理（这里）和模块设置页。
               用户指的是**模板管理**这一个，第一版却把滑杆加到了设置页，所以用户在实际使用的地方
               根本找不到它。这也正是把实现抽到 [StreamSpeedControl] 的原因：
               两处共用一份，将来再改也不会只改一处。
               间距给 20dp：让滑杆与上面那个破坏性按钮明显分开，避免被当成清理操作的一部分。 */
            StreamSpeedControl.attach(clearCard, topPad = 20)

            content.addView(clearCard)
            val winCard = NukeTheme.sectionCard(ctx)
            winCard.addView(NukeTheme.category(ctx, "\u60ac\u6d6e\u7a97\u63a7\u5236"))
            winCard.addView(
                NukeTheme.body(
                    ctx,
                    // 5.0.0: the restart applies to whichever host is running.
                    com.luoliaoo.wechat.core.HostApp.say(
                        "\u4fee\u6539\u540e\u7acb\u5373\u4fdd\u5b58\u5e76\u751f\u6548\uff0c\u65e0\u9700\u91cd\u542f\u5fae\u4fe1",
                    ),
                    NukeTheme.TEXT_DIM,
                    11f,
                ).apply { setPadding(0, dp(4), 0, dp(8)) },
            )

            // item 2: a compact two-row form. The previous version stacked a label, a full-width input
            // and a button with generous padding on each, which left a large empty band under the
            // max-height field.
            val etWidth = NukeTheme.input(ctx, cfg.cardWidth.toString(), "25")
            winCard.addView(compactRow(ctx, "\u5bbd\u5ea6\uff08\u5b57\u6570\uff09", etWidth))
            val etHeight = NukeTheme.input(ctx, cfg.panelMaxHeightDp.toString(), "420")
            winCard.addView(compactRow(ctx, "\u6700\u5927\u9ad8\u5ea6\uff08dp\uff09", etHeight))

            // ---------------------------------------------------------------- t4: live status line
            val tvWinMsg = TextView(ctx).apply {
                text = ""
                setTextColor(NukeTheme.OK)
                textSize = NukeTheme.sp(ctx, 11f)
                setPadding(0, dp(8), 0, 0)
            }

            /**
             * t4: writes 宽度 / 最大高度 and makes them take effect AT ONCE.
             *
             * The panel used to read these values only when it was (re)built, so editing them here looked
             * like a no-op until the panel was reopened -- reported as "改完不生效，要重启微信".
             */
            fun commitGeometry(announce: Boolean) {
                try {
                    val w = (etWidth.text.toString().toIntOrNull() ?: 25).coerceIn(1, 400)
                    val h = (etHeight.text.toString().toIntOrNull() ?: 420).coerceIn(140, 2000)
                    val c2 = ConfigRepo.loadLocal(ctx).copy(
                        panelWidthChars = w,
                        // The panel width is authoritative; the card width follows it. Keep both in
                        // step so the card is not silently narrower than the panel it sits in.
                        cardWidthChars = w,
                        panelMaxHeightDp = h,
                    )
                    // saveLocalAndMirror: saved locally AND scheduled into the cross-process mirror, so
                    // the WeChat process and the module app agree without either being restarted.
                    ConfigRepo.saveLocalAndMirror(ctx, c2)
                    // Take effect NOW on the panel that is already on screen -- no reopen, no restart.
                    com.luoliaoo.wechat.ui.FloatingPanel.onConfigGeometryChanged(ctx)
                    if (announce) {
                        tvWinMsg.setTextColor(NukeTheme.OK)
                        tvWinMsg.text = "\u5df2\u4fdd\u5b58\u5e76\u7acb\u5373\u751f\u6548\uff1a\u5bbd " +
                            w + " \u5b57 \u00b7 \u9ad8 " + h + "dp"
                    }
                    Log.i(TAG, "floating window settings applied live: w=" + w + " h=" + h)
                } catch (t: Throwable) {
                    tvWinMsg.setTextColor(NukeTheme.DANGER_TEXT)
                    tvWinMsg.text = "\u4fdd\u5b58\u5931\u8d25\uff1a" + t.message
                    Log.w(TAG, "saving the floating window settings failed: " + t.message)
                }
            }

            // t4: TEXT WATCHER with a 300 ms DEBOUNCE.
            //
            // Writing on every keystroke would put a prefs write (plus a mirror write) on the UI thread
            // per character -- the stutter being chased separately. 300 ms after the user stops typing the
            // value is committed once; the button and the focus loss below force it out immediately.
            val debounce = android.os.Handler(android.os.Looper.getMainLooper())
            val pending = Runnable { commitGeometry(announce = true) }

            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    debounce.removeCallbacks(pending)
                    debounce.postDelayed(pending, 300L)
                }
            }
            etWidth.addTextChangedListener(watcher)
            etHeight.addTextChangedListener(watcher)

            // Committing on focus loss too, so a value is never left only in the debounce queue when the
            // user taps straight onto another control (or closes the dialog).
            etWidth.setOnFocusChangeListener { _, has -> if (!has) commitGeometry(announce = false) }
            etHeight.setOnFocusChangeListener { _, has -> if (!has) commitGeometry(announce = false) }

            // item 3: the adaptive-font switch.
            val adaptiveRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            adaptiveRow.addView(
                TextView(ctx).apply {
                    text = "自适应字体"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 13f)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val swAdaptive = android.widget.Switch(ctx).apply {
                isChecked = cfg.adaptiveFont
                tag = Constants.TAG_OWN_UI
                setOnCheckedChangeListener { _, checked ->
                    try {
                        val c3 = com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx)
                            .copy(adaptiveFont = checked)
                        // t4: mirror too, so the other process sees the switch without a restart.
                        com.luoliaoo.wechat.data.ConfigRepo.saveLocalAndMirror(ctx, c3)
                        // The font decision is cached; drop it so the change shows immediately.
                        com.luoliaoo.wechat.ui.NukeTheme.invalidateFontCache()
                        // t4: re-render the live panel so the new text metric is actually used now.
                        com.luoliaoo.wechat.ui.FloatingPanel.refresh()
                        Log.i(TAG, "adaptive font = " + checked)
                    } catch (t: Throwable) {
                        Log.w(TAG, "saving the adaptive-font setting failed: " + t.message)
                    }
                }
            }
            adaptiveRow.addView(swAdaptive)
            winCard.addView(adaptiveRow)
            winCard.addView(
                NukeTheme.body(
                    ctx,
                    "开启后文字大小自动适应手机字体设置，避免字体过大导致排版错乱",
                    NukeTheme.TEXT_DIM,
                    11f,
                ).apply { setPadding(0, dp(4), 0, dp(6)) },
            )

            /* ★ v10.3.0：识图模型配置（用户要求：删掉原来的图片开关，改成配识图模型 + 视觉等级）。
               ─────────────────────────────────────────────────────────────────────
               【为什么删掉原来的「识别图片与表情包」开关】
               那一版是一个**全局布尔开关**，但识图后来改成了**逐条消息**控制
               （在「读取」预览里每条消息的开关），全局开关就变成了重复且语义冲突的东西：
               两处都能控制"要不要识图"，它们之间的关系无法向用户解释清楚。
               所以这里改成**能力配置**（用哪个模型看、看多细），
               而"这条消息要不要看"交给读取预览里的逐条开关。

               这个划分是干净的：
                 * 这里回答「**能不能**看图」→ 设备级能力，配一次长期有效；
                 * 逐条开关回答「**这条**要不要看」→ 每条消息的隐私决定，默认关闭。 */
            VisionConfigControl.attach(winCard, topPad = 18)

            winCard.addView(
                glassActionButton(ctx, "\u7acb\u5373\u4fdd\u5b58\u5e76\u751f\u6548") {
                    // t4: keep a manual commit as well, so the behaviour is the same whatever route the
                    // user takes -- the fields already commit themselves, and this makes it explicit.
                    debounce.removeCallbacks(pending)
                    commitGeometry(announce = true)
                    // The mirror write is debounced; a deliberate save pushes it out at once.
                    try {
                        ConfigRepo.flushMirror()
                    } catch (_: Throwable) {
                        // best effort
                    }
                }.apply {
                    // A small top margin only, so no empty band appears under the fields.
                    // 玻璃化后按钮是等宽权重 1f，必须补回 MATCH_PARENT 宽度，否则按钮会缩成半宽。
                    (layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = ViewGroup.LayoutParams.MATCH_PARENT
                        it.topMargin = dp(10)
                    }
                },
            )
            winCard.addView(tvWinMsg)
            content.addView(winCard)

            val scroll = ScrollView(ctx).apply {
                addView(content)
                tag = Constants.TAG_OWN_UI
            }

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .setNegativeButton("\u5173\u95ed", null)
                .create()
            // t4: whatever the debounce still holds is written when the dialog goes away, so a value
            // typed and immediately abandoned is not lost.
            dlg.setOnDismissListener {
                try {
                    ConfigRepo.flushMirror()
                } catch (_: Throwable) {
                    // best effort
                }
            }
            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(
                    glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                )
            } catch (_: Throwable) {
                // best effort
            }
            OwnUi.register(content, scroll, dlg.window?.decorView)
        } catch (t: Throwable) {
            Log.e(TAG, "template dialog failed", t)
        }
    }

    /**
     * item 2: a label + field on ONE line, so the form stays compact.
     *
     * The previous layout put a label above a full-width field, and the field's own generous padding left
     * a visible empty band below the last row.
     */
    private fun compactRow(ctx: Context, label: String, field: android.widget.EditText): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, NukeTheme.dp(ctx, 2), 0, NukeTheme.dp(ctx, 2))
        }
        row.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 12f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            field,
            LinearLayout.LayoutParams(
                NukeTheme.dp(ctx, 92),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return row
    }

    /** Asks for a new name. */
    private fun promptRename(ctx: Context, t: Template, done: () -> Unit) {
        try {
            val et = NukeTheme.input(ctx, t.name, "\u6a21\u677f\u540d")
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("\u91cd\u547d\u540d")
                .setView(et)
                .setNegativeButton("\u53d6\u6d88", null)
                .setPositiveButton("\u786e\u5b9a") { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isNotBlank()) {
                        // v4.1.0: a Skill is filed under its TEMPLATE's name, so renaming the template
                        // must move its skills too -- otherwise the renamed template would show an empty
                        // list and the old files would sit on disk forever.
                        try {
                            com.luoliaoo.wechat.data.SkillStore.renameTemplate(t.name, n)
                        } catch (t2: Throwable) {
                            Log.w(TAG, "moving the template's skills failed: " + t2.message)
                        }
                        TemplateStore.rename(t.name, n)
                        done()
                    }
                }
                .create()
                .showStyled()
        } catch (t2: Throwable) {
            Log.w(TAG, "rename failed: " + t2.message)
        }
    }

    /** The add / edit form. */
    fun editTemplate(ctx: Context, existing: Template?, done: () -> Unit) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        try {
            val form = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(6))
            }
            form.addView(
                TextView(ctx).apply {
                    text = if (existing == null) "\u65b0\u5efa\u6a21\u677f" else "\u7f16\u8f86\u6a21\u677f"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 17f)
                    typeface = Typeface.DEFAULT_BOLD
                    // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                    background = titleRule(ctx)
                },
            )
            form.addView(NukeTheme.fieldLabel(ctx, "\u540d\u79f0"))
            val etName = NukeTheme.input(ctx, existing?.name.orEmpty(), "\u604b\u7231")
            form.addView(etName)

            // 名称校验的行内反馈 —— 紧贴出错的那个字段，而不是弹一条会飘走的 Toast。
            //
            // WHY IT IS NEEDED: 名称为空时保存会被拒绝且不关窗，如果界面上什么都不变，用户点「保存」
            // 看起来就像按钮坏了 —— 正是本项目反复踩过的「静默失败」。提示要出现在视线本来就盯着的地方
            // （名称框正下方），所以直接用 NukeTheme.errorBox：它是模块里「需要注意的提示」唯一既有
            // 样式（DANGER_BG 半透明填充 + DANGER_BORDER 描边 + DANGER_TEXT 文字），FloatingPanel /
            // UpdateDialog 用的是同一个盒子，颜色全部来自令牌，零硬编码。
            //
            // 默认 GONE：刚打开表单不该有红字，只有真的校验失败才出现。
            val tvNameError = NukeTheme.errorBox(ctx, "\u8bf7\u586b\u5199\u6a21\u677f\u540d\u79f0").apply {
                visibility = android.view.View.GONE
                // errorBox 自带 14dp topMargin（它是给卡片块间用的），行内提示只要一点间距，
                // 否则会把下面的第一个字段整体顶开。
                (layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = dp(6) }
                setTag(Constants.TAG_OWN_UI)
            }
            form.addView(tvNameError)

            // 用户一旦把名字填上，就把红字收掉：留着不放会让人以为仍然没填对。
            etName.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (s != null && s.toString().isNotBlank()) {
                            tvNameError.visibility = android.view.View.GONE
                        }
                    }
                },
            )
            form.addView(NukeTheme.fieldLabel(ctx, "AI \u5730\u5740"))
            val etBase = NukeTheme.input(ctx, existing?.baseUrl.orEmpty(), "https://api.deepseek.com")
            form.addView(etBase)
            form.addView(NukeTheme.fieldLabel(ctx, "Key"))
            val etKey = NukeTheme.input(ctx, existing?.apiKey.orEmpty(), "sk-...")
            form.addView(etKey)
            form.addView(NukeTheme.fieldLabel(ctx, "\u6a21\u578b"))
            val etModel = NukeTheme.input(ctx, existing?.model.orEmpty(), "deepseek-chat")
            form.addView(etModel)
            form.addView(NukeTheme.fieldLabel(ctx, "\u63d0\u793a\u8bcd"))
            val etPrompt = NukeTheme.input(ctx, existing?.systemPrompt.orEmpty(), "").apply {
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            form.addView(etPrompt)

            // ======================================================== 屏蔽词 (v6.0.0)
            //
            // WHY THIS FIELD WAS ADDED BACK: the user reported twice that adding a block word did
            // nothing -- 「屏蔽关键词失败了」, then 「msg有用 click与auth没用」. The cause was that the
            // word list was unreachable from BOTH ends:
            //
            //   * this form had no field for it, and
            //   * the 屏蔽 box had been REMOVED from the scope dialog back in v3.0.3 (see ScopeDialog),
            //
            // so `blockWords` existed in the data model, was persisted, was filtered on -- and could
            // not be edited by anyone. Only the hardcoded PromptBuilder.SKIP_MARKERS ("msg"/"了解详情"/":0")
            // ever took effect, which is exactly the "msg works, my words do not" symptom.
            //
            // One word per line, kept simple on purpose: words may contain characters that any
            // separator would have to escape (the defaults include ":0"), so a line break is the only
            // choice that cannot corrupt an entry.
            form.addView(NukeTheme.fieldLabel(ctx, "\u5c4f\u853d\u8bcd"))
            val etBlock = NukeTheme.input(
                ctx,
                (existing?.blockWords ?: TemplateStore.DEFAULT_BLOCK_WORDS).joinToString("\n"),
                "",
            ).apply {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                hint = "\u6bcf\u884c\u4e00\u4e2a\uff0c\u5305\u542b\u5b83\u7684\u6d88\u606f\u4e0d\u4f1a\u53d1\u7ed9 AI"
            }
            form.addView(etBlock)

            // ======================================================== Skill management (v4.1.0)
            //
            // 编辑模板 owns the template's whole definition, so the Skills of THIS template are managed
            // in the same dialog. A NEW template has no skills yet and no stable key to file them under
            // (a Skill is stored per template NAME), so the block appears once the template exists -- the
            // 编辑 button on its card in 模板管理.
            //
            // The block carries its own 「Skill 管理」 header, so no second title is added here.
            if (existing != null) {
                try {
                    form.addView(com.luoliaoo.wechat.ui.SkillDialog.card(ctx, existing.name))
                } catch (t: Throwable) {
                    Log.w(TAG, "skill section failed: " + t.message)
                }
            }

            // ======================================================== 玻璃操作条（取消 / 保存）
            //
            // WHY NOT setNegativeButton/setPositiveButton: those two live in the PLATFORM button
            // strip, which keeps the host theme's own chrome no matter what this file draws elsewhere
            // -- the same 取消/确定 pair stayed flat grey text while every panel around it had already
            // become glass (reported as 「模板新建的 ui 把取消跟确定 增加按钮」). The buttons are
            // therefore OUR views, in the same row shape as the 添加/恢复默认 actions row above.
            //
            // The whole form now travels down as ONE setView payload, buttons included: a dialog whose
            // body is ours and whose buttons are the platform's cannot be styled consistently.
            val formActions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // 与列表页底部操作条同一层玻璃：控件级 12dp 圆角 + 边缘光描边，与上方表单之间有
                // 明度差，否则字段区与按钮区会糊成一片。
                background = glassPanel(
                    ctx, R_CONTROL, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC,
                )
                setPadding(
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                )
            }
            form.addView(formActions)

            // 取消 / 保存 需要回调到对话框本身（关闭），而按钮是在 builder 之前构造的，所以用一个
            // 后置赋值持有对话框：点击时它必然已被赋值（对话框不在屏幕上就没人能点）。
            var dlg: AlertDialog? = null

            /**
             * 保存 —— 语义与原来的 setPositiveButton 回调逐行一致。
             *
             * 抽成具名函数只是因为现在有两个地方需要「校验 + 落库」：按钮点击，以及名称为空时
             * 干脆不关窗（原来的 `return@setPositiveButton` 会静默关窗，用户的输入直接丢掉）。
             */
            fun save() {
                val t = Template(
                    name = etName.text.toString().trim(),
                    baseUrl = etBase.text.toString().trim(),
                    apiKey = etKey.text.toString().trim(),
                    model = etModel.text.toString().trim(),
                    systemPrompt = etPrompt.text.toString(),
                    // v6.0.0: one word per line; blanks dropped so a stray blank line cannot become
                    // a block word that would match EVERY message (an empty string is contained in
                    // every string, and PromptBuilder.isBlockedBy guards against it, but not
                    // persisting one is the real fix).
                    blockWords = etBlock.text.toString()
                        .split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                )
                // 名称为空 = 不保存，且不关窗：模板名是 Skill 的归档键，空名会写出一份无法再被找到
                // 的配置；保持对话框打开，用户补上名字即可继续，不用重新填一遍表单。
                //
                // 但「不关窗」本身不是反馈 —— 什么都不变的话用户会以为保存按钮坏了。这里必须显式
                // 报错：亮出名称框下方那条红字，并把焦点移过去，让下一步该做什么一目了然
                // （requestFocus 会同时弹出输入法，光标直接落在要改的字段里）。
                if (t.name.isBlank()) {
                    tvNameError.visibility = android.view.View.VISIBLE
                    etName.requestFocus()
                    Log.i(TAG, "template name is blank -- save rejected, the form stays open")
                    return
                }
                if (existing == null) {
                    TemplateStore.add(t)
                } else {
                    // v4.1.0: THIS FORM CAN RENAME TOO (the 名称 field is editable), and a Skill is
                    // filed under its template's name -- so the skills move with it. Without this the
                    // renamed template would show an empty list and the files would be orphaned.
                    if (existing.name != t.name) {
                        try {
                            com.luoliaoo.wechat.data.SkillStore.renameTemplate(existing.name, t.name)
                        } catch (t2: Throwable) {
                            Log.w(TAG, "moving the template's skills failed: " + t2.message)
                        }
                    }
                    TemplateStore.update(existing.name, t)
                }
                dlg?.dismiss()
                done()
            }

            // 玻璃化：取消是次操作 → 保持 NukeTheme 的描边样式；保存是主操作 → 强调色实心玻璃块。
            // 按钮自身的 layoutParams 已经是等宽权重 1f（与列表页操作条同款），这里不再覆写。
            formActions.addView(
                NukeTheme.warnOutlineButton(ctx, "\u53d6\u6d88") {
                    dlg?.dismiss()
                },
            )
            formActions.addView(glassActionButton(ctx, "\u4fdd\u5b58") { save() })

            val scroll = ScrollView(ctx).apply { addView(form) }
            dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scroll)
                .create()
            // 对话框主容器也用玻璃面板，与模板管理页、picker 保持同一块「玻璃」；
            // 少了这一步，自绘按钮会浮在系统默认的实色圆角底上，反而比改造前更割裂。
            dlg.showStyled()
            try {
                dlg.window?.setBackgroundDrawable(
                    glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                )
            } catch (_: Throwable) {
                // best effort
            }
        } catch (t: Throwable) {
            Log.e(TAG, "edit template failed", t)
        }
    }

    /**
     * item 3 / 6: the picker.
     *
     * Lists the templates by NAME; choosing one hands it back so the caller can apply it to the config
     * (the API settings) or to the prompt only (恢复指定提示词).
     */
    fun pick(ctx: Context, title: String, onPick: (Template) -> Unit) {
        fun dp(v: Int) = NukeTheme.dp(ctx, v)
        OwnUi.suppressFor(2000L)
        try {
            val names = TemplateStore.all().map { it.name }.toTypedArray()
            if (names.isEmpty()) {
                AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage("\u6ca1\u6709\u6a21\u677f\uff0c\u8bf7\u5148\u5728\u914d\u7f6e\u91cc\u6dfb\u52a0")
                    .setPositiveButton("\u77e5\u9053\u4e86", null)
                    .create()
                    .showStyled()
                return
            }
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(names) { _, which ->
                    val t = TemplateStore.byName(names[which])
                    Log.i(TAG, "template picked: " + names[which] + " found=" + (t != null))
                    if (t != null) onPick(t)
                }
                .setNegativeButton("\u53d6\u6d88", null)
                .create()
                .also { d ->
                    d.showStyled()
                    try {
                        d.window?.setBackgroundDrawable(
                            glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                        )
                    } catch (_: Throwable) {
                        // best effort
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "template picker failed", t)
        }
    }

    /**
     * item 3: applies a template to the CURRENT config (address, key, model, prompt).
     *
     * An empty field in the template leaves the corresponding configured value untouched, so a built-in
     * template (which ships without a key) does not wipe the key the user already entered.
     */
    fun applyToConfig(ctx: Context, t: Template) {
        try {
            val c: Config = ConfigRepo.loadLocal(ctx)
            // item 7: EVERY field the template carries is applied -- address, key, model, prompt AND the
            // default analysis scope. Only an empty field leaves the configured value alone, so a
            // built-in template (which ships without a key) never wipes the key the user entered.
            val merged = c.copy(
                baseUrl = t.baseUrl.ifBlank { c.baseUrl },
                apiKey = t.apiKey.ifBlank { c.apiKey },
                model = t.model.ifBlank { c.model },
                systemPrompt = t.systemPrompt.ifBlank { c.systemPrompt },
                defaultUp = t.defaultUp,
                defaultDown = t.defaultDown,
                defaultRangeHours = t.defaultRangeHours,
            )
            ConfigRepo.saveLocalAndMirror(ctx, merged)
            // t4: the geometry may have changed with the template; apply it to a panel that is already
            // on screen instead of waiting for the next open.
            com.luoliaoo.wechat.ui.FloatingPanel.onConfigGeometryChanged(ctx)
            // t4: the font metric cache may be stale if the template changed 自适应字体.
            com.luoliaoo.wechat.ui.NukeTheme.invalidateFontCache()
            Log.i(
                TAG,
                "template applied: " + t.name + " model=" + merged.model +
                    " baseUrl=" + merged.baseUrl + " keyPresent=" + merged.apiKey.isNotBlank() +
                    " up=" + merged.defaultUp + " down=" + merged.defaultDown +
                    " hours=" + merged.defaultRangeHours,
            )
        } catch (t2: Throwable) {
            Log.w(TAG, "applying the template failed: " + t2.message)
        }
    }
}
