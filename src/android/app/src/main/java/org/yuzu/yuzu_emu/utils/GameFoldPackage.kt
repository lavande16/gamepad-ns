// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.net.Uri
import android.os.Environment
import java.io.File

object GameFoldPackage {
    val root: File
        get() = File(Environment.getExternalStorageDirectory(), GamePadNsProductContract.EXTERNAL_ROOT_NAME)

    val systemDirectory: File get() = childDirectory(root, "system") ?: File(root, "system")
    val driversDirectory: File get() = childDirectory(root, "drivers") ?: File(root, "drivers")
    val gamesDirectory: File get() = childDirectory(root, "games") ?: File(root, "games")

    fun childDirectory(parent: File, vararg acceptedNames: String): File? {
        if (!parent.isDirectory) return null
        val names = acceptedNames.map { it.lowercase() }.toSet()
        return parent.listFiles()?.firstOrNull {
            it.isDirectory && it.name.lowercase() in names
        }
    }

    fun filesWithExtensions(parent: File?, extensions: Set<String>): List<File> {
        if (parent?.isDirectory != true) return emptyList()
        return parent.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .sortedBy { it.absolutePath.lowercase() }
            .toList()
    }

    /**
     * Returns the persistent shader-cache root belonging to a GamePad NS game.
     * The renderer appends the title ID below this directory.
     */
    fun shaderCacheDirectoryForGamePath(gamePath: String): File? {
        val rawPath = if (gamePath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(gamePath).path
        } else {
            gamePath
        } ?: return null
        val gameFile = File(rawPath)
        val parent = gameFile.parentFile ?: return null
        if (parent.name.equals("rom", true) || parent.name.equals("roms", true)) {
            val gameDirectory = parent.parentFile ?: return null
            if (gameDirectory.parentFile?.name.equals("games", true)) {
                return File(gameDirectory, "shadercache")
            }
        }
        if (parent.parentFile?.name.equals("games", true)) {
            return File(parent, "shadercache")
        }
        return null
    }

    fun runtimeSaveDirectoryForGamePath(gamePath: String): File? {
        val gameFile = gamePathToFile(gamePath) ?: return null
        val parent = gameFile.parentFile ?: return null
        val gameDirectory = when {
            parent.name.equals("rom", true) || parent.name.equals("roms", true) ->
                parent.parentFile
            parent.parentFile?.name.equals("games", true) -> parent
            else -> null
        } ?: return null
        if (!gameDirectory.parentFile?.name.equals("games", true)) return null
        return File(gameDirectory, "saves/runtime")
    }

    /**
     * Checks all user-id directories in the portable runtime-save tree. This is
     * needed before native startup migrates an old random profile to GamePad's
     * fixed profile: app-data reset can leave the old profile on shared storage.
     */
    fun hasRuntimeSaveForTitle(gameDirectory: File, titleId: String): Boolean {
        val usersDirectory = File(
            gameDirectory,
            "saves/runtime/user/save/0000000000000000"
        )
        return usersDirectory.listFiles()?.any { userDirectory ->
            if (!userDirectory.isDirectory) return@any false
            val titleDirectory = File(userDirectory, titleId)
            hasPersistedRuntimeSave(titleDirectory)
        } == true
    }

    fun hasPersistedRuntimeSave(directory: File): Boolean =
        directory.isDirectory && directory.walkTopDown().any {
            it.isFile && !it.name.equals("option.sav", true)
        }

    private fun gamePathToFile(gamePath: String): File? {
        val rawPath = if (gamePath.startsWith("file://", ignoreCase = true)) {
            Uri.parse(gamePath).path
        } else {
            gamePath
        } ?: return null
        return File(rawPath)
    }

    fun inventorySignature(root: File = this.root, includeLossless: Boolean = false): String {
        if (!root.isDirectory) return "missing"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter {
                it.isFile && it.name != ".DS_Store" &&
                    (includeLossless || !(it.name.equals("Lossless.dll", true) &&
                        it.parentFile?.name.equals("system", true))) &&
                    !it.relativeTo(root).path.split(File.separatorChar)
                        .any { component -> component.equals("shadercache", true) } &&
                    !it.relativeTo(root).path.split(File.separatorChar)
                        .windowed(2)
                        .any { components ->
                            components[0].equals("saves", true) &&
                                components[1].equals("runtime", true)
                        }
            }
            .sortedBy { it.relativeTo(root).path.lowercase() }
            .forEach { file ->
                val line = "${file.relativeTo(root).path}\u0000${file.length()}\u0000${file.lastModified()}\n"
                digest.update(line.toByteArray())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
