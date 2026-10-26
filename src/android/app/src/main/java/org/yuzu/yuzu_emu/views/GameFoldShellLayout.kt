// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import android.widget.FrameLayout
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract
import org.yuzu.yuzu_emu.utils.GamePadNsWindowPolicy
import org.yuzu.yuzu_emu.utils.GamePadNsDisplayMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Permanent GameFold product shell.
 *
 * Landscape app-owned screens use the 1728 x 972 display opening while the controller artwork
 * remains fixed on the 2480 x 2200 reference canvas. Portrait uses the explicit 16:9 display
 * opening and independently bottom-anchors that same controller reference. The shell owns the
 * approved controller touch geometry and forwards semantic input events to the emulator without
 * translating them into touches on the legacy overlay.
 */
class GameFoldShellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val appBackgroundColor = 0xFF252429.toInt()
    private val portraitBackgroundColor = 0xFF252529.toInt()
    private val portraitDisplayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF050607.toInt()
        style = Paint.Style.FILL
    }
    private val landscapeDisplayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val displayCoverMaskPaint = Paint().apply {
        color = 0xFF474951.toInt()
        style = Paint.Style.FILL
    }
    private val backgroundLayer = drawable(R.drawable.controller_background)
    private val displayLayer = drawable(R.drawable.controller_display)
    private val coverLayer = drawable(R.drawable.gamefold_display_cover)
    private val portraitHingeLayer = drawable(R.drawable.gamefold_portrait_hinge)
    private val portraitLogoLayer = drawable(R.drawable.gamefold_portrait_logo)
    private val dpadLayer = drawable(R.drawable.controller_dpad_default)
    private val abxyLayer = drawable(R.drawable.controller_abxy_default)
    private val stickCapLayer = drawable(R.drawable.controller_stick_cap)
    private val zlLayer = drawable(R.drawable.controller_button_zl_default)
    private val lLayer = drawable(R.drawable.controller_button_l_default)
    private val rLayer = drawable(R.drawable.controller_button_r_default)
    private val zrLayer = drawable(R.drawable.controller_button_zr_default)
    private val minusLayer = drawable(R.drawable.controller_button_minus_default)
    private val plusLayer = drawable(R.drawable.controller_button_plus_default)
    private val homeLayer = drawable(R.drawable.gamepad_home_logo)
    private val unsupportedUsageLayer = drawable(R.drawable.gamefold_splash_icon)
    private val rearLeftEdgeLight =
        BitmapFactory.decodeResource(resources, R.drawable.rear_touch_left_edge)
    private val rearRightEdgeLight =
        BitmapFactory.decodeResource(resources, R.drawable.rear_touch_right_edge)
    private val rearEdgeLightPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val unsupportedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.pixel_text_light, context.theme)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val dpadPressedResources = mapOf(
        GameFoldPadSector.TOP to R.drawable.controller_dpad_up_pressed,
        GameFoldPadSector.RIGHT to R.drawable.controller_dpad_right_pressed,
        GameFoldPadSector.BOTTOM to R.drawable.controller_dpad_down_pressed,
        GameFoldPadSector.LEFT to R.drawable.controller_dpad_left_pressed
    )
    private val abxyPressedResources = mapOf(
        GameFoldPadSector.TOP to R.drawable.controller_abxy_x_pressed,
        GameFoldPadSector.RIGHT to R.drawable.controller_abxy_a_pressed,
        GameFoldPadSector.BOTTOM to R.drawable.controller_abxy_b_pressed,
        GameFoldPadSector.LEFT to R.drawable.controller_abxy_y_pressed
    )
    private val smallPressedResources = mapOf(
        GameFoldSmallButton.ZL to R.drawable.controller_button_zl_pressed,
        GameFoldSmallButton.L to R.drawable.controller_button_l_pressed,
        GameFoldSmallButton.R to R.drawable.controller_button_r_pressed,
        GameFoldSmallButton.ZR to R.drawable.controller_button_zr_pressed,
        GameFoldSmallButton.MINUS to R.drawable.controller_button_minus_pressed,
        GameFoldSmallButton.PLUS to R.drawable.controller_button_plus_pressed
    )
    private val pressedDrawableCache = mutableMapOf<Int, Drawable>()

    private val activePointers = SparseArray<GameFoldActiveControl>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllerVisibilityState = GamePadControllerVisibilityState()
    private val hideLowerControlsJob = Runnable { onControllerIdleDeadline() }
    private val stickClickJobs = mutableMapOf<GameFoldPadSide, Runnable>()
    private val stickClickCandidateStart = mutableMapOf<GameFoldPadSide, GameFoldPoint>()
    private val stickClickActive = mutableSetOf<GameFoldPadSide>()
    private val stickNudgePlayed = mutableSetOf<GameFoldPadSide>()
    private val stickBoundaryPlayed = mutableSetOf<GameFoldPadSide>()
    private val pressedSmallButtons = mutableSetOf<GameFoldSmallButton>()
    private var leftPadSector: GameFoldPadSector? = null
    private var rightPadSector: GameFoldPadSector? = null
    private var leftStickOffset = GameFoldPoint(0f, 0f)
    private var rightStickOffset = GameFoldPoint(0f, 0f)
    private val stickReturnAnimations = mutableMapOf<GameFoldPadSide, ValueAnimator>()
    private var contentPointerIdBits = 0
    private var lowerControlsAlpha = 1f
    private var lowerControlsAnimator: ValueAnimator? = null
    private var wakeOnlyGesture = false
    private var controllerListener: GameFoldControllerListener? = null
    private var feedback: GameFoldControllerFeedback? = null
    private var controllerOverlayVisible = false

    private var shellScale = 1f
    private var shellLeft = 0f
    private var shellTop = 0f
    private var landscapeDisplayLeft = 0f
    private var landscapeDisplayTop = 0f
    private var landscapeDisplayWidth = 0f
    private var landscapeDisplayHeight = 0f
    private var landscapeContentLeft = 0f
    private var landscapeContentTop = 0f
    private var landscapeContentWidth = 0f
    private var landscapeContentHeight = 0f
    private var portraitMode = false
    private var portraitDisplayScale = 1f
    private var portraitDisplayLeft = 0f
    private var portraitDisplayTop = 0f
    private var portraitDisplayWidth = 0f
    private var portraitDisplayHeight = 0f
    private var portraitHingeTop = 0f
    private var portraitOuterTopLeftRadius = 0f
    private var portraitOuterTopRightRadius = 0f
    private var portraitInnerTopLeftRadius = 0f
    private var portraitInnerTopRightRadius = 0f
    private var supportedUsageMode = true
    private var rearLeftEdgePressed = false
    private var rearRightEdgePressed = false

    init {
        setWillNotDraw(false)
        setBackgroundColor(appBackgroundColor)
        clipChildren = false
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        updateSupportedUsageMode(width, height)

        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            portraitMode = false
            shellScale = 1f
            shellLeft = 0f
            shellTop = 0f
            landscapeDisplayLeft = 0f
            landscapeDisplayTop = 0f
            landscapeDisplayWidth = width.toFloat()
            landscapeDisplayHeight = height.toFloat()
            landscapeContentLeft = 0f
            landscapeContentTop = 0f
            landscapeContentWidth = width.toFloat()
            landscapeContentHeight = height.toFloat()
            val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            for (index in 0 until childCount) {
                getChildAt(index).measure(childWidthSpec, childHeightSpec)
            }
            return
        }

        portraitMode = height > width
        if (portraitMode) {
            // Keep the approved controller reference geometry and bottom-anchor it in portrait.
            // Keep controller pixels 1:1 with landscape; only group positions change.
            shellScale = 1f
            shellLeft = 0f
            shellTop = height - DESIGN_HEIGHT - PORTRAIT_CONTROL_BOTTOM_LIFT

            portraitDisplayScale = width / PORTRAIT_DISPLAY_SOURCE_WIDTH
            val displayMargin = PORTRAIT_DISPLAY_MARGIN * portraitDisplayScale
            portraitDisplayLeft = displayMargin
            portraitDisplayTop = displayMargin
            portraitDisplayWidth = width - displayMargin * 2f
            portraitDisplayHeight = portraitDisplayWidth * 9f / 16f
            portraitHingeTop = portraitDisplayTop + portraitDisplayHeight
            updatePortraitDisplayCornerRadii()

            val childWidthSpec = MeasureSpec.makeMeasureSpec(
                portraitDisplayWidth.roundToInt(),
                MeasureSpec.EXACTLY
            )
            val childHeightSpec = MeasureSpec.makeMeasureSpec(
                portraitDisplayHeight.roundToInt(),
                MeasureSpec.EXACTLY
            )
            for (index in 0 until childCount) {
                getChildAt(index).measure(childWidthSpec, childHeightSpec)
            }
            return
        }

        shellScale = min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        shellLeft = (width - DESIGN_WIDTH * shellScale) / 2f
        shellTop = (height - DESIGN_HEIGHT * shellScale) / 2f
        // Landscape screen geometry is anchored to the actual GameFold window, not to the
        // vertically letterboxed 2480 x 2200 artwork canvas. The cover remains artwork-scaled,
        // but its global bottom edge is the lower anchor for the independently drawn screen.
        val coverBottom = shellTop + COVER_BOTTOM * shellScale
        landscapeDisplayTop = LANDSCAPE_DISPLAY_TOP_PX
        val landscapeDisplayBottom = coverBottom - LANDSCAPE_DISPLAY_BOTTOM_GAP_PX
        landscapeDisplayHeight = (landscapeDisplayBottom - landscapeDisplayTop).coerceAtLeast(0f)
        // Keep the actual content aperture at the closest integer-pixel 16:9 size. The outer
        // black frame is added around it, so the frame itself is intentionally a few pixels
        // wider/taller than the content aperture.
        // The Canvas frame and child view use the same integer-pixel geometry; independently
        // rounding their float geometry creates uneven 6/8px borders on otherwise identical
        // 7px sides.
        val borderPx = DISPLAY_BORDER.roundToInt()
        val displayTopPx = LANDSCAPE_DISPLAY_TOP_PX.roundToInt()
        val displayBottomPx = (coverBottom - LANDSCAPE_DISPLAY_BOTTOM_GAP_PX).roundToInt()
        val displayHeightPx = (displayBottomPx - displayTopPx).coerceAtLeast(borderPx * 2)
        val contentHeightPx = displayHeightPx - borderPx * 2
        // Match GameFoldViewportCalculator's pixel rounding so the nested viewport fills the
        // content rect instead of becoming 1px narrower and leaving a right-side black strip.
        // Keep the aperture width even as well as close to 16:9. This gives the outer frame
        // an even width, so centering in an even-width window does not land on a half-pixel.
        val contentWidthPx = (contentHeightPx * 16f / 9f).roundToInt() / 2 * 2
        val displayWidthPx = contentWidthPx + borderPx * 2
        val displayLeftPx = (width - displayWidthPx) / 2

        landscapeDisplayLeft = displayLeftPx.toFloat()
        landscapeDisplayTop = displayTopPx.toFloat()
        landscapeDisplayWidth = displayWidthPx.toFloat()
        landscapeDisplayHeight = displayHeightPx.toFloat()
        landscapeContentLeft = (displayLeftPx + borderPx).toFloat()
        landscapeContentTop = (displayTopPx + borderPx).toFloat()
        landscapeContentWidth = contentWidthPx.toFloat()
        landscapeContentHeight = contentHeightPx.toFloat()

        val displayWidth = contentWidthPx
        val displayHeight = contentHeightPx
        val childWidthSpec = MeasureSpec.makeMeasureSpec(displayWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(displayHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            for (index in 0 until childCount) {
                getChildAt(index).apply {
                    layout(0, 0, measuredWidth, measuredHeight)
                    clipToOutline = false
                    outlineProvider = null
                }
            }
            return
        }
        if (portraitMode) {
            updatePortraitDisplayCornerRadii()
            val displayLeft = portraitDisplayLeft.roundToInt()
            val displayTop = portraitDisplayTop.roundToInt()
            val displayRight = displayLeft + portraitDisplayWidth.roundToInt()
            val displayBottom = displayTop + portraitDisplayHeight.roundToInt()
            for (index in 0 until childCount) {
                getChildAt(index).apply {
                    layout(displayLeft, displayTop, displayRight, displayBottom)
                    clipToOutline = true
                    outlineProvider = RoundedDisplayOutlineProvider(
                        portraitInnerTopLeftRadius,
                        portraitInnerTopRightRadius,
                        PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale,
                        PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale
                    )
                }
            }
            return
        }

        val displayLeft = landscapeContentLeft.roundToInt()
        val displayTop = landscapeContentTop.roundToInt()
        val displayRight = displayLeft + landscapeContentWidth.roundToInt()
        val displayBottom = displayTop + landscapeContentHeight.roundToInt()
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                layout(displayLeft, displayTop, displayRight, displayBottom)
                clipToOutline = true
                outlineProvider = RoundedDisplayOutlineProvider(
                    DISPLAY_CORNER_RADIUS - DISPLAY_BORDER,
                    DISPLAY_CORNER_RADIUS - DISPLAY_BORDER,
                    DISPLAY_CORNER_RADIUS - DISPLAY_BORDER,
                    DISPLAY_CORNER_RADIUS - DISPLAY_BORDER
                )
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!supportedUsageMode) {
            canvas.drawColor(appBackgroundColor)
            drawUnsupportedUsage(canvas)
            return
        }
        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            canvas.drawColor(appBackgroundColor)
            super.dispatchDraw(canvas)
            if (controllerOverlayVisible) drawGamePadController(canvas)
            return
        }
        if (portraitMode) {
            canvas.drawColor(portraitBackgroundColor)
            drawPortraitHinge(canvas)
            drawPortraitDisplayBorder(canvas)
            super.dispatchDraw(canvas)
            drawPortraitController(canvas)
            drawPortraitLogo(canvas)
            drawRearEdgeLight(canvas)
            return
        }
        drawOnReferenceCanvas(canvas) {
            drawFullCanvas(backgroundLayer)
            drawFullCanvas(displayLayer)
            drawFullCanvas(coverLayer)
        }
        // The mask is behind the screen. The screen is an independent layer and must remain
        // visible even where this legacy top cover mask is present.
        drawDisplayCoverMask(canvas)
        // Draw the frame before the child surface. The child is laid out inside this outer
        // integer-aligned fill, so the four straight edges are exposed as exactly 7px strips
        // instead of relying on Canvas stroke rasterization.
        drawLandscapeDisplayBorder(canvas)
        super.dispatchDraw(canvas)
        drawOnReferenceCanvas(canvas) {
            drawPositioned(
                leftPadSector?.let(dpadPressedResources::get)?.let(::pressedDrawable) ?: dpadLayer,
                130f,
                1311f,
                706f,
                706f
            )
            drawPositioned(
                rightPadSector?.let(abxyPressedResources::get)?.let(::pressedDrawable) ?: abxyLayer,
                1644f,
                1311f,
                706f,
                706f
            )
            drawStick(483f, leftStickOffset)
            drawStick(1997f, rightStickOffset)
            drawSmallButton(GameFoldSmallButton.ZL, zlLayer, 93f, 1277f)
            drawSmallButton(GameFoldSmallButton.L, lLayer, 734f, 1277f)
            drawSmallButton(GameFoldSmallButton.R, rLayer, 1608f, 1277f)
            drawSmallButton(GameFoldSmallButton.ZR, zrLayer, 2249f, 1277f)
            drawSmallButton(GameFoldSmallButton.MINUS, minusLayer, 734f, 1912f)
            drawSmallButton(GameFoldSmallButton.PLUS, plusLayer, 1608f, 1912f)
        }
        drawRearEdgeLight(canvas)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val result = super.onApplyWindowInsets(insets)
        if (portraitMode) {
            updatePortraitDisplayCornerRadii(insets)
            invalidate()
            for (index in 0 until childCount) {
                getChildAt(index).invalidateOutline()
            }
        }
        return result
    }

    fun setRearEdgeLight(leftPressed: Boolean, rightPressed: Boolean) {
        if (rearLeftEdgePressed == leftPressed && rearRightEdgePressed == rightPressed) return
        rearLeftEdgePressed = leftPressed
        rearRightEdgePressed = rightPressed
        invalidate()
    }

    private fun drawRearEdgeLight(canvas: Canvas) {
        // Rear controls and their edge feedback are a landscape-only feature. Do not draw a
        // stale pressed state while the main shell is in portrait mode.
        if (portraitMode || (!rearLeftEdgePressed && !rearRightEdgePressed)) return
        val displayLeft: Float
        val displayTop: Float
        val displayWidth: Float
        val displayHeight: Float
        if (portraitMode) {
            // Edge light belongs to the complete display opening, including its black frame.
            // Using only the nested content rect makes the light visibly inset from the
            // physical screen edge.
            val border = PORTRAIT_DISPLAY_BORDER * portraitDisplayScale
            displayLeft = portraitDisplayLeft - border
            displayTop = portraitDisplayTop - border
            displayWidth = portraitDisplayWidth + border * 2f
            displayHeight = portraitDisplayHeight + border * 2f
        } else {
            // Match the exact outer display frame used by drawLandscapeDisplayBorder().
            displayLeft = landscapeDisplayLeft
            displayTop = landscapeDisplayTop
            displayWidth = landscapeDisplayWidth
            displayHeight = landscapeDisplayHeight
        }
        if (displayWidth <= 0f || displayHeight <= 0f) return
        val edgeWidth = rearLeftEdgeLight.width.toFloat() * displayHeight /
            rearLeftEdgeLight.height.toFloat()
        rearEdgeLightPaint.alpha = 255
        if (rearLeftEdgePressed) {
            canvas.drawBitmap(
                rearLeftEdgeLight,
                null,
                // The glow is on the bezel side of the display. Its inner edge must
                // coincide with the display's left edge, without covering game content.
                RectF(
                    displayLeft - edgeWidth,
                    displayTop,
                    displayLeft,
                    displayTop + displayHeight
                ),
                rearEdgeLightPaint
            )
        }
        if (rearRightEdgePressed) {
            canvas.drawBitmap(
                rearRightEdgeLight,
                null,
                RectF(
                    // Keep the right glow outside the display so it cannot cover the
                    // rightmost game card; the strip starts exactly at the screen edge.
                    displayLeft + displayWidth,
                    displayTop,
                    displayLeft + displayWidth + edgeWidth,
                    displayTop + displayHeight
                ),
                rearEdgeLightPaint
            )
        }
    }

    fun setControllerInputListener(listener: GameFoldControllerListener?) {
        if (controllerListener === listener) return
        releaseControllerInputs()
        controllerListener = listener
        if (listener != null && feedback == null) {
            feedback = GameFoldControllerFeedback(context.applicationContext)
        }
    }

    fun setControllerOverlayVisible(visible: Boolean) {
        val changed = controllerOverlayVisible != visible
        controllerOverlayVisible = visible
        if (!visible) {
            setControllerAutoHideEnabled(false)
            releaseControllerInputs()
        }
        if (changed) invalidate()
    }

    fun setControllerAutoHideEnabled(enabled: Boolean) {
        val nowMs = SystemClock.uptimeMillis()
        controllerVisibilityState.setEnabled(enabled, nowMs)
        wakeOnlyGesture = false
        mainHandler.removeCallbacks(hideLowerControlsJob)
        animateLowerControls(1f)
        if (enabled) scheduleControllerIdle(nowMs)
    }

    fun releaseControllerInputs() {
        val controls = (0 until activePointers.size()).map { activePointers.valueAt(it) }
        activePointers.clear()
        controls.forEach(::releaseControl)
        stickClickJobs.values.forEach(mainHandler::removeCallbacks)
        stickClickJobs.clear()
        stickClickCandidateStart.clear()
        stickClickActive.clear()
        leftPadSector = null
        rightPadSector = null
        pressedSmallButtons.clear()
        setStickOffset(GameFoldPadSide.LEFT, GameFoldPoint(0f, 0f))
        setStickOffset(GameFoldPadSide.RIGHT, GameFoldPoint(0f, 0f))
        invalidate()
    }

    fun isPointInDisplay(x: Float, y: Float): Boolean {
        if (!supportedUsageMode) return false
        if (portraitMode) {
            return x in portraitDisplayLeft..(portraitDisplayLeft + portraitDisplayWidth) &&
                y in portraitDisplayTop..(portraitDisplayTop + portraitDisplayHeight)
        }
        return x in landscapeDisplayLeft..(landscapeDisplayLeft + landscapeDisplayWidth) &&
            y in landscapeDisplayTop..(landscapeDisplayTop + landscapeDisplayHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!supportedUsageMode) return true
        // The shell is shared by home/settings/assembly and emulation activities. A controller
        // listener may still be installed for D-pad UI navigation on non-game screens, but that
        // must never turn the game controller geometry into a touch target there.
        if (!controllerOverlayVisible) return super.onTouchEvent(event)
        if (controllerListener == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val screenPoint = GameFoldPoint(event.getX(index), event.getY(index))
                val candidate = if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
                    classifyGamePadControl(screenPoint)
                } else {
                    classifyGameFoldControl(
                        toLogicalPoint(screenPoint.x, screenPoint.y),
                        portraitRightGroupShift(),
                        portraitHomeShift()
                    )
                }
                if (candidate != null && !isOccupied(candidate)) {
                    activePointers.put(pointerId, candidate)
                    pressControl(candidate, controllerSourcePoint(screenPoint, candidate))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val active = activePointers[event.getPointerId(index)] ?: continue
                    val point = GameFoldPoint(event.getX(index), event.getY(index))
                    moveControl(
                        active,
                        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
                            controllerSourcePoint(point, active)
                        } else {
                            toLogicalPoint(point.x, point.y)
                        }
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                activePointers[pointerId]?.let(::releaseControl)
                activePointers.remove(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> releaseControllerInputs()
        }
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!supportedUsageMode) return true
        // Visibility is also the hit-test boundary. Do not classify controller geometry or send
        // controller input while this shell is hosting a non-game screen.
        if (!controllerOverlayVisible) return super.dispatchTouchEvent(event)
        val nowMs = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (activePointers.size() > 0) releaseControllerInputs()
            contentPointerIdBits = 0
            controllerVisibilityState.onTouchCancel(nowMs)
        }

        val isPointerDown = event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        val actionPointerId = event.getPointerId(event.actionIndex)
        val actionHit = if (isPointerDown) {
            classifyGamePadControl(GameFoldPoint(event.getX(event.actionIndex), event.getY(event.actionIndex)))
        } else null
        val wakeDisposition = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val disposition = controllerVisibilityState.onTouchDown(
                    nowMs,
                    actionHit == GameFoldActiveControl.Home
                )
                animateLowerControls(1f)
                disposition
            }
            else -> GamePadWakeTouchDisposition.DISPATCH
        }
        val consumeWakeOnlyGesture = wakeOnlyGesture ||
            wakeDisposition == GamePadWakeTouchDisposition.WAKE_ONLY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> controllerVisibilityState.onTouchActivity(nowMs)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                controllerVisibilityState.onTouchUp(nowMs)
            MotionEvent.ACTION_CANCEL -> controllerVisibilityState.onTouchCancel(nowMs)
        }
        scheduleControllerIdle(nowMs)
        if (consumeWakeOnlyGesture) {
            wakeOnlyGesture = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> true
            }
            return true
        }

        val actionRoutesToController = actionHit != null && controllerListener != null
        if (isPointerDown && !actionRoutesToController) {
            contentPointerIdBits = contentPointerIdBits or pointerIdBit(actionPointerId)
        }
        val hadControllerPointers = activePointers.size() > 0
        val controllerHandled = if (hadControllerPointers || actionRoutesToController) {
            onTouchEvent(event)
        } else false
        val contentHandled = dispatchToContent(event, contentPointerIdBits)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                contentPointerIdBits = contentPointerIdBits and pointerIdBit(actionPointerId).inv()
            }
            MotionEvent.ACTION_CANCEL -> contentPointerIdBits = 0
        }
        return controllerHandled || contentHandled || actionRoutesToController
    }

    private fun onControllerIdleDeadline() {
        val nowMs = SystemClock.uptimeMillis()
        if (controllerVisibilityState.update(nowMs)) {
            animateLowerControls(0f)
        } else {
            scheduleControllerIdle(nowMs)
        }
    }

    private fun scheduleControllerIdle(nowMs: Long) {
        mainHandler.removeCallbacks(hideLowerControlsJob)
        val delayMs = controllerVisibilityState.delayUntilIdle(nowMs) ?: return
        mainHandler.postDelayed(hideLowerControlsJob, max(1L, delayMs))
    }

    private fun animateLowerControls(targetAlpha: Float) {
        lowerControlsAnimator?.cancel()
        if (lowerControlsAlpha == targetAlpha) {
            invalidate()
            return
        }
        lowerControlsAnimator = ValueAnimator.ofFloat(lowerControlsAlpha, targetAlpha).apply {
            duration = controllerVisibilityState.transitionDurationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                lowerControlsAlpha = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dispatchToContent(event: MotionEvent, pointerIdBits: Int): Boolean {
        if (pointerIdBits == 0) return false
        val contentEvent = splitMotionEvent(event, pointerIdBits) ?: return false
        return try {
            super.dispatchTouchEvent(contentEvent)
        } finally {
            contentEvent.recycle()
        }
    }

    private fun splitMotionEvent(event: MotionEvent, pointerIdBits: Int): MotionEvent? {
        val sourceIndices = (0 until event.pointerCount).filter { index ->
            pointerIdBits and pointerIdBit(event.getPointerId(index)) != 0
        }
        if (sourceIndices.isEmpty()) return null
        val actionSourceIndex = event.actionIndex
        val actionTargetIndex = sourceIndices.indexOf(actionSourceIndex)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> when {
                actionTargetIndex < 0 -> MotionEvent.ACTION_MOVE
                sourceIndices.size == 1 -> if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    MotionEvent.ACTION_DOWN
                } else MotionEvent.ACTION_UP
                else -> event.actionMasked or
                    (actionTargetIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> event.action
        }
        val properties = Array(sourceIndices.size) { targetIndex ->
            MotionEvent.PointerProperties().also {
                event.getPointerProperties(sourceIndices[targetIndex], it)
            }
        }
        val coordinates = Array(sourceIndices.size) { targetIndex ->
            MotionEvent.PointerCoords().also {
                event.getPointerCoords(sourceIndices[targetIndex], it)
            }
        }
        return MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            action,
            sourceIndices.size,
            properties,
            coordinates,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags
        )
    }

    private fun pointerIdBit(pointerId: Int): Int {
        require(pointerId in 0 until Int.SIZE_BITS)
        return 1 shl pointerId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestLayout()
        invalidate()
    }

    private fun pressControl(control: GameFoldActiveControl, position: GameFoldPoint) {
        when (control) {
            is GameFoldActiveControl.Pad -> {
                if (control.side == GameFoldPadSide.LEFT) {
                    leftPadSector = control.sector
                    feedback?.dpad()
                } else {
                    rightPadSector = control.sector
                    feedback?.action()
                }
                feedback?.pulse(GameFoldHapticProfile.ACTION_CLICK)
                control.button()?.let { controllerListener?.onButtonChanged(it, true) }
            }
            is GameFoldActiveControl.Small -> {
                pressedSmallButtons += control.button
                feedback?.pulse(GameFoldHapticProfile.AUXILIARY_CLICK)
                feedback?.auxiliary()
                control.button()?.let { controllerListener?.onButtonChanged(it, true) }
            }
            GameFoldActiveControl.Home -> {
                feedback?.pulse(GameFoldHapticProfile.ACTION_CLICK)
                feedback?.action()
                controllerListener?.onButtonChanged(GameFoldButton.HOME, true)
            }
            is GameFoldActiveControl.Stick -> {
                stickReturnAnimations.remove(control.side)?.cancel()
                feedback?.pulse(GameFoldHapticProfile.STICK_PICKUP)
                stickNudgePlayed -= control.side
                stickBoundaryPlayed -= control.side
                cancelStickClickCandidate(control.side)
                stickClickActive -= control.side
                val isClickCandidate = (
                    position - gameFoldStickCenter(control.side, portraitRightGroupShift())
                    ).distance() <=
                    GameFoldControllerGeometry.STICK_CLICK_CANDIDATE_RADIUS
                val visual = if (isClickCandidate) GameFoldPoint(0f, 0f) else {
                    gameFoldVisualStickOffset(position, control.side, portraitRightGroupShift())
                }
                setStickOffset(control.side, visual)
                updateStickFeedback(control.side, visual)
                sendStick(control.side, visual)
                if (isClickCandidate) startStickClickCandidate(control.side, position)
            }
        }
        invalidate()
    }

    private fun moveControl(control: GameFoldActiveControl, position: GameFoldPoint) {
        if (control !is GameFoldActiveControl.Stick) return
        stickClickCandidateStart[control.side]?.let { start ->
            if (control.side !in stickClickActive &&
                (position - start).distance() <=
                GameFoldControllerGeometry.STICK_CLICK_JITTER_TOLERANCE
            ) {
                return
            }
            cancelStickClickCandidate(control.side)
        }
        val visual = gameFoldVisualStickOffset(position, control.side, portraitRightGroupShift())
        setStickOffset(control.side, visual)
        updateStickFeedback(control.side, visual)
        sendStick(control.side, visual)
        invalidate()
    }

    private fun releaseControl(control: GameFoldActiveControl) {
        when (control) {
            is GameFoldActiveControl.Pad -> {
                if (control.side == GameFoldPadSide.LEFT) leftPadSector = null else rightPadSector = null
                control.button()?.let { controllerListener?.onButtonChanged(it, false) }
            }
            is GameFoldActiveControl.Small -> {
                pressedSmallButtons -= control.button
                control.button()?.let { controllerListener?.onButtonChanged(it, false) }
            }
            GameFoldActiveControl.Home -> {
                controllerListener?.onButtonChanged(GameFoldButton.HOME, false)
            }
            is GameFoldActiveControl.Stick -> {
                cancelStickClickCandidate(control.side)
                if (stickClickActive.remove(control.side)) {
                    controllerListener?.onButtonChanged(control.side.stickButton(), false)
                }
                stickNudgePlayed -= control.side
                stickBoundaryPlayed -= control.side
                controllerListener?.onStickChanged(control.side.stick(), 0f, 0f)
                animateStickHome(control.side)
            }
        }
        invalidate()
    }

    private fun startStickClickCandidate(side: GameFoldPadSide, position: GameFoldPoint) {
        stickClickCandidateStart[side] = position
        val job = Runnable {
            stickClickJobs.remove(side)
            if (stickClickCandidateStart.remove(side) != null && side !in stickClickActive) {
                stickClickActive += side
                feedback?.stickClick()
                feedback?.stickClickSound()
                controllerListener?.onButtonChanged(side.stickButton(), true)
            }
        }
        stickClickJobs[side] = job
        mainHandler.postDelayed(job, GameFoldControllerGeometry.STICK_CLICK_DWELL_MS)
    }

    private fun cancelStickClickCandidate(side: GameFoldPadSide) {
        stickClickJobs.remove(side)?.let(mainHandler::removeCallbacks)
        stickClickCandidateStart.remove(side)
    }

    private fun updateStickFeedback(side: GameFoldPadSide, visual: GameFoldPoint) {
        val distance = visual.distance()
        if (distance > GameFoldControllerGeometry.STICK_DEAD_ZONE && side !in stickNudgePlayed) {
            stickNudgePlayed += side
            feedback?.stickNudge()
        }
        if (distance >= GameFoldControllerGeometry.STICK_MAX_TRAVEL * 0.9f &&
            side !in stickBoundaryPlayed
        ) {
            stickBoundaryPlayed += side
            feedback?.pulse(GameFoldHapticProfile.STICK_BOUNDARY)
            feedback?.stickStop()
        }
    }

    private fun sendStick(side: GameFoldPadSide, visual: GameFoldPoint) {
        val normalized = gameFoldNormalizedStickValue(visual)
        controllerListener?.onStickChanged(side.stick(), normalized.x, normalized.y)
    }

    private fun animateStickHome(side: GameFoldPadSide) {
        val start = if (side == GameFoldPadSide.LEFT) leftStickOffset else rightStickOffset
        stickReturnAnimations.remove(side)?.cancel()
        val animation = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                setStickOffset(side, GameFoldPoint(start.x * fraction, start.y * fraction))
                invalidate()
            }
        }
        stickReturnAnimations[side] = animation
        animation.start()
    }

    private fun setStickOffset(side: GameFoldPadSide, value: GameFoldPoint) {
        if (side == GameFoldPadSide.LEFT) leftStickOffset = value else rightStickOffset = value
    }

    private fun isOccupied(candidate: GameFoldActiveControl): Boolean =
        (0 until activePointers.size()).any { index ->
            val current = activePointers.valueAt(index)
            current !== candidate && current.occupancyKey() == candidate.occupancyKey()
        }

    private fun toLogicalPoint(x: Float, y: Float) = GameFoldPoint(
        (x - shellLeft) / shellScale,
        (y - shellTop) / shellScale
    )

    private fun classifyGamePadControl(screenPoint: GameFoldPoint): GameFoldActiveControl? {
        val fullHd = GamePadNsWindowPolicy.isFullHd(width, height)
        val viewportScale = if (fullHd) 0.82f else
            GamePadNsDisplayMode.controllerScale(width, height)
        val viewportTranslateX = if (fullHd) 16f else 0f
        val viewportTranslateY = if (fullHd) -44f else
            GamePadNsDisplayMode.controllerTranslationY(width, height)
        val viewportPoint = GameFoldPoint(
            (screenPoint.x - viewportTranslateX) / viewportScale,
            (screenPoint.y - viewportTranslateY) / viewportScale
        )
        val homeScale = if (fullHd) 0.75f else viewportScale
        val homeTranslateX = if (fullHd) 144f else 0f
        val homePoint = GameFoldPoint(
            (screenPoint.x - homeTranslateX) / homeScale,
            screenPoint.y / homeScale
        )
        if (homePoint.x in 1978f..2248f && homePoint.y in 56f..133f) {
            return GameFoldActiveControl.Home
        }
        val leftPoint = GameFoldPoint(
            (viewportPoint.x - 19.04f) / 0.72f,
            (viewportPoint.y + 115f) / 0.72f
        )
        classifyGameFoldControlInGroup(
            leftPoint,
            GameFoldControlGroup.LEFT,
            GameFoldControllerGeometry.SMALL_BUTTON_HIT_RADIUS
        )?.let { return it }
        val rightPoint = GameFoldPoint(
            (viewportPoint.x - 499.36f) / 0.72f,
            (viewportPoint.y + 115f) / 0.72f
        )
        return classifyGameFoldControlInGroup(
            rightPoint,
            GameFoldControlGroup.RIGHT,
            GameFoldControllerGeometry.SMALL_BUTTON_HIT_RADIUS
        )
    }

    private fun controllerSourcePoint(
        screenPoint: GameFoldPoint,
        control: GameFoldActiveControl
    ): GameFoldPoint {
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            return toLogicalPoint(screenPoint.x, screenPoint.y)
        }
        val fullHd = GamePadNsWindowPolicy.isFullHd(width, height)
        val viewportScale = if (fullHd) 0.82f else
            GamePadNsDisplayMode.controllerScale(width, height)
        val viewportTranslateY = if (fullHd) -44f else
            GamePadNsDisplayMode.controllerTranslationY(width, height)
        val viewportPoint = GameFoldPoint(
            (screenPoint.x - if (fullHd) 16f else 0f) / viewportScale,
            (screenPoint.y - viewportTranslateY) / viewportScale
        )
        val groupTranslateX = when (control) {
            is GameFoldActiveControl.Stick ->
                if (control.side == GameFoldPadSide.LEFT) 19.04f else 499.36f
            is GameFoldActiveControl.Pad ->
                if (control.side == GameFoldPadSide.LEFT) 19.04f else 499.36f
            else -> 19.04f
        }
        return GameFoldPoint(
            (viewportPoint.x - groupTranslateX) / 0.72f,
            (viewportPoint.y + 115f) / 0.72f
        )
    }

    private fun portraitRightGroupShift(): Float =
        if (portraitMode) {
            // Mirror the two controller groups around the actual portrait viewport center.
            // The reference centers are 483f and 1997f, whose sum is 2480f. This keeps the
            // 2200px reference device at -280px while correcting wider or narrower displays.
            width.toFloat() - (
                GameFoldControllerGeometry.LEFT_STICK_CENTER_X +
                    GameFoldControllerGeometry.RIGHT_STICK_CENTER_X
                )
        } else {
            0f
        }

    private fun portraitHomeShift(): Float =
        if (portraitMode) PORTRAIT_HOME_SHIFT else 0f

    private inline fun drawOnReferenceCanvas(canvas: Canvas, draw: Canvas.() -> Unit) {
        val checkpoint = canvas.save()
        canvas.translate(shellLeft, shellTop)
        canvas.scale(shellScale, shellScale)
        canvas.draw()
        canvas.restoreToCount(checkpoint)
    }

    private fun drawGamePadController(canvas: Canvas) {
        val fullHd = GamePadNsWindowPolicy.isFullHd(width, height)
        val viewportScale = if (fullHd) 0.82f else
            GamePadNsDisplayMode.controllerScale(width, height)
        val viewportTranslateX = if (fullHd) 16f else 0f
        val viewportTranslateY = if (fullHd) -44f else
            GamePadNsDisplayMode.controllerTranslationY(width, height)
        val homeScale = if (fullHd) 0.75f else viewportScale
        val homeTranslateX = if (fullHd) 144f else 0f

        val lowerControlsLayer = canvas.saveLayerAlpha(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            (lowerControlsAlpha * 255f).roundToInt()
        )
        drawWithTransform(canvas, viewportScale, viewportTranslateX, viewportTranslateY) {
            drawWithTransform(this, 0.72f, 19.04f, -115f) {
                drawPositioned(
                    leftPadSector?.let(dpadPressedResources::get)?.let(::pressedDrawable) ?: dpadLayer,
                    130f,
                    1311f,
                    706f,
                    706f
                )
                drawStick(483f, leftStickOffset)
                drawSmallButton(GameFoldSmallButton.ZL, zlLayer, 93f, 1277f)
                drawSmallButton(GameFoldSmallButton.L, lLayer, 734f, 1277f)
                drawSmallButton(GameFoldSmallButton.MINUS, minusLayer, 734f, 1912f)
            }
        }
        drawWithTransform(canvas, viewportScale, viewportTranslateX, viewportTranslateY) {
            drawWithTransform(this, 0.72f, 499.36f, -115f) {
                drawPositioned(
                    rightPadSector?.let(abxyPressedResources::get)?.let(::pressedDrawable) ?: abxyLayer,
                    1644f,
                    1311f,
                    706f,
                    706f
                )
                drawStick(1997f, rightStickOffset)
                drawSmallButton(GameFoldSmallButton.R, rLayer, 1608f, 1277f)
                drawSmallButton(GameFoldSmallButton.ZR, zrLayer, 2249f, 1277f)
                drawSmallButton(GameFoldSmallButton.PLUS, plusLayer, 1608f, 1912f)
            }
        }
        canvas.restoreToCount(lowerControlsLayer)
        drawWithTransform(canvas, homeScale, homeTranslateX, 0f) {
            drawPositioned(homeLayer, 1978f, 56f, 270f, 77f)
        }
    }

    private inline fun drawWithTransform(
        canvas: Canvas,
        scale: Float,
        translateX: Float,
        translateY: Float,
        draw: Canvas.() -> Unit
    ) {
        val checkpoint = canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)
        canvas.draw()
        canvas.restoreToCount(checkpoint)
    }

    private fun drawDisplayCoverMask(canvas: Canvas) {
        val longEdge = max(width, height).toFloat()
        val shortEdge = min(width, height).toFloat()
        if (shortEdge <= 0f) return

        val x = longEdge / shortEdge
        val y = (
            0.031f -
                0.425f * (x - 1.083f) -
                18.75f * (x - 1.083f) * (x - 1.103f)
            ).coerceAtLeast(0f)
        val maskHeight = shortEdge * y
        if (maskHeight > 0f) {
            canvas.drawRect(0f, 0f, longEdge, maskHeight, displayCoverMaskPaint)
        }
    }

    private fun drawLandscapeDisplayBorder(canvas: Canvas) {
        // Use one integer-aligned filled outer frame. The child content is independently
        // laid out at outer + DISPLAY_BORDER on all sides, so no stroke edge can consume an
        // extra pixel on the right or bottom during rasterization.
        canvas.drawRoundRect(
            landscapeDisplayLeft,
            landscapeDisplayTop,
            landscapeDisplayLeft + landscapeDisplayWidth,
            landscapeDisplayTop + landscapeDisplayHeight,
            DISPLAY_CORNER_RADIUS,
            DISPLAY_CORNER_RADIUS,
            landscapeDisplayBorderPaint
        )
    }

    private fun drawPortraitDisplayBorder(canvas: Canvas) {
        val border = PORTRAIT_DISPLAY_BORDER * portraitDisplayScale
        val rect = RectF(
            portraitDisplayLeft - border,
            portraitDisplayTop - border,
            portraitDisplayLeft + portraitDisplayWidth + border,
            portraitDisplayTop + portraitDisplayHeight + border
        )
        val radii = floatArrayOf(
            portraitOuterTopLeftRadius,
            portraitOuterTopLeftRadius,
            portraitOuterTopRightRadius,
            portraitOuterTopRightRadius,
            PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale,
            PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale,
            PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale,
            PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale
        )
        val path = Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
        canvas.drawPath(path, portraitDisplayBorderPaint)
    }

    /**
     * Computes only the portrait display's top corner radii from the current window's physical
     * rounded-corner centers. The bottom corners intentionally retain the product's existing
     * radius because they sit next to the portrait hinge rather than the device's outer corners.
     */
    private fun updatePortraitDisplayCornerRadii(insets: WindowInsets? = rootWindowInsets) {
        if (!portraitMode || portraitDisplayWidth <= 0f || portraitDisplayHeight <= 0f) return

        val border = PORTRAIT_DISPLAY_BORDER * portraitDisplayScale
        val innerLeft = portraitDisplayLeft
        val innerTop = portraitDisplayTop
        val innerRight = portraitDisplayLeft + portraitDisplayWidth
        val innerBottom = portraitDisplayTop + portraitDisplayHeight
        val fallbackInnerRadius = PORTRAIT_DISPLAY_CORNER_RADIUS * portraitDisplayScale
        val fallbackOuterRadius = fallbackInnerRadius + border

        val topLeft = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        } else {
            null
        }
        val topRight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        } else {
            null
        }

        val systemInnerTopLeftRadius = topLeft?.let {
            if (it.getRadius() > 0) {
                nestedTopLeftRadius(it, innerLeft, innerTop, innerRight, innerBottom)
            } else {
                null
            }
        }
        val systemInnerTopRightRadius = topRight?.let {
            if (it.getRadius() > 0) {
                nestedTopRightRadius(it, innerLeft, innerTop, innerRight, innerBottom)
            } else {
                null
            }
        }

        portraitInnerTopLeftRadius = systemInnerTopLeftRadius?.let {
            applyPortraitTopCornerCompensation(
                it,
                innerRight - innerLeft,
                innerBottom - innerTop,
                fallbackInnerRadius
            )
        } ?: fallbackInnerRadius
        portraitInnerTopRightRadius = systemInnerTopRightRadius?.let {
            applyPortraitTopCornerCompensation(
                it,
                innerRight - innerLeft,
                innerBottom - innerTop,
                fallbackInnerRadius
            )
        } ?: fallbackInnerRadius
        // Keep the bezel width constant: the outer arc follows the compensated inner arc plus
        // the existing border, so the two GameFold arcs remain concentric with one another.
        portraitOuterTopLeftRadius = if (systemInnerTopLeftRadius != null) {
            portraitInnerTopLeftRadius + border
        } else {
            fallbackOuterRadius
        }
        portraitOuterTopRightRadius = if (systemInnerTopRightRadius != null) {
            portraitInnerTopRightRadius + border
        } else {
            fallbackOuterRadius
        }
    }

    private fun applyPortraitTopCornerCompensation(
        radius: Float,
        width: Float,
        height: Float,
        minimumRadius: Float
    ): Float = (radius * PORTRAIT_TOP_CORNER_COMPENSATION)
        .coerceAtLeast(minimumRadius)
        .coerceAtMost(min(width, height) / 2f)

    private fun nestedTopLeftRadius(
        corner: RoundedCorner,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Float {
        val center = corner.getCenter()
        return min(center.x - left, center.y - top).coerceIn(
            0f,
            min((right - left) / 2f, (bottom - top) / 2f)
        )
    }

    private fun nestedTopRightRadius(
        corner: RoundedCorner,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Float {
        val center = corner.getCenter()
        return min(right - center.x, center.y - top).coerceIn(
            0f,
            min((right - left) / 2f, (bottom - top) / 2f)
        )
    }

    private fun drawPortraitHinge(canvas: Canvas) {
        portraitHingeLayer.setBounds(
            0,
            portraitHingeTop.roundToInt(),
            width,
            (portraitHingeTop + PORTRAIT_HINGE_SOURCE_HEIGHT * portraitDisplayScale).roundToInt()
        )
        portraitHingeLayer.draw(canvas)
    }

    private fun drawPortraitController(canvas: Canvas) {
        drawOnReferenceCanvas(canvas) {
            drawPositioned(
                leftPadSector?.let(dpadPressedResources::get)?.let(::pressedDrawable) ?: dpadLayer,
                130f,
                1311f,
                706f,
                706f
            )
            drawPositioned(
                rightPadSector?.let(abxyPressedResources::get)?.let(::pressedDrawable) ?: abxyLayer,
                1644f + portraitRightGroupShift(),
                1311f,
                706f,
                706f
            )
            drawStick(483f, leftStickOffset)
            drawStick(1997f + portraitRightGroupShift(), rightStickOffset)
            drawSmallButton(GameFoldSmallButton.ZL, zlLayer, 93f, 1277f)
            drawSmallButton(GameFoldSmallButton.L, lLayer, 734f, 1277f)
            drawSmallButton(GameFoldSmallButton.R, rLayer, 1608f + portraitRightGroupShift(), 1277f)
            drawSmallButton(GameFoldSmallButton.ZR, zrLayer, 2249f + portraitRightGroupShift(), 1277f)
            drawSmallButton(GameFoldSmallButton.MINUS, minusLayer, 734f, 1912f)
            drawSmallButton(GameFoldSmallButton.PLUS, plusLayer, 1608f + portraitRightGroupShift(), 1912f)
        }
    }

    private fun drawPortraitLogo(canvas: Canvas) {
        val logoWidth = PORTRAIT_LOGO_SOURCE_WIDTH * portraitDisplayScale
        val logoHeight = PORTRAIT_LOGO_SOURCE_HEIGHT * portraitDisplayScale
        val shoulderCenterY = shellTop + (GameFoldControllerGeometry.SHOULDER_Y +
            GameFoldControllerGeometry.SMALL_BUTTON_SIZE / 2f) * shellScale
        portraitLogoLayer.setBounds(
            ((width - logoWidth) / 2f).roundToInt(),
            (shoulderCenterY - logoHeight / 2f).roundToInt(),
            ((width + logoWidth) / 2f).roundToInt(),
            (shoulderCenterY + logoHeight / 2f).roundToInt()
        )
        portraitLogoLayer.draw(canvas)
    }

    private fun Canvas.drawFullCanvas(layer: Drawable) {
        drawPositioned(layer, 0f, 0f, DESIGN_WIDTH, DESIGN_HEIGHT)
    }

    private fun drawUnsupportedUsage(canvas: Canvas) {
        canvas.drawColor(resources.getColor(R.color.gamefold_splash_background, context.theme))
        val imageSize = (min(width, height) * 0.52f).roundToInt()
        val left = (width - imageSize) / 2
        val top = ((height - imageSize) / 2f - imageSize * 0.08f).roundToInt()
        unsupportedUsageLayer.setBounds(left, top, left + imageSize, top + imageSize)
        unsupportedUsageLayer.draw(canvas)
        unsupportedTextPaint.textSize = min(width, height) * 0.043f
        canvas.drawText(
            resources.getString(R.string.gamefold_unsupported_mode),
            width / 2f,
            (top + imageSize * 0.89f).coerceAtMost(height * 0.86f),
            unsupportedTextPaint
        )
    }

    private fun updateSupportedUsageMode(width: Int, height: Int) {
        val supported = if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            GamePadNsWindowPolicy.isSupported(context, width, height)
        } else {
            val activity = context.findActivity()
            val config = resources.configuration
            val inMultiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                activity?.isInMultiWindowMode == true
            val inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity?.isInPictureInPictureMode == true
            val isPortraitLayout = height > width
            !inMultiWindow &&
                !inPictureInPicture &&
                config.orientation in setOf(
                    Configuration.ORIENTATION_LANDSCAPE,
                    Configuration.ORIENTATION_PORTRAIT
                ) &&
                config.smallestScreenWidthDp >= INNER_DISPLAY_SMALLEST_WIDTH_DP &&
                (isPortraitLayout || config.screenWidthDp >= INNER_DISPLAY_MIN_WIDTH_DP) &&
                config.screenHeightDp >= INNER_DISPLAY_MIN_HEIGHT_DP
        }
        if (supportedUsageMode == supported) return

        supportedUsageMode = supported
        if (!supported) releaseControllerInputs()
        for (index in 0 until childCount) {
            getChildAt(index).visibility = if (supported) View.VISIBLE else View.INVISIBLE
        }
        importantForAccessibility = if (supported) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        invalidate()
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun Canvas.drawStick(centerX: Float, offset: GameFoldPoint) {
        drawPositioned(
            stickCapLayer,
            centerX - 175.5f + offset.x,
            1488.5f + offset.y,
            351f,
            351f
        )
    }

    private fun Canvas.drawSmallButton(
        button: GameFoldSmallButton,
        defaultLayer: Drawable,
        x: Float,
        y: Float
    ) {
        val layer = if (button in pressedSmallButtons) {
            pressedDrawable(requireNotNull(smallPressedResources[button]))
        } else {
            defaultLayer
        }
        drawPositioned(layer, x, y, 138f, 138f)
    }

    private fun Canvas.drawPositioned(
        layer: Drawable,
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ) {
        layer.setBounds(
            x.roundToInt(),
            y.roundToInt(),
            (x + width).roundToInt(),
            (y + height).roundToInt()
        )
        layer.draw(this)
    }

    private fun drawable(resourceId: Int): Drawable =
        requireNotNull(context.getDrawable(resourceId)).mutate()

    private fun pressedDrawable(resourceId: Int): Drawable =
        pressedDrawableCache.getOrPut(resourceId) { drawable(resourceId) }

    override fun onDetachedFromWindow() {
        releaseControllerInputs()
        stickReturnAnimations.values.forEach(ValueAnimator::cancel)
        stickReturnAnimations.clear()
        feedback?.release()
        feedback = null
        super.onDetachedFromWindow()
    }

    private class RoundedDisplayOutlineProvider(
        private val topLeftRadius: Float,
        private val topRightRadius: Float,
        private val bottomRightRadius: Float,
        private val bottomLeftRadius: Float
    ) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val radii = floatArrayOf(
                topLeftRadius,
                topLeftRadius,
                topRightRadius,
                topRightRadius,
                bottomRightRadius,
                bottomRightRadius,
                bottomLeftRadius,
                bottomLeftRadius
            )
            val path = Path().apply {
                addRoundRect(
                    RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                    radii,
                    Path.Direction.CW
                )
            }
            outline.setPath(path)
        }
    }

    companion object {
        const val DESIGN_WIDTH = 2480f
        const val DESIGN_HEIGHT = 2200f

        // The cover asset is 2480 x 2200 and its drawable bottom edge is y=1042 in the
        // reference canvas. LANDSCAPE_DISPLAY_TOP_PX and LANDSCAPE_DISPLAY_BOTTOM_GAP_PX are
        // actual window pixels; they are intentionally not scaled or converted to dp.
        const val COVER_BOTTOM = 1042f
        const val LANDSCAPE_DISPLAY_TOP_PX = 30f
        const val LANDSCAPE_DISPLAY_BOTTOM_GAP_PX = 32f
        const val DISPLAY_CORNER_RADIUS = 19.5f
        const val DISPLAY_BORDER = 7f

        const val PORTRAIT_DISPLAY_SOURCE_WIDTH = 2200f
        const val PORTRAIT_DISPLAY_MARGIN = 23f
        const val PORTRAIT_DISPLAY_CORNER_RADIUS = 15f
        const val PORTRAIT_DISPLAY_BORDER = 7f
        // Optical compensation for the device bezel. The default portrait R is the lower bound;
        // portrait bottom corners and all landscape corners retain their baselines.
        const val PORTRAIT_TOP_CORNER_COMPENSATION = 1.20f
        const val PORTRAIT_HINGE_SOURCE_HEIGHT = 158f
        const val PORTRAIT_LOGO_SOURCE_WIDTH = 326f
        const val PORTRAIT_LOGO_SOURCE_HEIGHT = 85f
        const val PORTRAIT_CONTROL_BOTTOM_LIFT = 40f
        const val PORTRAIT_HOME_SHIFT = -140f

        // Keep the existing landscape compatibility gate while allowing the approved portrait
        // layout to use the full inner display width of the Switch device.
        private const val INNER_DISPLAY_SMALLEST_WIDTH_DP = 600
        private const val INNER_DISPLAY_MIN_WIDTH_DP = 700
        private const val INNER_DISPLAY_MIN_HEIGHT_DP = 560

        /**
         * Returns the same coarse device/window compatibility result used by the shell before
         * any child content is exposed. The assembly gate uses this early check so unsupported
         * postures never reach the permission dialog or game-resource preflight.
         */
        fun isUnsupportedUsage(context: Context): Boolean {
            if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
                val metrics = context.resources.displayMetrics
                return !GamePadNsWindowPolicy.isSupported(
                    context,
                    metrics.widthPixels,
                    metrics.heightPixels
                )
            }
            val activity = findActivityForGate(context)
            val config = context.resources.configuration
            val inMultiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                activity?.isInMultiWindowMode == true
            val inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity?.isInPictureInPictureMode == true
            val portraitLayout = config.screenHeightDp > config.screenWidthDp
            val supported = !inMultiWindow &&
                !inPictureInPicture &&
                config.orientation in setOf(
                    Configuration.ORIENTATION_LANDSCAPE,
                    Configuration.ORIENTATION_PORTRAIT
                ) &&
                config.smallestScreenWidthDp >= INNER_DISPLAY_SMALLEST_WIDTH_DP &&
                (portraitLayout || config.screenWidthDp >= INNER_DISPLAY_MIN_WIDTH_DP) &&
                config.screenHeightDp >= INNER_DISPLAY_MIN_HEIGHT_DP
            return !supported
        }

        private fun findActivityForGate(context: Context): Activity? = when (context) {
            is Activity -> context
            is ContextWrapper -> findActivityForGate(context.baseContext)
            else -> null
        }

        /** Applies the same rounded aperture to SurfaceView and other independently rendered views. */
        fun applyDisplayClip(view: View) {
            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(
                        0,
                        0,
                        target.width,
                        target.height,
                        DISPLAY_CORNER_RADIUS - DISPLAY_BORDER
                    )
                }
            }
            view.invalidateOutline()
        }
    }
}
