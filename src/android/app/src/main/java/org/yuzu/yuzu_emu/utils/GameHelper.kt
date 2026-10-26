// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GameDir
import org.yuzu.yuzu_emu.model.MinimalDocumentFile
import androidx.core.content.edit
import androidx.core.net.toUri

object GameHelper {
    private const val KEY_OLD_GAME_PATH = "game_path"
    const val KEY_GAMES = "Games"

    var cachedGameList = mutableListOf<Game>()

    private lateinit var preferences: SharedPreferences

    fun getGames(): List<Game> {
        val games = mutableListOf<Game>()
        val context = YuzuApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val gameDirs = mutableListOf<GameDir>()
        val oldGamesDir = preferences.getString(KEY_OLD_GAME_PATH, "") ?: ""
        if (oldGamesDir.isNotEmpty()) {
            gameDirs.add(GameDir(oldGamesDir, true))
            preferences.edit() { remove(KEY_OLD_GAME_PATH) }
        }
        gameDirs.addAll(NativeConfig.getGameDirs())

        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        // Reset metadata so we don't use stale information
        GameMetadata.resetMetadata()

        // Remove previous filesystem provider information so we can get up to date version info
        NativeLibrary.clearFilesystemProvider()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)

        val badDirs = mutableListOf<Int>()
        gameDirs.forEachIndexed { index: Int, gameDir: GameDir ->
            val gameDirUri = gameDir.uriString.toUri()
            val rawDirectory = if (gameDirUri.scheme.isNullOrEmpty() || gameDirUri.scheme == "file") {
                File(gameDirUri.path ?: gameDir.uriString)
            } else {
                null
            }
            if (rawDirectory?.isDirectory == true) {
                addGamesFromFileTree(games, rawDirectory, mountedContainerUris)
            } else if (FileUtil.isTreeUriValid(gameDirUri)) {
                val scanDepth = if (gameDir.deepScan) 3 else 1

                addGamesRecursive(
                    games,
                    FileUtil.listFiles(gameDirUri),
                    scanDepth,
                    mountedContainerUris
                )
            } else {
                badDirs.add(index)
            }
        }

        // Remove all game dirs with insufficient permissions from config
        if (badDirs.isNotEmpty()) {
            var offset = 0
            badDirs.forEach {
                gameDirs.removeAt(it - offset)
                offset++
            }
        }
        NativeConfig.setGameDirs(gameDirs.toTypedArray())

        val uniqueGames = deduplicateGames(games)

        // Cache list of games found on disk
        val serializedGames = mutableSetOf<String>()
        uniqueGames.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit() {
            remove(KEY_GAMES)
                .putStringSet(KEY_GAMES, serializedGames)
        }

