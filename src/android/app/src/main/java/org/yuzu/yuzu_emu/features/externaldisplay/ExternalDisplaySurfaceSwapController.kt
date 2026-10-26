// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.externaldisplay

import android.app.Presentation
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Moves the emulator's rendering surface into a presentation-only external window.
 *
 * The GamePad shell, controller, logo, and performance overlays remain in the activity window.
 * Only the supplied rendering view is attached to the external display.
 */
class ExternalDisplaySurfaceSwapController(
    private val context: Context,
    private val surfaceViewProvider: () -> View?,
    private val internalHostProvider: () -> ViewGroup?,
    private val onGameMoveStarted: (Boolean) -> Unit = {},
    private val onGameOnExternalChanged: (Boolean) -> Unit = {},
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: GamePresentation? = null
    private var gameOnExternal = false
    private var internalLayoutParams: FrameLayout.LayoutParams? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()

        override fun onDisplayRemoved(displayId: Int) {
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                updatePresentation()
            }
        }
    }

    fun start() {
        displayManager?.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper()),
        )
        updatePresentation()
    }

    fun stop() {
        moveGameToInternal()
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
    }

    private fun updatePresentation() {
        val targetDisplay = findPresentationDisplay()
        if (targetDisplay == null) {
            moveGameToInternal()
            dismissPresentation()
            return
        }

        val needsNewPresentation = presentation?.display?.displayId != targetDisplay.displayId
        if (presentation == null || needsNewPresentation) {
            moveGameToInternal()
            dismissPresentation()
            presentation = try {
                GamePresentation(context, targetDisplay).also { it.show() }
            } catch (_: RuntimeException) {
                null
            }
        }
        if (presentation != null) {
            moveGameToExternal()
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun moveGameToExternal() {
        val surfaceView = surfaceViewProvider() ?: return
        val root = presentation?.root ?: return
        val parent = surfaceView.parent as? ViewGroup
        if (parent === root) {
            setGameOnExternal(true)
            return
        }

        onGameMoveStarted(true)
        if (parent != null) {
            if (parent === internalHostProvider()) {
                rememberInternalLayoutParams(surfaceView)
            }
            parent.removeView(surfaceView)
        }
        if (surfaceView.parent == null) {
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            root.addView(surfaceView)
        }
        setGameOnExternal(true)
    }

    private fun moveGameToInternal() {
        val surfaceView = surfaceViewProvider() ?: return
        val internalHost = internalHostProvider() ?: return
        val parent = surfaceView.parent as? ViewGroup
        if (parent === internalHost) {
            setGameOnExternal(false)
            return
        }

        onGameMoveStarted(false)
        parent?.removeView(surfaceView)
        if (surfaceView.parent == null) {
            surfaceView.layoutParams = internalLayoutParams?.let { FrameLayout.LayoutParams(it) }
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.CENTER }
            internalHost.addView(surfaceView)
        }
        setGameOnExternal(false)
    }

    private fun rememberInternalLayoutParams(surfaceView: View) {
        val params = surfaceView.layoutParams ?: return
        internalLayoutParams = FrameLayout.LayoutParams(params)
    }

    private fun setGameOnExternal(value: Boolean) {
        if (gameOnExternal == value) return
        gameOnExternal = value
        onGameOnExternalChanged(value)
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            (context as? Activity)?.windowManager?.defaultDisplay
        } ?: return null
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }
}

private class GamePresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {
    val root: FrameLayout by lazy {
        FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        setContentView(root)
    }
}
