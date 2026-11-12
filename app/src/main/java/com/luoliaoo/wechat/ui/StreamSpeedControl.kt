package com.luoliaoo.wechat.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.data.ConfigRepo
import com.luoliaoo.wechat.data.StreamSpeed

/**
 * 「追问流式输出速度」输入框 —— 全站唯一一份实现。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 【为什么从滑杆改成输入框（用户要求）】
 * 滑杆在这个场景下有三个实际问题：
 *   ① 取值范围只有 0..20 共 21 档，手指很难精确停在某个值上，
 *      想设 7 经常滑成 6 或 8，只能反复微调；
 *   ② 滑杆的"拖动中不落盘"防抖与"松手才保存"在窄范围下反而让人不确定到底存了没有；
 *   ③ 用户要的就是一个**确定的数字**，输入框能一次给准。
 *
 * 【两处界面共用】原先只有模块设置页有这个控件，但用户要的位置是
 * **模板管理界面**里「清空所有记录」下面 —— 两个界面都有同一个按钮，所以"放在它下面"
 * 这句话本身有二义性。抽成共用组件后，两处挂载同一份实现，不会再出现
 * "改了一处、另一处行为不一致"。
 *
 * 【落盘必须走 saveLocalAndMirror】
 * 这个值在**宿主进程**（微信里的 `AnalysisController`）被读取，而界面运行在**模块进程**，
 * 两个进程只有 `config-mirror.properties` 这一条通路。只写本地 SharedPreferences 的话，
 * 界面看着变了、追问速度却一点不变 —— 那是最难查的一类"改了没反应"。
 */
object StreamSpeedControl {

    private const val TAG = "LuoLiao"

    /**
     * 把输入框挂到 [parent] 上。
     *
     * @param parent 目标卡片容器。调用方负责决定它在界面里的位置（本组件只管自己那一段）。
     * @param topPad 与上一段内容的间距（dp）。不同界面的疏密不同，所以由调用方给。
     */
    fun attach(parent: LinearLayout, topPad: Int = 18) {
        val ctx = parent.context

        // ---- 标题 + 说明
        parent.addView(
            NukeTheme.body(ctx, "追问流式输出速度", NukeTheme.TEXT, 14f)
                .apply { setPadding(0, dp(ctx, topPad), 0, 0) },
        )
        parent.addView(
            NukeTheme.body(
                ctx,
                "单位：字/秒。数字越大越慢，0 = 不限速（直接显示）。无上限，默认 " +
                    StreamSpeed.DEFAULT + "。",
                NukeTheme.TEXT_DIM,
                12f,
            ).apply { setPadding(0, dp(ctx, 6), 0, dp(ctx, 8)) },
        )

        // ---- 输入框。
        // 复用 NukeTheme.input（本模块统一的输入框外观与无障碍对比度修正都在那里），
        // 而不是自己 new 一个 EditText 再手工配色 —— 那样两处的观感迟早会漂移。
        val cur = try {
            ConfigRepo.loadLocal(ctx).streamSpeed
        } catch (_: Throwable) {
            StreamSpeed.DEFAULT
        }
        val field = NukeTheme.input(ctx, StreamSpeed.clamp(cur).toString(), StreamSpeed.DEFAULT.toString()).apply {
            // 只要整数：范围小、语义明确，允许小数点或负号只会带来无意义的校验分支。
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = NukeTheme.sp(ctx, 16f)
            gravity = Gravity.CENTER
            setSelection(text.length)
        }
        parent.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ---- 结果提示行（成功/失败都用它，避免用 Toast 打断输入）
        val status = TextView(ctx).apply {
            textSize = NukeTheme.sp(ctx, 12f)
            setPadding(0, dp(ctx, 6), 0, 0)
            setTextColor(NukeTheme.TEXT_DIM)
        }
        parent.addView(status)

        /* 输入即保存（用户要求"输入修改后立即自动保存并生效"）。
           ─────────────────────────────────────────────────────────────────────
           【为什么不在 afterTextChanged 里直接落盘】
           用户输入"15"时，键盘会先产生 "1" 再产生 "15"。如果每敲一个字符都落盘，
           中间那个 "1" 会被当成用户想要的值存下去（那意味着 1 字/秒，极慢），
           然后才被 "15" 覆盖 —— 虽然最终值是对的，但期间**宿主进程读到过错误的值**，
           正在进行的追问会真的按 1 字/秒去跑。
           所以做成"短延时合并"：连续输入期间只保留最后一次，停下来 400ms 才落盘。
           对用户来说仍然是"改完就自动生效"，不需要按任何按钮。
           而**合法的即时值会被立即反映**在提示行上，所以操作反馈是即时的。 */
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val apply = Runnable {
            val raw = field.text?.toString().orEmpty().trim()
            if (raw.isEmpty()) {
                // 空输入不是"0"：用户多半是清空了准备重打，此时不动配置，等他打完。
                status.setTextColor(NukeTheme.TEXT_DIM)
                status.text = "请输入一个整数（0 = 不限速）"
                return@Runnable
            }
            /* 用 StreamSpeed.parse 而不是 toIntOrNull：
               上限已取消，用户完全可能打出超过 Int 的大数。若用 toIntOrNull，
               那种输入会返回 null，界面只能报「只能输入数字」——
               但用户输入的确实是数字，这个提示会让人一头雾水。
               parse 按 Long 解析后夹到上界，语义是"你要的最快，我就给你最快"。 */
            val parsed = StreamSpeed.parse(raw)
            if (parsed == null) {
                status.setTextColor(NukeTheme.DANGER_TEXT)
                status.text = "只能输入整数"
                return@Runnable
            }
            persist(ctx, parsed)
            val isCapped = raw.toLongOrNull()?.let { it > StreamSpeed.MAX.toLong() } == true
            if (parsed == StreamSpeed.OFF) {
                status.setTextColor(NukeTheme.OK)
                status.text = "已保存：不限速（直接显示）"
            } else if (isCapped) {
                field.setText(parsed.toString())
                field.setSelection(field.text.length)
                status.setTextColor(NukeTheme.DANGER_TEXT)
                status.text = "超出可用范围，已改为 $parsed"
            } else {
                status.setTextColor(NukeTheme.OK)
                status.text = "已保存：$parsed 字/秒"
            }
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(apply)
                handler.postDelayed(apply, 400L)
            }
        })

        // 输入框失焦时立即结算，避免用户填完后马上切页、400ms 的延时被吞掉。
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                handler.removeCallbacks(apply)
                handler.post(apply)
            }
        }

        // 初始提示：直接把当前生效值说出来，用户一眼就知道现在的状态。
        status.text = try {
            val cur = StreamSpeed.clamp(ConfigRepo.loadLocal(ctx).streamSpeed)
            if (cur == StreamSpeed.OFF) "当前：不限速（直接显示）" else "当前：$cur 字/秒"
        } catch (_: Throwable) {
            ""
        }
    }

    /** 落盘：本地 + 跨进程镜像，两边都写（见本文件的说明）。 */
    private fun persist(ctx: android.content.Context, value: Int) {
        try {
            val v = StreamSpeed.clamp(value)
            val cfg = ConfigRepo.loadLocal(ctx)
            // 值没变就不写：避免每次输入都产生一次无意义的镜像写入。
            if (cfg.streamSpeed == v) return
            ConfigRepo.saveLocalAndMirror(ctx, cfg.copy(streamSpeed = v))
        } catch (t: Throwable) {
            Log.w(TAG, "saveStreamSpeed failed: " + t.message)
        }
    }

    private fun dp(ctx: android.content.Context, v: Int): Int = NukeTheme.dp(ctx, v)
}
