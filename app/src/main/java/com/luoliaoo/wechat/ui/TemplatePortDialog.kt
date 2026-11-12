package com.luoliaoo.wechat.ui

import android.app.AlertDialog
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
import com.luoliaoo.wechat.data.SkillStore
import com.luoliaoo.wechat.data.Template
import com.luoliaoo.wechat.data.TemplateStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 模板管理的「导入 / 导出」（第 2 项）。
 *
 * 为什么单独开一个文件：TemplateDialog.kt 同一时间由另一位工程师在改（要在按钮行里接两个新按钮），
 * 两个人在同一个文件里写必然冲突。所以这里把导入 / 导出的**全部实现**收在一个新文件里，
 * 对外只暴露 [showExport] 与 [showImport] 两个入口，TemplateDialog 那边只要把两个按钮的 onClick
 * 指过来就行 —— 写入方只需要知道「函数名 + 参数」，不需要知道 zip 长什么样、文件放哪里。
 *
 * 和 SkillDialog 的 Skill 导入导出是一套做法（那里已经跑通了 FileProvider / MediaStore / 系统选择器
 * 这三条路），这里复用同样的机制、同样的系统组件，只是载体从 Skill 换成 Template。
 * 唯一没有复用的一份代码是 zip 的读写：那边是 `<Skill名>/SKILL.md` 的 markdown 布局，模板这边是
 * 结构化的 JSON，格式不同，硬套只会让两边都变得难读。
 *
 * 依赖：只用 `java.util.zip` + 已有的 org.json，**没有引入任何新依赖**。
 */
object TemplatePortDialog {

    private const val TAG = "LuoLiao"

    // ---------------------------------------------------------------- 液态玻璃尺度（冻结值）
    //
    // 与 TemplateDialog / SkillDialog 同一套节奏：弹窗 24 / 卡片 18 / 控件 12 / chip 10 / 徽标 6。
    // 用具名常量而不是散落的数字字面量，是为了让「圆角失控」在编译期就看得见。
    private const val R_DIALOG = 24
    private const val R_CONTROL = 12
    private const val R_CHIP = 10

    /** 无障碍：最小触控高度 48dp。本文件自绘的控件不走 NukeTheme 的工厂方法，得自己兜住这条。 */
    private const val MIN_TOUCH_DP = 48

    /**
     * 导入压缩包的大小上限。
     *
     * 与 [SkillStore.MAX_ZIP_BYTES] 取同一个值（8 MB）而不是各写各的：两个导入入口对「一个包能有多大」
     * 不该有两个答案。模板包里装的是纯文本 JSON，正常只有几十 KB —— 8 MB 已经是极端宽松的上限，
     * 但它把「有人塞了一个 2 GB 的文件进来」这条路径堵死在读入内存之前。
     */
    private const val MAX_ZIP_BYTES = SkillStore.MAX_ZIP_BYTES

    /**
     * 一次导入的模板数量上限。
     *
     * 理由同 [SkillStore.MAX_IMPORT]：zip 是一个外部文件，条数不该由文件内容说了算。
     * 超过就整体拒绝并告知，不做「悄悄只导入前 N 个」这种事。
     */
    private const val MAX_IMPORT = 200

    /**
     * 导出 zip 里每个模板的存放前缀。
     *
     * 加一层目录而不是把 JSON 平铺在根下，是为了让「这个包是什么」一眼可见：解压出来是一个
     * `templates/` 文件夹，而不是一堆散落的 json。导入侧按 `templates/` 前缀 + `.json` 后缀双重识别，
     * 因此包里混进别的文件（说明、缩略图、macOS 的 `__MACOSX/`）也不会被误读成模板。
     */
    private const val DIR_PREFIX = "templates/"

    /** 导出的压缩包里还会写一份人可读的说明，方便用户（和别人的 AI）看懂这是什么。 */
    private const val README = "README.txt"

    /** 包内的格式版本号。将来格式若要变，靠它判断该怎么读旧包。 */
    private const val FORMAT_VERSION = 1

    // ================================================================== 导出

