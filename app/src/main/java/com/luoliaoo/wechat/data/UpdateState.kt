package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.core.Log

/**
 * 更新提示的本地状态（「暂不下载」的 24 小时静默）。
 *
 * 为什么不用 SharedPreferences 而用文件：
 *   模块跑在宿主（微信/抖音）进程里，而 SharedPreferences 的写入是**异步落盘**的。
 *   宿主进程随时可能被系统杀掉（用户清理后台、系统回收内存），
 *   异步写常常还没落盘进程就没了 —— 表现为用户点了「暂不下载」，
 *   下次打开又被弹一次，而且怎么都记不住。
 *   文件是同步写，写完了才返回，不会有这个问题。
 *
 * 文件放在模块自己的私有目录（不是宿主目录），所以换宿主也共享同一份状态。
 */
object UpdateState {

    private const val TAG = "LuoLiao"
    private const val FILE = "update_state.txt"

    /** 「暂不下载」后静默多久。产品要求 24 小时。 */
    const val SNOOZE_MS = 24L * 60 * 60 * 1000

    /**
     * 记录「暂不下载」：从现在起 24 小时内不再弹。
     *
     * @param version 被跳过的那个版本号。
     *   记下版本号是为了：如果之后发布了**更新**的版本，
     *   即使 24 小时还没到也应该提示（用户跳过的是旧的那个，不是新的）。
     */
    @Synchronized
    fun snooze(ctx: Context, version: String) {
        try {
            val f = java.io.File(ctx.filesDir, FILE)
            f.writeText(version + "\n" + (System.currentTimeMillis() + SNOOZE_MS), Charsets.UTF_8)
            Log.i(TAG, "update: snoozed until +24h (version=" + version + ")")
        } catch (t: Throwable) {
            Log.w(TAG, "update: failed to persist snooze: " + t.message)
        }
    }

    /**
     * 现在该不该弹更新提示？
     *
     * @param latest 服务端给的版本号
     * @return true 表示可以弹
     *
     * 三个条件同时满足才不弹：
     *   ① 之前点过「暂不下载」
     *   ② 跳过的版本就是当前这个 latest（没出现更新的版本）
     *   ③ 24 小时还没到
     * 任何一条不成立就应该弹 —— 尤其②，否则「跳过了 5.1.0，结果 5.2.0 发布也不提示」，
     * 用户会被永久困在旧版本上。
     */
    @Synchronized
    fun shouldPrompt(ctx: Context, latest: String): Boolean {
        return try {
            val f = java.io.File(ctx.filesDir, FILE)
            if (!f.isFile) return true
            val lines = f.readText(Charsets.UTF_8).trim().split("\n")
            if (lines.size < 2) return true

            val snoozedVersion = lines[0].trim()
            val until = lines[1].trim().toLongOrNull() ?: return true

            if (System.currentTimeMillis() >= until) return true          // ③ 已过期
            if (snoozedVersion != latest.trim()) return true              // ② 换了新版本
            false
        } catch (t: Throwable) {
            /* 读不出来就当作可以弹 —— 宁可多弹一次，也不要因为状态文件损坏
               导致用户永远收不到更新提示（那会让用户卡在旧版本上，是更糟的结果）。 */
            Log.w(TAG, "update: reading snooze state failed, will prompt: " + t.message)
            true
        }
    }

    /** 清掉静默状态（用户点了「点我下载」时调用）。 */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            java.io.File(ctx.filesDir, FILE).delete()
        } catch (_: Throwable) {
            // 删不掉也无所谓，24 小时后自然失效
        }
    }
}
