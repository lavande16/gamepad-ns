// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract

/**
 * A lightweight 16:9 application stage.
 *
 * The view chooses the largest 16:9 rectangle allowed by its parent and measures all children
 * normally inside that rectangle. It intentionally does not scale or rasterize child content, so
 * text, touch targets, and rendering surfaces continue to operate at the device's native density.
 */
class GameFoldViewportLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val viewport = GameFoldViewportCalculator.calculate(availableWidth, availableHeight)

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(viewport.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(viewport.height, MeasureSpec.EXACTLY)
        )
    }

}
