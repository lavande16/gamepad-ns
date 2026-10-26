// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.input

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.ui.SettingsSubscreenActivity
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.ui.GamesFragment
import org.yuzu.yuzu_emu.views.GameFoldButton
import org.yuzu.yuzu_emu.views.GameFoldControllerListener
import org.yuzu.yuzu_emu.views.GameFoldStick
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

interface GameFoldUiFocusHost {
    fun requestGameFoldUiFocus(view: View): Boolean
}

interface GameFoldHomeHost {
    fun showGameFoldHome()
}

/**
 * Low-overhead navigation adapter for app-owned UI. It emits ordinary DPAD key events into the
 * currently active Activity/Dialog focus tree and never polls or invalidates on a frame loop.
 */
class GameFoldUiNavigationBridge(
    private val activity: AppCompatActivity
) : GameFoldControllerListener {
    private val handler = Handler(Looper.getMainLooper())
    private var repeatedDirection: Int? = null
    private var leftStickDirection: Int? = null
    private var lastGameFocus = WeakReference<View?>(null)
    private var lastUiFocus = WeakReference<View?>(null)
    private var selectedUiView = WeakReference<View?>(null)
    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatedDirection?.let(::moveFocus)
            if (repeatedDirection != null) handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    override fun onButtonChanged(button: GameFoldButton, pressed: Boolean) {
        if (pressed && button == GameFoldButton.HOME) {
            navigateToHome()
            return
        }
        val direction = button.toFocusDirection()
        if (direction != null) {
            if (pressed) startRepeating(direction) else stopRepeating(direction)
            return
        }
        if (!pressed) return

        when (button) {
            GameFoldButton.B,
            GameFoldButton.X -> confirmFocusedView()

            GameFoldButton.A,
            GameFoldButton.Y -> cancelOrNavigateBack()

            else -> Unit
        }
    }

    override fun onStickChanged(stick: GameFoldStick, x: Float, y: Float) {
        if (stick != GameFoldStick.LEFT) return
        val nextDirection = stickDirection(x, y)
        if (nextDirection == leftStickDirection) return

        leftStickDirection?.let(::stopRepeating)
        leftStickDirection = nextDirection
        nextDirection?.let(::startRepeating)
    }

    fun release() {
        repeatedDirection = null
        leftStickDirection = null
        handler.removeCallbacks(repeatRunnable)
    }

    fun dispose() {
        release()
        restoreOriginalBackground(selectedUiView.get())
        selectedUiView.clear()
    }

    fun requestDefaultFocus() {
        val dialog = GameFoldDialogRegistry.activeDialog(activity)
        if (dialog != null) {
            requestFirstFocusable(dialog.window?.decorView)
            return
        }

        findFirstGameCard()?.let {
            if (focusView(it)) lastGameFocus = WeakReference(it)
            return
        }
        if (isHomeRoot()) return
        val stage = activity.findViewById<ViewGroup?>(R.id.gamefold_display_stage)
        val root = stage ?: activity.window.decorView
        findFirstRecyclerItem(root)?.let {
            if (focusView(it)) {
                scrollFocusedViewIntoView(it)
                return
            }
        }
        requestFirstFocusable(root)
    }

    fun selectUiView(view: View): Boolean {
        selectView(view, requestAndroidFocus = true)
        return true
    }

    fun ensureHomeDefaultFocus() {
        if (!isHomeRoot()) return
        selectedUiView.get()?.takeIf { it.isShown && isUiTarget(it) }?.let {
            selectView(it, requestAndroidFocus = true)
            return
        }
        requestDefaultFocus()
    }

    private fun startRepeating(direction: Int) {
        if (repeatedDirection == direction) return
        handler.removeCallbacks(repeatRunnable)
        repeatedDirection = direction
        moveFocus(direction)
        handler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS)
    }

    private fun stopRepeating(direction: Int) {
        if (repeatedDirection != direction) return
        repeatedDirection = null
        handler.removeCallbacks(repeatRunnable)
    }

    private fun moveFocus(direction: Int) {
        val dialog = GameFoldDialogRegistry.activeDialog(activity)
        var currentFocus = resolveFocus(dialog)
        if (currentFocus == null) {
            requestDefaultFocus()
            currentFocus = resolveFocus(dialog)
        }
        if (currentFocus == null) return
        if (dialog == null && handleHomeFocusBoundary(direction, currentFocus)) return
        if (dialog == null && moveBetweenGameCards(direction, currentFocus)) return
        if (moveBetweenRecyclerItems(direction, currentFocus)) return
        val nextFocus = currentFocus.focusSearch(direction)?.takeIf {
            it !== currentFocus && it.isShown && it.isEnabled && it.isFocusable && isUiTarget(it)
        }
        if (nextFocus != null) {
            focusView(nextFocus)
        } else {
            dispatchDpadKey(dialog, direction.toKeyCode())
        }
        val movedFocus = resolveFocus(dialog)
        if (movedFocus != null) lastUiFocus = WeakReference(movedFocus)
        scrollFocusedViewIntoView(movedFocus)
    }

    private fun confirmFocusedView() {
        val dialog = GameFoldDialogRegistry.activeDialog(activity)
        var focused = resolveFocus(dialog)
        if (focused == null) {
            requestDefaultFocus()
            focused = resolveFocus(dialog)
        }
        if (focused == null) return
        if (focused.isEnabled && focused.isClickable) {
            handler.post {
                if (focused.isAttachedToWindow && focused.isEnabled) focused.performClick()
            }
        } else {
            dispatchDpadKey(dialog, KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    private fun cancelOrNavigateBack() {
        GameFoldDialogRegistry.activeDialog(activity)?.let {
            it.cancel()
            return
        }
        if (isHomeRoot()) {
            val navHost = activity.supportFragmentManager
                .findFragmentById(R.id.fragment_container) as? NavHostFragment
            if ((navHost?.childFragmentManager?.primaryNavigationFragment as? GamesFragment)
                    ?.closeQuickStart() == true
            ) return
            return
        }
        (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
    }

    private fun navigateToHome() {
        (activity as? GameFoldHomeHost)?.let {
            it.showGameFoldHome()
            return
        }
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_GAMEFOLD_HOME, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
        )
        activity.overridePendingTransition(0, 0)
    }

    private fun dispatchDpadKey(dialog: Dialog?, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        if (dialog != null) {
            dialog.dispatchKeyEvent(down)
            dialog.dispatchKeyEvent(up)
        } else {
            activity.dispatchKeyEvent(down)
            activity.dispatchKeyEvent(up)
        }
    }

    private fun handleHomeFocusBoundary(direction: Int, focused: View): Boolean {
        if (!isMainActivity()) return false
        val settings = activity.findViewById<View?>(R.id.settings_button)
        val information = activity.findViewById<View?>(R.id.information_button)

        if (direction == View.FOCUS_RIGHT && focused === information) {
            return settings?.takeIf { it.isShown }?.let(::focusView) == true
        }
        if (direction == View.FOCUS_LEFT && focused === settings) {
            return information?.takeIf { it.isShown }?.let(::focusView) == true
        }

        if (direction == View.FOCUS_DOWN && (focused === settings || focused === information)) {
            val target = lastGameFocus.get()?.takeIf { it.isShown && it.isFocusable } ?:
                findFirstGameCard()
            return target?.let(::focusView) == true
        }

        if (isGameCard(focused)) {
            lastGameFocus = WeakReference(focused)
            if (direction == View.FOCUS_UP && isGameInFirstRow(focused)) {
                return information?.takeIf { it.isShown }?.let(::focusView) == true ||
                    settings?.takeIf { it.isShown }?.let(::focusView) == true
            }
        }
        return false
    }

    private fun findFirstGameCard(): View? {
        if (!isMainActivity()) return null
        val recycler = activity.findViewById<RecyclerView?>(R.id.grid_games) ?: return null
        for (index in 0 until recycler.childCount) {
            findGameCard(recycler.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun isGameInFirstRow(view: View): Boolean {
        val recycler = activity.findViewById<RecyclerView?>(R.id.grid_games) ?: return false
        val holder = recycler.findContainingViewHolder(view) ?: return false
        return holder.bindingAdapterPosition in 0..3
    }

    private fun moveBetweenGameCards(direction: Int, focused: View): Boolean {
        if (!isGameCard(focused)) return false
        val recycler = activity.findViewById<RecyclerView?>(R.id.grid_games) ?: return false
        val holder = recycler.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val itemCount = recycler.adapter?.itemCount ?: 0
        val targetPosition = when (direction) {
            View.FOCUS_LEFT -> position - 1
            View.FOCUS_RIGHT -> position + 1
            View.FOCUS_UP -> position - HOME_GRID_COLUMNS
            View.FOCUS_DOWN -> {
                val nextRowPosition = position + HOME_GRID_COLUMNS
                // The single add-game card occupies the second row. Every first-row game
                // should reach it with Down, including columns whose direct target is absent.
                if (nextRowPosition < itemCount) nextRowPosition
                else if (position in 0 until HOME_GRID_COLUMNS && itemCount > HOME_GRID_COLUMNS) {
                    HOME_GRID_COLUMNS
                } else {
                    return false
                }
            }
            else -> return false
        }
        if (targetPosition !in 0 until itemCount) return false
        val targetHolder = recycler.findViewHolderForAdapterPosition(targetPosition) ?: return false
        val target = findGameCard(targetHolder.itemView) ?: return false
        if (focusView(target)) {
            lastGameFocus = WeakReference(target)
            scrollFocusedViewIntoView(target)
            return true
        }
        return false
    }

    private fun moveBetweenRecyclerItems(direction: Int, focused: View): Boolean {
        val recycler = findContainingRecycler(focused) ?: return false
        if (recycler.id == R.id.grid_games) return false
        val holder = recycler.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val targetPosition = when (val manager = recycler.layoutManager) {
            is GridLayoutManager -> {
                val columns = manager.spanCount.coerceAtLeast(1)
                when (direction) {
                    View.FOCUS_LEFT -> if (position % columns > 0) position - 1 else return false
                    View.FOCUS_RIGHT -> if (position % columns < columns - 1) position + 1 else return false
                    View.FOCUS_UP -> position - columns
                    View.FOCUS_DOWN -> position + columns
                    else -> return false
                }
            }
            is LinearLayoutManager -> when (direction) {
                View.FOCUS_UP -> position - 1
                View.FOCUS_DOWN -> position + 1
                else -> return false
            }
            else -> return false
        }
        if (targetPosition !in 0 until (recycler.adapter?.itemCount ?: 0)) return false
        val targetHolder = recycler.findViewHolderForAdapterPosition(targetPosition) ?: return false
        val target = findFirstFocusableDescendant(targetHolder.itemView) ?: return false
        if (!focusView(target)) return false
        scrollFocusedViewIntoView(target)
        return true
    }

    private fun findContainingRecycler(view: View): RecyclerView? {
        var current: View? = view
        while (current != null) {
            if (current is RecyclerView) return current
            current = current.parent as? View
        }
        return null
    }

    private fun findGameCard(root: View): View? = when {
        isGameCard(root) -> root
        root is ViewGroup -> sequenceOf(
            R.id.card_game_grid,
            R.id.card_game_grid_compact,
            R.id.card_game_list
        ).mapNotNull { root.findViewById<View?>(it) }.firstOrNull { it.isShown }
        else -> null
    }

    private fun isGameCard(view: View): Boolean = view.id == R.id.card_game_grid ||
        view.id == R.id.card_game_grid_compact || view.id == R.id.card_game_list

    private fun requestFirstFocusable(root: View?): Boolean {
        if (root == null) return false
        val focusables = ArrayList<View>()
        root.addFocusables(focusables, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
        val target = focusables.firstOrNull {
            it.isShown && it.isEnabled && it.isFocusable && it.id != R.id.fragment_container
        } ?: return false
        val focused = focusView(target)
        if (focused) scrollFocusedViewIntoView(target)
        return focused
    }

    private fun resolveFocus(dialog: Dialog?): View? {
        selectedUiView.get()?.takeIf { it.isShown && it.isFocusable && isUiTarget(it) }
            ?.let { return it }
        dialog?.currentFocus?.takeIf { it.isShown && isUiTarget(it) }?.let { return it }
        activity.currentFocus?.takeIf { it.isShown && isUiTarget(it) }?.let { return it }
        if (dialog != null) return null
        return lastUiFocus.get()?.takeIf { it.isShown && it.isFocusable && isUiTarget(it) }
    }

    private fun focusView(view: View): Boolean {
        if (!view.isShown || !view.isEnabled || !view.isFocusable || !isUiTarget(view)) return false
        selectView(view, requestAndroidFocus = true)
        return true
    }

    private fun selectView(view: View, requestAndroidFocus: Boolean) {
        val previous = selectedUiView.get()
        if (previous !== view) restoreOriginalBackground(previous)
        if (!originalBackgrounds.containsKey(view)) originalBackgrounds[view] = view.background
        view.background = if (isGameCard(view)) {
            // Game cards own their focus treatment: the supplied green cartridge asset sits on top.
            originalBackgrounds[view]
        } else {
            val base = originalBackgrounds[view]?.constantState?.newDrawable(activity.resources)?.mutate()
            val ring = ContextCompat.getDrawable(activity, R.drawable.pixel_focus_ring_overlay)?.mutate()
            when {
                base != null && ring != null -> LayerDrawable(arrayOf(base, ring))
                ring != null -> ring
                else -> originalBackgrounds[view]
            }
        }
        selectedUiView = WeakReference(view)
        lastUiFocus = WeakReference(view)
        if (requestAndroidFocus) view.requestFocusFromTouch() || view.requestFocus()
    }

    private fun restoreOriginalBackground(view: View?) {
        if (view == null || !originalBackgrounds.containsKey(view)) return
        view.background = originalBackgrounds[view]
    }

    private fun isUiTarget(view: View): Boolean {
        // The home wordmark is display-only. It must never participate in the controller focus chain.
        if (view.id == R.id.gamefold_logo) return false
        val dialog = GameFoldDialogRegistry.activeDialog(activity)
        if (dialog != null && isDescendantOf(view, dialog.window?.decorView)) return true
        return isDescendantOf(view, activity.findViewById(R.id.gamefold_display_stage))
    }

    private fun isDescendantOf(view: View, root: View?): Boolean {
        if (root == null) return false
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun findFirstRecyclerItem(root: View): View? {
        if (root is RecyclerView) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child.isShown && child.isEnabled && child.isFocusable) return child
                val nested = findFirstFocusableDescendant(child)
                if (nested != null) return nested
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findFirstRecyclerItem(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findFirstFocusableDescendant(root: View): View? {
        if (root.isShown && root.isEnabled && root.isFocusable) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findFirstFocusableDescendant(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun scrollFocusedViewIntoView(view: View?) {
        var current = view ?: return
        while (current.parent is View) {
            val parent = current.parent
            if (parent is RecyclerView) {
                val position = parent.getChildAdapterPosition(current)
                if (position != RecyclerView.NO_POSITION) parent.smoothScrollToPosition(position)
                return
            }
            current = parent as View
        }
    }

    private fun isHomeRoot(): Boolean {
        if (!isMainActivity()) return false
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.fragment_container) as? NavHostFragment
        return navHost?.navController?.currentDestination?.id == R.id.gamesFragment
    }

    private fun isMainActivity(): Boolean =
        activity.javaClass.name == "org.yuzu.yuzu_emu.ui.main.MainActivity"

    private fun GameFoldButton.toFocusDirection(): Int? = when (this) {
        GameFoldButton.DPAD_UP -> View.FOCUS_UP
        GameFoldButton.DPAD_RIGHT -> View.FOCUS_RIGHT
        GameFoldButton.DPAD_DOWN -> View.FOCUS_DOWN
        GameFoldButton.DPAD_LEFT -> View.FOCUS_LEFT
        else -> null
    }

    private fun stickDirection(x: Float, y: Float): Int? {
        val previous = leftStickDirection
        val releaseThreshold = if (previous == null) STICK_ENGAGE_THRESHOLD else STICK_RELEASE_THRESHOLD
        if (abs(x) < releaseThreshold && abs(y) < releaseThreshold) return null
        if (abs(x) >= abs(y)) {
            if (abs(x) < STICK_ENGAGE_THRESHOLD && previous == null) return null
            return if (x >= 0f) View.FOCUS_RIGHT else View.FOCUS_LEFT
        }
        if (abs(y) < STICK_ENGAGE_THRESHOLD && previous == null) return null
        return if (y >= 0f) View.FOCUS_DOWN else View.FOCUS_UP
    }

    private fun Int.toKeyCode(): Int = when (this) {
        View.FOCUS_UP -> KeyEvent.KEYCODE_DPAD_UP
        View.FOCUS_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        View.FOCUS_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> KeyEvent.KEYCODE_DPAD_LEFT
    }

    companion object {
        private const val INITIAL_REPEAT_DELAY_MS = 350L
        private const val REPEAT_INTERVAL_MS = 140L
        private const val STICK_ENGAGE_THRESHOLD = 0.55f
        private const val STICK_RELEASE_THRESHOLD = 0.35f
        private const val HOME_GRID_COLUMNS = 4
    }
}

/** Tracks only app-owned dialogs in UI-navigation Activities. */
object GameFoldDialogRegistry {
    private val dialogs = WeakHashMap<Activity, MutableList<WeakReference<Dialog>>>()

    fun register(activity: Activity, dialog: Dialog): Boolean {
        if (!supportsUiNavigation(activity)) return false
        val stack = dialogs.getOrPut(activity) { mutableListOf() }
        stack.removeAll { it.get() == null || it.get() === dialog }
        stack += WeakReference(dialog)
        return true
    }

    fun unregister(activity: Activity, dialog: Dialog?) {
        val stack = dialogs[activity] ?: return
        stack.removeAll { it.get() == null || it.get() === dialog }
        if (stack.isEmpty()) dialogs.remove(activity)
    }

    fun activeDialog(activity: Activity): Dialog? {
        val stack = dialogs[activity] ?: return null
        stack.removeAll { it.get() == null || it.get()?.isShowing != true }
        return stack.lastOrNull()?.get()
    }

    fun requestDefaultFocus(dialog: Dialog) {
        dialog.window?.decorView?.post {
            val focusables = ArrayList<View>()
            dialog.window?.decorView?.addFocusables(
                focusables,
                View.FOCUS_FORWARD,
                View.FOCUSABLES_ALL
            )
            focusables.firstOrNull { it.isShown && it.isEnabled && it.isFocusable }
                ?.requestFocus()
        }
    }

    private fun supportsUiNavigation(activity: Activity): Boolean = when (activity.javaClass.name) {
        "org.yuzu.yuzu_emu.ui.main.MainActivity",
        "org.yuzu.yuzu_emu.features.settings.ui.SettingsActivity",
        "org.yuzu.yuzu_emu.features.settings.ui.SettingsSubscreenActivity" -> true
        else -> false
    }
}
