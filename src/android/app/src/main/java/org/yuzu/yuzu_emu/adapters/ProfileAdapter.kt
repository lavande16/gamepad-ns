// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.ListItemProfileBinding
import org.yuzu.yuzu_emu.model.UserProfile
import java.io.File

class ProfileAdapter(
    private val onProfileClick: (UserProfile) -> Unit,
    private val onEditClick: (UserProfile) -> Unit,
    private val onDeleteClick: (UserProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private var currentUserUUID: String = ""

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<UserProfile>() {
        override fun areItemsTheSame(oldItem: UserProfile, newItem: UserProfile): Boolean {
            return oldItem.uuid == newItem.uuid
        }

        override fun areContentsTheSame(oldItem: UserProfile, newItem: UserProfile): Boolean {
            return oldItem == newItem
        }
    })

    fun submitList(list: List<UserProfile>) {
        differ.submitList(list)
    }

    fun setCurrentUser(uuid: String) {
        currentUserUUID = uuid
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ListItemProfileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    override fun getItemCount(): Int = differ.currentList.size

    inner class ProfileViewHolder(private val binding: ListItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: UserProfile) {
            val isLegacyDefaultProfile = profile.username.equals("Eden", ignoreCase = true)
            binding.textUsername.text = if (isLegacyDefaultProfile) "GameFold" else profile.username
            binding.textUuid.text = formatUUID(profile.uuid)

            val imageFile = File(profile.imagePath)
            if (isLegacyDefaultProfile) {
                binding.imageAvatar.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.imageAvatar.setImageResource(R.drawable.gamefold_profile_mark)
            } else if (imageFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(profile.imagePath)
                if (bitmap != null) {
                    binding.imageAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.imageAvatar.setImageBitmap(bitmap)
                } else {
                    binding.imageAvatar.scaleType = ImageView.ScaleType.FIT_CENTER
                    binding.imageAvatar.setImageResource(R.drawable.gamefold_profile_mark)
                }
            } else {
                binding.imageAvatar.scaleType = ImageView.ScaleType.FIT_CENTER
                binding.imageAvatar.setImageResource(R.drawable.gamefold_profile_mark)
            }

            if (profile.uuid == currentUserUUID) {
                binding.checkContainer.visibility = View.VISIBLE
            } else {
                binding.checkContainer.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onProfileClick(profile)
            }

            binding.buttonEdit.setOnClickListener {
                onEditClick(profile)
            }

            binding.buttonDelete.setOnClickListener {
                onDeleteClick(profile)
            }
        }

        private fun formatUUID(uuid: String): String {
            if (uuid.length != 32) return uuid
            return buildString {
                append(uuid.substring(0, 8))
                append("-")
                append(uuid.substring(8, 12))
                append("-")
                append(uuid.substring(12, 16))
                append("-")
                append(uuid.substring(16, 20))
                append("-")
                append(uuid.substring(20, 32))
            }
        }
    }
}
