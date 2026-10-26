// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import org.json.JSONObject
import java.io.File

class GameFoldAssemblyState private constructor(
    private val file: File,
    private val values: MutableMap<String, String>
) {
    fun get(key: String): String? = values[key]

    fun put(key: String, value: String) {
        values[key] = value
        save()
    }

    fun save() {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        values.toSortedMap().forEach { (key, value) -> json.put(key, value) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString(2))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        fun load(context: Context): GameFoldAssemblyState {
            val file = File(context.filesDir, "gamefold/assembly-state.json")
            val values = mutableMapOf<String, String>()
            if (file.isFile) {
                runCatching {
                    val json = JSONObject(file.readText())
                    json.keys().forEach { key -> values[key] = json.getString(key) }
                }
            }
            return GameFoldAssemblyState(file, values)
        }
    }
}
