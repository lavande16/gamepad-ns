// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/settings.h"
#include "common/logging.h"
#include "video_core/frame_gen/runtime_status.h"
#include "video_core/frame_gen/lossless_dll.h"
#include "video_core/renderer_vulkan/present/lsfg_shaders.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

LsfgShaders::LsfgShaders(const Device& device) {
    using namespace VideoCore::FrameGen;
    const auto support = ClassifyDriverSupport(device.IsVulkanMemoryModelSupported(),
                                              device.HasNullDescriptor());
    if (support != RuntimeStatus::Unchecked) {
        SetRuntimeStatus(support);
        LOG_WARNING(Render_Vulkan, "LSFG unavailable on selected device: {}",
                    support == RuntimeStatus::MissingMemoryModel ? "Vulkan memory model missing"
                                                               : "null descriptors missing");
        return;
    }

    const bool allow_fp16 = device.IsFloat16Supported();
    const bool prefer_fp16 = allow_fp16 && Settings::values.frame_gen_fp16.GetValue();

    VideoCore::FrameGen::ShaderModules code;
    const auto result = LoadShaderModules(code, allow_fp16, prefer_fp16);
    if (result != LosslessStatus::Ok) {
        SetRuntimeStatus(RuntimeStatus::ShaderPreparationFailed);
        LOG_WARNING(Render_Vulkan, "LSFG shader preparation failed: {}", static_cast<int>(result));
        return;
    }

    try {
        for (const auto& [id, words] : code) {
            modules.emplace(id, CreateWrappedShaderModule(device, words));
        }
    } catch (const vk::Exception& error) {
        modules.clear();
        SetRuntimeStatus(RuntimeStatus::ShaderPreparationFailed);
        LOG_WARNING(Render_Vulkan, "Selected driver rejected LSFG shaders: {}", error.what());
        return;
    }
    valid = true;
    SetRuntimeStatus(RuntimeStatus::ShadersReady);
}

VkShaderModule LsfgShaders::Get(u32 shader_id) const {
    const auto hit = modules.find(shader_id);
    return hit == modules.end() ? VK_NULL_HANDLE : *hit->second;
}

} // namespace Vulkan
