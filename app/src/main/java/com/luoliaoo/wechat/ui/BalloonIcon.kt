package com.luoliaoo.wechat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.LruCache

/**
 * The balloon's artwork: the picture supplied by the user, cropped to a CIRCLE.
 *
 * WHY EMBEDDED: this runs inside the WeChat process, where the module's own resources are not reliably
 * reachable. The picture is therefore embedded as a base64 JPEG and decoded at run time, which works in
 * any process. It is decoded once and cached.
 *
 * The crop is done twice, as the user asked -- "只显示一个圆":
 *   1. centre-crop to a square (the source is square already, but this keeps it correct if it changes)
 *   2. clip to a circle, so only the middle round part of the picture is visible
 */
object BalloonIcon {

    private const val TAG = "LuoLiao"

    /** The embedded picture. 256x256 JPEG. */
    private val DATA: String =
        "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"

    private val cache = LruCache<String, Bitmap>(4)

    /** The decoded source bitmap, or null when decoding failed. */
    private fun source(): Bitmap? {
        cache.get("src")?.let { return it }
        return try {
            val bytes = Base64.decode(DATA, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) cache.put("src", bmp)
            bmp
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w(TAG, "balloon icon decode failed: " + t.message)
            null
        }
    }

    /**
     * A circular Drawable of the picture, `sizePx` across.
     *
     * The picture is scaled to COVER the circle (centre-cropped) rather than squeezed, and clipped with
     * a circular Path, so the result is a clean disc with no white corners.
     *
     * ============================== THE RING DOES NOT SCALE WITH THE BALLOON ==============================
     * The ring used to be `side * 0.035f`, i.e. proportional. The user's report -- "shrinking it turns
     * it into a square" -- is what that produces: at 50% the disc is ~26dp across while the ring is
     * still 3.5% of ITS side, and at small sizes a ring drawn inside the circle eats so much of the
     * radius that the remaining visible disc reads as a rounded square rather than a circle.
     *
     * A ring that is the same THICKNESS at every size fixes it. The thickness is clamped so it also
     * cannot become invisible on a tiny balloon or a hairline on a huge one.
     */
    fun circular(context: Context, sizePx: Int): Drawable? {
        val side = sizePx.coerceAtLeast(8)
        val key = "c" + side
        cache.get(key)?.let { return BitmapDrawable(context.resources, it) }

        val src = source() ?: return null
        return try {
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            // Cover: scale so the SHORTER side fills the disc, then centre it.
            val scale = side.toFloat() / minOf(src.width, src.height).toFloat()
            val dw = src.width * scale
            val dh = src.height * scale
            val left = (side - dw) / 2f
            val top = (side - dh) / 2f
            val dst = RectF(left, top, left + dw, top + dh)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                // Map the shader into the destination rectangle.
                shader.setLocalMatrix(
                    android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(left, top)
                    },
                )
            }

            // The circle clip.
            val circle = Path().apply {
                addCircle(side / 2f, side / 2f, side / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(circle)
            canvas.drawRect(dst, paint)
            canvas.restore()

            // 液态玻璃描边 v2（t5）：原来是**单层不透明**深色环（RING = 0xFF1A1E2C）。
            // 单层不透明环在深色宿主背景上几乎看不见（深环压在深色图片/深色聊天底上），
            // 在浅色背景上又只是一圈生硬的实线，和全站要做的「液态玻璃」观感对不上。
            //
            // 现在改成玻璃边缘惯用的两层做法，全部画在裁切圆**内部**，因此不改动任何尺寸、
            // 不依赖宿主背景、也不会引出圆角处的裁切伪影：
            //   * 外层：半透明白色高光（模拟光线扫过玻璃边缘的折射亮边），
            //     在浅色宿主背景上不会白到糊掉（只有 66% 不透明度），在深色背景上提供亮边；
            //   * 内层：半透明冷色玻璃芯（与 NukeTheme 的 CARD_BORDER 0xFF283252 同一族，
            //     只是带透明度），补足轮廓对比，让悬浮球在浅色宿主背景上依然清晰可点。
            // 两层都是 alpha 混合，所以浅色/深色宿主背景上都可辨 —— 这正是铁律 4 的要求。
            //
            // 厚度仍与尺寸无关（见 KDoc：比例厚度才是「缩小变成方形」的原因），只轻微加粗
            // 以容纳两层描边。
            val edgePx = (side * 0.042f).coerceIn(1.8f, 4.5f)
            canvas.drawCircle(
                side / 2f,
                side / 2f,
                side / 2f - edgePx / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = edgePx
                    color = EDGE_HIGHLIGHT
                },
            )
            val corePx = edgePx * 0.62f
            canvas.drawCircle(
                side / 2f,
                side / 2f,
                side / 2f - edgePx / 2f - corePx / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = corePx
                    color = EDGE_CORE
                },
            )

