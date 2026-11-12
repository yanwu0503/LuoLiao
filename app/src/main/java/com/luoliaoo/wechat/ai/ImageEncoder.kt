package com.luoliaoo.wechat.ai

import android.content.Context
import android.util.Base64
import com.luoliaoo.wechat.core.Log
import com.luoliaoo.wechat.hook.MsgAccess

/**
 * ★ v10.3.0：把一条消息里的图片收集出来，编码成可以直接发给模型的形式。
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * 【职责边界】
 *   * [WeChatImage] 负责"怎么从磁盘读出一个图片文件"（路径推导 + 文件头嗅探）；
 *   * 本文件负责"从一条消息找到它有哪些图片、读到字节、base64 编码、控制数量与体积"。
 * 分开是因为前者的知识全部来自**真机实测**（分片目录、明文/密文、wxgf 陷阱），
 * 后者是纯粹的编排逻辑，两者的改动原因完全不同。
 *
 * 【图片与表情包：当前只有图片能读通】
 * 实测结论：图片（`image2/`）是**明文**、能读；表情包（`emoji/`）**是加密的**、读不出来。
 * 这不是本文件的取舍，是数据本身如此（证据见 [WeChatImage] 的文件头说明）。
 * 所以这里对表情包消息的处理是：**尝试同样的读取路径**，读不到就安静跳过 ——
 * 这样将来若找到表情包的读法（或微信改回明文），这里不用改一行就会自动生效。
 *
 * 【为什么整条链路"只记日志、不抛异常"】
 * 图片是**增强**，不是分析的前提。任何一个环节出错（文件不存在、格式不认识、
 * 体积超限、base64 失败），正确的结果都是"这次分析不带这张图"，
 * 而不是"用户点了分析却什么都没发生"。所以每个可能失败的点都单独兜住。
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ImageEncoder {

    private const val TAG = "LuoLiao"

    /**
     * base64 之后的**总**上限。
     *
     * base64 会把体积放大约 1/3（3 字节 → 4 字符），而多数 OpenAI 兼容服务端对请求体有硬上限。
     * 4 张 4MB 的原图 base64 后约 21MB，足以让服务端直接拒绝或超时。
     * 这里把"带上图片后的总预算"定在 ~6MB（base64 文本长度），超出就少带几张 ——
     * 宁可少看一张图，也不要整次请求失败。
     */
    private const val MAX_TOTAL_B64 = 6 * 1024 * 1024

    /**
     * 从一条消息里取出图片并编码。
     *
     * @param ctx    宿主进程 Context（用于定位微信私有目录）。
     * @param msg    消息对象（微信的 MsgInfo 等）。
     * @param imgPath 已经读好的 `field_imgPath`。调用方传进来而不是在这里读，
     *                是为了让"字段怎么读"这件事只有一处（见 [MsgAccess.voiceFile]）——
     *                那个函数虽然名字叫 voice，但它是**按字段名读、没有任何类型检查**，
     *                对图片消息同样有效，所以复用它而不是另写一个读取器。
     * @return 可直接放进请求的图片列表。**永不抛异常**；失败返回空列表（= 这次不带图）。
     */
    fun collect(ctx: Context?, msg: Any?, imgPath: String?): List<OpenAiClient.ImagePart> {
        if (ctx == null) return emptyList()
        return try {
            /* 一次分析通常只有一条消息带图。多图的场景（例如将来做"多选消息分析"）
               由调用方多次调用本函数后自行合并，所以这里只处理当前这一条。 */
            val paths = candidatePaths(imgPath)
            if (paths.isEmpty()) return emptyList()

            val out = ArrayList<OpenAiClient.ImagePart>(WeChatImage.MAX_IMAGES)
            var total = 0
            for (p in paths) {
                if (out.size >= WeChatImage.MAX_IMAGES) break
                val raw = WeChatImage.load(ctx, p) ?: continue
                val b64 = try {
                    Base64.encodeToString(raw.bytes, Base64.NO_WRAP)
                } catch (t: Throwable) {
                    Log.w(TAG, "image: base64 failed: " + t.message)
                    continue
                }
                if (total + b64.length > MAX_TOTAL_B64) {
                    Log.i(
                        TAG,
                        "image: budget reached, skipping the rest (" + out.size + " sent, " +
                            "total=" + total + ")",
                    )
                    break
                }
                total += b64.length
                out.add(OpenAiClient.ImagePart(raw.mime, b64))
            }
            if (out.isNotEmpty()) {
                Log.i(TAG, "image: " + out.size + " image(s) attached, b64Len=" + total)
            } else {
                // 这条消息看起来有图（有 imgPath），但一张都没读出来。
                // 最常见的两个原因：① 是表情包（加密的）；② 用户从没点开过这张图，
                // 原图还没下载到本地。两者都是正常情况，所以只记一行信息级日志。
                Log.i(TAG, "image: no readable image for imgPath=" + imgPath?.take(60))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "image: collect failed: " + t.message)
            emptyList()
        }
    }

    /**
     * 从 `imgPath` 推导出要尝试的文件名候选。
     *
     * `field_imgPath` 在不同微信版本 / 不同消息状态下写法不同，实测见过：
     *   * 纯文件名：`th_009d9b80134824969d6e5a250d7df0f7`
     *   * 带子目录：`image2/00/9d/xxxx`
     *   * 带扩展名：`xxxx.jpg`
     * 这里把**非空的那一个**作为候选交给 [WeChatImage.load]，由它去做目录分片推导。
     * 之所以不在这里拼绝对路径：目录结构的知识属于 WeChatImage（那里有实测注释），
     * 在两层里各拼一次必然迟早漂移。
     */
    private fun candidatePaths(imgPath: String?): List<String> {
        val p = imgPath?.trim().orEmpty()
        if (p.isEmpty()) return emptyList()
        return listOf(p)
    }
}
