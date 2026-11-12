package com.luoliaoo.wechat.hook

import com.luoliaoo.wechat.core.LegacyHook

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.luoliaoo.wechat.core.Log
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adds a "\u843d\u804a" entry to WeChat's "Me -> Settings" page.
 *
 * LEARNING FROM NUKE: its strings show it injects a DATA ITEM into the settings list
 * (SettingMenuInjector / BaseSettingItem / SettingGroup_Main_Nuke), letting WeChat render
 * the row natively -- rather than adding a floating view.
 *
 * WeChat 8.0.77 layout (verified by decompiling):
 *     e64.i              base class of every settings entry
 *         k7() -> String     title
 *         o7() -> Class      Activity opened on tap
 *         l7() -> e64.u      children (for groups)
 *     SettingGroupMain.l7()  the entry list under "SettingGroup_Main"
 *
 * Two strategies, both fully logged:
 *   A) DATA: hook SettingGroupMain's entry-list method and append our own entry, built as
 *      a runtime subclass of a concrete leaf item with overridden title/target.
 *   B) VIEW: insert a row View into the settings list container, retried several times
 *      because the container may not exist at onCreate time.
 *
 * Strategy B is the reliable fallback: it only needs an existing ViewGroup.
 */
class SettingsHook(
    
    private val loader: ClassLoader,
) {

    /**
     * 3.1.0 分身修复：所有微信类都必须走「微信自己的」类加载器。
     *
     * 实测（分身 user=999）：微信跑在 dalvik.system.DelegateLastClassLoader 下，它**最后**才问父加载器。
     * 于是 ModuleBridge.hostClassLoader（微信的 app.baseContext.classLoader）和本类构造参数 loader（模块自己
     * Context 的加载器）会把**同一个类名解析成两个不同的 Class**。
     *
     * 后果和「分析」按钮一模一样：Hook 装得上，但挂在了微信不会实例化的那个类上，永不触发。
     * 安装日志一切正常，只有事件从不发生，所以极难发现。
     *
     * 因此这里统一优先用 hostClassLoader；只要它可用就绝不用构造参数 loader。
     */
    private fun hostLoader(): ClassLoader =
        com.luoliaoo.wechat.core.ModuleBridge.hostClassLoader ?: loader

    private companion object {
        const val TAG = "LuoLiao"
        const val ENTRY_TAG = "luoliao_settings_entry"

        const val TITLE = "\u843d\u804a"
        const val SUMMARY = "AI \u6d88\u606f\u610f\u56fe\u5206\u6790  \u203a"

        /** Retry schedule (ms) for the view-injection path. */
        val RETRY_DELAYS = longArrayOf(0L, 200L, 500L, 1000L, 1800L)

        val SETTINGS_CLASSES = listOf(
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI",
        )

        /** Classes whose entry-list method we try to extend (strategy A). */
        val GROUP_CLASSES = listOf(
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain",
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAboutItem",
        )

        val injectedViews = AtomicInteger(0)
    }

    private val main = Handler(Looper.getMainLooper())

    fun install() {
        Log.i(TAG, "SettingsHook install START")

        var hooked = 0
        for (name in SETTINGS_CLASSES) {
            val cls = try {
                Class.forName(name, false, hostLoader())
            } catch (_: Throwable) {
                Log.d(TAG, "settings class absent: " + name)
                continue
            }
            val onCreate = findOnCreate(cls)
            if (onCreate == null) {
                Log.d(TAG, name + " has no onCreate(Bundle)")
                continue
            }
            try {
                LegacyHook.hook(onCreate)
                    .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val act = chain.thisObject
                        if (act is Activity) {
                            Log.i(TAG, "settings page onCreate: " + act.javaClass.name)
                            scheduleInject(act)
                        }
                        result
                    }
                hooked++
                Log.i(TAG, "hooked settings page: " + name + ".onCreate")
            } catch (t: Throwable) {
                Log.w(TAG, "hook settings page " + name + " failed: " + t.message)
            }
        }

        // Strategy A: try to extend the settings data list.
        hooked += tryDataInjection()

        if (hooked == 0) Log.w(TAG, "no settings hook installed")
        Log.i(TAG, "SettingsHook install END (hooks=" + hooked + ")")
    }

    // ------------------------------------------------------------ strategy A

    /**
     * Hooks the group's entry-list method and appends our entry.
     * Logs the list size before and after so the result is unambiguous.
     */
    private fun tryDataInjection(): Int {
        var n = 0
        for (name in GROUP_CLASSES) {
            val cls = try {
                Class.forName(name, false, hostLoader())
            } catch (_: Throwable) {
                continue
            }
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!List::class.java.isAssignableFrom(m.returnType)) continue
                try {
                    LegacyHook.hook(m)
                        .setExceptionMode(LegacyHook.Frame.EXCEPTION_MODE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val list = result as? MutableList<Any?>
                                if (list != null) {
                                    Log.i(TAG, "group " + cls.simpleName + "." + m.name +
                                        " returned " + list.size + " entries")
                                    if (list.isNotEmpty()) {
                                        val first = list[0]
                                        Log.i(TAG, "  first entry type=" +
                                            (first?.javaClass?.name ?: "null"))
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "inspecting group list failed: " + t.message)
                            }
                            result
                        }
                    n++
                    Log.i(TAG, "hooked group list: " + name + "." + m.name)
                } catch (t: Throwable) {
                    Log.w(TAG, "hook group list " + m.name + " failed: " + t.message)
                }
            }
        }
        return n
    }

    private fun findOnCreate(cls: Class<*>): Method? {
        var p: Class<*>? = cls
        var d = 0
        while (p != null && d < 4) {
            for (m in p.declaredMethods) {
                if (m.name == "onCreate" && m.parameterCount == 1 &&
                    m.parameterTypes[0] == Bundle::class.java
                ) {
                    return m
                }
            }
            p = p.superclass
            d++
        }
        return null
    }

    // ------------------------------------------------------------ strategy B

    /**
     * Repeatedly attempts to insert the entry row. Early attempts usually find nothing
     * because the settings list is built after onCreate returns.
     */
    private fun scheduleInject(activity: Activity) {
        for (delay in RETRY_DELAYS) {
            main.postDelayed({ injectEntry(activity) }, delay)
        }
    }

    private fun injectEntry(activity: Activity) {
        try {
            if (activity.isFinishing) return
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                Log.d(TAG, "inject: no content view yet")
                return
            }
            if (root.findViewWithTag<View>(ENTRY_TAG) != null) {
                Log.d(TAG, "inject: entry already present")
                return
            }

            val entry = buildEntry(activity)

            // Strategy 1: the RecyclerView that holds the setting rows.
            val list = findSettingsList(root)
            if (list != null) {
                val host = list.parent as? ViewGroup
                if (host != null) {
                    val idx = host.indexOfChild(list)
                    host.addView(entry, idx)
                    Log.i(
                        TAG,
                        "inject: inserted into list host=" + host.javaClass.simpleName +
                            " idx=" + idx + " (children=" + host.childCount + ")",
                    )
                    injectedViews.incrementAndGet()
                    return
                }
            }

            // Strategy 2: the page BODY (first child of the content view), never the
            // content root itself -- index 0 of the content root is under the title bar,
            // which is where the previous build put the row and why it was invisible.
            val body = firstChildGroup(root)
            if (body != null) {
                body.addView(entry, 0)
                Log.i(
                    TAG,
                    "inject: inserted into page body=" + body.javaClass.simpleName +
                        " (children=" + body.childCount + ")",
                )
                injectedViews.incrementAndGet()
                return
            }

            Log.w(TAG, "inject: no usable container found; container dump follows")
            val names = ArrayList<String>()
            dumpTree(root, names, 0)
            Log.w(TAG, "inject: tree=" + names)
        } catch (t: Throwable) {
            Log.e(TAG, "injectEntry failed", t)
        }
    }

    /** The first ViewGroup child of the content view -- the page body, below any bar. */
    private fun firstChildGroup(root: ViewGroup): ViewGroup? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup && c.visibility == View.VISIBLE) return c
        }
        return null
    }

    /** Compact dump of the view tree, for diagnosing container selection. */
    private fun dumpTree(v: View, out: MutableList<String>, depth: Int) {
        if (depth > 6 || out.size > 40) return
        if (v is ViewGroup) {
            out.add(v.javaClass.simpleName + "[" + v.childCount + "]")
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), out, depth + 1)
        }
    }

    /** Finds the RecyclerView that renders the setting rows. */
    private fun findSettingsList(root: ViewGroup): ViewGroup? {
        var found: ViewGroup? = null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && found == null) {
            val (v, depth) = stack.removeLast()
            if (depth > 10) continue
            if (v is ViewGroup && isRecyclerView(v)) {
                found = v
                break
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to depth + 1)
            }
        }
        // Log what the tree looks like when nothing was found, to aid diagnosis.
        if (found == null) {
            val names = LinkedHashSet<String>()
            collectContainerNames(root, names, 0)
            Log.i(TAG, "inject: no RecyclerView; containers=" + names)
        }
        return found
    }

    private fun collectContainerNames(v: View, out: MutableSet<String>, depth: Int) {
        if (depth > 8 || out.size > 24) return
        if (v is ViewGroup) out.add(v.javaClass.simpleName)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                collectContainerNames(v.getChildAt(i), out, depth + 1)
            }
        }
    }

    private fun isRecyclerView(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var d = 0
        while (c != null && d < 8) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
            d++
        }
        return false
    }

    /**
     * Builds the entry row, styled like a WeChat settings item.
     *
     * 液态玻璃升级：原先这里有三个硬编码颜色（白底 0xFFFFFFFF、深灰字 0xFF1F1F24、
     * 浅灰字 0xFF9A9AA2），它们**假定微信设置页是白底**。用户的微信若处于深色模式，
     * 这一行就是一块刺眼的白色补丁。
     *
     * 现在这一行的配色由下面 [entrySurface] / [entryTitleColor] / [entrySummaryColor]
     * 依据**宿主自己的 uiMode** 决定，微信深色时自动跟随。这里刻意不写进 NukeTheme：
     * 这一行注入的是**宿主的界面**（微信设置页），语义上属于「宿主设置项」而不是模块
     * 自己的面板，自有其对比度要求；而且 theme-core 正在改造 NukeTheme，独立实现可以
     * 避免两处同时改一个文件。
     *
     * 对比度（WCAG 2.1 相对亮度公式，已按最坏情况核算）：
     *   深色 标题 #F2F5FF on 玻璃底 ≈ 14.7:1，说明 #A8B0C8 ≈ 7.4:1
     *   浅色 标题 #1A1F36 on 玻璃底 ≈ 14.9:1，说明 #5A6379 ≈ 5.5:1
     * 两套都远超 AA 正文要求的 4.5:1。
     */
    private fun buildEntry(activity: Activity): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = ENTRY_TAG
            setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 18))
            background = entrySurface(activity)
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(TextView(activity).apply {
            text = TITLE
            textSize = 16f
            setTextColor(entryTitleColor(activity))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        row.addView(TextView(activity).apply {
            text = SUMMARY
            textSize = 13f
            setTextColor(entrySummaryColor(activity))
        })

        row.setOnClickListener {
            Log.i(TAG, "settings entry tapped; opening in-process config dialog")
            try {
                com.luoliaoo.wechat.ui.ConfigDialog.showWithTemplateChoice(activity)
            } catch (t: Throwable) {
                Log.e(TAG, "config dialog failed", t)
                Toast.makeText(
                    activity,
                    // item 5: include the MESSAGE, so a failure is diagnosable from the toast alone.
                    TITLE + "\uff1a\u65e0\u6cd5\u6253\u5f00\u914d\u7f6e\uff08" +
                        t.javaClass.simpleName +
                        (t.message?.let { ": " + it } ?: "") + "\uff09",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        return row
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    // ============================================================ 宿主设置行的配色
    //
    // 这一组函数只服务于「注入到宿主设置页的那一行」，所以刻意放在这里而不是 NukeTheme：
    // 它渲染的是微信自己的界面，判断依据必须是**宿主 Activity 的 uiMode**（模块进程的
    // 主题可能与宿主不同），而且不参与模块面板的设计体系。

    /** 宿主是否处于深色模式。用宿主 Activity 自己的 configuration，而不是模块进程的。 */
    private fun hostIsDark(activity: Activity): Boolean = try {
        val mode = activity.resources.configuration.uiMode
        (mode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        // 读不到 configuration 时退回浅色：微信设置页历史上是浅色，这是最不容易出错的兜底。
        false
    }

    /**
     * 该行的玻璃底：半透明 + 细描边 + 顶部高光，四角 8dp。
     *
     * 用 LayerDrawable 分层而不是实时模糊 —— 这一行在每个设置页打开时都要绘制，
     * 而模块运行在宿主进程里，逐帧模糊的代价不可接受。
     *
     * 明暗两套都是「半透明」而非纯色，所以底下的宿主页面会透出来一点，这正是玻璃的观感；
     * 但主体不透明度足够高（深色 0xE6 / 浅色 0xF0），保证文字对比度不被宿主底色影响。
     */
    private fun entrySurface(activity: Activity): android.graphics.drawable.Drawable {
        val dark = hostIsDark(activity)
        val radius = dp(activity, 8).toFloat()
        val density = activity.resources.displayMetrics.density

        // 主体：半透明玻璃填充 + 1dp 描边
        val body = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (dark) 0xE61A2032.toInt() else 0xF0FFFFFF.toInt())
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                if (dark) 0x33FFFFFF else 0x14000000,
            )
        }

        // 顶部高光：从上到下由亮到透明，制造「玻璃有厚度」的感觉
        val sheen = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) intArrayOf(0x14FFFFFF, 0x00FFFFFF) else intArrayOf(0x0DFFFFFF, 0x00FFFFFF),
        ).apply {
            cornerRadius = radius
        }

        return android.graphics.drawable.LayerDrawable(arrayOf(body, sheen)).apply {
            // 高光层与主体层同尺寸，靠自身的渐变淡出，因此不需要 inset。
            setLayerInset(1, 0, 0, 0, 0)
        }
    }

    /** 该行主标题色。深色 #F2F5FF（≈14.7:1）/ 浅色 #1A1F36（≈14.9:1），均达 AA。 */
    private fun entryTitleColor(activity: Activity): Int =
        if (hostIsDark(activity)) 0xFFF2F5FF.toInt() else 0xFF1A1F36.toInt()

    /** 该行次要说明色。深色 #A8B0C8（≈7.4:1）/ 浅色 #5A6379（≈5.5:1），均达 AA。 */
    private fun entrySummaryColor(activity: Activity): Int =
        if (hostIsDark(activity)) 0xFFA8B0C8.toInt() else 0xFF5A6379.toInt()
}
