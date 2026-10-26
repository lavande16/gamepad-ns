// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.input.InputManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.navigation.NavOptions
import org.yuzu.yuzu_emu.fragments.EmulationFragment
import org.yuzu.yuzu_emu.utils.CustomSettingsHandler
import android.util.Rational
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.ActivityEmulationBinding
import org.yuzu.yuzu_emu.dialogs.NetPlayDialog
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.GameFoldInputBridge
import org.yuzu.yuzu_emu.features.input.RearTouchInputRouter
import org.yuzu.yuzu_emu.features.rearscreen.RearDisplayController
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.model.EmulationViewModel
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.network.NetPlayManager
import org.yuzu.yuzu_emu.utils.InputHandler
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.MemoryUtil
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NfcReader
import org.yuzu.yuzu_emu.utils.ParamPackage
import org.yuzu.yuzu_emu.utils.ThemeHelper
import java.text.NumberFormat
import kotlin.math.roundToInt
import org.yuzu.yuzu_emu.utils.ForegroundService
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract
import androidx.core.os.BundleCompat
import java.lang.ref.WeakReference

class EmulationActivity : AppCompatActivity(), SensorEventListener, InputManager.InputDeviceListener {
    private lateinit var binding: ActivityEmulationBinding
    private val gameFoldInputBridge = GameFoldInputBridge(
        onHomePressed = ::returnToGameFoldHome,
        onConfirmWhilePaused = ::resumePausedGameFromController
    )

    var isActivityRecreated = false
    private lateinit var nfcReader: NfcReader
    private lateinit var inputManager: InputManager

    private var touchDownTime: Long = 0
    private val maxTapDuration = 500L

    private val gyro = FloatArray(3)
    private val accel = FloatArray(3)
    private var motionTimestamp: Long = 0

    private val actionPause = "ACTION_EMULATOR_PAUSE"
    private val actionPlay = "ACTION_EMULATOR_PLAY"
    private val actionMute = "ACTION_EMULATOR_MUTE"
    private val actionUnmute = "ACTION_EMULATOR_UNMUTE"

    private val emulationViewModel: EmulationViewModel by viewModels()

    private var foregroundService: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRomSwapIntent: Intent? = null
    private var isWaitingForRomSwapStop = false
    private var romSwapNativeStopped = false
    private var romSwapThreadStopped = false
    private var romSwapGeneration = 0
    private var hasEmulationSession = processHasEmulationSession
    private var returningToGameFoldHome = false
    private var physicalConfirmConsumedByPausedUi = false
    private var resumeSessionAfterGameFoldHome = false
    private val romSwapStopTimeoutRunnable = Runnable { onRomSwapStopTimeout() }
    private val pictureInPictureFailureActions: MutableSet<String> = mutableSetOf()

    private fun onRomSwapStopTimeout() {
        if (!isWaitingForRomSwapStop) {
            return
        }
        Log.warning("[EmulationActivity] ROM swap stop timed out; retrying native stop and continuing to wait")
        NativeLibrary.stopEmulation()
        scheduleRomSwapStopTimeout()
    }

