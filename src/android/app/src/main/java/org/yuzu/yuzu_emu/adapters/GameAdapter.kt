// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.content.DialogInterface
import android.text.Html
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.CardGameListBinding
import org.yuzu.yuzu_emu.databinding.CardGameGridBinding
import org.yuzu.yuzu_emu.databinding.CardGameCarouselBinding
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.GameFoldDialogSizing
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import androidx.core.net.toUri
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.databinding.CardGameGridCompactBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.Settings

class GameAdapter(private val activity: AppCompatActivity) :
    AbstractDiffAdapter<Game, GameAdapter.GameViewHolder>(exact = false) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_GRID_COMPACT = 1
        const val VIEW_TYPE_LIST = 2
        const val VIEW_TYPE_CAROUSEL = 3
        private const val ADD_GAME_PATH = "gamefold://add-game"
        val addGameItem = Game(title = "+", path = ADD_GAME_PATH)
    }

    private var viewType = 0
    var onFirstGameBound: ((View) -> Unit)? = null
    var onAddGameRequested: (() -> Unit)? = null
    private var portraitLayout = false

    fun setViewType(type: Int) {
        viewType = type
        notifyDataSetChanged()
    }

    fun setPortraitLayout(isPortrait: Boolean) {
        if (portraitLayout == isPortrait) return
        portraitLayout = isPortrait
        notifyDataSetChanged()
    }

    fun hasRealGames(): Boolean = currentList.any { it.path != ADD_GAME_PATH }

    public var cardSize: Int = 0
        private set

    fun setCardSize(size: Int) {
        if (cardSize != size && size > 0) {
            cardSize = size
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = viewType

    private fun gridRootWidth(): Int = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        when (getItemViewType(position)) {
            VIEW_TYPE_LIST -> {
                val listBinding = holder.binding as CardGameListBinding
                listBinding.cardGameList.scaleX = 1f
                listBinding.cardGameList.scaleY = 1f
                listBinding.cardGameList.alpha = 1f
                // Reset layout params to XML defaults
                listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID -> {
                val gridBinding = holder.binding as CardGameGridBinding
                gridBinding.cardGameGrid.scaleX = 1f
                gridBinding.cardGameGrid.scaleY = 1f
                gridBinding.cardGameGrid.alpha = 1f
                // Reset layout params to XML defaults
                gridBinding.root.layoutParams.width = gridRootWidth()
                gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                gridBinding.cardGameGrid.nextFocusUpId =
                    if (position < 4) R.id.settings_button else View.NO_ID
            }

            VIEW_TYPE_GRID_COMPACT -> {
                val gridCompactBinding = holder.binding as CardGameGridCompactBinding
                gridCompactBinding.cardGameGridCompact.scaleX = 1f
                gridCompactBinding.cardGameGridCompact.scaleY = 1f
                gridCompactBinding.cardGameGridCompact.alpha = 1f
                // Reset layout params to XML defaults (same as normal grid)
                gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                gridCompactBinding.cardGameGridCompact.nextFocusUpId =
                    if (position < 4) R.id.settings_button else View.NO_ID
            }

            VIEW_TYPE_CAROUSEL -> {
                val carouselBinding = holder.binding as CardGameCarouselBinding
                // soothens transient flickering
                carouselBinding.cardGameCarousel.scaleY = 0f
                carouselBinding.cardGameCarousel.alpha = 0f
            }
        }
        if (position == 0 && currentList.getOrNull(position)?.path != ADD_GAME_PATH) {
            val firstCard = when (getItemViewType(position)) {
                VIEW_TYPE_GRID -> (holder.binding as CardGameGridBinding).cardGameGrid
                VIEW_TYPE_GRID_COMPACT ->
                    (holder.binding as CardGameGridCompactBinding).cardGameGridCompact
                VIEW_TYPE_LIST -> (holder.binding as CardGameListBinding).cardGameList
                else -> null
            }
            firstCard?.post { onFirstGameBound?.invoke(firstCard) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = when (viewType) {
            VIEW_TYPE_LIST -> CardGameListBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID -> CardGameGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID_COMPACT -> CardGameGridCompactBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_CAROUSEL -> CardGameCarouselBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            else -> throw IllegalArgumentException("Invalid view type")
        }
        return GameViewHolder(binding, viewType)
    }

    inner class GameViewHolder(
        internal val binding: ViewBinding,
        private val viewType: Int
    ) : AbstractViewHolder<Game>(binding) {

        override fun bind(model: Game) {
            when (viewType) {
                VIEW_TYPE_LIST -> bindListView(model)
                VIEW_TYPE_GRID -> bindGridView(model)
                VIEW_TYPE_CAROUSEL -> bindCarouselView(model)
                VIEW_TYPE_GRID_COMPACT -> bindGridCompactView(model)
            }
        }

        private fun bindListView(model: Game) {
            val listBinding = binding as CardGameListBinding

            listBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, listBinding.imageGameScreen)

            listBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            listBinding.textGameDeveloper.text = model.developer

            listBinding.textGameTitle.marquee()
            listBinding.cardGameList.setOnClickListener { onClick(model) }
            listBinding.cardGameList.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults
            listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridView(model: Game) {
            val gridBinding = binding as CardGameGridBinding
            val isAddGame = model.path == ADD_GAME_PATH

            configureGridArtwork(gridBinding, isAddGame)
            if (isAddGame) {
                gridBinding.imageGameScreen.setImageResource(R.drawable.gamefold_add_game_cartridge)
            } else {
                GameIconUtils.loadGameIcon(model, gridBinding.imageGameScreen)
            }

            gridBinding.textGameTitle.text = if (isAddGame) {
                binding.root.context.getString(R.string.add_game)
            } else {
                model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            }

            gridBinding.textGameTitle.apply {
                isSingleLine = false
                ellipsize = TextUtils.TruncateAt.END
                isSelected = false
                maxLines = 2
            }
            gridBinding.cardGameGrid.contentDescription = if (isAddGame) {
                binding.root.context.getString(R.string.add_game)
            } else {
                binding.root.context.getString(R.string.game_image_desc, model.title)
            }
            gridBinding.cardGameGrid.setOnFocusChangeListener { _, _ ->
                gridBinding.textGameTitle.apply {
                    ellipsize = TextUtils.TruncateAt.END
                    isSelected = false
                }
            }
            gridBinding.cardGameGrid.setOnClickListener { onClick(model) }
            gridBinding.cardGameGrid.setOnLongClickListener {
                model.path != ADD_GAME_PATH && onLongClick(model)
            }

            // Reset layout params to XML defaults
            gridBinding.root.layoutParams.width = gridRootWidth()
            gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun configureGridArtwork(binding: CardGameGridBinding, isAddGame: Boolean) {
            binding.cardGameGrid.widthScale = if (portraitLayout) 0.90f else 0.875f
            binding.gameCartridgeFrame.visibility = if (isAddGame) View.INVISIBLE else View.VISIBLE
            binding.imageGameScreen.enforceSquare = !isAddGame
            binding.imageGameScreen.scaleType = if (isAddGame) {
                ImageView.ScaleType.FIT_XY
            } else {
                ImageView.ScaleType.CENTER_CROP
            }

            val density = binding.root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            binding.imageGameScreen.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isAddGame) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (isAddGame) {
                    // The supplied add-game artwork shares the cartridge ratio, so it must
                    // fill the same aspect-ratio container without an independent squeeze.
                    gravity = Gravity.FILL
                } else {
                    // Portrait gives the square cover a little more breathing room inside the
                    // cartridge window before the complete cover+frame group is reduced.
                    val sideMargin = if (portraitLayout) dp(16) else dp(14)
                    val topMargin = if (portraitLayout) dp(15) else dp(13)
                    setMargins(sideMargin, topMargin, sideMargin, 0)
                    gravity = Gravity.TOP
                }
            }
        }

        private fun bindGridCompactView(model: Game) {
            val gridCompactBinding = binding as CardGameGridCompactBinding

            gridCompactBinding.imageGameScreenCompact.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridCompactBinding.imageGameScreenCompact)

            gridCompactBinding.textGameTitleCompact.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")

            gridCompactBinding.textGameTitleCompact.marquee()
            gridCompactBinding.cardGameGridCompact.setOnClickListener { onClick(model) }
            gridCompactBinding.cardGameGridCompact.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults (same as normal grid)
            gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindCarouselView(model: Game) {
            val carouselBinding = binding as CardGameCarouselBinding

            carouselBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, carouselBinding.imageGameScreen)

            carouselBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            carouselBinding.textGameTitle.marquee()
            carouselBinding.cardGameCarousel.setOnClickListener { onClick(model) }
            carouselBinding.cardGameCarousel.setOnLongClickListener { onLongClick(model) }

            carouselBinding.imageGameScreen.contentDescription =
                binding.root.context.getString(R.string.game_image_desc, model.title)

            // Ensure zero-heighted-full-width cards for carousel
            carouselBinding.root.layoutParams.width = cardSize
        }

        fun onClick(game: Game) {
            if (game.path == ADD_GAME_PATH) {
                onAddGameRequested?.invoke()
                return
            }
            val gameUri = game.path.toUri()
            val gameExists = if (gameUri.scheme.isNullOrEmpty() || gameUri.scheme == "file") {
                java.io.File(gameUri.path ?: game.path).isFile
            } else {
                DocumentFile.fromSingleUri(YuzuApplication.appContext, gameUri)?.exists() == true
            }

            if (!gameExists) {
                Toast.makeText(
                    YuzuApplication.appContext,
                    R.string.loader_error_file_not_found,
                    Toast.LENGTH_LONG
                ).show()

                ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
                return
            }

            val launch: () -> Unit = {
                val preferences =
                    PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                preferences.edit {
                    putLong(
                        game.keyLastPlayedTime,
                        System.currentTimeMillis()
                    )
                }

                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val shortcut =
                            ShortcutInfoCompat.Builder(YuzuApplication.appContext, game.path)
                                .setShortLabel(game.title)
                                .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                                .setIntent(game.launchIntent)
                                .build()
                        ShortcutManagerCompat.pushDynamicShortcut(
                            YuzuApplication.appContext,
                            shortcut
                        )
                    }
                }

                if (EmulationActivity.hasActiveSession()) {
                    EmulationActivity.returnToSessionOrSwap(activity, game)
                } else {
                    val action = HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
                    binding.root.findNavController().navigate(action)
                }
            }

            if (NativeLibrary.gameRequiresFirmware(game.programId) && !NativeLibrary.isFirmwareAvailable()) {
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.loader_requires_firmware)
                    .setMessage(
                        Html.fromHtml(
                            activity.getString(R.string.loader_requires_firmware_description),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        launch()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .create()
                GameFoldDialogSizing.showWithinStage(activity, dialog)
            } else {
                launch()
            }
        }

        fun onLongClick(game: Game): Boolean {
            val action = HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)
            binding.root.findNavController().navigate(action)
            return true
        }
    }
}
