// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import java.io.File
import java.io.FileNotFoundException

object LosslessScalingHelper {
    const val RESULT_OK = 0
    const val RESULT_NOT_INSTALLED = 1

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    @Volatile private var installed: Boolean? = null
    @Volatile private var importFailure = ""

    fun isInstalled(): Boolean = installed ?: refreshStatus()

    /** Uses the active renderer's selected Device; no cached system-libvulkan verdict. */
    fun runtimeStatusText(): String {
        val context = YuzuApplication.appContext
        if (importFailure.isNotEmpty()) return importFailure
        if (!isInstalled()) return context.getString(R.string.lossless_scaling_missing_description)
        return context.getString(when (NativeLibrary.getFrameGenerationStatus()) {
            1 -> R.string.gamepad_lsfg_shaders_ready
            2 -> R.string.gamepad_lsfg_memory_model_missing
            3 -> R.string.gamepad_lsfg_null_descriptor_missing
            4 -> R.string.gamepad_lsfg_shader_failed
            5 -> R.string.gamepad_lsfg_storage_missing
            else -> R.string.gamepad_lsfg_driver_pending
        })
    }

    @Synchronized
    fun refreshStatus(): Boolean {
        val result = NativeLibrary.validatePreparedLosslessDll(false)
        installed = result
        val context = YuzuApplication.appContext
        _statusText.value = if (importFailure.isNotEmpty()) importFailure else context.getString(
            if (result) R.string.lossless_scaling_installed else R.string.lossless_scaling_not_installed
        )
        return result
    }

    @Synchronized
    fun importAutomatic(): Int {
        if (NativeLibrary.isRunning()) return RESULT_OK
        val source = GameFoldPackage.systemDirectory.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("Lossless.dll", true)
        }
        return installSource {
            source?.inputStream() ?: throw FileNotFoundException("system/Lossless.dll")
        }
    }

    @Synchronized
    fun install(source: Uri): Int = installSource {
        YuzuApplication.appContext.contentResolver.openInputStream(source)
            ?: throw FileNotFoundException(source.toString())
    }

    private fun installSource(source: LosslessMaterialStore.Source): Int {
        if (NativeLibrary.isRunning()) return 6
        val destination = File(NativeLibrary.getLosslessDllPath()).parentFile
            ?: return RESULT_NOT_INSTALLED
        val store = LosslessMaterialStore(destination,
            { NativeLibrary.prepareStagedLosslessDll() },
            { directory -> NativeLibrary.validatePreparedLosslessDll(directory != destination) }
        )
        val result = store.install(source)
        val context = YuzuApplication.appContext
        importFailure = if (result == RESULT_OK) "" else context.getString(
            R.string.gamepad_lsfg_import_failed,
            context.resources.getStringArray(R.array.losslessDllResults).getOrElse(result) {
                context.getString(R.string.error_lossless_unreadable)
            }
        )
        refreshStatus()
        if (result != RESULT_OK) Log.warning("[LSFG] $importFailure; previous prepared files retained")
        return result
    }

    @Synchronized
    fun remove(): Boolean {
        if (NativeLibrary.isRunning()) return false
        val removed = NativeLibrary.removeLosslessDll()
        importFailure = ""
        refreshStatus()
        return removed
    }
}
