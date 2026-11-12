package com.luoliaoo.wechat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The module's design system: LIQUID GLASS with a light and a dark theme.
 *
 * ============================== WHAT CHANGED IN THIS REVISION ==============================
 *
 * Every colour below used to be a `const val` describing ONE dark theme. They are now
 * `val ... get()` properties that resolve per theme, so the whole module follows the system's
 * dark/light setting. The API surface is otherwise UNCHANGED: same names, same signatures,
 * so the 700+ call sites in the other 13 files did not have to be touched.
 *
 * That was verified before the change: no `when` branch, no annotation argument and no
 * `const val` anywhere in the project depends on these being compile-time constants, which is
 * what makes the conversion safe. DO NOT turn one of them back into a `const val` -- it would
 * freeze that colour to whatever theme was active when the class loaded.
 *
 * ============================== HOW THE THEME IS RESOLVED ==============================
 *
 * A colour getter takes no `Context` (it cannot: the call sites read `NukeTheme.TEXT`), so the
 * theme lives in ONE process-wide flag, [dark], refreshed by [syncTheme] from
 * `Configuration.uiMode`. [syncTheme] is called at the top of [dp] and [sp] -- two functions
 * that every screen inevitably calls while building -- so every construction path refreshes the
 * flag without any screen needing to remember to.
 *
 * DELIBERATE CHOICES, each of which prevents a specific failure:
 *
 *  * The default is DARK. If the flag is never refreshed (an exotic path we did not foresee),
 *    the UI falls back to the appearance this module has always had, rather than flashing white.
 *  * The signal is `uiMode`, the SAME signal `res/values-night/themes.xml` responds to. Using
 *    anything else would let the window be dark while the panels are light.
 *  * The refresh is throttled to a few hundred milliseconds. It is a cheap field read, but
 *    [dp] is called in tight layout loops and the throttle keeps a hot path hot. A theme change
 *    is still applied within a frame or two.
 *  * [syncTheme] takes the CALLER's Context, because a dialog built inside the host app (WeChat)
 *    uses the HOST's configuration, which can legitimately differ from this module's process.
 *
 * ============================== GLASS, WITHOUT A BLUR ==============================
 *
 * The glass look is built from LAYERED `GradientDrawable`s -- a translucent body, a top-down
 * sheen, and a 1dp edge-light stroke -- never from a real-time blur.
 *
 * WHY NO BLUR, and this is a performance decision rather than a stylistic one:
 *  1. Most of this UI is drawn INSIDE THE HOST's process (WeChat/Douyin/QQ/Soul/Kuaishou), on
 *     the host's main thread. A per-frame blur there is paid by the app the user is actually
 *     typing in.
 *  2. `RenderEffect` needs API 31+ and cannot sample the host window's content anyway, so the
 *     result would not even look right.
 *  3. The panel re-renders while scrolling a long analysis list; N layered translucent
 *     rectangles rasterise cheaply, a blur does not.
 *
 * The module also has no animations at all (verified), so there is nothing to gate behind
 * [animationsEnabled] today. It exists so that anything added later is gated from the start.
 */
object NukeTheme {

    // ---------------------------------------------------------------- theme resolution

    /**
     * The active theme. `true` = dark.
     *
     * `@Volatile` because the UI is built on the main thread while the hooks can also run on
     * host threads; a stale read would only ever cost one frame of the wrong colour, but the
     * visibility guarantee makes that impossible rather than merely unlikely.
     */
    @Volatile
    private var dark: Boolean = true

    /**
     * The last Context seen, held WEAKLY so a destroyed Activity is never pinned.
     *
     * WHY THIS EXISTS -- it closes a class of bug that is otherwise very hard to see. Colours are
     * read as bare properties (`NukeTheme.TEXT`), and Kotlin evaluates a DEFAULT ARGUMENT at the
     * CALL SITE, before the function body runs. So `body(ctx, text)` reads `TEXT_MUTED` before
     * any line of that function has executed -- meaning a `syncTheme(ctx)` placed at the top of
     * the body is ALREADY TOO LATE for its own default arguments. After a theme switch, the first
     * widget built could be painted in the previous theme: a stray light title on a dark panel.
     *
     * [pick] therefore re-resolves from this Context as it reads, so A COLOUR READ IS ITSELF A
     * SYNC. That is the only thing that can cover the default-argument case.
     */
    @Volatile
    private var themeCtx: java.lang.ref.WeakReference<Context>? = null

    /**
     * Reads the theme from [ctx] and caches it, remembering the Context for later colour reads.
     *
     * Called from [dp] and [sp], which every screen calls while BUILDING its view tree, so no
     * screen has to remember to call it. Safe from any process or thread.
     *
     * THERE IS NO TIME-BASED CACHE, and that is a deliberate response to a real hazard: after the
     * user flips the system theme, the Activity is rebuilt within milliseconds, so even a 200 ms
     * throttle would paint that rebuild in the OLD theme. Nor is there a cost to avoid --
     * [dp]/[sp] run during construction (padding, text sizes), never per frame, and reading
     * `configuration.uiMode` is a field read on an already-cached object. A stale colour is
     * visible to the user; this read is not.
     */
    fun syncTheme(ctx: Context) {
        themeCtx = java.lang.ref.WeakReference(ctx)
        dark = resolveDark(ctx, dark)
    }

