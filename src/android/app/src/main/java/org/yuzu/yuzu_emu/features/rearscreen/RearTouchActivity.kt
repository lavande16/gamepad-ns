// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.rearscreen

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.SparseIntArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.input.RearTouchInputRouter
import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldControllerFeedback
import org.yuzu.yuzu_emu.views.GameFoldHapticProfile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A dedicated, non-recents Activity hosted on vivo's private rear display. The vendor display
 * rejects ordinary Presentation windows, so the controller keeps this Activity outside its
 * front-activity lifecycle accounting and explicitly destroys it with every one-shot session.
 */
internal class RearTouchActivity : Activity() {
    private var rootView: FrameLayout? = null
    private var touchView: RearTouchView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val firstPowerReset = Runnable {
        RearDisplayController.ensureRearDisplayPower("rear-resumed")
    }
    private val settledPowerReset = Runnable {
        RearDisplayController.ensureRearDisplayPower("rear-rotation-settled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RearDisplayController.onRearActivityCreated(this)
        window?.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#252529"))
            clipChildren = false
            clipToPadding = false
        }
        rootView = root
        val controls = RearTouchView(this)
        touchView = controls
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val portraitDisplay = height > width
            val params = controls.layoutParams as FrameLayout.LayoutParams
            params.width = if (portraitDisplay) height else width
            params.height = if (portraitDisplay) width else height
            params.gravity = Gravity.CENTER
            controls.layoutParams = params
            controls.rotation = if (portraitDisplay) 270f else 0f
        }
        setContentView(root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        RearDisplayController.onRearActivityReady(this)
    }

    override fun onStart() {
        super.onStart()
        schedulePowerReset()
    }

    override fun onPause() {
        handler.removeCallbacks(firstPowerReset)
        handler.removeCallbacks(settledPowerReset)
        touchView?.releaseAllButtons(vibrateCancel = false)
        super.onPause()
    }

