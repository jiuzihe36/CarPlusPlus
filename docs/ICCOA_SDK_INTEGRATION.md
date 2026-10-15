# ICCOA CarLink 集成说明

Car++ 以 [早期内部版本](https://github.com//早期内部版本-Pro) 为基座重建，把手机侧协议
从 **Android Auto** 换成 **ICCOA CarLink**（接住小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar
等走同一套协议的手机端）。本文记录这次替换中真正踩过的坑，
供后续维护者参考。

---

## 1. 为什么以 早期内部版本 为基座，而不是从极简工程派生

上一版 Car++ 派生自最小验证工程 —— 只有 4 个类、1 个布局，**根本没有设置页**。
于是 早期内部版本 里那套「常规 + 高级」设置（含 `仪表投屏` / `投屏时机` / `HUD 未识别不显示`）
在 Car++ 里全都对不上。

改为以 早期内部版本 为基座后，这些设置**原样回归**。

## 2. 现在的 Car++ 由哪些层组成

| 层 | 来源 | 说明 |
|---|---|---|
| UI 外壳 | 早期内部版本 | `MainActivity` + Navigation（首页 / 菜单 / 启动屏 / 各设置子页） |
| 设置体系 | 早期内部版本 | `main/SettingsActivity.kt` / `SettingsFragment.kt`：常规 · 无线连接 · 深色模式 · 自动化 · 导航 · 图形 · 视频 · 输入 · 音频 · 备份 · 重置 · 调试 · 信息 |
| BYD 仪表 + HUD 输出 | 早期内部版本 | `hud/` + `cluster/`；`BydNavigationOutputs.available()` 就是「HUD 未识别则不显示开关」的门控 |
| 投影渲染 | 早期内部版本 | `view/`（SurfaceView / TextureView / GLES）+ `aap/AapProjectionActivity` |
| 手机协议 | **ICCOA（新）** | `connection/IcocaBridge.kt` + `com.carpp.app.IccoaManager` + `app/libs/icar-sdk.jar` |

> 内部 Java 包名仍为 `com.andrerinas.openheadunit`（沿用基座，避免 275 个源文件 + JNI/proto/AIDL
> 的批量改名风险）；**对外的 `applicationId` 是 `com.carpp.app`**，与 早期内部版本
> （`com.andrerinas.早期内部版本`）独立共存。

## 3. 移植 SDK 时必须一起搬的三样东西

只把 SDK 的字节码放进工程**不等于** SDK 可用。真机上「手机完全扫不到车机、界面无配对码、
点任何按钮都无反应」的根因，就是**只搬了字节码**。字节码只等于 SDK 的**类**，另外三份必须一起搬：

| 组成 | 漏掉时的症状 | Car++ 的做法 |
|---|---|---|
| **`<service>` 组件** | 系统起不了 SDK 的 Service → **静默失效，零报错** | `AndroidManifest.xml` 显式注册 `com.share.connect.ShareLinkService` / `.ble.BluetoothLeService` / `.wifiap.WifiApService` / `.wifip2p.WifiP2pService` + `androidx.room.MultiInstanceInvalidationService` |
| **`<uses-permission>`** | 权限不足 → API 静默失败 | 逐条对齐样本：`TETHER_PRIVILEGED` / `OVERRIDE_WIFI_CONFIG` / `NETWORK_SETTINGS` / `NETWORK_STACK` / `LOCAL_MAC_ADDRESS` / `QUERY_ALL_PACKAGES` / `MANAGE_USB` 等 |
| **Gradle 传递依赖** | `NoClassDefFoundError` | `implementation("androidx.room:room-runtime:2.4.3")` —— SDK 用 Room 存信任设备（Peer）表，而它的 dex 里 `androidx/room/*` **定义数为 0**（只有引用） |

> 判定「类到底有没有被抽到」**必须解析 dex 的 `class_defs`**，不能只 grep 字符串池 ——
> 字符串池里出现 `Landroidx/room/RoomDatabase;` 既可能是**定义**、也可能只是**引用**。

还有一条：**启动 SDK 必须等运行时权限**。`BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`（API 31+）
未授予时 `startAdvertise()` 会**静默失败**。Car++ 用幂等的 `ensureIcocaStarted()`
（`onCreate` + `onResume` 各调一次）兜住。

## 4. 与 Android Auto 的资源隔离

Car++ 只把 `AapService` 当前台保活，并用 `BuildConfig.ICCOA_ONLY = true` 关掉它的 AA 专属副作用：

- `enableCarMode()` —— 会与车机自己的 UI 抢前台
- `MediaSession.isActive` —— 会抢车机媒体键
- `WifiDirectManager` / `SoftApCredentialsProvider` / `LocalHotspotCredentialsProvider` / `NearbyManager`
  —— **会抢占 ICCOA 投屏数据通道要用的 WiFi P2P 组与 SoftAP**

`AapService` 里的这些字段全是可空且下游一律用 `?.`，所以不构造它们即可安全跳过（下游自动 no-op）。

## 5. 配对码为什么必须「周期性重播」

真机实测暴露了一个只做「断开后刷新一次」解决不了的问题：

> 断开后确实刷新了新码，但如果当时没人连（手机关掉了配对弹窗），之后码就不再变，
> 手机也再不会弹窗 —— 只能重启应用才能重连。

**根因**：车机「一直在广播」不等于「手机能重新发现」。BLE 广播帧内容不变时，手机端 CarWith
**不会重新弹出**配对界面。

**解法**（`com.carpp.app.IccoaManager`）：

| 机制 | 说明 |
|---|---|
| **30 秒看门狗** | `AUTO_REFRESH_INTERVAL_MS = 30_000`：到期若仍未连接 → 重播 → 再调度下一轮 |
| **★ 重播 = 停-启** | `stopAdvertise()` → `startAdvertise()`。**对一条已在广播中的链路重复调用 `startAdvertise()` 会被 SDK 直接忽略**，广播帧不变，手机端什么也不会发生 —— 这是最容易被漏掉的一步 |
| **避让** | `sessionActive`（已连：状态 2/4/6/7）或 `castStarted`（画面已投出）时跳过，绝不打断进行中的会话；中间态**刻意不算**已连，好让协商卡死时能被重播救回来 |
| **计时重置** | `onPinCode` 时重置计时，保证用户拿到完整的 30 秒去确认 |
| **手动入口** | 首页「刷新配对码」按钮 → `IcocaBridge.refreshPairing()`，与看门狗同一条路径，只是不等 30 秒；仅未连接时可见 |

顺带修掉一个隐患：`tryStartCast()` 原先在无 Surface（待机页）时会**每 500 ms 无限自我重试**，
待机页常驻即等于永不停止的定时器。现已加 `CAST_RETRY_MAX = 20`（≈10 秒）预算，
Surface 稍后就绪的情形由 `setSurface()` 兜底触发。

---

## 6. 连接状态回调的真实签名（踩过的坑）

SDK 的接口是：

```java
void onConnectStateChanged(String deviceId, int state, int extra);
```

**第 2 个参数才是状态，第 3 个是附加码**（连接失败时是错误码，其余情况恒为 `-1`）。
`com.ucar.vehiclesdk.UCarConnectState` 里的状态常量：

| state | 含义 | 对应界面 |
|---|---|---|
| 1 | `UCAR_DEVICE_DISCONNECT` | 未连接 |
| 2 | `UCAR_DEVICE_CONNECTED` | 已连接 |
| 3 | `UCAR_DEVICE_CONNECT_FAILED` | 失败（`extra` 是错误码） |
| 4 | `UCAR_DEVICE_CAST_CONNECTED` | 投屏已建立 |
| 5 | `UCAR_DEVICE_CAST_DISCONNECTED` | 投屏断开 |
| 6 / 7 | `GO_TO_HOME` / `BACK_TO_FOREGROUND` | 前后台切换，**会话仍在** |

错误码：`10001` init 失败 / `10003` 无线连接失败 / `10005` 启动投屏失败 /
`10006` 超时 / `10008` 内部失败（典型是热点建不起来）。

**曾把签名误读成 `(deviceId, oldState, newState)`**，于是每次「连接成功」的那次回调
（`state=2, extra=-1`）被当成新状态 `-1`，落进 `else` 分支回落成「广播中」——
表现是**底层已经连上并在传视频，界面却永远停在「等待手机连接」**，投屏界面也不弹。
排查时先看 `onConnectStateChanged` 的原值，别信自己的映射。

## 7. 两条连接路线：Wi-Fi Direct 与车载热点

数据通道有两条路，由**设置里的「连接方式」**（`Settings.nativeApTransport`，
pref key `native-ap-transport`）决定，`IccoaManager.buildConfig()` 读它：

| 设置 | `UCarConfig` | 要求 |
|---|---|---|
| Wi-Fi Direct（0，默认） | `setSupportP2P(true)` + `setSupportSoftAP(false)` | 普通应用权限即可。SDK 自己 `createGroup` 组网 |
| 车载热点（1） | `setSupportP2P(false)` + `setSupportSoftAP(true)` | 需要 `TETHER_PRIVILEGED` —— **signature\|privileged 权限，只有系统应用拿得到** |

- 热点路线在**非系统安装**下必然失败：`setSoftApConfiguration()` 抛
  `SecurityException: App not allowed to read or update stored WiFi Ap config`，
  `pm grant` 也补不上（该权限不可动态授予）→ 热点建不起来 → 手机连不进 →
  `errorCode 10008` → 蓝牙被手机侧断开。要用这条路线，必须把 APK 装进 `/system/priv-app`。
- 两条都开着时 SDK 会**优先去建热点**，于是在非系统安装上直接撞死；这也是
  「手机明明显示车机名字、就是连不上」的常见原因。
- 手机端协商时会带自己的期望（`type:1002` = 期望 AP 连接）。若车机侧不支持，
  SDK 会打日志 `phone expect ap connection, but car only supports p2p connection, use p2p instead`
  并自动改走 P2P —— 所以**只开 P2P 是安全的兜底**。
- `UCarConfig` 是 **init-once**：改设置后要重启 App 才生效。

## 8. 触摸回传（UIBC）：三个条件缺一个就「点了没反应」

车机端把手指事件回传给手机，SDK 自己就能做 —— 但必须三样都满足。Car++ 第一版**三样都没做**
（投屏页用的是裸 `SurfaceView`，整条回传路径压根不存在），表现就是「画面能看，但点什么都没反应」。

| 条件 | Car++ 的做法 | 漏了会怎样 |
|---|---|---|
| 用 SDK 自带的 `UCarSurfaceView` | `activity_icoca_projection.xml` 里直接写 `<com.ucar.vehiclesdk.UCarSurfaceView>` | 裸 `SurfaceView` 不转发任何触摸：`UCarAdapter.sendTouchEvent()` 一次都不会被调用 |
| 布局里 `android:clickable="true"` | 同上 | `UCarSurfaceView.onTouchEvent` 最后 return `super.onTouchEvent()`，未 clickable 时是 `false` → ViewGroup 只投 ACTION_DOWN，后续 MOVE/UP 全部丢失（表现是点了没反应，或一直「按住」不放） |
| 触摸坐标空间 = 投屏 View 的像素尺寸 | 投屏页 `bindSurface()` 里先 `PhoneLink.setProjectionViewSize(view.width, view.height)`，**再** `setSurface()` | 用面板分辨率当坐标空间 → 触摸整体偏移（点中心落到右上角） |

细节（都是读 SDK 字节码 + 真机日志确认过的）：

- `UCarSurfaceView.onTouchEvent` 把 `getX/getY` 原样交给
  `UCarAdapter.sendTouchEvent(action, pointerCount, ids, xs, ys)`，`UibcManager` 再按
  `display → source` 缩放：`getTouchCoordinateX(x) = x * mSourceWidth / mDisplayWidth`。
- **display 尺寸就是 `startCast(surface, w, h)` 的 w/h**（`UCarAdapter.lambda$startCast$25` 里
  `setDisplayWidth(w)` / `setDisplayHeight(h)`），SDK 没有单独的 setter。所以 `startCast` 的
  w/h **不是码流分辨率，而是触摸坐标空间**；码流尺寸由 SDK 自己从链路里取（真机实测 source = 1600x720）。
- 投屏起来之后**没有入口**再改 display 尺寸 → 中途旋转 / 分屏会让坐标空间过期，只能断开重连。
  ICCOA 投屏页因此锁 `sensorLandscape`（车机本来就是横向画布，MainActivity 也是按长边在前声明的）。
  `IccoaManager.setProjectionViewSize()` 在投屏进行中收到新尺寸时会打一条明确告警。
- 手机**息屏**时手机侧不再出帧，镜像画面冻结（连拍两张 0.00% 差异），此时点什么都不动 ——
  排查触摸之前先确认手机是亮屏的。
- 验证方法（不用猜）：logcat 里 `UCar_UibcManager` 会打
  `origin point: (x, y), display size: (w, h), source size: (w, h), mapped point: (x, y)`，
  一眼就能看出坐标空间对不对。真机实测：
  `origin (330,360) → mapped (345,360)`、`origin (1030,360) → mapped (1077,360)`（×1600/1530）。
- 端到端判定「被投界面真的响应」：车机上左右滑动 → 手机 CarWith 界面在「地图页 ↔ 天气/步数页」
  之间来回切，且切回来时路名标签回到原位（可逆）—— 说明是车机端的触摸在驱动手机，
  而不是画面自己在变。

## 9. SDK 2.0.24（官方完整版）：jar 怎么抽、缺的厂商类怎么补

Car++ 现在用的 `app/libs/icar-sdk.jar` 是从**官方 ICCOA Carlink 车机端 App**
（`com.syncore.carlink` 2.0.24）里抽出来的，抽取与验证过程见
`~/项目/车机/iccoa-official-2.0.24/sdk-bundle/README.md`。

### 9.1 jar 里该放什么：按引用闭包抽，不能整包倒

把官方 APK 两个 dex 全量 `dex2jar` 后**不能**直接当 jar 用 —— 官方 App 自己带着
`com.google.android.material`(869 类)、`androidx.*`、`protobuf`，整包倒进去必然
`checkGithubDebugDuplicateClasses` 报一堆重复类。

正确做法是**从 SDK 的根包出发算引用闭包**（`com.ucar` + `com.share` + `com.ucarsink`），
把宿主已提供的包（`androidx/` `com/google/android/` `com/google/protobuf/` `kotlin/`
`com/google/firebase/` …）与厂商 App 层（`com/gxa/` 车控、`com/syncore/`）排除在外。
结果：**1,832 类 / 1.99 MB**（旧 jar 5.3 MB），自带 guava / gson / tink-subtle。

⚠ 解析 class 文件常量池时注意：`CONSTANT_Class` 存的是**裸类名**
（`com/google/crypto/tink/subtle/Hkdf`），**不是** dex 里的 `L…;` 描述符。
只按 `L([\w/$]+);` 匹配会漏掉真实调用 —— 实测差点把 SDK 加密握手要用的 tink 漏掉
（`com/ucar/vehiclesdk/c1` 用 `Hkdf.computeHkdf()` 做 ECDH 派生密钥）。

### 9.2 必须补的厂商垫片：`DeviceNameActivity`

新 SDK 在 **`UCarAdapter` 初始化阶段**（不是配对成功之后）就会调

```java
com.syncore.carlink.setting.DeviceNameActivity.resolveName(String)   // 静态方法
```

这个类住在厂商 App 里，官方 App 自带所以没事；我们只搬 SDK，运行到这一行直接

```
java.lang.NoClassDefFoundError: Failed resolution of: Lcom/syncore/carlink/setting/DeviceNameActivity;
  at com.ucar.vehiclesdk.s2.g() ← s2.a() ← UCarAdapter.e() ← UCarAdapter.a()
FATAL EXCEPTION: WorkerThread   → 进程当场死
```

旧版 SDK 里同一个调用不在启动路径上，所以换 2.0.24 才炸出来。
补法是**自己写一个同包同名的垫片类**：`sdk-iccoa/src/main/java/com/syncore/carlink/setting/DeviceNameActivity.kt`，
`class` + `companion object` + `@JvmStatic`（SDK 用 `invokestatic` 调，纯 `object` 或顶层函数
生成的字节码形态对不上），`resolveName` 原样返回入参、空则兜底官方默认名「我的爱车」。

已知同类厂商钩子（都在 SDK 里，Car++ 用不到但要知道）：
`com.gxa.syncoreoslinkadapter.SyncoreOsLinkV3Impl` → 回调 `com.syncore.space.caradapter.*`
（空调 / 车门 / 底盘车控），以及 SDK 里引用但**官方 App 也没带**的 `gxa.car.*`（音频 / 电源 /
硬按键）—— 那些路径在官方包上同样不存在，属于可选模块。

### 9.3 2.0.24 新增、需要在宿主侧登记的东西

- `com.ucar.vehiclesdk.MockActionReceiver`（`ucar.intent.action.NOTIFICATION_MOCK_ACTION`）：
  已按官方同款加进 `:sdk-iccoa` 的 manifest，并补了一条 signature 级
  `com.ucar.permission.UCAR_NOTIFICATION_RECEIVER`（官方只引用不声明，等于没人能调）。
- 原生库要跟着 jar 一起换版本：`libovmsink.so` / `libusbio.so` 由 SDK 内部
  `SinkNative` / `UsbNative` 各自 `System.loadLibrary` 加载，**jar 与 so 必须同批**，
  否则 JNI 注册的类名对不上。


