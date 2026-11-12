package com.luoliaoo.wechat.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.luoliaoo.wechat.core.Constants
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.core.OwnUi

/**
 * Follow-up input, shown as a dialog.
 *
 * WHY NOT INLINE: an EditText placed inside the card lives in a RecyclerView row that WeChat re-lays
 * out continuously; it lost focus, its text and its IME session, so typed characters never appeared.
 * A dialog on the Activity's own window is immune to all of that.
 *
 * Styled with NukeTheme so it matches the module's other panels.
 */
object FollowUpDialog {

    private const val TAG = "LuoLiao"

    fun show(ctx: Context, prefilled: String = "", onSend: (String) -> Unit) {
        val activity = unwrap(ctx) ?: ctx
        fun dp(v: Int) = NukeTheme.dp(activity, v)

        OwnUi.suppressFor(2000L)

        try {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 液态玻璃面板：一切视觉都走 NukeTheme，宿主主题（微信/抖音/QQ…）各不相同，
                // 这里不读宿主的任何 theme 属性，玻璃面板才能自我完备。
                // 圆角：弹窗主容器 → R_PANEL(24)，用内核具名常量而非手写数字。
                background = NukeTheme.panel(
                    NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, activity,
                )
                setPadding(dp(20), dp(18), dp(20), dp(16))
                tag = Constants.TAG_OWN_UI
            }

            // 标题区改用 NukeTheme 的 eyebrow/title/body 工厂。
            // 原来这里是手写的 TextView + textSize = NukeTheme.sp(ctx, 22f) + Typeface.BOLD：字号写死绕过了自适应字体
            // （NukeTheme.sp），颜色也写死 TEXT。换成工厂后自动跟随内核的明暗双主题与字体缩放。
            root.addView(NukeTheme.eyebrow(activity, "\u843d\u804a \u00b7 \u8ffd\u95ee"))
            root.addView(NukeTheme.title(activity, "\u8ffd\u95ee AI", 22f))
            root.addView(
                NukeTheme.body(
                    activity,
                    "\u4f1a\u7ed3\u5408\u8fd9\u6bb5\u804a\u5929\u8bb0\u5f55\u4e00\u8d77\u56de\u7b54",
                    NukeTheme.TEXT_MUTED,
                    12f,
                ).apply { setPadding(0, dp(6), 0, dp(14)) },
            )

            // 液态玻璃分层：面板 → 玻璃卡 → 控件。
            // 输入区收进一张 NukeTheme.sectionCard（featurePanel 玻璃卡），并在上面加一行 fieldLabel，
            // 与配置面板 / 文本优化面板的分层语汇保持一致，而不是让 EditText 直接贴在大面板上。
            val inputCard = NukeTheme.sectionCard(activity)
            inputCard.addView(NukeTheme.fieldLabel(activity, "\u8ffd\u95ee\u5185\u5bb9"))
            val et = NukeTheme.input(activity, prefilled, "\u60f3\u8ffd\u95ee\u4ec0\u4e48\uff1f\u56de\u8f66\u6362\u884c").apply {
                // 自适应字号：NukeTheme.input 内部已经算过 NukeTheme.sp(14f)，原来又用裸 14f 覆盖掉，
                // 等于在宿主设置「超大字体」时把自适应结果丢掉了。改回 sp 版本。
                textSize = NukeTheme.sp(activity, 14f)
                // 原来是硬编码的微信品牌绿 0xFF07C160。换成语义最近的 token：
                // NukeTheme.OK 是本模块的「肯定/成功」绿，且会随内核的明暗双主题一起重新定义。
                setTextColor(NukeTheme.OK)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                minLines = 3
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
                if (prefilled.isNotBlank()) setSelection(text.length)
            }
            inputCard.addView(et)
            root.addView(inputCard)

            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            val cancel = NukeTheme.secondaryButton(activity, "\u53d6\u6d88") {}.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginEnd = dp(10) }
            }
            val send = NukeTheme.primaryButton(activity, "\u786e\u5b9a\u53d1\u9001") {}.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            buttons.addView(cancel)
            buttons.addView(send)
            root.addView(buttons)

            val dlg = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setView(root)
                .create()

            dlg.setCanceledOnTouchOutside(true)
            dlg.showStyled()

            // The buttons validate before dismissing, so they are wired after show().
            send.setOnClickListener {
                val q = et.text.toString().trim()
                if (q.isEmpty()) {
                    et.error = "\u8bf7\u5148\u8f93\u5165\u5185\u5bb9"
                    return@setOnClickListener
                }
                Log.i(TAG, "follow-up submitted, len=" + q.length)
                try {
                    dlg.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
                onSend(q)
            }
            cancel.setOnClickListener {
                Log.i(TAG, "follow-up cancelled")
                try {
                    dlg.dismiss()
                } catch (_: Throwable) {
                    // already gone
                }
            }

            // Focus the field and raise the keyboard so typing works immediately.
            try {
                et.requestFocus()
                dlg.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                )
                dlg.window?.setBackgroundDrawable(
                    // 圆角：窗口背景与面板同档 → R_PANEL(24)，两者必须一致，
                    // 否则窗口圆角与里面的玻璃面板圆角会错开、露出宿主底色。
                    NukeTheme.panel(
                        NukeTheme.CARD, NukeTheme.CARD_BORDER, NukeTheme.R_PANEL, activity,
                    ),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "styling the follow-up window failed: " + t.message)
            }

            OwnUi.register(root, et, send, cancel, dlg.window?.decorView)
            Log.i(TAG, "follow-up dialog shown")
        } catch (t: Throwable) {
            Log.e(TAG, "follow-up dialog FAILED", t)
            try {
                android.widget.Toast.makeText(
                    ctx,
                    "\u843d\u804a\uff1a\u65e0\u6cd5\u6253\u5f00\u8ffd\u95ee\u8f93\u5165\uff08" + t.javaClass.simpleName + "\uff09",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) {
                // nothing more we can do
            }
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
