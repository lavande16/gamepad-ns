// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Preserves the supplied GameFold cartridge artwork ratio (343 wide by 392 high). */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    var widthScale: Float = 1f
        set(value) {
            val normalized = value.coerceIn(0.5f, 1f)
            if (field == normalized) return
            field = normalized
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = (measuredWidth * widthScale).roundToInt()
        if (width == 0) return

        val desiredHeight = (width * CARTRIDGE_HEIGHT / CARTRIDGE_WIDTH).roundToInt()
        val height = View.resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
    }

    private companion object {
        private const val CARTRIDGE_WIDTH = 343f
        private const val CARTRIDGE_HEIGHT = 392f
    }
}
