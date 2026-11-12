package com.luoliaoo.wechat.data

import android.content.Context
import com.luoliaoo.wechat.core.Log

/**
 * 免责声明的同意状态（v9.1.0）。
 *
 * 用户要求：
 *   「模块启动时检测有没有同意过 —— 弹出强制 UI，同意后才可以使用。
 *     有不同意 / 同意 两个按钮。同意后将写入本地文件，后续不再弹出。
 *     点不同意则直接应用闪退。」
 *
 * ★ 存储粒度：**每个宿主 App 各自一份**（2026-09-26 用户最终决定）。
 *   最初需求里写的是「所有作用域使用同一个验证，避免每个软件都需要同意」，
 *   真机测试后用户改为「还是改成每个app都有自己的吧」。原因见 [storageDir]。
 *   实际效果：微信里同意一次、QQ 里第一次用再同意一次，互不影响。
 *
 * ─────────────────────────────────────────────────────────────
 * 【为什么用文件而不是 SharedPreferences】
 * 模块跑在宿主进程里，而 SharedPreferences 的写入是**异步落盘**的。
 * 用户点了「同意」之后，如果宿主进程马上被系统杀掉（清后台、内存回收都很常见），
 * 异步写常常还没落盘，进程就没了 —— 表现为用户明明同意了，下次打开又被弹一次，
 * 怎么都记不住。文件是同步写，写完了才返回，不会出现这个问题。
 *
 * 【读不出来怎么办 —— 这里刻意选择「当作未同意」】
 * UpdateState 读失败时选择「可以弹」，因为那只是少打扰一次。
 * 本类的语义相反：读失败必须**再弹一次让用户确认**，而不是默认放行 ——
 * 免责声明是用户对使用行为的确认，宁可他多点一次，也不能在他没确认的情况下
 * 就当作他同意了。这条取舍与 UpdateState 不同，是刻意的。
 */
object Disclaimer {

    private const val TAG = "LuoLiao"

    /** 状态文件名。放在模块私有目录下，跨宿主共享。 */
    private const val FILE = "disclaimer_consent.txt"

    /** 当前免责声明版本。改动声明内容时**必须**把这里 +1。 */
    /*
     * 为什么要带版本号：将来免责声明的**内容**改了（比如新增了一条限制），
     * 之前同意过的用户同意的其实是旧内容。带上版本号，改了内容就自动
     * 重新征得同意，而不是让用户在不知情的情况下被旧同意覆盖。
     */
    const val CURRENT_VERSION = 1

    /**
     * 取得用来存放同意状态的目录。
     *
     * ★ 用户最终决定：**每个宿主 App 各自独立**（2026-09-26 修改）。
     *
     * 原始需求是「所有作用域使用同一个验证，避免每个软件都需要同意」，
     * 实现上走的是「模块自己的 Context」（ModuleBridge.moduleContext）。
     * 真机测试后用户改主意了，选择让每个 App 各自同意一次。理由（用户未明说，
     * 但从现场情况推断）：模块 Context 这条路依赖宿主允许我们创建它，
     * 在 QQ 上已有先例会失败（见 ModuleBridge 里 PACKAGE B 的注释），
     * 一旦拿不到就会**静默降级**或**永远弹不出声明**，行为不可预期；
     * 而用宿主自己的目录是**一定会成功**的路径 —— 我们本来就在宿主进程里。
     *
     * 【改动的实际影响】
     *   · 用户第一次用微信要点一次、第一次用 QQ 再点一次 —— 这是**用户明确接受**的。
     *   · 换来的好处：不依赖任何跨进程机制，5 个宿主行为完全一致、都一定能弹出来。
     *
     * 【保留了什么】仍然用宿主 Context 的 filesDir，而不是 SharedPreferences ——
     * 「同步落盘」这条理由与跨宿主无关，依然成立（见类注释）。
     *
     * @return 用于存放状态文件的目录；拿不到时返回 null。
     */
    private fun storageDir(hostCtx: Context): java.io.File? {
        /* 直接用宿主目录 —— 每个 App 一份，互不影响。
           我们本来就跑在宿主进程里，这是**必然可用**的路径，
           不像模块 Context 那样可能被宿主的 PackageManager 拦截而失败。 */
        return try {
            hostCtx.filesDir
        } catch (t: Throwable) {
            Log.w(TAG, "disclaimer: no usable storage dir: " + t.message)
            null
        }
    }

    /**
     * 用户是否已经同意过当前版本的免责声明。
     *
     * @return true = 已同意（可以正常使用模块）；false = 未同意或状态读取失败。
     */
    @Synchronized
    fun hasConsented(hostCtx: Context): Boolean {
        return try {
            val dir = storageDir(hostCtx) ?: return false
            val f = java.io.File(dir, FILE)
            if (!f.isFile) return false
            val text = f.readText(Charsets.UTF_8).trim()
            /* 文件格式：第一行是声明版本号，第二行是同意时间（毫秒）。
               只认「版本号完全相等」—— 声明内容改版后旧同意自然失效。 */
            val ver = text.split("\n").firstOrNull()?.trim()?.toIntOrNull() ?: return false
            ver == CURRENT_VERSION
        } catch (t: Throwable) {
            Log.w(TAG, "disclaimer: cannot read consent state, treating as NOT consented: " + t.message)
            false
        }
    }

    /**
     * 记录用户已同意。
     *
     * @return 是否真的写成功。**调用方必须检查**：
     *   写失败却放行，用户下次打开会被再弹一次（体验问题）；
     *   而更糟的是写失败被当成成功、状态却没落地，用户会反复被问。
     */
    @Synchronized
    fun markConsented(hostCtx: Context): Boolean {
        return try {
            val dir = storageDir(hostCtx) ?: return false
            val f = java.io.File(dir, FILE)
            /* 同步写：写完才返回，进程随时被杀也不会丢（见类注释）。 */
            f.writeText(
                CURRENT_VERSION.toString() + "\n" + System.currentTimeMillis() + "\n",
                Charsets.UTF_8,
            )
            Log.i(TAG, "disclaimer: consent recorded (version=" + CURRENT_VERSION +
                ", file=" + f.absolutePath + ")")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "disclaimer: failed to persist consent: " + t.message)
            false
        }
    }

    /** 仅供诊断/测试：清掉同意状态（下次启动会重新弹声明）。 */
    @Synchronized
    fun reset(hostCtx: Context) {
        try {
            storageDir(hostCtx)?.let { java.io.File(it, FILE).delete() }
        } catch (_: Throwable) {
            // 删不掉无所谓
        }
    }
}
