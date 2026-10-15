# 星际科幻主页 UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已批准的星际科幻原型（interstellar-car-ui-prototype.html）实现为 Car++ 的真实主页，接入现有 ICCOA 连接状态与全部既有操作。

**Architecture:** 在 :carpp-sdk 内引入 Jetpack Compose（仅 ui/foundation/animation，不用 material3），HomeFragment 保留全部现有逻辑（状态收集、投屏入口、退出、刷新配对码、长按进设置），仅把视图层从 fragment_home.xml 换成 ComposeView 承载的新 UI。视觉层拆为主题/星空背景/全息组件/主页布局四个文件，连接状态映射抽成纯函数以便单测。

**Tech Stack:** Jetpack Compose (BOM 2024.02.00, compiler ext 1.5.10 配 Kotlin 1.9.22), AGP 8.13.2, Kotlin 1.9.22, Canvas 动画（不引入 OpenGL——车机 Android 9 上 RenderEffect 等需 API 31，Canvas 发光绘制全版本可用）。

**Spec:** 视觉规范 = 用户批准的原型 `~/.hermes/cache/scratch/interstellar-car-ui-prototype.html`；行为规范 = 现有 HomeFragment.kt（194 行，注释即文档）。

## Global Constraints

- 分支 `refactor/iccoa-only`；main 不动；构建前 `export JAVA_HOME=~/项目/资料/toolchain/jdk-17.0.20.1+1/Contents/Home`
- 真机验证用 `~/android-sdk/platform-tools/adb`（LAVA，Android 12）；目标车机 Android 9（API 28），**禁用仅 API 31+ 的绘制特效**
- minSdk 16 → 21（Compose 硬要求；github 渠道与 sdk-iccoa github flavor 同步改，carpp-sdk defaultConfig 同步改）
- 不编造数据：原型里的温度/油耗等装饰数据**不上真机**，面板只放真实状态（连接阶段、进度、配对码、版本号）
- 既有操作一个不丢：长按品牌区→设置、退出→moveTaskToBack、打开投屏/回到投屏、刷新配对码、降级态（缺 icar-sdk.dex）渲染
- 单测必须全绿（现 46 个 + 新增状态映射测试）；不留未引用的旧布局

---

### Task 1: Compose 构建基建

**Files:**
- Modify: `carpp-sdk/build.gradle.kts`（plugins 不变；android{} 加 composeOptions + buildFeatures.compose；defaultConfig.minSdk 16→21；dependencies 加 compose BOM 三件套）
- Modify: `app/build.gradle.kts`（github flavor minSdk 16→21，注释同步改）
- Modify: `sdk-iccoa/build.gradle.kts`（github flavor minSdk 16→21 与 defaultConfig minSdk 16→21）
- Test: 现有 `:carpp-sdk:testGithubDebugUnitTest`

**Interfaces:** 产出 —— :carpp-sdk 内可 `import androidx.compose.*`；后续任务的 Compose 代码能编译。

- [ ] Step 1: 改三个 build.gradle.kts（composeOptions.kotlinCompilerExtensionVersion="1.5.10"；implementation BOM + ui + ui-graphics + foundation + animation）
- [ ] Step 2: `./gradlew :carpp-sdk:assembleGithubDebug` 验证编译通过、依赖可下载
- [ ] Step 3: 跑现有单测 46 个全绿
- [ ] Step 4: Commit `build: 引入 Jetpack Compose（minSdk 16→21）`

### Task 2: HomeUiState 纯函数状态映射（TDD）

**Files:**
- Create: `carpp-sdk/src/main/java/com/carpp/sdk/ui/scifi/HomeUiState.kt`
- Test: `carpp-sdk/src/test/java/com/carpp/sdk/ui/scifi/HomeUiStateTest.kt`

**Interfaces:**
- `data class HomeUiState(statusRes: Int, progressPercent: Float, pinCode: String?, showRefresh: Boolean, projectionLabelRes: Int?, degraded: Boolean)`
- `fun homeUiStateFor(state: LinkState?): HomeUiState` —— LinkState 为 null = 降级态
- 状态字串/标签用 res id（R.string.da_ready 等），composable 里再 getString，保持纯函数可测
- 复用 `HomeFragment.progressPercentFor` 与 `groupPin`

**规则（从现 render()/renderDegraded() 提取）：**
1. degraded（state==null）→ status=da_unavailable, progress=0, pin=null, showRefresh=false, projectionLabel=null
2. pinCode 可见性只看 `state.pinCode` 是否非空白，与 phase 无关
3. showRefresh = 未 Connected；projectionLabel = Connected ? projection_reopen : da_open（仅 Connected 时按钮可见 → 用 nullable 表达：Connected=projection_reopen，否则 null=不可见）
4. status：Idle/Advertising=da_ready, Pairing=da_connecting, Connected=da_connected；progress=state.progress ?: progressPercentFor(phase)

