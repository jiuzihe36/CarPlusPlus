# Car++

**车机端的 ICCOA CarLink 接收器。** 小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar 等手机，
无线投上车机大屏 —— 无需转接盒、无需数据线、无需 root、手机侧不装任何东西。

> 声明与许可：见 [`NOTICE.md`](NOTICE.md)。

Car++ 装在车机上，把自己广播成一台支持 ICCOA CarLink 的车机；手机在附近设备列表里发现它，
在手机上确认车机屏幕上的 6 位配对码，手机画面即出现在车机上。

---

## 特性

| 能力 | 状态 | 说明 |
|---|---|---|
| 无线建立 ICCOA CarLink 会话 | 真机已验证 | 手机放兜里或无线充电板上即可 |
| 配对码显示 + 30 秒重播看门狗 | 真机已验证 | 手机上误关的配对弹窗会自己回来，不用重启应用 |
| 完整设置体系 | 已实现 | 连接 / 显示 / 视频 / 输入 / 音频 / 备份 / 重置 / 诊断 |
| 仪表盘投屏（第二屏） | 已实现、默认关闭 | 走通用 `Presentation` 路线，车机把仪表作为第二屏时可用 |
| HUD 导航输出 | 已实现、未验证 | 比亚迪私有协议链路，手上没有对应车机 |

> 「已实现、未验证」= 代码路径已写好并能编译，但还没有在它面向的硬件上跑过。如实列出，不写成「可用」。

---

## 工程结构（已 SDK 化）

| 模块 | 类型 | 作用 |
|---|---|---|
| `:sdk-api` | library | **契约层**：`PhoneLink` / `LinkState` / `NavInfo` / `PhoneLinkRegistry`。只有接口和数据类 |
| `:carpp-sdk` | library | **SDK 本体**：连接、解码、渲染、设置、诊断、仪表 / HUD、JNI、内建界面 |
| `:sdk-iccoa` | library | **ICCOA CarLink 协议插件**，只是 `PhoneLink` 的一种实现，可整体替换 |
| `:app` | application | **薄壳**：applicationId、渠道、签名、输出命名、ICCOA dex 注入 |
| `:contract` | library | 对外 Intent 常量（`HeadUnitIntent`） |

依赖方向永远是 `app → sdk-iccoa → carpp-sdk → sdk-api`，**SDK 不依赖协议插件**。
宿主 App 在 `Application.onCreate` 里 `PhoneLinkRegistry.install(IccoaProvider())` 一次即可；
换协议时只要换成自己的 `PhoneLinkProvider`，SDK 一行都不用改。

详细说明见 [`carpp-sdk/README.md`](carpp-sdk/README.md) 与 [`sdk-iccoa/README.md`](sdk-iccoa/README.md)。

---

## 构建

```bash
./gradlew :app:assembleGithubDebug          # 可安装 APK（无 ICCOA dex 时也能装，只是连不上手机）
./gradlew :app:assembleGithubRelease        # 发布 APK
./gradlew :carpp-sdk:assembleGithubRelease  # SDK 的 AAR
```

要求 JDK 17、Android SDK（compileSdk 36）、NDK 29。

### 产物

| 产物 | 路径 |
|---|---|
| SDK（AAR） | `carpp-sdk/build/outputs/aar/carpp-sdk-github-release.aar` |
| 契约层（AAR） | `sdk-api/build/outputs/aar/sdk-api-release.aar` |
| ICCOA 插件（AAR） | `sdk-iccoa/build/outputs/aar/sdk-iccoa-github-release.aar` |
| 调试 APK | `app/build/outputs/apk/github/debug/com.carpp.app.debug_0.1.1-debug_debug.apk` |

---

## 关于 ICCOA 协议本体

ICCOA CarLink 的车机端协议实现是**第三方闭源 dex**（`icar-sdk.dex`，约 7 MB），
**不在本仓库分发**，也不在本项目许可范围内：

- 编译期只用签名存根 `sdk-iccoa/stubs/icar-sdk-stubs.jar`（`compileOnly`，不进产物）；
- 运行期把 dex 放到 `app/libs/icar-sdk.dex`，由 `:app` 的 `injectIccoaDex` 任务
  作为 `classesN.dex` 追加进 APK，再 zipalign + 重签。

**没有这个 dex 时工程照样能编译、能装、能开界面，只是连不上手机。**

移植 ICCOA SDK 必须三样一起搬：dex + manifest 里的 4 个 `com.share.connect.*` Service + 权限，
外加 `androidx.room:room-runtime`。详见 [`sdk-iccoa/README.md`](sdk-iccoa/README.md)。

---

## 安装与使用

1. 装 APK 到车机（允许「未知来源」）；
2. 打开一次 Car++，首页显示车机名称与 6 位配对码；
3. 手机打开 CarWith / Car+ 车联 / Jovi InCar，在附近设备列表里选中本车机并确认配对；
4. 手机画面出现在车机上。**请在停车状态下操作。**

---

## 许可

本项目以 **AGPL-3.0** 许可发布（见 [`LICENSE`](LICENSE)），
其中包含继承而来的 GPLv3 代码，其原作者版权声明见
[`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)。
二次分发或以本 SDK 构建衍生作品时，须保留上述许可与版权声明。

独立的社区项目。与小米、OPPO、vivo、ICCOA、Google、比亚迪均无隶属、授权或合作关系；
文中提及的产品与公司名称归各自所有者所有。
