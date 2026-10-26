// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <string>
#include <utility>

namespace FrontendCommon {
// Persisted INI markers make migration survive reload/export. Each title is migrated
// when loaded, including newly created configs; subsequent explicit LSFG overrides win.
// The caller supplies only a verified available T28 archive, or an empty path.
template <typename Ini>
bool MigrateGamePadLsfg(Ini& ini, bool global, const std::string& t28_path) {
    bool changed = false;
    if (!ini.GetBoolValue("GamePad", "lsfg_fixed_2x_v1", false)) {
        for (const auto& [key, value] : {std::pair{"frame_gen", "true"},
                                       std::pair{"frame_gen_multiplier", "2"},
                                       std::pair{"frame_gen_target_rate", "0"}}) {
            ini.SetValue("Renderer", key, value);
            ini.SetBoolValue("Renderer", (std::string(key) + "\\default").c_str(), false);
            if (!global) {
                ini.SetBoolValue("Renderer", (std::string(key) + "\\use_global").c_str(), true);
            }
        }
        ini.SetBoolValue("GamePad", "lsfg_fixed_2x_v1", true);
        changed = true;
    }
    if (!ini.GetBoolValue("GamePad", "global_t28_v1", false) &&
        (!global || !t28_path.empty())) {
        if (global) {
            ini.SetValue("GpuDriver", "driver_path", t28_path.c_str());
            ini.SetBoolValue("GpuDriver", "driver_path\\default", false);
        } else {
            ini.SetBoolValue("GpuDriver", "driver_path\\use_global", true);
        }
        ini.SetBoolValue("GamePad", "global_t28_v1", true);
        changed = true;
    }
    return changed;
}
} // namespace FrontendCommon
