# Car++ 瘦身到 ICCOA-only 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans 逐任务执行本计划。
> 步骤用 `- [ ]` 勾选跟踪。

**Goal:** 把 Car++ 收敛成只做 ICCOA CarLink 车机端的应用，删掉 Android Auto / 比亚迪相关的全部遗留代码，同时保留现有界面与设置体系、保留已验证的 ICCOA 能力（配对 → 连接 → 投屏 → 触摸回传）。

**Architecture:** 删除 `carpp-sdk` 里 7 个与 ICCOA 无关的包（aap/decoder/view/ssl/extractor/cluster/hud）与 AA 传输层 `connection/`，新增一个只做前台保活的 `CarppService` 顶替 `AapService`，再把 `main/` 里 20 处 AAP 引用与 AA 专属设置页/项逐一对齐。协议层保持 `sdk-api` 契约 + `sdk-iccoa` 实现不变。

**Tech Stack:** Kotlin/Java、AGP、Gradle 8.13、JDK 17、Android compileSdk 36、闭源 ICCOA SDK（`app/libs/icar-sdk.jar`）。

**Spec:** `docs/superpowers/specs/2026-10-15-iccoa-only-refactor-design.md`

## Global Constraints

- 全程在分支 `refactor/iccoa-only`；**main 不动**，每步一个 commit，可单独 revert。
- 构建命令必须带 JDK 17：
  `export JAVA_HOME=~/项目/资料/toolchain/jdk-17.0.20.1+1/Contents/Home && export PATH="$JAVA_HOME/bin:$PATH"`
- 每步验证：`:app:assembleGithubDebug` 通过；`:carpp-sdk:testGithubDebugUnitTest` 全绿（playstore 渠道本计划内删除，不再跑 `test`）。
- 提交信息中文，说明「删了什么 + 为什么 + 验证了什么」。
- 不留「以后可能用得上」的代码：判定为无关就一次删净（含资源、字符串、manifest 项、测试）。
- 真机验证一律用 LAVA（`~/android-sdk/platform-tools/adb`），不把操作丢回给用户。
- 闭源 SDK 反编译参考：`~/项目/车机/icar-sdk-decompiled/`（不进仓库）。

---

### Task 1: 基线确认

**Files:** 无改动

- [ ] **Step 1:** 确认分支与工作区
  `git branch --show-current` → `refactor/iccoa-only`；`git status --short` → 干净
- [ ] **Step 2:** 记录基线
  `./gradlew :app:assembleGithubDebug :carpp-sdk:testGithubDebugUnitTest` → BUILD SUCCESSFUL，718 用例 0 失败
- [ ] **Step 3:** 记录代码量基线
  `find carpp-sdk/src sdk-iccoa/src sdk-api/src app/src contract/src \( -name "*.kt" -o -name "*.java" \) | grep -v /build/ | xargs wc -l | tail -1` → 约 172,000 行

### Task 2: 新增 CarppService（前台保活），UI 改用它

