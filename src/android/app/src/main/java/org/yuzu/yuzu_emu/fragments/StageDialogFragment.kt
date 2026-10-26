// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import androidx.fragment.app.DialogFragment
import android.view.WindowManager
import org.yuzu.yuzu_emu.features.input.GameFoldDialogRegistry
import org.yuzu.yuzu_emu.utils.GameFoldDialogSizing

/** Base class for app-owned dialogs that must stay inside the 16:9 display stage. */
abstract class StageDialogFragment : DialogFragment() {
    override fun onStart() {
        super.onStart()
        val currentDialog = dialog ?: return
        GameFoldDialogSizing.constrainToStage(requireActivity(), currentDialog)
        if (GameFoldDialogRegistry.register(requireActivity(), currentDialog)) {
            // The dialog occupies only the upper display stage. Let controller-area touches pass
            // through to the permanent shell while the bridge targets this dialog's focus tree.
            currentDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            GameFoldDialogRegistry.requestDefaultFocus(currentDialog)
        }
    }

    override fun onStop() {
        GameFoldDialogRegistry.unregister(requireActivity(), dialog)
        super.onStop()
    }
}
