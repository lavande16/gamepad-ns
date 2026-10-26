// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.yuzu.yuzu_emu.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot

enum class GameFoldButton {
    DPAD_UP,
    DPAD_RIGHT,
    DPAD_DOWN,
    DPAD_LEFT,
    X,
    A,
    B,
    Y,
    MINUS,
    PLUS,
    L,
    R,
    ZL,
    ZR,
    L3,
    R3,
    HOME
}

enum class GameFoldStick { LEFT, RIGHT }

interface GameFoldControllerListener {
    fun onButtonChanged(button: GameFoldButton, pressed: Boolean)
    fun onStickChanged(stick: GameFoldStick, x: Float, y: Float)
}

internal data class GameFoldPoint(val x: Float, val y: Float) {
    operator fun minus(other: GameFoldPoint) = GameFoldPoint(x - other.x, y - other.y)
    fun distance() = hypot(x, y)
    fun distanceSquared() = x * x + y * y
}

internal enum class GameFoldPadSide { LEFT, RIGHT }
internal enum class GameFoldControlGroup { LEFT, RIGHT }
internal enum class GameFoldPadSector { TOP, RIGHT, BOTTOM, LEFT }
internal enum class GameFoldSmallButton { ZL, L, R, ZR, MINUS, PLUS }

internal sealed interface GameFoldActiveControl {
    data class Stick(val side: GameFoldPadSide) : GameFoldActiveControl
    data class Pad(val side: GameFoldPadSide, val sector: GameFoldPadSector) : GameFoldActiveControl
    data class Small(val button: GameFoldSmallButton) : GameFoldActiveControl
    data object Home : GameFoldActiveControl
}

internal object GameFoldControllerGeometry {
    const val PAD_HALF = 350f
    const val LEFT_PAD_X = 133f
    const val RIGHT_PAD_X = 1647f
    const val PAD_Y = 1314f
    const val LEFT_STICK_CENTER_X = LEFT_PAD_X + PAD_HALF
    const val RIGHT_STICK_CENTER_X = RIGHT_PAD_X + PAD_HALF
    const val STICK_CENTER_Y = PAD_Y + PAD_HALF
    const val STICK_ZONE_RADIUS = 149.5f
    const val STICK_CAP_VISIBLE_RADIUS = 80f
    const val STICK_MAX_TRAVEL = (STICK_ZONE_RADIUS - STICK_CAP_VISIBLE_RADIUS) * 1.2f
    const val STICK_DEAD_ZONE = STICK_MAX_TRAVEL * 0.12f
    const val STICK_CLICK_CANDIDATE_RADIUS = 72f
    const val STICK_CLICK_JITTER_TOLERANCE = 18f
    const val STICK_CLICK_DWELL_MS = 600L
    const val PAD_OUTWARD_HIT_EXTENSION = (PAD_HALF - STICK_ZONE_RADIUS) * 0.25f
    const val PAD_HIT_HALF = PAD_HALF + PAD_OUTWARD_HIT_EXTENSION

    const val SMALL_BUTTON_SIZE = 138f
    const val SMALL_BUTTON_RADIUS = SMALL_BUTTON_SIZE / 2f
    const val SMALL_BUTTON_HIT_RADIUS = SMALL_BUTTON_RADIUS * 1.8f
    const val ZL_X = 93f
    const val L_X = 734f
    const val R_X = 1608f
    const val ZR_X = 2249f
    const val SHOULDER_Y = 1277f
    const val MINUS_X = 734f
    const val PLUS_X = 1608f
    const val MINUS_PLUS_Y = 1912f

    // Generous touch target around the baked-in GAMEFOLD wordmark. It stays clear of
    // the L/R shoulder buttons and both directional pads.
    const val HOME_LEFT = 1040f
    const val HOME_TOP = 1280f
    const val HOME_RIGHT = 1440f
    const val HOME_BOTTOM = 1435f

}

internal fun classifyGameFoldControl(
    position: GameFoldPoint,
    rightGroupShift: Float = 0f,
    homeShift: Float = 0f
): GameFoldActiveControl? {
    classifyGameFoldStick(position, GameFoldPadSide.LEFT, rightGroupShift)?.let { return it }
    classifyGameFoldStick(position, GameFoldPadSide.RIGHT, rightGroupShift)?.let { return it }
    classifyGameFoldSmallButton(position, rightGroupShift)?.let { return GameFoldActiveControl.Small(it) }
    if (position.x in (GameFoldControllerGeometry.HOME_LEFT + homeShift)..
        (GameFoldControllerGeometry.HOME_RIGHT + homeShift) &&
        position.y in GameFoldControllerGeometry.HOME_TOP..GameFoldControllerGeometry.HOME_BOTTOM
    ) {
        return GameFoldActiveControl.Home
    }
    classifyGameFoldPad(position, GameFoldPadSide.LEFT, rightGroupShift)?.let { return it }
    return classifyGameFoldPad(position, GameFoldPadSide.RIGHT, rightGroupShift)
}

