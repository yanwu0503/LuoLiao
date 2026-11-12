package com.luoliaoo.wechat.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView

/**
 * A ScrollView that never grows beyond `cap` pixels.
 *
 * A plain ScrollView inside a LinearLayout expands to the full content height, which pushed the panels
 * off screen when a long chat was read (item 1) or when a card was long. Capping the measured height
 * keeps the panel a sensible size and makes the content scroll instead.
 *
 * Public, so both the floating panel and the scope dialog can use it.
 */
class MaxHeightScrollView(context: Context, private val cap: Int) : ScrollView(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (cap > 0) cap else Int.MAX_VALUE
        val spec = View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
        if (measuredHeight > limit) {
            setMeasuredDimension(measuredWidth, limit)
        }
    }
}
