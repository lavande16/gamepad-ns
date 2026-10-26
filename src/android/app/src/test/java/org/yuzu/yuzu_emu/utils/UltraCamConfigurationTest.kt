// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraCamConfigurationTest {
    @Test
    fun appliesBotwProfileWithoutChangingIniShape() {
        val root = Files.createTempDirectory("ultracam-botw").toFile()
        try {
            val file = root.resolve("romfs/UltraCam/maxlastbreath.ini")
            check(file.parentFile?.mkdirs() == true)
            file.writeText(
                "[Resolution]\r\n" +
                    "MaxFramerate = 30\r\n" +
                    "MenuFPS = 30\r\n" +
                    "QualityImprovements = On\r\n" +
                    "RenderDistance = 4000\r\n" +
                    "Width = 1280\r\n" +
                    "Height = 720\r\n\r\n" +
                    "[Features]\r\nFov = 50\r\n"
            )

            val result = UltraCamConfiguration.apply(
                UltraCamConfiguration.BOTW_TITLE_ID,
                root,
                null
            )
            val rewritten = file.readText()

            assertTrue(result?.changed == true)
            assertTrue(result?.missingKeys?.isEmpty() == true)
            assertTrue(rewritten.contains("MaxFramerate = 60\r\n"))
            assertTrue(rewritten.contains("QualityImprovements = Off\r\n"))
            assertTrue(rewritten.contains("RenderDistance = 5000\r\n"))
            assertTrue(rewritten.contains("Width = 1920\r\nHeight = 1080\r\n"))
            assertTrue(rewritten.contains("[Features]\r\nFov = 50\r\n"))
            assertFalse(rewritten.contains("\n\n\n"))

            val second = UltraCamConfiguration.apply(
                UltraCamConfiguration.BOTW_TITLE_ID,
                root,
                null
            )
            assertFalse(second?.changed == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun createsTotkRuntimeProfileWhenPackageOnlyContainsExefs() {
        val addonRoot = Files.createTempDirectory("ultracam-totk-addon").toFile()
        val sdmcRoot = Files.createTempDirectory("ultracam-totk-sdmc").toFile()
        try {
            val result = UltraCamConfiguration.apply(
                UltraCamConfiguration.TOTK_TITLE_ID,
                addonRoot,
                sdmcRoot
            )
            val file = sdmcRoot.resolve("UltraCam/TOTK/Config/maxlastbreath.ini")
            val created = file.readText()

            assertTrue(result?.created == true)
            assertTrue(created.contains("Width = 1920"))
            assertTrue(created.contains("Height = 1080"))
            assertTrue(created.contains("ShadowResolution = 512"))
            assertTrue(created.contains("RenderDistance = 5000"))
            assertTrue(created.contains("MaxFPS = 60.00"))
            assertTrue(created.contains("mMovieFPS = 30.00"))
            assertTrue(created.contains("OverrideHandheld_Resolution = True"))
        } finally {
            addonRoot.deleteRecursively()
            sdmcRoot.deleteRecursively()
        }
    }

    @Test
    fun doesNotConfigureOtherTitles() {
        val root = Files.createTempDirectory("ultracam-other").toFile()
        try {
            assertFalse(UltraCamConfiguration.supports("0100000000000000"))
            assertTrue(
                UltraCamConfiguration.apply("0100000000000000", root, null) == null
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
