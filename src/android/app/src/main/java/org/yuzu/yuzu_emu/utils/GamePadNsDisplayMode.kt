// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/** Whole-device 16:9 mode, expressed in landscape and natural portrait coordinates. */
object GamePadNsDisplayMode {
    const val WIDTH = 3840
    const val HEIGHT = 2160
    const val FORCED_WIDTH = HEIGHT
    const val FORCED_HEIGHT = WIDTH
    private const val LEGACY_WIDTH = 2304
    private const val LEGACY_HEIGHT = 1296

    fun matches(width: Int, height: Int): Boolean =
        (width == WIDTH && height == HEIGHT) ||
            (width == HEIGHT && height == WIDTH)

    fun matchesLegacy(width: Int, height: Int): Boolean =
        (width == LEGACY_WIDTH && height == LEGACY_HEIGHT) ||
            (width == LEGACY_HEIGHT && height == LEGACY_WIDTH)

    // Keep the accepted V1.7 controller proportions in the larger 16:9 canvas.
    // Rendering and inverse input mapping must use the same scale and translation.
    fun controllerScale(width: Int, height: Int): Float =
        if (matches(width, height)) WIDTH.toFloat() / GamePadNsWindowPolicy.NATIVE_WIDTH else 1f

    fun controllerTranslationY(width: Int, height: Int): Float = when {
        matches(width, height) ->
            (HEIGHT - GamePadNsWindowPolicy.NATIVE_HEIGHT * controllerScale(width, height)) / 2f
        matchesLegacy(width, height) -> (LEGACY_HEIGHT - GamePadNsWindowPolicy.NATIVE_HEIGHT) / 2f
        else -> 0f
    }
}
