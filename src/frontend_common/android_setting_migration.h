// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string_view>

namespace FrontendCommon {
// Oboe occupied 4 before the SDL3 runtime upgrade. Normalize before enum clamping,
// which would otherwise turn it into Null (3). Keep explicit mute and other settings.
constexpr std::string_view MigrateAndroidSetting(std::string_view key, std::string_view value) {
    return key == "output_engine" && value == "4" ? "0" : value;
}
} // namespace FrontendCommon
