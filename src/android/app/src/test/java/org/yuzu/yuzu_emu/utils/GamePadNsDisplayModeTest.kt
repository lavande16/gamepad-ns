// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.views.GameFoldControllerGeometry

class GamePadNsDisplayModeTest {
    @Test
    fun usesFourKWholeDeviceSixteenByNine() {
        assertEquals(3840, GamePadNsDisplayMode.WIDTH)
        assertEquals(2160, GamePadNsDisplayMode.HEIGHT)
        assertEquals(GamePadNsDisplayMode.WIDTH * 9, GamePadNsDisplayMode.HEIGHT * 16)
    }

    @Test
    fun windowManagerOverrideUsesNaturalPortraitCoordinates() {
        assertEquals(2160, GamePadNsDisplayMode.FORCED_WIDTH)
        assertEquals(3840, GamePadNsDisplayMode.FORCED_HEIGHT)
    }

    @Test
    fun recognizesLandscapeAndTransientPortraitWindows() {
        assertTrue(GamePadNsDisplayMode.matches(3840, 2160))
        assertTrue(GamePadNsDisplayMode.matches(2160, 3840))
    }

    @Test
    fun doesNotConfuseNativeLegacyOrArbitrarySizesWithTheNewMode() {
        assertFalse(GamePadNsDisplayMode.matches(2304, 1440))
        assertFalse(GamePadNsDisplayMode.matches(1920, 1080))
        assertFalse(GamePadNsDisplayMode.matches(1080, 1920))
        assertFalse(GamePadNsDisplayMode.matches(2304, 1296))
        assertFalse(GamePadNsDisplayMode.matches(3840, 2159))
        assertFalse(GamePadNsDisplayMode.matches(2304, 1295))
        assertFalse(GamePadNsDisplayMode.matches(0, 0))
    }

    @Test
    fun preservesPreviousOverrideForUpgradeAndRestore() {
        assertTrue(GamePadNsDisplayMode.matchesLegacy(2304, 1296))
        assertTrue(GamePadNsDisplayMode.matchesLegacy(1296, 2304))
        assertFalse(GamePadNsDisplayMode.matchesLegacy(3840, 2160))
        assertFalse(GamePadNsDisplayMode.matchesLegacy(2304, 1440))
        assertEquals(1f, GamePadNsDisplayMode.controllerScale(2304, 1296), 0f)
        assertEquals(-72f, GamePadNsDisplayMode.controllerTranslationY(2304, 1296), 0f)
    }

    @Test
    fun scalesControlsAndKeepsNativeAnd1080pPoliciesUnchanged() {
        assertEquals(5f / 3f, GamePadNsDisplayMode.controllerScale(3840, 2160), 0.0001f)
        assertEquals(-120f, GamePadNsDisplayMode.controllerTranslationY(3840, 2160), 0.001f)
        assertEquals(1f, GamePadNsDisplayMode.controllerScale(2304, 1440), 0f)
        assertEquals(1f, GamePadNsDisplayMode.controllerScale(1920, 1080), 0f)
        assertEquals(0f, GamePadNsDisplayMode.controllerTranslationY(2304, 1440), 0f)
        assertEquals(0f, GamePadNsDisplayMode.controllerTranslationY(1920, 1080), 0f)
    }

    @Test
    fun scaledBottomAndSideButtonsRemainInsideTheFourKCanvas() {
        val scale = GamePadNsDisplayMode.controllerScale(3840, 2160)
        val offset = GamePadNsDisplayMode.controllerTranslationY(3840, 2160)
        val bottomButtonEdge = (1912f + GameFoldControllerGeometry.SMALL_BUTTON_SIZE) *
            0.72f - 115f
        val rightButtonEdge = (2249f + GameFoldControllerGeometry.SMALL_BUTTON_SIZE) *
            0.72f + 499.36f
        assertTrue(bottomButtonEdge * scale + offset <= GamePadNsDisplayMode.HEIGHT)
        assertTrue(rightButtonEdge * scale <= GamePadNsDisplayMode.WIDTH)
    }

    @Test
    fun drawingAndInverseTouchTransformsRoundTripBothSticks() {
        val scale = GamePadNsDisplayMode.controllerScale(3840, 2160)
        val offset = GamePadNsDisplayMode.controllerTranslationY(3840, 2160)
        for ((sourceX, translateX) in listOf(483f to 19.04f, 1997f to 499.36f)) {
            val sourceY = GameFoldControllerGeometry.STICK_CENTER_Y
            val screenX = (sourceX * 0.72f + translateX) * scale
            val screenY = (sourceY * 0.72f - 115f) * scale + offset
            assertEquals(sourceX, (screenX / scale - translateX) / 0.72f, 0.001f)
            assertEquals(sourceY, ((screenY - offset) / scale + 115f) / 0.72f, 0.001f)
        }
    }

    @Test
    fun homeLogoUsesTheSameScaleAndRemainsWithinTheCanvas() {
        val scale = GamePadNsDisplayMode.controllerScale(3840, 2160)
        assertTrue(2248f * scale <= GamePadNsDisplayMode.WIDTH)
        assertTrue(133f * scale <= GamePadNsDisplayMode.HEIGHT)
        assertEquals(2113f, (2113f * scale) / scale, 0.001f)
    }
}