            cache.put(key, out)
            BitmapDrawable(context.resources, out)
        } catch (t: Throwable) {
            com.luoliaoo.wechat.core.Log.w(TAG, "balloon icon render failed: " + t.message)
            null
        }
    }

    /**
     * 悬浮球盘面的玻璃描边色（t5：液态玻璃统一）。
     *
     * 【为什么不再是单个不透明的 RING】原来只有一层 `RING = 0xFF1A1E2C`，是不透明深色，
     * 而且这个值**没有出处**（不属于任何既有色板）。悬浮球浮在**宿主 App 的界面**上，
     * 宿主背景可能是浅色（微信白天聊天背景接近白色）也可能是深色：一层不透明深色环在
     * 深色宿主上就消失了；而它在浅色背景上又只是一圈生硬的实线，与「液态玻璃」不是一回事。
     *
     * 【两层玻璃边缘】外层是半透明白高光（玻璃边缘的折射亮边），内层是半透明冷色玻璃芯。
     * 两层都靠 alpha 混合，所以**在浅色与深色宿主背景上都可辨、可点**（铁律 4）：
     * 浅底上由内层深芯分离轮廓，深底上由外层亮边分离轮廓。
     * 这也满足铁律 5：原来的 RING 语义（「盘面轮廓线」）保留，只是换成与玻璃协调的写法。
     *
     * 【为什么故意**不**写成 NukeTheme 的动态 token（重要，勿"顺手改回"）】
     * 悬浮球浮在**宿主 App** 的界面上，而不是浮在我们自己的画布上。宿主的明暗与我们模块的
     * 明暗主题是**互相独立**的两件事：用户在微信深色模式下完全可能把模块设成浅色主题。
     * 因此这两条边缘**不能**跟着 `NukeTheme.dark` 走 —— 若浅色主题下把内层芯取成
     * CARD_BORDER 那种浅灰（浅色主题下它确实是浅色），压在白色的微信聊天背景上就等于没描边，
     * 悬浮球边界会消失。这里需要的是「一深一浅同时存在」的双重边缘，它对**任意**宿主底色都成立。
     *
     * 【出处】RGB 仍取自模块既有色板：内层芯 0xFF283252 与 NukeTheme 面板描边同族，
     * 外层高光是玻璃高光的白。alpha 单独用 NukeTheme.withAlpha() 表达，避免再手写 0xA8xxxxxx
     * 这类「打包好的字面量」，色与透明度各管一段。
     *
     * 【★ 这里冻结是安全的，与 AnalysisIcon 的伴生属性不同 —— 不要把两件事混为一谈】
     * 这两个是 `object` 的单例属性，同样"只求值一次"，但**不构成 bug**，因为
     * `NukeTheme.withAlpha(color, alpha)` 是**纯位运算**（`(color and 0xFFFFFF) or (alpha shl 24)`），
     * 它的输入是两个**写死的字面量**，不读 `NukeTheme.dark`、不经过 `pick()`，
     * 所以无论何时求值结果都相同，冻结与不冻结等价。
     * 真正会出错的是"输入随主题变化"的那种捕获（例如 `val X = NukeTheme.ACCENT`）——
     * 因为 ACCENT 现在是 `get() = pick(...)`，读它才有明暗之分。
     * 判据很简单：**右边读到的值是否依赖主题**。依赖→必须用 getter；纯字面量运算→冻结无害。
     */
    private val EDGE_HIGHLIGHT = NukeTheme.withAlpha(0xFFFFFFFF.toInt(), 0xA8)

    /** 玻璃边缘的内层冷色芯，取自 NukeTheme 面板描边族的同色系。 */
    private val EDGE_CORE = NukeTheme.withAlpha(0xFF283252.toInt(), 0x8A)
}
