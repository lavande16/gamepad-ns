// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GameDir
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.OnErrorAction

data class GameFoldAssemblyUpdate(
    val progress: Int,
    val title: String,
    val detail: String,
    val completedSteps: Int,
    val totalSteps: Int = GameFoldAssembler.TOTAL_STEPS
)

data class GameFoldAssemblyResult(
    val success: Boolean,
    val summary: String,
    val games: Int = 0,
    val warnings: List<String> = emptyList()
)

sealed interface GameFoldPreflightResult {
    data object Ready : GameFoldPreflightResult
    data object NoGames : GameFoldPreflightResult
    data class MissingStructure(val summary: String) : GameFoldPreflightResult
}

object GameFoldAssembler {
    const val TOTAL_STEPS = 8
    private const val INVENTORY_SCHEMA_VERSION = "3"
    private val romExtensions = setOf("xci", "nsp", "nca", "nro")
    private val contentExtensions = setOf("xci", "nsp")

    fun preflight(root: File = GameFoldPackage.root): GameFoldPreflightResult {
        if (!root.isDirectory) {
            return GameFoldPreflightResult.NoGames
        }

        val gamesDir = GameFoldPackage.childDirectory(root, "games")
            ?: return GameFoldPreflightResult.NoGames
        val directGameCandidate = gamesDir.listFiles()?.any {
            it.isFile && it.extension.lowercase() in romExtensions
        } == true
        val nestedGameCandidate = gamesDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.any { gameDirectory ->
                val directCandidate = gameDirectory.listFiles()?.any {
                    it.isFile && it.extension.lowercase() in romExtensions
                } == true
                val romDirectory = GameFoldPackage.childDirectory(gameDirectory, "rom", "roms")
                directCandidate || GameFoldPackage.filesWithExtensions(
                    romDirectory,
                    romExtensions
                ).isNotEmpty()
            } == true
        if (!directGameCandidate && !nestedGameCandidate) {
            return GameFoldPreflightResult.NoGames
        }

        val missing = buildList {
            if (GameFoldPackage.childDirectory(root, "system") == null) add("system")
            if (GameFoldPackage.childDirectory(root, "drivers") == null) add("drivers")
        }
        if (missing.isNotEmpty()) {
            return GameFoldPreflightResult.MissingStructure(
                "gamefold 目录缺少 ${missing.joinToString("、")}"
            )
        }

        return GameFoldPreflightResult.Ready
    }

    fun needsAssembly(context: Context): Boolean {
        if (hasFlatGameFiles(GameFoldPackage.gamesDirectory)) return true
        val state = GameFoldAssemblyState.load(context)
        if (state.get("inventory_schema") != INVENTORY_SCHEMA_VERSION) return true
        val inventory = GameFoldPackage.inventorySignature()
        if (state.get("inventory") != inventory) {
            // Adopt the prior DLL-inclusive signature without rerunning unrelated assembly.
            if (state.get("inventory") != GameFoldPackage.inventorySignature(includeLossless = true)) {
                return true
            }
            state.put("inventory", inventory)
        }
        if (!NativeLibrary.areKeysPresent() || !NativeLibrary.isFirmwareAvailable()) return true
        val expectedGamesPath = GameFoldPackage.gamesDirectory.absolutePath
        return NativeConfig.getGameDirs().none { it.uriString == expectedGamesPath }
    }

    fun assemble(
        context: Context,
        update: (GameFoldAssemblyUpdate) -> Unit
    ): GameFoldAssemblyResult {
        val root = GameFoldPackage.root
        val state = GameFoldAssemblyState.load(context)
        val warnings = mutableListOf<String>()

        if (!root.isDirectory) {
            return GameFoldAssemblyResult(false, "未找到 ${root.absolutePath}")
        }

        emit(update, 4, "正在扫描素材", root.absolutePath, 0)
        val systemDir = GameFoldPackage.systemDirectory
        val driversDir = GameFoldPackage.driversDirectory
        val gamesDir = GameFoldPackage.gamesDirectory
        if (!systemDir.isDirectory || !driversDir.isDirectory || !gamesDir.isDirectory) {
            return GameFoldAssemblyResult(false, "gamefold 目录缺少 system、drivers 或 games")
        }

        emit(update, 10, "正在导入密钥", "检查 prod.keys", 1)
        val keys = systemDir.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("prod.keys", ignoreCase = true)
        } ?: return GameFoldAssemblyResult(false, "system/prod.keys 不存在")
        val keysFingerprint = sha256(keys)
        if (state.get("keys") != keysFingerprint || !NativeLibrary.areKeysPresent()) {
            val result = NativeLibrary.installKeys(Uri.fromFile(keys).toString(), "keys")
            if (result != 0) return GameFoldAssemblyResult(false, "prod.keys 导入失败，错误码 $result")
            NativeLibrary.reloadKeys()
            state.put("keys", keysFingerprint)
        }