private fun classifyGameFoldStick(
    position: GameFoldPoint,
    side: GameFoldPadSide,
    rightGroupShift: Float
): GameFoldActiveControl.Stick? =
    if ((position - gameFoldStickCenter(side, rightGroupShift)).distance() <=
        GameFoldControllerGeometry.STICK_ZONE_RADIUS
    ) {
        GameFoldActiveControl.Stick(side)
    } else {
        null
    }

private fun classifyGameFoldSmallButton(
    position: GameFoldPoint,
    rightGroupShift: Float,
    hitRadius: Float = GameFoldControllerGeometry.SMALL_BUTTON_HIT_RADIUS
): GameFoldSmallButton? {
    val radius = GameFoldControllerGeometry.SMALL_BUTTON_RADIUS
    val targets = listOf(
        GameFoldSmallButton.MINUS to GameFoldPoint(
            GameFoldControllerGeometry.MINUS_X + radius,
            GameFoldControllerGeometry.MINUS_PLUS_Y + radius
        ),
        GameFoldSmallButton.PLUS to GameFoldPoint(
            GameFoldControllerGeometry.PLUS_X + rightGroupShift + radius,
            GameFoldControllerGeometry.MINUS_PLUS_Y + radius
        ),
        GameFoldSmallButton.ZL to GameFoldPoint(
            GameFoldControllerGeometry.ZL_X + radius,
            GameFoldControllerGeometry.SHOULDER_Y + radius
        ),
        GameFoldSmallButton.L to GameFoldPoint(
            GameFoldControllerGeometry.L_X + radius,
            GameFoldControllerGeometry.SHOULDER_Y + radius
        ),
        GameFoldSmallButton.R to GameFoldPoint(
            GameFoldControllerGeometry.R_X + rightGroupShift + radius,
            GameFoldControllerGeometry.SHOULDER_Y + radius
        ),
        GameFoldSmallButton.ZR to GameFoldPoint(
            GameFoldControllerGeometry.ZR_X + rightGroupShift + radius,
            GameFoldControllerGeometry.SHOULDER_Y + radius
        )
    )
    val hitRadiusSquared = hitRadius * hitRadius
    return targets.firstOrNull { (_, center) ->
        (position - center).distanceSquared() <= hitRadiusSquared
    }?.first
}

internal fun classifyGameFoldControlInGroup(
    position: GameFoldPoint,
    group: GameFoldControlGroup,
    smallButtonHitRadius: Float = GameFoldControllerGeometry.SMALL_BUTTON_HIT_RADIUS
): GameFoldActiveControl? {
    val side = if (group == GameFoldControlGroup.LEFT) GameFoldPadSide.LEFT else GameFoldPadSide.RIGHT
    classifyGameFoldStick(position, side, 0f)?.let { return it }
    classifyGameFoldSmallButton(position, 0f, smallButtonHitRadius)?.let { button ->
        val belongs = when (group) {
            GameFoldControlGroup.LEFT -> button in setOf(
                GameFoldSmallButton.ZL,
                GameFoldSmallButton.L,
                GameFoldSmallButton.MINUS
            )
            GameFoldControlGroup.RIGHT -> button in setOf(
                GameFoldSmallButton.R,
                GameFoldSmallButton.ZR,
                GameFoldSmallButton.PLUS
            )
        }
        if (belongs) return GameFoldActiveControl.Small(button)
    }
    return classifyGameFoldPad(position, side, 0f)
}

private fun classifyGameFoldPad(
    position: GameFoldPoint,
    side: GameFoldPadSide,
    rightGroupShift: Float
): GameFoldActiveControl.Pad? {
    val delta = position - gameFoldStickCenter(side, rightGroupShift)
    if (delta.distance() <= GameFoldControllerGeometry.STICK_ZONE_RADIUS) return null
    if (abs(delta.x) + abs(delta.y) > GameFoldControllerGeometry.PAD_HIT_HALF) return null
    val sector = if (abs(delta.x) > abs(delta.y)) {
        if (delta.x >= 0f) GameFoldPadSector.RIGHT else GameFoldPadSector.LEFT
    } else {
        if (delta.y >= 0f) GameFoldPadSector.BOTTOM else GameFoldPadSector.TOP
    }
    return GameFoldActiveControl.Pad(side, sector)
}

internal fun gameFoldStickCenter(side: GameFoldPadSide, rightGroupShift: Float = 0f) = GameFoldPoint(
    if (side == GameFoldPadSide.LEFT) {
        GameFoldControllerGeometry.LEFT_STICK_CENTER_X
    } else {
        GameFoldControllerGeometry.RIGHT_STICK_CENTER_X + rightGroupShift
    },
    GameFoldControllerGeometry.STICK_CENTER_Y
)

internal fun gameFoldVisualStickOffset(
    position: GameFoldPoint,
    side: GameFoldPadSide,
    rightGroupShift: Float = 0f
): GameFoldPoint {
    val delta = position - gameFoldStickCenter(side, rightGroupShift)
    val distance = delta.distance()
    if (distance <= 0.001f) return GameFoldPoint(0f, 0f)
    val clamped = distance.coerceAtMost(GameFoldControllerGeometry.STICK_MAX_TRAVEL)
    return GameFoldPoint(delta.x / distance * clamped, delta.y / distance * clamped)
}

