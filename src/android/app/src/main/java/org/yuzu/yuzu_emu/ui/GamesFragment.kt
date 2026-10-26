// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.GameAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGamesBinding
import org.yuzu.yuzu_emu.features.input.GameFoldUiFocusHost
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.features.rearscreen.RearDisplayController
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.model.AppletInfo
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
import org.yuzu.yuzu_emu.utils.GameFoldDialogSizing
import org.yuzu.yuzu_emu.utils.GameFoldOrientation
import org.yuzu.yuzu_emu.utils.GamePadNsProductContract
import org.yuzu.yuzu_emu.utils.GamePadNsHomeGeometry
import org.yuzu.yuzu_emu.utils.GamePadNsDisplaySizeController
import org.yuzu.yuzu_emu.views.GameFoldShellLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class GamesFragment : Fragment() {
    private companion object {
        const val GAME_DOWNLOAD_URL =
            "https://pan.xunlei.com/s/VOxy7Ezye5UoPXB3DLxo3riWA1?pwd=wxu2"
    }

    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private var originalHeaderTopMargin: Int? = null

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter
    private var initialGameFocusAssigned = false
    private var initialFocusPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var gamesLayoutChangeListener: View.OnLayoutChangeListener? = null
    private var quickStartBackCallback: OnBackPressedCallback? = null
    private var quickStartVisible = false
    private val rearSessionStateListener = RearDisplayController.SessionStateListener {
        if (_binding != null) syncDualScreenToggle()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(true)
        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity
        )
        gameAdapter.onFirstGameBound = { card ->
            if (!initialGameFocusAssigned && _binding != null) {
                initialGameFocusAssigned =
                    (activity as? GameFoldUiFocusHost)?.requestGameFoldUiFocus(card)
                        ?: card.requestFocusFromTouch()
            }
        }

        applyGridGamesBinding()
        gamesLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            gameAdapter.setPortraitLayout(isPortraitShell())
            // Orientation is handled through configChanges, so the Fragment is not recreated.
            // Sync after the shell has received its new dimensions; otherwise the dual-screen
            // toggle remains hidden until the Quick Start panel happens to refresh it.
            syncDualScreenToggle()
        }.also { binding.root.addOnLayoutChangeListener(it) }
        quickStartBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeQuickStart()
            }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        initialFocusPreDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!initialGameFocusAssigned && gameAdapter.itemCount > 0 &&
                binding.gridGames.childCount > 0
            ) {
                requestInitialGameFocus()
            }
            if (initialGameFocusAssigned) removeInitialFocusPreDrawListener()
            true
        }.also { binding.gridGames.viewTreeObserver.addOnPreDrawListener(it) }

        binding.swipeRefresh.apply {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setOnRefreshListener {
                gamesViewModel.reloadGames(false)
            }
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressBackgroundColorSchemeColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setColorSchemeColors(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            post {
                if (_binding == null) {
                    return@post
                }
                (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = gamesViewModel.isReloading.value
            }
        }

        gamesViewModel.isReloading.collect(viewLifecycleOwner) {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = it
            binding.noticeText.setVisible(
                visible = gamesViewModel.games.value.isEmpty() && !it,
                gone = false
            )
        }
        gamesViewModel.games.collect(viewLifecycleOwner) {
            setAdapter(it)
        }
        gamesViewModel.shouldSwapData.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldSwapData(false) }
        ) {
            if (it) {
                setAdapter(gamesViewModel.games.value)
            }
        }
        gamesViewModel.shouldScrollToTop.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldScrollToTop(false) }
        ) { if (it) scrollToTop() }

        gamesViewModel.shouldScrollAfterReload.collect(viewLifecycleOwner) { shouldScroll ->
            if (shouldScroll) {
                binding.gridGames.post {
                    // The initial scan can finish with a list that compares equal to the cached
                    // StateFlow value. Re-submit the final scan result when the completion event
                    // arrives so newly discovered cards appear without a manual refresh.
                    setAdapter(gamesViewModel.games.value)
                }
                gamesViewModel.setShouldScrollAfterReload(false)
            }
        }

        setupTopView()

        updateButtonsVisibility()

        binding.launchQlaunch?.setOnClickListener {
            launchQLaunch()
        }

        setInsets()
    }

    val applyGridGamesBinding = {
        (binding.gridGames as? RecyclerView)?.apply {
            // Lite deliberately has one stable card presentation. No preference can
            // reactivate the overlapping/scaling carousel.
            adapter = null
            recycledViewPool.clear()
            gameAdapter.setViewType(GameAdapter.VIEW_TYPE_GRID)
            layoutManager = GridLayoutManager(context, 4, RecyclerView.VERTICAL, false)
            adapter = gameAdapter
        }
    }

    private fun setAdapter(games: List<Game>) {
        ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(
            games + GameAdapter.addGameItem
        ) {
            requestInitialGameFocus()
        }
        gamesViewModel.setFilteredGames(games)
    }

    private fun requestInitialGameFocus() {
        if (initialGameFocusAssigned || _binding == null || !gameAdapter.hasRealGames()) return
        binding.gridGames.scrollToPosition(0)
        val holder = binding.gridGames.findViewHolderForAdapterPosition(0) ?: return
        val card = holder.itemView.findViewById<View>(R.id.card_game_grid)
            ?: holder.itemView.findViewById(R.id.card_game_grid_compact)
        initialGameFocusAssigned = card?.let {
            (activity as? GameFoldUiFocusHost)?.requestGameFoldUiFocus(it)
                ?: it.requestFocusFromTouch()
        } == true
        if (initialGameFocusAssigned) removeInitialFocusPreDrawListener()
    }

    private fun removeInitialFocusPreDrawListener() {
        val listener = initialFocusPreDrawListener ?: return
        val observer = binding.gridGames.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        initialFocusPreDrawListener = null
    }

    override fun onResume() {
        super.onResume()
        initialGameFocusAssigned = false
        syncDualScreenToggle()
        if (_binding != null) binding.gridGames.post { requestInitialGameFocus() }
    }

    override fun onStart() {
        super.onStart()
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearDisplayController.addSessionStateListener(rearSessionStateListener)
        }
    }

    override fun onStop() {
        if (!GamePadNsProductContract.STRICT_GAMEPAD_SHELL) {
            RearDisplayController.removeSessionStateListener(rearSessionStateListener)
        }
        super.onStop()
    }

    private fun setupTopView() {
        binding.settingsButton.setOnClickListener { navigateToSettings() }
        binding.informationButton.setOnClickListener { toggleDisplaySize() }
        // GamePad NS is deliberately a strict single-screen landscape product. The legacy
        // orientation and rear-display controls remain in the source tree for baseline
        // compatibility, but are not part of this product surface.
        binding.orientationToggleButton.visibility = View.GONE
        binding.dualScreenToggleButton.visibility = View.GONE
        binding.orientationToggleButton.setOnClickListener(null)
        binding.dualScreenToggleButton.setOnClickListener(null)
        syncDualScreenToggle()
        binding.quickStartPanel.visibility = View.GONE
        quickStartVisible = false
        binding.gamefoldLogo.setOnClickListener { setQuickStartVisible(false) }
        gameAdapter.onAddGameRequested = { showAddGameDialog() }
    }

    private fun setQuickStartVisible(visible: Boolean) {
        quickStartVisible = visible
        quickStartBackCallback?.isEnabled = visible
        binding.quickStartPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.gridGames.visibility = if (visible) View.GONE else View.VISIBLE
        binding.noticeText.visibility = if (visible) View.GONE else binding.noticeText.visibility
        if (visible) {
            syncDualScreenToggle()
            (activity as? GameFoldUiFocusHost)?.requestGameFoldUiFocus(binding.informationButton)
        } else {
            binding.gridGames.post { requestInitialGameFocus() }
        }
    }

    private fun syncDualScreenToggle() {
        if (_binding == null) return
        binding.dualScreenToggleButton.visibility = View.GONE
        binding.orientationToggleButton.visibility = View.GONE
    }

    fun closeQuickStart(): Boolean {
        if (!quickStartVisible || _binding == null) return false
        setQuickStartVisible(false)
        return true
    }

    private fun showAddGameDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_game)
            .setMessage(R.string.add_game_path_message)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.download_game) { _, _ -> openGameDownloadLink() }
            .create()
        dialog.setOnShowListener {
            val actionColor = ContextCompat.getColor(requireContext(), R.color.pixel_action)
            val textColor = ContextCompat.getColor(requireContext(), R.color.pixel_text_light)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(textColor)
                backgroundTintList = ColorStateList.valueOf(actionColor)
                if (this is MaterialButton) {
                    iconTint = ColorStateList.valueOf(textColor)
                }
            }
        }
        GameFoldDialogSizing.showWithinStageOnFirstFrame(requireActivity(), dialog)
    }

    private fun openGameDownloadLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GAME_DOWNLOAD_URL))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.download_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToSettings() {
        val navController = findNavController()
        navController.navigate(R.id.action_gamesFragment_to_homeSettingsFragment)
    }

    override fun onDestroyView() {
        gameAdapter.onFirstGameBound = null
        gameAdapter.onAddGameRequested = null
        gamesLayoutChangeListener?.let { binding.root.removeOnLayoutChangeListener(it) }
        gamesLayoutChangeListener = null
        if (_binding != null) removeInitialFocusPreDrawListener()
        super.onDestroyView()
        _binding = null
    }

    private fun scrollToTop() {
        if (_binding != null) {
            binding.gridGames.smoothScrollToPosition(0)
        }
    }

    private fun launchQLaunch() {
        try {
            val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.QLaunch.entryId)
            if (appletPath.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.applets_error_applet,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            NativeLibrary.setCurrentAppletId(AppletInfo.QLaunch.appletId)

            val qlaunchGame = Game(
                title = getString(R.string.qlaunch_applet),
                path = appletPath
            )

            val action = HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to launch QLaunch: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateButtonsVisibility() {
        val showQLaunch = BooleanSetting.ENABLE_QLAUNCH_BUTTON.getBoolean()
        val isFirmwareAvailable = NativeLibrary.isFirmwareAvailable()

        val shouldShowQLaunch = showQLaunch && isFirmwareAvailable
        binding.launchQlaunch.visibility = if (shouldShowQLaunch) View.VISIBLE else View.GONE

    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressViewEndTarget(
                false,
                barInsets.top + resources.getDimensionPixelSize(R.dimen.spacing_refresh_end)
            )

            val mlpHeader = binding.header.layoutParams as ViewGroup.MarginLayoutParams

            if (originalHeaderTopMargin == null) {
                originalHeaderTopMargin = mlpHeader.topMargin
            }

            // The centered 16:9 stage is already clear of the system status bar.
            // Re-applying the device inset here wastes a full header row inside the stage.
            mlpHeader.topMargin = originalHeaderTopMargin ?: 0
            binding.header.layoutParams = mlpHeader

            val screenWidthDp = resources.configuration.screenWidthDp
            val density = resources.displayMetrics.density
            val headerInset = (
                GamePadNsHomeGeometry.headerHorizontalInsetDp(screenWidthDp) * density
                ).roundToInt()
            binding.header.setPadding(headerInset, 8.dpPx(), headerInset, 8.dpPx())
            val gridParams = binding.gridGames.layoutParams as? android.widget.RelativeLayout.LayoutParams
            if (gridParams != null) {
                gridParams.width = (
                    resources.displayMetrics.widthPixels *
                        GamePadNsHomeGeometry.gridWidthFraction(screenWidthDp)
                    ).roundToInt()
                gridParams.leftMargin = 0
                gridParams.rightMargin = 0
                gridParams.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL, android.widget.RelativeLayout.TRUE)
                binding.gridGames.layoutParams = gridParams
            }
            binding.gridGames.setPadding(
                (48 * density).roundToInt(),
                (12 * density).roundToInt(),
                (48 * density).roundToInt(),
                (8 * density).roundToInt()
            )

            binding.launchQlaunch?.let { qlaunchButton ->
                val mlpQLaunch = qlaunchButton.layoutParams as ViewGroup.MarginLayoutParams
                val stageGutter = resources.getDimensionPixelSize(R.dimen.pixel_space_md)
                mlpQLaunch.leftMargin = stageGutter
                mlpQLaunch.bottomMargin = barInsets.bottom + stageGutter
                qlaunchButton.layoutParams = mlpQLaunch
            }

            windowInsets
        }

    private fun Int.dpPx(): Int =
        (this * resources.displayMetrics.density).roundToInt()

    private fun toggleDisplaySize() {
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = GamePadNsDisplaySizeController.toggle(requireContext())
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(
                    requireContext(),
                    result,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun isPortraitShell(): Boolean {
        var current: View? = binding.root
        while (current != null) {
            if (current is GameFoldShellLayout) {
                return current.height > current.width
            }
            current = current.parent as? View
        }
        return false
    }
}
