package com.luoliaoo.wechat.core

import android.app.Activity
import android.content.Context

/**
 * 拿宿主的 Application Context。
 *
 * 为什么需要这个类（而不是直接用 AppContextHolder）：
 *   AppContextHolder 只在「模块见过任何一个 View」之后才有值。
 *   而宿主（微信/抖音）会开好几个进程：主进程、:push、:appbrand0 之类。
 *   那些后台进程从不显示界面，也就永远见不到 View ——
 *   于是它们的 Context 一直是 null。
 *
 * 实测症状：更新提示在后台进程里每次都打
 *   "update: new context yet, will retry on the next report"
 * 然后什么都不做，用户永远看不到弹窗。
 *
 * 所以这里按「从最可靠到最兜底」的顺序多试几条路：
 *   ① AppContextHolder —— 已经抓到就直接用（最省事）
 *   ② ActivityThread.currentApplication() —— 框架内部的当前 Application，
 *      任何进程都能拿到，是最通用的一条
 *   ③ ActivityThread 的 systemContext —— 兜底（有些定制 ROM 上 ② 会返回 null）
 *
 * 全部失败返回 null，调用方必须处理这种情况（不能假设一定有）。
 */
object HostContext {

    private const val TAG = "LuoLiao"

    /** 缓存住成功的 Context，避免每分钟都走一遍反射。 */
    @Volatile
    private var cached: Context? = null

    @Volatile
    private var reflectFailedOnce = false

    /**
     * @return 宿主的 Application Context；实在拿不到返回 null。
     */
    fun application(): Context? {
        cached?.let { return it }

        /* ① 模块自己已经抓到的（最可靠，且没有任何反射开销） */
        try {
            val held = AppContextHolder.get()
            if (held != null) {
                cached = held
                return held
            }
        } catch (_: Throwable) {
            // 继续试下一条
        }

        /* ② ActivityThread.currentApplication() —— 最通用的一条 */
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                val m = at.getDeclaredMethod("currentApplication")
                m.isAccessible = true
                val app = m.invoke(thread) as? Context
                if (app != null) {
                    cached = app
                    return app
                }

                /* ③ 兜底：systemContext。它在所有进程里都存在，
                   虽然拿它弹窗的观感取决于 ROM，但总比什么都不做强。 */
                try {
                    val f = at.getDeclaredField("mSystemContext")
                    f.isAccessible = true
                    val sys = f.get(thread) as? Context
                    if (sys != null) {
                        cached = sys
                        return sys
                    }
                } catch (_: Throwable) {
                    // 没有这个字段，正常
                }
            }
        } catch (t: Throwable) {
            /* 只报一次，避免每分钟刷屏 —— 这条路径每个上报周期都会走。 */
            if (!reflectFailedOnce) {
                reflectFailedOnce = true
                Log.w(TAG, "HostContext: reflection failed: " + t.message)
            }
        }
        return null
    }

    /** 清掉缓存（宿主进程重启后旧的 Context 可能已失效）。 */
    fun invalidate() {
        cached = null
    }
}