    private fun schedulePowerReset() {
        handler.removeCallbacks(firstPowerReset)
        handler.removeCallbacks(settledPowerReset)
        handler.postDelayed(firstPowerReset, 250L)
        handler.postDelayed(settledPowerReset, 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(firstPowerReset)
        handler.removeCallbacks(settledPowerReset)
        touchView?.releaseAllButtons(vibrateCancel = false)
        RearDisplayController.onRearActivityDestroyed(this)
        super.onDestroy()
    }

    internal fun prepareForShutdown(onBlankFrameCommitted: (() -> Unit)? = null) {
        handler.removeCallbacks(firstPowerReset)
        handler.removeCallbacks(settledPowerReset)
        touchView?.releaseAllButtons(vibrateCancel = false)
        touchView?.isEnabled = false
        touchView?.visibility = View.INVISIBLE
        rootView?.setBackgroundColor(Color.parseColor("#252529"))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        if (onBlankFrameCommitted == null) return

        val decor = window?.decorView
        if (decor == null || !decor.isAttachedToWindow) {
            handler.post { onBlankFrameCommitted() }
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var fallback: Runnable
        val complete = Runnable {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(fallback)
                Log.i(TAG, "rear shutdown blank committed display=${display?.displayId}")
                onBlankFrameCommitted()
            }
        }
        fallback = Runnable { complete.run() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && decor.isHardwareAccelerated) {
            decor.viewTreeObserver.registerFrameCommitCallback(complete)
            rootView?.invalidate()
            decor.invalidate()
        } else {
            decor.postOnAnimation {
                decor.postOnAnimation(complete)
            }
        }
        handler.postDelayed(fallback, SHUTDOWN_BLANK_TIMEOUT_MS)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "GameFoldRear"
        private const val SHUTDOWN_BLANK_TIMEOUT_MS = 150L
    }
}

internal class RearTouchView(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    companion object {
        private const val TAG = "GameFoldRearTouch"
        private const val ZONE_WIDTH = 788f
        private const val ZONE_HEIGHT = 438f
        private const val ZONE_GAP = 81f
        private const val TOP_MARGIN = 66f
        private const val LOGO_VERTICAL_GAP = 420f
        private const val BUTTON_ALPHA_THRESHOLD = 16
        private const val BUTTON_ZR = 0
        private const val BUTTON_ZL = 1
        private const val NO_BUTTON = -1
    }

    private val zrBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rear_touch_zr)
    private val zlBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rear_touch_zl)
    private val logoBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rear_touch_logo)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val zrZone = RectF()
    private val zlZone = RectF()
    private val logoZone = RectF()
    private val pointerButtons = SparseIntArray()
    private val pressedPointerCount = intArrayOf(0, 0)
    private var feedback: GameFoldControllerFeedback? = null

    init {
        setBackgroundColor(Color.parseColor("#252529"))
        isClickable = true
        feedback = runCatching { GameFoldControllerFeedback(context) }.getOrNull()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val groupLeft = (w - (ZONE_WIDTH * 2f + ZONE_GAP)) / 2f
        val bottom = TOP_MARGIN + ZONE_HEIGHT
        zrZone.set(groupLeft, TOP_MARGIN, groupLeft + ZONE_WIDTH, bottom)
        zlZone.set(
            zrZone.right + ZONE_GAP,
            TOP_MARGIN,
            zrZone.right + ZONE_GAP + ZONE_WIDTH,
            bottom
        )
        val logoLeft = (w - logoBitmap.width) / 2f
        val logoTop = bottom + LOGO_VERTICAL_GAP
        logoZone.set(
            logoLeft,
            logoTop,
            logoLeft + logoBitmap.width,
            logoTop + logoBitmap.height
        )
        Log.i(TAG, "size=${w}x$h ZR=$zrZone ZL=$zlZone logo=$logoZone")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handleDown(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> releaseAllButtons(vibrateCancel = true)
        }
        return true
    }

    private fun handleDown(event: MotionEvent) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val button = buttonAt(event.getX(index), event.getY(index))
        if (button == NO_BUTTON || pointerButtons.indexOfKey(id) >= 0) return
        pointerButtons.put(id, button)
        pressedPointerCount[button]++
        if (pressedPointerCount[button] == 1) onButtonPressed(button)
    }

    private fun handleUp(event: MotionEvent) {
        val id = event.getPointerId(event.actionIndex)
        val index = pointerButtons.indexOfKey(id)
        if (index < 0) return
        val button = pointerButtons.valueAt(index)
        pointerButtons.delete(id)
        pressedPointerCount[button] = (pressedPointerCount[button] - 1).coerceAtLeast(0)
        if (pressedPointerCount[button] == 0) {
            onButtonReleased(button)
            performClick()
        }
    }

    private fun buttonAt(x: Float, y: Float): Int = when {
        zrZone.contains(x, y) && isOpaqueAt(zrBitmap, zrZone, x, y) -> BUTTON_ZR
        zlZone.contains(x, y) && isOpaqueAt(zlBitmap, zlZone, x, y) -> BUTTON_ZL
        else -> NO_BUTTON
    }

    private fun isOpaqueAt(bitmap: Bitmap, zone: RectF, x: Float, y: Float): Boolean {
        val bitmapX = (((x - zone.left) / zone.width()) * bitmap.width)
            .toInt()
            .coerceIn(0, bitmap.width - 1)
        val bitmapY = (((y - zone.top) / zone.height()) * bitmap.height)
            .toInt()
            .coerceIn(0, bitmap.height - 1)
        return Color.alpha(bitmap.getPixel(bitmapX, bitmapY)) >= BUTTON_ALPHA_THRESHOLD
    }

    private fun onButtonPressed(button: Int) {
        val gameFoldButton = button.toGameFoldButton()
        RearTouchInputRouter.setButton(gameFoldButton, true)
        FrontEdgeLightController.setButton(gameFoldButton, true)
        // Reuse the exact front ZL/ZR feedback: AUXILIARY_CLICK haptic plus
        // controller_aux_click SoundPool sample.
        feedback?.pulse(GameFoldHapticProfile.AUXILIARY_CLICK)
        feedback?.auxiliary()
        invalidate()
    }

    private fun onButtonReleased(button: Int) {
        val gameFoldButton = button.toGameFoldButton()
        RearTouchInputRouter.setButton(gameFoldButton, false)
        FrontEdgeLightController.setButton(gameFoldButton, false)
        invalidate()
    }

    fun releaseAllButtons(@Suppress("UNUSED_PARAMETER") vibrateCancel: Boolean) {
        for (button in BUTTON_ZR..BUTTON_ZL) {
            if (pressedPointerCount[button] > 0) {
                pressedPointerCount[button] = 0
                val gameFoldButton = button.toGameFoldButton()
                RearTouchInputRouter.setButton(gameFoldButton, false)
                FrontEdgeLightController.setButton(gameFoldButton, false)
            }
        }
        pointerButtons.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawButton(canvas, zrBitmap, zrZone, pressedPointerCount[BUTTON_ZR] > 0)
        drawButton(canvas, zlBitmap, zlZone, pressedPointerCount[BUTTON_ZL] > 0)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(logoBitmap, null, logoZone, bitmapPaint)
    }

    private fun drawButton(canvas: Canvas, bitmap: Bitmap, zone: RectF, pressed: Boolean) {
        bitmapPaint.alpha = if (pressed) 215 else 255
        canvas.drawBitmap(bitmap, null, zone, bitmapPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        releaseAllButtons(vibrateCancel = false)
        feedback?.release()
        feedback = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (feedback == null) {
            feedback = runCatching { GameFoldControllerFeedback(context) }.getOrNull()
        }
    }

    private fun Int.toGameFoldButton(): GameFoldButton =
        if (this == BUTTON_ZR) GameFoldButton.ZR else GameFoldButton.ZL
}
