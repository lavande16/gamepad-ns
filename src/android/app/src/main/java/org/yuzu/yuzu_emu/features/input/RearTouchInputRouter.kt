// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldControllerListener

/**
 * Process-local bridge from the secondary-display touch activity to the active emulator input
 * bridge. Both activities share Android's main looper, so the native input callbacks stay on the
 * same thread as the normal GameFold controller surface.
 */
object RearTouchInputRouter {
    private val lock = Any()
    private var listener: GameFoldControllerListener? = null
    private val pressedButtons = linkedSetOf<GameFoldButton>()

    fun register(next: GameFoldControllerListener) {
        val previous: GameFoldControllerListener?
        synchronized(lock) {
            previous = listener
            listener = next
            pressedButtons.clear()
        }
        previous?.let { old ->
            old.onButtonChanged(GameFoldButton.ZL, false)
            old.onButtonChanged(GameFoldButton.ZR, false)
        }
    }

    fun unregister(expected: GameFoldControllerListener) {
        val toRelease: List<GameFoldButton>
        synchronized(lock) {
            if (listener !== expected) return
            toRelease = pressedButtons.toList()
            pressedButtons.clear()
            listener = null
        }
        toRelease.forEach { expected.onButtonChanged(it, false) }
    }

    fun setButton(button: GameFoldButton, pressed: Boolean) {
        val target: GameFoldControllerListener
        synchronized(lock) {
            target = listener ?: return
            if (pressed) pressedButtons += button else pressedButtons -= button
        }
        target.onButtonChanged(button, pressed)
    }

    fun releaseAll() {
        val target: GameFoldControllerListener
        val toRelease: List<GameFoldButton>
        synchronized(lock) {
            target = listener ?: return
            toRelease = pressedButtons.toList()
            pressedButtons.clear()
        }
        toRelease.forEach { target.onButtonChanged(it, false) }
    }
}