    private fun scheduleRomSwapStopTimeout() {
        mainHandler.removeCallbacks(romSwapStopTimeoutRunnable)
        mainHandler.postDelayed(romSwapStopTimeoutRunnable, ROM_SWAP_STOP_TIMEOUT_MS)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(YuzuApplication.applyLanguage(base))
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.gameLaunched = true
        ThemeHelper.setTheme(this)

        super.onCreate(savedInstanceState)
        activeActivity = WeakReference(this)

        // GameFold owns the orientation choice; it is explicit and persisted, not sensor-driven.
        GameFoldOrientation.apply(this)

        InputHandler.updateControllerData()
        val players = NativeConfig.getInputSettings(true)
        var hasConfiguredControllers = false
        players.forEach {
            if (it.hasMapping()) {
                hasConfiguredControllers = true
            }
        }
        if (!hasConfiguredControllers && InputHandler.androidControllers.isNotEmpty()) {
            var params: ParamPackage? = null
            for (controller in InputHandler.registeredControllers) {
                if (controller.get("port", -1) == 0) {
                    params = controller
                    break
                }
            }

            if (params != null) {
                NativeInput.updateMappingsWithDefault(
                    0,
                    params,
                    params.get("display", getString(R.string.unknown))
                )
                NativeConfig.saveGlobalConfig()
            }
        }

        binding = ActivityEmulationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setControllerOverlayVisible(true)
        binding.root.setControllerAutoHideEnabled(true)
        binding.root.setControllerInputListener(gameFoldInputBridge)

        val launchIntent = Intent(intent)
        val shouldDeferLaunchForSwap = hasEmulationSession && isSwapIntent(launchIntent)
        if (shouldDeferLaunchForSwap) {
            Log.info("[EmulationActivity] onCreate detected existing session; deferring new game setup for swap")
            emulationViewModel.setIsEmulationStopping(true)
            emulationViewModel.setEmulationStopped(false)
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val initialArgs = if (shouldDeferLaunchForSwap) {
            Bundle(intent.extras ?: Bundle()).apply {
                processSessionGame?.let { putParcelable("game", it) }
            }
        } else {
            intent.extras
        }
        navHostFragment.navController.setGraph(R.navigation.emulation_navigation, initialArgs)
        if (shouldDeferLaunchForSwap) {
            mainHandler.post {
                handleSwapIntent(launchIntent)
            }
        }

        isActivityRecreated = savedInstanceState != null

        // Set these options now so that the SurfaceView the game renders into is the right size.
        enableFullscreenImmersive()

        window.decorView.setBackgroundColor(getColor(android.R.color.black))

        nfcReader = NfcReader(this)
        nfcReader.initialize()

        inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, null)

        foregroundService = Intent(this, ForegroundService::class.java)
        startForegroundService(foregroundService)

        val preferences = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
        if (!preferences.getBoolean(Settings.PREF_MEMORY_WARNING_SHOWN, false)) {
            if (MemoryUtil.isLessThan(MemoryUtil.REQUIRED_MEMORY, MemoryUtil.totalMemory)) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.device_memory_inadequate,
                        MemoryUtil.getDeviceRAM(),
                        getString(
                            R.string.memory_formatted,
                            NumberFormat.getInstance().format(MemoryUtil.REQUIRED_MEMORY),
                            getString(R.string.memory_gigabyte)
                        )
                    ),
                    Toast.LENGTH_LONG
                ).show()
                preferences.edit()
                    .putBoolean(Settings.PREF_MEMORY_WARNING_SHOWN, true)
                    .apply()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                // Special case, we do not support multiline input, dismiss the keyboard.
                val overlayView: View =
                    this.findViewById(R.id.surface_input_overlay)
                val im =
                    overlayView.context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                im.hideSoftInputFromWindow(overlayView.windowToken, 0)
            } else {
                val textChar = event.unicodeChar
                if (textChar == 0) {
                    // No text, button input.
                    NativeLibrary.submitInlineKeyboardInput(keyCode)
                } else {
                    // Text submitted.
                    NativeLibrary.submitInlineKeyboardText(textChar.toChar().toString())
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (resumeSessionAfterGameFoldHome) {
            mainHandler.post {
                if (resumeSessionAfterGameFoldHome) {
                    resumeSessionAfterGameFoldHome = !resumePausedGameFromController()
                }
            }
        }
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearTouchInputRouter.register(gameFoldInputBridge)
        }
        returningToGameFoldHome = false
        nfcReader.startScanning()
        startMotionSensorListener()
        InputHandler.updateControllerData()
        notifyPhysicalControllerState()

        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            buildPictureInPictureParams()
        }
    }

    override fun onPause() {
        physicalConfirmConsumedByPausedUi = false
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearTouchInputRouter.unregister(gameFoldInputBridge)
        }
        binding.root.releaseControllerInputs()
        nfcReader.stopScanning()
        stopMotionSensorListener()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearDisplayController.onFrontWindowFocusChanged(this, hasFocus)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(romSwapStopTimeoutRunnable)
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearTouchInputRouter.unregister(gameFoldInputBridge)
        }
        binding.root.setControllerInputListener(null)
        binding.root.setControllerOverlayVisible(false)
        if (activeActivity?.get() === this) {
            activeActivity = null
        }
        super.onDestroy()
        inputManager.unregisterInputDeviceListener(this)
        stopForegroundService(this)
        NativeLibrary.playTimeManagerStop()
    }

    override fun onUserLeaveHint() {
        if (GamePadNsProductContract.STRICT_GAMEPAD_SHELL) return
        if (returningToGameFoldHome) return
        RearDisplayController.onFrontUserLeave(this, "emulation")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            !isPictureInPictureSupported() ||
            !BooleanSetting.PICTURE_IN_PICTURE.getBoolean() ||
            isInPictureInPictureMode
        ) {
            return
        }

        val pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
            .getPictureInPictureActionsBuilder().getPictureInPictureAspectBuilder()
        runPictureInPictureAction("enter picture-in-picture mode") {
            enterPictureInPictureMode(pictureInPictureParamsBuilder.build())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESUME_EXISTING_SESSION, false)) {
            setIntent(Intent(intent))
            resumeSessionAfterGameFoldHome = consumeResumeAfterGameFoldHome()
            InputHandler.updateControllerData()
            return
        }
        resumeSessionAfterGameFoldHome = false
        handleSwapIntent(intent)
        nfcReader.onNewIntent(intent)
        InputHandler.updateControllerData()
    }

    private fun isSwapIntent(intent: Intent): Boolean {
        return when {
            intent.getBooleanExtra(EXTRA_OVERLAY_GAMELESS_EDIT_MODE, false) -> false
            intent.action == CustomSettingsHandler.CUSTOM_CONFIG_ACTION -> true
            intent.data != null -> true
            else -> {
                val extras = intent.extras
                extras != null &&
                    (
                        BundleCompat.getParcelable(extras, NAV_ARG_GAME, Game::class.java) != null ||
                            BundleCompat.getParcelable(
                                extras,
                                EXTRA_SELECTED_GAME,
                                Game::class.java
                            ) != null
                        )
            }
        }
    }

    private fun handleSwapIntent(intent: Intent) {
        if (!isSwapIntent(intent)) {
            return
        }

            pendingRomSwapIntent = Intent(intent)

            if (!isWaitingForRomSwapStop) {
                Log.info("[EmulationActivity] Begin ROM swap: data=${intent.data}")
                isWaitingForRomSwapStop = true
                romSwapNativeStopped = false
                romSwapThreadStopped = false
                romSwapGeneration += 1
                val thisSwapGeneration = romSwapGeneration
                emulationViewModel.setIsEmulationStopping(true)
                emulationViewModel.setEmulationStopped(false)
                val navHostFragment =
                    supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
                val childFragmentManager = navHostFragment?.childFragmentManager
                val stoppingFragmentForSwap =
                    (childFragmentManager?.primaryNavigationFragment as? EmulationFragment) ?:
                    childFragmentManager
                        ?.fragments
                        ?.asReversed()
                        ?.firstOrNull {
                            it is EmulationFragment &&
                                it.isAdded &&
                            it.view != null &&
                            !it.isRemoving
                        } as? EmulationFragment

                val hasSessionForSwap = hasEmulationSession || stoppingFragmentForSwap != null

                if (!hasSessionForSwap) {
                    romSwapNativeStopped = true
                    romSwapThreadStopped = true
                } else {
                    if (stoppingFragmentForSwap != null) {
                        stoppingFragmentForSwap.stopForRomSwap()
                        stoppingFragmentForSwap.notifyWhenEmulationThreadStops {
                            if (!isWaitingForRomSwapStop || romSwapGeneration != thisSwapGeneration) {
                                return@notifyWhenEmulationThreadStops
                            }
                            romSwapThreadStopped = true
                            Log.info("[EmulationActivity] ROM swap thread stop acknowledged")
                            launchPendingRomSwap(force = false)
                        }
                    } else {
                        Log.warning("[EmulationActivity] ROM swap stop target fragment not found; requesting native stop")
                        romSwapThreadStopped = true
                        NativeLibrary.stopEmulation()
                    }

                    scheduleRomSwapStopTimeout()
                }
            }

            launchPendingRomSwap(force = false)
    }

    private fun launchPendingRomSwap(force: Boolean) {
        if (!isWaitingForRomSwapStop) {
            return
        }
        if (!force && (!romSwapNativeStopped || !romSwapThreadStopped)) {
            return
        }
        val swapIntent = pendingRomSwapIntent ?: return
        Log.info("[EmulationActivity] Launching pending ROM swap: data=${swapIntent.data}")
        pendingRomSwapIntent = null
        isWaitingForRomSwapStop = false
        romSwapNativeStopped = false
        romSwapThreadStopped = false
        mainHandler.removeCallbacks(romSwapStopTimeoutRunnable)
        applyGameLaunchIntent(swapIntent)
    }

    private fun applyGameLaunchIntent(intent: Intent) {
        hasEmulationSession = true
        processHasEmulationSession = true
        emulationViewModel.setIsEmulationStopping(false)
        emulationViewModel.setEmulationStopped(false)
        setIntent(Intent(intent))
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        val startArgs = intent.extras?.let { Bundle(it) } ?: Bundle()
        if (!startArgs.containsKey(NAV_ARG_GAME)) {
            BundleCompat.getParcelable(
                startArgs,
                EXTRA_SELECTED_GAME,
                Game::class.java
            )?.let { startArgs.putParcelable(NAV_ARG_GAME, it) }
        }
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.emulationFragment, true)
            .build()

        runCatching {
            navController.navigate(R.id.emulationFragment, startArgs, navOptions)
        }.onFailure {
            Log.warning("[EmulationActivity] ROM swap navigate fallback to setGraph: ${it.message}")
            navController.setGraph(R.navigation.emulation_navigation, startArgs)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isControllerInput = InputHandler.isPhysicalGameController(event.device)

        // While a game is actively running, every physical controller key belongs to the
        // emulated console. Do not let the GamePad shell treat B/A or MODE as navigation.
        if (isControllerInput && shouldRoutePhysicalControllerToGame()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                notifyControllerInput()
            }
            return InputHandler.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE &&
            isControllerInput
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                returnToGameFoldHome()
            }
            return true
        }

        if ((event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) &&
            isControllerInput
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                resumePausedGameFromController()
            ) {
                physicalConfirmConsumedByPausedUi = true
                return true
            }
            if (physicalConfirmConsumedByPausedUi) {
                if (event.action == KeyEvent.ACTION_UP) {
                    physicalConfirmConsumedByPausedUi = false
                }
                return true
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        val isPhysicalKeyboard = event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
                                event.device?.isVirtual == false

        if (!isControllerInput &&
            event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE &&
            !isPhysicalKeyboard
        ) {
            return super.dispatchKeyEvent(event)
        }

        if (emulationViewModel.drawerOpen.value) {
            return super.dispatchKeyEvent(event)
        }

        if (isControllerInput && event.action == KeyEvent.ACTION_DOWN) {
            notifyControllerInput()
        }

        return InputHandler.dispatchKeyEvent(event)
    }

    private fun shouldRoutePhysicalControllerToGame(): Boolean {
        if (!NativeLibrary.isRunning() || NativeLibrary.isPaused()) return false
        return !emulationViewModel.drawerOpen.value
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isControllerInput = InputHandler.isPhysicalGameController(event.device)

        if (!isControllerInput &&
            event.source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD &&
            event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE
        ) {
            return super.dispatchGenericMotionEvent(event)
        }

        if (emulationViewModel.drawerOpen.value) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Don't attempt to do anything if we are disconnecting a device.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return true
        }

        if (isControllerInput) {
            notifyControllerInput()
        }

        return InputHandler.dispatchGenericMotionEvent(event)
    }

    private fun notifyControllerInput() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val emulationFragment =
            navHostFragment?.childFragmentManager?.fragments?.firstOrNull() as? org.yuzu.yuzu_emu.fragments.EmulationFragment
        emulationFragment?.onControllerInputDetected()
    }

    private fun isGameController(deviceId: Int): Boolean {
        return InputHandler.isPhysicalGameController(InputDevice.getDevice(deviceId))
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (isGameController(deviceId)) {
            InputHandler.updateControllerData()
            notifyPhysicalControllerState()
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        InputHandler.updateControllerData()
        notifyPhysicalControllerState()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        if (isGameController(deviceId)) {
            InputHandler.updateControllerData()
            notifyPhysicalControllerState()
        }
    }

    private fun notifyPhysicalControllerState() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val emulationFragment =
            navHostFragment?.childFragmentManager?.fragments?.firstOrNull() as? org.yuzu.yuzu_emu.fragments.EmulationFragment
        emulationFragment?.onPhysicalControllerStateChanged(InputHandler.androidControllers.isNotEmpty())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!NativeLibrary.isRunning() || NativeLibrary.isPaused()) {
            return
        }

        // Sensor values use the device's natural coordinate system. Convert them into the
        // current display coordinate system before forwarding them to the virtual Switch pad.
        // ROTATION_90/270 intentionally preserve the previously verified landscape mapping;
        // ROTATION_0/180 add the missing portrait mappings.
        val rotation = this.display?.rotation ?: Surface.ROTATION_270

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            when (rotation) {
                Surface.ROTATION_0 -> {
                    accel[0] = -event.values[0] / SensorManager.GRAVITY_EARTH
                    accel[1] = -event.values[1] / SensorManager.GRAVITY_EARTH
                }
                Surface.ROTATION_90 -> {
                    accel[0] = event.values[1] / SensorManager.GRAVITY_EARTH
                    accel[1] = -event.values[0] / SensorManager.GRAVITY_EARTH
                }
                Surface.ROTATION_180 -> {
                    accel[0] = event.values[0] / SensorManager.GRAVITY_EARTH
                    accel[1] = event.values[1] / SensorManager.GRAVITY_EARTH
                }
                else -> {
                    accel[0] = -event.values[1] / SensorManager.GRAVITY_EARTH
                    accel[1] = event.values[0] / SensorManager.GRAVITY_EARTH
                }
            }
            accel[2] = -event.values[2] / SensorManager.GRAVITY_EARTH
        }
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Investigate why sensor value is off by 6x
            when (rotation) {
                Surface.ROTATION_0 -> {
                    gyro[0] = event.values[0] / 6.0f
                    gyro[1] = event.values[1] / 6.0f
                }
                Surface.ROTATION_90 -> {
                    gyro[0] = -event.values[1] / 6.0f
                    gyro[1] = event.values[0] / 6.0f
                }
                Surface.ROTATION_180 -> {
                    gyro[0] = -event.values[0] / 6.0f
                    gyro[1] = -event.values[1] / 6.0f
                }
                else -> {
                    gyro[0] = event.values[1] / 6.0f
                    gyro[1] = -event.values[0] / 6.0f
                }
            }
            gyro[2] = event.values[2] / 6.0f
        }

        // Only update state on accelerometer data
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) {
            return
        }
        val deltaTimestamp = (event.timestamp - motionTimestamp) / 1000
        motionTimestamp = event.timestamp
        NativeInput.onDeviceMotionEvent(
            NativeInput.Player1Device,
            deltaTimestamp,
            gyro[0],
            gyro[1],
            gyro[2],
            accel[0],
            accel[1],
            accel[2]
        )
        NativeInput.onDeviceMotionEvent(
            NativeInput.ConsoleDevice,
            deltaTimestamp,
            gyro[0],
            gyro[1],
            gyro[2],
            accel[0],
            accel[1],
            accel[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, i: Int) {}

    private fun enableFullscreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun PictureInPictureParams.Builder.getPictureInPictureAspectBuilder():
        PictureInPictureParams.Builder {
        return this.apply { setAspectRatio(Rational(16, 9)) }
    }

    private fun PictureInPictureParams.Builder.getPictureInPictureActionsBuilder():
        PictureInPictureParams.Builder {
        val pictureInPictureActions: MutableList<RemoteAction> = mutableListOf()
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        if (NativeLibrary.isPaused()) {
            val playIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_play)
            val playPendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_play,
                Intent(actionPlay),
                pendingFlags
            )
            val playRemoteAction = RemoteAction(
                playIcon,
                getString(R.string.play),
                getString(R.string.play),
                playPendingIntent
            )
            pictureInPictureActions.add(playRemoteAction)
        } else {
            val pauseIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_pause)
            val pausePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_pause,
                Intent(actionPause),
                pendingFlags
            )
            val pauseRemoteAction = RemoteAction(
                pauseIcon,
                getString(R.string.pause),
                getString(R.string.pause),
                pausePendingIntent
            )
            pictureInPictureActions.add(pauseRemoteAction)
        }

        if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
            val unmuteIcon = Icon.createWithResource(
                this@EmulationActivity,
                R.drawable.ic_pip_unmute
            )
            val unmutePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_unmute,
                Intent(actionUnmute),
                pendingFlags
            )
            val unmuteRemoteAction = RemoteAction(
                unmuteIcon,
                getString(R.string.unmute),
                getString(R.string.unmute),
                unmutePendingIntent
            )
            pictureInPictureActions.add(unmuteRemoteAction)
        } else {
            val muteIcon = Icon.createWithResource(this@EmulationActivity, R.drawable.ic_pip_mute)
            val mutePendingIntent = PendingIntent.getBroadcast(
                this@EmulationActivity,
                R.drawable.ic_pip_mute,
                Intent(actionMute),
                pendingFlags
            )
            val muteRemoteAction = RemoteAction(
                muteIcon,
                getString(R.string.mute),
                getString(R.string.mute),
                mutePendingIntent
            )
            pictureInPictureActions.add(muteRemoteAction)
        }

        return this.apply { setActions(pictureInPictureActions) }
    }

    private fun isPictureInPictureSupported() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun runPictureInPictureAction(actionName: String, action: () -> Unit) {
        try {
            action()
        } catch (e: IllegalStateException) {
            if (pictureInPictureFailureActions.add(actionName)) {
                Log.warning("[PiP] Failed to $actionName: ${e.message}")
            }
        } catch (e: UnsupportedOperationException) {
            if (pictureInPictureFailureActions.add(actionName)) {
                Log.warning("[PiP] Failed to $actionName: ${e.message}")
            }
        }
    }

    fun buildPictureInPictureParams() {
        if (!isPictureInPictureSupported()) {
            return
        }

        val pictureInPictureParamsBuilder = PictureInPictureParams.Builder()
            .getPictureInPictureActionsBuilder().getPictureInPictureAspectBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isEmulationActive = emulationViewModel.emulationStarted.value &&
                !emulationViewModel.isEmulationStopping.value
            pictureInPictureParamsBuilder.setAutoEnterEnabled(
                BooleanSetting.PICTURE_IN_PICTURE.getBoolean() && isEmulationActive
            )
        }
        runPictureInPictureAction("set picture-in-picture params") {
            setPictureInPictureParams(pictureInPictureParamsBuilder.build())
        }
    }

    fun displayMultiplayerDialog() {
        val dialog = NetPlayDialog(this)
        dialog.show()
    }

    fun addNetPlayMessages(type: Int, msg: String) {
        NetPlayManager.addNetPlayMessage(type, msg)
    }

    private var pictureInPictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == actionPlay) {
                if (NativeLibrary.isPaused()) NativeLibrary.unpauseEmulation()
            } else if (intent.action == actionPause) {
                if (!NativeLibrary.isPaused()) NativeLibrary.pauseEmulation()
            }
            if (intent.action == actionUnmute) {
                if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
                    BooleanSetting.AUDIO_MUTED.setBoolean(false)
                }
            } else if (intent.action == actionMute) {
                if (!BooleanSetting.AUDIO_MUTED.getBoolean()) {
                    BooleanSetting.AUDIO_MUTED.setBoolean(true)
                }
            }
            buildPictureInPictureParams()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            IntentFilter().apply {
                addAction(actionPause)
                addAction(actionPlay)
                addAction(actionMute)
                addAction(actionUnmute)
            }.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(pictureInPictureReceiver, it, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(pictureInPictureReceiver, it)
                }
            }
        } else {
            try {
                unregisterReceiver(pictureInPictureReceiver)
            } catch (ignored: Exception) {
            }
            // Always resume audio, since there is no UI button
            if (BooleanSetting.AUDIO_MUTED.getBoolean()) {
                BooleanSetting.AUDIO_MUTED.setBoolean(false)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val isDisplayTouch = binding.root.isPointInDisplay(
            event.getX(actionIndex),
            event.getY(actionIndex)
        )
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val emulationFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull() as? org.yuzu.yuzu_emu.fragments.EmulationFragment

        if (isDisplayTouch) emulationFragment?.let { fragment ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    // show overlay immediately on touch and cancel timer when only auto-hide is enabled
                    if (!emulationViewModel.drawerOpen.value &&
                        BooleanSetting.ENABLE_INPUT_OVERLAY_AUTO_HIDE.getBoolean() &&
                        !BooleanSetting.HIDE_OVERLAY_ON_CONTROLLER_INPUT.getBoolean()) {
                        fragment.handler.removeCallbacksAndMessages(null)
                        fragment.toggleOverlay(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!emulationViewModel.drawerOpen.value) {
                        val touchDuration = System.currentTimeMillis() - touchDownTime

                        if (touchDuration <= maxTapDuration) {
                            fragment.handleScreenTap(false)
                        } else {
                            // just start the auto-hide timer without toggling visibility
                            fragment.handleScreenTap(true)
                        }
                    }
                }
            }
        }

        return super.dispatchTouchEvent(event)
    }

    fun onEmulationStarted() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onEmulationStarted() }
            return
        }
        hasEmulationSession = true
        processHasEmulationSession = true
        emulationViewModel.setEmulationStarted(true)
        emulationViewModel.setIsEmulationStopping(false)
        emulationViewModel.setEmulationStopped(false)
        NativeLibrary.playTimeManagerStart()

    }

    fun onEmulationStopped(status: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onEmulationStopped(status) }
            return
        }
        hasEmulationSession = false
        processHasEmulationSession = false
        if (isWaitingForRomSwapStop) {
            romSwapNativeStopped = true
            Log.info("[EmulationActivity] ROM swap native stop acknowledged")
            launchPendingRomSwap(force = false)
        } else if (status == 0 && emulationViewModel.programChanged.value == -1) {
            processSessionGame = null
            finish()
        } else if (!isWaitingForRomSwapStop) {
            processSessionGame = null
        }
        emulationViewModel.setEmulationStopped(true)
    }

    fun updateSessionGame(game: Game?) {
        processSessionGame = game
    }

    private fun returnToGameFoldHome() {
        if (returningToGameFoldHome || isFinishing || isDestroyed) return
        if (NativeLibrary.isRunning() && !NativeLibrary.isPaused()) {
            markResumeAfterGameFoldHome()
        }
        returningToGameFoldHome = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        )
        overridePendingTransition(0, 0)
    }

    private fun consumeResumeAfterGameFoldHome(): Boolean =
        consumeResumeAfterGameFoldHomeFlag()

    private fun resumePausedGameFromController(): Boolean {
        if (!NativeLibrary.isPaused()) return false
        val playButton = findViewById<View?>(R.id.paused_icon)
            ?.takeIf { it.isShown && it.isEnabled }
            ?: return false
        playButton.performClick()
        return true
    }

    fun onProgramChanged(programIndex: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onProgramChanged(programIndex) }
            return
        }
        emulationViewModel.setProgramChanged(programIndex)
    }

    private fun startMotionSensorListener() {
        val sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopMotionSensorListener() {
        val sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        sensorManager.unregisterListener(this, gyroSensor)
        sensorManager.unregisterListener(this, accelSensor)
    }

    companion object {
        const val EXTRA_SELECTED_GAME = "SelectedGame"
        const val EXTRA_OVERLAY_GAMELESS_EDIT_MODE = "overlayGamelessEditMode"
        private const val EXTRA_RESUME_EXISTING_SESSION = "GameFoldResumeExistingSession"
        private const val NAV_ARG_GAME = "game"
        private const val ROM_SWAP_STOP_TIMEOUT_MS = 5000L
        @Volatile
        private var processHasEmulationSession = false
        @Volatile
        private var processSessionGame: Game? = null
        @Volatile
        private var resumeAfterGameFoldHome = false
        @Volatile
        private var activeActivity: WeakReference<EmulationActivity>? = null

        fun stopForegroundService(activity: Activity) {
            val startIntent = Intent(activity, ForegroundService::class.java)
            startIntent.action = ForegroundService.ACTION_STOP
            activity.startForegroundService(startIntent)
        }

        fun launch(activity: AppCompatActivity, game: Game) {
            val launcher = Intent(activity, EmulationActivity::class.java)
            launcher.putExtra(EXTRA_SELECTED_GAME, game)
            activity.startActivity(launcher)
        }

        fun hasActiveSession(): Boolean = processHasEmulationSession

        private fun markResumeAfterGameFoldHome() {
            resumeAfterGameFoldHome = true
        }

        private fun consumeResumeAfterGameFoldHomeFlag(): Boolean {
            val shouldResume = resumeAfterGameFoldHome
            resumeAfterGameFoldHome = false
            return shouldResume
        }

        fun stopForOrientationChange(activity: Activity) {
            val emulationActivity = activeActivity?.get()
            val hasSession = processHasEmulationSession ||
                NativeLibrary.isRunning() ||
                NativeLibrary.isPaused()
            if (!hasSession) return

            NativeLibrary.stopEmulation()
            processHasEmulationSession = false
            processSessionGame = null
            stopForegroundService(activity)

            emulationActivity?.let {
                if (!it.isFinishing && !it.isDestroyed) {
                    it.finish()
                }
            }
        }

        fun returnToSessionOrSwap(activity: AppCompatActivity, game: Game) {
            val resumeExisting = processSessionGame?.path == game.path
            val launcher = Intent(activity, EmulationActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(NAV_ARG_GAME, game)
                putExtra(EXTRA_RESUME_EXISTING_SESSION, resumeExisting)
            }
            activity.startActivity(launcher)
            activity.overridePendingTransition(0, 0)
        }

        fun launchForOverlayEdit(context: Context): Intent {
            return Intent(context, EmulationActivity::class.java).apply {
                putExtra(EXTRA_OVERLAY_GAMELESS_EDIT_MODE, true)
            }
        }

        private fun areCoordinatesOutside(view: View?, x: Float, y: Float): Boolean {
            if (view == null) {
                return true
            }
            val viewBounds = Rect()
            view.getGlobalVisibleRect(viewBounds)
            return !viewBounds.contains(x.roundToInt(), y.roundToInt())
        }
    }
}
