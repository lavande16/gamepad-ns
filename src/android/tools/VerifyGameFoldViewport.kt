// SPDX-FileCopyrightText: Copyright 2026 GameFold Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.tools

import org.yuzu.yuzu_emu.views.GameFoldViewportCalculator
import org.yuzu.yuzu_emu.views.GameFoldViewportCalculator.ViewportSize

fun main() {
    val cases = mapOf(
        (1920 to 1080) to ViewportSize(1920, 1080),
        (2400 to 1080) to ViewportSize(1920, 1080),
        (2560 to 1600) to ViewportSize(2560, 1440),
        (2208 to 1840) to ViewportSize(2208, 1242),
        (1280 to 720) to ViewportSize(1280, 720)
    )
    cases.forEach { (input, expected) ->
        val actual = GameFoldViewportCalculator.calculate(input.first, input.second)
        check(actual == expected) { "$input expected $expected but was $actual" }
        check(actual.width <= input.first && actual.height <= input.second)
    }
    println("GameFold viewport verification passed: ${cases.size} reference sizes")
}
