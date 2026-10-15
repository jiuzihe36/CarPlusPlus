# Car++ 瘦身到 ICCOA-only — 重构设计

> 状态：待评审 · 2026-10-15 · 分支 `refactor/iccoa-only`

## 1. 目标

把 Car++ 从「Android Auto 车机 App 的基座 + 塞进 ICCOA」收敛成
**「只做 ICCOA CarLink 车机端」**：删掉与 ICCOA 链路无关的全部遗留代码，
保留用户认可的界面观感、设置体系，以及已真机验证的能力
（配对码 → 连接 → 投屏画面 → 触摸回传）。

衡量标准：`carpp-sdk` 从 **171,674 行 / 363 文件** 降到 **≤ 25,000 行**，
且上述能力在 LAVA 真机上逐项复测通过。

## 2. 现状（实测数据）

| 包 | 文件 / 行数 | 性质 |
|---|---|---|
| `carpp-sdk/aap` | 90 / 121,846 | Android Auto 协议栈（含 protobuf 生成代码、AapService、AapProjectionActivity、媒体键/焦点策略）。**Car++ 从不建 AA 会话** |
| `carpp-sdk/main` | 32 / 12,866 | 首页 / 设置 / 导航 —— **保留**，但 20 处 import `aap.*` |
| `carpp-sdk/utils` | 41 / 7,455 | `Settings` / `AppLog` / 屏幕配置 —— 保留（含 AA 专属项需清） |
| `carpp-sdk/connection` | 32 / 8,582 | 基本是 AA 传输（USB accessory、Nearby、Wi-Fi Direct、SoftAP 凭据、AA 握手）；ICCOA 只用 `IcocaBridge` |
| `carpp-sdk/decoder` `view` `ssl` `extractor` | 39 / 7,283 | AAP 专用解码 / 渲染 / TLS / 抽帧 |
| `carpp-sdk/cluster` `hud` | 27 / 2,890 | **比亚迪**仪表投屏 + BYD HUD 私有协议（README 标注「已实现、未验证」） |
| `sdk-api` / `sdk-iccoa` / `contract` | 6 / 1,355 | 契约层 + ICCOA 实现 + 对外 Intent —— 保留 |
| `app` | 1 / 23 | 薄壳 —— 保留 |

其它：残留包名 `com.andrerinas.*` / `com.miauto.*` / `com.byd.*`；
`playstore` 渠道本来就是坏的（`VpnControl` 只在 `github` 源集）。

## 3. 删除范围

1. `carpp-sdk/src/main/java/com/carpp/sdk/aap/**`（90 文件 / 121,846 行）
2. `decoder/`、`view/`、`ssl/`、`extractor/`（39 文件 / 7,283 行）
3. `cluster/`、`hud/`（27 文件 / 2,890 行）—— 比亚迪专属，用户车辆为奇瑞；
   以后要仪表投屏就按 ICCOA 的 surface 重做一版
4. `connection/` 里 AA 传输部分，只留 ICCOA 链路真正用到的文件
5. 残留包名 `com/andrerinas/**`、`com/miauto/**`、`com/byd/**`
6. `playstore` 渠道（含 `VpnControl`）与两渠道的源集差异
7. 对应单测（82 个测试类里绝大部分是 AAP 的）
8. `AndroidManifest.xml` 里 AA 专属的 service / 权限 / 组件

## 4. 保留

- `sdk-api`（`PhoneLink` / `LinkState` / `NavInfo` 契约）
- `sdk-iccoa`（`IccoaManager` / `IcocaBridge` / `IcocaProjectionActivity` / 布局）
- `carpp-sdk`：`main`（首页 / 设置 / 导航）、`utils`（`Settings` / `AppLog`）、
  `app`（Application / Registry）、`icoca` 空包清理
- `contract`、品牌资源、多语言、README / docs / 构建脚本

## 5. 必须新增的部分（删完才跑得起来）

1. **前台保活**：原来靠 `AapService` 挂前台通知保活。新增 `CarppService`
   （ICCOA 自己的前台 Service）：只负责「保持 BLE 广播 + 投屏期间不被回收」，
   不再有 AA 的车机模式 / MediaSession / WiFi Direct 副作用。
2. **UI 解耦**：`main/` 里 20 处 `aap.*` 引用逐个处理 ——
   `AapService`（15 处，改指 `CarppService`）、`AapProjectionActivity`（4 处，改指协议提供的
   `PhoneLinkProvider.projectionIntent()`）、`MediaKeyRoutingPolicy` / `PlaybackFocusPolicy` /
   `VideoFaultInjector` / `SoftApState` / `proto.MediaPlayback`（AA 专属，直接删设置项）。
3. **设置项清理**：视频 / 图形 / 输入 / 调试里 AA 专属项删掉；
   连接 / 显示 / 音频 / 备份 / 重置 / 关于保留。

## 6. 执行顺序（每步都能编译 + 跑单测）

| 步 | 内容 | 验证 |
|---|---|---|
| 0 | 基线：分支建好，确认现状 build + 718 单测全绿 | 已确认 ✅ |
| 1 | 加 `CarppService`，`main/` 改用它，去掉对 `AapService` 的依赖 | build + 真机保活 |
| 2 | 删 `cluster/`、`hud/`（最独立） | build + 单测 |
| 3 | 删 `aap/` + `decoder/` + `view/` + `ssl/` + `extractor/`，消掉编译错误 | build |
| 4 | 精简 `connection/`，只留 ICCOA 需要的 | build |
| 5 | 清 `playstore` 渠道、残留包名、AAP 单测、manifest 组件与权限 | build + 单测 |
| 6 | 全量验证（下节） | 全绿 |

## 7. 验证

- `:app:assembleGithubDebug` 与 `:app:assembleGithubRelease`（release 走 R8，
  keep 规则与 SDK 反射入口要复核）
- `:carpp-sdk:testGithubDebugUnitTest`（清理后的用例全绿）
- LAVA 真机：配对码 → 连接 → 投屏画面 → **触摸回传（可逆验证）** → 首页 / 各设置页
  能进能出 → 断开重连 → 投屏连续 10 分钟不掉会话
- 代码量对照：`171,674 → ≤ 25,000` 行

## 8. 风险与回滚

| 风险 | 应对 |
|---|---|
| 换掉 `AapService` 后前台保活失效、被系统杀 | 投屏 10 分钟 + 息屏再亮复测；必要时把通知做成「不可划掉」 |
| 设置页删项误删有用项 | 每个设置页真机点一遍，逐页对照旧版截图 |
| release 构建被 R8 裁掉 SDK 入口 | `-keep class com.ucar.**` 已在 `sdk-iccoa/proguard-rules.pro`；release 必须装机实测 |
| 误删 ICCOA 真正用到的 `connection/` 文件 | 逐个文件按「谁引用它」判定，不用「看着像 AA 就删」 |

回滚：全部改动在 `refactor/iccoa-only` 分支上，main 不动；每步一个 commit，可单独 revert。

## 9. 参考材料

- 闭源 SDK 全量反编译：`~/项目/车机/icar-sdk-decompiled/`（jadx 输出，不进仓库）
- 现有文档：`docs/ICCOA_SDK_INTEGRATION.md`（连接 / 触摸回传的坑）、
  `app/libs/README.md`（jar 接入与必须一起搬的组件）
