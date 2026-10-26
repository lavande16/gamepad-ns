# 当前 GamePad NS 已安装基线

- 最新正式基线：**V1.8 / 10030**，包名 `com.gamepad.gamepad`。
- 状态：2026-10-18 用户确认“我验证了一下，没啥问题。帮我标个基线吧”；后续开发以此版为起点。
- 当前源码：`gamepad-ns-eden-lsfg-candidate`，分支 `gamepad-ns-eden-lsfg-upgrade`。
- 源码内容提交：`0f2249a7026f9ea2e90a6891125b3537a9f53491`；原构建来自 `1cf4a2355f39bf59b7bd7f429cae7576d334f5de` 加 `source-changes.patch`，未重新构建 APK。
- EDEN 来源：`1dcc5745918761f5a554fec981b4d144034f6201`。
- GitHub 备份基线元数据：`../baseline/`。
- APK 作为 GitHub Release asset 提供，不放入源码 Git 历史。
- APK SHA-256：`d3e621a2c060401cfa6cae6b96bf25b4293119be296551bb1eaba118e0031fca`。
- 正式标签：`gamepad-ns-v1.8-10030-baseline`，详情见 `CURRENT_GAMEPAD_NS_BASELINE.md`。
- V1.7 / 10029、V1.6 / 10027 及更早归档与标签保留不变，可供回退。

V1.8 的主页开关修改整机系统逻辑分辨率：16:9 为 3840×2160，系统自然竖向坐标为 2160×3840；再次点击恢复原物理横屏 2304×1440。游戏窗口跟随系统尺寸，物理面板、密度和内部渲染倍率不变。4K 手柄/HOME 绘制与触控同步按 5/3 缩放，手柄纵向偏移 -120 像素。旧 2304×1296 模式及 -72 像素偏移、原全屏、旧 1080p、IPS/LSFG/T28 与存档逻辑保留。

已在 vivo Pad `iPA2693 / 403G8G00BM611V0` 安装并逐字节核对 APK；安装前后 38 个 runtime 存档文件一致。7 个配置中 6 个逐字节一致，唯一变化为用户手动开启 NFS bigfont，已确认并保留，其余配置不变。12 项 Android 测试与 10 项 IPS 宿主回归通过。整机切换、恢复、离开应用后保持已实测；NFS 窗口和 SurfaceView 为 3840×2160，主菜单显示 60 帧，60FPS IPS Applied、帧生成关闭、手柄完整可见，HOME 触控返回主页已验证。

用户已验收并授权固化同一份 APK。本次提升时平板未连接，沿用此前设备回读证据；不声称新增长期比赛帧率、完整控制器或电视投屏测试。不重新打包、安装或修改设备数据。

下一个实际产生的新 APK 的 versionCode 必须为 **10031**，同时更新 Gradle 与 GamePadNsProductContract。