        cachedGameList = uniqueGames.toMutableList()
        return uniqueGames
    }

    private fun addGamesFromFileTree(
        games: MutableList<Game>,
        root: File,
        mountedContainerUris: MutableSet<String>
    ) {
        root.walkTopDown().maxDepth(8).filter { it.isFile }.forEach { file ->
            val extension = file.extension.lowercase()
            val path = file.absolutePath
            if (externalContentExtensions.contains(extension) && mountedContainerUris.add(path)) {
                NativeLibrary.addGameFolderFileToFilesystemProvider(path)
            }
            if (!Game.extensions.contains(extension) || !isLaunchableGameFile(file, root)) {
                return@forEach
            }
            getGame(Uri.parse(path), true, false)?.let { games.add(it) }
        }
    }

    private fun isLaunchableGameFile(file: File, root: File): Boolean {
        // A standalone ROM may sit directly in games. Structured GameFold units may
        // place their base ROM in the game's folder or in a rom/roms child folder.
        val isFlatRootRom = file.parentFile?.canonicalFile == root.canonicalFile
        val isDirectUnitRom = file.parentFile?.parentFile?.canonicalFile == root.canonicalFile
        return isFlatRootRom || isDirectUnitRom || isInsideRomDirectory(file, root)
    }

    private fun deduplicateGames(games: List<Game>): List<Game> {
        val seen = linkedSetOf<String>()
        return games.filter { game ->
            val numericProgramId = game.programId.toLongOrNull()?.takeIf { it != 0L }
            val identity = if (numericProgramId != null) {
                "program:$numericProgramId"
            } else {
                val uri = game.path.toUri()
                val normalizedPath = if (uri.scheme.isNullOrEmpty() || uri.scheme == "file") {
                    File(uri.path ?: game.path).canonicalPath
                } else {
                    game.path
                }
                "path:$normalizedPath"
            }
            seen.add(identity)
        }
    }

    private fun isInsideRomDirectory(file: File, root: File): Boolean {
        var parent = file.parentFile
        while (parent != null && parent != root.parentFile) {
            if (parent.name.equals("rom", true) || parent.name.equals("roms", true)) return true
            if (parent == root) break
            parent = parent.parentFile
        }
        return false
    }

    fun restoreContentForGame(game: Game) {
        NativeLibrary.reloadKeys()

        val mountedContainerUris = mutableSetOf<String>()
        mountExternalContentDirectories(mountedContainerUris)
        mountGameFolderContent(Uri.parse(game.path), mountedContainerUris)
        NativeLibrary.addFileToFilesystemProvider(game.path)
    }

    // File extensions considered as external content, buuut should
    // be done better imo.
    private val externalContentExtensions = setOf("nsp", "xci")

    private fun scanContentContainersRecursive(
        files: Array<MinimalDocumentFile>,
        depth: Int,
        onContainerFound: (MinimalDocumentFile) -> Unit
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                scanContentContainersRecursive(
                    FileUtil.listFiles(it.uri),
                    depth - 1,
                    onContainerFound
                )
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                if (externalContentExtensions.contains(extension)) {
                    onContainerFound(it)
                }
            }
        }
    }

    private fun addGamesRecursive(
        games: MutableList<Game>,
        files: Array<MinimalDocumentFile>,
        depth: Int,
        mountedContainerUris: MutableSet<String>
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                addGamesRecursive(
                    games,
                    FileUtil.listFiles(it.uri),
                    depth - 1,
                    mountedContainerUris
                )
            } else {
                val extension = FileUtil.getExtension(it.uri).lowercase()
                val filePath = it.uri.toString()

                if (externalContentExtensions.contains(extension) &&
                    mountedContainerUris.add(filePath)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(filePath)
                }

                if (Game.extensions.contains(extension)) {
                    val game = getGame(it.uri, true, false)
                    if (game != null) {
                        games.add(game)
                    }
                }
            }
        }
    }

    private fun mountExternalContentDirectories(mountedContainerUris: MutableSet<String>) {
        val uniqueExternalContentDirs = linkedSetOf<String>()
        NativeConfig.getExternalContentDirs().forEach { externalDir ->
            if (externalDir.isNotEmpty()) {
                uniqueExternalContentDirs.add(externalDir)
            }
        }

        for (externalDir in uniqueExternalContentDirs) {
            val externalDirUri = externalDir.toUri()
            if (FileUtil.isTreeUriValid(externalDirUri)) {
                scanContentContainersRecursive(FileUtil.listFiles(externalDirUri), 3) {
                    val containerUri = it.uri.toString()
                    if (mountedContainerUris.add(containerUri)) {
                        NativeLibrary.addFileToFilesystemProvider(containerUri)
                    }
                }
            }
        }
    }

    private fun mountGameFolderContent(gameUri: Uri, mountedContainerUris: MutableSet<String>) {
        if (gameUri.scheme == "content") {
            val parentUri = getParentDocumentUri(gameUri) ?: return
            scanContentContainersRecursive(FileUtil.listFiles(parentUri), 1) {
                val containerUri = it.uri.toString()
                if (mountedContainerUris.add(containerUri)) {
                    NativeLibrary.addGameFolderFileToFilesystemProvider(containerUri)
                }
            }
            return
        }

        val gameFile = File(gameUri.path ?: gameUri.toString())
        val romDirectory = gameFile.parentFile ?: return
        val unitDirectory = if (romDirectory.name.equals("rom", true) ||
            romDirectory.name.equals("roms", true)
        ) romDirectory.parentFile ?: romDirectory else romDirectory
        unitDirectory.walkTopDown().maxDepth(5).filter { it.isFile }.forEach { sibling ->
            val extension = sibling.extension.lowercase()
            if (externalContentExtensions.contains(extension) &&
                mountedContainerUris.add(sibling.absolutePath)
            ) {
                NativeLibrary.addGameFolderFileToFilesystemProvider(sibling.absolutePath)
            }
        }
    }

    private fun getParentDocumentUri(uri: Uri): Uri? {
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val separatorIndex = documentId.lastIndexOf('/')
            if (separatorIndex == -1) {
                null
            } else {
                val parentDocumentId = documentId.substring(0, separatorIndex)
                DocumentsContract.buildDocumentUriUsingTree(uri, parentDocumentId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getGame(
        uri: Uri,
        addedToLibrary: Boolean,
        registerFilesystemProvider: Boolean = true
    ): Game? {
        val filePath = uri.toString()
        if (!GameMetadata.getIsValid(filePath)) {
            return null
        }

        if (registerFilesystemProvider) {
            // Needed to update installed content information
            NativeLibrary.addFileToFilesystemProvider(filePath)
        }

        var name = GameMetadata.getTitle(filePath)

        // If the game's title field is empty, use the filename.
        if (name.isEmpty()) {
            name = FileUtil.getFilename(uri)
        }
        var programId = GameMetadata.getProgramId(filePath)

        // If the game's ID field is empty, use the filename without extension.
        if (programId.isEmpty()) {
            programId = name.substring(0, name.lastIndexOf("."))
        }

        val newGame = Game(
            name,
            filePath,
            programId,
            GameMetadata.getDeveloper(filePath),
            GameMetadata.getVersion(filePath, false),
            GameMetadata.getIsHomebrew(filePath)
        )

        if (addedToLibrary) {
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
