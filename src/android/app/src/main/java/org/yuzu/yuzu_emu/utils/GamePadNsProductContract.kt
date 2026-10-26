// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

/** Product boundary for the independent GamePad NS APK. */
object GamePadNsProductContract {
    const val APPLICATION_ID = "com.gamepad.gamepad"
    const val VERSION_NAME = "1.8"
    const val VERSION_CODE = 10030
    const val EXTERNAL_ROOT_NAME = "GamePadNS"
    const val DEFAULTS_MIGRATION_VERSION = 2

    /** GamePad NS is a landscape-only full-screen controller shell. */
    const val STRICT_GAMEPAD_SHELL = true
}
