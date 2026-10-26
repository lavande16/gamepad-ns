// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.rearscreen

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldShellLayout

/** Draws the rear-button feedback on the front display's left and right edges. */
object FrontEdgeLightController {
    private const val FRONT_DISPLAY_ID = 0
    private const val TAG = "GameFoldEdge"
    private const val ATTACH_RETRY_COUNT = 12
    private const val ATTACH_RETRY_DELAY_MS = 250L

    private lateinit var application: Application
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var installed = false
    private var candidateActivity: Activity? = null
    private var frontActivity: Activity? = null
    private var frontShell: GameFoldShellLayout? = null
    private var leftPressed = false
    private var rightPressed = false
    private var attachGeneration = 0L

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            requestAttach(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            requestAttach(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (frontActivity === activity || candidateActivity === activity) detach(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (frontActivity === activity) detach(activity)
            if (candidateActivity === activity) candidateActivity = null
        }

        override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
            requestAttach(activity)
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        application = app
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    fun setButton(button: GameFoldButton, pressed: Boolean) {
        if (!isVivoDevice()) {
            clear()
            return
        }
        if (::application.isInitialized && GameFoldOrientation.isPortrait(application)) {
            clear()
            return
        }
        when (button) {
            GameFoldButton.ZR -> rightPressed = pressed
            GameFoldButton.ZL -> leftPressed = pressed
            else -> return
        }
        candidateActivity?.let(::requestAttach)
        Log.i(TAG, "button=$button pressed=$pressed shell=${frontShell != null}")
        mainHandler.post { frontShell?.setRearEdgeLight(leftPressed, rightPressed) }
    }

    fun clear() {
        leftPressed = false
        rightPressed = false
        mainHandler.post { frontShell?.setRearEdgeLight(false, false) }
    }

    private fun requestAttach(activity: Activity) {
        if (activity is RearTouchActivity) return
        candidateActivity = activity
        val generation = ++attachGeneration
        mainHandler.post { attemptAttach(activity, 0, generation) }
    }

    private fun attemptAttach(activity: Activity, attempt: Int, generation: Long) {
        if (generation != attachGeneration || candidateActivity !== activity) return
        if (activity.isFinishing || activity.isDestroyed) return
        val displayId = activity.window.decorView.display?.displayId
        if (displayId != null && displayId != FRONT_DISPLAY_ID) return
        val root = activity.window.decorView as? ViewGroup
        val shell = root?.let(::findShell)
        if (shell == null) {
            if (attempt < ATTACH_RETRY_COUNT) {
                mainHandler.postDelayed(
                    { attemptAttach(activity, attempt + 1, generation) },
                    ATTACH_RETRY_DELAY_MS
                )
            } else {
                Log.w(TAG, "front shell not found activity=${activity.javaClass.simpleName}")
            }
            return
        }
        if (frontActivity === activity && frontShell === shell) {
            shell.setRearEdgeLight(leftPressed, rightPressed)
            return
        }
        detach(frontActivity)
        frontActivity = activity
        frontShell = shell
        Log.i(TAG, "attached activity=${activity.javaClass.simpleName} shell=${shell.javaClass.simpleName}")
        shell.setRearEdgeLight(leftPressed, rightPressed)
    }

    private fun findShell(root: ViewGroup): GameFoldShellLayout? {
        if (root is GameFoldShellLayout) return root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup) {
                findShell(child)?.let { return it }
            }
        }
        return null
    }

    private fun detach(activity: Activity?) {
        if (activity == null) return
        if (candidateActivity === activity) {
            candidateActivity = null
            attachGeneration++
        }
        if (frontActivity === activity) {
            frontShell?.setRearEdgeLight(false, false)
            frontShell = null
            frontActivity = null
        }
    }

    private fun isVivoDevice(): Boolean =
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true)
}
