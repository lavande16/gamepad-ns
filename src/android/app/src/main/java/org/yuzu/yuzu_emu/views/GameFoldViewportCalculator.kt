// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import kotlin.math.roundToInt

/** Android-free reference calculator for viewport tests and tooling. */
object GameFoldViewportCalculator {
    const val ASPECT_RATIO = 16f / 9f

    data class ViewportSize(val width: Int, val height: Int)

    fun calculate(availableWidth: Int, availableHeight: Int): ViewportSize {
        if (availableWidth <= 0 || availableHeight <= 0) {
            return ViewportSize(
                availableWidth.coerceAtLeast(0),
                availableHeight.coerceAtLeast(0)
            )
        }
        return if (availableWidth.toFloat() / availableHeight > ASPECT_RATIO) {
            ViewportSize((availableHeight * ASPECT_RATIO).roundToInt(), availableHeight)
        } else {
            ViewportSize(availableWidth, (availableWidth / ASPECT_RATIO).roundToInt())
        }
    }
}