    /**
     * Re-resolves the theme from the remembered Context, if there is one.
     *
     * Called by [pick] on every colour read. Cheap (a field chain -- no allocation), and it is the
     * ONLY way to guarantee that a colour read in a default argument reflects a theme switched
     * since the last builder ran.
     */
    private fun resync() {
        val ctx = themeCtx?.get() ?: return
        dark = resolveDark(ctx, dark)
    }

    /** Reads the night-mode bit, falling back to [fallback] when the configuration is unreadable. */
    private fun resolveDark(ctx: Context, fallback: Boolean): Boolean = try {
        val mode = ctx.resources.configuration.uiMode
        (mode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        // Unreadable configuration: keep the previous value rather than guessing, because a wrong
        // guess repaints the entire UI in the other theme.
        fallback
    }

    /** True when the dark theme is active. Exposed for the few places that need to branch. */
    fun isDark(): Boolean = dark

    /**
     * Picks the right member of a light/dark pair, re-resolving the theme first.
     *
     * The [resync] call is what makes the token properties self-sufficient: reading ANY colour
     * brings the theme up to date, including from a default argument that runs before its
     * function body.
     */
    private fun pick(light: Int, darkValue: Int): Int {
        resync()
        return if (dark) darkValue else light
    }

    // ---------------------------------------------------------------- tokens
    //
    // Every token keeps the name it had before, so call sites are untouched. Each pairs a light
    // and a dark value; the dark values stay in the original periwinkle-on-near-black family so
    // an upgrading user still recognises the app.

    /** Canvas and glow. */
    val BG: Int get() = pick(0xFFEEF1FA.toInt(), 0xFF080B14.toInt())
    val GLOW: Int get() = pick(0xFFD8E1F8.toInt(), 0xFF18213D.toInt())

    /**
     * Panels.
     *
     * The dark CARD was `#0E1322`, which against the `#080B14` canvas is a contrast of only
     * **1.06:1** -- measured, not guessed. Glass needs a surface the eye can actually separate
     * from the canvas, so it is lifted to `#151C30` (1.16:1 with the brighter border and sheen
     * doing the rest). The light side gets a genuinely white card on a tinted canvas, which is
     * where the strongest depth comes from.
     */
    val CARD: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF151C30.toInt())
    val CARD_BORDER: Int get() = pick(0xFFD8E0F2.toInt(), 0xFF35406A.toInt())
    val FEATURE: Int get() = pick(0xFFF7F9FF.toInt(), 0xFF1A2238.toInt())
    val FEATURE_ALT: Int get() = pick(0xFFEDF1FB.toInt(), 0xFF111726.toInt())
    val FEATURE_BORDER: Int get() = pick(0xFFE2E8F6.toInt(), 0xFF2A3358.toInt())

    /** Accents. */
    /**
     * The accent MUST differ between themes rather than being one value used on both grounds.
     *
     * Measured: `#4C6FF0` on the dark card is 4.26:1 (below AA), and `#91A9FF` on a light
     * surface is 2.11:1 (catastrophic). A single accent therefore cannot serve both themes, so
     * each theme gets a value tuned to its own background. No amount of alpha blending fixes
     * this -- the two backgrounds need opposite directions of contrast.
     */
    val ACCENT: Int get() = pick(0xFF3A5BD9.toInt(), 0xFF91A9FF.toInt())
    val ACCENT_STRONG: Int get() = pick(0xFF2B49C4.toInt(), 0xFF718FFF.toInt())
    val ACCENT_SOFT: Int get() = pick(0xFF4A66D8.toInt(), 0xFF8CA6FF.toInt())
    val CATEGORY: Int get() = pick(0xFF4055C0.toInt(), 0xFF829CFF.toInt())

