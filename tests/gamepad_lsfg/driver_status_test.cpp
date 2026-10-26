#include <cassert>
#include <iostream>
#include "video_core/frame_gen/runtime_status.h"
int main() {
    using namespace VideoCore::FrameGen;
    assert(ClassifyDriverSupport(false, true) == RuntimeStatus::MissingMemoryModel);
    assert(ClassifyDriverSupport(true, false) == RuntimeStatus::MissingNullDescriptor);
    assert(ClassifyDriverSupport(true, true) == RuntimeStatus::Unchecked);
    SetRuntimeStatus(RuntimeStatus::ShadersReady);
    assert(GetRuntimeStatus() == RuntimeStatus::ShadersReady);
    SetRuntimeStatus(RuntimeStatus::Unchecked);
    assert(GetRuntimeStatus() == RuntimeStatus::Unchecked);
    std::cout << "PASS 5 selected-driver status assertions\n";
}
