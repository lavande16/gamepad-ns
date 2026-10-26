// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Owns the explicit GameFold orientation choice.
 *
 * The choice is deliberately independent from the device sensor: GameFold is a physical shell
 * whose controller coordinate system is selected by the user from the Quick Start page.
 */
object GameFoldOrientation {
    private const val PREF_PORTRAIT_MODE = "gamefold_portrait_mode"

    fun isPortrait(context: Context): Boolean = false

    fun toggle(context: Context): Boolean = false

    fun apply(activity: Activity) {
        val requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (activity.requestedOrientation != requested) {
            activity.requestedOrientation = requested
        }
    }
}
