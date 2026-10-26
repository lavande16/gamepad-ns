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

/** Draws short labels with the GAMEFOLD 5 x 7 bitmap font, optional emboldening, and hard shadow. */
class PixelWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val config = readConfig(context, attrs)
    private val shadowEnabled = readShadowEnabled(context, attrs)
    private val boldEnabled = readBoldEnabled(context, attrs)
    private val pixelSize = config.textSizePx / 7f
    private val glyphGap = pixelSize
    private val pixelWeight = if (boldEnabled) (pixelSize * 0.24f).coerceAtLeast(1f) else 0f
    private val shadowOffset = if (shadowEnabled) pixelSize else 0f
    private val wordmark = config.text.uppercase()
    private val foreground = Paint().apply {
        color = config.textColor
        style = Paint.Style.FILL
    }
    private val shadow = Paint().apply {
        color = ContextCompat.getColor(context, R.color.pixel_border_ink)
        style = Paint.Style.FILL
    }

    init {
        contentDescription = wordmark
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentWidth = wordmark.length * 5f * pixelSize +
            (wordmark.length - 1) * glyphGap + pixelWeight + shadowOffset
        val contentHeight = 7f * pixelSize + shadowOffset
        setMeasuredDimension(
            resolveSize(contentWidth.roundToInt(), widthMeasureSpec),
            resolveSize(contentHeight.roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wordmark.forEachIndexed { index, character ->
            val glyph = GLYPHS.getValue(character)
            val originX = index * (5f * pixelSize + glyphGap)
            if (shadowEnabled) {
                drawGlyph(canvas, glyph, originX + shadowOffset, shadowOffset, shadow)
            }
            drawGlyph(canvas, glyph, originX, 0f, foreground)
        }
    }

    private fun drawGlyph(
        canvas: Canvas,
        glyph: IntArray,
        originX: Float,
        originY: Float,
        paint: Paint
    ) {
        glyph.forEachIndexed { row, bits ->
            repeat(5) { column ->
                if (bits and (1 shl (4 - column)) != 0) {
                    val left = originX + column * pixelSize
                    val top = originY + row * pixelSize
                    canvas.drawRect(left, top, left + pixelSize + pixelWeight, top + pixelSize, paint)
                }
            }
        }
    }

    private companion object {
        data class Config(val text: String, val textColor: Int, val textSizePx: Float)

        fun readConfig(context: Context, attrs: AttributeSet?): Config {
            val attributes = intArrayOf(
                android.R.attr.text,
                android.R.attr.textColor,
                android.R.attr.textSize
            )
            val typedArray = context.obtainStyledAttributes(attrs, attributes)
            val defaultTextColor = typedArray.getColor(
                1,
                ContextCompat.getColor(context, R.color.pixel_text_light)
            )
            val appAttributes = context.obtainStyledAttributes(attrs, R.styleable.PixelWordmarkView)
            return Config(
                text = typedArray.getString(0)?.takeIf { it.isNotBlank() } ?: "GAMEFOLD",
                textColor = appAttributes.getColor(
                    R.styleable.PixelWordmarkView_pixelWordmarkColor,
                    defaultTextColor
                ),
                textSizePx = appAttributes.getDimension(
                    R.styleable.PixelWordmarkView_pixelWordmarkSize,
                    typedArray.getDimension(
                        2,
                        17.5f * context.resources.displayMetrics.density
                    )
                )
            ).also {
                typedArray.recycle()
                appAttributes.recycle()
            }
        }

        fun readShadowEnabled(context: Context, attrs: AttributeSet?): Boolean {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.PixelWordmarkView)
            return typedArray.getBoolean(
                R.styleable.PixelWordmarkView_pixelShadowEnabled,
                true
            ).also { typedArray.recycle() }
        }

        fun readBoldEnabled(context: Context, attrs: AttributeSet?): Boolean {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.PixelWordmarkView)
            return typedArray.getBoolean(
                R.styleable.PixelWordmarkView_pixelWordmarkBold,
                false
            ).also { typedArray.recycle() }
        }

        val GLYPHS = mapOf(
            'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110),
            'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
            'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
            'F' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000),
            'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
            'D' to intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110),
            'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
            'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
            'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
            'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
            'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
            'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
            'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
            ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
            '!' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0, 0b00100)
        )
    }
}