    /**
     * 【入口 1】导出：弹出勾选面板 → 打包 zip → 调起系统分享。
     *
     * 交互（按第 2 项的要求）：
     *   * 列出**全部**模板；
     *   * 模板**默认全部不勾选** —— 手滑点一下不会把整个配置导出去，导出去的永远是被勾中的那些；
     *   * 单独一个「是否保留 key 密钥」勾选项，**默认不勾选 = 导出时不含 apiKey**。
     *     默认关闭是刻意的：apiKey 是可以直接花钱的凭据，分享 zip 又常常是发给别人 / 发到群里，
     *     默认带上就等于默认泄露。要带 key 必须由用户主动勾一下，界面上也写清楚这一点。
     *
     * @param ctx   任意 Context（模块 UI 跑在宿主进程里，会自己走到 Activity）
     * @param onDone 导出完成（或失败）后回调，交给调用方刷新列表
     */
    fun showExport(ctx: Context, onDone: () -> Unit = {}) {
        OwnUi.suppressFor(4000L)

        val all = try {
            TemplateStore.all()
        } catch (t: Throwable) {
            Log.w(TAG, "template export list failed: " + t.message)
            emptyList()
        }
        if (all.isEmpty()) {
            alert(ctx, "导出", "还没有任何模板，先点「添加」新建一个")
            onDone()
            return
        }

        try {
            // 用「名字集合」而不是索引集合来记录勾选：列表是快照，但按名字判断更抗意外。
            val selected = HashSet<String>()

            val d = NukeTheme.dp(ctx, 18)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d, NukeTheme.dp(ctx, 16), d, NukeTheme.dp(ctx, 10))
                tag = Constants.TAG_OWN_UI
            }

            box.addView(
                TextView(ctx).apply {
                    text = "导出模板"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 17f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 玻璃化：标题下方铺一条渐变光带，标题区与操作区之间因此有明度分隔。
                    background = titleRule(ctx)
                },
            )
            box.addView(NukeTheme.hintText(ctx, "勾选要导出的模板，打包成 zip 后调起系统分享"))

            // ---- 勾选状态提示行（兼错误行）
            val tvMsg = TextView(ctx).apply {
                text = "已选 0 个"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
            }

