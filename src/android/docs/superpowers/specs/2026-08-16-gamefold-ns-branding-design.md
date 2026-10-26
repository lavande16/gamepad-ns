# GameFold NS 品牌调整设计

## 范围

- 从已验证的 GameFold 2.9 R2 主线源码继续，保持包名 `com.switch.fold.baseline.lite` 和现有数据不变。
- 桌面应用名改为 `GameFold NS`，launcher 图标使用用户提供的 `icon-512.png` 原图。
- 首页横屏、竖屏左上角字标改为 `GAMEFOLD NS`，其余布局和交互不变。
- 关于页恢复现有小红书和 Dribbble 两个社媒按钮，保留现有图标、链接、焦点顺序和点击逻辑。
- 不覆盖 `baselines/GameFold-2.9-Mainline-Baseline-R2/`，构建产物进入独立候选目录。

## 验证

- 先运行资源策略检查并确认当前代码不满足新要求。
- 修改后重复策略检查、资源合并和 `mainlineRelease` 构建。
- 用 `aapt`、SHA-256 和 APK 签名工具核对候选包；安装后通过 `dumpsys package` 和拉取 APK 哈希核对设备身份。
