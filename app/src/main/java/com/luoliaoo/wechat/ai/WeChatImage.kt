package com.luoliaoo.wechat.ai

import android.content.Context
import com.luoliaoo.wechat.core.Log
import java.io.File

/**
 * ★ v10.3.0：从微信本地存储里读出图片字节，交给 AI 做多模态分析。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【本文件的结论全部来自真机实测，不是推测 —— 实测命令与结果如下】
 *
 * 设备：OnePlus PLK110，微信账号目录 `MicroMsg/86770b3a141f3e4f065b02284b8268d3`。
 *
 * ① **图片目录结构**（`ls` 实测）
 * ```
 * image2/00/9d/th_009d9b80134824969d6e5a250d7df0f7
 * image2/00/9d/th_009d9b80134824969d6e5a250d7df0f7hd_tmp.jpg
 * image2/00/3c/1c2f636c2a956b5791af8a62b7afbd06_backup
 * ```
 * 即 `image2/<名字前2位十六进制>/<第3-4位>/<文件>`。
 *
 * ② **这些文件是明文**（`xxd` 实测文件头）
 * ```
 * th_0100075d64a1ecd87868ac8e03cf9171_tmp.jpg  -> ffd8 ffe0 0010 4a46   (JPEG/JFIF)
 * 756ee08a35e9f9430cb21564d2cc34bc_backup      -> 8950 4e47 0d0a 1a0a   (PNG)
 * ```
 * 是标准 JPEG / PNG 头，**不是密文**。所以模块（以微信进程身份运行，能读微信私有目录）
 * 可以直接把字节取出来发给模型。
 *
 * ③ **⛔ 扩展名完全不可信，判断格式必须靠文件头**
 * ```
 * 0101c494a788a26ecd87e41aa2f3ac1f.jpg  -> 7778 6766 1300 0204   ("wxgf")
 * 0500bb00303d815aec251f15df81c7fa.png  -> 7778 6766 1300 0201   ("wxgf")
 * ```
 * 后缀写着 .jpg / .png，实际是微信自有的 `wxgf` 容器格式。
 * 若按扩展名把 MIME 报成 image/jpeg 发给服务端，服务端解码失败会让**整个请求**报错，
 * 用户看到的是"分析失败"，而不是"这张图不支持"。所以下面一律**嗅探文件头**。
 *
 * ④ **表情包不能走这条路**（`xxd` 实测 1835 个文件）
 * `MicroMsg/<账号>/emoji/` 下所有文件的头都是高熵随机字节
 * （`424db6a7`、`af52c2df`、`fb23e1a6` …），**没有一个是标准图片头** —— 它们是加密的。
 * 所以本文件只负责**图片**；表情包需要另一条路（见 [ImageEncoder] 的说明）。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object WeChatImage {

    private const val TAG = "LuoLiao"

    /** 单张图的上限。超过就不发，避免请求体过大被服务端拒绝或慢到超时。 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * 一次最多带几张图。
     *
     * 上限 4 是**成本与延迟的折中**：多模态请求按图片张数计费与排队，
     * 一次塞十几张会让用户等很久、花费也高，而分析一段对话通常看前几张就够。
     */
    const val MAX_IMAGES = 4

    /**
     * 已识别格式的图片字节。
     *
     * @param mime  嗅探出来的真实 MIME（**不是**按扩展名猜的）。
     * @param bytes 原始字节。
     */
    data class Raw(val mime: String, val bytes: ByteArray)

    /**
     * 按微信的 `imgPath` 字段定位并读出图片。
     *
     * @param ctx     宿主进程的 Context（用 `filesDir.parentFile` 反推微信私有目录）。
     * @param imgPath 消息的 `field_imgPath`。可能是：
     *                * 绝对路径（少数版本）；
     *                * 纯文件名（如 `th_009d9b80134824969d6e5a250d7df0f7`）；
     *                * 带子目录的相对路径。
     *                **三种都试**，因为不同微信版本写法不同，实测无法只靠一种覆盖。
     * @return 读到且**格式可识别**的图片；任何一步失败都返回 null（调用方据此跳过这张图，
     *         而不是让整次分析失败）。
     */
    fun load(ctx: Context?, imgPath: String?): Raw? {
        if (ctx == null || imgPath.isNullOrBlank()) return null
        return try {
            val file = resolve(ctx, imgPath) ?: run {
                Log.i(TAG, "image: not found for imgPath=" + imgPath.take(80))
                return null
            }
            if (!file.isFile) return null
            val len = file.length()
            if (len <= 0L) return null
            if (len > MAX_BYTES) {
                Log.w(TAG, "image: too large (" + len + " bytes), skipped")
                return null
            }
            val bytes = file.readBytes()
            val mime = sniff(bytes)
            if (mime == null) {
                /* 走到这里最常见的原因是 **wxgf**（微信自有格式，见文件头说明），
                   也可能是表情包那种加密内容。记下来但不报错 —— 这是"这张图不支持"，
                   不是"功能坏了"，用户不需要被一个错误弹窗打断。 */
                Log.i(
                    TAG,
                    "image: unrecognized format, first bytes=" +
                        bytes.take(8).joinToString("") { b -> String.format("%02x", b) },
                )
                return null
            }
            Log.i(TAG, "image: loaded " + mime + " " + bytes.size + " bytes from " + file.name)
            Raw(mime, bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "image load failed: " + t.message)
            null
        }
    }

    /**
     * 把一个 `imgPath` 变成磁盘上真实存在的文件。
     *
     * 候选顺序是**实测出来**的：微信把图片放在 `image2/<前2位>/<3-4位>/<文件名>`。
     * 名字本身可能带 `th_` 前缀（缩略图）或 `.jpg` 后缀，不同版本不一，
     * 所以按"原样 → 去掉目录只留文件名 → 加/去 th_ 前缀"逐个试。
     */
    private fun resolve(ctx: Context, imgPath: String): File? {
        val direct = File(imgPath)
        if (direct.isFile) return direct

        val base = dataDir(ctx) ?: return null
        val image2 = File(File(base, "MicroMsg"), accountDir(base)?.name ?: return null)
            .let { File(it, "image2") }
        if (!image2.isDirectory) return null

        val name = imgPath.substringAfterLast('/')
        // 名字里通常是 md5 十六进制串；用它推导分片目录。
        val stem = name.removePrefix("th_").substringBefore('.').removeSuffix("hd")
        for (candidate in nameCandidates(name)) {
            val dir = shardDir(image2, stem)
            if (dir != null) {
                val f = File(dir, candidate)
                if (f.isFile) return f
            }
            // 分片目录算不出来（名字不是十六进制）时，退化成全局找一次。
            findByName(image2, candidate)?.let { return it }
        }
        return null
    }

    /** 名字的候选写法（不同版本/不同状态的文件名不一样，全试一遍）。 */
    private fun nameCandidates(name: String): List<String> = buildList {
        add(name)
        if (!name.startsWith("th_")) add("th_$name")
        // 高清版常以 "hd" 结尾；缩略图与高清图内容不同，但**任一可用即可**。
        if (!name.endsWith("hd")) add(name + "hd")
        if (!name.contains('.')) {
            add("$name.jpg")
            add("th_$name.jpg")
        }
    }

    /** `image2/<前2位>/<第3-4位>`；名字不是十六进制时返回 null。 */
    private fun shardDir(image2: File, stem: String): File? {
        if (stem.length < 4) return null
        val a = stem.substring(0, 2)
        val b = stem.substring(2, 4)
        if (!a.all { it.isDigit() || it in 'a'..'f' }) return null
        if (!b.all { it.isDigit() || it in 'a'..'f' }) return null
        return File(File(image2, a), b)
    }

    /** 兜底：在 image2 下按文件名找一次（分片规则变了时仍能找到）。 */
    private fun findByName(image2: File, name: String): File? = try {
        var found: File? = null
        // 只下探两层（image2/xx/yy），与实测结构一致；再深就会遍历上万目录。
        image2.listFiles()?.forEach { l1 ->
            if (found != null || !l1.isDirectory) return@forEach
            l1.listFiles()?.forEach { l2 ->
                if (found != null) return@forEach
                val f = File(l2, name)
                if (f.isFile) found = f
            }
        }
        found
    } catch (_: Throwable) {
        null
    }

    /** 微信的数据根目录。宿主进程里 `filesDir.parentFile` 就是 `/data/data/com.tencent.mm`。 */
    private fun dataDir(ctx: Context): File? = try {
        ctx.filesDir?.parentFile
    } catch (_: Throwable) {
        null
    }

    /** 账号目录：取含 `EnMicroMsg.db` 且最近修改的那个（与 DbReader 同一套判据）。 */
    private fun accountDir(dataDir: File): File? = try {
        val microMsg = File(dataDir, "MicroMsg")
        microMsg.listFiles { f -> f.isDirectory && File(f, "EnMicroMsg.db").isFile }
            ?.sortedByDescending { File(it, "EnMicroMsg.db").lastModified() }
            ?.firstOrNull()
            ?: microMsg.listFiles { f -> f.isDirectory }?.maxByOrNull { it.lastModified() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 靠**文件头魔数**判断真实格式。
     *
     * ⛔ 绝不能按扩展名判断 —— 实测证明 `.jpg` 文件的实际内容是 `wxgf` 容器（见文件头说明）。
     * 判错 MIME 会让服务端解码失败、整个请求报错，而用户完全不知道该改什么。
     *
     * @return 认识的 MIME，或 null（不认识 → 调用方跳过这张图）。
     */
    fun sniff(b: ByteArray): String? {
        if (b.size < 12) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        return when {
            // ffd8ff = JPEG
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            // 89504e47 = PNG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            // 474946 = GIF
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            // RIFF....WEBP
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> null
        }
    }
}
