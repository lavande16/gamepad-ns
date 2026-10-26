# GameFold NS Branding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 2.9 R2 主线候选的桌面品牌、首页字标和关于页社媒图标更新到用户指定状态，并安装到 vivo 手机验收。

**Architecture:** 只改 Android 资源入口和现有布局属性，不改包名、运行时逻辑、链接或导航。用一个独立资源策略脚本验证源码状态，再以 release 构建和设备包体证据完成交付。

**Tech Stack:** Android Gradle Plugin、Kotlin/XML、Android resources、vivo ADB 安装流程。

## Global Constraints

- 基线：GameFold 2.9 R2，包名 `com.switch.fold.baseline.lite`。
- 已冻结目录和 APK不得覆盖。
- 应用名精确为 `GameFold NS`；首页字标精确为 `GAMEFOLD NS`。
- 两个社媒按钮继续使用现有小红书和 Dribbble 资源与链接。

---

### Task 1: Resource policy test

**Files:**
- Create: `tools/verify_gamefold_ns_branding.sh`

- [ ] 写入会检查应用名、首页横竖布局、launcher 图标哈希和社媒按钮可见性的脚本。
- [ ] 运行脚本，确认它因旧品牌和隐藏社媒容器而失败。

### Task 2: Minimal resource changes

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/fragment_games.xml`
- Modify: `app/src/main/res/layout-land/fragment_games.xml`
- Modify: `app/src/main/res/layout/fragment_about.xml`
- Create: `app/src/main/res/drawable-nodpi/gamefold_ns_launcher_icon.png`

- [ ] 把主线 manifest label 和生成的应用名资源改为 `GameFold NS`。
- [ ] 将首页两套布局显式绑定 `GAMEFOLD NS` 字符串。
- [ ] 指向与附件 SHA-256 一致的新 launcher PNG。
- [ ] 仅删除社媒容器的 `gone` 可见性覆盖。
- [ ] 重跑策略脚本，确认通过。

### Task 3: Build, archive, install, verify

**Files:**
- Create: `releases/candidates/GameFold-NS-2.9-Branding-RC1-<versionCode>/`

- [ ] 运行资源合并和 `:app:assembleMainlineRelease`。
- [ ] 用 `aapt` 验证包名、label、versionName、versionCode，用 `apksigner` 验证签名并计算 SHA-256。
- [ ] 复制到独立候选目录并记录元数据，不覆盖 2.9 R2。
- [ ] 使用 vivo 安装脚本原位更新，保留应用数据。
- [ ] 核对设备 `dumpsys package` 身份、安装时间，并拉取已安装 APK 比较哈希。
