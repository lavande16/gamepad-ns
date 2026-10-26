// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <atomic>
namespace VideoCore::FrameGen {
// Shader readiness is not proof that generated frames reached the display.
enum class RuntimeStatus {
    Unchecked = 0,
    ShadersReady = 1,
    MissingMemoryModel = 2,
    MissingNullDescriptor = 3,
    ShaderPreparationFailed = 4,
    MissingStorageImage = 5,
};
inline std::atomic<RuntimeStatus> runtime_status{RuntimeStatus::Unchecked};
inline void SetRuntimeStatus(RuntimeStatus status) { runtime_status.store(status); }
inline RuntimeStatus GetRuntimeStatus() { return runtime_status.load(); }
constexpr RuntimeStatus ClassifyDriverSupport(bool memory_model, bool null_descriptor) {
    if (!memory_model) return RuntimeStatus::MissingMemoryModel;
    if (!null_descriptor) return RuntimeStatus::MissingNullDescriptor;
    return RuntimeStatus::Unchecked;
}
} // namespace VideoCore::FrameGen
