#define SI_NO_CONVERSION 1
#include <SimpleIni.h>
#include <cassert>
#include <iostream>
#include <string>
#include "frontend_common/gamepad_lsfg_migration.h"
int main() {
    CSimpleIniA ini;
    ini.LoadData("[Renderer]\nframe_gen=false\nframe_gen\\default=false\nframe_gen_multiplier=4\nframe_gen_target_rate=120\nresolution_setup=3\n[GpuDriver]\ndriver_path=T30.zip\ndriver_path\\use_global=false\n[Controls]\nplayer_0_button_a=custom\n");
    assert(FrontendCommon::MigrateGamePadLsfg(ini, false, ""));
    assert(std::string(ini.GetValue("Renderer", "frame_gen")) == "true");
    assert(std::string(ini.GetValue("Renderer", "frame_gen_multiplier")) == "2");
    assert(std::string(ini.GetValue("Renderer", "frame_gen_target_rate")) == "0");
    assert(ini.GetBoolValue("Renderer", "frame_gen\\use_global"));
    assert(ini.GetBoolValue("GpuDriver", "driver_path\\use_global"));
    assert(std::string(ini.GetValue("Renderer", "resolution_setup")) == "3");
    assert(std::string(ini.GetValue("Controls", "player_0_button_a")) == "custom");
    ini.SetValue("Renderer", "frame_gen", "false");
    ini.SetValue("Renderer", "frame_gen\\use_global", "false");
    ini.SetValue("Renderer", "frame_gen_multiplier", "3");
    std::string serialized;
    ini.Save(serialized);
    CSimpleIniA restarted;
    restarted.LoadData(serialized.c_str());
    assert(!FrontendCommon::MigrateGamePadLsfg(restarted, false, ""));
    assert(!restarted.GetBoolValue("Renderer", "frame_gen"));
    assert(!restarted.GetBoolValue("Renderer", "frame_gen\\use_global"));
    assert(std::string(restarted.GetValue("Renderer", "frame_gen_multiplier")) == "3");
    CSimpleIniA global;
    assert(FrontendCommon::MigrateGamePadLsfg(global, true, "/user/gpu_drivers/T28.zip"));
    assert(global.GetBoolValue("Renderer", "frame_gen"));
    assert(!global.GetBoolValue("Renderer", "frame_gen\\default"));
    assert(std::string(global.GetValue("GpuDriver", "driver_path")) == "/user/gpu_drivers/T28.zip");
    assert(!FrontendCommon::MigrateGamePadLsfg(global, true, "/user/gpu_drivers/T28.zip"));
    CSimpleIniA fresh;
    assert(FrontendCommon::MigrateGamePadLsfg(fresh, false, ""));
    assert(fresh.GetBoolValue("Renderer", "frame_gen\\use_global"));
    assert(std::string(fresh.GetValue("Renderer", "frame_gen_multiplier")) == "2");
    assert(fresh.GetBoolValue("GpuDriver", "driver_path\\use_global"));
    CSimpleIniA unavailable;
    unavailable.LoadData("[GpuDriver]\ndriver_path=working.zip\n");
    FrontendCommon::MigrateGamePadLsfg(unavailable, true, "");
    assert(std::string(unavailable.GetValue("GpuDriver", "driver_path")) == "working.zip");
    assert(FrontendCommon::MigrateGamePadLsfg(unavailable, true, "/later/T28.zip"));
    assert(std::string(unavailable.GetValue("GpuDriver", "driver_path")) == "/later/T28.zip");
    std::cout << "PASS 24 settings-migration assertions\n";
}
