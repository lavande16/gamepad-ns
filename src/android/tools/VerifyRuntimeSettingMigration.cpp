// SPDX-FileCopyrightText: Copyright 2026 GamePad Project
// SPDX-License-Identifier: GPL-3.0-or-later
#include "frontend_common/android_setting_migration.h"

using FrontendCommon::MigrateAndroidSetting;
static_assert(MigrateAndroidSetting("output_engine", "4") == "0");
static_assert(MigrateAndroidSetting("output_engine", "3") == "3");
static_assert(MigrateAndroidSetting("output_engine", "2") == "2");
static_assert(MigrateAndroidSetting("output_engine", "1") == "1");
static_assert(MigrateAndroidSetting("output_engine", "0") == "0");
static_assert(MigrateAndroidSetting("volume", "4") == "4");
static_assert(MigrateAndroidSetting("output_engine", "") == "");
int main() {}
