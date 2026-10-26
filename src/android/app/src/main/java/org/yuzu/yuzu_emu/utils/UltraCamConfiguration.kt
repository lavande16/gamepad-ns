// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Applies the GamePad NS performance profile to the two Zelda UltraCam builds.
 *
 * The two mods do not share an INI schema or storage location:
 * BOTW reads its file from the add-on ROMFS, while TOTK reads it from the
 * emulator SDMC. Existing files are edited in place with their comments and
 * line endings preserved. Missing files receive only the fields known to be
 * supported by the corresponding build.
 */
object UltraCamConfiguration {
    const val BOTW_TITLE_ID = "01007EF00011E000"
    const val TOTK_TITLE_ID = "0100F2C0115B6000"

    data class ApplyResult(
        val file: File,
        val created: Boolean,
        val changed: Boolean,
        val missingKeys: List<String> = emptyList()
    )

    private data class IniValue(
        val section: String,
        val key: String,
        val value: String
    )

    private val botwValues = listOf(
        IniValue("Resolution", "MaxFramerate", "60"),
        IniValue("Resolution", "MenuFPS", "60"),
        IniValue("Resolution", "QualityImprovements", "Off"),
        IniValue("Resolution", "RenderDistance", "5000"),
        IniValue("Resolution", "Width", "1920"),
        IniValue("Resolution", "Height", "1080"),
        IniValue("Features", "Fov", "50")
    )

    private val totkValues = listOf(
        IniValue("Resolution", "Width", "1920"),
        IniValue("Resolution", "Height", "1080"),
        IniValue("Resolution", "ShadowResolution", "512"),
        IniValue("Resolution", "RenderDistance", "5000"),
        IniValue("Resolution", "QualityImprovements", "False"),
        IniValue("Resolution", "FSR", "True"),
        IniValue("Resolution", "FXAA", "True"),
        IniValue("UltraCamFPS", "MaxFPS", "60.00"),
        IniValue("UltraCamFPS", "DynamicFPS", "True"),
        IniValue("UltraCamFPS", "mMovieFPS", "30.00"),
        IniValue("UltraCamFPS", "Buffer", "8"),
        IniValue("Handheld", "OverrideHandheld_Resolution", "True"),
        IniValue("Handheld", "Width", "1280"),
        IniValue("Handheld", "Height", "720")
    )

    /** Returns whether this Title ID is one of the supported Zelda targets. */
    fun supports(titleId: String): Boolean = when {
        titleId.equals(BOTW_TITLE_ID, ignoreCase = true) -> true
        titleId.equals(TOTK_TITLE_ID, ignoreCase = true) -> true
        else -> false
    }

    /**
     * Configures a copied UltraCam add-on. For TOTK, [sdmcDirectory] is the
     * emulator SDMC root used by the runtime config; it may be null for a
     * caller that only wants to configure BOTW.
     */
    fun apply(
        titleId: String,
        addonDirectory: File,
        sdmcDirectory: File?
    ): ApplyResult? {
        return when {
            titleId.equals(BOTW_TITLE_ID, ignoreCase = true) -> {
                val file = resolveCaseInsensitive(addonDirectory, "romfs")
                    ?.let { resolveCaseInsensitive(it, "UltraCam", "maxlastbreath.ini") }
                    ?: File(addonDirectory, "romfs/UltraCam/maxlastbreath.ini")
                applyToFile(file, botwValues, botwTemplate)
            }

            titleId.equals(TOTK_TITLE_ID, ignoreCase = true) -> {
                val root = sdmcDirectory ?: throw IllegalStateException("SDMC 目录不可用")
                val file = File(root, "UltraCam/TOTK/Config/maxlastbreath.ini")
                applyToFile(file, totkValues, totkTemplate)
            }

            else -> null
        }
    }

    private fun applyToFile(
        file: File,
        values: List<IniValue>,
        template: String
    ): ApplyResult {
        file.parentFile?.mkdirs()
        if (!file.isFile) {
            file.writeText(template, StandardCharsets.UTF_8)
            return ApplyResult(file, created = true, changed = true)
        }

        val original = file.readText(StandardCharsets.UTF_8)
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n', limit = Int.MAX_VALUE)
        var section = ""
        val found = mutableSetOf<String>()
        var changed = false
        val rewritten = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length - 1).trim()
                return@map line
            }

            val match = iniEntry.matchEntire(line) ?: return@map line
            val key = match.groupValues[2].trim()
            val value = values.firstOrNull {
                it.section.equals(section, ignoreCase = true) &&
                    it.key.equals(key, ignoreCase = true)
            } ?: return@map line
            found += "${value.section}.${value.key}"
            if (match.groupValues[4].trim() == value.value) return@map line
            changed = true
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] +
                value.value + match.groupValues[5]
        }.joinToString(newline)

        if (changed) file.writeText(rewritten, StandardCharsets.UTF_8)
        val missing = values.map { "${it.section}.${it.key}" }
            .filterNot { it in found }
        return ApplyResult(file, created = false, changed = changed, missingKeys = missing)
    }

    private fun resolveCaseInsensitive(parent: File, vararg names: String): File? {
        var current = parent
        names.forEach { name ->
            current = current.listFiles()?.firstOrNull {
                it.name.equals(name, ignoreCase = true)
            } ?: return null
        }
        return current
    }

    private val iniEntry = Regex("^([\\t ]*)([^=;#]+?)(\\s*=\\s*)([^;#]*?)(\\s*[;#].*)?$")

    private val botwTemplate = """
        [Resolution]
        MaxFramerate = 60
        MenuFPS = 60
        QualityImprovements = Off
        RenderDistance = 5000
        Width = 1920
        Height = 1080

        [Features]
        Fov = 50
    """.trimIndent() + "\n"

    private val totkTemplate = """
        [Resolution]
        Width = 1920
        Height = 1080
        ShadowResolution = 512
        RenderDistance = 5000
        QualityImprovements = False
        FSR = True
        FXAA = True

        [UltraCamFPS]
        MaxFPS = 60.00
        DynamicFPS = True
        mMovieFPS = 30.00
        Buffer = 8

        [Handheld]
        OverrideHandheld_Resolution = True
        Width = 1280
        Height = 720
    """.trimIndent() + "\n"
}
