package com.luoliaoo.wechat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The little icon shown to the left of the 分析 entry in WeChat's long-press menu.
 *
 * Drawn entirely in code with a Path, so it needs no drawable resource -- important because this
 * widget is created inside the WeChat process, where the module's own resources are not reliably
 * reachable.
 *
 * Shape: a rounded speech bubble (it analyses a conversation) with a four-point spark inside (the AI
 * part). Drawn in the module's accent colour, sized to match a normal menu glyph.
 */
class AnalysisIcon(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR
    }

    companion object {
        /**
         * 模块强调色，与全站的液态玻璃配色保持一致。
         *
         * t5：以前这里是**手写的字面量** 0xFF91A9FF，与 NukeTheme.ACCENT 是同一个值但是两份来源 ——
         * 内核（theme-core）一旦调整强调色，这个图标就会留在旧颜色上，菜单里的「分析」图标
         * 会和面板、卡片对不上。
         *
         * 现在直接引用 NukeTheme 的公开 token（铁律 1：只用现有公开 API，不新增任何东西），
         * 值仍然是同一个浅紫蓝色：它在浅色宿主菜单上够深、在深色宿主菜单上够亮，
         * 所以同一个图标在微信/抖音/QQ/快手/Soul 的各种菜单底色上都清晰可辨（铁律 4）。
         *
         * ★ 必须写成 `val` + **自定义 getter**。两种写法都会踩同一个坑：
         *   * `const val COLOR = NukeTheme.ACCENT`  —— 编译期常量（现在的 ACCENT 已不是 const，写不出来）
         *   * `val COLOR: Int = NukeTheme.ACCENT`   —— **看起来"动态"，其实也在初始化时求值一次**
         * 后者尤其危险，因为它长得就像动态取值，却和 const 一样被冻住。
         *
         * 原因：液态玻璃改造把 NukeTheme.ACCENT 从「编译期常量」改成了**按明暗主题动态取值**
         * 的 getter（`pick(浅色值, 深色值)`）。而 `companion object` 的属性只在类第一次被加载时
         * 求值一次，之后在**整个进程存活期内固定不变** —— 它甚至跨过了主题切换。
         * 于是：首次加载若是深色主题，COLOR 就冻在深色强调色；用户切到浅色主题后，这个「分析」
         * 图标会停在旧颜色上，与菜单、面板、卡片全都对不上。改成 getter 后每次读取都重新问一遍
         * NukeTheme，切换主题即生效。
         *
         * 调用方安全性：`paint` 在**构造时**读取 COLOR（第 28 行），属于「视图构建那一刻」求值 ——
         * 这是**正确**的时机，因为界面重建（切主题必然伴随 Activity 重建）会重新构造本 View
         * 并重新取色。所以真正需要修的是上面那条只算一次的伴生属性，不是这里。
         */
        val COLOR: Int get() = NukeTheme.ACCENT

        /** Nominal size in dp; the row may stretch it, the drawing stays centred. */
        const val SIZE_DP = 20

        /**
         * Renders the icon into a Drawable so it can be handed to MenuItem.setIcon().
         *
         * A bitmap is used instead of a VectorDrawable because this runs inside the WeChat process, where
         * the module's own resources are not reachable and inflating one is unreliable.
         */
        fun toDrawable(context: Context, sizePx: Int): android.graphics.drawable.Drawable {
            val safe = sizePx.coerceAtLeast(8)
            val bmp = android.graphics.Bitmap.createBitmap(
                safe, safe, android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bmp)
            val view = AnalysisIcon(context)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(safe, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(safe, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, safe, safe)
            view.draw(canvas)
            return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        // Work in a square centred in the view, with a small inset for the stroke.
        val side = minOf(w, h)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        val pad = side * 0.12f
        val s = side - pad * 2f

        // ---- speech bubble: a rounded rectangle with a tail at the bottom-left.
        val bubble = RectF(left + pad, top + pad, left + pad + s, top + pad + s * 0.78f)
        val r = s * 0.24f
        val body = Path().apply {
            addRoundRect(bubble, r, r, Path.Direction.CW)
        }
        canvas.drawPath(body, paint)

        val tail = Path().apply {
            moveTo(left + pad + s * 0.22f, top + pad + s * 0.78f)
            lineTo(left + pad + s * 0.16f, top + pad + s * 0.98f)
            lineTo(left + pad + s * 0.42f, top + pad + s * 0.78f)
        }
        canvas.drawPath(tail, paint)

        // ---- the spark: a filled four-point star in the upper right of the bubble.
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR
        }
        val cx = left + pad + s * 0.70f
        val cy = top + pad + s * 0.30f
        val outer = s * 0.20f
        val inner = outer * 0.34f
        val spark = Path()
        for (i in 0 until 8) {
            val ang = Math.toRadians((i * 45 - 90).toDouble())
            val rad = if (i % 2 == 0) outer else inner
            val x = cx + (rad * Math.cos(ang)).toFloat()
            val y = cy + (rad * Math.sin(ang)).toFloat()
            if (i == 0) spark.moveTo(x, y) else spark.lineTo(x, y)
        }
        spark.close()
        canvas.drawPath(spark, fill)

        // ---- two "message" ticks on the left of the bubble, so it reads as a chat.
        val tick = Paint(paint).apply {
            strokeWidth = paint.strokeWidth * 0.9f
        }
        val tl = left + pad + s * 0.20f
        canvas.drawLine(tl, top + pad + s * 0.26f, tl + s * 0.30f, top + pad + s * 0.26f, tick)
        canvas.drawLine(tl, top + pad + s * 0.46f, tl + s * 0.20f, top + pad + s * 0.46f, tick)
    }
}
