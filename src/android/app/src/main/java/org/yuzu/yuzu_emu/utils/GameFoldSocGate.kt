// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.os.Build

object GameFoldSocGate {
    private val mediatekModelPattern = Regex(
        "(^|[^A-Z0-9])MT\\d{3,}[A-Z0-9]*($|[^A-Z0-9])"
    )

    fun isUnsupportedMtkDevice(): Boolean {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            ""
        }
        return isMediaTek(socModel, Build.HARDWARE)
    }

    fun isMediaTek(socModel: String?, hardware: String?): Boolean {
        return sequenceOf(socModel, hardware)
            .filterNotNull()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .any { value ->
                value.contains("MEDIATEK") ||
                    value.contains("DIMENSITY") ||
                    value.contains("MTK") ||
                    mediatekModelPattern.containsMatchIn(value)
            }
    }
}