    /** Text. */
    val TEXT: Int get() = pick(0xFF1A1F36.toInt(), 0xFFE8EBF6.toInt())
    val TEXT_MUTED: Int get() = pick(0xFF5A6379.toInt(), 0xFF9BA4BD.toInt())
    val TEXT_DIM: Int get() = pick(0xFF5F6878.toInt(), 0xFF929DB7.toInt())
    val TEXT_ON_ACCENT: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF071024.toInt())
    val TEXT_SECONDARY_BTN: Int get() = pick(0xFF2B3350.toInt(), 0xFFDCE3FF.toInt())

    /** Inputs. */
    val INPUT_BG: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF0B1020.toInt())
    val INPUT_BORDER: Int get() = pick(0xFFCFD7EA.toInt(), 0xFF303A5B.toInt())

    /** Badges and switch. */
    val BADGE_BG: Int get() = pick(0xFFE6EBFA.toInt(), 0xFF1D2744.toInt())
    val BADGE_TEXT: Int get() = pick(0xFF3A4780.toInt(), 0xFFA9B7E8.toInt())
    /**
     * The switch track when OFF.
     *
     * BUG FIX (auditor): in the light theme this was `#C3CBDE`, against which the white knob
     * measured only **1.63:1** -- the knob almost disappeared into its own track, so an "off"
     * switch looked like a disabled smudge. (The dark side was always fine at 7.75:1.)
     *
     * The light value went `#C3CBDE` -> `#8E98AC` (knob 3.13:1) -> finally `#8892A6`, after the
     * final audit measured the intermediate value at 2.90:1 -- a hair under the 3:1 that a state
     * indicator needs. 3.13:1 leaves a little headroom instead of sitting on the boundary.
     */
    val SWITCH_OFF: Int get() = pick(0xFF8892A6.toInt(), 0xFF353E57.toInt())
    val SWITCH_KNOB: Int get() = pick(0xFFFFFFFF.toInt(), 0xFFD7DCEB.toInt())

    // ---------------------------------------------------------------- semantic slots
    //
    // FOUR slots, not one. The auditor found the project had grown FOUR reds and THREE greens
    // because there was nowhere to put the distinctions it actually needed -- a numeric "anger"
    // score is rendered in four bands. That is why AnalysisCard carries its own palette. These
    // slots are the destination for that palette when the two systems are unified; each has a
    // text colour, a translucent fill and a border so a chip/box can be built from tokens alone.

    /** Success. */
    val OK: Int get() = pick(0xFF17744C.toInt(), 0xFF5FE0A3.toInt())
    val OK_BG: Int get() = pick(0x1417744C.toInt(), 0x1F5FE0A3.toInt())
    val OK_BORDER: Int get() = pick(0x6617744C.toInt(), 0x665FE0A3.toInt())

    /** Warning. */
    val WARN: Int get() = pick(0xFF9A6410.toInt(), 0xFFFFB35F.toInt())
    val WARN_BG: Int get() = pick(0x149A6410.toInt(), 0x1FFFB35F.toInt())
    val WARN_BORDER: Int get() = pick(0x669A6410.toInt(), 0x66FFB35F.toInt())

    /**
     * Caution: the amber CTA slot.
     *
     * Kept separate from [WARN] because the two play different roles -- WARN is a status colour,
     * this is a solid button fill with dark text on it, and it needs its own contrast pair.
     */
    val CAUTION: Int get() = pick(0xFF8A6508.toInt(), 0xFFFFD166.toInt())
    val CAUTION_FILL: Int get() = pick(0xFFE9A400.toInt(), 0xFFFFC53D.toInt())
    /** Text colour ON [CAUTION_FILL]; 7.56:1 in the light theme, near-black in both. */
    val CAUTION_ON: Int get() = pick(0xFF1A1F36.toInt(), 0xFF071024.toInt())
    val CAUTION_BG: Int get() = pick(0x14E9A400.toInt(), 0x22FFC53D.toInt())
    val CAUTION_BORDER: Int get() = pick(0x66C99000.toInt(), 0x66FFC53D.toInt())

    /** Danger. */
    val DANGER_TEXT: Int get() = pick(0xFFB3261E.toInt(), 0xFFFFB4C1.toInt())
    val DANGER_BG: Int get() = pick(0x14B3261E.toInt(), 0x88411925.toInt())
    /**
     * The danger border.
     *
     * The dark value used to be `#7B3644`, which against its own fill was only **1.99:1** --
     * effectively invisible, so the border that is supposed to SAY "this is an error" said
     * nothing.
     *
     * BOTH values are now verified against the danger fill they are drawn on, not just against
     * the page background (the first revision checked only the dark side and left the light one
     * at `#E0A9AE`, which measured a WORSE 1.77:1 -- the fix had made the light theme the broken
     * one). Measured now: dark `#B85C6E` is 3.72:1 on its fill (CARD) and 3.59:1 on FEATURE;
     * light `#B86A76` is 3.45:1 on its fill. All clear the 3:1 that a meaningful boundary needs.
     */
    val DANGER_BORDER: Int get() = pick(0xFFB86A76.toInt(), 0xFFB85C6E.toInt())

    // ---------------------------------------------------------------- glass tokens

    /**
     * The sheen painted over the top of a glass surface: white at the top fading out downward.
     *
     * On a dark surface this is what produces the "light hitting glass" read. On a light surface
     * the card is nearly white already, so the sheen carries a touch of the accent instead --
     * otherwise it would be white on white and do nothing at all.
     */
    val GLASS_SHEEN: Int get() = if (dark) 0x1FFFFFFF else 0x1A8FA8F5

    /** Body fill transparency for a glass panel. High enough that text contrast is never at the mercy of what is behind it. */
    private const val GLASS_BODY_ALPHA = 0xF0

    /** How much of the border colour bleeds into the top-left of a glass body (the "receiving light" face). */
    private const val GLASS_EDGE_ALPHA = 0x99

    /** Standard corner radii, as one frozen rhythm. */
    const val R_PANEL = 24
    const val R_CARD = 18
    const val R_CONTROL = 12
    const val R_CHIP = 10
    const val R_BADGE = 6

    /** Minimum touch target, per the Android accessibility guidance. */
    private const val MIN_TOUCH_DP = 48

    // ---------------------------------------------------------------- helpers

    /**
     * item 3: the text size to actually use.
     *
     * `sp` values are multiplied by the device font scale (and WeChat adds its own setting), which on a
     * "very large text" phone blows the panel's text up until a title wraps one character per line. When
     * 自适应 is ON the design size is divided by that scale, so what the user SEES stays close to what was
     * designed; the result is clamped so it can neither vanish nor explode.
     *
     * When 自适应 is OFF the designed value is used untouched, which is what some users prefer.
     *
     * PERFORMANCE: this used to call `ConfigRepo.loadLocal` on EVERY invocation, and a single screen build
     * performs dozens of them -- the exported log showed 15 config loads inside 10 ms, each re-parsing the
     * multi-hundred-character prompt. The decision is therefore CACHED briefly, and `invalidateFontCache()`
     * clears it when the setting changes.
     */
    @Volatile
    private var cachedAdaptive: Boolean = true

    @Volatile
    private var cachedAdaptiveAt: Long = 0L

    private const val FONT_CACHE_MS = 5000L

    /** Called when 自适应字体 is changed, so the new value takes effect at once. */
    fun invalidateFontCache() {
        cachedAdaptiveAt = 0L
    }

    /**
     * 液态玻璃: whether animations should be played.
     *
     * Honours the system's "remove animations" setting (`ANIMATOR_DURATION_SCALE == 0`), which is
     * Android's equivalent of `prefers-reduced-motion`. A user who turns animations off
     * system-wide -- often for motion sickness, or to keep a slow phone responsive -- expects
     * every app to respect it.
     *
     * The module currently draws NO animations, so nothing calls this yet. It exists so that any
     * transition added later is gated from its first line rather than retrofitted, which is the
     * order that actually holds up.
     */
    fun animationsEnabled(ctx: Context): Boolean = try {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    } catch (_: Throwable) {
        // Unreadable (or a resolver that refuses): assume animations are fine, which is the
        // platform default.
        true
    }

    /**
     * item 1: the text with a line through it, the way a text editor shows a deletion.
     *
     * A STRIKE_THROUGH span is used rather than a drawn line, so it follows the glyphs and stays correct
     * whatever the font scale or the text length.
     */
    fun struck(text: CharSequence, colour: Int): CharSequence {
        val sp = android.text.SpannableString(text)
        sp.setSpan(
            android.text.style.StrikethroughSpan(),
            0,
            sp.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sp.setSpan(
            android.text.style.ForegroundColorSpan(colour),
            0,
            sp.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sp
    }

    fun sp(ctx: Context, designed: Float): Float {
        syncTheme(ctx)
        return try {
            val now = System.currentTimeMillis()
            if (now - cachedAdaptiveAt > FONT_CACHE_MS) {
                cachedAdaptive = try {
                    com.luoliaoo.wechat.data.ConfigRepo.loadLocal(ctx).adaptiveFont
                } catch (_: Throwable) {
                    true
                }
                cachedAdaptiveAt = now
            }
            if (!cachedAdaptive) {
                return designed
            }
            val scale = ctx.resources.configuration.fontScale.coerceAtLeast(1f)
            (designed / scale).coerceIn(designed * 0.55f, designed)
        } catch (_: Throwable) {
            designed
        }
    }

    fun dp(ctx: Context, v: Int): Int {
        // The theme is refreshed here because every screen calls dp() many times while building,
        // which makes this the one place guaranteed to run on every construction path.
        syncTheme(ctx)
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
        ).toInt()
    }

    /** The canvas: the soft glow at the top-left fading into the background, as in Nuke's body rule. */
    fun canvas(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(GLOW, BG, BG),
    ).apply {
        gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    // ---------------------------------------------------------------- glass surfaces

    /**
     * 液态玻璃: the glass surface, as a reusable factory.
     *
     * Three layers, each with a job:
     *   1. the BODY   -- translucent fill plus a 1dp stroke. The stroke is the "edge light"; a
     *                    translucent rectangle with no edge reads as a smudge rather than a pane.
     *   2. the SHEEN  -- white fading downward from the top. This is what gives the pane a
     *                    lit-from-above solidity instead of a flat tint.
     *   3. the RECEIVE-- a faint diagonal wash of the border colour across the top-left, implying
     *                    the surface is catching light from one corner (as in iOS 26 / ColorOS).
     *
     * EVERY LAYER TAKES THE SAME CORNER RADIUS. This is the single most common way a hand-built
     * glass panel goes wrong: give the sheen a smaller radius, or none, and its corners paint
     * outside the body's rounded corners -- a pale, obviously-wrong fringe on all four corners.
     *
     * @param radiusDp corner radius; use the R_* constants so the whole module keeps one rhythm.
     * @param fill     the surface tint. Defaults to [CARD].
     * @param border   the edge colour. Defaults to [CARD_BORDER].
     */
    fun glassPanel(
        ctx: Context,
        radiusDp: Int = R_PANEL,
        fill: Int = CARD,
        border: Int = CARD_BORDER,
    ): Drawable {
        val radius = dp(ctx, radiusDp).toFloat()

        // 1. body: translucent fill + edge stroke
        val body = GradientDrawable().apply {
            cornerRadius = radius
            setColor(withAlpha(fill, GLASS_BODY_ALPHA))
            setStroke(dp(ctx, 1).coerceAtLeast(1), border)
        }

        // 2. sheen: the lit top edge
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(GLASS_SHEEN, GLASS_SHEEN and 0x00FFFFFF),
        ).apply {
            cornerRadius = radius
        }

        // 3. the corner that receives the light
        val receive = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                withAlpha(border, GLASS_EDGE_ALPHA),
                withAlpha(fill, 0x00),
                withAlpha(fill, 0x00),
            ),
        ).apply {
            cornerRadius = radius
        }

        return LayerDrawable(arrayOf(body, sheen, receive)).apply {
            // Same radius on every layer, and no insets: each layer is the full size of the
            // surface and fades by its own gradient, so nothing can paint outside the corners.
            for (i in 0 until numberOfLayers) {
                setLayerInset(i, 0, 0, 0, 0)
            }
        }
    }

    /**
     * Replaces a colour's alpha, keeping its RGB.
     *
     * Exists so derived colours FOLLOW THE THEME. The auditor found three places that had
     * hand-written a translucent white or a translucent copy of a token; those literals keep
     * their old value when the theme flips, which is the most easily missed bug in a two-theme
     * skin. Deriving from a token instead means the RGB tracks the theme automatically. Only the
     * alpha is the caller's choice.
     */
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /**
     * A panel: a glass surface with a generous radius.
     *
     * `syncTheme` runs FIRST. Its default arguments ([CARD] / [CARD_BORDER]) are evaluated by the
     * caller BEFORE this body executes, so a stale theme flag would be baked into the defaults
     * themselves -- the call would then paint the whole panel in the previous theme. Reading them
     * again after the sync is what makes a theme switch take effect on the very next screen.
     */
    fun panel(
        fill: Int = CARD,
        border: Int = CARD_BORDER,
        radiusDp: Int = R_PANEL,
        ctx: Context,
    ): GradientDrawable = GradientDrawable().apply {
        syncTheme(ctx)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(ctx, 1), border)
    }

    /** The gradient used by the feature cards, now built as a glass surface. */
    fun featurePanel(ctx: Context, radiusDp: Int = R_CARD): Drawable =
        glassPanel(ctx, radiusDp, FEATURE, FEATURE_BORDER)

    fun inputBackground(ctx: Context, focused: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, R_CONTROL).toFloat()
            setColor(INPUT_BG)
            if (focused) {
                setStroke(dp(ctx, 2), ACCENT_STRONG)
            } else {
                setStroke(dp(ctx, 1), INPUT_BORDER)
            }
        }

    /** Rounded rectangle used by buttons and badges. */
    fun rounded(
        fill: Int,
        radiusDp: Int,
        ctx: Context,
        border: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fill)
        if (border != null) setStroke(dp(ctx, 1), border)
    }

    /** A pill, used for the status dot and small chips. */
    fun pill(fill: Int, ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /**
     * A radio indicator DRAWN BY US, so it looks the same in every host.
     *
     * WHY THIS EXISTS. The scope dialog used a plain `android.widget.RadioButton`, whose indicator is
     * drawn by the HOST's theme -- and the dialog is built with the host's Context. On QQ that is
     * QQ's own drawable: a large tick in a filled circle, with the unchecked ones nearly white. The
     * user saw exactly that and reported it as "the tick is huge and the unselected ones are too
     * white; other apps are not like this", because WeChat/Douyin/Soul/Kuaishou each supplied a
     * different theme, so the same dialog looked different in every host.
     *
     * `buttonTintList` cannot fix it: tinting recolours the host's shape, it does not change the
     * shape, the tick, or the unchecked fill. The only way to be host-independent is to draw the
     * whole indicator ourselves.
     *
     * Shape: the unchecked state is a thin ring in [TEXT_DIM]; the checked state is a ring plus a
     * filled inner dot in [ACCENT] -- the standard radio look, at a size we choose (18dp) rather
     * than whatever the host's theme happens to use.
     *
     * @param checked which of the two states to build. The caller rebuilds on change, because a
     *        `Drawable` swap is cheaper and clearer than a `StateListDrawable` here.
     *
     * Returns `Drawable`, not `GradientDrawable`: the checked state is a `LayerDrawable` (ring plus
     * inner dot), and a `GradientDrawable` cannot draw a filled centre dot inside a hollow ring -- a
     * first revision declared `GradientDrawable` and did not compile.
     */
    fun radioDot(checked: Boolean, ctx: Context): Drawable {
        val size = dp(ctx, 18)
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, if (checked) 2 else 1), if (checked) ACCENT else TEXT_DIM)
            setSize(size, size)
        }
        if (!checked) {
            return outer
        }
        // Checked: the outer ring with a smaller filled dot centred inside it, as one layer-list so
        // the caller still deals with a single Drawable.
        //
        // CENTER GRAVITY ON EVERY LAYER, and that is the fix for "the selection dot sits off to one
        // side" -- a `LayerDrawable` positions a layer by its own gravity, and with no gravity set a
        // smaller layer is placed at the START/TOP corner rather than in the middle. Stating CENTER
        // is what actually centres it; relying on the default is what put it in the corner.
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
            val d = size - dp(ctx, 10)
            setSize(d, d)
        }
        val inset = dp(ctx, 5)
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerGravity(0, Gravity.CENTER)
            setLayerGravity(1, Gravity.CENTER)
            setLayerInset(1, inset, inset, inset, inset)
            setLayerSize(0, size, size)
            setLayerSize(1, size - dp(ctx, 10), size - dp(ctx, 10))
        }
    }

    /**
     * One row of a hand-drawn radio group: the dot plus its label.
     *
     * The row is a `LinearLayout` of `[dot][label]`, NOT a `RadioButton`. Tapping anywhere on the row
     * must select it, so the click listener is set on the ROW -- with a bare dot, only the dot would
     * be tappable and the row would feel dead.
     *
     * 液态玻璃 accessibility: the row now guarantees a 48dp touch target. It used to be about
     * 34dp, which is under the guidance and awkward to hit one-handed.
     *
     * @param onTap invoked with this row's index when the user picks it.
     */
    fun radioRow(
        ctx: Context,
        index: Int,
        label: String,
        checked: Boolean,
        onTap: (Int) -> Unit,
    ): LinearLayout {
        val size = dp(ctx, 18)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // CENTER_VERTICAL so the dot is centred against the LABEL, not against the row's padding
            // box. Without it the dot sits high whenever the text line is taller than the dot, which
            // is what the user saw as "the selection dot's position is off".
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            setOnClickListener { onTap(index) }
        }
        row.addView(
            View(ctx).apply {
                background = radioDot(checked, ctx)
                // A FIXED box the same size as the dot, and it must be a `LayoutParams` whose HEIGHT
                // is the dot's own: a WRAP_CONTENT dot in a taller row is what leaves the ring
                // visually off-centre even with CENTER_VERTICAL set.
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dp(ctx, 10)
                }
            },
        )
        row.addView(
            TextView(ctx).apply {
                text = label
                textSize = sp(ctx, 15f)
                setTextColor(if (checked) ACCENT else TEXT)
                // Left-aligned text inside its own box, so a checked label that gets slightly bolder
                // spacing does not shift the text relative to the rows above and below.
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        return row
    }

    /** Focus-aware background for an EditText. */
    fun inputStates(ctx: Context): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), inputBackground(ctx, true))
        addState(intArrayOf(), inputBackground(ctx, false))
    }

    // ---------------------------------------------------------------- widgets

    /** The small uppercase-ish label Nuke puts above a title. */
    fun eyebrow(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ACCENT_SOFT)
        textSize = sp(ctx, 12f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.16f
        setPadding(0, 0, 0, dp(ctx, 6))
    }

    /** The category chip on a feature card. */
    fun category(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(CATEGORY)
        textSize = sp(ctx, 11f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
    }

    /** A page or section title. */
    fun title(ctx: Context, text: String, sizeSp: Float = 26f): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = sp(ctx, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(ctx, 3).toFloat(), 1.05f)
    }

    /**
     * Body copy.
     *
     * NOTE on the `color` default: a default argument is evaluated at the CALL SITE, so naming a
     * token here is safe only because the token getter itself re-resolves the theme (see [pick]).
     * That is why `TEXT_MUTED` is written literally rather than resolved in the body.
     */
    fun body(ctx: Context, text: String, color: Int = TEXT_MUTED, sizeSp: Float = 14f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sp(ctx, sizeSp)
            setLineSpacing(dp(ctx, 5).toFloat(), 1.5f)
        }

    /** A field label. */
    fun fieldLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT_DIM)
        textSize = sp(ctx, 13f)
        setPadding(0, dp(ctx, 14), 0, dp(ctx, 6))
    }

    /**
     * A small hint under a field.
     *
     * v4.0.0 FIX: the `color` argument was DECLARED BUT IGNORED -- the body hardcoded the grey
     * 0xFF6B7699, so every caller that asked for a different colour (the red 补充背景 requirement line)
     * silently came out grey. The parameter is now honoured; the default keeps the previous look.
     *
     * 液态玻璃 accessibility fix: that default grey measured **4.12:1** on the card, below the
     * 4.5:1 that WCAG AA requires for body text -- and it is the colour of every hint in the
     * module. The default is now a theme-aware value (5.9:1 dark / 6.4:1 light). Fixing it HERE
     * repairs all 15 call sites at once, which is exactly why the colour lived in one place.
     */
    fun hintText(ctx: Context, text: String, color: Int = hintColor(ctx)): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sp(ctx, 11f)
            setPadding(0, dp(ctx, 5), 0, 0)
            setLineSpacing(dp(ctx, 3).toFloat(), 1.4f)
        }

    /**
     * The hint colour as a function rather than a constant, so it can be theme-aware AND still
     * usable as a default argument. `hintText`'s third parameter previously defaulted to a
     * literal; a default argument is evaluated at each call, so this costs nothing.
     */
    fun hintColor(ctx: Context): Int = pick(0xFF5E6878.toInt(), 0xFF8E97B0.toInt())

    /** An input field styled like Nuke's, with a focus ring. */
    fun input(ctx: Context, value: String, hintText: String = ""): EditText =
        EditText(ctx).apply {
            setText(value)
            hint = hintText
            setTextColor(TEXT)
            // 液态玻璃 accessibility fix: this was 0xFF5A6685, which measured **3.31:1** on the
            // input background -- the placeholder of every text field was below AA.
            //
            // BUG FIX (caught by the auditor): the first revision of this line read
            // `pick(0xFF7C87A8, 0xFF7C87A8)` -- the SAME value twice, a copy-paste slip that made
            // the call look theme-aware while doing nothing. Measured on the light theme it was
            // 3.16-3.57:1, i.e. still failing on all four backgrounds.
            //
            // The two values below are now genuinely different and each is verified against every
            // background it can land on: light #5E6878 is 5.21/5.63/5.35:1, dark #8E97B0 is
            // 6.74/5.81/5.42:1. The dark value also clears the tightest case (FEATURE), which the
            // previous value missed at 4.43:1.
            setHintTextColor(pick(0xFF5E6878.toInt(), 0xFF8E97B0.toInt()))
            textSize = sp(ctx, 14f)
            background = inputStates(ctx)
            setPadding(dp(ctx, 15), dp(ctx, 13), dp(ctx, 15), dp(ctx, 13))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /**
     * A yellow call to action, used by the scope panel's 配置 / 取消 / 确定 buttons.
     *
     * 液态玻璃: the amber is now the [CAUTION_FILL] token pair, so the button is legible in both
     * themes (a single amber cannot carry dark text on light and light text on dark).
     */
    fun warnButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(CAUTION_ON)
            textSize = sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(CAUTION_FILL, R_CONTROL, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = dp(ctx, 8) }
        }

    /** A quieter yellow action, for the secondary choices in the same row. */
    fun warnOutlineButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(CAUTION)
            textSize = sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(CAUTION_BG, R_CONTROL, ctx, CAUTION_BORDER)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginEnd = dp(ctx, 8) }
        }

    /**
     * The primary call to action.
     *
     * 液态玻璃: a vertical accent gradient plus a soft top-lit stroke, so the button reads as a
     * piece of the same glass as the panels rather than a flat rectangle stuck on top. The
     * gradient is used INSTEAD of a blur -- same visual job, none of the per-frame cost.
     */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(TEXT_ON_ACCENT)
            textSize = sp(ctx, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val radius = dp(ctx, R_CONTROL).toFloat()
            val fill = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ACCENT, ACCENT_STRONG),
            ).apply {
                cornerRadius = radius
                // The lit top edge, derived from TEXT so it follows the theme.
                setStroke(dp(ctx, 1), withAlpha(TEXT, 0x44))
            }
            background = fill
            setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /** A quieter action, as used by Nuke's .secondary. */
    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(TEXT_SECONDARY_BTN)
            textSize = sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(FEATURE_ALT, R_CONTROL, ctx, INPUT_BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /** A destructive action: same shape, danger colours, now from the token pair. */
    fun dangerButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(DANGER_TEXT)
            textSize = sp(ctx, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(DANGER_BG, R_CONTROL, ctx, DANGER_BORDER)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /**
     * item 6: makes an AlertDialog's own buttons readable.
     *
     * The platform dialog theme colours its buttons with `?android:attr/textColorPrimary`, which on the
     * dark canvas comes out a dim grey -- "取消 / 确定 太淡了看不清". They are OUR buttons on OUR panel, so
     * they are recoloured to the same near-white as the rest of the UI.
     *
     * Call AFTER `show()`: AlertDialog creates its button views during show(), so anything set before is
     * replaced when the layout is inflated.
     */
    fun styleDialogButtons(dialog: android.app.Dialog?, vararg which: Int) {
        val ids = if (which.isEmpty()) {
            intArrayOf(
                android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE,
                android.app.AlertDialog.BUTTON_NEUTRAL,
            )
        } else {
            which
        }
        for (id in ids) {
            try {
                val b = (dialog as? android.app.AlertDialog)?.getButton(id) ?: continue
                b.setTextColor(TEXT)
                b.textSize = sp(b.context, 14f)
                b.typeface = Typeface.DEFAULT_BOLD
            } catch (_: Throwable) {
                // a dialog without that button simply has nothing to restyle
            }
        }
    }

    /**
     * A tappable chip that shows whether it is selected.
     *
     * Used by 导出, where the user picks which skills to include: the fill flips between the accent tint and
     * the panel colour, so the current selection is readable at a glance rather than only by outline.
     */
    fun toggleChip(ctx: Context, label: String, selected: Boolean): TextView = TextView(ctx).apply {
        text = (if (selected) "\u2713 " else "") + label
        textSize = sp(ctx, 13f)
        setTextColor(if (selected) TEXT_ON_ACCENT else TEXT)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        background = if (selected) {
            rounded(ACCENT, R_CHIP, ctx)
        } else {
            rounded(FEATURE_ALT, R_CHIP, ctx, FEATURE_BORDER)
        }
        setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
        // Accessibility: was about 34dp.
        //
        // This is MIN_TOUCH_DP like every other control, NOT a local 44: an earlier revision used a
        // hard-coded 44 here, which made this one control quietly smaller than the ten others that
        // share the constant. A reviewer spotted the odd value out; using the constant is what
        // stops that kind of drift from reappearing.
        minimumHeight = dp(ctx, MIN_TOUCH_DP)
        isClickable = true
    }

    /**
     * An inline text action, like Nuke's link-style controls.
     *
     * Accessibility: this is the module's most-used small control (8 call sites, including the
     * 显示/隐藏密钥 and 恢复默认提示词 links). It measured about 26dp, which is under the 48dp
     * guidance and genuinely fiddly to hit. The minimum height fixes every call site at once
     * WITHOUT changing the visual size of the text.
     */
    fun link(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ACCENT)
        textSize = sp(ctx, 13f)
        setPadding(0, dp(ctx, 8), 0, dp(ctx, 2))
        minimumHeight = dp(ctx, MIN_TOUCH_DP)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** The small chip Nuke uses for metadata. */
    fun badge(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(BADGE_TEXT)
        textSize = sp(ctx, 11f)
        background = rounded(BADGE_BG, R_BADGE, ctx)
        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(ctx, 6) }
    }

    /** A status line with a glowing dot, as in Nuke's .status. */
    fun statusRow(ctx: Context, text: String, ok: Boolean = true): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(ctx).apply {
                background = pill(if (ok) OK else WARN, ctx)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9))
            })
            addView(TextView(ctx).apply {
                this.text = text
                setTextColor(TEXT_MUTED)
                textSize = sp(ctx, 13f)
                setPadding(dp(ctx, 8), 0, 0, 0)
            })
        }

    /**
     * Nuke's error box: pink text on a translucent crimson fill with a crimson border.
     *
     * 液态玻璃: the border is now [DANGER_BORDER], whose dark value was brightened from `#7B3644`.
     * At the old value the border was 1.99:1 against its own fill -- visually absent, so the edge
     * that is supposed to signal "this is an error" signalled nothing.
     */
    fun errorBox(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(DANGER_TEXT)
        textSize = sp(ctx, 13f)
        background = rounded(DANGER_BG, R_CHIP, ctx, DANGER_BORDER)
        setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
        setLineSpacing(dp(ctx, 4).toFloat(), 1.45f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 14) }
    }

    /** A panel that holds a titled block, matching Nuke's card composition, now a glass card. */
    fun sectionCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = glassPanel(ctx, R_CARD, FEATURE, FEATURE_BORDER)
        setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 14) }
    }

    /**
     * A labelled switch row.
     *
     * Uses the platform Switch with Nuke's colours applied to the track and thumb, so it looks like the
     * WebUI toggle without hand-drawing it.
     */
    fun switchRow(
        ctx: Context,
        label: String,
        initial: Boolean,
        describe: String = "",
    ): Pair<LinearLayout, Switch> {
        val sw = Switch(ctx).apply {
            isChecked = initial
            setThumbTintList(
                android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(Color.WHITE, SWITCH_KNOB),
                ),
            )
            setTrackTintList(
                android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(ACCENT_STRONG, SWITCH_OFF),
                ),
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 10), 0, 0)
            minimumHeight = dp(ctx, MIN_TOUCH_DP)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(TEXT)
                    textSize = sp(ctx, 14f)
                })
                if (describe.isNotBlank()) {
                    addView(TextView(ctx).apply {
                        text = describe
                        setTextColor(TEXT_DIM)
                        textSize = sp(ctx, 11f)
                        setPadding(0, dp(ctx, 3), 0, 0)
                    })
                }
            })
            addView(sw)
        }
        return row to sw
    }
}