        emit(update, 18, "正在安装系统固件", "验证 Firmware 压缩包", 2)
        val firmware = systemDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.maxByOrNull { it.length() }
            ?: return GameFoldAssemblyResult(false, "system 目录中没有 Firmware ZIP")
        val firmwareFingerprint = sha256(firmware) { read, total ->
            val phase = 18 + ((read.toDouble() / total.coerceAtLeast(1)) * 17).toInt()
            emit(update, phase.coerceIn(18, 35), "正在安装系统固件", "校验 ${firmware.name}", 2)
        }
        if (state.get("firmware") != firmwareFingerprint || !NativeLibrary.isFirmwareAvailable()) {
            installFirmware(context, firmware)
            if (!NativeLibrary.isFirmwareAvailable()) {
                return GameFoldAssemblyResult(false, "Firmware 安装后未通过模拟器校验")
            }
            state.put("firmware", firmwareFingerprint)
        }

        emit(update, 40, "正在装载图形驱动", "检查 T26 与 T28", 3)
        val driverFiles = driversDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        val copiedDrivers = driverFiles.mapNotNull { source ->
            val metadata = GpuDriverHelper.getMetadataFromZip(source)
            if (metadata.name == null || metadata.minApi > Build.VERSION.SDK_INT) {
                warnings += "已跳过无效或不兼容驱动：${source.name}"
                null
            } else {
                val fingerprint = sha256(source)
                val destination = File(GpuDriverHelper.driverStoragePath, source.name)
                if (state.get("driver:${source.name}") != fingerprint || !destination.isFile) {
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination, overwrite = true)
                    state.put("driver:${source.name}", fingerprint)
                }
                destination
            }
        }
        val t26 = copiedDrivers.firstOrNull { it.name.contains("T26", true) }
        val t28 = copiedDrivers.firstOrNull { it.name.contains("T28", true) }
        val preferred = t28 ?: t26
            ?: return GameFoldAssemblyResult(false, "未找到有效的 T28 或 T26 驱动")
        val activatedDriver = when {
            activateDriver(preferred) -> preferred
            t26 != null && preferred != t26 && activateDriver(t26) -> t26
            else -> null
        }
        if (activatedDriver == null) {
            GpuDriverHelper.installDefaultDriver()
            StringSetting.DRIVER_PATH.setString("")
            warnings += "自定义驱动启用失败，已回退系统驱动"
        } else {
            StringSetting.DRIVER_PATH.setString(activatedDriver.absolutePath)
            if (activatedDriver != preferred) warnings += "首选驱动启用失败，已回退 T26"
        }
        NativeConfig.saveGlobalConfig()

        emit(update, 52, "正在识别游戏", "读取外部 ROM 元数据", 4)
        organizeFlatGames(gamesDir, warnings)
        val gameUnits = discoverGames(gamesDir, warnings)
        if (gameUnits.isEmpty()) return GameFoldAssemblyResult(false, "games 目录中没有识别到可启动 ROM")
        val existingDirs = NativeConfig.getGameDirs().filter { it.uriString != gamesDir.absolutePath }.toMutableList()
        existingDirs += GameDir(gamesDir.absolutePath, true)
        NativeConfig.setGameDirs(existingDirs.toTypedArray())

        emit(update, 65, "正在挂载附加内容", "识别整合包、Update 与 DLC", 5)
        NativeLibrary.clearFilesystemProvider()
        gameUnits.forEach { unit ->
            unit.contentFiles.forEach { NativeLibrary.addGameFolderFileToFilesystemProvider(it.absolutePath) }
        }

        emit(update, 73, "正在安装游戏补丁", "纯 60FPS 优先；BOTW/TOTK 的 UltraCam 自动启用并应用 1080p 配置", 6)
        gameUnits.forEach { unit -> installMods(unit, state, warnings) }

        emit(update, 84, "正在导入存档", "仅在素材存档发生变化时导入", 7)
        gameUnits.forEach { unit -> importSaves(context, unit, state, warnings) }

        emit(update, 94, "正在刷新游戏库", "保存装配结果", 8)
        NativeConfig.saveGlobalConfig()
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(Settings.PREF_FIRST_APP_LAUNCH, false)
        }
        state.put("inventory", GameFoldPackage.inventorySignature(root))
        // Version the material-only inventory so existing installs re-run once
        // when assembly behavior changes, including UltraCam defaults.
        state.put("inventory_schema", INVENTORY_SCHEMA_VERSION)
        state.put("completed_at", System.currentTimeMillis().toString())
        emit(update, 100, "装配完成", "已识别 ${gameUnits.size} 款游戏", 8)
        return GameFoldAssemblyResult(true, "自动装配完成", gameUnits.size, warnings)
    }

    private data class GameUnit(
        val materialDirectory: File?,
        val sourceName: String,
        val game: Game,
        val contentFiles: List<File>
    )

    private fun hasFlatGameFiles(gamesDir: File): Boolean = gamesDir.listFiles()?.any {
        it.isFile && it.extension.lowercase() in romExtensions
    } == true

    /**
     * Give a ROM placed directly under games/ its own persistent game directory.
     * This keeps shadercache, mods, saves and ROM content in one portable unit.
     */
    private fun organizeFlatGames(gamesDir: File, warnings: MutableList<String>) {
        val flatGames = gamesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in romExtensions }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        flatGames.forEach { source ->
            val game = GameHelper.getGame(Uri.parse(source.absolutePath), false)
            if (game == null) {
                warnings += "${source.name}：ROM 无法解析，未自动整理"
                return@forEach
            }

            val title = game.title.trim().ifBlank { source.nameWithoutExtension }
                .removeSuffixIgnoreCase(".${source.extension}")
            val safeTitle = title
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
                .trim()
                .trimEnd('.')
                .ifBlank { source.nameWithoutExtension }
            val gameDirectory = uniqueGameDirectory(gamesDir, safeTitle, game.programIdHex)
            val romDirectory = File(gameDirectory, "rom")
            if (!romDirectory.exists() && !romDirectory.mkdirs()) {
                warnings += "${source.name}：无法创建 ${romDirectory.absolutePath}，未移动"
                return@forEach
            }

            val destination = File(romDirectory, source.name)
            if (destination.exists()) {
                warnings += "${source.name}：目标文件已存在，未移动"
                return@forEach
            }
            if (!source.renameTo(destination)) {
                warnings += "${source.name}：无法移动到 ${destination.absolutePath}"
            }
        }
    }

    private fun uniqueGameDirectory(gamesDir: File, baseName: String, programIdHex: String): File {
        val normalized = baseName.take(120)
        val preferred = File(gamesDir, normalized)
        if (!preferred.exists()) return preferred

        val idSuffix = programIdHex.takeIf { it != "0" }?.let { "-$it" }.orEmpty()
        val withId = if (idSuffix.isEmpty()) preferred else File(gamesDir, normalized + idSuffix)
        if (!withId.exists()) return withId

        var index = 2
        while (true) {
            val candidate = File(gamesDir, "$normalized-$index")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, true)) dropLast(suffix.length) else this

    private fun discoverGames(gamesDir: File, warnings: MutableList<String>): List<GameUnit> {
        val nestedUnits = gamesDir.listFiles()?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { directory ->
                val romDir = GameFoldPackage.childDirectory(directory, "rom", "roms")
                val directRoms = directory.listFiles()?.filter {
                    it.isFile && it.extension.lowercase() in romExtensions
                }.orEmpty()
                val roms = (directRoms + GameFoldPackage.filesWithExtensions(romDir, romExtensions))
                    .distinctBy { it.canonicalPath }
                    .sortedBy { it.name.lowercase() }
                if (roms.isEmpty()) {
                    warnings += "${directory.name}：没有找到 ROM"
                    return@mapNotNull null
                }
                if (roms.size > 1) warnings += "${directory.name}：存在多个 ROM，已选择第一个可启动文件"
                val selected = roms.firstNotNullOfOrNull { file ->
                    GameHelper.getGame(Uri.parse(file.absolutePath), false)
                }
                if (selected == null) {
                    warnings += "${directory.name}：ROM 无法解析"
                    return@mapNotNull null
                }
                val externalContent = buildList {
                    addAll(roms.filter { it.extension.lowercase() in contentExtensions })
                    listOf("updates", "update", "dlc").forEach { name ->
                        addAll(GameFoldPackage.filesWithExtensions(
                            GameFoldPackage.childDirectory(directory, name), contentExtensions
                        ))
                    }
                }.distinctBy { it.absolutePath }
                GameUnit(directory, directory.name, selected, externalContent)
            }.orEmpty()

        val directUnits = gamesDir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in romExtensions
        }?.sortedBy { it.name.lowercase() }?.mapNotNull { file ->
            val game = GameHelper.getGame(Uri.parse(file.absolutePath), false)
            if (game == null) {
                warnings += "${file.name}：ROM 无法解析"
                return@mapNotNull null
            }
            val contentFiles = if (file.extension.lowercase() in contentExtensions) {
                listOf(file)
            } else {
                emptyList()
            }
            GameUnit(null, file.name, game, contentFiles)
        }.orEmpty()

        return nestedUnits + directUnits
    }

    private fun installFirmware(context: Context, source: File) {
        val destination = File(NativeConfig.getNandDir(), "system/Contents/registered")
        val staging = File(context.cacheDir, "gamefold-firmware-staging")
        val backup = File(context.cacheDir, "gamefold-firmware-backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        FileUtil.unzipToInternalStorage(source.absolutePath, staging)
        val files = staging.walkTopDown().filter { it.isFile }.toList()
        require(files.isNotEmpty() && files.all { it.extension.equals("nca", true) }) {
            "Firmware ZIP 必须只包含 NCA 文件"
        }
        if (destination.exists()) destination.copyRecursively(backup, overwrite = true)
        try {
            destination.deleteRecursively()
            staging.copyRecursively(destination, overwrite = true)
            NativeLibrary.initializeSystem(true)
            check(NativeLibrary.isFirmwareAvailable()) {
                "Firmware 安装后未通过可用性校验"
            }
        } catch (error: Exception) {
            destination.deleteRecursively()
            if (backup.exists()) backup.copyRecursively(destination, overwrite = true)
            NativeLibrary.initializeSystem(true)
            throw error
        } finally {
            staging.deleteRecursively()
            backup.deleteRecursively()
        }
    }

    private fun activateDriver(driver: File): Boolean = runCatching {
        GpuDriverHelper.installCustomDriver(driver)
    }.getOrDefault(false)

    private fun installMods(
        unit: GameUnit,
        state: GameFoldAssemblyState,
        warnings: MutableList<String>
    ) {
        val materialDirectory = unit.materialDirectory ?: return
        val modsDir = GameFoldPackage.childDirectory(materialDirectory, "mods", "mod") ?: return
        val mods = modsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
        val disabled = NativeConfig.getDisabledAddons(unit.game.programId).toMutableSet()
        mods.forEach { source ->
            val valid = source.listFiles()?.any { it.isDirectory && it.name.lowercase() in AddonUtil.validAddonDirectories } == true
            if (!valid) {
                warnings += "${unit.sourceName}/${source.name}：MOD 目录结构无效"
                return@forEach
            }
            val fingerprint = directoryFingerprint(source)
            val destination = File(unit.game.addonDir, source.name)
            if (state.get("mod:${unit.game.programIdHex}:${source.name}") != fingerprint || !destination.isDirectory) {
                replaceDirectory(source, destination)
                state.put("mod:${unit.game.programIdHex}:${source.name}", fingerprint)
            }
            if (source.name.equals("UltraCam", true)) {
                if (UltraCamConfiguration.supports(unit.game.programIdHex)) {
                    disabled.removeAll { it.equals(source.name, ignoreCase = true) }
                    val sdmcDirectory = NativeConfig.getSdmcDir()
                        .takeIf { it.isNotBlank() }
                        ?.let(::File)
                    runCatching {
                        UltraCamConfiguration.apply(
                            unit.game.programIdHex,
                            destination,
                            sdmcDirectory
                        )
                    }.onSuccess { result ->
                        if (result?.missingKeys?.isNotEmpty() == true) {
                            warnings += "${unit.sourceName}/UltraCam：配置缺少 ${result.missingKeys.joinToString("、")}，已保留原值"
                        }
                    }.onFailure { error ->
                        warnings += "${unit.sourceName}/UltraCam：默认配置写入失败（${error.message ?: "未知错误"}）"
                    }
                } else {
                    disabled += source.name
                }
            }
            if (source.name.equals("60FPS", true)) disabled.removeAll {
                it.equals(source.name, ignoreCase = true)
            }
        }
        NativeConfig.setDisabledAddons(unit.game.programId, disabled.toTypedArray())
    }

    private fun importSaves(
        context: Context,
        unit: GameUnit,
        state: GameFoldAssemblyState,
        warnings: MutableList<String>
    ) {
        val materialDirectory = unit.materialDirectory ?: return
        val savesDir = GameFoldPackage.childDirectory(materialDirectory, "saves", "save") ?: return
        val archives = savesDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        archives.forEach { archive ->
            val fingerprint = sha256(archive)
            val key = "save:${unit.game.programIdHex}:${archive.name}"
            if (state.get(key) == fingerprint) return@forEach
            val staging = File(context.cacheDir, "gamefold-save-${unit.game.programIdHex}")
            staging.deleteRecursively()
            FileUtil.unzipToInternalStorage(archive.absolutePath, staging)
            val sourceSave = staging.walkTopDown().firstOrNull {
                it.isDirectory && it.name.equals(unit.game.programIdHex, true)
            }
            if (sourceSave == null) {
                warnings += "${unit.sourceName}：存档 ZIP 中没有 ${unit.game.programIdHex}"
                staging.deleteRecursively()
                return@forEach
            }
            val destination = File(unit.game.saveDir)
            val hasPersistentRuntimeSave = GameFoldPackage.hasPersistedRuntimeSave(destination)
            val hasLegacyRuntimeSave = GameFoldPackage.hasRuntimeSaveForTitle(
                materialDirectory,
                unit.game.programIdHex
            )
            if (hasPersistentRuntimeSave || hasLegacyRuntimeSave) {
                // ZIP files in the GameFold package are seed/import material. They must
                // never replace a live save after an app-data reset, because the assembly
                // state file is private app data and is reset together with the app. The
                // legacy scan also protects saves that still use the old random profile
                // while native startup is preparing the fixed GamePad profile.
                warnings += "${unit.sourceName}：已有运行时存档，跳过 ZIP 自动覆盖"
                staging.deleteRecursively()
                return@forEach
            }
            val replacement = File(destination.parentFile, "${destination.name}.gamefold-new")
            replacement.deleteRecursively()
            sourceSave.copyRecursively(replacement, overwrite = true)
            destination.deleteRecursively()
            if (!replacement.renameTo(destination)) {
                replacement.copyRecursively(destination, overwrite = true)
                replacement.deleteRecursively()
            }
            staging.deleteRecursively()
            state.put(key, fingerprint)
        }
    }

    private fun replaceDirectory(source: File, destination: File) {
        val staging = File(destination.parentFile, "${destination.name}.gamefold-new")
        staging.deleteRecursively()
        source.copyRecursively(staging, overwrite = true) { file, error ->
            if (file.name == ".DS_Store") OnErrorAction.SKIP else OnErrorAction.TERMINATE
        }
        destination.deleteRecursively()
        if (!staging.renameTo(destination)) {
            staging.copyRecursively(destination, overwrite = true)
            staging.deleteRecursively()
        }
    }

    private fun directoryFingerprint(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown().filter { it.isFile && it.name != ".DS_Store" }
            .sortedBy { it.relativeTo(directory).path.lowercase() }
            .forEach { file ->
                digest.update(file.relativeTo(directory).path.toByteArray())
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File, progress: ((Long, Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                read += count
                progress?.invoke(read, file.length())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun emit(
        callback: (GameFoldAssemblyUpdate) -> Unit,
        progress: Int,
        title: String,
        detail: String,
        completedSteps: Int
    ) = callback(GameFoldAssemblyUpdate(progress, title, detail, completedSteps))
}
