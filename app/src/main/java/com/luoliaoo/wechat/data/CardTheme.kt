package com.luoliaoo.wechat.data

/**
 * Card appearance (item 3): accent colour and font scale, both user-configurable.
 *
 * @param accent  ARGB accent colour used for bars, badges and buttons
 * @param fontSp  base body font size in sp
 */
data class CardTheme(
    val accent: Int = DEFAULT_ACCENT,
    val fontSp: Float = DEFAULT_FONT_SP,
) {
    companion object {
        const val DEFAULT_ACCENT = 0xFF4C6FF0.toInt()
        const val DEFAULT_FONT_SP = 13f

        /**
         * Preset colours offered in the config panel.
         *
         * v5.0.0: the green was named 「微信绿」. With two hosts a preset must not be named after one of
         * them -- a Douyin user was being offered a colour branded with a different app. The name is now
         * host-neutral while the COLOUR is unchanged, so any config already storing this accent keeps
         * rendering exactly as before (the value, not the label, is what is persisted).
         */
        val PRESETS: List<Pair<String, Int>> = listOf(
            "默认蓝" to 0xFF4C6FF0.toInt(),
            "绿" to 0xFF07C160.toInt(),
            "紫" to 0xFF7B5BF0.toInt(),
            "橙" to 0xFFE08A1E.toInt(),
            "红" to 0xFFD64545.toInt(),
            "青" to 0xFF12B5B0.toInt(),
            "粉" to 0xFFE8608F.toInt(),
            "石墨灰" to 0xFF5A5A66.toInt(),
        )

        /** Parses "#RRGGBB" / "RRGGBB" / "#AARRGGBB"; falls back to the default accent. */
        fun parseColor(s: String?, fallback: Int = DEFAULT_ACCENT): Int {
            val v = s?.trim()?.removePrefix("#") ?: return fallback
            return try {
                when (v.length) {
                    6 -> (0xFF000000.toInt()) or v.toLong(16).toInt()
                    8 -> v.toLong(16).toInt()
                    else -> fallback
                }
            } catch (_: Throwable) {
                fallback
            }
        }

        fun toHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)
    }
}
