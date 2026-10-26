// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.ViewTreeObserver
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.yuzu.yuzu_emu.R
import kotlin.math.roundToInt

/** Constrains app-owned dialogs to the visible GameFold application stage. */
object GameFoldDialogSizing {
    const val DEFAULT_MAX_WIDTH_FRACTION = 0.84f
    const val DEFAULT_MAX_HEIGHT_FRACTION = 0.84f

    data class DialogBounds(val width: Int, val height: Int)

    fun calculateMaxBounds(
        stageWidth: Int,
        stageHeight: Int,
        maxWidthFraction: Float = DEFAULT_MAX_WIDTH_FRACTION,
        maxHeightFraction: Float = DEFAULT_MAX_HEIGHT_FRACTION
    ): DialogBounds {
        require(maxWidthFraction in 0f..1f) { "Width fraction must be between 0 and 1" }
        require(maxHeightFraction in 0f..1f) { "Height fraction must be between 0 and 1" }
        return DialogBounds(
            width = (stageWidth.coerceAtLeast(0) * maxWidthFraction).roundToInt(),
            height = (stageHeight.coerceAtLeast(0) * maxHeightFraction).roundToInt()
        )
    }

    fun constrainToStage(
        activity: Activity,
        dialog: Dialog,
        maxWidthFraction: Float = DEFAULT_MAX_WIDTH_FRACTION,
        maxHeightFraction: Float = DEFAULT_MAX_HEIGHT_FRACTION
    ) {
        val stage = activity.findViewById<View>(R.id.gamefold_display_stage) ?: return
        stage.doOnLayout {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            applyBounds(
                dialog,
                it.context,
                it.width,
                it.height,
                location[0],
                location[1],
                maxWidthFraction,
                maxHeightFraction
            )
        }
    }

    fun constrainToStage(
        context: Context,
        dialog: Dialog,
        maxWidthFraction: Float = DEFAULT_MAX_WIDTH_FRACTION,
        maxHeightFraction: Float = DEFAULT_MAX_HEIGHT_FRACTION
    ) {
        val activity = context.findActivity() ?: return
        constrainToStage(activity, dialog, maxWidthFraction, maxHeightFraction)
    }

    fun showWithinStage(context: Context, dialog: Dialog): Dialog {
        dialog.show()
        constrainToStage(context, dialog)
        return dialog
    }

    /** Shows a dialog only after its first drawable frame has the final stage coordinates. */
    fun showWithinStageOnFirstFrame(context: Context, dialog: Dialog): Dialog {
        val activity = context.findActivity()
        val stage = activity?.findViewById<View>(R.id.gamefold_display_stage)
        val window = dialog.window
        if (activity == null || stage == null || window == null ||
            stage.width == 0 || stage.height == 0
        ) {
            return showWithinStage(context, dialog)
        }

        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        val bounds = calculateOwnedBounds(activity, stage.width, stage.height)
        if (bounds.width == 0 || bounds.height == 0) {
            return showWithinStage(context, dialog)
        }

        applyPixelChrome(dialog)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(bounds.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setWindowAnimations(0)

        val decorView = window.decorView
        var positioned = false
        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!decorView.viewTreeObserver.isAlive) return true
                if (!positioned) {
                    val measuredHeight = decorView.height.coerceAtLeast(decorView.measuredHeight)
                    val targetHeight = if (measuredHeight > bounds.height) {
                        bounds.height
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    val visibleHeight = if (targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        measuredHeight
                    } else {
                        targetHeight
                    }
                    window.setLayout(bounds.width, targetHeight)
                    window.attributes = window.attributes.apply {
                        x = location[0] + (stage.width - bounds.width) / 2
                        y = location[1] +
                            (stage.height - visibleHeight.coerceAtMost(bounds.height)) / 2
                    }
                    positioned = true
                    decorView.requestLayout()
                    return false
                }

                decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        }
        decorView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        dialog.show()
        return dialog
    }

    private fun applyBounds(
        dialog: Dialog,
        stageContext: Context,
        stageWidth: Int,
        stageHeight: Int,
        stageLeft: Int,
        stageTop: Int,
        maxWidthFraction: Float,
        maxHeightFraction: Float
    ) {
        val window = dialog.window ?: return
        applyPixelChrome(dialog)
        val bounds = calculateOwnedBounds(
            stageContext,
            stageWidth,
            stageHeight,
            maxWidthFraction,
            maxHeightFraction
        )
        if (bounds.width == 0 || bounds.height == 0) return

        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(bounds.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.decorView.post {
            val measuredHeight = window.decorView.height
            val targetHeight = if (measuredHeight > bounds.height) {
                bounds.height
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            window.setLayout(bounds.width, targetHeight)
            val visibleHeight = if (targetHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
                measuredHeight
            } else {
                targetHeight
            }
            window.attributes = window.attributes.apply {
                x = stageLeft + (stageWidth - bounds.width) / 2
                y = stageTop + (stageHeight - visibleHeight.coerceAtMost(bounds.height)) / 2
            }
        }
    }

    private fun calculateOwnedBounds(
        context: Context,
        stageWidth: Int,
        stageHeight: Int,
        maxWidthFraction: Float = DEFAULT_MAX_WIDTH_FRACTION,
        maxHeightFraction: Float = DEFAULT_MAX_HEIGHT_FRACTION
    ): DialogBounds {
        val defaultBounds = calculateMaxBounds(
            stageWidth,
            stageHeight,
            maxWidthFraction,
            maxHeightFraction
        )
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) return defaultBounds
        val insetPx = (
            GamePadNsHomeGeometry.headerHorizontalInsetDp(context.resources.configuration.screenWidthDp) *
                context.resources.displayMetrics.density
            ).roundToInt()
        val insetWidth = (stageWidth - insetPx * 2).coerceAtLeast(0)
        return defaultBounds.copy(width = insetWidth.coerceAtMost(defaultBounds.width))
    }

    private fun applyPixelChrome(dialog: Dialog) {
        if (dialog is BottomSheetDialog) {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundResource(R.drawable.pixel_dialog_background)
        } else {
            dialog.window?.apply {
                decorView.setPadding(0, 0, 0, 0)
                setBackgroundDrawableResource(R.drawable.pixel_dialog_background)
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
        else -> null
    }
}
