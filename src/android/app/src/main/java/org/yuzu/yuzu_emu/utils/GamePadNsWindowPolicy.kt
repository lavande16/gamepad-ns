// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

/** Supported GamePad NS window modes, including the whole-device 3840x2160 override. */
object GamePadNsWindowPolicy {
    const val NATIVE_WIDTH = 2304
    const val NATIVE_HEIGHT = 1440
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    private const val MIN_SMALLEST_WIDTH_DP = 576

    fun isSupported(context: Context, width: Int, height: Int): Boolean {
        val activity = context.findActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInMultiWindowMode == true) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity?.isInPictureInPictureMode == true
        ) {
            return false
        }
        val config = context.resources.configuration
        if (width <= 0 || height <= 0) return false
        // Android may report the same whole-device override in the Pad's natural portrait
        // coordinates while the activity is being re-laid out for landscape. Keep both
        // forms in the early gate so the resolution switch cannot fall into the lock screen.
        val approvedResolution = (width == NATIVE_WIDTH && height == NATIVE_HEIGHT)
        // Keep older overrides usable after upgrading so the same toggle can restore them.
        // Explicit supported modes do not rely on the physical display's dp threshold.
        return GamePadNsDisplayMode.matches(width, height) ||
            GamePadNsDisplayMode.matchesLegacy(width, height) || isFullHd(width, height) ||
            (approvedResolution && config.smallestScreenWidthDp >= MIN_SMALLEST_WIDTH_DP)
    }

    fun isFullHd(width: Int, height: Int): Boolean =
        (width == FULL_HD_WIDTH && height == FULL_HD_HEIGHT) ||
            (width == FULL_HD_HEIGHT && height == FULL_HD_WIDTH)

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
