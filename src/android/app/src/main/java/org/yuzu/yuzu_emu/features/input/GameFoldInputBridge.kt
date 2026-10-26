// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.features.input.model.NpadStyleIndex
import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldControllerListener
import org.yuzu.yuzu_emu.views.GameFoldStick

/** Direct bridge from the permanent GameFold shell to the emulator virtual controller. */
class GameFoldInputBridge(
    private val onHomePressed: () -> Unit = {},
    private val onConfirmWhilePaused: () -> Boolean = { false }
) : GameFoldControllerListener {
    private var confirmConsumedByPausedUi = false

    override fun onButtonChanged(button: GameFoldButton, pressed: Boolean) {
        if (button == GameFoldButton.HOME) {
            if (pressed) onHomePressed()
            return
        }
        if (button == GameFoldButton.B) {
            if (pressed && onConfirmWhilePaused()) {
                confirmConsumedByPausedUi = true
                return
            }
            if (!pressed && confirmConsumedByPausedUi) {
                confirmConsumedByPausedUi = false
                return
            }
        }
        NativeInput.onOverlayButtonEvent(
            activePlayerIndex(),
            button.toNativeButton(),
            if (pressed) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED
        )
    }

    override fun onStickChanged(stick: GameFoldStick, x: Float, y: Float) {
        NativeInput.onOverlayJoystickEvent(
            activePlayerIndex(),
            if (stick == GameFoldStick.LEFT) NativeAnalog.LStick else NativeAnalog.RStick,
            x,
            -y
        )
    }

    private fun activePlayerIndex(): Int = when (NativeInput.getStyleIndex(0)) {
        NpadStyleIndex.Handheld -> NativeInput.ConsoleDevice
        else -> NativeInput.Player1Device
    }

    private fun GameFoldButton.toNativeButton(): NativeButton = when (this) {
        GameFoldButton.DPAD_UP -> NativeButton.DUp
        GameFoldButton.DPAD_RIGHT -> NativeButton.DRight
        GameFoldButton.DPAD_DOWN -> NativeButton.DDown
        GameFoldButton.DPAD_LEFT -> NativeButton.DLeft
        GameFoldButton.X -> NativeButton.X
        GameFoldButton.A -> NativeButton.A
        GameFoldButton.B -> NativeButton.B
        GameFoldButton.Y -> NativeButton.Y
        GameFoldButton.MINUS -> NativeButton.Minus
        GameFoldButton.PLUS -> NativeButton.Plus
        GameFoldButton.L -> NativeButton.L
        GameFoldButton.R -> NativeButton.R
        GameFoldButton.ZL -> NativeButton.ZL
        GameFoldButton.ZR -> NativeButton.ZR
        GameFoldButton.L3 -> NativeButton.LStick
        GameFoldButton.R3 -> NativeButton.RStick
        GameFoldButton.HOME -> error("HOME is handled before native button mapping")
    }
}
