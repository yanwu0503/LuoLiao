package com.luoliaoo.wechat.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi
import com.luoliaoo.wechat.data.Skill
import com.luoliaoo.wechat.data.SkillStore
import com.luoliaoo.wechat.data.TemplateStore
import java.io.File

/**
 * The 编辑模板 page's 「Skill 管理」 block.
 *
 * A Skill belongs to ONE template, so everything here is keyed by `templateName`: the same code serves
 * 恋爱, 工作 and any template the user adds, without a second implementation.
 *
 * The block is a plain [LinearLayout] card, embedded directly in the template edit form by
 * [com.luoliaoo.wechat.ui.ConfigDialog] and [com.luoliaoo.wechat.ui.TemplateDialog]. It refreshes ITSELF
 * after every operation (list, per-row actions and the 「共 N 个」 counter all redraw together), so the
 * count and the list can never drift out of step with what is on disk.
 *
 * A skill is a FOLDER holding `SKILL.md` (see [SkillStore]), and the import / export format is the zip the
 * same layout produces -- `zip -> <name>/SKILL.md -> skills` is one round trip with nothing lost.
 *
 * NOTE: comments are ASCII-only on purpose (see core/Log.kt).
 */
object SkillDialog {

    private const val TAG = "LuoLiao"

    /** The largest import archive accepted. Shared with the store, so the two can never disagree. */
    private const val MAX_ZIP_BYTES = SkillStore.MAX_ZIP_BYTES

    // ---------------------------------------------------------------- 液态玻璃尺度（冻结值）
    //
    // 圆角节奏取自 LIQUID-GLASS-CONTRACT，全站只有这 5 档，不出现第 6 档：
    //   弹窗主容器 24 / 卡片 18 / 控件 12 / chip 10 / 徽标 6
    // 用具名常量而不是散落的数字字面量，是为了让「圆角失控」在编译期就看得见 ——
    // 审计发现项目里已出现 18、14 等各自为政的硬编码圆角，这里把本文件的取值钉死在这 5 档上。

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
     *   ② 主体：令牌填充 + 显式 alpha，半透明才有「通透」的玻璃感，直接传不透明色就退化成色块；
     *   ③ 厚度：1dp 描边就是玻璃的边缘光，这是「看起来是一块玻璃」而不是「一块半透明色」的关键。
     *
     * 圆角遵守全站冻结的节奏：弹窗 24dp / 卡片 18dp / 控件 12dp / chip 10dp / 徽标 6dp。
     * 所有颜色都取自 NukeTheme 现有 token —— 对话框跑在微信/抖音/QQ/Soul/快手宿主的进程里，
     * 宿主主题各不相同，只有完全自绘、且颜色只来自我们自己的令牌，观感才是稳定的。
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
     * 刻意不硬编码任何颜色值：rgb 三位取自传入的 NukeTheme token，只有 alpha 是本地的，
     * 因此明暗主题切换、或主题内核调整令牌取值时，这些派生色会自动跟着变。
     */
    private fun glassVeil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * 标题区与内容区之间的柔和分隔 —— **不新增任何视图**。
     *
     * 做法是给已有的标题 TextView 铺一层竖向渐变（全透明 → 极淡主题色），
     * 在标题下方形成一条「光带」。相比额外加一条横线，它不改变视图层级，也更符合玻璃语言。
     */
    private fun titleRule(ctx: Context, radiusDp: Int = R_CONTROL): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(android.graphics.Color.TRANSPARENT, glassVeil(NukeTheme.ACCENT, 0x1F)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
    }

