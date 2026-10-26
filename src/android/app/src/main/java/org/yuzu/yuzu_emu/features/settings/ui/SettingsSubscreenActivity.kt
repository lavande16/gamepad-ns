// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navArgs
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.ActivitySettingsBinding
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.FullscreenHelper
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import org.yuzu.yuzu_emu.utils.GamePadNsHomeGeometry
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract
import org.yuzu.yuzu_emu.utils.InsetsHelper
import org.yuzu.yuzu_emu.utils.ThemeHelper
import org.yuzu.yuzu_emu.features.input.GameFoldUiNavigationBridge
import org.yuzu.yuzu_emu.features.input.GameFoldUiFocusHost
import org.yuzu.yuzu_emu.features.rearscreen.RearDisplayController
import kotlin.math.roundToInt

enum class SettingsSubscreen {
    PROFILE_MANAGER,
    DRIVER_MANAGER,
    DRIVER_FETCHER,
    FREEDRENO_SETTINGS,
    LOSSLESS_MANAGER,
    APPLET_LAUNCHER,
    INSTALLABLE,
    GAME_FOLDERS,
    ABOUT,
    LICENSES,
    GAME_INFO,
    ADDONS,
}

class SettingsSubscreenActivity : AppCompatActivity(), GameFoldUiFocusHost {
    private lateinit var binding: ActivitySettingsBinding
    private val gameFoldUiNavigation by lazy { GameFoldUiNavigationBridge(this) }

    override fun requestGameFoldUiFocus(view: View): Boolean =
        gameFoldUiNavigation.selectUiView(view)

    private val args by navArgs<SettingsSubscreenActivityArgs>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(YuzuApplication.applyLanguage(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)

        super.onCreate(savedInstanceState)
        GameFoldOrientation.apply(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setControllerOverlayVisible(false)
        binding.root.setControllerInputListener(gameFoldUiNavigation)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        if (savedInstanceState == null) {
            val navController = navHostFragment.navController
            val navGraph = navController.navInflater.inflate(
                R.navigation.settings_subscreen_navigation
            )
            navGraph.setStartDestination(resolveStartDestination())
            navController.setGraph(navGraph, createStartDestinationArgs())
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (InsetsHelper.getSystemGestureType(applicationContext) !=
            InsetsHelper.GESTURE_NAVIGATION
        ) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeHelper.getColorWithOpacity(
                    MaterialColors.getColor(
                        binding.navigationBarShade,
                        com.google.android.material.R.attr.colorSurface
                    ),
                    ThemeHelper.SYSTEM_BAR_ALPHA
                )
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            }
        )

        setInsets()
        applyFullscreenPreference()
    }

    override fun onStart() {
        super.onStart()
        if (!DirectoryInitialization.areDirectoriesReady) {
            DirectoryInitialization.start()
        }
    }

    override fun onResume() {
        super.onResume()
        applyFullscreenPreference()
        binding.root.post { gameFoldUiNavigation.requestDefaultFocus() }
    }

    override fun onPause() {
        binding.root.releaseControllerInputs()
        gameFoldUiNavigation.release()
        super.onPause()
    }

    override fun onDestroy() {
        binding.root.setControllerInputListener(null)
        gameFoldUiNavigation.dispose()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        RearDisplayController.onFrontWindowFocusChanged(this, hasFocus)
        if (hasFocus) {
            applyFullscreenPreference()
        }
    }

    override fun onUserLeaveHint() {
        RearDisplayController.onFrontUserLeave(this, "settings-subscreen")
        super.onUserLeaveHint()
    }

    fun navigateBack() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        if (!navHostFragment.navController.popBackStack()) {
            finish()
        }
    }

    private fun resolveStartDestination(): Int =
        when (args.destination) {
            SettingsSubscreen.PROFILE_MANAGER -> R.id.profileManagerFragment
            SettingsSubscreen.DRIVER_MANAGER -> R.id.driverManagerFragment
            SettingsSubscreen.DRIVER_FETCHER -> R.id.driverFetcherFragment
            SettingsSubscreen.FREEDRENO_SETTINGS -> R.id.freedrenoSettingsFragment
            SettingsSubscreen.LOSSLESS_MANAGER -> R.id.losslessManagerFragment
            SettingsSubscreen.APPLET_LAUNCHER -> R.id.appletLauncherFragment
            SettingsSubscreen.INSTALLABLE -> R.id.installableFragment
            SettingsSubscreen.GAME_FOLDERS -> R.id.gameFoldersFragment
            SettingsSubscreen.ABOUT -> R.id.aboutFragment
            SettingsSubscreen.LICENSES -> R.id.licensesFragment
            SettingsSubscreen.GAME_INFO -> R.id.gameInfoFragment
            SettingsSubscreen.ADDONS -> R.id.addonsFragment
        }

    private fun createStartDestinationArgs(): Bundle =
        when (args.destination) {
            SettingsSubscreen.DRIVER_MANAGER,
            SettingsSubscreen.FREEDRENO_SETTINGS -> bundleOf("game" to args.game)

            SettingsSubscreen.GAME_INFO,
            SettingsSubscreen.ADDONS -> bundleOf(
                "game" to requireNotNull(args.game) {
                    "Game is required for ${args.destination}"
                }
            )

            else -> Bundle()
        }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.navigationBarShade
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            val mlpNavShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
            mlpNavShade.height = 0
            binding.navigationBarShade.layoutParams = mlpNavShade

            windowInsets
        }
    }

    private fun applyFullscreenPreference() {
        FullscreenHelper.applyToActivity(this)
    }
}
