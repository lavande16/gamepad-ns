// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.rearscreen

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.yuzu.yuzu_emu.features.input.RearTouchInputRouter
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one foreground-only rear-touch session.
 *
 * The rear Activity is excluded from Recents and from front-app lifecycle accounting. Like vivo
 * Camera's dual-screen preview, the session is opt-in and is permanently disabled whenever the
 * user leaves the app.
 */
object RearDisplayController {
    private const val TAG = "GameFoldRear"
    private const val FRONT_DISPLAY_ID = 0
    private const val REAR_DISPLAY_ID_BEFORE_STATE = 2
    private const val REAR_DISPLAY_ID = 3
    private const val DUAL_STATE = 100
    private const val USER_LEAVE_DISABLE_DELAY_MS = 350L
    // vivo keeps GameFold's front task visible during the Recents animation. The front window
    // focus transition is therefore the earliest reliable signal that the user left the app.
    private const val FRONT_WINDOW_FOCUS_DISABLE_DELAY_MS = 450L
    private const val REAR_LAUNCH_GRACE_MS = 1200L
    private const val DISPLAY_APPEAR_TIMEOUT_MS = 1600L

    enum class SessionState {
        OFF,
        STARTING,
        ACTIVE,
        STOPPING
    }

    fun interface SessionStateListener {
        fun onSessionStateChanged(state: SessionState)
    }

