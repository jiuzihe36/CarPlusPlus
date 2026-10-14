# Car++ SDK（`carpp-sdk`）

车机侧「接住手机」的完整实现，打成 AAR 给宿主 App 用。它自带外壳界面（首页 / 设置 / 投影 /
仪表投屏），也暴露了与协议无关的契约层，让「用哪种协议连手机」变成宿主的一句话配置。

上游脉络：早期内部版本 → 早期内部版本 → 早期内部版本 → Car++ → **Car++**。
内部源码包名已统一为 `com.carpp.sdk`，对外 applicationId 为 `com.carpp.app`。

---

## 模块结构

| 模块 | 类型 | 作用 |
|---|---|---|
| `:sdk-api` | library | **契约层**。`PhoneLink` / `LinkState` / `NavInfo` / `PhoneLinkRegistry`。只有接口和数据类，宿主面向它编程，换协议不用改 SDK。 |
| `:carpp-sdk` | library | **SDK 本体**。连接管理、解码（`decoder/`）、渲染（`view/`）、设置体系（`utils/Settings`）、诊断、仪表盘与 HUD（`cluster/` `hud/`）、JNI。 |
| `:sdk-iccoa` | library | **ICCOA CarLink 协议插件**（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）。只是 `PhoneLink` 的一种实现，可整体替换。 |
| `:app` | application | **薄壳**。只有 applicationId、渠道、签名、输出命名，以及把 ICCOA SDK dex 注入 APK 的那一步。 |
| `:contract` | library | 对外 Intent 常量（`HeadUnitIntent`），给车机上其它 App 调起用。 |

依赖关系：`app → sdk-iccoa → carpp-sdk → sdk-api`。**SDK 不依赖协议插件**，方向永远向下。

---

## 30 秒接入

### 1. 依赖

```kotlin
dependencies {
    implementation(project(":carpp-sdk"))   // 或你发布的 AAR
    implementation(project(":sdk-iccoa"))   // 想接 ICCOA CarLink 就要；换协议就换成你自己的
}
```

### 2. 注册一种协议（Application 里）

```kotlin
class MyHeadUnitApp : com.carpp.sdk.App() {
    override fun onCreate() {
        // 告诉 SDK：用 ICCOA CarLink 接手机
        com.carpp.sdk.sdk.PhoneLinkRegistry.install(IccoaProvider())
        super.onCreate()
    }
}
```

manifest 里把 `android:name` 指向它，其余组件（首页 / 投影页 / 仪表 Activity / Service / Receiver）
与全部权限都由库的 manifest 合并进来，不需要在宿主里重复声明。

### 3. 自己实现一种协议（可选）

```kotlin
class MyProvider : PhoneLinkProvider {
    override val id = "my-protocol"
    override fun create(context: Context): PhoneLink = MyLink(context)
    override fun projectionIntent(context: Context): Intent? = Intent(context, MyProjectionActivity::class.java)
    override val projectionForeground: Boolean get() = MyProjectionActivity.isForeground
}
```

`PhoneLink` 要实现的全部东西：

```kotlin
interface PhoneLink {
    val state: StateFlow<LinkState>      // Idle / Advertising / Pairing / Connected + 配对码
    val navigation: StateFlow<NavInfo?>  // 手机推来的导航信息
    val isStarted: Boolean
    fun start()
    fun stop()
    fun refreshPairing()                 // 用户点了「刷新配对码」
    fun disconnectSession()              // 只断会话、继续广播
    fun setScreenSize(width: Int, height: Int)
    fun setSurface(surface: Surface?)    // 投屏画面画到这里
    fun lastPinCode(): String?
}
```

没注册实现时 SDK 照常编译、照常跑界面，只是连不上手机（`App.provide(ctx).phoneLink == null`）。

---

## 目录职责（`carpp-sdk/src/main/java/com/carpp/sdk/`）

| 目录 | 职责 |
|---|---|
| `connection/` | 传输与发现：WiFi P2P、SoftAP / 热点、USB、Nearby、凭据协商 |
| `decoder/` | 视频 / 音频解码与全部策略类（码率、关键帧、卡顿、重启、背压） |
| `view/` | 投影渲染：SurfaceView / TextureView / GLES、触摸覆盖层、DPI 预览 |
| `cluster/` | 仪表盘投屏（第二屏 `Presentation` 路线）。**奇瑞 NV8341 这类把仪表作为 displayId>0 第二屏的车机走这里** |
| `hud/` | 比亚迪私有 HUD / 仪表协议（BYD CarServer）。仅比亚迪可用 |
| `utils/` | 设置、权限、诊断日志、备份、主题、热点、系统属性 |
| `main/` | 内建 UI 外壳（首页、设置各子页、引导页） |
| `aap/` | Android Auto 链路，Car++ 里只作前台保活（`ICCOA_ONLY=true` 关掉 AA 副作用） |
| `app/` | 开机 / 插 USB / 亮屏自启动的 Receiver |

---

## 构建

```bash
./gradlew :carpp-sdk:assembleGithubRelease   # → carpp-sdk/build/outputs/aar/carpp-sdk-github-release.aar
./gradlew :app:assembleGithubDebug           # → 可安装的 APK（无 ICCOA dex 时也能装，只是连不上手机）
```

要求：JDK 17、Android SDK（compileSdk 36）、NDK 29（JNI：`cpp/ffmpeg_hevc_decoder`、`usbhelper`、`local_hotspot_radio`）。

---

## 已知边界

1. **ICCOA 协议本体是闭源 dex**（`icar-sdk.dex`，约 7 MB），仓库不分发。
   `:sdk-iccoa` 编译期只用签名存根 `sdk-iccoa/stubs/icar-sdk-stubs.jar`，
   运行期由 `:app` 的 `injectIccoaDex` 任务把 dex 作为 `classesN.dex` 追加进 APK 并重签。
   **没有这个 dex 时，编译、安装、界面都正常，只是连不上手机。**
2. 移植 ICCOA SDK 必须三样一起搬：dex + manifest 里的 4 个 `com.share.connect.*` Service + 权限，
   外加 `androidx.room:room-runtime`（dex 里 room 只有引用没有定义）。
   详见 `sdk-iccoa/README.md` 与 `docs/ICCOA_SDK_INTEGRATION.md`。
3. `com.share.connect` / `com.ucar` / `com.easy` / `com.ucarsink` 包根不能改（SDK 字节码里写死了）。
4. 许可：AGPL-3.0（含上游 GPLv3 代码）。外发 AAR 或衍生作品需同源许可。