    /**
     * 主操作按钮 —— 实心强调色玻璃块（圆角 12dp，遵守控件节奏）。
     *
     * 按钮行需要「主次分明」，而 NukeTheme 现有的 warnButton 是固定琥珀黄实心块：
     * 放在已玻璃化的深色面板上像一块贴纸，也不属于本次冻结的玻璃语言。
     * 因此主操作改用 ACCENT 实心 + 近黑文字（TEXT_ON_ACCENT），顶部加一点点渐变当受光面；
     * 次操作继续沿用 NukeTheme 的描边按钮。**只改样式，onClick 回调原样透传。**
     */
    private fun glassActionButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(NukeTheme.TEXT_ON_ACCENT)
            textSize = NukeTheme.sp(ctx, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            // 无障碍：本按钮是自绘的，不走 NukeTheme 的工厂方法，因此要自己保证 48dp 触控高度，
            // 否则会比同屏那些已被内核抬高的按钮更难按。
            // 【取舍说明】本按钮有背景（实心强调色玻璃块），所以这个最小高度会把可见按钮一起
            // 抬到 48dp —— 这是团队明确裁决过的选择（选项 a），不是疏漏。团队否决了「TouchDelegate
            // 隐形热区」方案，理由：它必须挂在比子 View 更大的父容器上（本按钮是等宽权重布局，
            // 边界条件不可靠），且行为依赖宿主 measure/layout 时序，而模块要跨微信/抖音/QQ/Soul/
            // 快手 5 个宿主；相比之下可见目标本身达到 48dp 是更好的无障碍目标。
            minimumHeight = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            minimumWidth = NukeTheme.dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            // layoutParams 与 NukeTheme 的按钮完全一致（等宽权重 + 8dp 间距），
            // 这样原有的按钮行节奏不会被改变。
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = NukeTheme.dp(ctx, 8) }
        }

    /**
     * Builds the embeddable 「Skill 管理」 card for one template.
     *
     * The returned view keeps working on its own: its buttons close over the section that built it, so a
     * create / edit / rename / delete / import updates the visible list immediately.
     */
    fun card(ctx: Context, templateName: String): LinearLayout {
        try {
            SkillStore.ensureAttached(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "skill store attach failed: " + t.message)
        }
        return SkillSection(ctx, templateName).view
    }

    /**
     * The same manager, on its own.
     *
     * Used when there is no template form to embed it in (and useful for a future entry point in the
     * module app); it is the identical card inside a scrolling dialog.
     */
    fun show(ctx: Context, templateName: String) {
        OwnUi.suppressFor(3000L)
        try {
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：外层容器改为液态玻璃面板（24dp 圆角 + 边缘光描边 + 半透明主体）。
                // 用本文件私有的 glassPanel，避免改动 NukeTheme.kt。
                background = glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE)
                setPadding(
                    NukeTheme.dp(ctx, 20), NukeTheme.dp(ctx, 20),
                    NukeTheme.dp(ctx, 20), NukeTheme.dp(ctx, 12),
                )
                tag = Constants.TAG_OWN_UI
            }
            content.addView(NukeTheme.eyebrow(ctx, "落聊 · Skill"))
            content.addView(
                NukeTheme.title(ctx, "Skill 管理", 22f).apply {
                    // 玻璃化：标题下方铺一条渐变光带，把「标题区」与「内容区」在视觉上分开。
                    // 只改背景，不新增视图、不改层级。
                    background = titleRule(ctx)
                },
            )
            content.addView(
                NukeTheme.body(
                    ctx,
                    "模板：" + templateName,
                    NukeTheme.TEXT_MUTED,
                    12f,
                ).apply { setPadding(0, NukeTheme.dp(ctx, 6), 0, NukeTheme.dp(ctx, 12)) },
            )
            content.addView(card(ctx, templateName))

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
                    glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                )
            } catch (_: Throwable) {
                // best effort
            }
            OwnUi.register(content, scroll, dlg.window?.decorView)
        } catch (t: Throwable) {
            Log.e(TAG, "skill manager failed", t)
        }
    }

    // ================================================================== the embedded block

    /**
     * The live section: header + count, empty state, the skill list, the action row and a status line.
     *
     * Everything is rebuilt by [refresh], which is the ONLY place the views are produced -- so an operation
     * cannot forget to redraw part of the block.
     */
    private class SkillSection(private val ctx: Context, private val templateName: String) {

        val view: LinearLayout = NukeTheme.sectionCard(ctx)

        private val tvCount: TextView = TextView(ctx).apply {
            setTextColor(NukeTheme.ACCENT_SOFT)
            textSize = NukeTheme.sp(ctx, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val tvStatus: TextView = TextView(ctx).apply {
            text = ""
            textSize = NukeTheme.sp(ctx, 12f)
            setTextColor(NukeTheme.TEXT_MUTED)
            setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
            setLineSpacing(NukeTheme.dp(ctx, 3).toFloat(), 1.4f)
        }

        /** "（全部生效）" / "（N 个内容为空）" -- how many skills actually reach the prompt. */
        private val tvEffective: TextView = TextView(ctx).apply {
            text = ""
            textSize = NukeTheme.sp(ctx, 11f)
            setTextColor(NukeTheme.TEXT_DIM)
            setPadding(0, NukeTheme.dp(ctx, 4), 0, 0)
        }

        private val listBox: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        init {
            view.tag = Constants.TAG_OWN_UI

            // ---- header: the title and the live count, on one line.
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 玻璃化：标题行是一张「内嵌玻璃卡片」的顶栏，给它一条渐变光带，
                // 使它和下面的列表区之间形成明度差（层次分隔），而不是靠加一条分隔线。
                background = titleRule(ctx, R_CHIP)
                setPadding(0, NukeTheme.dp(ctx, 2), 0, NukeTheme.dp(ctx, 6))
            }
            header.addView(
                NukeTheme.category(ctx, "Skill 管理"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            header.addView(tvCount)
            view.addView(header)

            view.addView(
                NukeTheme.body(
                    ctx,
                    "Skill 属于当前模板；分析时会作为补充要求追加到本模板的系统提示词里",
                    NukeTheme.TEXT_DIM,
                    11f,
                ).apply { setPadding(0, NukeTheme.dp(ctx, 5), 0, NukeTheme.dp(ctx, 4)) },
            )

            view.addView(listBox)
            view.addView(tvEffective)

            // ---- actions: 创建 / 导入 / 导出
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                // 玻璃化：操作行独立成一条玻璃操作条（控件级 12dp 圆角），
                // 与列表区之间有明度差，避免所有内容糊成一片。
                background = glassPanel(
                    ctx, 12, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC,
                )
                setPadding(
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                    NukeTheme.dp(ctx, 8), NukeTheme.dp(ctx, 10),
                )
            }
            // 玻璃化：创建是这一行的主操作 → 强调色实心玻璃；导入 / 导出为次操作 → 保持描边。
            actions.addView(glassActionButton(ctx, "创建") { create(null) })
            actions.addView(NukeTheme.warnOutlineButton(ctx, "导入") { importMenu() })
            actions.addView(
                NukeTheme.warnOutlineButton(ctx, "导出") { exportZip() }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
                },
            )
            view.addView(actions)

            // ---- item 7: the storage path, with a 复制 button right beside it.
            //
            // The path is only useful if it can be handed to something else (a file manager, a chat), and
            // typing an Android path by hand is exactly the kind of thing nobody does correctly.
            val locationRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, NukeTheme.dp(ctx, 10), 0, 0)
            }
            locationRow.addView(
                NukeTheme.hintText(ctx, "存储位置：" + SkillStore.dirPath(templateName)).apply {
                    setPadding(0, 0, 0, 0)
                    maxLines = 2
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            locationRow.addView(
                NukeTheme.warnOutlineButton(ctx, "复制") { copyPath() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(
                        NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8),
                        NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 8),
                    )
                },
            )
            view.addView(locationRow)

            view.addView(tvStatus)
            refresh()
        }

        // -------------------------------------------------------------- rendering

        /** Rebuilds the list AND the count. Called after every operation. */
        fun refresh() {
            val all = try {
                SkillStore.all(templateName)
            } catch (t: Throwable) {
                Log.w(TAG, "skill list failed: " + t.message)
                emptyList()
            }
            tvCount.text = "共 " + all.size + " 个"

            // Whether every skill actually reaches the prompt is worth saying out loud: the block is
            // capped, and a silent truncation is exactly the kind of thing that looks like "the AI ignored
            // my skill".
            val effective = all.count { it.content.isNotBlank() }
            tvEffective.text = when {
                all.isEmpty() -> ""
                effective == 0 -> "（都还没有内容，暂不会影响分析）"
                effective < all.size -> "（" + effective + " 个会生效，" + (all.size - effective) + " 个内容为空）"
                else -> "（全部生效）"
            }

            listBox.removeAllViews()
            if (all.isEmpty()) {
                // The empty state GUIDES rather than merely reporting: it names both ways forward, and the
                // two buttons are the ones in the action row right below.
                listBox.addView(
                    TextView(ctx).apply {
                        text = "本模板还没有 Skill\n" +
                            "点下面的「创建」新建一个，或点「导入」从 zip / 其它模板导入"
                        setTextColor(NukeTheme.TEXT_MUTED)
                        textSize = NukeTheme.sp(ctx, 12f)
                        setLineSpacing(NukeTheme.dp(ctx, 5).toFloat(), 1.5f)
                        // 玻璃化：空状态是一块「虚线感的提示玻璃」—— 卡片级 18dp 圆角 +
                        // 玻璃渐变 + 边缘光描边，比原来的纯色块更能表达「这里还是空的」。
                        background = glassPanel(
                            ctx, R_CARD, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC,
                        )
                        setPadding(
                            NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 14),
                            NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 14),
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                    },
                )
                return
            }

            for (s in all) {
                listBox.addView(row(s))
            }
        }

        /** One skill: its name, a taste of its body, and the three per-item entries. */
        private fun row(s: Skill): LinearLayout {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 玻璃化：每个 Skill 是一个「列表项容器」，做成卡片级 18dp 玻璃
                // （半透明填充 + 边缘光描边 + 左上层高光），叠在 sectionCard 之上形成两层通透。
                background = glassPanel(
                    ctx, R_CARD, NukeTheme.FEATURE_ALT, NukeTheme.FEATURE_BORDER, 0xCC,
                )
                setPadding(
                    NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
                    NukeTheme.dp(ctx, 14), NukeTheme.dp(ctx, 12),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                tag = Constants.TAG_OWN_UI
            }
            box.addView(
                TextView(ctx).apply {
                    text = s.name
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
            )
            if (s.description.isNotBlank()) {
                box.addView(
                    TextView(ctx).apply {
                        text = s.description
                        setTextColor(NukeTheme.TEXT_DIM)
                        textSize = NukeTheme.sp(ctx, 11f)
                        setPadding(0, NukeTheme.dp(ctx, 3), 0, 0)
                    },
                )
            }
            box.addView(
                TextView(ctx).apply {
                    val flat = s.content.trim().replace('\n', ' ')
                    text = when {
                        flat.isEmpty() -> "（内容为空）"
                        flat.length > 60 -> flat.take(60) + "…"
                        else -> flat
                    }
                    setTextColor(NukeTheme.TEXT_MUTED)
                    textSize = NukeTheme.sp(ctx, 11f)
                    setPadding(0, NukeTheme.dp(ctx, 5), 0, 0)
                },
            )

            val buttons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, NukeTheme.dp(ctx, 10), 0, 0)
            }
            // 玻璃化：一行的三个操作里「编辑」是主操作，改用强调色玻璃实心块，
            // 与「重命名」（描边）/「删除」（危险色）拉开主次。回调与布局参数都不变。
            buttons.addView(glassActionButton(ctx, "编辑") { create(s) })
            buttons.addView(NukeTheme.warnOutlineButton(ctx, "重命名") { rename(s) })
            buttons.addView(
                NukeTheme.dangerButton(ctx, "删除") { confirmDelete(s) }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.let {
                        it.width = 0
                        it.weight = 1f
                    }
                },
            )
            box.addView(buttons)
            return box
        }

        // -------------------------------------------------------------- feedback

        private fun ok(msg: String) {
            tvStatus.setTextColor(NukeTheme.OK)
            tvStatus.text = msg
            refresh()
        }

        private fun fail(msg: String) {
            tvStatus.setTextColor(NukeTheme.DANGER_TEXT)
            tvStatus.text = msg
        }

        /** item 7: hands the storage path to the clipboard. */
        private fun copyPath() {
            val path = SkillStore.dirPath(templateName)
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("落聊 Skill 路径", path))
                ok("路径已复制：" + path)
            } catch (t: Throwable) {
                Log.w(TAG, "copying the skill path failed: " + t.message)
                fail("复制失败：" + (t.message ?: "剪贴板不可用"))
            }
        }

        // -------------------------------------------------------------- create / edit

        /** The create / edit form. `existing == null` means a new skill. */
        private fun create(existing: Skill?) {
            OwnUi.suppressFor(3000L)
            try {
                val d = NukeTheme.dp(ctx, 18)
                val form = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d, NukeTheme.dp(ctx, 14), d, NukeTheme.dp(ctx, 6))
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(
                    TextView(ctx).apply {
                        text = if (existing == null) "新建 Skill" else "编辑 Skill"
                        setTextColor(NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 17f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                        background = titleRule(ctx)
                    },
                )
                form.addView(
                    NukeTheme.hintText(ctx, "所属模板：" + templateName),
                )

                form.addView(NukeTheme.fieldLabel(ctx, "名称"))
                val etName = NukeTheme.input(ctx, existing?.name.orEmpty(), "例如：谈判开场").apply {
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(etName)
                form.addView(
                    NukeTheme.hintText(
                        ctx,
                        "最多 " + SkillStore.MAX_NAME + " 字；不能与同模板下的其它 Skill 重名",
                    ),
                )

                form.addView(NukeTheme.fieldLabel(ctx, "说明（可选）"))
                val etDesc = NukeTheme.input(ctx, existing?.description.orEmpty(), "一句话说明它做什么").apply {
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(etDesc)

                form.addView(NukeTheme.fieldLabel(ctx, "内容"))
                val etContent = NukeTheme.input(ctx, existing?.content.orEmpty(), "这个 Skill 的具体指令").apply {
                    minLines = 6
                    maxLines = 12
                    isSingleLine = false
                    gravity = Gravity.TOP or Gravity.START
                    setTextColor(NukeTheme.TEXT)
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(etContent)

                val tvErr = TextView(ctx).apply {
                    text = ""
                    setTextColor(NukeTheme.DANGER_TEXT)
                    textSize = NukeTheme.sp(ctx, 12f)
                    setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
                }
                form.addView(tvErr)

                val scroll = ScrollView(ctx).apply {
                    addView(form)
                    tag = Constants.TAG_OWN_UI
                }
                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(scroll)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存", null)
                    .create()
                dlg.showStyled()

                // The positive button is wired AFTER show(), so a rejected name keeps the dialog open --
                // which is what lets the error be read and corrected instead of silently lost.
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    val desc = etDesc.text.toString().trim()
                    // The body keeps its own line breaks; only the edges are trimmed. The SAME value is
                    // validated and then saved, so a limit can never be checked against a different string
                    // than the one that lands on disk.
                    val body = etContent.text.toString().trim()

                    // The rules come from the store, so this form and the importer can never disagree
                    // about what a valid skill is.
                    SkillStore.validate(name, desc, body)?.let {
                        tvErr.text = it
                        return@setOnClickListener
                    }

                    val clash = SkillStore.conflict(templateName, name, ignore = existing?.name)
                    if (clash != null) {
                        tvErr.text = "已存在同名 Skill「" + clash.name + "」，请换一个名称"
                        return@setOnClickListener
                    }

                    // ONE write for both the edited fields and a possible rename: `existing.name` names the
                    // skill being replaced, so it keeps its position and the old file is removed by the same
                    // save. Calling add + rename instead would leave TWO skills behind -- the new name is
                    // exactly what the rename would then collide with.
                    val saved = SkillStore.save(
                        templateName,
                        existing?.name,
                        Skill(
                            name = name,
                            description = desc,
                            content = body,
                            builtIn = existing?.builtIn ?: false,
                        ),
                    )
                    if (!saved) {
                        tvErr.text = "保存失败：写入存储失败，请重试"
                        return@setOnClickListener
                    }
                    dlg.dismiss()
                    ok(if (existing == null) "已创建「" + name + "」" else "已保存「" + name + "」")
                }
                try {
                    dlg.window?.setBackgroundDrawable(
                        glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                    )
                } catch (_: Throwable) {
                    // best effort
                }
                OwnUi.register(form, scroll, dlg.window?.decorView)
            } catch (t: Throwable) {
                Log.e(TAG, "skill form failed", t)
                fail("打开表单失败：" + t.message)
            }
        }

        // -------------------------------------------------------------- rename

        private fun rename(s: Skill) {
            OwnUi.suppressFor(3000L)
            try {
                val d = NukeTheme.dp(ctx, 18)
                val form = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d, NukeTheme.dp(ctx, 14), d, NukeTheme.dp(ctx, 6))
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(
                    TextView(ctx).apply {
                        text = "重命名 Skill"
                        setTextColor(NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 17f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                        background = titleRule(ctx)
                    },
                )
                form.addView(NukeTheme.fieldLabel(ctx, "新名称"))
                val et = NukeTheme.input(ctx, s.name, "Skill 名称").apply {
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(et)
                val tvErr = TextView(ctx).apply {
                    text = ""
                    setTextColor(NukeTheme.DANGER_TEXT)
                    textSize = NukeTheme.sp(ctx, 12f)
                    setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
                }
                form.addView(tvErr)

                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(form)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", null)
                    .create()
                dlg.showStyled()
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val n = et.text.toString().trim()
                    when {
                        n.isEmpty() -> {
                            tvErr.text = "名称不能为空"
                            return@setOnClickListener
                        }
                        n == s.name -> {
                            tvErr.text = "名称没有变化"
                            return@setOnClickListener
                        }
                        n.length > SkillStore.MAX_NAME -> {
                            tvErr.text = "名称最多 " + SkillStore.MAX_NAME + " 字"
                            return@setOnClickListener
                        }
                    }
                    val clash = SkillStore.conflict(templateName, n, ignore = s.name)
                    if (clash != null) {
                        tvErr.text = "已存在同名 Skill「" + clash.name + "」"
                        return@setOnClickListener
                    }
                    if (!SkillStore.rename(templateName, s.name, n)) {
                        tvErr.text = "重命名失败：写入存储失败，请重试"
                        return@setOnClickListener
                    }
                    dlg.dismiss()
                    ok("已重命名：" + s.name + " → " + n)
                }
                try {
                    dlg.window?.setBackgroundDrawable(
                        glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                    )
                } catch (_: Throwable) {
                    // best effort
                }
                OwnUi.register(form, dlg.window?.decorView)
            } catch (t: Throwable) {
                Log.e(TAG, "skill rename failed", t)
                fail("重命名失败：" + t.message)
            }
        }

        // -------------------------------------------------------------- delete

        /** Deleting always asks first: the name is repeated, so the wrong row cannot be removed by a slip. */
        private fun confirmDelete(s: Skill) {
            OwnUi.suppressFor(2500L)
            try {
                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("删除 Skill")
                    .setMessage("将删除「" + s.name + "」，此操作不可撤销。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        if (SkillStore.remove(templateName, s.name)) {
                            ok("已删除「" + s.name + "」")
                        } else {
                            fail("删除失败：「" + s.name + "」不存在或写入存储失败")
                        }
                    }
                    .create()
                dlg.showStyled()
            } catch (t: Throwable) {
                Log.e(TAG, "skill delete failed", t)
                fail("删除失败：" + t.message)
            }
        }

        // -------------------------------------------------------------- import

        /**
         * The import chooser.
         *
         * Three entries, as real BUTTONS rather than plain text -- a text link is easy to miss and hard to
         * hit, and these are the two ways a skill ever gets in.
         *
         *   1. ONE skill from a zip (a single SKILL.md).
         *   2. Copy one skill out of another template.
         *   3. MANY skills from a zip someone else exported.
         */
        private fun importMenu() {
            OwnUi.suppressFor(3500L)
            try {
                val d = NukeTheme.dp(ctx, 18)
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d, NukeTheme.dp(ctx, 14), d, NukeTheme.dp(ctx, 10))
                    tag = Constants.TAG_OWN_UI
                }
                box.addView(
                    TextView(ctx).apply {
                        text = "导入 Skill"
                        setTextColor(NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 17f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                        background = titleRule(ctx)
                    },
                )
                box.addView(
                    NukeTheme.hintText(
                        ctx,
                        "zip 里按「Skill 名/SKILL.md」存放；与 Skill 无关的文件（agent、配置等）会被自动忽略。" +
                            "格式不对时不会改动已有数据。",
                    ),
                )

                box.addView(
                    menuButton("① 导入 zip（一个 Skill）") { importZip(single = true) },
                )
                box.addView(
                    menuButton("② 粘贴其它模板的其中一个 Skill") { pickFromOtherTemplate() },
                )
                box.addView(
                    menuButton("③ 导入其它用户导出的 zip（可含多个）") { importZip(single = false) },
                )

                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(box)
                    .setNegativeButton("取消", null)
                    .create()
                dlg.showStyled()
                try {
                    dlg.window?.setBackgroundDrawable(
                        glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                    )
                } catch (_: Throwable) {
                    // best effort
                }
                OwnUi.register(box, dlg.window?.decorView)
            } catch (t: Throwable) {
                Log.e(TAG, "skill import menu failed", t)
                fail("导入失败：" + t.message)
            }
        }

        /** One full-width button in the import menu, dismissed by the caller's own flow. */
        private fun menuButton(label: String, onClick: () -> Unit): TextView =
            NukeTheme.warnOutlineButton(ctx, label) { onClick() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = NukeTheme.dp(ctx, 8) }
            }

        /**
         * Imports from a zip.
         *
         * @param single true for ①: the archive must hold exactly ONE skill, and the user is asked for the
         *               display name and description before anything is written. False for ③: every skill in
         *               the archive is taken, under the names the archive carries.
         *
         * Reading is delegated to [SkillStore.skillsFromZip], which parses the WHOLE archive before a byte
         * is written -- so a malformed zip can never leave the list half replaced.
         */
        private fun importZip(single: Boolean) {
            // The SYSTEM file chooser, not a scan of the public directories. See FilePicker: on Android 11+
            // an app cannot LIST 下载 / Documents at all, which is why the old scanner reported
            // "没有找到可导入的文件" for a file that was plainly there.
            FilePicker.pick(ctx) { result ->
                when (result) {
                    is FilePicker.Picked -> consumeZip(result.name, result.bytes, single)
                    is FilePicker.Failure -> {
                        // A cancelled picker is not a failure worth shouting about, but it must still clear
                        // any earlier success message.
                        if (result.message == "已取消") {
                            Log.i(TAG, "skill import: file pick cancelled")
                        } else {
                            fail("导入失败：" + result.message)
                        }
                    }
                    else -> fail("导入失败：没有取到文件")
                }
            }
        }

        private fun consumeZip(fileName: String, bytes: ByteArray, single: Boolean) {
            if (bytes.size > MAX_ZIP_BYTES) {
                fail(
                    "导入失败：压缩包过大（" + sizeLabel(bytes.size.toLong()) + "，上限 " +
                        sizeLabel(MAX_ZIP_BYTES) + "）",
                )
                return
            }

            val res = SkillStore.skillsFromZip(bytes)
            if (!res.ok) {
                fail(res.message)
                return
            }

            if (single) {
                if (res.skills.size > 1) {
                    fail(
                        "这个压缩包里有 " + res.skills.size + " 个 Skill；" +
                            "请改用「③ 导入其它用户导出的 zip」一次导入",
                    )
                    return
                }
                // item 3/1: ask for the display name and the (optional) description BEFORE writing.
                askNameAndImport(res.skills[0], fileName)
                return
            }

            val r = SkillStore.importMany(templateName, res.skills)
            report(r)
        }

        /**
         * The ① confirmation step: the display name, and an optional description.
         *
         * Both fields are PREFILLED from the archive, so accepting what the file already says is one tap.
         * The buttons are real, bold, white ones -- a dim platform button on the dark panel is exactly the
         * thing that reads as "not a button".
         */
        private fun askNameAndImport(incoming: Skill, fileName: String) {
            OwnUi.suppressFor(3000L)
            try {
                val d = NukeTheme.dp(ctx, 18)
                val form = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d, NukeTheme.dp(ctx, 14), d, NukeTheme.dp(ctx, 6))
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(
                    TextView(ctx).apply {
                        text = "设置导入的 Skill"
                        setTextColor(NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 17f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                        background = titleRule(ctx)
                    },
                )
                form.addView(
                    NukeTheme.hintText(
                        ctx,
                        "来源：" + fileName + " · 已读取 " + incoming.content.length +
                            " 字内容，导入后可在列表里继续编辑",
                    ),
                )

                form.addView(NukeTheme.fieldLabel(ctx, "显示名称"))
                val etName = NukeTheme.input(ctx, incoming.name, "例如：谈判开场").apply {
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(etName)

                form.addView(NukeTheme.fieldLabel(ctx, "说明（可选）"))
                val etDesc = NukeTheme.input(ctx, incoming.description, "一句话说明它做什么").apply {
                    tag = Constants.TAG_OWN_UI
                }
                form.addView(etDesc)

                val tvErr = TextView(ctx).apply {
                    text = ""
                    setTextColor(NukeTheme.DANGER_TEXT)
                    textSize = NukeTheme.sp(ctx, 12f)
                    setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
                }
                form.addView(tvErr)

                val scroll = ScrollView(ctx).apply {
                    addView(form)
                    tag = Constants.TAG_OWN_UI
                }
                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(scroll)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下一步", null)
                    .create()
                dlg.showStyled()

                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    val desc = etDesc.text.toString().trim()
                    SkillStore.validate(name, desc, incoming.content)?.let {
                        tvErr.text = it
                        return@setOnClickListener
                    }
                    val clash = SkillStore.conflict(templateName, name)
                    if (clash != null) {
                        tvErr.text = "已存在同名 Skill「" + clash.name + "」，请换一个名称"
                        return@setOnClickListener
                    }
                    if (SkillStore.add(templateName, incoming.copy(name = name, description = desc, builtIn = false))) {
                        dlg.dismiss()
                        ok("导入成功：新增 1 个（" + name + "）")
                    } else {
                        tvErr.text = "导入失败：写入存储失败，请重试"
                    }
                }
                try {
                    dlg.window?.setBackgroundDrawable(
                        glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                    )
                } catch (_: Throwable) {
                    // best effort
                }
                OwnUi.register(form, scroll, dlg.window?.decorView)
            } catch (t: Throwable) {
                Log.e(TAG, "skill zip naming failed", t)
                fail("导入失败：" + t.message)
            }
        }

        /**
         * ② copies ONE skill out of another template.
         *
         * Two steps: pick the source template, then pick the skill. A name that already exists in the
         * target is reported and NOT overwritten, so copying can never silently replace a local skill.
         */
        private fun pickFromOtherTemplate() {
            val others = try {
                TemplateStore.all().map { it.name }.filter { it != templateName }
            } catch (_: Throwable) {
                emptyList()
            }
            if (others.isEmpty()) {
                fail("导入失败：没有其它模板可作为来源")
                return
            }
            try {
                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("从哪个模板复制")
                    .setItems(others.toTypedArray()) { _, which -> pickSkillFrom(others[which]) }
                    .setNegativeButton("取消", null)
                    .create()
                dlg.showStyled()
            } catch (t: Throwable) {
                Log.e(TAG, "skill template picker failed", t)
                fail("导入失败：" + t.message)
            }
        }

        private fun pickSkillFrom(source: String) {
            val skills = try {
                SkillStore.all(source)
            } catch (_: Throwable) {
                emptyList()
            }
            if (skills.isEmpty()) {
                fail("导入失败：模板「" + source + "」下没有 Skill")
                return
            }
            val names = skills.map { it.name }.toTypedArray()
            try {
                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("从「" + source + "」复制一个 Skill（共 " + skills.size + " 个）")
                    .setItems(names) { _, which ->
                        val s = skills[which]
                        report(SkillStore.importFrom(templateName, s.copy(builtIn = false)))
                    }
                    .setNegativeButton("取消", null)
                    .create()
                dlg.showStyled()
            } catch (t: Throwable) {
                Log.e(TAG, "skill picker failed", t)
                fail("导入失败：" + t.message)
            }
        }

        /** Reports the result of an import and refreshes the list. */
        private fun report(res: com.luoliaoo.wechat.data.SkillImportResult) {
            if (res.ok) ok(res.message) else fail(res.message)
        }

        // -------------------------------------------------------------- export

        /**
         * Exports a CHOSEN set of this template's skills as one zip, then opens the system share sheet.
         *
         * item 5: the selection dialog starts with NOTHING selected, so a careless tap cannot export the
         * whole template by accident -- what goes out is always what was ticked.
         */
        private fun exportZip() {
            val all = try {
                SkillStore.all(templateName)
            } catch (t: Throwable) {
                Log.w(TAG, "skill export list failed: " + t.message)
                emptyList()
            }
            if (all.isEmpty()) {
                fail("导出失败：本模板还没有 Skill")
                return
            }

            OwnUi.suppressFor(4000L)
            try {
                val selected = HashSet<String>()
                val d = NukeTheme.dp(ctx, 18)
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d, NukeTheme.dp(ctx, 14), d, NukeTheme.dp(ctx, 10))
                    tag = Constants.TAG_OWN_UI
                }
                box.addView(
                    TextView(ctx).apply {
                        text = "请选择需要导出的 Skill"
                        setTextColor(NukeTheme.TEXT)
                        textSize = NukeTheme.sp(ctx, 17f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        // 玻璃化：标题下方铺一条渐变光带，标题区与表单区之间因此有明度分隔。
                        background = titleRule(ctx)
                    },
                )
                box.addView(
                    NukeTheme.hintText(ctx, "默认一个都不选；选中的会打包成一个 zip 并调起系统分享"),
                )

                val tvErr = TextView(ctx).apply {
                    text = ""
                    setTextColor(NukeTheme.DANGER_TEXT)
                    textSize = NukeTheme.sp(ctx, 12f)
                    setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
                }

                // Every skill is one tappable chip; tapping flips it between chosen and not.
                val chips = ArrayList<TextView>(all.size)
                val holder = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
                }
                for (s in all) {
                    val chip = NukeTheme.toggleChip(ctx, s.name, false).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                        setOnClickListener {
                            if (!selected.remove(s.name)) selected.add(s.name)
                            applyChipState(this, selected.contains(s.name))
                            tvErr.text = "已选 " + selected.size + " 个"
                            tvErr.setTextColor(NukeTheme.TEXT_DIM)
                        }
                    }
                    chips.add(chip)
                    holder.addView(chip)
                }

                val pickRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, NukeTheme.dp(ctx, 10), 0, 0)
                }
                pickRow.addView(
                    NukeTheme.warnOutlineButton(ctx, "全选") {
                        selected.clear()
                        for (s in all) selected.add(s.name)
                        for (c in chips) applyChipState(c, true)
                        tvErr.text = "已选 " + selected.size + " 个"
                        tvErr.setTextColor(NukeTheme.TEXT_DIM)
                    },
                )
                pickRow.addView(
                    NukeTheme.warnOutlineButton(ctx, "全不选") {
                        selected.clear()
                        for (c in chips) applyChipState(c, false)
                        tvErr.text = ""
                    }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0 },
                )
                holder.addView(pickRow)
                holder.addView(tvErr)

                val scroll = ScrollView(ctx).apply {
                    addView(holder)
                    tag = Constants.TAG_OWN_UI
                }
                // A long skill list must not push the buttons off the screen.
                scroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    NukeTheme.dp(ctx, 340),
                )
                box.addView(scroll)

                val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setView(box)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("导出并分享", null)
                    .create()
                dlg.showStyled()
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (selected.isEmpty()) {
                        tvErr.setTextColor(NukeTheme.DANGER_TEXT)
                        tvErr.text = "请至少选择一个 Skill"
                        return@setOnClickListener
                    }
                    val chosen = all.filter { selected.contains(it.name) }
                    dlg.dismiss()
                    doExport(chosen)
                }
                try {
                    dlg.window?.setBackgroundDrawable(
                        glassPanel(ctx, R_DIALOG, NukeTheme.CARD, NukeTheme.CARD_BORDER, 0xEE),
                    )
                } catch (_: Throwable) {
                    // best effort
                }
                OwnUi.register(box, scroll, dlg.window?.decorView)
            } catch (t: Throwable) {
                Log.e(TAG, "skill export picker failed", t)
                fail("导出失败：" + t.message)
            }
        }

        /** Repaints one chip for its current chosen / not-chosen state. */
        private fun applyChipState(chip: TextView, on: Boolean) {
            val base = chip.text.toString().removePrefix("\u2713 ")
            chip.text = (if (on) "\u2713 " else "") + base
            chip.setTextColor(if (on) NukeTheme.TEXT_ON_ACCENT else NukeTheme.TEXT)
            chip.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            chip.background = if (on) {
                NukeTheme.rounded(NukeTheme.ACCENT, 10, ctx)
            } else {
                NukeTheme.rounded(NukeTheme.FEATURE_ALT, 10, ctx, NukeTheme.FEATURE_BORDER)
            }
        }

        /** Builds the zip, keeps a copy where the user can find it, and opens the share sheet. */
        private fun doExport(skills: List<Skill>) {
            val bytes = SkillStore.zipOf(skills)
            if (bytes == null) {
                fail("导出失败：打包失败，请重试")
                return
            }
            val name = "luoliao-skills-" + SkillStore.safeName(templateName) + "-" +
                System.currentTimeMillis() / 1000 + ".zip"
            val uri = publishForSharing(bytes, name)
            if (uri == null) {
                fail("导出失败：无法写入分享文件（存储不可写）")
                return
            }
            shareUri(uri, name, skills.size)
        }

        /**
         * Writes the archive somewhere another app can read it, and returns the URI to hand over.
         *
         * TWO paths, because the manager runs in TWO processes:
         *
         *   1. MediaStore Downloads. This needs NO permission on API 29+, works from WeChat's process, and
         *      leaves the zip in 下载 where the user can find it. This is the path that matters in practice.
         *   2. Our own FileProvider, only usable when we are running as our own app -- inside WeChat the
         *      provider belongs to a different package and could not see WeChat's directory.
         */
        private fun publishForSharing(bytes: ByteArray, name: String): Uri? {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = ctx.contentResolver.insert(collection, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    Log.i(TAG, "skill zip written to Downloads: " + name)
                    return uri
                }
            } catch (t: Throwable) {
                Log.w(TAG, "writing the skill zip to Downloads failed: " + t.message)
            }

            return try {
                if (ctx.packageName != Constants.MODULE_PKG) return null
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                dir.mkdirs()
                val out = File(dir, name)
                out.writeBytes(bytes)
                androidx.core.content.FileProvider.getUriForFile(
                    ctx,
                    Constants.MODULE_PKG + ".fileprovider",
                    out,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "falling back to FileProvider failed: " + t.message)
                null
            }
        }

        /** Opens the SYSTEM share sheet with the archive attached. */
        private fun shareUri(uri: Uri, fileName: String, count: Int) {
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "落聊 Skill（" + templateName + "）")
                    putExtra(Intent.EXTRA_TEXT, "共 " + count + " 个 Skill，用「落聊 → 编辑模板 → Skill 管理 → 导入」接收")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "分享 Skill 压缩包").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(chooser)
                // Also toasted: the card's status line is behind the share sheet, so the outcome would
                // otherwise not be visible until the user comes back.
                toast("已导出 " + count + " 个 Skill")
                ok("已导出 " + count + " 个：" + fileName + "（已存入下载目录，请选择分享目标）")
            } catch (t: Throwable) {
                Log.w(TAG, "opening the share sheet failed: " + t.message)
                fail(
                    "已导出 " + count + " 个到下载目录（" + fileName + "），" +
                        "但无法打开分享：" + (t.message ?: "没有可用的分享应用"),
                )
            }
        }

        // -------------------------------------------------------------- helpers

        private fun sizeLabel(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> (bytes / 1024L).toString() + " KB"
            else -> bytes.toString() + " B"
        }

        /** A short confirmation toast, for the actions whose outcome is not visible in the list itself. */
        private fun toast(msg: String) {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                // no UI thread available
            }
        }
    }
}