    private lateinit var application: Application
    private lateinit var activityManager: ActivityManager
    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private val transitionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameFoldRearState").apply { isDaemon = true }
    }
    private val transitionGeneration = AtomicLong(0L)

    private var installed = false
    private var frontActivity: Activity? = null
    private var frontResumedActivity: Activity? = null
    private var focusedFrontActivity: Activity? = null
    private var rearActivity: RearTouchActivity? = null
    private val rearActivities =
        Collections.synchronizedSet(LinkedHashSet<RearTouchActivity>())
    private var rearStarting = false
    private var rearLaunchGraceUntil = 0L
    private var frontFocusConfirmedAfterRearReady = false
    private var rearSupported = false
    private var userLeaving = false

    @Volatile private var sessionEnabled = false
    @Volatile private var rearAllowed = false
    @Volatile private var desiredDual = false
    @Volatile private var systemDualEnabled = false
    @Volatile private var systemStateKnown = false
    @Volatile private var sessionState = SessionState.OFF
    private val sessionStateListeners = CopyOnWriteArraySet<SessionStateListener>()

    private val userLeaveRunnable = Runnable {
        if (userLeaving && sessionEnabled) {
            Log.i(TAG, "front user leave -> disable one-shot rear session")
            disableSession("front-user-leave")
        }
    }

    private val frontWindowFocusLossRunnable = object : Runnable {
        override fun run() {
            if (!sessionEnabled || rearStarting || focusedFrontActivity != null) return
            val remainingGrace = rearLaunchGraceUntil - System.currentTimeMillis()
            if (remainingGrace > 0L) {
                handler.postDelayed(this, remainingGrace)
                return
            }
            Log.i(TAG, "front window focus lost with no replacement -> disable rear session")
            disableSession("front-window-focus-lost")
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (!isFrontActivity(activity)) return
            frontActivity = activity
        }

        override fun onActivityStarted(activity: Activity) {
            if (!isFrontActivity(activity)) return
            cancelPendingForegroundLeave()
            frontActivity = activity
            if (sessionEnabled) updateForOrientation(activity, "front-started")
        }

        override fun onActivityResumed(activity: Activity) {
            if (!isFrontActivity(activity)) return
            cancelPendingForegroundLeave()
            frontActivity = activity
            frontResumedActivity = activity
            if (sessionEnabled) updateForOrientation(activity, "front-resumed")
        }

        override fun onActivityPaused(activity: Activity) {
            if (!isFrontActivity(activity)) return
            if (frontResumedActivity === activity) frontResumedActivity = null
            // Launching a second Activity on vivo's rear display can pause the front Activity
            // even while its window remains fully visible. Background exit is therefore owned
            // by the front-window focus/user-leave path, not by multi-display lifecycle state.
            releaseFeedbackAndInput()
        }

        override fun onActivityStopped(activity: Activity) {
            if (!isFrontActivity(activity)) return
            if (frontResumedActivity === activity) frontResumedActivity = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (!isFrontActivity(activity)) return
            if (frontActivity === activity) frontActivity = null
            if (frontResumedActivity === activity) frontResumedActivity = null
            if (focusedFrontActivity === activity) focusedFrontActivity = null
        }

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handler.post {
                if (sessionEnabled) {
                    frontActivity?.let { updateForOrientation(it, "configuration-changed") }
                }
            }
        }

        override fun onLowMemory() = Unit
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        application = app
        displayManager = app.getSystemService(DisplayManager::class.java)
        activityManager = app.getSystemService(ActivityManager::class.java)
        rearSupported = isVivoDevice()
        Log.i(
            TAG,
            "rear display support=$rearSupported manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND}"
        )
        app.registerActivityLifecycleCallbacks(callbacks)
        app.registerComponentCallbacks(componentCallbacks)

        // A fresh process always starts with the one-shot session disabled. Also clean up a
        // vendor dual-display state that may have survived a previous process crash.
        if (rearSupported) requestSingleScreen("process-start")
    }

    fun isSupportedDevice(): Boolean = installed && rearSupported

    fun isSessionEnabled(): Boolean =
        sessionState == SessionState.STARTING || sessionState == SessionState.ACTIVE

    fun getSessionState(): SessionState = sessionState

    fun addSessionStateListener(listener: SessionStateListener) {
        sessionStateListeners.add(listener)
        handler.post { listener.onSessionStateChanged(sessionState) }
    }

    fun removeSessionStateListener(listener: SessionStateListener) {
        sessionStateListeners.remove(listener)
    }

    /** Returns the resulting enabled state. Must be called from the main thread. */
    fun setSessionEnabled(activity: Activity, enabled: Boolean): Boolean {
        if (!enabled) {
            disableSession("manual-off")
            return false
        }
        if (!rearSupported || !isLandscapeFrontActivity(activity)) {
            return false
        }

        cancelPendingForegroundLeave()
        frontActivity = activity
        frontResumedActivity = activity
        focusedFrontActivity = activity
        rearLaunchGraceUntil = System.currentTimeMillis() + REAR_LAUNCH_GRACE_MS
        frontFocusConfirmedAfterRearReady = false
        sessionEnabled = true
        rearAllowed = true
        setSessionState(SessionState.STARTING)
        requestDualScreen("manual-on")
        return true
    }

    /** Called by front activities because vivo's Recents UI may not pause or stop them. */
    fun onFrontUserLeave(activity: Activity, reason: String) {
        if (!sessionEnabled || !isFrontActivity(activity)) return
        if (rearStarting) {
            Log.i(TAG, "ignore user-leave hint while rear Activity is starting")
            return
        }
        userLeaving = true
        releaseFeedbackAndInput()
        handler.removeCallbacks(userLeaveRunnable)
        handler.postDelayed(userLeaveRunnable, USER_LEAVE_DISABLE_DELAY_MS)
        Log.i(TAG, "front user leave scheduled reason=$reason")
    }

    /** Never auto-enables on return; it only keeps an already active foreground session healthy. */
    fun onFrontWindowFocusChanged(activity: Activity, hasFocus: Boolean) {
        if (!rearSupported || !isFrontActivity(activity)) return
        if (!hasFocus) {
            if (focusedFrontActivity === activity) focusedFrontActivity = null
            releaseFeedbackAndInput()
            if (sessionEnabled && !rearStarting) {
                if (!frontFocusConfirmedAfterRearReady) {
                    Log.i(TAG, "ignore rear-launch focus transition until front focus is confirmed")
                    return
                }
                handler.removeCallbacks(frontWindowFocusLossRunnable)
                handler.postDelayed(
                    frontWindowFocusLossRunnable,
                    FRONT_WINDOW_FOCUS_DISABLE_DELAY_MS
                )
                Log.i(TAG, "front window focus lost; background check scheduled")
            }
            return
        }

        cancelPendingForegroundLeave()
        frontActivity = activity
        frontResumedActivity = activity
        focusedFrontActivity = activity
        if (sessionState == SessionState.ACTIVE) {
            frontFocusConfirmedAfterRearReady = true
            Log.i(TAG, "front focus confirmed after rear Activity settled")
        }
        if (sessionEnabled) updateForOrientation(activity, "front-window-focus")
    }

    /** Reasserts rear-display power without racing the enable/disable state machine. */
    fun ensureRearDisplayPower(reason: String) {
        if (!rearSupported || !sessionEnabled || !desiredDual) return
        val generation = transitionGeneration.get()
        transitionExecutor.execute {
            if (!isCurrent(generation, wantDual = true)) return@execute
            val enabled = runShell("cmd display enable-display $REAR_DISPLAY_ID")
            val reset = runShell("cmd display power-reset $REAR_DISPLAY_ID")
            Log.i(TAG, "outer power after $reason: enable=$enabled reset=$reset")
        }
    }

    private fun cancelPendingForegroundLeave() {
        handler.removeCallbacks(userLeaveRunnable)
        handler.removeCallbacks(frontWindowFocusLossRunnable)
        userLeaving = false
    }

    private fun disableSession(reason: String) {
        cancelPendingForegroundLeave()
        val needsReset =
            sessionEnabled || desiredDual || systemDualEnabled || rearActivity != null || rearStarting
        sessionEnabled = false
        rearAllowed = false
        frontFocusConfirmedAfterRearReady = false
        if (needsReset) {
            setSessionState(SessionState.STOPPING)
            requestSingleScreen(reason, waitForRearBlank = true)
        } else {
            prepareRearForShutdown()
            setSessionState(SessionState.OFF)
        }
    }

    private fun updateForOrientation(activity: Activity, reason: String) {
        if (!sessionEnabled) return
        if (!isLandscapeFrontActivity(activity)) {
            disableSession("$reason-not-landscape")
            return
        }
        rearAllowed = true
        requestDualScreen(reason)
    }

    private fun requestDualScreen(reason: String) {
        val owner = frontResumedActivity ?: frontActivity
        if (!rearSupported || !sessionEnabled || !rearAllowed || owner == null ||
            owner.isFinishing || owner.isDestroyed
        ) return
        desiredDual = true
        val generation = transitionGeneration.incrementAndGet()
        Log.i(
            TAG,
            "reconcile#$generation desired=dual reason=$reason systemDual=$systemDualEnabled"
        )
        transitionExecutor.execute { reconcileDual(generation, reason) }
    }

    private fun requestSingleScreen(reason: String, waitForRearBlank: Boolean = false) {
        if (!rearSupported) return
        desiredDual = false
        releaseFeedbackAndInput()
        val generation = transitionGeneration.incrementAndGet()
        Log.i(
            TAG,
            "reconcile#$generation desired=single reason=$reason systemDual=$systemDualEnabled"
        )
        val reconcile = {
            if (isCurrent(generation, wantDual = false)) {
                transitionExecutor.execute { reconcileSingle(generation, reason) }
            }
        }
        if (waitForRearBlank) {
            prepareRearForShutdown(reconcile)
        } else {
            prepareRearForShutdown()
            reconcile()
        }
    }

    private fun reconcileDual(generation: Long, reason: String) {
        if (!isCurrent(generation, wantDual = true)) return

        if (!systemDualEnabled || displayManager.getDisplay(REAR_DISPLAY_ID) == null) {
            val displayEnabled = runShell("cmd display enable-display $REAR_DISPLAY_ID_BEFORE_STATE")
            if (!isCurrent(generation, wantDual = true)) return
            val stateChanged = runShell("cmd device_state state $DUAL_STATE")
            Log.i(
                TAG,
                "reconcile#$generation enable display $REAR_DISPLAY_ID_BEFORE_STATE=$displayEnabled " +
                    "state $DUAL_STATE=$stateChanged"
            )
            if (!stateChanged) {
                systemDualEnabled = false
                systemStateKnown = true
                handler.post { disableSession("device-state-failed") }
                return
            }
            systemDualEnabled = true
            systemStateKnown = true
        }

        val deadline = System.currentTimeMillis() + DISPLAY_APPEAR_TIMEOUT_MS
        while (
            isCurrent(generation, wantDual = true) &&
            displayManager.getDisplay(REAR_DISPLAY_ID) == null &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(80L)
        }
        if (!isCurrent(generation, wantDual = true)) return

        val shown = CountDownLatch(1)
        var rearStarted = false
        handler.post {
            if (isCurrent(generation, wantDual = true)) {
                rearStarted = ensureRearActivity(generation, reason)
            }
            shown.countDown()
        }
        shown.await(1200L, TimeUnit.MILLISECONDS)
        if (!isCurrent(generation, wantDual = true)) return
        if (!rearStarted) {
            Log.e(TAG, "reconcile#$generation rear Activity unavailable")
            handler.post { disableSession("rear-activity-failed") }
            return
        }

        val rearEnabled = runShell("cmd display enable-display $REAR_DISPLAY_ID")
        val rearReset = runShell("cmd display power-reset $REAR_DISPLAY_ID")
        Log.i(
            TAG,
                "reconcile#$generation dual ready reason=$reason " +
                "display=$rearEnabled powerReset=$rearReset rearActivity=true"
        )
    }

    private fun reconcileSingle(generation: Long, reason: String) {
        if (!isCurrent(generation, wantDual = false)) return
        if (!systemStateKnown || systemDualEnabled || displayManager.getDisplay(REAR_DISPLAY_ID) != null) {
            // Keep the dark rear window attached until the physical display leaves the layout,
            // matching vivo Camera and avoiding a visible empty-display frame.
            val restored = runShell("cmd device_state state reset")
            val rearDisabled = runShell("cmd display disable-display $REAR_DISPLAY_ID")
            Log.i(
                TAG,
                "reconcile#$generation single ready reason=$reason " +
                    "reset=$restored displayDisabled=$rearDisabled"
            )
        }
        systemDualEnabled = false
        systemStateKnown = true
        if (!isCurrent(generation, wantDual = false)) return
        handler.post {
            if (!isCurrent(generation, wantDual = false)) return@post
            finishRearActivity()
            finishAllRearTasks()
            setSessionState(SessionState.OFF)
        }
    }

    private fun ensureRearActivity(generation: Long, reason: String): Boolean {
        if (!isCurrent(generation, wantDual = true)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (displayManager.getDisplay(REAR_DISPLAY_ID) == null) return false
        rearActivity?.let { existing ->
            if (!existing.isFinishing && !existing.isDestroyed &&
                activityDisplayId(existing) == REAR_DISPLAY_ID
            ) {
                Log.i(TAG, "reconcile#$generation rear Activity already healthy")
                return true
            }
            runCatching { existing.finishAndRemoveTask() }
            rearActivity = null
        }
        if (rearStarting) return true

        val owner = frontResumedActivity ?: frontActivity ?: return false
        if (!isFrontActivity(owner) || owner.isFinishing || owner.isDestroyed) return false

        return try {
            val intent = Intent(owner, RearTouchActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = REAR_DISPLAY_ID
            }
            rearStarting = true
            frontFocusConfirmedAfterRearReady = false
            rearLaunchGraceUntil = maxOf(
                rearLaunchGraceUntil,
                System.currentTimeMillis() + REAR_LAUNCH_GRACE_MS
            )
            owner.startActivity(intent, options.toBundle())
            Log.i(TAG, "reconcile#$generation start rear Activity reason=$reason display=3")
            true
        } catch (error: Exception) {
            rearStarting = false
            Log.e(TAG, "start rear Activity failed", error)
            false
        }
    }

    internal fun onRearActivityReady(activity: RearTouchActivity) {
        if (activityDisplayId(activity) != REAR_DISPLAY_ID ||
            !sessionEnabled || !desiredDual || !rearAllowed
        ) {
            rearStarting = false
            runCatching { activity.finishAndRemoveTask() }
            if (sessionEnabled || desiredDual) disableSession("rear-activity-invalid")
            return
        }
        // A fold/configuration transition can create the replacement before the old rear
        // instance delivers onDestroy. Finish every older instance now so it cannot leave a
        // second task/window behind in Recents.
        rearActivities.toList().filter { it !== activity }.forEach { stale ->
            runCatching { stale.finishAndRemoveTask() }
                .onFailure { Log.w(TAG, "finish replaced rear Activity failed", it) }
        }
        rearActivity = activity
        rearStarting = false
        // vivo can report the old front window as focused while it is replacing the first rear
        // Activity for the display-state configuration change. That snapshot is stale. Only a
        // real onWindowFocusChanged(true) delivered after this final rear-ready callback may arm
        // focus-loss background detection.
        frontFocusConfirmedAfterRearReady = false
        setSessionState(SessionState.ACTIVE)
        Log.i(TAG, "rear Activity ready display=$REAR_DISPLAY_ID")
        ensureRearDisplayPower("rear-activity-ready")
    }

    internal fun onRearActivityDestroyed(activity: RearTouchActivity) {
        rearActivities.remove(activity)
        // vivo may destroy the old rear instance after delivering the new instance's onCreate
        // during the fold-state configuration change. Never let that stale callback tear down
        // the replacement window that is already rendering the controls.
        val wasCurrent = rearActivity === activity
        if (!wasCurrent) return
        rearActivity = null
        rearStarting = false
        handler.postDelayed({
            if (sessionEnabled && desiredDual && rearActivity == null && !rearStarting) {
                disableSession("rear-activity-destroyed")
            }
        }, 500L)
    }

    private fun finishRearActivity() {
        rearStarting = false
        val activity = rearActivity ?: return
        rearActivity = null
        activity.prepareForShutdown()
        runCatching { activity.finishAndRemoveTask() }
            .onFailure { Log.w(TAG, "finish rear Activity failed", it) }
    }

    /**
     * vivo can create a replacement rear task during fold/configuration changes before the old
     * task's destroy callback arrives. The controller only keeps the newest Activity reference,
     * so a single finish() leaves stopped rear tasks behind. Remove every rear task owned by this
     * package when the one-shot session is closed; otherwise those stale windows can keep the
     * outer panel awake and compete with the front task when Recents restores GameFold.
     */
    private fun finishAllRearTasks() {
        rearActivities.toList().forEach { stale ->
            stale.prepareForShutdown()
            runCatching { stale.finishAndRemoveTask() }
                .onFailure { error -> Log.w(TAG, "finish registered rear task failed", error) }
        }
        rearActivities.clear()
        val rearClassName = RearTouchActivity::class.java.name
        runCatching {
            activityManager.appTasks.forEach { appTask ->
                val taskInfo = appTask.taskInfo
                val isRearTask =
                    taskInfo.baseActivity?.className == rearClassName ||
                        taskInfo.topActivity?.className == rearClassName
                if (isRearTask) {
                    runCatching { appTask.finishAndRemoveTask() }
                        .onFailure { error ->
                            Log.w(TAG, "finish stale rear task failed", error)
                        }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "enumerate rear tasks failed", error)
        }
    }

    internal fun onRearActivityCreated(activity: RearTouchActivity) {
        rearActivities.add(activity)
    }

    private fun releaseFeedbackAndInput() {
        FrontEdgeLightController.clear()
        RearTouchInputRouter.releaseAll()
    }

    private fun prepareRearForShutdown(onBlankFramesCommitted: (() -> Unit)? = null) {
        releaseFeedbackAndInput()
        val activities = rearActivities.toList()
        if (onBlankFramesCommitted == null) {
            activities.forEach { it.prepareForShutdown() }
            return
        }
        if (activities.isEmpty()) {
            handler.post { onBlankFramesCommitted() }
            return
        }

        val remaining = AtomicInteger(activities.size)
        activities.forEach { activity ->
            activity.prepareForShutdown {
                if (remaining.decrementAndGet() == 0) {
                    onBlankFramesCommitted()
                }
            }
        }
    }

    private fun setSessionState(newState: SessionState) {
        if (sessionState == newState) return
        sessionState = newState
        sessionStateListeners.forEach { listener ->
            handler.post { listener.onSessionStateChanged(newState) }
        }
    }

    private fun isCurrent(generation: Long, wantDual: Boolean): Boolean =
        transitionGeneration.get() == generation && desiredDual == wantDual &&
            (!wantDual || (sessionEnabled && rearAllowed))

    private fun isLandscapeFrontActivity(activity: Activity): Boolean =
        isFrontActivity(activity) &&
            !GameFoldOrientation.isPortrait(activity)

    private fun isFrontActivity(activity: Activity): Boolean =
        activity !is RearTouchActivity &&
            activityDisplayId(activity).let { it == FRONT_DISPLAY_ID || it == null }

    private fun activityDisplayId(activity: Activity): Int? =
        activity.display?.displayId ?: activity.window.decorView.display?.displayId

    private fun runShell(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            if (output.isNotBlank()) Log.d(TAG, "shell out: $output")
            if (error.isNotBlank()) Log.w(TAG, "shell err: $error")
            process.exitValue() == 0
        } catch (error: Exception) {
            Log.e(TAG, "shell exception for $command", error)
            false
        }
    }

    private fun isVivoDevice(): Boolean =
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true)
}
