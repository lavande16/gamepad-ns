// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui.main

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.ActivityGamefoldAssemblyBinding
import org.yuzu.yuzu_emu.databinding.DialogGamefoldNoGamesBinding
import org.yuzu.yuzu_emu.features.rearscreen.RearDisplayController
import org.yuzu.yuzu_emu.utils.LosslessScalingHelper
import org.yuzu.yuzu_emu.utils.GameFoldAssembler
import org.yuzu.yuzu_emu.utils.GameFoldAssemblyResult
import org.yuzu.yuzu_emu.utils.GameFoldDialogSizing
import org.yuzu.yuzu_emu.utils.GameFoldPreflightResult
import org.yuzu.yuzu_emu.utils.GameFoldSocGate
import org.yuzu.yuzu_emu.utils.FullscreenHelper
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract
import org.yuzu.yuzu_emu.utils.GamePadNsHomeGeometry
import org.yuzu.yuzu_emu.utils.ThemeHelper
import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldControllerListener
import org.yuzu.yuzu_emu.views.GameFoldShellLayout
import org.yuzu.yuzu_emu.views.GameFoldStick
import kotlin.math.roundToInt

class GameFoldAssemblyActivity : AppCompatActivity(), ThemeProvider {
    private lateinit var binding: ActivityGamefoldAssemblyBinding
    private var assemblyStarted = false
    private var contentReady = false
    private var permissionRequestLaunched = false
    private var permissionExplanationShown = false
    private var splashRouting = true
    private var blockingUrl: String? = null
    private var permissionExplanationDialog: Dialog? = null
    private var noGamesDialog: Dialog? = null
    override var themeId: Int = 0

    private val controllerLinkListener = object : GameFoldControllerListener {
        override fun onButtonChanged(button: GameFoldButton, pressed: Boolean) {
            if (pressed) openBlockingLink()
        }

        override fun onStickChanged(stick: GameFoldStick, x: Float, y: Float) = Unit
    }

    private val permissionSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionRequestLaunched = false
        routeFromSplash()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(YuzuApplication.applyLanguage(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        GameFoldOrientation.apply(this)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { splashRouting }

        ThemeHelper.ThemeChangeListener(this)
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        FullscreenHelper.applyToActivity(this)

        routeFromSplash()
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyToActivity(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        routeFromSplash()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearDisplayController.onFrontWindowFocusChanged(this, hasFocus)
        }
        if (hasFocus) FullscreenHelper.applyToActivity(this)
    }

    override fun onUserLeaveHint() {
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearDisplayController.onFrontUserLeave(this, "assembly")
        }
        super.onUserLeaveHint()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (handleBlockingControllerKey(event)) true else super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (contentReady) binding.root.setControllerInputListener(null)
        permissionExplanationDialog?.dismiss()
        noGamesDialog?.dismiss()
        super.onDestroy()
    }

    private fun ensureAssemblyContent() {
        if (contentReady) return
        binding = ActivityGamefoldAssemblyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setControllerOverlayVisible(false)
        val insetPx = (
            GamePadNsHomeGeometry.headerHorizontalInsetDp(resources.configuration.screenWidthDp) *
                resources.displayMetrics.density
            ).roundToInt()
        binding.gamefoldDisplayStage.setPadding(insetPx, 20.dpPx(), insetPx, 16.dpPx())
        contentReady = true

        binding.buttonPermission.setOnClickListener { showPermissionExplanation(force = true) }
        binding.buttonRetry.setOnClickListener {
            noGamesDialog?.dismiss()
            assemblyStarted = false
            routeFromSplash()
        }
        binding.buttonPrimaryLink.setOnClickListener { openBlockingLink() }
        binding.textActionLink.setOnClickListener { openBlockingLink() }
        binding.textActionLink.paintFlags =
            binding.textActionLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.buttonDownloadFc.setOnClickListener { openBlockingLink() }

        binding.progressAssembly.progress = 0
        renderPermissionState()
    }