**Files:**
- Create: `carpp-sdk/src/main/java/com/carpp/sdk/app/CarppService.kt`
- Modify: `carpp-sdk/src/main/AndroidManifest.xml`（注册 service + `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 权限 + `foregroundServiceType="dataSync"`）
- Modify: `carpp-sdk/src/main/java/com/carpp/sdk/main/` 里引用 `AapService` 的 15 个文件（`MainActivity` / `SettingsFragment` / `DarkModeFragment` / `AutoStartFragment` / `QuickSettingsFragment` / `AutomationActivity` / `UsbListFragment` / `MicSettingsFragment` / `LocationsFragment` / `HomeFragment` / `NetworkListFragment` / `MapPickerFragment` 等）

**Interfaces:**
- Produces: `CarppService.start(context: Context)` / `CarppService.stop(context: Context)` / `CarppService.isRunning: Boolean`（UI 只依赖这三个，不再依赖 AA 的 `AapService`）

- [ ] **Step 1:** 写 `CarppService`：前台 Service，只做两件事 —— 挂常驻通知（渠道沿用 `BackgroundNotification`）+ 保持进程存活，让 BLE 广播与投屏不被回收。**不**做车机模式、MediaSession、WiFi Direct/SoftAP 任何事。
- [ ] **Step 2:** manifest 注册 + 权限（API 34+ 前台服务必须声明类型）。
- [ ] **Step 3:** 把 15 处 `AapService.*` 调用替换为 `CarppService.*`（同一语义：启动/停止/查询运行状态）。
- [ ] **Step 4:** 构建：`:app:assembleGithubDebug` 通过。
- [ ] **Step 5:** 真机：装到 LAVA，启动 App → 通知出现 → 手机连上投屏 → 静置 10 分钟不掉会话（`adb logcat` 看心跳持续）。
- [ ] **Step 6:** Commit：`refactor: 用 CarppService 顶替 AapService 做前台保活`

### Task 3: 删比亚迪那两块

**Files:**
- Delete: `carpp-sdk/src/main/java/com/carpp/sdk/cluster/**`（10 文件）
- Delete: `carpp-sdk/src/main/java/com/carpp/sdk/hud/**`（17 文件）
- Delete: 对应单测（`carpp-sdk/src/test/**/cluster/**`、`**/hud/**`）
- Modify: `carpp-sdk/src/main/AndroidManifest.xml`（删 `.cluster.ClusterHostActivity`、BYD 相关 `<queries>` 包名）
- Modify: `carpp-sdk/src/main/res/values*/strings.xml`（删 BYD/仪表/HUD 文案）、`res/layout*/`（删相关布局）

- [ ] **Step 1:** `git rm -r` 两个包 + 相关测试与布局。
- [ ] **Step 2:** 清 manifest 与字符串；清 `SettingsFragment` 里「仪表投屏 / HUD 未识别不显示」两项（`BydNavigationOutputs.available()` 门控一并删）。
- [ ] **Step 3:** 构建 + 单测。
- [ ] **Step 4:** Commit：`refactor: 删除比亚迪仪表投屏与 HUD 输出（27 文件 / 2890 行）`

### Task 4: 删 Android Auto 协议栈

**Files:**
- Delete: `carpp-sdk/src/main/java/com/carpp/sdk/aap/**`（90 文件 / 121,846 行）
- Delete: `carpp-sdk/src/main/java/com/carpp/sdk/decoder/**`（21）、`view/**`（11）、`ssl/**`（4）、`extractor/**`（3）
- Delete: 上述包对应的全部单测（`carpp-sdk/src/test/**`）
- Modify: `carpp-sdk/src/main/AndroidManifest.xml`（删 `.aap.AapProjectionActivity`、`.app.UsbAttachedActivity`、USB_DEVICE_ATTACHED intent-filter、AA 权限）
- Modify: `carpp-sdk/build.gradle.kts`（删 protobuf / AA 相关依赖与插件）

- [ ] **Step 1:** `git rm -r` 五个包。
- [ ] **Step 2:** 反复跑构建，按编译错误逐个消掉 `main/`、`utils/`、`app/` 里对 AAP 的引用：
  - `AapProjectionActivity` → 用 `PhoneLinkRegistry.link(this)?.let { startActivity(...) }` 或协议提供的 `projectionIntent()`
  - `MediaKeyRoutingPolicy` / `PlaybackFocusPolicy` / `VideoFaultInjector` / `proto.MediaPlayback` → AA 专属，删掉对应设置项与代码
  - `SoftApState`（`ConnectionSetupFragment` 用）→ 换成 ICCOA 自己的状态判断
- [ ] **Step 3:** 删 manifest 里 AA 组件与权限、清 `res/layout*` 与 `strings.xml` 里 AA 专属项。
- [ ] **Step 4:** 构建 + 单测（此时单测应只剩 ICCOA/契约/UI 相关的）。
- [ ] **Step 5:** Commit：`refactor: 删除 Android Auto 协议栈与 AAP 专用解码/渲染（129 文件 / 12.9 万行）`

### Task 5: 删 AA 传输层 connection/，改 UI 依赖

**Files:**
- Delete: `carpp-sdk/src/main/java/com/carpp/sdk/connection/**`（24 文件；ICCOA 侧不需要任何文件，`CommManager` 只出现在注释里）
- Modify: `main/MainViewModel.kt`、`main/MainActivity.kt`、`main/SettingsFragment.kt`、`main/UsbListFragment.kt`、`main/NetworkListFragment.kt`
- Delete: AA 专属设置页 —— `UsbListFragment`（USB 配件列表）、`KeymapFragment`（AA 方控映射）、`AutomationActivity`（AA 自动化）等按第 6 节判据逐页判定
- Modify: `res/navigation/*.xml`（删被删页面的目的地与 action）

**判据（保留 / 删除）:**
- 保留：连接方式（P2P/热点）、显示、视频、音频、输入（仅 ICCOA 需要的）、备份、重置、诊断、权限、关于、支持、深色模式、DPI、开机自启、自动连接、车辆信息、语言
- 删除：USB 配件列表、AA 键盘映射、AA 媒体键/焦点策略、AA 视频故障注入、AA 自动化、AA 地图选点（如仅服务 AA 导航）

- [ ] **Step 1:** `git rm -r connection/`，按编译错误清 UI 引用。
- [ ] **Step 2:** 按判据删设置页与其 nav 目的地/action、布局、字符串。
- [ ] **Step 3:** 构建 + 单测。
- [ ] **Step 4:** 真机：逐个设置页点进去、返回，无崩溃无空白页。
- [ ] **Step 5:** Commit：`refactor: 删除 AA 传输层与 AA 专属设置页`

### Task 6: 清残留包名、渠道、资源

**Files:**
- Delete: `carpp-sdk/src/main/java/com/andrerinas/**`、`com/miauto/**`、`com/byd/**`（若 Task 3/4 未覆盖）
- Modify: `app/build.gradle.kts`（删 `playstore` 渠道与其 `VpnControl` 源集、清依赖）
- Modify: `carpp-sdk/build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`
- Modify: `carpp-sdk/src/main/res/**`（清 AA/BYD 文案与图片；`shrinkResources` 只对 release 生效，debug 也要清）

- [ ] **Step 1:** 删残留包名与 playstore 渠道。
- [ ] **Step 2:** 清 `res/values*/strings.xml` 里已无引用的文案（用 `grep -rn "R.string.<name>"` 反查）。
- [ ] **Step 3:** 构建 + 单测。
- [ ] **Step 4:** Commit：`refactor: 清残留包名、playstore 渠道与无用资源`

### Task 7: 全量验证

- [ ] **Step 1:** `:app:assembleGithubDebug`、`:app:assembleGithubRelease`（release 走 R8，复核 `-keep class com.ucar.**` 与 SDK 反射入口）全部成功。
- [ ] **Step 2:** `:carpp-sdk:testGithubDebugUnitTest` 全绿。
- [ ] **Step 3:** release APK 装机实测（R8 可能裁掉只在布局里引用的 SDK View）。
- [ ] **Step 4:** LAVA 真机四项：配对码 → 连接 → 投屏画面 → 触摸回传（可逆验证：车机左右滑，手机界面切页并切回原位）；外加：首页各按钮、设置页逐个进出、断开重连、投屏 10 分钟。
- [ ] **Step 5:** 代码量对照：`find ... | xargs wc -l | tail -1` → ≤ 25,000 行；把前后数字写进提交信息。

### Task 8: 文档与合并

- [ ] **Step 1:** 更新 `README.md` / `README.zh-CN.md`（去掉 AAP / 比亚迪 / playstore 的描述）、`CHANGELOG-zh.md`（记一条 0.2.0 或 0.1.2）。
- [ ] **Step 2:** 删 `docs/` 里已失效的文档（`BYD_NAVIGATION.md`、`WIRELESS_BSSID_FIX.md`、`APK_ANALYSIS_ZHL-v1.5.3.md`、`FEASIBILITY.md` 按是否还有参考价值逐份判定），`ICCOA_SDK_INTEGRATION.md` 保留并更新。
- [ ] **Step 3:** 合并回 main（`git checkout main && git merge --no-ff refactor/iccoa-only`），推送，核对远端。
- [ ] **Step 4:** 真机最后复测一次（合并后的 main 构建）。