- [ ] Step 1: 写失败测试（上面 4 条规则各一用例 + pin 分组边界沿用既有测试则不重复）
- [ ] Step 2: 跑测试确认 FAIL（类不存在）
- [ ] Step 3: 实现 HomeUiState.kt
- [ ] Step 4: 跑测试确认 PASS，旧 46 个仍绿
- [ ] Step 5: Commit

### Task 3: 星际视觉层（主题 + 星空 + 全息组件 + 主页布局）

**Files:**
- Create: `carpp-sdk/src/main/java/com/carpp/sdk/ui/scifi/SciFiTheme.kt` —— 色板常量：SpaceBlack 0xFF010210、PanelBg 0xB3010210、Cyan 0xFF00F0FF、Violet 0xFF7B68EE、Gold 0xFFFFD200、边框 = Cyan@0.3
- Create: `.../ui/scifi/StarfieldBackground.kt` —— Canvas：~70 颗漂移星点（rememberInfiniteTransition 匀速平移+闪烁）、径向渐变星云（Violet@0.25 → 透明，缓慢呼吸缩放）、底部透视网格线（Cyan@0.1），全版本可用绘制
- Create: `.../ui/scifi/HoloComponents.kt` —— `HoloPanel`（圆角12 + 半透明底 + 1px 青边 + drawBehind 多层递减 alpha 模拟外发光）、`HoloButton`（按压缩放 0.96 + 扫光）、`StatusDot`（呼吸动画）、`GlowText`
- Create: `.../ui/scifi/SciFiHomeScreen.kt` —— 布局照原型三栏+底栏：
  - 顶栏：品牌名 Car++ 发光 + 右侧状态灯/状态字/实时时钟
  - 左栏「连接状态」：状态大字、进度条（呼吸渐变青→紫）、配对码卡片（3+3 分组，非空才显示）、刷新按钮（showRefresh 时）
  - 中栏 hero：状态标题 + 进度 + 主按钮（仅 Connected：打开投屏/回到投屏）
  - 右栏「系统信息」：版本号 BuildConfig.VERSION_NAME、协议 ICCOA CarLink、设置按钮（长按品牌也可进，按钮补明路）
  - 底栏 5 个圆形控制键：设置 / 刷新(未连接) / 投屏(主键，Connected) / 退出到车机后台 / 无连接时显示"等待手机连接"脉冲提示（不造假数据）
  - 签名：`@Composable fun SciFiHomeScreen(state: HomeUiState, onOpenSettings: () -> Unit, onExit: () -> Unit, onOpenProjection: () -> Unit, onRefreshPin: () -> Unit)`
- [ ] Step 1: 四个文件写完，`:carpp-sdk:assembleGithubDebug` 通过
- [ ] Step 2: Commit `feat(ui): 星际科幻视觉层（主题/星空/全息组件/主页布局）`

### Task 4: HomeFragment 接入 + 旧布局清理

**Files:**
- Modify: `carpp-sdk/src/main/java/com/carpp/sdk/main/HomeFragment.kt` —— onCreateView 返回 ComposeView；onViewCreated 把 collect 到的 LinkState/降级态写入 `mutableStateOf<HomeUiState>`；按钮回调接既有四个动作；保留 CarppService.start 保活逻辑
- Delete: `carpp-sdk/src/main/res/layout/fragment_home.xml`（先 grep 确认无其他引用；若有 land/port 变体一并删）
- Test: 既有 HomeUiStateTest + 全量单测

- [ ] Step 1: grep `fragment_home` 确认引用面只有 HomeFragment
- [ ] Step 2: 改 HomeFragment（语义与注释保留：投屏前 onUserReopen + icocaProjectionOpened=true）
- [ ] Step 3: 删旧布局
- [ ] Step 4: 全量构建 debug+release + 单测全绿
- [ ] Step 5: Commit `feat(ui): 主页切换为星际科幻 Compose 界面`

### Task 5: 真机验证 + 收尾

**Files:** 无新文件；安装包验证。

- [ ] Step 1: 装 LAVA：`adb install -r app/build/outputs/apk/github/debug/com.carpp.app.debug_0.1.1-debug_debug.apk`
- [ ] Step 2: 启动 → screencap + OCR 验证：星空背景、三栏、配对码/状态显示、无闪退
- [ ] Step 3: 依次点 设置 / 刷新 / 退出 三个底栏键（uiautomator 或 input tap）验证回调动作与返回
- [ ] Step 4: 等手机连上（或手动 Car+ 连接）→ 验证 Connected 态按钮变「回到投屏」、点了能进投屏
- [ ] Step 5: 最终 Commit + push

## Self-Review

- 覆盖：原型视觉（Task 3）、既有行为不丢（Task 2 规则 + Task 4 接入）、真机验证（Task 5）✓
- 无占位符；接口签名前后一致（HomeUiState 字段名 Task 2 定义 / Task 3 消费）✓
- 风险注记：AGP 8.13 下 composeOptions 若被拒，改用 `composeCompiler { }` DSL；依赖下载失败则检查 maven 通路
