# GameFold 16:9 像素版模拟器 AutoPack V1

## 基线

- 上游源码提交：`87eca4ff665bf46ef770cc6b812a28126a18d65a`
- 固定分支：`baseline/gamefold-16x9-pixel-emulator-v1`
- 不可变标签：`gamefold-16x9-pixel-emulator-baseline-v1`
- 基线 APK SHA-256：`350ef8736481fdd4a47142631ff442eaf2b37e62f0a9d8ee8ae94ee7d7c6a255`
- 本版本开发分支：`feature/gamefold-auto-assembly-v1`

本版本只修改模拟器线，不包含手柄 APK。

## 素材入口

应用只扫描共享存储根目录的固定素材包：

`/sdcard/gamefold`（Android 实际等价路径为 `/storage/emulated/0/gamefold`）

首次启动会申请“管理所有文件”权限。应用的用户数据、配置和模拟器目录仍沿用原路径；`gamefold` 只是便于准备、打包和分享的只读素材入口。

## 自动装配规则

| 素材 | 处理方式 |
| --- | --- |
| `system/prod.keys` | 校验后导入模拟器原有 keys 目录 |
| `system/*.zip` | 作为 Firmware 解压到模拟器原有 NAND 目录，失败时恢复安装前版本 |
| `drivers/*.zip` | T26、T28 都复制到原有驱动目录；SM8750/SM8850 选择 T28，其余 SoC 选择 T26；失败回退 T26 或系统驱动 |
| `games/<游戏>/rom` 或 `roms` | ROM 不复制，直接从 `/sdcard/gamefold` 读取并加入游戏库 |
| `games/<游戏>/updates`、`update`、`dlc` | 不复制，作为外部内容直接挂载；整合 XCI 也会交给内容提供器识别 |
| `games/<游戏>/mods` 或 `mod` | 复制到游戏原有 Add-on 目录；`60FPS` 默认启用；BOTW/TOTK 存在 `UltraCam` 时自动启用，并写入各自已验证的 1080p / 60FPS 配置 |
| `games/<游戏>/saves` 或 `save` | 首次或源 ZIP 内容变化时导入；覆盖前备份现有存档；源文件未变化时绝不重复覆盖 |

目录名匹配不区分大小写。游戏库只把 `rom`/`roms` 下的可启动文件显示为游戏，避免把 Update、DLC 重复显示成游戏。

UltraCam 默认配置仅对两个已识别 Title ID 生效：BOTW 写入 `MaxFramerate=60`、菜单 60 FPS、1080p、`RenderDistance=5000`、关闭质量改进并保持 FOV 50；TOTK 写入 1080p、阴影 512、`RenderDistance=5000`、60 FPS、动画 30 FPS、FSR/FXAA/动态帧率开启，并开启手持分辨率覆盖。配置器只更新对应版本 INI 中已有的可靠字段；TOTK 当前素材包没有 INI 时，会在模拟器 SDMC 的 `UltraCam/TOTK/Config/maxlastbreath.ini` 生成这组字段。

## 首次启动体验

启动页以 8 个阶段展示装配进度：扫描素材、导入密钥、安装固件、装载驱动、识别游戏、挂载附加内容、安装补丁、导入存档/刷新游戏库。完成后自动进入游戏库；装配失败会停留在当前页并显示原因与重试入口。

后续启动会比较素材清单签名和装配状态。素材未变化且核心环境完整时直接进入游戏库，不重复执行耗时装配。

## 状态与备份

- 装配状态：应用私有目录 `files/gamefold/assembly-state.json`
- 覆盖前存档备份：应用私有目录 `files/gamefold/backups/saves/<TitleId>/<时间戳>`
- 素材清单签名只记录相对路径、大小和修改时间，不读取大型 ROM 全量哈希。
