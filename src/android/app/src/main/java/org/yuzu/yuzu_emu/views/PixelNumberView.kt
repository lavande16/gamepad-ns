// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.yuzu.yuzu_emu.R
import kotlin.math.roundToInt

/** Draws integer values as a compact 3 x 5 bitmap font without a backing panel. */
class PixelNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val pixelSize = 4f * resources.displayMetrics.density
    private val digitGap = pixelSize
    private val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pixel_text_light)
        style = Paint.Style.FILL
    }
    private val shadow = Paint().apply {
        color = ContextCompat.getColor(context, R.color.pixel_border_ink)
        style = Paint.Style.FILL
    }
    private var value = "-"

    fun setValue(number: Int?) {
        val updated = number?.coerceAtLeast(0)?.toString() ?: "-"
        if (updated == value) return
        value = updated
        contentDescription = updated
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentWidth = value.length * (3f * pixelSize) +
            (value.length - 1).coerceAtLeast(0) * digitGap + pixelSize
        val contentHeight = 6f * pixelSize
        setMeasuredDimension(
            resolveSize(contentWidth.roundToInt(), widthMeasureSpec),
            resolveSize(contentHeight.roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        value.forEachIndexed { index, character ->
            val glyph = if (character == '-') UNAVAILABLE_GLYPH else GLYPHS[character - '0']
            val originX = index * (3f * pixelSize + digitGap)
            drawGlyph(canvas, glyph, originX + pixelSize, pixelSize, shadow)
            drawGlyph(canvas, glyph, originX, 0f, foreground)
        }
    }

    private fun drawGlyph(canvas: Canvas, glyph: IntArray, originX: Float, originY: Float, paint: Paint) {
        glyph.forEachIndexed { row, bits ->
            repeat(3) { column ->
                if (bits and (1 shl (2 - column)) != 0) {
                    val left = originX + column * pixelSize
                    val top = originY + row * pixelSize
                    canvas.drawRect(left, top, left + pixelSize, top + pixelSize, paint)
                }
            }
        }
    }

    private companion object {
        val UNAVAILABLE_GLYPH = intArrayOf(0, 0, 0b111, 0, 0)
        val GLYPHS = arrayOf(
            intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
            intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111),
            intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111),
            intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111),
            intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001),
            intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
            intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111),
            intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010),
            intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111),
            intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111)
        )
    }
}