internal fun gameFoldNormalizedStickValue(visual: GameFoldPoint): GameFoldPoint {
    val distance = visual.distance()
    if (distance <= GameFoldControllerGeometry.STICK_DEAD_ZONE) return GameFoldPoint(0f, 0f)
    val adjusted = (
        (distance - GameFoldControllerGeometry.STICK_DEAD_ZONE) /
            (GameFoldControllerGeometry.STICK_MAX_TRAVEL -
                GameFoldControllerGeometry.STICK_DEAD_ZONE)
        ).coerceIn(0f, 1f)
    return GameFoldPoint(visual.x / distance * adjusted, visual.y / distance * adjusted)
}

internal fun GameFoldActiveControl.occupancyKey(): String = when (this) {
    is GameFoldActiveControl.Pad -> "pad-${side.name}"
    is GameFoldActiveControl.Stick -> "pad-${side.name}"
    is GameFoldActiveControl.Small -> "small-${button.name}"
    GameFoldActiveControl.Home -> "home"
}

internal fun GameFoldActiveControl.button(): GameFoldButton? = when (this) {
    is GameFoldActiveControl.Pad -> when (side) {
        GameFoldPadSide.LEFT -> when (sector) {
            GameFoldPadSector.TOP -> GameFoldButton.DPAD_UP
            GameFoldPadSector.RIGHT -> GameFoldButton.DPAD_RIGHT
            GameFoldPadSector.BOTTOM -> GameFoldButton.DPAD_DOWN
            GameFoldPadSector.LEFT -> GameFoldButton.DPAD_LEFT
        }
        GameFoldPadSide.RIGHT -> when (sector) {
            // The supplied NS ABXY artwork defines X=top, A=right, B=bottom, Y=left.
            // Keep the logical result aligned with the label painted at each sector.
            GameFoldPadSector.TOP -> GameFoldButton.X
            GameFoldPadSector.RIGHT -> GameFoldButton.A
            GameFoldPadSector.BOTTOM -> GameFoldButton.B
            GameFoldPadSector.LEFT -> GameFoldButton.Y
        }
    }
    is GameFoldActiveControl.Small -> when (button) {
        GameFoldSmallButton.MINUS -> GameFoldButton.MINUS
        GameFoldSmallButton.PLUS -> GameFoldButton.PLUS
        GameFoldSmallButton.L -> GameFoldButton.L
        GameFoldSmallButton.R -> GameFoldButton.R
        GameFoldSmallButton.ZL -> GameFoldButton.ZL
        GameFoldSmallButton.ZR -> GameFoldButton.ZR
    }
    is GameFoldActiveControl.Stick -> null
    GameFoldActiveControl.Home -> GameFoldButton.HOME
}

internal fun GameFoldPadSide.stick() =
    if (this == GameFoldPadSide.LEFT) GameFoldStick.LEFT else GameFoldStick.RIGHT

internal fun GameFoldPadSide.stickButton() =
    if (this == GameFoldPadSide.LEFT) GameFoldButton.L3 else GameFoldButton.R3

internal enum class GameFoldHapticProfile(val durationMs: Long, val amplitude: Int) {
    ACTION_CLICK(24L, 180),
    AUXILIARY_CLICK(32L, 150),
    STICK_PICKUP(8L, 40),
    STICK_BOUNDARY(6L, 35)
}

internal class GameFoldControllerFeedback(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val loadedIds = ConcurrentHashMap.newKeySet<Int>()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val dpadId: Int
    private val auxiliaryId: Int
    private val stickClickId: Int
    private val stickNudgeId: Int
    private val stickStopId: Int

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds += sampleId
        }
        dpadId = soundPool.load(context, R.raw.controller_dpad_tick, 1)
        auxiliaryId = soundPool.load(context, R.raw.controller_aux_click, 1)
        stickClickId = soundPool.load(context, R.raw.controller_stick_click, 1)
        stickNudgeId = soundPool.load(context, R.raw.controller_stick_nudge, 1)
        stickStopId = soundPool.load(context, R.raw.controller_stick_stop, 1)
    }

    fun pulse(profile: GameFoldHapticProfile) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(profile.durationMs, profile.amplitude))
    }

    fun stickClick() {
        if (!vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(80L, 255)
        }
        vibrator.vibrate(effect)
    }

    fun dpad() = play(dpadId, 0.08f)
    fun action() = dpad()
    fun auxiliary() = play(auxiliaryId, 0.06f)
    fun stickClickSound() = play(stickClickId, 0.075f)
    fun stickNudge() = play(stickNudgeId, 0.05f)
    fun stickStop() = play(stickStopId, 0.045f)

    private fun play(sampleId: Int, volume: Float) {
        if (sampleId !in loadedIds) return
        val amplified = (volume * 3.2f).coerceAtMost(1f)
        soundPool.play(sampleId, amplified, amplified, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
        loadedIds.clear()
    }
}
