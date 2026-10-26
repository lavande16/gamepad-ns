package org.yuzu.yuzu_emu.utils

import android.content.Context
import kotlin.math.roundToInt

/** The GamePad PC home grid contract, kept in dp so NS and PC share the same geometry. */
object GamePadNsHomeGeometry {
    private const val GRID_HORIZONTAL_PADDING_DP = 48f
    private const val GRID_TIGHTENING = 0.8f
    private const val CARD_SCALE = 0.7f
    private const val CARD_OUTER_PADDING_DP = 4f

    fun gridWidthFraction(screenWidthDp: Int): Float {
        if (screenWidthDp <= 0) return 1f
        val horizontalPadding = GRID_HORIZONTAL_PADDING_DP * 2f
        val tightenedContentWidth = (screenWidthDp - horizontalPadding) * GRID_TIGHTENING
        return ((tightenedContentWidth + horizontalPadding) / screenWidthDp).coerceIn(0f, 1f)
    }

    fun cardScaleWithinTightenedGrid(): Float = CARD_SCALE / GRID_TIGHTENING

    fun headerHorizontalInsetDp(screenWidthDp: Int): Int {
        val gridWidth = screenWidthDp * gridWidthFraction(screenWidthDp)
        val gridStart = (screenWidthDp - gridWidth) / 2f
        val cellWidth = (gridWidth - GRID_HORIZONTAL_PADDING_DP * 2f) / 4f
        val scaledCardWidth = cellWidth * cardScaleWithinTightenedGrid()
        return (
            gridStart + GRID_HORIZONTAL_PADDING_DP +
                (cellWidth - scaledCardWidth) / 2f + CARD_OUTER_PADDING_DP
            ).roundToInt()
    }

    fun headerHorizontalInsetPx(context: Context): Int =
        (headerHorizontalInsetDp(context.resources.configuration.screenWidthDp) *
            context.resources.displayMetrics.density).roundToInt()
}
