// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentAboutBinding
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import org.yuzu.yuzu_emu.utils.GamePadNsHomeGeometry
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.utils.LiteModePreferences

class AboutFragment : Fragment() {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private var versionTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(visible = false)
        binding.toolbarAbout.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.imageLogo.setOnLongClickListener {
            Toast.makeText(
                requireContext(),
                R.string.gaia_is_not_real,
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        val fullVersionText = getString(R.string.gamefold_brand_name)

        binding.textVersionName.text = fullVersionText
        binding.buttonVersionName.setOnClickListener {
            versionTapCount++
            if (versionTapCount == 1) {
                Toast.makeText(
                    requireContext(),
                    if (LiteModePreferences.isMaintenanceModeEnabled(requireContext())) {
                        R.string.advanced_settings_mode_tap_hint_disable
                    } else {
                        R.string.advanced_settings_mode_tap_hint_enable
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (versionTapCount < MAINTENANCE_MODE_TAPS) {
                return@setOnClickListener
            }

            versionTapCount = 0
            val enabled = LiteModePreferences.toggleMaintenanceMode(requireContext())
            Toast.makeText(
                requireContext(),
                if (enabled) R.string.advanced_settings_mode_enabled else R.string.advanced_settings_mode_disabled,
                Toast.LENGTH_LONG
            ).show()
        }

        binding.buttonWebsite.setOnClickListener { openLink(getString(R.string.website_link)) }
        binding.buttonXiaohongshu.setOnClickListener { openLink(getString(R.string.dribbble_link)) }
        binding.buttonFeedback.setOnClickListener {
            openLink(getString(R.string.dribbble_link))
        }
        binding.buttonDribbble.setOnClickListener { openLink(getString(R.string.dribbble_link)) }

        setInsets()
    }

    private fun openLink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        startActivity(intent)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val horizontalInset = GamePadNsHomeGeometry.headerHorizontalInsetPx(requireContext())
            binding.contentAbout.updatePadding(left = horizontalInset, right = horizontalInset)
            binding.contentAbout.updatePadding(bottom = barInsets.bottom)

            windowInsets
        }

    companion object {
        private const val MAINTENANCE_MODE_TAPS = 7
    }
}
