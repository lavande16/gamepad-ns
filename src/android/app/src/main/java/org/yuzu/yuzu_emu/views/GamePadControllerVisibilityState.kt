package org.yuzu.yuzu_emu.views

enum class GamePadWakeTouchDisposition { DISPATCH, WAKE_ONLY }

class GamePadControllerVisibilityState(
    private val inactivityTimeoutMs: Long = 10_000L,
    val transitionDurationMs: Long = 300L,
) {
    var controlsVisible: Boolean = true
        private set

    private var enabled = false
    private var lastTouchAtMs = 0L
    private var activeTouchCount = 0

    fun setEnabled(enabled: Boolean, nowMs: Long) {
        this.enabled = enabled
        lastTouchAtMs = nowMs
        activeTouchCount = 0
        controlsVisible = true
    }

    fun onTouchDown(nowMs: Long, hitsHome: Boolean): GamePadWakeTouchDisposition {
        val wasHidden = enabled && !controlsVisible
        activeTouchCount += 1
        lastTouchAtMs = nowMs
        controlsVisible = true
        return if (wasHidden && !hitsHome) {
            GamePadWakeTouchDisposition.WAKE_ONLY
        } else {
            GamePadWakeTouchDisposition.DISPATCH
        }
    }

    fun onTouchActivity(nowMs: Long) { lastTouchAtMs = nowMs }

    fun onTouchUp(nowMs: Long) {
        activeTouchCount = (activeTouchCount - 1).coerceAtLeast(0)
        lastTouchAtMs = nowMs
    }

    fun onTouchCancel(nowMs: Long) {
        activeTouchCount = 0
        lastTouchAtMs = nowMs
    }

    fun update(nowMs: Long): Boolean {
        if (!enabled || !controlsVisible || activeTouchCount > 0) return false
        if (nowMs - lastTouchAtMs < inactivityTimeoutMs) return false
        controlsVisible = false
        return true
    }

    fun delayUntilIdle(nowMs: Long): Long? {
        if (!enabled || !controlsVisible || activeTouchCount > 0) return null
        return (inactivityTimeoutMs - (nowMs - lastTouchAtMs)).coerceAtLeast(0L)
    }
}
