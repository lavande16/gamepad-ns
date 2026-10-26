// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object LiteModePreferences {
    private const val PREF_MAINTENANCE_MODE = "gamefold_lite_maintenance_mode"

    fun isMaintenanceModeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_MAINTENANCE_MODE, false)

    fun toggleMaintenanceMode(context: Context): Boolean {
        val enabled = !isMaintenanceModeEnabled(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(PREF_MAINTENANCE_MODE, enabled)
        }
        return enabled
    }
}