            val chips = ArrayList<TextView>(all.size)
            val holder = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
            }

            // 刷新「已选 N 个」这一行。所有地方改完勾选都调它，数字和勾选状态就不会各说各话。
            fun sayCount() {
                tvMsg.setTextColor(NukeTheme.TEXT_DIM)
                tvMsg.text = "已选 " + selected.size + " 个"
            }

            for (t in all) {
                val chip = NukeTheme.toggleChip(ctx, t.name, false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                    setOnClickListener {
                        if (!selected.remove(t.name)) selected.add(t.name)
                        applyChipState(this, selected.contains(t.name))
                        sayCount()
                    }
                }
                chips.add(chip)
                holder.addView(chip)
            }

            // 全选 / 全不选：模板可能有十几个，逐个点太烦。默认仍然是不选，这两个只是快捷方式。
            val pickRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, NukeTheme.dp(ctx, 10), 0, 0)
            }
            pickRow.addView(
                NukeTheme.warnOutlineButton(ctx, "全选") {
                    selected.clear()
                    for (t in all) selected.add(t.name)
                    for (c in chips) applyChipState(c, true)
                    sayCount()
                },
            )
            pickRow.addView(
                NukeTheme.warnOutlineButton(ctx, "全不选") {
                    selected.clear()
                    for (c in chips) applyChipState(c, false)
                    sayCount()
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0 },
            )
            holder.addView(pickRow)
            holder.addView(tvMsg)

            val scroll = ScrollView(ctx).apply {
                addView(holder)
                tag = Constants.TAG_OWN_UI
            }
            // 固定高度：模板再多也不会把按钮顶出屏幕。
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NukeTheme.dp(ctx, 320),
            )
            box.addView(scroll)

            // ---- 是否保留 key（独立于模板勾选之外的第二个开关）
            val keepKey = booleanOf(false)
            box.addView(
                switchRow(
                    ctx,
                    "导出时包含 key 密钥",
                    keepKey,
                    "默认不包含。勾选后 zip 里会带上 apiKey —— 请只分享给自己信任的人",
                ),
            )

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出", null)
                .create()
            dlg.showStyled()
            // 自己接管确定按钮，才能在没勾任何模板时只提示、不关窗（关掉再重开很烦）。
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) {
                    tvMsg.setTextColor(NukeTheme.DANGER_TEXT)
                    tvMsg.text = "请至少勾选一个模板"
                    return@setOnClickListener
                }
                // 勾选顺序按 all 的顺序，不按用户点击顺序：导出的包内容对同样的勾选总是同一份。
                val chosen = all.filter { selected.contains(it.name) }
                val withKey = keepKey[0]
                dlg.dismiss()
                doExport(ctx, chosen, withKey, onDone)
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
            Log.e(TAG, "template export picker failed", t)
            alert(ctx, "导出失败", t.message ?: "未知错误")
            onDone()
        }
    }

    /**
     * 真正的导出：打包 → 写到一个「别的 App 读得到」的位置 → 调起系统分享。
     *
     * 打包和分享是两件事，所以拆开：打包失败和分享失败给的是不同的提示，用户能据此判断
     * 是「文件没生成」还是「生成了但没应用能接」。
     */
    private fun doExport(ctx: Context, chosen: List<Template>, withKey: Boolean, onDone: () -> Unit) {
        val bytes = zipOf(chosen, withKey)
        if (bytes == null) {
            alert(ctx, "导出失败", "打包失败，请重试")
            onDone()
            return
        }
        // 文件名带时间戳：连导两次不会互相覆盖，也更方便在下载目录里认出哪一份是新的。
        val name = "luoliao-templates-" + System.currentTimeMillis() / 1000 + ".zip"
        val uri = publishForSharing(ctx, bytes, name)
        if (uri == null) {
            alert(ctx, "导出失败", "无法写入分享文件（存储不可写）")
            onDone()
            return
        }
        shareUri(ctx, uri, name, chosen.size, withKey)
        onDone()
    }

    /**
     * 把 zip 写到另一个 App 能读的位置，返回交给分享的 URI。
     *
     * 两条路，理由与 SkillDialog.publishForSharing 完全一致（那边是同一个问题的既有答案）：
     *
     *   1. **MediaStore 下载目录**。Android 10+ 上不需要任何权限、在宿主的进程里也能用，
     *      而且文件落在「下载」里，用户自己找得到 —— 这是实机上真正走通的那条路。
     *   2. **我们自己的 FileProvider**。它只在「以模块自己 App 的身份运行」时可用：
     *      在微信里，这个 provider 属于另一个包，看不到微信的目录。
     *
     * **必须写在已声明的目录里**，否则 getUriForFile 会直接抛 IllegalArgumentException：
     * authority 是 `${applicationId}.fileprovider`，paths 见 res/xml/luoliao_file_paths.xml，
     * 那份 xml 声明的是 `external-files-path path="."`、`files-path path="."`、`cache-path path="."`
     * —— 三个都是**本 App 自己的目录、且 path 是`.`（整棵子树）**，所以下面用的
     * `getExternalFilesDir(null)`（→ external-files-path）和 `filesDir`（→ files-path）
     * 都已经被覆盖，不需要改那个 xml。
     *
     * 注意第 2 条里 `dir` 只可能是这两个之一，**不会**是 cacheDir：cache 虽然也被 xml 覆盖，
     * 但把要分享给别人的文件放缓存里，系统随时可以清掉。
     */
    private fun publishForSharing(ctx: Context, bytes: ByteArray, name: String): Uri? {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                // IS_PENDING 归零之前，别的 App 是看不到这个条目的；写完了再放行。
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                Log.i(TAG, "template zip written to Downloads: " + name)
                return uri
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writing the template zip to Downloads failed: " + t.message)
        }

        return try {
            // 不是我们自己 App 的进程时，我们声明的 authority 不存在于此进程所属的包里，直接放弃。
            if (ctx.packageName != Constants.MODULE_PKG) return null
            // getExternalFilesDir 可能为 null（外部存储不可用），回落到内部 filesDir —— 两者都在 xml 里。
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

    /** 打开**系统**分享面板，把压缩包挂上去。 */
    private fun shareUri(ctx: Context, uri: Uri, fileName: String, count: Int, withKey: Boolean) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "落聊模板（" + count + " 个）")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "共 " + count + " 个模板" + if (withKey) "（含 key 密钥）" else "（不含 key）" +
                        "，用「落聊 → 模板管理 → 导入」接收",
                )
                // 没有这个标志，接收方拿到的 URI 没有读权限，打开就是 SecurityException。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享模板压缩包").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 宿主进程里我们不一定有 foreground Activity，NEW_TASK 才是稳的那一下。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            Log.i(TAG, "template zip shared: " + fileName + " count=" + count + " withKey=" + withKey)
            // 分享面板一出来就盖住了模块自己的界面，所以另给一条 toast，用户才知道确实导出成功了。
            toast(ctx, "已导出 " + count + " 个模板")
        } catch (t: Throwable) {
            Log.w(TAG, "opening the share sheet failed: " + t.message)
            alert(
                ctx,
                "已导出但无法分享",
                "压缩包已保存为 " + fileName + "（在下载目录里）。\n" +
                    "打开分享面板失败：" + (t.message ?: "没有可用的分享应用"),
            )
        }
    }

    // ================================================================== 导入

    /**
     * 【入口 2】导入：打开系统文件选择器 → 预览 → 勾选 → 写入。
     *
     * 文件选择器用的是项目里已有的 [FilePicker]（模块 UI 跑在宿主进程里、没有自己的 Activity，
     * `onActivityResult` 无处可覆写，所以它 hook 了宿主 Activity 的结果方法），
     * **不是**自己再写一套 —— 那套 hook 的逻辑很细，第二份实现只会多一个出错的地方。
     *
     * 交互与导出**保持一致**：预览列表里默认一个都不勾选，点「导入」才写。
     *
     * 重名模板的处理（明确写死在这里，UI 上也会显示）：
     * **跳过不覆盖**。跳过的模板列在结果里告诉用户有几个、分别叫什么。
     * 为什么选跳过而不是覆盖：导入是把别人的模板拿过来，而本地同名的那份可能是用户自己改了很久的
     * （改了提示词、填了自己的 key）。覆盖会**静默销毁**这份改动，而且不可撤销 —— 用户点下去之前
     * 无法知道「同名」意味着什么。跳过最坏也只是「没导进来」，用户想用新版，把本地那个删掉再导一次
     * 就行了，代价小得多，而且是可逆的。
     * 顺带一条：`builtIn` 标志在导入时一律置 false（见下面的 `sanitize`）—— 外来模板不能在本地
     * 被当成自带模板，否则「恢复默认」的语义会被外来数据污染。
     *
     * @param ctx   任意 Context
     * @param onDone 导入完成（含失败 / 取消）后回调，交给调用方刷新列表
     */
    fun showImport(ctx: Context, onDone: () -> Unit = {}) {
        OwnUi.suppressFor(4000L)
        try {
            FilePicker.pick(ctx) { result ->
                when (result) {
                    is FilePicker.Picked -> {
                        // 大小在这里再挡一次：FilePicker 自己也有上限，但两个入口的界限必须一致。
                        if (result.bytes.size > MAX_ZIP_BYTES) {
                            alert(ctx, "导入失败", "压缩包过大（上限 " + mb(MAX_ZIP_BYTES) + "）")
                            onDone()
                            return@pick
                        }
                        val incoming = try {
                            templatesFromZip(result.bytes)
                        } catch (t: Throwable) {
                            Log.w(TAG, "reading the template zip failed: " + t.message)
                            Incoming(
                                false,
                                "导入失败：压缩包无法读取（" + (t.message ?: "不是有效的 zip") + "）",
                                emptyList(),
                                false,
                            )
                        }
                        if (!incoming.ok) {
                            alert(ctx, "导入失败", incoming.message)
                            onDone()
                        } else {
                            previewAndImport(ctx, result.name, incoming, onDone)
                        }
                    }
                    is FilePicker.Failure -> {
                        // 用户点了取消是正常操作，不是错误，安静收场。
                        if (result.message == "已取消") {
                            Log.i(TAG, "template import: pick cancelled")
                        } else {
                            alert(ctx, "导入失败", result.message)
                        }
                        onDone()
                    }
                    else -> {
                        alert(ctx, "导入失败", "没有取到文件")
                        onDone()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "template import failed", t)
            alert(ctx, "导入失败", t.message ?: "未知错误")
            onDone()
        }
    }

    /**
     * 预览 + 勾选 + 写入。
     *
     * 「先预览再勾选」不是多余的确认步骤：zip 是外部文件，用户在点它之前并不知道里面有几个模板、
     * 分别叫什么。把名字先列出来，用户才知道自己在导入什么；默认全不勾选，则保证「多导了一个」
     * 这种事不会因为手滑而发生。
     */
    private fun previewAndImport(
        ctx: Context,
        fileName: String,
        incoming: Incoming,
        onDone: () -> Unit,
    ) {
        OwnUi.suppressFor(4000L)
        try {
            val all = incoming.templates
            val selected = HashSet<String>()

            val d = NukeTheme.dp(ctx, 18)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d, NukeTheme.dp(ctx, 16), d, NukeTheme.dp(ctx, 10))
                tag = Constants.TAG_OWN_UI
            }
            box.addView(
                TextView(ctx).apply {
                    text = "导入模板"
                    setTextColor(NukeTheme.TEXT)
                    textSize = NukeTheme.sp(ctx, 17f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    // 玻璃化：标题下方铺一条渐变光带，标题区与操作区之间因此有明度分隔。
                    background = titleRule(ctx)
                },
            )
            box.addView(
                NukeTheme.hintText(
                    ctx,
                    "来源：" + fileName + " · 共 " + all.size + " 个模板" +
                        if (incoming.hasKey) "（含 key 密钥）" else "（不含 key）",
                ),
            )
            // 重名规则写在界面上，而不是只写在代码注释里 —— 用户点「导入」之前就该知道会发生什么。
            box.addView(
                NukeTheme.hintText(ctx, "与本地同名的模板会【跳过】，不会覆盖；列表默认都不勾选"),
            )

            val tvMsg = TextView(ctx).apply {
                text = "已选 0 个"
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 12f)
                setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
            }

            fun sayCount() {
                tvMsg.setTextColor(NukeTheme.TEXT_DIM)
                tvMsg.text = "已选 " + selected.size + " 个"
            }

            val chips = ArrayList<TextView>(all.size)
            val holder = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, NukeTheme.dp(ctx, 8), 0, 0)
            }
            for (t in all) {
                // 名字后面缀「（本地已有，将跳过）」：同名的那几个在预览时就能看出来，
                // 不必等导入结束才知道自己白勾了一个。
                val exists = try {
                    TemplateStore.byName(t.name) != null
                } catch (_: Throwable) {
                    false
                }
                val label = if (exists) t.name + "（本地已有，将跳过）" else t.name
                val chip = NukeTheme.toggleChip(ctx, label, false).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = NukeTheme.dp(ctx, 6) }
                    setOnClickListener {
                        if (!selected.remove(t.name)) selected.add(t.name)
                        applyChipState(this, selected.contains(t.name))
                        sayCount()
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
                    for (t in all) selected.add(t.name)
                    for (c in chips) applyChipState(c, true)
                    sayCount()
                },
            )
            pickRow.addView(
                NukeTheme.warnOutlineButton(ctx, "全不选") {
                    selected.clear()
                    for (c in chips) applyChipState(c, false)
                    sayCount()
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0 },
            )
            holder.addView(pickRow)
            holder.addView(tvMsg)

            val scroll = ScrollView(ctx).apply {
                addView(holder)
                tag = Constants.TAG_OWN_UI
            }
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                NukeTheme.dp(ctx, 340),
            )
            box.addView(scroll)

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", null)
                .create()
            dlg.showStyled()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) {
                    tvMsg.setTextColor(NukeTheme.DANGER_TEXT)
                    tvMsg.text = "请至少勾选一个模板"
                    return@setOnClickListener
                }
                val chosen = all.filter { selected.contains(it.name) }
                dlg.dismiss()
                importChosen(ctx, chosen, onDone)
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
            Log.e(TAG, "template import preview failed", t)
            alert(ctx, "导入失败", t.message ?: "未知错误")
            onDone()
        }
    }

    /** 写入 TemplateStore：同名的跳过并计数，最后把结果整份告诉用户。 */
    private fun importChosen(ctx: Context, chosen: List<Template>, onDone: () -> Unit) {
        try {
            val added = ArrayList<String>(chosen.size)
            val skipped = ArrayList<String>(chosen.size)
            for (t in chosen) {
                if (TemplateStore.byName(t.name) != null) {
                    // 重名 → 跳过。理由见 showImport 的注释：覆盖会不可逆地销毁用户自己的改动。
                    skipped.add(t.name)
                    continue
                }
                TemplateStore.add(t)
                added.add(t.name)
            }
            val msg = buildString {
                if (added.isEmpty()) {
                    append("没有导入任何模板：")
                    append(skipped.size).append(" 个都与本地重名，已全部跳过")
                } else {
                    append("导入成功：新增 ").append(added.size).append(" 个（")
                    append(added.joinToString("、")).append("）")
                    if (skipped.isNotEmpty()) {
                        append("；跳过同名 ").append(skipped.size).append(" 个：")
                        append(skipped.joinToString("、"))
                    }
                }
            }
            Log.i(
                TAG,
                "templates imported: added=" + added.size + " skipped=" + skipped.size +
                    " (" + added.joinToString("/") + " | " + skipped.joinToString("/") + ")",
            )
            toast(ctx, msg)
        } catch (t: Throwable) {
            Log.w(TAG, "importing templates failed: " + t.message)
            alert(ctx, "导入失败", t.message ?: "写入失败")
        }
        // 无论成功与否都回调：调用方只需要「重新读一遍 store 并重画」，不需要区分成败。
        onDone()
    }

    // ================================================================== zip 读写（标准 java.util.zip）

    /**
     * 打包。
     *
     * 布局：`templates/<安全名>.json`（每个模板一个文件），外加根目录一个 `README.txt`。
     *
     * 为什么是「每个模板一个 JSON」而不是一个大数组：和 TemplateStore 在磁盘上的布局
     * （`templates/<name>.json` + `_order.txt`）一一对应。这样包的结构和存储的结构是同一回事，
     * 用户解压出来就能直接看懂、甚至手工丢进目录里；反过来，将来若要让「直接导入一个文件夹」也能用，
     * 读的仍然是同一套规则。
     *
     * @param withKey false 时写入的 apiKey 是空串（默认），true 时才带上真实密钥。
     * @return 压缩包字节；失败返回 null。
     */
    private fun zipOf(templates: List<Template>, withKey: Boolean): ByteArray? {
        if (templates.isEmpty()) return null
        return try {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val used = HashSet<String>()
                for (t in templates) {
                    // 两个模板不可能共用一个文件名（同名模板在 store 里就不允许共存），
                    // 但安全名可能把不同名字压成同一个（"a/b" 和 "a:b" 都变成 "a_b"），
                    // 所以仍然加序号后缀兜住，否则第二个会把第一个覆盖掉 —— 静默丢数据。
                    var base = SkillStore.safeName(t.name, "template")
                    var n = 2
                    while (used.contains(base)) {
                        base = SkillStore.safeName(t.name, "template") + "-" + n
                        n++
                    }
                    used.add(base)

                    val entry = ZipEntry(DIR_PREFIX + base + ".json")
                    // 固定时间戳：同样内容的包每次都得到同样的字节，便于比对「这次导的和我上次的是否一样」。
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    zip.write(templateJson(t, withKey).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                val readme = ZipEntry(README)
                readme.time = 0L
                zip.putNextEntry(readme)
                zip.write(readmeText(templates.size, withKey).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "building the template zip failed: " + t.message)
            null
        }
    }

    /** 一个模板的 JSON。**withKey 为 false 时写空串**，不是省略字段 —— 两种写法读侧等价，但空串更直白。 */
    private fun templateJson(t: Template, withKey: Boolean): JSONObject = JSONObject().apply {
        put("name", t.name)
        put("baseUrl", t.baseUrl)
        put("apiKey", if (withKey) t.apiKey else "")
        put("model", t.model)
        put("systemPrompt", t.systemPrompt)
        put("defaultUp", t.defaultUp)
        put("defaultDown", t.defaultDown)
        put("defaultRangeHours", t.defaultRangeHours)
        put("blockWords", JSONArray().apply { t.blockWords.forEach { put(it) } })
        put("builtIn", t.builtIn)
    }

    private fun readmeText(count: Int, withKey: Boolean): String = buildString {
        append("落聊 · 模板导出包\n")
        append("格式版本：").append(FORMAT_VERSION).append('\n')
        append("模板数量：").append(count).append('\n')
        append("是否包含 key 密钥：").append(if (withKey) "是" else "否").append('\n')
        append('\n')
        append("包内每个模板是 ").append(DIR_PREFIX).append("<模板名>.json 一个文件，\n")
        append("内容为 UTF-8 JSON，可直接阅读和编辑。\n")
        append("导入方式：落聊 → 模板管理 → 导入，选中本压缩包即可。\n")
        append("注意：与本地同名的模板会被跳过，不会覆盖。\n")
    }

    /** 解析结果。`ok=false` 时 [message] 是给用户看的原因。 */
    private class Incoming(
        val ok: Boolean,
        val message: String,
        val templates: List<Template>,
        /** 包里是否含非空 apiKey，用来在预览里提醒用户。 */
        val hasKey: Boolean,
    )

    /**
     * 读包。
     *
     * 检查顺序刻意是「先全部解析完，再返回」：**一个字节都不写**。所以一个坏包永远不可能让本地
     * 模板变成半旧半新的状态 —— 要么整体可用，要么什么都没发生。
     *
     * 严格一点的地方：
     *   * 只认 `templates/` 前缀 + `.json` 后缀，包里混进 `__MACOSX/`、说明文件、图片都不会被误读；
     *   * 带 `..` 或超长名字的条目直接忽略（防路径穿越 / 内存炸弹），目录条目跳过；
     *   * 名字为空的模板跳过（空名字在 store 里会被 add() 直接丢掉，收进来只会让「导入 N 个」对不上）。
     */
    private fun templatesFromZip(bytes: ByteArray): Incoming {
        if (bytes.isEmpty()) return Incoming(false, "导入失败：文件为空", emptyList(), false)
        if (bytes.size > MAX_ZIP_BYTES) {
            return Incoming(false, "导入失败：压缩包过大（上限 " + mb(MAX_ZIP_BYTES) + "）", emptyList(), false)
        }

        val out = ArrayList<Template>()
        var hasKey = false
        var ignored = 0

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                var guard = 0
                while (entry != null && guard < 4000) {
                    guard++
                    val rawName = entry.name.replace('\\', '/')
                    if (entry.isDirectory) {
                        entry = zip.nextEntry
                        continue
                    }
                    if (!rawName.startsWith(DIR_PREFIX) || !rawName.lowercase().endsWith(".json")) {
                        // README、目录条目、macOS 的额外文件都从这里出去。
                        ignored++
                        entry = zip.nextEntry
                        continue
                    }
                    // 防穿越：条目名里带 ".." 的一律不要；超长名字同样丢弃。
                    if (rawName.contains("..") || rawName.length > 300) {
                        ignored++
                        entry = zip.nextEntry
                        continue
                    }
                    val text = readEntry(zip)
                    val t = parseTemplate(text, rawName)
                    if (t == null) {
                        ignored++
                    } else {
                        if (t.apiKey.isNotBlank()) hasKey = true
                        out.add(t)
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            return Incoming(
                false,
                "导入失败：压缩包无法读取（" + (t.message ?: "不是有效的 zip") + "）",
                emptyList(),
                false,
            )
        }

        if (out.isEmpty()) {
            return Incoming(
                false,
                "导入失败：压缩包里没有找到模板" +
                    "（需要 " + DIR_PREFIX + "<模板名>.json）" +
                    if (ignored > 0) "；已忽略 $ignored 个无关文件" else "",
                emptyList(),
                false,
            )
        }
        if (out.size > MAX_IMPORT) {
            return Incoming(
                false,
                "导入失败：一次最多导入 " + MAX_IMPORT + " 个模板（这个包里有 " + out.size + " 个）",
                emptyList(),
                false,
            )
        }

        // 包内自己也可能有两个同名（从两个来源拼出来的包），去重保留第一个，避免 UI 上出现两个
        // 一模一样的条目、用户勾两次却只进来一个。
        val seen = HashSet<String>()
        val deduped = ArrayList<Template>(out.size)
        for (t in out) {
            if (seen.add(t.name)) deduped.add(t)
        }

        Log.i(TAG, "template zip parsed: " + deduped.size + " template(s), ignored " + ignored)
        return Incoming(true, "", deduped, hasKey)
    }

    /**
     * 一条 JSON → 一个模板；无法使用时返回 null。
     *
     * 容错的地方：字段缺失按默认值走（旧包、手工编辑过的包都还能读）；
     * 严格的地方：**name 不能为空**（没有名字的模板在 UI 里既不能显示也不能选中），
     * 以及 **builtIn 一律置 false** —— 外来模板不许在本地冒充自带模板，否则「恢复默认」的
     * 语义会被外来数据污染（自带模板的提示词会被 migrateBuiltIns 改写）。
     */
    private fun parseTemplate(text: String, rawName: String): Template? = try {
        val o = JSONObject(text)
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) {
            // 名字缺失时退回文件名（去掉 templates/ 前缀和 .json 后缀），仍然不行才算无效。
            val fromFile = rawName.removePrefix(DIR_PREFIX).removeSuffix(".json").trim()
            if (fromFile.isEmpty()) {
                null
            } else {
                buildTemplate(o, fromFile)
            }
        } else {
            buildTemplate(o, name)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "template entry unreadable: " + rawName + " (" + t.message + ")")
        null
    }

    private fun buildTemplate(o: JSONObject, name: String): Template {
        val words = ArrayList<String>()
        // blockWords 缺失（旧包）→ 空列表，读侧会回落到 DEFAULT_BLOCK_WORDS，与 store 的既有行为一致。
        o.optJSONArray("blockWords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.optString(i, "").trim()
                if (w.isNotEmpty()) words.add(w)
            }
        }
        return Template(
            name = name,
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            systemPrompt = o.optString("systemPrompt", ""),
            defaultUp = o.optInt("defaultUp", 3),
            defaultDown = o.optInt("defaultDown", 3),
            defaultRangeHours = o.optInt("defaultRangeHours", 24),
            blockWords = words,
            // 见函数头：外来模板永远不是自带的。
            builtIn = false,
        )
    }

    /**
     * 读一条 zip 条目的文本。
     *
     * 每个条目都有上限（这里按 1 MB 字节算）：一个压缩包可以在几 KB 的体积里声明一条解压后几个 GB
     * 的条目（zip bomb），读之前不设限就会把进程撑爆。模板 JSON 是纯文本，1 MB 已经远超正常体积。
     */
    private fun readEntry(zip: ZipInputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        val cap = 1024 * 1024
        while (true) {
            val n = zip.read(chunk)
            if (n <= 0) break
            total += n
            if (total > cap) break
            buf.write(chunk, 0, n)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    // ================================================================== 共用小件

    /**
     * 一个可变的布尔开关，用来让 lambda 里能读写它。
     *
     * 用 `BooleanArray(1)` 而不是 `var`：Kotlin 的闭包捕获的是变量的引用，但把它穿进
     * 好几个回调（chip 的点击、确定按钮）之后，一个显式的容器比一个被多处读写的局部 var 更好读。
     */
    private fun booleanOf(v: Boolean) = booleanArrayOf(v)

    /** 一行「文字 + 说明 + Switch」。导出面板里的「是否保留 key」就是它。 */
    private fun switchRow(
        ctx: Context,
        label: String,
        holder: BooleanArray,
        hint: String,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, NukeTheme.dp(ctx, 12), 0, 0)
            tag = Constants.TAG_OWN_UI
        }
        val textBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(NukeTheme.TEXT)
                textSize = NukeTheme.sp(ctx, 13f)
            },
        )
        textBox.addView(
            TextView(ctx).apply {
                text = hint
                setTextColor(NukeTheme.TEXT_DIM)
                textSize = NukeTheme.sp(ctx, 11f)
            },
        )
        row.addView(
            textBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            android.widget.Switch(ctx).apply {
                isChecked = holder[0]
                // 自带 UI 标记：宿主菜单的 hook 靠这个标签避免劫持我们自己的控件。
                tag = Constants.TAG_OWN_UI
                setOnCheckedChangeListener { _, checked -> holder[0] = checked }
            },
        )
        return row
    }

    /**
     * 重画一个 chip 的选中态。
     *
     * 和 SkillDialog 里的同名函数是同一个做法：`NukeTheme.toggleChip` 只在**创建时**按 selected 画一次，
     * 之后要靠调用方自己重画。这里把「前缀 ✓ / 文字色 / 字重 / 背景」四件事一起改，
     * 少改一样就会出现「勾上了但看不出来」。
     */
    private fun applyChipState(chip: TextView, on: Boolean) {
        val base = chip.text.toString().removePrefix("\u2713 ")
        chip.text = (if (on) "\u2713 " else "") + base
        chip.setTextColor(if (on) NukeTheme.TEXT_ON_ACCENT else NukeTheme.TEXT)
        chip.typeface = if (on) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }
        chip.background = if (on) {
            NukeTheme.rounded(NukeTheme.ACCENT, R_CHIP, chip.context)
        } else {
            NukeTheme.rounded(NukeTheme.FEATURE_ALT, R_CHIP, chip.context, NukeTheme.FEATURE_BORDER)
        }
        // 无障碍：没走工厂方法的自绘控件自己保证 48dp 触控高度（背景被换掉时不会丢，这里只是兜底）。
        chip.minimumHeight = NukeTheme.dp(chip.context, MIN_TOUCH_DP)
    }

    /** 标题下方的渐变光带 —— 与 TemplateDialog / SkillDialog 一致的玻璃语言，本文件自己实现一份。 */
    private fun titleRule(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            android.graphics.Color.TRANSPARENT,
            // 只给现有 token 换一层 alpha，不硬编码颜色：明暗主题切换时会自动跟着变。
            (NukeTheme.ACCENT and 0x00FFFFFF) or (0x1F shl 24),
        ),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, R_CONTROL).toFloat()
    }

    /**
     * 玻璃面板 —— 本文件私有实现，不写进 NukeTheme.kt（主题内核正在同步改造那个文件）。
     *
     * 与 TemplateDialog.glassPanel 同一套三层结构：渐变的受光面 + 半透明主体 + 1dp 边缘光。
     */
    private fun glassPanel(
        ctx: Context,
        radiusDp: Int = R_DIALOG,
        fill: Int = NukeTheme.CARD,
        border: Int = NukeTheme.CARD_BORDER,
        fillAlpha: Int = 0xEE,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(veil(border, 0x99), veil(fill, fillAlpha), veil(fill, fillAlpha)),
    ).apply {
        cornerRadius = NukeTheme.dp(ctx, radiusDp).toFloat()
        setStroke(NukeTheme.dp(ctx, 1), border)
    }

    /** 只给令牌换一层 alpha（rgb 取自传入的 token）。 */
    private fun veil(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** 一个只说明结果的弹窗。导入 / 导出的失败都走这里，避免用户只看到界面闪一下就没了。 */
    private fun alert(ctx: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create()
                .showStyled()
        } catch (t: Throwable) {
            // 弹窗都开不出来时至少留一条 toast，不能什么都不说。
            Log.w(TAG, "alert failed: " + t.message)
            toast(ctx, title + "：" + message)
        }
    }

    /** 一条短提示。结果会显示在分享面板后面，所以必须另有 toast。 */
    private fun toast(ctx: Context, msg: String) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // 没有 UI 线程
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
