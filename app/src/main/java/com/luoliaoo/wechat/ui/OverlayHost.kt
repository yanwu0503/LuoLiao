package com.luoliaoo.wechat.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.luoliaoo.wechat.core.Log

/**
 * 用系统悬浮窗承载更新提示。
 *
 * 为什么需要这条路：
 *   弹 Dialog 必须有前台 Activity。而模块是在**宿主的后台进程**里也运行的
 *   （微信实测有 :push、:appbrand0 等），那些进程一生都没有 Activity。
 *   实测症状就是日志里一行
 *     "update: no context yet, will retry on the next report"
 *   然后永远等不到 —— 用户根本看不到更新提示。
 *
 *   用 WindowManager + TYPE_APPLICATION_OVERLAY 就不需要 Activity，
 *   只要应用有「显示在其他应用上层」权限即可。
 *
 * 权限：宿主（微信/抖音）通常没有这个权限，所以这条路在多数用户机器上走不通 ——
 *   但它是**零成本**的兜底：有权限就多一条能把提示送到用户眼前的路，
 *   没权限就返回 false，行为和不做这件事完全一样。
 *   真正的主力仍然是「等用户切到微信前台时用 Activity 弹 Dialog」。
 */
object OverlayHost {

    private const val TAG = "LuoLiao"

    @Volatile
    private var activeView: View? = null

    /**
     * 现在能不能弹悬浮窗？
     * 没权限时 WindowManager.addView 会抛 BadTokenException ——
     * 与其抛了再兜，不如先问一句。
     */
    fun canShow(): Boolean = try {
        val ctx = com.luoliaoo.wechat.core.HostContext.application() ?: return false
        android.provider.Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    /**
     * 把视图挂到系统窗口上。
     *
     * @return 成功返回 true
     */
    fun show(view: View): Boolean {
        if (activeView != null) {
            Log.i(TAG, "overlay: one is already showing, skip")
            return false
        }
        val ctx = com.luoliaoo.wechat.core.HostContext.application() ?: return false
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                /* 让用户能点按钮，但不要抢焦点（抢焦点会打断用户正在做的事，
                   比如正在打字时突然跳出我们的窗口，体验很差）。 */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.CENTER
                /* 上下各留出一点，别顶到状态栏和导航栏 */
                val density = ctx.resources.displayMetrics.density
                val pad = (16 * density).toInt()
                x = 0
                y = 0
                /* 宽度留白由外层容器的 padding 处理（MATCH_PARENT + padding） */
                view.setPadding(pad, pad, pad, pad)
            }
            wm.addView(view, lp)
            activeView = view
            Log.i(TAG, "overlay: shown")
            true
        } catch (t: Throwable) {
            /* 没有权限时这里会抛 BadTokenException —— 正常情况，不当作错误刷日志 */
            Log.w(TAG, "overlay: cannot show: " + t.javaClass.simpleName + " " + t.message)
            activeView = null
            false
        }
    }

    /** 移除当前悬浮窗（用户点了按钮或页面要清理时调用）。 */
    fun hide() {
        val v = activeView ?: return
        try {
            val ctx = com.luoliaoo.wechat.core.HostContext.application()
            val wm = ctx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.removeView(v)
            Log.i(TAG, "overlay: removed")
        } catch (t: Throwable) {
            Log.w(TAG, "overlay: removeView failed: " + t.message)
        } finally {
            activeView = null
        }
    }
}