    private fun Int.dpPx(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    private fun routeFromSplash() {
        if (GameFoldSocGate.isUnsupportedMtkDevice()) {
            ensureAssemblyContent()
            splashRouting = false
            assemblyStarted = false
            renderMtkGate()
            return
        }

        if (GameFoldShellLayout.isUnsupportedUsage(this)) {
            ensureAssemblyContent()
            splashRouting = false
            assemblyStarted = false
            renderUnsupportedUsageGate()
            return
        }

        if (!Environment.isExternalStorageManager()) {
            ensureAssemblyContent()
            splashRouting = false
            assemblyStarted = false
            setBlockingUrl(null)
            renderPermissionState()
            showPermissionExplanation()
            return
        }

        if (assemblyStarted) return
        assemblyStarted = true
        permissionExplanationDialog?.dismiss()
        noGamesDialog?.dismiss()

        lifecycleScope.launch {
            val preflight = withContext(Dispatchers.IO) {
                runCatching { GameFoldAssembler.preflight() }
                    .getOrElse {
                        GameFoldPreflightResult.MissingStructure(
                            it.message ?: getString(R.string.fatal_error)
                        )
                    }
            }
            when (preflight) {
                is GameFoldPreflightResult.MissingStructure -> {
                    ensureAssemblyContent()
                    splashRouting = false
                    assemblyStarted = false
                    renderStructureError(preflight.summary)
                }

                GameFoldPreflightResult.NoGames -> {
                    ensureAssemblyContent()
                    splashRouting = false
                    assemblyStarted = false
                    renderNoGamesState()
                    showNoGamesDialog()
                }

                GameFoldPreflightResult.Ready -> continueAfterPreflight()
            }
        }
    }

    private suspend fun continueAfterPreflight() {
        if (contentReady) setBlockingUrl(null)
        val needsAssembly = withContext(Dispatchers.IO) {
            // LSFG is an independent optional material; failure never blocks the library.
            runCatching { LosslessScalingHelper.importAutomatic() }
            runCatching { GameFoldAssembler.needsAssembly(this@GameFoldAssemblyActivity) }
                .getOrDefault(true)
        }
        if (!needsAssembly) {
            splashRouting = false
            openGameLibrary()
            return
        }
        ensureAssemblyContent()
        splashRouting = false
        setBlockingUrl(null)
        resetActions()
        runAssembly()
    }

    private fun runAssembly() {
        lifecycleScope.launch {
            binding.textEyebrow.text = getString(R.string.gamefold_assembly_eyebrow_running)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GameFoldAssembler.assemble(this@GameFoldAssemblyActivity) { update ->
                        runOnUiThread {
                            binding.progressAssembly.setProgressCompat(update.progress, true)
                            binding.textProgressPercent.text = getString(
                                R.string.gamefold_assembly_percent,
                                update.progress
                            )
                            binding.textAssemblyTitle.text = update.title
                            binding.textAssemblyDetail.text = update.detail
                            binding.textStepCount.text = getString(
                                R.string.gamefold_assembly_step_count,
                                update.completedSteps,
                                update.totalSteps
                            )
                        }
                    }
                }.getOrElse { error ->
                    GameFoldAssemblyResult(false, error.message ?: getString(R.string.fatal_error))
                }
            }
            if (result.success) {
                binding.textEyebrow.text = getString(R.string.gamefold_assembly_eyebrow_complete)
                binding.textAssemblyDetail.text = buildString {
                    append(getString(R.string.gamefold_assembly_games_ready, result.games))
                    if (result.warnings.isNotEmpty()) {
                        append(" · ")
                        append(getString(R.string.gamefold_assembly_warnings, result.warnings.size))
                    }
                }
                binding.root.postDelayed({ openGameLibrary() }, 650L)
            } else {
                assemblyStarted = false
                binding.textEyebrow.text = getString(R.string.gamefold_assembly_eyebrow_attention)
                binding.textAssemblyTitle.text = getString(R.string.gamefold_assembly_failed)
                binding.textAssemblyDetail.text = result.summary
                binding.buttonRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun renderPermissionState() {
        resetActions()
        binding.textEyebrow.text = getString(R.string.gamefold_assembly_eyebrow_permission)
        binding.textAssemblyTitle.text = getString(R.string.gamefold_assembly_permission_title)
        binding.textAssemblyDetail.text = getString(R.string.gamefold_assembly_permission_detail)
        binding.textStepCount.text = getString(R.string.gamefold_assembly_source_path)
        binding.textProgressPercent.text = "0%"
        binding.progressAssembly.progress = 0
        binding.buttonPermission.visibility = View.VISIBLE
    }

    private fun renderMtkGate() {
        resetActions()
        setBlockingUrl(FC_WEBSITE_URL)
        binding.textEyebrow.text = getString(R.string.gamefold_mtk_eyebrow)
        binding.textAssemblyTitle.text = getString(R.string.gamefold_mtk_title)
        binding.textAssemblyDetail.text = getString(R.string.gamefold_mtk_detail)
        binding.textStepCount.text = "MTK / MediaTek"
        binding.textProgressPercent.text = "—"
        binding.progressAssembly.progress = 0
        binding.textActionLink.visibility = View.VISIBLE
        binding.buttonDownloadFc.visibility = View.VISIBLE
    }

    private fun renderUnsupportedUsageGate() {
        resetActions()
        setBlockingUrl(null)
        binding.textEyebrow.text = getString(R.string.gamefold_unsupported_mode)
        binding.textAssemblyTitle.text = getString(R.string.gamefold_unsupported_mode)
        binding.textAssemblyDetail.text = getString(R.string.gamefold_unsupported_mode)
        binding.textStepCount.text = "—"
        binding.textProgressPercent.text = "—"
        binding.progressAssembly.progress = 0
    }

    private fun renderStructureError(summary: String) {
        resetActions()
        setBlockingUrl(null)
        binding.textEyebrow.text = getString(R.string.gamefold_assembly_eyebrow_attention)
        binding.textAssemblyTitle.text = getString(R.string.gamefold_assembly_failed)
        binding.textAssemblyDetail.text = summary
        binding.textStepCount.text = getString(R.string.gamefold_assembly_source_path)
        binding.textProgressPercent.text = "0%"
        binding.progressAssembly.progress = 0
        binding.buttonRetry.text = getString(R.string.gamefold_recheck)
        binding.buttonRetry.visibility = View.VISIBLE
    }

    private fun renderNoGamesState() {
        resetActions()
        setBlockingUrl(DOWNLOAD_WEBSITE_URL)
        binding.textEyebrow.text = getString(R.string.gamefold_no_games_eyebrow)
        binding.textAssemblyTitle.text = getString(R.string.gamefold_no_games_title)
        binding.textAssemblyDetail.text = getString(R.string.gamefold_no_games_detail)
        binding.textStepCount.text = getString(R.string.gamefold_assembly_source_path)
        binding.textProgressPercent.text = "0%"
        binding.progressAssembly.progress = 0
        binding.buttonPrimaryLink.visibility = View.VISIBLE
        binding.buttonRetry.text = getString(R.string.gamefold_recheck)
        binding.buttonRetry.visibility = View.VISIBLE
    }

    private fun resetActions() {
        binding.buttonPermission.visibility = View.GONE
        binding.buttonRetry.visibility = View.GONE
        binding.buttonPrimaryLink.visibility = View.GONE
        binding.textActionLink.visibility = View.GONE
        binding.buttonDownloadFc.visibility = View.GONE
        binding.buttonRetry.text = getString(R.string.gamefold_assembly_retry)
    }

    private fun showPermissionExplanation(force: Boolean = false) {
        if (Environment.isExternalStorageManager()) {
            routeFromSplash()
            return
        }
        if (permissionExplanationDialog?.isShowing == true) return
        if (permissionExplanationShown && !force) return
        permissionExplanationShown = true

        binding.gamefoldDisplayStage.doOnLayout {
            if (isFinishing || isDestroyed || Environment.isExternalStorageManager()) return@doOnLayout
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gamefold_permission_dialog_title)
                .setMessage(R.string.gamefold_permission_dialog_detail)
                .setNegativeButton(R.string.gamefold_permission_later, null)
                .setPositiveButton(R.string.gamefold_permission_confirm) { _, _ ->
                    requestAllFilesPermission()
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).apply {
                    setTextColor(
                        ContextCompat.getColor(this@GameFoldAssemblyActivity, R.color.pixel_text_light)
                    )
                    backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@GameFoldAssemblyActivity, R.color.pixel_action)
                    )
                }
            }
            dialog.setOnDismissListener {
                if (permissionExplanationDialog === dialog) permissionExplanationDialog = null
            }
            permissionExplanationDialog = dialog
            GameFoldDialogSizing.showWithinStageOnFirstFrame(this, dialog)
        }
    }

    private fun showNoGamesDialog() {
        if (noGamesDialog?.isShowing == true) return
        binding.gamefoldDisplayStage.doOnLayout {
            if (isFinishing || isDestroyed) return@doOnLayout
            val dialogBinding = DialogGamefoldNoGamesBinding.inflate(layoutInflater)
            val dialog = Dialog(this).apply {
                setContentView(dialogBinding.root)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, _, event -> handleBlockingControllerKey(event) }
            }
            dialogBinding.buttonCloseNoGames.setOnClickListener { dialog.dismiss() }
            dialogBinding.buttonOpenWebsite.setOnClickListener { openBlockingLink() }
            dialog.setOnDismissListener {
                if (noGamesDialog === dialog) noGamesDialog = null
            }
            dialog.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            noGamesDialog = dialog
            GameFoldDialogSizing.showWithinStageOnFirstFrame(this, dialog)
        }
    }

    private fun requestAllFilesPermission() {
        if (Environment.isExternalStorageManager() || permissionRequestLaunched) return
        permissionRequestLaunched = true
        val packageUri = Uri.parse("package:$packageName")
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { permissionSettingsLauncher.launch(appIntent) }
            .onFailure { permissionSettingsLauncher.launch(fallbackIntent) }
    }

    private fun setBlockingUrl(url: String?) {
        blockingUrl = url
        binding.root.setControllerInputListener(if (url == null) null else controllerLinkListener)
    }

    private fun openBlockingLink() {
        val url = blockingUrl ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun handleBlockingControllerKey(event: KeyEvent): Boolean {
        if (blockingUrl == null || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return false
        }
        val controllerSource = event.source and InputDevice.SOURCE_GAMEPAD ==
            InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        val directionalKey = event.keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
        if (!KeyEvent.isGamepadButton(event.keyCode) && !(controllerSource && directionalKey)) {
            return false
        }
        openBlockingLink()
        return true
    }

    private fun openGameLibrary() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        })
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val FC_WEBSITE_URL = "https://game-fold.pages.dev"
        const val DOWNLOAD_WEBSITE_URL = "https://gamefold.pages.dev/#download"
    }
}
